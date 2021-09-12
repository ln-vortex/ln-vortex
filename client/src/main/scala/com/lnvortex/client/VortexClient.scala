package com.lnvortex.client

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import grizzled.slf4j.Logging
import org.bitcoins.commons.jsonmodels.lnd.UTXOResult
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.StartStopAsync
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndRpcClient

import scala.concurrent.{Future, Promise}

case class VortexClient(lndRpcClient: LndRpcClient)(implicit
    system: ActorSystem,
    config: VortexAppConfig)
    extends StartStopAsync[Unit]
    with Logging {
  import system.dispatcher

  private val handlerP = Promise[ActorRef]()

  private var roundDetails: RoundDetails[_, _] = NoDetails

  private[client] def setRound(adv: MixAdvertisement): Unit = {
    roundDetails = KnownRound(adv)
  }

  val peer: Peer =
    Peer(socket = config.coordinatorAddress,
         socks5ProxyParams = config.socks5ProxyParams)

  override def start(): Future[Unit] = {
    for {
      _ <- P2PClient.connect(peer, this, Some(handlerP))
      handler <- handlerP.future
    } yield handler ! AskMixAdvertisement
  }

  override def stop(): Future[Unit] = {
    handlerP.future.map { handler =>
      system.stop(handler)
    }
  }

  def listCoins(): Future[Vector[UTXOResult]] = {
    lndRpcClient.listUnspent
  }

  def getOutputReferences(outpoints: Vector[TransactionOutPoint]): Future[
    Vector[OutputReference]] = {
    val txIds = outpoints.map(_.txIdBE)
    lndRpcClient.getTransactions.map { all =>
      val detailsMap =
        all.filter(tx => txIds.contains(tx.txId)).map(d => (d.txId, d)).toMap

      outpoints.map { outpoint =>
        val details = detailsMap(outpoint.txIdBE)
        val output = details.tx.outputs(outpoint.vout.toInt)

        OutputReference(outpoint, output)
      }
    }
  }

  def registerCoins(outpoints: Vector[TransactionOutPoint]): Future[Unit] = {
    roundDetails match {
      case state @ (NoDetails | _: InitializedRound[_]) =>
        sys.error(s"At invalid state $state, cannot register coins")
      case knownRound: KnownRound =>
        val round = knownRound.round
        for {
          handler <- handlerP.future
          outputRefs <- getOutputReferences(outpoints)

          changeAddr <- lndRpcClient.getNewAddress
          // todo add on chain fees
          changeAmt = outputRefs
            .map(_.output.value)
            .sum - round.amount - round.fee
          changeOutput = TransactionOutput(changeAmt, changeAddr.scriptPubKey)

          // todo negotiate channel, get output, hash it
          mixOutput = EmptyTransactionOutput
          hashedOutput = CryptoUtil.sha256(mixOutput.bytes).bytes

          tweaks = freshBlindingTweaks(signerPubKey = round.publicKey,
                                       signerNonce = round.nonce)
          challenge = BlindSchnorrUtil.generateChallenge(
            signerPubKey = round.publicKey,
            signerNonce = round.nonce,
            blindingTweaks = tweaks,
            message = hashedOutput)
        } yield {
          val details = InitDetails(outputRefs, changeOutput, mixOutput, tweaks)
          roundDetails = knownRound.nextStage(details)

          handler ! AliceInit(outputRefs, challenge, changeOutput)
        }
    }
  }

  def processAliceInitResponse(blindOutputSig: FieldElement): Future[Unit] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: MixOutputRegistered |
          _: PSBTSigned) =>
        sys.error(s"At invalid state $state, cannot processAliceInitResponse")
      case details: InputsRegistered =>
        val mixOutput = details.initDetails.mixOutput
        val publicKey = details.round.publicKey
        val nonce = details.round.nonce
        val tweaks = details.initDetails.tweaks

        val challenge = CryptoUtil.sha256(mixOutput.bytes).bytes
        val sig = BlindSchnorrUtil.unblindSignature(blindSig = blindOutputSig,
                                                    signerPubKey = publicKey,
                                                    signerNonce = nonce,
                                                    blindingTweaks = tweaks,
                                                    message = challenge)

        val bobHandlerP = Promise[ActorRef]()

        for {
          _ <- P2PClient.connect(peer, this, Some(bobHandlerP))
          bobHandler <- bobHandlerP.future
        } yield {
          roundDetails = details.nextStage()
          bobHandler ! BobMessage(sig, mixOutput)
        }
    }
  }

  def validateAndSignPsbt(psbt: PSBT): Future[PSBT] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InputsRegistered |
          _: PSBTSigned) =>
        sys.error(s"At invalid state $state, cannot validateAndSignPsbt")
      case state: MixOutputRegistered =>
        roundDetails = state.nextStage()

        // todo validate psbt has our outputs
        lndRpcClient.finalizePSBT(psbt)
    }
  }

  def completeRound(signedTx: Transaction): Future[Unit] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InputsRegistered |
          _: MixOutputRegistered) =>
        sys.error(s"At invalid state $state, cannot processAliceInitResponse")
      case state: PSBTSigned =>
        roundDetails = state.nextStage()
        println(signedTx)

        Future.unit
    }
  }
}
