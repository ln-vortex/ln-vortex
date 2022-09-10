package com.lnvortex.core.config

import com.typesafe.config.{Config, ConfigFactory}
import org.bitcoins.commons.config.AppConfig
import org.bitcoins.core.config._

import java.nio.file.{Path, Paths}
import scala.util.Properties

/** Parses arguments passed to the rpc server as command line arguments This
  * does NOT consider things that exist in reference.conf or application.conf
  * files
  */
case class ServerArgParser(commandLineArgs: Vector[String]) {

  private lazy val argsWithIndex: Vector[(String, Int)] =
    commandLineArgs.zipWithIndex

  /** The ip address we are binding the server to */
  lazy val rpcBindOpt: Option[String] = {
    val rpcbindOpt = argsWithIndex.find(_._1.toLowerCase == "--rpcbind")
    rpcbindOpt.map { case (_, idx) =>
      commandLineArgs(idx + 1)
    }
  }

  lazy val rpcPortOpt: Option[Int] = {
    val portOpt = argsWithIndex.find(_._1.toLowerCase == "--rpcport")
    portOpt.map { case (_, idx) =>
      commandLineArgs(idx + 1).toInt
    }
  }

  lazy val rpcUserOpt: Option[String] = {
    val portOpt = argsWithIndex.find(_._1.toLowerCase == "--rpcuser")
    portOpt.map { case (_, idx) =>
      commandLineArgs(idx + 1)
    }
  }

  lazy val rpcPasswordOpt: Option[String] = {
    val portOpt = argsWithIndex.find(_._1.toLowerCase == "--rpcpassword")
    portOpt.map { case (_, idx) =>
      commandLineArgs(idx + 1)
    }
  }

  lazy val networkOpt: Option[BitcoinNetwork] = {
    val netOpt = argsWithIndex.findLast(_._1.toLowerCase == "--network")
    netOpt.map { case (_, idx) =>
      val string = commandLineArgs(idx + 1)
      string.toLowerCase match {
        case "mainnet"  => MainNet
        case "main"     => MainNet
        case "testnet3" => TestNet3
        case "testnet"  => TestNet3
        case "test"     => TestNet3
        case "regtest"  => RegTest
        case "signet"   => SigNet
        case "sig"      => SigNet
        case _: String =>
          throw new IllegalArgumentException(s"Invalid network $string")
      }
    }
  }

  private lazy val dataDirIndexOpt: Option[(String, Int)] = {
    argsWithIndex.find(_._1.toLowerCase == "--datadir")
  }

  /** The datadir passed in as a command line arg using --datadir */
  lazy val datadirOpt: Option[Path] = dataDirIndexOpt.map { case (_, idx) =>
    val str = commandLineArgs(idx + 1)
    // we only want the replace ~ if it is first in the file path
    // otherwise windows gets mangled as it can have parts of the file path containing ~
    // https://stackoverflow.com/a/7163455/967713
    // C:\Users\RUNNER~1\AppData\Local\Temp\bitcoin-s-13391384540028797275
    val usableStr = str.replaceFirst("^~", Properties.userHome)
    Paths.get(usableStr)
  }

  private lazy val listenIdxOpt: Option[(String, Int)] = {
    argsWithIndex.find(_._1.toLowerCase == "--listen")
  }

  lazy val listenAddrOpt: Option[String] = listenIdxOpt.map { case (_, idx) =>
    commandLineArgs(idx + 1)
  }

  private lazy val coordIdxOpt: Option[(String, Int)] = {
    argsWithIndex.find(_._1.toLowerCase == "--coordinator")
  }

  lazy val coordOpt: Option[String] = coordIdxOpt.map { case (_, idx) =>
    commandLineArgs(idx + 1)
  }

  private lazy val configIndexOpt: Option[Int] = {
    argsWithIndex.find(_._1.toLowerCase == "--conf").map(_._2)
  }

  /** A custom configuration file passed in as a command line arg with --conf */
  lazy val configOpt: Option[Path] = {
    configIndexOpt.map { idx =>
      val str = commandLineArgs(idx + 1)
      val usableStr =
        if (str.startsWith("~")) str.replace("~", Properties.userHome)
        else str
      Paths.get(usableStr)
    }
  }

  /** Converts the given command line args into a Config object. There is one
    * exclusion to this, we cannot write the --conf flag to the config file as
    * that is self referential
    */
  def toConfig(moduleName: String): Config = {
    val networkString = networkOpt match {
      case Some(network) =>
        s"bitcoin-s.network=$network\n"
      case None => ""
    }

    val listenString = listenAddrOpt match {
      case Some(addr) =>
        s"coordinator.listen=\"$addr\"\n"
      case None => ""
    }

    val coordinatorString = coordOpt match {
      case Some(coord) =>
        s"vortex.coordinators=$coord\n"
      case None => ""
    }

    val rpcPortString = rpcPortOpt match {
      case Some(rpcPort) =>
        s"$moduleName.rpcPort=$rpcPort\n"
      case None => ""
    }

    val rpcUserString = rpcUserOpt match {
      case Some(rpcUser) =>
        s"$moduleName.rpcUser=$rpcUser\n"
      case None => ""
    }

    val rpcPasswordString = rpcPasswordOpt match {
      case Some(rpcPassword) =>
        s"$moduleName.rpcPassword=$rpcPassword\n"
      case None => ""
    }

    val rpcBindString = rpcBindOpt match {
      case Some(rpcbind) =>
        s"$moduleName.rpcBind=\"$rpcbind\"\n"
      case None => ""
    }

    val datadirString = datadirOpt match {
      case Some(datadir) =>
        s"bitcoin-s.datadir=" + AppConfig.safePathToString(datadir) + "\n"
      case None => ""
    }

    val concat =
      rpcPortString +
        rpcBindString +
        rpcUserString +
        rpcPasswordString +
        datadirString +
        networkString +
        listenString +
        coordinatorString

    val all = ConfigFactory.parseString(concat)

    configOpt match {
      case Some(file) =>
        val fromFile = ConfigFactory.parseFile(file.toFile)
        all.withFallback(fromFile)
      case None => all
    }
  }

}

object ServerArgParser {
  val empty: ServerArgParser = ServerArgParser(Vector.empty)
}
