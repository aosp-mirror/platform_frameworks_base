/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wearable;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.wearable.WearableSensingManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.system.OsConstants;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.infra.AbstractPerUserSystemService;

import java.io.PrintWriter;

/**
 * Per-user manager service for managing sensing {@link AmbientContextEvent}s on Wearables.
 */
final class WearableSensingManagerPerUserService extends
        AbstractPerUserSystemService<WearableSensingManagerPerUserService,
                WearableSensingManagerService> {
    private static final String TAG = WearableSensingManagerPerUserService.class.getSimpleName();

    @Nullable
    @VisibleForTesting
    RemoteWearableSensingService mRemoteService;

    private ComponentName mComponentName;

    WearableSensingManagerPerUserService(
            @NonNull WearableSensingManagerService master, Object lock, @UserIdInt int userId) {
        super(master, lock, userId);
    }

    static void notifyStatusCallback(RemoteCallback statusCallback, int statusCode) {
        Bundle bundle = new Bundle();
        bundle.putInt(
                WearableSensingManager.STATUS_RESPONSE_BUNDLE_KEY, statusCode);
        statusCallback.sendResult(bundle);
    }

    void destroyLocked() {
        Slog.d(TAG, "Trying to cancel the remote request. Reason: Service destroyed.");
        if (mRemoteService != null) {
            synchronized (mLock) {
                mRemoteService.unbind();
                mRemoteService = null;
            }
        }
    }

    @GuardedBy("mLock")
    private void ensureRemoteServiceInitiated() {
        if (mRemoteService == null) {
            mRemoteService = new RemoteWearableSensingService(
                    getContext(), mComponentName, getUserId());
        }
    }

    /**
     * get the currently bound component name.
     */
    @VisibleForTesting
    ComponentName getComponentName() {
        return mComponentName;
    }


    /**
     * Resolves and sets up the service if it had not been done yet. Returns true if the service
     * is available.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    boolean setUpServiceIfNeeded() {
        if (mComponentName == null) {
            mComponentName = updateServiceInfoLocked();
        }
        if (mComponentName == null) {
            return false;
        }

        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                    mComponentName, 0, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while setting up service");
            return false;
        }
        return serviceInfo != null;
    }

    @Override
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        ServiceInfo serviceInfo;
        try {
            serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    0, mUserId);
            if (serviceInfo != null) {
                final String permission = serviceInfo.permission;
                if (!Manifest.permission.BIND_WEARABLE_SENSING_SERVICE.equals(
                        permission)) {
                    throw new SecurityException(String.format(
                            "Service %s requires %s permission. Found %s permission",
                            serviceInfo.getComponentName(),
                            Manifest.permission.BIND_WEARABLE_SENSING_SERVICE,
                            serviceInfo.permission));
                }
            }
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
        return serviceInfo;
    }

    @Override
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        synchronized (super.mLock) {
            super.dumpLocked(prefix, pw);
        }
        if (mRemoteService != null) {
            mRemoteService.dump("", new IndentingPrintWriter(pw, "  "));
        }
    }

    /**
     * Handles sending the provided data stream for the wearable to the wearable sensing service.
     */
    public void onProvideDataStream(
            ParcelFileDescriptor parcelFileDescriptor,
            RemoteCallback callback) {
        Slog.i(TAG, "onProvideDataStream in per user service.");
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(callback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            Slog.i(TAG, "calling over to remote servvice.");
            ensureRemoteServiceInitiated();
            mRemoteService.provideDataStream(parcelFileDescriptor, callback);
        }
    }

    /**
     * Handles sending the provided data to the wearable sensing service.
     */
    public void onProvidedData(PersistableBundle data,
            SharedMemory sharedMemory,
            RemoteCallback callback) {
        synchronized (mLock) {
            if (!setUpServiceIfNeeded()) {
                Slog.w(TAG, "Detection service is not available at this moment.");
                notifyStatusCallback(callback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            ensureRemoteServiceInitiated();
            if (sharedMemory != null) {
                sharedMemory.setProtect(OsConstants.PROT_READ);
            }
            mRemoteService.provideData(data, sharedMemory, callback);
        }
    }
}
