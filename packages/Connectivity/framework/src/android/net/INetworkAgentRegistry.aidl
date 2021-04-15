/**
 * Copyright (c) 2020, The Android Open Source Project
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
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkScore;
import android.net.QosSession;
import android.telephony.data.EpsBearerQosSessionAttributes;
import android.telephony.data.NrQosSessionAttributes;

/**
 * Interface for NetworkAgents to send network properties.
 * @hide
 */
oneway interface INetworkAgentRegistry {
    void sendNetworkCapabilities(in NetworkCapabilities nc);
    void sendLinkProperties(in LinkProperties lp);
    // TODO: consider replacing this by "markConnected()" and removing
    void sendNetworkInfo(in NetworkInfo info);
    void sendScore(in NetworkScore score);
    void sendExplicitlySelected(boolean explicitlySelected, boolean acceptPartial);
    void sendSocketKeepaliveEvent(int slot, int reason);
    void sendUnderlyingNetworks(in @nullable List<Network> networks);
    void sendEpsQosSessionAvailable(int callbackId, in QosSession session, in EpsBearerQosSessionAttributes attributes);
    void sendNrQosSessionAvailable(int callbackId, in QosSession session, in NrQosSessionAttributes attributes);
    void sendQosSessionLost(int qosCallbackId, in QosSession session);
    void sendQosCallbackError(int qosCallbackId, int exceptionType);
    void sendTeardownDelayMs(int teardownDelayMs);
    void sendLingerDuration(int durationMs);
}
