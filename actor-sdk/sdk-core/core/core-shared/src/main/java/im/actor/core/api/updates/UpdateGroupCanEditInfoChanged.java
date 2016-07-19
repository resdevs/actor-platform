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

public class UpdateGroupCanEditInfoChanged extends Update {

    public static final int HEADER = 0xa47;
    public static UpdateGroupCanEditInfoChanged fromBytes(byte[] data) throws IOException {
        return Bser.parse(new UpdateGroupCanEditInfoChanged(), data);
    }

    private int groupId;
    private boolean canEditGroup;

    public UpdateGroupCanEditInfoChanged(int groupId, boolean canEditGroup) {
        this.groupId = groupId;
        this.canEditGroup = canEditGroup;
    }

    public UpdateGroupCanEditInfoChanged() {

    }

    public int getGroupId() {
        return this.groupId;
    }

    public boolean canEditGroup() {
        return this.canEditGroup;
    }

    @Override
    public void parse(BserValues values) throws IOException {
        this.groupId = values.getInt(1);
        this.canEditGroup = values.getBool(2);
    }

    @Override
    public void serialize(BserWriter writer) throws IOException {
        writer.writeInt(1, this.groupId);
        writer.writeBool(2, this.canEditGroup);
    }

    @Override
    public String toString() {
        String res = "update GroupCanEditInfoChanged{";
        res += "groupId=" + this.groupId;
        res += ", canEditGroup=" + this.canEditGroup;
        res += "}";
        return res;
    }

    @Override
    public int getHeaderKey() {
        return HEADER;
    }
}