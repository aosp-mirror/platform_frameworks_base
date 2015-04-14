/*
 * Copyright (C) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/license/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.location;

import android.location.Location;

/**
 * Fused Location hardware event sink interface.
 * This interface defines the set of events that the FusedLocationHardware provides.
 *
 * @hide
 */
interface IFusedLocationHardwareSink {
    /**
     * Event generated when a batch of location information is available.
     *
     * @param locations     The batch of location information available.
     */
    void onLocationAvailable(in Location[] locations) = 0;

    /**
     * Event generated from FLP HAL to provide diagnostic data to the platform.
     *
     * @param data      The diagnostic data provided by FLP HAL.
     */
    void onDiagnosticDataAvailable(in String data) = 1;

    /**
     * Event generated from FLP HAL to provide a mask of supported
     * capabilities.  Should be called immediatly after init.
     */
    void onCapabilities(int capabilities) = 2;

    /**
     * Event generated from FLP HAL when the status of location batching
     * changes (location is successful/unsuccessful).
     */
    void onStatusChanged(int status) = 3;
}