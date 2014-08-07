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
import static android.content.pm.PackageManager.INSTALL_FAILED_REJECTED;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_WRONLY;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.InstallSessionInfo;
import android.content.pm.InstallSessionParams;
import android.content.pm.PackageInstaller;
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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import libcore.io.Libcore;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = true;

    private static final int MSG_COMMIT = 0;

    // TODO: enforce INSTALL_ALLOW_TEST
    // TODO: enforce INSTALL_ALLOW_DOWNGRADE
    // TODO: handle INSTALL_EXTERNAL, INSTALL_INTERNAL

    // TODO: treat INHERIT_EXISTING as installExistingPackage()

    private final PackageInstallerService.InternalCallback mCallback;
    private final PackageManagerService mPm;
    private final Handler mHandler;

    final int sessionId;
    final int userId;
    final String installerPackageName;
    final InstallSessionParams params;
    final long createdMillis;
    final File sessionStageDir;

    /** Note that UID is not persisted; it's always derived at runtime. */
    final int installerUid;

    AtomicInteger openCount = new AtomicInteger();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private float mClientProgress = 0;
    @GuardedBy("mLock")
    private float mProgress = 0;
    @GuardedBy("mLock")
    private float mReportedProgress = -1;

    @GuardedBy("mLock")
    private boolean mSealed = false;
    @GuardedBy("mLock")
    private boolean mPermissionsAccepted = false;
    @GuardedBy("mLock")
    private boolean mDestroyed = false;

    private int mFinalStatus;
    private String mFinalMessage;

    /**
     * Path to the resolved base APK for this session, which may point at an APK
     * inside the session (when the session defines the base), or it may point
     * at the existing base APK (when adding splits to an existing app).
     * <p>
     * This is used when confirming permissions, since we can't fully stage the
     * session inside an ASEC before confirming with user.
     */
    @GuardedBy("mLock")
    private String mResolvedBaseCodePath;

    @GuardedBy("mLock")
    private ArrayList<FileBridge> mBridges = new ArrayList<>();

    @GuardedBy("mLock")
    private IPackageInstallObserver2 mRemoteObserver;

    /** Fields derived from commit parsing */
    private String mPackageName;
    private int mVersionCode;
    private Signature[] mSignatures;

    private final Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            synchronized (mLock) {
                if (msg.obj != null) {
                    mRemoteObserver = (IPackageInstallObserver2) msg.obj;
                }

                try {
                    commitLocked();
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "Install failed: " + e);
                    destroyInternal();
                    dispatchSessionFinished(e.error, e.getMessage(), null);
                }

                return true;
            }
        }
    };

    public PackageInstallerSession(PackageInstallerService.InternalCallback callback,
            PackageManagerService pm, Looper looper, int sessionId, int userId,
            String installerPackageName, InstallSessionParams params, long createdMillis,
            File sessionStageDir, boolean sealed) {
        mCallback = callback;
        mPm = pm;
        mHandler = new Handler(looper, mHandlerCallback);

        this.sessionId = sessionId;
        this.userId = userId;
        this.installerPackageName = installerPackageName;
        this.params = params;
        this.createdMillis = createdMillis;
        this.sessionStageDir = sessionStageDir;

        mSealed = sealed;

        // Always derived at runtime
        installerUid = mPm.getPackageUid(installerPackageName, userId);

        if (mPm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES,
                installerPackageName) == PackageManager.PERMISSION_GRANTED) {
            mPermissionsAccepted = true;
        } else {
            mPermissionsAccepted = false;
        }

        computeProgressLocked();
    }

    public InstallSessionInfo generateInfo() {
        final InstallSessionInfo info = new InstallSessionInfo();

        info.sessionId = sessionId;
        info.installerPackageName = installerPackageName;
        info.resolvedBaseCodePath = mResolvedBaseCodePath;
        info.progress = mProgress;
        info.sealed = mSealed;
        info.open = openCount.get() > 0;

        info.mode = params.mode;
        info.sizeBytes = params.sizeBytes;
        info.appPackageName = params.appPackageName;
        info.appIcon = params.appIcon;
        info.appLabel = params.appLabel;

        return info;
    }

    private void assertNotSealed(String cookie) {
        synchronized (mLock) {
            if (mSealed) {
                throw new SecurityException(cookie + " not allowed after commit");
            }
        }
    }

    @Override
    public void setClientProgress(float progress) {
        synchronized (mLock) {
            mClientProgress = progress;
            computeProgressLocked();
        }
        maybePublishProgress();
    }

    @Override
    public void addClientProgress(float progress) {
        synchronized (mLock) {
            mClientProgress += progress;
            computeProgressLocked();
        }
        maybePublishProgress();
    }

    private void computeProgressLocked() {
        mProgress = MathUtils.constrain(mClientProgress * 0.8f, 0f, 0.8f);
    }

    private void maybePublishProgress() {
        // Only publish when meaningful change
        if (Math.abs(mProgress - mReportedProgress) > 0.01) {
            mReportedProgress = mProgress;
            mCallback.onSessionProgressChanged(this, mProgress);
        }
    }

    @Override
    public String[] list() {
        assertNotSealed("list");
        return sessionStageDir.list();
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
            assertNotSealed("openWrite");

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
    public ParcelFileDescriptor openRead(String name) {
        try {
            return openReadInternal(name);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private ParcelFileDescriptor openReadInternal(String name) throws IOException {
        assertNotSealed("openRead");

        try {
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            final File target = new File(sessionStageDir, name);

            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(), O_RDONLY, 0);
            return new ParcelFileDescriptor(targetFd);

        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    @Override
    public void commit(IPackageInstallObserver2 observer) {
        Preconditions.checkNotNull(observer);
        mHandler.obtainMessage(MSG_COMMIT, observer).sendToTarget();
    }

    private void commitLocked() throws PackageManagerException {
        if (mDestroyed) {
            throw new PackageManagerException(INSTALL_FAILED_ALREADY_EXISTS, "Invalid session");
        }

        // Verify that all writers are hands-off
        if (!mSealed) {
            for (FileBridge bridge : mBridges) {
                if (!bridge.isClosed()) {
                    throw new PackageManagerException(INSTALL_FAILED_PACKAGE_CHANGED,
                            "Files still open");
                }
            }
            mSealed = true;

            // TODO: persist disabled mutations before going forward, since
            // beyond this point we may have hardlinks to the valid install
        }

        // Verify that stage looks sane with respect to existing application.
        // This currently only ensures packageName, versionCode, and certificate
        // consistency.
        validateInstallLocked();

        Preconditions.checkNotNull(mPackageName);
        Preconditions.checkNotNull(mSignatures);
        Preconditions.checkNotNull(mResolvedBaseCodePath);

        if (!mPermissionsAccepted) {
            // User needs to accept permissions; give installer an intent they
            // can use to involve user.
            final Intent intent = new Intent(PackageInstaller.ACTION_CONFIRM_PERMISSIONS);
            intent.setPackage("com.android.packageinstaller");
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
            try {
                mRemoteObserver.onUserActionRequired(intent);
            } catch (RemoteException ignored) {
            }
            return;
        }

        // Inherit any packages and native libraries from existing install that
        // haven't been overridden.
        if (params.mode == InstallSessionParams.MODE_INHERIT_EXISTING) {
            spliceExistingFilesIntoStage();
        }

        // TODO: surface more granular state from dexopt
        mCallback.onSessionProgressChanged(this, 0.9f);

        // TODO: for ASEC based applications, grow and stream in packages

        // We've reached point of no return; call into PMS to install the stage.
        // Regardless of success or failure we always destroy session.
        final IPackageInstallObserver2 remoteObserver = mRemoteObserver;
        final IPackageInstallObserver2 localObserver = new IPackageInstallObserver2.Stub() {
            @Override
            public void onUserActionRequired(Intent intent) {
                throw new IllegalStateException();
            }

            @Override
            public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                    Bundle extras) {
                destroyInternal();
                dispatchSessionFinished(returnCode, msg, extras);
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
        mSignatures = null;
        mResolvedBaseCodePath = null;

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
            final String targetName;
            if (info.splitName == null) {
                targetName = "base.apk";
            } else {
                targetName = "split_" + info.splitName + ".apk";
            }
            if (!FileUtils.isValidExtFilename(targetName)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Invalid filename: " + targetName);
            }

            final File targetFile = new File(sessionStageDir, targetName);
            if (!file.equals(targetFile)) {
                file.renameTo(targetFile);
            }

            // Base is coming from session
            if (info.splitName == null) {
                mResolvedBaseCodePath = targetFile.getAbsolutePath();
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
            // Partial installs must be consistent with existing install
            final ApplicationInfo app = mPm.getApplicationInfo(mPackageName, 0, userId);
            if (app == null) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Missing existing base package for " + mPackageName);
            }

            // Base might be inherited from existing install
            if (mResolvedBaseCodePath == null) {
                mResolvedBaseCodePath = app.getBaseCodePath();
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

    void setPermissionsResult(boolean accepted) {
        if (!mSealed) {
            throw new SecurityException("Must be sealed to accept permissions");
        }

        if (accepted) {
            // Mark and kick off another install pass
            mPermissionsAccepted = true;
            mHandler.obtainMessage(MSG_COMMIT).sendToTarget();
        } else {
            destroyInternal();
            dispatchSessionFinished(INSTALL_FAILED_REJECTED, "User rejected permissions", null);
        }
    }

    @Override
    public void close() {
        if (openCount.decrementAndGet() == 0) {
            mCallback.onSessionClosed(this);
        }
    }

    @Override
    public void abandon() {
        destroyInternal();
        dispatchSessionFinished(INSTALL_FAILED_INTERNAL_ERROR, "Session was abandoned", null);
    }

    private void dispatchSessionFinished(int returnCode, String msg, Bundle extras) {
        mFinalStatus = returnCode;
        mFinalMessage = msg;

        if (mRemoteObserver != null) {
            try {
                mRemoteObserver.onPackageInstalled(mPackageName, returnCode, msg, extras);
            } catch (RemoteException ignored) {
            }
        }

        final boolean success = (returnCode == PackageManager.INSTALL_SUCCEEDED);
        mCallback.onSessionFinished(this, success);
    }

    private void destroyInternal() {
        synchronized (mLock) {
            mSealed = true;
            mDestroyed = true;
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
        pw.printPair("mSealed", mSealed);
        pw.printPair("mPermissionsAccepted", mPermissionsAccepted);
        pw.printPair("mDestroyed", mDestroyed);
        pw.printPair("mBridges", mBridges.size());
        pw.printPair("mFinalStatus", mFinalStatus);
        pw.printPair("mFinalMessage", mFinalMessage);
        pw.println();

        pw.decreaseIndent();
    }

    Snapshot snapshot() {
        return new Snapshot(this);
    }

    static class Snapshot {
        final float clientProgress;
        final boolean sealed;

        public Snapshot(PackageInstallerSession session) {
            clientProgress = session.mClientProgress;
            sealed = session.mSealed;
        }
    }
}
