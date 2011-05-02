/*
 * Copyright (C) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.connectivitymanagertest;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.AuthAlgorithm;
import android.net.wifi.WifiConfiguration.IpAssignment;
import android.net.wifi.WifiConfiguration.KeyMgmt;
import android.net.wifi.WifiConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.util.Log;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


/**
 * Help class to process configurations of access points saved in an XML file.
 * The configurations of an access point is included in tag
 * <accesspoint></accesspoint>. The supported configuration includes: ssid,
 * security, eap, phase2, identity, password, anonymousidentity, cacert, usercert,
 * in which each is included in the corresponding tags. Static IP setting is also supported.
 * Tags that can be used include: ip, gateway, networkprefixlength, dns1, dns2. All access points
 * have to be enclosed in tags of <resources></resources>.
 *
 * The following is a sample configuration file for an access point using EAP-PEAP with MSCHAP2.
 * <resources>
 *   <accesspoint>
 *   <ssid>testnet</ssid>
 *   <security>EAP</security>
 *   <eap>PEAP</eap>
 *   <phase2>MSCHAP2</phase2>
 *   <identity>donut</identity</identity>
 *   <password>abcdefgh</password>
 *   </accesspoint>
 * </resources>
 *
 * Note:ssid and security have to be the first two tags
 *      for static ip setting, tag "ip" should be listed before other fields: dns, gateway,
 *      networkprefixlength.
 */
public class AccessPointParserHelper {
    private static final String KEYSTORE_SPACE = "keystore://";
    private static final String TAG = "AccessPointParserHelper";
    static final int NONE = 0;
    static final int WEP = 1;
    static final int PSK = 2;
    static final int EAP = 3;

    List<WifiConfiguration> networks = new ArrayList<WifiConfiguration>();

    private int getSecurityType (String security) {
        if (security.equalsIgnoreCase("NONE")) {
            return NONE;
        } else if (security.equalsIgnoreCase("WEP")) {
            return WEP;
        } else if (security.equalsIgnoreCase("PSK")) {
            return PSK;
        } else if (security.equalsIgnoreCase("EAP")) {
            return EAP;
        } else {
            return -1;
        }
    }

    private boolean validateEapValue(String value) {
        if (value.equalsIgnoreCase("PEAP") ||
                value.equalsIgnoreCase("TLS") ||
                value.equalsIgnoreCase("TTLS")) {
            return true;
        } else {
            return false;
        }
    }

