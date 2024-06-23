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

package com.android.systemui.statusbar.pipeline.satellite.shared.model

import android.telephony.satellite.SatelliteManager
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_CONNECTED
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_RETRYING
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_IDLE
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_LISTENING
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_NOT_CONNECTED
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_OFF
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE
import android.telephony.satellite.SatelliteManager.SATELLITE_MODEM_STATE_UNKNOWN

enum class SatelliteConnectionState {
    // State is unknown or undefined
    Unknown,
    // Radio is off
    Off,
    // Radio is on, but not yet connected
    On,
    // Radio is connected, aka satellite is available for use
    Connected;

    companion object {
        // TODO(b/316635648): validate these states. We don't need the level of granularity that
        //  SatelliteManager gives us.
        fun fromModemState(@SatelliteManager.SatelliteModemState modemState: Int) =
            when (modemState) {
                // Transferring data is connected
                SATELLITE_MODEM_STATE_CONNECTED,
                SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
                SATELLITE_MODEM_STATE_DATAGRAM_RETRYING -> Connected

                // Modem is on but not connected
                SATELLITE_MODEM_STATE_IDLE,
                SATELLITE_MODEM_STATE_LISTENING,
                SATELLITE_MODEM_STATE_NOT_CONNECTED -> On

                // Consider unavailable equivalent to Off
                SATELLITE_MODEM_STATE_UNAVAILABLE,
                SATELLITE_MODEM_STATE_OFF -> Off

                // Else, we don't know what's up
                SATELLITE_MODEM_STATE_UNKNOWN -> Unknown
                else -> Unknown
            }
    }
}
