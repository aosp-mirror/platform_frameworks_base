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

import static com.android.internal.util.XmlUtils.readBitmapAttribute;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.readUriAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.internal.util.XmlUtils.writeUriAttribute;
import static com.android.server.pm.PackageInstallerService.prepareExternalStageCid;
import static com.android.server.pm.PackageInstallerService.prepareStageDir;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteException;
import android.os.RevocableFileDescriptor;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.MathUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.PackageInstallerService.PackageInstallObserverAdapter;

import libcore.io.IoUtils;
import libcore.io.Libcore;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = true;
    private static final String REMOVE_SPLIT_MARKER_EXTENSION = ".removed";

    private static final int MSG_COMMIT = 0;
    private static final int MSG_SESSION_FINISHED_WITH_EXCEPTION = 1;

    /** XML constants used for persisting a session */
    static final String TAG_SESSION = "session";
    private static final String TAG_GRANTED_RUNTIME_PERMISSION = "granted-runtime-permission";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_INSTALLER_UID = "installerUid";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SESSION_STAGE_CID = "sessionStageCid";
    private static final String ATTR_PREPARED = "prepared";
    private static final String ATTR_SEALED = "sealed";
    private static final String ATTR_MODE = "mode";
    private static final String ATTR_INSTALL_FLAGS = "installFlags";
    private static final String ATTR_INSTALL_LOCATION = "installLocation";
    private static final String ATTR_SIZE_BYTES = "sizeBytes";
    private static final String ATTR_APP_PACKAGE_NAME = "appPackageName";
    @Deprecated
    private static final String ATTR_APP_ICON = "appIcon";
    private static final String ATTR_APP_LABEL = "appLabel";
    private static final String ATTR_ORIGINATING_URI = "originatingUri";
    private static final String ATTR_ORIGINATING_UID = "originatingUid";
    private static final String ATTR_REFERRER_URI = "referrerUri";
    private static final String ATTR_ABI_OVERRIDE = "abiOverride";
    private static final String ATTR_VOLUME_UUID = "volumeUuid";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_INSTALL_REASON = "installRason";

    // TODO: enforce INSTALL_ALLOW_TEST
    // TODO: enforce INSTALL_ALLOW_DOWNGRADE

    private final PackageInstallerService.InternalCallback mCallback;
    private final Context mContext;
    private final PackageManagerService mPm;
    private final Handler mHandler;

    final int sessionId;
    final int userId;
    final SessionParams params;
    final long createdMillis;
    final int defaultContainerGid;

    /** Staging location where client data is written. */
    final File stageDir;
    final String stageCid;

    private final AtomicInteger mActiveCount = new AtomicInteger();

    private final Object mLock = new Object();

    /** Uid of the creator of this session. */
    private final int mOriginalInstallerUid;

    /** Package of the owner of the installer session */
    @GuardedBy("mLock")
    private String mInstallerPackageName;

    /** Uid of the owner of the installer session */
    @GuardedBy("mLock")
    private int mInstallerUid;

    @GuardedBy("mLock")
    private float mClientProgress = 0;
    @GuardedBy("mLock")
    private float mInternalProgress = 0;

    @GuardedBy("mLock")
    private float mProgress = 0;
    @GuardedBy("mLock")
    private float mReportedProgress = -1;

    /** State of the session. */
    @GuardedBy("mLock")
    private boolean mPrepared = false;
    @GuardedBy("mLock")
    private boolean mSealed = false;
    @GuardedBy("mLock")
    private boolean mCommitted = false;
    @GuardedBy("mLock")
    private boolean mRelinquished = false;
    @GuardedBy("mLock")
    private boolean mDestroyed = false;

    /** Permissions have been accepted by the user (see {@link #setPermissionsResult}) */
    @GuardedBy("mLock")
    private boolean mPermissionsManuallyAccepted = false;

    @GuardedBy("mLock")
    private int mFinalStatus;
    @GuardedBy("mLock")
    private String mFinalMessage;

    @GuardedBy("mLock")
    private final ArrayList<RevocableFileDescriptor> mFds = new ArrayList<>();
    @GuardedBy("mLock")
    private final ArrayList<FileBridge> mBridges = new ArrayList<>();

    @GuardedBy("mLock")
    private IPackageInstallObserver2 mRemoteObserver;

    /** Fields derived from commit parsing */
    @GuardedBy("mLock")
    private String mPackageName;
    @GuardedBy("mLock")
    private int mVersionCode;
    @GuardedBy("mLock")
    private Signature[] mSignatures;
    @GuardedBy("mLock")
    private Certificate[][] mCertificates;

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

    private static final FileFilter sAddedFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) return false;
            if (file.getName().endsWith(REMOVE_SPLIT_MARKER_EXTENSION)) return false;
            return true;
        }
    };
    private static final FileFilter sRemovedFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            if (file.isDirectory()) return false;
            if (!file.getName().endsWith(REMOVE_SPLIT_MARKER_EXTENSION)) return false;
            return true;
        }
    };

    private final Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_COMMIT:
                    synchronized (mLock) {
                        try {
                            commitLocked();
                        } catch (PackageManagerException e) {
                            final String completeMsg = ExceptionUtils.getCompleteMessage(e);
                            Slog.e(TAG,
                                    "Commit of session " + sessionId + " failed: " + completeMsg);
                            destroyInternal();
                            dispatchSessionFinished(e.error, completeMsg, null);
                        }
                    }

                    break;
                case MSG_SESSION_FINISHED_WITH_EXCEPTION:
                    PackageManagerException e = (PackageManagerException) msg.obj;

                    dispatchSessionFinished(e.error, ExceptionUtils.getCompleteMessage(e),
                            null);
                    break;
            }

            return true;
        }
    };

    /**
     * @return {@code true} iff the installing is app an device owner?
     */
    private boolean isInstallerDeviceOwnerLocked() {
        DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        return (dpm != null) && dpm.isDeviceOwnerAppOnCallingUser(
                mInstallerPackageName);
    }

    /**
     * Checks if the permissions still need to be confirmed.
     *
     * <p>This is dependant on the identity of the installer, hence this cannot be cached if the
     * installer might still {@link #transfer(String) change}.
     *
     * @return {@code true} iff we need to ask to confirm the permissions?
     */
    private boolean needToAskForPermissionsLocked() {
        if (mPermissionsManuallyAccepted) {
            return false;
        }

        final boolean isPermissionGranted =
                (mPm.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final boolean isInstallerRoot = (mInstallerUid == Process.ROOT_UID);
        final boolean forcePermissionPrompt =
                (params.installFlags & PackageManager.INSTALL_FORCE_PERMISSION_PROMPT) != 0;

        // Device owners are allowed to silently install packages, so the permission check is
        // waived if the installer is the device owner.
        return forcePermissionPrompt || !(isPermissionGranted || isInstallerRoot
                || isInstallerDeviceOwnerLocked());
    }

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
        mOriginalInstallerUid = installerUid;
        mInstallerPackageName = installerPackageName;
        mInstallerUid = installerUid;
        this.params = params;
        this.createdMillis = createdMillis;
        this.stageDir = stageDir;
        this.stageCid = stageCid;

        if ((stageDir == null) == (stageCid == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of stageDir or stageCid stage must be set");
        }

        mPrepared = prepared;

        if (sealed) {
            synchronized (mLock) {
                try {
                    sealAndValidateLocked();
                } catch (PackageManagerException | IOException e) {
                    destroyInternal();
                    throw new IllegalArgumentException(e);
                }
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final int uid = mPm.getPackageUid(PackageManagerService.DEFAULT_CONTAINER_PACKAGE,
                    PackageManager.MATCH_SYSTEM_ONLY, UserHandle.USER_SYSTEM);
            defaultContainerGid = UserHandle.getSharedAppGid(uid);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public SessionInfo generateInfo() {
        return generateInfo(true);
    }

    public SessionInfo generateInfo(boolean includeIcon) {
        final SessionInfo info = new SessionInfo();
        synchronized (mLock) {
            info.sessionId = sessionId;
            info.installerPackageName = mInstallerPackageName;
            info.resolvedBaseCodePath = (mResolvedBaseFile != null) ?
                    mResolvedBaseFile.getAbsolutePath() : null;
            info.progress = mProgress;
            info.sealed = mSealed;
            info.active = mActiveCount.get() > 0;

            info.mode = params.mode;
            info.installReason = params.installReason;
            info.sizeBytes = params.sizeBytes;
            info.appPackageName = params.appPackageName;
            if (includeIcon) {
                info.appIcon = params.appIcon;
            }
            info.appLabel = params.appLabel;

            info.installLocation = params.installLocation;
            info.originatingUri = params.originatingUri;
            info.originatingUid = params.originatingUid;
            info.referrerUri = params.referrerUri;
            info.grantedRuntimePermissions = params.grantedRuntimePermissions;
            info.installFlags = params.installFlags;
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

    private void assertPreparedAndNotSealedLocked(String cookie) {
        assertPreparedAndNotCommittedOrDestroyedLocked(cookie);
        if (mSealed) {
            throw new SecurityException(cookie + " not allowed after sealing");
        }
    }

    private void assertPreparedAndNotCommittedOrDestroyedLocked(String cookie) {
        assertPreparedAndNotDestroyedLocked(cookie);
        if (mCommitted) {
            throw new SecurityException(cookie + " not allowed after commit");
        }
    }

    private void assertPreparedAndNotDestroyedLocked(String cookie) {
        if (!mPrepared) {
            throw new IllegalStateException(cookie + " before prepared");
        }
        if (mDestroyed) {
            throw new SecurityException(cookie + " not allowed after destruction");
        }
    }

    /**
     * Resolve the actual location where staged data should be written. This
     * might point at an ASEC mount point, which is why we delay path resolution
     * until someone actively works with the session.
     */
    private File resolveStageDirLocked() throws IOException {
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

    @Override
    public void setClientProgress(float progress) {
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();

            // Always publish first staging movement
            final boolean forcePublish = (mClientProgress == 0);
            mClientProgress = progress;
            computeProgressLocked(forcePublish);
        }
    }

    @Override
    public void addClientProgress(float progress) {
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();

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
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotCommittedOrDestroyedLocked("getNames");

            try {
                return resolveStageDirLocked().list();
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    @Override
    public void removeSplit(String splitName) {
        if (TextUtils.isEmpty(params.appPackageName)) {
            throw new IllegalStateException("Must specify package name to remove a split");
        }

        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotCommittedOrDestroyedLocked("removeSplit");

            try {
                createRemoveSplitMarkerLocked(splitName);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    private void createRemoveSplitMarkerLocked(String splitName) throws IOException {
        try {
            final String markerName = splitName + REMOVE_SPLIT_MARKER_EXTENSION;
            if (!FileUtils.isValidExtFilename(markerName)) {
                throw new IllegalArgumentException("Invalid marker: " + markerName);
            }
            final File target = new File(resolveStageDirLocked(), markerName);
            target.createNewFile();
            Os.chmod(target.getAbsolutePath(), 0 /*mode*/);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
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
        final RevocableFileDescriptor fd;
        final FileBridge bridge;
        final File stageDir;
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("openWrite");

            if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                fd = new RevocableFileDescriptor();
                bridge = null;
                mFds.add(fd);
            } else {
                fd = null;
                bridge = new FileBridge();
                mBridges.add(bridge);
            }

            stageDir = resolveStageDirLocked();
        }

        try {
            // Use installer provided name for now; we always rename later
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            final File target;
            final long identity = Binder.clearCallingIdentity();
            try {
                target = new File(stageDir, name);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

            // TODO: this should delegate to DCS so the system process avoids
            // holding open FDs into containers.
            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(),
                    O_CREAT | O_WRONLY, 0644);
            Os.chmod(target.getAbsolutePath(), 0644);

            // If caller specified a total length, allocate it for them. Free up
            // cache space to grow, if needed.
            if (stageDir != null && lengthBytes > 0) {
                mContext.getSystemService(StorageManager.class).allocateBytes(targetFd, lengthBytes,
                        PackageHelper.translateAllocateFlags(params.installFlags));
            }

            if (offsetBytes > 0) {
                Libcore.os.lseek(targetFd, offsetBytes, OsConstants.SEEK_SET);
            }

            if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                fd.init(mContext, targetFd);
                return fd.getRevocableFileDescriptor();
            } else {
                bridge.setTargetFile(targetFd);
                bridge.start();
                return new ParcelFileDescriptor(bridge.getClientSocket());
            }

        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    @Override
    public ParcelFileDescriptor openRead(String name) {
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotCommittedOrDestroyedLocked("openRead");
            try {
                return openReadInternalLocked(name);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    private ParcelFileDescriptor openReadInternalLocked(String name) throws IOException {
        try {
            if (!FileUtils.isValidExtFilename(name)) {
                throw new IllegalArgumentException("Invalid name: " + name);
            }
            final File target = new File(resolveStageDirLocked(), name);
            final FileDescriptor targetFd = Libcore.os.open(target.getAbsolutePath(), O_RDONLY, 0);
            return new ParcelFileDescriptor(targetFd);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Check if the caller is the owner of this session. Otherwise throw a
     * {@link SecurityException}.
     */
    private void assertCallerIsOwnerOrRootLocked() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != mInstallerUid) {
            throw new SecurityException("Session does not belong to uid " + callingUid);
        }
    }

    /**
     * If anybody is reading or writing data of the session, throw an {@link SecurityException}.
     */
    private void assertNoWriteFileTransfersOpenLocked() {
        // Verify that all writers are hands-off
        for (RevocableFileDescriptor fd : mFds) {
            if (!fd.isRevoked()) {
                throw new SecurityException("Files still open");
            }
        }
        for (FileBridge bridge : mBridges) {
            if (!bridge.isClosed()) {
                throw new SecurityException("Files still open");
            }
        }
    }

    @Override
    public void commit(@NonNull IntentSender statusReceiver, boolean forTransfer) {
        Preconditions.checkNotNull(statusReceiver);

        final boolean wasSealed;
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotDestroyedLocked("commit");

            final PackageInstallObserverAdapter adapter = new PackageInstallObserverAdapter(
                    mContext, statusReceiver, sessionId, isInstallerDeviceOwnerLocked(), userId);
            mRemoteObserver = adapter.getBinder();

            if (forTransfer) {
                mContext.enforceCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES, null);

                if (mInstallerUid == mOriginalInstallerUid) {
                    throw new IllegalArgumentException("Session has not been transferred");
                }
            } else {
                if (mInstallerUid != mOriginalInstallerUid) {
                    throw new IllegalArgumentException("Session has been transferred");
                }
            }

            wasSealed = mSealed;
            if (!mSealed) {
                try {
                    sealAndValidateLocked();
                } catch (IOException e) {
                    throw new IllegalArgumentException(e);
                } catch (PackageManagerException e) {
                    destroyInternal();

                    // Cannot call dispatchFinal synchronous as this might be called from inside the
                    // system server on the main thread. Hence the call back scheduled in
                    // dispachFinal has to be scheduled on a different thread.
                    mHandler.obtainMessage(MSG_SESSION_FINISHED_WITH_EXCEPTION, e).sendToTarget();

                    return;
                }
            }

            // Client staging is fully done at this point
            mClientProgress = 1f;
            computeProgressLocked(true);

            // This ongoing commit should keep session active, even though client
            // will probably close their end.
            mActiveCount.incrementAndGet();

            mCommitted = true;
            mHandler.obtainMessage(MSG_COMMIT).sendToTarget();
        }

        if (!wasSealed) {
            // Persist the fact that we've sealed ourselves to prevent
            // mutations of any hard links we create. We do this without holding
            // the session lock, since otherwise it's a lock inversion.
            mCallback.onSessionSealedBlocking(this);
        }
    }

    /**
     * Seal the session to prevent further modification and validate the contents of it.
     *
     * <p>The session will be sealed after calling this method even if it failed.
     *
     * @throws PackageManagerException if the session was sealed but something went wrong. If the
     *                                 session was sealed this is the only possible exception.
     */
    private void sealAndValidateLocked() throws PackageManagerException, IOException {
        assertNoWriteFileTransfersOpenLocked();
        assertPreparedAndNotDestroyedLocked("sealing of session");

        final PackageInfo pkgInfo = mPm.getPackageInfo(
                params.appPackageName, PackageManager.GET_SIGNATURES
                        | PackageManager.MATCH_STATIC_SHARED_LIBRARIES /*flags*/, userId);

        resolveStageDirLocked();

        mSealed = true;

        // Verify that stage looks sane with respect to existing application.
        // This currently only ensures packageName, versionCode, and certificate
        // consistency.
        try {
            validateInstallLocked(pkgInfo);
        } catch (PackageManagerException e) {
            throw e;
        } catch (Throwable e) {
            // Convert all exceptions into package manager exceptions as only those are handled
            // in the code above
            throw new PackageManagerException(e);
        }

        // Read transfers from the original owner stay open, but as the session's data
        // cannot be modified anymore, there is no leak of information.
    }

    @Override
    public void transfer(String packageName) {
        Preconditions.checkNotNull(packageName);

        ApplicationInfo newOwnerAppInfo = mPm.getApplicationInfo(packageName, 0, userId);
        if (newOwnerAppInfo == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException(packageName));
        }

        if (PackageManager.PERMISSION_GRANTED != mPm.checkUidPermission(
                Manifest.permission.INSTALL_PACKAGES, newOwnerAppInfo.uid)) {
            throw new SecurityException("Destination package " + packageName + " does not have "
                    + "the " + Manifest.permission.INSTALL_PACKAGES + " permission");
        }

        // Only install flags that can be verified by the app the session is transferred to are
        // allowed. The parameters can be read via PackageInstaller.SessionInfo.
        if (!params.areHiddenOptionsSet()) {
            throw new SecurityException("Can only transfer sessions that use public options");
        }

        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("transfer");

            try {
                sealAndValidateLocked();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (PackageManagerException e) {
                // Session is sealed but could not be verified, we need to destroy it
                destroyInternal();
                dispatchSessionFinished(e.error, ExceptionUtils.getCompleteMessage(e), null);

                throw new IllegalArgumentException("Package is not valid", e);
            }

            if (!mPackageName.equals(mInstallerPackageName)) {
                throw new SecurityException("Can only transfer sessions that update the original "
                        + "installer");
            }

            mInstallerPackageName = packageName;
            mInstallerUid = newOwnerAppInfo.uid;
        }

        // Persist the fact that we've sealed ourselves to prevent
        // mutations of any hard links we create. We do this without holding
        // the session lock, since otherwise it's a lock inversion.
        mCallback.onSessionSealedBlocking(this);
    }

    private void commitLocked()
            throws PackageManagerException {
        if (mDestroyed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session destroyed");
        }
        if (!mSealed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session not sealed");
        }

        Preconditions.checkNotNull(mPackageName);
        Preconditions.checkNotNull(mSignatures);
        Preconditions.checkNotNull(mResolvedBaseFile);

        if (needToAskForPermissionsLocked()) {
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
            closeInternal(false);
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
                final File toDir = resolveStageDirLocked();

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
                mInstallerPackageName, mInstallerUid, user, mCertificates);
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
    private void validateInstallLocked(@Nullable PackageInfo pkgInfo)
            throws PackageManagerException {
        mPackageName = null;
        mVersionCode = -1;
        mSignatures = null;

        mResolvedBaseFile = null;
        mResolvedStagedFiles.clear();
        mResolvedInheritedFiles.clear();

        try {
            resolveStageDirLocked();
        } catch (IOException e) {
            throw new PackageManagerException(INSTALL_FAILED_CONTAINER_ERROR,
                    "Failed to resolve stage location", e);
        }

        final File[] removedFiles = mResolvedStageDir.listFiles(sRemovedFilter);
        final List<String> removeSplitList = new ArrayList<>();
        if (!ArrayUtils.isEmpty(removedFiles)) {
            for (File removedFile : removedFiles) {
                final String fileName = removedFile.getName();
                final String splitName = fileName.substring(
                        0, fileName.length() - REMOVE_SPLIT_MARKER_EXTENSION.length());
                removeSplitList.add(splitName);
            }
        }

        final File[] addedFiles = mResolvedStageDir.listFiles(sAddedFilter);
        if (ArrayUtils.isEmpty(addedFiles) && removeSplitList.size() == 0) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, "No packages staged");
        }
        // Verify that all staged packages are internally consistent
        final ArraySet<String> stagedSplits = new ArraySet<>();
        for (File addedFile : addedFiles) {
            final ApkLite apk;
            try {
                int flags = PackageParser.PARSE_COLLECT_CERTIFICATES;
                if ((params.installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
                    flags |= PackageParser.PARSE_IS_EPHEMERAL;
                }
                apk = PackageParser.parseApkLite(addedFile, flags);
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
                mCertificates = apk.certificates;
            }

            assertApkConsistentLocked(String.valueOf(addedFile), apk);

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
            if (!addedFile.equals(targetFile)) {
                addedFile.renameTo(targetFile);
            }

            // Base is coming from session
            if (apk.splitName == null) {
                mResolvedBaseFile = targetFile;
            }

            mResolvedStagedFiles.add(targetFile);
        }

        if (removeSplitList.size() > 0) {
            if (pkgInfo == null) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Missing existing base package for " + mPackageName);
            }

            // validate split names marked for removal
            for (String splitName : removeSplitList) {
                if (!ArrayUtils.contains(pkgInfo.splitNames, splitName)) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Split not found: " + splitName);
                }
            }

            // ensure we've got appropriate package name, version code and signatures
            if (mPackageName == null) {
                mPackageName = pkgInfo.packageName;
                mVersionCode = pkgInfo.versionCode;
            }
            if (mSignatures == null) {
                mSignatures = pkgInfo.signatures;
            }
        }

        if (params.mode == SessionParams.MODE_FULL_INSTALL) {
            // Full installs must include a base package
            if (!stagedSplits.contains(null)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Full install must include a base package");
            }

        } else {
            // Partial installs must be consistent with existing install
            if (pkgInfo == null || pkgInfo.applicationInfo == null) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Missing existing base package for " + mPackageName);
            }

            final PackageLite existing;
            final ApkLite existingBase;
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            try {
                existing = PackageParser.parsePackageLite(new File(appInfo.getCodePath()), 0);
                existingBase = PackageParser.parseApkLite(new File(appInfo.getBaseCodePath()),
                        PackageParser.PARSE_COLLECT_CERTIFICATES);
            } catch (PackageParserException e) {
                throw PackageManagerException.from(e);
            }

            assertApkConsistentLocked("Existing base", existingBase);

            // Inherit base if not overridden
            if (mResolvedBaseFile == null) {
                mResolvedBaseFile = new File(appInfo.getBaseCodePath());
                mResolvedInheritedFiles.add(mResolvedBaseFile);
            }

            // Inherit splits if not overridden
            if (!ArrayUtils.isEmpty(existing.splitNames)) {
                for (int i = 0; i < existing.splitNames.length; i++) {
                    final String splitName = existing.splitNames[i];
                    final File splitFile = new File(existing.splitCodePaths[i]);
                    final boolean splitRemoved = removeSplitList.contains(splitName);
                    if (!stagedSplits.contains(splitName) && !splitRemoved) {
                        mResolvedInheritedFiles.add(splitFile);
                    }
                }
            }

            // Inherit compiled oat directory.
            final File packageInstallDir = (new File(appInfo.getBaseCodePath())).getParentFile();
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

                        // Only add compiled files associated with the base.
                        // Once b/62269291 is resolved, we can add all compiled files again.
                        for (File oatFile : oatFiles) {
                            if (oatFile.getName().equals("base.art")
                                    || oatFile.getName().equals("base.odex")
                                    || oatFile.getName().equals("base.vdex")) {
                                mResolvedInheritedFiles.add(oatFile);
                            }
                        }
                    }
                }
            }
        }
    }

    private void assertApkConsistentLocked(String tag, ApkLite apk)
            throws PackageManagerException {
        if (!mPackageName.equals(apk.packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag + " package "
                    + apk.packageName + " inconsistent with " + mPackageName);
        }
        if (params.appPackageName != null && !params.appPackageName.equals(apk.packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag
                    + " specified package " + params.appPackageName
                    + " inconsistent with " + apk.packageName);
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
        final PackageLite pkg = new PackageLite(null, baseApk, null, null, null, null,
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

    /**
     * @return the uid of the owner this session
     */
    public int getInstallerUid() {
        synchronized (mLock) {
            return mInstallerUid;
        }
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

    private void createOatDirs(List<String> instructionSets, File fromDir)
            throws PackageManagerException {
        for (String instructionSet : instructionSets) {
            try {
                mPm.mInstaller.createOatDir(fromDir.getAbsolutePath(), instructionSet);
            } catch (InstallerException e) {
                throw PackageManagerException.from(e);
            }
        }
    }

    private void linkFiles(List<File> fromFiles, File toDir, File fromDir)
            throws IOException {
        for (File fromFile : fromFiles) {
            final String relativePath = getRelativePath(fromFile, fromDir);
            try {
                mPm.mInstaller.linkFile(relativePath, fromDir.getAbsolutePath(),
                        toDir.getAbsolutePath());
            } catch (InstallerException e) {
                throw new IOException("failed linkOrCreateDir(" + relativePath + ", "
                        + fromDir + ", " + toDir + ")", e);
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

        if (!PackageHelper.fixSdPermissions(cid, defaultContainerGid, null)) {
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
                mPermissionsManuallyAccepted = true;
                mHandler.obtainMessage(MSG_COMMIT).sendToTarget();
            }
        } else {
            destroyInternal();
            dispatchSessionFinished(INSTALL_FAILED_ABORTED, "User rejected permissions", null);
        }
    }

    public void open() throws IOException {
        if (mActiveCount.getAndIncrement() == 0) {
            mCallback.onSessionActiveChanged(this, true);
        }

        boolean wasPrepared;
        synchronized (mLock) {
            wasPrepared = mPrepared;
            if (!mPrepared) {
                if (stageDir != null) {
                    prepareStageDir(stageDir);
                } else if (stageCid != null) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        prepareExternalStageCid(stageCid, params.sizeBytes);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    // TODO: deliver more granular progress for ASEC allocation
                    mInternalProgress = 0.25f;
                    computeProgressLocked(true);
                } else {
                    throw new IllegalArgumentException(
                            "Exactly one of stageDir or stageCid stage must be set");
                }

                mPrepared = true;
            }
        }

        if (!wasPrepared) {
            mCallback.onSessionPrepared(this);
        }
    }

    @Override
    public void close() {
        closeInternal(true);
    }

    private void closeInternal(boolean checkCaller) {
        int activeCount;
        synchronized (mLock) {
            if (checkCaller) {
                assertCallerIsOwnerOrRootLocked();
            }

            activeCount = mActiveCount.decrementAndGet();
        }

        if (activeCount == 0) {
            mCallback.onSessionActiveChanged(this, false);
        }
    }

    @Override
    public void abandon() {
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();

            if (mRelinquished) {
                Slog.d(TAG, "Ignoring abandon after commit relinquished control");
                return;
            }
            destroyInternal();
        }

        dispatchSessionFinished(INSTALL_FAILED_ABORTED, "Session was abandoned", null);
    }

    private void dispatchSessionFinished(int returnCode, String msg, Bundle extras) {
        final IPackageInstallObserver2 observer;
        final String packageName;
        synchronized (mLock) {
            mFinalStatus = returnCode;
            mFinalMessage = msg;

            observer = mRemoteObserver;
            packageName = mPackageName;
        }

        if (observer != null) {
            try {
                observer.onPackageInstalled(packageName, returnCode, msg, extras);
            } catch (RemoteException ignored) {
            }
        }

        final boolean success = (returnCode == PackageManager.INSTALL_SUCCEEDED);

        // Send broadcast to default launcher only if it's a new install
        final boolean isNewInstall = extras == null || !extras.getBoolean(Intent.EXTRA_REPLACING);
        if (success && isNewInstall) {
            mPm.sendSessionCommitBroadcast(generateInfo(), userId);
        }

        mCallback.onSessionFinished(this, success);
    }

    private void destroyInternal() {
        synchronized (mLock) {
            mSealed = true;
            mDestroyed = true;

            // Force shut down all bridges
            for (RevocableFileDescriptor fd : mFds) {
                fd.revoke();
            }
            for (FileBridge bridge : mBridges) {
                bridge.forceClose();
            }
        }
        if (stageDir != null) {
            try {
                mPm.mInstaller.rmPackageDir(stageDir.getAbsolutePath());
            } catch (InstallerException ignored) {
            }
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
        pw.printPair("mOriginalInstallerUid", mOriginalInstallerUid);
        pw.printPair("mInstallerPackageName", mInstallerPackageName);
        pw.printPair("mInstallerUid", mInstallerUid);
        pw.printPair("createdMillis", createdMillis);
        pw.printPair("stageDir", stageDir);
        pw.printPair("stageCid", stageCid);
        pw.println();

        params.dump(pw);

        pw.printPair("mClientProgress", mClientProgress);
        pw.printPair("mProgress", mProgress);
        pw.printPair("mSealed", mSealed);
        pw.printPair("mPermissionsManuallyAccepted", mPermissionsManuallyAccepted);
        pw.printPair("mRelinquished", mRelinquished);
        pw.printPair("mDestroyed", mDestroyed);
        pw.printPair("mFds", mFds.size());
        pw.printPair("mBridges", mBridges.size());
        pw.printPair("mFinalStatus", mFinalStatus);
        pw.printPair("mFinalMessage", mFinalMessage);
        pw.println();

        pw.decreaseIndent();
    }

    private static void writeGrantedRuntimePermissionsLocked(XmlSerializer out,
            String[] grantedRuntimePermissions) throws IOException {
        if (grantedRuntimePermissions != null) {
            for (String permission : grantedRuntimePermissions) {
                out.startTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
                writeStringAttribute(out, ATTR_NAME, permission);
                out.endTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
            }
        }
    }

    private static File buildAppIconFile(int sessionId, @NonNull File sessionsDir) {
        return new File(sessionsDir, "app_icon." + sessionId + ".png");
    }

    /**
     * Write this session to a {@link XmlSerializer}.
     *
     * @param out Where to write the session to
     * @param sessionsDir The directory containing the sessions
     */
    void write(@NonNull XmlSerializer out, @NonNull File sessionsDir) throws IOException {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }

            out.startTag(null, TAG_SESSION);

            writeIntAttribute(out, ATTR_SESSION_ID, sessionId);
            writeIntAttribute(out, ATTR_USER_ID, userId);
            writeStringAttribute(out, ATTR_INSTALLER_PACKAGE_NAME,
                    mInstallerPackageName);
            writeIntAttribute(out, ATTR_INSTALLER_UID, mInstallerUid);
            writeLongAttribute(out, ATTR_CREATED_MILLIS, createdMillis);
            if (stageDir != null) {
                writeStringAttribute(out, ATTR_SESSION_STAGE_DIR,
                        stageDir.getAbsolutePath());
            }
            if (stageCid != null) {
                writeStringAttribute(out, ATTR_SESSION_STAGE_CID, stageCid);
            }
            writeBooleanAttribute(out, ATTR_PREPARED, isPrepared());
            writeBooleanAttribute(out, ATTR_SEALED, isSealed());

            writeIntAttribute(out, ATTR_MODE, params.mode);
            writeIntAttribute(out, ATTR_INSTALL_FLAGS, params.installFlags);
            writeIntAttribute(out, ATTR_INSTALL_LOCATION, params.installLocation);
            writeLongAttribute(out, ATTR_SIZE_BYTES, params.sizeBytes);
            writeStringAttribute(out, ATTR_APP_PACKAGE_NAME, params.appPackageName);
            writeStringAttribute(out, ATTR_APP_LABEL, params.appLabel);
            writeUriAttribute(out, ATTR_ORIGINATING_URI, params.originatingUri);
            writeIntAttribute(out, ATTR_ORIGINATING_UID, params.originatingUid);
            writeUriAttribute(out, ATTR_REFERRER_URI, params.referrerUri);
            writeStringAttribute(out, ATTR_ABI_OVERRIDE, params.abiOverride);
            writeStringAttribute(out, ATTR_VOLUME_UUID, params.volumeUuid);
            writeIntAttribute(out, ATTR_INSTALL_REASON, params.installReason);

            // Persist app icon if changed since last written
            File appIconFile = buildAppIconFile(sessionId, sessionsDir);
            if (params.appIcon == null && appIconFile.exists()) {
                appIconFile.delete();
            } else if (params.appIcon != null
                    && appIconFile.lastModified() != params.appIconLastModified) {
                if (LOGD) Slog.w(TAG, "Writing changed icon " + appIconFile);
                FileOutputStream os = null;
                try {
                    os = new FileOutputStream(appIconFile);
                    params.appIcon.compress(Bitmap.CompressFormat.PNG, 90, os);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to write icon " + appIconFile + ": " + e.getMessage());
                } finally {
                    IoUtils.closeQuietly(os);
                }

                params.appIconLastModified = appIconFile.lastModified();
            }

            writeGrantedRuntimePermissionsLocked(out, params.grantedRuntimePermissions);
        }

        out.endTag(null, TAG_SESSION);
    }

    private static String[] readGrantedRuntimePermissions(XmlPullParser in)
            throws IOException, XmlPullParserException {
        List<String> permissions = null;

        final int outerDepth = in.getDepth();
        int type;
        while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || in.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (TAG_GRANTED_RUNTIME_PERMISSION.equals(in.getName())) {
                String permission = readStringAttribute(in, ATTR_NAME);
                if (permissions == null) {
                    permissions = new ArrayList<>();
                }
                permissions.add(permission);
            }
        }

        if (permissions == null) {
            return null;
        }

        String[] permissionsArray = new String[permissions.size()];
        permissions.toArray(permissionsArray);
        return permissionsArray;
    }

    /**
     * Read new session from a {@link XmlPullParser xml description} and create it.
     *
     * @param in The source of the description
     * @param callback Callback the session uses to notify about changes of it's state
     * @param context Context to be used by the session
     * @param pm PackageManager to use by the session
     * @param installerThread Thread to be used for callbacks of this session
     * @param sessionsDir The directory the sessions are stored in
     *
     * @return The newly created session
     */
    public static PackageInstallerSession readFromXml(@NonNull XmlPullParser in,
            @NonNull PackageInstallerService.InternalCallback callback, @NonNull Context context,
            @NonNull PackageManagerService pm, Looper installerThread, @NonNull File sessionsDir)
            throws IOException, XmlPullParserException {
        final int sessionId = readIntAttribute(in, ATTR_SESSION_ID);
        final int userId = readIntAttribute(in, ATTR_USER_ID);
        final String installerPackageName = readStringAttribute(in, ATTR_INSTALLER_PACKAGE_NAME);
        final int installerUid = readIntAttribute(in, ATTR_INSTALLER_UID, pm.getPackageUid(
                installerPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId));
        final long createdMillis = readLongAttribute(in, ATTR_CREATED_MILLIS);
        final String stageDirRaw = readStringAttribute(in, ATTR_SESSION_STAGE_DIR);
        final File stageDir = (stageDirRaw != null) ? new File(stageDirRaw) : null;
        final String stageCid = readStringAttribute(in, ATTR_SESSION_STAGE_CID);
        final boolean prepared = readBooleanAttribute(in, ATTR_PREPARED, true);
        final boolean sealed = readBooleanAttribute(in, ATTR_SEALED);

        final SessionParams params = new SessionParams(
                SessionParams.MODE_INVALID);
        params.mode = readIntAttribute(in, ATTR_MODE);
        params.installFlags = readIntAttribute(in, ATTR_INSTALL_FLAGS);
        params.installLocation = readIntAttribute(in, ATTR_INSTALL_LOCATION);
        params.sizeBytes = readLongAttribute(in, ATTR_SIZE_BYTES);
        params.appPackageName = readStringAttribute(in, ATTR_APP_PACKAGE_NAME);
        params.appIcon = readBitmapAttribute(in, ATTR_APP_ICON);
        params.appLabel = readStringAttribute(in, ATTR_APP_LABEL);
        params.originatingUri = readUriAttribute(in, ATTR_ORIGINATING_URI);
        params.originatingUid =
                readIntAttribute(in, ATTR_ORIGINATING_UID, SessionParams.UID_UNKNOWN);
        params.referrerUri = readUriAttribute(in, ATTR_REFERRER_URI);
        params.abiOverride = readStringAttribute(in, ATTR_ABI_OVERRIDE);
        params.volumeUuid = readStringAttribute(in, ATTR_VOLUME_UUID);
        params.grantedRuntimePermissions = readGrantedRuntimePermissions(in);
        params.installReason = readIntAttribute(in, ATTR_INSTALL_REASON);

        final File appIconFile = buildAppIconFile(sessionId, sessionsDir);
        if (appIconFile.exists()) {
            params.appIcon = BitmapFactory.decodeFile(appIconFile.getAbsolutePath());
            params.appIconLastModified = appIconFile.lastModified();
        }

        return new PackageInstallerSession(callback, context, pm,
                installerThread, sessionId, userId, installerPackageName, installerUid,
                params, createdMillis, stageDir, stageCid, prepared, sealed);
    }
}
