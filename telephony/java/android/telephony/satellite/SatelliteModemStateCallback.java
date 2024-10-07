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

package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.SystemApi;

import com.android.internal.telephony.flags.Flags;

/**
 * A callback class for monitoring satellite modem state change events.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public interface SatelliteModemStateCallback {
    /**
     * Called when satellite modem state changes.
     * @param state The new satellite modem state.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    void onSatelliteModemStateChanged(@SatelliteManager.SatelliteModemState int state);

    /**
     * Called when the satellite emergency mode has changed.
     *
     * @param isEmergency {@code true} enabled for emergency mode, {@code false} otherwise.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ROAMING_NB_IOT_NTN)
    default void onEmergencyModeChanged(boolean isEmergency) {};

    /**
     * Indicates that the satellite registration failed with following failure code
     *
     * @param causeCode the primary failure cause code of the procedure.
     *                  For LTE (EMM), cause codes are TS 24.301 Sec 9.9.3.9
     * @hide
     */
    default void onRegistrationFailure(int causeCode) {};
}
