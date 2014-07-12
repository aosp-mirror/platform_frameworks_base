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
import android.content.pm.IPackageInstallerObserver;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.InstallSessionInfo;
import android.content.pm.InstallSessionParams;
import android.os.Binder;
import android.os.FileUtils;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SELinux;
import android.os.UserHandle;
import android.os.UserManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.IoThread;
import com.google.android.collect.Sets;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class PackageInstallerService extends IPackageInstaller.Stub {
    private static final String TAG = "PackageInstaller";

    // TODO: destroy sessions with old timestamps
    // TODO: remove outstanding sessions when installer package goes away

    private final Context mContext;
    private final PackageManagerService mPm;
    private final AppOpsManager mAppOps;

    private final File mStagingDir;
    private final HandlerThread mInstallThread;

    private final Callback mCallback = new Callback();

    @GuardedBy("mSessions")
    private int mNextSessionId;
    @GuardedBy("mSessions")
    private final SparseArray<PackageInstallerSession> mSessions = new SparseArray<>();

    private static final FilenameFilter sStageFilter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.startsWith("vmdl") && name.endsWith(".tmp");
        }
    };

    public PackageInstallerService(Context context, PackageManagerService pm, File stagingDir) {
        mContext = context;
        mPm = pm;
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);

        mStagingDir = stagingDir;

        mInstallThread = new HandlerThread(TAG);
        mInstallThread.start();

        synchronized (mSessions) {
            readSessionsLocked();

            // Clean up orphaned staging directories
            final ArraySet<File> stages = Sets.newArraySet(mStagingDir.listFiles(sStageFilter));
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                stages.remove(session.sessionStageDir);
            }
            for (File stage : stages) {
                Slog.w(TAG, "Deleting orphan stage " + stage);
                if (stage.isDirectory()) {
                    FileUtils.deleteContents(stage);
                }
                stage.delete();
            }
        }
    }

    @Deprecated
    public File allocateSessionDir() throws IOException {
        synchronized (mSessions) {
            try {
                final int sessionId = allocateSessionIdLocked();
                return prepareSessionStageDir(sessionId);
            } catch (IllegalStateException e) {
                throw new IOException(e);
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
    public int createSession(String installerPackageName, InstallSessionParams params,
            int userId) {
        final int callingUid = Binder.getCallingUid();
        mPm.enforceCrossUserPermission(callingUid, userId, false, TAG);

        if (mPm.isUserRestricted(UserHandle.getUserId(callingUid),
                UserManager.DISALLOW_INSTALL_APPS)) {
            throw new SecurityException("User restriction prevents installing");
        }

        if ((callingUid == Process.SHELL_UID) || (callingUid == 0)) {
            params.installFlags |= INSTALL_FROM_ADB;
        } else {
            mAppOps.checkPackage(callingUid, installerPackageName);

            params.installFlags &= ~INSTALL_FROM_ADB;
            params.installFlags &= ~INSTALL_ALL_USERS;
            params.installFlags |= INSTALL_REPLACE_EXISTING;
        }

        synchronized (mSessions) {
            final long createdMillis = System.currentTimeMillis();
            final int sessionId = allocateSessionIdLocked();
            final File sessionStageDir = prepareSessionStageDir(sessionId);

            final PackageInstallerSession session = new PackageInstallerSession(mCallback, mPm,
                    sessionId, userId, installerPackageName, callingUid, params, createdMillis,
                    sessionStageDir, mInstallThread.getLooper());
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

    private File prepareSessionStageDir(int sessionId) {
        final File file = new File(mStagingDir, "vmdl" + sessionId + ".tmp");

        if (file.exists()) {
            throw new IllegalStateException();
        }

        try {
            Os.mkdir(file.getAbsolutePath(), 0755);
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            // This purposefully throws if directory already exists
            throw new IllegalStateException("Failed to prepare session dir", e);
        }

        if (!SELinux.restorecon(file)) {
            throw new IllegalStateException("Failed to prepare session dir");
        }

        return file;
    }

    @Override
    public List<InstallSessionInfo> getSessions(int userId) {
        mPm.enforceCrossUserPermission(Binder.getCallingUid(), userId, false, TAG);

        final List<InstallSessionInfo> result = new ArrayList<>();
        synchronized (mSessions) {
            for (int i = 0; i < mSessions.size(); i++) {
                final PackageInstallerSession session = mSessions.valueAt(i);
                if (session.userId == userId) {
                    result.add(session.generateInfo());
                }
            }
        }
        return result;
    }

    @Override
    public void uninstall(String packageName, int flags, IPackageDeleteObserver observer,
            int userId) {
        mPm.deletePackageAsUser(packageName, observer, userId, flags);
    }

    @Override
    public void uninstallSplit(String basePackageName, String overlayName, int flags,
            IPackageDeleteObserver observer, int userId) {
        // TODO: flesh out once PM has split support
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerObserver(IPackageInstallerObserver observer, int userId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unregisterObserver(IPackageInstallerObserver observer, int userId) {
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
