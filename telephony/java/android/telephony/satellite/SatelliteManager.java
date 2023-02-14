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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;

import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.ITelephony;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages satellite operations such as provisioning, pointing, messaging, location sharing, etc.
 * To get the object, call {@link Context#getSystemService(String)}.
 *
 * @hide
 */
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_SATELLITE)
public class SatelliteManager {
    private static final String TAG = "SatelliteManager";

    private final int mSubId;

    /**
     * Context this SatelliteManager is for.
     */
    @Nullable private final Context mContext;

    /**
     * Create an instance of the SatelliteManager.
     *
     * @param context The context the SatelliteManager belongs to.
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
     * Exception from the satellite service containing the {@link SatelliteError} error code.
     */
    public static class SatelliteException extends Exception {
        @SatelliteError private final int mErrorCode;

        /**
         * Create a SatelliteException with a given error code.
         *
         * @param errorCode The {@link SatelliteError}.
         */
        public SatelliteException(@SatelliteError int errorCode) {
            mErrorCode = errorCode;
        }

        /**
         * Get the error code returned from the satellite service.
         *
         * @return The {@link SatelliteError}.
         */
        @SatelliteError public int getErrorCode() {
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
     * {@link #requestMaxCharactersPerSatelliteTextMessage(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_MAX_CHARACTERS_PER_SATELLITE_TEXT =
            "max_characters_per_satellite_text";

    /**
     * Bundle key to get the response from
     * {@link #requestIsSatelliteProvisioned(Executor, OutcomeReceiver)}.
     * @hide
     */
    public static final String KEY_SATELLITE_PROVISIONED = "satellite_provisioned";

    /**
     * The request was successfully processed.
     */
    public static final int SATELLITE_ERROR_NONE = 0;
    /**
     * A generic error which should be used only when other specific errors cannot be used.
     */
    public static final int SATELLITE_ERROR = 1;
    /**
     * Error received from the satellite server.
     */
    public static final int SATELLITE_SERVER_ERROR = 2;
    /**
     * Error received from the vendor service. This generic error code should be used
     * only when the error cannot be mapped to other specific service error codes.
     */
    public static final int SATELLITE_SERVICE_ERROR = 3;
    /**
     * Error received from satellite modem. This generic error code should be used only when
     * the error cannot be mapped to other specific modem error codes.
     */
    public static final int SATELLITE_MODEM_ERROR = 4;
    /**
     * Error received from the satellite network. This generic error code should be used only when
     * the error cannot be mapped to other specific network error codes.
     */
    public static final int SATELLITE_NETWORK_ERROR = 5;
    /**
     * Telephony is not in a valid state to receive requests from clients.
     */
    public static final int SATELLITE_INVALID_TELEPHONY_STATE = 6;
    /**
     * Satellite modem is not in a valid state to receive requests from clients.
     */
    public static final int SATELLITE_INVALID_MODEM_STATE = 7;
    /**
     * Either vendor service, or modem, or Telephony framework has received a request with
     * invalid arguments from its clients.
     */
    public static final int SATELLITE_INVALID_ARGUMENTS = 8;
    /**
     * Telephony framework failed to send a request or receive a response from the vendor service
     * or satellite modem due to internal error.
     */
    public static final int SATELLITE_REQUEST_FAILED = 9;
    /**
     * Radio did not start or is resetting.
     */
    public static final int SATELLITE_RADIO_NOT_AVAILABLE = 10;
    /**
     * The request is not supported by either the satellite modem or the network.
     */
    public static final int SATELLITE_REQUEST_NOT_SUPPORTED = 11;
    /**
     * Satellite modem or network has no resources available to handle requests from clients.
     */
    public static final int SATELLITE_NO_RESOURCES = 12;
    /**
     * Satellite service is not provisioned yet.
     */
    public static final int SATELLITE_SERVICE_NOT_PROVISIONED = 13;
    /**
     * Satellite service provision is already in progress.
     */
    public static final int SATELLITE_SERVICE_PROVISION_IN_PROGRESS = 14;
    /**
     * The ongoing request was aborted by either the satellite modem or the network.
     */
    public static final int SATELLITE_REQUEST_ABORTED = 15;
    /**
     * The device/subscriber is barred from accessing the satellite service.
     */
    public static final int SATELLITE_ACCESS_BARRED = 16;
    /**
     * Satellite modem timeout to receive ACK or response from the satellite network after
     * sending a request to the network.
     */
    public static final int SATELLITE_NETWORK_TIMEOUT = 17;
    /**
     * Satellite network is not reachable from the modem.
     */
    public static final int SATELLITE_NOT_REACHABLE = 18;
    /**
     * The device/subscriber is not authorized to register with the satellite service provider.
     */
    public static final int SATELLITE_NOT_AUTHORIZED = 19;
    /**
     * The device does not support satellite.
     */
    public static final int SATELLITE_NOT_SUPPORTED = 20;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_"}, value = {
            SATELLITE_ERROR_NONE,
            SATELLITE_ERROR,
            SATELLITE_SERVER_ERROR,
            SATELLITE_SERVICE_ERROR,
            SATELLITE_MODEM_ERROR,
            SATELLITE_NETWORK_ERROR,
            SATELLITE_INVALID_TELEPHONY_STATE,
            SATELLITE_INVALID_MODEM_STATE,
            SATELLITE_INVALID_ARGUMENTS,
            SATELLITE_REQUEST_FAILED,
            SATELLITE_RADIO_NOT_AVAILABLE,
            SATELLITE_REQUEST_NOT_SUPPORTED,
            SATELLITE_NO_RESOURCES,
            SATELLITE_SERVICE_NOT_PROVISIONED,
            SATELLITE_SERVICE_PROVISION_IN_PROGRESS,
            SATELLITE_REQUEST_ABORTED,
            SATELLITE_ACCESS_BARRED,
            SATELLITE_NETWORK_TIMEOUT,
            SATELLITE_NOT_REACHABLE,
            SATELLITE_NOT_AUTHORIZED,
            SATELLITE_NOT_SUPPORTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteError {}

    /**
     * Enable or disable the satellite modem. If the satellite modem is enabled, this will also
     * disable the cellular modem, and if the satellite modem is disabled, this will also re-enable
     * the cellular modem.
     *
     * @param enable {@code true} to enable the satellite modem and {@code false} to disable.
     * @param executor The executor on which the error code listener will be called.
     * @param errorCodeListener Listener for the {@link SatelliteError} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void setSatelliteEnabled(boolean enable, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Integer> errorCodeListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(errorCodeListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> errorCodeListener.accept(result)));
                    }
                };
                telephony.setSatelliteEnabled(mSubId, enable, errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setSatelliteEnabled RemoteException: ", ex);
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
     *                 is powered on and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteError}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
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
                        if (resultCode == SATELLITE_ERROR_NONE) {
                            if (resultData.containsKey(KEY_SATELLITE_ENABLED)) {
                                boolean isSatelliteEnabled =
                                        resultData.getBoolean(KEY_SATELLITE_ENABLED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteEnabled)));
                            } else {
                                loge("KEY_SATELLITE_ENABLED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(
                                                new SatelliteException(SATELLITE_REQUEST_FAILED))));
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
     * Request to get whether the satellite service is supported on the device.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return a {@code boolean} with value {@code true} if the satellite
     *                 service is supported on the device and {@code false} otherwise.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *                 will return a {@link SatelliteException} with the {@link SatelliteError}.
     *
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
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
                        if (resultCode == SATELLITE_ERROR_NONE) {
                            if (resultData.containsKey(KEY_SATELLITE_SUPPORTED)) {
                                boolean isSatelliteSupported =
                                        resultData.getBoolean(KEY_SATELLITE_SUPPORTED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteSupported)));
                            } else {
                                loge("KEY_SATELLITE_SUPPORTED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(
                                                new SatelliteException(SATELLITE_REQUEST_FAILED))));
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
     *                 will return a {@link SatelliteException} with the {@link SatelliteError}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
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
                        if (resultCode == SATELLITE_ERROR_NONE) {
                            if (resultData.containsKey(KEY_SATELLITE_CAPABILITIES)) {
                                SatelliteCapabilities capabilities =
                                        resultData.getParcelable(KEY_SATELLITE_CAPABILITIES,
                                                SatelliteCapabilities.class);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(capabilities)));
                            } else {
                                loge("KEY_SATELLITE_CAPABILITIES does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(
                                                new SatelliteException(SATELLITE_REQUEST_FAILED))));
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
     * Message transfer is waiting to acquire.
     */
    public static final int SATELLITE_MESSAGE_TRANSFER_STATE_WAITING_TO_ACQUIRE = 0;
    /**
     * Message is being sent.
     */
    public static final int SATELLITE_MESSAGE_TRANSFER_STATE_SENDING = 1;
    /**
     * Message is being received.
     */
    public static final int SATELLITE_MESSAGE_TRANSFER_STATE_RECEIVING = 2;
    /**
     * Message transfer is being retried.
     */
    public static final int SATELLITE_MESSAGE_TRANSFER_STATE_RETRYING = 3;
    /**
     * Message transfer is complete.
     */
    public static final int SATELLITE_MESSAGE_TRANSFER_STATE_COMPLETE = 4;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_MESSAGE_TRANSFER_STATE_"}, value = {
            SATELLITE_MESSAGE_TRANSFER_STATE_WAITING_TO_ACQUIRE,
            SATELLITE_MESSAGE_TRANSFER_STATE_SENDING,
            SATELLITE_MESSAGE_TRANSFER_STATE_RECEIVING,
            SATELLITE_MESSAGE_TRANSFER_STATE_RETRYING,
            SATELLITE_MESSAGE_TRANSFER_STATE_COMPLETE
    })
    public @interface SatelliteMessageTransferState {}

