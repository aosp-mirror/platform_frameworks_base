package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;
import com.android.hotspot2.osu.commands.OSUCommandData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class PostDevDataResponse extends OSUResponse {
    private final List<OSUCommand> mOSUCommands;

    public PostDevDataResponse(XMLNode root) throws OMAException {
        super(root, OSUMessageType.PostDevData);

        if (getStatus() == OSUStatus.Error) {
            mOSUCommands = null;
            return;
        }

        mOSUCommands = new ArrayList<>();
        for (XMLNode child : root.getChildren()) {
            mOSUCommands.add(new OSUCommand(child));
        }
    }

    public OSUCommandID getOSUCommand() {
        return mOSUCommands.size() == 1 ? mOSUCommands.get(0).getOSUCommand() : null;
    }

    public ExecCommand getExecCommand() {
        return mOSUCommands.size() == 1 ? mOSUCommands.get(0).getExecCommand() : null;
    }

    public OSUCommandData getCommandData() {
        return mOSUCommands.size() == 1 ? mOSUCommands.get(0).getCommandData() : null;
    }

    public Collection<OSUCommand> getCommands() {
        return Collections.unmodifiableCollection(mOSUCommands);
    }

    @Override
    public String toString() {
        return super.toString() + ", commands " + mOSUCommands;
    }
}
