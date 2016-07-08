package im.actor.server.group.v2

import im.actor.api.rpc.groups.ApiMember

trait GroupsImplicits {
  implicit class ExtMember(m: Member) {
    def asStruct: ApiMember = ApiMember(m.userId, m.inviterUserId, m.invitedAt.toEpochMilli, Some(m.isAdmin))
  }
}
