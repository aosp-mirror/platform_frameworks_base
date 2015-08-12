/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_MEDIA_STORAGE;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_POLICY_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE;
import static android.content.pm.PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.INSTALL_EXTERNAL;
import static android.content.pm.PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
import static android.content.pm.PackageManager.INSTALL_FAILED_CONFLICTING_PROVIDER;
import static android.content.pm.PackageManager.INSTALL_FAILED_DEXOPT;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION;
import static android.content.pm.PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;
import static android.content.pm.PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE;
import static android.content.pm.PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_TEST_ONLY;
import static android.content.pm.PackageManager.INSTALL_FAILED_UID_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_FAILED_USER_RESTRICTED;
import static android.content.pm.PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;
import static android.content.pm.PackageManager.INSTALL_FORWARD_LOCK;
import static android.content.pm.PackageManager.INSTALL_INTERNAL;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MOVE_FAILED_DOESNT_EXIST;
import static android.content.pm.PackageManager.MOVE_FAILED_INTERNAL_ERROR;
import static android.content.pm.PackageManager.MOVE_FAILED_OPERATION_PENDING;
import static android.content.pm.PackageManager.MOVE_FAILED_SYSTEM_PACKAGE;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.content.pm.PackageParser.isApkFile;
import static android.os.Process.PACKAGE_INFO_GID;
import static android.os.Process.SYSTEM_UID;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDWR;
import static com.android.internal.app.IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE;
import static com.android.internal.app.IntentForwarderActivity.FORWARD_INTENT_TO_USER_OWNER;
import static com.android.internal.content.NativeLibraryHelper.LIB64_DIR_NAME;
import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.internal.util.ArrayUtils.appendInt;
import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSet;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.InstructionSets.getPreferredInstructionSet;
import static com.android.server.pm.InstructionSets.getPrimaryInstructionSet;
import static com.android.server.pm.PermissionsState.PERMISSION_OPERATION_FAILURE;
import static com.android.server.pm.PermissionsState.PERMISSION_OPERATION_SUCCESS;
import static com.android.server.pm.PermissionsState.PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.admin.IDevicePolicyManager;
import android.app.backup.IBackupManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IOnPermissionsChangeListener;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageDeleteObserver2;
import android.content.pm.IPackageInstallObserver2;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.InstrumentationInfo;
import android.content.pm.IntentFilterVerificationInfo;
import android.content.pm.KeySet;
import android.content.pm.ManifestDigest;
import android.content.pm.PackageCleanItem;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.LegacyPackageDeleteObserver;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ActivityIntentInfo;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageStats;
import android.content.pm.PackageUserState;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.pm.VerificationParams;
import android.content.pm.VerifierDeviceIdentity;
import android.content.pm.VerifierInfo;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Debug;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Environment.UserEnvironment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IMountService;
import android.os.storage.MountServiceInternal;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.security.KeyStore;
import android.security.SystemKeyStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.LogPrinter;
import android.util.MathUtils;
import android.util.PrintStreamPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;
import android.view.Display;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IMediaContainerService;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.NativeLibraryHelper;
import com.android.internal.content.PackageHelper;
import com.android.internal.os.IParcelFileDescriptorFactory;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.IntentResolver;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.Watchdog;
import com.android.server.pm.PermissionsState.PermissionState;
import com.android.server.pm.Settings.DatabaseVersion;
import com.android.server.pm.Settings.VersionInfo;
import com.android.server.storage.DeviceStorageMonitorInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keep track of all those .apks everywhere.
 *
 * This is very central to the platform's security; please run the unit
 * tests whenever making modifications here:
 *
mmm frameworks/base/tests/AndroidTests
adb install -r -f out/target/product/passion/data/app/AndroidTests.apk
adb shell am instrument -w -e class com.android.unit_tests.PackageManagerTests com.android.unit_tests/android.test.InstrumentationTestRunner
 *
 * {@hide}
 */
public class PackageManagerService extends IPackageManager.Stub {
    static final String TAG = "PackageManager";
    static final boolean DEBUG_SETTINGS = false;
    static final boolean DEBUG_PREFERRED = false;
    static final boolean DEBUG_UPGRADE = false;
    static final boolean DEBUG_DOMAIN_VERIFICATION = false;
    private static final boolean DEBUG_BACKUP = false;
    private static final boolean DEBUG_INSTALL = false;
    private static final boolean DEBUG_REMOVE = false;
    private static final boolean DEBUG_BROADCASTS = false;
    private static final boolean DEBUG_SHOW_INFO = false;
    private static final boolean DEBUG_PACKAGE_INFO = false;
    private static final boolean DEBUG_INTENT_MATCHING = false;
    private static final boolean DEBUG_PACKAGE_SCANNING = false;
    private static final boolean DEBUG_VERIFY = false;
    private static final boolean DEBUG_DEXOPT = false;
    private static final boolean DEBUG_ABI_SELECTION = false;

    static final boolean CLEAR_RUNTIME_PERMISSIONS_ON_UPGRADE = false;

    private static final int RADIO_UID = Process.PHONE_UID;
    private static final int LOG_UID = Process.LOG_UID;
    private static final int NFC_UID = Process.NFC_UID;
    private static final int BLUETOOTH_UID = Process.BLUETOOTH_UID;
    private static final int SHELL_UID = Process.SHELL_UID;

    // Cap the size of permission trees that 3rd party apps can define
    private static final int MAX_PERMISSION_TREE_FOOTPRINT = 32768;     // characters of text

    // Suffix used during package installation when copying/moving
    // package apks to install directory.
    private static final String INSTALL_PACKAGE_SUFFIX = "-";

    static final int SCAN_NO_DEX = 1<<1;
    static final int SCAN_FORCE_DEX = 1<<2;
    static final int SCAN_UPDATE_SIGNATURE = 1<<3;
    static final int SCAN_NEW_INSTALL = 1<<4;
    static final int SCAN_NO_PATHS = 1<<5;
    static final int SCAN_UPDATE_TIME = 1<<6;
    static final int SCAN_DEFER_DEX = 1<<7;
    static final int SCAN_BOOTING = 1<<8;
    static final int SCAN_TRUSTED_OVERLAY = 1<<9;
    static final int SCAN_DELETE_DATA_ON_FAILURES = 1<<10;
    static final int SCAN_REPLACING = 1<<11;
    static final int SCAN_REQUIRE_KNOWN = 1<<12;
    static final int SCAN_MOVE = 1<<13;
    static final int SCAN_INITIAL = 1<<14;

    static final int REMOVE_CHATTY = 1<<16;

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    /**
     * Timeout (in milliseconds) after which the watchdog should declare that
     * our handler thread is wedged.  The usual default for such things is one
     * minute but we sometimes do very lengthy I/O operations on this thread,
     * such as installing multi-gigabyte applications, so ours needs to be longer.
     */
    private static final long WATCHDOG_TIMEOUT = 1000*60*10;     // ten minutes

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
     * Whether verification is enabled by default.
     */
    private static final boolean DEFAULT_VERIFY_ENABLE = true;

    /**
     * The default maximum time to wait for the verification agent to return in
     * milliseconds.
     */
    private static final long DEFAULT_VERIFICATION_TIMEOUT = 10 * 1000;

    /**
     * The default response for package verification timeout.
     *
     * This can be either PackageManager.VERIFICATION_ALLOW or
     * PackageManager.VERIFICATION_REJECT.
     */
    private static final int DEFAULT_VERIFICATION_RESPONSE = PackageManager.VERIFICATION_ALLOW;

    static final String DEFAULT_CONTAINER_PACKAGE = "com.android.defcontainer";

    static final ComponentName DEFAULT_CONTAINER_COMPONENT = new ComponentName(
            DEFAULT_CONTAINER_PACKAGE,
            "com.android.defcontainer.DefaultContainerService");

    private static final String KILL_APP_REASON_GIDS_CHANGED =
            "permission grant or revoke changed gids";

    private static final String KILL_APP_REASON_PERMISSIONS_REVOKED =
            "permissions revoked";

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static final String VENDOR_OVERLAY_DIR = "/vendor/overlay";

    /** Permission grant: not grant the permission. */
    private static final int GRANT_DENIED = 1;

    /** Permission grant: grant the permission as an install permission. */
    private static final int GRANT_INSTALL = 2;

    /** Permission grant: grant the permission as an install permission for a legacy app. */
    private static final int GRANT_INSTALL_LEGACY = 3;

    /** Permission grant: grant the permission as a runtime one. */
    private static final int GRANT_RUNTIME = 4;

    /** Permission grant: grant as runtime a permission that was granted as an install time one. */
    private static final int GRANT_UPGRADE = 5;

    /** Canonical intent used to identify what counts as a "web browser" app */
    private static final Intent sBrowserIntent;
    static {
        sBrowserIntent = new Intent();
        sBrowserIntent.setAction(Intent.ACTION_VIEW);
        sBrowserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        sBrowserIntent.setData(Uri.parse("http:"));
    }

    final ServiceThread mHandlerThread;

    final PackageHandler mHandler;

    /**
     * Messages for {@link #mHandler} that need to wait for system ready before
     * being dispatched.
     */
    private ArrayList<Message> mPostSystemReadyMessages;

    final int mSdkVersion = Build.VERSION.SDK_INT;

    final Context mContext;
    final boolean mFactoryTest;
    final boolean mOnlyCore;
    final boolean mLazyDexOpt;
    final long mDexOptLRUThresholdInMills;
    final DisplayMetrics mMetrics;
    final int mDefParseFlags;
    final String[] mSeparateProcesses;
    final boolean mIsUpgrade;

    // This is where all application persistent data goes.
    final File mAppDataDir;

    // This is where all application persistent data goes for secondary users.
    final File mUserAppDataDir;

    /** The location for ASEC container files on internal storage. */
    final String mAsecInternalPath;

    // Used for privilege escalation. MUST NOT BE CALLED WITH mPackages
    // LOCK HELD.  Can be called with mInstallLock held.
    @GuardedBy("mInstallLock")
    final Installer mInstaller;

    /** Directory where installed third-party apps stored */
    final File mAppInstallDir;

    /**
     * Directory to which applications installed internally have their
     * 32 bit native libraries copied.
     */
    private File mAppLib32InstallDir;

    // Directory containing the private parts (e.g. code and non-resource assets) of forward-locked
    // apps.
    final File mDrmAppPrivateInstallDir;

    // ----------------------------------------------------------------

    // Lock for state used when installing and doing other long running
    // operations.  Methods that must be called with this lock held have
    // the suffix "LI".
    final Object mInstallLock = new Object();

    // ----------------------------------------------------------------

    // Keys are String (package name), values are Package.  This also serves
    // as the lock for the global state.  Methods that must be called with
    // this lock held have the prefix "LP".
    @GuardedBy("mPackages")
    final ArrayMap<String, PackageParser.Package> mPackages =
            new ArrayMap<String, PackageParser.Package>();

    // Tracks available target package names -> overlay package paths.
    final ArrayMap<String, ArrayMap<String, PackageParser.Package>> mOverlays =
        new ArrayMap<String, ArrayMap<String, PackageParser.Package>>();

    /**
     * Tracks new system packages [received in an OTA] that we expect to
     * find updated user-installed versions. Keys are package name, values
     * are package location.
     */
    final private ArrayMap<String, File> mExpectingBetter = new ArrayMap<>();

    /**
     * Tracks existing system packages prior to receiving an OTA. Keys are package name.
     */
    final private ArraySet<String> mExistingSystemPackages = new ArraySet<>();
    /**
     * Whether or not system app permissions should be promoted from install to runtime.
     */
    boolean mPromoteSystemApps;

    final Settings mSettings;
    boolean mRestoredSettings;

    // System configuration read by SystemConfig.
    final int[] mGlobalGids;
    final SparseArray<ArraySet<String>> mSystemPermissions;
    final ArrayMap<String, FeatureInfo> mAvailableFeatures;

    // If mac_permissions.xml was found for seinfo labeling.
    boolean mFoundPolicyFile;

    // If a recursive restorecon of /data/data/<pkg> is needed.
    private boolean mShouldRestoreconData = SELinuxMMAC.shouldRestorecon();

    public static final class SharedLibraryEntry {
        public final String path;
        public final String apk;

        SharedLibraryEntry(String _path, String _apk) {
            path = _path;
            apk = _apk;
        }
    }

    // Currently known shared libraries.
    final ArrayMap<String, SharedLibraryEntry> mSharedLibraries =
            new ArrayMap<String, SharedLibraryEntry>();

    // All available activities, for your resolving pleasure.
    final ActivityIntentResolver mActivities =
            new ActivityIntentResolver();

    // All available receivers, for your resolving pleasure.
    final ActivityIntentResolver mReceivers =
            new ActivityIntentResolver();

    // All available services, for your resolving pleasure.
    final ServiceIntentResolver mServices = new ServiceIntentResolver();

    // All available providers, for your resolving pleasure.
    final ProviderIntentResolver mProviders = new ProviderIntentResolver();

    // Mapping from provider base names (first directory in content URI codePath)
    // to the provider information.
    final ArrayMap<String, PackageParser.Provider> mProvidersByAuthority =
            new ArrayMap<String, PackageParser.Provider>();

    // Mapping from instrumentation class names to info about them.
    final ArrayMap<ComponentName, PackageParser.Instrumentation> mInstrumentation =
            new ArrayMap<ComponentName, PackageParser.Instrumentation>();

    // Mapping from permission names to info about them.
    final ArrayMap<String, PackageParser.PermissionGroup> mPermissionGroups =
            new ArrayMap<String, PackageParser.PermissionGroup>();

    // Packages whose data we have transfered into another package, thus
    // should no longer exist.
    final ArraySet<String> mTransferedPackages = new ArraySet<String>();

    // Broadcast actions that are only available to the system.
    final ArraySet<String> mProtectedBroadcasts = new ArraySet<String>();

    /** List of packages waiting for verification. */
    final SparseArray<PackageVerificationState> mPendingVerification
            = new SparseArray<PackageVerificationState>();

    /** Set of packages associated with each app op permission. */
    final ArrayMap<String, ArraySet<String>> mAppOpPermissionPackages = new ArrayMap<>();

    final PackageInstallerService mInstallerService;

    private final PackageDexOptimizer mPackageDexOptimizer;

    private AtomicInteger mNextMoveId = new AtomicInteger();
    private final MoveCallbacks mMoveCallbacks;

    private final OnPermissionChangeListeners mOnPermissionChangeListeners;

    // Cache of users who need badging.
    SparseBooleanArray mUserNeedsBadging = new SparseBooleanArray();

    /** Token for keys in mPendingVerification. */
    private int mPendingVerificationToken = 0;

    volatile boolean mSystemReady;
    volatile boolean mSafeMode;
    volatile boolean mHasSystemUidErrors;

    ApplicationInfo mAndroidApplication;
    final ActivityInfo mResolveActivity = new ActivityInfo();
    final ResolveInfo mResolveInfo = new ResolveInfo();
    ComponentName mResolveComponentName;
    PackageParser.Package mPlatformPackage;
    ComponentName mCustomResolverComponentName;

    boolean mResolverReplaced = false;

    private final ComponentName mIntentFilterVerifierComponent;
    private int mIntentFilterVerificationToken = 0;

    final SparseArray<IntentFilterVerificationState> mIntentFilterVerificationStates
            = new SparseArray<IntentFilterVerificationState>();

    final DefaultPermissionGrantPolicy mDefaultPermissionPolicy =
            new DefaultPermissionGrantPolicy(this);

    private static class IFVerificationParams {
        PackageParser.Package pkg;
        boolean replacing;
        int userId;
        int verifierUid;

        public IFVerificationParams(PackageParser.Package _pkg, boolean _replacing,
                int _userId, int _verifierUid) {
            pkg = _pkg;
            replacing = _replacing;
            userId = _userId;
            replacing = _replacing;
            verifierUid = _verifierUid;
        }
    }

    private interface IntentFilterVerifier<T extends IntentFilter> {
        boolean addOneIntentFilterVerification(int verifierId, int userId, int verificationId,
                                               T filter, String packageName);
        void startVerifications(int userId);
        void receiveVerificationResponse(int verificationId);
    }

    private class IntentVerifierProxy implements IntentFilterVerifier<ActivityIntentInfo> {
        private Context mContext;
        private ComponentName mIntentFilterVerifierComponent;
        private ArrayList<Integer> mCurrentIntentFilterVerifications = new ArrayList<Integer>();

        public IntentVerifierProxy(Context context, ComponentName verifierComponent) {
            mContext = context;
            mIntentFilterVerifierComponent = verifierComponent;
        }

        private String getDefaultScheme() {
            return IntentFilter.SCHEME_HTTPS;
        }

        @Override
        public void startVerifications(int userId) {
            // Launch verifications requests
            int count = mCurrentIntentFilterVerifications.size();
            for (int n=0; n<count; n++) {
                int verificationId = mCurrentIntentFilterVerifications.get(n);
                final IntentFilterVerificationState ivs =
                        mIntentFilterVerificationStates.get(verificationId);

                String packageName = ivs.getPackageName();

                ArrayList<PackageParser.ActivityIntentInfo> filters = ivs.getFilters();
                final int filterCount = filters.size();
                ArraySet<String> domainsSet = new ArraySet<>();
                for (int m=0; m<filterCount; m++) {
                    PackageParser.ActivityIntentInfo filter = filters.get(m);
                    domainsSet.addAll(filter.getHostsList());
                }
                ArrayList<String> domainsList = new ArrayList<>(domainsSet);
                synchronized (mPackages) {
                    if (mSettings.createIntentFilterVerificationIfNeededLPw(
                            packageName, domainsList) != null) {
                        scheduleWriteSettingsLocked();
                    }
                }
                sendVerificationRequest(userId, verificationId, ivs);
            }
            mCurrentIntentFilterVerifications.clear();
        }

        private void sendVerificationRequest(int userId, int verificationId,
                IntentFilterVerificationState ivs) {

            Intent verificationIntent = new Intent(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION);
            verificationIntent.putExtra(
                    PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID,
                    verificationId);
            verificationIntent.putExtra(
                    PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME,
                    getDefaultScheme());
            verificationIntent.putExtra(
                    PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS,
                    ivs.getHostsString());
            verificationIntent.putExtra(
                    PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME,
                    ivs.getPackageName());
            verificationIntent.setComponent(mIntentFilterVerifierComponent);
            verificationIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

            UserHandle user = new UserHandle(userId);
            mContext.sendBroadcastAsUser(verificationIntent, user);
            if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                    "Sending IntentFilter verification broadcast");
        }

        public void receiveVerificationResponse(int verificationId) {
            IntentFilterVerificationState ivs = mIntentFilterVerificationStates.get(verificationId);

            final boolean verified = ivs.isVerified();

            ArrayList<PackageParser.ActivityIntentInfo> filters = ivs.getFilters();
            final int count = filters.size();
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.i(TAG, "Received verification response " + verificationId
                        + " for " + count + " filters, verified=" + verified);
            }
            for (int n=0; n<count; n++) {
                PackageParser.ActivityIntentInfo filter = filters.get(n);
                filter.setVerified(verified);

                if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG, "IntentFilter " + filter.toString()
                        + " verified with result:" + verified + " and hosts:"
                        + ivs.getHostsString());
            }

            mIntentFilterVerificationStates.remove(verificationId);

            final String packageName = ivs.getPackageName();
            IntentFilterVerificationInfo ivi = null;

            synchronized (mPackages) {
                ivi = mSettings.getIntentFilterVerificationLPr(packageName);
            }
            if (ivi == null) {
                Slog.w(TAG, "IntentFilterVerificationInfo not found for verificationId:"
                        + verificationId + " packageName:" + packageName);
                return;
            }
            if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                    "Updating IntentFilterVerificationInfo for package " + packageName
                            +" verificationId:" + verificationId);

            synchronized (mPackages) {
                if (verified) {
                    ivi.setStatus(INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS);
                } else {
                    ivi.setStatus(INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK);
                }
                scheduleWriteSettingsLocked();

                final int userId = ivs.getUserId();
                if (userId != UserHandle.USER_ALL) {
                    final int userStatus =
                            mSettings.getIntentFilterVerificationStatusLPr(packageName, userId);

                    int updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
                    boolean needUpdate = false;

                    // We cannot override the STATUS_ALWAYS / STATUS_NEVER states if they have
                    // already been set by the User thru the Disambiguation dialog
                    switch (userStatus) {
                        case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED:
                            if (verified) {
                                updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
                            } else {
                                updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
                            }
                            needUpdate = true;
                            break;

                        case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK:
                            if (verified) {
                                updatedStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
                                needUpdate = true;
                            }
                            break;

                        default:
                            // Nothing to do
                    }

                    if (needUpdate) {
                        mSettings.updateIntentFilterVerificationStatusLPw(
                                packageName, updatedStatus, userId);
                        scheduleWritePackageRestrictionsLocked(userId);
                    }
                }
            }
        }

        @Override
        public boolean addOneIntentFilterVerification(int verifierUid, int userId, int verificationId,
                    ActivityIntentInfo filter, String packageName) {
            if (!hasValidDomains(filter)) {
                return false;
            }
            IntentFilterVerificationState ivs = mIntentFilterVerificationStates.get(verificationId);
            if (ivs == null) {
                ivs = createDomainVerificationState(verifierUid, userId, verificationId,
                        packageName);
            }
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(TAG, "Adding verification filter for " + packageName + " : " + filter);
            }
            ivs.addFilter(filter);
            return true;
        }

        private IntentFilterVerificationState createDomainVerificationState(int verifierUid,
                int userId, int verificationId, String packageName) {
            IntentFilterVerificationState ivs = new IntentFilterVerificationState(
                    verifierUid, userId, packageName);
            ivs.setPendingState();
            synchronized (mPackages) {
                mIntentFilterVerificationStates.append(verificationId, ivs);
                mCurrentIntentFilterVerifications.add(verificationId);
            }
            return ivs;
        }
    }

    private static boolean hasValidDomains(ActivityIntentInfo filter) {
        return filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                && (filter.hasDataScheme(IntentFilter.SCHEME_HTTP) ||
                        filter.hasDataScheme(IntentFilter.SCHEME_HTTPS));
    }

    private IntentFilterVerifier mIntentFilterVerifier;

    // Set of pending broadcasts for aggregating enable/disable of components.
    static class PendingPackageBroadcasts {
        // for each user id, a map of <package name -> components within that package>
        final SparseArray<ArrayMap<String, ArrayList<String>>> mUidMap;

        public PendingPackageBroadcasts() {
            mUidMap = new SparseArray<ArrayMap<String, ArrayList<String>>>(2);
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
                map = new ArrayMap<String, ArrayList<String>>();
                mUidMap.put(userId, map);
            }
            return map;
        }
    }
    final PendingPackageBroadcasts mPendingBroadcasts = new PendingPackageBroadcasts();

    // Service Connection to remote media container service to copy
    // package uri's from external media onto secure containers
    // or internal storage.
    private IMediaContainerService mContainerService = null;

    static final int SEND_PENDING_BROADCAST = 1;
    static final int MCS_BOUND = 3;
    static final int END_COPY = 4;
    static final int INIT_COPY = 5;
    static final int MCS_UNBIND = 6;
    static final int START_CLEANING_PACKAGE = 7;
    static final int FIND_INSTALL_LOC = 8;
    static final int POST_INSTALL = 9;
    static final int MCS_RECONNECT = 10;
    static final int MCS_GIVE_UP = 11;
    static final int UPDATED_MEDIA_STATUS = 12;
    static final int WRITE_SETTINGS = 13;
    static final int WRITE_PACKAGE_RESTRICTIONS = 14;
    static final int PACKAGE_VERIFIED = 15;
    static final int CHECK_PENDING_VERIFICATION = 16;
    static final int START_INTENT_FILTER_VERIFICATIONS = 17;
    static final int INTENT_FILTER_VERIFIED = 18;

    static final int WRITE_SETTINGS_DELAY = 10*1000;  // 10 seconds

    // Delay time in millisecs
    static final int BROADCAST_DELAY = 10 * 1000;

    static UserManagerService sUserManager;

    // Stores a list of users whose package restrictions file needs to be updated
    private ArraySet<Integer> mDirtyUsers = new ArraySet<Integer>();

    final private DefaultContainerConnection mDefContainerConn =
            new DefaultContainerConnection();
    class DefaultContainerConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "onServiceConnected");
            IMediaContainerService imcs =
                IMediaContainerService.Stub.asInterface(service);
            mHandler.sendMessage(mHandler.obtainMessage(MCS_BOUND, imcs));
        }

        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "onServiceDisconnected");
        }
    }

    // Recordkeeping of restore-after-install operations that are currently in flight
    // between the Package Manager and the Backup Manager
    class PostInstallData {
        public InstallArgs args;
        public PackageInstalledInfo res;

        PostInstallData(InstallArgs _a, PackageInstalledInfo _r) {
            args = _a;
            res = _r;
        }
    }

    final SparseArray<PostInstallData> mRunningInstalls = new SparseArray<PostInstallData>();
    int mNextInstallToken = 1;  // nonzero; will be wrapped back to 1 when ++ overflows

    // XML tags for backup/restore of various bits of state
    private static final String TAG_PREFERRED_BACKUP = "pa";
    private static final String TAG_DEFAULT_APPS = "da";
    private static final String TAG_INTENT_FILTER_VERIFICATION = "iv";

    final String mRequiredVerifierPackage;
    final String mRequiredInstallerPackage;

    private final PackageUsage mPackageUsage = new PackageUsage();

    private class PackageUsage {
        private static final int WRITE_INTERVAL
            = (DEBUG_DEXOPT) ? 0 : 30*60*1000; // 30m in ms

        private final Object mFileLock = new Object();
        private final AtomicLong mLastWritten = new AtomicLong(0);
        private final AtomicBoolean mBackgroundWriteRunning = new AtomicBoolean(false);

        private boolean mIsHistoricalPackageUsageAvailable = true;

        boolean isHistoricalPackageUsageAvailable() {
            return mIsHistoricalPackageUsageAvailable;
        }

        void write(boolean force) {
            if (force) {
                writeInternal();
                return;
            }
            if (SystemClock.elapsedRealtime() - mLastWritten.get() < WRITE_INTERVAL
                && !DEBUG_DEXOPT) {
                return;
            }
            if (mBackgroundWriteRunning.compareAndSet(false, true)) {
                new Thread("PackageUsage_DiskWriter") {
                    @Override
                    public void run() {
                        try {
                            writeInternal();
                        } finally {
                            mBackgroundWriteRunning.set(false);
                        }
                    }
                }.start();
            }
        }

        private void writeInternal() {
            synchronized (mPackages) {
                synchronized (mFileLock) {
                    AtomicFile file = getFile();
                    FileOutputStream f = null;
                    try {
                        f = file.startWrite();
                        BufferedOutputStream out = new BufferedOutputStream(f);
                        FileUtils.setPermissions(file.getBaseFile().getPath(), 0640, SYSTEM_UID, PACKAGE_INFO_GID);
                        StringBuilder sb = new StringBuilder();
                        for (PackageParser.Package pkg : mPackages.values()) {
                            if (pkg.mLastPackageUsageTimeInMills == 0) {
                                continue;
                            }
                            sb.setLength(0);
                            sb.append(pkg.packageName);
                            sb.append(' ');
                            sb.append((long)pkg.mLastPackageUsageTimeInMills);
                            sb.append('\n');
                            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
                        }
                        out.flush();
                        file.finishWrite(f);
                    } catch (IOException e) {
                        if (f != null) {
                            file.failWrite(f);
                        }
                        Log.e(TAG, "Failed to write package usage times", e);
                    }
                }
            }
            mLastWritten.set(SystemClock.elapsedRealtime());
        }

        void readLP() {
            synchronized (mFileLock) {
                AtomicFile file = getFile();
                BufferedInputStream in = null;
                try {
                    in = new BufferedInputStream(file.openRead());
                    StringBuffer sb = new StringBuffer();
                    while (true) {
                        String packageName = readToken(in, sb, ' ');
                        if (packageName == null) {
                            break;
                        }
                        String timeInMillisString = readToken(in, sb, '\n');
                        if (timeInMillisString == null) {
                            throw new IOException("Failed to find last usage time for package "
                                                  + packageName);
                        }
                        PackageParser.Package pkg = mPackages.get(packageName);
                        if (pkg == null) {
                            continue;
                        }
                        long timeInMillis;
                        try {
                            timeInMillis = Long.parseLong(timeInMillisString.toString());
                        } catch (NumberFormatException e) {
                            throw new IOException("Failed to parse " + timeInMillisString
                                                  + " as a long.", e);
                        }
                        pkg.mLastPackageUsageTimeInMills = timeInMillis;
                    }
                } catch (FileNotFoundException expected) {
                    mIsHistoricalPackageUsageAvailable = false;
                } catch (IOException e) {
                    Log.w(TAG, "Failed to read package usage times", e);
                } finally {
                    IoUtils.closeQuietly(in);
                }
            }
            mLastWritten.set(SystemClock.elapsedRealtime());
        }

        private String readToken(InputStream in, StringBuffer sb, char endOfToken)
                throws IOException {
            sb.setLength(0);
            while (true) {
                int ch = in.read();
                if (ch == -1) {
                    if (sb.length() == 0) {
                        return null;
                    }
                    throw new IOException("Unexpected EOF");
                }
                if (ch == endOfToken) {
                    return sb.toString();
                }
                sb.append((char)ch);
            }
        }

        private AtomicFile getFile() {
            File dataDir = Environment.getDataDirectory();
            File systemDir = new File(dataDir, "system");
            File fname = new File(systemDir, "package-usage.list");
            return new AtomicFile(fname);
        }
    }

    class PackageHandler extends Handler {
        private boolean mBound = false;
        final ArrayList<HandlerParams> mPendingInstalls =
            new ArrayList<HandlerParams>();

        private boolean connectToService() {
            if (DEBUG_SD_INSTALL) Log.i(TAG, "Trying to bind to" +
                    " DefaultContainerService");
            Intent service = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            if (mContext.bindServiceAsUser(service, mDefContainerConn,
                    Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                mBound = true;
                return true;
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            return false;
        }

        private void disconnectService() {
            mContainerService = null;
            mBound = false;
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            mContext.unbindService(mDefContainerConn);
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        }

        PackageHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            try {
                doHandleMessage(msg);
            } finally {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            }
        }

        void doHandleMessage(Message msg) {
            switch (msg.what) {
                case INIT_COPY: {
                    HandlerParams params = (HandlerParams) msg.obj;
                    int idx = mPendingInstalls.size();
                    if (DEBUG_INSTALL) Slog.i(TAG, "init_copy idx=" + idx + ": " + params);
                    // If a bind was already initiated we dont really
                    // need to do anything. The pending install
                    // will be processed later on.
                    if (!mBound) {
                        // If this is the only one pending we might
                        // have to bind to the service again.
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            params.serviceError();
                            return;
                        } else {
                            // Once we bind to the service, the first
                            // pending request will be processed.
                            mPendingInstalls.add(idx, params);
                        }
                    } else {
                        mPendingInstalls.add(idx, params);
                        // Already bound to the service. Just make
                        // sure we trigger off processing the first request.
                        if (idx == 0) {
                            mHandler.sendEmptyMessage(MCS_BOUND);
                        }
                    }
                    break;
                }
                case MCS_BOUND: {
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_bound");
                    if (msg.obj != null) {
                        mContainerService = (IMediaContainerService) msg.obj;
                    }
                    if (mContainerService == null) {
                        if (!mBound) {
                            // Something seriously wrong since we are not bound and we are not
                            // waiting for connection. Bail out.
                            Slog.e(TAG, "Cannot bind to media container service");
                            for (HandlerParams params : mPendingInstalls) {
                                // Indicate service bind error
                                params.serviceError();
                            }
                            mPendingInstalls.clear();
                        } else {
                            Slog.w(TAG, "Waiting to connect to media container service");
                        }
                    } else if (mPendingInstalls.size() > 0) {
                        HandlerParams params = mPendingInstalls.get(0);
                        if (params != null) {
                            if (params.startCopy()) {
                                // We are done...  look for more work or to
                                // go idle.
                                if (DEBUG_SD_INSTALL) Log.i(TAG,
                                        "Checking for more work or unbind...");
                                // Delete pending install
                                if (mPendingInstalls.size() > 0) {
                                    mPendingInstalls.remove(0);
                                }
                                if (mPendingInstalls.size() == 0) {
                                    if (mBound) {
                                        if (DEBUG_SD_INSTALL) Log.i(TAG,
                                                "Posting delayed MCS_UNBIND");
                                        removeMessages(MCS_UNBIND);
                                        Message ubmsg = obtainMessage(MCS_UNBIND);
                                        // Unbind after a little delay, to avoid
                                        // continual thrashing.
                                        sendMessageDelayed(ubmsg, 10000);
                                    }
                                } else {
                                    // There are more pending requests in queue.
                                    // Just post MCS_BOUND message to trigger processing
                                    // of next pending install.
                                    if (DEBUG_SD_INSTALL) Log.i(TAG,
                                            "Posting MCS_BOUND for next work");
                                    mHandler.sendEmptyMessage(MCS_BOUND);
                                }
                            }
                        }
                    } else {
                        // Should never happen ideally.
                        Slog.w(TAG, "Empty queue");
                    }
                    break;
                }
                case MCS_RECONNECT: {
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_reconnect");
                    if (mPendingInstalls.size() > 0) {
                        if (mBound) {
                            disconnectService();
                        }
                        if (!connectToService()) {
                            Slog.e(TAG, "Failed to bind to media container service");
                            for (HandlerParams params : mPendingInstalls) {
                                // Indicate service bind error
                                params.serviceError();
                            }
                            mPendingInstalls.clear();
                        }
                    }
                    break;
                }
                case MCS_UNBIND: {
                    // If there is no actual work left, then time to unbind.
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_unbind");

                    if (mPendingInstalls.size() == 0 && mPendingVerification.size() == 0) {
                        if (mBound) {
                            if (DEBUG_INSTALL) Slog.i(TAG, "calling disconnectService()");

                            disconnectService();
                        }
                    } else if (mPendingInstalls.size() > 0) {
                        // There are more pending requests in queue.
                        // Just post MCS_BOUND message to trigger processing
                        // of next pending install.
                        mHandler.sendEmptyMessage(MCS_BOUND);
                    }

                    break;
                }
                case MCS_GIVE_UP: {
                    if (DEBUG_INSTALL) Slog.i(TAG, "mcs_giveup too many retries");
                    mPendingInstalls.remove(0);
                    break;
                }
                case SEND_PENDING_BROADCAST: {
                    String packages[];
                    ArrayList<String> components[];
                    int size = 0;
                    int uids[];
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        if (mPendingBroadcasts == null) {
                            return;
                        }
                        size = mPendingBroadcasts.size();
                        if (size <= 0) {
                            // Nothing to be done. Just return
                            return;
                        }
                        packages = new String[size];
                        components = new ArrayList[size];
                        uids = new int[size];
                        int i = 0;  // filling out the above arrays

                        for (int n = 0; n < mPendingBroadcasts.userIdCount(); n++) {
                            int packageUserId = mPendingBroadcasts.userIdAt(n);
                            Iterator<Map.Entry<String, ArrayList<String>>> it
                                    = mPendingBroadcasts.packagesForUserId(packageUserId)
                                            .entrySet().iterator();
                            while (it.hasNext() && i < size) {
                                Map.Entry<String, ArrayList<String>> ent = it.next();
                                packages[i] = ent.getKey();
                                components[i] = ent.getValue();
                                PackageSetting ps = mSettings.mPackages.get(ent.getKey());
                                uids[i] = (ps != null)
                                        ? UserHandle.getUid(packageUserId, ps.appId)
                                        : -1;
                                i++;
                            }
                        }
                        size = i;
                        mPendingBroadcasts.clear();
                    }
                    // Send broadcasts
                    for (int i = 0; i < size; i++) {
                        sendPackageChangedBroadcast(packages[i], true, components[i], uids[i]);
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
                case START_CLEANING_PACKAGE: {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    final String packageName = (String)msg.obj;
                    final int userId = msg.arg1;
                    final boolean andCode = msg.arg2 != 0;
                    synchronized (mPackages) {
                        if (userId == UserHandle.USER_ALL) {
                            int[] users = sUserManager.getUserIds();
                            for (int user : users) {
                                mSettings.addPackageToCleanLPw(
                                        new PackageCleanItem(user, packageName, andCode));
                            }
                        } else {
                            mSettings.addPackageToCleanLPw(
                                    new PackageCleanItem(userId, packageName, andCode));
                        }
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    startCleaningPackages();
                } break;
                case POST_INSTALL: {
                    if (DEBUG_INSTALL) Log.v(TAG, "Handling post-install for " + msg.arg1);
                    PostInstallData data = mRunningInstalls.get(msg.arg1);
                    mRunningInstalls.delete(msg.arg1);
                    boolean deleteOld = false;

                    if (data != null) {
                        InstallArgs args = data.args;
                        PackageInstalledInfo res = data.res;

                        if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                            final String packageName = res.pkg.applicationInfo.packageName;
                            res.removedInfo.sendBroadcast(false, true, false);
                            Bundle extras = new Bundle(1);
                            extras.putInt(Intent.EXTRA_UID, res.uid);

                            // Now that we successfully installed the package, grant runtime
                            // permissions if requested before broadcasting the install.
                            if ((args.installFlags
                                    & PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS) != 0) {
                                grantRequestedRuntimePermissions(res.pkg, args.user.getIdentifier(),
                                        args.installGrantPermissions);
                            }

                            // Determine the set of users who are adding this
                            // package for the first time vs. those who are seeing
                            // an update.
                            int[] firstUsers;
                            int[] updateUsers = new int[0];
                            if (res.origUsers == null || res.origUsers.length == 0) {
                                firstUsers = res.newUsers;
                            } else {
                                firstUsers = new int[0];
                                for (int i=0; i<res.newUsers.length; i++) {
                                    int user = res.newUsers[i];
                                    boolean isNew = true;
                                    for (int j=0; j<res.origUsers.length; j++) {
                                        if (res.origUsers[j] == user) {
                                            isNew = false;
                                            break;
                                        }
                                    }
                                    if (isNew) {
                                        int[] newFirst = new int[firstUsers.length+1];
                                        System.arraycopy(firstUsers, 0, newFirst, 0,
                                                firstUsers.length);
                                        newFirst[firstUsers.length] = user;
                                        firstUsers = newFirst;
                                    } else {
                                        int[] newUpdate = new int[updateUsers.length+1];
                                        System.arraycopy(updateUsers, 0, newUpdate, 0,
                                                updateUsers.length);
                                        newUpdate[updateUsers.length] = user;
                                        updateUsers = newUpdate;
                                    }
                                }
                            }
                            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                                    packageName, extras, null, null, firstUsers);
                            final boolean update = res.removedInfo.removedPackage != null;
                            if (update) {
                                extras.putBoolean(Intent.EXTRA_REPLACING, true);
                            }
                            sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                                    packageName, extras, null, null, updateUsers);
                            if (update) {
                                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                                        packageName, extras, null, null, updateUsers);
                                sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED,
                                        null, null, packageName, null, updateUsers);

                                // treat asec-hosted packages like removable media on upgrade
                                if (res.pkg.isForwardLocked() || isExternal(res.pkg)) {
                                    if (DEBUG_INSTALL) {
                                        Slog.i(TAG, "upgrading pkg " + res.pkg
                                                + " is ASEC-hosted -> AVAILABLE");
                                    }
                                    int[] uidArray = new int[] { res.pkg.applicationInfo.uid };
                                    ArrayList<String> pkgList = new ArrayList<String>(1);
                                    pkgList.add(packageName);
                                    sendResourcesChangedBroadcast(true, true,
                                            pkgList,uidArray, null);
                                }
                            }
                            if (res.removedInfo.args != null) {
                                // Remove the replaced package's older resources safely now
                                deleteOld = true;
                            }

                            // If this app is a browser and it's newly-installed for some
                            // users, clear any default-browser state in those users
                            if (firstUsers.length > 0) {
                                // the app's nature doesn't depend on the user, so we can just
                                // check its browser nature in any user and generalize.
                                if (packageIsBrowser(packageName, firstUsers[0])) {
                                    synchronized (mPackages) {
                                        for (int userId : firstUsers) {
                                            mSettings.setDefaultBrowserPackageNameLPw(null, userId);
                                        }
                                    }
                                }
                            }
                            // Log current value of "unknown sources" setting
                            EventLog.writeEvent(EventLogTags.UNKNOWN_SOURCES_ENABLED,
                                getUnknownSourcesSettings());
                        }
                        // Force a gc to clear up things
                        Runtime.getRuntime().gc();
                        // We delete after a gc for applications  on sdcard.
                        if (deleteOld) {
                            synchronized (mInstallLock) {
                                res.removedInfo.args.doPostDeleteLI(true);
                            }
                        }
                        if (args.observer != null) {
                            try {
                                Bundle extras = extrasForInstallResult(res);
                                args.observer.onPackageInstalled(res.name, res.returnCode,
                                        res.returnMsg, extras);
                            } catch (RemoteException e) {
                                Slog.i(TAG, "Observer no longer exists.");
                            }
                        }
                    } else {
                        Slog.e(TAG, "Bogus post-install token " + msg.arg1);
                    }
                } break;
                case UPDATED_MEDIA_STATUS: {
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "Got message UPDATED_MEDIA_STATUS");
                    boolean reportStatus = msg.arg1 == 1;
                    boolean doGc = msg.arg2 == 1;
                    if (DEBUG_SD_INSTALL) Log.i(TAG, "reportStatus=" + reportStatus + ", doGc = " + doGc);
                    if (doGc) {
                        // Force a gc to clear up stale containers.
                        Runtime.getRuntime().gc();
                    }
                    if (msg.obj != null) {
                        @SuppressWarnings("unchecked")
                        Set<AsecInstallArgs> args = (Set<AsecInstallArgs>) msg.obj;
                        if (DEBUG_SD_INSTALL) Log.i(TAG, "Unloading all containers");
                        // Unload containers
                        unloadAllContainers(args);
                    }
                    if (reportStatus) {
                        try {
                            if (DEBUG_SD_INSTALL) Log.i(TAG, "Invoking MountService call back");
                            PackageHelper.getMountService().finishMediaUpdate();
                        } catch (RemoteException e) {
                            Log.e(TAG, "MountService not running?");
                        }
                    }
                } break;
                case WRITE_SETTINGS: {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        removeMessages(WRITE_SETTINGS);
                        removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                        mSettings.writeLPr();
                        mDirtyUsers.clear();
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } break;
                case WRITE_PACKAGE_RESTRICTIONS: {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mPackages) {
                        removeMessages(WRITE_PACKAGE_RESTRICTIONS);
                        for (int userId : mDirtyUsers) {
                            mSettings.writePackageRestrictionsLPr(userId);
                        }
                        mDirtyUsers.clear();
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                } break;
                case CHECK_PENDING_VERIFICATION: {
                    final int verificationId = msg.arg1;
                    final PackageVerificationState state = mPendingVerification.get(verificationId);

                    if ((state != null) && !state.timeoutExtended()) {
                        final InstallArgs args = state.getInstallArgs();
                        final Uri originUri = Uri.fromFile(args.origin.resolvedFile);

                        Slog.i(TAG, "Verification timed out for " + originUri);
                        mPendingVerification.remove(verificationId);

                        int ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;

                        if (getDefaultVerificationResponse() == PackageManager.VERIFICATION_ALLOW) {
                            Slog.i(TAG, "Continuing with installation of " + originUri);
                            state.setVerifierResponse(Binder.getCallingUid(),
                                    PackageManager.VERIFICATION_ALLOW_WITHOUT_SUFFICIENT);
                            broadcastPackageVerified(verificationId, originUri,
                                    PackageManager.VERIFICATION_ALLOW,
                                    state.getInstallArgs().getUser());
                            try {
                                ret = args.copyApk(mContainerService, true);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Could not contact the ContainerService");
                            }
                        } else {
                            broadcastPackageVerified(verificationId, originUri,
                                    PackageManager.VERIFICATION_REJECT,
                                    state.getInstallArgs().getUser());
                        }

                        processPendingInstall(args, ret);
                        mHandler.sendEmptyMessage(MCS_UNBIND);
                    }
                    break;
                }
                case PACKAGE_VERIFIED: {
                    final int verificationId = msg.arg1;

                    final PackageVerificationState state = mPendingVerification.get(verificationId);
                    if (state == null) {
                        Slog.w(TAG, "Invalid verification token " + verificationId + " received");
                        break;
                    }

                    final PackageVerificationResponse response = (PackageVerificationResponse) msg.obj;

                    state.setVerifierResponse(response.callerUid, response.code);

                    if (state.isVerificationComplete()) {
                        mPendingVerification.remove(verificationId);

                        final InstallArgs args = state.getInstallArgs();
                        final Uri originUri = Uri.fromFile(args.origin.resolvedFile);

                        int ret;
                        if (state.isInstallAllowed()) {
                            ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                            broadcastPackageVerified(verificationId, originUri,
                                    response.code, state.getInstallArgs().getUser());
                            try {
                                ret = args.copyApk(mContainerService, true);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "Could not contact the ContainerService");
                            }
                        } else {
                            ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;
                        }

                        processPendingInstall(args, ret);

                        mHandler.sendEmptyMessage(MCS_UNBIND);
                    }

                    break;
                }
                case START_INTENT_FILTER_VERIFICATIONS: {
                    IFVerificationParams params = (IFVerificationParams) msg.obj;
                    verifyIntentFiltersIfNeeded(params.userId, params.verifierUid,
                            params.replacing, params.pkg);
                    break;
                }
                case INTENT_FILTER_VERIFIED: {
                    final int verificationId = msg.arg1;

                    final IntentFilterVerificationState state = mIntentFilterVerificationStates.get(
                            verificationId);
                    if (state == null) {
                        Slog.w(TAG, "Invalid IntentFilter verification token "
                                + verificationId + " received");
                        break;
                    }

                    final int userId = state.getUserId();

                    if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                            "Processing IntentFilter verification with token:"
                            + verificationId + " and userId:" + userId);

                    final IntentFilterVerificationResponse response =
                            (IntentFilterVerificationResponse) msg.obj;

                    state.setVerifierResponse(response.callerUid, response.code);

                    if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                            "IntentFilter verification with token:" + verificationId
                            + " and userId:" + userId
                            + " is settings verifier response with response code:"
                            + response.code);

                    if (response.code == PackageManager.INTENT_FILTER_VERIFICATION_FAILURE) {
                        if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG, "Domains failing verification: "
                                + response.getFailedDomainsString());
                    }

                    if (state.isVerificationComplete()) {
                        mIntentFilterVerifier.receiveVerificationResponse(verificationId);
                    } else {
                        if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                                "IntentFilter verification with token:" + verificationId
                                + " was not said to be complete");
                    }

                    break;
                }
            }
        }
    }

    private StorageEventListener mStorageListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            if (vol.type == VolumeInfo.TYPE_PRIVATE) {
                if (vol.state == VolumeInfo.STATE_MOUNTED) {
                    final String volumeUuid = vol.getFsUuid();

                    // Clean up any users or apps that were removed or recreated
                    // while this volume was missing
                    reconcileUsers(volumeUuid);
                    reconcileApps(volumeUuid);

                    // Clean up any install sessions that expired or were
                    // cancelled while this volume was missing
                    mInstallerService.onPrivateVolumeMounted(volumeUuid);

                    loadPrivatePackages(vol);

                } else if (vol.state == VolumeInfo.STATE_EJECTING) {
                    unloadPrivatePackages(vol);
                }
            }

            if (vol.type == VolumeInfo.TYPE_PUBLIC && vol.isPrimary()) {
                if (vol.state == VolumeInfo.STATE_MOUNTED) {
                    updateExternalMediaStatus(true, false);
                } else if (vol.state == VolumeInfo.STATE_EJECTING) {
                    updateExternalMediaStatus(false, false);
                }
            }
        }

        @Override
        public void onVolumeForgotten(String fsUuid) {
            if (TextUtils.isEmpty(fsUuid)) {
                Slog.w(TAG, "Forgetting internal storage is probably a mistake; ignoring");
                return;
            }

            // Remove any apps installed on the forgotten volume
            synchronized (mPackages) {
                final List<PackageSetting> packages = mSettings.getVolumePackagesLPr(fsUuid);
                for (PackageSetting ps : packages) {
                    Slog.d(TAG, "Destroying " + ps.name + " because volume was forgotten");
                    deletePackage(ps.name, new LegacyPackageDeleteObserver(null).getBinder(),
                            UserHandle.USER_OWNER, PackageManager.DELETE_ALL_USERS);
                }

                mSettings.onVolumeForgotten(fsUuid);
                mSettings.writeLPr();
            }
        }
    };

    private void grantRequestedRuntimePermissions(PackageParser.Package pkg, int userId,
            String[] grantedPermissions) {
        if (userId >= UserHandle.USER_OWNER) {
            grantRequestedRuntimePermissionsForUser(pkg, userId, grantedPermissions);
        } else if (userId == UserHandle.USER_ALL) {
            final int[] userIds;
            synchronized (mPackages) {
                userIds = UserManagerService.getInstance().getUserIds();
            }
            for (int someUserId : userIds) {
                grantRequestedRuntimePermissionsForUser(pkg, someUserId, grantedPermissions);
            }
        }

        // We could have touched GID membership, so flush out packages.list
        synchronized (mPackages) {
            mSettings.writePackageListLPr();
        }
    }

    private void grantRequestedRuntimePermissionsForUser(PackageParser.Package pkg, int userId,
            String[] grantedPermissions) {
        SettingBase sb = (SettingBase) pkg.mExtras;
        if (sb == null) {
            return;
        }

        PermissionsState permissionsState = sb.getPermissionsState();

        for (String permission : pkg.requestedPermissions) {
            BasePermission bp = mSettings.mPermissions.get(permission);
            if (bp != null && bp.isRuntime() && (grantedPermissions == null
                    || ArrayUtils.contains(grantedPermissions, permission))) {
                permissionsState.grantRuntimePermission(bp, userId);
            }
        }
    }

    Bundle extrasForInstallResult(PackageInstalledInfo res) {
        Bundle extras = null;
        switch (res.returnCode) {
            case PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION: {
                extras = new Bundle();
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PERMISSION,
                        res.origPermission);
                extras.putString(PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE,
                        res.origPackage);
                break;
            }
            case PackageManager.INSTALL_SUCCEEDED: {
                extras = new Bundle();
                extras.putBoolean(Intent.EXTRA_REPLACING,
                        res.removedInfo != null && res.removedInfo.removedPackage != null);
                break;
            }
        }
        return extras;
    }

    void scheduleWriteSettingsLocked() {
        if (!mHandler.hasMessages(WRITE_SETTINGS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_SETTINGS, WRITE_SETTINGS_DELAY);
        }
    }

    void scheduleWritePackageRestrictionsLocked(int userId) {
        if (!sUserManager.exists(userId)) return;
        mDirtyUsers.add(userId);
        if (!mHandler.hasMessages(WRITE_PACKAGE_RESTRICTIONS)) {
            mHandler.sendEmptyMessageDelayed(WRITE_PACKAGE_RESTRICTIONS, WRITE_SETTINGS_DELAY);
        }
    }

    public static PackageManagerService main(Context context, Installer installer,
            boolean factoryTest, boolean onlyCore) {
        PackageManagerService m = new PackageManagerService(context, installer,
                factoryTest, onlyCore);
        ServiceManager.addService("package", m);
        return m;
    }

    static String[] splitString(String str, char sep) {
        int count = 1;
        int i = 0;
        while ((i=str.indexOf(sep, i)) >= 0) {
            count++;
            i++;
        }

        String[] res = new String[count];
        i=0;
        count = 0;
        int lastI=0;
        while ((i=str.indexOf(sep, i)) >= 0) {
            res[count] = str.substring(lastI, i);
            count++;
            i++;
            lastI = i;
        }
        res[count] = str.substring(lastI, str.length());
        return res;
    }

    private static void getDefaultDisplayMetrics(Context context, DisplayMetrics metrics) {
        DisplayManager displayManager = (DisplayManager) context.getSystemService(
                Context.DISPLAY_SERVICE);
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getMetrics(metrics);
    }

    public PackageManagerService(Context context, Installer installer,
            boolean factoryTest, boolean onlyCore) {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_START,
                SystemClock.uptimeMillis());

        if (mSdkVersion <= 0) {
            Slog.w(TAG, "**** ro.build.version.sdk not set!");
        }

        mContext = context;
        mFactoryTest = factoryTest;
        mOnlyCore = onlyCore;
        mLazyDexOpt = "eng".equals(SystemProperties.get("ro.build.type"));
        mMetrics = new DisplayMetrics();
        mSettings = new Settings(mPackages);
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

        // TODO: add a property to control this?
        long dexOptLRUThresholdInMinutes;
        if (mLazyDexOpt) {
            dexOptLRUThresholdInMinutes = 30; // only last 30 minutes of apps for eng builds.
        } else {
            dexOptLRUThresholdInMinutes = 7 * 24 * 60; // apps used in the 7 days for users.
        }
        mDexOptLRUThresholdInMills = dexOptLRUThresholdInMinutes * 60 * 1000;

        String separateProcesses = SystemProperties.get("debug.separate_processes");
        if (separateProcesses != null && separateProcesses.length() > 0) {
            if ("*".equals(separateProcesses)) {
                mDefParseFlags = PackageParser.PARSE_IGNORE_PROCESSES;
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

        mInstaller = installer;
        mPackageDexOptimizer = new PackageDexOptimizer(this);
        mMoveCallbacks = new MoveCallbacks(FgThread.get().getLooper());

        mOnPermissionChangeListeners = new OnPermissionChangeListeners(
                FgThread.get().getLooper());

        getDefaultDisplayMetrics(context, mMetrics);

        SystemConfig systemConfig = SystemConfig.getInstance();
        mGlobalGids = systemConfig.getGlobalGids();
        mSystemPermissions = systemConfig.getSystemPermissions();
        mAvailableFeatures = systemConfig.getAvailableFeatures();

        synchronized (mInstallLock) {
        // writer
        synchronized (mPackages) {
            mHandlerThread = new ServiceThread(TAG,
                    Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
            mHandlerThread.start();
            mHandler = new PackageHandler(mHandlerThread.getLooper());
            Watchdog.getInstance().addThread(mHandler, WATCHDOG_TIMEOUT);

            File dataDir = Environment.getDataDirectory();
            mAppDataDir = new File(dataDir, "data");
            mAppInstallDir = new File(dataDir, "app");
            mAppLib32InstallDir = new File(dataDir, "app-lib");
            mAsecInternalPath = new File(dataDir, "app-asec").getPath();
            mUserAppDataDir = new File(dataDir, "user");
            mDrmAppPrivateInstallDir = new File(dataDir, "app-private");

            sUserManager = new UserManagerService(context, this,
                    mInstallLock, mPackages);

            // Propagate permission configuration in to package manager.
            ArrayMap<String, SystemConfig.PermissionEntry> permConfig
                    = systemConfig.getPermissions();
            for (int i=0; i<permConfig.size(); i++) {
                SystemConfig.PermissionEntry perm = permConfig.valueAt(i);
                BasePermission bp = mSettings.mPermissions.get(perm.name);
                if (bp == null) {
                    bp = new BasePermission(perm.name, "android", BasePermission.TYPE_BUILTIN);
                    mSettings.mPermissions.put(perm.name, bp);
                }
                if (perm.gids != null) {
                    bp.setGids(perm.gids, perm.perUser);
                }
            }

            ArrayMap<String, String> libConfig = systemConfig.getSharedLibraries();
            for (int i=0; i<libConfig.size(); i++) {
                mSharedLibraries.put(libConfig.keyAt(i),
                        new SharedLibraryEntry(libConfig.valueAt(i), null));
            }

            mFoundPolicyFile = SELinuxMMAC.readInstallPolicy();

            mRestoredSettings = mSettings.readLPw(this, sUserManager.getUsers(false),
                    mSdkVersion, mOnlyCore);

            String customResolverActivity = Resources.getSystem().getString(
                    R.string.config_customResolverActivity);
            if (TextUtils.isEmpty(customResolverActivity)) {
                customResolverActivity = null;
            } else {
                mCustomResolverComponentName = ComponentName.unflattenFromString(
                        customResolverActivity);
            }

            long startTime = SystemClock.uptimeMillis();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SYSTEM_SCAN_START,
                    startTime);

            // Set flag to monitor and not change apk file paths when
            // scanning install directories.
            final int scanFlags = SCAN_NO_PATHS | SCAN_DEFER_DEX | SCAN_BOOTING | SCAN_INITIAL;

            final ArraySet<String> alreadyDexOpted = new ArraySet<String>();

            /**
             * Add everything in the in the boot class path to the
             * list of process files because dexopt will have been run
             * if necessary during zygote startup.
             */
            final String bootClassPath = System.getenv("BOOTCLASSPATH");
            final String systemServerClassPath = System.getenv("SYSTEMSERVERCLASSPATH");

            if (bootClassPath != null) {
                String[] bootClassPathElements = splitString(bootClassPath, ':');
                for (String element : bootClassPathElements) {
                    alreadyDexOpted.add(element);
                }
            } else {
                Slog.w(TAG, "No BOOTCLASSPATH found!");
            }

            if (systemServerClassPath != null) {
                String[] systemServerClassPathElements = splitString(systemServerClassPath, ':');
                for (String element : systemServerClassPathElements) {
                    alreadyDexOpted.add(element);
                }
            } else {
                Slog.w(TAG, "No SYSTEMSERVERCLASSPATH found!");
            }

            final List<String> allInstructionSets = InstructionSets.getAllInstructionSets();
            final String[] dexCodeInstructionSets =
                    getDexCodeInstructionSets(
                            allInstructionSets.toArray(new String[allInstructionSets.size()]));

            /**
             * Ensure all external libraries have had dexopt run on them.
             */
            if (mSharedLibraries.size() > 0) {
                // NOTE: For now, we're compiling these system "shared libraries"
                // (and framework jars) into all available architectures. It's possible
                // to compile them only when we come across an app that uses them (there's
                // already logic for that in scanPackageLI) but that adds some complexity.
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    for (SharedLibraryEntry libEntry : mSharedLibraries.values()) {
                        final String lib = libEntry.path;
                        if (lib == null) {
                            continue;
                        }

                        try {
                            int dexoptNeeded = DexFile.getDexOptNeeded(lib, null, dexCodeInstructionSet, false);
                            if (dexoptNeeded != DexFile.NO_DEXOPT_NEEDED) {
                                alreadyDexOpted.add(lib);
                                mInstaller.dexopt(lib, Process.SYSTEM_UID, true, dexCodeInstructionSet, dexoptNeeded);
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Library not found: " + lib);
                        } catch (IOException e) {
                            Slog.w(TAG, "Cannot dexopt " + lib + "; is it an APK or JAR? "
                                    + e.getMessage());
                        }
                    }
                }
            }

            File frameworkDir = new File(Environment.getRootDirectory(), "framework");

            // Gross hack for now: we know this file doesn't contain any
            // code, so don't dexopt it to avoid the resulting log spew.
            alreadyDexOpted.add(frameworkDir.getPath() + "/framework-res.apk");

            // Gross hack for now: we know this file is only part of
            // the boot class path for art, so don't dexopt it to
            // avoid the resulting log spew.
            alreadyDexOpted.add(frameworkDir.getPath() + "/core-libart.jar");

            /**
             * There are a number of commands implemented in Java, which
             * we currently need to do the dexopt on so that they can be
             * run from a non-root shell.
             */
            String[] frameworkFiles = frameworkDir.list();
            if (frameworkFiles != null) {
                // TODO: We could compile these only for the most preferred ABI. We should
                // first double check that the dex files for these commands are not referenced
                // by other system apps.
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    for (int i=0; i<frameworkFiles.length; i++) {
                        File libPath = new File(frameworkDir, frameworkFiles[i]);
                        String path = libPath.getPath();
                        // Skip the file if we already did it.
                        if (alreadyDexOpted.contains(path)) {
                            continue;
                        }
                        // Skip the file if it is not a type we want to dexopt.
                        if (!path.endsWith(".apk") && !path.endsWith(".jar")) {
                            continue;
                        }
                        try {
                            int dexoptNeeded = DexFile.getDexOptNeeded(path, null, dexCodeInstructionSet, false);
                            if (dexoptNeeded != DexFile.NO_DEXOPT_NEEDED) {
                                mInstaller.dexopt(path, Process.SYSTEM_UID, true, dexCodeInstructionSet, dexoptNeeded);
                            }
                        } catch (FileNotFoundException e) {
                            Slog.w(TAG, "Jar not found: " + path);
                        } catch (IOException e) {
                            Slog.w(TAG, "Exception reading jar: " + path, e);
                        }
                    }
                }
            }

            final VersionInfo ver = mSettings.getInternalVersion();
            mIsUpgrade = !Build.FINGERPRINT.equals(ver.fingerprint);
            // when upgrading from pre-M, promote system app permissions from install to runtime
            mPromoteSystemApps =
                    mIsUpgrade && ver.sdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1;

            // save off the names of pre-existing system packages prior to scanning; we don't
            // want to automatically grant runtime permissions for new system apps
            if (mPromoteSystemApps) {
                Iterator<PackageSetting> pkgSettingIter = mSettings.mPackages.values().iterator();
                while (pkgSettingIter.hasNext()) {
                    PackageSetting ps = pkgSettingIter.next();
                    if (isSystemApp(ps)) {
                        mExistingSystemPackages.add(ps.name);
                    }
                }
            }

            // Collect vendor overlay packages.
            // (Do this before scanning any apps.)
            // For security and version matching reason, only consider
            // overlay packages if they reside in VENDOR_OVERLAY_DIR.
            File vendorOverlayDir = new File(VENDOR_OVERLAY_DIR);
            scanDirLI(vendorOverlayDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanFlags | SCAN_TRUSTED_OVERLAY, 0);

            // Find base frameworks (resource packages without code).
            scanDirLI(frameworkDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR
                    | PackageParser.PARSE_IS_PRIVILEGED,
                    scanFlags | SCAN_NO_DEX, 0);

            // Collected privileged system packages.
            final File privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app");
            scanDirLI(privilegedAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR
                    | PackageParser.PARSE_IS_PRIVILEGED, scanFlags, 0);

            // Collect ordinary system packages.
            final File systemAppDir = new File(Environment.getRootDirectory(), "app");
            scanDirLI(systemAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanFlags, 0);

            // Collect all vendor packages.
            File vendorAppDir = new File("/vendor/app");
            try {
                vendorAppDir = vendorAppDir.getCanonicalFile();
            } catch (IOException e) {
                // failed to look up canonical path, continue with original one
            }
            scanDirLI(vendorAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanFlags, 0);

            // Collect all OEM packages.
            final File oemAppDir = new File(Environment.getOemDirectory(), "app");
            scanDirLI(oemAppDir, PackageParser.PARSE_IS_SYSTEM
                    | PackageParser.PARSE_IS_SYSTEM_DIR, scanFlags, 0);

            if (DEBUG_UPGRADE) Log.v(TAG, "Running installd update commands");
            mInstaller.moveFiles();

            // Prune any system packages that no longer exist.
            final List<String> possiblyDeletedUpdatedSystemApps = new ArrayList<String>();
            if (!mOnlyCore) {
                Iterator<PackageSetting> psit = mSettings.mPackages.values().iterator();
                while (psit.hasNext()) {
                    PackageSetting ps = psit.next();

                    /*
                     * If this is not a system app, it can't be a
                     * disable system app.
                     */
                    if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        continue;
                    }

                    /*
                     * If the package is scanned, it's not erased.
                     */
                    final PackageParser.Package scannedPkg = mPackages.get(ps.name);
                    if (scannedPkg != null) {
                        /*
                         * If the system app is both scanned and in the
                         * disabled packages list, then it must have been
                         * added via OTA. Remove it from the currently
                         * scanned package so the previously user-installed
                         * application can be scanned.
                         */
                        if (mSettings.isDisabledSystemPackageLPr(ps.name)) {
                            logCriticalInfo(Log.WARN, "Expecting better updated system app for "
                                    + ps.name + "; removing system app.  Last known codePath="
                                    + ps.codePathString + ", installStatus=" + ps.installStatus
                                    + ", versionCode=" + ps.versionCode + "; scanned versionCode="
                                    + scannedPkg.mVersionCode);
                            removePackageLI(ps, true);
                            mExpectingBetter.put(ps.name, ps.codePath);
                        }

                        continue;
                    }

                    if (!mSettings.isDisabledSystemPackageLPr(ps.name)) {
                        psit.remove();
                        logCriticalInfo(Log.WARN, "System package " + ps.name
                                + " no longer exists; wiping its data");
                        removeDataDirsLI(null, ps.name);
                    } else {
                        final PackageSetting disabledPs = mSettings.getDisabledSystemPkgLPr(ps.name);
                        if (disabledPs.codePath == null || !disabledPs.codePath.exists()) {
                            possiblyDeletedUpdatedSystemApps.add(ps.name);
                        }
                    }
                }
            }

            //look for any incomplete package installations
            ArrayList<PackageSetting> deletePkgsList = mSettings.getListOfIncompleteInstallPackagesLPr();
            //clean up list
            for(int i = 0; i < deletePkgsList.size(); i++) {
                //clean up here
                cleanupInstallFailedPackage(deletePkgsList.get(i));
            }
            //delete tmp files
            deleteTempPackageFiles();

            // Remove any shared userIDs that have no associated packages
            mSettings.pruneSharedUsersLPw();

            if (!mOnlyCore) {
                EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_DATA_SCAN_START,
                        SystemClock.uptimeMillis());
                scanDirLI(mAppInstallDir, 0, scanFlags | SCAN_REQUIRE_KNOWN, 0);

                scanDirLI(mDrmAppPrivateInstallDir, PackageParser.PARSE_FORWARD_LOCK,
                        scanFlags | SCAN_REQUIRE_KNOWN, 0);

                /**
                 * Remove disable package settings for any updated system
                 * apps that were removed via an OTA. If they're not a
                 * previously-updated app, remove them completely.
                 * Otherwise, just revoke their system-level permissions.
                 */
                for (String deletedAppName : possiblyDeletedUpdatedSystemApps) {
                    PackageParser.Package deletedPkg = mPackages.get(deletedAppName);
                    mSettings.removeDisabledSystemPackageLPw(deletedAppName);

                    String msg;
                    if (deletedPkg == null) {
                        msg = "Updated system package " + deletedAppName
                                + " no longer exists; wiping its data";
                        removeDataDirsLI(null, deletedAppName);
                    } else {
                        msg = "Updated system app + " + deletedAppName
                                + " no longer present; removing system privileges for "
                                + deletedAppName;

                        deletedPkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;

                        PackageSetting deletedPs = mSettings.mPackages.get(deletedAppName);
                        deletedPs.pkgFlags &= ~ApplicationInfo.FLAG_SYSTEM;
                    }
                    logCriticalInfo(Log.WARN, msg);
                }

                /**
                 * Make sure all system apps that we expected to appear on
                 * the userdata partition actually showed up. If they never
                 * appeared, crawl back and revive the system version.
                 */
                for (int i = 0; i < mExpectingBetter.size(); i++) {
                    final String packageName = mExpectingBetter.keyAt(i);
                    if (!mPackages.containsKey(packageName)) {
                        final File scanFile = mExpectingBetter.valueAt(i);

                        logCriticalInfo(Log.WARN, "Expected better " + packageName
                                + " but never showed up; reverting to system");

                        final int reparseFlags;
                        if (FileUtils.contains(privilegedAppDir, scanFile)) {
                            reparseFlags = PackageParser.PARSE_IS_SYSTEM
                                    | PackageParser.PARSE_IS_SYSTEM_DIR
                                    | PackageParser.PARSE_IS_PRIVILEGED;
                        } else if (FileUtils.contains(systemAppDir, scanFile)) {
                            reparseFlags = PackageParser.PARSE_IS_SYSTEM
                                    | PackageParser.PARSE_IS_SYSTEM_DIR;
                        } else if (FileUtils.contains(vendorAppDir, scanFile)) {
                            reparseFlags = PackageParser.PARSE_IS_SYSTEM
                                    | PackageParser.PARSE_IS_SYSTEM_DIR;
                        } else if (FileUtils.contains(oemAppDir, scanFile)) {
                            reparseFlags = PackageParser.PARSE_IS_SYSTEM
                                    | PackageParser.PARSE_IS_SYSTEM_DIR;
                        } else {
                            Slog.e(TAG, "Ignoring unexpected fallback path " + scanFile);
                            continue;
                        }

                        mSettings.enableSystemPackageLPw(packageName);

                        try {
                            scanPackageLI(scanFile, reparseFlags, scanFlags, 0, null);
                        } catch (PackageManagerException e) {
                            Slog.e(TAG, "Failed to parse original system package: "
                                    + e.getMessage());
                        }
                    }
                }
            }
            mExpectingBetter.clear();

            // Now that we know all of the shared libraries, update all clients to have
            // the correct library paths.
            updateAllSharedLibrariesLPw();

            for (SharedUserSetting setting : mSettings.getAllSharedUsersLPw()) {
                // NOTE: We ignore potential failures here during a system scan (like
                // the rest of the commands above) because there's precious little we
                // can do about it. A settings error is reported, though.
                adjustCpuAbisForSharedUserLPw(setting.packages, null /* scanned package */,
                        false /* force dexopt */, false /* defer dexopt */);
            }

            // Now that we know all the packages we are keeping,
            // read and update their last usage times.
            mPackageUsage.readLP();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_SCAN_END,
                    SystemClock.uptimeMillis());
            Slog.i(TAG, "Time to scan packages: "
                    + ((SystemClock.uptimeMillis()-startTime)/1000f)
                    + " seconds");

            // If the platform SDK has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear.  This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow...  it would be nice to have some better way to handle
            // this situation.
            int updateFlags = UPDATE_PERMISSIONS_ALL;
            if (ver.sdkVersion != mSdkVersion) {
                Slog.i(TAG, "Platform changed from " + ver.sdkVersion + " to "
                        + mSdkVersion + "; regranting permissions for internal storage");
                updateFlags |= UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL;
            }
            updatePermissionsLPw(null, null, updateFlags);
            ver.sdkVersion = mSdkVersion;
            // clear only after permissions have been updated
            mExistingSystemPackages.clear();
            mPromoteSystemApps = false;

            // If this is the first boot, and it is a normal boot, then
            // we need to initialize the default preferred apps.
            if (!mRestoredSettings && !onlyCore) {
                mSettings.applyDefaultPreferredAppsLPw(this, UserHandle.USER_OWNER);
                applyFactoryDefaultBrowserLPw(UserHandle.USER_OWNER);
                primeDomainVerificationsLPw(UserHandle.USER_OWNER);
            }

            // If this is first boot after an OTA, and a normal boot, then
            // we need to clear code cache directories.
            if (mIsUpgrade && !onlyCore) {
                Slog.i(TAG, "Build fingerprint changed; clearing code caches");
                for (int i = 0; i < mSettings.mPackages.size(); i++) {
                    final PackageSetting ps = mSettings.mPackages.valueAt(i);
                    if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, ps.volumeUuid)) {
                        deleteCodeCacheDirsLI(ps.volumeUuid, ps.name);
                    }
                }
                ver.fingerprint = Build.FINGERPRINT;
            }

            checkDefaultBrowser();

            // All the changes are done during package scanning.
            ver.databaseVersion = Settings.CURRENT_DATABASE_VERSION;

            // can downgrade to reader
            mSettings.writeLPr();

            EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_PMS_READY,
                    SystemClock.uptimeMillis());

            mRequiredVerifierPackage = getRequiredVerifierLPr();
            mRequiredInstallerPackage = getRequiredInstallerLPr();

            mInstallerService = new PackageInstallerService(context, this);

            mIntentFilterVerifierComponent = getIntentFilterVerifierComponentNameLPr();
            mIntentFilterVerifier = new IntentVerifierProxy(mContext,
                    mIntentFilterVerifierComponent);

        } // synchronized (mPackages)
        } // synchronized (mInstallLock)

        // Now after opening every single application zip, make sure they
        // are all flushed.  Not really needed, but keeps things nice and
        // tidy.
        Runtime.getRuntime().gc();

        // Expose private service for system components to use.
        LocalServices.addService(PackageManagerInternal.class, new PackageManagerInternalImpl());
    }

    @Override
    public boolean isFirstBoot() {
        return !mRestoredSettings;
    }

    @Override
    public boolean isOnlyCoreApps() {
        return mOnlyCore;
    }

    @Override
    public boolean isUpgrade() {
        return mIsUpgrade;
    }

    private String getRequiredVerifierLPr() {
        final Intent verification = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
        final List<ResolveInfo> receivers = queryIntentReceivers(verification, PACKAGE_MIME_TYPE,
                PackageManager.GET_DISABLED_COMPONENTS, 0 /* TODO: Which userId? */);

        String requiredVerifier = null;

        final int N = receivers.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo info = receivers.get(i);

            if (info.activityInfo == null) {
                continue;
            }

            final String packageName = info.activityInfo.packageName;

            if (checkPermission(android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                    packageName, UserHandle.USER_OWNER) != PackageManager.PERMISSION_GRANTED) {
                continue;
            }

            if (requiredVerifier != null) {
                throw new RuntimeException("There can be only one required verifier");
            }

            requiredVerifier = packageName;
        }

        return requiredVerifier;
    }

    private String getRequiredInstallerLPr() {
        Intent installerIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installerIntent.addCategory(Intent.CATEGORY_DEFAULT);
        installerIntent.setDataAndType(Uri.fromFile(new File("foo.apk")), PACKAGE_MIME_TYPE);

        final List<ResolveInfo> installers = queryIntentActivities(installerIntent,
                PACKAGE_MIME_TYPE, 0, 0);

        String requiredInstaller = null;

        final int N = installers.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo info = installers.get(i);
            final String packageName = info.activityInfo.packageName;

            if (!info.activityInfo.applicationInfo.isSystemApp()) {
                continue;
            }

            if (requiredInstaller != null) {
                throw new RuntimeException("There must be one required installer");
            }

            requiredInstaller = packageName;
        }

        if (requiredInstaller == null) {
            throw new RuntimeException("There must be one required installer");
        }

        return requiredInstaller;
    }

    private ComponentName getIntentFilterVerifierComponentNameLPr() {
        final Intent verification = new Intent(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION);
        final List<ResolveInfo> receivers = queryIntentReceivers(verification, PACKAGE_MIME_TYPE,
                PackageManager.GET_DISABLED_COMPONENTS, 0 /* userId */);

        ComponentName verifierComponentName = null;

        int priority = -1000;
        final int N = receivers.size();
        for (int i = 0; i < N; i++) {
            final ResolveInfo info = receivers.get(i);

            if (info.activityInfo == null) {
                continue;
            }

            final String packageName = info.activityInfo.packageName;

            final PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                continue;
            }

            if (checkPermission(android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT,
                    packageName, UserHandle.USER_OWNER) != PackageManager.PERMISSION_GRANTED) {
                continue;
            }

            // Select the IntentFilterVerifier with the highest priority
            if (priority < info.priority) {
                priority = info.priority;
                verifierComponentName = new ComponentName(packageName, info.activityInfo.name);
                if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG, "Selecting IntentFilterVerifier: "
                        + verifierComponentName + " with priority: " + info.priority);
            }
        }

        return verifierComponentName;
    }

    private void primeDomainVerificationsLPw(int userId) {
        if (DEBUG_DOMAIN_VERIFICATION) {
            Slog.d(TAG, "Priming domain verifications in user " + userId);
        }

        SystemConfig systemConfig = SystemConfig.getInstance();
        ArraySet<String> packages = systemConfig.getLinkedApps();
        ArraySet<String> domains = new ArraySet<String>();

        for (String packageName : packages) {
            PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg != null) {
                if (!pkg.isSystemApp()) {
                    Slog.w(TAG, "Non-system app '" + packageName + "' in sysconfig <app-link>");
                    continue;
                }

                domains.clear();
                for (PackageParser.Activity a : pkg.activities) {
                    for (ActivityIntentInfo filter : a.intents) {
                        if (hasValidDomains(filter)) {
                            domains.addAll(filter.getHostsList());
                        }
                    }
                }

                if (domains.size() > 0) {
                    if (DEBUG_DOMAIN_VERIFICATION) {
                        Slog.v(TAG, "      + " + packageName);
                    }
                    // 'Undefined' in the global IntentFilterVerificationInfo, i.e. the usual
                    // state w.r.t. the formal app-linkage "no verification attempted" state;
                    // and then 'always' in the per-user state actually used for intent resolution.
                    final IntentFilterVerificationInfo ivi;
                    ivi = mSettings.createIntentFilterVerificationIfNeededLPw(packageName,
                            new ArrayList<String>(domains));
                    ivi.setStatus(INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED);
                    mSettings.updateIntentFilterVerificationStatusLPw(packageName,
                            INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS, userId);
                } else {
                    Slog.w(TAG, "Sysconfig <app-link> package '" + packageName
                            + "' does not handle web links");
                }
            } else {
                Slog.w(TAG, "Unknown package '" + packageName + "' in sysconfig <app-link>");
            }
        }

        scheduleWritePackageRestrictionsLocked(userId);
        scheduleWriteSettingsLocked();
    }

    private void applyFactoryDefaultBrowserLPw(int userId) {
        // The default browser app's package name is stored in a string resource,
        // with a product-specific overlay used for vendor customization.
        String browserPkg = mContext.getResources().getString(
                com.android.internal.R.string.default_browser);
        if (!TextUtils.isEmpty(browserPkg)) {
            // non-empty string => required to be a known package
            PackageSetting ps = mSettings.mPackages.get(browserPkg);
            if (ps == null) {
                Slog.e(TAG, "Product default browser app does not exist: " + browserPkg);
                browserPkg = null;
            } else {
                mSettings.setDefaultBrowserPackageNameLPw(browserPkg, userId);
            }
        }

        // Nothing valid explicitly set? Make the factory-installed browser the explicit
        // default.  If there's more than one, just leave everything alone.
        if (browserPkg == null) {
            calculateDefaultBrowserLPw(userId);
        }
    }

    private void calculateDefaultBrowserLPw(int userId) {
        List<String> allBrowsers = resolveAllBrowserApps(userId);
        final String browserPkg = (allBrowsers.size() == 1) ? allBrowsers.get(0) : null;
        mSettings.setDefaultBrowserPackageNameLPw(browserPkg, userId);
    }

    private List<String> resolveAllBrowserApps(int userId) {
        // Resolve the canonical browser intent and check that the handleAllWebDataURI boolean is set
        List<ResolveInfo> list = queryIntentActivities(sBrowserIntent, null,
                PackageManager.MATCH_ALL, userId);

        final int count = list.size();
        List<String> result = new ArrayList<String>(count);
        for (int i=0; i<count; i++) {
            ResolveInfo info = list.get(i);
            if (info.activityInfo == null
                    || !info.handleAllWebDataURI
                    || (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || result.contains(info.activityInfo.packageName)) {
                continue;
            }
            result.add(info.activityInfo.packageName);
        }

        return result;
    }

    private boolean packageIsBrowser(String packageName, int userId) {
        List<ResolveInfo> list = queryIntentActivities(sBrowserIntent, null,
                PackageManager.MATCH_ALL, userId);
        final int N = list.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo info = list.get(i);
            if (packageName.equals(info.activityInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private void checkDefaultBrowser() {
        final int myUserId = UserHandle.myUserId();
        final String packageName = getDefaultBrowserPackageName(myUserId);
        if (packageName != null) {
            PackageInfo info = getPackageInfo(packageName, 0, myUserId);
            if (info == null) {
                Slog.w(TAG, "Default browser no longer installed: " + packageName);
                synchronized (mPackages) {
                    applyFactoryDefaultBrowserLPw(myUserId);    // leaves ambiguous when > 1
                }
            }
        }
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

    void cleanupInstallFailedPackage(PackageSetting ps) {
        logCriticalInfo(Log.WARN, "Cleaning up incompletely installed app: " + ps.name);

        removeDataDirsLI(ps.volumeUuid, ps.name);
        if (ps.codePath != null) {
            if (ps.codePath.isDirectory()) {
                mInstaller.rmPackageDir(ps.codePath.getAbsolutePath());
            } else {
                ps.codePath.delete();
            }
        }
        if (ps.resourcePath != null && !ps.resourcePath.equals(ps.codePath)) {
            if (ps.resourcePath.isDirectory()) {
                FileUtils.deleteContents(ps.resourcePath);
            }
            ps.resourcePath.delete();
        }
        mSettings.removePackageLPw(ps.name);
    }

    static int[] appendInts(int[] cur, int[] add) {
        if (add == null) return cur;
        if (cur == null) return add;
        final int N = add.length;
        for (int i=0; i<N; i++) {
            cur = appendInt(cur, add[i]);
        }
        return cur;
    }

    PackageInfo generatePackageInfo(PackageParser.Package p, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        final PackageSetting ps = (PackageSetting) p.mExtras;
        if (ps == null) {
            return null;
        }

        final PermissionsState permissionsState = ps.getPermissionsState();

        final int[] gids = permissionsState.computeGids(userId);
        final Set<String> permissions = permissionsState.getPermissions(userId);
        final PackageUserState state = ps.readUserState(userId);

        return PackageParser.generatePackageInfo(p, gids, flags,
                ps.firstInstallTime, ps.lastUpdateTime, permissions, state, userId);
    }

    @Override
    public boolean isPackageFrozen(String packageName) {
        synchronized (mPackages) {
            final PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps != null) {
                return ps.frozen;
            }
        }
        Slog.w(TAG, "Package " + packageName + " is missing; assuming frozen");
        return true;
    }

    @Override
    public boolean isPackageAvailable(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return false;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "is package available");
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (p != null) {
                final PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    final PackageUserState state = ps.readUserState(userId);
                    if (state != null) {
                        return PackageParser.isAvailable(state);
                    }
                }
            }
        }
        return false;
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get package info");
        // reader
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (DEBUG_PACKAGE_INFO)
                Log.v(TAG, "getPackageInfo " + packageName + ": " + p);
            if (p != null) {
                return generatePackageInfo(p, flags, userId);
            }
            if((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                return generatePackageInfoFromSettingsLPw(packageName, flags, userId);
            }
        }
        return null;
    }

    @Override
    public String[] currentToCanonicalPackageNames(String[] names) {
        String[] out = new String[names.length];
        // reader
        synchronized (mPackages) {
            for (int i=names.length-1; i>=0; i--) {
                PackageSetting ps = mSettings.mPackages.get(names[i]);
                out[i] = ps != null && ps.realName != null ? ps.realName : names[i];
            }
        }
        return out;
    }

    @Override
    public String[] canonicalToCurrentPackageNames(String[] names) {
        String[] out = new String[names.length];
        // reader
        synchronized (mPackages) {
            for (int i=names.length-1; i>=0; i--) {
                String cur = mSettings.mRenamedPackages.get(names[i]);
                out[i] = cur != null ? cur : names[i];
            }
        }
        return out;
    }

    @Override
    public int getPackageUid(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return -1;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get package uid");

        // reader
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if(p != null) {
                return UserHandle.getUid(userId, p.applicationInfo.uid);
            }
            PackageSetting ps = mSettings.mPackages.get(packageName);
            if((ps == null) || (ps.pkg == null) || (ps.pkg.applicationInfo == null)) {
                return -1;
            }
            p = ps.pkg;
            return p != null ? UserHandle.getUid(userId, p.applicationInfo.uid) : -1;
        }
    }

    @Override
    public int[] getPackageGids(String packageName, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) {
            return null;
        }

        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false,
                "getPackageGids");

        // reader
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (DEBUG_PACKAGE_INFO) {
                Log.v(TAG, "getPackageGids" + packageName + ": " + p);
            }
            if (p != null) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                return ps.getPermissionsState().computeGids(userId);
            }
        }

        return null;
    }

    static PermissionInfo generatePermissionInfo(
            BasePermission bp, int flags) {
        if (bp.perm != null) {
            return PackageParser.generatePermissionInfo(bp.perm, flags);
        }
        PermissionInfo pi = new PermissionInfo();
        pi.name = bp.name;
        pi.packageName = bp.sourcePackage;
        pi.nonLocalizedLabel = bp.name;
        pi.protectionLevel = bp.protectionLevel;
        return pi;
    }

    @Override
    public PermissionInfo getPermissionInfo(String name, int flags) {
        // reader
        synchronized (mPackages) {
            final BasePermission p = mSettings.mPermissions.get(name);
            if (p != null) {
                return generatePermissionInfo(p, flags);
            }
            return null;
        }
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String group, int flags) {
        // reader
        synchronized (mPackages) {
            ArrayList<PermissionInfo> out = new ArrayList<PermissionInfo>(10);
            for (BasePermission p : mSettings.mPermissions.values()) {
                if (group == null) {
                    if (p.perm == null || p.perm.info.group == null) {
                        out.add(generatePermissionInfo(p, flags));
                    }
                } else {
                    if (p.perm != null && group.equals(p.perm.info.group)) {
                        out.add(PackageParser.generatePermissionInfo(p.perm, flags));
                    }
                }
            }

            if (out.size() > 0) {
                return out;
            }
            return mPermissionGroups.containsKey(group) ? out : null;
        }
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String name, int flags) {
        // reader
        synchronized (mPackages) {
            return PackageParser.generatePermissionGroupInfo(
                    mPermissionGroups.get(name), flags);
        }
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        // reader
        synchronized (mPackages) {
            final int N = mPermissionGroups.size();
            ArrayList<PermissionGroupInfo> out
                    = new ArrayList<PermissionGroupInfo>(N);
            for (PackageParser.PermissionGroup pg : mPermissionGroups.values()) {
                out.add(PackageParser.generatePermissionGroupInfo(pg, flags));
            }
            return out;
        }
    }

    private ApplicationInfo generateApplicationInfoFromSettingsLPw(String packageName, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return null;
        PackageSetting ps = mSettings.mPackages.get(packageName);
        if (ps != null) {
            if (ps.pkg == null) {
                PackageInfo pInfo = generatePackageInfoFromSettingsLPw(packageName,
                        flags, userId);
                if (pInfo != null) {
                    return pInfo.applicationInfo;
                }
                return null;
            }
            return PackageParser.generateApplicationInfo(ps.pkg, flags,
                    ps.readUserState(userId), userId);
        }
        return null;
    }

    private PackageInfo generatePackageInfoFromSettingsLPw(String packageName, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return null;
        PackageSetting ps = mSettings.mPackages.get(packageName);
        if (ps != null) {
            PackageParser.Package pkg = ps.pkg;
            if (pkg == null) {
                if ((flags & PackageManager.GET_UNINSTALLED_PACKAGES) == 0) {
                    return null;
                }
                // Only data remains, so we aren't worried about code paths
                pkg = new PackageParser.Package(packageName);
                pkg.applicationInfo.packageName = packageName;
                pkg.applicationInfo.flags = ps.pkgFlags | ApplicationInfo.FLAG_IS_DATA_ONLY;
                pkg.applicationInfo.privateFlags = ps.pkgPrivateFlags;
                pkg.applicationInfo.dataDir = Environment
                        .getDataUserPackageDirectory(ps.volumeUuid, userId, packageName)
                        .getAbsolutePath();
                pkg.applicationInfo.primaryCpuAbi = ps.primaryCpuAbiString;
                pkg.applicationInfo.secondaryCpuAbi = ps.secondaryCpuAbiString;
            }
            return generatePackageInfo(pkg, flags, userId);
        }
        return null;
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get application info");
        // writer
        synchronized (mPackages) {
            PackageParser.Package p = mPackages.get(packageName);
            if (DEBUG_PACKAGE_INFO) Log.v(
                    TAG, "getApplicationInfo " + packageName
                    + ": " + p);
            if (p != null) {
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if (ps == null) return null;
                // Note: isEnabledLP() does not apply here - always return info
                return PackageParser.generateApplicationInfo(
                        p, flags, ps.readUserState(userId), userId);
            }
            if ("android".equals(packageName)||"system".equals(packageName)) {
                return mAndroidApplication;
            }
            if ((flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0) {
                return generateApplicationInfoFromSettingsLPw(packageName, flags, userId);
            }
        }
        return null;
    }

    @Override
    public void freeStorageAndNotify(final String volumeUuid, final long freeStorageSize,
            final IPackageDataObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        // Queue up an async operation since clearing cache may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                int retCode = -1;
                synchronized (mInstallLock) {
                    retCode = mInstaller.freeCache(volumeUuid, freeStorageSize);
                    if (retCode < 0) {
                        Slog.w(TAG, "Couldn't clear application caches");
                    }
                }
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(null, (retCode >= 0));
                    } catch (RemoteException e) {
                        Slog.w(TAG, "RemoveException when invoking call back");
                    }
                }
            }
        });
    }

    @Override
    public void freeStorage(final String volumeUuid, final long freeStorageSize,
            final IntentSender pi) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_CACHE, null);
        // Queue up an async operation since clearing cache may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                int retCode = -1;
                synchronized (mInstallLock) {
                    retCode = mInstaller.freeCache(volumeUuid, freeStorageSize);
                    if (retCode < 0) {
                        Slog.w(TAG, "Couldn't clear application caches");
                    }
                }
                if(pi != null) {
                    try {
                        // Callback via pending intent
                        int code = (retCode >= 0) ? 1 : 0;
                        pi.sendIntent(null, code, null,
                                null, null);
                    } catch (SendIntentException e1) {
                        Slog.i(TAG, "Failed to send pending intent");
                    }
                }
            }
        });
    }

    void freeStorage(String volumeUuid, long freeStorageSize) throws IOException {
        synchronized (mInstallLock) {
            if (mInstaller.freeCache(volumeUuid, freeStorageSize) < 0) {
                throw new IOException("Failed to free enough space");
            }
        }
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get activity info");
        synchronized (mPackages) {
            PackageParser.Activity a = mActivities.mActivities.get(component);

            if (DEBUG_PACKAGE_INFO) Log.v(TAG, "getActivityInfo " + component + ": " + a);
            if (a != null && mSettings.isEnabledLPr(a.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateActivityInfo(a, flags, ps.readUserState(userId),
                        userId);
            }
            if (mResolveComponentName.equals(component)) {
                return PackageParser.generateActivityInfo(mResolveActivity, flags,
                        new PackageUserState(), userId);
            }
        }
        return null;
    }

    @Override
    public boolean activitySupportsIntent(ComponentName component, Intent intent,
            String resolvedType) {
        synchronized (mPackages) {
            if (component.equals(mResolveComponentName)) {
                // The resolver supports EVERYTHING!
                return true;
            }
            PackageParser.Activity a = mActivities.mActivities.get(component);
            if (a == null) {
                return false;
            }
            for (int i=0; i<a.intents.size(); i++) {
                if (a.intents.get(i).match(intent.getAction(), resolvedType, intent.getScheme(),
                        intent.getData(), intent.getCategories(), TAG) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get receiver info");
        synchronized (mPackages) {
            PackageParser.Activity a = mReceivers.mActivities.get(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getReceiverInfo " + component + ": " + a);
            if (a != null && mSettings.isEnabledLPr(a.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateActivityInfo(a, flags, ps.readUserState(userId),
                        userId);
            }
        }
        return null;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get service info");
        synchronized (mPackages) {
            PackageParser.Service s = mServices.mServices.get(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getServiceInfo " + component + ": " + s);
            if (s != null && mSettings.isEnabledLPr(s.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateServiceInfo(s, flags, ps.readUserState(userId),
                        userId);
            }
        }
        return null;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "get provider info");
        synchronized (mPackages) {
            PackageParser.Provider p = mProviders.mProviders.get(component);
            if (DEBUG_PACKAGE_INFO) Log.v(
                TAG, "getProviderInfo " + component + ": " + p);
            if (p != null && mSettings.isEnabledLPr(p.info, flags, userId)) {
                PackageSetting ps = mSettings.mPackages.get(component.getPackageName());
                if (ps == null) return null;
                return PackageParser.generateProviderInfo(p, flags, ps.readUserState(userId),
                        userId);
            }
        }
        return null;
    }

    @Override
    public String[] getSystemSharedLibraryNames() {
        Set<String> libSet;
        synchronized (mPackages) {
            libSet = mSharedLibraries.keySet();
            int size = libSet.size();
            if (size > 0) {
                String[] libs = new String[size];
                libSet.toArray(libs);
                return libs;
            }
        }
        return null;
    }

    /**
     * @hide
     */
    PackageParser.Package findSharedNonSystemLibrary(String libName) {
        synchronized (mPackages) {
            PackageManagerService.SharedLibraryEntry lib = mSharedLibraries.get(libName);
            if (lib != null && lib.apk != null) {
                return mPackages.get(lib.apk);
            }
        }
        return null;
    }

    @Override
    public FeatureInfo[] getSystemAvailableFeatures() {
        Collection<FeatureInfo> featSet;
        synchronized (mPackages) {
            featSet = mAvailableFeatures.values();
            int size = featSet.size();
            if (size > 0) {
                FeatureInfo[] features = new FeatureInfo[size+1];
                featSet.toArray(features);
                FeatureInfo fi = new FeatureInfo();
                fi.reqGlEsVersion = SystemProperties.getInt("ro.opengles.version",
                        FeatureInfo.GL_ES_VERSION_UNDEFINED);
                features[size] = fi;
                return features;
            }
        }
        return null;
    }

    @Override
    public boolean hasSystemFeature(String name) {
        synchronized (mPackages) {
            return mAvailableFeatures.containsKey(name);
        }
    }

    private void checkValidCaller(int uid, int userId) {
        if (UserHandle.getUserId(uid) == userId || uid == Process.SYSTEM_UID || uid == 0)
            return;

        throw new SecurityException("Caller uid=" + uid
                + " is not privileged to communicate with user=" + userId);
    }

    @Override
    public int checkPermission(String permName, String pkgName, int userId) {
        if (!sUserManager.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        synchronized (mPackages) {
            final PackageParser.Package p = mPackages.get(pkgName);
            if (p != null && p.mExtras != null) {
                final PackageSetting ps = (PackageSetting) p.mExtras;
                final PermissionsState permissionsState = ps.getPermissionsState();
                if (permissionsState.hasPermission(permName, userId)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                // Special case: ACCESS_FINE_LOCATION permission includes ACCESS_COARSE_LOCATION
                if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName) && permissionsState
                        .hasPermission(Manifest.permission.ACCESS_FINE_LOCATION, userId)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }

        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public int checkUidPermission(String permName, int uid) {
        final int userId = UserHandle.getUserId(uid);

        if (!sUserManager.exists(userId)) {
            return PackageManager.PERMISSION_DENIED;
        }

        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj != null) {
                final SettingBase ps = (SettingBase) obj;
                final PermissionsState permissionsState = ps.getPermissionsState();
                if (permissionsState.hasPermission(permName, userId)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                // Special case: ACCESS_FINE_LOCATION permission includes ACCESS_COARSE_LOCATION
                if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName) && permissionsState
                        .hasPermission(Manifest.permission.ACCESS_FINE_LOCATION, userId)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            } else {
                ArraySet<String> perms = mSystemPermissions.get(uid);
                if (perms != null) {
                    if (perms.contains(permName)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                    if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permName) && perms
                            .contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                }
            }
        }

        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String permission, String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "isPermissionRevokedByPolicy for user " + userId);
        }

        if (checkPermission(permission, packageName, userId)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            final int flags = getPermissionFlags(permission, packageName, userId);
            return (flags & PackageManager.FLAG_PERMISSION_POLICY_FIXED) != 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public String getPermissionControllerPackageName() {
        synchronized (mPackages) {
            return mRequiredInstallerPackage;
        }
    }

    /**
     * Checks if the request is from the system or an app that has INTERACT_ACROSS_USERS
     * or INTERACT_ACROSS_USERS_FULL permissions, if the userid is not for the caller.
     * @param checkShell TODO(yamasani):
     * @param message the message to log on security exception
     */
    void enforceCrossUserPermission(int callingUid, int userId, boolean requireFullPermission,
            boolean checkShell, String message) {
        if (userId < 0) {
            throw new IllegalArgumentException("Invalid userId " + userId);
        }
        if (checkShell) {
            enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, userId);
        }
        if (userId == UserHandle.getUserId(callingUid)) return;
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            if (requireFullPermission) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
            } else {
                try {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, message);
                } catch (SecurityException se) {
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.INTERACT_ACROSS_USERS, message);
                }
            }
        }
    }

    void enforceShellRestriction(String restriction, int callingUid, int userHandle) {
        if (callingUid == Process.SHELL_UID) {
            if (userHandle >= 0
                    && sUserManager.hasUserRestriction(restriction, userHandle)) {
                throw new SecurityException("Shell does not have permission to access user "
                        + userHandle);
            } else if (userHandle < 0) {
                Slog.e(TAG, "Unable to check shell permission for user " + userHandle + "\n\t"
                        + Debug.getCallers(3));
            }
        }
    }

    private BasePermission findPermissionTreeLP(String permName) {
        for(BasePermission bp : mSettings.mPermissionTrees.values()) {
            if (permName.startsWith(bp.name) &&
                    permName.length() > bp.name.length() &&
                    permName.charAt(bp.name.length()) == '.') {
                return bp;
            }
        }
        return null;
    }

    private BasePermission checkPermissionTreeLP(String permName) {
        if (permName != null) {
            BasePermission bp = findPermissionTreeLP(permName);
            if (bp != null) {
                if (bp.uid == UserHandle.getAppId(Binder.getCallingUid())) {
                    return bp;
                }
                throw new SecurityException("Calling uid "
                        + Binder.getCallingUid()
                        + " is not allowed to add to permission tree "
                        + bp.name + " owned by uid " + bp.uid);
            }
        }
        throw new SecurityException("No permission tree found for " + permName);
    }

    static boolean compareStrings(CharSequence s1, CharSequence s2) {
        if (s1 == null) {
            return s2 == null;
        }
        if (s2 == null) {
            return false;
        }
        if (s1.getClass() != s2.getClass()) {
            return false;
        }
        return s1.equals(s2);
    }

    static boolean comparePermissionInfos(PermissionInfo pi1, PermissionInfo pi2) {
        if (pi1.icon != pi2.icon) return false;
        if (pi1.logo != pi2.logo) return false;
        if (pi1.protectionLevel != pi2.protectionLevel) return false;
        if (!compareStrings(pi1.name, pi2.name)) return false;
        if (!compareStrings(pi1.nonLocalizedLabel, pi2.nonLocalizedLabel)) return false;
        // We'll take care of setting this one.
        if (!compareStrings(pi1.packageName, pi2.packageName)) return false;
        // These are not currently stored in settings.
        //if (!compareStrings(pi1.group, pi2.group)) return false;
        //if (!compareStrings(pi1.nonLocalizedDescription, pi2.nonLocalizedDescription)) return false;
        //if (pi1.labelRes != pi2.labelRes) return false;
        //if (pi1.descriptionRes != pi2.descriptionRes) return false;
        return true;
    }

    int permissionInfoFootprint(PermissionInfo info) {
        int size = info.name.length();
        if (info.nonLocalizedLabel != null) size += info.nonLocalizedLabel.length();
        if (info.nonLocalizedDescription != null) size += info.nonLocalizedDescription.length();
        return size;
    }

    int calculateCurrentPermissionFootprintLocked(BasePermission tree) {
        int size = 0;
        for (BasePermission perm : mSettings.mPermissions.values()) {
            if (perm.uid == tree.uid) {
                size += perm.name.length() + permissionInfoFootprint(perm.perm.info);
            }
        }
        return size;
    }

    void enforcePermissionCapLocked(PermissionInfo info, BasePermission tree) {
        // We calculate the max size of permissions defined by this uid and throw
        // if that plus the size of 'info' would exceed our stated maximum.
        if (tree.uid != Process.SYSTEM_UID) {
            final int curTreeSize = calculateCurrentPermissionFootprintLocked(tree);
            if (curTreeSize + permissionInfoFootprint(info) > MAX_PERMISSION_TREE_FOOTPRINT) {
                throw new SecurityException("Permission tree size cap exceeded");
            }
        }
    }

    boolean addPermissionLocked(PermissionInfo info, boolean async) {
        if (info.labelRes == 0 && info.nonLocalizedLabel == null) {
            throw new SecurityException("Label must be specified in permission");
        }
        BasePermission tree = checkPermissionTreeLP(info.name);
        BasePermission bp = mSettings.mPermissions.get(info.name);
        boolean added = bp == null;
        boolean changed = true;
        int fixedLevel = PermissionInfo.fixProtectionLevel(info.protectionLevel);
        if (added) {
            enforcePermissionCapLocked(info, tree);
            bp = new BasePermission(info.name, tree.sourcePackage,
                    BasePermission.TYPE_DYNAMIC);
        } else if (bp.type != BasePermission.TYPE_DYNAMIC) {
            throw new SecurityException(
                    "Not allowed to modify non-dynamic permission "
                    + info.name);
        } else {
            if (bp.protectionLevel == fixedLevel
                    && bp.perm.owner.equals(tree.perm.owner)
                    && bp.uid == tree.uid
                    && comparePermissionInfos(bp.perm.info, info)) {
                changed = false;
            }
        }
        bp.protectionLevel = fixedLevel;
        info = new PermissionInfo(info);
        info.protectionLevel = fixedLevel;
        bp.perm = new PackageParser.Permission(tree.perm.owner, info);
        bp.perm.info.packageName = tree.perm.info.packageName;
        bp.uid = tree.uid;
        if (added) {
            mSettings.mPermissions.put(info.name, bp);
        }
        if (changed) {
            if (!async) {
                mSettings.writeLPr();
            } else {
                scheduleWriteSettingsLocked();
            }
        }
        return added;
    }

    @Override
    public boolean addPermission(PermissionInfo info) {
        synchronized (mPackages) {
            return addPermissionLocked(info, false);
        }
    }

    @Override
    public boolean addPermissionAsync(PermissionInfo info) {
        synchronized (mPackages) {
            return addPermissionLocked(info, true);
        }
    }

    @Override
    public void removePermission(String name) {
        synchronized (mPackages) {
            checkPermissionTreeLP(name);
            BasePermission bp = mSettings.mPermissions.get(name);
            if (bp != null) {
                if (bp.type != BasePermission.TYPE_DYNAMIC) {
                    throw new SecurityException(
                            "Not allowed to modify non-dynamic permission "
                            + name);
                }
                mSettings.mPermissions.remove(name);
                mSettings.writeLPr();
            }
        }
    }

    private static void enforceDeclaredAsUsedAndRuntimePermission(PackageParser.Package pkg,
            BasePermission bp) {
        int index = pkg.requestedPermissions.indexOf(bp.name);
        if (index == -1) {
            throw new SecurityException("Package " + pkg.packageName
                    + " has not requested permission " + bp.name);
        }
        if (!bp.isRuntime()) {
            throw new SecurityException("Permission " + bp.name
                    + " is not a changeable permission type");
        }
    }

    @Override
    public void grantRuntimePermission(String packageName, String name, final int userId) {
        if (!sUserManager.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                "grantRuntimePermission");

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false,
                "grantRuntimePermission");

        final int uid;
        final SettingBase sb;

        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            final BasePermission bp = mSettings.mPermissions.get(name);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + name);
            }

            enforceDeclaredAsUsedAndRuntimePermission(pkg, bp);

            uid = UserHandle.getUid(userId, pkg.applicationInfo.uid);
            sb = (SettingBase) pkg.mExtras;
            if (sb == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            final PermissionsState permissionsState = sb.getPermissionsState();

            final int flags = permissionsState.getPermissionFlags(name, userId);
            if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
                throw new SecurityException("Cannot grant system fixed permission: "
                        + name + " for package: " + packageName);
            }

            final int result = permissionsState.grantRuntimePermission(bp, userId);
            switch (result) {
                case PermissionsState.PERMISSION_OPERATION_FAILURE: {
                    return;
                }

                case PermissionsState.PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED: {
                    final int appId = UserHandle.getAppId(pkg.applicationInfo.uid);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            killUid(appId, userId, KILL_APP_REASON_GIDS_CHANGED);
                        }
                    });
                } break;
            }

            mOnPermissionChangeListeners.onPermissionsChanged(uid);

            // Not critical if that is lost - app has to request again.
            mSettings.writeRuntimePermissionsForUserLPr(userId, false);
        }

        // Only need to do this if user is initialized. Otherwise it's a new user
        // and there are no processes running as the user yet and there's no need
        // to make an expensive call to remount processes for the changed permissions.
        if (READ_EXTERNAL_STORAGE.equals(name)
                || WRITE_EXTERNAL_STORAGE.equals(name)) {
            final long token = Binder.clearCallingIdentity();
            try {
                if (sUserManager.isInitialized(userId)) {
                    MountServiceInternal mountServiceInternal = LocalServices.getService(
                            MountServiceInternal.class);
                    mountServiceInternal.onExternalStoragePolicyChanged(uid, packageName);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public void revokeRuntimePermission(String packageName, String name, int userId) {
        if (!sUserManager.exists(userId)) {
            Log.e(TAG, "No such user:" + userId);
            return;
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                "revokeRuntimePermission");

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false,
                "revokeRuntimePermission");

        final int appId;

        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            final BasePermission bp = mSettings.mPermissions.get(name);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + name);
            }

            enforceDeclaredAsUsedAndRuntimePermission(pkg, bp);

            SettingBase sb = (SettingBase) pkg.mExtras;
            if (sb == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            final PermissionsState permissionsState = sb.getPermissionsState();

            final int flags = permissionsState.getPermissionFlags(name, userId);
            if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
                throw new SecurityException("Cannot revoke system fixed permission: "
                        + name + " for package: " + packageName);
            }

            if (permissionsState.revokeRuntimePermission(bp, userId) ==
                    PermissionsState.PERMISSION_OPERATION_FAILURE) {
                return;
            }

            mOnPermissionChangeListeners.onPermissionsChanged(pkg.applicationInfo.uid);

            // Critical, after this call app should never have the permission.
            mSettings.writeRuntimePermissionsForUserLPr(userId, true);

            appId = UserHandle.getAppId(pkg.applicationInfo.uid);
        }

        killUid(appId, userId, KILL_APP_REASON_PERMISSIONS_REVOKED);
    }

    @Override
    public void resetRuntimePermissions() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
                "revokeRuntimePermission");

        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "resetRuntimePermissions");
        }

        synchronized (mPackages) {
            updatePermissionsLPw(null, null, UPDATE_PERMISSIONS_ALL);
            for (int userId : UserManagerService.getInstance().getUserIds()) {
                final int packageCount = mPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    PackageParser.Package pkg = mPackages.valueAt(i);
                    if (!(pkg.mExtras instanceof PackageSetting)) {
                        continue;
                    }
                    PackageSetting ps = (PackageSetting) pkg.mExtras;
                    resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, userId);
                }
            }
        }
    }

    @Override
    public int getPermissionFlags(String name, String packageName, int userId) {
        if (!sUserManager.exists(userId)) {
            return 0;
        }

        enforceGrantRevokeRuntimePermissionPermissions("getPermissionFlags");

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false,
                "getPermissionFlags");

        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            final BasePermission bp = mSettings.mPermissions.get(name);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + name);
            }

            SettingBase sb = (SettingBase) pkg.mExtras;
            if (sb == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            PermissionsState permissionsState = sb.getPermissionsState();
            return permissionsState.getPermissionFlags(name, userId);
        }
    }

    @Override
    public void updatePermissionFlags(String name, String packageName, int flagMask,
            int flagValues, int userId) {
        if (!sUserManager.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlags");

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false,
                "updatePermissionFlags");

        // Only the system can change these flags and nothing else.
        if (getCallingUid() != Process.SYSTEM_UID) {
            flagMask &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagValues &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagMask &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
            flagValues &= ~PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT;
        }

        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            final BasePermission bp = mSettings.mPermissions.get(name);
            if (bp == null) {
                throw new IllegalArgumentException("Unknown permission: " + name);
            }

            SettingBase sb = (SettingBase) pkg.mExtras;
            if (sb == null) {
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }

            PermissionsState permissionsState = sb.getPermissionsState();

            // Only the package manager can change flags for system component permissions.
            final int flags = permissionsState.getPermissionFlags(bp.name, userId);
            if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) != 0) {
                return;
            }

            boolean hadState = permissionsState.getRuntimePermissionState(name, userId) != null;

            if (permissionsState.updatePermissionFlags(bp, userId, flagMask, flagValues)) {
                // Install and runtime permissions are stored in different places,
                // so figure out what permission changed and persist the change.
                if (permissionsState.getInstallPermissionState(name) != null) {
                    scheduleWriteSettingsLocked();
                } else if (permissionsState.getRuntimePermissionState(name, userId) != null
                        || hadState) {
                    mSettings.writeRuntimePermissionsForUserLPr(userId, false);
                }
            }
        }
    }

    /**
     * Update the permission flags for all packages and runtime permissions of a user in order
     * to allow device or profile owner to remove POLICY_FIXED.
     */
    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        if (!sUserManager.exists(userId)) {
            return;
        }

        enforceGrantRevokeRuntimePermissionPermissions("updatePermissionFlagsForAllApps");

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false,
                "updatePermissionFlagsForAllApps");

        // Only the system can change system fixed flags.
        if (getCallingUid() != Process.SYSTEM_UID) {
            flagMask &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
            flagValues &= ~PackageManager.FLAG_PERMISSION_SYSTEM_FIXED;
        }

        synchronized (mPackages) {
            boolean changed = false;
            final int packageCount = mPackages.size();
            for (int pkgIndex = 0; pkgIndex < packageCount; pkgIndex++) {
                final PackageParser.Package pkg = mPackages.valueAt(pkgIndex);
                SettingBase sb = (SettingBase) pkg.mExtras;
                if (sb == null) {
                    continue;
                }
                PermissionsState permissionsState = sb.getPermissionsState();
                changed |= permissionsState.updatePermissionFlagsForAllPermissions(
                        userId, flagMask, flagValues);
            }
            if (changed) {
                mSettings.writeRuntimePermissionsForUserLPr(userId, false);
            }
        }
    }

    private void enforceGrantRevokeRuntimePermissionPermissions(String message) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED
            && mContext.checkCallingOrSelfPermission(Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(message + " requires "
                    + Manifest.permission.GRANT_RUNTIME_PERMISSIONS + " or "
                    + Manifest.permission.REVOKE_RUNTIME_PERMISSIONS);
        }
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String permissionName,
            String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "canShowRequestPermissionRationale for user " + userId);
        }

        final int uid = getPackageUid(packageName, userId);
        if (UserHandle.getAppId(getCallingUid()) != UserHandle.getAppId(uid)) {
            return false;
        }

        if (checkPermission(permissionName, packageName, userId)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        final int flags;

        final long identity = Binder.clearCallingIdentity();
        try {
            flags = getPermissionFlags(permissionName,
                    packageName, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        final int fixedFlags = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
                | PackageManager.FLAG_PERMISSION_POLICY_FIXED
                | PackageManager.FLAG_PERMISSION_USER_FIXED;

        if ((flags & fixedFlags) != 0) {
            return false;
        }

        return (flags & PackageManager.FLAG_PERMISSION_USER_SET) != 0;
    }

    void grantInstallPermissionLPw(String permission, PackageParser.Package pkg) {
        BasePermission bp = mSettings.mPermissions.get(permission);
        if (bp == null) {
            throw new SecurityException("Missing " + permission + " permission");
        }

        SettingBase sb = (SettingBase) pkg.mExtras;
        PermissionsState permissionsState = sb.getPermissionsState();

        if (permissionsState.grantInstallPermission(bp) !=
                PermissionsState.PERMISSION_OPERATION_FAILURE) {
            scheduleWriteSettingsLocked();
        }
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS,
                "addOnPermissionsChangeListener");

        synchronized (mPackages) {
            mOnPermissionChangeListeners.addListenerLocked(listener);
        }
    }

    @Override
    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        synchronized (mPackages) {
            mOnPermissionChangeListeners.removeListenerLocked(listener);
        }
    }

    @Override
    public boolean isProtectedBroadcast(String actionName) {
        synchronized (mPackages) {
            return mProtectedBroadcasts.contains(actionName);
        }
    }

    @Override
    public int checkSignatures(String pkg1, String pkg2) {
        synchronized (mPackages) {
            final PackageParser.Package p1 = mPackages.get(pkg1);
            final PackageParser.Package p2 = mPackages.get(pkg2);
            if (p1 == null || p1.mExtras == null
                    || p2 == null || p2.mExtras == null) {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return compareSignatures(p1.mSignatures, p2.mSignatures);
        }
    }

    @Override
    public int checkUidSignatures(int uid1, int uid2) {
        // Map to base uids.
        uid1 = UserHandle.getAppId(uid1);
        uid2 = UserHandle.getAppId(uid2);
        // reader
        synchronized (mPackages) {
            Signature[] s1;
            Signature[] s2;
            Object obj = mSettings.getUserIdLPr(uid1);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    s1 = ((SharedUserSetting)obj).signatures.mSignatures;
                } else if (obj instanceof PackageSetting) {
                    s1 = ((PackageSetting)obj).signatures.mSignatures;
                } else {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            obj = mSettings.getUserIdLPr(uid2);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    s2 = ((SharedUserSetting)obj).signatures.mSignatures;
                } else if (obj instanceof PackageSetting) {
                    s2 = ((PackageSetting)obj).signatures.mSignatures;
                } else {
                    return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
                }
            } else {
                return PackageManager.SIGNATURE_UNKNOWN_PACKAGE;
            }
            return compareSignatures(s1, s2);
        }
    }

    private void killUid(int appId, int userId, String reason) {
        final long identity = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManagerNative.getDefault();
            if (am != null) {
                try {
                    am.killUid(appId, userId, reason);
                } catch (RemoteException e) {
                    /* ignore - same process */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Compares two sets of signatures. Returns:
     * <br />
     * {@link PackageManager#SIGNATURE_NEITHER_SIGNED}: if both signature sets are null,
     * <br />
     * {@link PackageManager#SIGNATURE_FIRST_NOT_SIGNED}: if the first signature set is null,
     * <br />
     * {@link PackageManager#SIGNATURE_SECOND_NOT_SIGNED}: if the second signature set is null,
     * <br />
     * {@link PackageManager#SIGNATURE_MATCH}: if the two signature sets are identical,
     * <br />
     * {@link PackageManager#SIGNATURE_NO_MATCH}: if the two signature sets differ.
     */
    static int compareSignatures(Signature[] s1, Signature[] s2) {
        if (s1 == null) {
            return s2 == null
                    ? PackageManager.SIGNATURE_NEITHER_SIGNED
                    : PackageManager.SIGNATURE_FIRST_NOT_SIGNED;
        }

        if (s2 == null) {
            return PackageManager.SIGNATURE_SECOND_NOT_SIGNED;
        }

        if (s1.length != s2.length) {
            return PackageManager.SIGNATURE_NO_MATCH;
        }

        // Since both signature sets are of size 1, we can compare without HashSets.
        if (s1.length == 1) {
            return s1[0].equals(s2[0]) ?
                    PackageManager.SIGNATURE_MATCH :
                    PackageManager.SIGNATURE_NO_MATCH;
        }

        ArraySet<Signature> set1 = new ArraySet<Signature>();
        for (Signature sig : s1) {
            set1.add(sig);
        }
        ArraySet<Signature> set2 = new ArraySet<Signature>();
        for (Signature sig : s2) {
            set2.add(sig);
        }
        // Make sure s2 contains all signatures in s1.
        if (set1.equals(set2)) {
            return PackageManager.SIGNATURE_MATCH;
        }
        return PackageManager.SIGNATURE_NO_MATCH;
    }

    /**
     * If the database version for this type of package (internal storage or
     * external storage) is less than the version where package signatures
     * were updated, return true.
     */
    private boolean isCompatSignatureUpdateNeeded(PackageParser.Package scannedPkg) {
        final VersionInfo ver = getSettingsVersionForPackage(scannedPkg);
        return ver.databaseVersion < DatabaseVersion.SIGNATURE_END_ENTITY;
    }

    /**
     * Used for backward compatibility to make sure any packages with
     * certificate chains get upgraded to the new style. {@code existingSigs}
     * will be in the old format (since they were stored on disk from before the
     * system upgrade) and {@code scannedSigs} will be in the newer format.
     */
    private int compareSignaturesCompat(PackageSignatures existingSigs,
            PackageParser.Package scannedPkg) {
        if (!isCompatSignatureUpdateNeeded(scannedPkg)) {
            return PackageManager.SIGNATURE_NO_MATCH;
        }

        ArraySet<Signature> existingSet = new ArraySet<Signature>();
        for (Signature sig : existingSigs.mSignatures) {
            existingSet.add(sig);
        }
        ArraySet<Signature> scannedCompatSet = new ArraySet<Signature>();
        for (Signature sig : scannedPkg.mSignatures) {
            try {
                Signature[] chainSignatures = sig.getChainSignatures();
                for (Signature chainSig : chainSignatures) {
                    scannedCompatSet.add(chainSig);
                }
            } catch (CertificateEncodingException e) {
                scannedCompatSet.add(sig);
            }
        }
        /*
         * Make sure the expanded scanned set contains all signatures in the
         * existing one.
         */
        if (scannedCompatSet.equals(existingSet)) {
            // Migrate the old signatures to the new scheme.
            existingSigs.assignSignatures(scannedPkg.mSignatures);
            // The new KeySets will be re-added later in the scanning process.
            synchronized (mPackages) {
                mSettings.mKeySetManagerService.removeAppKeySetDataLPw(scannedPkg.packageName);
            }
            return PackageManager.SIGNATURE_MATCH;
        }
        return PackageManager.SIGNATURE_NO_MATCH;
    }

    private boolean isRecoverSignatureUpdateNeeded(PackageParser.Package scannedPkg) {
        final VersionInfo ver = getSettingsVersionForPackage(scannedPkg);
        return ver.databaseVersion < DatabaseVersion.SIGNATURE_MALFORMED_RECOVER;
    }

    private int compareSignaturesRecover(PackageSignatures existingSigs,
            PackageParser.Package scannedPkg) {
        if (!isRecoverSignatureUpdateNeeded(scannedPkg)) {
            return PackageManager.SIGNATURE_NO_MATCH;
        }

        String msg = null;
        try {
            if (Signature.areEffectiveMatch(existingSigs.mSignatures, scannedPkg.mSignatures)) {
                logCriticalInfo(Log.INFO, "Recovered effectively matching certificates for "
                        + scannedPkg.packageName);
                return PackageManager.SIGNATURE_MATCH;
            }
        } catch (CertificateException e) {
            msg = e.getMessage();
        }

        logCriticalInfo(Log.INFO,
                "Failed to recover certificates for " + scannedPkg.packageName + ": " + msg);
        return PackageManager.SIGNATURE_NO_MATCH;
    }

    @Override
    public String[] getPackagesForUid(int uid) {
        uid = UserHandle.getAppId(uid);
        // reader
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(uid);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                final int N = sus.packages.size();
                final String[] res = new String[N];
                final Iterator<PackageSetting> it = sus.packages.iterator();
                int i = 0;
                while (it.hasNext()) {
                    res[i++] = it.next().name;
                }
                return res;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return new String[] { ps.name };
            }
        }
        return null;
    }

    @Override
    public String getNameForUid(int uid) {
        // reader
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.name + ":" + sus.userId;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return ps.name;
            }
        }
        return null;
    }

    @Override
    public int getUidForSharedUser(String sharedUserName) {
        if(sharedUserName == null) {
            return -1;
        }
        // reader
        synchronized (mPackages) {
            final SharedUserSetting suid = mSettings.getSharedUserLPw(sharedUserName, 0, 0, false);
            if (suid == null) {
                return -1;
            }
            return suid.userId;
        }
    }

    @Override
    public int getFlagsForUid(int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.pkgFlags;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return ps.pkgFlags;
            }
        }
        return 0;
    }

    @Override
    public int getPrivateFlagsForUid(int uid) {
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(UserHandle.getAppId(uid));
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                return sus.pkgPrivateFlags;
            } else if (obj instanceof PackageSetting) {
                final PackageSetting ps = (PackageSetting) obj;
                return ps.pkgPrivateFlags;
            }
        }
        return 0;
    }

    @Override
    public boolean isUidPrivileged(int uid) {
        uid = UserHandle.getAppId(uid);
        // reader
        synchronized (mPackages) {
            Object obj = mSettings.getUserIdLPr(uid);
            if (obj instanceof SharedUserSetting) {
                final SharedUserSetting sus = (SharedUserSetting) obj;
                final Iterator<PackageSetting> it = sus.packages.iterator();
                while (it.hasNext()) {
                    if (it.next().isPrivileged()) {
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

    @Override
    public String[] getAppOpPermissionPackages(String permissionName) {
        synchronized (mPackages) {
            ArraySet<String> pkgs = mAppOpPermissionPackages.get(permissionName);
            if (pkgs == null) {
                return null;
            }
            return pkgs.toArray(new String[pkgs.size()]);
        }
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType,
            int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "resolve intent");
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, query, userId);
    }

    @Override
    public void setLastChosenActivity(Intent intent, String resolvedType, int flags,
            IntentFilter filter, int match, ComponentName activity) {
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
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
        // Find any earlier preferred or last chosen entries and nuke them
        findPreferredActivity(intent, resolvedType,
                flags, query, 0, false, true, false, userId);
        // Add the new activity as the last chosen for this filter
        addPreferredActivityInternal(filter, match, null, activity, false, userId,
                "Setting last chosen");
    }

    @Override
    public ResolveInfo getLastChosenActivity(Intent intent, String resolvedType, int flags) {
        final int userId = UserHandle.getCallingUserId();
        if (DEBUG_PREFERRED) Log.v(TAG, "Querying last chosen activity for " + intent);
        List<ResolveInfo> query = queryIntentActivities(intent, resolvedType, flags, userId);
        return findPreferredActivity(intent, resolvedType, flags, query, 0,
                false, false, false, userId);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
            int flags, List<ResolveInfo> query, int userId) {
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
                // default, then it is always desireable to pick it.
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
                // If we have saved a preference for a preferred activity for
                // this Intent, use that.
                ResolveInfo ri = findPreferredActivity(intent, resolvedType,
                        flags, query, r0.priority, true, false, debug, userId);
                if (ri != null) {
                    return ri;
                }
                if (userId != 0) {
                    ri = new ResolveInfo(mResolveInfo);
                    ri.activityInfo = new ActivityInfo(ri.activityInfo);
                    ri.activityInfo.applicationInfo = new ApplicationInfo(
                            ri.activityInfo.applicationInfo);
                    ri.activityInfo.applicationInfo.uid = UserHandle.getUid(userId,
                            UserHandle.getAppId(ri.activityInfo.applicationInfo.uid));
                    return ri;
                }
                return mResolveInfo;
            }
        }
        return null;
    }

    private ResolveInfo findPersistentPreferredActivityLP(Intent intent, String resolvedType,
            int flags, List<ResolveInfo> query, boolean debug, int userId) {
        final int N = query.size();
        PersistentPreferredIntentResolver ppir = mSettings.mPersistentPreferredActivities
                .get(userId);
        // Get the list of persistent preferred activities that handle the intent
        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Looking for presistent preferred activities...");
        List<PersistentPreferredActivity> pprefs = ppir != null
                ? ppir.queryIntent(intent, resolvedType,
                        (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId)
                : null;
        if (pprefs != null && pprefs.size() > 0) {
            final int M = pprefs.size();
            for (int i=0; i<M; i++) {
                final PersistentPreferredActivity ppa = pprefs.get(i);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Checking PersistentPreferredActivity ds="
                            + (ppa.countDataSchemes() > 0 ? ppa.getDataScheme(0) : "<none>")
                            + "\n  component=" + ppa.mComponent);
                    ppa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                }
                final ActivityInfo ai = getActivityInfo(ppa.mComponent,
                        flags | PackageManager.GET_DISABLED_COMPONENTS, userId);
                if (DEBUG_PREFERRED || debug) {
                    Slog.v(TAG, "Found persistent preferred activity:");
                    if (ai != null) {
                        ai.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                    } else {
                        Slog.v(TAG, "  null");
                    }
                }
                if (ai == null) {
                    // This previously registered persistent preferred activity
                    // component is no longer known. Ignore it and do NOT remove it.
                    continue;
                }
                for (int j=0; j<N; j++) {
                    final ResolveInfo ri = query.get(j);
                    if (!ri.activityInfo.applicationInfo.packageName
                            .equals(ai.applicationInfo.packageName)) {
                        continue;
                    }
                    if (!ri.activityInfo.name.equals(ai.name)) {
                        continue;
                    }
                    //  Found a persistent preference that can handle the intent.
                    if (DEBUG_PREFERRED || debug) {
                        Slog.v(TAG, "Returning persistent preferred activity: " +
                                ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                    }
                    return ri;
                }
            }
        }
        return null;
    }

    ResolveInfo findPreferredActivity(Intent intent, String resolvedType, int flags,
            List<ResolveInfo> query, int priority, boolean always,
            boolean removeMatches, boolean debug, int userId) {
        if (!sUserManager.exists(userId)) return null;
        // writer
        synchronized (mPackages) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
            }
            if (DEBUG_PREFERRED) intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);

            // Try to find a matching persistent preferred activity.
            ResolveInfo pri = findPersistentPreferredActivityLP(intent, resolvedType, flags, query,
                    debug, userId);

            // If a persistent preferred activity matched, use it.
            if (pri != null) {
                return pri;
            }

            PreferredIntentResolver pir = mSettings.mPreferredActivities.get(userId);
            // Get the list of preferred activities that handle the intent
            if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Looking for preferred activities...");
            List<PreferredActivity> prefs = pir != null
                    ? pir.queryIntent(intent, resolvedType,
                            (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId)
                    : null;
            if (prefs != null && prefs.size() > 0) {
                boolean changed = false;
                try {
                    // First figure out how good the original match set is.
                    // We will only allow preferred activities that came
                    // from the same match quality.
                    int match = 0;

                    if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Figuring out best match...");

                    final int N = query.size();
                    for (int j=0; j<N; j++) {
                        final ResolveInfo ri = query.get(j);
                        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Match for " + ri.activityInfo
                                + ": 0x" + Integer.toHexString(match));
                        if (ri.match > match) {
                            match = ri.match;
                        }
                    }

                    if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Best match: 0x"
                            + Integer.toHexString(match));

                    match &= IntentFilter.MATCH_CATEGORY_MASK;
                    final int M = prefs.size();
                    for (int i=0; i<M; i++) {
                        final PreferredActivity pa = prefs.get(i);
                        if (DEBUG_PREFERRED || debug) {
                            Slog.v(TAG, "Checking PreferredActivity ds="
                                    + (pa.countDataSchemes() > 0 ? pa.getDataScheme(0) : "<none>")
                                    + "\n  component=" + pa.mPref.mComponent);
                            pa.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                        }
                        if (pa.mPref.mMatch != match) {
                            if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping bad match "
                                    + Integer.toHexString(pa.mPref.mMatch));
                            continue;
                        }
                        // If it's not an "always" type preferred activity and that's what we're
                        // looking for, skip it.
                        if (always && !pa.mPref.mAlways) {
                            if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Skipping mAlways=false entry");
                            continue;
                        }
                        final ActivityInfo ai = getActivityInfo(pa.mPref.mComponent,
                                flags | PackageManager.GET_DISABLED_COMPONENTS, userId);
                        if (DEBUG_PREFERRED || debug) {
                            Slog.v(TAG, "Found preferred activity:");
                            if (ai != null) {
                                ai.dump(new LogPrinter(Log.VERBOSE, TAG, Log.LOG_ID_SYSTEM), "  ");
                            } else {
                                Slog.v(TAG, "  null");
                            }
                        }
                        if (ai == null) {
                            // This previously registered preferred activity
                            // component is no longer known.  Most likely an update
                            // to the app was installed and in the new version this
                            // component no longer exists.  Clean it up by removing
                            // it from the preferred activities list, and skip it.
                            Slog.w(TAG, "Removing dangling preferred activity: "
                                    + pa.mPref.mComponent);
                            pir.removeFilter(pa);
                            changed = true;
                            continue;
                        }
                        for (int j=0; j<N; j++) {
                            final ResolveInfo ri = query.get(j);
                            if (!ri.activityInfo.applicationInfo.packageName
                                    .equals(ai.applicationInfo.packageName)) {
                                continue;
                            }
                            if (!ri.activityInfo.name.equals(ai.name)) {
                                continue;
                            }

                            if (removeMatches) {
                                pir.removeFilter(pa);
                                changed = true;
                                if (DEBUG_PREFERRED) {
                                    Slog.v(TAG, "Removing match " + pa.mPref.mComponent);
                                }
                                break;
                            }

                            // Okay we found a previously set preferred or last chosen app.
                            // If the result set is different from when this
                            // was created, we need to clear it and re-ask the
                            // user their preference, if we're looking for an "always" type entry.
                            if (always && !pa.mPref.sameSet(query)) {
                                Slog.i(TAG, "Result set changed, dropping preferred activity for "
                                        + intent + " type " + resolvedType);
                                if (DEBUG_PREFERRED) {
                                    Slog.v(TAG, "Removing preferred activity since set changed "
                                            + pa.mPref.mComponent);
                                }
                                pir.removeFilter(pa);
                                // Re-add the filter as a "last chosen" entry (!always)
                                PreferredActivity lastChosen = new PreferredActivity(
                                        pa, pa.mPref.mMatch, null, pa.mPref.mComponent, false);
                                pir.addFilter(lastChosen);
                                changed = true;
                                return null;
                            }

                            // Yay! Either the set matched or we're looking for the last chosen
                            if (DEBUG_PREFERRED || debug) Slog.v(TAG, "Returning preferred activity: "
                                    + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                            return ri;
                        }
                    }
                } finally {
                    if (changed) {
                        if (DEBUG_PREFERRED) {
                            Slog.v(TAG, "Preferred activity bookkeeping changed; writing restrictions");
                        }
                        scheduleWritePackageRestrictionsLocked(userId);
                    }
                }
            }
        }
        if (DEBUG_PREFERRED || debug) Slog.v(TAG, "No preferred activity to return");
        return null;
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
        if (hasWebURI(intent)) {
            // cross-profile app linking works only towards the parent.
            final UserInfo parent = getProfileParent(sourceUserId);
            synchronized(mPackages) {
                CrossProfileDomainInfo xpDomainInfo = getCrossProfileDomainPreferredLpr(
                        intent, resolvedType, 0, sourceUserId, parent.id);
                return xpDomainInfo != null;
            }
        }
        return false;
    }

    private UserInfo getProfileParent(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return sUserManager.getProfileParent(userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private List<CrossProfileIntentFilter> getMatchingCrossProfileIntentFilters(Intent intent,
            String resolvedType, int userId) {
        CrossProfileIntentResolver resolver = mSettings.mCrossProfileIntentResolvers.get(userId);
        if (resolver != null) {
            return resolver.queryIntent(intent, resolvedType, false, userId);
        }
        return null;
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent,
            String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false, false, "query intent activities");
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            final String pkgName = intent.getPackage();
            if (pkgName == null) {
                List<CrossProfileIntentFilter> matchingFilters =
                        getMatchingCrossProfileIntentFilters(intent, resolvedType, userId);
                // Check for results that need to skip the current profile.
                ResolveInfo xpResolveInfo  = querySkipCurrentProfileIntents(matchingFilters, intent,
                        resolvedType, flags, userId);
                if (xpResolveInfo != null && isUserEnabled(xpResolveInfo.targetUserId)) {
                    List<ResolveInfo> result = new ArrayList<ResolveInfo>(1);
                    result.add(xpResolveInfo);
                    return filterIfNotPrimaryUser(result, userId);
                }

                // Check for results in the current profile.
                List<ResolveInfo> result = mActivities.queryIntent(
                        intent, resolvedType, flags, userId);

                // Check for cross profile results.
                xpResolveInfo = queryCrossProfileIntents(
                        matchingFilters, intent, resolvedType, flags, userId);
                if (xpResolveInfo != null && isUserEnabled(xpResolveInfo.targetUserId)) {
                    result.add(xpResolveInfo);
                    Collections.sort(result, mResolvePrioritySorter);
                }
                result = filterIfNotPrimaryUser(result, userId);
                if (hasWebURI(intent)) {
                    CrossProfileDomainInfo xpDomainInfo = null;
                    final UserInfo parent = getProfileParent(userId);
                    if (parent != null) {
                        xpDomainInfo = getCrossProfileDomainPreferredLpr(intent, resolvedType,
                                flags, userId, parent.id);
                    }
                    if (xpDomainInfo != null) {
                        if (xpResolveInfo != null) {
                            // If we didn't remove it, the cross-profile ResolveInfo would be twice
                            // in the result.
                            result.remove(xpResolveInfo);
                        }
                        if (result.size() == 0) {
                            result.add(xpDomainInfo.resolveInfo);
                            return result;
                        }
                    } else if (result.size() <= 1) {
                        return result;
                    }
                    result = filterCandidatesWithDomainPreferredActivitiesLPr(intent, flags, result,
                            xpDomainInfo, userId);
                    Collections.sort(result, mResolvePrioritySorter);
                }
                return result;
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return filterIfNotPrimaryUser(
                        mActivities.queryIntentForPackage(
                                intent, resolvedType, flags, pkg.activities, userId),
                        userId);
            }
            return new ArrayList<ResolveInfo>();
        }
    }

    private static class CrossProfileDomainInfo {
        /* ResolveInfo for IntentForwarderActivity to send the intent to the other profile */
        ResolveInfo resolveInfo;
        /* Best domain verification status of the activities found in the other profile */
        int bestDomainVerificationStatus;
    }

    private CrossProfileDomainInfo getCrossProfileDomainPreferredLpr(Intent intent,
            String resolvedType, int flags, int sourceUserId, int parentUserId) {
        if (!sUserManager.hasUserRestriction(UserManager.ALLOW_PARENT_PROFILE_APP_LINKING,
                sourceUserId)) {
            return null;
        }
        List<ResolveInfo> resultTargetUser = mActivities.queryIntent(intent,
                resolvedType, flags, parentUserId);

        if (resultTargetUser == null || resultTargetUser.isEmpty()) {
            return null;
        }
        CrossProfileDomainInfo result = null;
        int size = resultTargetUser.size();
        for (int i = 0; i < size; i++) {
            ResolveInfo riTargetUser = resultTargetUser.get(i);
            // Intent filter verification is only for filters that specify a host. So don't return
            // those that handle all web uris.
            if (riTargetUser.handleAllWebDataURI) {
                continue;
            }
            String packageName = riTargetUser.activityInfo.packageName;
            PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                continue;
            }
            long verificationState = getDomainVerificationStatusLPr(ps, parentUserId);
            int status = (int)(verificationState >> 32);
            if (result == null) {
                result = new CrossProfileDomainInfo();
                result.resolveInfo =
                        createForwardingResolveInfo(null, sourceUserId, parentUserId);
                result.bestDomainVerificationStatus = status;
            } else {
                result.bestDomainVerificationStatus = bestDomainVerificationStatus(status,
                        result.bestDomainVerificationStatus);
            }
        }
        // Don't consider matches with status NEVER across profiles.
        if (result != null && result.bestDomainVerificationStatus
                == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
            return null;
        }
        return result;
    }

    /**
     * Verification statuses are ordered from the worse to the best, except for
     * INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER, which is the worse.
     */
    private int bestDomainVerificationStatus(int status1, int status2) {
        if (status1 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
            return status2;
        }
        if (status2 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
            return status1;
        }
        return (int) MathUtils.max(status1, status2);
    }

    private boolean isUserEnabled(int userId) {
        long callingId = Binder.clearCallingIdentity();
        try {
            UserInfo userInfo = sUserManager.getUserInfo(userId);
            return userInfo != null && userInfo.isEnabled();
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Filter out activities with primaryUserOnly flag set, when current user is not the owner.
     *
     * @return filtered list
     */
    private List<ResolveInfo> filterIfNotPrimaryUser(List<ResolveInfo> resolveInfos, int userId) {
        if (userId == UserHandle.USER_OWNER) {
            return resolveInfos;
        }
        for (int i = resolveInfos.size() - 1; i >= 0; i--) {
            ResolveInfo info = resolveInfos.get(i);
            if ((info.activityInfo.flags & ActivityInfo.FLAG_PRIMARY_USER_ONLY) != 0) {
                resolveInfos.remove(i);
            }
        }
        return resolveInfos;
    }

    private static boolean hasWebURI(Intent intent) {
        if (intent.getData() == null) {
            return false;
        }
        final String scheme = intent.getScheme();
        if (TextUtils.isEmpty(scheme)) {
            return false;
        }
        return scheme.equals(IntentFilter.SCHEME_HTTP) || scheme.equals(IntentFilter.SCHEME_HTTPS);
    }

    private List<ResolveInfo> filterCandidatesWithDomainPreferredActivitiesLPr(Intent intent,
            int matchFlags, List<ResolveInfo> candidates, CrossProfileDomainInfo xpDomainInfo,
            int userId) {
        final boolean debug = (intent.getFlags() & Intent.FLAG_DEBUG_LOG_RESOLUTION) != 0;

        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtering results with preferred activities. Candidates count: " +
                    candidates.size());
        }

        ArrayList<ResolveInfo> result = new ArrayList<ResolveInfo>();
        ArrayList<ResolveInfo> alwaysList = new ArrayList<ResolveInfo>();
        ArrayList<ResolveInfo> undefinedList = new ArrayList<ResolveInfo>();
        ArrayList<ResolveInfo> neverList = new ArrayList<ResolveInfo>();
        ArrayList<ResolveInfo> matchAllList = new ArrayList<ResolveInfo>();

        synchronized (mPackages) {
            final int count = candidates.size();
            // First, try to use linked apps. Partition the candidates into four lists:
            // one for the final results, one for the "do not use ever", one for "undefined status"
            // and finally one for "browser app type".
            for (int n=0; n<count; n++) {
                ResolveInfo info = candidates.get(n);
                String packageName = info.activityInfo.packageName;
                PackageSetting ps = mSettings.mPackages.get(packageName);
                if (ps != null) {
                    // Add to the special match all list (Browser use case)
                    if (info.handleAllWebDataURI) {
                        matchAllList.add(info);
                        continue;
                    }
                    // Try to get the status from User settings first
                    long packedStatus = getDomainVerificationStatusLPr(ps, userId);
                    int status = (int)(packedStatus >> 32);
                    int linkGeneration = (int)(packedStatus & 0xFFFFFFFF);
                    if (status == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.i(TAG, "  + always: " + info.activityInfo.packageName
                                    + " : linkgen=" + linkGeneration);
                        }
                        // Use link-enabled generation as preferredOrder, i.e.
                        // prefer newly-enabled over earlier-enabled.
                        info.preferredOrder = linkGeneration;
                        alwaysList.add(info);
                    } else if (status == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER) {
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.i(TAG, "  + never: " + info.activityInfo.packageName);
                        }
                        neverList.add(info);
                    } else if (status == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED ||
                            status == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK) {
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.i(TAG, "  + ask: " + info.activityInfo.packageName);
                        }
                        undefinedList.add(info);
                    }
                }
            }
            // First try to add the "always" resolution(s) for the current user, if any
            if (alwaysList.size() > 0) {
                result.addAll(alwaysList);
            // if there is an "always" for the parent user, add it.
            } else if (xpDomainInfo != null && xpDomainInfo.bestDomainVerificationStatus
                    == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS) {
                result.add(xpDomainInfo.resolveInfo);
            } else {
                // Add all undefined Apps as we want them to appear in the Disambiguation dialog.
                result.addAll(undefinedList);
                if (xpDomainInfo != null && (
                        xpDomainInfo.bestDomainVerificationStatus
                        == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED
                        || xpDomainInfo.bestDomainVerificationStatus
                        == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK)) {
                    result.add(xpDomainInfo.resolveInfo);
                }
                // Also add Browsers (all of them or only the default one)
                if ((matchFlags & MATCH_ALL) != 0) {
                    result.addAll(matchAllList);
                } else {
                    // Browser/generic handling case.  If there's a default browser, go straight
                    // to that (but only if there is no other higher-priority match).
                    final String defaultBrowserPackageName = getDefaultBrowserPackageName(userId);
                    int maxMatchPrio = 0;
                    ResolveInfo defaultBrowserMatch = null;
                    final int numCandidates = matchAllList.size();
                    for (int n = 0; n < numCandidates; n++) {
                        ResolveInfo info = matchAllList.get(n);
                        // track the highest overall match priority...
                        if (info.priority > maxMatchPrio) {
                            maxMatchPrio = info.priority;
                        }
                        // ...and the highest-priority default browser match
                        if (info.activityInfo.packageName.equals(defaultBrowserPackageName)) {
                            if (defaultBrowserMatch == null
                                    || (defaultBrowserMatch.priority < info.priority)) {
                                if (debug) {
                                    Slog.v(TAG, "Considering default browser match " + info);
                                }
                                defaultBrowserMatch = info;
                            }
                        }
                    }
                    if (defaultBrowserMatch != null
                            && defaultBrowserMatch.priority >= maxMatchPrio
                            && !TextUtils.isEmpty(defaultBrowserPackageName))
                    {
                        if (debug) {
                            Slog.v(TAG, "Default browser match " + defaultBrowserMatch);
                        }
                        result.add(defaultBrowserMatch);
                    } else {
                        result.addAll(matchAllList);
                    }
                }

                // If there is nothing selected, add all candidates and remove the ones that the user
                // has explicitly put into the INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER state
                if (result.size() == 0) {
                    result.addAll(candidates);
                    result.removeAll(neverList);
                }
            }
        }
        if (DEBUG_PREFERRED || DEBUG_DOMAIN_VERIFICATION) {
            Slog.v(TAG, "Filtered results with preferred activities. New candidates count: " +
                    result.size());
            for (ResolveInfo info : result) {
                Slog.v(TAG, "  + " + info.activityInfo);
            }
        }
        return result;
    }

    // Returns a packed value as a long:
    //
    // high 'int'-sized word: link status: undefined/ask/never/always.
    // low 'int'-sized word: relative priority among 'always' results.
    private long getDomainVerificationStatusLPr(PackageSetting ps, int userId) {
        long result = ps.getDomainVerificationStatusForUser(userId);
        // if none available, get the master status
        if (result >> 32 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED) {
            if (ps.getIntentFilterVerificationInfo() != null) {
                result = ((long)ps.getIntentFilterVerificationInfo().getStatus()) << 32;
            }
        }
        return result;
    }

    private ResolveInfo querySkipCurrentProfileIntents(
            List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
            int flags, int sourceUserId) {
        if (matchingFilters != null) {
            int size = matchingFilters.size();
            for (int i = 0; i < size; i ++) {
                CrossProfileIntentFilter filter = matchingFilters.get(i);
                if ((filter.getFlags() & PackageManager.SKIP_CURRENT_PROFILE) != 0) {
                    // Checking if there are activities in the target user that can handle the
                    // intent.
                    ResolveInfo resolveInfo = checkTargetCanHandle(filter, intent, resolvedType,
                            flags, sourceUserId);
                    if (resolveInfo != null) {
                        return resolveInfo;
                    }
                }
            }
        }
        return null;
    }

    // Return matching ResolveInfo if any for skip current profile intent filters.
    private ResolveInfo queryCrossProfileIntents(
            List<CrossProfileIntentFilter> matchingFilters, Intent intent, String resolvedType,
            int flags, int sourceUserId) {
        if (matchingFilters != null) {
            // Two {@link CrossProfileIntentFilter}s can have the same targetUserId and
            // match the same intent. For performance reasons, it is better not to
            // run queryIntent twice for the same userId
            SparseBooleanArray alreadyTriedUserIds = new SparseBooleanArray();
            int size = matchingFilters.size();
            for (int i = 0; i < size; i++) {
                CrossProfileIntentFilter filter = matchingFilters.get(i);
                int targetUserId = filter.getTargetUserId();
                if ((filter.getFlags() & PackageManager.SKIP_CURRENT_PROFILE) == 0
                        && !alreadyTriedUserIds.get(targetUserId)) {
                    // Checking if there are activities in the target user that can handle the
                    // intent.
                    ResolveInfo resolveInfo = checkTargetCanHandle(filter, intent, resolvedType,
                            flags, sourceUserId);
                    if (resolveInfo != null) return resolveInfo;
                    alreadyTriedUserIds.put(targetUserId, true);
                }
            }
        }
        return null;
    }

    private ResolveInfo checkTargetCanHandle(CrossProfileIntentFilter filter, Intent intent,
            String resolvedType, int flags, int sourceUserId) {
        List<ResolveInfo> resultTargetUser = mActivities.queryIntent(intent,
                resolvedType, flags, filter.getTargetUserId());
        if (resultTargetUser != null && !resultTargetUser.isEmpty()) {
            return createForwardingResolveInfo(filter, sourceUserId, filter.getTargetUserId());
        }
        return null;
    }

    private ResolveInfo createForwardingResolveInfo(IntentFilter filter,
            int sourceUserId, int targetUserId) {
        ResolveInfo forwardingResolveInfo = new ResolveInfo();
        String className;
        if (targetUserId == UserHandle.USER_OWNER) {
            className = FORWARD_INTENT_TO_USER_OWNER;
        } else {
            className = FORWARD_INTENT_TO_MANAGED_PROFILE;
        }
        ComponentName forwardingActivityComponentName = new ComponentName(
                mAndroidApplication.packageName, className);
        ActivityInfo forwardingActivityInfo = getActivityInfo(forwardingActivityComponentName, 0,
                sourceUserId);
        if (targetUserId == UserHandle.USER_OWNER) {
            forwardingActivityInfo.showUserIcon = UserHandle.USER_OWNER;
            forwardingResolveInfo.noResourceId = true;
        }
        forwardingResolveInfo.activityInfo = forwardingActivityInfo;
        forwardingResolveInfo.priority = 0;
        forwardingResolveInfo.preferredOrder = 0;
        forwardingResolveInfo.match = 0;
        forwardingResolveInfo.isDefault = true;
        forwardingResolveInfo.filter = filter;
        forwardingResolveInfo.targetUserId = targetUserId;
        return forwardingResolveInfo;
    }

    @Override
    public List<ResolveInfo> queryIntentActivityOptions(ComponentName caller,
            Intent[] specifics, String[] specificTypes, Intent intent,
            String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        enforceCrossUserPermission(Binder.getCallingUid(), userId, false,
                false, "query intent activity options");
        final String resultsAction = intent.getAction();

        List<ResolveInfo> results = queryIntentActivities(intent, resolvedType, flags
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
    public List<ResolveInfo> queryIntentReceivers(Intent intent, String resolvedType, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mReceivers.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mReceivers.queryIntentForPackage(intent, resolvedType, flags, pkg.receivers,
                        userId);
            }
            return null;
        }
    }

    @Override
    public ResolveInfo resolveService(Intent intent, String resolvedType, int flags, int userId) {
        List<ResolveInfo> query = queryIntentServices(intent, resolvedType, flags, userId);
        if (!sUserManager.exists(userId)) return null;
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
    public List<ResolveInfo> queryIntentServices(Intent intent, String resolvedType, int flags,
            int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                final ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mServices.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mServices.queryIntentForPackage(intent, resolvedType, flags, pkg.services,
                        userId);
            }
            return null;
        }
    }

    @Override
    public List<ResolveInfo> queryIntentContentProviders(
            Intent intent, String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<ResolveInfo>(1);
            final ProviderInfo pi = getProviderInfo(comp, flags, userId);
            if (pi != null) {
                final ResolveInfo ri = new ResolveInfo();
                ri.providerInfo = pi;
                list.add(ri);
            }
            return list;
        }

        // reader
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName == null) {
                return mProviders.queryIntent(intent, resolvedType, flags, userId);
            }
            final PackageParser.Package pkg = mPackages.get(pkgName);
            if (pkg != null) {
                return mProviders.queryIntentForPackage(
                        intent, resolvedType, flags, pkg.providers, userId);
            }
            return null;
        }
    }

    @Override
    public ParceledListSlice<PackageInfo> getInstalledPackages(int flags, int userId) {
        final boolean listUninstalled = (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;

        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "get installed packages");

        // writer
        synchronized (mPackages) {
            ArrayList<PackageInfo> list;
            if (listUninstalled) {
                list = new ArrayList<PackageInfo>(mSettings.mPackages.size());
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    PackageInfo pi;
                    if (ps.pkg != null) {
                        pi = generatePackageInfo(ps.pkg, flags, userId);
                    } else {
                        pi = generatePackageInfoFromSettingsLPw(ps.name, flags, userId);
                    }
                    if (pi != null) {
                        list.add(pi);
                    }
                }
            } else {
                list = new ArrayList<PackageInfo>(mPackages.size());
                for (PackageParser.Package p : mPackages.values()) {
                    PackageInfo pi = generatePackageInfo(p, flags, userId);
                    if (pi != null) {
                        list.add(pi);
                    }
                }
            }

            return new ParceledListSlice<PackageInfo>(list);
        }
    }

    private void addPackageHoldingPermissions(ArrayList<PackageInfo> list, PackageSetting ps,
            String[] permissions, boolean[] tmp, int flags, int userId) {
        int numMatch = 0;
        final PermissionsState permissionsState = ps.getPermissionsState();
        for (int i=0; i<permissions.length; i++) {
            final String permission = permissions[i];
            if (permissionsState.hasPermission(permission, userId)) {
                tmp[i] = true;
                numMatch++;
            } else {
                tmp[i] = false;
            }
        }
        if (numMatch == 0) {
            return;
        }
        PackageInfo pi;
        if (ps.pkg != null) {
            pi = generatePackageInfo(ps.pkg, flags, userId);
        } else {
            pi = generatePackageInfoFromSettingsLPw(ps.name, flags, userId);
        }
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
        if (!sUserManager.exists(userId)) return null;
        final boolean listUninstalled = (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;

        // writer
        synchronized (mPackages) {
            ArrayList<PackageInfo> list = new ArrayList<PackageInfo>();
            boolean[] tmpBools = new boolean[permissions.length];
            if (listUninstalled) {
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags, userId);
                }
            } else {
                for (PackageParser.Package pkg : mPackages.values()) {
                    PackageSetting ps = (PackageSetting)pkg.mExtras;
                    if (ps != null) {
                        addPackageHoldingPermissions(list, ps, permissions, tmpBools, flags,
                                userId);
                    }
                }
            }

            return new ParceledListSlice<PackageInfo>(list);
        }
    }

    @Override
    public ParceledListSlice<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        final boolean listUninstalled = (flags & PackageManager.GET_UNINSTALLED_PACKAGES) != 0;

        // writer
        synchronized (mPackages) {
            ArrayList<ApplicationInfo> list;
            if (listUninstalled) {
                list = new ArrayList<ApplicationInfo>(mSettings.mPackages.size());
                for (PackageSetting ps : mSettings.mPackages.values()) {
                    ApplicationInfo ai;
                    if (ps.pkg != null) {
                        ai = PackageParser.generateApplicationInfo(ps.pkg, flags,
                                ps.readUserState(userId), userId);
                    } else {
                        ai = generateApplicationInfoFromSettingsLPw(ps.name, flags, userId);
                    }
                    if (ai != null) {
                        list.add(ai);
                    }
                }
            } else {
                list = new ArrayList<ApplicationInfo>(mPackages.size());
                for (PackageParser.Package p : mPackages.values()) {
                    if (p.mExtras != null) {
                        ApplicationInfo ai = PackageParser.generateApplicationInfo(p, flags,
                                ((PackageSetting)p.mExtras).readUserState(userId), userId);
                        if (ai != null) {
                            list.add(ai);
                        }
                    }
                }
            }

            return new ParceledListSlice<ApplicationInfo>(list);
        }
    }

    public List<ApplicationInfo> getPersistentApplications(int flags) {
        final ArrayList<ApplicationInfo> finalList = new ArrayList<ApplicationInfo>();

        // reader
        synchronized (mPackages) {
            final Iterator<PackageParser.Package> i = mPackages.values().iterator();
            final int userId = UserHandle.getCallingUserId();
            while (i.hasNext()) {
                final PackageParser.Package p = i.next();
                if (p.applicationInfo != null
                        && (p.applicationInfo.flags&ApplicationInfo.FLAG_PERSISTENT) != 0
                        && (!mSafeMode || isSystemApp(p))) {
                    PackageSetting ps = mSettings.mPackages.get(p.packageName);
                    if (ps != null) {
                        ApplicationInfo ai = PackageParser.generateApplicationInfo(p, flags,
                                ps.readUserState(userId), userId);
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
        if (!sUserManager.exists(userId)) return null;
        // reader
        synchronized (mPackages) {
            final PackageParser.Provider provider = mProvidersByAuthority.get(name);
            PackageSetting ps = provider != null
                    ? mSettings.mPackages.get(provider.owner.packageName)
                    : null;
            return ps != null
                    && mSettings.isEnabledLPr(provider.info, flags, userId)
                    && (!mSafeMode || (provider.info.applicationInfo.flags
                            &ApplicationInfo.FLAG_SYSTEM) != 0)
                    ? PackageParser.generateProviderInfo(provider, flags,
                            ps.readUserState(userId), userId)
                    : null;
        }
    }

    /**
     * @deprecated
     */
    @Deprecated
    public void querySyncProviders(List<String> outNames, List<ProviderInfo> outInfo) {
        // reader
        synchronized (mPackages) {
            final Iterator<Map.Entry<String, PackageParser.Provider>> i = mProvidersByAuthority
                    .entrySet().iterator();
            final int userId = UserHandle.getCallingUserId();
            while (i.hasNext()) {
                Map.Entry<String, PackageParser.Provider> entry = i.next();
                PackageParser.Provider p = entry.getValue();
                PackageSetting ps = mSettings.mPackages.get(p.owner.packageName);

                if (ps != null && p.syncable
                        && (!mSafeMode || (p.info.applicationInfo.flags
                                &ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    ProviderInfo info = PackageParser.generateProviderInfo(p, 0,
                            ps.readUserState(userId), userId);
                    if (info != null) {
                        outNames.add(entry.getKey());
                        outInfo.add(info);
                    }
                }
            }
        }
    }

    @Override
    public ParceledListSlice<ProviderInfo> queryContentProviders(String processName,
            int uid, int flags) {
        ArrayList<ProviderInfo> finalList = null;
        // reader
        synchronized (mPackages) {
            final Iterator<PackageParser.Provider> i = mProviders.mProviders.values().iterator();
            final int userId = processName != null ?
                    UserHandle.getUserId(uid) : UserHandle.getCallingUserId();
            while (i.hasNext()) {
                final PackageParser.Provider p = i.next();
                PackageSetting ps = mSettings.mPackages.get(p.owner.packageName);
                if (ps != null && p.info.authority != null
                        && (processName == null
                                || (p.info.processName.equals(processName)
                                        && UserHandle.isSameApp(p.info.applicationInfo.uid, uid)))
                        && mSettings.isEnabledLPr(p.info, flags, userId)
                        && (!mSafeMode
                                || (p.info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0)) {
                    if (finalList == null) {
                        finalList = new ArrayList<ProviderInfo>(3);
                    }
                    ProviderInfo info = PackageParser.generateProviderInfo(p, flags,
                            ps.readUserState(userId), userId);
                    if (info != null) {
                        finalList.add(info);
                    }
                }
            }
        }

        if (finalList != null) {
            Collections.sort(finalList, mProviderInitOrderSorter);
            return new ParceledListSlice<ProviderInfo>(finalList);
        }

        return null;
    }

    @Override
    public InstrumentationInfo getInstrumentationInfo(ComponentName name,
            int flags) {
        // reader
        synchronized (mPackages) {
            final PackageParser.Instrumentation i = mInstrumentation.get(name);
            return PackageParser.generateInstrumentationInfo(i, flags);
        }
    }

    @Override
    public List<InstrumentationInfo> queryInstrumentation(String targetPackage,
            int flags) {
        ArrayList<InstrumentationInfo> finalList =
            new ArrayList<InstrumentationInfo>();

        // reader
        synchronized (mPackages) {
            final Iterator<PackageParser.Instrumentation> i = mInstrumentation.values().iterator();
            while (i.hasNext()) {
                final PackageParser.Instrumentation p = i.next();
                if (targetPackage == null
                        || targetPackage.equals(p.info.targetPackage)) {
                    InstrumentationInfo ii = PackageParser.generateInstrumentationInfo(p,
                            flags);
                    if (ii != null) {
                        finalList.add(ii);
                    }
                }
            }
        }

        return finalList;
    }

    private void createIdmapsForPackageLI(PackageParser.Package pkg) {
        ArrayMap<String, PackageParser.Package> overlays = mOverlays.get(pkg.packageName);
        if (overlays == null) {
            Slog.w(TAG, "Unable to create idmap for " + pkg.packageName + ": no overlay packages");
            return;
        }
        for (PackageParser.Package opkg : overlays.values()) {
            // Not much to do if idmap fails: we already logged the error
            // and we certainly don't want to abort installation of pkg simply
            // because an overlay didn't fit properly. For these reasons,
            // ignore the return value of createIdmapForPackagePairLI.
            createIdmapForPackagePairLI(pkg, opkg);
        }
    }

    private boolean createIdmapForPackagePairLI(PackageParser.Package pkg,
            PackageParser.Package opkg) {
        if (!opkg.mTrustedOverlay) {
            Slog.w(TAG, "Skipping target and overlay pair " + pkg.baseCodePath + " and " +
                    opkg.baseCodePath + ": overlay not trusted");
            return false;
        }
        ArrayMap<String, PackageParser.Package> overlaySet = mOverlays.get(pkg.packageName);
        if (overlaySet == null) {
            Slog.e(TAG, "was about to create idmap for " + pkg.baseCodePath + " and " +
                    opkg.baseCodePath + " but target package has no known overlays");
            return false;
        }
        final int sharedGid = UserHandle.getSharedAppGid(pkg.applicationInfo.uid);
        // TODO: generate idmap for split APKs
        if (mInstaller.idmap(pkg.baseCodePath, opkg.baseCodePath, sharedGid) != 0) {
            Slog.e(TAG, "Failed to generate idmap for " + pkg.baseCodePath + " and "
                    + opkg.baseCodePath);
            return false;
        }
        PackageParser.Package[] overlayArray =
            overlaySet.values().toArray(new PackageParser.Package[0]);
        Comparator<PackageParser.Package> cmp = new Comparator<PackageParser.Package>() {
            public int compare(PackageParser.Package p1, PackageParser.Package p2) {
                return p1.mOverlayPriority - p2.mOverlayPriority;
            }
        };
        Arrays.sort(overlayArray, cmp);

        pkg.applicationInfo.resourceDirs = new String[overlayArray.length];
        int i = 0;
        for (PackageParser.Package p : overlayArray) {
            pkg.applicationInfo.resourceDirs[i++] = p.baseCodePath;
        }
        return true;
    }

    private void scanDirLI(File dir, int parseFlags, int scanFlags, long currentTime) {
        final File[] files = dir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            Log.d(TAG, "No files in app dir " + dir);
            return;
        }

        if (DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "Scanning app dir " + dir + " scanFlags=" + scanFlags
                    + " flags=0x" + Integer.toHexString(parseFlags));
        }

        for (File file : files) {
            final boolean isPackage = (isApkFile(file) || file.isDirectory())
                    && !PackageInstallerService.isStageName(file.getName());
            if (!isPackage) {
                // Ignore entries which are not packages
                continue;
            }
            try {
                scanPackageLI(file, parseFlags | PackageParser.PARSE_MUST_BE_APK,
                        scanFlags, currentTime, null);
            } catch (PackageManagerException e) {
                Slog.w(TAG, "Failed to parse " + file + ": " + e.getMessage());

                // Delete invalid userdata apps
                if ((parseFlags & PackageParser.PARSE_IS_SYSTEM) == 0 &&
                        e.error == PackageManager.INSTALL_FAILED_INVALID_APK) {
                    logCriticalInfo(Log.WARN, "Deleting invalid package at " + file);
                    if (file.isDirectory()) {
                        mInstaller.rmPackageDir(file.getAbsolutePath());
                    } else {
                        file.delete();
                    }
                }
            }
        }
    }

    private static File getSettingsProblemFile() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File fname = new File(systemDir, "uiderrors.txt");
        return fname;
    }

    static void reportSettingsProblem(int priority, String msg) {
        logCriticalInfo(priority, msg);
    }

    static void logCriticalInfo(int priority, String msg) {
        Slog.println(priority, TAG, msg);
        EventLogTags.writePmCriticalInfo(msg);
        try {
            File fname = getSettingsProblemFile();
            FileOutputStream out = new FileOutputStream(fname, true);
            PrintWriter pw = new FastPrintWriter(out);
            SimpleDateFormat formatter = new SimpleDateFormat();
            String dateString = formatter.format(new Date(System.currentTimeMillis()));
            pw.println(dateString + ": " + msg);
            pw.close();
            FileUtils.setPermissions(
                    fname.toString(),
                    FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IROTH,
                    -1, -1);
        } catch (java.io.IOException e) {
        }
    }

    private void collectCertificatesLI(PackageParser pp, PackageSetting ps,
            PackageParser.Package pkg, File srcFile, int parseFlags)
            throws PackageManagerException {
        if (ps != null
                && ps.codePath.equals(srcFile)
                && ps.timeStamp == srcFile.lastModified()
                && !isCompatSignatureUpdateNeeded(pkg)
                && !isRecoverSignatureUpdateNeeded(pkg)) {
            long mSigningKeySetId = ps.keySetData.getProperSigningKeySet();
            KeySetManagerService ksms = mSettings.mKeySetManagerService;
            ArraySet<PublicKey> signingKs;
            synchronized (mPackages) {
                signingKs = ksms.getPublicKeysFromKeySetLPr(mSigningKeySetId);
            }
            if (ps.signatures.mSignatures != null
                    && ps.signatures.mSignatures.length != 0
                    && signingKs != null) {
                // Optimization: reuse the existing cached certificates
                // if the package appears to be unchanged.
                pkg.mSignatures = ps.signatures.mSignatures;
                pkg.mSigningKeys = signingKs;
                return;
            }

            Slog.w(TAG, "PackageSetting for " + ps.name
                    + " is missing signatures.  Collecting certs again to recover them.");
        } else {
            Log.i(TAG, srcFile.toString() + " changed; collecting certs");
        }

        try {
            pp.collectCertificates(pkg, parseFlags);
            pp.collectManifestDigest(pkg);
        } catch (PackageParserException e) {
            throw PackageManagerException.from(e);
        }
    }

    /*
     *  Scan a package and return the newly parsed package.
     *  Returns null in case of errors and the error code is stored in mLastScanError
     */
    private PackageParser.Package scanPackageLI(File scanFile, int parseFlags, int scanFlags,
            long currentTime, UserHandle user) throws PackageManagerException {
        if (DEBUG_INSTALL) Slog.d(TAG, "Parsing: " + scanFile);
        parseFlags |= mDefParseFlags;
        PackageParser pp = new PackageParser();
        pp.setSeparateProcesses(mSeparateProcesses);
        pp.setOnlyCoreApps(mOnlyCore);
        pp.setDisplayMetrics(mMetrics);

        if ((scanFlags & SCAN_TRUSTED_OVERLAY) != 0) {
            parseFlags |= PackageParser.PARSE_TRUSTED_OVERLAY;
        }

        final PackageParser.Package pkg;
        try {
            pkg = pp.parsePackage(scanFile, parseFlags);
        } catch (PackageParserException e) {
            throw PackageManagerException.from(e);
        }

        PackageSetting ps = null;
        PackageSetting updatedPkg;
        // reader
        synchronized (mPackages) {
            // Look to see if we already know about this package.
            String oldName = mSettings.mRenamedPackages.get(pkg.packageName);
            if (pkg.mOriginalPackages != null && pkg.mOriginalPackages.contains(oldName)) {
                // This package has been renamed to its original name.  Let's
                // use that.
                ps = mSettings.peekPackageLPr(oldName);
            }
            // If there was no original package, see one for the real package name.
            if (ps == null) {
                ps = mSettings.peekPackageLPr(pkg.packageName);
            }
            // Check to see if this package could be hiding/updating a system
            // package.  Must look for it either under the original or real
            // package name depending on our state.
            updatedPkg = mSettings.getDisabledSystemPkgLPr(ps != null ? ps.name : pkg.packageName);
            if (DEBUG_INSTALL && updatedPkg != null) Slog.d(TAG, "updatedPkg = " + updatedPkg);
        }
        boolean updatedPkgBetter = false;
        // First check if this is a system package that may involve an update
        if (updatedPkg != null && (parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            // If new package is not located in "/system/priv-app" (e.g. due to an OTA),
            // it needs to drop FLAG_PRIVILEGED.
            if (locationIsPrivileged(scanFile)) {
                updatedPkg.pkgPrivateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
            } else {
                updatedPkg.pkgPrivateFlags &= ~ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
            }

            if (ps != null && !ps.codePath.equals(scanFile)) {
                // The path has changed from what was last scanned...  check the
                // version of the new path against what we have stored to determine
                // what to do.
                if (DEBUG_INSTALL) Slog.d(TAG, "Path changing from " + ps.codePath);
                if (pkg.mVersionCode <= ps.versionCode) {
                    // The system package has been updated and the code path does not match
                    // Ignore entry. Skip it.
                    if (DEBUG_INSTALL) Slog.i(TAG, "Package " + ps.name + " at " + scanFile
                            + " ignored: updated version " + ps.versionCode
                            + " better than this " + pkg.mVersionCode);
                    if (!updatedPkg.codePath.equals(scanFile)) {
                        Slog.w(PackageManagerService.TAG, "Code path for hidden system pkg : "
                                + ps.name + " changing from " + updatedPkg.codePathString
                                + " to " + scanFile);
                        updatedPkg.codePath = scanFile;
                        updatedPkg.codePathString = scanFile.toString();
                        updatedPkg.resourcePath = scanFile;
                        updatedPkg.resourcePathString = scanFile.toString();
                    }
                    updatedPkg.pkg = pkg;
                    throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                            "Package " + ps.name + " at " + scanFile
                                    + " ignored: updated version " + ps.versionCode
                                    + " better than this " + pkg.mVersionCode);
                } else {
                    // The current app on the system partition is better than
                    // what we have updated to on the data partition; switch
                    // back to the system partition version.
                    // At this point, its safely assumed that package installation for
                    // apps in system partition will go through. If not there won't be a working
                    // version of the app
                    // writer
                    synchronized (mPackages) {
                        // Just remove the loaded entries from package lists.
                        mPackages.remove(ps.name);
                    }

                    logCriticalInfo(Log.WARN, "Package " + ps.name + " at " + scanFile
                            + " reverting from " + ps.codePathString
                            + ": new version " + pkg.mVersionCode
                            + " better than installed " + ps.versionCode);

                    InstallArgs args = createInstallArgsForExisting(packageFlagsToInstallFlags(ps),
                            ps.codePathString, ps.resourcePathString, getAppDexInstructionSets(ps));
                    synchronized (mInstallLock) {
                        args.cleanUpResourcesLI();
                    }
                    synchronized (mPackages) {
                        mSettings.enableSystemPackageLPw(ps.name);
                    }
                    updatedPkgBetter = true;
                }
            }
        }

        if (updatedPkg != null) {
            // An updated system app will not have the PARSE_IS_SYSTEM flag set
            // initially
            parseFlags |= PackageParser.PARSE_IS_SYSTEM;

            // An updated privileged app will not have the PARSE_IS_PRIVILEGED
            // flag set initially
            if ((updatedPkg.pkgPrivateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0) {
                parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
            }
        }

        // Verify certificates against what was last scanned
        collectCertificatesLI(pp, ps, pkg, scanFile, parseFlags);

        /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        if (updatedPkg == null && ps != null
                && (parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0 && !isSystemApp(ps)) {
            /*
             * Check to make sure the signatures match first. If they don't,
             * wipe the installed application and its data.
             */
            if (compareSignatures(ps.signatures.mSignatures, pkg.mSignatures)
                    != PackageManager.SIGNATURE_MATCH) {
                logCriticalInfo(Log.WARN, "Package " + ps.name + " appeared on system, but"
                        + " signatures don't match existing userdata copy; removing");
                deletePackageLI(pkg.packageName, null, true, null, null, 0, null, false);
                ps = null;
            } else {
                /*
                 * If the newly-added system app is an older version than the
                 * already installed version, hide it. It will be scanned later
                 * and re-added like an update.
                 */
                if (pkg.mVersionCode <= ps.versionCode) {
                    shouldHideSystemApp = true;
                    logCriticalInfo(Log.INFO, "Package " + ps.name + " appeared at " + scanFile
                            + " but new version " + pkg.mVersionCode + " better than installed "
                            + ps.versionCode + "; hiding system");
                } else {
                    /*
                     * The newly found system app is a newer version that the
                     * one previously installed. Simply remove the
                     * already-installed application and replace it with our own
                     * while keeping the application data.
                     */
                    logCriticalInfo(Log.WARN, "Package " + ps.name + " at " + scanFile
                            + " reverting from " + ps.codePathString + ": new version "
                            + pkg.mVersionCode + " better than installed " + ps.versionCode);
                    InstallArgs args = createInstallArgsForExisting(packageFlagsToInstallFlags(ps),
                            ps.codePathString, ps.resourcePathString, getAppDexInstructionSets(ps));
                    synchronized (mInstallLock) {
                        args.cleanUpResourcesLI();
                    }
                }
            }
        }

        // The apk is forward locked (not public) if its code and resources
        // are kept in different files. (except for app in either system or
        // vendor path).
        // TODO grab this value from PackageSettings
        if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
            if (ps != null && !ps.codePath.equals(ps.resourcePath)) {
                parseFlags |= PackageParser.PARSE_FORWARD_LOCK;
            }
        }

        // TODO: extend to support forward-locked splits
        String resourcePath = null;
        String baseResourcePath = null;
        if ((parseFlags & PackageParser.PARSE_FORWARD_LOCK) != 0 && !updatedPkgBetter) {
            if (ps != null && ps.resourcePathString != null) {
                resourcePath = ps.resourcePathString;
                baseResourcePath = ps.resourcePathString;
            } else {
                // Should not happen at all. Just log an error.
                Slog.e(TAG, "Resource path not set for pkg : " + pkg.packageName);
            }
        } else {
            resourcePath = pkg.codePath;
            baseResourcePath = pkg.baseCodePath;
        }

        // Set application objects path explicitly.
        pkg.applicationInfo.volumeUuid = pkg.volumeUuid;
        pkg.applicationInfo.setCodePath(pkg.codePath);
        pkg.applicationInfo.setBaseCodePath(pkg.baseCodePath);
        pkg.applicationInfo.setSplitCodePaths(pkg.splitCodePaths);
        pkg.applicationInfo.setResourcePath(resourcePath);
        pkg.applicationInfo.setBaseResourcePath(baseResourcePath);
        pkg.applicationInfo.setSplitResourcePaths(pkg.splitCodePaths);

        // Note that we invoke the following method only if we are about to unpack an application
        PackageParser.Package scannedPkg = scanPackageLI(pkg, parseFlags, scanFlags
                | SCAN_UPDATE_SIGNATURE, currentTime, user);

        /*
         * If the system app should be overridden by a previously installed
         * data, hide the system app now and let the /data/app scan pick it up
         * again.
         */
        if (shouldHideSystemApp) {
            synchronized (mPackages) {
                /*
                 * We have to grant systems permissions before we hide, because
                 * grantPermissions will assume the package update is trying to
                 * expand its permissions.
                 */
                grantPermissionsLPw(pkg, true, pkg.packageName);
                mSettings.disableSystemPackageLPw(pkg.packageName);
            }
        }

        return scannedPkg;
    }

    private static String fixProcessName(String defProcessName,
            String processName, int uid) {
        if (processName == null) {
            return defProcessName;
        }
        return processName;
    }

    private void verifySignaturesLP(PackageSetting pkgSetting, PackageParser.Package pkg)
            throws PackageManagerException {
        if (pkgSetting.signatures.mSignatures != null) {
            // Already existing package. Make sure signatures match
            boolean match = compareSignatures(pkgSetting.signatures.mSignatures, pkg.mSignatures)
                    == PackageManager.SIGNATURE_MATCH;
            if (!match) {
                match = compareSignaturesCompat(pkgSetting.signatures, pkg)
                        == PackageManager.SIGNATURE_MATCH;
            }
            if (!match) {
                match = compareSignaturesRecover(pkgSetting.signatures, pkg)
                        == PackageManager.SIGNATURE_MATCH;
            }
            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE, "Package "
                        + pkg.packageName + " signatures do not match the "
                        + "previously installed version; ignoring!");
            }
        }

        // Check for shared user signatures
        if (pkgSetting.sharedUser != null && pkgSetting.sharedUser.signatures.mSignatures != null) {
            // Already existing package. Make sure signatures match
            boolean match = compareSignatures(pkgSetting.sharedUser.signatures.mSignatures,
                    pkg.mSignatures) == PackageManager.SIGNATURE_MATCH;
            if (!match) {
                match = compareSignaturesCompat(pkgSetting.sharedUser.signatures, pkg)
                        == PackageManager.SIGNATURE_MATCH;
            }
            if (!match) {
                match = compareSignaturesRecover(pkgSetting.sharedUser.signatures, pkg)
                        == PackageManager.SIGNATURE_MATCH;
            }
            if (!match) {
                throw new PackageManagerException(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                        "Package " + pkg.packageName
                        + " has no signatures that match those in shared user "
                        + pkgSetting.sharedUser.name + "; ignoring!");
            }
        }
    }

    /**
     * Enforces that only the system UID or root's UID can call a method exposed
     * via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static final void enforceSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != 0) {
            throw new SecurityException(message);
        }
    }

    @Override
    public void performBootDexOpt() {
        enforceSystemOrRoot("Only the system can request dexopt be performed");

        // Before everything else, see whether we need to fstrim.
        try {
            IMountService ms = PackageHelper.getMountService();
            if (ms != null) {
                final boolean isUpgrade = isUpgrade();
                boolean doTrim = isUpgrade;
                if (doTrim) {
                    Slog.w(TAG, "Running disk maintenance immediately due to system update");
                } else {
                    final long interval = android.provider.Settings.Global.getLong(
                            mContext.getContentResolver(),
                            android.provider.Settings.Global.FSTRIM_MANDATORY_INTERVAL,
                            DEFAULT_MANDATORY_FSTRIM_INTERVAL);
                    if (interval > 0) {
                        final long timeSinceLast = System.currentTimeMillis() - ms.lastMaintenance();
                        if (timeSinceLast > interval) {
                            doTrim = true;
                            Slog.w(TAG, "No disk maintenance in " + timeSinceLast
                                    + "; running immediately");
                        }
                    }
                }
                if (doTrim) {
                    if (!isFirstBoot()) {
                        try {
                            ActivityManagerNative.getDefault().showBootMessage(
                                    mContext.getResources().getString(
                                            R.string.android_upgrading_fstrim), true);
                        } catch (RemoteException e) {
                        }
                    }
                    ms.runMaintenance();
                }
            } else {
                Slog.e(TAG, "Mount service unavailable!");
            }
        } catch (RemoteException e) {
            // Can't happen; MountService is local
        }

        final ArraySet<PackageParser.Package> pkgs;
        synchronized (mPackages) {
            pkgs = mPackageDexOptimizer.clearDeferredDexOptPackages();
        }

        if (pkgs != null) {
            // Sort apps by importance for dexopt ordering. Important apps are given more priority
            // in case the device runs out of space.
            ArrayList<PackageParser.Package> sortedPkgs = new ArrayList<PackageParser.Package>();
            // Give priority to core apps.
            for (Iterator<PackageParser.Package> it = pkgs.iterator(); it.hasNext();) {
                PackageParser.Package pkg = it.next();
                if (pkg.coreApp) {
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Adding core app " + sortedPkgs.size() + ": " + pkg.packageName);
                    }
                    sortedPkgs.add(pkg);
                    it.remove();
                }
            }
            // Give priority to system apps that listen for pre boot complete.
            Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
            ArraySet<String> pkgNames = getPackageNamesForIntent(intent);
            for (Iterator<PackageParser.Package> it = pkgs.iterator(); it.hasNext();) {
                PackageParser.Package pkg = it.next();
                if (pkgNames.contains(pkg.packageName)) {
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Adding pre boot system app " + sortedPkgs.size() + ": " + pkg.packageName);
                    }
                    sortedPkgs.add(pkg);
                    it.remove();
                }
            }
            // Give priority to system apps.
            for (Iterator<PackageParser.Package> it = pkgs.iterator(); it.hasNext();) {
                PackageParser.Package pkg = it.next();
                if (isSystemApp(pkg) && !pkg.isUpdatedSystemApp()) {
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Adding system app " + sortedPkgs.size() + ": " + pkg.packageName);
                    }
                    sortedPkgs.add(pkg);
                    it.remove();
                }
            }
            // Give priority to updated system apps.
            for (Iterator<PackageParser.Package> it = pkgs.iterator(); it.hasNext();) {
                PackageParser.Package pkg = it.next();
                if (pkg.isUpdatedSystemApp()) {
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Adding updated system app " + sortedPkgs.size() + ": " + pkg.packageName);
                    }
                    sortedPkgs.add(pkg);
                    it.remove();
                }
            }
            // Give priority to apps that listen for boot complete.
            intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
            pkgNames = getPackageNamesForIntent(intent);
            for (Iterator<PackageParser.Package> it = pkgs.iterator(); it.hasNext();) {
                PackageParser.Package pkg = it.next();
                if (pkgNames.contains(pkg.packageName)) {
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Adding boot app " + sortedPkgs.size() + ": " + pkg.packageName);
                    }
                    sortedPkgs.add(pkg);
                    it.remove();
                }
            }
            // Filter out packages that aren't recently used.
            filterRecentlyUsedApps(pkgs);
            // Add all remaining apps.
            for (PackageParser.Package pkg : pkgs) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, "Adding app " + sortedPkgs.size() + ": " + pkg.packageName);
                }
                sortedPkgs.add(pkg);
            }

            // If we want to be lazy, filter everything that wasn't recently used.
            if (mLazyDexOpt) {
                filterRecentlyUsedApps(sortedPkgs);
            }

            int i = 0;
            int total = sortedPkgs.size();
            File dataDir = Environment.getDataDirectory();
            long lowThreshold = StorageManager.from(mContext).getStorageLowBytes(dataDir);
            if (lowThreshold == 0) {
                throw new IllegalStateException("Invalid low memory threshold");
            }
            for (PackageParser.Package pkg : sortedPkgs) {
                long usableSpace = dataDir.getUsableSpace();
                if (usableSpace < lowThreshold) {
                    Log.w(TAG, "Not running dexopt on remaining apps due to low memory: " + usableSpace);
                    break;
                }
                performBootDexOpt(pkg, ++i, total);
            }
        }
    }

    private void filterRecentlyUsedApps(Collection<PackageParser.Package> pkgs) {
        // Filter out packages that aren't recently used.
        //
        // The exception is first boot of a non-eng device (aka !mLazyDexOpt), which
        // should do a full dexopt.
        if (mLazyDexOpt || (!isFirstBoot() && mPackageUsage.isHistoricalPackageUsageAvailable())) {
            int total = pkgs.size();
            int skipped = 0;
            long now = System.currentTimeMillis();
            for (Iterator<PackageParser.Package> i = pkgs.iterator(); i.hasNext();) {
                PackageParser.Package pkg = i.next();
                long then = pkg.mLastPackageUsageTimeInMills;
                if (then + mDexOptLRUThresholdInMills < now) {
                    if (DEBUG_DEXOPT) {
                        Log.i(TAG, "Skipping dexopt of " + pkg.packageName + " last resumed: " +
                              ((then == 0) ? "never" : new Date(then)));
                    }
                    i.remove();
                    skipped++;
                }
            }
            if (DEBUG_DEXOPT) {
                Log.i(TAG, "Skipped optimizing " + skipped + " of " + total);
            }
        }
    }

    private ArraySet<String> getPackageNamesForIntent(Intent intent) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(
                    intent, null, 0, UserHandle.USER_OWNER);
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<String>();
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    private void performBootDexOpt(PackageParser.Package pkg, int curr, int total) {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Optimizing app " + curr + " of " + total + ": " + pkg.packageName);
        }
        if (!isFirstBoot()) {
            try {
                ActivityManagerNative.getDefault().showBootMessage(
                        mContext.getResources().getString(R.string.android_upgrading_apk,
                                curr, total), true);
            } catch (RemoteException e) {
            }
        }
        PackageParser.Package p = pkg;
        synchronized (mInstallLock) {
            mPackageDexOptimizer.performDexOpt(p, null /* instruction sets */,
                    false /* force dex */, false /* defer */, true /* include dependencies */);
        }
    }

    @Override
    public boolean performDexOptIfNeeded(String packageName, String instructionSet) {
        return performDexOpt(packageName, instructionSet, false);
    }

    public boolean performDexOpt(String packageName, String instructionSet, boolean backgroundDexopt) {
        boolean dexopt = mLazyDexOpt || backgroundDexopt;
        boolean updateUsage = !backgroundDexopt;  // Don't update usage if this is just a backgroundDexopt
        if (!dexopt && !updateUsage) {
            // We aren't going to dexopt or update usage, so bail early.
            return false;
        }
        PackageParser.Package p;
        final String targetInstructionSet;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            if (p == null) {
                return false;
            }
            if (updateUsage) {
                p.mLastPackageUsageTimeInMills = System.currentTimeMillis();
            }
            mPackageUsage.write(false);
            if (!dexopt) {
                // We aren't going to dexopt, so bail early.
                return false;
            }

            targetInstructionSet = instructionSet != null ? instructionSet :
                    getPrimaryInstructionSet(p.applicationInfo);
            if (p.mDexOptPerformed.contains(targetInstructionSet)) {
                return false;
            }
        }
        long callingId = Binder.clearCallingIdentity();
        try {
            synchronized (mInstallLock) {
                final String[] instructionSets = new String[] { targetInstructionSet };
                int result = mPackageDexOptimizer.performDexOpt(p, instructionSets,
                        false /* forceDex */, false /* defer */, true /* inclDependencies */);
                return result == PackageDexOptimizer.DEX_OPT_PERFORMED;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public ArraySet<String> getPackagesThatNeedDexOpt() {
        ArraySet<String> pkgs = null;
        synchronized (mPackages) {
            for (PackageParser.Package p : mPackages.values()) {
                if (DEBUG_DEXOPT) {
                    Log.i(TAG, p.packageName + " mDexOptPerformed=" + p.mDexOptPerformed.toArray());
                }
                if (!p.mDexOptPerformed.isEmpty()) {
                    continue;
                }
                if (pkgs == null) {
                    pkgs = new ArraySet<String>();
                }
                pkgs.add(p.packageName);
            }
        }
        return pkgs;
    }

    public void shutdown() {
        mPackageUsage.write(true);
    }

    @Override
    public void forceDexOpt(String packageName) {
        enforceSystemOrRoot("forceDexOpt");

        PackageParser.Package pkg;
        synchronized (mPackages) {
            pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new IllegalArgumentException("Missing package: " + packageName);
            }
        }

        synchronized (mInstallLock) {
            final String[] instructionSets = new String[] {
                    getPrimaryInstructionSet(pkg.applicationInfo) };
            final int res = mPackageDexOptimizer.performDexOpt(pkg, instructionSets,
                    true /*forceDex*/, false /* defer */, true /* inclDependencies */);
            if (res != PackageDexOptimizer.DEX_OPT_PERFORMED) {
                throw new IllegalStateException("Failed to dexopt: " + res);
            }
        }
    }

    private boolean verifyPackageUpdateLPr(PackageSetting oldPkg, PackageParser.Package newPkg) {
        if ((oldPkg.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name
                    + " to " + newPkg.packageName
                    + ": old package not in system partition");
            return false;
        } else if (mPackages.get(oldPkg.name) != null) {
            Slog.w(TAG, "Unable to update from " + oldPkg.name
                    + " to " + newPkg.packageName
                    + ": old package still exists");
            return false;
        }
        return true;
    }

    private int createDataDirsLI(String volumeUuid, String packageName, int uid, String seinfo) {
        int[] users = sUserManager.getUserIds();
        int res = mInstaller.install(volumeUuid, packageName, uid, uid, seinfo);
        if (res < 0) {
            return res;
        }
        for (int user : users) {
            if (user != 0) {
                res = mInstaller.createUserData(volumeUuid, packageName,
                        UserHandle.getUid(user, uid), user, seinfo);
                if (res < 0) {
                    return res;
                }
            }
        }
        return res;
    }

    private int removeDataDirsLI(String volumeUuid, String packageName) {
        int[] users = sUserManager.getUserIds();
        int res = 0;
        for (int user : users) {
            int resInner = mInstaller.remove(volumeUuid, packageName, user);
            if (resInner < 0) {
                res = resInner;
            }
        }

        return res;
    }

    private int deleteCodeCacheDirsLI(String volumeUuid, String packageName) {
        int[] users = sUserManager.getUserIds();
        int res = 0;
        for (int user : users) {
            int resInner = mInstaller.deleteCodeCacheFiles(volumeUuid, packageName, user);
            if (resInner < 0) {
                res = resInner;
            }
        }
        return res;
    }

    private void addSharedLibraryLPw(ArraySet<String> usesLibraryFiles, SharedLibraryEntry file,
            PackageParser.Package changingLib) {
        if (file.path != null) {
            usesLibraryFiles.add(file.path);
            return;
        }
        PackageParser.Package p = mPackages.get(file.apk);
        if (changingLib != null && changingLib.packageName.equals(file.apk)) {
            // If we are doing this while in the middle of updating a library apk,
            // then we need to make sure to use that new apk for determining the
            // dependencies here.  (We haven't yet finished committing the new apk
            // to the package manager state.)
            if (p == null || p.packageName.equals(changingLib.packageName)) {
                p = changingLib;
            }
        }
        if (p != null) {
            usesLibraryFiles.addAll(p.getAllCodePaths());
        }
    }

    private void updateSharedLibrariesLPw(PackageParser.Package pkg,
            PackageParser.Package changingLib) throws PackageManagerException {
        if (pkg.usesLibraries != null || pkg.usesOptionalLibraries != null) {
            final ArraySet<String> usesLibraryFiles = new ArraySet<>();
            int N = pkg.usesLibraries != null ? pkg.usesLibraries.size() : 0;
            for (int i=0; i<N; i++) {
                final SharedLibraryEntry file = mSharedLibraries.get(pkg.usesLibraries.get(i));
                if (file == null) {
                    throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                            "Package " + pkg.packageName + " requires unavailable shared library "
                            + pkg.usesLibraries.get(i) + "; failing!");
                }
                addSharedLibraryLPw(usesLibraryFiles, file, changingLib);
            }
            N = pkg.usesOptionalLibraries != null ? pkg.usesOptionalLibraries.size() : 0;
            for (int i=0; i<N; i++) {
                final SharedLibraryEntry file = mSharedLibraries.get(pkg.usesOptionalLibraries.get(i));
                if (file == null) {
                    Slog.w(TAG, "Package " + pkg.packageName
                            + " desires unavailable shared library "
                            + pkg.usesOptionalLibraries.get(i) + "; ignoring!");
                } else {
                    addSharedLibraryLPw(usesLibraryFiles, file, changingLib);
                }
            }
            N = usesLibraryFiles.size();
            if (N > 0) {
                pkg.usesLibraryFiles = usesLibraryFiles.toArray(new String[N]);
            } else {
                pkg.usesLibraryFiles = null;
            }
        }
    }

    private static boolean hasString(List<String> list, List<String> which) {
        if (list == null) {
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

    private void updateAllSharedLibrariesLPw() {
        for (PackageParser.Package pkg : mPackages.values()) {
            try {
                updateSharedLibrariesLPw(pkg, null);
            } catch (PackageManagerException e) {
                Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
            }
        }
    }

    private ArrayList<PackageParser.Package> updateAllSharedLibrariesLPw(
            PackageParser.Package changingPkg) {
        ArrayList<PackageParser.Package> res = null;
        for (PackageParser.Package pkg : mPackages.values()) {
            if (hasString(pkg.usesLibraries, changingPkg.libraryNames)
                    || hasString(pkg.usesOptionalLibraries, changingPkg.libraryNames)) {
                if (res == null) {
                    res = new ArrayList<PackageParser.Package>();
                }
                res.add(pkg);
                try {
                    updateSharedLibrariesLPw(pkg, changingPkg);
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
                }
            }
        }
        return res;
    }

    /**
     * Derive the value of the {@code cpuAbiOverride} based on the provided
     * value and an optional stored value from the package settings.
     */
    private static String deriveAbiOverride(String abiOverride, PackageSetting settings) {
        String cpuAbiOverride = null;

        if (NativeLibraryHelper.CLEAR_ABI_OVERRIDE.equals(abiOverride)) {
            cpuAbiOverride = null;
        } else if (abiOverride != null) {
            cpuAbiOverride = abiOverride;
        } else if (settings != null) {
            cpuAbiOverride = settings.cpuAbiOverrideString;
        }

        return cpuAbiOverride;
    }

    private PackageParser.Package scanPackageLI(PackageParser.Package pkg, int parseFlags,
            int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        boolean success = false;
        try {
            final PackageParser.Package res = scanPackageDirtyLI(pkg, parseFlags, scanFlags,
                    currentTime, user);
            success = true;
            return res;
        } finally {
            if (!success && (scanFlags & SCAN_DELETE_DATA_ON_FAILURES) != 0) {
                removeDataDirsLI(pkg.volumeUuid, pkg.packageName);
            }
        }
    }

    private PackageParser.Package scanPackageDirtyLI(PackageParser.Package pkg, int parseFlags,
            int scanFlags, long currentTime, UserHandle user) throws PackageManagerException {
        final File scanFile = new File(pkg.codePath);
        if (pkg.applicationInfo.getCodePath() == null ||
                pkg.applicationInfo.getResourcePath() == null) {
            // Bail out. The resource and code paths haven't been set.
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Code and resource paths haven't been set correctly");
        }

        if ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        } else {
            // Only allow system apps to be flagged as core apps.
            pkg.coreApp = false;
        }

        if ((parseFlags&PackageParser.PARSE_IS_PRIVILEGED) != 0) {
            pkg.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PRIVILEGED;
        }

        if (mCustomResolverComponentName != null &&
                mCustomResolverComponentName.getPackageName().equals(pkg.packageName)) {
            setUpCustomResolverActivity(pkg);
        }

        if (pkg.packageName.equals("android")) {
            synchronized (mPackages) {
                if (mAndroidApplication != null) {
                    Slog.w(TAG, "*************************************************");
                    Slog.w(TAG, "Core android package being redefined.  Skipping.");
                    Slog.w(TAG, " file=" + scanFile);
                    Slog.w(TAG, "*************************************************");
                    throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                            "Core android package being redefined.  Skipping.");
                }

                // Set up information for our fall-back user intent resolution activity.
                mPlatformPackage = pkg;
                pkg.mVersionCode = mSdkVersion;
                mAndroidApplication = pkg.applicationInfo;

                if (!mResolverReplaced) {
                    mResolveActivity.applicationInfo = mAndroidApplication;
                    mResolveActivity.name = ResolverActivity.class.getName();
                    mResolveActivity.packageName = mAndroidApplication.packageName;
                    mResolveActivity.processName = "system:ui";
                    mResolveActivity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
                    mResolveActivity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NEVER;
                    mResolveActivity.flags = ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
                    mResolveActivity.theme = R.style.Theme_Holo_Dialog_Alert;
                    mResolveActivity.exported = true;
                    mResolveActivity.enabled = true;
                    mResolveInfo.activityInfo = mResolveActivity;
                    mResolveInfo.priority = 0;
                    mResolveInfo.preferredOrder = 0;
                    mResolveInfo.match = 0;
                    mResolveComponentName = new ComponentName(
                            mAndroidApplication.packageName, mResolveActivity.name);
                }
            }
        }

        if (DEBUG_PACKAGE_SCANNING) {
            if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                Log.d(TAG, "Scanning package " + pkg.packageName);
        }

        if (mPackages.containsKey(pkg.packageName)
                || mSharedLibraries.containsKey(pkg.packageName)) {
            throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                    "Application package " + pkg.packageName
                    + " already installed.  Skipping duplicate.");
        }

        // If we're only installing presumed-existing packages, require that the
        // scanned APK is both already known and at the path previously established
        // for it.  Previously unknown packages we pick up normally, but if we have an
        // a priori expectation about this package's install presence, enforce it.
        // With a singular exception for new system packages. When an OTA contains
        // a new system package, we allow the codepath to change from a system location
        // to the user-installed location. If we don't allow this change, any newer,
        // user-installed version of the application will be ignored.
        if ((scanFlags & SCAN_REQUIRE_KNOWN) != 0) {
            if (mExpectingBetter.containsKey(pkg.packageName)) {
                logCriticalInfo(Log.WARN,
                        "Relax SCAN_REQUIRE_KNOWN requirement for package " + pkg.packageName);
            } else {
                PackageSetting known = mSettings.peekPackageLPr(pkg.packageName);
                if (known != null) {
                    if (DEBUG_PACKAGE_SCANNING) {
                        Log.d(TAG, "Examining " + pkg.codePath
                                + " and requiring known paths " + known.codePathString
                                + " & " + known.resourcePathString);
                    }
                    if (!pkg.applicationInfo.getCodePath().equals(known.codePathString)
                            || !pkg.applicationInfo.getResourcePath().equals(known.resourcePathString)) {
                        throw new PackageManagerException(INSTALL_FAILED_PACKAGE_CHANGED,
                                "Application package " + pkg.packageName
                                + " found at " + pkg.applicationInfo.getCodePath()
                                + " but expected at " + known.codePathString + "; ignoring.");
                    }
                }
            }
        }

        // Initialize package source and resource directories
        File destCodeFile = new File(pkg.applicationInfo.getCodePath());
        File destResourceFile = new File(pkg.applicationInfo.getResourcePath());

        SharedUserSetting suid = null;
        PackageSetting pkgSetting = null;

        if (!isSystemApp(pkg)) {
            // Only system apps can use these features.
            pkg.mOriginalPackages = null;
            pkg.mRealPackage = null;
            pkg.mAdoptPermissions = null;
        }

        // writer
        synchronized (mPackages) {
            if (pkg.mSharedUserId != null) {
                suid = mSettings.getSharedUserLPw(pkg.mSharedUserId, 0, 0, true);
                if (suid == null) {
                    throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                            "Creating application package " + pkg.packageName
                            + " for shared user failed");
                }
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                        Log.d(TAG, "Shared UserID " + pkg.mSharedUserId + " (uid=" + suid.userId
                                + "): packages=" + suid.packages);
                }
            }

            // Check if we are renaming from an original package name.
            PackageSetting origPackage = null;
            String realName = null;
            if (pkg.mOriginalPackages != null) {
                // This package may need to be renamed to a previously
                // installed name.  Let's check on that...
                final String renamed = mSettings.mRenamedPackages.get(pkg.mRealPackage);
                if (pkg.mOriginalPackages.contains(renamed)) {
                    // This package had originally been installed as the
                    // original name, and we have already taken care of
                    // transitioning to the new one.  Just update the new
                    // one to continue using the old name.
                    realName = pkg.mRealPackage;
                    if (!pkg.packageName.equals(renamed)) {
                        // Callers into this function may have already taken
                        // care of renaming the package; only do it here if
                        // it is not already done.
                        pkg.setPackageName(renamed);
                    }

                } else {
                    for (int i=pkg.mOriginalPackages.size()-1; i>=0; i--) {
                        if ((origPackage = mSettings.peekPackageLPr(
                                pkg.mOriginalPackages.get(i))) != null) {
                            // We do have the package already installed under its
                            // original name...  should we use it?
                            if (!verifyPackageUpdateLPr(origPackage, pkg)) {
                                // New package is not compatible with original.
                                origPackage = null;
                                continue;
                            } else if (origPackage.sharedUser != null) {
                                // Make sure uid is compatible between packages.
                                if (!origPackage.sharedUser.name.equals(pkg.mSharedUserId)) {
                                    Slog.w(TAG, "Unable to migrate data from " + origPackage.name
                                            + " to " + pkg.packageName + ": old uid "
                                            + origPackage.sharedUser.name
                                            + " differs from " + pkg.mSharedUserId);
                                    origPackage = null;
                                    continue;
                                }
                            } else {
                                if (DEBUG_UPGRADE) Log.v(TAG, "Renaming new package "
                                        + pkg.packageName + " to old name " + origPackage.name);
                            }
                            break;
                        }
                    }
                }
            }

            if (mTransferedPackages.contains(pkg.packageName)) {
                Slog.w(TAG, "Package " + pkg.packageName
                        + " was transferred to another, but its .apk remains");
            }

            // Just create the setting, don't add it yet. For already existing packages
            // the PkgSetting exists already and doesn't have to be created.
            pkgSetting = mSettings.getPackageLPw(pkg, origPackage, realName, suid, destCodeFile,
                    destResourceFile, pkg.applicationInfo.nativeLibraryRootDir,
                    pkg.applicationInfo.primaryCpuAbi,
                    pkg.applicationInfo.secondaryCpuAbi,
                    pkg.applicationInfo.flags, pkg.applicationInfo.privateFlags,
                    user, false);
            if (pkgSetting == null) {
                throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                        "Creating application package " + pkg.packageName + " failed");
            }

            if (pkgSetting.origPackage != null) {
                // If we are first transitioning from an original package,
                // fix up the new package's name now.  We need to do this after
                // looking up the package under its new name, so getPackageLP
                // can take care of fiddling things correctly.
                pkg.setPackageName(origPackage.name);

                // File a report about this.
                String msg = "New package " + pkgSetting.realName
                        + " renamed to replace old package " + pkgSetting.name;
                reportSettingsProblem(Log.WARN, msg);

                // Make a note of it.
                mTransferedPackages.add(origPackage.name);

                // No longer need to retain this.
                pkgSetting.origPackage = null;
            }

            if (realName != null) {
                // Make a note of it.
                mTransferedPackages.add(pkg.packageName);
            }

            if (mSettings.isDisabledSystemPackageLPr(pkg.packageName)) {
                pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }

            if ((parseFlags&PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                // Check all shared libraries and map to their actual file path.
                // We only do this here for apps not on a system dir, because those
                // are the only ones that can fail an install due to this.  We
                // will take care of the system apps by updating all of their
                // library paths after the scan is done.
                updateSharedLibrariesLPw(pkg, null);
            }

            if (mFoundPolicyFile) {
                SELinuxMMAC.assignSeinfoValue(pkg);
            }

            pkg.applicationInfo.uid = pkgSetting.appId;
            pkg.mExtras = pkgSetting;
            if (shouldCheckUpgradeKeySetLP(pkgSetting, scanFlags)) {
                if (checkUpgradeKeySetLP(pkgSetting, pkg)) {
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    pkgSetting.signatures.mSignatures = pkg.mSignatures;
                } else {
                    if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                "Package " + pkg.packageName + " upgrade keys do not match the "
                                + "previously installed version");
                    } else {
                        pkgSetting.signatures.mSignatures = pkg.mSignatures;
                        String msg = "System package " + pkg.packageName
                            + " signature changed; retaining data.";
                        reportSettingsProblem(Log.WARN, msg);
                    }
                }
            } else {
                try {
                    verifySignaturesLP(pkgSetting, pkg);
                    // We just determined the app is signed correctly, so bring
                    // over the latest parsed certs.
                    pkgSetting.signatures.mSignatures = pkg.mSignatures;
                } catch (PackageManagerException e) {
                    if ((parseFlags & PackageParser.PARSE_IS_SYSTEM_DIR) == 0) {
                        throw e;
                    }
                    // The signature has changed, but this package is in the system
                    // image...  let's recover!
                    pkgSetting.signatures.mSignatures = pkg.mSignatures;
                    // However...  if this package is part of a shared user, but it
                    // doesn't match the signature of the shared user, let's fail.
                    // What this means is that you can't change the signatures
                    // associated with an overall shared user, which doesn't seem all
                    // that unreasonable.
                    if (pkgSetting.sharedUser != null) {
                        if (compareSignatures(pkgSetting.sharedUser.signatures.mSignatures,
                                              pkg.mSignatures) != PackageManager.SIGNATURE_MATCH) {
                            throw new PackageManagerException(
                                    INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                                            "Signature mismatch for shared user : "
                                            + pkgSetting.sharedUser);
                        }
                    }
                    // File a report about this.
                    String msg = "System package " + pkg.packageName
                        + " signature changed; retaining data.";
                    reportSettingsProblem(Log.WARN, msg);
                }
            }
            // Verify that this new package doesn't have any content providers
            // that conflict with existing packages.  Only do this if the
            // package isn't already installed, since we don't want to break
            // things that are installed.
            if ((scanFlags & SCAN_NEW_INSTALL) != 0) {
                final int N = pkg.providers.size();
                int i;
                for (i=0; i<N; i++) {
                    PackageParser.Provider p = pkg.providers.get(i);
                    if (p.info.authority != null) {
                        String names[] = p.info.authority.split(";");
                        for (int j = 0; j < names.length; j++) {
                            if (mProvidersByAuthority.containsKey(names[j])) {
                                PackageParser.Provider other = mProvidersByAuthority.get(names[j]);
                                final String otherPackageName =
                                        ((other != null && other.getComponentName() != null) ?
                                                other.getComponentName().getPackageName() : "?");
                                throw new PackageManagerException(
                                        INSTALL_FAILED_CONFLICTING_PROVIDER,
                                                "Can't install because provider name " + names[j]
                                                + " (in package " + pkg.applicationInfo.packageName
                                                + ") is already used by " + otherPackageName);
                            }
                        }
                    }
                }
            }

            if (pkg.mAdoptPermissions != null) {
                // This package wants to adopt ownership of permissions from
                // another package.
                for (int i = pkg.mAdoptPermissions.size() - 1; i >= 0; i--) {
                    final String origName = pkg.mAdoptPermissions.get(i);
                    final PackageSetting orig = mSettings.peekPackageLPr(origName);
                    if (orig != null) {
                        if (verifyPackageUpdateLPr(orig, pkg)) {
                            Slog.i(TAG, "Adopting permissions from " + origName + " to "
                                    + pkg.packageName);
                            mSettings.transferPermissionsLPw(origName, pkg.packageName);
                        }
                    }
                }
            }
        }

        final String pkgName = pkg.packageName;

        final long scanFileTime = scanFile.lastModified();
        final boolean forceDex = (scanFlags & SCAN_FORCE_DEX) != 0;
        pkg.applicationInfo.processName = fixProcessName(
                pkg.applicationInfo.packageName,
                pkg.applicationInfo.processName,
                pkg.applicationInfo.uid);

        File dataPath;
        if (mPlatformPackage == pkg) {
            // The system package is special.
            dataPath = new File(Environment.getDataDirectory(), "system");

            pkg.applicationInfo.dataDir = dataPath.getPath();

        } else {
            // This is a normal package, need to make its data directory.
            dataPath = Environment.getDataUserPackageDirectory(pkg.volumeUuid,
                    UserHandle.USER_OWNER, pkg.packageName);

            boolean uidError = false;
            if (dataPath.exists()) {
                int currentUid = 0;
                try {
                    StructStat stat = Os.stat(dataPath.getPath());
                    currentUid = stat.st_uid;
                } catch (ErrnoException e) {
                    Slog.e(TAG, "Couldn't stat path " + dataPath.getPath(), e);
                }

                // If we have mismatched owners for the data path, we have a problem.
                if (currentUid != pkg.applicationInfo.uid) {
                    boolean recovered = false;
                    if (currentUid == 0) {
                        // The directory somehow became owned by root.  Wow.
                        // This is probably because the system was stopped while
                        // installd was in the middle of messing with its libs
                        // directory.  Ask installd to fix that.
                        int ret = mInstaller.fixUid(pkg.volumeUuid, pkgName,
                                pkg.applicationInfo.uid, pkg.applicationInfo.uid);
                        if (ret >= 0) {
                            recovered = true;
                            String msg = "Package " + pkg.packageName
                                    + " unexpectedly changed to uid 0; recovered to " +
                                    + pkg.applicationInfo.uid;
                            reportSettingsProblem(Log.WARN, msg);
                        }
                    }
                    if (!recovered && ((parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0
                            || (scanFlags&SCAN_BOOTING) != 0)) {
                        // If this is a system app, we can at least delete its
                        // current data so the application will still work.
                        int ret = removeDataDirsLI(pkg.volumeUuid, pkgName);
                        if (ret >= 0) {
                            // TODO: Kill the processes first
                            // Old data gone!
                            String prefix = (parseFlags&PackageParser.PARSE_IS_SYSTEM) != 0
                                    ? "System package " : "Third party package ";
                            String msg = prefix + pkg.packageName
                                    + " has changed from uid: "
                                    + currentUid + " to "
                                    + pkg.applicationInfo.uid + "; old data erased";
                            reportSettingsProblem(Log.WARN, msg);
                            recovered = true;

                            // And now re-install the app.
                            ret = createDataDirsLI(pkg.volumeUuid, pkgName, pkg.applicationInfo.uid,
                                    pkg.applicationInfo.seinfo);
                            if (ret == -1) {
                                // Ack should not happen!
                                msg = prefix + pkg.packageName
                                        + " could not have data directory re-created after delete.";
                                reportSettingsProblem(Log.WARN, msg);
                                throw new PackageManagerException(
                                        INSTALL_FAILED_INSUFFICIENT_STORAGE, msg);
                            }
                        }
                        if (!recovered) {
                            mHasSystemUidErrors = true;
                        }
                    } else if (!recovered) {
                        // If we allow this install to proceed, we will be broken.
                        // Abort, abort!
                        throw new PackageManagerException(INSTALL_FAILED_UID_CHANGED,
                                "scanPackageLI");
                    }
                    if (!recovered) {
                        pkg.applicationInfo.dataDir = "/mismatched_uid/settings_"
                            + pkg.applicationInfo.uid + "/fs_"
                            + currentUid;
                        pkg.applicationInfo.nativeLibraryDir = pkg.applicationInfo.dataDir;
                        pkg.applicationInfo.nativeLibraryRootDir = pkg.applicationInfo.dataDir;
                        String msg = "Package " + pkg.packageName
                                + " has mismatched uid: "
                                + currentUid + " on disk, "
                                + pkg.applicationInfo.uid + " in settings";
                        // writer
                        synchronized (mPackages) {
                            mSettings.mReadMessages.append(msg);
                            mSettings.mReadMessages.append('\n');
                            uidError = true;
                            if (!pkgSetting.uidError) {
                                reportSettingsProblem(Log.ERROR, msg);
                            }
                        }
                    }
                }
                pkg.applicationInfo.dataDir = dataPath.getPath();
                if (mShouldRestoreconData) {
                    Slog.i(TAG, "SELinux relabeling of " + pkg.packageName + " issued.");
                    mInstaller.restoreconData(pkg.volumeUuid, pkg.packageName,
                            pkg.applicationInfo.seinfo, pkg.applicationInfo.uid);
                }
            } else {
                if (DEBUG_PACKAGE_SCANNING) {
                    if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                        Log.v(TAG, "Want this data dir: " + dataPath);
                }
                //invoke installer to do the actual installation
                int ret = createDataDirsLI(pkg.volumeUuid, pkgName, pkg.applicationInfo.uid,
                        pkg.applicationInfo.seinfo);
                if (ret < 0) {
                    // Error from installer
                    throw new PackageManagerException(INSTALL_FAILED_INSUFFICIENT_STORAGE,
                            "Unable to create data dirs [errorCode=" + ret + "]");
                }

                if (dataPath.exists()) {
                    pkg.applicationInfo.dataDir = dataPath.getPath();
                } else {
                    Slog.w(TAG, "Unable to create data directory: " + dataPath);
                    pkg.applicationInfo.dataDir = null;
                }
            }

            pkgSetting.uidError = uidError;
        }

        final String path = scanFile.getPath();
        final String cpuAbiOverride = deriveAbiOverride(pkg.cpuAbiOverride, pkgSetting);

        if ((scanFlags & SCAN_NEW_INSTALL) == 0) {
            derivePackageAbi(pkg, scanFile, cpuAbiOverride, true /* extract libs */);

            // Some system apps still use directory structure for native libraries
            // in which case we might end up not detecting abi solely based on apk
            // structure. Try to detect abi based on directory structure.
            if (isSystemApp(pkg) && !pkg.isUpdatedSystemApp() &&
                    pkg.applicationInfo.primaryCpuAbi == null) {
                setBundledAppAbisAndRoots(pkg, pkgSetting);
                setNativeLibraryPaths(pkg);
            }

        } else {
            if ((scanFlags & SCAN_MOVE) != 0) {
                // We haven't run dex-opt for this move (since we've moved the compiled output too)
                // but we already have this packages package info in the PackageSetting. We just
                // use that and derive the native library path based on the new codepath.
                pkg.applicationInfo.primaryCpuAbi = pkgSetting.primaryCpuAbiString;
                pkg.applicationInfo.secondaryCpuAbi = pkgSetting.secondaryCpuAbiString;
            }

            // Set native library paths again. For moves, the path will be updated based on the
            // ABIs we've determined above. For non-moves, the path will be updated based on the
            // ABIs we determined during compilation, but the path will depend on the final
            // package path (after the rename away from the stage path).
            setNativeLibraryPaths(pkg);
        }

        if (DEBUG_INSTALL) Slog.i(TAG, "Linking native library dir for " + path);
        final int[] userIds = sUserManager.getUserIds();
        synchronized (mInstallLock) {
            // Make sure all user data directories are ready to roll; we're okay
            // if they already exist
            if (!TextUtils.isEmpty(pkg.volumeUuid)) {
                for (int userId : userIds) {
                    if (userId != 0) {
                        mInstaller.createUserData(pkg.volumeUuid, pkg.packageName,
                                UserHandle.getUid(userId, pkg.applicationInfo.uid), userId,
                                pkg.applicationInfo.seinfo);
                    }
                }
            }

            // Create a native library symlink only if we have native libraries
            // and if the native libraries are 32 bit libraries. We do not provide
            // this symlink for 64 bit libraries.
            if (pkg.applicationInfo.primaryCpuAbi != null &&
                    !VMRuntime.is64BitAbi(pkg.applicationInfo.primaryCpuAbi)) {
                final String nativeLibPath = pkg.applicationInfo.nativeLibraryDir;
                for (int userId : userIds) {
                    if (mInstaller.linkNativeLibraryDirectory(pkg.volumeUuid, pkg.packageName,
                            nativeLibPath, userId) < 0) {
                        throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                                "Failed linking native library dir (user=" + userId + ")");
                    }
                }
            }
        }

        // This is a special case for the "system" package, where the ABI is
        // dictated by the zygote configuration (and init.rc). We should keep track
        // of this ABI so that we can deal with "normal" applications that run under
        // the same UID correctly.
        if (mPlatformPackage == pkg) {
            pkg.applicationInfo.primaryCpuAbi = VMRuntime.getRuntime().is64Bit() ?
                    Build.SUPPORTED_64_BIT_ABIS[0] : Build.SUPPORTED_32_BIT_ABIS[0];
        }

        // If there's a mismatch between the abi-override in the package setting
        // and the abiOverride specified for the install. Warn about this because we
        // would've already compiled the app without taking the package setting into
        // account.
        if ((scanFlags & SCAN_NO_DEX) == 0 && (scanFlags & SCAN_NEW_INSTALL) != 0) {
            if (cpuAbiOverride == null && pkgSetting.cpuAbiOverrideString != null) {
                Slog.w(TAG, "Ignoring persisted ABI override " + cpuAbiOverride +
                        " for package: " + pkg.packageName);
            }
        }

        pkgSetting.primaryCpuAbiString = pkg.applicationInfo.primaryCpuAbi;
        pkgSetting.secondaryCpuAbiString = pkg.applicationInfo.secondaryCpuAbi;
        pkgSetting.cpuAbiOverrideString = cpuAbiOverride;

        // Copy the derived override back to the parsed package, so that we can
        // update the package settings accordingly.
        pkg.cpuAbiOverride = cpuAbiOverride;

        if (DEBUG_ABI_SELECTION) {
            Slog.d(TAG, "Resolved nativeLibraryRoot for " + pkg.applicationInfo.packageName
                    + " to root=" + pkg.applicationInfo.nativeLibraryRootDir + ", isa="
                    + pkg.applicationInfo.nativeLibraryRootRequiresIsa);
        }

        // Push the derived path down into PackageSettings so we know what to
        // clean up at uninstall time.
        pkgSetting.legacyNativeLibraryPathString = pkg.applicationInfo.nativeLibraryRootDir;

        if (DEBUG_ABI_SELECTION) {
            Log.d(TAG, "Abis for package[" + pkg.packageName + "] are" +
                    " primary=" + pkg.applicationInfo.primaryCpuAbi +
                    " secondary=" + pkg.applicationInfo.secondaryCpuAbi);
        }

        if ((scanFlags&SCAN_BOOTING) == 0 && pkgSetting.sharedUser != null) {
            // We don't do this here during boot because we can do it all
            // at once after scanning all existing packages.
            //
            // We also do this *before* we perform dexopt on this package, so that
            // we can avoid redundant dexopts, and also to make sure we've got the
            // code and package path correct.
            adjustCpuAbisForSharedUserLPw(pkgSetting.sharedUser.packages,
                    pkg, forceDex, (scanFlags & SCAN_DEFER_DEX) != 0);
        }

        if ((scanFlags & SCAN_NO_DEX) == 0) {
            int result = mPackageDexOptimizer.performDexOpt(pkg, null /* instruction sets */,
                    forceDex, (scanFlags & SCAN_DEFER_DEX) != 0, false /* inclDependencies */);
            if (result == PackageDexOptimizer.DEX_OPT_FAILED) {
                throw new PackageManagerException(INSTALL_FAILED_DEXOPT, "scanPackageLI");
            }
        }
        if (mFactoryTest && pkg.requestedPermissions.contains(
                android.Manifest.permission.FACTORY_TEST)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_FACTORY_TEST;
        }

        ArrayList<PackageParser.Package> clientLibPkgs = null;

        // writer
        synchronized (mPackages) {
            if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                // Only system apps can add new shared libraries.
                if (pkg.libraryNames != null) {
                    for (int i=0; i<pkg.libraryNames.size(); i++) {
                        String name = pkg.libraryNames.get(i);
                        boolean allowed = false;
                        if (pkg.isUpdatedSystemApp()) {
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
                            final PackageSetting sysPs = mSettings
                                    .getDisabledSystemPkgLPr(pkg.packageName);
                            if (sysPs.pkg != null && sysPs.pkg.libraryNames != null) {
                                for (int j=0; j<sysPs.pkg.libraryNames.size(); j++) {
                                    if (name.equals(sysPs.pkg.libraryNames.get(j))) {
                                        allowed = true;
                                        allowed = true;
                                        break;
                                    }
                                }
                            }
                        } else {
                            allowed = true;
                        }
                        if (allowed) {
                            if (!mSharedLibraries.containsKey(name)) {
                                mSharedLibraries.put(name, new SharedLibraryEntry(null, pkg.packageName));
                            } else if (!name.equals(pkg.packageName)) {
                                Slog.w(TAG, "Package " + pkg.packageName + " library "
                                        + name + " already exists; skipping");
                            }
                        } else {
                            Slog.w(TAG, "Package " + pkg.packageName + " declares lib "
                                    + name + " that is not declared on system image; skipping");
                        }
                    }
                    if ((scanFlags&SCAN_BOOTING) == 0) {
                        // If we are not booting, we need to update any applications
                        // that are clients of our shared library.  If we are booting,
                        // this will all be done once the scan is complete.
                        clientLibPkgs = updateAllSharedLibrariesLPw(pkg);
                    }
                }
            }
        }

        // We also need to dexopt any apps that are dependent on this library.  Note that
        // if these fail, we should abort the install since installing the library will
        // result in some apps being broken.
        if (clientLibPkgs != null) {
            if ((scanFlags & SCAN_NO_DEX) == 0) {
                for (int i = 0; i < clientLibPkgs.size(); i++) {
                    PackageParser.Package clientPkg = clientLibPkgs.get(i);
                    int result = mPackageDexOptimizer.performDexOpt(clientPkg,
                            null /* instruction sets */, forceDex,
                            (scanFlags & SCAN_DEFER_DEX) != 0, false);
                    if (result == PackageDexOptimizer.DEX_OPT_FAILED) {
                        throw new PackageManagerException(INSTALL_FAILED_DEXOPT,
                                "scanPackageLI failed to dexopt clientLibPkgs");
                    }
                }
            }
        }

        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        if ((scanFlags & SCAN_REPLACING) != 0) {
            killApplication(pkg.applicationInfo.packageName,
                        pkg.applicationInfo.uid, "replace pkg");
        }

        // Also need to kill any apps that are dependent on the library.
        if (clientLibPkgs != null) {
            for (int i=0; i<clientLibPkgs.size(); i++) {
                PackageParser.Package clientPkg = clientLibPkgs.get(i);
                killApplication(clientPkg.applicationInfo.packageName,
                        clientPkg.applicationInfo.uid, "update lib");
            }
        }

        // Make sure we're not adding any bogus keyset info
        KeySetManagerService ksms = mSettings.mKeySetManagerService;
        ksms.assertScannedPackageValid(pkg);

        // writer
        synchronized (mPackages) {
            // We don't expect installation to fail beyond this point

            // Add the new setting to mSettings
            mSettings.insertPackageSettingLPw(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPackages.put(pkg.applicationInfo.packageName, pkg);
            // Make sure we don't accidentally delete its data.
            final Iterator<PackageCleanItem> iter = mSettings.mPackagesToBeCleaned.iterator();
            while (iter.hasNext()) {
                PackageCleanItem item = iter.next();
                if (pkgName.equals(item.packageName)) {
                    iter.remove();
                }
            }

            // Take care of first install / last update times.
            if (currentTime != 0) {
                if (pkgSetting.firstInstallTime == 0) {
                    pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = currentTime;
                } else if ((scanFlags&SCAN_UPDATE_TIME) != 0) {
                    pkgSetting.lastUpdateTime = currentTime;
                }
            } else if (pkgSetting.firstInstallTime == 0) {
                // We need *something*.  Take time time stamp of the file.
                pkgSetting.firstInstallTime = pkgSetting.lastUpdateTime = scanFileTime;
            } else if ((parseFlags&PackageParser.PARSE_IS_SYSTEM_DIR) != 0) {
                if (scanFileTime != pkgSetting.timeStamp) {
                    // A package on the system image has changed; consider this
                    // to be an update.
                    pkgSetting.lastUpdateTime = scanFileTime;
                }
            }

            // Add the package's KeySets to the global KeySetManagerService
            ksms.addScannedPackageLPw(pkg);

            int N = pkg.providers.size();
            StringBuilder r = null;
            int i;
            for (i=0; i<N; i++) {
                PackageParser.Provider p = pkg.providers.get(i);
                p.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        p.info.processName, pkg.applicationInfo.uid);
                mProviders.addProvider(p);
                p.syncable = p.info.isSyncable;
                if (p.info.authority != null) {
                    String names[] = p.info.authority.split(";");
                    p.info.authority = null;
                    for (int j = 0; j < names.length; j++) {
                        if (j == 1 && p.syncable) {
                            // We only want the first authority for a provider to possibly be
                            // syncable, so if we already added this provider using a different
                            // authority clear the syncable flag. We copy the provider before
                            // changing it because the mProviders object contains a reference
                            // to a provider that we don't want to change.
                            // Only do this for the second authority since the resulting provider
                            // object can be the same for all future authorities for this provider.
                            p = new PackageParser.Provider(p);
                            p.syncable = false;
                        }
                        if (!mProvidersByAuthority.containsKey(names[j])) {
                            mProvidersByAuthority.put(names[j], p);
                            if (p.info.authority == null) {
                                p.info.authority = names[j];
                            } else {
                                p.info.authority = p.info.authority + ";" + names[j];
                            }
                            if (DEBUG_PACKAGE_SCANNING) {
                                if ((parseFlags & PackageParser.PARSE_CHATTY) != 0)
                                    Log.d(TAG, "Registered content provider: " + names[j]
                                            + ", className = " + p.info.name + ", isSyncable = "
                                            + p.info.isSyncable);
                            }
                        } else {
                            PackageParser.Provider other = mProvidersByAuthority.get(names[j]);
                            Slog.w(TAG, "Skipping provider name " + names[j] +
                                    " (in package " + pkg.applicationInfo.packageName +
                                    "): name already used by "
                                    + ((other != null && other.getComponentName() != null)
                                            ? other.getComponentName().getPackageName() : "?"));
                        }
                    }
                }
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Providers: " + r);
            }

            N = pkg.services.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Service s = pkg.services.get(i);
                s.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        s.info.processName, pkg.applicationInfo.uid);
                mServices.addService(s);
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(s.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Services: " + r);
            }

            N = pkg.receivers.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Activity a = pkg.receivers.get(i);
                a.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        a.info.processName, pkg.applicationInfo.uid);
                mReceivers.addActivity(a, "receiver");
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Receivers: " + r);
            }

            N = pkg.activities.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Activity a = pkg.activities.get(i);
                a.info.processName = fixProcessName(pkg.applicationInfo.processName,
                        a.info.processName, pkg.applicationInfo.uid);
                mActivities.addActivity(a, "activity");
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Activities: " + r);
            }

            N = pkg.permissionGroups.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.PermissionGroup pg = pkg.permissionGroups.get(i);
                PackageParser.PermissionGroup cur = mPermissionGroups.get(pg.info.name);
                if (cur == null) {
                    mPermissionGroups.put(pg.info.name, pg);
                    if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(pg.info.name);
                    }
                } else {
                    Slog.w(TAG, "Permission group " + pg.info.name + " from package "
                            + pg.info.packageName + " ignored: original from "
                            + cur.info.packageName);
                    if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append("DUP:");
                        r.append(pg.info.name);
                    }
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Permission Groups: " + r);
            }

            N = pkg.permissions.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Permission p = pkg.permissions.get(i);

                // Assume by default that we did not install this permission into the system.
                p.info.flags &= ~PermissionInfo.FLAG_INSTALLED;

                // Now that permission groups have a special meaning, we ignore permission
                // groups for legacy apps to prevent unexpected behavior. In particular,
                // permissions for one app being granted to someone just becuase they happen
                // to be in a group defined by another app (before this had no implications).
                if (pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
                    p.group = mPermissionGroups.get(p.info.group);
                    // Warn for a permission in an unknown group.
                    if (p.info.group != null && p.group == null) {
                        Slog.w(TAG, "Permission " + p.info.name + " from package "
                                + p.info.packageName + " in an unknown group " + p.info.group);
                    }
                }

                ArrayMap<String, BasePermission> permissionMap =
                        p.tree ? mSettings.mPermissionTrees
                                : mSettings.mPermissions;
                BasePermission bp = permissionMap.get(p.info.name);

                // Allow system apps to redefine non-system permissions
                if (bp != null && !Objects.equals(bp.sourcePackage, p.info.packageName)) {
                    final boolean currentOwnerIsSystem = (bp.perm != null
                            && isSystemApp(bp.perm.owner));
                    if (isSystemApp(p.owner)) {
                        if (bp.type == BasePermission.TYPE_BUILTIN && bp.perm == null) {
                            // It's a built-in permission and no owner, take ownership now
                            bp.packageSetting = pkgSetting;
                            bp.perm = p;
                            bp.uid = pkg.applicationInfo.uid;
                            bp.sourcePackage = p.info.packageName;
                            p.info.flags |= PermissionInfo.FLAG_INSTALLED;
                        } else if (!currentOwnerIsSystem) {
                            String msg = "New decl " + p.owner + " of permission  "
                                    + p.info.name + " is system; overriding " + bp.sourcePackage;
                            reportSettingsProblem(Log.WARN, msg);
                            bp = null;
                        }
                    }
                }

                if (bp == null) {
                    bp = new BasePermission(p.info.name, p.info.packageName,
                            BasePermission.TYPE_NORMAL);
                    permissionMap.put(p.info.name, bp);
                }

                if (bp.perm == null) {
                    if (bp.sourcePackage == null
                            || bp.sourcePackage.equals(p.info.packageName)) {
                        BasePermission tree = findPermissionTreeLP(p.info.name);
                        if (tree == null
                                || tree.sourcePackage.equals(p.info.packageName)) {
                            bp.packageSetting = pkgSetting;
                            bp.perm = p;
                            bp.uid = pkg.applicationInfo.uid;
                            bp.sourcePackage = p.info.packageName;
                            p.info.flags |= PermissionInfo.FLAG_INSTALLED;
                            if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                                if (r == null) {
                                    r = new StringBuilder(256);
                                } else {
                                    r.append(' ');
                                }
                                r.append(p.info.name);
                            }
                        } else {
                            Slog.w(TAG, "Permission " + p.info.name + " from package "
                                    + p.info.packageName + " ignored: base tree "
                                    + tree.name + " is from package "
                                    + tree.sourcePackage);
                        }
                    } else {
                        Slog.w(TAG, "Permission " + p.info.name + " from package "
                                + p.info.packageName + " ignored: original from "
                                + bp.sourcePackage);
                    }
                } else if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append("DUP:");
                    r.append(p.info.name);
                }
                if (bp.perm == p) {
                    bp.protectionLevel = p.info.protectionLevel;
                }
            }

            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Permissions: " + r);
            }

            N = pkg.instrumentation.size();
            r = null;
            for (i=0; i<N; i++) {
                PackageParser.Instrumentation a = pkg.instrumentation.get(i);
                a.info.packageName = pkg.applicationInfo.packageName;
                a.info.sourceDir = pkg.applicationInfo.sourceDir;
                a.info.publicSourceDir = pkg.applicationInfo.publicSourceDir;
                a.info.splitSourceDirs = pkg.applicationInfo.splitSourceDirs;
                a.info.splitPublicSourceDirs = pkg.applicationInfo.splitPublicSourceDirs;
                a.info.dataDir = pkg.applicationInfo.dataDir;

                // TODO: Update instrumentation.nativeLibraryDir as well ? Does it
                // need other information about the application, like the ABI and what not ?
                a.info.nativeLibraryDir = pkg.applicationInfo.nativeLibraryDir;
                mInstrumentation.put(a.getComponentName(), a);
                if ((parseFlags&PackageParser.PARSE_CHATTY) != 0) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.info.name);
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            if (pkg.protectedBroadcasts != null) {
                N = pkg.protectedBroadcasts.size();
                for (i=0; i<N; i++) {
                    mProtectedBroadcasts.add(pkg.protectedBroadcasts.get(i));
                }
            }

            pkgSetting.setTimeStamp(scanFileTime);

            // Create idmap files for pairs of (packages, overlay packages).
            // Note: "android", ie framework-res.apk, is handled by native layers.
            if (pkg.mOverlayTarget != null) {
                // This is an overlay package.
                if (pkg.mOverlayTarget != null && !pkg.mOverlayTarget.equals("android")) {
                    if (!mOverlays.containsKey(pkg.mOverlayTarget)) {
                        mOverlays.put(pkg.mOverlayTarget,
                                new ArrayMap<String, PackageParser.Package>());
                    }
                    ArrayMap<String, PackageParser.Package> map = mOverlays.get(pkg.mOverlayTarget);
                    map.put(pkg.packageName, pkg);
                    PackageParser.Package orig = mPackages.get(pkg.mOverlayTarget);
                    if (orig != null && !createIdmapForPackagePairLI(orig, pkg)) {
                        throw new PackageManagerException(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                "scanPackageLI failed to createIdmap");
                    }
                }
            } else if (mOverlays.containsKey(pkg.packageName) &&
                    !pkg.packageName.equals("android")) {
                // This is a regular package, with one or more known overlay packages.
                createIdmapsForPackageLI(pkg);
            }
        }

        return pkg;
    }

    /**
     * Derive the ABI of a non-system package located at {@code scanFile}. This information
     * is derived purely on the basis of the contents of {@code scanFile} and
     * {@code cpuAbiOverride}.
     *
     * If {@code extractLibs} is true, native libraries are extracted from the app if required.
     */
    public void derivePackageAbi(PackageParser.Package pkg, File scanFile,
                                 String cpuAbiOverride, boolean extractLibs)
            throws PackageManagerException {
        // TODO: We can probably be smarter about this stuff. For installed apps,
        // we can calculate this information at install time once and for all. For
        // system apps, we can probably assume that this information doesn't change
        // after the first boot scan. As things stand, we do lots of unnecessary work.

        // Give ourselves some initial paths; we'll come back for another
        // pass once we've determined ABI below.
        setNativeLibraryPaths(pkg);

        // We would never need to extract libs for forward-locked and external packages,
        // since the container service will do it for us. We shouldn't attempt to
        // extract libs from system app when it was not updated.
        if (pkg.isForwardLocked() || isExternal(pkg) ||
            (isSystemApp(pkg) && !pkg.isUpdatedSystemApp()) ) {
            extractLibs = false;
        }

        final String nativeLibraryRootStr = pkg.applicationInfo.nativeLibraryRootDir;
        final boolean useIsaSpecificSubdirs = pkg.applicationInfo.nativeLibraryRootRequiresIsa;

        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(scanFile);
            // TODO(multiArch): This can be null for apps that didn't go through the
            // usual installation process. We can calculate it again, like we
            // do during install time.
            //
            // TODO(multiArch): Why do we need to rescan ASEC apps again ? It seems totally
            // unnecessary.
            final File nativeLibraryRoot = new File(nativeLibraryRootStr);

            // Null out the abis so that they can be recalculated.
            pkg.applicationInfo.primaryCpuAbi = null;
            pkg.applicationInfo.secondaryCpuAbi = null;
            if (isMultiArch(pkg.applicationInfo)) {
                // Warn if we've set an abiOverride for multi-lib packages..
                // By definition, we need to copy both 32 and 64 bit libraries for
                // such packages.
                if (pkg.cpuAbiOverride != null
                        && !NativeLibraryHelper.CLEAR_ABI_OVERRIDE.equals(pkg.cpuAbiOverride)) {
                    Slog.w(TAG, "Ignoring abiOverride for multi arch application.");
                }

                int abi32 = PackageManager.NO_NATIVE_LIBRARIES;
                int abi64 = PackageManager.NO_NATIVE_LIBRARIES;
                if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                    if (extractLibs) {
                        abi32 = NativeLibraryHelper.copyNativeBinariesForSupportedAbi(handle,
                                nativeLibraryRoot, Build.SUPPORTED_32_BIT_ABIS,
                                useIsaSpecificSubdirs);
                    } else {
                        abi32 = NativeLibraryHelper.findSupportedAbi(handle, Build.SUPPORTED_32_BIT_ABIS);
                    }
                }

                maybeThrowExceptionForMultiArchCopy(
                        "Error unpackaging 32 bit native libs for multiarch app.", abi32);

                if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                    if (extractLibs) {
                        abi64 = NativeLibraryHelper.copyNativeBinariesForSupportedAbi(handle,
                                nativeLibraryRoot, Build.SUPPORTED_64_BIT_ABIS,
                                useIsaSpecificSubdirs);
                    } else {
                        abi64 = NativeLibraryHelper.findSupportedAbi(handle, Build.SUPPORTED_64_BIT_ABIS);
                    }
                }

                maybeThrowExceptionForMultiArchCopy(
                        "Error unpackaging 64 bit native libs for multiarch app.", abi64);

                if (abi64 >= 0) {
                    pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[abi64];
                }

                if (abi32 >= 0) {
                    final String abi = Build.SUPPORTED_32_BIT_ABIS[abi32];
                    if (abi64 >= 0) {
                        pkg.applicationInfo.secondaryCpuAbi = abi;
                    } else {
                        pkg.applicationInfo.primaryCpuAbi = abi;
                    }
                }
            } else {
                String[] abiList = (cpuAbiOverride != null) ?
                        new String[] { cpuAbiOverride } : Build.SUPPORTED_ABIS;

                // Enable gross and lame hacks for apps that are built with old
                // SDK tools. We must scan their APKs for renderscript bitcode and
                // not launch them if it's present. Don't bother checking on devices
                // that don't have 64 bit support.
                boolean needsRenderScriptOverride = false;
                if (Build.SUPPORTED_64_BIT_ABIS.length > 0 && cpuAbiOverride == null &&
                        NativeLibraryHelper.hasRenderscriptBitcode(handle)) {
                    abiList = Build.SUPPORTED_32_BIT_ABIS;
                    needsRenderScriptOverride = true;
                }

                final int copyRet;
                if (extractLibs) {
                    copyRet = NativeLibraryHelper.copyNativeBinariesForSupportedAbi(handle,
                            nativeLibraryRoot, abiList, useIsaSpecificSubdirs);
                } else {
                    copyRet = NativeLibraryHelper.findSupportedAbi(handle, abiList);
                }

                if (copyRet < 0 && copyRet != PackageManager.NO_NATIVE_LIBRARIES) {
                    throw new PackageManagerException(INSTALL_FAILED_INTERNAL_ERROR,
                            "Error unpackaging native libs for app, errorCode=" + copyRet);
                }

                if (copyRet >= 0) {
                    pkg.applicationInfo.primaryCpuAbi = abiList[copyRet];
                } else if (copyRet == PackageManager.NO_NATIVE_LIBRARIES && cpuAbiOverride != null) {
                    pkg.applicationInfo.primaryCpuAbi = cpuAbiOverride;
                } else if (needsRenderScriptOverride) {
                    pkg.applicationInfo.primaryCpuAbi = abiList[0];
                }
            }
        } catch (IOException ioe) {
            Slog.e(TAG, "Unable to get canonical file " + ioe.toString());
        } finally {
            IoUtils.closeQuietly(handle);
        }

        // Now that we've calculated the ABIs and determined if it's an internal app,
        // we will go ahead and populate the nativeLibraryPath.
        setNativeLibraryPaths(pkg);
    }

    /**
     * Adjusts ABIs for a set of packages belonging to a shared user so that they all match.
     * i.e, so that all packages can be run inside a single process if required.
     *
     * Optionally, callers can pass in a parsed package via {@code newPackage} in which case
     * this function will either try and make the ABI for all packages in {@code packagesForUser}
     * match {@code scannedPackage} or will update the ABI of {@code scannedPackage} to match
     * the ABI selected for {@code packagesForUser}. This variant is used when installing or
     * updating a package that belongs to a shared user.
     *
     * NOTE: We currently only match for the primary CPU abi string. Matching the secondary
     * adds unnecessary complexity.
     */
    private void adjustCpuAbisForSharedUserLPw(Set<PackageSetting> packagesForUser,
            PackageParser.Package scannedPackage, boolean forceDexOpt, boolean deferDexOpt) {
        String requiredInstructionSet = null;
        if (scannedPackage != null && scannedPackage.applicationInfo.primaryCpuAbi != null) {
            requiredInstructionSet = VMRuntime.getInstructionSet(
                     scannedPackage.applicationInfo.primaryCpuAbi);
        }

        PackageSetting requirer = null;
        for (PackageSetting ps : packagesForUser) {
            // If packagesForUser contains scannedPackage, we skip it. This will happen
            // when scannedPackage is an update of an existing package. Without this check,
            // we will never be able to change the ABI of any package belonging to a shared
            // user, even if it's compatible with other packages.
            if (scannedPackage == null || !scannedPackage.packageName.equals(ps.name)) {
                if (ps.primaryCpuAbiString == null) {
                    continue;
                }

                final String instructionSet = VMRuntime.getInstructionSet(ps.primaryCpuAbiString);
                if (requiredInstructionSet != null && !instructionSet.equals(requiredInstructionSet)) {
                    // We have a mismatch between instruction sets (say arm vs arm64) warn about
                    // this but there's not much we can do.
                    String errorMessage = "Instruction set mismatch, "
                            + ((requirer == null) ? "[caller]" : requirer)
                            + " requires " + requiredInstructionSet + " whereas " + ps
                            + " requires " + instructionSet;
                    Slog.w(TAG, errorMessage);
                }

                if (requiredInstructionSet == null) {
                    requiredInstructionSet = instructionSet;
                    requirer = ps;
                }
            }
        }

        if (requiredInstructionSet != null) {
            String adjustedAbi;
            if (requirer != null) {
                // requirer != null implies that either scannedPackage was null or that scannedPackage
                // did not require an ABI, in which case we have to adjust scannedPackage to match
                // the ABI of the set (which is the same as requirer's ABI)
                adjustedAbi = requirer.primaryCpuAbiString;
                if (scannedPackage != null) {
                    scannedPackage.applicationInfo.primaryCpuAbi = adjustedAbi;
                }
            } else {
                // requirer == null implies that we're updating all ABIs in the set to
                // match scannedPackage.
                adjustedAbi =  scannedPackage.applicationInfo.primaryCpuAbi;
            }

            for (PackageSetting ps : packagesForUser) {
                if (scannedPackage == null || !scannedPackage.packageName.equals(ps.name)) {
                    if (ps.primaryCpuAbiString != null) {
                        continue;
                    }

                    ps.primaryCpuAbiString = adjustedAbi;
                    if (ps.pkg != null && ps.pkg.applicationInfo != null) {
                        ps.pkg.applicationInfo.primaryCpuAbi = adjustedAbi;
                        Slog.i(TAG, "Adjusting ABI for : " + ps.name + " to " + adjustedAbi);

                        int result = mPackageDexOptimizer.performDexOpt(ps.pkg,
                                null /* instruction sets */, forceDexOpt, deferDexOpt, true);
                        if (result == PackageDexOptimizer.DEX_OPT_FAILED) {
                            ps.primaryCpuAbiString = null;
                            ps.pkg.applicationInfo.primaryCpuAbi = null;
                            return;
                        } else {
                            mInstaller.rmdex(ps.codePathString,
                                    getDexCodeInstructionSet(getPreferredInstructionSet()));
                        }
                    }
                }
            }
        }
    }

    private void setUpCustomResolverActivity(PackageParser.Package pkg) {
        synchronized (mPackages) {
            mResolverReplaced = true;
            // Set up information for custom user intent resolution activity.
            mResolveActivity.applicationInfo = pkg.applicationInfo;
            mResolveActivity.name = mCustomResolverComponentName.getClassName();
            mResolveActivity.packageName = pkg.applicationInfo.packageName;
            mResolveActivity.processName = pkg.applicationInfo.packageName;
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
            Slog.i(TAG, "Replacing default ResolverActivity with custom activity: " +
                    mResolveComponentName);
        }
    }

    private static String calculateBundledApkRoot(final String codePathString) {
        final File codePath = new File(codePathString);
        final File codeRoot;
        if (FileUtils.contains(Environment.getRootDirectory(), codePath)) {
            codeRoot = Environment.getRootDirectory();
        } else if (FileUtils.contains(Environment.getOemDirectory(), codePath)) {
            codeRoot = Environment.getOemDirectory();
        } else if (FileUtils.contains(Environment.getVendorDirectory(), codePath)) {
            codeRoot = Environment.getVendorDirectory();
        } else {
            // Unrecognized code path; take its top real segment as the apk root:
            // e.g. /something/app/blah.apk => /something
            try {
                File f = codePath.getCanonicalFile();
                File parent = f.getParentFile();    // non-null because codePath is a file
                File tmp;
                while ((tmp = parent.getParentFile()) != null) {
                    f = parent;
                    parent = tmp;
                }
                codeRoot = f;
                Slog.w(TAG, "Unrecognized code path "
                        + codePath + " - using " + codeRoot);
            } catch (IOException e) {
                // Can't canonicalize the code path -- shenanigans?
                Slog.w(TAG, "Can't canonicalize code path " + codePath);
                return Environment.getRootDirectory().getPath();
            }
        }
        return codeRoot.getPath();
    }

    /**
     * Derive and set the location of native libraries for the given package,
     * which varies depending on where and how the package was installed.
     */
    private void setNativeLibraryPaths(PackageParser.Package pkg) {
        final ApplicationInfo info = pkg.applicationInfo;
        final String codePath = pkg.codePath;
        final File codeFile = new File(codePath);
        final boolean bundledApp = info.isSystemApp() && !info.isUpdatedSystemApp();
        final boolean asecApp = info.isForwardLocked() || isExternal(info);

        info.nativeLibraryRootDir = null;
        info.nativeLibraryRootRequiresIsa = false;
        info.nativeLibraryDir = null;
        info.secondaryNativeLibraryDir = null;

        if (isApkFile(codeFile)) {
            // Monolithic install
            if (bundledApp) {
                // If "/system/lib64/apkname" exists, assume that is the per-package
                // native library directory to use; otherwise use "/system/lib/apkname".
                final String apkRoot = calculateBundledApkRoot(info.sourceDir);
                final boolean is64Bit = VMRuntime.is64BitInstructionSet(
                        getPrimaryInstructionSet(info));

                // This is a bundled system app so choose the path based on the ABI.
                // if it's a 64 bit abi, use lib64 otherwise use lib32. Note that this
                // is just the default path.
                final String apkName = deriveCodePathName(codePath);
                final String libDir = is64Bit ? LIB64_DIR_NAME : LIB_DIR_NAME;
                info.nativeLibraryRootDir = Environment.buildPath(new File(apkRoot), libDir,
                        apkName).getAbsolutePath();

                if (info.secondaryCpuAbi != null) {
                    final String secondaryLibDir = is64Bit ? LIB_DIR_NAME : LIB64_DIR_NAME;
                    info.secondaryNativeLibraryDir = Environment.buildPath(new File(apkRoot),
                            secondaryLibDir, apkName).getAbsolutePath();
                }
            } else if (asecApp) {
                info.nativeLibraryRootDir = new File(codeFile.getParentFile(), LIB_DIR_NAME)
                        .getAbsolutePath();
            } else {
                final String apkName = deriveCodePathName(codePath);
                info.nativeLibraryRootDir = new File(mAppLib32InstallDir, apkName)
                        .getAbsolutePath();
            }

            info.nativeLibraryRootRequiresIsa = false;
            info.nativeLibraryDir = info.nativeLibraryRootDir;
        } else {
            // Cluster install
            info.nativeLibraryRootDir = new File(codeFile, LIB_DIR_NAME).getAbsolutePath();
            info.nativeLibraryRootRequiresIsa = true;

            info.nativeLibraryDir = new File(info.nativeLibraryRootDir,
                    getPrimaryInstructionSet(info)).getAbsolutePath();

            if (info.secondaryCpuAbi != null) {
                info.secondaryNativeLibraryDir = new File(info.nativeLibraryRootDir,
                        VMRuntime.getInstructionSet(info.secondaryCpuAbi)).getAbsolutePath();
            }
        }
    }

    /**
     * Calculate the abis and roots for a bundled app. These can uniquely
     * be determined from the contents of the system partition, i.e whether
     * it contains 64 or 32 bit shared libraries etc. We do not validate any
     * of this information, and instead assume that the system was built
     * sensibly.
     */
    private void setBundledAppAbisAndRoots(PackageParser.Package pkg,
                                           PackageSetting pkgSetting) {
        final String apkName = deriveCodePathName(pkg.applicationInfo.getCodePath());

        // If "/system/lib64/apkname" exists, assume that is the per-package
        // native library directory to use; otherwise use "/system/lib/apkname".
        final String apkRoot = calculateBundledApkRoot(pkg.applicationInfo.sourceDir);
        setBundledAppAbi(pkg, apkRoot, apkName);
        // pkgSetting might be null during rescan following uninstall of updates
        // to a bundled app, so accommodate that possibility.  The settings in
        // that case will be established later from the parsed package.
        //
        // If the settings aren't null, sync them up with what we've just derived.
        // note that apkRoot isn't stored in the package settings.
        if (pkgSetting != null) {
            pkgSetting.primaryCpuAbiString = pkg.applicationInfo.primaryCpuAbi;
            pkgSetting.secondaryCpuAbiString = pkg.applicationInfo.secondaryCpuAbi;
        }
    }

    /**
     * Deduces the ABI of a bundled app and sets the relevant fields on the
     * parsed pkg object.
     *
     * @param apkRoot the root of the installed apk, something like {@code /system} or {@code /oem}
     *        under which system libraries are installed.
     * @param apkName the name of the installed package.
     */
    private static void setBundledAppAbi(PackageParser.Package pkg, String apkRoot, String apkName) {
        final File codeFile = new File(pkg.codePath);

        final boolean has64BitLibs;
        final boolean has32BitLibs;
        if (isApkFile(codeFile)) {
            // Monolithic install
            has64BitLibs = (new File(apkRoot, new File(LIB64_DIR_NAME, apkName).getPath())).exists();
            has32BitLibs = (new File(apkRoot, new File(LIB_DIR_NAME, apkName).getPath())).exists();
        } else {
            // Cluster install
            final File rootDir = new File(codeFile, LIB_DIR_NAME);
            if (!ArrayUtils.isEmpty(Build.SUPPORTED_64_BIT_ABIS)
                    && !TextUtils.isEmpty(Build.SUPPORTED_64_BIT_ABIS[0])) {
                final String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_64_BIT_ABIS[0]);
                has64BitLibs = (new File(rootDir, isa)).exists();
            } else {
                has64BitLibs = false;
            }
            if (!ArrayUtils.isEmpty(Build.SUPPORTED_32_BIT_ABIS)
                    && !TextUtils.isEmpty(Build.SUPPORTED_32_BIT_ABIS[0])) {
                final String isa = VMRuntime.getInstructionSet(Build.SUPPORTED_32_BIT_ABIS[0]);
                has32BitLibs = (new File(rootDir, isa)).exists();
            } else {
                has32BitLibs = false;
            }
        }

        if (has64BitLibs && !has32BitLibs) {
            // The package has 64 bit libs, but not 32 bit libs. Its primary
            // ABI should be 64 bit. We can safely assume here that the bundled
            // native libraries correspond to the most preferred ABI in the list.

            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else if (has32BitLibs && !has64BitLibs) {
            // The package has 32 bit libs but not 64 bit libs. Its primary
            // ABI should be 32 bit.

            pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            pkg.applicationInfo.secondaryCpuAbi = null;
        } else if (has32BitLibs && has64BitLibs) {
            // The application has both 64 and 32 bit bundled libraries. We check
            // here that the app declares multiArch support, and warn if it doesn't.
            //
            // We will be lenient here and record both ABIs. The primary will be the
            // ABI that's higher on the list, i.e, a device that's configured to prefer
            // 64 bit apps will see a 64 bit primary ABI,

            if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_MULTIARCH) == 0) {
                Slog.e(TAG, "Package: " + pkg + " has multiple bundled libs, but is not multiarch.");
            }

            if (VMRuntime.is64BitInstructionSet(getPreferredInstructionSet())) {
                pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
                pkg.applicationInfo.secondaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
            } else {
                pkg.applicationInfo.primaryCpuAbi = Build.SUPPORTED_32_BIT_ABIS[0];
                pkg.applicationInfo.secondaryCpuAbi = Build.SUPPORTED_64_BIT_ABIS[0];
            }
        } else {
            pkg.applicationInfo.primaryCpuAbi = null;
            pkg.applicationInfo.secondaryCpuAbi = null;
        }
    }

    private void killApplication(String pkgName, int appId, String reason) {
        // Request the ActivityManager to kill the process(only for existing packages)
        // so that we do not end up in a confused state while the user is still using the older
        // version of the application while the new one gets installed.
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                am.killApplicationWithAppId(pkgName, appId, reason);
            } catch (RemoteException e) {
            }
        }
    }

    void removePackageLI(PackageSetting ps, boolean chatty) {
        if (DEBUG_INSTALL) {
            if (chatty)
                Log.d(TAG, "Removing package " + ps.name);
        }

        // writer
        synchronized (mPackages) {
            mPackages.remove(ps.name);
            final PackageParser.Package pkg = ps.pkg;
            if (pkg != null) {
                cleanPackageDataStructuresLILPw(pkg, chatty);
            }
        }
    }

    void removeInstalledPackageLI(PackageParser.Package pkg, boolean chatty) {
        if (DEBUG_INSTALL) {
            if (chatty)
                Log.d(TAG, "Removing package " + pkg.applicationInfo.packageName);
        }

        // writer
        synchronized (mPackages) {
            mPackages.remove(pkg.applicationInfo.packageName);
            cleanPackageDataStructuresLILPw(pkg, chatty);
        }
    }

    void cleanPackageDataStructuresLILPw(PackageParser.Package pkg, boolean chatty) {
        int N = pkg.providers.size();
        StringBuilder r = null;
        int i;
        for (i=0; i<N; i++) {
            PackageParser.Provider p = pkg.providers.get(i);
            mProviders.removeProvider(p);
            if (p.info.authority == null) {

                /* There was another ContentProvider with this authority when
                 * this app was installed so this authority is null,
                 * Ignore it as we don't have to unregister the provider.
                 */
                continue;
            }
            String names[] = p.info.authority.split(";");
            for (int j = 0; j < names.length; j++) {
                if (mProvidersByAuthority.get(names[j]) == p) {
                    mProvidersByAuthority.remove(names[j]);
                    if (DEBUG_REMOVE) {
                        if (chatty)
                            Log.d(TAG, "Unregistered content provider: " + names[j]
                                    + ", className = " + p.info.name + ", isSyncable = "
                                    + p.info.isSyncable);
                    }
                }
            }
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(p.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Providers: " + r);
        }

        N = pkg.services.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Service s = pkg.services.get(i);
            mServices.removeService(s);
            if (chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(s.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Services: " + r);
        }

        N = pkg.receivers.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Activity a = pkg.receivers.get(i);
            mReceivers.removeActivity(a, "receiver");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Receivers: " + r);
        }

        N = pkg.activities.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Activity a = pkg.activities.get(i);
            mActivities.removeActivity(a, "activity");
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Activities: " + r);
        }

        N = pkg.permissions.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Permission p = pkg.permissions.get(i);
            BasePermission bp = mSettings.mPermissions.get(p.info.name);
            if (bp == null) {
                bp = mSettings.mPermissionTrees.get(p.info.name);
            }
            if (bp != null && bp.perm == p) {
                bp.perm = null;
                if (DEBUG_REMOVE && chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(p.info.name);
                }
            }
            if ((p.info.protectionLevel&PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
                ArraySet<String> appOpPerms = mAppOpPermissionPackages.get(p.info.name);
                if (appOpPerms != null) {
                    appOpPerms.remove(pkg.packageName);
                }
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
        }

        N = pkg.requestedPermissions.size();
        r = null;
        for (i=0; i<N; i++) {
            String perm = pkg.requestedPermissions.get(i);
            BasePermission bp = mSettings.mPermissions.get(perm);
            if (bp != null && (bp.protectionLevel&PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
                ArraySet<String> appOpPerms = mAppOpPermissionPackages.get(perm);
                if (appOpPerms != null) {
                    appOpPerms.remove(pkg.packageName);
                    if (appOpPerms.isEmpty()) {
                        mAppOpPermissionPackages.remove(perm);
                    }
                }
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Permissions: " + r);
        }

        N = pkg.instrumentation.size();
        r = null;
        for (i=0; i<N; i++) {
            PackageParser.Instrumentation a = pkg.instrumentation.get(i);
            mInstrumentation.remove(a.getComponentName());
            if (DEBUG_REMOVE && chatty) {
                if (r == null) {
                    r = new StringBuilder(256);
                } else {
                    r.append(' ');
                }
                r.append(a.info.name);
            }
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Instrumentation: " + r);
        }

        r = null;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
            // Only system apps can hold shared libraries.
            if (pkg.libraryNames != null) {
                for (i=0; i<pkg.libraryNames.size(); i++) {
                    String name = pkg.libraryNames.get(i);
                    SharedLibraryEntry cur = mSharedLibraries.get(name);
                    if (cur != null && cur.apk != null && cur.apk.equals(pkg.packageName)) {
                        mSharedLibraries.remove(name);
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
        }
        if (r != null) {
            if (DEBUG_REMOVE) Log.d(TAG, "  Libraries: " + r);
        }
    }

    private static boolean hasPermission(PackageParser.Package pkgInfo, String perm) {
        for (int i=pkgInfo.permissions.size()-1; i>=0; i--) {
            if (pkgInfo.permissions.get(i).info.name.equals(perm)) {
                return true;
            }
        }
        return false;
    }

    static final int UPDATE_PERMISSIONS_ALL = 1<<0;
    static final int UPDATE_PERMISSIONS_REPLACE_PKG = 1<<1;
    static final int UPDATE_PERMISSIONS_REPLACE_ALL = 1<<2;

    private void updatePermissionsLPw(String changingPkg,
            PackageParser.Package pkgInfo, int flags) {
        // Make sure there are no dangling permission trees.
        Iterator<BasePermission> it = mSettings.mPermissionTrees.values().iterator();
        while (it.hasNext()) {
            final BasePermission bp = it.next();
            if (bp.packageSetting == null) {
                // We may not yet have parsed the package, so just see if
                // we still know about its settings.
                bp.packageSetting = mSettings.mPackages.get(bp.sourcePackage);
            }
            if (bp.packageSetting == null) {
                Slog.w(TAG, "Removing dangling permission tree: " + bp.name
                        + " from package " + bp.sourcePackage);
                it.remove();
            } else if (changingPkg != null && changingPkg.equals(bp.sourcePackage)) {
                if (pkgInfo == null || !hasPermission(pkgInfo, bp.name)) {
                    Slog.i(TAG, "Removing old permission tree: " + bp.name
                            + " from package " + bp.sourcePackage);
                    flags |= UPDATE_PERMISSIONS_ALL;
                    it.remove();
                }
            }
        }

        // Make sure all dynamic permissions have been assigned to a package,
        // and make sure there are no dangling permissions.
        it = mSettings.mPermissions.values().iterator();
        while (it.hasNext()) {
            final BasePermission bp = it.next();
            if (bp.type == BasePermission.TYPE_DYNAMIC) {
                if (DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                        + bp.name + " pkg=" + bp.sourcePackage
                        + " info=" + bp.pendingInfo);
                if (bp.packageSetting == null && bp.pendingInfo != null) {
                    final BasePermission tree = findPermissionTreeLP(bp.name);
                    if (tree != null && tree.perm != null) {
                        bp.packageSetting = tree.packageSetting;
                        bp.perm = new PackageParser.Permission(tree.perm.owner,
                                new PermissionInfo(bp.pendingInfo));
                        bp.perm.info.packageName = tree.perm.info.packageName;
                        bp.perm.info.name = bp.name;
                        bp.uid = tree.uid;
                    }
                }
            }
            if (bp.packageSetting == null) {
                // We may not yet have parsed the package, so just see if
                // we still know about its settings.
                bp.packageSetting = mSettings.mPackages.get(bp.sourcePackage);
            }
            if (bp.packageSetting == null) {
                Slog.w(TAG, "Removing dangling permission: " + bp.name
                        + " from package " + bp.sourcePackage);
                it.remove();
            } else if (changingPkg != null && changingPkg.equals(bp.sourcePackage)) {
                if (pkgInfo == null || !hasPermission(pkgInfo, bp.name)) {
                    Slog.i(TAG, "Removing old permission: " + bp.name
                            + " from package " + bp.sourcePackage);
                    flags |= UPDATE_PERMISSIONS_ALL;
                    it.remove();
                }
            }
        }

        // Now update the permissions for all packages, in particular
        // replace the granted permissions of the system packages.
        if ((flags&UPDATE_PERMISSIONS_ALL) != 0) {
            for (PackageParser.Package pkg : mPackages.values()) {
                if (pkg != pkgInfo) {
                    grantPermissionsLPw(pkg, (flags&UPDATE_PERMISSIONS_REPLACE_ALL) != 0,
                            changingPkg);
                }
            }
        }

        if (pkgInfo != null) {
            grantPermissionsLPw(pkgInfo, (flags&UPDATE_PERMISSIONS_REPLACE_PKG) != 0, changingPkg);
        }
    }

    private void grantPermissionsLPw(PackageParser.Package pkg, boolean replace,
            String packageOfInterest) {
        // IMPORTANT: There are two types of permissions: install and runtime.
        // Install time permissions are granted when the app is installed to
        // all device users and users added in the future. Runtime permissions
        // are granted at runtime explicitly to specific users. Normal and signature
        // protected permissions are install time permissions. Dangerous permissions
        // are install permissions if the app's target SDK is Lollipop MR1 or older,
        // otherwise they are runtime permissions. This function does not manage
        // runtime permissions except for the case an app targeting Lollipop MR1
        // being upgraded to target a newer SDK, in which case dangerous permissions
        // are transformed from install time to runtime ones.

        final PackageSetting ps = (PackageSetting) pkg.mExtras;
        if (ps == null) {
            return;
        }

        PermissionsState permissionsState = ps.getPermissionsState();
        PermissionsState origPermissions = permissionsState;

        final int[] currentUserIds = UserManagerService.getInstance().getUserIds();

        int[] changedRuntimePermissionUserIds = EMPTY_INT_ARRAY;

        boolean changedInstallPermission = false;

        if (replace) {
            ps.installPermissionsFixed = false;
            if (!ps.isSharedUser()) {
                origPermissions = new PermissionsState(permissionsState);
                permissionsState.reset();
            }
        }

        permissionsState.setGlobalGids(mGlobalGids);

        final int N = pkg.requestedPermissions.size();
        for (int i=0; i<N; i++) {
            final String name = pkg.requestedPermissions.get(i);
            final BasePermission bp = mSettings.mPermissions.get(name);

            if (DEBUG_INSTALL) {
                Log.i(TAG, "Package " + pkg.packageName + " checking " + name + ": " + bp);
            }

            if (bp == null || bp.packageSetting == null) {
                if (packageOfInterest == null || packageOfInterest.equals(pkg.packageName)) {
                    Slog.w(TAG, "Unknown permission " + name
                            + " in package " + pkg.packageName);
                }
                continue;
            }

            final String perm = bp.name;
            boolean allowedSig = false;
            int grant = GRANT_DENIED;

            // Keep track of app op permissions.
            if ((bp.protectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
                ArraySet<String> pkgs = mAppOpPermissionPackages.get(bp.name);
                if (pkgs == null) {
                    pkgs = new ArraySet<>();
                    mAppOpPermissionPackages.put(bp.name, pkgs);
                }
                pkgs.add(pkg.packageName);
            }

            final int level = bp.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            switch (level) {
                case PermissionInfo.PROTECTION_NORMAL: {
                    // For all apps normal permissions are install time ones.
                    grant = GRANT_INSTALL;
                } break;

                case PermissionInfo.PROTECTION_DANGEROUS: {
                    if (pkg.applicationInfo.targetSdkVersion <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        // For legacy apps dangerous permissions are install time ones.
                        grant = GRANT_INSTALL_LEGACY;
                    } else if (origPermissions.hasInstallPermission(bp.name)) {
                        // For legacy apps that became modern, install becomes runtime.
                        grant = GRANT_UPGRADE;
                    } else if (mPromoteSystemApps
                            && isSystemApp(ps)
                            && mExistingSystemPackages.contains(ps.name)) {
                        // For legacy system apps, install becomes runtime.
                        // We cannot check hasInstallPermission() for system apps since those
                        // permissions were granted implicitly and not persisted pre-M.
                        grant = GRANT_UPGRADE;
                    } else {
                        // For modern apps keep runtime permissions unchanged.
                        grant = GRANT_RUNTIME;
                    }
                } break;

                case PermissionInfo.PROTECTION_SIGNATURE: {
                    // For all apps signature permissions are install time ones.
                    allowedSig = grantSignaturePermission(perm, pkg, bp, origPermissions);
                    if (allowedSig) {
                        grant = GRANT_INSTALL;
                    }
                } break;
            }

            if (DEBUG_INSTALL) {
                Log.i(TAG, "Package " + pkg.packageName + " granting " + perm);
            }

            if (grant != GRANT_DENIED) {
                if (!isSystemApp(ps) && ps.installPermissionsFixed) {
                    // If this is an existing, non-system package, then
                    // we can't add any new permissions to it.
                    if (!allowedSig && !origPermissions.hasInstallPermission(perm)) {
                        // Except...  if this is a permission that was added
                        // to the platform (note: need to only do this when
                        // updating the platform).
                        if (!isNewPlatformPermissionForPackage(perm, pkg)) {
                            grant = GRANT_DENIED;
                        }
                    }
                }

                switch (grant) {
                    case GRANT_INSTALL: {
                        // Revoke this as runtime permission to handle the case of
                        // a runtime permission being downgraded to an install one.
                        for (int userId : UserManagerService.getInstance().getUserIds()) {
                            if (origPermissions.getRuntimePermissionState(
                                    bp.name, userId) != null) {
                                // Revoke the runtime permission and clear the flags.
                                origPermissions.revokeRuntimePermission(bp, userId);
                                origPermissions.updatePermissionFlags(bp, userId,
                                      PackageManager.MASK_PERMISSION_FLAGS, 0);
                                // If we revoked a permission permission, we have to write.
                                changedRuntimePermissionUserIds = ArrayUtils.appendInt(
                                        changedRuntimePermissionUserIds, userId);
                            }
                        }
                        // Grant an install permission.
                        if (permissionsState.grantInstallPermission(bp) !=
                                PermissionsState.PERMISSION_OPERATION_FAILURE) {
                            changedInstallPermission = true;
                        }
                    } break;

                    case GRANT_INSTALL_LEGACY: {
                        // Grant an install permission.
                        if (permissionsState.grantInstallPermission(bp) !=
                                PermissionsState.PERMISSION_OPERATION_FAILURE) {
                            changedInstallPermission = true;
                        }
                    } break;

                    case GRANT_RUNTIME: {
                        // Grant previously granted runtime permissions.
                        for (int userId : UserManagerService.getInstance().getUserIds()) {
                            PermissionState permissionState = origPermissions
                                    .getRuntimePermissionState(bp.name, userId);
                            final int flags = permissionState != null
                                    ? permissionState.getFlags() : 0;
                            if (origPermissions.hasRuntimePermission(bp.name, userId)) {
                                if (permissionsState.grantRuntimePermission(bp, userId) ==
                                        PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                    // If we cannot put the permission as it was, we have to write.
                                    changedRuntimePermissionUserIds = ArrayUtils.appendInt(
                                            changedRuntimePermissionUserIds, userId);
                                }
                            }
                            // Propagate the permission flags.
                            permissionsState.updatePermissionFlags(bp, userId, flags, flags);
                        }
                    } break;

                    case GRANT_UPGRADE: {
                        // Grant runtime permissions for a previously held install permission.
                        PermissionState permissionState = origPermissions
                                .getInstallPermissionState(bp.name);
                        final int flags = permissionState != null ? permissionState.getFlags() : 0;

                        if (origPermissions.revokeInstallPermission(bp)
                                != PermissionsState.PERMISSION_OPERATION_FAILURE) {
                            // We will be transferring the permission flags, so clear them.
                            origPermissions.updatePermissionFlags(bp, UserHandle.USER_ALL,
                                    PackageManager.MASK_PERMISSION_FLAGS, 0);
                            changedInstallPermission = true;
                        }

                        // If the permission is not to be promoted to runtime we ignore it and
                        // also its other flags as they are not applicable to install permissions.
                        if ((flags & PackageManager.FLAG_PERMISSION_REVOKE_ON_UPGRADE) == 0) {
                            for (int userId : currentUserIds) {
                                if (permissionsState.grantRuntimePermission(bp, userId) !=
                                        PermissionsState.PERMISSION_OPERATION_FAILURE) {
                                    // Transfer the permission flags.
                                    permissionsState.updatePermissionFlags(bp, userId,
                                            flags, flags);
                                    // If we granted the permission, we have to write.
                                    changedRuntimePermissionUserIds = ArrayUtils.appendInt(
                                            changedRuntimePermissionUserIds, userId);
                                }
                            }
                        }
                    } break;

                    default: {
                        if (packageOfInterest == null
                                || packageOfInterest.equals(pkg.packageName)) {
                            Slog.w(TAG, "Not granting permission " + perm
                                    + " to package " + pkg.packageName
                                    + " because it was previously installed without");
                        }
                    } break;
                }
            } else {
                if (permissionsState.revokeInstallPermission(bp) !=
                        PermissionsState.PERMISSION_OPERATION_FAILURE) {
                    // Also drop the permission flags.
                    permissionsState.updatePermissionFlags(bp, UserHandle.USER_ALL,
                            PackageManager.MASK_PERMISSION_FLAGS, 0);
                    changedInstallPermission = true;
                    Slog.i(TAG, "Un-granting permission " + perm
                            + " from package " + pkg.packageName
                            + " (protectionLevel=" + bp.protectionLevel
                            + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                            + ")");
                } else if ((bp.protectionLevel&PermissionInfo.PROTECTION_FLAG_APPOP) == 0) {
                    // Don't print warning for app op permissions, since it is fine for them
                    // not to be granted, there is a UI for the user to decide.
                    if (packageOfInterest == null || packageOfInterest.equals(pkg.packageName)) {
                        Slog.w(TAG, "Not granting permission " + perm
                                + " to package " + pkg.packageName
                                + " (protectionLevel=" + bp.protectionLevel
                                + " flags=0x" + Integer.toHexString(pkg.applicationInfo.flags)
                                + ")");
                    }
                }
            }
        }

        if ((changedInstallPermission || replace) && !ps.installPermissionsFixed &&
                !isSystemApp(ps) || isUpdatedSystemApp(ps)){
            // This is the first that we have heard about this package, so the
            // permissions we have now selected are fixed until explicitly
            // changed.
            ps.installPermissionsFixed = true;
        }

        // Persist the runtime permissions state for users with changes.
        for (int userId : changedRuntimePermissionUserIds) {
            mSettings.writeRuntimePermissionsForUserLPr(userId, false);
        }
    }

    private boolean isNewPlatformPermissionForPackage(String perm, PackageParser.Package pkg) {
        boolean allowed = false;
        final int NP = PackageParser.NEW_PERMISSIONS.length;
        for (int ip=0; ip<NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (npi.name.equals(perm)
                    && pkg.applicationInfo.targetSdkVersion < npi.sdkVersion) {
                allowed = true;
                Log.i(TAG, "Auto-granting " + perm + " to old pkg "
                        + pkg.packageName);
                break;
            }
        }
        return allowed;
    }

    private boolean grantSignaturePermission(String perm, PackageParser.Package pkg,
            BasePermission bp, PermissionsState origPermissions) {
        boolean allowed;
        allowed = (compareSignatures(
                bp.packageSetting.signatures.mSignatures, pkg.mSignatures)
                        == PackageManager.SIGNATURE_MATCH)
                || (compareSignatures(mPlatformPackage.mSignatures, pkg.mSignatures)
                        == PackageManager.SIGNATURE_MATCH);
        if (!allowed && (bp.protectionLevel
                & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
            if (isSystemApp(pkg)) {
                // For updated system applications, a system permission
                // is granted only if it had been defined by the original application.
                if (pkg.isUpdatedSystemApp()) {
                    final PackageSetting sysPs = mSettings
                            .getDisabledSystemPkgLPr(pkg.packageName);
                    if (sysPs.getPermissionsState().hasInstallPermission(perm)) {
                        // If the original was granted this permission, we take
                        // that grant decision as read and propagate it to the
                        // update.
                        if (sysPs.isPrivileged()) {
                            allowed = true;
                        }
                    } else {
                        // The system apk may have been updated with an older
                        // version of the one on the data partition, but which
                        // granted a new system permission that it didn't have
                        // before.  In this case we do want to allow the app to
                        // now get the new permission if the ancestral apk is
                        // privileged to get it.
                        if (sysPs.pkg != null && sysPs.isPrivileged()) {
                            for (int j=0;
                                    j<sysPs.pkg.requestedPermissions.size(); j++) {
                                if (perm.equals(
                                        sysPs.pkg.requestedPermissions.get(j))) {
                                    allowed = true;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    allowed = isPrivilegedApp(pkg);
                }
            }
        }
        if (!allowed) {
            if (!allowed && (bp.protectionLevel
                    & PermissionInfo.PROTECTION_FLAG_PRE23) != 0
                    && pkg.applicationInfo.targetSdkVersion < Build.VERSION_CODES.M) {
                // If this was a previously normal/dangerous permission that got moved
                // to a system permission as part of the runtime permission redesign, then
                // we still want to blindly grant it to old apps.
                allowed = true;
            }
            if (!allowed && (bp.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0
                    && pkg.packageName.equals(mRequiredInstallerPackage)) {
                // If this permission is to be granted to the system installer and
                // this app is an installer, then it gets the permission.
                allowed = true;
            }
            if (!allowed && (bp.protectionLevel & PermissionInfo.PROTECTION_FLAG_VERIFIER) != 0
                    && pkg.packageName.equals(mRequiredVerifierPackage)) {
                // If this permission is to be granted to the system verifier and
                // this app is a verifier, then it gets the permission.
                allowed = true;
            }
            if (!allowed && (bp.protectionLevel
                    & PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0
                    && isSystemApp(pkg)) {
                // Any pre-installed system app is allowed to get this permission.
                allowed = true;
            }
            if (!allowed && (bp.protectionLevel
                    & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
                // For development permissions, a development permission
                // is granted only if it was already granted.
                allowed = origPermissions.hasInstallPermission(perm);
            }
        }
        return allowed;
    }

    final class ActivityIntentResolver
            extends IntentResolver<PackageParser.ActivityIntentInfo, ResolveInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, ArrayList<PackageParser.Activity> packageActivities, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (packageActivities == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int N = packageActivities.size();
            ArrayList<PackageParser.ActivityIntentInfo[]> listCut =
                new ArrayList<PackageParser.ActivityIntentInfo[]>(N);

            ArrayList<PackageParser.ActivityIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageActivities.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ActivityIntentInfo[] array =
                            new PackageParser.ActivityIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        public final void addActivity(PackageParser.Activity a, String type) {
            final boolean systemApp = a.info.applicationInfo.isSystemApp();
            mActivities.put(a.getComponentName(), a);
            if (DEBUG_SHOW_INFO)
                Log.v(
                TAG, "  " + type + " " +
                (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel : a.info.name) + ":");
            if (DEBUG_SHOW_INFO)
                Log.v(TAG, "    Class=" + a.info.name);
            final int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (!systemApp && intent.getPriority() > 0 && "activity".equals(type)) {
                    intent.setPriority(0);
                    Log.w(TAG, "Package " + a.info.applicationInfo.packageName + " has activity "
                            + a.className + " with priority > 0, forcing to 0");
                }
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Activity " + a.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeActivity(PackageParser.Activity a, String type) {
            mActivities.remove(a.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + type + " "
                        + (a.info.nonLocalizedLabel != null ? a.info.nonLocalizedLabel
                                : a.info.name) + ":");
                Log.v(TAG, "    Class=" + a.info.name);
            }
            final int NI = a.intents.size();
            for (int j=0; j<NI; j++) {
                PackageParser.ActivityIntentInfo intent = a.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ActivityIntentInfo filter, List<ResolveInfo> dest) {
            ActivityInfo filterAi = filter.activity.info;
            for (int i=dest.size()-1; i>=0; i--) {
                ActivityInfo destAi = dest.get(i).activityInfo;
                if (destAi.name == filterAi.name
                        && destAi.packageName == filterAi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected ActivityIntentInfo[] newArray(int size) {
            return new ActivityIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ActivityIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;
            PackageParser.Package p = filter.activity.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting)p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ActivityIntentInfo info) {
            return packageName.equals(info.activity.owner.packageName);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ActivityIntentInfo info,
                int match, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (!mSettings.isEnabledLPr(info.activity.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Activity activity = info.activity;
            if (mSafeMode && (activity.info.applicationInfo.flags
                    &ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            PackageSetting ps = (PackageSetting) activity.owner.mExtras;
            if (ps == null) {
                return null;
            }
            ActivityInfo ai = PackageParser.generateActivityInfo(activity, mFlags,
                    ps.readUserState(userId), userId);
            if (ai == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.activityInfo = ai;
            if ((mFlags&PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = info;
            }
            if (info != null) {
                res.handleAllWebDataURI = info.handleAllWebDataURI();
            }
            res.priority = info.getPriority();
            res.preferredOrder = activity.owner.mPreferredOrder;
            //System.out.println("Result: " + res.activityInfo.className +
            //                   " = " + res.priority);
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            if (userNeedsBadging(userId)) {
                res.noResourceId = true;
            } else {
                res.icon = info.icon;
            }
            res.iconResourceId = info.icon;
            res.system = res.activityInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ActivityIntentInfo filter) {
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(filter.activity)));
                    out.print(' ');
                    filter.activity.printComponentShortName(out);
                    out.print(" filter ");
                    out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(PackageParser.ActivityIntentInfo filter) {
            return filter.activity;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Activity activity = (PackageParser.Activity)label;
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(activity)));
                    out.print(' ');
                    activity.printComponentShortName(out);
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

//        List<ResolveInfo> filterEnabled(List<ResolveInfo> resolveInfoList) {
//            final Iterator<ResolveInfo> i = resolveInfoList.iterator();
//            final List<ResolveInfo> retList = Lists.newArrayList();
//            while (i.hasNext()) {
//                final ResolveInfo resolveInfo = i.next();
//                if (isEnabledLP(resolveInfo.activityInfo)) {
//                    retList.add(resolveInfo);
//                }
//            }
//            return retList;
//        }

        // Keys are String (activity class name), values are Activity.
        private final ArrayMap<ComponentName, PackageParser.Activity> mActivities
                = new ArrayMap<ComponentName, PackageParser.Activity>();
        private int mFlags;
    }

    private final class ServiceIntentResolver
            extends IntentResolver<PackageParser.ServiceIntentInfo, ResolveInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId)) return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, ArrayList<PackageParser.Service> packageServices, int userId) {
            if (!sUserManager.exists(userId)) return null;
            if (packageServices == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags&PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int N = packageServices.size();
            ArrayList<PackageParser.ServiceIntentInfo[]> listCut =
                new ArrayList<PackageParser.ServiceIntentInfo[]>(N);

            ArrayList<PackageParser.ServiceIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageServices.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ServiceIntentInfo[] array =
                            new PackageParser.ServiceIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        public final void addService(PackageParser.Service s) {
            mServices.put(s.getComponentName(), s);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int NI = s.intents.size();
            int j;
            for (j=0; j<NI; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Service " + s.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeService(PackageParser.Service s) {
            mServices.remove(s.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (s.info.nonLocalizedLabel != null
                        ? s.info.nonLocalizedLabel : s.info.name) + ":");
                Log.v(TAG, "    Class=" + s.info.name);
            }
            final int NI = s.intents.size();
            int j;
            for (j=0; j<NI; j++) {
                PackageParser.ServiceIntentInfo intent = s.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ServiceIntentInfo filter, List<ResolveInfo> dest) {
            ServiceInfo filterSi = filter.service.info;
            for (int i=dest.size()-1; i>=0; i--) {
                ServiceInfo destAi = dest.get(i).serviceInfo;
                if (destAi.name == filterSi.name
                        && destAi.packageName == filterSi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected PackageParser.ServiceIntentInfo[] newArray(int size) {
            return new PackageParser.ServiceIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ServiceIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId)) return true;
            PackageParser.Package p = filter.service.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting)p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ServiceIntentInfo info) {
            return packageName.equals(info.service.owner.packageName);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ServiceIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId)) return null;
            final PackageParser.ServiceIntentInfo info = (PackageParser.ServiceIntentInfo)filter;
            if (!mSettings.isEnabledLPr(info.service.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Service service = info.service;
            if (mSafeMode && (service.info.applicationInfo.flags
                    &ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            PackageSetting ps = (PackageSetting) service.owner.mExtras;
            if (ps == null) {
                return null;
            }
            ServiceInfo si = PackageParser.generateServiceInfo(service, mFlags,
                    ps.readUserState(userId), userId);
            if (si == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.serviceInfo = si;
            if ((mFlags&PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = info.getPriority();
            res.preferredOrder = service.owner.mPreferredOrder;
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = res.serviceInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ServiceIntentInfo filter) {
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(filter.service)));
                    out.print(' ');
                    filter.service.printComponentShortName(out);
                    out.print(" filter ");
                    out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(PackageParser.ServiceIntentInfo filter) {
            return filter.service;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Service service = (PackageParser.Service)label;
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(service)));
                    out.print(' ');
                    service.printComponentShortName(out);
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

//        List<ResolveInfo> filterEnabled(List<ResolveInfo> resolveInfoList) {
//            final Iterator<ResolveInfo> i = resolveInfoList.iterator();
//            final List<ResolveInfo> retList = Lists.newArrayList();
//            while (i.hasNext()) {
//                final ResolveInfo resolveInfo = (ResolveInfo) i;
//                if (isEnabledLP(resolveInfo.serviceInfo)) {
//                    retList.add(resolveInfo);
//                }
//            }
//            return retList;
//        }

        // Keys are String (activity class name), values are Activity.
        private final ArrayMap<ComponentName, PackageParser.Service> mServices
                = new ArrayMap<ComponentName, PackageParser.Service>();
        private int mFlags;
    };

    private final class ProviderIntentResolver
            extends IntentResolver<PackageParser.ProviderIntentInfo, ResolveInfo> {
        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType,
                boolean defaultOnly, int userId) {
            mFlags = defaultOnly ? PackageManager.MATCH_DEFAULT_ONLY : 0;
            return super.queryIntent(intent, resolvedType, defaultOnly, userId);
        }

        public List<ResolveInfo> queryIntent(Intent intent, String resolvedType, int flags,
                int userId) {
            if (!sUserManager.exists(userId))
                return null;
            mFlags = flags;
            return super.queryIntent(intent, resolvedType,
                    (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0, userId);
        }

        public List<ResolveInfo> queryIntentForPackage(Intent intent, String resolvedType,
                int flags, ArrayList<PackageParser.Provider> packageProviders, int userId) {
            if (!sUserManager.exists(userId))
                return null;
            if (packageProviders == null) {
                return null;
            }
            mFlags = flags;
            final boolean defaultOnly = (flags & PackageManager.MATCH_DEFAULT_ONLY) != 0;
            final int N = packageProviders.size();
            ArrayList<PackageParser.ProviderIntentInfo[]> listCut =
                    new ArrayList<PackageParser.ProviderIntentInfo[]>(N);

            ArrayList<PackageParser.ProviderIntentInfo> intentFilters;
            for (int i = 0; i < N; ++i) {
                intentFilters = packageProviders.get(i).intents;
                if (intentFilters != null && intentFilters.size() > 0) {
                    PackageParser.ProviderIntentInfo[] array =
                            new PackageParser.ProviderIntentInfo[intentFilters.size()];
                    intentFilters.toArray(array);
                    listCut.add(array);
                }
            }
            return super.queryIntentFromList(intent, resolvedType, defaultOnly, listCut, userId);
        }

        public final void addProvider(PackageParser.Provider p) {
            if (mProviders.containsKey(p.getComponentName())) {
                Slog.w(TAG, "Provider " + p.getComponentName() + " already defined; ignoring");
                return;
            }

            mProviders.put(p.getComponentName(), p);
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  "
                        + (p.info.nonLocalizedLabel != null
                                ? p.info.nonLocalizedLabel : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int NI = p.intents.size();
            int j;
            for (j = 0; j < NI; j++) {
                PackageParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                if (!intent.debugCheck()) {
                    Log.w(TAG, "==> For Provider " + p.info.name);
                }
                addFilter(intent);
            }
        }

        public final void removeProvider(PackageParser.Provider p) {
            mProviders.remove(p.getComponentName());
            if (DEBUG_SHOW_INFO) {
                Log.v(TAG, "  " + (p.info.nonLocalizedLabel != null
                        ? p.info.nonLocalizedLabel : p.info.name) + ":");
                Log.v(TAG, "    Class=" + p.info.name);
            }
            final int NI = p.intents.size();
            int j;
            for (j = 0; j < NI; j++) {
                PackageParser.ProviderIntentInfo intent = p.intents.get(j);
                if (DEBUG_SHOW_INFO) {
                    Log.v(TAG, "    IntentFilter:");
                    intent.dump(new LogPrinter(Log.VERBOSE, TAG), "      ");
                }
                removeFilter(intent);
            }
        }

        @Override
        protected boolean allowFilterResult(
                PackageParser.ProviderIntentInfo filter, List<ResolveInfo> dest) {
            ProviderInfo filterPi = filter.provider.info;
            for (int i = dest.size() - 1; i >= 0; i--) {
                ProviderInfo destPi = dest.get(i).providerInfo;
                if (destPi.name == filterPi.name
                        && destPi.packageName == filterPi.packageName) {
                    return false;
                }
            }
            return true;
        }

        @Override
        protected PackageParser.ProviderIntentInfo[] newArray(int size) {
            return new PackageParser.ProviderIntentInfo[size];
        }

        @Override
        protected boolean isFilterStopped(PackageParser.ProviderIntentInfo filter, int userId) {
            if (!sUserManager.exists(userId))
                return true;
            PackageParser.Package p = filter.provider.owner;
            if (p != null) {
                PackageSetting ps = (PackageSetting) p.mExtras;
                if (ps != null) {
                    // System apps are never considered stopped for purposes of
                    // filtering, because there may be no way for the user to
                    // actually re-launch them.
                    return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0
                            && ps.getStopped(userId);
                }
            }
            return false;
        }

        @Override
        protected boolean isPackageForFilter(String packageName,
                PackageParser.ProviderIntentInfo info) {
            return packageName.equals(info.provider.owner.packageName);
        }

        @Override
        protected ResolveInfo newResult(PackageParser.ProviderIntentInfo filter,
                int match, int userId) {
            if (!sUserManager.exists(userId))
                return null;
            final PackageParser.ProviderIntentInfo info = filter;
            if (!mSettings.isEnabledLPr(info.provider.info, mFlags, userId)) {
                return null;
            }
            final PackageParser.Provider provider = info.provider;
            if (mSafeMode && (provider.info.applicationInfo.flags
                    & ApplicationInfo.FLAG_SYSTEM) == 0) {
                return null;
            }
            PackageSetting ps = (PackageSetting) provider.owner.mExtras;
            if (ps == null) {
                return null;
            }
            ProviderInfo pi = PackageParser.generateProviderInfo(provider, mFlags,
                    ps.readUserState(userId), userId);
            if (pi == null) {
                return null;
            }
            final ResolveInfo res = new ResolveInfo();
            res.providerInfo = pi;
            if ((mFlags & PackageManager.GET_RESOLVED_FILTER) != 0) {
                res.filter = filter;
            }
            res.priority = info.getPriority();
            res.preferredOrder = provider.owner.mPreferredOrder;
            res.match = match;
            res.isDefault = info.hasDefault;
            res.labelRes = info.labelRes;
            res.nonLocalizedLabel = info.nonLocalizedLabel;
            res.icon = info.icon;
            res.system = res.providerInfo.applicationInfo.isSystemApp();
            return res;
        }

        @Override
        protected void sortResults(List<ResolveInfo> results) {
            Collections.sort(results, mResolvePrioritySorter);
        }

        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PackageParser.ProviderIntentInfo filter) {
            out.print(prefix);
            out.print(
                    Integer.toHexString(System.identityHashCode(filter.provider)));
            out.print(' ');
            filter.provider.printComponentShortName(out);
            out.print(" filter ");
            out.println(Integer.toHexString(System.identityHashCode(filter)));
        }

        @Override
        protected Object filterToLabel(PackageParser.ProviderIntentInfo filter) {
            return filter.provider;
        }

        protected void dumpFilterLabel(PrintWriter out, String prefix, Object label, int count) {
            PackageParser.Provider provider = (PackageParser.Provider)label;
            out.print(prefix); out.print(
                    Integer.toHexString(System.identityHashCode(provider)));
                    out.print(' ');
                    provider.printComponentShortName(out);
            if (count > 1) {
                out.print(" ("); out.print(count); out.print(" filters)");
            }
            out.println();
        }

        private final ArrayMap<ComponentName, PackageParser.Provider> mProviders
                = new ArrayMap<ComponentName, PackageParser.Provider>();
        private int mFlags;
    };

    private static final Comparator<ResolveInfo> mResolvePrioritySorter =
            new Comparator<ResolveInfo>() {
        public int compare(ResolveInfo r1, ResolveInfo r2) {
            int v1 = r1.priority;
            int v2 = r2.priority;
            //System.out.println("Comparing: q1=" + q1 + " q2=" + q2);
            if (v1 != v2) {
                return (v1 > v2) ? -1 : 1;
            }
            v1 = r1.preferredOrder;
            v2 = r2.preferredOrder;
            if (v1 != v2) {
                return (v1 > v2) ? -1 : 1;
            }
            if (r1.isDefault != r2.isDefault) {
                return r1.isDefault ? -1 : 1;
            }
            v1 = r1.match;
            v2 = r2.match;
            //System.out.println("Comparing: m1=" + m1 + " m2=" + m2);
            if (v1 != v2) {
                return (v1 > v2) ? -1 : 1;
            }
            if (r1.system != r2.system) {
                return r1.system ? -1 : 1;
            }
            return 0;
        }
    };

    private static final Comparator<ProviderInfo> mProviderInitOrderSorter =
            new Comparator<ProviderInfo>() {
        public int compare(ProviderInfo p1, ProviderInfo p2) {
            final int v1 = p1.initOrder;
            final int v2 = p2.initOrder;
            return (v1 > v2) ? -1 : ((v1 < v2) ? 1 : 0);
        }
    };

    final void sendPackageBroadcast(final String action, final String pkg,
            final Bundle extras, final String targetPkg, final IIntentReceiver finishedReceiver,
            final int[] userIds) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    final IActivityManager am = ActivityManagerNative.getDefault();
                    if (am == null) return;
                    final int[] resolvedUserIds;
                    if (userIds == null) {
                        resolvedUserIds = am.getRunningUserIds();
                    } else {
                        resolvedUserIds = userIds;
                    }
                    for (int id : resolvedUserIds) {
                        final Intent intent = new Intent(action,
                                pkg != null ? Uri.fromParts("package", pkg, null) : null);
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
                        intent.putExtra(Intent.EXTRA_USER_HANDLE, id);
                        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                        if (DEBUG_BROADCASTS) {
                            RuntimeException here = new RuntimeException("here");
                            here.fillInStackTrace();
                            Slog.d(TAG, "Sending to user " + id + ": "
                                    + intent.toShortString(false, true, false, false)
                                    + " " + intent.getExtras(), here);
                        }
                        am.broadcastIntent(null, intent, null, finishedReceiver,
                                0, null, null, null, android.app.AppOpsManager.OP_NONE,
                                null, finishedReceiver != null, false, id);
                    }
                } catch (RemoteException ex) {
                }
            }
        });
    }

    /**
     * Check if the external storage media is available. This is true if there
     * is a mounted external storage medium or if the external storage is
     * emulated.
     */
    private boolean isExternalMediaAvailable() {
        return mMediaMounted || Environment.isExternalStorageEmulated();
    }

    @Override
    public PackageCleanItem nextPackageToClean(PackageCleanItem lastPackage) {
        // writer
        synchronized (mPackages) {
            if (!isExternalMediaAvailable()) {
                // If the external storage is no longer mounted at this point,
                // the caller may not have been able to delete all of this
                // packages files and can not delete any more.  Bail.
                return null;
            }
            final ArrayList<PackageCleanItem> pkgs = mSettings.mPackagesToBeCleaned;
            if (lastPackage != null) {
                pkgs.remove(lastPackage);
            }
            if (pkgs.size() > 0) {
                return pkgs.get(0);
            }
        }
        return null;
    }

    void schedulePackageCleaning(String packageName, int userId, boolean andCode) {
        final Message msg = mHandler.obtainMessage(START_CLEANING_PACKAGE,
                userId, andCode ? 1 : 0, packageName);
        if (mSystemReady) {
            msg.sendToTarget();
        } else {
            if (mPostSystemReadyMessages == null) {
                mPostSystemReadyMessages = new ArrayList<>();
            }
            mPostSystemReadyMessages.add(msg);
        }
    }

    void startCleaningPackages() {
        // reader
        synchronized (mPackages) {
            if (!isExternalMediaAvailable()) {
                return;
            }
            if (mSettings.mPackagesToBeCleaned.isEmpty()) {
                return;
            }
        }
        Intent intent = new Intent(PackageManager.ACTION_CLEAN_EXTERNAL_STORAGE);
        intent.setComponent(DEFAULT_CONTAINER_COMPONENT);
        IActivityManager am = ActivityManagerNative.getDefault();
        if (am != null) {
            try {
                am.startService(null, intent, null, mContext.getOpPackageName(),
                        UserHandle.USER_OWNER);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void installPackage(String originPath, IPackageInstallObserver2 observer,
            int installFlags, String installerPackageName, VerificationParams verificationParams,
            String packageAbiOverride) {
        installPackageAsUser(originPath, observer, installFlags, installerPackageName,
                verificationParams, packageAbiOverride, UserHandle.getCallingUserId());
    }

    @Override
    public void installPackageAsUser(String originPath, IPackageInstallObserver2 observer,
            int installFlags, String installerPackageName, VerificationParams verificationParams,
            String packageAbiOverride, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES, null);

        final int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true, true, "installPackageAsUser");

        if (isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            try {
                if (observer != null) {
                    observer.onPackageInstalled("", INSTALL_FAILED_USER_RESTRICTED, null, null);
                }
            } catch (RemoteException re) {
            }
            return;
        }

        if ((callingUid == Process.SHELL_UID) || (callingUid == Process.ROOT_UID)) {
            installFlags |= PackageManager.INSTALL_FROM_ADB;

        } else {
            // Caller holds INSTALL_PACKAGES permission, so we're less strict
            // about installerPackageName.

            installFlags &= ~PackageManager.INSTALL_FROM_ADB;
            installFlags &= ~PackageManager.INSTALL_ALL_USERS;
        }

        UserHandle user;
        if ((installFlags & PackageManager.INSTALL_ALL_USERS) != 0) {
            user = UserHandle.ALL;
        } else {
            user = new UserHandle(userId);
        }

        // Only system components can circumvent runtime permissions when installing.
        if ((installFlags & PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS) != 0
                && mContext.checkCallingOrSelfPermission(Manifest.permission
                .INSTALL_GRANT_RUNTIME_PERMISSIONS) == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException("You need the "
                    + "android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS permission "
                    + "to use the PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS flag");
        }

        verificationParams.setInstallerUid(callingUid);

        final File originFile = new File(originPath);
        final OriginInfo origin = OriginInfo.fromUntrustedFile(originFile);

        final Message msg = mHandler.obtainMessage(INIT_COPY);
        msg.obj = new InstallParams(origin, null, observer, installFlags, installerPackageName,
                null, verificationParams, user, packageAbiOverride, null);
        mHandler.sendMessage(msg);
    }

    void installStage(String packageName, File stagedDir, String stagedCid,
            IPackageInstallObserver2 observer, PackageInstaller.SessionParams params,
            String installerPackageName, int installerUid, UserHandle user) {
        final VerificationParams verifParams = new VerificationParams(null, params.originatingUri,
                params.referrerUri, installerUid, null);
        verifParams.setInstallerUid(installerUid);

        final OriginInfo origin;
        if (stagedDir != null) {
            origin = OriginInfo.fromStagedFile(stagedDir);
        } else {
            origin = OriginInfo.fromStagedContainer(stagedCid);
        }

        final Message msg = mHandler.obtainMessage(INIT_COPY);
        msg.obj = new InstallParams(origin, null, observer, params.installFlags,
                installerPackageName, params.volumeUuid, verifParams, user, params.abiOverride,
                params.grantedRuntimePermissions);
        mHandler.sendMessage(msg);
    }

    private void sendPackageAddedForUser(String packageName, PackageSetting pkgSetting, int userId) {
        Bundle extras = new Bundle(1);
        extras.putInt(Intent.EXTRA_UID, UserHandle.getUid(userId, pkgSetting.appId));

        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                packageName, extras, null, null, new int[] {userId});
        try {
            IActivityManager am = ActivityManagerNative.getDefault();
            final boolean isSystem =
                    isSystemApp(pkgSetting) || isUpdatedSystemApp(pkgSetting);
            if (isSystem && am.isUserRunning(userId, false)) {
                // The just-installed/enabled app is bundled on the system, so presumed
                // to be able to run automatically without needing an explicit launch.
                // Send it a BOOT_COMPLETED if it would ordinarily have gotten one.
                Intent bcIntent = new Intent(Intent.ACTION_BOOT_COMPLETED)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        .setPackage(packageName);
                am.broadcastIntent(null, bcIntent, null, null, 0, null, null, null,
                        android.app.AppOpsManager.OP_NONE, null, false, false, userId);
            }
        } catch (RemoteException e) {
            // shouldn't happen
            Slog.w(TAG, "Unable to bootstrap installed package", e);
        }
    }

    @Override
    public boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
            int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        enforceCrossUserPermission(uid, userId, true, true,
                "setApplicationHiddenSetting for user " + userId);

        if (hidden && isPackageDeviceAdmin(packageName, userId)) {
            Slog.w(TAG, "Not hiding package " + packageName + ": has active device admin");
            return false;
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            boolean sendAdded = false;
            boolean sendRemoved = false;
            // writer
            synchronized (mPackages) {
                pkgSetting = mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
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
                sendPackageAddedForUser(packageName, pkgSetting, userId);
                return true;
            }
            if (sendRemoved) {
                killApplication(packageName, UserHandle.getUid(userId, pkgSetting.appId),
                        "hiding pkg");
                sendApplicationHiddenForUser(packageName, pkgSetting, userId);
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return false;
    }

    private void sendApplicationHiddenForUser(String packageName, PackageSetting pkgSetting,
            int userId) {
        final PackageRemovedInfo info = new PackageRemovedInfo();
        info.removedPackage = packageName;
        info.removedUsers = new int[] {userId};
        info.uid = UserHandle.getUid(userId, pkgSetting.appId);
        info.sendBroadcast(false, false, false);
    }

    /**
     * Returns true if application is not found or there was an error. Otherwise it returns
     * the hidden state of the package for the given user.
     */
    @Override
    public boolean getApplicationHiddenSettingAsUser(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true,
                false, "getApplicationHidden for user " + userId);
        PackageSetting pkgSetting;
        long callingId = Binder.clearCallingIdentity();
        try {
            // writer
            synchronized (mPackages) {
                pkgSetting = mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
                    return true;
                }
                return pkgSetting.getHidden(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * @hide
     */
    @Override
    public int installExistingPackageAsUser(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES,
                null);
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        enforceCrossUserPermission(uid, userId, true, true, "installExistingPackage for user "
                + userId);
        if (isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            return PackageManager.INSTALL_FAILED_USER_RESTRICTED;
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            boolean sendAdded = false;

            // writer
            synchronized (mPackages) {
                pkgSetting = mSettings.mPackages.get(packageName);
                if (pkgSetting == null) {
                    return PackageManager.INSTALL_FAILED_INVALID_URI;
                }
                if (!pkgSetting.getInstalled(userId)) {
                    pkgSetting.setInstalled(true, userId);
                    pkgSetting.setHidden(false, userId);
                    mSettings.writePackageRestrictionsLPr(userId);
                    sendAdded = true;
                }
            }

            if (sendAdded) {
                sendPackageAddedForUser(packageName, pkgSetting, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return PackageManager.INSTALL_SUCCEEDED;
    }

    boolean isUserRestricted(int userId, String restrictionKey) {
        Bundle restrictions = sUserManager.getUserRestrictions(userId);
        if (restrictions.getBoolean(restrictionKey, false)) {
            Log.w(TAG, "User is restricted: " + restrictionKey);
            return true;
        }
        return false;
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
        if ((verificationCodeAtTimeout != PackageManager.VERIFICATION_ALLOW)
                && (verificationCodeAtTimeout != PackageManager.VERIFICATION_REJECT)) {
            verificationCodeAtTimeout = PackageManager.VERIFICATION_REJECT;
        }

        if ((state != null) && !state.timeoutExtended()) {
            state.extendTimeout();

            final Message msg = mHandler.obtainMessage(PACKAGE_VERIFIED);
            msg.arg1 = id;
            msg.obj = response;
            mHandler.sendMessageDelayed(msg, millisecondsToDelay);
        }
    }

    private void broadcastPackageVerified(int verificationId, Uri packageUri,
            int verificationCode, UserHandle user) {
        final Intent intent = new Intent(Intent.ACTION_PACKAGE_VERIFIED);
        intent.setDataAndType(packageUri, PACKAGE_MIME_TYPE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);
        intent.putExtra(PackageManager.EXTRA_VERIFICATION_RESULT, verificationCode);

        mContext.sendBroadcastAsUser(intent, user,
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT);
    }

    private ComponentName matchComponentForVerifier(String packageName,
            List<ResolveInfo> receivers) {
        ActivityInfo targetReceiver = null;

        final int NR = receivers.size();
        for (int i = 0; i < NR; i++) {
            final ResolveInfo info = receivers.get(i);
            if (info.activityInfo == null) {
                continue;
            }

            if (packageName.equals(info.activityInfo.packageName)) {
                targetReceiver = info.activityInfo;
                break;
            }
        }

        if (targetReceiver == null) {
            return null;
        }

        return new ComponentName(targetReceiver.packageName, targetReceiver.name);
    }

    private List<ComponentName> matchVerifiers(PackageInfoLite pkgInfo,
            List<ResolveInfo> receivers, final PackageVerificationState verificationState) {
        if (pkgInfo.verifiers.length == 0) {
            return null;
        }

        final int N = pkgInfo.verifiers.length;
        final List<ComponentName> sufficientVerifiers = new ArrayList<ComponentName>(N + 1);
        for (int i = 0; i < N; i++) {
            final VerifierInfo verifierInfo = pkgInfo.verifiers[i];

            final ComponentName comp = matchComponentForVerifier(verifierInfo.packageName,
                    receivers);
            if (comp == null) {
                continue;
            }

            final int verifierUid = getUidForVerifier(verifierInfo);
            if (verifierUid == -1) {
                continue;
            }

            if (DEBUG_VERIFY) {
                Slog.d(TAG, "Added sufficient verifier " + verifierInfo.packageName
                        + " with the correct signature");
            }
            sufficientVerifiers.add(comp);
            verificationState.addSufficientVerifier(verifierUid);
        }

        return sufficientVerifiers;
    }

    private int getUidForVerifier(VerifierInfo verifierInfo) {
        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(verifierInfo.packageName);
            if (pkg == null) {
                return -1;
            } else if (pkg.mSignatures.length != 1) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " has more than one signature; ignoring");
                return -1;
            }

            /*
             * If the public key of the package's signature does not match
             * our expected public key, then this is a different package and
             * we should skip.
             */

            final byte[] expectedPublicKey;
            try {
                final Signature verifierSig = pkg.mSignatures[0];
                final PublicKey publicKey = verifierSig.getPublicKey();
                expectedPublicKey = publicKey.getEncoded();
            } catch (CertificateException e) {
                return -1;
            }

            final byte[] actualPublicKey = verifierInfo.publicKey.getEncoded();

            if (!Arrays.equals(actualPublicKey, expectedPublicKey)) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " does not have the expected public key; ignoring");
                return -1;
            }

            return pkg.applicationInfo.uid;
        }
    }

    @Override
    public void finishPackageInstall(int token) {
        enforceSystemOrRoot("Only the system is allowed to finish installs");

        if (DEBUG_INSTALL) {
            Slog.v(TAG, "BM finishing package install for " + token);
        }

        final Message msg = mHandler.obtainMessage(POST_INSTALL, token, 0);
        mHandler.sendMessage(msg);
    }

    /**
     * Get the verification agent timeout.
     *
     * @return verification timeout in milliseconds
     */
    private long getVerificationTimeout() {
        return android.provider.Settings.Global.getLong(mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_TIMEOUT,
                DEFAULT_VERIFICATION_TIMEOUT);
    }

    /**
     * Get the default verification agent response code.
     *
     * @return default verification response code
     */
    private int getDefaultVerificationResponse() {
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_DEFAULT_RESPONSE,
                DEFAULT_VERIFICATION_RESPONSE);
    }

    /**
     * Check whether or not package verification has been enabled.
     *
     * @return true if verification should be performed
     */
    private boolean isVerificationEnabled(int userId, int installFlags) {
        if (!DEFAULT_VERIFY_ENABLE) {
            return false;
        }

        boolean ensureVerifyAppsEnabled = isUserRestricted(userId, UserManager.ENSURE_VERIFY_APPS);

        // Check if installing from ADB
        if ((installFlags & PackageManager.INSTALL_FROM_ADB) != 0) {
            // Do not run verification in a test harness environment
            if (ActivityManager.isRunningInTestHarness()) {
                return false;
            }
            if (ensureVerifyAppsEnabled) {
                return true;
            }
            // Check if the developer does not want package verification for ADB installs
            if (android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                    android.provider.Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, 1) == 0) {
                return false;
            }
        }

        if (ensureVerifyAppsEnabled) {
            return true;
        }

        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.PACKAGE_VERIFIER_ENABLE, 1) == 1;
    }

    @Override
    public void verifyIntentFilter(int id, int verificationCode, List<String> failedDomains)
            throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT,
                "Only intentfilter verification agents can verify applications");

        final Message msg = mHandler.obtainMessage(INTENT_FILTER_VERIFIED);
        final IntentFilterVerificationResponse response = new IntentFilterVerificationResponse(
                Binder.getCallingUid(), verificationCode, failedDomains);
        msg.arg1 = id;
        msg.obj = response;
        mHandler.sendMessage(msg);
    }

    @Override
    public int getIntentVerificationStatus(String packageName, int userId) {
        synchronized (mPackages) {
            return mSettings.getIntentFilterVerificationStatusLPr(packageName, userId);
        }
    }

    @Override
    public boolean updateIntentVerificationStatus(String packageName, int status, int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        boolean result = false;
        synchronized (mPackages) {
            result = mSettings.updateIntentFilterVerificationStatusLPw(packageName, status, userId);
        }
        if (result) {
            scheduleWritePackageRestrictionsLocked(userId);
        }
        return result;
    }

    @Override
    public List<IntentFilterVerificationInfo> getIntentFilterVerifications(String packageName) {
        synchronized (mPackages) {
            return mSettings.getIntentFilterVerificationsLPr(packageName);
        }
    }

    @Override
    public List<IntentFilter> getAllIntentFilters(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return Collections.<IntentFilter>emptyList();
        }
        synchronized (mPackages) {
            PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null || pkg.activities == null) {
                return Collections.<IntentFilter>emptyList();
            }
            final int count = pkg.activities.size();
            ArrayList<IntentFilter> result = new ArrayList<>();
            for (int n=0; n<count; n++) {
                PackageParser.Activity activity = pkg.activities.get(n);
                if (activity.intents != null || activity.intents.size() > 0) {
                    result.addAll(activity.intents);
                }
            }
            return result;
        }
    }

    @Override
    public boolean setDefaultBrowserPackageName(String packageName, int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);

        synchronized (mPackages) {
            boolean result = mSettings.setDefaultBrowserPackageNameLPw(packageName, userId);
            if (packageName != null) {
                result |= updateIntentVerificationStatus(packageName,
                        PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS,
                        userId);
                mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultBrowserLPr(
                        packageName, userId);
            }
            return result;
        }
    }

    @Override
    public String getDefaultBrowserPackageName(int userId) {
        synchronized (mPackages) {
            return mSettings.getDefaultBrowserPackageNameLPw(userId);
        }
    }

    /**
     * Get the "allow unknown sources" setting.
     *
     * @return the current "allow unknown sources" setting
     */
    private int getUnknownSourcesSettings() {
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.INSTALL_NON_MARKET_APPS,
                -1);
    }

    @Override
    public void setInstallerPackageName(String targetPackage, String installerPackageName) {
        final int uid = Binder.getCallingUid();
        // writer
        synchronized (mPackages) {
            PackageSetting targetPackageSetting = mSettings.mPackages.get(targetPackage);
            if (targetPackageSetting == null) {
                throw new IllegalArgumentException("Unknown target package: " + targetPackage);
            }

            PackageSetting installerPackageSetting;
            if (installerPackageName != null) {
                installerPackageSetting = mSettings.mPackages.get(installerPackageName);
                if (installerPackageSetting == null) {
                    throw new IllegalArgumentException("Unknown installer package: "
                            + installerPackageName);
                }
            } else {
                installerPackageSetting = null;
            }

            Signature[] callerSignature;
            Object obj = mSettings.getUserIdLPr(uid);
            if (obj != null) {
                if (obj instanceof SharedUserSetting) {
                    callerSignature = ((SharedUserSetting)obj).signatures.mSignatures;
                } else if (obj instanceof PackageSetting) {
                    callerSignature = ((PackageSetting)obj).signatures.mSignatures;
                } else {
                    throw new SecurityException("Bad object " + obj + " for uid " + uid);
                }
            } else {
                throw new SecurityException("Unknown calling uid " + uid);
            }

            // Verify: can't set installerPackageName to a package that is
            // not signed with the same cert as the caller.
            if (installerPackageSetting != null) {
                if (compareSignatures(callerSignature,
                        installerPackageSetting.signatures.mSignatures)
                        != PackageManager.SIGNATURE_MATCH) {
                    throw new SecurityException(
                            "Caller does not have same cert as new installer package "
                            + installerPackageName);
                }
            }

            // Verify: if target already has an installer package, it must
            // be signed with the same cert as the caller.
            if (targetPackageSetting.installerPackageName != null) {
                PackageSetting setting = mSettings.mPackages.get(
                        targetPackageSetting.installerPackageName);
                // If the currently set package isn't valid, then it's always
                // okay to change it.
                if (setting != null) {
                    if (compareSignatures(callerSignature,
                            setting.signatures.mSignatures)
                            != PackageManager.SIGNATURE_MATCH) {
                        throw new SecurityException(
                                "Caller does not have same cert as old installer package "
                                + targetPackageSetting.installerPackageName);
                    }
                }
            }

            // Okay!
            targetPackageSetting.installerPackageName = installerPackageName;
            scheduleWriteSettingsLocked();
        }
    }

    private void processPendingInstall(final InstallArgs args, final int currentStatus) {
        // Queue up an async operation since the package installation may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                 // Result object to be returned
                PackageInstalledInfo res = new PackageInstalledInfo();
                res.returnCode = currentStatus;
                res.uid = -1;
                res.pkg = null;
                res.removedInfo = new PackageRemovedInfo();
                if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                    args.doPreInstall(res.returnCode);
                    synchronized (mInstallLock) {
                        installPackageLI(args, res);
                    }
                    args.doPostInstall(res.returnCode, res.uid);
                }

                // A restore should be performed at this point if (a) the install
                // succeeded, (b) the operation is not an update, and (c) the new
                // package has not opted out of backup participation.
                final boolean update = res.removedInfo.removedPackage != null;
                final int flags = (res.pkg == null) ? 0 : res.pkg.applicationInfo.flags;
                boolean doRestore = !update
                        && ((flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0);

                // Set up the post-install work request bookkeeping.  This will be used
                // and cleaned up by the post-install event handling regardless of whether
                // there's a restore pass performed.  Token values are >= 1.
                int token;
                if (mNextInstallToken < 0) mNextInstallToken = 1;
                token = mNextInstallToken++;

                PostInstallData data = new PostInstallData(args, res);
                mRunningInstalls.put(token, data);
                if (DEBUG_INSTALL) Log.v(TAG, "+ starting restore round-trip " + token);

                if (res.returnCode == PackageManager.INSTALL_SUCCEEDED && doRestore) {
                    // Pass responsibility to the Backup Manager.  It will perform a
                    // restore if appropriate, then pass responsibility back to the
                    // Package Manager to run the post-install observer callbacks
                    // and broadcasts.
                    IBackupManager bm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    if (bm != null) {
                        if (DEBUG_INSTALL) Log.v(TAG, "token " + token
                                + " to BM for possible restore");
                        try {
                            if (bm.isBackupServiceActive(UserHandle.USER_OWNER)) {
                                bm.restoreAtInstall(res.pkg.applicationInfo.packageName, token);
                            } else {
                                doRestore = false;
                            }
                        } catch (RemoteException e) {
                            // can't happen; the backup manager is local
                        } catch (Exception e) {
                            Slog.e(TAG, "Exception trying to enqueue restore", e);
                            doRestore = false;
                        }
                    } else {
                        Slog.e(TAG, "Backup Manager not found!");
                        doRestore = false;
                    }
                }

                if (!doRestore) {
                    // No restore possible, or the Backup Manager was mysteriously not
                    // available -- just fire the post-install work request directly.
                    if (DEBUG_INSTALL) Log.v(TAG, "No restore - queue post-install for " + token);
                    Message msg = mHandler.obtainMessage(POST_INSTALL, token, 0);
                    mHandler.sendMessage(msg);
                }
            }
        });
    }

    private abstract class HandlerParams {
        private static final int MAX_RETRIES = 4;

        /**
         * Number of times startCopy() has been attempted and had a non-fatal
         * error.
         */
        private int mRetries = 0;

        /** User handle for the user requesting the information or installation. */
        private final UserHandle mUser;

        HandlerParams(UserHandle user) {
            mUser = user;
        }

        UserHandle getUser() {
            return mUser;
        }

        final boolean startCopy() {
            boolean res;
            try {
                if (DEBUG_INSTALL) Slog.i(TAG, "startCopy " + mUser + ": " + this);

                if (++mRetries > MAX_RETRIES) {
                    Slog.w(TAG, "Failed to invoke remote methods on default container service. Giving up");
                    mHandler.sendEmptyMessage(MCS_GIVE_UP);
                    handleServiceError();
                    return false;
                } else {
                    handleStartCopy();
                    res = true;
                }
            } catch (RemoteException e) {
                if (DEBUG_INSTALL) Slog.i(TAG, "Posting install MCS_RECONNECT");
                mHandler.sendEmptyMessage(MCS_RECONNECT);
                res = false;
            }
            handleReturnCode();
            return res;
        }

        final void serviceError() {
            if (DEBUG_INSTALL) Slog.i(TAG, "serviceError");
            handleServiceError();
            handleReturnCode();
        }

        abstract void handleStartCopy() throws RemoteException;
        abstract void handleServiceError();
        abstract void handleReturnCode();
    }

    class MeasureParams extends HandlerParams {
        private final PackageStats mStats;
        private boolean mSuccess;

        private final IPackageStatsObserver mObserver;

        public MeasureParams(PackageStats stats, IPackageStatsObserver observer) {
            super(new UserHandle(stats.userHandle));
            mObserver = observer;
            mStats = stats;
        }

        @Override
        public String toString() {
            return "MeasureParams{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + mStats.packageName + "}";
        }

        @Override
        void handleStartCopy() throws RemoteException {
            synchronized (mInstallLock) {
                mSuccess = getPackageSizeInfoLI(mStats.packageName, mStats.userHandle, mStats);
            }

            if (mSuccess) {
                final boolean mounted;
                if (Environment.isExternalStorageEmulated()) {
                    mounted = true;
                } else {
                    final String status = Environment.getExternalStorageState();
                    mounted = (Environment.MEDIA_MOUNTED.equals(status)
                            || Environment.MEDIA_MOUNTED_READ_ONLY.equals(status));
                }

                if (mounted) {
                    final UserEnvironment userEnv = new UserEnvironment(mStats.userHandle);

                    mStats.externalCacheSize = calculateDirectorySize(mContainerService,
                            userEnv.buildExternalStorageAppCacheDirs(mStats.packageName));

                    mStats.externalDataSize = calculateDirectorySize(mContainerService,
                            userEnv.buildExternalStorageAppDataDirs(mStats.packageName));

                    // Always subtract cache size, since it's a subdirectory
                    mStats.externalDataSize -= mStats.externalCacheSize;

                    mStats.externalMediaSize = calculateDirectorySize(mContainerService,
                            userEnv.buildExternalStorageAppMediaDirs(mStats.packageName));

                    mStats.externalObbSize = calculateDirectorySize(mContainerService,
                            userEnv.buildExternalStorageAppObbDirs(mStats.packageName));
                }
            }
        }

        @Override
        void handleReturnCode() {
            if (mObserver != null) {
                try {
                    mObserver.onGetStatsCompleted(mStats, mSuccess);
                } catch (RemoteException e) {
                    Slog.i(TAG, "Observer no longer exists.");
                }
            }
        }

        @Override
        void handleServiceError() {
            Slog.e(TAG, "Could not measure application " + mStats.packageName
                            + " external storage");
        }
    }

    private static long calculateDirectorySize(IMediaContainerService mcs, File[] paths)
            throws RemoteException {
        long result = 0;
        for (File path : paths) {
            result += mcs.calculateDirectorySize(path.getAbsolutePath());
        }
        return result;
    }

    private static void clearDirectory(IMediaContainerService mcs, File[] paths) {
        for (File path : paths) {
            try {
                mcs.clearDirectory(path.getAbsolutePath());
            } catch (RemoteException e) {
            }
        }
    }

    static class OriginInfo {
        /**
         * Location where install is coming from, before it has been
         * copied/renamed into place. This could be a single monolithic APK
         * file, or a cluster directory. This location may be untrusted.
         */
        final File file;
        final String cid;

        /**
         * Flag indicating that {@link #file} or {@link #cid} has already been
         * staged, meaning downstream users don't need to defensively copy the
         * contents.
         */
        final boolean staged;

        /**
         * Flag indicating that {@link #file} or {@link #cid} is an already
         * installed app that is being moved.
         */
        final boolean existing;

        final String resolvedPath;
        final File resolvedFile;

        static OriginInfo fromNothing() {
            return new OriginInfo(null, null, false, false);
        }

        static OriginInfo fromUntrustedFile(File file) {
            return new OriginInfo(file, null, false, false);
        }

        static OriginInfo fromExistingFile(File file) {
            return new OriginInfo(file, null, false, true);
        }

        static OriginInfo fromStagedFile(File file) {
            return new OriginInfo(file, null, true, false);
        }

        static OriginInfo fromStagedContainer(String cid) {
            return new OriginInfo(null, cid, true, false);
        }

        private OriginInfo(File file, String cid, boolean staged, boolean existing) {
            this.file = file;
            this.cid = cid;
            this.staged = staged;
            this.existing = existing;

            if (cid != null) {
                resolvedPath = PackageHelper.getSdDir(cid);
                resolvedFile = new File(resolvedPath);
            } else if (file != null) {
                resolvedPath = file.getAbsolutePath();
                resolvedFile = file;
            } else {
                resolvedPath = null;
                resolvedFile = null;
            }
        }
    }

    class MoveInfo {
        final int moveId;
        final String fromUuid;
        final String toUuid;
        final String packageName;
        final String dataAppName;
        final int appId;
        final String seinfo;

        public MoveInfo(int moveId, String fromUuid, String toUuid, String packageName,
                String dataAppName, int appId, String seinfo) {
            this.moveId = moveId;
            this.fromUuid = fromUuid;
            this.toUuid = toUuid;
            this.packageName = packageName;
            this.dataAppName = dataAppName;
            this.appId = appId;
            this.seinfo = seinfo;
        }
    }

    class InstallParams extends HandlerParams {
        final OriginInfo origin;
        final MoveInfo move;
        final IPackageInstallObserver2 observer;
        int installFlags;
        final String installerPackageName;
        final String volumeUuid;
        final VerificationParams verificationParams;
        private InstallArgs mArgs;
        private int mRet;
        final String packageAbiOverride;
        final String[] grantedRuntimePermissions;


        InstallParams(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer,
                int installFlags, String installerPackageName, String volumeUuid,
                VerificationParams verificationParams, UserHandle user, String packageAbiOverride,
                String[] grantedPermissions) {
            super(user);
            this.origin = origin;
            this.move = move;
            this.observer = observer;
            this.installFlags = installFlags;
            this.installerPackageName = installerPackageName;
            this.volumeUuid = volumeUuid;
            this.verificationParams = verificationParams;
            this.packageAbiOverride = packageAbiOverride;
            this.grantedRuntimePermissions = grantedPermissions;
        }

        @Override
        public String toString() {
            return "InstallParams{" + Integer.toHexString(System.identityHashCode(this))
                    + " file=" + origin.file + " cid=" + origin.cid + "}";
        }

        public ManifestDigest getManifestDigest() {
            if (verificationParams == null) {
                return null;
            }
            return verificationParams.getManifestDigest();
        }

        private int installLocationPolicy(PackageInfoLite pkgLite) {
            String packageName = pkgLite.packageName;
            int installLocation = pkgLite.installLocation;
            boolean onSd = (installFlags & PackageManager.INSTALL_EXTERNAL) != 0;
            // reader
            synchronized (mPackages) {
                PackageParser.Package pkg = mPackages.get(packageName);
                if (pkg != null) {
                    if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                        // Check for downgrading.
                        if ((installFlags & PackageManager.INSTALL_ALLOW_DOWNGRADE) == 0) {
                            try {
                                checkDowngrade(pkg, pkgLite);
                            } catch (PackageManagerException e) {
                                Slog.w(TAG, "Downgrade detected: " + e.getMessage());
                                return PackageHelper.RECOMMEND_FAILED_VERSION_DOWNGRADE;
                            }
                        }
                        // Check for updated system application.
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                            if (onSd) {
                                Slog.w(TAG, "Cannot install update to system app on sdcard");
                                return PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION;
                            }
                            return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                        } else {
                            if (onSd) {
                                // Install flag overrides everything.
                                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
                            }
                            // If current upgrade specifies particular preference
                            if (installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
                                // Application explicitly specified internal.
                                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                            } else if (installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
                                // App explictly prefers external. Let policy decide
                            } else {
                                // Prefer previous location
                                if (isExternal(pkg)) {
                                    return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
                                }
                                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
                            }
                        }
                    } else {
                        // Invalid install. Return error code
                        return PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS;
                    }
                }
            }
            // All the special cases have been taken care of.
            // Return result based on recommended install location.
            if (onSd) {
                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            }
            return pkgLite.recommendedInstallLocation;
        }

        /*
         * Invoke remote method to get package information and install
         * location values. Override install location based on default
         * policy if needed and then create install arguments based
         * on the install location.
         */
        public void handleStartCopy() throws RemoteException {
            int ret = PackageManager.INSTALL_SUCCEEDED;

            // If we're already staged, we've firmly committed to an install location
            if (origin.staged) {
                if (origin.file != null) {
                    installFlags |= PackageManager.INSTALL_INTERNAL;
                    installFlags &= ~PackageManager.INSTALL_EXTERNAL;
                } else if (origin.cid != null) {
                    installFlags |= PackageManager.INSTALL_EXTERNAL;
                    installFlags &= ~PackageManager.INSTALL_INTERNAL;
                } else {
                    throw new IllegalStateException("Invalid stage location");
                }
            }

            final boolean onSd = (installFlags & PackageManager.INSTALL_EXTERNAL) != 0;
            final boolean onInt = (installFlags & PackageManager.INSTALL_INTERNAL) != 0;

            PackageInfoLite pkgLite = null;

            if (onInt && onSd) {
                // Check if both bits are set.
                Slog.w(TAG, "Conflicting flags specified for installing on both internal and external");
                ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
            } else {
                pkgLite = mContainerService.getMinimalPackageInfo(origin.resolvedPath, installFlags,
                        packageAbiOverride);

                /*
                 * If we have too little free space, try to free cache
                 * before giving up.
                 */
                if (!origin.staged && pkgLite.recommendedInstallLocation
                        == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
                    // TODO: focus freeing disk space on the target device
                    final StorageManager storage = StorageManager.from(mContext);
                    final long lowThreshold = storage.getStorageLowBytes(
                            Environment.getDataDirectory());

                    final long sizeBytes = mContainerService.calculateInstalledSize(
                            origin.resolvedPath, isForwardLocked(), packageAbiOverride);

                    if (mInstaller.freeCache(null, sizeBytes + lowThreshold) >= 0) {
                        pkgLite = mContainerService.getMinimalPackageInfo(origin.resolvedPath,
                                installFlags, packageAbiOverride);
                    }

                    /*
                     * The cache free must have deleted the file we
                     * downloaded to install.
                     *
                     * TODO: fix the "freeCache" call to not delete
                     *       the file we care about.
                     */
                    if (pkgLite.recommendedInstallLocation
                            == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                        pkgLite.recommendedInstallLocation
                            = PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
                    }
                }
            }

            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                int loc = pkgLite.recommendedInstallLocation;
                if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_LOCATION) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_ALREADY_EXISTS) {
                    ret = PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE) {
                    ret = PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_APK) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_APK;
                } else if (loc == PackageHelper.RECOMMEND_FAILED_INVALID_URI) {
                    ret = PackageManager.INSTALL_FAILED_INVALID_URI;
                } else if (loc == PackageHelper.RECOMMEND_MEDIA_UNAVAILABLE) {
                    ret = PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE;
                } else {
                    // Override with defaults if needed.
                    loc = installLocationPolicy(pkgLite);
                    if (loc == PackageHelper.RECOMMEND_FAILED_VERSION_DOWNGRADE) {
                        ret = PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE;
                    } else if (!onSd && !onInt) {
                        // Override install location with flags
                        if (loc == PackageHelper.RECOMMEND_INSTALL_EXTERNAL) {
                            // Set the flag to install on external media.
                            installFlags |= PackageManager.INSTALL_EXTERNAL;
                            installFlags &= ~PackageManager.INSTALL_INTERNAL;
                        } else {
                            // Make sure the flag for installing on external
                            // media is unset
                            installFlags |= PackageManager.INSTALL_INTERNAL;
                            installFlags &= ~PackageManager.INSTALL_EXTERNAL;
                        }
                    }
                }
            }

            final InstallArgs args = createInstallArgs(this);
            mArgs = args;

            if (ret == PackageManager.INSTALL_SUCCEEDED) {
                 /*
                 * ADB installs appear as UserHandle.USER_ALL, and can only be performed by
                 * UserHandle.USER_OWNER, so use the package verifier for UserHandle.USER_OWNER.
                 */
                int userIdentifier = getUser().getIdentifier();
                if (userIdentifier == UserHandle.USER_ALL
                        && ((installFlags & PackageManager.INSTALL_FROM_ADB) != 0)) {
                    userIdentifier = UserHandle.USER_OWNER;
                }

                /*
                 * Determine if we have any installed package verifiers. If we
                 * do, then we'll defer to them to verify the packages.
                 */
                final int requiredUid = mRequiredVerifierPackage == null ? -1
                        : getPackageUid(mRequiredVerifierPackage, userIdentifier);
                if (!origin.existing && requiredUid != -1
                        && isVerificationEnabled(userIdentifier, installFlags)) {
                    final Intent verification = new Intent(
                            Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
                    verification.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    verification.setDataAndType(Uri.fromFile(new File(origin.resolvedPath)),
                            PACKAGE_MIME_TYPE);
                    verification.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                    final List<ResolveInfo> receivers = queryIntentReceivers(verification,
                            PACKAGE_MIME_TYPE, PackageManager.GET_DISABLED_COMPONENTS,
                            0 /* TODO: Which userId? */);

                    if (DEBUG_VERIFY) {
                        Slog.d(TAG, "Found " + receivers.size() + " verifiers for intent "
                                + verification.toString() + " with " + pkgLite.verifiers.length
                                + " optional verifiers");
                    }

                    final int verificationId = mPendingVerificationToken++;

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_ID, verificationId);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_PACKAGE,
                            installerPackageName);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALL_FLAGS,
                            installFlags);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_PACKAGE_NAME,
                            pkgLite.packageName);

                    verification.putExtra(PackageManager.EXTRA_VERIFICATION_VERSION_CODE,
                            pkgLite.versionCode);

                    if (verificationParams != null) {
                        if (verificationParams.getVerificationURI() != null) {
                           verification.putExtra(PackageManager.EXTRA_VERIFICATION_URI,
                                 verificationParams.getVerificationURI());
                        }
                        if (verificationParams.getOriginatingURI() != null) {
                            verification.putExtra(Intent.EXTRA_ORIGINATING_URI,
                                  verificationParams.getOriginatingURI());
                        }
                        if (verificationParams.getReferrer() != null) {
                            verification.putExtra(Intent.EXTRA_REFERRER,
                                  verificationParams.getReferrer());
                        }
                        if (verificationParams.getOriginatingUid() >= 0) {
                            verification.putExtra(Intent.EXTRA_ORIGINATING_UID,
                                  verificationParams.getOriginatingUid());
                        }
                        if (verificationParams.getInstallerUid() >= 0) {
                            verification.putExtra(PackageManager.EXTRA_VERIFICATION_INSTALLER_UID,
                                  verificationParams.getInstallerUid());
                        }
                    }

                    final PackageVerificationState verificationState = new PackageVerificationState(
                            requiredUid, args);

                    mPendingVerification.append(verificationId, verificationState);

                    final List<ComponentName> sufficientVerifiers = matchVerifiers(pkgLite,
                            receivers, verificationState);

                    // Apps installed for "all" users use the device owner to verify the app
                    UserHandle verifierUser = getUser();
                    if (verifierUser == UserHandle.ALL) {
                        verifierUser = UserHandle.OWNER;
                    }

                    /*
                     * If any sufficient verifiers were listed in the package
                     * manifest, attempt to ask them.
                     */
                    if (sufficientVerifiers != null) {
                        final int N = sufficientVerifiers.size();
                        if (N == 0) {
                            Slog.i(TAG, "Additional verifiers required, but none installed.");
                            ret = PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE;
                        } else {
                            for (int i = 0; i < N; i++) {
                                final ComponentName verifierComponent = sufficientVerifiers.get(i);

                                final Intent sufficientIntent = new Intent(verification);
                                sufficientIntent.setComponent(verifierComponent);
                                mContext.sendBroadcastAsUser(sufficientIntent, verifierUser);
                            }
                        }
                    }

                    final ComponentName requiredVerifierComponent = matchComponentForVerifier(
                            mRequiredVerifierPackage, receivers);
                    if (ret == PackageManager.INSTALL_SUCCEEDED
                            && mRequiredVerifierPackage != null) {
                        /*
                         * Send the intent to the required verification agent,
                         * but only start the verification timeout after the
                         * target BroadcastReceivers have run.
                         */
                        verification.setComponent(requiredVerifierComponent);
                        mContext.sendOrderedBroadcastAsUser(verification, verifierUser,
                                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                                new BroadcastReceiver() {
                                    @Override
                                    public void onReceive(Context context, Intent intent) {
                                        final Message msg = mHandler
                                                .obtainMessage(CHECK_PENDING_VERIFICATION);
                                        msg.arg1 = verificationId;
                                        mHandler.sendMessageDelayed(msg, getVerificationTimeout());
                                    }
                                }, null, 0, null, null);

                        /*
                         * We don't want the copy to proceed until verification
                         * succeeds, so null out this field.
                         */
                        mArgs = null;
                    }
                } else {
                    /*
                     * No package verification is enabled, so immediately start
                     * the remote call to initiate copy using temporary file.
                     */
                    ret = args.copyApk(mContainerService, true);
                }
            }

            mRet = ret;
        }

        @Override
        void handleReturnCode() {
            // If mArgs is null, then MCS couldn't be reached. When it
            // reconnects, it will try again to install. At that point, this
            // will succeed.
            if (mArgs != null) {
                processPendingInstall(mArgs, mRet);
            }
        }

        @Override
        void handleServiceError() {
            mArgs = createInstallArgs(this);
            mRet = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        }

        public boolean isForwardLocked() {
            return (installFlags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
        }
    }

    /**
     * Used during creation of InstallArgs
     *
     * @param installFlags package installation flags
     * @return true if should be installed on external storage
     */
    private static boolean installOnExternalAsec(int installFlags) {
        if ((installFlags & PackageManager.INSTALL_INTERNAL) != 0) {
            return false;
        }
        if ((installFlags & PackageManager.INSTALL_EXTERNAL) != 0) {
            return true;
        }
        return false;
    }

    /**
     * Used during creation of InstallArgs
     *
     * @param installFlags package installation flags
     * @return true if should be installed as forward locked
     */
    private static boolean installForwardLocked(int installFlags) {
        return (installFlags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
    }

    private InstallArgs createInstallArgs(InstallParams params) {
        if (params.move != null) {
            return new MoveInstallArgs(params);
        } else if (installOnExternalAsec(params.installFlags) || params.isForwardLocked()) {
            return new AsecInstallArgs(params);
        } else {
            return new FileInstallArgs(params);
        }
    }

    /**
     * Create args that describe an existing installed package. Typically used
     * when cleaning up old installs, or used as a move source.
     */
    private InstallArgs createInstallArgsForExisting(int installFlags, String codePath,
            String resourcePath, String[] instructionSets) {
        final boolean isInAsec;
        if (installOnExternalAsec(installFlags)) {
            /* Apps on SD card are always in ASEC containers. */
            isInAsec = true;
        } else if (installForwardLocked(installFlags)
                && !codePath.startsWith(mDrmAppPrivateInstallDir.getAbsolutePath())) {
            /*
             * Forward-locked apps are only in ASEC containers if they're the
             * new style
             */
            isInAsec = true;
        } else {
            isInAsec = false;
        }

        if (isInAsec) {
            return new AsecInstallArgs(codePath, instructionSets,
                    installOnExternalAsec(installFlags), installForwardLocked(installFlags));
        } else {
            return new FileInstallArgs(codePath, resourcePath, instructionSets);
        }
    }

    static abstract class InstallArgs {
        /** @see InstallParams#origin */
        final OriginInfo origin;
        /** @see InstallParams#move */
        final MoveInfo move;

        final IPackageInstallObserver2 observer;
        // Always refers to PackageManager flags only
        final int installFlags;
        final String installerPackageName;
        final String volumeUuid;
        final ManifestDigest manifestDigest;
        final UserHandle user;
        final String abiOverride;
        final String[] installGrantPermissions;

        // The list of instruction sets supported by this app. This is currently
        // only used during the rmdex() phase to clean up resources. We can get rid of this
        // if we move dex files under the common app path.
        /* nullable */ String[] instructionSets;

        InstallArgs(OriginInfo origin, MoveInfo move, IPackageInstallObserver2 observer,
                int installFlags, String installerPackageName, String volumeUuid,
                ManifestDigest manifestDigest, UserHandle user, String[] instructionSets,
                String abiOverride, String[] installGrantPermissions) {
            this.origin = origin;
            this.move = move;
            this.installFlags = installFlags;
            this.observer = observer;
            this.installerPackageName = installerPackageName;
            this.volumeUuid = volumeUuid;
            this.manifestDigest = manifestDigest;
            this.user = user;
            this.instructionSets = instructionSets;
            this.abiOverride = abiOverride;
            this.installGrantPermissions = installGrantPermissions;
        }

        abstract int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException;
        abstract int doPreInstall(int status);

        /**
         * Rename package into final resting place. All paths on the given
         * scanned package should be updated to reflect the rename.
         */
        abstract boolean doRename(int status, PackageParser.Package pkg, String oldCodePath);
        abstract int doPostInstall(int status, int uid);

        /** @see PackageSettingBase#codePathString */
        abstract String getCodePath();
        /** @see PackageSettingBase#resourcePathString */
        abstract String getResourcePath();

        // Need installer lock especially for dex file removal.
        abstract void cleanUpResourcesLI();
        abstract boolean doPostDeleteLI(boolean delete);

        /**
         * Called before the source arguments are copied. This is used mostly
         * for MoveParams when it needs to read the source file to put it in the
         * destination.
         */
        int doPreCopy() {
            return PackageManager.INSTALL_SUCCEEDED;
        }

        /**
         * Called after the source arguments are copied. This is used mostly for
         * MoveParams when it needs to read the source file to put it in the
         * destination.
         *
         * @return
         */
        int doPostCopy(int uid) {
            return PackageManager.INSTALL_SUCCEEDED;
        }

        protected boolean isFwdLocked() {
            return (installFlags & PackageManager.INSTALL_FORWARD_LOCK) != 0;
        }

        protected boolean isExternalAsec() {
            return (installFlags & PackageManager.INSTALL_EXTERNAL) != 0;
        }

        UserHandle getUser() {
            return user;
        }
    }

    private void removeDexFiles(List<String> allCodePaths, String[] instructionSets) {
        if (!allCodePaths.isEmpty()) {
            if (instructionSets == null) {
                throw new IllegalStateException("instructionSet == null");
            }
            String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
            for (String codePath : allCodePaths) {
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    int retCode = mInstaller.rmdex(codePath, dexCodeInstructionSet);
                    if (retCode < 0) {
                        Slog.w(TAG, "Couldn't remove dex file for package: "
                                + " at location " + codePath + ", retcode=" + retCode);
                        // we don't consider this to be a failure of the core package deletion
                    }
                }
            }
        }
    }

    /**
     * Logic to handle installation of non-ASEC applications, including copying
     * and renaming logic.
     */
    class FileInstallArgs extends InstallArgs {
        private File codeFile;
        private File resourceFile;

        // Example topology:
        // /data/app/com.example/base.apk
        // /data/app/com.example/split_foo.apk
        // /data/app/com.example/lib/arm/libfoo.so
        // /data/app/com.example/lib/arm64/libfoo.so
        // /data/app/com.example/dalvik/arm/base.apk@classes.dex

        /** New install */
        FileInstallArgs(InstallParams params) {
            super(params.origin, params.move, params.observer, params.installFlags,
                    params.installerPackageName, params.volumeUuid, params.getManifestDigest(),
                    params.getUser(), null /* instruction sets */, params.packageAbiOverride,
                    params.grantedRuntimePermissions);
            if (isFwdLocked()) {
                throw new IllegalArgumentException("Forward locking only supported in ASEC");
            }
        }

        /** Existing install */
        FileInstallArgs(String codePath, String resourcePath, String[] instructionSets) {
            super(OriginInfo.fromNothing(), null, null, 0, null, null, null, null, instructionSets,
                    null, null);
            this.codeFile = (codePath != null) ? new File(codePath) : null;
            this.resourceFile = (resourcePath != null) ? new File(resourcePath) : null;
        }

        int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            if (origin.staged) {
                if (DEBUG_INSTALL) Slog.d(TAG, origin.file + " already staged; skipping copy");
                codeFile = origin.file;
                resourceFile = origin.file;
                return PackageManager.INSTALL_SUCCEEDED;
            }

            try {
                final File tempDir = mInstallerService.allocateStageDirLegacy(volumeUuid);
                codeFile = tempDir;
                resourceFile = tempDir;
            } catch (IOException e) {
                Slog.w(TAG, "Failed to create copy file: " + e);
                return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
            }

            final IParcelFileDescriptorFactory target = new IParcelFileDescriptorFactory.Stub() {
                @Override
                public ParcelFileDescriptor open(String name, int mode) throws RemoteException {
                    if (!FileUtils.isValidExtFilename(name)) {
                        throw new IllegalArgumentException("Invalid filename: " + name);
                    }
                    try {
                        final File file = new File(codeFile, name);
                        final FileDescriptor fd = Os.open(file.getAbsolutePath(),
                                O_RDWR | O_CREAT, 0644);
                        Os.chmod(file.getAbsolutePath(), 0644);
                        return new ParcelFileDescriptor(fd);
                    } catch (ErrnoException e) {
                        throw new RemoteException("Failed to open: " + e.getMessage());
                    }
                }
            };

            int ret = PackageManager.INSTALL_SUCCEEDED;
            ret = imcs.copyPackage(origin.file.getAbsolutePath(), target);
            if (ret != PackageManager.INSTALL_SUCCEEDED) {
                Slog.e(TAG, "Failed to copy package");
                return ret;
            }

            final File libraryRoot = new File(codeFile, LIB_DIR_NAME);
            NativeLibraryHelper.Handle handle = null;
            try {
                handle = NativeLibraryHelper.Handle.create(codeFile);
                ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                        abiOverride);
            } catch (IOException e) {
                Slog.e(TAG, "Copying native libraries failed", e);
                ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            } finally {
                IoUtils.closeQuietly(handle);
            }

            return ret;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        boolean doRename(int status, PackageParser.Package pkg, String oldCodePath) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
                return false;
            }

            final File targetDir = codeFile.getParentFile();
            final File beforeCodeFile = codeFile;
            final File afterCodeFile = getNextCodePath(targetDir, pkg.packageName);

            if (DEBUG_INSTALL) Slog.d(TAG, "Renaming " + beforeCodeFile + " to " + afterCodeFile);
            try {
                Os.rename(beforeCodeFile.getAbsolutePath(), afterCodeFile.getAbsolutePath());
            } catch (ErrnoException e) {
                Slog.w(TAG, "Failed to rename", e);
                return false;
            }

            if (!SELinux.restoreconRecursive(afterCodeFile)) {
                Slog.w(TAG, "Failed to restorecon");
                return false;
            }

            // Reflect the rename internally
            codeFile = afterCodeFile;
            resourceFile = afterCodeFile;

            // Reflect the rename in scanned details
            pkg.codePath = afterCodeFile.getAbsolutePath();
            pkg.baseCodePath = FileUtils.rewriteAfterRename(beforeCodeFile, afterCodeFile,
                    pkg.baseCodePath);
            pkg.splitCodePaths = FileUtils.rewriteAfterRename(beforeCodeFile, afterCodeFile,
                    pkg.splitCodePaths);

            // Reflect the rename in app info
            pkg.applicationInfo.volumeUuid = pkg.volumeUuid;
            pkg.applicationInfo.setCodePath(pkg.codePath);
            pkg.applicationInfo.setBaseCodePath(pkg.baseCodePath);
            pkg.applicationInfo.setSplitCodePaths(pkg.splitCodePaths);
            pkg.applicationInfo.setResourcePath(pkg.codePath);
            pkg.applicationInfo.setBaseResourcePath(pkg.baseCodePath);
            pkg.applicationInfo.setSplitResourcePaths(pkg.splitCodePaths);

            return true;
        }

        int doPostInstall(int status, int uid) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            }
            return status;
        }

        @Override
        String getCodePath() {
            return (codeFile != null) ? codeFile.getAbsolutePath() : null;
        }

        @Override
        String getResourcePath() {
            return (resourceFile != null) ? resourceFile.getAbsolutePath() : null;
        }

        private boolean cleanUp() {
            if (codeFile == null || !codeFile.exists()) {
                return false;
            }

            if (codeFile.isDirectory()) {
                mInstaller.rmPackageDir(codeFile.getAbsolutePath());
            } else {
                codeFile.delete();
            }

            if (resourceFile != null && !FileUtils.contains(codeFile, resourceFile)) {
                resourceFile.delete();
            }

            return true;
        }

        void cleanUpResourcesLI() {
            // Try enumerating all code paths before deleting
            List<String> allCodePaths = Collections.EMPTY_LIST;
            if (codeFile != null && codeFile.exists()) {
                try {
                    final PackageLite pkg = PackageParser.parsePackageLite(codeFile, 0);
                    allCodePaths = pkg.getAllCodePaths();
                } catch (PackageParserException e) {
                    // Ignored; we tried our best
                }
            }

            cleanUp();
            removeDexFiles(allCodePaths, instructionSets);
        }

        boolean doPostDeleteLI(boolean delete) {
            // XXX err, shouldn't we respect the delete flag?
            cleanUpResourcesLI();
            return true;
        }
    }

    private boolean isAsecExternal(String cid) {
        final String asecPath = PackageHelper.getSdFilesystem(cid);
        return !asecPath.startsWith(mAsecInternalPath);
    }

    private static void maybeThrowExceptionForMultiArchCopy(String message, int copyRet) throws
            PackageManagerException {
        if (copyRet < 0) {
            if (copyRet != PackageManager.NO_NATIVE_LIBRARIES &&
                    copyRet != PackageManager.INSTALL_FAILED_NO_MATCHING_ABIS) {
                throw new PackageManagerException(copyRet, message);
            }
        }
    }

    /**
     * Extract the MountService "container ID" from the full code path of an
     * .apk.
     */
    static String cidFromCodePath(String fullCodePath) {
        int eidx = fullCodePath.lastIndexOf("/");
        String subStr1 = fullCodePath.substring(0, eidx);
        int sidx = subStr1.lastIndexOf("/");
        return subStr1.substring(sidx+1, eidx);
    }

    /**
     * Logic to handle installation of ASEC applications, including copying and
     * renaming logic.
     */
    class AsecInstallArgs extends InstallArgs {
        static final String RES_FILE_NAME = "pkg.apk";
        static final String PUBLIC_RES_FILE_NAME = "res.zip";

        String cid;
        String packagePath;
        String resourcePath;

        /** New install */
        AsecInstallArgs(InstallParams params) {
            super(params.origin, params.move, params.observer, params.installFlags,
                    params.installerPackageName, params.volumeUuid, params.getManifestDigest(),
                    params.getUser(), null /* instruction sets */, params.packageAbiOverride,
                    params.grantedRuntimePermissions);
        }

        /** Existing install */
        AsecInstallArgs(String fullCodePath, String[] instructionSets,
                        boolean isExternal, boolean isForwardLocked) {
            super(OriginInfo.fromNothing(), null, null, (isExternal ? INSTALL_EXTERNAL : 0)
                    | (isForwardLocked ? INSTALL_FORWARD_LOCK : 0), null, null, null, null,
                    instructionSets, null, null);
            // Hackily pretend we're still looking at a full code path
            if (!fullCodePath.endsWith(RES_FILE_NAME)) {
                fullCodePath = new File(fullCodePath, RES_FILE_NAME).getAbsolutePath();
            }

            // Extract cid from fullCodePath
            int eidx = fullCodePath.lastIndexOf("/");
            String subStr1 = fullCodePath.substring(0, eidx);
            int sidx = subStr1.lastIndexOf("/");
            cid = subStr1.substring(sidx+1, eidx);
            setMountPath(subStr1);
        }

        AsecInstallArgs(String cid, String[] instructionSets, boolean isForwardLocked) {
            super(OriginInfo.fromNothing(), null, null, (isAsecExternal(cid) ? INSTALL_EXTERNAL : 0)
                    | (isForwardLocked ? INSTALL_FORWARD_LOCK : 0), null, null, null, null,
                    instructionSets, null, null);
            this.cid = cid;
            setMountPath(PackageHelper.getSdDir(cid));
        }

        void createCopyFile() {
            cid = mInstallerService.allocateExternalStageCidLegacy();
        }

        int copyApk(IMediaContainerService imcs, boolean temp) throws RemoteException {
            if (origin.staged) {
                if (DEBUG_INSTALL) Slog.d(TAG, origin.cid + " already staged; skipping copy");
                cid = origin.cid;
                setMountPath(PackageHelper.getSdDir(cid));
                return PackageManager.INSTALL_SUCCEEDED;
            }

            if (temp) {
                createCopyFile();
            } else {
                /*
                 * Pre-emptively destroy the container since it's destroyed if
                 * copying fails due to it existing anyway.
                 */
                PackageHelper.destroySdDir(cid);
            }

            final String newMountPath = imcs.copyPackageToContainer(
                    origin.file.getAbsolutePath(), cid, getEncryptKey(), isExternalAsec(),
                    isFwdLocked(), deriveAbiOverride(abiOverride, null /* settings */));

            if (newMountPath != null) {
                setMountPath(newMountPath);
                return PackageManager.INSTALL_SUCCEEDED;
            } else {
                return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
            }
        }

        @Override
        String getCodePath() {
            return packagePath;
        }

        @Override
        String getResourcePath() {
            return resourcePath;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                // Destroy container
                PackageHelper.destroySdDir(cid);
            } else {
                boolean mounted = PackageHelper.isContainerMounted(cid);
                if (!mounted) {
                    String newMountPath = PackageHelper.mountSdDir(cid, getEncryptKey(),
                            Process.SYSTEM_UID);
                    if (newMountPath != null) {
                        setMountPath(newMountPath);
                    } else {
                        return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                    }
                }
            }
            return status;
        }

        boolean doRename(int status, PackageParser.Package pkg, String oldCodePath) {
            String newCacheId = getNextCodePath(oldCodePath, pkg.packageName, "/" + RES_FILE_NAME);
            String newMountPath = null;
            if (PackageHelper.isContainerMounted(cid)) {
                // Unmount the container
                if (!PackageHelper.unMountSdDir(cid)) {
                    Slog.i(TAG, "Failed to unmount " + cid + " before renaming");
                    return false;
                }
            }
            if (!PackageHelper.renameSdDir(cid, newCacheId)) {
                Slog.e(TAG, "Failed to rename " + cid + " to " + newCacheId +
                        " which might be stale. Will try to clean up.");
                // Clean up the stale container and proceed to recreate.
                if (!PackageHelper.destroySdDir(newCacheId)) {
                    Slog.e(TAG, "Very strange. Cannot clean up stale container " + newCacheId);
                    return false;
                }
                // Successfully cleaned up stale container. Try to rename again.
                if (!PackageHelper.renameSdDir(cid, newCacheId)) {
                    Slog.e(TAG, "Failed to rename " + cid + " to " + newCacheId
                            + " inspite of cleaning it up.");
                    return false;
                }
            }
            if (!PackageHelper.isContainerMounted(newCacheId)) {
                Slog.w(TAG, "Mounting container " + newCacheId);
                newMountPath = PackageHelper.mountSdDir(newCacheId,
                        getEncryptKey(), Process.SYSTEM_UID);
            } else {
                newMountPath = PackageHelper.getSdDir(newCacheId);
            }
            if (newMountPath == null) {
                Slog.w(TAG, "Failed to get cache path for  " + newCacheId);
                return false;
            }
            Log.i(TAG, "Succesfully renamed " + cid +
                    " to " + newCacheId +
                    " at new path: " + newMountPath);
            cid = newCacheId;

            final File beforeCodeFile = new File(packagePath);
            setMountPath(newMountPath);
            final File afterCodeFile = new File(packagePath);

            // Reflect the rename in scanned details
            pkg.codePath = afterCodeFile.getAbsolutePath();
            pkg.baseCodePath = FileUtils.rewriteAfterRename(beforeCodeFile, afterCodeFile,
                    pkg.baseCodePath);
            pkg.splitCodePaths = FileUtils.rewriteAfterRename(beforeCodeFile, afterCodeFile,
                    pkg.splitCodePaths);

            // Reflect the rename in app info
            pkg.applicationInfo.volumeUuid = pkg.volumeUuid;
            pkg.applicationInfo.setCodePath(pkg.codePath);
            pkg.applicationInfo.setBaseCodePath(pkg.baseCodePath);
            pkg.applicationInfo.setSplitCodePaths(pkg.splitCodePaths);
            pkg.applicationInfo.setResourcePath(pkg.codePath);
            pkg.applicationInfo.setBaseResourcePath(pkg.baseCodePath);
            pkg.applicationInfo.setSplitResourcePaths(pkg.splitCodePaths);

            return true;
        }

        private void setMountPath(String mountPath) {
            final File mountFile = new File(mountPath);

            final File monolithicFile = new File(mountFile, RES_FILE_NAME);
            if (monolithicFile.exists()) {
                packagePath = monolithicFile.getAbsolutePath();
                if (isFwdLocked()) {
                    resourcePath = new File(mountFile, PUBLIC_RES_FILE_NAME).getAbsolutePath();
                } else {
                    resourcePath = packagePath;
                }
            } else {
                packagePath = mountFile.getAbsolutePath();
                resourcePath = packagePath;
            }
        }

        int doPostInstall(int status, int uid) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp();
            } else {
                final int groupOwner;
                final String protectedFile;
                if (isFwdLocked()) {
                    groupOwner = UserHandle.getSharedAppGid(uid);
                    protectedFile = RES_FILE_NAME;
                } else {
                    groupOwner = -1;
                    protectedFile = null;
                }

                if (uid < Process.FIRST_APPLICATION_UID
                        || !PackageHelper.fixSdPermissions(cid, groupOwner, protectedFile)) {
                    Slog.e(TAG, "Failed to finalize " + cid);
                    PackageHelper.destroySdDir(cid);
                    return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                }

                boolean mounted = PackageHelper.isContainerMounted(cid);
                if (!mounted) {
                    PackageHelper.mountSdDir(cid, getEncryptKey(), Process.myUid());
                }
            }
            return status;
        }

        private void cleanUp() {
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "cleanUp");

            // Destroy secure container
            PackageHelper.destroySdDir(cid);
        }

        private List<String> getAllCodePaths() {
            final File codeFile = new File(getCodePath());
            if (codeFile != null && codeFile.exists()) {
                try {
                    final PackageLite pkg = PackageParser.parsePackageLite(codeFile, 0);
                    return pkg.getAllCodePaths();
                } catch (PackageParserException e) {
                    // Ignored; we tried our best
                }
            }
            return Collections.EMPTY_LIST;
        }

        void cleanUpResourcesLI() {
            // Enumerate all code paths before deleting
            cleanUpResourcesLI(getAllCodePaths());
        }

        private void cleanUpResourcesLI(List<String> allCodePaths) {
            cleanUp();
            removeDexFiles(allCodePaths, instructionSets);
        }

        String getPackageName() {
            return getAsecPackageName(cid);
        }

        boolean doPostDeleteLI(boolean delete) {
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "doPostDeleteLI() del=" + delete);
            final List<String> allCodePaths = getAllCodePaths();
            boolean mounted = PackageHelper.isContainerMounted(cid);
            if (mounted) {
                // Unmount first
                if (PackageHelper.unMountSdDir(cid)) {
                    mounted = false;
                }
            }
            if (!mounted && delete) {
                cleanUpResourcesLI(allCodePaths);
            }
            return !mounted;
        }

        @Override
        int doPreCopy() {
            if (isFwdLocked()) {
                if (!PackageHelper.fixSdPermissions(cid,
                        getPackageUid(DEFAULT_CONTAINER_PACKAGE, 0), RES_FILE_NAME)) {
                    return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                }
            }

            return PackageManager.INSTALL_SUCCEEDED;
        }

        @Override
        int doPostCopy(int uid) {
            if (isFwdLocked()) {
                if (uid < Process.FIRST_APPLICATION_UID
                        || !PackageHelper.fixSdPermissions(cid, UserHandle.getSharedAppGid(uid),
                                RES_FILE_NAME)) {
                    Slog.e(TAG, "Failed to finalize " + cid);
                    PackageHelper.destroySdDir(cid);
                    return PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
                }
            }

            return PackageManager.INSTALL_SUCCEEDED;
        }
    }

    /**
     * Logic to handle movement of existing installed applications.
     */
    class MoveInstallArgs extends InstallArgs {
        private File codeFile;
        private File resourceFile;

        /** New install */
        MoveInstallArgs(InstallParams params) {
            super(params.origin, params.move, params.observer, params.installFlags,
                    params.installerPackageName, params.volumeUuid, params.getManifestDigest(),
                    params.getUser(), null /* instruction sets */, params.packageAbiOverride,
                    params.grantedRuntimePermissions);
        }

        int copyApk(IMediaContainerService imcs, boolean temp) {
            if (DEBUG_INSTALL) Slog.d(TAG, "Moving " + move.packageName + " from "
                    + move.fromUuid + " to " + move.toUuid);
            synchronized (mInstaller) {
                if (mInstaller.copyCompleteApp(move.fromUuid, move.toUuid, move.packageName,
                        move.dataAppName, move.appId, move.seinfo) != 0) {
                    return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
                }
            }

            codeFile = new File(Environment.getDataAppDirectory(move.toUuid), move.dataAppName);
            resourceFile = codeFile;
            if (DEBUG_INSTALL) Slog.d(TAG, "codeFile after move is " + codeFile);

            return PackageManager.INSTALL_SUCCEEDED;
        }

        int doPreInstall(int status) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp(move.toUuid);
            }
            return status;
        }

        boolean doRename(int status, PackageParser.Package pkg, String oldCodePath) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                cleanUp(move.toUuid);
                return false;
            }

            // Reflect the move in app info
            pkg.applicationInfo.volumeUuid = pkg.volumeUuid;
            pkg.applicationInfo.setCodePath(pkg.codePath);
            pkg.applicationInfo.setBaseCodePath(pkg.baseCodePath);
            pkg.applicationInfo.setSplitCodePaths(pkg.splitCodePaths);
            pkg.applicationInfo.setResourcePath(pkg.codePath);
            pkg.applicationInfo.setBaseResourcePath(pkg.baseCodePath);
            pkg.applicationInfo.setSplitResourcePaths(pkg.splitCodePaths);

            return true;
        }

        int doPostInstall(int status, int uid) {
            if (status == PackageManager.INSTALL_SUCCEEDED) {
                cleanUp(move.fromUuid);
            } else {
                cleanUp(move.toUuid);
            }
            return status;
        }

        @Override
        String getCodePath() {
            return (codeFile != null) ? codeFile.getAbsolutePath() : null;
        }

        @Override
        String getResourcePath() {
            return (resourceFile != null) ? resourceFile.getAbsolutePath() : null;
        }

        private boolean cleanUp(String volumeUuid) {
            final File codeFile = new File(Environment.getDataAppDirectory(volumeUuid),
                    move.dataAppName);
            Slog.d(TAG, "Cleaning up " + move.packageName + " on " + volumeUuid);
            synchronized (mInstallLock) {
                // Clean up both app data and code
                removeDataDirsLI(volumeUuid, move.packageName);
                if (codeFile.isDirectory()) {
                    mInstaller.rmPackageDir(codeFile.getAbsolutePath());
                } else {
                    codeFile.delete();
                }
            }
            return true;
        }

        void cleanUpResourcesLI() {
            throw new UnsupportedOperationException();
        }

        boolean doPostDeleteLI(boolean delete) {
            throw new UnsupportedOperationException();
        }
    }

    static String getAsecPackageName(String packageCid) {
        int idx = packageCid.lastIndexOf("-");
        if (idx == -1) {
            return packageCid;
        }
        return packageCid.substring(0, idx);
    }

    // Utility method used to create code paths based on package name and available index.
    private static String getNextCodePath(String oldCodePath, String prefix, String suffix) {
        String idxStr = "";
        int idx = 1;
        // Fall back to default value of idx=1 if prefix is not
        // part of oldCodePath
        if (oldCodePath != null) {
            String subStr = oldCodePath;
            // Drop the suffix right away
            if (suffix != null && subStr.endsWith(suffix)) {
                subStr = subStr.substring(0, subStr.length() - suffix.length());
            }
            // If oldCodePath already contains prefix find out the
            // ending index to either increment or decrement.
            int sidx = subStr.lastIndexOf(prefix);
            if (sidx != -1) {
                subStr = subStr.substring(sidx + prefix.length());
                if (subStr != null) {
                    if (subStr.startsWith(INSTALL_PACKAGE_SUFFIX)) {
                        subStr = subStr.substring(INSTALL_PACKAGE_SUFFIX.length());
                    }
                    try {
                        idx = Integer.parseInt(subStr);
                        if (idx <= 1) {
                            idx++;
                        } else {
                            idx--;
                        }
                    } catch(NumberFormatException e) {
                    }
                }
            }
        }
        idxStr = INSTALL_PACKAGE_SUFFIX + Integer.toString(idx);
        return prefix + idxStr;
    }

    private File getNextCodePath(File targetDir, String packageName) {
        int suffix = 1;
        File result;
        do {
            result = new File(targetDir, packageName + "-" + suffix);
            suffix++;
        } while (result.exists());
        return result;
    }

    // Utility method that returns the relative package path with respect
    // to the installation directory. Like say for /data/data/com.test-1.apk
    // string com.test-1 is returned.
    static String deriveCodePathName(String codePath) {
        if (codePath == null) {
            return null;
        }
        final File codeFile = new File(codePath);
        final String name = codeFile.getName();
        if (codeFile.isDirectory()) {
            return name;
        } else if (name.endsWith(".apk") || name.endsWith(".tmp")) {
            final int lastDot = name.lastIndexOf('.');
            return name.substring(0, lastDot);
        } else {
            Slog.w(TAG, "Odd, " + codePath + " doesn't look like an APK");
            return null;
        }
    }

    class PackageInstalledInfo {
        String name;
        int uid;
        // The set of users that originally had this package installed.
        int[] origUsers;
        // The set of users that now have this package installed.
        int[] newUsers;
        PackageParser.Package pkg;
        int returnCode;
        String returnMsg;
        PackageRemovedInfo removedInfo;

        public void setError(int code, String msg) {
            returnCode = code;
            returnMsg = msg;
            Slog.w(TAG, msg);
        }

        public void setError(String msg, PackageParserException e) {
            returnCode = e.error;
            returnMsg = ExceptionUtils.getCompleteMessage(msg, e);
            Slog.w(TAG, msg, e);
        }

        public void setError(String msg, PackageManagerException e) {
            returnCode = e.error;
            returnMsg = ExceptionUtils.getCompleteMessage(msg, e);
            Slog.w(TAG, msg, e);
        }

        // In some error cases we want to convey more info back to the observer
        String origPackage;
        String origPermission;
    }

    /*
     * Install a non-existing package.
     */
    private void installNewPackageLI(PackageParser.Package pkg, int parseFlags, int scanFlags,
            UserHandle user, String installerPackageName, String volumeUuid,
            PackageInstalledInfo res) {
        // Remember this for later, in case we need to rollback this install
        String pkgName = pkg.packageName;

        if (DEBUG_INSTALL) Slog.d(TAG, "installNewPackageLI: " + pkg);
        final boolean dataDirExists = Environment
                .getDataUserPackageDirectory(volumeUuid, UserHandle.USER_OWNER, pkgName).exists();
        synchronized(mPackages) {
            if (mSettings.mRenamedPackages.containsKey(pkgName)) {
                // A package with the same name is already installed, though
                // it has been renamed to an older name.  The package we
                // are trying to install should be installed as an update to
                // the existing one, but that has not been requested, so bail.
                res.setError(INSTALL_FAILED_ALREADY_EXISTS, "Attempt to re-install " + pkgName
                        + " without first uninstalling package running as "
                        + mSettings.mRenamedPackages.get(pkgName));
                return;
            }
            if (mPackages.containsKey(pkgName)) {
                // Don't allow installation over an existing package with the same name.
                res.setError(INSTALL_FAILED_ALREADY_EXISTS, "Attempt to re-install " + pkgName
                        + " without first uninstalling.");
                return;
            }
        }

        try {
            PackageParser.Package newPackage = scanPackageLI(pkg, parseFlags, scanFlags,
                    System.currentTimeMillis(), user);

            updateSettingsLI(newPackage, installerPackageName, volumeUuid, null, null, res, user);
            // delete the partially installed application. the data directory will have to be
            // restored if it was already existing
            if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
                // remove package from internal structures.  Note that we want deletePackageX to
                // delete the package data and cache directories that it created in
                // scanPackageLocked, unless those directories existed before we even tried to
                // install.
                deletePackageLI(pkgName, UserHandle.ALL, false, null, null,
                        dataDirExists ? PackageManager.DELETE_KEEP_DATA : 0,
                                res.removedInfo, true);
            }

        } catch (PackageManagerException e) {
            res.setError("Package couldn't be installed in " + pkg.codePath, e);
        }
    }

    private boolean shouldCheckUpgradeKeySetLP(PackageSetting oldPs, int scanFlags) {
        // Can't rotate keys during boot or if sharedUser.
        if (oldPs == null || (scanFlags&SCAN_INITIAL) != 0 || oldPs.sharedUser != null
                || !oldPs.keySetData.isUsingUpgradeKeySets()) {
            return false;
        }
        // app is using upgradeKeySets; make sure all are valid
        KeySetManagerService ksms = mSettings.mKeySetManagerService;
        long[] upgradeKeySets = oldPs.keySetData.getUpgradeKeySets();
        for (int i = 0; i < upgradeKeySets.length; i++) {
            if (!ksms.isIdValidKeySetId(upgradeKeySets[i])) {
                Slog.wtf(TAG, "Package "
                         + (oldPs.name != null ? oldPs.name : "<null>")
                         + " contains upgrade-key-set reference to unknown key-set: "
                         + upgradeKeySets[i]
                         + " reverting to signatures check.");
                return false;
            }
        }
        return true;
    }

    private boolean checkUpgradeKeySetLP(PackageSetting oldPS, PackageParser.Package newPkg) {
        // Upgrade keysets are being used.  Determine if new package has a superset of the
        // required keys.
        long[] upgradeKeySets = oldPS.keySetData.getUpgradeKeySets();
        KeySetManagerService ksms = mSettings.mKeySetManagerService;
        for (int i = 0; i < upgradeKeySets.length; i++) {
            Set<PublicKey> upgradeSet = ksms.getPublicKeysFromKeySetLPr(upgradeKeySets[i]);
            if (upgradeSet != null && newPkg.mSigningKeys.containsAll(upgradeSet)) {
                return true;
            }
        }
        return false;
    }

    private void replacePackageLI(PackageParser.Package pkg, int parseFlags, int scanFlags,
            UserHandle user, String installerPackageName, String volumeUuid,
            PackageInstalledInfo res) {
        final PackageParser.Package oldPackage;
        final String pkgName = pkg.packageName;
        final int[] allUsers;
        final boolean[] perUserInstalled;

        // First find the old package info and check signatures
        synchronized(mPackages) {
            oldPackage = mPackages.get(pkgName);
            if (DEBUG_INSTALL) Slog.d(TAG, "replacePackageLI: new=" + pkg + ", old=" + oldPackage);
            final PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (shouldCheckUpgradeKeySetLP(ps, scanFlags)) {
                if(!checkUpgradeKeySetLP(ps, pkg)) {
                    res.setError(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                            "New package not signed by keys specified by upgrade-keysets: "
                            + pkgName);
                    return;
                }
            } else {
                // default to original signature matching
                if (compareSignatures(oldPackage.mSignatures, pkg.mSignatures)
                    != PackageManager.SIGNATURE_MATCH) {
                    res.setError(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                            "New package has a different signature: " + pkgName);
                    return;
                }
            }

            // In case of rollback, remember per-user/profile install state
            allUsers = sUserManager.getUserIds();
            perUserInstalled = new boolean[allUsers.length];
            for (int i = 0; i < allUsers.length; i++) {
                perUserInstalled[i] = ps != null ? ps.getInstalled(allUsers[i]) : false;
            }
        }

        boolean sysPkg = (isSystemApp(oldPackage));
        if (sysPkg) {
            replaceSystemPackageLI(oldPackage, pkg, parseFlags, scanFlags,
                    user, allUsers, perUserInstalled, installerPackageName, volumeUuid, res);
        } else {
            replaceNonSystemPackageLI(oldPackage, pkg, parseFlags, scanFlags,
                    user, allUsers, perUserInstalled, installerPackageName, volumeUuid, res);
        }
    }

    private void replaceNonSystemPackageLI(PackageParser.Package deletedPackage,
            PackageParser.Package pkg, int parseFlags, int scanFlags, UserHandle user,
            int[] allUsers, boolean[] perUserInstalled, String installerPackageName,
            String volumeUuid, PackageInstalledInfo res) {
        String pkgName = deletedPackage.packageName;
        boolean deletedPkg = true;
        boolean updatedSettings = false;

        if (DEBUG_INSTALL) Slog.d(TAG, "replaceNonSystemPackageLI: new=" + pkg + ", old="
                + deletedPackage);
        long origUpdateTime;
        if (pkg.mExtras != null) {
            origUpdateTime = ((PackageSetting)pkg.mExtras).lastUpdateTime;
        } else {
            origUpdateTime = 0;
        }

        // First delete the existing package while retaining the data directory
        if (!deletePackageLI(pkgName, null, true, null, null, PackageManager.DELETE_KEEP_DATA,
                res.removedInfo, true)) {
            // If the existing package wasn't successfully deleted
            res.setError(INSTALL_FAILED_REPLACE_COULDNT_DELETE, "replaceNonSystemPackageLI");
            deletedPkg = false;
        } else {
            // Successfully deleted the old package; proceed with replace.

            // If deleted package lived in a container, give users a chance to
            // relinquish resources before killing.
            if (deletedPackage.isForwardLocked() || isExternal(deletedPackage)) {
                if (DEBUG_INSTALL) {
                    Slog.i(TAG, "upgrading pkg " + deletedPackage + " is ASEC-hosted -> UNAVAILABLE");
                }
                final int[] uidArray = new int[] { deletedPackage.applicationInfo.uid };
                final ArrayList<String> pkgList = new ArrayList<String>(1);
                pkgList.add(deletedPackage.applicationInfo.packageName);
                sendResourcesChangedBroadcast(false, true, pkgList, uidArray, null);
            }

            deleteCodeCacheDirsLI(pkg.volumeUuid, pkgName);
            try {
                final PackageParser.Package newPackage = scanPackageLI(pkg, parseFlags,
                        scanFlags | SCAN_UPDATE_TIME, System.currentTimeMillis(), user);
                updateSettingsLI(newPackage, installerPackageName, volumeUuid, allUsers,
                        perUserInstalled, res, user);
                updatedSettings = true;
            } catch (PackageManagerException e) {
                res.setError("Package couldn't be installed in " + pkg.codePath, e);
            }
        }

        if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
            // remove package from internal structures.  Note that we want deletePackageX to
            // delete the package data and cache directories that it created in
            // scanPackageLocked, unless those directories existed before we even tried to
            // install.
            if(updatedSettings) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Install failed, rolling pack: " + pkgName);
                deletePackageLI(
                        pkgName, null, true, allUsers, perUserInstalled,
                        PackageManager.DELETE_KEEP_DATA,
                                res.removedInfo, true);
            }
            // Since we failed to install the new package we need to restore the old
            // package that we deleted.
            if (deletedPkg) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Install failed, reinstalling: " + deletedPackage);
                File restoreFile = new File(deletedPackage.codePath);
                // Parse old package
                boolean oldExternal = isExternal(deletedPackage);
                int oldParseFlags  = mDefParseFlags | PackageParser.PARSE_CHATTY |
                        (deletedPackage.isForwardLocked() ? PackageParser.PARSE_FORWARD_LOCK : 0) |
                        (oldExternal ? PackageParser.PARSE_EXTERNAL_STORAGE : 0);
                int oldScanFlags = SCAN_UPDATE_SIGNATURE | SCAN_UPDATE_TIME;
                try {
                    scanPackageLI(restoreFile, oldParseFlags, oldScanFlags, origUpdateTime, null);
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "Failed to restore package : " + pkgName + " after failed upgrade: "
                            + e.getMessage());
                    return;
                }
                // Restore of old package succeeded. Update permissions.
                // writer
                synchronized (mPackages) {
                    updatePermissionsLPw(deletedPackage.packageName, deletedPackage,
                            UPDATE_PERMISSIONS_ALL);
                    // can downgrade to reader
                    mSettings.writeLPr();
                }
                Slog.i(TAG, "Successfully restored package : " + pkgName + " after failed upgrade");
            }
        }
    }

    private void replaceSystemPackageLI(PackageParser.Package deletedPackage,
            PackageParser.Package pkg, int parseFlags, int scanFlags, UserHandle user,
            int[] allUsers, boolean[] perUserInstalled, String installerPackageName,
            String volumeUuid, PackageInstalledInfo res) {
        if (DEBUG_INSTALL) Slog.d(TAG, "replaceSystemPackageLI: new=" + pkg
                + ", old=" + deletedPackage);
        boolean disabledSystem = false;
        boolean updatedSettings = false;
        parseFlags |= PackageParser.PARSE_IS_SYSTEM;
        if ((deletedPackage.applicationInfo.privateFlags&ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                != 0) {
            parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
        }
        String packageName = deletedPackage.packageName;
        if (packageName == null) {
            res.setError(INSTALL_FAILED_REPLACE_COULDNT_DELETE,
                    "Attempt to delete null packageName.");
            return;
        }
        PackageParser.Package oldPkg;
        PackageSetting oldPkgSetting;
        // reader
        synchronized (mPackages) {
            oldPkg = mPackages.get(packageName);
            oldPkgSetting = mSettings.mPackages.get(packageName);
            if((oldPkg == null) || (oldPkg.applicationInfo == null) ||
                    (oldPkgSetting == null)) {
                res.setError(INSTALL_FAILED_REPLACE_COULDNT_DELETE,
                        "Couldn't find package:" + packageName + " information");
                return;
            }
        }

        killApplication(packageName, oldPkg.applicationInfo.uid, "replace sys pkg");

        res.removedInfo.uid = oldPkg.applicationInfo.uid;
        res.removedInfo.removedPackage = packageName;
        // Remove existing system package
        removePackageLI(oldPkgSetting, true);
        // writer
        synchronized (mPackages) {
            disabledSystem = mSettings.disableSystemPackageLPw(packageName);
            if (!disabledSystem && deletedPackage != null) {
                // We didn't need to disable the .apk as a current system package,
                // which means we are replacing another update that is already
                // installed.  We need to make sure to delete the older one's .apk.
                res.removedInfo.args = createInstallArgsForExisting(0,
                        deletedPackage.applicationInfo.getCodePath(),
                        deletedPackage.applicationInfo.getResourcePath(),
                        getAppDexInstructionSets(deletedPackage.applicationInfo));
            } else {
                res.removedInfo.args = null;
            }
        }

        // Successfully disabled the old package. Now proceed with re-installation
        deleteCodeCacheDirsLI(pkg.volumeUuid, packageName);

        res.returnCode = PackageManager.INSTALL_SUCCEEDED;
        pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;

        PackageParser.Package newPackage = null;
        try {
            newPackage = scanPackageLI(pkg, parseFlags, scanFlags, 0, user);
            if (newPackage.mExtras != null) {
                final PackageSetting newPkgSetting = (PackageSetting) newPackage.mExtras;
                newPkgSetting.firstInstallTime = oldPkgSetting.firstInstallTime;
                newPkgSetting.lastUpdateTime = System.currentTimeMillis();

                // is the update attempting to change shared user? that isn't going to work...
                if (oldPkgSetting.sharedUser != newPkgSetting.sharedUser) {
                    res.setError(INSTALL_FAILED_SHARED_USER_INCOMPATIBLE,
                            "Forbidding shared user change from " + oldPkgSetting.sharedUser
                            + " to " + newPkgSetting.sharedUser);
                    updatedSettings = true;
                }
            }

            if (res.returnCode == PackageManager.INSTALL_SUCCEEDED) {
                updateSettingsLI(newPackage, installerPackageName, volumeUuid, allUsers,
                        perUserInstalled, res, user);
                updatedSettings = true;
            }

        } catch (PackageManagerException e) {
            res.setError("Package couldn't be installed in " + pkg.codePath, e);
        }

        if (res.returnCode != PackageManager.INSTALL_SUCCEEDED) {
            // Re installation failed. Restore old information
            // Remove new pkg information
            if (newPackage != null) {
                removeInstalledPackageLI(newPackage, true);
            }
            // Add back the old system package
            try {
                scanPackageLI(oldPkg, parseFlags, SCAN_UPDATE_SIGNATURE, 0, user);
            } catch (PackageManagerException e) {
                Slog.e(TAG, "Failed to restore original package: " + e.getMessage());
            }
            // Restore the old system information in Settings
            synchronized (mPackages) {
                if (disabledSystem) {
                    mSettings.enableSystemPackageLPw(packageName);
                }
                if (updatedSettings) {
                    mSettings.setInstallerPackageName(packageName,
                            oldPkgSetting.installerPackageName);
                }
                mSettings.writeLPr();
            }
        }
    }

    private void updateSettingsLI(PackageParser.Package newPackage, String installerPackageName,
            String volumeUuid, int[] allUsers, boolean[] perUserInstalled, PackageInstalledInfo res,
            UserHandle user) {
        String pkgName = newPackage.packageName;
        synchronized (mPackages) {
            //write settings. the installStatus will be incomplete at this stage.
            //note that the new package setting would have already been
            //added to mPackages. It hasn't been persisted yet.
            mSettings.setInstallStatus(pkgName, PackageSettingBase.PKG_INSTALL_INCOMPLETE);
            mSettings.writeLPr();
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "New package installed in " + newPackage.codePath);

        synchronized (mPackages) {
            updatePermissionsLPw(newPackage.packageName, newPackage,
                    UPDATE_PERMISSIONS_REPLACE_PKG | (newPackage.permissions.size() > 0
                            ? UPDATE_PERMISSIONS_ALL : 0));
            // For system-bundled packages, we assume that installing an upgraded version
            // of the package implies that the user actually wants to run that new code,
            // so we enable the package.
            PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                if (isSystemApp(newPackage)) {
                    // NB: implicit assumption that system package upgrades apply to all users
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Implicitly enabling system package on upgrade: " + pkgName);
                    }
                    if (res.origUsers != null) {
                        for (int userHandle : res.origUsers) {
                            ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT,
                                    userHandle, installerPackageName);
                        }
                    }
                    // Also convey the prior install/uninstall state
                    if (allUsers != null && perUserInstalled != null) {
                        for (int i = 0; i < allUsers.length; i++) {
                            if (DEBUG_INSTALL) {
                                Slog.d(TAG, "    user " + allUsers[i]
                                        + " => " + perUserInstalled[i]);
                            }
                            ps.setInstalled(perUserInstalled[i], allUsers[i]);
                        }
                        // these install state changes will be persisted in the
                        // upcoming call to mSettings.writeLPr().
                    }
                }
                // It's implied that when a user requests installation, they want the app to be
                // installed and enabled.
                int userId = user.getIdentifier();
                if (userId != UserHandle.USER_ALL) {
                    ps.setInstalled(true, userId);
                    ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, userId, installerPackageName);
                }
            }
            res.name = pkgName;
            res.uid = newPackage.applicationInfo.uid;
            res.pkg = newPackage;
            mSettings.setInstallStatus(pkgName, PackageSettingBase.PKG_INSTALL_COMPLETE);
            mSettings.setInstallerPackageName(pkgName, installerPackageName);
            res.returnCode = PackageManager.INSTALL_SUCCEEDED;
            //to update install status
            mSettings.writeLPr();
        }
    }

    private void installPackageLI(InstallArgs args, PackageInstalledInfo res) {
        final int installFlags = args.installFlags;
        final String installerPackageName = args.installerPackageName;
        final String volumeUuid = args.volumeUuid;
        final File tmpPackageFile = new File(args.getCodePath());
        final boolean forwardLocked = ((installFlags & PackageManager.INSTALL_FORWARD_LOCK) != 0);
        final boolean onExternal = (((installFlags & PackageManager.INSTALL_EXTERNAL) != 0)
                || (args.volumeUuid != null));
        boolean replace = false;
        int scanFlags = SCAN_NEW_INSTALL | SCAN_UPDATE_SIGNATURE;
        if (args.move != null) {
            // moving a complete application; perfom an initial scan on the new install location
            scanFlags |= SCAN_INITIAL;
        }
        // Result object to be returned
        res.returnCode = PackageManager.INSTALL_SUCCEEDED;

        if (DEBUG_INSTALL) Slog.d(TAG, "installPackageLI: path=" + tmpPackageFile);
        // Retrieve PackageSettings and parse package
        final int parseFlags = mDefParseFlags | PackageParser.PARSE_CHATTY
                | (forwardLocked ? PackageParser.PARSE_FORWARD_LOCK : 0)
                | (onExternal ? PackageParser.PARSE_EXTERNAL_STORAGE : 0);
        PackageParser pp = new PackageParser();
        pp.setSeparateProcesses(mSeparateProcesses);
        pp.setDisplayMetrics(mMetrics);

        final PackageParser.Package pkg;
        try {
            pkg = pp.parsePackage(tmpPackageFile, parseFlags);
        } catch (PackageParserException e) {
            res.setError("Failed parse during installPackageLI", e);
            return;
        }

        // Mark that we have an install time CPU ABI override.
        pkg.cpuAbiOverride = args.abiOverride;

        String pkgName = res.name = pkg.packageName;
        if ((pkg.applicationInfo.flags&ApplicationInfo.FLAG_TEST_ONLY) != 0) {
            if ((installFlags & PackageManager.INSTALL_ALLOW_TEST) == 0) {
                res.setError(INSTALL_FAILED_TEST_ONLY, "installPackageLI");
                return;
            }
        }

        try {
            pp.collectCertificates(pkg, parseFlags);
            pp.collectManifestDigest(pkg);
        } catch (PackageParserException e) {
            res.setError("Failed collect during installPackageLI", e);
            return;
        }

        /* If the installer passed in a manifest digest, compare it now. */
        if (args.manifestDigest != null) {
            if (DEBUG_INSTALL) {
                final String parsedManifest = pkg.manifestDigest == null ? "null"
                        : pkg.manifestDigest.toString();
                Slog.d(TAG, "Comparing manifests: " + args.manifestDigest.toString() + " vs. "
                        + parsedManifest);
            }

            if (!args.manifestDigest.equals(pkg.manifestDigest)) {
                res.setError(INSTALL_FAILED_PACKAGE_CHANGED, "Manifest digest changed");
                return;
            }
        } else if (DEBUG_INSTALL) {
            final String parsedManifest = pkg.manifestDigest == null
                    ? "null" : pkg.manifestDigest.toString();
            Slog.d(TAG, "manifestDigest was not present, but parser got: " + parsedManifest);
        }

        // Get rid of all references to package scan path via parser.
        pp = null;
        String oldCodePath = null;
        boolean systemApp = false;
        synchronized (mPackages) {
            // Check if installing already existing package
            if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                String oldName = mSettings.mRenamedPackages.get(pkgName);
                if (pkg.mOriginalPackages != null
                        && pkg.mOriginalPackages.contains(oldName)
                        && mPackages.containsKey(oldName)) {
                    // This package is derived from an original package,
                    // and this device has been updating from that original
                    // name.  We must continue using the original name, so
                    // rename the new package here.
                    pkg.setPackageName(oldName);
                    pkgName = pkg.packageName;
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replacing existing renamed package: oldName="
                            + oldName + " pkgName=" + pkgName);
                } else if (mPackages.containsKey(pkgName)) {
                    // This package, under its official name, already exists
                    // on the device; we should replace it.
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replace existing pacakge: " + pkgName);
                }

                // Prevent apps opting out from runtime permissions
                if (replace) {
                    PackageParser.Package oldPackage = mPackages.get(pkgName);
                    final int oldTargetSdk = oldPackage.applicationInfo.targetSdkVersion;
                    final int newTargetSdk = pkg.applicationInfo.targetSdkVersion;
                    if (oldTargetSdk > Build.VERSION_CODES.LOLLIPOP_MR1
                            && newTargetSdk <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        res.setError(PackageManager.INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE,
                                "Package " + pkg.packageName + " new target SDK " + newTargetSdk
                                        + " doesn't support runtime permissions but the old"
                                        + " target SDK " + oldTargetSdk + " does.");
                        return;
                    }
                }
            }

            PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Existing package: " + ps);

                // Quick sanity check that we're signed correctly if updating;
                // we'll check this again later when scanning, but we want to
                // bail early here before tripping over redefined permissions.
                if (shouldCheckUpgradeKeySetLP(ps, scanFlags)) {
                    if (!checkUpgradeKeySetLP(ps, pkg)) {
                        res.setError(INSTALL_FAILED_UPDATE_INCOMPATIBLE, "Package "
                                + pkg.packageName + " upgrade keys do not match the "
                                + "previously installed version");
                        return;
                    }
                } else {
                    try {
                        verifySignaturesLP(ps, pkg);
                    } catch (PackageManagerException e) {
                        res.setError(e.error, e.getMessage());
                        return;
                    }
                }

                oldCodePath = mSettings.mPackages.get(pkgName).codePathString;
                if (ps.pkg != null && ps.pkg.applicationInfo != null) {
                    systemApp = (ps.pkg.applicationInfo.flags &
                            ApplicationInfo.FLAG_SYSTEM) != 0;
                }
                res.origUsers = ps.queryInstalledUsers(sUserManager.getUserIds(), true);
            }

            // Check whether the newly-scanned package wants to define an already-defined perm
            int N = pkg.permissions.size();
            for (int i = N-1; i >= 0; i--) {
                PackageParser.Permission perm = pkg.permissions.get(i);
                BasePermission bp = mSettings.mPermissions.get(perm.info.name);
                if (bp != null) {
                    // If the defining package is signed with our cert, it's okay.  This
                    // also includes the "updating the same package" case, of course.
                    // "updating same package" could also involve key-rotation.
                    final boolean sigsOk;
                    if (bp.sourcePackage.equals(pkg.packageName)
                            && (bp.packageSetting instanceof PackageSetting)
                            && (shouldCheckUpgradeKeySetLP((PackageSetting) bp.packageSetting,
                                    scanFlags))) {
                        sigsOk = checkUpgradeKeySetLP((PackageSetting) bp.packageSetting, pkg);
                    } else {
                        sigsOk = compareSignatures(bp.packageSetting.signatures.mSignatures,
                                pkg.mSignatures) == PackageManager.SIGNATURE_MATCH;
                    }
                    if (!sigsOk) {
                        // If the owning package is the system itself, we log but allow
                        // install to proceed; we fail the install on all other permission
                        // redefinitions.
                        if (!bp.sourcePackage.equals("android")) {
                            res.setError(INSTALL_FAILED_DUPLICATE_PERMISSION, "Package "
                                    + pkg.packageName + " attempting to redeclare permission "
                                    + perm.info.name + " already owned by " + bp.sourcePackage);
                            res.origPermission = perm.info.name;
                            res.origPackage = bp.sourcePackage;
                            return;
                        } else {
                            Slog.w(TAG, "Package " + pkg.packageName
                                    + " attempting to redeclare system permission "
                                    + perm.info.name + "; ignoring new declaration");
                            pkg.permissions.remove(i);
                        }
                    }
                }
            }

        }

        if (systemApp && onExternal) {
            // Disable updates to system apps on sdcard
            res.setError(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                    "Cannot install updates to system apps on sdcard");
            return;
        }

        if (args.move != null) {
            // We did an in-place move, so dex is ready to roll
            scanFlags |= SCAN_NO_DEX;
            scanFlags |= SCAN_MOVE;

            synchronized (mPackages) {
                final PackageSetting ps = mSettings.mPackages.get(pkgName);
                if (ps == null) {
                    res.setError(INSTALL_FAILED_INTERNAL_ERROR,
                            "Missing settings for moved package " + pkgName);
                }

                // We moved the entire application as-is, so bring over the
                // previously derived ABI information.
                pkg.applicationInfo.primaryCpuAbi = ps.primaryCpuAbiString;
                pkg.applicationInfo.secondaryCpuAbi = ps.secondaryCpuAbiString;
            }

        } else if (!forwardLocked && !pkg.applicationInfo.isExternalAsec()) {
            // Enable SCAN_NO_DEX flag to skip dexopt at a later stage
            scanFlags |= SCAN_NO_DEX;

            try {
                derivePackageAbi(pkg, new File(pkg.codePath), args.abiOverride,
                        true /* extract libs */);
            } catch (PackageManagerException pme) {
                Slog.e(TAG, "Error deriving application ABI", pme);
                res.setError(INSTALL_FAILED_INTERNAL_ERROR, "Error deriving application ABI");
                return;
            }

            // Run dexopt before old package gets removed, to minimize time when app is unavailable
            int result = mPackageDexOptimizer
                    .performDexOpt(pkg, null /* instruction sets */, false /* forceDex */,
                            false /* defer */, false /* inclDependencies */);
            if (result == PackageDexOptimizer.DEX_OPT_FAILED) {
                res.setError(INSTALL_FAILED_DEXOPT, "Dexopt failed for " + pkg.codePath);
                return;
            }
        }

        if (!args.doRename(res.returnCode, pkg, oldCodePath)) {
            res.setError(INSTALL_FAILED_INSUFFICIENT_STORAGE, "Failed rename");
            return;
        }

        startIntentFilterVerifications(args.user.getIdentifier(), replace, pkg);

        if (replace) {
            replacePackageLI(pkg, parseFlags, scanFlags | SCAN_REPLACING, args.user,
                    installerPackageName, volumeUuid, res);
        } else {
            installNewPackageLI(pkg, parseFlags, scanFlags | SCAN_DELETE_DATA_ON_FAILURES,
                    args.user, installerPackageName, volumeUuid, res);
        }
        synchronized (mPackages) {
            final PackageSetting ps = mSettings.mPackages.get(pkgName);
            if (ps != null) {
                res.newUsers = ps.queryInstalledUsers(sUserManager.getUserIds(), true);
            }
        }
    }

    private void startIntentFilterVerifications(int userId, boolean replacing,
            PackageParser.Package pkg) {
        if (mIntentFilterVerifierComponent == null) {
            Slog.w(TAG, "No IntentFilter verification will not be done as "
                    + "there is no IntentFilterVerifier available!");
            return;
        }

        final int verifierUid = getPackageUid(
                mIntentFilterVerifierComponent.getPackageName(),
                (userId == UserHandle.USER_ALL) ? UserHandle.USER_OWNER : userId);

        mHandler.removeMessages(START_INTENT_FILTER_VERIFICATIONS);
        final Message msg = mHandler.obtainMessage(START_INTENT_FILTER_VERIFICATIONS);
        msg.obj = new IFVerificationParams(pkg, replacing, userId, verifierUid);
        mHandler.sendMessage(msg);
    }

    private void verifyIntentFiltersIfNeeded(int userId, int verifierUid, boolean replacing,
            PackageParser.Package pkg) {
        int size = pkg.activities.size();
        if (size == 0) {
            if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                    "No activity, so no need to verify any IntentFilter!");
            return;
        }

        final boolean hasDomainURLs = hasDomainURLs(pkg);
        if (!hasDomainURLs) {
            if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                    "No domain URLs, so no need to verify any IntentFilter!");
            return;
        }

        if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG, "Checking for userId:" + userId
                + " if any IntentFilter from the " + size
                + " Activities needs verification ...");

        int count = 0;
        final String packageName = pkg.packageName;

        synchronized (mPackages) {
            // If this is a new install and we see that we've already run verification for this
            // package, we have nothing to do: it means the state was restored from backup.
            if (!replacing) {
                IntentFilterVerificationInfo ivi =
                        mSettings.getIntentFilterVerificationLPr(packageName);
                if (ivi != null) {
                    if (DEBUG_DOMAIN_VERIFICATION) {
                        Slog.i(TAG, "Package " + packageName+ " already verified: status="
                                + ivi.getStatusString());
                    }
                    return;
                }
            }

            // If any filters need to be verified, then all need to be.
            boolean needToVerify = false;
            for (PackageParser.Activity a : pkg.activities) {
                for (ActivityIntentInfo filter : a.intents) {
                    if (filter.needsVerification() && needsNetworkVerificationLPr(filter)) {
                        if (DEBUG_DOMAIN_VERIFICATION) {
                            Slog.d(TAG, "Intent filter needs verification, so processing all filters");
                        }
                        needToVerify = true;
                        break;
                    }
                }
            }

            if (needToVerify) {
                final int verificationId = mIntentFilterVerificationToken++;
                for (PackageParser.Activity a : pkg.activities) {
                    for (ActivityIntentInfo filter : a.intents) {
                        if (filter.handlesWebUris(true) && needsNetworkVerificationLPr(filter)) {
                            if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG,
                                    "Verification needed for IntentFilter:" + filter.toString());
                            mIntentFilterVerifier.addOneIntentFilterVerification(
                                    verifierUid, userId, verificationId, filter, packageName);
                            count++;
                        }
                    }
                }
            }
        }

        if (count > 0) {
            if (DEBUG_DOMAIN_VERIFICATION) Slog.d(TAG, "Starting " + count
                    + " IntentFilter verification" + (count > 1 ? "s" : "")
                    +  " for userId:" + userId);
            mIntentFilterVerifier.startVerifications(userId);
        } else {
            if (DEBUG_DOMAIN_VERIFICATION) {
                Slog.d(TAG, "No filters or not all autoVerify for " + packageName);
            }
        }
    }

    private boolean needsNetworkVerificationLPr(ActivityIntentInfo filter) {
        final ComponentName cn  = filter.activity.getComponentName();
        final String packageName = cn.getPackageName();

        IntentFilterVerificationInfo ivi = mSettings.getIntentFilterVerificationLPr(
                packageName);
        if (ivi == null) {
            return true;
        }
        int status = ivi.getStatus();
        switch (status) {
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED:
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK:
                return true;

            default:
                // Nothing to do
                return false;
        }
    }

    private static boolean isMultiArch(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_MULTIARCH) != 0;
    }

    private static boolean isMultiArch(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_MULTIARCH) != 0;
    }

    private static boolean isExternal(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    private static boolean isExternal(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    private static boolean isExternal(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    private static boolean isSystemApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static boolean isPrivilegedApp(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    private static boolean hasDomainURLs(PackageParser.Package pkg) {
        return (pkg.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS) != 0;
    }

    private static boolean isSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static boolean isUpdatedSystemApp(PackageSetting ps) {
        return (ps.pkgFlags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    private int packageFlagsToInstallFlags(PackageSetting ps) {
        int installFlags = 0;
        if (isExternal(ps) && TextUtils.isEmpty(ps.volumeUuid)) {
            // This existing package was an external ASEC install when we have
            // the external flag without a UUID
            installFlags |= PackageManager.INSTALL_EXTERNAL;
        }
        if (ps.isForwardLocked()) {
            installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
        }
        return installFlags;
    }

    private VersionInfo getSettingsVersionForPackage(PackageParser.Package pkg) {
        if (isExternal(pkg)) {
            if (TextUtils.isEmpty(pkg.volumeUuid)) {
                return mSettings.getExternalVersion();
            } else {
                return mSettings.findOrCreateVersion(pkg.volumeUuid);
            }
        } else {
            return mSettings.getInternalVersion();
        }
    }

    private void deleteTempPackageFiles() {
        final FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("vmdl") && name.endsWith(".tmp");
            }
        };
        for (File file : mDrmAppPrivateInstallDir.listFiles(filter)) {
            file.delete();
        }
    }

    @Override
    public void deletePackageAsUser(String packageName, IPackageDeleteObserver observer, int userId,
            int flags) {
        deletePackage(packageName, new LegacyPackageDeleteObserver(observer).getBinder(), userId,
                flags);
    }

    @Override
    public void deletePackage(final String packageName,
            final IPackageDeleteObserver2 observer, final int userId, final int flags) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        Preconditions.checkNotNull(packageName);
        Preconditions.checkNotNull(observer);
        final int uid = Binder.getCallingUid();
        if (UserHandle.getUserId(uid) != userId) {
            mContext.enforceCallingPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "deletePackage for user " + userId);
        }
        if (isUserRestricted(userId, UserManager.DISALLOW_UNINSTALL_APPS)) {
            try {
                observer.onPackageDeleted(packageName,
                        PackageManager.DELETE_FAILED_USER_RESTRICTED, null);
            } catch (RemoteException re) {
            }
            return;
        }

        boolean uninstallBlocked = false;
        if ((flags & PackageManager.DELETE_ALL_USERS) != 0) {
            int[] users = sUserManager.getUserIds();
            for (int i = 0; i < users.length; ++i) {
                if (getBlockUninstallForUser(packageName, users[i])) {
                    uninstallBlocked = true;
                    break;
                }
            }
        } else {
            uninstallBlocked = getBlockUninstallForUser(packageName, userId);
        }
        if (uninstallBlocked) {
            try {
                observer.onPackageDeleted(packageName, PackageManager.DELETE_FAILED_OWNER_BLOCKED,
                        null);
            } catch (RemoteException re) {
            }
            return;
        }

        if (DEBUG_REMOVE) {
            Slog.d(TAG, "deletePackageAsUser: pkg=" + packageName + " user=" + userId);
        }
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final int returnCode = deletePackageX(packageName, userId, flags);
                if (observer != null) {
                    try {
                        observer.onPackageDeleted(packageName, returnCode, null);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    } //end catch
                } //end if
            } //end run
        });
    }

    private boolean isPackageDeviceAdmin(String packageName, int userId) {
        IDevicePolicyManager dpm = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
        try {
            if (dpm != null) {
                if (dpm.isDeviceOwner(packageName)) {
                    return true;
                }
                int[] users;
                if (userId == UserHandle.USER_ALL) {
                    users = sUserManager.getUserIds();
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

    /**
     *  This method is an internal method that could be get invoked either
     *  to delete an installed package or to clean up a failed installation.
     *  After deleting an installed package, a broadcast is sent to notify any
     *  listeners that the package has been installed. For cleaning up a failed
     *  installation, the broadcast is not necessary since the package's
     *  installation wouldn't have sent the initial broadcast either
     *  The key steps in deleting a package are
     *  deleting the package information in internal structures like mPackages,
     *  deleting the packages base directories through installd
     *  updating mSettings to reflect current status
     *  persisting settings for later use
     *  sending a broadcast if necessary
     */
    private int deletePackageX(String packageName, int userId, int flags) {
        final PackageRemovedInfo info = new PackageRemovedInfo();
        final boolean res;

        final UserHandle removeForUser = (flags & PackageManager.DELETE_ALL_USERS) != 0
                ? UserHandle.ALL : new UserHandle(userId);

        if (isPackageDeviceAdmin(packageName, removeForUser.getIdentifier())) {
            Slog.w(TAG, "Not removing package " + packageName + ": has active device admin");
            return PackageManager.DELETE_FAILED_DEVICE_POLICY_MANAGER;
        }

        boolean removedForAllUsers = false;
        boolean systemUpdate = false;

        // for the uninstall-updates case and restricted profiles, remember the per-
        // userhandle installed state
        int[] allUsers;
        boolean[] perUserInstalled;
        synchronized (mPackages) {
            PackageSetting ps = mSettings.mPackages.get(packageName);
            allUsers = sUserManager.getUserIds();
            perUserInstalled = new boolean[allUsers.length];
            for (int i = 0; i < allUsers.length; i++) {
                perUserInstalled[i] = ps != null ? ps.getInstalled(allUsers[i]) : false;
            }
        }

        synchronized (mInstallLock) {
            if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageX: pkg=" + packageName + " user=" + userId);
            res = deletePackageLI(packageName, removeForUser,
                    true, allUsers, perUserInstalled,
                    flags | REMOVE_CHATTY, info, true);
            systemUpdate = info.isRemovedPackageSystemUpdate;
            if (res && !systemUpdate && mPackages.get(packageName) == null) {
                removedForAllUsers = true;
            }
            if (DEBUG_REMOVE) Slog.d(TAG, "delete res: systemUpdate=" + systemUpdate
                    + " removedForAllUsers=" + removedForAllUsers);
        }

        if (res) {
            info.sendBroadcast(true, systemUpdate, removedForAllUsers);

            // If the removed package was a system update, the old system package
            // was re-enabled; we need to broadcast this information
            if (systemUpdate) {
                Bundle extras = new Bundle(1);
                extras.putInt(Intent.EXTRA_UID, info.removedAppId >= 0
                        ? info.removedAppId : info.uid);
                extras.putBoolean(Intent.EXTRA_REPLACING, true);

                sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, packageName,
                        extras, null, null, null);
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, packageName,
                        extras, null, null, null);
                sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED, null,
                        null, packageName, null, null);
            }
        }
        // Force a gc here.
        Runtime.getRuntime().gc();
        // Delete the resources here after sending the broadcast to let
        // other processes clean up before deleting resources.
        if (info.args != null) {
            synchronized (mInstallLock) {
                info.args.doPostDeleteLI(true);
            }
        }

        return res ? PackageManager.DELETE_SUCCEEDED : PackageManager.DELETE_FAILED_INTERNAL_ERROR;
    }

    class PackageRemovedInfo {
        String removedPackage;
        int uid = -1;
        int removedAppId = -1;
        int[] removedUsers = null;
        boolean isRemovedPackageSystemUpdate = false;
        // Clean up resources deleted packages.
        InstallArgs args = null;

        void sendBroadcast(boolean fullRemove, boolean replacing, boolean removedForAllUsers) {
            Bundle extras = new Bundle(1);
            extras.putInt(Intent.EXTRA_UID, removedAppId >= 0 ? removedAppId : uid);
            extras.putBoolean(Intent.EXTRA_DATA_REMOVED, fullRemove);
            if (replacing) {
                extras.putBoolean(Intent.EXTRA_REPLACING, true);
            }
            extras.putBoolean(Intent.EXTRA_REMOVED_FOR_ALL_USERS, removedForAllUsers);
            if (removedPackage != null) {
                sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, removedPackage,
                        extras, null, null, removedUsers);
                if (fullRemove && !replacing) {
                    sendPackageBroadcast(Intent.ACTION_PACKAGE_FULLY_REMOVED, removedPackage,
                            extras, null, null, removedUsers);
                }
            }
            if (removedAppId >= 0) {
                sendPackageBroadcast(Intent.ACTION_UID_REMOVED, null, extras, null, null,
                        removedUsers);
            }
        }
    }

    /*
     * This method deletes the package from internal data structures. If the DONT_DELETE_DATA
     * flag is not set, the data directory is removed as well.
     * make sure this flag is set for partially installed apps. If not its meaningless to
     * delete a partially installed application.
     */
    private void removePackageDataLI(PackageSetting ps,
            int[] allUserHandles, boolean[] perUserInstalled,
            PackageRemovedInfo outInfo, int flags, boolean writeSettings) {
        String packageName = ps.name;
        if (DEBUG_REMOVE) Slog.d(TAG, "removePackageDataLI: " + ps);
        removePackageLI(ps, (flags&REMOVE_CHATTY) != 0);
        // Retrieve object to delete permissions for shared user later on
        final PackageSetting deletedPs;
        // reader
        synchronized (mPackages) {
            deletedPs = mSettings.mPackages.get(packageName);
            if (outInfo != null) {
                outInfo.removedPackage = packageName;
                outInfo.removedUsers = deletedPs != null
                        ? deletedPs.queryInstalledUsers(sUserManager.getUserIds(), true)
                        : null;
            }
        }
        if ((flags&PackageManager.DELETE_KEEP_DATA) == 0) {
            removeDataDirsLI(ps.volumeUuid, packageName);
            schedulePackageCleaning(packageName, UserHandle.USER_ALL, true);
        }
        // writer
        synchronized (mPackages) {
            if (deletedPs != null) {
                if ((flags&PackageManager.DELETE_KEEP_DATA) == 0) {
                    clearIntentFilterVerificationsLPw(deletedPs.name, UserHandle.USER_ALL);
                    clearDefaultBrowserIfNeeded(packageName);
                    if (outInfo != null) {
                        mSettings.mKeySetManagerService.removeAppKeySetDataLPw(packageName);
                        outInfo.removedAppId = mSettings.removePackageLPw(packageName);
                    }
                    updatePermissionsLPw(deletedPs.name, null, 0);
                    if (deletedPs.sharedUser != null) {
                        // Remove permissions associated with package. Since runtime
                        // permissions are per user we have to kill the removed package
                        // or packages running under the shared user of the removed
                        // package if revoking the permissions requested only by the removed
                        // package is successful and this causes a change in gids.
                        for (int userId : UserManagerService.getInstance().getUserIds()) {
                            final int userIdToKill = mSettings.updateSharedUserPermsLPw(deletedPs,
                                    userId);
                            if (userIdToKill == UserHandle.USER_ALL
                                    || userIdToKill >= UserHandle.USER_OWNER) {
                                // If gids changed for this user, kill all affected packages.
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // This has to happen with no lock held.
                                        killApplication(deletedPs.name, deletedPs.appId,
                                                KILL_APP_REASON_GIDS_CHANGED);
                                    }
                                });
                                break;
                            }
                        }
                    }
                    clearPackagePreferredActivitiesLPw(deletedPs.name, UserHandle.USER_ALL);
                }
                // make sure to preserve per-user disabled state if this removal was just
                // a downgrade of a system app to the factory package
                if (allUserHandles != null && perUserInstalled != null) {
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "Propagating install state across downgrade");
                    }
                    for (int i = 0; i < allUserHandles.length; i++) {
                        if (DEBUG_REMOVE) {
                            Slog.d(TAG, "    user " + allUserHandles[i]
                                    + " => " + perUserInstalled[i]);
                        }
                        ps.setInstalled(perUserInstalled[i], allUserHandles[i]);
                    }
                }
            }
            // can downgrade to reader
            if (writeSettings) {
                // Save settings now
                mSettings.writeLPr();
            }
        }
        if (outInfo != null) {
            // A user ID was deleted here. Go through all users and remove it
            // from KeyStore.
            removeKeystoreDataIfNeeded(UserHandle.USER_ALL, outInfo.removedAppId);
        }
    }

    static boolean locationIsPrivileged(File path) {
        try {
            final String privilegedAppDir = new File(Environment.getRootDirectory(), "priv-app")
                    .getCanonicalPath();
            return path.getCanonicalPath().startsWith(privilegedAppDir);
        } catch (IOException e) {
            Slog.e(TAG, "Unable to access code path " + path);
        }
        return false;
    }

    /*
     * Tries to delete system package.
     */
    private boolean deleteSystemPackageLI(PackageSetting newPs,
            int[] allUserHandles, boolean[] perUserInstalled,
            int flags, PackageRemovedInfo outInfo, boolean writeSettings) {
        final boolean applyUserRestrictions
                = (allUserHandles != null) && (perUserInstalled != null);
        PackageSetting disabledPs = null;
        // Confirm if the system package has been updated
        // An updated system app can be deleted. This will also have to restore
        // the system pkg from system partition
        // reader
        synchronized (mPackages) {
            disabledPs = mSettings.getDisabledSystemPkgLPr(newPs.name);
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deleteSystemPackageLI: newPs=" + newPs
                + " disabledPs=" + disabledPs);
        if (disabledPs == null) {
            Slog.w(TAG, "Attempt to delete unknown system package "+ newPs.name);
            return false;
        } else if (DEBUG_REMOVE) {
            Slog.d(TAG, "Deleting system pkg from data partition");
        }
        if (DEBUG_REMOVE) {
            if (applyUserRestrictions) {
                Slog.d(TAG, "Remembering install states:");
                for (int i = 0; i < allUserHandles.length; i++) {
                    Slog.d(TAG, "   u=" + allUserHandles[i] + " inst=" + perUserInstalled[i]);
                }
            }
        }
        // Delete the updated package
        outInfo.isRemovedPackageSystemUpdate = true;
        if (disabledPs.versionCode < newPs.versionCode) {
            // Delete data for downgrades
            flags &= ~PackageManager.DELETE_KEEP_DATA;
        } else {
            // Preserve data by setting flag
            flags |= PackageManager.DELETE_KEEP_DATA;
        }
        boolean ret = deleteInstalledPackageLI(newPs, true, flags,
                allUserHandles, perUserInstalled, outInfo, writeSettings);
        if (!ret) {
            return false;
        }
        // writer
        synchronized (mPackages) {
            // Reinstate the old system package
            mSettings.enableSystemPackageLPw(newPs.name);
            // Remove any native libraries from the upgraded package.
            NativeLibraryHelper.removeNativeBinariesLI(newPs.legacyNativeLibraryPathString);
        }
        // Install the system package
        if (DEBUG_REMOVE) Slog.d(TAG, "Re-installing system package: " + disabledPs);
        int parseFlags = PackageParser.PARSE_MUST_BE_APK | PackageParser.PARSE_IS_SYSTEM;
        if (locationIsPrivileged(disabledPs.codePath)) {
            parseFlags |= PackageParser.PARSE_IS_PRIVILEGED;
        }

        final PackageParser.Package newPkg;
        try {
            newPkg = scanPackageLI(disabledPs.codePath, parseFlags, SCAN_NO_PATHS, 0, null);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to restore system package:" + newPs.name + ": " + e.getMessage());
            return false;
        }

        // writer
        synchronized (mPackages) {
            PackageSetting ps = mSettings.mPackages.get(newPkg.packageName);

            // Propagate the permissions state as we do not want to drop on the floor
            // runtime permissions. The update permissions method below will take
            // care of removing obsolete permissions and grant install permissions.
            ps.getPermissionsState().copyFrom(newPs.getPermissionsState());
            updatePermissionsLPw(newPkg.packageName, newPkg,
                    UPDATE_PERMISSIONS_ALL | UPDATE_PERMISSIONS_REPLACE_PKG);

            if (applyUserRestrictions) {
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Propagating install state across reinstall");
                }
                for (int i = 0; i < allUserHandles.length; i++) {
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "    user " + allUserHandles[i]
                                + " => " + perUserInstalled[i]);
                    }
                    ps.setInstalled(perUserInstalled[i], allUserHandles[i]);

                    mSettings.writeRuntimePermissionsForUserLPr(allUserHandles[i], false);
                }
                // Regardless of writeSettings we need to ensure that this restriction
                // state propagation is persisted
                mSettings.writeAllUsersPackageRestrictionsLPr();
            }
            // can downgrade to reader here
            if (writeSettings) {
                mSettings.writeLPr();
            }
        }
        return true;
    }

    private boolean deleteInstalledPackageLI(PackageSetting ps,
            boolean deleteCodeAndResources, int flags,
            int[] allUserHandles, boolean[] perUserInstalled,
            PackageRemovedInfo outInfo, boolean writeSettings) {
        if (outInfo != null) {
            outInfo.uid = ps.appId;
        }

        // Delete package data from internal structures and also remove data if flag is set
        removePackageDataLI(ps, allUserHandles, perUserInstalled, outInfo, flags, writeSettings);

        // Delete application code and resources
        if (deleteCodeAndResources && (outInfo != null)) {
            outInfo.args = createInstallArgsForExisting(packageFlagsToInstallFlags(ps),
                    ps.codePathString, ps.resourcePathString, getAppDexInstructionSets(ps));
            if (DEBUG_SD_INSTALL) Slog.i(TAG, "args=" + outInfo.args);
        }
        return true;
    }

    @Override
    public boolean setBlockUninstallForUser(String packageName, boolean blockUninstall,
            int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_PACKAGES, null);
        synchronized (mPackages) {
            PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                Log.i(TAG, "Package doesn't exist in set block uninstall " + packageName);
                return false;
            }
            if (!ps.getInstalled(userId)) {
                // Can't block uninstall for an app that is not installed or enabled.
                Log.i(TAG, "Package not installed in set block uninstall " + packageName);
                return false;
            }
            ps.setBlockUninstall(blockUninstall, userId);
            mSettings.writePackageRestrictionsLPr(userId);
        }
        return true;
    }

    @Override
    public boolean getBlockUninstallForUser(String packageName, int userId) {
        synchronized (mPackages) {
            PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                Log.i(TAG, "Package doesn't exist in get block uninstall " + packageName);
                return false;
            }
            return ps.getBlockUninstall(userId);
        }
    }

    /*
     * This method handles package deletion in general
     */
    private boolean deletePackageLI(String packageName, UserHandle user,
            boolean deleteCodeAndResources, int[] allUserHandles, boolean[] perUserInstalled,
            int flags, PackageRemovedInfo outInfo,
            boolean writeSettings) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        if (DEBUG_REMOVE) Slog.d(TAG, "deletePackageLI: " + packageName + " user " + user);
        PackageSetting ps;
        boolean dataOnly = false;
        int removeUser = -1;
        int appId = -1;
        synchronized (mPackages) {
            ps = mSettings.mPackages.get(packageName);
            if (ps == null) {
                Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                return false;
            }
            if ((!isSystemApp(ps) || (flags&PackageManager.DELETE_SYSTEM_APP) != 0) && user != null
                    && user.getIdentifier() != UserHandle.USER_ALL) {
                // The caller is asking that the package only be deleted for a single
                // user.  To do this, we just mark its uninstalled state and delete
                // its data.  If this is a system app, we only allow this to happen if
                // they have set the special DELETE_SYSTEM_APP which requests different
                // semantics than normal for uninstalling system apps.
                if (DEBUG_REMOVE) Slog.d(TAG, "Only deleting for single user");
                final int userId = user.getIdentifier();
                ps.setUserState(userId,
                        COMPONENT_ENABLED_STATE_DEFAULT,
                        false, //installed
                        true,  //stopped
                        true,  //notLaunched
                        false, //hidden
                        null, null, null,
                        false, // blockUninstall
                        ps.readUserState(userId).domainVerificationStatus, 0);
                if (!isSystemApp(ps)) {
                    if (ps.isAnyInstalled(sUserManager.getUserIds())) {
                        // Other user still have this package installed, so all
                        // we need to do is clear this user's data and save that
                        // it is uninstalled.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Still installed by other users");
                        removeUser = user.getIdentifier();
                        appId = ps.appId;
                        scheduleWritePackageRestrictionsLocked(removeUser);
                    } else {
                        // We need to set it back to 'installed' so the uninstall
                        // broadcasts will be sent correctly.
                        if (DEBUG_REMOVE) Slog.d(TAG, "Not installed by other users, full delete");
                        ps.setInstalled(true, user.getIdentifier());
                    }
                } else {
                    // This is a system app, so we assume that the
                    // other users still have this package installed, so all
                    // we need to do is clear this user's data and save that
                    // it is uninstalled.
                    if (DEBUG_REMOVE) Slog.d(TAG, "Deleting system app");
                    removeUser = user.getIdentifier();
                    appId = ps.appId;
                    scheduleWritePackageRestrictionsLocked(removeUser);
                }
            }
        }

        if (removeUser >= 0) {
            // From above, we determined that we are deleting this only
            // for a single user.  Continue the work here.
            if (DEBUG_REMOVE) Slog.d(TAG, "Updating install state for user: " + removeUser);
            if (outInfo != null) {
                outInfo.removedPackage = packageName;
                outInfo.removedAppId = appId;
                outInfo.removedUsers = new int[] {removeUser};
            }
            mInstaller.clearUserData(ps.volumeUuid, packageName, removeUser);
            removeKeystoreDataIfNeeded(removeUser, appId);
            schedulePackageCleaning(packageName, removeUser, false);
            synchronized (mPackages) {
                if (clearPackagePreferredActivitiesLPw(packageName, removeUser)) {
                    scheduleWritePackageRestrictionsLocked(removeUser);
                }
                resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, removeUser);
            }
            return true;
        }

        if (dataOnly) {
            // Delete application data first
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing package data only");
            removePackageDataLI(ps, null, null, outInfo, flags, writeSettings);
            return true;
        }

        boolean ret = false;
        if (isSystemApp(ps)) {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing system package:" + ps.name);
            // When an updated system application is deleted we delete the existing resources as well and
            // fall back to existing code in system partition
            ret = deleteSystemPackageLI(ps, allUserHandles, perUserInstalled,
                    flags, outInfo, writeSettings);
        } else {
            if (DEBUG_REMOVE) Slog.d(TAG, "Removing non-system package:" + ps.name);
            // Kill application pre-emptively especially for apps on sd.
            killApplication(packageName, ps.appId, "uninstall pkg");
            ret = deleteInstalledPackageLI(ps, deleteCodeAndResources, flags,
                    allUserHandles, perUserInstalled,
                    outInfo, writeSettings);
        }

        return ret;
    }

    private final class ClearStorageConnection implements ServiceConnection {
        IMediaContainerService mContainerService;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (this) {
                mContainerService = IMediaContainerService.Stub.asInterface(service);
                notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    }

    private void clearExternalStorageDataSync(String packageName, int userId, boolean allData) {
        final boolean mounted;
        if (Environment.isExternalStorageEmulated()) {
            mounted = true;
        } else {
            final String status = Environment.getExternalStorageState();

            mounted = status.equals(Environment.MEDIA_MOUNTED)
                    || status.equals(Environment.MEDIA_MOUNTED_READ_ONLY);
        }

        if (!mounted) {
            return;
        }

        final Intent containerIntent = new Intent().setComponent(DEFAULT_CONTAINER_COMPONENT);
        int[] users;
        if (userId == UserHandle.USER_ALL) {
            users = sUserManager.getUserIds();
        } else {
            users = new int[] { userId };
        }
        final ClearStorageConnection conn = new ClearStorageConnection();
        if (mContext.bindServiceAsUser(
                containerIntent, conn, Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            try {
                for (int curUser : users) {
                    long timeout = SystemClock.uptimeMillis() + 5000;
                    synchronized (conn) {
                        long now = SystemClock.uptimeMillis();
                        while (conn.mContainerService == null && now < timeout) {
                            try {
                                conn.wait(timeout - now);
                            } catch (InterruptedException e) {
                            }
                        }
                    }
                    if (conn.mContainerService == null) {
                        return;
                    }

                    final UserEnvironment userEnv = new UserEnvironment(curUser);
                    clearDirectory(conn.mContainerService,
                            userEnv.buildExternalStorageAppCacheDirs(packageName));
                    if (allData) {
                        clearDirectory(conn.mContainerService,
                                userEnv.buildExternalStorageAppDataDirs(packageName));
                        clearDirectory(conn.mContainerService,
                                userEnv.buildExternalStorageAppMediaDirs(packageName));
                    }
                }
            } finally {
                mContext.unbindService(conn);
            }
        }
    }

    @Override
    public void clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer, final int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CLEAR_APP_USER_DATA, null);
        enforceCrossUserPermission(Binder.getCallingUid(), userId, true, false, "clear application data");
        // Queue up an async operation since the package deletion may take a little while.
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeeded;
                synchronized (mInstallLock) {
                    succeeded = clearApplicationUserDataLI(packageName, userId);
                }
                clearExternalStorageDataSync(packageName, userId, true);
                if (succeeded) {
                    // invoke DeviceStorageMonitor's update method to clear any notifications
                    DeviceStorageMonitorInternal
                            dsm = LocalServices.getService(DeviceStorageMonitorInternal.class);
                    if (dsm != null) {
                        dsm.checkMemory();
                    }
                }
                if(observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, succeeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                } //end if observer
            } //end run
        });
    }

    private boolean clearApplicationUserDataLI(String packageName, int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }

        // Try finding details about the requested package
        PackageParser.Package pkg;
        synchronized (mPackages) {
            pkg = mPackages.get(packageName);
            if (pkg == null) {
                final PackageSetting ps = mSettings.mPackages.get(packageName);
                if (ps != null) {
                    pkg = ps.pkg;
                }
            }

            if (pkg == null) {
                Slog.w(TAG, "Package named '" + packageName + "' doesn't exist.");
                return false;
            }

            PackageSetting ps = (PackageSetting) pkg.mExtras;
            resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, userId);
        }

        // Always delete data directories for package, even if we found no other
        // record of app. This helps users recover from UID mismatches without
        // resorting to a full data wipe.
        int retCode = mInstaller.clearUserData(pkg.volumeUuid, packageName, userId);
        if (retCode < 0) {
            Slog.w(TAG, "Couldn't remove cache files for package: " + packageName);
            return false;
        }

        final int appId = pkg.applicationInfo.uid;
        removeKeystoreDataIfNeeded(userId, appId);

        // Create a native library symlink only if we have native libraries
        // and if the native libraries are 32 bit libraries. We do not provide
        // this symlink for 64 bit libraries.
        if (pkg.applicationInfo.primaryCpuAbi != null &&
                !VMRuntime.is64BitAbi(pkg.applicationInfo.primaryCpuAbi)) {
            final String nativeLibPath = pkg.applicationInfo.nativeLibraryDir;
            if (mInstaller.linkNativeLibraryDirectory(pkg.volumeUuid, pkg.packageName,
                    nativeLibPath, userId) < 0) {
                Slog.w(TAG, "Failed linking native library dir");
                return false;
            }
        }

        return true;
    }

    /**
     * Reverts user permission state changes (permissions and flags) in
     * all packages for a given user.
     *
     * @param userId The device user for which to do a reset.
     */
    private void resetUserChangesToRuntimePermissionsAndFlagsLPw(int userId) {
        final int packageCount = mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageParser.Package pkg = mPackages.valueAt(i);
            PackageSetting ps = (PackageSetting) pkg.mExtras;
            resetUserChangesToRuntimePermissionsAndFlagsLPw(ps, userId);
        }
    }

    /**
     * Reverts user permission state changes (permissions and flags).
     *
     * @param ps The package for which to reset.
     * @param userId The device user for which to do a reset.
     */
    private void resetUserChangesToRuntimePermissionsAndFlagsLPw(
            final PackageSetting ps, final int userId) {
        if (ps.pkg == null) {
            return;
        }

        final int userSettableFlags = FLAG_PERMISSION_USER_SET
                | FLAG_PERMISSION_USER_FIXED
                | FLAG_PERMISSION_REVOKE_ON_UPGRADE;

        final int policyOrSystemFlags = FLAG_PERMISSION_SYSTEM_FIXED
                | FLAG_PERMISSION_POLICY_FIXED;

        boolean writeInstallPermissions = false;
        boolean writeRuntimePermissions = false;

        final int permissionCount = ps.pkg.requestedPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permission = ps.pkg.requestedPermissions.get(i);

            BasePermission bp = mSettings.mPermissions.get(permission);
            if (bp == null) {
                continue;
            }

            // If shared user we just reset the state to which only this app contributed.
            if (ps.sharedUser != null) {
                boolean used = false;
                final int packageCount = ps.sharedUser.packages.size();
                for (int j = 0; j < packageCount; j++) {
                    PackageSetting pkg = ps.sharedUser.packages.valueAt(j);
                    if (pkg.pkg != null && !pkg.pkg.packageName.equals(ps.pkg.packageName)
                            && pkg.pkg.requestedPermissions.contains(permission)) {
                        used = true;
                        break;
                    }
                }
                if (used) {
                    continue;
                }
            }

            PermissionsState permissionsState = ps.getPermissionsState();

            final int oldFlags = permissionsState.getPermissionFlags(bp.name, userId);

            // Always clear the user settable flags.
            final boolean hasInstallState = permissionsState.getInstallPermissionState(
                    bp.name) != null;
            if (permissionsState.updatePermissionFlags(bp, userId, userSettableFlags, 0)) {
                if (hasInstallState) {
                    writeInstallPermissions = true;
                } else {
                    writeRuntimePermissions = true;
                }
            }

            // Below is only runtime permission handling.
            if (!bp.isRuntime()) {
                continue;
            }

            // Never clobber system or policy.
            if ((oldFlags & policyOrSystemFlags) != 0) {
                continue;
            }

            // If this permission was granted by default, make sure it is.
            if ((oldFlags & FLAG_PERMISSION_GRANTED_BY_DEFAULT) != 0) {
                if (permissionsState.grantRuntimePermission(bp, userId)
                        != PERMISSION_OPERATION_FAILURE) {
                    writeRuntimePermissions = true;
                }
            } else {
                // Otherwise, reset the permission.
                final int revokeResult = permissionsState.revokeRuntimePermission(bp, userId);
                switch (revokeResult) {
                    case PERMISSION_OPERATION_SUCCESS: {
                        writeRuntimePermissions = true;
                    } break;

                    case PERMISSION_OPERATION_SUCCESS_GIDS_CHANGED: {
                        writeRuntimePermissions = true;
                        final int appId = ps.appId;
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                killUid(appId, userId, KILL_APP_REASON_GIDS_CHANGED);
                            }
                        });
                    } break;
                }
            }
        }

        // Synchronously write as we are taking permissions away.
        if (writeRuntimePermissions) {
            mSettings.writeRuntimePermissionsForUserLPr(userId, true);
        }

        // Synchronously write as we are taking permissions away.
        if (writeInstallPermissions) {
            mSettings.writeLPr();
        }
    }

    /**
     * Remove entries from the keystore daemon. Will only remove it if the
     * {@code appId} is valid.
     */
    private static void removeKeystoreDataIfNeeded(int userId, int appId) {
        if (appId < 0) {
            return;
        }

        final KeyStore keyStore = KeyStore.getInstance();
        if (keyStore != null) {
            if (userId == UserHandle.USER_ALL) {
                for (final int individual : sUserManager.getUserIds()) {
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
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.DELETE_CACHE_FILES, null);
        // Queue up an async operation since the package deletion may take a little while.
        final int userId = UserHandle.getCallingUserId();
        mHandler.post(new Runnable() {
            public void run() {
                mHandler.removeCallbacks(this);
                final boolean succeded;
                synchronized (mInstallLock) {
                    succeded = deleteApplicationCacheFilesLI(packageName, userId);
                }
                clearExternalStorageDataSync(packageName, userId, false);
                if (observer != null) {
                    try {
                        observer.onRemoveCompleted(packageName, succeded);
                    } catch (RemoteException e) {
                        Log.i(TAG, "Observer no longer exists.");
                    }
                } //end if observer
            } //end run
        });
    }

    private boolean deleteApplicationCacheFilesLI(String packageName, int userId) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to delete null packageName.");
            return false;
        }
        PackageParser.Package p;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
        }
        if (p == null) {
            Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
            return false;
        }
        final ApplicationInfo applicationInfo = p.applicationInfo;
        if (applicationInfo == null) {
            Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
            return false;
        }
        int retCode = mInstaller.deleteCacheFiles(p.volumeUuid, packageName, userId);
        if (retCode < 0) {
            Slog.w(TAG, "Couldn't remove cache files for package: "
                       + packageName + " u" + userId);
            return false;
        }
        return true;
    }

    @Override
    public void getPackageSizeInfo(final String packageName, int userHandle,
            final IPackageStatsObserver observer) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.GET_PACKAGE_SIZE, null);
        if (packageName == null) {
            throw new IllegalArgumentException("Attempt to get size of null packageName");
        }

        PackageStats stats = new PackageStats(packageName, userHandle);

        /*
         * Queue up an async operation since the package measurement may take a
         * little while.
         */
        Message msg = mHandler.obtainMessage(INIT_COPY);
        msg.obj = new MeasureParams(stats, observer);
        mHandler.sendMessage(msg);
    }

    private boolean getPackageSizeInfoLI(String packageName, int userHandle,
            PackageStats pStats) {
        if (packageName == null) {
            Slog.w(TAG, "Attempt to get size of null packageName.");
            return false;
        }
        PackageParser.Package p;
        boolean dataOnly = false;
        String libDirRoot = null;
        String asecPath = null;
        PackageSetting ps = null;
        synchronized (mPackages) {
            p = mPackages.get(packageName);
            ps = mSettings.mPackages.get(packageName);
            if(p == null) {
                dataOnly = true;
                if((ps == null) || (ps.pkg == null)) {
                    Slog.w(TAG, "Package named '" + packageName +"' doesn't exist.");
                    return false;
                }
                p = ps.pkg;
            }
            if (ps != null) {
                libDirRoot = ps.legacyNativeLibraryPathString;
            }
            if (p != null && (isExternal(p) || p.isForwardLocked())) {
                String secureContainerId = cidFromCodePath(p.applicationInfo.getBaseCodePath());
                if (secureContainerId != null) {
                    asecPath = PackageHelper.getSdFilesystem(secureContainerId);
                }
            }
        }
        String publicSrcDir = null;
        if(!dataOnly) {
            final ApplicationInfo applicationInfo = p.applicationInfo;
            if (applicationInfo == null) {
                Slog.w(TAG, "Package " + packageName + " has no applicationInfo.");
                return false;
            }
            if (p.isForwardLocked()) {
                publicSrcDir = applicationInfo.getBaseResourcePath();
            }
        }
        // TODO: extend to measure size of split APKs
        // TODO(multiArch): Extend getSizeInfo to look at the full subdirectory tree,
        // not just the first level.
        // TODO(multiArch): Extend getSizeInfo to look at *all* instruction sets, not
        // just the primary.
        String[] dexCodeInstructionSets = getDexCodeInstructionSets(getAppDexInstructionSets(ps));
        int res = mInstaller.getSizeInfo(p.volumeUuid, packageName, userHandle, p.baseCodePath,
                libDirRoot, publicSrcDir, asecPath, dexCodeInstructionSets, pStats);
        if (res < 0) {
            return false;
        }

        // Fix-up for forward-locked applications in ASEC containers.
        if (!isExternal(p)) {
            pStats.codeSize += pStats.externalCodeSize;
            pStats.externalCodeSize = 0L;
        }

        return true;
    }


    @Override
    public void addPackageToPreferred(String packageName) {
        Slog.w(TAG, "addPackageToPreferred: this is now a no-op");
    }

    @Override
    public void removePackageFromPreferred(String packageName) {
        Slog.w(TAG, "removePackageFromPreferred: this is now a no-op");
    }

    @Override
    public List<PackageInfo> getPreferredPackages(int flags) {
        return new ArrayList<PackageInfo>();
    }

    private int getUidTargetSdkVersionLockedLPr(int uid) {
        Object obj = mSettings.getUserIdLPr(uid);
        if (obj instanceof SharedUserSetting) {
            final SharedUserSetting sus = (SharedUserSetting) obj;
            int vers = Build.VERSION_CODES.CUR_DEVELOPMENT;
            final Iterator<PackageSetting> it = sus.packages.iterator();
            while (it.hasNext()) {
                final PackageSetting ps = it.next();
                if (ps.pkg != null) {
                    int v = ps.pkg.applicationInfo.targetSdkVersion;
                    if (v < vers) vers = v;
                }
            }
            return vers;
        } else if (obj instanceof PackageSetting) {
            final PackageSetting ps = (PackageSetting) obj;
            if (ps.pkg != null) {
                return ps.pkg.applicationInfo.targetSdkVersion;
            }
        }
        return Build.VERSION_CODES.CUR_DEVELOPMENT;
    }

    @Override
    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        addPreferredActivityInternal(filter, match, set, activity, true, userId,
                "Adding preferred");
    }

    private void addPreferredActivityInternal(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, boolean always, int userId,
            String opname) {
        // writer
        int callingUid = Binder.getCallingUid();
        enforceCrossUserPermission(callingUid, userId, true, false, "add preferred activity");
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        synchronized (mPackages) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (getUidTargetSdkVersionLockedLPr(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring addPreferredActivity() from uid "
                            + callingUid);
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }

            PreferredIntentResolver pir = mSettings.editPreferredActivitiesLPw(userId);
            Slog.i(TAG, opname + " activity " + activity.flattenToShortString() + " for user "
                    + userId + ":");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
            pir.addFilter(new PreferredActivity(filter, match, set, activity, always));
            scheduleWritePackageRestrictionsLocked(userId);
        }
    }

    @Override
    public void replacePreferredActivity(IntentFilter filter, int match,
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
        enforceCrossUserPermission(callingUid, userId, true, false, "replace preferred activity");
        synchronized (mPackages) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (getUidTargetSdkVersionLockedLPr(callingUid)
                        < Build.VERSION_CODES.FROYO) {
                    Slog.w(TAG, "Ignoring replacePreferredActivity() from uid "
                            + Binder.getCallingUid());
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
            }

            PreferredIntentResolver pir = mSettings.mPreferredActivities.get(userId);
            if (pir != null) {
                // Get all of the existing entries that exactly match this filter.
                ArrayList<PreferredActivity> existing = pir.findFilters(filter);
                if (existing != null && existing.size() == 1) {
                    PreferredActivity cur = existing.get(0);
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
                    if (DEBUG_PREFERRED) {
                        Slog.i(TAG, existing.size() + " existing preferred matches for:");
                        filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
                    }
                    for (int i = 0; i < existing.size(); i++) {
                        PreferredActivity pa = existing.get(i);
                        if (DEBUG_PREFERRED) {
                            Slog.i(TAG, "Removing existing preferred activity "
                                    + pa.mPref.mComponent + ":");
                            pa.dump(new LogPrinter(Log.INFO, TAG), "  ");
                        }
                        pir.removeFilter(pa);
                    }
                }
            }
            addPreferredActivityInternal(filter, match, set, activity, true, userId,
                    "Replacing preferred");
        }
    }

    @Override
    public void clearPackagePreferredActivities(String packageName) {
        final int uid = Binder.getCallingUid();
        // writer
        synchronized (mPackages) {
            PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null || pkg.applicationInfo.uid != uid) {
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (getUidTargetSdkVersionLockedLPr(Binder.getCallingUid())
                            < Build.VERSION_CODES.FROYO) {
                        Slog.w(TAG, "Ignoring clearPackagePreferredActivities() from uid "
                                + Binder.getCallingUid());
                        return;
                    }
                    mContext.enforceCallingOrSelfPermission(
                            android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
                }
            }

            int user = UserHandle.getCallingUserId();
            if (clearPackagePreferredActivitiesLPw(packageName, user)) {
                scheduleWritePackageRestrictionsLocked(user);
            }
        }
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    boolean clearPackagePreferredActivitiesLPw(String packageName, int userId) {
        ArrayList<PreferredActivity> removed = null;
        boolean changed = false;
        for (int i=0; i<mSettings.mPreferredActivities.size(); i++) {
            final int thisUserId = mSettings.mPreferredActivities.keyAt(i);
            PreferredIntentResolver pir = mSettings.mPreferredActivities.valueAt(i);
            if (userId != UserHandle.USER_ALL && userId != thisUserId) {
                continue;
            }
            Iterator<PreferredActivity> it = pir.filterIterator();
            while (it.hasNext()) {
                PreferredActivity pa = it.next();
                // Mark entry for removal only if it matches the package name
                // and the entry is of type "always".
                if (packageName == null ||
                        (pa.mPref.mComponent.getPackageName().equals(packageName)
                                && pa.mPref.mAlways)) {
                    if (removed == null) {
                        removed = new ArrayList<PreferredActivity>();
                    }
                    removed.add(pa);
                }
            }
            if (removed != null) {
                for (int j=0; j<removed.size(); j++) {
                    PreferredActivity pa = removed.get(j);
                    pir.removeFilter(pa);
                }
                changed = true;
            }
        }
        return changed;
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    private void clearIntentFilterVerificationsLPw(int userId) {
        final int packageCount = mPackages.size();
        for (int i = 0; i < packageCount; i++) {
            PackageParser.Package pkg = mPackages.valueAt(i);
            clearIntentFilterVerificationsLPw(pkg.packageName, userId);
        }
    }

    /** This method takes a specific user id as well as UserHandle.USER_ALL. */
    void clearIntentFilterVerificationsLPw(String packageName, int userId) {
        if (userId == UserHandle.USER_ALL) {
            if (mSettings.removeIntentFilterVerificationLPw(packageName,
                    sUserManager.getUserIds())) {
                for (int oneUserId : sUserManager.getUserIds()) {
                    scheduleWritePackageRestrictionsLocked(oneUserId);
                }
            }
        } else {
            if (mSettings.removeIntentFilterVerificationLPw(packageName, userId)) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    void clearDefaultBrowserIfNeeded(String packageName) {
        for (int oneUserId : sUserManager.getUserIds()) {
            String defaultBrowserPackageName = getDefaultBrowserPackageName(oneUserId);
            if (TextUtils.isEmpty(defaultBrowserPackageName)) continue;
            if (packageName.equals(defaultBrowserPackageName)) {
                setDefaultBrowserPackageName(null, oneUserId);
            }
        }
    }

    @Override
    public void resetApplicationPreferences(int userId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_PREFERRED_APPLICATIONS, null);
        // writer
        synchronized (mPackages) {
            final long identity = Binder.clearCallingIdentity();
            try {
                clearPackagePreferredActivitiesLPw(null, userId);
                mSettings.applyDefaultPreferredAppsLPw(this, userId);
                // TODO: We have to reset the default SMS and Phone. This requires
                // significant refactoring to keep all default apps in the package
                // manager (cleaner but more work) or have the services provide
                // callbacks to the package manager to request a default app reset.
                applyFactoryDefaultBrowserLPw(userId);
                clearIntentFilterVerificationsLPw(userId);
                primeDomainVerificationsLPw(userId);
                resetUserChangesToRuntimePermissionsAndFlagsLPw(userId);
                scheduleWritePackageRestrictionsLocked(userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName) {

        int num = 0;
        final int userId = UserHandle.getCallingUserId();
        // reader
        synchronized (mPackages) {
            PreferredIntentResolver pir = mSettings.mPreferredActivities.get(userId);
            if (pir != null) {
                final Iterator<PreferredActivity> it = pir.filterIterator();
                while (it.hasNext()) {
                    final PreferredActivity pa = it.next();
                    if (packageName == null
                            || (pa.mPref.mComponent.getPackageName().equals(packageName)
                                    && pa.mPref.mAlways)) {
                        if (outFilters != null) {
                            outFilters.add(new IntentFilter(pa));
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
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "addPersistentPreferredActivity can only be run by the system");
        }
        if (filter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a preferred activity with no filter actions");
            return;
        }
        synchronized (mPackages) {
            Slog.i(TAG, "Adding persistent preferred activity " + activity + " for user " + userId +
                    " :");
            filter.dump(new LogPrinter(Log.INFO, TAG), "  ");
            mSettings.editPersistentPreferredActivitiesLPw(userId).addFilter(
                    new PersistentPreferredActivity(filter, activity));
            scheduleWritePackageRestrictionsLocked(userId);
        }
    }

    @Override
    public void clearPackagePersistentPreferredActivities(String packageName, int userId) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "clearPackagePersistentPreferredActivities can only be run by the system");
        }
        ArrayList<PersistentPreferredActivity> removed = null;
        boolean changed = false;
        synchronized (mPackages) {
            for (int i=0; i<mSettings.mPersistentPreferredActivities.size(); i++) {
                final int thisUserId = mSettings.mPersistentPreferredActivities.keyAt(i);
                PersistentPreferredIntentResolver ppir = mSettings.mPersistentPreferredActivities
                        .valueAt(i);
                if (userId != thisUserId) {
                    continue;
                }
                Iterator<PersistentPreferredActivity> it = ppir.filterIterator();
                while (it.hasNext()) {
                    PersistentPreferredActivity ppa = it.next();
                    // Mark entry for removal only if it matches the package name.
                    if (ppa.mComponent.getPackageName().equals(packageName)) {
                        if (removed == null) {
                            removed = new ArrayList<PersistentPreferredActivity>();
                        }
                        removed.add(ppa);
                    }
                }
                if (removed != null) {
                    for (int j=0; j<removed.size(); j++) {
                        PersistentPreferredActivity ppa = removed.get(j);
                        ppir.removeFilter(ppa);
                    }
                    changed = true;
                }
            }

            if (changed) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    /**
     * Common machinery for picking apart a restored XML blob and passing
     * it to a caller-supplied functor to be applied to the running system.
     */
    private void restoreFromXml(XmlPullParser parser, int userId,
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
        public void apply(XmlPullParser parser, int userId) throws IOException, XmlPullParserException;
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
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_PREFERRED_BACKUP);

            synchronized (mPackages) {
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
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_PREFERRED_BACKUP,
                    new BlobXmlRestorer() {
                        @Override
                        public void apply(XmlPullParser parser, int userId)
                                throws XmlPullParserException, IOException {
                            synchronized (mPackages) {
                                mSettings.readPreferredActivitiesLPw(parser, userId);
                            }
                        }
                    } );
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
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_DEFAULT_APPS);

            synchronized (mPackages) {
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
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_DEFAULT_APPS,
                    new BlobXmlRestorer() {
                        @Override
                        public void apply(XmlPullParser parser, int userId)
                                throws XmlPullParserException, IOException {
                            synchronized (mPackages) {
                                mSettings.readDefaultAppsLPw(parser, userId);
                            }
                        }
                    } );
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring default apps: " + e.getMessage());
            }
        }
    }

    @Override
    public byte[] getIntentFilterVerificationBackup(int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call getIntentFilterVerificationBackup()");
        }

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        try {
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(dataStream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_INTENT_FILTER_VERIFICATION);

            synchronized (mPackages) {
                mSettings.writeAllDomainVerificationsLPr(serializer, userId);
            }

            serializer.endTag(null, TAG_INTENT_FILTER_VERIFICATION);
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
    public void restoreIntentFilterVerification(byte[] backup, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call restorePreferredActivities()");
        }

        try {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(backup), StandardCharsets.UTF_8.name());
            restoreFromXml(parser, userId, TAG_INTENT_FILTER_VERIFICATION,
                    new BlobXmlRestorer() {
                        @Override
                        public void apply(XmlPullParser parser, int userId)
                                throws XmlPullParserException, IOException {
                            synchronized (mPackages) {
                                mSettings.readAllDomainVerificationsLPr(parser, userId);
                                mSettings.writeLPr();
                            }
                        }
                    } );
        } catch (Exception e) {
            if (DEBUG_BACKUP) {
                Slog.e(TAG, "Exception restoring preferred activities: " + e.getMessage());
            }
        }
    }

    @Override
    public void addCrossProfileIntentFilter(IntentFilter intentFilter, String ownerPackage,
            int sourceUserId, int targetUserId, int flags) {
        mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        if (intentFilter.countActions() == 0) {
            Slog.w(TAG, "Cannot set a crossProfile intent filter with no filter actions");
            return;
        }
        synchronized (mPackages) {
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
        int callingUid = Binder.getCallingUid();
        enforceOwnerRights(ownerPackage, callingUid);
        enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, callingUid, sourceUserId);
        synchronized (mPackages) {
            CrossProfileIntentResolver resolver =
                    mSettings.editCrossProfileIntentResolverLPw(sourceUserId);
            ArraySet<CrossProfileIntentFilter> set =
                    new ArraySet<CrossProfileIntentFilter>(resolver.filterSet());
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
        int callingUserId = UserHandle.getUserId(callingUid);
        PackageInfo pi = getPackageInfo(pkg, 0, callingUserId);
        if (pi == null) {
            throw new IllegalArgumentException("Unknown package " + pkg + " on user "
                    + callingUserId);
        }
        if (!UserHandle.isSameApp(pi.applicationInfo.uid, callingUid)) {
            throw new SecurityException("Calling uid " + callingUid
                    + " does not own package " + pkg);
        }
    }

    @Override
    public ComponentName getHomeActivities(List<ResolveInfo> allHomeCandidates) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);

        final int callingUserId = UserHandle.getCallingUserId();
        List<ResolveInfo> list = queryIntentActivities(intent, null,
                PackageManager.GET_META_DATA, callingUserId);
        ResolveInfo preferred = findPreferredActivity(intent, null, 0, list, 0,
                true, false, false, callingUserId);

        allHomeCandidates.clear();
        if (list != null) {
            for (ResolveInfo ri : list) {
                allHomeCandidates.add(ri);
            }
        }
        return (preferred == null || preferred.activityInfo == null)
                ? null
                : new ComponentName(preferred.activityInfo.packageName,
                        preferred.activityInfo.name);
    }

    @Override
    public void setApplicationEnabledSetting(String appPackageName,
            int newState, int flags, int userId, String callingPackage) {
        if (!sUserManager.exists(userId)) return;
        if (callingPackage == null) {
            callingPackage = Integer.toString(Binder.getCallingUid());
        }
        setEnabledSetting(appPackageName, null, newState, flags, userId, callingPackage);
    }

    @Override
    public void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags, int userId) {
        if (!sUserManager.exists(userId)) return;
        setEnabledSetting(componentName.getPackageName(),
                componentName.getClassName(), newState, flags, userId, null);
    }

    private void setEnabledSetting(final String packageName, String className, int newState,
            final int flags, int userId, String callingPackage) {
        if (!(newState == COMPONENT_ENABLED_STATE_DEFAULT
              || newState == COMPONENT_ENABLED_STATE_ENABLED
              || newState == COMPONENT_ENABLED_STATE_DISABLED
              || newState == COMPONENT_ENABLED_STATE_DISABLED_USER
              || newState == COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
            throw new IllegalArgumentException("Invalid new component state: "
                    + newState);
        }
        PackageSetting pkgSetting;
        final int uid = Binder.getCallingUid();
        final int permission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        enforceCrossUserPermission(uid, userId, false, true, "set enabled");
        final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
        boolean sendNow = false;
        boolean isApp = (className == null);
        String componentName = isApp ? packageName : className;
        int packageUid = -1;
        ArrayList<String> components;

        // writer
        synchronized (mPackages) {
            pkgSetting = mSettings.mPackages.get(packageName);
            if (pkgSetting == null) {
                if (className == null) {
                    throw new IllegalArgumentException(
                            "Unknown package: " + packageName);
                }
                throw new IllegalArgumentException(
                        "Unknown component: " + packageName
                        + "/" + className);
            }
            // Allow root and verify that userId is not being specified by a different user
            if (!allowedByPermission && !UserHandle.isSameApp(uid, pkgSetting.appId)) {
                throw new SecurityException(
                        "Permission Denial: attempt to change component state from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + uid + ", package uid=" + pkgSetting.appId);
            }
            if (className == null) {
                // We're dealing with an application/package level state change
                if (pkgSetting.getEnabled(userId) == newState) {
                    // Nothing to do
                    return;
                }
                if (newState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    || newState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    // Don't care about who enables an app.
                    callingPackage = null;
                }
                pkgSetting.setEnabled(newState, userId, callingPackage);
                // pkgSetting.pkg.mSetEnabled = newState;
            } else {
                // We're dealing with a component level state change
                // First, verify that this is a valid class name.
                PackageParser.Package pkg = pkgSetting.pkg;
                if (pkg == null || !pkg.hasComponentClassName(className)) {
                    if (pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.JELLY_BEAN) {
                        throw new IllegalArgumentException("Component class " + className
                                + " does not exist in " + packageName);
                    } else {
                        Slog.w(TAG, "Failed setComponentEnabledSetting: component class "
                                + className + " does not exist in " + packageName);
                    }
                }
                switch (newState) {
                case COMPONENT_ENABLED_STATE_ENABLED:
                    if (!pkgSetting.enableComponentLPw(className, userId)) {
                        return;
                    }
                    break;
                case COMPONENT_ENABLED_STATE_DISABLED:
                    if (!pkgSetting.disableComponentLPw(className, userId)) {
                        return;
                    }
                    break;
                case COMPONENT_ENABLED_STATE_DEFAULT:
                    if (!pkgSetting.restoreComponentLPw(className, userId)) {
                        return;
                    }
                    break;
                default:
                    Slog.e(TAG, "Invalid new component state: " + newState);
                    return;
                }
            }
            scheduleWritePackageRestrictionsLocked(userId);
            components = mPendingBroadcasts.get(userId, packageName);
            final boolean newPackage = components == null;
            if (newPackage) {
                components = new ArrayList<String>();
            }
            if (!components.contains(componentName)) {
                components.add(componentName);
            }
            if ((flags&PackageManager.DONT_KILL_APP) == 0) {
                sendNow = true;
                // Purge entry from pending broadcast list if another one exists already
                // since we are sending one right away.
                mPendingBroadcasts.remove(userId, packageName);
            } else {
                if (newPackage) {
                    mPendingBroadcasts.put(userId, packageName, components);
                }
                if (!mHandler.hasMessages(SEND_PENDING_BROADCAST)) {
                    // Schedule a message
                    mHandler.sendEmptyMessageDelayed(SEND_PENDING_BROADCAST, BROADCAST_DELAY);
                }
            }
        }

        long callingId = Binder.clearCallingIdentity();
        try {
            if (sendNow) {
                packageUid = UserHandle.getUid(userId, pkgSetting.appId);
                sendPackageChangedBroadcast(packageName,
                        (flags&PackageManager.DONT_KILL_APP) != 0, components, packageUid);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void sendPackageChangedBroadcast(String packageName,
            boolean killFlag, ArrayList<String> componentNames, int packageUid) {
        if (DEBUG_INSTALL)
            Log.v(TAG, "Sending package changed: package=" + packageName + " components="
                    + componentNames);
        Bundle extras = new Bundle(4);
        extras.putString(Intent.EXTRA_CHANGED_COMPONENT_NAME, componentNames.get(0));
        String nameList[] = new String[componentNames.size()];
        componentNames.toArray(nameList);
        extras.putStringArray(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, nameList);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, killFlag);
        extras.putInt(Intent.EXTRA_UID, packageUid);
        sendPackageBroadcast(Intent.ACTION_PACKAGE_CHANGED,  packageName, extras, null, null,
                new int[] {UserHandle.getUserId(packageUid)});
    }

    @Override
    public void setPackageStoppedState(String packageName, boolean stopped, int userId) {
        if (!sUserManager.exists(userId)) return;
        final int uid = Binder.getCallingUid();
        final int permission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE);
        final boolean allowedByPermission = (permission == PackageManager.PERMISSION_GRANTED);
        enforceCrossUserPermission(uid, userId, true, true, "stop package");
        // writer
        synchronized (mPackages) {
            if (mSettings.setPackageStoppedStateLPw(this, packageName, stopped,
                    allowedByPermission, uid, userId)) {
                scheduleWritePackageRestrictionsLocked(userId);
            }
        }
    }

    @Override
    public String getInstallerPackageName(String packageName) {
        // reader
        synchronized (mPackages) {
            return mSettings.getInstallerPackageNameLPr(packageName);
        }
    }

    @Override
    public int getApplicationEnabledSetting(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;
        int uid = Binder.getCallingUid();
        enforceCrossUserPermission(uid, userId, false, false, "get enabled");
        // reader
        synchronized (mPackages) {
            return mSettings.getApplicationEnabledSettingLPr(packageName, userId);
        }
    }

    @Override
    public int getComponentEnabledSetting(ComponentName componentName, int userId) {
        if (!sUserManager.exists(userId)) return COMPONENT_ENABLED_STATE_DISABLED;
        int uid = Binder.getCallingUid();
        enforceCrossUserPermission(uid, userId, false, false, "get component enabled");
        // reader
        synchronized (mPackages) {
            return mSettings.getComponentEnabledSettingLPr(componentName, userId);
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
        mSystemReady = true;

        // Read the compatibilty setting when the system is ready.
        boolean compatibilityModeEnabled = android.provider.Settings.Global.getInt(
                mContext.getContentResolver(),
                android.provider.Settings.Global.COMPATIBILITY_MODE, 1) == 1;
        PackageParser.setCompatibilityModeEnabled(compatibilityModeEnabled);
        if (DEBUG_SETTINGS) {
            Log.d(TAG, "compatibility mode:" + compatibilityModeEnabled);
        }

        int[] grantPermissionsUserIds = EMPTY_INT_ARRAY;

        synchronized (mPackages) {
            // Verify that all of the preferred activity components actually
            // exist.  It is possible for applications to be updated and at
            // that point remove a previously declared activity component that
            // had been set as a preferred activity.  We try to clean this up
            // the next time we encounter that preferred activity, but it is
            // possible for the user flow to never be able to return to that
            // situation so here we do a sanity check to make sure we haven't
            // left any junk around.
            ArrayList<PreferredActivity> removed = new ArrayList<PreferredActivity>();
            for (int i=0; i<mSettings.mPreferredActivities.size(); i++) {
                PreferredIntentResolver pir = mSettings.mPreferredActivities.valueAt(i);
                removed.clear();
                for (PreferredActivity pa : pir.filterSet()) {
                    if (mActivities.mActivities.get(pa.mPref.mComponent) == null) {
                        removed.add(pa);
                    }
                }
                if (removed.size() > 0) {
                    for (int r=0; r<removed.size(); r++) {
                        PreferredActivity pa = removed.get(r);
                        Slog.w(TAG, "Removing dangling preferred activity: "
                                + pa.mPref.mComponent);
                        pir.removeFilter(pa);
                    }
                    mSettings.writePackageRestrictionsLPr(
                            mSettings.mPreferredActivities.keyAt(i));
                }
            }

            for (int userId : UserManagerService.getInstance().getUserIds()) {
                if (!mSettings.areDefaultRuntimePermissionsGrantedLPr(userId)) {
                    grantPermissionsUserIds = ArrayUtils.appendInt(
                            grantPermissionsUserIds, userId);
                }
            }
        }
        sUserManager.systemReady();

        // If we upgraded grant all default permissions before kicking off.
        for (int userId : grantPermissionsUserIds) {
            mDefaultPermissionPolicy.grantDefaultPermissions(userId);
        }

        // Kick off any messages waiting for system ready
        if (mPostSystemReadyMessages != null) {
            for (Message msg : mPostSystemReadyMessages) {
                msg.sendToTarget();
            }
            mPostSystemReadyMessages = null;
        }

        // Watch for external volumes that come and go over time
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        storage.registerListener(mStorageListener);

        mInstallerService.systemReady();
        mPackageDexOptimizer.systemReady();

        MountServiceInternal mountServiceInternal = LocalServices.getService(
                MountServiceInternal.class);
        mountServiceInternal.addExternalStoragePolicy(
                new MountServiceInternal.ExternalStorageMountPolicy() {
            @Override
            public int getMountMode(int uid, String packageName) {
                if (Process.isIsolated(uid)) {
                    return Zygote.MOUNT_EXTERNAL_NONE;
                }
                if (checkUidPermission(WRITE_MEDIA_STORAGE, uid) == PERMISSION_GRANTED) {
                    return Zygote.MOUNT_EXTERNAL_DEFAULT;
                }
                if (checkUidPermission(READ_EXTERNAL_STORAGE, uid) == PERMISSION_DENIED) {
                    return Zygote.MOUNT_EXTERNAL_DEFAULT;
                }
                if (checkUidPermission(WRITE_EXTERNAL_STORAGE, uid) == PERMISSION_DENIED) {
                    return Zygote.MOUNT_EXTERNAL_READ;
                }
                return Zygote.MOUNT_EXTERNAL_WRITE;
            }

            @Override
            public boolean hasExternalStorage(int uid, String packageName) {
                return true;
            }
        });
    }

    @Override
    public boolean isSafeMode() {
        return mSafeMode;
    }

    @Override
    public boolean hasSystemUidErrors() {
        return mHasSystemUidErrors;
    }

    static String arrayToString(int[] array) {
        StringBuffer buf = new StringBuffer(128);
        buf.append('[');
        if (array != null) {
            for (int i=0; i<array.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(array[i]);
            }
        }
        buf.append(']');
        return buf.toString();
    }

    static class DumpState {
        public static final int DUMP_LIBS = 1 << 0;
        public static final int DUMP_FEATURES = 1 << 1;
        public static final int DUMP_RESOLVERS = 1 << 2;
        public static final int DUMP_PERMISSIONS = 1 << 3;
        public static final int DUMP_PACKAGES = 1 << 4;
        public static final int DUMP_SHARED_USERS = 1 << 5;
        public static final int DUMP_MESSAGES = 1 << 6;
        public static final int DUMP_PROVIDERS = 1 << 7;
        public static final int DUMP_VERIFIERS = 1 << 8;
        public static final int DUMP_PREFERRED = 1 << 9;
        public static final int DUMP_PREFERRED_XML = 1 << 10;
        public static final int DUMP_KEYSETS = 1 << 11;
        public static final int DUMP_VERSION = 1 << 12;
        public static final int DUMP_INSTALLS = 1 << 13;
        public static final int DUMP_INTENT_FILTER_VERIFIERS = 1 << 14;
        public static final int DUMP_DOMAIN_PREFERRED = 1 << 15;

        public static final int OPTION_SHOW_FILTERS = 1 << 0;

        private int mTypes;

        private int mOptions;

        private boolean mTitlePrinted;

        private SharedUserSetting mSharedUser;

        public boolean isDumping(int type) {
            if (mTypes == 0 && type != DUMP_PREFERRED_XML) {
                return true;
            }

            return (mTypes & type) != 0;
        }

        public void setDump(int type) {
            mTypes |= type;
        }

        public boolean isOptionEnabled(int option) {
            return (mOptions & option) != 0;
        }

        public void setOptionEnabled(int option) {
            mOptions |= option;
        }

        public boolean onTitlePrinted() {
            final boolean printed = mTitlePrinted;
            mTitlePrinted = true;
            return printed;
        }

        public boolean getTitlePrinted() {
            return mTitlePrinted;
        }

        public void setTitlePrinted(boolean enabled) {
            mTitlePrinted = enabled;
        }

        public SharedUserSetting getSharedUser() {
            return mSharedUser;
        }

        public void setSharedUser(SharedUserSetting user) {
            mSharedUser = user;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ActivityManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }

        DumpState dumpState = new DumpState();
        boolean fullPreferred = false;
        boolean checkin = false;

        String packageName = null;
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
                pw.println("  [-h] [-f] [--checkin] [cmd] ...");
                pw.println("    --checkin: dump for a checkin");
                pw.println("    -f: print details of intent filters");
                pw.println("    -h: print this help");
                pw.println("  cmd may be one of:");
                pw.println("    l[ibraries]: list known shared libraries");
                pw.println("    f[ibraries]: list device features");
                pw.println("    k[eysets]: print known keysets");
                pw.println("    r[esolvers]: dump intent resolvers");
                pw.println("    perm[issions]: dump permissions");
                pw.println("    permission [name ...]: dump declaration and use of given permission");
                pw.println("    pref[erred]: print preferred package settings");
                pw.println("    preferred-xml [--full]: print preferred package settings as xml");
                pw.println("    prov[iders]: dump content providers");
                pw.println("    p[ackages]: dump installed packages");
                pw.println("    s[hared-users]: dump shared user IDs");
                pw.println("    m[essages]: print collected runtime messages");
                pw.println("    v[erifiers]: print package verifier info");
                pw.println("    d[omain-preferred-apps]: print domains preferred apps");
                pw.println("    i[ntent-filter-verifiers]|ifv: print intent filter verifier info");
                pw.println("    version: print database version info");
                pw.println("    write: write current settings now");
                pw.println("    installs: details about install sessions");
                pw.println("    <package.name>: info about given package");
                return;
            } else if ("--checkin".equals(opt)) {
                checkin = true;
            } else if ("-f".equals(opt)) {
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
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
                packageName = cmd;
                // When dumping a single package, we always dump all of its
                // filter information since the amount of data will be reasonable.
                dumpState.setOptionEnabled(DumpState.OPTION_SHOW_FILTERS);
            } else if ("l".equals(cmd) || "libraries".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_LIBS);
            } else if ("f".equals(cmd) || "features".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_FEATURES);
            } else if ("r".equals(cmd) || "resolvers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_RESOLVERS);
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
                    fullPreferred = true;
                    opti++;
                }
            } else if ("d".equals(cmd) || "domain-preferred-apps".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_DOMAIN_PREFERRED);
            } else if ("p".equals(cmd) || "packages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PACKAGES);
            } else if ("s".equals(cmd) || "shared-users".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_SHARED_USERS);
            } else if ("prov".equals(cmd) || "providers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_PROVIDERS);
            } else if ("m".equals(cmd) || "messages".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_MESSAGES);
            } else if ("v".equals(cmd) || "verifiers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERIFIERS);
            } else if ("i".equals(cmd) || "ifv".equals(cmd)
                    || "intent-filter-verifiers".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_INTENT_FILTER_VERIFIERS);
            } else if ("version".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_VERSION);
            } else if ("k".equals(cmd) || "keysets".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_KEYSETS);
            } else if ("installs".equals(cmd)) {
                dumpState.setDump(DumpState.DUMP_INSTALLS);
            } else if ("write".equals(cmd)) {
                synchronized (mPackages) {
                    mSettings.writeLPr();
                    pw.println("Settings written.");
                    return;
                }
            }
        }

        if (checkin) {
            pw.println("vers,1");
        }

        // reader
        synchronized (mPackages) {
            if (dumpState.isDumping(DumpState.DUMP_VERSION) && packageName == null) {
                if (!checkin) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Database versions:");
                    mSettings.dumpVersionLPr(new IndentingPrintWriter(pw, "  "));
                }
            }

            if (dumpState.isDumping(DumpState.DUMP_VERIFIERS) && packageName == null) {
                if (!checkin) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Verifiers:");
                    pw.print("  Required: ");
                    pw.print(mRequiredVerifierPackage);
                    pw.print(" (uid=");
                    pw.print(getPackageUid(mRequiredVerifierPackage, 0));
                    pw.println(")");
                } else if (mRequiredVerifierPackage != null) {
                    pw.print("vrfy,"); pw.print(mRequiredVerifierPackage);
                    pw.print(","); pw.println(getPackageUid(mRequiredVerifierPackage, 0));
                }
            }

            if (dumpState.isDumping(DumpState.DUMP_INTENT_FILTER_VERIFIERS) &&
                    packageName == null) {
                if (mIntentFilterVerifierComponent != null) {
                    String verifierPackageName = mIntentFilterVerifierComponent.getPackageName();
                    if (!checkin) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("Intent Filter Verifier:");
                        pw.print("  Using: ");
                        pw.print(verifierPackageName);
                        pw.print(" (uid=");
                        pw.print(getPackageUid(verifierPackageName, 0));
                        pw.println(")");
                    } else if (verifierPackageName != null) {
                        pw.print("ifv,"); pw.print(verifierPackageName);
                        pw.print(","); pw.println(getPackageUid(verifierPackageName, 0));
                    }
                } else {
                    pw.println();
                    pw.println("No Intent Filter Verifier available!");
                }
            }

            if (dumpState.isDumping(DumpState.DUMP_LIBS) && packageName == null) {
                boolean printedHeader = false;
                final Iterator<String> it = mSharedLibraries.keySet().iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    SharedLibraryEntry ent = mSharedLibraries.get(name);
                    if (!checkin) {
                        if (!printedHeader) {
                            if (dumpState.onTitlePrinted())
                                pw.println();
                            pw.println("Libraries:");
                            printedHeader = true;
                        }
                        pw.print("  ");
                    } else {
                        pw.print("lib,");
                    }
                    pw.print(name);
                    if (!checkin) {
                        pw.print(" -> ");
                    }
                    if (ent.path != null) {
                        if (!checkin) {
                            pw.print("(jar) ");
                            pw.print(ent.path);
                        } else {
                            pw.print(",jar,");
                            pw.print(ent.path);
                        }
                    } else {
                        if (!checkin) {
                            pw.print("(apk) ");
                            pw.print(ent.apk);
                        } else {
                            pw.print(",apk,");
                            pw.print(ent.apk);
                        }
                    }
                    pw.println();
                }
            }

            if (dumpState.isDumping(DumpState.DUMP_FEATURES) && packageName == null) {
                if (dumpState.onTitlePrinted())
                    pw.println();
                if (!checkin) {
                    pw.println("Features:");
                }
                Iterator<String> it = mAvailableFeatures.keySet().iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    if (!checkin) {
                        pw.print("  ");
                    } else {
                        pw.print("feat,");
                    }
                    pw.println(name);
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_RESOLVERS)) {
                if (mActivities.dump(pw, dumpState.getTitlePrinted() ? "\nActivity Resolver Table:"
                        : "Activity Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
                    dumpState.setTitlePrinted(true);
                }
                if (mReceivers.dump(pw, dumpState.getTitlePrinted() ? "\nReceiver Resolver Table:"
                        : "Receiver Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
                    dumpState.setTitlePrinted(true);
                }
                if (mServices.dump(pw, dumpState.getTitlePrinted() ? "\nService Resolver Table:"
                        : "Service Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
                    dumpState.setTitlePrinted(true);
                }
                if (mProviders.dump(pw, dumpState.getTitlePrinted() ? "\nProvider Resolver Table:"
                        : "Provider Resolver Table:", "  ", packageName,
                        dumpState.isOptionEnabled(DumpState.OPTION_SHOW_FILTERS), true)) {
                    dumpState.setTitlePrinted(true);
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PREFERRED)) {
                for (int i=0; i<mSettings.mPreferredActivities.size(); i++) {
                    PreferredIntentResolver pir = mSettings.mPreferredActivities.valueAt(i);
                    int user = mSettings.mPreferredActivities.keyAt(i);
                    if (pir.dump(pw,
                            dumpState.getTitlePrinted()
                                ? "\nPreferred Activities User " + user + ":"
                                : "Preferred Activities User " + user + ":", "  ",
                            packageName, true, false)) {
                        dumpState.setTitlePrinted(true);
                    }
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PREFERRED_XML)) {
                pw.flush();
                FileOutputStream fout = new FileOutputStream(fd);
                BufferedOutputStream str = new BufferedOutputStream(fout);
                XmlSerializer serializer = new FastXmlSerializer();
                try {
                    serializer.setOutput(str, StandardCharsets.UTF_8.name());
                    serializer.startDocument(null, true);
                    serializer.setFeature(
                            "http://xmlpull.org/v1/doc/features.html#indent-output", true);
                    mSettings.writePreferredActivitiesLPr(serializer, 0, fullPreferred);
                    serializer.endDocument();
                    serializer.flush();
                } catch (IllegalArgumentException e) {
                    pw.println("Failed writing: " + e);
                } catch (IllegalStateException e) {
                    pw.println("Failed writing: " + e);
                } catch (IOException e) {
                    pw.println("Failed writing: " + e);
                }
            }

            if (!checkin
                    && dumpState.isDumping(DumpState.DUMP_DOMAIN_PREFERRED)
                    && packageName == null) {
                pw.println();
                int count = mSettings.mPackages.size();
                if (count == 0) {
                    pw.println("No applications!");
                    pw.println();
                } else {
                    final String prefix = "  ";
                    Collection<PackageSetting> allPackageSettings = mSettings.mPackages.values();
                    if (allPackageSettings.size() == 0) {
                        pw.println("No domain preferred apps!");
                        pw.println();
                    } else {
                        pw.println("App verification status:");
                        pw.println();
                        count = 0;
                        for (PackageSetting ps : allPackageSettings) {
                            IntentFilterVerificationInfo ivi = ps.getIntentFilterVerificationInfo();
                            if (ivi == null || ivi.getPackageName() == null) continue;
                            pw.println(prefix + "Package: " + ivi.getPackageName());
                            pw.println(prefix + "Domains: " + ivi.getDomainsString());
                            pw.println(prefix + "Status:  " + ivi.getStatusString());
                            pw.println();
                            count++;
                        }
                        if (count == 0) {
                            pw.println(prefix + "No app verification established.");
                            pw.println();
                        }
                        for (int userId : sUserManager.getUserIds()) {
                            pw.println("App linkages for user " + userId + ":");
                            pw.println();
                            count = 0;
                            for (PackageSetting ps : allPackageSettings) {
                                final long status = ps.getDomainVerificationStatusForUser(userId);
                                if (status >> 32 == INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED) {
                                    continue;
                                }
                                pw.println(prefix + "Package: " + ps.name);
                                pw.println(prefix + "Domains: " + dumpDomainString(ps.name));
                                String statusStr = IntentFilterVerificationInfo.
                                        getStatusStringFromValue(status);
                                pw.println(prefix + "Status:  " + statusStr);
                                pw.println();
                                count++;
                            }
                            if (count == 0) {
                                pw.println(prefix + "No configured app linkages.");
                                pw.println();
                            }
                        }
                    }
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PERMISSIONS)) {
                mSettings.dumpPermissionsLPr(pw, packageName, permissionNames, dumpState);
                if (packageName == null && permissionNames == null) {
                    for (int iperm=0; iperm<mAppOpPermissionPackages.size(); iperm++) {
                        if (iperm == 0) {
                            if (dumpState.onTitlePrinted())
                                pw.println();
                            pw.println("AppOp Permissions:");
                        }
                        pw.print("  AppOp Permission ");
                        pw.print(mAppOpPermissionPackages.keyAt(iperm));
                        pw.println(":");
                        ArraySet<String> pkgs = mAppOpPermissionPackages.valueAt(iperm);
                        for (int ipkg=0; ipkg<pkgs.size(); ipkg++) {
                            pw.print("    "); pw.println(pkgs.valueAt(ipkg));
                        }
                    }
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_PROVIDERS)) {
                boolean printedSomething = false;
                for (PackageParser.Provider p : mProviders.mProviders.values()) {
                    if (packageName != null && !packageName.equals(p.info.packageName)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("Registered ContentProviders:");
                        printedSomething = true;
                    }
                    pw.print("  "); p.printComponentShortName(pw); pw.println(":");
                    pw.print("    "); pw.println(p.toString());
                }
                printedSomething = false;
                for (Map.Entry<String, PackageParser.Provider> entry :
                        mProvidersByAuthority.entrySet()) {
                    PackageParser.Provider p = entry.getValue();
                    if (packageName != null && !packageName.equals(p.info.packageName)) {
                        continue;
                    }
                    if (!printedSomething) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("ContentProvider Authorities:");
                        printedSomething = true;
                    }
                    pw.print("  ["); pw.print(entry.getKey()); pw.println("]:");
                    pw.print("    "); pw.println(p.toString());
                    if (p.info != null && p.info.applicationInfo != null) {
                        final String appInfo = p.info.applicationInfo.toString();
                        pw.print("      applicationInfo="); pw.println(appInfo);
                    }
                }
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_KEYSETS)) {
                mSettings.mKeySetManagerService.dumpLPr(pw, packageName, dumpState);
            }

            if (dumpState.isDumping(DumpState.DUMP_PACKAGES)) {
                mSettings.dumpPackagesLPr(pw, packageName, permissionNames, dumpState, checkin);
            }

            if (dumpState.isDumping(DumpState.DUMP_SHARED_USERS)) {
                mSettings.dumpSharedUsersLPr(pw, packageName, permissionNames, dumpState, checkin);
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_INSTALLS) && packageName == null) {
                // XXX should handle packageName != null by dumping only install data that
                // the given package is involved with.
                if (dumpState.onTitlePrinted()) pw.println();
                mInstallerService.dump(new IndentingPrintWriter(pw, "  ", 120));
            }

            if (!checkin && dumpState.isDumping(DumpState.DUMP_MESSAGES) && packageName == null) {
                if (dumpState.onTitlePrinted()) pw.println();
                mSettings.dumpReadMessagesLPr(pw, dumpState);

                pw.println();
                pw.println("Package warning messages:");
                BufferedReader in = null;
                String line = null;
                try {
                    in = new BufferedReader(new FileReader(getSettingsProblemFile()));
                    while ((line = in.readLine()) != null) {
                        if (line.contains("ignored: updated version")) continue;
                        pw.println(line);
                    }
                } catch (IOException ignored) {
                } finally {
                    IoUtils.closeQuietly(in);
                }
            }

            if (checkin && dumpState.isDumping(DumpState.DUMP_MESSAGES)) {
                BufferedReader in = null;
                String line = null;
                try {
                    in = new BufferedReader(new FileReader(getSettingsProblemFile()));
                    while ((line = in.readLine()) != null) {
                        if (line.contains("ignored: updated version")) continue;
                        pw.print("msg,");
                        pw.println(line);
                    }
                } catch (IOException ignored) {
                } finally {
                    IoUtils.closeQuietly(in);
                }
            }
        }
    }

    private String dumpDomainString(String packageName) {
        List<IntentFilterVerificationInfo> iviList = getIntentFilterVerifications(packageName);
        List<IntentFilter> filters = getAllIntentFilters(packageName);

        ArraySet<String> result = new ArraySet<>();
        if (iviList.size() > 0) {
            for (IntentFilterVerificationInfo ivi : iviList) {
                for (String host : ivi.getDomains()) {
                    result.add(host);
                }
            }
        }
        if (filters != null && filters.size() > 0) {
            for (IntentFilter filter : filters) {
                if (filter.hasCategory(Intent.CATEGORY_BROWSABLE)
                        && (filter.hasDataScheme(IntentFilter.SCHEME_HTTP) ||
                                filter.hasDataScheme(IntentFilter.SCHEME_HTTPS))) {
                    result.addAll(filter.getHostsList());
                }
            }
        }

        StringBuilder sb = new StringBuilder(result.size() * 16);
        for (String domain : result) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(domain);
        }
        return sb.toString();
    }

    // ------- apps on sdcard specific code -------
    static final boolean DEBUG_SD_INSTALL = false;

    private static final String SD_ENCRYPTION_KEYSTORE_NAME = "AppsOnSD";

    private static final String SD_ENCRYPTION_ALGORITHM = "AES";

    private boolean mMediaMounted = false;

    static String getEncryptKey() {
        try {
            String sdEncKey = SystemKeyStore.getInstance().retrieveKeyHexString(
                    SD_ENCRYPTION_KEYSTORE_NAME);
            if (sdEncKey == null) {
                sdEncKey = SystemKeyStore.getInstance().generateNewKeyHexString(128,
                        SD_ENCRYPTION_ALGORITHM, SD_ENCRYPTION_KEYSTORE_NAME);
                if (sdEncKey == null) {
                    Slog.e(TAG, "Failed to create encryption keys");
                    return null;
                }
            }
            return sdEncKey;
        } catch (NoSuchAlgorithmException nsae) {
            Slog.e(TAG, "Failed to create encryption keys with exception: " + nsae);
            return null;
        } catch (IOException ioe) {
            Slog.e(TAG, "Failed to retrieve encryption keys with exception: " + ioe);
            return null;
        }
    }

    /*
     * Update media status on PackageManager.
     */
    @Override
    public void updateExternalMediaStatus(final boolean mediaStatus, final boolean reportStatus) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException("Media status can only be updated by the system");
        }
        // reader; this apparently protects mMediaMounted, but should probably
        // be a different lock in that case.
        synchronized (mPackages) {
            Log.i(TAG, "Updating external media status from "
                    + (mMediaMounted ? "mounted" : "unmounted") + " to "
                    + (mediaStatus ? "mounted" : "unmounted"));
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "updateExternalMediaStatus:: mediaStatus=" + mediaStatus
                        + ", mMediaMounted=" + mMediaMounted);
            if (mediaStatus == mMediaMounted) {
                final Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS, reportStatus ? 1
                        : 0, -1);
                mHandler.sendMessage(msg);
                return;
            }
            mMediaMounted = mediaStatus;
        }
        // Queue up an async operation since the package installation may take a
        // little while.
        mHandler.post(new Runnable() {
            public void run() {
                updateExternalMediaStatusInner(mediaStatus, reportStatus, true);
            }
        });
    }

    /**
     * Called by MountService when the initial ASECs to scan are available.
     * Should block until all the ASEC containers are finished being scanned.
     */
    public void scanAvailableAsecs() {
        updateExternalMediaStatusInner(true, false, false);
        if (mShouldRestoreconData) {
            SELinuxMMAC.setRestoreconDone();
            mShouldRestoreconData = false;
        }
    }

    /*
     * Collect information of applications on external media, map them against
     * existing containers and update information based on current mount status.
     * Please note that we always have to report status if reportStatus has been
     * set to true especially when unloading packages.
     */
    private void updateExternalMediaStatusInner(boolean isMounted, boolean reportStatus,
            boolean externalStorage) {
        ArrayMap<AsecInstallArgs, String> processCids = new ArrayMap<>();
        int[] uidArr = EmptyArray.INT;

        final String[] list = PackageHelper.getSecureContainerList();
        if (ArrayUtils.isEmpty(list)) {
            Log.i(TAG, "No secure containers found");
        } else {
            // Process list of secure containers and categorize them
            // as active or stale based on their package internal state.

            // reader
            synchronized (mPackages) {
                for (String cid : list) {
                    // Leave stages untouched for now; installer service owns them
                    if (PackageInstallerService.isStageName(cid)) continue;

                    if (DEBUG_SD_INSTALL)
                        Log.i(TAG, "Processing container " + cid);
                    String pkgName = getAsecPackageName(cid);
                    if (pkgName == null) {
                        Slog.i(TAG, "Found stale container " + cid + " with no package name");
                        continue;
                    }
                    if (DEBUG_SD_INSTALL)
                        Log.i(TAG, "Looking for pkg : " + pkgName);

                    final PackageSetting ps = mSettings.mPackages.get(pkgName);
                    if (ps == null) {
                        Slog.i(TAG, "Found stale container " + cid + " with no matching settings");
                        continue;
                    }

                    /*
                     * Skip packages that are not external if we're unmounting
                     * external storage.
                     */
                    if (externalStorage && !isMounted && !isExternal(ps)) {
                        continue;
                    }

                    final AsecInstallArgs args = new AsecInstallArgs(cid,
                            getAppDexInstructionSets(ps), ps.isForwardLocked());
                    // The package status is changed only if the code path
                    // matches between settings and the container id.
                    if (ps.codePathString != null
                            && ps.codePathString.startsWith(args.getCodePath())) {
                        if (DEBUG_SD_INSTALL) {
                            Log.i(TAG, "Container : " + cid + " corresponds to pkg : " + pkgName
                                    + " at code path: " + ps.codePathString);
                        }

                        // We do have a valid package installed on sdcard
                        processCids.put(args, ps.codePathString);
                        final int uid = ps.appId;
                        if (uid != -1) {
                            uidArr = ArrayUtils.appendInt(uidArr, uid);
                        }
                    } else {
                        Slog.i(TAG, "Found stale container " + cid + ": expected codePath="
                                + ps.codePathString);
                    }
                }
            }

            Arrays.sort(uidArr);
        }

        // Process packages with valid entries.
        if (isMounted) {
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Loading packages");
            loadMediaPackages(processCids, uidArr);
            startCleaningPackages();
            mInstallerService.onSecureContainersAvailable();
        } else {
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Unloading packages");
            unloadMediaPackages(processCids, uidArr, reportStatus);
        }
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
            ArrayList<ApplicationInfo> infos, IIntentReceiver finishedReceiver) {
        final int size = infos.size();
        final String[] packageNames = new String[size];
        final int[] packageUids = new int[size];
        for (int i = 0; i < size; i++) {
            final ApplicationInfo info = infos.get(i);
            packageNames[i] = info.packageName;
            packageUids[i] = info.uid;
        }
        sendResourcesChangedBroadcast(mediaStatus, replacing, packageNames, packageUids,
                finishedReceiver);
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
            ArrayList<String> pkgList, int uidArr[], IIntentReceiver finishedReceiver) {
        sendResourcesChangedBroadcast(mediaStatus, replacing,
                pkgList.toArray(new String[pkgList.size()]), uidArr, finishedReceiver);
    }

    private void sendResourcesChangedBroadcast(boolean mediaStatus, boolean replacing,
            String[] pkgList, int uidArr[], IIntentReceiver finishedReceiver) {
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
            sendPackageBroadcast(action, null, extras, null, finishedReceiver, null);
        }
    }

   /*
     * Look at potentially valid container ids from processCids If package
     * information doesn't match the one on record or package scanning fails,
     * the cid is added to list of removeCids. We currently don't delete stale
     * containers.
     */
    private void loadMediaPackages(ArrayMap<AsecInstallArgs, String> processCids, int[] uidArr) {
        ArrayList<String> pkgList = new ArrayList<String>();
        Set<AsecInstallArgs> keys = processCids.keySet();

        for (AsecInstallArgs args : keys) {
            String codePath = processCids.get(args);
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Loading container : " + args.cid);
            int retCode = PackageManager.INSTALL_FAILED_CONTAINER_ERROR;
            try {
                // Make sure there are no container errors first.
                if (args.doPreInstall(PackageManager.INSTALL_SUCCEEDED) != PackageManager.INSTALL_SUCCEEDED) {
                    Slog.e(TAG, "Failed to mount cid : " + args.cid
                            + " when installing from sdcard");
                    continue;
                }
                // Check code path here.
                if (codePath == null || !codePath.startsWith(args.getCodePath())) {
                    Slog.e(TAG, "Container " + args.cid + " cachepath " + args.getCodePath()
                            + " does not match one in settings " + codePath);
                    continue;
                }
                // Parse package
                int parseFlags = mDefParseFlags;
                if (args.isExternalAsec()) {
                    parseFlags |= PackageParser.PARSE_EXTERNAL_STORAGE;
                }
                if (args.isFwdLocked()) {
                    parseFlags |= PackageParser.PARSE_FORWARD_LOCK;
                }

                synchronized (mInstallLock) {
                    PackageParser.Package pkg = null;
                    try {
                        pkg = scanPackageLI(new File(codePath), parseFlags, 0, 0, null);
                    } catch (PackageManagerException e) {
                        Slog.w(TAG, "Failed to scan " + codePath + ": " + e.getMessage());
                    }
                    // Scan the package
                    if (pkg != null) {
                        /*
                         * TODO why is the lock being held? doPostInstall is
                         * called in other places without the lock. This needs
                         * to be straightened out.
                         */
                        // writer
                        synchronized (mPackages) {
                            retCode = PackageManager.INSTALL_SUCCEEDED;
                            pkgList.add(pkg.packageName);
                            // Post process args
                            args.doPostInstall(PackageManager.INSTALL_SUCCEEDED,
                                    pkg.applicationInfo.uid);
                        }
                    } else {
                        Slog.i(TAG, "Failed to install pkg from  " + codePath + " from sdcard");
                    }
                }

            } finally {
                if (retCode != PackageManager.INSTALL_SUCCEEDED) {
                    Log.w(TAG, "Container " + args.cid + " is stale, retCode=" + retCode);
                }
            }
        }
        // writer
        synchronized (mPackages) {
            // If the platform SDK has changed since the last time we booted,
            // we need to re-grant app permission to catch any new ones that
            // appear. This is really a hack, and means that apps can in some
            // cases get permissions that the user didn't initially explicitly
            // allow... it would be nice to have some better way to handle
            // this situation.
            final VersionInfo ver = mSettings.getExternalVersion();

            int updateFlags = UPDATE_PERMISSIONS_ALL;
            if (ver.sdkVersion != mSdkVersion) {
                logCriticalInfo(Log.INFO, "Platform changed from " + ver.sdkVersion + " to "
                        + mSdkVersion + "; regranting permissions for external");
                updateFlags |= UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL;
            }
            updatePermissionsLPw(null, null, updateFlags);

            // Yay, everything is now upgraded
            ver.forceCurrent();

            // can downgrade to reader
            // Persist settings
            mSettings.writeLPr();
        }
        // Send a broadcast to let everyone know we are done processing
        if (pkgList.size() > 0) {
            sendResourcesChangedBroadcast(true, false, pkgList, uidArr, null);
        }
    }

   /*
     * Utility method to unload a list of specified containers
     */
    private void unloadAllContainers(Set<AsecInstallArgs> cidArgs) {
        // Just unmount all valid containers.
        for (AsecInstallArgs arg : cidArgs) {
            synchronized (mInstallLock) {
                arg.doPostDeleteLI(false);
           }
       }
   }

    /*
     * Unload packages mounted on external media. This involves deleting package
     * data from internal structures, sending broadcasts about diabled packages,
     * gc'ing to free up references, unmounting all secure containers
     * corresponding to packages on external media, and posting a
     * UPDATED_MEDIA_STATUS message if status has been requested. Please note
     * that we always have to post this message if status has been requested no
     * matter what.
     */
    private void unloadMediaPackages(ArrayMap<AsecInstallArgs, String> processCids, int uidArr[],
            final boolean reportStatus) {
        if (DEBUG_SD_INSTALL)
            Log.i(TAG, "unloading media packages");
        ArrayList<String> pkgList = new ArrayList<String>();
        ArrayList<AsecInstallArgs> failedList = new ArrayList<AsecInstallArgs>();
        final Set<AsecInstallArgs> keys = processCids.keySet();
        for (AsecInstallArgs args : keys) {
            String pkgName = args.getPackageName();
            if (DEBUG_SD_INSTALL)
                Log.i(TAG, "Trying to unload pkg : " + pkgName);
            // Delete package internally
            PackageRemovedInfo outInfo = new PackageRemovedInfo();
            synchronized (mInstallLock) {
                boolean res = deletePackageLI(pkgName, null, false, null, null,
                        PackageManager.DELETE_KEEP_DATA, outInfo, false);
                if (res) {
                    pkgList.add(pkgName);
                } else {
                    Slog.e(TAG, "Failed to delete pkg from sdcard : " + pkgName);
                    failedList.add(args);
                }
            }
        }

        // reader
        synchronized (mPackages) {
            // We didn't update the settings after removing each package;
            // write them now for all packages.
            mSettings.writeLPr();
        }

        // We have to absolutely send UPDATED_MEDIA_STATUS only
        // after confirming that all the receivers processed the ordered
        // broadcast when packages get disabled, force a gc to clean things up.
        // and unload all the containers.
        if (pkgList.size() > 0) {
            sendResourcesChangedBroadcast(false, false, pkgList, uidArr,
                    new IIntentReceiver.Stub() {
                public void performReceive(Intent intent, int resultCode, String data,
                        Bundle extras, boolean ordered, boolean sticky,
                        int sendingUser) throws RemoteException {
                    Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS,
                            reportStatus ? 1 : 0, 1, keys);
                    mHandler.sendMessage(msg);
                }
            });
        } else {
            Message msg = mHandler.obtainMessage(UPDATED_MEDIA_STATUS, reportStatus ? 1 : 0, -1,
                    keys);
            mHandler.sendMessage(msg);
        }
    }

    private void loadPrivatePackages(VolumeInfo vol) {
        final ArrayList<ApplicationInfo> loaded = new ArrayList<>();
        final int parseFlags = mDefParseFlags | PackageParser.PARSE_EXTERNAL_STORAGE;
        synchronized (mInstallLock) {
        synchronized (mPackages) {
            final VersionInfo ver = mSettings.findOrCreateVersion(vol.fsUuid);
            final List<PackageSetting> packages = mSettings.getVolumePackagesLPr(vol.fsUuid);
            for (PackageSetting ps : packages) {
                final PackageParser.Package pkg;
                try {
                    pkg = scanPackageLI(ps.codePath, parseFlags, SCAN_INITIAL, 0L, null);
                    loaded.add(pkg.applicationInfo);
                } catch (PackageManagerException e) {
                    Slog.w(TAG, "Failed to scan " + ps.codePath + ": " + e.getMessage());
                }

                if (!Build.FINGERPRINT.equals(ver.fingerprint)) {
                    deleteCodeCacheDirsLI(ps.volumeUuid, ps.name);
                }
            }

            int updateFlags = UPDATE_PERMISSIONS_ALL;
            if (ver.sdkVersion != mSdkVersion) {
                logCriticalInfo(Log.INFO, "Platform changed from " + ver.sdkVersion + " to "
                        + mSdkVersion + "; regranting permissions for " + vol.fsUuid);
                updateFlags |= UPDATE_PERMISSIONS_REPLACE_PKG | UPDATE_PERMISSIONS_REPLACE_ALL;
            }
            updatePermissionsLPw(null, null, updateFlags);

            // Yay, everything is now upgraded
            ver.forceCurrent();

            mSettings.writeLPr();
        }
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "Loaded packages " + loaded);
        sendResourcesChangedBroadcast(true, false, loaded, null);
    }

    private void unloadPrivatePackages(VolumeInfo vol) {
        final ArrayList<ApplicationInfo> unloaded = new ArrayList<>();
        synchronized (mInstallLock) {
        synchronized (mPackages) {
            final List<PackageSetting> packages = mSettings.getVolumePackagesLPr(vol.fsUuid);
            for (PackageSetting ps : packages) {
                if (ps.pkg == null) continue;

                final ApplicationInfo info = ps.pkg.applicationInfo;
                final PackageRemovedInfo outInfo = new PackageRemovedInfo();
                if (deletePackageLI(ps.name, null, false, null, null,
                        PackageManager.DELETE_KEEP_DATA, outInfo, false)) {
                    unloaded.add(info);
                } else {
                    Slog.w(TAG, "Failed to unload " + ps.codePath);
                }
            }

            mSettings.writeLPr();
        }
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "Unloaded packages " + unloaded);
        sendResourcesChangedBroadcast(false, false, unloaded, null);
    }

    /**
     * Examine all users present on given mounted volume, and destroy data
     * belonging to users that are no longer valid, or whose user ID has been
     * recycled.
     */
    private void reconcileUsers(String volumeUuid) {
        final File[] files = FileUtils
                .listFilesOrEmpty(Environment.getDataUserDirectory(volumeUuid));
        for (File file : files) {
            if (!file.isDirectory()) continue;

            final int userId;
            final UserInfo info;
            try {
                userId = Integer.parseInt(file.getName());
                info = sUserManager.getUserInfo(userId);
            } catch (NumberFormatException e) {
                Slog.w(TAG, "Invalid user directory " + file);
                continue;
            }

            boolean destroyUser = false;
            if (info == null) {
                logCriticalInfo(Log.WARN, "Destroying user directory " + file
                        + " because no matching user was found");
                destroyUser = true;
            } else {
                try {
                    UserManagerService.enforceSerialNumber(file, info.serialNumber);
                } catch (IOException e) {
                    logCriticalInfo(Log.WARN, "Destroying user directory " + file
                            + " because we failed to enforce serial number: " + e);
                    destroyUser = true;
                }
            }

            if (destroyUser) {
                synchronized (mInstallLock) {
                    mInstaller.removeUserDataDirs(volumeUuid, userId);
                }
            }
        }

        final UserManager um = mContext.getSystemService(UserManager.class);
        for (UserInfo user : um.getUsers()) {
            final File userDir = Environment.getDataUserDirectory(volumeUuid, user.id);
            if (userDir.exists()) continue;

            try {
                UserManagerService.prepareUserDirectory(userDir);
                UserManagerService.enforceSerialNumber(userDir, user.serialNumber);
            } catch (IOException e) {
                Log.wtf(TAG, "Failed to create user directory on " + volumeUuid, e);
            }
        }
    }

    /**
     * Examine all apps present on given mounted volume, and destroy apps that
     * aren't expected, either due to uninstallation or reinstallation on
     * another volume.
     */
    private void reconcileApps(String volumeUuid) {
        final File[] files = FileUtils
                .listFilesOrEmpty(Environment.getDataAppDirectory(volumeUuid));
        for (File file : files) {
            final boolean isPackage = (isApkFile(file) || file.isDirectory())
                    && !PackageInstallerService.isStageName(file.getName());
            if (!isPackage) {
                // Ignore entries which are not packages
                continue;
            }

            boolean destroyApp = false;
            String packageName = null;
            try {
                final PackageLite pkg = PackageParser.parsePackageLite(file,
                        PackageParser.PARSE_MUST_BE_APK);
                packageName = pkg.packageName;

                synchronized (mPackages) {
                    final PackageSetting ps = mSettings.mPackages.get(packageName);
                    if (ps == null) {
                        logCriticalInfo(Log.WARN, "Destroying " + packageName + " on + "
                                + volumeUuid + " because we found no install record");
                        destroyApp = true;
                    } else if (!TextUtils.equals(volumeUuid, ps.volumeUuid)) {
                        logCriticalInfo(Log.WARN, "Destroying " + packageName + " on "
                                + volumeUuid + " because we expected it on " + ps.volumeUuid);
                        destroyApp = true;
                    }
                }

            } catch (PackageParserException e) {
                logCriticalInfo(Log.WARN, "Destroying " + file + " due to parse failure: " + e);
                destroyApp = true;
            }

            if (destroyApp) {
                synchronized (mInstallLock) {
                    if (packageName != null) {
                        removeDataDirsLI(volumeUuid, packageName);
                    }
                    if (file.isDirectory()) {
                        mInstaller.rmPackageDir(file.getAbsolutePath());
                    } else {
                        file.delete();
                    }
                }
            }
        }
    }

    private void unfreezePackage(String packageName) {
        synchronized (mPackages) {
            final PackageSetting ps = mSettings.mPackages.get(packageName);
            if (ps != null) {
                ps.frozen = false;
            }
        }
    }

    @Override
    public int movePackage(final String packageName, final String volumeUuid) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MOVE_PACKAGE, null);

        final int moveId = mNextMoveId.getAndIncrement();
        try {
            movePackageInternal(packageName, volumeUuid, moveId);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to move " + packageName, e);
            mMoveCallbacks.notifyStatusChanged(moveId,
                    PackageManager.MOVE_FAILED_INTERNAL_ERROR);
        }
        return moveId;
    }

    private void movePackageInternal(final String packageName, final String volumeUuid,
            final int moveId) throws PackageManagerException {
        final UserHandle user = new UserHandle(UserHandle.getCallingUserId());
        final StorageManager storage = mContext.getSystemService(StorageManager.class);
        final PackageManager pm = mContext.getPackageManager();

        final boolean currentAsec;
        final String currentVolumeUuid;
        final File codeFile;
        final String installerPackageName;
        final String packageAbiOverride;
        final int appId;
        final String seinfo;
        final String label;

        // reader
        synchronized (mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            final PackageSetting ps = mSettings.mPackages.get(packageName);
            if (pkg == null || ps == null) {
                throw new PackageManagerException(MOVE_FAILED_DOESNT_EXIST, "Missing package");
            }

            if (pkg.applicationInfo.isSystemApp()) {
                throw new PackageManagerException(MOVE_FAILED_SYSTEM_PACKAGE,
                        "Cannot move system application");
            }

            if (pkg.applicationInfo.isExternalAsec()) {
                currentAsec = true;
                currentVolumeUuid = StorageManager.UUID_PRIMARY_PHYSICAL;
            } else if (pkg.applicationInfo.isForwardLocked()) {
                currentAsec = true;
                currentVolumeUuid = "forward_locked";
            } else {
                currentAsec = false;
                currentVolumeUuid = ps.volumeUuid;

                final File probe = new File(pkg.codePath);
                final File probeOat = new File(probe, "oat");
                if (!probe.isDirectory() || !probeOat.isDirectory()) {
                    throw new PackageManagerException(MOVE_FAILED_INTERNAL_ERROR,
                            "Move only supported for modern cluster style installs");
                }
            }

            if (Objects.equals(currentVolumeUuid, volumeUuid)) {
                throw new PackageManagerException(MOVE_FAILED_INTERNAL_ERROR,
                        "Package already moved to " + volumeUuid);
            }

            if (ps.frozen) {
                throw new PackageManagerException(MOVE_FAILED_OPERATION_PENDING,
                        "Failed to move already frozen package");
            }
            ps.frozen = true;

            codeFile = new File(pkg.codePath);
            installerPackageName = ps.installerPackageName;
            packageAbiOverride = ps.cpuAbiOverrideString;
            appId = UserHandle.getAppId(pkg.applicationInfo.uid);
            seinfo = pkg.applicationInfo.seinfo;
            label = String.valueOf(pm.getApplicationLabel(pkg.applicationInfo));
        }

        // Now that we're guarded by frozen state, kill app during move
        final long token = Binder.clearCallingIdentity();
        try {
            killApplication(packageName, appId, "move pkg");
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        final Bundle extras = new Bundle();
        extras.putString(Intent.EXTRA_PACKAGE_NAME, packageName);
        extras.putString(Intent.EXTRA_TITLE, label);
        mMoveCallbacks.notifyCreated(moveId, extras);

        int installFlags;
        final boolean moveCompleteApp;
        final File measurePath;

        if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid)) {
            installFlags = INSTALL_INTERNAL;
            moveCompleteApp = !currentAsec;
            measurePath = Environment.getDataAppDirectory(volumeUuid);
        } else if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
            installFlags = INSTALL_EXTERNAL;
            moveCompleteApp = false;
            measurePath = storage.getPrimaryPhysicalVolume().getPath();
        } else {
            final VolumeInfo volume = storage.findVolumeByUuid(volumeUuid);
            if (volume == null || volume.getType() != VolumeInfo.TYPE_PRIVATE
                    || !volume.isMountedWritable()) {
                unfreezePackage(packageName);
                throw new PackageManagerException(MOVE_FAILED_INTERNAL_ERROR,
                        "Move location not mounted private volume");
            }

            Preconditions.checkState(!currentAsec);

            installFlags = INSTALL_INTERNAL;
            moveCompleteApp = true;
            measurePath = Environment.getDataAppDirectory(volumeUuid);
        }

        final PackageStats stats = new PackageStats(null, -1);
        synchronized (mInstaller) {
            if (!getPackageSizeInfoLI(packageName, -1, stats)) {
                unfreezePackage(packageName);
                throw new PackageManagerException(MOVE_FAILED_INTERNAL_ERROR,
                        "Failed to measure package size");
            }
        }

        if (DEBUG_INSTALL) Slog.d(TAG, "Measured code size " + stats.codeSize + ", data size "
                + stats.dataSize);

        final long startFreeBytes = measurePath.getFreeSpace();
        final long sizeBytes;
        if (moveCompleteApp) {
            sizeBytes = stats.codeSize + stats.dataSize;
        } else {
            sizeBytes = stats.codeSize;
        }

        if (sizeBytes > storage.getStorageBytesUntilLow(measurePath)) {
            unfreezePackage(packageName);
            throw new PackageManagerException(MOVE_FAILED_INTERNAL_ERROR,
                    "Not enough free space to move");
        }

        mMoveCallbacks.notifyStatusChanged(moveId, 10);

        final CountDownLatch installedLatch = new CountDownLatch(1);
        final IPackageInstallObserver2 installObserver = new IPackageInstallObserver2.Stub() {
            @Override
            public void onUserActionRequired(Intent intent) throws RemoteException {
                throw new IllegalStateException();
            }

            @Override
            public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                    Bundle extras) throws RemoteException {
                if (DEBUG_INSTALL) Slog.d(TAG, "Install result for move: "
                        + PackageManager.installStatusToString(returnCode, msg));

                installedLatch.countDown();

                // Regardless of success or failure of the move operation,
                // always unfreeze the package
                unfreezePackage(packageName);

                final int status = PackageManager.installStatusToPublicStatus(returnCode);
                switch (status) {
                    case PackageInstaller.STATUS_SUCCESS:
                        mMoveCallbacks.notifyStatusChanged(moveId,
                                PackageManager.MOVE_SUCCEEDED);
                        break;
                    case PackageInstaller.STATUS_FAILURE_STORAGE:
                        mMoveCallbacks.notifyStatusChanged(moveId,
                                PackageManager.MOVE_FAILED_INSUFFICIENT_STORAGE);
                        break;
                    default:
                        mMoveCallbacks.notifyStatusChanged(moveId,
                                PackageManager.MOVE_FAILED_INTERNAL_ERROR);
                        break;
                }
            }
        };

        final MoveInfo move;
        if (moveCompleteApp) {
            // Kick off a thread to report progress estimates
            new Thread() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            if (installedLatch.await(1, TimeUnit.SECONDS)) {
                                break;
                            }
                        } catch (InterruptedException ignored) {
                        }

                        final long deltaFreeBytes = startFreeBytes - measurePath.getFreeSpace();
                        final int progress = 10 + (int) MathUtils.constrain(
                                ((deltaFreeBytes * 80) / sizeBytes), 0, 80);
                        mMoveCallbacks.notifyStatusChanged(moveId, progress);
                    }
                }
            }.start();

            final String dataAppName = codeFile.getName();
            move = new MoveInfo(moveId, currentVolumeUuid, volumeUuid, packageName,
                    dataAppName, appId, seinfo);
        } else {
            move = null;
        }

        installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;

        final Message msg = mHandler.obtainMessage(INIT_COPY);
        final OriginInfo origin = OriginInfo.fromExistingFile(codeFile);
        msg.obj = new InstallParams(origin, move, installObserver, installFlags,
                installerPackageName, volumeUuid, null, user, packageAbiOverride, null);
        mHandler.sendMessage(msg);
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

        final StorageManager storage = mContext.getSystemService(StorageManager.class);
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
        return android.provider.Settings.Global.getInt(mContext.getContentResolver(),
                android.provider.Settings.Global.DEFAULT_INSTALL_LOCATION,
                PackageHelper.APP_INSTALL_AUTO);
    }

    /** Called by UserManagerService */
    void cleanUpUserLILPw(UserManagerService userManager, int userHandle) {
        mDirtyUsers.remove(userHandle);
        mSettings.removeUserLPw(userHandle);
        mPendingBroadcasts.remove(userHandle);
        if (mInstaller != null) {
            // Technically, we shouldn't be doing this with the package lock
            // held.  However, this is very rare, and there is already so much
            // other disk I/O going on, that we'll let it slide for now.
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
                final String volumeUuid = vol.getFsUuid();
                if (DEBUG_INSTALL) Slog.d(TAG, "Removing user data on volume " + volumeUuid);
                mInstaller.removeUserDataDirs(volumeUuid, userHandle);
            }
        }
        mUserNeedsBadging.delete(userHandle);
        removeUnusedPackagesLILPw(userManager, userHandle);
    }

    /**
     * We're removing userHandle and would like to remove any downloaded packages
     * that are no longer in use by any other user.
     * @param userHandle the user being removed
     */
    private void removeUnusedPackagesLILPw(UserManagerService userManager, final int userHandle) {
        final boolean DEBUG_CLEAN_APKS = false;
        int [] users = userManager.getUserIdsLPr();
        Iterator<PackageSetting> psit = mSettings.mPackages.values().iterator();
        while (psit.hasNext()) {
            PackageSetting ps = psit.next();
            if (ps.pkg == null) {
                continue;
            }
            final String packageName = ps.pkg.packageName;
            // Skip over if system app
            if ((ps.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                continue;
            }
            if (DEBUG_CLEAN_APKS) {
                Slog.i(TAG, "Checking package " + packageName);
            }
            boolean keep = false;
            for (int i = 0; i < users.length; i++) {
                if (users[i] != userHandle && ps.getInstalled(users[i])) {
                    keep = true;
                    if (DEBUG_CLEAN_APKS) {
                        Slog.i(TAG, "  Keeping package " + packageName + " for user "
                                + users[i]);
                    }
                    break;
                }
            }
            if (!keep) {
                if (DEBUG_CLEAN_APKS) {
                    Slog.i(TAG, "  Removing package " + packageName);
                }
                mHandler.post(new Runnable() {
                    public void run() {
                        deletePackageX(packageName, userHandle, 0);
                    } //end run
                });
            }
        }
    }

    /** Called by UserManagerService */
    void createNewUserLILPw(int userHandle) {
        if (mInstaller != null) {
            mInstaller.createUserConfig(userHandle);
            mSettings.createNewUserLILPw(this, mInstaller, userHandle);
            applyFactoryDefaultBrowserLPw(userHandle);
            primeDomainVerificationsLPw(userHandle);
        }
    }

    void newUserCreated(final int userHandle) {
        mDefaultPermissionPolicy.grantDefaultPermissions(userHandle);
    }

    @Override
    public VerifierDeviceIdentity getVerifierDeviceIdentity() throws RemoteException {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.PACKAGE_VERIFICATION_AGENT,
                "Only package verification agents can read the verifier device identity");

        synchronized (mPackages) {
            return mSettings.getVerifierDeviceIdentityLPw();
        }
    }

    @Override
    public void setPermissionEnforced(String permission, boolean enforced) {
        // TODO: Now that we no longer change GID for storage, this should to away.
        mContext.enforceCallingOrSelfPermission(Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
                "setPermissionEnforced");
        if (READ_EXTERNAL_STORAGE.equals(permission)) {
            synchronized (mPackages) {
                if (mSettings.mReadExternalStorageEnforced == null
                        || mSettings.mReadExternalStorageEnforced != enforced) {
                    mSettings.mReadExternalStorageEnforced = enforced;
                    mSettings.writeLPr();
                }
            }
            // kill any non-foreground processes so we restart them and
            // grant/revoke the GID.
            final IActivityManager am = ActivityManagerNative.getDefault();
            if (am != null) {
                final long token = Binder.clearCallingIdentity();
                try {
                    am.killProcessesBelowForeground("setPermissionEnforcement");
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        } else {
            throw new IllegalArgumentException("No selective enforcement for " + permission);
        }
    }

    @Override
    @Deprecated
    public boolean isPermissionEnforced(String permission) {
        return true;
    }

    @Override
    public boolean isStorageLow() {
        final long token = Binder.clearCallingIdentity();
        try {
            final DeviceStorageMonitorInternal
                    dsm = LocalServices.getService(DeviceStorageMonitorInternal.class);
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
        return mInstallerService;
    }

    private boolean userNeedsBadging(int userId) {
        int index = mUserNeedsBadging.indexOfKey(userId);
        if (index < 0) {
            final UserInfo userInfo;
            final long token = Binder.clearCallingIdentity();
            try {
                userInfo = sUserManager.getUserInfo(userId);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            final boolean b;
            if (userInfo != null && userInfo.isManagedProfile()) {
                b = true;
            } else {
                b = false;
            }
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
        synchronized(mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                Slog.w(TAG, "KeySet requested for unknown package:" + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            KeySetManagerService ksms = mSettings.mKeySetManagerService;
            return new KeySet(ksms.getKeySetByAliasAndPackageNameLPr(packageName, alias));
        }
    }

    @Override
    public KeySet getSigningKeySet(String packageName) {
        if (packageName == null) {
            return null;
        }
        synchronized(mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                Slog.w(TAG, "KeySet requested for unknown package:" + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            if (pkg.applicationInfo.uid != Binder.getCallingUid()
                    && Process.SYSTEM_UID != Binder.getCallingUid()) {
                throw new SecurityException("May not access signing KeySet of other apps.");
            }
            KeySetManagerService ksms = mSettings.mKeySetManagerService;
            return new KeySet(ksms.getSigningKeySetByPackageNameLPr(packageName));
        }
    }

    @Override
    public boolean isPackageSignedByKeySet(String packageName, KeySet ks) {
        if (packageName == null || ks == null) {
            return false;
        }
        synchronized(mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                Slog.w(TAG, "KeySet requested for unknown package:" + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            IBinder ksh = ks.getToken();
            if (ksh instanceof KeySetHandle) {
                KeySetManagerService ksms = mSettings.mKeySetManagerService;
                return ksms.packageIsSignedByLPr(packageName, (KeySetHandle) ksh);
            }
            return false;
        }
    }

    @Override
    public boolean isPackageSignedByKeySetExactly(String packageName, KeySet ks) {
        if (packageName == null || ks == null) {
            return false;
        }
        synchronized(mPackages) {
            final PackageParser.Package pkg = mPackages.get(packageName);
            if (pkg == null) {
                Slog.w(TAG, "KeySet requested for unknown package:" + packageName);
                throw new IllegalArgumentException("Unknown package: " + packageName);
            }
            IBinder ksh = ks.getToken();
            if (ksh instanceof KeySetHandle) {
                KeySetManagerService ksms = mSettings.mKeySetManagerService;
                return ksms.packageIsSignedByExactlyLPr(packageName, (KeySetHandle) ksh);
            }
            return false;
        }
    }

    public void getUsageStatsIfNoPackageUsageInfo() {
        if (!mPackageUsage.isHistoricalPackageUsageAvailable()) {
            UsageStatsManager usm = (UsageStatsManager) mContext.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                throw new IllegalStateException("UsageStatsManager must be initialized");
            }
            long now = System.currentTimeMillis();
            Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(now - mDexOptLRUThresholdInMills, now);
            for (Map.Entry<String, UsageStats> entry : stats.entrySet()) {
                String packageName = entry.getKey();
                PackageParser.Package pkg = mPackages.get(packageName);
                if (pkg == null) {
                    continue;
                }
                UsageStats usage = entry.getValue();
                pkg.mLastPackageUsageTimeInMills = usage.getLastTimeUsed();
                mPackageUsage.mIsHistoricalPackageUsageAvailable = true;
            }
        }
    }

    /**
     * Check and throw if the given before/after packages would be considered a
     * downgrade.
     */
    private static void checkDowngrade(PackageParser.Package before, PackageInfoLite after)
            throws PackageManagerException {
        if (after.versionCode < before.mVersionCode) {
            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                    "Update version code " + after.versionCode + " is older than current "
                    + before.mVersionCode);
        } else if (after.versionCode == before.mVersionCode) {
            if (after.baseRevisionCode < before.baseRevisionCode) {
                throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                        "Update base revision code " + after.baseRevisionCode
                        + " is older than current " + before.baseRevisionCode);
            }

            if (!ArrayUtils.isEmpty(after.splitNames)) {
                for (int i = 0; i < after.splitNames.length; i++) {
                    final String splitName = after.splitNames[i];
                    final int j = ArrayUtils.indexOf(before.splitNames, splitName);
                    if (j != -1) {
                        if (after.splitRevisionCodes[i] < before.splitRevisionCodes[j]) {
                            throw new PackageManagerException(INSTALL_FAILED_VERSION_DOWNGRADE,
                                    "Update split " + splitName + " revision code "
                                    + after.splitRevisionCodes[i] + " is older than current "
                                    + before.splitRevisionCodes[j]);
                        }
                    }
                }
            }
        }
    }

    private static class MoveCallbacks extends Handler {
        private static final int MSG_CREATED = 1;
        private static final int MSG_STATUS_CHANGED = 2;

        private final RemoteCallbackList<IPackageMoveObserver>
                mCallbacks = new RemoteCallbackList<>();

        private final SparseIntArray mLastStatus = new SparseIntArray();

        public MoveCallbacks(Looper looper) {
            super(looper);
        }

        public void register(IPackageMoveObserver callback) {
            mCallbacks.register(callback);
        }

        public void unregister(IPackageMoveObserver callback) {
            mCallbacks.unregister(callback);
        }

        @Override
        public void handleMessage(Message msg) {
            final SomeArgs args = (SomeArgs) msg.obj;
            final int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                final IPackageMoveObserver callback = mCallbacks.getBroadcastItem(i);
                try {
                    invokeCallback(callback, msg.what, args);
                } catch (RemoteException ignored) {
                }
            }
            mCallbacks.finishBroadcast();
            args.recycle();
        }

        private void invokeCallback(IPackageMoveObserver callback, int what, SomeArgs args)
                throws RemoteException {
            switch (what) {
                case MSG_CREATED: {
                    callback.onCreated(args.argi1, (Bundle) args.arg2);
                    break;
                }
                case MSG_STATUS_CHANGED: {
                    callback.onStatusChanged(args.argi1, args.argi2, (long) args.arg3);
                    break;
                }
            }
        }

        private void notifyCreated(int moveId, Bundle extras) {
            Slog.v(TAG, "Move " + moveId + " created " + extras.toString());

            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = moveId;
            args.arg2 = extras;
            obtainMessage(MSG_CREATED, args).sendToTarget();
        }

        private void notifyStatusChanged(int moveId, int status) {
            notifyStatusChanged(moveId, status, -1);
        }

        private void notifyStatusChanged(int moveId, int status, long estMillis) {
            Slog.v(TAG, "Move " + moveId + " status " + status);

            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = moveId;
            args.argi2 = status;
            args.arg3 = estMillis;
            obtainMessage(MSG_STATUS_CHANGED, args).sendToTarget();

            synchronized (mLastStatus) {
                mLastStatus.put(moveId, status);
            }
        }
    }

    private final class OnPermissionChangeListeners extends Handler {
        private static final int MSG_ON_PERMISSIONS_CHANGED = 1;

        private final RemoteCallbackList<IOnPermissionsChangeListener> mPermissionListeners =
                new RemoteCallbackList<>();

        public OnPermissionChangeListeners(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ON_PERMISSIONS_CHANGED: {
                    final int uid = msg.arg1;
                    handleOnPermissionsChanged(uid);
                } break;
            }
        }

        public void addListenerLocked(IOnPermissionsChangeListener listener) {
            mPermissionListeners.register(listener);

        }

        public void removeListenerLocked(IOnPermissionsChangeListener listener) {
            mPermissionListeners.unregister(listener);
        }

        public void onPermissionsChanged(int uid) {
            if (mPermissionListeners.getRegisteredCallbackCount() > 0) {
                obtainMessage(MSG_ON_PERMISSIONS_CHANGED, uid, 0).sendToTarget();
            }
        }

        private void handleOnPermissionsChanged(int uid) {
            final int count = mPermissionListeners.beginBroadcast();
            try {
                for (int i = 0; i < count; i++) {
                    IOnPermissionsChangeListener callback = mPermissionListeners
                            .getBroadcastItem(i);
                    try {
                        callback.onPermissionsChanged(uid);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Permission listener is dead", e);
                    }
                }
            } finally {
                mPermissionListeners.finishBroadcast();
            }
        }
    }

    private class PackageManagerInternalImpl extends PackageManagerInternal {
        @Override
        public void setLocationPackagesProvider(PackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setLocationPackagesProviderLPw(provider);
            }
        }

        @Override
        public void setImePackagesProvider(PackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setImePackagesProviderLPr(provider);
            }
        }

        @Override
        public void setVoiceInteractionPackagesProvider(PackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setVoiceInteractionPackagesProviderLPw(provider);
            }
        }

        @Override
        public void setSmsAppPackagesProvider(PackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setSmsAppPackagesProviderLPw(provider);
            }
        }

        @Override
        public void setDialerAppPackagesProvider(PackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setDialerAppPackagesProviderLPw(provider);
            }
        }

        @Override
        public void setSimCallManagerPackagesProvider(PackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setSimCallManagerPackagesProviderLPw(provider);
            }
        }

        @Override
        public void setSyncAdapterPackagesprovider(SyncAdapterPackagesProvider provider) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.setSyncAdapterPackagesProviderLPw(provider);
            }
        }

        @Override
        public void grantDefaultPermissionsToDefaultSmsApp(String packageName, int userId) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultSmsAppLPr(
                        packageName, userId);
            }
        }

        @Override
        public void grantDefaultPermissionsToDefaultDialerApp(String packageName, int userId) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultDialerAppLPr(
                        packageName, userId);
            }
        }
        @Override
        public void grantDefaultPermissionsToDefaultSimCallManager(String packageName, int userId) {
            synchronized (mPackages) {
                mDefaultPermissionPolicy.grantDefaultPermissionsToDefaultSimCallManagerLPr(
                        packageName, userId);
            }
        }
    }

    @Override
    public void grantDefaultPermissionsToEnabledCarrierApps(String[] packageNames, int userId) {
        enforceSystemOrPhoneCaller("grantPermissionsToEnabledCarrierApps");
        synchronized (mPackages) {
            final long identity = Binder.clearCallingIdentity();
            try {
                mDefaultPermissionPolicy.grantDefaultPermissionsToEnabledCarrierAppsLPr(
                        packageNames, userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static void enforceSystemOrPhoneCaller(String tag) {
        int callingUid = Binder.getCallingUid();
        if (callingUid != Process.PHONE_UID && callingUid != Process.SYSTEM_UID) {
            throw new SecurityException(
                    "Cannot call " + tag + " from UID " + callingUid);
        }
    }
}
