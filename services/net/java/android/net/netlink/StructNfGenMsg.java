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

import libcore.io.SizeOf;

import java.nio.ByteBuffer;


/**
 * struct nfgenmsg
 *
 * see &lt;linux_src&gt;/include/uapi/linux/netfilter/nfnetlink.h
 *
 * @hide
 */
public class StructNfGenMsg {
    public static final int STRUCT_SIZE = 2 + SizeOf.SHORT;

    public static final int NFNETLINK_V0 = 0;

    final public byte nfgen_family;
    final public byte version;
    final public short res_id;  // N.B.: this is big endian in the kernel

    public StructNfGenMsg(byte family) {
        nfgen_family = family;
        version = (byte) NFNETLINK_V0;
        res_id = (short) 0;
    }

    public void pack(ByteBuffer byteBuffer) {
        byteBuffer.put(nfgen_family);
        byteBuffer.put(version);
        byteBuffer.putShort(res_id);
    }
}
