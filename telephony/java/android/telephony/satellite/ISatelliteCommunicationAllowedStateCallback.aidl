/*
 * Copyright 2024 The Android Open Source Project
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
 * Interface for satellite communication allowed state callback.
 * @hide
 */
oneway interface ISatelliteCommunicationAllowedStateCallback {
    /**
     * Telephony does not guarantee that whenever there is a change in communication allowed
     * state, this API will be called. Telephony does its best to detect the changes and notify
     * its listners accordingly.
     *
     * @param allowed whether satellite communication state or not
     */
    void onSatelliteCommunicationAllowedStateChanged(in boolean isAllowed);
}
