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

import android.telephony.satellite.stub.ISatelliteCapabilitiesConsumer;
import android.telephony.satellite.stub.ISatelliteListener;
import android.telephony.satellite.stub.SatelliteDatagram;

import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntegerConsumer;

/**
 * {@hide}
 */
oneway interface ISatellite {
    /**
     * Register the callback interface with satellite service.
     *
     * @param listener The callback interface to handle satellite service indications.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void setSatelliteListener(in ISatelliteListener listener, in IIntegerConsumer errorCallback);

    /**
     * Request to enable or disable the satellite service listening mode.
     * Listening mode allows the satellite service to listen for incoming pages.
     *
     * @param enable True to enable satellite listening mode and false to disable.
     * @param isDemoMode Whether demo mode is enabled.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestSatelliteListeningEnabled(in boolean enable, in boolean isDemoMode,
            in IIntegerConsumer errorCallback);

    /**
     * Request to enable or disable the satellite modem. If the satellite modem is enabled,
     * this will also disable the cellular modem, and if the satellite modem is disabled,
     * this will also re-enable the cellular modem.
     *
     * @param enable True to enable the satellite modem and false to disable.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestSatelliteEnabled(in boolean enabled, in IIntegerConsumer errorCallback);

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether the satellite modem is enabled.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestIsSatelliteEnabled(in IIntegerConsumer errorCallback, in IBooleanConsumer callback);

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether the satellite service is supported on the device.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestIsSatelliteSupported(in IIntegerConsumer errorCallback,
            in IBooleanConsumer callback);

    /**
     * Request to get the SatelliteCapabilities of the satellite service.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the SatelliteCapabilities of the satellite service.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestSatelliteCapabilities(in IIntegerConsumer errorCallback,
            in ISatelliteCapabilitiesConsumer callback);

    /**
     * User started pointing to the satellite.
     * The satellite service should report the satellite pointing info via
     * ISatelliteListener#onSatellitePositionChanged as the user device/satellite moves.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void startSendingSatellitePointingInfo(in IIntegerConsumer errorCallback);

    /**
     * User stopped pointing to the satellite.
     * The satellite service should stop reporting satellite pointing info to the framework.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void stopSendingSatellitePointingInfo(in IIntegerConsumer errorCallback);

    /**
     * Request to get the maximum number of characters per MO text message on satellite.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the maximum number of characters per MO text message on satellite.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestMaxCharactersPerMOTextMessage(in IIntegerConsumer errorCallback,
            in IIntegerConsumer callback);

    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once provisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report true.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:REQUEST_ABORTED
     *   SatelliteError:NETWORK_TIMEOUT
     */
    void provisionSatelliteService(in String token, in IIntegerConsumer errorCallback);

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once deprovisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report false.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:REQUEST_ABORTED
     *   SatelliteError:NETWORK_TIMEOUT
     */
    void deprovisionSatelliteService(in String token, in IIntegerConsumer errorCallback);

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether this device is provisioned with a satellite provider.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestIsSatelliteProvisioned(in IIntegerConsumer errorCallback,
            in IBooleanConsumer callback);

    /**
     * Poll the pending datagrams to be received over satellite.
     * The satellite service should check if there are any pending datagrams to be received over
     * satellite and report them via ISatelliteListener#onSatelliteDatagramsReceived.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:SATELLITE_ACCESS_BARRED
     *   SatelliteError:NETWORK_TIMEOUT
     *   SatelliteError:SATELLITE_NOT_REACHABLE
     *   SatelliteError:NOT_AUTHORIZED
     */
    void pollPendingSatelliteDatagrams(in IIntegerConsumer errorCallback);

    /**
     * Send datagram over satellite.
     *
     * @param datagram Datagram to send in byte format.
     * @param isDemoMode Whether demo mode is enabled.
     * @param isEmergency Whether this is an emergency datagram.
     * @param errorCallback The callback to receive the error code result of the operation.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:NETWORK_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     *   SatelliteError:REQUEST_ABORTED
     *   SatelliteError:SATELLITE_ACCESS_BARRED
     *   SatelliteError:NETWORK_TIMEOUT
     *   SatelliteError:SATELLITE_NOT_REACHABLE
     *   SatelliteError:NOT_AUTHORIZED
     */
    void sendSatelliteDatagram(in SatelliteDatagram datagram, in boolean isDemoMode,
            in boolean isEmergency, in IIntegerConsumer errorCallback);

    /**
     * Request the current satellite modem state.
     * The satellite service should report the current satellite modem state via
     * ISatelliteListener#onSatelliteModemStateChanged.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the current satellite modem state.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestSatelliteModemState(in IIntegerConsumer errorCallback,
            in IIntegerConsumer callback);

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 whether satellite communication is allowed for the current location.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            in IIntegerConsumer errorCallback, in IBooleanConsumer callback);

    /**
     * Request to get the time after which the satellite will be visible. This is an int
     * representing the duration in seconds after which the satellite will be visible.
     * This will return 0 if the satellite is currently visible.
     *
     * @param errorCallback The callback to receive the error code result of the operation.
     *                      This must only be sent when the result is not SatelliteError#ERROR_NONE.
     * @param callback If the result is SatelliteError#ERROR_NONE, the callback to receive
     *                 the time after which the satellite will be visible.
     *
     * Valid error codes returned:
     *   SatelliteError:ERROR_NONE
     *   SatelliteError:SERVICE_ERROR
     *   SatelliteError:MODEM_ERROR
     *   SatelliteError:INVALID_MODEM_STATE
     *   SatelliteError:INVALID_ARGUMENTS
     *   SatelliteError:RADIO_NOT_AVAILABLE
     *   SatelliteError:REQUEST_NOT_SUPPORTED
     *   SatelliteError:NO_RESOURCES
     */
    void requestTimeForNextSatelliteVisibility(in IIntegerConsumer errorCallback,
            in IIntegerConsumer callback);
}
