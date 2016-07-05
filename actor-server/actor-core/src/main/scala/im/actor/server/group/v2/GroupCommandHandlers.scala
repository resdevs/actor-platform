package im.actor.server.group.v2

import java.time.Instant

import akka.actor.Status
import im.actor.api.rpc.groups.{ UpdateGroupAvatarChanged, UpdateGroupAvatarChangedObsolete, UpdateGroupTitleChanged, UpdateGroupTitleChangedObsolete }
import im.actor.server.file.{ Avatar, ImageUtils }
import im.actor.server.group.GroupEvents.{ AvatarUpdated, TitleUpdated }
import im.actor.server.group.GroupServiceMessages
import im.actor.server.groupV2.GroupCommandsV2._
import im.actor.server.model.AvatarData
import im.actor.server.sequence.{ SeqState, SeqStateDate }
import akka.pattern.pipe
import im.actor.api.rpc.files.ApiAvatar
import im.actor.concurrent.StashingActor
import im.actor.server.group.GroupErrors.NotAMember
import im.actor.server.office.PushTexts
import im.actor.server.persist.{ AvatarDataRepo, GroupRepo }

import scala.concurrent.Future

private[group] trait GroupCommandHandlers extends StashingActor { self: GroupProcessorV2 ⇒
  import im.actor.server.ApiConversions._

  private val notMember = Status.Failure(NotAMember)

  protected def updateAvatar(upd: UpdateAvatar): Unit = {
    if (state.nonMember(upd.clientUserId)) {
      sender() ! notMember
    } else {
      //TODO: port events
      persist(AvatarUpdated(Instant.now, upd.avatar)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val apiAvatar: Option[ApiAvatar] = upd.avatar
        val memberIds = newState.members.keySet

        val updateNew = UpdateGroupAvatarChanged(groupId, apiAvatar)
        val updateObsolete = UpdateGroupAvatarChangedObsolete(groupId, upd.clientUserId, apiAvatar, dateMillis, upd.randomId)
        val serviceMessage = GroupServiceMessages.changedAvatar(apiAvatar)

        val result: Future[UpdateAvatarAck] = for {
          //          _ ← db.run(AvatarDataRepo.createOrUpdate(getAvatarDate(upd.avatar))) /// may change to _ = db.run()
          _ ← db.run(AvatarDataRepo.createOrUpdate(getAvatarDate(upd.avatar)))

          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(upd.clientUserId, upd.clientAuthId, memberIds, updateObsolete)

          // new group api updates
          _ ← seqUpdExt.broadcastPeopleUpdate(userIds = memberIds + upd.clientUserId, updateNew)
          newSeqState ← dialogExt.sendMessage(
            groupPeer,
            upd.clientUserId,
            upd.clientAuthId,
            upd.randomId,
            serviceMessage,
            newState.accessHash
          )

          SeqState(seq, state) = if (upd.isV2) newSeqState else obsoleteSeqState
        } yield UpdateAvatarAck(apiAvatar).withSeqStateDate(SeqStateDate(seq, state, dateMillis))

        result pipeTo sender()
      }
    }

  }

  private def getAvatarDate(avatar: Option[Avatar]): AvatarData =
    avatar
      .map(ImageUtils.getAvatarData(AvatarData.OfGroup, groupId, _))
      .getOrElse(AvatarData.empty(AvatarData.OfGroup, groupId.toLong))

  protected def updateTitle(upd: UpdateTitle): Unit = {
    if (state.nonMember(upd.clientUserId)) {
      sender() ! notMember
    } else {
      //TODO: port events

      persist(TitleUpdated(Instant.now(), upd.title)) { evt ⇒
        val newState = commit(evt)

        val dateMillis = evt.ts.toEpochMilli
        val title = upd.title
        val memberIds = newState.members.keySet

        val updateNew = UpdateGroupTitleChanged(groupId, title)
        val updateObsolete = UpdateGroupTitleChangedObsolete(
          groupId,
          userId = upd.clientUserId,
          title = title,
          date = dateMillis,
          randomId = upd.randomId
        )
        val serviceMessage = GroupServiceMessages.changedTitle(title)

        val result: Future[SeqStateDate] = for {
          _ ← db.run(GroupRepo.updateTitle(groupId, title, upd.clientUserId, upd.randomId, date = evt.ts))
          _ ← seqUpdExt.broadcastPeopleUpdate(userIds = memberIds + upd.clientUserId, updateNew)

          // old group api update
          obsoleteSeqState ← seqUpdExt.broadcastClientUpdate(
            upd.clientUserId,
            upd.clientAuthId,
            memberIds,
            updateObsolete,
            seqUpdExt.pushRules(isFat = false, Some(PushTexts.TitleChanged))
          )

          // new group api updates
          _ ← seqUpdExt.broadcastPeopleUpdate(
            userIds = memberIds + upd.clientUserId,
            updateNew,
            seqUpdExt.pushRules(isFat = false, Some(PushTexts.TitleChanged))
          )
          newSeqState ← dialogExt.sendMessage(
            groupPeer,
            upd.clientUserId,
            upd.clientAuthId,
            upd.randomId,
            serviceMessage,
            newState.accessHash
          )

          SeqState(seq, state) = if (upd.isV2) newSeqState else obsoleteSeqState
        } yield SeqStateDate(seq, state, dateMillis)

        result pipeTo sender()
      }
    }
  }

  protected def updateTopic(upd: UpdateTopic): Unit = {

  }

  protected def updateAbout(upd: UpdateAbout): Unit = {

  }

  //  def withGroupMember(userId: Int)(block: => Unit): Unit =
  //    if (state.isMember(userId)) {
  //      block
  //    } else {
  //      sender() ! Status.Failure(NotAMember)
  //    }

}
