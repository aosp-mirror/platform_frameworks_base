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

package com.android.systemui.statusbar.pipeline.satellite.ui.model

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState

/**
 * Define the [Icon] that relates to a given satellite connection state + level. Note that for now
 * We don't need any data class box, so we can just use a simple mapping function.
 */
object SatelliteIconModel {
    fun fromConnectionState(
        connectionState: SatelliteConnectionState,
        signalStrength: Int,
    ): Icon.Resource? =
        when (connectionState) {
            // TODO(b/316635648): check if this should be null
            SatelliteConnectionState.Unknown,
            SatelliteConnectionState.Off,
            SatelliteConnectionState.On ->
                Icon.Resource(
                    res = R.drawable.ic_satellite_not_connected,
                    contentDescription = null,
                )
            SatelliteConnectionState.Connected -> fromSignalStrength(signalStrength)
        }

    /**
     * Satellite icon appropriate for when we are connected. Use [fromConnectionState] for a more
     * generally correct representation.
     */
    fun fromSignalStrength(
        signalStrength: Int,
    ): Icon.Resource? =
        // TODO(b/316634365): these need content descriptions
        when (signalStrength) {
            // No signal
            0 -> Icon.Resource(res = R.drawable.ic_satellite_connected_0, contentDescription = null)

            // Poor -> Moderate
            1,
            2 -> Icon.Resource(res = R.drawable.ic_satellite_connected_1, contentDescription = null)

            // Good -> Great
            3,
            4 -> Icon.Resource(res = R.drawable.ic_satellite_connected_2, contentDescription = null)
            else -> null
        }
}
