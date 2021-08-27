/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.rotationresolver;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Abstract base class for rotation resolver service.
 *
 * <p> A {@link RotationResolverService} is a service that help determine screen rotation for the
 * system. When the system wants to resolve rotations, it will send a request to this service
 * via {@link #onResolveRotation} interface. A {@link RotationResolverCallback} is
 * attached to the request so that the implementer of the rotation resolver service can send
 * back results to the system. The system may then decide to rotate the screen based on the
 * results.
 *
 * <p> If RotationResolverService provides the result in time, the system will respect that result
 * and rotate the screen if possible.
 *
 * <p> The system's default RotationResolverService implementation is configured at
 * the {@code config_defaultRotationResolverService} field in the config XML file.
 *
 * <p> The implementation of RotationResolverService must have the following service interface.
 * Also, it must have permission {@link android.Manifest.permission#BIND_ROTATION_RESOLVER_SERVICE}.
 *
 * <pre>
 * {@literal
 * <service android:name=".RotationResolverService"
 *          android:permission="android.permission.BIND_ROTATION_RESOLVER_SERVICE">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
public abstract class RotationResolverService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_ROTATION_RESOLVER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.rotationresolver.RotationResolverService";

    /** Request has been cancelled. */
    public static final int ROTATION_RESULT_FAILURE_CANCELLED = 0;

    /** Request timed out. */
    public static final int ROTATION_RESULT_FAILURE_TIMED_OUT = 1;

    /** Preempted by other requests. */
    public static final int ROTATION_RESULT_FAILURE_PREEMPTED = 2;

    /** Unknown reasons for failing to fulfill the request. */
    public static final int ROTATION_RESULT_FAILURE_UNKNOWN = 3;

    /** Does not support rotation query at this moment. */
    public static final int ROTATION_RESULT_FAILURE_NOT_SUPPORTED = 4;

    /**
     * Result codes explaining why rotation recommendation request was not successful.
     *
     * @hide
     */
    @IntDef(prefix = {"ROTATION_RESULT_FAILURE_"}, value = {
            ROTATION_RESULT_FAILURE_CANCELLED,
            ROTATION_RESULT_FAILURE_TIMED_OUT, ROTATION_RESULT_FAILURE_PREEMPTED,
            ROTATION_RESULT_FAILURE_UNKNOWN,
            ROTATION_RESULT_FAILURE_NOT_SUPPORTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FailureCodes {
    }

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper(), null, true);
    @Nullable
    private RotationResolverCallbackWrapper mPendingCallback;
    @Nullable
    private CancellationSignal mCancellationSignal;

    @Nullable
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return new IRotationResolverService.Stub() {
                /** {@inheritDoc} */
                @Override
                public void resolveRotation(IRotationResolverCallback callback,
                        RotationResolutionRequest request) throws RemoteException {
                    Objects.requireNonNull(callback);
                    Objects.requireNonNull(request);
                    final ICancellationSignal transport = CancellationSignal.createTransport();
                    callback.onCancellable(transport);
                    mMainThreadHandler.sendMessage(
                            obtainMessage(RotationResolverService::resolveRotation,
                                    RotationResolverService.this, callback, request, transport));
                }
            };
        }
        return null;
    }

    @MainThread
    private void resolveRotation(IRotationResolverCallback callback,
            RotationResolutionRequest request, ICancellationSignal transport) {
        // If there is a valid, uncancelled pending callback running in process, the new rotation
        // resolution request will be rejected immediately with a failure result.
        if (mPendingCallback != null
                && (mCancellationSignal == null || !mCancellationSignal.isCanceled())
                && (SystemClock.uptimeMillis() < mPendingCallback.mExpirationTime)) {
            reportFailures(callback, ROTATION_RESULT_FAILURE_PREEMPTED);
            return;
        }
        mPendingCallback = new RotationResolverCallbackWrapper(callback, this,
                SystemClock.uptimeMillis() + request.getTimeoutMillis());
        mCancellationSignal = CancellationSignal.fromTransport(transport);

        onResolveRotation(request, mCancellationSignal, mPendingCallback);
    }

    @MainThread
    private void sendRotationResult(IRotationResolverCallback internalCallback, int result) {
        if (mPendingCallback != null && mPendingCallback.mCallback == internalCallback) {
            mPendingCallback = null;
            try {
                internalCallback.onSuccess(result);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    @MainThread
    private void sendFailureResult(IRotationResolverCallback internalCallback, int error) {
        if (mPendingCallback != null && internalCallback == mPendingCallback.mCallback) {
            reportFailures(internalCallback, error);
            mPendingCallback = null;
        }
    }

    @MainThread
    private void reportFailures(IRotationResolverCallback callback, int error) {
        try {
            callback.onFailure(error);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }


    /**
     * Gets called when the system requests to resolve the screen rotation. The implementer then
     * should return the result via the provided callback.
     *
     * @param request A request instance that contains information from the system that may help
     *                the implementer provide a better result.
     * @param cancellationSignal The signal for observing the cancellation of the request. The
     *                           system will use this to notify the implementer that the rotation
     *                           result is no longer needed. Implementer should then stop handling
     *                           the request in order to save resources.
     * @param callback A callback that Receives the rotation results.
     */
    public abstract void onResolveRotation(@NonNull RotationResolutionRequest request,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull RotationResolverCallback callback);

    /**
     * Interface definition for a callback to be invoked when rotation resolution request is
     * completed.
     */
    public interface RotationResolverCallback {
        /**
         * Signals a success and provides the result code.
         */
        void onSuccess(@Surface.Rotation int result);

        /**
         * Signals a failure and provides the error code.
         */
        void onFailure(@FailureCodes int error);
    }


    /**
     * An implementation of the callback that receives rotation resolution results.
     *
     * @hide
     */
    public static final class RotationResolverCallbackWrapper implements RotationResolverCallback {

        @NonNull
        private final android.service.rotationresolver.IRotationResolverCallback mCallback;
        @NonNull
        private final RotationResolverService mService;
        @NonNull
        private final Handler mHandler;

        private final long mExpirationTime;

        private RotationResolverCallbackWrapper(
                @NonNull android.service.rotationresolver.IRotationResolverCallback callback,
                RotationResolverService service, long expirationTime) {
            mCallback = callback;
            mService = service;
            mHandler = service.mMainThreadHandler;
            mExpirationTime = expirationTime;
            Objects.requireNonNull(mHandler);
        }


        @Override
        public void onSuccess(int result) {
            mHandler.sendMessage(
                    obtainMessage(RotationResolverService::sendRotationResult, mService, mCallback,
                            result));
        }

        @Override
        public void onFailure(int error) {
            mHandler.sendMessage(
                    obtainMessage(RotationResolverService::sendFailureResult, mService,
                            mCallback,
                            error));
        }
    }
}
