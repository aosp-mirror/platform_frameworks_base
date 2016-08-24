package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.MOTree;
import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.XMLNode;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public class SOAPBuilder {
    private static final String EnvelopeTag = "s12:Envelope";
    private static final String BodyTag = "s12:Body";

    private static final Map<String, String> sEnvelopeAttributes = new HashMap<>(2);
    private static final Map<RequestReason, String> sRequestReasons =
            new EnumMap<>(RequestReason.class);

    static {
        sEnvelopeAttributes.put("xmlns:s12", "http://www.w3.org/2003/05/soap-envelope");
        sEnvelopeAttributes.put("xmlns:spp",
                "http://www.wi-fi.org/specifications/hotspot2dot0/v1.0/spp");

        sRequestReasons.put(RequestReason.SubRegistration, "Subscription registration");
        sRequestReasons.put(RequestReason.SubProvisioning, "Subscription provisioning");
        sRequestReasons.put(RequestReason.SubRemediation, "Subscription remediation");
        sRequestReasons.put(RequestReason.InputComplete, "User input completed");
        sRequestReasons.put(RequestReason.NoClientCert, "No acceptable client certificate");
        sRequestReasons.put(RequestReason.CertEnrollmentComplete,
                "Certificate enrollment completed");
        sRequestReasons.put(RequestReason.CertEnrollmentFailed, "Certificate enrollment failed");
        sRequestReasons.put(RequestReason.SubMetaDataUpdate, "Subscription metadata update");
        sRequestReasons.put(RequestReason.PolicyUpdate, "Policy update");
        sRequestReasons.put(RequestReason.NextCommand, "Retrieve next command");
        sRequestReasons.put(RequestReason.MOUpload, "MO upload");
        sRequestReasons.put(RequestReason.Unspecified, "Unspecified");
    }

    public static String buildPostDevDataResponse(RequestReason reason, String sessionID,
                                                  String redirURI, MOTree... mos) {
        XMLNode envelope = buildEnvelope();
        buildSppPostDevData(envelope.getChildren().get(0), sessionID, reason, redirURI, mos);
        return envelope.toString();
    }

    public static String buildUpdateResponse(String sessionID, OSUError error) {
        XMLNode envelope = buildEnvelope();
        buildSppUpdateResponse(envelope.getChildren().get(0), sessionID, error);
        return envelope.toString();
    }

    private static XMLNode buildEnvelope() {
        XMLNode envelope = new XMLNode(null, EnvelopeTag, sEnvelopeAttributes);
        envelope.addChild(new XMLNode(envelope, BodyTag, (Map<String, String>) null));
        return envelope;
    }

    private static XMLNode buildSppPostDevData(XMLNode parent, String sessionID,
                                               RequestReason reason, String redirURI,
                                               MOTree... mos) {
        Map<String, String> pddAttributes = new HashMap<>();
        pddAttributes.put(OMAConstants.TAG_Version, OMAConstants.MOVersion);
        pddAttributes.put("requestReason", sRequestReasons.get(reason));
        if (sessionID != null) {
            pddAttributes.put(OMAConstants.TAG_SessionID, sessionID);
        }
        if (redirURI != null) {
            pddAttributes.put("redirectURI", redirURI);
        }

        XMLNode pddNode = new XMLNode(parent, OMAConstants.TAG_PostDevData, pddAttributes);

        XMLNode vNode = new XMLNode(pddNode, OMAConstants.TAG_SupportedVersions,
                (HashMap<String, String>) null);
        vNode.setText("1.0");
        pddNode.addChild(vNode);

        XMLNode moNode = new XMLNode(pddNode, OMAConstants.TAG_SupportedMOs,
                (HashMap<String, String>) null);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String urn : OMAConstants.SupportedMO_URNs) {
            if (first) {
                first = false;
            } else {
                sb.append(' ');
            }
            sb.append(urn);
        }
        moNode.setText(sb.toString());
        pddNode.addChild(moNode);

        if (mos != null) {
            for (MOTree moTree : mos) {
                Map<String, String> map = null;
                if (moTree.getUrn() != null) {
                    map = new HashMap<>(1);
                    map.put(OMAConstants.SppMOAttribute, moTree.getUrn());
                }
                moNode = new XMLNode(pddNode, OMAConstants.TAG_MOContainer, map);
                moNode.setText(moTree.toXml());
                pddNode.addChild(moNode);
            }
        }

        parent.addChild(pddNode);
        return pddNode;
    }

    private static XMLNode buildSppUpdateResponse(XMLNode parent, String sessionID,
                                                  OSUError error) {
        Map<String, String> urAttributes = new HashMap<>();
        urAttributes.put(OMAConstants.TAG_Version, OMAConstants.MOVersion);
        if (sessionID != null) {
            urAttributes.put(OMAConstants.TAG_SessionID, sessionID);
        }
        if (error == null) {
            urAttributes.put(OMAConstants.TAG_Status, OMAConstants.mapStatus(OSUStatus.OK));
        } else {
            urAttributes.put(OMAConstants.TAG_Status, OMAConstants.mapStatus(OSUStatus.Error));
        }

        XMLNode urNode = new XMLNode(parent, OMAConstants.TAG_UpdateResponse, urAttributes);

        if (error != null) {
            Map<String, String> errorAttributes = new HashMap<>();
            errorAttributes.put("errorCode", OMAConstants.mapError(error));
            XMLNode errorNode = new XMLNode(urNode, OMAConstants.TAG_Error, errorAttributes);
            urNode.addChild(errorNode);
        }

        parent.addChild(urNode);
        return urNode;
    }

    /*
    <xsd:element name="sppUpdateResponse">
		<xsd:annotation>
			<xsd:documentation>SOAP method used by SPP client to confirm installation of MO addition or update.</xsd:documentation>
		</xsd:annotation>
		<xsd:complexType>
			<xsd:sequence>
				<xsd:element ref="sppError" minOccurs="0"/>
				<xsd:any namespace="##other" maxOccurs="unbounded" minOccurs="0"/>
			</xsd:sequence>
			<xsd:attribute ref="sppVersion" use="required"/>
			<xsd:attribute ref="sppStatus" use="required"/>
			<xsd:attribute ref="sessionID" use="required"/>
			<xsd:anyAttribute namespace="##other"/>
		</xsd:complexType>
	</xsd:element>

    <xsd:element name="sppError">
		<xsd:annotation>
			<xsd:documentation>Error response.</xsd:documentation>
		</xsd:annotation>
		<xsd:complexType>
			<xsd:attribute name="errorCode" use="required">
				<xsd:simpleType>
					<xsd:restriction base="xsd:string">
						<xsd:enumeration value="SPP version not supported"/>
						<xsd:enumeration value="One or more mandatory MOs not supported"/>
						<xsd:enumeration value="Credentials cannot be provisioned at this time"/>
						<xsd:enumeration value="Remediation cannot be completed at this time"/>
						<xsd:enumeration value="Provisioning cannot be completed at this time"/>
						<xsd:enumeration value="Continue to use existing certificate"/>
						<xsd:enumeration value="Cookie invalid"/>
						<xsd:enumeration value="No corresponding web-browser-connection Session ID"/>
						<xsd:enumeration value="Permission denied"/>
						<xsd:enumeration value="Command failed"/>
						<xsd:enumeration value="MO addition or update failed"/>
						<xsd:enumeration value="Device full"/>
						<xsd:enumeration value="Bad management tree URI"/>
						<xsd:enumeration value="Requested entity too large"/>
						<xsd:enumeration value="Command not allowed"/>
						<xsd:enumeration value="Command not executed due to user"/>
						<xsd:enumeration value="Not found"/>
						<xsd:enumeration value="Other"/>
					</xsd:restriction>
				</xsd:simpleType>
			</xsd:attribute>
			<xsd:anyAttribute namespace="##other"/>
		</xsd:complexType>
	</xsd:element>


     */
}
