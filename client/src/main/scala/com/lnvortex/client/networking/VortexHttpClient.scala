package com.lnvortex.client.networking

import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.lnvortex.client.VortexClient
import com.lnvortex.core._
import com.lnvortex.core.api.VortexWalletApi
import org.bitcoins.asyncutil.AsyncUtil
import org.bitcoins.core.config.BitcoinNetwork
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto._
import org.bitcoins.tor.Socks5ClientTransport
import play.api.libs.json._

import java.net._
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Try

trait VortexHttpClient[+V <: VortexWalletApi] { self: VortexClient[V] =>

  private val baseUrl: String = {
    val addr = coordinatorAddress.address
    s"${addr.getHostString}:${addr.getPort}"
  }

  private val http = Http()

  private var registrationQueue: Option[
    (SourceQueueWithComplete[Message], Promise[Unit])] = None

  private var roundsSubscription: Option[Promise[Option[Nothing]]] = None

  private def createHttpConnectionPoolSettings(): ConnectionPoolSettings = {
    Socks5ClientTransport.createConnectionPoolSettings(
      new URI(s"http://$baseUrl"),
      config.socks5ProxyParams)
  }

  private lazy val httpConnectionPoolSettings =
    createHttpConnectionPoolSettings()

  private def sendRequest(
      request: HttpRequest,
      newConnection: Boolean): Future[String] = {
    val settings =
      if (newConnection) createHttpConnectionPoolSettings()
      else httpConnectionPoolSettings

    http
      .singleRequest(request, settings = settings)
      .flatMap(response =>
        response.entity.dataBytes
          .runFold(ByteString.empty)(_ ++ _))
      .map(payload => payload.decodeString(ByteString.UTF_8))
  }

  private def sendRequestAndParse[T](
      request: HttpRequest,
      newConnection: Boolean)(implicit reads: Reads[T]): Future[T] = {
    sendRequest(request, newConnection).map { str =>
      if (str.nonEmpty) {
        val json = Json.parse(str)
        json.validate[T] match {
          case JsSuccess(value, _) => value
          case JsError(errors) =>
            sys.error(
              s"Error parsing response, got $json\n${errors.mkString("\n")}")
        }
      } else {
        sys.error("Error: did not receive response")
      }
    }
  }

  def ping(): Future[Boolean] = {
    sendRequestAndParse[String](
      HttpRequest(uri = "http://" + baseUrl + "/ping"),
      newConnection = false).map(_ == "pong")
  }

  protected def getRoundParams(
      network: BitcoinNetwork): Future[RoundParameters] = {
    val request = Get(
      "http://" + baseUrl + "/params/" + network.chainParams.genesisBlock.blockHeader.hashBE.hex)

    sendRequestAndParse[RoundParameters](request, newConnection = false)
  }

  protected def subscribeRounds(network: BitcoinNetwork): Future[Unit] = {
    require(roundsSubscription.isEmpty, "Already subscribed to rounds")

    logger.info(s"Connecting to coordinator $baseUrl")

    val url =
      "ws://" + baseUrl + "/rounds/" + network.chainParams.genesisBlock.blockHeader.hashBE.hex

    val sink = roundsFlow().toMat(Sink.ignore)(Keep.right)

    val shutdownP = Promise[Unit]()
    val flow = Flow.fromSinkAndSourceMat(sink, Source.maybe)(Keep.right)
    val wsFlow = flow.watchTermination() { (promise, termination) =>
      termination.onComplete { _ => shutdownP.success(()) }
      promise
    }

    val (upgradeResponse, closedF) =
      http.singleWebSocketRequest(
        WebSocketRequest(url),
        wsFlow,
        settings = createHttpConnectionPoolSettings().connectionSettings)

    upgradeResponse.map {
      case _: ValidUpgrade =>
        logger.info("Subscribed to coordinator round announcements!")
        roundsSubscription = Some(closedF)

        shutdownP.future.foreach { _ =>
          logger.warn("Disconnected from coordinator!")
          if (roundsSubscription.isDefined) {
            roundsSubscription.foreach(_.trySuccess(None))
            roundsSubscription = None

            logger.info("Attempting to reconnect to coordinator")
            AsyncUtil.retryUntilSatisfiedF(
              () => subscribeRounds(network).map(_ => true).recover(_ => false),
              interval = 10.seconds,
              maxTries = 6000)
          } else roundsSubscription = None
        }
      case InvalidUpgradeResponse(response, cause) =>
        throw new RuntimeException(
          s"Connection failed ${response.status}: $cause")
    }
  }

  protected def registerOutput(msg: RegisterOutput): Future[Boolean] = {
    val request =
      Post("http://" + baseUrl + "/output", Json.toJson(msg).toString)
    sendRequestAndParse[Boolean](request, newConnection = true)
  }

  protected def cancelRegistration(
      msg: CancelRegistrationMessage): Future[Boolean] = {
    val request =
      Post("http://" + baseUrl + "/cancel", Json.toJson(msg).toString)

    // Need to do these synchronously so we get
    // the correct return from the coordinator
    for {
      response <- sendRequestAndParse[Boolean](request, newConnection = false)
      _ <- disconnectRegistration()
    } yield response
  }

