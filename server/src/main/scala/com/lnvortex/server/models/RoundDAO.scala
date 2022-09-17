package com.lnvortex.server.models

import com.lnvortex.core.RoundStatus
import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.crypto._
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

case class RoundDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: VortexCoordinatorAppConfig)
    extends CRUD[RoundDb, DoubleSha256Digest]
    with SlickUtil[RoundDb, DoubleSha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  implicit val roundStatusMapper: BaseColumnType[RoundStatus] =
    MappedColumnType.base[RoundStatus, String](_.toString,
                                               RoundStatus.fromString)

  implicit val satoshisPerVirtualByteMapper: BaseColumnType[
    SatoshisPerVirtualByte] = {
    MappedColumnType
      .base[SatoshisPerVirtualByte, Int](
        _.toLong.toInt,
        int => SatoshisPerVirtualByte.fromLong(int.toLong))
  }

  import mappers.{satoshisPerVirtualByteMapper => _, _}

  override val table: TableQuery[RoundTable] = TableQuery[RoundTable]

  override def createAll(ts: Vector[RoundDb]): Future[Vector[RoundDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[DoubleSha256Digest]): Query[RoundTable, RoundDb, Seq] =
    table.filter(_.roundId.inSet(ids))

  override protected def findAll(
      ts: Vector[RoundDb]): Query[RoundTable, RoundDb, Seq] =
    findByPrimaryKeys(ts.map(_.roundId))

  def hasTxIdAction(txId: DoubleSha256DigestBE): DBIOAction[Boolean,
                                                            NoStream,
                                                            Effect.Read] = {
    table.filter(_.txIdOpt === txId).size.result.map(_ > 0)
  }

  class RoundTable(tag: Tag) extends Table[RoundDb](tag, schemaName, "rounds") {

    def roundId: Rep[DoubleSha256Digest] = column("round_id", O.PrimaryKey)

    def status: Rep[RoundStatus] = column("status")

    def roundTime: Rep[Instant] = column("round_time")

    def coordinatorFee: Rep[CurrencyUnit] = column("coordinator_fee")

    def amount: Rep[CurrencyUnit] = column("amount")

    def feeRate: Rep[Option[SatoshisPerVirtualByte]] = column("fee_rate")

    def psbtOpt: Rep[Option[PSBT]] = column("psbt")

    def transactionOpt: Rep[Option[Transaction]] = column("transaction")

    def txIdOpt: Rep[Option[DoubleSha256DigestBE]] = column("txid")

    def profit: Rep[Option[CurrencyUnit]] = column("profit")

    def * : ProvenShape[RoundDb] =
      (roundId,
       status,
       roundTime,
       coordinatorFee,
       amount,
       feeRate,
       psbtOpt,
       transactionOpt,
       txIdOpt,
       profit).<>(RoundDb.tupled, RoundDb.unapply)
  }
}
