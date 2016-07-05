package im.actor.server.group.v2

import java.time.Instant

import akka.persistence.SnapshotMetadata
import im.actor.api.rpc.collections.ApiMapValue
import im.actor.server.cqrs.{ Event, ProcessorState }
import im.actor.server.file.Avatar
import im.actor.server.group.GroupType
import im.actor.server.group.GroupEvents._

private[group] final case class Member(
  userId:        Int,
  inviterUserId: Int,
  invitedAt:     Instant,
  isAdmin:       Boolean
)

private[group] final case class Bot(
  userId: Int,
  token:  String
)

private[group] object GroupState {
  def empty(groupId: Int): GroupState =
    GroupState(
      id = groupId,
      typ = GroupType.General,
      accessHash = 0L,
      creatorUserId = 0,
      ownerUserId = 0,
      createdAt = Instant.now(), //???
      members = Map.empty,
      invitedUserIds = Set.empty,
      title = "",
      about = None,
      bot = None,
      avatar = None,
      isHidden = false,
      topic = None,
      isHistoryShared = false,
      extensions = Seq.empty
    )
}

//TODO: add membersCount
private[group] final case class GroupState(
  id:              Int,
  typ:             GroupType,
  accessHash:      Long,
  creatorUserId:   Int,
  ownerUserId:     Int,
  createdAt:       Instant,
  members:         Map[Int, Member],
  invitedUserIds:  Set[Int],
  title:           String,
  about:           Option[String],
  bot:             Option[Bot],
  avatar:          Option[Avatar],
  topic:           Option[String],
  isHidden:        Boolean,
  isHistoryShared: Boolean,
  extensions:      Seq[ApiMapValue]
) extends ProcessorState[GroupState] {

  def isMember(userId: Int): Boolean = members.contains(userId)

  def nonMember(userId: Int): Boolean = !isMember(userId)

  def isInvited(userId: Int): Boolean = invitedUserIds.contains(userId)

  def isBot(userId: Int): Boolean = userId == 0 || (bot exists (_.userId == userId))

  def isAdmin(userId: Int): Boolean = members.get(userId) exists (_.isAdmin)

  //  def canInvitePeople(group: GroupState, clientUserId: Int) =
  //    isMember(group, clientUserId)

  def canViewMembers(group: GroupState, userId: Int) =
    (group.typ.isGeneral || group.typ.isPublic) && isMember(userId)

  override def updated(e: Event): GroupState = e match {
    case BotAdded(_, userId, token) ⇒
      this.copy(
        bot = Some(Bot(userId, token))
      )
    case UserInvited(ts, userId, inviterUserId) ⇒
      this.copy(
        members = members +
        (userId →
          Member(
            userId,
            inviterUserId,
            invitedAt = ts,
            isAdmin = userId == creatorUserId
          )),
        invitedUserIds = invitedUserIds + userId
      )
    case UserJoined(ts, userId, inviterUserId) ⇒
      this.copy(
        members = members +
        (userId →
          Member(
            userId,
            inviterUserId,
            ts,
            isAdmin = userId == creatorUserId
          )),
        invitedUserIds = invitedUserIds - userId
      )
    case UserKicked(_, userId, kickerUserId) ⇒
      this.copy(
        members = members - userId,
        invitedUserIds = invitedUserIds - userId
      )
    case UserLeft(_, userId) ⇒
      this.copy(
        members = members - userId,
        invitedUserIds = invitedUserIds - userId
      )
    case AvatarUpdated(_, newAvatar) ⇒
      this.copy(avatar = newAvatar)
    case TitleUpdated(_, newTitle) ⇒
      this.copy(title = newTitle)
    case BecamePublic(_) ⇒
      this.copy(
        typ = GroupType.Public,
        isHistoryShared = true
      )
    case AboutUpdated(_, newAbout) ⇒
      this.copy(about = newAbout)
    case TopicUpdated(_, newTopic) ⇒
      this.copy(topic = newTopic)
    case UserBecameAdmin(_, userId, _) ⇒
      this.copy(
        members = members.updated(userId, members(userId).copy(isAdmin = true))
      )
    case IntegrationTokenRevoked(_, newToken) ⇒
      this.copy(
        bot = bot.map(_.copy(token = newToken))
      )
    case OwnerChanged(_, userId) ⇒
      this.copy(ownerUserId = userId)
  }

  // TODO: real snapshot
  def withSnapshot(metadata: SnapshotMetadata, snapshot: Any): GroupState = this
}
