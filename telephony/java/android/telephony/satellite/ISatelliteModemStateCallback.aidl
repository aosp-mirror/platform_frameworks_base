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

/**
 * Interface for satellite state change callback.
 * @hide
 */
oneway interface ISatelliteModemStateCallback {
    /**
     * Indicates that the satellite modem state has changed.
     *
     * @param state The current satellite modem state.
     */
    void onSatelliteModemStateChanged(in int state);

    /**
     * Indicates that the satellite emergency mode has changed.
     *
     * @param isEmergency True means satellite enabled for emergency mode, false otherwise.
     */
    void onEmergencyModeChanged(in boolean isEmergency);

    /**
     * Indicates that the satellite registration failed with following failure code
     *
     * @param causeCode the primary failure cause code of the procedure.
     *                  For LTE (EMM), cause codes are TS 24.301 Sec 9.9.3.9
     */
    void onRegistrationFailure(in int causeCode);
}
