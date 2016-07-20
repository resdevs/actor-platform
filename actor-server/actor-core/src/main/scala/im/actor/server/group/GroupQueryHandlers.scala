package im.actor.server.group

import akka.http.scaladsl.util.FastFuture
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.google.protobuf.ByteString
import com.google.protobuf.wrappers.Int32Value
import im.actor.api.rpc.groups._
import im.actor.server.group.GroupErrors.{ NoPermission, NotOwner }
import im.actor.server.group.GroupQueries._
import im.actor.server.group.GroupType.{ Channel, General, Unrecognized }

import scala.concurrent.Future

trait GroupQueryHandlers {
  self: GroupProcessor ⇒

  import im.actor.server.ApiConversions._

  protected def getAccessHash =
    FastFuture.successful(GetAccessHashResponse(state.accessHash))

  protected def getTitle =
    FastFuture.successful(GetTitleResponse(state.title))

  protected def getIntegrationToken(optUserId: Option[Int]): Future[GetIntegrationTokenResponse] = {
    val canViewToken = optUserId.forall(state.isAdmin)
    val allowedToView = optUserId.forall(state.isMember)
    if (allowedToView) {
      FastFuture.successful(GetIntegrationTokenResponse(
        if (canViewToken) state.bot.map(_.token) else None
      ))
    } else {
      FastFuture.failed(NoPermission)
    }
  }

  //TODO: do something with this method. Will this method used in "client" context.
  // If not - don't change it. Maybe rename to `getMembersInternal`
  protected def getMembers: Future[GetMembersResponse] =
    FastFuture.successful {
      GetMembersResponse(
        memberIds = state.memberIds.toSeq,
        invitedUserIds = state.invitedUserIds.toSeq,
        botId = state.bot.map(_.userId)
      )
    }

  //TODO: rewrite to sort by online + name. Won't work like this
  // we can subscribe group object to group onlines! When online comes, we reorder key-set. Use that key set as source.
  protected def loadMembers(clientUserId: Int, limit: Int, offsetBs: Option[ByteString]): Future[LoadMembersResponse] = {
    def load = {
      implicit val mat = ActorMaterializer()
      val offset = offsetBs map (_.toByteArray) map (Int32Value.parseFrom(_).value) getOrElse 0

      for {
        (userIds, nextOffset) ← Source(state.members.keySet)
          .mapAsync(1)(userId ⇒ userExt.getName(userId, clientUserId) map (userId → _))
          .runFold(Vector.empty[(Int, String)])(_ :+ _) map { users ⇒
            val tail = users.sortBy(_._2).map(_._1).drop(offset)
            val nextOffset = if (tail.length > limit) Some(Int32Value(offset + limit).toByteArray) else None
            (tail.take(limit), nextOffset)
          }
      } yield LoadMembersResponse(
        userIds = userIds,
        offset = nextOffset map ByteString.copyFrom
      )
    }

    state.groupType match {
      case General ⇒ load
      case Channel ⇒
        if (state.isAdmin(clientUserId)) load
        else FastFuture.successful(LoadMembersResponse(Seq.empty, offsetBs))
    }
  }

  protected def isChannel =
    FastFuture.successful(IsChannelResponse(state.groupType.isChannel))

  protected def isHistoryShared =
    FastFuture.successful(IsHistorySharedResponse(state.isHistoryShared))

  //TODO: add ext!
  //TODO: what if state changes during request?
  protected def getApiStruct(clientUserId: Int) = {
    val isMember = state.isMember(clientUserId)
    val (members, count) = membersAndCount(state, clientUserId)

    FastFuture.successful {
      GetApiStructResponse(
        ApiGroup(
          groupId,
          accessHash = state.accessHash,
          title = state.title,
          avatar = state.avatar,
          isMember = Some(isMember),
          creatorUserId = state.creatorUserId,
          members = members,
          createDate = extractCreatedAtMillis(state),
          isAdmin = Some(state.isAdmin(clientUserId)),
          theme = state.topic,
          about = state.about,
          isHidden = Some(state.isHidden),
          ext = None,
          membersCount = Some(count),
          groupType = Some(state.groupType match {
            case Channel                   ⇒ ApiGroupType.CHANNEL
            case General | Unrecognized(_) ⇒ ApiGroupType.GROUP
          }),
          canSendMessage = Some(state.permissions.canSendMessage(clientUserId))
        )
      )
    }
  }

