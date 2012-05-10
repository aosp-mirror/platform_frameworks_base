/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.wifi.p2p.nsd;

import android.net.nsd.DnsSdTxtRecord;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class for storing Bonjour service information that is advertised
 * over a Wi-Fi peer-to-peer setup.
 *
 * {@see android.net.wifi.p2p.WifiP2pManager#addLocalService}
 * {@see android.net.wifi.p2p.WifiP2pManager#removeLocalService}
 * {@see WifiP2pServiceInfo}
 * {@see WifiP2pUpnpServiceInfo}
 */
public class WifiP2pDnsSdServiceInfo extends WifiP2pServiceInfo {

    /**
     * Bonjour version 1.
     * @hide
     */
    public static final int VERSION_1 = 0x01;

    /**
     * Pointer record.
     * @hide
     */
    public static final int DNS_TYPE_PTR = 12;

    /**
     * Text record.
     * @hide
     */
    public static final int DNS_TYPE_TXT = 16;

    /**
     * virtual memory packet.
     * see E.3 of the Wi-Fi Direct technical specification for the detail.<br>
     * Key: domain name Value: pointer address.<br>
     */
    private final static Map<String, String> sVmPacket;

    static {
        sVmPacket = new HashMap<String, String>();
        sVmPacket.put("_tcp.local.", "c00c");
        sVmPacket.put("local.", "c011");
        sVmPacket.put("_udp.local.", "c01c");
    }

    /**
     * This constructor is only used in newInstance().
     *
     * @param queryList
     */
    private WifiP2pDnsSdServiceInfo(List<String> queryList) {
        super(queryList);
    }

    /**
     * Create a Bonjour service information object.
     *
     * @param instanceName instance name.<br>
     *  e.g) "MyPrinter"
     * @param serviceType service type.<br>
     *  e.g) "_ipp._tcp"
     * @param txtMap TXT record with key/value pair in a map confirming to format defined at
     * http://files.dns-sd.org/draft-cheshire-dnsext-dns-sd.txt
     * @return Bonjour service information object
     */
    public static WifiP2pDnsSdServiceInfo newInstance(String instanceName,
            String serviceType, Map<String, String> txtMap) {
        if (TextUtils.isEmpty(instanceName) || TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException(
                    "instance name or service type cannot be empty");
        }

        DnsSdTxtRecord txtRecord = new DnsSdTxtRecord();
        if (txtMap != null) {
            for (String key : txtMap.keySet()) {
                txtRecord.set(key, txtMap.get(key));
            }
        }

        ArrayList<String> queries = new ArrayList<String>();
        queries.add(createPtrServiceQuery(instanceName, serviceType));
        queries.add(createTxtServiceQuery(instanceName, serviceType, txtRecord));

        return new WifiP2pDnsSdServiceInfo(queries);
    }

    /**
     * Create wpa_supplicant service query for PTR record.
     *
     * @param instanceName instance name.<br>
     *  e.g) "MyPrinter"
     * @param serviceType service type.<br>
     *  e.g) "_ipp._tcp"
     * @return wpa_supplicant service query.
     */
    private static String createPtrServiceQuery(String instanceName,
            String serviceType) {

        StringBuffer sb = new StringBuffer();
        sb.append("bonjour ");
        sb.append(createRequest(serviceType + ".local.", DNS_TYPE_PTR, VERSION_1));
        sb.append(" ");

        byte[] data = instanceName.getBytes();
        sb.append(String.format("%02x", data.length));
        sb.append(WifiP2pServiceInfo.bin2HexStr(data));
        // This is the start point of this response.
        // Therefore, it indicates the request domain name.
        sb.append("c027");
        return sb.toString();
    }

    /**
     * Create wpa_supplicant service query for TXT record.
     *
     * @param instanceName instance name.<br>
     *  e.g) "MyPrinter"
     * @param serviceType service type.<br>
     *  e.g) "_ipp._tcp"
     * @param txtRecord TXT record.<br>
     * @return wpa_supplicant service query.
     */
    private static String createTxtServiceQuery(String instanceName,
            String serviceType,
            DnsSdTxtRecord txtRecord) {


        StringBuffer sb = new StringBuffer();
        sb.append("bonjour ");

        sb.append(createRequest((instanceName + "." + serviceType + ".local."),
                DNS_TYPE_TXT, VERSION_1));
        sb.append(" ");
        byte[] rawData = txtRecord.getRawData();
        if (rawData.length == 0) {
            sb.append("00");
        } else {
            sb.append(bin2HexStr(rawData));
        }
        return sb.toString();
    }

    /**
     * Create bonjour service discovery request.
     *
     * @param dnsName dns name
     * @param dnsType dns type
     * @param version version number
     * @hide
     */
    static String createRequest(String dnsName, int dnsType, int version) {
        StringBuffer sb = new StringBuffer();

        /*
         * The request format is as follows.
         * ________________________________________________
         * |  Encoded and Compressed dns name (variable)  |
         * ________________________________________________
         * |   Type (2)           | Version (1) |
         */
        if (dnsType == WifiP2pDnsSdServiceInfo.DNS_TYPE_TXT) {
            dnsName = dnsName.toLowerCase();
        }
        sb.append(compressDnsName(dnsName));
        sb.append(String.format("%04x", dnsType));
        sb.append(String.format("%02x", version));

        return sb.toString();
    }

    /**
     * Compress DNS data.
     *
     * see E.3 of the Wi-Fi Direct technical specification for the detail.
     *
     * @param dnsName dns name
     * @return compressed dns name
     */
    private static String compressDnsName(String dnsName) {
        StringBuffer sb = new StringBuffer();

        // The domain name is replaced with a pointer to a prior
        // occurrence of the same name in virtual memory packet.
        while (true) {
            String data = sVmPacket.get(dnsName);
            if (data != null) {
                sb.append(data);
                break;
            }
            int i = dnsName.indexOf('.');
            if (i == -1) {
                if (dnsName.length() > 0) {
                    sb.append(String.format("%02x", dnsName.length()));
                    sb.append(WifiP2pServiceInfo.bin2HexStr(dnsName.getBytes()));
                }
                // for a sequence of labels ending in a zero octet
                sb.append("00");
                break;
            }

            String name = dnsName.substring(0, i);
            dnsName = dnsName.substring(i + 1);
            sb.append(String.format("%02x", name.length()));
            sb.append(WifiP2pServiceInfo.bin2HexStr(name.getBytes()));
        }
        return sb.toString();
    }
}
