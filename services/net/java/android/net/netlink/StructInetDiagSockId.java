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

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * struct inet_diag_req_v2
 *
 * see &lt;linux_src&gt;/include/uapi/linux/inet_diag.h
 *
 * struct inet_diag_sockid {
 *        __be16    idiag_sport;
 *        __be16    idiag_dport;
 *        __be32    idiag_src[4];
 *        __be32    idiag_dst[4];
 *        __u32     idiag_if;
 *        __u32     idiag_cookie[2];
 * #define INET_DIAG_NOCOOKIE (~0U)
 * };
 *
 * @hide
 */
public class StructInetDiagSockId {
    public static final int STRUCT_SIZE = 48;

    private final InetSocketAddress mLocSocketAddress;
    private final InetSocketAddress mRemSocketAddress;
    private final byte[] INET_DIAG_NOCOOKIE = new byte[]{
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};
    private final byte[] IPV4_PADDING = new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    public StructInetDiagSockId(InetSocketAddress loc, InetSocketAddress rem) {
        mLocSocketAddress = loc;
        mRemSocketAddress = rem;
    }

    public void pack(ByteBuffer byteBuffer) {
        byteBuffer.order(BIG_ENDIAN);
        byteBuffer.putShort((short) mLocSocketAddress.getPort());
        byteBuffer.putShort((short) mRemSocketAddress.getPort());
        byteBuffer.put(mLocSocketAddress.getAddress().getAddress());
        if (mLocSocketAddress.getAddress() instanceof Inet4Address) {
            byteBuffer.put(IPV4_PADDING);
        }
        byteBuffer.put(mRemSocketAddress.getAddress().getAddress());
        if (mRemSocketAddress.getAddress() instanceof Inet4Address) {
            byteBuffer.put(IPV4_PADDING);
        }
        byteBuffer.order(ByteOrder.nativeOrder());
        byteBuffer.putInt(0);
        byteBuffer.put(INET_DIAG_NOCOOKIE);
    }

    @Override
    public String toString() {
        return "StructInetDiagSockId{ "
                + "idiag_sport{" + mLocSocketAddress.getPort() + "}, "
                + "idiag_dport{" + mRemSocketAddress.getPort() + "}, "
                + "idiag_src{" + mLocSocketAddress.getAddress().getHostAddress() + "}, "
                + "idiag_dst{" + mRemSocketAddress.getAddress().getHostAddress() + "}, "
                + "idiag_if{" + 0 + "} "
                + "idiag_cookie{INET_DIAG_NOCOOKIE}"
                + "}";
    }
}
