package com.lnvortex.server.models

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.protocol.script.ScriptWitness
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.crypto.{DoubleSha256Digest, Sha256Digest}
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.{ExecutionContext, Future}

case class RegisteredInputDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: VortexCoordinatorAppConfig)
    extends CRUD[RegisteredInputDb, TransactionOutPoint]
    with SlickUtil[RegisteredInputDb, TransactionOutPoint] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[RegisteredInputsTable] =
    TableQuery[RegisteredInputsTable]

  private lazy val roundTable: slick.lifted.TableQuery[RoundDAO#RoundTable] = {
    RoundDAO().table
  }

  private lazy val aliceTable: slick.lifted.TableQuery[AliceDAO#AliceTable] = {
    AliceDAO().table
  }

  override def createAll(
      ts: Vector[RegisteredInputDb]): Future[Vector[RegisteredInputDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[TransactionOutPoint]): Query[
    RegisteredInputsTable,
    RegisteredInputDb,
    Seq] =
    table.filter(_.outPoint.inSet(ids))

  override protected def findAll(ts: Vector[RegisteredInputDb]): Query[
    RegisteredInputsTable,
    RegisteredInputDb,
    Seq] =
    findByPrimaryKeys(ts.map(_.outPoint))

  def findByRoundIdAction(roundId: DoubleSha256Digest): DBIOAction[
    Vector[RegisteredInputDb],
    NoStream,
    Effect.Read] = {
    table.filter(_.roundId === roundId).result.map(_.toVector)
  }

  def findByRoundId(
      roundId: DoubleSha256Digest): Future[Vector[RegisteredInputDb]] = {
    safeDatabase.run(findByRoundIdAction(roundId))
  }

  def findByPeerIdAction(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest): DBIOAction[
    Vector[RegisteredInputDb],
    NoStream,
    Effect.Read] = {
    table
      .filter(t => t.peerId === peerId && t.roundId === roundId)
      .result
      .map(_.toVector)
  }

  def findByPeerId(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest): Future[Vector[RegisteredInputDb]] = {
    safeDatabase.run(findByPeerIdAction(peerId, roundId))
  }

  def findByPeerIdsAction(
      peerIds: Vector[Sha256Digest],
      roundId: DoubleSha256Digest): DBIOAction[
    Vector[RegisteredInputDb],
    NoStream,
    Effect.Read] = {
    table
      .filter(t => t.peerId.inSet(peerIds) && t.roundId === roundId)
      .result
      .map(_.toVector)
  }

  def deleteByPeerIdAction(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest): DBIOAction[Int, NoStream, Effect.Write] = {
    table.filter(t => t.peerId === peerId && t.roundId === roundId).delete
  }

  def deleteByPeerId(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest): Future[Int] = {
    safeDatabase.run(deleteByPeerIdAction(peerId, roundId))
  }

  def deleteByRoundIdAction(
      roundId: DoubleSha256Digest): DBIOAction[Int, NoStream, Effect.Write] = {
    table.filter(_.roundId === roundId).delete
  }

  class RegisteredInputsTable(tag: Tag)
      extends Table[RegisteredInputDb](tag, schemaName, "registered_inputs") {

    def outPoint: Rep[TransactionOutPoint] = column("outpoint", O.PrimaryKey)

    def output: Rep[TransactionOutput] = column("output")

    def inputProof: Rep[ScriptWitness] = column("input_proof")

    def indexOpt: Rep[Option[Int]] = column("index")

    def roundId: Rep[DoubleSha256Digest] = column("round_id")

    def peerId: Rep[Sha256Digest] = column("peer_id")

    def * : ProvenShape[RegisteredInputDb] =
      (outPoint, output, inputProof, indexOpt, roundId, peerId).<>(
        RegisteredInputDb.tupled,
        RegisteredInputDb.unapply)

    def fkRoundId: ForeignKeyQuery[_, RoundDb] =
      foreignKey("fk_roundId",
                 sourceColumns = roundId,
                 targetTableQuery = roundTable)(_.roundId)

    def fkPeerId: ForeignKeyQuery[_, AliceDb] =
      foreignKey("fk_peerId",
                 sourceColumns = peerId,
                 targetTableQuery = aliceTable)(_.peerId)
  }
}
