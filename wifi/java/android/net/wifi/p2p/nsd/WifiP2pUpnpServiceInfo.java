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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A class for storing Upnp service information that is advertised
 * over a Wi-Fi peer-to-peer setup.
 *
 * {@see android.net.wifi.p2p.WifiP2pManager#addLocalService}
 * {@see android.net.wifi.p2p.WifiP2pManager#removeLocalService}
 * {@see WifiP2pServiceInfo}
 * {@see WifiP2pDnsSdServiceInfo}
 */
public class WifiP2pUpnpServiceInfo extends WifiP2pServiceInfo {

    /**
     * UPnP version 1.0.
     *
     * <pre>Query Version should always be set to 0x10 if the query values are
     * compatible with UPnP Device Architecture 1.0.
     * @hide
     */
    public static final int VERSION_1_0 = 0x10;

    /**
     * This constructor is only used in newInstance().
     *
     * @param queryList
     */
    private WifiP2pUpnpServiceInfo(List<String> queryList) {
        super(queryList);
    }

    /**
     * Create UPnP service information object.
     *
     * @param uuid a string representation of this UUID in the following format,
     *  as per <a href="http://www.ietf.org/rfc/rfc4122.txt">RFC 4122</a>.<br>
     *  e.g) 6859dede-8574-59ab-9332-123456789012
     * @param device a string representation of this device in the following format,
     * as per
     * <a href="http://www.upnp.org/specs/arch/UPnP-arch-DeviceArchitecture-v1.1.pdf">
     *  UPnP Device Architecture1.1</a><br>
     *  e.g) urn:schemas-upnp-org:device:MediaServer:1
     * @param services a string representation of this service in the following format,
     * as per
     * <a href="http://www.upnp.org/specs/arch/UPnP-arch-DeviceArchitecture-v1.1.pdf">
     *  UPnP Device Architecture1.1</a><br>
     *  e.g) urn:schemas-upnp-org:service:ContentDirectory:1
     * @return UPnP service information object.
     */
    public static WifiP2pUpnpServiceInfo newInstance(String uuid,
            String device, List<String> services) {
        if (uuid == null || device == null) {
            throw new IllegalArgumentException("uuid or device cannnot be null");
        }
        UUID.fromString(uuid);

        ArrayList<String> info = new ArrayList<String>();

        info.add(createSupplicantQuery(uuid, null));
        info.add(createSupplicantQuery(uuid, "upnp:rootdevice"));
        info.add(createSupplicantQuery(uuid, device));
        if (services != null) {
            for (String service:services) {
                info.add(createSupplicantQuery(uuid, service));
            }
        }

        return new WifiP2pUpnpServiceInfo(info);
    }

    /**
     * Create wpa_supplicant service query for upnp.
     *
     * @param uuid
     * @param data
     * @return wpa_supplicant service query for upnp
     */
    private static String createSupplicantQuery(String uuid, String data) {
        StringBuffer sb = new StringBuffer();
        sb.append("upnp ");
        sb.append(String.format(Locale.US, "%02x ", VERSION_1_0));
        sb.append("uuid:");
        sb.append(uuid);
        if (data != null) {
            sb.append("::");
            sb.append(data);
        }
        return sb.toString();
    }
}
