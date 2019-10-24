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

import android.annotation.Nullable;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

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

    private final byte mSdiagFamily;
    private final byte mSdiagProtocol;
    private final byte mIdiagExt;
    private final byte mPad;
    private final StructInetDiagSockId mId;
    private final int mState;
    public static final int INET_DIAG_REQ_V2_ALL_STATES = (int) 0xffffffff;

    public StructInetDiagReqV2(int protocol, InetSocketAddress local, InetSocketAddress remote,
            int family) {
        this(protocol, local, remote, family, 0 /* pad */, 0 /* extension */,
                INET_DIAG_REQ_V2_ALL_STATES);
    }

    public StructInetDiagReqV2(int protocol, @Nullable InetSocketAddress local,
            @Nullable InetSocketAddress remote, int family, int pad, int extension, int state)
            throws NullPointerException {
        mSdiagFamily = (byte) family;
        mSdiagProtocol = (byte) protocol;
        // Request for all sockets if no specific socket is requested. Specify the local and remote
        // socket address information for target request socket.
        if ((local == null) != (remote == null)) {
            throw new NullPointerException("Local and remote must be both null or both non-null");
        }
        mId = ((local != null && remote != null) ? new StructInetDiagSockId(local, remote) : null);
        mPad = (byte) pad;
        mIdiagExt = (byte) extension;
        mState = state;
    }

    public void pack(ByteBuffer byteBuffer) {
        // The ByteOrder must have already been set by the caller.
        byteBuffer.put((byte) mSdiagFamily);
        byteBuffer.put((byte) mSdiagProtocol);
        byteBuffer.put((byte) mIdiagExt);
        byteBuffer.put((byte) mPad);
        byteBuffer.putInt(mState);
        if (mId != null) mId.pack(byteBuffer);
    }

    @Override
    public String toString() {
        final String familyStr = NetlinkConstants.stringForAddressFamily(mSdiagFamily);
        final String protocolStr = NetlinkConstants.stringForAddressFamily(mSdiagProtocol);

        return "StructInetDiagReqV2{ "
                + "sdiag_family{" + familyStr + "}, "
                + "sdiag_protocol{" + protocolStr + "}, "
                + "idiag_ext{" + mIdiagExt + ")}, "
                + "pad{" + mPad + "}, "
                + "idiag_states{" + Integer.toHexString(mState) + "}, "
                + ((mId != null) ? mId.toString() : "inet_diag_sockid=null")
                + "}";
    }
}
