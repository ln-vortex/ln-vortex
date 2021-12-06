package com.lnvortex.server.models

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

case class BannedUtxoDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: VortexCoordinatorAppConfig)
    extends CRUD[BannedUtxoDb, TransactionOutPoint]
    with SlickUtil[BannedUtxoDb, TransactionOutPoint] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  import mappers._

  override val table: TableQuery[BannedUtxoTable] = TableQuery[BannedUtxoTable]

  override def createAll(
      ts: Vector[BannedUtxoDb]): Future[Vector[BannedUtxoDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(ids: Vector[
    TransactionOutPoint]): Query[BannedUtxoTable, BannedUtxoDb, Seq] =
    table.filter(_.outPoint.inSet(ids))

  override protected def findAll(
      ts: Vector[BannedUtxoDb]): Query[BannedUtxoTable, BannedUtxoDb, Seq] =
    findByPrimaryKeys(ts.map(_.outPoint))

  class BannedUtxoTable(tag: Tag)
      extends Table[BannedUtxoDb](tag, schemaName, "banned_utxos") {

    def outPoint: Rep[TransactionOutPoint] = column("outpoint", O.PrimaryKey)
    def bannedUntil: Rep[Instant] = column("banned_until")
    def reason: Rep[String] = column("reason")

    def * : ProvenShape[BannedUtxoDb] = (outPoint, bannedUntil, reason).<>(
      BannedUtxoDb.tupled,
      BannedUtxoDb.unapply)
  }
}
