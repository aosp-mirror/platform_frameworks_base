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

import java.util.ArrayList;
import java.util.List;

/**
 * A class for a response of upnp service discovery.
 *
 * @hide
 */
public class WifiP2pUpnpServiceResponse extends WifiP2pServiceResponse {

    /**
     * UPnP version. should be {@link WifiP2pUpnpServiceInfo#VERSION_1_0}
     */
    private int mVersion;

    /**
     * The list of Unique Service Name.
     * e.g)
     *{"uuid:6859dede-8574-59ab-9332-123456789012",
     *"uuid:6859dede-8574-59ab-9332-123456789012::upnp:rootdevice"}
     */
    private List<String> mUniqueServiceNames;

    /**
     * Return UPnP version number.
     *
     * @return version number.
     * @see WifiP2pUpnpServiceInfo#VERSION_1_0
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Return Unique Service Name strings.
     *
     * @return Unique Service Name.<br>
     * e.g ) <br>
     * <ul>
     * <li>"uuid:6859dede-8574-59ab-9332-123456789012"
     * <li>"uuid:6859dede-8574-59ab-9332-123456789012::upnp:rootdevice"
     * <li>"uuid:6859dede-8574-59ab-9332-123456789012::urn:schemas-upnp-org:device:
     * MediaServer:2"
     * <li>"uuid:6859dede-8574-59ab-9332-123456789012::urn:schemas-upnp-org:service:
     * ContentDirectory:2"
     * </ul>
     */
    public List<String> getUniqueServiceNames() {
        return mUniqueServiceNames;
    }

    /**
     * hidden constructor.
     *
     * @param status status code
     * @param transId transaction id
     * @param dev source device
     * @param data UPnP response data.
     */
    protected WifiP2pUpnpServiceResponse(int status,
            int transId, WifiP2pDevice dev, byte[] data) {
        super(WifiP2pServiceInfo.SERVICE_TYPE_UPNP,
                status, transId, dev, data);
        if (!parse()) {
            throw new IllegalArgumentException("Malformed upnp service response");
        }
    }

    /**
     * Parse UPnP service discovery response
     *
     * @return {@code true} if the operation succeeded
     */
    private boolean parse() {
        /*
         * The data format is as follows.
         *
         * ______________________________________________________
         * |  Version (1)  |          USN (Variable)            |
         */
        if (mData == null) {
            // the empty is OK.
            return true;
        }

        if (mData.length < 1) {
            return false;
        }

        mVersion = mData[0] & 0xff;
        String[] names = new String(mData, 1, mData.length-1).split(",");
        mUniqueServiceNames = new ArrayList<String>();
        for (String name : names) {
            mUniqueServiceNames.add(name);
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("serviceType:UPnP(").append(mServiceType).append(")");
        sbuf.append(" status:").append(Status.toString(mStatus));
        sbuf.append(" srcAddr:").append(mDevice.deviceAddress);
        sbuf.append(" version:").append(String.format("%02x", mVersion));
        if (mUniqueServiceNames != null) {
            for (String name : mUniqueServiceNames) {
                sbuf.append(" usn:").append(name);
            }
        }
        return sbuf.toString();
    }

    /**
     * Create upnp service response.
     *
     * <pre>This is only used in{@link WifiP2pServiceResponse}
     *
     * @param status status code.
     * @param transId transaction id.
     * @param device source device.
     * @param data UPnP response data.
     * @return UPnP service response data.
     * @hide
     */
    static WifiP2pUpnpServiceResponse newInstance(int status,
            int transId, WifiP2pDevice device, byte[] data) {
        if (status != WifiP2pServiceResponse.Status.SUCCESS) {
            return new WifiP2pUpnpServiceResponse(status, transId, device, null);
        }

        try {
            return new WifiP2pUpnpServiceResponse(status, transId, device, data);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
        return null;
    }
}
