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
 * limitations under the License
 */

package com.android.server.backup.restore;

import static android.app.backup.BackupManager.OperationType;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_SESSION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_GET_RESTORE_SETS;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_RESTORE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.util.Slog;

import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.params.RestoreGetSetsParams;
import com.android.server.backup.params.RestoreParams;
import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.utils.BackupEligibilityRules;

import java.util.function.BiFunction;

/**
 * Restore session.
 */
public class ActiveRestoreSession extends IRestoreSession.Stub {
    private static final String TAG = "RestoreSession";
    private static final String DEVICE_NAME_FOR_D2D_SET = "D2D";

    private final TransportManager mTransportManager;
    private final String mTransportName;
    private final UserBackupManagerService mBackupManagerService;
    private final int mUserId;
    private final BackupEligibilityRules mBackupEligibilityRules;
    @Nullable private final String mPackageName;
    public RestoreSet[] mRestoreSets = null;
    boolean mEnded = false;
    boolean mTimedOut = false;

    public ActiveRestoreSession(
            UserBackupManagerService backupManagerService,
            @Nullable String packageName,
            String transportName,
            BackupEligibilityRules backupEligibilityRules) {
        mBackupManagerService = backupManagerService;
        mPackageName = packageName;
        mTransportManager = backupManagerService.getTransportManager();
        mTransportName = transportName;
        mUserId = backupManagerService.getUserId();
        mBackupEligibilityRules = backupEligibilityRules;
    }

    public void markTimedOut() {
        mTimedOut = true;
    }

    // --- Binder interface ---
    public synchronized int getAvailableRestoreSets(IRestoreObserver observer,
            IBackupManagerMonitor monitor) {
        mBackupManagerService.getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP,
                "getAvailableRestoreSets");
        if (observer == null) {
            throw new IllegalArgumentException("Observer must not be null");
        }

        if (mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }

        if (mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            TransportConnection transportConnection =
                    mTransportManager.getTransportClient(
                                    mTransportName, "RestoreSession.getAvailableRestoreSets()");
            if (transportConnection == null) {
                Slog.w(TAG, "Null transport client getting restore sets");
                return -1;
            }

            // We know we're doing legit work now, so halt the timeout
            // until we're done.  It gets started again when the result
            // comes in.
            mBackupManagerService.getBackupHandler().removeMessages(MSG_RESTORE_SESSION_TIMEOUT);

            UserBackupManagerService.BackupWakeLock wakelock = mBackupManagerService.getWakelock();
            wakelock.acquire();

            // Prevent lambda from leaking 'this'
            TransportManager transportManager = mTransportManager;
            OnTaskFinishedListener listener = caller -> {
                    transportManager.disposeOfTransportClient(transportConnection, caller);
                    wakelock.release();
            };
            Message msg = mBackupManagerService.getBackupHandler().obtainMessage(
                    MSG_RUN_GET_RESTORE_SETS,
                    new RestoreGetSetsParams(transportConnection, this, observer, monitor,
                            listener));
            mBackupManagerService.getBackupHandler().sendMessage(msg);
            return 0;
        } catch (Exception e) {
            Slog.e(TAG, "Error in getAvailableRestoreSets", e);
            return -1;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    public synchronized int restoreAll(long token, IRestoreObserver observer,
            IBackupManagerMonitor monitor) {
        mBackupManagerService.getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP,
                "performRestore");

        if (DEBUG) {
            Slog.d(TAG, "restoreAll token=" + Long.toHexString(token)
                    + " observer=" + observer);
        }

        if (mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }

        if (mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        }

        if (mRestoreSets == null) {
            Slog.e(TAG, "Ignoring restoreAll() with no restore set");
            return -1;
        }

        if (mPackageName != null) {
            Slog.e(TAG, "Ignoring restoreAll() on single-package session");
            return -1;
        }

        if (!mTransportManager.isTransportRegistered(mTransportName)) {
            Slog.e(TAG, "Transport " + mTransportName + " not registered");
            return -1;
        }

        synchronized (mBackupManagerService.getQueueLock()) {
            for (int i = 0; i < mRestoreSets.length; i++) {
                if (token == mRestoreSets[i].token) {
                    final long oldId = Binder.clearCallingIdentity();
                    RestoreSet restoreSet = mRestoreSets[i];
                    try {
                        return sendRestoreToHandlerLocked(
                                (transportClient, listener) ->
                                        RestoreParams.createForRestoreAll(
                                                transportClient,
                                                observer,
                                                monitor,
                                                token,
                                                listener,
                                                getBackupEligibilityRules(restoreSet)),
                                "RestoreSession.restoreAll()");
                    } finally {
                        Binder.restoreCallingIdentity(oldId);
                    }
                }
            }
        }

        Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
        return -1;
    }

