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
    public static final int NLA_HEADERLEN  = 4;
    public static final int NLA_F_NESTED   = (1 << 15);

    public static short makeNestedType(short type) {
        return (short) (type | NLA_F_NESTED);
    }

    // Return a (length, type) object only, without consuming any bytes in
    // |byteBuffer| and without copying or interpreting any value bytes.
    // This is used for scanning over a packed set of struct nlattr's,
    // looking for instances of a particular type.
    public static StructNlAttr peek(ByteBuffer byteBuffer) {
        if (byteBuffer == null || byteBuffer.remaining() < NLA_HEADERLEN) {
            return null;
        }
        final int baseOffset = byteBuffer.position();

        // Assume the byte order of the buffer is the expected byte order of the value.
        final StructNlAttr struct = new StructNlAttr(byteBuffer.order());
        // The byte order of nla_len and nla_type is always native.
        final ByteOrder originalOrder = byteBuffer.order();
        byteBuffer.order(ByteOrder.nativeOrder());
        try {
            struct.nla_len = byteBuffer.getShort();
            struct.nla_type = byteBuffer.getShort();
        } finally {
            byteBuffer.order(originalOrder);
        }

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

    public short nla_len = (short) NLA_HEADERLEN;
    public short nla_type;
    public byte[] nla_value;

    // The byte order used to read/write the value member. Netlink length and
    // type members are always read/written in native order.
    private ByteOrder mByteOrder = ByteOrder.nativeOrder();

    public StructNlAttr() {}

    public StructNlAttr(ByteOrder byteOrder) {
        mByteOrder = byteOrder;
    }

    public StructNlAttr(short type, byte value) {
        nla_type = type;
        setValue(new byte[1]);
        nla_value[0] = value;
    }

    public StructNlAttr(short type, short value) {
        this(type, value, ByteOrder.nativeOrder());
    }

    public StructNlAttr(short type, short value, ByteOrder order) {
        this(order);
        nla_type = type;
        setValue(new byte[Short.BYTES]);
        getValueAsByteBuffer().putShort(value);
    }

    public StructNlAttr(short type, int value) {
        this(type, value, ByteOrder.nativeOrder());
    }

    public StructNlAttr(short type, int value, ByteOrder order) {
        this(order);
        nla_type = type;
        setValue(new byte[Integer.BYTES]);
        getValueAsByteBuffer().putInt(value);
    }

    public StructNlAttr(short type, InetAddress ip) {
        nla_type = type;
        setValue(ip.getAddress());
    }

    public StructNlAttr(short type, StructNlAttr... nested) {
        this();
        nla_type = makeNestedType(type);

        int payloadLength = 0;
        for (StructNlAttr nla : nested) payloadLength += nla.getAlignedLength();
        setValue(new byte[payloadLength]);

        final ByteBuffer buf = getValueAsByteBuffer();
        for (StructNlAttr nla : nested) {
            nla.pack(buf);
        }
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
        if (byteBuffer == null || byteBuffer.remaining() != Integer.BYTES) {
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
        final ByteOrder originalOrder = byteBuffer.order();
        final int originalPosition = byteBuffer.position();

        byteBuffer.order(ByteOrder.nativeOrder());
        try {
            byteBuffer.putShort(nla_len);
            byteBuffer.putShort(nla_type);
            if (nla_value != null) byteBuffer.put(nla_value);
        } finally {
            byteBuffer.order(originalOrder);
        }
        byteBuffer.position(originalPosition + getAlignedLength());
    }

    private void setValue(byte[] value) {
        nla_value = value;
        nla_len = (short) (NLA_HEADERLEN + ((nla_value != null) ? nla_value.length : 0));
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
