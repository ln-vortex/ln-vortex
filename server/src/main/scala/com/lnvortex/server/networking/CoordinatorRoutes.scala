package com.lnvortex.server.networking

import akka.actor.ActorSystem
import com.lnvortex.server.coordinator._
import grizzled.slf4j.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream._
import akka.stream.scaladsl._
import com.lnvortex.core._
import org.bitcoins.core.config._
import org.bitcoins.crypto._
import play.api.libs.json._

import scala.concurrent._
import scala.util._

class CoordinatorRoutes(var coordinator: VortexCoordinator)(implicit
    system: ActorSystem)
    extends Logging {
  implicit val ec: ExecutionContext = system.dispatcher

  // Need to switch coordinator when given a new one
  def prepareNextCoordinator(): Unit = {
    coordinator.getNextCoordinator.map { nextCoordinator =>
      coordinator = nextCoordinator
      prepareNextCoordinator()
    }
    ()
  }

  prepareNextCoordinator()

  private val pingRoute = path("ping") {
    get {
      complete {
        jsonResponse("pong")
      }
    }
  }

  private val paramsRoute = path("params" / Segment) { seg =>
    get {
      val currentNetwork = coordinator.config.network
      val hash = DoubleSha256DigestBE(seg)
      Try(Networks.fromChainHash(hash)) match {
        case Success(network) =>
          if (currentNetwork == network) {
            complete(jsonResponse(coordinator.roundParams))
          } else {
            val rejection = ValidationRejection(
              s"Network ${network.name} is not the current network ${currentNetwork.name}")
            reject(rejection)
          }
        case Failure(ex) =>
          val rejection = ValidationRejection(
            s"Could not parse to network from hash ${hash.hex}, currentNetwork: ${currentNetwork.name}",
            Some(ex))
          reject(rejection)
      }
    }
  }

  private val outputRoute = path("output") {
    post {
      entity(as[String]) { body =>
        val outputMsgT = Try {
          Json.parse(body).as[RegisterOutput]
        }

        outputMsgT match {
          case Failure(_) =>
            val err =
              new IllegalArgumentException(s"Could not parse json: $body")
            logger.error(s"Received invalid bob output from peer: $body", err)
            val rejection = ValidationRejection(
              s"Received invalid bob output from peer: $body")
            reject(rejection)
          case Success(outputMsg) =>
            complete {
              coordinator
                .verifyAndRegisterBob(outputMsg)
                .map { _ =>
                  jsonResponse(true)
                }
                .recover(_ => jsonResponse(false))
            }
        }
      }
    }
  }

  private val cancelRoute = path("cancel") {
    post {
      entity(as[String]) { body =>
        val cancelMsgT = Try {
          Json.parse(body).as[CancelRegistrationMessage]
        }

        cancelMsgT match {
          case Failure(_) =>
            val err =
              new IllegalArgumentException(s"Could not parse json: $body")
            logger.error(
              s"Received invalid cancel registration from peer: $body",
              err)
            val rejection = ValidationRejection(
              s"Received invalid cancel registration from peer: $body")
            reject(rejection)
          case Success(cancel) =>
            if (cancel.roundId != coordinator.getCurrentRoundId) {
              val rejection = ValidationRejection(
                s"Tried to cancel registration for round ${cancel.roundId} but current round is ${coordinator.getCurrentRoundId}")
              reject(rejection)
            } else {
              complete {
                coordinator
                  .cancelRegistration(Left(cancel.nonce), cancel.roundId)
                  .map { _ =>
                    jsonResponse(true)
                  }
                  .recover { ex =>
                    logger.error(s"Could not cancel registration: ", ex)
                    jsonResponse(false)
                  }
              }
            }
        }
      }
    }
  }

  private def handleVortexMessage(
      peerId: Sha256Digest,
      message: ClientVortexMessage): Future[Unit] = {
    message match {
      case inputs: RegisterInputs =>
        coordinator.registerAlice(peerId, inputs).map(_ => ())
      case bob: RegisterOutput =>
        coordinator.verifyAndRegisterBob(bob)
      case SignedPsbtMessage(psbt) =>
        coordinator.registerPSBTSignatures(peerId, psbt).map(_ => ())
      case cancel: CancelRegistrationMessage =>
        coordinator.cancelRegistration(Left(cancel.nonce), cancel.roundId)
    }
  }

  private val registerWebsocketRoute =
    path("register" / Segment) { seg =>
      val peerId = CryptoUtil.sha256(ECPrivateKey.freshPrivateKey.bytes)
      val roundId = DoubleSha256Digest(seg)

      extractWebSocketUpgrade { ws =>
        val (queue, source) = Source
          .queue[Message](bufferSize = 10,
                          OverflowStrategy.backpressure,
                          maxConcurrentOffers = 2)
          .toMat(BroadcastHub.sink)(Keep.both)
          .run()

        val parallelism = coordinator.config.maxPeers * 2
        val sink = Sink.foreachAsync[Message](parallelism) {
          case TextMessage.Strict("goodbye") =>
            queue.complete()
            Future.unit
          case TextMessage.Strict(text) =>
            val vortexMessage = Try {
              Json.parse(text).as[VortexMessage]
            }.getOrElse {
              throw new RuntimeException(s"Could not parse json: $text")
            }
            vortexMessage match {
              case message: ClientVortexMessage =>
                handleVortexMessage(peerId, message)
              case message: ServerVortexMessage =>
                logger.error(s"Received server message $message")
                Future.failed(
                  new RuntimeException(s"Received server message $message"))
            }
          case streamed: TextMessage.Streamed =>
            streamed.textStream.runWith(Sink.ignore)
            Future.unit
          case bm: BinaryMessage =>
            bm.dataStream.runWith(Sink.ignore)
            logger.warn("Received unexpected message")
            Future.unit
        }

        complete {
          // queue up sending nonce to client
          for {
            aliceDb <- coordinator.getNonce(peerId = peerId,
                                            connectionHandler = queue,
                                            peerRoundId = roundId)

            nonceMsg = NonceMessage(aliceDb.nonce)
            _ <- queue.offer(TextMessage(Json.toJson(nonceMsg).toString))
          } yield {
            val flow = Flow.fromSinkAndSource(sink, source)
            val wsFlow = flow.watchTermination() { (_, termination) =>
              termination.onComplete { _ =>
                coordinator.cancelRegistration(Right(peerId), roundId)
              }
            }

            logger.info(
              s"Peer ${peerId.hex} connected to round ${roundId.hex}!")

            ws.handleMessages(wsFlow)
          }
        }
      }
    }

  private val roundStatusWebsocketRoute = {
    path("rounds" / Segment) { seg =>
      val currentNetwork = coordinator.config.network
      val hash = DoubleSha256DigestBE(seg)
      Try(Networks.fromChainHash(hash)) match {
        case Success(network) =>
          if (currentNetwork == network) {

            extractWebSocketUpgrade { ws =>
              val (queue, source) = Source
                .queue[Message](bufferSize = 10,
                                OverflowStrategy.backpressure,
                                maxConcurrentOffers = 2)
                .toMat(BroadcastHub.sink)(Keep.both)
                .run()

              // send current round status to client
              coordinator.roundSubscribers += queue
              queue.offer(
                TextMessage(Json.toJson(coordinator.roundParams).toString))

              val flow = Flow.fromSinkAndSource(Sink.ignore, source)
              val wsFlow = flow.watchTermination() { (_, termination) =>
                termination.onComplete { _ =>
                  coordinator.roundSubscribers -= queue
                }
              }

              complete(ws.handleMessages(wsFlow))
            }
          } else {
            val rejection = ValidationRejection(
              s"Network ${network.name} is not the current network ${currentNetwork.name}")
            reject(rejection)
          }
        case Failure(ex) =>
          val rejection = ValidationRejection(
            s"Could not parse to network from hash ${hash.hex}, currentNetwork: ${currentNetwork.name}",
            Some(ex))
          reject(rejection)
      }
    }
  }

  lazy val topLevelRoute: Route =
    concat(
      pingRoute,
      paramsRoute,
      outputRoute,
      cancelRoute,
      roundStatusWebsocketRoute,
      registerWebsocketRoute
    )

  def jsonResponse[T](body: T)(implicit
      writes: Writes[T]): HttpEntity.Strict = {
    HttpEntity(
      `application/json`,
      writes.writes(body).toString
    )
  }
}
