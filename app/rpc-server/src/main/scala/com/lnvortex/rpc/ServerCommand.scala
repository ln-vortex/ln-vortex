package com.lnvortex.rpc

import ujson._
import upickle.default._

case class ServerCommand(id: Long, method: String, params: Obj)

object ServerCommand {

  implicit val rw: ReadWriter[ServerCommand] =
    readwriter[ujson.Value].bimap[ServerCommand](
      cmd => {
        if (cmd.params.obj.isEmpty)
          Obj("id" -> Num(cmd.id.toDouble), "method" -> Str(cmd.method))
        else
          Obj("id" -> Num(cmd.id.toDouble),
              "method" -> Str(cmd.method),
              "params" -> cmd.params)
      },
      json => {
        val obj = json.obj
        val method = obj("method").str
        val id = obj("id").num.toLong
        if (obj.contains("params"))
          ServerCommand(id, method, obj("params").obj)
        else ServerCommand(id, method, Obj())
      }
    )
}
