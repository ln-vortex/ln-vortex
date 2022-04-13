package com.lnvortex.rpc

import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts._
import org.bitcoins.core.api.wallet.CoinSelectionAlgo
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.TimeUtil
import ujson._

import java.net.InetSocketAddress
import java.time.Instant
import scala.util._
import scala.util.control.NonFatal

case class QueueCoins(
    outpoints: Vector[TransactionOutPoint],
    nodeId: NodeId,
    peerAddrOpt: Option[InetSocketAddress])

object QueueCoins extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[QueueCoins] = {
    jsArr.arr.toList match {
      case outpointsJs :: nodeIdJs :: peerAddrOptJs :: Nil =>
        Try {
          val outpoints = jsToTransactionOutPointSeq(outpointsJs).toVector
          val nodeId = NodeId(nodeIdJs.str)
          val peerOpt = nullToOpt(peerAddrOptJs).map(js =>
            new InetSocketAddress(js.str, 9735))

          QueueCoins(outpoints, nodeId, peerOpt)
        }
      case Nil =>
        Failure(
          new IllegalArgumentException(
            "Missing outpoints and nodeId arguments"))
      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 2 or 3"))
    }
  }
}

trait ServerJsonModels {

  def jsToBitcoinAddress(js: Value): BitcoinAddress = {
    try {
      BitcoinAddress.fromString(js.str)
    } catch {
      case _: IllegalArgumentException =>
        throw Value.InvalidData(js, "Expected a valid address")
    }
  }

  def jsToPSBTSeq(js: Value): Seq[PSBT] = {
    js.arr.foldLeft(Seq.empty[PSBT])((seq, psbt) => seq :+ jsToPSBT(psbt))
  }

  def jsToPSBT(js: Value): PSBT = PSBT.fromString(js.str)

  def jsToTransactionOutPointSeq(js: Value): Seq[TransactionOutPoint] = {
    js.arr.foldLeft(Seq.empty[TransactionOutPoint])((seq, outPoint) =>
      seq :+ jsToTransactionOutPoint(outPoint))
  }

  def jsToTransactionOutPoint(js: Value): TransactionOutPoint =
    TransactionOutPoint(js.str)

  def jsToLockUnspentOutputParameter(js: Value): LockUnspentOutputParameter =
    LockUnspentOutputParameter.fromJson(js)

  def jsToLockUnspentOutputParameters(
      js: Value): Seq[LockUnspentOutputParameter] = {
    js.arr.foldLeft(Seq.empty[LockUnspentOutputParameter])((seq, outPoint) =>
      seq :+ jsToLockUnspentOutputParameter(outPoint))
  }

  def jsToCoinSelectionAlgo(js: Value): CoinSelectionAlgo =
    CoinSelectionAlgo
      .fromString(js.str)

  def jsToTx(js: Value): Transaction = Transaction.fromHex(js.str)

  def jsISOtoInstant(js: Value): Instant = {
    try {
      js match {
        case Str(str) =>
          val date = TimeUtil.iso8601ToDate(str)
          date.toInstant
        case Null | Obj(_) | Arr(_) | _: Bool | _: Num =>
          throw new Exception
      }
    } catch {
      case NonFatal(_) =>
        throw Value.InvalidData(js, "Expected a date given in ISO 8601 format")
    }
  }

  def nullToOpt(value: Value): Option[Value] =
    value match {
      case Null                      => None
      case Arr(arr) if arr.isEmpty   => None
      case Arr(arr) if arr.size == 1 => Some(arr.head)
      case _: Value                  => Some(value)
    }
}
