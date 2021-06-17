/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.location.provider;

import android.annotation.SystemApi;
import android.location.Location;

/**
 * Base class for sinks to interact with FusedLocationHardware.
 *
 * <p>Default implementations allow new methods to be added without crashing
 * clients compiled against an old library version.
 *
 * @deprecated This class may no longer be used from Android P and onwards.
 * @hide
 */
@Deprecated
@SystemApi
public class FusedLocationHardwareSink {
    /**
     * Called when one or more locations are available from the FLP
     * HAL.
     */
    public void onLocationAvailable(Location[] locations) {
        // default do nothing
    }

    /**
     * Called when diagnostic data is available from the FLP HAL.
     */
    public void onDiagnosticDataAvailable(String data) {
        // default do nothing
    }

    /**
     * Called when capabilities are available from the FLP HAL.
     * Should be called once right after initialization.
     *
     * @param capabilities A bitmask of capabilities defined in
     *                     fused_location.h.
     */
    public void onCapabilities(int capabilities) {
        // default do nothing
    }

    /**
     * Called when the status changes in the underlying FLP HAL
     * implementation (the ability to compute location).  This
     * callback will only be made on version 2 or later
     * (see {@link FusedLocationHardware#getVersion()}).
     *
     * @param status One of FLP_STATUS_LOCATION_AVAILABLE or
     *               FLP_STATUS_LOCATION_UNAVAILABLE as defined in
     *               fused_location.h.
     */
    public void onStatusChanged(int status) {
        // default do nothing
    }
}
