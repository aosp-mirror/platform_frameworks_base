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
import android.net.netlink.StructNlMsgHdr;

import java.nio.ByteBuffer;


/**
 * struct nlmsgerr
 *
 * see &lt;linux_src&gt;/include/uapi/linux/netlink.h
 *
 * @hide
 */
public class StructNlMsgErr {
    public static final int STRUCT_SIZE = Integer.BYTES + StructNlMsgHdr.STRUCT_SIZE;

    public static boolean hasAvailableSpace(ByteBuffer byteBuffer) {
        return byteBuffer != null && byteBuffer.remaining() >= STRUCT_SIZE;
    }

    public static StructNlMsgErr parse(ByteBuffer byteBuffer) {
        if (!hasAvailableSpace(byteBuffer)) { return null; }

        // The ByteOrder must have already been set by the caller.  In most
        // cases ByteOrder.nativeOrder() is correct, with the exception
        // of usage within unittests.
        final StructNlMsgErr struct = new StructNlMsgErr();
        struct.error = byteBuffer.getInt();
        struct.msg = StructNlMsgHdr.parse(byteBuffer);
        return struct;
    }

    public int error;
    public StructNlMsgHdr msg;

    public void pack(ByteBuffer byteBuffer) {
        // The ByteOrder must have already been set by the caller.  In most
        // cases ByteOrder.nativeOrder() is correct, with the possible
        // exception of usage within unittests.
        byteBuffer.putInt(error);
        if (msg != null) {
            msg.pack(byteBuffer);
        }
    }

    @Override
    public String toString() {
        return "StructNlMsgErr{ "
                + "error{" + error + "}, "
                + "msg{" + (msg == null ? "" : msg.toString()) + "} "
                + "}";
    }
}
