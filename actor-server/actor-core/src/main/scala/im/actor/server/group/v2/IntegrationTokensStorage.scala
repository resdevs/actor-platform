package im.actor.server.group.v2

import java.time.Instant

import akka.actor.ActorSystem
import akka.util.Timeout
import com.google.protobuf.wrappers.{ Int32Value, Int64Value, StringValue }
import im.actor.server.KeyValueMappings
import im.actor.server.db.DbExtension
import im.actor.server.group.GroupExtension
import im.actor.storage.{ Connector, SimpleStorage }
import org.slf4j.LoggerFactory
import shardakka.{ IntCodec, ShardakkaExtension }

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Stores mapping "Group id" -> "Integration token"
 * name: String
 * timestamp: Long
 */
private object IntegrationTokensStorage extends SimpleStorage("group_integration_token")

//TODO: find better name
final class IntegrationTokensStorage(groupId: Int, createdAt: Long)(implicit system: ActorSystem) {
  import system.dispatcher

  private val groupV2Ts = GroupExtension(system).GroupV2MigrationTs

  val (upsert, delete): (String ⇒ Future[Unit], String ⇒ Future[Unit]) = if (createdAt > groupV2Ts) {
    // we user db key-value for new groups
    val conn = DbExtension(system).connector
    val upsert = { token: String ⇒
      conn.run(
        IntegrationTokensStorage.upsert(token, Int32Value(groupId).toByteArray)
      ) map (_ ⇒ ())
    }
    val delete = { token: String ⇒
      conn.run(
        IntegrationTokensStorage.delete(token)
      ) map (_ ⇒ ())
    }
    (upsert, delete)
  } else {
    // we user shardakka for legacy groups
    val kv = ShardakkaExtension(system).simpleKeyValue[Int](KeyValueMappings.IntegrationTokens, IntCodec)
    implicit val timeout = Timeout(20.seconds)
    (kv.upsert(_, groupId), kv.delete)
  }
}
