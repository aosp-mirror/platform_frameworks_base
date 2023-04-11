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
import static android.app.admin.DevicePolicyResources.Strings.Core.PACKAGE_INSTALLED_BY_DO;
import static android.app.admin.DevicePolicyResources.Strings.Core.PACKAGE_UPDATED_BY_DO;
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
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_WRONLY;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.internal.util.XmlUtils.readBitmapAttribute;
import static com.android.internal.util.XmlUtils.readByteArrayAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.readUriAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeByteArrayAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.internal.util.XmlUtils.writeUriAttribute;
import static com.android.server.pm.PackageInstallerService.prepareStageDir;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.Checksum;
import android.content.pm.DataLoaderManager;
import android.content.pm.DataLoaderParams;
import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoader;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstallerSession;
import android.content.pm.IPackageInstallerSessionFileSystemConnector;
import android.content.pm.IPackageLoadingProgressCallback;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstallationFile;
import android.content.pm.InstallationFileParcel;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
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
import android.os.SELinux;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.incremental.IStorageHealthListener;
import android.os.incremental.IncrementalFileStorages;
import android.os.incremental.IncrementalManager;
import android.os.incremental.PerUidReadTimeouts;
import android.os.incremental.StorageHealthCheckParams;
import android.os.storage.StorageManager;
import android.provider.Settings.Secure;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.system.ErrnoException;
import android.system.Int64Ref;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.IntArray;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.os.SomeArgs;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.parsing.ParsingPackageUtils;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class PackageInstallerSession extends IPackageInstallerSession.Stub {
    private static final String TAG = "PackageInstallerSession";
    private static final boolean LOGD = true;
    private static final String REMOVE_MARKER_EXTENSION = ".removed";

    private static final int MSG_ON_SESSION_SEALED = 1;
    private static final int MSG_STREAM_VALIDATE_AND_COMMIT = 2;
    private static final int MSG_INSTALL = 3;
    private static final int MSG_ON_PACKAGE_INSTALLED = 4;
    private static final int MSG_SESSION_VALIDATION_FAILURE = 5;

    /** XML constants used for persisting a session */
    static final String TAG_SESSION = "session";
    static final String TAG_CHILD_SESSION = "childSession";
    static final String TAG_SESSION_FILE = "sessionFile";
    static final String TAG_SESSION_CHECKSUM = "sessionChecksum";
    static final String TAG_SESSION_CHECKSUM_SIGNATURE = "sessionChecksumSignature";
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
    private static final String ATTR_COMMITTED_MILLIS = "committedMillis";
    private static final String ATTR_SESSION_STAGE_DIR = "sessionStageDir";
    private static final String ATTR_SESSION_STAGE_CID = "sessionStageCid";
    private static final String ATTR_PREPARED = "prepared";
    private static final String ATTR_COMMITTED = "committed";
    private static final String ATTR_DESTROYED = "destroyed";
    private static final String ATTR_SEALED = "sealed";
    private static final String ATTR_MULTI_PACKAGE = "multiPackage";
    private static final String ATTR_PARENT_SESSION_ID = "parentSessionId";
    private static final String ATTR_STAGED_SESSION = "stagedSession";
    private static final String ATTR_IS_READY = "isReady";
    private static final String ATTR_IS_FAILED = "isFailed";
    private static final String ATTR_IS_APPLIED = "isApplied";
    private static final String ATTR_PACKAGE_SOURCE = "packageSource";
    private static final String ATTR_SESSION_ERROR_CODE = "errorCode";
    private static final String ATTR_SESSION_ERROR_MESSAGE = "errorMessage";
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
    private static final String ATTR_CHECKSUM_KIND = "checksumKind";
    private static final String ATTR_CHECKSUM_VALUE = "checksumValue";

    private static final String PROPERTY_NAME_INHERIT_NATIVE = "pi.inherit_native_on_dont_kill";
    private static final int[] EMPTY_CHILD_SESSION_ARRAY = EmptyArray.INT;
    private static final InstallationFile[] EMPTY_INSTALLATION_FILE_ARRAY = {};

    private static final String SYSTEM_DATA_LOADER_PACKAGE = "android";
    private static final String APEX_FILE_EXTENSION = ".apex";

    private static final int INCREMENTAL_STORAGE_BLOCKED_TIMEOUT_MS = 2000;
    private static final int INCREMENTAL_STORAGE_UNHEALTHY_TIMEOUT_MS = 7000;
    private static final int INCREMENTAL_STORAGE_UNHEALTHY_MONITORING_MS = 60000;

    /**
     * The default value of {@link #mValidatedTargetSdk} is {@link Integer#MAX_VALUE}. If {@link
     * #mValidatedTargetSdk} is compared with {@link Build.VERSION_CODES#R} before getting the
     * target sdk version from a validated apk in {@link #validateApkInstallLocked()}, the compared
     * result will not trigger any user action in
     * {@link #checkUserActionRequirement(PackageInstallerSession)}.
     */
    private static final int INVALID_TARGET_SDK_VERSION = Integer.MAX_VALUE;

    // TODO: enforce INSTALL_ALLOW_TEST
    // TODO: enforce INSTALL_ALLOW_DOWNGRADE

    private final PackageInstallerService.InternalCallback mCallback;
    private final Context mContext;
    private final PackageManagerService mPm;
    private final Installer mInstaller;
    private final Handler mHandler;
    private final PackageSessionProvider mSessionProvider;
    private final SilentUpdatePolicy mSilentUpdatePolicy;
    /**
     * Note all calls must be done outside {@link #mLock} to prevent lock inversion.
     */
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

    /**
     * Used to detect and reject concurrent access to this session object to ensure mutation
     * to multiple objects like {@link #addChildSessionId} are done atomically.
     */
    private final AtomicBoolean mTransactionLock = new AtomicBoolean(false);

    /** Timestamp of the last time this session changed state  */
    @GuardedBy("mLock")
    private long updatedMillis;

    /** Timestamp of the time this session is committed  */
    @GuardedBy("mLock")
    private long committedMillis;

    /** Uid of the creator of this session. */
    private final int mOriginalInstallerUid;

    /** Package name of the app that created the installation session. */
    private final String mOriginalInstallerPackageName;

    /** Uid of the owner of the installer session */
    private volatile int mInstallerUid;

    /** Where this install request came from */
    @GuardedBy("mLock")
    private InstallSource mInstallSource;

    private final Object mProgressLock = new Object();

    @GuardedBy("mProgressLock")
    private float mClientProgress = 0;
    @GuardedBy("mProgressLock")
    private float mInternalProgress = 0;

    @GuardedBy("mProgressLock")
    private float mProgress = 0;
    @GuardedBy("mProgressLock")
    private float mReportedProgress = -1;
    @GuardedBy("mProgressLock")
    private float mIncrementalProgress = 0;

    /** State of the session. */
    @GuardedBy("mLock")
    private boolean mPrepared = false;
    @GuardedBy("mLock")
    private boolean mSealed = false;
    @GuardedBy("mLock")
    private boolean mShouldBeSealed = false;

    private final AtomicBoolean mCommitted = new AtomicBoolean(false);

    /**
     * True if staging files are being used by external entities like {@link PackageSessionVerifier}
     * or {@link PackageManagerService} which means it is not safe for {@link #abandon()} to clean
     * up the files.
     */
    @GuardedBy("mLock")
    private boolean mStageDirInUse = false;

    /**
     * True if the verification is already in progress. This is used to prevent running
     * verification again while one is already in progress which will break internal states.
     *
     * Worker thread only.
     */
    private boolean mVerificationInProgress = false;

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
    private SigningDetails mSigningDetails;
    @GuardedBy("mLock")
    private SparseArray<PackageInstallerSession> mChildSessions = new SparseArray<>();
    @GuardedBy("mLock")
    private int mParentSessionId;

    @GuardedBy("mLock")
    private boolean mHasDeviceAdminReceiver;

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

    static class PerFileChecksum {
        private final Checksum[] mChecksums;
        private final byte[] mSignature;

        PerFileChecksum(Checksum[] checksums, byte[] signature) {
            mChecksums = checksums;
            mSignature = signature;
        }

        Checksum[] getChecksums() {
            return this.mChecksums;
        }

        byte[] getSignature() {
            return this.mSignature;
        }
    }

    @GuardedBy("mLock")
    private ArrayMap<String, PerFileChecksum> mChecksums = new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mSessionApplied;
    @GuardedBy("mLock")
    private boolean mSessionReady;
    @GuardedBy("mLock")
    private boolean mSessionFailed;
    @GuardedBy("mLock")
    private int mSessionErrorCode = PackageManager.INSTALL_UNKNOWN;
    @GuardedBy("mLock")
    private String mSessionErrorMessage;

    @Nullable
    final StagedSession mStagedSession;

    /**
     * The callback to run when pre-reboot verification has ended. Used by {@link #abandon()}
     * to delay session clean-up until it is safe to do so.
     */
    @GuardedBy("mLock")
    @Nullable
    private Runnable mPendingAbandonCallback;

    @VisibleForTesting
    public class StagedSession implements StagingManager.StagedSession {
        @Override
        public List<StagingManager.StagedSession> getChildSessions() {
            if (!params.isMultiPackage) {
                return Collections.EMPTY_LIST;
            }
            synchronized (mLock) {
                int size = mChildSessions.size();
                List<StagingManager.StagedSession> childSessions = new ArrayList<>(size);
                for (int i = 0; i < size; ++i) {
                    childSessions.add(mChildSessions.valueAt(i).mStagedSession);
                }
                return childSessions;
            }
        }

        @Override
        public SessionParams sessionParams() {
            return params;
        }

        @Override
        public boolean isMultiPackage() {
            return params.isMultiPackage;
        }

        @Override
        public boolean isApexSession() {
            return (params.installFlags & PackageManager.INSTALL_APEX) != 0;
        }

        @Override
        public int sessionId() {
            return sessionId;
        }

        @Override
        public boolean containsApexSession() {
            return sessionContains((s) -> s.isApexSession());
        }

        @Override
        public String getPackageName() {
            return PackageInstallerSession.this.getPackageName();
        }

        @Override
        public void setSessionReady() {
            PackageInstallerSession.this.setSessionReady();
        }

        @Override
        public void setSessionFailed(int errorCode, String errorMessage) {
            PackageInstallerSession.this.setSessionFailed(errorCode, errorMessage);
        }

        @Override
        public void setSessionApplied() {
            PackageInstallerSession.this.setSessionApplied();
        }

        @Override
        public boolean containsApkSession() {
            return PackageInstallerSession.this.containsApkSession();
        }

        /**
         * Installs apks of staged session while skipping the verification process for a committed
         * and ready session.
         *
         * @return a CompletableFuture that will be completed when installation completes.
         */
        @Override
        public CompletableFuture<Void> installSession() {
            assertCallerIsOwnerOrRootOrSystem();
            assertNotChild("StagedSession#installSession");
            Preconditions.checkArgument(isCommitted() && isSessionReady());
            return install();
        }

        @Override
        public boolean hasParentSessionId() {
            return PackageInstallerSession.this.hasParentSessionId();
        }

        @Override
        public int getParentSessionId() {
            return PackageInstallerSession.this.getParentSessionId();
        }

        @Override
        public boolean isCommitted() {
            return PackageInstallerSession.this.isCommitted();
        }

        @Override
        public boolean isInTerminalState() {
            return PackageInstallerSession.this.isInTerminalState();
        }

        @Override
        public boolean isDestroyed() {
            return PackageInstallerSession.this.isDestroyed();
        }

        @Override
        public long getCommittedMillis() {
            return PackageInstallerSession.this.getCommittedMillis();
        }

        @Override
        public boolean sessionContains(Predicate<StagingManager.StagedSession> filter) {
            return PackageInstallerSession.this.sessionContains(s -> filter.test(s.mStagedSession));
        }

        @Override
        public boolean isSessionReady() {
            return PackageInstallerSession.this.isSessionReady();
        }

        @Override
        public boolean isSessionApplied() {
            return PackageInstallerSession.this.isSessionApplied();
        }

        @Override
        public boolean isSessionFailed() {
            return PackageInstallerSession.this.isSessionFailed();
        }

        @Override
        public void abandon() {
            PackageInstallerSession.this.abandon();
        }

        /**
         * Resumes verification process for non-final committed staged session.
         *
         * Useful if a device gets rebooted before verification is complete and we need to restart
         * the verification.
         */
        @Override
        public void verifySession() {
            assertCallerIsOwnerOrRootOrSystem();
            Preconditions.checkArgument(isCommitted());
            Preconditions.checkArgument(!isInTerminalState());
            verify();
        }
    }

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
    private boolean mVerityFoundForApks;

    /**
     * Both flags should be guarded with mLock whenever changes need to be in lockstep.
     * Ok to check without mLock in case the proper check is done later, e.g. status callbacks
     * for DataLoaders with deferred processing.
     */
    private volatile boolean mDestroyed = false;
    private volatile boolean mDataLoaderFinished = false;

    @GuardedBy("mLock")
    private IncrementalFileStorages mIncrementalFileStorages;

    @GuardedBy("mLock")
    private PackageLite mPackageLite;

    /**
     * Keep the target sdk of a validated apk.
     */
    @GuardedBy("mLock")
    private int mValidatedTargetSdk = INVALID_TARGET_SDK_VERSION;

    private static final FileFilter sAddedApkFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) return false;
            if (file.getName().endsWith(REMOVE_MARKER_EXTENSION)) return false;
            if (DexMetadataHelper.isDexMetadataFile(file)) return false;
            if (VerityUtils.isFsveritySignatureFile(file)) return false;
            if (ApkChecksums.isDigestOrDigestSignatureFile(file)) return false;
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

    static boolean isDataLoaderInstallation(SessionParams params) {
        return params.dataLoaderParams != null;
    }

    static boolean isSystemDataLoaderInstallation(SessionParams params) {
        if (!isDataLoaderInstallation(params)) {
            return false;
        }
        return SYSTEM_DATA_LOADER_PACKAGE.equals(
                params.dataLoaderParams.getComponentName().getPackageName());
    }

    private final Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_SESSION_SEALED:
                    handleSessionSealed();
                    break;
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
                            isInstallerDeviceOwnerOrAffiliatedProfileOwner(), userId,
                            packageName, returnCode, message, extras);

                    break;
                case MSG_SESSION_VALIDATION_FAILURE:
                    final int error = msg.arg1;
                    final String detailMessage = (String) msg.obj;
                    onSessionValidationFailure(error, detailMessage);
                    break;
            }

            return true;
        }
    };

    private boolean isDataLoaderInstallation() {
        return isDataLoaderInstallation(this.params);
    }

    private boolean isStreamingInstallation() {
        return isDataLoaderInstallation() && params.dataLoaderParams.getType() == STREAMING;
    }

    private boolean isIncrementalInstallation() {
        return isDataLoaderInstallation() && params.dataLoaderParams.getType() == INCREMENTAL;
    }

    private boolean isSystemDataLoaderInstallation() {
        return isSystemDataLoaderInstallation(this.params);
    }

    /**
     * @return {@code true} iff the installing is app an device owner or affiliated profile owner.
     */
    private boolean isInstallerDeviceOwnerOrAffiliatedProfileOwner() {
        assertNotLocked("isInstallerDeviceOwnerOrAffiliatedProfileOwner");
        // It is safe to access mInstallerUid and mInstallSource without lock
        // because they are immutable after sealing.
        assertSealed("isInstallerDeviceOwnerOrAffiliatedProfileOwner");
        if (userId != UserHandle.getUserId(mInstallerUid)) {
            return false;
        }
        DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        // It may wait for a long time to finish {@code dpmi.canSilentlyInstallPackage}.
        // Please don't acquire mLock before calling {@code dpmi.canSilentlyInstallPackage}.
        return dpmi != null && dpmi.canSilentlyInstallPackage(
                getInstallSource().installerPackageName, mInstallerUid);
    }

    private static final int USER_ACTION_NOT_NEEDED = 0;
    private static final int USER_ACTION_REQUIRED = 1;
    private static final int USER_ACTION_PENDING_APK_PARSING = 2;

    @IntDef({USER_ACTION_NOT_NEEDED, USER_ACTION_REQUIRED, USER_ACTION_PENDING_APK_PARSING})
    @interface
    UserActionRequirement {}

    /**
     * Checks if the permissions still need to be confirmed.
     *
     * <p>This is dependant on the identity of the installer, hence this cannot be cached if the
     * installer might still {@link #transfer(String) change}.
     *
     * @return {@code true} iff we need to ask to confirm the permissions?
     */
    @UserActionRequirement
    private int computeUserActionRequirement() {
        final String packageName;
        final boolean hasDeviceAdminReceiver;
        synchronized (mLock) {
            if (mPermissionsManuallyAccepted) {
                return USER_ACTION_NOT_NEEDED;
            }
            packageName = mPackageName;
            hasDeviceAdminReceiver = mHasDeviceAdminReceiver;
        }

        final boolean forcePermissionPrompt =
                (params.installFlags & PackageManager.INSTALL_FORCE_PERMISSION_PROMPT) != 0
                        || params.requireUserAction == SessionParams.USER_ACTION_REQUIRED;
        if (forcePermissionPrompt) {
            return USER_ACTION_REQUIRED;
        }
        // It is safe to access mInstallerUid and mInstallSource without lock
        // because they are immutable after sealing.
        final Computer snapshot = mPm.snapshotComputer();
        final boolean isInstallPermissionGranted =
                (snapshot.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final boolean isSelfUpdatePermissionGranted =
                (snapshot.checkUidPermission(android.Manifest.permission.INSTALL_SELF_UPDATES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final boolean isUpdatePermissionGranted =
                (snapshot.checkUidPermission(android.Manifest.permission.INSTALL_PACKAGE_UPDATES,
                        mInstallerUid) == PackageManager.PERMISSION_GRANTED);
        final boolean isUpdateWithoutUserActionPermissionGranted = (snapshot.checkUidPermission(
                android.Manifest.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION, mInstallerUid)
                == PackageManager.PERMISSION_GRANTED);
        final boolean isInstallDpcPackagesPermissionGranted = (snapshot.checkUidPermission(
                android.Manifest.permission.INSTALL_DPC_PACKAGES, mInstallerUid)
                == PackageManager.PERMISSION_GRANTED);
        final int targetPackageUid = snapshot.getPackageUid(packageName, 0, userId);
        final boolean isUpdate = targetPackageUid != -1 || isApexSession();
        final InstallSourceInfo existingInstallSourceInfo = isUpdate
                ? snapshot.getInstallSourceInfo(packageName)
                : null;
        final String existingInstallerPackageName = existingInstallSourceInfo != null
                ? existingInstallSourceInfo.getInstallingPackageName()
                : null;
        final boolean isInstallerOfRecord = isUpdate
                && Objects.equals(existingInstallerPackageName, getInstallerPackageName());
        final boolean isSelfUpdate = targetPackageUid == mInstallerUid;
        final boolean isPermissionGranted = isInstallPermissionGranted
                || (isUpdatePermissionGranted && isUpdate)
                || (isSelfUpdatePermissionGranted && isSelfUpdate)
                || (isInstallDpcPackagesPermissionGranted && hasDeviceAdminReceiver);
        final boolean isInstallerRoot = (mInstallerUid == Process.ROOT_UID);
        final boolean isInstallerSystem = (mInstallerUid == Process.SYSTEM_UID);

        // Device owners and affiliated profile owners  are allowed to silently install packages, so
        // the permission check is waived if the installer is the device owner.
        final boolean noUserActionNecessary = isPermissionGranted || isInstallerRoot
                || isInstallerSystem || isInstallerDeviceOwnerOrAffiliatedProfileOwner();

        if (noUserActionNecessary) {
            return USER_ACTION_NOT_NEEDED;
        }

        if (snapshot.isInstallDisabledForPackage(getInstallerPackageName(), mInstallerUid,
                userId)) {
            // show the installer to account for device poslicy or unknown sources use cases
            return USER_ACTION_REQUIRED;
        }

        if (params.requireUserAction == SessionParams.USER_ACTION_NOT_REQUIRED
                && isUpdateWithoutUserActionPermissionGranted
                && (isInstallerOfRecord || isSelfUpdate)) {
            return USER_ACTION_PENDING_APK_PARSING;
        }

        return USER_ACTION_REQUIRED;
    }

    @SuppressWarnings("GuardedBy" /*mPm.mInstaller is {@code final} field*/)
    public PackageInstallerSession(PackageInstallerService.InternalCallback callback,
            Context context, PackageManagerService pm,
            PackageSessionProvider sessionProvider,
            SilentUpdatePolicy silentUpdatePolicy, Looper looper, StagingManager stagingManager,
            int sessionId, int userId, int installerUid, @NonNull InstallSource installSource,
            SessionParams params, long createdMillis, long committedMillis,
            File stageDir, String stageCid, InstallationFile[] files,
            ArrayMap<String, PerFileChecksum> checksums,
            boolean prepared, boolean committed, boolean destroyed, boolean sealed,
            @Nullable int[] childSessionIds, int parentSessionId, boolean isReady,
            boolean isFailed, boolean isApplied, int sessionErrorCode,
            String sessionErrorMessage) {
        mCallback = callback;
        mContext = context;
        mPm = pm;
        mInstaller = (mPm != null) ? mPm.mInstaller : null;
        mSessionProvider = sessionProvider;
        mSilentUpdatePolicy = silentUpdatePolicy;
        mHandler = new Handler(looper, mHandlerCallback);
        mStagingManager = stagingManager;

        this.sessionId = sessionId;
        this.userId = userId;
        mOriginalInstallerUid = installerUid;
        mInstallerUid = installerUid;
        mInstallSource = Objects.requireNonNull(installSource);
        mOriginalInstallerPackageName = mInstallSource.installerPackageName;
        this.params = params;
        this.createdMillis = createdMillis;
        this.updatedMillis = createdMillis;
        this.committedMillis = committedMillis;
        this.stageDir = stageDir;
        this.stageCid = stageCid;
        this.mShouldBeSealed = sealed;
        if (childSessionIds != null) {
            for (int childSessionId : childSessionIds) {
                // Null values will be resolved to actual object references in
                // #onAfterSessionRead later.
                mChildSessions.put(childSessionId, null);
            }
        }
        this.mParentSessionId = parentSessionId;

        if (files != null) {
            mFiles.ensureCapacity(files.length);
            for (int i = 0, size = files.length; i < size; ++i) {
                InstallationFile file = files[i];
                if (!mFiles.add(new FileEntry(i, file))) {
                    throw new IllegalArgumentException(
                            "Trying to add a duplicate installation file");
                }
            }
        }

        if (checksums != null) {
            mChecksums.putAll(checksums);
        }

        if (!params.isMultiPackage && (stageDir == null) == (stageCid == null)) {
            throw new IllegalArgumentException(
                    "Exactly one of stageDir or stageCid stage must be set");
        }

        mPrepared = prepared;
        mCommitted.set(committed);
        mDestroyed = destroyed;
        mSessionReady = isReady;
        mSessionApplied = isApplied;
        mSessionFailed = isFailed;
        mSessionErrorCode = sessionErrorCode;
        mSessionErrorMessage =
                sessionErrorMessage != null ? sessionErrorMessage : "";
        mStagedSession = params.isStaged ? new StagedSession() : null;

        if (isDataLoaderInstallation()) {
            if (isApexSession()) {
                throw new IllegalArgumentException(
                        "DataLoader installation of APEX modules is not allowed.");
            }

            if (isSystemDataLoaderInstallation() && mContext.checkCallingOrSelfPermission(
                    Manifest.permission.USE_SYSTEM_DATA_LOADERS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("You need the "
                        + "com.android.permission.USE_SYSTEM_DATA_LOADERS permission "
                        + "to use system data loaders");
            }
        }

        if (isIncrementalInstallation() && !IncrementalManager.isAllowed()) {
            throw new IllegalArgumentException("Incremental installation not allowed.");
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
        final float progress;
        synchronized (mProgressLock) {
            progress = mProgress;
        }
        synchronized (mLock) {
            info.sessionId = sessionId;
            info.userId = userId;
            info.installerPackageName = mInstallSource.installerPackageName;
            info.installerAttributionTag = mInstallSource.installerAttributionTag;
            info.resolvedBaseCodePath = (mResolvedBaseFile != null) ?
                    mResolvedBaseFile.getAbsolutePath() : null;
            info.progress = progress;
            info.sealed = mSealed;
            info.isCommitted = mCommitted.get();
            info.active = mActiveCount.get() > 0;

            info.mode = params.mode;
            info.installReason = params.installReason;
            info.installScenario = params.installScenario;
            info.sizeBytes = params.sizeBytes;
            info.appPackageName = mPackageName != null ? mPackageName : params.appPackageName;
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
            info.childSessionIds = getChildSessionIdsLocked();
            info.isSessionApplied = mSessionApplied;
            info.isSessionReady = mSessionReady;
            info.isSessionFailed = mSessionFailed;
            info.setSessionErrorCode(mSessionErrorCode, mSessionErrorMessage);
            info.createdMillis = createdMillis;
            info.updatedMillis = updatedMillis;
            info.requireUserAction = params.requireUserAction;
            info.installerUid = mInstallerUid;
            info.packageSource = params.packageSource;
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
        return mCommitted.get();
    }

    /** {@hide} */
    boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
        }
    }

    private boolean isInTerminalState() {
        synchronized (mLock) {
            return mSessionApplied || mSessionFailed;
        }
    }

    /** Returns true if a staged session has reached a final state and can be forgotten about  */
    public boolean isStagedAndInTerminalState() {
        return params.isStaged && isInTerminalState();
    }

    private void assertNotLocked(String cookie) {
        if (Thread.holdsLock(mLock)) {
            throw new IllegalStateException(cookie + " is holding mLock");
        }
    }

    private void assertSealed(String cookie) {
        if (!isSealed()) {
            throw new IllegalStateException(cookie + " before sealing");
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
        if (mCommitted.get()) {
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

    @GuardedBy("mProgressLock")
    private void setClientProgressLocked(float progress) {
        // Always publish first staging movement
        final boolean forcePublish = (mClientProgress == 0);
        mClientProgress = progress;
        computeProgressLocked(forcePublish);
    }

    @Override
    public void setClientProgress(float progress) {
        assertCallerIsOwnerOrRoot();
        synchronized (mProgressLock) {
            setClientProgressLocked(progress);
        }
    }

    @Override
    public void addClientProgress(float progress) {
        assertCallerIsOwnerOrRoot();
        synchronized (mProgressLock) {
            setClientProgressLocked(mClientProgress + progress);
        }
    }

    @GuardedBy("mProgressLock")
    private void computeProgressLocked(boolean forcePublish) {
        if (!isIncrementalInstallation() || !mCommitted.get()) {
            mProgress = MathUtils.constrain(mClientProgress * 0.8f, 0f, 0.8f)
                    + MathUtils.constrain(mInternalProgress * 0.2f, 0f, 0.2f);
        } else {
            // For incremental, publish regular install progress before the session is committed,
            // but publish incremental progress afterwards.
            if (mIncrementalProgress - mProgress >= 0.01) {
                // It takes some time for data loader to write to incremental file system, so at the
                // beginning of the commit, the incremental progress might be very small.
                // Wait till the incremental progress is larger than what's already displayed.
                // This way we don't see the progress ring going backwards.
                mProgress = mIncrementalProgress;
            }
        }

        // Only publish meaningful progress changes.
        if (forcePublish || (mProgress - mReportedProgress) >= 0.01) {
            mReportedProgress = mProgress;
            mCallback.onSessionProgressChanged(this, mProgress);
        }
    }

    @Override
    public String[] getNames() {
        assertCallerIsOwnerRootOrVerifier();
        synchronized (mLock) {
            assertPreparedAndNotDestroyedLocked("getNames");
            if (!mCommitted.get()) {
                return getNamesLocked();
            } else {
                return getStageDirContentsLocked();
            }
        }
    }

    @GuardedBy("mLock")
    private String[] getStageDirContentsLocked() {
        String[] result = stageDir.list();
        if (result == null) {
            result = EmptyArray.STRING;
        }
        return result;
    }

    @GuardedBy("mLock")
    private String[] getNamesLocked() {
        if (!isDataLoaderInstallation()) {
            return getStageDirContentsLocked();
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
    public void setChecksums(String name, @NonNull Checksum[] checksums,
            @Nullable byte[] signature) {
        if (checksums.length == 0) {
            return;
        }

        final String installerPackageName;
        if (!TextUtils.isEmpty(getInstallSource().initiatingPackageName)) {
            installerPackageName = getInstallSource().initiatingPackageName;
        } else {
            installerPackageName = getInstallSource().installerPackageName;
        }
        if (TextUtils.isEmpty(installerPackageName)) {
            throw new IllegalStateException("Installer package is empty.");
        }

        final AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        appOps.checkPackage(Binder.getCallingUid(), installerPackageName);

        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final AndroidPackage callingInstaller = pmi.getPackage(installerPackageName);
        if (callingInstaller == null) {
            throw new IllegalStateException("Can't obtain calling installer's package.");
        }

        if (signature != null && signature.length != 0) {
            try {
                Certificate[] ignored = ApkChecksums.verifySignature(checksums, signature);
            } catch (IOException | NoSuchAlgorithmException | SignatureException e) {
                throw new IllegalArgumentException("Can't verify signature", e);
            }
        }

        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
            assertPreparedAndNotCommittedOrDestroyedLocked("addChecksums");

            if (mChecksums.containsKey(name)) {
                throw new IllegalStateException("Duplicate checksums.");
            }

            mChecksums.put(name, new PerFileChecksum(checksums, signature));
        }
    }

    @Override
    public void requestChecksums(@NonNull String name, @Checksum.TypeMask int optional,
            @Checksum.TypeMask int required, @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener) {
        assertCallerIsOwnerRootOrVerifier();
        final File file = new File(stageDir, name);
        final String installerPackageName = getInstallSource().initiatingPackageName;
        try {
            mPm.requestFileChecksums(file, installerPackageName, optional, required,
                    trustedInstallers, onChecksumsReadyListener);
        } catch (FileNotFoundException e) {
            throw new ParcelableException(e);
        }
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

        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
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

    @GuardedBy("mLock")
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
        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
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

    @Override
    public void stageViaHardLink(String path) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("link() can only be run by the system");
        }

        try {
            final File target = new File(path);
            final File source = new File(stageDir, target.getName());
            try {
                Os.link(path, source.getAbsolutePath());
                // Grant READ access for APK to be read successfully
                Os.chmod(source.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
            if (!SELinux.restorecon(source)) {
                throw new IOException("Can't relabel file: " + source);
            }
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
        }
    }

    private ParcelFileDescriptor openTargetInternal(String path, int flags, int mode)
            throws IOException, ErrnoException {
        // TODO: this should delegate to DCS so the system process avoids
        // holding open FDs into containers.
        final FileDescriptor fd = Os.open(path, flags, mode);
        return new ParcelFileDescriptor(fd);
    }

    private ParcelFileDescriptor createRevocableFdInternal(RevocableFileDescriptor fd,
            ParcelFileDescriptor pfd) throws IOException {
        int releasedFdInt = pfd.detachFd();
        FileDescriptor releasedFd = new FileDescriptor();
        releasedFd.setInt$(releasedFdInt);
        fd.init(mContext, releasedFd);
        return fd.getRevocableFileDescriptor();
    }

    private ParcelFileDescriptor doWriteInternal(String name, long offsetBytes, long lengthBytes,
            ParcelFileDescriptor incomingFd) throws IOException {
        // Quick validity check of state, and allocate a pipe for ourselves. We
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

            ParcelFileDescriptor targetPfd = openTargetInternal(target.getAbsolutePath(),
                    O_CREAT | O_WRONLY, 0644);
            Os.chmod(target.getAbsolutePath(), 0644);

            // If caller specified a total length, allocate it for them. Free up
            // cache space to grow, if needed.
            if (stageDir != null && lengthBytes > 0) {
                mContext.getSystemService(StorageManager.class).allocateBytes(
                        targetPfd.getFileDescriptor(), lengthBytes,
                        InstallLocationUtils.translateAllocateFlags(params.installFlags));
            }

            if (offsetBytes > 0) {
                Os.lseek(targetPfd.getFileDescriptor(), offsetBytes, OsConstants.SEEK_SET);
            }

            if (incomingFd != null) {
                // In "reverse" mode, we're streaming data ourselves from the
                // incoming FD, which means we never have to hand out our
                // sensitive internal FD. We still rely on a "bridge" being
                // inserted above to hold the session active.
                try {
                    final Int64Ref last = new Int64Ref(0);
                    FileUtils.copy(incomingFd.getFileDescriptor(), targetPfd.getFileDescriptor(),
                            lengthBytes, null, Runnable::run,
                            (long progress) -> {
                                if (params.sizeBytes > 0) {
                                    final long delta = progress - last.value;
                                    last.value = progress;
                                    synchronized (mProgressLock) {
                                        setClientProgressLocked(mClientProgress
                                                + (float) delta / (float) params.sizeBytes);
                                    }
                                }
                            });
                } finally {
                    IoUtils.closeQuietly(targetPfd);
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
                return createRevocableFdInternal(fd, targetPfd);
            } else {
                bridge.setTargetFile(targetPfd);
                bridge.start();
                return bridge.getClientSocket();
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
        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
            assertPreparedAndNotCommittedOrDestroyedLocked("openRead");
            try {
                return openReadInternalLocked(name);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    @GuardedBy("mLock")
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
     * Check if the caller is the owner of this session or a verifier.
     * Otherwise throw a {@link SecurityException}.
     */
    private void assertCallerIsOwnerRootOrVerifier() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == Process.ROOT_UID || callingUid == mInstallerUid) {
            return;
        }
        if (isSealed() && mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        throw new SecurityException("Session does not belong to uid " + callingUid);
    }

    /**
     * Check if the caller is the owner of this session. Otherwise throw a
     * {@link SecurityException}.
     */
    private void assertCallerIsOwnerOrRoot() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != mInstallerUid) {
            throw new SecurityException("Session does not belong to uid " + callingUid);
        }
    }

    /**
     * Check if the caller is the owner of this session. Otherwise throw a
     * {@link SecurityException}.
     */
    private void assertCallerIsOwnerOrRootOrSystem() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != mInstallerUid
                && callingUid != Process.SYSTEM_UID) {
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
                            + getParentSessionId() +  " and may not be committed directly.");
        }

        if (!markAsSealed(statusReceiver, forTransfer)) {
            return;
        }
        if (isMultiPackage()) {
            synchronized (mLock) {
                boolean sealFailed = false;
                for (int i = mChildSessions.size() - 1; i >= 0; --i) {
                    // seal all children, regardless if any of them fail; we'll throw/return
                    // as appropriate once all children have been processed
                    if (!mChildSessions.valueAt(i).markAsSealed(null, forTransfer)) {
                        sealFailed = true;
                    }
                }
                if (sealFailed) {
                    return;
                }
            }
        }

        dispatchSessionSealed();
    }

    /**
     * Kicks off the install flow. The first step is to persist 'sealed' flags
     * to prevent mutations of hard links created later.
     */
    private void dispatchSessionSealed() {
        mHandler.obtainMessage(MSG_ON_SESSION_SEALED).sendToTarget();
    }

    private void handleSessionSealed() {
        assertSealed("dispatchSessionSealed");
        // Persist the fact that we've sealed ourselves to prevent
        // mutations of any hard links we create.
        mCallback.onSessionSealedBlocking(this);
        dispatchStreamValidateAndCommit();
    }

    private void dispatchStreamValidateAndCommit() {
        mHandler.obtainMessage(MSG_STREAM_VALIDATE_AND_COMMIT).sendToTarget();
    }

    @WorkerThread
    private void handleStreamValidateAndCommit() {
        try {
            // This will track whether the session and any children were validated and are ready to
            // progress to the next phase of install
            boolean allSessionsReady = true;
            for (PackageInstallerSession child : getChildSessions()) {
                allSessionsReady &= child.streamValidateAndCommit();
            }
            if (allSessionsReady && streamValidateAndCommit()) {
                mHandler.obtainMessage(MSG_INSTALL).sendToTarget();
            }
        } catch (PackageManagerException e) {
            destroy();
            String msg = ExceptionUtils.getCompleteMessage(e);
            dispatchSessionFinished(e.error, msg, null);
            maybeFinishChildSessions(e.error, msg);
        }
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
                KnownPackages.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM);
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
        final PackageStateInternal existingPkgSetting = pmi.getPackageStateInternal(packageName);
        if (existingPkgSetting == null || existingPkgSetting.getPkg() == null) {
            return true;
        }

        return !existingPkgSetting.getPkg().isSystem()
                && !existingPkgSetting.getTransientState().isUpdatedSystemApp();
    }

    /**
     * If this was not already called, the session will be sealed.
     *
     * This method may be called multiple times to update the status receiver validate caller
     * permissions.
     */
    private boolean markAsSealed(@Nullable IntentSender statusReceiver, boolean forTransfer) {
        Preconditions.checkState(statusReceiver != null || hasParentSessionId(),
                "statusReceiver can't be null for the root session");
        assertCallerIsOwnerOrRoot();

        synchronized (mLock) {
            assertPreparedAndNotDestroyedLocked("commit of session " + sessionId);
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

            setRemoteStatusReceiver(statusReceiver);

            // After updating the observer, we can skip re-sealing.
            if (mSealed) {
                return true;
            }

            try {
                sealLocked();
            } catch (PackageManagerException e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true if the session is successfully validated and committed. Returns false if the
     * dataloader could not be prepared. This can be called multiple times so long as no
     * exception is thrown.
     * @throws PackageManagerException on an unrecoverable error.
     */
    @WorkerThread
    private boolean streamValidateAndCommit() throws PackageManagerException {
        // TODO(patb): since the work done here for a parent session in a multi-package install is
        //             mostly superficial, consider splitting this method for the parent and
        //             single / child sessions.
        try {
            synchronized (mLock) {
                if (mCommitted.get()) {
                    return true;
                }
                // Read transfers from the original owner stay open, but as the session's data
                // cannot be modified anymore, there is no leak of information. For staged sessions,
                // further validation is performed by the staging manager.
                if (!params.isMultiPackage) {
                    if (!prepareDataLoaderLocked()) {
                        return false;
                    }

                    if (isApexSession()) {
                        validateApexInstallLocked();
                    } else {
                        validateApkInstallLocked();
                    }
                }
                if (mDestroyed) {
                    throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                            "Session destroyed");
                }
                if (!isIncrementalInstallation()) {
                    synchronized (mProgressLock) {
                        // For non-incremental installs, client staging is fully done at this point
                        mClientProgress = 1f;
                        computeProgressLocked(true);
                    }
                }

                // This ongoing commit should keep session active, even though client
                // will probably close their end.
                mActiveCount.incrementAndGet();

                if (!mCommitted.compareAndSet(false /*expect*/, true /*update*/)) {
                    throw new PackageManagerException(
                            INSTALL_FAILED_INTERNAL_ERROR,
                            TextUtils.formatSimple(
                                    "The mCommitted of session %d should be false originally",
                                    sessionId));
                }
                committedMillis = System.currentTimeMillis();
            }
            return true;
        } catch (PackageManagerException e) {
            throw e;
        } catch (Throwable e) {
            // Convert all exceptions into package manager exceptions as only those are handled
            // in the code above.
            throw new PackageManagerException(e);
        }
    }

    @GuardedBy("mLock")
    private @NonNull List<PackageInstallerSession> getChildSessionsLocked() {
        List<PackageInstallerSession> childSessions = Collections.EMPTY_LIST;
        if (isMultiPackage()) {
            int size = mChildSessions.size();
            childSessions = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                childSessions.add(mChildSessions.valueAt(i));
            }
        }
        return childSessions;
    }

    @NonNull List<PackageInstallerSession> getChildSessions() {
        synchronized (mLock) {
            return getChildSessionsLocked();
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
    private void sealLocked()
            throws PackageManagerException {
        try {
            assertNoWriteFileTransfersOpenLocked();
            assertPreparedAndNotDestroyedLocked("sealing of session " + sessionId);
            mSealed = true;
        } catch (Throwable e) {
            // Convert all exceptions into package manager exceptions as only those are handled
            // in the code above.
            throw onSessionValidationFailure(new PackageManagerException(e));
        }
    }

    private PackageManagerException onSessionValidationFailure(PackageManagerException e) {
        onSessionValidationFailure(e.error, ExceptionUtils.getCompleteMessage(e));
        return e;
    }

    private void onSessionValidationFailure(int error, String detailMessage) {
        // Session is sealed but could not be validated, we need to destroy it.
        destroyInternal();
        // Dispatch message to remove session from PackageInstallerService.
        dispatchSessionFinished(error, detailMessage, null);
    }

    private void onSessionVerificationFailure(int error, String msg) {
        Slog.e(TAG, "Failed to verify session " + sessionId);
        // Dispatch message to remove session from PackageInstallerService.
        dispatchSessionFinished(error, msg, null);
        maybeFinishChildSessions(error, msg);
    }

    private void onSystemDataLoaderUnrecoverable() {
        final DeletePackageHelper deletePackageHelper = new DeletePackageHelper(mPm);
        final String packageName = getPackageName();
        if (TextUtils.isEmpty(packageName)) {
            // The package has not been installed.
            return;
        }
        mHandler.post(() -> {
            if (deletePackageHelper.deletePackageX(packageName,
                    PackageManager.VERSION_CODE_HIGHEST, UserHandle.USER_SYSTEM,
                    PackageManager.DELETE_ALL_USERS, true /*removedBySystem*/)
                    != PackageManager.DELETE_SUCCEEDED) {
                Slog.e(TAG, "Failed to uninstall package with failed dataloader: " + packageName);
            }
        });
    }

    /**
     * If session should be sealed, then it's sealed to prevent further modification.
     * If the session can't be sealed then it's destroyed.
     *
     * Additionally for staged APEX/APK sessions read+validate the package and populate req'd
     * fields.
     *
     * <p> This is meant to be called after all of the sessions are loaded and added to
     * PackageInstallerService
     *
     * @param allSessions All sessions loaded by PackageInstallerService, guaranteed to be
     *                    immutable by the caller during the method call. Used to resolve child
     *                    sessions Ids to actual object reference.
     */
    @AnyThread
    void onAfterSessionRead(SparseArray<PackageInstallerSession> allSessions) {
        synchronized (mLock) {
            // Resolve null values to actual object references
            for (int i = mChildSessions.size() - 1; i >= 0; --i) {
                int childSessionId = mChildSessions.keyAt(i);
                PackageInstallerSession childSession = allSessions.get(childSessionId);
                if (childSession != null) {
                    mChildSessions.setValueAt(i, childSession);
                } else {
                    Slog.e(TAG, "Child session not existed: " + childSessionId);
                    mChildSessions.removeAt(i);
                }
            }

            if (!mShouldBeSealed || isStagedAndInTerminalState()) {
                return;
            }
            try {
                sealLocked();

                // Session that are staged, committed and not multi package will be installed or
                // restart verification during this boot. As such, we need populate all the fields
                // for successful installation.
                if (isMultiPackage() || !isStaged() || !isCommitted()) {
                    return;
                }
                final PackageInstallerSession root = hasParentSessionId()
                        ? allSessions.get(getParentSessionId())
                        : this;
                if (root != null && !root.isStagedAndInTerminalState()) {
                    if (isApexSession()) {
                        validateApexInstallLocked();
                    } else {
                        validateApkInstallLocked();
                    }
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
        Preconditions.checkArgument(!TextUtils.isEmpty(packageName));
        final Computer snapshot = mPm.snapshotComputer();
        ApplicationInfo newOwnerAppInfo = snapshot.getApplicationInfo(packageName, 0, userId);
        if (newOwnerAppInfo == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException(packageName));
        }

        if (PackageManager.PERMISSION_GRANTED != snapshot.checkUidPermission(
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
            assertCallerIsOwnerOrRoot();
            assertPreparedAndNotSealedLocked("transfer");

            try {
                sealLocked();
            } catch (PackageManagerException e) {
                throw new IllegalStateException("Package is not valid", e);
            }

            mInstallerUid = newOwnerAppInfo.uid;
            mInstallSource = InstallSource.create(packageName, null, packageName, null,
                    params.packageSource);
        }
    }

    @WorkerThread
    private static boolean checkUserActionRequirement(
            PackageInstallerSession session, IntentSender target) {
        if (session.isMultiPackage()) {
            return false;
        }

        @UserActionRequirement int userActionRequirement = USER_ACTION_NOT_NEEDED;
        // TODO(b/159331446): Move this to makeSessionActiveForInstall and update javadoc
        userActionRequirement = session.computeUserActionRequirement();
        if (userActionRequirement == USER_ACTION_REQUIRED) {
            session.sendPendingUserActionIntent(target);
            return true;
        }

        if (!session.isApexSession() && userActionRequirement == USER_ACTION_PENDING_APK_PARSING) {
            final int validatedTargetSdk;
            synchronized (session.mLock) {
                validatedTargetSdk = session.mValidatedTargetSdk;
            }

            if (validatedTargetSdk != INVALID_TARGET_SDK_VERSION
                    && validatedTargetSdk < Build.VERSION_CODES.R) {
                session.sendPendingUserActionIntent(target);
                return true;
            }

            if (session.params.requireUserAction == SessionParams.USER_ACTION_NOT_REQUIRED) {
                if (!session.mSilentUpdatePolicy.isSilentUpdateAllowed(
                        session.getInstallerPackageName(), session.getPackageName())) {
                    // Fall back to the non-silent update if a repeated installation is invoked
                    // within the throttle time.
                    session.sendPendingUserActionIntent(target);
                    return true;
                }
                session.mSilentUpdatePolicy.track(session.getInstallerPackageName(),
                        session.getPackageName());
            }
        }

        return false;
    }

    /**
     * Find out any session needs user action.
     *
     * @return true if the session set requires user action for the installation, otherwise false.
     */
    @WorkerThread
    private boolean sendPendingUserActionIntentIfNeeded() {
        assertNotChild("PackageInstallerSession#sendPendingUserActionIntentIfNeeded");

        final IntentSender statusReceiver = getRemoteStatusReceiver();
        return sessionContains(s -> checkUserActionRequirement(s, statusReceiver));
    }

    @WorkerThread
    private void handleInstall() {
        if (isInstallerDeviceOwnerOrAffiliatedProfileOwner()) {
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.INSTALL_PACKAGE)
                    .setAdmin(getInstallSource().installerPackageName)
                    .write();
        }

        /**
         * Stops the installation of the whole session set if one session needs user action
         * in its belong session set. When the user answers the yes,
         * {@link #setPermissionsResult(boolean)} is called and then {@link #MSG_INSTALL} is
         * handled to come back here to check again.
         */
        if (sendPendingUserActionIntentIfNeeded()) {
            return;
        }

        if (mVerificationInProgress) {
            Slog.w(TAG, "Verification is already in progress for session " + sessionId);
            return;
        }
        mVerificationInProgress = true;

        if (params.isStaged) {
            mStagedSession.verifySession();
        } else {
            verify();
        }
    }

    private void verify() {
        try {
            List<PackageInstallerSession> children = getChildSessions();
            if (isMultiPackage()) {
                for (PackageInstallerSession child : children) {
                    child.prepareInheritedFiles();
                    child.parseApkAndExtractNativeLibraries();
                }
            } else {
                prepareInheritedFiles();
                parseApkAndExtractNativeLibraries();
            }
            verifyNonStaged();
        } catch (PackageManagerException e) {
            final String completeMsg = ExceptionUtils.getCompleteMessage(e);
            final String errorMsg = PackageManager.installStatusToString(e.error, completeMsg);
            setSessionFailed(e.error, errorMsg);
            onSessionVerificationFailure(e.error, errorMsg);
        }
    }

    private IntentSender getRemoteStatusReceiver() {
        synchronized (mLock) {
            return mRemoteStatusReceiver;
        }
    }

    private void setRemoteStatusReceiver(IntentSender remoteStatusReceiver) {
        synchronized (mLock) {
            mRemoteStatusReceiver = remoteStatusReceiver;
        }
    }

    /**
     * Prepares staged directory with any inherited APKs.
     */
    private void prepareInheritedFiles() throws PackageManagerException {
        if (isApexSession() || params.mode != SessionParams.MODE_INHERIT_EXISTING) {
            return;
        }
        synchronized (mLock) {
            if (mStageDirInUse) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session files in use");
            }
            if (mDestroyed) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session destroyed");
            }
            if (!mSealed) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session not sealed");
            }
            // Inherit any packages and native libraries from existing install that
            // haven't been overridden.
            try {
                final List<File> fromFiles = mResolvedInheritedFiles;
                final File toDir = stageDir;
                final String tempPackageName = toDir.getName();

                if (LOGD) Slog.d(TAG, "Inherited files: " + mResolvedInheritedFiles);
                if (!mResolvedInheritedFiles.isEmpty() && mInheritedFilesBase == null) {
                    throw new IllegalStateException("mInheritedFilesBase == null");
                }

                if (isLinkPossible(fromFiles, toDir)) {
                    if (!mResolvedInstructionSets.isEmpty()) {
                        final File oatDir = new File(toDir, "oat");
                        createOatDirs(tempPackageName, mResolvedInstructionSets, oatDir);
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
                    linkFiles(tempPackageName, fromFiles, toDir, mInheritedFilesBase);
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
    }

    @GuardedBy("mLock")
    private void markStageDirInUseLocked() throws PackageManagerException {
        if (mDestroyed) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                    "Session destroyed");
        }
        // Set this flag to prevent abandon() from deleting staging files when verification or
        // installation is about to start.
        mStageDirInUse = true;
    }

    private void parseApkAndExtractNativeLibraries() throws PackageManagerException {
        synchronized (mLock) {
            if (mStageDirInUse) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session files in use");
            }
            if (mDestroyed) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session destroyed");
            }
            if (!mSealed) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session not sealed");
            }
            Objects.requireNonNull(mPackageName);
            Objects.requireNonNull(mSigningDetails);
            Objects.requireNonNull(mResolvedBaseFile);
            final PackageLite result;
            if (!isApexSession()) {
                // For mode inherit existing, it would link/copy existing files to stage dir in
                // prepareInheritedFiles(). Therefore, we need to parse the complete package in
                // stage dir here.
                // Besides, PackageLite may be null for staged sessions that don't complete
                // pre-reboot verification.
                result = getOrParsePackageLiteLocked(stageDir, /* flags */ 0);
            } else {
                result = getOrParsePackageLiteLocked(mResolvedBaseFile, /* flags */ 0);
            }
            if (result != null) {
                mPackageLite = result;
                if (!isApexSession()) {
                    synchronized (mProgressLock) {
                        mInternalProgress = 0.5f;
                        computeProgressLocked(true);
                    }
                    extractNativeLibraries(
                            mPackageLite, stageDir, params.abiOverride, mayInheritNativeLibs());
                }
            }
        }
    }

    private void verifyNonStaged()
            throws PackageManagerException {
        synchronized (mLock) {
            markStageDirInUseLocked();
        }
        mSessionProvider.getSessionVerifier().verify(this, (error, msg) -> {
            mHandler.post(() -> {
                if (dispatchPendingAbandonCallback()) {
                    // No need to continue if abandoned
                    return;
                }
                if (error == INSTALL_SUCCEEDED) {
                    onVerificationComplete();
                } else {
                    onSessionVerificationFailure(error, msg);
                }
            });
        });
    }

    private static class InstallResult {
        public final PackageInstallerSession session;
        public final Bundle extras;
        InstallResult(PackageInstallerSession session, Bundle extras) {
            this.session = session;
            this.extras = extras;
        }
    }

    /**
     * Stages installs and do cleanup accordingly depending on whether the installation is
     * successful or not.
     *
     * @return a future that will be completed when the whole process is completed.
     */
    private CompletableFuture<Void> install() {
        List<CompletableFuture<InstallResult>> futures = installNonStaged();
        CompletableFuture<InstallResult>[] arr = new CompletableFuture[futures.size()];
        return CompletableFuture.allOf(futures.toArray(arr)).whenComplete((r, t) -> {
            if (t == null) {
                setSessionApplied();
                for (CompletableFuture<InstallResult> f : futures) {
                    InstallResult result = f.join();
                    result.session.dispatchSessionFinished(
                            INSTALL_SUCCEEDED, "Session installed", result.extras);
                }
            } else {
                PackageManagerException e = (PackageManagerException) t.getCause();
                setSessionFailed(e.error,
                        PackageManager.installStatusToString(e.error, e.getMessage()));
                dispatchSessionFinished(e.error, e.getMessage(), null);
                maybeFinishChildSessions(e.error, e.getMessage());
            }
        });
    }

    /**
     * Stages sessions (including child sessions if any) for install.
     *
     * @return a list of futures to indicate the install results of each session.
     */
    private List<CompletableFuture<InstallResult>> installNonStaged() {
        try {
            List<CompletableFuture<InstallResult>> futures = new ArrayList<>();
            CompletableFuture<InstallResult> future = new CompletableFuture<>();
            futures.add(future);
            final InstallParams installingSession = makeInstallParams(future);
            if (isMultiPackage()) {
                final List<PackageInstallerSession> childSessions = getChildSessions();
                List<InstallParams> installingChildSessions = new ArrayList<>(childSessions.size());
                for (int i = 0; i < childSessions.size(); ++i) {
                    final PackageInstallerSession session = childSessions.get(i);
                    future = new CompletableFuture<>();
                    futures.add(future);
                    final InstallParams installingChildSession = session.makeInstallParams(future);
                    if (installingChildSession != null) {
                        installingChildSessions.add(installingChildSession);
                    }
                }
                if (!installingChildSessions.isEmpty()) {
                    installingSession.installStage(installingChildSessions);
                }
            } else if (installingSession != null) {
                installingSession.installStage();
            }

            return futures;
        } catch (PackageManagerException e) {
            List<CompletableFuture<InstallResult>> futures = new ArrayList<>();
            futures.add(CompletableFuture.failedFuture(e));
            return futures;
        }
    }

    private void sendPendingUserActionIntent(IntentSender target) {
        // User needs to confirm installation;
        // give installer an intent they can use to involve
        // user.
        final Intent intent = new Intent(PackageInstaller.ACTION_CONFIRM_INSTALL);
        intent.setPackage(mPm.getPackageInstallerPackageName());
        intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);

        sendOnUserActionRequired(mContext, target, sessionId, intent);

        // Commit was keeping session marked as active until now; release
        // that extra refcount so session appears idle.
        closeInternal(false);
    }

    @WorkerThread
    private void onVerificationComplete() {
        if (isStaged()) {
            mStagingManager.commitSession(mStagedSession);
            sendUpdateToRemoteStatusReceiver(INSTALL_SUCCEEDED, "Session staged", null);
            return;
        }
        install();
    }

    /**
     * Stages this session for install and returns a
     * {@link InstallParams} representing this new staged state.
     *
     * @param future a future that will be completed when this session is completed.
     */
    @Nullable
    private InstallParams makeInstallParams(CompletableFuture<InstallResult> future)
            throws PackageManagerException {
        synchronized (mLock) {
            if (!mSealed) {
                throw new PackageManagerException(
                        INSTALL_FAILED_INTERNAL_ERROR, "Session not sealed");
            }
            markStageDirInUseLocked();
        }

        if (isMultiPackage()) {
            // Always treat parent session as success for it has nothing to install
            future.complete(new InstallResult(this, null));
        } else if (isApexSession() && params.isStaged) {
            // Staged apex sessions have been handled by apexd
            future.complete(new InstallResult(this, null));
            return null;
        }

        final IPackageInstallObserver2 localObserver = new IPackageInstallObserver2.Stub() {
            @Override
            public void onUserActionRequired(Intent intent) {
                throw new IllegalStateException();
            }

            @Override
            public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                    Bundle extras) {
                if (returnCode == INSTALL_SUCCEEDED) {
                    future.complete(new InstallResult(PackageInstallerSession.this, extras));
                } else {
                    future.completeExceptionally(new PackageManagerException(returnCode, msg));
                }
            }
        };

        final UserHandle user;
        if ((params.installFlags & PackageManager.INSTALL_ALL_USERS) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(userId);
        }

        if (params.isStaged) {
            params.installFlags |= INSTALL_STAGED;
        }

        if (!isMultiPackage() && !isApexSession()) {
            synchronized (mLock) {
                // This shouldn't be null, but have this code path just in case.
                if (mPackageLite == null) {
                    Slog.wtf(TAG, "Session: " + sessionId + ". Don't have a valid PackageLite.");
                }
                mPackageLite = getOrParsePackageLiteLocked(stageDir, /* flags */ 0);
            }
        }

        synchronized (mLock) {
            return new InstallParams(stageDir, localObserver, params, mInstallSource, user,
                    mSigningDetails, mInstallerUid, mPackageLite, mPm);
        }
    }

    @GuardedBy("mLock")
    private PackageLite getOrParsePackageLiteLocked(File packageFile, int flags)
            throws PackageManagerException {
        if (mPackageLite != null) {
            return mPackageLite;
        }

        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<PackageLite> result =
                ApkLiteParseUtils.parsePackageLite(input, packageFile, flags);
        if (result.isError()) {
            throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    result.getErrorMessage(), result.getException());
        }
        return result.getResult();
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
        final String packageName = getPackageName();
        final String packageNameToLog =
                (params.installFlags & PackageManager.INSTALL_FROM_ADB) == 0 ? packageName : "";
        final long currentTimestamp = System.currentTimeMillis();
        final int packageUid;
        if (returnCode != INSTALL_SUCCEEDED) {
            // Package didn't install; no valid uid
            packageUid = Process.INVALID_UID;
        } else {
            packageUid = mPm.snapshotComputer().getPackageUid(packageName, 0, userId);
        }
        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_INSTALLER_V2_REPORTED,
                isIncrementalInstallation(),
                packageNameToLog,
                currentTimestamp - createdMillis,
                returnCode,
                getApksSize(packageName),
                packageUid);
    }

    private long getApksSize(String packageName) {
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final PackageStateInternal ps = pmi.getPackageStateInternal(packageName);
        if (ps == null) {
            return 0;
        }
        final File apkDirOrPath = ps.getPath();
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
    boolean isApexSession() {
        return (params.installFlags & PackageManager.INSTALL_APEX) != 0;
    }

    boolean sessionContains(Predicate<PackageInstallerSession> filter) {
        if (!isMultiPackage()) {
            return filter.test(this);
        }
        final List<PackageInstallerSession> childSessions;
        synchronized (mLock) {
            childSessions = getChildSessionsLocked();
        }
        for (PackageInstallerSession child: childSessions) {
            if (filter.test(child)) {
                return true;
            }
        }
        return false;
    }

    boolean containsApkSession() {
        return sessionContains((s) -> !s.isApexSession());
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
                    TextUtils.formatSimple("Session: %d. No packages staged in %s", sessionId,
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
        resolveAndStageFileLocked(addedFile, targetFile, null);
        mResolvedBaseFile = targetFile;

        // Populate package name of the apex session
        mPackageName = null;
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<ApkLite> ret = ApkLiteParseUtils.parseApkLite(input.reset(),
                mResolvedBaseFile, ParsingPackageUtils.PARSE_COLLECT_CERTIFICATES);
        if (ret.isError()) {
            throw new PackageManagerException(ret.getErrorCode(), ret.getErrorMessage(),
                    ret.getException());
        }
        final ApkLite apk = ret.getResult();

        if (mPackageName == null) {
            mPackageName = apk.getPackageName();
            mVersionCode = apk.getLongVersionCode();
        }

        mSigningDetails = apk.getSigningDetails();
        mHasDeviceAdminReceiver = apk.isHasDeviceAdminReceiver();
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
     * @return a {@link PackageLite} representation of the validated APK(s).
     */
    @GuardedBy("mLock")
    private PackageLite validateApkInstallLocked() throws PackageManagerException {
        ApkLite baseApk = null;
        final PackageLite packageLite;
        mPackageLite = null;
        mPackageName = null;
        mVersionCode = -1;
        mSigningDetails = SigningDetails.UNKNOWN;

        mResolvedBaseFile = null;
        mResolvedStagedFiles.clear();
        mResolvedInheritedFiles.clear();

        final PackageInfo pkgInfo = mPm.snapshotComputer().getPackageInfo(
                params.appPackageName, PackageManager.GET_SIGNATURES
                        | PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES /*flags*/, userId);

        // Partial installs must be consistent with existing install
        if (params.mode == SessionParams.MODE_INHERIT_EXISTING
                && (pkgInfo == null || pkgInfo.applicationInfo == null)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Missing existing base package");
        }

        // Default to require only if existing base apk has fs-verity.
        mVerityFoundForApks = PackageManagerServiceUtils.isApkVerityEnabled()
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
                    TextUtils.formatSimple("Session: %d. No packages staged in %s", sessionId,
                          stageDir.getAbsolutePath()));
        }

        // Verify that all staged packages are internally consistent
        final ArraySet<String> stagedSplits = new ArraySet<>();
        final ArraySet<String> stagedSplitTypes = new ArraySet<>();
        final ArraySet<String> requiredSplitTypes = new ArraySet<>();
        final ArrayMap<String, ApkLite> splitApks = new ArrayMap<>();
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        for (File addedFile : addedFiles) {
            final ParseResult<ApkLite> result = ApkLiteParseUtils.parseApkLite(input.reset(),
                    addedFile, ParsingPackageUtils.PARSE_COLLECT_CERTIFICATES);
            if (result.isError()) {
                throw new PackageManagerException(result.getErrorCode(),
                        result.getErrorMessage(), result.getException());
            }

            final ApkLite apk = result.getResult();
            if (!stagedSplits.add(apk.getSplitName())) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Split " + apk.getSplitName() + " was defined multiple times");
            }

            // Use first package to define unknown values
            if (mPackageName == null) {
                mPackageName = apk.getPackageName();
                mVersionCode = apk.getLongVersionCode();
            }
            if (mSigningDetails == SigningDetails.UNKNOWN) {
                mSigningDetails = apk.getSigningDetails();
            }
            mHasDeviceAdminReceiver = apk.isHasDeviceAdminReceiver();

            assertApkConsistentLocked(String.valueOf(addedFile), apk);

            // Take this opportunity to enforce uniform naming
            final String targetName = ApkLiteParseUtils.splitNameToFileName(apk);
            if (!FileUtils.isValidExtFilename(targetName)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Invalid filename: " + targetName);
            }

            // Yell loudly if installers drop attribute installLocation when apps explicitly set.
            if (apk.getInstallLocation() != PackageInfo.INSTALL_LOCATION_UNSPECIFIED) {
                final String installerPackageName = getInstallerPackageName();
                if (installerPackageName != null
                        && (params.installLocation != apk.getInstallLocation())) {
                    Slog.wtf(TAG, installerPackageName
                            + " drops manifest attribute android:installLocation in " + targetName
                            + " for " + mPackageName);
                }
            }

            final File targetFile = new File(stageDir, targetName);
            resolveAndStageFileLocked(addedFile, targetFile, apk.getSplitName());

            // Base is coming from session
            if (apk.getSplitName() == null) {
                mResolvedBaseFile = targetFile;
                baseApk = apk;
            } else {
                splitApks.put(apk.getSplitName(), apk);
            }

            // Collect the requiredSplitTypes and staged splitTypes
            CollectionUtils.addAll(requiredSplitTypes, apk.getRequiredSplitTypes());
            CollectionUtils.addAll(stagedSplitTypes, apk.getSplitTypes());
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
            if (mSigningDetails == SigningDetails.UNKNOWN) {
                mSigningDetails = unsafeGetCertsWithoutVerification(
                        pkgInfo.applicationInfo.sourceDir);
            }
        }

        if (isIncrementalInstallation() && !isIncrementalInstallationAllowed(mPackageName)) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_SESSION_INVALID,
                    "Incremental installation of this package is not allowed.");
        }

        if (mInstallerUid != mOriginalInstallerUid) {
            // Session has been transferred, check package name.
            if (TextUtils.isEmpty(mPackageName) || !mPackageName.equals(
                    mOriginalInstallerPackageName)) {
                throw new PackageManagerException(PackageManager.INSTALL_FAILED_PACKAGE_CHANGED,
                        "Can only transfer sessions that update the original installer");
            }
        }

        if (!mChecksums.isEmpty()) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_SESSION_INVALID,
                    "Invalid checksum name(s): " + String.join(",", mChecksums.keySet()));
        }

        if (params.mode == SessionParams.MODE_FULL_INSTALL) {
            // Full installs must include a base package
            if (!stagedSplits.contains(null)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Full install must include a base package");
            } else if ((params.installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
                EventLog.writeEvent(0x534e4554, "219044664");

                // Installing base.apk. Make sure the app is restarted.
                params.setDontKillApp(false);
            }
            if (baseApk.isSplitRequired() && (stagedSplits.size() <= 1
                    || !stagedSplitTypes.containsAll(requiredSplitTypes))) {
                throw new PackageManagerException(INSTALL_FAILED_MISSING_SPLIT,
                        "Missing split for " + mPackageName);
            }
            // For mode full install, we compose package lite for future usage instead of
            // re-parsing it again and again.
            final ParseResult<PackageLite> pkgLiteResult =
                    ApkLiteParseUtils.composePackageLiteFromApks(input.reset(), stageDir, baseApk,
                            splitApks, true);
            if (pkgLiteResult.isError()) {
                throw new PackageManagerException(pkgLiteResult.getErrorCode(),
                        pkgLiteResult.getErrorMessage(), pkgLiteResult.getException());
            }
            mPackageLite = pkgLiteResult.getResult();
            packageLite = mPackageLite;
        } else {
            final ApplicationInfo appInfo = pkgInfo.applicationInfo;
            ParseResult<PackageLite> pkgLiteResult = ApkLiteParseUtils.parsePackageLite(
                    input.reset(), new File(appInfo.getCodePath()), 0);
            if (pkgLiteResult.isError()) {
                throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                        pkgLiteResult.getErrorMessage(), pkgLiteResult.getException());
            }
            final PackageLite existing = pkgLiteResult.getResult();
            packageLite = existing;
            assertPackageConsistentLocked("Existing", existing.getPackageName(),
                    existing.getLongVersionCode());
            final SigningDetails signingDetails =
                    unsafeGetCertsWithoutVerification(existing.getBaseApkPath());
            if (!mSigningDetails.signaturesMatchExactly(signingDetails)) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Existing signatures are inconsistent");
            }

            // Inherit base if not overridden.
            if (mResolvedBaseFile == null) {
                mResolvedBaseFile = new File(appInfo.getBaseCodePath());
                inheritFileLocked(mResolvedBaseFile);
                // Collect the requiredSplitTypes from base
                CollectionUtils.addAll(requiredSplitTypes, existing.getBaseRequiredSplitTypes());
            } else if ((params.installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
                EventLog.writeEvent(0x534e4554, "219044664");

                // Installing base.apk. Make sure the app is restarted.
                params.setDontKillApp(false);
            }

            // Inherit splits if not overridden.
            if (!ArrayUtils.isEmpty(existing.getSplitNames())) {
                for (int i = 0; i < existing.getSplitNames().length; i++) {
                    final String splitName = existing.getSplitNames()[i];
                    final File splitFile = new File(existing.getSplitApkPaths()[i]);
                    final boolean splitRemoved = removeSplitList.contains(splitName);
                    if (!stagedSplits.contains(splitName) && !splitRemoved) {
                        inheritFileLocked(splitFile);
                        // Collect the requiredSplitTypes and staged splitTypes from splits
                        CollectionUtils.addAll(requiredSplitTypes,
                                existing.getRequiredSplitTypes()[i]);
                        CollectionUtils.addAll(stagedSplitTypes, existing.getSplitTypes()[i]);
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
            // For the case of split required, failed if no splits existed
            if (packageLite.isSplitRequired()) {
                final int existingSplits = ArrayUtils.size(existing.getSplitNames());
                final boolean allSplitsRemoved = (existingSplits == removeSplitList.size());
                final boolean onlyBaseFileStaged = (stagedSplits.size() == 1
                        && stagedSplits.contains(null));
                if ((allSplitsRemoved && (stagedSplits.isEmpty() || onlyBaseFileStaged))
                        || !stagedSplitTypes.containsAll(requiredSplitTypes)) {
                    throw new PackageManagerException(INSTALL_FAILED_MISSING_SPLIT,
                            "Missing split for " + mPackageName);
                }
            }
        }
        if (packageLite.isUseEmbeddedDex()) {
            for (File file : mResolvedStagedFiles) {
                if (file.getName().endsWith(".apk")
                        && !DexManager.auditUncompressedDexInApk(file.getPath())) {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Some dex are not uncompressed and aligned correctly for "
                            + mPackageName);
                }
            }
        }

        final boolean isInstallerShell = (mInstallerUid == Process.SHELL_UID);
        if (isInstallerShell && isIncrementalInstallation() && mIncrementalFileStorages != null) {
            if (!packageLite.isDebuggable() && !packageLite.isProfileableByShell()) {
                mIncrementalFileStorages.disallowReadLogs();
            }
        }

        // {@link #sendPendingUserActionIntentIfNeeded} needs to use
        // {@link PackageLite#getTargetSdk()}
        mValidatedTargetSdk = packageLite.getTargetSdk();

        return packageLite;
    }

    @GuardedBy("mLock")
    private void stageFileLocked(File origFile, File targetFile)
            throws PackageManagerException {
        mResolvedStagedFiles.add(targetFile);
        maybeRenameFile(origFile, targetFile);
    }

    @GuardedBy("mLock")
    private void maybeStageFsveritySignatureLocked(File origFile, File targetFile,
            boolean fsVerityRequired) throws PackageManagerException {
        final File originalSignature = new File(
                VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        if (originalSignature.exists()) {
            final File stagedSignature = new File(
                    VerityUtils.getFsveritySignatureFilePath(targetFile.getPath()));
            stageFileLocked(originalSignature, stagedSignature);
        } else if (fsVerityRequired) {
            throw new PackageManagerException(INSTALL_FAILED_BAD_SIGNATURE,
                    "Missing corresponding fs-verity signature to " + origFile);
        }
    }

    @GuardedBy("mLock")
    private void maybeStageDexMetadataLocked(File origFile, File targetFile)
            throws PackageManagerException {
        final File dexMetadataFile = DexMetadataHelper.findDexMetadataForFile(origFile);
        if (dexMetadataFile == null) {
            return;
        }

        if (!FileUtils.isValidExtFilename(dexMetadataFile.getName())) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Invalid filename: " + dexMetadataFile);
        }
        final File targetDexMetadataFile = new File(stageDir,
                DexMetadataHelper.buildDexMetadataPathForApk(targetFile.getName()));

        stageFileLocked(dexMetadataFile, targetDexMetadataFile);

        // Also stage .dm.fsv_sig. .dm may be required to install with fs-verity signature on
        // supported on older devices.
        maybeStageFsveritySignatureLocked(dexMetadataFile, targetDexMetadataFile,
                DexMetadataHelper.isFsVerityRequired());
    }

    private IncrementalFileStorages getIncrementalFileStorages() {
        synchronized (mLock) {
            return mIncrementalFileStorages;
        }
    }

    private void storeBytesToInstallationFile(final String localPath, final String absolutePath,
            final byte[] bytes) throws IOException {
        final IncrementalFileStorages incrementalFileStorages = getIncrementalFileStorages();
        if (!isIncrementalInstallation() || incrementalFileStorages == null) {
            FileUtils.bytesToFile(absolutePath, bytes);
        } else {
            incrementalFileStorages.makeFile(localPath, bytes);
        }
    }

    @GuardedBy("mLock")
    private void maybeStageDigestsLocked(File origFile, File targetFile, String splitName)
            throws PackageManagerException {
        final PerFileChecksum perFileChecksum = mChecksums.get(origFile.getName());
        if (perFileChecksum == null) {
            return;
        }
        mChecksums.remove(origFile.getName());

        final Checksum[] checksums = perFileChecksum.getChecksums();
        if (checksums.length == 0) {
            return;
        }

        final String targetDigestsPath = ApkChecksums.buildDigestsPathForApk(targetFile.getName());
        final File targetDigestsFile = new File(stageDir, targetDigestsPath);
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ApkChecksums.writeChecksums(os, checksums);

            final byte[] signature = perFileChecksum.getSignature();
            if (signature != null && signature.length > 0) {
                Certificate[] ignored = ApkChecksums.verifySignature(checksums, signature);
            }

            // Storing and staging checksums.
            storeBytesToInstallationFile(targetDigestsPath, targetDigestsFile.getAbsolutePath(),
                    os.toByteArray());
            stageFileLocked(targetDigestsFile, targetDigestsFile);

            // Storing and staging signature.
            if (signature == null || signature.length == 0) {
                return;
            }

            final String targetDigestsSignaturePath = ApkChecksums.buildSignaturePathForDigests(
                    targetDigestsPath);
            final File targetDigestsSignatureFile = new File(stageDir, targetDigestsSignaturePath);
            storeBytesToInstallationFile(targetDigestsSignaturePath,
                    targetDigestsSignatureFile.getAbsolutePath(), signature);
            stageFileLocked(targetDigestsSignatureFile, targetDigestsSignatureFile);
        } catch (IOException e) {
            throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                    "Failed to store digests for " + mPackageName, e);
        } catch (NoSuchAlgorithmException | SignatureException e) {
            throw new PackageManagerException(INSTALL_PARSE_FAILED_NO_CERTIFICATES,
                    "Failed to verify digests' signature for " + mPackageName, e);
        }
    }

    @GuardedBy("mLock")
    private boolean isFsVerityRequiredForApk(File origFile, File targetFile)
            throws PackageManagerException {
        if (mVerityFoundForApks) {
            return true;
        }

        // We haven't seen .fsv_sig for any APKs. Treat it as not required until we see one.
        final File originalSignature = new File(
                VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        if (!originalSignature.exists()) {
            return false;
        }
        mVerityFoundForApks = true;

        // When a signature is found, also check any previous staged APKs since they also need to
        // have fs-verity signature consistently.
        for (File file : mResolvedStagedFiles) {
            if (!file.getName().endsWith(".apk")) {
                continue;
            }
            // Ignore the current targeting file.
            if (targetFile.getName().equals(file.getName())) {
                continue;
            }
            throw new PackageManagerException(INSTALL_FAILED_BAD_SIGNATURE,
                    "Previously staged apk is missing fs-verity signature");
        }
        return true;
    }

    @GuardedBy("mLock")
    private void resolveAndStageFileLocked(File origFile, File targetFile, String splitName)
            throws PackageManagerException {
        stageFileLocked(origFile, targetFile);

        // Stage APK's fs-verity signature if present.
        maybeStageFsveritySignatureLocked(origFile, targetFile,
                isFsVerityRequiredForApk(origFile, targetFile));
        // Stage dex metadata (.dm) and corresponding fs-verity signature if present.
        maybeStageDexMetadataLocked(origFile, targetFile);
        // Stage checksums (.digests) if present.
        maybeStageDigestsLocked(origFile, targetFile, splitName);
    }

    @GuardedBy("mLock")
    private void maybeInheritFsveritySignatureLocked(File origFile) {
        // Inherit the fsverity signature file if present.
        final File fsveritySignatureFile = new File(
                VerityUtils.getFsveritySignatureFilePath(origFile.getPath()));
        if (fsveritySignatureFile.exists()) {
            mResolvedInheritedFiles.add(fsveritySignatureFile);
        }
    }

    @GuardedBy("mLock")
    private void inheritFileLocked(File origFile) {
        mResolvedInheritedFiles.add(origFile);

        maybeInheritFsveritySignatureLocked(origFile);

        // Inherit the dex metadata if present.
        final File dexMetadataFile =
                DexMetadataHelper.findDexMetadataForFile(origFile);
        if (dexMetadataFile != null) {
            mResolvedInheritedFiles.add(dexMetadataFile);
            maybeInheritFsveritySignatureLocked(dexMetadataFile);
        }
        // Inherit the digests if present.
        final File digestsFile = ApkChecksums.findDigestsForFile(origFile);
        if (digestsFile != null) {
            mResolvedInheritedFiles.add(digestsFile);

            final File signatureFile = ApkChecksums.findSignatureForDigests(digestsFile);
            if (signatureFile != null) {
                mResolvedInheritedFiles.add(signatureFile);
            }
        }
    }

    @GuardedBy("mLock")
    private void assertApkConsistentLocked(String tag, ApkLite apk)
            throws PackageManagerException {
        assertPackageConsistentLocked(tag, apk.getPackageName(), apk.getLongVersionCode());
        if (!mSigningDetails.signaturesMatchExactly(apk.getSigningDetails())) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    tag + " signatures are inconsistent");
        }
    }

    @GuardedBy("mLock")
    private void assertPackageConsistentLocked(String tag, String packageName,
            long versionCode) throws PackageManagerException {
        if (!mPackageName.equals(packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag + " package "
                    + packageName + " inconsistent with " + mPackageName);
        }
        if (params.appPackageName != null && !params.appPackageName.equals(packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag
                    + " specified package " + params.appPackageName
                    + " inconsistent with " + packageName);
        }
        if (mVersionCode != versionCode) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK, tag
                    + " version code " + versionCode + " inconsistent with "
                    + mVersionCode);
        }
    }

    private SigningDetails unsafeGetCertsWithoutVerification(String path)
            throws PackageManagerException {
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<SigningDetails> result =
                ApkSignatureVerifier.unsafeGetCertsWithoutVerification(
                        input, path, SigningDetails.SignatureSchemeVersion.JAR);
        if (result.isError()) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Couldn't obtain signatures from APK : " + path);
        }
        return result.getResult();
    }

    /**
     * Determine if creating hard links between source and destination is
     * possible. That is, do they all live on the same underlying device.
     */
    private static boolean isLinkPossible(List<File> fromFiles, File toDir) {
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
    @VisibleForTesting(visibility = PACKAGE)
    public String getPackageName() {
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

    long getCommittedMillis() {
        synchronized (mLock) {
            return committedMillis;
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

    SigningDetails getSigningDetails() {
        synchronized (mLock) {
            return mSigningDetails;
        }
    }

    PackageLite getPackageLite() {
        synchronized (mLock) {
            return mPackageLite;
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

    private void createOatDirs(String packageName, List<String> instructionSets, File fromDir)
            throws PackageManagerException {
        for (String instructionSet : instructionSets) {
            try {
                mInstaller.createOatDir(packageName, fromDir.getAbsolutePath(), instructionSet);
            } catch (InstallerException e) {
                throw PackageManagerException.from(e);
            }
        }
    }

    private void linkFile(String packageName, String relativePath, String fromBase, String toBase)
            throws IOException {
        try {
            // Try
            final IncrementalFileStorages incrementalFileStorages = getIncrementalFileStorages();
            if (incrementalFileStorages != null && incrementalFileStorages.makeLink(relativePath,
                    fromBase, toBase)) {
                return;
            }
            mInstaller.linkFile(packageName, relativePath, fromBase, toBase);
        } catch (InstallerException | IOException e) {
            throw new IOException("failed linkOrCreateDir(" + relativePath + ", "
                    + fromBase + ", " + toBase + ")", e);
        }
    }

    private void linkFiles(String packageName, List<File> fromFiles, File toDir, File fromDir)
            throws IOException {
        for (File fromFile : fromFiles) {
            final String relativePath = getRelativePath(fromFile, fromDir);
            final String fromBase = fromDir.getAbsolutePath();
            final String toBase = toDir.getAbsolutePath();

            linkFile(packageName, relativePath, fromBase, toBase);
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

    private void extractNativeLibraries(PackageLite packageLite, File packageDir,
            String abiOverride, boolean inherit)
            throws PackageManagerException {
        Objects.requireNonNull(packageLite);
        final File libDir = new File(packageDir, NativeLibraryHelper.LIB_DIR_NAME);
        if (!inherit) {
            // Start from a clean slate
            NativeLibraryHelper.removeNativeBinariesFromDirLI(libDir, true);
        }

        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(packageLite);
            final int res = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libDir,
                    abiOverride, isIncrementalInstallation());
            if (res != INSTALL_SUCCEEDED) {
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
        if (!isSealed()) {
            throw new SecurityException("Must be sealed to accept permissions");
        }

        PackageInstallerSession root = hasParentSessionId()
                ? mSessionProvider.getSession(getParentSessionId()) : this;

        if (accepted) {
            // Mark and kick off another install pass
            synchronized (mLock) {
                mPermissionsManuallyAccepted = true;
            }
            root.mHandler.obtainMessage(MSG_INSTALL).sendToTarget();
        } else {
            root.destroy();
            root.dispatchSessionFinished(INSTALL_FAILED_ABORTED, "User rejected permissions", null);
            root.maybeFinishChildSessions(INSTALL_FAILED_ABORTED, "User rejected permissions");
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
                assertCallerIsOwnerOrRoot();
            }

            activeCount = mActiveCount.decrementAndGet();
        }

        if (activeCount == 0) {
            mCallback.onSessionActiveChanged(this, false);
        }
    }

    /**
     * Calls dispatchSessionFinished() on all child sessions with the given error code and
     * error message to prevent orphaned child sessions.
     */
    private void maybeFinishChildSessions(int returnCode, String msg) {
        for (PackageInstallerSession child : getChildSessions()) {
            child.dispatchSessionFinished(returnCode, msg, null);
        }
    }

    private void assertNotChild(String cookie) {
        if (hasParentSessionId()) {
            throw new IllegalStateException(cookie + " can't be called on a child session, id="
                    + sessionId + " parentId=" + getParentSessionId());
        }
    }

    /**
     * Called when verification has completed. Now it is safe to clean up the session
     * if {@link #abandon()} has been called previously.
     *
     * @return True if this session has been abandoned.
     */
    private boolean dispatchPendingAbandonCallback() {
        final Runnable callback;
        synchronized (mLock) {
            Preconditions.checkState(mStageDirInUse);
            mStageDirInUse = false;
            callback = mPendingAbandonCallback;
            mPendingAbandonCallback = null;
        }
        if (callback != null) {
            callback.run();
            return true;
        }
        return false;
    }

    @Override
    public void abandon() {
        final Runnable r;
        synchronized (mLock) {
            assertNotChild("abandon");
            assertCallerIsOwnerOrRootOrSystem();
            if (isInTerminalState()) {
                // Finalized sessions have been properly cleaned up. No need to abandon them.
                return;
            }
            mDestroyed = true;
            r = () -> {
                assertNotLocked("abandonStaged");
                if (isStaged() && mCommitted.get()) {
                    mStagingManager.abortCommittedSession(mStagedSession);
                }
                destroy();
                dispatchSessionFinished(INSTALL_FAILED_ABORTED, "Session was abandoned", null);
                maybeFinishChildSessions(INSTALL_FAILED_ABORTED,
                        "Session was abandoned because the parent session is abandoned");
            };
            if (mStageDirInUse) {
                // Verification is ongoing, not safe to clean up the session yet.
                mPendingAbandonCallback = r;
                mCallback.onSessionChanged(this);
                return;
            }
        }
        r.run();
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
    public int getInstallFlags() {
        return params.installFlags;
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
            assertCallerIsOwnerOrRoot();
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
            assertCallerIsOwnerOrRoot();
            assertPreparedAndNotSealedLocked("removeFile");

            if (!mFiles.add(new FileEntry(mFiles.size(),
                    new InstallationFile(location, getRemoveMarkerName(name), -1, null, null)))) {
                throw new IllegalArgumentException("File already removed: " + name);
            }
        }
    }

    /**
     * Makes sure files are present in staging location.
     * @return if the image is ready for installation
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

        final DataLoaderParams params = this.params.dataLoaderParams;
        final boolean manualStartAndDestroy = !isIncrementalInstallation();
        final boolean systemDataLoader = isSystemDataLoaderInstallation();
        final IDataLoaderStatusListener statusListener = new IDataLoaderStatusListener.Stub() {
            @Override
            public void onStatusChanged(int dataLoaderId, int status) {
                switch (status) {
                    case IDataLoaderStatusListener.DATA_LOADER_BINDING:
                    case IDataLoaderStatusListener.DATA_LOADER_STOPPED:
                    case IDataLoaderStatusListener.DATA_LOADER_DESTROYED:
                        return;
                }

                if (mDestroyed || mDataLoaderFinished) {
                    switch (status) {
                        case IDataLoaderStatusListener.DATA_LOADER_UNRECOVERABLE:
                            if (systemDataLoader) {
                                onSystemDataLoaderUnrecoverable();
                            }
                            return;
                    }
                    return;
                }
                try {
                    switch (status) {
                        case IDataLoaderStatusListener.DATA_LOADER_BOUND: {
                            if (manualStartAndDestroy) {
                                FileSystemControlParcel control = new FileSystemControlParcel();
                                control.callback = new FileSystemConnector(addedFiles);
                                getDataLoader(dataLoaderId).create(dataLoaderId, params.getData(),
                                        control, this);
                            }

                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_CREATED: {
                            if (manualStartAndDestroy) {
                                // IncrementalFileStorages will call start after all files are
                                // created in IncFS.
                                getDataLoader(dataLoaderId).start(dataLoaderId);
                            }
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_STARTED: {
                            getDataLoader(dataLoaderId).prepareImage(
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
                                        getParentSessionId()).dispatchSessionSealed();
                            } else {
                                dispatchSessionSealed();
                            }
                            if (manualStartAndDestroy) {
                                getDataLoader(dataLoaderId).destroy(dataLoaderId);
                            }
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_IMAGE_NOT_READY: {
                            mDataLoaderFinished = true;
                            dispatchSessionValidationFailure(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                                    "Failed to prepare image.");
                            if (manualStartAndDestroy) {
                                getDataLoader(dataLoaderId).destroy(dataLoaderId);
                            }
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_UNAVAILABLE: {
                            // Don't fail or commit the session. Allow caller to commit again.
                            sendPendingStreaming(mContext, getRemoteStatusReceiver(), sessionId,
                                    "DataLoader unavailable");
                            break;
                        }
                        case IDataLoaderStatusListener.DATA_LOADER_UNRECOVERABLE:
                            throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                                    "DataLoader reported unrecoverable failure.");
                    }
                } catch (PackageManagerException e) {
                    mDataLoaderFinished = true;
                    dispatchSessionValidationFailure(e.error, ExceptionUtils.getCompleteMessage(e));
                } catch (RemoteException e) {
                    // In case of streaming failure we don't want to fail or commit the session.
                    // Just return from this method and allow caller to commit again.
                    sendPendingStreaming(mContext, getRemoteStatusReceiver(), sessionId,
                            e.getMessage());
                }
            }
        };

        if (!manualStartAndDestroy) {
            final PerUidReadTimeouts[] perUidReadTimeouts =
                    mPm.getPerUidReadTimeouts(mPm.snapshotComputer());

            final StorageHealthCheckParams healthCheckParams = new StorageHealthCheckParams();
            healthCheckParams.blockedTimeoutMs = INCREMENTAL_STORAGE_BLOCKED_TIMEOUT_MS;
            healthCheckParams.unhealthyTimeoutMs = INCREMENTAL_STORAGE_UNHEALTHY_TIMEOUT_MS;
            healthCheckParams.unhealthyMonitoringMs = INCREMENTAL_STORAGE_UNHEALTHY_MONITORING_MS;

            final IStorageHealthListener healthListener = new IStorageHealthListener.Stub() {
                @Override
                public void onHealthStatus(int storageId, int status) {
                    if (mDestroyed || mDataLoaderFinished) {
                        return;
                    }

                    switch (status) {
                        case IStorageHealthListener.HEALTH_STATUS_OK:
                            break;
                        case IStorageHealthListener.HEALTH_STATUS_READS_PENDING:
                        case IStorageHealthListener.HEALTH_STATUS_BLOCKED:
                            if (systemDataLoader) {
                                // It's OK for ADB data loader to wait for pages.
                                break;
                            }
                            // fallthrough
                        case IStorageHealthListener.HEALTH_STATUS_UNHEALTHY:
                            // Even ADB installation can't wait for missing pages for too long.
                            mDataLoaderFinished = true;
                            dispatchSessionValidationFailure(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                                    "Image is missing pages required for installation.");
                            break;
                    }
                }
            };

            try {
                final PackageInfo pkgInfo = mPm.snapshotComputer()
                        .getPackageInfo(this.params.appPackageName, 0, userId);
                final File inheritedDir =
                        (pkgInfo != null && pkgInfo.applicationInfo != null) ? new File(
                                pkgInfo.applicationInfo.getCodePath()).getParentFile() : null;

                if (mIncrementalFileStorages == null) {
                    mIncrementalFileStorages = IncrementalFileStorages.initialize(mContext,
                            stageDir, inheritedDir, params, statusListener, healthCheckParams,
                            healthListener, addedFiles, perUidReadTimeouts,
                            new IPackageLoadingProgressCallback.Stub() {
                                @Override
                                public void onPackageLoadingProgressChanged(float progress) {
                                    synchronized (mProgressLock) {
                                        mIncrementalProgress = progress;
                                        computeProgressLocked(true);
                                    }
                                }
                            });
                } else {
                    // Retrying commit.
                    mIncrementalFileStorages.startLoading(params, statusListener, healthCheckParams,
                            healthListener, perUidReadTimeouts);
                }
                return false;
            } catch (IOException e) {
                throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE, e.getMessage(),
                        e.getCause());
            }
        }

        final long bindDelayMs = 0;
        if (!getDataLoaderManager().bindToDataLoader(sessionId, params.getData(), bindDelayMs,
                statusListener)) {
            throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failed to initialize data loader");
        }

        return false;
    }

    private DataLoaderManager getDataLoaderManager() throws PackageManagerException {
        DataLoaderManager dataLoaderManager = mContext.getSystemService(DataLoaderManager.class);
        if (dataLoaderManager == null) {
            throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failed to find data loader manager service");
        }
        return dataLoaderManager;
    }

    private IDataLoader getDataLoader(int dataLoaderId) throws PackageManagerException {
        IDataLoader dataLoader = getDataLoaderManager().getDataLoader(dataLoaderId);
        if (dataLoader == null) {
            throw new PackageManagerException(INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failure to obtain data loader");
        }
        return dataLoader;
    }

    private void dispatchSessionValidationFailure(int error, String detailMessage) {
        mHandler.obtainMessage(MSG_SESSION_VALIDATION_FAILURE, error, -1,
                detailMessage).sendToTarget();
    }

    @GuardedBy("mLock")
    private int[] getChildSessionIdsLocked() {
        int size = mChildSessions.size();
        if (size == 0) {
            return EMPTY_CHILD_SESSION_ARRAY;
        }
        final int[] childSessionIds = new int[size];
        for (int i = 0; i < size; ++i) {
            childSessionIds[i] = mChildSessions.keyAt(i);
        }
        return childSessionIds;
    }

    @Override
    public int[] getChildSessionIds() {
        synchronized (mLock) {
            return getChildSessionIdsLocked();
        }
    }

    private boolean canBeAddedAsChild(int parentCandidate) {
        synchronized (mLock) {
            return (!hasParentSessionId() || mParentSessionId == parentCandidate)
                    && !mCommitted.get()
                    && !mDestroyed;
        }
    }

    private void acquireTransactionLock() {
        if (!mTransactionLock.compareAndSet(false, true)) {
            throw new UnsupportedOperationException("Concurrent access not supported");
        }
    }

    private void releaseTransactionLock() {
        mTransactionLock.compareAndSet(true, false);
    }

    @Override
    public void addChildSessionId(int childSessionId) {
        if (!params.isMultiPackage) {
            throw new IllegalStateException("Single-session " + sessionId + " can't have child.");
        }

        final PackageInstallerSession childSession = mSessionProvider.getSession(childSessionId);
        if (childSession == null) {
            throw new IllegalStateException("Unable to add child session " + childSessionId
                    + " as it does not exist.");
        }
        if (childSession.params.isMultiPackage) {
            throw new IllegalStateException("Multi-session " + childSessionId
                    + " can't be a child.");
        }
        if (params.isStaged != childSession.params.isStaged) {
            throw new IllegalStateException("Multipackage Inconsistency: session "
                    + childSession.sessionId + " and session " + sessionId
                    + " have inconsistent staged settings");
        }
        if (params.getEnableRollback() != childSession.params.getEnableRollback()) {
            throw new IllegalStateException("Multipackage Inconsistency: session "
                    + childSession.sessionId + " and session " + sessionId
                    + " have inconsistent rollback settings");
        }
        boolean hasAPK = containsApkSession() || !childSession.isApexSession();
        boolean hasAPEX = sessionContains(s -> s.isApexSession()) || childSession.isApexSession();
        if (!params.isStaged && hasAPK && hasAPEX) {
            throw new IllegalStateException("Mix of APK and APEX is not supported for "
                    + "non-staged multi-package session");
        }

        try {
            acquireTransactionLock();
            childSession.acquireTransactionLock();

            if (!childSession.canBeAddedAsChild(sessionId)) {
                throw new IllegalStateException("Unable to add child session " + childSessionId
                        + " as it is in an invalid state.");
            }
            synchronized (mLock) {
                assertCallerIsOwnerOrRoot();
                assertPreparedAndNotSealedLocked("addChildSessionId");

                final int indexOfSession = mChildSessions.indexOfKey(childSessionId);
                if (indexOfSession >= 0) {
                    return;
                }
                childSession.setParentSessionId(this.sessionId);
                mChildSessions.put(childSessionId, childSession);
            }
        } finally {
            releaseTransactionLock();
            childSession.releaseTransactionLock();
        }
    }

    @Override
    public void removeChildSessionId(int sessionId) {
        synchronized (mLock) {
            assertCallerIsOwnerOrRoot();
            assertPreparedAndNotSealedLocked("removeChildSessionId");

            final int indexOfSession = mChildSessions.indexOfKey(sessionId);
            if (indexOfSession < 0) {
                // not added in the first place; no-op
                return;
            }
            PackageInstallerSession session = mChildSessions.valueAt(indexOfSession);
            try {
                acquireTransactionLock();
                session.acquireTransactionLock();
                session.setParentSessionId(SessionInfo.INVALID_ID);
                mChildSessions.removeAt(indexOfSession);
            } finally {
                releaseTransactionLock();
                session.releaseTransactionLock();
            }
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
        synchronized (mLock) {
            return mParentSessionId != SessionInfo.INVALID_ID;
        }
    }

    @Override
    public int getParentSessionId() {
        synchronized (mLock) {
            return mParentSessionId;
        }
    }

    private void dispatchSessionFinished(int returnCode, String msg, Bundle extras) {
        sendUpdateToRemoteStatusReceiver(returnCode, msg, extras);

        synchronized (mLock) {
            mFinalStatus = returnCode;
            mFinalMessage = msg;
        }

        final boolean success = (returnCode == INSTALL_SUCCEEDED);

        // Send broadcast to default launcher only if it's a new install
        // TODO(b/144270665): Secure the usage of this broadcast.
        final boolean isNewInstall = extras == null || !extras.getBoolean(Intent.EXTRA_REPLACING);
        if (success && isNewInstall && mPm.mInstallerService.okToSendBroadcasts()) {
            mPm.sendSessionCommitBroadcast(generateInfoScrubbed(true /*icon*/), userId);
        }

        mCallback.onSessionFinished(this, success);
        if (isDataLoaderInstallation()) {
            logDataLoaderInstallationSession(returnCode);
        }
    }

    private void sendUpdateToRemoteStatusReceiver(int returnCode, String msg, Bundle extras) {
        final IntentSender statusReceiver = getRemoteStatusReceiver();
        if (statusReceiver != null) {
            // Execute observer.onPackageInstalled on different thread as we don't want callers
            // inside the system server have to worry about catching the callbacks while they are
            // calling into the session
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = getPackageName();
            args.arg2 = msg;
            args.arg3 = extras;
            args.arg4 = statusReceiver;
            args.argi1 = returnCode;
            mHandler.obtainMessage(MSG_ON_PACKAGE_INSTALLED, args).sendToTarget();
        }
    }

    void setSessionReady() {
        synchronized (mLock) {
            // Do not allow destroyed/failed session to change state
            if (mDestroyed || mSessionFailed) return;
            mSessionReady = true;
            mSessionApplied = false;
            mSessionFailed = false;
            mSessionErrorCode = PackageManager.INSTALL_UNKNOWN;
            mSessionErrorMessage = "";
        }
        mCallback.onSessionChanged(this);
    }

    void setSessionFailed(int errorCode, String errorMessage) {
        synchronized (mLock) {
            // Do not allow destroyed/failed session to change state
            if (mDestroyed || mSessionFailed) return;
            mSessionReady = false;
            mSessionApplied = false;
            mSessionFailed = true;
            mSessionErrorCode = errorCode;
            mSessionErrorMessage = errorMessage;
            Slog.d(TAG, "Marking session " + sessionId + " as failed: " + errorMessage);
        }
        destroy();
        mCallback.onSessionChanged(this);
    }

    private void setSessionApplied() {
        synchronized (mLock) {
            // Do not allow destroyed/failed session to change state
            if (mDestroyed || mSessionFailed) return;
            mSessionReady = false;
            mSessionApplied = true;
            mSessionFailed = false;
            mSessionErrorCode = INSTALL_SUCCEEDED;
            mSessionErrorMessage = "";
            Slog.d(TAG, "Marking session " + sessionId + " as applied");
        }
        destroy();
        mCallback.onSessionChanged(this);
    }

    /** {@hide} */
    boolean isSessionReady() {
        synchronized (mLock) {
            return mSessionReady;
        }
    }

    /** {@hide} */
    boolean isSessionApplied() {
        synchronized (mLock) {
            return mSessionApplied;
        }
    }

    /** {@hide} */
    boolean isSessionFailed() {
        synchronized (mLock) {
            return mSessionFailed;
        }
    }

    /** {@hide} */
    int getSessionErrorCode() {
        synchronized (mLock) {
            return mSessionErrorCode;
        }
    }

    /** {@hide} */
    String getSessionErrorMessage() {
        synchronized (mLock) {
            return mSessionErrorMessage;
        }
    }

    /**
     * Free up storage used by this session and its children.
     * Must not be called on a child session.
     */
    private void destroy() {
        // TODO(b/173194203): destroy() is called indirectly by
        //  PackageInstallerService#restoreAndApplyStagedSessionIfNeeded on an orphan child session.
        //  Enable this assertion when we figure out a better way to clean up orphan sessions.
        // assertNotChild("destroy");

        // TODO(b/173194203): destroyInternal() should be used by destroy() only.
        //  For the sake of consistency, a session should be destroyed as a whole. The caller
        //  should always call destroy() for cleanup without knowing it has child sessions or not.
        destroyInternal();
        for (PackageInstallerSession child : getChildSessions()) {
            child.destroyInternal();
        }
    }

    /**
     * Free up storage used by this session.
     */
    private void destroyInternal() {
        final IncrementalFileStorages incrementalFileStorages;
        synchronized (mLock) {
            mSealed = true;
            if (!params.isStaged) {
                mDestroyed = true;
            }
            // Force shut down all bridges
            for (RevocableFileDescriptor fd : mFds) {
                fd.revoke();
            }
            for (FileBridge bridge : mBridges) {
                bridge.forceClose();
            }
            incrementalFileStorages = mIncrementalFileStorages;
            mIncrementalFileStorages = null;
        }
        try {
            if (incrementalFileStorages != null) {
                incrementalFileStorages.cleanUpAndMarkComplete();
            }
            if (stageDir != null) {
                final String tempPackageName = stageDir.getName();
                mInstaller.rmPackageDir(tempPackageName, stageDir.getAbsolutePath());
            }
        } catch (InstallerException ignored) {
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
        pw.printPair("mOriginalInstallerPackageName", mOriginalInstallerPackageName);
        pw.printPair("installerPackageName", mInstallSource.installerPackageName);
        pw.printPair("installInitiatingPackageName", mInstallSource.initiatingPackageName);
        pw.printPair("installOriginatingPackageName", mInstallSource.originatingPackageName);
        pw.printPair("mInstallerUid", mInstallerUid);
        pw.printPair("createdMillis", createdMillis);
        pw.printPair("updatedMillis", updatedMillis);
        pw.printPair("committedMillis", committedMillis);
        pw.printPair("stageDir", stageDir);
        pw.printPair("stageCid", stageCid);
        pw.println();

        params.dump(pw);

        final float clientProgress;
        final float progress;
        synchronized (mProgressLock) {
            clientProgress = mClientProgress;
            progress = mProgress;
        }
        pw.printPair("mClientProgress", clientProgress);
        pw.printPair("mProgress", progress);
        pw.printPair("mCommitted", mCommitted);
        pw.printPair("mSealed", mSealed);
        pw.printPair("mPermissionsManuallyAccepted", mPermissionsManuallyAccepted);
        pw.printPair("mStageDirInUse", mStageDirInUse);
        pw.printPair("mDestroyed", mDestroyed);
        pw.printPair("mFds", mFds.size());
        pw.printPair("mBridges", mBridges.size());
        pw.printPair("mFinalStatus", mFinalStatus);
        pw.printPair("mFinalMessage", mFinalMessage);
        pw.printPair("params.isMultiPackage", params.isMultiPackage);
        pw.printPair("params.isStaged", params.isStaged);
        pw.printPair("mParentSessionId", mParentSessionId);
        pw.printPair("mChildSessionIds", getChildSessionIdsLocked());
        pw.printPair("mSessionApplied", mSessionApplied);
        pw.printPair("mSessionFailed", mSessionFailed);
        pw.printPair("mSessionReady", mSessionReady);
        pw.printPair("mSessionErrorCode", mSessionErrorCode);
        pw.printPair("mSessionErrorMessage", mSessionErrorMessage);
        pw.println();

        pw.decreaseIndent();
    }

    /**
     * This method doesn't change internal states and is safe to call outside the lock.
     */
    private static void sendOnUserActionRequired(Context context, IntentSender target,
            int sessionId, Intent intent) {
        final Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION);
        fillIn.putExtra(Intent.EXTRA_INTENT, intent);
        try {
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            target.sendIntent(context, 0, fillIn, null /* onFinished */,
                    null /* handler */, null /* requiredPermission */, options.toBundle());
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    /**
     * This method doesn't change internal states and is safe to call outside the lock.
     */
    private static void sendOnPackageInstalled(Context context, IntentSender target, int sessionId,
            boolean showNotification, int userId, String basePackageName, int returnCode,
            String msg, Bundle extras) {
        if (INSTALL_SUCCEEDED == returnCode && showNotification) {
            boolean update = (extras != null) && extras.getBoolean(Intent.EXTRA_REPLACING);
            Notification notification = PackageInstallerService.buildSuccessNotification(context,
                    getDeviceOwnerInstalledPackageMsg(context, update),
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
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            target.sendIntent(context, 0, fillIn, null /* onFinished */,
                    null /* handler */, null /* requiredPermission */, options.toBundle());
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    private static String getDeviceOwnerInstalledPackageMsg(Context context, boolean update) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return update
                ? dpm.getResources().getString(PACKAGE_UPDATED_BY_DO,
                    () -> context.getString(R.string.package_updated_device_owner))
                : dpm.getResources().getString(PACKAGE_INSTALLED_BY_DO,
                    () -> context.getString(R.string.package_installed_device_owner));
    }

    /**
     * This method doesn't change internal states and is safe to call outside the lock.
     */
    private static void sendPendingStreaming(Context context, IntentSender target, int sessionId,
            @Nullable String cause) {
        if (target == null) {
            Slog.e(TAG, "Missing receiver for pending streaming status.");
            return;
        }

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
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            target.sendIntent(context, 0, intent, null /* onFinished */,
                    null /* handler */, null /* requiredPermission */, options.toBundle());
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    private static void writeGrantedRuntimePermissionsLocked(TypedXmlSerializer out,
            String[] grantedRuntimePermissions) throws IOException {
        if (grantedRuntimePermissions != null) {
            for (String permission : grantedRuntimePermissions) {
                out.startTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
                writeStringAttribute(out, ATTR_NAME, permission);
                out.endTag(null, TAG_GRANTED_RUNTIME_PERMISSION);
            }
        }
    }

    private static void writeWhitelistedRestrictedPermissionsLocked(@NonNull TypedXmlSerializer out,
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

    private static void writeAutoRevokePermissionsMode(@NonNull TypedXmlSerializer out, int mode)
            throws IOException {
        out.startTag(null, TAG_AUTO_REVOKE_PERMISSIONS_MODE);
        out.attributeInt(null, ATTR_MODE, mode);
        out.endTag(null, TAG_AUTO_REVOKE_PERMISSIONS_MODE);
    }


    private static File buildAppIconFile(int sessionId, @NonNull File sessionsDir) {
        return new File(sessionsDir, "app_icon." + sessionId + ".png");
    }

    /**
     * Write this session to a {@link TypedXmlSerializer}.
     *
     * @param out Where to write the session to
     * @param sessionsDir The directory containing the sessions
     */
    void write(@NonNull TypedXmlSerializer out, @NonNull File sessionsDir) throws IOException {
        synchronized (mLock) {
            if (mDestroyed && !params.isStaged) {
                return;
            }

            out.startTag(null, TAG_SESSION);

            out.attributeInt(null, ATTR_SESSION_ID, sessionId);
            out.attributeInt(null, ATTR_USER_ID, userId);
            writeStringAttribute(out, ATTR_INSTALLER_PACKAGE_NAME,
                    mInstallSource.installerPackageName);
            writeStringAttribute(out, ATTR_INSTALLER_ATTRIBUTION_TAG,
                    mInstallSource.installerAttributionTag);
            out.attributeInt(null, ATTR_INSTALLER_UID, mInstallerUid);
            writeStringAttribute(out, ATTR_INITIATING_PACKAGE_NAME,
                    mInstallSource.initiatingPackageName);
            writeStringAttribute(out, ATTR_ORIGINATING_PACKAGE_NAME,
                    mInstallSource.originatingPackageName);
            out.attributeLong(null, ATTR_CREATED_MILLIS, createdMillis);
            out.attributeLong(null, ATTR_UPDATED_MILLIS, updatedMillis);
            out.attributeLong(null, ATTR_COMMITTED_MILLIS, committedMillis);
            if (stageDir != null) {
                writeStringAttribute(out, ATTR_SESSION_STAGE_DIR,
                        stageDir.getAbsolutePath());
            }
            if (stageCid != null) {
                writeStringAttribute(out, ATTR_SESSION_STAGE_CID, stageCid);
            }
            writeBooleanAttribute(out, ATTR_PREPARED, mPrepared);
            writeBooleanAttribute(out, ATTR_COMMITTED, mCommitted.get());
            writeBooleanAttribute(out, ATTR_DESTROYED, mDestroyed);
            writeBooleanAttribute(out, ATTR_SEALED, mSealed);

            writeBooleanAttribute(out, ATTR_MULTI_PACKAGE, params.isMultiPackage);
            writeBooleanAttribute(out, ATTR_STAGED_SESSION, params.isStaged);
            writeBooleanAttribute(out, ATTR_IS_READY, mSessionReady);
            writeBooleanAttribute(out, ATTR_IS_FAILED, mSessionFailed);
            writeBooleanAttribute(out, ATTR_IS_APPLIED, mSessionApplied);
            out.attributeInt(null, ATTR_PACKAGE_SOURCE, params.packageSource);
            out.attributeInt(null, ATTR_SESSION_ERROR_CODE, mSessionErrorCode);
            writeStringAttribute(out, ATTR_SESSION_ERROR_MESSAGE, mSessionErrorMessage);
            // TODO(patb,109941548): avoid writing to xml and instead infer / validate this after
            //                       we've read all sessions.
            out.attributeInt(null, ATTR_PARENT_SESSION_ID, mParentSessionId);
            out.attributeInt(null, ATTR_MODE, params.mode);
            out.attributeInt(null, ATTR_INSTALL_FLAGS, params.installFlags);
            out.attributeInt(null, ATTR_INSTALL_LOCATION, params.installLocation);
            out.attributeLong(null, ATTR_SIZE_BYTES, params.sizeBytes);
            writeStringAttribute(out, ATTR_APP_PACKAGE_NAME, params.appPackageName);
            writeStringAttribute(out, ATTR_APP_LABEL, params.appLabel);
            writeUriAttribute(out, ATTR_ORIGINATING_URI, params.originatingUri);
            out.attributeInt(null, ATTR_ORIGINATING_UID, params.originatingUid);
            writeUriAttribute(out, ATTR_REFERRER_URI, params.referrerUri);
            writeStringAttribute(out, ATTR_ABI_OVERRIDE, params.abiOverride);
            writeStringAttribute(out, ATTR_VOLUME_UUID, params.volumeUuid);
            out.attributeInt(null, ATTR_INSTALL_REASON, params.installReason);

            final boolean isDataLoader = params.dataLoaderParams != null;
            writeBooleanAttribute(out, ATTR_IS_DATALOADER, isDataLoader);
            if (isDataLoader) {
                out.attributeInt(null, ATTR_DATALOADER_TYPE, params.dataLoaderParams.getType());
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
            final int[] childSessionIds = getChildSessionIdsLocked();
            for (int childSessionId : childSessionIds) {
                out.startTag(null, TAG_CHILD_SESSION);
                out.attributeInt(null, ATTR_SESSION_ID, childSessionId);
                out.endTag(null, TAG_CHILD_SESSION);
            }

            final InstallationFile[] files = getInstallationFilesLocked();
            for (InstallationFile file : files) {
                out.startTag(null, TAG_SESSION_FILE);
                out.attributeInt(null, ATTR_LOCATION, file.getLocation());
                writeStringAttribute(out, ATTR_NAME, file.getName());
                out.attributeLong(null, ATTR_LENGTH_BYTES, file.getLengthBytes());
                writeByteArrayAttribute(out, ATTR_METADATA, file.getMetadata());
                writeByteArrayAttribute(out, ATTR_SIGNATURE, file.getSignature());
                out.endTag(null, TAG_SESSION_FILE);
            }

            for (int i = 0, isize = mChecksums.size(); i < isize; ++i) {
                final String fileName = mChecksums.keyAt(i);
                final PerFileChecksum perFileChecksum = mChecksums.valueAt(i);
                final Checksum[] checksums = perFileChecksum.getChecksums();
                for (Checksum checksum : checksums) {
                    out.startTag(null, TAG_SESSION_CHECKSUM);
                    writeStringAttribute(out, ATTR_NAME, fileName);
                    out.attributeInt(null, ATTR_CHECKSUM_KIND, checksum.getType());
                    writeByteArrayAttribute(out, ATTR_CHECKSUM_VALUE, checksum.getValue());
                    out.endTag(null, TAG_SESSION_CHECKSUM);
                }
            }
            for (int i = 0, isize = mChecksums.size(); i < isize; ++i) {
                final String fileName = mChecksums.keyAt(i);
                final PerFileChecksum perFileChecksum = mChecksums.valueAt(i);
                final byte[] signature = perFileChecksum.getSignature();
                if (signature == null || signature.length == 0) {
                    continue;
                }
                out.startTag(null, TAG_SESSION_CHECKSUM_SIGNATURE);
                writeStringAttribute(out, ATTR_NAME, fileName);
                writeByteArrayAttribute(out, ATTR_SIGNATURE, signature);
                out.endTag(null, TAG_SESSION_CHECKSUM_SIGNATURE);
            }

        }

        out.endTag(null, TAG_SESSION);
    }

    // Validity check to be performed when the session is restored from an external file. Only one
    // of the session states should be true, or none of them.
    private static boolean isStagedSessionStateValid(boolean isReady, boolean isApplied,
                                                     boolean isFailed) {
        return (!isReady && !isApplied && !isFailed)
                || (isReady && !isApplied && !isFailed)
                || (!isReady && isApplied && !isFailed)
                || (!isReady && !isApplied && isFailed);
    }

    /**
     * Read new session from a {@link TypedXmlPullParser xml description} and create it.
     *
     * @param in The source of the description
     * @param callback Callback the session uses to notify about changes of it's state
     * @param context Context to be used by the session
     * @param pm PackageManager to use by the session
     * @param installerThread Thread to be used for callbacks of this session
     * @param sessionsDir The directory the sessions are stored in
     *
     * @param sessionProvider to get the other PackageInstallerSession instance by sessionId.
     * @return The newly created session
     */
    public static PackageInstallerSession readFromXml(@NonNull TypedXmlPullParser in,
            @NonNull PackageInstallerService.InternalCallback callback, @NonNull Context context,
            @NonNull PackageManagerService pm, Looper installerThread,
            @NonNull StagingManager stagingManager, @NonNull File sessionsDir,
            @NonNull PackageSessionProvider sessionProvider,
            @NonNull SilentUpdatePolicy silentUpdatePolicy)
            throws IOException, XmlPullParserException {
        final int sessionId = in.getAttributeInt(null, ATTR_SESSION_ID);
        final int userId = in.getAttributeInt(null, ATTR_USER_ID);
        final String installerPackageName = readStringAttribute(in, ATTR_INSTALLER_PACKAGE_NAME);
        final String installerAttributionTag = readStringAttribute(in,
                ATTR_INSTALLER_ATTRIBUTION_TAG);
        final int installerUid = in.getAttributeInt(null, ATTR_INSTALLER_UID, pm.snapshotComputer()
                .getPackageUid(installerPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        userId));
        final String installInitiatingPackageName =
                readStringAttribute(in, ATTR_INITIATING_PACKAGE_NAME);
        final String installOriginatingPackageName =
                readStringAttribute(in, ATTR_ORIGINATING_PACKAGE_NAME);
        final long createdMillis = in.getAttributeLong(null, ATTR_CREATED_MILLIS);
        long updatedMillis = in.getAttributeLong(null, ATTR_UPDATED_MILLIS);
        final long committedMillis = in.getAttributeLong(null, ATTR_COMMITTED_MILLIS, 0L);
        final String stageDirRaw = readStringAttribute(in, ATTR_SESSION_STAGE_DIR);
        final File stageDir = (stageDirRaw != null) ? new File(stageDirRaw) : null;
        final String stageCid = readStringAttribute(in, ATTR_SESSION_STAGE_CID);
        final boolean prepared = in.getAttributeBoolean(null, ATTR_PREPARED, true);
        final boolean committed = in.getAttributeBoolean(null, ATTR_COMMITTED, false);
        final boolean destroyed = in.getAttributeBoolean(null, ATTR_DESTROYED, false);
        final boolean sealed = in.getAttributeBoolean(null, ATTR_SEALED, false);
        final int parentSessionId = in.getAttributeInt(null, ATTR_PARENT_SESSION_ID,
                SessionInfo.INVALID_ID);

        final SessionParams params = new SessionParams(
                SessionParams.MODE_INVALID);
        params.isMultiPackage = in.getAttributeBoolean(null, ATTR_MULTI_PACKAGE, false);
        params.isStaged = in.getAttributeBoolean(null, ATTR_STAGED_SESSION, false);
        params.mode = in.getAttributeInt(null, ATTR_MODE);
        params.installFlags = in.getAttributeInt(null, ATTR_INSTALL_FLAGS);
        params.installLocation = in.getAttributeInt(null, ATTR_INSTALL_LOCATION);
        params.sizeBytes = in.getAttributeLong(null, ATTR_SIZE_BYTES);
        params.appPackageName = readStringAttribute(in, ATTR_APP_PACKAGE_NAME);
        params.appIcon = readBitmapAttribute(in, ATTR_APP_ICON);
        params.appLabel = readStringAttribute(in, ATTR_APP_LABEL);
        params.originatingUri = readUriAttribute(in, ATTR_ORIGINATING_URI);
        params.originatingUid =
                in.getAttributeInt(null, ATTR_ORIGINATING_UID, SessionParams.UID_UNKNOWN);
        params.referrerUri = readUriAttribute(in, ATTR_REFERRER_URI);
        params.abiOverride = readStringAttribute(in, ATTR_ABI_OVERRIDE);
        params.volumeUuid = readStringAttribute(in, ATTR_VOLUME_UUID);
        params.installReason = in.getAttributeInt(null, ATTR_INSTALL_REASON);
        params.packageSource = in.getAttributeInt(null, ATTR_PACKAGE_SOURCE);

        if (in.getAttributeBoolean(null, ATTR_IS_DATALOADER, false)) {
            params.dataLoaderParams = new DataLoaderParams(
                    in.getAttributeInt(null, ATTR_DATALOADER_TYPE),
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
        final boolean isReady = in.getAttributeBoolean(null, ATTR_IS_READY, false);
        final boolean isFailed = in.getAttributeBoolean(null, ATTR_IS_FAILED, false);
        final boolean isApplied = in.getAttributeBoolean(null, ATTR_IS_APPLIED, false);
        final int sessionErrorCode = in.getAttributeInt(null, ATTR_SESSION_ERROR_CODE,
                PackageManager.INSTALL_UNKNOWN);
        final String sessionErrorMessage = readStringAttribute(in, ATTR_SESSION_ERROR_MESSAGE);

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
        IntArray childSessionIds = new IntArray();
        List<InstallationFile> files = new ArrayList<>();
        ArrayMap<String, List<Checksum>> checksums = new ArrayMap<>();
        ArrayMap<String, byte[]> signatures = new ArrayMap<>();
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
                autoRevokePermissionsMode = in.getAttributeInt(null, ATTR_MODE);
            }
            if (TAG_CHILD_SESSION.equals(in.getName())) {
                childSessionIds.add(in.getAttributeInt(null, ATTR_SESSION_ID,
                        SessionInfo.INVALID_ID));
            }
            if (TAG_SESSION_FILE.equals(in.getName())) {
                files.add(new InstallationFile(
                        in.getAttributeInt(null, ATTR_LOCATION, 0),
                        readStringAttribute(in, ATTR_NAME),
                        in.getAttributeLong(null, ATTR_LENGTH_BYTES, -1),
                        readByteArrayAttribute(in, ATTR_METADATA),
                        readByteArrayAttribute(in, ATTR_SIGNATURE)));
            }
            if (TAG_SESSION_CHECKSUM.equals(in.getName())) {
                final String fileName = readStringAttribute(in, ATTR_NAME);
                final Checksum checksum = new Checksum(
                        in.getAttributeInt(null, ATTR_CHECKSUM_KIND, 0),
                        readByteArrayAttribute(in, ATTR_CHECKSUM_VALUE));

                List<Checksum> fileChecksums = checksums.get(fileName);
                if (fileChecksums == null) {
                    fileChecksums = new ArrayList<>();
                    checksums.put(fileName, fileChecksums);
                }
                fileChecksums.add(checksum);
            }
            if (TAG_SESSION_CHECKSUM_SIGNATURE.equals(in.getName())) {
                final String fileName = readStringAttribute(in, ATTR_NAME);
                final byte[] signature = readByteArrayAttribute(in, ATTR_SIGNATURE);
                signatures.put(fileName, signature);
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

        ArrayMap<String, PerFileChecksum> checksumsMap = null;
        if (!checksums.isEmpty()) {
            checksumsMap = new ArrayMap<>(checksums.size());
            for (int i = 0, isize = checksums.size(); i < isize; ++i) {
                final String fileName = checksums.keyAt(i);
                final List<Checksum> perFileChecksum = checksums.valueAt(i);
                final byte[] perFileSignature = signatures.get(fileName);
                checksumsMap.put(fileName, new PerFileChecksum(
                        perFileChecksum.toArray(new Checksum[perFileChecksum.size()]),
                        perFileSignature));
            }
        }

        InstallSource installSource = InstallSource.create(installInitiatingPackageName,
                installOriginatingPackageName, installerPackageName, installerAttributionTag,
                params.packageSource);
        return new PackageInstallerSession(callback, context, pm, sessionProvider,
                silentUpdatePolicy, installerThread, stagingManager, sessionId, userId,
                installerUid, installSource, params, createdMillis, committedMillis, stageDir,
                stageCid, fileArray, checksumsMap, prepared, committed, destroyed, sealed,
                childSessionIdsArray, parentSessionId, isReady, isFailed, isApplied,
                sessionErrorCode, sessionErrorMessage);
    }
}
