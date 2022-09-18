package com.lnvortex.rpc

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.lnvortex.client._
import com.lnvortex.config.VortexPicklers._
import com.lnvortex.core.RoundDetails.getRoundParamsOpt
import com.lnvortex.core.api.VortexWalletApi
import play.api.libs.json._

import scala.concurrent._

case class LnVortexRoutes(clientManager: VortexClientManager[VortexWalletApi])(
    implicit system: ActorSystem)
    extends ServerRoute {
  implicit val ec: ExecutionContext = system.dispatcher

  def getClient(coordinator: String): VortexClient[VortexWalletApi] = {
    clientManager.clientMap(coordinator)
  }

  override def handleCommand: PartialFunction[ServerCommand, Route] = {
    case ServerCommand(id, "listutxos", _) =>
      complete {
        clientManager.listCoins().map { utxos =>
          RpcServer.httpSuccess(id, utxos)
        }
      }

    case ServerCommand(id, "getinfo", _) =>
      complete {
        val json = Json.obj(
          "network" -> clientManager.network.toString,
          "coordinators" -> clientManager.coordinators.map(_.name)
        )

        RpcServer.httpSuccess(id, json)
      }

    case ServerCommand(id, "getbalance", _) =>
      complete {
        clientManager.listCoins().map { utxos =>
          val balance = utxos.map(_.amount).sum.satoshis
          RpcServer.httpSuccess(id, balance.toLong)
        }
      }

    case ServerCommand(id, "listtransactions", _) =>
      complete {
        clientManager.listTransactions().map { txs =>
          RpcServer.httpSuccess(id, txs)
        }
      }

    case ServerCommand(id, "listchannels", _) =>
      complete {
        clientManager.listChannels().map { channels =>
          RpcServer.httpSuccess(id, channels)
        }
      }

    case ServerCommand(id, "getstatuses", _) =>
      complete {
        val allDetails =
          clientManager.clients.map { client =>
            client.coordinatorAddress.name -> client.getCurrentRoundDetails
          }
        RpcServer.httpSuccess(id, allDetails)
      }

    case ServerCommand(id, "getstatus", obj) =>
      withValidServerCommand(SelectCoordinator.fromJsObj(obj)) {
        case SelectCoordinator(coordinator) =>
          complete {
            val client = getClient(coordinator)
            val details = client.getCurrentRoundDetails
            RpcServer.httpSuccess(id, details)
          }
      }

    case ServerCommand(id, "cancelcoins", obj) =>
      withValidServerCommand(SelectCoordinator.fromJsObj(obj)) {
        case SelectCoordinator(coordinator) =>
          complete {
            val client = getClient(coordinator)
            client.cancelRegistration().map { _ =>
              RpcServer.httpSuccess(id, JsNull)
            }
          }
      }

    case ServerCommand(id, "queuecoins", obj) =>
      withValidServerCommand(QueueCoins.fromJsObj(obj)) {
        case QueueCoins(coordinator,
                        outpoints,
                        addrOpt,
                        nodeIdOpt,
                        peerAddrOpt) =>
          complete {
            val client = getClient(coordinator)
            val f = client.askNonce().flatMap { _ =>
              (addrOpt, nodeIdOpt) match {
                case (Some(_), Some(_)) =>
                  throw new IllegalArgumentException(
                    "Cannot have both nodeId and address")
                case (Some(addr), None) =>
                  client.getCoinsAndQueue(outpoints, addr)
                case (None, Some(nodeId)) =>
                  client.getCoinsAndQueue(outpoints, nodeId, peerAddrOpt)
                case (None, None) =>
                  for {
                    addr <- client.vortexWallet.getNewAddress(getRoundParamsOpt(
                      client.getCurrentRoundDetails).get.outputType)
                    _ <- client.getCoinsAndQueue(outpoints, addr)
                  } yield ()
              }
            }

            f.recoverWith { case ex: Throwable =>
              client.cancelRegistration().recover(_ => ())
              Future.failed(ex)
            }.map(_ => RpcServer.httpSuccess(id, JsNull))
          }
      }
  }
}
