/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.os.Handler;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * DPP Status Callback. Use this callback to get status updates (success, failure, progress)
 * from the DPP operation started with {@link WifiManager#startDppAsConfiguratorInitiator(String,
 * int, int, Handler, DppStatusCallback)} or {@link WifiManager#startDppAsEnrolleeInitiator(String,
 * Handler, DppStatusCallback)}
 * @hide
 */
@SystemApi
public abstract class DppStatusCallback {
    /**
     * DPP Success event: Configuration sent (Configurator mode).
     */
    public static final int DPP_EVENT_SUCCESS_CONFIGURATION_SENT = 0;

    /** @hide */
    @IntDef(prefix = { "DPP_EVENT_SUCCESS_" }, value = {
            DPP_EVENT_SUCCESS_CONFIGURATION_SENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DppSuccessStatusCode {}

    /**
     * DPP Progress event: Initial authentication with peer succeeded.
     */
    public static final int DPP_EVENT_PROGRESS_AUTHENTICATION_SUCCESS = 0;

    /**
     * DPP Progress event: Peer requires more time to process bootstrapping.
     */
    public static final int DPP_EVENT_PROGRESS_RESPONSE_PENDING = 1;

    /** @hide */
    @IntDef(prefix = { "DPP_EVENT_PROGRESS_" }, value = {
            DPP_EVENT_PROGRESS_AUTHENTICATION_SUCCESS,
            DPP_EVENT_PROGRESS_RESPONSE_PENDING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DppProgressStatusCode {}

    /**
     * DPP Failure event: Scanned QR code is either not a DPP URI, or the DPP URI has errors.
     */
    public static final int DPP_EVENT_FAILURE_INVALID_URI = -1;

    /**
     * DPP Failure event: Bootstrapping/Authentication initialization process failure.
     */
    public static final int DPP_EVENT_FAILURE_AUTHENTICATION = -2;

    /**
     * DPP Failure event: Both devices are implementing the same role and are incompatible.
     */
    public static final int DPP_EVENT_FAILURE_NOT_COMPATIBLE = -3;

    /**
     * DPP Failure event: Configuration process has failed due to malformed message.
     */
    public static final int DPP_EVENT_FAILURE_CONFIGURATION = -4;

    /**
     * DPP Failure event: DPP request while in another DPP exchange.
     */
    public static final int DPP_EVENT_FAILURE_BUSY = -5;

    /**
     * DPP Failure event: No response from the peer.
     */
    public static final int DPP_EVENT_FAILURE_TIMEOUT = -6;

    /**
     * DPP Failure event: General protocol failure.
     */
    public static final int DPP_EVENT_FAILURE = -7;

    /**
     * DPP Failure event: Feature or option is not supported.
     */
    public static final int DPP_EVENT_FAILURE_NOT_SUPPORTED = -8;

    /**
     * DPP Failure event: Invalid network provided to DPP configurator.
     * Network must either be WPA3-Personal (SAE) or WPA2-Personal (PSK).
     */
    public static final int DPP_EVENT_FAILURE_INVALID_NETWORK = -9;


    /** @hide */
    @IntDef(prefix = {"DPP_EVENT_FAILURE_"}, value = {
            DPP_EVENT_FAILURE_INVALID_URI,
            DPP_EVENT_FAILURE_AUTHENTICATION,
            DPP_EVENT_FAILURE_NOT_COMPATIBLE,
            DPP_EVENT_FAILURE_CONFIGURATION,
            DPP_EVENT_FAILURE_BUSY,
            DPP_EVENT_FAILURE_TIMEOUT,
            DPP_EVENT_FAILURE,
            DPP_EVENT_FAILURE_NOT_SUPPORTED,
            DPP_EVENT_FAILURE_INVALID_NETWORK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DppFailureStatusCode {
    }

    /**
     * Called when local DPP Enrollee successfully receives a new Wi-Fi configuration from the
     * peer DPP configurator. This callback marks the successful end of the DPP current DPP
     * session, and no further callbacks will be called. This callback is the successful outcome
     * of a DPP flow starting with {@link WifiManager#startDppAsEnrolleeInitiator(String, Handler,
     * DppStatusCallback)}.
     *
     * @param newNetworkId New Wi-Fi configuration with a network ID received from the configurator
     */
    public abstract void onEnrolleeSuccess(int newNetworkId);

    /**
     * Called when a DPP success event takes place, except for when configuration is received from
     * an external Configurator. The callback onSuccessConfigReceived will be used in this case.
     * This callback marks the successful end of the current DPP session, and no further
     * callbacks will be called. This callback is the successful outcome of a DPP flow starting with
     * {@link WifiManager#startDppAsConfiguratorInitiator(String, int, int, Handler,
     * DppStatusCallback)}.
     *
     * @param code DPP success status code.
     */
    public abstract void onConfiguratorSuccess(@DppSuccessStatusCode int code);

    /**
     * Called when a DPP Failure event takes place. This callback marks the unsuccessful end of the
     * current DPP session, and no further callbacks will be called.
     *
     * @param code DPP failure status code.
     */
    public abstract void onFailure(@DppFailureStatusCode int code);

    /**
     * Called when DPP events that indicate progress take place. Can be used by UI elements
     * to show progress.
     *
     * @param code DPP progress status code.
     */
    public abstract void onProgress(@DppProgressStatusCode int code);
}
