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

import android.telephony.satellite.stub.NTRadioTechnology;
import android.telephony.satellite.stub.PointingInfo;
import android.telephony.satellite.stub.SatelliteDatagram;
import android.telephony.satellite.stub.SatelliteError;
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
     * @param datagrams Array of new datagrams received.
     * @param pendingCount The number of datagrams that are pending.
     */
    void onSatelliteDatagramsReceived(in SatelliteDatagram[] datagrams, in int pendingCount);

    /**
     * Indicates that the satellite has pending datagrams for the device to be pulled.
     *
     * @param count Number of pending datagrams.
     */
    void onPendingDatagramCount(in int count);

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
     * Indicates that the satellite radio technology has changed.
     *
     * @param technology The current satellite radio technology.
     */
    void onSatelliteRadioTechnologyChanged(in NTRadioTechnology technology);
}
