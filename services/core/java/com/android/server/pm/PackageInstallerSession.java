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

import static android.content.pm.PackageManager.INSTALL_FAILED_ABORTED;
import static android.content.pm.PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_WRONLY;
import static com.android.server.pm.PackageInstallerService.prepareExternalStageCid;
import static com.android.server.pm.PackageInstallerService.prepareStageDir;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.PackageLite;
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
import android.os.SELinux;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.MathUtils;
import android.util.Slog;

import libcore.io.IoUtils;
import libcore.io.Libcore;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.pm.PackageInstallerService.PackageInstallObserverAdapter;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = true;

    private static final int MSG_COMMIT = 0;

    // TODO: enforce INSTALL_ALLOW_TEST
    // TODO: enforce INSTALL_ALLOW_DOWNGRADE

    private final PackageInstallerService.InternalCallback mCallback;
    private final Context mContext;
    private final PackageManagerService mPm;
    private final Handler mHandler;
    private final boolean mIsInstallerDeviceOwner;

    final int sessionId;
    final int userId;
    final String installerPackageName;
    final int installerUid;
    final SessionParams params;
    final long createdMillis;

    /** Staging location where client data is written. */
    final File stageDir;
    final String stageCid;

    private final AtomicInteger mActiveCount = new AtomicInteger();

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private float mClientProgress = 0;
    @GuardedBy("mLock")
    private float mInternalProgress = 0;

    @GuardedBy("mLock")
    private float mProgress = 0;
    @GuardedBy("mLock")
    private float mReportedProgress = -1;

    @GuardedBy("mLock")
    private boolean mPrepared = false;
    @GuardedBy("mLock")
    private boolean mSealed = false;
    @GuardedBy("mLock")
    private boolean mPermissionsAccepted = false;
    @GuardedBy("mLock")
    private boolean mRelinquished = false;
    @GuardedBy("mLock")
    private boolean mDestroyed = false;

    private int mFinalStatus;
    private String mFinalMessage;

    @GuardedBy("mLock")
    private ArrayList<FileBridge> mBridges = new ArrayList<>();

    @GuardedBy("mLock")
    private IPackageInstallObserver2 mRemoteObserver;

    /** Fields derived from commit parsing */
    private String mPackageName;
    private int mVersionCode;
    private Signature[] mSignatures;

    /**
     * Path to the validated base APK for this session, which may point at an
     * APK inside the session (when the session defines the base), or it may
     * point at the existing base APK (when adding splits to an existing app).
     * <p>
     * This is used when confirming permissions, since we can't fully stage the
     * session inside an ASEC before confirming with user.
     */
    @GuardedBy("mLock")
    private File mResolvedBaseFile;

    @GuardedBy("mLock")
    private File mResolvedStageDir;

    @GuardedBy("mLock")
    private final List<File> mResolvedStagedFiles = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<File> mResolvedInheritedFiles = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<String> mResolvedInstructionSets = new ArrayList<>();
    @GuardedBy("mLock")
    private File mInheritedFilesBase;

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
                    final String completeMsg = ExceptionUtils.getCompleteMessage(e);
                    Slog.e(TAG, "Commit of session " + sessionId + " failed: " + completeMsg);
                    destroyInternal();
                    dispatchSessionFinished(e.error, completeMsg, null);
                }

                return true;
            }
        }
    };

    public PackageInstallerSession(PackageInstallerService.InternalCallback callback,
            Context context, PackageManagerService pm, Looper looper, int sessionId, int userId,
            String installerPackageName, int installerUid, SessionParams params, long createdMillis,
            File stageDir, String stageCid, boolean prepared, boolean sealed) {
        mCallback = callback;
        mContext = context;
        mPm = pm;
        mHandler = new Handler(looper, mHandlerCallback);

        this.sessionId = sessionId;
        this.userId = userId;
        this.installerPackageName = installerPackageName;
        this.installerUid = installerUid;
        this.params = params;
        this.createdMillis = createdMillis;
        this.stageDir = stageDir;
        this.stageCid = stageCid;

        if ((stageDir == null) == (stageCid == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of stageDir or stageCid stage must be set");
        }

        mPrepared = prepared;
        mSealed = sealed;

        // Device owners are allowed to silently install packages, so the permission check is
        // waived if the installer is the device owner.
        DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        final boolean isPermissionGranted =
                (mPm.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES, installerUid)
                        == PackageManager.PERMISSION_GRANTED);
        final boolean isInstallerRoot = (installerUid == Process.ROOT_UID);
        final boolean forcePermissionPrompt =
                (params.installFlags & PackageManager.INSTALL_FORCE_PERMISSION_PROMPT) != 0;
        mIsInstallerDeviceOwner = (dpm != null) && dpm.isDeviceOwnerApp(installerPackageName);
        if ((isPermissionGranted
                        || isInstallerRoot
                        || mIsInstallerDeviceOwner)
                && !forcePermissionPrompt) {
            mPermissionsAccepted = true;
        } else {
            mPermissionsAccepted = false;
        }
    }

    public SessionInfo generateInfo() {
        final SessionInfo info = new SessionInfo();
        synchronized (mLock) {
            info.sessionId = sessionId;
            info.installerPackageName = installerPackageName;
            info.resolvedBaseCodePath = (mResolvedBaseFile != null) ?
                    mResolvedBaseFile.getAbsolutePath() : null;
            info.progress = mProgress;
            info.sealed = mSealed;
            info.active = mActiveCount.get() > 0;

            info.mode = params.mode;
            info.sizeBytes = params.sizeBytes;
            info.appPackageName = params.appPackageName;
            info.appIcon = params.appIcon;
            info.appLabel = params.appLabel;
        }
        return info;
    }

    public boolean isPrepared() {
        synchronized (mLock) {
            return mPrepared;
        }
    }

    public boolean isSealed() {
        synchronized (mLock) {
            return mSealed;
        }
    }

    private void assertPreparedAndNotSealed(String cookie) {
        synchronized (mLock) {
            if (!mPrepared) {
                throw new IllegalStateException(cookie + " before prepared");
            }
            if (mSealed) {
                throw new SecurityException(cookie + " not allowed after commit");
            }
        }
    }

    /**
     * Resolve the actual location where staged data should be written. This
     * might point at an ASEC mount point, which is why we delay path resolution
     * until someone actively works with the session.
     */
    private File resolveStageDir() throws IOException {
        synchronized (mLock) {
            if (mResolvedStageDir == null) {
                if (stageDir != null) {
                    mResolvedStageDir = stageDir;
                } else {
                    final String path = PackageHelper.getSdDir(stageCid);
                    if (path != null) {
                        mResolvedStageDir = new File(path);
                    } else {
                        throw new IOException("Failed to resolve path to container " + stageCid);
                    }
                }
            }
            return mResolvedStageDir;
        }
    }

    @Override
    public void setClientProgress(float progress) {
        synchronized (mLock) {
            // Always publish first staging movement
            final boolean forcePublish = (mClientProgress == 0);
            mClientProgress = progress;
            computeProgressLocked(forcePublish);
        }
    }

    @Override
    public void addClientProgress(float progress) {
        synchronized (mLock) {
            setClientProgress(mClientProgress + progress);
        }
    }

    private void computeProgressLocked(boolean forcePublish) {
        mProgress = MathUtils.constrain(mClientProgress * 0.8f, 0f, 0.8f)
                + MathUtils.constrain(mInternalProgress * 0.2f, 0f, 0.2f);

        // Only publish when meaningful change
        if (forcePublish || Math.abs(mProgress - mReportedProgress) >= 0.01) {
            mReportedProgress = mProgress;
            mCallback.onSessionProgressChanged(this, mProgress);
        }
    }

    @Override
    public String[] getNames() {
        assertPreparedAndNotSealed("getNames");
        try {
            return resolveStageDir().list();
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
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
        // Quick sanity check of state, and allocate a pipe for ourselves. We
        // then do heavy disk allocation outside the lock, but this open pipe
        // will block any attempted install transitions.
        final FileBridge bridge;
        synchronized (mLock) {
            assertPreparedAndNotSealed("openWrite");

            bridge = new FileBridge();
            mBridges.add(bridge);
        }

        try {
            // Use installer provided name for now; we always rename later
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            final File target = new File(resolveStageDir(), name);

            // TODO: this should delegate to DCS so the system process avoids
            // holding open FDs into containers.
            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(),
                    O_CREAT | O_WRONLY, 0644);
            Os.chmod(target.getAbsolutePath(), 0644);

            // If caller specified a total length, allocate it for them. Free up
            // cache space to grow, if needed.
            if (lengthBytes > 0) {
                final StructStat stat = Libcore.os.fstat(targetFd);
                final long deltaBytes = lengthBytes - stat.st_size;
                // Only need to free up space when writing to internal stage
                if (stageDir != null && deltaBytes > 0) {
                    mPm.freeStorage(params.volumeUuid, deltaBytes);
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
        assertPreparedAndNotSealed("openRead");

        try {
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            final File target = new File(resolveStageDir(), name);

            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(), O_RDONLY, 0);
            return new ParcelFileDescriptor(targetFd);

        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    @Override
    public void commit(IntentSender statusReceiver) {
        Preconditions.checkNotNull(statusReceiver);

        final boolean wasSealed;
        synchronized (mLock) {
            wasSealed = mSealed;
            if (!mSealed) {
                // Verify that all writers are hands-off
                for (FileBridge bridge : mBridges) {
                    if (!bridge.isClosed()) {
                        throw new SecurityException("Files still open");
                    }
                }
                mSealed = true;
            }

            // Client staging is fully done at this point
            mClientProgress = 1f;
            computeProgressLocked(true);
        }

        if (!wasSealed) {
            // Persist the fact that we've sealed ourselves to prevent
            // mutations of any hard links we create. We do this without holding
            // the session lock, since otherwise it's a lock inversion.
            mCallback.onSessionSealedBlocking(this);
        }

        // This ongoing commit should keep session active, even though client
        // will probably close their end.
        mActiveCount.incrementAndGet();

        final PackageInstallObserverAdapter adapter = new PackageInstallObserverAdapter(mContext,
                statusReceiver, sessionId, mIsInstallerDeviceOwner, userId);
        mHandler.obtainMessage(MSG_COMMIT, adapter.getBinder()).sendToTarget();
    }

    private void commitLocked() throws PackageManagerException {
        if (mDestroyed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session destroyed");
        }
        if (!mSealed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session not sealed");
        }

        try {
            resolveStageDir();
        } catch (IOException e) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to resolve stage location", e);
        }

        // Verify that stage looks sane with respect to existing application.
        // This currently only ensures packageName, versionCode, and certificate
        // consistency.
        validateInstallLocked();

        Preconditions.checkNotNull(mPackageName);
        Preconditions.checkNotNull(mSignatures);
        Preconditions.checkNotNull(mResolvedBaseFile);

        if (!mPermissionsAccepted) {
            // User needs to accept permissions; give installer an intent they
            // can use to involve user.
            final Intent intent = new Intent(PackageInstaller.ACTION_CONFIRM_PERMISSIONS);
            intent.setPackage(mContext.getPackageManager().getPermissionControllerPackageName());
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
            try {
                mRemoteObserver.onUserActionRequired(intent);
            } catch (RemoteException ignored) {
            }

            // Commit was keeping session marked as active until now; release
            // that extra refcount so session appears idle.
            close();
            return;
        }

        if (stageCid != null) {
            // Figure out the final installed size and resize the container once
            // and for all. Internally the parser handles straddling between two
            // locations when inheriting.
            final long finalSize = calculateInstalledSize();
            resizeContainer(stageCid, finalSize);
        }

        // Inherit any packages and native libraries from existing install that
        // haven't been overridden.
        if (params.mode == SessionParams.MODE_INHERIT_EXISTING) {
            try {
                final List<File> fromFiles = mResolvedInheritedFiles;
                final File toDir = resolveStageDir();

                if (LOGD) Slog.d(TAG, "Inherited files: " + mResolvedInheritedFiles);
                if (!mResolvedInheritedFiles.isEmpty() && mInheritedFilesBase == null) {
                    throw new IllegalStateException("mInheritedFilesBase == null");
                }

                if (isLinkPossible(fromFiles, toDir)) {
                    if (!mResolvedInstructionSets.isEmpty()) {
                        final File oatDir = new File(toDir, "oat");
                        createOatDirs(mResolvedInstructionSets, oatDir);
                    }
                    linkFiles(fromFiles, toDir, mInheritedFilesBase);
                } else {
                    // TODO: this should delegate to DCS so the system process
                    // avoids holding open FDs into containers.
                    copyFiles(fromFiles, toDir);
                }
            } catch (IOException e) {
                throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                        "Failed to inherit existing install", e);
            }
        }

        // TODO: surface more granular state from dexopt
        mInternalProgress = 0.5f;
        computeProgressLocked(true);

        // Unpack native libraries
        extractNativeLibraries(mResolvedStageDir, params.abiOverride);

        // Container is ready to go, let's seal it up!
        if (stageCid != null) {
            finalizeAndFixContainer(stageCid);
        }

        // We've reached point of no return; call into PMS to install the stage.
        // Regardless of success or failure we always destroy session.
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

        final UserHandle user;
        if ((params.installFlags & PackageManager.INSTALL_ALL_USERS) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(userId);
        }

        mRelinquished = true;
        mPm.installStage(mPackageName, stageDir, stageCid, localObserver, params,
                installerPackageName, installerUid, user);
    }

    /**
     * Validate install by confirming that all application packages are have
     * consistent package name, version code, and signing certificates.
     * <p>
     * Clears and populates {@link #mResolvedBaseFile},
     * {@link #mResolvedStagedFiles}, and {@link #mResolvedInheritedFiles}.
     * <p>
     * Renames package files in stage to match split names defined inside.
     * <p>
     * Note that upgrade compatibility is still performed by
     * {@link PackageManagerService}.
     */
    private void validateInstallLocked() throws PackageManagerException {
        mPackageName = null;
        mVersionCode = -1;
        mSignatures = null;

        mResolvedBaseFile = null;
        mResolvedStagedFiles.clear();
        mResolvedInheritedFiles.clear();

        final File[] files = mResolvedStageDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, "No packages staged");
        }

        // Verify that all staged packages are internally consistent
        final ArraySet<String> stagedSplits = new ArraySet<>();
        for (File file : files) {

            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) continue;

            final ApkLite apk;
            try {
                apk = PackageParser.parseApkLite(file, PackageParser.PARSE_COLLECT_CERTIFICATES);
            } catch (PackageParserException e) {
                throw PackageManagerException.from(e);
            }

            if (!stagedSplits.add(apk.splitName)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Split " + apk.splitName + " was defined multiple times");
            }

            // Use first package to define unknown values
            if (mPackageName == null) {
                mPackageName = apk.packageName;
                mVersionCode = apk.versionCode;
            }
            if (mSignatures == null) {
                mSignatures = apk.signatures;
            }

            assertApkConsistent(String.valueOf(file), apk);

            // Take this opportunity to enforce uniform naming
            final String targetName;
            if (apk.splitName == null) {
                targetName = "base.apk";
            } else {
                targetName = "split_" + apk.splitName + ".apk";
            }
            if (!FileUtils.isValidExtFilename(targetName)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Invalid filename: " + targetName);
            }

            final File targetFile = new File(mResolvedStageDir, targetName);
            if (!file.equals(targetFile)) {
                file.renameTo(targetFile);
            }

            // Base is coming from session
            if (apk.splitName == null) {
                mResolvedBaseFile = targetFile;
            }

            mResolvedStagedFiles.add(targetFile);
        }

        if (params.mode == SessionParams.MODE_FULL_INSTALL) {
            // Full installs must include a base package
            if (!stagedSplits.contains(null)) {
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

            final PackageLite existing;
            final ApkLite existingBase;
            try {
                existing = PackageParser.parsePackageLite(new File(app.getCodePath()), 0);
                existingBase = PackageParser.parseApkLite(new File(app.getBaseCodePath()),
                        PackageParser.PARSE_COLLECT_CERTIFICATES);
            } catch (PackageParserException e) {
                throw PackageManagerException.from(e);
            }

            assertApkConsistent("Existing base", existingBase);

            // Inherit base if not overridden
            if (mResolvedBaseFile == null) {
                mResolvedBaseFile = new File(app.getBaseCodePath());
                mResolvedInheritedFiles.add(mResolvedBaseFile);
            }

            // Inherit splits if not overridden
            if (!ArrayUtils.isEmpty(existing.splitNames)) {
                for (int i = 0; i < existing.splitNames.length; i++) {
                    final String splitName = existing.splitNames[i];
                    final File splitFile = new File(existing.splitCodePaths[i]);

                    if (!stagedSplits.contains(splitName)) {
                        mResolvedInheritedFiles.add(splitFile);
                    }
                }
            }

            // Inherit compiled oat directory.
            final File packageInstallDir = (new File(app.getBaseCodePath())).getParentFile();
            mInheritedFilesBase = packageInstallDir;
            final File oatDir = new File(packageInstallDir, "oat");
            if (oatDir.exists()) {
                final File[] archSubdirs = oatDir.listFiles();

                // Keep track of all instruction sets we've seen compiled output for.
                // If we're linking (and not copying) inherited files, we can recreate the
                // instruction set hierarchy and link compiled output.
                if (archSubdirs != null && archSubdirs.length > 0) {
                    final String[] instructionSets = InstructionSets.getAllDexCodeInstructionSets();
                    for (File archSubDir : archSubdirs) {
                        // Skip any directory that isn't an ISA subdir.
                        if (!ArrayUtils.contains(instructionSets, archSubDir.getName())) {
                            continue;
                        }

                        mResolvedInstructionSets.add(archSubDir.getName());
                        List<File> oatFiles = Arrays.asList(archSubDir.listFiles());
                        if (!oatFiles.isEmpty()) {
                            mResolvedInheritedFiles.addAll(oatFiles);
                        }
                    }
                }
            }
        }
    }

    private void assertApkConsistent(String tag, ApkLite apk) throws PackageManagerException {
        if (!mPackageName.equals(apk.packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag + " package "
                    + apk.packageName + " inconsistent with " + mPackageName);
        }
        if (mVersionCode != apk.versionCode) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag
                    + " version code " + apk.versionCode + " inconsistent with "
                    + mVersionCode);
        }
        if (!Signature.areExactMatch(mSignatures, apk.signatures)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    tag + " signatures are inconsistent");
        }
    }

    /**
     * Calculate the final install footprint size, combining both staged and
     * existing APKs together and including unpacked native code from both.
     */
    private long calculateInstalledSize() throws PackageManagerException {
        Preconditions.checkNotNull(mResolvedBaseFile);

        final ApkLite baseApk;
        try {
            baseApk = PackageParser.parseApkLite(mResolvedBaseFile, 0);
        } catch (PackageParserException e) {
            throw PackageManagerException.from(e);
        }

        final List<String> splitPaths = new ArrayList<>();
        for (File file : mResolvedStagedFiles) {
            if (mResolvedBaseFile.equals(file)) continue;
            splitPaths.add(file.getAbsolutePath());
        }
        for (File file : mResolvedInheritedFiles) {
            if (mResolvedBaseFile.equals(file)) continue;
            splitPaths.add(file.getAbsolutePath());
        }

        // This is kind of hacky; we're creating a half-parsed package that is
        // straddled between the inherited and staged APKs.
        final PackageLite pkg = new PackageLite(null, baseApk, null,
                splitPaths.toArray(new String[splitPaths.size()]), null);
        final boolean isForwardLocked =
                (params.installFlags & PackageManager.INSTALL_FORWARD_LOCK) != 0;

        try {
            return PackageHelper.calculateInstalledSize(pkg, isForwardLocked, params.abiOverride);
        } catch (IOException e) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Failed to calculate install size", e);
        }
    }

    /**
     * Determine if creating hard links between source and destination is
     * possible. That is, do they all live on the same underlying device.
     */
    private boolean isLinkPossible(List<File> fromFiles, File toDir) {
        try {
            final StructStat toStat = Os.stat(toDir.getAbsolutePath());
            for (File fromFile : fromFiles) {
                final StructStat fromStat = Os.stat(fromFile.getAbsolutePath());
                if (fromStat.st_dev != toStat.st_dev) {
                    return false;
                }
            }
        } catch (ErrnoException e) {
            Slog.w(TAG, "Failed to detect if linking possible: " + e);
            return false;
        }
        return true;
    }

    private static String getRelativePath(File file, File base) throws IOException {
        final String pathStr = file.getAbsolutePath();
        final String baseStr = base.getAbsolutePath();
        // Don't allow relative paths.
        if (pathStr.contains("/.") ) {
            throw new IOException("Invalid path (was relative) : " + pathStr);
        }

        if (pathStr.startsWith(baseStr)) {
            return pathStr.substring(baseStr.length());
        }

        throw new IOException("File: " + pathStr + " outside base: " + baseStr);
    }

    private void createOatDirs(List<String> instructionSets, File fromDir) {
        for (String instructionSet : instructionSets) {
            mPm.mInstaller.createOatDir(fromDir.getAbsolutePath(), instructionSet);
        }
    }

    private void linkFiles(List<File> fromFiles, File toDir, File fromDir)
            throws IOException {
        for (File fromFile : fromFiles) {
            final String relativePath = getRelativePath(fromFile, fromDir);
            final int ret = mPm.mInstaller.linkFile(relativePath, fromDir.getAbsolutePath(),
                    toDir.getAbsolutePath());

            if (ret < 0) {
                // installd will log failure details.
                throw new IOException("failed linkOrCreateDir(" + relativePath + ", "
                        + fromDir + ", " + toDir + ")");
            }
        }

        Slog.d(TAG, "Linked " + fromFiles.size() + " files into " + toDir);
    }

    private static void copyFiles(List<File> fromFiles, File toDir) throws IOException {
        // Remove any partial files from previous attempt
        for (File file : toDir.listFiles()) {
            if (file.getName().endsWith(".tmp")) {
                file.delete();
            }
        }

        for (File fromFile : fromFiles) {
            final File tmpFile = File.createTempFile("inherit", ".tmp", toDir);
            if (LOGD) Slog.d(TAG, "Copying " + fromFile + " to " + tmpFile);
            if (!FileUtils.copyFile(fromFile, tmpFile)) {
                throw new IOException("Failed to copy " + fromFile + " to " + tmpFile);
            }
            try {
                Os.chmod(tmpFile.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                throw new IOException("Failed to chmod " + tmpFile);
            }
            final File toFile = new File(toDir, fromFile.getName());
            if (LOGD) Slog.d(TAG, "Renaming " + tmpFile + " to " + toFile);
            if (!tmpFile.renameTo(toFile)) {
                throw new IOException("Failed to rename " + tmpFile + " to " + toFile);
            }
        }
        Slog.d(TAG, "Copied " + fromFiles.size() + " files into " + toDir);
    }

    private static void extractNativeLibraries(File packageDir, String abiOverride)
            throws PackageManagerException {
        // Always start from a clean slate
        final File libDir = new File(packageDir, NativeLibraryHelper.LIB_DIR_NAME);
        NativeLibraryHelper.removeNativeBinariesFromDirLI(libDir, true);

        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(packageDir);
            final int res = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libDir,
                    abiOverride);
            if (res != PackageManager.INSTALL_SUCCEEDED) {
                throw new PackageManagerException(res,
                        "Failed to extract native libraries, res=" + res);
            }
        } catch (IOException e) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Failed to extract native libraries", e);
        } finally {
            IoUtils.closeQuietly(handle);
        }
    }

    private static void resizeContainer(String cid, long targetSize)
            throws PackageManagerException {
        String path = PackageHelper.getSdDir(cid);
        if (path == null) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to find mounted " + cid);
        }

        final long currentSize = new File(path).getTotalSpace();
        if (currentSize > targetSize) {
            Slog.w(TAG, "Current size " + currentSize + " is larger than target size "
                    + targetSize + "; skipping resize");
            return;
        }

        if (!PackageHelper.unMountSdDir(cid)) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to unmount " + cid + " before resize");
        }

        if (!PackageHelper.resizeSdDir(targetSize, cid,
                PackageManagerService.getEncryptKey())) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to resize " + cid + " to " + targetSize + " bytes");
        }

        path = PackageHelper.mountSdDir(cid, PackageManagerService.getEncryptKey(),
                Process.SYSTEM_UID, false);
        if (path == null) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to mount " + cid + " after resize");
        }
    }

    private void finalizeAndFixContainer(String cid) throws PackageManagerException {
        if (!PackageHelper.finalizeSdDir(cid)) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to finalize container " + cid);
        }

        final int uid = mPm.getPackageUid(PackageManagerService.DEFAULT_CONTAINER_PACKAGE,
                UserHandle.USER_OWNER);
        final int gid = UserHandle.getSharedAppGid(uid);
        if (!PackageHelper.fixSdPermissions(cid, gid, null)) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to fix permissions on container " + cid);
        }
    }

    void setPermissionsResult(boolean accepted) {
        if (!mSealed) {
            throw new SecurityException("Must be sealed to accept permissions");
        }

        if (accepted) {
            // Mark and kick off another install pass
            synchronized (mLock) {
                mPermissionsAccepted = true;
            }
            mHandler.obtainMessage(MSG_COMMIT).sendToTarget();
        } else {
            destroyInternal();
            dispatchSessionFinished(INSTALL_FAILED_ABORTED, "User rejected permissions", null);
        }
    }

    public void open() throws IOException {
        if (mActiveCount.getAndIncrement() == 0) {
            mCallback.onSessionActiveChanged(this, true);
        }

        synchronized (mLock) {
            if (!mPrepared) {
                if (stageDir != null) {
                    prepareStageDir(stageDir);
                } else if (stageCid != null) {
                    prepareExternalStageCid(stageCid, params.sizeBytes);

                    // TODO: deliver more granular progress for ASEC allocation
                    mInternalProgress = 0.25f;
                    computeProgressLocked(true);
                } else {
                    throw new IllegalArgumentException(
                            "Exactly one of stageDir or stageCid stage must be set");
                }

                mPrepared = true;
                mCallback.onSessionPrepared(this);
            }
        }
    }

    @Override
    public void close() {
        if (mActiveCount.decrementAndGet() == 0) {
            mCallback.onSessionActiveChanged(this, false);
        }
    }

    @Override
    public void abandon() {
        if (mRelinquished) {
            Slog.d(TAG, "Ignoring abandon after commit relinquished control");
            return;
        }
        destroyInternal();
        dispatchSessionFinished(INSTALL_FAILED_ABORTED, "Session was abandoned", null);
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

            // Force shut down all bridges
            for (FileBridge bridge : mBridges) {
                bridge.forceClose();
            }
        }
        if (stageDir != null) {
            mPm.mInstaller.rmPackageDir(stageDir.getAbsolutePath());
        }
        if (stageCid != null) {
            PackageHelper.destroySdDir(stageCid);
        }
    }

    void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            dumpLocked(pw);
        }
    }

    private void dumpLocked(IndentingPrintWriter pw) {
        pw.println("Session " + sessionId + ":");
        pw.increaseIndent();

        pw.printPair("userId", userId);
        pw.printPair("installerPackageName", installerPackageName);
        pw.printPair("installerUid", installerUid);
        pw.printPair("createdMillis", createdMillis);
        pw.printPair("stageDir", stageDir);
        pw.printPair("stageCid", stageCid);
        pw.println();

        params.dump(pw);

        pw.printPair("mClientProgress", mClientProgress);
        pw.printPair("mProgress", mProgress);
        pw.printPair("mSealed", mSealed);
        pw.printPair("mPermissionsAccepted", mPermissionsAccepted);
        pw.printPair("mRelinquished", mRelinquished);
        pw.printPair("mDestroyed", mDestroyed);
        pw.printPair("mBridges", mBridges.size());
        pw.printPair("mFinalStatus", mFinalStatus);
        pw.printPair("mFinalMessage", mFinalMessage);
        pw.println();

        pw.decreaseIndent();
    }
}
