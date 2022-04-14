package com.lnvortex.rpc

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import com.lnvortex.client.VortexClient
import com.lnvortex.config.Picklers._
import com.lnvortex.core.api.VortexWalletApi
import ujson._

import scala.concurrent._

case class LnVortexRoutes(client: VortexClient[VortexWalletApi])(implicit
    system: ActorSystem)
    extends ServerRoute {
  implicit val ec: ExecutionContext = system.dispatcher

  override def handleCommand: PartialFunction[ServerCommand, Route] = {
    case ServerCommand(id, "listutxos", _) =>
      complete {
        client.listCoins().map { utxos =>
          val json = upickle.default.writeJs(utxos)
          RpcServer.httpSuccess(id, json)
        }
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

    case ServerCommand(id, "queuecoins", obj) =>
      withValidServerCommand(QueueCoins.fromJsObj(obj)) {
        case QueueCoins(outpoints, nodeId, peerAddrOpt) =>
          complete {
            client.queueCoins(outpoints, nodeId, peerAddrOpt).map { _ =>
              RpcServer.httpSuccess(id, Null)
            }
          }
      }
  }
}