    DefaultHandler mHandler = new DefaultHandler() {

        boolean ssid = false;
        boolean security = false;
        boolean password = false;
        boolean ip = false;
        boolean gateway = false;
        boolean networkprefix = false;
        boolean dns1 = false;
        boolean dns2 = false;
        boolean eap = false;
        boolean phase2 = false;
        boolean identity = false;
        boolean anonymousidentity = false;
        boolean cacert = false;
        boolean usercert = false;
        WifiConfiguration config = null;
        int securityType = NONE;
        LinkProperties mLinkProperties = null;
        InetAddress mInetAddr = null;

        @Override
        public void startElement(String uri, String localName, String tagName,
                Attributes attributes) throws SAXException {
            if (tagName.equalsIgnoreCase("accesspoint")) {
                config = new WifiConfiguration();
            }
            if (tagName.equalsIgnoreCase("ssid")) {
                ssid = true;
            }
            if (tagName.equalsIgnoreCase("security")) {
                security = true;
            }
            if (tagName.equalsIgnoreCase("password")) {
                password = true;
            }
            if (tagName.equalsIgnoreCase("eap")) {
                eap = true;
            }
            if (tagName.equalsIgnoreCase("phase2")) {
                phase2 = true;
            }
            if (tagName.equalsIgnoreCase("identity")) {
                identity = true;
            }
            if (tagName.equalsIgnoreCase("anonymousidentity")) {
                anonymousidentity = true;
            }
            if (tagName.equalsIgnoreCase("cacert")) {
                cacert = true;
            }
            if (tagName.equalsIgnoreCase("usercert")) {
                usercert = true;
            }
            if (tagName.equalsIgnoreCase("ip")) {
                mLinkProperties = new LinkProperties();
                ip = true;
            }
            if (tagName.equalsIgnoreCase("gateway")) {
                gateway = true;
            }
            if (tagName.equalsIgnoreCase("networkprefixlength")) {
                networkprefix = true;
            }
            if (tagName.equalsIgnoreCase("dns1")) {
                dns1 = true;
            }
            if (tagName.equalsIgnoreCase("dns2")) {
                dns2 = true;
            }
        }

        @Override
        public void endElement(String uri, String localName, String tagName) throws SAXException {
            if (tagName.equalsIgnoreCase("accesspoint")) {
                if (mLinkProperties != null) {
                    config.ipAssignment = IpAssignment.STATIC;
                    config.linkProperties = mLinkProperties;
                } else {
                    config.ipAssignment = IpAssignment.DHCP;
                }
                config.proxySettings = ProxySettings.NONE;
                networks.add(config);
                mLinkProperties = null;
            }
        }

        @Override
        public void characters(char ch[], int start, int length) throws SAXException {
            if (ssid) {
                config.SSID = new String(ch, start, length);
                ssid = false;
            }
            if (security) {
                String securityStr = (new String(ch, start, length)).toUpperCase();
                securityType = getSecurityType(securityStr);
                switch (securityType) {
                    case NONE:
                        config.allowedKeyManagement.set(KeyMgmt.NONE);
                        break;
                    case WEP:
                        config.allowedKeyManagement.set(KeyMgmt.NONE);
                        config.allowedAuthAlgorithms.set(AuthAlgorithm.OPEN);
                        config.allowedAuthAlgorithms.set(AuthAlgorithm.SHARED);
                        break;
                    case PSK:
                        config.allowedKeyManagement.set(KeyMgmt.WPA_PSK);
                        break;
                    case EAP:
                        config.allowedKeyManagement.set(KeyMgmt.WPA_EAP);
                        config.allowedKeyManagement.set(KeyMgmt.IEEE8021X);
                        // Initialize other fields.
                        config.phase2.setValue("");
                        config.ca_cert.setValue("");
                        config.client_cert.setValue("");
                        config.private_key.setValue("");
                        config.identity.setValue("");
                        config.anonymous_identity.setValue("");
                        break;
                    default:
                        throw new SAXException();
                }
                security = false;
            }
            if (password) {
                String passwordStr = new String(ch, start, length);
                int len = passwordStr.length();
                if (len == 0) {
                    throw new SAXException();
                }
                if (securityType == WEP) {
                    if ((len == 10 || len == 26 || len == 58) &&
                            passwordStr.matches("[0-9A-Fa-f]*")) {
                        config.wepKeys[0] = passwordStr;
                    } else {
                        config.wepKeys[0] = '"' + passwordStr + '"';
                    }
                } else if (securityType == PSK) {
                    if (passwordStr.matches("[0-9A-Fa-f]{64}")) {
                        config.preSharedKey = passwordStr;
                    } else {
                        config.preSharedKey = '"' + passwordStr + '"';
                    }
                } else if (securityType == EAP) {
                    config.password.setValue(passwordStr);
                } else {
                    throw new SAXException();
                }
                password = false;
            }
            if (eap) {
                String eapValue = new String(ch, start, length);
                if (!validateEapValue(eapValue)) {
                    throw new SAXException();
                }
                config.eap.setValue(eapValue);
                eap = false;
            }
            if (phase2) {
                String phase2Value = new String(ch, start, length);
                config.phase2.setValue("auth=" + phase2Value);
                phase2 = false;
            }
            if (identity) {
                String identityValue = new String(ch, start, length);
                config.identity.setValue(identityValue);
                identity = false;
            }
            if (anonymousidentity) {
                String anonyId = new String(ch, start, length);
                config.anonymous_identity.setValue(anonyId);
                anonymousidentity = false;
            }
            if (cacert) {
                String cacertValue = new String(ch, start, length);
                // need to install the credentail to "keystore://"
                config.ca_cert.setValue(KEYSTORE_SPACE);
                cacert = false;
            }
            if (usercert) {
                String usercertValue = new String(ch, start, length);
                config.client_cert.setValue(KEYSTORE_SPACE);
                usercert = false;
            }
            if (ip) {
                try {
                    String ipAddr = new String(ch, start, length);
                    if (!InetAddress.isNumeric(ipAddr)) {
                        throw new SAXException();
                    }
                    mInetAddr = InetAddress.getByName(ipAddr);
                } catch (UnknownHostException e) {
                    throw new SAXException();
                }
                ip = false;
            }
            if (gateway) {
                try {
                    String gwAddr = new String(ch, start, length);
                    if (!InetAddress.isNumeric(gwAddr)) {
                        throw new SAXException();
                    }
                    mLinkProperties.addRoute(new RouteInfo(InetAddress.getByName(gwAddr)));
                } catch (UnknownHostException e) {
                    throw new SAXException();
                }
                gateway = false;
            }
            if (networkprefix) {
                try {
                    int nwPrefixLength = Integer.parseInt(new String(ch, start, length));
                    if ((nwPrefixLength < 0) || (nwPrefixLength > 32)) {
                        throw new SAXException();
                    }
                    mLinkProperties.addLinkAddress(new LinkAddress(mInetAddr, nwPrefixLength));
                } catch (NumberFormatException e) {
                    throw new SAXException();
                }
                networkprefix = false;
            }
            if (dns1) {
                try {
                    String dnsAddr = new String(ch, start, length);
                    if (!InetAddress.isNumeric(dnsAddr)) {
                        throw new SAXException();
                    }
                    mLinkProperties.addDns(InetAddress.getByName(dnsAddr));
                } catch (UnknownHostException e) {
                    throw new SAXException();
                }
                dns1 = false;
            }
            if (dns2) {
                try {
                    String dnsAddr = new String(ch, start, length);
                    if (!InetAddress.isNumeric(dnsAddr)) {
                        throw new SAXException();
                    }
                    mLinkProperties.addDns(InetAddress.getByName(dnsAddr));
                } catch (UnknownHostException e) {
                    throw new SAXException();
                }
                dns2 = false;
            }
        }
    };

    /**
     * Process the InputStream in
     * @param in is the InputStream that can be used for XML parsing
     * @throws Exception
     */
    public AccessPointParserHelper(InputStream in) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser saxParser = factory.newSAXParser();
        saxParser.parse(in, mHandler);
    }

    public List<WifiConfiguration> getNetworkConfigurations() throws Exception {
        return networks;
    }
}
