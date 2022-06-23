package com.lnvortex.client.db

import com.lnvortex.client.config.VortexAppConfig
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.db._
import slick.lifted.ProvenShape

import scala.concurrent._

case class UTXODAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: VortexAppConfig)
    extends CRUD[UTXODb, TransactionOutPoint]
    with SlickUtil[UTXODb, TransactionOutPoint] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)
  import mappers._

  override val table: TableQuery[UTXOTable] = TableQuery[UTXOTable]

  override def createAll(ts: Vector[UTXODb]): Future[Vector[UTXODb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[TransactionOutPoint]): Query[UTXOTable, UTXODb, Seq] =
    table.filter(_.outPoint.inSet(ids))

  override protected def findAll(
      ts: Vector[UTXODb]): Query[UTXOTable, UTXODb, Seq] =
    findByPrimaryKeys(ts.map(_.outPoint))

  def findByOutPoint(outPoint: TransactionOutPoint): Future[Option[UTXODb]] = {
    safeDatabase.run(findByPrimaryKey(outPoint).result.headOption)
  }

  def findByOutPoints(
      outPoints: Vector[TransactionOutPoint]): Future[Vector[UTXODb]] = {
    safeDatabase.runVec(findByPrimaryKeys(outPoints).result)
  }

  def createMissing(
      outpoints: Vector[TransactionOutPoint]): Future[Vector[UTXODb]] = {
    val q = findByPrimaryKeys(outpoints).result.flatMap { existing =>
      val missing = outpoints.filterNot(existing.contains)
      if (missing.isEmpty) DBIO.successful(Vector.empty)
      else createAllAction(missing.map(UTXODb(_, 1, isChange = false)))
    }

    safeDatabase.runVec(q)
  }

  def setAnonSets(
      inputs: Vector[TransactionOutPoint],
      output: TransactionOutPoint,
      change: Option[TransactionOutPoint],
      anonSet: Int): Future[Vector[UTXODb]] = {
    val changeDbOpt = change.map(c => UTXODb(c, anonSet, isChange = true))

    val q = findByPrimaryKeys(inputs).result.flatMap { prev =>
      val minPrevAnonSet = prev.map(_.anonSet).minOption.getOrElse(1)
      val newAnonSet = minPrevAnonSet + anonSet - 1
      val outputDb = UTXODb(output, Math.min(1, newAnonSet), isChange = false)
      val newDbs = outputDb +: changeDbOpt.toVector

      upsertAllAction(newDbs)
    }

    safeDatabase.run(q)
  }

  class UTXOTable(tag: Tag) extends Table[UTXODb](tag, schemaName, "utxos") {

    def outPoint: Rep[TransactionOutPoint] = column("outpoint", O.PrimaryKey)

    def anonSet: Rep[Int] = column("anon_set")

    def isChange: Rep[Boolean] = column("is_change")

    def * : ProvenShape[UTXODb] =
      (outPoint, anonSet, isChange).<>(UTXODb.tupled, UTXODb.unapply)
  }
}
