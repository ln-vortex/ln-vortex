package com.lnvortex.coordinator.rpc

import com.danielasfregola.twitter4s.TwitterRestClient
import com.danielasfregola.twitter4s.entities.Tweet
import com.lnvortex.core.VortexUtils
import com.lnvortex.server.models.RoundDb
import grizzled.slf4j.Logging
import org.bitcoins.core.config._

import scala.concurrent.Future

class TwitterHandler(client: TwitterRestClient)(implicit
    config: CoordinatorRpcAppConfig)
    extends Logging
    with VortexUtils {

  private def sendTweet(message: String): Future[Tweet] = {
    client.createTweet(status = message.trim)
  }

  def roundSuccessTweet(roundDb: RoundDb): Future[Tweet] = {
    val tx = roundDb.transactionOpt.get

    val mempoolLink = config.network match {
      case MainNet  => s"https://mempool.space/tx/${tx.txIdBE.hex}"
      case TestNet3 => s"https://mempool.space/testnet/tx/${tx.txIdBE.hex}"
      case SigNet   => s"https://mempool.space/signet/tx/${tx.txIdBE.hex}"
      case RegTest  => ""
    }

    val tweet =
      s"""⚡ 🌪️ New Vortex Transaction! 🌪️ ⚡
         |Coordinator: ${config.coordinatorConfig.coordinatorName}
         |
         |Inputs: ${tx.inputs.size}
         |Outputs: ${tx.outputs.size}
         |Anonymity set: ${getMaxAnonymitySet(tx)}
         |
         |$mempoolLink
         |""".stripMargin

    sendTweet(tweet)
  }
}
