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

import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_CANCELLED;

import static com.android.internal.util.LatencyTracker.ACTION_ROTATE_SCREEN_CAMERA_CHECK;
import static com.android.server.rotationresolver.RotationResolverManagerService.RESOLUTION_UNAVAILABLE;
import static com.android.server.rotationresolver.RotationResolverManagerService.logRotationStats;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.rotationresolver.RotationResolverInternal;
import android.service.rotationresolver.RotationResolutionRequest;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.LatencyTracker;
import com.android.server.infra.AbstractPerUserSystemService;

/**
 * Manages the Rotation Resolver Service on a per-user basis.
 */
final class RotationResolverManagerPerUserService extends
        AbstractPerUserSystemService<RotationResolverManagerPerUserService,
                RotationResolverManagerService> {

    private static final String TAG = RotationResolverManagerPerUserService.class.getSimpleName();

    /** Service will unbind if connection is not used for that amount of time. */
    private static final long CONNECTION_TTL_MILLIS = 60_000L;

    @GuardedBy("mLock")
    @VisibleForTesting
    RemoteRotationResolverService.RotationRequest mCurrentRequest;

    @Nullable
    @VisibleForTesting
    @GuardedBy("mLock")
    RemoteRotationResolverService mRemoteService;

    private ComponentName mComponentName;
    @GuardedBy("mLock")
    private LatencyTracker mLatencyTracker;

    RotationResolverManagerPerUserService(@NonNull RotationResolverManagerService main,
            @NonNull Object lock, @UserIdInt int userId) {
        super(main, lock, userId);
        mLatencyTracker = LatencyTracker.getInstance(getContext());
    }

    @GuardedBy("mLock")
    void destroyLocked() {
        if (isVerbose()) {
            Slog.v(TAG, "destroyLocked()");
        }

        if (mCurrentRequest == null) {
            return;
        }
        Slog.d(TAG, "Trying to cancel the remote request. Reason: Service destroyed.");
        cancelLocked();

        if (mRemoteService != null) {
            mRemoteService.unbind();
            mRemoteService = null;
        }
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    void resolveRotationLocked(
            @NonNull RotationResolverInternal.RotationResolverCallbackInternal callbackInternal,
            @NonNull RotationResolutionRequest request,
            @NonNull CancellationSignal cancellationSignalInternal) {

        if (!isServiceAvailableLocked()) {
            Slog.w(TAG, "Service is not available at this moment.");
            callbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
            logRotationStats(request.getProposedRotation(), request.getCurrentRotation(),
                    RESOLUTION_UNAVAILABLE);
            return;
        }

        ensureRemoteServiceInitiated();

        // Cancel the previous on-going request.
        if (mCurrentRequest != null && !mCurrentRequest.mIsFulfilled) {
            cancelLocked();
        }

        synchronized (mLock) {
            mLatencyTracker.onActionStart(ACTION_ROTATE_SCREEN_CAMERA_CHECK);
        }
        /** Need to wrap RotationResolverCallbackInternal since there was no other way to hook
         into the success/failure callback **/
        final RotationResolverInternal.RotationResolverCallbackInternal wrapper =
                new RotationResolverInternal.RotationResolverCallbackInternal() {

                    @Override
                    public void onSuccess(int result) {
                        synchronized (mLock) {
                            mLatencyTracker
                                    .onActionEnd(ACTION_ROTATE_SCREEN_CAMERA_CHECK);
                        }
                        callbackInternal.onSuccess(result);
                    }

                    @Override
                    public void onFailure(int error) {
                        synchronized (mLock) {
                            mLatencyTracker
                                    .onActionEnd(ACTION_ROTATE_SCREEN_CAMERA_CHECK);
                        }
                        callbackInternal.onFailure(error);
                    }
                };
        mCurrentRequest = new RemoteRotationResolverService.RotationRequest(wrapper,
                request, cancellationSignalInternal, mLock);

        cancellationSignalInternal.setOnCancelListener(() -> {
            synchronized (mLock) {
                if (mCurrentRequest != null && !mCurrentRequest.mIsFulfilled) {
                    Slog.d(TAG, "Trying to cancel the remote request. Reason: Client cancelled.");
                    mCurrentRequest.cancelInternal();
                }
            }
        });


        mRemoteService.resolveRotation(mCurrentRequest);
        mCurrentRequest.mIsDispatched = true;
    }

    @GuardedBy("mLock")
    private void ensureRemoteServiceInitiated() {
        if (mRemoteService == null) {
            mRemoteService = new RemoteRotationResolverService(getContext(), mComponentName,
                    getUserId(), CONNECTION_TTL_MILLIS);
        }
    }

    /**
     * get the currently bound component name.
     */
    @VisibleForTesting
    ComponentName getComponentName() {
        return mComponentName;
    }


    /** Resolves and sets up the rotation resolver service if it had not been done yet. */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean isServiceAvailableLocked() {
        if (mComponentName == null) {
            mComponentName = updateServiceInfoLocked();
        }
        return mComponentName != null;
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
            if (serviceInfo != null) {
                final String permission = serviceInfo.permission;
                if (!Manifest.permission.BIND_ROTATION_RESOLVER_SERVICE.equals(permission)) {
                    throw new SecurityException(String.format(
                            "Service %s requires %s permission. Found %s permission",
                            serviceInfo.getComponentName(),
                            Manifest.permission.BIND_ROTATION_RESOLVER_SERVICE,
                            serviceInfo.permission));
                }
            }
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return serviceInfo;
    }

    @GuardedBy("mLock")
    private void cancelLocked() {
        if (mCurrentRequest == null) {
            return;
        }
        mCurrentRequest.cancelInternal();
        mCurrentRequest = null;
    }

    void dumpInternal(IndentingPrintWriter ipw) {
        synchronized (mLock) {
            if (mRemoteService != null) {
                mRemoteService.dump("", ipw);
            }
            if (mCurrentRequest != null) {
                mCurrentRequest.dump(ipw);
            }
        }
    }
}
