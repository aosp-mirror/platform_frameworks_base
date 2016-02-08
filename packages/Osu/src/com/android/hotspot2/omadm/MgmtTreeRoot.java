package com.android.hotspot2.omadm;

import java.util.Map;

public class MgmtTreeRoot extends OMAConstructed {
    private final String mDtdRev;

    public MgmtTreeRoot(XMLNode node, String dtdRev) {
        super(null, MOTree.MgmtTreeTag, null, new MultiValueMap<OMANode>(),
                node.getTextualAttributes());
        mDtdRev = dtdRev;
    }

    public MgmtTreeRoot(String dtdRev) {
        super(null, MOTree.MgmtTreeTag, null, "xmlns", OMAConstants.SyncML);
        mDtdRev = dtdRev;
    }

    @Override
    public void toXml(StringBuilder sb) {
        sb.append('<').append(MOTree.MgmtTreeTag);
        if (getAttributes() != null && !getAttributes().isEmpty()) {
            for (Map.Entry<String, String> avp : getAttributes().entrySet()) {
                sb.append(' ').append(avp.getKey()).append("=\"")
                        .append(avp.getValue()).append('"');
            }
        }
        sb.append(">\n");

        sb.append('<').append(OMAConstants.SyncMLVersionTag).append('>').append(mDtdRev)
                .append("</").append(OMAConstants.SyncMLVersionTag).append(">\n");
        for (OMANode child : getChildren()) {
            child.toXml(sb);
        }
        sb.append("</").append(MOTree.MgmtTreeTag).append(">\n");
    }
}
