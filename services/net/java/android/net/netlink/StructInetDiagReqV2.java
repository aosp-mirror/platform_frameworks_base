/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static java.nio.ByteOrder.BIG_ENDIAN;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * struct inet_diag_req_v2
 *
 * see &lt;linux_src&gt;/include/uapi/linux/inet_diag.h
 *
 *      struct inet_diag_req_v2 {
 *          __u8    sdiag_family;
 *          __u8    sdiag_protocol;
 *          __u8    idiag_ext;
 *          __u8    pad;
 *          __u32   idiag_states;
 *          struct  inet_diag_sockid id;
 *      };
 *
 * @hide
 */
public class StructInetDiagReqV2 {
    public static final int STRUCT_SIZE = 8 + StructInetDiagSockId.STRUCT_SIZE;

    private final byte sdiag_family;
    private final byte sdiag_protocol;
    private final StructInetDiagSockId id;
    private final int INET_DIAG_REQ_V2_ALL_STATES = (int) 0xffffffff;


    public StructInetDiagReqV2(int protocol, InetSocketAddress local, InetSocketAddress remote,
                               int family) {
        sdiag_family = (byte) family;
        sdiag_protocol = (byte) protocol;
        id = new StructInetDiagSockId(local, remote);
    }

    public void pack(ByteBuffer byteBuffer) {
        // The ByteOrder must have already been set by the caller.
        byteBuffer.put((byte) sdiag_family);
        byteBuffer.put((byte) sdiag_protocol);
        byteBuffer.put((byte) 0);
        byteBuffer.put((byte) 0);
        byteBuffer.putInt(INET_DIAG_REQ_V2_ALL_STATES);
        id.pack(byteBuffer);
    }

    @Override
    public String toString() {
        final String familyStr = NetlinkConstants.stringForAddressFamily(sdiag_family);
        final String protocolStr = NetlinkConstants.stringForAddressFamily(sdiag_protocol);

        return "StructInetDiagReqV2{ "
                + "sdiag_family{" + familyStr + "}, "
                + "sdiag_protocol{" + protocolStr + "}, "
                + "idiag_ext{" + 0 + ")}, "
                + "pad{" + 0 + "}, "
                + "idiag_states{" + Integer.toHexString(INET_DIAG_REQ_V2_ALL_STATES) + "}, "
                + id.toString()
                + "}";
    }
}
