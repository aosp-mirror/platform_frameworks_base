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
import android.os.RemoteException;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyFrameworkInitializer;
import android.util.ArrayMap;

import com.android.internal.telephony.ITelephony;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages satellite operations such as provisioning, pointing, messaging, location sharing, etc.
 * To get the object, call {@link Context#getSystemService(Context.SATELLITE_SERVICE)}.
 * To create an instance of {@link SatelliteManager} associated with a specific subscription ID,
 * call {@link #createForSubscriptionId(int)}.
 *
 * @hide
 */
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_SATELLITE)
public class SatelliteManager {
    private static final String TAG = "SatelliteManager";

    /**
     * Map of all SatellitePositionUpdateCallback and their associated callback ids.
     */
    private final Map<SatellitePositionUpdateCallback, Integer> mSatellitePositionUpdateCallbacks =
            new ArrayMap<>();

    /**
     * AtomicInteger for the id of the next SatellitePositionUpdateCallback.
     */
    private final AtomicInteger mSatellitePositionUpdateCallbackId = new AtomicInteger(0);

    /**
     * The subscription ID for this SatelliteManager.
     */
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
        // TODO: replace DEFAULT_SUBSCRIPTION_ID with DEFAULT_SATELLITE_SUBSCRIPTION_ID
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
     * Successful response.
     */
    public static final int SATELLITE_SERVICE_SUCCESS = 0;
    /**
     * Satellite server is not reachable.
     */
    public static final int SATELLITE_SERVICE_SERVER_NOT_REACHABLE = 1;
    /**
     * Error received from the satellite server.
     */
    public static final int SATELLITE_SERVICE_SERVER_ERROR = 2;
    /**
     * Internal error received from the satellite service
     */
    public static final int SATELLITE_SERVICE_INTERNAL_ERROR = 3;
    /**
     * Modem error received from the satellite service.
     */
    public static final int SATELLITE_SERVICE_MODEM_ERROR = 4;
    /**
     * System error received from the satellite service.
     */
    public static final int SATELLITE_SERVICE_SYSTEM_ERROR = 5;
    /**
     * Invalid arguments passed.
     */
    public static final int SATELLITE_SERVICE_INVALID_ARGUMENTS = 6;
    /**
     * Invalid modem state.
     */
    public static final int SATELLITE_SERVICE_INVALID_MODEM_STATE = 7;
    /**
     * Invalid SIM state.
     */
    public static final int SATELLITE_SERVICE_INVALID_SIM_STATE = 8;
    /**
     * Invalid state.
     */
    public static final int SATELLITE_SERVICE_INVALID_STATE = 9;
    /**
     * Satellite service is unavailable.
     */
    public static final int SATELLITE_SERVICE_NOT_AVAILABLE = 10;
    /**
     * Satellite service is not supported by the device or OS.
     */
    public static final int SATELLITE_SERVICE_NOT_SUPPORTED = 11;
    /**
     * Satellite service is rate limited.
     */
    public static final int SATELLITE_SERVICE_RATE_LIMITED = 12;
    /**
     * Satellite service has no memory available.
     */
    public static final int SATELLITE_SERVICE_NO_MEMORY = 13;
    /**
     * Satellite service has no resources available.
     */
    public static final int SATELLITE_SERVICE_NO_RESOURCES = 14;
    /**
     * Failed to send a request to the satellite service.
     */
    public static final int SATELLITE_SERVICE_REQUEST_FAILED = 15;
    /**
     * Failed to send a request to the satellite service for the given subscription ID.
     */
    public static final int SATELLITE_SERVICE_INVALID_SUBSCRIPTION_ID = 16;
    /**
     * Error received from satellite service.
     */
    public static final int SATELLITE_SERVICE_ERROR = 17;

    /** @hide */
    @IntDef(prefix = {"SATELLITE_SERVICE_"}, value = {
            SATELLITE_SERVICE_SUCCESS,
            SATELLITE_SERVICE_SERVER_NOT_REACHABLE,
            SATELLITE_SERVICE_SERVER_ERROR,
            SATELLITE_SERVICE_INTERNAL_ERROR,
            SATELLITE_SERVICE_MODEM_ERROR,
            SATELLITE_SERVICE_SYSTEM_ERROR,
            SATELLITE_SERVICE_INVALID_ARGUMENTS,
            SATELLITE_SERVICE_INVALID_MODEM_STATE,
            SATELLITE_SERVICE_INVALID_SIM_STATE,
            SATELLITE_SERVICE_INVALID_STATE,
            SATELLITE_SERVICE_NOT_AVAILABLE,
            SATELLITE_SERVICE_NOT_SUPPORTED,
            SATELLITE_SERVICE_RATE_LIMITED,
            SATELLITE_SERVICE_NO_MEMORY,
            SATELLITE_SERVICE_NO_RESOURCES,
            SATELLITE_SERVICE_REQUEST_FAILED,
            SATELLITE_SERVICE_INVALID_SUBSCRIPTION_ID,
            SATELLITE_SERVICE_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SatelliteServiceResult {}

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
     * Callback for position updates from the satellite service.
     */
    public interface SatellitePositionUpdateCallback {
        /**
         * Called when the satellite position changes.
         *
         * @param pointingInfo The pointing info containing the satellite location.
         */
        void onSatellitePositionUpdate(@NonNull PointingInfo pointingInfo);

        /**
         * Called when satellite message transfer state changes.
         *
         * @param state The new message transfer state.
         */
        void onMessageTransferStateUpdate(@SatelliteMessageTransferState int state);
    }

    /**
     * Start receiving satellite position updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     * Satellite position updates are started only on {@link #SATELLITE_SERVICE_SUCCESS}.
     * All other results indicate that this operation failed.
     *
     * @param executor The executor to run callbacks on.
     * @param callback The callback to notify of changes in satellite position.
     * @return The result of the operation.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteServiceResult public int startSatellitePositionUpdates(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull SatellitePositionUpdateCallback callback) {
        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int id;
                if (mSatellitePositionUpdateCallbacks.containsKey(callback)) {
                    id = mSatellitePositionUpdateCallbacks.get(callback);
                } else {
                    id = mSatellitePositionUpdateCallbackId.getAndIncrement();
                }
                int result = telephony.startSatellitePositionUpdates(mSubId, id,
                        new ISatellitePositionUpdateCallback.Stub() {
                            @Override
                            public void onSatellitePositionUpdate(
                                    @NonNull PointingInfo pointingInfo) {
                                logd("onSatellitePositionUpdate: pointingInfo=" + pointingInfo);
                                executor.execute(() ->
                                        callback.onSatellitePositionUpdate(pointingInfo));
                            }

                            @Override
                            public void onMessageTransferStateUpdate(
                                    @SatelliteMessageTransferState int state) {
                                logd("onMessageTransferStateUpdate: state=" + state);
                                executor.execute(() ->
                                        callback.onMessageTransferStateUpdate(state));
                            }
                        });
                if (result == SATELLITE_SERVICE_SUCCESS) {
                    mSatellitePositionUpdateCallbacks.put(callback, id);
                }
                return result;
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("startSatellitePositionUpdates RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_SERVICE_REQUEST_FAILED;
    }

    /**
     * Stop receiving satellite position updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     * Satellite position updates are stopped only on {@link #SATELLITE_SERVICE_SUCCESS}.
     * All other results indicate that this operation failed.
     *
     * @param callback The callback that was passed in {@link
     *                 #startSatellitePositionUpdates(Executor, SatellitePositionUpdateCallback)}.
     * @return The result of the operation.
     * @throws IllegalArgumentException if the callback is invalid.
     */
    @RequiresPermission(Manifest.permission.SATELLITE_COMMUNICATION)
    @SatelliteServiceResult public int stopSatellitePositionUpdates(
            @NonNull SatellitePositionUpdateCallback callback) {
        if (!mSatellitePositionUpdateCallbacks.containsKey(callback)) {
            throw new IllegalArgumentException(
                    "startSatellitePositionUpdates was never called with the callback provided.");
        }

        try {
            ITelephony telephony = getITelephony();
            if (telephony != null) {
                int result = telephony.stopSatellitePositionUpdates(mSubId,
                        mSatellitePositionUpdateCallbacks.get(callback));
                if (result == SATELLITE_SERVICE_SUCCESS) {
                    mSatellitePositionUpdateCallbacks.remove(callback);
                    // TODO: Notify SmsHandler that pointing UI stopped
                }
                return result;
            } else {
                throw new IllegalStateException("telephony service is null.");
            }
        } catch (RemoteException ex) {
            loge("stopSatellitePositionUpdates RemoteException: " + ex);
            ex.rethrowFromSystemServer();
        }
        return SATELLITE_SERVICE_REQUEST_FAILED;
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
