/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.Manifest.permission.MANAGE_DEVICE_ADMINS;
import static android.Manifest.permission.SET_HARMFUL_APP_WARNINGS;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_FACTORY_ONLY;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET;
import static android.os.Process.INVALID_UID;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;
import static android.util.FeatureFlagUtils.SETTINGS_TREAT_PAUSE_AS_QUARANTINE;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_INIT_TIME;
import static com.android.server.pm.DexOptHelper.useArtService;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSet;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.isInstalledByAdb;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.ApplicationPackageManager;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.app.admin.IDevicePolicyManager;
import android.app.admin.SecurityLog;
import android.app.backup.IBackupManager;
import android.app.role.RoleManager;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.Checksum;
import android.content.pm.ComponentInfo;
import android.content.pm.DataLoaderType;
import android.content.pm.FallbackCategoryProvider;
import android.content.pm.FeatureInfo;
import android.content.pm.Flags;
import android.content.pm.IDexModuleRegisterCallback;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageLoadingProgressCallback;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstantAppInfo;
import android.content.pm.InstantAppRequest;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ComponentEnabledSetting;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackagePartitions;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.TestUtilityService;
import android.content.pm.UserInfo;
import android.content.pm.UserPackage;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VerifierInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.parsing.PackageLite;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.ReconcileSdkDataArgs;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
import android.os.incremental.PerUidReadTimeouts;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeRecord;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.InstallLocationUtils;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.pm.parsing.PackageParser2;
import com.android.internal.pm.parsing.pkg.AndroidPackageInternal;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.internal.pm.pkg.component.ParsedMainComponent;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.telephony.CarrierAppUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.permission.persistence.RuntimePermissionsState;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.PackageWatchdog;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.ThreadPriorityBooster;
import com.android.server.Watchdog;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.art.DexUseManagerLocal;
import com.android.server.art.model.DeleteResult;
import com.android.server.compat.CompatChange;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.Installer.LegacyDexoptDisabledException;
import com.android.server.pm.Settings.VersionInfo;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.ArtUtils;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DynamicCodeLogger;
import com.android.server.pm.local.PackageManagerLocalImpl;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.permission.LegacyPermissionManagerService;
import com.android.server.pm.permission.LegacyPermissionSettings;
import com.android.server.pm.permission.PermissionManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserState;
import com.android.server.pm.pkg.PackageUserStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.mutate.PackageStateMutator;
import com.android.server.pm.pkg.mutate.PackageStateWrite;
import com.android.server.pm.pkg.mutate.PackageUserStateWrite;
import com.android.server.pm.resolution.ComponentResolver;
import com.android.server.pm.resolution.ComponentResolverApi;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;
import com.android.server.sdksandbox.SdkSandboxManagerLocal;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.utils.Watchable;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedSparseBooleanArray;
import com.android.server.utils.WatchedSparseIntArray;
import com.android.server.utils.Watcher;

import dalvik.system.VMRuntime;

