package com.lnvortex.client

import akka.actor.{ActorRef, ActorSystem}
import com.lnvortex.core._
import com.lnvortex.core.crypto.BlindSchnorrUtil
import com.lnvortex.core.crypto.BlindingTweaks.freshBlindingTweaks
import grizzled.slf4j.Logging
import org.bitcoins.commons.jsonmodels.lnd.UTXOResult
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt16
import org.bitcoins.core.policy.Policy
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

  private[client] var roundDetails: RoundDetails[_, _] = NoDetails

  private val knownVersions = Vector(UInt16.zero)

  private[client] def setRound(adv: MixAdvertisement): Unit = {
    if (knownVersions.contains(adv.version)) {
      roundDetails = KnownRound(adv)
    } else {
      throw new RuntimeException(
        s"Received unknown mix version ${adv.version.toInt}, consider updating software")
    }
  }

  lazy val peer: Peer = Peer(socket = config.coordinatorAddress,
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
    lndRpcClient.listUnspent.map { all =>
      all.filter(t => outpoints.contains(t.outPointOpt.get)).map { utxoRes =>
        val output = TransactionOutput(utxoRes.amount, utxoRes.spk)
        OutputReference(utxoRes.outPointOpt.get, output)
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

          selectedAmt = outputRefs.map(_.output.value).sum
          inputFees = Satoshis(outputRefs.size) * round.inputFee
          outputFees = Satoshis(2) * round.outputFee
          onChainFees = inputFees + outputFees
          changeAmt = selectedAmt - round.amount - round.mixFee - onChainFees

          _ = require(
            changeAmt > Policy.dustThreshold,
            s"Not enough coins selected, need ${changeAmt - Policy.dustThreshold} more")

          changeAddr <- lndRpcClient.getNewAddress
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

  def validateAndSignPsbt(unsigned: PSBT): Future[PSBT] = {
    roundDetails match {
      case state @ (NoDetails | _: KnownRound | _: InputsRegistered |
          _: PSBTSigned) =>
        sys.error(s"At invalid state $state, cannot validateAndSignPsbt")
      case state: MixOutputRegistered =>
        roundDetails = state.nextStage()
        val inputs = state.initDetails.inputs
        lazy val myOutpoints = inputs.map(_.outPoint)
        lazy val txOutpoints = unsigned.transaction.inputs.map(_.previousOutput)
        // should we care if they don't have our inputs?
        lazy val hasInputs = myOutpoints.forall(txOutpoints.contains)

        lazy val hasChangeOutput =
          unsigned.transaction.outputs.contains(state.initDetails.changeOutput)
        lazy val hasMixOutput =
          unsigned.transaction.outputs.contains(state.initDetails.mixOutput)

        if (hasMixOutput && hasChangeOutput && hasInputs) {
          val sigFs = inputs.map { input =>
            val idx = txOutpoints.indexOf(input.outPoint)
            lndRpcClient
              .computeInputScript(unsigned.transaction, idx, input.output)
              .map { case (scriptSig, witness) =>
                (scriptSig, witness, idx)
              }
          }

          val withInputInfo = inputs.foldLeft(unsigned) { case (psbt, outRef) =>
            val idx = txOutpoints.indexOf(outRef.outPoint)
            psbt.addWitnessUTXOToInput(outRef.output, idx)
          }

          Future.sequence(sigFs).map { sigs =>
            sigs.foldLeft(withInputInfo) {
              case (psbt, (scriptSig, witness, idx)) =>
                psbt.addFinalizedScriptWitnessToInput(scriptSig, witness, idx)
            }
          }
        } else {
          Future.failed(
            new RuntimeException(
              "Received PSBT did not contain our inputs or outputs"))
        }

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
        // todo publish tx to peer

        handlerP.future.map(_ ! AskMixAdvertisement)
    }
  }
}
