package im.actor.server.group

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import akka.actor.Status
import akka.http.scaladsl.util.FastFuture
import akka.pattern.pipe
import im.actor.api.rpc.Update
import im.actor.api.rpc.files.ApiAvatar
import im.actor.api.rpc.groups._
import im.actor.api.rpc.users.ApiSex
import im.actor.concurrent.FutureExt
import im.actor.server.CommonErrors
import im.actor.server.acl.ACLUtils
import im.actor.server.dialog.UserAcl
import im.actor.server.file.{ Avatar, ImageUtils }
import im.actor.server.group.GroupErrors._
import im.actor.server.group.GroupEvents.{ AboutUpdated, AvatarUpdated, BotAdded, Created, IntegrationTokenRevoked, OwnerChanged, TitleUpdated, TopicUpdated, UserBecameAdmin, UserInvited, UserJoined, UserKicked, UserLeft }
import im.actor.server.group.GroupCommands._
import im.actor.server.model.{ AvatarData, Group }
import im.actor.server.office.PushTexts
import im.actor.server.persist.{ AvatarDataRepo, GroupBotRepo, GroupInviteTokenRepo, GroupRepo, GroupUserRepo }
import im.actor.server.sequence.{ SeqState, SeqStateDate }
import im.actor.util.ThreadLocalSecureRandom
import im.actor.util.misc.IdUtils

import scala.concurrent.Future

