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

package com.android.server.systemcaptions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

/** Manages the captions manager service on a per-user basis. */
final class SystemCaptionsManagerPerUserService extends
        AbstractPerUserSystemService<SystemCaptionsManagerPerUserService,
                SystemCaptionsManagerService> {

    private static final String TAG = SystemCaptionsManagerPerUserService.class.getSimpleName();

    @Nullable
    @GuardedBy("mLock")
    private RemoteSystemCaptionsManagerService mRemoteService;

    SystemCaptionsManagerPerUserService(
            @NonNull SystemCaptionsManagerService master,
            @NonNull Object lock, boolean disabled, @UserIdInt int userId) {
        super(master, lock, userId);
    }

    @Override
    @NonNull
    protected ServiceInfo newServiceInfoLocked(
            @SuppressWarnings("unused") @NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException {
        try {
            return AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException e) {
            throw new PackageManager.NameNotFoundException(
                    "Could not get service for " + serviceComponent);
        }
    }

    @GuardedBy("mLock")
    void initializeLocked() {
        if (mMaster.verbose) {
            Slog.v(TAG, "initialize()");
        }

        RemoteSystemCaptionsManagerService service = getRemoteServiceLocked();
        if (service == null && mMaster.verbose) {
            Slog.v(TAG, "initialize(): Failed to init remote server");
        }
    }

    @GuardedBy("mLock")
    void destroyLocked() {
        if (mMaster.verbose) {
            Slog.v(TAG, "destroyLocked()");
        }

        if (mRemoteService != null) {
            mRemoteService.destroy();
            mRemoteService = null;
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private RemoteSystemCaptionsManagerService getRemoteServiceLocked() {
        if (mRemoteService == null) {
            String serviceName = getComponentNameLocked();
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "getRemoteServiceLocked(): Not set");
                }
                return null;
            }

            ComponentName serviceComponent = ComponentName.unflattenFromString(serviceName);
            mRemoteService = new RemoteSystemCaptionsManagerService(
                    getContext(),
                    serviceComponent,
                    mUserId,
                    mMaster.verbose);
            if (mMaster.verbose) {
                Slog.v(TAG, "getRemoteServiceLocked(): initialize for user " + mUserId);
            }
            mRemoteService.initialize();
        }

        return mRemoteService;
    }
}
