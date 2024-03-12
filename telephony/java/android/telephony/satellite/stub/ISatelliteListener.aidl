/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony.satellite.stub;

import android.telephony.satellite.stub.NtnSignalStrength;
import android.telephony.satellite.stub.NTRadioTechnology;
import android.telephony.satellite.stub.PointingInfo;
import android.telephony.satellite.stub.SatelliteCapabilities;
import android.telephony.satellite.stub.SatelliteDatagram;
import android.telephony.satellite.stub.SatelliteModemState;

/**
 * {@hide}
 */
oneway interface ISatelliteListener {
    /**
     * Indicates that the satellite provision state has changed.
     *
     * @param provisioned True means the service is provisioned and false means it is not.
     */
    void onSatelliteProvisionStateChanged(in boolean provisioned);

    /**
     * Indicates that new datagrams have been received on the device.
     *
     * @param datagram New datagram that was received.
     * @param pendingCount Number of additional datagrams yet to be received.
     */
    void onSatelliteDatagramReceived(in SatelliteDatagram datagram, in int pendingCount);

    /**
     * Indicates that the satellite has pending datagrams for the device to be pulled.
     */
    void onPendingDatagrams();

    /**
     * Indicates that the satellite pointing input has changed.
     *
     * @param pointingInfo The current pointing info.
     */
    void onSatellitePositionChanged(in PointingInfo pointingInfo);

    /**
     * Indicates that the satellite modem state has changed.
     *
     * @param state The current satellite modem state.
     */
    void onSatelliteModemStateChanged(in SatelliteModemState state);

    /**
     * Called when NTN signal strength changes.
     *
     * @param ntnSignalStrength The new NTN signal strength.
     */
    void onNtnSignalStrengthChanged(in NtnSignalStrength ntnSignalStrength);

    /**
     * Called when satellite capabilities of the satellite service have changed.
     *
     * @param SatelliteCapabilities The current satellite capabilities.
     */
    void onSatelliteCapabilitiesChanged(in SatelliteCapabilities capabilities);
}
