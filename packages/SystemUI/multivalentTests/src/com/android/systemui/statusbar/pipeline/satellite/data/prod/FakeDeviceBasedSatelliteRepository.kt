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

package com.android.systemui.statusbar.pipeline.satellite.data.prod

import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState.Off
import kotlinx.coroutines.flow.MutableStateFlow

class FakeDeviceBasedSatelliteRepository() : DeviceBasedSatelliteRepository {
    override val isSatelliteProvisioned = MutableStateFlow(true)

    override val connectionState = MutableStateFlow(Off)

    override val signalStrength = MutableStateFlow(0)

    override val isSatelliteAllowedForCurrentLocation = MutableStateFlow(false)
}
