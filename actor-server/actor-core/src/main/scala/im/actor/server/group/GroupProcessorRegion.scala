package im.actor.server.group

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import im.actor.server.groupV2.GroupEnvelopeV2
import im.actor.server.group.v2.GroupProcessorV2

object GroupProcessorRegion {
  private def extractEntityId(system: ActorSystem): ShardRegion.ExtractEntityId = {
    case GroupEnvelopeV2(Some(groupId), command, query) ⇒
      (
        groupId.toString,
        if (command.isDefined) command else query // payload
      )
  }

  private def extractShardId(system: ActorSystem): ShardRegion.ExtractShardId = {
    case GroupEnvelopeV2(Some(groupId), _, _) ⇒ (groupId % 100).toString // TODO: configurable
  }

  private val typeName = "GroupProcessor"

  private def start(props: Props)(implicit system: ActorSystem): GroupProcessorRegion =
    GroupProcessorRegion(ClusterSharding(system).start(
      typeName = typeName,
      entityProps = props,
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId(system),
      extractShardId = extractShardId(system)
    ))

  def start()(implicit system: ActorSystem): GroupProcessorRegion = start(GroupProcessorV2.props)

  def startProxy()(implicit system: ActorSystem): GroupProcessorRegion =
    GroupProcessorRegion(ClusterSharding(system).startProxy(
      typeName = typeName,
      role = None,
      extractEntityId = extractEntityId(system),
      extractShardId = extractShardId(system)
    ))
}

case class GroupProcessorRegion(ref: ActorRef)

case class GroupViewRegion(ref: ActorRef)
