package com.lnvortex.rpc

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.lnvortex.client.VortexClient
import com.lnvortex.config.VortexPicklers._
import com.lnvortex.core.api.VortexWalletApi
import play.api.libs.json._

import scala.concurrent._

case class LnVortexRoutes(client: VortexClient[VortexWalletApi])(implicit
    system: ActorSystem)
    extends ServerRoute {
  implicit val ec: ExecutionContext = system.dispatcher

  override def handleCommand: PartialFunction[ServerCommand, Route] = {
    case ServerCommand(id, "listutxos", _) =>
      complete {
        client.listCoins().map { utxos =>
          RpcServer.httpSuccess(id, utxos)
        }
      }

    case ServerCommand(id, "getinfo", _) =>
      complete {
        val json = JsObject(
          Vector(
            "network" -> JsString(client.vortexWallet.network.toString)
          ))

        RpcServer.httpSuccess(id, json)
      }

    case ServerCommand(id, "getbalance", _) =>
      complete {
        client.listCoins().map { utxos =>
          val balance = utxos.map(_.amount).sum.satoshis
          RpcServer.httpSuccess(id, balance.toLong)
        }
      }

    case ServerCommand(id, "listtransactions", _) =>
      complete {
        client.vortexWallet.listTransactions().map { txs =>
          RpcServer.httpSuccess(id, txs)
        }
      }

    case ServerCommand(id, "listchannels", _) =>
      complete {
        client.vortexWallet.listChannels().map { channels =>
          RpcServer.httpSuccess(id, channels)
        }
      }

    case ServerCommand(id, "getstatus", _) =>
      complete {
        val details = client.getCurrentRoundDetails
        RpcServer.httpSuccess(id, details)
      }

    case ServerCommand(id, "cancelcoins", _) =>
      complete {
        client.cancelRegistration().map { _ =>
          RpcServer.httpSuccess(id, JsNull)
        }
      }

    case ServerCommand(id, "queuecoins", obj) =>
      withValidServerCommand(QueueCoins.fromJsObj(obj)) {
        case QueueCoins(outpoints, addrOpt, nodeIdOpt, peerAddrOpt) =>
          complete {
            val f = client.askNonce().flatMap { _ =>
              (addrOpt, nodeIdOpt) match {
                case (Some(_), Some(_)) =>
                  throw new IllegalArgumentException(
                    "Cannot have both nodeId and address")
                case (Some(addr), None) =>
                  client.queueCoins(outpoints, addr)
                case (None, Some(nodeId)) =>
                  client.queueCoins(outpoints, nodeId, peerAddrOpt)
                case (None, None) =>
                  for {
                    addr <- client.vortexWallet.getNewAddress()
                    _ <- client.queueCoins(outpoints, addr)
                  } yield ()
              }
            }

            f.map(_ => RpcServer.httpSuccess(id, JsNull))
          }
      }
  }
}
