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
enum SatelliteError {
    /**
     * The request was successfully processed.
     */
    ERROR_NONE = 0,
    /**
     * A generic error which should be used only when other specific errors cannot be used.
     */
    SATELLITE_ERROR = 1,
    /**
     * Error received from the satellite server.
     */
    SERVER_ERROR = 2,
    /**
     * Error received from the vendor service. This generic error code should be used
     * only when the error cannot be mapped to other specific service error codes.
     */
    SERVICE_ERROR = 3,
    /**
     * Error received from satellite modem. This generic error code should be used only when
     * the error cannot be mapped to other specific modem error codes.
     */
    MODEM_ERROR = 4,
    /**
     * Error received from the satellite network. This generic error code should be used only when
     * the error cannot be mapped to other specific network error codes.
     */
    NETWORK_ERROR = 5,
    /**
     * Telephony is not in a valid state to receive requests from clients.
     */
    INVALID_TELEPHONY_STATE = 6,
    /**
     * Satellite modem is not in a valid state to receive requests from clients.
     */
    INVALID_MODEM_STATE = 7,
    /**
     * Either vendor service, or modem, or Telephony framework has received a request with
     * invalid arguments from its clients.
     */
    INVALID_ARGUMENTS = 8,
    /**
     * Telephony framework failed to send a request or receive a response from the vendor service
     * or satellite modem due to internal error.
     */
    REQUEST_FAILED = 9,
    /**
     * Radio did not start or is resetting.
     */
    RADIO_NOT_AVAILABLE = 10,
    /**
     * The request is not supported by either the satellite modem or the network.
     */
    REQUEST_NOT_SUPPORTED = 11,
    /**
     * Satellite modem or network has no resources available to handle requests from clients.
     */
    NO_RESOURCES = 12,
    /**
     * Satellite service is not provisioned yet.
     */
    SERVICE_NOT_PROVISIONED = 13,
    /**
     * Satellite service provision is already in progress.
     */
    SERVICE_PROVISION_IN_PROGRESS = 14,
    /**
     * The ongoing request was aborted by either the satellite modem or the network.
     */
    REQUEST_ABORTED = 15,
    /**
     * The device/subscriber is barred from accessing the satellite service.
     */
    SATELLITE_ACCESS_BARRED = 16,
    /**
     * Satellite modem timeout to receive ACK or response from the satellite network after
     * sending a request to the network.
     */
    NETWORK_TIMEOUT = 17,
    /**
     * Satellite network is not reachable from the modem.
     */
    SATELLITE_NOT_REACHABLE = 18,
    /**
     * The device/subscriber is not authorized to register with the satellite service provider.
     */
    NOT_AUTHORIZED = 19
}
