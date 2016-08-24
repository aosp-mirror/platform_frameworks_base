package com.android.hotspot2.omadm;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

public class OMAConstructed extends OMANode {
    private final MultiValueMap<OMANode> mChildren;

    public OMAConstructed(OMAConstructed parent, String name, String context, String... avps) {
        this(parent, name, context, new MultiValueMap<OMANode>(), buildAttributes(avps));
    }

    protected OMAConstructed(OMAConstructed parent, String name, String context,
                             MultiValueMap<OMANode> children, Map<String, String> avps) {
        super(parent, name, context, avps);
        mChildren = children;
    }

    @Override
    public OMANode addChild(String name, String context, String value, String pathString)
            throws IOException {
        if (pathString == null) {
            OMANode child = value != null ?
                    new OMAScalar(this, name, context, value) :
                    new OMAConstructed(this, name, context);
            mChildren.put(name, child);
            return child;
        } else {
            OMANode target = this;
            while (target.getParent() != null)
                target = target.getParent();

            for (String element : pathString.split("/")) {
                target = target.getChild(element);
                if (target == null)
                    throw new IOException("No child node '" + element + "' in " + getPathString());
                else if (target.isLeaf())
                    throw new IOException("Cannot add child to leaf node: " + getPathString());
            }
            return target.addChild(name, context, value, null);
        }
    }

    @Override
    public OMAConstructed reparent(OMAConstructed parent) {
        return new OMAConstructed(parent, getName(), getContext(), mChildren, getAttributes());
    }

    public void addChild(OMANode child) {
        mChildren.put(child.getName(), child.reparent(this));
    }

    public String getScalarValue(Iterator<String> path) throws OMAException {
        if (!path.hasNext()) {
            throw new OMAException("Path too short for " + getPathString());
        }
        String tag = path.next();
        OMANode child = mChildren.get(tag);
        if (child != null) {
            return child.getScalarValue(path);
        } else {
            return null;
        }
    }

    @Override
    public OMANode getListValue(Iterator<String> path) throws OMAException {
        if (!path.hasNext()) {
            return null;
        }
        String tag = path.next();
        OMANode child;
        if (tag.equals("?")) {
            child = mChildren.getSingletonValue();
        } else {
            child = mChildren.get(tag);
        }

        if (child == null) {
            return null;
        } else if (path.hasNext()) {
            return child.getListValue(path);
        } else {
            return child;
        }
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    @Override
    public Collection<OMANode> getChildren() {
        return Collections.unmodifiableCollection(mChildren.values());
    }

    public OMANode getChild(String name) {
        return mChildren.get(name);
    }

    public OMANode replaceNode(OMANode oldNode, OMANode newNode) {
        return mChildren.replace(oldNode.getName(), oldNode, newNode);
    }

    public OMANode removeNode(String key, OMANode node) {
        if (key.equals("?")) {
            return mChildren.remove(node);
        } else {
            return mChildren.remove(key, node);
        }
    }

    @Override
    public String getValue() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void toString(StringBuilder sb, int level) {
        sb.append(getPathString());
        if (getContext() != null) {
            sb.append(" (").append(getContext()).append(')');
        }
        sb.append('\n');

        for (OMANode node : mChildren.values()) {
            node.toString(sb, level + 1);
        }
    }

    @Override
    public void marshal(OutputStream out, int level) throws IOException {
        OMAConstants.indent(level, out);
        OMAConstants.serializeString(getName(), out);
        if (getContext() != null) {
            out.write(String.format("(%s)", getContext()).getBytes(StandardCharsets.UTF_8));
        }
        out.write(new byte[]{'+', '\n'});

        for (OMANode child : mChildren.values()) {
            child.marshal(out, level + 1);
        }
        OMAConstants.indent(level, out);
        out.write(".\n".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void fillPayload(StringBuilder sb) {
        if (getContext() != null) {
            sb.append('<').append(MOTree.RTPropTag).append(">\n");
            sb.append('<').append(MOTree.TypeTag).append(">\n");
            sb.append('<').append(MOTree.DDFNameTag).append(">");
            sb.append(getContext());
            sb.append("</").append(MOTree.DDFNameTag).append(">\n");
            sb.append("</").append(MOTree.TypeTag).append(">\n");
            sb.append("</").append(MOTree.RTPropTag).append(">\n");
        }

        for (OMANode child : getChildren()) {
            child.toXml(sb);
        }
    }
}
