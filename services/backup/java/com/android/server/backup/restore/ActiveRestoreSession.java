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

import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.MORE_DEBUG;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_SESSION_TIMEOUT;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_GET_RESTORE_SETS;
import static com.android.server.backup.internal.BackupHandler.MSG_RUN_RESTORE;

import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.RestoreSet;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Message;
import android.util.Slog;

import com.android.internal.backup.IBackupTransport;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.params.RestoreGetSetsParams;
import com.android.server.backup.params.RestoreParams;

/**
 * Restore session.
 */
public class ActiveRestoreSession extends IRestoreSession.Stub {

    private static final String TAG = "RestoreSession";

    private RefactoredBackupManagerService backupManagerService;
    private String mPackageName;
    private IBackupTransport mRestoreTransport = null;
    public RestoreSet[] mRestoreSets = null;
    boolean mEnded = false;
    boolean mTimedOut = false;

    public ActiveRestoreSession(RefactoredBackupManagerService backupManagerService,
            String packageName, String transport) {
        this.backupManagerService = backupManagerService;
        mPackageName = packageName;
        mRestoreTransport = backupManagerService.getTransportManager().getTransportBinder(
                transport);
    }

    public void markTimedOut() {
        mTimedOut = true;
    }

    // --- Binder interface ---
    public synchronized int getAvailableRestoreSets(IRestoreObserver observer,
            IBackupManagerMonitor monitor) {
        backupManagerService.getContext().enforceCallingOrSelfPermission(
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

        long oldId = Binder.clearCallingIdentity();
        try {
            if (mRestoreTransport == null) {
                Slog.w(TAG, "Null transport getting restore sets");
                return -1;
            }

            // We know we're doing legit work now, so halt the timeout
            // until we're done.  It gets started again when the result
            // comes in.
            backupManagerService.getBackupHandler().removeMessages(MSG_RESTORE_SESSION_TIMEOUT);

            // spin off the transport request to our service thread
            backupManagerService.getWakelock().acquire();
            Message msg = backupManagerService.getBackupHandler().obtainMessage(
                    MSG_RUN_GET_RESTORE_SETS,
                    new RestoreGetSetsParams(mRestoreTransport, this, observer,
                            monitor));
            backupManagerService.getBackupHandler().sendMessage(msg);
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
        backupManagerService.getContext().enforceCallingOrSelfPermission(
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

        if (mRestoreTransport == null || mRestoreSets == null) {
            Slog.e(TAG, "Ignoring restoreAll() with no restore set");
            return -1;
        }

        if (mPackageName != null) {
            Slog.e(TAG, "Ignoring restoreAll() on single-package session");
            return -1;
        }

        String dirName;
        try {
            dirName = mRestoreTransport.transportDirName();
        } catch (Exception e) {
            // Transport went AWOL; fail.
            Slog.e(TAG, "Unable to get transport dir for restore: " + e.getMessage());
            return -1;
        }

        synchronized (backupManagerService.getQueueLock()) {
            for (int i = 0; i < mRestoreSets.length; i++) {
                if (token == mRestoreSets[i].token) {
                    // Real work, so stop the session timeout until we finalize the restore
                    backupManagerService.getBackupHandler().removeMessages(
                            MSG_RESTORE_SESSION_TIMEOUT);

                    long oldId = Binder.clearCallingIdentity();
                    backupManagerService.getWakelock().acquire();
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "restoreAll() kicking off");
                    }
                    Message msg = backupManagerService.getBackupHandler().obtainMessage(
                            MSG_RUN_RESTORE);
                    msg.obj = new RestoreParams(mRestoreTransport, dirName,
                            observer, monitor, token);
                    backupManagerService.getBackupHandler().sendMessage(msg);
                    Binder.restoreCallingIdentity(oldId);
                    return 0;
                }
            }
        }

        Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
        return -1;
    }

    // Restores of more than a single package are treated as 'system' restores
    public synchronized int restoreSome(long token, IRestoreObserver observer,
            IBackupManagerMonitor monitor, String[] packages) {
        backupManagerService.getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.BACKUP,
                "performRestore");

        if (DEBUG) {
            StringBuilder b = new StringBuilder(128);
            b.append("restoreSome token=");
            b.append(Long.toHexString(token));
            b.append(" observer=");
            b.append(observer.toString());
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

        if (mRestoreTransport == null || mRestoreSets == null) {
            Slog.e(TAG, "Ignoring restoreAll() with no restore set");
            return -1;
        }

        if (mPackageName != null) {
            Slog.e(TAG, "Ignoring restoreAll() on single-package session");
            return -1;
        }

        String dirName;
        try {
            dirName = mRestoreTransport.transportDirName();
        } catch (Exception e) {
            // Transport went AWOL; fail.
            Slog.e(TAG, "Unable to get transport name for restoreSome: " + e.getMessage());
            return -1;
        }

        synchronized (backupManagerService.getQueueLock()) {
            for (int i = 0; i < mRestoreSets.length; i++) {
                if (token == mRestoreSets[i].token) {
                    // Stop the session timeout until we finalize the restore
                    backupManagerService.getBackupHandler().removeMessages(
                            MSG_RESTORE_SESSION_TIMEOUT);

                    long oldId = Binder.clearCallingIdentity();
                    backupManagerService.getWakelock().acquire();
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "restoreSome() of " + packages.length + " packages");
                    }
                    Message msg = backupManagerService.getBackupHandler().obtainMessage(
                            MSG_RUN_RESTORE);
                    msg.obj = new RestoreParams(mRestoreTransport, dirName, observer, monitor,
                            token, packages, packages.length > 1);
                    backupManagerService.getBackupHandler().sendMessage(msg);
                    Binder.restoreCallingIdentity(oldId);
                    return 0;
                }
            }
        }

