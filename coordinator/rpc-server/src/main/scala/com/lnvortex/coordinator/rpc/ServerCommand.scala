package com.lnvortex.coordinator.rpc

import play.api.libs.json._

case class ServerCommand(
    id: Either[String, Long],
    method: String,
    params: JsObject)

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
