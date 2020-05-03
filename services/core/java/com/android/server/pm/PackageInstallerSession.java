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

import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.content.pm.DataLoaderType.INCREMENTAL;
import static android.content.pm.DataLoaderType.STREAMING;
import static android.content.pm.PackageInstaller.LOCATION_DATA_APP;
import static android.content.pm.PackageManager.INSTALL_FAILED_ABORTED;
import static android.content.pm.PackageManager.INSTALL_FAILED_BAD_SIGNATURE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SPLIT;
import static android.content.pm.PackageParser.APEX_FILE_EXTENSION;
import static android.content.pm.PackageParser.APK_FILE_EXTENSION;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_WRONLY;

import static com.android.internal.util.XmlUtils.readBitmapAttribute;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readByteArrayAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.readUriAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeByteArrayAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.internal.util.XmlUtils.writeUriAttribute;
import static com.android.server.pm.PackageInstallerService.prepareStageDir;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.DataLoaderManager;
import android.content.pm.DataLoaderParams;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.IPackageInstallerSessionFileSystemConnector;
import android.content.pm.InstallationFile;
import android.content.pm.InstallationFileParcel;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionInfo.StagedSessionErrorCode;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Bundle;
import android.os.FileBridge;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.Process;
import android.os.RemoteException;
import android.os.RevocableFileDescriptor;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.incremental.IncrementalFileStorages;
import android.os.incremental.IncrementalManager;
import android.os.storage.StorageManager;
import android.provider.Settings.Secure;
import android.stats.devicepolicy.DevicePolicyEnums;
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
import android.util.SparseIntArray;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.security.VerityUtils;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstallerSession";
    private static final boolean LOGD = true;
    private static final String REMOVE_MARKER_EXTENSION = ".removed";

    private static final int MSG_STREAM_VALIDATE_AND_COMMIT = 1;
    private static final int MSG_INSTALL = 2;
    private static final int MSG_ON_PACKAGE_INSTALLED = 3;
    private static final int MSG_SESSION_VERIFICATION_FAILURE = 4;

    /** XML constants used for persisting a session */
    static final String TAG_SESSION = "session";
    static final String TAG_CHILD_SESSION = "childSession";
    static final String TAG_SESSION_FILE = "sessionFile";
    private static final String TAG_GRANTED_RUNTIME_PERMISSION = "granted-runtime-permission";
    private static final String TAG_WHITELISTED_RESTRICTED_PERMISSION =
            "whitelisted-restricted-permission";
    private static final String TAG_AUTO_REVOKE_PERMISSIONS_MODE =
            "auto-revoke-permissions-mode";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_INSTALLER_ATTRIBUTION_TAG = "installerAttributionTag";
    private static final String ATTR_INSTALLER_UID = "installerUid";
    private static final String ATTR_INITIATING_PACKAGE_NAME =
            "installInitiatingPackageName";
    private static final String ATTR_ORIGINATING_PACKAGE_NAME =
            "installOriginatingPackageName";
    private static final String ATTR_CREATED_MILLIS = "createdMillis";
    private static final String ATTR_UPDATED_MILLIS = "updatedMillis";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SESSION_STAGE_CID = "sessionStageCid";
    private static final String ATTR_PREPARED = "prepared";
    private static final String ATTR_COMMITTED = "committed";
    private static final String ATTR_SEALED = "sealed";
    private static final String ATTR_MULTI_PACKAGE = "multiPackage";
    private static final String ATTR_PARENT_SESSION_ID = "parentSessionId";
    private static final String ATTR_STAGED_SESSION = "stagedSession";
    private static final String ATTR_IS_READY = "isReady";
    private static final String ATTR_IS_FAILED = "isFailed";
    private static final String ATTR_IS_APPLIED = "isApplied";
    private static final String ATTR_STAGED_SESSION_ERROR_CODE = "errorCode";
    private static final String ATTR_STAGED_SESSION_ERROR_MESSAGE = "errorMessage";
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
    private static final String ATTR_IS_DATALOADER = "isDataLoader";
    private static final String ATTR_DATALOADER_TYPE = "dataLoaderType";
    private static final String ATTR_DATALOADER_PACKAGE_NAME = "dataLoaderPackageName";
    private static final String ATTR_DATALOADER_CLASS_NAME = "dataLoaderClassName";
    private static final String ATTR_DATALOADER_ARGUMENTS = "dataLoaderArguments";
    private static final String ATTR_LOCATION = "location";
    private static final String ATTR_LENGTH_BYTES = "lengthBytes";
    private static final String ATTR_METADATA = "metadata";
    private static final String ATTR_SIGNATURE = "signature";

    private static final String PROPERTY_NAME_INHERIT_NATIVE = "pi.inherit_native_on_dont_kill";
    private static final int[] EMPTY_CHILD_SESSION_ARRAY = EmptyArray.INT;
    private static final InstallationFile[] EMPTY_INSTALLATION_FILE_ARRAY = {};

    private static final String SYSTEM_DATA_LOADER_PACKAGE = "android";

    // TODO: enforce INSTALL_ALLOW_TEST
    // TODO: enforce INSTALL_ALLOW_DOWNGRADE

    private final PackageInstallerService.InternalCallback mCallback;
    private final Context mContext;
    private final PackageManagerService mPm;
    private final Handler mHandler;
    private final PackageSessionProvider mSessionProvider;
    private final StagingManager mStagingManager;

    final int sessionId;
    final int userId;
    final SessionParams params;
    final long createdMillis;

    /** Staging location where client data is written. */
    final File stageDir;
    final String stageCid;

    private final AtomicInteger mActiveCount = new AtomicInteger();

    private final Object mLock = new Object();

    /** Timestamp of the last time this session changed state  */
    @GuardedBy("mLock")
    private long updatedMillis;

    /** Uid of the creator of this session. */
    private final int mOriginalInstallerUid;

    /** Uid of the owner of the installer session */
    @GuardedBy("mLock")
    private int mInstallerUid;

    /** Where this install request came from */
    @GuardedBy("mLock")
    private InstallSource mInstallSource;

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
    private boolean mShouldBeSealed = false;
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
    private IntentSender mRemoteStatusReceiver;

    /** Fields derived from commit parsing */
    @GuardedBy("mLock")
    private String mPackageName;
    @GuardedBy("mLock")
    private long mVersionCode;
    @GuardedBy("mLock")
    private PackageParser.SigningDetails mSigningDetails;
    @GuardedBy("mLock")
    private SparseIntArray mChildSessionIds = new SparseIntArray();
    @GuardedBy("mLock")
    private int mParentSessionId;

    static class FileEntry {
        private final int mIndex;
        private final InstallationFile mFile;

        FileEntry(int index, InstallationFile file) {
            this.mIndex = index;
            this.mFile = file;
        }

        int getIndex() {
            return this.mIndex;
        }

        InstallationFile getFile() {
            return this.mFile;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof FileEntry)) {
                return false;
            }
            final FileEntry rhs = (FileEntry) obj;
            return (mFile.getLocation() == rhs.mFile.getLocation()) && TextUtils.equals(
                    mFile.getName(), rhs.mFile.getName());
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFile.getLocation(), mFile.getName());
        }
    }

    @GuardedBy("mLock")
    private ArraySet<FileEntry> mFiles = new ArraySet<>();

    @GuardedBy("mLock")
    private boolean mStagedSessionApplied;
    @GuardedBy("mLock")
    private boolean mStagedSessionReady;
    @GuardedBy("mLock")
    private boolean mStagedSessionFailed;
    @GuardedBy("mLock")
    private int mStagedSessionErrorCode = SessionInfo.STAGED_SESSION_NO_ERROR;
    @GuardedBy("mLock")
    private String mStagedSessionErrorMessage;

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
    private final List<File> mResolvedStagedFiles = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<File> mResolvedInheritedFiles = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<String> mResolvedInstructionSets = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<String> mResolvedNativeLibPaths = new ArrayList<>();
    @GuardedBy("mLock")
    private File mInheritedFilesBase;
    @GuardedBy("mLock")
    private boolean mVerityFound;

    private boolean mDataLoaderFinished = false;

    // TODO(b/146080380): merge file list with Callback installation.
    private IncrementalFileStorages mIncrementalFileStorages;

    private static final FileFilter sAddedApkFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) return false;
            if (file.getName().endsWith(REMOVE_MARKER_EXTENSION)) return false;
            if (DexMetadataHelper.isDexMetadataFile(file)) return false;
            if (VerityUtils.isFsveritySignatureFile(file)) return false;
            return true;
        }
    };
    private static final FileFilter sAddedFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) return false;
            if (file.getName().endsWith(REMOVE_MARKER_EXTENSION)) return false;
            return true;
        }
    };
    private static final FileFilter sRemovedFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            if (file.isDirectory()) return false;
            if (!file.getName().endsWith(REMOVE_MARKER_EXTENSION)) return false;
            return true;
        }
    };

    private final Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_STREAM_VALIDATE_AND_COMMIT:
                    handleStreamValidateAndCommit();
                    break;
                case MSG_INSTALL:
                    handleInstall();
                    break;
                case MSG_ON_PACKAGE_INSTALLED:
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final String packageName = (String) args.arg1;
                    final String message = (String) args.arg2;
                    final Bundle extras = (Bundle) args.arg3;
                    final IntentSender statusReceiver = (IntentSender) args.arg4;
                    final int returnCode = args.argi1;
                    args.recycle();

                    sendOnPackageInstalled(mContext, statusReceiver, sessionId,
                            isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked(), userId,
                            packageName, returnCode, message, extras);

                    break;
                case MSG_SESSION_VERIFICATION_FAILURE:
                    final int error = msg.arg1;
                    final String detailMessage = (String) msg.obj;
                    onSessionVerificationFailure(error, detailMessage);
                    break;
            }

            return true;
        }
    };

    private boolean isDataLoaderInstallation() {
        return params.dataLoaderParams != null;
    }

    private boolean isStreamingInstallation() {
        return isDataLoaderInstallation() && params.dataLoaderParams.getType() == STREAMING;
    }

    private boolean isIncrementalInstallation() {
        return isDataLoaderInstallation() && params.dataLoaderParams.getType() == INCREMENTAL;
    }

    /**
     * @return {@code true} iff the installing is app an device owner or affiliated profile owner.
     */
    @GuardedBy("mLock")
    private boolean isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked() {
        if (userId != UserHandle.getUserId(mInstallerUid)) {
            return false;
        }
        DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        return dpmi != null && dpmi.canSilentlyInstallPackage(
                mInstallSource.installerPackageName, mInstallerUid);
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
            Context context, PackageManagerService pm,
            PackageSessionProvider sessionProvider, Looper looper, StagingManager stagingManager,
            int sessionId, int userId, int installerUid, @NonNull InstallSource installSource,
            SessionParams params, long createdMillis,
            File stageDir, String stageCid, InstallationFile[] files, boolean prepared,
            boolean committed, boolean sealed,
            @Nullable int[] childSessionIds, int parentSessionId, boolean isReady,
            boolean isFailed, boolean isApplied, int stagedSessionErrorCode,
            String stagedSessionErrorMessage) {
        mCallback = callback;
        mContext = context;
        mPm = pm;
        mSessionProvider = sessionProvider;
        mHandler = new Handler(looper, mHandlerCallback);
        mStagingManager = stagingManager;

        this.sessionId = sessionId;
        this.userId = userId;
        mOriginalInstallerUid = installerUid;
        mInstallerUid = installerUid;
        mInstallSource = Objects.requireNonNull(installSource);
        this.params = params;
        this.createdMillis = createdMillis;
        this.updatedMillis = createdMillis;
        this.stageDir = stageDir;
        this.stageCid = stageCid;
        this.mShouldBeSealed = sealed;
        if (childSessionIds != null) {
            for (int childSessionId : childSessionIds) {
                mChildSessionIds.put(childSessionId, 0);
            }
        }
        this.mParentSessionId = parentSessionId;

        if (files != null) {
            for (int i = 0, size = files.length; i < size; ++i) {
                InstallationFile file = files[i];
                if (!mFiles.add(new FileEntry(i, file))) {
                    throw new IllegalArgumentException(
                            "Trying to add a duplicate installation file");
                }
            }
        }

        if (!params.isMultiPackage && (stageDir == null) == (stageCid == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of stageDir or stageCid stage must be set");
        }

        mPrepared = prepared;
        mCommitted = committed;
        mStagedSessionReady = isReady;
        mStagedSessionFailed = isFailed;
        mStagedSessionApplied = isApplied;
        mStagedSessionErrorCode = stagedSessionErrorCode;
        mStagedSessionErrorMessage =
                stagedSessionErrorMessage != null ? stagedSessionErrorMessage : "";

        if (isDataLoaderInstallation()) {
            if (isApexInstallation()) {
                throw new IllegalArgumentException(
                        "DataLoader installation of APEX modules is not allowed.");
            }
            if (this.params.dataLoaderParams.getComponentName().getPackageName()
                    == SYSTEM_DATA_LOADER_PACKAGE) {
                assertShellOrSystemCalling("System data loaders");
            }
        }

        if (isIncrementalInstallation()) {
            if (!IncrementalManager.isAllowed()) {
                throw new IllegalArgumentException("Incremental installation not allowed.");
            }
            if (!isIncrementalInstallationAllowed(mPackageName)) {
                throw new IllegalArgumentException(
                        "Incremental installation of this package is not allowed.");
            }
        }
    }

    /**
     * Returns {@code true} if the {@link SessionInfo} object should be produced with potentially
     * sensitive data scrubbed from its fields.
     *
     * @param callingUid the uid of the caller; the recipient of the {@link SessionInfo} that may
     *                   need to be scrubbed
     */
    private boolean shouldScrubData(int callingUid) {
        return !(callingUid < Process.FIRST_APPLICATION_UID || getInstallerUid() == callingUid);
    }

    /**
     * Generates a {@link SessionInfo} object for the provided uid. This may result in some fields
     * that may contain sensitive info being filtered.
     *
     * @param includeIcon true if the icon should be included in the object
     * @param callingUid the uid of the caller; the recipient of the {@link SessionInfo} that may
     *                   need to be scrubbed
     * @see #shouldScrubData(int)
     */
    public SessionInfo generateInfoForCaller(boolean includeIcon, int callingUid) {
        return generateInfoInternal(includeIcon, shouldScrubData(callingUid));
    }

    /**
     * Generates a {@link SessionInfo} object to ensure proper hiding of sensitive fields.
     *
     * @param includeIcon true if the icon should be included in the object
     * @see #generateInfoForCaller(boolean, int)
     */
    public SessionInfo generateInfoScrubbed(boolean includeIcon) {
        return generateInfoInternal(includeIcon, true /*scrubData*/);
    }

    private SessionInfo generateInfoInternal(boolean includeIcon, boolean scrubData) {
        final SessionInfo info = new SessionInfo();
        synchronized (mLock) {
            info.sessionId = sessionId;
            info.userId = userId;
            info.installerPackageName = mInstallSource.installerPackageName;
            info.installerAttributionTag = mInstallSource.installerAttributionTag;
            info.resolvedBaseCodePath = (mResolvedBaseFile != null) ?
                    mResolvedBaseFile.getAbsolutePath() : null;
            info.progress = mProgress;
            info.sealed = mSealed;
            info.isCommitted = mCommitted;
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
            if (!scrubData) {
                info.originatingUri = params.originatingUri;
            }
            info.originatingUid = params.originatingUid;
            if (!scrubData) {
                info.referrerUri = params.referrerUri;
            }
            info.grantedRuntimePermissions = params.grantedRuntimePermissions;
            info.whitelistedRestrictedPermissions = params.whitelistedRestrictedPermissions;
            info.autoRevokePermissionsMode = params.autoRevokePermissionsMode;
            info.installFlags = params.installFlags;
            info.isMultiPackage = params.isMultiPackage;
            info.isStaged = params.isStaged;
            info.rollbackDataPolicy = params.rollbackDataPolicy;
            info.parentSessionId = mParentSessionId;
            info.childSessionIds = mChildSessionIds.copyKeys();
            if (info.childSessionIds == null) {
                info.childSessionIds = EMPTY_CHILD_SESSION_ARRAY;
            }
            info.isStagedSessionApplied = mStagedSessionApplied;
            info.isStagedSessionReady = mStagedSessionReady;
            info.isStagedSessionFailed = mStagedSessionFailed;
            info.setStagedSessionErrorCode(mStagedSessionErrorCode, mStagedSessionErrorMessage);
            info.createdMillis = createdMillis;
            info.updatedMillis = updatedMillis;
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

    /** {@hide} */
    boolean isCommitted() {
        synchronized (mLock) {
            return mCommitted;
        }
    }

    /** Returns true if a staged session has reached a final state and can be forgotten about  */
    public boolean isStagedAndInTerminalState() {
        synchronized (mLock) {
            return params.isStaged && (mStagedSessionApplied || mStagedSessionFailed);
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

    @GuardedBy("mLock")
    private void setClientProgressLocked(float progress) {
        // Always publish first staging movement
        final boolean forcePublish = (mClientProgress == 0);
        mClientProgress = progress;
        computeProgressLocked(forcePublish);
    }

    @Override
    public void setClientProgress(float progress) {
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            setClientProgressLocked(progress);
        }
    }

    @Override
    public void addClientProgress(float progress) {
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            setClientProgressLocked(mClientProgress + progress);
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

            return getNamesLocked();
        }
    }

    @GuardedBy("mLock")
    private String[] getNamesLocked() {
        if (!isDataLoaderInstallation()) {
            String[] result = stageDir.list();
            if (result == null) {
                result = EmptyArray.STRING;
            }
            return result;
        }

        InstallationFile[] files = getInstallationFilesLocked();
        String[] result = new String[files.length];
        for (int i = 0, size = files.length; i < size; ++i) {
            result[i] = files[i].getName();
        }
        return result;
    }

    @GuardedBy("mLock")
    private InstallationFile[] getInstallationFilesLocked() {
        final InstallationFile[] result = new InstallationFile[mFiles.size()];
        for (FileEntry fileEntry : mFiles) {
            result[fileEntry.getIndex()] = fileEntry.getFile();
        }
        return result;
    }

    private static ArrayList<File> filterFiles(File parent, String[] names, FileFilter filter) {
        ArrayList<File> result = new ArrayList<>(names.length);
        for (String name : names) {
            File file = new File(parent, name);
            if (filter.accept(file)) {
                result.add(file);
            }
        }
        return result;
    }

    @GuardedBy("mLock")
    private List<File> getAddedApksLocked() {
        String[] names = getNamesLocked();
        return filterFiles(stageDir, names, sAddedApkFilter);
    }

    @GuardedBy("mLock")
    private List<File> getRemovedFilesLocked() {
        String[] names = getNamesLocked();
        return filterFiles(stageDir, names, sRemovedFilter);
    }

    @Override
    public void removeSplit(String splitName) {
        if (isDataLoaderInstallation()) {
            throw new IllegalStateException(
                    "Cannot remove splits in a data loader installation session.");
        }
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

    private static String getRemoveMarkerName(String name) {
        final String markerName = name + REMOVE_MARKER_EXTENSION;
        if (!FileUtils.isValidExtFilename(markerName)) {
            throw new IllegalArgumentException("Invalid marker: " + markerName);
        }
        return markerName;
    }

    private void createRemoveSplitMarkerLocked(String splitName) throws IOException {
        try {
            final File target = new File(stageDir, getRemoveMarkerName(splitName));
            target.createNewFile();
            Os.chmod(target.getAbsolutePath(), 0 /*mode*/);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    private void assertShellOrSystemCalling(String operation) {
        switch (Binder.getCallingUid()) {
            case android.os.Process.SHELL_UID:
            case android.os.Process.ROOT_UID:
            case android.os.Process.SYSTEM_UID:
                break;
            default:
                throw new SecurityException(operation + " only supported from shell or system");
        }
    }

    private void assertCanWrite(boolean reverseMode) {
        if (isDataLoaderInstallation()) {
            throw new IllegalStateException(
                    "Cannot write regular files in a data loader installation session.");
        }
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("assertCanWrite");
        }
        if (reverseMode) {
            assertShellOrSystemCalling("Reverse mode");
        }
    }

    @Override
    public ParcelFileDescriptor openWrite(String name, long offsetBytes, long lengthBytes) {
        assertCanWrite(false);
        try {
            return doWriteInternal(name, offsetBytes, lengthBytes, null);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    @Override
    public void write(String name, long offsetBytes, long lengthBytes,
            ParcelFileDescriptor fd) {
        assertCanWrite(fd != null);
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
        synchronized (mLock) {
            if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                fd = new RevocableFileDescriptor();
                bridge = null;
                mFds.add(fd);
            } else {
                fd = null;
                bridge = new FileBridge();
                mBridges.add(bridge);
            }
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
                // In "reverse" mode, we're streaming data ourselves from the
                // incoming FD, which means we never have to hand out our
                // sensitive internal FD. We still rely on a "bridge" being
                // inserted above to hold the session active.
                try {
                    final Int64Ref last = new Int64Ref(0);
                    FileUtils.copy(incomingFd.getFileDescriptor(), targetFd, lengthBytes, null,
                            Runnable::run, (long progress) -> {
                                if (params.sizeBytes > 0) {
                                    final long delta = progress - last.value;
                                    last.value = progress;
                                    synchronized (mLock) {
                                        setClientProgressLocked(mClientProgress
                                                + (float) delta / (float) params.sizeBytes);
                                    }
                                }
                            });
                } finally {
                    IoUtils.closeQuietly(targetFd);
                    IoUtils.closeQuietly(incomingFd);

                    // We're done here, so remove the "bridge" that was holding
                    // the session active.
                    synchronized (mLock) {
                        if (PackageInstaller.ENABLE_REVOCABLE_FD) {
                            mFds.remove(fd);
                        } else {
                            bridge.forceClose();
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
        if (isDataLoaderInstallation()) {
            throw new IllegalStateException(
                    "Cannot read regular files in a data loader installation session.");
        }
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
            final File target = new File(stageDir, name);
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
        if (hasParentSessionId()) {
            throw new IllegalStateException(
                    "Session " + sessionId + " is a child of multi-package session "
                            + mParentSessionId +  " and may not be committed directly.");
        }

        if (!markAsSealed(statusReceiver, forTransfer)) {
            return;
        }
        if (isMultiPackage()) {
            final SparseIntArray remainingSessions = mChildSessionIds.clone();
            final IntentSender childIntentSender =
                    new ChildStatusIntentReceiver(remainingSessions, statusReceiver)
                            .getIntentSender();
            boolean sealFailed = false;
            for (int i = mChildSessionIds.size() - 1; i >= 0; --i) {
                final int childSessionId = mChildSessionIds.keyAt(i);
                // seal all children, regardless if any of them fail; we'll throw/return
                // as appropriate once all children have been processed
                if (!mSessionProvider.getSession(childSessionId)
                        .markAsSealed(childIntentSender, forTransfer)) {
                    sealFailed = true;
                }
            }
            if (sealFailed) {
                return;
            }
        }

        dispatchStreamValidateAndCommit();
    }

    private void dispatchStreamValidateAndCommit() {
        mHandler.obtainMessage(MSG_STREAM_VALIDATE_AND_COMMIT).sendToTarget();
    }

    private void handleStreamValidateAndCommit() {
        PackageManagerException unrecoverableFailure = null;
        // This will track whether the session and any children were validated and are ready to
        // progress to the next phase of install
        boolean allSessionsReady = false;
        try {
            allSessionsReady = streamValidateAndCommit();
        } catch (PackageManagerException e) {
            unrecoverableFailure = e;
        }

        if (isMultiPackage()) {
            int childCount = mChildSessionIds.size();

            // This will contain all child sessions that do not encounter an unrecoverable failure
            ArrayList<PackageInstallerSession> nonFailingSessions = new ArrayList<>(childCount);

            for (int i = childCount - 1; i >= 0; --i) {
                final int childSessionId = mChildSessionIds.keyAt(i);
                // commit all children, regardless if any of them fail; we'll throw/return
                // as appropriate once all children have been processed
                try {
                    PackageInstallerSession session = mSessionProvider.getSession(childSessionId);
                    if (!session.streamValidateAndCommit()) {
                        allSessionsReady = false;
                    }
                    nonFailingSessions.add(session);
                } catch (PackageManagerException e) {
                    allSessionsReady = false;
                    if (unrecoverableFailure == null) {
                        unrecoverableFailure = e;
                    }
                }
            }
            // If we encountered any unrecoverable failures, destroy all
            // other impacted sessions besides the parent; that will be cleaned up by the
            // ChildStatusIntentReceiver.
            if (unrecoverableFailure != null) {
                // fail other child sessions that did not already fail
                for (int i = nonFailingSessions.size() - 1; i >= 0; --i) {
                    PackageInstallerSession session = nonFailingSessions.get(i);
                    session.onSessionVerificationFailure(unrecoverableFailure);
                }
            }
        }

        if (!allSessionsReady) {
            return;
        }

        mHandler.obtainMessage(MSG_INSTALL).sendToTarget();
    }

    private final class FileSystemConnector extends
            IPackageInstallerSessionFileSystemConnector.Stub {
        final Set<String> mAddedFiles = new ArraySet<>();

        FileSystemConnector(List<InstallationFileParcel> addedFiles) {
            for (InstallationFileParcel file : addedFiles) {
                mAddedFiles.add(file.name);
            }
        }

        @Override
        public void writeData(String name, long offsetBytes, long lengthBytes,
                ParcelFileDescriptor incomingFd) {
            if (incomingFd == null) {
                throw new IllegalArgumentException("incomingFd can't be null");
            }
            if (!mAddedFiles.contains(name)) {
                throw new SecurityException("File name is not in the list of added files.");
            }
            try {
                doWriteInternal(name, offsetBytes, lengthBytes, incomingFd);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    private class ChildStatusIntentReceiver {
        private final SparseIntArray mChildSessionsRemaining;
        private final IntentSender mStatusReceiver;
        private final IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                statusUpdate(intent);
            }
        };

        private ChildStatusIntentReceiver(SparseIntArray remainingSessions,
                IntentSender statusReceiver) {
            this.mChildSessionsRemaining = remainingSessions;
            this.mStatusReceiver = statusReceiver;
        }

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public void statusUpdate(Intent intent) {
            mHandler.post(() -> {
                if (mChildSessionsRemaining.size() == 0) {
                    return;
                }
                final int sessionId = intent.getIntExtra(
                        PackageInstaller.EXTRA_SESSION_ID, 0);
                final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                final int sessionIndex = mChildSessionsRemaining.indexOfKey(sessionId);
                if (PackageInstaller.STATUS_SUCCESS == status) {
                    mChildSessionsRemaining.removeAt(sessionIndex);
                    if (mChildSessionsRemaining.size() == 0) {
                        try {
                            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID,
                                    PackageInstallerSession.this.sessionId);
                            mStatusReceiver.sendIntent(mContext, 0, intent, null, null);
                        } catch (IntentSender.SendIntentException ignore) {
                        }
                    }
                } else if (PackageInstaller.STATUS_PENDING_USER_ACTION == status) {
                    try {
                        mStatusReceiver.sendIntent(mContext, 0, intent, null, null);
                    } catch (IntentSender.SendIntentException ignore) {
                    }
                } else { // failure, let's forward and clean up this session.
                    intent.putExtra(PackageInstaller.EXTRA_SESSION_ID,
                            PackageInstallerSession.this.sessionId);
                    mChildSessionsRemaining.clear(); // we're done. Don't send any more.
                    onSessionVerificationFailure(status,
                            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                }
            });
        }
    }

    /** {@hide} */
    private class StreamingException extends Exception {
        StreamingException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Returns whether or not a package can be installed while Secure FRP is enabled.
     * <p>
     * Only callers with the INSTALL_PACKAGES permission are allowed to install. However,
     * prevent the package installer from installing anything because, while it has the
     * permission, it will allows packages to be installed from anywhere.
     */
    private static boolean isSecureFrpInstallAllowed(Context context, int callingUid) {
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final String[] systemInstaller = pmi.getKnownPackageNames(
                PackageManagerInternal.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM);
        final AndroidPackage callingInstaller = pmi.getPackage(callingUid);
        if (callingInstaller != null
                && ArrayUtils.contains(systemInstaller, callingInstaller.getPackageName())) {
            // don't allow the system package installer to install while under secure FRP
            return false;
        }

        // require caller to hold the INSTALL_PACKAGES permission
        return context.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks if the package can be installed on IncFs.
     */
    private static boolean isIncrementalInstallationAllowed(String packageName) {
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final PackageSetting existingPkgSetting = pmi.getPackageSetting(packageName);
        if (existingPkgSetting == null || existingPkgSetting.pkg == null) {
            return true;
        }

        return !existingPkgSetting.pkg.isSystem()
                && !existingPkgSetting.getPkgState().isUpdatedSystemApp();
    }

    /**
     * If this was not already called, the session will be sealed.
     *
     * This method may be called multiple times to update the status receiver validate caller
     * permissions.
     */
    private boolean markAsSealed(@NonNull IntentSender statusReceiver, boolean forTransfer) {
        Objects.requireNonNull(statusReceiver);

        List<PackageInstallerSession> childSessions = getChildSessions();

        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotDestroyedLocked("commit");
            assertNoWriteFileTransfersOpenLocked();

            final boolean isSecureFrpEnabled =
                    (Secure.getInt(mContext.getContentResolver(), Secure.SECURE_FRP_MODE, 0) == 1);
            if (isSecureFrpEnabled
                    && !isSecureFrpInstallAllowed(mContext, Binder.getCallingUid())) {
                throw new SecurityException("Can't install packages while in secure FRP");
            }

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

            mRemoteStatusReceiver = statusReceiver;

            // After updating the observer, we can skip re-sealing.
            if (mSealed) {
                return true;
            }

            try {
                sealLocked(childSessions);
            } catch (PackageManagerException e) {
                return false;
            }
        }

        // Persist the fact that we've sealed ourselves to prevent
        // mutations of any hard links we create. We do this without holding
        // the session lock, since otherwise it's a lock inversion.
        mCallback.onSessionSealedBlocking(this);

        return true;
    }

    /**
     * Returns true if the session is successfully validated and committed. Returns false if the
     * dataloader could not be prepared. This can be called multiple times so long as no
     * exception is thrown.
     * @throws PackageManagerException on an unrecoverable error.
     */
    private boolean streamValidateAndCommit() throws PackageManagerException {
        synchronized (mLock) {
            if (mCommitted) {
                return true;
            }

            if (!streamAndValidateLocked()) {
                return false;
            }

            // Client staging is fully done at this point
            mClientProgress = 1f;
            computeProgressLocked(true);

            // This ongoing commit should keep session active, even though client
            // will probably close their end.
            mActiveCount.incrementAndGet();

            mCommitted = true;
        }
        return true;
    }

    /** Return a list of child sessions or null if the session is not multipackage
     *
     * <p> This method is handy to prevent potential deadlocks (b/123391593)
     */
    private @Nullable List<PackageInstallerSession> getChildSessions() {
        List<PackageInstallerSession> childSessions = null;
        if (isMultiPackage()) {
            final int[] childSessionIds = getChildSessionIds();
            childSessions = new ArrayList<>(childSessionIds.length);
            for (int childSessionId : childSessionIds) {
                childSessions.add(mSessionProvider.getSession(childSessionId));
            }
        }
        return childSessions;
    }

    /**
     * Assert multipackage install has consistent sessions.
     *
     * @throws PackageManagerException if child sessions don't match parent session
     *                                  in respect to staged and enable rollback parameters.
     */
    @GuardedBy("mLock")
    private void assertMultiPackageConsistencyLocked(
            @NonNull List<PackageInstallerSession> childSessions) throws PackageManagerException {
        for (PackageInstallerSession childSession : childSessions) {
            // It might be that the parent session is loaded before all of it's child sessions are,
            // e.g. when reading sessions from XML. Those sessions will be null here, and their
            // conformance with the multipackage params will be checked when they're loaded.
            if (childSession == null) {
                continue;
            }
            assertConsistencyWithLocked(childSession);
        }
    }

    /**
     * Assert consistency with the given session.
     *
     * @throws PackageManagerException if other sessions doesn't match this session
     *                                  in respect to staged and enable rollback parameters.
     */
    @GuardedBy("mLock")
    private void assertConsistencyWithLocked(PackageInstallerSession other)
            throws PackageManagerException {
        // Session groups must be consistent wrt to isStaged parameter. Non-staging session
        // cannot be grouped with staging sessions.
        if (this.params.isStaged != other.params.isStaged) {
            throw new PackageManagerException(
                PackageManager.INSTALL_FAILED_MULTIPACKAGE_INCONSISTENCY,
                "Multipackage Inconsistency: session " + other.sessionId
                    + " and session " + sessionId
                    + " have inconsistent staged settings");
        }
        if (this.params.getEnableRollback() != other.params.getEnableRollback()) {
            throw new PackageManagerException(
                PackageManager.INSTALL_FAILED_MULTIPACKAGE_INCONSISTENCY,
                "Multipackage Inconsistency: session " + other.sessionId
                    + " and session " + sessionId
                    + " have inconsistent rollback settings");
        }
    }

    /**
     * Seal the session to prevent further modification.
     *
     * <p>The session will be sealed after calling this method even if it failed.
     *
     * @throws PackageManagerException if the session was sealed but something went wrong. If the
     *                                 session was sealed this is the only possible exception.
     */
    @GuardedBy("mLock")
    private void sealLocked(List<PackageInstallerSession> childSessions)
            throws PackageManagerException {
        try {
            assertNoWriteFileTransfersOpenLocked();
            assertPreparedAndNotDestroyedLocked("sealing of session");

            mSealed = true;

            if (childSessions != null) {
                assertMultiPackageConsistencyLocked(childSessions);
            }
        } catch (PackageManagerException e) {
            throw onSessionVerificationFailure(e);
        } catch (Throwable e) {
            // Convert all exceptions into package manager exceptions as only those are handled
            // in the code above.
            throw onSessionVerificationFailure(new PackageManagerException(e));
        }
    }

    /**
     * Prepare DataLoader and stream content for DataLoader sessions.
     * Validate the contents of all session.
     *
     * @return false if the data loader could not be prepared.
     * @throws PackageManagerException when an unrecoverable exception is encountered
     */
    @GuardedBy("mLock")
    private boolean streamAndValidateLocked() throws PackageManagerException {
        try {
            // Read transfers from the original owner stay open, but as the session's data cannot
            // be modified anymore, there is no leak of information. For staged sessions, further
            // validation is performed by the staging manager.
            if (!params.isMultiPackage) {
                if (!prepareDataLoaderLocked()) {
                    return false;
                }

                if (isApexInstallation()) {
                    validateApexInstallLocked();
                } else {
                    validateApkInstallLocked();
                }
            }

            if (params.isStaged) {
                mStagingManager.checkNonOverlappingWithStagedSessions(this);
            }
            return true;
        } catch (PackageManagerException e) {
            throw onSessionVerificationFailure(e);
        } catch (Throwable e) {
            // Convert all exceptions into package manager exceptions as only those are handled
            // in the code above.
            throw onSessionVerificationFailure(new PackageManagerException(e));
        }
    }

    private PackageManagerException onSessionVerificationFailure(PackageManagerException e) {
        onSessionVerificationFailure(e.error, ExceptionUtils.getCompleteMessage(e));
        return e;
    }

    private void onSessionVerificationFailure(int error, String detailMessage) {
        // Session is sealed but could not be verified, we need to destroy it.
        destroyInternal();
        // Dispatch message to remove session from PackageInstallerService.
        dispatchSessionFinished(error, detailMessage, null);
    }

    private void onDataLoaderUnrecoverable() {
        if (TextUtils.isEmpty(mPackageName)) {
            // The package has not been installed.
            return;
        }
        final PackageManagerService packageManagerService = mPm;
        final String packageName = mPackageName;
        mHandler.post(() -> {
            if (packageManagerService.deletePackageX(packageName,
                    PackageManager.VERSION_CODE_HIGHEST, UserHandle.USER_SYSTEM,
                    PackageManager.DELETE_ALL_USERS) != PackageManager.DELETE_SUCCEEDED) {
                Slog.e(TAG, "Failed to uninstall package with failed dataloader: " + packageName);
            }
        });
    }

    /**
     * If session should be sealed, then it's sealed to prevent further modification.
     * If the session can't be sealed then it's destroyed.
     *
     * Additionally for staged APEX sessions read+validate the package and populate req'd fields.
     *
     * <p> This is meant to be called after all of the sessions are loaded and added to
     * PackageInstallerService
     */
    void onAfterSessionRead() {
        synchronized (mLock) {
            if (!mShouldBeSealed || isStagedAndInTerminalState()) {
                return;
            }
        }
        List<PackageInstallerSession> childSessions = getChildSessions();
        synchronized (mLock) {
            try {
                sealLocked(childSessions);

                if (isApexInstallation()) {
                    // APEX installations rely on certain fields to be populated after reboot.
                    // E.g. mPackageName.
                    validateApexInstallLocked();
                }
            } catch (PackageManagerException e) {
                Slog.e(TAG, "Package not valid", e);
            }
        }
    }

    /** Update the timestamp of when the staged session last changed state */
    public void markUpdated() {
        synchronized (mLock) {
            this.updatedMillis = System.currentTimeMillis();
        }
    }

    @Override
    public void transfer(String packageName) {
        Objects.requireNonNull(packageName);

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

        List<PackageInstallerSession> childSessions = getChildSessions();

        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("transfer");

            try {
                sealLocked(childSessions);
            } catch (PackageManagerException e) {
                throw new IllegalArgumentException("Package is not valid", e);
            }

            if (!mPackageName.equals(mInstallSource.installerPackageName)) {
                throw new SecurityException("Can only transfer sessions that update the original "
                        + "installer");
            }

            mInstallerUid = newOwnerAppInfo.uid;
            mInstallSource = InstallSource.create(packageName, null, packageName, null);
        }

        // Persist the fact that we've sealed ourselves to prevent
        // mutations of any hard links we create. We do this without holding
        // the session lock, since otherwise it's a lock inversion.
        mCallback.onSessionSealedBlocking(this);
    }

    private void handleInstall() {
        if (isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked()) {
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.INSTALL_PACKAGE)
                    .setAdmin(mInstallSource.installerPackageName)
                    .write();
        }
        if (params.isStaged) {
            mStagingManager.commitSession(this);
            destroyInternal();
            dispatchSessionFinished(PackageManager.INSTALL_SUCCEEDED, "Session staged", null);
            return;
        }

        if (isApexInstallation()) {
            destroyInternal();
            dispatchSessionFinished(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    "APEX packages can only be installed using staged sessions.", null);
            return;
        }

        // For a multiPackage session, read the child sessions
        // outside of the lock, because reading the child
        // sessions with the lock held could lead to deadlock
        // (b/123391593).
        List<PackageInstallerSession> childSessions = getChildSessions();

        try {
            synchronized (mLock) {
                installNonStagedLocked(childSessions);
            }
        } catch (PackageManagerException e) {
            final String completeMsg = ExceptionUtils.getCompleteMessage(e);
            Slog.e(TAG, "Commit of session " + sessionId + " failed: " + completeMsg);
            destroyInternal();
            dispatchSessionFinished(e.error, completeMsg, null);
        }
    }

    @GuardedBy("mLock")
    private void installNonStagedLocked(List<PackageInstallerSession> childSessions)
            throws PackageManagerException {
        final PackageManagerService.ActiveInstallSession installingSession =
                makeSessionActiveLocked();
        if (installingSession == null) {
            return;
        }
        if (isMultiPackage()) {
            List<PackageManagerService.ActiveInstallSession> installingChildSessions =
                    new ArrayList<>(childSessions.size());
            boolean success = true;
            PackageManagerException failure = null;
            for (int i = 0; i < childSessions.size(); ++i) {
                final PackageInstallerSession session = childSessions.get(i);
                try {
                    final PackageManagerService.ActiveInstallSession installingChildSession =
                            session.makeSessionActiveLocked();
                    if (installingChildSession != null) {
                        installingChildSessions.add(installingChildSession);
                    }
                } catch (PackageManagerException e) {
                    failure = e;
                    success = false;
                }
            }
            if (!success) {
                sendOnPackageInstalled(mContext, mRemoteStatusReceiver, sessionId,
                        isInstallerDeviceOwnerOrAffiliatedProfileOwnerLocked(), userId, null,
                        failure.error, failure.getLocalizedMessage(), null);
                return;
            }
            mPm.installStage(installingChildSessions);
        } else {
            mPm.installStage(installingSession);
        }
    }

    /**
     * Stages this session for install and returns a
     * {@link PackageManagerService.ActiveInstallSession} representing this new staged state or null
     * in case permissions need to be requested before install can proceed.
     */
    @GuardedBy("mLock")
    private PackageManagerService.ActiveInstallSession makeSessionActiveLocked()
            throws PackageManagerException {
        if (mRelinquished) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Session relinquished");
        }
        if (mDestroyed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session destroyed");
        }
        if (!mSealed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR, "Session not sealed");
        }

        final IPackageInstallObserver2 localObserver;
        if (isApexInstallation()) {
            localObserver = null;
        } else {
            if (!params.isMultiPackage) {
                Objects.requireNonNull(mPackageName);
                Objects.requireNonNull(mSigningDetails);
                Objects.requireNonNull(mResolvedBaseFile);

                if (needToAskForPermissionsLocked()) {
                    // User needs to confirm installation;
                    // give installer an intent they can use to involve
                    // user.
                    final Intent intent = new Intent(PackageInstaller.ACTION_CONFIRM_INSTALL);
                    intent.setPackage(mPm.getPackageInstallerPackageName());
                    intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);

                    sendOnUserActionRequired(mContext, mRemoteStatusReceiver, sessionId, intent);

                    // Commit was keeping session marked as active until now; release
                    // that extra refcount so session appears idle.
                    closeInternal(false);
                    return null;
                }

                // Inherit any packages and native libraries from existing install that
                // haven't been overridden.
                if (params.mode == SessionParams.MODE_INHERIT_EXISTING) {
                    try {
                        final List<File> fromFiles = mResolvedInheritedFiles;
                        final File toDir = stageDir;

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
                                        Slog.e(TAG,
                                                "Skipping native library creation for linking due"
                                                        + " to invalid path: " + libPath);
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

                extractNativeLibraries(stageDir, params.abiOverride, mayInheritNativeLibs());
            }

            // We've reached point of no return; call into PMS to install the stage.
            // Regardless of success or failure we always destroy session.
            localObserver = new IPackageInstallObserver2.Stub() {
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
        }

        final UserHandle user;
        if ((params.installFlags & PackageManager.INSTALL_ALL_USERS) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(userId);
        }

        mRelinquished = true;
        return new PackageManagerService.ActiveInstallSession(mPackageName, stageDir, localObserver,
                sessionId, params, mInstallerUid, mInstallSource, user, mSigningDetails);
    }

    private static void maybeRenameFile(File from, File to) throws PackageManagerException {
        if (!from.equals(to)) {
            if (!from.renameTo(to)) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Could not rename file " + from + " to " + to);
            }
        }
    }

    private void logDataLoaderInstallationSession(int returnCode) {
        // Skip logging the side-loaded app installations, as those are private and aren't reported
        // anywhere; app stores already have a record of the installation and that's why reporting
        // it here is fine
        final String packageNameToLog =
                (params.installFlags & PackageManager.INSTALL_FROM_ADB) == 0 ? mPackageName : "";
        final long currentTimestamp = System.currentTimeMillis();
        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_INSTALLER_V2_REPORTED,
                isIncrementalInstallation(),
                packageNameToLog,
                currentTimestamp - createdMillis,
                returnCode,
                getApksSize());
    }

    private long getApksSize() {
        final PackageSetting ps = mPm.getPackageSetting(mPackageName);
        if (ps == null) {
            return 0;
        }
        final File apkDirOrPath = ps.codePath;
        if (apkDirOrPath == null) {
            return 0;
        }
        if (apkDirOrPath.isFile() && apkDirOrPath.getName().toLowerCase().endsWith(".apk")) {
            return apkDirOrPath.length();
        }
        if (!apkDirOrPath.isDirectory()) {
            return 0;
        }
        final File[] files = apkDirOrPath.listFiles();
        long apksSize = 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().toLowerCase().endsWith(".apk")) {
                apksSize += files[i].length();
            }
        }
        return apksSize;
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
     * Returns true if the session is installing an APEX package.
     */
    private boolean isApexInstallation() {
        return (params.installFlags & PackageManager.INSTALL_APEX) != 0;
    }

    /**
     * Validate apex install.
     * <p>
     * Sets {@link #mResolvedBaseFile} for RollbackManager to use. Sets {@link #mPackageName} for
     * StagingManager to use.
     */
    @GuardedBy("mLock")
    private void validateApexInstallLocked()
            throws PackageManagerException {
        final List<File> addedFiles = getAddedApksLocked();
        if (addedFiles.isEmpty()) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    String.format("Session: %d. No packages staged in %s", sessionId,
                          stageDir.getAbsolutePath()));
        }

        if (ArrayUtils.size(addedFiles) > 1) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Too many files for apex install");
        }

        File addedFile = addedFiles.get(0); // there is only one file

        // Ensure file name has proper suffix
        final String sourceName = addedFile.getName();
        final String targetName = sourceName.endsWith(APEX_FILE_EXTENSION)
                ? sourceName
                : sourceName + APEX_FILE_EXTENSION;
        if (!FileUtils.isValidExtFilename(targetName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Invalid filename: " + targetName);
        }

        final File targetFile = new File(stageDir, targetName);
        resolveAndStageFile(addedFile, targetFile);
        mResolvedBaseFile = targetFile;

        // Populate package name of the apex session
        mPackageName = null;
        final ApkLite apk;
        try {
            apk = PackageParser.parseApkLite(
                    mResolvedBaseFile, PackageParser.PARSE_COLLECT_CERTIFICATES);
        } catch (PackageParserException e) {
            throw PackageManagerException.from(e);
        }

        if (mPackageName == null) {
            mPackageName = apk.packageName;
            mVersionCode = apk.getLongVersionCode();
        }
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
    private void validateApkInstallLocked() throws PackageManagerException {
        ApkLite baseApk = null;
        mPackageName = null;
        mVersionCode = -1;
        mSigningDetails = PackageParser.SigningDetails.UNKNOWN;

        mResolvedBaseFile = null;
        mResolvedStagedFiles.clear();
        mResolvedInheritedFiles.clear();

        final PackageInfo pkgInfo = mPm.getPackageInfo(
                params.appPackageName, PackageManager.GET_SIGNATURES
                        | PackageManager.MATCH_STATIC_SHARED_LIBRARIES /*flags*/, userId);

        // Partial installs must be consistent with existing install
        if (params.mode == SessionParams.MODE_INHERIT_EXISTING
                && (pkgInfo == null || pkgInfo.applicationInfo == null)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Missing existing base package");
        }
        // Default to require only if existing base has fs-verity.
        mVerityFound = PackageManagerServiceUtils.isApkVerityEnabled()
                && params.mode == SessionParams.MODE_INHERIT_EXISTING
                && VerityUtils.hasFsverity(pkgInfo.applicationInfo.getBaseCodePath());

        final List<File> removedFiles = getRemovedFilesLocked();
        final List<String> removeSplitList = new ArrayList<>();
        if (!removedFiles.isEmpty()) {
            for (File removedFile : removedFiles) {
                final String fileName = removedFile.getName();
                final String splitName = fileName.substring(
                        0, fileName.length() - REMOVE_MARKER_EXTENSION.length());
                removeSplitList.add(splitName);
            }
        }

        final List<File> addedFiles = getAddedApksLocked();
        if (addedFiles.isEmpty() && removeSplitList.size() == 0) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    String.format("Session: %d. No packages staged in %s", sessionId,
                          stageDir.getAbsolutePath()));
        }

        // Verify that all staged packages are internally consistent
        final ArraySet<String> stagedSplits = new ArraySet<>();
        for (File addedFile : addedFiles) {
            final ApkLite apk;
            try {
                apk = ApkLiteParseUtils.parseApkLite(
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

            final File targetFile = new File(stageDir, targetName);
            resolveAndStageFile(addedFile, targetFile);

            // Base is coming from session
            if (apk.splitName == null) {
                mResolvedBaseFile = targetFile;
                baseApk = apk;
            }

            final File dexMetadataFile = DexMetadataHelper.findDexMetadataForFile(addedFile);
            if (dexMetadataFile != null) {
                if (!FileUtils.isValidExtFilename(dexMetadataFile.getName())) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Invalid filename: " + dexMetadataFile);
                }
                final File targetDexMetadataFile = new File(stageDir,
                        DexMetadataHelper.buildDexMetadataPathForApk(targetName));
                resolveAndStageFile(dexMetadataFile, targetDexMetadataFile);
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
                    mSigningDetails = ApkSignatureVerifier.unsafeGetCertsWithoutVerification(
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
            final PackageLite existing;
            final ApkLite existingBase;
            ApplicationInfo appInfo = pkgInfo.applicationInfo;
            try {
                existing = PackageParser.parsePackageLite(new File(appInfo.getCodePath()), 0);
                existingBase = ApkLiteParseUtils.parseApkLite(new File(appInfo.getBaseCodePath()),
                        PackageParser.PARSE_COLLECT_CERTIFICATES);
            } catch (PackageParserException e) {
                throw PackageManagerException.from(e);
            }

            assertApkConsistentLocked("Existing base", existingBase);

            // Inherit base if not overridden
            if (mResolvedBaseFile == null) {
                mResolvedBaseFile = new File(appInfo.getBaseCodePath());
                resolveInheritedFile(mResolvedBaseFile);
                // Inherit the dex metadata if present.
                final File baseDexMetadataFile =
                        DexMetadataHelper.findDexMetadataForFile(mResolvedBaseFile);
                if (baseDexMetadataFile != null) {
                    resolveInheritedFile(baseDexMetadataFile);
                }
                baseApk = existingBase;
            }

            // Inherit splits if not overridden
            if (!ArrayUtils.isEmpty(existing.splitNames)) {
                for (int i = 0; i < existing.splitNames.length; i++) {
                    final String splitName = existing.splitNames[i];
                    final File splitFile = new File(existing.splitCodePaths[i]);
                    final boolean splitRemoved = removeSplitList.contains(splitName);
                    if (!stagedSplits.contains(splitName) && !splitRemoved) {
                        resolveInheritedFile(splitFile);
                        // Inherit the dex metadata if present.
                        final File splitDexMetadataFile =
                                DexMetadataHelper.findDexMetadataForFile(splitFile);
                        if (splitDexMetadataFile != null) {
                            resolveInheritedFile(splitDexMetadataFile);
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

                        File[] files = archSubDir.listFiles();
                        if (files == null || files.length == 0) {
                            continue;
                        }

                        mResolvedInstructionSets.add(archSubDir.getName());
                        mResolvedInheritedFiles.addAll(Arrays.asList(files));
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
                    final List<String> libDirsToInherit = new ArrayList<>();
                    final List<File> libFilesToInherit = new ArrayList<>();
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
                            libFilesToInherit.clear();
                            break;
                        }

                        File[] files = archSubDir.listFiles();
                        if (files == null || files.length == 0) {
                            continue;
                        }

                        libDirsToInherit.add(relLibPath);
                        libFilesToInherit.addAll(Arrays.asList(files));
                    }
                    for (String subDir : libDirsToInherit) {
                        if (!mResolvedNativeLibPaths.contains(subDir)) {
                            mResolvedNativeLibPaths.add(subDir);
                        }
                    }
                    mResolvedInheritedFiles.addAll(libFilesToInherit);
                }
            }
        }
        if (baseApk.useEmbeddedDex) {
            for (File file : mResolvedStagedFiles) {
                if (file.getName().endsWith(".apk")
                        && !DexManager.auditUncompressedDexInApk(file.getPath())) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Some dex are not uncompressed and aligned correctly for "
                            + mPackageName);
                }
            }
        }
        if (baseApk.isSplitRequired && stagedSplits.size() <= 1) {
            throw new PackageManagerException(INSTALL_FAILED_MISSING_SPLIT,
                    "Missing split for " + mPackageName);
        }
    }

    private void resolveAndStageFile(File origFile, File targetFile)
            throws PackageManagerException {
        mResolvedStagedFiles.add(targetFile);
        maybeRenameFile(origFile, targetFile);

        final File originalSignature = new File(
                VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        // Make sure .fsv_sig exists when it should, then resolve and stage it.
        if (originalSignature.exists()) {
            // mVerityFound can only change from false to true here during the staging loop. Since
            // all or none of files should have .fsv_sig, this should only happen in the first time
            // (or never), otherwise bail out.
            if (!mVerityFound) {
                mVerityFound = true;
                if (mResolvedStagedFiles.size() > 1) {
                    throw new PackageManagerException(INSTALL_FAILED_BAD_SIGNATURE,
                            "Some file is missing fs-verity signature");
                }
            }
        } else {
            if (!mVerityFound) {
                return;
            }
            throw new PackageManagerException(INSTALL_FAILED_BAD_SIGNATURE,
                    "Missing corresponding fs-verity signature to " + origFile);
        }

        final File stagedSignature = new File(
                VerityUtils.getFsveritySignatureFilePath(targetFile.getPath()));
        maybeRenameFile(originalSignature, stagedSignature);
        mResolvedStagedFiles.add(stagedSignature);
    }

    private void resolveInheritedFile(File origFile) {
        mResolvedInheritedFiles.add(origFile);

        // Inherit the fsverity signature file if present.
        final File fsveritySignatureFile = new File(
                VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        if (fsveritySignatureFile.exists()) {
            mResolvedInheritedFiles.add(fsveritySignatureFile);
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

    /**
     * @return the package name of this session
     */
    String getPackageName() {
        synchronized (mLock) {
            return mPackageName;
        }
    }

    /**
     * @return the timestamp of when this session last changed state
     */
    public long getUpdatedMillis() {
        synchronized (mLock) {
            return updatedMillis;
        }
    }

    String getInstallerPackageName() {
        return getInstallSource().installerPackageName;
    }

    String getInstallerAttributionTag() {
        return getInstallSource().installerAttributionTag;
    }

    InstallSource getInstallSource() {
        synchronized (mLock) {
            return mInstallSource;
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

    private void extractNativeLibraries(File packageDir, String abiOverride, boolean inherit)
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
                    abiOverride, isIncrementalInstallation());
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
                mHandler.obtainMessage(MSG_INSTALL).sendToTarget();
            }
        } else {
            destroyInternal();
            dispatchSessionFinished(INSTALL_FAILED_ABORTED, "User rejected permissions", null);
        }
    }

    /**
     * Adds a child session ID without any safety / sanity checks. This should only be used to
     * build a session from XML or similar.
     */
    void addChildSessionIdInternal(int sessionId) {
        mChildSessionIds.put(sessionId, 0);
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
                } else if (params.isMultiPackage) {
                    // it's all ok
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
        if (hasParentSessionId()) {
            throw new IllegalStateException(
                    "Session " + sessionId + " is a child of multi-package session "
                            + mParentSessionId +  " and may not be abandoned directly.");
        }
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();

            if (isStagedAndInTerminalState()) {
                // We keep the session in the database if it's in a finalized state. It will be
                // removed by PackageInstallerService when the last update time is old enough.
                // Also, in such cases cleanStageDir() has already been executed so no need to
                // do it now.
                return;
            }
            if (mCommitted && params.isStaged) {
                synchronized (mLock) {
                    mDestroyed = true;
                }
                mStagingManager.abortCommittedSession(this);

                cleanStageDir();
            }

            if (mRelinquished) {
                Slog.d(TAG, "Ignoring abandon after commit relinquished control");
                return;
            }
            destroyInternal();
        }
        dispatchSessionFinished(INSTALL_FAILED_ABORTED, "Session was abandoned", null);
    }

    @Override
    public boolean isMultiPackage() {
        return params.isMultiPackage;
    }

    @Override
    public boolean isStaged() {
        return params.isStaged;
    }

    @Override
    public DataLoaderParamsParcel getDataLoaderParams() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.USE_INSTALLER_V2, null);
        return params.dataLoaderParams != null ? params.dataLoaderParams.getData() : null;
    }

    @Override
    public void addFile(int location, String name, long lengthBytes, byte[] metadata,
            byte[] signature) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.USE_INSTALLER_V2, null);
        if (!isDataLoaderInstallation()) {
            throw new IllegalStateException(
                    "Cannot add files to non-data loader installation session.");
        }
        if (isStreamingInstallation()) {
            if (location != LOCATION_DATA_APP) {
                throw new IllegalArgumentException(
                        "Non-incremental installation only supports /data/app placement: " + name);
            }
        }
        if (metadata == null) {
            throw new IllegalArgumentException(
                    "DataLoader installation requires valid metadata: " + name);
        }
        // Use installer provided name for now; we always rename later
        if (!FileUtils.isValidExtFilename(name)) {
            throw new IllegalArgumentException("Invalid name: " + name);
        }

        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("addFile");

            if (!mFiles.add(new FileEntry(mFiles.size(),
                    new InstallationFile(location, name, lengthBytes, metadata, signature)))) {
                throw new IllegalArgumentException("File already added: " + name);
            }
        }
    }

    @Override
    public void removeFile(int location, String name) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.USE_INSTALLER_V2, null);
        if (!isDataLoaderInstallation()) {
            throw new IllegalStateException(
                    "Cannot add files to non-data loader installation session.");
        }
        if (TextUtils.isEmpty(params.appPackageName)) {
            throw new IllegalStateException("Must specify package name to remove a split");
        }

        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("removeFile");

            if (!mFiles.add(new FileEntry(mFiles.size(),
                    new InstallationFile(location, getRemoveMarkerName(name), -1, null, null)))) {
                throw new IllegalArgumentException("File already removed: " + name);
            }
        }
    }

    /**
     * Makes sure files are present in staging location.
     */
    @GuardedBy("mLock")
    private boolean prepareDataLoaderLocked()
            throws PackageManagerException {
        if (!isDataLoaderInstallation()) {
            return true;
        }
        if (mDataLoaderFinished) {
            return true;
        }

        final List<InstallationFileParcel> addedFiles = new ArrayList<>();
        final List<String> removedFiles = new ArrayList<>();

        final InstallationFile[] files = getInstallationFilesLocked();
        for (InstallationFile file : files) {
            if (sAddedFilter.accept(new File(this.stageDir, file.getName()))) {
                addedFiles.add(file.getData());
                continue;
            }
            if (sRemovedFilter.accept(new File(this.stageDir, file.getName()))) {
                String name = file.getName().substring(
                        0, file.getName().length() - REMOVE_MARKER_EXTENSION.length());
                removedFiles.add(name);
            }
        }

        final DataLoaderManager dataLoaderManager = mContext.getSystemService(
                DataLoaderManager.class);
        if (dataLoaderManager == null) {
            throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failed to find data loader manager service");
        }

        final DataLoaderParams params = this.params.dataLoaderParams;
        final boolean manualStartAndDestroy = !isIncrementalInstallation();
        final IDataLoaderStatusListener listener = new IDataLoaderStatusListener.Stub() {
            @Override
            public void onStatusChanged(int dataLoaderId, int status) {
                switch (status) {
                    case IDataLoaderStatusListener.DATA_LOADER_STOPPED:
                    case IDataLoaderStatusListener.DATA_LOADER_DESTROYED:
                        return;
                }

                if (mDestroyed || mDataLoaderFinished) {
                    switch (status) {
                        case IDataLoaderStatusListener.DATA_LOADER_UNRECOVERABLE:
                            onDataLoaderUnrecoverable();
                            return;
                    }
                    return;
                }

                try {
                    IDataLoader dataLoader = dataLoaderManager.getDataLoader(dataLoaderId);
                    if (dataLoader == null) {
                        mDataLoaderFinished = true;
                        dispatchSessionVerificationFailure(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                                "Failure to obtain data loader");
                        return;
                    }

                    switch (status) {
                        case IDataLoaderStatusListener.DATA_LOADER_BOUND: {
                            if (manualStartAndDestroy) {
                                FileSystemControlParcel control = new FileSystemControlParcel();
                                control.callback = new FileSystemConnector(addedFiles);
                                dataLoader.create(dataLoaderId, params.getData(), control, this);
                            }

                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_CREATED: {
                            if (manualStartAndDestroy) {
                                // IncrementalFileStorages will call start after all files are
                                // created in IncFS.
                                dataLoader.start(dataLoaderId);
                            }
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_STARTED: {
                            dataLoader.prepareImage(
                                    dataLoaderId,
                                    addedFiles.toArray(
                                            new InstallationFileParcel[addedFiles.size()]),
                                    removedFiles.toArray(new String[removedFiles.size()]));
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_IMAGE_READY: {
                            mDataLoaderFinished = true;
                            if (hasParentSessionId()) {
                                mSessionProvider.getSession(
                                        mParentSessionId).dispatchStreamValidateAndCommit();
                            } else {
                                dispatchStreamValidateAndCommit();
                            }
                            if (manualStartAndDestroy) {
                                dataLoader.destroy(dataLoaderId);
                            }
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_IMAGE_NOT_READY: {
                            mDataLoaderFinished = true;
                            dispatchSessionVerificationFailure(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                                    "Failed to prepare image.");
                            if (manualStartAndDestroy) {
                                dataLoader.destroy(dataLoaderId);
                            }
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_UNAVAILABLE: {
                            // Don't fail or commit the session. Allow caller to commit again.
                            sendPendingStreaming(mContext, mRemoteStatusReceiver, sessionId,
                                    "DataLoader unavailable");
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_UNRECOVERABLE:
                            mDataLoaderFinished = true;
                            dispatchSessionVerificationFailure(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                                    "DataLoader reported unrecoverable failure.");
                            break;
                    }
                } catch (RemoteException e) {
                    // In case of streaming failure we don't want to fail or commit the session.
                    // Just return from this method and allow caller to commit again.
                    sendPendingStreaming(mContext, mRemoteStatusReceiver, sessionId,
                            e.getMessage());
                }
            }
        };

        if (!manualStartAndDestroy) {
            try {
                mIncrementalFileStorages = IncrementalFileStorages.initialize(mContext, stageDir,
                        params, listener, addedFiles);
                return false;
            } catch (IOException e) {
                throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE, e.getMessage(),
                        e.getCause());
            }
        }

        if (!dataLoaderManager.bindToDataLoader(
                sessionId, params.getData(), listener)) {
            throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failed to initialize data loader");
        }

        return false;
    }

    private void dispatchSessionVerificationFailure(int error, String detailMessage) {
        mHandler.obtainMessage(MSG_SESSION_VERIFICATION_FAILURE, error, -1,
                detailMessage).sendToTarget();
    }

    @Override
    public int[] getChildSessionIds() {
        final int[] childSessionIds = mChildSessionIds.copyKeys();
        if (childSessionIds != null) {
            return childSessionIds;
        }
        return EMPTY_CHILD_SESSION_ARRAY;
    }

    @Override
    public void addChildSessionId(int childSessionId) {
        final PackageInstallerSession childSession = mSessionProvider.getSession(childSessionId);
        if (childSession == null
                || (childSession.hasParentSessionId() && childSession.mParentSessionId != sessionId)
                || childSession.mCommitted
                || childSession.mDestroyed) {
            throw new IllegalStateException("Unable to add child session " + childSessionId
                            + " as it does not exist or is in an invalid state.");
        }
        synchronized (mLock) {
            assertCallerIsOwnerOrRootLocked();
            assertPreparedAndNotSealedLocked("addChildSessionId");

            final int indexOfSession = mChildSessionIds.indexOfKey(childSessionId);
            if (indexOfSession >= 0) {
                return;
            }
            childSession.setParentSessionId(this.sessionId);
            addChildSessionIdInternal(childSessionId);
        }
    }

    @Override
    public void removeChildSessionId(int sessionId) {
        final PackageInstallerSession session = mSessionProvider.getSession(sessionId);
        synchronized (mLock) {
            final int indexOfSession = mChildSessionIds.indexOfKey(sessionId);
            if (session != null) {
                session.setParentSessionId(SessionInfo.INVALID_ID);
            }
            if (indexOfSession < 0) {
                // not added in the first place; no-op
                return;
            }
            mChildSessionIds.removeAt(indexOfSession);
        }
    }

    /**
     * Sets the parent session ID if not already set.
     * If {@link SessionInfo#INVALID_ID} is passed, it will be unset.
     */
    void setParentSessionId(int parentSessionId) {
        synchronized (mLock) {
            if (parentSessionId != SessionInfo.INVALID_ID
                    && mParentSessionId != SessionInfo.INVALID_ID) {
                throw new IllegalStateException("The parent of " + sessionId + " is" + " already"
                        + "set to " + mParentSessionId);
            }
            this.mParentSessionId = parentSessionId;
        }
    }

    boolean hasParentSessionId() {
        return mParentSessionId != SessionInfo.INVALID_ID;
    }

    @Override
    public int getParentSessionId() {
        return mParentSessionId;
    }

    private void dispatchSessionFinished(int returnCode, String msg, Bundle extras) {
        final IntentSender statusReceiver;
        final String packageName;
        synchronized (mLock) {
            mFinalStatus = returnCode;
            mFinalMessage = msg;

            statusReceiver = mRemoteStatusReceiver;
            packageName = mPackageName;
        }

        if (statusReceiver != null) {
            // Execute observer.onPackageInstalled on different thread as we don't want callers
            // inside the system server have to worry about catching the callbacks while they are
            // calling into the session
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = packageName;
            args.arg2 = msg;
            args.arg3 = extras;
            args.arg4 = statusReceiver;
            args.argi1 = returnCode;

            mHandler.obtainMessage(MSG_ON_PACKAGE_INSTALLED, args).sendToTarget();
        }

        final boolean success = (returnCode == PackageManager.INSTALL_SUCCEEDED);

        // Send broadcast to default launcher only if it's a new install
        // TODO(b/144270665): Secure the usage of this broadcast.
        final boolean isNewInstall = extras == null || !extras.getBoolean(Intent.EXTRA_REPLACING);
        if (success && isNewInstall && mPm.mInstallerService.okToSendBroadcasts()
                && (params.installFlags & PackageManager.INSTALL_DRY_RUN) == 0) {
            mPm.sendSessionCommitBroadcast(generateInfoScrubbed(true /*icon*/), userId);
        }

        mCallback.onSessionFinished(this, success);
        if (isDataLoaderInstallation()) {
            logDataLoaderInstallationSession(returnCode);
        }
    }

    /** {@hide} */
    void setStagedSessionReady() {
        synchronized (mLock) {
            mStagedSessionReady = true;
            mStagedSessionApplied = false;
            mStagedSessionFailed = false;
            mStagedSessionErrorCode = SessionInfo.STAGED_SESSION_NO_ERROR;
            mStagedSessionErrorMessage = "";
        }
        mCallback.onStagedSessionChanged(this);
    }

    /** {@hide} */
    void setStagedSessionFailed(@StagedSessionErrorCode int errorCode,
                                String errorMessage) {
        synchronized (mLock) {
            mStagedSessionReady = false;
            mStagedSessionApplied = false;
            mStagedSessionFailed = true;
            mStagedSessionErrorCode = errorCode;
            mStagedSessionErrorMessage = errorMessage;
            Slog.d(TAG, "Marking session " + sessionId + " as failed: " + errorMessage);
        }
        cleanStageDir();
        mCallback.onStagedSessionChanged(this);
    }

    /** {@hide} */
    void setStagedSessionApplied() {
        synchronized (mLock) {
            mStagedSessionReady = false;
            mStagedSessionApplied = true;
            mStagedSessionFailed = false;
            mStagedSessionErrorCode = SessionInfo.STAGED_SESSION_NO_ERROR;
            mStagedSessionErrorMessage = "";
            Slog.d(TAG, "Marking session " + sessionId + " as applied");
        }
        cleanStageDir();
        mCallback.onStagedSessionChanged(this);
    }

    /** {@hide} */
    boolean isStagedSessionReady() {
        return mStagedSessionReady;
    }

    /** {@hide} */
    boolean isStagedSessionApplied() {
        return mStagedSessionApplied;
    }

    /** {@hide} */
    boolean isStagedSessionFailed() {
        return mStagedSessionFailed;
    }

    /** {@hide} */
    @StagedSessionErrorCode int getStagedSessionErrorCode() {
        return mStagedSessionErrorCode;
    }

    /** {@hide} */
    String getStagedSessionErrorMessage() {
        return mStagedSessionErrorMessage;
    }

    private void destroyInternal() {
        synchronized (mLock) {
            mSealed = true;
            if (!params.isStaged || isStagedAndInTerminalState()) {
                mDestroyed = true;
            }
            // Force shut down all bridges
            for (RevocableFileDescriptor fd : mFds) {
                fd.revoke();
            }
            for (FileBridge bridge : mBridges) {
                bridge.forceClose();
            }
        }
        if (mIncrementalFileStorages != null) {
            mIncrementalFileStorages.cleanUp();
            mIncrementalFileStorages = null;
        }
        // For staged sessions, we don't delete the directory where the packages have been copied,
        // since these packages are supposed to be read on reboot.
        // Those dirs are deleted when the staged session has reached a final state.
        if (stageDir != null && !params.isStaged) {
            try {
                mPm.mInstaller.rmPackageDir(stageDir.getAbsolutePath());
            } catch (InstallerException ignored) {
            }
        }
    }

    private void cleanStageDir() {
        if (isMultiPackage()) {
            for (int childSessionId : getChildSessionIds()) {
                mSessionProvider.getSession(childSessionId).cleanStageDir();
            }
        } else {
            if (mIncrementalFileStorages != null) {
                mIncrementalFileStorages.cleanUp();
                mIncrementalFileStorages = null;
            }
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
        pw.printPair("installerPackageName", mInstallSource.installerPackageName);
        pw.printPair("installInitiatingPackageName", mInstallSource.initiatingPackageName);
        pw.printPair("installOriginatingPackageName", mInstallSource.originatingPackageName);
        pw.printPair("mInstallerUid", mInstallerUid);
        pw.printPair("createdMillis", createdMillis);
        pw.printPair("updatedMillis", updatedMillis);
        pw.printPair("stageDir", stageDir);
        pw.printPair("stageCid", stageCid);
        pw.println();

        params.dump(pw);

        pw.printPair("mClientProgress", mClientProgress);
        pw.printPair("mProgress", mProgress);
        pw.printPair("mCommitted", mCommitted);
        pw.printPair("mSealed", mSealed);
        pw.printPair("mPermissionsManuallyAccepted", mPermissionsManuallyAccepted);
        pw.printPair("mRelinquished", mRelinquished);
        pw.printPair("mDestroyed", mDestroyed);
        pw.printPair("mFds", mFds.size());
        pw.printPair("mBridges", mBridges.size());
        pw.printPair("mFinalStatus", mFinalStatus);
        pw.printPair("mFinalMessage", mFinalMessage);
        pw.printPair("params.isMultiPackage", params.isMultiPackage);
        pw.printPair("params.isStaged", params.isStaged);
        pw.printPair("mParentSessionId", mParentSessionId);
        pw.printPair("mChildSessionIds", mChildSessionIds);
        pw.printPair("mStagedSessionApplied", mStagedSessionApplied);
        pw.printPair("mStagedSessionFailed", mStagedSessionFailed);
        pw.printPair("mStagedSessionReady", mStagedSessionReady);
        pw.printPair("mStagedSessionErrorCode", mStagedSessionErrorCode);
        pw.printPair("mStagedSessionErrorMessage", mStagedSessionErrorMessage);
        pw.println();

        pw.decreaseIndent();
    }

    private static void sendOnUserActionRequired(Context context, IntentSender target,
            int sessionId, Intent intent) {
        final Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION);
        fillIn.putExtra(Intent.EXTRA_INTENT, intent);
        try {
            target.sendIntent(context, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    private static void sendOnPackageInstalled(Context context, IntentSender target, int sessionId,
            boolean showNotification, int userId, String basePackageName, int returnCode,
            String msg, Bundle extras) {
        if (PackageManager.INSTALL_SUCCEEDED == returnCode && showNotification) {
            boolean update = (extras != null) && extras.getBoolean(Intent.EXTRA_REPLACING);
            Notification notification = PackageInstallerService.buildSuccessNotification(context,
                    context.getResources()
                            .getString(update ? R.string.package_updated_device_owner :
                                    R.string.package_installed_device_owner),
                    basePackageName,
                    userId);
            if (notification != null) {
                NotificationManager notificationManager = (NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                notificationManager.notify(basePackageName,
                        SystemMessageProto.SystemMessage.NOTE_PACKAGE_STATE,
                        notification);
            }
        }
        final Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, basePackageName);
        fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                PackageManager.installStatusToPublicStatus(returnCode));
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,
                PackageManager.installStatusToString(returnCode, msg));
        fillIn.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, returnCode);
        if (extras != null) {
            final String existing = extras.getString(
                    PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE);
            if (!TextUtils.isEmpty(existing)) {
                fillIn.putExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME, existing);
            }
        }
        try {
            target.sendIntent(context, 0, fillIn, null, null);
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    private static void sendPendingStreaming(Context context, IntentSender target, int sessionId,
            @Nullable String cause) {
        final Intent intent = new Intent();
        intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_STREAMING);
        if (!TextUtils.isEmpty(cause)) {
            intent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,
                    "Staging Image Not Ready [" + cause + "]");
        } else {
            intent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "Staging Image Not Ready");
        }
        try {
            target.sendIntent(context, 0, intent, null, null);
        } catch (IntentSender.SendIntentException ignored) {
        }
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

    private static void writeWhitelistedRestrictedPermissionsLocked(@NonNull XmlSerializer out,
            @Nullable List<String> whitelistedRestrictedPermissions) throws IOException {
        if (whitelistedRestrictedPermissions != null) {
            final int permissionCount = whitelistedRestrictedPermissions.size();
            for (int i = 0; i < permissionCount; i++) {
                out.startTag(null, TAG_WHITELISTED_RESTRICTED_PERMISSION);
                writeStringAttribute(out, ATTR_NAME, whitelistedRestrictedPermissions.get(i));
                out.endTag(null, TAG_WHITELISTED_RESTRICTED_PERMISSION);
            }
        }
    }

    private static void writeAutoRevokePermissionsMode(@NonNull XmlSerializer out, int mode)
            throws IOException {
        out.startTag(null, TAG_AUTO_REVOKE_PERMISSIONS_MODE);
        writeIntAttribute(out, ATTR_MODE, mode);
        out.endTag(null, TAG_AUTO_REVOKE_PERMISSIONS_MODE);
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
                    mInstallSource.installerPackageName);
            writeStringAttribute(out, ATTR_INSTALLER_ATTRIBUTION_TAG,
                    mInstallSource.installerAttributionTag);
            writeIntAttribute(out, ATTR_INSTALLER_UID, mInstallerUid);
            writeStringAttribute(out, ATTR_INITIATING_PACKAGE_NAME,
                    mInstallSource.initiatingPackageName);
            writeStringAttribute(out, ATTR_ORIGINATING_PACKAGE_NAME,
                    mInstallSource.originatingPackageName);
            writeLongAttribute(out, ATTR_CREATED_MILLIS, createdMillis);
            writeLongAttribute(out, ATTR_UPDATED_MILLIS, updatedMillis);
            if (stageDir != null) {
                writeStringAttribute(out, ATTR_SESSION_STAGE_DIR,
                        stageDir.getAbsolutePath());
            }
            if (stageCid != null) {
                writeStringAttribute(out, ATTR_SESSION_STAGE_CID, stageCid);
            }
            writeBooleanAttribute(out, ATTR_PREPARED, isPrepared());
            writeBooleanAttribute(out, ATTR_COMMITTED, isCommitted());
            writeBooleanAttribute(out, ATTR_SEALED, isSealed());

            writeBooleanAttribute(out, ATTR_MULTI_PACKAGE, params.isMultiPackage);
            writeBooleanAttribute(out, ATTR_STAGED_SESSION, params.isStaged);
            writeBooleanAttribute(out, ATTR_IS_READY, mStagedSessionReady);
            writeBooleanAttribute(out, ATTR_IS_FAILED, mStagedSessionFailed);
            writeBooleanAttribute(out, ATTR_IS_APPLIED, mStagedSessionApplied);
            writeIntAttribute(out, ATTR_STAGED_SESSION_ERROR_CODE, mStagedSessionErrorCode);
            writeStringAttribute(out, ATTR_STAGED_SESSION_ERROR_MESSAGE,
                    mStagedSessionErrorMessage);
            // TODO(patb,109941548): avoid writing to xml and instead infer / validate this after
            //                       we've read all sessions.
            writeIntAttribute(out, ATTR_PARENT_SESSION_ID, mParentSessionId);
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

            final boolean isDataLoader = params.dataLoaderParams != null;
            writeBooleanAttribute(out, ATTR_IS_DATALOADER, isDataLoader);
            if (isDataLoader) {
                writeIntAttribute(out, ATTR_DATALOADER_TYPE, params.dataLoaderParams.getType());
                writeStringAttribute(out, ATTR_DATALOADER_PACKAGE_NAME,
                        params.dataLoaderParams.getComponentName().getPackageName());
                writeStringAttribute(out, ATTR_DATALOADER_CLASS_NAME,
                        params.dataLoaderParams.getComponentName().getClassName());
                writeStringAttribute(out, ATTR_DATALOADER_ARGUMENTS,
                        params.dataLoaderParams.getArguments());
            }

            writeGrantedRuntimePermissionsLocked(out, params.grantedRuntimePermissions);
            writeWhitelistedRestrictedPermissionsLocked(out,
                    params.whitelistedRestrictedPermissions);
            writeAutoRevokePermissionsMode(out, params.autoRevokePermissionsMode);

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
            final int[] childSessionIds = getChildSessionIds();
            for (int childSessionId : childSessionIds) {
                out.startTag(null, TAG_CHILD_SESSION);
                writeIntAttribute(out, ATTR_SESSION_ID, childSessionId);
                out.endTag(null, TAG_CHILD_SESSION);
            }

            final InstallationFile[] files = getInstallationFilesLocked();
            for (InstallationFile file : getInstallationFilesLocked()) {
                out.startTag(null, TAG_SESSION_FILE);
                writeIntAttribute(out, ATTR_LOCATION, file.getLocation());
                writeStringAttribute(out, ATTR_NAME, file.getName());
                writeLongAttribute(out, ATTR_LENGTH_BYTES, file.getLengthBytes());
                writeByteArrayAttribute(out, ATTR_METADATA, file.getMetadata());
                writeByteArrayAttribute(out, ATTR_SIGNATURE, file.getSignature());
                out.endTag(null, TAG_SESSION_FILE);
            }
        }

        out.endTag(null, TAG_SESSION);
    }

    // Sanity check to be performed when the session is restored from an external file. Only one
    // of the session states should be true, or none of them.
    private static boolean isStagedSessionStateValid(boolean isReady, boolean isApplied,
                                                     boolean isFailed) {
        return (!isReady && !isApplied && !isFailed)
                || (isReady && !isApplied && !isFailed)
                || (!isReady && isApplied && !isFailed)
                || (!isReady && !isApplied && isFailed);
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
     * @param sessionProvider
     * @return The newly created session
     */
    public static PackageInstallerSession readFromXml(@NonNull XmlPullParser in,
            @NonNull PackageInstallerService.InternalCallback callback, @NonNull Context context,
            @NonNull PackageManagerService pm, Looper installerThread,
            @NonNull StagingManager stagingManager, @NonNull File sessionsDir,
            @NonNull PackageSessionProvider sessionProvider)
            throws IOException, XmlPullParserException {
        final int sessionId = readIntAttribute(in, ATTR_SESSION_ID);
        final int userId = readIntAttribute(in, ATTR_USER_ID);
        final String installerPackageName = readStringAttribute(in, ATTR_INSTALLER_PACKAGE_NAME);
        final String installerAttributionTag = readStringAttribute(in,
                ATTR_INSTALLER_ATTRIBUTION_TAG);
        final int installerUid = readIntAttribute(in, ATTR_INSTALLER_UID, pm.getPackageUid(
                installerPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, userId));
        final String installInitiatingPackageName =
                readStringAttribute(in, ATTR_INITIATING_PACKAGE_NAME);
        final String installOriginatingPackageName =
                readStringAttribute(in, ATTR_ORIGINATING_PACKAGE_NAME);
        final long createdMillis = readLongAttribute(in, ATTR_CREATED_MILLIS);
        long updatedMillis = readLongAttribute(in, ATTR_UPDATED_MILLIS);
        final String stageDirRaw = readStringAttribute(in, ATTR_SESSION_STAGE_DIR);
        final File stageDir = (stageDirRaw != null) ? new File(stageDirRaw) : null;
        final String stageCid = readStringAttribute(in, ATTR_SESSION_STAGE_CID);
        final boolean prepared = readBooleanAttribute(in, ATTR_PREPARED, true);
        final boolean committed = readBooleanAttribute(in, ATTR_COMMITTED);
        final boolean sealed = readBooleanAttribute(in, ATTR_SEALED);
        final int parentSessionId = readIntAttribute(in, ATTR_PARENT_SESSION_ID,
                SessionInfo.INVALID_ID);

        final SessionParams params = new SessionParams(
                SessionParams.MODE_INVALID);
        params.isMultiPackage = readBooleanAttribute(in, ATTR_MULTI_PACKAGE, false);
        params.isStaged = readBooleanAttribute(in, ATTR_STAGED_SESSION, false);
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

        if (readBooleanAttribute(in, ATTR_IS_DATALOADER)) {
            params.dataLoaderParams = new DataLoaderParams(
                    readIntAttribute(in, ATTR_DATALOADER_TYPE),
                    new ComponentName(
                            readStringAttribute(in, ATTR_DATALOADER_PACKAGE_NAME),
                            readStringAttribute(in, ATTR_DATALOADER_CLASS_NAME)),
                    readStringAttribute(in, ATTR_DATALOADER_ARGUMENTS));
        }

        final File appIconFile = buildAppIconFile(sessionId, sessionsDir);
        if (appIconFile.exists()) {
            params.appIcon = BitmapFactory.decodeFile(appIconFile.getAbsolutePath());
            params.appIconLastModified = appIconFile.lastModified();
        }
        final boolean isReady = readBooleanAttribute(in, ATTR_IS_READY);
        final boolean isFailed = readBooleanAttribute(in, ATTR_IS_FAILED);
        final boolean isApplied = readBooleanAttribute(in, ATTR_IS_APPLIED);
        final int stagedSessionErrorCode = readIntAttribute(in, ATTR_STAGED_SESSION_ERROR_CODE,
                SessionInfo.STAGED_SESSION_NO_ERROR);
        final String stagedSessionErrorMessage = readStringAttribute(in,
                ATTR_STAGED_SESSION_ERROR_MESSAGE);

        if (!isStagedSessionStateValid(isReady, isApplied, isFailed)) {
            throw new IllegalArgumentException("Can't restore staged session with invalid state.");
        }

        // Parse sub tags of this session, typically used for repeated values / arrays.
        // Sub tags can come in any order, therefore we need to keep track of what we find while
        // parsing and only set the right values at the end.

        // Store the current depth. We should stop parsing when we reach an end tag at the same
        // depth.
        List<String> grantedRuntimePermissions = new ArrayList<>();
        List<String> whitelistedRestrictedPermissions = new ArrayList<>();
        int autoRevokePermissionsMode = MODE_DEFAULT;
        List<Integer> childSessionIds = new ArrayList<>();
        List<InstallationFile> files = new ArrayList<>();
        int outerDepth = in.getDepth();
        int type;
        while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || in.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (TAG_GRANTED_RUNTIME_PERMISSION.equals(in.getName())) {
                grantedRuntimePermissions.add(readStringAttribute(in, ATTR_NAME));
            }
            if (TAG_WHITELISTED_RESTRICTED_PERMISSION.equals(in.getName())) {
                whitelistedRestrictedPermissions.add(readStringAttribute(in, ATTR_NAME));

            }
            if (TAG_AUTO_REVOKE_PERMISSIONS_MODE.equals(in.getName())) {
                autoRevokePermissionsMode = readIntAttribute(in, ATTR_MODE);
            }
            if (TAG_CHILD_SESSION.equals(in.getName())) {
                childSessionIds.add(readIntAttribute(in, ATTR_SESSION_ID, SessionInfo.INVALID_ID));
            }
            if (TAG_SESSION_FILE.equals(in.getName())) {
                files.add(new InstallationFile(
                        readIntAttribute(in, ATTR_LOCATION, 0),
                        readStringAttribute(in, ATTR_NAME),
                        readLongAttribute(in, ATTR_LENGTH_BYTES, -1),
                        readByteArrayAttribute(in, ATTR_METADATA),
                        readByteArrayAttribute(in, ATTR_SIGNATURE)));
            }
        }

        if (grantedRuntimePermissions.size() > 0) {
            params.grantedRuntimePermissions =
                    grantedRuntimePermissions.toArray(EmptyArray.STRING);
        }

        if (whitelistedRestrictedPermissions.size() > 0) {
            params.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
        }

        params.autoRevokePermissionsMode = autoRevokePermissionsMode;

        int[] childSessionIdsArray;
        if (childSessionIds.size() > 0) {
            childSessionIdsArray = new int[childSessionIds.size()];
            for (int i = 0, size = childSessionIds.size(); i < size; ++i) {
                childSessionIdsArray[i] = childSessionIds.get(i);
            }
        } else {
            childSessionIdsArray = EMPTY_CHILD_SESSION_ARRAY;
        }

        InstallationFile[] fileArray = null;
        if (!files.isEmpty()) {
            fileArray = files.toArray(EMPTY_INSTALLATION_FILE_ARRAY);
        }

        InstallSource installSource = InstallSource.create(installInitiatingPackageName,
                installOriginatingPackageName, installerPackageName, installerAttributionTag);
        return new PackageInstallerSession(callback, context, pm, sessionProvider,
                installerThread, stagingManager, sessionId, userId, installerUid,
                installSource, params, createdMillis, stageDir, stageCid, fileArray,
                prepared, committed, sealed, childSessionIdsArray, parentSessionId,
                isReady, isFailed, isApplied, stagedSessionErrorCode, stagedSessionErrorMessage);
    }
}
