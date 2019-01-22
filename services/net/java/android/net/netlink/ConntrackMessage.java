/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.netlink.StructNlMsgHdr.NLM_F_ACK;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REPLACE;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import static java.nio.ByteOrder.BIG_ENDIAN;

import android.system.OsConstants;

import java.net.Inet4Address;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * A NetlinkMessage subclass for netlink conntrack messages.
 *
 * see also: &lt;linux_src&gt;/include/uapi/linux/netfilter/nfnetlink_conntrack.h
 *
 * @hide
 */
public class ConntrackMessage extends NetlinkMessage {
    public static final int STRUCT_SIZE = StructNlMsgHdr.STRUCT_SIZE + StructNfGenMsg.STRUCT_SIZE;

    public static final short NFNL_SUBSYS_CTNETLINK = 1;
    public static final short IPCTNL_MSG_CT_NEW = 0;

    // enum ctattr_type
    public static final short CTA_TUPLE_ORIG  = 1;
    public static final short CTA_TUPLE_REPLY = 2;
    public static final short CTA_TIMEOUT     = 7;

    // enum ctattr_tuple
    public static final short CTA_TUPLE_IP    = 1;
    public static final short CTA_TUPLE_PROTO = 2;

    // enum ctattr_ip
    public static final short CTA_IP_V4_SRC = 1;
    public static final short CTA_IP_V4_DST = 2;

    // enum ctattr_l4proto
    public static final short CTA_PROTO_NUM      = 1;
    public static final short CTA_PROTO_SRC_PORT = 2;
    public static final short CTA_PROTO_DST_PORT = 3;

    public static byte[] newIPv4TimeoutUpdateRequest(
            int proto, Inet4Address src, int sport, Inet4Address dst, int dport, int timeoutSec) {
        // *** STYLE WARNING ***
        //
        // Code below this point uses extra block indentation to highlight the
        // packing of nested tuple netlink attribute types.
        final StructNlAttr ctaTupleOrig = new StructNlAttr(CTA_TUPLE_ORIG,
                new StructNlAttr(CTA_TUPLE_IP,
                        new StructNlAttr(CTA_IP_V4_SRC, src),
                        new StructNlAttr(CTA_IP_V4_DST, dst)),
                new StructNlAttr(CTA_TUPLE_PROTO,
                        new StructNlAttr(CTA_PROTO_NUM, (byte) proto),
                        new StructNlAttr(CTA_PROTO_SRC_PORT, (short) sport, BIG_ENDIAN),
                        new StructNlAttr(CTA_PROTO_DST_PORT, (short) dport, BIG_ENDIAN)));

        final StructNlAttr ctaTimeout = new StructNlAttr(CTA_TIMEOUT, timeoutSec, BIG_ENDIAN);

        final int payloadLength = ctaTupleOrig.getAlignedLength() + ctaTimeout.getAlignedLength();
        final byte[] bytes = new byte[STRUCT_SIZE + payloadLength];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final ConntrackMessage ctmsg = new ConntrackMessage();
        ctmsg.mHeader.nlmsg_len = bytes.length;
        ctmsg.mHeader.nlmsg_type = (NFNL_SUBSYS_CTNETLINK << 8) | IPCTNL_MSG_CT_NEW;
        ctmsg.mHeader.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK | NLM_F_REPLACE;
        ctmsg.mHeader.nlmsg_seq = 1;
        ctmsg.pack(byteBuffer);

        ctaTupleOrig.pack(byteBuffer);
        ctaTimeout.pack(byteBuffer);

        return bytes;
    }

    protected StructNfGenMsg mNfGenMsg;

    private ConntrackMessage() {
        super(new StructNlMsgHdr());
        mNfGenMsg = new StructNfGenMsg((byte) OsConstants.AF_INET);
    }

    public void pack(ByteBuffer byteBuffer) {
        mHeader.pack(byteBuffer);
        mNfGenMsg.pack(byteBuffer);
    }
}
