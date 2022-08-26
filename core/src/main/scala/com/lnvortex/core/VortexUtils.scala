package com.lnvortex.core

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

  def isMinimalSelection(
      outputRefs: Vector[OutputReference],
      target: CurrencyUnit): Boolean = {
    val total = outputRefs.map(_.output.value).sum

    !outputRefs.exists(o => total - o.output.value >= target)
  }

  final val DEFAULT_PORT = 12523
}

object VortexUtils extends VortexUtils
