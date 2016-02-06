package com.android.hotspot2.asn1;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Collection;

public class Asn1Integer extends Asn1Object {
    private static final int SignBit = 0x80;

    private final long mValue;
    private final BigInteger mBigValue;

    public Asn1Integer(int tag, Asn1Class asn1Class, int length, ByteBuffer data) {
        super(tag, asn1Class, false, length);

        if (length <= 8) {
            long value = (data.get(data.position()) & SignBit) != 0 ? -1 : 0;
            for (int n = 0; n < length; n++) {
                value = (value << Byte.SIZE) | data.get();
            }
            mValue = value;
            mBigValue = null;
        } else {
            byte[] payload = new byte[length];
            data.get(payload);
            mValue = 0;
            mBigValue = new BigInteger(payload);
        }
    }

    public boolean isBigValue() {
        return mBigValue != null;
    }

    public long getValue() {
        return mValue;
    }

    public BigInteger getBigValue() {
        return mBigValue;
    }

    @Override
    public Collection<Asn1Object> getChildren() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        if (isBigValue()) {
            return super.toString() + '=' + mBigValue.toString(16);
        } else {
            return super.toString() + '=' + mValue;
        }
    }
}
