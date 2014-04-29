/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_ALL_USERS;
import static android.content.pm.PackageManager.INSTALL_FROM_ADB;
import static android.content.pm.PackageManager.INSTALL_REPLACE_EXISTING;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInstallerParams;
import android.os.Binder;
import android.os.FileUtils;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.IoThread;
import com.google.android.collect.Sets;

import java.io.File;

public class PackageInstallerService extends IPackageInstaller.Stub {
    private static final String TAG = "PackageInstaller";

    // TODO: destroy sessions with old timestamps
    // TODO: remove outstanding sessions when installer package goes away

    private final Context mContext;
    private final PackageManagerService mPm;
    private final AppOpsManager mAppOps;

    private final File mStagingDir;

    private final HandlerThread mInstallThread = new HandlerThread(TAG);
    private final Callback mCallback = new Callback();

    @GuardedBy("mSessions")
    private int mNextSessionId;
    @GuardedBy("mSessions")
    private final SparseArray<PackageInstallerSession> mSessions = new SparseArray<>();

    public PackageInstallerService(Context context, PackageManagerService pm, File stagingDir) {
        mContext = context;
        mPm = pm;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);

        mStagingDir = stagingDir;
        mStagingDir.mkdirs();

        synchronized (mSessions) {
            readSessionsLocked();

            // Clean up orphaned staging directories
            final ArraySet<String> dirs = Sets.newArraySet(mStagingDir.list());
            for (int i = 0; i < mSessions.size(); i++) {
                dirs.remove(Integer.toString(mSessions.keyAt(i)));
            }
            for (String dirName : dirs) {
                Slog.w(TAG, "Deleting orphan session " + dirName);
                final File dir = new File(mStagingDir, dirName);
                FileUtils.deleteContents(dir);
                dir.delete();
            }
        }
    }

    private void readSessionsLocked() {
        // TODO: implement persisting
        mSessions.clear();
        mNextSessionId = 1;
    }

    private void writeSessionsLocked() {
        // TODO: implement persisting
    }

    private void writeSessionsAsync() {
        IoThread.getHandler().post(new Runnable() {
            @Override
            public void run() {
                synchronized (mSessions) {
                    writeSessionsLocked();
                }
            }
        });
    }

    @Override
    public int createSession(int userId, String installerPackageName,
            PackageInstallerParams params) {
        final int callingUid = Binder.getCallingUid();
        mPm.enforceCrossUserPermission(callingUid, userId, false, TAG);
        mAppOps.checkPackage(callingUid, installerPackageName);

        if (mPm.isUserRestricted(UserHandle.getUserId(callingUid),
                UserManager.DISALLOW_INSTALL_APPS)) {
            throw new SecurityException("User restriction prevents installing");
        }

        if ((callingUid == Process.SHELL_UID) || (callingUid == 0)) {
            params.installFlags |= INSTALL_FROM_ADB;
        } else {
            params.installFlags &= ~INSTALL_FROM_ADB;
            params.installFlags &= ~INSTALL_ALL_USERS;
            params.installFlags |= INSTALL_REPLACE_EXISTING;
        }

        synchronized (mSessions) {
            final int sessionId = allocateSessionIdLocked();
            final long createdMillis = System.currentTimeMillis();
            final File sessionDir = new File(mStagingDir, Integer.toString(sessionId));
            sessionDir.mkdirs();

            final PackageInstallerSession session = new PackageInstallerSession(mCallback, mPm,
                    sessionId, userId, installerPackageName, callingUid, params, createdMillis,
                    sessionDir, mInstallThread.getLooper());
            mSessions.put(sessionId, session);

            writeSessionsAsync();
            return sessionId;
        }
    }

    @Override
    public IPackageInstallerSession openSession(int sessionId) {
        synchronized (mSessions) {
            final PackageInstallerSession session = mSessions.get(sessionId);
            if (session == null) {
                throw new IllegalStateException("Missing session " + sessionId);
            }
            if (Binder.getCallingUid() != session.installerUid) {
                throw new SecurityException("Caller has no access to session " + sessionId);
            }
            return session;
        }
    }

    private int allocateSessionIdLocked() {
        if (mSessions.get(mNextSessionId) != null) {
            throw new IllegalStateException("Next session already allocated");
        }
        return mNextSessionId++;
    }

    @Override
    public int[] getSessions(int userId, String installerPackageName) {
        final int callingUid = Binder.getCallingUid();
        mPm.enforceCrossUserPermission(callingUid, userId, false, TAG);
        mAppOps.checkPackage(callingUid, installerPackageName);

        int[] matching = new int[0];
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final int key = mSessions.keyAt(i);
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (session.userId == userId
                        && session.installerPackageName.equals(installerPackageName)) {
                    matching = ArrayUtils.appendInt(matching, key);
                }
            }
        }
        return matching;
    }

    @Override
    public void uninstall(int userId, String basePackageName, IPackageDeleteObserver observer) {
        mPm.deletePackageAsUser(basePackageName, observer, userId, 0);
    }

    @Override
    public void uninstallSplit(int userId, String basePackageName, String overlayName,
            IPackageDeleteObserver observer) {
        // TODO: flesh out once PM has split support
        throw new UnsupportedOperationException();
    }

    class Callback {
        public void onProgressChanged(PackageInstallerSession session) {
            // TODO: notify listeners
        }

        public void onSessionInvalid(PackageInstallerSession session) {
            writeSessionsAsync();
        }
    }
}
