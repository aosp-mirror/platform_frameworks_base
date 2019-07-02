/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi.hotspot2;

import android.annotation.SystemApi;
import android.net.wifi.WifiManager;
import android.os.Handler;

/**
 * Base class for provisioning callbacks. Should be extended by applications and set when calling
 * {@link WifiManager#startSubscriptionProvisioning(OsuProvider, ProvisioningCallback, Handler)}.
 *
 * @hide
 */
@SystemApi
public abstract class ProvisioningCallback {

    /**
     * The reason code for Provisioning Failure due to connection failure to OSU AP.
     */
    public static final int OSU_FAILURE_AP_CONNECTION = 1;

    /**
     * The reason code for invalid server URL address.
     */
    public static final int OSU_FAILURE_SERVER_URL_INVALID = 2;

    /**
     * The reason code for provisioning failure due to connection failure to the server.
     */
    public static final int OSU_FAILURE_SERVER_CONNECTION = 3;

    /**
     * The reason code for provisioning failure due to invalid server certificate.
     */
    public static final int OSU_FAILURE_SERVER_VALIDATION = 4;

    /**
     * The reason code for provisioning failure due to invalid service provider.
     */
    public static final int OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION = 5;

    /**
     * The reason code for provisioning failure when a provisioning flow is aborted.
     */
    public static final int OSU_FAILURE_PROVISIONING_ABORTED = 6;

    /**
     * The reason code for provisioning failure when a provisioning flow is not possible.
     */
    public static final int OSU_FAILURE_PROVISIONING_NOT_AVAILABLE = 7;

    /**
     * The reason code for provisioning failure due to invalid web url format for an OSU web page.
     */
    public static final int OSU_FAILURE_INVALID_URL_FORMAT_FOR_OSU = 8;

    /**
     * The reason code for provisioning failure when a command received is not the expected command
     * type.
     */
    public static final int OSU_FAILURE_UNEXPECTED_COMMAND_TYPE = 9;

    /**
     * The reason code for provisioning failure when a SOAP message is not the expected message
     * type.
     */
    public static final int OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_TYPE = 10;

    /**
     * The reason code for provisioning failure when a SOAP message exchange fails.
     */
    public static final int OSU_FAILURE_SOAP_MESSAGE_EXCHANGE = 11;

    /**
     * The reason code for provisioning failure when a redirect listener fails to start.
     */
    public static final int OSU_FAILURE_START_REDIRECT_LISTENER = 12;

    /**
     * The reason code for provisioning failure when a redirect listener timed out to receive a HTTP
     * redirect response.
     */
    public static final int OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER = 13;

    /**
     * The reason code for provisioning failure when there is no OSU activity to listen to
     * {@link WifiManager#ACTION_PASSPOINT_LAUNCH_OSU_VIEW} intent.
     */
    public static final int OSU_FAILURE_NO_OSU_ACTIVITY_FOUND = 14;

    /**
     * The reason code for provisioning failure when the status of a SOAP message is not the
     * expected message status.
     */
    public static final int OSU_FAILURE_UNEXPECTED_SOAP_MESSAGE_STATUS = 15;

    /**
     * The reason code for provisioning failure when there is no PPS MO.
     * MO.
     */
    public static final int OSU_FAILURE_NO_PPS_MO = 16;

    /**
     * The reason code for provisioning failure when there is no AAAServerTrustRoot node in a PPS
     * MO.
     */
    public static final int OSU_FAILURE_NO_AAA_SERVER_TRUST_ROOT_NODE = 17;

    /**
     * The reason code for provisioning failure when there is no TrustRoot node for remediation
     * server in a PPS MO.
     */
    public static final int OSU_FAILURE_NO_REMEDIATION_SERVER_TRUST_ROOT_NODE = 18;

    /**
     * The reason code for provisioning failure when there is no TrustRoot node for policy server in
     * a PPS MO.
     */
    public static final int OSU_FAILURE_NO_POLICY_SERVER_TRUST_ROOT_NODE = 19;

    /**
     * The reason code for provisioning failure when failing to retrieve trust root certificates
     * used for validating server certificate for AAA, Remediation and Policy server.
     */
    public static final int OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES = 20;

    /**
     * The reason code for provisioning failure when there is no trust root certificate for AAA
     * server.
     */
    public static final int OSU_FAILURE_NO_AAA_TRUST_ROOT_CERTIFICATE = 21;

    /**
     * The reason code for provisioning failure when a {@link PasspointConfiguration} is failed to
     * install.
     */
    public static final int OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION = 22;

    /**
     * The reason code for provisioning failure when an {@link OsuProvider} is not found for
     * provisioning.
     */
    public static final int OSU_FAILURE_OSU_PROVIDER_NOT_FOUND = 23;

    /**
     * The status code for provisioning flow to indicate connecting to OSU AP
     */
    public static final int OSU_STATUS_AP_CONNECTING = 1;

    /**
     * The status code for provisioning flow to indicate the OSU AP is connected.
     */
    public static final int OSU_STATUS_AP_CONNECTED = 2;

    /**
     * The status code for provisioning flow to indicate connecting to the server.
     */
    public static final int OSU_STATUS_SERVER_CONNECTING = 3;

    /**
     * The status code for provisioning flow to indicate the server certificate is validated.
     */
    public static final int OSU_STATUS_SERVER_VALIDATED = 4;

    /**
     * The status code for provisioning flow to indicate the server is connected
     */
    public static final int OSU_STATUS_SERVER_CONNECTED = 5;

    /**
     * The status code for provisioning flow to indicate starting the first SOAP exchange.
     */
    public static final int OSU_STATUS_INIT_SOAP_EXCHANGE = 6;

    /**
     * The status code for provisioning flow to indicate waiting for a HTTP redirect response.
     */
    public static final int OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE = 7;

    /**
     * The status code for provisioning flow to indicate a HTTP redirect response is received.
     */
    public static final int OSU_STATUS_REDIRECT_RESPONSE_RECEIVED = 8;

    /**
     * The status code for provisioning flow to indicate starting the second SOAP exchange.
     */
    public static final int OSU_STATUS_SECOND_SOAP_EXCHANGE = 9;

    /**
     * The status code for provisioning flow to indicate starting the third SOAP exchange.
     */
    public static final int OSU_STATUS_THIRD_SOAP_EXCHANGE = 10;

    /**
     * The status code for provisioning flow to indicate starting a step retrieving trust root
     * certs.
     */
    public static final int OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS = 11;

    /**
     * Provisioning status for OSU failure
     *
     * @param status indicates error condition
     */
    public abstract void onProvisioningFailure(int status);

    /**
     * Provisioning status when OSU is in progress
     *
     * @param status indicates status of OSU flow
     */
    public abstract void onProvisioningStatus(int status);

    /**
     * Provisioning complete when provisioning/remediation flow completes
     */
    public abstract void onProvisioningComplete();
}

