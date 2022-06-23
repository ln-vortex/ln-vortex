package com.lnvortex.core

import org.bitcoins.core.protocol.transaction.Transaction

trait VortexUtils {

  def getAnonymitySet(transaction: Transaction, outputIndex: Int): Int = {
    val output = transaction.outputs(outputIndex)
    val equalOutputs = transaction.outputs.count(_.value == output.value)
    val numInputs = transaction.inputs.size

    // Anonymity set cannot be larger than the number of inputs.
    Math.min(numInputs, equalOutputs)
  }
}

object VortexUtils extends VortexUtils
