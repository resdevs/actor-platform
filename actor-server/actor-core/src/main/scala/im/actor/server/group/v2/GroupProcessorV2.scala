package im.actor.server.group.v2

import akka.actor.{ ActorSystem, Props }
import im.actor.api.rpc.peers.{ ApiPeer, ApiPeerType }
import im.actor.server.cqrs.Processor
import im.actor.server.db.DbExtension
import im.actor.server.dialog.DialogExtension
import im.actor.server.group.GroupOffice
import im.actor.server.groupV2.GroupCommandsV2._
import im.actor.server.sequence.SeqUpdatesExtension

import scala.concurrent.{ ExecutionContext, Future }

private[group] object GroupProcessorV2 {
  def props = Props(classOf[GroupProcessorV2])
}

private[group] final class GroupProcessorV2
  extends Processor[GroupState]
  with GroupCommandHandlers {

  protected implicit val system: ActorSystem = context.system
  protected implicit val ec: ExecutionContext = system.dispatcher
  protected val db = DbExtension(system).db
  protected val seqUpdExt = SeqUpdatesExtension(system)
  protected val dialogExt = DialogExtension(system)

  protected val groupId = self.path.name.toInt
  protected val groupPeer = ApiPeer(ApiPeerType.Group, groupId)

  protected def handleCommand: Receive = {
    case upd: UpdateAvatar ⇒ updateAvatar(upd)
    case upd: UpdateTitle  ⇒ updateTitle(upd)
    case upd: UpdateTopic  ⇒ updateTopic(upd)
    case upd: UpdateAbout  ⇒ updateAbout(upd)
  }

  protected def handleQuery: PartialFunction[Any, Future[Any]] = ???

  def persistenceId: String = GroupOffice.persistenceIdFor(groupId)

  protected def getInitialState: GroupState = GroupState.empty(groupId)
}
