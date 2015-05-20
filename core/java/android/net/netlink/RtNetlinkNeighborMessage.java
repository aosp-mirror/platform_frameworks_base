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

import static android.net.netlink.StructNlMsgHdr.NLM_F_ACK;
import static android.net.netlink.StructNlMsgHdr.NLM_F_DUMP;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REPLACE;
import static android.net.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import android.net.netlink.StructNdaCacheInfo;
import android.net.netlink.StructNdMsg;
import android.net.netlink.StructNlAttr;
import android.net.netlink.StructNlMsgHdr;
import android.net.netlink.NetlinkMessage;
import android.system.OsConstants;
import android.util.Log;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


/**
 * A NetlinkMessage subclass for netlink error messages.
 *
 * see also: &lt;linux_src&gt;/include/uapi/linux/neighbour.h
 *
 * @hide
 */
public class RtNetlinkNeighborMessage extends NetlinkMessage {
    public static final short NDA_UNSPEC    = 0;
    public static final short NDA_DST       = 1;
    public static final short NDA_LLADDR    = 2;
    public static final short NDA_CACHEINFO = 3;
    public static final short NDA_PROBES    = 4;
    public static final short NDA_VLAN      = 5;
    public static final short NDA_PORT      = 6;
    public static final short NDA_VNI       = 7;
    public static final short NDA_IFINDEX   = 8;
    public static final short NDA_MASTER    = 9;

    private static StructNlAttr findNextAttrOfType(short attrType, ByteBuffer byteBuffer) {
        while (byteBuffer != null && byteBuffer.remaining() > 0) {
            final StructNlAttr nlAttr = StructNlAttr.peek(byteBuffer);
            if (nlAttr == null) {
                break;
            }
            if (nlAttr.nla_type == attrType) {
                return StructNlAttr.parse(byteBuffer);
            }
            if (byteBuffer.remaining() < nlAttr.getAlignedLength()) {
                break;
            }
            byteBuffer.position(byteBuffer.position() + nlAttr.getAlignedLength());
        }
        return null;
    }

    public static RtNetlinkNeighborMessage parse(StructNlMsgHdr header, ByteBuffer byteBuffer) {
        final RtNetlinkNeighborMessage neighMsg = new RtNetlinkNeighborMessage(header);

        neighMsg.mNdmsg = StructNdMsg.parse(byteBuffer);
        if (neighMsg.mNdmsg == null) {
            return null;
        }

        // Some of these are message-type dependent, and not always present.
        final int baseOffset = byteBuffer.position();
        StructNlAttr nlAttr = findNextAttrOfType(NDA_DST, byteBuffer);
        if (nlAttr != null) {
            neighMsg.mDestination = nlAttr.getValueAsInetAddress();
        }

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(NDA_LLADDR, byteBuffer);
        if (nlAttr != null) {
            neighMsg.mLinkLayerAddr = nlAttr.nla_value;
        }

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(NDA_PROBES, byteBuffer);
        if (nlAttr != null) {
            neighMsg.mNumProbes = nlAttr.getValueAsInt(0);
        }

        byteBuffer.position(baseOffset);
        nlAttr = findNextAttrOfType(NDA_CACHEINFO, byteBuffer);
        if (nlAttr != null) {
            neighMsg.mCacheInfo = StructNdaCacheInfo.parse(nlAttr.getValueAsByteBuffer());
        }

        final int kMinConsumed = StructNlMsgHdr.STRUCT_SIZE + StructNdMsg.STRUCT_SIZE;
        final int kAdditionalSpace = NetlinkConstants.alignedLengthOf(
                neighMsg.mHeader.nlmsg_len - kMinConsumed);
        if (byteBuffer.remaining() < kAdditionalSpace) {
            byteBuffer.position(byteBuffer.limit());
        } else {
            byteBuffer.position(baseOffset + kAdditionalSpace);
        }

        return neighMsg;
    }

    /**
     * A convenience method to create an RTM_GETNEIGH request message.
     */
    public static byte[] newGetNeighborsRequest(int seqNo) {
        final int length = StructNlMsgHdr.STRUCT_SIZE + StructNdMsg.STRUCT_SIZE;
        final byte[] bytes = new byte[length];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_len = length;
        nlmsghdr.nlmsg_type = NetlinkConstants.RTM_GETNEIGH;
        nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
        nlmsghdr.nlmsg_seq = seqNo;
        nlmsghdr.pack(byteBuffer);

        final StructNdMsg ndmsg = new StructNdMsg();
        ndmsg.pack(byteBuffer);

        return bytes;
    }

