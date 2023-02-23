/*
 * Copyright 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.telephony.satellite.PointingInfo;

/**
 * Interface for position update and datagram transfer state change callback.
 * @hide
 */
oneway interface ISatellitePositionUpdateCallback {
    /**
     * Called when satellite datagram transfer state changed.
     *
     * @param state The new datagram transfer state.
     * @param sendPendingCount The number of datagrams that are currently being sent.
     * @param receivePendingCount The number of datagrams that are currently being received.
     * @param errorCode If datagram transfer failed, the reason for failure.
     */
    void onDatagramTransferStateChanged(in int state, in int sendPendingCount,
            in int receivePendingCount, in int errorCode);

    /**
     * Called when the satellite position changed.
     *
     * @param pointingInfo The pointing info containing the satellite location.
     */
    void onSatellitePositionChanged(in PointingInfo pointingInfo);
}
