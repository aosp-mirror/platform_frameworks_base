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

import static com.android.server.rotationresolver.RotationResolverManagerService.RESOLUTION_UNAVAILABLE;
import static com.android.server.rotationresolver.RotationResolverManagerService.getServiceConfigPackage;
import static com.android.server.rotationresolver.RotationResolverManagerService.logRotationStats;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.CancellationSignal;
import android.rotationresolver.RotationResolverInternal;
import android.service.rotationresolver.RotationResolverService;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
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

    RotationResolverManagerPerUserService(@NonNull RotationResolverManagerService main,
            @NonNull Object lock, @UserIdInt int userId) {
        super(main, lock, userId);
    }

    @GuardedBy("mLock")
    void destroyLocked() {
        if (isVerbose()) {
            Slog.v(TAG, "destroyLocked()");
        }

        if (mCurrentRequest == null) {
            return;
        }
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
            @Surface.Rotation int proposedRotation, @Surface.Rotation int currentRotation,
            String packageName, long timeoutMillis,
            @NonNull CancellationSignal cancellationSignalInternal) {

        if (!isServiceAvailableLocked()) {
            Slog.w(TAG, "Service is not available at this moment.");
            callbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
            logRotationStats(proposedRotation, currentRotation, RESOLUTION_UNAVAILABLE);
            return;
        }

        ensureRemoteServiceInitiated();

        // Cancel the previous on-going request.
        if (mCurrentRequest != null) {
            cancelLocked();
        }

        mCurrentRequest = new RemoteRotationResolverService.RotationRequest(callbackInternal,
                proposedRotation, currentRotation, packageName, timeoutMillis,
                cancellationSignalInternal);

        cancellationSignalInternal.setOnCancelListener(() -> {
            synchronized (mLock) {
                Slog.i(TAG, "Trying to cancel current request.");
                mCurrentRequest.cancelInternal();
            }
        });

        if (mRemoteService != null) {
            mRemoteService.resolveRotationLocked(mCurrentRequest);
            mCurrentRequest.mIsDispatched = true;
        } else {
            Slog.w(TAG, "Remote service is not available at this moment.");
            callbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
            cancelLocked();
        }
    }

    @GuardedBy("mLock")
    private void ensureRemoteServiceInitiated() {
        if (mRemoteService == null) {
            mRemoteService = new RemoteRotationResolverService(getContext(), mComponentName,
                    getUserId(), CONNECTION_TTL_MILLIS, mLock);
        }
    }

    /**
     * Provides rotation resolver service component name at runtime, making sure it's provided
     * by the system.
     */
    private static ComponentName resolveRotationResolverService(Context context) {
        final String serviceConfigPackage = getServiceConfigPackage(context);

        String resolvedPackage;
        int flags = PackageManager.MATCH_SYSTEM_ONLY;

        if (!TextUtils.isEmpty(serviceConfigPackage)) {
            resolvedPackage = serviceConfigPackage;
        } else {
            return null;
        }

        final Intent intent = new Intent(
                RotationResolverService.SERVICE_INTERFACE).setPackage(resolvedPackage);

        final ResolveInfo resolveInfo = context.getPackageManager().resolveServiceAsUser(intent,
                flags, context.getUserId());
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.wtf(TAG, String.format("Service %s not found in package %s",
                    RotationResolverService.SERVICE_INTERFACE, serviceConfigPackage
            ));
            return null;
        }

        final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        final String permission = serviceInfo.permission;
        if (Manifest.permission.BIND_ROTATION_RESOLVER_SERVICE.equals(permission)) {
            return serviceInfo.getComponentName();
        }
        Slog.e(TAG, String.format(
                "Service %s should require %s permission. Found %s permission",
                serviceInfo.getComponentName(),
                Manifest.permission.BIND_ROTATION_RESOLVER_SERVICE,
                serviceInfo.permission));
        return null;
    }


    /** Resolves and sets up the rotation resolver service if it had not been done yet. */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean isServiceAvailableLocked() {
        if (mComponentName == null) {
            mComponentName = resolveRotationResolverService(getContext());
        }
        return mComponentName != null;
    }


    @GuardedBy("mLock")
    private void cancelLocked() {
        if (mCurrentRequest == null) {
            return;
        }

        if (mCurrentRequest.mIsFulfilled) {
            if (isVerbose()) {
                Slog.d(TAG, "Trying to cancel the request that has been already fulfilled.");
            }
            mCurrentRequest = null;
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
