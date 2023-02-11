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
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.satellite.stub.SatelliteImplBase;

import com.android.internal.telephony.IBooleanConsumer;
import com.android.internal.telephony.IIntArrayConsumer;
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
     * Create a new SatelliteManager associated with the given subscription ID.
     *
     * @param subId The subscription ID to create the SatelliteManager with.
     * @return A SatelliteManager that uses the given subscription ID for all calls.
     */
    @NonNull public SatelliteManager createForSubscriptionId(int subId) {
        return new SatelliteManager(mContext, subId);
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
     * Telephony framework failed to send a request to the vendor service or the satellite
     * modem due to internal error.
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
     * Power on or off the satellite modem.
     *
     * @param powerOn {@code true} to power on the satellite modem and {@code false} to power off.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError public int setSatellitePower(boolean powerOn) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.setSatellitePower(mSubId, powerOn);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            Rlog.e(TAG, "setSatellitePower RemoteException", ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Check whether the satellite modem is powered on.
     *
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener with the result if the operation is successful.
     *                       If this method returns {@link #SATELLITE_ERROR_NONE}, the result
     *                       listener will return {@code true} if the satellite modem is powered on
     *                       and {@code false} otherwise.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError public int isSatellitePowerOn(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IBooleanConsumer internalCallback = new IBooleanConsumer.Stub() {
                    @Override
                    public void accept(boolean result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };

                return telephony.isSatellitePowerOn(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("isSatellitePowerOn() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Check whether the satellite service is supported on the device.
     *
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener with the result if the operation is successful.
     *                       If this method returns {@link #SATELLITE_ERROR_NONE}, the result
     *                       listener will return {@code true} if the satellite service is supported
     *                       and {@code false} otherwise.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError public int isSatelliteSupported(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IBooleanConsumer internalCallback = new IBooleanConsumer.Stub() {
                    @Override
                    public void accept(boolean result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };

                return telephony.isSatelliteSupported(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("isSatelliteSupported() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Get the {@link SatelliteCapabilities} with all capabilities of the satellite service.
     *
     * @param executor The executor on which the result listener will be called.
     * @param resultListener Listener with the result if the operation is successful.
     *                       If this method returns {@link #SATELLITE_ERROR_NONE}, the result
     *                       listener will return the current {@link SatelliteCapabilities}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError public int getSatelliteCapabilities(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<SatelliteCapabilities> resultListener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(resultListener);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                ISatelliteCapabilitiesConsumer internalCallback =
                        new ISatelliteCapabilitiesConsumer.Stub() {
                    @Override
                    public void accept(SatelliteCapabilities result) {
                        executor.execute(() -> Binder.withCleanCallingIdentity(
                                () -> resultListener.accept(result)));
                    }
                };

                return telephony.getSatelliteCapabilities(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getSatelliteCapabilities() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
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
     * @param executor The executor on which the callback will be called.
     * @param callback The callback to notify of changes in satellite position. This
     *                 SatelliteCallback should implement the interface
     *                 {@link SatelliteCallback.SatellitePositionUpdateListener}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError
    public int startSatellitePositionUpdates(
            @NonNull Executor executor, @NonNull SatelliteCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                callback.init(executor);
                return telephony.startSatellitePositionUpdates(mSubId, callback.getCallbackStub());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("startSatellitePositionUpdates RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Stop receiving satellite position updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     * Satellite position updates are stopped only on {@link #SATELLITE_ERROR_NONE}.
     * All other results indicate that this operation failed.
     *
     * @param callback The callback that was passed in {@link
     *                 #startSatellitePositionUpdates(Executor, SatelliteCallback)}.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalArgumentException if the callback is invalid.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError
    public int stopSatellitePositionUpdates(
            @NonNull SatelliteCallback callback) {
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.stopSatellitePositionUpdates(mSubId, callback.getCallbackStub());
                // TODO: Notify SmsHandler that pointing UI stopped
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("stopSatellitePositionUpdates RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Get maximum number of characters per text message on satellite.
     * @param executor - The executor on which the result listener will be called.
     * @param resultListener - Listener that will be called when the operation is successful.
     *                       If this method returns {@link #SATELLITE_ERROR_NONE}, listener
     *                       will be called with maximum characters limit.
     *
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     *
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError
    public int getMaxCharactersPerSatelliteTextMessage(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Integer> resultListener) {
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

                return telephony.getMaxCharactersPerSatelliteTextMessage(mSubId, internalCallback);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getMaxCharactersPerSatelliteTextMessage() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }


    /**
     * Register the subscription with a satellite provider. This is needed if the provider allows
     * dynamic registration.
     *
     * @param features List of features to be provisioned.
     * @param executor The optional executor to run callbacks on.
     * @param callback The optional callback to get the error code of the request.
     * @param cancellationSignal The optional signal used by the caller to cancel the provision
     *                           request. Even when the cancellation is signaled, Telephony will
     *                           still trigger the callback to return the result of this request.
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    public void provisionSatelliteService(
            @NonNull @SatelliteImplBase.Feature int[] features,
            @Nullable @CallbackExecutor Executor executor,
            @SatelliteError @Nullable Consumer<Integer> callback,
            @Nullable CancellationSignal cancellationSignal) {
        Objects.requireNonNull(features);

        ICancellationSignal cancelRemote = null;
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntegerConsumer callbackStub = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        if (executor == null || callback == null) {
                            logd("provisionSatelliteService: executor and/or callback is null");
                            return;
                        }
                        Binder.withCleanCallingIdentity(() -> {
                            executor.execute(() -> callback.accept(result));
                        });
                    }
                };
                cancelRemote = telephony.provisionSatelliteService(mSubId, features, callbackStub);
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
     * @param executor - The executor on which the callback will be called.
     * @param callback The callback to handle the satellite provision state changed event. This
     *                 SatelliteCallback should implement the interface
     *                 {@link SatelliteCallback.SatelliteProvisionStateListener}.
     * @return The error code of the request.
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError
    public int registerForSatelliteProvisionStateChanged(
            @NonNull Executor executor, @NonNull SatelliteCallback callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                callback.init(executor);
                return telephony.registerForSatelliteProvisionStateChanged(
                        mSubId, callback.getCallbackStub());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("registerForSatelliteProvisionStateChanged RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Unregister for the satellite provision state change.
     *
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteProvisionStateChanged(Executor, SatelliteCallback)}
     * @return The error code of the request.
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError
    public int unregisterForSatelliteProvisionStateChanged(@NonNull SatelliteCallback callback) {
        Objects.requireNonNull(callback);

        if (callback.getCallbackStub() == null) {
            loge("unregisterForSatelliteProvisionStateChanged: callbackStub is null");
            return SATELLITE_INVALID_ARGUMENTS;
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                return telephony.unregisterForSatelliteProvisionStateChanged(
                        mSubId, callback.getCallbackStub());
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("unregisterForSatelliteProvisionStateChanged RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
    }

    /**
     * Get the list of provisioned satellite features.
     *
     * @param executor The executor to run callbacks on.
     * @param resultListener The callback to get the list of provisioned features when the request
     *                       returns success result.
     * @return The error code of the request.
     * @throws SecurityException if the caller doesn't have required permission.
     * @throws IllegalStateException if the Telephony process is not currently available.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteError
    public int getProvisionedSatelliteFeatures(
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<int[]> resultListener) {
        Objects.requireNonNull(resultListener);
        Objects.requireNonNull(executor);

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                IIntArrayConsumer callbackStub = new IIntArrayConsumer.Stub() {
                    @Override
                    public void accept(int[] result) {
                        Binder.withCleanCallingIdentity(() -> {
                            executor.execute(() -> resultListener.accept(result));
                        });
                    }
                };
                return telephony.getProvisionedSatelliteFeatures(mSubId, callbackStub);
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("getProvisionedSatelliteFeatures() RemoteException:" + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_REQUEST_FAILED;
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
