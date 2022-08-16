package com.lnvortex.config

import com.lnvortex.core._
import org.bitcoins.core.protocol.ln.node._
import com.lnvortex.core.api._
import com.lnvortex.core.crypto.BlindingTweaks
import org.bitcoins.commons.serializers.JsonSerializers._
import org.bitcoins.commons.serializers.JsonWriters._
import org.bitcoins.commons.serializers.JsonReaders._
import org.bitcoins.commons.serializers.Picklers.{
  transactionOutPointPickler => _,
  _
}
import org.bitcoins.commons.serializers.SerializerUtil
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.channel._
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.script.ScriptType
import org.bitcoins.crypto._
import play.api.libs.json._
import upickle.default._

import java.net.InetSocketAddress

object VortexPicklers {

  implicit val playJsRW: ReadWriter[JsValue] = {
    readwriter[String].bimap(_.toString, Json.parse)
  }

  implicit val schnorrNonceWrites: Writes[SchnorrNonce] =
    (c: SchnorrNonce) => JsString(c.hex)

  implicit val fieldElementWrites: Writes[FieldElement] =
    (c: FieldElement) => JsString(c.hex)

  implicit val NodeIdWrites: Writes[NodeId] =
    (c: NodeId) => JsString(c.hex)

  implicit val InetWrites: Writes[InetSocketAddress] =
    (c: InetSocketAddress) =>
      JsString(c.toString.replaceAll("/<unresolved>", ""))

  implicit val inetSocketAddressRW: ReadWriter[InetSocketAddress] =
    readwriter[String].bimap(
      addr => addr.toString.replaceAll("/<unresolved>", ""),
      str => {
        if (str.contains(":")) {
          val parts = str.split(":")
          InetSocketAddress.createUnresolved(parts(0), parts(1).toInt)
        } else {
          InetSocketAddress.createUnresolved(str, 9735)
        }
      }
    )

  implicit val currentUnitWrites: Writes[CurrencyUnit] =
    (c: CurrencyUnit) => JsNumber(c.satoshis.toLong)

  implicit val currentUnitReads: Reads[CurrencyUnit] = (js: JsValue) =>
    SerializerUtil.processJsNumber(n => Satoshis(n.toLong))(js)

  implicit val currencyUnitRW: ReadWriter[CurrencyUnit] = {
    readwriter[Long].bimap(_.satoshis.toLong, Satoshis(_))
  }

  implicit val uint16RW: ReadWriter[UInt16] = {
    readwriter[Long].bimap(_.toLong, UInt16(_))
  }

  implicit val UInt16Writes: Writes[UInt16] =
    (c: UInt16) => JsNumber(c.toLong)

  implicit val uint64RW: ReadWriter[UInt64] = {
    readwriter[Long].bimap(_.toLong, UInt64(_))
  }

  implicit val DoubleSha256DigestRW: ReadWriter[DoubleSha256Digest] = {
    readwriter[String].bimap(_.hex, DoubleSha256Digest.fromHex)
  }

  implicit val SchnorrPublicKeyRW: ReadWriter[SchnorrPublicKey] = {
    readwriter[String].bimap(_.hex, SchnorrPublicKey.fromHex)
  }

  implicit val SchnorrPublicKeyWrites: Writes[SchnorrPublicKey] = {
    (c: SchnorrPublicKey) => JsString(c.hex)
  }

  implicit val ShortChannelIdRW: ReadWriter[ShortChannelId] = {
    readwriter[String].bimap(_.toHumanReadableString,
                             ShortChannelId.fromHumanReadableString)
  }

  implicit val ShortChannelIdWrites: Writes[ShortChannelId] = {
    (c: ShortChannelId) => JsString(c.toHumanReadableString)
  }

  implicit val TransactionOutPointRW: ReadWriter[TransactionOutPoint] = {
    readwriter[String].bimap(_.toHumanReadableString,
                             TransactionOutPoint.fromString)
  }

