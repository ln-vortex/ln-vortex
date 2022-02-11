package com.lnvortex.server.models

import com.lnvortex.core.RoundStatus
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

  implicit val roundStatusMapper: BaseColumnType[RoundStatus] =
    MappedColumnType.base[RoundStatus, String](_.toString,
                                               RoundStatus.fromString)
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

  def findByRoundId(
      roundId: DoubleSha256Digest): Future[Vector[RegisteredInputDb]] = {
    val query = table.filter(_.roundId === roundId).result

    safeDatabase.runVec(query)
  }

  def findByPeerId(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest): Future[Vector[RegisteredInputDb]] = {
    val query =
      table.filter(t => t.peerId === peerId && t.roundId === roundId).result

    safeDatabase.runVec(query)
  }

  def findByPeerIds(
      peerIds: Vector[Sha256Digest],
      roundId: DoubleSha256Digest): Future[Vector[RegisteredInputDb]] = {
    val query =
      table.filter(t => t.peerId.inSet(peerIds) && t.roundId === roundId).result

    safeDatabase.runVec(query)
  }

  def deleteByPeerId(
      peerId: Sha256Digest,
      roundId: DoubleSha256Digest): Future[Int] = {
    val query =
      table.filter(t => t.peerId === peerId && t.roundId === roundId).delete

    safeDatabase.run(query)
  }

  def deleteByRoundId(roundId: DoubleSha256Digest): Future[Int] = {
    val query = table.filter(_.roundId === roundId).delete

    safeDatabase.run(query)
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
