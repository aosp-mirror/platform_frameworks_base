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

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ICancellationSignal;
import android.os.RemoteException;
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
        final RotationResolutionRequest remoteRequest = new RotationResolutionRequest(
                request.mProposedRotation, request.mCurrentRotation, request.mPackageName,
                request.mTimeoutMillis);
        post(service -> service.resolveRotation(request.mIRotationResolverCallback, remoteRequest));

        // schedule a timeout.
        getJobHandler().postDelayed(() -> {
            synchronized (request.mLock) {
                if (!request.mIsFulfilled) {
                    request.mCallbackInternal.onFailure(ROTATION_RESULT_FAILURE_TIMED_OUT);
                    request.cancelInternal();
                }
            }
        }, request.mTimeoutMillis);
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

        private final long mTimeoutMillis;

        @VisibleForTesting
        final int mProposedRotation;

        private final int mCurrentRotation;
        private final String mPackageName;

        boolean mIsDispatched;
        private final Object mLock = new Object();

        RotationRequest(
                @NonNull RotationResolverInternal.RotationResolverCallbackInternal
                        callbackInternal, int proposedRotation, int currentRotation,
                String packageName, long timeoutMillis,
                @NonNull CancellationSignal cancellationSignal) {
            mTimeoutMillis = timeoutMillis;
            mCallbackInternal = callbackInternal;
            mProposedRotation = proposedRotation;
            mCurrentRotation = currentRotation;
            mPackageName = packageName;
            mIRotationResolverCallback = new RotationResolverCallback();
            mCancellationSignalInternal = cancellationSignal;
        }


        void cancelInternal() {
            Handler.getMain().post(() -> {
                synchronized (mLock) {
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
            synchronized (mLock) {
                mIsFulfilled = true;
            }
            mCallbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
        }

        void dump(IndentingPrintWriter ipw) {
            ipw.increaseIndent();
            ipw.println("is dispatched=" + mIsDispatched);
            ipw.println("is fulfilled:=" + mIsFulfilled);
            ipw.decreaseIndent();
        }

        private class RotationResolverCallback extends IRotationResolverCallback.Stub {
            @Override
            public void onSuccess(int rotation) {
                synchronized (mLock) {
                    if (mIsFulfilled) {
                        Slog.w(TAG, "Callback received after the rotation request is fulfilled.");
                        return;
                    }
                    mIsFulfilled = true;
                    mCallbackInternal.onSuccess(rotation);
                    logStats(rotation);
                }
            }

            @Override
            public void onFailure(int error) {
                synchronized (mLock) {
                    if (mIsFulfilled) {
                        Slog.w(TAG, "Callback received after the rotation request is fulfilled.");
                        return;
                    }
                    mIsFulfilled = true;
                    mCallbackInternal.onFailure(error);
                    logStats(error);
                }
            }

            @Override
            public void onCancellable(@NonNull ICancellationSignal cancellation) {
                synchronized (mLock) {
                    mCancellation = cancellation;
                    if (mCancellationSignalInternal.isCanceled()) {
                        // Dispatch the cancellation signal if the client has cancelled the request.
                        try {
                            cancellation.cancel();
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to cancel the remote request.");
                        }
                    }
                }

            }

            private void logStats(int result) {
                // TODO FrameworkStatsLog
            }
        }
    }
}
