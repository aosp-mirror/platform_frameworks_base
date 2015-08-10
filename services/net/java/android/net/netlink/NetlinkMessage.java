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
import android.net.netlink.NetlinkErrorMessage;
import android.net.netlink.RtNetlinkNeighborMessage;
import android.net.netlink.StructNlAttr;
import android.net.netlink.StructNlMsgHdr;
import android.util.Log;

import java.nio.ByteBuffer;


/**
 * NetlinkMessage base class for other, more specific netlink message types.
 *
 * Classes that extend NetlinkMessage should:
 *     - implement a public static parse(StructNlMsgHdr, ByteBuffer) method
 *     - returning either null (parse errors) or a new object of the subclass
 *       type (cast-able to NetlinkMessage)
 *
 * NetlinkMessage.parse() should be updated to know which nlmsg_type values
 * correspond with which message subclasses.
 *
 * @hide
 */
public class NetlinkMessage {
    private final static String TAG = "NetlinkMessage";

    public static NetlinkMessage parse(ByteBuffer byteBuffer) {
        final int startPosition = (byteBuffer != null) ? byteBuffer.position() : -1;
        final StructNlMsgHdr nlmsghdr = StructNlMsgHdr.parse(byteBuffer);
        if (nlmsghdr == null) {
            return null;
        }

        int payloadLength = NetlinkConstants.alignedLengthOf(nlmsghdr.nlmsg_len);
        payloadLength -= StructNlMsgHdr.STRUCT_SIZE;
        if (payloadLength < 0 || payloadLength > byteBuffer.remaining()) {
            // Malformed message or runt buffer.  Pretend the buffer was consumed.
            byteBuffer.position(byteBuffer.limit());
            return null;
        }

        switch (nlmsghdr.nlmsg_type) {
            //case NetlinkConstants.NLMSG_NOOP:
            case NetlinkConstants.NLMSG_ERROR:
                return (NetlinkMessage) NetlinkErrorMessage.parse(nlmsghdr, byteBuffer);
            case NetlinkConstants.NLMSG_DONE:
                byteBuffer.position(byteBuffer.position() + payloadLength);
                return new NetlinkMessage(nlmsghdr);
            //case NetlinkConstants.NLMSG_OVERRUN:
            case NetlinkConstants.RTM_NEWNEIGH:
            case NetlinkConstants.RTM_DELNEIGH:
            case NetlinkConstants.RTM_GETNEIGH:
                return (NetlinkMessage) RtNetlinkNeighborMessage.parse(nlmsghdr, byteBuffer);
            default:
                if (nlmsghdr.nlmsg_type <= NetlinkConstants.NLMSG_MAX_RESERVED) {
                    // Netlink control message.  Just parse the header for now,
                    // pretending the whole message was consumed.
                    byteBuffer.position(byteBuffer.position() + payloadLength);
                    return new NetlinkMessage(nlmsghdr);
                }
                return null;
        }
    }

    protected StructNlMsgHdr mHeader;

    public NetlinkMessage(StructNlMsgHdr nlmsghdr) {
        mHeader = nlmsghdr;
    }

    public StructNlMsgHdr getHeader() {
        return mHeader;
    }

    @Override
    public String toString() {
        return "NetlinkMessage{" + (mHeader == null ? "" : mHeader.toString()) + "}";
    }
}
