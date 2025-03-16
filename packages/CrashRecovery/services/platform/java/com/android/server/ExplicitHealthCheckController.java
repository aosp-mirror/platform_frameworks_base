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
import static android.crashrecovery.flags.Flags.refactorCrashrecovery;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_HEALTH_CHECK_PASSED_PACKAGE;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_REQUESTED_PACKAGES;
import static android.service.watchdog.ExplicitHealthCheckService.EXTRA_SUPPORTED_PACKAGES;
import static android.service.watchdog.ExplicitHealthCheckService.PackageConfig;

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
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

// TODO(b/120598832): Add tests
/**
 * Controls the connections with {@link ExplicitHealthCheckService}.
 */
class ExplicitHealthCheckController {
    private static final String TAG = "ExplicitHealthCheckController";
    private final Object mLock = new Object();
    private final Context mContext;

    // Called everytime a package passes the health check, so the watchdog is notified of the
    // passing check. In practice, should never be null after it has been #setEnabled.
    // To prevent deadlocks between the controller and watchdog threads, we have
    // a lock invariant to ALWAYS acquire the PackageWatchdog#mLock before #mLock in this class.
    // It's easier to just NOT hold #mLock when calling into watchdog code on this consumer.
    @GuardedBy("mLock") @Nullable private Consumer<String> mPassedConsumer;
    // Called everytime after a successful #syncRequest call, so the watchdog can receive packages
    // supporting health checks and update its internal state. In practice, should never be null
    // after it has been #setEnabled.
    // To prevent deadlocks between the controller and watchdog threads, we have
    // a lock invariant to ALWAYS acquire the PackageWatchdog#mLock before #mLock in this class.
    // It's easier to just NOT hold #mLock when calling into watchdog code on this consumer.
    @GuardedBy("mLock") @Nullable private Consumer<List<PackageConfig>> mSupportedConsumer;
    // Called everytime we need to notify the watchdog to sync requests between itself and the
    // health check service. In practice, should never be null after it has been #setEnabled.
    // To prevent deadlocks between the controller and watchdog threads, we have
    // a lock invariant to ALWAYS acquire the PackageWatchdog#mLock before #mLock in this class.
    // It's easier to just NOT hold #mLock when calling into watchdog code on this runnable.
    @GuardedBy("mLock") @Nullable private Runnable mNotifySyncRunnable;
    // Actual binder object to the explicit health check service.
    @GuardedBy("mLock") @Nullable private IExplicitHealthCheckService mRemoteService;
    // Connection to the explicit health check service, necessary to unbind.
    // We should only try to bind if mConnection is null, non-null indicates we
    // are connected or at least connecting.
    @GuardedBy("mLock") @Nullable private ServiceConnection mConnection;
    // Bind state of the explicit health check service.
    @GuardedBy("mLock") private boolean mEnabled;

    ExplicitHealthCheckController(Context context) {
        mContext = context;
    }

