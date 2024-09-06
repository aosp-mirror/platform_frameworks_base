/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.internal.telephony.flags.Flags;


/**
 * A callback class for monitoring satellite communication allowed state changed events.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public interface SatelliteCommunicationAllowedStateCallback {

    /**
     * Telephony does not guarantee that whenever there is a change in communication allowed state,
     * this API will be called. Telephony does its best to detect the changes and notify its
     * listeners accordingly.
     *
     * @param isAllowed {@code true} means satellite allow state is changed,
     *                  {@code false} satellite allow state is not changed
     * @hide
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    void onSatelliteCommunicationAllowedStateChanged(boolean isAllowed);
}
