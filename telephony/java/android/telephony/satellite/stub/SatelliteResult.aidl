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
enum SatelliteResult {
    /**
     * The request was successfully processed.
     */
    SATELLITE_RESULT_SUCCESS = 0,
    /**
     * A generic error which should be used only when other specific errors cannot be used.
     */
    SATELLITE_RESULT_ERROR = 1,
    /**
     * Error received from the satellite server.
     */
    SATELLITE_RESULT_SERVER_ERROR = 2,
    /**
     * Error received from the vendor service. This generic error code should be used
     * only when the error cannot be mapped to other specific service error codes.
     */
    SATELLITE_RESULT_SERVICE_ERROR = 3,
    /**
     * Error received from satellite modem. This generic error code should be used only when
     * the error cannot be mapped to other specific modem error codes.
     */
    SATELLITE_RESULT_MODEM_ERROR = 4,
    /**
     * Error received from the satellite network. This generic error code should be used only when
     * the error cannot be mapped to other specific network error codes.
     */
    SATELLITE_RESULT_NETWORK_ERROR = 5,
    /**
     * Satellite modem is not in a valid state to receive requests from clients.
     */
    SATELLITE_RESULT_INVALID_MODEM_STATE = 6,
    /**
     * Either vendor service, or modem, or Telephony framework has received a request with
     * invalid arguments from its clients.
     */
    SATELLITE_RESULT_INVALID_ARGUMENTS = 7,
    /**
     * Telephony framework failed to send a request or receive a response from the vendor service
     * or satellite modem due to internal error.
     */
    SATELLITE_RESULT_REQUEST_FAILED = 8,
    /**
     * Radio did not start or is resetting.
     */
    SATELLITE_RESULT_RADIO_NOT_AVAILABLE = 9,
    /**
     * The request is not supported by either the satellite modem or the network.
     */
    SATELLITE_RESULT_REQUEST_NOT_SUPPORTED = 10,
    /**
     * Satellite modem or network has no resources available to handle requests from clients.
     */
    SATELLITE_RESULT_NO_RESOURCES = 11,
    /**
     * Satellite service is not provisioned yet.
     */
    SATELLITE_RESULT_SERVICE_NOT_PROVISIONED = 12,
    /**
     * Satellite service provision is already in progress.
     */
    SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS = 13,
    /**
     * The ongoing request was aborted by either the satellite modem or the network.
     */
    SATELLITE_RESULT_REQUEST_ABORTED = 14,
    /**
     * The device/subscriber is barred from accessing the satellite service.
     */
    SATELLITE_RESULT_ACCESS_BARRED = 15,
    /**
     * Satellite modem timeout to receive ACK or response from the satellite network after
     * sending a request to the network.
     */
    SATELLITE_RESULT_NETWORK_TIMEOUT = 16,
    /**
     * Satellite network is not reachable from the modem.
     */
    SATELLITE_RESULT_NOT_REACHABLE = 17,
    /**
     * The device/subscriber is not authorized to register with the satellite service provider.
     */
    SATELLITE_RESULT_NOT_AUTHORIZED = 18
}
