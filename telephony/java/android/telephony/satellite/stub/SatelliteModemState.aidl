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

package android.telephony.satellite.stub;

/**
 * {@hide}
 */
@Backing(type="int")
enum SatelliteModemState {
    /**
     * Satellite modem is in idle state.
     */
    SATELLITE_MODEM_STATE_IDLE = 0,
    /**
     * Satellite modem is listening for incoming datagrams.
     */
    SATELLITE_MODEM_STATE_LISTENING = 1,
    /**
     * Satellite modem is sending and/or receiving datagrams.
     */
    SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING = 2,
    /**
     * Satellite modem is retrying to send and/or receive datagrams.
     */
    SATELLITE_MODEM_STATE_DATAGRAM_RETRYING = 3,
    /**
     * Satellite modem is powered off.
     */
    SATELLITE_MODEM_STATE_OFF = 4,
    /**
     * Satellite modem is unavailable.
     */
    SATELLITE_MODEM_STATE_UNAVAILABLE = 5,
    /**
     * The satellite modem is powered on but the device is not registered to a satellite cell.
     */
    SATELLITE_MODEM_STATE_NOT_CONNECTED = 6,
    /**
     * The satellite modem is powered on and the device is registered to a satellite cell.
     */
    SATELLITE_MODEM_STATE_CONNECTED = 7,
    /**
     * Satellite modem state is unknown. This generic modem state should be used only when the
     * modem state cannot be mapped to other specific modem states.
     */
    SATELLITE_MODEM_STATE_UNKNOWN = -1,
}
