package com.lnvortex.rpc

import com.lnvortex.config.VortexPicklers._
import org.bitcoins.commons.serializers.Picklers.bitcoinAddressPickler
import org.bitcoins.commons.jsonmodels.bitcoind.RpcOpts._
import org.bitcoins.core.api.wallet.CoinSelectionAlgo
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.ln.node.NodeId
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.TimeUtil
import ujson._
import upickle.{default => up}
import upickle.default._

import java.net.InetSocketAddress
import java.time.Instant
import scala.util._
import scala.util.control.NonFatal

case class SelectCoordinator(coordinator: String)

object SelectCoordinator extends ServerJsonModels {

  implicit val SelectCoordinatorRW: Reader[SelectCoordinator] =
    reader[ujson.Obj].map[SelectCoordinator](json =>
      SelectCoordinator(
        json("coordinator").str
      ))

  def fromJsObj(obj: ujson.Obj): Try[SelectCoordinator] = {
    Try(upickle.default.read[SelectCoordinator](obj))
  }
}

case class QueueCoins(
    coordinator: String,
    outpoints: Vector[TransactionOutPoint],
    address: Option[BitcoinAddress],
    nodeId: Option[NodeId],
    peerAddr: Option[InetSocketAddress]) {

  require(!(address.isDefined && nodeId.isDefined),
          "Cannot have nodeId and address set")
}

object QueueCoins extends ServerJsonModels {

  implicit val queueCoinsRW: Reader[QueueCoins] =
    reader[ujson.Obj].map[QueueCoins](json =>
      QueueCoins(
        json("coordinator").str,
        up.read[Vector[TransactionOutPoint]](json("outpoints")),
        if (json.obj.contains("address")) {
          val res = Try(up.read[BitcoinAddress](json("address"))).getOrElse(
            throw new IllegalArgumentException(
              "Invalid address: " + json("address")))

          Some(res)
        } else None,
        if (json.obj.contains("nodeId")) {
          val res = Try(up.read[NodeId](json("nodeId"))).getOrElse(
            throw new IllegalArgumentException(
              "Invalid nodeId: " + json("nodeId")))

          Some(res)
        } else None,
        if (json.obj.contains("peerAddr")) {
          if (json("peerAddr").strOpt.exists(_.isEmpty)) {
            None
          } else {
            val res =
              Try(up.read[InetSocketAddress](json("peerAddr"))).getOrElse(
                throw new IllegalArgumentException(
                  "Invalid peerAddr: " + json("peerAddr")))

            Some(res)
          }
        } else None
      ))

  def fromJsObj(obj: ujson.Obj): Try[QueueCoins] = {
    Try(upickle.default.read[QueueCoins](obj))
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
