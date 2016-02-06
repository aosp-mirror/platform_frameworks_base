package com.android.hotspot2.omadm;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class XMLNode {
    private final String mTag;
    private final Map<String, NodeAttribute> mAttributes;
    private final List<XMLNode> mChildren;
    private final XMLNode mParent;
    private MOTree mMO;
    private StringBuilder mTextBuilder;
    private String mText;

    private static final String XML_SPECIAL_CHARS = "\"'<>&";
    private static final Set<Character> XML_SPECIAL = new HashSet<>();
    private static final String CDATA_OPEN = "<![CDATA[";
    private static final String CDATA_CLOSE = "]]>";

    static {
        for (int n = 0; n < XML_SPECIAL_CHARS.length(); n++) {
            XML_SPECIAL.add(XML_SPECIAL_CHARS.charAt(n));
        }
    }

    public XMLNode(XMLNode parent, String tag, Attributes attributes) throws SAXException {
        mTag = tag;

        mAttributes = new HashMap<>();

        if (attributes.getLength() > 0) {
            for (int n = 0; n < attributes.getLength(); n++)
                mAttributes.put(attributes.getQName(n), new NodeAttribute(attributes.getQName(n),
                        attributes.getType(n), attributes.getValue(n)));
        }

        mParent = parent;
        mChildren = new ArrayList<>();

        mTextBuilder = new StringBuilder();
    }

    public XMLNode(XMLNode parent, String tag, Map<String, String> attributes) {
        mTag = tag;

        mAttributes = new HashMap<>(attributes == null ? 0 : attributes.size());

        if (attributes != null) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                mAttributes.put(entry.getKey(),
                        new NodeAttribute(entry.getKey(), "", entry.getValue()));
            }
        }

        mParent = parent;
        mChildren = new ArrayList<>();

        mTextBuilder = new StringBuilder();
    }

    public void setText(String text) {
        mText = text;
        mTextBuilder = null;
    }

    public void addText(char[] chs, int start, int length) {
        String s = new String(chs, start, length);
        String trimmed = s.trim();
        if (trimmed.isEmpty())
            return;

        if (s.charAt(0) != trimmed.charAt(0))
            mTextBuilder.append(' ');
        mTextBuilder.append(trimmed);
        if (s.charAt(s.length() - 1) != trimmed.charAt(trimmed.length() - 1))
            mTextBuilder.append(' ');
    }

    public void addChild(XMLNode child) {
        mChildren.add(child);
    }

    public void close() throws IOException, SAXException {
        String text = mTextBuilder.toString().trim();
        StringBuilder filtered = new StringBuilder(text.length());
        for (int n = 0; n < text.length(); n++) {
            char ch = text.charAt(n);
            if (ch >= ' ')
                filtered.append(ch);
        }

        mText = filtered.toString();
        mTextBuilder = null;

        if (MOTree.hasMgmtTreeTag(mText)) {
            try {
                NodeAttribute urn = mAttributes.get(OMAConstants.SppMOAttribute);
                OMAParser omaParser = new OMAParser();
                mMO = omaParser.parse(mText, urn != null ? urn.getValue() : null);
            } catch (SAXException | IOException e) {
                mMO = null;
            }
        }
    }

    public String getTag() {
        return mTag;
    }

    public String getNameSpace() throws OMAException {
        String[] nsn = mTag.split(":");
        if (nsn.length != 2) {
            throw new OMAException("Non-namespaced tag: '" + mTag + "'");
        }
        return nsn[0];
    }

    public String getStrippedTag() throws OMAException {
        String[] nsn = mTag.split(":");
        if (nsn.length != 2) {
            throw new OMAException("Non-namespaced tag: '" + mTag + "'");
        }
        return nsn[1].toLowerCase();
    }

    public XMLNode getSoleChild() throws OMAException {
        if (mChildren.size() != 1) {
            throw new OMAException("Expected exactly one child to " + mTag);
        }
        return mChildren.get(0);
    }

    public XMLNode getParent() {
        return mParent;
    }

    public String getText() {
        return mText;
    }

    public Map<String, NodeAttribute> getAttributes() {
        return Collections.unmodifiableMap(mAttributes);
    }

    public Map<String, String> getTextualAttributes() {
        Map<String, String> map = new HashMap<>(mAttributes.size());
        for (Map.Entry<String, NodeAttribute> entry : mAttributes.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getValue());
        }
        return map;
    }

    public String getAttributeValue(String name) {
        NodeAttribute nodeAttribute = mAttributes.get(name);
        return nodeAttribute != null ? nodeAttribute.getValue() : null;
    }

    public List<XMLNode> getChildren() {
        return mChildren;
    }

    public MOTree getMOTree() {
        return mMO;
    }

    private void toString(char[] indent, StringBuilder sb) {
        Arrays.fill(indent, ' ');

        sb.append(indent).append('<').append(mTag);
        for (Map.Entry<String, NodeAttribute> entry : mAttributes.entrySet()) {
            sb.append(' ').append(entry.getKey()).append("='")
                    .append(entry.getValue().getValue()).append('\'');
        }

        if (mText != null && !mText.isEmpty()) {
            sb.append('>').append(escapeCdata(mText)).append("</").append(mTag).append(">\n");
        } else if (mChildren.isEmpty()) {
            sb.append("/>\n");
        } else {
            sb.append(">\n");
            char[] subIndent = Arrays.copyOf(indent, indent.length + 2);
            for (XMLNode child : mChildren) {
                child.toString(subIndent, sb);
            }
            sb.append(indent).append("</").append(mTag).append(">\n");
        }
    }

    private static String escapeCdata(String text) {
        if (!escapable(text)) {
            return text;
        }

        // Any appearance of ]]> in the text must be split into "]]" | "]]>" | <![CDATA[ | ">"
        // i.e. "split the sequence by putting a close CDATA and a new open CDATA before the '>'
        StringBuilder sb = new StringBuilder();
        sb.append(CDATA_OPEN);
        int start = 0;
        for (; ; ) {
            int etoken = text.indexOf(CDATA_CLOSE);
            if (etoken >= 0) {
                sb.append(text.substring(start, etoken + 2)).append(CDATA_CLOSE).append(CDATA_OPEN);
                start = etoken + 2;
            } else {
                if (start < text.length() - 1) {
                    sb.append(text.substring(start));
                }
                break;
            }
        }
        sb.append(CDATA_CLOSE);
        return sb.toString();
    }

    private static boolean escapable(String s) {
        for (int n = 0; n < s.length(); n++) {
            if (XML_SPECIAL.contains(s.charAt(n))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(new char[0], sb);
        return sb.toString();
    }
}
