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

package android.telephony.satellite;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceSpecificException;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.IVoidConsumer;
import com.android.internal.telephony.flags.Flags;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Manages satellite operations such as provisioning, pointing, messaging, location sharing, etc.
 * To get the object, call {@link Context#getSystemService(String)}.
 *
 * @hide
 */
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_SATELLITE)
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public final class SatelliteManager {
    private static final String TAG = "SatelliteManager";

    private static final ConcurrentHashMap<SatelliteDatagramCallback, ISatelliteDatagramCallback>
            sSatelliteDatagramCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteProvisionStateCallback,
            ISatelliteProvisionStateCallback> sSatelliteProvisionStateCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteModemStateCallback,
            ISatelliteModemStateCallback>
            sSatelliteModemStateCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteTransmissionUpdateCallback,
            ISatelliteTransmissionUpdateCallback> sSatelliteTransmissionUpdateCallbackMap =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<NtnSignalStrengthCallback, INtnSignalStrengthCallback>
            sNtnSignalStrengthCallbackMap = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SatelliteCapabilitiesCallback,
            ISatelliteCapabilitiesCallback>
            sSatelliteCapabilitiesCallbackMap = new ConcurrentHashMap<>();

    private final int mSubId;

    /**
     * Context this SatelliteManager is for.
     */
    @Nullable private final Context mContext;

    /**
     * Create an instance of the SatelliteManager.
     *
     * @param context The context the SatelliteManager belongs to.
     * @hide
     */
    public SatelliteManager(@Nullable Context context) {
        this(context, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
    }

    /**
     * Create an instance of the SatelliteManager associated with a particular subscription.
     *
     * @param context The context the SatelliteManager belongs to.
     * @param subId The subscription ID associated with the SatelliteManager.
     */
    private SatelliteManager(@Nullable Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /**
     * Exception from the satellite service containing the {@link SatelliteResult} error code.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static class SatelliteException extends Exception {
        @SatelliteResult private final int mErrorCode;

        /**
         * Create a SatelliteException with a given error code.
         *
         * @param errorCode The {@link SatelliteResult}.
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        public SatelliteException(@SatelliteResult int errorCode) {
            mErrorCode = errorCode;
        }

        /**
         * Get the error code returned from the satellite service.
         *
         * @return The {@link SatelliteResult}.
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        @SatelliteResult public int getErrorCode() {
            return mErrorCode;
        }
    }

    /**
     * Bundle key to get the response from
     * {@link #requestIsSatelliteEnabled(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_ENABLED = "satellite_enabled";

    /**
     * Bundle key to get the response from
     * {@link #requestIsDemoModeEnabled(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_DEMO_MODE_ENABLED = "demo_mode_enabled";

    /**
     * Bundle key to get the response from
     * {@link #requestIsSatelliteSupported(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_SUPPORTED = "satellite_supported";

    /**
     * Bundle key to get the response from
     * {@link #requestSatelliteCapabilities(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_CAPABILITIES = "satellite_capabilities";

    /**
     * Bundle key to get the response from
     * {@link #requestIsSatelliteProvisioned(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_PROVISIONED = "satellite_provisioned";

    /**
     * Bundle key to get the response from
     * {@link #requestIsSatelliteCommunicationAllowedForCurrentLocation(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_COMMUNICATION_ALLOWED =
            "satellite_communication_allowed";

    /**
     * Bundle key to get the response from
     * {@link #requestTimeForNextSatelliteVisibility(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_SATELLITE_NEXT_VISIBILITY = "satellite_next_visibility";

    /**
     * Bundle key to get the response from
     * {@link #requestNtnSignalStrength(Executor, OutcomeReceiver)}.
     * @hide
     */

    public static final String KEY_NTN_SIGNAL_STRENGTH = "ntn_signal_strength";

    /**
     * The request was successfully processed.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SUCCESS = 0;
    /**
     * A generic error which should be used only when other specific errors cannot be used.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_ERROR = 1;
    /**
     * Error received from the satellite server.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVER_ERROR = 2;
    /**
     * Error received from the vendor service. This generic error code should be used
     * only when the error cannot be mapped to other specific service error codes.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVICE_ERROR = 3;
    /**
     * Error received from satellite modem. This generic error code should be used only when
     * the error cannot be mapped to other specific modem error codes.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_MODEM_ERROR = 4;
    /**
     * Error received from the satellite network. This generic error code should be used only when
     * the error cannot be mapped to other specific network error codes.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NETWORK_ERROR = 5;
    /**
     * Telephony is not in a valid state to receive requests from clients.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_INVALID_TELEPHONY_STATE = 6;
    /**
     * Satellite modem is not in a valid state to receive requests from clients.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_INVALID_MODEM_STATE = 7;
    /**
     * Either vendor service, or modem, or Telephony framework has received a request with
     * invalid arguments from its clients.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_INVALID_ARGUMENTS = 8;
    /**
     * Telephony framework failed to send a request or receive a response from the vendor service
     * or satellite modem due to internal error.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_FAILED = 9;
    /**
     * Radio did not start or is resetting.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_RADIO_NOT_AVAILABLE = 10;
    /**
     * The request is not supported by either the satellite modem or the network.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_NOT_SUPPORTED = 11;
    /**
     * Satellite modem or network has no resources available to handle requests from clients.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NO_RESOURCES = 12;
    /**
     * Satellite service is not provisioned yet.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVICE_NOT_PROVISIONED = 13;
    /**
     * Satellite service provision is already in progress.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS = 14;
    /**
     * The ongoing request was aborted by either the satellite modem or the network.
     * This error is also returned when framework decides to abort current send request as one
     * of the previous send request failed.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_ABORTED = 15;
    /**
     * The device/subscriber is barred from accessing the satellite service.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_ACCESS_BARRED = 16;
    /**
     * Satellite modem timeout to receive ACK or response from the satellite network after
     * sending a request to the network.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NETWORK_TIMEOUT = 17;
    /**
     * Satellite network is not reachable from the modem.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NOT_REACHABLE = 18;
    /**
     * The device/subscriber is not authorized to register with the satellite service provider.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NOT_AUTHORIZED = 19;
    /**
     * The device does not support satellite.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_NOT_SUPPORTED = 20;

    /**
     * The current request is already in-progress.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_REQUEST_IN_PROGRESS = 21;

    /**
     * Satellite modem is currently busy due to which current request cannot be processed.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_RESULT_MODEM_BUSY = 22;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_RESULT_"}, value = {
            SATELLITE_RESULT_SUCCESS,
            SATELLITE_RESULT_ERROR,
            SATELLITE_RESULT_SERVER_ERROR,
            SATELLITE_RESULT_SERVICE_ERROR,
            SATELLITE_RESULT_MODEM_ERROR,
            SATELLITE_RESULT_NETWORK_ERROR,
            SATELLITE_RESULT_INVALID_TELEPHONY_STATE,
            SATELLITE_RESULT_INVALID_MODEM_STATE,
            SATELLITE_RESULT_INVALID_ARGUMENTS,
            SATELLITE_RESULT_REQUEST_FAILED,
            SATELLITE_RESULT_RADIO_NOT_AVAILABLE,
            SATELLITE_RESULT_REQUEST_NOT_SUPPORTED,
            SATELLITE_RESULT_NO_RESOURCES,
            SATELLITE_RESULT_SERVICE_NOT_PROVISIONED,
            SATELLITE_RESULT_SERVICE_PROVISION_IN_PROGRESS,
            SATELLITE_RESULT_REQUEST_ABORTED,
            SATELLITE_RESULT_ACCESS_BARRED,
            SATELLITE_RESULT_NETWORK_TIMEOUT,
            SATELLITE_RESULT_NOT_REACHABLE,
            SATELLITE_RESULT_NOT_AUTHORIZED,
            SATELLITE_RESULT_NOT_SUPPORTED,
            SATELLITE_RESULT_REQUEST_IN_PROGRESS,
            SATELLITE_RESULT_MODEM_BUSY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteResult {}

    /**
     * Unknown Non-Terrestrial radio technology. This generic radio technology should be used
     * only when the radio technology cannot be mapped to other specific radio technologies.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_UNKNOWN = 0;
    /**
     * 3GPP NB-IoT (Narrowband Internet of Things) over Non-Terrestrial-Networks technology.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_NB_IOT_NTN = 1;
    /**
     * 3GPP 5G NR over Non-Terrestrial-Networks technology.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_NR_NTN = 2;
    /**
     * 3GPP eMTC (enhanced Machine-Type Communication) over Non-Terrestrial-Networks technology.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_EMTC_NTN = 3;
    /**
     * Proprietary technology.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NT_RADIO_TECHNOLOGY_PROPRIETARY = 4;

    /** @hide */
    @IntDef(prefix = "NT_RADIO_TECHNOLOGY_", value = {
            NT_RADIO_TECHNOLOGY_UNKNOWN,
            NT_RADIO_TECHNOLOGY_NB_IOT_NTN,
            NT_RADIO_TECHNOLOGY_NR_NTN,
            NT_RADIO_TECHNOLOGY_EMTC_NTN,
            NT_RADIO_TECHNOLOGY_PROPRIETARY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NTRadioTechnology {}

    /** Suggested device hold position is unknown. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_UNKNOWN = 0;
    /** User is suggested to hold the device in portrait mode. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_PORTRAIT = 1;
    /** User is suggested to hold the device in landscape mode with left hand. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_LANDSCAPE_LEFT = 2;
    /** User is suggested to hold the device in landscape mode with right hand. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DEVICE_HOLD_POSITION_LANDSCAPE_RIGHT = 3;

    /** @hide */
    @IntDef(prefix = {"DEVICE_HOLD_POSITION_"}, value = {
            DEVICE_HOLD_POSITION_UNKNOWN,
            DEVICE_HOLD_POSITION_PORTRAIT,
            DEVICE_HOLD_POSITION_LANDSCAPE_LEFT,
            DEVICE_HOLD_POSITION_LANDSCAPE_RIGHT
       })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceHoldPosition {}

    /** Display mode is unknown. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_UNKNOWN = 0;
    /** Display mode of the device used for satellite communication for non-foldable phones. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_FIXED = 1;
    /** Display mode of the device used for satellite communication for foldabale phones when the
     * device is opened. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_OPENED = 2;
    /** Display mode of the device used for satellite communication for foldabable phones when the
     * device is closed. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DISPLAY_MODE_CLOSED = 3;

    /** @hide */
    @IntDef(prefix = {"ANTENNA_POSITION_"}, value = {
            DISPLAY_MODE_UNKNOWN,
            DISPLAY_MODE_FIXED,
            DISPLAY_MODE_OPENED,
            DISPLAY_MODE_CLOSED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DisplayMode {}

    /**
     * The emergency call is handed over to oem-enabled satellite SOS messaging. SOS messages are
     * sent to SOS providers, which will then forward the messages to emergency providers.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_SOS = 1;
    /**
     * The emergency call is handed over to carrier-enabled satellite T911 messaging. T911 messages
     * are sent directly to local emergency providers.
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public static final int EMERGENCY_CALL_TO_SATELLITE_HANDOVER_TYPE_T911 = 2;

    /**
     * Request to enable or disable the satellite modem and demo mode.
     * If satellite modem and cellular modem cannot work concurrently,
     * then this will disable the cellular modem if satellite modem is enabled,
     * and will re-enable the cellular modem if satellite modem is disabled.
     *
     * Demo mode is created to simulate the experience of sending and receiving messages over
     * satellite. If user enters demo mode, a request should be sent to framework to enable
     * satellite with enableDemoMode set to {code true}. Once satellite is enabled and device is
     * aligned with the satellite, user can send a message and also receive a reply in demo mode.
     * If enableSatellite is {@code false}, enableDemoMode has no impact on the behavior.
     *
     * @param enableSatellite {@code true} to enable the satellite modem and
     *                        {@code false} to disable.
     * @param enableDemoMode {@code true} to enable demo mode and {@code false} to disable.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestSatelliteEnabled(boolean enableSatellite, boolean enableDemoMode,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.requestSatelliteEnabled(mSubId, enableSatellite, enableDemoMode,
                        errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "requestSatelliteEnabled() RemoteException: ", ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite modem
     *                 is enabled and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsSatelliteEnabled(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_ENABLED)) {
                                boolean isSatelliteEnabled =
                                        resultData.getBoolean(KEY_SATELLITE_ENABLED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteEnabled)));
                            } else {
                                loge("KEY_SATELLITE_ENABLED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteEnabled(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestIsSatelliteEnabled() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get whether the satellite service demo mode is enabled.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if demo mode is enabled
     *                 and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsDemoModeEnabled(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_DEMO_MODE_ENABLED)) {
                                boolean isDemoModeEnabled =
                                        resultData.getBoolean(KEY_DEMO_MODE_ENABLED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isDemoModeEnabled)));
                            } else {
                                loge("KEY_DEMO_MODE_ENABLED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsDemoModeEnabled(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestIsDemoModeEnabled() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * <p>
     * Note: This API only checks whether the device supports the satellite feature. The result will
     * not be affected by whether the device is provisioned.
     * </p>
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite
     *                 service is supported on the device and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsSatelliteSupported(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_SUPPORTED)) {
                                boolean isSatelliteSupported =
                                        resultData.getBoolean(KEY_SATELLITE_SUPPORTED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteSupported)));
                            } else {
                                loge("KEY_SATELLITE_SUPPORTED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteSupported(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestIsSatelliteSupported() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get the {@link SatelliteCapabilities} of the satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the {@link SatelliteCapabilities} of the satellite service.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestSatelliteCapabilities(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<SatelliteCapabilities, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_CAPABILITIES)) {
                                SatelliteCapabilities capabilities =
                                        resultData.getParcelable(KEY_SATELLITE_CAPABILITIES,
                                                SatelliteCapabilities.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(capabilities)));
                            } else {
                                loge("KEY_SATELLITE_CAPABILITIES does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestSatelliteCapabilities(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestSatelliteCapabilities() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * The default state indicating that datagram transfer is idle.
     * This should be sent if there are no message transfer activity happening.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE = 0;
    /**
     * A transition state indicating that a datagram is being sent.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING = 1;
    /**
     * An end state indicating that datagram sending completed successfully.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS = 2;
    /**
     * An end state indicating that datagram sending completed with a failure.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * must be sent before reporting any additional datagram transfer state changes. All pending
     * messages will be reported as failed, to the corresponding applications.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED = 3;
    /**
     * A transition state indicating that a datagram is being received.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING = 4;
    /**
     * An end state indicating that datagram receiving completed successfully.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS = 5;
    /**
     * An end state indicating that datagram receive operation found that there are no
     * messages to be retrieved from the satellite.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_NONE = 6;
    /**
     * An end state indicating that datagram receive completed with a failure.
     * After datagram transfer completes, {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE}
     * will be sent if no more messages are pending.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED = 7;
    /**
     * A transition state indicating that Telephony is waiting for satellite modem to connect to a
     * satellite network before sending a datagram or polling for datagrams. If the satellite modem
     * successfully connects to a satellite network, either
     * {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING} or
     * {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING} will be sent. Otherwise,
     * either {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED} or
     * {@link #SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED} will be sent.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_WAITING_TO_CONNECT = 8;
    /**
     * The datagram transfer state is unknown. This generic datagram transfer state should be used
     * only when the datagram transfer state cannot be mapped to other specific datagram transfer
     * states.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_DATAGRAM_TRANSFER_STATE_UNKNOWN = -1;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_DATAGRAM_TRANSFER_STATE_"}, value = {
            SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
            SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
            SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
            SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_NONE,
            SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED,
            SATELLITE_DATAGRAM_TRANSFER_STATE_WAITING_TO_CONNECT,
            SATELLITE_DATAGRAM_TRANSFER_STATE_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteDatagramTransferState {}
    // TODO: Split into two enums for sending and receiving states

    /**
     * Satellite modem is in idle state.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_IDLE = 0;
    /**
     * Satellite modem is listening for incoming datagrams.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_LISTENING = 1;
    /**
     * Satellite modem is sending and/or receiving datagrams.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING = 2;
    /**
     * Satellite modem is retrying to send and/or receive datagrams.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_DATAGRAM_RETRYING = 3;
    /**
     * Satellite modem is powered off.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_OFF = 4;
    /**
     * Satellite modem is unavailable.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_UNAVAILABLE = 5;
    /**
     * The satellite modem is powered on but the device is not registered to a satellite cell.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_NOT_CONNECTED = 6;
    /**
     * The satellite modem is powered on and the device is registered to a satellite cell.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_CONNECTED = 7;
    /**
     * Satellite modem state is unknown. This generic modem state should be used only when the
     * modem state cannot be mapped to other specific modem states.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_MODEM_STATE_UNKNOWN = -1;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_MODEM_STATE_"}, value = {
            SATELLITE_MODEM_STATE_IDLE,
            SATELLITE_MODEM_STATE_LISTENING,
            SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
            SATELLITE_MODEM_STATE_DATAGRAM_RETRYING,
            SATELLITE_MODEM_STATE_OFF,
            SATELLITE_MODEM_STATE_UNAVAILABLE,
            SATELLITE_MODEM_STATE_NOT_CONNECTED,
            SATELLITE_MODEM_STATE_CONNECTED,
            SATELLITE_MODEM_STATE_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteModemState {}

    /**
     * Datagram type is unknown. This generic datagram type should be used only when the
     * datagram type cannot be mapped to other specific datagram types.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DATAGRAM_TYPE_UNKNOWN = 0;
    /**
     * Datagram type indicating that the datagram to be sent or received is of type SOS message.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DATAGRAM_TYPE_SOS_MESSAGE = 1;
    /**
     * Datagram type indicating that the datagram to be sent or received is of type
     * location sharing.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int DATAGRAM_TYPE_LOCATION_SHARING = 2;

    /** @hide */
    @IntDef(prefix = "DATAGRAM_TYPE_", value = {
            DATAGRAM_TYPE_UNKNOWN,
            DATAGRAM_TYPE_SOS_MESSAGE,
            DATAGRAM_TYPE_LOCATION_SHARING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DatagramType {}

    /**
     * Satellite communication restricted by user.
     * @hide
     */
    public static final int SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER = 0;

    /**
     * Satellite communication restricted by geolocation. This can be
     * triggered based upon geofence input provided by carrier to enable or disable satellite.
     */
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public static final int SATELLITE_COMMUNICATION_RESTRICTION_REASON_GEOLOCATION = 1;

    /** @hide */
    @IntDef(prefix = "SATELLITE_COMMUNICATION_RESTRICTION_REASON_", value = {
            SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER,
            SATELLITE_COMMUNICATION_RESTRICTION_REASON_GEOLOCATION
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteCommunicationRestrictionReason {}

    /**
     * Start receiving satellite transmission updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     * Satellite transmission updates are started only on {@link #SATELLITE_RESULT_SUCCESS}.
     * All other results indicate that this operation failed.
     * Once satellite transmission updates begin, position and datagram transfer state updates
     * will be sent through {@link SatelliteTransmissionUpdateCallback}.
     *
     * @param executor The executor on which the callback and error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     * @param callback The callback to notify of satellite transmission updates.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void startSatelliteTransmissionUpdates(@NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener,
            @NonNull SatelliteTransmissionUpdateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                ISatelliteTransmissionUpdateCallback internalCallback =
                        new ISatelliteTransmissionUpdateCallback.Stub() {

                            @Override
                            public void onSatellitePositionChanged(PointingInfo pointingInfo) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatellitePositionChanged(pointingInfo)));
                            }

                            @Override
                            public void onSendDatagramStateChanged(int state, int sendPendingCount,
                                    int errorCode) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSendDatagramStateChanged(
                                                state, sendPendingCount, errorCode)));
                            }

                            @Override
                            public void onReceiveDatagramStateChanged(int state,
                                    int receivePendingCount, int errorCode) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onReceiveDatagramStateChanged(
                                                state, receivePendingCount, errorCode)));
                            }
                        };
                sSatelliteTransmissionUpdateCallbackMap.put(callback, internalCallback);
                telephony.startSatelliteTransmissionUpdates(mSubId, errorCallback,
                        internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("startSatelliteTransmissionUpdates() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Stop receiving satellite transmission updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     * Satellite transmission updates are stopped and the callback is unregistered only on
     * {@link #SATELLITE_RESULT_SUCCESS}. All other results that this operation failed.
     *
     * @param callback The callback that was passed to {@link
     * #startSatelliteTransmissionUpdates(Executor, Consumer, SatelliteTransmissionUpdateCallback)}.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void stopSatelliteTransmissionUpdates(
            @NonNull SatelliteTransmissionUpdateCallback callback,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(callback);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);
        ISatelliteTransmissionUpdateCallback internalCallback =
                sSatelliteTransmissionUpdateCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            executor.execute(() -> Binder.withCleanCallingIdentity(
                                    () -> resultListener.accept(result)));
                        }
                    };
                    telephony.stopSatelliteTransmissionUpdates(mSubId, errorCallback,
                            internalCallback);
                    // TODO: Notify SmsHandler that pointing UI stopped
                } else {
                    loge("stopSatelliteTransmissionUpdates: No internal callback.");
                    executor.execute(() -> Binder.withCleanCallingIdentity(
                            () -> resultListener.accept(SATELLITE_RESULT_INVALID_ARGUMENTS)));
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("stopSatelliteTransmissionUpdates() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     *
     * @param token The token is generated by the user which is used as a unique identifier for
     *              provisioning with satellite gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning server
     * @param cancellationSignal The optional signal used by the caller to cancel the provision
     *                           request. Even when the cancellation is signaled, Telephony will
     *                           still trigger the callback to return the result of this request.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void provisionSatelliteService(@NonNull String token, @NonNull byte[] provisionData,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);
        Objects.requireNonNull(provisionData);

        ICancellationSignal cancelRemote = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                cancelRemote = telephony.provisionSatelliteService(mSubId, token, provisionData,
                        errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("provisionSatelliteService() RemoteException=" + ex);
            ex.rethrowFromSystemServer();
        }
        if (cancellationSignal != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Deprovision the device with the satellite provider.
     * This is needed if the provider allows dynamic registration. Once deprovisioned,
     * {@link SatelliteProvisionStateCallback#onSatelliteProvisionStateChanged(boolean)}
     * should report as deprovisioned.
     * For provisioning satellite service, refer to
     * {@link #provisionSatelliteService(String, byte[], CancellationSignal, Executor, Consumer)}
     *
     * @param token The token of the device/subscription to be deprovisioned.
     *              This should match with the token passed as input in
     *              {@link #provisionSatelliteService(String, byte[], CancellationSignal, Executor,
     *              Consumer)}
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void deprovisionSatelliteService(@NonNull String token,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.deprovisionSatelliteService(mSubId, token, errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("deprovisionSatelliteService() RemoteException=" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Registers for the satellite provision state changed.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite provision state changed event.
     *
     * @return The {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForSatelliteProvisionStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteProvisionStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteProvisionStateCallback internalCallback =
                        new ISatelliteProvisionStateCallback.Stub() {
                            @Override
                            public void onSatelliteProvisionStateChanged(boolean provisioned) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteProvisionStateChanged(
                                                provisioned)));
                            }
                        };
                sSatelliteProvisionStateCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteProvisionStateChanged(
                        mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteProvisionStateChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for the satellite provision state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteProvisionStateChanged(Executor, SatelliteProvisionStateCallback)}
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForSatelliteProvisionStateChanged(
            @NonNull SatelliteProvisionStateCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteProvisionStateCallback internalCallback =
                sSatelliteProvisionStateCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteProvisionStateChanged(mSubId, internalCallback);
                } else {
                    loge("unregisterForSatelliteProvisionStateChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteProvisionStateChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get whether this device is provisioned with a satellite provider.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the device is
     *                 provisioned with a satellite provider and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsSatelliteProvisioned(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_PROVISIONED)) {
                                boolean isSatelliteProvisioned =
                                        resultData.getBoolean(KEY_SATELLITE_PROVISIONED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteProvisioned)));
                            } else {
                                loge("KEY_SATELLITE_PROVISIONED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteProvisioned(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestIsSatelliteProvisioned() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite modem state changed event.
     *
     * @return The {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForSatelliteModemStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteModemStateCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteModemStateCallback internalCallback =
                        new ISatelliteModemStateCallback.Stub() {
                    @Override
                    public void onSatelliteModemStateChanged(int state) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                callback.onSatelliteModemStateChanged(state)));
                    }
                };
                sSatelliteModemStateCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteModemStateChanged(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteModemStateChanged() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for modem state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteModemStateChanged(Executor, SatelliteModemStateCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForSatelliteModemStateChanged(
            @NonNull SatelliteModemStateCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteModemStateCallback internalCallback = sSatelliteModemStateCallbackMap.remove(
                callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteModemStateChanged(mSubId, internalCallback);
                } else {
                    loge("unregisterForSatelliteModemStateChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteModemStateChanged() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register to receive incoming datagrams over satellite.
     *
     * To poll for pending satellite datagrams, refer to
     * {@link #pollPendingSatelliteDatagrams(Executor, Consumer)}
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle incoming datagrams over satellite.
     *                 This callback with be invoked when a new datagram is received from satellite.
     *
     * @return The {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForSatelliteDatagram(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteDatagramCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteDatagramCallback internalCallback =
                        new ISatelliteDatagramCallback.Stub() {
                            @Override
                            public void onSatelliteDatagramReceived(long datagramId,
                                    @NonNull SatelliteDatagram datagram, int pendingCount,
                                    @NonNull IVoidConsumer internalAck) {
                                Consumer<Void> externalAck = new Consumer<Void>() {
                                    @Override
                                    public void accept(Void result) {
                                        try {
                                            internalAck.accept();
                                        }  catch (RemoteException e) {
                                              logd("onSatelliteDatagramReceived "
                                                      + "RemoteException: " + e);
                                        }
                                    }
                                };

                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteDatagramReceived(
                                                datagramId, datagram, pendingCount, externalAck)));
                            }
                        };
                sSatelliteDatagramCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteDatagram(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteDatagram() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteDatagram(Executor, SatelliteDatagramCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForSatelliteDatagram(@NonNull SatelliteDatagramCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteDatagramCallback internalCallback =
                sSatelliteDatagramCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteDatagram(mSubId, internalCallback);
                } else {
                    loge("unregisterForSatelliteDatagram: No internal callback.");
                }
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteDatagram() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Poll pending satellite datagrams over satellite.
     *
     * This method should be called when user specifies to check incoming messages over satellite.
     * This method requests modem to check if there are any pending datagrams to be received over
     * satellite. If there are any incoming datagrams, they will be received via
     * {@link SatelliteDatagramCallback#onSatelliteDatagramReceived(long, SatelliteDatagram, int,
     * Consumer)} )}
     *
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void pollPendingSatelliteDatagrams(@NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.pollPendingSatelliteDatagrams(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("pollPendingSatelliteDatagrams() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Send datagram over satellite.
     *
     * Gateway encodes SOS message or location sharing message into a datagram and passes it as
     * input to this method. Datagram received here will be passed down to modem without any
     * encoding or encryption.
     *
     * @param datagramType datagram type indicating whether the datagram is of type
     *                     SOS_SMS or LOCATION_SHARING.
     * @param datagram encoded gateway datagram which is encrypted by the caller.
     *                 Datagram will be passed down to modem without any encoding or encryption.
     * @param needFullScreenPointingUI If set to true, this indicates pointingUI app to open in full
     *                                 screen mode if satellite communication needs pointingUI.
     *                                 If this is set to false, pointingUI may be presented to the
     *                                 user in collapsed view. Application may decide to mark this
     *                                 flag as true when the user is sending data for the first time
     *                                 or whenever there is a considerable idle time between
     *                                 satellite activity. This decision should be done based upon
     *                                 user activity and the application's ability to determine the
     *                                 best possible UX experience for the user.
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void sendSatelliteDatagram(@DatagramType int datagramType,
            @NonNull SatelliteDatagram datagram, boolean needFullScreenPointingUI,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(datagram);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.sendSatelliteDatagram(mSubId, datagramType, datagram,
                        needFullScreenPointingUI, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("sendSatelliteDatagram() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if satellite
     *                 communication is allowed for the current location and
     *                 {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_COMMUNICATION_ALLOWED)) {
                                boolean isSatelliteCommunicationAllowed =
                                        resultData.getBoolean(KEY_SATELLITE_COMMUNICATION_ALLOWED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteCommunicationAllowed)));
                            } else {
                                loge("KEY_SATELLITE_COMMUNICATION_ALLOWED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestIsSatelliteCommunicationAllowedForCurrentLocation(mSubId,
                        receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestIsSatelliteCommunicationAllowedForCurrentLocation() RemoteException: "
                    + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get the duration in seconds after which the satellite will be visible.
     * This will be {@link Duration#ZERO} if the satellite is currently visible.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the time after which the satellite will be visible.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestTimeForNextSatelliteVisibility(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Duration, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_SATELLITE_NEXT_VISIBILITY)) {
                                int nextVisibilityDuration =
                                        resultData.getInt(KEY_SATELLITE_NEXT_VISIBILITY);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(
                                                Duration.ofSeconds(nextVisibilityDuration))));
                            } else {
                                loge("KEY_SATELLITE_NEXT_VISIBILITY does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestTimeForNextSatelliteVisibility(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestTimeForNextSatelliteVisibility() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Inform whether the device is aligned with the satellite for demo mode.
     *
     * Framework can send datagram to modem only when device is aligned with the satellite.
     * This method helps framework to simulate the experience of sending datagram over satellite.
     *
     * @param isAligned {@true} Device is aligned with the satellite for demo mode
     *                  {@false} Device is not aligned with the satellite for demo mode
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void setDeviceAlignedWithSatellite(boolean isAligned) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                telephony.setDeviceAlignedWithSatellite(mSubId, isAligned);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("informDeviceAlignedToSatellite() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * User request to enable or disable carrier supported satellite plmn scan and attach by modem.
     * <p>
     * This API should be called by only settings app to pass down the user input for
     * enabling/disabling satellite. This user input will be persisted across device reboots.
     * <p>
     * Satellite will be enabled only when the following conditions are met:
     * <ul>
     * <li>Users want to enable it.</li>
     * <li>There is no satellite communication restriction, which is added by
     * {@link #addSatelliteAttachRestrictionForCarrier(int, Executor, Consumer)}</li>
     * <li>The carrier config {@link
     * android.telephony.CarrierConfigManager#KEY_SATELLITE_ATTACH_SUPPORTED_BOOL} is set to
     * {@code true}.</li>
     * </ul>
     *
     * @param subId The subscription ID of the carrier.
     * @param enableSatellite {@code true} to enable the satellite and {@code false} to disable.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void requestSatelliteAttachEnabledForCarrier(int subId, boolean enableSatellite,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        if (enableSatellite) {
            removeSatelliteAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, executor, resultListener);
        } else {
            addSatelliteAttachRestrictionForCarrier(subId,
                    SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER, executor, resultListener);
        }
    }

    /**
     * Request to get whether the carrier supported satellite plmn scan and attach by modem is
     * enabled by user.
     *
     * @param subId The subscription ID of the carrier.
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite
     *                 is enabled and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void requestIsSatelliteAttachEnabledForCarrier(int subId,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Boolean, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        Set<Integer> restrictionReason = getSatelliteAttachRestrictionReasonsForCarrier(subId);
        executor.execute(() -> callback.onResult(
                !restrictionReason.contains(SATELLITE_COMMUNICATION_RESTRICTION_REASON_USER)));
    }

    /**
     * Add a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem.
     *
     * @param subId The subscription ID of the carrier.
     * @param reason Reason for disallowing satellite communication.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void addSatelliteAttachRestrictionForCarrier(int subId,
            @SatelliteCommunicationRestrictionReason int reason,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.addSatelliteAttachRestrictionForCarrier(subId, reason, errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("addSatelliteAttachRestrictionForCarrier() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a restriction reason for disallowing carrier supported satellite plmn scan and attach
     * by modem.
     *
     * @param subId The subscription ID of the carrier.
     * @param reason Reason for disallowing satellite communication.
     * @param executor The executor on which the error code listener will be called.
     * @param resultListener Listener for the {@link SatelliteResult} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    public void removeSatelliteAttachRestrictionForCarrier(int subId,
            @SatelliteCommunicationRestrictionReason int reason,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteResult @NonNull Consumer<Integer> resultListener) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };
                telephony.removeSatelliteAttachRestrictionForCarrier(subId, reason, errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("removeSatelliteAttachRestrictionForCarrier() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get reasons for disallowing satellite attach, as requested by
     * {@link #addSatelliteAttachRestrictionForCarrier(int, Executor, Consumer)}
     *
     * @param subId The subscription ID of the carrier.
     * @return Set of reasons for disallowing satellite communication.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws IllegalArgumentException if the subscription is invalid.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteCommunicationRestrictionReason
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @NonNull public Set<Integer> getSatelliteAttachRestrictionReasonsForCarrier(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int[] receivedArray =
                        telephony.getSatelliteAttachRestrictionReasonsForCarrier(subId);
                if (receivedArray.length == 0) {
                    logd("receivedArray is empty, create empty set");
                    return new HashSet<>();
                } else {
                    return Arrays.stream(receivedArray).boxed().collect(Collectors.toSet());
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getSatelliteAttachRestrictionReasonsForCarrier() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return new HashSet<>();
    }

    /**
     * Request to get the signal strength of the satellite connection.
     *
     * <p>
     * Note: This API is specifically designed for OEM enabled satellite connectivity only.
     * For satellite connectivity enabled using carrier roaming, please refer to
     * {@link android.telephony.TelephonyCallback.SignalStrengthsListener}, and
     * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
     * </p>
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered. If the request is
     * successful, {@link OutcomeReceiver#onResult(Object)} will return an instance of
     * {@link NtnSignalStrength} with a value of {@link NtnSignalStrength.NtnSignalStrengthLevel}
     * The {@link NtnSignalStrength#NTN_SIGNAL_STRENGTH_NONE} will be returned if there is no
     * signal strength data available.
     * If the request is not successful, {@link OutcomeReceiver#onError(Throwable)} will return a
     * {@link SatelliteException} with the {@link SatelliteResult}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available or
     * satellite is not supported.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void requestNtnSignalStrength(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<NtnSignalStrength, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_RESULT_SUCCESS) {
                            if (resultData.containsKey(KEY_NTN_SIGNAL_STRENGTH)) {
                                NtnSignalStrength ntnSignalStrength =
                                        resultData.getParcelable(KEY_NTN_SIGNAL_STRENGTH,
                                                NtnSignalStrength.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(ntnSignalStrength)));
                            } else {
                                loge("KEY_NTN_SIGNAL_STRENGTH does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(new SatelliteException(
                                                SATELLITE_RESULT_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestNtnSignalStrength(mSubId, receiver);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestNtnSignalStrength() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Registers for NTN signal strength changed from satellite modem.
     * If the registration operation is not successful, a {@link SatelliteException} that contains
     * {@link SatelliteResult} will be thrown.
     *
     * <p>
     * Note: This API is specifically designed for OEM enabled satellite connectivity only.
     * For satellite connectivity enabled using carrier roaming, please refer to
     * {@link android.telephony.TelephonyCallback.SignalStrengthsListener}, and
     * {@link TelephonyManager#registerTelephonyCallback(Executor, TelephonyCallback)}.
     * </p>
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the NTN signal strength changed event.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     * @throws SatelliteException if the callback registration operation fails.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void registerForNtnSignalStrengthChanged(@NonNull @CallbackExecutor Executor executor,
            @NonNull NtnSignalStrengthCallback callback) throws SatelliteException {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                INtnSignalStrengthCallback internalCallback =
                        new INtnSignalStrengthCallback.Stub() {
                            @Override
                            public void onNtnSignalStrengthChanged(
                                    NtnSignalStrength ntnSignalStrength) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onNtnSignalStrengthChanged(
                                                ntnSignalStrength)));
                            }
                        };
                telephony.registerForNtnSignalStrengthChanged(mSubId, internalCallback);
                sNtnSignalStrengthCallbackMap.put(callback, internalCallback);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (ServiceSpecificException ex) {
            logd("registerForNtnSignalStrengthChanged() registration fails: " + ex.errorCode);
            throw new SatelliteException(ex.errorCode);
        } catch (RemoteException ex) {
            loge("registerForNtnSignalStrengthChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters for NTN signal strength changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * <p>
     * Note: This API is specifically designed for OEM enabled satellite connectivity only.
     * For satellite connectivity enabled using carrier roaming, please refer to
     * {@link TelephonyManager#unregisterTelephonyCallback(TelephonyCallback)}..
     * </p>
     *
     * @param callback The callback that was passed to.
     * {@link #registerForNtnSignalStrengthChanged(Executor, NtnSignalStrengthCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the callback is not valid or has already been
     * unregistered.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForNtnSignalStrengthChanged(@NonNull NtnSignalStrengthCallback callback) {
        Objects.requireNonNull(callback);
        INtnSignalStrengthCallback internalCallback =
                sNtnSignalStrengthCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForNtnSignalStrengthChanged(mSubId, internalCallback);
                } else {
                    loge("unregisterForNtnSignalStrengthChanged: No internal callback.");
                    throw new IllegalArgumentException("callback is not valid");
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForNtnSignalStrengthChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Registers for satellite capabilities change event from the satellite service.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to handle the satellite capabilities changed event.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @SatelliteResult public int registerForSatelliteCapabilitiesChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatelliteCapabilitiesCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteCapabilitiesCallback internalCallback =
                        new ISatelliteCapabilitiesCallback.Stub() {
                            @Override
                            public void onSatelliteCapabilitiesChanged(
                                    SatelliteCapabilities capabilities) {
                                executor.execute(() -> Binder.withCleanCallingIdentity(
                                        () -> callback.onSatelliteCapabilitiesChanged(
                                                capabilities)));
                            }
                        };
                sSatelliteCapabilitiesCallbackMap.put(callback, internalCallback);
                return telephony.registerForSatelliteCapabilitiesChanged(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteCapabilitiesChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_RESULT_REQUEST_FAILED;
    }

    /**
     * Unregisters for satellite capabilities change event from the satellite service.
     * If callback was not registered before, the request will be ignored.
     *
     * @param callback The callback that was passed to.
     * {@link #registerForSatelliteCapabilitiesChanged(Executor, SatelliteCapabilitiesCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void unregisterForSatelliteCapabilitiesChanged(
            @NonNull SatelliteCapabilitiesCallback callback) {
        Objects.requireNonNull(callback);
        ISatelliteCapabilitiesCallback internalCallback =
                sSatelliteCapabilitiesCallbackMap.remove(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                if (internalCallback != null) {
                    telephony.unregisterForSatelliteCapabilitiesChanged(mSubId, internalCallback);
                } else {
                    loge("unregisterForSatelliteCapabilitiesChanged: No internal callback.");
                }
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteCapabilitiesChanged() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get all satellite PLMNs for which attach is enable for carrier.
     *
     * @param subId subId The subscription ID of the carrier.
     *
     * @return List of plmn for carrier satellite service. If no plmn is available, empty list will
     * be returned.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @FlaggedApi(Flags.FLAG_CARRIER_ENABLED_SATELLITE_FLAG)
    @NonNull public List<String> getAllSatellitePlmnsForCarrier(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.getAllSatellitePlmnsForCarrier(subId);
            } else {
                throw new IllegalStateException("Telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getAllSatellitePlmnsForCarrier() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return new ArrayList<>();
    }

    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(TelephonyFrameworkInitializer
                .getTelephonyServiceManager()
                .getTelephonyServiceRegisterer()
                .get());
        if (binder == null) {
            throw new RuntimeException("Could not find Telephony Service.");
        }
        return binder;
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }
}
