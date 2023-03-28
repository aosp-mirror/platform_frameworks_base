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
import android.telephony.satellite.AntennaPosition;
/**
 * {@hide}
 */
parcelable SatelliteCapabilities {
    /**
     * List of technologies supported by the satellite modem.
     */
    NTRadioTechnology[] supportedRadioTechnologies;

    /**
     * Whether UE needs to point to a satellite to send and receive data.
     */
    boolean isPointingRequired;

    /**
     * The maximum number of bytes per datagram that can be sent over satellite.
     */
    int maxBytesPerOutgoingDatagram;

    /**
     * Keys which are used to fill mAntennaPositionMap.
     */
    int[] antennaPositionKeys;

    /**
     * Antenna Position for different display modes received from satellite modem.
     */
    AntennaPosition[] antennaPositionValues;
}
