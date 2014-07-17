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

import static android.content.pm.PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_WRONLY;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.InstallSessionInfo;
import android.content.pm.InstallSessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.MathUtils;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import libcore.io.Libcore;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = true;

    // TODO: enforce INSTALL_ALLOW_TEST
    // TODO: enforce INSTALL_ALLOW_DOWNGRADE
    // TODO: handle INSTALL_EXTERNAL, INSTALL_INTERNAL

    private final PackageInstallerService.Callback mCallback;
    private final PackageManagerService mPm;
    private final Handler mHandler;

    public final int sessionId;
    public final int userId;
    public final String installerPackageName;
    /** UID not persisted */
    public final int installerUid;
    public final InstallSessionParams params;
    public final long createdMillis;
    public final File sessionStageDir;

    private static final int MSG_INSTALL = 0;

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            synchronized (mLock) {
                if (msg.obj != null) {
                    mRemoteObserver = (IPackageInstallObserver2) msg.obj;
                }

                try {
                    installLocked();
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "Install failed: " + e);
                    destroyInternal();
                    try {
                        mRemoteObserver.packageInstalled(mPackageName, null, e.error,
                                e.getMessage());
                    } catch (RemoteException ignored) {
                    }
                    mCallback.onSessionFinished(PackageInstallerSession.this, false);
                }

                return true;
            }
        }
    };

    private final Object mLock = new Object();

    private int mClientProgress;
    private int mProgress = 0;

    private String mPackageName;
    private int mVersionCode;
    private Signature[] mSignatures;

    private boolean mMutationsAllowed;
    private boolean mPermissionsConfirmed;
    private boolean mInvalid;

    private ArrayList<FileBridge> mBridges = new ArrayList<>();

    private IPackageInstallObserver2 mRemoteObserver;

    public PackageInstallerSession(PackageInstallerService.Callback callback,
            PackageManagerService pm, int sessionId, int userId, String installerPackageName,
            int installerUid, InstallSessionParams params, long createdMillis, File sessionStageDir,
            Looper looper) {
        mCallback = callback;
        mPm = pm;
        mHandler = new Handler(looper, mHandlerCallback);

        this.sessionId = sessionId;
        this.userId = userId;
        this.installerPackageName = installerPackageName;
        this.installerUid = installerUid;
        this.params = params;
        this.createdMillis = createdMillis;
        this.sessionStageDir = sessionStageDir;

        // Check against any explicitly provided signatures
        mSignatures = params.signatures;

        // TODO: splice in flag when restoring persisted session
        mMutationsAllowed = true;

        if (pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, installerPackageName)
                == PackageManager.PERMISSION_GRANTED) {
            mPermissionsConfirmed = true;
        }
        if (installerUid == Process.SHELL_UID || installerUid == 0) {
            mPermissionsConfirmed = true;
        }
    }

    public InstallSessionInfo generateInfo() {
        final InstallSessionInfo info = new InstallSessionInfo();

        info.sessionId = sessionId;
        info.installerPackageName = installerPackageName;
        info.progress = mProgress;

        info.mode = params.mode;
        info.packageName = params.packageName;
        info.icon = params.icon;
        info.title = params.title;

        return info;
    }

    @Override
    public void setClientProgress(int progress) {
        mClientProgress = progress;
        mProgress = MathUtils.constrain(
                (int) (((float) mClientProgress) / ((float) params.progressMax)) * 80, 0, 80);
        mCallback.onSessionProgress(this, mProgress);
    }

    @Override
    public void addClientProgress(int progress) {
        setClientProgress(mClientProgress + progress);
    }

    @Override
    public ParcelFileDescriptor openWrite(String name, long offsetBytes, long lengthBytes) {
        try {
            return openWriteInternal(name, offsetBytes, lengthBytes);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private ParcelFileDescriptor openWriteInternal(String name, long offsetBytes, long lengthBytes)
            throws IOException {
        // TODO: relay over to DCS when installing to ASEC

        // Quick sanity check of state, and allocate a pipe for ourselves. We
        // then do heavy disk allocation outside the lock, but this open pipe
        // will block any attempted install transitions.
        final FileBridge bridge;
        synchronized (mLock) {
            if (!mMutationsAllowed) {
                throw new IllegalStateException("Mutations not allowed");
            }

            bridge = new FileBridge();
            mBridges.add(bridge);
        }

        try {
            // Use installer provided name for now; we always rename later
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            final File target = new File(sessionStageDir, name);

            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(),
                    O_CREAT | O_WRONLY, 0644);
            Os.chmod(target.getAbsolutePath(), 0644);

            // If caller specified a total length, allocate it for them. Free up
            // cache space to grow, if needed.
            if (lengthBytes > 0) {
                final StructStat stat = Libcore.os.fstat(targetFd);
                final long deltaBytes = lengthBytes - stat.st_size;
                if (deltaBytes > 0) {
                    mPm.freeStorage(deltaBytes);
                }
                Libcore.os.posix_fallocate(targetFd, 0, lengthBytes);
            }

            if (offsetBytes > 0) {
                Libcore.os.lseek(targetFd, offsetBytes, OsConstants.SEEK_SET);
            }

            bridge.setTargetFile(targetFd);
            bridge.start();
            return new ParcelFileDescriptor(bridge.getClientSocket());

        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    @Override
    public void install(IPackageInstallObserver2 observer) {
        Preconditions.checkNotNull(observer);
        mHandler.obtainMessage(MSG_INSTALL, observer).sendToTarget();
    }

    private void installLocked() throws PackageManagerException {
        if (mInvalid) {
            throw new PackageManagerException(INSTALL_FAILED_ALREADY_EXISTS, "Invalid session");
        }

        // Verify that all writers are hands-off
        if (mMutationsAllowed) {
            for (FileBridge bridge : mBridges) {
                if (!bridge.isClosed()) {
                    throw new PackageManagerException(INSTALL_FAILED_PACKAGE_CHANGED,
                            "Files still open");
                }
            }
            mMutationsAllowed = false;

            // TODO: persist disabled mutations before going forward, since
            // beyond this point we may have hardlinks to the valid install
        }

        // Verify that stage looks sane with respect to existing application.
        // This currently only ensures packageName, versionCode, and certificate
        // consistency.
        validateInstallLocked();

        Preconditions.checkNotNull(mPackageName);
        Preconditions.checkNotNull(mSignatures);

        if (!mPermissionsConfirmed) {
            // TODO: async confirm permissions with user
            // when they confirm, we'll kick off another install() pass
            throw new SecurityException("Caller must hold INSTALL permission");
        }

        // Inherit any packages and native libraries from existing install that
        // haven't been overridden.
        if (params.mode == InstallSessionParams.MODE_INHERIT_EXISTING) {
            spliceExistingFilesIntoStage();
        }

        // TODO: surface more granular state from dexopt
        mCallback.onSessionProgress(this, 90);

        // TODO: for ASEC based applications, grow and stream in packages

        // We've reached point of no return; call into PMS to install the stage.
        // Regardless of success or failure we always destroy session.
        final IPackageInstallObserver2 remoteObserver = mRemoteObserver;
        final IPackageInstallObserver2 localObserver = new IPackageInstallObserver2.Stub() {
            @Override
            public void packageInstalled(String basePackageName, Bundle extras, int returnCode,
                    String msg) {
                destroyInternal();
                try {
                    remoteObserver.packageInstalled(basePackageName, extras, returnCode, msg);
                } catch (RemoteException ignored) {
                }
                final boolean success = (returnCode == PackageManager.INSTALL_SUCCEEDED);
                mCallback.onSessionFinished(PackageInstallerSession.this, success);
            }
        };

        mPm.installStage(mPackageName, this.sessionStageDir, localObserver, params,
                installerPackageName, installerUid, new UserHandle(userId));
    }

    /**
     * Validate install by confirming that all application packages are have
     * consistent package name, version code, and signing certificates.
     * <p>
     * Renames package files in stage to match split names defined inside.
     */
    private void validateInstallLocked() throws PackageManagerException {
        mPackageName = null;
        mVersionCode = -1;

        final File[] files = sessionStageDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, "No packages staged");
        }

        final ArraySet<String> seenSplits = new ArraySet<>();

        // Verify that all staged packages are internally consistent
        for (File file : files) {
            final ApkLite info;
            try {
                info = PackageParser.parseApkLite(file, PackageParser.PARSE_GET_SIGNATURES);
            } catch (PackageParserException e) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Failed to parse " + file + ": " + e);
            }

            if (!seenSplits.add(info.splitName)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Split " + info.splitName + " was defined multiple times");
            }

            // Use first package to define unknown values
            if (mPackageName == null) {
                mPackageName = info.packageName;
                mVersionCode = info.versionCode;
            }
            if (mSignatures == null) {
                mSignatures = info.signatures;
            }

            assertPackageConsistent(String.valueOf(file), info.packageName, info.versionCode,
                    info.signatures);

            // Take this opportunity to enforce uniform naming
            final String name;
            if (info.splitName == null) {
                name = "base.apk";
            } else {
                name = "split_" + info.splitName + ".apk";
            }
            if (!FileUtils.isValidExtFilename(name)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Invalid filename: " + name);
            }
            if (!file.getName().equals(name)) {
                file.renameTo(new File(file.getParentFile(), name));
            }
        }

        // TODO: shift package signature verification to installer; we're
        // currently relying on PMS to do this.
        // TODO: teach about compatible upgrade keysets.

        if (params.mode == InstallSessionParams.MODE_FULL_INSTALL) {
            // Full installs must include a base package
            if (!seenSplits.contains(null)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Full install must include a base package");
            }

        } else {
            // Partial installs must be consistent with existing install.
            final ApplicationInfo app = mPm.getApplicationInfo(mPackageName, 0, userId);
            if (app == null) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Missing existing base package for " + mPackageName);
            }

            final ApkLite info;
            try {
                info = PackageParser.parseApkLite(new File(app.getBaseCodePath()),
                        PackageParser.PARSE_GET_SIGNATURES);
            } catch (PackageParserException e) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Failed to parse existing base " + app.getBaseCodePath() + ": " + e);
            }

            assertPackageConsistent("Existing base", info.packageName, info.versionCode,
                    info.signatures);
        }
    }

    private void assertPackageConsistent(String tag, String packageName, int versionCode,
            Signature[] signatures) throws PackageManagerException {
        if (!mPackageName.equals(packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag + " package "
                    + packageName + " inconsistent with " + mPackageName);
        }
        if (mVersionCode != versionCode) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag
                    + " version code " + versionCode + " inconsistent with "
                    + mVersionCode);
        }
        if (!Signature.areExactMatch(mSignatures, signatures)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    tag + " signatures are inconsistent");
        }
    }

    /**
     * Application is already installed; splice existing files that haven't been
     * overridden into our stage.
     */
    private void spliceExistingFilesIntoStage() throws PackageManagerException {
        final ApplicationInfo app = mPm.getApplicationInfo(mPackageName, 0, userId);

        int n = 0;
        final File[] oldFiles = new File(app.getCodePath()).listFiles();
        if (!ArrayUtils.isEmpty(oldFiles)) {
            for (File oldFile : oldFiles) {
                if (!PackageParser.isApkFile(oldFile)) continue;

                final File newFile = new File(sessionStageDir, oldFile.getName());
                try {
                    Os.link(oldFile.getAbsolutePath(), newFile.getAbsolutePath());
                    n++;
                } catch (ErrnoException e) {
                    throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                            "Failed to splice into stage", e);
                }
            }
        }

        if (LOGD) Slog.d(TAG, "Spliced " + n + " existing APKs into stage");
    }

    @Override
    public void destroy() {
        try {
            destroyInternal();
        } finally {
            mCallback.onSessionFinished(this, false);
        }
    }

    private void destroyInternal() {
        synchronized (mLock) {
            mInvalid = true;
        }
        FileUtils.deleteContents(sessionStageDir);
        sessionStageDir.delete();
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Session " + sessionId + ":");
        pw.increaseIndent();

        pw.printPair("userId", userId);
        pw.printPair("installerPackageName", installerPackageName);
        pw.printPair("installerUid", installerUid);
        pw.printPair("createdMillis", createdMillis);
        pw.printPair("sessionStageDir", sessionStageDir);
        pw.println();

        params.dump(pw);

        pw.printPair("mClientProgress", mClientProgress);
        pw.printPair("mProgress", mProgress);
        pw.printPair("mMutationsAllowed", mMutationsAllowed);
        pw.printPair("mPermissionsConfirmed", mPermissionsConfirmed);
        pw.printPair("mBridges", mBridges.size());
        pw.println();

        pw.decreaseIndent();
    }
}
