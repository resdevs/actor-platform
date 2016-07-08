package im.actor.server.group.v2

import akka.actor.{ ActorRef, ActorSystem, Props, ReceiveTimeout, Status }
import akka.cluster.sharding.ShardRegion
import akka.http.scaladsl.util.FastFuture
import im.actor.api.rpc.peers.{ ApiPeer, ApiPeerType }
import im.actor.server.cqrs.Processor
import im.actor.server.db.DbExtension
import im.actor.server.dialog.{ DialogEnvelope, DialogExtension }
import im.actor.server.group.GroupErrors.{ GroupIdAlreadyExists, GroupNotFound }
import im.actor.server.group.{ GroupOffice, GroupPeer }
import im.actor.server.groupV2.GroupCommand
import im.actor.server.groupV2.GroupCommandsV2._
import im.actor.server.groupV2.GroupQueriesV2._
import im.actor.server.sequence.SeqUpdatesExtension
import im.actor.server.user.UserExtension

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

case object StopProcessor

private[group] object GroupProcessorV2 {
  def props = Props(classOf[GroupProcessorV2])
}

//TODO: implement snapshots
private[group] final class GroupProcessorV2
  extends Processor[GroupState]
  with GroupCommandHandlers
  with GroupQueryHandlers {

  protected implicit val system: ActorSystem = context.system
  protected implicit val ec: ExecutionContext = system.dispatcher
  protected val db = DbExtension(system).db
  protected val dialogExt = DialogExtension(system)
  protected val seqUpdExt = SeqUpdatesExtension(system)
  protected val userExt = UserExtension(system)

  protected var integrationStorage: IntegrationTokensStorage = _

  protected val groupId = self.path.name.toInt
  protected val apiGroupPeer = ApiPeer(ApiPeerType.Group, groupId)

  context.setReceiveTimeout(5.hours)

  protected def handleCommand: Receive = {
    //or move inside of method?
    // creationHandlers
    case c: Create if state.isNotCreated       ⇒ create(c)
    case _: GroupCommand if state.isNotCreated ⇒ Status.Failure(GroupNotFound(groupId))
    case _: Create                             ⇒ sender() ! Status.Failure(GroupIdAlreadyExists(groupId))

    // membersHandlers
    case i: Invite                             ⇒ invite(i)
    case j: Join                               ⇒ join(j)

    //    case l: Leave => leave(l)
    //    case k: Kick => kick(k)

    // groupInfoHandlers
    case u: UpdateAvatar                       ⇒ updateAvatar(u)
    case u: UpdateTitle                        ⇒ updateTitle(u)
    case u: UpdateTopic                        ⇒ updateTopic(u)
    case u: UpdateAbout                        ⇒ updateAbout(u)

    // adminHandlers
    case r: RevokeIntegrationToken             ⇒ revokeIntegrationToken(r)
    case m: MakeUserAdmin                      ⇒ makeUserAdmin(m)
    case t: TransferOwnership                  ⇒ transferOwnership(t)

    case StopProcessor                         ⇒ context stop self
    case ReceiveTimeout                        ⇒ context.parent ! ShardRegion.Passivate(stopMessage = StopProcessor)
    case de: DialogEnvelope ⇒
      groupPeerActor forward de.getAllFields.values.head

  }

  // TODO: add backoff
  private def groupPeerActor: ActorRef = {
    val groupPeer = "GroupPeer"
    context.child(groupPeer).getOrElse(context.actorOf(GroupPeer.props(groupId), groupPeer))
  }

  protected def handleQuery: PartialFunction[Any, Future[Any]] = {
    case query if state.isNotCreated              ⇒ FastFuture.failed(GroupNotFound(groupId))
    case GetAccessHash()                          ⇒ getAccessHash
    case GetTitle()                               ⇒ getTitle
    case GetIntegrationToken(optClient)           ⇒ getIntegrationToken(optClient)
    case GetMembers()                             ⇒ getMembers
    case LoadMembers(clientUserId, limit, offset) ⇒ loadMembers(clientUserId, limit, offset)
    case IsPublic()                               ⇒ isPublic
    case IsHistoryShared()                        ⇒ isHistoryShared
    case GetApiStruct(clientUserId)               ⇒ getApiStruct(clientUserId)
    case GetApiFullStruct(clientUserId)           ⇒ getApiFullStruct(clientUserId)
    case CheckAccessHash(accessHash)              ⇒ checkAccessHash(accessHash)
  }

  def persistenceId: String = GroupOffice.persistenceIdFor(groupId)

  protected def getInitialState: GroupState = GroupState.empty(groupId)

  override protected def onRecoveryCompleted(): Unit = {
    super.onRecoveryCompleted()
    // set integrationStorage only for created group
    if (state.isCreated) {
      integrationStorage = new IntegrationTokensStorage(groupId, state.createdAt.get.toEpochMilli) //FIXME
    }
  }
}
