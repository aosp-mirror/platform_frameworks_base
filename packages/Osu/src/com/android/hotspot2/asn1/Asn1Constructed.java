package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.util.*;

public class Asn1Constructed extends Asn1Object {
    private final int mTagPosition;
    private final List<Asn1Object> mChildren;

    public Asn1Constructed(int tag, Asn1Class asn1Class, int length,
                           ByteBuffer payload, int tagPosition) {
        super(tag, asn1Class, true, length, payload);
        mTagPosition = tagPosition;
        mChildren = new ArrayList<>();
    }

    public void addChild(Asn1Object object) {
        mChildren.add(object);
    }

    @Override
    public Collection<Asn1Object> getChildren() {
        return Collections.unmodifiableCollection(mChildren);
    }

    public ByteBuffer getEncoding() {
        return getPayload(mTagPosition);
    }

    private void toString(int level, StringBuilder sb) {
        sb.append(indent(level)).append(super.toString()).append(":\n");
        for (Asn1Object child : mChildren) {
            if (child.isConstructed()) {
                ((Asn1Constructed) child).toString(level + 1, sb);
            } else {
                sb.append(indent(level + 1)).append(child.toString()).append('\n');
            }
        }
    }

    public static String indent(int level) {
        char[] indent = new char[level * 2];
        Arrays.fill(indent, ' ');
        return new String(indent);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(0, sb);
        return sb.toString();
    }
}
