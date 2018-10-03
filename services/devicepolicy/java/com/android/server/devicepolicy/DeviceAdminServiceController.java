/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.devicepolicy;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DeviceAdminService;
import android.app.admin.DevicePolicyManager;
import android.app.admin.IDeviceAdminService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.server.am.PersistentConnection;
import com.android.server.appbinding.AppBindingUtils;

import java.io.PrintWriter;

/**
 * Manages connections to persistent services in owner packages.
 */
public class DeviceAdminServiceController {
    static final String TAG = DevicePolicyManagerService.LOG_TAG;

    static final boolean DEBUG = false; // DO NOT MERGE WITH TRUE.

    final Object mLock = new Object();
    final Context mContext;

    private final DevicePolicyManagerService mService;
    private final DevicePolicyManagerService.Injector mInjector;
    private final DevicePolicyConstants mConstants;

    private final Handler mHandler; // needed?

    static void debug(String format, Object... args) {
        if (!DEBUG) {
            return;
        }
        Slog.d(TAG, String.format(format, args));
    }

    private class DevicePolicyServiceConnection
            extends PersistentConnection<IDeviceAdminService> {
        public DevicePolicyServiceConnection(int userId, @NonNull ComponentName componentName) {
            super(TAG, mContext, mHandler, userId, componentName,
                    mConstants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_SEC,
                    mConstants.DAS_DIED_SERVICE_RECONNECT_BACKOFF_INCREASE,
                    mConstants.DAS_DIED_SERVICE_RECONNECT_MAX_BACKOFF_SEC,
                    mConstants.DAS_DIED_SERVICE_STABLE_CONNECTION_THRESHOLD_SEC);
        }

        @Override
        protected int getBindFlags() {
            return Context.BIND_FOREGROUND_SERVICE;
        }

        @Override
        protected IDeviceAdminService asInterface(IBinder binder) {
            return IDeviceAdminService.Stub.asInterface(binder);
        }
    }

    /**
     * User-ID -> {@link PersistentConnection}.
     */
    @GuardedBy("mLock")
    private final SparseArray<DevicePolicyServiceConnection> mConnections = new SparseArray<>();

    public DeviceAdminServiceController(DevicePolicyManagerService service,
            DevicePolicyConstants constants) {
        mService = service;
        mInjector = service.mInjector;
        mContext = mInjector.mContext;
        mHandler = new Handler(BackgroundThread.get().getLooper());
        mConstants = constants;
    }

    /**
     * Find a service that handles {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE}
     * in a given package.
     */
    @Nullable
    private ServiceInfo findService(@NonNull String packageName, int userId) {
        return AppBindingUtils.findService(
                packageName,
                userId,
                DevicePolicyManager.ACTION_DEVICE_ADMIN_SERVICE,
                permission.BIND_DEVICE_ADMIN,
                DeviceAdminService.class,
                mInjector.getIPackageManager(),
                new StringBuilder() /* ignore error message */);
    }

    /**
     * Find a service that handles {@link DevicePolicyManager#ACTION_DEVICE_ADMIN_SERVICE}
     * in an owner package and connect to it.
     */
    public void startServiceForOwner(@NonNull String packageName, int userId,
            @NonNull String actionForLog) {
        final long token = mInjector.binderClearCallingIdentity();
        try {
            synchronized (mLock) {
                final ServiceInfo service = findService(packageName, userId);
                if (service == null) {
                    debug("Owner package %s on u%d has no service.",
                            packageName, userId);
                    disconnectServiceOnUserLocked(userId, actionForLog);
                    return;
                }
                // See if it's already running.
                final PersistentConnection<IDeviceAdminService> existing =
                        mConnections.get(userId);
                if (existing != null) {
                    // Note even when we're already connected to the same service, the binding
                    // would have died at this point due to a package update.  So we disconnect
                    // anyway and re-connect.
                    debug("Disconnecting from existing service connection.",
                            packageName, userId);
                    disconnectServiceOnUserLocked(userId, actionForLog);
                }

                debug("Owner package %s on u%d has service %s for %s",
                        packageName, userId,
                        service.getComponentName().flattenToShortString(), actionForLog);

                final DevicePolicyServiceConnection conn =
                        new DevicePolicyServiceConnection(
                                userId, service.getComponentName());
                mConnections.put(userId, conn);
                conn.bind();
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    /**
     * Stop an owner service on a given user.
     */
    public void stopServiceForOwner(int userId, @NonNull String actionForLog) {
        final long token = mInjector.binderClearCallingIdentity();
        try {
            synchronized (mLock) {
                disconnectServiceOnUserLocked(userId, actionForLog);
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    @GuardedBy("mLock")
    private void disconnectServiceOnUserLocked(int userId, @NonNull String actionForLog) {
        final DevicePolicyServiceConnection conn = mConnections.get(userId);
        if (conn != null) {
            debug("Stopping service for u%d if already running for %s.",
                    userId, actionForLog);
            conn.unbind();
            mConnections.remove(userId);
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        synchronized (mLock) {
            if (mConnections.size() == 0) {
                return;
            }
            pw.println();
            pw.print(prefix); pw.println("Owner Services:");
            for (int i = 0; i < mConnections.size(); i++) {
                final int userId = mConnections.keyAt(i);
                pw.print(prefix); pw.print("  "); pw.print("User: "); pw.println(userId);

                final DevicePolicyServiceConnection con = mConnections.valueAt(i);
                con.dump(prefix + "    ", pw);
            }
            pw.println();
        }
    }
}
