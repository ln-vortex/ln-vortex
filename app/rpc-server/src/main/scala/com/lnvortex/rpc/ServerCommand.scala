package com.lnvortex.rpc

import ujson._
import upickle.default._

case class ServerCommand(
    id: Either[String, Long],
    method: String,
    params: Obj) {

  lazy val idJs: Value with Serializable = id match {
    case Left(value)  => Str(value)
    case Right(value) => Num(value.toDouble)
  }
}

object ServerCommand {

  implicit val rw: ReadWriter[ServerCommand] =
    readwriter[ujson.Value].bimap[ServerCommand](
      cmd => {
        if (cmd.params.obj.isEmpty)
          Obj("id" -> cmd.idJs, "method" -> Str(cmd.method))
        else
          Obj("id" -> cmd.idJs,
              "method" -> Str(cmd.method),
              "params" -> cmd.params)
      },
      json => {
        val obj = json.obj
        val method = obj("method").str
        val id = obj("id") match {
          case Str(value)                       => Left(value)
          case Num(value)                       => Right(value.toLong)
          case Obj(_) | Arr(_) | _: Bool | Null => Right(0L)
        }
        if (obj.contains("params"))
          ServerCommand(id, method, obj("params").obj)
        else ServerCommand(id, method, Obj())
      }
    )
}