  implicit val TransactionOutPointWrites: Writes[TransactionOutPoint] =
    (out: TransactionOutPoint) => JsString(out.toHumanReadableString)

  implicit val TransactionOutputWrites: Writes[TransactionOutput] =
    Json.writes[TransactionOutput]

  implicit val OutputReferenceWrites: Writes[OutputReference] =
    Json.writes[OutputReference]

  implicit val UTXOWarningWrites: Writes[UTXOWarning] =
    (out: UTXOWarning) => JsString(out.warning)

  implicit val UTXOWarningReads: Reads[UTXOWarning] = (js: JsValue) =>
    SerializerUtil.processJsStringOpt(n => UTXOWarning.fromStringOpt(n))(js)

  implicit val UTXOWarningRW: ReadWriter[UTXOWarning] =
    readwriter[String].bimap[UTXOWarning](
      warning => warning.warning,
      str => UTXOWarning.fromString(str)
    )

  // used from cli -> server
  implicit val unspentCoinRW: ReadWriter[UnspentCoin] = macroRW[UnspentCoin]

  // return from server
  implicit val unspentCoinReads: Reads[UnspentCoin] = Json.reads[UnspentCoin]

  implicit val unspentCoinWrites: OWrites[UnspentCoin] = (o: UnspentCoin) => {
    val original = Json.writes[UnspentCoin].writes(o)
    original ++ Json.obj(
      "scriptType" -> o.address.scriptPubKey.scriptType
    )
  }

  implicit val TransactionDetailsRW: ReadWriter[TransactionDetails] =
    macroRW[TransactionDetails]

  implicit val TransactionDetailsReads: Reads[TransactionDetails] =
    Json.reads[TransactionDetails]

  implicit val TransactionDetailsWrites: OWrites[TransactionDetails] =
    Json.writes[TransactionDetails]

  implicit val ChannelDetailsRW: ReadWriter[ChannelDetails] =
    macroRW[ChannelDetails]

  implicit val ChannelDetailsReads: Reads[ChannelDetails] =
    Json.reads[ChannelDetails]

  implicit val ChannelDetailsWrites: OWrites[ChannelDetails] =
    Json.writes[ChannelDetails]

  implicit val nodeIdPickler: ReadWriter[NodeId] = {
    readwriter[String].bimap(_.hex, NodeId.fromHex)
  }

  implicit val FieldElementRW: ReadWriter[FieldElement] = {
    readwriter[String].bimap(_.hex, FieldElement.fromHex)
  }

  implicit val BlindingTweaksRW: ReadWriter[BlindingTweaks] =
    macroRW[BlindingTweaks]

  implicit val BlindingTweaksWrites: OWrites[BlindingTweaks] =
    Json.writes[BlindingTweaks]

  implicit val InitDetailsWrites: OWrites[InitDetails] =
    Json.writes[InitDetails]

  implicit val scriptTypeWrites: Writes[ScriptType] = scriptType =>
    JsString(scriptType.toString)

  implicit val roundParametersWrites: OWrites[RoundParameters] =
    Json.writes[RoundParameters]

  implicit val RoundDetailsWrites: OWrites[RoundDetails] =
    (details: RoundDetails) => {
      val original = details match {
        case NoDetails            => JsObject(Vector.empty)
        case kr: KnownRound       => Json.writes[KnownRound].writes(kr)
        case rn: ReceivedNonce    => Json.writes[ReceivedNonce].writes(rn)
        case is: InputsScheduled  => Json.writes[InputsScheduled].writes(is)
        case ir: InputsRegistered => Json.writes[InputsRegistered].writes(ir)
        case or: OutputRegistered => Json.writes[OutputRegistered].writes(or)
        case ps: PSBTSigned       => Json.writes[PSBTSigned].writes(ps)
      }

      val extra = JsObject(
        Vector("status" -> JsString(details.status.toString)))

      original ++ extra
    }
}
