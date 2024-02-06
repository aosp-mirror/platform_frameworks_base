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
import static android.content.pm.PackageInstaller.UNARCHIVAL_OK;
import static android.content.pm.PackageInstaller.UNARCHIVAL_STATUS_UNSET;
import static android.content.pm.PackageItemInfo.MAX_SAFE_LABEL_LENGTH;
import static android.content.pm.PackageManager.INSTALL_FAILED_ABORTED;
import static android.content.pm.PackageManager.INSTALL_FAILED_BAD_SIGNATURE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SPLIT;
import static android.content.pm.PackageManager.INSTALL_FAILED_PRE_APPROVAL_NOT_AVAILABLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.os.Process.INVALID_UID;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;
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
import static com.android.server.pm.PackageManagerService.APP_METADATA_FILE_NAME;
import static com.android.server.pm.PackageManagerService.DEFAULT_FILE_ACCESS_MODE;
import static com.android.server.pm.PackageManagerServiceUtils.isInstalledByAdb;
import static com.android.server.pm.PackageManagerShellCommandDataLoader.Metadata;

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
import android.app.PendingIntent;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.EnabledSince;
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
import android.content.pm.PackageInstaller.PreapprovalDetails;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageInstaller.UnarchivalStatus;
import android.content.pm.PackageInstaller.UserActionReason;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.pm.verify.domain.DomainSet;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.icu.util.ULocale;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.incremental.IStorageHealthListener;
import android.os.incremental.IncrementalFileStorages;
import android.os.incremental.IncrementalManager;
import android.os.incremental.PerUidReadTimeouts;
import android.os.incremental.StorageHealthCheckParams;
import android.os.incremental.V4Signature;
import android.os.storage.StorageManager;
import android.provider.DeviceConfig;
import android.provider.Settings.Global;
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
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.messages.nano.SystemMessageProto;
import com.android.internal.os.SomeArgs;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;

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
import java.nio.file.Files;
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
    private static final int MSG_PRE_APPROVAL_REQUEST = 6;

    /** XML constants used for persisting a session */
    static final String TAG_SESSION = "session";
    static final String TAG_CHILD_SESSION = "childSession";
    static final String TAG_SESSION_FILE = "sessionFile";
    static final String TAG_SESSION_CHECKSUM = "sessionChecksum";
    static final String TAG_SESSION_CHECKSUM_SIGNATURE = "sessionChecksumSignature";
    private static final String TAG_GRANTED_RUNTIME_PERMISSION = "granted-runtime-permission";
    private static final String TAG_GRANT_PERMISSION = "grant-permission";
    private static final String TAG_DENY_PERMISSION = "deny-permission";
    private static final String TAG_WHITELISTED_RESTRICTED_PERMISSION =
            "whitelisted-restricted-permission";
    private static final String TAG_AUTO_REVOKE_PERMISSIONS_MODE =
            "auto-revoke-permissions-mode";

    static final String TAG_PRE_VERIFIED_DOMAINS = "preVerifiedDomains";
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_USER_ID = "userId";
    private static final String ATTR_INSTALLER_PACKAGE_NAME = "installerPackageName";
    private static final String ATTR_INSTALLER_PACKAGE_UID = "installerPackageUid";
    private static final String ATTR_UPDATE_OWNER_PACKAGE_NAME = "updateOwnererPackageName";
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
    private static final String ATTR_APPLICATION_ENABLED_SETTING_PERSISTENT =
            "applicationEnabledSettingPersistent";
    private static final String ATTR_DOMAIN = "domain";

    private static final String PROPERTY_NAME_INHERIT_NATIVE = "pi.inherit_native_on_dont_kill";
    private static final int[] EMPTY_CHILD_SESSION_ARRAY = EmptyArray.INT;
    private static final InstallationFile[] EMPTY_INSTALLATION_FILE_ARRAY = {};

    private static final String SYSTEM_DATA_LOADER_PACKAGE = "android";
    private static final String APEX_FILE_EXTENSION = ".apex";

    private static final int INCREMENTAL_STORAGE_BLOCKED_TIMEOUT_MS = 2000;
    private static final int INCREMENTAL_STORAGE_UNHEALTHY_TIMEOUT_MS = 7000;
    private static final int INCREMENTAL_STORAGE_UNHEALTHY_MONITORING_MS = 60000;

    /**
     * If an app being installed targets {@link Build.VERSION_CODES#S API 31} and above, the app
     * can be installed without user action.
     * See {@link PackageInstaller.SessionParams#setRequireUserAction} for other conditions required
     * to be satisfied for a silent install.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    private static final long SILENT_INSTALL_ALLOWED = 265131695L;

    /**
     * The system supports pre-approval and update ownership features from
     * {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE API 34}. The change id is used to make sure
     * the system includes the fix of pre-approval with update ownership case. When checking the
     * change id, if it is disabled, it means the build includes the fix. The more detail is on
     * b/293644536.
     * See {@link PackageInstaller.SessionParams#setRequestUpdateOwnership(boolean)} and
     * {@link #requestUserPreapproval(PreapprovalDetails, IntentSender)} for more details.
     */
    @Disabled
    @ChangeId
    private static final long PRE_APPROVAL_WITH_UPDATE_OWNERSHIP_FIX = 293644536L;

    /**
     * The default value of {@link #mValidatedTargetSdk} is {@link Integer#MAX_VALUE}. If {@link
     * #mValidatedTargetSdk} is compared with {@link Build.VERSION_CODES#S} before getting the
     * target sdk version from a validated apk in {@link #validateApkInstallLocked()}, the compared
     * result will not trigger any user action in
     * {@link #checkUserActionRequirement(PackageInstallerSession, IntentSender)}.
     */
    private static final int INVALID_TARGET_SDK_VERSION = Integer.MAX_VALUE;

    /**
     * Byte size limit for app metadata.
     *
     * Flag type: {@code long}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_APP_METADATA_BYTE_SIZE_LIMIT =
            "app_metadata_byte_size_limit";

    /** Default byte size limit for app metadata */
    private static final long DEFAULT_APP_METADATA_BYTE_SIZE_LIMIT = 32000;

    private static final int APP_METADATA_FILE_ACCESS_MODE = 0640;

    /**
     * Throws IllegalArgumentException if the {@link IntentSender} from an immutable
     * {@link android.app.PendingIntent} when caller has a target SDK of API
     * {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM} or above.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static final long THROW_EXCEPTION_COMMIT_WITH_IMMUTABLE_PENDING_INTENT = 240618202L;

    /**
     * Configurable maximum number of pre-verified domains allowed to be added to the session.
     * Flag type: {@code long}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_PRE_VERIFIED_DOMAINS_COUNT_LIMIT =
            "pre_verified_domains_count_limit";
    /**
     * Configurable maximum string length of each pre-verified domain.
     * Flag type: {@code long}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_PRE_VERIFIED_DOMAIN_LENGTH_LIMIT =
            "pre_verified_domain_length_limit";
    /** Default max number of pre-verified domains */
    private static final long DEFAULT_PRE_VERIFIED_DOMAINS_COUNT_LIMIT = 1000;
    /** Default max string length of each pre-verified domain */
    private static final long DEFAULT_PRE_VERIFIED_DOMAIN_LENGTH_LIMIT = 256;

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

    /** Used for tracking whether user action was required for an install. */
    @Nullable
    private Boolean mUserActionRequired;

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

    private final AtomicBoolean mPreapprovalRequested = new AtomicBoolean(false);
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

    @GuardedBy("mLock")
    private IntentSender mPreapprovalRemoteStatusReceiver;

    @GuardedBy("mLock")
    private PreapprovalDetails mPreapprovalDetails;

    /** Fields derived from commit parsing */
    @GuardedBy("mLock")
    private String mPackageName;
    @GuardedBy("mLock")
    private long mVersionCode;
    @GuardedBy("mLock")
    private SigningDetails mSigningDetails;
    @GuardedBy("mLock")
    private final SparseArray<PackageInstallerSession> mChildSessions = new SparseArray<>();
    @GuardedBy("mLock")
    private int mParentSessionId;

    @GuardedBy("mLock")
    private boolean mHasDeviceAdminReceiver;

    @GuardedBy("mLock")
    private int mUserActionRequirement;

    @GuardedBy("mLock")
    private DomainSet mPreVerifiedDomains;

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
    private final ArraySet<FileEntry> mFiles = new ArraySet<>();

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
    private final ArrayMap<String, PerFileChecksum> mChecksums = new ArrayMap<>();

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
            if (isCommittedAndNotInTerminalState()) {
                verify();
            }
        }

        private boolean isCommittedAndNotInTerminalState() {
            String errorMsg = null;
            if (!isCommitted()) {
                errorMsg = TextUtils.formatSimple("The session %d should be committed", sessionId);
            } else if (isSessionApplied()) {
                errorMsg = TextUtils.formatSimple("The session %d has applied", sessionId);
            } else if (isSessionFailed()) {
                synchronized (PackageInstallerSession.this.mLock) {
                    errorMsg = TextUtils.formatSimple("The session %d has failed with error: %s",
                            sessionId, PackageInstallerSession.this.mSessionErrorMessage);
                }
            }
            if (errorMsg != null) {
                Slog.e(TAG, "verifySession error: " + errorMsg);
                setSessionFailed(INSTALL_FAILED_INTERNAL_ERROR, errorMsg);
                onSessionVerificationFailure(INSTALL_FAILED_INTERNAL_ERROR, errorMsg);
                return false;
            }
            return true;
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
    private final Set<IntentSender> mUnarchivalListeners = new ArraySet<>();

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

    @UnarchivalStatus
    private int mUnarchivalStatus = UNARCHIVAL_STATUS_UNSET;

    private static final FileFilter sAddedApkFilter = new FileFilter() {
        @Override
        public boolean accept(File file) {
            // Installers can't stage directories, so it's fine to ignore
            // entries like "lost+found".
            if (file.isDirectory()) return false;
            if (file.getName().endsWith(REMOVE_MARKER_EXTENSION)) return false;
            if (file.getName().endsWith(V4Signature.EXT)) return false;
            if (isAppMetadata(file)) return false;
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

    static boolean isArchivedInstallation(int installFlags) {
        return (installFlags & PackageManager.INSTALL_ARCHIVED) != 0;
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
                    final boolean isPreapproval = args.argi2 == 1;
                    args.recycle();

                    sendOnPackageInstalled(mContext, statusReceiver, sessionId,
                            isInstallerDeviceOwnerOrAffiliatedProfileOwner(), userId,
                            packageName, returnCode, isPreapproval, message, extras);

                    break;
                case MSG_SESSION_VALIDATION_FAILURE:
                    final int error = msg.arg1;
                    final String detailMessage = (String) msg.obj;
                    onSessionValidationFailure(error, detailMessage);
                    break;
                case MSG_PRE_APPROVAL_REQUEST:
                    handlePreapprovalRequest();
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

    private boolean isArchivedInstallation() {
        return isArchivedInstallation(this.params.installFlags);
    }

    /**
     * @return {@code true} iff the installing is app an device owner or affiliated profile owner.
     */
    private boolean isInstallerDeviceOwnerOrAffiliatedProfileOwner() {
        assertNotLocked("isInstallerDeviceOwnerOrAffiliatedProfileOwner");
        if (userId != UserHandle.getUserId(getInstallerUid())) {
            return false;
        }
        DevicePolicyManagerInternal dpmi =
                LocalServices.getService(DevicePolicyManagerInternal.class);
        // It may wait for a long time to finish {@code dpmi.canSilentlyInstallPackage}.
        // Please don't acquire mLock before calling {@code dpmi.canSilentlyInstallPackage}.
        return dpmi != null && dpmi.canSilentlyInstallPackage(
                getInstallSource().mInstallerPackageName, mInstallerUid);
    }

    private boolean isEmergencyInstallerEnabled(String packageName, Computer snapshot) {
        final PackageStateInternal ps = snapshot.getPackageStateInternal(packageName);
        if (ps == null || ps.getPkg() == null || !ps.isSystem()) {
            return false;
        }
        String emergencyInstaller = ps.getPkg().getEmergencyInstaller();
        if (emergencyInstaller == null || !ArrayUtils.contains(
                snapshot.getPackagesForUid(mInstallerUid),
                emergencyInstaller)) {
            return false;
        }
        return (snapshot.checkUidPermission(Manifest.permission.EMERGENCY_INSTALL_PACKAGES,
                mInstallerUid) == PackageManager.PERMISSION_GRANTED);
    }

    private static final int USER_ACTION_NOT_NEEDED = 0;
    private static final int USER_ACTION_REQUIRED = 1;
    private static final int USER_ACTION_PENDING_APK_PARSING = 2;
    private static final int USER_ACTION_REQUIRED_UPDATE_OWNER_REMINDER = 3;

    @IntDef({
            USER_ACTION_NOT_NEEDED,
            USER_ACTION_REQUIRED,
            USER_ACTION_PENDING_APK_PARSING,
            USER_ACTION_REQUIRED_UPDATE_OWNER_REMINDER,
    })
    @interface UserActionRequirement {}

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
            // For pre-pappvoal case, the mPackageName would be null.
            if (mPackageName != null) {
                packageName = mPackageName;
            } else if (mPreapprovalRequested.get() && mPreapprovalDetails != null) {
                packageName = mPreapprovalDetails.getPackageName();
            } else {
                packageName = null;
            }
            hasDeviceAdminReceiver = mHasDeviceAdminReceiver;
        }

        // For the below cases, force user action prompt
        // 1. installFlags includes INSTALL_FORCE_PERMISSION_PROMPT
        // 2. params.requireUserAction is USER_ACTION_REQUIRED
        final boolean forceUserActionPrompt =
                (params.installFlags & PackageManager.INSTALL_FORCE_PERMISSION_PROMPT) != 0
                        || params.requireUserAction == SessionParams.USER_ACTION_REQUIRED;
        final int userActionNotTypicallyNeededResponse = forceUserActionPrompt
                ? USER_ACTION_REQUIRED
                : USER_ACTION_NOT_NEEDED;

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
                ? snapshot.getInstallSourceInfo(packageName, userId)
                : null;
        final String existingInstallerPackageName = existingInstallSourceInfo != null
                ? existingInstallSourceInfo.getInstallingPackageName()
                : null;
        final String existingUpdateOwnerPackageName = existingInstallSourceInfo != null
                ? existingInstallSourceInfo.getUpdateOwnerPackageName()
                : null;
        final boolean isInstallerOfRecord = isUpdate
                && Objects.equals(existingInstallerPackageName, getInstallerPackageName());
        final boolean isUpdateOwner = TextUtils.equals(existingUpdateOwnerPackageName,
                getInstallerPackageName());
        final boolean isSelfUpdate = targetPackageUid == mInstallerUid;
        final boolean isEmergencyInstall =
                isEmergencyInstallerEnabled(packageName, snapshot);
        final boolean isPermissionGranted = isInstallPermissionGranted
                || (isUpdatePermissionGranted && isUpdate)
                || (isSelfUpdatePermissionGranted && isSelfUpdate)
                || (isInstallDpcPackagesPermissionGranted && hasDeviceAdminReceiver);
        final boolean isInstallerRoot = (mInstallerUid == Process.ROOT_UID);
        final boolean isInstallerSystem = (mInstallerUid == Process.SYSTEM_UID);
        final boolean isInstallerShell = (mInstallerUid == Process.SHELL_UID);
        final boolean isFromManagedUserOrProfile =
                (params.installFlags & PackageManager.INSTALL_FROM_MANAGED_USER_OR_PROFILE) != 0;
        final boolean isUpdateOwnershipEnforcementEnabled =
                mPm.isUpdateOwnershipEnforcementAvailable()
                        && existingUpdateOwnerPackageName != null;

        // Device owners and affiliated profile owners are allowed to silently install packages, so
        // the permission check is waived if the installer is the device owner.
        final boolean noUserActionNecessary = isInstallerRoot || isInstallerSystem
                || isInstallerDeviceOwnerOrAffiliatedProfileOwner() || isEmergencyInstall;

        if (noUserActionNecessary) {
            return userActionNotTypicallyNeededResponse;
        }

        if (isUpdateOwnershipEnforcementEnabled
                && !isApexSession()
                && !isUpdateOwner
                && !isInstallerShell
                // We don't enforce the update ownership for the managed user and profile.
                && !isFromManagedUserOrProfile) {
            return USER_ACTION_REQUIRED_UPDATE_OWNER_REMINDER;
        }

        if (isPermissionGranted) {
            return userActionNotTypicallyNeededResponse;
        }

        if (snapshot.isInstallDisabledForPackage(getInstallerPackageName(), mInstallerUid,
                userId)) {
            // show the installer to account for device policy or unknown sources use cases
            return USER_ACTION_REQUIRED;
        }

        if (params.requireUserAction == SessionParams.USER_ACTION_NOT_REQUIRED
                && isUpdateWithoutUserActionPermissionGranted
                && ((isUpdateOwnershipEnforcementEnabled ? isUpdateOwner
                : isInstallerOfRecord) || isSelfUpdate)) {
            return USER_ACTION_PENDING_APK_PARSING;
        }

        return USER_ACTION_REQUIRED;
    }

    private void updateUserActionRequirement(int requirement) {
        synchronized (mLock) {
            mUserActionRequirement = requirement;
        }
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
            String sessionErrorMessage, DomainSet preVerifiedDomains) {
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
        mOriginalInstallerPackageName = mInstallSource.mInstallerPackageName;
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
        mPreVerifiedDomains = preVerifiedDomains;

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

        if (isArchivedInstallation()) {
            if (params.mode != SessionParams.MODE_FULL_INSTALL) {
                throw new IllegalArgumentException(
                        "Archived installation can only be full install.");
            }
            if (!isStreamingInstallation() || !isSystemDataLoaderInstallation()) {
                throw new IllegalArgumentException(
                        "Archived installation can only use Streaming System DataLoader.");
            }
        }
    }

    PackageInstallerHistoricalSession createHistoricalSession() {
        final float progress;
        final float clientProgress;
        synchronized (mProgressLock) {
            progress = mProgress;
            clientProgress = mClientProgress;
        }
        synchronized (mLock) {
            return new PackageInstallerHistoricalSession(sessionId, userId, mOriginalInstallerUid,
                    mOriginalInstallerPackageName, mInstallSource, mInstallerUid, createdMillis,
                    updatedMillis, committedMillis, stageDir, stageCid, clientProgress, progress,
                    isCommitted(), isPreapprovalRequested(), mSealed, mPermissionsManuallyAccepted,
                    mStageDirInUse, mDestroyed, mFds.size(), mBridges.size(), mFinalStatus,
                    mFinalMessage, params, mParentSessionId, getChildSessionIdsLocked(),
                    mSessionApplied, mSessionFailed, mSessionReady, mSessionErrorCode,
                    mSessionErrorMessage, mPreapprovalDetails, mPreVerifiedDomains);
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
            info.installerPackageName = mInstallSource.mInstallerPackageName;
            info.installerAttributionTag = mInstallSource.mInstallerAttributionTag;
            info.resolvedBaseCodePath = null;
            if (mContext.checkCallingOrSelfPermission(
                    Manifest.permission.READ_INSTALLED_SESSION_PATHS)
                    == PackageManager.PERMISSION_GRANTED) {
                File file = mResolvedBaseFile;
                if (file == null) {
                    // Try to guess mResolvedBaseFile file.
                    final List<File> addedFiles = getAddedApksLocked();
                    if (addedFiles.size() > 0) {
                        file = addedFiles.get(0);
                    }
                }
                if (file != null) {
                    info.resolvedBaseCodePath = file.getAbsolutePath();
                }
            }
            info.progress = progress;
            info.sealed = mSealed;
            info.isCommitted = isCommitted();
            info.isPreapprovalRequested = isPreapprovalRequested();
            info.active = mActiveCount.get() > 0;

            info.mode = params.mode;
            info.installReason = params.installReason;
            info.installScenario = params.installScenario;
            info.sizeBytes = params.sizeBytes;
            info.appPackageName = mPreapprovalDetails != null ? mPreapprovalDetails.getPackageName()
                    : mPackageName != null ? mPackageName : params.appPackageName;
            if (includeIcon) {
                info.appIcon = mPreapprovalDetails != null && mPreapprovalDetails.getIcon() != null
                        ? mPreapprovalDetails.getIcon() : params.appIcon;
            }
            info.appLabel =
                    mPreapprovalDetails != null ? mPreapprovalDetails.getLabel() : params.appLabel;

            info.installLocation = params.installLocation;
            if (!scrubData) {
                info.originatingUri = params.originatingUri;
            }
            info.originatingUid = params.originatingUid;
            if (!scrubData) {
                info.referrerUri = params.referrerUri;
            }
            info.grantedRuntimePermissions = params.getLegacyGrantedRuntimePermissions();
            info.whitelistedRestrictedPermissions = params.whitelistedRestrictedPermissions;
            info.autoRevokePermissionsMode = params.autoRevokePermissionsMode;
            info.installFlags = params.installFlags;
            info.rollbackLifetimeMillis = params.rollbackLifetimeMillis;
            info.rollbackImpactLevel = params.rollbackImpactLevel;
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
            info.applicationEnabledSettingPersistent = params.applicationEnabledSettingPersistent;
            info.pendingUserActionReason = userActionRequirementToReason(mUserActionRequirement);
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

    /** @hide */
    boolean isPreapprovalRequested() {
        return mPreapprovalRequested.get();
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
    private void assertPreparedAndNotPreapprovalRequestedLocked(String cookie) {
        assertPreparedAndNotSealedLocked(cookie);
        if (isPreapprovalRequested()) {
            throw new IllegalStateException(cookie + " not allowed after requesting");
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
        if (isCommitted()) {
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
        if (!isIncrementalInstallation() || !isCommitted()) {
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
            String[] names;
            if (!isCommitted()) {
                names = getNamesLocked();
            } else {
                names = getStageDirContentsLocked();
            }
            return ArrayUtils.removeString(names, APP_METADATA_FILE_NAME);
        }
    }

    @GuardedBy("mLock")
    private String[] getStageDirContentsLocked() {
        if (stageDir == null) {
            return EmptyArray.STRING;
        }
        String[] result = stageDir.list();
        if (result == null) {
            return EmptyArray.STRING;
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
    private void enableFsVerityToAddedApksWithIdsig() throws PackageManagerException {
        try {
            List<File> files = getAddedApksLocked();
            for (var file : files) {
                if (new File(file.getPath() + V4Signature.EXT).exists()) {
                    VerityUtils.setUpFsverity(file.getPath());
                }
            }
        } catch (IOException e) {
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_BAD_SIGNATURE,
                    "Failed to enable fs-verity to verify with idsig: " + e);
        }
    }

    @GuardedBy("mLock")
    private List<ApkLite> getAddedApkLitesLocked() throws PackageManagerException {
        if (!isArchivedInstallation()) {
            List<File> files = getAddedApksLocked();
            final List<ApkLite> result = new ArrayList<>(files.size());

            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            for (int i = 0, size = files.size(); i < size; ++i) {
                final ParseResult<ApkLite> parseResult = ApkLiteParseUtils.parseApkLite(
                        input.reset(), files.get(i),
                        ParsingPackageUtils.PARSE_COLLECT_CERTIFICATES);
                if (parseResult.isError()) {
                    throw new PackageManagerException(parseResult.getErrorCode(),
                            parseResult.getErrorMessage(), parseResult.getException());
                }
                result.add(parseResult.getResult());
            }

            return result;
        }

        InstallationFile[] files = getInstallationFilesLocked();
        final List<ApkLite> result = new ArrayList<>(files.length);

        for (int i = 0, size = files.length; i < size; ++i) {
            File file = new File(stageDir, files[i].getName());
            if (!sAddedApkFilter.accept(file)) {
                continue;
            }

            final Metadata metadata;
            try {
                metadata = Metadata.fromByteArray(files[i].getMetadata());
            } catch (IOException e) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Failed to ", e);
            }
            if (metadata.getMode() != Metadata.ARCHIVED) {
                throw new PackageManagerException(INSTALL_FAILED_VERIFICATION_FAILURE,
                        "File metadata is not for ARCHIVED package: " + file);
            }

            var archPkg = metadata.getArchivedPackage();
            if (archPkg == null) {
                throw new PackageManagerException(INSTALL_FAILED_VERIFICATION_FAILURE,
                        "Metadata does not contain ArchivedPackage: " + file);
            }
            if (archPkg.packageName == null || archPkg.signingDetails == null) {
                throw new PackageManagerException(INSTALL_FAILED_VERIFICATION_FAILURE,
                        "ArchivedPackage does not contain required info: " + file);
            }
            result.add(new ApkLite(file.getAbsolutePath(), archPkg));
        }
        return result;
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

        final String initiatingPackageName = getInstallSource().mInitiatingPackageName;
        final String installerPackageName;
        if (!isInstalledByAdb(initiatingPackageName)) {
            installerPackageName = initiatingPackageName;
        } else {
            installerPackageName = getInstallSource().mInstallerPackageName;
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
                throw new IllegalArgumentException("Can't verify signature: " + e.getMessage(), e);
            }
        }

        for (Checksum checksum : checksums) {
            if (checksum.getValue() == null
                    || checksum.getValue().length > Checksum.MAX_CHECKSUM_SIZE_BYTES) {
                throw new IllegalArgumentException("Invalid checksum.");
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
        final String installerPackageName = PackageManagerServiceUtils.isInstalledByAdb(
                getInstallSource().mInitiatingPackageName)
                ? getInstallSource().mInstallerPackageName
                : getInstallSource().mInitiatingPackageName;
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

    private File getTmpAppMetadataFile() {
        return new File(Environment.getDataAppDirectory(params.volumeUuid),
                sessionId + "-" + APP_METADATA_FILE_NAME);
    }

    private File getStagedAppMetadataFile() {
        File file = new File(stageDir, APP_METADATA_FILE_NAME);
        return file.exists() ? file : null;
    }

    private static boolean isAppMetadata(String name) {
        return name.endsWith(APP_METADATA_FILE_NAME);
    }

    private static boolean isAppMetadata(File file) {
        return isAppMetadata(file.getName());
    }

    @Override
    public ParcelFileDescriptor getAppMetadataFd() {
        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
            assertPreparedAndNotCommittedOrDestroyedLocked("getAppMetadataFd");
            if (getStagedAppMetadataFile() == null) {
                return null;
            }
            try {
                return openReadInternalLocked(APP_METADATA_FILE_NAME);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    @Override
    public void removeAppMetadata() {
        File file = getStagedAppMetadataFile();
        if (file != null) {
            file.delete();
        }
    }

    private static long getAppMetadataSizeLimit() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getLong(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_APP_METADATA_BYTE_SIZE_LIMIT, DEFAULT_APP_METADATA_BYTE_SIZE_LIMIT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public ParcelFileDescriptor openWriteAppMetadata() {
        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
            assertPreparedAndNotSealedLocked("openWriteAppMetadata");
        }
        try {
            return doWriteInternal(APP_METADATA_FILE_NAME, /* offsetBytes= */ 0,
                    /* lengthBytes= */ -1, null);
        } catch (IOException e) {
            throw ExceptionUtils.wrap(e);
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

        final File target = new File(path);
        final File source = new File(stageDir, target.getName());
        var sourcePath = source.getAbsolutePath();
        try {
            try {
                Os.link(path, sourcePath);
                // Grant READ access for APK to be read successfully
                Os.chmod(sourcePath, DEFAULT_FILE_ACCESS_MODE);
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
            if (!SELinux.restorecon(source)) {
                throw new IOException("Can't relabel file: " + source);
            }
        } catch (IOException e) {
            try {
                Os.unlink(sourcePath);
            } catch (Exception ignored) {
                Slog.d(TAG, "Failed to unlink session file: " + sourcePath);
            }

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

            // If file is app metadata then set permission to 0640 to deny user read access since it
            // might contain sensitive information.
            int mode = name.equals(APP_METADATA_FILE_NAME)
                    ? APP_METADATA_FILE_ACCESS_MODE : DEFAULT_FILE_ACCESS_MODE;
            ParcelFileDescriptor targetPfd = openTargetInternal(target.getAbsolutePath(),
                    O_CREAT | O_WRONLY, mode);
            Os.chmod(target.getAbsolutePath(), mode);

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
        assertNotChild("commit");
        boolean throwsExceptionCommitImmutableCheck = CompatChanges.isChangeEnabled(
                THROW_EXCEPTION_COMMIT_WITH_IMMUTABLE_PENDING_INTENT, Binder.getCallingUid());
        if (throwsExceptionCommitImmutableCheck && statusReceiver.isImmutable()) {
            throw new IllegalArgumentException(
                "The commit() status receiver should come from a mutable PendingIntent");
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

        File appMetadataFile = getStagedAppMetadataFile();
        if (appMetadataFile != null) {
            long sizeLimit = getAppMetadataSizeLimit();
            if (appMetadataFile.length() > sizeLimit) {
                appMetadataFile.delete();
                throw new IllegalArgumentException(
                        "App metadata size exceeds the maximum allowed limit of " + sizeLimit);
            }
            if (isIncrementalInstallation()) {
                // Incremental requires stageDir to be empty so move the app metadata file to a
                // temporary location and move back after commit.
                appMetadataFile.renameTo(getTmpAppMetadataFile());
            }
        }

        dispatchSessionSealed();
    }

    @Override
    public void seal() {
        assertNotChild("seal");
        assertCallerIsOwnerOrRoot();
        try {
            sealInternal();
            for (var child : getChildSessions()) {
                child.sealInternal();
            }
        } catch (PackageManagerException e) {
            throw new IllegalStateException("Package is not valid", e);
        }
    }

    private void sealInternal() throws PackageManagerException {
        synchronized (mLock) {
            sealLocked();
        }
    }

    @Override
    public List<String> fetchPackageNames() {
        assertNotChild("fetchPackageNames");
        assertCallerIsOwnerOrRoot();
        var sessions = getSelfOrChildSessions();
        var result = new ArrayList<String>(sessions.size());
        for (var s : sessions) {
            result.add(s.fetchPackageName());
        }
        return result;
    }

    private String fetchPackageName() {
        assertSealed("fetchPackageName");
        synchronized (mLock) {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final List<File> addedFiles = getAddedApksLocked();
            for (File addedFile : addedFiles) {
                final ParseResult<ApkLite> result =
                        ApkLiteParseUtils.parseApkLite(input.reset(), addedFile, 0);
                if (result.isError()) {
                    throw new IllegalStateException(
                            "Can't parse package for session=" + sessionId, result.getException());
                }
                final ApkLite apk = result.getResult();
                var packageName = apk.getPackageName();
                if (packageName != null) {
                    return packageName;
                }
            }
            throw new IllegalStateException("Can't fetch package name for session=" + sessionId);
        }
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
            String msg = ExceptionUtils.getCompleteMessage(e);
            destroy(msg);
            dispatchSessionFinished(e.error, msg, null);
            maybeFinishChildSessions(e.error, msg);
        }
    }

    @WorkerThread
    private void handlePreapprovalRequest() {
        /**
         * Stops the process if the session needs user action. When the user answers the yes,
         * {@link #setPermissionsResult(boolean)} is called and then
         * {@link #MSG_PRE_APPROVAL_REQUEST} is handled to come back here to check again.
         */
        if (sendPendingUserActionIntentIfNeeded(/* forPreapproval= */true)) {
            return;
        }

        dispatchSessionPreapproved();
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

    private boolean isInstallationAllowed(PackageStateInternal psi) {
        if (psi == null || psi.getPkg() == null) {
            return true;
        }
        if (psi.getPkg().isUpdatableSystem()) {
            return true;
        }
        if (mOriginalInstallerUid == Process.ROOT_UID) {
            Slog.w(TAG, "Overriding updatableSystem because the installer is root: "
                    + psi.getPackageName());
            return true;
        }
        return false;
    }

    /**
     * Check if this package can be installed archived.
     */
    private static boolean isArchivedInstallationAllowed(PackageStateInternal psi) {
        if (psi == null) {
            return true;
        }
        return false;
    }

    /**
     * Checks if the package can be installed on IncFs.
     */
    private static boolean isIncrementalInstallationAllowed(PackageStateInternal psi) {
        if (psi == null || psi.getPkg() == null) {
            return true;
        }
        return !psi.isSystem() && !psi.isUpdatedSystemApp();
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
                    Global.getInt(mContext.getContentResolver(), Global.SECURE_FRP_MODE, 0) == 1;

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
                if (isCommitted()) {
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

    @NonNull
    private List<PackageInstallerSession> getSelfOrChildSessions() {
        return isMultiPackage() ? getChildSessions() : Collections.singletonList(this);
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
        destroyInternal("Failed to validate session, error: " + error + ", " + detailMessage);
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
        final String packageName = getPackageName();
        if (TextUtils.isEmpty(packageName)) {
            // The package has not been installed.
            return;
        }
        mHandler.post(() -> {
            if (mPm.deletePackageX(packageName,
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
            mInstallSource = InstallSource.create(packageName, null /* originatingPackageName */,
                    packageName, mInstallerUid, packageName, null /* installerAttributionTag */,
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
        session.updateUserActionRequirement(userActionRequirement);
        if (userActionRequirement == USER_ACTION_REQUIRED
                || userActionRequirement == USER_ACTION_REQUIRED_UPDATE_OWNER_REMINDER) {
            session.sendPendingUserActionIntent(target);
            return true;
        }

        if (!session.isApexSession() && userActionRequirement == USER_ACTION_PENDING_APK_PARSING) {
            if (!isTargetSdkConditionSatisfied(session)) {
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
     * Checks if the app being installed has a targetSdk more than the minimum required for a
     * silent install. See {@link SessionParams#setRequireUserAction(int)} for details about the
     * targetSdk requirement.
     * @param session Current install session
     * @return true if the targetSdk of the app being installed is more than the minimum required,
     *          resulting in a silent install, false otherwise.
     */
    private static boolean isTargetSdkConditionSatisfied(PackageInstallerSession session) {
        final int validatedTargetSdk;
        final String packageName;
        synchronized (session.mLock) {
            validatedTargetSdk = session.mValidatedTargetSdk;
            packageName = session.mPackageName;
        }

        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.targetSdkVersion = validatedTargetSdk;

        IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        try {
            // Using manually constructed AppInfo to check if a change is enabled may not work
            // in the future.
            return validatedTargetSdk != INVALID_TARGET_SDK_VERSION
                    && platformCompat.isChangeEnabled(SILENT_INSTALL_ALLOWED, appInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get a response from PLATFORM_COMPAT_SERVICE", e);
            return false;
        }
    }

    private static @UserActionReason int userActionRequirementToReason(
            @UserActionRequirement int requirement) {
        switch (requirement) {
            case USER_ACTION_REQUIRED_UPDATE_OWNER_REMINDER:
                return PackageInstaller.REASON_REMIND_OWNERSHIP;
            default:
                return PackageInstaller.REASON_CONFIRM_PACKAGE_CHANGE;
        }
    }

    /**
     * Find out any session needs user action.
     *
     * @return true if the session set requires user action for the installation, otherwise false.
     */
    @WorkerThread
    private boolean sendPendingUserActionIntentIfNeeded(boolean forPreapproval) {
        // To support pre-approval request of atomic install, we allow child session to handle
        // the result by itself since it has the status receiver.
        if (isCommitted()) {
            assertNotChild("PackageInstallerSession#sendPendingUserActionIntentIfNeeded");
        }
        // Since there are separate status receivers for session preapproval and commit,
        // check whether user action is requested for session preapproval or commit
        final IntentSender statusReceiver = forPreapproval ? getPreapprovalRemoteStatusReceiver()
                                            : getRemoteStatusReceiver();
        return sessionContains(s -> checkUserActionRequirement(s, statusReceiver));
    }

    @WorkerThread
    private void handleInstall() {
        if (isInstallerDeviceOwnerOrAffiliatedProfileOwner()) {
            DevicePolicyEventLogger
                    .createEvent(DevicePolicyEnums.INSTALL_PACKAGE)
                    .setAdmin(getInstallSource().mInstallerPackageName)
                    .write();
        }

        /**
         * Stops the installation of the whole session set if one session needs user action
         * in its belong session set. When the user answers the yes,
         * {@link #setPermissionsResult(boolean)} is called and then {@link #MSG_INSTALL} is
         * handled to come back here to check again.
         *
         * {@code mUserActionRequired} is used to track when user action is required for an
         * install. Since control may come back here more than 1 time, we must ensure that it's
         * value is not overwritten.
         */
        boolean wasUserActionIntentSent =
                sendPendingUserActionIntentIfNeeded(/* forPreapproval= */false);
        if (mUserActionRequired == null) {
            mUserActionRequired = wasUserActionIntentSent;
        }
        if (wasUserActionIntentSent) {
            // Commit was keeping session marked as active until now; release
            // that extra refcount so session appears idle.
            deactivate();
            return;
        } else if (mUserActionRequired) {
            // If user action is required, control comes back here when the user allows
            // the installation. At this point, the session is marked active once again,
            // since installation is in progress.
            activate();
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

    private IntentSender getPreapprovalRemoteStatusReceiver() {
        synchronized (mLock) {
            return mPreapprovalRemoteStatusReceiver;
        }
    }

    private void setPreapprovalRemoteStatusReceiver(IntentSender remoteStatusReceiver) {
        synchronized (mLock) {
            mPreapprovalRemoteStatusReceiver = remoteStatusReceiver;
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
            if (mPackageName == null) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session no package name");
            }
            if (mSigningDetails == null) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session no signing data");
            }
            if (mResolvedBaseFile == null) {
                throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                        "Session no resolved base file");
            }
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
        // `futures` either contains only one session (`this`) or contains one parent session
        // (`this`) and n-1 child sessions.
        List<CompletableFuture<InstallResult>> futures = installNonStaged();
        CompletableFuture<InstallResult>[] arr = new CompletableFuture[futures.size()];
        return CompletableFuture.allOf(futures.toArray(arr)).whenComplete((r, t) -> {
            if (t == null) {
                setSessionApplied();
                var multiPackageWarnings = new ArrayList<String>();
                if (isMultiPackage()) {
                    // This is a parent session. Collect warnings from children.
                    for (CompletableFuture<InstallResult> f : futures) {
                        InstallResult result = f.join();
                        if (result.session != this && result.extras != null) {
                            ArrayList<String> childWarnings = result.extras.getStringArrayList(
                                    PackageInstaller.EXTRA_WARNINGS);
                            if (!ArrayUtils.isEmpty(childWarnings)) {
                                multiPackageWarnings.addAll(childWarnings);
                            }
                        }
                    }
                }
                for (CompletableFuture<InstallResult> f : futures) {
                    InstallResult result = f.join();
                    Bundle extras = result.extras;
                    if (isMultiPackage() && result.session == this
                            && !multiPackageWarnings.isEmpty()) {
                        if (extras == null) {
                            extras = new Bundle();
                        }
                        extras.putStringArrayList(
                                PackageInstaller.EXTRA_WARNINGS, multiPackageWarnings);
                    }
                    result.session.dispatchSessionFinished(
                            INSTALL_SUCCEEDED, "Session installed", extras);
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
            final InstallingSession installingSession = createInstallingSession(future);
            if (isMultiPackage()) {
                final List<PackageInstallerSession> childSessions = getChildSessions();
                List<InstallingSession> installingChildSessions =
                        new ArrayList<>(childSessions.size());
                for (int i = 0; i < childSessions.size(); ++i) {
                    final PackageInstallerSession session = childSessions.get(i);
                    future = new CompletableFuture<>();
                    futures.add(future);
                    final InstallingSession installingChildSession =
                            session.createInstallingSession(future);
                    if (installingChildSession != null) {
                        installingChildSessions.add(installingChildSession);
                    }
                }
                if (!installingChildSessions.isEmpty()) {
                    Objects.requireNonNull(installingSession).installStage(installingChildSessions);
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
        final boolean isPreapproval = isPreapprovalRequested() && !isCommitted();
        final Intent intent = new Intent(
                isPreapproval ? PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL
                        : PackageInstaller.ACTION_CONFIRM_INSTALL);
        intent.setPackage(mPm.getPackageInstallerPackageName());
        intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        sendOnUserActionRequired(mContext, target, sessionId, intent);
    }

    @WorkerThread
    private void onVerificationComplete() {
        if (isStaged()) {
            mStagingManager.commitSession(mStagedSession);
            sendUpdateToRemoteStatusReceiver(INSTALL_SUCCEEDED, "Session staged",
                    /* extras= */ null, /* forPreapproval= */ false);
            return;
        }
        install();
    }

    /**
     * Stages this session for install and returns a
     * {@link InstallingSession} representing this new staged state.
     *
     * @param future a future that will be completed when this session is completed.
     */
    @Nullable
    private InstallingSession createInstallingSession(CompletableFuture<InstallResult> future)
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
            return new InstallingSession(sessionId, stageDir, localObserver, params, mInstallSource,
                    user, mSigningDetails, mInstallerUid, mPackageLite, mPreVerifiedDomains, mPm);
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
            packageUid = INVALID_UID;
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

        // Default to require only if existing base apk has fs-verity signature.
        mVerityFoundForApks = PackageManagerServiceUtils.isApkVerityEnabled()
                && params.mode == SessionParams.MODE_INHERIT_EXISTING
                && VerityUtils.hasFsverity(pkgInfo.applicationInfo.getBaseCodePath())
                && (new File(VerityUtils.getFsveritySignatureFilePath(
                        pkgInfo.applicationInfo.getBaseCodePath()))).exists();

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

        // Needs to happen before the first v4 signature verification, which happens in
        // getAddedApkLitesLocked.
        if (android.security.Flags.extendVbChainToUpdatedApk()) {
            if (!isIncrementalInstallation()) {
                enableFsVerityToAddedApksWithIdsig();
            }
        }

        final List<ApkLite> addedFiles = getAddedApkLitesLocked();
        if (addedFiles.isEmpty()
                && (removeSplitList.size() == 0 || getStagedAppMetadataFile() != null)) {
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
        for (ApkLite apk : addedFiles) {
            if (!stagedSplits.add(apk.getSplitName())) {
                throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                        "Split " + apk.getSplitName() + " was defined multiple times");
            }

            if (!apk.isUpdatableSystem()) {
                if (mOriginalInstallerUid == Process.ROOT_UID) {
                    Slog.w(TAG, "Overriding updatableSystem because the installer is root for: "
                            + apk.getPackageName());
                } else {
                    throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                            "Non updatable system package can't be installed or updated");
                }
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

            assertApkConsistentLocked(String.valueOf(apk), apk);

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
            if (!isArchivedInstallation()) {
                final File sourceFile = new File(apk.getPath());
                resolveAndStageFileLocked(sourceFile, targetFile, apk.getSplitName());
            }

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

        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final PackageStateInternal existingPkgSetting = pmi.getPackageStateInternal(mPackageName);

        if (!isInstallationAllowed(existingPkgSetting)) {
            throw new PackageManagerException(
                    PackageManager.INSTALL_FAILED_SESSION_INVALID,
                    "Installation of this package is not allowed.");
        }

        if (isArchivedInstallation()) {
            if (!isArchivedInstallationAllowed(existingPkgSetting)) {
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_SESSION_INVALID,
                        "Archived installation of this package is not allowed.");
            }

            if (!mPm.mInstallerService.mPackageArchiver.verifySupportsUnarchival(
                    getInstallSource().mInstallerPackageName, userId)) {
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_SESSION_INVALID,
                        "Installer has to support unarchival in order to install archived "
                                + "packages.");
            }
        }

        if (isIncrementalInstallation()) {
            if (!isIncrementalInstallationAllowed(existingPkgSetting)) {
                throw new PackageManagerException(
                        PackageManager.INSTALL_FAILED_SESSION_INVALID,
                        "Incremental installation of this package is not allowed.");
            }
            // Since we moved the staged app metadata file so that incfs can be initialized, lets
            // now move it back.
            File appMetadataFile = getTmpAppMetadataFile();
            if (appMetadataFile.exists()) {
                final IncrementalFileStorages incrementalFileStorages =
                        getIncrementalFileStorages();
                try {
                    incrementalFileStorages.makeFile(APP_METADATA_FILE_NAME,
                            Files.readAllBytes(appMetadataFile.toPath()),
                            APP_METADATA_FILE_ACCESS_MODE);
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to write app metadata to incremental storage", e);
                } finally {
                    appMetadataFile.delete();
                }
            }
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

            boolean existingSplitReplacedOrRemoved = false;
            // Inherit splits if not overridden.
            if (!ArrayUtils.isEmpty(existing.getSplitNames())) {
                for (int i = 0; i < existing.getSplitNames().length; i++) {
                    final String splitName = existing.getSplitNames()[i];
                    final File splitFile = new File(existing.getSplitApkPaths()[i]);
                    final boolean splitRemoved = removeSplitList.contains(splitName);
                    final boolean splitReplaced = stagedSplits.contains(splitName);
                    if (!splitReplaced && !splitRemoved) {
                        inheritFileLocked(splitFile);
                        // Collect the requiredSplitTypes and staged splitTypes from splits
                        CollectionUtils.addAll(requiredSplitTypes,
                                existing.getRequiredSplitTypes()[i]);
                        CollectionUtils.addAll(stagedSplitTypes, existing.getSplitTypes()[i]);
                    } else {
                        existingSplitReplacedOrRemoved = true;
                    }
                }
            }
            if (existingSplitReplacedOrRemoved
                    && (params.installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
                // Some splits are being replaced or removed. Make sure the app is restarted.
                params.setDontKillApp(false);
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

        assertPreapprovalDetailsConsistentIfNeededLocked(packageLite, pkgInfo);

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
        if (android.security.Flags.deprecateFsvSig()) {
            return;
        }
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
    private void maybeStageV4SignatureLocked(File origFile, File targetFile)
            throws PackageManagerException {
        final File originalSignature = new File(origFile.getPath() + V4Signature.EXT);
        if (originalSignature.exists()) {
            final File stagedSignature = new File(targetFile.getPath() + V4Signature.EXT);
            stageFileLocked(originalSignature, stagedSignature);
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
            incrementalFileStorages.makeFile(localPath, bytes, 0777);
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
        // Stage APK's v4 signature if present, and fs-verity is supported.
        if (android.security.Flags.extendVbChainToUpdatedApk()
                && VerityUtils.isFsVeritySupported()) {
            maybeStageV4SignatureLocked(origFile, targetFile);
        }
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
    private void maybeInheritV4SignatureLocked(File origFile) {
        // Inherit the v4 signature file if present.
        final File v4SignatureFile = new File(origFile.getPath() + V4Signature.EXT);
        if (v4SignatureFile.exists()) {
            mResolvedInheritedFiles.add(v4SignatureFile);
        }
    }

    @GuardedBy("mLock")
    private void inheritFileLocked(File origFile) {
        mResolvedInheritedFiles.add(origFile);

        maybeInheritFsveritySignatureLocked(origFile);
        if (android.security.Flags.extendVbChainToUpdatedApk()) {
            maybeInheritV4SignatureLocked(origFile);
        }

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

    @GuardedBy("mLock")
    private void assertPreapprovalDetailsConsistentIfNeededLocked(@NonNull PackageLite packageLite,
            @Nullable PackageInfo info) throws PackageManagerException {
        if (mPreapprovalDetails == null || !isPreapprovalRequested()) {
            return;
        }

        if (!TextUtils.equals(mPackageName, mPreapprovalDetails.getPackageName())) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                    mPreapprovalDetails + " inconsistent with " + mPackageName);
        }

        final PackageManager packageManager = mContext.getPackageManager();
        // The given info isn't null only when params.appPackageName is set.
        final PackageInfo existingPackageInfo =
                info != null ? info : mPm.snapshotComputer().getPackageInfo(mPackageName,
                        0 /* flags */, userId);
        // If the app label in PreapprovalDetails matches the existing one, we treat it as valid.
        final CharSequence appLabel = mPreapprovalDetails.getLabel();
        if (existingPackageInfo != null) {
            final ApplicationInfo existingAppInfo = existingPackageInfo.applicationInfo;
            final CharSequence existingAppLabel = packageManager.getApplicationLabel(
                    existingAppInfo);
            if (TextUtils.equals(appLabel, existingAppLabel)) {
                return;
            }
        }

        final PackageInfo packageInfoFromApk = packageManager.getPackageArchiveInfo(
                        packageLite.getPath(), PackageInfoFlags.of(0));
        if (packageInfoFromApk == null) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Failure to obtain package info from APK files.");
        }

        // In case the app label in PreapprovalDetails from different locale in split APK,
        // we check all APK files to find the app label.
        final List<String> filePaths = packageLite.getAllApkPaths();
        final ULocale appLocale = mPreapprovalDetails.getLocale();
        final ApplicationInfo appInfo = packageInfoFromApk.applicationInfo;
        boolean appLabelMatched = false;
        for (int i = filePaths.size() - 1; i >= 0 && !appLabelMatched; i--) {
            appLabelMatched |= TextUtils.equals(getAppLabel(filePaths.get(i), appLocale, appInfo),
                    appLabel);
        }
        if (!appLabelMatched) {
            throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                    mPreapprovalDetails + " inconsistent with app label");
        }
    }

    private CharSequence getAppLabel(String path, ULocale locale, ApplicationInfo appInfo)
            throws PackageManagerException {
        final Resources pRes = mContext.getResources();
        final AssetManager assetManager = new AssetManager();
        final Configuration config = new Configuration(pRes.getConfiguration());
        final ApkAssets apkAssets;
        try {
            apkAssets = ApkAssets.loadFromPath(path);
        } catch (IOException e) {
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Failure to get resources from package archive " + path);
        }
        assetManager.setApkAssets(new ApkAssets[]{apkAssets}, false /* invalidateCaches */);
        config.setLocale(locale.toLocale());
        final Resources res = new Resources(assetManager, pRes.getDisplayMetrics(), config);
        return TextUtils.trimToSize(tryLoadingAppLabel(res, appInfo), MAX_SAFE_LABEL_LENGTH);
    }

    private CharSequence tryLoadingAppLabel(@NonNull Resources res, @NonNull ApplicationInfo info) {
        CharSequence label = null;
        // Try to load the label from the package's resources. If an app has not explicitly
        // specified any label, just use the package name.
        if (info.labelRes != 0) {
            try {
                label = res.getText(info.labelRes).toString().trim();
            } catch (Resources.NotFoundException ignore) {
            }
        }
        if (label == null) {
            label = (info.nonLocalizedLabel != null)
                    ? info.nonLocalizedLabel : info.packageName;
        }

        return label;
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
        return getInstallSource().mInstallerPackageName;
    }

    String getInstallerAttributionTag() {
        return getInstallSource().mInstallerAttributionTag;
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

    /**
     * @return a boolean value indicating whether user action was requested for the install.
     * Returns {@code false} if {@code mUserActionRequired} is {@code null}
     */
    public boolean getUserActionRequired() {
        if (mUserActionRequired != null) {
            return mUserActionRequired.booleanValue();
        }
        Slog.wtf(TAG, "mUserActionRequired should not be null.");
        return false;
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
                Os.chmod(tmpFile.getAbsolutePath(), DEFAULT_FILE_ACCESS_MODE);
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

        // Skip native libraries processing for archival installation.
        if (isArchivedInstallation()) {
            return;
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
        if (!isSealed() && !isPreapprovalRequested()) {
            throw new SecurityException("Must be sealed to accept permissions");
        }

        // To support pre-approval request of atomic install, we allow child session to handle
        // the result by itself since it has the status receiver.
        final PackageInstallerSession root = hasParentSessionId() && isCommitted()
                ? mSessionProvider.getSession(getParentSessionId()) : this;

        if (accepted) {
            // Mark and kick off another install pass
            synchronized (mLock) {
                mPermissionsManuallyAccepted = true;
            }
            root.mHandler.obtainMessage(
                    isCommitted() ? MSG_INSTALL : MSG_PRE_APPROVAL_REQUEST).sendToTarget();
        } else {
            root.destroy("User rejected permissions");
            root.dispatchSessionFinished(INSTALL_FAILED_ABORTED, "User rejected permissions", null);
            root.maybeFinishChildSessions(INSTALL_FAILED_ABORTED, "User rejected permissions");
        }
    }

    public void open() throws IOException {
        activate();
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

    private void activate() {
        if (mActiveCount.getAndIncrement() == 0) {
            mCallback.onSessionActiveChanged(this, true);
        }
    }

    @Override
    public void close() {
        closeInternal(true);
    }

    private void closeInternal(boolean checkCaller) {
        synchronized (mLock) {
            if (checkCaller) {
                assertCallerIsOwnerOrRoot();
            }
        }
        deactivate();
    }

    private void deactivate() {
        int activeCount;
        synchronized (mLock) {
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
            if (!mStageDirInUse) {
                return false;
            }
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
                if (isStaged() && isCommitted()) {
                    mStagingManager.abortCommittedSession(mStagedSession);
                }
                destroy("Session was abandoned");
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

        final long token = Binder.clearCallingIdentity();
        try {
            // This will call into StagingManager which might trigger external callbacks
            r.run();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
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

    @android.annotation.EnforcePermission(android.Manifest.permission.USE_INSTALLER_V2)
    @Override
    public DataLoaderParamsParcel getDataLoaderParams() {
        getDataLoaderParams_enforcePermission();
        return params.dataLoaderParams != null ? params.dataLoaderParams.getData() : null;
    }

    @android.annotation.EnforcePermission(android.Manifest.permission.USE_INSTALLER_V2)
    @Override
    public void addFile(int location, String name, long lengthBytes, byte[] metadata,
            byte[] signature) {
        addFile_enforcePermission();
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

    @android.annotation.EnforcePermission(android.Manifest.permission.USE_INSTALLER_V2)
    @Override
    public void removeFile(int location, String name) {
        removeFile_enforcePermission();
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
                    && !isCommitted()
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
        // Session can be marked as finished due to user rejecting pre approval or commit request,
        // any internal error or after successful completion. As such, check whether
        // the session is in the preapproval stage or the commit stage.
        sendUpdateToRemoteStatusReceiver(returnCode, msg, extras,
                /* forPreapproval= */ isPreapprovalRequested() && !isCommitted());

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

    private void sendUpdateToRemoteStatusReceiver(int returnCode, String msg, Bundle extras,
            boolean forPreapproval) {
        final IntentSender statusReceiver = forPreapproval ? getPreapprovalRemoteStatusReceiver()
                                            : getRemoteStatusReceiver();
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
            args.argi2 = isPreapprovalRequested() && !isCommitted() ? 1 : 0;
            mHandler.obtainMessage(MSG_ON_PACKAGE_INSTALLED, args).sendToTarget();
        }
    }

    private void dispatchSessionPreapproved() {
        final IntentSender target = getPreapprovalRemoteStatusReceiver();
        final Intent intent = new Intent();
        intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS);
        intent.putExtra(PackageInstaller.EXTRA_PRE_APPROVAL, true);
        try {
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            target.sendIntent(mContext, 0 /* code */, intent, null /* onFinished */,
                    null /* handler */, null /* requiredPermission */, options.toBundle());
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    @Override
    public void requestUserPreapproval(@NonNull PreapprovalDetails details,
            @NonNull IntentSender statusReceiver) {
        validatePreapprovalRequest(details, statusReceiver);

        if (!mPm.isPreapprovalRequestAvailable()) {
            sendUpdateToRemoteStatusReceiver(INSTALL_FAILED_PRE_APPROVAL_NOT_AVAILABLE,
                    "Request user pre-approval is currently not available.", /* extras= */null,
                    /* preapproval= */true);
            return;
        }

        dispatchPreapprovalRequest();
    }

    /**
     * Validates whether the necessary information (e.g., PreapprovalDetails) are provided.
     */
    private void validatePreapprovalRequest(@NonNull PreapprovalDetails details,
            @NonNull IntentSender statusReceiver) {
        assertCallerIsOwnerOrRoot();
        if (isMultiPackage()) {
            throw new IllegalStateException(
                    "Session " + sessionId + " is a parent of multi-package session and "
                            + "requestUserPreapproval on the parent session isn't supported.");
        }

        synchronized (mLock) {
            assertPreparedAndNotSealedLocked("request of session " + sessionId);
            mPreapprovalDetails = details;
            setPreapprovalRemoteStatusReceiver(statusReceiver);
        }
    }

    private void dispatchPreapprovalRequest() {
        synchronized (mLock) {
            assertPreparedAndNotPreapprovalRequestedLocked("dispatchPreapprovalRequest");
        }

        // Mark this session are pre-approval requested, and ready to progress to the next phase.
        markAsPreapprovalRequested();

        mHandler.obtainMessage(MSG_PRE_APPROVAL_REQUEST).sendToTarget();
    }

    /**
     * Marks this session as pre-approval requested, and prevents further related modification.
     */
    private void markAsPreapprovalRequested() {
        mPreapprovalRequested.set(true);
    }

    @Override
    public boolean isApplicationEnabledSettingPersistent() {
        return params.applicationEnabledSettingPersistent;
    }

    @Override
    public boolean isRequestUpdateOwnership() {
        return (params.installFlags & PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP) != 0;
    }

    @Override
    public void setPreVerifiedDomains(@NonNull DomainSet preVerifiedDomains) {
        // First check permissions
        final boolean exemptFromPermissionChecks =
                (mInstallerUid == Process.ROOT_UID) || (mInstallerUid == Process.SHELL_UID);
        if (!exemptFromPermissionChecks) {
            final Computer snapshot = mPm.snapshotComputer();
            if (PackageManager.PERMISSION_GRANTED != snapshot.checkUidPermission(
                    Manifest.permission.ACCESS_INSTANT_APPS, mInstallerUid)) {
                throw new SecurityException("You need android.permission.ACCESS_INSTANT_APPS "
                        + "permission to set pre-verified domains.");
            }
            ComponentName instantAppInstallerComponent = snapshot.getInstantAppInstallerComponent();
            if (instantAppInstallerComponent == null) {
                // Shouldn't happen
                throw new IllegalStateException("Instant app installer is not available. "
                        + "Only the instant app installer can call this API.");
            }
            if (!instantAppInstallerComponent.getPackageName().equals(getInstallerPackageName())) {
                throw new SecurityException("Only the instant app installer can call this API.");
            }
        }
        // Then check size limits
        final long preVerifiedDomainsCountLimit = getPreVerifiedDomainsCountLimit();
        if (preVerifiedDomains.getDomains().size() > preVerifiedDomainsCountLimit) {
            throw new IllegalArgumentException(
                    "The number of pre-verified domains have exceeded the maximum of "
                            + preVerifiedDomainsCountLimit);
        }
        final long preVerifiedDomainLengthLimit = getPreVerifiedDomainLengthLimit();
        for (String domain : preVerifiedDomains.getDomains()) {
            if (domain.length() > preVerifiedDomainLengthLimit) {
                throw new IllegalArgumentException(
                        "Pre-verified domain: [" + domain + " ] exceeds maximum length allowed: "
                                + preVerifiedDomainLengthLimit);
            }
        }
        // Okay to proceed
        synchronized (mLock) {
            mPreVerifiedDomains = preVerifiedDomains;
        }
    }

    private static long getPreVerifiedDomainsCountLimit() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getLong(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_PRE_VERIFIED_DOMAINS_COUNT_LIMIT,
                    DEFAULT_PRE_VERIFIED_DOMAINS_COUNT_LIMIT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static long getPreVerifiedDomainLengthLimit() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getLong(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_PRE_VERIFIED_DOMAIN_LENGTH_LIMIT,
                    DEFAULT_PRE_VERIFIED_DOMAIN_LENGTH_LIMIT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    @Nullable
    public DomainSet getPreVerifiedDomains() {
        assertCallerIsOwnerOrRoot();
        synchronized (mLock) {
            assertPreparedAndNotCommittedOrDestroyedLocked("getPreVerifiedDomains");
            return mPreVerifiedDomains;
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
        destroy("Session marked as failed: " + errorMessage);
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
        destroy(null);
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

    void registerUnarchivalListener(IntentSender intentSender) {
        synchronized (mLock) {
            this.mUnarchivalListeners.add(intentSender);
        }
    }

    Set<IntentSender> getUnarchivalListeners() {
        synchronized (mLock) {
            return new ArraySet<>(mUnarchivalListeners);
        }
    }

    void reportUnarchivalStatus(@UnarchivalStatus int status, int unarchiveId,
            long requiredStorageBytes, PendingIntent userActionIntent) {
        if (getUnarchivalStatus() != UNARCHIVAL_STATUS_UNSET) {
            throw new IllegalStateException(
                    TextUtils.formatSimple(
                            "Unarchival status for ID %s has already been set or a session has "
                                    + "been created for it already by the caller.",
                            unarchiveId));
        }
        mUnarchivalStatus = status;

        // Execute expensive calls outside the sync block.
        mPm.mHandler.post(
                () -> mPm.mInstallerService.mPackageArchiver.notifyUnarchivalListener(status,
                        getInstallerPackageName(), params.appPackageName, requiredStorageBytes,
                        userActionIntent, getUnarchivalListeners(), userId));
        if (status != UNARCHIVAL_OK) {
            Binder.withCleanCallingIdentity(this::abandon);
        }
    }

    @UnarchivalStatus
    int getUnarchivalStatus() {
        return this.mUnarchivalStatus;
    }

    /**
     * Free up storage used by this session and its children.
     * Must not be called on a child session.
     */
    private void destroy(String reason) {
        // TODO(b/173194203): destroy() is called indirectly by
        //  PackageInstallerService#restoreAndApplyStagedSessionIfNeeded on an orphan child session.
        //  Enable this assertion when we figure out a better way to clean up orphan sessions.
        // assertNotChild("destroy");

        // TODO(b/173194203): destroyInternal() should be used by destroy() only.
        //  For the sake of consistency, a session should be destroyed as a whole. The caller
        //  should always call destroy() for cleanup without knowing it has child sessions or not.
        destroyInternal(reason);
        for (PackageInstallerSession child : getChildSessions()) {
            child.destroyInternal(reason);
        }
    }

    /**
     * Free up storage used by this session.
     */
    private void destroyInternal(String reason) {
        if (reason != null) {
            Slog.i(TAG,
                    "Session [" + this.sessionId + "] was destroyed because of [" + reason + "]");
        }
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
        pw.printPair("installerPackageName", mInstallSource.mInstallerPackageName);
        pw.printPair("installInitiatingPackageName", mInstallSource.mInitiatingPackageName);
        pw.printPair("installOriginatingPackageName", mInstallSource.mOriginatingPackageName);
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
        pw.printPair("mPreapprovalRequested", mPreapprovalRequested);
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
        pw.printPair("mPreapprovalDetails", mPreapprovalDetails);
        if (mPreVerifiedDomains != null) {
            pw.printPair("mPreVerifiedDomains", mPreVerifiedDomains);
        }
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
        fillIn.putExtra(PackageInstaller.EXTRA_PRE_APPROVAL,
                PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(intent.getAction()));
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
            boolean isPreapproval, String msg, Bundle extras) {
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
        fillIn.putExtra(PackageInstaller.EXTRA_PRE_APPROVAL, isPreapproval);
        if (extras != null) {
            final String existing = extras.getString(
                    PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE);
            if (!TextUtils.isEmpty(existing)) {
                fillIn.putExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME, existing);
            }
            ArrayList<String> warnings = extras.getStringArrayList(PackageInstaller.EXTRA_WARNINGS);
            if (!ArrayUtils.isEmpty(warnings)) {
                fillIn.putStringArrayListExtra(PackageInstaller.EXTRA_WARNINGS, warnings);
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

    private static void writePermissionsLocked(@NonNull TypedXmlSerializer out,
            @NonNull SessionParams params) throws IOException {
        var permissionStates = params.getPermissionStates();
        for (int index = 0; index < permissionStates.size(); index++) {
            var permissionName = permissionStates.keyAt(index);
            var state = permissionStates.valueAt(index);
            String tag = state == SessionParams.PERMISSION_STATE_GRANTED ? TAG_GRANT_PERMISSION
                    : TAG_DENY_PERMISSION;
            out.startTag(null, tag);
            writeStringAttribute(out, ATTR_NAME, permissionName);
            out.endTag(null, tag);
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
                    mInstallSource.mInstallerPackageName);
            out.attributeInt(null, ATTR_INSTALLER_PACKAGE_UID, mInstallSource.mInstallerPackageUid);
            writeStringAttribute(out, ATTR_UPDATE_OWNER_PACKAGE_NAME,
                    mInstallSource.mUpdateOwnerPackageName);
            writeStringAttribute(out, ATTR_INSTALLER_ATTRIBUTION_TAG,
                    mInstallSource.mInstallerAttributionTag);
            out.attributeInt(null, ATTR_INSTALLER_UID, mInstallerUid);
            writeStringAttribute(out, ATTR_INITIATING_PACKAGE_NAME,
                    mInstallSource.mInitiatingPackageName);
            writeStringAttribute(out, ATTR_ORIGINATING_PACKAGE_NAME,
                    mInstallSource.mOriginatingPackageName);
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
            writeBooleanAttribute(out, ATTR_COMMITTED, isCommitted());
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
            writeBooleanAttribute(out, ATTR_APPLICATION_ENABLED_SETTING_PERSISTENT,
                    params.applicationEnabledSettingPersistent);

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

            writePermissionsLocked(out, params);
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
            if (mPreVerifiedDomains != null) {
                for (String domain : mPreVerifiedDomains.getDomains()) {
                    out.startTag(null, TAG_PRE_VERIFIED_DOMAINS);
                    writeStringAttribute(out, ATTR_DOMAIN, domain);
                    out.endTag(null, TAG_PRE_VERIFIED_DOMAINS);
                }
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
        final int installPackageUid = in.getAttributeInt(null, ATTR_INSTALLER_PACKAGE_UID,
                INVALID_UID);
        final String updateOwnerPackageName = readStringAttribute(in,
                ATTR_UPDATE_OWNER_PACKAGE_NAME);
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
        params.applicationEnabledSettingPersistent = in.getAttributeBoolean(null,
                ATTR_APPLICATION_ENABLED_SETTING_PERSISTENT, false);

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
        List<String> legacyGrantedRuntimePermissions = new ArrayList<>();
        ArraySet<String> grantPermissions = new ArraySet<>();
        ArraySet<String> denyPermissions = new ArraySet<>();
        List<String> whitelistedRestrictedPermissions = new ArrayList<>();
        int autoRevokePermissionsMode = MODE_DEFAULT;
        IntArray childSessionIds = new IntArray();
        List<InstallationFile> files = new ArrayList<>();
        ArrayMap<String, List<Checksum>> checksums = new ArrayMap<>();
        ArrayMap<String, byte[]> signatures = new ArrayMap<>();
        ArraySet<String> preVerifiedDomainSet = new ArraySet<>();
        int outerDepth = in.getDepth();
        int type;
        while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || in.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            switch (in.getName()) {
                case TAG_GRANTED_RUNTIME_PERMISSION:
                    legacyGrantedRuntimePermissions.add(readStringAttribute(in, ATTR_NAME));
                    break;
                case TAG_GRANT_PERMISSION:
                    grantPermissions.add(readStringAttribute(in, ATTR_NAME));
                    break;
                case TAG_DENY_PERMISSION:
                    denyPermissions.add(readStringAttribute(in, ATTR_NAME));
                    break;
                case TAG_WHITELISTED_RESTRICTED_PERMISSION:
                    whitelistedRestrictedPermissions.add(readStringAttribute(in, ATTR_NAME));
                    break;
                case TAG_AUTO_REVOKE_PERMISSIONS_MODE:
                    autoRevokePermissionsMode = in.getAttributeInt(null, ATTR_MODE);
                    break;
                case TAG_CHILD_SESSION:
                    childSessionIds.add(in.getAttributeInt(null, ATTR_SESSION_ID,
                            SessionInfo.INVALID_ID));
                    break;
                case TAG_SESSION_FILE:
                    files.add(new InstallationFile(
                            in.getAttributeInt(null, ATTR_LOCATION, 0),
                            readStringAttribute(in, ATTR_NAME),
                            in.getAttributeLong(null, ATTR_LENGTH_BYTES, -1),
                            readByteArrayAttribute(in, ATTR_METADATA),
                            readByteArrayAttribute(in, ATTR_SIGNATURE)));
                    break;
                case TAG_SESSION_CHECKSUM:
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
                    break;
                case TAG_SESSION_CHECKSUM_SIGNATURE:
                    final String fileName1 = readStringAttribute(in, ATTR_NAME);
                    final byte[] signature = readByteArrayAttribute(in, ATTR_SIGNATURE);
                    signatures.put(fileName1, signature);
                    break;
                case TAG_PRE_VERIFIED_DOMAINS:
                    preVerifiedDomainSet.add(readStringAttribute(in, ATTR_DOMAIN));
                    break;
            }
        }

        if (legacyGrantedRuntimePermissions.size() > 0) {
            params.setPermissionStates(legacyGrantedRuntimePermissions, Collections.emptyList());
        } else {
            params.setPermissionStates(grantPermissions, denyPermissions);
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

        DomainSet preVerifiedDomains =
                preVerifiedDomainSet.isEmpty() ? null : new DomainSet(preVerifiedDomainSet);

        InstallSource installSource = InstallSource.create(installInitiatingPackageName,
                installOriginatingPackageName, installerPackageName, installPackageUid,
                updateOwnerPackageName, installerAttributionTag, params.packageSource);
        return new PackageInstallerSession(callback, context, pm, sessionProvider,
                silentUpdatePolicy, installerThread, stagingManager, sessionId, userId,
                installerUid, installSource, params, createdMillis, committedMillis, stageDir,
                stageCid, fileArray, checksumsMap, prepared, committed, destroyed, sealed,
                childSessionIdsArray, parentSessionId, isReady, isFailed, isApplied,
                sessionErrorCode, sessionErrorMessage, preVerifiedDomains);
    }
}