    // Restores of more than a single package are treated as 'system' restores
    public synchronized int restorePackages(long token, @Nullable IRestoreObserver observer,
            @NonNull String[] packages, @Nullable IBackupManagerMonitor monitor) {
        mBackupManagerService.getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP,
                "performRestore");

        if (DEBUG) {
            StringBuilder b = new StringBuilder(128);
            b.append("restorePackages token=");
            b.append(Long.toHexString(token));
            b.append(" observer=");
            if (observer == null) {
                b.append("null");
            } else {
                b.append(observer.toString());
            }
            b.append(" monitor=");
            if (monitor == null) {
                b.append("null");
            } else {
                b.append(monitor.toString());
            }
            b.append(" packages=");
            if (packages == null) {
                b.append("null");
            } else {
                b.append('{');
                boolean first = true;
                for (String s : packages) {
                    if (!first) {
                        b.append(", ");
                    } else {
                        first = false;
                    }
                    b.append(s);
                }
                b.append('}');
            }
            Slog.d(TAG, b.toString());
        }

        if (mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }

        if (mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        }

        if (mRestoreSets == null) {
            Slog.e(TAG, "Ignoring restoreAll() with no restore set");
            return -1;
        }

        if (mPackageName != null) {
            Slog.e(TAG, "Ignoring restoreAll() on single-package session");
            return -1;
        }

        if (!mTransportManager.isTransportRegistered(mTransportName)) {
            Slog.e(TAG, "Transport " + mTransportName + " not registered");
            return -1;
        }

