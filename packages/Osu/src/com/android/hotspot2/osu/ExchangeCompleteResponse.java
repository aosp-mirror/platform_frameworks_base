package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;

	/*
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
	 */

public class ExchangeCompleteResponse extends OSUResponse {
    public ExchangeCompleteResponse(XMLNode root) throws OMAException {
        super(root, OSUMessageType.ExchangeComplete);
    }
}