  //TODO: add ext!
  protected def getApiFullStruct(clientUserId: Int) =
    FastFuture.successful {
      GetApiFullStructResponse(
        ApiGroupFull(
          groupId,
          theme = state.topic,
          about = state.about,
          ownerUserId = state.getShowableOwner(clientUserId),
          createDate = extractCreatedAtMillis(state),
          ext = None,
          canViewMembers = Some(state.permissions.canViewMembers(clientUserId)),
          canInvitePeople = Some(state.permissions.canInvitePeople(clientUserId)),
          isSharedHistory = Some(state.isHistoryShared),
          isAsyncMembers = Some(state.isAsyncMembers),
          members = membersAndCount(state, clientUserId)._1,
          shortName = state.shortName,
          canEditGroupInfo = Some(state.permissions.canEditInfo(clientUserId)),
          canEditShortName = Some(state.permissions.canEditShortName(clientUserId)),
          canEditAdminList = Some(state.permissions.canEditAdmins(clientUserId)),
          canViewAdminList = Some(state.permissions.canViewAdmins(clientUserId)),
          canEditAdminSettings = Some(state.permissions.canEditAdminSettings(clientUserId)),
          canInviteViaLink = Some(state.permissions.canInviteViaLink(clientUserId)),
          canDelete = Some(state.permissions.canDelete(clientUserId)),
          canLeave = Some(state.permissions.canLeave(clientUserId))
        )
      )
    }

  protected def checkAccessHash(hash: Long) =
    FastFuture.successful(CheckAccessHashResponse(isCorrect = state.accessHash == hash))

  protected def canSendMessage(clientUserId: Int): Future[CanSendMessageResponse] =
    FastFuture.successful {
      val canSend = state.bot.exists(_.userId == clientUserId) || {
        state.groupType match {
          case General ⇒ state.isMember(clientUserId)
          case Channel ⇒ state.isAdmin(clientUserId)
        }
      }
      CanSendMessageResponse(
        canSend = canSend,
        isChannel = state.groupType.isChannel,
        memberIds = state.memberIds.toSeq,
        botId = state.bot.map(_.userId)
      )
    }

  protected def loadAdminSettings(clientUserId: Int): Future[LoadAdminSettingsResponse] = {
    if (state.permissions.canEditAdminSettings(clientUserId)) {
      FastFuture.successful {
        LoadAdminSettingsResponse(
          ApiAdminSettings(
            showAdminsToMembers = state.adminSettings.showAdminsToMembers,
            canMembersInvite = state.adminSettings.canMembersInvite,
            canMembersEditGroupInfo = state.adminSettings.canMembersEditGroupInfo,
            canAdminsEditGroupInfo = state.adminSettings.canAdminsEditGroupInfo
          )
        )
      }
    } else {
      FastFuture.failed(NotOwner)
    }
  }

  private def extractCreatedAtMillis(group: GroupState): Long =
    group.createdAt.map(_.toEpochMilli).getOrElse(throw new RuntimeException("No date created provided for group!"))

  /**
   * Return group members, and number of members.
   * If `clientUserId` is not a group member, return empty members list and 0
   * For `General` and `Public` groups return all members and their number.
   * For `Channel` return members list only if `clientUserId` is group admin. Otherwise return empty members list and real members count
   */
  private def membersAndCount(group: GroupState, clientUserId: Int): (Vector[ApiMember], Int) = {
    def apiMembers = group.members.toVector map {
      case (_, m) ⇒
        ApiMember(m.userId, m.inviterUserId, m.invitedAt.toEpochMilli, Some(m.isAdmin))
    }

    if (state.isMember(clientUserId)) {
      state.groupType match {
        case General ⇒
          apiMembers → group.membersCount
        case Channel ⇒
          if (state.isAdmin(clientUserId))
            apiMembers → group.membersCount
          else
            apiMembers.find(_.userId == clientUserId).toVector → group.membersCount
      }
    } else {
      Vector.empty[ApiMember] → 0
    }
  }

}
