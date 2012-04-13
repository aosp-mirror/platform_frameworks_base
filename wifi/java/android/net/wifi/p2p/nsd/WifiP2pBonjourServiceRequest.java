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


/**
 * A class for a request of bonjour service discovery.
 * @hide
 */
public class WifiP2pBonjourServiceRequest extends WifiP2pServiceRequest {

    /**
     * This constructor is only used in newInstance().
     *
     * @param query The part of service specific query.
     * @hide
     */
    private WifiP2pBonjourServiceRequest(String query) {
        super(WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR, query);
    }

    /**
     * This constructor is only used in newInstance().
     * @hide
     */
    private WifiP2pBonjourServiceRequest() {
        super(WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR, null);
    }

    private WifiP2pBonjourServiceRequest(String registrationType, int dnsType, int version) {
        super(WifiP2pServiceInfo.SERVICE_TYPE_BONJOUR, WifiP2pBonjourServiceInfo.createRequest(
                registrationType,
                WifiP2pBonjourServiceInfo.DNS_TYPE_PTR,
                WifiP2pBonjourServiceInfo.VERSION_1));
    }

    /**
     * Create a service discovery request to search all Bonjour services.
     *
     * @return service request for Bonjour.
     */
    public static WifiP2pBonjourServiceRequest newInstance() {
        return new WifiP2pBonjourServiceRequest();
    }

    /**
     * Create a service discovery request to resolve the instance name with the specified
     * registration type.
     *
     * @param registrationType registration type. Cannot be null <br>
     * e.g) <br>
     *  "_afpovertcp._tcp.local."(Apple File Sharing over TCP)<br>
     *  "_ipp._tcp.local." (IP Printing over TCP)<br>
     * @return service request for Bonjour.
     */
    public static WifiP2pBonjourServiceRequest newInstance(String registrationType) {
        if (registrationType == null) {
            throw new IllegalArgumentException("registration type cannot be null");
        }
        return new WifiP2pBonjourServiceRequest(registrationType,
                WifiP2pBonjourServiceInfo.DNS_TYPE_PTR,
                WifiP2pBonjourServiceInfo.VERSION_1);
    }

    /**
     * Create a service discovery request to get the TXT data from the specified
     * service.
     *
     * @param instanceName instance name. Cannot be null. <br>
     *  "MyPrinter"
     * @param registrationType registration type. Cannot be null. <br>
     * e.g) <br>
     *  "_afpovertcp._tcp.local."(Apple File Sharing over TCP)<br>
     *  "_ipp._tcp.local." (IP Printing over TCP)<br>
     * @return service request for Bonjour.
     */
    public static WifiP2pBonjourServiceRequest newInstance(String instanceName,
            String registrationType) {
        if (instanceName == null || registrationType == null) {
            throw new IllegalArgumentException(
                    "instance name or registration type cannot be null");
        }
        String fullDomainName = instanceName + "." + registrationType;
        return new WifiP2pBonjourServiceRequest(fullDomainName,
                WifiP2pBonjourServiceInfo.DNS_TYPE_TXT,
                WifiP2pBonjourServiceInfo.VERSION_1);
    }
}
