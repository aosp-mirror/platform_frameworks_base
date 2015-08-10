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
import java.nio.ByteBuffer;


/**
 * struct nlmsghdr
 *
 * see &lt;linux_src&gt;/include/uapi/linux/netlink.h
 *
 * @hide
 */
public class StructNlMsgHdr {
    // Already aligned.
    public static final int STRUCT_SIZE = 16;

    public static final short NLM_F_REQUEST = 0x0001;
    public static final short NLM_F_MULTI   = 0x0002;
    public static final short NLM_F_ACK     = 0x0004;
    public static final short NLM_F_ECHO    = 0x0008;
    // Flags for a GET request.
    public static final short NLM_F_ROOT    = 0x0100;
    public static final short NLM_F_MATCH   = 0x0200;
    public static final short NLM_F_DUMP    = NLM_F_ROOT|NLM_F_MATCH;
    // Flags for a NEW request.
    public static final short NLM_F_REPLACE   = 0x100;
    public static final short NLM_F_EXCL      = 0x200;
    public static final short NLM_F_CREATE    = 0x400;
    public static final short NLM_F_APPEND    = 0x800;


    public static String stringForNlMsgFlags(short flags) {
        final StringBuilder sb = new StringBuilder();
        if ((flags & NLM_F_REQUEST) != 0) {
            sb.append("NLM_F_REQUEST");
        }
        if ((flags & NLM_F_MULTI) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NLM_F_MULTI");
        }
        if ((flags & NLM_F_ACK) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NLM_F_ACK");
        }
        if ((flags & NLM_F_ECHO) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NLM_F_ECHO");
        }
        if ((flags & NLM_F_ROOT) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NLM_F_ROOT");
        }
        if ((flags & NLM_F_MATCH) != 0) {
            if (sb.length() > 0) { sb.append("|"); }
            sb.append("NLM_F_MATCH");
        }
        return sb.toString();
    }

    public static boolean hasAvailableSpace(ByteBuffer byteBuffer) {
        return byteBuffer != null && byteBuffer.remaining() >= STRUCT_SIZE;
    }

    public static StructNlMsgHdr parse(ByteBuffer byteBuffer) {
        if (!hasAvailableSpace(byteBuffer)) { return null; }

        // The ByteOrder must have already been set by the caller.  In most
        // cases ByteOrder.nativeOrder() is correct, with the exception
        // of usage within unittests.
        final StructNlMsgHdr struct = new StructNlMsgHdr();
        struct.nlmsg_len = byteBuffer.getInt();
        struct.nlmsg_type = byteBuffer.getShort();
        struct.nlmsg_flags = byteBuffer.getShort();
        struct.nlmsg_seq = byteBuffer.getInt();
        struct.nlmsg_pid = byteBuffer.getInt();

        if (struct.nlmsg_len < STRUCT_SIZE) {
            // Malformed.
            return null;
        }
        return struct;
    }

    public int nlmsg_len;
    public short nlmsg_type;
    public short nlmsg_flags;
    public int nlmsg_seq;
    public int nlmsg_pid;

    public StructNlMsgHdr() {
        nlmsg_len = 0;
        nlmsg_type = 0;
        nlmsg_flags = 0;
        nlmsg_seq = 0;
        nlmsg_pid = 0;
    }

    public void pack(ByteBuffer byteBuffer) {
        // The ByteOrder must have already been set by the caller.  In most
        // cases ByteOrder.nativeOrder() is correct, with the possible
        // exception of usage within unittests.
        byteBuffer.putInt(nlmsg_len);
        byteBuffer.putShort(nlmsg_type);
        byteBuffer.putShort(nlmsg_flags);
        byteBuffer.putInt(nlmsg_seq);
        byteBuffer.putInt(nlmsg_pid);
    }

    @Override
    public String toString() {
        final String typeStr = "" + nlmsg_type
                + "(" + NetlinkConstants.stringForNlMsgType(nlmsg_type) + ")";
        final String flagsStr = "" + nlmsg_flags
                + "(" + stringForNlMsgFlags(nlmsg_flags) + ")";
        return "StructNlMsgHdr{ "
                + "nlmsg_len{" + nlmsg_len + "}, "
                + "nlmsg_type{" + typeStr + "}, "
                + "nlmsg_flags{" + flagsStr + ")}, "
                + "nlmsg_seq{" + nlmsg_seq + "}, "
                + "nlmsg_pid{" + nlmsg_pid + "} "
                + "}";
    }
}
