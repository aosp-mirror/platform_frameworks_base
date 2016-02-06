package com.android.hotspot2.asn1;

public class Asn1ID {
    private final int mTag;
    private final Asn1Class mClass;

    public Asn1ID(int tag, Asn1Class asn1Class) {
        mTag = tag;
        mClass = asn1Class;
    }

    public int getTag() {
        return mTag;
    }

    public Asn1Class getAsn1Class() {
        return mClass;
    }
}
