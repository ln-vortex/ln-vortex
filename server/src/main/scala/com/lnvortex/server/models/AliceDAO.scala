package com.lnvortex.server.models

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.crypto.{FieldElement, Sha256Digest}
import org.bitcoins.db.{CRUD, DbCommonsColumnMappers, SlickUtil}
import slick.lifted.ProvenShape

import scala.concurrent.{ExecutionContext, Future}

case class AliceDAO()(implicit
    val ec: ExecutionContext,
    override val appConfig: VortexCoordinatorAppConfig)
    extends CRUD[AliceDb, Sha256Digest]
    with SlickUtil[AliceDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)

  implicit val bip32PathMapper: BaseColumnType[BIP32Path] =
    MappedColumnType.base[BIP32Path, String](_.toString, BIP32Path.fromString)

  import mappers._

  override val table: TableQuery[AliceTable] = TableQuery[AliceTable]

  override def createAll(ts: Vector[AliceDb]): Future[Vector[AliceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[AliceTable, AliceDb, Seq] =
    table.filter(_.roundId.inSet(ids))

  override protected def findAll(
      ts: Vector[AliceDb]): Query[AliceTable, AliceDb, Seq] =
    findByPrimaryKeys(ts.map(_.roundId))

  def findByRoundId(roundId: Sha256Digest): Future[Vector[AliceDb]] = {
    val query = table.filter(_.roundId === roundId).result

    safeDatabase.runVec(query)
  }

  def nextNonceIndex(): Future[Int] = {
    val query = table.map(_.nonceIndex).max

    safeDatabase.run(query.result).map {
      case None        => 0
      case Some(value) => value + 1
    }
  }

  class AliceTable(tag: Tag) extends Table[AliceDb](tag, schemaName, "alices") {

    def peerId: Rep[Sha256Digest] = column("peer_id", O.PrimaryKey)

    def roundId: Rep[Sha256Digest] = column("round_id")

    def purpose: Rep[HDPurpose] = column("purpose")

    def coin: Rep[HDCoinType] = column("coin")

    def account: Rep[HDAccount] = column("account")

    def chain: Rep[HDChainType] = column("chain")

    def nonceIndex: Rep[Int] = column("nonce_index")

    def blindedOutputOpt: Rep[Option[FieldElement]] = column("blinded_output")

    def changeOutputOpt: Rep[Option[TransactionOutput]] = column(
      "change_output")

    def blindOutputSigOpt: Rep[Option[FieldElement]] = column(
      "blind_output_sig")

    def * : ProvenShape[AliceDb] =
      (peerId,
       roundId,
       purpose,
       coin,
       account,
       chain,
       nonceIndex,
       blindedOutputOpt,
       changeOutputOpt,
       blindedOutputOpt).<>(AliceDb.tupled, AliceDb.unapply)
  }
}