    /**
     * A convenience method to create an RTM_NEWNEIGH message, to modify
     * the kernel's state information for a specific neighbor.
     */
    public static byte[] newNewNeighborMessage(
            int seqNo, InetAddress ip, short nudState, int ifIndex, byte[] llAddr) {
        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        nlmsghdr.nlmsg_type = NetlinkConstants.RTM_NEWNEIGH;
        nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK | NLM_F_REPLACE;
        nlmsghdr.nlmsg_seq = seqNo;

        final RtNetlinkNeighborMessage msg = new RtNetlinkNeighborMessage(nlmsghdr);
        msg.mNdmsg = new StructNdMsg();
        msg.mNdmsg.ndm_family =
                (byte) ((ip instanceof Inet6Address) ? OsConstants.AF_INET6 : OsConstants.AF_INET);
        msg.mNdmsg.ndm_ifindex = ifIndex;
        msg.mNdmsg.ndm_state = nudState;
        msg.mDestination = ip;
        msg.mLinkLayerAddr = llAddr;  // might be null

        final byte[] bytes = new byte[msg.getRequiredSpace()];
        nlmsghdr.nlmsg_len = bytes.length;
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());
        msg.pack(byteBuffer);
        return bytes;
    }

    private StructNdMsg mNdmsg;
    private InetAddress mDestination;
    private byte[] mLinkLayerAddr;
    private int mNumProbes;
    private StructNdaCacheInfo mCacheInfo;

    private RtNetlinkNeighborMessage(StructNlMsgHdr header) {
        super(header);
        mNdmsg = null;
        mDestination = null;
        mLinkLayerAddr = null;
        mNumProbes = 0;
        mCacheInfo = null;
    }

    public StructNdMsg getNdHeader() {
        return mNdmsg;
    }

    public InetAddress getDestination() {
        return mDestination;
    }

    public byte[] getLinkLayerAddress() {
        return mLinkLayerAddr;
    }

    public int getProbes() {
        return mNumProbes;
    }

    public StructNdaCacheInfo getCacheInfo() {
        return mCacheInfo;
    }

    public int getRequiredSpace() {
        int spaceRequired = StructNlMsgHdr.STRUCT_SIZE + StructNdMsg.STRUCT_SIZE;
        if (mDestination != null) {
            spaceRequired += NetlinkConstants.alignedLengthOf(
                    StructNlAttr.NLA_HEADERLEN + mDestination.getAddress().length);
        }
        if (mLinkLayerAddr != null) {
            spaceRequired += NetlinkConstants.alignedLengthOf(
                    StructNlAttr.NLA_HEADERLEN + mLinkLayerAddr.length);
        }
        // Currently we don't write messages with NDA_PROBES nor NDA_CACHEINFO
        // attributes appended.  Fix later, if necessary.
        return spaceRequired;
    }

    private static void packNlAttr(short nlType, byte[] nlValue, ByteBuffer byteBuffer) {
        final StructNlAttr nlAttr = new StructNlAttr();
        nlAttr.nla_type = nlType;
        nlAttr.nla_value = nlValue;
        nlAttr.nla_len = (short) (StructNlAttr.NLA_HEADERLEN + nlAttr.nla_value.length);
        nlAttr.pack(byteBuffer);
    }

    public void pack(ByteBuffer byteBuffer) {
        getHeader().pack(byteBuffer) ;
        mNdmsg.pack(byteBuffer);

        if (mDestination != null) {
            packNlAttr(NDA_DST, mDestination.getAddress(), byteBuffer);
        }
        if (mLinkLayerAddr != null) {
            packNlAttr(NDA_LLADDR, mLinkLayerAddr, byteBuffer);
        }
    }

    @Override
    public String toString() {
        final String ipLiteral = (mDestination == null) ? "" : mDestination.getHostAddress();
        return "RtNetlinkNeighborMessage{ "
                + "nlmsghdr{" + (mHeader == null ? "" : mHeader.toString()) + "}, "
                + "ndmsg{" + (mNdmsg == null ? "" : mNdmsg.toString()) + "}, "
                + "destination{" + ipLiteral + "} "
                + "linklayeraddr{" + NetlinkConstants.hexify(mLinkLayerAddr) + "} "
                + "probes{" + mNumProbes + "} "
                + "cacheinfo{" + (mCacheInfo == null ? "" : mCacheInfo.toString()) + "} "
                + "}";
    }
}
