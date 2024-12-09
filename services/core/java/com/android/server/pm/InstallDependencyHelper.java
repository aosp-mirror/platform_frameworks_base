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
import android.os.Binder;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
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
    @GuardedBy("mTrackers")
    private final List<DependencyInstallTracker> mTrackers = new ArrayList<>();
    @GuardedBy("mRemoteServices")
    private final ArrayMap<Integer, ServiceConnector<IDependencyInstallerService>> mRemoteServices =
            new ArrayMap<>();

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
                Slog.d(TAG, "No missing dependency for " + pkg.getPackageName());
            }
            // No need for dependency resolution. Move to installation directly.
            callback.onResult(null);
            return;
        }

        if (DEBUG) {
            Slog.d(TAG, "Missing dependencies found for pkg: " + pkg.getPackageName()
                    + " user: " + userId);
        }

        if (!bindToDependencyInstallerIfNeeded(userId, handler, snapshot)) {
            onError(callback, "Dependency Installer Service not found");
            return;
        }

        IDependencyInstallerCallback serviceCallback =
                new DependencyInstallerCallbackCallOnce(handler, callback, userId);
        boolean scheduleSuccess;
        synchronized (mRemoteServices) {
            scheduleSuccess = mRemoteServices.get(userId).run(service -> {
                service.onDependenciesRequired(missing,
                        new DependencyInstallerCallback(serviceCallback.asBinder()));
            });
        }
        if (!scheduleSuccess) {
            onError(callback, "Failed to schedule job on Dependency Installer Service");
        }
    }

    void notifySessionComplete(int sessionId) {
        if (DEBUG) {
            Slog.d(TAG, "Session complete for " + sessionId);
        }
        synchronized (mTrackers) {
            List<DependencyInstallTracker> completedTrackers = new ArrayList<>();
            for (DependencyInstallTracker tracker: mTrackers) {
                if (!tracker.onSessionComplete(sessionId)) {
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
        synchronized (mRemoteServices) {
            if (mRemoteServices.containsKey(userId)) {
                if (DEBUG) {
                    Slog.i(TAG, "DependencyInstallerService for user " + userId + " already bound");
                }
                return true;
            }
        }

        Slog.i(TAG, "Attempting to bind to Dependency Installer Service for user " + userId);

        RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        if (roleManager == null) {
            Slog.w(TAG, "Cannot find RoleManager system service");
            return false;
        }
        List<String> holders = roleManager.getRoleHoldersAsUser(
                ROLE_SYSTEM_DEPENDENCY_INSTALLER, UserHandle.of(userId));
        if (holders.isEmpty()) {
            Slog.w(TAG, "No holders of ROLE_SYSTEM_DEPENDENCY_INSTALLER found for user " + userId);
            return false;
        }

        Intent serviceIntent = new Intent(ACTION_INSTALL_DEPENDENCY);
        serviceIntent.setPackage(holders.getFirst());
        List<ResolveInfo> resolvedIntents = snapshot.queryIntentServicesInternal(
                serviceIntent, /*resolvedType=*/ null, /*flags=*/0,
                userId, SYSTEM_UID, Process.INVALID_PID,
                /*includeInstantApps*/ false, /*resolveForStart*/ false);

        if (resolvedIntents.isEmpty()) {
            Slog.w(TAG, "No package holding ROLE_SYSTEM_DEPENDENCY_INSTALLER found for user "
                    + userId);
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


        synchronized (mRemoteServices) {
            // Some other thread managed to connect to the service first
            if (mRemoteServices.containsKey(userId)) {
                return true;
            }
            mRemoteServices.put(userId, serviceConnector);
            // Block the lock until we connect to the service
            serviceConnector.setServiceLifecycleCallbacks(
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
                        synchronized (mRemoteServices) {
                            if (mRemoteServices.containsKey(userId)) {
                                mRemoteServices.get(userId).unbind();
                                mRemoteServices.remove(userId);
                            }
                        }
                    }

                });
            AndroidFuture<IDependencyInstallerService> unusedFuture = serviceConnector.connect();
        }
        Slog.i(TAG, "Successfully bound to Dependency Installer Service for user " + userId);
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
        private final int mUserId;

        @GuardedBy("this")
        private boolean mDependencyInstallerCallbackInvoked = false;

        DependencyInstallerCallbackCallOnce(Handler handler, CallOnceProxy callback, int userId) {
            mHandler = handler;
            mCallback = callback;
            mUserId = userId;
        }

        @Override
        public void onAllDependenciesResolved(int[] sessionIds) throws RemoteException {
            synchronized (this) {
                if (mDependencyInstallerCallbackInvoked) {
                    throw new IllegalStateException(
                            "Callback is being or has been already processed");
                }
                mDependencyInstallerCallbackInvoked = true;
            }


            if (DEBUG) {
                Slog.d(TAG, "onAllDependenciesResolved started");
            }

            try {
                // Before creating any tracker, validate the arguments
                ArraySet<Integer> validSessionIds = validateSessionIds(sessionIds);

                if (validSessionIds.isEmpty()) {
                    mCallback.onResult(null);
                    return;
                }

                // Create a tracker now if there are any pending sessions remaining.
                DependencyInstallTracker tracker = new DependencyInstallTracker(
                        mCallback, validSessionIds);
                synchronized (mTrackers) {
                    mTrackers.add(tracker);
                }

                // By the time the tracker was created, some of the sessions in validSessionIds
                // could have finished. Avoid waiting for them indefinitely.
                for (int sessionId : validSessionIds) {
                    SessionInfo sessionInfo = mPackageInstallerService.getSessionInfo(sessionId);

                    // Don't wait for sessions that finished already
                    if (sessionInfo == null) {
                        Binder.withCleanCallingIdentity(() -> {
                            notifySessionComplete(sessionId);
                        });
                    }
                }
            } catch (Exception e) {
                // Allow calling the callback again
                synchronized (this) {
                    mDependencyInstallerCallbackInvoked = false;
                }
                throw e;
            }
        }

        @Override
        public void onFailureToResolveAllDependencies() throws RemoteException {
            synchronized (this) {
                if (mDependencyInstallerCallbackInvoked) {
                    throw new IllegalStateException(
                            "Callback is being or has been already processed");
                }
                mDependencyInstallerCallbackInvoked = true;
            }

            Binder.withCleanCallingIdentity(() -> {
                onError(mCallback, "Failed to resolve all dependencies automatically");
            });
        }

        private ArraySet<Integer> validateSessionIds(int[] sessionIds) {
            // Before creating any tracker, validate the arguments
            ArraySet<Integer> validSessionIds = new ArraySet<>();

            List<SessionInfo> historicalSessions = null;
            for (int i = 0; i < sessionIds.length; i++) {
                int sessionId = sessionIds[i];
                SessionInfo sessionInfo = mPackageInstallerService.getSessionInfo(sessionId);

                // Continue waiting if session exists and hasn't passed or failed yet.
                if (sessionInfo != null) {
                    if (sessionInfo.isSessionFailed) {
                        throw new IllegalArgumentException("Session already finished: "
                                + sessionId);
                    }

                    // Wait for session to finish install if it's not already successful.
                    if (!sessionInfo.isSessionApplied) {
                        if (DEBUG) {
                            Slog.d(TAG, "onAllDependenciesResolved pending session: " + sessionId);
                        }
                        validSessionIds.add(sessionId);
                    }

                    // An applied session found. No need to check historical session anymore.
                    continue;
                }

                if (DEBUG) {
                    Slog.d(TAG, "onAllDependenciesResolved cleaning up finished"
                            + " session: " + sessionId);
                }

                if (historicalSessions == null) {
                    historicalSessions = mPackageInstallerService.getHistoricalSessions(
                            mUserId).getList();
                }

                sessionInfo = historicalSessions.stream().filter(
                        s -> s.sessionId == sessionId).findFirst().orElse(null);

                if (sessionInfo == null) {
                    throw new IllegalArgumentException("Failed to find session: " + sessionId);
                }

                // Historical session must have been successful, otherwise throw IAE.
                if (!sessionInfo.isSessionApplied) {
                    throw new IllegalArgumentException("Session already finished: " + sessionId);
                }
            }

            return validSessionIds;
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
        @GuardedBy("this")
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
        public boolean onSessionComplete(int sessionId) {
            synchronized (this) {
                if (!mPendingSessionIds.contains(sessionId)) {
                    // This had no impact on tracker, so continue tracking
                    return true;
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
