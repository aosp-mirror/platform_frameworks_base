package com.android.hotspot2.omadm;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class MOTree {
    public static final String MgmtTreeTag = "MgmtTree";

    public static final String NodeTag = "Node";
    public static final String NodeNameTag = "NodeName";
    public static final String PathTag = "Path";
    public static final String ValueTag = "Value";
    public static final String RTPropTag = "RTProperties";
    public static final String TypeTag = "Type";
    public static final String DDFNameTag = "DDFName";

    private final String mUrn;
    private final String mDtdRev;
    private final OMAConstructed mRoot;

    public MOTree(XMLNode node, String urn) throws IOException, SAXException {
        Iterator<XMLNode> children = node.getChildren().iterator();

        String dtdRev = null;

        while (children.hasNext()) {
            XMLNode child = children.next();
            if (child.getTag().equals(OMAConstants.SyncMLVersionTag)) {
                dtdRev = child.getText();
                children.remove();
                break;
            }
        }

        mUrn = urn;
        mDtdRev = dtdRev;

        mRoot = new MgmtTreeRoot(node, dtdRev);

        for (XMLNode child : node.getChildren()) {
            buildNode(mRoot, child);
        }
    }

    public MOTree(String urn, String rev, OMAConstructed root) throws IOException {
        mUrn = urn;
        mDtdRev = rev;
        mRoot = root;
    }

    public static MOTree buildMgmtTree(String urn, String rev, OMAConstructed root)
            throws IOException {
        OMAConstructed realRoot;
        switch (urn) {
            case OMAConstants.PPS_URN:
            case OMAConstants.DevInfoURN:
            case OMAConstants.DevDetailURN:
            case OMAConstants.DevDetailXURN:
                realRoot = new OMAConstructed(null, MgmtTreeTag, urn, "xmlns", OMAConstants.SyncML);
                realRoot.addChild(root);
                return new MOTree(urn, rev, realRoot);
            default:
                return new MOTree(urn, rev, root);
        }
    }

    public static boolean hasMgmtTreeTag(String text) {
        for (int n = 0; n < text.length(); n++) {
            char ch = text.charAt(n);
            if (ch > ' ') {
                return text.regionMatches(true, n, '<' + MgmtTreeTag + '>',
                        0, MgmtTreeTag.length() + 2);
            }
        }
        return false;
    }

    private static class NodeData {
        private final String mName;
        private String mPath;
        private String mValue;

        private NodeData(String name) {
            mName = name;
        }

        private void setPath(String path) {
            mPath = path;
        }

        private void setValue(String value) {
            mValue = value;
        }

        public String getName() {
            return mName;
        }

        public String getPath() {
            return mPath;
        }

        public String getValue() {
            return mValue;
        }
    }

    private static void buildNode(OMANode parent, XMLNode node) throws IOException {
        if (!node.getTag().equals(NodeTag))
            throw new IOException("Node is a '" + node.getTag() + "' instead of a 'Node'");

        Map<String, XMLNode> checkMap = new HashMap<>(3);
        String context = null;
        List<NodeData> values = new ArrayList<>();
        List<XMLNode> children = new ArrayList<>();

        NodeData curValue = null;

        for (XMLNode child : node.getChildren()) {
            XMLNode old = checkMap.put(child.getTag(), child);

            switch (child.getTag()) {
                case NodeNameTag:
                    if (curValue != null)
                        throw new IOException(NodeNameTag + " not expected");
                    curValue = new NodeData(child.getText());

                    break;
                case PathTag:
                    if (curValue == null || curValue.getPath() != null)
                        throw new IOException(PathTag + " not expected");
                    curValue.setPath(child.getText());

                    break;
                case ValueTag:
                    if (!children.isEmpty())
                        throw new IOException(ValueTag + " in constructed node");
                    if (curValue == null || curValue.getValue() != null)
                        throw new IOException(ValueTag + " not expected");
                    curValue.setValue(child.getText());
                    values.add(curValue);
                    curValue = null;

                    break;
                case RTPropTag:
                    if (old != null)
                        throw new IOException("Duplicate " + RTPropTag);
                    XMLNode typeNode = getNextNode(child, TypeTag);
                    XMLNode ddfName = getNextNode(typeNode, DDFNameTag);
                    context = ddfName.getText();
                    if (context == null)
                        throw new IOException("No text in " + DDFNameTag);

                    break;
                case NodeTag:
                    if (!values.isEmpty())
                        throw new IOException("Scalar node " + node.getText() + " has Node child");
                    children.add(child);

                    break;
            }
        }

        if (values.isEmpty()) {
            if (curValue == null)
                throw new IOException("Missing name");

            OMANode subNode = parent.addChild(curValue.getName(),
                    context, null, curValue.getPath());

            for (XMLNode child : children) {
                buildNode(subNode, child);
            }
        } else {
            if (!children.isEmpty())
                throw new IOException("Got both sub nodes and value(s)");

            for (NodeData nodeData : values) {
                parent.addChild(nodeData.getName(), context,
                        nodeData.getValue(), nodeData.getPath());
            }
        }
    }

    private static XMLNode getNextNode(XMLNode node, String tag) throws IOException {
        if (node == null)
            throw new IOException("No node for " + tag);
        if (node.getChildren().size() != 1)
            throw new IOException("Expected " + node.getTag() + " to have exactly one child");
        XMLNode child = node.getChildren().iterator().next();
        if (!child.getTag().equals(tag))
            throw new IOException("Expected " + node.getTag() + " to have child '" + tag +
                    "' instead of '" + child.getTag() + "'");
        return child;
    }

    public String getUrn() {
        return mUrn;
    }

    public String getDtdRev() {
        return mDtdRev;
    }

    public OMAConstructed getRoot() {
        return mRoot;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("MO Tree v").append(mDtdRev).append(", urn ").append(mUrn).append(")\n");
        sb.append(mRoot);

        return sb.toString();
    }

    public void marshal(OutputStream out) throws IOException {
        out.write("tree ".getBytes(StandardCharsets.UTF_8));
        OMAConstants.serializeString(mDtdRev, out);
        out.write(String.format("(%s)\n", mUrn).getBytes(StandardCharsets.UTF_8));
        mRoot.marshal(out, 0);
    }

    public static MOTree unmarshal(InputStream in) throws IOException {
        boolean strip = true;
        StringBuilder tree = new StringBuilder();
        for (; ; ) {
            int octet = in.read();
            if (octet < 0) {
                return null;
            } else if (octet > ' ') {
                tree.append((char) octet);
                strip = false;
            } else if (!strip) {
                break;
            }
        }
        if (!tree.toString().equals("tree")) {
            throw new IOException("Not a tree: " + tree);
        }

        String version = OMAConstants.deserializeString(in);
        int next = in.read();
        if (next != '(') {
            throw new IOException("Expected URN in tree definition");
        }
        String urn = OMAConstants.readURN(in);

        OMAConstructed root = OMANode.unmarshal(in);

        return new MOTree(urn, version, root);
    }

    public String toXml() {
        StringBuilder sb = new StringBuilder();
        mRoot.toXml(sb);
        return sb.toString();
    }
}
