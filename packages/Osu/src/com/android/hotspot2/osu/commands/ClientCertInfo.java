package com.android.hotspot2.osu.commands;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;

import java.util.ArrayList;
import java.util.List;

/*
<xsd:element name="useClientCertTLS">
    <xsd:annotation>
        <xsd:documentation>Command to mobile to re-negotiate the TLS connection using a client certificate of the accepted type or Issuer to authenticate with the Subscription server.</xsd:documentation>
    </xsd:annotation>
    <xsd:complexType>
        <xsd:sequence>
            <xsd:element name="providerIssuerName" minOccurs="0"
                maxOccurs="unbounded">
                <xsd:complexType>
                    <xsd:attribute name="name" type="xsd:string">
                    <xsd:annotation>
                    <xsd:documentation>The issuer name of an acceptable provider-issued certificate.  The text of this element is formatted in accordance with the Issuer Name field in RFC-3280.  This element is present only when acceptProviderCerts is true.</xsd:documentation>
                    </xsd:annotation>
                    </xsd:attribute>
                    <xsd:anyAttribute namespace="##other"/>
                </xsd:complexType>
            </xsd:element>
            <xsd:any namespace="##other" minOccurs="0"
                maxOccurs="unbounded"/>
        </xsd:sequence>
        <xsd:attribute name="acceptMfgCerts" type="xsd:boolean"
            use="optional" default="false">
            <xsd:annotation>
                <xsd:documentation>When this boolean is true, IEEE 802.1ar manufacturing certificates are acceptable for mobile device authentication.</xsd:documentation>
            </xsd:annotation>
        </xsd:attribute>
        <xsd:attribute name="acceptProviderCerts" type="xsd:boolean"
            use="optional" default="true">
            <xsd:annotation>
                <xsd:documentation>When this boolean is true, X509v3 certificates issued by providers identified in the providerIssuerName child element(s) are acceptable for mobile device authentication.</xsd:documentation>
            </xsd:annotation>
        </xsd:attribute>
        <xsd:anyAttribute namespace="##other"/>
    </xsd:complexType>
</xsd:element>
 */

public class ClientCertInfo implements OSUCommandData {
    private final boolean mAcceptMfgCerts;
    private final boolean mAcceptProviderCerts;
    /*
     * The issuer name of an acceptable provider-issued certificate.
     * The text of this element is formatted in accordance with the Issuer Name field in RFC-3280.
     * This element is present only when acceptProviderCerts is true.
     */
    private final List<String> mIssuerNames;

    public ClientCertInfo(XMLNode commandNode) throws OMAException {
        mAcceptMfgCerts = Boolean.parseBoolean(commandNode.getAttributeValue("acceptMfgCerts"));
        mAcceptProviderCerts =
                Boolean.parseBoolean(commandNode.getAttributeValue("acceptProviderCerts"));

        if (mAcceptMfgCerts) {
            mIssuerNames = new ArrayList<>();
            for (XMLNode node : commandNode.getChildren()) {
                if (node.getStrippedTag().equals("providerIssuerName")) {
                    mIssuerNames.add(node.getAttributeValue("name"));
                }
            }
        } else {
            mIssuerNames = null;
        }
    }

    public boolean doesAcceptMfgCerts() {
        return mAcceptMfgCerts;
    }

    public boolean doesAcceptProviderCerts() {
        return mAcceptProviderCerts;
    }

    public List<String> getIssuerNames() {
        return mIssuerNames;
    }

    @Override
    public String toString() {
        return "ClientCertInfo{" +
                "acceptMfgCerts=" + mAcceptMfgCerts +
                ", acceptProviderCerts=" + mAcceptProviderCerts +
                ", issuerNames=" + mIssuerNames +
                '}';
    }
}
