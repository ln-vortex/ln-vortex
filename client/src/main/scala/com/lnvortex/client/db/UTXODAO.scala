package com.lnvortex.client.db

import com.lnvortex.client.config.VortexAppConfig
import com.lnvortex.core.{PSBTSigned, UTXOWarning, UnspentCoin}
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.crypto.DoubleSha256DigestBE
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

  implicit val UTXOWarningMapper: BaseColumnType[UTXOWarning] =
    MappedColumnType.base[UTXOWarning, String](_.toString,
                                               UTXOWarning.fromString)

  override val table: TableQuery[UTXOTable] = TableQuery[UTXOTable]

  override def createAll(ts: Vector[UTXODb]): Future[Vector[UTXODb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[TransactionOutPoint]): Query[UTXOTable, UTXODb, Seq] =
    table.filter(_.outPoint.inSet(ids))

  override protected def findAll(
      ts: Vector[UTXODb]): Query[UTXOTable, UTXODb, Seq] =
    findByPrimaryKeys(ts.map(_.outPoint))

  def findBySPKsAction(spks: Vector[ScriptPubKey]): DBIOAction[Vector[UTXODb],
                                                               NoStream,
                                                               Effect.Read] = {
    table.filter(_.spk.inSet(spks)).result.map(_.toVector)
  }

  def createOutPointMap(outPoints: Vector[TransactionOutPoint]): Future[
    Map[TransactionOutPoint, Option[UTXODb]]] = {
    val actions = outPoints.map { outPoint =>
      findByPrimaryKeyAction(outPoint).map(outPoint -> _)
    }
    safeDatabase.run(DBIO.sequence(actions)).map(_.toMap)
  }

  def createMissing(coins: Vector[UnspentCoin]): Future[Vector[UTXODb]] = {
    val q = findByPrimaryKeys(coins.map(_.outPoint)).result.flatMap {
      existing =>
        val missing =
          coins.filterNot(out => existing.exists(_.outPoint == out.outPoint))

        val old =
          existing.filterNot(t => coins.exists(_.outPoint == t.outPoint))

        val all = old.map(t => (t.scriptPubKey, t.outPoint)) ++
          coins.map(t => (t.spk, t.outPoint))

        if (missing.isEmpty) DBIO.successful(Vector.empty)
        else {
          val dbs = missing.map { m =>
            val tuple = (m.spk, m.outPoint)
            val (anonSet, warning) =
              if (all.exists(t => t != tuple && t._2 == m.outPoint)) {
                (0, Some(UTXOWarning.AddressReuse))
              } else (1, None)

            UTXODb(outPoint = m.outPoint,
                   txId = m.outPoint.txIdBE,
                   scriptPubKey = m.spk,
                   anonSet = anonSet,
                   warning = warning,
                   isChange = false,
                   isVortex = true)
          }

          createAllAction(dbs)
        }
    }

    safeDatabase.runVec(q)
  }

  def setAnonSets(state: PSBTSigned, anonSet: Int): Future[Vector[UTXODb]] = {
    val findAction = for {
      prev <- findByPrimaryKeysAction(state.initDetails.inputs.map(_.outPoint))
      bySpks <- findBySPKsAction(state.spks)
    } yield (prev, bySpks)

    val q = findAction.flatMap { case (prev, bySpks) =>
      val changeDbOpt = state.changeOutpointOpt.map { c =>
        val spk = state.initDetails.changeSpkOpt.get
        val (anonSet, warning) = if (bySpks.exists(_.scriptPubKey == spk)) {
          (0, Some(UTXOWarning.AddressReuse))
        } else (1, None)

        UTXODb(outPoint = c,
               txId = c.txIdBE,
               scriptPubKey = spk,
               anonSet = anonSet,
               warning = warning,
               isChange = true,
               isVortex = true)
      }

      val minPrevAnonSet =
        prev.map(_.anonSet).filter(_ > 0).minOption.getOrElse(1)

      val (newAnonSet, warning) =
        if (bySpks.exists(_.scriptPubKey == state.targetSpk)) {
          (0, Some(UTXOWarning.AddressReuse))
        } else {
          (Math.max(1, minPrevAnonSet + anonSet - 1), None)
        }

      val outputDb = UTXODb(state.targetOutpoint,
                            state.txId,
                            state.targetSpk,
                            newAnonSet,
                            warning = warning,
                            isChange = false,
                            isVortex = true)
      val newDbs = outputDb +: changeDbOpt.toVector

      upsertAllAction(newDbs)
    }

    safeDatabase.run(q)
  }

  def getVortexTransactions(): Future[Vector[DoubleSha256DigestBE]] = {
    val q = table.filter(_.isVortex).map(_.txId).result
    safeDatabase.runVec(q)
  }

  class UTXOTable(tag: Tag) extends Table[UTXODb](tag, schemaName, "utxos") {

    def outPoint: Rep[TransactionOutPoint] = column("outpoint", O.PrimaryKey)

    def txId: Rep[DoubleSha256DigestBE] = column("txid")

    def spk: Rep[ScriptPubKey] = column("script_pub_key")

    def anonSet: Rep[Int] = column("anon_set")

    def warning: Rep[Option[UTXOWarning]] = column("warning")

    def isChange: Rep[Boolean] = column("is_change")

    def isVortex: Rep[Boolean] = column("is_vortex")

    def * : ProvenShape[UTXODb] =
      (outPoint, txId, spk, anonSet, warning, isChange, isVortex).<>(
        UTXODb.tupled,
        UTXODb.unapply)
  }
}
