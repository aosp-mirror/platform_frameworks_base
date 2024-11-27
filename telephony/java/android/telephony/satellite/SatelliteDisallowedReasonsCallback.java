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
import android.annotation.NonNull;
import android.annotation.SystemApi;

import com.android.internal.telephony.flags.Flags;

/**
 * A callback class for disallowed reason of satellite change events.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_SATELLITE_SYSTEM_APIS)
public interface SatelliteDisallowedReasonsCallback {

    /**
     * Called when disallowed reason of satellite has changed.
     * @param disallowedReasons Integer array of disallowed reasons.
     */
    void onSatelliteDisallowedReasonsChanged(
            @NonNull @SatelliteManager.SatelliteDisallowedReason int[] disallowedReasons);
}
