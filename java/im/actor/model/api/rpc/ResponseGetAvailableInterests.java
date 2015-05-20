package im.actor.model.api.rpc;
/*
 *  Generated by the Actor API Scheme generator.  DO NOT EDIT!
 */

import im.actor.model.droidkit.bser.Bser;
import im.actor.model.droidkit.bser.BserParser;
import im.actor.model.droidkit.bser.BserObject;
import im.actor.model.droidkit.bser.BserValues;
import im.actor.model.droidkit.bser.BserWriter;
import im.actor.model.droidkit.bser.DataInput;
import im.actor.model.droidkit.bser.DataOutput;
import static im.actor.model.droidkit.bser.Utils.*;
import java.io.IOException;
import im.actor.model.network.parser.*;
import java.util.List;
import java.util.ArrayList;
import im.actor.model.api.*;

public class ResponseGetAvailableInterests extends Response {

    public static final int HEADER = 0x99;
    public static ResponseGetAvailableInterests fromBytes(byte[] data) throws IOException {
        return Bser.parse(new ResponseGetAvailableInterests(), data);
    }

    private List<Interest> rootInterests;

    public ResponseGetAvailableInterests(List<Interest> rootInterests) {
        this.rootInterests = rootInterests;
    }

    public ResponseGetAvailableInterests() {

    }

    public List<Interest> getRootInterests() {
        return this.rootInterests;
    }

    @Override
    public void parse(BserValues values) throws IOException {
        List<Interest> _rootInterests = new ArrayList<Interest>();
        for (int i = 0; i < values.getRepeatedCount(1); i ++) {
            _rootInterests.add(new Interest());
        }
        this.rootInterests = values.getRepeatedObj(1, _rootInterests);
    }

    @Override
    public void serialize(BserWriter writer) throws IOException {
        writer.writeRepeatedObj(1, this.rootInterests);
    }

    @Override
    public String toString() {
        String res = "tuple GetAvailableInterests{";
        res += "}";
        return res;
    }

    @Override
    public int getHeaderKey() {
        return HEADER;
    }
}
