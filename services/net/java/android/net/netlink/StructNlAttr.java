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
import libcore.io.SizeOf;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;


/**
 * struct nlattr
 *
 * see: &lt;linux_src&gt;/include/uapi/linux/netlink.h
 *
 * @hide
 */
public class StructNlAttr {
    // Already aligned.
    public static final int NLA_HEADERLEN         = 4;

    // Return a (length, type) object only, without consuming any bytes in
    // |byteBuffer| and without copying or interpreting any value bytes.
    // This is used for scanning over a packed set of struct nlattr's,
    // looking for instances of a particular type.
    public static StructNlAttr peek(ByteBuffer byteBuffer) {
        if (byteBuffer == null || byteBuffer.remaining() < NLA_HEADERLEN) {
            return null;
        }
        final int baseOffset = byteBuffer.position();

        final StructNlAttr struct = new StructNlAttr();
        struct.nla_len = byteBuffer.getShort();
        struct.nla_type = byteBuffer.getShort();
        struct.mByteOrder = byteBuffer.order();

        byteBuffer.position(baseOffset);
        if (struct.nla_len < NLA_HEADERLEN) {
            // Malformed.
            return null;
        }
        return struct;
    }

    public static StructNlAttr parse(ByteBuffer byteBuffer) {
        final StructNlAttr struct = peek(byteBuffer);
        if (struct == null || byteBuffer.remaining() < struct.getAlignedLength()) {
            return null;
        }

        final int baseOffset = byteBuffer.position();
        byteBuffer.position(baseOffset + NLA_HEADERLEN);

        int valueLen = ((int) struct.nla_len) & 0xffff;
        valueLen -= NLA_HEADERLEN;
        if (valueLen > 0) {
            struct.nla_value = new byte[valueLen];
            byteBuffer.get(struct.nla_value, 0, valueLen);
            byteBuffer.position(baseOffset + struct.getAlignedLength());
        }
        return struct;
    }

    public short nla_len;
    public short nla_type;
    public byte[] nla_value;
    public ByteOrder mByteOrder;

    public StructNlAttr() {
        mByteOrder = ByteOrder.nativeOrder();
    }

    public int getAlignedLength() {
        return NetlinkConstants.alignedLengthOf(nla_len);
    }

    public ByteBuffer getValueAsByteBuffer() {
        if (nla_value == null) { return null; }
        final ByteBuffer byteBuffer = ByteBuffer.wrap(nla_value);
        byteBuffer.order(mByteOrder);
        return byteBuffer;
    }

    public int getValueAsInt(int defaultValue) {
        final ByteBuffer byteBuffer = getValueAsByteBuffer();
        if (byteBuffer == null || byteBuffer.remaining() != SizeOf.INT) {
            return defaultValue;
        }
        return getValueAsByteBuffer().getInt();
    }

    public InetAddress getValueAsInetAddress() {
        if (nla_value == null) { return null; }

        try {
            return InetAddress.getByAddress(nla_value);
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    public void pack(ByteBuffer byteBuffer) {
        final int originalPosition = byteBuffer.position();
        byteBuffer.putShort(nla_len);
        byteBuffer.putShort(nla_type);
        byteBuffer.put(nla_value);
        byteBuffer.position(originalPosition + getAlignedLength());
    }

    @Override
    public String toString() {
        return "StructNlAttr{ "
                + "nla_len{" + nla_len + "}, "
                + "nla_type{" + nla_type + "}, "
                + "nla_value{" + NetlinkConstants.hexify(nla_value) + "}, "
                + "}";
    }
}
