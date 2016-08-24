package com.android.hotspot2.osu.commands;

import android.util.Base64;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/*
    <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
        <env:Header/>
        <env:Body>
            <spp:sppPostDevDataResponse xmlns:spp="http://www.wi-fi.org/specifications/hotspot2dot0/v1.0/spp"
                                        spp:sessionID="A40103ACEDE94C45BA127A41239BD60F" spp:sppStatus="OK"
                                        spp:sppVersion="1.0">
                <spp:exec>
                    <spp:getCertificate enrollmentProtocol="EST">
                        <spp:enrollmentServerURI>https://osu-server.r2-testbed-rks.wi-fi.org:9446/.well-known/est
                        </spp:enrollmentServerURI>
                        <spp:estUserID>a88c4830-aafd-420b-b790-c08f457a0fa3</spp:estUserID>
                        <spp:estPassword>cnVja3VzMTIzNA==</spp:estPassword>
                    </spp:getCertificate>
                </spp:exec>
            </spp:sppPostDevDataResponse>
        </env:Body>
    </env:Envelope>
 */

public class GetCertData implements OSUCommandData {
    private final String mProtocol;
    private final String mServer;
    private final String mUserName;
    private final byte[] mPassword;

    public GetCertData(XMLNode commandNode) throws OMAException {
        mProtocol = commandNode.getAttributeValue("enrollmentProtocol");

        Map<String, String> values = new HashMap<>(3);
        for (XMLNode node : commandNode.getChildren()) {
            values.put(node.getStrippedTag(), node.getText());
        }

        mServer = values.get("enrollmentserveruri");
        mUserName = values.get("estuserid");
        mPassword = Base64.decode(values.get("estpassword"), Base64.DEFAULT);
    }

    public String getProtocol() {
        return mProtocol;
    }

    public String getServer() {
        return mServer;
    }

    public String getUserName() {
        return mUserName;
    }

    public byte[] getPassword() {
        return mPassword;
    }

    @Override
    public String toString() {
        return "GetCertData " +
                "protocol='" + mProtocol + '\'' +
                ", server='" + mServer + '\'' +
                ", userName='" + mUserName + '\'' +
                ", password='" + new String(mPassword, StandardCharsets.ISO_8859_1) + '\'';
    }
}
