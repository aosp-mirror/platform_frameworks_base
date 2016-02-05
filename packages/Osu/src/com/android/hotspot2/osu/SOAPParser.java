package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

public class SOAPParser {

    private static final String EnvelopeTag = "envelope";
    private static final String BodyTag = "body";

    private static final Map<String, ResponseFactory> sResponseMap = new HashMap<>();

    static {
        sResponseMap.put("spppostdevdataresponse", new ResponseFactory() {
            @Override
            public OSUResponse buildResponse(XMLNode root) throws OMAException {
                return new PostDevDataResponse(root);
            }
        });
        sResponseMap.put("sppexchangecomplete", new ResponseFactory() {
            @Override
            public OSUResponse buildResponse(XMLNode root) throws OMAException {
                return new ExchangeCompleteResponse(root);
            }
        });
        sResponseMap.put("getcertificate", new ResponseFactory() {
            @Override
            public OSUResponse buildResponse(XMLNode root) {
                return null;
            }
        });
        sResponseMap.put("spperror", new ResponseFactory() {
            @Override
            public OSUResponse buildResponse(XMLNode root) {
                return null;
            }
        });
    }

    private final XMLNode mResponseNode;

    public SOAPParser(InputStream in)
            throws ParserConfigurationException, SAXException, IOException {
        XMLNode root;

        try {
            XMLParser parser = new XMLParser(in);
            root = parser.getRoot();
        } finally {
            in.close();
        }

        String[] nsn = root.getTag().split(":");
        if (nsn.length > 2) {
            throw new OMAException("Bad root tag syntax: '" + root.getTag() + "'");
        } else if (!EnvelopeTag.equalsIgnoreCase(nsn[nsn.length - 1])) {
            throw new OMAException("Expected envelope: '" + root.getTag() + "'");
        }

        String bodyTag = nsn.length > 1 ? (nsn[0] + ":" + BodyTag) : BodyTag;
        XMLNode body = null;

        for (XMLNode child : root.getChildren()) {
            if (bodyTag.equalsIgnoreCase(child.getTag())) {
                body = child;
                break;
            }
        }

        if (body == null || body.getChildren().isEmpty()) {
            throw new OMAException("Missing SOAP body");
        }

        mResponseNode = body.getSoleChild();
    }

    public OSUResponse getResponse() throws OMAException {
        ResponseFactory responseFactory = sResponseMap.get(mResponseNode.getStrippedTag());
        if (responseFactory == null) {
            throw new OMAException("Unknown response type: '"
                    + mResponseNode.getStrippedTag() + "'");
        }
        return responseFactory.buildResponse(mResponseNode);
    }

    public XMLNode getResponseNode() {
        return mResponseNode;
    }


