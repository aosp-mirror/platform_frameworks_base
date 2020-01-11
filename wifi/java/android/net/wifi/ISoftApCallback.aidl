/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApInfo;

import android.net.wifi.WifiClient;

/**
 * Interface for Soft AP callback.
 *
 * @hide
 */
oneway interface ISoftApCallback
{
    /**
     * Service to manager callback providing current soft AP state. The possible
     * parameter values listed are defined in WifiManager.java
     *
     * @param state new AP state. One of WIFI_AP_STATE_DISABLED,
     *        WIFI_AP_STATE_DISABLING, WIFI_AP_STATE_ENABLED,
     *        WIFI_AP_STATE_ENABLING, WIFI_AP_STATE_FAILED
     * @param failureReason reason when in failed state. One of
     *        SAP_START_FAILURE_GENERAL, SAP_START_FAILURE_NO_CHANNEL
     */
    void onStateChanged(int state, int failureReason);

    /**
     * Service to manager callback providing connected client's information.
     *
     * @param clients the currently connected clients
     */
    void onConnectedClientsChanged(in List<WifiClient> clients);

    /**
     * Service to manager callback providing information of softap.
     *
     * @param softApInfo is the softap information. {@link SoftApInfo}
     */
    void onInfoChanged(in SoftApInfo softApInfo);


    /**
     * Service to manager callback providing capability of softap.
     *
     * @param capability is the softap capability. {@link SoftApCapability}
     */
    void onCapabilityChanged(in SoftApCapability capability);

    /**
     * Service to manager callback providing blocked client of softap with specific reason code.
     *
     * @param client the currently blocked client.
     * @param blockedReason one of blocked reason from {@link WifiManager.SapClientBlockedReason}
     */
    void onBlockedClientConnecting(in WifiClient client, int blockedReason);
}