        Slog.w(TAG, "Restore token " + Long.toHexString(token) + " not found");
        return -1;
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

        PackageInfo app = null;
        try {
            app = backupManagerService.getPackageManager().getPackageInfo(packageName, 0);
        } catch (NameNotFoundException nnf) {
            Slog.w(TAG, "Asked to restore nonexistent pkg " + packageName);
            return -1;
        }

        // If the caller is not privileged and is not coming from the target
        // app's uid, throw a permission exception back to the caller.
        int perm = backupManagerService.getContext().checkPermission(
                android.Manifest.permission.BACKUP,
                Binder.getCallingPid(), Binder.getCallingUid());
        if ((perm == PackageManager.PERMISSION_DENIED) &&
                (app.applicationInfo.uid != Binder.getCallingUid())) {
            Slog.w(TAG, "restorePackage: bad packageName=" + packageName
                    + " or calling uid=" + Binder.getCallingUid());
            throw new SecurityException("No permission to restore other packages");
        }

        // So far so good; we're allowed to try to restore this package.
        long oldId = Binder.clearCallingIdentity();
        try {
            // Check whether there is data for it in the current dataset, falling back
            // to the ancestral dataset if not.
            long token = backupManagerService.getAvailableRestoreToken(packageName);
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

            String dirName;
            try {
                dirName = mRestoreTransport.transportDirName();
            } catch (Exception e) {
                // Transport went AWOL; fail.
                Slog.e(TAG, "Unable to get transport dir for restorePackage: " + e.getMessage());
                return -1;
            }

            // Stop the session timeout until we finalize the restore
            backupManagerService.getBackupHandler().removeMessages(MSG_RESTORE_SESSION_TIMEOUT);

            // Ready to go:  enqueue the restore request and claim success
            backupManagerService.getWakelock().acquire();
            if (MORE_DEBUG) {
                Slog.d(TAG, "restorePackage() : " + packageName);
            }
            Message msg = backupManagerService.getBackupHandler().obtainMessage(MSG_RUN_RESTORE);
            msg.obj = new RestoreParams(mRestoreTransport, dirName, observer, monitor,
                    token, app);
            backupManagerService.getBackupHandler().sendMessage(msg);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
        return 0;
    }

    // Posted to the handler to tear down a restore session in a cleanly synchronized way
    public class EndRestoreRunnable implements Runnable {

        RefactoredBackupManagerService mBackupManager;
        ActiveRestoreSession mSession;

        public EndRestoreRunnable(RefactoredBackupManagerService manager,
                ActiveRestoreSession session) {
            mBackupManager = manager;
            mSession = session;
        }

        public void run() {
            // clean up the session's bookkeeping
            synchronized (mSession) {
                mSession.mRestoreTransport = null;
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

        backupManagerService.getBackupHandler().post(
                new EndRestoreRunnable(backupManagerService, this));
    }
}
