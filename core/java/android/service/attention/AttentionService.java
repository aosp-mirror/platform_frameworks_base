/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.attention;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Objects;


/**
 * Abstract base class for Attention service.
 *
 * <p> An attention service provides attention estimation related features to the system.
 * The system's default AttentionService implementation is configured in
 * {@code config_AttentionComponent}. If this config has no value, a stub is returned.
 *
 * See: {@link com.android.server.attention.AttentionManagerService}.
 *
 * <pre>
 * {@literal
 * <service android:name=".YourAttentionService"
 *          android:permission="android.permission.BIND_ATTENTION_SERVICE">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
public abstract class AttentionService extends Service {
    private static final String LOG_TAG = "AttentionService";
    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the {@link android.Manifest.permission#BIND_ATTENTION_SERVICE}
     * permission so that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.attention.AttentionService";

    /** Attention is absent. */
    public static final int ATTENTION_SUCCESS_ABSENT = 0;

    /** Attention is present. */
    public static final int ATTENTION_SUCCESS_PRESENT = 1;

    /** Unknown reasons for failing to determine the attention. */
    public static final int ATTENTION_FAILURE_UNKNOWN = 2;

    /** Request has been cancelled. */
    public static final int ATTENTION_FAILURE_CANCELLED = 3;

    /** Preempted by other client. */
    public static final int ATTENTION_FAILURE_PREEMPTED = 4;

    /** Request timed out. */
    public static final int ATTENTION_FAILURE_TIMED_OUT = 5;

    /** Camera permission is not granted. */
    public static final int ATTENTION_FAILURE_CAMERA_PERMISSION_ABSENT = 6;

    /** Users’ proximity is unknown (proximity sensing was inconclusive and is unsupported). */
    public static final double PROXIMITY_UNKNOWN = -1;

    /**
     * Result codes for when attention check was successful.
     *
     * @hide
     */
    @IntDef(prefix = {"ATTENTION_SUCCESS_"}, value = {ATTENTION_SUCCESS_ABSENT,
            ATTENTION_SUCCESS_PRESENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttentionSuccessCodes {
    }

    /**
     * Result codes explaining why attention check was not successful.
     *
     * @hide
     */
    @IntDef(prefix = {"ATTENTION_FAILURE_"}, value = {ATTENTION_FAILURE_UNKNOWN,
            ATTENTION_FAILURE_CANCELLED, ATTENTION_FAILURE_PREEMPTED, ATTENTION_FAILURE_TIMED_OUT,
            ATTENTION_FAILURE_CAMERA_PERMISSION_ABSENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttentionFailureCodes {
    }

    private final IAttentionService.Stub mBinder = new IAttentionService.Stub() {

        /** {@inheritDoc} */
        @Override
        public void checkAttention(IAttentionCallback callback) {
            Preconditions.checkNotNull(callback);
            AttentionService.this.onCheckAttention(new AttentionCallback(callback));
        }

        /** {@inheritDoc} */
        @Override
        public void cancelAttentionCheck(IAttentionCallback callback) {
            Preconditions.checkNotNull(callback);
            AttentionService.this.onCancelAttentionCheck(new AttentionCallback(callback));
        }

        /** {@inheritDoc} */
        @Override
        public void onStartProximityUpdates(IProximityUpdateCallback callback) {
            Objects.requireNonNull(callback);
            AttentionService.this.onStartProximityUpdates(new ProximityUpdateCallback(callback));

        }

        /** {@inheritDoc} */
        @Override
        public void onStopProximityUpdates() {
            AttentionService.this.onStopProximityUpdates();
        }
    };

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    /**
     * Checks the user attention and calls into the provided callback.
     *
     * @param callback the callback to return the result to
     */
    public abstract void onCheckAttention(@NonNull AttentionCallback callback);

    /**
     * Cancels pending work for a given callback.
     *
     * Implementation must call back with a failure code of {@link #ATTENTION_FAILURE_CANCELLED}.
     */
    public abstract void onCancelAttentionCheck(@NonNull AttentionCallback callback);

    /**
     * Requests the continuous updates of proximity signal via the provided callback,
     * until {@link #onStopProximityUpdates} is called.
     *
     * @param callback the callback to return the result to
     */
    public void onStartProximityUpdates(@NonNull ProximityUpdateCallback callback) {
        Slog.w(LOG_TAG, "Override this method.");
    }

    /**
     * Requests to stop providing continuous updates until the callback is registered.
     */
    public void onStopProximityUpdates() {
        Slog.w(LOG_TAG, "Override this method.");
    }

    /** Callbacks for AttentionService results. */
    public static final class AttentionCallback {
        @NonNull private final IAttentionCallback mCallback;

        private AttentionCallback(@NonNull IAttentionCallback callback) {
            mCallback = callback;
        }

        /**
         * Signals a success and provides the result code.
         *
         * @param timestamp of when the attention signal was computed; system throttles the requests
         *                  so this is useful to know how fresh the result is.
         */
        public void onSuccess(@AttentionSuccessCodes int result, long timestamp) {
            try {
                mCallback.onSuccess(result, timestamp);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        /** Signals a failure and provides the error code. */
        public void onFailure(@AttentionFailureCodes int error) {
            try {
                mCallback.onFailure(error);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /** Callbacks for ProximityUpdateCallback results. */
    public static final class ProximityUpdateCallback {
        @NonNull private final WeakReference<IProximityUpdateCallback> mCallback;

        private ProximityUpdateCallback(@NonNull IProximityUpdateCallback callback) {
            mCallback = new WeakReference<>(callback);
        }

        /**
         * @param distance the estimated distance of the user (in meter)
         * The distance will be {@link #PROXIMITY_UNKNOWN} if the proximity sensing
         * was inconclusive.
         */
        public void onProximityUpdate(double distance) {
            try {
                if (mCallback.get() != null) {
                    mCallback.get().onProximityUpdate(distance);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }
}
