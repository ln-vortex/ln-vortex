package com.lnvortex.server.config

import com.lnvortex.server.models.RoundDb
import grizzled.slf4j.Logging
import org.bitcoins.core.api.callback.{CallbackFactory, ModuleCallbacks}
import org.bitcoins.core.api.{Callback, CallbackHandler}

import scala.concurrent.{ExecutionContext, Future}

trait OnRoundComplete extends Callback[RoundDb]
trait OnRoundReconciled extends Callback[RoundDb]

trait CoordinatorCallbacks
    extends ModuleCallbacks[CoordinatorCallbacks]
    with Logging {
  def onRoundComplete: CallbackHandler[RoundDb, OnRoundComplete]
  def onRoundReconciled: CallbackHandler[RoundDb, OnRoundReconciled]

  def executeOnRoundComplete(roundDb: RoundDb)(implicit
      ec: ExecutionContext): Future[Unit] = {
    onRoundComplete.execute(
      roundDb,
      (err: Throwable) =>
        logger.error(s"${onRoundComplete.name} Callback failed with", err))
  }

  def executeOnRoundReconciled(roundDb: RoundDb)(implicit
      ec: ExecutionContext): Future[Unit] = {
    onRoundReconciled.execute(
      roundDb,
      (err: Throwable) =>
        logger.error(s"${onRoundReconciled.name} Callback failed with", err))
  }

  def +(other: CoordinatorCallbacks): CoordinatorCallbacks
}

object CoordinatorCallbacks extends CallbackFactory[CoordinatorCallbacks] {

  private case class CoordinatorCallbacksImpl(
      onRoundComplete: CallbackHandler[RoundDb, OnRoundComplete],
      onRoundReconciled: CallbackHandler[RoundDb, OnRoundReconciled]
  ) extends CoordinatorCallbacks {

    override def +(other: CoordinatorCallbacks): CoordinatorCallbacks = {
      copy(onRoundComplete = onRoundComplete ++ other.onRoundComplete,
           onRoundReconciled = onRoundReconciled ++ other.onRoundReconciled)
    }
  }

  override val empty: CoordinatorCallbacks = apply(Vector.empty, Vector.empty)

  def apply(onRoundReconciled: OnRoundReconciled): CoordinatorCallbacks = {
    apply(Vector.empty, Vector(onRoundReconciled))
  }

  def apply(onRoundComplete: OnRoundComplete): CoordinatorCallbacks = {
    apply(Vector(onRoundComplete), Vector.empty)
  }

  def apply(
      onRoundComplete: OnRoundComplete,
      onRoundReconciled: OnRoundReconciled): CoordinatorCallbacks = {
    apply(Vector(onRoundComplete), Vector(onRoundReconciled))
  }

  def apply(
      onRoundComplete: Vector[OnRoundComplete],
      onRoundReconciled: Vector[OnRoundReconciled]): CoordinatorCallbacks = {
    val onRoundCompleteHandler =
      CallbackHandler[RoundDb, OnRoundComplete]("onRoundComplete",
                                                onRoundComplete)
    val onRoundReconciledHandler =
      CallbackHandler[RoundDb, OnRoundReconciled]("onRoundReconciled",
                                                  onRoundReconciled)
    CoordinatorCallbacksImpl(onRoundCompleteHandler, onRoundReconciledHandler)
  }
}
