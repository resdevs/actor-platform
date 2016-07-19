package im.actor.core.api.updates;
/*
 *  Generated by the Actor API Scheme generator.  DO NOT EDIT!
 */

import im.actor.runtime.bser.*;
import im.actor.runtime.collections.*;
import static im.actor.runtime.bser.Utils.*;
import im.actor.core.network.parser.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.google.j2objc.annotations.ObjectiveCName;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import im.actor.core.api.*;

public class UpdateGroupCanEditAdminsChanged extends Update {

    public static final int HEADER = 0xa49;
    public static UpdateGroupCanEditAdminsChanged fromBytes(byte[] data) throws IOException {
        return Bser.parse(new UpdateGroupCanEditAdminsChanged(), data);
    }

    private int groupId;
    private boolean canAssignAdmins;

    public UpdateGroupCanEditAdminsChanged(int groupId, boolean canAssignAdmins) {
        this.groupId = groupId;
        this.canAssignAdmins = canAssignAdmins;
    }

    public UpdateGroupCanEditAdminsChanged() {

    }

    public int getGroupId() {
        return this.groupId;
    }

    public boolean canAssignAdmins() {
        return this.canAssignAdmins;
    }

    @Override
    public void parse(BserValues values) throws IOException {
        this.groupId = values.getInt(1);
        this.canAssignAdmins = values.getBool(2);
    }

    @Override
    public void serialize(BserWriter writer) throws IOException {
        writer.writeInt(1, this.groupId);
        writer.writeBool(2, this.canAssignAdmins);
    }

    @Override
    public String toString() {
        String res = "update GroupCanEditAdminsChanged{";
        res += "groupId=" + this.groupId;
        res += "}";
        return res;
    }

    @Override
    public int getHeaderKey() {
        return HEADER;
    }
}