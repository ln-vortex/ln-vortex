package com.lnvortex.server.models

import com.lnvortex.server.config.VortexCoordinatorAppConfig
import org.bitcoins.core.hd._
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.crypto._
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

  import mappers._

  implicit val doubleSha256DigestMapper: BaseColumnType[DoubleSha256Digest] =
    MappedColumnType.base[DoubleSha256Digest, String](
      _.hex,
      DoubleSha256Digest.fromHex)

  override val table: TableQuery[AliceTable] = TableQuery[AliceTable]

  override def createAll(ts: Vector[AliceDb]): Future[Vector[AliceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[AliceTable, AliceDb, Seq] =
    table.filter(_.peerId.inSet(ids))

  override protected def findAll(
      ts: Vector[AliceDb]): Query[AliceTable, AliceDb, Seq] =
    findByPrimaryKeys(ts.map(_.peerId))

  def findByRoundId(roundId: DoubleSha256Digest): Future[Vector[AliceDb]] = {
    val query = table.filter(_.roundId === roundId).result

    safeDatabase.runVec(query)
  }

  def findRegisteredForRound(
      roundId: DoubleSha256Digest): Future[Vector[AliceDb]] = {
    val query = table
      .filter(t => t.roundId === roundId && t.blindOutputSigOpt.isDefined)
      .result

    safeDatabase.runVec(query.transactionally)
  }

  def numRegisteredForRound(roundId: DoubleSha256Digest): Future[Int] = {
    val query = table
      .filter(t => t.roundId === roundId && t.blindOutputSigOpt.isDefined)
      .map(_.peerId)
      .distinct
      .size

    safeDatabase.run(query.result.transactionally)
  }

  def getPeerIdSigMap(roundId: DoubleSha256Digest): Future[
    Vector[(Sha256Digest, FieldElement)]] = {
    val query = table
      .filter(t => t.roundId === roundId && t.blindOutputSigOpt.isDefined)
      .map(t => (t.peerId, t.blindOutputSigOpt.get))

    safeDatabase.runVec(query.result.transactionally)
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

    def roundId: Rep[DoubleSha256Digest] = column("round_id")

    def purpose: Rep[HDPurpose] = column("purpose")

    def coin: Rep[HDCoinType] = column("coin")

    def accountIdx: Rep[Int] = column("account")

    def chain: Rep[HDChainType] = column("chain")

    def nonceIndex: Rep[Int] = column("nonce_index")

    def nonce: Rep[SchnorrNonce] = column("nonce")

    def numInputs: Rep[Int] = column("num_inputs")

    def blindedOutputOpt: Rep[Option[FieldElement]] = column("blinded_output")

    def changeSpkOpt: Rep[Option[ScriptPubKey]] = column("change_spk")

    def blindOutputSigOpt: Rep[Option[FieldElement]] = column("blind_sig")

    def signed: Rep[Boolean] = column("signed")

    def * : ProvenShape[AliceDb] =
      (peerId,
       roundId,
       purpose,
       coin,
       accountIdx,
       chain,
       nonceIndex,
       nonce,
       numInputs,
       blindedOutputOpt,
       changeSpkOpt,
       blindOutputSigOpt,
       signed).<>(AliceDb.tupled, AliceDb.unapply)
  }
}
