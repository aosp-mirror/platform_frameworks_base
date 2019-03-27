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

package com.android.server;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_HEALTH_CHECK_PASSED_PACKAGE;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_REQUESTED_PACKAGES;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_SUPPORTED_PACKAGES;

import android.Manifest;
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.watchdog.ExplicitHealthCheckService;
import android.service.watchdog.IExplicitHealthCheckService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.List;
import java.util.function.Consumer;

/**
 * Controls the connections with {@link ExplicitHealthCheckService}.
 */
class ExplicitHealthCheckController {
    private static final String TAG = "ExplicitHealthCheckController";
    private final Object mLock = new Object();
    private final Context mContext;
    @GuardedBy("mLock") @Nullable private StateCallback mStateCallback;
    @GuardedBy("mLock") @Nullable private IExplicitHealthCheckService mRemoteService;
    @GuardedBy("mLock") @Nullable private ServiceConnection mConnection;
    @GuardedBy("mLock") @Nullable private List<String> mSupportedPackages;

    ExplicitHealthCheckController(Context context) {
        mContext = context;
    }

    /**
     * Requests an explicit health check for {@code packageName}.
     * After this request, the callback registered on {@link startService} can receive explicit
     * health check passed results.
     *
     * @throws IllegalStateException if the service is not started
     */
    public void request(String packageName) throws RemoteException {
        synchronized (mLock) {
            enforceServiceReadyLocked();
            mRemoteService.request(packageName);
        }
    }

    /**
     * Cancels all explicit health checks for {@code packageName}.
     * After this request, the callback registered on {@link startService} can no longer receive
     * explicit health check passed results.
     *
     * @throws IllegalStateException if the service is not started
     */
    public void cancel(String packageName) throws RemoteException {
        synchronized (mLock) {
            enforceServiceReadyLocked();
            mRemoteService.cancel(packageName);
        }
    }

    /**
     * Returns the packages that we can request explicit health checks for.
     * The packages will be returned to the {@code consumer}.
     *
     * @throws IllegalStateException if the service is not started
     */
    public void getSupportedPackages(Consumer<List<String>> consumer) throws RemoteException {
        synchronized (mLock) {
            enforceServiceReadyLocked();
            if (mSupportedPackages == null) {
                mRemoteService.getSupportedPackages(new RemoteCallback(result -> {
                    mSupportedPackages = result.getStringArrayList(EXTRA_SUPPORTED_PACKAGES);
                    consumer.accept(mSupportedPackages);
                }));
            } else {
                consumer.accept(mSupportedPackages);
            }
        }
    }

    /**
     * Returns the packages for which health checks are currently in progress.
     * The packages will be returned to the {@code consumer}.
     *
     * @throws IllegalStateException if the service is not started
     */
    public void getRequestedPackages(Consumer<List<String>> consumer) throws RemoteException {
        synchronized (mLock) {
            enforceServiceReadyLocked();
            mRemoteService.getRequestedPackages(new RemoteCallback(
                    result -> consumer.accept(
                            result.getStringArrayList(EXTRA_REQUESTED_PACKAGES))));
        }
    }

    /**
     * Starts the explicit health check service.
     *
     * @param stateCallback will receive important state changes changes
     * @param passedConsumer will accept packages that pass explicit health checks
     *
     * @throws IllegalStateException if the service is already started
     */
    public void startService(StateCallback stateCallback, Consumer<String> passedConsumer) {
        synchronized (mLock) {
            if (mRemoteService != null) {
                throw new IllegalStateException("Explicit health check service already started.");
            }
            mStateCallback = stateCallback;
            mConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    synchronized (mLock) {
                        mRemoteService = IExplicitHealthCheckService.Stub.asInterface(service);
                        try {
                            mRemoteService.setCallback(new RemoteCallback(result -> {
                                String packageName =
                                        result.getString(EXTRA_HEALTH_CHECK_PASSED_PACKAGE);
                                if (!TextUtils.isEmpty(packageName)) {
                                    passedConsumer.accept(packageName);
                                } else {
                                    Slog.w(TAG, "Empty package passed explicit health check?");
                                }
                            }));
                            mStateCallback.onStart();
                            Slog.i(TAG, "Explicit health check service is connected " + name);
                        } catch (RemoteException e) {
                            Slog.wtf(TAG, "Coud not setCallback on explicit health check service");
                        }
                    }
                }

                @Override
                @MainThread
                public void onServiceDisconnected(ComponentName name) {
                    resetState();
                    Slog.i(TAG, "Explicit health check service is disconnected " + name);
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    resetState();
                    Slog.i(TAG, "Explicit health check service binding is dead " + name);
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    resetState();
                    Slog.i(TAG, "Explicit health check service binding is null " + name);
                }
            };

            ComponentName component = getServiceComponentNameLocked();
            if (component != null) {
                Intent intent = new Intent();
                intent.setComponent(component);
                mContext.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE,
                        UserHandle.of(UserHandle.USER_SYSTEM));
            }
        }
    }

    // TODO: Differentiate between expected vs unexpected stop?
    /** Callback to receive important {@link ExplicitHealthCheckController} state changes. */
    abstract static class StateCallback {
        /** The controller is ready and we can request explicit health checks for packages */
        public void onStart() {}

        /** The controller is not ready and we cannot request explicit health checks for packages */
        public void onStop() {}
    }

    /** Stops the explicit health check service. */
    public void stopService() {
        synchronized (mLock) {
            if (mRemoteService != null) {
                mContext.unbindService(mConnection);
            }
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private ServiceInfo getServiceInfoLocked() {
        final String packageName =
                mContext.getPackageManager().getServicesSystemSharedLibraryPackageName();
        if (packageName == null) {
            Slog.w(TAG, "no external services package!");
            return null;
        }

        final Intent intent = new Intent(ExplicitHealthCheckService.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            Slog.w(TAG, "No valid components found.");
            return null;
        }
        return resolveInfo.serviceInfo;
    }

    @GuardedBy("mLock")
    @Nullable
    private ComponentName getServiceComponentNameLocked() {
        final ServiceInfo serviceInfo = getServiceInfoLocked();
        if (serviceInfo == null) {
            return null;
        }

        final ComponentName name = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        if (!Manifest.permission.BIND_EXPLICIT_HEALTH_CHECK_SERVICE
                .equals(serviceInfo.permission)) {
            Slog.w(TAG, name.flattenToShortString() + " does not require permission "
                    + Manifest.permission.BIND_EXPLICIT_HEALTH_CHECK_SERVICE);
            return null;
        }
        return name;
    }

    private void resetState() {
        synchronized (mLock) {
            mStateCallback.onStop();
            mStateCallback = null;
            mSupportedPackages = null;
            mRemoteService = null;
            mConnection = null;
        }
    }

    @GuardedBy("mLock")
    private void enforceServiceReadyLocked() {
        if (mRemoteService == null) {
            throw new IllegalStateException("Explicit health check service not ready");
        }
    }
}
