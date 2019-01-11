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

    void start();
    void launchCaptivePortalApp();
    void forceReevaluation(int uid);
    void notifyPrivateDnsChanged(in PrivateDnsConfigParcel config);
    void notifyDnsResponse(int returnCode);
    void notifySystemReady();
    void notifyNetworkConnected();
    void notifyNetworkDisconnected();
    void notifyLinkPropertiesChanged();
    void notifyNetworkCapabilitiesChanged();
}