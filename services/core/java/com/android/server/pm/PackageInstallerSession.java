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
import static android.content.pm.PackageParser.APK_FILE_EXTENSION;
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
import static com.android.server.pm.PackageInstallerService.prepareStageDir;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
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
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Int64Ref;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.ExceptionUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.PackageInstallerService.PackageInstallObserverAdapter;

import android.content.pm.dex.DexMetadataHelper;
import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstaller";
    private static final boolean LOGD = true;
    private static final String REMOVE_SPLIT_MARKER_EXTENSION = ".removed";

    private static final int MSG_EARLY_BIND = 0;
    private static final int MSG_COMMIT = 1;
    private static final int MSG_ON_PACKAGE_INSTALLED = 2;

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

    private static final String PROPERTY_NAME_INHERIT_NATIVE = "pi.inherit_native_on_dont_kill";

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
    private long mVersionCode;
    @GuardedBy("mLock")
    private PackageParser.SigningDetails mSigningDetails;

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
    private final List<String> mResolvedNativeLibPaths = new ArrayList<>();
    @GuardedBy("mLock")
    private File mInheritedFilesBase;

    private static final FileFilter sAddedFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) return false;
            if (file.getName().endsWith(REMOVE_SPLIT_MARKER_EXTENSION)) return false;
            if (DexMetadataHelper.isDexMetadataFile(file)) return false;
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
                case MSG_EARLY_BIND:
                    earlyBindToDefContainer();
                    break;
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
                case MSG_ON_PACKAGE_INSTALLED:
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final String packageName = (String) args.arg1;
                    final String message = (String) args.arg2;
                    final Bundle extras = (Bundle) args.arg3;
                    final IPackageInstallObserver2 observer = (IPackageInstallObserver2) args.arg4;
                    final int returnCode = args.argi1;
                    args.recycle();

                    try {
                        observer.onPackageInstalled(packageName, returnCode, message, extras);
                    } catch (RemoteException ignored) {
                    }

                    break;
            }

            return true;
        }
    };

    private void earlyBindToDefContainer() {
        mPm.earlyBindToDefContainer();
    }

    /**
     * @return {@code true} iff the installing is app an device owner or affiliated profile owner.
     */
    @GuardedBy("mLock")
    private boolean isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked() {
        DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        return dpmi != null && dpmi.isActiveAdminWithPolicy(mInstallerUid,
                DeviceAdminInfo.USES_POLICY_PROFILE_OWNER) && dpmi.isUserAffiliatedWithDevice(
                userId);
    }

    /**
     * Checks if the permissions still need to be confirmed.
     *
     * <p>This is dependant on the identity of the installer, hence this cannot be cached if the
     * installer might still {@link #transfer(String) change}.
     *
     * @return {@code true} iff we need to ask to confirm the permissions?
     */
    @GuardedBy("mLock")
    private boolean needToAskForPermissionsLocked() {
        if (mPermissionsManuallyAccepted) {
            return false;
        }

        final boolean isInstallPermissionGranted =
                (mPm.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final boolean isSelfUpdatePermissionGranted =
                (mPm.checkUidPermission(android.Manifest.permission.INSTALL_SELF_UPDATES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final boolean isUpdatePermissionGranted =
                (mPm.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGE_UPDATES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final int targetPackageUid = mPm.getPackageUid(mPackageName, 0, userId);
        final boolean isPermissionGranted = isInstallPermissionGranted
                || (isUpdatePermissionGranted && targetPackageUid != -1)
                || (isSelfUpdatePermissionGranted && targetPackageUid == mInstallerUid);
        final boolean isInstallerRoot = (mInstallerUid == Process.ROOT_UID);
        final boolean isInstallerSystem = (mInstallerUid == Process.SYSTEM_UID);
        final boolean forcePermissionPrompt =
                (params.installFlags & PackageManager.INSTALL_FORCE_PERMISSION_PROMPT) != 0;

        // Device owners and affiliated profile owners  are allowed to silently install packages, so
        // the permission check is waived if the installer is the device owner.
        return forcePermissionPrompt || !(isPermissionGranted || isInstallerRoot
                || isInstallerSystem || isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked());
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
        // attempt to bind to the DefContainer as early as possible
        if ((params.installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_EARLY_BIND));
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

    @GuardedBy("mLock")
    private void assertPreparedAndNotSealedLocked(String cookie) {
        assertPreparedAndNotCommittedOrDestroyedLocked(cookie);
        if (mSealed) {
            throw new SecurityException(cookie + " not allowed after sealing");
        }
    }

    @GuardedBy("mLock")
    private void assertPreparedAndNotCommittedOrDestroyedLocked(String cookie) {
        assertPreparedAndNotDestroyedLocked(cookie);
        if (mCommitted) {
            throw new SecurityException(cookie + " not allowed after commit");
        }
    }

    @GuardedBy("mLock")
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
    @GuardedBy("mLock")
    private File resolveStageDirLocked() throws IOException {
        if (mResolvedStageDir == null) {
            if (stageDir != null) {
                mResolvedStageDir = stageDir;
            } else {
                throw new IOException("Missing stageDir");
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

    @GuardedBy("mLock")
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
            return doWriteInternal(name, offsetBytes, lengthBytes, null);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    @Override
    public void write(String name, long offsetBytes, long lengthBytes,
            ParcelFileDescriptor fd) {
        try {
            doWriteInternal(name, offsetBytes, lengthBytes, fd);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private ParcelFileDescriptor doWriteInternal(String name, long offsetBytes, long lengthBytes,
            ParcelFileDescriptor incomingFd) throws IOException {
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
            final FileDescriptor targetFd = Os.open(target.getAbsolutePath(),
                    O_CREAT | O_WRONLY, 0644);
            Os.chmod(target.getAbsolutePath(), 0644);

            // If caller specified a total length, allocate it for them. Free up
            // cache space to grow, if needed.
            if (stageDir != null && lengthBytes > 0) {
                mContext.getSystemService(StorageManager.class).allocateBytes(targetFd, lengthBytes,
                        PackageHelper.translateAllocateFlags(params.installFlags));
            }

            if (offsetBytes > 0) {
                Os.lseek(targetFd, offsetBytes, OsConstants.SEEK_SET);
            }

            if (incomingFd != null) {
                switch (Binder.getCallingUid()) {
                    case android.os.Process.SHELL_UID:
                    case android.os.Process.ROOT_UID:
                        break;
                    default:
                        throw new SecurityException("Reverse mode only supported from shell");
                }

                // In "reverse" mode, we're streaming data ourselves from the
                // incoming FD, which means we never have to hand out our
                // sensitive internal FD. We still rely on a "bridge" being
                // inserted above to hold the session active.
                try {
                    final Int64Ref last = new Int64Ref(0);
                    FileUtils.copy(incomingFd.getFileDescriptor(), targetFd, (long progress) -> {
                        if (params.sizeBytes > 0) {
                            final long delta = progress - last.value;
                            last.value = progress;
                            addClientProgress((float) delta / (float) params.sizeBytes);
                        }
                    }, null, lengthBytes);
                } finally {
                    IoUtils.closeQuietly(targetFd);
                    IoUtils.closeQuietly(incomingFd);

                    // We're done here, so remove the "bridge" that was holding
                    // the session active.
                    synchronized (mLock) {
                        if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                            mFds.remove(fd);
                        } else {
                            mBridges.remove(bridge);
                        }
                    }
                }
                return null;
            } else if (PackageInstaller.ENABLE_REVOCABLE_FD) {
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
            final FileDescriptor targetFd = Os.open(target.getAbsolutePath(), O_RDONLY, 0);
            return new ParcelFileDescriptor(targetFd);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Check if the caller is the owner of this session. Otherwise throw a
     * {@link SecurityException}.
     */
    @GuardedBy("mLock")
    private void assertCallerIsOwnerOrRootLocked() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != mInstallerUid) {
            throw new SecurityException("Session does not belong to uid " + callingUid);
        }
    }

    /**
     * If anybody is reading or writing data of the session, throw an {@link SecurityException}.
     */
    @GuardedBy("mLock")
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
                    mContext, statusReceiver, sessionId,
                    isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked(), userId);
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
                    // Do now throw an exception here to stay compatible with O and older
                    destroyInternal();
                    dispatchSessionFinished(e.error, ExceptionUtils.getCompleteMessage(e), null);
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
    @GuardedBy("mLock")
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

    @GuardedBy("mLock")
    private void commitLocked()
            throws PackageManagerException {
        if (mDestroyed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session destroyed");
        }
        if (!mSealed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session not sealed");
        }

        Preconditions.checkNotNull(mPackageName);
        Preconditions.checkNotNull(mSigningDetails);
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
                    // pre-create lib dirs for linking if necessary
                    if (!mResolvedNativeLibPaths.isEmpty()) {
                        for (String libPath : mResolvedNativeLibPaths) {
                            // "/lib/arm64" -> ["lib", "arm64"]
                            final int splitIndex = libPath.lastIndexOf('/');
                            if (splitIndex < 0 || splitIndex >= libPath.length() - 1) {
                                Slog.e(TAG, "Skipping native library creation for linking due to "
                                        + "invalid path: " + libPath);
                                continue;
                            }
                            final String libDirPath = libPath.substring(1, splitIndex);
                            final File libDir = new File(toDir, libDirPath);
                            if (!libDir.exists()) {
                                NativeLibraryHelper.createNativeLibrarySubdir(libDir);
                            }
                            final String archDirPath = libPath.substring(splitIndex + 1);
                            NativeLibraryHelper.createNativeLibrarySubdir(
                                    new File(libDir, archDirPath));
                        }
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
        extractNativeLibraries(mResolvedStageDir, params.abiOverride, mayInheritNativeLibs());

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
        mPm.installStage(mPackageName, stageDir, localObserver, params,
                mInstallerPackageName, mInstallerUid, user, mSigningDetails);
    }

    private static void maybeRenameFile(File from, File to) throws PackageManagerException {
        if (!from.equals(to)) {
            if (!from.renameTo(to)) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Could not rename file " + from + " to " + to);
            }
        }
    }

    /**
     * Returns true if the session should attempt to inherit any existing native libraries already
     * extracted at the current install location. This is necessary to prevent double loading of
     * native libraries already loaded by the running app.
     */
    private boolean mayInheritNativeLibs() {
        return SystemProperties.getBoolean(PROPERTY_NAME_INHERIT_NATIVE, true) &&
                params.mode == SessionParams.MODE_INHERIT_EXISTING &&
                (params.installFlags & PackageManager.DONT_KILL_APP) != 0;
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
    @GuardedBy("mLock")
    private void validateInstallLocked(@Nullable PackageInfo pkgInfo)
            throws PackageManagerException {
        mPackageName = null;
        mVersionCode = -1;
        mSigningDetails = PackageParser.SigningDetails.UNKNOWN;

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
                apk = PackageParser.parseApkLite(
                        addedFile, PackageParser.PARSE_COLLECT_CERTIFICATES);
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
                mVersionCode = apk.getLongVersionCode();
            }
            if (mSigningDetails == PackageParser.SigningDetails.UNKNOWN) {
                mSigningDetails = apk.signingDetails;
            }

            assertApkConsistentLocked(String.valueOf(addedFile), apk);

            // Take this opportunity to enforce uniform naming
            final String targetName;
            if (apk.splitName == null) {
                targetName = "base" + APK_FILE_EXTENSION;
            } else {
                targetName = "split_" + apk.splitName + APK_FILE_EXTENSION;
            }
            if (!FileUtils.isValidExtFilename(targetName)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Invalid filename: " + targetName);
            }

            final File targetFile = new File(mResolvedStageDir, targetName);
            maybeRenameFile(addedFile, targetFile);

            // Base is coming from session
            if (apk.splitName == null) {
                mResolvedBaseFile = targetFile;
            }

            mResolvedStagedFiles.add(targetFile);

            final File dexMetadataFile = DexMetadataHelper.findDexMetadataForFile(addedFile);
            if (dexMetadataFile != null) {
                if (!FileUtils.isValidExtFilename(dexMetadataFile.getName())) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Invalid filename: " + dexMetadataFile);
                }
                final File targetDexMetadataFile = new File(mResolvedStageDir,
                        DexMetadataHelper.buildDexMetadataPathForApk(targetName));
                mResolvedStagedFiles.add(targetDexMetadataFile);
                maybeRenameFile(dexMetadataFile, targetDexMetadataFile);
            }
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
                mVersionCode = pkgInfo.getLongVersionCode();
            }
            if (mSigningDetails == PackageParser.SigningDetails.UNKNOWN) {
                try {
                    mSigningDetails = ApkSignatureVerifier.plsCertsNoVerifyOnlyCerts(
                            pkgInfo.applicationInfo.sourceDir,
                            PackageParser.SigningDetails.SignatureSchemeVersion.JAR);
                } catch (PackageParserException e) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Couldn't obtain signatures from base APK");
                }
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
                // Inherit the dex metadata if present.
                final File baseDexMetadataFile =
                        DexMetadataHelper.findDexMetadataForFile(mResolvedBaseFile);
                if (baseDexMetadataFile != null) {
                    mResolvedInheritedFiles.add(baseDexMetadataFile);
                }
            }

            // Inherit splits if not overridden
            if (!ArrayUtils.isEmpty(existing.splitNames)) {
                for (int i = 0; i < existing.splitNames.length; i++) {
                    final String splitName = existing.splitNames[i];
                    final File splitFile = new File(existing.splitCodePaths[i]);
                    final boolean splitRemoved = removeSplitList.contains(splitName);
                    if (!stagedSplits.contains(splitName) && !splitRemoved) {
                        mResolvedInheritedFiles.add(splitFile);
                        // Inherit the dex metadata if present.
                        final File splitDexMetadataFile =
                                DexMetadataHelper.findDexMetadataForFile(splitFile);
                        if (splitDexMetadataFile != null) {
                            mResolvedInheritedFiles.add(splitDexMetadataFile);
                        }
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
                        if (!oatFiles.isEmpty()) {
                            mResolvedInheritedFiles.addAll(oatFiles);
                        }
                    }
                }
            }

            // Inherit native libraries for DONT_KILL sessions.
            if (mayInheritNativeLibs() && removeSplitList.isEmpty()) {
                File[] libDirs = new File[]{
                        new File(packageInstallDir, NativeLibraryHelper.LIB_DIR_NAME),
                        new File(packageInstallDir, NativeLibraryHelper.LIB64_DIR_NAME)};
                for (File libDir : libDirs) {
                    if (!libDir.exists() || !libDir.isDirectory()) {
                        continue;
                    }
                    final List<File> libDirsToInherit = new LinkedList<>();
                    for (File archSubDir : libDir.listFiles()) {
                        if (!archSubDir.isDirectory()) {
                            continue;
                        }
                        String relLibPath;
                        try {
                            relLibPath = getRelativePath(archSubDir, packageInstallDir);
                        } catch (IOException e) {
                            Slog.e(TAG, "Skipping linking of native library directory!", e);
                            // shouldn't be possible, but let's avoid inheriting these to be safe
                            libDirsToInherit.clear();
                            break;
                        }
                        if (!mResolvedNativeLibPaths.contains(relLibPath)) {
                            mResolvedNativeLibPaths.add(relLibPath);
                        }
                        libDirsToInherit.addAll(Arrays.asList(archSubDir.listFiles()));
                    }
                    mResolvedInheritedFiles.addAll(libDirsToInherit);
                }
            }
        }
    }

    @GuardedBy("mLock")
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
        if (mVersionCode != apk.getLongVersionCode()) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag
                    + " version code " + apk.versionCode + " inconsistent with "
                    + mVersionCode);
        }
        if (!mSigningDetails.signaturesMatchExactly(apk.signingDetails)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    tag + " signatures are inconsistent");
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

    private static void extractNativeLibraries(File packageDir, String abiOverride, boolean inherit)
            throws PackageManagerException {
        final File libDir = new File(packageDir, NativeLibraryHelper.LIB_DIR_NAME);
        if (!inherit) {
            // Start from a clean slate
            NativeLibraryHelper.removeNativeBinariesFromDirLI(libDir, true);
        }

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
                } else {
                    throw new IllegalArgumentException("stageDir must be set");
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
            // Execute observer.onPackageInstalled on different tread as we don't want callers
            // inside the system server have to worry about catching the callbacks while they are
            // calling into the session
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = packageName;
            args.arg2 = msg;
            args.arg3 = extras;
            args.arg4 = observer;
            args.argi1 = returnCode;

            mHandler.obtainMessage(MSG_ON_PACKAGE_INSTALLED, args).sendToTarget();
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
    }

    void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            dumpLocked(pw);
        }
    }

    @GuardedBy("mLock")
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

            writeGrantedRuntimePermissionsLocked(out, params.grantedRuntimePermissions);

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
        params.installReason = readIntAttribute(in, ATTR_INSTALL_REASON);

        params.grantedRuntimePermissions = readGrantedRuntimePermissions(in);

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
