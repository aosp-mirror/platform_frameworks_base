package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.util.Collection;

public abstract class Asn1Object {
    private final int mTag;
    private final Asn1Class mClass;
    private final boolean mConstructed;
    private final int mLength;
    private final ByteBuffer mPayload;

    protected Asn1Object(int tag, Asn1Class asn1Class, boolean constructed, int length) {
        this(tag, asn1Class, constructed, length, null);
    }

    protected Asn1Object(int tag, Asn1Class asn1Class, boolean constructed,
                         int length, ByteBuffer payload) {
        mTag = tag;
        mClass = asn1Class;
        mConstructed = constructed;
        mLength = length;
        mPayload = payload != null ? payload.duplicate() : null;
    }

    public int getTag() {
        return mTag;
    }

    public Asn1Class getAsn1Class() {
        return mClass;
    }

    public boolean isConstructed() {
        return mConstructed;
    }

    public boolean isIndefiniteLength() {
        return mLength == Asn1Decoder.IndefiniteLength;
    }

    public int getLength() {
        return mLength;
    }

    public ByteBuffer getPayload() {
        return mPayload != null ? mPayload.duplicate() : null;
    }

    protected ByteBuffer getPayload(int position) {
        if (mPayload == null) {
            return null;
        }
        ByteBuffer encoding = mPayload.duplicate();
        encoding.position(position);
        return encoding;
    }

    protected void setEndOfData(int position) {
        mPayload.limit(position);
    }

    protected int getEndOfData() {
        return mPayload.limit();
    }

    public boolean matches(Asn1ID id) {
        return mTag == id.getTag() && mClass == id.getAsn1Class();
    }

    public String toSimpleString() {
        Asn1Tag tag = mClass == Asn1Class.Universal ? Asn1Decoder.mapTag(mTag) : null;
        if (tag != null) {
            return tag.name();
        } else if (mClass == Asn1Class.Universal) {
            return String.format("[%d]", mTag);
        } else {
            return String.format("[%s %d]", mClass, mTag);
        }
    }

    public abstract Collection<Asn1Object> getChildren();

    @Override
    public String toString() {
        return toSimpleString();
    }
}
