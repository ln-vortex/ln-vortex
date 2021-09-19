package com.lnvortex.server.models

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.{ForeignKeyQuery, ProvenShape}

import scala.concurrent.{ExecutionContext, Future}

case class RegisteredOutputDAO()(implicit
    val ec: ExecutionContext,
    override val appConfig: VortexCoordinatorAppConfig)
    extends CRUD[RegisteredOutputDb, TransactionOutput]
    with SlickUtil[RegisteredOutputDb, TransactionOutput] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[RegisteredOutputTable] =
    TableQuery[RegisteredOutputTable]

  private lazy val roundTable: slick.lifted.TableQuery[RoundDAO#RoundTable] = {
    RoundDAO().table
  }

  implicit val doubleSha256DigestMapper: BaseColumnType[DoubleSha256Digest] =
    MappedColumnType.base[DoubleSha256Digest, String](
      _.hex,
      DoubleSha256Digest.fromHex)

  override def createAll(
      ts: Vector[RegisteredOutputDb]): Future[Vector[RegisteredOutputDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(ids: Vector[
    TransactionOutput]): Query[RegisteredOutputTable, RegisteredOutputDb, Seq] =
    table.filter(_.output.inSet(ids))

  override protected def findAll(ts: Vector[RegisteredOutputDb]): Query[
    RegisteredOutputTable,
    RegisteredOutputDb,
    Seq] =
    findByPrimaryKeys(ts.map(_.output))

  def findByRoundId(
      roundId: DoubleSha256Digest): Future[Vector[RegisteredOutputDb]] = {
    val query = table.filter(_.roundId === roundId).result

    safeDatabase.runVec(query)
  }

  class RegisteredOutputTable(tag: Tag)
      extends Table[RegisteredOutputDb](tag, schemaName, "registered_outputs") {

    def output: Rep[TransactionOutput] = column("output", O.PrimaryKey)

    def sig: Rep[SchnorrDigitalSignature] = column("sig")

    def roundId: Rep[DoubleSha256Digest] = column("round_id")

    def * : ProvenShape[RegisteredOutputDb] = (output, sig, roundId).<>(
      RegisteredOutputDb.tupled,
      RegisteredOutputDb.unapply)

    def fkRoundId: ForeignKeyQuery[_, RoundDb] =
      foreignKey("fk_roundId",
                 sourceColumns = roundId,
                 targetTableQuery = roundTable)(_.roundId)
  }
}
