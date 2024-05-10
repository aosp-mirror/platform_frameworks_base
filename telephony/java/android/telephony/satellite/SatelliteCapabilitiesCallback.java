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
import android.annotation.NonNull;
import android.annotation.SystemApi;

import com.android.internal.telephony.flags.Flags;

/**
 * A callback class for satellite capabilities change events.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public interface SatelliteCapabilitiesCallback {
    /**
     * Called when satellite capability has changed.
     * @param capabilities The new satellite capabilities.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    void onSatelliteCapabilitiesChanged(@NonNull SatelliteCapabilities capabilities);
}
