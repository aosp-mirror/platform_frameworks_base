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

package com.android.server.rotationresolver;

import static android.content.Context.BIND_FOREGROUND_SERVICE;
import static android.content.Context.BIND_INCLUDE_CAPABILITIES;
import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_CANCELLED;
import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_TIMED_OUT;

import static com.android.server.rotationresolver.RotationResolverManagerService.errorCodeToProto;
import static com.android.server.rotationresolver.RotationResolverManagerService.surfaceRotationToProto;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.rotationresolver.RotationResolverInternal;
import android.service.rotationresolver.IRotationResolverCallback;
import android.service.rotationresolver.IRotationResolverService;
import android.service.rotationresolver.RotationResolutionRequest;
import android.service.rotationresolver.RotationResolverService;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.ServiceConnector;

import java.lang.ref.WeakReference;


/** Manages the connection to the remote rotation resolver service. */
class RemoteRotationResolverService extends ServiceConnector.Impl<IRotationResolverService> {
    private static final String TAG = RemoteRotationResolverService.class.getSimpleName();

    private final long mIdleUnbindTimeoutMs;
    private final Object mLock;

    RemoteRotationResolverService(Context context, ComponentName serviceName,
            int userId, long idleUnbindTimeoutMs, Object lock) {
        super(context,
                new Intent(RotationResolverService.SERVICE_INTERFACE).setComponent(serviceName),
                BIND_FOREGROUND_SERVICE | BIND_INCLUDE_CAPABILITIES, userId,
                IRotationResolverService.Stub::asInterface);

        mIdleUnbindTimeoutMs = idleUnbindTimeoutMs;
        mLock = lock;

        // Bind right away.
        connect();
    }

    @Override
    protected long getAutoDisconnectTimeoutMs() {
        // Disable automatic unbinding.
        return -1;
    }

    @GuardedBy("mLock")
    public void resolveRotationLocked(RotationRequest request) {
        final RotationResolutionRequest remoteRequest = request.mRemoteRequest;
        post(service -> service.resolveRotation(request.mIRotationResolverCallback, remoteRequest));

        // schedule a timeout.
        getJobHandler().postDelayed(() -> {
            synchronized (request.mLock) {
                if (!request.mIsFulfilled) {
                    request.mCallbackInternal.onFailure(ROTATION_RESULT_FAILURE_TIMED_OUT);
                    Slog.d(TAG, "Trying to cancel the remote request. Reason: Timed out.");
                    request.cancelInternal();
                }
            }
        }, request.mRemoteRequest.getTimeoutMillis());
    }

    @VisibleForTesting
    static final class RotationRequest {
        @NonNull
        private final IRotationResolverCallback mIRotationResolverCallback;
        @NonNull
        private ICancellationSignal mCancellation;
        @NonNull
        private final CancellationSignal mCancellationSignalInternal;
        @NonNull
        final RotationResolverInternal.RotationResolverCallbackInternal
                mCallbackInternal;

        @GuardedBy("mLock")
        boolean mIsFulfilled;

        @VisibleForTesting
        final RotationResolutionRequest mRemoteRequest;

        boolean mIsDispatched;
        private final Object mLock = new Object();
        private final long mRequestStartTimeMillis;

        RotationRequest(
                @NonNull RotationResolverInternal.RotationResolverCallbackInternal callbackInternal,
                RotationResolutionRequest request, @NonNull CancellationSignal cancellationSignal) {
            mCallbackInternal = callbackInternal;
            mRemoteRequest = request;
            mIRotationResolverCallback = new RotationResolverCallback(this);
            mCancellationSignalInternal = cancellationSignal;
            mRequestStartTimeMillis = SystemClock.elapsedRealtime();
        }


        void cancelInternal() {
            Handler.getMain().post(() -> {
                synchronized (mLock) {
                    if (mIsFulfilled) {
                        return;
                    }
                    mIsFulfilled = true;
                    try {
                        if (mCancellation != null) {
                            mCancellation.cancel();
                            mCancellation = null;
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to cancel request in remote service.");
                    }
                }
            });
            mCallbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
        }

        void dump(IndentingPrintWriter ipw) {
            ipw.increaseIndent();
            ipw.println("is dispatched=" + mIsDispatched);
            ipw.println("is fulfilled:=" + mIsFulfilled);
            ipw.decreaseIndent();
        }

        private static class RotationResolverCallback extends IRotationResolverCallback.Stub {
            private WeakReference<RotationRequest> mRequestWeakReference;

            RotationResolverCallback(RotationRequest request) {
                this.mRequestWeakReference = new WeakReference<>(request);
            }

            @Override
            public void onSuccess(int rotation) {
                final RotationRequest request = mRequestWeakReference.get();
                synchronized (request.mLock) {
                    if (request.mIsFulfilled) {
                        Slog.w(TAG, "Callback received after the rotation request is fulfilled.");
                        return;
                    }
                    request.mIsFulfilled = true;
                    request.mCallbackInternal.onSuccess(rotation);
                    final long timeToCalculate =
                            SystemClock.elapsedRealtime() - request.mRequestStartTimeMillis;
                    RotationResolverManagerService.logRotationStatsWithTimeToCalculate(
                            request.mRemoteRequest.getProposedRotation(),
                            request.mRemoteRequest.getCurrentRotation(),
                            surfaceRotationToProto(rotation), timeToCalculate);
                    Slog.d(TAG, "onSuccess:" + rotation);
                    Slog.d(TAG, "timeToCalculate:" + timeToCalculate);
                }
            }

            @Override
            public void onFailure(int error) {
                final RotationRequest request = mRequestWeakReference.get();
                synchronized (request.mLock) {
                    if (request.mIsFulfilled) {
                        Slog.w(TAG, "Callback received after the rotation request is fulfilled.");
                        return;
                    }
                    request.mIsFulfilled = true;
                    request.mCallbackInternal.onFailure(error);
                    final long timeToCalculate =
                            SystemClock.elapsedRealtime() - request.mRequestStartTimeMillis;
                    RotationResolverManagerService.logRotationStatsWithTimeToCalculate(
                            request.mRemoteRequest.getProposedRotation(),
                            request.mRemoteRequest.getCurrentRotation(), errorCodeToProto(error),
                            timeToCalculate);
                    Slog.d(TAG, "onFailure:" + error);
                    Slog.d(TAG, "timeToCalculate:" + timeToCalculate);
                }
            }

            @Override
            public void onCancellable(@NonNull ICancellationSignal cancellation) {
                final RotationRequest request = mRequestWeakReference.get();
                synchronized (request.mLock) {
                    request.mCancellation = cancellation;
                    if (request.mCancellationSignalInternal.isCanceled()) {
                        // Dispatch the cancellation signal if the client has cancelled the request.
                        try {
                            cancellation.cancel();
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to cancel the remote request.");
                        }
                    }
                }

            }
        }
    }
}
