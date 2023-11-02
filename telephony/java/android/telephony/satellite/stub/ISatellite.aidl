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

import android.telephony.IBooleanConsumer;
import android.telephony.IIntegerConsumer;

import android.telephony.satellite.stub.INtnSignalStrengthConsumer;
import android.telephony.satellite.stub.ISatelliteCapabilitiesConsumer;
import android.telephony.satellite.stub.ISatelliteListener;
import android.telephony.satellite.stub.SatelliteDatagram;

/**
 * {@hide}
 */
oneway interface ISatellite {
    /**
     * Register the callback interface with satellite service.
     *
     * @param listener The callback interface to handle satellite service indications.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void setSatelliteListener(in ISatelliteListener listener);

    /**
     * Request to enable or disable the satellite service listening mode.
     * Listening mode allows the satellite service to listen for incoming pages.
     *
     * @param enable True to enable satellite listening mode and false to disable.
     * @param timeout How long the satellite modem should wait for the next incoming page before
     *                disabling listening mode.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestSatelliteListeningEnabled(in boolean enable, in int timeout,
            in IIntegerConsumer resultCallback);

    /**
     * Allow cellular modem scanning while satellite mode is on.
     * @param enabled  {@code true} to enable cellular modem while satellite mode is on
     * and {@code false} to disable
     * @param errorCallback The callback to receive the error code result of the operation.
     */
    void enableCellularModemWhileSatelliteModeIsOn(in boolean enabled,
        in IIntegerConsumer errorCallback);

    /**
     * Request to enable or disable the satellite modem and demo mode. If the satellite modem
     * is enabled, this may also disable the cellular modem, and if the satellite modem is disabled,
     * this may also re-enable the cellular modem.
     *
     * @param enableSatellite True to enable the satellite modem and false to disable.
     * @param enableDemoMode True to enable demo mode and false to disable.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestSatelliteEnabled(in boolean enableSatellite, in boolean enableDemoMode,
            in IIntegerConsumer resultCallback);

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether the satellite modem is enabled.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestIsSatelliteEnabled(in IIntegerConsumer resultCallback,
            in IBooleanConsumer callback);

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether the satellite service is supported on the device.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestIsSatelliteSupported(in IIntegerConsumer resultCallback,
            in IBooleanConsumer callback);

    /**
     * Request to get the SatelliteCapabilities of the satellite service.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive the SatelliteCapabilities of the satellite service.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestSatelliteCapabilities(in IIntegerConsumer resultCallback,
            in ISatelliteCapabilitiesConsumer callback);

    /**
     * User started pointing to the satellite.
     * The satellite service should report the satellite pointing info via
     * ISatelliteListener#onSatellitePositionChanged as the user device/satellite moves.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void startSendingSatellitePointingInfo(in IIntegerConsumer resultCallback);

    /**
     * User stopped pointing to the satellite.
     * The satellite service should stop reporting satellite pointing info to the framework.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void stopSendingSatellitePointingInfo(in IIntegerConsumer resultCallback);

    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once provisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report true.
     *
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning server
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_ABORTED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     */
    void provisionSatelliteService(in String token, in byte[] provisionData,
            in IIntegerConsumer resultCallback);

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration.
     * Once deprovisioned, ISatelliteListener#onSatelliteProvisionStateChanged should report false.
     *
     * @param token The token of the device/subscription to be deprovisioned.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_ABORTED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     */
    void deprovisionSatelliteService(in String token, in IIntegerConsumer resultCallback);

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether this device is provisioned with a satellite provider.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestIsSatelliteProvisioned(in IIntegerConsumer resultCallback,
            in IBooleanConsumer callback);

    /**
     * Poll the pending datagrams to be received over satellite.
     * The satellite service should check if there are any pending datagrams to be received over
     * satellite and report them via ISatelliteListener#onSatelliteDatagramsReceived.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_ACCESS_BARRED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     *   SatelliteResult:SATELLITE_RESULT_NOT_REACHABLE
     *   SatelliteResult:SATELLITE_RESULT_NOT_AUTHORIZED
     */
    void pollPendingSatelliteDatagrams(in IIntegerConsumer resultCallback);

    /**
     * Send datagram over satellite.
     *
     * @param datagram Datagram to send in byte format.
     * @param isEmergency Whether this is an emergency datagram.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_ABORTED
     *   SatelliteResult:SATELLITE_RESULT_ACCESS_BARRED
     *   SatelliteResult:SATELLITE_RESULT_NETWORK_TIMEOUT
     *   SatelliteResult:SATELLITE_RESULT_NOT_REACHABLE
     *   SatelliteResult:SATELLITE_RESULT_NOT_AUTHORIZED
     */
    void sendSatelliteDatagram(in SatelliteDatagram datagram, in boolean isEmergency,
            in IIntegerConsumer resultCallback);

    /**
     * Request the current satellite modem state.
     * The satellite service should report the current satellite modem state via
     * ISatelliteListener#onSatelliteModemStateChanged.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive the current satellite modem state.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestSatelliteModemState(in IIntegerConsumer resultCallback,
            in IIntegerConsumer callback);

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
     *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive whether satellite communication is allowed for the current location.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            in IIntegerConsumer resultCallback, in IBooleanConsumer callback);

    /**
     * Request to get the time after which the satellite will be visible. This is an int
     * representing the duration in seconds after which the satellite will be visible.
     * This will return 0 if the satellite is currently visible.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *                       This must only be sent when the result is not
          *                       SatelliteResult#SATELLITE_RESULT_SUCCESS.
     * @param callback If the result is SatelliteResult#SATELLITE_RESULT_SUCCESS, the callback to
     *                 receive the time after which the satellite will be visible.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     */
    void requestTimeForNextSatelliteVisibility(in IIntegerConsumer resultCallback,
            in IIntegerConsumer callback);

    /**
     * Set the non-terrestrial PLMN with lower priority than terrestrial networks.
     * MCC/MNC broadcast by the non-terrestrial networks may not be included in OPLMNwACT file on
     * SIM profile. Acquisition of satellite based system is lower priority to terrestrial
     * networks. UE shall make all attempts to acquire terrestrial service prior to camping on
     * satellite LTE service.
     *
     * @param simSlot Indicates the SIM slot to which this API will be applied. The modem will use
     *                this information to determine the relevant carrier.
     * @param carrierPlmnList The list of roaming PLMN used for connecting to satellite networks
     *                        supported by user subscription.
     * @param allSatellitePlmnList Modem should use the allSatellitePlmnList to identify satellite
     *                             PLMNs that are not supported by the carrier and make sure not to
     *                             attach to them.
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:NONE
     *   SatelliteResult:SATELLITE_RESULT_INVALID_ARGUMENTS
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:MODEM_ERR
     *   SatelliteResult:SATELLITE_RESULT_NO_RESOURCES
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
    void setSatellitePlmn(int simSlot, in List<String> carrierPlmnList,
            in List<String> allSatellitePlmnList, in IIntegerConsumer resultCallback);

    /**
     * Enable or disable satellite in the cellular modem associated with a carrier.
     * Refer setSatellitePlmn for the details of satellite PLMN scanning process.
     *
     * @param simSlot Indicates the SIM slot to which this API will be applied. The modem will use
     *                this information to determine the relevant carrier.
     * @param serial Serial number of request.
     * @param enable {@code true} to enable satellite, {@code false} to disable satellite.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
    void setSatelliteEnabledForCarrier(int simSlot, boolean satelliteEnabled,
         in IIntegerConsumer callback);

    /**
     * Check whether satellite is enabled in the cellular modem associated with a carrier.
     *
     * @param simSlot Indicates the SIM slot to which this API will be applied. The modem will use
     *                this information to determine the relevant carrier.
     * @param serial Serial number of request.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_MODEM_ERROR
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
    void requestIsSatelliteEnabledForCarrier(int simSlot, in IIntegerConsumer resultCallback,
            in IBooleanConsumer callback);

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * @param resultCallback The {@link SatelliteError} result of the operation.
     * @param callback The callback to handle the NTN signal strength changed event.
     */
    void requestSignalStrength(in IIntegerConsumer resultCallback,
            in INtnSignalStrengthConsumer callback);

    /**
     * The satellite service should report the NTN signal strength via
     * ISatelliteListener#onNtnSignalStrengthChanged when the NTN signal strength changes.
     *
     * Note: This API can be invoked multiple times. If the modem is already in the expected
     * state from a previous request, subsequent invocations may be ignored.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
     void startSendingNtnSignalStrength(in IIntegerConsumer resultCallback);

    /**
     * The satellite service should stop reporting NTN signal strength to the framework. This will
     * be called when device is screen off to save power by not letting signal strength updates to
     * wake up application processor.
     *
     * Note: This API can be invoked multiple times. If the modem is already in the expected
     * state from a previous request, subsequent invocations may be ignored.
     *
     * @param resultCallback The callback to receive the error code result of the operation.
     *
     * Valid result codes returned:
     *   SatelliteResult:SATELLITE_RESULT_SUCCESS
     *   SatelliteResult:SATELLITE_RESULT_SERVICE_ERROR
     *   SatelliteResult:SATELLITE_RESULT_INVALID_MODEM_STATE
     *   SatelliteResult:SATELLITE_RESULT_RADIO_NOT_AVAILABLE
     *   SatelliteResult:SATELLITE_RESULT_REQUEST_NOT_SUPPORTED
     */
     void stopSendingNtnSignalStrength(in IIntegerConsumer resultCallback);
}
