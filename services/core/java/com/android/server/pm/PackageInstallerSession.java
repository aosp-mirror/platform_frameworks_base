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
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;

import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInstallerParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;
import libcore.io.Libcore;
import libcore.io.Streams;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstaller";

    private final PackageInstallerService.Callback mCallback;
    private final PackageManagerService mPm;
    private final Handler mHandler;

    public final int sessionId;
    public final int userId;
    public final String installerPackageName;
    /** UID not persisted */
    public final int installerUid;
    public final PackageInstallerParams params;
    public final long createdMillis;
    public final File sessionDir;

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
                } catch (InstallFailedException e) {
                    Slog.e(TAG, "Install failed: " + e);
                    try {
                        mRemoteObserver.packageInstalled(mPackageName, null, e.error);
                    } catch (RemoteException ignored) {
                    }
                }

                return true;
            }
        }
    };

    private final Object mLock = new Object();

    private int mProgress;

    private String mPackageName;
    private int mVersionCode;
    private Signature[] mSignatures;

    private boolean mMutationsAllowed;
    private boolean mVerifierConfirmed;
    private boolean mPermissionsConfirmed;
    private boolean mInvalid;

    private ArrayList<FileBridge> mBridges = new ArrayList<>();

    private IPackageInstallObserver2 mRemoteObserver;

    public PackageInstallerSession(PackageInstallerService.Callback callback,
            PackageManagerService pm, int sessionId, int userId, String installerPackageName,
            int installerUid, PackageInstallerParams params, long createdMillis, File sessionDir,
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
        this.sessionDir = sessionDir;

        // Check against any explicitly provided signatures
        mSignatures = params.signatures;

        // TODO: splice in flag when restoring persisted session
        mMutationsAllowed = true;

        if (pm.checkPermission(android.Manifest.permission.INSTALL_PACKAGES, installerPackageName)
                == PackageManager.PERMISSION_GRANTED) {
            mPermissionsConfirmed = true;
        }
    }

    @Override
    public void updateProgress(int progress) {
        mProgress = progress;
        mCallback.onProgressChanged(this);
    }

    @Override
    public ParcelFileDescriptor openWrite(String name, long offsetBytes, long lengthBytes) {
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
            final File target = new File(sessionDir, name);

            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(),
                    OsConstants.O_CREAT | OsConstants.O_WRONLY, 00700);

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
            throw new IllegalStateException("Failed to write", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write", e);
        }
    }

    @Override
    public void install(IPackageInstallObserver2 observer) {
        Preconditions.checkNotNull(observer);
        mHandler.obtainMessage(MSG_INSTALL, observer).sendToTarget();
    }

    private void installLocked() throws InstallFailedException {
        if (mInvalid) {
            throw new InstallFailedException(INSTALL_FAILED_ALREADY_EXISTS, "Invalid session");
        }

        // Verify that all writers are hands-off
        if (mMutationsAllowed) {
            for (FileBridge bridge : mBridges) {
                if (!bridge.isClosed()) {
                    throw new InstallFailedException(INSTALL_FAILED_PACKAGE_CHANGED,
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

        if (!mVerifierConfirmed) {
            // TODO: async communication with verifier
            // when they confirm, we'll kick off another install() pass
            mVerifierConfirmed = true;
        }

        if (!mPermissionsConfirmed) {
            // TODO: async confirm permissions with user
            // when they confirm, we'll kick off another install() pass
            mPermissionsConfirmed = true;
        }

        // Unpack any native libraries contained in this session
        unpackNativeLibraries();

        // Inherit any packages and native libraries from existing install that
        // haven't been overridden.
        if (!params.fullInstall) {
            spliceExistingFilesIntoStage();
        }

        // TODO: for ASEC based applications, grow and stream in packages

        // We've reached point of no return; call into PMS to install the stage.
        // Regardless of success or failure we always destroy session.
        final IPackageInstallObserver2 remoteObserver = mRemoteObserver;
        final IPackageInstallObserver2 localObserver = new IPackageInstallObserver2.Stub() {
            @Override
            public void packageInstalled(String basePackageName, Bundle extras, int returnCode)
                    throws RemoteException {
                destroy();
                remoteObserver.packageInstalled(basePackageName, extras, returnCode);
            }
        };

        mPm.installStage(mPackageName, this.sessionDir, localObserver, params.installFlags);
    }

    /**
     * Validate install by confirming that all application packages are have
     * consistent package name, version code, and signing certificates.
     * <p>
     * Renames package files in stage to match split names defined inside.
     */
    private void validateInstallLocked() throws InstallFailedException {
        mPackageName = null;
        mVersionCode = -1;
        mSignatures = null;

        final File[] files = sessionDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            throw new InstallFailedException(INSTALL_FAILED_INVALID_APK, "No packages staged");
        }

        final ArraySet<String> seenSplits = new ArraySet<>();

        // Verify that all staged packages are internally consistent
        for (File file : files) {
            final ApkLite info = PackageParser.parseApkLite(file.getAbsolutePath(),
                    PackageParser.PARSE_GET_SIGNATURES);
            if (info == null) {
                throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                        "Failed to parse " + file);
            }

            if (!seenSplits.add(info.splitName)) {
                throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                        "Split " + info.splitName + " was defined multiple times");
            }

            // Use first package to define unknown values
            if (mPackageName != null) {
                mPackageName = info.packageName;
                mVersionCode = info.versionCode;
            }
            if (mSignatures != null) {
                mSignatures = info.signatures;
            }

            assertPackageConsistent(String.valueOf(file), info.packageName, info.versionCode,
                    info.signatures);

            // Take this opportunity to enforce uniform naming
            final String name;
            if (info.splitName == null) {
                name = info.packageName + ".apk";
            } else {
                name = info.packageName + "-" + info.splitName + ".apk";
            }
            if (!FileUtils.isValidExtFilename(name)) {
                throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                        "Invalid filename: " + name);
            }
            if (!file.getName().equals(name)) {
                file.renameTo(new File(file.getParentFile(), name));
            }
        }

        // TODO: shift package signature verification to installer; we're
        // currently relying on PMS to do this.
        // TODO: teach about compatible upgrade keysets.

        if (params.fullInstall) {
            // Full installs must include a base package
            if (!seenSplits.contains(null)) {
                throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                        "Full install must include a base package");
            }

        } else {
            // Partial installs must be consistent with existing install.
            final ApplicationInfo app = mPm.getApplicationInfo(mPackageName, 0, userId);
            if (app == null) {
                throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                        "Missing existing base package for " + mPackageName);
            }

            final ApkLite info = PackageParser.parseApkLite(app.sourceDir,
                    PackageParser.PARSE_GET_SIGNATURES);
            if (info == null) {
                throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                        "Failed to parse existing base " + app.sourceDir);
            }

            assertPackageConsistent("Existing base", info.packageName, info.versionCode,
                    info.signatures);
        }
    }

    private void assertPackageConsistent(String tag, String packageName, int versionCode,
            Signature[] signatures) throws InstallFailedException {
        if (!mPackageName.equals(packageName)) {
            throw new InstallFailedException(INSTALL_FAILED_INVALID_APK, tag + " package "
                    + packageName + " inconsistent with " + mPackageName);
        }
        if (mVersionCode != versionCode) {
            throw new InstallFailedException(INSTALL_FAILED_INVALID_APK, tag
                    + " version code " + versionCode + " inconsistent with "
                    + mVersionCode);
        }
        if (!Signature.areExactMatch(mSignatures, signatures)) {
            throw new InstallFailedException(INSTALL_FAILED_INVALID_APK,
                    tag + " signatures are inconsistent");
        }
    }

    /**
     * Application is already installed; splice existing files that haven't been
     * overridden into our stage.
     */
    private void spliceExistingFilesIntoStage() throws InstallFailedException {
        final ApplicationInfo app = mPm.getApplicationInfo(mPackageName, 0, userId);
        final File existingDir = new File(app.sourceDir).getParentFile();

        try {
            linkTreeIgnoringExisting(existingDir, sessionDir);
        } catch (ErrnoException e) {
            throw new InstallFailedException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to splice into stage");
        }
    }

    /**
     * Recursively hard link all files from source directory tree to target.
     * When a file already exists in the target tree, it leaves that file
     * intact.
     */
    private void linkTreeIgnoringExisting(File sourceDir, File targetDir) throws ErrnoException {
        final File[] sourceContents = sourceDir.listFiles();
        if (ArrayUtils.isEmpty(sourceContents)) return;

        for (File sourceFile : sourceContents) {
            final File targetFile = new File(targetDir, sourceFile.getName());

            if (sourceFile.isDirectory()) {
                targetFile.mkdir();
                linkTreeIgnoringExisting(sourceFile, targetFile);
            } else {
                Libcore.os.link(sourceFile.getAbsolutePath(), targetFile.getAbsolutePath());
            }
        }
    }

    private void unpackNativeLibraries() throws InstallFailedException {
        final File libDir = new File(sessionDir, "lib");

        if (!libDir.mkdir()) {
            throw new InstallFailedException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to create " + libDir);
        }

        try {
            Libcore.os.chmod(libDir.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            throw new InstallFailedException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to prepare " + libDir + ": " + e);
        }

        if (!SELinux.restorecon(libDir)) {
            throw new InstallFailedException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to set context on " + libDir);
        }

        // Unpack all native libraries under stage
        final File[] files = sessionDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            throw new InstallFailedException(INSTALL_FAILED_INVALID_APK, "No packages staged");
        }

        for (File file : files) {
            final NativeLibraryHelper.ApkHandle handle = new NativeLibraryHelper.ApkHandle(file);
            try {
                final int abiIndex = NativeLibraryHelper.findSupportedAbi(handle,
                        Build.SUPPORTED_ABIS);
                if (abiIndex >= 0) {
                    int copyRet = NativeLibraryHelper.copyNativeBinariesIfNeededLI(handle, libDir,
                            Build.SUPPORTED_ABIS[abiIndex]);
                    if (copyRet != INSTALL_SUCCEEDED) {
                        throw new InstallFailedException(copyRet,
                                "Failed to copy native libraries for " + file);
                    }
                } else if (abiIndex != PackageManager.NO_NATIVE_LIBRARIES) {
                    throw new InstallFailedException(abiIndex,
                            "Failed to copy native libraries for " + file);
                }
            } finally {
                handle.close();
            }
        }
    }

    @Override
    public void destroy() {
        try {
            synchronized (mLock) {
                mInvalid = true;
            }
            FileUtils.deleteContents(sessionDir);
            sessionDir.delete();
        } finally {
            mCallback.onSessionInvalid(this);
        }
    }

    private class InstallFailedException extends Exception {
        private final int error;

        public InstallFailedException(int error, String detailMessage) {
            super(detailMessage);
            this.error = error;
        }
    }
}
