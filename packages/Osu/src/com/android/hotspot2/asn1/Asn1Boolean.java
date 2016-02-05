package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.util.Collection;

public class Asn1Boolean extends Asn1Object {
    private final boolean mBoolean;

    public Asn1Boolean(int tag, Asn1Class asn1Class, int length, ByteBuffer data)
            throws DecodeException {
        super(tag, asn1Class, false, length);
        if (length != 1) {
            throw new DecodeException("Boolean length != 1: " + length, data.position());
        }
        mBoolean = data.get() != 0;
    }

    public boolean getValue() {
        return mBoolean;
    }

    @Override
    public Collection<Asn1Object> getChildren() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return super.toString() + "=" + Boolean.toString(mBoolean);
    }
}
