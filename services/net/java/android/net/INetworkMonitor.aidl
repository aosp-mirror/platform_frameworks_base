/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing perNmissions and
 * limitations under the License.
 */
package android.net;

import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.PrivateDnsConfigParcel;

/** @hide */
oneway interface INetworkMonitor {
    // After a network has been tested this result can be sent with EVENT_NETWORK_TESTED.
    // The network should be used as a default internet connection.  It was found to be:
    // 1. a functioning network providing internet access, or
    // 2. a captive portal and the user decided to use it as is.
    const int NETWORK_TEST_RESULT_VALID = 0;

    // After a network has been tested this result can be sent with EVENT_NETWORK_TESTED.
    // The network should not be used as a default internet connection.  It was found to be:
    // 1. a captive portal and the user is prompted to sign-in, or
    // 2. a captive portal and the user did not want to use it, or
    // 3. a broken network (e.g. DNS failed, connect failed, HTTP request failed).
    const int NETWORK_TEST_RESULT_INVALID = 1;

    // After a network has been tested, this result can be sent with EVENT_NETWORK_TESTED.
    // The network may be used as a default internet connection, but it was found to be a partial
    // connectivity network which can get the pass result for http probe but get the failed result
    // for https probe.
    const int NETWORK_TEST_RESULT_PARTIAL_CONNECTIVITY = 2;

    // Network validation flags indicate probe result and types. If no NETWORK_VALIDATION_RESULT_*
    // are set, then it's equal to NETWORK_TEST_RESULT_INVALID. If NETWORK_VALIDATION_RESULT_VALID
    // is set, then the network validates and equal to NETWORK_TEST_RESULT_VALID. If
    // NETWORK_VALIDATION_RESULT_PARTIAL is set, then the network has partial connectivity which
    // is equal to NETWORK_TEST_RESULT_PARTIAL_CONNECTIVITY. NETWORK_VALIDATION_PROBE_* is set
    // when the specific probe result of the network is resolved.
    const int NETWORK_VALIDATION_RESULT_VALID = 0x01;
    const int NETWORK_VALIDATION_RESULT_PARTIAL = 0x02;
    const int NETWORK_VALIDATION_PROBE_DNS = 0x04;
    const int NETWORK_VALIDATION_PROBE_HTTP = 0x08;
    const int NETWORK_VALIDATION_PROBE_HTTPS = 0x10;
    const int NETWORK_VALIDATION_PROBE_FALLBACK = 0x20;
    const int NETWORK_VALIDATION_PROBE_PRIVDNS = 0x40;

    void start();
    void launchCaptivePortalApp();
    void notifyCaptivePortalAppFinished(int response);
    void setAcceptPartialConnectivity();
    void forceReevaluation(int uid);
    void notifyPrivateDnsChanged(in PrivateDnsConfigParcel config);
    void notifyDnsResponse(int returnCode);
    void notifyNetworkConnected(in LinkProperties lp, in NetworkCapabilities nc);
    void notifyNetworkDisconnected();
    void notifyLinkPropertiesChanged(in LinkProperties lp);
    void notifyNetworkCapabilitiesChanged(in NetworkCapabilities nc);
}