    /** Enables or disables explicit health checks. */
    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            Slog.i(TAG, "Explicit health checks " + (enabled ? "enabled." : "disabled."));
            mEnabled = enabled;
        }
    }

    /**
     * Sets callbacks to listen to important events from the controller.
     *
     * <p> Should be called once at initialization before any other calls to the controller to
     * ensure a happens-before relationship of the set parameters and visibility on other threads.
     */
    public void setCallbacks(Consumer<String> passedConsumer,
            Consumer<List<PackageConfig>> supportedConsumer, Runnable notifySyncRunnable) {
        synchronized (mLock) {
            if (mPassedConsumer != null || mSupportedConsumer != null
                    || mNotifySyncRunnable != null) {
                Slog.wtf(TAG, "Resetting health check controller callbacks");
            }

            mPassedConsumer = Objects.requireNonNull(passedConsumer);
            mSupportedConsumer = Objects.requireNonNull(supportedConsumer);
            mNotifySyncRunnable = Objects.requireNonNull(notifySyncRunnable);
        }
    }

    /**
     * Calls the health check service to request or cancel packages based on
     * {@code newRequestedPackages}.
     *
     * <p> Supported packages in {@code newRequestedPackages} that have not been previously
     * requested will be requested while supported packages not in {@code newRequestedPackages}
     * but were previously requested will be cancelled.
     *
     * <p> This handles binding and unbinding to the health check service as required.
     *
     * <p> Note, calling this may modify {@code newRequestedPackages}.
     *
     * <p> Note, this method is not thread safe, all calls should be serialized.
     */
    public void syncRequests(Set<String> newRequestedPackages) {
        boolean enabled;
        synchronized (mLock) {
            enabled = mEnabled;
        }

        if (!enabled) {
            Slog.i(TAG, "Health checks disabled, no supported packages");
            // Call outside lock
            mSupportedConsumer.accept(Collections.emptyList());
            return;
        }

        getSupportedPackages(supportedPackageConfigs -> {
            // Notify the watchdog without lock held
            mSupportedConsumer.accept(supportedPackageConfigs);
            getRequestedPackages(previousRequestedPackages -> {
                synchronized (mLock) {
                    // Hold lock so requests and cancellations are sent atomically.
                    // It is important we don't mix requests from multiple threads.

                    Set<String> supportedPackages = new ArraySet<>();
                    for (PackageConfig config : supportedPackageConfigs) {
                        supportedPackages.add(config.getPackageName());
                    }
                    // Note, this may modify newRequestedPackages
                    newRequestedPackages.retainAll(supportedPackages);

                    // Cancel packages no longer requested
                    actOnDifference(previousRequestedPackages,
                            newRequestedPackages, p -> cancel(p));
                    // Request packages not yet requested
                    actOnDifference(newRequestedPackages,
                            previousRequestedPackages, p -> request(p));

                    if (newRequestedPackages.isEmpty()) {
                        Slog.i(TAG, "No more health check requests, unbinding...");
                        unbindService();
                        return;
                    }
                }
            });
        });
    }

    private void actOnDifference(Collection<String> collection1, Collection<String> collection2,
            Consumer<String> action) {
        Iterator<String> iterator = collection1.iterator();
        while (iterator.hasNext()) {
            String packageName = iterator.next();
            if (!collection2.contains(packageName)) {
                action.accept(packageName);
            }
        }
    }

    /**
     * Requests an explicit health check for {@code packageName}.
     * After this request, the callback registered on {@link #setCallbacks} can receive explicit
     * health check passed results.
     */
    private void request(String packageName) {
        synchronized (mLock) {
            if (!prepareServiceLocked("request health check for " + packageName)) {
                return;
            }

            Slog.i(TAG, "Requesting health check for package " + packageName);
            try {
                mRemoteService.request(packageName);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to request health check for package " + packageName, e);
            }
        }
    }

    /**
     * Cancels all explicit health checks for {@code packageName}.
     * After this request, the callback registered on {@link #setCallbacks} can no longer receive
     * explicit health check passed results.
     */
    private void cancel(String packageName) {
        synchronized (mLock) {
            if (!prepareServiceLocked("cancel health check for " + packageName)) {
                return;
            }

            Slog.i(TAG, "Cancelling health check for package " + packageName);
            try {
                mRemoteService.cancel(packageName);
            } catch (RemoteException e) {
                // Do nothing, if the service is down, when it comes up, we will sync requests,
                // if there's some other error, retrying wouldn't fix anyways.
                Slog.w(TAG, "Failed to cancel health check for package " + packageName, e);
            }
        }
    }

    /**
     * Returns the packages that we can request explicit health checks for.
     * The packages will be returned to the {@code consumer}.
     */
    private void getSupportedPackages(Consumer<List<PackageConfig>> consumer) {
        synchronized (mLock) {
            if (!prepareServiceLocked("get health check supported packages")) {
                return;
            }

            Slog.d(TAG, "Getting health check supported packages");
            try {
                mRemoteService.getSupportedPackages(new RemoteCallback(result -> {
                    List<PackageConfig> packages =
                            result.getParcelableArrayList(EXTRA_SUPPORTED_PACKAGES, android.service.watchdog.ExplicitHealthCheckService.PackageConfig.class);
                    Slog.i(TAG, "Explicit health check supported packages " + packages);
                    consumer.accept(packages);
                }));
            } catch (RemoteException e) {
                // Request failed, treat as if all observed packages are supported, if any packages
                // expire during this period, we may incorrectly treat it as failing health checks
                // even if we don't support health checks for the package.
                Slog.w(TAG, "Failed to get health check supported packages", e);
            }
        }
    }

    /**
     * Returns the packages for which health checks are currently in progress.
     * The packages will be returned to the {@code consumer}.
     */
    private void getRequestedPackages(Consumer<List<String>> consumer) {
        synchronized (mLock) {
            if (!prepareServiceLocked("get health check requested packages")) {
                return;
            }

            Slog.d(TAG, "Getting health check requested packages");
            try {
                mRemoteService.getRequestedPackages(new RemoteCallback(result -> {
                    List<String> packages = result.getStringArrayList(EXTRA_REQUESTED_PACKAGES);
                    Slog.i(TAG, "Explicit health check requested packages " + packages);
                    consumer.accept(packages);
                }));
            } catch (RemoteException e) {
                // Request failed, treat as if we haven't requested any packages, if any packages
                // were actually requested, they will not be cancelled now. May be cancelled later
                Slog.w(TAG, "Failed to get health check requested packages", e);
            }
        }
    }

    /**
     * Binds to the explicit health check service if the controller is enabled and
     * not already bound.
     */
    private void bindService() {
        synchronized (mLock) {
            if (!mEnabled || mConnection != null || mRemoteService != null) {
                if (!mEnabled) {
                    Slog.i(TAG, "Not binding to service, service disabled");
                } else if (mRemoteService != null) {
                    Slog.i(TAG, "Not binding to service, service already connected");
                } else {
                    Slog.i(TAG, "Not binding to service, service already connecting");
                }
                return;
            }
            ComponentName component = getServiceComponentNameLocked();
            if (component == null) {
                Slog.wtf(TAG, "Explicit health check service not found");
                return;
            }

            Intent intent = new Intent();
            intent.setComponent(component);
            mConnection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    Slog.i(TAG, "Explicit health check service is connected " + name);
                    initState(service);
                }

                @Override
                @MainThread
                public void onServiceDisconnected(ComponentName name) {
                    // Service crashed or process was killed, #onServiceConnected will be called.
                    // Don't need to re-bind.
                    Slog.i(TAG, "Explicit health check service is disconnected " + name);
                    synchronized (mLock) {
                        mRemoteService = null;
                    }
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    // Application hosting service probably got updated
                    // Need to re-bind.
                    Slog.i(TAG, "Explicit health check service binding is dead. Rebind: " + name);
                    unbindService();
                    bindService();
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    // Should never happen. Service returned null from #onBind.
                    Slog.wtf(TAG, "Explicit health check service binding is null?? " + name);
                }
            };

            mContext.bindServiceAsUser(intent, mConnection,
                    Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
            Slog.i(TAG, "Explicit health check service is bound");
        }
    }

    /** Unbinds the explicit health check service. */
    private void unbindService() {
        synchronized (mLock) {
            if (mRemoteService != null) {
                mContext.unbindService(mConnection);
                mRemoteService = null;
                mConnection = null;
            }
            Slog.i(TAG, "Explicit health check service is unbound");
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private ServiceInfo getServiceInfoLocked() {
        if (refactorCrashrecovery()) {
            final Intent intent = new Intent(ExplicitHealthCheckService.SERVICE_INTERFACE);
            final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(intent,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA
                            |  PackageManager.MATCH_SYSTEM_ONLY);
            if (resolveInfo == null || resolveInfo.serviceInfo == null) {
                Slog.w(TAG, "No valid components found.");
                return null;
            }
            return resolveInfo.serviceInfo;
        } else {
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
            if (!mEnabled) {
                Slog.w(TAG, "Attempting to connect disabled service?? Unbinding...");
                // Very unlikely, but we disabled the service after binding but before we connected
                unbindService();
                return;
            }
            mRemoteService = IExplicitHealthCheckService.Stub.asInterface(service);
            try {
                mRemoteService.setCallback(new RemoteCallback(result -> {
                    String packageName = result.getString(EXTRA_HEALTH_CHECK_PASSED_PACKAGE);
                    if (!TextUtils.isEmpty(packageName)) {
                        if (mPassedConsumer == null) {
                            Slog.wtf(TAG, "Health check passed for package " + packageName
                                    + "but no consumer registered.");
                        } else {
                            // Call without lock held
                            mPassedConsumer.accept(packageName);
                        }
                    } else {
                        Slog.wtf(TAG, "Empty package passed explicit health check?");
                    }
                }));
                Slog.i(TAG, "Service initialized, syncing requests");
            } catch (RemoteException e) {
                Slog.wtf(TAG, "Could not setCallback on explicit health check service");
            }
        }
        // Calling outside lock
        mNotifySyncRunnable.run();
    }

    /**
     * Prepares the health check service to receive requests.
     *
     * @return {@code true} if it is ready and we can proceed with a request,
     * {@code false} otherwise. If it is not ready, and the service is enabled,
     * we will bind and the request should be automatically attempted later.
     */
    @GuardedBy("mLock")
    private boolean prepareServiceLocked(String action) {
        if (mRemoteService != null && mEnabled) {
            return true;
        }
        Slog.i(TAG, "Service not ready to " + action
                + (mEnabled ? ". Binding..." : ". Disabled"));
        if (mEnabled) {
            bindService();
        }
        return false;
    }
}
