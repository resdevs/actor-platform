package im.actor.server.sequence

import com.google.protobuf.ByteString
import im.actor.api.rpc.counters.UpdateCountersChanged
import im.actor.api.rpc.groups._
import im.actor.api.rpc.sequence.{ApiUpdateOptimization, UpdateEmptyUpdate}
import im.actor.server.messaging.MessageParsing
import im.actor.server.model.SerializedUpdate

object Optimization extends MessageParsing {
  type UpdateHeader = Int
  type Func = SerializedUpdate ⇒ SerializedUpdate

  private val emptyUpdate = SerializedUpdate(
    header = UpdateEmptyUpdate.header,
    body = ByteString.copyFrom(UpdateEmptyUpdate.toByteArray)
  )

  private val EmptyFunc: Func = identity[SerializedUpdate]

  // this is our default client we must support.
  // it is
  private val noOptimizationTransformation: Map[ApiUpdateOptimization.ApiUpdateOptimization, Func] = Map(
    ApiUpdateOptimization.STRIP_COUNTERS → EmptyFunc,
    ApiUpdateOptimization.GROUPS_V2 → { upd: SerializedUpdate ⇒
      val excludeUpdates = Set(
        UpdateGroupAboutChanged.header,
        UpdateGroupAvatarChanged.header,
        UpdateGroupTopicChanged.header,
        UpdateGroupTitleChanged.header,

        UpdateGroupOwnerChanged.header,
        UpdateGroupHistoryShared.header,
        UpdateGroupCanSendMessagesChanged.header,
        UpdateGroupCanViewMembersChanged.header,
        UpdateGroupCanInviteMembersChanged.header,
        UpdateGroupMemberChanged.header,
        UpdateGroupMembersBecameAsync.header,
        UpdateGroupMembersUpdated.header,
        UpdateGroupMemberDiff.header,
        UpdateGroupMembersCountChanged.header,
        UpdateGroupMemberAdminChanged.header
      )
      excludeIfContains(excludeUpdates, upd)
    }
  )

  val Default: Func = Function.chain(noOptimizationTransformation.values.toSeq)

  private val optimizationTransformation: Map[ApiUpdateOptimization.ApiUpdateOptimization, Func] = Map(
    ApiUpdateOptimization.STRIP_COUNTERS → { upd: SerializedUpdate ⇒
      val excludeUpdates = Set(UpdateCountersChanged.header)
      excludeIfContains(excludeUpdates, upd)
    },
    ApiUpdateOptimization.GROUPS_V2 → { upd: SerializedUpdate ⇒
      val excludeUpdates = Set(
        UpdateGroupInviteObsolete.header,
        UpdateGroupUserInvitedObsolete.header,
        UpdateGroupUserLeaveObsolete.header,
        UpdateGroupUserKickObsolete.header,
        UpdateGroupMembersUpdateObsolete.header,
        UpdateGroupTitleChangedObsolete.header,
        UpdateGroupTopicChangedObsolete.header,
        UpdateGroupAboutChangedObsolete.header,
        UpdateGroupAvatarChangedObsolete.header
      )
      excludeIfContains(excludeUpdates, upd)
    }
  )

  private def excludeIfContains(excludeUpdates: Set[UpdateHeader], upd: SerializedUpdate): SerializedUpdate =
    if (excludeUpdates contains upd.header) emptyUpdate else upd

  def apply(optimizations: Seq[Int]): Func = {
    val enabledOptimizations = optimizations map { optIndex ⇒
      val opt = ApiUpdateOptimization(optIndex)
      opt → optimizationTransformation(opt)
    }
    Function.chain((noOptimizationTransformation ++ enabledOptimizations).values.toSeq)
  }
}
