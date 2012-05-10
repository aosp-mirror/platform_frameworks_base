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

import android.net.wifi.p2p.WifiP2pDevice;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A class for a response of bonjour service discovery.
 *
 * @hide
 */
public class WifiP2pDnsSdServiceResponse extends WifiP2pServiceResponse {

    /**
     * DNS query name.
     * e.g)
     * for PTR
     * "_ipp._tcp.local."
     * for TXT
     * "MyPrinter._ipp._tcp.local."
     */
    private String mDnsQueryName;

    /**
     * Service instance name.
     * e.g) "MyPrinter"
     * This field is only used when the dns type equals to
     * {@link WifiP2pDnsSdServiceInfo#DNS_TYPE_PTR}.
     */
    private String mInstanceName;

    /**
     * DNS Type.
     * Should be {@link WifiP2pDnsSdServiceInfo#DNS_TYPE_PTR} or
     * {@link WifiP2pDnsSdServiceInfo#DNS_TYPE_TXT}.
     */
    private int mDnsType;

    /**
     * DnsSd version number.
     * Should be {@link WifiP2pDnsSdServiceInfo#VERSION_1}.
     */
    private int mVersion;

    /**
     * Txt record.
     * This field is only used when the dns type equals to
     * {@link WifiP2pDnsSdServiceInfo#DNS_TYPE_TXT}.
     */
    private final HashMap<String, String> mTxtRecord = new HashMap<String, String>();

    /**
     * Virtual memory packet.
     * see E.3 of the Wi-Fi Direct technical specification for the detail.<br>
     * The spec can be obtained from wi-fi.org
     * Key: pointer Value: domain name.<br>
     */
    private final static Map<Integer, String> sVmpack;

    static {
        sVmpack = new HashMap<Integer, String>();
        sVmpack.put(0x0c, "_tcp.local.");
        sVmpack.put(0x11, "local.");
        sVmpack.put(0x1c, "_udp.local.");
    }

    /**
     * Returns query DNS name.
     * @return DNS name.
     */
    public String getDnsQueryName() {
        return mDnsQueryName;
    }

    /**
     * Return query DNS type.
     * @return DNS type.
     */
    public int getDnsType() {
        return mDnsType;
    }

    /**
     * Return bonjour version number.
     * @return version number.
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Return instance name.
     * @return
     */
    public String getInstanceName() {
        return mInstanceName;
    }

    /**
     * Return TXT record data.
     * @return TXT record data.
     */
    public Map<String, String> getTxtRecord() {
        return mTxtRecord;
    }

    @Override
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("serviceType:DnsSd(").append(mServiceType).append(")");
        sbuf.append(" status:").append(Status.toString(mStatus));
        sbuf.append(" srcAddr:").append(mDevice.deviceAddress);
        sbuf.append(" version:").append(String.format("%02x", mVersion));
        sbuf.append(" dnsName:").append(mDnsQueryName);
        sbuf.append(" TxtRecord:");
        for (String key : mTxtRecord.keySet()) {
            sbuf.append(" key:").append(key).append(" value:").append(mTxtRecord.get(key));
        }
        if (mInstanceName != null) {
            sbuf.append(" InsName:").append(mInstanceName);
        }
        return sbuf.toString();
    }

    /**
     * This is only used in framework.
     * @param status status code.
     * @param dev source device.
     * @param data RDATA.
     * @hide
     */
    protected WifiP2pDnsSdServiceResponse(int status,
            int tranId, WifiP2pDevice dev, byte[] data) {
        super(WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR,
                status, tranId, dev, data);
        if (!parse()) {
            throw new IllegalArgumentException("Malformed bonjour service response");
        }
    }

    /**
     * Parse DnsSd service discovery response.
     *
     * @return {@code true} if the operation succeeded
     */
    private boolean parse() {
        /*
         * The data format from Wi-Fi Direct spec is as follows.
         * ________________________________________________
         * |  encoded and compressed dns name (variable)  |
         * ________________________________________________
         * |       dnstype(2byte)      |  version(1byte)  |
         * ________________________________________________
         * |              RDATA (variable)                |
         */
        if (mData == null) {
            // the empty is OK.
            return true;
        }

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(mData));

        mDnsQueryName = readDnsName(dis);
        if (mDnsQueryName == null) {
            return false;
        }

        try {
            mDnsType = dis.readUnsignedShort();
            mVersion = dis.readUnsignedByte();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        if (mDnsType == WifiP2pDnsSdServiceInfo.DNS_TYPE_PTR) {
            String rData = readDnsName(dis);
            if (rData == null) {
                return false;
            }
            if (rData.length() <= mDnsQueryName.length()) {
                return false;
            }

            mInstanceName = rData.substring(0,
                    rData.length() - mDnsQueryName.length() -1);
        } else if (mDnsType == WifiP2pDnsSdServiceInfo.DNS_TYPE_TXT) {
            return readTxtData(dis);
        } else {
            return false;
        }

        return true;
    }

    /**
     * Read dns name.
     *
     * @param dis data input stream.
     * @return dns name
     */
    private String readDnsName(DataInputStream dis) {
        StringBuffer sb = new StringBuffer();

        // copy virtual memory packet.
        HashMap<Integer, String> vmpack = new HashMap<Integer, String>(sVmpack);
        if (mDnsQueryName != null) {
            vmpack.put(0x27, mDnsQueryName);
        }
        try {
            while (true) {
                int i = dis.readUnsignedByte();
                if (i == 0x00) {
                    return sb.toString();
                } else if (i == 0xc0) {
                    // refer to pointer.
                    String ref = vmpack.get(dis.readUnsignedByte());
                    if (ref == null) {
                        //invalid.
                        return null;
                    }
                    sb.append(ref);
                    return sb.toString();
                } else {
                    byte[] data = new byte[i];
                    dis.readFully(data);
                    sb.append(new String(data));
                    sb.append(".");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Read TXT record data.
     *
     * @param dis
     * @return true if TXT data is valid
     */
    private boolean readTxtData(DataInputStream dis) {
        try {
            while (dis.available() > 0) {
                int len = dis.readUnsignedByte();
                if (len == 0) {
                    break;
                }
                byte[] data = new byte[len];
                dis.readFully(data);
                String[] keyVal = new String(data).split("=");
                if (keyVal.length != 2) {
                    return false;
                }
                mTxtRecord.put(keyVal[0], keyVal[1]);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Creates DnsSd service response.
     *  This is only called from WifiP2pServiceResponse
     *
     * @param status status code.
     * @param dev source device.
     * @param data DnsSd response data.
     * @return DnsSd service response data.
     * @hide
     */
    static WifiP2pDnsSdServiceResponse newInstance(int status,
            int transId, WifiP2pDevice dev, byte[] data) {
        if (status != WifiP2pServiceResponse.Status.SUCCESS) {
            return new WifiP2pDnsSdServiceResponse(status,
                    transId, dev, null);
        }
        try {
            return new WifiP2pDnsSdServiceResponse(status,
                    transId, dev, data);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
        return null;
    }
}
