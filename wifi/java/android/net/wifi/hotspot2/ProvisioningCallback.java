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

import android.os.Handler;

/**
 * Base class for provisioning callbacks. Should be extended by applications and set when calling
 * {@link WifiManager#startSubscriptionProvisiong(OsuProvider, ProvisioningCallback, Handler)}.
 *
 * @hide
 */
public abstract class ProvisioningCallback {

    /**
     * The reason code for Provisioning Failure due to connection failure to OSU AP.
     * @hide
     */
    public static final int OSU_FAILURE_AP_CONNECTION      = 1;

    /**
     * The reason code for Provisioning Failure due to connection failure to OSU AP.
     * @hide
     */
    public static final int OSU_FAILURE_SERVER_URL_INVALID = 2;

    /**
     * The reason code for Provisioning Failure due to connection failure to OSU AP.
     * @hide
     */
    public static final int OSU_FAILURE_SERVER_CONNECTION  = 3;

    /**
     * The reason code for Provisioning Failure due to connection failure to OSU AP.
     * @hide
     */
    public static final int OSU_FAILURE_SERVER_VALIDATION  = 4;

    /**
     * The reason code for Provisioning Failure due to connection failure to OSU AP.
     * @hide
     */
    public static final int OSU_FAILURE_PROVIDER_VERIFICATION = 5;

    /**
     * The reason code for Provisioning Failure when a provisioning flow is aborted.
     * @hide
     */
    public static final int OSU_FAILURE_PROVISIONING_ABORTED = 6;

    /**
     * The reason code for Provisioning Failure when a provisioning flow is aborted.
     * @hide
     */
    public static final int OSU_FAILURE_PROVISIONING_NOT_AVAILABLE = 7;

    /**
     * The status code for Provisioning flow to indicate connecting to OSU AP
     * @hide
     */
    public static final int OSU_STATUS_AP_CONNECTING       = 1;

    /**
     * The status code for Provisioning flow to indicate connected to OSU AP
     * @hide
     */
    public static final int OSU_STATUS_AP_CONNECTED        = 2;

    /**
     * The status code for Provisioning flow to indicate connecting to OSU AP
     * @hide
     */
    public static final int OSU_STATUS_SERVER_CONNECTED    = 3;

    /**
     * The status code for Provisioning flow to indicate connecting to OSU AP
     * @hide
     */
    public static final int OSU_STATUS_SERVER_VALIDATED    = 4;

    /**
     * The status code for Provisioning flow to indicate connecting to OSU AP
     * @hide
     */
    public static final int OSU_STATUS_PROVIDER_VERIFIED   = 5;

    /**
     * Provisioning status for OSU failure
     * @param status indicates error condition
     */
    public abstract void onProvisioningFailure(int status);

    /**
     * Provisioning status when OSU is in progress
     * @param status indicates status of OSU flow
     */
    public abstract void onProvisioningStatus(int status);
}