  protected def startRegistration(roundId: DoubleSha256Digest): Future[Unit] = {
    require(registrationQueue.isEmpty, "Already registered")

    val url = "ws://" + baseUrl + "/register/" + roundId.hex

    val (queue, source) = Source
      .queue[Message](bufferSize = 10,
                      OverflowStrategy.backpressure,
                      maxConcurrentOffers = 2)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

    val sink = Sink.foreachAsync[Message](5) {
      case TextMessage.Strict(text) =>
        val vortexMessage = Try {
          Json.parse(text).as[VortexMessage]
        }.getOrElse {
          throw new RuntimeException(s"Could not parse json: $text")
        }
        vortexMessage match {
          case clientMessage: ClientVortexMessage =>
            logger.error(s"Received client message $clientMessage")
            Future.failed(
              new RuntimeException(s"Received client message $clientMessage"))
          case message: ServerVortexMessage =>
            handleVortexMessage(queue, message)
        }
      case streamed: TextMessage.Streamed =>
        streamed.textStream.runWith(Sink.ignore)
        Future.unit
      case bm: BinaryMessage =>
        bm.dataStream.runWith(Sink.ignore)
        logger.warn("Received unexpected message")
        Future.unit
    }

    val shutdownP = Promise[Unit]()

    val flow = Flow.fromSinkAndSourceMat(sink, source)(Keep.left)
    val wsFlow = flow.watchTermination() { (_, termination) =>
      termination.onComplete { _ =>
        shutdownP.success(())
      }
    }

    val (upgradeResponse, _) =
      http.singleWebSocketRequest(
        WebSocketRequest(url),
        wsFlow,
        settings = httpConnectionPoolSettings.connectionSettings)
    registrationQueue = Some((queue, shutdownP))

    upgradeResponse.map {
      case _: ValidUpgrade => ()
      case InvalidUpgradeResponse(response, cause) =>
        throw new RuntimeException(
          s"Connection failed ${response.status}: $cause")
    }
  }

  protected def disconnectRegistration(): Future[Unit] = {
    registrationQueue match {
      case Some((queue, closedP)) =>
        queue.offer(TextMessage("goodbye"))
        queue.complete()
        registrationQueue = None
        closedP.future
      case None => Future.unit
    }
  }

  protected def disconnect(): Future[Unit] = {
    val disconnectRoundsF = roundsSubscription match {
      case Some(value) =>
        value.trySuccess(None)
        roundsSubscription = None
        value.future
      case None => Future.unit
    }

    val disconnectRegistrationF = disconnectRegistration()

    for {
      _ <- disconnectRoundsF
      _ <- disconnectRegistrationF
    } yield ()
  }

  private def roundsFlow(): Flow[Message, Unit, Any] = {
    Flow[Message].mapAsync(FutureUtil.getParallelism) {
      case tm: TextMessage =>
        tm.toStrict(10.seconds).flatMap { msg =>
          val announcement = Try {
            Json.parse(msg.text).as[ServerAnnouncementMessage]
          }.getOrElse {
            throw new RuntimeException(s"Could not parse json: ${msg.text}")
          }

          announcement match {
            case round: RoundParameters =>
              logger.info(s"Received round details ${round.roundId.hex}")
              setRound(round)
            case FeeRateHint(feeRate) =>
              logger.info(s"Received fee rate hint $feeRate")
              updateFeeRate(feeRate)
              Future.unit
          }
        }
      case bm: BinaryMessage =>
        // ignore binary messages but drain content to avoid the stream being clogged
        bm.dataStream.runWith(Sink.ignore)
        Future.unit
    }
  }

  private def handleVortexMessage(
      queue: SourceQueueWithComplete[Message],
      message: ServerVortexMessage): Future[Unit] = {
    message match {
      case adv: RoundParameters =>
        logger.info(s"Received round details ${adv.roundId.hex}")
        setRound(adv)
      case FeeRateHint(feeRate) =>
        logger.info(s"Received fee rate hint $feeRate")
        updateFeeRate(feeRate)
        Future.unit
      case NonceMessage(schnorrNonce) =>
        storeNonce(schnorrNonce)
        Future.unit
      case AskInputs(roundId, inputFee, outputFee, changeOutputFee) =>
        logger.info("Received AskInputs from coordinator")
        for {
          registerInputs <- registerCoins(roundId,
                                          inputFee,
                                          outputFee,
                                          changeOutputFee)

          json = Json.toJson(registerInputs)
          _ <- queue.offer(TextMessage(json.toString))
        } yield logger.info("Sent RegisterInputs to coordinator")
      case BlindedSig(blindOutputSig) =>
        val msg = processBlindOutputSig(blindOutputSig)
        sendOutputMessageAsBob(msg)
      case UnsignedPsbtMessage(psbt) =>
        for {
          signed <- validateAndSignPsbt(psbt)
          json = Json.toJson(SignedPsbtMessage(signed))
          _ <- queue.offer(TextMessage(json.toString))
        } yield ()
      case SignedTxMessage(transaction) =>
        for {
          _ <- completeRound(transaction)
        } yield ()
      case msg: RestartRoundMessage =>
        restartRound(msg)
        Future.unit
    }
  }
}
