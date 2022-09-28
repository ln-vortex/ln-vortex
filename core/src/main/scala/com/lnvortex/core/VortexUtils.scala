package com.lnvortex.core

import org.bitcoins.core.config._
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.transaction._

trait VortexUtils {

  def getAnonymitySet(transaction: Transaction, outputIndex: Int): Int = {
    val output = transaction.outputs(outputIndex)
    val equalOutputs = transaction.outputs.count(_.value == output.value)
    val numInputs = transaction.inputs.size

    // Anonymity set cannot be larger than the number of inputs.
    Math.min(numInputs, equalOutputs)
  }

  def getMaxAnonymitySet(transaction: Transaction): Int = {
    transaction.outputs.indices.map(getAnonymitySet(transaction, _)).max
  }

  def isMinimalSelection(
      outputRefs: Vector[OutputReference],
      target: CurrencyUnit): Boolean = {
    val total = outputRefs.map(_.output.value).sum

    !outputRefs.exists(o => total - o.output.value >= target)
  }

  def getDefaultClientRpcPort(network: BitcoinNetwork): Int = {
    network match {
      case MainNet  => 2522
      case TestNet3 => 12522
      case RegTest  => 22522
      case SigNet   => 32522
    }
  }

  def getDefaultCoordinatorRpcPort(network: BitcoinNetwork): Int = {
    network match {
      case MainNet  => 2524
      case TestNet3 => 12524
      case RegTest  => 22524
      case SigNet   => 32524
    }
  }

  def getDefaultPort(network: BitcoinNetwork): Int = {
    network match {
      case MainNet  => 2523
      case TestNet3 => 12523
      case RegTest  => 22523
      case SigNet   => 32523
    }
  }

  final val CONFIG_FILE_NAME: String = "vortex.conf"
}

object VortexUtils extends VortexUtils
