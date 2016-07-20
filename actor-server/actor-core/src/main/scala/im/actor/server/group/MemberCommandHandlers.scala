package im.actor.server.group

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import akka.actor.Status
import akka.pattern.pipe
import im.actor.api.rpc.Update
import im.actor.api.rpc.groups._
import im.actor.api.rpc.messaging.{ ApiServiceMessage, UpdateMessage }
import im.actor.concurrent.FutureExt
import im.actor.server.acl.ACLUtils
import im.actor.server.group.GroupCommands.{ Invite, Join, Kick, Leave }
import im.actor.server.group.GroupErrors.CantLeaveGroup
import im.actor.server.group.GroupEvents.{ UserInvited, UserJoined, UserKicked, UserLeft }
import im.actor.server.persist.{ GroupInviteTokenRepo, GroupUserRepo }
import im.actor.server.sequence.{ Optimization, SeqState, SeqStateDate }

import scala.concurrent.Future

private[group] trait MemberCommandHandlers extends GroupsImplicits {
  this: GroupProcessor ⇒

  import im.actor.server.ApiConversions._

  protected def invite(cmd: Invite): Unit = {
    if (!state.permissions.canInvitePeople(cmd.inviterUserId)) {
      sender() ! noPermission
    } else if (state.isInvited(cmd.inviteeUserId)) {
      sender() ! Status.Failure(GroupErrors.UserAlreadyInvited)
    } else if (state.isMember(cmd.inviteeUserId)) {
      sender() ! Status.Failure(GroupErrors.UserAlreadyJoined)
    } else {
      val inviteeIsExUser = state.isExUser(cmd.inviteeUserId)

      persist(UserInvited(Instant.now, cmd.inviteeUserId, cmd.inviterUserId)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val memberIds = newState.memberIds
        val apiMembers = newState.members.values.map(_.asStruct).toVector

        // if user ever been in this group - we should push these updates,
        val inviteeUpdatesNew: List[Update] = refreshGroupUpdates(newState, cmd.inviteeUserId)

        val membersUpdateNew: Update =
          if (newState.groupType.isChannel) // if channel, or group is big enough
            UpdateGroupMembersCountChanged(groupId, newState.membersCount)
          else
            UpdateGroupMembersUpdated(groupId, apiMembers)

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

        //TODO: remove deprecated
        db.run(GroupUserRepo.create(groupId, cmd.inviteeUserId, cmd.inviterUserId, evt.ts, None, isAdmin = false))

        def inviteGROUPUpdates: Future[SeqStateDate] =
          for {
            // push updated members list to inviteeUserId,
            // make it `FatSeqUpdate` if this user invited to group for first time.
            _ ← seqUpdExt.deliverUserUpdate(
              userId = cmd.inviteeUserId,
              membersUpdateNew,
              pushRules = seqUpdExt.pushRules(isFat = !inviteeIsExUser, Some(PushTexts.Invited)),
              deliveryId = s"invite_${groupId}_${cmd.randomId}"
            )

            // push all "refresh group" updates to inviteeUserId
            _ ← FutureExt.ftraverse(inviteeUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.inviteeUserId, update)
            }

            // push updated members list to all group members except inviteeUserId
            SeqState(seq, state) ← seqUpdExt.broadcastClientUpdate(
              userId = cmd.inviterUserId,
              authId = cmd.inviterAuthId,
              bcastUserIds = (memberIds - cmd.inviterUserId) - cmd.inviteeUserId,
              update = membersUpdateNew,
              deliveryId = s"useradded_${groupId}_${cmd.randomId}"
            )

            // explicitly send service message
            SeqStateDate(_, _, date) ← dialogExt.sendServerMessage(
              apiGroupPeer,
              cmd.inviterUserId,
              cmd.inviterAuthId,
              cmd.randomId,
              serviceMessage,
              deliveryTag = Some(Optimization.GroupV2)
            )
          } yield SeqStateDate(seq, state, date)

        def inviteCHANNELUpdates: Future[SeqStateDate] =
          for {
            // push updated members count to inviteeUserId
            _ ← seqUpdExt.deliverUserUpdate(
              userId = cmd.inviteeUserId,
              membersUpdateNew,
              pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.Invited)),
              deliveryId = s"invite_${groupId}_${cmd.randomId}"
            )

            // push all "refresh group" updates to inviteeUserId
            _ ← FutureExt.ftraverse(inviteeUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.inviteeUserId, update)
            }

            // push updated members count to all group members
            SeqState(seq, state) ← seqUpdExt.broadcastClientUpdate(
              userId = cmd.inviterUserId,
              authId = cmd.inviterAuthId,
              bcastUserIds = (memberIds - cmd.inviterUserId) - cmd.inviteeUserId,
              update = membersUpdateNew,
              deliveryId = s"useradded_${groupId}_${cmd.randomId}"
            )

            // push service message to invitee
            _ ← seqUpdExt.deliverUserUpdate(
              userId = cmd.inviteeUserId,
              update = serviceMessageUpdate(
                cmd.inviterUserId,
                dateMillis,
                cmd.randomId,
                serviceMessage
              ),
              deliveryTag = Some(Optimization.GroupV2)
            )
          } yield SeqStateDate(seq, state, dateMillis)

        val result: Future[SeqStateDate] = for {
          ///////////////////////////
          // Groups V1 API updates //
          ///////////////////////////

          // push "Invited" to invitee
          _ ← seqUpdExt.deliverUserUpdate(
            userId = cmd.inviteeUserId,
            inviteeUpdateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, Some(PushTexts.Invited)),
            deliveryId = s"invite_obsolete_${groupId}_${cmd.randomId}"
          )

          // push "User added" to all group members except for `inviterUserId`
          _ ← seqUpdExt.broadcastPeopleUpdate(
            (memberIds - cmd.inviteeUserId) - cmd.inviterUserId, // is it right?
            membersUpdateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, Some(PushTexts.Added)),
            deliveryId = s"useradded_obsolete_${groupId}_${cmd.randomId}"
          )

          // push "User added" to `inviterUserId`
          _ ← seqUpdExt.deliverClientUpdate(
            cmd.inviterUserId,
            cmd.inviterAuthId,
            membersUpdateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = true, None),
            deliveryId = s"useradded_obsolete_${groupId}_${cmd.randomId}"
          )

          ///////////////////////////
          // Groups V2 API updates //
          ///////////////////////////

          seqStateDate ← if (newState.groupType.isChannel) inviteCHANNELUpdates else inviteGROUPUpdates

        } yield seqStateDate

        result pipeTo sender()
      }
    }
  }

  /**
   * User can join
   * • after invite(was invited by other user previously). In this case he already have group on devices
   * • via invite link. In this case he doesn't have group, and we need to deliver it.
   */
  protected def join(cmd: Join): Unit = {
    // user is already a member, and should not complete invitation process
    if (state.isMember(cmd.joiningUserId) && !state.isInvited(cmd.joiningUserId)) {
      sender() ! Status.Failure(GroupErrors.UserAlreadyJoined)
    } else {
      // user was invited in group by other group user
      val wasInvited = state.isInvited(cmd.joiningUserId)

      // trying to figure out who invited joining user.
      // Descdending priority:
      // • inviter defined in `Join` command (when invited via token)
      // • inviter from members list (when invited by other user)
      // • group creator (safe fallback)
      val optMember = state.members.get(cmd.joiningUserId)
      val inviterUserId = cmd.invitingUserId
        .orElse(optMember.map(_.inviterUserId))
        .getOrElse(state.ownerUserId)

      persist(UserJoined(Instant.now, cmd.joiningUserId, inviterUserId)) { evt ⇒
        val newState = commit(evt)

        val date = evt.ts
        val dateMillis = date.toEpochMilli
        val memberIds = newState.memberIds
        val apiMembers = newState.members.values.map(_.asStruct).toVector
        val randomId = ACLUtils.randomLong()

        // If user was never invited to group - he don't have group on devices,
        // that means we need to push all group-info related updates
        //
        // If user was invited to group by other member - we don't need to push group updates,
        // cause they we pushed already on invite step
        val joiningUserUpdatesNew: List[Update] =
          if (wasInvited) List.empty[Update] else refreshGroupUpdates(newState, cmd.joiningUserId)

        val membersUpdateNew: Update =
          if (newState.groupType.isChannel) // if channel, or group is big enough
            UpdateGroupMembersCountChanged(groupId, newState.membersCount)
          else
            UpdateGroupMembersUpdated(groupId, apiMembers) // will update date when member got into group

        // TODO: not sure how it should be in old API
        val membersUpdateObsolete = UpdateGroupMembersUpdateObsolete(groupId, apiMembers)

        val serviceMessage = GroupServiceMessages.userJoined

        //TODO: remove deprecated
        db.run(GroupUserRepo.create(
          groupId,
          userId = cmd.joiningUserId,
          inviterUserId = inviterUserId,
          invitedAt = optMember.map(_.invitedAt).getOrElse(date),
          joinedAt = Some(LocalDateTime.now(ZoneOffset.UTC)),
          isAdmin = false
        ))

        def joinGROUPUpdates: Future[SeqStateDate] =
          for {
            // push all group updates to joiningUserId
            _ ← FutureExt.ftraverse(joiningUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.joiningUserId, update)
            }

            // push updated members list to joining user,
            // make it `FatSeqUpdate` if this user invited to group for first time.
            // TODO???: isFat = !wasInvited - is it correct?
            SeqState(seq, state) ← seqUpdExt.deliverClientUpdate(
              userId = cmd.joiningUserId,
              authId = cmd.joiningUserAuthId,
              update = membersUpdateNew,
              pushRules = seqUpdExt.pushRules(isFat = !wasInvited, None), //!wasInvited means that user came for first time here
              deliveryId = s"join_${groupId}_${randomId}"

            )

            // push updated members list to all group members except joiningUserId
            _ ← seqUpdExt.broadcastPeopleUpdate(
              memberIds - cmd.joiningUserId,
              membersUpdateNew,
              deliveryId = s"userjoined_${groupId}_${randomId}"
            )

            SeqStateDate(_, _, date) ← dialogExt.sendServerMessage(
              apiGroupPeer,
              senderUserId = cmd.joiningUserId,
              senderAuthId = cmd.joiningUserAuthId,
              randomId = randomId,
              serviceMessage // no delivery tag. This updated handled this way in Groups V1
            )
          } yield SeqStateDate(seq, state, date)

        def joinCHANNELUpdates: Future[SeqStateDate] =
          for {
            // push all group updates to joiningUserId
            _ ← FutureExt.ftraverse(joiningUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.joiningUserId, update)
            }

            // push updated members count to joining user
            SeqState(seq, state) ← seqUpdExt.deliverClientUpdate(
              userId = cmd.joiningUserId,
              authId = cmd.joiningUserAuthId,
              update = membersUpdateNew,
              deliveryId = s"join_${groupId}_${randomId}"
            )

            // push updated members count to all group members except joining user
            _ ← seqUpdExt.broadcastPeopleUpdate(
              memberIds - cmd.joiningUserId,
              membersUpdateNew,
              deliveryId = s"userjoined_${groupId}_${randomId}"
            )
          } yield SeqStateDate(seq, state, dateMillis)

        val result: Future[(SeqStateDate, Vector[Int], Long)] =
          for {
            ///////////////////////////
            // Groups V1 API updates //
            ///////////////////////////

            // push update about members to all users, except joining user
            _ ← seqUpdExt.broadcastPeopleUpdate(
              memberIds - cmd.joiningUserId,
              membersUpdateObsolete,
              pushRules = seqUpdExt.pushRules(isFat = true, None),
              deliveryId = s"userjoined_obsolete_${groupId}_${randomId}"
            )

            ///////////////////////////
            // Groups V2 API updates //
            ///////////////////////////

            seqStateDate ← if (newState.groupType.isChannel) joinCHANNELUpdates else joinGROUPUpdates

          } yield (seqStateDate, memberIds.toVector :+ inviterUserId, randomId)

        result pipeTo sender()
      }
    }
  }

  /**
   * This case handled in other manner, so we change state in the end
   * cause user that left, should send service message. And we don't allow non-members
   * to send message. So we keep him as member until message sent, and remove him from members
   */
  protected def leave(cmd: Leave): Unit = {
    if (state.nonMember(cmd.userId)) {
      sender() ! notMember
    } else if (!state.permissions.canLeave(cmd.userId)) {
      sender() ! Status.Failure(CantLeaveGroup)
    } else {
      persist(UserLeft(Instant.now, cmd.userId)) { evt ⇒
        // no commit here. it will be after service message sent

        val dateMillis = evt.ts.toEpochMilli

        val updateObsolete = UpdateGroupUserLeaveObsolete(groupId, cmd.userId, dateMillis, cmd.randomId)

        val leftUserUpdatesNew: Vector[Update] = {
          val commonUpdates = Vector(
            UpdateGroupCanSendMessagesChanged(groupId, canSendMessages = false),
            UpdateGroupCanEditInfoChanged(groupId, canEditGroup = false),
            UpdateGroupCanEditUsernameChanged(groupId, canEditUsername = false),
            UpdateGroupCanEditAdminsChanged(groupId, canAssignAdmins = false),
            UpdateGroupCanViewAdminsChanged(groupId, canViewAdmins = false),
            UpdateGroupCanEditAdminSettingsChanged(groupId, canEditAdminSettings = false),
            UpdateGroupCanInviteViaLink(groupId, canInviteViaLink = false),
            UpdateGroupCanLeaveChanged(groupId, canLeaveChanged = false),
            UpdateGroupCanDeleteChanged(groupId, canDeleteChanged = false)
          )

          if (state.groupType.isChannel) {
            (UpdateGroupCanViewMembersChanged(groupId, canViewMembers = false) +:
              commonUpdates) :+
              UpdateGroupCanInviteMembersChanged(groupId, canInviteMembers = false)
          } else {
            commonUpdates ++ Vector(
              UpdateGroupCanViewMembersChanged(groupId, canViewMembers = false),
              UpdateGroupMembersUpdated(groupId, members = Vector.empty),
              UpdateGroupCanInviteMembersChanged(groupId, canInviteMembers = false)
            )
          }
        }

        val membersUpdateNew =
          if (state.groupType.isChannel) { // if channel, or group is big enough
            UpdateGroupMembersCountChanged(
              groupId,
              membersCount = state.membersCount - 1
            )
          } else {
            UpdateGroupMembersUpdated(
              groupId,
              members = state.members.filterNot(_._1 == cmd.userId).values.map(_.asStruct).toVector
            )
          }

        val serviceMessage = GroupServiceMessages.userLeft

        //TODO: remove deprecated. GroupInviteTokenRepo don't have replacement yet.
        db.run(
          for {
            _ ← GroupUserRepo.delete(groupId, cmd.userId)
            _ ← GroupInviteTokenRepo.revoke(groupId, cmd.userId)
          } yield ()
        )

        val leaveGROUPUpdates: Future[SeqStateDate] =
          for {
            // push updated members list to all group members
            _ ← seqUpdExt.broadcastPeopleUpdate(
              state.memberIds - cmd.userId,
              membersUpdateNew
            )

            // send service message
            SeqStateDate(_, _, date) ← dialogExt.sendServerMessage(
              apiGroupPeer,
              senderUserId = cmd.userId,
              senderAuthId = cmd.authId,
              randomId = cmd.randomId,
              message = serviceMessage,
              deliveryTag = Some(Optimization.GroupV2)
            )

            // push left user that he is no longer a member
            SeqState(seq, state) ← seqUpdExt.deliverClientUpdate(
              userId = cmd.userId,
              authId = cmd.authId,
              update = UpdateGroupMemberChanged(groupId, isMember = false)
            )

            // push left user updates
            // • with empty group members
            // • that he can't view and invite members
            _ ← FutureExt.ftraverse(leftUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.userId, update)
            }
          } yield SeqStateDate(seq, state, date)

        val leaveCHANNELUpdates: Future[SeqStateDate] =
          for {
            // push updated members count to all group members
            _ ← seqUpdExt.broadcastPeopleUpdate(
              state.memberIds - cmd.userId,
              membersUpdateNew
            )

            // push left user that he is no longer a member
            SeqState(seq, state) ← seqUpdExt.deliverClientUpdate(
              userId = cmd.userId,
              authId = cmd.authId,
              update = UpdateGroupMemberChanged(groupId, isMember = false)
            )

            // push left user updates that he has no group rights
            _ ← FutureExt.ftraverse(leftUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.userId, update)
            }
          } yield SeqStateDate(seq, state, dateMillis)

        // read this dialog by user that leaves group. don't wait for ack
        dialogExt.messageRead(apiGroupPeer, cmd.userId, 0L, dateMillis)
        val result: Future[SeqStateDate] = for {

          ///////////////////////////
          // Groups V1 API updates //
          ///////////////////////////

          _ ← seqUpdExt.broadcastClientUpdate(
            userId = cmd.userId,
            authId = cmd.authId,
            bcastUserIds = state.memberIds + cmd.userId, // push this to other user's devices too. actually cmd.userId is still in state.memberIds
            update = updateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.Left), Seq(cmd.authId))
          )

          ///////////////////////////
          // Groups V2 API updates //
          ///////////////////////////

          seqStateDate ← if (state.groupType.isChannel) leaveCHANNELUpdates else leaveGROUPUpdates

        } yield seqStateDate

        result andThen { case _ ⇒ commit(evt) } pipeTo sender()
      }
    }
  }

  protected def kick(cmd: Kick): Unit = {
    if (!state.permissions.canKickMember(cmd.kickerUserId)) {
      sender() ! noPermission
    } else if (state.nonMember(cmd.kickedUserId)) {
      sender() ! notMember
    } else {
      persist(UserKicked(Instant.now, cmd.kickedUserId, cmd.kickerUserId)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli

        val updateObsolete = UpdateGroupUserKickObsolete(groupId, cmd.kickedUserId, cmd.kickerUserId, dateMillis, cmd.randomId)

        val kickedUserUpdatesNew: Vector[Update] = {
          val commonUpdates = Vector(
            UpdateGroupCanViewMembersChanged(groupId, canViewMembers = false),
            UpdateGroupCanSendMessagesChanged(groupId, canSendMessages = false),
            UpdateGroupCanEditInfoChanged(groupId, canEditGroup = false),
            UpdateGroupCanEditUsernameChanged(groupId, canEditUsername = false),
            UpdateGroupCanEditAdminsChanged(groupId, canAssignAdmins = false),
            UpdateGroupCanViewAdminsChanged(groupId, canViewAdmins = false),
            UpdateGroupCanEditAdminSettingsChanged(groupId, canEditAdminSettings = false),
            UpdateGroupCanInviteMembersChanged(groupId, canInviteMembers = false),
            UpdateGroupCanInviteViaLink(groupId, canInviteViaLink = false),
            UpdateGroupCanLeaveChanged(groupId, canLeaveChanged = false),
            UpdateGroupCanDeleteChanged(groupId, canDeleteChanged = false)
          )

          if (state.groupType.isChannel) {
            commonUpdates :+ UpdateGroupMemberChanged(groupId, isMember = false)
          } else {
            commonUpdates ++ Vector(
              UpdateGroupMembersUpdated(groupId, members = Vector.empty),
              UpdateGroupMemberChanged(groupId, isMember = false)
            )
          }
        }

        val membersUpdateNew: Update =
          if (newState.groupType.isChannel) { // if channel, or group is big enough
            UpdateGroupMembersCountChanged(
              groupId,
              membersCount = newState.membersCount
            )
          } else {
            UpdateGroupMembersUpdated(
              groupId,
              members = newState.members.values.map(_.asStruct).toVector
            )
          }

        val serviceMessage = GroupServiceMessages.userKicked(cmd.kickedUserId)

        //TODO: remove deprecated. GroupInviteTokenRepo don't have replacement yet.
        db.run(
          for {
            _ ← GroupUserRepo.delete(groupId, cmd.kickedUserId)
            _ ← GroupInviteTokenRepo.revoke(groupId, cmd.kickedUserId)
          } yield ()
        )

        val kickGROUPUpdates: Future[SeqStateDate] =
          for {
            // push updated members list to all group members. Don't push to kicked user!
            SeqState(seq, state) ← seqUpdExt.broadcastClientUpdate(
              userId = cmd.kickerUserId,
              authId = cmd.kickerAuthId,
              bcastUserIds = newState.memberIds - cmd.kickerUserId,
              update = membersUpdateNew
            )

            SeqStateDate(_, _, date) ← dialogExt.sendServerMessage(
              apiGroupPeer,
              senderUserId = cmd.kickerUserId,
              senderAuthId = cmd.kickerAuthId,
              randomId = cmd.randomId,
              message = serviceMessage,
              deliveryTag = Some(Optimization.GroupV2)
            )

            // push kicked user updates
            // • with empty group members
            // • that he is no longer a member of group
            // • that he can't view and invite members
            _ ← FutureExt.ftraverse(kickedUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.kickedUserId, update)
            }
          } yield SeqStateDate(seq, state, date)

        val kickCHANNELUpdates: Future[SeqStateDate] =
          for {
            // push updated members count to all group members. Don't push to kicked user!
            SeqState(seq, state) ← seqUpdExt.broadcastClientUpdate(
              userId = cmd.kickerUserId,
              authId = cmd.kickerAuthId,
              bcastUserIds = newState.memberIds - cmd.kickerUserId,
              update = membersUpdateNew
            )

            // push service message to kicker and kicked users.
            _ ← seqUpdExt.broadcastPeopleUpdate(
              userIds = Set(cmd.kickedUserId, cmd.kickerUserId),
              update = serviceMessageUpdate(
                cmd.kickerUserId,
                dateMillis,
                cmd.randomId,
                serviceMessage
              ),
              deliveryTag = Some(Optimization.GroupV2)
            )

            // push kicked user updates that he has no group rights
            _ ← FutureExt.ftraverse(kickedUserUpdatesNew) { update ⇒
              seqUpdExt.deliverUserUpdate(userId = cmd.kickedUserId, update)
            }
          } yield SeqStateDate(seq, state, dateMillis)

        // read this dialog by kicked user. don't wait for ack
        dialogExt.messageRead(apiGroupPeer, cmd.kickedUserId, 0L, dateMillis)
        val result: Future[SeqStateDate] = for {
          ///////////////////////////
          // Groups V1 API updates //
          ///////////////////////////

          _ ← seqUpdExt.broadcastClientUpdate(
            userId = cmd.kickerUserId,
            authId = cmd.kickerAuthId,
            bcastUserIds = newState.memberIds,
            update = updateObsolete,
            pushRules = seqUpdExt.pushRules(isFat = false, Some(PushTexts.Kicked), Seq(cmd.kickerAuthId))
          )

          ///////////////////////////
          // Groups V2 API updates //
          ///////////////////////////

          seqStateDate ← if (state.groupType.isChannel) kickCHANNELUpdates else kickGROUPUpdates

        } yield seqStateDate

        result pipeTo sender()
      }
    }
  }

  // Updates that will be sent to user, when he enters group.
  // Helps clients that have this group to refresh it's data.
  private def refreshGroupUpdates(newState: GroupState, userId: Int): List[Update] = List(
    UpdateGroupMemberChanged(groupId, isMember = true),
    UpdateGroupAboutChanged(groupId, newState.about),
    UpdateGroupAvatarChanged(groupId, newState.avatar),
    UpdateGroupTopicChanged(groupId, newState.topic),
    UpdateGroupTitleChanged(groupId, newState.title),
    UpdateGroupOwnerChanged(groupId, newState.ownerUserId),
    UpdateGroupCanSendMessagesChanged(groupId, newState.permissions.canSendMessage(userId)),
    UpdateGroupCanViewMembersChanged(groupId, newState.permissions.canViewMembers(userId)),
    UpdateGroupCanInviteMembersChanged(groupId, newState.permissions.canInvitePeople(userId)),
    UpdateGroupCanEditInfoChanged(groupId, newState.permissions.canEditInfo(userId)),
    UpdateGroupCanEditUsernameChanged(groupId, newState.permissions.canEditShortName(userId)),
    UpdateGroupCanEditAdminsChanged(groupId, newState.permissions.canEditAdmins(userId)),
    UpdateGroupCanViewAdminsChanged(groupId, newState.permissions.canViewAdmins(userId)),
    UpdateGroupCanInviteViaLink(groupId, newState.permissions.canInviteViaLink(userId)),
    UpdateGroupCanLeaveChanged(groupId, newState.permissions.canLeave(userId)),
    UpdateGroupCanDeleteChanged(groupId, newState.permissions.canDelete(userId)),
    UpdateGroupCanEditAdminSettingsChanged(groupId, newState.permissions.canEditAdminSettings(userId))
  //    UpdateGroupExtChanged(groupId, newState.extension) //TODO: figure out and fix
  //          if(bigGroup) UpdateGroupMembersCountChanged(groupId, newState.extension)
  )

  private def serviceMessageUpdate(senderUserId: Int, date: Long, randomId: Long, message: ApiServiceMessage) =
    UpdateMessage(
      peer = apiGroupPeer,
      senderUserId = senderUserId,
      date = date,
      randomId = randomId,
      message = message,
      attributes = None,
      quotedMessage = None
    )

}
