/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
import static android.os.Process.SYSTEM_UID;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.dependencyinstaller.DependencyInstallerCallback;
import android.content.pm.dependencyinstaller.IDependencyInstallerCallback;
import android.content.pm.dependencyinstaller.IDependencyInstallerService;
import android.content.pm.parsing.PackageLite;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helper class to interact with SDK Dependency Installer service.
 */
public class InstallDependencyHelper {
    private static final String TAG = InstallDependencyHelper.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final String ACTION_INSTALL_DEPENDENCY =
            "android.intent.action.INSTALL_DEPENDENCY";
    // The maximum amount of time to wait before the system unbinds from the verifier.
    private static final long UNBIND_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final long REQUEST_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final SharedLibrariesImpl mSharedLibraries;
    private final Context mContext;
    private final Object mRemoteServiceLock = new Object();

    @GuardedBy("mRemoteServiceLock")
    private ServiceConnector<IDependencyInstallerService> mRemoteService = null;

    InstallDependencyHelper(Context context, SharedLibrariesImpl sharedLibraries) {
        mContext = context;
        mSharedLibraries = sharedLibraries;
    }

    void resolveLibraryDependenciesIfNeeded(PackageLite pkg, Computer snapshot, int userId,
            Handler handler, OutcomeReceiver<Void, PackageManagerException> origCallback) {
        CallOnceProxy callback = new CallOnceProxy(handler, origCallback);
        try {
            resolveLibraryDependenciesIfNeededInternal(pkg, snapshot, userId, handler, callback);
        } catch (PackageManagerException e) {
            callback.onError(e);
        } catch (Exception e) {
            onError(callback, e.getMessage());
        }
    }


    private void resolveLibraryDependenciesIfNeededInternal(PackageLite pkg, Computer snapshot,
            int userId, Handler handler, CallOnceProxy callback) throws PackageManagerException {
        final List<SharedLibraryInfo> missing =
                mSharedLibraries.collectMissingSharedLibraryInfos(pkg);

        if (missing.isEmpty()) {
            if (DEBUG) {
                Slog.i(TAG, "No missing dependency for " + pkg);
            }
            // No need for dependency resolution. Move to installation directly.
            callback.onResult(null);
            return;
        }

        if (!bindToDependencyInstallerIfNeeded(userId, handler, snapshot)) {
            onError(callback, "Dependency Installer Service not found");
            return;
        }

        IDependencyInstallerCallback serviceCallback = new IDependencyInstallerCallback.Stub() {
            @Override
            public void onAllDependenciesResolved(int[] sessionIds) throws RemoteException {
                // TODO(b/372862145): Implement waiting for sessions to finish installation
                callback.onResult(null);
            }

            @Override
            public void onFailureToResolveAllDependencies() throws RemoteException {
                onError(callback, "Failed to resolve all dependencies automatically");
            }
        };

        boolean scheduleSuccess;
        synchronized (mRemoteServiceLock) {
            scheduleSuccess = mRemoteService.run(service -> {
                service.onDependenciesRequired(missing,
                        new DependencyInstallerCallback(serviceCallback.asBinder()));
            });
        }
        if (!scheduleSuccess) {
            onError(callback, "Failed to schedule job on Dependency Installer Service");
        }
    }

    private void onError(CallOnceProxy callback, String msg) {
        PackageManagerException pe = new PackageManagerException(
                INSTALL_FAILED_MISSING_SHARED_LIBRARY, msg);
        callback.onError(pe);
    }

    private boolean bindToDependencyInstallerIfNeeded(int userId, Handler handler,
            Computer snapshot) {
        synchronized (mRemoteServiceLock) {
            if (mRemoteService != null) {
                if (DEBUG) {
                    Slog.i(TAG, "DependencyInstallerService already bound");
                }
                return true;
            }
        }

        Intent serviceIntent = new Intent(ACTION_INSTALL_DEPENDENCY);
        // TODO(b/372862145): Use RoleManager to find the package name
        List<ResolveInfo> resolvedIntents = snapshot.queryIntentServicesInternal(
                serviceIntent, /*resolvedType=*/ null, /*flags=*/0,
                userId, SYSTEM_UID, Process.INVALID_PID,
                /*includeInstantApps*/ false, /*resolveForStart*/ false);

        if (resolvedIntents.isEmpty()) {
            return false;
        }


        ResolveInfo resolveInfo = resolvedIntents.getFirst();
        ComponentName componentName = resolveInfo.getComponentInfo().getComponentName();
        serviceIntent.setComponent(componentName);

        ServiceConnector<IDependencyInstallerService> serviceConnector =
                new ServiceConnector.Impl<IDependencyInstallerService>(mContext, serviceIntent,
                    Context.BIND_AUTO_CREATE, userId,
                    IDependencyInstallerService.Stub::asInterface) {
                    @Override
                    protected Handler getJobHandler() {
                        return handler;
                    }

                    @Override
                    protected long getRequestTimeoutMs() {
                        return REQUEST_TIMEOUT_MILLIS;
                    }

                    @Override
                    protected long getAutoDisconnectTimeoutMs() {
                        return UNBIND_TIMEOUT_MILLIS;
                    }
                };


        synchronized (mRemoteServiceLock) {
            // Some other thread managed to connect to the service first
            if (mRemoteService != null) {
                return true;
            }
            mRemoteService = serviceConnector;
            mRemoteService.setServiceLifecycleCallbacks(
                new ServiceConnector.ServiceLifecycleCallbacks<>() {
                    @Override
                    public void onDisconnected(@NonNull IDependencyInstallerService service) {
                        Slog.w(TAG,
                                "DependencyInstallerService " + componentName + " is disconnected");
                        destroy();
                    }

                    @Override
                    public void onBinderDied() {
                        Slog.w(TAG, "DependencyInstallerService " + componentName + " has died");
                        destroy();
                    }

                    private void destroy() {
                        synchronized (mRemoteServiceLock) {
                            if (mRemoteService != null) {
                                mRemoteService.unbind();
                                mRemoteService = null;
                            }
                        }
                    }

                });
            AndroidFuture<IDependencyInstallerService> unusedFuture = mRemoteService.connect();
        }
        return true;
    }

    /**
     * Ensure we call one of the outcomes only once, on the right handler.
     *
     * Repeated calls will be no-op.
     */
    private static class CallOnceProxy implements OutcomeReceiver<Void, PackageManagerException> {
        private final Handler mHandler;
        private final OutcomeReceiver<Void, PackageManagerException> mCallback;
        @GuardedBy("this")
        private boolean mCalled = false;

        CallOnceProxy(Handler handler, OutcomeReceiver<Void, PackageManagerException> callback) {
            mHandler = handler;
            mCallback = callback;
        }

        @Override
        public void onResult(Void result) {
            synchronized (this) {
                if (!mCalled) {
                    mHandler.post(() -> {
                        mCallback.onResult(null);
                    });
                    mCalled = true;
                }
            }
        }

        @Override
        public void onError(@NonNull PackageManagerException error) {
            synchronized (this) {
                if (!mCalled) {
                    mHandler.post(() -> {
                        mCallback.onError(error);
                    });
                    mCalled = true;
                }
            }
        }
    }
}
