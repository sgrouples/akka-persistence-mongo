package akka.contrib.persistence.mongodb

import play.api.libs.iteratee.Iteratee
import reactivemongo.api._
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api.commands.{DefaultWriteResult, WriteResult, WriteConcern}
import reactivemongo.api.indexes.{IndexType, Index}
import reactivemongo.bson._

import akka.actor.ActorSystem

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.language.implicitConversions
import scala.util.{Failure, Success}

object RxMongoPersistenceDriver {
  import MongoPersistenceDriver._

  def toWriteConcern(writeSafety: WriteSafety, wtimeout: Duration, fsync: Boolean):WriteConcern = (writeSafety,wtimeout.toMillis.toInt,fsync) match {
    case (Unacknowledged,wt,f) =>
      WriteConcern.Unacknowledged.copy(fsync = f, wtimeout = Option(wt))
    case (Acknowledged,wt,f) =>
      WriteConcern.Acknowledged.copy(fsync = f, wtimeout = Option(wt))
    case (Journaled,wt,_) =>
      WriteConcern.Journaled.copy(wtimeout = Option(wt))
    case (ReplicaAcknowledged,wt,f) =>
      WriteConcern.ReplicaAcknowledged(2, wt, !f)
  }
}

class RxMongoDriver(actorSystem: ActorSystem) extends MongoPersistenceDriver(actorSystem) {
  import RxMongoPersistenceDriver._
  import concurrent.Await
  import concurrent.duration._

  // Collection type
  type C = BSONCollection

  type D = BSONDocument

  private[mongodb] lazy val driver = MongoDriver()
  private[this] lazy val parsedMongoUri = MongoConnection.parseURI(mongoUri) match {
    case Success(parsed) => parsed
    case Failure(throwable) => throw throwable
  }
  private[mongodb] lazy val connection =
    waitForPrimary(driver.connection(parsedURI = parsedMongoUri))

  private[this] def waitForPrimary(conn: MongoConnection): MongoConnection = {
    Await.result(conn.waitForPrimary(3.seconds),4.seconds)
    conn
  }

  def walk(collection: BSONCollection)(previous: Future[WriteResult], doc: BSONDocument)(implicit ec: ExecutionContext): Cursor.State[Future[WriteResult]] = {
    import scala.collection.immutable.{Seq => ISeq}
    import RxMongoSerializers._
    import DefaultBSONHandlers._
    import Producer._

    val id = doc.getAs[BSONObjectID]("_id").get
    val ev = deserializeJournal(doc)
    val q = BSONDocument("_id" -> id)
    println(s"q = ${q.elements.toList} ev = $ev")

//    collection.update(q, serializeJournal(Atom(ev.pid, ev.sn, ev.sn, ISeq(ev))))

    // Wait for previous record to be updated
    val wr = previous.flatMap(_ =>
      collection.update(BSONDocument("_id" -> id), serializeJournal(Atom(ev.pid, ev.sn, ev.sn, ISeq(ev))))
    )

    Cursor.Cont(wr)
  }

  override private[mongodb] def upgradeJournalIfNeeded(): Unit = {
    import concurrent.ExecutionContext.Implicits.global
    import JournallingFieldNames._

    val j = collection(journalCollectionName)
    val walker = walk(j) _
    val q = BSONDocument(VERSION -> BSONDocument("$exists" -> 0))
    val empty: Future[WriteResult] = Future.successful(DefaultWriteResult(
      ok = true, n = 0,
      writeErrors = Seq.empty, writeConcernError = None,
      code = None, errmsg = None
    ))

    val eventuallyUpgrade = for {
      count <- j.count(Option(q))
        if count > 0
      wr <- j.find(q).cursor[BSONDocument]().foldWhile(empty)(walker, (_,t) => Cursor.Fail(t)).flatMap(identity)
    } yield wr

    Await.result(eventuallyUpgrade, 2.minutes) // ouch

    ()
  }

  private[mongodb] def closeConnections(): Unit = driver.close()

  private[mongodb] def db = connection(parsedMongoUri.db.getOrElse(DEFAULT_DB_NAME))(actorSystem.dispatcher)

  private[mongodb] override def collection(name: String) = db[BSONCollection](name)
  private[mongodb] def journalWriteConcern: WriteConcern = toWriteConcern(journalWriteSafety,journalWTimeout,journalFsync)
  private[mongodb] def snapsWriteConcern: WriteConcern = toWriteConcern(snapsWriteSafety,snapsWTimeout,snapsFsync)

  private[mongodb] override def ensureUniqueIndex(collection: C, indexName: String, keys: (String,Int)*)(implicit ec: ExecutionContext) = {
    val ky = keys.toSeq.map{ case (f,o) => f -> (if (o > 0) IndexType.Ascending else IndexType.Descending)}
    collection.indexesManager.ensure(new Index(
      key = ky,
      background = true,
      unique = true,
      name = Some(indexName)))
    collection
  }
}

class RxMongoPersistenceExtension(actorSystem: ActorSystem) extends MongoPersistenceExtension {

  private[this] lazy val driver = new RxMongoDriver(actorSystem)
  private[this] lazy val _journaler = new RxMongoJournaller(driver) with MongoPersistenceJournalMetrics with MongoPersistenceJournalFailFast {
    override def driverName = "rxmongo"
    override private[mongodb] val breaker = driver.breaker
  }
  private[this] lazy val _snapshotter = new RxMongoSnapshotter(driver) with MongoPersistenceSnapshotFailFast {
    override private[mongodb] val breaker = driver.breaker
  }

  override def journaler = _journaler
  override def snapshotter = _snapshotter
} 
