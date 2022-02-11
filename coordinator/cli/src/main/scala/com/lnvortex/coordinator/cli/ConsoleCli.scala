package com.lnvortex.coordinator.cli

import com.lnvortex.coordinator.cli.CliCommand._
import com.lnvortex.coordinator.cli.ConsoleCli.serverConfig
import com.lnvortex.coordinator.config.LnVortexRpcServerConfig
import scopt.OParser
import ujson._
import upickle.{default => up}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util._

object ConsoleCli {

  lazy val serverConfig: LnVortexRpcServerConfig =
    LnVortexRpcServerConfig.fromDefaultDatadir()

  def parser: OParser[Unit, CliConfig] = {
    val builder = OParser.builder[CliConfig]

    import builder._
    OParser.sequence(
      programName("bitcoin-s-cli"),
      opt[Unit]("debug")
        .action((_, conf) => conf.copy(debug = true))
        .text("Print debugging information"),
      opt[Int]("rpcport")
        .action((port, conf) => conf.copy(rpcPortOpt = Some(port)))
        .text(s"The port to send our rpc request to on the server"),
      opt[String]("rpcuser")
        .action((password, conf) => conf.copy(rpcPassword = password))
        .text(s"The user to send our rpc request to on the server"),
      opt[String]("rpcpassword")
        .action((password, conf) => conf.copy(rpcPassword = password))
        .text(s"The password to send our rpc request to on the server"),
      opt[Unit]("version")
        .action((_, conf) => conf.copy(command = GetVersion))
        .hidden(),
      help('h', "help").text("Display this help message and exit"),
      note(sys.props("line.separator") + "Commands:"),
      cmd("getinfo")
        .action((_, conf) => conf.copy(command = GetInfo))
        .text(s"Returns basic info about the coordinator"),
      checkConfig {
        case CliConfig(NoCommand, _, _, _, _) =>
          failure("You need to provide a command!")
        case _ => success
      }
    )
  }

  def exec(args: Vector[String]): Try[String] = {
    val cliConfig = CliConfig(rpcUser = serverConfig.rpcUsername,
                              rpcPassword = serverConfig.rpcPassword,
                              rpcPortOpt = Some(serverConfig.rpcPort))

    val config = OParser.parse(parser, args, cliConfig) match {
      case None       => sys.exit(1)
      case Some(conf) => conf
    }

    exec(config.command, config)
  }

  def exec(command: CliCommand, config: CliConfig): Try[String] = {
    import System.err.{println => printerr}

    /** Prints the given message to stderr if debug is set */
    def debug(message: Any): Unit = {
      if (config.debug) {
        printerr(s"DEBUG: $message")
      }
    }

    /** Prints the given message to stderr and exist */
    def error[T](message: String): Failure[T] = {
      Failure(new RuntimeException(message))
    }

    val requestParam: RequestParam = command match {
      case GetInfo    => RequestParam("getinfo")
      case GetVersion =>
        // skip sending to server and just return version number of cli
        return Success(getClass.getPackage.getImplementationVersion)
      case NoCommand => throw new RuntimeException("Attempted to use NoCommand")
    }

    Try {
      import com.softwaremill.sttp._
      implicit val backend: SttpBackend[Id, Nothing] =
        HttpURLConnectionBackend()
      val request =
        sttp
          .post(uri"http://$host:${config.rpcPort}/")
          .contentType("application/json")
          .auth
          .basic(config.rpcUser, config.rpcPassword)
          .body {
            val uuid = java.util.UUID.randomUUID.toString
            val paramsWithID: Map[String, ujson.Value] =
              requestParam.toJsonMap + ("id" -> up
                .writeJs(uuid))
            up.write(paramsWithID)
          }
      debug(s"HTTP request: $request")
      val response = request.send()

      debug(s"HTTP response:")
      debug(response)

      // in order to mimic Bitcoin Core we always send
      // an object looking like {"result": ..., "error": ...}
      val rawBody = response.body match {
        case Left(err)       => err
        case Right(response) => response
      }

      val jsObjT =
        Try(ujson.read(rawBody).obj)
          .transform[mutable.LinkedHashMap[String, ujson.Value]](
            Success(_),
            _ => error(s"Response was not a JSON object! Got: $rawBody"))

      /** Gets the given key from jsObj if it exists
        * and is not null
        */
      def getKey(key: String): Option[ujson.Value] = {
        jsObjT.toOption.flatMap(_.get(key).flatMap(result =>
          if (result.isNull) None else Some(result)))
      }

      /** Converts a `ujson.Value` to String, making an
        * effort to avoid preceding and trailing `"`s
        */
      def jsValueToString(value: ujson.Value) =
        value match {
          case Str(string)             => string
          case Num(num) if num.isWhole => num.toLong.toString
          case Num(num)                => num.toString
          case rest: ujson.Value       => rest.render(2)
        }

      (getKey("result"), getKey("error")) match {
        case (Some(result), None) =>
          Success(jsValueToString(result))
        case (None, Some(err)) =>
          val msg = jsValueToString(err)
          error(msg)
        case (None, None) =>
          Success("")
        case (None, None) | (Some(_), Some(_)) =>
          error(s"Got unexpected response: $rawBody")
      }
    }.flatten
  }

  def host = "localhost"

  case class RequestParam(
      method: String,
      params: Seq[ujson.Value.Value] = Nil) {

    lazy val toJsonMap: Map[String, ujson.Value] = {
      if (params.isEmpty)
        Map("method" -> method)
      else
        Map("method" -> method, "params" -> params)
    }
  }
}

case class CliConfig(
    command: CliCommand = CliCommand.NoCommand,
    rpcUser: String = serverConfig.rpcUsername,
    rpcPassword: String = serverConfig.rpcUsername,
    debug: Boolean = false,
    rpcPortOpt: Option[Int] = None) {

  val rpcPort: Int = rpcPortOpt match {
    case Some(port) => port
    case None       => command.defaultPort
  }
}

object CliConfig {
  val empty: CliConfig = CliConfig()
}

sealed abstract class CliCommand {
  def defaultPort: Int = 12522
}

object CliCommand {

  case object NoCommand extends CliCommand

  case object GetVersion extends CliCommand

  case object GetInfo extends CliCommand

}
