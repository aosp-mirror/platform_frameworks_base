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

import android.net.wifi.p2p.WifiP2pManager;

/**
 * A class for creating a Upnp service discovery request for use with
 * {@link WifiP2pManager#addServiceRequest} and {@link WifiP2pManager#removeServiceRequest}
 *
 * {@see WifiP2pManager}
 * {@see WifiP2pServiceRequest}
 * {@see WifiP2pDnsSdServiceRequest}
 */
public class WifiP2pUpnpServiceRequest extends WifiP2pServiceRequest {

    /**
     * This constructor is only used in newInstance().
     *
     * @param query The part of service specific query.
     * @hide
     */
    protected WifiP2pUpnpServiceRequest(String query) {
        super(WifiP2pServiceInfo.SERVICE_TYPE_UPNP, query);
    }

    /**
     * This constructor is only used in newInstance().
     * @hide
     */
    protected WifiP2pUpnpServiceRequest() {
        super(WifiP2pServiceInfo.SERVICE_TYPE_UPNP, null);
    }

    /**
     * Create a service discovery request to search all UPnP services.
     *
     * @return service request for UPnP.
     */
    public static WifiP2pUpnpServiceRequest newInstance() {
        return new WifiP2pUpnpServiceRequest();
    }
    /**
     * Create a service discovery request to search specified UPnP services.
     *
     * @param st ssdp search target.  Cannot be null.<br>
     * e.g ) <br>
     * <ul>
     * <li>"ssdp:all"
     * <li>"upnp:rootdevice"
     * <li>"urn:schemas-upnp-org:device:MediaServer:2"
     * <li>"urn:schemas-upnp-org:service:ContentDirectory:2"
     * <li>"uuid:6859dede-8574-59ab-9332-123456789012"
     * </ul>
     * @return service request for UPnP.
     */
    public static WifiP2pUpnpServiceRequest newInstance(String st) {
        if (st == null) {
            throw new IllegalArgumentException("search target cannot be null");
        }
        StringBuffer sb = new StringBuffer();
        sb.append(String.format("%02x", WifiP2pUpnpServiceInfo.VERSION_1_0));
        sb.append(WifiP2pServiceInfo.bin2HexStr(st.getBytes()));
        return new WifiP2pUpnpServiceRequest(sb.toString());
    }
}
