package com.android.hotspot2.omadm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public abstract class OMANode {
    private final OMAConstructed mParent;
    private final String mName;
    private final String mContext;
    private final Map<String, String> mAttributes;

    protected OMANode(OMAConstructed parent, String name, String context, Map<String, String> avps) {
        mParent = parent;
        mName = name;
        mContext = context;
        mAttributes = avps;
    }

    protected static Map<String, String> buildAttributes(String[] avps) {
        if (avps == null) {
            return null;
        }
        Map<String, String> attributes = new HashMap<>();
        for (int n = 0; n < avps.length; n += 2) {
            attributes.put(avps[n], avps[n + 1]);
        }
        return attributes;
    }

    protected Map<String, String> getAttributes() {
        return mAttributes;
    }

    public OMAConstructed getParent() {
        return mParent;
    }

    public String getName() {
        return mName;
    }

    public String getContext() {
        return mContext;
    }

    public List<String> getPath() {
        LinkedList<String> path = new LinkedList<>();
        for (OMANode node = this; node != null; node = node.getParent()) {
            path.addFirst(node.getName());
        }
        return path;
    }

    public String getPathString() {
        StringBuilder sb = new StringBuilder();
        for (String element : getPath()) {
            sb.append('/').append(element);
        }
        return sb.toString();
    }

    public abstract OMANode reparent(OMAConstructed parent);

    public abstract String getScalarValue(Iterator<String> path) throws OMAException;

    public abstract OMANode getListValue(Iterator<String> path) throws OMAException;

    public abstract boolean isLeaf();

    public abstract Collection<OMANode> getChildren();

    public abstract OMANode getChild(String name) throws OMAException;

    public abstract String getValue();

    public abstract OMANode addChild(String name, String context, String value, String path)
            throws IOException;

    public abstract void marshal(OutputStream out, int level) throws IOException;

    public abstract void toString(StringBuilder sb, int level);

    public abstract void fillPayload(StringBuilder sb);

    public void toXml(StringBuilder sb) {
        sb.append('<').append(MOTree.NodeTag);
        if (mAttributes != null && !mAttributes.isEmpty()) {
            for (Map.Entry<String, String> avp : mAttributes.entrySet()) {
                sb.append(' ').append(avp.getKey()).append("=\"").append(avp.getValue()).append('"');
            }
        }
        sb.append(">\n");

        sb.append('<').append(MOTree.NodeNameTag).append('>');
        sb.append(getName());
        sb.append("</").append(MOTree.NodeNameTag).append(">\n");

        fillPayload(sb);

        sb.append("</").append(MOTree.NodeTag).append(">\n");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    public static OMAConstructed unmarshal(InputStream in) throws IOException {
        OMANode node = buildNode(in, null);
        if (node == null || node.isLeaf()) {
            throw new IOException("Bad OMA tree");
        }
        unmarshal(in, (OMAConstructed) node);
        return (OMAConstructed) node;
    }

    private static void unmarshal(InputStream in, OMAConstructed parent) throws IOException {
        for (; ; ) {
            OMANode node = buildNode(in, parent);
            if (node == null) {
                return;
            } else if (!node.isLeaf()) {
                unmarshal(in, (OMAConstructed) node);
            }
        }
    }

    private static OMANode buildNode(InputStream in, OMAConstructed parent) throws IOException {
        String name = OMAConstants.deserializeString(in);
        if (name == null) {
            return null;
        }

        String urn = null;
        int next = in.read();
        if (next == '(') {
            urn = OMAConstants.readURN(in);
            next = in.read();
        }

        if (next == '=') {
            String value = OMAConstants.deserializeString(in);
            return parent.addChild(name, urn, value, null);
        } else if (next == '+') {
            if (parent != null) {
                return parent.addChild(name, urn, null, null);
            } else {
                return new OMAConstructed(null, name, urn);
            }
        } else {
            throw new IOException("Parse error: expected = or + after node name");
        }
    }
}
