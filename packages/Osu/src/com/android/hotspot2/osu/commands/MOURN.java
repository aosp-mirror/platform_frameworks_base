package com.android.hotspot2.osu.commands;

/*
<xsd:element name="uploadMO" maxOccurs="unbounded">
    <xsd:annotation>
        <xsd:documentation>Command to mobile to upload the MO named in the moURN attribute to the SPP server.</xsd:documentation>
    </xsd:annotation>
    <xsd:complexType>
        <xsd:attribute ref="moURN"/>
    </xsd:complexType>
</xsd:element>
 */

import com.android.hotspot2.omadm.XMLNode;

public class MOURN implements OSUCommandData {
    private final String mURN;

    public MOURN(XMLNode root) {
        mURN = root.getAttributeValue("spp:moURN");
    }

    public String getURN() {
        return mURN;
    }

    @Override
    public String toString() {
        return "MOURN{" +
                "URN='" + mURN + '\'' +
                '}';
    }
}
