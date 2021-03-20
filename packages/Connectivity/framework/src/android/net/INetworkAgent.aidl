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

import android.net.NattKeepalivePacketData;
import android.net.QosFilterParcelable;
import android.net.TcpKeepalivePacketData;

import android.net.INetworkAgentRegistry;

/**
 * Interface to notify NetworkAgent of connectivity events.
 * @hide
 */
oneway interface INetworkAgent {
    void onRegistered(in INetworkAgentRegistry registry);
    void onDisconnected();
    void onBandwidthUpdateRequested();
    void onValidationStatusChanged(int validationStatus,
            in @nullable String captivePortalUrl);
    void onSaveAcceptUnvalidated(boolean acceptUnvalidated);
    void onStartNattSocketKeepalive(int slot, int intervalDurationMs,
        in NattKeepalivePacketData packetData);
    void onStartTcpSocketKeepalive(int slot, int intervalDurationMs,
        in TcpKeepalivePacketData packetData);
    void onStopSocketKeepalive(int slot);
    void onSignalStrengthThresholdsUpdated(in int[] thresholds);
    void onPreventAutomaticReconnect();
    void onAddNattKeepalivePacketFilter(int slot,
        in NattKeepalivePacketData packetData);
    void onAddTcpKeepalivePacketFilter(int slot,
        in TcpKeepalivePacketData packetData);
    void onRemoveKeepalivePacketFilter(int slot);
    void onQosFilterCallbackRegistered(int qosCallbackId, in QosFilterParcelable filterParcel);
    void onQosCallbackUnregistered(int qosCallbackId);
}