import libcore.util.EmptyArray;
import libcore.util.HexEncoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Keep track of all those APKs everywhere.
 * <p>
 * Internally there are three important locks:
 * <ul>
 * <li>{@link #mLock} is used to guard all in-memory parsed package details
 * and other related state. It is a fine-grained lock that should only be held
 * momentarily, as it's one of the most contended locks in the system.
 * <li>{@link #mInstallLock} is used to guard all {@code installd} access, whose
 * operations typically involve heavy lifting of application data on disk. Since
 * {@code installd} is single-threaded, and it's operations can often be slow,
 * this lock should never be acquired while already holding {@link #mLock}.
 * Conversely, it's safe to acquire {@link #mLock} momentarily while already
 * holding {@link #mInstallLock}.
 * <li>{@link #mSnapshotLock} is used to guard access to two snapshot fields: the snapshot
 * itself and the snapshot invalidation flag.  This lock should never be acquired while
 * already holding {@link #mLock}. Conversely, it's safe to acquire {@link #mLock}
 * momentarily while already holding {@link #mSnapshotLock}.
 * </ul>
 * Many internal methods rely on the caller to hold the appropriate locks, and
 * this contract is expressed through method name suffixes:
 * <ul>
 * <li>fooLI(): the caller must hold {@link #mInstallLock}
 * <li>fooLIF(): the caller must hold {@link #mInstallLock} and the package
 * being modified must be frozen
 * <li>fooLPr(): the caller must hold {@link #mLock} for reading
 * <li>fooLPw(): the caller must hold {@link #mLock} for writing
 * </ul>
 * {@link #mSnapshotLock} is taken in exactly one place - {@code snapshotComputer()}.  It
 * should not be taken anywhere else or used for any other purpose.
 * <p>
 * Because this class is very central to the platform's security; please run all
 * CTS and unit tests whenever making modifications:
 *
 * <pre>
 * $ runtest -c android.content.pm.PackageManagerTests frameworks-core
 * $ cts-tradefed run commandAndExit cts -m CtsAppSecurityHostTestCases
 * </pre>
 */
public class PackageManagerService implements PackageSender, TestUtilityService {

    static final String TAG = "PackageManager";
    public static final boolean DEBUG_SETTINGS = false;
    static final boolean DEBUG_PREFERRED = false;
    static final boolean DEBUG_UPGRADE = false;
    static final boolean DEBUG_DOMAIN_VERIFICATION = false;
    static final boolean DEBUG_BACKUP = false;
    public static final boolean DEBUG_INSTALL = false;
    public static final boolean DEBUG_REMOVE = false;
    static final boolean DEBUG_PACKAGE_INFO = false;
    static final boolean DEBUG_INTENT_MATCHING = false;
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    static final boolean DEBUG_VERIFY = false;
    public static final boolean DEBUG_PERMISSIONS = false;
    public static final boolean DEBUG_COMPRESSION = Build.IS_DEBUGGABLE;
    public static final boolean TRACE_SNAPSHOTS = false;
    private static final boolean DEBUG_PER_UID_READ_TIMEOUTS = false;

    // Debug output for dexopting. This is shared between PackageManagerService, OtaDexoptService
    // and PackageDexOptimizer. All these classes have their own flag to allow switching a single
    // user, but by default initialize to this.
    public static final boolean DEBUG_DEXOPT = false;

    static final boolean DEBUG_ABI_SELECTION = false;
    public static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;

    static final String SHELL_PACKAGE_NAME = "com.android.shell";

    static final boolean HIDE_EPHEMERAL_APIS = false;

    static final String PRECOMPILE_LAYOUTS = "pm.precompile_layouts";

    private static final int RADIO_UID = Process.PHONE_UID;
    private static final int LOG_UID = Process.LOG_UID;
    private static final int NFC_UID = Process.NFC_UID;
    private static final int BLUETOOTH_UID = Process.BLUETOOTH_UID;
    private static final int SHELL_UID = Process.SHELL_UID;
    private static final int SE_UID = Process.SE_UID;
    private static final int NETWORKSTACK_UID = Process.NETWORK_STACK_UID;
    private static final int UWB_UID = Process.UWB_UID;

    static final int SCAN_NO_DEX = 1 << 0;
    static final int SCAN_UPDATE_SIGNATURE = 1 << 1;
    static final int SCAN_NEW_INSTALL = 1 << 2;
    static final int SCAN_UPDATE_TIME = 1 << 3;
    static final int SCAN_BOOTING = 1 << 4;
    static final int SCAN_REQUIRE_KNOWN = 1 << 7;
    static final int SCAN_MOVE = 1 << 8;
    static final int SCAN_INITIAL = 1 << 9;
    static final int SCAN_DONT_KILL_APP = 1 << 10;
    static final int SCAN_IGNORE_FROZEN = 1 << 11;
    static final int SCAN_FIRST_BOOT_OR_UPGRADE = 1 << 12;
    static final int SCAN_AS_INSTANT_APP = 1 << 13;
    static final int SCAN_AS_FULL_APP = 1 << 14;
    static final int SCAN_AS_VIRTUAL_PRELOAD = 1 << 15;
    static final int SCAN_AS_SYSTEM = 1 << 16;
    static final int SCAN_AS_PRIVILEGED = 1 << 17;
    static final int SCAN_AS_OEM = 1 << 18;
    static final int SCAN_AS_VENDOR = 1 << 19;
    static final int SCAN_AS_PRODUCT = 1 << 20;
    static final int SCAN_AS_SYSTEM_EXT = 1 << 21;
    static final int SCAN_AS_ODM = 1 << 22;
    static final int SCAN_AS_APK_IN_APEX = 1 << 23;
    static final int SCAN_DROP_CACHE = 1 << 24;
    static final int SCAN_AS_FACTORY = 1 << 25;
    static final int SCAN_AS_APEX = 1 << 26;
    static final int SCAN_AS_STOPPED_SYSTEM_APP = 1 << 27;

    @IntDef(flag = true, prefix = { "SCAN_" }, value = {
            SCAN_NO_DEX,
            SCAN_UPDATE_SIGNATURE,
            SCAN_NEW_INSTALL,
            SCAN_UPDATE_TIME,
            SCAN_BOOTING,
            SCAN_REQUIRE_KNOWN,
            SCAN_MOVE,
            SCAN_INITIAL,
            SCAN_DONT_KILL_APP,
            SCAN_IGNORE_FROZEN,
            SCAN_FIRST_BOOT_OR_UPGRADE,
            SCAN_AS_INSTANT_APP,
            SCAN_AS_FULL_APP,
            SCAN_AS_VIRTUAL_PRELOAD,
            SCAN_AS_STOPPED_SYSTEM_APP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanFlags {}

    /**
     * Used as the result code of the {@link Computer#getPackageStartability(boolean, String, int,
     * int)}.
     */
    @IntDef(value = {
        PACKAGE_STARTABILITY_OK,
        PACKAGE_STARTABILITY_NOT_FOUND,
        PACKAGE_STARTABILITY_NOT_SYSTEM,
        PACKAGE_STARTABILITY_FROZEN,
        PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageStartability {}

    /**
     * Used as the result code of the {@link Computer#getPackageStartability(boolean, String, int,
     * int)} to indicate the given package is allowed to start.
     */
    public static final int PACKAGE_STARTABILITY_OK = 0;

    /**
     * Used as the result code of the {@link Computer#getPackageStartability(boolean, String, int,
     * int)} to indicate the given package is <b>not</b> allowed to start because it's not found
     * (could be due to that package is invisible to the given user).
     */
    public static final int PACKAGE_STARTABILITY_NOT_FOUND = 1;

    /**
     * Used as the result code of the {@link Computer#getPackageStartability(boolean, String, int,
     * int)} to indicate the given package is <b>not</b> allowed to start because it's not a system
     * app and the system is running in safe mode.
     */
    public static final int PACKAGE_STARTABILITY_NOT_SYSTEM = 2;

    /**
     * Used as the result code of the {@link Computer#getPackageStartability(boolean, String, int,
     * int)} to indicate the given package is <b>not</b> allowed to start because it's currently
     * frozen.
     */
    public static final int PACKAGE_STARTABILITY_FROZEN = 3;

    /**
     * Used as the result code of the {@link Computer#getPackageStartability(boolean, String, int,
     * int)} to indicate the given package is <b>not</b> allowed to start because it doesn't support
     * direct boot.
     */
    public static final int PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED = 4;

    private static final String STATIC_SHARED_LIB_DELIMITER = "_";
    /**
     * Extension of the compressed packages
     */
    public final static String COMPRESSED_EXTENSION = ".gz";
    /** Suffix of stub packages on the system partition */
    public final static String STUB_SUFFIX = "-Stub";

    static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * Timeout (in milliseconds) after which the watchdog should declare that
     * our handler thread is wedged.  The usual default for such things is one
     * minute but we sometimes do very lengthy I/O operations on this thread,
     * such as installing multi-gigabyte applications, so ours needs to be longer.
     */
    static final long WATCHDOG_TIMEOUT = 1000*60*10;     // ten minutes

    /**
     * Default IncFs timeouts. Maximum values in IncFs is 1hr.
     *
     * <p>If flag value is empty, the default value will be assigned.
     *
     * Flag type: {@code String}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_INCFS_DEFAULT_TIMEOUTS = "incfs_default_timeouts";

    /**
     * Known digesters with optional timeouts.
     *
     * Flag type: {@code String}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_KNOWN_DIGESTERS_LIST = "known_digesters_list";

    /**
     * Whether or not requesting the approval before committing sessions is available.
     *
     * Flag type: {@code boolean}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE =
            "is_preapproval_available";

    /**
     * Whether or not the update ownership enforcement is available.
     *
     * Flag type: {@code boolean}
     * Namespace: NAMESPACE_PACKAGE_MANAGER_SERVICE
     */
    private static final String PROPERTY_IS_UPDATE_OWNERSHIP_ENFORCEMENT_AVAILABLE =
            "is_update_ownership_enforcement_available";

    private static final String PROPERTY_DEFERRED_NO_KILL_POST_DELETE_DELAY_MS_EXTENDED =
            "deferred_no_kill_post_delete_delay_ms_extended";

    /**
     * The default response for package verification timeout.
     *
     * This can be either PackageManager.VERIFICATION_ALLOW or
     * PackageManager.VERIFICATION_REJECT.
     */
    static final int DEFAULT_VERIFICATION_RESPONSE = PackageManager.VERIFICATION_ALLOW;

    /**
     * Adding an installer package name to a package that does not have one set requires the
     * INSTALL_PACKAGES permission.
     *
     * If the caller targets R, this will throw a SecurityException. Otherwise the request will
     * fail silently. In both cases, and regardless of whether this change is enabled, the
     * installer package will remain unchanged.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long THROW_EXCEPTION_ON_REQUIRE_INSTALL_PACKAGES_TO_ADD_INSTALLER_PACKAGE =
            150857253;

    public static final String PLATFORM_PACKAGE_NAME = "android";

    static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    static final String PACKAGE_SCHEME = "package";

    private static final String COMPANION_PACKAGE_NAME = "com.android.companiondevicemanager";

    // How many required verifiers can be on the system.
    private static final int REQUIRED_VERIFIERS_MAX_COUNT = 2;

    /**
     * Specifies the minimum target SDK version an apk must specify in order to be installed
     * on the system. This improves security and privacy by blocking low
     * target sdk apps as malware can target older sdk versions to avoid
     * the enforcement of new API behavior.
     */
    public static final int MIN_INSTALLABLE_TARGET_SDK = Build.VERSION_CODES.M;

    // Compilation reasons.
    // TODO(b/260124949): Clean this up with the legacy dexopt code.
    public static final int REASON_FIRST_BOOT = 0;
    public static final int REASON_BOOT_AFTER_OTA = 1;
    public static final int REASON_POST_BOOT = 2;
    public static final int REASON_INSTALL = 3;
    public static final int REASON_INSTALL_FAST = 4;
    public static final int REASON_INSTALL_BULK = 5;
    public static final int REASON_INSTALL_BULK_SECONDARY = 6;
    public static final int REASON_INSTALL_BULK_DOWNGRADED = 7;
    public static final int REASON_INSTALL_BULK_SECONDARY_DOWNGRADED = 8;
    public static final int REASON_BACKGROUND_DEXOPT = 9;
    public static final int REASON_AB_OTA = 10;
    public static final int REASON_INACTIVE_PACKAGE_DOWNGRADE = 11;
    public static final int REASON_CMDLINE = 12;
    public static final int REASON_BOOT_AFTER_MAINLINE_UPDATE = 13;
    public static final int REASON_SHARED = 14;

    public static final int REASON_LAST = REASON_SHARED;

    static final String RANDOM_DIR_PREFIX = "~~";
    static final char RANDOM_CODEPATH_PREFIX = '-';

    static final String APP_METADATA_FILE_NAME = "app.metadata";

    final Handler mHandler;
    final Handler mBackgroundHandler;

    final ProcessLoggingHandler mProcessLoggingHandler;

    private final int mSdkVersion;
    final Context mContext;
    final boolean mFactoryTest;
    final DisplayMetrics mMetrics;
    private final int mDefParseFlags;
    private final String[] mSeparateProcesses;
    private final boolean mIsUpgrade;
    private final boolean mIsPreNMR1Upgrade;
    private final boolean mIsPreQUpgrade;
    // If mIsUpgrade == true, contains the prior SDK version, else -1.
    private final int mPriorSdkVersion;

    // Used for privilege escalation. MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    @GuardedBy("mInstallLock")
    final Installer mInstaller;

    /** Directory where installed applications are stored */
    private final File mAppInstallDir;

    // ----------------------------------------------------------------

    // Lock for state used when installing and doing other long running
    // operations.  Methods that must be called with this lock held have
    // the suffix "LI".
    final Object mInstallLock;

    // ----------------------------------------------------------------

    // Lock for global state used when modifying package state or settings.
    // Methods that must be called with this lock held have
    // the suffix "Locked". Some methods may use the legacy suffix "LP"
    final PackageManagerTracedLock mLock;

    // Ensures order of overlay updates until data storage can be moved to overlay code
    private final PackageManagerTracedLock mOverlayPathsLock = new PackageManagerTracedLock();

    // Lock alias for doing package state mutation
    private final PackageManagerTracedLock mPackageStateWriteLock;

    private final PackageStateMutator mPackageStateMutator = new PackageStateMutator(
            this::getPackageSettingForMutation,
            this::getDisabledPackageSettingForMutation);

    // Keys are String (package name), values are Package.
    @Watched
    @GuardedBy("mLock")
    final WatchedArrayMap<String, AndroidPackage> mPackages = new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<String, AndroidPackage>> mPackagesSnapshot =
            new SnapshotCache.Auto(mPackages, mPackages, "PackageManagerService.mPackages");

    // Keys are isolated uids and values are the uid of the application
    // that created the isolated process.
    @Watched
    @GuardedBy("mLock")
    final WatchedSparseIntArray mIsolatedOwners = new WatchedSparseIntArray();
    private final SnapshotCache<WatchedSparseIntArray> mIsolatedOwnersSnapshot =
            new SnapshotCache.Auto(mIsolatedOwners, mIsolatedOwners,
                                   "PackageManagerService.mIsolatedOwners");

    /**
     * Tracks existing packages prior to receiving an OTA. Keys are package name.
     * Only non-null during an OTA, and even then it is nulled again once systemReady().
     */
    private @Nullable ArraySet<String> mExistingPackages = null;

    /**
     * List of code paths that need to be released when the system becomes ready.
     * <p>
     * NOTE: We have to delay releasing cblocks for no other reason than we cannot
     * retrieve the setting {@link Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL}. When
     * we no longer need to read that setting, cblock release can occur in the
     * constructor.
     *
     * @see Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL
     * @see #systemReady()
     */
    @Nullable List<File> mReleaseOnSystemReady;

    /**
     * Whether or not system app permissions should be promoted from install to runtime.
     */
    boolean mPromoteSystemApps;

    private final TestUtilityService mTestUtilityService;

    @Watched
    @GuardedBy("mLock")
    final Settings mSettings;

    /**
     * Map of package names to frozen counts that are currently "frozen",
     * which means active surgery is being done on the code/data for that
     * package. The platform will refuse to launch frozen packages to avoid
     * race conditions.
     *
     * @see PackageFreezer
     */
    @GuardedBy("mLock")
    final WatchedArrayMap<String, Integer> mFrozenPackages = new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<String, Integer>> mFrozenPackagesSnapshot =
            new SnapshotCache.Auto(mFrozenPackages, mFrozenPackages,
                    "PackageManagerService.mFrozenPackages");

    final ProtectedPackages mProtectedPackages;

    private boolean mFirstBoot;

    final boolean mIsEngBuild;
    private final boolean mIsUserDebugBuild;
    private final String mIncrementalVersion;

    PackageManagerInternal.ExternalSourcesPolicy mExternalSourcesPolicy;

    @GuardedBy("mAvailableFeatures")
    private final ArrayMap<String, FeatureInfo> mAvailableFeatures;

    @Watched
    final InstantAppRegistry mInstantAppRegistry;

    @NonNull
    final ChangedPackagesTracker mChangedPackagesTracker;

    @NonNull
    private final PackageObserverHelper mPackageObserverHelper = new PackageObserverHelper();

    @NonNull
    private final PackageMonitorCallbackHelper mPackageMonitorCallbackHelper;

    private final ModuleInfoProvider mModuleInfoProvider;

    final ApexManager mApexManager;

    final PackageManagerServiceInjector mInjector;

    /**
     * The list of all system partitions that may contain packages in ascending order of
     * specificity (the more generic, the earlier in the list a partition appears).
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public static final List<ScanPartition> SYSTEM_PARTITIONS = Collections.unmodifiableList(
            PackagePartitions.getOrderedPartitions(ScanPartition::new));

    private @NonNull final OverlayConfig mOverlayConfig;

    // Cached parsed flag value. Invalidated on each flag change.
    PerUidReadTimeouts[] mPerUidReadTimeoutsCache;

    private static final PerUidReadTimeouts[] EMPTY_PER_UID_READ_TIMEOUTS_ARRAY = {};

    private static class DefaultSystemWrapper implements
            PackageManagerServiceInjector.SystemWrapper {

        @Override
        public void disablePackageCaches() {
            // disable all package caches that shouldn't apply within system server
            PackageManager.disableApplicationInfoCache();
            PackageManager.disablePackageInfoCache();
            ApplicationPackageManager.invalidateGetPackagesForUidCache();
            ApplicationPackageManager.disableGetPackagesForUidCache();
            ApplicationPackageManager.invalidateHasSystemFeatureCache();
            PackageManager.corkPackageInfoCache();
        }

        @Override
        public void enablePackageCaches() {
            PackageManager.uncorkPackageInfoCache();
        }
    }

    @Watched
    final AppsFilterImpl mAppsFilter;

    final PackageParser2.Callback mPackageParserCallback;

    // Currently known shared libraries.
    @Watched
    private final SharedLibrariesImpl mSharedLibraries;

    // Mapping from instrumentation class names to info about them.
    @Watched
    private final WatchedArrayMap<ComponentName, ParsedInstrumentation> mInstrumentation =
            new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<ComponentName, ParsedInstrumentation>>
            mInstrumentationSnapshot =
            new SnapshotCache.Auto<>(mInstrumentation, mInstrumentation,
                                     "PackageManagerService.mInstrumentation");

    // Packages whose data we have transfered into another package, thus
    // should no longer exist.
    final ArraySet<String> mTransferredPackages = new ArraySet<>();

    // Broadcast actions that are only available to the system.
    @GuardedBy("mProtectedBroadcasts")
    final ArraySet<String> mProtectedBroadcasts = new ArraySet<>();

    /**
     * List of packages waiting for verification.
     * Handler thread only!
     */
    final SparseArray<PackageVerificationState> mPendingVerification = new SparseArray<>();

    /**
     * List of packages waiting for rollback to be enabled.
     * Handler thread only!
     */
    final SparseArray<VerifyingSession> mPendingEnableRollback = new SparseArray<>();

    final PackageInstallerService mInstallerService;
    final ArtManagerService mArtManagerService;

    // TODO(b/260124949): Remove these.
    final PackageDexOptimizer mPackageDexOptimizer;
    @Nullable
    final BackgroundDexOptService mBackgroundDexOptService; // null when ART Service is in use.
    // DexManager handles the usage of dex files (e.g. secondary files, whether or not a package
    // is used by other apps).
    private final DexManager mDexManager;
    private final DynamicCodeLogger mDynamicCodeLogger;

    private final AtomicInteger mNextMoveId = new AtomicInteger();
    final MovePackageHelper.MoveCallbacks mMoveCallbacks;

    /**
     * Token for keys in mPendingVerification.
     * Handler thread only!
     */
    int mPendingVerificationToken = 0;

    /**
     * Token for keys in mPendingEnableRollback.
     * Handler thread only!
     */
    int mPendingEnableRollbackToken = 0;

    @Watched(manual = true)
    private volatile boolean mSystemReady;
    @Watched(manual = true)
    private volatile boolean mSafeMode;
    @Watched
    private final WatchedSparseBooleanArray mWebInstantAppsDisabled =
            new WatchedSparseBooleanArray();

    @Watched(manual = true)
    private ApplicationInfo mAndroidApplication;
    @Watched(manual = true)
    private final ActivityInfo mResolveActivity = new ActivityInfo();
    private final ResolveInfo mResolveInfo = new ResolveInfo();
    @Watched(manual = true)
    ComponentName mResolveComponentName;
    private AndroidPackage mPlatformPackage;
    ComponentName mCustomResolverComponentName;

    // Recorded overlay paths configuration for the Android app info.
    private String[] mPlatformPackageOverlayPaths = null;
    private String[] mPlatformPackageOverlayResourceDirs = null;
    // And the same paths for the replaced resolver activity package
    private String[] mReplacedResolverPackageOverlayPaths = null;
    private String[] mReplacedResolverPackageOverlayResourceDirs = null;

    private boolean mResolverReplaced = false;

    @NonNull
    final DomainVerificationManagerInternal mDomainVerificationManager;

    /** The service connection to the ephemeral resolver */
    final InstantAppResolverConnection mInstantAppResolverConnection;
    /** Component used to show resolver settings for Instant Apps */
    final ComponentName mInstantAppResolverSettingsComponent;

    /** Activity used to install instant applications */
    @Watched(manual = true)
    ActivityInfo mInstantAppInstallerActivity;
    @Watched(manual = true)
    private final ResolveInfo mInstantAppInstallerInfo = new ResolveInfo();

    private final Map<String, InstallRequest>
            mNoKillInstallObservers = Collections.synchronizedMap(new HashMap<>());

    private final Map<String, InstallRequest>
            mPendingKillInstallObservers = Collections.synchronizedMap(new HashMap<>());

    // Internal interface for permission manager
    final PermissionManagerServiceInternal mPermissionManager;

    @Watched
    final ComponentResolver mComponentResolver;

    // Set of packages names to keep cached, even if they are uninstalled for all users
    @GuardedBy("mKeepUninstalledPackages")
    @NonNull
    private final ArraySet<String> mKeepUninstalledPackages = new ArraySet<>();

    // Cached reference to IDevicePolicyManager.
    private IDevicePolicyManager mDevicePolicyManager = null;

    private File mCacheDir;

    private Future<?> mPrepareAppDataFuture;

    final IncrementalManager mIncrementalManager;

    private final DefaultAppProvider mDefaultAppProvider;

    private final LegacyPermissionManagerInternal mLegacyPermissionManager;

    private final PackageProperty mPackageProperty = new PackageProperty();

    final PendingPackageBroadcasts mPendingBroadcasts;

    static final int SEND_PENDING_BROADCAST = 1;
    // public static final int UNUSED = 5;
    static final int POST_INSTALL = 9;
    static final int WRITE_SETTINGS = 13;
    static final int WRITE_DIRTY_PACKAGE_RESTRICTIONS = 14;
    static final int PACKAGE_VERIFIED = 15;
    static final int CHECK_PENDING_VERIFICATION = 16;
    // public static final int UNUSED = 17;
    // public static final int UNUSED = 18;
    static final int WRITE_PACKAGE_LIST = 19;
    static final int INSTANT_APP_RESOLUTION_PHASE_TWO = 20;
    static final int ENABLE_ROLLBACK_STATUS = 21;
    static final int ENABLE_ROLLBACK_TIMEOUT = 22;
    static final int DEFERRED_NO_KILL_POST_DELETE = 23;
    static final int DEFERRED_NO_KILL_INSTALL_OBSERVER = 24;
    static final int INTEGRITY_VERIFICATION_COMPLETE = 25;
    static final int CHECK_PENDING_INTEGRITY_VERIFICATION = 26;
    static final int DOMAIN_VERIFICATION = 27;
    static final int PRUNE_UNUSED_STATIC_SHARED_LIBRARIES = 28;
    static final int DEFERRED_PENDING_KILL_INSTALL_OBSERVER = 29;

    static final int WRITE_USER_PACKAGE_RESTRICTIONS = 30;

    private static final int DEFERRED_NO_KILL_POST_DELETE_DELAY_MS = 3 * 1000;

    private static final long DEFERRED_NO_KILL_POST_DELETE_DELAY_MS_EXTENDED =
            TimeUnit.DAYS.toMillis(1);

    private static final int DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS = 500;
    private static final int DEFERRED_PENDING_KILL_INSTALL_OBSERVER_DELAY_MS = 1000;

    static final int WRITE_SETTINGS_DELAY = 10*1000;  // 10 seconds

    private static final long BROADCAST_DELAY_DURING_STARTUP = 10 * 1000L; // 10 seconds (in millis)
    private static final long BROADCAST_DELAY = 1 * 1000L; // 1 second (in millis)

    private static final long PRUNE_UNUSED_SHARED_LIBRARIES_DELAY =
            TimeUnit.MINUTES.toMillis(3); // 3 minutes

    // When the service constructor finished plus a delay (used for broadcast delay computation)
    private long mServiceStartWithDelay;

    static final long DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD =
            TimeUnit.DAYS.toMillis(7); /* 7 days */

    final UserManagerService mUserManager;

    final UserNeedsBadgingCache mUserNeedsBadging;

    // Stores a list of users whose package restrictions file needs to be updated
    final ArraySet<Integer> mDirtyUsers = new ArraySet<>();

    final SparseArray<InstallRequest> mRunningInstalls = new SparseArray<>();
    int mNextInstallToken = 1;  // nonzero; will be wrapped back to 1 when ++ overflows

    final @NonNull String[] mRequiredVerifierPackages;
    final @NonNull String mRequiredInstallerPackage;
    final @NonNull String mRequiredUninstallerPackage;
    final @NonNull String mRequiredPermissionControllerPackage;
    final @Nullable String mSetupWizardPackage;
    final @Nullable String mStorageManagerPackage;
    final @Nullable String mDefaultTextClassifierPackage;
    final @Nullable String mSystemTextClassifierPackageName;
    final @Nullable String mConfiguratorPackage;
    final @Nullable String mAppPredictionServicePackage;
    final @Nullable String mIncidentReportApproverPackage;
    final @Nullable String mServicesExtensionPackageName;
    final @Nullable String mSharedSystemSharedLibraryPackageName;
    final @Nullable String mRetailDemoPackage;
    final @Nullable String mOverlayConfigSignaturePackage;
    final @Nullable String mRecentsPackage;
    final @Nullable String mAmbientContextDetectionPackage;
    final @Nullable String mWearableSensingPackage;
    final @NonNull Set<String> mInitialNonStoppedSystemPackages;
    final boolean mShouldStopSystemPackagesByDefault;
    private final @NonNull String mRequiredSdkSandboxPackage;

    @GuardedBy("mLock")
    private final PackageUsage mPackageUsage = new PackageUsage();
    final CompilerStats mCompilerStats = new CompilerStats();

    private final DomainVerificationConnection mDomainVerificationConnection;

    private final BroadcastHelper mBroadcastHelper;
    private final RemovePackageHelper mRemovePackageHelper;
    private final DeletePackageHelper mDeletePackageHelper;
    private final InitAppsHelper mInitAppsHelper;
    private final AppDataHelper mAppDataHelper;
    @NonNull private final InstallPackageHelper mInstallPackageHelper;
    private final PreferredActivityHelper mPreferredActivityHelper;
    private final ResolveIntentHelper mResolveIntentHelper;
    private final DexOptHelper mDexOptHelper;
    private final SuspendPackageHelper mSuspendPackageHelper;
    private final DistractingPackageHelper mDistractingPackageHelper;
    private final StorageEventHelper mStorageEventHelper;
    private final FreeStorageHelper mFreeStorageHelper;


    private static final boolean ENABLE_BOOST = false;

    private static ThreadPriorityBooster sThreadPriorityBooster = new ThreadPriorityBooster(
            Process.THREAD_PRIORITY_FOREGROUND, LockGuard.INDEX_PACKAGES);

    /**
     * Boost the priority of the thread before holding PM traced lock.
     * @hide
     */
    public static void boostPriorityForPackageManagerTracedLockedSection() {
        if (ENABLE_BOOST) {
            sThreadPriorityBooster.boost();
        }
    }


    /**
     * Restore the priority of the thread after release the PM traced lock.
     * @hide
     */
    public static void resetPriorityAfterPackageManagerTracedLockedSection() {
        if (ENABLE_BOOST) {
            sThreadPriorityBooster.reset();
        }
    }

    /**
     * Invalidate the package info cache, which includes updating the cached computer.
     * @hide
     */
    public static void invalidatePackageInfoCache() {
        PackageManager.invalidatePackageInfoCache();
        onChanged();
    }

    private final Watcher mWatcher = new Watcher() {
            @Override
                       public void onChange(@Nullable Watchable what) {
                PackageManagerService.onChange(what);
            }
        };

    /**
     * A Snapshot is a subset of PackageManagerService state.  A snapshot is either live
     * or snapped.  Live snapshots directly reference PackageManagerService attributes.
     * Snapped snapshots contain deep copies of the attributes.
     */
    class Snapshot {
        public static final int LIVE = 1;
        public static final int SNAPPED = 2;

        public final Settings settings;
        public final WatchedSparseIntArray isolatedOwners;
        public final WatchedArrayMap<String, AndroidPackage> packages;
        public final WatchedArrayMap<ComponentName, ParsedInstrumentation> instrumentation;
        public final WatchedSparseBooleanArray webInstantAppsDisabled;
        public final ComponentName resolveComponentName;
        public final ActivityInfo resolveActivity;
        public final ActivityInfo instantAppInstallerActivity;
        public final ResolveInfo instantAppInstallerInfo;
        public final InstantAppRegistry instantAppRegistry;
        public final ApplicationInfo androidApplication;
        public final String appPredictionServicePackage;
        public final AppsFilterSnapshot appsFilter;
        public final ComponentResolverApi componentResolver;
        public final PackageManagerService service;
        public final WatchedArrayMap<String, Integer> frozenPackages;
        public final SharedLibrariesRead sharedLibraries;

        Snapshot(int type) {
            if (type == Snapshot.SNAPPED) {
                settings = mSettings.snapshot();
                isolatedOwners = mIsolatedOwnersSnapshot.snapshot();
                packages = mPackagesSnapshot.snapshot();
                instrumentation = mInstrumentationSnapshot.snapshot();
                resolveComponentName = mResolveComponentName == null
                        ? null : mResolveComponentName.clone();
                resolveActivity = new ActivityInfo(mResolveActivity);
                instantAppInstallerActivity =
                        (mInstantAppInstallerActivity == null)
                        ? null
                        : new ActivityInfo(mInstantAppInstallerActivity);
                instantAppInstallerInfo = new ResolveInfo(mInstantAppInstallerInfo);
                webInstantAppsDisabled = mWebInstantAppsDisabled.snapshot();
                instantAppRegistry = mInstantAppRegistry.snapshot();
                androidApplication =
                        (mAndroidApplication == null)
                        ? null
                        : new ApplicationInfo(mAndroidApplication);
                appPredictionServicePackage = mAppPredictionServicePackage;
                appsFilter = mAppsFilter.snapshot();
                componentResolver = mComponentResolver.snapshot();
                frozenPackages = mFrozenPackagesSnapshot.snapshot();
                sharedLibraries = mSharedLibraries.snapshot();
            } else if (type == Snapshot.LIVE) {
                settings = mSettings;
                isolatedOwners = mIsolatedOwners;
                packages = mPackages;
                instrumentation = mInstrumentation;
                resolveComponentName = mResolveComponentName;
                resolveActivity = mResolveActivity;
                instantAppInstallerActivity = mInstantAppInstallerActivity;
                instantAppInstallerInfo = mInstantAppInstallerInfo;
                webInstantAppsDisabled = mWebInstantAppsDisabled;
                instantAppRegistry = mInstantAppRegistry;
                androidApplication = mAndroidApplication;
                appPredictionServicePackage = mAppPredictionServicePackage;
                appsFilter = mAppsFilter;
                componentResolver = mComponentResolver;
                frozenPackages = mFrozenPackages;
                sharedLibraries = mSharedLibraries;
            } else {
                throw new IllegalArgumentException();
            }
            service = PackageManagerService.this;
        }
    }

    // Compute read-only functions, based on live data.  This attribute may be modified multiple
    // times during the PackageManagerService constructor but it should not be modified thereafter.
    private ComputerLocked mLiveComputer;

    private static final AtomicReference<Computer> sSnapshot = new AtomicReference<>();

    // If this differs from Computer#getVersion, the snapshot is invalid (stale).
    private static final AtomicInteger sSnapshotPendingVersion = new AtomicInteger(1);

    /**
     * This lock is used to make reads from {@link #sSnapshotPendingVersion} and
     * {@link #sSnapshot} atomic inside {@code snapshotComputer()} when the versions mismatch.
     * This lock is not meant to be used outside that method. This lock must be taken before
     * {@link #mLock} is taken.
     */
    private final Object mSnapshotLock = new Object();

    /**
     * The snapshot statistics.  These are collected to track performance and to identify
     * situations in which the snapshots are misbehaving.
     */
    @Nullable
    private final SnapshotStatistics mSnapshotStatistics;

    /**
     * Return the cached computer.  The method will rebuild the cached computer if necessary.
     * The live computer will be returned if snapshots are disabled.
     */
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    @NonNull
    public Computer snapshotComputer() {
        return snapshotComputer(true /*allowLiveComputer*/);
    }

    /**
     * This method should only ever be called from {@link PackageManagerLocal#snapshot()}.
     *
     * @param allowLiveComputer Whether to allow a live computer instance based on caller {@link
     *                          #mLock} hold state. In certain cases, like for {@link
     *                          PackageManagerLocal} API, it must be enforced that the caller gets
     *                          a snapshot at time, and never the live variant.
     * @deprecated Use {@link #snapshotComputer()}
     */
    @Deprecated
    @NonNull
    public Computer snapshotComputer(boolean allowLiveComputer) {
        var isHoldingPackageLock = Thread.holdsLock(mLock);
        if (allowLiveComputer) {
            if (isHoldingPackageLock) {
                // If the current thread holds mLock then it may have modified state but not
                // yet invalidated the snapshot.  Always give the thread the live computer.
                return mLiveComputer;
            }
        }

        var oldSnapshot = sSnapshot.get();
        var pendingVersion = sSnapshotPendingVersion.get();

        if (oldSnapshot != null && oldSnapshot.getVersion() == pendingVersion) {
            return oldSnapshot.use();
        }

        if (isHoldingPackageLock) {
            // If the current thread holds mLock then it already has exclusive write access to the
            // two snapshot fields, and we can just go ahead and rebuild the snapshot.
            @SuppressWarnings("GuardedBy")
            var newSnapshot = rebuildSnapshot(oldSnapshot, pendingVersion);
            sSnapshot.set(newSnapshot);
            return newSnapshot.use();
        }

        synchronized (mSnapshotLock) {
            // Re-capture pending version in case a new invalidation occurred since last check
            var rebuildSnapshot = sSnapshot.get();
            var rebuildVersion = sSnapshotPendingVersion.get();

            // Check the versions again while the lock is held, in case the rebuild time caused
            // multiple threads to wait on the snapshot lock. When the first thread finishes
            // a rebuild, the snapshot is now valid and the other waiting threads can use it
            // without kicking off their own rebuilds.
            if (rebuildSnapshot != null && rebuildSnapshot.getVersion() == rebuildVersion) {
                return rebuildSnapshot.use();
            }

            synchronized (mLock) {
                // Fetch version one last time to ensure that the rebuilt snapshot matches
                // the latest invalidation, which could have come in between entering the
                // SnapshotLock and mLock sync blocks.
                rebuildSnapshot = sSnapshot.get();
                rebuildVersion = sSnapshotPendingVersion.get();
                if (rebuildSnapshot != null && rebuildSnapshot.getVersion() == rebuildVersion) {
                    return rebuildSnapshot.use();
                }

                // Build the snapshot for this version
                var newSnapshot = rebuildSnapshot(rebuildSnapshot, rebuildVersion);
                sSnapshot.set(newSnapshot);
                return newSnapshot.use();
            }
        }
    }

    @GuardedBy("mLock")
    private Computer rebuildSnapshot(@Nullable Computer oldSnapshot, int newVersion) {
        var now = SystemClock.currentTimeMicro();
        var hits = oldSnapshot == null ? -1 : oldSnapshot.getUsed();
        var args = new Snapshot(Snapshot.SNAPPED);
        var newSnapshot = new ComputerEngine(args, newVersion);
        var done = SystemClock.currentTimeMicro();

        if (mSnapshotStatistics != null) {
            mSnapshotStatistics.rebuild(now, done, hits, newSnapshot.getPackageStates().size());
        }
        return newSnapshot;
    }

    /**
     * Create a live computer
     */
    private ComputerLocked createLiveComputer() {
        return new ComputerLocked(new Snapshot(Snapshot.LIVE));
    }

    /**
     * This method is called when the state of PackageManagerService changes so as to
     * invalidate the current snapshot.
     * @param what The {@link Watchable} that reported the change
     * @hide
     */
    public static void onChange(@Nullable Watchable what) {
        if (TRACE_SNAPSHOTS) {
            Log.i(TAG, "snapshot: onChange(" + what + ")");
        }
        sSnapshotPendingVersion.incrementAndGet();
    }

    /**
     * Report a locally-detected change to observers.  The <what> parameter is left null,
     * but it signifies that the change was detected by PackageManagerService itself.
     */
    static void onChanged() {
        onChange(null);
    }

    void notifyInstallObserver(String packageName, boolean killApp) {
        final InstallRequest installRequest =
                killApp ? mPendingKillInstallObservers.remove(packageName)
                        : mNoKillInstallObservers.remove(packageName);

        if (installRequest != null) {
            notifyInstallObserver(installRequest);
        }
    }

    void notifyInstallObserver(InstallRequest request) {
        if (request.getObserver() != null) {
            try {
                Bundle extras = extrasForInstallResult(request);
                request.getObserver().onPackageInstalled(request.getName(),
                        request.getReturnCode(), request.getReturnMsg(), extras);
            } catch (RemoteException e) {
                Slog.i(TAG, "Observer no longer exists.");
            }
        }
    }

    void scheduleDeferredNoKillInstallObserver(InstallRequest request) {
        String packageName = request.getPkg().getPackageName();
        mNoKillInstallObservers.put(packageName, request);
        Message message = mHandler.obtainMessage(DEFERRED_NO_KILL_INSTALL_OBSERVER, packageName);
        mHandler.sendMessageDelayed(message, DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS);
    }

    void scheduleDeferredNoKillPostDelete(CleanUpArgs args) {
        Message message = mHandler.obtainMessage(DEFERRED_NO_KILL_POST_DELETE, args);
        // If the feature flag is on, retain the old files for a day. Otherwise, delete the old
        // files after a few seconds.
        long deleteDelayMillis = DEFERRED_NO_KILL_POST_DELETE_DELAY_MS;
        if (Flags.improveInstallDontKill()) {
            deleteDelayMillis = Binder.withCleanCallingIdentity(() -> {
                return DeviceConfig.getLong(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                        /* name= */ PROPERTY_DEFERRED_NO_KILL_POST_DELETE_DELAY_MS_EXTENDED,
                        /* defaultValue= */ DEFERRED_NO_KILL_POST_DELETE_DELAY_MS_EXTENDED);
            });
            Slog.w(TAG, "Delaying the deletion of <" + args.getCodePath() + "> by "
                    + deleteDelayMillis + "ms or till the next reboot");
        }
        mHandler.sendMessageDelayed(message, deleteDelayMillis);
    }

    void schedulePruneUnusedStaticSharedLibraries(boolean delay) {
        mHandler.removeMessages(PRUNE_UNUSED_STATIC_SHARED_LIBRARIES);
        mHandler.sendEmptyMessageDelayed(PRUNE_UNUSED_STATIC_SHARED_LIBRARIES,
                delay ? getPruneUnusedSharedLibrariesDelay() : 0);
    }

    void scheduleDeferredPendingKillInstallObserver(InstallRequest request) {
        final String packageName = request.getPkg().getPackageName();
        mPendingKillInstallObservers.put(packageName, request);
        final Message message = mHandler.obtainMessage(DEFERRED_PENDING_KILL_INSTALL_OBSERVER,
                packageName);
        mHandler.sendMessageDelayed(message, DEFERRED_PENDING_KILL_INSTALL_OBSERVER_DELAY_MS);
    }

    private static long getPruneUnusedSharedLibrariesDelay() {
        return SystemProperties.getLong("debug.pm.prune_unused_shared_libraries_delay",
                PRUNE_UNUSED_SHARED_LIBRARIES_DELAY);
    }

    /**
     * Requests checksums for the APK file.
     * See {@link PackageInstaller.Session#requestChecksums} for details.
     */
    public void requestFileChecksums(@NonNull File file,
            @NonNull String installerPackageName, @Checksum.TypeMask int optional,
            @Checksum.TypeMask int required, @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener)
            throws FileNotFoundException {
        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        final Executor executor = mInjector.getBackgroundExecutor();
        final Handler handler = mInjector.getBackgroundHandler();
        final Certificate[] trustedCerts = (trustedInstallers != null) ? decodeCertificates(
                trustedInstallers) : null;

        final List<Pair<String, File>> filesToChecksum = new ArrayList<>(1);
        filesToChecksum.add(Pair.create(null, file));

        executor.execute(() -> {
            ApkChecksums.Injector injector = new ApkChecksums.Injector(
                    () -> mContext,
                    () -> handler,
                    mInjector::getIncrementalManager,
                    () -> mInjector.getLocalService(PackageManagerInternal.class));
            ApkChecksums.getChecksums(filesToChecksum, optional, required, installerPackageName,
                    trustedCerts, onChecksumsReadyListener, injector);
        });
    }

    void requestChecksumsInternal(@NonNull Computer snapshot, @NonNull String packageName,
            boolean includeSplits, @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
            @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
            @NonNull Executor executor, @NonNull Handler handler) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(onChecksumsReadyListener);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(handler);

        final ApplicationInfo applicationInfo = snapshot.getApplicationInfoInternal(packageName, 0,
                Binder.getCallingUid(), userId);
        if (applicationInfo == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException(packageName));
        }

        final InstallSourceInfo installSourceInfo = snapshot.getInstallSourceInfo(packageName,
                userId);
        final String installerPackageName;
        if (installSourceInfo != null) {
            final String initiatingPackageName = installSourceInfo.getInitiatingPackageName();
            if (!isInstalledByAdb(initiatingPackageName)) {
                installerPackageName = initiatingPackageName;
            } else {
                installerPackageName = installSourceInfo.getInstallingPackageName();
            }
        } else {
            installerPackageName = null;
        }

        List<Pair<String, File>> filesToChecksum = new ArrayList<>();

        // Adding base split.
        filesToChecksum.add(Pair.create(null, new File(applicationInfo.sourceDir)));

        // Adding other splits.
        if (includeSplits && applicationInfo.splitNames != null) {
            for (int i = 0, size = applicationInfo.splitNames.length; i < size; ++i) {
                filesToChecksum.add(Pair.create(applicationInfo.splitNames[i],
                        new File(applicationInfo.splitSourceDirs[i])));
            }
        }

        final Certificate[] trustedCerts = (trustedInstallers != null) ? decodeCertificates(
                trustedInstallers) : null;

        executor.execute(() -> {
            ApkChecksums.Injector injector = new ApkChecksums.Injector(
                    () -> mContext,
                    () -> handler,
                    mInjector::getIncrementalManager,
                    () -> mInjector.getLocalService(PackageManagerInternal.class));
            ApkChecksums.getChecksums(filesToChecksum, optional, required, installerPackageName,
                    trustedCerts, onChecksumsReadyListener, injector);
        });
    }

    private static @NonNull Certificate[] decodeCertificates(@NonNull List certs) {
        try {
            final CertificateFactory cf = CertificateFactory.getInstance("X.509");
            final Certificate[] result = new Certificate[certs.size()];
            for (int i = 0, size = certs.size(); i < size; ++i) {
                final InputStream is = new ByteArrayInputStream((byte[]) certs.get(i));
                final X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                result[i] = cert;
            }
            return result;
        } catch (CertificateException e) {
            throw ExceptionUtils.propagate(e);
        }
    }

    private static Bundle extrasForInstallResult(InstallRequest request) {
        Bundle extras = null;
        switch (request.getReturnCode()) {
            case PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION: {
                extras = new Bundle();
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PERMISSION,
                        request.getOrigPermission());
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE,
                        request.getOrigPackage());
                break;
            }
            case PackageManager.INSTALL_SUCCEEDED: {
                extras = new Bundle();
                extras.putBoolean(Intent.EXTRA_REPLACING,
                        request.getRemovedInfo() != null
                                && request.getRemovedInfo().mRemovedPackage != null);
                break;
            }
        }
        if (!request.getWarnings().isEmpty()) {
            extras.putStringArrayList(PackageInstaller.EXTRA_WARNINGS, request.getWarnings());
        }
        return extras;
    }

    ArchivedPackageParcel getArchivedPackageInternal(@NonNull String packageName, int userId) {
        Objects.requireNonNull(packageName);
        int binderUid = Binder.getCallingUid();

        Computer snapshot = snapshotComputer();
        snapshot.enforceCrossUserPermission(binderUid, userId, true, true,
                "getArchivedPackage");

        ArchivedPackageParcel archPkg = new ArchivedPackageParcel();
        archPkg.packageName = packageName;

        ArchiveState archiveState;
        synchronized (mLock) {
            PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null) {
                return null;
            }
            var psi = ps.getUserStateOrDefault(userId);
            archiveState = psi.getArchiveState();
            if (archiveState == null && !psi.isInstalled()) {
                return null;
            }

            archPkg.signingDetails = ps.getSigningDetails();

            long longVersionCode = ps.getVersionCode();
            archPkg.versionCodeMajor = (int) (longVersionCode >> 32);
            archPkg.versionCode = (int) longVersionCode;

            archPkg.targetSdkVersion = ps.getTargetSdkVersion();

            // These get translated in flags important for user data management.
            archPkg.defaultToDeviceProtectedStorage = String.valueOf(
                    ps.isDefaultToDeviceProtectedStorage());
            archPkg.requestLegacyExternalStorage = String.valueOf(
                    ps.isRequestLegacyExternalStorage());
            archPkg.userDataFragile = String.valueOf(ps.isUserDataFragile());
        }

        try {
            if (archiveState != null) {
                archPkg.archivedActivities = PackageArchiver.createArchivedActivities(
                        archiveState);
            } else {
                final int iconSize = mContext.getSystemService(
                        ActivityManager.class).getLauncherLargeIconSize();

                var mainActivities =
                        mInstallerService.mPackageArchiver.getLauncherActivityInfos(packageName,
                                userId);
                archPkg.archivedActivities = PackageArchiver.createArchivedActivities(
                        mainActivities, iconSize);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Package does not have a main activity", e);
        }

        return archPkg;
    }

    void markPackageAsArchivedIfNeeded(PackageSetting pkgSetting,
                                       ArchivedPackageParcel archivePackage, int[] userIds) {
        if (pkgSetting == null || archivePackage == null
                || archivePackage.archivedActivities == null || userIds == null
                || userIds.length == 0) {
            return;
        }

        String responsibleInstallerPackage = PackageArchiver.getResponsibleInstallerPackage(
                pkgSetting);
        // TODO(b/278553670) Check if responsibleInstallerPackage supports unarchival.
        if (TextUtils.isEmpty(responsibleInstallerPackage)) {
            Slog.e(TAG, "Can't create archive state: responsible installer is empty");
            return;
        }
        for (int userId : userIds) {
            var archiveState = mInstallerService.mPackageArchiver.createArchiveState(
                    archivePackage, userId, responsibleInstallerPackage);
            if (archiveState == null) {
                continue;
            }
            pkgSetting
                    .setPkg(null)
                    .modifyUserState(userId)
                    .setInstalled(false)
                    .setArchiveState(archiveState);
        }
    }


    void scheduleWriteSettings() {
        // We normally invalidate when we write settings, but in cases where we delay and
        // coalesce settings writes, this strategy would have us invalidate the cache too late.
        // Invalidating on schedule addresses this problem.
        invalidatePackageInfoCache();
        if (!mHandler.hasMessages(WRITE_SETTINGS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_SETTINGS, WRITE_SETTINGS_DELAY);
        }
    }

    private void scheduleWritePackageListLocked(int userId) {
        invalidatePackageInfoCache();
        if (!mHandler.hasMessages(WRITE_PACKAGE_LIST)) {
            Message msg = mHandler.obtainMessage(WRITE_PACKAGE_LIST);
            msg.arg1 = userId;
            mHandler.sendMessageDelayed(msg, WRITE_SETTINGS_DELAY);
        }
    }

    void scheduleWritePackageRestrictions(UserHandle user) {
        final int userId = user == null ? UserHandle.USER_ALL : user.getIdentifier();
        scheduleWritePackageRestrictions(userId);
    }

    void scheduleWritePackageRestrictions(int userId) {
        invalidatePackageInfoCache();
        if (userId == UserHandle.USER_ALL) {
            synchronized (mDirtyUsers) {
                for (int aUserId : mUserManager.getUserIds()) {
                    mDirtyUsers.add(aUserId);
                }
            }
        } else {
            if (!mUserManager.exists(userId)) {
                return;
            }
            synchronized (mDirtyUsers) {
                mDirtyUsers.add(userId);
            }
        }
        if (!mBackgroundHandler.hasMessages(WRITE_DIRTY_PACKAGE_RESTRICTIONS)) {
            mBackgroundHandler.sendMessageDelayed(
                    mBackgroundHandler.obtainMessage(WRITE_DIRTY_PACKAGE_RESTRICTIONS, this),
                    WRITE_SETTINGS_DELAY);
        }
    }

    void writePendingRestrictions() {
        final Integer[] dirtyUsers;
        synchronized (mLock) {
            mBackgroundHandler.removeMessages(WRITE_DIRTY_PACKAGE_RESTRICTIONS);
            synchronized (mDirtyUsers) {
                if (mDirtyUsers.isEmpty()) {
                    return;
                }
                dirtyUsers = mDirtyUsers.toArray(Integer[]::new);
                mDirtyUsers.clear();
            }
        }
        mSettings.writePackageRestrictions(dirtyUsers);
    }

    void writeSettings(boolean sync) {
        synchronized (mLock) {
            mHandler.removeMessages(WRITE_SETTINGS);
            mBackgroundHandler.removeMessages(WRITE_DIRTY_PACKAGE_RESTRICTIONS);
            writeSettingsLPrTEMP(sync);
            synchronized (mDirtyUsers) {
                mDirtyUsers.clear();
            }
        }
    }

    void writePackageList(int userId) {
        synchronized (mLock) {
            mHandler.removeMessages(WRITE_PACKAGE_LIST);
            mSettings.writePackageListLPr(userId);
        }
    }

    private static final Handler.Callback BACKGROUND_HANDLER_CALLBACK = new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case WRITE_DIRTY_PACKAGE_RESTRICTIONS: {
                    PackageManagerService pm = (PackageManagerService) msg.obj;
                    pm.writePendingRestrictions();
                    return true;
                }
                case WRITE_USER_PACKAGE_RESTRICTIONS: {
                    final Runnable r = (Runnable) msg.obj;
                    r.run();
                    return true;
                }
            }
            return false;
        }
    };

    /** Starts PackageManagerService. */
    public static PackageManagerService main(Context context,
            Installer installer, @NonNull DomainVerificationService domainVerificationService,
            boolean factoryTest) {
        // Self-check for initial settings.
        PackageManagerServiceCompilerMapping.checkProperties();
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_PACKAGE_MANAGER);
        t.traceBegin("create package manager");
        final PackageManagerTracedLock lock = new PackageManagerTracedLock();
        final Object installLock = new Object();

        HandlerThread backgroundThread = new ServiceThread("PackageManagerBg",
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        backgroundThread.start();
        Handler backgroundHandler = new Handler(backgroundThread.getLooper(),
                BACKGROUND_HANDLER_CALLBACK);

        PackageManagerServiceInjector injector = new PackageManagerServiceInjector(
                context, lock, installer, installLock, new PackageAbiHelperImpl(),
                backgroundHandler,
                SYSTEM_PARTITIONS,
                (i, pm) -> new ComponentResolver(i.getUserManagerService(), pm.mUserNeedsBadging),
                (i, pm) -> PermissionManagerService.create(context,
                        i.getSystemConfig().getAvailableFeatures()),
                (i, pm) -> new UserManagerService(context, pm,
                        new UserDataPreparer(installer, installLock, context), lock),
                (i, pm) -> new Settings(Environment.getDataDirectory(),
                        RuntimePermissionsPersistence.createInstance(),
                        i.getPermissionManagerServiceInternal(),
                        domainVerificationService, backgroundHandler,
                        lock),
                (i, pm) -> AppsFilterImpl.create(i,
                        i.getLocalService(PackageManagerInternal.class)),
                (i, pm) -> (PlatformCompat) ServiceManager.getService("platform_compat"),
                (i, pm) -> SystemConfig.getInstance(),
                (i, pm) -> new PackageDexOptimizer(i.getInstaller(), i.getInstallLock(),
                        i.getContext(), "*dexopt*"),
                (i, pm) -> new DexManager(i.getContext(), i.getPackageDexOptimizer(),
                        i.getInstaller(), i.getInstallLock(), i.getDynamicCodeLogger()),
                (i, pm) -> new DynamicCodeLogger(i.getInstaller()),
                (i, pm) -> new ArtManagerService(i.getContext(), i.getInstaller(),
                        i.getInstallLock()),
                (i, pm) -> ApexManager.getInstance(),
                (i, pm) -> (IncrementalManager)
                        i.getContext().getSystemService(Context.INCREMENTAL_SERVICE),
                (i, pm) -> new DefaultAppProvider(() -> context.getSystemService(RoleManager.class),
                        () -> LocalServices.getService(UserManagerInternal.class)),
                (i, pm) -> new DisplayMetrics(),
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, i.getDisplayMetrics(),
                        new PackageCacher(pm.mCacheDir),
                        pm.mPackageParserCallback) /* scanningCachingPackageParserProducer */,
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, i.getDisplayMetrics(), null,
                        pm.mPackageParserCallback) /* scanningPackageParserProducer */,
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, i.getDisplayMetrics(), null,
                        pm.mPackageParserCallback) /* preparingPackageParserProducer */,
                // Prepare a supplier of package parser for the staging manager to parse apex file
                // during the staging installation.
                (i, pm) -> new PackageInstallerService(
                        i.getContext(), pm, i::getScanningPackageParser),
                (i, pm, cn) -> new InstantAppResolverConnection(
                        i.getContext(), cn, Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE),
                (i, pm) -> new ModuleInfoProvider(i.getContext()),
                (i, pm) -> LegacyPermissionManagerService.create(i.getContext()),
                (i, pm) -> domainVerificationService,
                (i, pm) -> {
                    HandlerThread thread = new ServiceThread(TAG,
                            Process.THREAD_PRIORITY_DEFAULT, true /*allowIo*/);
                    thread.start();
                    return new PackageHandler(thread.getLooper(), pm);
                },
                new DefaultSystemWrapper(),
                LocalServices::getService,
                context::getSystemService,
                (i, pm) -> {
                    if (useArtService()) {
                        return null;
                    }
                    try {
                        return new BackgroundDexOptService(i.getContext(), i.getDexManager(), pm);
                    } catch (LegacyDexoptDisabledException e) {
                        throw new RuntimeException(e);
                    }
                },
                (i, pm) -> IBackupManager.Stub.asInterface(ServiceManager.getService(
                        Context.BACKUP_SERVICE)),
                (i, pm) -> new SharedLibrariesImpl(pm, i),
                (i, pm) -> new CrossProfileIntentFilterHelper(i.getSettings(),
                        i.getUserManagerService(), i.getLock(), i.getUserManagerInternal(),
                        context),
                (i, pm) -> new UpdateOwnershipHelper(),
                (i, pm) -> new PackageMonitorCallbackHelper());

        if (Build.VERSION.SDK_INT <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        PackageManagerService m = new PackageManagerService(injector, factoryTest,
                PackagePartitions.FINGERPRINT, Build.IS_ENG, Build.IS_USERDEBUG,
                Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL);
        t.traceEnd(); // "create package manager"

        final CompatChange.ChangeListener selinuxChangeListener = packageName -> {
            synchronized (m.mInstallLock) {
                final Computer snapshot = m.snapshotComputer();
                final PackageStateInternal packageState =
                        snapshot.getPackageStateInternal(packageName);
                if (packageState == null) {
                    Slog.e(TAG, "Failed to find package setting " + packageName);
                    return;
                }
                AndroidPackage pkg = packageState.getPkg();
                SharedUserApi sharedUser = snapshot.getSharedUser(
                        packageState.getSharedUserAppId());
                String oldSeInfo = packageState.getSeInfo();

                if (pkg == null) {
                    Slog.e(TAG, "Failed to find package " + packageName);
                    return;
                }
                final String newSeInfo = SELinuxMMAC.getSeInfo(packageState, pkg, sharedUser,
                        m.mInjector.getCompatibility());

                if (!newSeInfo.equals(oldSeInfo)) {
                    Slog.i(TAG, "Updating seInfo for package " + packageName + " from: "
                            + oldSeInfo + " to: " + newSeInfo);
                    m.commitPackageStateMutation(null, packageName,
                            state -> state.setOverrideSeInfo(newSeInfo));
                    m.mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);
                }
            }
        };

        injector.getCompatibility().registerListener(SELinuxMMAC.SELINUX_LATEST_CHANGES,
                selinuxChangeListener);
        injector.getCompatibility().registerListener(SELinuxMMAC.SELINUX_R_CHANGES,
                selinuxChangeListener);

        m.installAllowlistedSystemPackages();
        IPackageManagerImpl iPackageManager = m.new IPackageManagerImpl();
        ServiceManager.addService("package", iPackageManager);
        final PackageManagerNative pmn = new PackageManagerNative(m);
        ServiceManager.addService("package_native", pmn);
        return m;
    }

    /** Install/uninstall system packages for all users based on their user-type, as applicable. */
    private void installAllowlistedSystemPackages() {
        if (mUserManager.installWhitelistedSystemPackages(isFirstBoot(), isDeviceUpgrading(),
                mExistingPackages)) {
            scheduleWritePackageRestrictions(UserHandle.USER_ALL);
            scheduleWriteSettings();
        }
    }

    // Link watchables to the class
    @SuppressWarnings("GuardedBy")
    private void registerObservers(boolean verify) {
        // Null check to handle nullable test parameters
        if (mPackages != null) {
            mPackages.registerObserver(mWatcher);
        }
        if (mSharedLibraries != null) {
            mSharedLibraries.registerObserver(mWatcher);
        }
        if (mInstrumentation != null) {
            mInstrumentation.registerObserver(mWatcher);
        }
        if (mWebInstantAppsDisabled != null) {
            mWebInstantAppsDisabled.registerObserver(mWatcher);
        }
        if (mAppsFilter != null) {
            mAppsFilter.registerObserver(mWatcher);
        }
        if (mInstantAppRegistry != null) {
            mInstantAppRegistry.registerObserver(mWatcher);
        }
        if (mSettings != null) {
            mSettings.registerObserver(mWatcher);
        }
        if (mIsolatedOwners != null) {
            mIsolatedOwners.registerObserver(mWatcher);
        }
        if (mComponentResolver != null) {
            mComponentResolver.registerObserver(mWatcher);
        }
        if (mFrozenPackages != null) {
            mFrozenPackages.registerObserver(mWatcher);
        }
        if (verify) {
            // If neither "build" attribute is true then this may be a mockito test,
            // and verification can fail as a false positive.
            Watchable.verifyWatchedAttributes(this, mWatcher, !(mIsEngBuild || mIsUserDebugBuild));
        }
    }

    /**
     * An extremely minimal constructor designed to start up a PackageManagerService instance for
     * testing.
     *
     * It is assumed that all methods under test will mock the internal fields and thus
     * none of the initialization is needed.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public PackageManagerService(@NonNull PackageManagerServiceInjector injector,
            @NonNull PackageManagerServiceTestParams testParams) {
        mInjector = injector;
        mInjector.bootstrap(this);
        mAppsFilter = injector.getAppsFilter();
        mComponentResolver = injector.getComponentResolver();
        mContext = injector.getContext();
        mInstaller = injector.getInstaller();
        mInstallLock = injector.getInstallLock();
        mLock = injector.getLock();
        mPackageStateWriteLock = mLock;
        mPermissionManager = injector.getPermissionManagerServiceInternal();
        mSettings = injector.getSettings();
        mUserManager = injector.getUserManagerService();
        mUserNeedsBadging = new UserNeedsBadgingCache(mUserManager);
        mDomainVerificationManager = injector.getDomainVerificationManagerInternal();
        mHandler = injector.getHandler();
        mBackgroundHandler = injector.getBackgroundHandler();
        mSharedLibraries = injector.getSharedLibrariesImpl();

        mApexManager = testParams.apexManager;
        mArtManagerService = testParams.artManagerService;
        mAvailableFeatures = testParams.availableFeatures;
        mBackgroundDexOptService = testParams.backgroundDexOptService;
        mDefParseFlags = testParams.defParseFlags;
        mDefaultAppProvider = testParams.defaultAppProvider;
        mLegacyPermissionManager = testParams.legacyPermissionManagerInternal;
        mDexManager = testParams.dexManager;
        mDynamicCodeLogger = testParams.dynamicCodeLogger;
        mFactoryTest = testParams.factoryTest;
        mIncrementalManager = testParams.incrementalManager;
        mInstallerService = testParams.installerService;
        mInstantAppRegistry = testParams.instantAppRegistry;
        mChangedPackagesTracker = testParams.changedPackagesTracker;
        mInstantAppResolverConnection = testParams.instantAppResolverConnection;
        mInstantAppResolverSettingsComponent = testParams.instantAppResolverSettingsComponent;
        mIsPreNMR1Upgrade = testParams.isPreNmr1Upgrade;
        mIsPreQUpgrade = testParams.isPreQupgrade;
        mPriorSdkVersion = testParams.priorSdkVersion;
        mIsUpgrade = testParams.isUpgrade;
        mMetrics = testParams.Metrics;
        mModuleInfoProvider = testParams.moduleInfoProvider;
        mMoveCallbacks = testParams.moveCallbacks;
        mOverlayConfig = testParams.overlayConfig;
        mPackageDexOptimizer = testParams.packageDexOptimizer;
        mPackageParserCallback = testParams.packageParserCallback;
        mPendingBroadcasts = testParams.pendingPackageBroadcasts;
        mTestUtilityService = testParams.testUtilityService;
        mProcessLoggingHandler = testParams.processLoggingHandler;
        mProtectedPackages = testParams.protectedPackages;
        mSeparateProcesses = testParams.separateProcesses;
        mRequiredVerifierPackages = testParams.requiredVerifierPackages;
        mRequiredInstallerPackage = testParams.requiredInstallerPackage;
        mRequiredUninstallerPackage = testParams.requiredUninstallerPackage;
        mRequiredPermissionControllerPackage = testParams.requiredPermissionControllerPackage;
        mSetupWizardPackage = testParams.setupWizardPackage;
        mStorageManagerPackage = testParams.storageManagerPackage;
        mDefaultTextClassifierPackage = testParams.defaultTextClassifierPackage;
        mSystemTextClassifierPackageName = testParams.systemTextClassifierPackage;
        mRetailDemoPackage = testParams.retailDemoPackage;
        mRecentsPackage = testParams.recentsPackage;
        mAmbientContextDetectionPackage = testParams.ambientContextDetectionPackage;
        mWearableSensingPackage = testParams.wearableSensingPackage;
        mConfiguratorPackage = testParams.configuratorPackage;
        mAppPredictionServicePackage = testParams.appPredictionServicePackage;
        mIncidentReportApproverPackage = testParams.incidentReportApproverPackage;
        mServicesExtensionPackageName = testParams.servicesExtensionPackageName;
        mSharedSystemSharedLibraryPackageName = testParams.sharedSystemSharedLibraryPackageName;
        mOverlayConfigSignaturePackage = testParams.overlayConfigSignaturePackage;
        mResolveComponentName = testParams.resolveComponentName;
        mRequiredSdkSandboxPackage = testParams.requiredSdkSandboxPackage;
        mInitialNonStoppedSystemPackages = testParams.initialNonStoppedSystemPackages;
        mShouldStopSystemPackagesByDefault = testParams.shouldStopSystemPackagesByDefault;

        mLiveComputer = createLiveComputer();
        mSnapshotStatistics = null;

        mPackages.putAll(testParams.packages);
        mFreeStorageHelper = testParams.freeStorageHelper;
        mSdkVersion = testParams.sdkVersion;
        mAppInstallDir = testParams.appInstallDir;
        mIsEngBuild = testParams.isEngBuild;
        mIsUserDebugBuild = testParams.isUserDebugBuild;
        mIncrementalVersion = testParams.incrementalVersion;
        mDomainVerificationConnection = new DomainVerificationConnection(this);

        mBroadcastHelper = testParams.broadcastHelper;
        mAppDataHelper = testParams.appDataHelper;
        mInstallPackageHelper = testParams.installPackageHelper;
        mRemovePackageHelper = testParams.removePackageHelper;
        mInitAppsHelper = testParams.initAndSystemPackageHelper;
        mDeletePackageHelper = testParams.deletePackageHelper;
        mPreferredActivityHelper = testParams.preferredActivityHelper;
        mResolveIntentHelper = testParams.resolveIntentHelper;
        mDexOptHelper = testParams.dexOptHelper;
        mSuspendPackageHelper = testParams.suspendPackageHelper;
        mDistractingPackageHelper = testParams.distractingPackageHelper;

        mSharedLibraries.setDeletePackageHelper(mDeletePackageHelper);

        mStorageEventHelper = testParams.storageEventHelper;
        mPackageMonitorCallbackHelper = testParams.packageMonitorCallbackHelper;

        registerObservers(false);
        invalidatePackageInfoCache();
    }

    public PackageManagerService(PackageManagerServiceInjector injector, boolean factoryTest,
            final String partitionsFingerprint, final boolean isEngBuild,
            final boolean isUserDebugBuild, final int sdkVersion, final String incrementalVersion) {
        mIsEngBuild = isEngBuild;
        mIsUserDebugBuild = isUserDebugBuild;
        mSdkVersion = sdkVersion;
        mIncrementalVersion = incrementalVersion;
        mInjector = injector;
        mInjector.getSystemWrapper().disablePackageCaches();

        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_PACKAGE_MANAGER);
        mPendingBroadcasts = new PendingPackageBroadcasts();

        mInjector.bootstrap(this);
        mLock = injector.getLock();
        mPackageStateWriteLock = mLock;
        mInstallLock = injector.getInstallLock();
        LockGuard.installLock(mLock, LockGuard.INDEX_PACKAGES);
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        mContext = injector.getContext();
        mFactoryTest = factoryTest;
        mMetrics = injector.getDisplayMetrics();
        mInstaller = injector.getInstaller();
        mFreeStorageHelper = new FreeStorageHelper(this);

        // Create sub-components that provide services / data. Order here is important.
        t.traceBegin("createSubComponents");

        // Expose private service for system components to use.
        LocalServices.addService(PackageManagerInternal.class, new PackageManagerInternalImpl());
        LocalManagerRegistry.addManager(PackageManagerLocal.class,
                new PackageManagerLocalImpl(this));
        LocalServices.addService(TestUtilityService.class, this);
        mTestUtilityService = LocalServices.getService(TestUtilityService.class);
        mUserManager = injector.getUserManagerService();
        mUserNeedsBadging = new UserNeedsBadgingCache(mUserManager);
        mComponentResolver = injector.getComponentResolver();
        mPermissionManager = injector.getPermissionManagerServiceInternal();
        mSettings = injector.getSettings();
        mIncrementalManager = mInjector.getIncrementalManager();
        mDefaultAppProvider = mInjector.getDefaultAppProvider();
        mLegacyPermissionManager = mInjector.getLegacyPermissionManagerInternal();
        PlatformCompat platformCompat = mInjector.getCompatibility();
        mPackageParserCallback = new PackageParser2.Callback() {
            @Override
            public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
                return platformCompat.isChangeEnabled(changeId, appInfo);
            }

            @Override
            public boolean hasFeature(String feature) {
                return PackageManagerService.this.hasSystemFeature(feature, 0);
            }

            @Override
            public Set<String> getHiddenApiWhitelistedApps() {
                return SystemConfig.getInstance().getHiddenApiWhitelistedApps();
            }

            @Override
            public Set<String> getInstallConstraintsAllowlist() {
                return SystemConfig.getInstance().getInstallConstraintsAllowlist();
            }
        };

        // CHECKSTYLE:ON IndentationCheck
        t.traceEnd();

        t.traceBegin("addSharedUsers");
        mSettings.addSharedUserLPw("android.uid.system", Process.SYSTEM_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.phone", RADIO_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.log", LOG_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.nfc", NFC_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.bluetooth", BLUETOOTH_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.shell", SHELL_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.se", SE_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.networkstack", NETWORKSTACK_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        mSettings.addSharedUserLPw("android.uid.uwb", UWB_UID,
                ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
        t.traceEnd();

        String separateProcesses = SystemProperties.get("debug.separate_processes");

        if (separateProcesses != null && separateProcesses.length() > 0) {
            if ("*".equals(separateProcesses)) {
                mDefParseFlags = ParsingPackageUtils.PARSE_IGNORE_PROCESSES;
                mSeparateProcesses = null;
                Slog.w(TAG, "Running with debug.separate_processes: * (ALL)");
            } else {
                mDefParseFlags = 0;
                mSeparateProcesses = separateProcesses.split(",");
                Slog.w(TAG, "Running with debug.separate_processes: "
                        + separateProcesses);
            }
        } else {
            mDefParseFlags = 0;
            mSeparateProcesses = null;
        }

        mPackageDexOptimizer = injector.getPackageDexOptimizer();
        mDexManager = injector.getDexManager();
        mDynamicCodeLogger = injector.getDynamicCodeLogger();
        mBackgroundDexOptService = injector.getBackgroundDexOptService();
        mArtManagerService = injector.getArtManagerService();
        mMoveCallbacks = new MovePackageHelper.MoveCallbacks(FgThread.get().getLooper());
        mSharedLibraries = mInjector.getSharedLibrariesImpl();
        mBackgroundHandler = injector.getBackgroundHandler();

        mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getMetrics(mMetrics);

        t.traceBegin("get system config");
        SystemConfig systemConfig = injector.getSystemConfig();
        mAvailableFeatures = systemConfig.getAvailableFeatures();
        t.traceEnd();

        mProtectedPackages = new ProtectedPackages(mContext);

        mApexManager = injector.getApexManager();
        mAppsFilter = mInjector.getAppsFilter();

        mChangedPackagesTracker = new ChangedPackagesTracker();

        mAppInstallDir = new File(Environment.getDataDirectory(), "app");

        mDomainVerificationConnection = new DomainVerificationConnection(this);
        mDomainVerificationManager = injector.getDomainVerificationManagerInternal();
        mDomainVerificationManager.setConnection(mDomainVerificationConnection);

        mBroadcastHelper = new BroadcastHelper(mInjector);
        mPackageMonitorCallbackHelper = injector.getPackageMonitorCallbackHelper();
        mAppDataHelper = new AppDataHelper(this);
        mRemovePackageHelper = new RemovePackageHelper(this, mAppDataHelper, mBroadcastHelper);
        mDeletePackageHelper = new DeletePackageHelper(this, mRemovePackageHelper,
                mBroadcastHelper);
        mInstallPackageHelper = new InstallPackageHelper(this, mAppDataHelper, mRemovePackageHelper,
                mDeletePackageHelper, mBroadcastHelper);

        mInstantAppRegistry = new InstantAppRegistry(mContext, mPermissionManager,
                mInjector.getUserManagerInternal(), mDeletePackageHelper);

        mSharedLibraries.setDeletePackageHelper(mDeletePackageHelper);
        mPreferredActivityHelper = new PreferredActivityHelper(this, mBroadcastHelper);
        mResolveIntentHelper = new ResolveIntentHelper(mContext, mPreferredActivityHelper,
                injector.getCompatibility(), mUserManager, mDomainVerificationManager,
                mUserNeedsBadging, () -> mResolveInfo, () -> mInstantAppInstallerActivity,
                injector.getBackgroundHandler());
        mDexOptHelper = new DexOptHelper(this);
        mSuspendPackageHelper = new SuspendPackageHelper(this, mInjector, mBroadcastHelper,
                mProtectedPackages);
        mDistractingPackageHelper = new DistractingPackageHelper(this, mBroadcastHelper,
                mSuspendPackageHelper);
        mStorageEventHelper = new StorageEventHelper(this, mDeletePackageHelper,
                mRemovePackageHelper);

        synchronized (mLock) {
            // Create the computer as soon as the state objects have been installed.  The
            // cached computer is the same as the live computer until the end of the
            // constructor, at which time the invalidation method updates it.
            mSnapshotStatistics = new SnapshotStatistics();
            sSnapshotPendingVersion.incrementAndGet();
            mLiveComputer = createLiveComputer();
            registerObservers(true);
        }

        Computer computer = mLiveComputer;
        // CHECKSTYLE:OFF IndentationCheck
        synchronized (mInstallLock) {
        // writer
        synchronized (mLock) {
            mHandler = injector.getHandler();
            mProcessLoggingHandler = new ProcessLoggingHandler();
            Watchdog.getInstance().addThread(mHandler, WATCHDOG_TIMEOUT);

            ArrayMap<String, SystemConfig.SharedLibraryEntry> libConfig
                    = systemConfig.getSharedLibraries();
            final int builtInLibCount = libConfig.size();
            for (int i = 0; i < builtInLibCount; i++) {
                mSharedLibraries.addBuiltInSharedLibraryLPw(libConfig.valueAt(i));
            }

            // Now that we have added all the libraries, iterate again to add dependency
            // information IFF their dependencies are added.
            long undefinedVersion = SharedLibraryInfo.VERSION_UNDEFINED;
            for (int i = 0; i < builtInLibCount; i++) {
                String name = libConfig.keyAt(i);
                SystemConfig.SharedLibraryEntry entry = libConfig.valueAt(i);
                final int dependencyCount = entry.dependencies.length;
                for (int j = 0; j < dependencyCount; j++) {
                    final SharedLibraryInfo dependency =
                        computer.getSharedLibraryInfo(entry.dependencies[j], undefinedVersion);
                    if (dependency != null) {
                        computer.getSharedLibraryInfo(name, undefinedVersion)
                                .addDependency(dependency);
                    }
                }
            }

            SELinuxMMAC.readInstallPolicy();

            t.traceBegin("loadFallbacks");
            FallbackCategoryProvider.loadFallbacks();
            t.traceEnd();

            t.traceBegin("read user settings");
            mFirstBoot = !mSettings.readLPw(computer,
                    mInjector.getUserManagerInternal().getUsers(
                    /* excludePartial= */ true,
                    /* excludeDying= */ false,
                    /* excludePreCreated= */ false));
            t.traceEnd();

            if (mFirstBoot) {
                t.traceBegin("setFirstBoot: ");
                try {
                    mInstaller.setFirstBoot();
                } catch (InstallerException e) {
                    Slog.w(TAG, "Could not set First Boot: ", e);
                }
                t.traceEnd();
            }

            mPermissionManager.readLegacyPermissionsTEMP(mSettings.mPermissions);
            mPermissionManager.readLegacyPermissionStateTEMP();

            if (mFirstBoot) {
                DexOptHelper.requestCopyPreoptedFiles();
            }

            String customResolverActivityName = Resources.getSystem().getString(
                    R.string.config_customResolverActivity);
            if (!TextUtils.isEmpty(customResolverActivityName)) {
                mCustomResolverComponentName = ComponentName.unflattenFromString(
                        customResolverActivityName);
            }

            long startTime = SystemClock.uptimeMillis();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);

            final String bootClassPath = System.getenv("BOOTCLASSPATH");
            final String systemServerClassPath = System.getenv("SYSTEMSERVERCLASSPATH");

            if (bootClassPath == null) {
                Slog.w(TAG, "No BOOTCLASSPATH found!");
            }

            if (systemServerClassPath == null) {
                Slog.w(TAG, "No SYSTEMSERVERCLASSPATH found!");
            }

            final VersionInfo ver = mSettings.getInternalVersion();
            mIsUpgrade =
                    !partitionsFingerprint.equals(ver.fingerprint);
            if (mIsUpgrade) {
                PackageManagerServiceUtils.logCriticalInfo(Log.INFO,
                        "Upgrading from " + ver.fingerprint + " (" + ver.buildFingerprint + ") to "
                                + PackagePartitions.FINGERPRINT + " (" + Build.FINGERPRINT + ")");
            }
            mPriorSdkVersion = mIsUpgrade ? ver.sdkVersion : -1;
            mInitAppsHelper = new InitAppsHelper(this, mApexManager, mInstallPackageHelper,
                    mInjector.getSystemPartitions());

            // when upgrading from pre-M, promote system app permissions from install to runtime
            mPromoteSystemApps =
                    mIsUpgrade && ver.sdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1;

            mIsPreNMR1Upgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.N_MR1;
            mIsPreQUpgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.Q;

            final WatchedArrayMap<String, PackageSetting> packageSettings =
                mSettings.getPackagesLocked();

            if (isDeviceUpgrading()) {
                // Save the names of pre-existing packages prior to scanning, so we can determine
                // which system packages are completely new due to an upgrade.
                mExistingPackages = new ArraySet<>(packageSettings.size());
                for (int i = 0; i < packageSettings.size(); i++) {
                    mExistingPackages.add(packageSettings.valueAt(i).getPackageName());
                }

                // Triggering {@link com.android.server.pm.crossprofile.
                // CrossProfileIntentFilterHelper.updateDefaultCrossProfileIntentFilter} to update
                // {@link  CrossProfileIntentFilter}s between eligible users and their parent
                t.traceBegin("cross profile intent filter update");
                mInjector.getCrossProfileIntentFilterHelper()
                        .updateDefaultCrossProfileIntentFilter();
                t.traceEnd();
            }

            mCacheDir = PackageManagerServiceUtils.preparePackageParserCache(
                    mIsEngBuild, mIsUserDebugBuild, mIncrementalVersion);

            mInitialNonStoppedSystemPackages = mInjector.getSystemConfig()
                    .getInitialNonStoppedSystemPackages();
            mShouldStopSystemPackagesByDefault = mContext.getResources()
                    .getBoolean(R.bool.config_stopSystemPackagesByDefault);

            final int[] userIds = mUserManager.getUserIds();
            PackageParser2 packageParser = mInjector.getScanningCachingPackageParser();
            mOverlayConfig = mInitAppsHelper.initSystemApps(packageParser, packageSettings, userIds,
                    startTime);
            mInitAppsHelper.initNonSystemApps(packageParser, userIds, startTime);
            packageParser.close();

            mRequiredVerifierPackages = getRequiredButNotReallyRequiredVerifiersLPr(computer);
            mRequiredInstallerPackage = getRequiredInstallerLPr(computer);
            mRequiredUninstallerPackage = getRequiredUninstallerLPr(computer);

            // PermissionController hosts default permission granting and role management, so it's a
            // critical part of the core system.
            mRequiredPermissionControllerPackage = getRequiredPermissionControllerLPr(computer);

            // Resolve the storage manager.
            mStorageManagerPackage = getStorageManagerPackageName(computer);

            // Resolve protected action filters. Only the setup wizard is allowed to
            // have a high priority filter for these actions.
            mSetupWizardPackage = getSetupWizardPackageNameImpl(computer);
            mComponentResolver.fixProtectedFilterPriorities(mSetupWizardPackage);

            mDefaultTextClassifierPackage = ensureSystemPackageName(computer,
                    mContext.getString(R.string.config_servicesExtensionPackage));
            mSystemTextClassifierPackageName = ensureSystemPackageName(computer,
                    mContext.getString(R.string.config_defaultTextClassifierPackage));
            mConfiguratorPackage = ensureSystemPackageName(computer,
                    mContext.getString(R.string.config_deviceConfiguratorPackageName));
            mAppPredictionServicePackage = ensureSystemPackageName(computer,
                    getPackageFromComponentString(R.string.config_defaultAppPredictionService));
            mIncidentReportApproverPackage = ensureSystemPackageName(computer,
                    mContext.getString(R.string.config_incidentReportApproverPackage));
            mRetailDemoPackage = getRetailDemoPackageName();
            mOverlayConfigSignaturePackage = ensureSystemPackageName(computer,
                    mInjector.getSystemConfig().getOverlayConfigSignaturePackage());
            mRecentsPackage = ensureSystemPackageName(computer,
                    getPackageFromComponentString(R.string.config_recentsComponentName));
            mAmbientContextDetectionPackage = ensureSystemPackageName(computer,
                    getPackageFromComponentString(
                            R.string.config_defaultAmbientContextDetectionService));
            mWearableSensingPackage = ensureSystemPackageName(computer,
                    getPackageFromComponentString(
                            R.string.config_defaultWearableSensingService));

            // Now that we know all of the shared libraries, update all clients to have
            // the correct library paths.
            mSharedLibraries.updateAllSharedLibrariesLPw(
                    null, null, Collections.unmodifiableMap(mPackages));

            for (SharedUserSetting setting : mSettings.getAllSharedUsersLPw()) {
                // NOTE: We ignore potential failures here during a system scan (like
                // the rest of the commands above) because there's precious little we
                // can do about it. A settings error is reported, though.
                final List<String> changedAbiCodePath =
                        ScanPackageUtils.applyAdjustedAbiToSharedUser(setting,
                                null /*scannedPackage*/,
                                mInjector.getAbiHelper().getAdjustedAbiForSharedUser(
                                        setting.getPackageStates(), null /*scannedPackage*/));
                if (!useArtService() && // Skip for ART Service since it has its own dex file GC.
                        changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
                    for (int i = changedAbiCodePath.size() - 1; i >= 0; --i) {
                        final String codePathString = changedAbiCodePath.get(i);
                        try {
                            mInstaller.rmdex(codePathString,
                                    getDexCodeInstructionSet(getPreferredInstructionSet()));
                        } catch (LegacyDexoptDisabledException e) {
                            throw new RuntimeException(e);
                        } catch (InstallerException ignored) {
                        }
                    }
                }
                // Adjust seInfo to ensure apps which share a sharedUserId are placed in the same
                // SELinux domain.
                setting.fixSeInfoLocked();
                setting.updateProcesses();
            }

            // Now that we know all the packages we are keeping,
            // read and update their last usage times.
            mPackageUsage.read(packageSettings);
            mCompilerStats.read();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SCAN_END,
                    SystemClock.uptimeMillis());
            Slog.i(TAG, "Time to scan packages: "
                    + ((SystemClock.uptimeMillis() - startTime) / 1000f)
                    + " seconds");

            // If the partitions fingerprint has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear.  This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow...  it would be nice to have some better way to handle
            // this situation.
            if (mIsUpgrade) {
                Slog.i(TAG, "Partitions fingerprint changed from " + ver.fingerprint + " to "
                        + PackagePartitions.FINGERPRINT
                        + "; regranting permissions for internal storage");
            }
            mPermissionManager.onStorageVolumeMounted(
                    StorageManager.UUID_PRIVATE_INTERNAL, mIsUpgrade);
            ver.sdkVersion = mSdkVersion;

            // If this is the first boot or an update from pre-M, then we need to initialize the
            // default preferred apps across all defined users.
            if (mPromoteSystemApps || mFirstBoot) {
                final List<UserInfo> users = mInjector.getUserManagerInternal().getUsers(true);
                for (int i = 0; i < users.size(); i++) {
                    mSettings.applyDefaultPreferredAppsLPw(users.get(i).id);

                }
            }

            // If this is first boot after an OTA, then we need to clear code cache directories.
            // Note that we do *not* clear the application profiles. These remain valid
            // across OTAs and are used to drive profile verification (post OTA) and
            // profile compilation (without waiting to collect a fresh set of profiles).
            if (mIsUpgrade) {
                Slog.i(TAG, "Build fingerprint changed; clearing code caches");
                for (int i = 0; i < packageSettings.size(); i++) {
                    final PackageSetting ps = packageSettings.valueAt(i);
                    if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, ps.getVolumeUuid())) {
                        // No apps are running this early, so no need to freeze
                        mAppDataHelper.clearAppDataLIF(ps.getPkg(), UserHandle.USER_ALL,
                                FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                                        | Installer.FLAG_CLEAR_CODE_CACHE_ONLY
                                        | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES);
                    }
                }
                ver.buildFingerprint = Build.FINGERPRINT;
                ver.fingerprint = PackagePartitions.FINGERPRINT;
            }

            // Defer the app data fixup until we are done with app data clearing above.
            mPrepareAppDataFuture = mAppDataHelper.fixAppsDataOnBoot();

            // Legacy existing (installed before Q) non-system apps to hide
            // their icons in launcher.
            if (mIsPreQUpgrade) {
                Slog.i(TAG, "Allowlisting all existing apps to hide their icons");
                int size = packageSettings.size();
                for (int i = 0; i < size; i++) {
                    final PackageSetting ps = packageSettings.valueAt(i);
                    if ((ps.getFlags() & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        continue;
                    }
                    ps.disableComponentLPw(PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME,
                            UserHandle.USER_SYSTEM);
                }
            }

            // clear only after permissions and other defaults have been updated
            mPromoteSystemApps = false;

            // All the changes are done during package scanning.
            ver.databaseVersion = Settings.CURRENT_DATABASE_VERSION;

            // can downgrade to reader
            t.traceBegin("write settings");
            writeSettingsLPrTEMP();
            t.traceEnd();
            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_READY,
                    SystemClock.uptimeMillis());

            ComponentName intentFilterVerifierComponent =
                    getIntentFilterVerifierComponentNameLPr(computer);
            ComponentName domainVerificationAgent =
                    getDomainVerificationAgentComponentNameLPr(computer);

            DomainVerificationProxy domainVerificationProxy = DomainVerificationProxy.makeProxy(
                    intentFilterVerifierComponent, domainVerificationAgent, mContext,
                    mDomainVerificationManager, mDomainVerificationManager.getCollector(),
                    mDomainVerificationConnection);

            mDomainVerificationManager.setProxy(domainVerificationProxy);

            mServicesExtensionPackageName = getRequiredServicesExtensionPackageLPr(computer);
            mSharedSystemSharedLibraryPackageName = getRequiredSharedLibrary(computer,
                    PackageManager.SYSTEM_SHARED_LIBRARY_SHARED,
                    SharedLibraryInfo.VERSION_UNDEFINED);

            mSettings.setPermissionControllerVersion(
                    computer.getPackageInfo(mRequiredPermissionControllerPackage, 0,
                            UserHandle.USER_SYSTEM).getLongVersionCode());

            // Resolve the sdk sandbox package
            mRequiredSdkSandboxPackage = getRequiredSdkSandboxPackageName(computer);

            // Initialize InstantAppRegistry's Instant App list for all users.
            forEachPackageState(computer, packageState -> {
                var pkg = packageState.getAndroidPackage();
                if (pkg == null || packageState.isSystem()) {
                    return;
                }
                for (int userId : userIds) {
                    if (!packageState.getUserStateOrDefault(userId).isInstantApp()
                            || !packageState.getUserStateOrDefault(userId).isInstalled()) {
                        continue;
                    }
                    mInstantAppRegistry.addInstantApp(userId, packageState.getAppId());
                }
            });

            mInstallerService = mInjector.getPackageInstallerService();
            final ComponentName instantAppResolverComponent = getInstantAppResolver(computer);
            if (instantAppResolverComponent != null) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Set ephemeral resolver: " + instantAppResolverComponent);
                }
                mInstantAppResolverConnection =
                        mInjector.getInstantAppResolverConnection(instantAppResolverComponent);
                mInstantAppResolverSettingsComponent =
                        getInstantAppResolverSettingsLPr(computer,
                                instantAppResolverComponent);
            } else {
                mInstantAppResolverConnection = null;
                mInstantAppResolverSettingsComponent = null;
            }
            updateInstantAppInstallerLocked(null);

            // Read and update the usage of dex files.
            // Do this at the end of PM init so that all the packages have their
            // data directory reconciled.
            // At this point we know the code paths of the packages, so we can validate
            // the disk file and build the internal cache.
            // The usage file is expected to be small so loading and verifying it
            // should take a fairly small time compare to the other activities (e.g. package
            // scanning).
            final Map<Integer, List<PackageInfo>> userPackages = new HashMap<>();
            for (int userId : userIds) {
                userPackages.put(userId, computer.getInstalledPackages(/*flags*/ 0, userId)
                        .getList());
            }
            mDexManager.load(userPackages);
            mDynamicCodeLogger.load(userPackages);
            if (mIsUpgrade) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                        BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_INIT_TIME,
                        SystemClock.uptimeMillis() - startTime);
            }

            // If this is first boot or first boot after OTA then set the file path to the app
            // metadata files for preloaded packages.
            if (mFirstBoot || isDeviceUpgrading()) {
                ArrayMap<String, String> paths = systemConfig.getAppMetadataFilePaths();
                for (Map.Entry<String, String> entry : paths.entrySet()) {
                    String pkgName = entry.getKey();
                    String path = entry.getValue();
                    File file = new File(path);
                    if (!file.exists()) {
                        path = null;
                    }
                    PackageSetting disabledPkgSetting = mSettings.getDisabledSystemPkgLPr(pkgName);
                    if (disabledPkgSetting == null) {
                        PackageSetting pkgSetting = mSettings.getPackageLPr(pkgName);
                        if (pkgSetting != null) {
                            pkgSetting.setAppMetadataFilePath(path);
                        } else {
                            Slog.w(TAG, "Cannot set app metadata file for nonexistent package "
                                    + pkgName);
                        }
                    } else {
                        disabledPkgSetting.setAppMetadataFilePath(path);
                    }
                }
            }

            // Rebuild the live computer since some attributes have been rebuilt.
            mLiveComputer = createLiveComputer();

        } // synchronized (mLock)
        } // synchronized (mInstallLock)
        // CHECKSTYLE:ON IndentationCheck

        mModuleInfoProvider = mInjector.getModuleInfoProvider();

        mInjector.getSystemWrapper().enablePackageCaches();

        // The initial scanning above does many calls into installd while
        // holding the mPackages lock, but we're mostly interested in yelling
        // once we have a booted system.
        mInstaller.setWarnIfHeld(mLock);

        ParsingPackageUtils.readConfigUseRoundIcon(mContext.getResources());

        mServiceStartWithDelay = SystemClock.uptimeMillis() + (60 * 1000L);

        Slog.i(TAG, "Fix for b/169414761 is applied");
    }

    @GuardedBy("mLock")
    void updateInstantAppInstallerLocked(String modifiedPackage) {
        // we're only interested in updating the installer application when 1) it's not
        // already set or 2) the modified package is the installer
        if (mInstantAppInstallerActivity != null
                && !mInstantAppInstallerActivity.getComponentName().getPackageName()
                        .equals(modifiedPackage)) {
            return;
        }
        setUpInstantAppInstallerActivityLP(getInstantAppInstallerLPr());
    }

    public boolean isFirstBoot() {
        // allow instant applications
        return mFirstBoot;
    }

    public boolean isDeviceUpgrading() {
        // allow instant applications
        // The system property allows testing ota flow when upgraded to the same image.
        return mIsUpgrade || SystemProperties.getBoolean(
                "persist.pm.mock-upgrade", false /* default */);
    }

    @NonNull
    private String[] getRequiredButNotReallyRequiredVerifiersLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);

        final List<ResolveInfo> matches =
                mResolveIntentHelper.queryIntentReceiversInternal(computer, intent,
                        PACKAGE_MIME_TYPE,
                        MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.USER_SYSTEM, Binder.getCallingUid());
        final int size = matches.size();
        if (size == 0) {
            Log.w(TAG, "There should probably be a verifier, but, none were found");
            return EmptyArray.STRING;
        } else if (size <= REQUIRED_VERIFIERS_MAX_COUNT) {
            String[] verifiers = new String[size];
            for (int i = 0; i < size; ++i) {
                verifiers[i] = matches.get(i).getComponentInfo().packageName;
                if (TextUtils.isEmpty(verifiers[i])) {
                    throw new RuntimeException("Invalid verifier: " + matches);
                }
            }
            return verifiers;
        }
        throw new RuntimeException(
                "There must be no more than " + REQUIRED_VERIFIERS_MAX_COUNT + " verifiers; found "
                        + matches);
    }

    @NonNull
    private String getRequiredSharedLibrary(@NonNull Computer snapshot, @NonNull String name,
            int version) {
        SharedLibraryInfo libraryInfo = snapshot.getSharedLibraryInfo(name, version);
        if (libraryInfo == null) {
            throw new IllegalStateException("Missing required shared library:" + name);
        }
        String packageName = libraryInfo.getPackageName();
        if (packageName == null) {
            throw new IllegalStateException("Expected a package for shared library " + name);
        }
        return packageName;
    }

    @NonNull
    private String getRequiredServicesExtensionPackageLPr(@NonNull Computer computer) {
        String configServicesExtensionPackage = mContext.getString(
                R.string.config_servicesExtensionPackage);
        if (TextUtils.isEmpty(configServicesExtensionPackage)) {
            throw new RuntimeException(
                    "Required services extension package failed due to "
                            + "config_servicesExtensionPackage is empty.");
        }
        String servicesExtensionPackage = ensureSystemPackageName(computer,
                configServicesExtensionPackage);
        if (TextUtils.isEmpty(servicesExtensionPackage)) {
            throw new RuntimeException(
                    "Required services extension package is missing, "
                            + "config_servicesExtensionPackage had defined with "
                            + configServicesExtensionPackage
                            + ", but can not find the package info on the system image, check if "
                            + "the package has a problem.");
        }
        return servicesExtensionPackage;
    }

    private @NonNull String getRequiredInstallerLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.parse("content://com.example/foo.apk"), PACKAGE_MIME_TYPE);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent,
                PACKAGE_MIME_TYPE,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM);
        if (matches.size() == 1) {
            ResolveInfo resolveInfo = matches.get(0);
            if (!resolveInfo.activityInfo.applicationInfo.isPrivilegedApp()) {
                throw new RuntimeException("The installer must be a privileged app");
            }
            return matches.get(0).getComponentInfo().packageName;
        } else {
            throw new RuntimeException("There must be exactly one installer; found " + matches);
        }
    }

    private @NonNull String getRequiredUninstallerLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.fromParts(PACKAGE_SCHEME, "foo.bar", null));

        final ResolveInfo resolveInfo = mResolveIntentHelper.resolveIntentInternal(computer, intent,
                null, MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                0 /*privateResolveFlags*/, UserHandle.USER_SYSTEM, false, Binder.getCallingUid());
        if (resolveInfo == null ||
                mResolveActivity.name.equals(resolveInfo.getComponentInfo().name)) {
            throw new RuntimeException("There must be exactly one uninstaller; found "
                    + resolveInfo);
        }
        return resolveInfo.getComponentInfo().packageName;
    }

    private @NonNull String getRequiredPermissionControllerLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_MANAGE_PERMISSIONS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM);
        if (matches.size() == 1) {
            ResolveInfo resolveInfo = matches.get(0);
            if (!resolveInfo.activityInfo.applicationInfo.isPrivilegedApp()) {
                throw new RuntimeException("The permissions manager must be a privileged app");
            }
            return matches.get(0).getComponentInfo().packageName;
        } else {
            throw new RuntimeException("There must be exactly one permissions manager; found "
                    + matches);
        }
    }

    @NonNull
    private ComponentName getIntentFilterVerifierComponentNameLPr(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION);

        final List<ResolveInfo> matches =
                mResolveIntentHelper.queryIntentReceiversInternal(computer, intent,
                        PACKAGE_MIME_TYPE,
                        MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.USER_SYSTEM, Binder.getCallingUid());
        ResolveInfo best = null;
        final int N = matches.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo cur = matches.get(i);
            final String packageName = cur.getComponentInfo().packageName;
            if (checkPermission(
                    android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT, packageName,
                    UserHandle.USER_SYSTEM) != PackageManager.PERMISSION_GRANTED) {
                continue;
            }

            if (best == null || cur.priority > best.priority) {
                best = cur;
            }
        }

        if (best != null) {
            return best.getComponentInfo().getComponentName();
        }
        Slog.w(TAG, "Intent filter verifier not found");
        return null;
    }

    @Nullable
    private ComponentName getDomainVerificationAgentComponentNameLPr(@NonNull Computer computer) {
        Intent intent = new Intent(Intent.ACTION_DOMAINS_NEED_VERIFICATION);
        List<ResolveInfo> matches =
                mResolveIntentHelper.queryIntentReceiversInternal(computer, intent, null,
                        MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                        UserHandle.USER_SYSTEM, Binder.getCallingUid());
        ResolveInfo best = null;
        final int N = matches.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo cur = matches.get(i);
            final String packageName = cur.getComponentInfo().packageName;
            if (checkPermission(
                    android.Manifest.permission.DOMAIN_VERIFICATION_AGENT, packageName,
                    UserHandle.USER_SYSTEM) != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Domain verification agent found but does not hold permission: "
                        + packageName);
                continue;
            }

            if (best == null || cur.priority > best.priority) {
                if (computer.isComponentEffectivelyEnabled(cur.getComponentInfo(),
                        UserHandle.SYSTEM)) {
                    best = cur;
                } else {
                    Slog.w(TAG, "Domain verification agent found but not enabled");
                }
            }
        }

        if (best != null) {
            return best.getComponentInfo().getComponentName();
        }
        Slog.w(TAG, "Domain verification agent not found");
        return null;
    }

    @Nullable ComponentName getInstantAppResolver(@NonNull Computer snapshot) {
        final String[] packageArray =
                mContext.getResources().getStringArray(R.array.config_ephemeralResolverPackage);
        if (packageArray.length == 0 && !Build.IS_DEBUGGABLE) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Ephemeral resolver NOT found; empty package list");
            }
            return null;
        }

        final int callingUid = Binder.getCallingUid();
        final int resolveFlags =
                MATCH_DIRECT_BOOT_AWARE
                | MATCH_DIRECT_BOOT_UNAWARE
                | (!Build.IS_DEBUGGABLE ? MATCH_SYSTEM_ONLY : 0);
        final Intent resolverIntent = new Intent(Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE);
        List<ResolveInfo> resolvers = snapshot.queryIntentServicesInternal(resolverIntent, null,
                resolveFlags, UserHandle.USER_SYSTEM, callingUid, false /*includeInstantApps*/);
        final int N = resolvers.size();
        if (N == 0) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Ephemeral resolver NOT found; no matching intent filters");
            }
            return null;
        }

        final Set<String> possiblePackages = new ArraySet<>(Arrays.asList(packageArray));
        for (int i = 0; i < N; i++) {
            final ResolveInfo info = resolvers.get(i);

            if (info.serviceInfo == null) {
                continue;
            }

            final String packageName = info.serviceInfo.packageName;
            if (!possiblePackages.contains(packageName) && !Build.IS_DEBUGGABLE) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Ephemeral resolver not in allowed package list;"
                            + " pkg: " + packageName + ", info:" + info);
                }
                continue;
            }

            if (DEBUG_INSTANT) {
                Slog.v(TAG, "Ephemeral resolver found;"
                        + " pkg: " + packageName + ", info:" + info);
            }
            return new ComponentName(packageName, info.serviceInfo.name);
        }
        if (DEBUG_INSTANT) {
            Slog.v(TAG, "Ephemeral resolver NOT found");
        }
        return null;
    }

    @GuardedBy("mLock")
    private @Nullable ActivityInfo getInstantAppInstallerLPr() {
        String[] orderedActions = mIsEngBuild
                ? new String[]{
                        Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE + "_TEST",
                        Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE}
                : new String[]{
                        Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE};

        final int resolveFlags =
                MATCH_DIRECT_BOOT_AWARE
                        | MATCH_DIRECT_BOOT_UNAWARE
                        | Intent.FLAG_IGNORE_EPHEMERAL
                        | (mIsEngBuild ? 0 : MATCH_SYSTEM_ONLY);
        final Computer computer = snapshotComputer();
        final Intent intent = new Intent();
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.fromFile(new File("foo.apk")), PACKAGE_MIME_TYPE);
        List<ResolveInfo> matches = null;
        for (String action : orderedActions) {
            intent.setAction(action);
            matches = computer.queryIntentActivitiesInternal(intent, PACKAGE_MIME_TYPE,
                    resolveFlags, UserHandle.USER_SYSTEM);
            if (matches.isEmpty()) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Instant App installer not found with " + action);
                }
            } else {
                break;
            }
        }
        Iterator<ResolveInfo> iter = matches.iterator();
        while (iter.hasNext()) {
            final ResolveInfo rInfo = iter.next();
            if (checkPermission(
                    Manifest.permission.INSTALL_PACKAGES,
                    rInfo.activityInfo.packageName, 0) == PERMISSION_GRANTED || mIsEngBuild) {
                continue;
            }
            iter.remove();
        }
        if (matches.size() == 0) {
            return null;
        } else if (matches.size() == 1) {
            return (ActivityInfo) matches.get(0).getComponentInfo();
        } else {
            throw new RuntimeException(
                    "There must be at most one ephemeral installer; found " + matches);
        }
    }

    private @Nullable ComponentName getInstantAppResolverSettingsLPr(@NonNull Computer computer,
            @NonNull ComponentName resolver) {
        final Intent intent =  new Intent(Intent.ACTION_INSTANT_APP_RESOLVER_SETTINGS)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(resolver.getPackageName());
        final int resolveFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                resolveFlags, UserHandle.USER_SYSTEM);
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(0).getComponentInfo().getComponentName();
    }

    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class)
                .getPermissionGroupInfo(groupName, flags);
    }

    /**
     * Blocking call to clear all cached app data above quota.
     */
    public void freeAllAppCacheAboveQuota(String volumeUuid) throws IOException {
        synchronized (mInstallLock) {
            // To avoid refactoring Installer.freeCache() and InstalldNativeService.freeCache(),
            // Long.MAX_VALUE is passed as an argument which is used in neither of two methods
            // when FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES is set
            try {
                mInstaller.freeCache(volumeUuid, Long.MAX_VALUE, Installer.FLAG_FREE_CACHE_V2
                        | Installer.FLAG_FREE_CACHE_DEFY_TARGET_FREE_BYTES);
            } catch (InstallerException ignored) {
            }
        }
        return;
    }

    /**
     * Blocking call to clear various types of cached data across the system
     * until the requested bytes are available.
     */
    public void freeStorage(String volumeUuid, long bytes,
            @StorageManager.AllocateFlags int flags) throws IOException {
        mFreeStorageHelper.freeStorage(volumeUuid, bytes, flags);
    }

    int freeCacheForInstallation(int recommendedInstallLocation, PackageLite pkgLite,
            String resolvedPath, String mPackageAbiOverride, int installFlags) {
        return mFreeStorageHelper.freeCacheForInstallation(recommendedInstallLocation, pkgLite,
                resolvedPath, mPackageAbiOverride, installFlags);
    }

    public ModuleInfo getModuleInfo(String packageName, @PackageManager.ModuleInfoFlags int flags) {
        return mModuleInfoProvider.getModuleInfo(packageName, flags);
    }

    void updateSequenceNumberLP(PackageSetting pkgSetting, int[] userList) {
        mChangedPackagesTracker.updateSequenceNumber(pkgSetting.getPackageName(), userList);
    }

    public boolean hasSystemFeature(String name, int version) {
        // allow instant applications
        synchronized (mAvailableFeatures) {
            final FeatureInfo feat = mAvailableFeatures.get(name);
            if (feat == null) {
                return false;
            } else {
                return feat.version >= version;
            }
        }
    }

    // NOTE: Can't remove due to unsupported app usage
    public int checkPermission(String permName, String pkgName, int userId) {
        return mPermissionManager.checkPermission(pkgName, permName, Context.DEVICE_ID_DEFAULT,
                userId);
    }

    public String getSdkSandboxPackageName() {
        return mRequiredSdkSandboxPackage;
    }

    String getPackageInstallerPackageName() {
        return mRequiredInstallerPackage;
    }

    void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
            Intent origIntent, String resolvedType, String callingPackage,
            @Nullable String callingFeatureId, boolean isRequesterInstantApp,
            Bundle verificationBundle, int userId) {
        final Message msg = mHandler.obtainMessage(INSTANT_APP_RESOLUTION_PHASE_TWO,
                new InstantAppRequest(responseObj, origIntent, resolvedType,
                        callingPackage, callingFeatureId, isRequesterInstantApp, userId,
                        verificationBundle, false /*resolveForStart*/,
                        responseObj.hostDigestPrefixSecure, responseObj.token));
        mHandler.sendMessage(msg);
    }

    // findPreferredActivityBody returns two items: a "things changed" flag and a
    // ResolveInfo, which is the preferred activity itself.
    static class FindPreferredActivityBodyResult {
        boolean mChanged;
        ResolveInfo mPreferredResolveInfo;
    }

    public @NonNull ParceledListSlice<ResolveInfo> queryIntentReceivers(@NonNull Computer snapshot,
            Intent intent, String resolvedType, @PackageManager.ResolveInfoFlagsBits long flags,
            @UserIdInt int userId) {
        return new ParceledListSlice<>(mResolveIntentHelper.queryIntentReceiversInternal(
                snapshot, intent, resolvedType, flags, userId, Binder.getCallingUid()));
    }

    public static void reportSettingsProblem(int priority, String msg) {
        logCriticalInfo(priority, msg);
    }

    // TODO:(b/135203078): Move to parsing
    static void renameStaticSharedLibraryPackage(ParsedPackage parsedPackage) {
        // Derive the new package synthetic package name
        parsedPackage.setPackageName(toStaticSharedLibraryPackageName(
                parsedPackage.getPackageName(), parsedPackage.getStaticSharedLibraryVersion()));
    }

    private static String toStaticSharedLibraryPackageName(
            String packageName, long libraryVersion) {
        return packageName + STATIC_SHARED_LIB_DELIMITER + libraryVersion;
    }

    public void performFstrimIfNeeded() {
        mFreeStorageHelper.performFstrimIfNeeded();
    }

    public void updatePackagesIfNeeded() {
        mDexOptHelper.performPackageDexOptUpgradeIfNeeded();
    }

    private void notifyPackageUseInternal(String packageName, int reason) {
        long time = System.currentTimeMillis();
        synchronized (mLock) {
            final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
            if (pkgSetting == null) {
                return;
            }
            pkgSetting.getPkgState().setLastPackageUsageTimeInMills(reason, time);
        }
    }

    /*package*/ DexManager getDexManager() {
        return mDexManager;
    }

    /*package*/ DexOptHelper getDexOptHelper() {
        return mDexOptHelper;
    }

    /*package*/ DynamicCodeLogger getDynamicCodeLogger() {
        return mDynamicCodeLogger;
    }

    public void shutdown() {
        mCompilerStats.writeNow();
        mDexManager.writePackageDexUsageNow();
        mDynamicCodeLogger.writeNow();
        PackageWatchdog.getInstance(mContext).writeNow();

        synchronized (mLock) {
            mPackageUsage.writeNow(mSettings.getPackagesLocked());

            if (mHandler.hasMessages(WRITE_SETTINGS)
                    || mBackgroundHandler.hasMessages(WRITE_DIRTY_PACKAGE_RESTRICTIONS)
                    || mHandler.hasMessages(WRITE_PACKAGE_LIST)) {
                writeSettings(/*sync=*/true);
            }
        }
    }

    @NonNull
    int[] resolveUserIds(int userId) {
        return (userId == UserHandle.USER_ALL) ? mUserManager.getUserIds() : new int[] { userId };
    }

    private void setUpInstantAppInstallerActivityLP(ActivityInfo installerActivity) {
        if (installerActivity == null) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Clear ephemeral installer activity");
            }
            mInstantAppInstallerActivity = null;
            onChanged();
            return;
        }

        if (DEBUG_INSTANT) {
            Slog.d(TAG, "Set ephemeral installer activity: "
                    + installerActivity.getComponentName());
        }
        // Set up information for ephemeral installer activity
        mInstantAppInstallerActivity = installerActivity;
        mInstantAppInstallerActivity.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                | ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
        mInstantAppInstallerActivity.exported = true;
        mInstantAppInstallerActivity.enabled = true;
        mInstantAppInstallerInfo.activityInfo = mInstantAppInstallerActivity;
        mInstantAppInstallerInfo.priority = 1;
        mInstantAppInstallerInfo.preferredOrder = 1;
        mInstantAppInstallerInfo.isDefault = true;
        mInstantAppInstallerInfo.match = IntentFilter.MATCH_CATEGORY_SCHEME_SPECIFIC_PART
                | IntentFilter.MATCH_ADJUSTMENT_NORMAL;
        onChanged();
    }

    void killApplication(String pkgName, @AppIdInt int appId, String reason, int exitInfoReason) {
        killApplication(pkgName, appId, UserHandle.USER_ALL, reason, exitInfoReason);
    }

    void killApplication(String pkgName, @AppIdInt int appId,
            @UserIdInt int userId, String reason, int exitInfoReason) {
        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        final long token = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killApplication(pkgName, appId, userId, reason, exitInfoReason);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void notifyPackageAdded(String packageName, int uid) {
        mPackageObserverHelper.notifyAdded(packageName, uid);
    }

    @Override
    public void notifyPackageChanged(String packageName, int uid) {
        mPackageObserverHelper.notifyChanged(packageName, uid);
    }

    @Override
    public void notifyPackageRemoved(String packageName, int uid) {
        mPackageObserverHelper.notifyRemoved(packageName, uid);
        UserPackage.removeFromCache(UserHandle.getUserId(uid), packageName);
    }

    boolean isUserRestricted(int userId, String restrictionKey) {
        Bundle restrictions = mUserManager.getUserRestrictions(userId);
        if (restrictions.getBoolean(restrictionKey, false)) {
            Log.w(TAG, "User is restricted: " + restrictionKey);
            return true;
        }
        return false;
    }

    private void enforceCanSetPackagesSuspendedAsUser(@NonNull Computer snapshot,
            boolean quarantined, UserPackage suspender, int callingUid, int targetUserId,
            String callingMethod) {
        if (callingUid == Process.ROOT_UID
                // Need to compare app-id to allow system dialogs access on secondary users
                || UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return;
        }

        final String ownerPackage =
                mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(targetUserId);
        if (ownerPackage != null) {
            final int ownerUid = snapshot.getPackageUid(ownerPackage, 0, targetUserId);
            if (ownerUid == callingUid) {
                return;
            }
        }

        if (quarantined) {
            final boolean hasQuarantineAppsPerm = mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.QUARANTINE_APPS) == PERMISSION_GRANTED;
            // TODO: b/305256093 - In order to facilitate testing, temporarily allowing apps
            // with SUSPEND_APPS permission to quarantine apps. Remove this once the testing
            // is done and this is no longer needed.
            if (!hasQuarantineAppsPerm) {
                mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SUSPEND_APPS,
                        callingMethod);
            }
        } else {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SUSPEND_APPS,
                    callingMethod);
        }

        final int packageUid = snapshot.getPackageUid(suspender.packageName, 0, targetUserId);
        final boolean allowedPackageUid = packageUid == callingUid;
        // TODO(b/139383163): remove special casing for shell and enforce INTERACT_ACROSS_USERS_FULL
        final boolean allowedShell = callingUid == SHELL_UID
                && UserHandle.isSameApp(packageUid, callingUid);

        if (!allowedShell && !allowedPackageUid) {
            throw new SecurityException("Suspending package " + suspender.packageName
                    + " in user " + targetUserId + " does not belong to calling uid " + callingUid);
        }
    }

    void unsuspendForSuspendingPackage(@NonNull Computer computer, String suspendingPackage,
            @UserIdInt int suspendingUserId) {
        // TODO: This can be replaced by a special parameter to iterate all packages, rather than
        //  this weird pre-collect of all packages.
        final String[] allPackages = computer.getPackageStates().keySet().toArray(new String[0]);
        final Predicate<UserPackage> suspenderPredicate =
                UserPackage.of(suspendingUserId, suspendingPackage)::equals;
        mSuspendPackageHelper.removeSuspensionsBySuspendingPackage(computer,
                allPackages, suspenderPredicate, suspendingUserId);
    }

    void removeAllDistractingPackageRestrictions(@NonNull Computer snapshot, int userId) {
        final String[] allPackages = snapshot.getAllAvailablePackageNames();
        mDistractingPackageHelper.removeDistractingPackageRestrictions(snapshot, allPackages,
                userId);
    }

    private void enforceCanSetDistractingPackageRestrictionsAsUser(int callingUid, int userId,
            String callingMethod) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                callingMethod);

        if (!PackageManagerServiceUtils.isSystemOrRoot(callingUid)
                && UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid + " cannot call for user "
                    + userId);
        }
    }

    void setEnableRollbackCode(int token, int enableRollbackCode) {
        final Message msg = mHandler.obtainMessage(ENABLE_ROLLBACK_STATUS);
        msg.arg1 = token;
        msg.arg2 = enableRollbackCode;
        mHandler.sendMessage(msg);
    }

    /**
     * Callback from PackageSettings whenever an app is first transitioned out of the
     * 'stopped' state.  Normally we just issue the broadcast, but we can't do that if
     * the app was "launched" for a restoreAtInstall operation.  Therefore we check
     * here whether the app is the target of an ongoing install, and only send the
     * broadcast immediately if it is not in that state.  If it *is* undergoing a restore,
     * the first-launch broadcast will be sent implicitly on that basis in POST_INSTALL
     * handling.
     */
    void notifyFirstLaunch(final String packageName, final String installerPackage,
            final int userId) {
        // Serialize this with the rest of the install-process message chain.  In the
        // restore-at-install case, this Runnable will necessarily run before the
        // POST_INSTALL message is processed, so the contents of mRunningInstalls
        // are coherent.  In the non-restore case, the app has already completed install
        // and been launched through some other means, so it is not in a problematic
        // state for observers to see the FIRST_LAUNCH signal.
        mHandler.post(() -> {
            for (int i = 0; i < mRunningInstalls.size(); i++) {
                final InstallRequest installRequest = mRunningInstalls.valueAt(i);
                if (installRequest.getReturnCode() != PackageManager.INSTALL_SUCCEEDED) {
                    continue;
                }
                if (packageName.equals(installRequest.getPkg().getPackageName())) {
                    // right package; but is it for the right user?
                    for (int uIndex = 0; uIndex < installRequest.getNewUsers().length; uIndex++) {
                        if (userId == installRequest.getNewUsers()[uIndex]) {
                            if (DEBUG_BACKUP) {
                                Slog.i(TAG, "Package " + packageName
                                        + " being restored so deferring FIRST_LAUNCH");
                            }
                            return;
                        }
                    }
                }
            }
            // didn't find it, so not being restored
            if (DEBUG_BACKUP) {
                Slog.i(TAG, "Package " + packageName + " sending normal FIRST_LAUNCH");
            }
            final boolean isInstantApp = snapshotComputer().isInstantAppInternal(
                    packageName, userId, Process.SYSTEM_UID);
            final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
            final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
            mBroadcastHelper.sendFirstLaunchBroadcast(
                    packageName, installerPackage, userIds, instantUserIds);
        });
    }

    @SuppressWarnings("GuardedBy")
    VersionInfo getSettingsVersionForPackage(AndroidPackage pkg) {
        if (pkg.isExternalStorage()) {
            if (TextUtils.isEmpty(pkg.getVolumeUuid())) {
                return mSettings.getExternalVersion();
            } else {
                return mSettings.findOrCreateVersion(pkg.getVolumeUuid());
            }
        } else {
            return mSettings.getInternalVersion();
        }
    }

    public void deleteExistingPackageAsUser(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId) {
        mDeletePackageHelper.deleteExistingPackageAsUser(
                versionedPackage, observer, userId);
    }

    public void deletePackageVersioned(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags) {
        mDeletePackageHelper.deletePackageVersionedInternal(
                versionedPackage, observer, userId, deleteFlags, false);
    }

    boolean isCallerVerifier(@NonNull Computer snapshot, int callingUid) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        for (String requiredVerifierPackage : mRequiredVerifierPackages) {
            if (callingUid == snapshot.getPackageUid(requiredVerifierPackage, 0, callingUserId)) {
                return true;
            }
        }
        return false;
    }

    public boolean isPackageDeviceAdminOnAnyUser(@NonNull Computer snapshot, String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (snapshot.checkUidPermission(android.Manifest.permission.MANAGE_USERS, callingUid)
                != PERMISSION_GRANTED) {
            EventLog.writeEvent(0x534e4554, "128599183", -1, "");
            throw new SecurityException(android.Manifest.permission.MANAGE_USERS
                    + " permission is required to call this API");
        }
        if (snapshot.getInstantAppPackageName(callingUid) != null
                && !snapshot.isCallerSameApp(packageName, callingUid)) {
            return false;
        }
        return isPackageDeviceAdmin(packageName, UserHandle.USER_ALL);
    }

    // TODO(b/261957226): centralise this logic in DPM
    boolean isPackageDeviceAdmin(String packageName, int userId) {
        final IDevicePolicyManager dpm = getDevicePolicyManager();
        try {
            if (dpm != null) {
                final ComponentName deviceOwnerComponentName = dpm.getDeviceOwnerComponent(
                        /* callingUserOnly =*/ false);
                final String deviceOwnerPackageName = deviceOwnerComponentName == null ? null
                        : deviceOwnerComponentName.getPackageName();
                // Does the package contains the device owner?
                // TODO Do we have to do it even if userId != UserHandle.USER_ALL?  Otherwise,
                // this check is probably not needed, since DO should be registered as a device
                // admin on some user too. (Original bug for this: b/17657954)
                if (packageName.equals(deviceOwnerPackageName)) {
                    return true;
                }
                // Does it contain a device admin for any user?
                int[] users;
                if (userId == UserHandle.USER_ALL) {
                    users = mUserManager.getUserIds();
                } else {
                    users = new int[]{userId};
                }
                for (int i = 0; i < users.length; ++i) {
                    if (dpm.packageHasActiveAdmins(packageName, users[i])) {
                        return true;
                    }
                    if (isDeviceManagementRoleHolder(packageName, users[i])) {
                        return true;
                    }
                }
            }
        } catch (RemoteException e) {
        }
        return false;
    }

    private boolean isDeviceManagementRoleHolder(String packageName, int userId) {
        return Objects.equals(packageName, getDevicePolicyManagementRoleHolderPackageName(userId));
    }

    @Nullable
    public String getDevicePolicyManagementRoleHolderPackageName(int userId) {
        return Binder.withCleanCallingIdentity(() -> {
            RoleManager roleManager = mContext.getSystemService(RoleManager.class);
            List<String> roleHolders =
                    roleManager.getRoleHoldersAsUser(
                            RoleManager.ROLE_DEVICE_POLICY_MANAGEMENT, UserHandle.of(userId));
            if (roleHolders.isEmpty()) {
                return null;
            }
            return roleHolders.get(0);
        });
    }

    /** Returns the device policy manager interface. */
    private IDevicePolicyManager getDevicePolicyManager() {
        if (mDevicePolicyManager == null) {
            // No need to synchronize; worst-case scenario it will be fetched twice.
            mDevicePolicyManager = IDevicePolicyManager.Stub.asInterface(
                            ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
        }
        return mDevicePolicyManager;
    }

    private boolean clearApplicationUserDataLIF(@NonNull Computer snapshot, String packageName,
            int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }

        // Try finding details about the requested package
        AndroidPackage pkg = snapshot.getPackage(packageName);
        if (pkg == null) {
            Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
            return false;
        }
        mPermissionManager.resetRuntimePermissions(pkg, userId);

        mAppDataHelper.clearAppDataLIF(pkg, userId,
                FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);

        final int appId = UserHandle.getAppId(pkg.getUid());
        mAppDataHelper.clearKeystoreData(userId, appId);

        UserManagerInternal umInternal = mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mInjector.getLocalService(StorageManagerInternal.class);
        final int flags;
        if (StorageManager.isCeStorageUnlocked(userId) && smInternal.isCeStoragePrepared(userId)) {
            flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
        } else if (umInternal.isUserRunning(userId)) {
            flags = StorageManager.FLAG_STORAGE_DE;
        } else {
            flags = 0;
        }
        mAppDataHelper.prepareAppDataContentsLIF(pkg, snapshot.getPackageStateInternal(packageName),
                userId, flags);

        return true;
    }

    /**
     * Update component enabled settings to {@link PackageManager#COMPONENT_ENABLED_STATE_DEFAULT}
     * if the resetEnabledSettingsOnAppDataCleared is {@code true}.
     */
    @GuardedBy("mLock")
    private void resetComponentEnabledSettingsIfNeededLPw(String packageName, int userId) {
        final AndroidPackage pkg = packageName != null ? mPackages.get(packageName) : null;
        if (pkg == null || !pkg.isResetEnabledSettingsOnAppDataCleared()) {
            return;
        }
        final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
        if (pkgSetting == null) {
            return;
        }
        final ArrayList<String> updatedComponents = new ArrayList<>();
        final Consumer<? super ParsedMainComponent> resetSettings = (component) -> {
            if (pkgSetting.restoreComponentLPw(component.getClassName(), userId)) {
                updatedComponents.add(component.getClassName());
            }
        };
        for (int i = 0; i < pkg.getActivities().size(); i++) {
            resetSettings.accept(pkg.getActivities().get(i));
        }
        for (int i = 0; i < pkg.getReceivers().size(); i++) {
            resetSettings.accept(pkg.getReceivers().get(i));
        }
        for (int i = 0; i < pkg.getServices().size(); i++) {
            resetSettings.accept(pkg.getServices().get(i));
        }
        for (int i = 0; i < pkg.getProviders().size(); i++) {
            resetSettings.accept(pkg.getProviders().get(i));
        }
        if (ArrayUtils.isEmpty(updatedComponents)) {
            // nothing changed
            return;
        }

        updateSequenceNumberLP(pkgSetting, new int[] { userId });
        updateInstantAppInstallerLocked(packageName);
        scheduleWritePackageRestrictions(userId);

        mPendingBroadcasts.addComponents(userId, packageName, updatedComponents);
        if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
            mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
        }
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    @GuardedBy("mLock")
    void clearPackagePreferredActivitiesLPw(String packageName,
            @NonNull SparseBooleanArray outUserChanged, int userId) {
        mSettings.clearPackagePreferredActivities(packageName, outUserChanged, userId);
    }

    void restorePermissionsAndUpdateRolesForNewUserInstall(String packageName,
            @UserIdInt int userId) {
        // We may also need to apply pending (restored) runtime permission grants
        // within these users.
        mPermissionManager.restoreDelayedRuntimePermissions(packageName, userId);

        // Restore default browser setting if it is now installed.
        String defaultBrowser;
        synchronized (mLock) {
            defaultBrowser = mSettings.getPendingDefaultBrowserLPr(userId);
        }
        if (Objects.equals(packageName, defaultBrowser)) {
            mDefaultAppProvider.setDefaultBrowser(packageName, userId);
            synchronized (mLock) {
                mSettings.removePendingDefaultBrowserLPw(userId);
            }
        }

        // Persistent preferred activity might have came into effect due to this
        // install.
        mPreferredActivityHelper.updateDefaultHomeNotLocked(snapshotComputer(), userId);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addCrossProfileIntentFilter(@NonNull Computer snapshot,
            WatchedIntentFilter intentFilter, String ownerPackage, int sourceUserId,
            int targetUserId, int flags) {
        mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(snapshot, ownerPackage, callingUid);

        // Verifying that current calling uid should be able to add {@link CrossProfileIntentFilter}
        // for source and target user
        mUserManager.enforceCrossProfileIntentFilterAccess(sourceUserId, targetUserId, callingUid,
                /* addCrossProfileIntentFilter */ true);

        PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        if (!intentFilter.checkDataPathAndSchemeSpecificParts()) {
            EventLog.writeEvent(0x534e4554, "246749936", callingUid);
            throw new IllegalArgumentException("Invalid intent data paths or scheme specific parts"
                    + " in the filter.");
        }
        if (intentFilter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a crossProfile intent filter with no filter actions");
            return;
        }
        synchronized (mLock) {
            CrossProfileIntentFilter newFilter = new CrossProfileIntentFilter(intentFilter,
                    ownerPackage, targetUserId, flags, mUserManager
                    .getCrossProfileIntentFilterAccessControl(sourceUserId, targetUserId));
            CrossProfileIntentResolver resolver =
                    mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArrayList<CrossProfileIntentFilter> existing = resolver.findFilters(intentFilter);
            // We have all those whose filter is equal. Now checking if the rest is equal as well.
            if (existing != null) {
                int size = existing.size();
                for (int i = 0; i < size; i++) {
                    if (newFilter.equalsIgnoreFilter(existing.get(i))) {
                        return;
                    }
                }
            }
            resolver.addFilter(snapshotComputer(), newFilter);
        }
        scheduleWritePackageRestrictions(sourceUserId);
    }


    // Enforcing that callingUid is owning pkg on userId
    private void enforceOwnerRights(@NonNull Computer snapshot, String pkg, int callingUid) {
        // The system owns everything.
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return;
        }
        final String[] callerPackageNames = snapshot.getPackagesForUid(callingUid);
        if (!ArrayUtils.contains(callerPackageNames, pkg)) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not own package " + pkg);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        PackageInfo pi = snapshot.getPackageInfo(pkg, 0, callingUserId);
        if (pi == null) {
            throw new IllegalArgumentException("Unknown package " + pkg + " on user "
                    + callingUserId);
        }
    }

    public void sendSessionCommitBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId) {
        mBroadcastHelper.sendSessionCommitBroadcast(snapshotComputer(), sessionInfo, userId,
                mAppPredictionServicePackage);
    }

    private @Nullable String getSetupWizardPackageNameImpl(@NonNull Computer computer) {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_SETUP_WIZARD);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE
                        | MATCH_DISABLED_COMPONENTS,
                UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else {
            Slog.e(TAG, "There should probably be exactly one setup wizard; found " + matches.size()
                    + ": matches=" + matches);
            return null;
        }
    }

    private @Nullable String getStorageManagerPackageName(@NonNull Computer computer) {
        final Intent intent = new Intent(StorageManager.ACTION_MANAGE_STORAGE);

        final List<ResolveInfo> matches = computer.queryIntentActivitiesInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE
                        | MATCH_DISABLED_COMPONENTS,
                UserHandle.myUserId());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else {
            Slog.w(TAG, "There should probably be exactly one storage manager; found "
                    + matches.size() + ": matches=" + matches);
            return null;
        }
    }

    @NonNull
    private static String getRequiredSdkSandboxPackageName(@NonNull Computer computer) {
        final Intent intent = new Intent(SdkSandboxManagerLocal.SERVICE_INTERFACE);

        final List<ResolveInfo> matches = computer.queryIntentServicesInternal(
                intent,
                /* resolvedType= */ null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM,
                /* callingUid= */ Process.myUid(),
                /* includeInstantApps= */ false);
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else {
            throw new RuntimeException("There should exactly one sdk sandbox package; found "
                    + matches.size() + ": matches=" + matches);
        }
    }

    @Nullable
    private String getRetailDemoPackageName() {
        final String predefinedPkgName = mContext.getString(R.string.config_retailDemoPackage);
        final String predefinedSignature = mContext.getString(
                R.string.config_retailDemoPackageSignature);

        if (TextUtils.isEmpty(predefinedPkgName) || TextUtils.isEmpty(predefinedSignature)) {
            return null;
        }

        final AndroidPackage androidPkg = mPackages.get(predefinedPkgName);
        if (androidPkg != null) {
            final SigningDetails signingDetail = androidPkg.getSigningDetails();
            if (signingDetail != null && signingDetail.getSignatures() != null) {
                try {
                    final MessageDigest msgDigest = MessageDigest.getInstance("SHA-256");
                    for (Signature signature : signingDetail.getSignatures()) {
                        if (TextUtils.equals(predefinedSignature,
                                HexEncoding.encodeToString(msgDigest.digest(
                                        signature.toByteArray()), false))) {
                            return predefinedPkgName;
                        }
                    }
                } catch (NoSuchAlgorithmException e) {
                    Slog.e(
                            TAG,
                            "Unable to verify signatures as getting the retail demo package name",
                            e);
                }
            }
        }

        return null;
    }

    @Nullable
    String getPackageFromComponentString(@StringRes int stringResId) {
        final String componentString = mContext.getString(stringResId);
        if (TextUtils.isEmpty(componentString)) {
            return null;
        }
        final ComponentName component = ComponentName.unflattenFromString(componentString);
        if (component == null) {
            return null;
        }
        return component.getPackageName();
    }

    @Nullable
    String ensureSystemPackageName(@NonNull Computer snapshot,
            @Nullable String packageName) {
        if (packageName == null) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (snapshot.getPackageInfo(packageName, MATCH_FACTORY_ONLY,
                    UserHandle.USER_SYSTEM) == null) {
                PackageInfo packageInfo =
                        snapshot.getPackageInfo(packageName, 0, UserHandle.USER_SYSTEM);
                if (packageInfo != null) {
                    EventLog.writeEvent(0x534e4554, "145981139", packageInfo.applicationInfo.uid,
                            "");
                }
                Log.w(TAG, "Missing required system package: " + packageName + (packageInfo != null
                        ? ", but found with extended search." : "."));
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return packageName;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void updateComponentLabelIcon(/*@NonNull*/ ComponentName componentName,
            @Nullable String nonLocalizedLabel, @Nullable Integer icon, int userId) {
        if (componentName == null) {
            throw new IllegalArgumentException("Must specify a component");
        }

        int callingUid = Binder.getCallingUid();
        String componentPkgName = componentName.getPackageName();

        Computer computer = snapshotComputer();

        int componentUid = computer.getPackageUid(componentPkgName, 0, userId);
        if (!UserHandle.isSameApp(callingUid, componentUid)) {
            throw new SecurityException("The calling UID (" + callingUid + ")"
                    + " does not match the target UID");
        }

        String allowedCallerPkg =
                mContext.getString(R.string.config_overrideComponentUiPackage);
        if (TextUtils.isEmpty(allowedCallerPkg)) {
            throw new SecurityException( "There is no package defined as allowed to change a "
                    + "component's label or icon");
        }

        int allowedCallerUid = computer.getPackageUid(allowedCallerPkg,
                PackageManager.MATCH_SYSTEM_ONLY, userId);
        if (allowedCallerUid == -1 || !UserHandle.isSameApp(callingUid, allowedCallerUid)) {
            throw new SecurityException("The calling UID (" + callingUid + ")"
                    + " is not allowed to change a component's label or icon");
        }
        PackageStateInternal packageState = computer.getPackageStateInternal(componentPkgName);
        if (packageState == null || packageState.getPkg() == null
                || (!packageState.isSystem()
                && !packageState.isUpdatedSystemApp())) {
            throw new SecurityException(
                    "Changing the label is not allowed for " + componentName);
        }

        if (!computer.getComponentResolver().componentExists(componentName)) {
            throw new IllegalArgumentException("Component " + componentName + " not found");
        }

        Pair<String, Integer> overrideLabelIcon = packageState.getUserStateOrDefault(userId)
                .getOverrideLabelIconForComponent(componentName);

        String existingLabel = overrideLabelIcon == null ? null : overrideLabelIcon.first;
        Integer existingIcon = overrideLabelIcon == null ? null : overrideLabelIcon.second;

        if (TextUtils.equals(existingLabel, nonLocalizedLabel)
                && Objects.equals(existingIcon, icon)) {
            // Nothing changed
            return;
        }

        commitPackageStateMutation(null, componentPkgName,
                state -> state.userState(userId)
                        .setComponentLabelIcon(componentName, nonLocalizedLabel, icon));

        mPendingBroadcasts.addComponent(userId, componentPkgName, componentName.getClassName());

        if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
            mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
        }
    }

    private void setEnabledSettings(List<ComponentEnabledSetting> settings, int userId,
            @NonNull String callingPackage) {
        final int callingUid = Binder.getCallingUid();
        // TODO: This method is not properly snapshotified beyond this call
        final Computer preLockSnapshot = snapshotComputer();
        preLockSnapshot.enforceCrossUserPermission(callingUid, userId,
                false /* requireFullPermission */, true /* checkShell */, "set enabled");

        final int targetSize = settings.size();
        for (int i = 0; i < targetSize; i++) {
            final int newState = settings.get(i).getEnabledState();
            if (!(newState == COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == COMPONENT_ENABLED_STATE_ENABLED
                    || newState == COMPONENT_ENABLED_STATE_DISABLED
                    || newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                    || newState == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
                throw new IllegalArgumentException("Invalid new component state: " + newState);
            }
        }
        if (targetSize > 1) {
            final ArraySet<String> checkDuplicatedPackage = new ArraySet<>();
            final ArraySet<ComponentName> checkDuplicatedComponent = new ArraySet<>();
            final ArrayMap<String, Integer> checkConflictFlag = new ArrayMap<>();
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (setting.isComponent()) {
                    final ComponentName componentName = setting.getComponentName();
                    if (checkDuplicatedComponent.contains(componentName)) {
                        throw new IllegalArgumentException("The component " + componentName
                                + " is duplicated");
                    }
                    checkDuplicatedComponent.add(componentName);

                    // check if there is a conflict of the DONT_KILL_APP flag between components
                    // in the package
                    final Integer enabledFlags = checkConflictFlag.get(packageName);
                    if (enabledFlags == null) {
                        checkConflictFlag.put(packageName, setting.getEnabledFlags());
                    } else if ((enabledFlags & PackageManager.DONT_KILL_APP)
                            != (setting.getEnabledFlags() & PackageManager.DONT_KILL_APP)) {
                        throw new IllegalArgumentException("A conflict of the DONT_KILL_APP flag "
                                + "between components in the package " + packageName);
                    }
                } else {
                    if (checkDuplicatedPackage.contains(packageName)) {
                        throw new IllegalArgumentException("The package " + packageName
                                + " is duplicated");
                    }
                    checkDuplicatedPackage.add(packageName);
                }
            }
        }

        final boolean allowedByPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE) == PERMISSION_GRANTED;
        final boolean[] updateAllowed = new boolean[targetSize];
        Arrays.fill(updateAllowed, true);

        final Map<String, PackageSetting> pkgSettings = new ArrayMap<>(targetSize);
        // reader
        synchronized (mLock) {
            final Computer snapshot = snapshotComputer();
            // Checks for target packages
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (pkgSettings.containsKey(packageName)) {
                    // this package has verified
                    continue;
                }
                final boolean isCallerTargetApp = ArrayUtils.contains(
                        snapshot.getPackagesForUid(callingUid), packageName);
                final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
                // Limit who can change which apps
                if (!isCallerTargetApp && !allowedByPermission) {
                    // Don't allow apps that don't have permission to modify other apps
                    throw new SecurityException("Attempt to change component state; "
                            + "pid=" + Binder.getCallingPid()
                            + ", uid=" + callingUid
                            + (!setting.isComponent() ? ", package=" + packageName
                            : ", component=" + setting.getComponentName()));
                }
                if (pkgSetting == null || snapshot.shouldFilterApplicationIncludingUninstalled(
                        pkgSetting, callingUid, userId)) {
                    throw new IllegalArgumentException(setting.isComponent()
                            ? "Unknown component: " + setting.getComponentName()
                            : "Unknown package: " + packageName);
                }
                // Don't allow changing protected packages.
                if (!isCallerTargetApp
                        && mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                    throw new SecurityException(
                            "Cannot disable a protected package: " + packageName);
                }
                if (callingUid == Process.SHELL_UID
                        && (pkgSetting.getFlags() & ApplicationInfo.FLAG_TEST_ONLY) == 0) {
                    // Shell can only change whole packages between ENABLED and DISABLED_USER states
                    // unless it is a test package.
                    final int oldState = pkgSetting.getEnabled(userId);
                    final int newState = setting.getEnabledState();
                    if (!setting.isComponent()
                            &&
                            (oldState == COMPONENT_ENABLED_STATE_DISABLED_USER
                                    || oldState == COMPONENT_ENABLED_STATE_DEFAULT
                                    || oldState == COMPONENT_ENABLED_STATE_ENABLED)
                            &&
                            (newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                                    || newState == COMPONENT_ENABLED_STATE_DEFAULT
                                    || newState == COMPONENT_ENABLED_STATE_ENABLED)) {
                        // ok
                    } else {
                        throw new SecurityException(
                                "Shell cannot change component state for "
                                        + setting.getComponentName() + " to " + newState);
                    }
                }
                pkgSettings.put(packageName, pkgSetting);
            }
            // Checks for target components
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                // skip if it's application
                if (!setting.isComponent()) continue;

                // Only allow apps with CHANGE_COMPONENT_ENABLED_STATE permission to change hidden
                // app details activity
                final String packageName = setting.getPackageName();
                final String className = setting.getClassName();
                if (!allowedByPermission
                        && PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(className)) {
                    throw new SecurityException("Cannot disable a system-generated component");
                }
                // Verify that this is a valid class name.
                final AndroidPackage pkg = pkgSettings.get(packageName).getPkg();
                if (pkg == null || !AndroidPackageUtils.hasComponentClassName(pkg, className)) {
                    if (pkg != null
                            && pkg.getTargetSdkVersion() >= Build.VERSION_CODES.JELLY_BEAN) {
                        throw new IllegalArgumentException("Component class " + className
                                + " does not exist in " + packageName);
                    } else {
                        Slog.w(TAG, "Failed setComponentEnabledSetting: component class "
                                + className + " does not exist in " + packageName);
                        updateAllowed[i] = false;
                    }
                }
            }
        }

        // More work for application enabled setting updates
        for (int i = 0; i < targetSize; i++) {
            final ComponentEnabledSetting setting = settings.get(i);
            // skip if it's component
            if (setting.isComponent()) continue;

            final PackageSetting pkgSetting = pkgSettings.get(setting.getPackageName());
            final int newState = setting.getEnabledState();
            synchronized (mLock) {
                if (pkgSetting.getEnabled(userId) == newState) {
                    // Nothing to do
                    updateAllowed[i] = false;
                    continue;
                }
            }
            // If we're enabling a system stub, there's a little more work to do.
            // Prior to enabling the package, we need to decompress the APK(s) to the
            // data partition and then replace the version on the system partition.
            final AndroidPackage deletedPkg = pkgSetting.getPkg();
            final boolean isSystemStub = (deletedPkg != null)
                    && deletedPkg.isStub()
                    && pkgSetting.isSystem();
            if (isSystemStub
                    && (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
                if (!enableCompressedPackage(deletedPkg, pkgSetting)) {
                    Slog.w(TAG, "Failed setApplicationEnabledSetting: failed to enable "
                            + "commpressed package " + setting.getPackageName());
                    updateAllowed[i] = false;
                }
            }
        }

        // packageName -> list of components to send broadcasts now
        final ArrayMap<String, ArrayList<String>> sendNowBroadcasts = new ArrayMap<>(targetSize);
        synchronized (mLock) {
            Computer computer = snapshotComputer();
            boolean scheduleBroadcastMessage = false;
            boolean isSynchronous = false;
            boolean anyChanged = false;

            for (int i = 0; i < targetSize; i++) {
                if (!updateAllowed[i]) {
                    continue;
                }
                // update enabled settings
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (!setEnabledSettingInternalLocked(computer, pkgSettings.get(packageName),
                        setting, userId, callingPackage)) {
                    continue;
                }
                anyChanged = true;

                if ((setting.getEnabledFlags() & PackageManager.SYNCHRONOUS) != 0) {
                    isSynchronous = true;
                }
                // collect broadcast list for the package
                final String componentName = setting.isComponent()
                        ? setting.getClassName() : packageName;
                if ((setting.getEnabledFlags() & PackageManager.DONT_KILL_APP) == 0) {
                    ArrayList<String> componentList = sendNowBroadcasts.get(packageName);
                    componentList = componentList == null ? new ArrayList<>() : componentList;
                    if (!componentList.contains(componentName)) {
                        componentList.add(componentName);
                    }
                    sendNowBroadcasts.put(packageName, componentList);
                    // Purge entry from pending broadcast list if another one exists already
                    // since we are sending one right away.
                    mPendingBroadcasts.remove(userId, packageName);
                } else {
                    mPendingBroadcasts.addComponent(userId, packageName, componentName);
                    scheduleBroadcastMessage = true;
                }
            }
            if (!anyChanged) {
                // nothing changed, return immediately
                return;
            }

            if (isSynchronous) {
                flushPackageRestrictionsAsUserInternalLocked(userId);
            } else {
                scheduleWritePackageRestrictions(userId);
            }
            if (scheduleBroadcastMessage) {
                if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
                    // Schedule a message - if it has been a "reasonably long time" since the
                    // service started, send the broadcast with a delay of one second to avoid
                    // delayed reactions from the receiver, else keep the default ten second delay
                    // to avoid extreme thrashing on service startup.
                    final long broadcastDelay = SystemClock.uptimeMillis() > mServiceStartWithDelay
                            ? BROADCAST_DELAY
                            : BROADCAST_DELAY_DURING_STARTUP;
                    mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, broadcastDelay);
                }
            }
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            final Computer newSnapshot = snapshotComputer();
            for (int i = 0; i < sendNowBroadcasts.size(); i++) {
                final String packageName = sendNowBroadcasts.keyAt(i);
                final ArrayList<String> components = sendNowBroadcasts.valueAt(i);
                final int packageUid = UserHandle.getUid(
                        userId, pkgSettings.get(packageName).getAppId());
                mBroadcastHelper.sendPackageChangedBroadcast(newSnapshot, packageName,
                        false /* dontKillApp */, components, packageUid, null /* reason */);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @GuardedBy("mLock")
    private boolean setEnabledSettingInternalLocked(@NonNull Computer computer,
            PackageSetting pkgSetting, ComponentEnabledSetting setting, @UserIdInt int userId,
            String callingPackage) {
        final int newState = setting.getEnabledState();
        final String packageName = setting.getPackageName();
        boolean success = false;
        if (!setting.isComponent()) {
            // We're dealing with an application/package level state change
            pkgSetting.setEnabled(newState, userId, callingPackage);
            if ((newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                    || newState == COMPONENT_ENABLED_STATE_DISABLED)
                    && checkPermission(Manifest.permission.SUSPEND_APPS, packageName, userId)
                    == PERMISSION_GRANTED) {
                // This app should not generally be allowed to get disabled by the UI, but
                // if it ever does, we don't want to end up with some of the user's apps
                // permanently suspended.
                unsuspendForSuspendingPackage(computer, packageName, userId);
                removeAllDistractingPackageRestrictions(computer, userId);
            }
            success = true;
        } else {
            // We're dealing with a component level state change
            final String className = setting.getClassName();
            switch (newState) {
                case COMPONENT_ENABLED_STATE_ENABLED:
                    success = pkgSetting.enableComponentLPw(className, userId);
                    break;
                case COMPONENT_ENABLED_STATE_DISABLED:
                    success = pkgSetting.disableComponentLPw(className, userId);
                    break;
                case COMPONENT_ENABLED_STATE_DEFAULT:
                    success = pkgSetting.restoreComponentLPw(className, userId);
                    break;
                default:
                    Slog.e(TAG, "Failed setComponentEnabledSetting: component "
                            + packageName + "/" + className
                            + " requested an invalid new component state: " + newState);
                    break;
            }
        }
        if (!success) {
            return false;
        }

        updateSequenceNumberLP(pkgSetting, new int[] { userId });
        final long callingId = Binder.clearCallingIdentity();
        try {
            updateInstantAppInstallerLocked(packageName);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return true;
    }

    @GuardedBy("mLock")
    private void flushPackageRestrictionsAsUserInternalLocked(int userId) {
        // NOTE: this invokes synchronous disk access, so callers using this
        // method should consider running on a background thread
        mSettings.writePackageRestrictionsLPr(userId);
        synchronized (mDirtyUsers) {
            mDirtyUsers.remove(userId);
            if (mDirtyUsers.isEmpty()) {
                mBackgroundHandler.removeMessages(WRITE_DIRTY_PACKAGE_RESTRICTIONS);
            }
        }
    }

    /**
     * Used by SystemServer
     */
    public void waitForAppDataPrepared() {
        if (mPrepareAppDataFuture == null) {
            return;
        }
        ConcurrentUtils.waitForFutureNoInterrupt(mPrepareAppDataFuture, "wait for prepareAppData");
        mPrepareAppDataFuture = null;
    }

    public void systemReady() {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system can claim the system is ready");

        final ContentResolver resolver = mContext.getContentResolver();
        if (mReleaseOnSystemReady != null) {
            for (int i = mReleaseOnSystemReady.size() - 1; i >= 0; --i) {
                final File dstCodePath = mReleaseOnSystemReady.get(i);
                F2fsUtils.releaseCompressedBlocks(resolver, dstCodePath);
            }
            mReleaseOnSystemReady = null;
        }
        mSystemReady = true;
        ContentObserver co = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                final boolean ephemeralFeatureDisabled =
                        Global.getInt(resolver, Global.ENABLE_EPHEMERAL_FEATURE, 1) == 0;
                for (int userId : UserManagerService.getInstance().getUserIds()) {
                    final boolean instantAppsDisabledForUser =
                            ephemeralFeatureDisabled || Secure.getIntForUser(resolver,
                                    Secure.INSTANT_APPS_ENABLED, 1, userId) == 0;
                    mWebInstantAppsDisabled.put(userId, instantAppsDisabledForUser);
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(android.provider.Settings.Global
                        .getUriFor(Global.ENABLE_EPHEMERAL_FEATURE),
                false, co, UserHandle.USER_ALL);
        mContext.getContentResolver().registerContentObserver(android.provider.Settings.Secure
                .getUriFor(Secure.INSTANT_APPS_ENABLED), false, co, UserHandle.USER_ALL);
        co.onChange(true);

        mAppsFilter.onSystemReady(LocalServices.getService(PackageManagerInternal.class));

        // Disable any carrier apps. We do this very early in boot to prevent the apps from being
        // disabled after already being started.
        CarrierAppUtils.disableCarrierAppsUntilPrivileged(
                mContext.getOpPackageName(), UserHandle.USER_SYSTEM, mContext);

        disableSkuSpecificApps();

        // Read the compatibilty setting when the system is ready.
        boolean compatibilityModeEnabled = android.provider.Settings.Global.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.Global.COMPATIBILITY_MODE, 1) == 1;
        ParsingPackageUtils.setCompatibilityModeEnabled(compatibilityModeEnabled);

        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + compatibilityModeEnabled);
        }

        synchronized (mLock) {
            ArrayList<Integer> changed = mSettings.systemReady(mComponentResolver);
            for (int i = 0; i < changed.size(); i++) {
                mSettings.writePackageRestrictionsLPr(changed.get(i));
            }
        }

        mUserManager.systemReady();

        // Watch for external volumes that come and go over time
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        storage.registerListener(mStorageEventHelper);

        mInstallerService.systemReady();
        mPackageDexOptimizer.systemReady();

        // Now that we're mostly running, clean up stale users and apps
        mUserManager.reconcileUsers(StorageManager.UUID_PRIVATE_INTERNAL);
        mStorageEventHelper.reconcileApps(snapshotComputer(), StorageManager.UUID_PRIVATE_INTERNAL);

        mPermissionManager.onSystemReady();

        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
        final List<UserInfo> livingUsers = mInjector.getUserManagerInternal().getUsers(
                /* excludePartial= */ true,
                /* excludeDying= */ true,
                /* excludePreCreated= */ false);
        final int livingUserCount = livingUsers.size();
        for (int i = 0; i < livingUserCount; i++) {
            final int userId = livingUsers.get(i).id;
            final boolean isPermissionUpgradeNeeded = !Objects.equals(
                    mPermissionManager.getDefaultPermissionGrantFingerprint(userId),
                    Build.FINGERPRINT);
            if (isPermissionUpgradeNeeded) {
                grantPermissionsUserIds = ArrayUtils.appendInt(
                        grantPermissionsUserIds, userId);
            }
        }
        // If we upgraded grant all default permissions before kicking off.
        for (int userId : grantPermissionsUserIds) {
            mLegacyPermissionManager.grantDefaultPermissions(userId);
            mPermissionManager.setDefaultPermissionGrantFingerprint(Build.FINGERPRINT, userId);
        }
        if (grantPermissionsUserIds == EMPTY_INT_ARRAY) {
            // If we did not grant default permissions, we preload from this the
            // default permission exceptions lazily to ensure we don't hit the
            // disk on a new user creation.
            mLegacyPermissionManager.scheduleReadDefaultPermissionExceptions();
        }

        if (mInstantAppResolverConnection != null) {
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mInstantAppResolverConnection.optimisticBind();
                    mContext.unregisterReceiver(this);
                }
            }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        }

        IntentFilter overlayFilter = new IntentFilter(Intent.ACTION_OVERLAY_CHANGED);
        overlayFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                Uri data = intent.getData();
                if (data == null) {
                    return;
                }
                String packageName = data.getSchemeSpecificPart();
                if (packageName == null) {
                    return;
                }
                final Computer snapshot = snapshotComputer();
                AndroidPackage pkg = snapshot.getPackage(packageName);
                if (pkg == null) {
                    return;
                }
                mBroadcastHelper.sendPackageChangedBroadcast(snapshot, pkg.getPackageName(),
                        true /* dontKillApp */,
                        new ArrayList<>(Collections.singletonList(pkg.getPackageName())),
                        pkg.getUid(),
                        Intent.ACTION_OVERLAY_CHANGED);
            }
        }, overlayFilter);

        mModuleInfoProvider.systemReady();

        // Installer service might attempt to install some packages that have been staged for
        // installation on reboot. Make sure this is the last component to be call since the
        // installation might require other components to be ready.
        mInstallerService.restoreAndApplyStagedSessionIfNeeded();

        mExistingPackages = null;

        // Clear cache on flags changes.
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE_PACKAGE_MANAGER_SERVICE, mInjector.getBackgroundExecutor(),
                properties -> {
                    final Set<String> keyset = properties.getKeyset();
                    if (keyset.contains(PROPERTY_INCFS_DEFAULT_TIMEOUTS) || keyset.contains(
                            PROPERTY_KNOWN_DIGESTERS_LIST)) {
                        mPerUidReadTimeoutsCache = null;
                    }
                });

        if (!useArtService()) {
            // The background dexopt job is scheduled in DexOptHelper.initializeArtManagerLocal when
            // ART Service is in use.
            try {
                mBackgroundDexOptService.systemReady();
            } catch (LegacyDexoptDisabledException e) {
                throw new RuntimeException(e);
            }
        }

        // Prune unused static shared libraries which have been cached a period of time
        schedulePruneUnusedStaticSharedLibraries(false /* delay */);

        DexUseManagerLocal dexUseManager = DexOptHelper.getDexUseManagerLocal();
        if (dexUseManager != null) {
            dexUseManager.systemReady();
        }
    }

    //TODO: b/111402650
    private void disableSkuSpecificApps() {
        String[] apkList = mContext.getResources().getStringArray(
                R.array.config_disableApksUnlessMatchedSku_apk_list);
        String[] skuArray = mContext.getResources().getStringArray(
                R.array.config_disableApkUnlessMatchedSku_skus_list);
        if (ArrayUtils.isEmpty(apkList)) {
           return;
        }
        String sku = SystemProperties.get("ro.boot.hardware.sku");
        if (!TextUtils.isEmpty(sku) && ArrayUtils.contains(skuArray, sku)) {
            return;
        }
        final Computer snapshot = snapshotComputer();
        for (String packageName : apkList) {
            setSystemAppHiddenUntilInstalled(snapshot, packageName, true);
            final List<UserInfo> users = mInjector.getUserManagerInternal().getUsers(false);
            for (int i = 0; i < users.size(); i++) {
                setSystemAppInstallState(snapshot, packageName, false, users.get(i).id);
            }
        }
    }

    public PackageFreezer freezePackage(String packageName, int userId, String killReason,
            int exitInfoReason, InstallRequest request) {
        return new PackageFreezer(packageName, userId, killReason, this, exitInfoReason, request);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int userId, int deleteFlags,
            String killReason, int exitInfoReason) {
        if ((deleteFlags & PackageManager.DELETE_DONT_KILL_APP) != 0) {
            return new PackageFreezer(this, null /* request */);
        } else {
            return freezePackage(packageName, userId, killReason, exitInfoReason,
                    null /* request */);
        }
    }

    /** Called by UserManagerService */
    void cleanUpUser(UserManagerService userManager, @UserIdInt int userId) {
        synchronized (mLock) {
            synchronized (mDirtyUsers) {
                mDirtyUsers.remove(userId);
            }
            mUserNeedsBadging.delete(userId);
            mPermissionManager.onUserRemoved(userId);
            mSettings.removeUserLPw(userId);
            mPendingBroadcasts.remove(userId);
            mDeletePackageHelper.removeUnusedPackagesLPw(userManager, userId);
            mAppsFilter.onUserDeleted(snapshotComputer(), userId);
        }
        mInstantAppRegistry.onUserRemoved(userId);
        mPackageMonitorCallbackHelper.onUserRemoved(userId);
    }

    /**
     * Called by UserManagerService.
     *
     * @param userTypeInstallablePackages system packages that should be initially installed for
     *                                    this type of user, or {@code null} if all system packages
     *                                    should be installed
     * @param disallowedPackages packages that should not be initially installed. Takes precedence
     *                           over installablePackages.
     */
    void createNewUser(int userId, @Nullable Set<String> userTypeInstallablePackages,
            String[] disallowedPackages) {
        synchronized (mInstallLock) {
            mSettings.createNewUserLI(this, mInstaller, userId,
                    userTypeInstallablePackages, disallowedPackages);
        }
        synchronized (mLock) {
            scheduleWritePackageRestrictions(userId);
            scheduleWritePackageListLocked(userId);
            mAppsFilter.onUserCreated(snapshotComputer(), userId);
        }
    }

    void onNewUserCreated(@UserIdInt int userId, boolean convertedFromPreCreated) {
        if (DEBUG_PERMISSIONS) {
            Slog.d(TAG, "onNewUserCreated(id=" + userId
                    + ", convertedFromPreCreated=" + convertedFromPreCreated + ")");
        }
        if (!convertedFromPreCreated || !readPermissionStateForUser(userId)) {
            mPermissionManager.onUserCreated(userId);
            mLegacyPermissionManager.grantDefaultPermissions(userId);
            mPermissionManager.setDefaultPermissionGrantFingerprint(Build.FINGERPRINT, userId);
            mDomainVerificationManager.clearUser(userId);
        }
    }

    private boolean readPermissionStateForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            mPermissionManager.writeLegacyPermissionStateTEMP();
            mSettings.readPermissionStateForUserSyncLPr(userId);
            mPermissionManager.readLegacyPermissionStateTEMP();
            final boolean isPermissionUpgradeNeeded = !Objects.equals(
                    mPermissionManager.getDefaultPermissionGrantFingerprint(userId),
                    Build.FINGERPRINT);
            return isPermissionUpgradeNeeded;
        }
    }

    public boolean isStorageLow() {
        // allow instant applications
        final long token = Binder.clearCallingIdentity();
        try {
            final DeviceStorageMonitorInternal
                    dsm = mInjector.getLocalService(DeviceStorageMonitorInternal.class);
            if (dsm != null) {
                return dsm.isMemoryLow();
            } else {
                return false;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void deletePackageIfUnused(@NonNull Computer snapshot, final String packageName) {
        PackageStateInternal ps = snapshot.getPackageStateInternal(packageName);
        if (ps == null) {
            return;
        }
        final SparseArray<? extends PackageUserStateInternal> userStates = ps.getUserStates();
        for (int index = 0; index < userStates.size(); index++) {
            if (userStates.valueAt(index).isInstalled()) {
                return;
            }
        }
        // TODO Implement atomic delete if package is unused
        // It is currently possible that the package will be deleted even if it is installed
        // after this method returns.
        mHandler.post(() -> mDeletePackageHelper.deletePackageX(
                packageName, PackageManager.VERSION_CODE_HIGHEST,
                0, PackageManager.DELETE_ALL_USERS, true /*removedBySystem*/));
    }

    void deletePreloadsFileCache() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CLEAR_APP_CACHE,
                "deletePreloadsFileCache");
        File dir = Environment.getDataPreloadsFileCacheDirectory();
        Slog.i(PackageManagerService.TAG, "Deleting preloaded file cache " + dir);
        FileUtils.deleteContents(dir);
    }

    void setSystemAppHiddenUntilInstalled(@NonNull Computer snapshot, String packageName,
            boolean hidden) {
        final int callingUid = Binder.getCallingUid();
        final boolean calledFromSystemOrPhone = callingUid == Process.PHONE_UID
                || callingUid == Process.SYSTEM_UID;
        if (!calledFromSystemOrPhone) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "setSystemAppHiddenUntilInstalled");
        }

        final PackageStateInternal stateRead = snapshot.getPackageStateInternal(packageName);
        if (stateRead == null || !stateRead.isSystem() || stateRead.getPkg() == null) {
            return;
        }
        if (stateRead.getPkg().isCoreApp() && !calledFromSystemOrPhone) {
            throw new SecurityException("Only system or phone callers can modify core apps");
        }

        commitPackageStateMutation(null, mutator -> {
            mutator.forPackage(packageName)
                    .setHiddenUntilInstalled(hidden);
            mutator.forDisabledSystemPackage(packageName)
                    .setHiddenUntilInstalled(hidden);
        });
    }

    boolean setSystemAppInstallState(@NonNull Computer snapshot, String packageName,
            boolean installed, int userId) {
        final int callingUid = Binder.getCallingUid();
        final boolean calledFromSystemOrPhone = callingUid == Process.PHONE_UID
                || callingUid == Process.SYSTEM_UID;
        if (!calledFromSystemOrPhone) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "setSystemAppHiddenUntilInstalled");
        }

        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        // The target app should always be in system
        if (packageState == null || !packageState.isSystem() || packageState.getPkg() == null) {
            return false;
        }
        if (packageState.getPkg().isCoreApp() && !calledFromSystemOrPhone) {
            throw new SecurityException("Only system or phone callers can modify core apps");
        }
        // Check if the install state is the same
        if (packageState.getUserStateOrDefault(userId).isInstalled() == installed) {
            return false;
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            if (installed) {
                // install the app from uninstalled state
                mInstallPackageHelper.installExistingPackageAsUser(
                        packageName,
                        userId,
                        PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                        PackageManager.INSTALL_REASON_DEVICE_SETUP,
                        null,
                        null);
                return true;
            }

            // uninstall the app from installed state
            deletePackageVersioned(
                    new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    new PackageManager.LegacyPackageDeleteObserver(null).getBinder(),
                    userId,
                    PackageManager.DELETE_SYSTEM_APP);
            return true;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    void finishPackageInstall(int token, boolean didLaunch) {
        PackageManagerServiceUtils.enforceSystemOrRoot(
                "Only the system is allowed to finish installs");

        if (PackageManagerService.DEBUG_INSTALL) {
            Slog.v(PackageManagerService.TAG, "BM finishing package install for " + token);
        }
        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "restore", token);

        final Message msg = mHandler.obtainMessage(PackageManagerService.POST_INSTALL, token,
                didLaunch ? 1 : 0);
        mHandler.sendMessage(msg);
    }

    void checkPackageStartable(@NonNull Computer snapshot, @NonNull String packageName,
            @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        if (snapshot.getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        if (!mUserManager.exists(userId)) {
            throw new SecurityException("User doesn't exist");
        }
        snapshot.enforceCrossUserPermission(callingUid, userId, false, false,
                "checkPackageStartable");
        switch (snapshot.getPackageStartability(mSafeMode, packageName, callingUid, userId)) {
            case PACKAGE_STARTABILITY_NOT_FOUND:
                throw new SecurityException("Package " + packageName + " was not found!");
            case PACKAGE_STARTABILITY_NOT_SYSTEM:
                throw new SecurityException("Package " + packageName + " not a system app!");
            case PACKAGE_STARTABILITY_FROZEN:
                throw new SecurityException("Package " + packageName + " is currently frozen!");
            case PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED:
                throw new SecurityException("Package " + packageName + " is not encryption aware!");
            case PACKAGE_STARTABILITY_OK:
            default:
        }
    }

    void setPackageStoppedState(@NonNull Computer snapshot, @NonNull String packageName,
            boolean stopped, @UserIdInt int userId) {
        if (!mUserManager.exists(userId)) return;
        final int callingUid = Binder.getCallingUid();
        boolean wasStopped = false;
        if (snapshot.getInstantAppPackageName(callingUid) == null) {
            final int permission = mContext.checkCallingOrSelfPermission(
                    Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
            final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
            if (!allowedByPermission
                    && !ArrayUtils.contains(snapshot.getPackagesForUid(callingUid), packageName)) {
                throw new SecurityException(
                        "Permission Denial: attempt to change stopped state from pid="
                                + Binder.getCallingPid()
                                + ", uid=" + callingUid + ", package=" + packageName);
            }
            snapshot.enforceCrossUserPermission(callingUid, userId,
                    true /* requireFullPermission */, true /* checkShell */, "stop package");

            final PackageStateInternal packageState =
                    snapshot.getPackageStateForInstalledAndFiltered(
                            packageName, callingUid, userId);
            final PackageUserState packageUserState = packageState == null
                    ? null : packageState.getUserStateOrDefault(userId);
            if (packageState != null && packageUserState.isStopped() != stopped) {
                boolean wasNotLaunched = packageUserState.isNotLaunched();
                wasStopped = packageUserState.isStopped();
                commitPackageStateMutation(null, packageName, state -> {
                    PackageUserStateWrite userState = state.userState(userId);
                    userState.setStopped(stopped);
                    if (wasNotLaunched) {
                        userState.setNotLaunched(false);
                    }
                });

                if (wasNotLaunched) {
                    final String installerPackageName =
                            packageState.getInstallSource().mInstallerPackageName;
                    if (installerPackageName != null) {
                        notifyFirstLaunch(packageName, installerPackageName, userId);
                    }
                }

                scheduleWritePackageRestrictions(userId);
            }
        }

        // If this would cause the app to leave force-stop, then also make sure to unhibernate the
        // app if needed.
        if (!stopped) {
            mHandler.post(() -> {
                AppHibernationManagerInternal ah =
                        mInjector.getLocalService(AppHibernationManagerInternal.class);
                if (ah != null && ah.isHibernatingForUser(packageName, userId)) {
                    ah.setHibernatingForUser(packageName, userId, false);
                    ah.setHibernatingGlobally(packageName, false);
                }
            });
            // Send UNSTOPPED broadcast if necessary
            if (wasStopped && Flags.stayStopped()) {
                final PackageManagerInternal pmi =
                        mInjector.getLocalService(PackageManagerInternal.class);
                final int [] userIds = resolveUserIds(userId);
                final SparseArray<int[]> broadcastAllowList =
                        snapshotComputer().getVisibilityAllowLists(packageName, userIds);
                final Bundle extras = new Bundle();
                extras.putInt(Intent.EXTRA_UID, pmi.getPackageUid(packageName, 0, userId));
                extras.putInt(Intent.EXTRA_USER_HANDLE, userId);
                extras.putLong(Intent.EXTRA_TIME, SystemClock.elapsedRealtime());
                mHandler.post(() -> {
                    mBroadcastHelper.sendPackageBroadcast(Intent.ACTION_PACKAGE_UNSTOPPED,
                            packageName, extras,
                            Intent.FLAG_RECEIVER_REGISTERED_ONLY, null, null,
                            userIds, null, broadcastAllowList, null,
                            null);
                });
                mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_UNSTOPPED,
                        packageName, extras, userIds, null /* instantUserIds */,
                        broadcastAllowList, mHandler, null /* filterExtras */);
            }
        }
    }

    void notifyComponentUsed(@NonNull Computer snapshot, @NonNull String packageName,
            @UserIdInt int userId, @Nullable String recentCallingPackage,
            @NonNull String debugInfo) {
        synchronized (mLock) {
            final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
            // If the package doesn't exist, don't need to proceed to setPackageStoppedState.
            if (pkgSetting == null) {
                return;
            }
            if (pkgSetting.getUserStateOrDefault(userId).isQuarantined()) {
                Slog.i(TAG,
                        "Component is quarantined+suspended but being used: "
                                + packageName + " by " + recentCallingPackage + ", debugInfo: "
                                + debugInfo);
            }
        }
        PackageManagerService.this
                .setPackageStoppedState(snapshot, packageName, false /* stopped */,
                        userId);
    }

    public class IPackageManagerImpl extends IPackageManagerBase {

        public IPackageManagerImpl() {
            super(PackageManagerService.this, mContext, mDexOptHelper, mModuleInfoProvider,
                    mPreferredActivityHelper, mResolveIntentHelper, mDomainVerificationManager,
                    mDomainVerificationConnection, mInstallerService, mPackageProperty,
                    mResolveComponentName, mInstantAppResolverSettingsComponent,
                    mServicesExtensionPackageName, mSharedSystemSharedLibraryPackageName);
        }

        @Override
        public void checkPackageStartable(String packageName, int userId) {
            PackageManagerService.this
                    .checkPackageStartable(snapshotComputer(), packageName, userId);
        }

        @Override
        public void clearApplicationProfileData(String packageName) {
            PackageManagerServiceUtils.enforceSystemOrRootOrShell(
                    "Only the system or shell can clear all profile data");

            final Computer snapshot = snapshotComputer();
            final AndroidPackage pkg = snapshot.getPackage(packageName);
            try (PackageFreezer ignored =
                            freezePackage(packageName, UserHandle.USER_ALL,
                                    "clearApplicationProfileData",
                                    ApplicationExitInfo.REASON_OTHER, null /* request */)) {
                synchronized (mInstallLock) {
                    mAppDataHelper.clearAppProfilesLIF(pkg);
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CLEAR_APP_USER_DATA)
        @Override
        public void clearApplicationUserData(final String packageName,
                final IPackageDataObserver observer, final int userId) {
            clearApplicationUserData_enforcePermission();

            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                    false /* checkShell */, "clear application data");

            if (snapshot.getPackageStateForInstalledAndFiltered(
                    packageName, callingUid, userId) == null) {
                if (observer != null) {
                    mHandler.post(() -> {
                        try {
                            observer.onRemoveCompleted(packageName, false);
                        } catch (RemoteException e) {
                            Log.i(TAG, "Observer no longer exists.");
                        }
                    });
                }
                return;
            }
            if (mProtectedPackages.isPackageDataProtected(userId, packageName)) {
                throw new SecurityException("Cannot clear data for a protected package: "
                        + packageName);
            }
            final int callingPid = Binder.getCallingPid();
            EventLog.writeEvent(EventLogTags.PM_CLEAR_APP_DATA_CALLER, callingPid, callingUid,
                    packageName);

            // Queue up an async operation since the package deletion may take a little while.
            mHandler.post(new Runnable() {
                public void run() {
                    mHandler.removeCallbacks(this);
                    final boolean succeeded;
                    try (PackageFreezer freezer = freezePackage(packageName, UserHandle.USER_ALL,
                            "clearApplicationUserData",
                            ApplicationExitInfo.REASON_USER_REQUESTED, null /* request */)) {
                        synchronized (mInstallLock) {
                            succeeded = clearApplicationUserDataLIF(snapshotComputer(), packageName,
                                    userId);
                        }
                        mInstantAppRegistry.deleteInstantApplicationMetadata(packageName, userId);
                        synchronized (mLock) {
                            if (succeeded) {
                                resetComponentEnabledSettingsIfNeededLPw(packageName, userId);
                            }
                        }
                    }
                    if (succeeded) {
                        // invoke DeviceStorageMonitor's update method to clear any notifications
                        DeviceStorageMonitorInternal dsm = LocalServices
                                .getService(DeviceStorageMonitorInternal.class);
                        if (dsm != null) {
                            dsm.checkMemory();
                        }
                        if (checkPermission(Manifest.permission.SUSPEND_APPS, packageName, userId)
                                == PERMISSION_GRANTED) {
                            final Computer snapshot = snapshotComputer();
                            unsuspendForSuspendingPackage(snapshot, packageName, userId);
                            removeAllDistractingPackageRestrictions(snapshot, userId);
                            synchronized (mLock) {
                                flushPackageRestrictionsAsUserInternalLocked(userId);
                            }
                        }
                    }
                    if (observer != null) {
                        try {
                            observer.onRemoveCompleted(packageName, succeeded);
                        } catch (RemoteException e) {
                            Log.i(TAG, "Observer no longer exists.");
                        }
                    } //end if observer
                } //end run
            });
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
        @Override
        public void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage) {
            clearCrossProfileIntentFilters_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            enforceOwnerRights(snapshot, ownerPackage, callingUid);
            PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
            PackageManagerService.this.mInjector.getCrossProfileIntentFilterHelper()
                            .clearCrossProfileIntentFilters(sourceUserId, ownerPackage,
                                    null);
            scheduleWritePackageRestrictions(sourceUserId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
        @Override
        public boolean removeCrossProfileIntentFilter(IntentFilter intentFilter,
                String ownerPackage,
                int sourceUserId,
                int targetUserId, int flags) {
            removeCrossProfileIntentFilter_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            enforceOwnerRights(snapshotComputer(), ownerPackage, callingUid);
            mUserManager.enforceCrossProfileIntentFilterAccess(sourceUserId, targetUserId,
                    callingUid, /* addCrossProfileIntentFilter */ false);
            PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                    UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);

            boolean removedMatchingFilter = false;
            synchronized (mLock) {
                CrossProfileIntentResolver resolver =
                        mSettings.editCrossProfileIntentResolverLPw(sourceUserId);

                ArraySet<CrossProfileIntentFilter> set =
                        new ArraySet<>(resolver.filterSet());
                for (int i = 0; i < set.size(); i++) {
                    final CrossProfileIntentFilter filter = set.valueAt(i);
                    if (IntentFilter.filterEquals(filter.mFilter, intentFilter)
                            && filter.getOwnerPackage().equals(ownerPackage)
                            && filter.getTargetUserId() == targetUserId
                            && filter.getFlags() == flags) {
                        resolver.removeFilter(filter);
                        removedMatchingFilter = true;
                        break;
                    }
                }
            }
            if (removedMatchingFilter) {
                scheduleWritePackageRestrictions(sourceUserId);
            }
            return removedMatchingFilter;
        }

        @Override
        public final void deleteApplicationCacheFiles(final String packageName,
                final IPackageDataObserver observer) {
            final int userId = UserHandle.getCallingUserId();
            deleteApplicationCacheFilesAsUser(packageName, userId, observer);
        }

        @Override
        public void deleteApplicationCacheFilesAsUser(final String packageName, final int userId,
                final IPackageDataObserver observer) {
            final int callingUid = Binder.getCallingUid();
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERNAL_DELETE_CACHE_FILES)
                    != PackageManager.PERMISSION_GRANTED) {
                // If the caller has the old delete cache permission, silently ignore.  Else throw.
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.DELETE_CACHE_FILES)
                        == PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Calling uid " + callingUid + " does not have " +
                            android.Manifest.permission.INTERNAL_DELETE_CACHE_FILES +
                            ", silently ignoring");
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERNAL_DELETE_CACHE_FILES, null);
            }
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId, /* requireFullPermission= */ true,
                    /* checkShell= */ false, "delete application cache files");
            final int hasAccessInstantApps = mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_INSTANT_APPS);
            final int callingPid = Binder.getCallingPid();
            EventLog.writeEvent(EventLogTags.PM_CLEAR_APP_DATA_CALLER, callingPid, callingUid,
                    packageName);

            // Queue up an async operation since the package deletion may take a little while.
            mHandler.post(() -> {
                // Snapshot in the Handler Runnable since this may be deferred quite a bit
                // TODO: Is this and the later mInstallLock re-snapshot necessary?
                final Computer newSnapshot = snapshotComputer();
                final PackageStateInternal ps = newSnapshot.getPackageStateInternal(packageName);
                boolean doClearData = true;
                if (ps != null) {
                    final boolean targetIsInstantApp =
                            ps.getUserStateOrDefault(UserHandle.getUserId(callingUid)).isInstantApp();
                    doClearData = !targetIsInstantApp
                            || hasAccessInstantApps == PackageManager.PERMISSION_GRANTED;
                }
                if (doClearData) {
                    synchronized (mInstallLock) {
                        final int flags = FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL;
                        // Snapshot again after mInstallLock?
                        final AndroidPackage pkg = snapshotComputer().getPackage(packageName);
                        // We're only clearing cache files, so we don't care if the
                        // app is unfrozen and still able to run
                        mAppDataHelper.clearAppDataLIF(pkg, userId,
                                flags | Installer.FLAG_CLEAR_CACHE_ONLY);
                        mAppDataHelper.clearAppDataLIF(pkg, userId,
                                flags | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
                    }
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, true);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                }
            });
        }

        @Override
        public void enterSafeMode() {
            PackageManagerServiceUtils.enforceSystemOrRoot(
                    "Only the system can request entering safe mode");

            if (!mSystemReady) {
                mSafeMode = true;
            }
        }

        @Override
        public void extendVerificationTimeout(int verificationId, int verificationCodeAtTimeout,
                long millisecondsToDelay) {
            // Negative ids correspond to testing verifiers and will be silently enforced in
            // the handler thread.
            if (verificationId >= 0) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                        "Only package verification agents can extend verification timeouts");
            }
            final int callingUid = Binder.getCallingUid();

            mHandler.post(() -> {
                final int id = verificationId >= 0 ? verificationId : -verificationId;
                final PackageVerificationState state = mPendingVerification.get(id);
                if (state == null || !state.extendTimeout(callingUid)) {
                    // Invalid uid or already extended.
                    return;
                }

                final PackageVerificationResponse response = new PackageVerificationResponse(
                        verificationCodeAtTimeout, callingUid);

                long delay = millisecondsToDelay;
                if (delay > PackageManager.MAXIMUM_VERIFICATION_TIMEOUT) {
                    delay = PackageManager.MAXIMUM_VERIFICATION_TIMEOUT;
                }
                if (delay < 0) {
                    delay = 0;
                }

                final Message msg = mHandler.obtainMessage(PackageManagerService.PACKAGE_VERIFIED);
                msg.arg1 = id;
                msg.obj = response;
                mHandler.sendMessageDelayed(msg, delay);
            });
        }

        @WorkerThread
        @Override
        public void flushPackageRestrictionsAsUser(int userId) {
            final Computer snapshot = snapshotComputer();
            final int callingUid = Binder.getCallingUid();
            if (snapshot.getInstantAppPackageName(callingUid) != null) {
                return;
            }
            if (!mUserManager.exists(userId)) {
                return;
            }
            snapshot.enforceCrossUserPermission(callingUid, userId,
                    false /* requireFullPermission*/, false /* checkShell */,
                    "flushPackageRestrictions");
            synchronized (mLock) {
                flushPackageRestrictionsAsUserInternalLocked(userId);
            }
        }


        @android.annotation.EnforcePermission(android.Manifest.permission.CLEAR_APP_CACHE)
        @Override
        public void freeStorage(final String volumeUuid, final long freeStorageSize,
                final @StorageManager.AllocateFlags int flags, final IntentSender pi) {
            freeStorage_enforcePermission();
            mHandler.post(() -> {
                boolean success = false;
                try {
                    PackageManagerService.this.freeStorage(volumeUuid, freeStorageSize, flags);
                    success = true;
                } catch (IOException e) {
                    Slog.w(TAG, e);
                }
                if (pi != null) {
                    try {
                        final BroadcastOptions options = BroadcastOptions.makeBasic();
                        options.setPendingIntentBackgroundActivityLaunchAllowed(false);
                        pi.sendIntent(null, success ? 1 : 0, null /* intent */,
                                null /* onFinished*/, null /* handler */,
                                null /* requiredPermission */, options.toBundle());
                    } catch (SendIntentException e) {
                        Slog.w(TAG, e);
                    }
                }
            });
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CLEAR_APP_CACHE)
        @Override
        public void freeStorageAndNotify(final String volumeUuid, final long freeStorageSize,
                final @StorageManager.AllocateFlags int flags, final IPackageDataObserver observer) {
            freeStorageAndNotify_enforcePermission();
            mHandler.post(() -> {
                boolean success = false;
                try {
                    PackageManagerService.this.freeStorage(volumeUuid, freeStorageSize, flags);
                    success = true;
                } catch (IOException e) {
                    Slog.w(PackageManagerService.TAG, e);
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(null, success);
                    } catch (RemoteException e) {
                        Slog.w(PackageManagerService.TAG, e);
                    }
                }
            });
        }

        @Override
        public ChangedPackages getChangedPackages(int sequenceNumber, int userId) {
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            if (snapshot.getInstantAppPackageName(callingUid) != null) {
                return null;
            }
            if (!mUserManager.exists(userId)) {
                return null;
            }
            snapshot.enforceCrossUserPermission(callingUid, userId, false, false,
                    "getChangedPackages");
            final ChangedPackages changedPackages = mChangedPackagesTracker.getChangedPackages(
                    sequenceNumber, userId);

            if (changedPackages != null) {
                final List<String> packageNames = changedPackages.getPackageNames();
                for (int index = packageNames.size() - 1; index >= 0; index--) {
                    // Filter out the changes if the calling package should not be able to see it.
                    final PackageStateInternal packageState =
                            snapshot.getPackageStateInternal(packageNames.get(index));
                    if (snapshot.shouldFilterApplication(packageState, callingUid, userId)) {
                        packageNames.remove(index);
                    }
                }
            }

            return changedPackages;
        }

        @Override
        public byte[] getDomainVerificationBackup(int userId) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only the system may call getDomainVerificationBackup()");
            }

            try {
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    TypedXmlSerializer serializer = Xml.resolveSerializer(output);
                    mDomainVerificationManager.writeSettings(snapshotComputer(), serializer, true,
                            userId);
                    return output.toByteArray();
                }
            } catch (Exception e) {
                if (PackageManagerService.DEBUG_BACKUP) {
                    Slog.e(PackageManagerService.TAG, "Unable to write domain verification for backup", e);
                }
                return null;
            }
        }

        @Override
        public IBinder getHoldLockToken() {
            if (!Build.IS_DEBUGGABLE) {
                throw new SecurityException("getHoldLockToken requires a debuggable build");
            }

            mContext.enforceCallingPermission(
                    Manifest.permission.INJECT_EVENTS,
                    "getHoldLockToken requires INJECT_EVENTS permission");

            final Binder token = new Binder();
            token.attachInterface(this, "holdLock:" + Binder.getCallingUid());
            return token;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.ACCESS_INSTANT_APPS)
        @Override
        public String getInstantAppAndroidId(String packageName, int userId) {
            getInstantAppAndroidId_enforcePermission();
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    true /* requireFullPermission */, false /* checkShell */,
                    "getInstantAppAndroidId");
            // Make sure the target is an Instant App.
            if (!snapshot.isInstantApp(packageName, userId)) {
                return null;
            }
            return mInstantAppRegistry.getInstantAppAndroidId(packageName, userId);
        }

        @Override
        public byte[] getInstantAppCookie(String packageName, int userId) {
            if (HIDE_EPHEMERAL_APIS) {
                return null;
            }

            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    true /* requireFullPermission */, false /* checkShell */,
                    "getInstantAppCookie");
            if (!snapshot.isCallerSameApp(packageName, Binder.getCallingUid())) {
                return null;
            }
            PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
            if (packageState == null || packageState.getPkg() == null) {
                return null;
            }
            return mInstantAppRegistry.getInstantAppCookie(packageState.getPkg(), userId);
        }

        @Override
        public Bitmap getInstantAppIcon(String packageName, int userId) {
            if (HIDE_EPHEMERAL_APIS) {
                return null;
            }

            final Computer snapshot = snapshotComputer();
            if (!snapshot.canViewInstantApps(Binder.getCallingUid(), userId)) {
                mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_INSTANT_APPS,
                        "getInstantAppIcon");
            }
            snapshot.enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    true /* requireFullPermission */, false /* checkShell */,
                    "getInstantAppIcon");

            return mInstantAppRegistry.getInstantAppIcon(packageName, userId);
        }

        @Override
        public ParceledListSlice<InstantAppInfo> getInstantApps(int userId) {
            if (HIDE_EPHEMERAL_APIS) {
                return null;
            }

            final Computer snapshot = snapshotComputer();
            if (!snapshot.canViewInstantApps(Binder.getCallingUid(), userId)) {
                mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_INSTANT_APPS,
                        "getEphemeralApplications");
            }
            snapshot.enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    true /* requireFullPermission */, false /* checkShell */,
                    "getEphemeralApplications");

            List<InstantAppInfo> instantApps = mInstantAppRegistry.getInstantApps(snapshot, userId);
            if (instantApps != null) {
                return new ParceledListSlice<>(instantApps);
            }
            return null;
        }

        @Override
        public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
            return mPreferredActivityHelper.getLastChosenActivity(snapshotComputer(), intent,
                    resolvedType, flags);
        }

        @Override
        public IntentSender getLaunchIntentSenderForPackage(String packageName, String callingPackage,
                String featureId, int userId) throws RemoteException {
            return mResolveIntentHelper.getLaunchIntentSenderForPackage(snapshotComputer(),
                    packageName, callingPackage, featureId, userId);
        }

        @Override
        public List<String> getMimeGroup(String packageName, String mimeGroup) {
            final Computer snapshot = snapshotComputer();
            enforceOwnerRights(snapshot, packageName, Binder.getCallingUid());
            return getMimeGroupInternal(snapshot, packageName, mimeGroup);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS)
        @Override
        public int getMoveStatus(int moveId) {
            getMoveStatus_enforcePermission();
            return mMoveCallbacks.mLastStatus.get(moveId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.GET_APP_METADATA)
        @Override
        public ParcelFileDescriptor getAppMetadataFd(String packageName, int userId) {
            getAppMetadataFd_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            final PackageStateInternal ps = snapshot.getPackageStateForInstalledAndFiltered(
                    packageName, callingUid, userId);
            if (ps == null) {
                throw new ParcelableException(
                        new PackageManager.NameNotFoundException(packageName));
            }
            String filePath = ps.getAppMetadataFilePath();
            if (filePath != null) {
                File file = new File(filePath);
                try {
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                } catch (FileNotFoundException e) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public String getPermissionControllerPackageName() {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            final Computer snapshot = snapshotComputer();
            if (snapshot.getPackageStateForInstalledAndFiltered(
                    mRequiredPermissionControllerPackage, callingUid, callingUserId) != null) {
                return mRequiredPermissionControllerPackage;
            }

            throw new IllegalStateException("PermissionController is not found");
        }

        @Override
        @SuppressWarnings("GuardedBy")
        public int getRuntimePermissionsVersion(@UserIdInt int userId) {
            Preconditions.checkArgumentNonnegative(userId);
            enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
                    "getRuntimePermissionVersion");
            return mSettings.getDefaultRuntimePermissionsVersion(userId);
        }

        @Override
        public String getSplashScreenTheme(@NonNull String packageName, int userId) {
            final Computer snapshot = snapshotComputer();
            final int callingUid = Binder.getCallingUid();
            snapshot.enforceCrossUserPermission(
                    callingUid, userId, false /* requireFullPermission */,
                    false /* checkShell */, "getSplashScreenTheme");
            PackageStateInternal packageState = snapshot.getPackageStateForInstalledAndFiltered(
                    packageName, callingUid, userId);
            return packageState == null ? null
                    : packageState.getUserStateOrDefault(userId).getSplashScreenTheme();
        }

        @Override
        @PackageManager.UserMinAspectRatio
        public int getUserMinAspectRatio(@NonNull String packageName, int userId) {
            final Computer snapshot = snapshotComputer();
            final int callingUid = Binder.getCallingUid();
            final PackageStateInternal packageState = snapshot
                    .getPackageStateForInstalledAndFiltered(packageName, callingUid, userId);
            return packageState == null ? USER_MIN_ASPECT_RATIO_UNSET
                    : packageState.getUserStateOrDefault(userId).getMinAspectRatio();
        }

        @Override
        public Bundle getSuspendedPackageAppExtras(String packageName, int userId) {
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshot();
            if (snapshot.getPackageUid(packageName, 0, userId) != callingUid) {
                throw new SecurityException("Calling package " + packageName
                        + " does not belong to calling uid " + callingUid);
            }
            return SuspendPackageHelper
                    .getSuspendedPackageAppExtras(snapshot, packageName, userId, callingUid);
        }

        @Override
        public String getSuspendingPackage(String packageName, int userId) {
            try {
                final int callingUid = Binder.getCallingUid();
                final Computer snapshot = snapshot();
                // This will do visibility checks as well.
                if (!snapshot.isPackageSuspendedForUser(packageName, userId)) {
                    return null;
                }
                final UserPackage suspender = mSuspendPackageHelper.getSuspendingPackage(
                        snapshot, packageName, userId, callingUid);
                return suspender != null ? suspender.packageName : null;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }

        @Override
        public @NonNull ParceledListSlice<FeatureInfo> getSystemAvailableFeatures() {
            // allow instant applications
            ArrayList<FeatureInfo> res;
            synchronized (mAvailableFeatures) {
                res = new ArrayList<>(mAvailableFeatures.size() + 1);
                res.addAll(mAvailableFeatures.values());
            }
            final FeatureInfo fi = new FeatureInfo();
            fi.reqGlEsVersion = SystemProperties.getInt("ro.opengles.version",
                    FeatureInfo.GL_ES_VERSION_UNDEFINED);
            res.add(fi);

            return new ParceledListSlice<>(res);
        }

        @Override
        public @NonNull List<String> getInitialNonStoppedSystemPackages() {
            return mInitialNonStoppedSystemPackages != null
                    ? new ArrayList<>(mInitialNonStoppedSystemPackages) : new ArrayList<>();
        }

        @Override
        public String[] getUnsuspendablePackagesForUser(String[] packageNames, int userId) {
            Objects.requireNonNull(packageNames, "packageNames cannot be null");
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "getUnsuspendablePackagesForUser");
            final int callingUid = Binder.getCallingUid();
            if (UserHandle.getUserId(callingUid) != userId) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "Calling uid " + callingUid
                                + " cannot query getUnsuspendablePackagesForUser for user "
                                + userId);
            }
            return mSuspendPackageHelper.getUnsuspendablePackagesForUser(snapshotComputer(),
                    packageNames, userId, callingUid);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.PACKAGE_VERIFICATION_AGENT)
        @Override
        public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
            getVerifierDeviceIdentity_enforcePermission();

            synchronized (mLock) {
                return mSettings.getVerifierDeviceIdentityLPw(mLiveComputer);
            }
        }

        @Override
        public void makeProviderVisible(int recipientUid, @NonNull String visibleAuthority) {
            final Computer snapshot = snapshotComputer();
            final int recipientUserId = UserHandle.getUserId(recipientUid);
            final ProviderInfo providerInfo =
                    snapshot.getGrantImplicitAccessProviderInfo(recipientUid, visibleAuthority);
            if (providerInfo == null) {
                return;
            }
            int visibleUid = providerInfo.applicationInfo.uid;
            PackageManagerService.this.grantImplicitAccess(snapshot, recipientUserId,
                    null /*Intent*/, UserHandle.getAppId(recipientUid), visibleUid,
                    false /*direct*/, false /* retainOnUpdate */);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MAKE_UID_VISIBLE)
        @Override
        public void makeUidVisible(int recipientUid, int visibleUid) {
            makeUidVisible_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            final int recipientUserId = UserHandle.getUserId(recipientUid);
            final int visibleUserId = UserHandle.getUserId(visibleUid);
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, recipientUserId,
                    false /* requireFullPermission */, false /* checkShell */, "makeUidVisible");
            snapshot.enforceCrossUserPermission(callingUid, visibleUserId,
                    false /* requireFullPermission */, false /* checkShell */, "makeUidVisible");
            snapshot.enforceCrossUserPermission(recipientUid, visibleUserId,
                    false /* requireFullPermission */, false /* checkShell */, "makeUidVisible");

            PackageManagerService.this.grantImplicitAccess(snapshot, recipientUserId,
                    null /*Intent*/, UserHandle.getAppId(recipientUid), visibleUid,
                    false /*direct*/, false /* retainOnUpdate */);
        }

        @Override
        public void holdLock(IBinder token, int durationMs) {
            mTestUtilityService.verifyHoldLockToken(token);

            synchronized (mLock) {
                SystemClock.sleep(durationMs);
            }
        }

        /**
         * @hide
         */
        @Override
        public int installExistingPackageAsUser(String packageName, int userId, int installFlags,
                int installReason, List<String> whiteListedPermissions) {
            return mInstallPackageHelper.installExistingPackageAsUser(packageName, userId, installFlags,
                    installReason, whiteListedPermissions, null).first;
        }

        @Override
        public boolean isAutoRevokeWhitelisted(String packageName) {
            int mode = mInjector.getSystemService(AppOpsManager.class).checkOpNoThrow(
                    AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                    Binder.getCallingUid(), packageName);
            return mode == MODE_IGNORED;
        }

        @Override
        public boolean isPackageStateProtected(@NonNull String packageName, @UserIdInt int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingAppId = UserHandle.getAppId(callingUid);

            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                    true /*checkShell*/, "isPackageStateProtected");

            if (!PackageManagerServiceUtils.isSystemOrRoot(callingAppId)
                    && snapshot.checkUidPermission(MANAGE_DEVICE_ADMINS, callingUid)
                    != PERMISSION_GRANTED) {
                throw new SecurityException("Caller must have the "
                        + MANAGE_DEVICE_ADMINS + " permission.");
            }

            return mProtectedPackages.isPackageStateProtected(userId, packageName);
        }

        @Override
        public boolean isProtectedBroadcast(String actionName) {
            if (actionName != null) {
                // TODO: remove these terrible hacks
                if (actionName.startsWith("android.net.netmon.lingerExpired")
                        || actionName.startsWith("com.android.server.sip.SipWakeupTimer")
                        || actionName.startsWith("com.android.internal.telephony.data-reconnect")
                        || actionName.startsWith("android.net.netmon.launchCaptivePortalApp")) {
                    return true;
                }
            }
            // allow instant applications
            synchronized (mProtectedBroadcasts) {
                return mProtectedBroadcasts.contains(actionName);
            }
        }

        /**
         * Logs process start information (including base APK hash) to the security log.
         * @hide
         */
        @Override
        public void logAppProcessStartIfNeeded(String packageName, String processName, int uid,
                String seinfo, String apkFile, int pid) {
            final Computer snapshot = snapshotComputer();
            if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
                return;
            }
            if (!SecurityLog.isLoggingEnabled()) {
                return;
            }
            mProcessLoggingHandler.logAppProcessStart(mContext,
                    LocalServices.getService(PackageManagerInternal.class), apkFile, packageName,
                    processName, uid, seinfo, pid);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MOVE_PACKAGE)
        @Override
        public int movePackage(final String packageName, final String volumeUuid) {
            movePackage_enforcePermission();

            final int callingUid = Binder.getCallingUid();
            final UserHandle user = new UserHandle(UserHandle.getUserId(callingUid));
            final int moveId = mNextMoveId.getAndIncrement();
            mHandler.post(() -> {
                try {
                    MovePackageHelper movePackageHelper =
                            new MovePackageHelper(PackageManagerService.this);
                    movePackageHelper.movePackageInternal(
                            packageName, volumeUuid, moveId, callingUid, user);
                } catch (PackageManagerException e) {
                    Slog.w(PackageManagerService.TAG, "Failed to move " + packageName, e);
                    mMoveCallbacks.notifyStatusChanged(moveId, e.error);
                }
            });
            return moveId;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MOVE_PACKAGE)
        @Override
        public int movePrimaryStorage(String volumeUuid) throws RemoteException {
            movePrimaryStorage_enforcePermission();

            final int realMoveId = mNextMoveId.getAndIncrement();
            final Bundle extras = new Bundle();
            extras.putString(VolumeRecord.EXTRA_FS_UUID, volumeUuid);
            mMoveCallbacks.notifyCreated(realMoveId, extras);

            final IPackageMoveObserver callback = new IPackageMoveObserver.Stub() {
                @Override
                public void onCreated(int moveId, Bundle extras) {
                    // Ignored
                }

                @Override
                public void onStatusChanged(int moveId, int status, long estMillis) {
                    mMoveCallbacks.notifyStatusChanged(realMoveId, status, estMillis);
                }
            };

            final StorageManager storage = mInjector.getSystemService(StorageManager.class);
            storage.setPrimaryStorageUuid(volumeUuid, callback);
            return realMoveId;
        }

        @Override
        public void notifyDexLoad(String loadingPackageName,
                Map<String, String> classLoaderContextMap,
                String loaderIsa) {
            int callingUid = Binder.getCallingUid();
            Computer snapshot = snapshot();

            // System server should be able to report dex load on behalf of other apps. E.g., it
            // could potentially resend the notifications in order to migrate the existing dex load
            // info to ART Service.
            if (!PackageManagerServiceUtils.isSystemOrRoot()
                    && !snapshot.isCallerSameApp(
                            loadingPackageName, callingUid, true /* resolveIsolatedUid */)) {
                Slog.w(PackageManagerService.TAG,
                        TextUtils.formatSimple(
                                "Invalid dex load report. loadingPackageName=%s, uid=%d",
                                loadingPackageName, callingUid));
                return;
            }

            UserHandle user = Binder.getCallingUserHandle();
            int userId = user.getIdentifier();

            // Proxy the call to either ART Service or the legacy implementation. If the
            // implementation is switched with the system property, the dex usage info will be
            // incomplete, with these effects:
            //
            // -  Shared dex files may temporarily get compiled for private use.
            // -  Secondary dex files may not get compiled at all.
            // -  Stale compiled artifacts for secondary dex files may not get cleaned up.
            //
            // This recovers in the first background dexopt after the depending apps have been
            // loaded for the first time.

            DexUseManagerLocal dexUseManager = DexOptHelper.getDexUseManagerLocal();
            if (dexUseManager != null) {
                // TODO(chiuwinson): Retrieve filtered snapshot from Computer instance instead.
                try (PackageManagerLocal.FilteredSnapshot filteredSnapshot =
                                LocalManagerRegistry.getManager(PackageManagerLocal.class)
                                        .withFilteredSnapshot(callingUid, user)) {
                    if (loaderIsa != null) {
                        // Check that loaderIsa agrees with the ISA that dexUseManager will
                        // determine.
                        PackageState loadingPkgState =
                                filteredSnapshot.getPackageState(loadingPackageName);
                        // If we don't find the loading package just pass it through and let
                        // dexUseManager throw on it.
                        if (loadingPkgState != null) {
                            String loadingPkgAbi = loadingPkgState.getPrimaryCpuAbi();
                            if (loadingPkgAbi == null) {
                                loadingPkgAbi = Build.SUPPORTED_ABIS[0];
                            }
                            String loadingPkgDexCodeIsa = InstructionSets.getDexCodeInstructionSet(
                                    VMRuntime.getInstructionSet(loadingPkgAbi));
                            if (!loaderIsa.equals(loadingPkgDexCodeIsa)) {
                                // TODO(b/251903639): We make this a wtf to surface any situations
                                // where this argument doesn't correspond to our expectations. Later
                                // it should be turned into an IllegalArgumentException, when we can
                                // assume it's the caller that's wrong rather than us.
                                Log.wtf(TAG,
                                        "Invalid loaderIsa in notifyDexLoad call from "
                                                + loadingPackageName + ", uid " + callingUid
                                                + ": expected " + loadingPkgDexCodeIsa + ", got "
                                                + loaderIsa);
                                return;
                            }
                        }
                    }

                    // This is called from binder, so exceptions thrown here are caught and handled
                    // by it.
                    dexUseManager.notifyDexContainersLoaded(
                            filteredSnapshot, loadingPackageName, classLoaderContextMap);
                }
            } else {
                ApplicationInfo ai =
                        snapshot.getApplicationInfo(loadingPackageName, /*flags*/ 0, userId);
                if (ai == null) {
                    Slog.w(PackageManagerService.TAG,
                            "Loading a package that does not exist for the calling user. package="
                                    + loadingPackageName + ", user=" + userId);
                    return;
                }
                mDexManager.notifyDexLoad(ai, classLoaderContextMap, loaderIsa, userId,
                        Process.isIsolated(callingUid));
            }
        }

        @Override
        public void notifyPackageUse(String packageName, int reason) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            Computer snapshot = snapshotComputer();
            final boolean notify;
            if (snapshot.getInstantAppPackageName(callingUid) != null) {
                notify = snapshot.isCallerSameApp(packageName, callingUid);
            } else {
                notify = !snapshot.isInstantAppInternal(packageName, callingUserId,
                        Process.SYSTEM_UID);
            }
            if (!notify) {
                return;
            }

            notifyPackageUseInternal(packageName, reason);
        }

        @Override
        public void overrideLabelAndIcon(@NonNull ComponentName componentName,
                @NonNull String nonLocalizedLabel, int icon, int userId) {
            if (TextUtils.isEmpty(nonLocalizedLabel)) {
                throw new IllegalArgumentException("Override label should be a valid String");
            }
            updateComponentLabelIcon(componentName, nonLocalizedLabel, icon, userId);
        }

        @Override
        public ParceledListSlice<PackageManager.Property> queryProperty(
                String propertyName, @PackageManager.PropertyLocation int componentType) {
            Objects.requireNonNull(propertyName);
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getCallingUserId();
            final Computer snapshot = snapshotComputer();
            final List<PackageManager.Property> result =
                    mPackageProperty.queryProperty(propertyName, componentType,
                            packageName -> snapshot.getPackageStateForInstalledAndFiltered(
                                    packageName, callingUid, callingUserId) == null
                    );
            if (result == null) {
                return ParceledListSlice.emptyList();
            }
            return new ParceledListSlice<>(result);
        }

        @Override
        public void registerDexModule(String packageName, String dexModulePath,
                boolean isSharedModule,
                IDexModuleRegisterCallback callback) {
            // ART Service doesn't support this explicit dexopting and instead relies on background
            // dexopt for secondary dex files. For compat parity between ART Service and the legacy
            // code it's disabled for both.
            //
            // Also, this API is problematic anyway since it doesn't provide the correct classloader
            // context, so it is hard to produce dexopt artifacts that the runtime can load
            // successfully.
            Slog.i(TAG,
                    "Ignored unsupported registerDexModule call for " + dexModulePath + " in "
                            + packageName);
            DexManager.RegisterDexModuleResult result = new DexManager.RegisterDexModuleResult(
                    false, "registerDexModule call not supported since Android U");

            if (callback != null) {
                mHandler.post(() -> {
                    try {
                        callback.onDexModuleRegistered(dexModulePath, result.success,
                                result.message);
                    } catch (RemoteException e) {
                        Slog.w(PackageManagerService.TAG,
                                "Failed to callback after module registration " + dexModulePath, e);
                    }
                });
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS)
        @Override
        public void registerMoveCallback(IPackageMoveObserver callback) {
            registerMoveCallback_enforcePermission();
            mMoveCallbacks.register(callback);
        }

        @Override
        public void restoreDomainVerification(byte[] backup, int userId) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                throw new SecurityException("Only the system may call restorePreferredActivities()");
            }

            try {
                ByteArrayInputStream input = new ByteArrayInputStream(backup);
                TypedXmlPullParser parser = Xml.resolvePullParser(input);

                // User ID input isn't necessary here as it assumes the user integers match and that
                // the only states inside the backup XML are for the target user.
                mDomainVerificationManager.restoreSettings(snapshotComputer(), parser);
                input.close();
            } catch (Exception e) {
                if (PackageManagerService.DEBUG_BACKUP) {
                    Slog.e(PackageManagerService.TAG, "Exception restoring domain verification: " + e.getMessage());
                }
            }
        }

        @Override
        public void restoreLabelAndIcon(@NonNull ComponentName componentName, int userId) {
            updateComponentLabelIcon(componentName, null, null, userId);
        }

        @Override
        public void sendDeviceCustomizationReadyBroadcast() {
            mContext.enforceCallingPermission(Manifest.permission.SEND_DEVICE_CUSTOMIZATION_READY,
                    "sendDeviceCustomizationReadyBroadcast");

            final long ident = Binder.clearCallingIdentity();
            try {
                BroadcastHelper.sendDeviceCustomizationReadyBroadcast();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setApplicationCategoryHint(String packageName, int categoryHint,
                String callerPackageName) {
            final FunctionalUtils.ThrowingBiFunction<PackageStateMutator.InitialState, Computer,
                    PackageStateMutator.Result> implementation = (initialState, computer) -> {
                if (computer.getInstantAppPackageName(Binder.getCallingUid()) != null) {
                    throw new SecurityException(
                            "Instant applications don't have access to this method");
                }
                mInjector.getSystemService(AppOpsManager.class)
                        .checkPackage(Binder.getCallingUid(), callerPackageName);

                PackageStateInternal packageState = computer.getPackageStateForInstalledAndFiltered(
                        packageName, Binder.getCallingUid(), UserHandle.getCallingUserId());
                if (packageState == null) {
                    throw new IllegalArgumentException("Unknown target package " + packageName);
                }

                if (!Objects.equals(callerPackageName,
                        packageState.getInstallSource().mInstallerPackageName)) {
                    throw new IllegalArgumentException("Calling package " + callerPackageName
                            + " is not installer for " + packageName);
                }

                if (packageState.getCategoryOverride() != categoryHint) {
                    return commitPackageStateMutation(initialState,
                            packageName, state -> state.setCategoryOverride(categoryHint));
                } else {
                    return null;
                }
            };

            PackageStateMutator.Result result =
                    implementation.apply(recordInitialState(), snapshotComputer());
            if (result != null && result.isStateChanged() && !result.isSpecificPackageNull()) {
                // TODO: Specific return value of what state changed?
                // The installer on record might have changed, retry with lock
                synchronized (mPackageStateWriteLock) {
                    result = implementation.apply(recordInitialState(), snapshotComputer());
                }
            }

            if (result != null && result.isCommitted()) {
                scheduleWriteSettings();
            }
        }

        @Override
        public void setApplicationEnabledSetting(String appPackageName,
                int newState, int flags, int userId, String callingPackage) {
            if (!mUserManager.exists(userId)) return;
            if (callingPackage == null) {
                callingPackage = Integer.toString(Binder.getCallingUid());
            }

            setEnabledSettings(List.of(new PackageManager.ComponentEnabledSetting(appPackageName, newState, flags)),
                    userId, callingPackage);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MANAGE_USERS)
        @Override
        public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
                int userId) {
            setApplicationHiddenSettingAsUser_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                    true /* checkShell */, "setApplicationHiddenSetting for user " + userId);

            if (hidden && isPackageDeviceAdmin(packageName, userId)) {
                Slog.w(TAG, "Not hiding package " + packageName + ": has active device admin");
                return false;
            }

            // Do not allow "android" is being disabled
            if ("android".equals(packageName)) {
                Slog.w(TAG, "Cannot hide package: android");
                return false;
            }

            final long callingId = Binder.clearCallingIdentity();
            try {
                final PackageStateInternal packageState = snapshot.getPackageStateInternal(
                        packageName);
                if (packageState == null) {
                    return false;
                }

                final PackageUserStateInternal userState = packageState.getUserStateOrDefault(
                        userId);
                if (userState.isHidden() == hidden || !userState.isInstalled()
                        || snapshot.shouldFilterApplication(packageState, callingUid, userId)) {
                    return false;
                }

                // Cannot hide static shared libs as they are considered
                // a part of the using app (emulating static linking). Also
                // static libs are installed always on internal storage.
                AndroidPackage pkg = packageState.getPkg();
                if (pkg != null) {
                    // Cannot hide SDK libs as they are controlled by SDK manager.
                    if (pkg.getSdkLibraryName() != null) {
                        Slog.w(TAG, "Cannot hide package: " + packageName
                                + " providing SDK library: "
                                + pkg.getSdkLibraryName());
                        return false;
                    }
                    // Cannot hide static shared libs as they are considered
                    // a part of the using app (emulating static linking). Also
                    // static libs are installed always on internal storage.
                    if (pkg.getStaticSharedLibraryName() != null) {
                        Slog.w(TAG, "Cannot hide package: " + packageName
                                + " providing static shared library: "
                                + pkg.getStaticSharedLibraryName());
                        return false;
                    }
                }
                // Only allow protected packages to hide themselves.
                if (hidden && !UserHandle.isSameApp(callingUid, packageState.getAppId())
                        && mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                    Slog.w(TAG, "Not hiding protected package: " + packageName);
                    return false;
                }

                commitPackageStateMutation(null, packageName, packageState1 ->
                        packageState1.userState(userId).setHidden(hidden));

                final Computer newSnapshot = snapshotComputer();
                final PackageStateInternal newPackageState =
                        newSnapshot.getPackageStateInternal(packageName);

                if (hidden) {
                    killApplication(packageName, newPackageState.getAppId(), userId, "hiding pkg",
                            ApplicationExitInfo.REASON_OTHER);
                    mBroadcastHelper.sendApplicationHiddenForUser(
                            packageName, newPackageState, userId,
                            /* packageSender= */ PackageManagerService.this);
                } else {
                    mBroadcastHelper.sendPackageAddedForUser(
                            newSnapshot, packageName, newPackageState, userId,
                            false /* isArchived */, DataLoaderType.NONE,
                            mAppPredictionServicePackage);
                }

                scheduleWritePackageRestrictions(userId);
                return true;
            } finally {
                Binder.restoreCallingIdentity(callingId);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.DELETE_PACKAGES)
        @Override
        public boolean setBlockUninstallForUser(String packageName, boolean blockUninstall,
                int userId) {
            setBlockUninstallForUser_enforcePermission();
            final Computer snapshot = snapshotComputer();
            PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
            if (packageState != null && packageState.getPkg() != null) {
                AndroidPackage pkg = packageState.getPkg();
                // Cannot block uninstall SDK libs as they are controlled by SDK manager.
                if (pkg.getSdkLibraryName() != null) {
                    Slog.w(PackageManagerService.TAG, "Cannot block uninstall of package: " + packageName
                            + " providing SDK library: " + pkg.getSdkLibraryName());
                    return false;
                }
                // Cannot block uninstall of static shared libs as they are
                // considered a part of the using app (emulating static linking).
                // Also static libs are installed always on internal storage.
                if (pkg.getStaticSharedLibraryName() != null) {
                    Slog.w(PackageManagerService.TAG, "Cannot block uninstall of package: " + packageName
                            + " providing static shared library: " + pkg.getStaticSharedLibraryName());
                    return false;
                }
            }
            synchronized (mLock) {
                mSettings.setBlockUninstallLPw(userId, packageName, blockUninstall);
            }

            scheduleWritePackageRestrictions(userId);
            return true;
        }

        @Override
        public void setComponentEnabledSetting(ComponentName componentName,
                int newState, int flags, int userId, String callingPackage) {
            if (!mUserManager.exists(userId)) return;
            if (callingPackage == null) {
                callingPackage = Integer.toString(Binder.getCallingUid());
            }

            setEnabledSettings(List.of(new PackageManager.ComponentEnabledSetting(componentName, newState, flags)),
                    userId, callingPackage);
        }

        @Override
        public void setComponentEnabledSettings(
                List<PackageManager.ComponentEnabledSetting> settings, int userId,
                String callingPackage) {
            if (!mUserManager.exists(userId)) return;
            if (settings == null || settings.isEmpty()) {
                throw new IllegalArgumentException("The list of enabled settings is empty");
            }
            if (callingPackage == null) {
                callingPackage = Integer.toString(Binder.getCallingUid());
            }
            setEnabledSettings(settings, userId, callingPackage);
        }

        @Override
        public String[] setDistractingPackageRestrictionsAsUser(String[] packageNames,
                int restrictionFlags, int userId) {
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            enforceCanSetDistractingPackageRestrictionsAsUser(callingUid, userId,
                    "setDistractingPackageRestrictionsAsUser");
            Objects.requireNonNull(packageNames, "packageNames cannot be null");
            return mDistractingPackageHelper.setDistractingPackageRestrictionsAsUser(snapshot,
                    packageNames, restrictionFlags, userId, callingUid);
        }

        @Override
        public void setHarmfulAppWarning(@NonNull String packageName, @Nullable CharSequence warning,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingAppId = UserHandle.getAppId(callingUid);

            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId, true /*requireFullPermission*/,
                    true /*checkShell*/, "setHarmfulAppInfo");

            if (!PackageManagerServiceUtils.isSystemOrRoot(callingAppId)
                    && snapshot.checkUidPermission(SET_HARMFUL_APP_WARNINGS, callingUid)
                            != PERMISSION_GRANTED) {
                throw new SecurityException("Caller must have the "
                        + SET_HARMFUL_APP_WARNINGS + " permission.");
            }

            PackageStateMutator.Result result = commitPackageStateMutation(null, packageName,
                    packageState -> packageState.userState(userId)
                            .setHarmfulAppWarning(warning == null ? null : warning.toString()));
            if (result.isSpecificPackageNull()) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            scheduleWritePackageRestrictions(userId);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        @Override
        public boolean setInstallLocation(int loc) {
            setInstallLocation_enforcePermission();
            if (getInstallLocation() == loc) {
                return true;
            }
            if (loc == InstallLocationUtils.APP_INSTALL_AUTO
                    || loc == InstallLocationUtils.APP_INSTALL_INTERNAL
                    || loc == InstallLocationUtils.APP_INSTALL_EXTERNAL) {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                        android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION, loc);
                return true;
            }
            return false;
        }

        @Override
        public void setInstallerPackageName(String targetPackage,
                @Nullable String installerPackageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            final FunctionalUtils.ThrowingCheckedFunction<Computer, Boolean, RuntimeException>
                    implementation = snapshot -> {
                if (snapshot.getInstantAppPackageName(callingUid) != null) {
                    return false;
                }

                PackageStateInternal targetPackageState =
                        snapshot.getPackageStateForInstalledAndFiltered(
                                targetPackage, callingUid, callingUserId);
                if (targetPackageState == null) {
                    throw new IllegalArgumentException("Unknown target package: " + targetPackage);
                }

                PackageStateInternal installerPackageState = null;
                if (installerPackageName != null) {
                    installerPackageState = snapshot.getPackageStateForInstalledAndFiltered(
                            installerPackageName, callingUid, callingUserId);
                    if (installerPackageState == null) {
                        throw new IllegalArgumentException("Unknown installer package: "
                                + installerPackageName);
                    }
                }

                SigningDetails callerSigningDetails;
                final int appId = UserHandle.getAppId(callingUid);
                Pair<PackageStateInternal, SharedUserApi> either =
                        snapshot.getPackageOrSharedUser(appId);
                if (either != null) {
                    if (either.first != null) {
                        callerSigningDetails = either.first.getSigningDetails();
                    } else {
                        callerSigningDetails = either.second.getSigningDetails();
                    }
                } else {
                    throw new SecurityException("Unknown calling UID: " + callingUid);
                }

                // Verify: can't set installerPackageName to a package that is
                // not signed with the same cert as the caller.
                if (installerPackageState != null) {
                    if (compareSignatures(callerSigningDetails,
                            installerPackageState.getSigningDetails())
                            != PackageManager.SIGNATURE_MATCH) {
                        throw new SecurityException(
                                "Caller does not have same cert as new installer package "
                                        + installerPackageName);
                    }
                }

                // Verify: if target already has an installer package, it must
                // be signed with the same cert as the caller.
                String targetInstallerPackageName =
                        targetPackageState.getInstallSource().mInstallerPackageName;
                PackageStateInternal targetInstallerPkgSetting = targetInstallerPackageName == null
                        ? null : snapshot.getPackageStateInternal(targetInstallerPackageName);

                if (targetInstallerPkgSetting != null) {
                    if (compareSignatures(callerSigningDetails,
                            targetInstallerPkgSetting.getSigningDetails())
                            != PackageManager.SIGNATURE_MATCH) {
                        throw new SecurityException(
                                "Caller does not have same cert as old installer package "
                                        + targetInstallerPackageName);
                    }
                } else if (mContext.checkCallingOrSelfPermission(
                        Manifest.permission.INSTALL_PACKAGES) != PERMISSION_GRANTED) {
                    // This is probably an attempt to exploit vulnerability b/150857253 of taking
                    // privileged installer permissions when the installer has been uninstalled or
                    // was never set.
                    EventLog.writeEvent(0x534e4554, "150857253", callingUid, "");

                    final long binderToken = Binder.clearCallingIdentity();
                    try {
                        if (mInjector.getCompatibility().isChangeEnabledByUid(
                                PackageManagerService.THROW_EXCEPTION_ON_REQUIRE_INSTALL_PACKAGES_TO_ADD_INSTALLER_PACKAGE,
                                callingUid)) {
                            throw new SecurityException("Neither user " + callingUid
                                    + " nor current process has "
                                    + Manifest.permission.INSTALL_PACKAGES);
                        } else {
                            // If change disabled, fail silently for backwards compatibility
                            return false;
                        }
                    } finally {
                        Binder.restoreCallingIdentity(binderToken);
                    }
                }

                return true;
            };
            PackageStateMutator.InitialState initialState = recordInitialState();
            boolean allowed = implementation.apply(snapshotComputer());
            if (allowed) {
                // TODO: Need to lock around here to handle mSettings.addInstallerPackageNames,
                //  should find an alternative which avoids any race conditions
                final int installerPackageUid = installerPackageName == null
                        ? INVALID_UID : snapshotComputer().getPackageUid(installerPackageName,
                        0 /* flags */, callingUserId);
                PackageStateInternal targetPackageState;
                synchronized (mLock) {
                    PackageStateMutator.Result result = commitPackageStateMutation(initialState,
                            targetPackage, state -> state.setInstaller(installerPackageName,
                                    installerPackageUid));
                    if (result.isPackagesChanged() || result.isStateChanged()) {
                        synchronized (mPackageStateWriteLock) {
                            allowed = implementation.apply(snapshotComputer());
                            if (allowed) {
                                commitPackageStateMutation(null, targetPackage,
                                        state -> state.setInstaller(installerPackageName,
                                                installerPackageUid));
                            } else {
                                return;
                            }
                        }
                    }
                    targetPackageState = snapshotComputer().getPackageStateInternal(targetPackage);
                    mSettings.addInstallerPackageNames(targetPackageState.getInstallSource());
                }
                mAppsFilter.addPackage(snapshotComputer(), targetPackageState);
                scheduleWriteSettings();
            }
        }

        @Override
        public void relinquishUpdateOwnership(String targetPackage) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            final Computer snapshot = snapshotComputer();

            final PackageStateInternal targetPackageState =
                    snapshot.getPackageStateForInstalledAndFiltered(targetPackage, callingUid,
                            callingUserId);
            if (targetPackageState == null) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }

            final String targetUpdateOwnerPackageName =
                    targetPackageState.getInstallSource().mUpdateOwnerPackageName;
            final PackageStateInternal targetUpdateOwnerPkgSetting =
                    targetUpdateOwnerPackageName == null ? null
                            : snapshot.getPackageStateInternal(targetUpdateOwnerPackageName);

            if (targetUpdateOwnerPkgSetting == null) {
                return;
            }

            final int callingAppId = UserHandle.getAppId(callingUid);
            final int targetUpdateOwnerAppId = targetUpdateOwnerPkgSetting.getAppId();
            if (callingAppId != Process.SYSTEM_UID
                    && callingAppId != Process.SHELL_UID
                    && callingAppId != targetUpdateOwnerAppId) {
                throw new SecurityException("Caller is not the current update owner.");
            }

            commitPackageStateMutation(null /* initialState */, targetPackage,
                    state -> state.setUpdateOwner(null /* updateOwnerPackageName */));
            scheduleWriteSettings();
        }

        @Override
        public boolean setInstantAppCookie(String packageName, byte[] cookie, int userId) {
            if (HIDE_EPHEMERAL_APIS) {
                return true;
            }

            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(Binder.getCallingUid(), userId,
                    true /* requireFullPermission */, true /* checkShell */,
                    "setInstantAppCookie");
            if (!snapshot.isCallerSameApp(packageName, Binder.getCallingUid())) {
                return false;
            }

            PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
            if (packageState == null || packageState.getPkg() == null) {
                return false;
            }
            return mInstantAppRegistry.setInstantAppCookie(packageState.getPkg(), cookie,
                    mContext.getPackageManager().getInstantAppCookieMaxBytes(), userId);
        }

        @Override
        public void setKeepUninstalledPackages(List<String> packageList) {
            mContext.enforceCallingPermission(
                    Manifest.permission.KEEP_UNINSTALLED_PACKAGES,
                    "setKeepUninstalledPackages requires KEEP_UNINSTALLED_PACKAGES permission");
            Objects.requireNonNull(packageList);

            setKeepUninstalledPackagesInternal(snapshot(), packageList);
        }

        @Override
        public void setMimeGroup(String packageName, String mimeGroup, List<String> mimeTypes) {
            final Computer snapshot = snapshotComputer();
            enforceOwnerRights(snapshot, packageName, Binder.getCallingUid());
            mimeTypes = CollectionUtils.emptyIfNull(mimeTypes);
            for (int i = 0; i < mimeTypes.size(); i++) {
                if (mimeTypes.get(i).length() > 255) {
                    throw new IllegalArgumentException("MIME type length exceeds 255 characters");
                }
            }
            final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
            Set<String> existingMimeTypes = packageState.getMimeGroups().get(mimeGroup);
            if (existingMimeTypes == null) {
                throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                        + " for package " + packageName);
            }
            if (existingMimeTypes.size() == mimeTypes.size()
                    && existingMimeTypes.containsAll(mimeTypes)) {
                return;
            }
            if (mimeTypes.size() > 500) {
                throw new IllegalStateException("Max limit on MIME types for MIME group "
                        + mimeGroup + " exceeded for package " + packageName);
            }

            ArraySet<String> mimeTypesSet = new ArraySet<>(mimeTypes);
            commitPackageStateMutation(null, packageName, packageStateWrite -> {
                packageStateWrite.setMimeGroup(mimeGroup, mimeTypesSet);
            });
            if (mComponentResolver.updateMimeGroup(snapshotComputer(), packageName, mimeGroup)) {
                Binder.withCleanCallingIdentity(() ->
                        mPreferredActivityHelper.clearPackagePreferredActivities(packageName,
                                UserHandle.USER_ALL));
            }

            scheduleWriteSettings();
        }

        @Override
        public void setPackageStoppedState(String packageName, boolean stopped, int userId) {
            PackageManagerService.this
                    .setPackageStoppedState(snapshotComputer(), packageName, stopped, userId);
        }

        @Override
        public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended,
                PersistableBundle appExtras, PersistableBundle launcherExtras,
                SuspendDialogInfo dialogInfo, int flags, String suspendingPackage,
                int suspendingUserId, int targetUserId) {
            final int callingUid = Binder.getCallingUid();
            boolean quarantined = false;
            if (Flags.quarantinedEnabled()) {
                if ((flags & PackageManager.FLAG_SUSPEND_QUARANTINED) != 0) {
                    quarantined = true;
                } else if (FeatureFlagUtils.isEnabled(mContext,
                        SETTINGS_TREAT_PAUSE_AS_QUARANTINE)) {
                    final String wellbeingPkg = mContext.getString(R.string.config_systemWellbeing);
                    quarantined = suspendingPackage.equals(wellbeingPkg);
                }
            }
            final Computer snapshot = snapshotComputer();
            final UserPackage suspender = UserPackage.of(targetUserId, suspendingPackage);
            enforceCanSetPackagesSuspendedAsUser(snapshot, quarantined, suspender, callingUid,
                    targetUserId, "setPackagesSuspendedAsUser");
            return mSuspendPackageHelper.setPackagesSuspended(snapshot, packageNames, suspended,
                    appExtras, launcherExtras, dialogInfo, suspender, targetUserId, callingUid,
                    quarantined);
        }

        @Override
        public boolean setRequiredForSystemUser(String packageName, boolean requiredForSystemUser) {
            PackageManagerServiceUtils.enforceSystemOrRoot(
                    "setRequiredForSystemUser can only be run by the system or root");

            PackageStateMutator.Result result = commitPackageStateMutation(null, packageName,
                    packageState -> packageState.setRequiredForSystemUser(requiredForSystemUser));
            if (!result.isCommitted()) {
                return false;
            }

            scheduleWriteSettings();
            return true;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_PACKAGES)
        @Override
        public void setUserMinAspectRatio(@NonNull String packageName, int userId,
                @PackageManager.UserMinAspectRatio int aspectRatio) {
            setUserMinAspectRatio_enforcePermission();
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId,
                    false /* requireFullPermission */, false /* checkShell */,
                    "setUserMinAspectRatio");
            enforceOwnerRights(snapshot, packageName, callingUid);

            final PackageStateInternal packageState = snapshot
                    .getPackageStateForInstalledAndFiltered(packageName, callingUid, userId);
            if (packageState == null) {
                return;
            }

            if (packageState.getUserStateOrDefault(userId).getMinAspectRatio() == aspectRatio) {
                return;
            }

            commitPackageStateMutation(null, packageName, state ->
                    state.userState(userId).setMinAspectRatio(aspectRatio));
        }

        @Override
        @SuppressWarnings("GuardedBy")
        public void setRuntimePermissionsVersion(int version, @UserIdInt int userId) {
            Preconditions.checkArgumentNonnegative(version);
            Preconditions.checkArgumentNonnegative(userId);
            enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
                    "setRuntimePermissionVersion");
            mSettings.setDefaultRuntimePermissionsVersion(version, userId);
        }

        @Override
        public void setSplashScreenTheme(@NonNull String packageName, @Nullable String themeId,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            snapshot.enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                    false /* checkShell */, "setSplashScreenTheme");
            enforceOwnerRights(snapshot, packageName, callingUid);

            PackageStateInternal packageState = snapshot.getPackageStateForInstalledAndFiltered(
                    packageName, callingUid, userId);
            if (packageState == null) {
                return;
            }

            commitPackageStateMutation(null, packageName, state ->
                    state.userState(userId).setSplashScreenTheme(themeId));
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.INSTALL_PACKAGES)
        @Override
        public void setUpdateAvailable(String packageName, boolean updateAvailable) {
            setUpdateAvailable_enforcePermission();
            commitPackageStateMutation(null, packageName, state ->
                    state.setUpdateAvailable(updateAvailable));
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS)
        @Override
        public void unregisterMoveCallback(IPackageMoveObserver callback) {
            unregisterMoveCallback_enforcePermission();
            mMoveCallbacks.unregister(callback);
        }

        @Override
        public void verifyPendingInstall(int verificationId, int verificationCode)
                throws RemoteException {
            // Negative ids correspond to testing verifiers and will be silently enforced in
            // the handler thread.
            if (verificationId >= 0) {
                mContext.enforceCallingOrSelfPermission(
                        Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                        "Only package verification agents can verify applications");
            }
            final int callingUid = Binder.getCallingUid();

            mHandler.post(() -> {
                final int id = verificationId >= 0 ? verificationId : -verificationId;
                final PackageVerificationState state = mPendingVerification.get(id);
                if (state == null) {
                    return;
                }
                if (!state.checkRequiredVerifierUid(callingUid)
                        && !state.checkSufficientVerifierUid(callingUid)) {
                    // Only allow calls from verifiers.
                    return;
                }

                final Message msg = mHandler.obtainMessage(PackageManagerService.PACKAGE_VERIFIED);
                final PackageVerificationResponse response = new PackageVerificationResponse(
                        verificationCode, callingUid);
                msg.arg1 = id;
                msg.obj = response;
                mHandler.sendMessage(msg);
            });
        }

        @Override
        public void registerPackageMonitorCallback(@NonNull IRemoteCallback callback, int userId) {
            int uid = Binder.getCallingUid();
            int targetUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), uid,
                    userId, true, true, "registerPackageMonitorCallback",
                    mContext.getPackageName());
            mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, targetUserId,
                    uid);
        }

        @Override
        public void unregisterPackageMonitorCallback(@NonNull IRemoteCallback callback) {
            mPackageMonitorCallbackHelper.unregisterPackageMonitorCallback(callback);
        }

        @Override
        public void requestPackageChecksums(@NonNull String packageName, boolean includeSplits,
                @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
                @Nullable List trustedInstallers,
                @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId) {
            requestChecksumsInternal(snapshotComputer(), packageName, includeSplits, optional,
                    required, trustedInstallers, onChecksumsReadyListener, userId,
                    mInjector.getBackgroundExecutor(), mInjector.getBackgroundHandler());
        }

        @Override
        public void notifyPackagesReplacedReceived(String[] packages) {
            Computer computer = snapshotComputer();
            ArraySet<String> packagesToNotify = computer.getNotifyPackagesForReplacedReceived(packages);
            for (int index = 0; index < packagesToNotify.size(); index++) {
                notifyInstallObserver(packagesToNotify.valueAt(index), false /* killApp */);
            }
        }

        @Override
        public ArchivedPackageParcel getArchivedPackage(@NonNull String packageName, int userId) {
            return getArchivedPackageInternal(packageName, userId);
        }

        @Override
        public Bitmap getArchivedAppIcon(@NonNull String packageName, @NonNull UserHandle user) {
            return mInstallerService.mPackageArchiver.getArchivedAppIcon(packageName, user);
        }

        @Override
        public boolean isAppArchivable(@NonNull String packageName, @NonNull UserHandle user) {
            return mInstallerService.mPackageArchiver.isAppArchivable(packageName, user);
        }

        /**
         * Wait for the handler to finish handling all pending messages.
         * @param timeoutMillis Maximum time in milliseconds to wait.
         * @param forBackgroundHandler Whether to wait for the background handler instead.
         * @return True if all the waiting messages in the handler has been handled.
         *         False if timeout.
         */
        @Override
        public boolean waitForHandler(long timeoutMillis, boolean forBackgroundHandler) {
            final CountDownLatch latch = new CountDownLatch(1);
            if (forBackgroundHandler) {
                mBackgroundHandler.post(latch::countDown);
            } else {
                mHandler.post(latch::countDown);
            }
            final long endTimeMillis = System.currentTimeMillis() + timeoutMillis;
            while (latch.getCount() > 0) {
                try {
                    final long remainingTimeMillis = endTimeMillis - System.currentTimeMillis();
                    if (remainingTimeMillis <= 0) {
                        return false;
                    }
                    return latch.await(remainingTimeMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // ignore and retry
                }
            }
            return true;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (RuntimeException e) {
                if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)
                        && !(e instanceof ParcelableException)) {
                    Slog.wtf(TAG, "Package Manager Unexpected Exception", e);
                }
                throw e;
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new PackageManagerShellCommand(this, mContext,
                    mDomainVerificationManager.getShell()))
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @SuppressWarnings("resource")
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;
            final Computer snapshot = snapshotComputer();
            final KnownPackages knownPackages = new KnownPackages(
                    mDefaultAppProvider,
                    mRequiredInstallerPackage,
                    mRequiredUninstallerPackage,
                    mSetupWizardPackage,
                    mRequiredVerifierPackages,
                    mDefaultTextClassifierPackage,
                    mSystemTextClassifierPackageName,
                    mRequiredPermissionControllerPackage,
                    mConfiguratorPackage,
                    mIncidentReportApproverPackage,
                    mAmbientContextDetectionPackage,
                    mWearableSensingPackage,
                    mAppPredictionServicePackage,
                    COMPANION_PACKAGE_NAME,
                    mRetailDemoPackage,
                    mOverlayConfigSignaturePackage,
                    mRecentsPackage);
            final ArrayMap<String, FeatureInfo> availableFeatures;
            synchronized (mAvailableFeatures) {
                availableFeatures = new ArrayMap<>(mAvailableFeatures);
            }
            final ArraySet<String> protectedBroadcasts;
            synchronized (mProtectedBroadcasts) {
                protectedBroadcasts = new ArraySet<>(mProtectedBroadcasts);
            }
            new DumpHelper(mPermissionManager, mStorageEventHelper,
                    mDomainVerificationManager, mInstallerService, mRequiredVerifierPackages,
                    knownPackages, mChangedPackagesTracker, availableFeatures, protectedBroadcasts,
                    getPerUidReadTimeouts(snapshot), mSnapshotStatistics
            ).doDump(snapshot, fd, pw, args);
        }
    }

    private class PackageManagerInternalImpl extends PackageManagerInternalBase {

        public PackageManagerInternalImpl() {
            super(PackageManagerService.this);
        }

        @NonNull
        @Override
        protected Context getContext() {
            return mContext;
        }

        @NonNull
        @Override
        protected PermissionManagerServiceInternal getPermissionManager() {
            return mPermissionManager;
        }

        @NonNull
        @Override
        protected AppDataHelper getAppDataHelper() {
            return mAppDataHelper;
        }

        @NonNull
        @Override
        protected PackageObserverHelper getPackageObserverHelper() {
            return mPackageObserverHelper;
        }

        @NonNull
        @Override
        protected ResolveIntentHelper getResolveIntentHelper() {
            return mResolveIntentHelper;
        }

        @NonNull
        @Override
        protected SuspendPackageHelper getSuspendPackageHelper() {
            return mSuspendPackageHelper;
        }

        @NonNull
        @Override
        protected DistractingPackageHelper getDistractingPackageHelper() {
            return mDistractingPackageHelper;
        }

        @NonNull
        @Override
        protected ProtectedPackages getProtectedPackages() {
            return mProtectedPackages;
        }

        @NonNull
        @Override
        protected UserNeedsBadgingCache getUserNeedsBadging() {
            return mUserNeedsBadging;
        }

        @NonNull
        @Override
        protected InstantAppRegistry getInstantAppRegistry() {
            return mInstantAppRegistry;
        }

        @NonNull
        @Override
        protected ApexManager getApexManager() {
            return mApexManager;
        }

        @NonNull
        @Override
        protected DexManager getDexManager() {
            return mDexManager;
        }

        @NonNull
        @Override
        public DynamicCodeLogger getDynamicCodeLogger() {
            return mDynamicCodeLogger;
        }

        @Override
        public boolean isPlatformSigned(String packageName) {
            PackageStateInternal packageState = snapshot().getPackageStateInternal(packageName);
            if (packageState == null) {
                return false;
            }
            SigningDetails signingDetails = packageState.getSigningDetails();
            return signingDetails.hasAncestorOrSelf(mPlatformPackage.getSigningDetails())
                    || mPlatformPackage.getSigningDetails().checkCapability(signingDetails,
                    SigningDetails.CertCapabilities.PERMISSION);
        }

        @Override
        public boolean isDataRestoreSafe(byte[] restoringFromSigHash, String packageName) {
            final Computer snapshot = snapshot();
            SigningDetails sd = snapshot.getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasSha256Certificate(restoringFromSigHash,
                    SigningDetails.CertCapabilities.INSTALLED_DATA);
        }

        @Override
        public boolean isDataRestoreSafe(Signature restoringFromSig, String packageName) {
            final Computer snapshot = snapshot();
            SigningDetails sd = snapshot.getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasCertificate(restoringFromSig,
                    SigningDetails.CertCapabilities.INSTALLED_DATA);
        }

        @Override
        public boolean hasSignatureCapability(int serverUid, int clientUid,
                @SigningDetails.CertCapabilities int capability) {
            final Computer snapshot = snapshot();
            SigningDetails serverSigningDetails = snapshot.getSigningDetails(serverUid);
            SigningDetails clientSigningDetails = snapshot.getSigningDetails(clientUid);
            return serverSigningDetails.checkCapability(clientSigningDetails, capability)
                    || clientSigningDetails.hasAncestorOrSelf(serverSigningDetails);
        }

        @Override
        public PackageList getPackageList(@Nullable PackageListObserver observer) {
            final ArrayList<String> list = new ArrayList<>();
            PackageManagerService.this.forEachPackageState(snapshot(), packageState -> {
                AndroidPackage pkg = packageState.getPkg();
                if (pkg != null) {
                    list.add(pkg.getPackageName());
                }
            });
            final PackageList packageList = new PackageList(list, observer);
            if (observer != null) {
                mPackageObserverHelper.addObserver(packageList);
            }
            return packageList;
        }

        @Override
        public @Nullable
        String getDisabledSystemPackageName(@NonNull String packageName) {
            PackageStateInternal disabledPkgSetting = snapshot().getDisabledSystemPackage(
                    packageName);
            AndroidPackage disabledPkg = disabledPkgSetting == null
                    ? null : disabledPkgSetting.getPkg();
            return disabledPkg == null ? null : disabledPkg.getPackageName();
        }

        @Override
        public boolean isResolveActivityComponent(ComponentInfo component) {
            return mResolveActivity.packageName.equals(component.packageName)
                    && mResolveActivity.name.equals(component.name);
        }

        @Override
        public long getCeDataInode(String packageName, int userId) {
            final PackageStateInternal packageState =
                    snapshot().getPackageStateInternal(packageName);
            if (packageState == null) {
                return 0;
            } else {
                return packageState.getUserStateOrDefault(userId).getCeDataInode();
            }
        }

        @Override
        public void removeAllNonSystemPackageSuspensions(int userId) {
            final Computer computer = snapshotComputer();
            final String[] allPackages = computer.getAllAvailablePackageNames();
            mSuspendPackageHelper.removeSuspensionsBySuspendingPackage(computer, allPackages,
                    (suspender) -> !PLATFORM_PACKAGE_NAME.equals(suspender.packageName),
                    userId);
        }

        @Override
        public void flushPackageRestrictions(int userId) {
            synchronized (mLock) {
                PackageManagerService.this.flushPackageRestrictionsAsUserInternalLocked(userId);
            }
        }

        @Override
        public String[] setPackagesSuspendedByAdmin(
                @UserIdInt int userId, @NonNull String[] packageNames, boolean suspended) {
            final int suspendingUserId = userId;
            final UserPackage suspender = UserPackage.of(
                    suspendingUserId, PackageManagerService.PLATFORM_PACKAGE_NAME);
            return mSuspendPackageHelper.setPackagesSuspended(snapshotComputer(), packageNames,
                    suspended, null /* appExtras */, null /* launcherExtras */,
                    null /* dialogInfo */, suspender, userId, Process.SYSTEM_UID,
                    false /* quarantined */);
        }

        @Override
        public void setDeviceAndProfileOwnerPackages(
                int deviceOwnerUserId, String deviceOwnerPackage,
                SparseArray<String> profileOwnerPackages) {
            mProtectedPackages.setDeviceAndProfileOwnerPackages(
                    deviceOwnerUserId, deviceOwnerPackage, profileOwnerPackages);
            final ArraySet<Integer> usersWithPoOrDo = new ArraySet<>();
            if (deviceOwnerPackage != null) {
                usersWithPoOrDo.add(deviceOwnerUserId);
            }
            final int sz = profileOwnerPackages.size();
            for (int i = 0; i < sz; i++) {
                if (profileOwnerPackages.valueAt(i) != null) {
                    removeAllNonSystemPackageSuspensions(profileOwnerPackages.keyAt(i));
                }
            }
        }

        @Override
        public void setExternalSourcesPolicy(ExternalSourcesPolicy policy) {
            if (policy != null) {
                mExternalSourcesPolicy = policy;
            }
        }

        @Override
        public boolean isPackagePersistent(String packageName) {
            final PackageStateInternal packageState =
                    snapshot().getPackageStateInternal(packageName);
            if (packageState == null) {
                return false;
            }

            AndroidPackage pkg = packageState.getPkg();
            return pkg != null && packageState.isSystem() && pkg.isPersistent();
        }

        @Override
        public List<PackageInfo> getOverlayPackages(int userId) {
            final Computer snapshot = snapshotComputer();
            final ArrayList<PackageInfo> overlayPackages = new ArrayList<>();
            final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                    snapshot.getPackageStates();
            for (int index = 0; index < packageStates.size(); index++) {
                final PackageStateInternal packageState = packageStates.valueAt(index);
                final AndroidPackage pkg = packageState.getPkg();
                if (pkg != null && pkg.getOverlayTarget() != null) {
                    PackageInfo pkgInfo = snapshot.generatePackageInfo(packageState, 0, userId);
                    if (pkgInfo != null) {
                        overlayPackages.add(pkgInfo);
                    }
                }
            }

            return overlayPackages;
        }

        @Override
        public List<String> getTargetPackageNames(int userId) {
            List<String> targetPackages = new ArrayList<>();
            PackageManagerService.this.forEachPackageState(snapshot(), packageState -> {
                final AndroidPackage pkg = packageState.getPkg();
                if (pkg != null && !pkg.isResourceOverlay()) {
                    targetPackages.add(pkg.getPackageName());
                }
            });
            return targetPackages;
        }

        @Override
        public void setEnabledOverlayPackages(int userId,
                @NonNull ArrayMap<String, OverlayPaths> pendingChanges,
                @NonNull Set<String> outUpdatedPackageNames,
                @NonNull Set<String> outInvalidPackageNames) {
            PackageManagerService.this.setEnabledOverlayPackages(userId,
                    pendingChanges, outUpdatedPackageNames, outInvalidPackageNames);
        }

        @Override
        public void addIsolatedUid(int isolatedUid, int ownerUid) {
            synchronized (mLock) {
                mIsolatedOwners.put(isolatedUid, ownerUid);
            }
        }

        @Override
        public void removeIsolatedUid(int isolatedUid) {
            synchronized (mLock) {
                mIsolatedOwners.delete(isolatedUid);
            }
        }

        @Override
        public void notifyPackageUse(String packageName, int reason) {
            PackageManagerService.this.notifyPackageUseInternal(packageName, reason);
        }

        @Nullable
        @Override
        public String removeLegacyDefaultBrowserPackageName(int userId) {
            synchronized (mLock) {
                return mSettings.removePendingDefaultBrowserLPw(userId);
            }
        }

        @Override
        public void uninstallApex(String packageName, long versionCode, int userId,
                IntentSender intentSender, int flags) {
            final int callerUid = Binder.getCallingUid();
            if (!PackageManagerServiceUtils.isRootOrShell(callerUid)) {
                throw new SecurityException("Not allowed to uninstall apexes");
            }
            PackageInstallerService.PackageDeleteObserverAdapter adapter =
                    new PackageInstallerService.PackageDeleteObserverAdapter(
                            PackageManagerService.this.mContext, intentSender, packageName,
                            false, userId);
            if ((flags & PackageManager.DELETE_ALL_USERS) == 0) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        "Can't uninstall an apex for a single user");
                return;
            }
            final ApexManager am = PackageManagerService.this.mApexManager;
            PackageInfo activePackage = snapshot().getPackageInfo(
                    packageName, PackageManager.MATCH_APEX, UserHandle.USER_SYSTEM);
            if (activePackage == null) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        packageName + " is not an apex package");
                return;
            }
            if (versionCode != PackageManager.VERSION_CODE_HIGHEST
                    && activePackage.getLongVersionCode() != versionCode) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        "Active version " + activePackage.getLongVersionCode()
                                + " is not equal to " + versionCode + "]");
                return;
            }
            if (!am.uninstallApex(activePackage.applicationInfo.sourceDir)) {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_ABORTED,
                        "Failed to uninstall apex " + packageName);
            } else {
                adapter.onPackageDeleted(packageName, PackageManager.DELETE_SUCCEEDED,
                        null);
            }
        }

        /** @deprecated For legacy shell command only. */
        @Override
        @Deprecated
        public void legacyDumpProfiles(String packageName, boolean dumpClassesAndMethods)
                throws LegacyDexoptDisabledException {
            final Computer snapshot = snapshotComputer();
            AndroidPackage pkg = snapshot.getPackage(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            synchronized (mInstallLock) {
                Trace.traceBegin(Trace.TRACE_TAG_DALVIK, "dump profiles");
                mArtManagerService.dumpProfiles(pkg, dumpClassesAndMethods);
                Trace.traceEnd(Trace.TRACE_TAG_DALVIK);
            }
        }

        /** @deprecated For legacy shell command only. */
        @Override
        @Deprecated
        public void legacyForceDexOpt(String packageName) throws LegacyDexoptDisabledException {
            mDexOptHelper.forceDexOpt(snapshotComputer(), packageName);
        }

        /** @deprecated For legacy shell command only. */
        @Override
        @Deprecated
        public void legacyReconcileSecondaryDexFiles(String packageName)
                throws LegacyDexoptDisabledException {
            final Computer snapshot = snapshotComputer();
            if (snapshot.getInstantAppPackageName(Binder.getCallingUid()) != null) {
                return;
            } else if (snapshot.isInstantAppInternal(
                               packageName, UserHandle.getCallingUserId(), Process.SYSTEM_UID)) {
                return;
            }
            mDexManager.reconcileSecondaryDexFiles(packageName);
        }

        @Override
        @SuppressWarnings("GuardedBy")
        public void updateRuntimePermissionsFingerprint(@UserIdInt int userId) {
            mSettings.updateRuntimePermissionsFingerprint(userId);
        }

        @Override
        public void migrateLegacyObbData() {
            try {
                mInstaller.migrateLegacyObbData();
            } catch (Exception e) {
                Slog.wtf(TAG, e);
            }
        }

        @Override
        public void writeSettings(boolean async) {
            synchronized (mLock) {
                if (async) {
                    scheduleWriteSettings();
                } else {
                    writeSettingsLPrTEMP();
                }
            }
        }

        @Override
        public void writePermissionSettings(int[] userIds, boolean async) {
            synchronized (mLock) {
                for (int userId : userIds) {
                    mSettings.writePermissionStateForUserLPr(userId, !async);
                }
            }
        }

        @Override
        public LegacyPermissionSettings getLegacyPermissions() {
            synchronized (mLock) {
                return mSettings.mPermissions;
            }
        }

        /**
         * Read legacy permission states for permissions migration to new permission subsystem.
         */
        @Override
        public RuntimePermissionsState getLegacyPermissionsState(int userId) {
            synchronized (mLock) {
                return mSettings.getLegacyPermissionsState(userId);
            }
        }

        @Override
        public int getLegacyPermissionsVersion(@UserIdInt int userId) {
            synchronized (mLock) {
                return mSettings.getDefaultRuntimePermissionsVersion(userId);
            }
        }

        @Override
        @SuppressWarnings("GuardedBy")
        public boolean isPermissionUpgradeNeeded(int userId) {
            return mSettings.isPermissionUpgradeNeeded(userId);
        }

        @Override
        public void setIntegrityVerificationResult(int verificationId, int verificationResult) {
            final Message msg = mHandler.obtainMessage(INTEGRITY_VERIFICATION_COMPLETE);
            msg.arg1 = verificationId;
            msg.obj = verificationResult;
            mHandler.sendMessage(msg);
        }

        @Override
        public void setVisibilityLogging(String packageName, boolean enable) {
            PackageManagerServiceUtils.enforceSystemOrRootOrShell(
                    "Only the system or shell can set visibility logging.");
            final PackageStateInternal packageState =
                    snapshot().getPackageStateInternal(packageName);
            if (packageState == null) {
                throw new IllegalStateException("No package found for " + packageName);
            }
            mAppsFilter.getFeatureConfig().enableLogging(packageState.getAppId(), enable);
        }

        @Override
        public void clearBlockUninstallForUser(@UserIdInt int userId) {
            synchronized (mLock) {
                mSettings.clearBlockUninstallLPw(userId);
                mSettings.writePackageRestrictionsLPr(userId);
            }
        }

        @Override
        public boolean registerInstalledLoadingProgressCallback(String packageName,
                PackageManagerInternal.InstalledLoadingProgressCallback callback, int userId) {
            final Computer snapshot = snapshotComputer();
            final PackageStateInternal ps = snapshot.getPackageStateForInstalledAndFiltered(
                    packageName, Binder.getCallingUid(), userId);
            if (ps == null) {
                return false;
            }
            if (!ps.isLoading()) {
                Slog.w(TAG,
                        "Failed registering loading progress callback. Package is fully loaded.");
                return false;
            }
            if (mIncrementalManager == null) {
                Slog.w(TAG,
                        "Failed registering loading progress callback. Incremental is not enabled");
                return false;
            }
            return mIncrementalManager.registerLoadingProgressCallback(ps.getPathString(),
                    (IPackageLoadingProgressCallback) callback.getBinder());
        }

        @Override
        public IncrementalStatesInfo getIncrementalStatesInfo(
                @NonNull String packageName, int filterCallingUid, int userId) {
            final Computer snapshot = snapshotComputer();
            final PackageStateInternal ps = snapshot.getPackageStateForInstalledAndFiltered(
                    packageName, filterCallingUid, userId);
            if (ps == null) {
                return null;
            }
            return new IncrementalStatesInfo(ps.isLoading(), ps.getLoadingProgress(),
                    ps.getLoadingCompletedTime());
        }

        @Override
        public boolean isSameApp(@Nullable String packageName, int callingUid, int userId) {
            return isSameApp(packageName, /*flags=*/0, callingUid, userId);
        }

        @Override
        public boolean isSameApp(@Nullable String packageName,
                @PackageManager.PackageInfoFlagsBits long flags, int callingUid, int userId) {
            if (packageName == null) {
                return false;
            }

            if (Process.isSdkSandboxUid(callingUid)) {
                return packageName.equals(mRequiredSdkSandboxPackage);
            }
            Computer snapshot = snapshot();
            int uid = snapshot.getPackageUid(packageName, flags, userId);
            return UserHandle.isSameApp(uid, callingUid);
        }

        @Override
        public void onPackageProcessKilledForUninstall(String packageName) {
            mHandler.post(() -> PackageManagerService.this.notifyInstallObserver(packageName,
                    true /* killApp */));
        }

        @Override
        public int[] getDistractingPackageRestrictionsAsUser(
                @NonNull String[] packageNames, int userId) {
            final int callingUid = Binder.getCallingUid();
            final Computer snapshot = snapshotComputer();
            Objects.requireNonNull(packageNames, "packageNames cannot be null");
            return mDistractingPackageHelper.getDistractingPackageRestrictionsAsUser(snapshot,
                    packageNames, userId, callingUid);
        }

        @Override
        public ParceledListSlice<PackageInstaller.SessionInfo> getHistoricalSessions(int userId) {
            return mInstallerService.getHistoricalSessions(userId);
        }

        @Override
        public PackageArchiver getPackageArchiver() {
            return mInstallerService.mPackageArchiver;
        }

        @Override
        public void sendPackageRestartedBroadcast(@NonNull String packageName,
                int uid, @Intent.Flags int flags) {
            final int userId = UserHandle.getUserId(uid);
            final int [] userIds = resolveUserIds(userId);
            final SparseArray<int[]> broadcastAllowList =
                    snapshotComputer().getVisibilityAllowLists(packageName, userIds);
            final Bundle extras = new Bundle();
            extras.putInt(Intent.EXTRA_UID, uid);
            extras.putInt(Intent.EXTRA_USER_HANDLE, userId);
            if (android.content.pm.Flags.stayStopped()) {
                extras.putLong(Intent.EXTRA_TIME, SystemClock.elapsedRealtime());
                // Sent async using the PM handler, to maintain ordering with PACKAGE_UNSTOPPED
                mHandler.post(() -> {
                    mBroadcastHelper.sendPackageBroadcast(Intent.ACTION_PACKAGE_RESTARTED,
                            packageName, extras,
                            flags, null, null,
                            userIds, null, broadcastAllowList, null,
                            null);
                });
            } else {
                mBroadcastHelper.sendPackageBroadcast(Intent.ACTION_PACKAGE_RESTARTED,
                        packageName, extras,
                        flags, null, null,
                        userIds, null, broadcastAllowList, null,
                        null);
            }
            mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_RESTARTED,
                    packageName, extras, userIds, null /* instantUserIds */,
                    broadcastAllowList, mHandler, null /* filterExtras */);
        }

        @Override
        public void sendPackageDataClearedBroadcast(@NonNull String packageName,
                int uid, int userId, boolean isRestore, boolean isInstantApp) {
            int[] visibilityAllowList =
                    snapshotComputer().getVisibilityAllowList(packageName, userId);
            final Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                    Uri.fromParts("package", packageName, null /* fragment */));
            intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                    | Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(Intent.EXTRA_UID, uid);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            if (isRestore) {
                intent.putExtra(Intent.EXTRA_IS_RESTORE, true);
            }
            if (isInstantApp) {
                intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            }
            mBroadcastHelper.sendPackageBroadcastWithIntent(intent, userId, isInstantApp,
                    0 /* flags */, visibilityAllowList, null /* finishedReceiver */,
                    null /* filterExtrasForReceiver */, null /* bOptions */);
            mPackageMonitorCallbackHelper.notifyPackageMonitorWithIntent(intent, userId,
                    visibilityAllowList, mHandler);
        }

        @Override
        public boolean isUpgradingFromLowerThan(int sdkVersion) {
            final boolean isUpgrading = mPriorSdkVersion != -1;
            return isUpgrading && mPriorSdkVersion < sdkVersion;
        }
    }

    private void setEnabledOverlayPackages(@UserIdInt int userId,
            @NonNull ArrayMap<String, OverlayPaths> pendingChanges,
            @NonNull Set<String> outUpdatedPackageNames,
            @NonNull Set<String> outInvalidPackageNames) {
        final ArrayMap<String, ArrayMap<String, ArraySet<String>>>
                targetPkgToLibNameToModifiedDependents = new ArrayMap<>();
        final int numberOfPendingChanges = pendingChanges.size();

        synchronized (mOverlayPathsLock) {
            Computer computer = snapshotComputer();
            for (int i = 0; i < numberOfPendingChanges; i++) {
                final String targetPackageName = pendingChanges.keyAt(i);
                final OverlayPaths newOverlayPaths = pendingChanges.valueAt(i);
                final PackageStateInternal packageState = computer.getPackageStateInternal(
                        targetPackageName);
                final AndroidPackage targetPkg =
                        packageState == null ? null : packageState.getPkg();
                if (targetPackageName == null || targetPkg == null) {
                    Slog.e(TAG, "failed to find package " + targetPackageName);
                    outInvalidPackageNames.add(targetPackageName);
                    continue;
                }

                if (Objects.equals(packageState.getUserStateOrDefault(userId).getOverlayPaths(),
                        newOverlayPaths)) {
                    continue;
                }

                if (targetPkg.getLibraryNames() != null) {
                    // Set the overlay paths for dependencies of the shared library.
                    List<String> libraryNames = targetPkg.getLibraryNames();
                    for (int j = 0; j < libraryNames.size(); j++) {
                        final String libName = libraryNames.get(j);
                        ArraySet<String> modifiedDependents = null;

                        final SharedLibraryInfo info = computer.getSharedLibraryInfo(libName,
                                SharedLibraryInfo.VERSION_UNDEFINED);
                        if (info == null) {
                            continue;
                        }
                        var usingSharedLibraryPair = computer.getPackagesUsingSharedLibrary(info, 0,
                                Process.SYSTEM_UID, userId);
                        final List<VersionedPackage> dependents = usingSharedLibraryPair.first;
                        if (dependents == null) {
                            continue;
                        }
                        for (int k = 0; k < dependents.size(); k++) {
                            final VersionedPackage dependent = dependents.get(k);
                            final PackageStateInternal dependentState =
                                    computer.getPackageStateInternal(dependent.getPackageName());
                            if (dependentState == null) {
                                continue;
                            }
                            if (canSetOverlayPaths(dependentState.getUserStateOrDefault(userId)
                                    .getSharedLibraryOverlayPaths()
                                    .get(libName), newOverlayPaths)) {
                                String dependentPackageName = dependent.getPackageName();
                                modifiedDependents = ArrayUtils.add(modifiedDependents,
                                        dependentPackageName);
                                outUpdatedPackageNames.add(dependentPackageName);
                            }
                        }

                        if (modifiedDependents != null) {
                            ArrayMap<String, ArraySet<String>> libNameToModifiedDependents =
                                    targetPkgToLibNameToModifiedDependents.get(
                                            targetPackageName);
                            if (libNameToModifiedDependents == null) {
                                libNameToModifiedDependents = new ArrayMap<>();
                                targetPkgToLibNameToModifiedDependents.put(targetPackageName,
                                        libNameToModifiedDependents);
                            }
                            libNameToModifiedDependents.put(libName, modifiedDependents);
                        }
                    }
                }

                if (canSetOverlayPaths(packageState.getUserStateOrDefault(userId).getOverlayPaths(),
                        newOverlayPaths)) {
                    outUpdatedPackageNames.add(targetPackageName);
                }
            }

            commitPackageStateMutation(null, mutator -> {
                for (int i = 0; i < numberOfPendingChanges; i++) {
                    final String targetPackageName = pendingChanges.keyAt(i);
                    final OverlayPaths newOverlayPaths = pendingChanges.valueAt(i);

                    if (!outUpdatedPackageNames.contains(targetPackageName)) {
                        continue;
                    }

                    mutator.forPackage(targetPackageName)
                            .userState(userId)
                            .setOverlayPaths(newOverlayPaths);

                    final ArrayMap<String, ArraySet<String>> libNameToModifiedDependents =
                            targetPkgToLibNameToModifiedDependents.get(
                                    targetPackageName);
                    if (libNameToModifiedDependents == null) {
                        continue;
                    }

                    for (int mapIndex = 0; mapIndex < libNameToModifiedDependents.size();
                            mapIndex++) {
                        String libName = libNameToModifiedDependents.keyAt(mapIndex);
                        ArraySet<String> modifiedDependents =
                                libNameToModifiedDependents.valueAt(mapIndex);
                        for (int setIndex = 0; setIndex < modifiedDependents.size(); setIndex++) {
                            mutator.forPackage(modifiedDependents.valueAt(setIndex))
                                    .userState(userId)
                                    .setOverlayPathsForLibrary(libName, newOverlayPaths);
                        }
                    }
                }
            });
        }

        if (userId == UserHandle.USER_SYSTEM) {
            // Keep the overlays in the system application info (and anything special cased as well)
            // up to date to make sure system ui is themed correctly.
            for (int i = 0; i < numberOfPendingChanges; i++) {
                final String targetPackageName = pendingChanges.keyAt(i);
                final OverlayPaths newOverlayPaths = pendingChanges.valueAt(i);
                maybeUpdateSystemOverlays(targetPackageName, newOverlayPaths);
            }
        }

        invalidatePackageInfoCache();
    }

    private boolean canSetOverlayPaths(OverlayPaths origPaths, OverlayPaths newPaths) {
        if (Objects.equals(origPaths, newPaths)) {
            return false;
        }
        if ((origPaths == null && newPaths.isEmpty())
                || (newPaths == null && origPaths.isEmpty())) {
            return false;
        }
        return true;
    }

    private void maybeUpdateSystemOverlays(String targetPackageName, OverlayPaths newOverlayPaths) {
        if (!mResolverReplaced) {
            if (targetPackageName.equals("android")) {
                if (newOverlayPaths == null) {
                    mPlatformPackageOverlayPaths = null;
                    mPlatformPackageOverlayResourceDirs = null;
                } else {
                    mPlatformPackageOverlayPaths = newOverlayPaths.getOverlayPaths().toArray(
                            new String[0]);
                    mPlatformPackageOverlayResourceDirs = newOverlayPaths.getResourceDirs().toArray(
                            new String[0]);
                }
                applyUpdatedSystemOverlayPaths();
            }
        } else {
            if (targetPackageName.equals(mResolveActivity.applicationInfo.packageName)) {
                if (newOverlayPaths == null) {
                    mReplacedResolverPackageOverlayPaths = null;
                    mReplacedResolverPackageOverlayResourceDirs = null;
                } else {
                    mReplacedResolverPackageOverlayPaths =
                            newOverlayPaths.getOverlayPaths().toArray(new String[0]);
                    mReplacedResolverPackageOverlayResourceDirs =
                            newOverlayPaths.getResourceDirs().toArray(new String[0]);
                }
                applyUpdatedSystemOverlayPaths();
            }
        }
    }

    private void applyUpdatedSystemOverlayPaths() {
        if (mAndroidApplication == null) {
            Slog.i(TAG, "Skipped the AndroidApplication overlay paths update - no app yet");
        } else {
            mAndroidApplication.overlayPaths = mPlatformPackageOverlayPaths;
            mAndroidApplication.resourceDirs = mPlatformPackageOverlayResourceDirs;
        }
        if (mResolverReplaced) {
            mResolveActivity.applicationInfo.overlayPaths = mReplacedResolverPackageOverlayPaths;
            mResolveActivity.applicationInfo.resourceDirs =
                    mReplacedResolverPackageOverlayResourceDirs;
        }
    }

    private void enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
            @NonNull String message) {
        if (mContext.checkCallingOrSelfPermission(
                Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY)
                != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(
                Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY + " or "
                    + Manifest.permission.UPGRADE_RUNTIME_PERMISSIONS);
        }
    }

    // TODO: Remove
    @Deprecated
    @Nullable
    @GuardedBy("mLock")
    PackageSetting getPackageSettingForMutation(String packageName) {
        return mSettings.getPackageLPr(packageName);
    }

    // TODO: Remove
    @Deprecated
    @Nullable
    @GuardedBy("mLock")
    PackageSetting getDisabledPackageSettingForMutation(String packageName) {
        return mSettings.getDisabledSystemPkgLPr(packageName);
    }

    @Deprecated
    void forEachPackageSetting(Consumer<PackageSetting> actionLocked) {
        synchronized (mLock) {
            int size = mSettings.getPackagesLocked().size();
            for (int index = 0; index < size; index++) {
                actionLocked.accept(mSettings.getPackagesLocked().valueAt(index));
            }
        }
    }

    void forEachPackageState(@NonNull Computer snapshot, Consumer<PackageStateInternal> consumer) {
        forEachPackageState(snapshot.getPackageStates(), consumer);
    }

    void forEachPackage(@NonNull Computer snapshot, Consumer<AndroidPackage> consumer) {
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                snapshot.getPackageStates();
        int size = packageStates.size();
        for (int index = 0; index < size; index++) {
            PackageStateInternal packageState = packageStates.valueAt(index);
            if (packageState.getPkg() != null) {
                consumer.accept(packageState.getPkg());
            }
        }
    }

    void forEachPackageInternal(@NonNull Computer snapshot,
            @NonNull Consumer<AndroidPackageInternal> consumer) {
        final ArrayMap<String, ? extends PackageStateInternal> packageStates =
                snapshot.getPackageStates();
        int size = packageStates.size();
        for (int index = 0; index < size; index++) {
            PackageStateInternal packageState = packageStates.valueAt(index);
            if (packageState.getPkg() != null) {
                consumer.accept(packageState.getPkg());
            }
        }
    }

    private void forEachPackageState(
            @NonNull ArrayMap<String, ? extends PackageStateInternal> packageStates,
            @NonNull Consumer<PackageStateInternal> consumer) {
        int size = packageStates.size();
        for (int index = 0; index < size; index++) {
            PackageStateInternal packageState = packageStates.valueAt(index);
            consumer.accept(packageState);
        }
    }

    void forEachInstalledPackage(@NonNull Computer snapshot, @NonNull Consumer<AndroidPackage> action,
            @UserIdInt int userId) {
        Consumer<PackageStateInternal> actionWrapped = packageState -> {
            if (packageState.getPkg() != null
                    && packageState.getUserStateOrDefault(userId).isInstalled()) {
                action.accept(packageState.getPkg());
            }
        };
        forEachPackageState(snapshot.getPackageStates(), actionWrapped);
    }

    boolean isHistoricalPackageUsageAvailable() {
        return mPackageUsage.isHistoricalPackageUsageAvailable();
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(AndroidPackage pkg) {
        return getOrCreateCompilerPackageStats(pkg.getPackageName());
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(String pkgName) {
        return mCompilerStats.getOrCreatePackageStats(pkgName);
    }

    void grantImplicitAccess(@NonNull Computer snapshot, @UserIdInt int userId,
            Intent intent, @AppIdInt int recipientAppId, int visibleUid, boolean direct,
            boolean retainOnUpdate) {
        final AndroidPackage visiblePackage = snapshot.getPackage(visibleUid);
        final int recipientUid = UserHandle.getUid(userId, recipientAppId);
        if (visiblePackage == null || snapshot.getPackage(recipientUid) == null) {
            return;
        }

        final boolean instantApp = snapshot.isInstantAppInternal(
                visiblePackage.getPackageName(), userId, visibleUid);
        final boolean accessGranted;
        if (instantApp) {
            if (!direct) {
                // if the interaction that lead to this granting access to an instant app
                // was indirect (i.e.: URI permission grant), do not actually execute the
                // grant.
                return;
            }
            accessGranted = mInstantAppRegistry.grantInstantAccess(userId, intent,
                    recipientAppId, UserHandle.getAppId(visibleUid) /*instantAppId*/);
        } else {
            accessGranted = mAppsFilter.grantImplicitAccess(recipientUid, visibleUid,
                    retainOnUpdate);
        }

        if (accessGranted) {
            ApplicationPackageManager.invalidateGetPackagesForUidCache();
        }
    }

    boolean canHaveOatDir(@NonNull Computer snapshot, String packageName) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        if (packageState == null || packageState.getPkg() == null) {
            return false;
        }
        return AndroidPackageUtils.canHaveOatDir(packageState, packageState.getPkg());
    }

    long deleteOatArtifactsOfPackage(@NonNull Computer snapshot, String packageName) {
        PackageManagerServiceUtils.enforceSystemOrRootOrShell(
                "Only the system or shell can delete oat artifacts");

        if (DexOptHelper.useArtService()) {
            // TODO(chiuwinson): Retrieve filtered snapshot from Computer instance instead.
            try (PackageManagerLocal.FilteredSnapshot filteredSnapshot =
                            PackageManagerServiceUtils.getPackageManagerLocal()
                                    .withFilteredSnapshot()) {
                try {
                    DeleteResult res = DexOptHelper.getArtManagerLocal().deleteDexoptArtifacts(
                            filteredSnapshot, packageName);
                    return res.getFreedBytes();
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, e.toString());
                    return -1;
                } catch (IllegalStateException e) {
                    Slog.wtfStack(TAG, e.toString());
                    return -1;
                }
            }
        } else {
            PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
            if (packageState == null || packageState.getPkg() == null) {
                return -1; // error code of deleteOptimizedFiles
            }
            try {
                return mDexManager.deleteOptimizedFiles(
                        ArtUtils.createArtPackageInfo(packageState.getPkg(), packageState));
            } catch (LegacyDexoptDisabledException e) {
                throw new RuntimeException(e);
            }
        }
    }

    List<String> getMimeGroupInternal(@NonNull Computer snapshot, String packageName,
            String mimeGroup) {
        final PackageStateInternal packageState = snapshot.getPackageStateInternal(packageName);
        if (packageState == null) {
            return Collections.emptyList();
        }

        final Map<String, Set<String>> mimeGroups = packageState.getMimeGroups();
        Set<String> mimeTypes = mimeGroups != null ? mimeGroups.get(mimeGroup) : null;
        if (mimeTypes == null) {
            throw new IllegalArgumentException("Unknown MIME group " + mimeGroup
                    + " for package " + packageName);
        }
        return new ArrayList<>(mimeTypes);
    }

    /**
     * Temporary method that wraps mSettings.writeLPr() and calls mPermissionManager's
     * writeLegacyPermissionsTEMP() beforehand.
     *
     * TODO: In the meantime, can this be moved to a schedule call?
     * TODO(b/182523293): This should be removed once we finish migration of permission storage.
     */
    @SuppressWarnings("GuardedBy")
    void writeSettingsLPrTEMP(boolean sync) {
        snapshotComputer(false);
        mPermissionManager.writeLegacyPermissionsTEMP(mSettings.mPermissions);
        mSettings.writeLPr(mLiveComputer, sync);
    }

    // Default async version.
    void writeSettingsLPrTEMP() {
        writeSettingsLPrTEMP(/*sync=*/false);
    }

    @Override
    public void verifyHoldLockToken(IBinder token) {
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException("holdLock requires a debuggable build");
        }

        if (token == null) {
            throw new SecurityException("null holdLockToken");
        }

        if (token.queryLocalInterface("holdLock:" + Binder.getCallingUid()) != this) {
            throw new SecurityException("Invalid holdLock() token");
        }
    }

    static String getDefaultTimeouts() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_INCFS_DEFAULT_TIMEOUTS, "");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static String getKnownDigestersList() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_KNOWN_DIGESTERS_LIST, "");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static boolean isPreapprovalRequestAvailable() {
        final long token = Binder.clearCallingIdentity();
        try {
            if (!Resources.getSystem().getBoolean(
                    com.android.internal.R.bool.config_isPreApprovalRequestAvailable)) {
                return false;
            }
            return DeviceConfig.getBoolean(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE, true /* defaultValue */);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static boolean isUpdateOwnershipEnforcementAvailable() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getBoolean(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_IS_UPDATE_OWNERSHIP_ENFORCEMENT_AVAILABLE, true /* defaultValue */);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns the array containing per-uid timeout configuration.
     * This is derived from DeviceConfig flags.
     */
    public @NonNull PerUidReadTimeouts[] getPerUidReadTimeouts(@NonNull Computer snapshot) {
        PerUidReadTimeouts[] result = mPerUidReadTimeoutsCache;
        if (result == null) {
            result = parsePerUidReadTimeouts(snapshot);
            mPerUidReadTimeoutsCache = result;
        }
        return result;
    }

    private @NonNull PerUidReadTimeouts[] parsePerUidReadTimeouts(@NonNull Computer snapshot) {
        final String defaultTimeouts = getDefaultTimeouts();
        final String knownDigestersList = getKnownDigestersList();
        final List<PerPackageReadTimeouts> perPackageReadTimeouts =
                PerPackageReadTimeouts.parseDigestersList(defaultTimeouts, knownDigestersList);

        if (perPackageReadTimeouts.size() == 0) {
            return EMPTY_PER_UID_READ_TIMEOUTS_ARRAY;
        }

        final int[] allUsers = mInjector.getUserManagerService().getUserIds();
        final List<PerUidReadTimeouts> result = new ArrayList<>(perPackageReadTimeouts.size());
        for (int i = 0, size = perPackageReadTimeouts.size(); i < size; ++i) {
            final PerPackageReadTimeouts perPackage = perPackageReadTimeouts.get(i);
            final PackageStateInternal ps =
                    snapshot.getPackageStateInternal(perPackage.packageName);
            if (ps == null) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: package not found = "
                            + perPackage.packageName);
                }
                continue;
            }
            if (ps.getAppId() < Process.FIRST_APPLICATION_UID) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: package is system, appId="
                            + ps.getAppId());
                }
                continue;
            }

            final AndroidPackage pkg = ps.getPkg();
            if (pkg.getLongVersionCode() < perPackage.versionCodes.minVersionCode
                    || pkg.getLongVersionCode() > perPackage.versionCodes.maxVersionCode) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: version code is not in range = "
                            + perPackage.packageName + ":" + pkg.getLongVersionCode());
                }
                continue;
            }
            if (perPackage.sha256certificate != null
                    && !pkg.getSigningDetails().hasSha256Certificate(
                    perPackage.sha256certificate)) {
                if (DEBUG_PER_UID_READ_TIMEOUTS) {
                    Slog.i(TAG, "PerUidReadTimeouts: invalid certificate = "
                            + perPackage.packageName + ":" + pkg.getLongVersionCode());
                }
                continue;
            }
            for (int userId : allUsers) {
                if (!ps.getUserStateOrDefault(userId).isInstalled()) {
                    continue;
                }
                final int uid = UserHandle.getUid(userId, ps.getAppId());
                final PerUidReadTimeouts perUid = new PerUidReadTimeouts();
                perUid.uid = uid;
                perUid.minTimeUs = perPackage.timeouts.minTimeUs;
                perUid.minPendingTimeUs = perPackage.timeouts.minPendingTimeUs;
                perUid.maxPendingTimeUs = perPackage.timeouts.maxPendingTimeUs;
                result.add(perUid);
            }
        }
        return result.toArray(new PerUidReadTimeouts[result.size()]);
    }

    void setKeepUninstalledPackagesInternal(@NonNull Computer snapshot, List<String> packageList) {
        Preconditions.checkNotNull(packageList);
        synchronized (mKeepUninstalledPackages) {
            List<String> toRemove = new ArrayList<>(mKeepUninstalledPackages);
            toRemove.removeAll(packageList); // Do not remove anything still in the list

            mKeepUninstalledPackages.clear();
            mKeepUninstalledPackages.addAll(packageList);

            for (int i = 0; i < toRemove.size(); i++) {
                deletePackageIfUnused(snapshot, toRemove.get(i));
            }
        }
    }

    boolean shouldKeepUninstalledPackageLPr(String packageName) {
        synchronized (mKeepUninstalledPackages) {
            return mKeepUninstalledPackages.contains(packageName);
        }
    }

    boolean getSafeMode() {
        return mSafeMode;
    }

    ComponentName getResolveComponentName() {
        return mResolveComponentName;
    }

    DefaultAppProvider getDefaultAppProvider() {
        return mDefaultAppProvider;
    }

    File getCacheDir() {
        return mCacheDir;
    }

    PackageProperty getPackageProperty() {
        return mPackageProperty;
    }

    WatchedArrayMap<ComponentName, ParsedInstrumentation> getInstrumentation() {
        return mInstrumentation;
    }

    int getSdkVersion() {
        return mSdkVersion;
    }

    void addAllPackageProperties(@NonNull AndroidPackage pkg) {
        mPackageProperty.addAllProperties(pkg);
    }

    void addInstrumentation(ComponentName name, ParsedInstrumentation instrumentation) {
        mInstrumentation.put(name, instrumentation);
    }

    String[] getKnownPackageNamesInternal(@NonNull Computer snapshot, int knownPackage,
            int userId) {
        return new KnownPackages(
                mDefaultAppProvider,
                mRequiredInstallerPackage,
                mRequiredUninstallerPackage,
                mSetupWizardPackage,
                mRequiredVerifierPackages,
                mDefaultTextClassifierPackage,
                mSystemTextClassifierPackageName,
                mRequiredPermissionControllerPackage,
                mConfiguratorPackage,
                mIncidentReportApproverPackage,
                mAmbientContextDetectionPackage,
                mWearableSensingPackage,
                mAppPredictionServicePackage,
                COMPANION_PACKAGE_NAME,
                mRetailDemoPackage,
                mOverlayConfigSignaturePackage,
                mRecentsPackage)
                .getKnownPackageNames(snapshot, knownPackage, userId);
    }

    String getActiveLauncherPackageName(int userId) {
        return mDefaultAppProvider.getDefaultHome(userId);
    }

    boolean setActiveLauncherPackage(@NonNull String packageName, @UserIdInt int userId,
            @NonNull Consumer<Boolean> callback) {
        return mDefaultAppProvider.setDefaultHome(packageName, userId, mContext.getMainExecutor(),
                callback);
    }

    @Nullable
    String getDefaultBrowser(@UserIdInt int userId) {
        return mDefaultAppProvider.getDefaultBrowser(userId);
    }

    void setDefaultBrowser(@Nullable String packageName, @UserIdInt int userId) {
        mDefaultAppProvider.setDefaultBrowser(packageName, userId);
    }

    PackageUsage getPackageUsage() {
        return mPackageUsage;
    }

    String getModuleMetadataPackageName() {
        return mModuleInfoProvider.getPackageName();
    }

    File getAppInstallDir() {
        return mAppInstallDir;
    }

    boolean isExpectingBetter(String packageName) {
        return mInitAppsHelper.isExpectingBetter(packageName);
    }

    int getDefParseFlags() {
        return mDefParseFlags;
    }

    void setUpCustomResolverActivity(AndroidPackage pkg, PackageSetting pkgSetting) {
        synchronized (mLock) {
            mResolverReplaced = true;

            // The instance created in PackageManagerService is special cased to be non-user
            // specific, so initialize all the needed fields here.
            ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                    PackageUserStateInternal.DEFAULT, UserHandle.USER_SYSTEM, pkgSetting);

            // Set up information for custom user intent resolution activity.
            mResolveActivity.applicationInfo = appInfo;
            mResolveActivity.name = mCustomResolverComponentName.getClassName();
            mResolveActivity.packageName = pkg.getPackageName();
            mResolveActivity.processName = pkg.getProcessName();
            mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                    | ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS
                    | ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
            mResolveActivity.theme = 0;
            mResolveActivity.exported = true;
            mResolveActivity.enabled = true;
            mResolveInfo.activityInfo = mResolveActivity;
            mResolveInfo.priority = 0;
            mResolveInfo.preferredOrder = 0;
            mResolveInfo.match = 0;
            mResolveComponentName = mCustomResolverComponentName;
            PackageManagerService.onChanged();
            Slog.i(TAG, "Replacing default ResolverActivity with custom activity: "
                    + mResolveComponentName);
        }
    }

    void setPlatformPackage(AndroidPackage pkg, PackageSetting pkgSetting) {
        synchronized (mLock) {
            // Set up information for our fall-back user intent resolution activity.
            mPlatformPackage = pkg;

            // The instance stored in PackageManagerService is special cased to be non-user
            // specific, so initialize all the needed fields here.
            mAndroidApplication = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                    PackageUserStateInternal.DEFAULT, UserHandle.USER_SYSTEM, pkgSetting);

            if (!mResolverReplaced) {
                mResolveActivity.applicationInfo = mAndroidApplication;
                mResolveActivity.name = ResolverActivity.class.getName();
                mResolveActivity.packageName = mAndroidApplication.packageName;
                mResolveActivity.processName = "system:ui";
                mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                mResolveActivity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NEVER;
                mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
                        | ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY
                        | ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
                mResolveActivity.theme = R.style.Theme_Material_Dialog_Alert;
                mResolveActivity.exported = true;
                mResolveActivity.enabled = true;
                mResolveActivity.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
                mResolveActivity.configChanges = ActivityInfo.CONFIG_SCREEN_SIZE
                        | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                        | ActivityInfo.CONFIG_SCREEN_LAYOUT
                        | ActivityInfo.CONFIG_ORIENTATION
                        | ActivityInfo.CONFIG_KEYBOARD
                        | ActivityInfo.CONFIG_KEYBOARD_HIDDEN;
                mResolveInfo.activityInfo = mResolveActivity;
                mResolveInfo.priority = 0;
                mResolveInfo.preferredOrder = 0;
                mResolveInfo.match = 0;
                mResolveComponentName = new ComponentName(
                        mAndroidApplication.packageName, mResolveActivity.name);
            }
            PackageManagerService.onChanged();
        }
        applyUpdatedSystemOverlayPaths();
    }

    ApplicationInfo getCoreAndroidApplication() {
        return mAndroidApplication;
    }

    boolean isSystemReady() {
        return mSystemReady;
    }

    AndroidPackage getPlatformPackage() {
        return mPlatformPackage;
    }

    boolean isPreNMR1Upgrade() {
        return mIsPreNMR1Upgrade;
    }

    boolean isOverlayMutable(String packageName) {
        return mOverlayConfig.isMutable(packageName);
    }

    @ScanFlags int getSystemPackageScanFlags(File codePath) {
        List<ScanPartition> dirsToScanAsSystem =
                mInitAppsHelper.getDirsToScanAsSystem();
        @PackageManagerService.ScanFlags int scanFlags = SCAN_AS_SYSTEM;
        for (int i = dirsToScanAsSystem.size() - 1; i >= 0; i--) {
            ScanPartition partition = dirsToScanAsSystem.get(i);
            if (partition.containsFile(codePath)) {
                scanFlags |= partition.scanFlag;
                if (partition.containsPrivApp(codePath)) {
                    scanFlags |= SCAN_AS_PRIVILEGED;
                }
                break;
            }
        }
        return scanFlags;
    }

    Pair<Integer, Integer> getSystemPackageRescanFlagsAndReparseFlags(File scanFile,
            int systemScanFlags, int systemParseFlags) {
        List<ScanPartition> dirsToScanAsSystem =
                mInitAppsHelper.getDirsToScanAsSystem();
        @ParsingPackageUtils.ParseFlags int reparseFlags = 0;
        @PackageManagerService.ScanFlags int rescanFlags = 0;
        for (int i1 = dirsToScanAsSystem.size() - 1; i1 >= 0; i1--) {
            final ScanPartition partition = dirsToScanAsSystem.get(i1);
            if (partition.containsPrivApp(scanFile)) {
                reparseFlags = systemParseFlags;
                rescanFlags = systemScanFlags | SCAN_AS_PRIVILEGED
                        | partition.scanFlag;
                break;
            }
            if (partition.containsApp(scanFile)) {
                reparseFlags = systemParseFlags;
                rescanFlags = systemScanFlags | partition.scanFlag;
                break;
            }
        }
        return new Pair<>(rescanFlags, reparseFlags);
    }


    /**
     * @see PackageManagerInternal#recordInitialState()
     */
    @NonNull
    public PackageStateMutator.InitialState recordInitialState() {
        return mPackageStateMutator.initialState(mChangedPackagesTracker.getSequenceNumber());
    }

    /**
     * @see PackageManagerInternal#commitPackageStateMutation(PackageStateMutator.InitialState,
     * Consumer)
     */
    @NonNull
    public PackageStateMutator.Result commitPackageStateMutation(
            @Nullable PackageStateMutator.InitialState initialState,
            @NonNull Consumer<PackageStateMutator> consumer) {
        synchronized (mPackageStateWriteLock) {
            final PackageStateMutator.Result result = mPackageStateMutator.generateResult(
                    initialState, mChangedPackagesTracker.getSequenceNumber());
            if (result != PackageStateMutator.Result.SUCCESS) {
                return result;
            }

            consumer.accept(mPackageStateMutator);
            mPackageStateMutator.onFinished();
        }

        return PackageStateMutator.Result.SUCCESS;
    }

    /**
     * @see PackageManagerInternal#commitPackageStateMutation(PackageStateMutator.InitialState,
     * Consumer)
     */
    @NonNull
    public PackageStateMutator.Result commitPackageStateMutation(
            @Nullable PackageStateMutator.InitialState initialState, @NonNull String packageName,
            @NonNull Consumer<PackageStateWrite> consumer) {
        PackageStateMutator.Result result = null;
        if (Thread.holdsLock(mPackageStateWriteLock)) {
            // If the thread is already holding the lock, this is likely a retry based on a prior
            // failure, and re-calculating whether a state change occurred can be skipped.
            result = PackageStateMutator.Result.SUCCESS;
        }
        synchronized (mPackageStateWriteLock) {
            if (result == null) {
                // If the thread wasn't previously holding, this is a first-try commit and so a
                // state change may have happened.
                result = mPackageStateMutator.generateResult(
                        initialState, mChangedPackagesTracker.getSequenceNumber());
            }
            if (result != PackageStateMutator.Result.SUCCESS) {
                return result;
            }

            PackageStateWrite state = mPackageStateMutator.forPackage(packageName);
            if (state == null) {
                return PackageStateMutator.Result.SPECIFIC_PACKAGE_NULL;
            } else {
                consumer.accept(state);
            }

            state.onChanged();
        }

        return PackageStateMutator.Result.SUCCESS;
    }

    void notifyInstantAppPackageInstalled(String packageName, int[] newUsers) {
        mInstantAppRegistry.onPackageInstalled(snapshotComputer(), packageName, newUsers);
    }

    void addInstallerPackageName(InstallSource installSource) {
        synchronized (mLock) {
            mSettings.addInstallerPackageNames(installSource);
        }
    }

    public void reconcileSdkData(@Nullable String volumeUuid, @NonNull String packageName,
            @NonNull List<String> subDirNames, int userId, int appId, int previousAppId,
            @NonNull String seInfo, int flags) throws IOException {
        synchronized (mInstallLock) {
            ReconcileSdkDataArgs args = mInstaller.buildReconcileSdkDataArgs(volumeUuid,
                    packageName, subDirNames, userId, appId, seInfo,
                    flags);
            args.previousAppId = previousAppId;
            try {
                mInstaller.reconcileSdkData(args);
            } catch (InstallerException e) {
                throw new IOException(e.getMessage());
            }
        }
    }

    void removeCodePath(@Nullable File codePath) {
        mRemovePackageHelper.removeCodePath(codePath);
    }

    void cleanUpResources(@NonNull String packageName, @NonNull File codeFile,
                          @NonNull String[] instructionSets) {
        mRemovePackageHelper.cleanUpResources(packageName, codeFile, instructionSets);
    }

    void cleanUpForMoveInstall(String volumeUuid, String packageName, String fromCodePath) {
        mRemovePackageHelper.cleanUpForMoveInstall(volumeUuid, packageName, fromCodePath);
    }

    void sendPendingBroadcasts() {
        mInstallPackageHelper.sendPendingBroadcasts();
    }

    void handlePackagePostInstall(@NonNull InstallRequest request, boolean launchedForRestore) {
        mInstallPackageHelper.handlePackagePostInstall(request, launchedForRestore);
    }

    Pair<Integer, IntentSender> installExistingPackageAsUser(
            @Nullable String packageName,
            @UserIdInt int userId, @PackageManager.InstallFlags int installFlags,
            @PackageManager.InstallReason int installReason,
            @Nullable List<String> allowlistedRestrictedPermissions,
            @Nullable IntentSender intentSender) {
        return mInstallPackageHelper.installExistingPackageAsUser(packageName, userId, installFlags,
                installReason, allowlistedRestrictedPermissions, intentSender);
    }
    AndroidPackage initPackageTracedLI(File scanFile, final int parseFlags, int scanFlags)
            throws PackageManagerException {
        return mInstallPackageHelper.initPackageTracedLI(scanFile, parseFlags, scanFlags);
    }

    void restoreDisabledSystemPackageLIF(@NonNull DeletePackageAction action,
                                         @NonNull int[] allUserHandles,
                                         boolean writeSettings) throws SystemDeleteException {
        mInstallPackageHelper.restoreDisabledSystemPackageLIF(
                action, allUserHandles, writeSettings);
    }
    boolean enableCompressedPackage(@NonNull AndroidPackage stubPkg,
                                    @NonNull PackageSetting stubPs) {
        return mInstallPackageHelper.enableCompressedPackage(stubPkg, stubPs);
    }

    void installPackagesTraced(List<InstallRequest> requests) {
        mInstallPackageHelper.installPackagesTraced(requests);
    }

    void restoreAndPostInstall(InstallRequest request) {
        mInstallPackageHelper.restoreAndPostInstall(request);
    }

    Pair<Integer, String> verifyReplacingVersionCode(@NonNull PackageInfoLite pkgLite,
                                    long requiredInstalledVersionCode,
                                    int installFlags) {
        return mInstallPackageHelper.verifyReplacingVersionCode(
                pkgLite, requiredInstalledVersionCode, installFlags);
    }

    int getUidForVerifier(VerifierInfo verifierInfo) {
        return mInstallPackageHelper.getUidForVerifier(verifierInfo);
    }

    int deletePackageX(String packageName, long versionCode, int userId, int deleteFlags,
                        boolean removedBySystem) {
        return mDeletePackageHelper.deletePackageX(packageName,
                PackageManager.VERSION_CODE_HIGHEST, UserHandle.USER_SYSTEM,
                PackageManager.DELETE_ALL_USERS, true /*removedBySystem*/);
    }
}
