package com.lnvortex.server.models

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class AliceDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: VortexCoordinatorAppConfig)
    extends CRUD[AliceDb, Sha256Digest]
    with SlickUtil[AliceDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[AliceTable] = TableQuery[AliceTable]

  override def createAll(ts: Vector[AliceDb]): Future[Vector[AliceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[AliceTable, AliceDb, Seq] =
    table.filter(_.peerId.inSet(ids))

  override protected def findAll(
      ts: Vector[AliceDb]): Query[AliceTable, AliceDb, Seq] =
    findByPrimaryKeys(ts.map(_.peerId))

  def findByEitherAction(
      either: Either[SchnorrNonce, Sha256Digest]): DBIOAction[Option[AliceDb],
                                                              NoStream,
                                                              Effect.Read] = {
    either match {
      case Left(nonce) =>
        table.filter(_.nonce === nonce).result.headOption
      case Right(peerId) =>
        table.filter(_.peerId === peerId).result.headOption
    }
  }

  def findByNonceAction(nonce: SchnorrNonce): DBIOAction[Option[AliceDb],
                                                         NoStream,
                                                         Effect.Read] = {
    table.filter(_.nonce === nonce).result.map(_.headOption)
  }

  def findByNonce(nonce: SchnorrNonce): Future[Option[AliceDb]] = {
    safeDatabase.run(findByNonceAction(nonce))
  }

  def findByRoundIdAction(
      roundId: DoubleSha256Digest): DBIOAction[Vector[AliceDb],
                                               NoStream,
                                               Effect.Read] = {
    table.filter(_.roundId === roundId).result.map(_.toVector)
  }

  def findByRoundId(roundId: DoubleSha256Digest): Future[Vector[AliceDb]] = {
    safeDatabase.run(findByRoundIdAction(roundId))
  }

  def findRegisteredForRoundAction(
      roundId: DoubleSha256Digest): DBIOAction[Vector[AliceDb],
                                               NoStream,
                                               Effect.Read] = {
    table
      .filter(t => t.roundId === roundId && t.blindOutputSigOpt.isDefined)
      .result
      .map(_.toVector)
  }

  def numRegisteredForRoundAction(
      roundId: DoubleSha256Digest): DBIOAction[Int,
                                               profile.api.NoStream,
                                               Effect.Read] = {
    table
      .filter(t => t.roundId === roundId && t.blindOutputSigOpt.isDefined)
      .map(_.peerId)
      .distinct
      .size
      .result
  }

  def findSignedForRoundAction(
      roundId: DoubleSha256Digest): DBIOAction[Vector[AliceDb],
                                               NoStream,
                                               Effect.Read] = {
    table
      .filter(t => t.roundId === roundId && t.signedPSBT.isDefined)
      .result
      .map(_.toVector)
  }

  def findSignedForRound(
      roundId: DoubleSha256Digest): Future[Vector[AliceDb]] = {
    safeDatabase.run(findSignedForRoundAction(roundId))
  }

  def deleteByPeerIdsAction(
      peerIds: Seq[Sha256Digest]): DBIOAction[Int, NoStream, Effect.Write] = {
    table.filter(_.peerId.inSet(peerIds)).delete
  }

  def nextNonceIndexAction(): DBIOAction[Int, NoStream, Effect.Read] = {
    table.map(_.nonceIndex).max.result.map {
      case None        => 0
      case Some(value) => value + 1
    }
  }

  class AliceTable(tag: Tag) extends Table[AliceDb](tag, schemaName, "alices") {

    def peerId: Rep[Sha256Digest] = column("peer_id", O.PrimaryKey)

    def roundId: Rep[DoubleSha256Digest] = column("round_id")

    def purpose: Rep[HDPurpose] = column("purpose")

    def coin: Rep[HDCoinType] = column("coin")

    def accountIdx: Rep[Int] = column("account")

    def chain: Rep[HDChainType] = column("chain")

    def nonceIndex: Rep[Int] = column("nonce_index")

    def nonce: Rep[SchnorrNonce] = column("nonce", O.Unique)

    def isRemix: Rep[Boolean] = column("is_remix")

    def numInputs: Rep[Int] = column("num_inputs")

    def blindedOutputOpt: Rep[Option[FieldElement]] = column("blinded_output")

    def changeSpkOpt: Rep[Option[ScriptPubKey]] = column("change_spk")

    def blindOutputSigOpt: Rep[Option[FieldElement]] = column("blind_sig")

    def signedPSBT: Rep[Option[PSBT]] = column("signed_psbt")

    def * : ProvenShape[AliceDb] =
      (peerId,
       roundId,
       purpose,
       coin,
       accountIdx,
       chain,
       nonceIndex,
       nonce,
       isRemix,
       numInputs,
       blindedOutputOpt,
       changeSpkOpt,
       blindOutputSigOpt,
       signedPSBT).<>(AliceDb.tupled, AliceDb.unapply)
  }
}
