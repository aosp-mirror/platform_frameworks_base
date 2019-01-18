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
 * Easy Connect (DPP) Status Callback. Use this callback to get status updates (success, failure,
 * progress) from the Easy Connect operation started with
 * {@link WifiManager#startEasyConnectAsConfiguratorInitiator(String,
 * int, int, Handler, EasyConnectStatusCallback)} or
 * {@link WifiManager#startEasyConnectAsEnrolleeInitiator(String,
 * Handler, EasyConnectStatusCallback)}
 *
 * @hide
 */
@SystemApi
public abstract class EasyConnectStatusCallback {
    /**
     * Easy Connect Success event: Configuration sent (Configurator mode).
     */
    public static final int EASY_CONNECT_EVENT_SUCCESS_CONFIGURATION_SENT = 0;

    /** @hide */
    @IntDef(prefix = {"EASY_CONNECT_EVENT_SUCCESS_"}, value = {
            EASY_CONNECT_EVENT_SUCCESS_CONFIGURATION_SENT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EasyConnectSuccessStatusCode {
    }

    /**
     * Easy Connect Progress event: Initial authentication with peer succeeded.
     */
    public static final int EASY_CONNECT_EVENT_PROGRESS_AUTHENTICATION_SUCCESS = 0;

    /**
     * Easy Connect Progress event: Peer requires more time to process bootstrapping.
     */
    public static final int EASY_CONNECT_EVENT_PROGRESS_RESPONSE_PENDING = 1;

    /** @hide */
    @IntDef(prefix = {"EASY_CONNECT_EVENT_PROGRESS_"}, value = {
            EASY_CONNECT_EVENT_PROGRESS_AUTHENTICATION_SUCCESS,
            EASY_CONNECT_EVENT_PROGRESS_RESPONSE_PENDING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EasyConnectProgressStatusCode {
    }

    /**
     * Easy Connect Failure event: Scanned QR code is either not a Easy Connect URI, or the Easy
     * Connect URI has errors.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_INVALID_URI = -1;

    /**
     * Easy Connect Failure event: Bootstrapping/Authentication initialization process failure.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_AUTHENTICATION = -2;

    /**
     * Easy Connect Failure event: Both devices are implementing the same role and are incompatible.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_NOT_COMPATIBLE = -3;

    /**
     * Easy Connect Failure event: Configuration process has failed due to malformed message.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_CONFIGURATION = -4;

    /**
     * Easy Connect Failure event: Easy Connect request while in another Easy Connect exchange.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_BUSY = -5;

    /**
     * Easy Connect Failure event: No response from the peer.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_TIMEOUT = -6;

    /**
     * Easy Connect Failure event: General protocol failure.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_GENERIC = -7;

    /**
     * Easy Connect Failure event: Feature or option is not supported.
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_NOT_SUPPORTED = -8;

    /**
     * Easy Connect Failure event: Invalid network provided to Easy Connect configurator.
     * Network must either be WPA3-Personal (SAE) or WPA2-Personal (PSK).
     */
    public static final int EASY_CONNECT_EVENT_FAILURE_INVALID_NETWORK = -9;


    /** @hide */
    @IntDef(prefix = {"EASY_CONNECT_EVENT_FAILURE_"}, value = {
            EASY_CONNECT_EVENT_FAILURE_INVALID_URI,
            EASY_CONNECT_EVENT_FAILURE_AUTHENTICATION,
            EASY_CONNECT_EVENT_FAILURE_NOT_COMPATIBLE,
            EASY_CONNECT_EVENT_FAILURE_CONFIGURATION,
            EASY_CONNECT_EVENT_FAILURE_BUSY,
            EASY_CONNECT_EVENT_FAILURE_TIMEOUT,
            EASY_CONNECT_EVENT_FAILURE_GENERIC,
            EASY_CONNECT_EVENT_FAILURE_NOT_SUPPORTED,
            EASY_CONNECT_EVENT_FAILURE_INVALID_NETWORK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EasyConnectFailureStatusCode {
    }

    /**
     * Called when local Easy Connect Enrollee successfully receives a new Wi-Fi configuration from
     * the
     * peer Easy Connect configurator. This callback marks the successful end of the Easy Connect
     * current Easy Connect
     * session, and no further callbacks will be called. This callback is the successful outcome
     * of a Easy Connect flow starting with
     * {@link WifiManager#startEasyConnectAsEnrolleeInitiator(String,
     * Handler,
     * EasyConnectStatusCallback)}.
     *
     * @param newNetworkId New Wi-Fi configuration with a network ID received from the configurator
     */
    public abstract void onEnrolleeSuccess(int newNetworkId);

    /**
     * Called when a Easy Connect success event takes place, except for when configuration is
     * received from
     * an external Configurator. The callback onSuccessConfigReceived will be used in this case.
     * This callback marks the successful end of the current Easy Connect session, and no further
     * callbacks will be called. This callback is the successful outcome of a Easy Connect flow
     * starting with
     * {@link WifiManager#startEasyConnectAsConfiguratorInitiator(String, int, int, Handler,
     * EasyConnectStatusCallback)}.
     *
     * @param code Easy Connect success status code.
     */
    public abstract void onConfiguratorSuccess(@EasyConnectSuccessStatusCode int code);

    /**
     * Called when a Easy Connect Failure event takes place. This callback marks the unsuccessful
     * end of the
     * current Easy Connect session, and no further callbacks will be called.
     *
     * @param code Easy Connect failure status code.
     */
    public abstract void onFailure(@EasyConnectFailureStatusCode int code);

    /**
     * Called when Easy Connect events that indicate progress take place. Can be used by UI elements
     * to show progress.
     *
     * @param code Easy Connect progress status code.
     */
    public abstract void onProgress(@EasyConnectProgressStatusCode int code);
}
