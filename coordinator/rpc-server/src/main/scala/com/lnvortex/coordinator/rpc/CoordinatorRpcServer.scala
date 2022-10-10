package com.lnvortex.coordinator.rpc

import akka.Done
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.{Credentials, DebuggingDirectives}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import grizzled.slf4j.Logging
import upickle.{default => up}

import scala.concurrent.Future

case class CoordinatorRpcServer(
    handlers: Seq[ServerRoute],
    rpcBindOpt: Option[String],
    rpcPort: Int,
    rpcUser: String,
    rpcPassword: String)(implicit system: ActorSystem)
    extends PlayJsonSupport
    with Logging {

  import system.dispatcher

  /** Handles all server commands by throwing a MethodNotFound */
  private val catchAllHandler: PartialFunction[ServerCommand, StandardRoute] = {
    case ServerCommand(_, name, _) => throw HttpError.MethodNotFound(name)
  }

  def authenticator(credentials: Credentials): Option[Done] =
    credentials match {
      case p @ Credentials.Provided(_)
          if rpcPassword.nonEmpty && p.verify(rpcPassword) =>
        Some(Done)
      case _ =>
        None
    }

  /** HTTP directive that handles both exceptions and rejections */
  private def withErrorHandling(route: Route): Route = {

    val rejectionHandler =
      RejectionHandler
        .newBuilder()
        .handleNotFound {
          complete {
            CoordinatorRpcServer.httpError(
              """Resource not found. Hint: all RPC calls are made against root ('/')""",
              StatusCodes.BadRequest)
          }
        }
        .result()

    val exceptionHandler = ExceptionHandler {
      case HttpError.MethodNotFound(method) =>
        complete(
          CoordinatorRpcServer.httpError(s"'$method' is not a valid method",
                                         StatusCodes.BadRequest))
      case err: Throwable =>
        logger.info(s"Unhandled error in server:", err)
        complete(
          CoordinatorRpcServer.httpError(s"Request failed: ${err.getMessage}"))
    }

    handleRejections(rejectionHandler) {
      handleExceptions(exceptionHandler) {
        route
      }
    }
  }

  val route: Route =
    DebuggingDirectives.logRequestResult(
      ("http-rpc-server", Logging.DebugLevel)) {
      authenticateBasic("auth", authenticator) { _ =>
        withErrorHandling {
          pathSingleSlash {
            post {
              entity(as[ServerCommand]) { cmd =>
                val init = PartialFunction.empty[ServerCommand, Route]
                val handler = handlers.foldLeft(init) { case (accum, curr) =>
                  accum.orElse(curr.handleCommand)
                }
                handler.orElse(catchAllHandler).apply(cmd)
              }
            }
          }
        }
      }
    }

  def start(): Future[Http.ServerBinding] = {
    val httpFut =
      Http()
        .newServerAt(rpcBindOpt.getOrElse("localhost"), rpcPort)
        .bindFlow(route)
    httpFut.foreach { http =>
      logger.info(s"Started coordinator RPC server at ${http.localAddress}")
    }

    httpFut.failed.foreach { err =>
      logger.error(s"Failed to start coordinator RPC server:", err)
    }

    httpFut
  }
}

object CoordinatorRpcServer {

  case class Response(
      result: Option[ujson.Value] = None,
      error: Option[String] = None) {

    def toJsonMap: Map[String, ujson.Value] = {
      Map(
        "result" -> (result match {
          case None      => ujson.Null
          case Some(res) => res
        }),
        "error" -> (error match {
          case None      => ujson.Null
          case Some(err) => err
        })
      )
    }
  }

  /** Creates a HTTP response with the given body as a JSON response */
  def httpSuccess[T](body: T)(implicit
      writer: up.Writer[T]): HttpEntity.Strict = {
    val response = Response(result = Some(up.writeJs(body)))
    HttpEntity(
      ContentTypes.`application/json`,
      up.write(response.toJsonMap)
    )
  }

  def httpSuccessOption[T](bodyOpt: Option[T])(implicit
      writer: up.Writer[T]): HttpEntity.Strict = {
    val response = Response(result = bodyOpt.map(body => up.writeJs(body)))
    HttpEntity(
      ContentTypes.`application/json`,
      up.write(response.toJsonMap)
    )
  }

  def httpBadRequest(ex: Throwable): HttpResponse = {
    httpBadRequest(ex.getMessage)
  }

  def httpBadRequest(msg: String): HttpResponse = {

    val entity = {
      val response = Response(error = Some(msg))
      HttpEntity(
        ContentTypes.`application/json`,
        up.write(response.toJsonMap)
      )
    }
    HttpResponse(status = StatusCodes.BadRequest, entity = entity)
  }

  def httpError(
      msg: String,
      status: StatusCode = StatusCodes.InternalServerError): HttpResponse = {

    val entity = {
      val response = Response(error = Some(msg))
      HttpEntity(
        ContentTypes.`application/json`,
        up.write(response.toJsonMap)
      )
    }

    HttpResponse(status = status, entity = entity)
  }
}
