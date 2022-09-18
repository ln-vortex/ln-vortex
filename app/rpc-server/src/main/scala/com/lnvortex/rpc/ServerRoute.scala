package com.lnvortex.rpc

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.ValidationRejection

import scala.util.Try

trait ServerRoute {
  def handleCommand: PartialFunction[ServerCommand, Route]

  def withValidServerCommand[R](validator: Try[R]): Directive1[R] =
    validator.fold(
      e => reject(ValidationRejection(e.getMessage, Some(e))),
      provide
    )
}
