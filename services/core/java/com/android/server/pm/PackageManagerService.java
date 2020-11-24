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

import static android.Manifest.permission.DELETE_PACKAGES;
import static android.Manifest.permission.INSTALL_PACKAGES;
import static android.Manifest.permission.MANAGE_DEVICE_ADMINS;
import static android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS;
import static android.Manifest.permission.REQUEST_DELETE_PACKAGES;
import static android.Manifest.permission.SET_HARMFUL_APP_WARNINGS;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.Intent.CATEGORY_DEFAULT;
import static android.content.pm.PackageManager.CERT_INPUT_RAW_X509;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_FACTORY_ONLY;
import static android.content.pm.PackageManager.MATCH_KNOWN_PACKAGES;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageManager.RESTRICTION_NONE;
import static android.content.pm.PackageManager.TYPE_ACTIVITY;
import static android.content.pm.PackageManager.TYPE_PROVIDER;
import static android.content.pm.PackageManager.TYPE_RECEIVER;
import static android.content.pm.PackageManager.TYPE_UNKNOWN;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.PackageManagerInternal.LAST_KNOWN_PACKAGE;
import static android.os.PowerWhitelistManager.REASON_LOCKED_BOOT_COMPLETED;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_INIT_TIME;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSet;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.PackageManagerServiceCompilerMapping.getDefaultCompilerFilter;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.dumpCriticalInfo;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;
import static com.android.server.pm.PackageManagerServiceUtils.verifySignatures;
import static com.android.server.pm.parsing.PackageInfoUtils.checkUseInstalledOrHidden;

import android.Manifest;
import android.annotation.AppIdInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.ApplicationPackageManager;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.app.PendingIntent;
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
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.pm.Checksum;
import android.content.pm.ComponentInfo;
import android.content.pm.DataLoaderType;
import android.content.pm.FallbackCategoryProvider;
import android.content.pm.FeatureInfo;
import android.content.pm.IDexModuleRegisterCallback;
import android.content.pm.IOnChecksumsReadyListener;
import android.content.pm.IPackageChangeObserver;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageLoadingProgressCallback;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageManagerNative;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.IStagedApexObserver;
import android.content.pm.IncrementalStatesInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.InstantAppInfo;
import android.content.pm.InstantAppRequest;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageChangeEvent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ComponentEnabledSetting;
import android.content.pm.PackageManager.ComponentType;
import android.content.pm.PackageManager.LegacyPackageDeleteObserver;
import android.content.pm.PackageManager.ModuleInfoFlags;
import android.content.pm.PackageManager.Property;
import android.content.pm.PackageManager.PropertyLocation;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageManagerInternal.PackageListObserver;
import android.content.pm.PackageManagerInternal.PrivateResolveFlags;
import android.content.pm.PackagePartitions;
import android.content.pm.PackageUserState;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProcessInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.SELinuxUtil;
import android.content.pm.ServiceInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.StagedApexInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.TestUtilityService;
import android.content.pm.UserInfo;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.ArtManager;
import android.content.pm.dex.IArtManager;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingPackageUtils.ParseFlags;
import android.content.pm.parsing.component.ParsedActivity;
import android.content.pm.parsing.component.ParsedInstrumentation;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.content.pm.parsing.component.ParsedProvider;
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
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelableException;
import android.os.PersistableBundle;
import android.os.PowerWhitelistManager;
import android.os.Process;
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
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.permission.PermissionManager;
import android.provider.ContactsContract;
import android.provider.DeviceConfig;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.security.KeyStore;
import android.service.pm.PackageServiceDumpProto;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.LogPrinter;
import android.util.PackageUtils;
import android.util.Pair;
import android.util.PrintStreamPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimingsTraceLog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.F2fsUtils;
import com.android.internal.content.PackageHelper;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.telephony.CarrierAppUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.PackageWatchdog;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.Watchdog;
import com.android.server.apphibernation.AppHibernationManagerInternal;
import com.android.server.apphibernation.AppHibernationService;
import com.android.server.compat.CompatChange;
import com.android.server.compat.PlatformCompat;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.Settings.DatabaseVersion;
import com.android.server.pm.Settings.VersionInfo;
import com.android.server.pm.dex.ArtManagerService;
import com.android.server.pm.dex.ArtUtils;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.dex.DexoptOptions;
import com.android.server.pm.dex.PackageDexUsage;
import com.android.server.pm.dex.ViewCompiler;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.permission.LegacyPermissionManagerService;
import com.android.server.pm.permission.PermissionManagerService;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackageApi;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateImpl;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.pm.verify.domain.DomainVerificationService;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy;
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV1;
import com.android.server.rollback.RollbackManagerInternal;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.utils.Watchable;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedLongSparseArray;
import com.android.server.utils.WatchedSparseBooleanArray;
import com.android.server.utils.WatchedSparseIntArray;
import com.android.server.utils.Watcher;
import com.android.server.wm.ActivityTaskManagerInternal;

import dalvik.system.VMRuntime;

import libcore.util.EmptyArray;
import libcore.util.HexEncoding;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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
public class PackageManagerService extends IPackageManager.Stub
        implements PackageSender, TestUtilityService {
    static final String TAG = "PackageManager";
    public static final boolean DEBUG_SETTINGS = false;
    static final boolean DEBUG_PREFERRED = false;
    static final boolean DEBUG_UPGRADE = false;
    static final boolean DEBUG_DOMAIN_VERIFICATION = false;
    static final boolean DEBUG_BACKUP = false;
    public static final boolean DEBUG_INSTALL = false;
    public static final boolean DEBUG_REMOVE = false;
    private static final boolean DEBUG_BROADCASTS = false;
    static final boolean DEBUG_PACKAGE_INFO = false;
    static final boolean DEBUG_INTENT_MATCHING = false;
    public static final boolean DEBUG_PACKAGE_SCANNING = false;
    static final boolean DEBUG_VERIFY = false;
    public static final boolean DEBUG_PERMISSIONS = false;
    private static final boolean DEBUG_SHARED_LIBRARIES = false;
    public static final boolean DEBUG_COMPRESSION = Build.IS_DEBUGGABLE;
    public static final boolean TRACE_SNAPSHOTS = false;
    private static final boolean DEBUG_PER_UID_READ_TIMEOUTS = false;

    // Debug output for dexopting. This is shared between PackageManagerService, OtaDexoptService
    // and PackageDexOptimizer. All these classes have their own flag to allow switching a single
    // user, but by default initialize to this.
    public static final boolean DEBUG_DEXOPT = false;

    static final boolean DEBUG_ABI_SELECTION = false;
    public static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;
    private static final boolean DEBUG_APP_DATA = false;

    /** REMOVE. According to Svet, this was only used to reset permissions during development. */
    static final boolean CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE = false;

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
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScanFlags {}

    /**
     * Used as the result code of the {@link #getPackageStartability}.
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
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is allowed to start.
     */
    static final int PACKAGE_STARTABILITY_OK = 0;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it's not found
     * (could be due to that package is invisible to the given user).
     */
    static final int PACKAGE_STARTABILITY_NOT_FOUND = 1;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it's not a system app
     * and the system is running in safe mode.
     */
    static final int PACKAGE_STARTABILITY_NOT_SYSTEM = 2;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it's currently frozen.
     */
    static final int PACKAGE_STARTABILITY_FROZEN = 3;

    /**
     * Used as the result code of the {@link #getPackageStartability} to indicate
     * the given package is <b>not</b> allowed to start because it doesn't support
     * direct boot.
     */
    static final int PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED = 4;

    private static final String STATIC_SHARED_LIB_DELIMITER = "_";
    /** Extension of the compressed packages */
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
     * Wall-clock timeout (in milliseconds) after which we *require* that an fstrim
     * be run on this device.  We use the value in the Settings.Global.MANDATORY_FSTRIM_INTERVAL
     * settings entry if available, otherwise we use the hardcoded default.  If it's been
     * more than this long since the last fstrim, we force one during the boot sequence.
     *
     * This backstops other fstrim scheduling:  if the device is alive at midnight+idle,
     * one gets run at the next available charging+idle time.  This final mandatory
     * no-fstrim check kicks in only of the other scheduling criteria is never met.
     */
    private static final long DEFAULT_MANDATORY_FSTRIM_INTERVAL = 3 * DateUtils.DAY_IN_MILLIS;

    /**
     * The default maximum time to wait for the verification agent to return in
     * milliseconds.
     */
    static final long DEFAULT_VERIFICATION_TIMEOUT = 10 * 1000;

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

    /**
     * Apps targeting Android S and above need to declare dependencies to the public native
     * shared libraries that are defined by the device maker using {@code uses-native-library} tag
     * in its {@code AndroidManifest.xml}.
     *
     * If any of the dependencies cannot be satisfied, i.e. one of the dependency doesn't exist,
     * the package manager rejects to install the app. The dependency can be specified as optional
     * using {@code android:required} attribute in the tag, in which case failing to satisfy the
     * dependency doesn't stop the installation.
     * <p>Once installed, an app is provided with only the native shared libraries that are
     * specified in the app manifest. {@code dlopen}ing a native shared library that doesn't appear
     * in the app manifest will fail even if it actually exists on the device.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long ENFORCE_NATIVE_SHARED_LIBRARY_DEPENDENCIES = 142191088;

    public static final String PLATFORM_PACKAGE_NAME = "android";

    static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static final String PACKAGE_SCHEME = "package";

    private static final String COMPANION_PACKAGE_NAME = "com.android.companiondevicemanager";

    // Compilation reasons.
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
    public static final int REASON_SHARED = 13;

    public static final int REASON_LAST = REASON_SHARED;

    /**
     * The initial enabled state of the cache before other checks are done.
     */
    private static final boolean DEFAULT_PACKAGE_PARSER_CACHE_ENABLED = true;

    /**
     * Whether to skip all other checks and force the cache to be enabled.
     *
     * Setting this to true will cause the cache to be named "debug" to avoid eviction from
     * build fingerprint changes.
     */
    private static final boolean FORCE_PACKAGE_PARSED_CACHE_ENABLED = false;

    /**
     * Permissions required in order to receive instant application lifecycle broadcasts.
     */
    private static final String[] INSTANT_APP_BROADCAST_PERMISSION =
            new String[] { android.Manifest.permission.ACCESS_INSTANT_APPS };

    static final String RANDOM_DIR_PREFIX = "~~";

    final Handler mHandler;

    final ProcessLoggingHandler mProcessLoggingHandler;

    private final boolean mEnableFreeCacheV2;

    final int mSdkVersion;
    final Context mContext;
    final boolean mFactoryTest;
    final boolean mOnlyCore;
    final DisplayMetrics mMetrics;
    final int mDefParseFlags;
    final String[] mSeparateProcesses;
    final boolean mIsUpgrade;
    final boolean mIsPreNUpgrade;
    final boolean mIsPreNMR1Upgrade;
    final boolean mIsPreQUpgrade;

    @GuardedBy("mLock")
    private boolean mDexOptDialogShown;

    // Used for privilege escalation. MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    @GuardedBy("mInstallLock")
    final Installer mInstaller;

    /** Directory where installed applications are stored */
    private final File mAppInstallDir;
    /** Directory where installed application's 32-bit native libraries are copied. */
    @VisibleForTesting
    final File mAppLib32InstallDir;

    static File getAppLib32InstallDir() {
        return new File(Environment.getDataDirectory(), "app-lib");
    }

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
     * Tracks new system packages [received in an OTA] that we expect to
     * find updated user-installed versions. Keys are package name, values
     * are package location.
     */
    final ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();

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

    private final PackageManagerInternal mPmInternal;
    private final TestUtilityService mTestUtilityService;

    @Watched
    @GuardedBy("mLock")
    final Settings mSettings;

    /**
     * Set of package names that are currently "frozen", which means active
     * surgery is being done on the code/data for that package. The platform
     * will refuse to launch frozen packages to avoid race conditions.
     *
     * @see PackageFreezer
     */
    @GuardedBy("mLock")
    final ArraySet<String> mFrozenPackages = new ArraySet<>();

    final ProtectedPackages mProtectedPackages;

    @GuardedBy("mLoadedVolumes")
    final ArraySet<String> mLoadedVolumes = new ArraySet<>();

    boolean mFirstBoot;

    final boolean mIsEngBuild;
    private final boolean mIsUserDebugBuild;
    private final String mIncrementalVersion;

    PackageManagerInternal.ExternalSourcesPolicy mExternalSourcesPolicy;

    @GuardedBy("mAvailableFeatures")
    final ArrayMap<String, FeatureInfo> mAvailableFeatures;

    @Watched
    final InstantAppRegistry mInstantAppRegistry;

    @GuardedBy("mLock")
    int mChangedPackagesSequenceNumber;
    /**
     * List of changed [installed, removed or updated] packages.
     * mapping from user id -> sequence number -> package name
     */
    @GuardedBy("mLock")
    final SparseArray<SparseArray<String>> mChangedPackages = new SparseArray<>();
    /**
     * The sequence number of the last change to a package.
     * mapping from user id -> package name -> sequence number
     */
    @GuardedBy("mLock")
    final SparseArray<Map<String, Integer>> mChangedPackagesSequenceNumbers = new SparseArray<>();

    @GuardedBy("mLock")
    final private ArraySet<PackageListObserver> mPackageListObservers = new ArraySet<>();

    private final ModuleInfoProvider mModuleInfoProvider;

    final ApexManager mApexManager;

    final Injector mInjector;

    /**
     * The list of all system partitions that may contain packages in ascending order of
     * specificity (the more generic, the earlier in the list a partition appears).
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static final List<ScanPartition> SYSTEM_PARTITIONS = Collections.unmodifiableList(
            PackagePartitions.getOrderedPartitions(ScanPartition::new));

    private final List<ScanPartition> mDirsToScanAsSystem;

    final OverlayConfig mOverlayConfig;

    @GuardedBy("itself")
    final private ArrayList<IPackageChangeObserver> mPackageChangeObservers =
        new ArrayList<>();

    // Cached parsed flag value. Invalidated on each flag change.
    PerUidReadTimeouts[] mPerUidReadTimeoutsCache;

    private static final PerUidReadTimeouts[] EMPTY_PER_UID_READ_TIMEOUTS_ARRAY = {};

    /**
     * Unit tests will instantiate, extend and/or mock to mock dependencies / behaviors.
     *
     * NOTE: All getters should return the same instance for every call.
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Injector {

        @VisibleForTesting(visibility = Visibility.PRIVATE)
        interface Producer<T> {
            /** Produce an instance of type {@link T} */
            T produce(Injector injector, PackageManagerService packageManager);
        }

        interface ProducerWithArgument<T, R> {
            T produce(Injector injector, PackageManagerService packageManager, R argument);
        }

        interface ServiceProducer {
            <T> T produce(Class<T> c);
        }

        @VisibleForTesting(visibility = Visibility.PRIVATE)
        static class Singleton<T> {
            private final Producer<T> mProducer;
            private volatile T mInstance = null;
            Singleton(Producer<T> producer) {
                this.mProducer = producer;
            }
            T get(Injector injector, PackageManagerService packageManagerService) {
                if (mInstance == null) {
                    mInstance = mProducer.produce(injector, packageManagerService);
                }
                return mInstance;
            }
        }

        private PackageManagerService mPackageManager;

        private final PackageAbiHelper mAbiHelper;
        private final Context mContext;
        private final PackageManagerTracedLock mLock;
        private final Installer mInstaller;
        private final Object mInstallLock;
        private final Handler mBackgroundHandler;
        private final Executor mBackgroundExecutor;
        private final List<ScanPartition> mSystemPartitions;

        // ----- producers -----
        private final Singleton<ComponentResolver> mComponentResolverProducer;
        private final Singleton<PermissionManagerServiceInternal> mPermissionManagerServiceProducer;
        private final Singleton<UserManagerService> mUserManagerProducer;
        private final Singleton<Settings> mSettingsProducer;
        private final Singleton<AppsFilter> mAppsFilterProducer;
        private final Singleton<PlatformCompat> mPlatformCompatProducer;
        private final Singleton<SystemConfig> mSystemConfigProducer;
        private final Singleton<PackageDexOptimizer> mPackageDexOptimizerProducer;
        private final Singleton<DexManager> mDexManagerProducer;
        private final Singleton<ArtManagerService> mArtManagerServiceProducer;
        private final Singleton<ApexManager> mApexManagerProducer;
        private final Singleton<ViewCompiler> mViewCompilerProducer;
        private final Singleton<IncrementalManager> mIncrementalManagerProducer;
        private final Singleton<DefaultAppProvider> mDefaultAppProviderProducer;
        private final Singleton<DisplayMetrics> mDisplayMetricsProducer;
        private final Producer<PackageParser2> mScanningCachingPackageParserProducer;
        private final Producer<PackageParser2> mScanningPackageParserProducer;
        private final Producer<PackageParser2> mPreparingPackageParserProducer;
        private final Singleton<PackageInstallerService> mPackageInstallerServiceProducer;
        private final ProducerWithArgument<InstantAppResolverConnection, ComponentName>
                mInstantAppResolverConnectionProducer;
        private final Singleton<LegacyPermissionManagerInternal>
                mLegacyPermissionManagerInternalProducer;
        private final SystemWrapper mSystemWrapper;
        private final ServiceProducer mGetLocalServiceProducer;
        private final ServiceProducer mGetSystemServiceProducer;
        private final Singleton<ModuleInfoProvider> mModuleInfoProviderProducer;
        private final Singleton<DomainVerificationManagerInternal>
                mDomainVerificationManagerInternalProducer;
        private final Singleton<Handler> mHandlerProducer;

        Injector(Context context, PackageManagerTracedLock lock, Installer installer,
                Object installLock, PackageAbiHelper abiHelper,
                Handler backgroundHandler,
                List<ScanPartition> systemPartitions,
                Producer<ComponentResolver> componentResolverProducer,
                Producer<PermissionManagerServiceInternal> permissionManagerServiceProducer,
                Producer<UserManagerService> userManagerProducer,
                Producer<Settings> settingsProducer,
                Producer<AppsFilter> appsFilterProducer,
                Producer<PlatformCompat> platformCompatProducer,
                Producer<SystemConfig> systemConfigProducer,
                Producer<PackageDexOptimizer> packageDexOptimizerProducer,
                Producer<DexManager> dexManagerProducer,
                Producer<ArtManagerService> artManagerServiceProducer,
                Producer<ApexManager> apexManagerProducer,
                Producer<ViewCompiler> viewCompilerProducer,
                Producer<IncrementalManager> incrementalManagerProducer,
                Producer<DefaultAppProvider> defaultAppProviderProducer,
                Producer<DisplayMetrics> displayMetricsProducer,
                Producer<PackageParser2> scanningCachingPackageParserProducer,
                Producer<PackageParser2> scanningPackageParserProducer,
                Producer<PackageParser2> preparingPackageParserProducer,
                Producer<PackageInstallerService> packageInstallerServiceProducer,
                ProducerWithArgument<InstantAppResolverConnection, ComponentName>
                        instantAppResolverConnectionProducer,
                Producer<ModuleInfoProvider> moduleInfoProviderProducer,
                Producer<LegacyPermissionManagerInternal> legacyPermissionManagerInternalProducer,
                Producer<DomainVerificationManagerInternal>
                        domainVerificationManagerInternalProducer,
                Producer<Handler> handlerProducer,
                SystemWrapper systemWrapper,
                ServiceProducer getLocalServiceProducer,
                ServiceProducer getSystemServiceProducer) {
            mContext = context;
            mLock = lock;
            mInstaller = installer;
            mAbiHelper = abiHelper;
            mInstallLock = installLock;
            mBackgroundHandler = backgroundHandler;
            mBackgroundExecutor = new HandlerExecutor(backgroundHandler);
            mSystemPartitions = systemPartitions;
            mComponentResolverProducer = new Singleton<>(componentResolverProducer);
            mPermissionManagerServiceProducer = new Singleton<>(permissionManagerServiceProducer);
            mUserManagerProducer = new Singleton<>(userManagerProducer);
            mSettingsProducer = new Singleton<>(settingsProducer);
            mAppsFilterProducer = new Singleton<>(appsFilterProducer);
            mPlatformCompatProducer = new Singleton<>(platformCompatProducer);
            mSystemConfigProducer = new Singleton<>(systemConfigProducer);
            mPackageDexOptimizerProducer = new Singleton<>(packageDexOptimizerProducer);
            mDexManagerProducer = new Singleton<>(dexManagerProducer);
            mArtManagerServiceProducer = new Singleton<>(artManagerServiceProducer);
            mApexManagerProducer = new Singleton<>(apexManagerProducer);
            mViewCompilerProducer = new Singleton<>(viewCompilerProducer);
            mIncrementalManagerProducer = new Singleton<>(incrementalManagerProducer);
            mDefaultAppProviderProducer = new Singleton<>(defaultAppProviderProducer);
            mDisplayMetricsProducer = new Singleton<>(displayMetricsProducer);
            mScanningCachingPackageParserProducer = scanningCachingPackageParserProducer;
            mScanningPackageParserProducer = scanningPackageParserProducer;
            mPreparingPackageParserProducer = preparingPackageParserProducer;
            mPackageInstallerServiceProducer = new Singleton<>(packageInstallerServiceProducer);
            mInstantAppResolverConnectionProducer = instantAppResolverConnectionProducer;
            mModuleInfoProviderProducer = new Singleton<>(moduleInfoProviderProducer);
            mLegacyPermissionManagerInternalProducer = new Singleton<>(
                    legacyPermissionManagerInternalProducer);
            mSystemWrapper = systemWrapper;
            mGetLocalServiceProducer = getLocalServiceProducer;
            mGetSystemServiceProducer = getSystemServiceProducer;
            mDomainVerificationManagerInternalProducer =
                    new Singleton<>(domainVerificationManagerInternalProducer);
            mHandlerProducer = new Singleton<>(handlerProducer);
        }

        /**
         * Bootstraps this injector with the {@link PackageManagerService instance to which it
         * belongs.
         */
        public void bootstrap(PackageManagerService pm) {
            this.mPackageManager = pm;
        }

        public UserManagerInternal getUserManagerInternal() {
            return getUserManagerService().getInternalForInjectorOnly();
        }

        public PackageAbiHelper getAbiHelper() {
            return mAbiHelper;
        }

        public Object getInstallLock() {
            return mInstallLock;
        }

        public List<ScanPartition> getSystemPartitions() {
            return mSystemPartitions;
        }

        public UserManagerService getUserManagerService() {
            return mUserManagerProducer.get(this, mPackageManager);
        }

        public PackageManagerTracedLock getLock() {
            return mLock;
        }

        public Installer getInstaller() {
            return mInstaller;
        }

        public ComponentResolver getComponentResolver() {
            return mComponentResolverProducer.get(this, mPackageManager);
        }

        public PermissionManagerServiceInternal getPermissionManagerServiceInternal() {
            return mPermissionManagerServiceProducer.get(this, mPackageManager);
        }

        public Context getContext() {
            return mContext;
        }

        public Settings getSettings() {
            return mSettingsProducer.get(this, mPackageManager);
        }

        public AppsFilter getAppsFilter() {
            return mAppsFilterProducer.get(this, mPackageManager);
        }

        public PlatformCompat getCompatibility() {
            return mPlatformCompatProducer.get(this, mPackageManager);
        }

        public SystemConfig getSystemConfig() {
            return mSystemConfigProducer.get(this, mPackageManager);
        }

        public PackageDexOptimizer getPackageDexOptimizer() {
            return mPackageDexOptimizerProducer.get(this, mPackageManager);
        }

        public DexManager getDexManager() {
            return mDexManagerProducer.get(this, mPackageManager);
        }

        public ArtManagerService getArtManagerService() {
            return mArtManagerServiceProducer.get(this, mPackageManager);
        }

        public ApexManager getApexManager() {
            return mApexManagerProducer.get(this, mPackageManager);
        }

        public ViewCompiler getViewCompiler() {
            return mViewCompilerProducer.get(this, mPackageManager);
        }

        public Handler getBackgroundHandler() {
            return mBackgroundHandler;
        }

        public Executor getBackgroundExecutor() {
            return mBackgroundExecutor;
        }

        public DisplayMetrics getDisplayMetrics() {
            return mDisplayMetricsProducer.get(this, mPackageManager);
        }

        public <T> T getLocalService(Class<T> c) {
            return mGetLocalServiceProducer.produce(c);
        }

        public <T> T getSystemService(Class<T> c) {
            return mGetSystemServiceProducer.produce(c);
        }

        public SystemWrapper getSystemWrapper() {
            return mSystemWrapper;
        }

        public IncrementalManager getIncrementalManager() {
            return mIncrementalManagerProducer.get(this, mPackageManager);
        }

        public DefaultAppProvider getDefaultAppProvider() {
            return mDefaultAppProviderProducer.get(this, mPackageManager);
        }

        public PackageParser2 getScanningCachingPackageParser() {
            return mScanningCachingPackageParserProducer.produce(this, mPackageManager);
        }
        public PackageParser2 getScanningPackageParser() {
            return mScanningPackageParserProducer.produce(this, mPackageManager);
        }
        public PackageParser2 getPreparingPackageParser() {
            return mPreparingPackageParserProducer.produce(this, mPackageManager);
        }

        public PackageInstallerService getPackageInstallerService() {
            return mPackageInstallerServiceProducer.get(this, mPackageManager);
        }

        public InstantAppResolverConnection getInstantAppResolverConnection(
                ComponentName instantAppResolverComponent) {
            return mInstantAppResolverConnectionProducer.produce(
                    this, mPackageManager, instantAppResolverComponent);
        }

        public ModuleInfoProvider getModuleInfoProvider() {
            return mModuleInfoProviderProducer.get(this, mPackageManager);
        }

        public LegacyPermissionManagerInternal getLegacyPermissionManagerInternal() {
            return mLegacyPermissionManagerInternalProducer.get(this, mPackageManager);
        }

        public DomainVerificationManagerInternal getDomainVerificationManagerInternal() {
            return mDomainVerificationManagerInternalProducer.get(this, mPackageManager);
        }

        public Handler getHandler() {
            return mHandlerProducer.get(this, mPackageManager);
        }
    }

    /** Provides an abstraction to static access to system state. */
    public interface SystemWrapper {
        void disablePackageCaches();
        void enablePackageCaches();
    }

    private static class DefaultSystemWrapper implements SystemWrapper {

        @Override
        public void disablePackageCaches() {
            // disable all package caches that shouldn't apply within system server
            PackageManager.disableApplicationInfoCache();
            PackageManager.disablePackageInfoCache();
            ApplicationPackageManager.invalidateGetPackagesForUidCache();
            ApplicationPackageManager.disableGetPackagesForUidCache();
            ApplicationPackageManager.invalidateHasSystemFeatureCache();

            // Avoid invalidation-thrashing by preventing cache invalidations from causing property
            // writes if the cache isn't enabled yet.  We re-enable writes later when we're
            // done initializing.
            sSnapshotCorked.incrementAndGet();
            PackageManager.corkPackageInfoCache();
        }

        @Override
        public void enablePackageCaches() {
            // Uncork cache invalidations and allow clients to cache package information.
            int corking = sSnapshotCorked.decrementAndGet();
            if (TRACE_SNAPSHOTS && corking == 0) {
                Log.i(TAG, "snapshot: corking returns to 0");
            }
            PackageManager.uncorkPackageInfoCache();
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class TestParams {
        public ApexManager apexManager;
        public @Nullable String appPredictionServicePackage;
        public ArtManagerService artManagerService;
        public @Nullable String configuratorPackage;
        public int defParseFlags;
        public DefaultAppProvider defaultAppProvider;
        public DexManager dexManager;
        public List<ScanPartition> dirsToScanAsSystem;
        public boolean factoryTest;
        public ArrayMap<String, FeatureInfo> availableFeatures;
        public Handler handler;
        public @Nullable String incidentReportApproverPackage;
        public IncrementalManager incrementalManager;
        public PackageInstallerService installerService;
        public InstantAppRegistry instantAppRegistry;
        public InstantAppResolverConnection instantAppResolverConnection;
        public ComponentName instantAppResolverSettingsComponent;
        public boolean isPreNmr1Upgrade;
        public boolean isPreNupgrade;
        public boolean isPreQupgrade;
        public boolean isUpgrade;
        public LegacyPermissionManagerInternal legacyPermissionManagerInternal;
        public DisplayMetrics Metrics;
        public ModuleInfoProvider moduleInfoProvider;
        public MovePackageHelper.MoveCallbacks moveCallbacks;
        public boolean onlyCore;
        public OverlayConfig overlayConfig;
        public PackageDexOptimizer packageDexOptimizer;
        public PackageParser2.Callback packageParserCallback;
        public PendingPackageBroadcasts pendingPackageBroadcasts;
        public PackageManagerInternal pmInternal;
        public TestUtilityService testUtilityService;
        public ProcessLoggingHandler processLoggingHandler;
        public ProtectedPackages protectedPackages;
        public @NonNull String requiredInstallerPackage;
        public @NonNull String requiredPermissionControllerPackage;
        public @NonNull String requiredUninstallerPackage;
        public @Nullable String requiredVerifierPackage;
        public String[] separateProcesses;
        public @NonNull String servicesExtensionPackageName;
        public @Nullable String setupWizardPackage;
        public @NonNull String sharedSystemSharedLibraryPackageName;
        public @Nullable String storageManagerPackage;
        public @Nullable String defaultTextClassifierPackage;
        public @Nullable String systemTextClassifierPackage;
        public @Nullable String overlayConfigSignaturePackage;
        public ViewCompiler viewCompiler;
        public @Nullable String retailDemoPackage;
        public @Nullable String recentsPackage;
        public ComponentName resolveComponentName;
        public ArrayMap<String, AndroidPackage> packages;
        public boolean enableFreeCacheV2;
        public int sdkVersion;
        public File appInstallDir;
        public File appLib32InstallDir;
        public boolean isEngBuild;
        public boolean isUserDebugBuild;
        public int sdkInt = Build.VERSION.SDK_INT;
        public final String incrementalVersion = Build.VERSION.INCREMENTAL;
    }

    @Watched
    final AppsFilter mAppsFilter;

    final PackageParser2.Callback mPackageParserCallback;

    // Currently known shared libraries.
    @Watched
    final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>
            mSharedLibraries = new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>>
            mSharedLibrariesSnapshot =
            new SnapshotCache.Auto<>(mSharedLibraries, mSharedLibraries,
                                     "PackageManagerService.mSharedLibraries");
    @Watched
    final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>
            mStaticLibsByDeclaringPackage = new WatchedArrayMap<>();
    private final SnapshotCache<WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>>
            mStaticLibsByDeclaringPackageSnapshot =
            new SnapshotCache.Auto<>(mStaticLibsByDeclaringPackage, mStaticLibsByDeclaringPackage,
                                     "PackageManagerService.mStaticLibsByDeclaringPackage");

    // Mapping from instrumentation class names to info about them.
    @Watched
    final WatchedArrayMap<ComponentName, ParsedInstrumentation> mInstrumentation =
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

    /** List of packages waiting for verification. */
    final SparseArray<PackageVerificationState> mPendingVerification = new SparseArray<>();

    /** List of packages waiting for rollback to be enabled. */
    final SparseArray<VerificationParams> mPendingEnableRollback = new SparseArray<>();

    final PackageInstallerService mInstallerService;

    final ArtManagerService mArtManagerService;

    final PackageDexOptimizer mPackageDexOptimizer;
    // DexManager handles the usage of dex files (e.g. secondary files, whether or not a package
    // is used by other apps).
    private final DexManager mDexManager;

    final ViewCompiler mViewCompiler;

    private final AtomicInteger mNextMoveId = new AtomicInteger();
    final MovePackageHelper.MoveCallbacks mMoveCallbacks;

    // Cache of users who need badging.
    private final SparseBooleanArray mUserNeedsBadging = new SparseBooleanArray();

    /** Token for keys in mPendingVerification. */
    int mPendingVerificationToken = 0;

    /** Token for keys in mPendingEnableRollback. */
    int mPendingEnableRollbackToken = 0;

    @Watched(manual = true)
    volatile boolean mSystemReady;
    @Watched(manual = true)
    private volatile boolean mSafeMode;
    volatile boolean mHasSystemUidErrors;
    @Watched
    private final WatchedSparseBooleanArray mWebInstantAppsDisabled =
            new WatchedSparseBooleanArray();

    @Watched(manual = true)
    ApplicationInfo mAndroidApplication;
    @Watched(manual = true)
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    @Watched(manual = true)
    private ComponentName mResolveComponentName;
    AndroidPackage mPlatformPackage;
    ComponentName mCustomResolverComponentName;

    boolean mResolverReplaced = false;

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

    private final Map<String, Pair<PackageInstalledInfo, IPackageInstallObserver2>>
            mNoKillInstallObservers = Collections.synchronizedMap(new HashMap<>());

    // Internal interface for permission manager
    final PermissionManagerServiceInternal mPermissionManager;

    @Watched
    final ComponentResolver mComponentResolver;

    // List of packages names to keep cached, even if they are uninstalled for all users
    private List<String> mKeepUninstalledPackages;

    // Cached reference to IDevicePolicyManager.
    private IDevicePolicyManager mDevicePolicyManager = null;

    private File mCacheDir;

    private Future<?> mPrepareAppDataFuture;

    final IncrementalManager mIncrementalManager;

    private final DefaultAppProvider mDefaultAppProvider;

    private final LegacyPermissionManagerInternal mLegacyPermissionManager;

    private final PackageProperty mPackageProperty = new PackageProperty();

    // Set of pending broadcasts for aggregating enable/disable of components.
    @VisibleForTesting(visibility = Visibility.PACKAGE)
    public static class PendingPackageBroadcasts {
        // for each user id, a map of <package name -> components within that package>
        final SparseArray<ArrayMap<String, ArrayList<String>>> mUidMap;

        public PendingPackageBroadcasts() {
            mUidMap = new SparseArray<>(2);
        }

        public ArrayList<String> get(int userId, String packageName) {
            ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            return packages.get(packageName);
        }

        public void put(int userId, String packageName, ArrayList<String> components) {
            ArrayMap<String, ArrayList<String>> packages = getOrAllocate(userId);
            packages.put(packageName, components);
        }

        public void remove(int userId, String packageName) {
            ArrayMap<String, ArrayList<String>> packages = mUidMap.get(userId);
            if (packages != null) {
                packages.remove(packageName);
            }
        }

        public void remove(int userId) {
            mUidMap.remove(userId);
        }

        public int userIdCount() {
            return mUidMap.size();
        }

        public int userIdAt(int n) {
            return mUidMap.keyAt(n);
        }

        public ArrayMap<String, ArrayList<String>> packagesForUserId(int userId) {
            return mUidMap.get(userId);
        }

        public int size() {
            // total number of pending broadcast entries across all userIds
            int num = 0;
            for (int i = 0; i< mUidMap.size(); i++) {
                num += mUidMap.valueAt(i).size();
            }
            return num;
        }

        public void clear() {
            mUidMap.clear();
        }

        private ArrayMap<String, ArrayList<String>> getOrAllocate(int userId) {
            ArrayMap<String, ArrayList<String>> map = mUidMap.get(userId);
            if (map == null) {
                map = new ArrayMap<>();
                mUidMap.put(userId, map);
            }
            return map;
        }
    }
    final PendingPackageBroadcasts mPendingBroadcasts;

    static final int SEND_PENDING_BROADCAST = 1;
    static final int INIT_COPY = 5;
    static final int POST_INSTALL = 9;
    static final int WRITE_SETTINGS = 13;
    static final int WRITE_PACKAGE_RESTRICTIONS = 14;
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
    static final int SNAPSHOT_UNCORK = 28;

    static final int DEFERRED_NO_KILL_POST_DELETE_DELAY_MS = 3 * 1000;
    private static final int DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS = 500;

    static final int WRITE_SETTINGS_DELAY = 10*1000;  // 10 seconds

    private static final long BROADCAST_DELAY_DURING_STARTUP = 10 * 1000L; // 10 seconds (in millis)
    private static final long BROADCAST_DELAY = 1 * 1000L; // 1 second (in millis)

    // When the service constructor finished plus a delay (used for broadcast delay computation)
    private long mServiceStartWithDelay;

    private static final long DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD =
            2 * 60 * 60 * 1000L; /* two hours */

    final UserManagerService mUserManager;

    // Stores a list of users whose package restrictions file needs to be updated
    final ArraySet<Integer> mDirtyUsers = new ArraySet<>();

    // Recordkeeping of restore-after-install operations that are currently in flight
    // between the Package Manager and the Backup Manager
    static class PostInstallData {
        @Nullable
        public final InstallArgs args;
        @NonNull
        public final PackageInstalledInfo res;
        @Nullable
        public final Runnable mPostInstallRunnable;

        PostInstallData(@Nullable InstallArgs args, @NonNull PackageInstalledInfo res,
                @Nullable Runnable postInstallRunnable) {
            this.args = args;
            this.res = res;
            mPostInstallRunnable = postInstallRunnable;
        }
    }

    final SparseArray<PostInstallData> mRunningInstalls = new SparseArray<>();
    int mNextInstallToken = 1;  // nonzero; will be wrapped back to 1 when ++ overflows

    // XML tags for backup/restore of various bits of state
    private static final String TAG_PREFERRED_BACKUP = "pa";
    private static final String TAG_DEFAULT_APPS = "da";

    final @Nullable String mRequiredVerifierPackage;
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

    @GuardedBy("mLock")
    private final PackageUsage mPackageUsage = new PackageUsage();
    final CompilerStats mCompilerStats = new CompilerStats();

    private final DomainVerificationConnection mDomainVerificationConnection;

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
        public final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> sharedLibs;
        public final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> staticLibs;
        public final WatchedArrayMap<ComponentName, ParsedInstrumentation> instrumentation;
        public final WatchedSparseBooleanArray webInstantAppsDisabled;
        public final ComponentName resolveComponentName;
        public final ActivityInfo resolveActivity;
        public final ActivityInfo instantAppInstallerActivity;
        public final ResolveInfo instantAppInstallerInfo;
        public final InstantAppRegistry instantAppRegistry;
        public final ApplicationInfo androidApplication;
        public final String appPredictionServicePackage;
        public final AppsFilter appsFilter;
        public final ComponentResolver componentResolver;
        public final PackageManagerService service;

        Snapshot(int type) {
            if (type == Snapshot.SNAPPED) {
                settings = mSettings.snapshot();
                isolatedOwners = mIsolatedOwnersSnapshot.snapshot();
                packages = mPackagesSnapshot.snapshot();
                sharedLibs = mSharedLibrariesSnapshot.snapshot();
                staticLibs = mStaticLibsByDeclaringPackageSnapshot.snapshot();
                instrumentation = mInstrumentationSnapshot.snapshot();
                resolveComponentName = mResolveComponentName.clone();
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
            } else if (type == Snapshot.LIVE) {
                settings = mSettings;
                isolatedOwners = mIsolatedOwners;
                packages = mPackages;
                sharedLibs = mSharedLibraries;
                staticLibs = mStaticLibsByDeclaringPackage;
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
            } else {
                throw new IllegalArgumentException();
            }
            service = PackageManagerService.this;
        }
    }

    // Compute read-only functions, based on live data.  This attribute may be modified multiple
    // times during the PackageManagerService constructor but it should not be modified thereafter.
    private ComputerLocked mLiveComputer;

    // A lock-free cache for frequently called functions.
    private volatile Computer mSnapshotComputer;

    // A trampoline that directs callers to either the live or snapshot computer.
    private final ComputerTracker mComputer = new ComputerTracker(this);

    // If true, the snapshot is invalid (stale).  The attribute is static since it may be
    // set from outside classes.  The attribute may be set to true anywhere, although it
    // should only be set true while holding mLock.  However, the attribute id guaranteed
    // to be set false only while mLock and mSnapshotLock are both held.
    private static final AtomicBoolean sSnapshotInvalid = new AtomicBoolean(true);
    // If true, the snapshot is corked.  Do not create a new snapshot but use the live
    // computer.  This throttles snapshot creation during periods of churn in Package
    // Manager.
    static final AtomicInteger sSnapshotCorked = new AtomicInteger(0);

    static final ThreadLocal<ThreadComputer> sThreadComputer =
            ThreadLocal.withInitial(ThreadComputer::new);

    /**
     * This lock is used to make reads from {@link #sSnapshotInvalid} and
     * {@link #mSnapshotComputer} atomic inside {@code snapshotComputer()}.  This lock is
     * not meant to be used outside that method.  This lock must be taken before
     * {@link #mLock} is taken.
     */
    private final Object mSnapshotLock = new Object();

    /**
     * The snapshot statistics.  These are collected to track performance and to identify
     * situations in which the snapshots are misbehaving.
     */
    private final SnapshotStatistics mSnapshotStatistics;

    // The snapshot disable/enable switch.  An image with the flag set true uses snapshots
    // and an image with the flag set false does not use snapshots.
    private static final boolean SNAPSHOT_ENABLED = true;

    // The per-instance snapshot disable/enable flag.  This is generally set to false in
    // test instances and set to SNAPSHOT_ENABLED in operational instances.
    private final boolean mSnapshotEnabled;

    /**
     * Return the live computer.
     */
    Computer liveComputer() {
        return mLiveComputer;
    }

    /**
     * Return the cached computer.  The method will rebuild the cached computer if necessary.
     * The live computer will be returned if snapshots are disabled.
     */
    Computer snapshotComputer() {
        if (!mSnapshotEnabled) {
            return mLiveComputer;
        }
        if (Thread.holdsLock(mLock)) {
            // If the current thread holds mLock then it may have modified state but not
            // yet invalidated the snapshot.  Always give the thread the live computer.
            return mLiveComputer;
        } else if (sSnapshotCorked.get() > 0) {
            // Snapshots are corked, which means new ones should not be built right now.
            mSnapshotStatistics.corked();
            return mLiveComputer;
        }
        synchronized (mSnapshotLock) {
            // This synchronization block serializes access to the snapshot computer and
            // to the code that samples mSnapshotInvalid.
            Computer c = mSnapshotComputer;
            if (sSnapshotInvalid.getAndSet(false) || (c == null)) {
                // The snapshot is invalid if it is marked as invalid or if it is null.  If it
                // is null, then it is currently being rebuilt by rebuildSnapshot().
                synchronized (mLock) {
                    // Rebuild the snapshot if it is invalid.  Note that the snapshot might be
                    // invalidated as it is rebuilt.  However, the snapshot is still
                    // self-consistent (the lock is being held) and is current as of the time
                    // this function is entered.
                    rebuildSnapshot();

                    // Guaranteed to be non-null.  mSnapshotComputer is only be set to null
                    // temporarily in rebuildSnapshot(), which is guarded by mLock().  Since
                    // the mLock is held in this block and since rebuildSnapshot() is
                    // complete, the attribute can not now be null.
                    c = mSnapshotComputer;
                }
            }
            c.use();
            return c;
        }
    }

    /**
     * Rebuild the cached computer.  mSnapshotComputer is temporarily set to null to block other
     * threads from using the invalid computer until it is rebuilt.
     */
    @GuardedBy({ "mLock", "mSnapshotLock"})
    private void rebuildSnapshot() {
        final long now = SystemClock.currentTimeMicro();
        final int hits = mSnapshotComputer == null ? -1 : mSnapshotComputer.getUsed();
        mSnapshotComputer = null;
        final Snapshot args = new Snapshot(Snapshot.SNAPPED);
        mSnapshotComputer = new ComputerEngine(args);
        final long done = SystemClock.currentTimeMicro();

        mSnapshotStatistics.rebuild(now, done, hits);
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
        sSnapshotInvalid.set(true);
    }

    /**
     * Report a locally-detected change to observers.  The <what> parameter is left null,
     * but it signifies that the change was detected by PackageManagerService itself.
     */
    private static void onChanged() {
        onChange(null);
    }

    @Override
    public void notifyPackagesReplacedReceived(String[] packages) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        for (String packageName : packages) {
            final boolean filterApp;
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                filterApp = shouldFilterApplicationLocked(ps, callingUid, callingUserId);
            }
            if (!filterApp) {
                notifyInstallObserver(packageName);
            }
        }
    }

    void notifyInstallObserver(String packageName) {
        Pair<PackageInstalledInfo, IPackageInstallObserver2> pair =
                mNoKillInstallObservers.remove(packageName);

        if (pair != null) {
            notifyInstallObserver(pair.first, pair.second);
        }
    }

    void notifyInstallObserver(PackageInstalledInfo info,
            IPackageInstallObserver2 installObserver) {
        if (installObserver != null) {
            try {
                Bundle extras = extrasForInstallResult(info);
                installObserver.onPackageInstalled(info.mName, info.mReturnCode,
                        info.mReturnMsg, extras);
            } catch (RemoteException e) {
                Slog.i(TAG, "Observer no longer exists.");
            }
        }
    }

    void scheduleDeferredNoKillInstallObserver(PackageInstalledInfo info,
            IPackageInstallObserver2 observer) {
        String packageName = info.mPkg.getPackageName();
        mNoKillInstallObservers.put(packageName, Pair.create(info, observer));
        Message message = mHandler.obtainMessage(DEFERRED_NO_KILL_INSTALL_OBSERVER, packageName);
        mHandler.sendMessageDelayed(message, DEFERRED_NO_KILL_INSTALL_OBSERVER_DELAY_MS);
    }

    @Override
    public void requestChecksums(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int optional,
            @Checksum.TypeMask int required, @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId) {
        requestChecksumsInternal(packageName, includeSplits, optional, required, trustedInstallers,
                onChecksumsReadyListener, userId, mInjector.getBackgroundExecutor(),
                mInjector.getBackgroundHandler());
    }

    private void requestChecksumsInternal(@NonNull String packageName, boolean includeSplits,
            @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
            @Nullable List trustedInstallers,
            @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
            @NonNull Executor executor, @NonNull Handler handler) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(onChecksumsReadyListener);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(handler);

        final ApplicationInfo applicationInfo = getApplicationInfoInternal(packageName, 0,
                Binder.getCallingUid(), userId);
        if (applicationInfo == null) {
            throw new ParcelableException(new PackageManager.NameNotFoundException(packageName));
        }
        final InstallSourceInfo installSourceInfo = getInstallSourceInfo(packageName);
        final String installerPackageName =
                installSourceInfo != null ? installSourceInfo.getInitiatingPackageName() : null;

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
                    () -> mInjector.getIncrementalManager(),
                    () -> mPmInternal);
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

    private static Bundle extrasForInstallResult(PackageInstalledInfo res) {
        Bundle extras = null;
        switch (res.mReturnCode) {
            case PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION: {
                extras = new Bundle();
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PERMISSION,
                        res.mOrigPermission);
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE,
                        res.mOrigPackage);
                break;
            }
            case PackageManager.INSTALL_SUCCEEDED: {
                extras = new Bundle();
                extras.putBoolean(Intent.EXTRA_REPLACING,
                        res.mRemovedInfo != null && res.mRemovedInfo.mRemovedPackage != null);
                break;
            }
        }
        return extras;
    }

    void scheduleWriteSettingsLocked() {
        // We normally invalidate when we write settings, but in cases where we delay and
        // coalesce settings writes, this strategy would have us invalidate the cache too late.
        // Invalidating on schedule addresses this problem.
        invalidatePackageInfoCache();
        if (!mHandler.hasMessages(WRITE_SETTINGS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_SETTINGS, WRITE_SETTINGS_DELAY);
        }
    }

    void scheduleWritePackageListLocked(int userId) {
        invalidatePackageInfoCache();
        if (!mHandler.hasMessages(WRITE_PACKAGE_LIST)) {
            Message msg = mHandler.obtainMessage(WRITE_PACKAGE_LIST);
            msg.arg1 = userId;
            mHandler.sendMessageDelayed(msg, WRITE_SETTINGS_DELAY);
        }
    }

    void scheduleWritePackageRestrictionsLocked(UserHandle user) {
        final int userId = user == null ? UserHandle.USER_ALL : user.getIdentifier();
        scheduleWritePackageRestrictionsLocked(userId);
    }

    void scheduleWritePackageRestrictionsLocked(int userId) {
        invalidatePackageInfoCache();
        final int[] userIds = (userId == UserHandle.USER_ALL)
                ? mUserManager.getUserIds() : new int[]{userId};
        for (int nextUserId : userIds) {
            if (!mUserManager.exists(nextUserId)) return;

            mDirtyUsers.add(nextUserId);
            if (!mHandler.hasMessages(WRITE_PACKAGE_RESTRICTIONS)) {
                mHandler.sendEmptyMessageDelayed(WRITE_PACKAGE_RESTRICTIONS, WRITE_SETTINGS_DELAY);
            }
        }
    }

    public static PackageManagerService main(Context context, Installer installer,
            @NonNull DomainVerificationService domainVerificationService, boolean factoryTest,
            boolean onlyCore) {
        // Self-check for initial settings.
        PackageManagerServiceCompilerMapping.checkProperties();
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                Trace.TRACE_TAG_PACKAGE_MANAGER);
        t.traceBegin("create package manager");
        final PackageManagerTracedLock lock = new PackageManagerTracedLock();
        final Object installLock = new Object();
        HandlerThread backgroundThread = new HandlerThread("PackageManagerBg");
        backgroundThread.start();
        Handler backgroundHandler = new Handler(backgroundThread.getLooper());

        Injector injector = new Injector(
                context, lock, installer, installLock, new PackageAbiHelperImpl(),
                backgroundHandler,
                SYSTEM_PARTITIONS,
                (i, pm) -> new ComponentResolver(i.getUserManagerService(), pm.mPmInternal, lock),
                (i, pm) -> PermissionManagerService.create(context,
                        i.getSystemConfig().getAvailableFeatures()),
                (i, pm) -> new UserManagerService(context, pm,
                        new UserDataPreparer(installer, installLock, context, onlyCore),
                        lock),
                (i, pm) -> new Settings(Environment.getDataDirectory(),
                        RuntimePermissionsPersistence.createInstance(),
                        i.getPermissionManagerServiceInternal(),
                        domainVerificationService, lock),
                (i, pm) -> AppsFilter.create(pm.mPmInternal, i),
                (i, pm) -> (PlatformCompat) ServiceManager.getService("platform_compat"),
                (i, pm) -> SystemConfig.getInstance(),
                (i, pm) -> new PackageDexOptimizer(i.getInstaller(), i.getInstallLock(),
                        i.getContext(), "*dexopt*"),
                (i, pm) -> new DexManager(i.getContext(), pm, i.getPackageDexOptimizer(),
                        i.getInstaller(), i.getInstallLock()),
                (i, pm) -> new ArtManagerService(i.getContext(), pm, i.getInstaller(),
                        i.getInstallLock()),
                (i, pm) -> ApexManager.getInstance(),
                (i, pm) -> new ViewCompiler(i.getInstallLock(), i.getInstaller()),
                (i, pm) -> (IncrementalManager)
                        i.getContext().getSystemService(Context.INCREMENTAL_SERVICE),
                (i, pm) -> new DefaultAppProvider(() -> context.getSystemService(RoleManager.class),
                        () -> LocalServices.getService(UserManagerInternal.class)),
                (i, pm) -> new DisplayMetrics(),
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, pm.mOnlyCore,
                        i.getDisplayMetrics(), pm.mCacheDir,
                        pm.mPackageParserCallback) /* scanningCachingPackageParserProducer */,
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, pm.mOnlyCore,
                        i.getDisplayMetrics(), null,
                        pm.mPackageParserCallback) /* scanningPackageParserProducer */,
                (i, pm) -> new PackageParser2(pm.mSeparateProcesses, false, i.getDisplayMetrics(),
                        null, pm.mPackageParserCallback) /* preparingPackageParserProducer */,
                // Prepare a supplier of package parser for the staging manager to parse apex file
                // during the staging installation.
                (i, pm) -> new PackageInstallerService(
                        i.getContext(), pm, i::getScanningPackageParser),
                (i, pm, cn) -> new InstantAppResolverConnection(
                        i.getContext(), cn, Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE),
                (i, pm) -> new ModuleInfoProvider(i.getContext(), pm),
                (i, pm) -> LegacyPermissionManagerService.create(i.getContext()),
                (i, pm) -> domainVerificationService,
                (i, pm) -> {
                    HandlerThread thread = new ServiceThread(TAG,
                            Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
                    thread.start();
                    return new PackageHandler(thread.getLooper(), pm);
                },
                new DefaultSystemWrapper(),
                LocalServices::getService,
                context::getSystemService);

        if (Build.VERSION.SDK_INT <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        PackageManagerService m = new PackageManagerService(injector, onlyCore, factoryTest,
                Build.FINGERPRINT, Build.IS_ENG, Build.IS_USERDEBUG, Build.VERSION.SDK_INT,
                Build.VERSION.INCREMENTAL);
        t.traceEnd(); // "create package manager"

        final CompatChange.ChangeListener selinuxChangeListener = packageName -> {
            synchronized (m.mInstallLock) {
                final AndroidPackage pkg;
                final PackageSetting ps;
                final SharedUserSetting sharedUser;
                final String oldSeInfo;
                synchronized (m.mLock) {
                    ps = m.mSettings.getPackageLPr(packageName);
                    if (ps == null) {
                        Slog.e(TAG, "Failed to find package setting " + packageName);
                        return;
                    }
                    pkg = ps.getPkg();
                    sharedUser = ps.getSharedUser();
                    oldSeInfo = AndroidPackageUtils.getSeInfo(pkg, ps);
                }

                if (pkg == null) {
                    Slog.e(TAG, "Failed to find package " + packageName);
                    return;
                }
                final String newSeInfo = SELinuxMMAC.getSeInfo(pkg, sharedUser,
                        m.mInjector.getCompatibility());

                if (!newSeInfo.equals(oldSeInfo)) {
                    Slog.i(TAG, "Updating seInfo for package " + packageName + " from: "
                            + oldSeInfo + " to: " + newSeInfo);
                    ps.getPkgState().setOverrideSeInfo(newSeInfo);
                    m.prepareAppDataAfterInstallLIF(pkg);
                }
            }
        };

        injector.getCompatibility().registerListener(SELinuxMMAC.SELINUX_LATEST_CHANGES,
                selinuxChangeListener);
        injector.getCompatibility().registerListener(SELinuxMMAC.SELINUX_R_CHANGES,
                selinuxChangeListener);

        m.installWhitelistedSystemPackages();
        ServiceManager.addService("package", m);
        final PackageManagerNative pmn = m.new PackageManagerNative();
        ServiceManager.addService("package_native", pmn);
        return m;
    }

    /** Install/uninstall system packages for all users based on their user-type, as applicable. */
    private void installWhitelistedSystemPackages() {
        synchronized (mLock) {
            final boolean scheduleWrite = mUserManager.installWhitelistedSystemPackages(
                    isFirstBoot(), isDeviceUpgrading(), mExistingPackages);
            if (scheduleWrite) {
                scheduleWritePackageRestrictionsLocked(UserHandle.USER_ALL);
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Requests that files preopted on a secondary system partition be copied to the data partition
     * if possible.  Note that the actual copying of the files is accomplished by init for security
     * reasons. This simply requests that the copy takes place and awaits confirmation of its
     * completion. See platform/system/extras/cppreopt/ for the implementation of the actual copy.
     */
    private static void requestCopyPreoptedFiles() {
        final int WAIT_TIME_MS = 100;
        final String CP_PREOPT_PROPERTY = "sys.cppreopt";
        if (SystemProperties.getInt("ro.cp_system_other_odex", 0) == 1) {
            SystemProperties.set(CP_PREOPT_PROPERTY, "requested");
            // We will wait for up to 100 seconds.
            final long timeStart = SystemClock.uptimeMillis();
            final long timeEnd = timeStart + 100 * 1000;
            long timeNow = timeStart;
            while (!SystemProperties.get(CP_PREOPT_PROPERTY).equals("finished")) {
                try {
                    Thread.sleep(WAIT_TIME_MS);
                } catch (InterruptedException e) {
                    // Do nothing
                }
                timeNow = SystemClock.uptimeMillis();
                if (timeNow > timeEnd) {
                    SystemProperties.set(CP_PREOPT_PROPERTY, "timed-out");
                    Slog.wtf(TAG, "cppreopt did not finish!");
                    break;
                }
            }

            Slog.i(TAG, "cppreopts took " + (timeNow - timeStart) + " ms");
        }
    }

    // Link watchables to the class
    private void registerObserver() {
        mPackages.registerObserver(mWatcher);
        mSharedLibraries.registerObserver(mWatcher);
        mStaticLibsByDeclaringPackage.registerObserver(mWatcher);
        mInstrumentation.registerObserver(mWatcher);
        mWebInstantAppsDisabled.registerObserver(mWatcher);
        mAppsFilter.registerObserver(mWatcher);
        mInstantAppRegistry.registerObserver(mWatcher);
        mSettings.registerObserver(mWatcher);
        mIsolatedOwners.registerObserver(mWatcher);
        mComponentResolver.registerObserver(mWatcher);
        // If neither "build" attribute is true then this may be a mockito test, and verification
        // can fail as a false positive.
        Watchable.verifyWatchedAttributes(this, mWatcher, !(mIsEngBuild || mIsUserDebugBuild));
    }

    /**
     * A extremely minimal constructor designed to start up a PackageManagerService instance for
     * testing.
     *
     * It is assumed that all methods under test will mock the internal fields and thus
     * none of the initialization is needed.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public PackageManagerService(@NonNull Injector injector, @NonNull TestParams testParams) {
        mInjector = injector;
        mInjector.bootstrap(this);
        mAppsFilter = injector.getAppsFilter();
        mComponentResolver = injector.getComponentResolver();
        mContext = injector.getContext();
        mInstaller = injector.getInstaller();
        mInstallLock = injector.getInstallLock();
        mLock = injector.getLock();
        mPermissionManager = injector.getPermissionManagerServiceInternal();
        mSettings = injector.getSettings();
        mUserManager = injector.getUserManagerService();
        mDomainVerificationManager = injector.getDomainVerificationManagerInternal();
        mHandler = injector.getHandler();

        mApexManager = testParams.apexManager;
        mArtManagerService = testParams.artManagerService;
        mAvailableFeatures = testParams.availableFeatures;
        mDefParseFlags = testParams.defParseFlags;
        mDefaultAppProvider = testParams.defaultAppProvider;
        mLegacyPermissionManager = testParams.legacyPermissionManagerInternal;
        mDexManager = testParams.dexManager;
        mDirsToScanAsSystem = testParams.dirsToScanAsSystem;
        mFactoryTest = testParams.factoryTest;
        mIncrementalManager = testParams.incrementalManager;
        mInstallerService = testParams.installerService;
        mInstantAppRegistry = testParams.instantAppRegistry;
        mInstantAppResolverConnection = testParams.instantAppResolverConnection;
        mInstantAppResolverSettingsComponent = testParams.instantAppResolverSettingsComponent;
        mIsPreNMR1Upgrade = testParams.isPreNmr1Upgrade;
        mIsPreNUpgrade = testParams.isPreNupgrade;
        mIsPreQUpgrade = testParams.isPreQupgrade;
        mIsUpgrade = testParams.isUpgrade;
        mMetrics = testParams.Metrics;
        mModuleInfoProvider = testParams.moduleInfoProvider;
        mMoveCallbacks = testParams.moveCallbacks;
        mOnlyCore = testParams.onlyCore;
        mOverlayConfig = testParams.overlayConfig;
        mPackageDexOptimizer = testParams.packageDexOptimizer;
        mPackageParserCallback = testParams.packageParserCallback;
        mPendingBroadcasts = testParams.pendingPackageBroadcasts;
        mPmInternal = testParams.pmInternal;
        mTestUtilityService = testParams.testUtilityService;
        mProcessLoggingHandler = testParams.processLoggingHandler;
        mProtectedPackages = testParams.protectedPackages;
        mSeparateProcesses = testParams.separateProcesses;
        mViewCompiler = testParams.viewCompiler;
        mRequiredVerifierPackage = testParams.requiredVerifierPackage;
        mRequiredInstallerPackage = testParams.requiredInstallerPackage;
        mRequiredUninstallerPackage = testParams.requiredUninstallerPackage;
        mRequiredPermissionControllerPackage = testParams.requiredPermissionControllerPackage;
        mSetupWizardPackage = testParams.setupWizardPackage;
        mStorageManagerPackage = testParams.storageManagerPackage;
        mDefaultTextClassifierPackage = testParams.defaultTextClassifierPackage;
        mSystemTextClassifierPackageName = testParams.systemTextClassifierPackage;
        mRetailDemoPackage = testParams.retailDemoPackage;
        mRecentsPackage = testParams.recentsPackage;
        mConfiguratorPackage = testParams.configuratorPackage;
        mAppPredictionServicePackage = testParams.appPredictionServicePackage;
        mIncidentReportApproverPackage = testParams.incidentReportApproverPackage;
        mServicesExtensionPackageName = testParams.servicesExtensionPackageName;
        mSharedSystemSharedLibraryPackageName = testParams.sharedSystemSharedLibraryPackageName;
        mOverlayConfigSignaturePackage = testParams.overlayConfigSignaturePackage;
        mResolveComponentName = testParams.resolveComponentName;

        // Disable snapshots in this instance of PackageManagerService, which is only used
        // for testing.  The instance still needs a live computer.  The snapshot computer
        // is set to null since it must never be used by this instance.
        mSnapshotEnabled = false;
        mLiveComputer = createLiveComputer();
        mSnapshotComputer = null;
        mSnapshotStatistics = null;

        mPackages.putAll(testParams.packages);
        mEnableFreeCacheV2 = testParams.enableFreeCacheV2;
        mSdkVersion = testParams.sdkVersion;
        mAppInstallDir = testParams.appInstallDir;
        mAppLib32InstallDir = testParams.appLib32InstallDir;
        mIsEngBuild = testParams.isEngBuild;
        mIsUserDebugBuild = testParams.isUserDebugBuild;
        mIncrementalVersion = testParams.incrementalVersion;
        mDomainVerificationConnection = new DomainVerificationConnection(this);

        invalidatePackageInfoCache();
    }

    public PackageManagerService(Injector injector, boolean onlyCore, boolean factoryTest,
            final String buildFingerprint, final boolean isEngBuild,
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
        mInstallLock = injector.getInstallLock();
        LockGuard.installLock(mLock, LockGuard.INDEX_PACKAGES);
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        mContext = injector.getContext();
        mFactoryTest = factoryTest;
        mOnlyCore = onlyCore;
        mMetrics = injector.getDisplayMetrics();
        mInstaller = injector.getInstaller();
        mEnableFreeCacheV2 = SystemProperties.getBoolean("fw.free_cache_v2", true);

        // Create sub-components that provide services / data. Order here is important.
        t.traceBegin("createSubComponents");

        // Expose private service for system components to use.
        mPmInternal = new PackageManagerInternalImpl();
        LocalServices.addService(TestUtilityService.class, this);
        mTestUtilityService = LocalServices.getService(TestUtilityService.class);
        LocalServices.addService(PackageManagerInternal.class, mPmInternal);
        mUserManager = injector.getUserManagerService();
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
        mArtManagerService = injector.getArtManagerService();
        mMoveCallbacks = new MovePackageHelper.MoveCallbacks(FgThread.get().getLooper());
        mViewCompiler = injector.getViewCompiler();

        mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getMetrics(mMetrics);

        t.traceBegin("get system config");
        SystemConfig systemConfig = injector.getSystemConfig();
        mAvailableFeatures = systemConfig.getAvailableFeatures();
        t.traceEnd();

        mProtectedPackages = new ProtectedPackages(mContext);

        mApexManager = injector.getApexManager();
        mAppsFilter = mInjector.getAppsFilter();

        final List<ScanPartition> scanPartitions = new ArrayList<>();
        final List<ApexManager.ActiveApexInfo> activeApexInfos = mApexManager.getActiveApexInfos();
        for (int i = 0; i < activeApexInfos.size(); i++) {
            final ScanPartition scanPartition = resolveApexToScanPartition(activeApexInfos.get(i));
            if (scanPartition != null) {
                scanPartitions.add(scanPartition);
            }
        }

        mInstantAppRegistry = new InstantAppRegistry(this, mPermissionManager, mPmInternal);

        mDirsToScanAsSystem = new ArrayList<>();
        mDirsToScanAsSystem.addAll(injector.getSystemPartitions());
        mDirsToScanAsSystem.addAll(scanPartitions);
        Slog.d(TAG, "Directories scanned as system partitions: " + mDirsToScanAsSystem);

        mAppInstallDir = new File(Environment.getDataDirectory(), "app");
        mAppLib32InstallDir = getAppLib32InstallDir();

        mDomainVerificationConnection = new DomainVerificationConnection(this);
        mDomainVerificationManager = injector.getDomainVerificationManagerInternal();
        mDomainVerificationManager.setConnection(mDomainVerificationConnection);

        synchronized (mLock) {
            // Create the computer as soon as the state objects have been installed.  The
            // cached computer is the same as the live computer until the end of the
            // constructor, at which time the invalidation method updates it.  The cache is
            // corked initially to ensure a cached computer is not built until the end of the
            // constructor.
            mSnapshotStatistics = new SnapshotStatistics();
            sSnapshotCorked.set(1);
            sSnapshotInvalid.set(true);
            mLiveComputer = createLiveComputer();
            mSnapshotComputer = null;
            mSnapshotEnabled = SNAPSHOT_ENABLED;
            registerObserver();
        }

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
                addBuiltInSharedLibraryLocked(libConfig.valueAt(i));
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
                        getSharedLibraryInfoLPr(entry.dependencies[j], undefinedVersion);
                    if (dependency != null) {
                        getSharedLibraryInfoLPr(name, undefinedVersion).addDependency(dependency);
                    }
                }
            }

            SELinuxMMAC.readInstallPolicy();

            t.traceBegin("loadFallbacks");
            FallbackCategoryProvider.loadFallbacks();
            t.traceEnd();

            t.traceBegin("read user settings");
            mFirstBoot = !mSettings.readLPw(mInjector.getUserManagerInternal().getUsers(
                    /* excludePartial= */ true,
                    /* excludeDying= */ false,
                    /* excludePreCreated= */ false));
            t.traceEnd();

            mPermissionManager.readLegacyPermissionsTEMP(mSettings.mPermissions);
            mPermissionManager.readLegacyPermissionStateTEMP();

            if (!mOnlyCore && mFirstBoot) {
                requestCopyPreoptedFiles();
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
                    !buildFingerprint.equals(ver.fingerprint);
            if (mIsUpgrade) {
                PackageManagerServiceUtils.logCriticalInfo(Log.INFO,
                        "Upgrading from " + ver.fingerprint + " to " + Build.FINGERPRINT);
            }

            // when upgrading from pre-M, promote system app permissions from install to runtime
            mPromoteSystemApps =
                    mIsUpgrade && ver.sdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1;

            // When upgrading from pre-N, we need to handle package extraction like first boot,
            // as there is no profiling data available.
            mIsPreNUpgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.N;

            mIsPreNMR1Upgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.N_MR1;
            mIsPreQUpgrade = mIsUpgrade && ver.sdkVersion < Build.VERSION_CODES.Q;

            final WatchedArrayMap<String, PackageSetting> packageSettings =
                mSettings.getPackagesLocked();

            // Save the names of pre-existing packages prior to scanning, so we can determine
            // which system packages are completely new due to an upgrade.
            if (isDeviceUpgrading()) {
                mExistingPackages = new ArraySet<>(packageSettings.size());
                for (PackageSetting ps : packageSettings.values()) {
                    mExistingPackages.add(ps.getPackageName());
                }
            }

            mCacheDir = preparePackageParserCache(mIsEngBuild);

            // Set flag to monitor and not change apk file paths when
            // scanning install directories.
            int scanFlags = SCAN_BOOTING | SCAN_INITIAL;

            if (mIsUpgrade || mFirstBoot) {
                scanFlags = scanFlags | SCAN_FIRST_BOOT_OR_UPGRADE;
            }

            final int systemParseFlags = mDefParseFlags | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
            final int systemScanFlags = scanFlags | SCAN_AS_SYSTEM;

            PackageParser2 packageParser = injector.getScanningCachingPackageParser();

            ExecutorService executorService = ParallelPackageParser.makeExecutorService();
            // Prepare apex package info before scanning APKs, these information are needed when
            // scanning apk in apex.
            mApexManager.scanApexPackagesTraced(packageParser, executorService);

            final InitAndSystemPackageHelper helper = new InitAndSystemPackageHelper(this);
            helper.scanSystemDirs(mDirsToScanAsSystem, mIsUpgrade,
                    packageParser, executorService, mPlatformPackage, mIsPreNMR1Upgrade,
                    systemParseFlags, systemScanFlags);
            // Parse overlay configuration files to set default enable state, mutability, and
            // priority of system overlays.
            mOverlayConfig = OverlayConfig.initializeSystemInstance(
                    consumer -> mPmInternal.forEachPackage(
                            pkg -> consumer.accept(pkg, pkg.isSystem())));
            final int[] userIds = mUserManager.getUserIds();
            helper.cleanupSystemPackagesAndInstallStubs(mDirsToScanAsSystem,
                    mIsUpgrade, packageParser, executorService, mOnlyCore, packageSettings,
                    startTime, mAppInstallDir, mPlatformPackage, mIsPreNMR1Upgrade,
                    scanFlags, systemParseFlags, systemScanFlags, userIds);
            packageParser.close();

            // Resolve the storage manager.
            mStorageManagerPackage = getStorageManagerPackageName();

            // Resolve protected action filters. Only the setup wizard is allowed to
            // have a high priority filter for these actions.
            mSetupWizardPackage = getSetupWizardPackageNameImpl();
            mComponentResolver.fixProtectedFilterPriorities();

            mDefaultTextClassifierPackage = getDefaultTextClassifierPackageName();
            mSystemTextClassifierPackageName = getSystemTextClassifierPackageName();
            mConfiguratorPackage = getDeviceConfiguratorPackageName();
            mAppPredictionServicePackage = getAppPredictionServicePackageName();
            mIncidentReportApproverPackage = getIncidentReportApproverPackageName();
            mRetailDemoPackage = getRetailDemoPackageName();
            mOverlayConfigSignaturePackage = getOverlayConfigSignaturePackageName();
            mRecentsPackage = getRecentsPackageName();

            // Now that we know all of the shared libraries, update all clients to have
            // the correct library paths.
            updateAllSharedLibrariesLocked(null, null, Collections.unmodifiableMap(mPackages));

            for (SharedUserSetting setting : mSettings.getAllSharedUsersLPw()) {
                // NOTE: We ignore potential failures here during a system scan (like
                // the rest of the commands above) because there's precious little we
                // can do about it. A settings error is reported, though.
                final List<String> changedAbiCodePath =
                        ScanPackageHelper.applyAdjustedAbiToSharedUser(
                                setting, null /*scannedPackage*/,
                                mInjector.getAbiHelper().getAdjustedAbiForSharedUser(
                                setting.packages, null /*scannedPackage*/));
                if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
                    for (int i = changedAbiCodePath.size() - 1; i >= 0; --i) {
                        final String codePathString = changedAbiCodePath.get(i);
                        try {
                            mInstaller.rmdex(codePathString,
                                    getDexCodeInstructionSet(getPreferredInstructionSet()));
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
                    + ((SystemClock.uptimeMillis()-startTime)/1000f)
                    + " seconds");

            // If the build fingerprint has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear.  This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow...  it would be nice to have some better way to handle
            // this situation.
            if (mIsUpgrade) {
                Slog.i(TAG, "Build fingerprint changed from " + ver.fingerprint + " to "
                        + Build.FINGERPRINT + "; regranting permissions for internal storage");
            }
            mPermissionManager.onStorageVolumeMounted(
                    StorageManager.UUID_PRIVATE_INTERNAL, mIsUpgrade);
            ver.sdkVersion = mSdkVersion;

            // If this is the first boot or an update from pre-M, and it is a normal
            // boot, then we need to initialize the default preferred apps across
            // all defined users.
            if (!mOnlyCore && (mPromoteSystemApps || mFirstBoot)) {
                for (UserInfo user : mInjector.getUserManagerInternal().getUsers(true)) {
                    mSettings.applyDefaultPreferredAppsLPw(user.id);
                }
            }

            // Prepare storage for system user really early during boot,
            // since core system apps like SettingsProvider and SystemUI
            // can't wait for user to start
            final int storageFlags;
            if (StorageManager.isFileEncryptedNativeOrEmulated()) {
                storageFlags = StorageManager.FLAG_STORAGE_DE;
            } else {
                storageFlags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
            }
            List<String> deferPackages = reconcileAppsDataLI(StorageManager.UUID_PRIVATE_INTERNAL,
                    UserHandle.USER_SYSTEM, storageFlags, true /* migrateAppData */,
                    true /* onlyCoreApps */);
            mPrepareAppDataFuture = SystemServerInitThreadPool.submit(() -> {
                TimingsTraceLog traceLog = new TimingsTraceLog("SystemServerTimingAsync",
                        Trace.TRACE_TAG_PACKAGE_MANAGER);
                traceLog.traceBegin("AppDataFixup");
                try {
                    mInstaller.fixupAppData(StorageManager.UUID_PRIVATE_INTERNAL,
                            StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
                } catch (InstallerException e) {
                    Slog.w(TAG, "Trouble fixing GIDs", e);
                }
                traceLog.traceEnd();

                traceLog.traceBegin("AppDataPrepare");
                if (deferPackages == null || deferPackages.isEmpty()) {
                    return;
                }
                int count = 0;
                final Installer.Batch batch = new Installer.Batch();
                for (String pkgName : deferPackages) {
                    AndroidPackage pkg = null;
                    synchronized (mLock) {
                        PackageSetting ps = mSettings.getPackageLPr(pkgName);
                        if (ps != null && ps.getInstalled(UserHandle.USER_SYSTEM)) {
                            pkg = ps.getPkg();
                        }
                    }
                    if (pkg != null) {
                        prepareAppDataAndMigrate(batch, pkg, UserHandle.USER_SYSTEM, storageFlags,
                                true /* maybeMigrateAppData */);
                        count++;
                    }
                }
                synchronized (mInstallLock) {
                    executeBatchLI(batch);
                }
                traceLog.traceEnd();
                Slog.i(TAG, "Deferred reconcileAppsData finished " + count + " packages");
            }, "prepareAppData");

            // If this is first boot after an OTA, and a normal boot, then
            // we need to clear code cache directories.
            // Note that we do *not* clear the application profiles. These remain valid
            // across OTAs and are used to drive profile verification (post OTA) and
            // profile compilation (without waiting to collect a fresh set of profiles).
            if (mIsUpgrade && !mOnlyCore) {
                Slog.i(TAG, "Build fingerprint changed; clearing code caches");
                for (int i = 0; i < packageSettings.size(); i++) {
                    final PackageSetting ps = packageSettings.valueAt(i);
                    if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, ps.getVolumeUuid())) {
                        // No apps are running this early, so no need to freeze
                        clearAppDataLIF(ps.getPkg(), UserHandle.USER_ALL,
                                FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                                        | Installer.FLAG_CLEAR_CODE_CACHE_ONLY
                                        | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES);
                    }
                }
                ver.fingerprint = Build.FINGERPRINT;
            }

            // Legacy existing (installed before Q) non-system apps to hide
            // their icons in launcher.
            if (!mOnlyCore && mIsPreQUpgrade) {
                Slog.i(TAG, "Whitelisting all existing apps to hide their icons");
                int size = packageSettings.size();
                for (int i = 0; i < size; i++) {
                    final PackageSetting ps = packageSettings.valueAt(i);
                    if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0) {
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

            if (!mOnlyCore) {
                mRequiredVerifierPackage = getRequiredButNotReallyRequiredVerifierLPr();
                mRequiredInstallerPackage = getRequiredInstallerLPr();
                mRequiredUninstallerPackage = getRequiredUninstallerLPr();
                ComponentName intentFilterVerifierComponent =
                        getIntentFilterVerifierComponentNameLPr();
                ComponentName domainVerificationAgent =
                        getDomainVerificationAgentComponentNameLPr();

                DomainVerificationProxy domainVerificationProxy = DomainVerificationProxy.makeProxy(
                        intentFilterVerifierComponent, domainVerificationAgent, mContext,
                        mDomainVerificationManager, mDomainVerificationManager.getCollector(),
                        mDomainVerificationConnection);

                mDomainVerificationManager.setProxy(domainVerificationProxy);

                mServicesExtensionPackageName = getRequiredServicesExtensionPackageLPr();
                mSharedSystemSharedLibraryPackageName = getRequiredSharedLibraryLPr(
                        PackageManager.SYSTEM_SHARED_LIBRARY_SHARED,
                        SharedLibraryInfo.VERSION_UNDEFINED);
            } else {
                mRequiredVerifierPackage = null;
                mRequiredInstallerPackage = null;
                mRequiredUninstallerPackage = null;
                mServicesExtensionPackageName = null;
                mSharedSystemSharedLibraryPackageName = null;
            }

            // PermissionController hosts default permission granting and role management, so it's a
            // critical part of the core system.
            mRequiredPermissionControllerPackage = getRequiredPermissionControllerLPr();

            mSettings.setPermissionControllerVersion(
                    getPackageInfo(mRequiredPermissionControllerPackage, 0,
                            UserHandle.USER_SYSTEM).getLongVersionCode());

            // Initialize InstantAppRegistry's Instant App list for all users.
            for (AndroidPackage pkg : mPackages.values()) {
                if (pkg.isSystem()) {
                    continue;
                }
                for (int userId : userIds) {
                    final PackageSetting ps = getPackageSetting(pkg.getPackageName());
                    if (ps == null || !ps.getInstantApp(userId) || !ps.getInstalled(userId)) {
                        continue;
                    }
                    mInstantAppRegistry.addInstantAppLPw(userId, ps.getAppId());
                }
            }

            mInstallerService = mInjector.getPackageInstallerService();
            final ComponentName instantAppResolverComponent = getInstantAppResolverLPr();
            if (instantAppResolverComponent != null) {
                if (DEBUG_INSTANT) {
                    Slog.d(TAG, "Set ephemeral resolver: " + instantAppResolverComponent);
                }
                mInstantAppResolverConnection =
                        mInjector.getInstantAppResolverConnection(instantAppResolverComponent);
                mInstantAppResolverSettingsComponent =
                        getInstantAppResolverSettingsLPr(instantAppResolverComponent);
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
                userPackages.put(userId, getInstalledPackages(/*flags*/ 0, userId).getList());
            }
            mDexManager.load(userPackages);
            if (mIsUpgrade) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.BOOT_TIME_EVENT_DURATION_REPORTED,
                        BOOT_TIME_EVENT_DURATION__EVENT__OTA_PACKAGE_MANAGER_INIT_TIME,
                        SystemClock.uptimeMillis() - startTime);
            }

            // Rebild the live computer since some attributes have been rebuilt.
            mLiveComputer = createLiveComputer();

        } // synchronized (mLock)
        } // synchronized (mInstallLock)
        // CHECKSTYLE:ON IndentationCheck

        mModuleInfoProvider = mInjector.getModuleInfoProvider();
        mInjector.getSystemWrapper().enablePackageCaches();

        // Now after opening every single application zip, make sure they
        // are all flushed.  Not really needed, but keeps things nice and
        // tidy.
        t.traceBegin("GC");
        VMRuntime.getRuntime().requestConcurrentGC();
        t.traceEnd();

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
        // we're only interested in updating the installer appliction when 1) it's not
        // already set or 2) the modified package is the installer
        if (mInstantAppInstallerActivity != null
                && !mInstantAppInstallerActivity.getComponentName().getPackageName()
                        .equals(modifiedPackage)) {
            return;
        }
        setUpInstantAppInstallerActivityLP(getInstantAppInstallerLPr());
    }

    private @Nullable File preparePackageParserCache(boolean forEngBuild) {
        if (!FORCE_PACKAGE_PARSED_CACHE_ENABLED) {
            if (!DEFAULT_PACKAGE_PARSER_CACHE_ENABLED) {
                return null;
            }

            // Disable package parsing on eng builds to allow for faster incremental development.
            if (forEngBuild) {
                return null;
            }

            if (SystemProperties.getBoolean("pm.boot.disable_package_cache", false)) {
                Slog.i(TAG, "Disabling package parser cache due to system property.");
                return null;
            }
        }

        // The base directory for the package parser cache lives under /data/system/.
        final File cacheBaseDir = Environment.getPackageCacheDirectory();
        if (!FileUtils.createDir(cacheBaseDir)) {
            return null;
        }

        // There are several items that need to be combined together to safely
        // identify cached items. In particular, changing the value of certain
        // feature flags should cause us to invalidate any caches.
        final String cacheName = FORCE_PACKAGE_PARSED_CACHE_ENABLED ? "debug"
                : SystemProperties.digestOf("ro.build.fingerprint");

        // Reconcile cache directories, keeping only what we'd actually use.
        for (File cacheDir : FileUtils.listFilesOrEmpty(cacheBaseDir)) {
            if (Objects.equals(cacheName, cacheDir.getName())) {
                Slog.d(TAG, "Keeping known cache " + cacheDir.getName());
            } else {
                Slog.d(TAG, "Destroying unknown cache " + cacheDir.getName());
                FileUtils.deleteContentsAndDir(cacheDir);
            }
        }

        // Return the versioned package cache directory.
        File cacheDir = FileUtils.createDir(cacheBaseDir, cacheName);

        if (cacheDir == null) {
            // Something went wrong. Attempt to delete everything and return.
            Slog.wtf(TAG, "Cache directory cannot be created - wiping base dir " + cacheBaseDir);
            FileUtils.deleteContentsAndDir(cacheBaseDir);
            return null;
        }

        // The following is a workaround to aid development on non-numbered userdebug
        // builds or cases where "adb sync" is used on userdebug builds. If we detect that
        // the system partition is newer.
        //
        // NOTE: When no BUILD_NUMBER is set by the build system, it defaults to a build
        // that starts with "eng." to signify that this is an engineering build and not
        // destined for release.
        if (mIsUserDebugBuild && mIncrementalVersion.startsWith("eng.")) {
            Slog.w(TAG, "Wiping cache directory because the system partition changed.");

            // Heuristic: If the /system directory has been modified recently due to an "adb sync"
            // or a regular make, then blow away the cache. Note that mtimes are *NOT* reliable
            // in general and should not be used for production changes. In this specific case,
            // we know that they will work.
            File frameworkDir =
                    new File(Environment.getRootDirectory(), "framework");
            if (cacheDir.lastModified() < frameworkDir.lastModified()) {
                FileUtils.deleteContents(cacheBaseDir);
                cacheDir = FileUtils.createDir(cacheBaseDir, cacheName);
            }
        }

        return cacheDir;
    }

    @Override
    public boolean isFirstBoot() {
        // allow instant applications
        return mFirstBoot;
    }

    @Override
    public boolean isOnlyCoreApps() {
        // allow instant applications
        return mOnlyCore;
    }

    @Override
    public boolean isDeviceUpgrading() {
        // allow instant applications
        // The system property allows testing ota flow when upgraded to the same image.
        return mIsUpgrade || SystemProperties.getBoolean(
                "persist.pm.mock-upgrade", false /* default */);
    }

    private @Nullable String getRequiredButNotReallyRequiredVerifierLPr() {
        final Intent intent = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);

        final List<ResolveInfo> matches = queryIntentReceiversInternal(intent, PACKAGE_MIME_TYPE,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM, Binder.getCallingUid());
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().packageName;
        } else if (matches.size() == 0) {
            Log.w(TAG, "There should probably be a verifier, but, none were found");
            return null;
        }
        throw new RuntimeException("There must be exactly one verifier; found " + matches);
    }

    private @NonNull String getRequiredSharedLibraryLPr(String name, int version) {
        synchronized (mLock) {
            SharedLibraryInfo libraryInfo = getSharedLibraryInfoLPr(name, version);
            if (libraryInfo == null) {
                throw new IllegalStateException("Missing required shared library:" + name);
            }
            String packageName = libraryInfo.getPackageName();
            if (packageName == null) {
                throw new IllegalStateException("Expected a package for shared library " + name);
            }
            return packageName;
        }
    }

    @NonNull
    private String getRequiredServicesExtensionPackageLPr() {
        String servicesExtensionPackage =
                ensureSystemPackageName(
                        mContext.getString(R.string.config_servicesExtensionPackage));
        if (TextUtils.isEmpty(servicesExtensionPackage)) {
            throw new RuntimeException(
                    "Required services extension package is missing, check "
                            + "config_servicesExtensionPackage.");
        }
        return servicesExtensionPackage;
    }

    private @NonNull String getRequiredInstallerLPr() {
        final Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.parse("content://com.example/foo.apk"), PACKAGE_MIME_TYPE);

        final List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, PACKAGE_MIME_TYPE,
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

    private @NonNull String getRequiredUninstallerLPr() {
        final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setData(Uri.fromParts(PACKAGE_SCHEME, "foo.bar", null));

        final ResolveInfo resolveInfo = resolveIntent(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM);
        if (resolveInfo == null ||
                mResolveActivity.name.equals(resolveInfo.getComponentInfo().name)) {
            throw new RuntimeException("There must be exactly one uninstaller; found "
                    + resolveInfo);
        }
        return resolveInfo.getComponentInfo().packageName;
    }

    private @NonNull String getRequiredPermissionControllerLPr() {
        final Intent intent = new Intent(Intent.ACTION_MANAGE_PERMISSIONS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);

        final List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null,
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

    private @NonNull ComponentName getIntentFilterVerifierComponentNameLPr() {
        final Intent intent = new Intent(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION);

        final List<ResolveInfo> matches = queryIntentReceiversInternal(intent, PACKAGE_MIME_TYPE,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM, Binder.getCallingUid());
        ResolveInfo best = null;
        final int N = matches.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo cur = matches.get(i);
            final String packageName = cur.getComponentInfo().packageName;
            if (checkPermission(android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT,
                    packageName, UserHandle.USER_SYSTEM) != PackageManager.PERMISSION_GRANTED) {
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
    private ComponentName getDomainVerificationAgentComponentNameLPr() {
        Intent intent = new Intent(Intent.ACTION_DOMAINS_NEED_VERIFICATION);
        List<ResolveInfo> matches = queryIntentReceiversInternal(intent, null,
                MATCH_SYSTEM_ONLY | MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE,
                UserHandle.USER_SYSTEM, Binder.getCallingUid());
        ResolveInfo best = null;
        final int N = matches.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo cur = matches.get(i);
            final String packageName = cur.getComponentInfo().packageName;
            if (checkPermission(android.Manifest.permission.DOMAIN_VERIFICATION_AGENT,
                    packageName, UserHandle.USER_SYSTEM) != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Domain verification agent found but does not hold permission: "
                        + packageName);
                continue;
            }

            if (best == null || cur.priority > best.priority) {
                if (isComponentEffectivelyEnabled(cur.getComponentInfo(), UserHandle.USER_SYSTEM)) {
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

    @Override
    public @Nullable ComponentName getInstantAppResolverComponent() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        synchronized (mLock) {
            return getInstantAppResolverLPr();
        }
    }

    private @Nullable ComponentName getInstantAppResolverLPr() {
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
        List<ResolveInfo> resolvers = queryIntentServicesInternal(resolverIntent, null,
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
        final Intent intent = new Intent();
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setDataAndType(Uri.fromFile(new File("foo.apk")), PACKAGE_MIME_TYPE);
        List<ResolveInfo> matches = null;
        for (String action : orderedActions) {
            intent.setAction(action);
            matches = queryIntentActivitiesInternal(intent, PACKAGE_MIME_TYPE,
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

    private @Nullable ComponentName getInstantAppResolverSettingsLPr(
            @NonNull ComponentName resolver) {
        final Intent intent =  new Intent(Intent.ACTION_INSTANT_APP_RESOLVER_SETTINGS)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(resolver.getPackageName());
        final int resolveFlags = MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null, resolveFlags,
                UserHandle.USER_SYSTEM);
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(0).getComponentInfo().getComponentName();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            if (!(e instanceof SecurityException) && !(e instanceof IllegalArgumentException)) {
                Slog.wtf(TAG, "Package Manager Crash", e);
            }
            throw e;
        }
    }

    /**
     * Returns whether or not a full application can see an instant application.
     * <p>
     * Currently, there are four cases in which this can occur:
     * <ol>
     * <li>The calling application is a "special" process. Special processes
     *     are those with a UID < {@link Process#FIRST_APPLICATION_UID}.</li>
     * <li>The calling application has the permission
     *     {@link android.Manifest.permission#ACCESS_INSTANT_APPS}.</li>
     * <li>The calling application is the default launcher on the
     *     system partition.</li>
     * <li>The calling application is the default app prediction service.</li>
     * </ol>
     */
    private boolean canViewInstantApps(int callingUid, int userId) {
        return mComputer.canViewInstantApps(callingUid, userId);
    }

    private PackageInfo generatePackageInfo(PackageSetting ps, int flags, int userId) {
        return mComputer.generatePackageInfo(ps, flags, userId);
    }

    @Override
    public void checkPackageStartable(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        if (!mUserManager.exists(userId)) {
            throw new SecurityException("User doesn't exist");
        }
        enforceCrossUserPermission(callingUid, userId, false, false, "checkPackageStartable");
        switch (getPackageStartability(packageName, callingUid, userId)) {
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
                return;
        }
    }

    private @PackageStartability int getPackageStartability(String packageName,
            int callingUid, int userId) {
        final boolean userKeyUnlocked = StorageManager.isUserKeyUnlocked(userId);
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null || shouldFilterApplicationLocked(ps, callingUid, userId)
                    || !ps.getInstalled(userId)) {
                return PACKAGE_STARTABILITY_NOT_FOUND;
            }

            if (mSafeMode && !ps.isSystem()) {
                return PACKAGE_STARTABILITY_NOT_SYSTEM;
            }

            if (mFrozenPackages.contains(packageName)) {
                return PACKAGE_STARTABILITY_FROZEN;
            }

            if (!userKeyUnlocked && !AndroidPackageUtils.isEncryptionAware(ps.getPkg())) {
                return PACKAGE_STARTABILITY_DIRECT_BOOT_UNSUPPORTED;
            }
        }
        return PACKAGE_STARTABILITY_OK;
    }

    @Override
    public boolean isPackageAvailable(String packageName, int userId) {
        if (!mUserManager.exists(userId)) return false;
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "is package available");
        synchronized (mLock) {
            AndroidPackage p = mPackages.get(packageName);
            if (p != null) {
                final PackageSetting ps = getPackageSetting(p.getPackageName());
                if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    return false;
                }
                if (ps != null) {
                    final PackageUserState state = ps.readUserState(userId);
                    if (state != null) {
                        return checkUseInstalledOrHidden(p, ps, state, 0 /*flags*/);
                    }
                }
            }
        }
        return false;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        return mComputer.getPackageInfo(packageName, flags, userId);
    }

    @Override
    public PackageInfo getPackageInfoVersioned(VersionedPackage versionedPackage,
            int flags, int userId) {
        return getPackageInfoInternal(versionedPackage.getPackageName(),
                versionedPackage.getLongVersionCode(), flags, Binder.getCallingUid(), userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out packages
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    private PackageInfo getPackageInfoInternal(String packageName, long versionCode,
            int flags, int filterCallingUid, int userId) {
        return mComputer.getPackageInfoInternal(packageName, versionCode,
                flags, filterCallingUid, userId);
    }

    /**
     * Returns whether or not access to the application should be filtered.
     * <p>
     * Access may be limited based upon whether the calling or target applications
     * are instant applications.
     *
     * @see #canViewInstantApps(int, int)
     */
    @GuardedBy("mLock")
    private boolean shouldFilterApplicationLocked(@Nullable PackageSetting ps, int callingUid,
            @Nullable ComponentName component, @ComponentType int componentType, int userId) {
        return mComputer.shouldFilterApplicationLocked(ps, callingUid,
                component, componentType, userId);
    }

    /**
     * @see #shouldFilterApplicationLocked(PackageSetting, int, ComponentName, int, int)
     */
    @GuardedBy("mLock")
    boolean shouldFilterApplicationLocked(
            @Nullable PackageSetting ps, int callingUid, int userId) {
        return mComputer.shouldFilterApplicationLocked(
            ps, callingUid, userId);
    }

    /**
     * @see #shouldFilterApplicationLocked(PackageSetting, int, ComponentName, int, int)
     */
    @GuardedBy("mLock")
    private boolean shouldFilterApplicationLocked(@NonNull SharedUserSetting sus, int callingUid,
            int userId) {
        return mComputer.shouldFilterApplicationLocked(sus, callingUid, userId);
    }

    @GuardedBy("mLock")
    private boolean filterSharedLibPackageLPr(@Nullable PackageSetting ps, int uid, int userId,
            int flags) {
        return mComputer.filterSharedLibPackageLPr(ps, uid, userId,
                flags);
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return names;
        }
        final String[] out = new String[names.length];
        // reader
        synchronized (mLock) {
            final int callingUserId = UserHandle.getUserId(callingUid);
            final boolean canViewInstantApps = canViewInstantApps(callingUid, callingUserId);
            for (int i=names.length-1; i>=0; i--) {
                final PackageSetting ps = mSettings.getPackageLPr(names[i]);
                boolean translateName = false;
                if (ps != null && ps.getRealName() != null) {
                    final boolean targetIsInstantApp = ps.getInstantApp(callingUserId);
                    translateName = !targetIsInstantApp
                            || canViewInstantApps
                            || mInstantAppRegistry.isInstantAccessGranted(callingUserId,
                                    UserHandle.getAppId(callingUid), ps.getAppId());
                }
                out[i] = translateName ? ps.getRealName() : names[i];
            }
        }
        return out;
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return names;
        }
        final String[] out = new String[names.length];
        // reader
        synchronized (mLock) {
            final int callingUserId = UserHandle.getUserId(callingUid);
            final boolean canViewInstantApps = canViewInstantApps(callingUid, callingUserId);
            for (int i=names.length-1; i>=0; i--) {
                final String cur = mSettings.getRenamedPackageLPr(names[i]);
                boolean translateName = false;
                if (cur != null) {
                    final PackageSetting ps = mSettings.getPackageLPr(names[i]);
                    final boolean targetIsInstantApp =
                            ps != null && ps.getInstantApp(callingUserId);
                    translateName = !targetIsInstantApp
                            || canViewInstantApps
                            || mInstantAppRegistry.isInstantAccessGranted(callingUserId,
                                    UserHandle.getAppId(callingUid), ps.getAppId());
                }
                out[i] = translateName ? cur : names[i];
            }
        }
        return out;
    }

    @Override
    public int getPackageUid(String packageName, int flags, int userId) {
        if (!mUserManager.exists(userId)) return -1;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "getPackageUid");
        return getPackageUidInternal(packageName, flags, userId, callingUid);
    }

    private int getPackageUidInternal(String packageName, int flags, int userId, int callingUid) {
        return mComputer.getPackageUidInternal(packageName, flags, userId, callingUid);
    }

    @Override
    public int[] getPackageGids(String packageName, int flags, int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "getPackageGids");

        // reader
        synchronized (mLock) {
            final AndroidPackage p = mPackages.get(packageName);
            if (p != null && AndroidPackageUtils.isMatchForSystemOnly(p, flags)) {
                final PackageSetting ps = getPackageSetting(p.getPackageName());
                if (ps != null && ps.getInstalled(userId)
                        && !shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    return mPermissionManager.getGidsForUid(UserHandle.getUid(userId,
                            ps.getAppId()));
                }
            }
            if ((flags & MATCH_KNOWN_PACKAGES) != 0) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                if (ps != null && ps.isMatch(flags)
                        && !shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    return mPermissionManager.getGidsForUid(
                            UserHandle.getUid(userId, ps.getAppId()));
                }
            }
        }

        return null;
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class)
                .getPermissionGroupInfo(groupName, flags);
    }

    @GuardedBy("mLock")
    private ApplicationInfo generateApplicationInfoFromSettingsLPw(String packageName, int flags,
            int filterCallingUid, int userId) {
        return mComputer.generateApplicationInfoFromSettingsLPw(packageName, flags,
                filterCallingUid, userId);
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        return mComputer.getApplicationInfo(packageName, flags, userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out applications
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    private ApplicationInfo getApplicationInfoInternal(String packageName, int flags,
            int filterCallingUid, int userId) {
        return mComputer.getApplicationInfoInternal(packageName, flags,
                filterCallingUid, userId);
    }

    @GuardedBy("mLock")
    private String normalizePackageNameLPr(String packageName) {
        String normalizedPackageName = mSettings.getRenamedPackageLPr(packageName);
        return normalizedPackageName != null ? normalizedPackageName : packageName;
    }

    @Override
    public void deletePreloadsFileCache() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CLEAR_APP_CACHE,
                "deletePreloadsFileCache");
        File dir = Environment.getDataPreloadsFileCacheDirectory();
        Slog.i(TAG, "Deleting preloaded file cache " + dir);
        FileUtils.deleteContents(dir);
    }

    @Override
    public void freeStorageAndNotify(final String volumeUuid, final long freeStorageSize,
            final int storageFlags, final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        mHandler.post(() -> {
            boolean success = false;
            try {
                freeStorage(volumeUuid, freeStorageSize, storageFlags);
                success = true;
            } catch (IOException e) {
                Slog.w(TAG, e);
            }
            if (observer != null) {
                try {
                    observer.onRemoveCompleted(null, success);
                } catch (RemoteException e) {
                    Slog.w(TAG, e);
                }
            }
        });
    }

    @Override
    public void freeStorage(final String volumeUuid, final long freeStorageSize,
            final int storageFlags, final IntentSender pi) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, TAG);
        mHandler.post(() -> {
            boolean success = false;
            try {
                freeStorage(volumeUuid, freeStorageSize, storageFlags);
                success = true;
            } catch (IOException e) {
                Slog.w(TAG, e);
            }
            if (pi != null) {
                try {
                    pi.sendIntent(null, success ? 1 : 0, null, null, null);
                } catch (SendIntentException e) {
                    Slog.w(TAG, e);
                }
            }
        });
    }

    /**
     * Blocking call to clear various types of cached data across the system
     * until the requested bytes are available.
     */
    public void freeStorage(String volumeUuid, long bytes, int storageFlags) throws IOException {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        final File file = storage.findPathForUuid(volumeUuid);
        if (file.getUsableSpace() >= bytes) return;

        if (mEnableFreeCacheV2) {
            final boolean internalVolume = Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL,
                    volumeUuid);
            final boolean aggressive = (storageFlags
                    & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0;
            final long reservedBytes = storage.getStorageCacheBytes(file, storageFlags);

            // 1. Pre-flight to determine if we have any chance to succeed
            // 2. Consider preloaded data (after 1w honeymoon, unless aggressive)
            if (internalVolume && (aggressive || SystemProperties
                    .getBoolean("persist.sys.preloads.file_cache_expired", false))) {
                deletePreloadsFileCache();
                if (file.getUsableSpace() >= bytes) return;
            }

            // 3. Consider parsed APK data (aggressive only)
            if (internalVolume && aggressive) {
                FileUtils.deleteContents(mCacheDir);
                if (file.getUsableSpace() >= bytes) return;
            }

            // 4. Consider cached app data (above quotas)
            try {
                mInstaller.freeCache(volumeUuid, bytes, reservedBytes,
                        Installer.FLAG_FREE_CACHE_V2);
            } catch (InstallerException ignored) {
            }
            if (file.getUsableSpace() >= bytes) return;

            // 5. Consider shared libraries with refcount=0 and age>min cache period
            if (internalVolume && pruneUnusedStaticSharedLibraries(bytes,
                    android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                            Global.UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD,
                            DEFAULT_UNUSED_STATIC_SHARED_LIB_MIN_CACHE_PERIOD))) {
                return;
            }

            // 6. Consider dexopt output (aggressive only)
            // TODO: Implement

            // 7. Consider installed instant apps unused longer than min cache period
            if (internalVolume && mInstantAppRegistry.pruneInstalledInstantApps(bytes,
                    android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                            Global.INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                            InstantAppRegistry.DEFAULT_INSTALLED_INSTANT_APP_MIN_CACHE_PERIOD))) {
                return;
            }

            // 8. Consider cached app data (below quotas)
            try {
                mInstaller.freeCache(volumeUuid, bytes, reservedBytes,
                        Installer.FLAG_FREE_CACHE_V2 | Installer.FLAG_FREE_CACHE_V2_DEFY_QUOTA);
            } catch (InstallerException ignored) {
            }
            if (file.getUsableSpace() >= bytes) return;

            // 9. Consider DropBox entries
            // TODO: Implement

            // 10. Consider instant meta-data (uninstalled apps) older that min cache period
            if (internalVolume && mInstantAppRegistry.pruneUninstalledInstantApps(bytes,
                    android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                            Global.UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD,
                            InstantAppRegistry.DEFAULT_UNINSTALLED_INSTANT_APP_MIN_CACHE_PERIOD))) {
                return;
            }

            // 11. Free storage service cache
            StorageManagerInternal smInternal =
                    mInjector.getLocalService(StorageManagerInternal.class);
            long freeBytesRequired = bytes - file.getUsableSpace();
            if (freeBytesRequired > 0) {
                smInternal.freeCache(volumeUuid, freeBytesRequired);
            }
        } else {
            try {
                mInstaller.freeCache(volumeUuid, bytes, 0, 0);
            } catch (InstallerException ignored) {
            }
        }
        if (file.getUsableSpace() >= bytes) return;

        throw new IOException("Failed to free " + bytes + " on storage device at " + file);
    }

    private boolean pruneUnusedStaticSharedLibraries(long neededSpace, long maxCachePeriod)
            throws IOException {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        final File volume = storage.findPathForUuid(StorageManager.UUID_PRIVATE_INTERNAL);

        List<VersionedPackage> packagesToDelete = null;
        final long now = System.currentTimeMillis();

        synchronized (mLock) {
            final int libCount = mSharedLibraries.size();
            for (int i = 0; i < libCount; i++) {
                final WatchedLongSparseArray<SharedLibraryInfo> versionedLib
                        = mSharedLibraries.valueAt(i);
                if (versionedLib == null) {
                    continue;
                }
                final int versionCount = versionedLib.size();
                for (int j = 0; j < versionCount; j++) {
                    SharedLibraryInfo libInfo = versionedLib.valueAt(j);
                    // Skip packages that are not static shared libs.
                    if (!libInfo.isStatic()) {
                        break;
                    }
                    // Important: We skip static shared libs used for some user since
                    // in such a case we need to keep the APK on the device. The check for
                    // a lib being used for any user is performed by the uninstall call.
                    final VersionedPackage declaringPackage = libInfo.getDeclaringPackage();
                    // Resolve the package name - we use synthetic package names internally
                    final String internalPackageName = resolveInternalPackageNameLPr(
                            declaringPackage.getPackageName(),
                            declaringPackage.getLongVersionCode());
                    final PackageSetting ps = mSettings.getPackageLPr(internalPackageName);
                    // Skip unused static shared libs cached less than the min period
                    // to prevent pruning a lib needed by a subsequently installed package.
                    if (ps == null || now - ps.getLastUpdateTime() < maxCachePeriod) {
                        continue;
                    }

                    if (ps.getPkg().isSystem()) {
                        continue;
                    }

                    if (packagesToDelete == null) {
                        packagesToDelete = new ArrayList<>();
                    }
                    packagesToDelete.add(new VersionedPackage(internalPackageName,
                            declaringPackage.getLongVersionCode()));
                }
            }
        }

        if (packagesToDelete != null) {
            final int packageCount = packagesToDelete.size();
            for (int i = 0; i < packageCount; i++) {
                final VersionedPackage pkgToDelete = packagesToDelete.get(i);
                // Delete the package synchronously (will fail of the lib used for any user).
                if (deletePackageX(pkgToDelete.getPackageName(), pkgToDelete.getLongVersionCode(),
                        UserHandle.USER_SYSTEM, PackageManager.DELETE_ALL_USERS,
                        true /*removedBySystem*/) == PackageManager.DELETE_SUCCEEDED) {
                    if (volume.getUsableSpace() >= neededSpace) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Update given flags when being used to request {@link PackageInfo}.
     */
    private int updateFlagsForPackage(int flags, int userId) {
        return mComputer.updateFlagsForPackage(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link ApplicationInfo}.
     */
    private int updateFlagsForApplication(int flags, int userId) {
        return mComputer.updateFlagsForApplication(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link ComponentInfo}.
     */
    private int updateFlagsForComponent(int flags, int userId) {
        return mComputer.updateFlagsForComponent(flags, userId);
    }

    /**
     * Update given flags when being used to request {@link ResolveInfo}.
     * <p>Instant apps are resolved specially, depending upon context. Minimally,
     * {@code}flags{@code} must have the {@link PackageManager#MATCH_INSTANT}
     * flag set. However, this flag is only honoured in three circumstances:
     * <ul>
     * <li>when called from a system process</li>
     * <li>when the caller holds the permission {@code android.permission.ACCESS_INSTANT_APPS}</li>
     * <li>when resolution occurs to start an activity with a {@code android.intent.action.VIEW}
     * action and a {@code android.intent.category.BROWSABLE} category</li>
     * </ul>
     */
    private int updateFlagsForResolve(int flags, int userId, int callingUid,
            boolean wantInstantApps, boolean isImplicitImageCaptureIntentAndNotSetByDpc) {
        return mComputer.updateFlagsForResolve(flags, userId, callingUid,
                wantInstantApps, isImplicitImageCaptureIntentAndNotSetByDpc);
    }

    @Override
    public int getTargetSdkVersion(String packageName)  {
        synchronized (mLock) {
            final AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null) {
                return -1;
            }

            final PackageSetting ps = getPackageSetting(pkg.getPackageName());
            if (shouldFilterApplicationLocked(ps, Binder.getCallingUid(),
                    UserHandle.getCallingUserId())) {
                return -1;
            }
            return pkg.getTargetSdkVersion();
        }
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        return mComputer.getActivityInfo(component, flags, userId);
    }

    /**
     * Important: The provided filterCallingUid is used exclusively to filter out activities
     * that can be seen based on user state. It's typically the original caller uid prior
     * to clearing. Because it can only be provided by trusted code, its value can be
     * trusted and will be used as-is; unlike userId which will be validated by this method.
     */
    private ActivityInfo getActivityInfoInternal(ComponentName component, int flags,
            int filterCallingUid, int userId) {
        return mComputer.getActivityInfoInternal(component, flags,
                filterCallingUid, userId);
    }

    @Override
    public boolean activitySupportsIntent(ComponentName component, Intent intent,
            String resolvedType) {
        synchronized (mLock) {
            if (component.equals(mResolveComponentName)) {
                // The resolver supports EVERYTHING!
                return true;
            }
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            ParsedActivity a = mComponentResolver.getActivity(component);
            if (a == null) {
                return false;
            }
            PackageSetting ps = mSettings.getPackageLPr(component.getPackageName());
            if (ps == null) {
                return false;
            }
            if (shouldFilterApplicationLocked(
                    ps, callingUid, component, TYPE_ACTIVITY, callingUserId)) {
                return false;
            }
            for (int i=0; i< a.getIntents().size(); i++) {
                if (a.getIntents().get(i).match(intent.getAction(), resolvedType, intent.getScheme(),
                        intent.getData(), intent.getCategories(), TAG) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForComponent(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get receiver info");
        synchronized (mLock) {
            ParsedActivity a = mComponentResolver.getReceiver(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getReceiverInfo " + component + ": " + a);

            if (a == null) {
                return null;
            }

            AndroidPackage pkg = mPackages.get(a.getPackageName());
            if (pkg == null) {
                return null;
            }

            if (mSettings.isEnabledAndMatchLPr(pkg, a, flags, userId)) {
                PackageSetting ps = mSettings.getPackageLPr(component.getPackageName());
                if (ps == null) return null;
                if (shouldFilterApplicationLocked(
                        ps, callingUid, component, TYPE_RECEIVER, userId)) {
                    return null;
                }
                return PackageInfoUtils.generateActivityInfo(pkg,
                        a, flags, ps.readUserState(userId), userId, ps);
            }
        }
        return null;
    }

    @Override
    public ParceledListSlice<SharedLibraryInfo> getSharedLibraries(String packageName,
            int flags, int userId) {
        if (!mUserManager.exists(userId)) return null;
        Preconditions.checkArgumentNonnegative(userId, "userId must be >= 0");
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        flags = updateFlagsForPackage(flags, userId);

        final boolean canSeeStaticLibraries =
                mContext.checkCallingOrSelfPermission(INSTALL_PACKAGES)
                        == PERMISSION_GRANTED
                || mContext.checkCallingOrSelfPermission(DELETE_PACKAGES)
                        == PERMISSION_GRANTED
                || canRequestPackageInstallsInternal(packageName, callingUid, userId,
                        false  /* throwIfPermNotDeclared*/)
                || mContext.checkCallingOrSelfPermission(REQUEST_DELETE_PACKAGES)
                        == PERMISSION_GRANTED
                || mContext.checkCallingOrSelfPermission(
                        Manifest.permission.ACCESS_SHARED_LIBRARIES) == PERMISSION_GRANTED;

        synchronized (mLock) {
            List<SharedLibraryInfo> result = null;

            final int libCount = mSharedLibraries.size();
            for (int i = 0; i < libCount; i++) {
                WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                        mSharedLibraries.valueAt(i);
                if (versionedLib == null) {
                    continue;
                }

                final int versionCount = versionedLib.size();
                for (int j = 0; j < versionCount; j++) {
                    SharedLibraryInfo libInfo = versionedLib.valueAt(j);
                    if (!canSeeStaticLibraries && libInfo.isStatic()) {
                        break;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        PackageInfo packageInfo = getPackageInfoVersioned(
                                libInfo.getDeclaringPackage(), flags
                                        | PackageManager.MATCH_STATIC_SHARED_LIBRARIES, userId);
                        if (packageInfo == null) {
                            continue;
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    SharedLibraryInfo resLibInfo = new SharedLibraryInfo(libInfo.getPath(),
                            libInfo.getPackageName(), libInfo.getAllCodePaths(),
                            libInfo.getName(), libInfo.getLongVersion(),
                            libInfo.getType(), libInfo.getDeclaringPackage(),
                            getPackagesUsingSharedLibraryLPr(libInfo, flags, callingUid, userId),
                            (libInfo.getDependencies() == null
                                    ? null
                                    : new ArrayList<>(libInfo.getDependencies())),
                            libInfo.isNative());

                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(resLibInfo);
                }
            }

            return result != null ? new ParceledListSlice<>(result) : null;
        }
    }

    @Nullable
    @Override
    public ParceledListSlice<SharedLibraryInfo> getDeclaredSharedLibraries(
            @NonNull String packageName, int flags, @NonNull int userId) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_SHARED_LIBRARIES,
                "getDeclaredSharedLibraries");
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "getDeclaredSharedLibraries");

        Preconditions.checkNotNull(packageName, "packageName cannot be null");
        Preconditions.checkArgumentNonnegative(userId, "userId must be >= 0");
        if (!mUserManager.exists(userId)) {
            return null;
        }

        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }

        synchronized (mLock) {
            List<SharedLibraryInfo> result = null;

            int libraryCount = mSharedLibraries.size();
            for (int i = 0; i < libraryCount; i++) {
                WatchedLongSparseArray<SharedLibraryInfo> versionedLibrary =
                        mSharedLibraries.valueAt(i);
                if (versionedLibrary == null) {
                    continue;
                }

                int versionCount = versionedLibrary.size();
                for (int j = 0; j < versionCount; j++) {
                    SharedLibraryInfo libraryInfo = versionedLibrary.valueAt(j);

                    VersionedPackage declaringPackage = libraryInfo.getDeclaringPackage();
                    if (!Objects.equals(declaringPackage.getPackageName(), packageName)) {
                        continue;
                    }

                    final long identity = Binder.clearCallingIdentity();
                    try {
                        PackageInfo packageInfo = getPackageInfoVersioned(declaringPackage, flags
                                | PackageManager.MATCH_STATIC_SHARED_LIBRARIES, userId);
                        if (packageInfo == null) {
                            continue;
                        }
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    SharedLibraryInfo resultLibraryInfo = new SharedLibraryInfo(
                            libraryInfo.getPath(), libraryInfo.getPackageName(),
                            libraryInfo.getAllCodePaths(), libraryInfo.getName(),
                            libraryInfo.getLongVersion(), libraryInfo.getType(),
                            libraryInfo.getDeclaringPackage(),
                            getPackagesUsingSharedLibraryLPr(
                                    libraryInfo, flags, callingUid, userId),
                            libraryInfo.getDependencies() == null
                                    ? null : new ArrayList<>(libraryInfo.getDependencies()),
                            libraryInfo.isNative());

                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(resultLibraryInfo);
                }
            }

            return result != null ? new ParceledListSlice<>(result) : null;
        }
    }

    @GuardedBy("mLock")
    private List<VersionedPackage> getPackagesUsingSharedLibraryLPr(
            SharedLibraryInfo libInfo, int flags, int callingUid, int userId) {
        List<VersionedPackage> versionedPackages = null;
        final int packageCount = mSettings.getPackagesLocked().size();
        for (int i = 0; i < packageCount; i++) {
            PackageSetting ps = mSettings.getPackagesLocked().valueAt(i);

            if (ps == null) {
                continue;
            }

            if (!ps.readUserState(userId).isAvailable(flags)) {
                continue;
            }

            final String libName = libInfo.getName();
            if (libInfo.isStatic()) {
                final int libIdx = ArrayUtils.indexOf(ps.usesStaticLibraries, libName);
                if (libIdx < 0) {
                    continue;
                }
                if (ps.usesStaticLibrariesVersions[libIdx] != libInfo.getLongVersion()) {
                    continue;
                }
                if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    continue;
                }
                if (versionedPackages == null) {
                    versionedPackages = new ArrayList<>();
                }
                // If the dependent is a static shared lib, use the public package name
                String dependentPackageName = ps.getPackageName();
                if (ps.getPkg() != null && ps.getPkg().isStaticSharedLibrary()) {
                    dependentPackageName = ps.getPkg().getManifestPackageName();
                }
                versionedPackages.add(new VersionedPackage(dependentPackageName,
                        ps.getLongVersionCode()));
            } else if (ps.getPkg() != null) {
                if (ArrayUtils.contains(ps.getPkg().getUsesLibraries(), libName)
                        || ArrayUtils.contains(ps.getPkg().getUsesOptionalLibraries(), libName)) {
                    if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                        continue;
                    }
                    if (versionedPackages == null) {
                        versionedPackages = new ArrayList<>();
                    }
                    versionedPackages.add(new VersionedPackage(ps.getPackageName(),
                            ps.getLongVersionCode()));
                }
            }
        }

        return versionedPackages;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        return mComputer.getServiceInfo(component, flags, userId);
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (!mUserManager.exists(userId)) return null;
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForComponent(flags, userId);
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get provider info");
        synchronized (mLock) {
            ParsedProvider p = mComponentResolver.getProvider(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                    TAG, "getProviderInfo " + component + ": " + p);
            if (p == null) {
                return null;
            }

            AndroidPackage pkg = mPackages.get(p.getPackageName());
            if (pkg == null) {
                return null;
            }

            if (mSettings.isEnabledAndMatchLPr(pkg, p, flags, userId)) {
                PackageSetting ps = mSettings.getPackageLPr(component.getPackageName());
                if (ps == null) return null;
                if (shouldFilterApplicationLocked(
                        ps, callingUid, component, TYPE_PROVIDER, userId)) {
                    return null;
                }
                PackageUserState state = ps.readUserState(userId);
                final ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(
                        pkg, flags, state, userId, ps);
                if (appInfo == null) {
                    return null;
                }
                return PackageInfoUtils.generateProviderInfo(
                        pkg, p, flags, state, appInfo, userId, ps);
            }
        }
        return null;
    }

    @Override
    public ModuleInfo getModuleInfo(String packageName, @ModuleInfoFlags int flags) {
        return mModuleInfoProvider.getModuleInfo(packageName, flags);
    }

    @Override
    public List<ModuleInfo> getInstalledModules(int flags) {
        return mModuleInfoProvider.getInstalledModules(flags);
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        // allow instant applications
        synchronized (mLock) {
            Set<String> libs = null;
            final int libCount = mSharedLibraries.size();
            for (int i = 0; i < libCount; i++) {
                WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                        mSharedLibraries.valueAt(i);
                if (versionedLib == null) {
                    continue;
                }
                final int versionCount = versionedLib.size();
                for (int j = 0; j < versionCount; j++) {
                    SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                    if (!libraryInfo.isStatic()) {
                        if (libs == null) {
                            libs = new ArraySet<>();
                        }
                        libs.add(libraryInfo.getName());
                        break;
                    }
                    PackageSetting ps = mSettings.getPackageLPr(libraryInfo.getPackageName());
                    if (ps != null && !filterSharedLibPackageLPr(ps, Binder.getCallingUid(),
                            UserHandle.getUserId(Binder.getCallingUid()),
                            PackageManager.MATCH_STATIC_SHARED_LIBRARIES)) {
                        if (libs == null) {
                            libs = new ArraySet<>();
                        }
                        libs.add(libraryInfo.getName());
                        break;
                    }
                }
            }

            if (libs != null) {
                String[] libsArray = new String[libs.size()];
                libs.toArray(libsArray);
                return libsArray;
            }

            return null;
        }
    }

    @Override
    public @NonNull String getServicesSystemSharedLibraryPackageName() {
        // allow instant applications
        synchronized (mLock) {
            return mServicesExtensionPackageName;
        }
    }

    @Override
    public @NonNull String getSharedSystemSharedLibraryPackageName() {
        // allow instant applications
        synchronized (mLock) {
            return mSharedSystemSharedLibraryPackageName;
        }
    }

    @GuardedBy("mLock")
    void updateSequenceNumberLP(PackageSetting pkgSetting, int[] userList) {
        for (int i = userList.length - 1; i >= 0; --i) {
            final int userId = userList[i];
            SparseArray<String> changedPackages = mChangedPackages.get(userId);
            if (changedPackages == null) {
                changedPackages = new SparseArray<>();
                mChangedPackages.put(userId, changedPackages);
            }
            Map<String, Integer> sequenceNumbers = mChangedPackagesSequenceNumbers.get(userId);
            if (sequenceNumbers == null) {
                sequenceNumbers = new HashMap<>();
                mChangedPackagesSequenceNumbers.put(userId, sequenceNumbers);
            }
            final Integer sequenceNumber = sequenceNumbers.get(pkgSetting.getPackageName());
            if (sequenceNumber != null) {
                changedPackages.remove(sequenceNumber);
            }
            changedPackages.put(mChangedPackagesSequenceNumber, pkgSetting.getPackageName());
            sequenceNumbers.put(pkgSetting.getPackageName(), mChangedPackagesSequenceNumber);
        }
        mChangedPackagesSequenceNumber++;
    }

    @Override
    public ChangedPackages getChangedPackages(int sequenceNumber, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        if (!mUserManager.exists(userId)) {
            return null;
        }
        enforceCrossUserPermission(callingUid, userId, false, false, "getChangedPackages");
        synchronized (mLock) {
            if (sequenceNumber >= mChangedPackagesSequenceNumber) {
                return null;
            }
            final SparseArray<String> changedPackages = mChangedPackages.get(userId);
            if (changedPackages == null) {
                return null;
            }
            final List<String> packageNames =
                    new ArrayList<>(mChangedPackagesSequenceNumber - sequenceNumber);
            for (int i = sequenceNumber; i < mChangedPackagesSequenceNumber; i++) {
                final String packageName = changedPackages.get(i);
                if (packageName != null) {
                    // Filter out the changes if the calling package should not be able to see it.
                    final PackageSetting ps = mSettings.getPackageLPr(packageName);
                    if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                        continue;
                    }
                    packageNames.add(packageName);
                }
            }
            return packageNames.isEmpty()
                    ? null : new ChangedPackages(mChangedPackagesSequenceNumber, packageNames);
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
    @Override
    public int checkPermission(String permName, String pkgName, int userId) {
        return mPermissionManager.checkPermission(pkgName, permName, userId);
    }

    // NOTE: Can't remove without a major refactor. Keep around for now.
    @Override
    public int checkUidPermission(String permName, int uid) {
        return mComputer.checkUidPermission(permName, uid);
    }

    @Override
    public String getPermissionControllerPackageName() {
        synchronized (mLock) {
            if (mRequiredPermissionControllerPackage != null) {
                final PackageSetting ps = getPackageSetting(mRequiredPermissionControllerPackage);
                if (ps != null) {
                    final int callingUid = Binder.getCallingUid();
                    final int callingUserId = UserHandle.getUserId(callingUid);
                    if (!shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                        return mRequiredPermissionControllerPackage;
                    }
                }
            }
            throw new IllegalStateException("PermissionController is not found");
        }
    }

    String getPackageInstallerPackageName() {
        synchronized (mLock) {
            return mRequiredInstallerPackage;
        }
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public boolean addPermission(PermissionInfo info) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class).addPermission(info, false);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        return mContext.getSystemService(PermissionManager.class).addPermission(info, true);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public void removePermission(String permName) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        mContext.getSystemService(PermissionManager.class).removePermission(permName);
    }

    // NOTE: Can't remove due to unsupported app usage
    @Override
    public void grantRuntimePermission(String packageName, String permName, final int userId) {
        // Because this is accessed via the package manager service AIDL,
        // go through the permission manager service AIDL
        mContext.getSystemService(PermissionManager.class)
                .grantRuntimePermission(packageName, permName, UserHandle.of(userId));
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

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        synchronized (mLock) {
            final AndroidPackage p1 = mPackages.get(pkg1);
            final AndroidPackage p2 = mPackages.get(pkg2);
            final PackageSetting ps1 = p1 == null ? null : getPackageSetting(p1.getPackageName());
            final PackageSetting ps2 = p2 == null ? null : getPackageSetting(p2.getPackageName());
            if (p1 == null || ps1 == null || p2 == null || ps2 == null) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (shouldFilterApplicationLocked(ps1, callingUid, callingUserId)
                    || shouldFilterApplicationLocked(ps2, callingUid, callingUserId)) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return checkSignaturesInternal(p1.getSigningDetails(), p2.getSigningDetails());
        }
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        // Map to base uids.
        final int appId1 = UserHandle.getAppId(uid1);
        final int appId2 = UserHandle.getAppId(uid2);
        // reader
        synchronized (mLock) {
            SigningDetails p1SigningDetails;
            SigningDetails p2SigningDetails;
            Object obj = mSettings.getSettingLPr(appId1);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    final SharedUserSetting sus = (SharedUserSetting) obj;
                    if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                        return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                    }
                    p1SigningDetails = sus.signatures.mSigningDetails;
                } else if (obj instanceof PackageSetting) {
                    final PackageSetting ps = (PackageSetting) obj;
                    if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                        return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                    }
                    p1SigningDetails = ps.getSigningDetails();
                } else {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            obj = mSettings.getSettingLPr(appId2);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    final SharedUserSetting sus = (SharedUserSetting) obj;
                    if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                        return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                    }
                    p2SigningDetails = sus.signatures.mSigningDetails;
                } else if (obj instanceof PackageSetting) {
                    final PackageSetting ps = (PackageSetting) obj;
                    if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                        return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                    }
                    p2SigningDetails = ps.getSigningDetails();
                } else {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return checkSignaturesInternal(p1SigningDetails, p2SigningDetails);
        }
    }

    private int checkSignaturesInternal(SigningDetails p1SigningDetails,
            SigningDetails p2SigningDetails) {
        if (p1SigningDetails == null) {
            return p2SigningDetails == null
                    ? PackageManager.SIGNATURE_NEITHER_SIGNED
                    : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        }
        if (p2SigningDetails == null) {
            return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        }
        int result = compareSignatures(p1SigningDetails.getSignatures(),
                p2SigningDetails.getSignatures());
        if (result == PackageManager.SIGNATURE_MATCH) {
            return result;
        }
        // To support backwards compatibility with clients of this API expecting pre-key
        // rotation results if either of the packages has a signing lineage the oldest signer
        // in the lineage is used for signature verification.
        if (p1SigningDetails.hasPastSigningCertificates()
                || p2SigningDetails.hasPastSigningCertificates()) {
            Signature[] p1Signatures = p1SigningDetails.hasPastSigningCertificates()
                    ? new Signature[]{p1SigningDetails.getPastSigningCertificates()[0]}
                    : p1SigningDetails.getSignatures();
            Signature[] p2Signatures = p2SigningDetails.hasPastSigningCertificates()
                    ? new Signature[]{p2SigningDetails.getPastSigningCertificates()[0]}
                    : p2SigningDetails.getSignatures();
            result = compareSignatures(p1Signatures, p2Signatures);
        }
        return result;
    }

    @Override
    public boolean hasSigningCertificate(
            String packageName, byte[] certificate, @PackageManager.CertificateInputType int type) {

        synchronized (mLock) {
            final AndroidPackage p = mPackages.get(packageName);
            if (p == null) {
                return false;
            }
            final PackageSetting ps = getPackageSetting(p.getPackageName());
            if (ps == null) {
                return false;
            }
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                return false;
            }
            switch (type) {
                case CERT_INPUT_RAW_X509:
                    return p.getSigningDetails().hasCertificate(certificate);
                case CERT_INPUT_SHA256:
                    return p.getSigningDetails().hasSha256Certificate(certificate);
                default:
                    return false;
            }
        }
    }

    @Override
    public boolean hasUidSigningCertificate(
            int uid, byte[] certificate, @PackageManager.CertificateInputType int type) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        // Map to base uids.
        final int appId = UserHandle.getAppId(uid);
        // reader
        synchronized (mLock) {
            final SigningDetails signingDetails;
            final Object obj = mSettings.getSettingLPr(appId);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    final SharedUserSetting sus = (SharedUserSetting) obj;
                    if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                        return false;
                    }
                    signingDetails = sus.signatures.mSigningDetails;
                } else if (obj instanceof PackageSetting) {
                    final PackageSetting ps = (PackageSetting) obj;
                    if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                        return false;
                    }
                    signingDetails = ps.getSigningDetails();
                } else {
                    return false;
                }
            } else {
                return false;
            }
            switch (type) {
                case CERT_INPUT_RAW_X509:
                    return signingDetails.hasCertificate(certificate);
                case CERT_INPUT_SHA256:
                    return signingDetails.hasSha256Certificate(certificate);
                default:
                    return false;
            }
        }
    }

    /**
     * If the database version for this type of package (internal storage or
     * external storage) is less than the version where package signatures
     * were updated, return true.
     */
    boolean isCompatSignatureUpdateNeeded(AndroidPackage pkg) {
        return isCompatSignatureUpdateNeeded(getSettingsVersionForPackage(pkg));
    }

    static boolean isCompatSignatureUpdateNeeded(VersionInfo ver) {
        return ver.databaseVersion < DatabaseVersion.SIGNATURE_END_ENTITY;
    }

    boolean isRecoverSignatureUpdateNeeded(AndroidPackage pkg) {
        return isRecoverSignatureUpdateNeeded(getSettingsVersionForPackage(pkg));
    }

    static boolean isRecoverSignatureUpdateNeeded(VersionInfo ver) {
        return ver.databaseVersion < DatabaseVersion.SIGNATURE_MALFORMED_RECOVER;
    }

    @Override
    public List<String> getAllPackages() {
        // Allow iorapd to call this method.
        if (Binder.getCallingUid() != Process.IORAPD_UID) {
            enforceSystemOrRootOrShell("getAllPackages is limited to privileged callers");
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        synchronized (mLock) {
            if (canViewInstantApps(callingUid, callingUserId)) {
                return new ArrayList<>(mPackages.keySet());
            }
            final String instantAppPkgName = getInstantAppPackageName(callingUid);
            final List<String> result = new ArrayList<>();
            if (instantAppPkgName != null) {
                // caller is an instant application; filter unexposed applications
                for (AndroidPackage pkg : mPackages.values()) {
                    if (!pkg.isVisibleToInstantApps()) {
                        continue;
                    }
                    result.add(pkg.getPackageName());
                }
            } else {
                // caller is a normal application; filter instant applications
                for (AndroidPackage pkg : mPackages.values()) {
                    final PackageSetting ps = getPackageSetting(pkg.getPackageName());
                    if (ps != null
                            && ps.getInstantApp(callingUserId)
                            && !mInstantAppRegistry.isInstantAccessGranted(callingUserId,
                                    UserHandle.getAppId(callingUid), ps.getAppId())) {
                        continue;
                    }
                    result.add(pkg.getPackageName());
                }
            }
            return result;
        }
    }

    /**
     * <em>IMPORTANT:</em> Not all packages returned by this method may be known
     * to the system. There are two conditions in which this may occur:
     * <ol>
     *   <li>The package is on adoptable storage and the device has been removed</li>
     *   <li>The package is being removed and the internal structures are partially updated</li>
     * </ol>
     * The second is an artifact of the current data structures and should be fixed. See
     * b/111075456 for one such instance.
     * This binder API is cached.  If the algorithm in this method changes,
     * or if the underlying objecs (as returned by getSettingLPr()) change
     * then the logic that invalidates the cache must be revisited.  See
     * calls to invalidateGetPackagesForUidCache() to locate the points at
     * which the cache is invalidated.
     */
    @Override
    public String[] getPackagesForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        enforceCrossUserOrProfilePermission(callingUid, userId,
                /* requireFullPermission */ false,
                /* checkShell */ false, "getPackagesForUid");
        return mComputer.getPackagesForUid(uid);
    }

    @Override
    public String getNameForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(uid);
        synchronized (mLock) {
            final Object obj = mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                    return null;
                }
                return sus.name + ":" + sus.userId;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                    return null;
                }
                return ps.getPackageName();
            }
            return null;
        }
    }

    @Override
    public String[] getNamesForUids(int[] uids) {
        if (uids == null || uids.length == 0) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return null;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final String[] names = new String[uids.length];
        synchronized (mLock) {
            for (int i = uids.length - 1; i >= 0; i--) {
                final int appId = UserHandle.getAppId(uids[i]);
                final Object obj = mSettings.getSettingLPr(appId);
                if (obj instanceof SharedUserSetting) {
                    final SharedUserSetting sus = (SharedUserSetting) obj;
                    if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                        names[i] = null;
                    } else {
                        names[i] = "shared:" + sus.name;
                    }
                } else if (obj instanceof PackageSetting) {
                    final PackageSetting ps = (PackageSetting) obj;
                    if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                        names[i] = null;
                    } else {
                        names[i] = ps.getPackageName();
                    }
                } else {
                    names[i] = null;
                }
            }
        }
        return names;
    }

    @Override
    public int getUidForSharedUser(String sharedUserName) {
        if (sharedUserName == null) {
            return Process.INVALID_UID;
        }
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return Process.INVALID_UID;
        }
        // reader
        synchronized (mLock) {
            try {
                final SharedUserSetting suid = mSettings.getSharedUserLPw(sharedUserName,
                        0 /* pkgFlags */, 0 /* pkgPrivateFlags */, false /* create */);
                if (suid != null && !shouldFilterApplicationLocked(suid, callingUid,
                        UserHandle.getUserId(callingUid))) {
                    return suid.userId;
                }
            } catch (PackageManagerException ignore) {
                // can't happen, but, still need to catch it
            }
            return Process.INVALID_UID;
        }
    }

    @Override
    public int getFlagsForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(uid);
        synchronized (mLock) {
            final Object obj = mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                    return 0;
                }
                return sus.pkgFlags;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                    return 0;
                }
                return ps.pkgFlags;
            }
        }
        return 0;
    }

    @Override
    public int getPrivateFlagsForUid(int uid) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        final int appId = UserHandle.getAppId(uid);
        synchronized (mLock) {
            final Object obj = mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                if (shouldFilterApplicationLocked(sus, callingUid, callingUserId)) {
                    return 0;
                }
                return sus.pkgPrivateFlags;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                    return 0;
                }
                return ps.pkgPrivateFlags;
            }
        }
        return 0;
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        }
        final int appId = UserHandle.getAppId(uid);
        // reader
        synchronized (mLock) {
            final Object obj = mSettings.getSettingLPr(appId);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                final int numPackages = sus.packages.size();
                for (int index = 0; index < numPackages; index++) {
                    final PackageSetting ps = sus.packages.valueAt(index);
                    if (ps.isPrivileged()) {
                        return true;
                    }
                }
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return ps.isPrivileged();
            }
        }
        return false;
    }

    // NOTE: Can't remove due to unsupported app usage
    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(String permissionName) {
        if (permissionName == null) {
            return EmptyArray.STRING;
        }
        if (getInstantAppPackageName(getCallingUid()) != null) {
            return EmptyArray.STRING;
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        final ArraySet<String> packageNames = new ArraySet(
                mPermissionManager.getAppOpPermissionPackages(permissionName));
        synchronized (mLock) {
            for (int i = packageNames.size() - 1; i >= 0; i--) {
                final String packageName = packageNames.valueAt(i);
                if (!shouldFilterApplicationLocked(mSettings.getPackageLPr(packageName),
                        callingUid, callingUserId)) {
                    continue;
                }
                packageNames.removeAt(i);
            }
        }
        return packageNames.toArray(new String[packageNames.size()]);
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType,
            int flags, int userId) {
        return resolveIntentInternal(intent, resolvedType, flags, 0 /*privateResolveFlags*/,
                userId, false, Binder.getCallingUid());
    }

    /**
     * Normally instant apps can only be resolved when they're visible to the caller.
     * However, if {@code resolveForStart} is {@code true}, all instant apps are visible
     * since we need to allow the system to start any installed application.
     */
    private ResolveInfo resolveIntentInternal(Intent intent, String resolvedType, int flags,
            @PrivateResolveFlags int privateResolveFlags, int userId, boolean resolveForStart,
            int filterCallingUid) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "resolveIntent");

            if (!mUserManager.exists(userId)) return null;
            final int callingUid = Binder.getCallingUid();
            flags = updateFlagsForResolve(flags, userId, filterCallingUid, resolveForStart,
                    isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, userId, resolvedType,
                            flags));
            enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                    false /*checkShell*/, "resolve intent");

            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "queryIntentActivities");
            final List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType,
                    flags, privateResolveFlags, filterCallingUid, userId, resolveForStart,
                    true /*allowDynamicSplits*/);
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

            final boolean queryMayBeFiltered =
                    UserHandle.getAppId(filterCallingUid) >= Process.FIRST_APPLICATION_UID
                            && !resolveForStart;

            final ResolveInfo bestChoice =
                    chooseBestActivity(
                            intent, resolvedType, flags, privateResolveFlags, query, userId,
                            queryMayBeFiltered);
            final boolean nonBrowserOnly =
                    (privateResolveFlags & PackageManagerInternal.RESOLVE_NON_BROWSER_ONLY) != 0;
            if (nonBrowserOnly && bestChoice != null && bestChoice.handleAllWebDataURI) {
                return null;
            }
            return bestChoice;
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @Override
    public ResolveInfo findPersistentPreferredActivity(Intent intent, int userId) {
        if (!UserHandle.isSameApp(Binder.getCallingUid(), Process.SYSTEM_UID)) {
            throw new SecurityException(
                    "findPersistentPreferredActivity can only be run by the system");
        }
        if (!mUserManager.exists(userId)) {
            return null;
        }
        final int callingUid = Binder.getCallingUid();
        intent = PackageManagerServiceUtils.updateIntentForResolve(intent);
        final String resolvedType = intent.resolveTypeIfNeeded(mContext.getContentResolver());
        final int flags = updateFlagsForResolve(
                0, userId, callingUid, false /*includeInstantApps*/,
                isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, userId, resolvedType, 0));
        final List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags,
                userId);
        synchronized (mLock) {
            return findPersistentPreferredActivityLP(intent, resolvedType, flags, query, false,
                    userId);
        }
    }

    @Override
    public void setLastChosenActivity(Intent intent, String resolvedType, int flags,
            IntentFilter filter, int match, ComponentName activity) {
        setLastChosenActivity(intent, resolvedType, flags,
                              new WatchedIntentFilter(filter), match, activity);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void setLastChosenActivity(Intent intent, String resolvedType, int flags,
            WatchedIntentFilter filter, int match, ComponentName activity) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) {
            Log.v(TAG, "setLastChosenActivity intent=" + intent
                + " resolvedType=" + resolvedType
                + " flags=" + flags
                + " filter=" + filter
                + " match=" + match
                + " activity=" + activity);
            filter.dump(new PrintStreamPrinter(System.out), "    ");
        }
        intent.setComponent(null);
        final List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags,
                userId);
        // Find any earlier preferred or last chosen entries and nuke them
        findPreferredActivityNotLocked(
                intent, resolvedType, flags, query, false, true, false, userId);
        // Add the new activity as the last chosen for this filter
        addPreferredActivity(filter, match, null, activity, false, userId,
                "Setting last chosen", false);
    }

    @Override
    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) Log.v(TAG, "Querying last chosen activity for " + intent);
        final List<ResolveInfo> query = queryIntentActivitiesInternal(intent, resolvedType, flags,
                userId);
        return findPreferredActivityNotLocked(
                intent, resolvedType, flags, query, false, false, false, userId);
    }

    private void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
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

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
            int flags, int privateResolveFlags, List<ResolveInfo> query, int userId,
            boolean queryMayBeFiltered) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                final boolean debug = ((intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0);
                // If there is more than one activity with the same priority,
                // then let the user decide between them.
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                if (DEBUG_INTENT_MATCHING || debug) {
                    Slog.v(TAG, r0.activityInfo.name + "=" + r0.priority + " vs "
                            + r1.activityInfo.name + "=" + r1.priority);
                }
                // If the first activity has a higher priority, or a different
                // default, then it is always desirable to pick it.
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
                // If we have saved a preference for a preferred activity for
                // this Intent, use that.
                ResolveInfo ri = findPreferredActivityNotLocked(intent, resolvedType,
                        flags, query, true, false, debug, userId, queryMayBeFiltered);
                if (ri != null) {
                    return ri;
                }
                int browserCount = 0;
                for (int i = 0; i < N; i++) {
                    ri = query.get(i);
                    if (ri.handleAllWebDataURI) {
                        browserCount++;
                    }
                    // If we have an ephemeral app, use it
                    if (ri.activityInfo.applicationInfo.isInstantApp()) {
                        final String packageName = ri.activityInfo.packageName;
                        final PackageSetting ps = mSettings.getPackageLPr(packageName);
                        if (ps != null && PackageManagerServiceUtils.hasAnyDomainApproval(
                                mDomainVerificationManager, ps, intent, flags, userId)) {
                            return ri;
                        }
                    }
                }
                if ((privateResolveFlags
                        & PackageManagerInternal.RESOLVE_NON_RESOLVER_ONLY) != 0) {
                    return null;
                }
                ri = new ResolveInfo(mResolveInfo);
                // if all resolve options are browsers, mark the resolver's info as if it were
                // also a browser.
                ri.handleAllWebDataURI = browserCount == N;
                ri.activityInfo = new ActivityInfo(ri.activityInfo);
                ri.activityInfo.labelRes = ResolverActivity.getLabelRes(intent.getAction());
                // If all of the options come from the same package, show the application's
                // label and icon instead of the generic resolver's.
                // Some calls like Intent.resolveActivityInfo query the ResolveInfo from here
                // and then throw away the ResolveInfo itself, meaning that the caller loses
                // the resolvePackageName. Therefore the activityInfo.labelRes above provides
                // a fallback for this case; we only set the target package's resources on
                // the ResolveInfo, not the ActivityInfo.
                final String intentPackage = intent.getPackage();
                if (!TextUtils.isEmpty(intentPackage) && allHavePackage(query, intentPackage)) {
                    final ApplicationInfo appi = query.get(0).activityInfo.applicationInfo;
                    ri.resolvePackageName = intentPackage;
                    if (userNeedsBadging(userId)) {
                        ri.noResourceId = true;
                    } else {
                        ri.icon = appi.icon;
                    }
                    ri.iconResourceId = appi.icon;
                    ri.labelRes = appi.labelRes;
                }
                ri.activityInfo.applicationInfo = new ApplicationInfo(
                        ri.activityInfo.applicationInfo);
                if (userId != 0) {
                    ri.activityInfo.applicationInfo.uid = UserHandle.getUid(userId,
                            UserHandle.getAppId(ri.activityInfo.applicationInfo.uid));
                }
                // Make sure that the resolver is displayable in car mode
                if (ri.activityInfo.metaData == null) ri.activityInfo.metaData = new Bundle();
                ri.activityInfo.metaData.putBoolean(Intent.METADATA_DOCK_HOME, true);
                return ri;
            }
        }
        return null;
    }

    /**
     * Return true if the given list is not empty and all of its contents have
     * an activityInfo with the given package name.
     */
    private boolean allHavePackage(List<ResolveInfo> list, String packageName) {
        if (ArrayUtils.isEmpty(list)) {
            return false;
        }
        for (int i = 0, N = list.size(); i < N; i++) {
            final ResolveInfo ri = list.get(i);
            final ActivityInfo ai = ri != null ? ri.activityInfo : null;
            if (ai == null || !packageName.equals(ai.packageName)) {
                return false;
            }
        }
        return true;
    }

    /**
     * From Android R, camera intents have to match system apps. The only exception to this is if
     * the DPC has set the camera persistent preferred activity. This case was introduced
     * because it is important that the DPC has the ability to set both system and non-system
     * camera persistent preferred activities.
     *
     * @return {@code true} if the intent is a camera intent and the persistent preferred
     * activity was not set by the DPC.
     */
    @GuardedBy("mLock")
    private boolean isImplicitImageCaptureIntentAndNotSetByDpcLocked(Intent intent, int userId,
            String resolvedType, int flags) {
        return mComputer.isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, userId,
                resolvedType, flags);
    }

    @GuardedBy("mLock")
    private ResolveInfo findPersistentPreferredActivityLP(Intent intent,
            String resolvedType,
            int flags, List<ResolveInfo> query, boolean debug, int userId) {
        return mComputer.findPersistentPreferredActivityLP(intent,
                resolvedType,
                flags, query, debug, userId);
    }

    // findPreferredActivityBody returns two items: a "things changed" flag and a
    // ResolveInfo, which is the preferred activity itself.
    static class FindPreferredActivityBodyResult {
        boolean mChanged;
        ResolveInfo mPreferredResolveInfo;
    }

    private FindPreferredActivityBodyResult findPreferredActivityInternal(
            Intent intent, String resolvedType, int flags,
            List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered) {
        return mComputer.findPreferredActivityInternal(
            intent, resolvedType, flags,
            query, always,
            removeMatches, debug, userId, queryMayBeFiltered);
    }

    private ResolveInfo findPreferredActivityNotLocked(Intent intent, String resolvedType,
            int flags, List<ResolveInfo> query, boolean always, boolean removeMatches,
            boolean debug, int userId) {
        return findPreferredActivityNotLocked(
                intent, resolvedType, flags, query, always, removeMatches, debug, userId,
                UserHandle.getAppId(Binder.getCallingUid()) >= Process.FIRST_APPLICATION_UID);
    }

    // TODO: handle preferred activities missing while user has amnesia
    /** <b>must not hold {@link #mLock}</b> */
    private ResolveInfo findPreferredActivityNotLocked(
            Intent intent, String resolvedType, int flags, List<ResolveInfo> query, boolean always,
            boolean removeMatches, boolean debug, int userId, boolean queryMayBeFiltered) {
        if (Thread.holdsLock(mLock)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
        }
        if (!mUserManager.exists(userId)) return null;

        FindPreferredActivityBodyResult body = findPreferredActivityInternal(
                intent, resolvedType, flags, query, always,
                removeMatches, debug, userId, queryMayBeFiltered);
        if (body.mChanged) {
            if (DEBUG_PREFERRED) {
                Slog.v(TAG, "Preferred activity bookkeeping changed; writing restrictions");
            }
            synchronized (mLock) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
        if ((DEBUG_PREFERRED || debug) && body.mPreferredResolveInfo == null) {
            Slog.v(TAG, "No preferred activity to return");
        }
        return body.mPreferredResolveInfo;
    }

    /*
     * Returns if intent can be forwarded from the sourceUserId to the targetUserId
     */
    @Override
    public boolean canForwardTo(Intent intent, String resolvedType, int sourceUserId,
            int targetUserId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        List<CrossProfileIntentFilter> matches =
                getMatchingCrossProfileIntentFilters(intent, resolvedType, sourceUserId);
        if (matches != null) {
            int size = matches.size();
            for (int i = 0; i < size; i++) {
                if (matches.get(i).getTargetUserId() == targetUserId) return true;
            }
        }
        if (intent.hasWebURI()) {
            // cross-profile app linking works only towards the parent.
            final int callingUid = Binder.getCallingUid();
            final UserInfo parent = getProfileParent(sourceUserId);
            if (parent == null) {
                return false;
            }
            synchronized (mLock) {
                int flags = updateFlagsForResolve(0, parent.id, callingUid,
                        false /*includeInstantApps*/,
                        isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, parent.id,
                                resolvedType, 0));
                flags |= PackageManager.MATCH_DEFAULT_ONLY;
                CrossProfileDomainInfo xpDomainInfo = getCrossProfileDomainPreferredLpr(
                        intent, resolvedType, flags, sourceUserId, parent.id);
                return xpDomainInfo != null;
            }
        }
        return false;
    }

    private UserInfo getProfileParent(int userId) {
        return mComputer.getProfileParent(userId);
    }

    private List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent,
            String resolvedType, int userId) {
        return mComputer.getMatchingCrossProfileIntentFilters(intent,
                resolvedType, userId);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, int flags, int userId) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "queryIntentActivities");

            return new ParceledListSlice<>(
                    queryIntentActivitiesInternal(intent, resolvedType, flags, userId));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Returns the package name of the calling Uid if it's an instant app. If it isn't
     * instant, returns {@code null}.
     */
    private String getInstantAppPackageName(int callingUid) {
        return mComputer.getInstantAppPackageName(callingUid);
    }

    private @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
            String resolvedType, int flags, int userId) {
        return mComputer.queryIntentActivitiesInternal(intent,
                resolvedType, flags, userId);
    }

    private @NonNull List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,
            String resolvedType, int flags, @PrivateResolveFlags int privateResolveFlags,
            int filterCallingUid, int userId, boolean resolveForStart, boolean allowDynamicSplits) {
        return mComputer.queryIntentActivitiesInternal(intent,
                resolvedType, flags, privateResolveFlags,
                filterCallingUid, userId, resolveForStart, allowDynamicSplits);
    }

    private CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent,
            String resolvedType, int flags, int sourceUserId, int parentUserId) {
        return mComputer.getCrossProfileDomainPreferredLpr(intent,
                resolvedType, flags, sourceUserId, parentUserId);
    }

    /**
     * Filters out ephemeral activities.
     * <p>When resolving for an ephemeral app, only activities that 1) are defined in the
     * ephemeral app or 2) marked with {@code visibleToEphemeral} are returned.
     *
     * @param resolveInfos The pre-filtered list of resolved activities
     * @param ephemeralPkgName The ephemeral package name. If {@code null}, no filtering
     *          is performed.
     * @param intent
     * @return A filtered list of resolved activities.
     */
    private List<ResolveInfo> applyPostResolutionFilter(@NonNull List<ResolveInfo> resolveInfos,
            String ephemeralPkgName, boolean allowDynamicSplits, int filterCallingUid,
            boolean resolveForStart, int userId, Intent intent) {
        return mComputer.applyPostResolutionFilter(resolveInfos,
                ephemeralPkgName, allowDynamicSplits, filterCallingUid,
                resolveForStart, userId, intent);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
            Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(queryIntentActivityOptionsInternal(caller, specifics,
                specificTypes, intent, resolvedType, flags, userId));
    }

    private @NonNull List<ResolveInfo> queryIntentActivityOptionsInternal(ComponentName caller,
            Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, int flags, int userId) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        final int callingUid = Binder.getCallingUid();
        flags = updateFlagsForResolve(flags, userId, callingUid, false /*includeInstantApps*/,
                isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, userId, resolvedType,
                        flags));
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "query intent activity options");
        final String resultsAction = intent.getAction();

        final List<ResolveInfo> results = queryIntentActivitiesInternal(intent, resolvedType, flags
                | PackageManager.GET_RESOLVED_FILTER, userId);

        if (DEBUG_INTENT_MATCHING) {
            Log.v(TAG, "Query " + intent + ": " + results);
        }

        int specificsPos = 0;
        int N;

        // todo: note that the algorithm used here is O(N^2).  This
        // isn't a problem in our current environment, but if we start running
        // into situations where we have more than 5 or 10 matches then this
        // should probably be changed to something smarter...

        // First we go through and resolve each of the specific items
        // that were supplied, taking care of removing any corresponding
        // duplicate items in the generic resolve list.
        if (specifics != null) {
            for (int i=0; i<specifics.length; i++) {
                final Intent sintent = specifics[i];
                if (sintent == null) {
                    continue;
                }

                if (DEBUG_INTENT_MATCHING) {
                    Log.v(TAG, "Specific #" + i + ": " + sintent);
                }

                String action = sintent.getAction();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    action = null;
                }

                ResolveInfo ri = null;
                ActivityInfo ai = null;

                ComponentName comp = sintent.getComponent();
                if (comp == null) {
                    ri = resolveIntent(
                        sintent,
                        specificTypes != null ? specificTypes[i] : null,
                            flags, userId);
                    if (ri == null) {
                        continue;
                    }
                    if (ri == mResolveInfo) {
                        // ACK!  Must do something better with this.
                    }
                    ai = ri.activityInfo;
                    comp = new ComponentName(ai.applicationInfo.packageName,
                            ai.name);
                } else {
                    ai = getActivityInfo(comp, flags, userId);
                    if (ai == null) {
                        continue;
                    }
                }

                // Look for any generic query activities that are duplicates
                // of this specific one, and remove them from the results.
                if (DEBUG_INTENT_MATCHING) Log.v(TAG, "Specific #" + i + ": " + ai);
                N = results.size();
                int j;
                for (j=specificsPos; j<N; j++) {
                    ResolveInfo sri = results.get(j);
                    if ((sri.activityInfo.name.equals(comp.getClassName())
                            && sri.activityInfo.applicationInfo.packageName.equals(
                                    comp.getPackageName()))
                        || (action != null && sri.filter.matchAction(action))) {
                        results.remove(j);
                        if (DEBUG_INTENT_MATCHING) Log.v(
                            TAG, "Removing duplicate item from " + j
                            + " due to specific " + specificsPos);
                        if (ri == null) {
                            ri = sri;
                        }
                        j--;
                        N--;
                    }
                }

                // Add this specific item to its proper place.
                if (ri == null) {
                    ri = new ResolveInfo();
                    ri.activityInfo = ai;
                }
                results.add(specificsPos, ri);
                ri.specificIndex = i;
                specificsPos++;
            }
        }

        // Now we go through the remaining generic results and remove any
        // duplicate actions that are found here.
        N = results.size();
        for (int i=specificsPos; i<N-1; i++) {
            final ResolveInfo rii = results.get(i);
            if (rii.filter == null) {
                continue;
            }

            // Iterate over all of the actions of this result's intent
            // filter...  typically this should be just one.
            final Iterator<String> it = rii.filter.actionsIterator();
            if (it == null) {
                continue;
            }
            while (it.hasNext()) {
                final String action = it.next();
                if (resultsAction != null && resultsAction.equals(action)) {
                    // If this action was explicitly requested, then don't
                    // remove things that have it.
                    continue;
                }
                for (int j=i+1; j<N; j++) {
                    final ResolveInfo rij = results.get(j);
                    if (rij.filter != null && rij.filter.hasAction(action)) {
                        results.remove(j);
                        if (DEBUG_INTENT_MATCHING) Log.v(
                            TAG, "Removing duplicate item from " + j
                            + " due to action " + action + " at " + i);
                        j--;
                        N--;
                    }
                }
            }

            // If the caller didn't request filter information, drop it now
            // so we don't have to marshall/unmarshall it.
            if ((flags&PackageManager.GET_RESOLVED_FILTER) == 0) {
                rii.filter = null;
            }
        }

        // Filter out the caller activity if so requested.
        if (caller != null) {
            N = results.size();
            for (int i=0; i<N; i++) {
                ActivityInfo ainfo = results.get(i).activityInfo;
                if (caller.getPackageName().equals(ainfo.applicationInfo.packageName)
                        && caller.getClassName().equals(ainfo.name)) {
                    results.remove(i);
                    break;
                }
            }
        }

        // If the caller didn't request filter information,
        // drop them now so we don't have to
        // marshall/unmarshall it.
        if ((flags&PackageManager.GET_RESOLVED_FILTER) == 0) {
            N = results.size();
            for (int i=0; i<N; i++) {
                results.get(i).filter = null;
            }
        }

        if (DEBUG_INTENT_MATCHING) Log.v(TAG, "Result: " + results);
        return results;
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentReceivers(Intent intent,
            String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(queryIntentReceiversInternal(intent, resolvedType,
                flags, userId, Binder.getCallingUid()));
    }

    // In this method, we have to know the actual calling UID, but in some cases Binder's
    // call identity is removed, so the UID has to be passed in explicitly.
    private @NonNull List<ResolveInfo> queryIntentReceiversInternal(Intent intent,
            String resolvedType, int flags, int userId, int filterCallingUid) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        enforceCrossUserPermission(filterCallingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "query intent receivers");
        final String instantAppPkgName = getInstantAppPackageName(filterCallingUid);
        flags = updateFlagsForResolve(flags, userId, filterCallingUid, false /*includeInstantApps*/,
                isImplicitImageCaptureIntentAndNotSetByDpcLocked(intent, userId, resolvedType,
                        flags));
        Intent originalIntent = null;
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                originalIntent = intent;
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        List<ResolveInfo> list = Collections.emptyList();
        if (comp != null) {
            final ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                // When specifying an explicit component, we prevent the activity from being
                // used when either 1) the calling package is normal and the activity is within
                // an instant application or 2) the calling package is ephemeral and the
                // activity is not visible to instant applications.
                final boolean matchInstantApp =
                        (flags & PackageManager.MATCH_INSTANT) != 0;
                final boolean matchVisibleToInstantAppOnly =
                        (flags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
                final boolean matchExplicitlyVisibleOnly =
                        (flags & PackageManager.MATCH_EXPLICITLY_VISIBLE_ONLY) != 0;
                final boolean isCallerInstantApp =
                        instantAppPkgName != null;
                final boolean isTargetSameInstantApp =
                        comp.getPackageName().equals(instantAppPkgName);
                final boolean isTargetInstantApp =
                        (ai.applicationInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
                final boolean isTargetVisibleToInstantApp =
                        (ai.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0;
                final boolean isTargetExplicitlyVisibleToInstantApp =
                        isTargetVisibleToInstantApp
                        && (ai.flags & ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP) == 0;
                final boolean isTargetHiddenFromInstantApp =
                        !isTargetVisibleToInstantApp
                        || (matchExplicitlyVisibleOnly && !isTargetExplicitlyVisibleToInstantApp);
                final boolean blockResolution =
                        !isTargetSameInstantApp
                        && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp)
                                || (matchVisibleToInstantAppOnly && isCallerInstantApp
                                        && isTargetHiddenFromInstantApp));
                if (!blockResolution) {
                    ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    list = new ArrayList<>(1);
                    list.add(ri);
                    PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                            mInjector.getCompatibility(), mComponentResolver,
                            list, true, intent, resolvedType, filterCallingUid);
                }
            }
        } else {
            // reader
            synchronized (mLock) {
                String pkgName = intent.getPackage();
                if (pkgName == null) {
                    final List<ResolveInfo> result =
                            mComponentResolver.queryReceivers(intent, resolvedType, flags, userId);
                    if (result != null) {
                        list = result;
                    }
                }
                final AndroidPackage pkg = mPackages.get(pkgName);
                if (pkg != null) {
                    final List<ResolveInfo> result = mComponentResolver.queryReceivers(
                            intent, resolvedType, flags, pkg.getReceivers(), userId);
                    if (result != null) {
                        list = result;
                    }
                }
            }
        }

        if (originalIntent != null) {
            // We also have to ensure all components match the original intent
            PackageManagerServiceUtils.applyEnforceIntentFilterMatching(
                    mInjector.getCompatibility(), mComponentResolver,
                    list, true, originalIntent, resolvedType, filterCallingUid);
        }

        return applyPostResolutionFilter(
                list, instantAppPkgName, false, filterCallingUid, false, userId, intent);
    }

    @Override
    public ResolveInfo resolveService(Intent intent, String resolvedType, int flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return resolveServiceInternal(intent, resolvedType, flags, userId, callingUid);
    }

    private ResolveInfo resolveServiceInternal(Intent intent, String resolvedType, int flags,
            int userId, int callingUid) {
        if (!mUserManager.exists(userId)) return null;
        flags = updateFlagsForResolve(flags, userId, callingUid, false /*includeInstantApps*/,
                false /* isImplicitImageCaptureIntentAndNotSetByDpc */);
        List<ResolveInfo> query = queryIntentServicesInternal(
                intent, resolvedType, flags, userId, callingUid, false /*includeInstantApps*/);
        if (query != null) {
            if (query.size() >= 1) {
                // If there is more than one service with the same priority,
                // just arbitrarily pick the first one.
                return query.get(0);
            }
        }
        return null;
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentServices(Intent intent,
            String resolvedType, int flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(queryIntentServicesInternal(
                intent, resolvedType, flags, userId, callingUid, false /*includeInstantApps*/));
    }

    private @NonNull List<ResolveInfo> queryIntentServicesInternal(Intent intent,
            String resolvedType, int flags, int userId, int callingUid,
            boolean includeInstantApps) {
        return mComputer.queryIntentServicesInternal(intent,
                resolvedType, flags, userId, callingUid,
                includeInstantApps);
    }

    @Override
    public @NonNull ParceledListSlice<ResolveInfo> queryIntentContentProviders(Intent intent,
            String resolvedType, int flags, int userId) {
        return new ParceledListSlice<>(
                queryIntentContentProvidersInternal(intent, resolvedType, flags, userId));
    }

    private @NonNull List<ResolveInfo> queryIntentContentProvidersInternal(
            Intent intent, String resolvedType, int flags, int userId) {
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        final int callingUid = Binder.getCallingUid();
        final String instantAppPkgName = getInstantAppPackageName(callingUid);
        flags = updateFlagsForResolve(flags, userId, callingUid, false /*includeInstantApps*/,
                false /* isImplicitImageCaptureIntentAndNotSetByDpc */);
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ProviderInfo pi = getProviderInfo(comp, flags, userId);
            if (pi != null) {
                // When specifying an explicit component, we prevent the provider from being
                // used when either 1) the provider is in an instant application and the
                // caller is not the same instant application or 2) the calling package is an
                // instant application and the provider is not visible to instant applications.
                final boolean matchInstantApp =
                        (flags & PackageManager.MATCH_INSTANT) != 0;
                final boolean matchVisibleToInstantAppOnly =
                        (flags & PackageManager.MATCH_VISIBLE_TO_INSTANT_APP_ONLY) != 0;
                final boolean isCallerInstantApp =
                        instantAppPkgName != null;
                final boolean isTargetSameInstantApp =
                        comp.getPackageName().equals(instantAppPkgName);
                final boolean isTargetInstantApp =
                        (pi.applicationInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
                final boolean isTargetHiddenFromInstantApp =
                        (pi.flags & ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP) == 0;
                final boolean blockResolution =
                        !isTargetSameInstantApp
                        && ((!matchInstantApp && !isCallerInstantApp && isTargetInstantApp)
                                || (matchVisibleToInstantAppOnly && isCallerInstantApp
                                        && isTargetHiddenFromInstantApp));
                final boolean blockNormalResolution = !isTargetInstantApp && !isCallerInstantApp
                        && shouldFilterApplicationLocked(
                        getPackageSettingInternal(pi.applicationInfo.packageName,
                                Process.SYSTEM_UID), callingUid, userId);
                if (!blockResolution && !blockNormalResolution) {
                    final ResolveInfo ri = new ResolveInfo();
                    ri.providerInfo = pi;
                    list.add(ri);
                }
            }
            return list;
        }

        // reader
        synchronized (mLock) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                final List<ResolveInfo> resolveInfos = mComponentResolver.queryProviders(intent,
                        resolvedType, flags, userId);
                if (resolveInfos == null) {
                    return Collections.emptyList();
                }
                return applyPostContentProviderResolutionFilter(
                        resolveInfos, instantAppPkgName, userId, callingUid);
            }
            final AndroidPackage pkg = mPackages.get(pkgName);
            if (pkg != null) {
                final List<ResolveInfo> resolveInfos = mComponentResolver.queryProviders(intent,
                        resolvedType, flags,
                        pkg.getProviders(), userId);
                if (resolveInfos == null) {
                    return Collections.emptyList();
                }
                return applyPostContentProviderResolutionFilter(
                        resolveInfos, instantAppPkgName, userId, callingUid);
            }
            return Collections.emptyList();
        }
    }

    private List<ResolveInfo> applyPostContentProviderResolutionFilter(
            List<ResolveInfo> resolveInfos, String instantAppPkgName,
            @UserIdInt int userId, int callingUid) {
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            final ResolveInfo info = resolveInfos.get(i);

            if (instantAppPkgName == null) {
                SettingBase callingSetting =
                        mSettings.getSettingLPr(UserHandle.getAppId(callingUid));
                PackageSetting resolvedSetting =
                        getPackageSettingInternal(info.providerInfo.packageName, 0);
                if (!mAppsFilter.shouldFilterApplication(
                        callingUid, callingSetting, resolvedSetting, userId)) {
                    continue;
                }
            }

            final boolean isEphemeralApp = info.providerInfo.applicationInfo.isInstantApp();
            // allow providers that are defined in the provided package
            if (isEphemeralApp && instantAppPkgName.equals(info.providerInfo.packageName)) {
                if (info.providerInfo.splitName != null
                        && !ArrayUtils.contains(info.providerInfo.applicationInfo.splitNames,
                                info.providerInfo.splitName)) {
                    if (mInstantAppInstallerActivity == null) {
                        if (DEBUG_INSTANT) {
                            Slog.v(TAG, "No installer - not adding it to the ResolveInfo list");
                        }
                        resolveInfos.remove(i);
                        continue;
                    }
                    // requested provider is defined in a split that hasn't been installed yet.
                    // add the installer to the resolve list
                    if (DEBUG_INSTANT) {
                        Slog.v(TAG, "Adding ephemeral installer to the ResolveInfo list");
                    }
                    final ResolveInfo installerInfo = new ResolveInfo(
                            mInstantAppInstallerInfo);
                    installerInfo.auxiliaryInfo = new AuxiliaryResolveInfo(
                            null /*failureActivity*/,
                            info.providerInfo.packageName,
                            info.providerInfo.applicationInfo.longVersionCode,
                            info.providerInfo.splitName);
                    // add a non-generic filter
                    installerInfo.filter = new IntentFilter();
                    // load resources from the correct package
                    installerInfo.resolvePackageName = info.getComponentInfo().packageName;
                    resolveInfos.set(i, installerInfo);
                }
                continue;
            }
            // allow providers that have been explicitly exposed to instant applications
            if (!isEphemeralApp
                    && ((info.providerInfo.flags & ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0)) {
                continue;
            }
            resolveInfos.remove(i);
        }
        return resolveInfos;
    }

    @Override
    public ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId) {
        return mComputer.getInstalledPackages(flags, userId);
    }

    private void addPackageHoldingPermissions(ArrayList<PackageInfo> list, PackageSetting ps,
            String[] permissions, boolean[] tmp, int flags, int userId) {
        int numMatch = 0;
        for (int i=0; i<permissions.length; i++) {
            final String permission = permissions[i];
            if (checkPermission(permission, ps.getPackageName(), userId) == PERMISSION_GRANTED) {
                tmp[i] = true;
                numMatch++;
            } else {
                tmp[i] = false;
            }
        }
        if (numMatch == 0) {
            return;
        }
        final PackageInfo pi = generatePackageInfo(ps, flags, userId);

        // The above might return null in cases of uninstalled apps or install-state
        // skew across users/profiles.
        if (pi != null) {
            if ((flags&PackageManager.GET_PERMISSIONS) == 0) {
                if (numMatch == permissions.length) {
                    pi.requestedPermissions = permissions;
                } else {
                    pi.requestedPermissions = new String[numMatch];
                    numMatch = 0;
                    for (int i=0; i<permissions.length; i++) {
                        if (tmp[i]) {
                            pi.requestedPermissions[numMatch] = permissions[i];
                            numMatch++;
                        }
                    }
                }
            }
            list.add(pi);
        }
    }

    @Override
    public ParceledListSlice<PackageInfo> getPackagesHoldingPermissions(
            String[] permissions, int flags, int userId) {
        if (!mUserManager.exists(userId)) return ParceledListSlice.emptyList();
        flags = updateFlagsForPackage(flags, userId);
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "get packages holding permissions");
        final boolean listUninstalled = (flags & MATCH_KNOWN_PACKAGES) != 0;

        // writer
        synchronized (mLock) {
            ArrayList<PackageInfo> list = new ArrayList<>();
            boolean[] tmpBools = new boolean[permissions.length];
            if (listUninstalled) {
                for (PackageSetting ps : mSettings.getPackagesLocked().values()) {
                    addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags,
                            userId);
                }
            } else {
                for (AndroidPackage pkg : mPackages.values()) {
                    PackageSetting ps = getPackageSetting(pkg.getPackageName());
                    if (ps != null) {
                        addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags,
                                userId);
                    }
                }
            }

            return new ParceledListSlice<>(list);
        }
    }

    @Override
    public ParceledListSlice<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        final int callingUid = Binder.getCallingUid();
        return new ParceledListSlice<>(
                getInstalledApplicationsListInternal(flags, userId, callingUid));
    }

    private List<ApplicationInfo> getInstalledApplicationsListInternal(int flags, int userId,
            int callingUid) {
        if (getInstantAppPackageName(callingUid) != null) {
            return Collections.emptyList();
        }
        if (!mUserManager.exists(userId)) return Collections.emptyList();
        flags = updateFlagsForApplication(flags, userId);
        final boolean listUninstalled = (flags & MATCH_KNOWN_PACKAGES) != 0;

        enforceCrossUserPermission(
            callingUid,
            userId,
            false /* requireFullPermission */,
            false /* checkShell */,
            "get installed application info");

        // writer
        synchronized (mLock) {
            ArrayList<ApplicationInfo> list;
            if (listUninstalled) {
                list = new ArrayList<>(mSettings.getPackagesLocked().size());
                for (PackageSetting ps : mSettings.getPackagesLocked().values()) {
                    ApplicationInfo ai;
                    int effectiveFlags = flags;
                    if (ps.isSystem()) {
                        effectiveFlags |= PackageManager.MATCH_ANY_USER;
                    }
                    if (ps.getPkg() != null) {
                        if (filterSharedLibPackageLPr(ps, callingUid, userId, flags)) {
                            continue;
                        }
                        if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                            continue;
                        }
                        ai = PackageInfoUtils.generateApplicationInfo(ps.getPkg(), effectiveFlags,
                                ps.readUserState(userId), userId, ps);
                        if (ai != null) {
                            ai.packageName = resolveExternalPackageNameLPr(ps.getPkg());
                        }
                    } else {
                        // Shared lib filtering done in generateApplicationInfoFromSettingsLPw
                        // and already converts to externally visible package name
                        ai = generateApplicationInfoFromSettingsLPw(ps.getPackageName(),
                                effectiveFlags, callingUid, userId);
                    }
                    if (ai != null) {
                        list.add(ai);
                    }
                }
            } else {
                list = new ArrayList<>(mPackages.size());
                for (AndroidPackage p : mPackages.values()) {
                    final PackageSetting ps = getPackageSetting(p.getPackageName());
                    if (ps != null) {
                        if (filterSharedLibPackageLPr(ps, Binder.getCallingUid(), userId, flags)) {
                            continue;
                        }
                        if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                            continue;
                        }
                        ApplicationInfo ai = PackageInfoUtils.generateApplicationInfo(p, flags,
                                ps.readUserState(userId), userId, ps);
                        if (ai != null) {
                            ai.packageName = resolveExternalPackageNameLPr(p);
                            list.add(ai);
                        }
                    }
                }
            }

            return list;
        }
    }

    @Override
    public ParceledListSlice<InstantAppInfo> getInstantApps(int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return null;
        }
        if (!canViewInstantApps(Binder.getCallingUid(), userId)) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_INSTANT_APPS,
                    "getEphemeralApplications");
        }
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getEphemeralApplications");
        synchronized (mLock) {
            List<InstantAppInfo> instantApps = mInstantAppRegistry
                    .getInstantAppsLPr(userId);
            if (instantApps != null) {
                return new ParceledListSlice<>(instantApps);
            }
        }
        return null;
    }

    @Override
    public boolean isInstantApp(String packageName, int userId) {
        return mComputer.isInstantApp(packageName, userId);
    }

    private boolean isInstantAppInternal(String packageName, @UserIdInt int userId,
            int callingUid) {
        return mComputer.isInstantAppInternal(packageName, userId,
                callingUid);
    }

    @Override
    public byte[] getInstantAppCookie(String packageName, int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return null;
        }

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getInstantAppCookie");
        if (!isCallerSameApp(packageName, Binder.getCallingUid())) {
            return null;
        }
        synchronized (mLock) {
            return mInstantAppRegistry.getInstantAppCookieLPw(
                    packageName, userId);
        }
    }

    @Override
    public boolean setInstantAppCookie(String packageName, byte[] cookie, int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return true;
        }

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                true /* checkShell */, "setInstantAppCookie");
        if (!isCallerSameApp(packageName, Binder.getCallingUid())) {
            return false;
        }
        synchronized (mLock) {
            return mInstantAppRegistry.setInstantAppCookieLPw(
                    packageName, cookie, userId);
        }
    }

    @Override
    public Bitmap getInstantAppIcon(String packageName, int userId) {
        if (HIDE_EPHEMERAL_APIS) {
            return null;
        }

        if (!canViewInstantApps(Binder.getCallingUid(), userId)) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.ACCESS_INSTANT_APPS,
                    "getInstantAppIcon");
        }
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getInstantAppIcon");

        synchronized (mLock) {
            return mInstantAppRegistry.getInstantAppIconLPw(
                    packageName, userId);
        }
    }

    private boolean isCallerSameApp(String packageName, int uid) {
        return mComputer.isCallerSameApp(packageName, uid);
    }

    @Override
    public @NonNull ParceledListSlice<ApplicationInfo> getPersistentApplications(int flags) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(getPersistentApplicationsInternal(flags));
    }

    private @NonNull List<ApplicationInfo> getPersistentApplicationsInternal(int flags) {
        final ArrayList<ApplicationInfo> finalList = new ArrayList<>();

        // reader
        synchronized (mLock) {
            final int numPackages = mPackages.size();
            final int userId = UserHandle.getCallingUserId();
            for (int index = 0; index < numPackages; index++) {
                final AndroidPackage p = mPackages.valueAt(index);

                final boolean matchesUnaware = ((flags & MATCH_DIRECT_BOOT_UNAWARE) != 0)
                        && !p.isDirectBootAware();
                final boolean matchesAware = ((flags & MATCH_DIRECT_BOOT_AWARE) != 0)
                        && p.isDirectBootAware();

                if (p.isPersistent()
                        && (!mSafeMode || p.isSystem())
                        && (matchesUnaware || matchesAware)) {
                    PackageSetting ps = mSettings.getPackageLPr(p.getPackageName());
                    if (ps != null) {
                        ApplicationInfo ai = PackageInfoUtils.generateApplicationInfo(p, flags,
                                ps.readUserState(userId), userId, ps);
                        if (ai != null) {
                            finalList.add(ai);
                        }
                    }
                }
            }
        }

        return finalList;
    }

    @Override
    public ProviderInfo resolveContentProvider(String name, int flags, int userId) {
        return resolveContentProviderInternal(name, flags, userId, Binder.getCallingUid());
    }

    private ProviderInfo resolveContentProviderInternal(String name, int flags, int userId,
            int callingUid) {
        if (!mUserManager.exists(userId)) return null;
        flags = updateFlagsForComponent(flags, userId);
        final ProviderInfo providerInfo = mComponentResolver.queryProvider(name, flags, userId);
        boolean checkedGrants = false;
        if (providerInfo != null) {
            // Looking for cross-user grants before enforcing the typical cross-users permissions
            if (userId != UserHandle.getUserId(callingUid)) {
                final UriGrantsManagerInternal ugmInternal =
                        mInjector.getLocalService(UriGrantsManagerInternal.class);
                checkedGrants =
                        ugmInternal.checkAuthorityGrants(callingUid, providerInfo, userId, true);
            }
        }
        if (!checkedGrants) {
            enforceCrossUserPermission(callingUid, userId, false, false, "resolveContentProvider");
        }
        if (providerInfo == null) {
            return null;
        }
        synchronized (mLock) {
            if (!mSettings.isEnabledAndMatchLPr(providerInfo, flags, userId)) {
                return null;
            }
            final PackageSetting ps = mSettings.getPackageLPr(providerInfo.packageName);
            final ComponentName component =
                    new ComponentName(providerInfo.packageName, providerInfo.name);
            if (shouldFilterApplicationLocked(ps, callingUid, component, TYPE_PROVIDER, userId)) {
                return null;
            }
            return providerInfo;
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        final List<String> names = new ArrayList<>();
        final List<ProviderInfo> infos = new ArrayList<>();
        final int callingUserId = UserHandle.getCallingUserId();
        mComponentResolver.querySyncProviders(
                names, infos, mSafeMode, callingUserId);
        synchronized (mLock) {
            for (int i = infos.size() - 1; i >= 0; i--) {
                final ProviderInfo providerInfo = infos.get(i);
                final PackageSetting ps = mSettings.getPackageLPr(providerInfo.packageName);
                final ComponentName component =
                        new ComponentName(providerInfo.packageName, providerInfo.name);
                if (!shouldFilterApplicationLocked(ps, Binder.getCallingUid(), component,
                        TYPE_PROVIDER, callingUserId)) {
                    continue;
                }
                infos.remove(i);
                names.remove(i);
            }
        }
        if (!names.isEmpty()) {
            outNames.addAll(names);
        }
        if (!infos.isEmpty()) {
            outInfo.addAll(infos);
        }
    }

    @Override
    public @NonNull ParceledListSlice<ProviderInfo> queryContentProviders(String processName,
            int uid, int flags, String metaDataKey) {
        final int callingUid = Binder.getCallingUid();
        final int userId = processName != null ? UserHandle.getUserId(uid)
                : UserHandle.getCallingUserId();
        if (!mUserManager.exists(userId)) return ParceledListSlice.emptyList();
        flags = updateFlagsForComponent(flags, userId);
        ArrayList<ProviderInfo> finalList = null;
        final List<ProviderInfo> matchList =
                mComponentResolver.queryProviders(processName, metaDataKey, uid, flags, userId);
        final int listSize = (matchList == null ? 0 : matchList.size());
        synchronized (mLock) {
            for (int i = 0; i < listSize; i++) {
                final ProviderInfo providerInfo = matchList.get(i);
                if (!mSettings.isEnabledAndMatchLPr(providerInfo, flags, userId)) {
                    continue;
                }
                final PackageSetting ps = mSettings.getPackageLPr(providerInfo.packageName);
                final ComponentName component =
                        new ComponentName(providerInfo.packageName, providerInfo.name);
                if (shouldFilterApplicationLocked(
                        ps, callingUid, component, TYPE_PROVIDER, userId)) {
                    continue;
                }
                if (finalList == null) {
                    finalList = new ArrayList<>(listSize - i);
                }
                finalList.add(providerInfo);
            }
        }

        if (finalList != null) {
            finalList.sort(sProviderInitOrderSorter);
            return new ParceledListSlice<>(finalList);
        }

        return ParceledListSlice.emptyList();
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName component, int flags) {
        // reader
        synchronized (mLock) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            String packageName = component.getPackageName();
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            AndroidPackage pkg = mPackages.get(packageName);
            if (ps == null || pkg == null) return null;
            if (shouldFilterApplicationLocked(
                    ps, callingUid, component, TYPE_UNKNOWN, callingUserId)) {
                return null;
            }
            final ParsedInstrumentation i = mInstrumentation.get(component);
            return PackageInfoUtils.generateInstrumentationInfo(i, pkg, flags, callingUserId, ps);
        }
    }

    @Override
    public @NonNull ParceledListSlice<InstrumentationInfo> queryInstrumentation(
            String targetPackage, int flags) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(targetPackage);
            if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                return ParceledListSlice.emptyList();
            }
        }
        return new ParceledListSlice<>(queryInstrumentationInternal(targetPackage, flags,
                callingUserId));
    }

    private @NonNull List<InstrumentationInfo> queryInstrumentationInternal(String targetPackage,
            int flags, int userId) {
        ArrayList<InstrumentationInfo> finalList = new ArrayList<>();

        // reader
        synchronized (mLock) {
            final int numInstrumentations = mInstrumentation.size();
            for (int index = 0; index < numInstrumentations; index++) {
                final ParsedInstrumentation p = mInstrumentation.valueAt(index);
                if (targetPackage == null
                        || targetPackage.equals(p.getTargetPackage())) {
                    String packageName = p.getPackageName();
                    AndroidPackage pkg = mPackages.get(packageName);
                    PackageSetting pkgSetting = getPackageSetting(packageName);
                    if (pkg != null) {
                        InstrumentationInfo ii = PackageInfoUtils.generateInstrumentationInfo(p,
                                pkg, flags, userId, pkgSetting);
                        if (ii != null) {
                            finalList.add(ii);
                        }
                    }
                }
            }
        }

        return finalList;
    }

    public static void reportSettingsProblem(int priority, String msg) {
        logCriticalInfo(priority, msg);
    }

    // TODO:(b/135203078): Move to parsing
    static void renameStaticSharedLibraryPackage(ParsedPackage parsedPackage) {
        // Derive the new package synthetic package name
        parsedPackage.setPackageName(toStaticSharedLibraryPackageName(
                parsedPackage.getPackageName(), parsedPackage.getStaticSharedLibVersion()));
    }

    private static String toStaticSharedLibraryPackageName(
            String packageName, long libraryVersion) {
        return packageName + STATIC_SHARED_LIB_DELIMITER + libraryVersion;
    }

    /**
     * Enforces that only the system UID or root's UID can call a method exposed
     * via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static void enforceSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.ROOT_UID) {
            throw new SecurityException(message);
        }
    }

    /**
     * Enforces that only the system UID or root's UID or shell's UID can call
     * a method exposed via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or shell
     */
    private static void enforceSystemOrRootOrShell(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.ROOT_UID && uid != Process.SHELL_UID) {
            throw new SecurityException(message);
        }
    }

    /**
     * Enforces the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the {@code userId} is not for the caller.
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    void enforceCrossUserPermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        mComputer.enforceCrossUserPermission(callingUid, userId,
                requireFullPermission, checkShell, message);
    }

    /**
     * Checks if the request is from the system or an app that has the appropriate cross-user
     * permissions defined as follows:
     * <ul>
     * <li>INTERACT_ACROSS_USERS_FULL if {@code requireFullPermission} is true.</li>
     * <li>INTERACT_ACROSS_USERS if the given {@code userId} is in a different profile group
     * to the caller.</li>
     * <li>Otherwise, INTERACT_ACROSS_PROFILES if the given {@code userId} is in the same profile
     * group as the caller.</li>
     * </ul>
     *
     * @param checkShell whether to prevent shell from access if there's a debugging restriction
     * @param message the message to log on security exception
     */
    private void enforceCrossUserOrProfilePermission(int callingUid, @UserIdInt int userId,
            boolean requireFullPermission, boolean checkShell, String message) {
        mComputer.enforceCrossUserOrProfilePermission(callingUid, userId,
                requireFullPermission, checkShell, message);
    }

    @Override
    public void performFstrimIfNeeded() {
        enforceSystemOrRoot("Only the system can request fstrim");

        // Before everything else, see whether we need to fstrim.
        try {
            IStorageManager sm = PackageHelper.getStorageManager();
            if (sm != null) {
                boolean doTrim = false;
                final long interval = android.provider.Settings.Global.getLong(
                        mContext.getContentResolver(),
                        android.provider.Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                        DEFAULT_MANDATORY_FSTRIM_INTERVAL);
                if (interval > 0) {
                    final long timeSinceLast = System.currentTimeMillis() - sm.lastMaintenance();
                    if (timeSinceLast > interval) {
                        doTrim = true;
                        Slog.w(TAG, "No disk maintenance in " + timeSinceLast
                                + "; running immediately");
                    }
                }
                if (doTrim) {
                    final boolean dexOptDialogShown;
                    synchronized (mLock) {
                        dexOptDialogShown = mDexOptDialogShown;
                    }
                    if (!isFirstBoot() && dexOptDialogShown) {
                        try {
                            ActivityManager.getService().showBootMessage(
                                    mContext.getResources().getString(
                                            R.string.android_upgrading_fstrim), true);
                        } catch (RemoteException e) {
                        }
                    }
                    sm.runMaintenance();
                }
            } else {
                Slog.e(TAG, "storageManager service unavailable!");
            }
        } catch (RemoteException e) {
            // Can't happen; StorageManagerService is local
        }
    }

    @Override
    public void updatePackagesIfNeeded() {
        enforceSystemOrRoot("Only the system can request package update");

        // We need to re-extract after an OTA.
        boolean causeUpgrade = isDeviceUpgrading();

        // First boot or factory reset.
        // Note: we also handle devices that are upgrading to N right now as if it is their
        //       first boot, as they do not have profile data.
        boolean causeFirstBoot = isFirstBoot() || mIsPreNUpgrade;

        if (!causeUpgrade && !causeFirstBoot) {
            return;
        }

        List<PackageSetting> pkgSettings;
        synchronized (mLock) {
            pkgSettings = PackageManagerServiceUtils.getPackagesForDexopt(
                    mSettings.getPackagesLocked().values(), this);
        }

        List<AndroidPackage> pkgs = new ArrayList<>(pkgSettings.size());
        for (int index = 0; index < pkgSettings.size(); index++) {
            pkgs.add(pkgSettings.get(index).getPkg());
        }

        final long startTime = System.nanoTime();
        final int[] stats = performDexOptUpgrade(pkgs, mIsPreNUpgrade /* showDialog */,
                    causeFirstBoot ? REASON_FIRST_BOOT : REASON_BOOT_AFTER_OTA,
                    false /* bootComplete */);

        final int elapsedTimeSeconds =
                (int) TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);

        MetricsLogger.histogram(mContext, "opt_dialog_num_dexopted", stats[0]);
        MetricsLogger.histogram(mContext, "opt_dialog_num_skipped", stats[1]);
        MetricsLogger.histogram(mContext, "opt_dialog_num_failed", stats[2]);
        MetricsLogger.histogram(mContext, "opt_dialog_num_total", getOptimizablePackages().size());
        MetricsLogger.histogram(mContext, "opt_dialog_time_s", elapsedTimeSeconds);
    }

    /*
     * Return the prebuilt profile path given a package base code path.
     */
    private static String getPrebuildProfilePath(AndroidPackage pkg) {
        return pkg.getBaseApkPath() + ".prof";
    }

    /**
     * Performs dexopt on the set of packages in {@code packages} and returns an int array
     * containing statistics about the invocation. The array consists of three elements,
     * which are (in order) {@code numberOfPackagesOptimized}, {@code numberOfPackagesSkipped}
     * and {@code numberOfPackagesFailed}.
     */
    private int[] performDexOptUpgrade(List<AndroidPackage> pkgs, boolean showDialog,
            final int compilationReason, boolean bootComplete) {

        int numberOfPackagesVisited = 0;
        int numberOfPackagesOptimized = 0;
        int numberOfPackagesSkipped = 0;
        int numberOfPackagesFailed = 0;
        final int numberOfPackagesToDexopt = pkgs.size();

        for (AndroidPackage pkg : pkgs) {
            numberOfPackagesVisited++;

            boolean useProfileForDexopt = false;

            if ((isFirstBoot() || isDeviceUpgrading()) && pkg.isSystem()) {
                // Copy over initial preopt profiles since we won't get any JIT samples for methods
                // that are already compiled.
                File profileFile = new File(getPrebuildProfilePath(pkg));
                // Copy profile if it exists.
                if (profileFile.exists()) {
                    try {
                        // We could also do this lazily before calling dexopt in
                        // PackageDexOptimizer to prevent this happening on first boot. The issue
                        // is that we don't have a good way to say "do this only once".
                        if (!mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                pkg.getUid(), pkg.getPackageName(),
                                ArtManager.getProfileName(null))) {
                            Log.e(TAG, "Installer failed to copy system profile!");
                        } else {
                            // Disabled as this causes speed-profile compilation during first boot
                            // even if things are already compiled.
                            // useProfileForDexopt = true;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to copy profile " + profileFile.getAbsolutePath() + " ",
                                e);
                    }
                } else {
                    PackageSetting disabledPs = mSettings.getDisabledSystemPkgLPr(
                            pkg.getPackageName());
                    // Handle compressed APKs in this path. Only do this for stubs with profiles to
                    // minimize the number off apps being speed-profile compiled during first boot.
                    // The other paths will not change the filter.
                    if (disabledPs != null && disabledPs.getPkg().isStub()) {
                        // The package is the stub one, remove the stub suffix to get the normal
                        // package and APK names.
                        String systemProfilePath = getPrebuildProfilePath(disabledPs.getPkg())
                                .replace(STUB_SUFFIX, "");
                        profileFile = new File(systemProfilePath);
                        // If we have a profile for a compressed APK, copy it to the reference
                        // location.
                        // Note that copying the profile here will cause it to override the
                        // reference profile every OTA even though the existing reference profile
                        // may have more data. We can't copy during decompression since the
                        // directories are not set up at that point.
                        if (profileFile.exists()) {
                            try {
                                // We could also do this lazily before calling dexopt in
                                // PackageDexOptimizer to prevent this happening on first boot. The
                                // issue is that we don't have a good way to say "do this only
                                // once".
                                if (!mInstaller.copySystemProfile(profileFile.getAbsolutePath(),
                                        pkg.getUid(), pkg.getPackageName(),
                                        ArtManager.getProfileName(null))) {
                                    Log.e(TAG, "Failed to copy system profile for stub package!");
                                } else {
                                    useProfileForDexopt = true;
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to copy profile " +
                                        profileFile.getAbsolutePath() + " ", e);
                            }
                        }
                    }
                }
            }

            if (!PackageDexOptimizer.canOptimizePackage(pkg)) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Skipping update of non-optimizable app " + pkg.getPackageName());
                }
                numberOfPackagesSkipped++;
                continue;
            }

            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Updating app " + numberOfPackagesVisited + " of " +
                        numberOfPackagesToDexopt + ": " + pkg.getPackageName());
            }

            if (showDialog) {
                try {
                    ActivityManager.getService().showBootMessage(
                            mContext.getResources().getString(R.string.android_upgrading_apk,
                                    numberOfPackagesVisited, numberOfPackagesToDexopt), true);
                } catch (RemoteException e) {
                }
                synchronized (mLock) {
                    mDexOptDialogShown = true;
                }
            }

            int pkgCompilationReason = compilationReason;
            if (useProfileForDexopt) {
                // Use background dexopt mode to try and use the profile. Note that this does not
                // guarantee usage of the profile.
                pkgCompilationReason = PackageManagerService.REASON_BACKGROUND_DEXOPT;
            }

            if (SystemProperties.getBoolean(PRECOMPILE_LAYOUTS, false)) {
                mArtManagerService.compileLayouts(pkg);
            }

            // checkProfiles is false to avoid merging profiles during boot which
            // might interfere with background compilation (b/28612421).
            // Unfortunately this will also means that "pm.dexopt.boot=speed-profile" will
            // behave differently than "pm.dexopt.bg-dexopt=speed-profile" but that's a
            // trade-off worth doing to save boot time work.
            int dexoptFlags = bootComplete ? DexoptOptions.DEXOPT_BOOT_COMPLETE : 0;
            if (compilationReason == REASON_FIRST_BOOT) {
                // TODO: This doesn't cover the upgrade case, we should check for this too.
                dexoptFlags |= DexoptOptions.DEXOPT_INSTALL_WITH_DEX_METADATA_FILE;
            }
            int primaryDexOptStaus = performDexOptTraced(new DexoptOptions(
                    pkg.getPackageName(),
                    pkgCompilationReason,
                    dexoptFlags));

            switch (primaryDexOptStaus) {
                case PackageDexOptimizer.DEX_OPT_PERFORMED:
                    numberOfPackagesOptimized++;
                    break;
                case PackageDexOptimizer.DEX_OPT_SKIPPED:
                    numberOfPackagesSkipped++;
                    break;
                case PackageDexOptimizer.DEX_OPT_CANCELLED:
                    // ignore this case
                    break;
                case PackageDexOptimizer.DEX_OPT_FAILED:
                    numberOfPackagesFailed++;
                    break;
                default:
                    Log.e(TAG, "Unexpected dexopt return code " + primaryDexOptStaus);
                    break;
            }
        }

        return new int[] { numberOfPackagesOptimized, numberOfPackagesSkipped,
                numberOfPackagesFailed };
    }

    @Override
    public void notifyPackageUse(String packageName, int reason) {
        synchronized (mLock) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            if (getInstantAppPackageName(callingUid) != null) {
                if (!isCallerSameApp(packageName, callingUid)) {
                    return;
                }
            } else {
                if (isInstantApp(packageName, callingUserId)) {
                    return;
                }
            }
            notifyPackageUseLocked(packageName, reason);
        }
    }

    @GuardedBy("mLock")
    private void notifyPackageUseLocked(String packageName, int reason) {
        final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
        if (pkgSetting == null) {
            return;
        }
        pkgSetting.getPkgState().setLastPackageUsageTimeInMills(reason, System.currentTimeMillis());
    }

    @Override
    public void notifyDexLoad(String loadingPackageName, Map<String, String> classLoaderContextMap,
            String loaderIsa) {
        int callingUid = Binder.getCallingUid();
        if (PLATFORM_PACKAGE_NAME.equals(loadingPackageName) && callingUid != Process.SYSTEM_UID) {
            Slog.w(TAG, "Non System Server process reporting dex loads as system server. uid="
                    + callingUid);
            // Do not record dex loads from processes pretending to be system server.
            // Only the system server should be assigned the package "android", so reject calls
            // that don't satisfy the constraint.
            //
            // notifyDexLoad is a PM API callable from the app process. So in theory, apps could
            // craft calls to this API and pretend to be system server. Doing so poses no particular
            // danger for dex load reporting or later dexopt, however it is a sensible check to do
            // in order to verify the expectations.
            return;
        }

        int userId = UserHandle.getCallingUserId();
        ApplicationInfo ai = getApplicationInfo(loadingPackageName, /*flags*/ 0, userId);
        if (ai == null) {
            Slog.w(TAG, "Loading a package that does not exist for the calling user. package="
                + loadingPackageName + ", user=" + userId);
            return;
        }
        mDexManager.notifyDexLoad(ai, classLoaderContextMap, loaderIsa, userId,
                Process.isIsolated(callingUid));
    }

    @Override
    public void registerDexModule(String packageName, String dexModulePath, boolean isSharedModule,
            IDexModuleRegisterCallback callback) {
        int userId = UserHandle.getCallingUserId();
        ApplicationInfo ai = getApplicationInfo(packageName, /*flags*/ 0, userId);
        DexManager.RegisterDexModuleResult result;
        if (ai == null) {
            Slog.w(TAG, "Registering a dex module for a package that does not exist for the" +
                     " calling user. package=" + packageName + ", user=" + userId);
            result = new DexManager.RegisterDexModuleResult(false, "Package not installed");
        } else {
            result = mDexManager.registerDexModule(ai, dexModulePath, isSharedModule, userId);
        }

        if (callback != null) {
            mHandler.post(() -> {
                try {
                    callback.onDexModuleRegistered(dexModulePath, result.success, result.message);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to callback after module registration " + dexModulePath, e);
                }
            });
        }
    }

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter.
     *
     * Note: exposed only for the shell command to allow moving packages explicitly to a
     *       definite state.
     */
    @Override
    public boolean performDexOptMode(String packageName,
            boolean checkProfiles, String targetCompilerFilter, boolean force,
            boolean bootComplete, String splitName) {
        enforceSystemOrRootOrShell("performDexOptMode");

        int flags = (checkProfiles ? DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES : 0) |
                (force ? DexoptOptions.DEXOPT_FORCE : 0) |
                (bootComplete ? DexoptOptions.DEXOPT_BOOT_COMPLETE : 0);
        return performDexOpt(new DexoptOptions(packageName, REASON_CMDLINE,
                targetCompilerFilter, splitName, flags));
    }

    /**
     * Ask the package manager to perform a dex-opt with the given compiler filter on the
     * secondary dex files belonging to the given package.
     *
     * Note: exposed only for the shell command to allow moving packages explicitly to a
     *       definite state.
     */
    @Override
    public boolean performDexOptSecondary(String packageName, String compilerFilter,
            boolean force) {
        int flags = DexoptOptions.DEXOPT_ONLY_SECONDARY_DEX |
                DexoptOptions.DEXOPT_CHECK_FOR_PROFILES_UPDATES |
                DexoptOptions.DEXOPT_BOOT_COMPLETE |
                (force ? DexoptOptions.DEXOPT_FORCE : 0);
        return performDexOpt(new DexoptOptions(packageName, compilerFilter, flags));
    }

    /*package*/ boolean performDexOpt(DexoptOptions options) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        } else if (isInstantApp(options.getPackageName(), UserHandle.getCallingUserId())) {
            return false;
        }

        if (options.isDexoptOnlySecondaryDex()) {
            return mDexManager.dexoptSecondaryDex(options);
        } else {
            int dexoptStatus = performDexOptWithStatus(options);
            return dexoptStatus != PackageDexOptimizer.DEX_OPT_FAILED;
        }
    }

    /*package*/ void controlDexOptBlocking(boolean block) {
        mPackageDexOptimizer.controlDexOptBlocking(block);
    }

    /**
     * Perform dexopt on the given package and return one of following result:
     *  {@link PackageDexOptimizer#DEX_OPT_SKIPPED}
     *  {@link PackageDexOptimizer#DEX_OPT_PERFORMED}
     *  {@link PackageDexOptimizer#DEX_OPT_CANCELLED}
     *  {@link PackageDexOptimizer#DEX_OPT_FAILED}
     */
    @PackageDexOptimizer.DexOptResult
    /* package */ int performDexOptWithStatus(DexoptOptions options) {
        return performDexOptTraced(options);
    }

    private int performDexOptTraced(DexoptOptions options) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");
        try {
            return performDexOptInternal(options);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    // Run dexopt on a given package. Returns true if dexopt did not fail, i.e.
    // if the package can now be considered up to date for the given filter.
    private int performDexOptInternal(DexoptOptions options) {
        AndroidPackage p;
        PackageSetting pkgSetting;
        synchronized (mLock) {
            p = mPackages.get(options.getPackageName());
            pkgSetting = mSettings.getPackageLPr(options.getPackageName());
            if (p == null || pkgSetting == null) {
                // Package could not be found. Report failure.
                return PackageDexOptimizer.DEX_OPT_FAILED;
            }
            mPackageUsage.maybeWriteAsync(mSettings.getPackagesLocked());
            mCompilerStats.maybeWriteAsync();
        }
        final long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mInstallLock) {
                return performDexOptInternalWithDependenciesLI(p, pkgSetting, options);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public ArraySet<String> getOptimizablePackages() {
        ArraySet<String> pkgs = new ArraySet<>();
        synchronized (mLock) {
            for (AndroidPackage p : mPackages.values()) {
                if (PackageDexOptimizer.canOptimizePackage(p)) {
                    pkgs.add(p.getPackageName());
                }
            }
        }
        if (AppHibernationService.isAppHibernationEnabled()) {
            AppHibernationManagerInternal appHibernationManager =
                    mInjector.getLocalService(AppHibernationManagerInternal.class);
            pkgs.removeIf(pkgName -> appHibernationManager.isHibernatingGlobally(pkgName));
        }
        return pkgs;
    }

    private int performDexOptInternalWithDependenciesLI(AndroidPackage p,
            @NonNull PackageSetting pkgSetting, DexoptOptions options) {
        // System server gets a special path.
        if (PLATFORM_PACKAGE_NAME.equals(p.getPackageName())) {
            return mDexManager.dexoptSystemServer(options);
        }

        // Select the dex optimizer based on the force parameter.
        // Note: The force option is rarely used (cmdline input for testing, mostly), so it's OK to
        //       allocate an object here.
        PackageDexOptimizer pdo = options.isForce()
                ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(mPackageDexOptimizer)
                : mPackageDexOptimizer;

        // Dexopt all dependencies first. Note: we ignore the return value and march on
        // on errors.
        // Note that we are going to call performDexOpt on those libraries as many times as
        // they are referenced in packages. When we do a batch of performDexOpt (for example
        // at boot, or background job), the passed 'targetCompilerFilter' stays the same,
        // and the first package that uses the library will dexopt it. The
        // others will see that the compiled code for the library is up to date.
        Collection<SharedLibraryInfo> deps = findSharedLibraries(pkgSetting);
        final String[] instructionSets = getAppDexInstructionSets(
                AndroidPackageUtils.getPrimaryCpuAbi(p, pkgSetting),
                AndroidPackageUtils.getSecondaryCpuAbi(p, pkgSetting));
        if (!deps.isEmpty()) {
            DexoptOptions libraryOptions = new DexoptOptions(options.getPackageName(),
                    options.getCompilationReason(), options.getCompilerFilter(),
                    options.getSplitName(),
                    options.getFlags() | DexoptOptions.DEXOPT_AS_SHARED_LIBRARY);
            for (SharedLibraryInfo info : deps) {
                AndroidPackage depPackage = null;
                PackageSetting depPackageSetting = null;
                synchronized (mLock) {
                    depPackage = mPackages.get(info.getPackageName());
                    depPackageSetting = mSettings.getPackageLPr(info.getPackageName());
                }
                if (depPackage != null && depPackageSetting != null) {
                    // TODO: Analyze and investigate if we (should) profile libraries.
                    pdo.performDexOpt(depPackage, depPackageSetting, instructionSets,
                            getOrCreateCompilerPackageStats(depPackage),
                            mDexManager.getPackageUseInfoOrDefault(depPackage.getPackageName()),
                            libraryOptions);
                } else {
                    // TODO(ngeoffray): Support dexopting system shared libraries.
                }
            }
        }

        return pdo.performDexOpt(p, pkgSetting, instructionSets,
                getOrCreateCompilerPackageStats(p),
                mDexManager.getPackageUseInfoOrDefault(p.getPackageName()), options);
    }

    /**
     * Reconcile the information we have about the secondary dex files belonging to
     * {@code packageName} and the actual dex files. For all dex files that were
     * deleted, update the internal records and delete the generated oat files.
     */
    @Override
    public void reconcileSecondaryDexFiles(String packageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        } else if (isInstantApp(packageName, UserHandle.getCallingUserId())) {
            return;
        }
        mDexManager.reconcileSecondaryDexFiles(packageName);
    }

    /*package*/ DexManager getDexManager() {
        return mDexManager;
    }

    /**
     * Execute the background dexopt job immediately.
     */
    @Override
    public boolean runBackgroundDexoptJob(@Nullable List<String> packageNames) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return false;
        }
        enforceSystemOrRootOrShell("runBackgroundDexoptJob");
        final long identity = Binder.clearCallingIdentity();
        try {
            return BackgroundDexOptService.runIdleOptimizationsNow(this, mContext, packageNames);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static List<SharedLibraryInfo> findSharedLibraries(PackageSetting pkgSetting) {
        if (!pkgSetting.getPkgState().getUsesLibraryInfos().isEmpty()) {
            ArrayList<SharedLibraryInfo> retValue = new ArrayList<>();
            Set<String> collectedNames = new HashSet<>();
            for (SharedLibraryInfo info : pkgSetting.getPkgState().getUsesLibraryInfos()) {
                findSharedLibrariesRecursive(info, retValue, collectedNames);
            }
            return retValue;
        } else {
            return Collections.emptyList();
        }
    }

    private static void findSharedLibrariesRecursive(SharedLibraryInfo info,
            ArrayList<SharedLibraryInfo> collected, Set<String> collectedNames) {
        if (!collectedNames.contains(info.getName())) {
            collectedNames.add(info.getName());
            collected.add(info);

            if (info.getDependencies() != null) {
                for (SharedLibraryInfo dep : info.getDependencies()) {
                    findSharedLibrariesRecursive(dep, collected, collectedNames);
                }
            }
        }
    }

    List<PackageSetting> findSharedNonSystemLibraries(PackageSetting pkgSetting) {
        List<SharedLibraryInfo> deps = findSharedLibraries(pkgSetting);
        if (!deps.isEmpty()) {
            List<PackageSetting> retValue = new ArrayList<>();
            synchronized (mLock) {
                for (SharedLibraryInfo info : deps) {
                    PackageSetting depPackageSetting =
                            mSettings.getPackageLPr(info.getPackageName());
                    if (depPackageSetting != null && depPackageSetting.getPkg() != null) {
                        retValue.add(depPackageSetting);
                    }
                }
            }
            return retValue;
        } else {
            return Collections.emptyList();
        }
    }

    @Nullable
    private SharedLibraryInfo getSharedLibraryInfoLPr(String name, long version) {
        return mComputer.getSharedLibraryInfoLPr(name, version);
    }

    @Nullable
    public static SharedLibraryInfo getSharedLibraryInfo(String name, long version,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries) {
        if (newLibraries != null) {
            final WatchedLongSparseArray<SharedLibraryInfo> versionedLib = newLibraries.get(name);
            SharedLibraryInfo info = null;
            if (versionedLib != null) {
                info = versionedLib.get(version);
            }
            if (info != null) {
                return info;
            }
        }
        final WatchedLongSparseArray<SharedLibraryInfo> versionedLib = existingLibraries.get(name);
        if (versionedLib == null) {
            return null;
        }
        return versionedLib.get(version);
    }

    SharedLibraryInfo getLatestSharedLibraVersionLPr(AndroidPackage pkg) {
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(
                pkg.getStaticSharedLibName());
        if (versionedLib == null) {
            return null;
        }
        long previousLibVersion = -1;
        final int versionCount = versionedLib.size();
        for (int i = 0; i < versionCount; i++) {
            final long libVersion = versionedLib.keyAt(i);
            if (libVersion < pkg.getStaticSharedLibVersion()) {
                previousLibVersion = Math.max(previousLibVersion, libVersion);
            }
        }
        if (previousLibVersion >= 0) {
            return versionedLib.get(previousLibVersion);
        }
        return null;
    }

    @Nullable
    PackageSetting getSharedLibLatestVersionSetting(@NonNull ScanResult scanResult) {
        PackageSetting sharedLibPackage = null;
        synchronized (mLock) {
            final SharedLibraryInfo latestSharedLibraVersionLPr =
                    getLatestSharedLibraVersionLPr(scanResult.mRequest.mParsedPackage);
            if (latestSharedLibraVersionLPr != null) {
                sharedLibPackage = mSettings.getPackageLPr(
                        latestSharedLibraVersionLPr.getPackageName());
            }
        }
        return sharedLibPackage;
    }

    public void shutdown() {
        mCompilerStats.writeNow();
        mDexManager.writePackageDexUsageNow();
        PackageWatchdog.getInstance(mContext).writeNow();

        synchronized (mLock) {
            mPackageUsage.writeNow(mSettings.getPackagesLocked());

            // This is the last chance to write out pending restriction settings
            if (mHandler.hasMessages(WRITE_PACKAGE_RESTRICTIONS)) {
                mHandler.removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                for (int userId : mDirtyUsers) {
                    mSettings.writePackageRestrictionsLPr(userId);
                }
                mDirtyUsers.clear();
            }
        }
    }

    @Override
    public void dumpProfiles(String packageName) {
        /* Only the shell, root, or the app user should be able to dump profiles. */
        final int callingUid = Binder.getCallingUid();
        final String[] callerPackageNames = getPackagesForUid(callingUid);
        if (callingUid != Process.SHELL_UID
                && callingUid != Process.ROOT_UID
                && !ArrayUtils.contains(callerPackageNames, packageName)) {
            throw new SecurityException("dumpProfiles");
        }

        AndroidPackage pkg;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
        }

        synchronized (mInstallLock) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dump profiles");
            mArtManagerService.dumpProfiles(pkg);
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @Override
    public void forceDexOpt(String packageName) {
        enforceSystemOrRoot("forceDexOpt");

        AndroidPackage pkg;
        PackageSetting pkgSetting;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
            pkgSetting = mSettings.getPackageLPr(packageName);
            if (pkg == null || pkgSetting == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
        }

        synchronized (mInstallLock) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "dexopt");

            // Whoever is calling forceDexOpt wants a compiled package.
            // Don't use profiles since that may cause compilation to be skipped.
            final int res = performDexOptInternalWithDependenciesLI(pkg, pkgSetting,
                    new DexoptOptions(packageName,
                            getDefaultCompilerFilter(),
                            DexoptOptions.DEXOPT_FORCE | DexoptOptions.DEXOPT_BOOT_COMPLETE));

            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            if (res != PackageDexOptimizer.DEX_OPT_PERFORMED) {
                throw new IllegalStateException("Failed to dexopt: " + res);
            }
        }
    }

    @GuardedBy("mInstallLock")
    void removeCodePathLI(File codePath) {
        if (codePath.isDirectory()) {
            final File codePathParent = codePath.getParentFile();
            final boolean needRemoveParent = codePathParent.getName().startsWith(RANDOM_DIR_PREFIX);
            try {
                final boolean isIncremental = (mIncrementalManager != null && isIncrementalPath(
                        codePath.getAbsolutePath()));
                if (isIncremental) {
                    if (needRemoveParent) {
                        mIncrementalManager.rmPackageDir(codePathParent);
                    } else {
                        mIncrementalManager.rmPackageDir(codePath);
                    }
                }

                mInstaller.rmPackageDir(codePath.getAbsolutePath());
                if (needRemoveParent) {
                    mInstaller.rmPackageDir(codePathParent.getAbsolutePath());
                    removeCachedResult(codePathParent);
                }
            } catch (InstallerException e) {
                Slog.w(TAG, "Failed to remove code path", e);
            }
        } else {
            codePath.delete();
        }
    }

    private void removeCachedResult(@NonNull File codePath) {
        if (mCacheDir == null) {
            return;
        }

        final PackageCacher cacher = new PackageCacher(mCacheDir);
        // Find and delete the cached result belong to the given codePath.
        cacher.cleanCachedResult(codePath);
    }

    int[] resolveUserIds(int userId) {
        return (userId == UserHandle.USER_ALL) ? mUserManager.getUserIds() : new int[] { userId };
    }

    void clearAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            return;
        }
        clearAppDataLeafLIF(pkg, userId, flags);

        if ((flags & Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES) == 0) {
            clearAppProfilesLIF(pkg);
        }
    }

    private void clearAppDataLeafLIF(AndroidPackage pkg, int userId, int flags) {
        final PackageSetting ps;
        synchronized (mLock) {
            ps = mSettings.getPackageLPr(pkg.getPackageName());
        }
        for (int realUserId : resolveUserIds(userId)) {
            final long ceDataInode = (ps != null) ? ps.getCeDataInode(realUserId) : 0;
            try {
                mInstaller.clearAppData(pkg.getVolumeUuid(), pkg.getPackageName(), realUserId,
                        flags, ceDataInode);
            } catch (InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
        }
    }

    private void destroyAppDataLIF(AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppDataLeafLIF(pkg, userId, flags);
    }

    private void destroyAppDataLeafLIF(AndroidPackage pkg, int userId, int flags) {
        final PackageSetting ps;
        synchronized (mLock) {
            ps = mSettings.getPackageLPr(pkg.getPackageName());
        }
        for (int realUserId : resolveUserIds(userId)) {
            final long ceDataInode = (ps != null) ? ps.getCeDataInode(realUserId) : 0;
            try {
                mInstaller.destroyAppData(pkg.getVolumeUuid(), pkg.getPackageName(), realUserId,
                        flags, ceDataInode);
            } catch (InstallerException e) {
                Slog.w(TAG, String.valueOf(e));
            }
            mDexManager.notifyPackageDataDestroyed(pkg.getPackageName(), userId);
        }
    }

    private void destroyAppProfilesLIF(AndroidPackage pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        destroyAppProfilesLeafLIF(pkg);
    }

    private void destroyAppProfilesLeafLIF(AndroidPackage pkg) {
        try {
            mInstaller.destroyAppProfiles(pkg.getPackageName());
        } catch (InstallerException e) {
            Slog.w(TAG, String.valueOf(e));
        }
    }

    void clearAppProfilesLIF(AndroidPackage pkg) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        mArtManagerService.clearAppProfiles(pkg);
    }

    @GuardedBy("mLock")
    private void applyDefiningSharedLibraryUpdateLocked(
            AndroidPackage pkg, SharedLibraryInfo libInfo,
            BiConsumer<SharedLibraryInfo, SharedLibraryInfo> action) {
        // Note that libraries defined by this package may be null if:
        // - Package manager was unable to create the shared library. The package still
        //   gets installed, but the shared library does not get created.
        // Or:
        // - Package manager is in a state where package isn't scanned yet. This will
        //   get called again after scanning to fix the dependencies.
        if (AndroidPackageUtils.isLibrary(pkg)) {
            if (pkg.getStaticSharedLibName() != null) {
                SharedLibraryInfo definedLibrary = getSharedLibraryInfoLPr(
                        pkg.getStaticSharedLibName(), pkg.getStaticSharedLibVersion());
                if (definedLibrary != null) {
                    action.accept(definedLibrary, libInfo);
                }
            } else {
                for (String libraryName : pkg.getLibraryNames()) {
                    SharedLibraryInfo definedLibrary = getSharedLibraryInfoLPr(
                            libraryName, SharedLibraryInfo.VERSION_UNDEFINED);
                    if (definedLibrary != null) {
                        action.accept(definedLibrary, libInfo);
                    }
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void addSharedLibraryLPr(AndroidPackage pkg, Set<String> usesLibraryFiles,
            SharedLibraryInfo libInfo, @Nullable AndroidPackage changingLib,
            @Nullable PackageSetting changingLibSetting) {
        if (libInfo.getPath() != null) {
            usesLibraryFiles.add(libInfo.getPath());
            return;
        }
        AndroidPackage pkgForCodePaths = mPackages.get(libInfo.getPackageName());
        PackageSetting pkgSetting = mSettings.getPackageLPr(libInfo.getPackageName());
        if (changingLib != null && changingLib.getPackageName().equals(libInfo.getPackageName())) {
            // If we are doing this while in the middle of updating a library apk,
            // then we need to make sure to use that new apk for determining the
            // dependencies here.  (We haven't yet finished committing the new apk
            // to the package manager state.)
            if (pkgForCodePaths == null
                    || pkgForCodePaths.getPackageName().equals(changingLib.getPackageName())) {
                pkgForCodePaths = changingLib;
                pkgSetting = changingLibSetting;
            }
        }
        if (pkgForCodePaths != null) {
            usesLibraryFiles.addAll(AndroidPackageUtils.getAllCodePaths(pkgForCodePaths));
            // If the package provides libraries, add the dependency to them.
            applyDefiningSharedLibraryUpdateLocked(pkg, libInfo, SharedLibraryInfo::addDependency);
            if (pkgSetting != null) {
                usesLibraryFiles.addAll(pkgSetting.getPkgState().getUsesLibraryFiles());
            }
        }
    }

    @GuardedBy("mLock")
    void updateSharedLibrariesLocked(AndroidPackage pkg, PackageSetting pkgSetting,
            @Nullable AndroidPackage changingLib, @Nullable PackageSetting changingLibSetting,
            Map<String, AndroidPackage> availablePackages)
            throws PackageManagerException {
        final ArrayList<SharedLibraryInfo> sharedLibraryInfos = collectSharedLibraryInfos(
                pkgSetting.getPkg(), availablePackages, mSharedLibraries, null /* newLibraries */,
                mInjector.getCompatibility());
        executeSharedLibrariesUpdateLPr(pkg, pkgSetting, changingLib, changingLibSetting,
                sharedLibraryInfos, mUserManager.getUserIds());
    }

    private static ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(AndroidPackage pkg,
            Map<String, AndroidPackage> availablePackages,
            @NonNull final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries,
            PlatformCompat platformCompat) throws PackageManagerException {
        if (pkg == null) {
            return null;
        }
        // The collection used here must maintain the order of addition (so
        // that libraries are searched in the correct order) and must have no
        // duplicates.
        ArrayList<SharedLibraryInfo> usesLibraryInfos = null;
        if (!pkg.getUsesLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesLibraries(), null, null,
                    pkg.getPackageName(), true, pkg.getTargetSdkVersion(), null,
                    availablePackages, existingLibraries, newLibraries);
        }
        if (!pkg.getUsesStaticLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesStaticLibraries(),
                    pkg.getUsesStaticLibrariesVersions(), pkg.getUsesStaticLibrariesCertDigests(),
                    pkg.getPackageName(), true, pkg.getTargetSdkVersion(), usesLibraryInfos,
                    availablePackages, existingLibraries, newLibraries);
        }
        if (!pkg.getUsesOptionalLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesOptionalLibraries(),
                    null, null, pkg.getPackageName(), false, pkg.getTargetSdkVersion(),
                    usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
        }
        if (platformCompat.isChangeEnabledInternal(ENFORCE_NATIVE_SHARED_LIBRARY_DEPENDENCIES,
                pkg.getPackageName(), pkg.getTargetSdkVersion())) {
            if (!pkg.getUsesNativeLibraries().isEmpty()) {
                usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesNativeLibraries(), null,
                        null, pkg.getPackageName(), true, pkg.getTargetSdkVersion(),
                        usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
            }
            if (!pkg.getUsesOptionalNativeLibraries().isEmpty()) {
                usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesOptionalNativeLibraries(),
                        null, null, pkg.getPackageName(), false, pkg.getTargetSdkVersion(),
                        usesLibraryInfos, availablePackages, existingLibraries, newLibraries);
            }
        }
        return usesLibraryInfos;
    }

    private void executeSharedLibrariesUpdateLPr(AndroidPackage pkg,
            @NonNull PackageSetting pkgSetting, @Nullable AndroidPackage changingLib,
            @Nullable PackageSetting changingLibSetting,
            ArrayList<SharedLibraryInfo> usesLibraryInfos, int[] allUsers) {
        // If the package provides libraries, clear their old dependencies.
        // This method will set them up again.
        applyDefiningSharedLibraryUpdateLocked(pkg, null, (definingLibrary, dependency) -> {
            definingLibrary.clearDependencies();
        });
        if (usesLibraryInfos != null) {
            pkgSetting.getPkgState().setUsesLibraryInfos(usesLibraryInfos);
            // Use LinkedHashSet to preserve the order of files added to
            // usesLibraryFiles while eliminating duplicates.
            Set<String> usesLibraryFiles = new LinkedHashSet<>();
            for (SharedLibraryInfo libInfo : usesLibraryInfos) {
                addSharedLibraryLPr(pkg, usesLibraryFiles, libInfo, changingLib,
                        changingLibSetting);
            }
            pkgSetting.getPkgState().setUsesLibraryFiles(new ArrayList<>(usesLibraryFiles));
            // let's make sure we mark all static shared libraries as installed for the same users
            // that its dependent packages are installed for.
            int[] installedUsers = new int[allUsers.length];
            int installedUserCount = 0;
            for (int u = 0; u < allUsers.length; u++) {
                if (pkgSetting.getInstalled(allUsers[u])) {
                    installedUsers[installedUserCount++] = allUsers[u];
                }
            }
            for (SharedLibraryInfo sharedLibraryInfo : usesLibraryInfos) {
                if (!sharedLibraryInfo.isStatic()) {
                    continue;
                }
                final PackageSetting staticLibPkgSetting =
                        getPackageSetting(sharedLibraryInfo.getPackageName());
                if (staticLibPkgSetting == null) {
                    Slog.wtf(TAG, "Shared lib without setting: " + sharedLibraryInfo);
                    continue;
                }
                for (int u = 0; u < installedUserCount; u++) {
                    staticLibPkgSetting.setInstalled(true, installedUsers[u]);
                }
            }
        } else {
            pkgSetting.getPkgState().setUsesLibraryInfos(Collections.emptyList())
                    .setUsesLibraryFiles(Collections.emptyList());
        }
    }

    @GuardedBy("mLock")
    private static ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(
            @NonNull List<String> requestedLibraries,
            @Nullable long[] requiredVersions, @Nullable String[][] requiredCertDigests,
            @NonNull String packageName, boolean required, int targetSdk,
            @Nullable ArrayList<SharedLibraryInfo> outUsedLibraries,
            @NonNull final Map<String, AndroidPackage> availablePackages,
            @NonNull final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingLibraries,
            @Nullable final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries)
            throws PackageManagerException {
        final int libCount = requestedLibraries.size();
        for (int i = 0; i < libCount; i++) {
            final String libName = requestedLibraries.get(i);
            final long libVersion = requiredVersions != null ? requiredVersions[i]
                    : SharedLibraryInfo.VERSION_UNDEFINED;
            final SharedLibraryInfo libraryInfo =
                    getSharedLibraryInfo(libName, libVersion, existingLibraries, newLibraries);
            if (libraryInfo == null) {
                if (required) {
                    throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                            "Package " + packageName + " requires unavailable shared library "
                                    + libName + "; failing!");
                } else if (DEBUG_SHARED_LIBRARIES) {
                    Slog.i(TAG, "Package " + packageName
                            + " desires unavailable shared library "
                            + libName + "; ignoring!");
                }
            } else {
                if (requiredVersions != null && requiredCertDigests != null) {
                    if (libraryInfo.getLongVersion() != requiredVersions[i]) {
                        throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                            "Package " + packageName + " requires unavailable static shared"
                                    + " library " + libName + " version "
                                    + libraryInfo.getLongVersion() + "; failing!");
                    }
                    AndroidPackage pkg = availablePackages.get(libraryInfo.getPackageName());
                    SigningDetails libPkg = pkg == null ? null : pkg.getSigningDetails();
                    if (libPkg == null) {
                        throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                "Package " + packageName + " requires unavailable static shared"
                                        + " library; failing!");
                    }
                    final String[] expectedCertDigests = requiredCertDigests[i];
                    if (expectedCertDigests.length > 1) {
                        // For apps targeting O MR1 we require explicit enumeration of all certs.
                        final String[] libCertDigests = (targetSdk >= Build.VERSION_CODES.O_MR1)
                                ? PackageUtils.computeSignaturesSha256Digests(
                                libPkg.getSignatures())
                                : PackageUtils.computeSignaturesSha256Digests(
                                        new Signature[]{libPkg.getSignatures()[0]});

                        // Take a shortcut if sizes don't match. Note that if an app doesn't
                        // target O we don't parse the "additional-certificate" tags similarly
                        // how we only consider all certs only for apps targeting O (see above).
                        // Therefore, the size check is safe to make.
                        if (expectedCertDigests.length != libCertDigests.length) {
                            throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                    "Package " + packageName + " requires differently signed" +
                                            " static shared library; failing!");
                        }

                        // Use a predictable order as signature order may vary
                        Arrays.sort(libCertDigests);
                        Arrays.sort(expectedCertDigests);

                        final int certCount = libCertDigests.length;
                        for (int j = 0; j < certCount; j++) {
                            if (!libCertDigests[j].equalsIgnoreCase(expectedCertDigests[j])) {
                                throw new PackageManagerException(
                                        INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                        "Package " + packageName + " requires differently signed" +
                                                " static shared library; failing!");
                            }
                        }
                    } else {
                        // lib signing cert could have rotated beyond the one expected, check to see
                        // if the new one has been blessed by the old
                        byte[] digestBytes = HexEncoding.decode(
                                expectedCertDigests[0], false /* allowSingleChar */);
                        if (!libPkg.hasSha256Certificate(digestBytes)) {
                            throw new PackageManagerException(
                                    INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                    "Package " + packageName + " requires differently signed" +
                                            " static shared library; failing!");
                        }
                    }
                }
                if (outUsedLibraries == null) {
                    outUsedLibraries = new ArrayList<>();
                }
                outUsedLibraries.add(libraryInfo);
            }
        }
        return outUsedLibraries;
    }

    private static boolean hasString(List<String> list, List<String> which) {
        if (list == null || which == null) {
            return false;
        }
        for (int i=list.size()-1; i>=0; i--) {
            for (int j=which.size()-1; j>=0; j--) {
                if (which.get(j).equals(list.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private ArrayList<AndroidPackage> updateAllSharedLibrariesLocked(
            @Nullable AndroidPackage updatedPkg, @Nullable PackageSetting updatedPkgSetting,
            Map<String, AndroidPackage> availablePackages) {
        ArrayList<AndroidPackage> resultList = null;
        // Set of all descendants of a library; used to eliminate cycles
        ArraySet<String> descendants = null;
        // The current list of packages that need updating
        List<Pair<AndroidPackage, PackageSetting>> needsUpdating = null;
        if (updatedPkg != null && updatedPkgSetting != null) {
            needsUpdating = new ArrayList<>(1);
            needsUpdating.add(Pair.create(updatedPkg, updatedPkgSetting));
        }
        do {
            final Pair<AndroidPackage, PackageSetting> changingPkgPair =
                    (needsUpdating == null) ? null : needsUpdating.remove(0);
            final AndroidPackage changingPkg = changingPkgPair != null
                    ? changingPkgPair.first : null;
            final PackageSetting changingPkgSetting = changingPkgPair != null
                    ? changingPkgPair.second : null;
            for (int i = mPackages.size() - 1; i >= 0; --i) {
                final AndroidPackage pkg = mPackages.valueAt(i);
                final PackageSetting pkgSetting = mSettings.getPackageLPr(pkg.getPackageName());
                if (changingPkg != null
                        && !hasString(pkg.getUsesLibraries(), changingPkg.getLibraryNames())
                        && !hasString(pkg.getUsesOptionalLibraries(), changingPkg.getLibraryNames())
                        && !ArrayUtils.contains(pkg.getUsesStaticLibraries(),
                        changingPkg.getStaticSharedLibName())) {
                    continue;
                }
                if (resultList == null) {
                    resultList = new ArrayList<>();
                }
                resultList.add(pkg);
                // if we're updating a shared library, all of its descendants must be updated
                if (changingPkg != null) {
                    if (descendants == null) {
                        descendants = new ArraySet<>();
                    }
                    if (!descendants.contains(pkg.getPackageName())) {
                        descendants.add(pkg.getPackageName());
                        needsUpdating.add(Pair.create(pkg, pkgSetting));
                    }
                }
                try {
                    updateSharedLibrariesLocked(pkg, pkgSetting, changingPkg,
                            changingPkgSetting, availablePackages);
                } catch (PackageManagerException e) {
                    // If a system app update or an app and a required lib missing we
                    // delete the package and for updated system apps keep the data as
                    // it is better for the user to reinstall than to be in an limbo
                    // state. Also libs disappearing under an app should never happen
                    // - just in case.
                    if (!pkg.isSystem() || pkgSetting.getPkgState().isUpdatedSystemApp()) {
                        final int flags = pkgSetting.getPkgState().isUpdatedSystemApp()
                                ? PackageManager.DELETE_KEEP_DATA : 0;
                        deletePackageLIF(pkg.getPackageName(), null, true,
                                mUserManager.getUserIds(), flags, null,
                                true);
                    }
                    Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
                }
            }
        } while (needsUpdating != null && needsUpdating.size() > 0);
        return resultList;
    }

    /**
     * Commits the package scan and modifies system state.
     * <p><em>WARNING:</em> The method may throw an excpetion in the middle
     * of committing the package, leaving the system in an inconsistent state.
     * This needs to be fixed so, once we get to this point, no errors are
     * possible and the system is not left in an inconsistent state.
     */
    @GuardedBy({"mLock", "mInstallLock"})
    AndroidPackage commitReconciledScanResultLocked(
            @NonNull ReconciledPackage reconciledPkg, int[] allUsers) {
        final ScanResult result = reconciledPkg.mScanResult;
        final ScanRequest request = result.mRequest;
        // TODO(b/135203078): Move this even further away
        ParsedPackage parsedPackage = request.mParsedPackage;
        if ("android".equals(parsedPackage.getPackageName())) {
            // TODO(b/135203078): Move this to initial parse
            parsedPackage.setVersionCode(mSdkVersion)
                    .setVersionCodeMajor(0);
        }

        final AndroidPackage oldPkg = request.mOldPkg;
        final @ParseFlags int parseFlags = request.mParseFlags;
        final @ScanFlags int scanFlags = request.mScanFlags;
        final PackageSetting oldPkgSetting = request.mOldPkgSetting;
        final PackageSetting originalPkgSetting = request.mOriginalPkgSetting;
        final UserHandle user = request.mUser;
        final String realPkgName = request.mRealPkgName;
        final List<String> changedAbiCodePath = result.mChangedAbiCodePath;
        final PackageSetting pkgSetting;
        if (request.mPkgSetting != null && request.mPkgSetting.getSharedUser() != null
                && request.mPkgSetting.getSharedUser() != result.mPkgSetting.getSharedUser()) {
            // shared user changed, remove from old shared user
            request.mPkgSetting.getSharedUser().removePackage(request.mPkgSetting);
        }
        if (result.mExistingSettingCopied) {
            pkgSetting = request.mPkgSetting;
            pkgSetting.updateFrom(result.mPkgSetting);
        } else {
            pkgSetting = result.mPkgSetting;
            if (originalPkgSetting != null) {
                mSettings.addRenamedPackageLPw(
                        AndroidPackageUtils.getRealPackageOrNull(parsedPackage),
                        originalPkgSetting.getPackageName());
                mTransferredPackages.add(originalPkgSetting.getPackageName());
            } else {
                mSettings.removeRenamedPackageLPw(parsedPackage.getPackageName());
            }
        }
        if (pkgSetting.getSharedUser() != null) {
            pkgSetting.getSharedUser().addPackage(pkgSetting);
        }
        if (reconciledPkg.mInstallArgs != null
                        && reconciledPkg.mInstallArgs.mForceQueryableOverride) {
            pkgSetting.setForceQueryableOverride(true);
        }

        // If this is part of a standard install, set the initiating package name, else rely on
        // previous device state.
        if (reconciledPkg.mInstallArgs != null) {
            InstallSource installSource = reconciledPkg.mInstallArgs.mInstallSource;
            if (installSource.initiatingPackageName != null) {
                final PackageSetting ips = mSettings.getPackageLPr(
                        installSource.initiatingPackageName);
                if (ips != null) {
                    installSource = installSource.setInitiatingPackageSignatures(
                            ips.getSignatures());
                }
            }
            pkgSetting.setInstallSource(installSource);
        }

        // TODO(toddke): Consider a method specifically for modifying the Package object
        // post scan; or, moving this stuff out of the Package object since it has nothing
        // to do with the package on disk.
        // We need to have this here because addUserToSettingLPw() is sometimes responsible
        // for creating the application ID. If we did this earlier, we would be saving the
        // correct ID.
        parsedPackage.setUid(pkgSetting.getAppId());
        final AndroidPackage pkg = parsedPackage.hideAsFinal();

        mSettings.writeUserRestrictionsLPw(pkgSetting, oldPkgSetting);

        if (realPkgName != null) {
            mTransferredPackages.add(pkg.getPackageName());
        }

        if (reconciledPkg.mCollectedSharedLibraryInfos != null) {
            executeSharedLibrariesUpdateLPr(pkg, pkgSetting, null, null,
                    reconciledPkg.mCollectedSharedLibraryInfos, allUsers);
        }

        final KeySetManagerService ksms = mSettings.getKeySetManagerService();
        if (reconciledPkg.mRemoveAppKeySetData) {
            ksms.removeAppKeySetDataLPw(pkg.getPackageName());
        }
        if (reconciledPkg.mSharedUserSignaturesChanged) {
            pkgSetting.getSharedUser().signaturesChanged = Boolean.TRUE;
            pkgSetting.getSharedUser().signatures.mSigningDetails = reconciledPkg.mSigningDetails;
        }
        pkgSetting.setSigningDetails(reconciledPkg.mSigningDetails);

        if (changedAbiCodePath != null && changedAbiCodePath.size() > 0) {
            for (int i = changedAbiCodePath.size() - 1; i >= 0; --i) {
                final String codePathString = changedAbiCodePath.get(i);
                try {
                    mInstaller.rmdex(codePathString,
                            getDexCodeInstructionSet(getPreferredInstructionSet()));
                } catch (InstallerException ignored) {
                }
            }
        }

        final int userId = user == null ? 0 : user.getIdentifier();
        // Modify state for the given package setting
        commitPackageSettings(pkg, oldPkg, pkgSetting, oldPkgSetting, scanFlags,
                (parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0 /*chatty*/, reconciledPkg);
        if (pkgSetting.getInstantApp(userId)) {
            mInstantAppRegistry.addInstantAppLPw(userId, pkgSetting.getAppId());
        }
        pkgSetting.setStatesOnCommit();

        return pkg;
    }

    @GuardedBy("mLock")
    private void addBuiltInSharedLibraryLocked(SystemConfig.SharedLibraryEntry entry) {
        if (nonStaticSharedLibExistsLocked(entry.name)) {
            return;
        }

        SharedLibraryInfo libraryInfo = new SharedLibraryInfo(entry.filename, null, null,
                entry.name, SharedLibraryInfo.VERSION_UNDEFINED,
                SharedLibraryInfo.TYPE_BUILTIN,
                new VersionedPackage(PLATFORM_PACKAGE_NAME, (long)0), null, null,
                entry.isNative);

        commitSharedLibraryInfoLocked(libraryInfo);
    }

    @GuardedBy("mLock")
    private boolean nonStaticSharedLibExistsLocked(String name) {
        return sharedLibExists(name, SharedLibraryInfo.VERSION_UNDEFINED, mSharedLibraries);
    }

    private static boolean sharedLibExists(final String name, final long version,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> librarySource) {
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = librarySource.get(name);
        return versionedLib != null && versionedLib.indexOfKey(version) >= 0;
    }

    @GuardedBy("mLock")
    private void commitSharedLibraryInfoLocked(SharedLibraryInfo libraryInfo) {
        final String name = libraryInfo.getName();
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(name);
        if (versionedLib == null) {
            versionedLib = new WatchedLongSparseArray<>();
            mSharedLibraries.put(name, versionedLib);
        }
        final String declaringPackageName = libraryInfo.getDeclaringPackage().getPackageName();
        if (libraryInfo.getType() == SharedLibraryInfo.TYPE_STATIC) {
            mStaticLibsByDeclaringPackage.put(declaringPackageName, versionedLib);
        }
        versionedLib.put(libraryInfo.getLongVersion(), libraryInfo);
    }

    private boolean removeSharedLibraryLPw(String name, long version) {
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(name);
        if (versionedLib == null) {
            return false;
        }
        final int libIdx = versionedLib.indexOfKey(version);
        if (libIdx < 0) {
            return false;
        }
        SharedLibraryInfo libraryInfo = versionedLib.valueAt(libIdx);

        // Remove the shared library overlays from its dependent packages.
        for (int currentUserId : UserManagerService.getInstance().getUserIds()) {
            final List<VersionedPackage> dependents = getPackagesUsingSharedLibraryLPr(
                    libraryInfo, 0, Process.SYSTEM_UID, currentUserId);
            if (dependents == null) {
                continue;
            }
            for (VersionedPackage dependentPackage : dependents) {
                final PackageSetting ps = mSettings.getPackageLPr(
                        dependentPackage.getPackageName());
                if (ps != null) {
                    ps.setOverlayPathsForLibrary(libraryInfo.getName(), null, currentUserId);
                }
            }
        }

        versionedLib.remove(version);
        if (versionedLib.size() <= 0) {
            mSharedLibraries.remove(name);
            if (libraryInfo.getType() == SharedLibraryInfo.TYPE_STATIC) {
                mStaticLibsByDeclaringPackage.remove(libraryInfo.getDeclaringPackage()
                        .getPackageName());
            }
        }
        return true;
    }

    @Override
    public Property getProperty(String propertyName, String packageName, String className) {
        Objects.requireNonNull(propertyName);
        Objects.requireNonNull(packageName);
        synchronized (mLock) {
            final PackageSetting ps = getPackageSetting(packageName);
            if (shouldFilterApplicationLocked(ps, Binder.getCallingUid(),
                    UserHandle.getCallingUserId())) {
                return null;
            }
            return mPackageProperty.getProperty(propertyName, packageName, className);
        }
    }

    @Override
    public ParceledListSlice<Property> queryProperty(
            String propertyName, @PropertyLocation int componentType) {
        Objects.requireNonNull(propertyName);
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        final List<Property> result =
                mPackageProperty.queryProperty(propertyName, componentType, packageName -> {
                    final PackageSetting ps = getPackageSetting(packageName);
                    return shouldFilterApplicationLocked(ps, callingUid, callingUserId);
                });
        if (result == null) {
            return ParceledListSlice.emptyList();
        }
        return new ParceledListSlice<>(result);
    }

    /**
     * Adds a scanned package to the system. When this method is finished, the package will
     * be available for query, resolution, etc...
     */
    private void commitPackageSettings(@NonNull AndroidPackage pkg, @Nullable AndroidPackage oldPkg,
            @NonNull PackageSetting pkgSetting, @Nullable PackageSetting oldPkgSetting,
            final @ScanFlags int scanFlags, boolean chatty, ReconciledPackage reconciledPkg) {
        final String pkgName = pkg.getPackageName();
        if (mCustomResolverComponentName != null &&
                mCustomResolverComponentName.getPackageName().equals(pkg.getPackageName())) {
            setUpCustomResolverActivity(pkg, pkgSetting);
        }

        if (pkg.getPackageName().equals("android")) {
            synchronized (mLock) {
                // Set up information for our fall-back user intent resolution activity.
                mPlatformPackage = pkg;

                // The instance stored in PackageManagerService is special cased to be non-user
                // specific, so initialize all the needed fields here.
                mAndroidApplication = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                        new PackageUserState(), UserHandle.USER_SYSTEM, pkgSetting);

                if (!mResolverReplaced) {
                    mResolveActivity.applicationInfo = mAndroidApplication;
                    mResolveActivity.name = ResolverActivity.class.getName();
                    mResolveActivity.packageName = mAndroidApplication.packageName;
                    mResolveActivity.processName = "system:ui";
                    mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                    mResolveActivity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NEVER;
                    mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
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
                onChanged();
            }
        }

        ArrayList<AndroidPackage> clientLibPkgs = null;
        // writer
        synchronized (mLock) {
            if (!ArrayUtils.isEmpty(reconciledPkg.mAllowedSharedLibraryInfos)) {
                for (SharedLibraryInfo info : reconciledPkg.mAllowedSharedLibraryInfos) {
                    commitSharedLibraryInfoLocked(info);
                }
                final Map<String, AndroidPackage> combinedSigningDetails =
                        reconciledPkg.getCombinedAvailablePackages();
                try {
                    // Shared libraries for the package need to be updated.
                    updateSharedLibrariesLocked(pkg, pkgSetting, null, null,
                            combinedSigningDetails);
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "updateSharedLibrariesLPr failed: ", e);
                }
                // Update all applications that use this library. Skip when booting
                // since this will be done after all packages are scaned.
                if ((scanFlags & SCAN_BOOTING) == 0) {
                    clientLibPkgs = updateAllSharedLibrariesLocked(pkg, pkgSetting,
                            combinedSigningDetails);
                }
            }
        }
        if (reconciledPkg.mInstallResult != null) {
            reconciledPkg.mInstallResult.mLibraryConsumers = clientLibPkgs;
        }

        if ((scanFlags & SCAN_BOOTING) != 0) {
            // No apps can run during boot scan, so they don't need to be frozen
        } else if ((scanFlags & SCAN_DONT_KILL_APP) != 0) {
            // Caller asked to not kill app, so it's probably not frozen
        } else if ((scanFlags & SCAN_IGNORE_FROZEN) != 0) {
            // Caller asked us to ignore frozen check for some reason; they
            // probably didn't know the package name
        } else {
            // We're doing major surgery on this package, so it better be frozen
            // right now to keep it from launching
            checkPackageFrozen(pkgName);
        }

        // Also need to kill any apps that are dependent on the library.
        if (clientLibPkgs != null) {
            for (int i=0; i<clientLibPkgs.size(); i++) {
                AndroidPackage clientPkg = clientLibPkgs.get(i);
                killApplication(clientPkg.getPackageName(),
                        clientPkg.getUid(), "update lib");
            }
        }

        // writer
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        synchronized (mLock) {
            // We don't expect installation to fail beyond this point
            // Add the new setting to mSettings
            mSettings.insertPackageSettingLPw(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPackages.put(pkg.getPackageName(), pkg);
            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0) {
                mApexManager.registerApkInApex(pkg);
            }

            // Add the package's KeySets to the global KeySetManagerService
            KeySetManagerService ksms = mSettings.getKeySetManagerService();
            ksms.addScannedPackageLPw(pkg);

            mComponentResolver.addAllComponents(pkg, chatty);
            final boolean isReplace =
                    reconciledPkg.mPrepareResult != null && reconciledPkg.mPrepareResult.mReplace;
            mAppsFilter.addPackage(pkgSetting, isReplace);
            mPackageProperty.addAllProperties(pkg);

            if (oldPkgSetting == null || oldPkgSetting.getPkg() == null) {
                mDomainVerificationManager.addPackage(pkgSetting);
            } else {
                mDomainVerificationManager.migrateState(oldPkgSetting, pkgSetting);
            }

            int collectionSize = ArrayUtils.size(pkg.getInstrumentations());
            StringBuilder r = null;
            int i;
            for (i = 0; i < collectionSize; i++) {
                ParsedInstrumentation a = pkg.getInstrumentations().get(i);
                a.setPackageName(pkg.getPackageName());
                mInstrumentation.put(a.getComponentName(), a);
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.getName());
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            final List<String> protectedBroadcasts = pkg.getProtectedBroadcasts();
            if (!protectedBroadcasts.isEmpty()) {
                synchronized (mProtectedBroadcasts) {
                    mProtectedBroadcasts.addAll(protectedBroadcasts);
                }
            }

            mPermissionManager.onPackageAdded(pkg, (scanFlags & SCAN_AS_INSTANT_APP) != 0, oldPkg);
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    private void setUpCustomResolverActivity(AndroidPackage pkg, PackageSetting pkgSetting) {
        synchronized (mLock) {
            mResolverReplaced = true;

            // The instance created in PackageManagerService is special cased to be non-user
            // specific, so initialize all the needed fields here.
            ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                    new PackageUserState(), UserHandle.USER_SYSTEM, pkgSetting);

            // Set up information for custom user intent resolution activity.
            mResolveActivity.applicationInfo = appInfo;
            mResolveActivity.name = mCustomResolverComponentName.getClassName();
            mResolveActivity.packageName = pkg.getPackageName();
            mResolveActivity.processName = pkg.getProcessName();
            mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS |
                    ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
            mResolveActivity.theme = 0;
            mResolveActivity.exported = true;
            mResolveActivity.enabled = true;
            mResolveInfo.activityInfo = mResolveActivity;
            mResolveInfo.priority = 0;
            mResolveInfo.preferredOrder = 0;
            mResolveInfo.match = 0;
            mResolveComponentName = mCustomResolverComponentName;
            onChanged();
            Slog.i(TAG, "Replacing default ResolverActivity with custom activity: " +
                    mResolveComponentName);
        }
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

    private void killApplication(String pkgName, @AppIdInt int appId, String reason) {
        killApplication(pkgName, appId, UserHandle.USER_ALL, reason);
    }

    void killApplication(String pkgName, @AppIdInt int appId,
            @UserIdInt int userId, String reason) {
        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        final long token = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killApplication(pkgName, appId, userId, reason);
                } catch (RemoteException e) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void removePackageLI(AndroidPackage pkg, boolean chatty) {
        // Remove the parent package setting
        PackageSetting ps = getPackageSetting(pkg.getPackageName());
        if (ps != null) {
            removePackageLI(ps.getPackageName(), chatty);
        } else if (DEBUG_REMOVE && chatty) {
            Log.d(TAG, "Not removing package " + pkg.getPackageName() + "; mExtras == null");
        }
    }

    private void removePackageLI(String packageName, boolean chatty) {
        if (DEBUG_INSTALL) {
            if (chatty)
                Log.d(TAG, "Removing package " + packageName);
        }

        // writer
        synchronized (mLock) {
            final AndroidPackage removedPackage = mPackages.remove(packageName);
            if (removedPackage != null) {
                cleanPackageDataStructuresLILPw(removedPackage, chatty);
            }
        }
    }

    private void cleanPackageDataStructuresLILPw(AndroidPackage pkg, boolean chatty) {
        mComponentResolver.removeAllComponents(pkg, chatty);
        mPermissionManager.onPackageRemoved(pkg);
        mPackageProperty.removeAllProperties(pkg);

        final int instrumentationSize = ArrayUtils.size(pkg.getInstrumentations());
        StringBuilder r = null;
        int i;
        for (i = 0; i < instrumentationSize; i++) {
            ParsedInstrumentation a = pkg.getInstrumentations().get(i);
            mInstrumentation.remove(a.getComponentName());
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.getName());
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Instrumentation: " + r);
        }

        r = null;
        if (pkg.isSystem()) {
            // Only system apps can hold shared libraries.
            final int libraryNamesSize = pkg.getLibraryNames().size();
            for (i = 0; i < libraryNamesSize; i++) {
                String name = pkg.getLibraryNames().get(i);
                if (removeSharedLibraryLPw(name, 0)) {
                    if (DEBUG_REMOVE && chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(name);
                    }
                }
            }
        }

        r = null;

        // Any package can hold static shared libraries.
        if (pkg.getStaticSharedLibName() != null) {
            if (removeSharedLibraryLPw(pkg.getStaticSharedLibName(),
                    pkg.getStaticSharedLibVersion())) {
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(pkg.getStaticSharedLibName());
                }
            }
        }

        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Libraries: " + r);
        }
    }

    @Override
    public void sendPackageBroadcast(final String action, final String pkg, final Bundle extras,
            final int flags, final String targetPkg, final IIntentReceiver finishedReceiver,
            final int[] userIds, int[] instantUserIds,
            @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable Bundle bOptions) {
        mHandler.post(() -> {
            try {
                final IActivityManager am = ActivityManager.getService();
                if (am == null) return;
                final int[] resolvedUserIds;
                if (userIds == null) {
                    resolvedUserIds = am.getRunningUserIds();
                } else {
                    resolvedUserIds = userIds;
                }
                doSendBroadcast(action, pkg, extras, flags, targetPkg, finishedReceiver,
                        resolvedUserIds, false, broadcastAllowList, bOptions);
                if (instantUserIds != null && instantUserIds != EMPTY_INT_ARRAY) {
                    doSendBroadcast(action, pkg, extras, flags, targetPkg, finishedReceiver,
                            instantUserIds, true, null, bOptions);
                }
            } catch (RemoteException ex) {
            }
        });
    }

    @Override
    public void notifyPackageAdded(String packageName, int uid) {
        final PackageListObserver[] observers;
        synchronized (mLock) {
            if (mPackageListObservers.size() == 0) {
                return;
            }
            final PackageListObserver[] observerArray =
                    new PackageListObserver[mPackageListObservers.size()];
            observers = mPackageListObservers.toArray(observerArray);
        }
        for (int i = observers.length - 1; i >= 0; --i) {
            observers[i].onPackageAdded(packageName, uid);
        }
    }

    @Override
    public void notifyPackageChanged(String packageName, int uid) {
        final PackageListObserver[] observers;
        synchronized (mLock) {
            if (mPackageListObservers.size() == 0) {
                return;
            }
            final PackageListObserver[] observerArray =
                    new PackageListObserver[mPackageListObservers.size()];
            observers = mPackageListObservers.toArray(observerArray);
        }
        for (int i = observers.length - 1; i >= 0; --i) {
            observers[i].onPackageChanged(packageName, uid);
        }
    }

    private static final Comparator<ProviderInfo> sProviderInitOrderSorter = (p1, p2) -> {
        final int v1 = p1.initOrder;
        final int v2 = p2.initOrder;
        return (v1 > v2) ? -1 : ((v1 < v2) ? 1 : 0);
    };

    @Override
    public void notifyPackageRemoved(String packageName, int uid) {
        final PackageListObserver[] observers;
        synchronized (mLock) {
            if (mPackageListObservers.size() == 0) {
                return;
            }
            final PackageListObserver[] observerArray =
                    new PackageListObserver[mPackageListObservers.size()];
            observers = mPackageListObservers.toArray(observerArray);
        }
        for (int i = observers.length - 1; i >= 0; --i) {
            observers[i].onPackageRemoved(packageName, uid);
        }
    }

    /**
     * Sends a broadcast for the given action.
     * <p>If {@code isInstantApp} is {@code true}, then the broadcast is protected with
     * the {@link android.Manifest.permission#ACCESS_INSTANT_APPS} permission. This allows
     * the system and applications allowed to see instant applications to receive package
     * lifecycle events for instant applications.
     */
    private void doSendBroadcast(String action, String pkg, Bundle extras,
            int flags, String targetPkg, IIntentReceiver finishedReceiver,
            int[] userIds, boolean isInstantApp, @Nullable SparseArray<int[]> broadcastAllowList,
            @Nullable Bundle bOptions) {
        for (int id : userIds) {
            final Intent intent = new Intent(action,
                    pkg != null ? Uri.fromParts(PACKAGE_SCHEME, pkg, null) : null);
            final String[] requiredPermissions =
                    isInstantApp ? INSTANT_APP_BROADCAST_PERMISSION : null;
            if (extras != null) {
                intent.putExtras(extras);
            }
            if (targetPkg != null) {
                intent.setPackage(targetPkg);
            }
            // Modify the UID when posting to other users
            int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            if (uid > 0 && UserHandle.getUserId(uid) != id) {
                uid = UserHandle.getUid(id, UserHandle.getAppId(uid));
                intent.putExtra(Intent.EXTRA_UID, uid);
            }
            if (broadcastAllowList != null && PLATFORM_PACKAGE_NAME.equals(targetPkg)) {
                intent.putExtra(Intent.EXTRA_VISIBILITY_ALLOW_LIST, broadcastAllowList.get(id));
            }
            intent.putExtra(Intent.EXTRA_USER_HANDLE, id);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT | flags);
            if (DEBUG_BROADCASTS) {
                RuntimeException here = new RuntimeException("here");
                here.fillInStackTrace();
                Slog.d(TAG, "Sending to user " + id + ": "
                        + intent.toShortString(false, true, false, false)
                        + " " + intent.getExtras(), here);
            }
            mInjector.getLocalService(ActivityManagerInternal.class).broadcastIntent(
                    intent, finishedReceiver, requiredPermissions,
                    finishedReceiver != null, id,
                    broadcastAllowList == null ? null : broadcastAllowList.get(id),
                    bOptions);
        }
    }

    private void sendPackageAddedForUser(String packageName, PackageSetting pkgSetting,
            int userId, int dataLoaderType) {
        final boolean isSystem = PackageManagerServiceUtils.isSystemApp(pkgSetting)
                || PackageManagerServiceUtils.isUpdatedSystemApp(pkgSetting);
        final boolean isInstantApp = pkgSetting.getInstantApp(userId);
        final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
        final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
        sendPackageAddedForNewUsers(packageName, isSystem /*sendBootCompleted*/,
                false /*startReceiver*/, pkgSetting.getAppId(), userIds, instantUserIds,
                dataLoaderType);

        // Send a session commit broadcast
        final PackageInstaller.SessionInfo info = new PackageInstaller.SessionInfo();
        info.installReason = pkgSetting.getInstallReason(userId);
        info.appPackageName = packageName;
        sendSessionCommitBroadcast(info, userId);
    }

    @Override
    public void sendPackageAddedForNewUsers(String packageName, boolean sendBootCompleted,
            boolean includeStopped, @AppIdInt int appId, int[] userIds, int[] instantUserIds,
            int dataLoaderType) {
        if (ArrayUtils.isEmpty(userIds) && ArrayUtils.isEmpty(instantUserIds)) {
            return;
        }
        Bundle extras = new Bundle(1);
        // Set to UID of the first user, EXTRA_UID is automatically updated in sendPackageBroadcast
        final int uid = UserHandle.getUid(
                (ArrayUtils.isEmpty(userIds) ? instantUserIds[0] : userIds[0]), appId);
        extras.putInt(Intent.EXTRA_UID, uid);
        extras.putInt(PackageInstaller.EXTRA_DATA_LOADER_TYPE, dataLoaderType);

        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                packageName, extras, 0, null, null, userIds, instantUserIds,
                mAppsFilter.getVisibilityAllowList(
                        getPackageSettingInternal(packageName, Process.SYSTEM_UID),
                        userIds, mSettings.getPackagesLocked()), null);
        if (sendBootCompleted && !ArrayUtils.isEmpty(userIds)) {
            mHandler.post(() -> {
                        for (int userId : userIds) {
                            sendBootCompletedBroadcastToSystemApp(
                                    packageName, includeStopped, userId);
                        }
                    }
            );
        }
    }

    /**
     * The just-installed/enabled app is bundled on the system, so presumed to be able to run
     * automatically without needing an explicit launch.
     * Send it a LOCKED_BOOT_COMPLETED/BOOT_COMPLETED if it would ordinarily have gotten ones.
     */
    private void sendBootCompletedBroadcastToSystemApp(
            String packageName, boolean includeStopped, int userId) {
        // If user is not running, the app didn't miss any broadcast
        if (!mUserManager.isUserRunning(userId)) {
            return;
        }
        final IActivityManager am = ActivityManager.getService();
        try {
            // Deliver LOCKED_BOOT_COMPLETED first
            Intent lockedBcIntent = new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
                    .setPackage(packageName);
            if (includeStopped) {
                lockedBcIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            }
            final String[] requiredPermissions = {Manifest.permission.RECEIVE_BOOT_COMPLETED};
            final BroadcastOptions bOptions = getTemporaryAppAllowlistBroadcastOptions(
                    REASON_LOCKED_BOOT_COMPLETED);
            am.broadcastIntentWithFeature(null, null, lockedBcIntent, null, null, 0, null, null,
                    requiredPermissions, null, android.app.AppOpsManager.OP_NONE,
                    bOptions.toBundle(), false, false, userId);

            // Deliver BOOT_COMPLETED only if user is unlocked
            final UserManagerInternal umInternal = mInjector.getUserManagerInternal();
            if (umInternal.isUserUnlockingOrUnlocked(userId)) {
                Intent bcIntent = new Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(packageName);
                if (includeStopped) {
                    bcIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                }
                am.broadcastIntentWithFeature(null, null, bcIntent, null, null, 0, null, null,
                        requiredPermissions, null, android.app.AppOpsManager.OP_NONE,
                        bOptions.toBundle(), false, false, userId);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
            int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        PackageSetting pkgSetting;
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                true /* checkShell */, "setApplicationHiddenSetting for user " + userId);

        if (hidden && isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Not hiding package " + packageName + ": has active device admin");
            return false;
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            boolean sendAdded = false;
            boolean sendRemoved = false;
            // writer
            synchronized (mLock) {
                pkgSetting = mSettings.getPackageLPr(packageName);
                if (pkgSetting == null) {
                    return false;
                }
                if (shouldFilterApplicationLocked(pkgSetting, callingUid, userId)) {
                    return false;
                }
                // Do not allow "android" is being disabled
                if ("android".equals(packageName)) {
                    Slog.w(TAG, "Cannot hide package: android");
                    return false;
                }
                // Cannot hide static shared libs as they are considered
                // a part of the using app (emulating static linking). Also
                // static libs are installed always on internal storage.
                AndroidPackage pkg = mPackages.get(packageName);
                if (pkg != null && pkg.getStaticSharedLibName() != null) {
                    Slog.w(TAG, "Cannot hide package: " + packageName
                            + " providing static shared library: "
                            + pkg.getStaticSharedLibName());
                    return false;
                }
                // Only allow protected packages to hide themselves.
                if (hidden && !UserHandle.isSameApp(callingUid, pkgSetting.getAppId())
                        && mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                    Slog.w(TAG, "Not hiding protected package: " + packageName);
                    return false;
                }

                if (pkgSetting.getHidden(userId) != hidden) {
                    pkgSetting.setHidden(hidden, userId);
                    mSettings.writePackageRestrictionsLPr(userId);
                    if (hidden) {
                        sendRemoved = true;
                    } else {
                        sendAdded = true;
                    }
                }
            }
            if (sendAdded) {
                sendPackageAddedForUser(packageName, pkgSetting, userId, DataLoaderType.NONE);
                return true;
            }
            if (sendRemoved) {
                killApplication(packageName, pkgSetting.getAppId(), userId,
                        "hiding pkg");
                sendApplicationHiddenForUser(packageName, pkgSetting, userId);
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return false;
    }

    @Override
    public void setSystemAppHiddenUntilInstalled(String packageName, boolean hidden) {
        final int callingUid = Binder.getCallingUid();
        final boolean calledFromSystemOrPhone = callingUid == Process.PHONE_UID
                || callingUid == Process.SYSTEM_UID;
        if (!calledFromSystemOrPhone) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "setSystemAppHiddenUntilInstalled");
        }

        synchronized (mLock) {
            final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
            if (pkgSetting == null || !pkgSetting.isSystem()) {
                return;
            }
            if (pkgSetting.getPkg().isCoreApp() && !calledFromSystemOrPhone) {
                throw new SecurityException("Only system or phone callers can modify core apps");
            }
            pkgSetting.getPkgState().setHiddenUntilInstalled(hidden);
            final PackageSetting disabledPs = mSettings.getDisabledSystemPkgLPr(packageName);
            if (disabledPs == null) {
                return;
            }
            disabledPs.getPkgState().setHiddenUntilInstalled(hidden);
        }
    }

    @Override
    public boolean setSystemAppInstallState(String packageName, boolean installed, int userId) {
        final int callingUid = Binder.getCallingUid();
        final boolean calledFromSystemOrPhone = callingUid == Process.PHONE_UID
                || callingUid == Process.SYSTEM_UID;
        if (!calledFromSystemOrPhone) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                    "setSystemAppHiddenUntilInstalled");
        }

        synchronized (mLock) {
            final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
            // The target app should always be in system
            if (pkgSetting == null || !pkgSetting.isSystem()) {
                return false;
            }
            if (pkgSetting.getPkg().isCoreApp() && !calledFromSystemOrPhone) {
                throw new SecurityException("Only system or phone callers can modify core apps");
            }
            // Check if the install state is the same
            if (pkgSetting.getInstalled(userId) == installed) {
                return false;
            }
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            if (installed) {
                // install the app from uninstalled state
                installExistingPackageAsUser(
                        packageName,
                        userId,
                        PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS,
                        PackageManager.INSTALL_REASON_DEVICE_SETUP,
                        null);
                return true;
            }

            // uninstall the app from installed state
            deletePackageVersioned(
                    new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    new LegacyPackageDeleteObserver(null).getBinder(),
                    userId,
                    PackageManager.DELETE_SYSTEM_APP);
            return true;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void sendApplicationHiddenForUser(String packageName, PackageSetting pkgSetting,
            int userId) {
        final PackageRemovedInfo info = new PackageRemovedInfo(this);
        info.mRemovedPackage = packageName;
        info.mInstallerPackageName = pkgSetting.getInstallSource().installerPackageName;
        info.mRemovedUsers = new int[] {userId};
        info.mBroadcastUsers = new int[] {userId};
        info.mUid = UserHandle.getUid(userId, pkgSetting.getAppId());
        info.sendPackageRemovedBroadcasts(true /*killApp*/, false /*removedBySystem*/);
    }

    private void sendDistractingPackagesChanged(String[] pkgList, int[] uidList, int userId,
            int distractionFlags) {
        final Bundle extras = new Bundle(3);
        extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgList);
        extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidList);
        extras.putInt(Intent.EXTRA_DISTRACTION_RESTRICTIONS, distractionFlags);
        sendPackageBroadcast(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED, null, extras,
                Intent.FLAG_RECEIVER_REGISTERED_ONLY, null, null, new int[]{userId}, null, null,
                null);
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void sendPackagesSuspendedForUser(String intent, String[] pkgList, int[] uidList, int userId) {
        final List<List<String>> pkgsToSend = new ArrayList(pkgList.length);
        final List<IntArray> uidsToSend = new ArrayList(pkgList.length);
        final List<SparseArray<int[]>> allowListsToSend = new ArrayList(pkgList.length);
        final int[] userIds = new int[] {userId};
        // Get allow lists for the pkg in the pkgList. Merge into the existed pkgs and uids if
        // allow lists are the same.
        synchronized (mLock) {
            for (int i = 0; i < pkgList.length; i++) {
                final String pkgName = pkgList[i];
                final int uid = uidList[i];
                SparseArray<int[]> allowList = mAppsFilter.getVisibilityAllowList(
                        getPackageSettingInternal(pkgName, Process.SYSTEM_UID),
                        userIds, mSettings.getPackagesLocked());
                if (allowList == null) {
                    allowList = new SparseArray<>(0);
                }
                boolean merged = false;
                for (int j = 0; j < allowListsToSend.size(); j++) {
                    if (Arrays.equals(allowListsToSend.get(j).get(userId), allowList.get(userId))) {
                        pkgsToSend.get(j).add(pkgName);
                        uidsToSend.get(j).add(uid);
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    pkgsToSend.add(new ArrayList<>(Arrays.asList(pkgName)));
                    uidsToSend.add(IntArray.wrap(new int[] {uid}));
                    allowListsToSend.add(allowList);
                }
            }
        }

        for (int i = 0; i < pkgsToSend.size(); i++) {
            final Bundle extras = new Bundle(3);
            extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST,
                    pkgsToSend.get(i).toArray(new String[pkgsToSend.get(i).size()]));
            extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidsToSend.get(i).toArray());
            final SparseArray<int[]> allowList = allowListsToSend.get(i).size() == 0
                    ? null : allowListsToSend.get(i);
            sendPackageBroadcast(intent, null, extras, Intent.FLAG_RECEIVER_REGISTERED_ONLY, null,
                    null, userIds, null, allowList, null);
        }
    }

    /**
     * Returns true if application is not found or there was an error. Otherwise it returns
     * the hidden state of the package for the given user.
     */
    @Override
    public boolean getApplicationHiddenSettingAsUser(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "getApplicationHidden for user " + userId);
        PackageSetting ps;
        final long callingId = Binder.clearCallingIdentity();
        try {
            // writer
            synchronized (mLock) {
                ps = mSettings.getPackageLPr(packageName);
                if (ps == null) {
                    return true;
                }
                if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    return true;
                }
                return ps.getHidden(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * @hide
     */
    @Override
    public int installExistingPackageAsUser(String packageName, int userId, int installFlags,
            int installReason, List<String> whiteListedPermissions) {
        return installExistingPackageAsUser(packageName, userId, installFlags, installReason,
                whiteListedPermissions, null);
    }

    int installExistingPackageAsUser(@Nullable String packageName, @UserIdInt int userId,
            @PackageManager.InstallFlags int installFlags,
            @PackageManager.InstallReason int installReason,
            @Nullable List<String> allowlistedRestrictedPermissions,
            @Nullable IntentSender intentSender) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "installExistingPackageAsUser package=" + packageName + " userId=" + userId
                    + " installFlags=" + installFlags + " installReason=" + installReason
                    + " allowlistedRestrictedPermissions=" + allowlistedRestrictedPermissions);
        }

        final int callingUid = Binder.getCallingUid();
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES)
                != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.INSTALL_EXISTING_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Neither user " + callingUid + " nor current process has "
                    + android.Manifest.permission.INSTALL_PACKAGES + ".");
        }
        PackageSetting pkgSetting;
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                true /* checkShell */, "installExistingPackage for user " + userId);
        if (isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            return PackageManager.INSTALL_FAILED_USER_RESTRICTED;
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            boolean installed = false;
            final boolean instantApp =
                    (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
            final boolean fullApp =
                    (installFlags & PackageManager.INSTALL_FULL_APP) != 0;

            // writer
            synchronized (mLock) {
                pkgSetting = mSettings.getPackageLPr(packageName);
                if (pkgSetting == null) {
                    return PackageManager.INSTALL_FAILED_INVALID_URI;
                }
                if (!canViewInstantApps(callingUid, UserHandle.getUserId(callingUid))) {
                    // only allow the existing package to be used if it's installed as a full
                    // application for at least one user
                    boolean installAllowed = false;
                    for (int checkUserId : mUserManager.getUserIds()) {
                        installAllowed = !pkgSetting.getInstantApp(checkUserId);
                        if (installAllowed) {
                            break;
                        }
                    }
                    if (!installAllowed) {
                        return PackageManager.INSTALL_FAILED_INVALID_URI;
                    }
                }
                if (!pkgSetting.getInstalled(userId)) {
                    pkgSetting.setInstalled(true, userId);
                    pkgSetting.setHidden(false, userId);
                    pkgSetting.setInstallReason(installReason, userId);
                    pkgSetting.setUninstallReason(PackageManager.UNINSTALL_REASON_UNKNOWN, userId);
                    mSettings.writePackageRestrictionsLPr(userId);
                    mSettings.writeKernelMappingLPr(pkgSetting);
                    installed = true;
                } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                    // upgrade app from instant to full; we don't allow app downgrade
                    installed = true;
                }
                setInstantAppForUser(mInjector, pkgSetting, userId, instantApp, fullApp);
            }

            if (installed) {
                if (pkgSetting.getPkg() != null) {
                    final PermissionManagerServiceInternal.PackageInstalledParams.Builder
                            permissionParamsBuilder =
                            new PermissionManagerServiceInternal.PackageInstalledParams.Builder();
                    if ((installFlags & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS)
                            != 0) {
                        permissionParamsBuilder.setAllowlistedRestrictedPermissions(
                                pkgSetting.getPkg().getRequestedPermissions());
                    }
                    mPermissionManager.onPackageInstalled(pkgSetting.getPkg(),
                            Process.INVALID_UID /* previousAppId */,
                            permissionParamsBuilder.build(), userId);
                }

                if (pkgSetting.getPkg() != null) {
                    synchronized (mInstallLock) {
                        // We don't need to freeze for a brand new install
                        prepareAppDataAfterInstallLIF(pkgSetting.getPkg());
                    }
                }
                sendPackageAddedForUser(packageName, pkgSetting, userId, DataLoaderType.NONE);
                synchronized (mLock) {
                    updateSequenceNumberLP(pkgSetting, new int[]{ userId });
                }
                // start async restore with no post-install since we finish install here
                PackageInstalledInfo res = new PackageInstalledInfo(
                        PackageManager.INSTALL_SUCCEEDED);
                res.mPkg = pkgSetting.getPkg();
                res.mNewUsers = new int[]{ userId };

                PostInstallData postInstallData =
                        new PostInstallData(null, res, () -> {
                            restorePermissionsAndUpdateRolesForNewUserInstall(packageName,
                                    userId);
                            if (intentSender != null) {
                                onRestoreComplete(res.mReturnCode, mContext, intentSender);
                            }
                        });
                restoreAndPostInstall(userId, res, postInstallData);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    static void onRestoreComplete(int returnCode, Context context, IntentSender target) {
        Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                PackageManager.installStatusToPublicStatus(returnCode));
        try {
            target.sendIntent(context, 0, fillIn, null, null);
        } catch (SendIntentException ignored) {
        }
    }

    static void setInstantAppForUser(Injector injector, PackageSetting pkgSetting,
            int userId, boolean instantApp, boolean fullApp) {
        // no state specified; do nothing
        if (!instantApp && !fullApp) {
            return;
        }
        if (userId != UserHandle.USER_ALL) {
            if (instantApp && !pkgSetting.getInstantApp(userId)) {
                pkgSetting.setInstantApp(true /*instantApp*/, userId);
            } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                pkgSetting.setInstantApp(false /*instantApp*/, userId);
            }
        } else {
            for (int currentUserId : injector.getUserManagerInternal().getUserIds()) {
                if (instantApp && !pkgSetting.getInstantApp(currentUserId)) {
                    pkgSetting.setInstantApp(true /*instantApp*/, currentUserId);
                } else if (fullApp && pkgSetting.getInstantApp(currentUserId)) {
                    pkgSetting.setInstantApp(false /*instantApp*/, currentUserId);
                }
            }
        }
    }

    boolean isUserRestricted(int userId, String restrictionKey) {
        Bundle restrictions = mUserManager.getUserRestrictions(userId);
        if (restrictions.getBoolean(restrictionKey, false)) {
            Log.w(TAG, "User is restricted: " + restrictionKey);
            return true;
        }
        return false;
    }

    @Override
    public String[] setDistractingPackageRestrictionsAsUser(String[] packageNames,
            int restrictionFlags, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SUSPEND_APPS,
                "setDistractingPackageRestrictionsAsUser");

        final int callingUid = Binder.getCallingUid();
        if (callingUid != Process.ROOT_UID && callingUid != Process.SYSTEM_UID
                && UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid + " cannot call for user "
                    + userId);
        }
        Objects.requireNonNull(packageNames, "packageNames cannot be null");
        if (restrictionFlags != 0 && !isSuspendAllowedForUser(userId)) {
            Slog.w(TAG, "Cannot restrict packages due to restrictions on user " + userId);
            return packageNames;
        }

        final List<String> changedPackagesList = new ArrayList<>(packageNames.length);
        final IntArray changedUids = new IntArray(packageNames.length);
        final List<String> unactionedPackages = new ArrayList<>(packageNames.length);
        final boolean[] canRestrict = (restrictionFlags != 0) ? canSuspendPackageForUserInternal(
                packageNames, userId) : null;

        for (int i = 0; i < packageNames.length; i++) {
            final String packageName = packageNames[i];
            final PackageSetting pkgSetting;
            synchronized (mLock) {
                pkgSetting = mSettings.getPackageLPr(packageName);
                if (pkgSetting == null
                        || shouldFilterApplicationLocked(pkgSetting, callingUid, userId)) {
                    Slog.w(TAG, "Could not find package setting for package: " + packageName
                            + ". Skipping...");
                    unactionedPackages.add(packageName);
                    continue;
                }
            }
            if (canRestrict != null && !canRestrict[i]) {
                unactionedPackages.add(packageName);
                continue;
            }
            synchronized (mLock) {
                final int oldDistractionFlags = pkgSetting.getDistractionFlags(userId);
                if (restrictionFlags != oldDistractionFlags) {
                    pkgSetting.setDistractionFlags(restrictionFlags, userId);
                    changedPackagesList.add(packageName);
                    changedUids.add(UserHandle.getUid(userId, pkgSetting.getAppId()));
                }
            }
        }

        if (!changedPackagesList.isEmpty()) {
            final String[] changedPackages = changedPackagesList.toArray(
                    new String[changedPackagesList.size()]);
            sendDistractingPackagesChanged(changedPackages, changedUids.toArray(), userId,
                    restrictionFlags);
            synchronized (mLock) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
        return unactionedPackages.toArray(new String[0]);
    }

    private void enforceCanSetPackagesSuspendedAsUser(String callingPackage, int callingUid,
            int userId, String callingMethod) {
        if (callingUid == Process.ROOT_UID
                // Need to compare app-id to allow system dialogs access on secondary users
                || UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return;
        }

        final String ownerPackage = mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(userId);
        if (ownerPackage != null) {
            final int ownerUid = getPackageUid(ownerPackage, 0, userId);
            if (ownerUid == callingUid) {
                return;
            }
        }

        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.SUSPEND_APPS,
                callingMethod);

        final int packageUid = getPackageUid(callingPackage, 0, userId);
        final boolean allowedPackageUid = packageUid == callingUid;
        // TODO(b/139383163): remove special casing for shell and enforce INTERACT_ACROSS_USERS_FULL
        final boolean allowedShell = callingUid == SHELL_UID
                && UserHandle.isSameApp(packageUid, callingUid);

        if (!allowedShell && !allowedPackageUid) {
            throw new SecurityException("Calling package " + callingPackage + " in user "
                    + userId + " does not belong to calling uid " + callingUid);
        }
    }

    @Override
    public String[] setPackagesSuspendedAsUser(String[] packageNames, boolean suspended,
            PersistableBundle appExtras, PersistableBundle launcherExtras,
            SuspendDialogInfo dialogInfo, String callingPackage, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCanSetPackagesSuspendedAsUser(callingPackage, callingUid, userId,
                "setPackagesSuspendedAsUser");

        if (ArrayUtils.isEmpty(packageNames)) {
            return packageNames;
        }
        if (suspended && !isSuspendAllowedForUser(userId)) {
            Slog.w(TAG, "Cannot suspend due to restrictions on user " + userId);
            return packageNames;
        }

        final List<String> changedPackagesList = new ArrayList<>(packageNames.length);
        final IntArray changedUids = new IntArray(packageNames.length);
        final List<String> modifiedPackagesList = new ArrayList<>(packageNames.length);
        final IntArray modifiedUids = new IntArray(packageNames.length);
        final List<String> unactionedPackages = new ArrayList<>(packageNames.length);
        final boolean[] canSuspend = suspended ? canSuspendPackageForUserInternal(packageNames,
                userId) : null;

        for (int i = 0; i < packageNames.length; i++) {
            final String packageName = packageNames[i];
            if (callingPackage.equals(packageName)) {
                Slog.w(TAG, "Calling package: " + callingPackage + " trying to "
                        + (suspended ? "" : "un") + "suspend itself. Ignoring");
                unactionedPackages.add(packageName);
                continue;
            }
            final PackageSetting pkgSetting;
            synchronized (mLock) {
                pkgSetting = mSettings.getPackageLPr(packageName);
                if (pkgSetting == null
                        || shouldFilterApplicationLocked(pkgSetting, callingUid, userId)) {
                    Slog.w(TAG, "Could not find package setting for package: " + packageName
                            + ". Skipping suspending/un-suspending.");
                    unactionedPackages.add(packageName);
                    continue;
                }
            }
            if (canSuspend != null && !canSuspend[i]) {
                unactionedPackages.add(packageName);
                continue;
            }
            final boolean packageUnsuspended;
            final boolean packageModified;
            synchronized (mLock) {
                if (suspended) {
                    packageModified = pkgSetting.addOrUpdateSuspension(callingPackage,
                            dialogInfo, appExtras, launcherExtras, userId);
                } else {
                    packageModified = pkgSetting.removeSuspension(callingPackage, userId);
                }
                packageUnsuspended = !suspended && !pkgSetting.getSuspended(userId);
            }
            if (suspended || packageUnsuspended) {
                changedPackagesList.add(packageName);
                changedUids.add(UserHandle.getUid(userId, pkgSetting.getAppId()));
            }
            if (packageModified) {
                modifiedPackagesList.add(packageName);
                modifiedUids.add(UserHandle.getUid(userId, pkgSetting.getAppId()));
            }
        }

        if (!changedPackagesList.isEmpty()) {
            final String[] changedPackages = changedPackagesList.toArray(new String[0]);
            sendPackagesSuspendedForUser(
                    suspended ? Intent.ACTION_PACKAGES_SUSPENDED
                              : Intent.ACTION_PACKAGES_UNSUSPENDED,
                    changedPackages, changedUids.toArray(), userId);
            sendMyPackageSuspendedOrUnsuspended(changedPackages, suspended, userId);
            synchronized (mLock) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
        // Send the suspension changed broadcast to ensure suspension state is not stale.
        if (!modifiedPackagesList.isEmpty()) {
            sendPackagesSuspendedForUser(Intent.ACTION_PACKAGES_SUSPENSION_CHANGED,
                    modifiedPackagesList.toArray(new String[0]), modifiedUids.toArray(), userId);
        }
        return unactionedPackages.toArray(new String[0]);
    }

    @Override
    public Bundle getSuspendedPackageAppExtras(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        if (getPackageUid(packageName, 0, userId) != callingUid) {
            throw new SecurityException("Calling package " + packageName
                    + " does not belong to calling uid " + callingUid);
        }
        return getSuspendedPackageAppExtrasInternal(packageName, userId);
    }

    private Bundle getSuspendedPackageAppExtrasInternal(String packageName, int userId) {
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null) {
                return null;
            }
            final PackageUserState pus = ps.readUserState(userId);
            final Bundle allExtras = new Bundle();
            if (pus.suspended) {
                for (int i = 0; i < pus.suspendParams.size(); i++) {
                    final PackageUserState.SuspendParams params = pus.suspendParams.valueAt(i);
                    if (params != null && params.appExtras != null) {
                        allExtras.putAll(params.appExtras);
                    }
                }
            }
            return (allExtras.size() > 0) ? allExtras : null;
        }
    }

    private void sendMyPackageSuspendedOrUnsuspended(String[] affectedPackages, boolean suspended,
            int userId) {
        final String action = suspended
                ? Intent.ACTION_MY_PACKAGE_SUSPENDED
                : Intent.ACTION_MY_PACKAGE_UNSUSPENDED;
        mHandler.post(() -> {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) {
                Slog.wtf(TAG, "IActivityManager null. Cannot send MY_PACKAGE_ "
                        + (suspended ? "" : "UN") + "SUSPENDED broadcasts");
                return;
            }
            final int[] targetUserIds = new int[] {userId};
            for (String packageName : affectedPackages) {
                final Bundle appExtras = suspended
                        ? getSuspendedPackageAppExtrasInternal(packageName, userId)
                        : null;
                final Bundle intentExtras;
                if (appExtras != null) {
                    intentExtras = new Bundle(1);
                    intentExtras.putBundle(Intent.EXTRA_SUSPENDED_PACKAGE_EXTRAS, appExtras);
                } else {
                    intentExtras = null;
                }
                doSendBroadcast(action, null, intentExtras,
                        Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND, packageName, null,
                        targetUserIds, false, null, null);
            }
        });
    }

    @Override
    public boolean isPackageSuspendedForUser(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "isPackageSuspendedForUser for user " + userId);
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null || shouldFilterApplicationLocked(ps, callingUid, userId)) {
                throw new IllegalArgumentException("Unknown target package: " + packageName);
            }
            return ps.getSuspended(userId);
        }
    }

    void unsuspendForSuspendingPackage(String suspendingPackage, int userId) {
        final String[] allPackages;
        synchronized (mLock) {
            allPackages = mPackages.keySet().toArray(new String[mPackages.size()]);
        }
        removeSuspensionsBySuspendingPackage(allPackages, suspendingPackage::equals, userId);
    }

    boolean isSuspendingAnyPackages(String suspendingPackage, int userId) {
        synchronized (mLock) {
            for (final PackageSetting ps : mSettings.getPackagesLocked().values()) {
                if (ps.isSuspendedBy(suspendingPackage, userId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Removes any suspensions on given packages that were added by packages that pass the given
     * predicate.
     *
     * <p> Caller must flush package restrictions if it cares about immediate data consistency.
     *
     * @param packagesToChange The packages on which the suspension are to be removed.
     * @param suspendingPackagePredicate A predicate identifying the suspending packages whose
     *                                   suspensions will be removed.
     * @param userId The user for which the changes are taking place.
     */
    void removeSuspensionsBySuspendingPackage(String[] packagesToChange,
            Predicate<String> suspendingPackagePredicate, int userId) {
        final List<String> unsuspendedPackages = new ArrayList<>();
        final IntArray unsuspendedUids = new IntArray();
        synchronized (mLock) {
            for (String packageName : packagesToChange) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                if (ps != null && ps.getSuspended(userId)) {
                    ps.removeSuspension(suspendingPackagePredicate, userId);
                    if (!ps.getSuspended(userId)) {
                        unsuspendedPackages.add(ps.getPackageName());
                        unsuspendedUids.add(UserHandle.getUid(userId, ps.getAppId()));
                    }
                }
            }
            scheduleWritePackageRestrictionsLocked(userId);
        }
        if (!unsuspendedPackages.isEmpty()) {
            final String[] packageArray = unsuspendedPackages.toArray(
                    new String[unsuspendedPackages.size()]);
            sendMyPackageSuspendedOrUnsuspended(packageArray, false, userId);
            sendPackagesSuspendedForUser(Intent.ACTION_PACKAGES_UNSUSPENDED,
                    packageArray, unsuspendedUids.toArray(), userId);
        }
    }

    void removeAllDistractingPackageRestrictions(int userId) {
        final String[] allPackages;
        synchronized (mLock) {
            allPackages = mPackages.keySet().toArray(new String[mPackages.size()]);
        }
        removeDistractingPackageRestrictions(allPackages, userId);
    }

    /**
     * Removes any {@link android.content.pm.PackageManager.DistractionRestriction restrictions}
     * set on given packages.
     *
     * <p> Caller must flush package restrictions if it cares about immediate data consistency.
     *
     * @param packagesToChange The packages on which restrictions are to be removed.
     * @param userId the user for which changes are taking place.
     */
    void removeDistractingPackageRestrictions(String[] packagesToChange, int userId) {
        final List<String> changedPackages = new ArrayList<>();
        final IntArray changedUids = new IntArray();
        synchronized (mLock) {
            for (String packageName : packagesToChange) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                if (ps != null && ps.getDistractionFlags(userId) != 0) {
                    ps.setDistractionFlags(0, userId);
                    changedPackages.add(ps.getPackageName());
                    changedUids.add(UserHandle.getUid(userId, ps.getAppId()));
                }
            }
            if (!changedPackages.isEmpty()) {
                final String[] packageArray = changedPackages.toArray(
                        new String[changedPackages.size()]);
                sendDistractingPackagesChanged(packageArray, changedUids.toArray(), userId, 0);
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    private boolean isCallerDeviceOrProfileOwner(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SYSTEM_UID) {
            return true;
        }
        final String ownerPackage = mProtectedPackages.getDeviceOwnerOrProfileOwnerPackage(userId);
        if (ownerPackage != null) {
            return callingUid == getPackageUidInternal(ownerPackage, 0, userId, callingUid);
        }
        return false;
    }

    private boolean isSuspendAllowedForUser(int userId) {
        return isCallerDeviceOrProfileOwner(userId)
                || (!mUserManager.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL, userId)
                && !mUserManager.hasUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS, userId));
    }

    @Override
    public String[] getUnsuspendablePackagesForUser(String[] packageNames, int userId) {
        Objects.requireNonNull(packageNames, "packageNames cannot be null");
        mContext.enforceCallingOrSelfPermission(Manifest.permission.SUSPEND_APPS,
                "getUnsuspendablePackagesForUser");
        final int callingUid = Binder.getCallingUid();
        if (UserHandle.getUserId(callingUid) != userId) {
            throw new SecurityException("Calling uid " + callingUid
                    + " cannot query getUnsuspendablePackagesForUser for user " + userId);
        }
        if (!isSuspendAllowedForUser(userId)) {
            Slog.w(TAG, "Cannot suspend due to restrictions on user " + userId);
            return packageNames;
        }
        final ArraySet<String> unactionablePackages = new ArraySet<>();
        final boolean[] canSuspend = canSuspendPackageForUserInternal(packageNames, userId);
        for (int i = 0; i < packageNames.length; i++) {
            if (!canSuspend[i]) {
                unactionablePackages.add(packageNames[i]);
                continue;
            }
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageNames[i]);
                if (ps == null || shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    Slog.w(TAG, "Could not find package setting for package: " + packageNames[i]);
                    unactionablePackages.add(packageNames[i]);
                }
            }
        }
        return unactionablePackages.toArray(new String[unactionablePackages.size()]);
    }

    /**
     * Returns an array of booleans, such that the ith boolean denotes whether the ith package can
     * be suspended or not.
     *
     * @param packageNames  The package names to check suspendability for.
     * @param userId The user to check in
     * @return An array containing results of the checks
     */
    @NonNull
    private boolean[] canSuspendPackageForUserInternal(@NonNull String[] packageNames, int userId) {
        final boolean[] canSuspend = new boolean[packageNames.length];
        final boolean isCallerOwner = isCallerDeviceOrProfileOwner(userId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            final String activeLauncherPackageName = mDefaultAppProvider.getDefaultHome(userId);
            final String dialerPackageName = mDefaultAppProvider.getDefaultDialer(userId);
            for (int i = 0; i < packageNames.length; i++) {
                canSuspend[i] = false;
                final String packageName = packageNames[i];

                if (isPackageDeviceAdmin(packageName, userId)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": has an active device admin");
                    continue;
                }
                if (packageName.equals(activeLauncherPackageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": contains the active launcher");
                    continue;
                }
                if (packageName.equals(mRequiredInstallerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for package installation");
                    continue;
                }
                if (packageName.equals(mRequiredUninstallerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for package uninstallation");
                    continue;
                }
                if (packageName.equals(mRequiredVerifierPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for package verification");
                    continue;
                }
                if (packageName.equals(dialerPackageName)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": is the default dialer");
                    continue;
                }
                if (packageName.equals(mRequiredPermissionControllerPackage)) {
                    Slog.w(TAG, "Cannot suspend package \"" + packageName
                            + "\": required for permissions management");
                    continue;
                }
                synchronized (mLock) {
                    if (mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                        Slog.w(TAG, "Cannot suspend package \"" + packageName
                                + "\": protected package");
                        continue;
                    }
                    if (!isCallerOwner && mSettings.getBlockUninstallLPr(userId, packageName)) {
                        Slog.w(TAG, "Cannot suspend package \"" + packageName
                                + "\": blocked by admin");
                        continue;
                    }

                    // Cannot suspend static shared libs as they are considered
                    // a part of the using app (emulating static linking). Also
                    // static libs are installed always on internal storage.
                    AndroidPackage pkg = mPackages.get(packageName);
                    if (pkg != null && pkg.isStaticSharedLibrary()) {
                        Slog.w(TAG, "Cannot suspend package: " + packageName
                                + " providing static shared library: "
                                + pkg.getStaticSharedLibName());
                        continue;
                    }
                }
                if (PLATFORM_PACKAGE_NAME.equals(packageName)) {
                    Slog.w(TAG, "Cannot suspend the platform package: " + packageName);
                    continue;
                }
                canSuspend[i] = true;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return canSuspend;
    }

    @Override
    public void verifyPendingInstall(int id, int verificationCode) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can verify applications");

        final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
        final PackageVerificationResponse response = new PackageVerificationResponse(
                verificationCode, Binder.getCallingUid());
        msg.arg1 = id;
        msg.obj = response;
        mHandler.sendMessage(msg);
    }

    @Override
    public void extendVerificationTimeout(int id, int verificationCodeAtTimeout,
            long millisecondsToDelay) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can extend verification timeouts");

        final PackageVerificationState state = mPendingVerification.get(id);
        final PackageVerificationResponse response = new PackageVerificationResponse(
                verificationCodeAtTimeout, Binder.getCallingUid());

        if (millisecondsToDelay > PackageManager.MAXIMUM_VERIFICATION_TIMEOUT) {
            millisecondsToDelay = PackageManager.MAXIMUM_VERIFICATION_TIMEOUT;
        }
        if (millisecondsToDelay < 0) {
            millisecondsToDelay = 0;
        }

        if ((state != null) && !state.timeoutExtended()) {
            state.extendTimeout();

            final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
            msg.arg1 = id;
            msg.obj = response;
            mHandler.sendMessageDelayed(msg, millisecondsToDelay);
        }
    }

    private void setEnableRollbackCode(int token, int enableRollbackCode) {
        final Message msg = mHandler.obtainMessage(ENABLE_ROLLBACK_STATUS);
        msg.arg1 = token;
        msg.arg2 = enableRollbackCode;
        mHandler.sendMessage(msg);
    }

    @Override
    public void finishPackageInstall(int token, boolean didLaunch) {
        enforceSystemOrRoot("Only the system is allowed to finish installs");

        if (DEBUG_INSTALL) {
            Slog.v(TAG, "BM finishing package install for " + token);
        }
        Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, "restore", token);

        final Message msg = mHandler.obtainMessage(POST_INSTALL, token, didLaunch ? 1 : 0);
        mHandler.sendMessage(msg);
    }

    @Deprecated
    @Override
    public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains) {
        DomainVerificationProxyV1.queueLegacyVerifyResult(mContext, mDomainVerificationConnection,
                id, verificationCode, failedDomains, Binder.getCallingUid());
    }

    @Deprecated
    @Override
    public int getIntentVerificationStatus(String packageName, int userId) {
        return mDomainVerificationManager.getLegacyState(packageName, userId);
    }

    @Deprecated
    @Override
    public boolean updateIntentVerificationStatus(String packageName, int status, int userId) {
        return mDomainVerificationManager.setLegacyUserState(packageName, userId, status);
    }

    @Deprecated
    @Override
    public @NonNull ParceledListSlice<IntentFilterVerificationInfo> getIntentFilterVerifications(
            String packageName) {
        return ParceledListSlice.emptyList();
    }

    @Override
    public @NonNull ParceledListSlice<IntentFilter> getAllIntentFilters(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return ParceledListSlice.emptyList();
        }
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        synchronized (mLock) {
            AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null || ArrayUtils.isEmpty(pkg.getActivities())) {
                return ParceledListSlice.emptyList();
            }
            final PackageSetting ps = getPackageSetting(pkg.getPackageName());
            if (ps == null) {
                return ParceledListSlice.emptyList();
            }
            if (shouldFilterApplicationLocked(ps, callingUid, callingUserId)) {
                return ParceledListSlice.emptyList();
            }
            final int count = ArrayUtils.size(pkg.getActivities());
            ArrayList<IntentFilter> result = new ArrayList<>();
            for (int n=0; n<count; n++) {
                ParsedActivity activity = pkg.getActivities().get(n);
                if (activity.getIntents() != null && activity.getIntents().size() > 0) {
                    result.addAll(activity.getIntents());
                }
            }
            return new ParceledListSlice<IntentFilter>(result) {
                @Override
                protected void writeElement(IntentFilter parcelable, Parcel dest, int callFlags) {
                    parcelable.writeToParcel(dest, callFlags);
                }

                @Override
                protected void writeParcelableCreator(IntentFilter parcelable, Parcel dest) {
                    // All Parcel#writeParcelableCreator does is serialize the class name to
                    // access via reflection to grab its CREATOR. This does that manually, pointing
                    // to the parent IntentFilter so that all of the subclass fields are ignored.
                    dest.writeString(IntentFilter.class.getName());
                }
            };
        }
    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        // writer
        synchronized (mLock) {
            PackageSetting targetPackageSetting = mSettings.getPackageLPr(targetPackage);
            if (targetPackageSetting == null
                    || shouldFilterApplicationLocked(
                            targetPackageSetting, callingUid, callingUserId)) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }

            PackageSetting installerPackageSetting;
            if (installerPackageName != null) {
                installerPackageSetting = mSettings.getPackageLPr(installerPackageName);
                if (installerPackageSetting == null
                        || shouldFilterApplicationLocked(
                                installerPackageSetting, callingUid, callingUserId)) {
                    throw new IllegalArgumentException("Unknown installer package: "
                            + installerPackageName);
                }
            } else {
                installerPackageSetting = null;
            }

            Signature[] callerSignature;
            final int appId = UserHandle.getAppId(callingUid);
            final Object obj = mSettings.getSettingLPr(appId);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    callerSignature =
                            ((SharedUserSetting) obj).signatures.mSigningDetails.getSignatures();
                } else if (obj instanceof PackageSetting) {
                    callerSignature =
                            ((PackageSetting) obj).getSigningDetails().getSignatures();
                } else {
                    throw new SecurityException("Bad object " + obj + " for uid " + callingUid);
                }
            } else {
                throw new SecurityException("Unknown calling UID: " + callingUid);
            }

            // Verify: can't set installerPackageName to a package that is
            // not signed with the same cert as the caller.
            if (installerPackageSetting != null) {
                if (compareSignatures(callerSignature,
                        installerPackageSetting.getSigningDetails().getSignatures())
                        != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException(
                            "Caller does not have same cert as new installer package "
                            + installerPackageName);
                }
            }

            // Verify: if target already has an installer package, it must
            // be signed with the same cert as the caller.
            String targetInstallerPackageName =
                    targetPackageSetting.getInstallSource().installerPackageName;
            PackageSetting targetInstallerPkgSetting = targetInstallerPackageName == null ? null :
                    mSettings.getPackageLPr(targetInstallerPackageName);

            if (targetInstallerPkgSetting != null) {
                if (compareSignatures(callerSignature,
                        targetInstallerPkgSetting.getSigningDetails().getSignatures())
                        != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException(
                            "Caller does not have same cert as old installer package "
                            + targetInstallerPackageName);
                }
            } else if (mContext.checkCallingOrSelfPermission(Manifest.permission.INSTALL_PACKAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                // This is probably an attempt to exploit vulnerability b/150857253 of taking
                // privileged installer permissions when the installer has been uninstalled or
                // was never set.
                EventLog.writeEvent(0x534e4554, "150857253", callingUid, "");

                final long binderToken = Binder.clearCallingIdentity();
                try {
                    if (mInjector.getCompatibility().isChangeEnabledByUid(
                            THROW_EXCEPTION_ON_REQUIRE_INSTALL_PACKAGES_TO_ADD_INSTALLER_PACKAGE,
                            callingUid)) {
                        throw new SecurityException("Neither user " + callingUid
                                + " nor current process has "
                                + Manifest.permission.INSTALL_PACKAGES);
                    } else {
                        // If change disabled, fail silently for backwards compatibility
                        return;
                    }
                } finally {
                    Binder.restoreCallingIdentity(binderToken);
                }
            }

            // Okay!
            targetPackageSetting.setInstallerPackageName(installerPackageName);
            mSettings.addInstallerPackageNames(targetPackageSetting.getInstallSource());
            mAppsFilter.addPackage(targetPackageSetting);
            scheduleWriteSettingsLocked();
        }
    }

    @Override
    public void setApplicationCategoryHint(String packageName, int categoryHint,
            String callerPackageName) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            throw new SecurityException("Instant applications don't have access to this method");
        }
        mInjector.getSystemService(AppOpsManager.class).checkPackage(Binder.getCallingUid(),
                callerPackageName);
        synchronized (mLock) {
            PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null || shouldFilterApplicationLocked(
                    ps, Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                throw new IllegalArgumentException("Unknown target package " + packageName);
            }
            if (!Objects.equals(callerPackageName, ps.getInstallSource().installerPackageName)) {
                throw new IllegalArgumentException("Calling package " + callerPackageName
                        + " is not installer for " + packageName);
            }

            if (ps.getCategoryOverride() != categoryHint) {
                ps.setCategoryOverride(categoryHint);
                scheduleWriteSettingsLocked();
            }
        }
    }

    /** @param data Post-install is performed only if this is non-null. */
    void restoreAndPostInstall(
            int userId, PackageInstalledInfo res, @Nullable PostInstallData data) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "restoreAndPostInstall userId=" + userId + " package=" + res.mPkg);
        }

        // A restore should be requested at this point if (a) the install
        // succeeded, (b) the operation is not an update.
        final boolean update = res.mRemovedInfo != null
                && res.mRemovedInfo.mRemovedPackage != null;
        boolean doRestore = !update && res.mPkg != null;

        // Set up the post-install work request bookkeeping.  This will be used
        // and cleaned up by the post-install event handling regardless of whether
        // there's a restore pass performed.  Token values are >= 1.
        int token;
        if (mNextInstallToken < 0) mNextInstallToken = 1;
        token = mNextInstallToken++;
        if (data != null) {
            mRunningInstalls.put(token, data);
        } else if (DEBUG_INSTALL) {
            Log.v(TAG, "No post-install required for " + token);
        }

        if (DEBUG_INSTALL) Log.v(TAG, "+ starting restore round-trip " + token);

        if (res.mReturnCode == PackageManager.INSTALL_SUCCEEDED && doRestore) {
            // Pass responsibility to the Backup Manager.  It will perform a
            // restore if appropriate, then pass responsibility back to the
            // Package Manager to run the post-install observer callbacks
            // and broadcasts.
            if (res.mFreezer != null) {
                res.mFreezer.close();
            }
            doRestore = performBackupManagerRestore(userId, token, res);
        }

        // If this is an update to a package that might be potentially downgraded, then we
        // need to check with the rollback manager whether there's any userdata that might
        // need to be snapshotted or restored for the package.
        //
        // TODO(narayan): Get this working for cases where userId == UserHandle.USER_ALL.
        if (res.mReturnCode == PackageManager.INSTALL_SUCCEEDED && !doRestore && update) {
            doRestore = performRollbackManagerRestore(userId, token, res, data);
        }

        if (!doRestore) {
            // No restore possible, or the Backup Manager was mysteriously not
            // available -- just fire the post-install work request directly.
            if (DEBUG_INSTALL) Log.v(TAG, "No restore - queue post-install for " + token);

            Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "postInstall", token);

            Message msg = mHandler.obtainMessage(POST_INSTALL, token, 0);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Perform Backup Manager restore for a given {@link PackageInstalledInfo}.
     * Returns whether the restore successfully completed.
     */
    private boolean performBackupManagerRestore(int userId, int token, PackageInstalledInfo res) {
        IBackupManager bm = IBackupManager.Stub.asInterface(
                ServiceManager.getService(Context.BACKUP_SERVICE));
        if (bm != null) {
            // For backwards compatibility as USER_ALL previously routed directly to USER_SYSTEM
            // in the BackupManager. USER_ALL is used in compatibility tests.
            if (userId == UserHandle.USER_ALL) {
                userId = UserHandle.USER_SYSTEM;
            }
            if (DEBUG_INSTALL) {
                Log.v(TAG, "token " + token + " to BM for possible restore for user " + userId);
            }
            Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "restore", token);
            try {
                if (bm.isUserReadyForBackup(userId)) {
                    bm.restoreAtInstallForUser(
                            userId, res.mPkg.getPackageName(), token);
                } else {
                    Slog.w(TAG, "User " + userId + " is not ready. Restore at install "
                            + "didn't take place.");
                    return false;
                }
            } catch (RemoteException e) {
                // can't happen; the backup manager is local
            } catch (Exception e) {
                Slog.e(TAG, "Exception trying to enqueue restore", e);
                return false;
            }
        } else {
            Slog.e(TAG, "Backup Manager not found!");
            return false;
        }
        return true;
    }

    /**
     * Perform Rollback Manager restore for a given {@link PackageInstalledInfo}.
     * Returns whether the restore successfully completed.
     */
    private boolean performRollbackManagerRestore(int userId, int token, PackageInstalledInfo res,
            PostInstallData data) {
        RollbackManagerInternal rm = mInjector.getLocalService(RollbackManagerInternal.class);

        final String packageName = res.mPkg.getPackageName();
        final int[] allUsers = mUserManager.getUserIds();
        final int[] installedUsers;

        final PackageSetting ps;
        int appId = -1;
        long ceDataInode = -1;
        synchronized (mLock) {
            ps = mSettings.getPackageLPr(packageName);
            if (ps != null) {
                appId = ps.getAppId();
                ceDataInode = ps.getCeDataInode(userId);
            }

            // NOTE: We ignore the user specified in the InstallParam because we know this is
            // an update, and hence need to restore data for all installed users.
            installedUsers = ps.queryInstalledUsers(allUsers, true);
        }

        boolean doSnapshotOrRestore = data != null && data.args != null
                && ((data.args.mInstallFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0
                || (data.args.mInstallFlags & PackageManager.INSTALL_REQUEST_DOWNGRADE) != 0);

        if (ps != null && doSnapshotOrRestore) {
            final String seInfo = AndroidPackageUtils.getSeInfo(res.mPkg, ps);
            rm.snapshotAndRestoreUserData(packageName, UserHandle.toUserHandles(installedUsers),
                    appId, ceDataInode, seInfo, token);
            return true;
        }
        return false;
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
                final PostInstallData data = mRunningInstalls.valueAt(i);
                if (data.res.mReturnCode != PackageManager.INSTALL_SUCCEEDED) {
                    continue;
                }
                if (packageName.equals(data.res.mPkg.getPackageName())) {
                    // right package; but is it for the right user?
                    for (int uIndex = 0; uIndex < data.res.mNewUsers.length; uIndex++) {
                        if (userId == data.res.mNewUsers[uIndex]) {
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
            final boolean isInstantApp = isInstantApp(packageName, userId);
            final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
            final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
            sendFirstLaunchBroadcast(packageName, installerPackage, userIds, instantUserIds);
        });
    }

    void sendFirstLaunchBroadcast(String pkgName, String installerPkg,
            int[] userIds, int[] instantUserIds) {
        sendPackageBroadcast(Intent.ACTION_PACKAGE_FIRST_LAUNCH, pkgName, null, 0,
                installerPkg, null, userIds, instantUserIds, null /* broadcastAllowList */, null);
    }

    /**
     * Create args that describe an existing installed package. Typically used
     * when cleaning up old installs, or used as a move source.
     */
    InstallArgs createInstallArgsForExisting(String codePath, String[] instructionSets) {
        return new FileInstallArgs(codePath, instructionSets, this);
    }

    @GuardedBy("mLock")
    Map<String, ReconciledPackage> reconcilePackagesLocked(
            final ReconcileRequest request, KeySetManagerService ksms, Injector injector)
            throws ReconcileFailure {
        final Map<String, ScanResult> scannedPackages = request.mScannedPackages;

        final Map<String, ReconciledPackage> result = new ArrayMap<>(scannedPackages.size());

        // make a copy of the existing set of packages so we can combine them with incoming packages
        final ArrayMap<String, AndroidPackage> combinedPackages =
                new ArrayMap<>(request.mAllPackages.size() + scannedPackages.size());

        combinedPackages.putAll(request.mAllPackages);

        final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> incomingSharedLibraries =
                new ArrayMap<>();

        for (String installPackageName : scannedPackages.keySet()) {
            final ScanResult scanResult = scannedPackages.get(installPackageName);

            // add / replace existing with incoming packages
            combinedPackages.put(scanResult.mPkgSetting.getPackageName(),
                    scanResult.mRequest.mParsedPackage);

            // in the first pass, we'll build up the set of incoming shared libraries
            final List<SharedLibraryInfo> allowedSharedLibInfos =
                    getAllowedSharedLibInfos(scanResult, request.mSharedLibrarySource);
            final SharedLibraryInfo staticLib = scanResult.mStaticSharedLibraryInfo;
            if (allowedSharedLibInfos != null) {
                for (SharedLibraryInfo info : allowedSharedLibInfos) {
                    if (!addSharedLibraryToPackageVersionMap(incomingSharedLibraries, info)) {
                        throw new ReconcileFailure("Static Shared Library " + staticLib.getName()
                                + " is being installed twice in this set!");
                    }
                }
            }

            // the following may be null if we're just reconciling on boot (and not during install)
            final InstallArgs installArgs = request.mInstallArgs.get(installPackageName);
            final PackageInstalledInfo res = request.mInstallResults.get(installPackageName);
            final PrepareResult prepareResult = request.mPreparedPackages.get(installPackageName);
            final boolean isInstall = installArgs != null;
            if (isInstall && (res == null || prepareResult == null)) {
                throw new ReconcileFailure("Reconcile arguments are not balanced for "
                        + installPackageName + "!");
            }

            final DeletePackageAction deletePackageAction;
            // we only want to try to delete for non system apps
            if (isInstall && prepareResult.mReplace && !prepareResult.mSystem) {
                final boolean killApp = (scanResult.mRequest.mScanFlags & SCAN_DONT_KILL_APP) == 0;
                final int deleteFlags = PackageManager.DELETE_KEEP_DATA
                        | (killApp ? 0 : PackageManager.DELETE_DONT_KILL_APP);
                deletePackageAction = mayDeletePackageLocked(res.mRemovedInfo,
                        prepareResult.mOriginalPs, prepareResult.mDisabledPs,
                        deleteFlags, null /* all users */);
                if (deletePackageAction == null) {
                    throw new ReconcileFailure(
                            PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE,
                            "May not delete " + installPackageName + " to replace");
                }
            } else {
                deletePackageAction = null;
            }

            final int scanFlags = scanResult.mRequest.mScanFlags;
            final int parseFlags = scanResult.mRequest.mParseFlags;
            final ParsedPackage parsedPackage = scanResult.mRequest.mParsedPackage;

            final PackageSetting disabledPkgSetting = scanResult.mRequest.mDisabledPkgSetting;
            final PackageSetting lastStaticSharedLibSetting =
                    request.mLastStaticSharedLibSettings.get(installPackageName);
            final PackageSetting signatureCheckPs =
                    (prepareResult != null && lastStaticSharedLibSetting != null)
                            ? lastStaticSharedLibSetting
                            : scanResult.mPkgSetting;
            boolean removeAppKeySetData = false;
            boolean sharedUserSignaturesChanged = false;
            SigningDetails signingDetails = null;
            if (ksms.shouldCheckUpgradeKeySetLocked(signatureCheckPs, scanFlags)) {
                if (ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                } else {
                    if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new ReconcileFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                "Package " + parsedPackage.getPackageName()
                                        + " upgrade keys do not match the previously installed"
                                        + " version");
                    } else {
                        String msg = "System package " + parsedPackage.getPackageName()
                                + " signature changed; retaining data.";
                        reportSettingsProblem(Log.WARN, msg);
                    }
                }
                signingDetails = parsedPackage.getSigningDetails();
            } else {
                try {
                    final VersionInfo versionInfo = request.mVersionInfos.get(installPackageName);
                    final boolean compareCompat = isCompatSignatureUpdateNeeded(versionInfo);
                    final boolean compareRecover = isRecoverSignatureUpdateNeeded(versionInfo);
                    final boolean isRollback = installArgs != null
                            && installArgs.mInstallReason == PackageManager.INSTALL_REASON_ROLLBACK;
                    final boolean compatMatch = verifySignatures(signatureCheckPs,
                            disabledPkgSetting, parsedPackage.getSigningDetails(), compareCompat,
                            compareRecover, isRollback);
                    // The new KeySets will be re-added later in the scanning process.
                    if (compatMatch) {
                        removeAppKeySetData = true;
                    }
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    signingDetails = parsedPackage.getSigningDetails();

                    // if this is is a sharedUser, check to see if the new package is signed by a
                    // newer
                    // signing certificate than the existing one, and if so, copy over the new
                    // details
                    if (signatureCheckPs.getSharedUser() != null) {
                        // Attempt to merge the existing lineage for the shared SigningDetails with
                        // the lineage of the new package; if the shared SigningDetails are not
                        // returned this indicates the new package added new signers to the lineage
                        // and/or changed the capabilities of existing signers in the lineage.
                        SigningDetails sharedSigningDetails =
                                signatureCheckPs.getSharedUser().signatures.mSigningDetails;
                        SigningDetails mergedDetails = sharedSigningDetails.mergeLineageWith(
                                signingDetails);
                        if (mergedDetails != sharedSigningDetails) {
                            signatureCheckPs.getSharedUser().signatures.mSigningDetails =
                                    mergedDetails;
                        }
                        if (signatureCheckPs.getSharedUser().signaturesChanged == null) {
                            signatureCheckPs.getSharedUser().signaturesChanged = Boolean.FALSE;
                        }
                    }
                } catch (PackageManagerException e) {
                    if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new ReconcileFailure(e);
                    }
                    signingDetails = parsedPackage.getSigningDetails();

                    // If the system app is part of a shared user we allow that shared user to
                    // change
                    // signatures as well as part of an OTA. We still need to verify that the
                    // signatures
                    // are consistent within the shared user for a given boot, so only allow
                    // updating
                    // the signatures on the first package scanned for the shared user (i.e. if the
                    // signaturesChanged state hasn't been initialized yet in SharedUserSetting).
                    if (signatureCheckPs.getSharedUser() != null) {
                        final Signature[] sharedUserSignatures = signatureCheckPs.getSharedUser()
                                .signatures.mSigningDetails.getSignatures();
                        if (signatureCheckPs.getSharedUser().signaturesChanged != null
                                && compareSignatures(sharedUserSignatures,
                                parsedPackage.getSigningDetails().getSignatures())
                                        != PackageManager.SIGNATURE_MATCH) {
                            if (SystemProperties.getInt("ro.product.first_api_level", 0) <= 29) {
                                // Mismatched signatures is an error and silently skipping system
                                // packages will likely break the device in unforeseen ways.
                                // However, we allow the device to boot anyway because, prior to Q,
                                // vendors were not expecting the platform to crash in this
                                // situation.
                                // This WILL be a hard failure on any new API levels after Q.
                                throw new ReconcileFailure(
                                        INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                        "Signature mismatch for shared user: "
                                                + scanResult.mPkgSetting.getSharedUser());
                            } else {
                                // Treat mismatched signatures on system packages using a shared
                                // UID as
                                // fatal for the system overall, rather than just failing to install
                                // whichever package happened to be scanned later.
                                throw new IllegalStateException(
                                        "Signature mismatch on system package "
                                                + parsedPackage.getPackageName()
                                                + " for shared user "
                                                + scanResult.mPkgSetting.getSharedUser());
                            }
                        }

                        sharedUserSignaturesChanged = true;
                        signatureCheckPs.getSharedUser().signatures.mSigningDetails =
                                parsedPackage.getSigningDetails();
                        signatureCheckPs.getSharedUser().signaturesChanged = Boolean.TRUE;
                    }
                    // File a report about this.
                    String msg = "System package " + parsedPackage.getPackageName()
                            + " signature changed; retaining data.";
                    reportSettingsProblem(Log.WARN, msg);
                } catch (IllegalArgumentException e) {
                    // should never happen: certs matched when checking, but not when comparing
                    // old to new for sharedUser
                    throw new RuntimeException(
                            "Signing certificates comparison made on incomparable signing details"
                                    + " but somehow passed verifySignatures!", e);
                }
            }

            result.put(installPackageName,
                    new ReconciledPackage(request, installArgs, scanResult.mPkgSetting,
                            res, request.mPreparedPackages.get(installPackageName), scanResult,
                            deletePackageAction, allowedSharedLibInfos, signingDetails,
                            sharedUserSignaturesChanged, removeAppKeySetData));
        }

        for (String installPackageName : scannedPackages.keySet()) {
            // Check all shared libraries and map to their actual file path.
            // We only do this here for apps not on a system dir, because those
            // are the only ones that can fail an install due to this.  We
            // will take care of the system apps by updating all of their
            // library paths after the scan is done. Also during the initial
            // scan don't update any libs as we do this wholesale after all
            // apps are scanned to avoid dependency based scanning.
            final ScanResult scanResult = scannedPackages.get(installPackageName);
            if ((scanResult.mRequest.mScanFlags & SCAN_BOOTING) != 0
                    || (scanResult.mRequest.mParseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR)
                    != 0) {
                continue;
            }
            try {
                result.get(installPackageName).mCollectedSharedLibraryInfos =
                        collectSharedLibraryInfos(scanResult.mRequest.mParsedPackage,
                                combinedPackages, request.mSharedLibrarySource,
                                incomingSharedLibraries, injector.getCompatibility());

            } catch (PackageManagerException e) {
                throw new ReconcileFailure(e.error, e.getMessage());
            }
        }

        return result;
    }

    /**
     * Compare the newly scanned package with current system state to see which of its declared
     * shared libraries should be allowed to be added to the system.
     */
    private static List<SharedLibraryInfo> getAllowedSharedLibInfos(
            ScanResult scanResult,
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> existingSharedLibraries) {
        // Let's used the parsed package as scanResult.pkgSetting may be null
        final ParsedPackage parsedPackage = scanResult.mRequest.mParsedPackage;
        if (scanResult.mStaticSharedLibraryInfo == null
                && scanResult.mDynamicSharedLibraryInfos == null) {
            return null;
        }

        // Any app can add new static shared libraries
        if (scanResult.mStaticSharedLibraryInfo != null) {
            return Collections.singletonList(scanResult.mStaticSharedLibraryInfo);
        }
        final boolean hasDynamicLibraries = parsedPackage.isSystem()
                        && scanResult.mDynamicSharedLibraryInfos != null;
        if (!hasDynamicLibraries) {
            return null;
        }
        final boolean isUpdatedSystemApp = scanResult.mPkgSetting.getPkgState()
                .isUpdatedSystemApp();
        // We may not yet have disabled the updated package yet, so be sure to grab the
        // current setting if that's the case.
        final PackageSetting updatedSystemPs = isUpdatedSystemApp
                ? scanResult.mRequest.mDisabledPkgSetting == null
                        ? scanResult.mRequest.mOldPkgSetting
                        : scanResult.mRequest.mDisabledPkgSetting
                : null;
        if (isUpdatedSystemApp && (updatedSystemPs.getPkg() == null
                || updatedSystemPs.getPkg().getLibraryNames() == null)) {
            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                    + " declares libraries that are not declared on the system image; skipping");
            return null;
        }
        final ArrayList<SharedLibraryInfo> infos =
                new ArrayList<>(scanResult.mDynamicSharedLibraryInfos.size());
        for (SharedLibraryInfo info : scanResult.mDynamicSharedLibraryInfos) {
            final String name = info.getName();
            if (isUpdatedSystemApp) {
                // New library entries can only be added through the
                // system image.  This is important to get rid of a lot
                // of nasty edge cases: for example if we allowed a non-
                // system update of the app to add a library, then uninstalling
                // the update would make the library go away, and assumptions
                // we made such as through app install filtering would now
                // have allowed apps on the device which aren't compatible
                // with it.  Better to just have the restriction here, be
                // conservative, and create many fewer cases that can negatively
                // impact the user experience.
                if (!updatedSystemPs.getPkg().getLibraryNames().contains(name)) {
                    Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                            + " declares library " + name
                            + " that is not declared on system image; skipping");
                    continue;
                }
            }
            if (sharedLibExists(
                    name, SharedLibraryInfo.VERSION_UNDEFINED, existingSharedLibraries)) {
                Slog.w(TAG, "Package " + parsedPackage.getPackageName() + " declares library "
                        + name + " that already exists; skipping");
                continue;
            }
            infos.add(info);
        }
        return infos;
    }

    /**
     * Returns false if the adding shared library already exists in the map and so could not be
     * added.
     */
    private static boolean addSharedLibraryToPackageVersionMap(
            Map<String, WatchedLongSparseArray<SharedLibraryInfo>> target,
            SharedLibraryInfo library) {
        final String name = library.getName();
        if (target.containsKey(name)) {
            if (library.getType() != SharedLibraryInfo.TYPE_STATIC) {
                // We've already added this non-version-specific library to the map.
                return false;
            } else if (target.get(name).indexOfKey(library.getLongVersion()) >= 0) {
                // We've already added this version of a version-specific library to the map.
                return false;
            }
        } else {
            target.put(name, new WatchedLongSparseArray<>());
        }
        target.get(name).put(library.getLongVersion(), library);
        return true;
    }

    @Nullable PackageSetting getPackageSettingForUser(String packageName, int callingUid,
            int userId) {
        final PackageSetting ps;
        synchronized (mLock) {
            ps = mSettings.getPackageLPr(packageName);
            if (ps == null) {
                Slog.w(TAG, "Failed to get package setting. Package " + packageName
                        + " is not installed");
                return null;
            }
            if (!ps.getInstalled(userId)) {
                Slog.w(TAG, "Failed to get package setting. Package " + packageName
                        + " is not installed for user " + userId);
                return null;
            }
            if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                Slog.w(TAG, "Failed to get package setting. Package " + packageName
                        + " is not visible to the calling app");
                return null;
            }
        }
        return ps;
    }

    private void notifyPackageChangeObserversOnDelete(String packageName, long version) {
        PackageChangeEvent pkgChangeEvent = new PackageChangeEvent();
        pkgChangeEvent.packageName = packageName;
        pkgChangeEvent.version = version;
        pkgChangeEvent.lastUpdateTimeMillis = 0L;
        pkgChangeEvent.newInstalled = false;
        pkgChangeEvent.dataRemoved = false;
        pkgChangeEvent.isDeleted = true;

        notifyPackageChangeObservers(pkgChangeEvent);
    }

    void notifyPackageChangeObservers(PackageChangeEvent event) {
        try {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "notifyPackageChangeObservers");
            synchronized (mPackageChangeObservers) {
                for (IPackageChangeObserver observer : mPackageChangeObservers) {
                    try {
                        observer.onPackageChanged(event);
                    } catch (RemoteException e) {
                        Log.wtf(TAG, e);
                    }
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

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

    @Override
    public void deletePackageAsUser(String packageName, int versionCode,
            IPackageDeleteObserver observer, int userId, int flags) {
        deletePackageVersioned(new VersionedPackage(packageName, versionCode),
                new LegacyPackageDeleteObserver(observer).getBinder(), userId, flags);
    }

    @Override
    public void deleteExistingPackageAsUser(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        Preconditions.checkNotNull(versionedPackage);
        Preconditions.checkNotNull(observer);
        final String packageName = versionedPackage.getPackageName();
        final long versionCode = versionedPackage.getLongVersionCode();

        int installedForUsersCount = 0;
        synchronized (mLock) {
            // Normalize package name to handle renamed packages and static libs
            final String internalPkgName = resolveInternalPackageNameLPr(packageName, versionCode);
            final PackageSetting ps = mSettings.getPackageLPr(internalPkgName);
            if (ps != null) {
                int[] installedUsers = ps.queryInstalledUsers(mUserManager.getUserIds(), true);
                installedForUsersCount = installedUsers.length;
            }
        }

        if (installedForUsersCount > 1) {
            deletePackageVersionedInternal(versionedPackage, observer, userId, 0, true);
        } else {
            try {
                observer.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_INTERNAL_ERROR,
                        null);
            } catch (RemoteException re) {
            }
        }
    }

    @Override
    public void deletePackageVersioned(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags) {
        deletePackageVersionedInternal(versionedPackage, observer, userId, deleteFlags, false);
    }

    private void deletePackageVersionedInternal(VersionedPackage versionedPackage,
            final IPackageDeleteObserver2 observer, final int userId, final int deleteFlags,
            final boolean allowSilentUninstall) {
        final int callingUid = Binder.getCallingUid();
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        final boolean canViewInstantApps = canViewInstantApps(callingUid, userId);
        Preconditions.checkNotNull(versionedPackage);
        Preconditions.checkNotNull(observer);
        Preconditions.checkArgumentInRange(versionedPackage.getLongVersionCode(),
                PackageManager.VERSION_CODE_HIGHEST,
                Long.MAX_VALUE, "versionCode must be >= -1");

        final String packageName = versionedPackage.getPackageName();
        final long versionCode = versionedPackage.getLongVersionCode();
        final String internalPackageName;

        try {
            if (mInjector.getLocalService(ActivityTaskManagerInternal.class)
                    .isBaseOfLockedTask(packageName)) {
                observer.onPackageDeleted(
                        packageName, PackageManager.DELETE_FAILED_APP_PINNED, null);
                EventLog.writeEvent(0x534e4554, "127605586", -1, "");
                return;
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        synchronized (mLock) {
            // Normalize package name to handle renamed packages and static libs
            internalPackageName = resolveInternalPackageNameLPr(packageName, versionCode);
        }

        final int uid = Binder.getCallingUid();
        if (!isOrphaned(internalPackageName)
                && !allowSilentUninstall
                && !isCallerAllowedToSilentlyUninstall(uid, internalPackageName)) {
            mHandler.post(() -> {
                try {
                    final Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
                    intent.setData(Uri.fromParts(PACKAGE_SCHEME, packageName, null));
                    intent.putExtra(PackageInstaller.EXTRA_CALLBACK, observer.asBinder());
                    observer.onUserActionRequired(intent);
                } catch (RemoteException re) {
                }
            });
            return;
        }
        final boolean deleteAllUsers = (deleteFlags & PackageManager.DELETE_ALL_USERS) != 0;
        final int[] users = deleteAllUsers ? mUserManager.getUserIds() : new int[]{userId};
        if (UserHandle.getUserId(uid) != userId || (deleteAllUsers && users.length > 1)) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "deletePackage for user " + userId);
        }

        if (isUserRestricted(userId, UserManager.DISALLOW_UNINSTALL_APPS)) {
            mHandler.post(() -> {
                try {
                    observer.onPackageDeleted(packageName,
                            PackageManager.DELETE_FAILED_USER_RESTRICTED, null);
                } catch (RemoteException re) {
                }
            });
            return;
        }

        if (!deleteAllUsers && getBlockUninstallForUser(internalPackageName, userId)) {
            mHandler.post(() -> {
                try {
                    observer.onPackageDeleted(packageName,
                            PackageManager.DELETE_FAILED_OWNER_BLOCKED, null);
                } catch (RemoteException re) {
                }
            });
            return;
        }

        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deletePackageAsUser: pkg=" + internalPackageName + " user=" + userId
                    + " deleteAllUsers: " + deleteAllUsers + " version="
                    + (versionCode == PackageManager.VERSION_CODE_HIGHEST
                    ? "VERSION_CODE_HIGHEST" : versionCode));
        }
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(() -> {
            int returnCode;
            final PackageSetting ps = mSettings.getPackageLPr(internalPackageName);
            boolean doDeletePackage = true;
            if (ps != null) {
                final boolean targetIsInstantApp =
                        ps.getInstantApp(UserHandle.getUserId(callingUid));
                doDeletePackage = !targetIsInstantApp
                        || canViewInstantApps;
            }
            if (doDeletePackage) {
                if (!deleteAllUsers) {
                    returnCode = deletePackageX(internalPackageName, versionCode,
                            userId, deleteFlags, false /*removedBySystem*/);
                } else {
                    int[] blockUninstallUserIds = getBlockUninstallForUsers(
                            internalPackageName, users);
                    // If nobody is blocking uninstall, proceed with delete for all users
                    if (ArrayUtils.isEmpty(blockUninstallUserIds)) {
                        returnCode = deletePackageX(internalPackageName, versionCode,
                                userId, deleteFlags, false /*removedBySystem*/);
                    } else {
                        // Otherwise uninstall individually for users with blockUninstalls=false
                        final int userFlags = deleteFlags & ~PackageManager.DELETE_ALL_USERS;
                        for (int userId1 : users) {
                            if (!ArrayUtils.contains(blockUninstallUserIds, userId1)) {
                                returnCode = deletePackageX(internalPackageName, versionCode,
                                        userId1, userFlags, false /*removedBySystem*/);
                                if (returnCode != PackageManager.DELETE_SUCCEEDED) {
                                    Slog.w(TAG, "Package delete failed for user " + userId1
                                            + ", returnCode " + returnCode);
                                }
                            }
                        }
                        // The app has only been marked uninstalled for certain users.
                        // We still need to report that delete was blocked
                        returnCode = PackageManager.DELETE_FAILED_OWNER_BLOCKED;
                    }
                }
            } else {
                returnCode = PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }
            try {
                observer.onPackageDeleted(packageName, returnCode, null);
            } catch (RemoteException e) {
                Log.i(TAG, "Observer no longer exists.");
            } //end catch
            notifyPackageChangeObserversOnDelete(packageName, versionCode);
        });
    }

    private String resolveExternalPackageNameLPr(AndroidPackage pkg) {
        return mComputer.resolveExternalPackageNameLPr(pkg);
    }

    @GuardedBy("mLock")
    private String resolveInternalPackageNameLPr(String packageName, long versionCode) {
        return mComputer.resolveInternalPackageNameLPr(packageName, versionCode);
    }

    boolean isCallerVerifier(int callingUid) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        return mRequiredVerifierPackage != null &&
                callingUid == getPackageUid(mRequiredVerifierPackage, 0, callingUserId);
    }

    private boolean isCallerAllowedToSilentlyUninstall(int callingUid, String pkgName) {
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID
              || UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return true;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        // If the caller installed the pkgName, then allow it to silently uninstall.
        if (callingUid == getPackageUid(getInstallerPackageName(pkgName), 0, callingUserId)) {
            return true;
        }

        // Allow package verifier to silently uninstall.
        if (mRequiredVerifierPackage != null &&
                callingUid == getPackageUid(mRequiredVerifierPackage, 0, callingUserId)) {
            return true;
        }

        // Allow package uninstaller to silently uninstall.
        if (mRequiredUninstallerPackage != null &&
                callingUid == getPackageUid(mRequiredUninstallerPackage, 0, callingUserId)) {
            return true;
        }

        // Allow storage manager to silently uninstall.
        if (mStorageManagerPackage != null &&
                callingUid == getPackageUid(mStorageManagerPackage, 0, callingUserId)) {
            return true;
        }

        // Allow caller having MANAGE_PROFILE_AND_DEVICE_OWNERS permission to silently
        // uninstall for device owner provisioning.
        return checkUidPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS, callingUid)
                == PERMISSION_GRANTED;
    }

    private int[] getBlockUninstallForUsers(String packageName, int[] userIds) {
        int[] result = EMPTY_INT_ARRAY;
        for (int userId : userIds) {
            if (getBlockUninstallForUser(packageName, userId)) {
                result = ArrayUtils.appendInt(result, userId);
            }
        }
        return result;
    }

    @Override
    public boolean isPackageDeviceAdminOnAnyUser(String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (checkUidPermission(android.Manifest.permission.MANAGE_USERS, callingUid)
                != PERMISSION_GRANTED) {
            EventLog.writeEvent(0x534e4554, "128599183", -1, "");
            throw new SecurityException(android.Manifest.permission.MANAGE_USERS
                    + " permission is required to call this API");
        }
        if (getInstantAppPackageName(callingUid) != null
                && !isCallerSameApp(packageName, callingUid)) {
            return false;
        }
        return isPackageDeviceAdmin(packageName, UserHandle.USER_ALL);
    }

    private boolean isPackageDeviceAdmin(String packageName, int userId) {
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
                }
            }
        } catch (RemoteException e) {
        }
        return false;
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

    private boolean shouldKeepUninstalledPackageLPr(String packageName) {
        return mKeepUninstalledPackages != null && mKeepUninstalledPackages.contains(packageName);
    }

    /**
     *  This method is an internal method that could be get invoked either
     *  to delete an installed package or to clean up a failed installation.
     *  After deleting an installed package, a broadcast is sent to notify any
     *  listeners that the package has been removed. For cleaning up a failed
     *  installation, the broadcast is not necessary since the package's
     *  installation wouldn't have sent the initial broadcast either
     *  The key steps in deleting a package are
     *  deleting the package information in internal structures like mPackages,
     *  deleting the packages base directories through installd
     *  updating mSettings to reflect current status
     *  persisting settings for later use
     *  sending a broadcast if necessary
     *
     *  @param removedBySystem A boolean to indicate the package was removed automatically without
     *                         the user-initiated action.
     */
    int deletePackageX(String packageName, long versionCode, int userId, int deleteFlags,
            boolean removedBySystem) {
        final PackageRemovedInfo info = new PackageRemovedInfo(this);
        final boolean res;

        final int removeUser = (deleteFlags & PackageManager.DELETE_ALL_USERS) != 0
                ? UserHandle.USER_ALL : userId;

        if (isPackageDeviceAdmin(packageName, removeUser)) {
            Slog.w(TAG, "Not removing package " + packageName + ": has active device admin");
            return PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER;
        }

        final PackageSetting uninstalledPs;
        final PackageSetting disabledSystemPs;
        final AndroidPackage pkg;

        // for the uninstall-updates case and restricted profiles, remember the per-
        // user handle installed state
        int[] allUsers;
        final int freezeUser;
        final SparseArray<TempUserState> priorUserStates;
        final InitAndSystemPackageHelper helper = new InitAndSystemPackageHelper(this);
        /** enabled state of the uninstalled application */
        synchronized (mLock) {
            uninstalledPs = mSettings.getPackageLPr(packageName);
            if (uninstalledPs == null) {
                Slog.w(TAG, "Not removing non-existent package " + packageName);
                return PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }

            if (versionCode != PackageManager.VERSION_CODE_HIGHEST
                    && uninstalledPs.getLongVersionCode() != versionCode) {
                Slog.w(TAG, "Not removing package " + packageName + " with versionCode "
                        + uninstalledPs.getLongVersionCode() + " != " + versionCode);
                return PackageManager.DELETE_FAILED_INTERNAL_ERROR;
            }

            disabledSystemPs = mSettings.getDisabledSystemPkgLPr(packageName);
            // Static shared libs can be declared by any package, so let us not
            // allow removing a package if it provides a lib others depend on.
            pkg = mPackages.get(packageName);

            allUsers = mUserManager.getUserIds();

            if (pkg != null && pkg.getStaticSharedLibName() != null) {
                SharedLibraryInfo libraryInfo = getSharedLibraryInfoLPr(
                        pkg.getStaticSharedLibName(), pkg.getStaticSharedLibVersion());
                if (libraryInfo != null) {
                    for (int currUserId : allUsers) {
                        if (removeUser != UserHandle.USER_ALL && removeUser != currUserId) {
                            continue;
                        }
                        List<VersionedPackage> libClientPackages = getPackagesUsingSharedLibraryLPr(
                                libraryInfo, MATCH_KNOWN_PACKAGES, Process.SYSTEM_UID, currUserId);
                        if (!ArrayUtils.isEmpty(libClientPackages)) {
                            Slog.w(TAG, "Not removing package " + pkg.getManifestPackageName()
                                    + " hosting lib " + libraryInfo.getName() + " version "
                                    + libraryInfo.getLongVersion() + " used by " + libClientPackages
                                    + " for user " + currUserId);
                            return PackageManager.DELETE_FAILED_USED_SHARED_LIBRARY;
                        }
                    }
                }
            }

            info.mOrigUsers = uninstalledPs.queryInstalledUsers(allUsers, true);

            if (PackageManagerServiceUtils.isUpdatedSystemApp(uninstalledPs)
                    && ((deleteFlags & PackageManager.DELETE_SYSTEM_APP) == 0)) {
                // We're downgrading a system app, which will apply to all users, so
                // freeze them all during the downgrade
                freezeUser = UserHandle.USER_ALL;
                priorUserStates = new SparseArray<>();
                for (int i = 0; i < allUsers.length; i++) {
                    PackageUserState userState = uninstalledPs.readUserState(allUsers[i]);
                    priorUserStates.put(allUsers[i],
                            new TempUserState(userState.enabled, userState.lastDisableAppCaller,
                                    userState.installed));
                }
            } else {
                freezeUser = removeUser;
                priorUserStates = null;
            }
        }

        synchronized (mInstallLock) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageX: pkg=" + packageName + " user=" + userId);
            try (PackageFreezer freezer = freezePackageForDelete(packageName, freezeUser,
                    deleteFlags, "deletePackageX")) {
                res = deletePackageLIF(packageName, UserHandle.of(removeUser), true, allUsers,
                        deleteFlags | PackageManager.DELETE_CHATTY, info, true);
            }
            synchronized (mLock) {
                if (res) {
                    if (pkg != null) {
                        mInstantAppRegistry.onPackageUninstalledLPw(pkg, uninstalledPs,
                                info.mRemovedUsers);
                    }
                    updateSequenceNumberLP(uninstalledPs, info.mRemovedUsers);
                    updateInstantAppInstallerLocked(packageName);
                }
            }
            ApplicationPackageManager.invalidateGetPackagesForUidCache();
        }

        if (res) {
            final boolean killApp = (deleteFlags & PackageManager.DELETE_DONT_KILL_APP) == 0;
            info.sendPackageRemovedBroadcasts(killApp, removedBySystem);
            info.sendSystemPackageUpdatedBroadcasts();
        }
        // Force a gc here.
        Runtime.getRuntime().gc();
        // Delete the resources here after sending the broadcast to let
        // other processes clean up before deleting resources.
        synchronized (mInstallLock) {
            if (info.mArgs != null) {
                info.mArgs.doPostDeleteLI(true);
            }

            boolean reEnableStub = false;

            if (priorUserStates != null) {
                synchronized (mLock) {
                    for (int i = 0; i < allUsers.length; i++) {
                        TempUserState priorUserState = priorUserStates.get(allUsers[i]);
                        int enabledState = priorUserState.enabledState;
                        PackageSetting pkgSetting = getPackageSetting(packageName);
                        pkgSetting.setEnabled(enabledState, allUsers[i],
                                priorUserState.lastDisableAppCaller);

                        AndroidPackage aPkg = pkgSetting.getPkg();
                        boolean pkgEnabled = aPkg != null && aPkg.isEnabled();
                        if (!reEnableStub && priorUserState.installed
                                && ((enabledState == COMPONENT_ENABLED_STATE_DEFAULT && pkgEnabled)
                                        || enabledState == COMPONENT_ENABLED_STATE_ENABLED)) {
                            reEnableStub = true;
                        }
                    }
                    mSettings.writeAllUsersPackageRestrictionsLPr();
                }
            }

            final AndroidPackage stubPkg =
                    (disabledSystemPs == null) ? null : disabledSystemPs.getPkg();
            if (stubPkg != null && stubPkg.isStub()) {
                final PackageSetting stubPs;
                synchronized (mLock) {
                    stubPs = mSettings.getPackageLPr(stubPkg.getPackageName());
                }

                if (stubPs != null) {
                    if (reEnableStub) {
                        if (DEBUG_COMPRESSION) {
                            Slog.i(TAG, "Enabling system stub after removal; pkg: "
                                    + stubPkg.getPackageName());
                        }
                        helper.enableCompressedPackage(stubPkg, stubPs, mDefParseFlags,
                                mDirsToScanAsSystem);
                    } else if (DEBUG_COMPRESSION) {
                        Slog.i(TAG, "System stub disabled for all users, leaving uncompressed "
                                + "after removal; pkg: " + stubPkg.getPackageName());
                    }
                }
            }
        }

        return res ? PackageManager.DELETE_SUCCEEDED : PackageManager.DELETE_FAILED_INTERNAL_ERROR;
    }

    /*
     * This method deletes the package from internal data structures. If the DELETE_KEEP_DATA
     * flag is not set, the data directory is removed as well.
     * make sure this flag is set for partially installed apps. If not its meaningless to
     * delete a partially installed application.
     */
    void removePackageDataLIF(final PackageSetting deletedPs, @NonNull int[] allUserHandles,
            PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        String packageName = deletedPs.getPackageName();
        if (DEBUG_REMOVE) Slog.d(TAG, "removePackageDataLI: " + deletedPs);
        // Retrieve object to delete permissions for shared user later on
        final AndroidPackage deletedPkg = deletedPs.getPkg();
        if (outInfo != null) {
            outInfo.mRemovedPackage = packageName;
            outInfo.mInstallerPackageName = deletedPs.getInstallSource().installerPackageName;
            outInfo.mIsStaticSharedLib = deletedPkg != null
                    && deletedPkg.getStaticSharedLibName() != null;
            outInfo.populateUsers(deletedPs == null ? null
                    : deletedPs.queryInstalledUsers(mUserManager.getUserIds(), true), deletedPs);
        }

        removePackageLI(deletedPs.getPackageName(), (flags & PackageManager.DELETE_CHATTY) != 0);

        if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
            final AndroidPackage resolvedPkg;
            if (deletedPkg != null) {
                resolvedPkg = deletedPkg;
            } else {
                // We don't have a parsed package when it lives on an ejected
                // adopted storage device, so fake something together
                resolvedPkg = PackageImpl.buildFakeForDeletion(deletedPs.getPackageName(),
                        deletedPs.getVolumeUuid());
            }
            destroyAppDataLIF(resolvedPkg, UserHandle.USER_ALL,
                    FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);
            destroyAppProfilesLIF(resolvedPkg);
            if (outInfo != null) {
                outInfo.mDataRemoved = true;
            }
        }

        int removedAppId = -1;

        // writer
        boolean installedStateChanged = false;
        if (deletedPs != null) {
            if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
                final SparseBooleanArray changedUsers = new SparseBooleanArray();
                synchronized (mLock) {
                    mDomainVerificationManager.clearPackage(deletedPs.getPackageName());
                    mSettings.getKeySetManagerService().removeAppKeySetDataLPw(packageName);
                    mAppsFilter.removePackage(getPackageSetting(packageName),
                            false /* isReplace */);
                    removedAppId = mSettings.removePackageLPw(packageName);
                    if (outInfo != null) {
                        outInfo.mRemovedAppId = removedAppId;
                    }
                    if (!mSettings.isDisabledSystemPackageLPr(packageName)) {
                        // If we don't have a disabled system package to reinstall, the package is
                        // really gone and its permission state should be removed.
                        final SharedUserSetting sus = deletedPs.getSharedUser();
                        List<AndroidPackage> sharedUserPkgs = sus != null ? sus.getPackages()
                                : null;
                        if (sharedUserPkgs == null) {
                            sharedUserPkgs = Collections.emptyList();
                        }
                        mPermissionManager.onPackageUninstalled(packageName, deletedPs.getAppId(),
                                deletedPs.getPkg(), sharedUserPkgs, UserHandle.USER_ALL);
                    }
                    clearPackagePreferredActivitiesLPw(
                            deletedPs.getPackageName(), changedUsers, UserHandle.USER_ALL);

                    mSettings.removeRenamedPackageLPw(deletedPs.getRealName());
                }
                if (changedUsers.size() > 0) {
                    updateDefaultHomeNotLocked(changedUsers);
                    postPreferredActivityChangedBroadcast(UserHandle.USER_ALL);
                }
            }
            // make sure to preserve per-user disabled state if this removal was just
            // a downgrade of a system app to the factory package
            if (outInfo != null && outInfo.mOrigUsers != null) {
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Propagating install state across downgrade");
                }
                for (int userId : allUserHandles) {
                    final boolean installed = ArrayUtils.contains(outInfo.mOrigUsers, userId);
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "    user " + userId + " => " + installed);
                    }
                    if (installed != deletedPs.getInstalled(userId)) {
                        installedStateChanged = true;
                    }
                    deletedPs.setInstalled(installed, userId);
                    if (installed) {
                        deletedPs.setUninstallReason(UNINSTALL_REASON_UNKNOWN, userId);
                    }
                }
            }
        }
        synchronized (mLock) {
            // can downgrade to reader
            if (writeSettings) {
                // Save settings now
                writeSettingsLPrTEMP();
            }
            if (installedStateChanged) {
                mSettings.writeKernelMappingLPr(deletedPs);
            }
        }
        if (removedAppId != -1) {
            // A user ID was deleted here. Go through all users and remove it
            // from KeyStore.
            removeKeystoreDataIfNeeded(
                    mInjector.getUserManagerInternal(), UserHandle.USER_ALL, removedAppId);
        }
    }

    private static @Nullable ScanPartition resolveApexToScanPartition(
            ApexManager.ActiveApexInfo apexInfo) {
        for (int i = 0, size = SYSTEM_PARTITIONS.size(); i < size; i++) {
            ScanPartition sp = SYSTEM_PARTITIONS.get(i);
            if (apexInfo.preInstalledApexPath.getAbsolutePath().startsWith(
                    sp.getFolder().getAbsolutePath())) {
                return new ScanPartition(apexInfo.apexDirectory, sp, SCAN_AS_APK_IN_APEX);
            }
        }
        return null;
    }

    void deleteInstalledPackageLIF(PackageSetting ps,
            boolean deleteCodeAndResources, int flags, @NonNull int[] allUserHandles,
            PackageRemovedInfo outInfo, boolean writeSettings) {
        synchronized (mLock) {
            if (outInfo != null) {
                outInfo.mUid = ps.getAppId();
                outInfo.mBroadcastAllowList = mAppsFilter.getVisibilityAllowList(ps,
                        allUserHandles, mSettings.getPackagesLocked());
            }
        }

        // Delete package data from internal structures and also remove data if flag is set
        removePackageDataLIF(ps, allUserHandles, outInfo, flags, writeSettings);

        // Delete application code and resources only for parent packages
        if (deleteCodeAndResources && (outInfo != null)) {
            outInfo.mArgs = createInstallArgsForExisting(
                    ps.getPathString(), getAppDexInstructionSets(
                            ps.getPrimaryCpuAbi(), ps.getSecondaryCpuAbi()));
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "args=" + outInfo.mArgs);
        }
    }

    @Override
    public boolean setBlockUninstallForUser(String packageName, boolean blockUninstall,
            int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        // TODO (b/157774108): This should fail on non-existent packages.
        synchronized (mLock) {
            // Cannot block uninstall of static shared libs as they are
            // considered a part of the using app (emulating static linking).
            // Also static libs are installed always on internal storage.
            AndroidPackage pkg = mPackages.get(packageName);
            if (pkg != null && pkg.getStaticSharedLibName() != null) {
                Slog.w(TAG, "Cannot block uninstall of package: " + packageName
                        + " providing static shared library: " + pkg.getStaticSharedLibName());
                return false;
            }
            mSettings.setBlockUninstallLPw(userId, packageName, blockUninstall);
            mSettings.writePackageRestrictionsLPr(userId);
        }
        return true;
    }

    @Override
    public boolean getBlockUninstallForUser(String packageName, int userId) {
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null || shouldFilterApplicationLocked(ps, Binder.getCallingUid(), userId)) {
                return false;
            }
            return mSettings.getBlockUninstallLPr(userId, packageName);
        }
    }

    @Override
    public boolean setRequiredForSystemUser(String packageName, boolean systemUserApp) {
        enforceSystemOrRoot("setRequiredForSystemUser can only be run by the system or root");
        synchronized (mLock) {
            PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null) {
                Log.w(TAG, "Package doesn't exist: " + packageName);
                return false;
            }
            if (systemUserApp) {
                ps.pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER;
            } else {
                ps.pkgPrivateFlags &= ~ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER;
            }
            writeSettingsLPrTEMP();
        }
        return true;
    }

    /**
     * @return a {@link DeletePackageAction} if the provided package and related state may be
     * deleted, {@code null} otherwise.
     */
    @Nullable
    @GuardedBy("mLock")
    private static DeletePackageAction mayDeletePackageLocked(
            PackageRemovedInfo outInfo, PackageSetting ps, @Nullable PackageSetting disabledPs,
            int flags, UserHandle user) {
        if (ps == null) {
            return null;
        }
        if (PackageManagerServiceUtils.isSystemApp(ps)) {
            final boolean deleteSystem = (flags & PackageManager.DELETE_SYSTEM_APP) != 0;
            final boolean deleteAllUsers =
                    user == null || user.getIdentifier() == UserHandle.USER_ALL;
            if ((!deleteSystem || deleteAllUsers) && disabledPs == null) {
                Slog.w(TAG, "Attempt to delete unknown system package "
                        + ps.getPkg().getPackageName());
                return null;
            }
            // Confirmed if the system package has been updated
            // An updated system app can be deleted. This will also have to restore
            // the system pkg from system partition reader
        }
        return new DeletePackageAction(ps, disabledPs, outInfo, flags, user);
    }

    /*
     * This method handles package deletion in general
     */
    boolean deletePackageLIF(@NonNull String packageName, UserHandle user,
            boolean deleteCodeAndResources, @NonNull int[] allUserHandles, int flags,
            PackageRemovedInfo outInfo, boolean writeSettings) {
        final DeletePackageAction action;
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            final PackageSetting disabledPs = mSettings.getDisabledSystemPkgLPr(ps);
            action = mayDeletePackageLocked(outInfo, ps, disabledPs, flags, user);
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: " + packageName + " user " + user);
        if (null == action) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: action was null");
            return false;
        }

        try {
            executeDeletePackageLIF(action, packageName, deleteCodeAndResources,
                    allUserHandles, writeSettings);
        } catch (SystemDeleteException e) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: system deletion failure", e);
            return false;
        }
        return true;
    }

    /** Deletes a package. Only throws when install of a disabled package fails. */
    void executeDeletePackageLIF(DeletePackageAction action,
            String packageName, boolean deleteCodeAndResources,
            @NonNull int[] allUserHandles, boolean writeSettings) throws SystemDeleteException {
        final PackageSetting ps = action.mDeletingPs;
        final PackageRemovedInfo outInfo = action.mRemovedInfo;
        final UserHandle user = action.mUser;
        final int flags = action.mFlags;
        final boolean systemApp = PackageManagerServiceUtils.isSystemApp(ps);

        // We need to get the permission state before package state is (potentially) destroyed.
        final SparseBooleanArray hadSuspendAppsPermission = new SparseBooleanArray();
        for (int userId : allUserHandles) {
            hadSuspendAppsPermission.put(userId, checkPermission(Manifest.permission.SUSPEND_APPS,
                    packageName, userId) == PERMISSION_GRANTED);
        }

        final int userId = user == null ? UserHandle.USER_ALL : user.getIdentifier();

        if ((!systemApp || (flags & PackageManager.DELETE_SYSTEM_APP) != 0)
                && userId != UserHandle.USER_ALL) {
            // The caller is asking that the package only be deleted for a single
            // user.  To do this, we just mark its uninstalled state and delete
            // its data. If this is a system app, we only allow this to happen if
            // they have set the special DELETE_SYSTEM_APP which requests different
            // semantics than normal for uninstalling system apps.
            final boolean clearPackageStateAndReturn;
            synchronized (mLock) {
                markPackageUninstalledForUserLPw(ps, user);
                if (!systemApp) {
                    // Do not uninstall the APK if an app should be cached
                    boolean keepUninstalledPackage = shouldKeepUninstalledPackageLPr(packageName);
                    if (ps.isAnyInstalled(mUserManager.getUserIds()) || keepUninstalledPackage) {
                        // Other users still have this package installed, so all
                        // we need to do is clear this user's data and save that
                        // it is uninstalled.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Still installed by other users");
                        clearPackageStateAndReturn = true;
                    } else {
                        // We need to set it back to 'installed' so the uninstall
                        // broadcasts will be sent correctly.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Not installed by other users, full delete");
                        ps.setInstalled(true, userId);
                        mSettings.writeKernelMappingLPr(ps);
                        clearPackageStateAndReturn = false;
                    }
                } else {
                    // This is a system app, so we assume that the
                    // other users still have this package installed, so all
                    // we need to do is clear this user's data and save that
                    // it is uninstalled.
                    if (DEBUG_REMOVE) Slog.d(TAG, "Deleting system app");
                    clearPackageStateAndReturn = true;
                }
            }
            if (clearPackageStateAndReturn) {
                clearPackageStateForUserLIF(ps, userId, outInfo, flags);
                synchronized (mLock) {
                    scheduleWritePackageRestrictionsLocked(user);
                }
                return;
            }
        }

        // TODO(b/109941548): break reasons for ret = false out into mayDelete method
        if (systemApp) {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing system package: " + ps.getPackageName());
            // When an updated system application is deleted we delete the existing resources
            // as well and fall back to existing code in system partition
            final InitAndSystemPackageHelper helper = new InitAndSystemPackageHelper(this);
            helper.deleteSystemPackageLIF(action, ps, allUserHandles, flags, outInfo,
                    writeSettings, mDefParseFlags, mDirsToScanAsSystem);
        } else {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing non-system package: " + ps.getPackageName());
            deleteInstalledPackageLIF(ps, deleteCodeAndResources, flags, allUserHandles,
                    outInfo, writeSettings);
        }

        // If the package removed had SUSPEND_APPS, unset any restrictions that might have been in
        // place for all affected users.
        int[] affectedUserIds = (outInfo != null) ? outInfo.mRemovedUsers : null;
        if (affectedUserIds == null) {
            affectedUserIds = resolveUserIds(userId);
        }
        for (final int affectedUserId : affectedUserIds) {
            if (hadSuspendAppsPermission.get(affectedUserId)) {
                unsuspendForSuspendingPackage(packageName, affectedUserId);
                removeAllDistractingPackageRestrictions(affectedUserId);
            }
        }

        // Take a note whether we deleted the package for all users
        if (outInfo != null) {
            outInfo.mRemovedForAllUsers = mPackages.get(ps.getPackageName()) == null;
        }
    }

    @GuardedBy("mLock")
    private void markPackageUninstalledForUserLPw(PackageSetting ps, UserHandle user) {
        final int[] userIds = (user == null || user.getIdentifier() == UserHandle.USER_ALL)
                ? mUserManager.getUserIds() : new int[] {user.getIdentifier()};
        for (int nextUserId : userIds) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Marking package:" + ps.getPackageName()
                        + " uninstalled for user:" + nextUserId);
            }
            ps.setUserState(nextUserId, 0, COMPONENT_ENABLED_STATE_DEFAULT,
                    false /*installed*/,
                    true /*stopped*/,
                    true /*notLaunched*/,
                    false /*hidden*/,
                    0 /*distractionFlags*/,
                    false /*suspended*/,
                    null /*suspendParams*/,
                    false /*instantApp*/,
                    false /*virtualPreload*/,
                    null /*lastDisableAppCaller*/,
                    null /*enabledComponents*/,
                    null /*disabledComponents*/,
                    PackageManager.INSTALL_REASON_UNKNOWN,
                    PackageManager.UNINSTALL_REASON_UNKNOWN,
                    null /*harmfulAppWarning*/,
                    null /*splashScreenTheme*/);
        }
        mSettings.writeKernelMappingLPr(ps);
    }

    private void clearPackageStateForUserLIF(PackageSetting ps, int userId,
            PackageRemovedInfo outInfo, int flags) {
        final AndroidPackage pkg;
        synchronized (mLock) {
            pkg = mPackages.get(ps.getPackageName());
        }

        destroyAppProfilesLIF(pkg);

        final SharedUserSetting sus = ps.getSharedUser();
        List<AndroidPackage> sharedUserPkgs = sus != null ? sus.getPackages() : null;
        if (sharedUserPkgs == null) {
            sharedUserPkgs = Collections.emptyList();
        }
        final int[] userIds = (userId == UserHandle.USER_ALL) ? mUserManager.getUserIds()
                : new int[] {userId};
        for (int nextUserId : userIds) {
            if (DEBUG_REMOVE) {
                Slog.d(TAG, "Updating package:" + ps.getPackageName() + " install state for user:"
                        + nextUserId);
            }
            if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
                destroyAppDataLIF(pkg, nextUserId,
                        FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);
            }
            removeKeystoreDataIfNeeded(mInjector.getUserManagerInternal(), nextUserId,
                    ps.getAppId());
            clearPackagePreferredActivities(ps.getPackageName(), nextUserId);
            mDomainVerificationManager.clearPackageForUser(ps.getPackageName(), nextUserId);
        }
        mPermissionManager.onPackageUninstalled(ps.getPackageName(), ps.getAppId(), pkg,
                sharedUserPkgs, userId);

        if (outInfo != null) {
            if ((flags & PackageManager.DELETE_KEEP_DATA) == 0) {
                outInfo.mDataRemoved = true;
            }
            outInfo.mRemovedPackage = ps.getPackageName();
            outInfo.mInstallerPackageName = ps.getInstallSource().installerPackageName;
            outInfo.mIsStaticSharedLib = pkg != null && pkg.getStaticSharedLibName() != null;
            outInfo.mRemovedAppId = ps.getAppId();
            outInfo.mRemovedUsers = userIds;
            outInfo.mBroadcastUsers = userIds;
        }
    }

    @Override
    public void clearApplicationProfileData(String packageName) {
        enforceSystemOrRoot("Only the system can clear all profile data");

        final AndroidPackage pkg;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
        }

        try (PackageFreezer freezer = freezePackage(packageName, "clearApplicationProfileData")) {
            synchronized (mInstallLock) {
                clearAppProfilesLIF(pkg);
            }
        }
    }

    @Override
    public void clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer, final int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_USER_DATA, null);

        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "clear application data");

        final boolean filterApp;
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            filterApp = shouldFilterApplicationLocked(ps, callingUid, userId);
        }
        if (!filterApp && mProtectedPackages.isPackageDataProtected(userId, packageName)) {
            throw new SecurityException("Cannot clear data for a protected package: "
                    + packageName);
        }
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeeded;
                if (!filterApp) {
                    try (PackageFreezer freezer = freezePackage(packageName,
                            "clearApplicationUserData")) {
                        synchronized (mInstallLock) {
                            succeeded = clearApplicationUserDataLIF(packageName, userId);
                        }
                        synchronized (mLock) {
                            mInstantAppRegistry.deleteInstantApplicationMetadataLPw(
                                    packageName, userId);
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
                            unsuspendForSuspendingPackage(packageName, userId);
                            removeAllDistractingPackageRestrictions(userId);
                            flushPackageRestrictionsAsUserInternalLocked(userId);
                        }
                    }
                } else {
                    succeeded = false;
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

    private boolean clearApplicationUserDataLIF(String packageName, int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }

        // Try finding details about the requested package
        AndroidPackage pkg;
        PackageSetting ps;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
            ps = mSettings.getPackageLPr(packageName);
            if (pkg == null) {
                if (ps != null) {
                    pkg = ps.getPkg();
                }
            }
        }
        if (pkg == null) {
            Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
            return false;
        }
        mPermissionManager.resetRuntimePermissions(pkg, userId);

        clearAppDataLIF(pkg, userId,
                FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL);

        final int appId = UserHandle.getAppId(pkg.getUid());
        removeKeystoreDataIfNeeded(mInjector.getUserManagerInternal(), userId, appId);

        UserManagerInternal umInternal = mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mInjector.getLocalService(StorageManagerInternal.class);
        final int flags;
        if (StorageManager.isUserKeyUnlocked(userId) && smInternal.isCeStoragePrepared(userId)) {
            flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
        } else if (umInternal.isUserRunning(userId)) {
            flags = StorageManager.FLAG_STORAGE_DE;
        } else {
            flags = 0;
        }
        prepareAppDataContentsLIF(pkg, ps, userId, flags);

        return true;
    }

    private void resetNetworkPolicies(int userId) {
        mInjector.getLocalService(NetworkPolicyManagerInternal.class).resetUserState(userId);
    }

    /**
     * Remove entries from the keystore daemon. Will only remove it if the
     * {@code appId} is valid.
     */
    private static void removeKeystoreDataIfNeeded(UserManagerInternal um, @UserIdInt int userId,
            @AppIdInt int appId) {
        if (appId < 0) {
            return;
        }

        final KeyStore keyStore = KeyStore.getInstance();
        if (keyStore != null) {
            if (userId == UserHandle.USER_ALL) {
                for (final int individual : um.getUserIds()) {
                    keyStore.clearUid(UserHandle.getUid(individual, appId));
                }
            } else {
                keyStore.clearUid(UserHandle.getUid(userId, appId));
            }
        } else {
            Slog.w(TAG, "Could not contact keystore to clear entries for app id " + appId);
        }
    }

    @Override
    public void deleteApplicationCacheFiles(final String packageName,
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
        enforceCrossUserPermission(callingUid, userId, /* requireFullPermission= */ true,
                /* checkShell= */ false, "delete application cache files");
        final int hasAccessInstantApps = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_INSTANT_APPS);

        final AndroidPackage pkg;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
        }

        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(() -> {
            final PackageSetting ps = pkg == null ? null : getPackageSetting(pkg.getPackageName());
            boolean doClearData = true;
            if (ps != null) {
                final boolean targetIsInstantApp =
                        ps.getInstantApp(UserHandle.getUserId(callingUid));
                doClearData = !targetIsInstantApp
                        || hasAccessInstantApps == PackageManager.PERMISSION_GRANTED;
            }
            if (doClearData) {
                synchronized (mInstallLock) {
                    final int flags = FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL;
                    // We're only clearing cache files, so we don't care if the
                    // app is unfrozen and still able to run
                    clearAppDataLIF(pkg, userId, flags | Installer.FLAG_CLEAR_CACHE_ONLY);
                    clearAppDataLIF(pkg, userId, flags | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
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
    public void getPackageSizeInfo(final String packageName, int userId,
            final IPackageStatsObserver observer) {
        throw new UnsupportedOperationException(
                "Shame on you for calling the hidden API getPackageSizeInfo(). Shame!");
    }

    @GuardedBy("mLock")
    private int getUidTargetSdkVersionLockedLPr(int uid) {
        final int appId = UserHandle.getAppId(uid);
        final Object obj = mSettings.getSettingLPr(appId);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            int vers = Build.VERSION_CODES.CUR_DEVELOPMENT;
            final int numPackages = sus.packages.size();
            for (int index = 0; index < numPackages; index++) {
                final PackageSetting ps = sus.packages.valueAt(index);
                if (ps.getPkg() != null) {
                    int v = ps.getPkg().getTargetSdkVersion();
                    if (v < vers) vers = v;
                }
            }
            return vers;
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            if (ps.getPkg() != null) {
                return ps.getPkg().getTargetSdkVersion();
            }
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @GuardedBy("mLock")
    private int getPackageTargetSdkVersionLockedLPr(String packageName) {
        final AndroidPackage p = mPackages.get(packageName);
        if (p != null) {
            return p.getTargetSdkVersion();
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Override
    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId, boolean removeExisting) {
        addPreferredActivity(new WatchedIntentFilter(filter), match, set, activity, true, userId,
                "Adding preferred", removeExisting);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addPreferredActivity(WatchedIntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, boolean always, int userId,
            String opname, boolean removeExisting) {
        // writer
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "add preferred activity");
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            synchronized (mLock) {
                if (getUidTargetSdkVersionLockedLPr(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring addPreferredActivity() from uid "
                            + callingUid);
                    return;
                }
            }
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        if (DEBUG_PREFERRED) {
            Slog.i(TAG, opname + " activity " + activity.flattenToShortString() + " for user "
                    + userId + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
        }
        synchronized (mLock) {
            final PreferredIntentResolver pir = mSettings.editPreferredActivitiesLPw(userId);
            final ArrayList<PreferredActivity> existing = pir.findFilters(filter);
            if (removeExisting && existing != null) {
                Settings.removeFilters(pir, filter, existing);
            }
            pir.addFilter(new PreferredActivity(filter, match, set, activity, always));
            scheduleWritePackageRestrictionsLocked(userId);
        }
        if (!(isHomeFilter(filter) && updateDefaultHomeNotLocked(userId))) {
            postPreferredActivityChangedBroadcast(userId);
        }
    }

    private void postPreferredActivityChangedBroadcast(int userId) {
        mHandler.post(() -> {
            final IActivityManager am = ActivityManager.getService();
            if (am == null) {
                return;
            }

            final Intent intent = new Intent(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED);
            intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            try {
                am.broadcastIntentWithFeature(null, null, intent, null, null,
                        0, null, null, null, null, android.app.AppOpsManager.OP_NONE,
                        null, false, false, userId);
            } catch (RemoteException e) {
            }
        });
    }

    @Override
    public void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        replacePreferredActivity(new WatchedIntentFilter(filter), match,
                                 set, activity, userId);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void replacePreferredActivity(WatchedIntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        if (filter.countActions() != 1) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have only 1 action.");
        }
        if (filter.countDataAuthorities() != 0
                || filter.countDataPaths() != 0
                || filter.countDataSchemes() > 1
                || filter.countDataTypes() != 0) {
            throw new IllegalArgumentException(
                    "replacePreferredActivity expects filter to have no data authorities, " +
                    "paths, or types; and at most one scheme.");
        }

        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "replace preferred activity");
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            synchronized (mLock) {
                if (getUidTargetSdkVersionLockedLPr(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring replacePreferredActivity() from uid "
                            + Binder.getCallingUid());
                    return;
                }
            }
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        }

        synchronized (mLock) {
            final PreferredIntentResolver pir = mSettings.getPreferredActivities(userId);
            if (pir != null) {
                // Get all of the existing entries that exactly match this filter.
                final ArrayList<PreferredActivity> existing = pir.findFilters(filter);
                if (existing != null && existing.size() == 1) {
                    final PreferredActivity cur = existing.get(0);
                    if (DEBUG_PREFERRED) {
                        Slog.i(TAG, "Checking replace of preferred:");
                        filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                        if (!cur.mPref.mAlways) {
                            Slog.i(TAG, "  -- CUR; not mAlways!");
                        } else {
                            Slog.i(TAG, "  -- CUR: mMatch=" + cur.mPref.mMatch);
                            Slog.i(TAG, "  -- CUR: mSet="
                                    + Arrays.toString(cur.mPref.mSetComponents));
                            Slog.i(TAG, "  -- CUR: mComponent=" + cur.mPref.mShortComponent);
                            Slog.i(TAG, "  -- NEW: mMatch="
                                    + (match&IntentFilter.MATCH_CATEGORY_MASK));
                            Slog.i(TAG, "  -- CUR: mSet=" + Arrays.toString(set));
                            Slog.i(TAG, "  -- CUR: mComponent=" + activity.flattenToShortString());
                        }
                    }
                    if (cur.mPref.mAlways && cur.mPref.mComponent.equals(activity)
                            && cur.mPref.mMatch == (match&IntentFilter.MATCH_CATEGORY_MASK)
                            && cur.mPref.sameSet(set)) {
                        // Setting the preferred activity to what it happens to be already
                        if (DEBUG_PREFERRED) {
                            Slog.i(TAG, "Replacing with same preferred activity "
                                    + cur.mPref.mShortComponent + " for user "
                                    + userId + ":");
                            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                        }
                        return;
                    }
                }
                if (existing != null) {
                    Settings.removeFilters(pir, filter, existing);
                }
            }
        }
        addPreferredActivity(filter, match, set, activity, true, userId,
                "Replacing preferred", false);
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        // writer
        synchronized (mLock) {
            AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null || !isCallerSameApp(packageName, callingUid)) {
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (getUidTargetSdkVersionLockedLPr(callingUid)
                            < Build.VERSION_CODES.FROYO) {
                        Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid "
                                + callingUid);
                        return;
                    }
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
                }
            }
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps != null
                    && shouldFilterApplicationLocked(
                            ps, callingUid, UserHandle.getUserId(callingUid))) {
                return;
            }
        }
        int callingUserId = UserHandle.getCallingUserId();
        clearPackagePreferredActivities(packageName, callingUserId);
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    private void clearPackagePreferredActivities(String packageName, int userId) {
        final SparseBooleanArray changedUsers = new SparseBooleanArray();
        synchronized (mLock) {
            clearPackagePreferredActivitiesLPw(packageName, changedUsers, userId);
        }
        if (changedUsers.size() > 0) {
            updateDefaultHomeNotLocked(changedUsers);
            postPreferredActivityChangedBroadcast(userId);
            synchronized (mLock) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    @GuardedBy("mLock")
    private void clearPackagePreferredActivitiesLPw(String packageName,
            @NonNull SparseBooleanArray outUserChanged, int userId) {
        mSettings.clearPackagePreferredActivities(packageName, outUserChanged, userId);
    }

    void restorePermissionsAndUpdateRolesForNewUserInstall(String packageName,
            @UserIdInt int userId) {
        // We may also need to apply pending (restored) runtime permission grants
        // within these users.
        mPermissionManager.restoreDelayedRuntimePermissions(packageName, userId);

        // Persistent preferred activity might have came into effect due to this
        // install.
        updateDefaultHomeNotLocked(userId);
    }

    @Override
    public void resetApplicationPreferences(int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        final long identity = Binder.clearCallingIdentity();
        // writer
        try {
            final SparseBooleanArray changedUsers = new SparseBooleanArray();
            synchronized (mLock) {
                clearPackagePreferredActivitiesLPw(null, changedUsers, userId);
            }
            if (changedUsers.size() > 0) {
                postPreferredActivityChangedBroadcast(userId);
            }
            synchronized (mLock) {
                mSettings.applyDefaultPreferredAppsLPw(userId);
                mDomainVerificationManager.clearUser(userId);
                final int numPackages = mPackages.size();
                for (int i = 0; i < numPackages; i++) {
                    final AndroidPackage pkg = mPackages.valueAt(i);
                    mPermissionManager.resetRuntimePermissions(pkg, userId);
                }
            }
            updateDefaultHomeNotLocked(userId);
            resetNetworkPolicies(userId);
            synchronized (mLock) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {
        List<WatchedIntentFilter> temp =
                WatchedIntentFilter.toWatchedIntentFilterList(outFilters);
        final int result = getPreferredActivitiesInternal(
                temp, outActivities, packageName);
        outFilters.clear();
        for (int i = 0; i < temp.size(); i++) {
            outFilters.add(temp.get(i).getIntentFilter());
        }
        return result;
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public int getPreferredActivitiesInternal(List<WatchedIntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return 0;
        }
        int num = 0;
        final int userId = UserHandle.getCallingUserId();
        // reader
        synchronized (mLock) {
            PreferredIntentResolver pir = mSettings.getPreferredActivities(userId);
            if (pir != null) {
                final Iterator<PreferredActivity> it = pir.filterIterator();
                while (it.hasNext()) {
                    final PreferredActivity pa = it.next();
                    final String prefPackageName = pa.mPref.mComponent.getPackageName();
                    if (packageName == null
                            || (prefPackageName.equals(packageName) && pa.mPref.mAlways)) {
                        if (shouldFilterApplicationLocked(
                                mSettings.getPackageLPr(prefPackageName), callingUid, userId)) {
                            continue;
                        }
                        if (outFilters != null) {
                            outFilters.add(new WatchedIntentFilter(pa.getIntentFilter()));
                        }
                        if (outActivities != null) {
                            outActivities.add(pa.mPref.mComponent);
                        }
                    }
                }
            }
        }

        return num;
    }

    @Override
    public void addPersistentPreferredActivity(IntentFilter filter, ComponentName activity,
            int userId) {
        addPersistentPreferredActivity(new WatchedIntentFilter(filter), activity, userId);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addPersistentPreferredActivity(WatchedIntentFilter filter, ComponentName activity,
            int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "addPersistentPreferredActivity can only be run by the system");
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        if (DEBUG_PREFERRED) {
            Slog.i(TAG, "Adding persistent preferred activity " + activity
                    + " for user " + userId + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
        }
        synchronized (mLock) {
            mSettings.editPersistentPreferredActivitiesLPw(userId).addFilter(
                    new PersistentPreferredActivity(filter, activity, true));
            scheduleWritePackageRestrictionsLocked(userId);
        }
        if (isHomeFilter(filter)) {
            updateDefaultHomeNotLocked(userId);
        }
        postPreferredActivityChangedBroadcast(userId);
    }

    @Override
    public void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "clearPackagePersistentPreferredActivities can only be run by the system");
        }
        boolean changed = false;
        synchronized (mLock) {
            changed = mSettings.clearPackagePersistentPreferredActivities(packageName, userId);
        }
        if (changed) {
            updateDefaultHomeNotLocked(userId);
            postPreferredActivityChangedBroadcast(userId);
            synchronized (mLock) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    /**
     * Common machinery for picking apart a restored XML blob and passing
     * it to a caller-supplied functor to be applied to the running system.
     */
    private void restoreFromXml(TypedXmlPullParser parser, int userId,
            String expectedStartTag, BlobXmlRestorer functor)
            throws IOException, XmlPullParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }
        if (type != XmlPullParser.START_TAG) {
            // oops didn't find a start tag?!
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Didn't find start tag during restore");
            }
            return;
        }
        // this is supposed to be TAG_PREFERRED_BACKUP
        if (!expectedStartTag.equals(parser.getName())) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Found unexpected tag " + parser.getName());
            }
            return;
        }

        // skip interfering stuff, then we're aligned with the backing implementation
        while ((type = parser.next()) == XmlPullParser.TEXT) { }
        functor.apply(parser, userId);
    }

    private interface BlobXmlRestorer {
        void apply(TypedXmlPullParser parser, int userId)
                throws IOException, XmlPullParserException;
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the
     * full set of preferred activities in its canonical XML format.  Returns the
     * XML output as a byte array, or null if there is none.
     */
    @Override
    public byte[] getPreferredActivityBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getPreferredActivityBackup()");
        }

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            final TypedXmlSerializer serializer = Xml.newFastSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_PREFERRED_BACKUP);

            synchronized (mLock) {
                mSettings.writePreferredActivitiesLPr(serializer, userId, true);
            }

            serializer.endTag(null, TAG_PREFERRED_BACKUP);
            serializer.endDocument();
            serializer.flush();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write preferred activities for backup", e);
            }
            return null;
        }

        return dataStream.toByteArray();
    }

    @Override
    public void restorePreferredActivities(byte[] backup, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call restorePreferredActivities()");
        }

        try {
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_PREFERRED_BACKUP,
                    (readParser, readUserId) -> {
                        synchronized (mLock) {
                            mSettings.readPreferredActivitiesLPw(readParser, readUserId);
                        }
                        updateDefaultHomeNotLocked(readUserId);
                    });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring preferred activities: " + e.getMessage());
            }
        }
    }

    /**
     * Non-Binder method, support for the backup/restore mechanism: write the
     * default browser (etc) settings in its canonical XML format.  Returns the default
     * browser XML representation as a byte array, or null if there is none.
     */
    @Override
    public byte[] getDefaultAppsBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getDefaultAppsBackup()");
        }

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            final TypedXmlSerializer serializer = Xml.newFastSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_DEFAULT_APPS);

            synchronized (mLock) {
                mSettings.writeDefaultAppsLPr(serializer, userId);
            }

            serializer.endTag(null, TAG_DEFAULT_APPS);
            serializer.endDocument();
            serializer.flush();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write default apps for backup", e);
            }
            return null;
        }

        return dataStream.toByteArray();
    }

    @Override
    public void restoreDefaultApps(byte[] backup, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call restoreDefaultApps()");
        }

        try {
            final TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_DEFAULT_APPS,
                    (parser1, userId1) -> {
                        final String defaultBrowser;
                        synchronized (mLock) {
                            mSettings.readDefaultAppsLPw(parser1, userId1);
                            defaultBrowser = mSettings.removeDefaultBrowserPackageNameLPw(userId1);
                        }
                        if (defaultBrowser != null) {
                            mDefaultAppProvider.setDefaultBrowser(defaultBrowser, false, userId1);
                        }
                    });
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring default apps: " + e.getMessage());
            }
        }
    }

    @Override
    public byte[] getDomainVerificationBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getDomainVerificationBackup()");
        }

        try {
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                TypedXmlSerializer serializer = Xml.resolveSerializer(output);
                mDomainVerificationManager.writeSettings(serializer, true, userId);
                return output.toByteArray();
            }
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Unable to write domain verification for backup", e);
            }
            return null;
        }
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
            mDomainVerificationManager.restoreSettings(parser);
            input.close();
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring domain verification: " + e.getMessage());
            }
        }
    }

    @Override
    public void addCrossProfileIntentFilter(IntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags) {
        addCrossProfileIntentFilter(new WatchedIntentFilter(intentFilter), ownerPackage,
                                    sourceUserId, targetUserId, flags);
    }

    /**
     * Variant that takes a {@link WatchedIntentFilter}
     */
    public void addCrossProfileIntentFilter(WatchedIntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags) {
        mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        if (intentFilter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a crossProfile intent filter with no filter actions");
            return;
        }
        synchronized (mLock) {
            CrossProfileIntentFilter newFilter = new CrossProfileIntentFilter(intentFilter,
                    ownerPackage, targetUserId, flags);
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
            resolver.addFilter(newFilter);
            scheduleWritePackageRestrictionsLocked(sourceUserId);
        }
    }

    @Override
    public void clearCrossProfileIntentFilters(int sourceUserId, String ownerPackage) {
        mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        final int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        PackageManagerServiceUtils.enforceShellRestriction(mInjector.getUserManagerInternal(),
                UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        synchronized (mLock) {
            CrossProfileIntentResolver resolver =
                    mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArraySet<CrossProfileIntentFilter> set =
                    new ArraySet<>(resolver.filterSet());
            for (CrossProfileIntentFilter filter : set) {
                if (filter.getOwnerPackage().equals(ownerPackage)) {
                    resolver.removeFilter(filter);
                }
            }
            scheduleWritePackageRestrictionsLocked(sourceUserId);
        }
    }

    // Enforcing that callingUid is owning pkg on userId
    private void enforceOwnerRights(String pkg, int callingUid) {
        // The system owns everything.
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return;
        }
        final String[] callerPackageNames = getPackagesForUid(callingUid);
        if (!ArrayUtils.contains(callerPackageNames, pkg)) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not own package " + pkg);
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        PackageInfo pi = getPackageInfo(pkg, 0, callingUserId);
        if (pi == null) {
            throw new IllegalArgumentException("Unknown package " + pkg + " on user "
                    + callingUserId);
        }
    }

    @Override
    public ComponentName getHomeActivities(List<ResolveInfo> allHomeCandidates) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return getHomeActivitiesAsUser(allHomeCandidates, UserHandle.getCallingUserId());
    }

    /**
     * Send a {@code PackageInstaller.ACTION_SESSION_UPDATED} broadcast intent, containing
     * the {@code sessionInfo} in the extra field {@code PackageInstaller.EXTRA_SESSION}.
     */
    public void sendSessionUpdatedBroadcast(PackageInstaller.SessionInfo sessionInfo,
            int userId) {
        if (TextUtils.isEmpty(sessionInfo.installerPackageName)) {
            return;
        }
        Intent sessionUpdatedIntent = new Intent(PackageInstaller.ACTION_SESSION_UPDATED)
                .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                .setPackage(sessionInfo.installerPackageName);
        mContext.sendBroadcastAsUser(sessionUpdatedIntent, UserHandle.of(userId));
    }

    public void sendSessionCommitBroadcast(PackageInstaller.SessionInfo sessionInfo, int userId) {
        UserManagerService ums = UserManagerService.getInstance();
        if (ums != null && !sessionInfo.isStaged()) {
            final UserInfo parent = ums.getProfileParent(userId);
            final int launcherUid = (parent != null) ? parent.id : userId;
            final ComponentName launcherComponent = getDefaultHomeActivity(launcherUid);
            if (launcherComponent != null) {
                Intent launcherIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED)
                        .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                        .putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
                        .setPackage(launcherComponent.getPackageName());
                mContext.sendBroadcastAsUser(launcherIntent, UserHandle.of(launcherUid));
            }
            // TODO(b/122900055) Change/Remove this and replace with new permission role.
            if (mAppPredictionServicePackage != null) {
                Intent predictorIntent = new Intent(PackageInstaller.ACTION_SESSION_COMMITTED)
                        .putExtra(PackageInstaller.EXTRA_SESSION, sessionInfo)
                        .putExtra(Intent.EXTRA_USER, UserHandle.of(userId))
                        .setPackage(mAppPredictionServicePackage);
                mContext.sendBroadcastAsUser(predictorIntent, UserHandle.of(launcherUid));
            }
        }
    }

    /**
     * Report the 'Home' activity which is currently set as "always use this one". If non is set
     * then reports the most likely home activity or null if there are more than one.
     */
    private ComponentName getDefaultHomeActivity(int userId) {
        return mComputer.getDefaultHomeActivity(userId);
    }

    private Intent getHomeIntent() {
        return mComputer.getHomeIntent();
    }

    private WatchedIntentFilter getHomeFilter() {
        WatchedIntentFilter filter = new WatchedIntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        return filter;
    }

    private boolean isHomeFilter(@NonNull WatchedIntentFilter filter) {
        return filter.hasAction(Intent.ACTION_MAIN) && filter.hasCategory(Intent.CATEGORY_HOME)
                && filter.hasCategory(CATEGORY_DEFAULT);
    }

    ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
            int userId) {
        return mComputer.getHomeActivitiesAsUser(allHomeCandidates,
                userId);
    }

    /** <b>must not hold {@link #mLock}</b> */
    private void updateDefaultHomeNotLocked(SparseBooleanArray userIds) {
        if (Thread.holdsLock(mLock)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
        }
        for (int i = userIds.size() - 1; i >= 0; --i) {
            final int userId = userIds.keyAt(i);
            updateDefaultHomeNotLocked(userId);
        }
    }

    /**
     * <b>must not hold {@link #mLock}</b>
     *
     * @return Whether the ACTION_PREFERRED_ACTIVITY_CHANGED broadcast has been scheduled.
     */
    private boolean updateDefaultHomeNotLocked(int userId) {
        if (Thread.holdsLock(mLock)) {
            Slog.wtf(TAG, "Calling thread " + Thread.currentThread().getName()
                    + " is holding mLock", new Throwable());
        }
        if (!mSystemReady) {
            // We might get called before system is ready because of package changes etc, but
            // finding preferred activity depends on settings provider, so we ignore the update
            // before that.
            return false;
        }
        final Intent intent = getHomeIntent();
        final List<ResolveInfo> resolveInfos = queryIntentActivitiesInternal(intent, null,
                MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE, userId);
        final ResolveInfo preferredResolveInfo = findPreferredActivityNotLocked(
                intent, null, 0, resolveInfos, true, false, false, userId);
        final String packageName = preferredResolveInfo != null
                && preferredResolveInfo.activityInfo != null
                ? preferredResolveInfo.activityInfo.packageName : null;
        final String currentPackageName = mDefaultAppProvider.getDefaultHome(userId);
        if (TextUtils.equals(currentPackageName, packageName)) {
            return false;
        }
        final String[] callingPackages = getPackagesForUid(Binder.getCallingUid());
        if (callingPackages != null && ArrayUtils.contains(callingPackages,
                mRequiredPermissionControllerPackage)) {
            // PermissionController manages default home directly.
            return false;
        }

        if (packageName == null) {
            // Keep the default home package in RoleManager.
            return false;
        }
        return mDefaultAppProvider.setDefaultHome(packageName, userId, mContext.getMainExecutor(),
                successful -> {
                    if (successful) {
                        postPreferredActivityChangedBroadcast(userId);
                    }
                });
    }

    @Override
    public void setHomeActivity(ComponentName comp, int userId) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        ArrayList<ResolveInfo> homeActivities = new ArrayList<>();
        getHomeActivitiesAsUser(homeActivities, userId);

        boolean found = false;

        final int size = homeActivities.size();
        final ComponentName[] set = new ComponentName[size];
        for (int i = 0; i < size; i++) {
            final ResolveInfo candidate = homeActivities.get(i);
            final ActivityInfo info = candidate.activityInfo;
            final ComponentName activityName = new ComponentName(info.packageName, info.name);
            set[i] = activityName;
            if (!found && activityName.equals(comp)) {
                found = true;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Component " + comp + " cannot be home on user "
                    + userId);
        }
        replacePreferredActivity(getHomeFilter(), IntentFilter.MATCH_CATEGORY_EMPTY,
                set, comp, userId);
    }

    private @Nullable String getSetupWizardPackageNameImpl() {
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_SETUP_WIZARD);

        final List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null,
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

    private @Nullable String getStorageManagerPackageName() {
        final Intent intent = new Intent(StorageManager.ACTION_MANAGE_STORAGE);

        final List<ResolveInfo> matches = queryIntentActivitiesInternal(intent, null,
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

    @Override
    public String getDefaultTextClassifierPackageName() {
        return ensureSystemPackageName(
                mContext.getString(R.string.config_servicesExtensionPackage));
    }

    @Override
    public String getSystemTextClassifierPackageName() {
        return ensureSystemPackageName(
                mContext.getString(R.string.config_defaultTextClassifierPackage));
    }

    @Override
    public @Nullable String getAttentionServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultAttentionService));
    }

    @Override
    public @Nullable String getRotationResolverPackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultRotationResolverService));
    }

    @Nullable
    private String getDeviceConfiguratorPackageName() {
        return ensureSystemPackageName(mContext.getString(
                R.string.config_deviceConfiguratorPackageName));
    }

    @Override
    public String getWellbeingPackageName() {
        final long identity = Binder.clearCallingIdentity();
        try {
            return CollectionUtils.firstOrNull(
                    mContext.getSystemService(RoleManager.class).getRoleHolders(
                            RoleManager.ROLE_SYSTEM_WELLBEING));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String getAppPredictionServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultAppPredictionService));
    }

    @Override
    public String getSystemCaptionsServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultSystemCaptionsService));
    }

    @Override
    public String getSetupWizardPackageName() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }
        return mPmInternal.getSetupWizardPackageName();
    }

    public String getIncidentReportApproverPackageName() {
        return ensureSystemPackageName(mContext.getString(
                R.string.config_incidentReportApproverPackage));
    }

    @Override
    public String getContentCaptureServicePackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_defaultContentCaptureService));
    }

    public String getOverlayConfigSignaturePackageName() {
        return ensureSystemPackageName(mInjector.getSystemConfig()
                .getOverlayConfigSignaturePackage());
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
    private String getRecentsPackageName() {
        return ensureSystemPackageName(
                getPackageFromComponentString(R.string.config_recentsComponentName));

    }

    @Nullable
    private String getPackageFromComponentString(@StringRes int stringResId) {
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
    private String ensureSystemPackageName(@Nullable String packageName) {
        if (packageName == null) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (getPackageInfo(packageName, MATCH_FACTORY_ONLY, UserHandle.USER_SYSTEM) == null) {
                PackageInfo packageInfo = getPackageInfo(packageName, 0, UserHandle.USER_SYSTEM);
                if (packageInfo != null) {
                    EventLog.writeEvent(0x534e4554, "145981139", packageInfo.applicationInfo.uid,
                            "");
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return packageName;
    }

    @Override
    public void setApplicationEnabledSetting(String appPackageName,
            int newState, int flags, int userId, String callingPackage) {
        if (!mUserManager.exists(userId)) return;
        if (callingPackage == null) {
            callingPackage = Integer.toString(Binder.getCallingUid());
        }

        setEnabledSettings(List.of(new ComponentEnabledSetting(appPackageName, newState, flags)),
                userId, callingPackage);
    }

    @Override
    public void setUpdateAvailable(String packageName, boolean updateAvailable) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES, null);
        synchronized (mLock) {
            final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
            if (pkgSetting != null) {
                pkgSetting.setUpdateAvailable(updateAvailable);
            }
        }
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
    public void restoreLabelAndIcon(@NonNull ComponentName componentName, int userId) {
        updateComponentLabelIcon(componentName, null, null, userId);
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void updateComponentLabelIcon(/*@NonNull*/ ComponentName componentName,
            @Nullable String nonLocalizedLabel, @Nullable Integer icon, int userId) {
        if (componentName == null) {
            throw new IllegalArgumentException("Must specify a component");
        }

        int callingUid = Binder.getCallingUid();

        String componentPkgName = componentName.getPackageName();
        int componentUid = getPackageUid(componentPkgName, 0, userId);
        if (!UserHandle.isSameApp(callingUid, componentUid)) {
            throw new SecurityException("The calling UID (" + callingUid + ")"
                    + " does not match the target UID");
        }

        String allowedCallerPkg = mContext.getString(R.string.config_overrideComponentUiPackage);
        if (TextUtils.isEmpty(allowedCallerPkg)) {
            throw new SecurityException(
                    "There is no package defined as allowed to change a component's label or icon");
        }

        int allowedCallerUid = getPackageUid(allowedCallerPkg, PackageManager.MATCH_SYSTEM_ONLY,
                userId);
        if (allowedCallerUid == -1 || !UserHandle.isSameApp(callingUid, allowedCallerUid)) {
            throw new SecurityException("The calling UID (" + callingUid + ")"
                    + " is not allowed to change a component's label or icon");
        }

        synchronized (mLock) {
            AndroidPackage pkg = mPackages.get(componentPkgName);
            PackageSetting pkgSetting = getPackageSetting(componentPkgName);
            if (pkg == null || pkgSetting == null
                    || (!pkg.isSystem() && !pkgSetting.getPkgState().isUpdatedSystemApp())) {
                throw new SecurityException(
                        "Changing the label is not allowed for " + componentName);
            }

            if (!mComponentResolver.componentExists(componentName)) {
                throw new IllegalArgumentException("Component " + componentName + " not found");
            }

            if (!pkgSetting.overrideNonLocalizedLabelAndIcon(componentName, nonLocalizedLabel,
                    icon, userId)) {
                // Nothing changed
                return;
            }
        }

        ArrayList<String> components = mPendingBroadcasts.get(userId, componentPkgName);
        if (components == null) {
            components = new ArrayList<>();
            mPendingBroadcasts.put(userId, componentPkgName, components);
        }

        String className = componentName.getClassName();
        if (!components.contains(className)) {
            components.add(className);
        }

        if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
            mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
        }
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags, int userId) {
        if (!mUserManager.exists(userId)) return;

        setEnabledSettings(List.of(new ComponentEnabledSetting(componentName, newState, flags)),
                userId, null /* callingPackage */);
    }

    @Override
    public void setComponentEnabledSettings(List<ComponentEnabledSetting> settings, int userId) {
        if (!mUserManager.exists(userId)) return;
        if (settings == null || settings.isEmpty()) {
            throw new IllegalArgumentException("The list of enabled settings is empty");
        }

        setEnabledSettings(settings, userId, null /* callingPackage */);
    }

    private void setEnabledSettings(List<ComponentEnabledSetting> settings, int userId,
            String callingPackage) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                true /* checkShell */, "set enabled");

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
            // Checks for target packages
            for (int i = 0; i < targetSize; i++) {
                final ComponentEnabledSetting setting = settings.get(i);
                final String packageName = setting.getPackageName();
                if (pkgSettings.containsKey(packageName)) {
                    // this package has verified
                    continue;
                }
                final boolean isCallerTargetApp = ArrayUtils.contains(
                        getPackagesForUid(callingUid), packageName);
                final PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
                // Limit who can change which apps
                if (!isCallerTargetApp) {
                    // Don't allow apps that don't have permission to modify other apps
                    if (!allowedByPermission
                            || shouldFilterApplicationLocked(pkgSetting, callingUid, userId)) {
                        throw new SecurityException("Attempt to change component state; "
                                + "pid=" + Binder.getCallingPid()
                                + ", uid=" + callingUid
                                + (!setting.isComponent() ? ", package=" + packageName
                                        : ", component=" + setting.getComponentName()));
                    }
                    // Don't allow changing protected packages.
                    if (mProtectedPackages.isPackageStateProtected(userId, packageName)) {
                        throw new SecurityException(
                                "Cannot disable a protected package: " + packageName);
                    }
                }
                if (pkgSetting == null) {
                    throw new IllegalArgumentException(setting.isComponent()
                            ? "Unknown component: " + setting.getComponentName()
                            : "Unknown package: " + packageName);
                }
                if (callingUid == Process.SHELL_UID
                        && (pkgSetting.pkgFlags & ApplicationInfo.FLAG_TEST_ONLY) == 0) {
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

        final InitAndSystemPackageHelper helper = new InitAndSystemPackageHelper(this);
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
                    && deletedPkg.isSystem();
            if (isSystemStub
                    && (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
                if (!helper.enableCompressedPackage(deletedPkg, pkgSetting, mDefParseFlags,
                        mDirsToScanAsSystem)) {
                    Slog.w(TAG, "Failed setApplicationEnabledSetting: failed to enable "
                            + "commpressed package " + setting.getPackageName());
                    updateAllowed[i] = false;
                    continue;
                }
            }
        }

        // packageName -> list of components to send broadcasts now
        final ArrayMap<String, ArrayList<String>> sendNowBroadcasts = new ArrayMap<>(targetSize);
        synchronized (mLock) {
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
                if (!setEnabledSettingInternalLocked(pkgSettings.get(packageName), setting,
                        userId, callingPackage)) {
                    continue;
                }
                anyChanged = true;

                if ((setting.getEnabledFlags() & PackageManager.SYNCHRONOUS) != 0) {
                    isSynchronous = true;
                }
                // collect broadcast list for the package
                final String componentName = setting.isComponent()
                        ? setting.getClassName() : packageName;
                ArrayList<String> componentList = sendNowBroadcasts.get(packageName);
                if (componentList == null) {
                    componentList = mPendingBroadcasts.get(userId, packageName);
                }
                final boolean newPackage = componentList == null;
                if (newPackage) {
                    componentList = new ArrayList<>();
                }
                if (!componentList.contains(componentName)) {
                    componentList.add(componentName);
                }
                if ((setting.getEnabledFlags() & PackageManager.DONT_KILL_APP) == 0) {
                    sendNowBroadcasts.put(packageName, componentList);
                    // Purge entry from pending broadcast list if another one exists already
                    // since we are sending one right away.
                    mPendingBroadcasts.remove(userId, packageName);
                } else {
                    if (newPackage) {
                        mPendingBroadcasts.put(userId, packageName, componentList);
                    }
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
                scheduleWritePackageRestrictionsLocked(userId);
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
            for (int i = 0; i < sendNowBroadcasts.size(); i++) {
                final String packageName = sendNowBroadcasts.keyAt(i);
                final ArrayList<String> components = sendNowBroadcasts.valueAt(i);
                final int packageUid = UserHandle.getUid(
                        userId, pkgSettings.get(packageName).getAppId());
                sendPackageChangedBroadcast(packageName, false /* dontKillApp */,
                        components, packageUid, null /* reason */);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private boolean setEnabledSettingInternalLocked(PackageSetting pkgSetting,
            ComponentEnabledSetting setting, int userId, String callingPackage) {
        final int newState = setting.getEnabledState();
        final String packageName = setting.getPackageName();
        boolean success = false;
        if (!setting.isComponent()) {
            // We're dealing with an application/package level state change
            if (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                // Don't care about who enables an app.
                callingPackage = null;
            }
            pkgSetting.setEnabled(newState, userId, callingPackage);
            if ((newState == COMPONENT_ENABLED_STATE_DISABLED_USER
                    || newState == COMPONENT_ENABLED_STATE_DISABLED)
                    && checkPermission(Manifest.permission.SUSPEND_APPS, packageName, userId)
                    == PERMISSION_GRANTED) {
                // This app should not generally be allowed to get disabled by the UI, but
                // if it ever does, we don't want to end up with some of the user's apps
                // permanently suspended.
                unsuspendForSuspendingPackage(packageName, userId);
                removeAllDistractingPackageRestrictions(userId);
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

    @WorkerThread
    @Override
    public void flushPackageRestrictionsAsUser(int userId) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        if (!mUserManager.exists(userId)) {
            return;
        }
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false /* requireFullPermission*/,
                false /* checkShell */, "flushPackageRestrictions");
        synchronized (mLock) {
            flushPackageRestrictionsAsUserInternalLocked(userId);
        }
    }

    @GuardedBy("mLock")
    private void flushPackageRestrictionsAsUserInternalLocked(int userId) {
        // NOTE: this invokes synchronous disk access, so callers using this
        // method should consider running on a background thread
        mSettings.writePackageRestrictionsLPr(userId);
        mDirtyUsers.remove(userId);
        if (mDirtyUsers.isEmpty()) {
            mHandler.removeMessages(WRITE_PACKAGE_RESTRICTIONS);
        }
    }

    void sendPackageChangedBroadcast(String packageName,
            boolean dontKillApp, ArrayList<String> componentNames, int packageUid, String reason) {
        if (DEBUG_INSTALL)
            Log.v(TAG, "Sending package changed: package=" + packageName + " components="
                    + componentNames);
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String[] nameList = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, dontKillApp);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        if (reason != null) {
            extras.putString(Intent.EXTRA_REASON, reason);
        }
        // If this is not reporting a change of the overall package, then only send it
        // to registered receivers.  We don't want to launch a swath of apps for every
        // little component state change.
        final int flags = !componentNames.contains(packageName)
                ? Intent.FLAG_RECEIVER_REGISTERED_ONLY : 0;
        final int userId = UserHandle.getUserId(packageUid);
        final boolean isInstantApp = isInstantApp(packageName, userId);
        final int[] userIds = isInstantApp ? EMPTY_INT_ARRAY : new int[] { userId };
        final int[] instantUserIds = isInstantApp ? new int[] { userId } : EMPTY_INT_ARRAY;
        final SparseArray<int[]> broadcastAllowList;
        synchronized (mLock) {
            PackageSetting setting = getPackageSettingInternal(packageName, Process.SYSTEM_UID);
            if (setting == null) {
                return;
            }
            broadcastAllowList = isInstantApp ? null : mAppsFilter.getVisibilityAllowList(setting,
                    userIds, mSettings.getPackagesLocked());
        }
        sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED,  packageName, extras, flags, null, null,
                userIds, instantUserIds, broadcastAllowList, null);
    }

    @Override
    public void setPackageStoppedState(String packageName, boolean stopped, int userId) {
        if (!mUserManager.exists(userId)) return;
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return;
        }
        final int permission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
        if (!allowedByPermission
                && !ArrayUtils.contains(getPackagesForUid(callingUid), packageName)) {
            throw new SecurityException(
                    "Permission Denial: attempt to change stopped state from pid="
                            + Binder.getCallingPid()
                            + ", uid=" + callingUid + ", package=" + packageName);
        }
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                true /* checkShell */, "stop package");
        boolean shouldUnhibernate = false;
        // writer
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps != null && ps.getStopped(userId) && !stopped) {
                shouldUnhibernate = true;
            }
            if (!shouldFilterApplicationLocked(ps, callingUid, userId)
                    && mSettings.setPackageStoppedStateLPw(this, packageName, stopped, userId)) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
        if (shouldUnhibernate) {
            mHandler.post(() -> {
                AppHibernationManagerInternal ah =
                        mInjector.getLocalService(AppHibernationManagerInternal.class);
                ah.setHibernatingForUser(packageName, userId, false);
                ah.setHibernatingGlobally(packageName, false);
            });
        }
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        final int callingUid = Binder.getCallingUid();
        synchronized (mLock) {
            final InstallSource installSource = getInstallSourceLocked(packageName, callingUid);
            if (installSource == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            String installerPackageName = installSource.installerPackageName;
            if (installerPackageName != null) {
                final PackageSetting ps = mSettings.getPackageLPr(installerPackageName);
                if (ps == null || shouldFilterApplicationLocked(ps, callingUid,
                        UserHandle.getUserId(callingUid))) {
                    installerPackageName = null;
                }
            }
            return installerPackageName;
        }
    }

    @Override
    @Nullable
    public InstallSourceInfo getInstallSourceInfo(String packageName) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);

        String installerPackageName;
        String initiatingPackageName;
        String originatingPackageName;

        final InstallSource installSource;
        synchronized (mLock) {
            installSource = getInstallSourceLocked(packageName, callingUid);
            if (installSource == null) {
                return null;
            }

            installerPackageName = installSource.installerPackageName;
            if (installerPackageName != null) {
                final PackageSetting ps = mSettings.getPackageLPr(installerPackageName);
                if (ps == null || shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    installerPackageName = null;
                }
            }

            if (installSource.isInitiatingPackageUninstalled) {
                // We can't check visibility in the usual way, since the initiating package is no
                // longer present. So we apply simpler rules to whether to expose the info:
                // 1. Instant apps can't see it.
                // 2. Otherwise only the installed app itself can see it.
                final boolean isInstantApp = getInstantAppPackageName(callingUid) != null;
                if (!isInstantApp && isCallerSameApp(packageName, callingUid)) {
                    initiatingPackageName = installSource.initiatingPackageName;
                } else {
                    initiatingPackageName = null;
                }
            } else {
                // All installSource strings are interned, so == is ok here
                if (installSource.initiatingPackageName == installSource.installerPackageName) {
                    // The installer and initiator will often be the same, and when they are
                    // we can skip doing the same check again.
                    initiatingPackageName = installerPackageName;
                } else {
                    initiatingPackageName = installSource.initiatingPackageName;
                    final PackageSetting ps = mSettings.getPackageLPr(initiatingPackageName);
                    if (ps == null || shouldFilterApplicationLocked(ps, callingUid, userId)) {
                        initiatingPackageName = null;
                    }
                }
            }

            originatingPackageName = installSource.originatingPackageName;
            if (originatingPackageName != null) {
                final PackageSetting ps = mSettings.getPackageLPr(originatingPackageName);
                if (ps == null || shouldFilterApplicationLocked(ps, callingUid, userId)) {
                    originatingPackageName = null;
                }
            }
        }

        // Remaining work can safely be done outside the lock. (Note that installSource is
        // immutable so it's ok to carry on reading from it.)

        if (originatingPackageName != null && mContext.checkCallingOrSelfPermission(
                Manifest.permission.INSTALL_PACKAGES) != PackageManager.PERMISSION_GRANTED) {
            originatingPackageName = null;
        }

        // If you can see the initiatingPackageName, and we have valid signing info for it,
        // then we let you see that too.
        final SigningInfo initiatingPackageSigningInfo;
        final PackageSignatures signatures = installSource.initiatingPackageSignatures;
        if (initiatingPackageName != null && signatures != null
                && signatures.mSigningDetails != SigningDetails.UNKNOWN) {
            initiatingPackageSigningInfo = new SigningInfo(signatures.mSigningDetails);
        } else {
            initiatingPackageSigningInfo = null;
        }

        return new InstallSourceInfo(initiatingPackageName, initiatingPackageSigningInfo,
                originatingPackageName, installerPackageName);
    }

    @GuardedBy("mLock")
    @Nullable
    private InstallSource getInstallSourceLocked(String packageName, int callingUid) {
        final PackageSetting ps = mSettings.getPackageLPr(packageName);

        // Installer info for Apex is not stored in PackageManager
        if (ps == null && mApexManager.isApexPackage(packageName)) {
            return InstallSource.EMPTY;
        }

        if (ps == null || shouldFilterApplicationLocked(ps, callingUid,
                UserHandle.getUserId(callingUid))) {
            return null;
        }

        return ps.getInstallSource();
    }

    public boolean isOrphaned(String packageName) {
        // reader
        synchronized (mLock) {
            if (!mPackages.containsKey(packageName)) {
                return false;
            }
            return mSettings.isOrphaned(packageName);
        }
    }

    @Override
    public int getApplicationEnabledSetting(String packageName, int userId) {
        if (!mUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get enabled");
        // reader
        synchronized (mLock) {
            try {
                if (shouldFilterApplicationLocked(
                        mSettings.getPackageLPr(packageName), callingUid, userId)) {
                    throw new PackageManager.NameNotFoundException(packageName);
                }
                return mSettings.getApplicationEnabledSettingLPr(packageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
        }
    }

    @Override
    public int getComponentEnabledSetting(@NonNull ComponentName component, int userId) {
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "getComponentEnabled");
        return getComponentEnabledSettingInternal(component, callingUid, userId);
    }

    private int getComponentEnabledSettingInternal(ComponentName component, int callingUid,
            int userId) {
        if (component == null) return COMPONENT_ENABLED_STATE_DEFAULT;
        if (!mUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;

        synchronized (mLock) {
            try {
                if (shouldFilterApplicationLocked(
                        mSettings.getPackageLPr(component.getPackageName()), callingUid,
                        component, TYPE_UNKNOWN, userId)) {
                    throw new PackageManager.NameNotFoundException(component.getPackageName());
                }
                return mSettings.getComponentEnabledSettingLPr(component, userId);
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException("Unknown component: " + component);
            }
        }
    }

    /**
     * @return true if the runtime app user enabled state, runtime component user enabled state,
     * install-time app manifest enabled state, and install-time component manifest enabled state
     * are all effectively enabled for the given component. Or if the component cannot be found,
     * returns false.
     */
    private boolean isComponentEffectivelyEnabled(@NonNull ComponentInfo componentInfo,
            @UserIdInt int userId) {
        synchronized (mLock) {
            try {
                String packageName = componentInfo.packageName;
                int appEnabledSetting =
                        mSettings.getApplicationEnabledSettingLPr(packageName, userId);
                if (appEnabledSetting == COMPONENT_ENABLED_STATE_DEFAULT) {
                    if (!componentInfo.applicationInfo.enabled) {
                        return false;
                    }
                } else if (appEnabledSetting != COMPONENT_ENABLED_STATE_ENABLED) {
                    return false;
                }

                int componentEnabledSetting = mSettings.getComponentEnabledSettingLPr(
                                componentInfo.getComponentName(), userId);
                if (componentEnabledSetting == COMPONENT_ENABLED_STATE_DEFAULT) {
                    return componentInfo.isEnabled();
                } else return componentEnabledSetting == COMPONENT_ENABLED_STATE_ENABLED;
            } catch (PackageManager.NameNotFoundException ignored) {
                return false;
            }
        }
    }

    @Override
    public void enterSafeMode() {
        enforceSystemOrRoot("Only the system can request entering safe mode");

        if (!mSystemReady) {
            mSafeMode = true;
        }
    }

    @Override
    public void systemReady() {
        enforceSystemOrRoot("Only the system can claim the system is ready");

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

        mAppsFilter.onSystemReady();

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
        final StorageEventHelper storageEventHelper = new StorageEventHelper(this);
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        storage.registerListener(storageEventHelper);

        mInstallerService.systemReady();
        mPackageDexOptimizer.systemReady();

        // Now that we're mostly running, clean up stale users and apps
        mUserManager.reconcileUsers(StorageManager.UUID_PRIVATE_INTERNAL);
        storageEventHelper.reconcileApps(StorageManager.UUID_PRIVATE_INTERNAL);

        mPermissionManager.onSystemReady();

        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;
        final List<UserInfo> livingUsers = mInjector.getUserManagerInternal().getUsers(
                /* excludePartial= */ true,
                /* excludeDying= */ true,
                /* excludePreCreated= */ false);
        final int livingUserCount = livingUsers.size();
        for (int i = 0; i < livingUserCount; i++) {
            final int userId = livingUsers.get(i).id;
            if (mPmInternal.isPermissionUpgradeNeeded(userId)) {
                grantPermissionsUserIds = ArrayUtils.appendInt(
                        grantPermissionsUserIds, userId);
            }
        }
        // If we upgraded grant all default permissions before kicking off.
        for (int userId : grantPermissionsUserIds) {
            mLegacyPermissionManager.grantDefaultPermissions(userId);
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
                AndroidPackage pkg = mPackages.get(packageName);
                if (pkg == null) {
                    return;
                }
                sendPackageChangedBroadcast(pkg.getPackageName(),
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
    }

    public void waitForAppDataPrepared() {
        if (mPrepareAppDataFuture == null) {
            return;
        }
        ConcurrentUtils.waitForFutureNoInterrupt(mPrepareAppDataFuture, "wait for prepareAppData");
        mPrepareAppDataFuture = null;
    }

    @Override
    public boolean isSafeMode() {
        // allow instant applications
        return mSafeMode;
    }

    @Override
    public boolean hasSystemUidErrors() {
        // allow instant applications
        return mHasSystemUidErrors;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new PackageManagerShellCommand(this, mContext,mDomainVerificationManager.getShell()))
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    @SuppressWarnings("resource")
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        DumpState dumpState = new DumpState();
        ArraySet<String> permissionNames = null;

        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;

            if ("-a".equals(opt)) {
                // Right now we only know how to print all.
            } else if ("-h".equals(opt)) {
                pw.println("Package manager dump options:");
                pw.println("  [-h] [-f] [--checkin] [--all-components] [cmd] ...");
                pw.println("    --checkin: dump for a checkin");
                pw.println("    -f: print details of intent filters");
                pw.println("    -h: print this help");
                pw.println("    --all-components: include all component names in package dump");
                pw.println("  cmd may be one of:");
                pw.println("    apex: list active APEXes and APEX session state");
                pw.println("    l[ibraries]: list known shared libraries");
                pw.println("    f[eatures]: list device features");
                pw.println("    k[eysets]: print known keysets");
                pw.println("    r[esolvers] [activity|service|receiver|content]: dump intent resolvers");
                pw.println("    perm[issions]: dump permissions");
                pw.println("    permission [name ...]: dump declaration and use of given permission");
                pw.println("    pref[erred]: print preferred package settings");
                pw.println("    preferred-xml [--full]: print preferred package settings as xml");
                pw.println("    prov[iders]: dump content providers");
                pw.println("    p[ackages]: dump installed packages");
                pw.println("    q[ueries]: dump app queryability calculations");
                pw.println("    s[hared-users]: dump shared user IDs");
                pw.println("    m[essages]: print collected runtime messages");
                pw.println("    v[erifiers]: print package verifier info");
                pw.println("    d[omain-preferred-apps]: print domains preferred apps");
                pw.println("    i[ntent-filter-verifiers]|ifv: print intent filter verifier info");
                pw.println("    t[imeouts]: print read timeouts for known digesters");
                pw.println("    version: print database version info");
                pw.println("    write: write current settings now");
                pw.println("    installs: details about install sessions");
                pw.println("    check-permission <permission> <package> [<user>]: does pkg hold perm?");
                pw.println("    dexopt: dump dexopt state");
                pw.println("    compiler-stats: dump compiler statistics");
                pw.println("    service-permissions: dump permissions required by services");
                pw.println("    snapshot: dump snapshot statistics");
                pw.println("    protected-broadcasts: print list of protected broadcast actions");
                pw.println("    known-packages: dump known packages");
                pw.println("    <package.name>: info about given package");
                return;
            } else if ("--checkin".equals(opt)) {
                dumpState.setCheckIn(true);
            } else if ("--all-components".equals(opt)) {
                dumpState.setOptionEnabled(DumpState.OPTION_DUMP_ALL_COMPONENTS);
            } else if ("-f".equals(opt)) {
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else if ("--proto".equals(opt)) {
                dumpProto(fd);
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }

        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            // Is this a package name?
            if ("android".equals(cmd) || cmd.contains(".")) {
                dumpState.setTargetPackageName(cmd);
                // When dumping a single package, we always dump all of its
                // filter information since the amount of data will be reasonable.
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else if ("check-permission".equals(cmd)) {
                if (opti >= args.length) {
                    pw.println("Error: check-permission missing permission argument");
                    return;
                }
                String perm = args[opti];
                opti++;
                if (opti >= args.length) {
                    pw.println("Error: check-permission missing package argument");
                    return;
                }

                String pkg = args[opti];
                opti++;
                int user = UserHandle.getUserId(Binder.getCallingUid());
                if (opti < args.length) {
                    try {
                        user = Integer.parseInt(args[opti]);
                    } catch (NumberFormatException e) {
                        pw.println("Error: check-permission user argument is not a number: "
                                + args[opti]);
                        return;
                    }
                }

                // Normalize package name to handle renamed packages and static libs
                pkg = resolveInternalPackageNameLPr(pkg, PackageManager.VERSION_CODE_HIGHEST);

                pw.println(checkPermission(perm, pkg, user));
                return;
            } else if ("l".equals(cmd) || "libraries".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_LIBS);
            } else if ("f".equals(cmd) || "features".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_FEATURES);
            } else if ("r".equals(cmd) || "resolvers".equals(cmd)) {
                if (opti >= args.length) {
                    dumpState.setDump(DumpState.DUMP_ACTIVITY_RESOLVERS
                            | DumpState.DUMP_SERVICE_RESOLVERS
                            | DumpState.DUMP_RECEIVER_RESOLVERS
                            | DumpState.DUMP_CONTENT_RESOLVERS);
                } else {
                    while (opti < args.length) {
                        String name = args[opti];
                        if ("a".equals(name) || "activity".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_ACTIVITY_RESOLVERS);
                        } else if ("s".equals(name) || "service".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_SERVICE_RESOLVERS);
                        } else if ("r".equals(name) || "receiver".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_RECEIVER_RESOLVERS);
                        } else if ("c".equals(name) || "content".equals(name)) {
                            dumpState.setDump(DumpState.DUMP_CONTENT_RESOLVERS);
                        } else {
                            pw.println("Error: unknown resolver table type: " + name);
                            return;
                        }
                        opti++;
                    }
                }
            } else if ("perm".equals(cmd) || "permissions".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PERMISSIONS);
            } else if ("permission".equals(cmd)) {
                if (opti >= args.length) {
                    pw.println("Error: permission requires permission name");
                    return;
                }
                permissionNames = new ArraySet<>();
                while (opti < args.length) {
                    permissionNames.add(args[opti]);
                    opti++;
                }
                dumpState.setDump(DumpState.DUMP_PERMISSIONS
                        | DumpState.DUMP_PACKAGES | DumpState.DUMP_SHARED_USERS);
            } else if ("pref".equals(cmd) || "preferred".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PREFERRED);
            } else if ("preferred-xml".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PREFERRED_XML);
                if (opti < args.length && "--full".equals(args[opti])) {
                    dumpState.setFullPreferred(true);
                    opti++;
                }
            } else if ("d".equals(cmd) || "domain-preferred-apps".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DOMAIN_PREFERRED);
            } else if ("p".equals(cmd) || "packages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PACKAGES);
            } else if ("q".equals(cmd) || "queries".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_QUERIES);
            } else if ("s".equals(cmd) || "shared-users".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SHARED_USERS);
                if (opti < args.length && "noperm".equals(args[opti])) {
                    dumpState.setOptionEnabled(DumpState.OPTION_SKIP_PERMISSIONS);
                }
            } else if ("prov".equals(cmd) || "providers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PROVIDERS);
            } else if ("m".equals(cmd) || "messages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_MESSAGES);
            } else if ("v".equals(cmd) || "verifiers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERIFIERS);
            } else if ("dv".equals(cmd) || "domain-verifier".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DOMAIN_VERIFIER);
            } else if ("version".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERSION);
            } else if ("k".equals(cmd) || "keysets".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_KEYSETS);
            } else if ("installs".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_INSTALLS);
            } else if ("frozen".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_FROZEN);
            } else if ("volumes".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VOLUMES);
            } else if ("dexopt".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DEXOPT);
            } else if ("compiler-stats".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_COMPILER_STATS);
            } else if ("changes".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_CHANGES);
            } else if ("service-permissions".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SERVICE_PERMISSIONS);
            } else if ("known-packages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_KNOWN_PACKAGES);
            } else if ("t".equals(cmd) || "timeouts".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PER_UID_READ_TIMEOUTS);
            } else if ("snapshot".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SNAPSHOT_STATISTICS);
                if (opti < args.length) {
                    if ("--full".equals(args[opti])) {
                        dumpState.setBrief(false);
                        opti++;
                    } else if ("--brief".equals(args[opti])) {
                        dumpState.setBrief(true);
                        opti++;
                    }
                }
            } else if ("protected-broadcasts".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PROTECTED_BROADCASTS);
            } else if ("write".equals(cmd)) {
                synchronized (mLock) {
                    writeSettingsLPrTEMP();
                    pw.println("Settings written.");
                    return;
                }
            }
        }

        final String packageName = dumpState.getTargetPackageName();
        final boolean checkin = dumpState.isCheckIn();

        // Return if the package doesn't exist.
        if (packageName != null
                && getPackageSetting(packageName) == null
                && !mApexManager.isApexPackage(packageName)) {
            pw.println("Unable to find package: " + packageName);
            return;
        }

        if (checkin) {
            pw.println("vers,1");
        }

        // reader
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_VERSION)
                && packageName == null) {
            dump(DumpState.DUMP_VERSION, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_KNOWN_PACKAGES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            ipw.println("Known Packages:");
            ipw.increaseIndent();
            for (int i = 0; i <= LAST_KNOWN_PACKAGE; i++) {
                final String knownPackage = PackageManagerInternal.knownPackageToString(i);
                ipw.print(knownPackage);
                ipw.println(":");
                final String[] pkgNames = mPmInternal.getKnownPackageNames(i,
                        UserHandle.USER_SYSTEM);
                ipw.increaseIndent();
                if (ArrayUtils.isEmpty(pkgNames)) {
                    ipw.println("none");
                } else {
                    for (String name : pkgNames) {
                        ipw.println(name);
                    }
                }
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
        }

        if (dumpState.isDumping(DumpState.DUMP_VERIFIERS)
                && packageName == null) {
            final String requiredVerifierPackage = mRequiredVerifierPackage;
            if (!checkin) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                pw.println("Verifiers:");
                pw.print("  Required: ");
                pw.print(requiredVerifierPackage);
                pw.print(" (uid=");
                pw.print(getPackageUid(requiredVerifierPackage, MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.USER_SYSTEM));
                pw.println(")");
            } else if (requiredVerifierPackage != null) {
                pw.print("vrfy,"); pw.print(requiredVerifierPackage);
                pw.print(",");
                pw.println(getPackageUid(requiredVerifierPackage, MATCH_DEBUG_TRIAGED_MISSING,
                        UserHandle.USER_SYSTEM));
            }
        }

        if (dumpState.isDumping(DumpState.DUMP_DOMAIN_VERIFIER)
                && packageName == null) {
            final DomainVerificationProxy proxy = mDomainVerificationManager.getProxy();
            final ComponentName verifierComponent = proxy.getComponentName();
            if (verifierComponent != null) {
                String verifierPackageName = verifierComponent.getPackageName();
                if (!checkin) {
                    if (dumpState.onTitlePrinted()) {
                        pw.println();
                    }
                    pw.println("Domain Verifier:");
                    pw.print("  Using: ");
                    pw.print(verifierPackageName);
                    pw.print(" (uid=");
                    pw.print(getPackageUid(verifierPackageName, MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.USER_SYSTEM));
                    pw.println(")");
                } else if (verifierPackageName != null) {
                    pw.print("dv,"); pw.print(verifierPackageName);
                    pw.print(",");
                    pw.println(getPackageUid(verifierPackageName, MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.USER_SYSTEM));
                }
            } else {
                pw.println();
                pw.println("No Domain Verifier available!");
            }
        }

        if (dumpState.isDumping(DumpState.DUMP_LIBS)
                && packageName == null) {
            dump(DumpState.DUMP_LIBS, fd, pw, dumpState);
        }

        if (dumpState.isDumping(DumpState.DUMP_FEATURES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            if (!checkin) {
                pw.println("Features:");
            }

            synchronized (mAvailableFeatures) {
                for (FeatureInfo feat : mAvailableFeatures.values()) {
                    if (!checkin) {
                        pw.print("  ");
                        pw.print(feat.name);
                        if (feat.version > 0) {
                            pw.print(" version=");
                            pw.print(feat.version);
                        }
                        pw.println();
                    } else {
                        pw.print("feat,");
                        pw.print(feat.name);
                        pw.print(",");
                        pw.println(feat.version);
                    }
                }
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_ACTIVITY_RESOLVERS)) {
            synchronized (mLock) {
                mComponentResolver.dumpActivityResolvers(pw, dumpState, packageName);
            }
        }
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_RECEIVER_RESOLVERS)) {
            synchronized (mLock) {
                mComponentResolver.dumpReceiverResolvers(pw, dumpState, packageName);
            }
        }
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_SERVICE_RESOLVERS)) {
            synchronized (mLock) {
                mComponentResolver.dumpServiceResolvers(pw, dumpState, packageName);
            }
        }
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_CONTENT_RESOLVERS)) {
            synchronized (mLock) {
                mComponentResolver.dumpProviderResolvers(pw, dumpState, packageName);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PREFERRED)) {
            dump(DumpState.DUMP_PREFERRED, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PREFERRED_XML)
                && packageName == null) {
            dump(DumpState.DUMP_PREFERRED_XML, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_DOMAIN_PREFERRED)) {
            dump(DumpState.DUMP_DOMAIN_PREFERRED, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PERMISSIONS)) {
            synchronized (mLock) {
                mSettings.dumpPermissions(pw, packageName, permissionNames, dumpState);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PROVIDERS)) {
            synchronized (mLock) {
                mComponentResolver.dumpContentProviders(pw, dumpState, packageName);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_KEYSETS)) {
            synchronized (mLock) {
                mSettings.getKeySetManagerService().dumpLPr(pw, packageName, dumpState);
            }
        }

        if (dumpState.isDumping(DumpState.DUMP_PACKAGES)) {
            // This cannot be moved to ComputerEngine since some variables of the collections
            // in PackageUserState such as suspendParams, disabledComponents and enabledComponents
            // do not have a copy.
            synchronized (mLock) {
                mSettings.dumpPackagesLPr(pw, packageName, permissionNames, dumpState, checkin);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_QUERIES)) {
            dump(DumpState.DUMP_QUERIES, fd, pw, dumpState);
        }

        if (dumpState.isDumping(DumpState.DUMP_SHARED_USERS)) {
            // This cannot be moved to ComputerEngine since the set of packages in the
            // SharedUserSetting do not have a copy.
            synchronized (mLock) {
                mSettings.dumpSharedUsersLPr(pw, packageName, permissionNames, dumpState, checkin);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_CHANGES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Package Changes:");
            synchronized (mLock) {
                pw.print("  Sequence number="); pw.println(mChangedPackagesSequenceNumber);
                final int K = mChangedPackages.size();
                for (int i = 0; i < K; i++) {
                    final SparseArray<String> changes = mChangedPackages.valueAt(i);
                    pw.print("  User "); pw.print(mChangedPackages.keyAt(i)); pw.println(":");
                    final int N = changes.size();
                    if (N == 0) {
                        pw.print("    "); pw.println("No packages changed");
                    } else {
                        for (int j = 0; j < N; j++) {
                            final String pkgName = changes.valueAt(j);
                            final int sequenceNumber = changes.keyAt(j);
                            pw.print("    ");
                            pw.print("seq=");
                            pw.print(sequenceNumber);
                            pw.print(", package=");
                            pw.println(pkgName);
                        }
                    }
                }
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_FROZEN)
                && packageName == null) {
            // XXX should handle packageName != null by dumping only install data that
            // the given package is involved with.
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            ipw.println();
            ipw.println("Frozen packages:");
            ipw.increaseIndent();
            synchronized (mLock) {
                if (mFrozenPackages.size() == 0) {
                    ipw.println("(none)");
                } else {
                    for (int i = 0; i < mFrozenPackages.size(); i++) {
                        ipw.println(mFrozenPackages.valueAt(i));
                    }
                }
            }
            ipw.decreaseIndent();
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_VOLUMES)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            ipw.println();
            ipw.println("Loaded volumes:");
            ipw.increaseIndent();
            synchronized (mLoadedVolumes) {
                if (mLoadedVolumes.size() == 0) {
                    ipw.println("(none)");
                } else {
                    for (int i = 0; i < mLoadedVolumes.size(); i++) {
                        ipw.println(mLoadedVolumes.valueAt(i));
                    }
                }
            }
            ipw.decreaseIndent();
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_SERVICE_PERMISSIONS)
                && packageName == null) {
            synchronized (mLock) {
                mComponentResolver.dumpServicePermissions(pw, dumpState);
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_DEXOPT)) {
            dump(DumpState.DUMP_DEXOPT, fd, pw, dumpState);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_COMPILER_STATS)) {
            dump(DumpState.DUMP_COMPILER_STATS, fd, pw, dumpState);
        }

        if (dumpState.isDumping(DumpState.DUMP_MESSAGES)
                && packageName == null) {
            if (!checkin) {
                if (dumpState.onTitlePrinted()) {
                    pw.println();
                }
                synchronized (mLock) {
                    mSettings.dumpReadMessagesLPr(pw, dumpState);
                }
                pw.println();
                pw.println("Package warning messages:");
                dumpCriticalInfo(pw, null);
            } else {
                dumpCriticalInfo(pw, "msg,");
            }
        }

        // PackageInstaller should be called outside of mPackages lock
        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_INSTALLS)
                && packageName == null) {
            // XXX should handle packageName != null by dumping only install data that
            // the given package is involved with.
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            mInstallerService.dump(new IndentingPrintWriter(pw, "  ", 120));
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_APEX)
                && (packageName == null || mApexManager.isApexPackage(packageName))) {
            mApexManager.dump(pw, packageName);
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PER_UID_READ_TIMEOUTS)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Per UID read timeouts:");
            pw.println("    Default timeouts flag: " + getDefaultTimeouts());
            pw.println("    Known digesters list flag: " + getKnownDigestersList());

            PerUidReadTimeouts[] items = getPerUidReadTimeouts();
            pw.println("    Timeouts (" + items.length + "):");
            for (PerUidReadTimeouts item : items) {
                pw.print("        (");
                pw.print("uid=" + item.uid + ", ");
                pw.print("minTimeUs=" + item.minTimeUs + ", ");
                pw.print("minPendingTimeUs=" + item.minPendingTimeUs + ", ");
                pw.print("maxPendingTimeUs=" + item.maxPendingTimeUs);
                pw.println(")");
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_SNAPSHOT_STATISTICS)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Snapshot statistics");
            if (!mSnapshotEnabled) {
                pw.println("  Snapshots disabled");
            } else {
                int hits = 0;
                int level = sSnapshotCorked.get();
                synchronized (mSnapshotLock) {
                    if (mSnapshotComputer != null) {
                        hits = mSnapshotComputer.getUsed();
                    }
                }
                final long now = SystemClock.currentTimeMicro();
                mSnapshotStatistics.dump(pw, "  ", now, hits, level, dumpState.isBrief());
            }
        }

        if (!checkin
                && dumpState.isDumping(DumpState.DUMP_PROTECTED_BROADCASTS)
                && packageName == null) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Protected broadcast actions:");
            synchronized (mProtectedBroadcasts) {
                for (int i = 0; i < mProtectedBroadcasts.size(); i++) {
                    pw.print("  ");
                    pw.println(mProtectedBroadcasts.valueAt(i));
                }
            }

        }
    }

    /**
     * Dump package manager states to the file according to a given dumping type of
     * {@link DumpState}.
     */
    private void dump(int type, FileDescriptor fd, PrintWriter pw, DumpState dumpState) {
        mComputer.dump(type, fd, pw, dumpState);
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
        for (String packageName : apkList) {
            setSystemAppHiddenUntilInstalled(packageName, true);
            for (UserInfo user : mInjector.getUserManagerInternal().getUsers(false)) {
                setSystemAppInstallState(packageName, false, user.id);
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            final long requiredVerifierPackageToken =
                    proto.start(PackageServiceDumpProto.REQUIRED_VERIFIER_PACKAGE);
            proto.write(PackageServiceDumpProto.PackageShortProto.NAME, mRequiredVerifierPackage);
            proto.write(
                    PackageServiceDumpProto.PackageShortProto.UID,
                    getPackageUid(
                            mRequiredVerifierPackage,
                            MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.USER_SYSTEM));
            proto.end(requiredVerifierPackageToken);

            DomainVerificationProxy proxy = mDomainVerificationManager.getProxy();
            ComponentName verifierComponent = proxy.getComponentName();
            if (verifierComponent != null) {
                String verifierPackageName = verifierComponent.getPackageName();
                final long verifierPackageToken =
                        proto.start(PackageServiceDumpProto.VERIFIER_PACKAGE);
                proto.write(PackageServiceDumpProto.PackageShortProto.NAME, verifierPackageName);
                proto.write(
                        PackageServiceDumpProto.PackageShortProto.UID,
                        getPackageUid(
                                verifierPackageName,
                                MATCH_DEBUG_TRIAGED_MISSING,
                                UserHandle.USER_SYSTEM));
                proto.end(verifierPackageToken);
            }

            dumpSharedLibrariesProto(proto);
            dumpFeaturesProto(proto);
            mSettings.dumpPackagesProto(proto);
            mSettings.dumpSharedUsersProto(proto);
            dumpCriticalInfo(proto);
        }
        proto.flush();
    }

    private void dumpFeaturesProto(ProtoOutputStream proto) {
        synchronized (mAvailableFeatures) {
            final int count = mAvailableFeatures.size();
            for (int i = 0; i < count; i++) {
                mAvailableFeatures.valueAt(i).dumpDebug(proto, PackageServiceDumpProto.FEATURES);
            }
        }
    }

    private void dumpSharedLibrariesProto(ProtoOutputStream proto) {
        final int count = mSharedLibraries.size();
        for (int i = 0; i < count; i++) {
            final String libName = mSharedLibraries.keyAt(i);
            WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(libName);
            if (versionedLib == null) {
                continue;
            }
            final int versionCount = versionedLib.size();
            for (int j = 0; j < versionCount; j++) {
                final SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                final long sharedLibraryToken =
                        proto.start(PackageServiceDumpProto.SHARED_LIBRARIES);
                proto.write(PackageServiceDumpProto.SharedLibraryProto.NAME, libraryInfo.getName());
                final boolean isJar = (libraryInfo.getPath() != null);
                proto.write(PackageServiceDumpProto.SharedLibraryProto.IS_JAR, isJar);
                if (isJar) {
                    proto.write(PackageServiceDumpProto.SharedLibraryProto.PATH,
                            libraryInfo.getPath());
                } else {
                    proto.write(PackageServiceDumpProto.SharedLibraryProto.APK,
                            libraryInfo.getPackageName());
                }
                proto.end(sharedLibraryToken);
            }
        }
    }

    // ------- apps on sdcard specific code -------
    static final boolean DEBUG_SD_INSTALL = false;

    void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
            ArrayList<String> pkgList, int[] uidArr, IIntentReceiver finishedReceiver) {
        sendResourcesChangedBroadcast(mediaStatus, replacing,
                pkgList.toArray(new String[pkgList.size()]), uidArr, finishedReceiver);
    }

    void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
            String[] pkgList, int[] uidArr, IIntentReceiver finishedReceiver) {
        int size = pkgList.length;
        if (size > 0) {
            // Send broadcasts here
            Bundle extras = new Bundle();
            extras.putStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST, pkgList);
            if (uidArr != null) {
                extras.putIntArray(Intent.EXTRA_CHANGED_UID_LIST, uidArr);
            }
            if (replacing) {
                extras.putBoolean(Intent.EXTRA_REPLACING, replacing);
            }
            String action = mediaStatus ? Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE
                    : Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE;
            // TODO: not sure how to handle this one.
            sendPackageBroadcast(action, null, extras, 0, null, finishedReceiver,
                    null, null, null, null);
        }
    }

    private void assertPackageKnownAndInstalled(String volumeUuid, String packageName, int userId)
            throws PackageManagerException {
        synchronized (mLock) {
            // Normalize package name to handle renamed packages
            packageName = normalizePackageNameLPr(packageName);

            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (ps == null) {
                throw new PackageManagerException("Package " + packageName + " is unknown");
            } else if (!TextUtils.equals(volumeUuid, ps.getVolumeUuid())) {
                throw new PackageManagerException(
                        "Package " + packageName + " found on unknown volume " + volumeUuid
                                + "; expected volume " + ps.getVolumeUuid());
            } else if (!ps.getInstalled(userId)) {
                throw new PackageManagerException(
                        "Package " + packageName + " not installed for user " + userId);
            }
        }
    }

    private void executeBatchLI(@NonNull Installer.Batch batch) {
        try {
            batch.execute(mInstaller);
        } catch (InstallerException e) {
            Slog.w(TAG, "Failed to execute pending operations", e);
        }
    }

    /**
     * Reconcile all app data for the given user.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps on all mounted volumes.
     */
    @NonNull
    void reconcileAppsData(int userId, int flags, boolean migrateAppsData) {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
            final String volumeUuid = vol.getFsUuid();
            synchronized (mInstallLock) {
                reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppsData);
            }
        }
    }

    @GuardedBy("mInstallLock")
    void reconcileAppsDataLI(String volumeUuid, int userId, int flags,
            boolean migrateAppData) {
        reconcileAppsDataLI(volumeUuid, userId, flags, migrateAppData, false /* onlyCoreApps */);
    }

    /**
     * Reconcile all app data on given mounted volume.
     * <p>
     * Destroys app data that isn't expected, either due to uninstallation or
     * reinstallation on another volume.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps.
     * @return list of skipped non-core packages (if {@code onlyCoreApps} is true)
     */
    @GuardedBy("mInstallLock")
    private List<String> reconcileAppsDataLI(String volumeUuid, int userId, int flags,
            boolean migrateAppData, boolean onlyCoreApps) {
        Slog.v(TAG, "reconcileAppsData for " + volumeUuid + " u" + userId + " 0x"
                + Integer.toHexString(flags) + " migrateAppData=" + migrateAppData);
        List<String> result = onlyCoreApps ? new ArrayList<>() : null;

        final File ceDir = Environment.getDataUserCeDirectory(volumeUuid, userId);
        final File deDir = Environment.getDataUserDeDirectory(volumeUuid, userId);

        // First look for stale data that doesn't belong, and check if things
        // have changed since we did our last restorecon
        if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
            if (StorageManager.isFileEncryptedNativeOrEmulated()
                    && !StorageManager.isUserKeyUnlocked(userId)) {
                throw new RuntimeException(
                        "Yikes, someone asked us to reconcile CE storage while " + userId
                                + " was still locked; this would have caused massive data loss!");
            }

            final File[] files = FileUtils.listFilesOrEmpty(ceDir);
            for (File file : files) {
                final String packageName = file.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName, userId);
                } catch (PackageManagerException e) {
                    logCriticalInfo(Log.WARN, "Destroying " + file + " due to: " + e);
                    try {
                        mInstaller.destroyAppData(volumeUuid, packageName, userId,
                                StorageManager.FLAG_STORAGE_CE, 0);
                    } catch (InstallerException e2) {
                        logCriticalInfo(Log.WARN, "Failed to destroy: " + e2);
                    }
                }
            }
        }
        if ((flags & StorageManager.FLAG_STORAGE_DE) != 0) {
            final File[] files = FileUtils.listFilesOrEmpty(deDir);
            for (File file : files) {
                final String packageName = file.getName();
                try {
                    assertPackageKnownAndInstalled(volumeUuid, packageName, userId);
                } catch (PackageManagerException e) {
                    logCriticalInfo(Log.WARN, "Destroying " + file + " due to: " + e);
                    try {
                        mInstaller.destroyAppData(volumeUuid, packageName, userId,
                                StorageManager.FLAG_STORAGE_DE, 0);
                    } catch (InstallerException e2) {
                        logCriticalInfo(Log.WARN, "Failed to destroy: " + e2);
                    }
                }
            }
        }

        // Ensure that data directories are ready to roll for all packages
        // installed for this volume and user
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "prepareAppDataAndMigrate");
        Installer.Batch batch = new Installer.Batch();
        final List<PackageSetting> packages;
        synchronized (mLock) {
            packages = mSettings.getVolumePackagesLPr(volumeUuid);
        }
        int preparedCount = 0;
        for (PackageSetting ps : packages) {
            final String packageName = ps.getPackageName();
            if (ps.getPkg() == null) {
                Slog.w(TAG, "Odd, missing scanned package " + packageName);
                // TODO: might be due to legacy ASEC apps; we should circle back
                // and reconcile again once they're scanned
                continue;
            }
            // Skip non-core apps if requested
            if (onlyCoreApps && !ps.getPkg().isCoreApp()) {
                result.add(packageName);
                continue;
            }

            if (ps.getInstalled(userId)) {
                prepareAppDataAndMigrate(batch, ps.getPkg(), userId, flags, migrateAppData);
                preparedCount++;
            }
        }
        executeBatchLI(batch);
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

        Slog.v(TAG, "reconcileAppsData finished " + preparedCount + " packages");
        return result;
    }

    /**
     * Prepare app data for the given app just after it was installed or
     * upgraded. This method carefully only touches users that it's installed
     * for, and it forces a restorecon to handle any seinfo changes.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps. If there is an ownership mismatch, it
     * will try recovering system apps by wiping data; third-party app data is
     * left intact.
     * <p>
     * <em>Note: To avoid a deadlock, do not call this method with {@code mLock} lock held</em>
     */
    void prepareAppDataAfterInstallLIF(AndroidPackage pkg) {
        final PackageSetting ps;
        synchronized (mLock) {
            ps = mSettings.getPackageLPr(pkg.getPackageName());
            mSettings.writeKernelMappingLPr(ps);
        }

        Installer.Batch batch = new Installer.Batch();
        UserManagerInternal umInternal = mInjector.getUserManagerInternal();
        StorageManagerInternal smInternal = mInjector.getLocalService(StorageManagerInternal.class);
        for (UserInfo user : mUserManager.getUsers(false /*excludeDying*/)) {
            final int flags;
            if (StorageManager.isUserKeyUnlocked(user.id)
                    && smInternal.isCeStoragePrepared(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE;
            } else if (umInternal.isUserRunning(user.id)) {
                flags = StorageManager.FLAG_STORAGE_DE;
            } else {
                continue;
            }

            // TODO@ashfall check ScanResult.mNeedsNewAppId, and if true instead
            // of creating app data, migrate / change ownership of existing
            // data.

            if (ps.getInstalled(user.id)) {
                // TODO: when user data is locked, mark that we're still dirty
                prepareAppData(batch, pkg, user.id, flags).thenRun(() -> {
                    // Note: this code block is executed with the Installer lock
                    // already held, since it's invoked as a side-effect of
                    // executeBatchLI()
                    if (umInternal.isUserUnlockingOrUnlocked(user.id)) {
                        // Prepare app data on external storage; currently this is used to
                        // setup any OBB dirs that were created by the installer correctly.
                        int uid = UserHandle.getUid(user.id, UserHandle.getAppId(pkg.getUid()));
                        smInternal.prepareAppDataAfterInstall(pkg.getPackageName(), uid);
                    }
                });
            }
        }
        executeBatchLI(batch);
    }

    /**
     * Prepare app data for the given app.
     * <p>
     * Verifies that directories exist and that ownership and labeling is
     * correct for all installed apps. If there is an ownership mismatch, this
     * will try recovering system apps by wiping data; third-party app data is
     * left intact.
     */
    private @NonNull CompletableFuture<?> prepareAppData(@NonNull Installer.Batch batch,
            @Nullable AndroidPackage pkg, int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return CompletableFuture.completedFuture(null);
        }
        return prepareAppDataLeaf(batch, pkg, userId, flags);
    }

    private void prepareAppDataAndMigrate(@NonNull Installer.Batch batch,
            @NonNull AndroidPackage pkg, int userId, int flags, boolean maybeMigrateAppData) {
        prepareAppData(batch, pkg, userId, flags).thenRun(() -> {
            // Note: this code block is executed with the Installer lock
            // already held, since it's invoked as a side-effect of
            // executeBatchLI()
            if (maybeMigrateAppData && maybeMigrateAppDataLIF(pkg, userId)) {
                // We may have just shuffled around app data directories, so
                // prepare them one more time
                final Installer.Batch batchInner = new Installer.Batch();
                prepareAppData(batchInner, pkg, userId, flags);
                executeBatchLI(batchInner);
            }
        });
    }

    private @NonNull CompletableFuture<?> prepareAppDataLeaf(@NonNull Installer.Batch batch,
            @NonNull AndroidPackage pkg, int userId, int flags) {
        if (DEBUG_APP_DATA) {
            Slog.v(TAG, "prepareAppData for " + pkg.getPackageName() + " u" + userId + " 0x"
                    + Integer.toHexString(flags));
        }

        final PackageSetting ps;
        final String seInfoUser;
        synchronized (mLock) {
            ps = mSettings.getPackageLPr(pkg.getPackageName());
            seInfoUser = SELinuxUtil.getSeinfoUser(ps.readUserState(userId));
        }
        final String volumeUuid = pkg.getVolumeUuid();
        final String packageName = pkg.getPackageName();

        final int appId = UserHandle.getAppId(pkg.getUid());

        String pkgSeInfo = AndroidPackageUtils.getSeInfo(pkg, ps);

        Preconditions.checkNotNull(pkgSeInfo);

        final String seInfo = pkgSeInfo + seInfoUser;
        final int targetSdkVersion = pkg.getTargetSdkVersion();

        return batch.createAppData(volumeUuid, packageName, userId, flags, appId, seInfo,
                targetSdkVersion).whenComplete((ceDataInode, e) -> {
                    // Note: this code block is executed with the Installer lock
                    // already held, since it's invoked as a side-effect of
                    // executeBatchLI()
                    if (e != null) {
                        logCriticalInfo(Log.WARN, "Failed to create app data for " + packageName
                                + ", but trying to recover: " + e);
                        destroyAppDataLeafLIF(pkg, userId, flags);
                        try {
                            ceDataInode = mInstaller.createAppData(volumeUuid, packageName, userId,
                                    flags, appId, seInfo, pkg.getTargetSdkVersion());
                            logCriticalInfo(Log.DEBUG, "Recovery succeeded!");
                        } catch (InstallerException e2) {
                            logCriticalInfo(Log.DEBUG, "Recovery failed!");
                        }
                    }

                    // Prepare the application profiles only for upgrades and
                    // first boot (so that we don't repeat the same operation at
                    // each boot).
                    //
                    // We only have to cover the upgrade and first boot here
                    // because for app installs we prepare the profiles before
                    // invoking dexopt (in installPackageLI).
                    //
                    // We also have to cover non system users because we do not
                    // call the usual install package methods for them.
                    //
                    // NOTE: in order to speed up first boot time we only create
                    // the current profile and do not update the content of the
                    // reference profile. A system image should already be
                    // configured with the right profile keys and the profiles
                    // for the speed-profile prebuilds should already be copied.
                    // That's done in #performDexOptUpgrade.
                    //
                    // TODO(calin, mathieuc): We should use .dm files for
                    // prebuilds profiles instead of manually copying them in
                    // #performDexOptUpgrade. When we do that we should have a
                    // more granular check here and only update the existing
                    // profiles.
                    if (mIsUpgrade || mFirstBoot || (userId != UserHandle.USER_SYSTEM)) {
                        mArtManagerService.prepareAppProfiles(pkg, userId,
                            /* updateReferenceProfileContent= */ false);
                    }

                    if ((flags & StorageManager.FLAG_STORAGE_CE) != 0 && ceDataInode != -1) {
                        // TODO: mark this structure as dirty so we persist it!
                        synchronized (mLock) {
                            if (ps != null) {
                                ps.setCeDataInode(ceDataInode, userId);
                            }
                        }
                    }

                    prepareAppDataContentsLeafLIF(pkg, ps, userId, flags);
                });
    }

    private void prepareAppDataContentsLIF(AndroidPackage pkg, @Nullable PackageSetting pkgSetting,
            int userId, int flags) {
        if (pkg == null) {
            Slog.wtf(TAG, "Package was null!", new Throwable());
            return;
        }
        prepareAppDataContentsLeafLIF(pkg, pkgSetting, userId, flags);
    }

    private void prepareAppDataContentsLeafLIF(AndroidPackage pkg,
            @Nullable PackageSetting pkgSetting, int userId, int flags) {
        final String volumeUuid = pkg.getVolumeUuid();
        final String packageName = pkg.getPackageName();

        if ((flags & StorageManager.FLAG_STORAGE_CE) != 0) {
            // Create a native library symlink only if we have native libraries
            // and if the native libraries are 32 bit libraries. We do not provide
            // this symlink for 64 bit libraries.
            String primaryCpuAbi = AndroidPackageUtils.getPrimaryCpuAbi(pkg, pkgSetting);
            if (primaryCpuAbi != null && !VMRuntime.is64BitAbi(primaryCpuAbi)) {
                final String nativeLibPath = pkg.getNativeLibraryDir();
                try {
                    mInstaller.linkNativeLibraryDirectory(volumeUuid, packageName,
                            nativeLibPath, userId);
                } catch (InstallerException e) {
                    Slog.e(TAG, "Failed to link native for " + packageName + ": " + e);
                }
            }
        }
    }

    /**
     * For system apps on non-FBE devices, this method migrates any existing
     * CE/DE data to match the {@code defaultToDeviceProtectedStorage} flag
     * requested by the app.
     */
    private boolean maybeMigrateAppDataLIF(AndroidPackage pkg, int userId) {
        if (pkg.isSystem() && !StorageManager.isFileEncryptedNativeOrEmulated()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            final int storageTarget = pkg.isDefaultToDeviceProtectedStorage()
                    ? StorageManager.FLAG_STORAGE_DE : StorageManager.FLAG_STORAGE_CE;
            try {
                mInstaller.migrateAppData(pkg.getVolumeUuid(), pkg.getPackageName(), userId,
                        storageTarget);
            } catch (InstallerException e) {
                logCriticalInfo(Log.WARN,
                        "Failed to migrate " + pkg.getPackageName() + ": " + e.getMessage());
            }
            return true;
        } else {
            return false;
        }
    }

    public PackageFreezer freezePackage(String packageName, String killReason) {
        return freezePackage(packageName, UserHandle.USER_ALL, killReason);
    }

    public PackageFreezer freezePackage(String packageName, int userId, String killReason) {
        return new PackageFreezer(packageName, userId, killReason, this);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int deleteFlags,
            String killReason) {
        return freezePackageForDelete(packageName, UserHandle.USER_ALL, deleteFlags, killReason);
    }

    public PackageFreezer freezePackageForDelete(String packageName, int userId, int deleteFlags,
            String killReason) {
        if ((deleteFlags & PackageManager.DELETE_DONT_KILL_APP) != 0) {
            return new PackageFreezer(this);
        } else {
            return freezePackage(packageName, userId, killReason);
        }
    }

    /**
     * Verify that given package is currently frozen.
     */
    private void checkPackageFrozen(String packageName) {
        synchronized (mLock) {
            if (!mFrozenPackages.contains(packageName)) {
                Slog.wtf(TAG, "Expected " + packageName + " to be frozen!", new Throwable());
            }
        }
    }

    @Override
    public int movePackage(final String packageName, final String volumeUuid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MOVE_PACKAGE, null);

        final int callingUid = Binder.getCallingUid();
        final UserHandle user = new UserHandle(UserHandle.getUserId(callingUid));
        final int moveId = mNextMoveId.getAndIncrement();
        mHandler.post(() -> {
            try {
                MovePackageHelper movePackageHelper = new MovePackageHelper(this);
                movePackageHelper.movePackageInternal(
                        packageName, volumeUuid, moveId, callingUid, user);
            } catch (PackageManagerException e) {
                Slog.w(TAG, "Failed to move " + packageName, e);
                mMoveCallbacks.notifyStatusChanged(moveId, e.error);
            }
        });
        return moveId;
    }

    @Override
    public int movePrimaryStorage(String volumeUuid) throws RemoteException {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MOVE_PACKAGE, null);

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
    public int getMoveStatus(int moveId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        return mMoveCallbacks.mLastStatus.get(moveId);
    }

    @Override
    public void registerMoveCallback(IPackageMoveObserver callback) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        mMoveCallbacks.register(callback);
    }

    @Override
    public void unregisterMoveCallback(IPackageMoveObserver callback) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        mMoveCallbacks.unregister(callback);
    }

    @Override
    public boolean setInstallLocation(int loc) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                null);
        if (getInstallLocation() == loc) {
            return true;
        }
        if (loc == PackageHelper.APP_INSTALL_AUTO || loc == PackageHelper.APP_INSTALL_INTERNAL
                || loc == PackageHelper.APP_INSTALL_EXTERNAL) {
            android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION, loc);
            return true;
        }
        return false;
   }

    @Override
    public int getInstallLocation() {
        // allow instant app access
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION,
                PackageHelper.APP_INSTALL_AUTO);
    }

    /** Called by UserManagerService */
    void cleanUpUser(UserManagerService userManager, @UserIdInt int userId) {
        synchronized (mLock) {
            mDirtyUsers.remove(userId);
            mUserNeedsBadging.delete(userId);
            mPermissionManager.onUserRemoved(userId);
            mSettings.removeUserLPw(userId);
            mPendingBroadcasts.remove(userId);
            mInstantAppRegistry.onUserRemovedLPw(userId);
            removeUnusedPackagesLPw(userManager, userId);
        }
    }

    /**
     * We're removing userId and would like to remove any downloaded packages
     * that are no longer in use by any other user.
     * @param userId the user being removed
     */
    @GuardedBy("mLock")
    private void removeUnusedPackagesLPw(UserManagerService userManager, final int userId) {
        final boolean DEBUG_CLEAN_APKS = false;
        int [] users = userManager.getUserIds();
        final int numPackages = mSettings.getPackagesLocked().size();
        for (int index = 0; index < numPackages; index++) {
            final PackageSetting ps = mSettings.getPackagesLocked().valueAt(index);
            if (ps.getPkg() == null) {
                continue;
            }
            final String packageName = ps.getPkg().getPackageName();
            // Skip over if system app or static shared library
            if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || !TextUtils.isEmpty(ps.getPkg().getStaticSharedLibName())) {
                continue;
            }
            if (DEBUG_CLEAN_APKS) {
                Slog.i(TAG, "Checking package " + packageName);
            }
            boolean keep = shouldKeepUninstalledPackageLPr(packageName);
            if (keep) {
                if (DEBUG_CLEAN_APKS) {
                    Slog.i(TAG, "  Keeping package " + packageName + " - requested by DO");
                }
            } else {
                for (int i = 0; i < users.length; i++) {
                    if (users[i] != userId && ps.getInstalled(users[i])) {
                        keep = true;
                        if (DEBUG_CLEAN_APKS) {
                            Slog.i(TAG, "  Keeping package " + packageName + " for user "
                                    + users[i]);
                        }
                        break;
                    }
                }
            }
            if (!keep) {
                if (DEBUG_CLEAN_APKS) {
                    Slog.i(TAG, "  Removing package " + packageName);
                }
                //end run
                mHandler.post(() -> deletePackageX(packageName, PackageManager.VERSION_CODE_HIGHEST,
                        userId, 0, true /*removedBySystem*/));
            }
        }
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
            scheduleWritePackageRestrictionsLocked(userId);
            scheduleWritePackageListLocked(userId);
            mAppsFilter.onUsersChanged();
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
            mDomainVerificationManager.clearUser(userId);
        }
    }

    boolean readPermissionStateForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            mPermissionManager.writeLegacyPermissionStateTEMP();
            mSettings.readPermissionStateForUserSyncLPr(userId);
            mPermissionManager.readLegacyPermissionStateTEMP();
            return mPmInternal.isPermissionUpgradeNeeded(userId);
        }
    }

    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can read the verifier device identity");

        synchronized (mLock) {
            return mSettings.getVerifierDeviceIdentityLPw();
        }
    }

    @Override
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

    @Override
    public IPackageInstaller getPackageInstaller() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return mInstallerService;
    }

    @Override
    public IArtManager getArtManager() {
        return mArtManagerService;
    }

    private boolean userNeedsBadging(int userId) {
        int index = mUserNeedsBadging.indexOfKey(userId);
        if (index < 0) {
            final UserInfo userInfo;
            final long token = Binder.clearCallingIdentity();
            try {
                userInfo = mUserManager.getUserInfo(userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            final boolean b;
            b = userInfo != null && userInfo.isManagedProfile();
            mUserNeedsBadging.put(userId, b);
            return b;
        }
        return mUserNeedsBadging.valueAt(index);
    }

    @Override
    public KeySet getKeySetByAlias(String packageName, String alias) {
        if (packageName == null || alias == null) {
            return null;
        }
        synchronized (mLock) {
            final AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null
                    || shouldFilterApplicationLocked(getPackageSetting(pkg.getPackageName()),
                    Binder.getCallingUid(), UserHandle.getCallingUserId())) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            final KeySetManagerService ksms = mSettings.getKeySetManagerService();
            return new KeySet(ksms.getKeySetByAliasAndPackageNameLPr(packageName, alias));
        }
    }

    @Override
    public KeySet getSigningKeySet(String packageName) {
        if (packageName == null) {
            return null;
        }
        synchronized (mLock) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            final AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null
                    || shouldFilterApplicationLocked(getPackageSetting(pkg.getPackageName()),
                    callingUid, callingUserId)) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName
                        + ", uid:" + callingUid);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            if (pkg.getUid() != callingUid
                    && Process.SYSTEM_UID != callingUid) {
                throw new SecurityException("May not access signing KeySet of other apps.");
            }
            final KeySetManagerService ksms = mSettings.getKeySetManagerService();
            return new KeySet(ksms.getSigningKeySetByPackageNameLPr(packageName));
        }
    }

    @Override
    public boolean isPackageSignedByKeySet(String packageName, KeySet ks) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return false;
        }
        if (packageName == null || ks == null) {
            return false;
        }
        synchronized (mLock) {
            final AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null
                    || shouldFilterApplicationLocked(getPackageSetting(pkg.getPackageName()),
                    callingUid, UserHandle.getUserId(callingUid))) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            IBinder ksh = ks.getToken();
            if (ksh instanceof KeySetHandle) {
                final KeySetManagerService ksms = mSettings.getKeySetManagerService();
                return ksms.packageIsSignedByLPr(packageName, (KeySetHandle) ksh);
            }
            return false;
        }
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(String packageName, KeySet ks) {
        final int callingUid = Binder.getCallingUid();
        if (getInstantAppPackageName(callingUid) != null) {
            return false;
        }
        if (packageName == null || ks == null) {
            return false;
        }
        synchronized (mLock) {
            final AndroidPackage pkg = mPackages.get(packageName);
            if (pkg == null
                    || shouldFilterApplicationLocked(getPackageSetting(pkg.getPackageName()),
                    callingUid, UserHandle.getUserId(callingUid))) {
                Slog.w(TAG, "KeySet requested for unknown package: " + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            IBinder ksh = ks.getToken();
            if (ksh instanceof KeySetHandle) {
                final KeySetManagerService ksms = mSettings.getKeySetManagerService();
                return ksms.packageIsSignedByExactlyLPr(packageName, (KeySetHandle) ksh);
            }
            return false;
        }
    }

    @GuardedBy("mLock")
    private void deletePackageIfUnusedLPr(final String packageName) {
        PackageSetting ps = mSettings.getPackageLPr(packageName);
        if (ps == null) {
            return;
        }
        if (!ps.isAnyInstalled(mUserManager.getUserIds())) {
            // TODO Implement atomic delete if package is unused
            // It is currently possible that the package will be deleted even if it is installed
            // after this method returns.
            mHandler.post(() -> deletePackageX(packageName, PackageManager.VERSION_CODE_HIGHEST,
                    0, PackageManager.DELETE_ALL_USERS, true /*removedBySystem*/));
        }
    }

    private final class PackageChangeObserverDeathRecipient implements IBinder.DeathRecipient {
        private final IPackageChangeObserver mObserver;

        PackageChangeObserverDeathRecipient(IPackageChangeObserver observer) {
            mObserver = observer;
        }

        @Override
        public void binderDied() {
            synchronized (mPackageChangeObservers) {
                mPackageChangeObservers.remove(mObserver);
                Log.d(TAG, "Size of mPackageChangeObservers after removing dead observer is "
                    + mPackageChangeObservers.size());
            }
        }
    }

    private class PackageManagerNative extends IPackageManagerNative.Stub {
        @Override
        public void registerPackageChangeObserver(@NonNull IPackageChangeObserver observer) {
          synchronized (mPackageChangeObservers) {
            try {
                observer.asBinder().linkToDeath(
                    new PackageChangeObserverDeathRecipient(observer), 0);
            } catch (RemoteException e) {
              Log.e(TAG, e.getMessage());
            }
            mPackageChangeObservers.add(observer);
            Log.d(TAG, "Size of mPackageChangeObservers after registry is "
                + mPackageChangeObservers.size());
          }
        }

        @Override
        public void unregisterPackageChangeObserver(@NonNull IPackageChangeObserver observer) {
          synchronized (mPackageChangeObservers) {
            mPackageChangeObservers.remove(observer);
            Log.d(TAG, "Size of mPackageChangeObservers after unregistry is "
                + mPackageChangeObservers.size());
          }
        }

        @Override
        public String[] getAllPackages() {
            return PackageManagerService.this.getAllPackages().toArray(new String[0]);
        }

        @Override
        public String[] getNamesForUids(int[] uids) throws RemoteException {
            String[] names = null;
            String[] results = null;
            try {
                if (uids == null || uids.length == 0) {
                    return null;
                }
                names = PackageManagerService.this.getNamesForUids(uids);
                results = (names != null) ? names : new String[uids.length];
                // massage results so they can be parsed by the native binder
                for (int i = results.length - 1; i >= 0; --i) {
                    if (results[i] == null) {
                        results[i] = "";
                    }
                }
                return results;
            } catch (Throwable t) {
                // STOPSHIP(186558987): revert addition of try/catch/log
                Slog.e(TAG, "uids: " + Arrays.toString(uids));
                Slog.e(TAG, "names: " + Arrays.toString(names));
                Slog.e(TAG, "results: " + Arrays.toString(results));
                Slog.e(TAG, "throwing exception", t);
                throw t;
            }
        }

        // NB: this differentiates between preloads and sideloads
        @Override
        public String getInstallerForPackage(String packageName) throws RemoteException {
            final String installerName = getInstallerPackageName(packageName);
            if (!TextUtils.isEmpty(installerName)) {
                return installerName;
            }
            // differentiate between preload and sideload
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            ApplicationInfo appInfo = getApplicationInfo(packageName,
                                    /*flags*/ 0,
                                    /*userId*/ callingUser);
            if (appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                return "preload";
            }
            return "";
        }

        @Override
        public long getVersionCodeForPackage(String packageName) throws RemoteException {
            try {
                int callingUser = UserHandle.getUserId(Binder.getCallingUid());
                PackageInfo pInfo = getPackageInfo(packageName, 0, callingUser);
                if (pInfo != null) {
                    return pInfo.getLongVersionCode();
                }
            } catch (Exception e) {
            }
            return 0;
        }

        @Override
        public int getTargetSdkVersionForPackage(String packageName) throws RemoteException {
            int targetSdk = getTargetSdkVersion(packageName);
            if (targetSdk != -1) {
                return targetSdk;
            }

            throw new RemoteException("Couldn't get targetSdkVersion for package " + packageName);
        }

        @Override
        public boolean isPackageDebuggable(String packageName) throws RemoteException {
            int callingUser = UserHandle.getCallingUserId();
            ApplicationInfo appInfo = getApplicationInfo(packageName, 0, callingUser);
            if (appInfo != null) {
                return (0 != (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE));
            }

            throw new RemoteException("Couldn't get debug flag for package " + packageName);
        }

        @Override
        public boolean[] isAudioPlaybackCaptureAllowed(String[] packageNames)
                throws RemoteException {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            boolean[] results = new boolean[packageNames.length];
            for (int i = results.length - 1; i >= 0; --i) {
                ApplicationInfo appInfo = getApplicationInfo(packageNames[i], 0, callingUser);
                results[i] = appInfo != null && appInfo.isAudioPlaybackCaptureAllowed();
            }
            return results;
        }

        @Override
        public int getLocationFlags(String packageName) throws RemoteException {
            int callingUser = UserHandle.getUserId(Binder.getCallingUid());
            ApplicationInfo appInfo = getApplicationInfo(packageName,
                    /*flags*/ 0,
                    /*userId*/ callingUser);
            if (appInfo == null) {
                throw new RemoteException(
                        "Couldn't get ApplicationInfo for package " + packageName);
            }
            return ((appInfo.isSystemApp() ? IPackageManagerNative.LOCATION_SYSTEM : 0)
                    | (appInfo.isVendor() ? IPackageManagerNative.LOCATION_VENDOR : 0)
                    | (appInfo.isProduct() ? IPackageManagerNative.LOCATION_PRODUCT : 0));
        }

        @Override
        public String getModuleMetadataPackageName() throws RemoteException {
            return PackageManagerService.this.mModuleInfoProvider.getPackageName();
        }

        @Override
        public boolean hasSha256SigningCertificate(String packageName, byte[] certificate)
                throws RemoteException {
            return PackageManagerService.this.hasSigningCertificate(
                packageName, certificate, CERT_INPUT_SHA256);
        }

        @Override
        public boolean hasSystemFeature(String featureName, int version) {
            return PackageManagerService.this.hasSystemFeature(featureName, version);
        }

        @Override
        public void registerStagedApexObserver(IStagedApexObserver observer) {
            mInstallerService.getStagingManager().registerStagedApexObserver(observer);
        }

        @Override
        public void unregisterStagedApexObserver(IStagedApexObserver observer) {
            mInstallerService.getStagingManager().unregisterStagedApexObserver(observer);
        }

        @Override
        public String[] getStagedApexModuleNames() {
            return mInstallerService.getStagingManager()
                    .getStagedApexModuleNames().toArray(new String[0]);
        }

        @Override
        @Nullable
        public StagedApexInfo getStagedApexInfo(String moduleName) {
            return mInstallerService.getStagingManager().getStagedApexInfo(moduleName);
        }

    }

    private AndroidPackage getPackage(String packageName) {
        return mComputer.getPackage(packageName);
    }

    private AndroidPackage getPackage(int uid) {
        return mComputer.getPackage(uid);
    }

    private SigningDetails getSigningDetails(@NonNull String packageName) {
        return mComputer.getSigningDetails(packageName);
    }

    private SigningDetails getSigningDetails(int uid) {
        return mComputer.getSigningDetails(uid);
    }

    private boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
        return mComputer.filterAppAccess(pkg, callingUid, userId);
    }

    private boolean filterAppAccess(String packageName, int callingUid, int userId) {
        return mComputer.filterAppAccess(packageName, callingUid, userId);
    }

    private boolean filterAppAccess(int uid, int callingUid) {
        return mComputer.filterAppAccess(uid, callingUid);
    }

    private int[] getVisibilityAllowList(@NonNull String packageName, int userId) {
        synchronized (mLock) {
            final PackageSetting ps = getPackageSettingInternal(packageName, Process.SYSTEM_UID);
            if (ps == null) {
                return null;
            }
            final SparseArray<int[]> visibilityAllowList = mAppsFilter.getVisibilityAllowList(ps,
                    new int[]{userId}, mSettings.getPackagesLocked());
            return visibilityAllowList != null ? visibilityAllowList.get(userId) : null;
        }
    }

    private class PackageManagerInternalImpl extends PackageManagerInternal {
        @Override
        public List<ApplicationInfo> getInstalledApplications(int flags, int userId,
                int callingUid) {
            return PackageManagerService.this.getInstalledApplicationsListInternal(flags, userId,
                    callingUid);
        }

        @Override
        public boolean isPlatformSigned(String packageName) {
            PackageSetting packageSetting = mSettings.getPackageLPr(packageName);
            if (packageSetting == null) {
                return false;
            }
            AndroidPackage pkg = packageSetting.getPkg();
            if (pkg == null) {
                // May happen if package in on a removable sd card
                return false;
            }
            return pkg.getSigningDetails().hasAncestorOrSelf(mPlatformPackage.getSigningDetails())
                    || mPlatformPackage.getSigningDetails().checkCapability(pkg.getSigningDetails(),
                    SigningDetails.CertCapabilities.PERMISSION);
        }

        @Override
        public boolean isDataRestoreSafe(byte[] restoringFromSigHash, String packageName) {
            SigningDetails sd = getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasSha256Certificate(restoringFromSigHash,
                    SigningDetails.CertCapabilities.INSTALLED_DATA);
        }

        @Override
        public boolean isDataRestoreSafe(Signature restoringFromSig, String packageName) {
            SigningDetails sd = getSigningDetails(packageName);
            if (sd == null) {
                return false;
            }
            return sd.hasCertificate(restoringFromSig,
                    SigningDetails.CertCapabilities.INSTALLED_DATA);
        }

        @Override
        public boolean hasSignatureCapability(int serverUid, int clientUid,
                @SigningDetails.CertCapabilities int capability) {
            SigningDetails serverSigningDetails = getSigningDetails(serverUid);
            SigningDetails clientSigningDetails = getSigningDetails(clientUid);
            return serverSigningDetails.checkCapability(clientSigningDetails, capability)
                    || clientSigningDetails.hasAncestorOrSelf(serverSigningDetails);

        }

        private SigningDetails getSigningDetails(@NonNull String packageName) {
            return PackageManagerService.this.getSigningDetails(packageName);
        }

        private SigningDetails getSigningDetails(int uid) {
            return PackageManagerService.this.getSigningDetails(uid);
        }

        @Override
        public boolean isInstantApp(String packageName, int userId) {
            return PackageManagerService.this.isInstantApp(packageName, userId);
        }

        @Override
        public String getInstantAppPackageName(int uid) {
            return PackageManagerService.this.getInstantAppPackageName(uid);
        }

        @Override
        public boolean filterAppAccess(AndroidPackage pkg, int callingUid, int userId) {
            return PackageManagerService.this.filterAppAccess(pkg, callingUid, userId);
        }

        @Override
        public boolean filterAppAccess(String packageName, int callingUid, int userId) {
            return PackageManagerService.this.filterAppAccess(packageName, callingUid, userId);
        }

        @Override
        public boolean filterAppAccess(int uid, int callingUid) {
            return PackageManagerService.this.filterAppAccess(uid, callingUid);
        }

        @Nullable
        public int[] getVisibilityAllowList(@NonNull String packageName, int userId) {
            return PackageManagerService.this.getVisibilityAllowList(packageName, userId);
        }

        @Override
        public AndroidPackage getPackage(String packageName) {
            return PackageManagerService.this.getPackage(packageName);
        }

        @Nullable
        @Override
        public AndroidPackageApi getAndroidPackage(@NonNull String packageName) {
            return PackageManagerService.this.getPackage(packageName);
        }

        @Override
        public AndroidPackage getPackage(int uid) {
            return PackageManagerService.this.getPackage(uid);
        }

        @Override
        public List<AndroidPackage> getPackagesForAppId(int appId) {
            final Object obj;
            synchronized (mLock) {
                obj = mSettings.getSettingLPr(appId);
            }
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.getPackages();
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return List.of(ps.getPkg());
            } else {
                return Collections.emptyList();
            }
        }

        @Nullable
        @Override
        public PackageSetting getPackageSetting(String packageName) {
            return PackageManagerService.this.getPackageSetting(packageName);
        }

        @Nullable
        @Override
        public PackageState getPackageState(@NonNull String packageName) {
            return PackageManagerService.this.getPackageState(packageName);
        }

        @Override
        public PackageList getPackageList(PackageListObserver observer) {
            synchronized (mLock) {
                final int N = mPackages.size();
                final ArrayList<String> list = new ArrayList<>(N);
                for (int i = 0; i < N; i++) {
                    list.add(mPackages.keyAt(i));
                }
                final PackageList packageList = new PackageList(list, observer);
                if (observer != null) {
                    mPackageListObservers.add(packageList);
                }
                return packageList;
            }
        }

        @Override
        public void removePackageListObserver(PackageListObserver observer) {
            synchronized (mLock) {
                mPackageListObservers.remove(observer);
            }
        }

        @Override
        public PackageSetting getDisabledSystemPackage(@NonNull String packageName) {
            synchronized (mLock) {
                return mSettings.getDisabledSystemPkgLPr(packageName);
            }
        }

        @Override
        public @Nullable
        String getDisabledSystemPackageName(@NonNull String packageName) {
            PackageSetting disabledPkgSetting = getDisabledSystemPackage(
                    packageName);
            AndroidPackage disabledPkg = disabledPkgSetting == null
                    ? null : disabledPkgSetting.getPkg();
            return disabledPkg == null ? null : disabledPkg.getPackageName();
        }

        /**
         * Only keep package names that refer to {@link AndroidPackage#isSystem system} packages.
         *
         * @param pkgNames The packages to filter
         *
         * @return The filtered packages
         */
        private @NonNull String[] filterOnlySystemPackages(@Nullable String... pkgNames) {
            if (pkgNames == null) {
                return ArrayUtils.emptyArray(String.class);
            }

            ArrayList<String> systemPackageNames = new ArrayList<>(pkgNames.length);

            for (String pkgName: pkgNames) {
                synchronized (mLock) {
                    if (pkgName == null) {
                        continue;
                    }

                    AndroidPackage pkg = getPackage(pkgName);
                    if (pkg == null) {
                        Log.w(TAG, "Could not find package " + pkgName);
                        continue;
                    }

                    if (!pkg.isSystem()) {
                        Log.w(TAG, pkgName + " is not system");
                        continue;
                    }

                    systemPackageNames.add(pkgName);
                }
            }

            return systemPackageNames.toArray(new String[]{});
        }

        @Override
        public @NonNull String[] getKnownPackageNames(int knownPackage, int userId) {
            return getKnownPackageNamesInternal(knownPackage, userId);
        }

        private String[] getKnownPackageNamesInternal(int knownPackage, int userId) {
            switch (knownPackage) {
                case PackageManagerInternal.PACKAGE_BROWSER:
                    return new String[] { mDefaultAppProvider.getDefaultBrowser(userId) };
                case PackageManagerInternal.PACKAGE_INSTALLER:
                    return filterOnlySystemPackages(mRequiredInstallerPackage);
                case PackageManagerInternal.PACKAGE_SETUP_WIZARD:
                    return filterOnlySystemPackages(mSetupWizardPackage);
                case PackageManagerInternal.PACKAGE_SYSTEM:
                    return new String[]{"android"};
                case PackageManagerInternal.PACKAGE_VERIFIER:
                    return filterOnlySystemPackages(mRequiredVerifierPackage);
                case PackageManagerInternal.PACKAGE_SYSTEM_TEXT_CLASSIFIER:
                    return filterOnlySystemPackages(
                            mDefaultTextClassifierPackage, mSystemTextClassifierPackageName);
                case PackageManagerInternal.PACKAGE_PERMISSION_CONTROLLER:
                    return filterOnlySystemPackages(mRequiredPermissionControllerPackage);
                case PackageManagerInternal.PACKAGE_CONFIGURATOR:
                    return filterOnlySystemPackages(mConfiguratorPackage);
                case PackageManagerInternal.PACKAGE_INCIDENT_REPORT_APPROVER:
                    return filterOnlySystemPackages(mIncidentReportApproverPackage);
                case PackageManagerInternal.PACKAGE_APP_PREDICTOR:
                    return filterOnlySystemPackages(mAppPredictionServicePackage);
                case PackageManagerInternal.PACKAGE_COMPANION:
                    return filterOnlySystemPackages(COMPANION_PACKAGE_NAME);
                case PackageManagerInternal.PACKAGE_RETAIL_DEMO:
                    return TextUtils.isEmpty(mRetailDemoPackage)
                            ? ArrayUtils.emptyArray(String.class)
                            : new String[] {mRetailDemoPackage};
                case PackageManagerInternal.PACKAGE_OVERLAY_CONFIG_SIGNATURE:
                    return filterOnlySystemPackages(getOverlayConfigSignaturePackageName());
                case PackageManagerInternal.PACKAGE_RECENTS:
                    return filterOnlySystemPackages(mRecentsPackage);
                default:
                    return ArrayUtils.emptyArray(String.class);
            }
        }

        @Override
        public boolean isResolveActivityComponent(ComponentInfo component) {
            return mResolveActivity.packageName.equals(component.packageName)
                    && mResolveActivity.name.equals(component.name);
        }

        @Override
        public void setKeepUninstalledPackages(final List<String> packageList) {
            PackageManagerService.this.setKeepUninstalledPackagesInternal(packageList);
        }

        @Override
        public boolean isPermissionsReviewRequired(String packageName, int userId) {
            return mPermissionManager.isPermissionsReviewRequired(packageName, userId);
        }

        @Override
        public PackageInfo getPackageInfo(
                String packageName, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this
                    .getPackageInfoInternal(packageName, PackageManager.VERSION_CODE_HIGHEST,
                            flags, filterCallingUid, userId);
        }

        @Override
        public long getCeDataInode(String packageName, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                if (ps != null) {
                    return ps.getCeDataInode(userId);
                }
                return 0;
            }
        }

        @Override
        public Bundle getSuspendedPackageLauncherExtras(String packageName, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                final Bundle allExtras = new Bundle();
                if (ps != null) {
                    final PackageUserState pus = ps.readUserState(userId);
                    if (pus.suspended) {
                        for (int i = 0; i < pus.suspendParams.size(); i++) {
                            final PackageUserState.SuspendParams params =
                                    pus.suspendParams.valueAt(i);
                            if (params != null && params.launcherExtras != null) {
                                allExtras.putAll(params.launcherExtras);
                            }
                        }
                    }

                }
                return (allExtras.size() > 0) ? allExtras : null;
            }
        }

        @Override
        public boolean isPackageSuspended(String packageName, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                return ps != null && ps.getSuspended(userId);
            }
        }

        @Override
        public void removeAllNonSystemPackageSuspensions(int userId) {
            final String[] allPackages;
            synchronized (mLock) {
                allPackages = mPackages.keySet().toArray(new String[mPackages.size()]);
            }
            PackageManagerService.this.removeSuspensionsBySuspendingPackage(allPackages,
                    (suspendingPackage) -> !PLATFORM_PACKAGE_NAME.equals(suspendingPackage),
                    userId);
        }

        @Override
        public void removeNonSystemPackageSuspensions(String packageName, int userId) {
            PackageManagerService.this.removeSuspensionsBySuspendingPackage(
                    new String[]{packageName},
                    (suspendingPackage) -> !PLATFORM_PACKAGE_NAME.equals(suspendingPackage),
                    userId);
        }

        @Override
        public void flushPackageRestrictions(int userId) {
            synchronized (mLock) {
                PackageManagerService.this.flushPackageRestrictionsAsUserInternalLocked(userId);
            }
        }

        @Override
        public void removeDistractingPackageRestrictions(String packageName, int userId) {
            PackageManagerService.this.removeDistractingPackageRestrictions(
                    new String[]{packageName}, userId);
        }

        @Override
        public void removeAllDistractingPackageRestrictions(int userId) {
            PackageManagerService.this.removeAllDistractingPackageRestrictions(userId);
        }

        @Override
        public String getSuspendingPackage(String suspendedPackage, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(suspendedPackage);
                if (ps != null) {
                    final PackageUserState pus = ps.readUserState(userId);
                    if (pus.suspended) {
                        String suspendingPackage = null;
                        for (int i = 0; i < pus.suspendParams.size(); i++) {
                            suspendingPackage = pus.suspendParams.keyAt(i);
                            if (PLATFORM_PACKAGE_NAME.equals(suspendingPackage)) {
                                return suspendingPackage;
                            }
                        }
                        return suspendingPackage;
                    }
                }
                return null;
            }
        }

        @Override
        public SuspendDialogInfo getSuspendedDialogInfo(String suspendedPackage,
                String suspendingPackage, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(suspendedPackage);
                if (ps != null) {
                    final PackageUserState pus = ps.readUserState(userId);
                    if (pus.suspended) {
                        final PackageUserState.SuspendParams suspendParams =
                                pus.suspendParams.get(suspendingPackage);
                        return (suspendParams != null) ? suspendParams.dialogInfo : null;
                    }
                }
            }
            return null;
        }

        @Override
        public int getDistractingPackageRestrictions(String packageName, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                return (ps != null) ? ps.getDistractionFlags(userId) : RESTRICTION_NONE;
            }
        }

        @Override
        public int getPackageUid(String packageName, int flags, int userId) {
            return PackageManagerService.this
                    .getPackageUidInternal(packageName, flags, userId, Process.SYSTEM_UID);
        }

        @Override
        public ApplicationInfo getApplicationInfo(
                String packageName, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this
                    .getApplicationInfoInternal(packageName, flags, filterCallingUid, userId);
        }

        @Override
        public ActivityInfo getActivityInfo(
                ComponentName component, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this
                    .getActivityInfoInternal(component, flags, filterCallingUid, userId);
        }

        @Override
        public List<ResolveInfo> queryIntentActivities(
                Intent intent, String resolvedType, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this
                    .queryIntentActivitiesInternal(intent, resolvedType, flags, 0, filterCallingUid,
                            userId, false /*resolveForStart*/, true /*allowDynamicSplits*/);
        }

        @Override
        public List<ResolveInfo> queryIntentReceivers(Intent intent,
                String resolvedType, int flags, int filterCallingUid, int userId) {
            return PackageManagerService.this.queryIntentReceiversInternal(intent, resolvedType,
                    flags, userId, filterCallingUid);
        }

        @Override
        public List<ResolveInfo> queryIntentServices(
                Intent intent, int flags, int callingUid, int userId) {
            final String resolvedType = intent.resolveTypeIfNeeded(mContext.getContentResolver());
            return PackageManagerService.this
                    .queryIntentServicesInternal(intent, resolvedType, flags, userId, callingUid,
                            false);
        }

        @Override
        public ComponentName getHomeActivitiesAsUser(List<ResolveInfo> allHomeCandidates,
                int userId) {
            return PackageManagerService.this.getHomeActivitiesAsUser(allHomeCandidates, userId);
        }

        @Override
        public ComponentName getDefaultHomeActivity(int userId) {
            return PackageManagerService.this.getDefaultHomeActivity(userId);
        }

        @Override
        public ComponentName getSystemUiServiceComponent() {
            return ComponentName.unflattenFromString(mContext.getResources().getString(
                    com.android.internal.R.string.config_systemUIServiceComponent));
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
                    usersWithPoOrDo.add(profileOwnerPackages.keyAt(i));
                }
            }
        }

        @Override
        public void setDeviceOwnerProtectedPackages(
                String deviceOwnerPackageName, List<String> packageNames) {
            mProtectedPackages.setDeviceOwnerProtectedPackages(
                    deviceOwnerPackageName, packageNames);
        }

        @Override
        public boolean isPackageDataProtected(int userId, String packageName) {
            return mProtectedPackages.isPackageDataProtected(userId, packageName);
        }

        @Override
        public boolean isPackageStateProtected(String packageName, int userId) {
            return mProtectedPackages.isPackageStateProtected(userId, packageName);
        }

        @Override
        public boolean isPackageEphemeral(int userId, String packageName) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(packageName);
                return ps != null && ps.getInstantApp(userId);
            }
        }

        @Override
        public boolean wasPackageEverLaunched(String packageName, int userId) {
            synchronized (mLock) {
                return mSettings.wasPackageEverLaunchedLPr(packageName, userId);
            }
        }

        // TODO(188814480) should be able to remove the NPE check when snapshot
        // "recursion" is fixed.
        @Override
        public boolean isEnabledAndMatches(ParsedMainComponent component, int flags, int userId) {
            synchronized (mLock) {
                AndroidPackage pkg = getPackage(component.getPackageName());
                if (pkg == null) {
                    return false;
                } else {
                    return mSettings.isEnabledAndMatchLPr(pkg, component, flags, userId);
                }
            }
        }

        @Override
        public boolean userNeedsBadging(int userId) {
            synchronized (mLock) {
                return PackageManagerService.this.userNeedsBadging(userId);
            }
        }

        @Override
        public String getNameForUid(int uid) {
            return PackageManagerService.this.getNameForUid(uid);
        }

        @Override
        public void requestInstantAppResolutionPhaseTwo(AuxiliaryResolveInfo responseObj,
                Intent origIntent, String resolvedType, String callingPackage,
                @Nullable String callingFeatureId, boolean isRequesterInstantApp,
                Bundle verificationBundle, int userId) {
            PackageManagerService.this.requestInstantAppResolutionPhaseTwo(responseObj, origIntent,
                    resolvedType, callingPackage, callingFeatureId, isRequesterInstantApp,
                    verificationBundle, userId);
        }

        @Override
        public void grantImplicitAccess(int userId, Intent intent,
                int recipientAppId, int visibleUid, boolean direct) {
            grantImplicitAccess(userId, intent, recipientAppId, visibleUid, direct,
                    false /* retainOnUpdate */);
        }

        @Override
        public void grantImplicitAccess(int userId, Intent intent,
                int recipientAppId, int visibleUid, boolean direct, boolean retainOnUpdate) {
            synchronized (mLock) {
                final AndroidPackage visiblePackage = getPackage(visibleUid);
                final int recipientUid = UserHandle.getUid(userId, recipientAppId);
                if (visiblePackage == null || getPackage(recipientUid) == null) {
                    return;
                }

                final boolean instantApp =
                        isInstantAppInternal(visiblePackage.getPackageName(), userId, visibleUid);
                final boolean accessGranted;
                if (instantApp) {
                    if (!direct) {
                        // if the interaction that lead to this granting access to an instant app
                        // was indirect (i.e.: URI permission grant), do not actually execute the
                        // grant.
                        return;
                    }
                    accessGranted = mInstantAppRegistry.grantInstantAccessLPw(userId, intent,
                            recipientAppId, UserHandle.getAppId(visibleUid) /*instantAppId*/);
                } else {
                    accessGranted = mAppsFilter.grantImplicitAccess(recipientUid, visibleUid,
                            retainOnUpdate);
                }
                if (accessGranted) {
                    ApplicationPackageManager.invalidateGetPackagesForUidCache();
                }
            }
        }

        @Override
        public boolean isInstantAppInstallerComponent(ComponentName component) {
            synchronized (mLock) {
                return mInstantAppInstallerActivity != null
                        && mInstantAppInstallerActivity.getComponentName().equals(component);
            }
        }

        @Override
        public void pruneInstantApps() {
            mInstantAppRegistry.pruneInstantApps();
        }

        @Override
        public void pruneCachedApksInApex(@NonNull List<PackageInfo> apexPackages) {
            if (mCacheDir == null) {
                return;
            }

            final PackageCacher cacher = new PackageCacher(mCacheDir);
            synchronized (mLock) {
                for (int i = 0, size = apexPackages.size(); i < size; i++) {
                    final List<String> apkNames =
                            mApexManager.getApksInApex(apexPackages.get(i).packageName);
                    for (int j = 0, apksInApex = apkNames.size(); j < apksInApex; j++) {
                        final AndroidPackage pkg = getPackage(apkNames.get(j));
                        cacher.cleanCachedResult(new File(pkg.getPath()));
                    }
                }
            }
        }

        @Override
        public String getSetupWizardPackageName() {
            return mSetupWizardPackage;
        }

        public void setExternalSourcesPolicy(ExternalSourcesPolicy policy) {
            if (policy != null) {
                mExternalSourcesPolicy = policy;
            }
        }

        @Override
        public boolean isPackagePersistent(String packageName) {
            synchronized (mLock) {
                AndroidPackage pkg = mPackages.get(packageName);
                return pkg != null && pkg.isSystem() && pkg.isPersistent();
            }
        }

        @Override
        public List<PackageInfo> getOverlayPackages(int userId) {
            final ArrayList<PackageInfo> overlayPackages = new ArrayList<PackageInfo>();
            synchronized (mLock) {
                for (AndroidPackage p : mPackages.values()) {
                    if (p.getOverlayTarget() != null) {
                        PackageInfo pkg = generatePackageInfo(getPackageSetting(p.getPackageName()),
                                0, userId);
                        if (pkg != null) {
                            overlayPackages.add(pkg);
                        }
                    }
                }
            }
            return overlayPackages;
        }

        @Override
        public List<String> getTargetPackageNames(int userId) {
            List<String> targetPackages = new ArrayList<>();
            synchronized (mLock) {
                for (AndroidPackage p : mPackages.values()) {
                    if (p.getOverlayTarget() == null) {
                        targetPackages.add(p.getPackageName());
                    }
                }
            }
            return targetPackages;
        }

        @Override
        public boolean setEnabledOverlayPackages(int userId, @NonNull String targetPackageName,
                @Nullable OverlayPaths overlayPaths,
                @NonNull Set<String> outUpdatedPackageNames) {
            boolean modified = false;
            synchronized (mLock) {
                final AndroidPackage targetPkg = mPackages.get(targetPackageName);
                if (targetPackageName == null || targetPkg == null) {
                    Slog.e(TAG, "failed to find package " + targetPackageName);
                    return false;
                }

                if (targetPkg.getLibraryNames() != null) {
                    // Set the overlay paths for dependencies of the shared library.
                    for (final String libName : targetPkg.getLibraryNames()) {
                        final SharedLibraryInfo info = getSharedLibraryInfoLPr(libName,
                                SharedLibraryInfo.VERSION_UNDEFINED);
                        if (info == null) {
                            continue;
                        }
                        final List<VersionedPackage> dependents = getPackagesUsingSharedLibraryLPr(
                                info, 0, Process.SYSTEM_UID, userId);
                        if (dependents == null) {
                            continue;
                        }
                        for (final VersionedPackage dependent : dependents) {
                            final PackageSetting ps = mSettings.getPackageLPr(
                                    dependent.getPackageName());
                            if (ps == null) {
                                continue;
                            }
                            if (ps.setOverlayPathsForLibrary(libName, overlayPaths, userId)) {
                                outUpdatedPackageNames.add(dependent.getPackageName());
                                modified = true;
                            }
                        }
                    }
                }

                final PackageSetting ps = mSettings.getPackageLPr(targetPackageName);
                if (ps.setOverlayPaths(overlayPaths, userId)) {
                    outUpdatedPackageNames.add(targetPackageName);
                    modified = true;
                }

                if (modified) {
                    invalidatePackageInfoCache();
                }
            }

            return true;
        }

        @Override
        public ResolveInfo resolveIntent(Intent intent, String resolvedType,
                int flags, int privateResolveFlags, int userId, boolean resolveForStart,
                int filterCallingUid) {
            return resolveIntentInternal(
                    intent, resolvedType, flags, privateResolveFlags, userId, resolveForStart,
                    filterCallingUid);
        }

        @Override
        public ResolveInfo resolveService(Intent intent, String resolvedType,
                int flags, int userId, int callingUid) {
            return resolveServiceInternal(intent, resolvedType, flags, userId, callingUid);
        }

        @Override
        public ProviderInfo resolveContentProvider(String name, int flags, int userId,
                int callingUid) {
            return PackageManagerService.this.resolveContentProviderInternal(
                    name, flags, userId, callingUid);
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
        public int getUidTargetSdkVersion(int uid) {
            synchronized (mLock) {
                return getUidTargetSdkVersionLockedLPr(uid);
            }
        }

        @Override
        public int getPackageTargetSdkVersion(String packageName) {
            synchronized (mLock) {
                return getPackageTargetSdkVersionLockedLPr(packageName);
            }
        }

        @Override
        public boolean canAccessInstantApps(int callingUid, int userId) {
            return PackageManagerService.this.canViewInstantApps(callingUid, userId);
        }

        @Override
        public boolean canAccessComponent(int callingUid, ComponentName component, int userId) {
            synchronized (mLock) {
                final PackageSetting ps = mSettings.getPackageLPr(component.getPackageName());
                return ps != null && !PackageManagerService.this.shouldFilterApplicationLocked(
                        ps, callingUid, component, TYPE_UNKNOWN, userId);
            }
        }

        @Override
        public boolean hasInstantApplicationMetadata(String packageName, int userId) {
            synchronized (mLock) {
                return mInstantAppRegistry.hasInstantApplicationMetadataLPr(packageName, userId);
            }
        }

        @Override
        public void notifyPackageUse(String packageName, int reason) {
            synchronized (mLock) {
                PackageManagerService.this.notifyPackageUseLocked(packageName, reason);
            }
        }

        @Override
        public SparseArray<String> getAppsWithSharedUserIds() {
            synchronized (mLock) {
                return getAppsWithSharedUserIdsLocked();
            }
        }

        @Override
        @NonNull
        public String[] getSharedUserPackagesForPackage(String packageName, int userId) {
            synchronized (mLock) {
                return getSharedUserPackagesForPackageLocked(packageName, userId);
            }
        }

        @Override
        public ArrayMap<String, ProcessInfo> getProcessesForUid(int uid) {
            synchronized (mLock) {
                return getProcessesForUidLocked(uid);
            }
        }

        @Override
        public int[] getPermissionGids(String permissionName, int userId) {
            return mPermissionManager.getPermissionGids(permissionName, userId);
        }

        @Override
        public boolean isOnlyCoreApps() {
            return PackageManagerService.this.isOnlyCoreApps();
        }

        @Override
        public void freeStorage(String volumeUuid, long bytes, int storageFlags)
                throws IOException {
            PackageManagerService.this.freeStorage(volumeUuid, bytes, storageFlags);
        }

        @Override
        public void forEachPackage(Consumer<AndroidPackage> actionLocked) {
            PackageManagerService.this.forEachPackage(actionLocked);
        }

        @Override
        public void forEachPackageSetting(Consumer<PackageSetting> actionLocked) {
            PackageManagerService.this.forEachPackageSetting(actionLocked);
        }

        @Override
        public void forEachPackageState(boolean locked, Consumer<PackageState> action) {
            PackageManagerService.this.forEachPackageState(locked, action);
        }

        @Override
        public void forEachInstalledPackage(@NonNull Consumer<AndroidPackage> actionLocked,
                @UserIdInt int userId) {
            PackageManagerService.this.forEachInstalledPackage(actionLocked, userId);
        }

        @Override
        public ArraySet<String> getEnabledComponents(String packageName, int userId) {
            synchronized (mLock) {
                PackageSetting setting = mSettings.getPackageLPr(packageName);
                if (setting == null) {
                    return new ArraySet<>();
                }
                return setting.getEnabledComponents(userId);
            }
        }

        @Override
        public ArraySet<String> getDisabledComponents(String packageName, int userId) {
            synchronized (mLock) {
                PackageSetting setting = mSettings.getPackageLPr(packageName);
                if (setting == null) {
                    return new ArraySet<>();
                }
                return setting.getDisabledComponents(userId);
            }
        }

        @Override
        public @PackageManager.EnabledState int getApplicationEnabledState(
                String packageName, int userId) {
            synchronized (mLock) {
                PackageSetting setting = mSettings.getPackageLPr(packageName);
                if (setting == null) {
                    return COMPONENT_ENABLED_STATE_DEFAULT;
                }
                return setting.getEnabled(userId);
            }
        }

        @Override
        public @PackageManager.EnabledState int getComponentEnabledSetting(
                @NonNull ComponentName componentName, int callingUid, int userId) {
            return PackageManagerService.this.getComponentEnabledSettingInternal(componentName,
                    callingUid, userId);
        }

        @Override
        public void setEnableRollbackCode(int token, int enableRollbackCode) {
            PackageManagerService.this.setEnableRollbackCode(token, enableRollbackCode);
        }

        /**
         * Ask the package manager to compile layouts in the given package.
         */
        @Override
        public boolean compileLayouts(String packageName) {
            AndroidPackage pkg;
            synchronized (mLock) {
                pkg = mPackages.get(packageName);
                if (pkg == null) {
                    return false;
                }
            }
            return mArtManagerService.compileLayouts(pkg);
        }

        @Override
        public void finishPackageInstall(int token, boolean didLaunch) {
            PackageManagerService.this.finishPackageInstall(token, didLaunch);
        }

        @Nullable
        @Override
        public String removeLegacyDefaultBrowserPackageName(int userId) {
            synchronized (mLock) {
                return mSettings.removeDefaultBrowserPackageNameLPw(userId);
            }
        }

        @Override
        public boolean isApexPackage(String packageName) {
            return PackageManagerService.this.mApexManager.isApexPackage(packageName);
        }

        @Override
        public List<String> getApksInApex(String apexPackageName) {
            return PackageManagerService.this.mApexManager.getApksInApex(apexPackageName);
        }

        @Override
        public void uninstallApex(String packageName, long versionCode, int userId,
                IntentSender intentSender, int flags) {
            final int callerUid = Binder.getCallingUid();
            if (callerUid != Process.ROOT_UID && callerUid != Process.SHELL_UID) {
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
            PackageInfo activePackage = am.getPackageInfo(packageName,
                    ApexManager.MATCH_ACTIVE_PACKAGE);
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

        @Override
        public void updateRuntimePermissionsFingerprint(@UserIdInt int userId) {
            synchronized (mLock) {
                mSettings.updateRuntimePermissionsFingerprintLPr(userId);
            }
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
                    scheduleWriteSettingsLocked();
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
        public boolean isCallerInstallerOfRecord(
                @NonNull AndroidPackage pkg, int callingUid) {
            synchronized (mLock) {
                if (pkg == null) {
                    return false;
                }
                final PackageSetting packageSetting = getPackageSetting(pkg.getPackageName());
                if (packageSetting == null) {
                    return false;
                }
                final PackageSetting installerPackageSetting =
                        mSettings.getPackageLPr(packageSetting.getInstallSource()
                                .installerPackageName);
                return installerPackageSetting != null
                        && UserHandle.isSameApp(installerPackageSetting.getAppId(), callingUid);
            }
        }

        @Override
        public boolean isPermissionUpgradeNeeded(int userId) {
            synchronized (mLock) {
                return mSettings.isPermissionUpgradeNeededLPr(userId);
            }
        }

        @Override
        public void setIntegrityVerificationResult(int verificationId, int verificationResult) {
            final Message msg = mHandler.obtainMessage(INTEGRITY_VERIFICATION_COMPLETE);
            msg.arg1 = verificationId;
            msg.obj = verificationResult;
            mHandler.sendMessage(msg);
        }

        @Override
        public List<String> getMimeGroup(String packageName, String mimeGroup) {
            return PackageManagerService.this.getMimeGroupInternal(packageName, mimeGroup);
        }

        @Override
        public void setVisibilityLogging(String packageName, boolean enable) {
            final PackageSetting pkg;
            synchronized (mLock) {
                pkg = mSettings.getPackageLPr(packageName);
            }
            if (pkg == null) {
                throw new IllegalStateException("No package found for " + packageName);
            }
            mAppsFilter.getFeatureConfig().enableLogging(pkg.getAppId(), enable);
        }

        @Override
        public boolean isSystemPackage(@NonNull String packageName) {
            return packageName.equals(
                    PackageManagerService.this.ensureSystemPackageName(packageName));
        }

        @Override
        public void clearBlockUninstallForUser(@UserIdInt int userId) {
            synchronized (mLock) {
                mSettings.clearBlockUninstallLPw(userId);
                mSettings.writePackageRestrictionsLPr(userId);
            }
        }

        @Override
        public void unsuspendForSuspendingPackage(final String packageName, int affectedUser) {
            PackageManagerService.this.unsuspendForSuspendingPackage(packageName, affectedUser);
        }

        @Override
        public boolean isSuspendingAnyPackages(String suspendingPackage, int userId) {
            return PackageManagerService.this.isSuspendingAnyPackages(suspendingPackage, userId);
        }

        @Override
        public boolean registerInstalledLoadingProgressCallback(String packageName,
                PackageManagerInternal.InstalledLoadingProgressCallback callback, int userId) {
            final PackageSetting ps = getPackageSettingForUser(packageName, Binder.getCallingUid(),
                    userId);
            if (ps == null) {
                return false;
            }
            if (!ps.isPackageLoading()) {
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
            final PackageSetting ps = getPackageSettingForUser(packageName, filterCallingUid,
                    userId);
            if (ps == null) {
                return null;
            }
            return ps.getIncrementalStatesInfo();
        }

        @Override
        public void requestChecksums(@NonNull String packageName, boolean includeSplits,
                @Checksum.TypeMask int optional, @Checksum.TypeMask int required,
                @Nullable List trustedInstallers,
                @NonNull IOnChecksumsReadyListener onChecksumsReadyListener, int userId,
                @NonNull Executor executor, @NonNull Handler handler) {
            requestChecksumsInternal(packageName, includeSplits, optional, required,
                    trustedInstallers, onChecksumsReadyListener, userId, executor, handler);
        }

        @Override
        public boolean isPackageFrozen(@NonNull String packageName,
                int callingUid, int userId) {
            return PackageManagerService.this.getPackageStartability(
                    packageName, callingUid, userId) == PACKAGE_STARTABILITY_FROZEN;
        }

        @Override
        public long deleteOatArtifactsOfPackage(String packageName) {
            return PackageManagerService.this.deleteOatArtifactsOfPackage(packageName);
        }

        @Override
        public void withPackageSettingsSnapshot(
                @NonNull Consumer<Function<String, PackageSetting>> block) {
            final Computer snapshot = snapshotComputer();

            // This method needs to either lock or not lock consistently throughout the method,
            // so if the live computer is returned, force a wrapping sync block.
            if (snapshot == mLiveComputer) {
                synchronized (mLock) {
                    block.accept(snapshot::getPackageSetting);
                }
            } else {
                block.accept(snapshot::getPackageSetting);
            }
        }

        @Override
        public <Output> Output withPackageSettingsSnapshotReturning(
                @NonNull FunctionalUtils.ThrowingFunction<Function<String, PackageSetting>, Output>
                        block) {
            final Computer snapshot = snapshotComputer();

            // This method needs to either lock or not lock consistently throughout the method,
            // so if the live computer is returned, force a wrapping sync block.
            if (snapshot == mLiveComputer) {
                synchronized (mLock) {
                    return block.apply(snapshot::getPackageSetting);
                }
            } else {
                return block.apply(snapshot::getPackageSetting);
            }
        }

        @Override
        public <ExceptionType extends Exception> void withPackageSettingsSnapshotThrowing(
                @NonNull FunctionalUtils.ThrowingCheckedConsumer<Function<String, PackageSetting>,
                        ExceptionType> block) throws ExceptionType {
            final Computer snapshot = snapshotComputer();

            // This method needs to either lock or not lock consistently throughout the method,
            // so if the live computer is returned, force a wrapping sync block.
            if (snapshot == mLiveComputer) {
                synchronized (mLock) {
                    block.accept(snapshot::getPackageSetting);
                }
            } else {
                block.accept(snapshot::getPackageSetting);
            }
        }

        @Override
        public <ExceptionOne extends Exception, ExceptionTwo extends Exception> void
                withPackageSettingsSnapshotThrowing2(
                        @NonNull FunctionalUtils.ThrowingChecked2Consumer<
                                Function<String, PackageSetting>, ExceptionOne, ExceptionTwo> block)
                throws ExceptionOne, ExceptionTwo {
            final Computer snapshot = snapshotComputer();

            // This method needs to either lock or not lock consistently throughout the method,
            // so if the live computer is returned, force a wrapping sync block.
            if (snapshot == mLiveComputer) {
                synchronized (mLock) {
                    block.accept(snapshot::getPackageSetting);
                }
            } else {
                block.accept(snapshot::getPackageSetting);
            }
        }

        @Override
        public <Output, ExceptionType extends Exception> Output
                withPackageSettingsSnapshotReturningThrowing(
                        @NonNull FunctionalUtils.ThrowingCheckedFunction<
                                Function<String, PackageSetting>, Output, ExceptionType> block)
                throws ExceptionType {
            final Computer snapshot = snapshotComputer();

            // This method needs to either lock or not lock consistently throughout the method,
            // so if the live computer is returned, force a wrapping sync block.
            if (snapshot == mLiveComputer) {
                synchronized (mLock) {
                    return block.apply(snapshot::getPackageSetting);
                }
            } else {
                return block.apply(snapshot::getPackageSetting);
            }
        }
    }

    @GuardedBy("mLock")
    private SparseArray<String> getAppsWithSharedUserIdsLocked() {
        final SparseArray<String> sharedUserIds = new SparseArray<>();
        synchronized (mLock) {
            for (SharedUserSetting setting : mSettings.getAllSharedUsersLPw()) {
                sharedUserIds.put(UserHandle.getAppId(setting.userId), setting.name);
            }
        }
        return sharedUserIds;
    }

    @GuardedBy("mLock")
    @NonNull
    private String[] getSharedUserPackagesForPackageLocked(String packageName, int userId) {
        final PackageSetting packageSetting = mSettings.getPackageLPr(packageName);
        if (packageSetting == null || !packageSetting.isSharedUser()) {
            return EmptyArray.STRING;
        }

        ArraySet<PackageSetting> packages = packageSetting.getSharedUser().packages;
        final int numPackages = packages.size();
        String[] res = new String[numPackages];
        int i = 0;
        for (int index = 0; index < numPackages; index++) {
            final PackageSetting ps = packages.valueAt(index);
            if (ps.getInstalled(userId)) {
                res[i++] = ps.getPackageName();
            }
        }
        res = ArrayUtils.trimToSize(res, i);
        return res != null ? res : EmptyArray.STRING;
    }

    @GuardedBy("mLock")
    public ArrayMap<String, ProcessInfo> getProcessesForUidLocked(int uid) {
        final int appId = UserHandle.getAppId(uid);
        final SettingBase obj = mSettings.getSettingLPr(appId);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            return PackageInfoUtils.generateProcessInfo(sus.processes, 0);
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            return PackageInfoUtils.generateProcessInfo(ps.getPkg().getProcesses(), 0);
        }
        return null;
    }

    @Override
    public int getRuntimePermissionsVersion(@UserIdInt int userId) {
        Preconditions.checkArgumentNonnegative(userId);
        enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
                "getRuntimePermissionVersion");
        synchronized (mLock) {
            return mSettings.getDefaultRuntimePermissionsVersionLPr(userId);
        }
    }

    @Override
    public void setRuntimePermissionsVersion(int version, @UserIdInt int userId) {
        Preconditions.checkArgumentNonnegative(version);
        Preconditions.checkArgumentNonnegative(userId);
        enforceAdjustRuntimePermissionsPolicyOrUpgradeRuntimePermissions(
                "setRuntimePermissionVersion");
        synchronized (mLock) {
            mSettings.setDefaultRuntimePermissionsVersionLPr(version, userId);
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

    @Nullable
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    PackageSetting getPackageSetting(String packageName) {
        return mComputer.getPackageSetting(packageName);
    }

    PackageSetting getPackageSettingInternal(String packageName, int callingUid) {
        return mComputer.getPackageSettingInternal(packageName, callingUid);
    }

    @Nullable
    private PackageState getPackageState(String packageName) {
        return mComputer.getPackageState(packageName);
    }

    void forEachPackage(Consumer<AndroidPackage> actionLocked) {
        synchronized (mLock) {
            int numPackages = mPackages.size();
            for (int i = 0; i < numPackages; i++) {
                actionLocked.accept(mPackages.valueAt(i));
            }
        }
    }

    private void forEachPackageSetting(Consumer<PackageSetting> actionLocked) {
        synchronized (mLock) {
            int size = mSettings.getPackagesLocked().size();
            for (int index = 0; index < size; index++) {
                actionLocked.accept(mSettings.getPackagesLocked().valueAt(index));
            }
        }
    }

    private void forEachPackageState(boolean locked, Consumer<PackageState> action) {
        if (locked) {
            forEachPackageSetting(action::accept);
        } else {
            List<PackageState> packageStates = new ArrayList<>();
            forEachPackageSetting(pkgSetting ->
                    packageStates.add(PackageStateImpl.copy(pkgSetting)));
            int size = packageStates.size();
            for (int index = 0; index < size; index++) {
                action.accept(packageStates.get(index));
            }
        }
    }

    void forEachInstalledPackage(@NonNull Consumer<AndroidPackage> actionLocked,
            @UserIdInt int userId) {
        synchronized (mLock) {
            int numPackages = mPackages.size();
            for (int i = 0; i < numPackages; i++) {
                AndroidPackage pkg = mPackages.valueAt(i);
                PackageSetting setting = mSettings.getPackageLPr(pkg.getPackageName());
                if (setting == null || !setting.getInstalled(userId)) {
                    continue;
                }
                actionLocked.accept(pkg);
            }
        }
    }

    boolean isHistoricalPackageUsageAvailable() {
        synchronized (mLock) {
            return mPackageUsage.isHistoricalPackageUsageAvailable();
        }
    }

    /**
     * Logs process start information (including base APK hash) to the security log.
     * @hide
     */
    @Override
    public void logAppProcessStartIfNeeded(String packageName, String processName, int uid,
            String seinfo, String apkFile, int pid) {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return;
        }
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        mProcessLoggingHandler.logAppProcessStart(mContext, mPmInternal, apkFile, packageName,
                processName, uid, seinfo, pid);
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(AndroidPackage pkg) {
        return getOrCreateCompilerPackageStats(pkg.getPackageName());
    }

    public CompilerStats.PackageStats getOrCreateCompilerPackageStats(String pkgName) {
        return mCompilerStats.getOrCreatePackageStats(pkgName);
    }

    @Override
    public boolean isAutoRevokeWhitelisted(String packageName) {
        int mode = mInjector.getSystemService(AppOpsManager.class).checkOpNoThrow(
                AppOpsManager.OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
                Binder.getCallingUid(), packageName);
        return mode == MODE_IGNORED;
    }

    @Override
    public int getInstallReason(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true /* requireFullPermission */,
                false /* checkShell */, "get install reason");
        synchronized (mLock) {
            final PackageSetting ps = mSettings.getPackageLPr(packageName);
            if (shouldFilterApplicationLocked(ps, callingUid, userId)) {
                return PackageManager.INSTALL_REASON_UNKNOWN;
            }
            if (ps != null) {
                return ps.getInstallReason(userId);
            }
        }
        return PackageManager.INSTALL_REASON_UNKNOWN;
    }

    @Override
    public boolean canRequestPackageInstalls(String packageName, int userId) {
        return canRequestPackageInstallsInternal(packageName, Binder.getCallingUid(), userId,
                true /* throwIfPermNotDeclared*/);
    }

    private boolean canRequestPackageInstallsInternal(String packageName, int callingUid,
            int userId, boolean throwIfPermNotDeclared) {
        int uid = getPackageUidInternal(packageName, 0, userId, callingUid);
        if (callingUid != uid && callingUid != Process.ROOT_UID
                && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "Caller uid " + callingUid + " does not own package " + packageName);
        }
        if (isInstantAppInternal(packageName, userId, callingUid)) {
            return false;
        }
        final AndroidPackage pkg;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
        }
        if (pkg == null) {
            return false;
        }
        if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.O) {
            return false;
        }
        if (!pkg.getRequestedPermissions().contains(
                android.Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
            final String message = "Need to declare "
                    + android.Manifest.permission.REQUEST_INSTALL_PACKAGES
                    + " to call this api";
            if (throwIfPermNotDeclared) {
                throw new SecurityException(message);
            } else {
                Slog.e(TAG, message);
                return false;
            }
        }

        return !isInstallDisabledForPackage(packageName, uid, userId);
    }

    /**
     * Returns true if the system or user is explicitly preventing an otherwise valid installer to
     * complete an install. This includes checks like unknown sources and user restrictions.
     */
    public boolean isInstallDisabledForPackage(String packageName, int uid, int userId) {
        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, userId)
                || mUserManager.hasUserRestriction(
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, userId)) {
            return true;
        }
        if (mExternalSourcesPolicy != null) {
            int isTrusted = mExternalSourcesPolicy.getPackageTrustedToInstallApps(packageName, uid);
            return isTrusted != PackageManagerInternal.ExternalSourcesPolicy.USER_TRUSTED;
        }
        return false;
    }

    @Override
    public ComponentName getInstantAppResolverSettingsComponent() {
        return mInstantAppResolverSettingsComponent;
    }

    @Override
    public ComponentName getInstantAppInstallerComponent() {
        if (getInstantAppPackageName(Binder.getCallingUid()) != null) {
            return null;
        }
        return mInstantAppInstallerActivity == null
                ? null : mInstantAppInstallerActivity.getComponentName();
    }

    @Override
    public String getInstantAppAndroidId(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_INSTANT_APPS,
                "getInstantAppAndroidId");
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true /* requireFullPermission */,
                false /* checkShell */, "getInstantAppAndroidId");
        // Make sure the target is an Instant App.
        if (!isInstantApp(packageName, userId)) {
            return null;
        }
        synchronized (mLock) {
            return mInstantAppRegistry.getInstantAppAndroidIdLPw(packageName, userId);
        }
    }

    @Override
    public void grantImplicitAccess(int recipientUid, String visibleAuthority) {
        // This API is exposed temporarily to only the contacts provider. (b/158688602)
        final int callingUid = Binder.getCallingUid();
        ProviderInfo contactsProvider = resolveContentProviderInternal(ContactsContract.AUTHORITY,
                0, UserHandle.getUserId(callingUid), callingUid);
        if (contactsProvider == null || contactsProvider.applicationInfo == null
                || !UserHandle.isSameApp(contactsProvider.applicationInfo.uid, callingUid)) {
            throw new SecurityException(callingUid + " is not allow to call grantImplicitAccess");
        }
        final int userId = UserHandle.getUserId(recipientUid);
        final long token = Binder.clearCallingIdentity();
        final ProviderInfo providerInfo;
        try {
            providerInfo = resolveContentProvider(visibleAuthority, 0 /*flags*/, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (providerInfo == null) {
            return;
        }
        int visibleUid = providerInfo.applicationInfo.uid;
        mPmInternal.grantImplicitAccess(userId, null /*Intent*/, UserHandle.getAppId(recipientUid),
                visibleUid, false /*direct*/);
    }

    boolean canHaveOatDir(String packageName) {
        synchronized (mLock) {
            AndroidPackage p = mPackages.get(packageName);
            PackageSetting pkgSetting = mSettings.getPackageLPr(packageName);
            if (p == null || pkgSetting == null) {
                return false;
            }
            return AndroidPackageUtils.canHaveOatDir(p,
                    pkgSetting.getPkgState().isUpdatedSystemApp());
        }
    }

    long deleteOatArtifactsOfPackage(String packageName) {
        final AndroidPackage pkg;
        final PackageSetting pkgSetting;
        synchronized (mLock) {
            pkg = mPackages.get(packageName);
            pkgSetting = mSettings.getPackageLPr(packageName);
        }
        return mDexManager.deleteOptimizedFiles(ArtUtils.createArtPackageInfo(pkg, pkgSetting));
    }

    Set<String> getUnusedPackages(long downgradeTimeThresholdMillis) {
        Set<String> unusedPackages = new HashSet<>();
        long currentTimeInMillis = System.currentTimeMillis();
        synchronized (mLock) {
            for (AndroidPackage pkg : mPackages.values()) {
                PackageSetting ps =  mSettings.getPackageLPr(pkg.getPackageName());
                if (ps == null) {
                    continue;
                }
                PackageDexUsage.PackageUseInfo packageUseInfo =
                      getDexManager().getPackageUseInfoOrDefault(pkg.getPackageName());
                if (PackageManagerServiceUtils
                        .isUnusedSinceTimeInMillis(ps.getFirstInstallTime(), currentTimeInMillis,
                                downgradeTimeThresholdMillis, packageUseInfo,
                                ps.getPkgState().getLatestPackageUseTimeInMills(),
                                ps.getPkgState().getLatestForegroundPackageUseTimeInMills())) {
                    unusedPackages.add(pkg.getPackageName());
                }
            }
        }
        return unusedPackages;
    }

    @Override
    public void setHarmfulAppWarning(@NonNull String packageName, @Nullable CharSequence warning,
            int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        enforceCrossUserPermission(callingUid, userId, true /*requireFullPermission*/,
                true /*checkShell*/, "setHarmfulAppInfo");

        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.ROOT_UID &&
                checkUidPermission(SET_HARMFUL_APP_WARNINGS, callingUid) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have the "
                    + SET_HARMFUL_APP_WARNINGS + " permission.");
        }

        synchronized (mLock) {
            mSettings.setHarmfulAppWarningLPw(packageName, warning, userId);
            scheduleWritePackageRestrictionsLocked(userId);
        }
    }

    @Nullable
    @Override
    public CharSequence getHarmfulAppWarning(@NonNull String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        enforceCrossUserPermission(callingUid, userId, true /*requireFullPermission*/,
                true /*checkShell*/, "getHarmfulAppInfo");

        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.ROOT_UID &&
                checkUidPermission(SET_HARMFUL_APP_WARNINGS, callingUid) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have the "
                    + SET_HARMFUL_APP_WARNINGS + " permission.");
        }

        synchronized (mLock) {
            return mSettings.getHarmfulAppWarningLPr(packageName, userId);
        }
    }

    @Override
    public boolean isPackageStateProtected(@NonNull String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingAppId = UserHandle.getAppId(callingUid);

        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                true /*checkShell*/, "isPackageStateProtected");

        if (callingAppId != Process.SYSTEM_UID && callingAppId != Process.ROOT_UID
                && checkUidPermission(MANAGE_DEVICE_ADMINS, callingUid) != PERMISSION_GRANTED) {
            throw new SecurityException("Caller must have the "
                    + MANAGE_DEVICE_ADMINS + " permission.");
        }

        return mProtectedPackages.isPackageStateProtected(userId, packageName);
    }

    @Override
    public void sendDeviceCustomizationReadyBroadcast() {
        mContext.enforceCallingPermission(Manifest.permission.SEND_DEVICE_CUSTOMIZATION_READY,
                "sendDeviceCustomizationReadyBroadcast");

        final long ident = Binder.clearCallingIdentity();
        try {
            final Intent intent = new Intent(Intent.ACTION_DEVICE_CUSTOMIZATION_READY);
            intent.setFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            final IActivityManager am = ActivityManager.getService();
            final String[] requiredPermissions = {
                Manifest.permission.RECEIVE_DEVICE_CUSTOMIZATION_READY,
            };
            try {
                am.broadcastIntentWithFeature(null, null, intent, null, null, 0, null, null,
                        requiredPermissions, null, android.app.AppOpsManager.OP_NONE, null, false,
                        false, UserHandle.USER_ALL);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void applyMimeGroupChanges(String packageName, String mimeGroup) {
        if (mComponentResolver.updateMimeGroup(packageName, mimeGroup)) {
            Binder.withCleanCallingIdentity(() ->
                    clearPackagePreferredActivities(packageName, UserHandle.USER_ALL));
        }

        mPmInternal.writeSettings(false);
    }

    @Override
    public void setMimeGroup(String packageName, String mimeGroup, List<String> mimeTypes) {
        enforceOwnerRights(packageName, Binder.getCallingUid());
        final boolean changed;
        synchronized (mLock) {
            changed = mSettings.getPackageLPr(packageName).setMimeGroup(mimeGroup, mimeTypes);
        }
        if (changed) {
            applyMimeGroupChanges(packageName, mimeGroup);
        }
    }

    @Override
    public List<String> getMimeGroup(String packageName, String mimeGroup) {
        enforceOwnerRights(packageName, Binder.getCallingUid());
        return getMimeGroupInternal(packageName, mimeGroup);
    }

    private List<String> getMimeGroupInternal(String packageName, String mimeGroup) {
        synchronized (mLock) {
            return mSettings.getPackageLPr(packageName).getMimeGroup(mimeGroup);
        }
    }

    @Override
    public void setSplashScreenTheme(@NonNull String packageName, @Nullable String themeId,
            int userId) {
        int callingUid = Binder.getCallingUid();
        PackageSetting packageSetting = getPackageSettingForUser(packageName, callingUid, userId);
        if (packageSetting != null) {
            packageSetting.setSplashScreenTheme(userId, themeId);
        }
    }

    @Override
    public String getSplashScreenTheme(@NonNull String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        PackageSetting packageSetting = getPackageSettingForUser(packageName, callingUid, userId);
        return packageSetting != null ? packageSetting.getSplashScreenTheme(userId) : null;
    }

    /**
     * Temporary method that wraps mSettings.writeLPr() and calls mPermissionManager's
     * writeLegacyPermissionsTEMP() beforehand.
     *
     * TODO(zhanghai): This should be removed once we finish migration of permission storage.
     */
    void writeSettingsLPrTEMP() {
        mPermissionManager.writeLegacyPermissionsTEMP(mSettings.mPermissions);
        mSettings.writeLPr();
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

    @Override
    public void holdLock(IBinder token, int durationMs) {
        mTestUtilityService.verifyHoldLockToken(token);

        synchronized (mLock) {
            SystemClock.sleep(durationMs);
        }
    }

    private static String getDefaultTimeouts() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_INCFS_DEFAULT_TIMEOUTS, "");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static String getKnownDigestersList() {
        final long token = Binder.clearCallingIdentity();
        try {
            return DeviceConfig.getString(NAMESPACE_PACKAGE_MANAGER_SERVICE,
                    PROPERTY_KNOWN_DIGESTERS_LIST, "");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Returns the array containing per-uid timeout configuration.
     * This is derived from DeviceConfig flags.
     */
    public @NonNull PerUidReadTimeouts[] getPerUidReadTimeouts() {
        PerUidReadTimeouts[] result = mPerUidReadTimeoutsCache;
        if (result == null) {
            result = parsePerUidReadTimeouts();
            mPerUidReadTimeoutsCache = result;
        }
        return result;
    }

    private @NonNull PerUidReadTimeouts[] parsePerUidReadTimeouts() {
        final String defaultTimeouts = getDefaultTimeouts();
        final String knownDigestersList = getKnownDigestersList();
        final List<PerPackageReadTimeouts> perPackageReadTimeouts =
                PerPackageReadTimeouts.parseDigestersList(defaultTimeouts, knownDigestersList);

        if (perPackageReadTimeouts.size() == 0) {
            return EMPTY_PER_UID_READ_TIMEOUTS_ARRAY;
        }

        final int[] allUsers = mInjector.getUserManagerService().getUserIds();

        List<PerUidReadTimeouts> result = new ArrayList<>(perPackageReadTimeouts.size());
        synchronized (mLock) {
            for (int i = 0, size = perPackageReadTimeouts.size(); i < size; ++i) {
                final PerPackageReadTimeouts perPackage = perPackageReadTimeouts.get(i);
                final PackageSetting ps = mSettings.mPackages.get(perPackage.packageName);
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
                    if (!ps.getInstalled(userId)) {
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
        }
        return result.toArray(new PerUidReadTimeouts[result.size()]);
    }

    static @NonNull BroadcastOptions getTemporaryAppAllowlistBroadcastOptions(
            @PowerWhitelistManager.ReasonCode int reasonCode) {
        long duration = 10_000;
        final ActivityManagerInternal amInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        if (amInternal != null) {
            duration = amInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                reasonCode, "");
        return bOptions;
    }

    @Override
    public void setKeepUninstalledPackages(List<String> packageList) {
        mContext.enforceCallingPermission(
                Manifest.permission.KEEP_UNINSTALLED_PACKAGES,
                "setKeepUninstalledPackages requires KEEP_UNINSTALLED_PACKAGES permission");
        Objects.requireNonNull(packageList);

        setKeepUninstalledPackagesInternal(packageList);
    }

    private void setKeepUninstalledPackagesInternal(List<String> packageList) {
        Preconditions.checkNotNull(packageList);
        List<String> removedFromList = null;
        synchronized (mLock) {
            if (mKeepUninstalledPackages != null) {
                final int packagesCount = mKeepUninstalledPackages.size();
                for (int i = 0; i < packagesCount; i++) {
                    String oldPackage = mKeepUninstalledPackages.get(i);
                    if (packageList != null && packageList.contains(oldPackage)) {
                        continue;
                    }
                    if (removedFromList == null) {
                        removedFromList = new ArrayList<>();
                    }
                    removedFromList.add(oldPackage);
                }
            }
            mKeepUninstalledPackages = new ArrayList<>(packageList);
            if (removedFromList != null) {
                final int removedCount = removedFromList.size();
                for (int i = 0; i < removedCount; i++) {
                    deletePackageIfUnusedLPr(removedFromList.get(i));
                }
            }
        }
    }

    @Override
    public IntentSender getLaunchIntentSenderForPackage(String packageName, String callingPackage,
            String featureId, int userId) throws RemoteException {
        Objects.requireNonNull(packageName);
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /* requireFullPermission */,
                false /* checkShell */, "get launch intent sender for package");
        final int packageUid = getPackageUid(callingPackage, 0 /* flags */, userId);
        if (!UserHandle.isSameApp(callingUid, packageUid)) {
            throw new SecurityException("getLaunchIntentSenderForPackage() from calling uid: "
                    + callingUid + " does not own package: " + callingPackage);
        }

        // Using the same implementation with the #getLaunchIntentForPackage to get the ResolveInfo.
        // Pass the resolveForStart as true in queryIntentActivities to skip the app filtering.
        final Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_INFO);
        intentToResolve.setPackage(packageName);
        String resolvedType = intentToResolve.resolveTypeIfNeeded(mContext.getContentResolver());
        List<ResolveInfo> ris = queryIntentActivitiesInternal(intentToResolve, resolvedType,
                0 /* flags */, 0 /* privateResolveFlags */, callingUid, userId,
                true /* resolveForStart */, false /* allowDynamicSplits */);
        if (ris == null || ris.size() <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO);
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
            intentToResolve.setPackage(packageName);
            resolvedType = intentToResolve.resolveTypeIfNeeded(mContext.getContentResolver());
            ris = queryIntentActivitiesInternal(intentToResolve, resolvedType,
                    0 /* flags */, 0 /* privateResolveFlags */, callingUid, userId,
                    true /* resolveForStart */, false /* allowDynamicSplits */);
        }

        final Intent intent = new Intent(intentToResolve);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // For the case of empty result, no component name is assigned into the intent. A
        // non-launchable IntentSender which contains the failed intent is created. The
        // SendIntentException is thrown if the IntentSender#sendIntent is invoked.
        if (ris != null && !ris.isEmpty()) {
            intent.setClassName(ris.get(0).activityInfo.packageName,
                    ris.get(0).activityInfo.name);
        }
        final IIntentSender target = ActivityManager.getService().getIntentSenderWithFeature(
                ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                featureId, null /* token */, null /* resultWho */,
                1 /* requestCode */, new Intent[] { intent },
                resolvedType != null ? new String[] { resolvedType } : null,
                PendingIntent.FLAG_IMMUTABLE, null /* bOptions */, userId);
        return new IntentSender(target);
    }

    @Override
    public boolean canPackageQuery(String sourcePackageName, String targetPackageName, int userId) {
        if (!mUserManager.exists(userId)) return false;
        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, false /*requireFullPermission*/,
                false /*checkShell*/, "may package query");
        synchronized (mLock) {
            final PackageSetting sourceSetting = getPackageSetting(sourcePackageName);
            final PackageSetting targetSetting = getPackageSetting(targetPackageName);
            if (sourceSetting == null || targetSetting == null) {
                throw new ParcelableException(new PackageManager.NameNotFoundException("Package(s) "
                        + (sourceSetting == null ? sourcePackageName + " " : "")
                        + (targetSetting == null ? targetPackageName + " " : "")
                        + "not found."));
            }
            final boolean filterSource =
                    shouldFilterApplicationLocked(sourceSetting, callingUid, userId);
            final boolean filterTarget =
                    shouldFilterApplicationLocked(targetSetting, callingUid, userId);
            // The caller must have visibility of the both packages
            if (filterSource || filterTarget) {
                throw new ParcelableException(new PackageManager.NameNotFoundException("Package(s) "
                        + (filterSource ? sourcePackageName + " " : "")
                        + (filterTarget ? targetPackageName + " " : "")
                        + "not found."));
            }
            final int sourcePackageUid = UserHandle.getUid(userId, sourceSetting.getAppId());
            return !shouldFilterApplicationLocked(targetSetting, sourcePackageUid, userId);
        }
    }

    private static class TempUserState {
        public final int enabledState;
        @Nullable
        public final String lastDisableAppCaller;
        public final boolean installed;

        private TempUserState(int enabledState, @Nullable String lastDisableAppCaller,
                boolean installed) {
            this.enabledState = enabledState;
            this.lastDisableAppCaller = lastDisableAppCaller;
            this.installed = installed;
        }
    }

    public boolean getSafeMode() {
        return mSafeMode;
    }

    public ComponentName getResolveComponentName() {
        return mResolveComponentName;
    }

    public DefaultAppProvider getDefaultAppProvider() {
        return mDefaultAppProvider;
    }
}
