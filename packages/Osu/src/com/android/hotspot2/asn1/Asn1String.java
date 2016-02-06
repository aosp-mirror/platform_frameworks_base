package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public class Asn1String extends Asn1Object {
    private final String mString;

    public Asn1String(int tag, Asn1Class asn1Class, int length, ByteBuffer data) {
        super(tag, asn1Class, false, length);

        byte[] octets = new byte[length];
        data.get(octets);
        Charset charset = tag == Asn1Decoder.TAG_UTF8String
                ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        mString = new String(octets, charset);
    }

    public String getString() {
        return mString;
    }

    @Override
    public Collection<Asn1Object> getChildren() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return super.toString() + "='" + mString + '\'';
    }
}