        synchronized (mBackupManagerService.getQueueLock()) {
            for (int i = 0; i < mRestoreSets.length; i++) {
                if (token == mRestoreSets[i].token) {
                    final long oldId = Binder.clearCallingIdentity();
                    RestoreSet restoreSet = mRestoreSets[i];
                    try {
                        return sendRestoreToHandlerLocked(
                                (transportClient, listener) ->
                                        RestoreParams.createForRestorePackages(
                                                transportClient,
                                                observer,
                                                monitor,
                                                token,
                                                packages,
                                                /* isSystemRestore */ packages.length > 1,
                                                listener,
                                                getBackupEligibilityRules(restoreSet)),
                                "RestoreSession.restorePackages(" + packages.length + " packages)");
                    } finally {
                        Binder.restoreCallingIdentity(oldId);
                    }
                }
            }
        }

        Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
        return -1;
    }

    private BackupEligibilityRules getBackupEligibilityRules(RestoreSet restoreSet) {
        // TODO(b/182986784): Remove device name comparison once a designated field for operation
        //  type is added to RestoreSet object.
        int operationType = DEVICE_NAME_FOR_D2D_SET.equals(restoreSet.device)
                ? OperationType.MIGRATION : OperationType.BACKUP;
        return mBackupManagerService.getEligibilityRulesForOperation(operationType);
    }

    public synchronized int restorePackage(String packageName, IRestoreObserver observer,
            IBackupManagerMonitor monitor) {
        if (DEBUG) {
            Slog.v(TAG, "restorePackage pkg=" + packageName + " obs=" + observer
                    + "monitor=" + monitor);
        }

        if (mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }

        if (mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return -1;
        }

        if (mPackageName != null) {
            if (!mPackageName.equals(packageName)) {
                Slog.e(TAG, "Ignoring attempt to restore pkg=" + packageName
                        + " on session for package " + mPackageName);
                return -1;
            }
        }

        final PackageInfo app;
        try {
            app = mBackupManagerService.getPackageManager().getPackageInfoAsUser(
                    packageName, 0, mUserId);
        } catch (NameNotFoundException nnf) {
            Slog.w(TAG, "Asked to restore nonexistent pkg " + packageName);
            return -1;
        }

        // If the caller is not privileged and is not coming from the target
        // app's uid, throw a permission exception back to the caller.
        int perm = mBackupManagerService.getContext().checkPermission(
                android.Manifest.permission.BACKUP,
                Binder.getCallingPid(), Binder.getCallingUid());
        if ((perm == PackageManager.PERMISSION_DENIED) &&
                (app.applicationInfo.uid != Binder.getCallingUid())) {
            Slog.w(TAG, "restorePackage: bad packageName=" + packageName
                    + " or calling uid=" + Binder.getCallingUid());
            throw new SecurityException("No permission to restore other packages");
        }

        if (!mTransportManager.isTransportRegistered(mTransportName)) {
            Slog.e(TAG, "Transport " + mTransportName + " not registered");
            return -1;
        }

        // So far so good; we're allowed to try to restore this package.
        final long oldId = Binder.clearCallingIdentity();
        try {
            // Check whether there is data for it in the current dataset, falling back
            // to the ancestral dataset if not.
            long token = mBackupManagerService.getAvailableRestoreToken(packageName);
            if (DEBUG) {
                Slog.v(TAG, "restorePackage pkg=" + packageName
                        + " token=" + Long.toHexString(token));
            }

            // If we didn't come up with a place to look -- no ancestral dataset and
            // the app has never been backed up from this device -- there's nothing
            // to do but return failure.
            if (token == 0) {
                if (DEBUG) {
                    Slog.w(TAG, "No data available for this package; not restoring");
                }
                return -1;
            }

            return sendRestoreToHandlerLocked(
                    (transportClient, listener) ->
                            RestoreParams.createForSinglePackage(
                                    transportClient,
                                    observer,
                                    monitor,
                                    token,
                                    app,
                                    listener,
                                    mBackupEligibilityRules),
                    "RestoreSession.restorePackage(" + packageName + ")");
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    public void setRestoreSets(RestoreSet[] restoreSets) {
        mRestoreSets = restoreSets;
    }

    /**
     * Returns 0 if operation sent or -1 otherwise.
     */
    private int sendRestoreToHandlerLocked(
            BiFunction<TransportConnection, OnTaskFinishedListener,
                    RestoreParams> restoreParamsBuilder, String callerLogString) {
        TransportConnection transportConnection =
                mTransportManager.getTransportClient(mTransportName, callerLogString);
        if (transportConnection == null) {
            Slog.e(TAG, "Transport " + mTransportName + " got unregistered");
            return -1;
        }

        // Stop the session timeout until we finalize the restore
        Handler backupHandler = mBackupManagerService.getBackupHandler();
        backupHandler.removeMessages(MSG_RESTORE_SESSION_TIMEOUT);

        UserBackupManagerService.BackupWakeLock wakelock = mBackupManagerService.getWakelock();
        wakelock.acquire();
        if (MORE_DEBUG) {
            Slog.d(TAG, callerLogString);
        }

        // Prevent lambda from leaking 'this'
        TransportManager transportManager = mTransportManager;
        OnTaskFinishedListener listener = caller -> {
                transportManager.disposeOfTransportClient(transportConnection, caller);
                wakelock.release();
        };
        Message msg = backupHandler.obtainMessage(MSG_RUN_RESTORE);
        msg.obj = restoreParamsBuilder.apply(transportConnection, listener);
        backupHandler.sendMessage(msg);
        return 0;
    }

    // Posted to the handler to tear down a restore session in a cleanly synchronized way
    public class EndRestoreRunnable implements Runnable {

        UserBackupManagerService mBackupManager;
        ActiveRestoreSession mSession;

        public EndRestoreRunnable(UserBackupManagerService manager, ActiveRestoreSession session) {
            mBackupManager = manager;
            mSession = session;
        }

        public void run() {
            // clean up the session's bookkeeping
            synchronized (mSession) {
                mSession.mEnded = true;
            }

            // clean up the BackupManagerImpl side of the bookkeeping
            // and cancel any pending timeout message
            mBackupManager.clearRestoreSession(mSession);
        }
    }

    public synchronized void endRestoreSession() {
        if (DEBUG) {
            Slog.d(TAG, "endRestoreSession");
        }

        if (mTimedOut) {
            Slog.i(TAG, "Session already timed out");
            return;
        }

        if (mEnded) {
            throw new IllegalStateException("Restore session already ended");
        }

        mBackupManagerService.getBackupHandler().post(
                new EndRestoreRunnable(mBackupManagerService, this));
    }
}