    /**
     * Start receiving satellite position updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     * Satellite position updates are started only on {@link #SATELLITE_ERROR_NONE}.
     * All other results indicate that this operation failed.
     *
     * @param executor The executor on which the callback and error code listener will be called.
     * @param errorCodeListener Listener for the {@link SatelliteError} result of the operation.
     * @param callback The callback to notify of changes in satellite position. This
     *                 SatelliteCallback should implement the interface
     *                 {@link SatelliteCallback.SatellitePositionUpdateListener}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void startSatellitePositionUpdates(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Integer> errorCodeListener, @NonNull SatelliteCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(errorCodeListener);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                callback.init(executor);
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> errorCodeListener.accept(result)));
                    }
                };
                telephony.startSatellitePositionUpdates(mSubId, errorCallback,
                        callback.getCallbackStub());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("startSatellitePositionUpdates RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Stop receiving satellite position updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     * Satellite position updates are stopped only on {@link #SATELLITE_ERROR_NONE}.
     * All other results indicate that this operation failed.
     *
     * @param callback The callback that was passed to
     *       {@link #startSatellitePositionUpdates(Executor, Consumer, SatelliteCallback)}.
     * @param executor The executor on which the error code listener will be called.
     * @param errorCodeListener Listener for the {@link SatelliteError} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the callback is invalid.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void stopSatellitePositionUpdates(@NonNull SatelliteCallback callback,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Integer> errorCodeListener) {
        Objects.requireNonNull(callback);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(errorCodeListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> errorCodeListener.accept(result)));
                    }
                };
                telephony.stopSatellitePositionUpdates(mSubId, errorCallback,
                        callback.getCallbackStub());
                // TODO: Notify SmsHandler that pointing UI stopped
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("stopSatellitePositionUpdates RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request to get the maximum number of characters per text message on satellite.
     *
     * @param executor The executor on which the callback will be called.
     * @param callback The callback object to which the result will be delivered.
     *                 If the request is successful, {@link OutcomeReceiver#onResult(Object)}
     *                 will return the maximum number of characters per text message on satellite.
     *                 If the request is not successful, {@link OutcomeReceiver#onError(Throwable)}
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void requestMaxCharactersPerSatelliteTextMessage(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<Integer, SatelliteException> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ResultReceiver receiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode == SATELLITE_ERROR_NONE) {
                            if (resultData.containsKey(KEY_MAX_CHARACTERS_PER_SATELLITE_TEXT)) {
                                int maxCharacters =
                                        resultData.getInt(KEY_MAX_CHARACTERS_PER_SATELLITE_TEXT);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(maxCharacters)));
                            } else {
                                loge("KEY_MAX_CHARACTERS_PER_SATELLITE_TEXT does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(
                                                new SatelliteException(SATELLITE_REQUEST_FAILED))));
                            }
                        } else {
                            executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                    callback.onError(new SatelliteException(resultCode))));
                        }
                    }
                };
                telephony.requestMaxCharactersPerSatelliteTextMessage(mSubId, receiver);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("requestMaxCharactersPerSatelliteTextMessage() RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }


    /**
     * Provision the device with a satellite provider.
     * This is needed if the provider allows dynamic registration.
     *
     * @param token The security token of the device/subscription to be provisioned.
     * @param cancellationSignal The optional signal used by the caller to cancel the provision
     *                           request. Even when the cancellation is signaled, Telephony will
     *                           still trigger the callback to return the result of this request.
     * @param executor The executor on which the error code listener will be called.
     * @param errorCodeListener Listener for the {@link SatelliteError} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void provisionSatelliteService(@NonNull String token,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor,
            @SatelliteError @NonNull Consumer<Integer> errorCodeListener) {
        Objects.requireNonNull(token);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(errorCodeListener);

        ICancellationSignal cancelRemote = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> errorCodeListener.accept(result)));
                    }
                };
                cancelRemote = telephony.provisionSatelliteService(mSubId, token, errorCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("provisionSatelliteService RemoteException=" + ex);
            ex.rethrowFromSystemServer();
        }
        if (cancellationSignal != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Register for the satellite provision state change.
     *
     * @param executor The executor on which the callback and error code listener will be called.
     * @param errorCodeListener Listener for the {@link SatelliteError} result of the operation.
     * @param callback The callback to handle the satellite provision state changed event. This
     *                 SatelliteCallback should implement the interface
     *                 {@link SatelliteCallback.SatelliteProvisionStateListener}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void registerForSatelliteProvisionStateChanged(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Integer> errorCodeListener, @NonNull SatelliteCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(errorCodeListener);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                callback.init(executor);
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> errorCodeListener.accept(result)));
                    }
                };
                telephony.registerForSatelliteProvisionStateChanged(
                        mSubId, errorCallback, callback.getCallbackStub());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteProvisionStateChanged RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister for the satellite provision state change.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteProvisionStateChanged(Executor, Consumer, SatelliteCallback)}.
     * @param executor The executor on which the error code listener will be called.
     * @param errorCodeListener Listener for the {@link SatelliteError} result of the operation.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void unregisterForSatelliteProvisionStateChanged(@NonNull SatelliteCallback callback,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Integer> errorCodeListener) {
        Objects.requireNonNull(callback);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(errorCodeListener);

        if (callback.getCallbackStub() == null) {
            loge("unregisterForSatelliteProvisionStateChanged: callbackStub is null");
            executor.execute(() -> Binder.withCleanCallingIdentity(
                    () -> errorCodeListener.accept(SATELLITE_INVALID_ARGUMENTS)));
            return;
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> errorCodeListener.accept(result)));
                    }
                };
                telephony.unregisterForSatelliteProvisionStateChanged(mSubId, errorCallback,
                        callback.getCallbackStub());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteProvisionStateChanged RemoteException: " + ex);
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
     *                 will return a {@link SatelliteException} with the {@link SatelliteError}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
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
                        if (resultCode == SATELLITE_ERROR_NONE) {
                            if (resultData.containsKey(KEY_SATELLITE_PROVISIONED)) {
                                boolean isSatelliteProvisioned =
                                        resultData.getBoolean(KEY_SATELLITE_PROVISIONED);
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onResult(isSatelliteProvisioned)));
                            } else {
                                loge("KEY_SATELLITE_PROVISIONED does not exist.");
                                executor.execute(() -> Binder.withCleanCallingIdentity(() ->
                                        callback.onError(
                                                new SatelliteException(SATELLITE_REQUEST_FAILED))));
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