    /*
    <xsd:element name="sppPostDevDataResponse">
		<xsd:annotation>
			<xsd:documentation>SOAP method response from SPP server.</xsd:documentation>
		</xsd:annotation>
		<xsd:complexType>
			<xsd:choice>
				<xsd:element ref="sppError"/>
				<xsd:element name="exec">
					<xsd:annotation>
						<xsd:documentation>Receipt of this element by a mobile device causes the following command to be executed.</xsd:documentation>
					</xsd:annotation>
					<xsd:complexType>
						<xsd:choice>
							<xsd:element name="launchBrowserToURI" type="httpsURIType">
								<xsd:annotation>
									<xsd:documentation>When the mobile device receives this command, it launches its default browser to the URI contained in this element.  The URI must use HTTPS as the protocol and must contain an FQDN.</xsd:documentation>
								</xsd:annotation>
							</xsd:element>
							<xsd:element ref="getCertificate"/>
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
							<xsd:element name="uploadMO" maxOccurs="unbounded">
								<xsd:annotation>
									<xsd:documentation>Command to mobile to upload the MO named in the moURN attribute to the SPP server.</xsd:documentation>
								</xsd:annotation>
								<xsd:complexType>
									<xsd:attribute ref="moURN"/>
								</xsd:complexType>
							</xsd:element>
							<xsd:any namespace="##other" maxOccurs="unbounded" minOccurs="0">
								<xsd:annotation>
									<xsd:documentation>Element to allow the addition of new commands in the future.</xsd:documentation>
								</xsd:annotation>
							</xsd:any>
						</xsd:choice>
						<xsd:anyAttribute namespace="##other"/>
					</xsd:complexType>
				</xsd:element>
				<xsd:element name="addMO">
					<xsd:annotation>
						<xsd:documentation>This command causes an management object in the mobile devices management tree at the specified location to be added.  If there is already a management object at that location, the object is replaced.</xsd:documentation>
					</xsd:annotation>
					<xsd:complexType>
						<xsd:simpleContent>
							<xsd:extension base="xsd:string">
								<xsd:attribute ref="managementTreeURI"/>
								<xsd:attribute ref="moURN"/>
							</xsd:extension>
						</xsd:simpleContent>
					</xsd:complexType>
				</xsd:element>
				<xsd:element maxOccurs="unbounded" name="updateNode">
					<xsd:annotation>
						<xsd:documentation>This command causes the update of an interior node and its child nodes (if any) at the location specified in the management tree URI attribute.  The content of this element is the MO node XML.</xsd:documentation>
					</xsd:annotation>
					<xsd:complexType>
						<xsd:simpleContent>
							<xsd:extension base="xsd:string">
								<xsd:attribute ref="managementTreeURI"/>
							</xsd:extension>
						</xsd:simpleContent>
					</xsd:complexType>
				</xsd:element>
				<xsd:element name="noMOUpdate">
					<xsd:annotation>
						<xsd:documentation>This response is used when there is no command to be executed nor update of any MO required.</xsd:documentation>
					</xsd:annotation>
				</xsd:element>
				<xsd:any namespace="##other" minOccurs="0" maxOccurs="unbounded">
					<xsd:annotation>
						<xsd:documentation>For vendor-specific extensions or future needs.</xsd:documentation>
					</xsd:annotation>
				</xsd:any>
			</xsd:choice>
			<xsd:attribute ref="sppVersion" use="required"/>
			<xsd:attribute ref="sppStatus" use="required"/>
			<xsd:attribute ref="moreCommands" use="optional"/>
			<xsd:attribute ref="sessionID" use="required"/>
			<xsd:anyAttribute namespace="##other"/>
		</xsd:complexType>
	</xsd:element>
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
	<xsd:element name="sppExchangeComplete">
		<xsd:annotation>
			<xsd:documentation>SOAP method used by SPP server to end session.</xsd:documentation>
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
	<xsd:element name="getCertificate">
		<xsd:annotation>
			<xsd:documentation>Command to mobile to initiate certificate enrollment or re-enrollment and is a container for metadata to enable enrollment.</xsd:documentation>
		</xsd:annotation>
		<xsd:complexType>
			<xsd:sequence>
				<xsd:element name="enrollmentServerURI" type="httpsURIType">
					<xsd:annotation>
						<xsd:documentation>HTTPS URI of the server to be contacted to initiate certificate enrollment.  The URI must contain an FQDN.</xsd:documentation>
					</xsd:annotation>
				</xsd:element>
				<xsd:element name="estUserID" minOccurs="0">
					<xsd:annotation>
						<xsd:documentation>Temporary userid used by an EST client to authenticate to the EST server using HTTP Digest authentication.  This element must be used for initial certificate enrollment; its use is optional for certificate re-enrollment.</xsd:documentation>
					</xsd:annotation>
					<xsd:simpleType>
						<xsd:restriction base="xsd:string">
							<xsd:maxLength value="255"/>
						</xsd:restriction>
					</xsd:simpleType>
				</xsd:element>
				<xsd:element name="estPassword" minOccurs="0">
					<xsd:annotation>
						<xsd:documentation>Temporary password used by an EST client to authenticate to the EST server using HTTP Digest authentication.  This element must be used for initial certificate enrollment; its use is optional for certificate re-enrollment.</xsd:documentation>
					</xsd:annotation>
					<xsd:simpleType>
						<xsd:restriction base="xsd:base64Binary">
							<xsd:maxLength value="340"/>
						</xsd:restriction>
					</xsd:simpleType>
				</xsd:element>
				<xsd:any namespace="##other" minOccurs="0" maxOccurs="unbounded">
					<xsd:annotation>
						<xsd:documentation>For vendor-specific extensions or future needs.</xsd:documentation>
					</xsd:annotation>
				</xsd:any>
			</xsd:sequence>
			<xsd:attribute name="enrollmentProtocol" use="required">
				<xsd:simpleType>
					<xsd:restriction base="xsd:string">
						<xsd:enumeration value="EST"/>
						<xsd:enumeration value="Other"/>
					</xsd:restriction>
				</xsd:simpleType>
			</xsd:attribute>
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
