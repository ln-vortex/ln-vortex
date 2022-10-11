package com.lnvortex.rpc

import play.api.libs.json._
import ujson._

case class ServerCommand(
    id: Either[String, Long],
    method: String,
    params: JsObject) {

  lazy val idJs: Value with Serializable = id match {
    case Left(value)  => Str(value)
    case Right(value) => Num(value.toDouble)
  }
}

object ServerCommand {

  implicit val reads: Reads[ServerCommand] = Reads[ServerCommand] { js =>
    for {
      idJs <- (js \ "id").validate[JsValue]
      id = idJs match {
        case JsNumber(value) => Right(value.toLong)
        case JsString(value) => Left(value)
        case JsNull | _: JsBoolean | _: JsArray | _: JsObject =>
          throw new RuntimeException("Invalid id json")
      }

      method <- (js \ "method").validate[String]
      params <- (js \ "params")
        .validateOpt[JsObject]
        .map(_.getOrElse(JsObject.empty))
    } yield {
      ServerCommand(id, method, params)
    }
  }
}
