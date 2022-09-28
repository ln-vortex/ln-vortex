package com.lnvortex.coordinator.rpc

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.clients.FutureSttpClient
import com.bot4s.telegram.future.TelegramBot
import com.lnvortex.core.VortexUtils
import com.lnvortex.server.models.RoundDb
import org.bitcoins.core.config.{MainNet, RegTest, SigNet, TestNet3}
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.util.StartStopAsync
import sttp.capabilities.akka.AkkaStreams
import sttp.client3.SttpBackend
import sttp.client3.akkahttp.AkkaHttpBackend

import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import scala.concurrent.Future

class TelegramHandler(telegramCreds: String, myTelegramId: String)(implicit
    config: CoordinatorRpcAppConfig,
    system: ActorSystem)
    extends TelegramBot
    with VortexUtils
    with StartStopAsync[Unit] {

  val intFormatter: NumberFormat = java.text.NumberFormat.getIntegerInstance

  val currencyFormatter: NumberFormat =
    java.text.NumberFormat.getCurrencyInstance(Locale.US)

  implicit private val backend: SttpBackend[Future, AkkaStreams] =
    AkkaHttpBackend.usingActorSystem(system)

  override val client: RequestHandler[Future] = new FutureSttpClient(
    telegramCreds)

  override def start(): Future[Unit] = {
    for {
      _ <- run()
      _ <- sendTelegramMessage("Connected!", myTelegramId)
    } yield ()
  }

  override def stop(): Future[Unit] = Future.unit

  def sendTelegramMessage(
      message: String,
      telegramId: String = myTelegramId): Future[Unit] = {
    val url = s"https://api.telegram.org/bot$telegramCreds/sendMessage" +
      s"?chat_id=${URLEncoder.encode(telegramId, "UTF-8")}" +
      s"&text=${URLEncoder.encode(message.trim, "UTF-8")}"

    Http().singleRequest(Get(url)).map(_ => ())
  }

  def roundSuccessMessage(roundDb: RoundDb): Future[Unit] = {
    val tx = roundDb.transactionOpt.get

    val mempoolLink = config.network match {
      case MainNet  => s"https://mempool.space/tx/${tx.txIdBE.hex}"
      case TestNet3 => s"https://mempool.space/testnet/tx/${tx.txIdBE.hex}"
      case SigNet   => s"https://mempool.space/signet/tx/${tx.txIdBE.hex}"
      case RegTest  => ""
    }

    val telegramMsg =
      s"""
         |🔔 🔔 New Vortex Transaction! 🔔 🔔
         |Coordinator: ${config.coordinatorConfig.coordinatorName}
         |
         |num inputs: ${tx.inputs.size}
         |num outputs: ${tx.outputs.size}
         |anon set: ${getMaxAnonymitySet(tx)}
         |profit: ${printAmount(roundDb.profitOpt.get)}
         |
         |$mempoolLink
         |""".stripMargin

    sendTelegramMessage(telegramMsg, myTelegramId)
  }

  private def printAmount(amount: CurrencyUnit): String = {
    intFormatter.format(amount.satoshis.toLong) + " sats"
  }
}
