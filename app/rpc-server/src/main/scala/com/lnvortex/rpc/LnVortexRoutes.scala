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
    case ServerCommand("listutxos", _) =>
      complete {
        client.listCoins().map { utxos =>
          val json = upickle.default.writeJs(utxos)
          RpcServer.httpSuccess(json)
        }
      }

    case ServerCommand("getbalance", _) =>
      complete {
        client.listCoins().map { utxos =>
          val balance = utxos.map(_.amount).sum.satoshis
          RpcServer.httpSuccess(balance.toLong)
        }
      }

    case ServerCommand("listtransactions", _) =>
      complete {
        client.vortexWallet.listTransactions().map { txs =>
          RpcServer.httpSuccess(txs)
        }
      }

    case ServerCommand("listchannels", _) =>
      complete {
        client.vortexWallet.listChannels().map { channels =>
          RpcServer.httpSuccess(channels)
        }
      }

    case ServerCommand("queuecoins", arr) =>
      withValidServerCommand(QueueCoins.fromJsArr(arr)) {
        case QueueCoins(outpoints, nodeId, peerAddrOpt) =>
          complete {
            client.queueCoins(outpoints, nodeId, peerAddrOpt).map { _ =>
              RpcServer.httpSuccess(Null)
            }
          }
      }
  }
}
