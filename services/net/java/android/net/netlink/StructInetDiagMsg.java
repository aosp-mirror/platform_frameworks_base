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
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.util.Log;

/**
 * struct inet_diag_msg
 *
 * see &lt;linux_src&gt;/include/uapi/linux/inet_diag.h
 *
 * struct inet_diag_msg {
 *      __u8    idiag_family;
 *      __u8    idiag_state;
 *      __u8    idiag_timer;
 *      __u8    idiag_retrans;
 *      struct  inet_diag_sockid id;
 *      __u32   idiag_expires;
 *      __u32   idiag_rqueue;
 *      __u32   idiag_wqueue;
 *      __u32   idiag_uid;
 *      __u32   idiag_inode;
 * };
 *
 * @hide
 */
public class StructInetDiagMsg {
    public static final int STRUCT_SIZE = 4 + StructInetDiagSockId.STRUCT_SIZE + 20;
    private static final int IDIAG_UID_OFFSET = StructNlMsgHdr.STRUCT_SIZE + 4 +
            StructInetDiagSockId.STRUCT_SIZE + 12;
    public int idiag_uid;

    public static StructInetDiagMsg parse(ByteBuffer byteBuffer) {
        StructInetDiagMsg struct = new StructInetDiagMsg();
        struct.idiag_uid = byteBuffer.getInt(IDIAG_UID_OFFSET);
        return struct;
    }

    @Override
    public String toString() {
        return "StructInetDiagMsg{ "
                + "idiag_uid{" + idiag_uid + "}, "
                + "}";
    }
}
