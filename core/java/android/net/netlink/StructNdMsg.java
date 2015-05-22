/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.netlink;

import android.net.netlink.NetlinkConstants;
import android.system.OsConstants;
import java.nio.ByteBuffer;


/**
 * struct ndmsg
 *
 * see: &lt;linux_src&gt;/include/uapi/linux/neighbour.h
 *
 * @hide
 */
public class StructNdMsg {
    // Already aligned.
    public static final int STRUCT_SIZE = 12;

    // Neighbor Cache Entry States
    public static final short NUD_NONE        = 0x00;
    public static final short NUD_INCOMPLETE  = 0x01;
    public static final short NUD_REACHABLE   = 0x02;
    public static final short NUD_STALE       = 0x04;
    public static final short NUD_DELAY       = 0x08;
    public static final short NUD_PROBE       = 0x10;
    public static final short NUD_FAILED      = 0x20;
    public static final short NUD_NOARP       = 0x40;
    public static final short NUD_PERMANENT   = 0x80;

    public static String stringForNudState(short nudState) {
        switch (nudState) {
            case NUD_NONE: return "NUD_NONE";
            case NUD_INCOMPLETE: return "NUD_INCOMPLETE";
            case NUD_REACHABLE: return "NUD_REACHABLE";
            case NUD_STALE: return "NUD_STALE";
            case NUD_DELAY: return "NUD_DELAY";
            case NUD_PROBE: return "NUD_PROBE";
            case NUD_FAILED: return "NUD_FAILED";
            case NUD_NOARP: return "NUD_NOARP";
            case NUD_PERMANENT: return "NUD_PERMANENT";
            default:
                return "unknown NUD state: " + String.valueOf(nudState);
        }
    }

    public static boolean isNudStateConnected(short nudState) {
        return ((nudState & (NUD_PERMANENT|NUD_NOARP|NUD_REACHABLE)) != 0);
    }

    // Neighbor Cache Entry Flags
    public static byte NTF_USE       = (byte) 0x01;
    public static byte NTF_SELF      = (byte) 0x02;
    public static byte NTF_MASTER    = (byte) 0x04;
    public static byte NTF_PROXY     = (byte) 0x08;
    public static byte NTF_ROUTER    = (byte) 0x80;

    public static String stringForNudFlags(byte flags) {
        final StringBuilder sb = new StringBuilder();
        if ((flags & NTF_USE) != 0) {
            sb.append("NTF_USE");
        }
        if ((flags & NTF_SELF) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NTF_SELF");
        }
        if ((flags & NTF_MASTER) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NTF_MASTER");
        }
        if ((flags & NTF_PROXY) != 0) {
            if (sb.length() > 0) { sb.append("|");
        }
            sb.append("NTF_PROXY"); }
        if ((flags & NTF_ROUTER) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NTF_ROUTER");
        }
        return sb.toString();
    }

    private static boolean hasAvailableSpace(ByteBuffer byteBuffer) {
        return byteBuffer != null && byteBuffer.remaining() >= STRUCT_SIZE;
    }

    public static StructNdMsg parse(ByteBuffer byteBuffer) {
        if (!hasAvailableSpace(byteBuffer)) { return null; }

        // The ByteOrder must have already been set by the caller.  In most
        // cases ByteOrder.nativeOrder() is correct, with the possible
        // exception of usage within unittests.
        final StructNdMsg struct = new StructNdMsg();
        struct.ndm_family = byteBuffer.get();
        final byte pad1 = byteBuffer.get();
        final short pad2 = byteBuffer.getShort();
        struct.ndm_ifindex = byteBuffer.getInt();
        struct.ndm_state = byteBuffer.getShort();
        struct.ndm_flags = byteBuffer.get();
        struct.ndm_type = byteBuffer.get();
        return struct;
    }

    public byte ndm_family;
    public int ndm_ifindex;
    public short ndm_state;
    public byte ndm_flags;
    public byte ndm_type;

    public StructNdMsg() {
        ndm_family = (byte) OsConstants.AF_UNSPEC;
    }

    public void pack(ByteBuffer byteBuffer) {
        // The ByteOrder must have already been set by the caller.  In most
        // cases ByteOrder.nativeOrder() is correct, with the exception
        // of usage within unittests.
        byteBuffer.put(ndm_family);
        byteBuffer.put((byte) 0);         // pad1
        byteBuffer.putShort((short) 0);   // pad2
        byteBuffer.putInt(ndm_ifindex);
        byteBuffer.putShort(ndm_state);
        byteBuffer.put(ndm_flags);
        byteBuffer.put(ndm_type);
    }

    public boolean nudConnected() {
        return isNudStateConnected(ndm_state);
    }

    public boolean nudValid() {
        return (nudConnected() || ((ndm_state & (NUD_PROBE|NUD_STALE|NUD_DELAY)) != 0));
    }

    @Override
    public String toString() {
        final String stateStr = "" + ndm_state + " (" + stringForNudState(ndm_state) + ")";
        final String flagsStr = "" + ndm_flags + " (" + stringForNudFlags(ndm_flags) + ")";
        return "StructNdMsg{ "
                + "family{" + NetlinkConstants.stringForAddressFamily((int) ndm_family) + "}, "
                + "ifindex{" + ndm_ifindex + "}, "
                + "state{" + stateStr + "}, "
                + "flags{" + flagsStr + "}, "
                + "type{" + ndm_type + "} "
                + "}";
    }
}
