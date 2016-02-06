package com.android.hotspot2.omadm;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

public class OMAScalar extends OMANode {
    private final String mValue;

    public OMAScalar(OMAConstructed parent, String name, String context, String value,
                     String ... avps) {
        this(parent, name, context, value, buildAttributes(avps));
    }

    public OMAScalar(OMAConstructed parent, String name, String context, String value,
                     Map<String, String> avps) {
        super(parent, name, context, avps);
        mValue = value;
    }

    @Override
    public OMAScalar reparent(OMAConstructed parent) {
        return new OMAScalar(parent, getName(), getContext(), mValue, getAttributes());
    }

    public String getScalarValue(Iterator<String> path) throws OMAException {
        return mValue;
    }

    @Override
    public OMANode getListValue(Iterator<String> path) throws OMAException {
        throw new OMAException("Scalar encountered in list path: " + getPathString());
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    public Collection<OMANode> getChildren() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getValue() {
        return mValue;
    }

    @Override
    public OMANode getChild(String name) throws OMAException {
        throw new OMAException("'" + getName() + "' is a scalar node");
    }

    @Override
    public OMANode addChild(String name, String context, String value, String path)
            throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toString(StringBuilder sb, int level) {
        sb.append(getPathString()).append('=').append(mValue);
        if (getContext() != null) {
            sb.append(" (").append(getContext()).append(')');
        }
        sb.append('\n');
    }

    @Override
    public void marshal(OutputStream out, int level) throws IOException {
        OMAConstants.indent(level, out);
        OMAConstants.serializeString(getName(), out);
        out.write((byte) '=');
        OMAConstants.serializeString(getValue(), out);
        out.write((byte) '\n');
    }

    @Override
    public void fillPayload(StringBuilder sb) {
        sb.append('<').append(MOTree.ValueTag).append('>');
        sb.append(mValue);
        sb.append("</").append(MOTree.ValueTag).append(">\n");
    }
}