private[group] trait GroupCommandHandlers extends GroupsImplicits with UserAcl {
  self: GroupProcessor ⇒

  import im.actor.server.ApiConversions._

  private val notMember = Status.Failure(NotAMember)
  private val notAdmin = Status.Failure(NotAdmin)

  protected def create(cmd: Create): Unit = {
    if (!isValidTitle(cmd.title)) {
      sender() ! Status.Failure(InvalidTitle)
    } else {
      val rng = ThreadLocalSecureRandom.current()
      val accessHash = ACLUtils.randomLong(rng)

      // exclude ids of users, who blocked group creator
      val resolvedUserIds = FutureExt.ftraverse(cmd.userIds.filterNot(_ == cmd.creatorUserId)) { userId ⇒
        withNonBlockedUser(cmd.creatorUserId, userId)(
          default = FastFuture.successful(Some(userId)),
          failed = FastFuture.successful(None)
        )
      } map (_.flatten)

      // send invites to all users, that creator can invite
      for {
        userIds ← resolvedUserIds
        _ = userIds foreach (u ⇒ context.parent ! Invite(groupId, u, cmd.creatorAuthId, rng.nextLong()))
      } yield ()

      // Group creation
      persist(Created(
        ts = Instant.now,
        groupId,
        typ = None, //Some(cmd.typ),
        creatorUserId = cmd.creatorUserId,
        accessHash = accessHash,
        title = cmd.title,
        userIds = Seq(cmd.creatorUserId), // only creator user becomes group member. all other users are invited via Invite message
        isHidden = Some(false),
        isHistoryShared = Some(false),
        extensions = Seq.empty
      )) { evt ⇒
        val newState = commit(evt)

        val date = evt.ts
        val dateMillis = date.toEpochMilli

        val updateObsolete = UpdateGroupInviteObsolete(
          groupId,
          inviteUserId = cmd.creatorUserId,
          date = dateMillis,
          randomId = cmd.randomId
        )

        val creatorUpdatesNew = refreshGroupUpdates(newState)

        val serviceMessage = GroupServiceMessages.groupCreated

        //TODO: remove deprecated
        db.run(
          for {
            _ ← GroupRepo.create(
              Group(
                id = groupId,
                creatorUserId = newState.creatorUserId,
                accessHash = newState.accessHash,
                title = newState.title,
                isPublic = newState.typ == GroupType.Public,
                createdAt = evt.ts,
                about = None,
                topic = None
              ),
              cmd.randomId,
              isHidden = false
            )
            _ ← GroupUserRepo.create(groupId, cmd.creatorUserId, cmd.creatorUserId, date, None, isAdmin = true)
          } yield ()
        )

        val result: Future[CreateAck] = for {

          // old group api update
          _ ← seqUpdExt.deliverUserUpdate(
            userId = cmd.creatorUserId,
            update = updateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, None, excludeAuthIds = Seq(cmd.creatorAuthId)), //do we really need to remove self auth id here?
            reduceKey = None,
            deliveryId = s"creategroup_${groupId}_${cmd.randomId}"
          )
          // new group api updates

          // send update about group info to group creator
          _ ← FutureExt.ftraverse(creatorUpdatesNew) { update ⇒
            seqUpdExt.deliverUserUpdate(userId = cmd.creatorUserId, update)
          }

          // send service message to group
          seqStateDate ← dialogExt.sendServerMessage(
            apiGroupPeer,
            senderUserId = cmd.creatorUserId,
            senderAuthId = cmd.creatorAuthId,
            randomId = cmd.randomId,
            message = serviceMessage
          )

        } yield CreateAck(newState.accessHash).withSeqStateDate(seqStateDate)

        result pipeTo sender() onFailure {
          case e ⇒
            log.error(e, "Failed to create a group")
        }
      }

      //Adding bot to group
      val botUserId = IdUtils.nextIntId(rng)
      val botToken = ACLUtils.accessToken(rng)

      persist(BotAdded(Instant.now, botUserId, botToken)) { evt ⇒
        val newState = commit(evt)

        (for {
          _ ← userExt.create(botUserId, ACLUtils.nextAccessSalt(), None, "Bot", "US", ApiSex.Unknown, isBot = true)
          _ ← db.run(GroupBotRepo.create(groupId, botUserId, botToken))
          _ ← integrationStorage.upsert(botToken)
        } yield ()) onFailure {
          case e ⇒
            log.error(e, "Failed to create group bot")
        }
      }
    }
  }

  protected def invite(cmd: Invite): Unit = {
    if (state.isInvited(cmd.inviteeUserId)) {
      sender() ! Status.Failure(GroupErrors.UserAlreadyInvited)
    } else if (state.isMember(cmd.inviteeUserId)) {
      sender() ! Status.Failure(GroupErrors.UserAlreadyJoined)
    } else {
      val inviteeIsExUser = state.isExUser(cmd.inviteeUserId)

      persist(UserInvited(Instant.now, cmd.inviteeUserId, cmd.inviterUserId)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val memberIds = newState.memberIds
        val members = newState.members.values.map(_.asStruct).toVector

        // if user ever been in this group - we should push these updates,
        // but don't push them if user is first time in group. in this case we should push FatSeqUpdate
        //TODO: duplication - move to method
        val inviteeUpdatesNew: List[Update] = refreshGroupUpdates(newState)

        // отправить всем, в том числе тому кто вошел в группу.
        // отправить этот апдейт как фат тому кто приглашен, если он приглашен первый раз
        val membersUpdateNew = UpdateGroupMembersUpdated(groupId, members)

        val inviteeUpdateObsolete = UpdateGroupInviteObsolete(
          groupId,
          inviteUserId = cmd.inviterUserId,
          date = dateMillis,
          randomId = cmd.randomId
        )

        val membersUpdateObsolete = UpdateGroupUserInvitedObsolete(
          groupId,
          userId = cmd.inviteeUserId,
          inviterUserId = cmd.inviterUserId,
          date = dateMillis,
          randomId = cmd.randomId
        )
        val serviceMessage = GroupServiceMessages.userInvited(cmd.inviteeUserId)

        db.run(GroupUserRepo.create(groupId, cmd.inviteeUserId, cmd.inviterUserId, evt.ts, None, isAdmin = false))
        val result: Future[SeqStateDate] = for {
          // old group api updates
          ///////////////////////////////////////////////
          // push "Invited" to invitee
          _ ← seqUpdExt.deliverUserUpdate(
            userId = cmd.inviteeUserId,
            inviteeUpdateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, Some(PushTexts.Invited)),
            deliveryId = s"invite_${groupId}_${cmd.randomId}"
          )

          // push "User added" to all group members except for `inviterUserId`
          _ ← seqUpdExt.broadcastPeopleUpdate(
            (memberIds - cmd.inviteeUserId) - cmd.inviterUserId, // is it right?
            membersUpdateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, Some(PushTexts.Added)),
            deliveryId = s"useradded_${groupId}_${cmd.randomId}"
          )

          // push "User added" to `inviterUserId` and return SeqState

          // по идее - это бред! если мы изымаем апдейты из цепочки, seq не поменяется у клиента.
          // поэтому можно брать просто последний апдейт!

          obsoleteSeqState ← seqUpdExt.deliverClientUpdate(
            cmd.inviterUserId,
            cmd.inviterAuthId,
            membersUpdateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, None),
            deliveryId = s"useradded_${groupId}_${cmd.randomId}"
          )
          ///////////////////////////////////////////////

          // new group api updates

          // push updated members list to inviteeUserId,
          // TODO???: make it fat update if user never been to group before.
          _ ← seqUpdExt.deliverUserUpdate(
            userId = cmd.inviteeUserId,
            membersUpdateNew,
            pushRules = seqUpdExt.pushRules(isFat = !inviteeIsExUser, Some(PushTexts.Invited)),
            deliveryId = s"invite_${groupId}_${cmd.randomId}"
          )

          // push all group updates to inviteeUserId
          _ ← FutureExt.ftraverse(inviteeUpdatesNew) { update ⇒
            seqUpdExt.deliverUserUpdate(userId = cmd.inviteeUserId, update)
          }

          // push updated members list to all group members except inviteeUserId
          _ ← seqUpdExt.broadcastPeopleUpdate(
            (memberIds + cmd.inviterUserId) - cmd.inviteeUserId,
            membersUpdateNew,
            deliveryId = s"useradded_${groupId}_${cmd.randomId}"
          )

          // explicitly send service message
          seqStateDate ← dialogExt.sendServerMessage(
            apiGroupPeer,
            cmd.inviterUserId,
            cmd.inviterAuthId,
            cmd.randomId,
            serviceMessage
          )

          //          SeqState(seq, state) = if (cmd.isV2) newSeqState else obsoleteSeqState
        } yield seqStateDate

        result pipeTo sender()
      }
    }
  }

  // join может состояться либо по ссылке, либо при первом прочтении
  // если по ссылке, то
  protected def join(cmd: Join): Unit = {
    // user is already a member, and should not complete invitation process
    if (state.isMember(cmd.joiningUserId) && !state.isInvited(cmd.joiningUserId)) {
      sender() ! Status.Failure(GroupErrors.UserAlreadyJoined)
    } else {
      // user was invited in group by other group user
      val wasInvited = state.isInvited(cmd.joiningUserId)

      val optMember = state.members.get(cmd.joiningUserId)
      val inviterUserId = cmd.invitingUserId
        .orElse(optMember.map(_.inviterUserId))
        .getOrElse(state.creatorUserId)

      persist(UserJoined(Instant.now, cmd.joiningUserId, inviterUserId)) { evt ⇒
        val newState = commit(evt)

        val date = evt.ts
        val memberIds = newState.memberIds
        val members = newState.members.values.map(_.asStruct).toVector
        val randomId = ACLUtils.randomLong()

        // !!!!
        // если этот человек первый раз входит в группу, не через приглашение,
        // то нужно отрпавить ему все апдейты, по изменениям в группе,

        // If user was invited to group by other member - we don't need to push group updates,
        // cause they we pushed already on invite step
        val joiningUserUpdatesNew: List[Update] =
          if (wasInvited) List.empty[Update] else refreshGroupUpdates(newState)

        // нужно отправить ему апдейт о членах в группе.
        // всем нужно отправить апдейт о изменившихся членах в группе. можно в любом случае отправить

        // отправить всем, в том числе тому кто вошел в группу.
        // отправить этот апдейт как фат тому кто приглашен, если он приглашен первый раз
        // update date when member got into group
        val membersUpdateNew = UpdateGroupMembersUpdated(groupId, members)

        val membersUpdateObsolete = UpdateGroupMembersUpdateObsolete(groupId, members)

        val serviceMessage = GroupServiceMessages.userJoined

        db.run(GroupUserRepo.create(
          groupId,
          userId = cmd.joiningUserId,
          inviterUserId = inviterUserId,
          invitedAt = optMember.map(_.invitedAt).getOrElse(date),
          joinedAt = Some(LocalDateTime.now(ZoneOffset.UTC)),
          isAdmin = false
        ))
        val result: Future[(SeqStateDate, Vector[Int], Long)] =
          for {
            // old group api update
            //WTF???
            _ ← seqUpdExt.broadcastPeopleUpdate(
              memberIds - cmd.joiningUserId,
              membersUpdateObsolete,
              pushRules = seqUpdExt.pushRules(isFat = true, None),
              deliveryId = s"userjoined_${groupId}_${randomId}"
            )
            /////////////////////////////

            // new group api updates
            // push updated members list to joining user,
            // TODO???: isFat = !wasInvited - is it correct?
            _ ← seqUpdExt.deliverUserUpdate(
              userId = cmd.joiningUserId,
              membersUpdateNew,
              pushRules = seqUpdExt.pushRules(isFat = !wasInvited, None), //!wasInvited means that user came for first time here
              deliveryId = s"userjoined_${groupId}_${randomId}"
            )

            // push all group updates to inviteeUserId
            _ ← FutureExt.ftraverse(joiningUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.joiningUserId, update)
            }

            // push updated members list to all group members except joiningUserId
            _ ← seqUpdExt.broadcastPeopleUpdate(
              memberIds - cmd.joiningUserId,
              membersUpdateNew,
              deliveryId = s"join_${groupId}_${randomId}"
            )

            seqStateDate ← dialogExt.sendServerMessage(
              apiGroupPeer,
              senderUserId = cmd.joiningUserId,
              senderAuthId = cmd.joiningUserAuthId,
              randomId = randomId,
              serviceMessage
            )
          } yield (seqStateDate, memberIds.toVector :+ inviterUserId, randomId)

        result pipeTo sender()
      }
    }
  }

  protected def leave(cmd: Leave): Unit = {
    if (state.nonMember(cmd.userId)) {
      sender() ! notMember
    } else {
      persist(UserLeft(Instant.now, cmd.userId)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val members = newState.members.values.map(_.asStruct).toVector

        val updateObsolete = UpdateGroupUserLeaveObsolete(groupId, cmd.userId, dateMillis, cmd.randomId)

        val userUpdatesNew: List[Update] = List(
          UpdateGroupMemberChanged(groupId, isMember = false),
          UpdateGroupMembersUpdated(groupId, members = Vector.empty)
        )

        val membersUpdateNew = UpdateGroupMembersUpdated(groupId, members)

        val serviceMessage = GroupServiceMessages.userLeft(cmd.userId)

        db.run(
          for {
            _ ← GroupUserRepo.delete(groupId, cmd.userId)
            _ ← GroupInviteTokenRepo.revoke(groupId, cmd.userId)
          } yield ()
        )

        // оба:
        // • прочитываем диалог этого чувака им самим
        //
        // старый:
        // пушим клиенту и всем членам группы UpdateGroupUserLeaveObsolete, исключаем текущий клиент для пуша
        //
        // новый:
        // пушим UpdateGroupMemberChanged чуваку, что он перестал быть членом группы, и UpdateGroupMembersChanged с пустым списком
        // всем остальным членам группы пушим UpdateGroupMembersUpdated без этого чувака
        // отправляем после ухода сервисное сообщение
        val result: Future[SeqStateDate] = for {
          _ ← dialogExt.messageRead(apiGroupPeer, cmd.userId, 0L, dateMillis)

          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
            userId = cmd.userId,
            authId = cmd.authId,
            bcastUserIds = newState.memberIds + cmd.userId, // push this to other user's devices too
            update = updateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.Left), Seq(cmd.authId))
          )

          // new group api updates
          _ ← FutureExt.ftraverse(userUpdatesNew) { update ⇒
            seqUpdExt.deliverUserUpdate(userId = cmd.userId, update)
          }

          // push updated members list to all group members
          _ ← seqUpdExt.broadcastPeopleUpdate(
            newState.memberIds,
            membersUpdateNew
          )

          seqStateDate ← dialogExt.sendServerMessage(
            apiGroupPeer,
            senderUserId = cmd.userId,
            senderAuthId = cmd.authId,
            randomId = cmd.randomId,
            message = serviceMessage
          )
        } yield seqStateDate

        result pipeTo sender()
      }
    }
  }

  protected def kick(cmd: Kick): Unit = {
    if (state.nonMember(cmd.kickerUserId) || state.nonMember(cmd.kickedUserId)) {
      sender() ! notMember
    } else {
      persist(UserKicked(Instant.now, cmd.kickedUserId, cmd.kickerUserId)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val members = newState.members.values.map(_.asStruct).toVector

        val updateObsolete = UpdateGroupUserKickObsolete(groupId, cmd.kickedUserId, cmd.kickerUserId, dateMillis, cmd.randomId)

        val kickedUserUpdatesNew: List[Update] = List(
          UpdateGroupMemberChanged(groupId, isMember = false),
          UpdateGroupMembersUpdated(groupId, members = Vector.empty)
        )

        val membersUpdateNew = UpdateGroupMembersUpdated(groupId, members)

        val serviceMessage = GroupServiceMessages.userKicked(cmd.kickedUserId)

        db.run(
          for {
            _ ← GroupUserRepo.delete(groupId, cmd.kickedUserId)
            _ ← GroupInviteTokenRepo.revoke(groupId, cmd.kickedUserId)
          } yield ()
        )

        // оба:
        // • прочитываем диалог этого чувака им самим
        //
        // старый:
        // пушим всем членам группы и кикнутому UpdateGroupUserKickObsolete, исключаем клиент того кто кикнул из пуша
        //
        // новый:
        // пушим UpdateGroupMemberChanged чуваку, что он перестал быть членом группы, и UpdateGroupMembersChanged с пустым списком
        // всем остальным членам группы пушим UpdateGroupMembersUpdated без этого чувака
        // отправляем после ухода сервисное сообщение
        val result: Future[SeqStateDate] = for {
          _ ← dialogExt.messageRead(apiGroupPeer, cmd.kickedUserId, 0L, dateMillis)

          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
            userId = cmd.kickerUserId,
            authId = cmd.kickerAuthId,
            bcastUserIds = newState.memberIds,
            update = updateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.Kicked), Seq(cmd.kickerAuthId))
          )

          // new group api updates
          _ ← FutureExt.ftraverse(kickedUserUpdatesNew) { update ⇒
            seqUpdExt.deliverUserUpdate(userId = cmd.kickedUserId, update)
          }

          // push updated members list to all group members. Don't push to kicked user!
          _ ← seqUpdExt.broadcastPeopleUpdate(
            newState.memberIds,
            membersUpdateNew
          )

          seqStateDate ← dialogExt.sendServerMessage(
            apiGroupPeer,
            senderUserId = cmd.kickerUserId,
            senderAuthId = cmd.kickerAuthId,
            randomId = cmd.randomId,
            message = serviceMessage
          )
        } yield seqStateDate

        result pipeTo sender()
      }
    }
  }

  protected def updateAvatar(cmd: UpdateAvatar): Unit = {
    if (state.nonMember(cmd.clientUserId)) {
      sender() ! notMember
    } else {
      //TODO: port events
      persist(AvatarUpdated(Instant.now, cmd.avatar)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val apiAvatar: Option[ApiAvatar] = cmd.avatar
        val memberIds = newState.memberIds

        val updateNew = UpdateGroupAvatarChanged(groupId, apiAvatar)
        val updateObsolete = UpdateGroupAvatarChangedObsolete(groupId, cmd.clientUserId, apiAvatar, dateMillis, cmd.randomId)
        val serviceMessage = GroupServiceMessages.changedAvatar(apiAvatar)

        db.run(AvatarDataRepo.createOrUpdate(getAvatarDate(cmd.avatar)))
        val result: Future[UpdateAvatarAck] = for {
          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(cmd.clientUserId, cmd.clientAuthId, memberIds, updateObsolete)

          // new group api updates
          _ ← seqUpdExt.broadcastPeopleUpdate(userIds = memberIds + cmd.clientUserId, updateNew)
          seqStateDate ← dialogExt.sendServerMessage(
            apiGroupPeer,
            senderUserId = cmd.clientUserId,
            senderAuthId = cmd.clientAuthId,
            randomId = cmd.randomId,
            message = serviceMessage
          )

          //          SeqState(seq, state) = if (cmd.isV2) newSeqState else obsoleteSeqState
        } yield UpdateAvatarAck(apiAvatar).withSeqStateDate(seqStateDate)

        result pipeTo sender()
      }
    }

  }

  private def getAvatarDate(avatar: Option[Avatar]): AvatarData =
    avatar
      .map(ImageUtils.getAvatarData(AvatarData.OfGroup, groupId, _))
      .getOrElse(AvatarData.empty(AvatarData.OfGroup, groupId.toLong))

  private def isValidTitle(title: String) = title.nonEmpty && title.length < 255

  protected def updateTitle(cmd: UpdateTitle): Unit = {
    val title = cmd.title
    if (state.nonMember(cmd.clientUserId)) {
      sender() ! notMember
    } else if (!isValidTitle(title)) {
      sender() ! Status.Failure(InvalidTitle)
    } else {
      //TODO: port events
      persist(TitleUpdated(Instant.now(), title)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val memberIds = newState.memberIds

        val updateNew = UpdateGroupTitleChanged(groupId, title)
        val updateObsolete = UpdateGroupTitleChangedObsolete(
          groupId,
          userId = cmd.clientUserId,
          title = title,
          date = dateMillis,
          randomId = cmd.randomId
        )
        val serviceMessage = GroupServiceMessages.changedTitle(title)
        val pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.TitleChanged))

        db.run(GroupRepo.updateTitle(groupId, title, cmd.clientUserId, cmd.randomId, date = evt.ts))
        val result: Future[SeqStateDate] = for {
          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
            cmd.clientUserId,
            cmd.clientAuthId,
            memberIds - cmd.clientUserId,
            updateObsolete,
            pushRules
          )

          // new group api updates
          _ ← seqUpdExt.broadcastPeopleUpdate(
            userIds = memberIds + cmd.clientUserId,
            updateNew,
            pushRules
          )
          seqStateDate ← dialogExt.sendServerMessage(
            apiGroupPeer,
            senderUserId = cmd.clientUserId,
            senderAuthId = cmd.clientAuthId,
            randomId = cmd.randomId,
            message = serviceMessage
          )

          //          SeqState(seq, state) = if (cmd.isV2) newSeqState else obsoleteSeqState
        } yield seqStateDate

        result pipeTo sender()
      }

    }
  }

  protected def updateTopic(cmd: UpdateTopic): Unit = {
    def isValidTopic(topic: Option[String]) = topic.forall(_.length < 255)

    if (state.nonMember(cmd.clientUserId)) {
      sender() ! notMember
    } else {
      val topic = trimToEmpty(cmd.topic)
      if (isValidTopic(topic)) {
        // TODO: port events
        persist(TopicUpdated(Instant.now, topic)) { evt ⇒
          val newState = commit(evt)

          val dateMillis = evt.ts.toEpochMilli
          val memberIds = newState.memberIds

          val updateNew = UpdateGroupTopicChanged(groupId, topic)
          val updateObsolete = UpdateGroupTopicChangedObsolete(
            groupId,
            randomId = cmd.randomId,
            userId = cmd.clientUserId,
            topic = topic,
            date = dateMillis
          )
          val serviceMessage = GroupServiceMessages.changedTopic(topic)
          val pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.TopicChanged))

          db.run(GroupRepo.updateTopic(groupId, topic))
          val result: Future[SeqStateDate] = for {
            // old group api update
            obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
              cmd.clientUserId,
              cmd.clientAuthId,
              memberIds - cmd.clientUserId,
              updateObsolete,
              pushRules
            )

            // new group api updates
            _ ← seqUpdExt.broadcastPeopleUpdate(
              userIds = memberIds + cmd.clientUserId,
              updateNew,
              pushRules
            )
            seqStateDate ← dialogExt.sendServerMessage(
              apiGroupPeer,
              senderUserId = cmd.clientUserId,
              senderAuthId = cmd.clientAuthId,
              randomId = cmd.randomId,
              message = serviceMessage
            )

            //            SeqState(seq, state) = if (cmd.isV2) newSeqState else obsoleteSeqState
          } yield seqStateDate

          result pipeTo sender()
        }
      } else {
        sender() ! Status.Failure(TopicTooLong)
      }
    }
  }

  protected def updateAbout(cmd: UpdateAbout): Unit = {
    def isValidAbout(about: Option[String]) = about.forall(_.length < 255)

    if (!state.isAdmin(cmd.clientUserId)) {
      sender() ! notAdmin
    } else {
      val about = trimToEmpty(cmd.about)

      if (isValidAbout(about)) {
        // TODO: port events
        persist(AboutUpdated(Instant.now, about)) { evt ⇒
          val newState = commit(evt)

          val dateMillis = evt.ts.toEpochMilli
          val memberIds = newState.memberIds

          val updateNew = UpdateGroupAboutChanged(groupId, about)
          val updateObsolete = UpdateGroupAboutChangedObsolete(groupId, about)
          val serviceMessage = GroupServiceMessages.changedAbout(about)
          val pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.TopicChanged))

          db.run(GroupRepo.updateAbout(groupId, about))
          val result: Future[SeqStateDate] = for {
            // old group api update
            obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
              cmd.clientUserId,
              cmd.clientAuthId,
              memberIds - cmd.clientUserId,
              updateObsolete,
              pushRules
            )

            // new group api updates
            _ ← seqUpdExt.broadcastPeopleUpdate(
              userIds = memberIds + cmd.clientUserId,
              updateNew,
              pushRules
            )
            seqStateDate ← dialogExt.sendServerMessage(
              apiGroupPeer,
              senderUserId = cmd.clientUserId,
              senderAuthId = cmd.clientAuthId,
              randomId = cmd.randomId,
              message = serviceMessage
            )

            //            SeqState(seq, state) = if (cmd.isV2) newSeqState else obsoleteSeqState
          } yield seqStateDate

          result pipeTo sender()
        }
      } else {
        sender() ! Status.Failure(AboutTooLong)
      }
    }
  }

  protected def revokeIntegrationToken(cmd: RevokeIntegrationToken): Unit = {
    if (!state.isAdmin(cmd.clientUserId)) {
      sender() ! notAdmin
    } else {
      val oldToken = state.bot.map(_.token)
      val newToken = ACLUtils.accessToken()

      persist(IntegrationTokenRevoked(Instant.now, newToken)) { evt ⇒
        val newState = commit(evt)

        db.run(GroupBotRepo.updateToken(groupId, newToken))
        val result: Future[RevokeIntegrationTokenAck] = for {
          _ ← oldToken match {
            case Some(token) ⇒ integrationStorage.delete(token)
            case None        ⇒ FastFuture.successful(())
          }
          _ ← integrationStorage.upsert(newToken)
        } yield RevokeIntegrationTokenAck(newToken)

        result pipeTo sender()
      }
    }
  }

  protected def makeUserAdmin(cmd: MakeUserAdmin): Unit = {
    if (!state.isAdmin(cmd.clientUserId) || state.nonMember(cmd.candidateUserId)) {
      sender() ! Status.Failure(CommonErrors.Forbidden)
    } else if (state.isAdmin(cmd.candidateUserId)) {
      sender() ! Status.Failure(UserAlreadyAdmin)
    } else {
      persist(UserBecameAdmin(Instant.now, cmd.candidateUserId, cmd.clientUserId)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val memberIds = newState.memberIds
        val members = newState.members.values.map(_.asStruct).toVector

        val updateAdmin = UpdateGroupMemberAdminChanged(groupId, cmd.candidateUserId, isAdmin = true)
        val updateMembers = UpdateGroupMembersUpdated(groupId, members)

        val updateObsolete = UpdateGroupMembersUpdateObsolete(groupId, members)

        db.run(GroupUserRepo.makeAdmin(groupId, cmd.candidateUserId))
        val result: Future[(Vector[ApiMember], SeqStateDate)] = for {
          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
            cmd.clientUserId,
            cmd.clientAuthId,
            memberIds - cmd.clientUserId,
            updateObsolete
          )

          // new group api updates
          _ ← seqUpdExt.broadcastPeopleUpdate(
            userIds = memberIds + cmd.clientUserId,
            updateAdmin
          )
          newSeqState ← seqUpdExt.broadcastClientUpdate(
            cmd.clientUserId,
            cmd.clientAuthId,
            memberIds - cmd.clientUserId,
            updateMembers
          )
          SeqState(seq, state) = if (cmd.isV2) newSeqState else obsoleteSeqState
        } yield (members, SeqStateDate(seq, state, dateMillis))

        result pipeTo sender()
      }
    }
  }

  protected def transferOwnership(cmd: TransferOwnership): Unit = {
    if (!state.isOwner(cmd.clientUserId)) {
      sender() ! Status.Failure(CommonErrors.Forbidden)
    } else {
      persist(OwnerChanged(Instant.now, cmd.newOwnerId)) { evt ⇒
        val newState = commit(evt)
        val memberIds = newState.memberIds

        val result: Future[SeqState] = for {
          seqState ← seqUpdExt.broadcastClientUpdate(
            userId = cmd.clientUserId,
            authId = cmd.clientAuthId,
            bcastUserIds = memberIds - cmd.clientUserId,
            update = UpdateGroupOwnerChanged(groupId, cmd.newOwnerId),
            pushRules = seqUpdExt.pushRules(isFat = false, None, Seq(cmd.clientAuthId))
          )
        } yield seqState

        result pipeTo sender()
      }
    }
  }

  // Updates that will be sent to user, when he enters group.
  // Helps clients that have this group to refresh it's data.
  private def refreshGroupUpdates(newState: GroupState): List[Update] = List(
    UpdateGroupMemberChanged(groupId, isMember = true),
    UpdateGroupAboutChanged(groupId, newState.about),
    UpdateGroupAvatarChanged(groupId, newState.avatar),
    UpdateGroupTopicChanged(groupId, newState.topic),
    UpdateGroupTitleChanged(groupId, newState.title),
    UpdateGroupOwnerChanged(groupId, newState.ownerUserId)
  //    UpdateGroupExtChanged(groupId, newState.extension) //TODO: figure out and fix
  //          if(bigGroup) UpdateGroupMembersCountChanged(groupId, newState.extension)
  )

  private def trimToEmpty(s: Option[String]): Option[String] = s map (_.trim) filter (_.nonEmpty)

}
