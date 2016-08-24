package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.util.Collection;

public class Asn1Octets extends Asn1Object {
    private final byte[] mOctets;
    private final int mBitResidual;

    public Asn1Octets(int tag, Asn1Class asn1Class, int length, ByteBuffer data) {
        super(tag, asn1Class, false, length);
        mOctets = new byte[length];
        data.get(mOctets);
        mBitResidual = -1;
    }

    public Asn1Octets(int tag, Asn1Class asn1Class, int length, ByteBuffer data, int bitResidual) {
        super(tag, asn1Class, false, length);
        mOctets = new byte[length - 1];
        data.get(mOctets);
        mBitResidual = bitResidual;
    }

    public byte[] getOctets() {
        return mOctets;
    }

    @Override
    public Collection<Asn1Object> getChildren() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (byte b : mOctets) {
            sb.append(String.format(" %02x", b & Asn1Decoder.ByteMask));
        }
        if (mBitResidual >= 0) {
            return super.toString() + '=' + sb + '/' + mBitResidual;
        } else if (getTag() == Asn1Decoder.TAG_NULL && getLength() == 0) {
            return super.toString();
        } else {
            return super.toString() + '=' + sb;
        }
    }
}
