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

package com.android.systemui.statusbar.pipeline.satellite.data

import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Device-based satellite refers to the capability of a device to connect directly to a satellite
 * network. This is in contrast to carrier-based satellite connectivity, which is a property of a
 * given mobile data subscription.
 */
interface DeviceBasedSatelliteRepository {
    /** The current status of satellite provisioning. If not false, we don't want to show an icon */
    val isSatelliteProvisioned: StateFlow<Boolean>

    /** See [SatelliteConnectionState] for available states */
    val connectionState: Flow<SatelliteConnectionState>

    /** 0-4 level (similar to wifi and mobile) */
    // @IntRange(from = 0, to = 4)
    val signalStrength: Flow<Int>

    /** Clients must observe this property, as device-based satellite is location-dependent */
    val isSatelliteAllowedForCurrentLocation: Flow<Boolean>
}
