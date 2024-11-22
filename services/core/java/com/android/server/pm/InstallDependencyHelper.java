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

import static android.content.pm.PackageInstaller.ACTION_INSTALL_DEPENDENCY;
import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
import static android.os.Process.SYSTEM_UID;

import android.annotation.NonNull;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller.SessionInfo;
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
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.infra.ServiceConnector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helper class to interact with SDK Dependency Installer service.
 */
public class InstallDependencyHelper {
    private static final String TAG = InstallDependencyHelper.class.getSimpleName();
    private static final boolean DEBUG = true;
    private static final String ROLE_SYSTEM_DEPENDENCY_INSTALLER =
            "android.app.role.SYSTEM_DEPENDENCY_INSTALLER";
    // The maximum amount of time to wait before the system unbinds from the verifier.
    private static final long UNBIND_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(6);
    private static final long REQUEST_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final Context mContext;
    private final SharedLibrariesImpl mSharedLibraries;
    private final PackageInstallerService mPackageInstallerService;
    private final Object mRemoteServiceLock = new Object();
    @GuardedBy("mTrackers")
    private final List<DependencyInstallTracker> mTrackers = new ArrayList<>();

    @GuardedBy("mRemoteServiceLock")
    private ServiceConnector<IDependencyInstallerService> mRemoteService = null;

    InstallDependencyHelper(Context context, SharedLibrariesImpl sharedLibraries,
            PackageInstallerService packageInstallerService) {
        mContext = context;
        mSharedLibraries = sharedLibraries;
        mPackageInstallerService = packageInstallerService;
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

        IDependencyInstallerCallback serviceCallback =
                new DependencyInstallerCallbackCallOnce(handler, callback);
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

    void notifySessionComplete(int sessionId, boolean success) {
        if (DEBUG) {
            Slog.i(TAG, "Session complete for " + sessionId + " result: " + success);
        }
        synchronized (mTrackers) {
            List<DependencyInstallTracker> completedTrackers = new ArrayList<>();
            for (DependencyInstallTracker tracker: mTrackers) {
                if (!tracker.onSessionComplete(sessionId, success)) {
                    completedTrackers.add(tracker);
                }
            }
            mTrackers.removeAll(completedTrackers);
        }
    }

    private static void onError(CallOnceProxy callback, String msg) {
        PackageManagerException pe = new PackageManagerException(
                INSTALL_FAILED_MISSING_SHARED_LIBRARY, msg);
        callback.onError(pe);
        if (DEBUG) {
            Slog.i(TAG, "Orig session error: " + msg);
        }
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

        RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        if (roleManager == null) {
            Slog.w(TAG, "Cannot find RoleManager system service");
            return false;
        }
        List<String> holders = roleManager.getRoleHoldersAsUser(
                ROLE_SYSTEM_DEPENDENCY_INSTALLER, UserHandle.of(userId));
        if (holders.isEmpty()) {
            Slog.w(TAG, "No holders of ROLE_SYSTEM_DEPENDENCY_INSTALLER found");
            return false;
        }

        Intent serviceIntent = new Intent(ACTION_INSTALL_DEPENDENCY);
        serviceIntent.setPackage(holders.getFirst());
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

    /**
     * Ensure we call one of the outcomes only once, on the right handler.
     *
     * Repeated calls will be no-op.
     */
    private class DependencyInstallerCallbackCallOnce extends IDependencyInstallerCallback.Stub {

        private final Handler mHandler;
        private final CallOnceProxy mCallback;

        @GuardedBy("this")
        private boolean mCalled = false;

        DependencyInstallerCallbackCallOnce(Handler handler, CallOnceProxy callback) {
            mHandler = handler;
            mCallback = callback;
        }

        // TODO(b/372862145): Consider turning the binder call to two-way so that we can
        //  throw IllegalArgumentException
        @Override
        public void onAllDependenciesResolved(int[] sessionIds) throws RemoteException {
            synchronized (this) {
                if (mCalled) {
                    return;
                }
                mCalled = true;
            }

            ArraySet<Integer> set = new ArraySet<>();
            for (int i = 0; i < sessionIds.length; i++) {
                if (DEBUG) {
                    Slog.i(TAG, "onAllDependenciesResolved called with " + sessionIds[i]);
                }
                set.add(sessionIds[i]);
            }

            DependencyInstallTracker tracker = new DependencyInstallTracker(mCallback, set);
            synchronized (mTrackers) {
                mTrackers.add(tracker);
            }

            // In case any of the session ids have already been installed, check if they
            // are valid.
            mHandler.post(() -> {
                if (DEBUG) {
                    Slog.i(TAG, "onAllDependenciesResolved cleaning up invalid sessions");
                }

                for (int i = 0; i < sessionIds.length; i++) {
                    int sessionId = sessionIds[i];
                    SessionInfo sessionInfo = mPackageInstallerService.getSessionInfo(sessionId);

                    // Continue waiting if session exists and hasn't passed or failed yet.
                    if (sessionInfo != null && !sessionInfo.isSessionApplied
                            && !sessionInfo.isSessionFailed) {
                        continue;
                    }

                    if (DEBUG) {
                        Slog.i(TAG, "onAllDependenciesResolved cleaning up finished"
                                + " session: " + sessionId);
                    }

                    // If session info is null, we assume it to be success.
                    // TODO(b/372862145): Check historical sessions to be more precise.
                    boolean success = sessionInfo == null || sessionInfo.isSessionApplied;

                    notifySessionComplete(sessionId, /*success=*/success);
                }
            });
        }

        @Override
        public void onFailureToResolveAllDependencies() throws RemoteException {
            synchronized (this) {
                if (mCalled) {
                    return;
                }
                onError(mCallback, "Failed to resolve all dependencies automatically");
                mCalled = true;
            }
        }
    }

    /**
     * Tracks a list of session ids against a particular callback.
     *
     * If all the sessions completes successfully, it invokes the positive flow. If any of the
     * sessions fails, it invokes the failure flow immediately.
     */
    // TODO(b/372862145): Determine and add support for rebooting while dependency is being resolved
    private static class DependencyInstallTracker {
        private final CallOnceProxy mCallback;
        private final ArraySet<Integer> mPendingSessionIds;

        DependencyInstallTracker(CallOnceProxy callback, ArraySet<Integer> pendingSessionIds) {
            mCallback = callback;
            mPendingSessionIds = pendingSessionIds;
        }

        /**
         * Process a session complete event.
         *
         * Returns true if we still need to continue tracking.
         */
        public boolean onSessionComplete(int sessionId, boolean success) {
            synchronized (this) {
                if (!mPendingSessionIds.contains(sessionId)) {
                    // This had no impact on tracker, so continue tracking
                    return true;
                }

                if (!success) {
                    // If one of the dependency fails, the orig session would fail too.
                    onError(mCallback, "Failed to install all dependencies");
                    // TODO(b/372862145): Abandon the rest of the pending sessions.
                    return false; // No point in tracking anymore
                }

                mPendingSessionIds.remove(sessionId);
                if (mPendingSessionIds.isEmpty()) {
                    mCallback.onResult(null);
                    return false; // Nothing to track anymore
                }
                return true; // Keep on tracking
            }
        }

    }
}
