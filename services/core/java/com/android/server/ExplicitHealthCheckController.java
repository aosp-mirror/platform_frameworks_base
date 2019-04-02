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
import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Controls the connections with {@link ExplicitHealthCheckService}.
 */
class ExplicitHealthCheckController {
    private static final String TAG = "ExplicitHealthCheckController";
    private final Object mLock = new Object();
    private final Context mContext;

    // Called everytime the service is connected, so the watchdog can sync it's state with
    // the health check service. In practice, should never be null after it has been #setEnabled.
    @GuardedBy("mLock") @Nullable private Runnable mOnConnected;
    // Called everytime a package passes the health check, so the watchdog is notified of the
    // passing check. In practice, should never be null after it has been #setEnabled.
    @GuardedBy("mLock") @Nullable private Consumer<String> mPassedConsumer;
    // Actual binder object to the explicit health check service.
    @GuardedBy("mLock") @Nullable private IExplicitHealthCheckService mRemoteService;
    // Cache for packages supporting explicit health checks. This cache should not change while
    // the health check service is running.
    @GuardedBy("mLock") @Nullable private List<String> mSupportedPackages;
    // Connection to the explicit health check service, necessary to unbind
    @GuardedBy("mLock") @Nullable private ServiceConnection mConnection;
    // Bind state of the explicit health check service.
    @GuardedBy("mLock") private boolean mEnabled;

    ExplicitHealthCheckController(Context context) {
        mContext = context;
    }

    /**
     * Requests an explicit health check for {@code packageName}.
     * After this request, the callback registered on {@link #setCallbacks} can receive explicit
     * health check passed results.
     *
     * @throws IllegalStateException if the service is not started
     */
    public void request(String packageName) throws RemoteException {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }

            enforceServiceReadyLocked();

            Slog.i(TAG, "Requesting health check for package " + packageName);
            mRemoteService.request(packageName);
        }
    }

    /**
     * Cancels all explicit health checks for {@code packageName}.
     * After this request, the callback registered on {@link #setCallbacks} can no longer receive
     * explicit health check passed results.
     *
     * @throws IllegalStateException if the service is not started
     */
    public void cancel(String packageName) throws RemoteException {
        synchronized (mLock) {
            if (!mEnabled) {
                return;
            }

            enforceServiceReadyLocked();

            Slog.i(TAG, "Cancelling health check for package " + packageName);
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
            if (!mEnabled) {
                consumer.accept(Collections.emptyList());
                return;
            }

            enforceServiceReadyLocked();

            if (mSupportedPackages == null) {
                Slog.d(TAG, "Getting health check supported packages");
                mRemoteService.getSupportedPackages(new RemoteCallback(result -> {
                    mSupportedPackages = result.getStringArrayList(EXTRA_SUPPORTED_PACKAGES);
                    consumer.accept(mSupportedPackages);
                }));
            } else {
                Slog.d(TAG, "Getting cached health check supported packages");
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
            if (!mEnabled) {
                consumer.accept(Collections.emptyList());
                return;
            }

            enforceServiceReadyLocked();

            Slog.d(TAG, "Getting health check requested packages");
            mRemoteService.getRequestedPackages(new RemoteCallback(
                    result -> consumer.accept(
                            result.getStringArrayList(EXTRA_REQUESTED_PACKAGES))));
        }
    }

    /** Enables or disables explicit health checks. */
    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            if (enabled == mEnabled) {
                return;
            }

            Slog.i(TAG, "Setting explicit health checks enabled " + enabled);
            mEnabled = enabled;
            if (enabled) {
                bindService();
            } else {
                unbindService();
            }
        }
    }

    /**
     * Sets callbacks to listen to important events from the controller.
     * Should be called at initialization.
     */
    public void setCallbacks(Runnable onConnected, Consumer<String> passedConsumer) {
        Preconditions.checkNotNull(onConnected);
        Preconditions.checkNotNull(passedConsumer);
        mOnConnected = onConnected;
        mPassedConsumer = passedConsumer;
    }

    /** Binds to the explicit health check service. */
    private void bindService() {
        synchronized (mLock) {
            if (mRemoteService != null) {
                return;
            }
            ComponentName component = getServiceComponentNameLocked();
            if (component == null) {
                Slog.wtf(TAG, "Explicit health check service not found");
                return;
            }

            Intent intent = new Intent();
            intent.setComponent(component);
            // TODO: Fix potential race conditions during mConnection state transitions.
            // E.g after #onServiceDisconected, the mRemoteService object is invalid until
            // we get an #onServiceConnected.
            mConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    initState(service);
                    Slog.i(TAG, "Explicit health check service is connected " + name);
                }

                @Override
                @MainThread
                public void onServiceDisconnected(ComponentName name) {
                    // Service crashed or process was killed, #onServiceConnected will be called.
                    // Don't need to re-bind.
                    Slog.i(TAG, "Explicit health check service is disconnected " + name);
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    // Application hosting service probably got updated
                    // Need to re-bind.
                    synchronized (mLock) {
                        if (mEnabled) {
                            unbindService();
                            bindService();
                        }
                    }
                    Slog.i(TAG, "Explicit health check service binding is dead " + name);
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    // Should never happen. Service returned null from #onBind.
                    Slog.wtf(TAG, "Explicit health check service binding is null?? " + name);
                }
            };

            Slog.i(TAG, "Binding to explicit health service");
            mContext.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE,
                    UserHandle.of(UserHandle.USER_SYSTEM));
        }
    }

    /** Unbinds the explicit health check service. */
    private void unbindService() {
        synchronized (mLock) {
            if (mRemoteService != null) {
                Slog.i(TAG, "Unbinding from explicit health service");
                mContext.unbindService(mConnection);
                mRemoteService = null;
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

    private void initState(IBinder service) {
        synchronized (mLock) {
            mSupportedPackages = null;
            mRemoteService = IExplicitHealthCheckService.Stub.asInterface(service);
            try {
                mRemoteService.setCallback(new RemoteCallback(result -> {
                    String packageName = result.getString(EXTRA_HEALTH_CHECK_PASSED_PACKAGE);
                    if (!TextUtils.isEmpty(packageName)) {
                        synchronized (mLock) {
                            if (mPassedConsumer == null) {
                                Slog.w(TAG, "Health check passed for package " + packageName
                                        + "but no consumer registered.");
                            } else {
                                mPassedConsumer.accept(packageName);
                            }
                        }
                    } else {
                        Slog.w(TAG, "Empty package passed explicit health check?");
                    }
                }));
                if (mOnConnected == null) {
                    Slog.w(TAG, "Health check service connected but no runnable registered.");
                } else {
                    mOnConnected.run();
                }
            } catch (RemoteException e) {
                Slog.wtf(TAG, "Could not setCallback on explicit health check service");
            }
        }
    }

    @GuardedBy("mLock")
    private void enforceServiceReadyLocked() {
        if (mRemoteService == null) {
            // TODO: Try to bind to service
            throw new IllegalStateException("Explicit health check service not ready");
        }
    }
}
