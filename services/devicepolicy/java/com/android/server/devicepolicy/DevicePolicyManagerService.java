/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.Manifest.permission.BIND_DEVICE_ADMIN;
import static android.Manifest.permission.MANAGE_CA_CERTIFICATES;
import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.admin.DeviceAdminReceiver.EXTRA_TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE;
import static android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_USER;
import static android.app.admin.DevicePolicyManager.CODE_ACCOUNTS_NOT_EMPTY;
import static android.app.admin.DevicePolicyManager.CODE_ADD_MANAGED_PROFILE_DISALLOWED;
import static android.app.admin.DevicePolicyManager.CODE_CANNOT_ADD_MANAGED_PROFILE;
import static android.app.admin.DevicePolicyManager.CODE_DEVICE_ADMIN_NOT_SUPPORTED;
import static android.app.admin.DevicePolicyManager.CODE_HAS_DEVICE_OWNER;
import static android.app.admin.DevicePolicyManager.CODE_HAS_PAIRED;
import static android.app.admin.DevicePolicyManager.CODE_MANAGED_USERS_NOT_SUPPORTED;
import static android.app.admin.DevicePolicyManager.CODE_NONSYSTEM_USER_EXISTS;
import static android.app.admin.DevicePolicyManager.CODE_NOT_SYSTEM_USER;
import static android.app.admin.DevicePolicyManager.CODE_NOT_SYSTEM_USER_SPLIT;
import static android.app.admin.DevicePolicyManager.CODE_OK;
import static android.app.admin.DevicePolicyManager.CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER;
import static android.app.admin.DevicePolicyManager.CODE_SYSTEM_USER;
import static android.app.admin.DevicePolicyManager.CODE_USER_HAS_PROFILE_OWNER;
import static android.app.admin.DevicePolicyManager.CODE_USER_NOT_RUNNING;
import static android.app.admin.DevicePolicyManager.CODE_USER_SETUP_COMPLETED;
import static android.app.admin.DevicePolicyManager.DELEGATION_APP_RESTRICTIONS;
import static android.app.admin.DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL;
import static android.app.admin.DevicePolicyManager.DELEGATION_CERT_INSTALL;
import static android.app.admin.DevicePolicyManager.DELEGATION_ENABLE_SYSTEM_APP;
import static android.app.admin.DevicePolicyManager.DELEGATION_INSTALL_EXISTING_PACKAGE;
import static android.app.admin.DevicePolicyManager.DELEGATION_KEEP_UNINSTALLED_PACKAGES;
import static android.app.admin.DevicePolicyManager.DELEGATION_PACKAGE_ACCESS;
import static android.app.admin.DevicePolicyManager.DELEGATION_PERMISSION_GRANT;
import static android.app.admin.DevicePolicyManager.ID_TYPE_BASE_INFO;
import static android.app.admin.DevicePolicyManager.ID_TYPE_IMEI;
import static android.app.admin.DevicePolicyManager.ID_TYPE_MEID;
import static android.app.admin.DevicePolicyManager.ID_TYPE_SERIAL;
import static android.app.admin.DevicePolicyManager.LEAVE_ALL_SYSTEM_APPS_ENABLED;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_HOME;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS;
import static android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
import static android.app.admin.DevicePolicyManager.PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER;
import static android.app.admin.DevicePolicyManager.WIPE_EUICC;
import static android.app.admin.DevicePolicyManager.WIPE_EXTERNAL_STORAGE;
import static android.app.admin.DevicePolicyManager.WIPE_RESET_PROTECTION_DATA;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;

import static android.provider.Telephony.Carriers.DPC_URI;
import static android.provider.Telephony.Carriers.ENFORCE_KEY;
import static android.provider.Telephony.Carriers.ENFORCE_MANAGED_URI;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent
        .PROVISIONING_ENTRY_POINT_ADB;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker
        .STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;

import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.ADMIN_TYPE_DEVICE_OWNER;
import static com.android.server.devicepolicy.TransferOwnershipMetadataManager.ADMIN_TYPE_PROFILE_OWNER;


import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.Manifest.permission;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityThread;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.IServiceConnection;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyCache;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.NetworkEvent;
import android.app.admin.PasswordMetrics;
import android.app.admin.SecurityLog;
import android.app.admin.SecurityLog.SecurityEvent;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.app.backup.IBackupManager;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.trust.TrustManager;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.StringParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.IAudioService;
import android.net.ConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.ProxyInfo;
import android.net.Uri;
import android.net.metrics.IpConnectivityLog;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RecoverySystem;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.UserManagerInternal.UserRestrictionsListener;
import android.os.storage.StorageManager;
import android.provider.ContactsContract.QuickContact;
import android.provider.ContactsInternal;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.security.IKeyChainAliasCallback;
import android.security.IKeyChainService;
import android.security.KeyChain;
import android.security.KeyChain.KeyChainConnection;
import android.security.KeyStore;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keystore.AttestationUtils;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.ParcelableKeyGenParameterSpec;
import android.service.persistentdata.PersistentDataBlockManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.IWindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.telephony.SmsApplication;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.internal.util.StatLogger;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.devicepolicy.DevicePolicyManagerService.ActiveAdmin.TrustAgentInfo;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.pm.UserRestrictionsUtils;
import com.android.server.storage.DeviceStorageMonitorInternal;

import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Implementation of the device policy APIs.
 */
public class DevicePolicyManagerService extends BaseIDevicePolicyManager {

    protected static final String LOG_TAG = "DevicePolicyManager";

    private static final boolean VERBOSE_LOG = false; // DO NOT SUBMIT WITH TRUE

    private static final String DEVICE_POLICIES_XML = "device_policies.xml";

    private static final String TRANSFER_OWNERSHIP_PARAMETERS_XML =
            "transfer-ownership-parameters.xml";

    private static final String TAG_ACCEPTED_CA_CERTIFICATES = "accepted-ca-certificate";

    private static final String TAG_LOCK_TASK_COMPONENTS = "lock-task-component";

    private static final String TAG_LOCK_TASK_FEATURES = "lock-task-features";

    private static final String TAG_STATUS_BAR = "statusbar";

    private static final String ATTR_DISABLED = "disabled";

    private static final String ATTR_NAME = "name";

    private static final String DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML =
            "do-not-ask-credentials-on-boot";

    private static final String TAG_AFFILIATION_ID = "affiliation-id";

    private static final String TAG_LAST_SECURITY_LOG_RETRIEVAL = "last-security-log-retrieval";

    private static final String TAG_LAST_BUG_REPORT_REQUEST = "last-bug-report-request";

    private static final String TAG_LAST_NETWORK_LOG_RETRIEVAL = "last-network-log-retrieval";

    private static final String TAG_ADMIN_BROADCAST_PENDING = "admin-broadcast-pending";

    private static final String TAG_CURRENT_INPUT_METHOD_SET = "current-ime-set";

    private static final String TAG_OWNER_INSTALLED_CA_CERT = "owner-installed-ca-cert";

    private static final String ATTR_ID = "id";

    private static final String ATTR_VALUE = "value";

    private static final String ATTR_ALIAS = "alias";

    private static final String TAG_INITIALIZATION_BUNDLE = "initialization-bundle";

    private static final String TAG_PASSWORD_TOKEN_HANDLE = "password-token";

    private static final String TAG_PASSWORD_VALIDITY = "password-validity";

    private static final String TAG_TRANSFER_OWNERSHIP_BUNDLE = "transfer-ownership-bundle";

    private static final int REQUEST_EXPIRE_PASSWORD = 5571;

    private static final long MS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private static final long EXPIRATION_GRACE_PERIOD_MS = 5 * MS_PER_DAY; // 5 days, in ms

    private static final String ACTION_EXPIRED_PASSWORD_NOTIFICATION
            = "com.android.server.ACTION_EXPIRED_PASSWORD_NOTIFICATION";

    private static final String ATTR_PERMISSION_PROVIDER = "permission-provider";
    private static final String ATTR_SETUP_COMPLETE = "setup-complete";
    private static final String ATTR_PROVISIONING_STATE = "provisioning-state";
    private static final String ATTR_PERMISSION_POLICY = "permission-policy";
    private static final String ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED =
            "device-provisioning-config-applied";
    private static final String ATTR_DEVICE_PAIRED = "device-paired";
    private static final String ATTR_DELEGATED_CERT_INSTALLER = "delegated-cert-installer";
    private static final String ATTR_APPLICATION_RESTRICTIONS_MANAGER
            = "application-restrictions-manager";

    // Comprehensive list of delegations.
    private static final String DELEGATIONS[] = {
        DELEGATION_CERT_INSTALL,
        DELEGATION_APP_RESTRICTIONS,
        DELEGATION_BLOCK_UNINSTALL,
        DELEGATION_ENABLE_SYSTEM_APP,
        DELEGATION_KEEP_UNINSTALLED_PACKAGES,
        DELEGATION_PACKAGE_ACCESS,
        DELEGATION_PERMISSION_GRANT,
        DELEGATION_INSTALL_EXISTING_PACKAGE,
        DELEGATION_KEEP_UNINSTALLED_PACKAGES
    };

    /**
     *  System property whose value is either "true" or "false", indicating whether
     *  device owner is present.
     */
    private static final String PROPERTY_DEVICE_OWNER_PRESENT = "ro.device_owner";

    private static final int STATUS_BAR_DISABLE_MASK =
            StatusBarManager.DISABLE_EXPAND |
            StatusBarManager.DISABLE_NOTIFICATION_ICONS |
            StatusBarManager.DISABLE_NOTIFICATION_ALERTS |
            StatusBarManager.DISABLE_SEARCH;

    private static final int STATUS_BAR_DISABLE2_MASK =
            StatusBarManager.DISABLE2_QUICK_SETTINGS;

    private static final Set<String> SECURE_SETTINGS_WHITELIST;
    private static final Set<String> SECURE_SETTINGS_DEVICEOWNER_WHITELIST;
    private static final Set<String> GLOBAL_SETTINGS_WHITELIST;
    private static final Set<String> GLOBAL_SETTINGS_DEPRECATED;
    private static final Set<String> SYSTEM_SETTINGS_WHITELIST;
    static {
        SECURE_SETTINGS_WHITELIST = new ArraySet<>();
        SECURE_SETTINGS_WHITELIST.add(Settings.Secure.DEFAULT_INPUT_METHOD);
        SECURE_SETTINGS_WHITELIST.add(Settings.Secure.SKIP_FIRST_USE_HINTS);
        SECURE_SETTINGS_WHITELIST.add(Settings.Secure.INSTALL_NON_MARKET_APPS);

        SECURE_SETTINGS_DEVICEOWNER_WHITELIST = new ArraySet<>();
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST.addAll(SECURE_SETTINGS_WHITELIST);
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST.add(Settings.Secure.LOCATION_MODE);

        GLOBAL_SETTINGS_WHITELIST = new ArraySet<>();
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.ADB_ENABLED);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.AUTO_TIME);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.AUTO_TIME_ZONE);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.DATA_ROAMING);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.USB_MASS_STORAGE_ENABLED);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.WIFI_SLEEP_POLICY);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN);

        GLOBAL_SETTINGS_DEPRECATED = new ArraySet<>();
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.BLUETOOTH_ON);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.MODE_RINGER);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.NETWORK_PREFERENCE);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.WIFI_ON);

        SYSTEM_SETTINGS_WHITELIST = new ArraySet<>();
        SYSTEM_SETTINGS_WHITELIST.add(Settings.System.SCREEN_BRIGHTNESS);
        SYSTEM_SETTINGS_WHITELIST.add(Settings.System.SCREEN_BRIGHTNESS_MODE);
        SYSTEM_SETTINGS_WHITELIST.add(Settings.System.SCREEN_OFF_TIMEOUT);
    }

    /**
     * Keyguard features that when set on a profile affect the profile content or challenge only.
     * These cannot be set on the managed profile's parent DPM instance
     */
    private static final int PROFILE_KEYGUARD_FEATURES_PROFILE_ONLY =
            DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;

    /** Keyguard features that are allowed to be set on a managed profile */
    private static final int PROFILE_KEYGUARD_FEATURES =
            PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER | PROFILE_KEYGUARD_FEATURES_PROFILE_ONLY;

    private static final int DEVICE_ADMIN_DEACTIVATE_TIMEOUT = 10000;

    /**
     * Minimum timeout in milliseconds after which unlocking with weak auth times out,
     * i.e. the user has to use a strong authentication method like password, PIN or pattern.
     */
    private static final long MINIMUM_STRONG_AUTH_TIMEOUT_MS = TimeUnit.HOURS.toMillis(1);

    /**
     * Strings logged with {@link
     * com.android.internal.logging.nano.MetricsProto.MetricsEvent#PROVISIONING_ENTRY_POINT_ADB}.
     */
    private static final String LOG_TAG_PROFILE_OWNER = "profile-owner";
    private static final String LOG_TAG_DEVICE_OWNER = "device-owner";

    final Context mContext;
    final Injector mInjector;
    final IPackageManager mIPackageManager;
    final UserManager mUserManager;
    final UserManagerInternal mUserManagerInternal;
    final UsageStatsManagerInternal mUsageStatsManagerInternal;
    final TelephonyManager mTelephonyManager;
    private final LockPatternUtils mLockPatternUtils;
    private final DevicePolicyConstants mConstants;
    private final DeviceAdminServiceController mDeviceAdminServiceController;
    private final OverlayPackagesProvider mOverlayPackagesProvider;

    private final DevicePolicyCacheImpl mPolicyCache = new DevicePolicyCacheImpl();

    /**
     * Contains (package-user) pairs to remove. An entry (p, u) implies that removal of package p
     * is requested for user u.
     */
    private final Set<Pair<String, Integer>> mPackagesToRemove =
            new ArraySet<Pair<String, Integer>>();

    final LocalService mLocalService;

    // Stores and loads state on device and profile owners.
    @VisibleForTesting
    final Owners mOwners;

    private final Binder mToken = new Binder();

    /**
     * Whether or not device admin feature is supported. If it isn't return defaults for all
     * public methods.
     */
    final boolean mHasFeature;

    /**
     * Whether or not this device is a watch.
     */
    final boolean mIsWatch;

    private final CertificateMonitor mCertificateMonitor;
    private final SecurityLogMonitor mSecurityLogMonitor;

    @GuardedBy("getLockObject()")
    private NetworkLogger mNetworkLogger;

    private final AtomicBoolean mRemoteBugreportServiceIsActive = new AtomicBoolean();
    private final AtomicBoolean mRemoteBugreportSharingAccepted = new AtomicBoolean();

    private final SetupContentObserver mSetupContentObserver;

    private static boolean ENABLE_LOCK_GUARD = Build.IS_ENG
            || (SystemProperties.getInt("debug.dpm.lock_guard", 0) == 1);

    interface Stats {
        int LOCK_GUARD_GUARD = 0;

        int COUNT = LOCK_GUARD_GUARD + 1;
    }

    private final StatLogger mStatLogger = new StatLogger(new String[] {
            "LockGuard.guard()",
    });

    private final Object mLockDoNoUseDirectly = LockGuard.installNewLock(
            LockGuard.INDEX_DPMS, /* doWtf=*/ true);

    final Object getLockObject() {
        if (ENABLE_LOCK_GUARD) {
            final long start = mStatLogger.getTime();
            LockGuard.guard(LockGuard.INDEX_DPMS);
            mStatLogger.logDurationStat(Stats.LOCK_GUARD_GUARD, start);
        }
        return mLockDoNoUseDirectly;
    }

    /**
     * Check if the current thread holds the DPMS lock, and if not, do a WTF.
     *
     * (Doing this check too much may be costly, so don't call it in a hot path.)
     */
    final void ensureLocked() {
        if (Thread.holdsLock(mLockDoNoUseDirectly)) {
            return;
        }
        Slog.wtfStack(LOG_TAG, "Not holding DPMS lock.");
    }

    @VisibleForTesting
    final TransferOwnershipMetadataManager mTransferOwnershipMetadataManager;

    private final Runnable mRemoteBugreportTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if(mRemoteBugreportServiceIsActive.get()) {
                onBugreportFailed();
            }
        }
    };

    /** Listens only if mHasFeature == true. */
    private final BroadcastReceiver mRemoteBugreportFinishedReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DevicePolicyManager.ACTION_REMOTE_BUGREPORT_DISPATCH.equals(intent.getAction())
                    && mRemoteBugreportServiceIsActive.get()) {
                onBugreportFinished(intent);
            }
        }
    };

    /** Listens only if mHasFeature == true. */
    private final BroadcastReceiver mRemoteBugreportConsentReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            mInjector.getNotificationManager().cancel(LOG_TAG,
                    RemoteBugreportUtils.NOTIFICATION_ID);
            if (DevicePolicyManager.ACTION_BUGREPORT_SHARING_ACCEPTED.equals(action)) {
                onBugreportSharingAccepted();
            } else if (DevicePolicyManager.ACTION_BUGREPORT_SHARING_DECLINED.equals(action)) {
                onBugreportSharingDeclined();
            }
            mContext.unregisterReceiver(mRemoteBugreportConsentReceiver);
        }
    };

    public static final class Lifecycle extends SystemService {
        private BaseIDevicePolicyManager mService;

        public Lifecycle(Context context) {
            super(context);
            String dpmsClassName = context.getResources()
                    .getString(R.string.config_deviceSpecificDevicePolicyManagerService);
            if (TextUtils.isEmpty(dpmsClassName)) {
                dpmsClassName = DevicePolicyManagerService.class.getName();
            }
            try {
                Class serviceClass = Class.forName(dpmsClassName);
                Constructor constructor = serviceClass.getConstructor(Context.class);
                mService = (BaseIDevicePolicyManager) constructor.newInstance(context);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Failed to instantiate DevicePolicyManagerService with class name: "
                    + dpmsClassName, e);
            }
        }

        @Override
        public void onStart() {
            publishBinderService(Context.DEVICE_POLICY_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            mService.systemReady(phase);
        }

        @Override
        public void onStartUser(int userHandle) {
            mService.handleStartUser(userHandle);
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mService.handleUnlockUser(userHandle);
        }

        @Override
        public void onStopUser(int userHandle) {
            mService.handleStopUser(userHandle);
        }
    }

    public static class DevicePolicyData {
        int mFailedPasswordAttempts = 0;
        boolean mPasswordValidAtLastCheckpoint = true;

        int mUserHandle;
        int mPasswordOwner = -1;
        long mLastMaximumTimeToLock = -1;
        boolean mUserSetupComplete = false;
        boolean mPaired = false;
        int mUserProvisioningState;
        int mPermissionPolicy;

        boolean mDeviceProvisioningConfigApplied = false;

        final ArrayMap<ComponentName, ActiveAdmin> mAdminMap = new ArrayMap<>();
        final ArrayList<ActiveAdmin> mAdminList = new ArrayList<>();
        final ArrayList<ComponentName> mRemovingAdmins = new ArrayList<>();

        // TODO(b/35385311): Keep track of metadata in TrustedCertificateStore instead.
        final ArraySet<String> mAcceptedCaCertificates = new ArraySet<>();

        // This is the list of component allowed to start lock task mode.
        List<String> mLockTaskPackages = new ArrayList<>();

        // Bitfield of feature flags to be enabled during LockTask mode.
        // We default on the power button menu, in order to be consistent with pre-P behaviour.
        int mLockTaskFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;

        boolean mStatusBarDisabled = false;

        ComponentName mRestrictionsProvider;

        // Map of delegate package to delegation scopes
        final ArrayMap<String, List<String>> mDelegationMap = new ArrayMap<>();

        boolean doNotAskCredentialsOnBoot = false;

        Set<String> mAffiliationIds = new ArraySet<>();

        long mLastSecurityLogRetrievalTime = -1;

        long mLastBugReportRequestTime = -1;

        long mLastNetworkLogsRetrievalTime = -1;

        boolean mCurrentInputMethodSet = false;

        // TODO(b/35385311): Keep track of metadata in TrustedCertificateStore instead.
        Set<String> mOwnerInstalledCaCerts = new ArraySet<>();

        // Used for initialization of users created by createAndManageUser.
        boolean mAdminBroadcastPending = false;
        PersistableBundle mInitBundle = null;

        long mPasswordTokenHandle = 0;

        public DevicePolicyData(int userHandle) {
            mUserHandle = userHandle;
        }
    }

    @GuardedBy("getLockObject()")
    final SparseArray<DevicePolicyData> mUserData = new SparseArray<>();

    @GuardedBy("getLockObject()")
    final SparseArray<PasswordMetrics> mUserPasswordMetrics = new SparseArray<>();

    final Handler mHandler;
    final Handler mBackgroundHandler;

    /** Listens only if mHasFeature == true. */
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                    getSendingUserId());

            /*
             * Network logging would ideally be started in setDeviceOwnerSystemPropertyLocked(),
             * however it's too early in the boot process to register with IIpConnectivityMetrics
             * to listen for events.
             */
            if (Intent.ACTION_USER_STARTED.equals(action)
                    && userHandle == mOwners.getDeviceOwnerUserId()) {
                synchronized (getLockObject()) {
                    if (isNetworkLoggingEnabledInternalLocked()) {
                        setNetworkLoggingActiveInternal(true);
                    }
                }
            }
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    && userHandle == mOwners.getDeviceOwnerUserId()
                    && getDeviceOwnerRemoteBugreportUri() != null) {
                IntentFilter filterConsent = new IntentFilter();
                filterConsent.addAction(DevicePolicyManager.ACTION_BUGREPORT_SHARING_DECLINED);
                filterConsent.addAction(DevicePolicyManager.ACTION_BUGREPORT_SHARING_ACCEPTED);
                mContext.registerReceiver(mRemoteBugreportConsentReceiver, filterConsent);
                mInjector.getNotificationManager().notifyAsUser(LOG_TAG,
                        RemoteBugreportUtils.NOTIFICATION_ID,
                        RemoteBugreportUtils.buildNotification(mContext,
                                DevicePolicyManager.NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED),
                                UserHandle.ALL);
            }
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || ACTION_EXPIRED_PASSWORD_NOTIFICATION.equals(action)) {
                if (VERBOSE_LOG) {
                    Slog.v(LOG_TAG, "Sending password expiration notifications for action "
                            + action + " for user " + userHandle);
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handlePasswordExpirationNotification(userHandle);
                    }
                });
            }

            if (Intent.ACTION_USER_ADDED.equals(action)) {
                sendDeviceOwnerUserCommand(DeviceAdminReceiver.ACTION_USER_ADDED, userHandle);
                synchronized (getLockObject()) {
                    // It might take a while for the user to become affiliated. Make security
                    // and network logging unavailable in the meantime.
                    maybePauseDeviceWideLoggingLocked();
                }
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                sendDeviceOwnerUserCommand(DeviceAdminReceiver.ACTION_USER_REMOVED, userHandle);
                synchronized (getLockObject()) {
                    // Check whether the user is affiliated, *before* removing its data.
                    boolean isRemovedUserAffiliated = isUserAffiliatedWithDeviceLocked(userHandle);
                    removeUserData(userHandle);
                    if (!isRemovedUserAffiliated) {
                        // We discard the logs when unaffiliated users are deleted (so that the
                        // device owner cannot retrieve data about that user after it's gone).
                        discardDeviceWideLogsLocked();
                        // Resume logging if all remaining users are affiliated.
                        maybeResumeDeviceWideLoggingLocked();
                    }
                }
            } else if (Intent.ACTION_USER_STARTED.equals(action)) {
                sendDeviceOwnerUserCommand(DeviceAdminReceiver.ACTION_USER_STARTED, userHandle);
                synchronized (getLockObject()) {
                    maybeSendAdminEnabledBroadcastLocked(userHandle);
                    // Reset the policy data
                    mUserData.remove(userHandle);
                }
                handlePackagesChanged(null /* check all admins */, userHandle);
            } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                sendDeviceOwnerUserCommand(DeviceAdminReceiver.ACTION_USER_STOPPED, userHandle);
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                sendDeviceOwnerUserCommand(DeviceAdminReceiver.ACTION_USER_SWITCHED, userHandle);
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                synchronized (getLockObject()) {
                    maybeSendAdminEnabledBroadcastLocked(userHandle);
                }
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                handlePackagesChanged(null /* check all admins */, userHandle);
            } else if (Intent.ACTION_PACKAGE_CHANGED.equals(action)
                    || (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false))) {
                handlePackagesChanged(intent.getData().getSchemeSpecificPart(), userHandle);
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                handlePackagesChanged(intent.getData().getSchemeSpecificPart(), userHandle);
            } else if (Intent.ACTION_MANAGED_PROFILE_ADDED.equals(action)) {
                clearWipeProfileNotification();
            } else if (Intent.ACTION_DATE_CHANGED.equals(action)
                    || Intent.ACTION_TIME_CHANGED.equals(action)) {
                // Update freeze period record when clock naturally progresses to the next day
                // (ACTION_DATE_CHANGED), or when manual clock adjustment is made
                // (ACTION_TIME_CHANGED)
                updateSystemUpdateFreezePeriodsRecord(/* saveIfChanged */ true);
            }

        }

        private void sendDeviceOwnerUserCommand(String action, int userHandle) {
            synchronized (getLockObject()) {
                ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner != null) {
                    Bundle extras = new Bundle();
                    extras.putParcelable(Intent.EXTRA_USER, UserHandle.of(userHandle));
                    sendAdminCommandLocked(deviceOwner, action, extras, /* result */ null,
                            /* inForeground */ true);
                }
            }
        }
    };

    protected static class RestrictionsListener implements UserRestrictionsListener {
        private Context mContext;

        public RestrictionsListener(Context context) {
            mContext = context;
        }

        public void onUserRestrictionsChanged(int userId, Bundle newRestrictions,
                Bundle prevRestrictions) {
            final boolean newlyDisallowed =
                    newRestrictions.getBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE);
            final boolean previouslyDisallowed =
                    prevRestrictions.getBoolean(UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE);
            final boolean restrictionChanged = (newlyDisallowed != previouslyDisallowed);

            if (restrictionChanged) {
                // Notify ManagedProvisioning to update the built-in cross profile intent filters.
                Intent intent = new Intent(
                        DevicePolicyManager.ACTION_DATA_SHARING_RESTRICTION_CHANGED);
                intent.setPackage(getManagedProvisioningPackage(mContext));
                intent.putExtra(Intent.EXTRA_USER_ID, userId);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
            }
        }
    }

    static class ActiveAdmin {
        private static final String TAG_DISABLE_KEYGUARD_FEATURES = "disable-keyguard-features";
        private static final String TAG_TEST_ONLY_ADMIN = "test-only-admin";
        private static final String TAG_DISABLE_CAMERA = "disable-camera";
        private static final String TAG_DISABLE_CALLER_ID = "disable-caller-id";
        private static final String TAG_DISABLE_CONTACTS_SEARCH = "disable-contacts-search";
        private static final String TAG_DISABLE_BLUETOOTH_CONTACT_SHARING
                = "disable-bt-contacts-sharing";
        private static final String TAG_DISABLE_SCREEN_CAPTURE = "disable-screen-capture";
        private static final String TAG_DISABLE_ACCOUNT_MANAGEMENT = "disable-account-management";
        private static final String TAG_REQUIRE_AUTO_TIME = "require_auto_time";
        private static final String TAG_FORCE_EPHEMERAL_USERS = "force_ephemeral_users";
        private static final String TAG_IS_NETWORK_LOGGING_ENABLED = "is_network_logging_enabled";
        private static final String TAG_ACCOUNT_TYPE = "account-type";
        private static final String TAG_PERMITTED_ACCESSIBILITY_SERVICES
                = "permitted-accessiblity-services";
        private static final String TAG_ENCRYPTION_REQUESTED = "encryption-requested";
        private static final String TAG_MANAGE_TRUST_AGENT_FEATURES = "manage-trust-agent-features";
        private static final String TAG_TRUST_AGENT_COMPONENT_OPTIONS = "trust-agent-component-options";
        private static final String TAG_TRUST_AGENT_COMPONENT = "component";
        private static final String TAG_PASSWORD_EXPIRATION_DATE = "password-expiration-date";
        private static final String TAG_PASSWORD_EXPIRATION_TIMEOUT = "password-expiration-timeout";
        private static final String TAG_GLOBAL_PROXY_EXCLUSION_LIST = "global-proxy-exclusion-list";
        private static final String TAG_GLOBAL_PROXY_SPEC = "global-proxy-spec";
        private static final String TAG_SPECIFIES_GLOBAL_PROXY = "specifies-global-proxy";
        private static final String TAG_PERMITTED_IMES = "permitted-imes";
        private static final String TAG_PERMITTED_NOTIFICATION_LISTENERS =
                "permitted-notification-listeners";
        private static final String TAG_MAX_FAILED_PASSWORD_WIPE = "max-failed-password-wipe";
        private static final String TAG_MAX_TIME_TO_UNLOCK = "max-time-to-unlock";
        private static final String TAG_STRONG_AUTH_UNLOCK_TIMEOUT = "strong-auth-unlock-timeout";
        private static final String TAG_MIN_PASSWORD_NONLETTER = "min-password-nonletter";
        private static final String TAG_MIN_PASSWORD_SYMBOLS = "min-password-symbols";
        private static final String TAG_MIN_PASSWORD_NUMERIC = "min-password-numeric";
        private static final String TAG_MIN_PASSWORD_LETTERS = "min-password-letters";
        private static final String TAG_MIN_PASSWORD_LOWERCASE = "min-password-lowercase";
        private static final String TAG_MIN_PASSWORD_UPPERCASE = "min-password-uppercase";
        private static final String TAG_PASSWORD_HISTORY_LENGTH = "password-history-length";
        private static final String TAG_MIN_PASSWORD_LENGTH = "min-password-length";
        private static final String ATTR_VALUE = "value";
        private static final String TAG_PASSWORD_QUALITY = "password-quality";
        private static final String TAG_POLICIES = "policies";
        private static final String TAG_CROSS_PROFILE_WIDGET_PROVIDERS =
                "cross-profile-widget-providers";
        private static final String TAG_PROVIDER = "provider";
        private static final String TAG_PACKAGE_LIST_ITEM  = "item";
        private static final String TAG_KEEP_UNINSTALLED_PACKAGES  = "keep-uninstalled-packages";
        private static final String TAG_USER_RESTRICTIONS = "user-restrictions";
        private static final String TAG_DEFAULT_ENABLED_USER_RESTRICTIONS =
                "default-enabled-user-restrictions";
        private static final String TAG_RESTRICTION = "restriction";
        private static final String TAG_SHORT_SUPPORT_MESSAGE = "short-support-message";
        private static final String TAG_LONG_SUPPORT_MESSAGE = "long-support-message";
        private static final String TAG_PARENT_ADMIN = "parent-admin";
        private static final String TAG_ORGANIZATION_COLOR = "organization-color";
        private static final String TAG_ORGANIZATION_NAME = "organization-name";
        private static final String ATTR_LAST_NETWORK_LOGGING_NOTIFICATION = "last-notification";
        private static final String ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS = "num-notifications";
        private static final String TAG_IS_LOGOUT_ENABLED = "is_logout_enabled";
        private static final String TAG_MANDATORY_BACKUP_TRANSPORT = "mandatory_backup_transport";
        private static final String TAG_START_USER_SESSION_MESSAGE = "start_user_session_message";
        private static final String TAG_END_USER_SESSION_MESSAGE = "end_user_session_message";
        private static final String TAG_METERED_DATA_DISABLED_PACKAGES
                = "metered_data_disabled_packages";

        DeviceAdminInfo info;


        static final int DEF_PASSWORD_HISTORY_LENGTH = 0;
        int passwordHistoryLength = DEF_PASSWORD_HISTORY_LENGTH;

        static final int DEF_MINIMUM_PASSWORD_LENGTH = 0;
        static final int DEF_MINIMUM_PASSWORD_LETTERS = 1;
        static final int DEF_MINIMUM_PASSWORD_UPPER_CASE = 0;
        static final int DEF_MINIMUM_PASSWORD_LOWER_CASE = 0;
        static final int DEF_MINIMUM_PASSWORD_NUMERIC = 1;
        static final int DEF_MINIMUM_PASSWORD_SYMBOLS = 1;
        static final int DEF_MINIMUM_PASSWORD_NON_LETTER = 0;
        @NonNull
        PasswordMetrics minimumPasswordMetrics = new PasswordMetrics(
                PASSWORD_QUALITY_UNSPECIFIED, DEF_MINIMUM_PASSWORD_LENGTH,
                DEF_MINIMUM_PASSWORD_LETTERS, DEF_MINIMUM_PASSWORD_UPPER_CASE,
                DEF_MINIMUM_PASSWORD_LOWER_CASE, DEF_MINIMUM_PASSWORD_NUMERIC,
                DEF_MINIMUM_PASSWORD_SYMBOLS, DEF_MINIMUM_PASSWORD_NON_LETTER);

        static final long DEF_MAXIMUM_TIME_TO_UNLOCK = 0;
        long maximumTimeToUnlock = DEF_MAXIMUM_TIME_TO_UNLOCK;

        long strongAuthUnlockTimeout = 0; // admin doesn't participate by default

        static final int DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE = 0;
        int maximumFailedPasswordsForWipe = DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE;

        static final long DEF_PASSWORD_EXPIRATION_TIMEOUT = 0;
        long passwordExpirationTimeout = DEF_PASSWORD_EXPIRATION_TIMEOUT;

        static final long DEF_PASSWORD_EXPIRATION_DATE = 0;
        long passwordExpirationDate = DEF_PASSWORD_EXPIRATION_DATE;

        static final int DEF_KEYGUARD_FEATURES_DISABLED = 0; // none

        int disabledKeyguardFeatures = DEF_KEYGUARD_FEATURES_DISABLED;

        boolean encryptionRequested = false;
        boolean testOnlyAdmin = false;
        boolean disableCamera = false;
        boolean disableCallerId = false;
        boolean disableContactsSearch = false;
        boolean disableBluetoothContactSharing = true;
        boolean disableScreenCapture = false; // Can only be set by a device/profile owner.
        boolean requireAutoTime = false; // Can only be set by a device owner.
        boolean forceEphemeralUsers = false; // Can only be set by a device owner.
        boolean isNetworkLoggingEnabled = false; // Can only be set by a device owner.
        boolean isLogoutEnabled = false; // Can only be set by a device owner.

        // one notification after enabling + one more after reboots
        static final int DEF_MAXIMUM_NETWORK_LOGGING_NOTIFICATIONS_SHOWN = 2;
        int numNetworkLoggingNotifications = 0;
        long lastNetworkLoggingNotificationTimeMs = 0; // Time in milliseconds since epoch

        ActiveAdmin parentAdmin;
        final boolean isParent;

        static class TrustAgentInfo {
            public PersistableBundle options;
            TrustAgentInfo(PersistableBundle bundle) {
                options = bundle;
            }
        }

        // The list of packages which are not allowed to use metered data.
        List<String> meteredDisabledPackages;

        final Set<String> accountTypesWithManagementDisabled = new ArraySet<>();

        // The list of permitted accessibility services package namesas set by a profile
        // or device owner. Null means all accessibility services are allowed, empty means
        // none except system services are allowed.
        List<String> permittedAccessiblityServices;

        // The list of permitted input methods package names as set by a profile or device owner.
        // Null means all input methods are allowed, empty means none except system imes are
        // allowed.
        List<String> permittedInputMethods;

        // The list of packages allowed to use a NotificationListenerService to receive events for
        // notifications from this user. Null means that all packages are allowed. Empty list means
        // that only packages from the system are allowed.
        List<String> permittedNotificationListeners;

        // List of package names to keep cached.
        List<String> keepUninstalledPackages;

        // TODO: review implementation decisions with frameworks team
        boolean specifiesGlobalProxy = false;
        String globalProxySpec = null;
        String globalProxyExclusionList = null;

        ArrayMap<String, TrustAgentInfo> trustAgentInfos = new ArrayMap<>();

        List<String> crossProfileWidgetProviders;

        Bundle userRestrictions;

        // User restrictions that have already been enabled by default for this admin (either when
        // setting the device or profile owner, or during a system update if one of those "enabled
        // by default" restrictions is newly added).
        final Set<String> defaultEnabledRestrictionsAlreadySet = new ArraySet<>();

        // Support text provided by the admin to display to the user.
        CharSequence shortSupportMessage = null;
        CharSequence longSupportMessage = null;

        // Background color of confirm credentials screen. Default: teal.
        static final int DEF_ORGANIZATION_COLOR = Color.parseColor("#00796B");
        int organizationColor = DEF_ORGANIZATION_COLOR;

        // Default title of confirm credentials screen
        String organizationName = null;

        // The component name of the backup transport which has to be used if backups are mandatory
        // or null if backups are not mandatory.
        ComponentName mandatoryBackupTransport = null;

        // Message for user switcher
        String startUserSessionMessage = null;
        String endUserSessionMessage = null;

        ActiveAdmin(DeviceAdminInfo _info, boolean parent) {
            info = _info;
            isParent = parent;
        }

        ActiveAdmin getParentActiveAdmin() {
            Preconditions.checkState(!isParent);

            if (parentAdmin == null) {
                parentAdmin = new ActiveAdmin(info, /* parent */ true);
            }
            return parentAdmin;
        }

        boolean hasParentActiveAdmin() {
            return parentAdmin != null;
        }

        int getUid() { return info.getActivityInfo().applicationInfo.uid; }

        public UserHandle getUserHandle() {
            return UserHandle.of(UserHandle.getUserId(info.getActivityInfo().applicationInfo.uid));
        }

        void writeToXml(XmlSerializer out)
                throws IllegalArgumentException, IllegalStateException, IOException {
            out.startTag(null, TAG_POLICIES);
            info.writePoliciesToXml(out);
            out.endTag(null, TAG_POLICIES);
            if (minimumPasswordMetrics.quality != PASSWORD_QUALITY_UNSPECIFIED) {
                out.startTag(null, TAG_PASSWORD_QUALITY);
                out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.quality));
                out.endTag(null, TAG_PASSWORD_QUALITY);
                if (minimumPasswordMetrics.length != DEF_MINIMUM_PASSWORD_LENGTH) {
                    out.startTag(null, TAG_MIN_PASSWORD_LENGTH);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.length));
                    out.endTag(null, TAG_MIN_PASSWORD_LENGTH);
                }
                if(passwordHistoryLength != DEF_PASSWORD_HISTORY_LENGTH) {
                    out.startTag(null, TAG_PASSWORD_HISTORY_LENGTH);
                    out.attribute(null, ATTR_VALUE, Integer.toString(passwordHistoryLength));
                    out.endTag(null, TAG_PASSWORD_HISTORY_LENGTH);
                }
                if (minimumPasswordMetrics.upperCase != DEF_MINIMUM_PASSWORD_UPPER_CASE) {
                    out.startTag(null, TAG_MIN_PASSWORD_UPPERCASE);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.upperCase));
                    out.endTag(null, TAG_MIN_PASSWORD_UPPERCASE);
                }
                if (minimumPasswordMetrics.lowerCase != DEF_MINIMUM_PASSWORD_LOWER_CASE) {
                    out.startTag(null, TAG_MIN_PASSWORD_LOWERCASE);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.lowerCase));
                    out.endTag(null, TAG_MIN_PASSWORD_LOWERCASE);
                }
                if (minimumPasswordMetrics.letters != DEF_MINIMUM_PASSWORD_LETTERS) {
                    out.startTag(null, TAG_MIN_PASSWORD_LETTERS);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.letters));
                    out.endTag(null, TAG_MIN_PASSWORD_LETTERS);
                }
                if (minimumPasswordMetrics.numeric != DEF_MINIMUM_PASSWORD_NUMERIC) {
                    out.startTag(null, TAG_MIN_PASSWORD_NUMERIC);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.numeric));
                    out.endTag(null, TAG_MIN_PASSWORD_NUMERIC);
                }
                if (minimumPasswordMetrics.symbols != DEF_MINIMUM_PASSWORD_SYMBOLS) {
                    out.startTag(null, TAG_MIN_PASSWORD_SYMBOLS);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.symbols));
                    out.endTag(null, TAG_MIN_PASSWORD_SYMBOLS);
                }
                if (minimumPasswordMetrics.nonLetter > DEF_MINIMUM_PASSWORD_NON_LETTER) {
                    out.startTag(null, TAG_MIN_PASSWORD_NONLETTER);
                    out.attribute(
                            null, ATTR_VALUE, Integer.toString(minimumPasswordMetrics.nonLetter));
                    out.endTag(null, TAG_MIN_PASSWORD_NONLETTER);
                }
            }
            if (maximumTimeToUnlock != DEF_MAXIMUM_TIME_TO_UNLOCK) {
                out.startTag(null, TAG_MAX_TIME_TO_UNLOCK);
                out.attribute(null, ATTR_VALUE, Long.toString(maximumTimeToUnlock));
                out.endTag(null, TAG_MAX_TIME_TO_UNLOCK);
            }
            if (strongAuthUnlockTimeout != DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS) {
                out.startTag(null, TAG_STRONG_AUTH_UNLOCK_TIMEOUT);
                out.attribute(null, ATTR_VALUE, Long.toString(strongAuthUnlockTimeout));
                out.endTag(null, TAG_STRONG_AUTH_UNLOCK_TIMEOUT);
            }
            if (maximumFailedPasswordsForWipe != DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE) {
                out.startTag(null, TAG_MAX_FAILED_PASSWORD_WIPE);
                out.attribute(null, ATTR_VALUE, Integer.toString(maximumFailedPasswordsForWipe));
                out.endTag(null, TAG_MAX_FAILED_PASSWORD_WIPE);
            }
            if (specifiesGlobalProxy) {
                out.startTag(null, TAG_SPECIFIES_GLOBAL_PROXY);
                out.attribute(null, ATTR_VALUE, Boolean.toString(specifiesGlobalProxy));
                out.endTag(null, TAG_SPECIFIES_GLOBAL_PROXY);
                if (globalProxySpec != null) {
                    out.startTag(null, TAG_GLOBAL_PROXY_SPEC);
                    out.attribute(null, ATTR_VALUE, globalProxySpec);
                    out.endTag(null, TAG_GLOBAL_PROXY_SPEC);
                }
                if (globalProxyExclusionList != null) {
                    out.startTag(null, TAG_GLOBAL_PROXY_EXCLUSION_LIST);
                    out.attribute(null, ATTR_VALUE, globalProxyExclusionList);
                    out.endTag(null, TAG_GLOBAL_PROXY_EXCLUSION_LIST);
                }
            }
            if (passwordExpirationTimeout != DEF_PASSWORD_EXPIRATION_TIMEOUT) {
                out.startTag(null, TAG_PASSWORD_EXPIRATION_TIMEOUT);
                out.attribute(null, ATTR_VALUE, Long.toString(passwordExpirationTimeout));
                out.endTag(null, TAG_PASSWORD_EXPIRATION_TIMEOUT);
            }
            if (passwordExpirationDate != DEF_PASSWORD_EXPIRATION_DATE) {
                out.startTag(null, TAG_PASSWORD_EXPIRATION_DATE);
                out.attribute(null, ATTR_VALUE, Long.toString(passwordExpirationDate));
                out.endTag(null, TAG_PASSWORD_EXPIRATION_DATE);
            }
            if (encryptionRequested) {
                out.startTag(null, TAG_ENCRYPTION_REQUESTED);
                out.attribute(null, ATTR_VALUE, Boolean.toString(encryptionRequested));
                out.endTag(null, TAG_ENCRYPTION_REQUESTED);
            }
            if (testOnlyAdmin) {
                out.startTag(null, TAG_TEST_ONLY_ADMIN);
                out.attribute(null, ATTR_VALUE, Boolean.toString(testOnlyAdmin));
                out.endTag(null, TAG_TEST_ONLY_ADMIN);
            }
            if (disableCamera) {
                out.startTag(null, TAG_DISABLE_CAMERA);
                out.attribute(null, ATTR_VALUE, Boolean.toString(disableCamera));
                out.endTag(null, TAG_DISABLE_CAMERA);
            }
            if (disableCallerId) {
                out.startTag(null, TAG_DISABLE_CALLER_ID);
                out.attribute(null, ATTR_VALUE, Boolean.toString(disableCallerId));
                out.endTag(null, TAG_DISABLE_CALLER_ID);
            }
            if (disableContactsSearch) {
                out.startTag(null, TAG_DISABLE_CONTACTS_SEARCH);
                out.attribute(null, ATTR_VALUE, Boolean.toString(disableContactsSearch));
                out.endTag(null, TAG_DISABLE_CONTACTS_SEARCH);
            }
            if (!disableBluetoothContactSharing) {
                out.startTag(null, TAG_DISABLE_BLUETOOTH_CONTACT_SHARING);
                out.attribute(null, ATTR_VALUE,
                        Boolean.toString(disableBluetoothContactSharing));
                out.endTag(null, TAG_DISABLE_BLUETOOTH_CONTACT_SHARING);
            }
            if (disableScreenCapture) {
                out.startTag(null, TAG_DISABLE_SCREEN_CAPTURE);
                out.attribute(null, ATTR_VALUE, Boolean.toString(disableScreenCapture));
                out.endTag(null, TAG_DISABLE_SCREEN_CAPTURE);
            }
            if (requireAutoTime) {
                out.startTag(null, TAG_REQUIRE_AUTO_TIME);
                out.attribute(null, ATTR_VALUE, Boolean.toString(requireAutoTime));
                out.endTag(null, TAG_REQUIRE_AUTO_TIME);
            }
            if (forceEphemeralUsers) {
                out.startTag(null, TAG_FORCE_EPHEMERAL_USERS);
                out.attribute(null, ATTR_VALUE, Boolean.toString(forceEphemeralUsers));
                out.endTag(null, TAG_FORCE_EPHEMERAL_USERS);
            }
            if (isNetworkLoggingEnabled) {
                out.startTag(null, TAG_IS_NETWORK_LOGGING_ENABLED);
                out.attribute(null, ATTR_VALUE, Boolean.toString(isNetworkLoggingEnabled));
                out.attribute(null, ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS,
                        Integer.toString(numNetworkLoggingNotifications));
                out.attribute(null, ATTR_LAST_NETWORK_LOGGING_NOTIFICATION,
                        Long.toString(lastNetworkLoggingNotificationTimeMs));
                out.endTag(null, TAG_IS_NETWORK_LOGGING_ENABLED);
            }
            if (disabledKeyguardFeatures != DEF_KEYGUARD_FEATURES_DISABLED) {
                out.startTag(null, TAG_DISABLE_KEYGUARD_FEATURES);
                out.attribute(null, ATTR_VALUE, Integer.toString(disabledKeyguardFeatures));
                out.endTag(null, TAG_DISABLE_KEYGUARD_FEATURES);
            }
            if (!accountTypesWithManagementDisabled.isEmpty()) {
                out.startTag(null, TAG_DISABLE_ACCOUNT_MANAGEMENT);
                writeAttributeValuesToXml(
                        out, TAG_ACCOUNT_TYPE, accountTypesWithManagementDisabled);
                out.endTag(null,  TAG_DISABLE_ACCOUNT_MANAGEMENT);
            }
            if (!trustAgentInfos.isEmpty()) {
                Set<Entry<String, TrustAgentInfo>> set = trustAgentInfos.entrySet();
                out.startTag(null, TAG_MANAGE_TRUST_AGENT_FEATURES);
                for (Entry<String, TrustAgentInfo> entry : set) {
                    TrustAgentInfo trustAgentInfo = entry.getValue();
                    out.startTag(null, TAG_TRUST_AGENT_COMPONENT);
                    out.attribute(null, ATTR_VALUE, entry.getKey());
                    if (trustAgentInfo.options != null) {
                        out.startTag(null, TAG_TRUST_AGENT_COMPONENT_OPTIONS);
                        try {
                            trustAgentInfo.options.saveToXml(out);
                        } catch (XmlPullParserException e) {
                            Log.e(LOG_TAG, "Failed to save TrustAgent options", e);
                        }
                        out.endTag(null, TAG_TRUST_AGENT_COMPONENT_OPTIONS);
                    }
                    out.endTag(null, TAG_TRUST_AGENT_COMPONENT);
                }
                out.endTag(null, TAG_MANAGE_TRUST_AGENT_FEATURES);
            }
            if (crossProfileWidgetProviders != null && !crossProfileWidgetProviders.isEmpty()) {
                out.startTag(null, TAG_CROSS_PROFILE_WIDGET_PROVIDERS);
                writeAttributeValuesToXml(out, TAG_PROVIDER, crossProfileWidgetProviders);
                out.endTag(null, TAG_CROSS_PROFILE_WIDGET_PROVIDERS);
            }
            writePackageListToXml(out, TAG_PERMITTED_ACCESSIBILITY_SERVICES,
                    permittedAccessiblityServices);
            writePackageListToXml(out, TAG_PERMITTED_IMES, permittedInputMethods);
            writePackageListToXml(out, TAG_PERMITTED_NOTIFICATION_LISTENERS,
                    permittedNotificationListeners);
            writePackageListToXml(out, TAG_KEEP_UNINSTALLED_PACKAGES, keepUninstalledPackages);
            writePackageListToXml(out, TAG_METERED_DATA_DISABLED_PACKAGES, meteredDisabledPackages);
            if (hasUserRestrictions()) {
                UserRestrictionsUtils.writeRestrictions(
                        out, userRestrictions, TAG_USER_RESTRICTIONS);
            }
            if (!defaultEnabledRestrictionsAlreadySet.isEmpty()) {
                out.startTag(null, TAG_DEFAULT_ENABLED_USER_RESTRICTIONS);
                writeAttributeValuesToXml(
                        out, TAG_RESTRICTION, defaultEnabledRestrictionsAlreadySet);
                out.endTag(null, TAG_DEFAULT_ENABLED_USER_RESTRICTIONS);
            }
            if (!TextUtils.isEmpty(shortSupportMessage)) {
                out.startTag(null, TAG_SHORT_SUPPORT_MESSAGE);
                out.text(shortSupportMessage.toString());
                out.endTag(null, TAG_SHORT_SUPPORT_MESSAGE);
            }
            if (!TextUtils.isEmpty(longSupportMessage)) {
                out.startTag(null, TAG_LONG_SUPPORT_MESSAGE);
                out.text(longSupportMessage.toString());
                out.endTag(null, TAG_LONG_SUPPORT_MESSAGE);
            }
            if (parentAdmin != null) {
                out.startTag(null, TAG_PARENT_ADMIN);
                parentAdmin.writeToXml(out);
                out.endTag(null, TAG_PARENT_ADMIN);
            }
            if (organizationColor != DEF_ORGANIZATION_COLOR) {
                out.startTag(null, TAG_ORGANIZATION_COLOR);
                out.attribute(null, ATTR_VALUE, Integer.toString(organizationColor));
                out.endTag(null, TAG_ORGANIZATION_COLOR);
            }
            if (organizationName != null) {
                out.startTag(null, TAG_ORGANIZATION_NAME);
                out.text(organizationName);
                out.endTag(null, TAG_ORGANIZATION_NAME);
            }
            if (isLogoutEnabled) {
                out.startTag(null, TAG_IS_LOGOUT_ENABLED);
                out.attribute(null, ATTR_VALUE, Boolean.toString(isLogoutEnabled));
                out.endTag(null, TAG_IS_LOGOUT_ENABLED);
            }
            if (mandatoryBackupTransport != null) {
                out.startTag(null, TAG_MANDATORY_BACKUP_TRANSPORT);
                out.attribute(null, ATTR_VALUE, mandatoryBackupTransport.flattenToString());
                out.endTag(null, TAG_MANDATORY_BACKUP_TRANSPORT);
            }
            if (startUserSessionMessage != null) {
                out.startTag(null, TAG_START_USER_SESSION_MESSAGE);
                out.text(startUserSessionMessage);
                out.endTag(null, TAG_START_USER_SESSION_MESSAGE);
            }
            if (endUserSessionMessage != null) {
                out.startTag(null, TAG_END_USER_SESSION_MESSAGE);
                out.text(endUserSessionMessage);
                out.endTag(null, TAG_END_USER_SESSION_MESSAGE);
            }
        }

        void writePackageListToXml(XmlSerializer out, String outerTag,
                List<String> packageList)
                throws IllegalArgumentException, IllegalStateException, IOException {
            if (packageList == null) {
                return;
            }

            out.startTag(null, outerTag);
            writeAttributeValuesToXml(out, TAG_PACKAGE_LIST_ITEM, packageList);
            out.endTag(null, outerTag);
        }

        void writeAttributeValuesToXml(XmlSerializer out, String tag,
                @NonNull Collection<String> values) throws IOException {
            for (String value : values) {
                out.startTag(null, tag);
                out.attribute(null, ATTR_VALUE, value);
                out.endTag(null, tag);
            }
        }

        void readFromXml(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            int outerDepth = parser.getDepth();
            int type;
            while ((type=parser.next()) != END_DOCUMENT
                   && (type != END_TAG || parser.getDepth() > outerDepth)) {
                if (type == END_TAG || type == TEXT) {
                    continue;
                }
                String tag = parser.getName();
                if (TAG_POLICIES.equals(tag)) {
                    info.readPoliciesFromXml(parser);
                } else if (TAG_PASSWORD_QUALITY.equals(tag)) {
                    minimumPasswordMetrics.quality = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_LENGTH.equals(tag)) {
                    minimumPasswordMetrics.length = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_PASSWORD_HISTORY_LENGTH.equals(tag)) {
                    passwordHistoryLength = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_UPPERCASE.equals(tag)) {
                    minimumPasswordMetrics.upperCase = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_LOWERCASE.equals(tag)) {
                    minimumPasswordMetrics.lowerCase = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_LETTERS.equals(tag)) {
                    minimumPasswordMetrics.letters = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_NUMERIC.equals(tag)) {
                    minimumPasswordMetrics.numeric = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_SYMBOLS.equals(tag)) {
                    minimumPasswordMetrics.symbols = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_NONLETTER.equals(tag)) {
                    minimumPasswordMetrics.nonLetter = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                }else if (TAG_MAX_TIME_TO_UNLOCK.equals(tag)) {
                    maximumTimeToUnlock = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_STRONG_AUTH_UNLOCK_TIMEOUT.equals(tag)) {
                    strongAuthUnlockTimeout = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MAX_FAILED_PASSWORD_WIPE.equals(tag)) {
                    maximumFailedPasswordsForWipe = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_SPECIFIES_GLOBAL_PROXY.equals(tag)) {
                    specifiesGlobalProxy = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_GLOBAL_PROXY_SPEC.equals(tag)) {
                    globalProxySpec =
                        parser.getAttributeValue(null, ATTR_VALUE);
                } else if (TAG_GLOBAL_PROXY_EXCLUSION_LIST.equals(tag)) {
                    globalProxyExclusionList =
                        parser.getAttributeValue(null, ATTR_VALUE);
                } else if (TAG_PASSWORD_EXPIRATION_TIMEOUT.equals(tag)) {
                    passwordExpirationTimeout = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_PASSWORD_EXPIRATION_DATE.equals(tag)) {
                    passwordExpirationDate = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_ENCRYPTION_REQUESTED.equals(tag)) {
                    encryptionRequested = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_TEST_ONLY_ADMIN.equals(tag)) {
                    testOnlyAdmin = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_CAMERA.equals(tag)) {
                    disableCamera = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_CALLER_ID.equals(tag)) {
                    disableCallerId = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_CONTACTS_SEARCH.equals(tag)) {
                    disableContactsSearch = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_BLUETOOTH_CONTACT_SHARING.equals(tag)) {
                    disableBluetoothContactSharing = Boolean.parseBoolean(parser
                            .getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_SCREEN_CAPTURE.equals(tag)) {
                    disableScreenCapture = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_REQUIRE_AUTO_TIME.equals(tag)) {
                    requireAutoTime = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_FORCE_EPHEMERAL_USERS.equals(tag)) {
                    forceEphemeralUsers = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_IS_NETWORK_LOGGING_ENABLED.equals(tag)) {
                    isNetworkLoggingEnabled = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                    lastNetworkLoggingNotificationTimeMs = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_LAST_NETWORK_LOGGING_NOTIFICATION));
                    numNetworkLoggingNotifications = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_NUM_NETWORK_LOGGING_NOTIFICATIONS));
                } else if (TAG_DISABLE_KEYGUARD_FEATURES.equals(tag)) {
                    disabledKeyguardFeatures = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_ACCOUNT_MANAGEMENT.equals(tag)) {
                    readAttributeValues(
                            parser, TAG_ACCOUNT_TYPE, accountTypesWithManagementDisabled);
                } else if (TAG_MANAGE_TRUST_AGENT_FEATURES.equals(tag)) {
                    trustAgentInfos = getAllTrustAgentInfos(parser, tag);
                } else if (TAG_CROSS_PROFILE_WIDGET_PROVIDERS.equals(tag)) {
                    crossProfileWidgetProviders = new ArrayList<>();
                    readAttributeValues(parser, TAG_PROVIDER, crossProfileWidgetProviders);
                } else if (TAG_PERMITTED_ACCESSIBILITY_SERVICES.equals(tag)) {
                    permittedAccessiblityServices = readPackageList(parser, tag);
                } else if (TAG_PERMITTED_IMES.equals(tag)) {
                    permittedInputMethods = readPackageList(parser, tag);
                } else if (TAG_PERMITTED_NOTIFICATION_LISTENERS.equals(tag)) {
                    permittedNotificationListeners = readPackageList(parser, tag);
                } else if (TAG_KEEP_UNINSTALLED_PACKAGES.equals(tag)) {
                    keepUninstalledPackages = readPackageList(parser, tag);
                } else if (TAG_METERED_DATA_DISABLED_PACKAGES.equals(tag)) {
                    meteredDisabledPackages = readPackageList(parser, tag);
                } else if (TAG_USER_RESTRICTIONS.equals(tag)) {
                    userRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_DEFAULT_ENABLED_USER_RESTRICTIONS.equals(tag)) {
                    readAttributeValues(
                            parser, TAG_RESTRICTION, defaultEnabledRestrictionsAlreadySet);
                } else if (TAG_SHORT_SUPPORT_MESSAGE.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        shortSupportMessage = parser.getText();
                    } else {
                        Log.w(LOG_TAG, "Missing text when loading short support message");
                    }
                } else if (TAG_LONG_SUPPORT_MESSAGE.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        longSupportMessage = parser.getText();
                    } else {
                        Log.w(LOG_TAG, "Missing text when loading long support message");
                    }
                } else if (TAG_PARENT_ADMIN.equals(tag)) {
                    Preconditions.checkState(!isParent);

                    parentAdmin = new ActiveAdmin(info, /* parent */ true);
                    parentAdmin.readFromXml(parser);
                } else if (TAG_ORGANIZATION_COLOR.equals(tag)) {
                    organizationColor = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_ORGANIZATION_NAME.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        organizationName = parser.getText();
                    } else {
                        Log.w(LOG_TAG, "Missing text when loading organization name");
                    }
                } else if (TAG_IS_LOGOUT_ENABLED.equals(tag)) {
                    isLogoutEnabled = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MANDATORY_BACKUP_TRANSPORT.equals(tag)) {
                    mandatoryBackupTransport = ComponentName.unflattenFromString(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_START_USER_SESSION_MESSAGE.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        startUserSessionMessage = parser.getText();
                    } else {
                        Log.w(LOG_TAG, "Missing text when loading start session message");
                    }
                } else if (TAG_END_USER_SESSION_MESSAGE.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        endUserSessionMessage = parser.getText();
                    } else {
                        Log.w(LOG_TAG, "Missing text when loading end session message");
                    }
                } else {
                    Slog.w(LOG_TAG, "Unknown admin tag: " + tag);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }

        private List<String> readPackageList(XmlPullParser parser,
                String tag) throws XmlPullParserException, IOException {
            List<String> result = new ArrayList<String>();
            int outerDepth = parser.getDepth();
            int outerType;
            while ((outerType=parser.next()) != XmlPullParser.END_DOCUMENT
                    && (outerType != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (outerType == XmlPullParser.END_TAG || outerType == XmlPullParser.TEXT) {
                    continue;
                }
                String outerTag = parser.getName();
                if (TAG_PACKAGE_LIST_ITEM.equals(outerTag)) {
                    String packageName = parser.getAttributeValue(null, ATTR_VALUE);
                    if (packageName != null) {
                        result.add(packageName);
                    } else {
                        Slog.w(LOG_TAG, "Package name missing under " + outerTag);
                    }
                } else {
                    Slog.w(LOG_TAG, "Unknown tag under " + tag +  ": " + outerTag);
                }
            }
            return result;
        }

        private void readAttributeValues(
                XmlPullParser parser, String tag, Collection<String> result)
                throws XmlPullParserException, IOException {
            result.clear();
            int outerDepthDAM = parser.getDepth();
            int typeDAM;
            while ((typeDAM=parser.next()) != END_DOCUMENT
                    && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
                if (typeDAM == END_TAG || typeDAM == TEXT) {
                    continue;
                }
                String tagDAM = parser.getName();
                if (tag.equals(tagDAM)) {
                    result.add(parser.getAttributeValue(null, ATTR_VALUE));
                } else {
                    Slog.e(LOG_TAG, "Expected tag " + tag +  " but found " + tagDAM);
                }
            }
        }

        private ArrayMap<String, TrustAgentInfo> getAllTrustAgentInfos(
                XmlPullParser parser, String tag) throws XmlPullParserException, IOException {
            int outerDepthDAM = parser.getDepth();
            int typeDAM;
            final ArrayMap<String, TrustAgentInfo> result = new ArrayMap<>();
            while ((typeDAM=parser.next()) != END_DOCUMENT
                    && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
                if (typeDAM == END_TAG || typeDAM == TEXT) {
                    continue;
                }
                String tagDAM = parser.getName();
                if (TAG_TRUST_AGENT_COMPONENT.equals(tagDAM)) {
                    final String component = parser.getAttributeValue(null, ATTR_VALUE);
                    final TrustAgentInfo trustAgentInfo = getTrustAgentInfo(parser, tag);
                    result.put(component, trustAgentInfo);
                } else {
                    Slog.w(LOG_TAG, "Unknown tag under " + tag +  ": " + tagDAM);
                }
            }
            return result;
        }

        private TrustAgentInfo getTrustAgentInfo(XmlPullParser parser, String tag)
                throws XmlPullParserException, IOException  {
            int outerDepthDAM = parser.getDepth();
            int typeDAM;
            TrustAgentInfo result = new TrustAgentInfo(null);
            while ((typeDAM=parser.next()) != END_DOCUMENT
                    && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
                if (typeDAM == END_TAG || typeDAM == TEXT) {
                    continue;
                }
                String tagDAM = parser.getName();
                if (TAG_TRUST_AGENT_COMPONENT_OPTIONS.equals(tagDAM)) {
                    result.options = PersistableBundle.restoreFromXml(parser);
                } else {
                    Slog.w(LOG_TAG, "Unknown tag under " + tag +  ": " + tagDAM);
                }
            }
            return result;
        }

        boolean hasUserRestrictions() {
            return userRestrictions != null && userRestrictions.size() > 0;
        }

        Bundle ensureUserRestrictions() {
            if (userRestrictions == null) {
                userRestrictions = new Bundle();
            }
            return userRestrictions;
        }

        public void transfer(DeviceAdminInfo deviceAdminInfo) {
            if (hasParentActiveAdmin()) {
                parentAdmin.info = deviceAdminInfo;
            }
            info = deviceAdminInfo;
        }

        void dump(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("uid="); pw.println(getUid());
            pw.print(prefix); pw.print("testOnlyAdmin=");
            pw.println(testOnlyAdmin);
            pw.print(prefix); pw.println("policies:");
            ArrayList<DeviceAdminInfo.PolicyInfo> pols = info.getUsedPolicies();
            if (pols != null) {
                for (int i=0; i<pols.size(); i++) {
                    pw.print(prefix); pw.print("  "); pw.println(pols.get(i).tag);
                }
            }
            pw.print(prefix); pw.print("passwordQuality=0x");
                    pw.println(Integer.toHexString(minimumPasswordMetrics.quality));
            pw.print(prefix); pw.print("minimumPasswordLength=");
                    pw.println(minimumPasswordMetrics.length);
            pw.print(prefix); pw.print("passwordHistoryLength=");
                    pw.println(passwordHistoryLength);
            pw.print(prefix); pw.print("minimumPasswordUpperCase=");
                    pw.println(minimumPasswordMetrics.upperCase);
            pw.print(prefix); pw.print("minimumPasswordLowerCase=");
                    pw.println(minimumPasswordMetrics.lowerCase);
            pw.print(prefix); pw.print("minimumPasswordLetters=");
                    pw.println(minimumPasswordMetrics.letters);
            pw.print(prefix); pw.print("minimumPasswordNumeric=");
                    pw.println(minimumPasswordMetrics.numeric);
            pw.print(prefix); pw.print("minimumPasswordSymbols=");
                    pw.println(minimumPasswordMetrics.symbols);
            pw.print(prefix); pw.print("minimumPasswordNonLetter=");
                    pw.println(minimumPasswordMetrics.nonLetter);
            pw.print(prefix); pw.print("maximumTimeToUnlock=");
                    pw.println(maximumTimeToUnlock);
            pw.print(prefix); pw.print("strongAuthUnlockTimeout=");
                    pw.println(strongAuthUnlockTimeout);
            pw.print(prefix); pw.print("maximumFailedPasswordsForWipe=");
                    pw.println(maximumFailedPasswordsForWipe);
            pw.print(prefix); pw.print("specifiesGlobalProxy=");
                    pw.println(specifiesGlobalProxy);
            pw.print(prefix); pw.print("passwordExpirationTimeout=");
                    pw.println(passwordExpirationTimeout);
            pw.print(prefix); pw.print("passwordExpirationDate=");
                    pw.println(passwordExpirationDate);
            if (globalProxySpec != null) {
                pw.print(prefix); pw.print("globalProxySpec=");
                        pw.println(globalProxySpec);
            }
            if (globalProxyExclusionList != null) {
                pw.print(prefix); pw.print("globalProxyEclusionList=");
                        pw.println(globalProxyExclusionList);
            }
            pw.print(prefix); pw.print("encryptionRequested=");
                    pw.println(encryptionRequested);
            pw.print(prefix); pw.print("disableCamera=");
                    pw.println(disableCamera);
            pw.print(prefix); pw.print("disableCallerId=");
                    pw.println(disableCallerId);
            pw.print(prefix); pw.print("disableContactsSearch=");
                    pw.println(disableContactsSearch);
            pw.print(prefix); pw.print("disableBluetoothContactSharing=");
                    pw.println(disableBluetoothContactSharing);
            pw.print(prefix); pw.print("disableScreenCapture=");
                    pw.println(disableScreenCapture);
            pw.print(prefix); pw.print("requireAutoTime=");
                    pw.println(requireAutoTime);
            pw.print(prefix); pw.print("forceEphemeralUsers=");
                    pw.println(forceEphemeralUsers);
            pw.print(prefix); pw.print("isNetworkLoggingEnabled=");
                    pw.println(isNetworkLoggingEnabled);
            pw.print(prefix); pw.print("disabledKeyguardFeatures=");
                    pw.println(disabledKeyguardFeatures);
            pw.print(prefix); pw.print("crossProfileWidgetProviders=");
                    pw.println(crossProfileWidgetProviders);
            if (permittedAccessiblityServices != null) {
                pw.print(prefix); pw.print("permittedAccessibilityServices=");
                    pw.println(permittedAccessiblityServices);
            }
            if (permittedInputMethods != null) {
                pw.print(prefix); pw.print("permittedInputMethods=");
                    pw.println(permittedInputMethods);
            }
            if (permittedNotificationListeners != null) {
                pw.print(prefix); pw.print("permittedNotificationListeners=");
                pw.println(permittedNotificationListeners);
            }
            if (keepUninstalledPackages != null) {
                pw.print(prefix); pw.print("keepUninstalledPackages=");
                    pw.println(keepUninstalledPackages);
            }
            pw.print(prefix); pw.print("organizationColor=");
                    pw.println(organizationColor);
            if (organizationName != null) {
                pw.print(prefix); pw.print("organizationName=");
                    pw.println(organizationName);
            }
            pw.print(prefix); pw.println("userRestrictions:");
            UserRestrictionsUtils.dumpRestrictions(pw, prefix + "  ", userRestrictions);
            pw.print(prefix); pw.print("defaultEnabledRestrictionsAlreadySet=");
                    pw.println(defaultEnabledRestrictionsAlreadySet);
            pw.print(prefix); pw.print("isParent=");
                    pw.println(isParent);
            if (parentAdmin != null) {
                pw.print(prefix);  pw.println("parentAdmin:");
                parentAdmin.dump(prefix + "  ", pw);
            }
        }
    }

    private void handlePackagesChanged(@Nullable String packageName, int userHandle) {
        boolean removedAdmin = false;
        if (VERBOSE_LOG) {
            Slog.d(LOG_TAG, "Handling package changes package " + packageName
                    + " for user " + userHandle);
        }
        DevicePolicyData policy = getUserData(userHandle);
        synchronized (getLockObject()) {
            for (int i = policy.mAdminList.size() - 1; i >= 0; i--) {
                ActiveAdmin aa = policy.mAdminList.get(i);
                try {
                    // If we're checking all packages or if the specific one we're checking matches,
                    // then check if the package and receiver still exist.
                    final String adminPackage = aa.info.getPackageName();
                    if (packageName == null || packageName.equals(adminPackage)) {
                        if (mIPackageManager.getPackageInfo(adminPackage, 0, userHandle) == null
                                || mIPackageManager.getReceiverInfo(aa.info.getComponent(),
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                userHandle) == null) {
                            removedAdmin = true;
                            policy.mAdminList.remove(i);
                            policy.mAdminMap.remove(aa.info.getComponent());
                            pushActiveAdminPackagesLocked(userHandle);
                            pushMeteredDisabledPackagesLocked(userHandle);
                        }
                    }
                } catch (RemoteException re) {
                    // Shouldn't happen.
                }
            }
            if (removedAdmin) {
                validatePasswordOwnerLocked(policy);
            }

            boolean removedDelegate = false;

            // Check if a delegate was removed.
            for (int i = policy.mDelegationMap.size() - 1; i >= 0; i--) {
                final String delegatePackage = policy.mDelegationMap.keyAt(i);
                if (isRemovedPackage(packageName, delegatePackage, userHandle)) {
                    policy.mDelegationMap.removeAt(i);
                    removedDelegate = true;
                }
            }

            // If it's an owner package, we may need to refresh the bound connection.
            final ComponentName owner = getOwnerComponent(userHandle);
            if ((packageName != null) && (owner != null)
                    && (owner.getPackageName().equals(packageName))) {
                startOwnerService(userHandle, "package-broadcast");
            }

            // Persist updates if the removed package was an admin or delegate.
            if (removedAdmin || removedDelegate) {
                saveSettingsLocked(policy.mUserHandle);
            }
        }
        if (removedAdmin) {
            // The removed admin might have disabled camera, so update user restrictions.
            pushUserRestrictions(userHandle);
        }
    }

    private boolean isRemovedPackage(String changedPackage, String targetPackage, int userHandle) {
        try {
            return targetPackage != null
                    && (changedPackage == null || changedPackage.equals(targetPackage))
                    && mIPackageManager.getPackageInfo(targetPackage, 0, userHandle) == null;
        } catch (RemoteException e) {
            // Shouldn't happen
        }

        return false;
    }

    /**
     * Unit test will subclass it to inject mocks.
     */
    @VisibleForTesting
    static class Injector {

        public final Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        public boolean hasFeature() {
            return getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN);
        }

        Context createContextAsUser(UserHandle user) throws PackageManager.NameNotFoundException {
            final String packageName = mContext.getPackageName();
            return mContext.createPackageContextAsUser(packageName, 0, user);
        }

        Resources getResources() {
            return mContext.getResources();
        }

        Owners newOwners() {
            return new Owners(getUserManager(), getUserManagerInternal(),
                    getPackageManagerInternal());
        }

        UserManager getUserManager() {
            return UserManager.get(mContext);
        }

        UserManagerInternal getUserManagerInternal() {
            return LocalServices.getService(UserManagerInternal.class);
        }

        PackageManagerInternal getPackageManagerInternal() {
            return LocalServices.getService(PackageManagerInternal.class);
        }

        UsageStatsManagerInternal getUsageStatsManagerInternal() {
            return LocalServices.getService(UsageStatsManagerInternal.class);
        }

        NetworkPolicyManagerInternal getNetworkPolicyManagerInternal() {
            return LocalServices.getService(NetworkPolicyManagerInternal.class);
        }

        NotificationManager getNotificationManager() {
            return mContext.getSystemService(NotificationManager.class);
        }

        IIpConnectivityMetrics getIIpConnectivityMetrics() {
            return (IIpConnectivityMetrics) IIpConnectivityMetrics.Stub.asInterface(
                ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
        }

        PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        PowerManagerInternal getPowerManagerInternal() {
            return LocalServices.getService(PowerManagerInternal.class);
        }

        TelephonyManager getTelephonyManager() {
            return TelephonyManager.from(mContext);
        }

        TrustManager getTrustManager() {
            return (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
        }

        AlarmManager getAlarmManager() {
            return mContext.getSystemService(AlarmManager.class);
        }

        ConnectivityManager getConnectivityManager() {
            return mContext.getSystemService(ConnectivityManager.class);
        }

        IWindowManager getIWindowManager() {
            return IWindowManager.Stub
                    .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        }

        IActivityManager getIActivityManager() {
            return ActivityManager.getService();
        }

        ActivityManagerInternal getActivityManagerInternal() {
            return LocalServices.getService(ActivityManagerInternal.class);
        }

        IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        IBackupManager getIBackupManager() {
            return IBackupManager.Stub.asInterface(
                    ServiceManager.getService(Context.BACKUP_SERVICE));
        }

        IAudioService getIAudioService() {
            return IAudioService.Stub.asInterface(ServiceManager.getService(Context.AUDIO_SERVICE));
        }

        boolean isBuildDebuggable() {
            return Build.IS_DEBUGGABLE;
        }

        LockPatternUtils newLockPatternUtils() {
            return new LockPatternUtils(mContext);
        }

        boolean storageManagerIsFileBasedEncryptionEnabled() {
            return StorageManager.isFileEncryptedNativeOnly();
        }

        boolean storageManagerIsNonDefaultBlockEncrypted() {
            long identity = Binder.clearCallingIdentity();
            try {
                return StorageManager.isNonDefaultBlockEncrypted();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        boolean storageManagerIsEncrypted() {
            return StorageManager.isEncrypted();
        }

        boolean storageManagerIsEncryptable() {
            return StorageManager.isEncryptable();
        }

        Looper getMyLooper() {
            return Looper.myLooper();
        }

        WifiManager getWifiManager() {
            return mContext.getSystemService(WifiManager.class);
        }

        long binderClearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        void binderRestoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }

        int binderGetCallingUid() {
            return Binder.getCallingUid();
        }

        int binderGetCallingPid() {
            return Binder.getCallingPid();
        }

        UserHandle binderGetCallingUserHandle() {
            return Binder.getCallingUserHandle();
        }

        boolean binderIsCallingUidMyUid() {
            return getCallingUid() == Process.myUid();
        }

        void binderWithCleanCallingIdentity(@NonNull ThrowingRunnable action) {
             Binder.withCleanCallingIdentity(action);
        }

        final int userHandleGetCallingUserId() {
            return UserHandle.getUserId(binderGetCallingUid());
        }

        File environmentGetUserSystemDirectory(int userId) {
            return Environment.getUserSystemDirectory(userId);
        }

        void powerManagerGoToSleep(long time, int reason, int flags) {
            mContext.getSystemService(PowerManager.class).goToSleep(time, reason, flags);
        }

        void powerManagerReboot(String reason) {
            mContext.getSystemService(PowerManager.class).reboot(reason);
        }

        void recoverySystemRebootWipeUserData(boolean shutdown, String reason, boolean force,
                boolean wipeEuicc) throws IOException {
            RecoverySystem.rebootWipeUserData(mContext, shutdown, reason, force, wipeEuicc);
        }

        boolean systemPropertiesGetBoolean(String key, boolean def) {
            return SystemProperties.getBoolean(key, def);
        }

        long systemPropertiesGetLong(String key, long def) {
            return SystemProperties.getLong(key, def);
        }

        String systemPropertiesGet(String key, String def) {
            return SystemProperties.get(key, def);
        }

        String systemPropertiesGet(String key) {
            return SystemProperties.get(key);
        }

        void systemPropertiesSet(String key, String value) {
            SystemProperties.set(key, value);
        }

        boolean userManagerIsSplitSystemUser() {
            return UserManager.isSplitSystemUser();
        }

        String getDevicePolicyFilePathForSystemUser() {
            return "/data/system/";
        }

        PendingIntent pendingIntentGetActivityAsUser(Context context, int requestCode,
                @NonNull Intent intent, int flags, Bundle options, UserHandle user) {
            return PendingIntent.getActivityAsUser(
                    context, requestCode, intent, flags, options, user);
        }

        void registerContentObserver(Uri uri, boolean notifyForDescendents,
                ContentObserver observer, int userHandle) {
            mContext.getContentResolver().registerContentObserver(uri, notifyForDescendents,
                    observer, userHandle);
        }

        int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    name, def, userHandle);
        }

        String settingsSecureGetStringForUser(String name, int userHandle) {
            return Settings.Secure.getStringForUser(mContext.getContentResolver(), name,
                    userHandle);
        }

        void settingsSecurePutIntForUser(String name, int value, int userHandle) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    name, value, userHandle);
        }

        void settingsSecurePutStringForUser(String name, String value, int userHandle) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    name, value, userHandle);
        }

        void settingsGlobalPutStringForUser(String name, String value, int userHandle) {
            Settings.Global.putStringForUser(mContext.getContentResolver(),
                    name, value, userHandle);
        }

        void settingsSecurePutInt(String name, int value) {
            Settings.Secure.putInt(mContext.getContentResolver(), name, value);
        }

        int settingsGlobalGetInt(String name, int def) {
            return Settings.Global.getInt(mContext.getContentResolver(), name, def);
        }

        String settingsGlobalGetString(String name) {
            return Settings.Global.getString(mContext.getContentResolver(), name);
        }

        void settingsGlobalPutInt(String name, int value) {
            Settings.Global.putInt(mContext.getContentResolver(), name, value);
        }

        void settingsSecurePutString(String name, String value) {
            Settings.Secure.putString(mContext.getContentResolver(), name, value);
        }

        void settingsGlobalPutString(String name, String value) {
            Settings.Global.putString(mContext.getContentResolver(), name, value);
        }

        void settingsSystemPutStringForUser(String name, String value, int userId) {
          Settings.System.putStringForUser(
              mContext.getContentResolver(), name, value, userId);
        }

        void securityLogSetLoggingEnabledProperty(boolean enabled) {
            SecurityLog.setLoggingEnabledProperty(enabled);
        }

        boolean securityLogGetLoggingEnabledProperty() {
            return SecurityLog.getLoggingEnabledProperty();
        }

        boolean securityLogIsLoggingEnabled() {
            return SecurityLog.isLoggingEnabled();
        }

        KeyChainConnection keyChainBindAsUser(UserHandle user) throws InterruptedException {
            return KeyChain.bindAsUser(mContext, user);
        }

        void postOnSystemServerInitThreadPool(Runnable runnable) {
            SystemServerInitThreadPool.get().submit(runnable, LOG_TAG);
        }

        public TransferOwnershipMetadataManager newTransferOwnershipMetadataManager() {
            return new TransferOwnershipMetadataManager();
        }

        public void runCryptoSelfTest() {
            CryptoTestHelper.runAndLogSelfTest();
        }
    }

    /**
     * Instantiates the service.
     */
    public DevicePolicyManagerService(Context context) {
        this(new Injector(context));
    }

    @VisibleForTesting
    DevicePolicyManagerService(Injector injector) {
        mInjector = injector;
        mContext = Preconditions.checkNotNull(injector.mContext);
        mHandler = new Handler(Preconditions.checkNotNull(injector.getMyLooper()));
        mConstants = DevicePolicyConstants.loadFromString(
                mInjector.settingsGlobalGetString(Global.DEVICE_POLICY_CONSTANTS));

        mOwners = Preconditions.checkNotNull(injector.newOwners());

        mUserManager = Preconditions.checkNotNull(injector.getUserManager());
        mUserManagerInternal = Preconditions.checkNotNull(injector.getUserManagerInternal());
        mUsageStatsManagerInternal = Preconditions.checkNotNull(
                injector.getUsageStatsManagerInternal());
        mIPackageManager = Preconditions.checkNotNull(injector.getIPackageManager());
        mTelephonyManager = Preconditions.checkNotNull(injector.getTelephonyManager());

        mLocalService = new LocalService();
        mLockPatternUtils = injector.newLockPatternUtils();

        // TODO: why does SecurityLogMonitor need to be created even when mHasFeature == false?
        mSecurityLogMonitor = new SecurityLogMonitor(this);

        mHasFeature = mInjector.hasFeature();
        mIsWatch = mInjector.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_WATCH);
        mBackgroundHandler = BackgroundThread.getHandler();

        // Needed when mHasFeature == false, because it controls the certificate warning text.
        mCertificateMonitor = new CertificateMonitor(this, mInjector, mBackgroundHandler);

        mDeviceAdminServiceController = new DeviceAdminServiceController(this, mConstants);

        mOverlayPackagesProvider = new OverlayPackagesProvider(mContext);

        mTransferOwnershipMetadataManager = mInjector.newTransferOwnershipMetadataManager();

        if (!mHasFeature) {
            // Skip the rest of the initialization
            mSetupContentObserver = null;
            return;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(ACTION_EXPIRED_PASSWORD_NOTIFICATION);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_STARTED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);

        LocalServices.addService(DevicePolicyManagerInternal.class, mLocalService);

        mSetupContentObserver = new SetupContentObserver(mHandler);

        mUserManagerInternal.addUserRestrictionsListener(new RestrictionsListener(mContext));
    }

    /**
     * Creates and loads the policy data from xml.
     * @param userHandle the user for whom to load the policy data
     * @return
     */
    @NonNull
    DevicePolicyData getUserData(int userHandle) {
        synchronized (getLockObject()) {
            DevicePolicyData policy = mUserData.get(userHandle);
            if (policy == null) {
                policy = new DevicePolicyData(userHandle);
                mUserData.append(userHandle, policy);
                loadSettingsLocked(policy, userHandle);
            }
            return policy;
        }
    }

    /**
     * Provides PasswordMetrics object corresponding to the given user.
     * @param userHandle the user for whom to provide metrics.
     * @return the user password metrics, or {@code null} if none have been associated with
     * the user yet (for example, if the device has booted but not been unlocked).
     */
    PasswordMetrics getUserPasswordMetricsLocked(int userHandle) {
        return mUserPasswordMetrics.get(userHandle);
    }

    /**
     * Creates and loads the policy data from xml for data that is shared between
     * various profiles of a user. In contrast to {@link #getUserData(int)}
     * it allows access to data of users other than the calling user.
     *
     * This function should only be used for shared data, e.g. everything regarding
     * passwords and should be removed once multiple screen locks are present.
     * @param userHandle the user for whom to load the policy data
     * @return
     */
    DevicePolicyData getUserDataUnchecked(int userHandle) {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            return getUserData(userHandle);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    void removeUserData(int userHandle) {
        synchronized (getLockObject()) {
            if (userHandle == UserHandle.USER_SYSTEM) {
                Slog.w(LOG_TAG, "Tried to remove device policy file for user 0! Ignoring.");
                return;
            }
            mPolicyCache.onUserRemoved(userHandle);

            mOwners.removeProfileOwner(userHandle);
            mOwners.writeProfileOwner(userHandle);

            DevicePolicyData policy = mUserData.get(userHandle);
            if (policy != null) {
                mUserData.remove(userHandle);
            }
            if (mUserPasswordMetrics.get(userHandle) != null) {
                mUserPasswordMetrics.remove(userHandle);
            }

            File policyFile = new File(mInjector.environmentGetUserSystemDirectory(userHandle),
                    DEVICE_POLICIES_XML);
            policyFile.delete();
            Slog.i(LOG_TAG, "Removed device policy file " + policyFile.getAbsolutePath());
        }
    }

    void loadOwners() {
        synchronized (getLockObject()) {
            mOwners.load();
            setDeviceOwnerSystemPropertyLocked();
            findOwnerComponentIfNecessaryLocked();
            migrateUserRestrictionsIfNecessaryLocked();
            maybeSetDefaultDeviceOwnerUserRestrictionsLocked();

            // TODO PO may not have a class name either due to b/17652534.  Address that too.

            updateDeviceOwnerLocked();
        }
    }

    /** Apply default restrictions that haven't been applied to device owners yet. */
    private void maybeSetDefaultDeviceOwnerUserRestrictionsLocked() {
        final ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        if (deviceOwner != null) {
            maybeSetDefaultRestrictionsForAdminLocked(mOwners.getDeviceOwnerUserId(),
                    deviceOwner, UserRestrictionsUtils.getDefaultEnabledForDeviceOwner());
        }
    }

    /** Apply default restrictions that haven't been applied to profile owners yet. */
    private void maybeSetDefaultProfileOwnerUserRestrictions() {
        synchronized (getLockObject()) {
            for (final int userId : mOwners.getProfileOwnerKeys()) {
                final ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                // The following restrictions used to be applied to managed profiles by different
                // means (via Settings or by disabling components). Now they are proper user
                // restrictions so we apply them to managed profile owners. Non-managed secondary
                // users didn't have those restrictions so we skip them to keep existing behavior.
                if (profileOwner == null || !mUserManager.isManagedProfile(userId)) {
                    continue;
                }
                maybeSetDefaultRestrictionsForAdminLocked(userId, profileOwner,
                        UserRestrictionsUtils.getDefaultEnabledForManagedProfiles());
                ensureUnknownSourcesRestrictionForProfileOwnerLocked(
                        userId, profileOwner, false /* newOwner */);
            }
        }
    }

    /**
     * Checks whether {@link UserManager#DISALLOW_INSTALL_UNKNOWN_SOURCES} should be added to the
     * set of restrictions for this profile owner.
     */
    private void ensureUnknownSourcesRestrictionForProfileOwnerLocked(int userId,
            ActiveAdmin profileOwner, boolean newOwner) {
        if (newOwner || mInjector.settingsSecureGetIntForUser(
                Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED, 0, userId) != 0) {
            profileOwner.ensureUserRestrictions().putBoolean(
                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true);
            saveUserRestrictionsLocked(userId);
            mInjector.settingsSecurePutIntForUser(
                    Settings.Secure.UNKNOWN_SOURCES_DEFAULT_REVERSED, 0, userId);
        }
    }

    /**
     * Apply default restrictions that haven't been applied to a given admin yet.
     */
    private void maybeSetDefaultRestrictionsForAdminLocked(
            int userId, ActiveAdmin admin, Set<String> defaultRestrictions) {
        if (defaultRestrictions.equals(admin.defaultEnabledRestrictionsAlreadySet)) {
            return; // The same set of default restrictions has been already applied.
        }
        Slog.i(LOG_TAG, "New user restrictions need to be set by default for user " + userId);

        if (VERBOSE_LOG) {
            Slog.d(LOG_TAG,"Default enabled restrictions: "
                    + defaultRestrictions
                    + ". Restrictions already enabled: "
                    + admin.defaultEnabledRestrictionsAlreadySet);
        }

        final Set<String> restrictionsToSet = new ArraySet<>(defaultRestrictions);
        restrictionsToSet.removeAll(admin.defaultEnabledRestrictionsAlreadySet);
        if (!restrictionsToSet.isEmpty()) {
            for (final String restriction : restrictionsToSet) {
                admin.ensureUserRestrictions().putBoolean(restriction, true);
            }
            admin.defaultEnabledRestrictionsAlreadySet.addAll(restrictionsToSet);
            Slog.i(LOG_TAG, "Enabled the following restrictions by default: " + restrictionsToSet);
            saveUserRestrictionsLocked(userId);
        }
    }

    private void setDeviceOwnerSystemPropertyLocked() {
        final boolean deviceProvisioned =
                mInjector.settingsGlobalGetInt(Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        final boolean hasDeviceOwner = mOwners.hasDeviceOwner();
        // If the device is not provisioned and there is currently no device owner, do not set the
        // read-only system property yet, since Device owner may still be provisioned.
        if (!hasDeviceOwner && !deviceProvisioned) {
            return;
        }
        // Still at the first stage of CryptKeeper double bounce, mOwners.hasDeviceOwner is
        // always false at this point.
        if (StorageManager.inCryptKeeperBounce()) {
            return;
        }

        if (!mInjector.systemPropertiesGet(PROPERTY_DEVICE_OWNER_PRESENT, "").isEmpty()) {
            Slog.w(LOG_TAG, "Trying to set ro.device_owner, but it has already been set?");
        } else {
            final String value = Boolean.toString(hasDeviceOwner);
            mInjector.systemPropertiesSet(PROPERTY_DEVICE_OWNER_PRESENT, value);
            Slog.i(LOG_TAG, "Set ro.device_owner property to " + value);
        }
    }

    private void maybeStartSecurityLogMonitorOnActivityManagerReady() {
        synchronized (getLockObject()) {
            if (mInjector.securityLogIsLoggingEnabled()) {
                mSecurityLogMonitor.start();
                mInjector.runCryptoSelfTest();
                maybePauseDeviceWideLoggingLocked();
            }
        }
    }

    private void findOwnerComponentIfNecessaryLocked() {
        if (!mOwners.hasDeviceOwner()) {
            return;
        }
        final ComponentName doComponentName = mOwners.getDeviceOwnerComponent();

        if (!TextUtils.isEmpty(doComponentName.getClassName())) {
            return; // Already a full component name.
        }

        final ComponentName doComponent = findAdminComponentWithPackageLocked(
                doComponentName.getPackageName(),
                mOwners.getDeviceOwnerUserId());
        if (doComponent == null) {
            Slog.e(LOG_TAG, "Device-owner isn't registered as device-admin");
        } else {
            mOwners.setDeviceOwnerWithRestrictionsMigrated(
                    doComponent,
                    mOwners.getDeviceOwnerName(),
                    mOwners.getDeviceOwnerUserId(),
                    !mOwners.getDeviceOwnerUserRestrictionsNeedsMigration());
            mOwners.writeDeviceOwner();
            if (VERBOSE_LOG) {
                Log.v(LOG_TAG, "Device owner component filled in");
            }
        }
    }

    /**
     * We didn't use to persist user restrictions for each owners but only persisted in user
     * manager.
     */
    private void migrateUserRestrictionsIfNecessaryLocked() {
        boolean migrated = false;
        // Migrate for the DO.  Basically all restrictions should be considered to be set by DO,
        // except for the "system controlled" ones.
        if (mOwners.getDeviceOwnerUserRestrictionsNeedsMigration()) {
            if (VERBOSE_LOG) {
                Log.v(LOG_TAG, "Migrating DO user restrictions");
            }
            migrated = true;

            // Migrate user 0 restrictions to DO.
            final ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();

            migrateUserRestrictionsForUser(UserHandle.SYSTEM, deviceOwnerAdmin,
                    /* exceptionList =*/ null, /* isDeviceOwner =*/ true);

            // Push DO user restrictions to user manager.
            pushUserRestrictions(UserHandle.USER_SYSTEM);

            mOwners.setDeviceOwnerUserRestrictionsMigrated();
        }

        // Migrate for POs.

        // The following restrictions can be set on secondary users by the device owner, so we
        // assume they're not from the PO.
        final Set<String> secondaryUserExceptionList = Sets.newArraySet(
                UserManager.DISALLOW_OUTGOING_CALLS,
                UserManager.DISALLOW_SMS);

        for (UserInfo ui : mUserManager.getUsers()) {
            final int userId = ui.id;
            if (mOwners.getProfileOwnerUserRestrictionsNeedsMigration(userId)) {
                if (VERBOSE_LOG) {
                    Log.v(LOG_TAG, "Migrating PO user restrictions for user " + userId);
                }
                migrated = true;

                final ActiveAdmin profileOwnerAdmin = getProfileOwnerAdminLocked(userId);

                final Set<String> exceptionList =
                        (userId == UserHandle.USER_SYSTEM) ? null : secondaryUserExceptionList;

                migrateUserRestrictionsForUser(ui.getUserHandle(), profileOwnerAdmin,
                        exceptionList, /* isDeviceOwner =*/ false);

                // Note if a secondary user has no PO but has a DA that disables camera, we
                // don't get here and won't push the camera user restriction to UserManager
                // here.  That's okay because we'll push user restrictions anyway when a user
                // starts.  But we still do it because we want to let user manager persist
                // upon migration.
                pushUserRestrictions(userId);

                mOwners.setProfileOwnerUserRestrictionsMigrated(userId);
            }
        }
        if (VERBOSE_LOG && migrated) {
            Log.v(LOG_TAG, "User restrictions migrated.");
        }
    }

    private void migrateUserRestrictionsForUser(UserHandle user, ActiveAdmin admin,
            Set<String> exceptionList, boolean isDeviceOwner) {
        final Bundle origRestrictions = mUserManagerInternal.getBaseUserRestrictions(
                user.getIdentifier());

        final Bundle newBaseRestrictions = new Bundle();
        final Bundle newOwnerRestrictions = new Bundle();

        for (String key : origRestrictions.keySet()) {
            if (!origRestrictions.getBoolean(key)) {
                continue;
            }
            final boolean canOwnerChange = isDeviceOwner
                    ? UserRestrictionsUtils.canDeviceOwnerChange(key)
                    : UserRestrictionsUtils.canProfileOwnerChange(key, user.getIdentifier());

            if (!canOwnerChange || (exceptionList!= null && exceptionList.contains(key))) {
                newBaseRestrictions.putBoolean(key, true);
            } else {
                newOwnerRestrictions.putBoolean(key, true);
            }
        }

        if (VERBOSE_LOG) {
            Log.v(LOG_TAG, "origRestrictions=" + origRestrictions);
            Log.v(LOG_TAG, "newBaseRestrictions=" + newBaseRestrictions);
            Log.v(LOG_TAG, "newOwnerRestrictions=" + newOwnerRestrictions);
        }
        mUserManagerInternal.setBaseUserRestrictionsByDpmsForMigration(user.getIdentifier(),
                newBaseRestrictions);

        if (admin != null) {
            admin.ensureUserRestrictions().clear();
            admin.ensureUserRestrictions().putAll(newOwnerRestrictions);
        } else {
            Slog.w(LOG_TAG, "ActiveAdmin for DO/PO not found. user=" + user.getIdentifier());
        }
        saveSettingsLocked(user.getIdentifier());
    }

    private ComponentName findAdminComponentWithPackageLocked(String packageName, int userId) {
        final DevicePolicyData policy = getUserData(userId);
        final int n = policy.mAdminList.size();
        ComponentName found = null;
        int nFound = 0;
        for (int i = 0; i < n; i++) {
            final ActiveAdmin admin = policy.mAdminList.get(i);
            if (packageName.equals(admin.info.getPackageName())) {
                // Found!
                if (nFound == 0) {
                    found = admin.info.getComponent();
                }
                nFound++;
            }
        }
        if (nFound > 1) {
            Slog.w(LOG_TAG, "Multiple DA found; assume the first one is DO.");
        }
        return found;
    }

    /**
     * Set an alarm for an upcoming event - expiration warning, expiration, or post-expiration
     * reminders.  Clears alarm if no expirations are configured.
     */
    private void setExpirationAlarmCheckLocked(Context context, int userHandle, boolean parent) {
        final long expiration = getPasswordExpirationLocked(null, userHandle, parent);
        final long now = System.currentTimeMillis();
        final long timeToExpire = expiration - now;
        final long alarmTime;
        if (expiration == 0) {
            // No expirations are currently configured:  Cancel alarm.
            alarmTime = 0;
        } else if (timeToExpire <= 0) {
            // The password has already expired:  Repeat every 24 hours.
            alarmTime = now + MS_PER_DAY;
        } else {
            // Selecting the next alarm time:  Roll forward to the next 24 hour multiple before
            // the expiration time.
            long alarmInterval = timeToExpire % MS_PER_DAY;
            if (alarmInterval == 0) {
                alarmInterval = MS_PER_DAY;
            }
            alarmTime = now + alarmInterval;
        }

        long token = mInjector.binderClearCallingIdentity();
        try {
            int affectedUserHandle = parent ? getProfileParentId(userHandle) : userHandle;
            AlarmManager am = mInjector.getAlarmManager();
            PendingIntent pi = PendingIntent.getBroadcastAsUser(context, REQUEST_EXPIRE_PASSWORD,
                    new Intent(ACTION_EXPIRED_PASSWORD_NOTIFICATION),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT,
                    UserHandle.of(affectedUserHandle));
            am.cancel(pi);
            if (alarmTime != 0) {
                am.set(AlarmManager.RTC, alarmTime, pi);
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    ActiveAdmin getActiveAdminUncheckedLocked(ComponentName who, int userHandle) {
        ensureLocked();
        ActiveAdmin admin = getUserData(userHandle).mAdminMap.get(who);
        if (admin != null
                && who.getPackageName().equals(admin.info.getActivityInfo().packageName)
                && who.getClassName().equals(admin.info.getActivityInfo().name)) {
            return admin;
        }
        return null;
    }

    ActiveAdmin getActiveAdminUncheckedLocked(ComponentName who, int userHandle, boolean parent) {
        ensureLocked();
        if (parent) {
            enforceManagedProfile(userHandle, "call APIs on the parent profile");
        }
        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
        if (admin != null && parent) {
            admin = admin.getParentActiveAdmin();
        }
        return admin;
    }

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy)
            throws SecurityException {
        ensureLocked();
        final int callingUid = mInjector.binderGetCallingUid();

        ActiveAdmin result = getActiveAdminWithPolicyForUidLocked(who, reqPolicy, callingUid);
        if (result != null) {
            return result;
        }

        if (who != null) {
            final int userId = UserHandle.getUserId(callingUid);
            final DevicePolicyData policy = getUserData(userId);
            ActiveAdmin admin = policy.mAdminMap.get(who);
            if (reqPolicy == DeviceAdminInfo.USES_POLICY_DEVICE_OWNER) {
                throw new SecurityException("Admin " + admin.info.getComponent()
                         + " does not own the device");
            }
            if (reqPolicy == DeviceAdminInfo.USES_POLICY_PROFILE_OWNER) {
                throw new SecurityException("Admin " + admin.info.getComponent()
                        + " does not own the profile");
            }
            throw new SecurityException("Admin " + admin.info.getComponent()
                    + " did not specify uses-policy for: "
                    + admin.info.getTagForPolicy(reqPolicy));
        } else {
            throw new SecurityException("No active admin owned by uid "
                    + mInjector.binderGetCallingUid() + " for policy #" + reqPolicy);
        }
    }

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy, boolean parent)
            throws SecurityException {
        ensureLocked();
        if (parent) {
            enforceManagedProfile(mInjector.userHandleGetCallingUserId(),
                    "call APIs on the parent profile");
        }
        ActiveAdmin admin = getActiveAdminForCallerLocked(who, reqPolicy);
        return parent ? admin.getParentActiveAdmin() : admin;
    }
    /**
     * Find the admin for the component and userId bit of the uid, then check
     * the admin's uid matches the uid.
     */
    private ActiveAdmin getActiveAdminForUidLocked(ComponentName who, int uid) {
        ensureLocked();
        final int userId = UserHandle.getUserId(uid);
        final DevicePolicyData policy = getUserData(userId);
        ActiveAdmin admin = policy.mAdminMap.get(who);
        if (admin == null) {
            throw new SecurityException("No active admin " + who);
        }
        if (admin.getUid() != uid) {
            throw new SecurityException("Admin " + who + " is not owned by uid " + uid);
        }
        return admin;
    }

    private ActiveAdmin getActiveAdminWithPolicyForUidLocked(ComponentName who, int reqPolicy,
            int uid) {
        ensureLocked();
        // Try to find an admin which can use reqPolicy
        final int userId = UserHandle.getUserId(uid);
        final DevicePolicyData policy = getUserData(userId);
        if (who != null) {
            ActiveAdmin admin = policy.mAdminMap.get(who);
            if (admin == null) {
                throw new SecurityException("No active admin " + who);
            }
            if (admin.getUid() != uid) {
                throw new SecurityException("Admin " + who + " is not owned by uid " + uid);
            }
            if (isActiveAdminWithPolicyForUserLocked(admin, reqPolicy, userId)) {
                return admin;
            }
        } else {
            for (ActiveAdmin admin : policy.mAdminList) {
                if (admin.getUid() == uid && isActiveAdminWithPolicyForUserLocked(admin, reqPolicy,
                        userId)) {
                    return admin;
                }
            }
        }

        return null;
    }

    @VisibleForTesting
    boolean isActiveAdminWithPolicyForUserLocked(ActiveAdmin admin, int reqPolicy,
            int userId) {
        ensureLocked();
        final boolean ownsDevice = isDeviceOwner(admin.info.getComponent(), userId);
        final boolean ownsProfile = isProfileOwner(admin.info.getComponent(), userId);

        if (reqPolicy == DeviceAdminInfo.USES_POLICY_DEVICE_OWNER) {
            return ownsDevice;
        } else if (reqPolicy == DeviceAdminInfo.USES_POLICY_PROFILE_OWNER) {
            // DO always has the PO power.
            return ownsDevice || ownsProfile;
        } else {
            return admin.info.usesPolicy(reqPolicy);
        }
    }

    void sendAdminCommandLocked(ActiveAdmin admin, String action) {
        sendAdminCommandLocked(admin, action, null);
    }

    void sendAdminCommandLocked(ActiveAdmin admin, String action, BroadcastReceiver result) {
        sendAdminCommandLocked(admin, action, null, result);
    }

    void sendAdminCommandLocked(ActiveAdmin admin, String action, Bundle adminExtras,
            BroadcastReceiver result) {
        sendAdminCommandLocked(admin, action, adminExtras, result, false);
    }

    /**
     * Send an update to one specific admin, get notified when that admin returns a result.
     *
     * @return whether the broadcast was successfully sent
     */
    boolean sendAdminCommandLocked(ActiveAdmin admin, String action, Bundle adminExtras,
            BroadcastReceiver result, boolean inForeground) {
        Intent intent = new Intent(action);
        intent.setComponent(admin.info.getComponent());
        if (UserManager.isDeviceInDemoMode(mContext)) {
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        if (action.equals(DeviceAdminReceiver.ACTION_PASSWORD_EXPIRING)) {
            intent.putExtra("expiration", admin.passwordExpirationDate);
        }
        if (inForeground) {
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        }
        if (adminExtras != null) {
            intent.putExtras(adminExtras);
        }
        if (mInjector.getPackageManager().queryBroadcastReceiversAsUser(
                intent,
                PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                admin.getUserHandle()).isEmpty()) {
            return false;
        }
        if (result != null) {
            mContext.sendOrderedBroadcastAsUser(intent, admin.getUserHandle(),
                    null, result, mHandler, Activity.RESULT_OK, null, null);
        } else {
            mContext.sendBroadcastAsUser(intent, admin.getUserHandle());
        }
        return true;
    }

    /**
     * Send an update to all admins of a user that enforce a specified policy.
     */
    void sendAdminCommandLocked(String action, int reqPolicy, int userHandle, Bundle adminExtras) {
        final DevicePolicyData policy = getUserData(userHandle);
        final int count = policy.mAdminList.size();
        for (int i = 0; i < count; i++) {
            final ActiveAdmin admin = policy.mAdminList.get(i);
            if (admin.info.usesPolicy(reqPolicy)) {
                sendAdminCommandLocked(admin, action, adminExtras, null);
            }
        }
    }

    /**
     * Send an update intent to all admins of a user and its profiles. Only send to admins that
     * enforce a specified policy.
     */
    private void sendAdminCommandToSelfAndProfilesLocked(String action, int reqPolicy,
            int userHandle, Bundle adminExtras) {
        int[] profileIds = mUserManager.getProfileIdsWithDisabled(userHandle);
        for (int profileId : profileIds) {
            sendAdminCommandLocked(action, reqPolicy, profileId, adminExtras);
        }
    }

    /**
     * Sends a broadcast to each profile that share the password unlock with the given user id.
     */
    private void sendAdminCommandForLockscreenPoliciesLocked(
            String action, int reqPolicy, int userHandle) {
        final Bundle extras = new Bundle();
        extras.putParcelable(Intent.EXTRA_USER, UserHandle.of(userHandle));
        if (isSeparateProfileChallengeEnabled(userHandle)) {
            sendAdminCommandLocked(action, reqPolicy, userHandle, extras);
        } else {
            sendAdminCommandToSelfAndProfilesLocked(action, reqPolicy, userHandle, extras);
        }
    }

    void removeActiveAdminLocked(final ComponentName adminReceiver, final int userHandle) {
        final ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
        DevicePolicyData policy = getUserData(userHandle);
        if (admin != null && !policy.mRemovingAdmins.contains(adminReceiver)) {
            policy.mRemovingAdmins.add(adminReceiver);
            sendAdminCommandLocked(admin,
                    DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            removeAdminArtifacts(adminReceiver, userHandle);
                            removePackageIfRequired(adminReceiver.getPackageName(), userHandle);
                        }
                    });
        }
    }


    public DeviceAdminInfo findAdmin(ComponentName adminName, int userHandle,
            boolean throwForMissingPermission) {
        if (!mHasFeature) {
            return null;
        }
        enforceFullCrossUsersPermission(userHandle);
        ActivityInfo ai = null;
        try {
            ai = mIPackageManager.getReceiverInfo(adminName,
                    PackageManager.GET_META_DATA |
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS |
                    PackageManager.MATCH_DIRECT_BOOT_AWARE |
                    PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userHandle);
        } catch (RemoteException e) {
            // shouldn't happen.
        }
        if (ai == null) {
            throw new IllegalArgumentException("Unknown admin: " + adminName);
        }

        if (!permission.BIND_DEVICE_ADMIN.equals(ai.permission)) {
            final String message = "DeviceAdminReceiver " + adminName + " must be protected with "
                    + permission.BIND_DEVICE_ADMIN;
            Slog.w(LOG_TAG, message);
            if (throwForMissingPermission &&
                    ai.applicationInfo.targetSdkVersion > Build.VERSION_CODES.M) {
                throw new IllegalArgumentException(message);
            }
        }

        try {
            return new DeviceAdminInfo(mContext, ai);
        } catch (XmlPullParserException | IOException e) {
            Slog.w(LOG_TAG, "Bad device admin requested for user=" + userHandle + ": " + adminName,
                    e);
            return null;
        }
    }

    private File getPolicyFileDirectory(@UserIdInt int userId) {
        return userId == UserHandle.USER_SYSTEM
                ? new File(mInjector.getDevicePolicyFilePathForSystemUser())
                : mInjector.environmentGetUserSystemDirectory(userId);
    }

    private JournaledFile makeJournaledFile(@UserIdInt int userId) {
        final String base = new File(getPolicyFileDirectory(userId), DEVICE_POLICIES_XML)
                .getAbsolutePath();
        if (VERBOSE_LOG) {
            Log.v(LOG_TAG, "Opening " + base);
        }
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked(int userHandle) {
        DevicePolicyData policy = getUserData(userHandle);
        JournaledFile journal = makeJournaledFile(userHandle);
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(journal.chooseForWrite(), false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            out.startTag(null, "policies");
            if (policy.mRestrictionsProvider != null) {
                out.attribute(null, ATTR_PERMISSION_PROVIDER,
                        policy.mRestrictionsProvider.flattenToString());
            }
            if (policy.mUserSetupComplete) {
                out.attribute(null, ATTR_SETUP_COMPLETE,
                        Boolean.toString(true));
            }
            if (policy.mPaired) {
                out.attribute(null, ATTR_DEVICE_PAIRED,
                        Boolean.toString(true));
            }
            if (policy.mDeviceProvisioningConfigApplied) {
                out.attribute(null, ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED,
                        Boolean.toString(true));
            }
            if (policy.mUserProvisioningState != DevicePolicyManager.STATE_USER_UNMANAGED) {
                out.attribute(null, ATTR_PROVISIONING_STATE,
                        Integer.toString(policy.mUserProvisioningState));
            }
            if (policy.mPermissionPolicy != DevicePolicyManager.PERMISSION_POLICY_PROMPT) {
                out.attribute(null, ATTR_PERMISSION_POLICY,
                        Integer.toString(policy.mPermissionPolicy));
            }

            // Serialize delegations.
            for (int i = 0; i < policy.mDelegationMap.size(); ++i) {
                final String delegatePackage = policy.mDelegationMap.keyAt(i);
                final List<String> scopes = policy.mDelegationMap.valueAt(i);

                // Every "delegation" tag serializes the information of one delegate-scope pair.
                for (String scope : scopes) {
                    out.startTag(null, "delegation");
                    out.attribute(null, "delegatePackage", delegatePackage);
                    out.attribute(null, "scope", scope);
                    out.endTag(null, "delegation");
                }
            }

            final int N = policy.mAdminList.size();
            for (int i=0; i<N; i++) {
                ActiveAdmin ap = policy.mAdminList.get(i);
                if (ap != null) {
                    out.startTag(null, "admin");
                    out.attribute(null, "name", ap.info.getComponent().flattenToString());
                    ap.writeToXml(out);
                    out.endTag(null, "admin");
                }
            }

            if (policy.mPasswordOwner >= 0) {
                out.startTag(null, "password-owner");
                out.attribute(null, "value", Integer.toString(policy.mPasswordOwner));
                out.endTag(null, "password-owner");
            }

            if (policy.mFailedPasswordAttempts != 0) {
                out.startTag(null, "failed-password-attempts");
                out.attribute(null, "value", Integer.toString(policy.mFailedPasswordAttempts));
                out.endTag(null, "failed-password-attempts");
            }

            // For FDE devices only, we save this flag so we can report on password sufficiency
            // before the user enters their password for the first time after a reboot.  For
            // security reasons, we don't want to store the full set of active password metrics.
            if (!mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
                out.startTag(null, TAG_PASSWORD_VALIDITY);
                out.attribute(null, ATTR_VALUE,
                        Boolean.toString(policy.mPasswordValidAtLastCheckpoint));
                out.endTag(null, TAG_PASSWORD_VALIDITY);
            }

            for (int i = 0; i < policy.mAcceptedCaCertificates.size(); i++) {
                out.startTag(null, TAG_ACCEPTED_CA_CERTIFICATES);
                out.attribute(null, ATTR_NAME, policy.mAcceptedCaCertificates.valueAt(i));
                out.endTag(null, TAG_ACCEPTED_CA_CERTIFICATES);
            }

            for (int i=0; i<policy.mLockTaskPackages.size(); i++) {
                String component = policy.mLockTaskPackages.get(i);
                out.startTag(null, TAG_LOCK_TASK_COMPONENTS);
                out.attribute(null, "name", component);
                out.endTag(null, TAG_LOCK_TASK_COMPONENTS);
            }

            if (policy.mLockTaskFeatures != DevicePolicyManager.LOCK_TASK_FEATURE_NONE) {
                out.startTag(null, TAG_LOCK_TASK_FEATURES);
                out.attribute(null, ATTR_VALUE, Integer.toString(policy.mLockTaskFeatures));
                out.endTag(null, TAG_LOCK_TASK_FEATURES);
            }

            if (policy.mStatusBarDisabled) {
                out.startTag(null, TAG_STATUS_BAR);
                out.attribute(null, ATTR_DISABLED, Boolean.toString(policy.mStatusBarDisabled));
                out.endTag(null, TAG_STATUS_BAR);
            }

            if (policy.doNotAskCredentialsOnBoot) {
                out.startTag(null, DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML);
                out.endTag(null, DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML);
            }

            for (String id : policy.mAffiliationIds) {
                out.startTag(null, TAG_AFFILIATION_ID);
                out.attribute(null, ATTR_ID, id);
                out.endTag(null, TAG_AFFILIATION_ID);
            }

            if (policy.mLastSecurityLogRetrievalTime >= 0) {
                out.startTag(null, TAG_LAST_SECURITY_LOG_RETRIEVAL);
                out.attribute(null, ATTR_VALUE,
                        Long.toString(policy.mLastSecurityLogRetrievalTime));
                out.endTag(null, TAG_LAST_SECURITY_LOG_RETRIEVAL);
            }

            if (policy.mLastBugReportRequestTime >= 0) {
                out.startTag(null, TAG_LAST_BUG_REPORT_REQUEST);
                out.attribute(null, ATTR_VALUE,
                        Long.toString(policy.mLastBugReportRequestTime));
                out.endTag(null, TAG_LAST_BUG_REPORT_REQUEST);
            }

            if (policy.mLastNetworkLogsRetrievalTime >= 0) {
                out.startTag(null, TAG_LAST_NETWORK_LOG_RETRIEVAL);
                out.attribute(null, ATTR_VALUE,
                        Long.toString(policy.mLastNetworkLogsRetrievalTime));
                out.endTag(null, TAG_LAST_NETWORK_LOG_RETRIEVAL);
            }

            if (policy.mAdminBroadcastPending) {
                out.startTag(null, TAG_ADMIN_BROADCAST_PENDING);
                out.attribute(null, ATTR_VALUE,
                        Boolean.toString(policy.mAdminBroadcastPending));
                out.endTag(null, TAG_ADMIN_BROADCAST_PENDING);
            }

            if (policy.mInitBundle != null) {
                out.startTag(null, TAG_INITIALIZATION_BUNDLE);
                policy.mInitBundle.saveToXml(out);
                out.endTag(null, TAG_INITIALIZATION_BUNDLE);
            }

            if (policy.mPasswordTokenHandle != 0) {
                out.startTag(null, TAG_PASSWORD_TOKEN_HANDLE);
                out.attribute(null, ATTR_VALUE,
                        Long.toString(policy.mPasswordTokenHandle));
                out.endTag(null, TAG_PASSWORD_TOKEN_HANDLE);
            }

            if (policy.mCurrentInputMethodSet) {
                out.startTag(null, TAG_CURRENT_INPUT_METHOD_SET);
                out.endTag(null, TAG_CURRENT_INPUT_METHOD_SET);
            }

            for (final String cert : policy.mOwnerInstalledCaCerts) {
                out.startTag(null, TAG_OWNER_INSTALLED_CA_CERT);
                out.attribute(null, ATTR_ALIAS, cert);
                out.endTag(null, TAG_OWNER_INSTALLED_CA_CERT);
            }

            out.endTag(null, "policies");

            out.endDocument();
            stream.flush();
            FileUtils.sync(stream);
            stream.close();
            journal.commit();
            sendChangedNotification(userHandle);
        } catch (XmlPullParserException | IOException e) {
            Slog.w(LOG_TAG, "failed writing file", e);
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            journal.rollback();
        }
    }

    private void sendChangedNotification(int userHandle) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        long ident = mInjector.binderClearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, new UserHandle(userHandle));
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void loadSettingsLocked(DevicePolicyData policy, int userHandle) {
        JournaledFile journal = makeJournaledFile(userHandle);
        FileInputStream stream = null;
        File file = journal.chooseForRead();
        boolean needsRewrite = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }
            String tag = parser.getName();
            if (!"policies".equals(tag)) {
                throw new XmlPullParserException(
                        "Settings do not start with policies tag: found " + tag);
            }

            // Extract the permission provider component name if available
            String permissionProvider = parser.getAttributeValue(null, ATTR_PERMISSION_PROVIDER);
            if (permissionProvider != null) {
                policy.mRestrictionsProvider = ComponentName.unflattenFromString(permissionProvider);
            }
            String userSetupComplete = parser.getAttributeValue(null, ATTR_SETUP_COMPLETE);
            if (userSetupComplete != null && Boolean.toString(true).equals(userSetupComplete)) {
                policy.mUserSetupComplete = true;
            }
            String paired = parser.getAttributeValue(null, ATTR_DEVICE_PAIRED);
            if (paired != null && Boolean.toString(true).equals(paired)) {
                policy.mPaired = true;
            }
            String deviceProvisioningConfigApplied = parser.getAttributeValue(null,
                    ATTR_DEVICE_PROVISIONING_CONFIG_APPLIED);
            if (deviceProvisioningConfigApplied != null
                    && Boolean.toString(true).equals(deviceProvisioningConfigApplied)) {
                policy.mDeviceProvisioningConfigApplied = true;
            }
            String provisioningState = parser.getAttributeValue(null, ATTR_PROVISIONING_STATE);
            if (!TextUtils.isEmpty(provisioningState)) {
                policy.mUserProvisioningState = Integer.parseInt(provisioningState);
            }
            String permissionPolicy = parser.getAttributeValue(null, ATTR_PERMISSION_POLICY);
            if (!TextUtils.isEmpty(permissionPolicy)) {
                policy.mPermissionPolicy = Integer.parseInt(permissionPolicy);
            }
            // Check for delegation compatibility with pre-O.
            // TODO(edmanp) remove in P.
            {
                final String certDelegate = parser.getAttributeValue(null,
                        ATTR_DELEGATED_CERT_INSTALLER);
                if (certDelegate != null) {
                    List<String> scopes = policy.mDelegationMap.get(certDelegate);
                    if (scopes == null) {
                        scopes = new ArrayList<>();
                        policy.mDelegationMap.put(certDelegate, scopes);
                    }
                    if (!scopes.contains(DELEGATION_CERT_INSTALL)) {
                        scopes.add(DELEGATION_CERT_INSTALL);
                        needsRewrite = true;
                    }
                }
                final String appRestrictionsDelegate = parser.getAttributeValue(null,
                        ATTR_APPLICATION_RESTRICTIONS_MANAGER);
                if (appRestrictionsDelegate != null) {
                    List<String> scopes = policy.mDelegationMap.get(appRestrictionsDelegate);
                    if (scopes == null) {
                        scopes = new ArrayList<>();
                        policy.mDelegationMap.put(appRestrictionsDelegate, scopes);
                    }
                    if (!scopes.contains(DELEGATION_APP_RESTRICTIONS)) {
                        scopes.add(DELEGATION_APP_RESTRICTIONS);
                        needsRewrite = true;
                    }
                }
            }

            type = parser.next();
            int outerDepth = parser.getDepth();
            policy.mLockTaskPackages.clear();
            policy.mAdminList.clear();
            policy.mAdminMap.clear();
            policy.mAffiliationIds.clear();
            policy.mOwnerInstalledCaCerts.clear();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                tag = parser.getName();
                if ("admin".equals(tag)) {
                    String name = parser.getAttributeValue(null, "name");
                    try {
                        DeviceAdminInfo dai = findAdmin(
                                ComponentName.unflattenFromString(name), userHandle,
                                /* throwForMissingPermission= */ false);
                        if (VERBOSE_LOG
                                && (UserHandle.getUserId(dai.getActivityInfo().applicationInfo.uid)
                                != userHandle)) {
                            Slog.w(LOG_TAG, "findAdmin returned an incorrect uid "
                                    + dai.getActivityInfo().applicationInfo.uid + " for user "
                                    + userHandle);
                        }
                        if (dai != null) {
                            ActiveAdmin ap = new ActiveAdmin(dai, /* parent */ false);
                            ap.readFromXml(parser);
                            policy.mAdminMap.put(ap.info.getComponent(), ap);
                        }
                    } catch (RuntimeException e) {
                        Slog.w(LOG_TAG, "Failed loading admin " + name, e);
                    }
                } else if ("delegation".equals(tag)) {
                    // Parse delegation info.
                    final String delegatePackage = parser.getAttributeValue(null,
                            "delegatePackage");
                    final String scope = parser.getAttributeValue(null, "scope");

                    // Get a reference to the scopes list for the delegatePackage.
                    List<String> scopes = policy.mDelegationMap.get(delegatePackage);
                    // Or make a new list if none was found.
                    if (scopes == null) {
                        scopes = new ArrayList<>();
                        policy.mDelegationMap.put(delegatePackage, scopes);
                    }
                    // Add the new scope to the list of delegatePackage if it's not already there.
                    if (!scopes.contains(scope)) {
                        scopes.add(scope);
                    }
                } else if ("failed-password-attempts".equals(tag)) {
                    policy.mFailedPasswordAttempts = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                } else if ("password-owner".equals(tag)) {
                    policy.mPasswordOwner = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                } else if (TAG_ACCEPTED_CA_CERTIFICATES.equals(tag)) {
                    policy.mAcceptedCaCertificates.add(parser.getAttributeValue(null, ATTR_NAME));
                } else if (TAG_LOCK_TASK_COMPONENTS.equals(tag)) {
                    policy.mLockTaskPackages.add(parser.getAttributeValue(null, "name"));
                } else if (TAG_LOCK_TASK_FEATURES.equals(tag)) {
                    policy.mLockTaskFeatures = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_STATUS_BAR.equals(tag)) {
                    policy.mStatusBarDisabled = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_DISABLED));
                } else if (DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML.equals(tag)) {
                    policy.doNotAskCredentialsOnBoot = true;
                } else if (TAG_AFFILIATION_ID.equals(tag)) {
                    policy.mAffiliationIds.add(parser.getAttributeValue(null, ATTR_ID));
                } else if (TAG_LAST_SECURITY_LOG_RETRIEVAL.equals(tag)) {
                    policy.mLastSecurityLogRetrievalTime = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_LAST_BUG_REPORT_REQUEST.equals(tag)) {
                    policy.mLastBugReportRequestTime = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_LAST_NETWORK_LOG_RETRIEVAL.equals(tag)) {
                    policy.mLastNetworkLogsRetrievalTime = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_ADMIN_BROADCAST_PENDING.equals(tag)) {
                    String pending = parser.getAttributeValue(null, ATTR_VALUE);
                    policy.mAdminBroadcastPending = Boolean.toString(true).equals(pending);
                } else if (TAG_INITIALIZATION_BUNDLE.equals(tag)) {
                    policy.mInitBundle = PersistableBundle.restoreFromXml(parser);
                } else if ("active-password".equals(tag)) {
                    // Remove password metrics from saved settings, as we no longer wish to store
                    // these on disk
                    needsRewrite = true;
                } else if (TAG_PASSWORD_VALIDITY.equals(tag)) {
                    if (!mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
                        // This flag is only used for FDE devices
                        policy.mPasswordValidAtLastCheckpoint = Boolean.parseBoolean(
                                parser.getAttributeValue(null, ATTR_VALUE));
                    }
                } else if (TAG_PASSWORD_TOKEN_HANDLE.equals(tag)) {
                    policy.mPasswordTokenHandle = Long.parseLong(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_CURRENT_INPUT_METHOD_SET.equals(tag)) {
                    policy.mCurrentInputMethodSet = true;
                } else if (TAG_OWNER_INSTALLED_CA_CERT.equals(tag)) {
                    policy.mOwnerInstalledCaCerts.add(parser.getAttributeValue(null, ATTR_ALIAS));
                } else {
                    Slog.w(LOG_TAG, "Unknown tag: " + tag);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } catch (FileNotFoundException e) {
            // Don't be noisy, this is normal if we haven't defined any policies.
        } catch (NullPointerException | NumberFormatException | XmlPullParserException | IOException
                | IndexOutOfBoundsException e) {
            Slog.w(LOG_TAG, "failed parsing " + file, e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        // Generate a list of admins from the admin map
        policy.mAdminList.addAll(policy.mAdminMap.values());

        // Might need to upgrade the file by rewriting it
        if (needsRewrite) {
            saveSettingsLocked(userHandle);
        }

        validatePasswordOwnerLocked(policy);
        updateMaximumTimeToLockLocked(userHandle);
        updateLockTaskPackagesLocked(policy.mLockTaskPackages, userHandle);
        updateLockTaskFeaturesLocked(policy.mLockTaskFeatures, userHandle);
        if (policy.mStatusBarDisabled) {
            setStatusBarDisabledInternal(policy.mStatusBarDisabled, userHandle);
        }
    }

    private void updateLockTaskPackagesLocked(List<String> packages, int userId) {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            mInjector.getIActivityManager()
                    .updateLockTaskPackages(userId, packages.toArray(new String[packages.size()]));
        } catch (RemoteException e) {
            // Not gonna happen.
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void updateLockTaskFeaturesLocked(int flags, int userId) {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            mInjector.getIActivityManager()
                    .updateLockTaskFeatures(userId, flags);
        } catch (RemoteException e) {
            // Not gonna happen.
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void updateDeviceOwnerLocked() {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            // TODO This is to prevent DO from getting "clear data"ed, but it should also check the
            // user id and also protect all other DAs too.
            final ComponentName deviceOwnerComponent = mOwners.getDeviceOwnerComponent();
            if (deviceOwnerComponent != null) {
                mInjector.getIActivityManager()
                        .updateDeviceOwner(deviceOwnerComponent.getPackageName());
            }
        } catch (RemoteException e) {
            // Not gonna happen.
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    static void validateQualityConstant(int quality) {
        switch (quality) {
            case DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED:
            case DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK:
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
            case DevicePolicyManager.PASSWORD_QUALITY_MANAGED:
                return;
        }
        throw new IllegalArgumentException("Invalid quality constant: 0x"
                + Integer.toHexString(quality));
    }

    void validatePasswordOwnerLocked(DevicePolicyData policy) {
        if (policy.mPasswordOwner >= 0) {
            boolean haveOwner = false;
            for (int i = policy.mAdminList.size() - 1; i >= 0; i--) {
                if (policy.mAdminList.get(i).getUid() == policy.mPasswordOwner) {
                    haveOwner = true;
                    break;
                }
            }
            if (!haveOwner) {
                Slog.w(LOG_TAG, "Previous password owner " + policy.mPasswordOwner
                        + " no longer active; disabling");
                policy.mPasswordOwner = -1;
            }
        }
    }

    @VisibleForTesting
    @Override
    void systemReady(int phase) {
        if (!mHasFeature) {
            return;
        }
        switch (phase) {
            case SystemService.PHASE_LOCK_SETTINGS_READY:
                onLockSettingsReady();
                loadAdminDataAsync();
                mOwners.systemReady();
                break;
            case SystemService.PHASE_ACTIVITY_MANAGER_READY:
                maybeStartSecurityLogMonitorOnActivityManagerReady();
                break;
            case SystemService.PHASE_BOOT_COMPLETED:
                ensureDeviceOwnerUserStarted(); // TODO Consider better place to do this.
                break;
        }
    }

    private void onLockSettingsReady() {
        getUserData(UserHandle.USER_SYSTEM);
        loadOwners();
        cleanUpOldUsers();
        maybeSetDefaultProfileOwnerUserRestrictions();
        handleStartUser(UserHandle.USER_SYSTEM);
        maybeLogStart();

        // Register an observer for watching for user setup complete and settings changes.
        mSetupContentObserver.register();
        // Initialize the user setup state, to handle the upgrade case.
        updateUserSetupCompleteAndPaired();

        List<String> packageList;
        synchronized (getLockObject()) {
            packageList = getKeepUninstalledPackagesLocked();
        }
        if (packageList != null) {
            mInjector.getPackageManagerInternal().setKeepUninstalledPackages(packageList);
        }

        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            if (deviceOwner != null) {
                // Push the force-ephemeral-users policy to the user manager.
                mUserManagerInternal.setForceEphemeralUsers(deviceOwner.forceEphemeralUsers);

                // Update user switcher message to activity manager.
                ActivityManagerInternal activityManagerInternal =
                        mInjector.getActivityManagerInternal();
                activityManagerInternal.setSwitchingFromSystemUserMessage(
                        deviceOwner.startUserSessionMessage);
                activityManagerInternal.setSwitchingToSystemUserMessage(
                        deviceOwner.endUserSessionMessage);
            }

            revertTransferOwnershipIfNecessaryLocked();
        }
    }

    private void revertTransferOwnershipIfNecessaryLocked() {
        if (!mTransferOwnershipMetadataManager.metadataFileExists()) {
            return;
        }
        Slog.e(LOG_TAG, "Owner transfer metadata file exists! Reverting transfer.");
        final TransferOwnershipMetadataManager.Metadata metadata =
                mTransferOwnershipMetadataManager.loadMetadataFile();
        // Revert transfer
        if (metadata.adminType.equals(ADMIN_TYPE_PROFILE_OWNER)) {
            transferProfileOwnershipLocked(metadata.targetComponent, metadata.sourceComponent,
                    metadata.userId);
            deleteTransferOwnershipMetadataFileLocked();
            deleteTransferOwnershipBundleLocked(metadata.userId);
        } else if (metadata.adminType.equals(ADMIN_TYPE_DEVICE_OWNER)) {
            transferDeviceOwnershipLocked(metadata.targetComponent, metadata.sourceComponent,
                    metadata.userId);
            deleteTransferOwnershipMetadataFileLocked();
            deleteTransferOwnershipBundleLocked(metadata.userId);
        }
        updateSystemUpdateFreezePeriodsRecord(/* saveIfChanged */ true);
    }

    private void maybeLogStart() {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        final String verifiedBootState =
                mInjector.systemPropertiesGet("ro.boot.verifiedbootstate");
        final String verityMode = mInjector.systemPropertiesGet("ro.boot.veritymode");
        SecurityLog.writeEvent(SecurityLog.TAG_OS_STARTUP, verifiedBootState, verityMode);
    }

    private void ensureDeviceOwnerUserStarted() {
        final int userId;
        synchronized (getLockObject()) {
            if (!mOwners.hasDeviceOwner()) {
                return;
            }
            userId = mOwners.getDeviceOwnerUserId();
        }
        if (VERBOSE_LOG) {
            Log.v(LOG_TAG, "Starting non-system DO user: " + userId);
        }
        if (userId != UserHandle.USER_SYSTEM) {
            try {
                mInjector.getIActivityManager().startUserInBackground(userId);

                // STOPSHIP Prevent the DO user from being killed.

            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Exception starting user", e);
            }
        }
    }

    @Override
    void handleStartUser(int userId) {
        updateScreenCaptureDisabled(userId,
                getScreenCaptureDisabled(null, userId));
        pushUserRestrictions(userId);

        startOwnerService(userId, "start-user");
    }

    @Override
    void handleUnlockUser(int userId) {
        startOwnerService(userId, "unlock-user");
    }

    @Override
    void handleStopUser(int userId) {
        stopOwnerService(userId, "stop-user");
    }

    private void startOwnerService(int userId, String actionForLog) {
        final ComponentName owner = getOwnerComponent(userId);
        if (owner != null) {
            mDeviceAdminServiceController.startServiceForOwner(
                    owner.getPackageName(), userId, actionForLog);
        }
    }

    private void stopOwnerService(int userId, String actionForLog) {
        mDeviceAdminServiceController.stopServiceForOwner(userId, actionForLog);
    }

    private void cleanUpOldUsers() {
        // This is needed in case the broadcast {@link Intent.ACTION_USER_REMOVED} was not handled
        // before reboot
        Set<Integer> usersWithProfileOwners;
        Set<Integer> usersWithData;
        synchronized (getLockObject()) {
            usersWithProfileOwners = mOwners.getProfileOwnerKeys();
            usersWithData = new ArraySet<>();
            for (int i = 0; i < mUserData.size(); i++) {
                usersWithData.add(mUserData.keyAt(i));
            }
        }
        List<UserInfo> allUsers = mUserManager.getUsers();

        Set<Integer> deletedUsers = new ArraySet<>();
        deletedUsers.addAll(usersWithProfileOwners);
        deletedUsers.addAll(usersWithData);
        for (UserInfo userInfo : allUsers) {
            deletedUsers.remove(userInfo.id);
        }
        for (Integer userId : deletedUsers) {
            removeUserData(userId);
        }
    }

    private void handlePasswordExpirationNotification(int userHandle) {
        final Bundle adminExtras = new Bundle();
        adminExtras.putParcelable(Intent.EXTRA_USER, UserHandle.of(userHandle));

        synchronized (getLockObject()) {
            final long now = System.currentTimeMillis();

            List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(
                    userHandle, /* parent */ false);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (admin.info.usesPolicy(DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD)
                        && admin.passwordExpirationTimeout > 0L
                        && now >= admin.passwordExpirationDate - EXPIRATION_GRACE_PERIOD_MS
                        && admin.passwordExpirationDate > 0L) {
                    sendAdminCommandLocked(admin,
                            DeviceAdminReceiver.ACTION_PASSWORD_EXPIRING, adminExtras, null);
                }
            }
            setExpirationAlarmCheckLocked(mContext, userHandle, /* parent */ false);
        }
    }

    /**
     * Clean up internal state when the set of installed trusted CA certificates changes.
     *
     * @param userHandle user to check for. This must be a real user and not, for example,
     *        {@link UserHandle#ALL}.
     * @param installedCertificates the full set of certificate authorities currently installed for
     *        {@param userHandle}. After calling this function, {@code mAcceptedCaCertificates} will
     *        correspond to some subset of this.
     */
    protected void onInstalledCertificatesChanged(final UserHandle userHandle,
            final @NonNull Collection<String> installedCertificates) {
        if (!mHasFeature) {
            return;
        }
        enforceManageUsers();

        synchronized (getLockObject()) {
            final DevicePolicyData policy = getUserData(userHandle.getIdentifier());

            boolean changed = false;
            changed |= policy.mAcceptedCaCertificates.retainAll(installedCertificates);
            changed |= policy.mOwnerInstalledCaCerts.retainAll(installedCertificates);
            if (changed) {
                saveSettingsLocked(userHandle.getIdentifier());
            }
        }
    }

    /**
     * Internal method used by {@link CertificateMonitor}.
     */
    protected Set<String> getAcceptedCaCertificates(final UserHandle userHandle) {
        if (!mHasFeature) {
            return Collections.<String> emptySet();
        }
        synchronized (getLockObject()) {
            final DevicePolicyData policy = getUserData(userHandle.getIdentifier());
            return policy.mAcceptedCaCertificates;
        }
    }

    /**
     * @param adminReceiver The admin to add
     * @param refreshing true = update an active admin, no error
     */
    @Override
    public void setActiveAdmin(ComponentName adminReceiver, boolean refreshing, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        setActiveAdmin(adminReceiver, refreshing, userHandle, null);
    }

    private void setActiveAdmin(ComponentName adminReceiver, boolean refreshing, int userHandle,
            Bundle onEnableData) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_DEVICE_ADMINS, null);
        enforceFullCrossUsersPermission(userHandle);

        DevicePolicyData policy = getUserData(userHandle);
        DeviceAdminInfo info = findAdmin(adminReceiver, userHandle,
                /* throwForMissingPermission= */ true);
        synchronized (getLockObject()) {
            checkActiveAdminPrecondition(adminReceiver, info, policy);
            long ident = mInjector.binderClearCallingIdentity();
            try {
                final ActiveAdmin existingAdmin
                        = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
                if (!refreshing && existingAdmin != null) {
                    throw new IllegalArgumentException("Admin is already added");
                }
                ActiveAdmin newAdmin = new ActiveAdmin(info, /* parent */ false);
                newAdmin.testOnlyAdmin =
                        (existingAdmin != null) ? existingAdmin.testOnlyAdmin
                                : isPackageTestOnly(adminReceiver.getPackageName(), userHandle);
                policy.mAdminMap.put(adminReceiver, newAdmin);
                int replaceIndex = -1;
                final int N = policy.mAdminList.size();
                for (int i=0; i < N; i++) {
                    ActiveAdmin oldAdmin = policy.mAdminList.get(i);
                    if (oldAdmin.info.getComponent().equals(adminReceiver)) {
                        replaceIndex = i;
                        break;
                    }
                }
                if (replaceIndex == -1) {
                    policy.mAdminList.add(newAdmin);
                    enableIfNecessary(info.getPackageName(), userHandle);
                    mUsageStatsManagerInternal.onActiveAdminAdded(
                            adminReceiver.getPackageName(), userHandle);
                } else {
                    policy.mAdminList.set(replaceIndex, newAdmin);
                }
                saveSettingsLocked(userHandle);
                sendAdminCommandLocked(newAdmin, DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED,
                        onEnableData, null);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    private void loadAdminDataAsync() {
        mInjector.postOnSystemServerInitThreadPool(() -> {
            pushActiveAdminPackages();
            mUsageStatsManagerInternal.onAdminDataAvailable();
            pushAllMeteredRestrictedPackages();
            mInjector.getNetworkPolicyManagerInternal().onAdminDataAvailable();
        });
    }

    private void pushActiveAdminPackages() {
        synchronized (getLockObject()) {
            final List<UserInfo> users = mUserManager.getUsers();
            for (int i = users.size() - 1; i >= 0; --i) {
                final int userId = users.get(i).id;
                mUsageStatsManagerInternal.setActiveAdminApps(
                        getActiveAdminPackagesLocked(userId), userId);
            }
        }
    }

    private void pushAllMeteredRestrictedPackages() {
        synchronized (getLockObject()) {
            final List<UserInfo> users = mUserManager.getUsers();
            for (int i = users.size() - 1; i >= 0; --i) {
                final int userId = users.get(i).id;
                mInjector.getNetworkPolicyManagerInternal().setMeteredRestrictedPackagesAsync(
                        getMeteredDisabledPackagesLocked(userId), userId);
            }
        }
    }

    private void pushActiveAdminPackagesLocked(int userId) {
        mUsageStatsManagerInternal.setActiveAdminApps(
                getActiveAdminPackagesLocked(userId), userId);
    }

    private Set<String> getActiveAdminPackagesLocked(int userId) {
        final DevicePolicyData policy = getUserData(userId);
        Set<String> adminPkgs = null;
        for (int i = policy.mAdminList.size() - 1; i >= 0; --i) {
            final String pkgName = policy.mAdminList.get(i).info.getPackageName();
            if (adminPkgs == null) {
                adminPkgs = new ArraySet<>();
            }
            adminPkgs.add(pkgName);
        }
        return adminPkgs;
    }

    private void transferActiveAdminUncheckedLocked(ComponentName incomingReceiver,
            ComponentName outgoingReceiver, int userHandle) {
        final DevicePolicyData policy = getUserData(userHandle);
        if (!policy.mAdminMap.containsKey(outgoingReceiver)
                && policy.mAdminMap.containsKey(incomingReceiver)) {
            // Nothing to transfer - the incoming receiver is already the active admin.
            return;
        }
        final DeviceAdminInfo incomingDeviceInfo = findAdmin(incomingReceiver, userHandle,
            /* throwForMissingPermission= */ true);
        final ActiveAdmin adminToTransfer = policy.mAdminMap.get(outgoingReceiver);
        final int oldAdminUid = adminToTransfer.getUid();

        adminToTransfer.transfer(incomingDeviceInfo);
        policy.mAdminMap.remove(outgoingReceiver);
        policy.mAdminMap.put(incomingReceiver, adminToTransfer);
        if (policy.mPasswordOwner == oldAdminUid) {
            policy.mPasswordOwner = adminToTransfer.getUid();
        }

        saveSettingsLocked(userHandle);
        sendAdminCommandLocked(adminToTransfer, DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED,
                null, null);
    }

    private void checkActiveAdminPrecondition(ComponentName adminReceiver, DeviceAdminInfo info,
            DevicePolicyData policy) {
        if (info == null) {
            throw new IllegalArgumentException("Bad admin: " + adminReceiver);
        }
        if (!info.getActivityInfo().applicationInfo.isInternal()) {
            throw new IllegalArgumentException("Only apps in internal storage can be active admin: "
                    + adminReceiver);
        }
        if (info.getActivityInfo().applicationInfo.isInstantApp()) {
            throw new IllegalArgumentException("Instant apps cannot be device admins: "
                    + adminReceiver);
        }
        if (policy.mRemovingAdmins.contains(adminReceiver)) {
            throw new IllegalArgumentException(
                    "Trying to set an admin which is being removed");
        }
    }

    @Override
    public boolean isAdminActive(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            return getActiveAdminUncheckedLocked(adminReceiver, userHandle) != null;
        }
    }

    @Override
    public boolean isRemovingAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(userHandle);
            return policyData.mRemovingAdmins.contains(adminReceiver);
        }
    }

    @Override
    public boolean hasGrantedPolicy(ComponentName adminReceiver, int policyId, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin administrator = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (administrator == null) {
                throw new SecurityException("No active admin " + adminReceiver);
            }
            return administrator.info.usesPolicy(policyId);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ComponentName> getActiveAdmins(int userHandle) {
        if (!mHasFeature) {
            return Collections.EMPTY_LIST;
        }

        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle);
            final int N = policy.mAdminList.size();
            if (N <= 0) {
                return null;
            }
            ArrayList<ComponentName> res = new ArrayList<ComponentName>(N);
            for (int i=0; i<N; i++) {
                res.add(policy.mAdminList.get(i).info.getComponent());
            }
            return res;
        }
    }

    @Override
    public boolean packageHasActiveAdmins(String packageName, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle);
            final int N = policy.mAdminList.size();
            for (int i=0; i<N; i++) {
                if (policy.mAdminList.get(i).info.getPackageName().equals(packageName)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public void forceRemoveActiveAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(adminReceiver, "ComponentName is null");
        enforceShell("forceRemoveActiveAdmin");
        long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                if (!isAdminTestOnlyLocked(adminReceiver, userHandle)) {
                    throw new SecurityException("Attempt to remove non-test admin "
                            + adminReceiver + " " + userHandle);
                }

                // If admin is a device or profile owner tidy that up first.
                if (isDeviceOwner(adminReceiver, userHandle)) {
                    clearDeviceOwnerLocked(getDeviceOwnerAdminLocked(), userHandle);
                }
                if (isProfileOwner(adminReceiver, userHandle)) {
                    final ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver,
                            userHandle, /* parent */ false);
                    clearProfileOwnerLocked(admin, userHandle);
                }
            }
            // Remove the admin skipping sending the broadcast.
            removeAdminArtifacts(adminReceiver, userHandle);
            Slog.i(LOG_TAG, "Admin " + adminReceiver + " removed from user " + userHandle);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void clearDeviceOwnerUserRestrictionLocked(UserHandle userHandle) {
        // ManagedProvisioning/DPC sets DISALLOW_ADD_USER. Clear to recover to the original state
        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_USER, userHandle)) {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, false, userHandle);
        }
    }

    /**
     * Return if a given package has testOnly="true", in which case we'll relax certain rules
     * for CTS.
     *
     * DO NOT use this method except in {@link #setActiveAdmin}.  Use {@link #isAdminTestOnlyLocked}
     * to check wehter an active admin is test-only or not.
     *
     * The system allows this flag to be changed when an app is updated, which is not good
     * for us.  So we persist the flag in {@link ActiveAdmin} when an admin is first installed,
     * and used the persisted version in actual checks. (See b/31382361 and b/28928996)
     */
    private boolean isPackageTestOnly(String packageName, int userHandle) {
        final ApplicationInfo ai;
        try {
            ai = mInjector.getIPackageManager().getApplicationInfo(packageName,
                    (PackageManager.MATCH_DIRECT_BOOT_AWARE
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE), userHandle);
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
        if (ai == null) {
            throw new IllegalStateException("Couldn't find package: "
                    + packageName + " on user " + userHandle);
        }
        return (ai.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0;
    }

    /**
     * See {@link #isPackageTestOnly}.
     */
    private boolean isAdminTestOnlyLocked(ComponentName who, int userHandle) {
        final ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
        return (admin != null) && admin.testOnlyAdmin;
    }

    private void enforceShell(String method) {
        final int callingUid = mInjector.binderGetCallingUid();
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            throw new SecurityException("Non-shell user attempted to call " + method);
        }
    }

    @Override
    public void removeActiveAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceUserUnlocked(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (admin == null) {
                return;
            }
            // Active device/profile owners must remain active admins.
            if (isDeviceOwner(adminReceiver, userHandle)
                    || isProfileOwner(adminReceiver, userHandle)) {
                Slog.e(LOG_TAG, "Device/profile owner cannot be removed: component=" +
                        adminReceiver);
                return;
            }
            if (admin.getUid() != mInjector.binderGetCallingUid()) {
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_DEVICE_ADMINS, null);
            }
            long ident = mInjector.binderClearCallingIdentity();
            try {
                removeActiveAdminLocked(adminReceiver, userHandle);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public boolean isSeparateProfileChallengeAllowed(int userHandle) {
        ComponentName profileOwner = getProfileOwner(userHandle);
        // Profile challenge is supported on N or newer release.
        return profileOwner != null &&
                getTargetSdk(profileOwner.getPackageName(), userHandle) > Build.VERSION_CODES.M;
    }

    @Override
    public void setPasswordQuality(ComponentName who, int quality, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        validateQualityConstant(quality);

        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.quality != quality) {
                metrics.quality = quality;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    /**
     * Updates a flag that tells us whether the user's password currently satisfies the
     * requirements set by all of the user's active admins. The flag is updated both in memory
     * and persisted to disk by calling {@link #saveSettingsLocked}, for the value of the flag
     * be the correct one upon boot.
     * This should be called whenever the password or the admin policies have changed.
     */
    private void updatePasswordValidityCheckpointLocked(int userHandle, boolean parent) {
        final int credentialOwner = getCredentialOwner(userHandle, parent);
        DevicePolicyData policy = getUserData(credentialOwner);
        PasswordMetrics metrics = getUserPasswordMetricsLocked(credentialOwner);
        if (metrics == null) {
            metrics = new PasswordMetrics();
        }
        policy.mPasswordValidAtLastCheckpoint =
                isPasswordSufficientForUserWithoutCheckpointLocked(
                        metrics, userHandle, parent);

        saveSettingsLocked(credentialOwner);
    }

    @Override
    public int getPasswordQuality(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return PASSWORD_QUALITY_UNSPECIFIED;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            int mode = PASSWORD_QUALITY_UNSPECIFIED;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordMetrics.quality : mode;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (mode < admin.minimumPasswordMetrics.quality) {
                    mode = admin.minimumPasswordMetrics.quality;
                }
            }
            return mode;
        }
    }

    private List<ActiveAdmin> getActiveAdminsForLockscreenPoliciesLocked(
            int userHandle, boolean parent) {
        if (!parent && isSeparateProfileChallengeEnabled(userHandle)) {
            // If this user has a separate challenge, only return its restrictions.
            return getUserDataUnchecked(userHandle).mAdminList;
        } else {
            // Return all admins for this user and the profiles that are visible from this
            // user that do not use a separate work challenge.
            ArrayList<ActiveAdmin> admins = new ArrayList<ActiveAdmin>();
            for (UserInfo userInfo : mUserManager.getProfiles(userHandle)) {
                DevicePolicyData policy = getUserData(userInfo.id);
                if (!userInfo.isManagedProfile()) {
                    admins.addAll(policy.mAdminList);
                } else {
                    // For managed profiles, we always include the policies set on the parent
                    // profile. Additionally, we include the ones set on the managed profile
                    // if no separate challenge is in place.
                    boolean hasSeparateChallenge = isSeparateProfileChallengeEnabled(userInfo.id);
                    final int N = policy.mAdminList.size();
                    for (int i = 0; i < N; i++) {
                        ActiveAdmin admin = policy.mAdminList.get(i);
                        if (admin.hasParentActiveAdmin()) {
                            admins.add(admin.getParentActiveAdmin());
                        }
                        if (!hasSeparateChallenge) {
                            admins.add(admin);
                        }
                    }
                }
            }
            return admins;
        }
    }

    private boolean isSeparateProfileChallengeEnabled(int userHandle) {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            return mLockPatternUtils.isSeparateProfileChallengeEnabled(userHandle);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public void setPasswordMinimumLength(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.length != length) {
                metrics.length = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumLength(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.length, PASSWORD_QUALITY_UNSPECIFIED);
    }

    @Override
    public void setPasswordHistoryLength(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.passwordHistoryLength != length) {
                ap.passwordHistoryLength = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            final int affectedUserId = parent ? getProfileParentId(userId) : userId;
            SecurityLog.writeEvent(SecurityLog.TAG_PASSWORD_HISTORY_LENGTH_SET,
                    who.getPackageName(), userId, affectedUserId, length);
        }
    }

    @Override
    public int getPasswordHistoryLength(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.passwordHistoryLength, PASSWORD_QUALITY_UNSPECIFIED);
    }

    @Override
    public void setPasswordExpirationTimeout(ComponentName who, long timeout, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgumentNonnegative(timeout, "Timeout must be >= 0 ms");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD, parent);
            // Calling this API automatically bumps the expiration date
            final long expiration = timeout > 0L ? (timeout + System.currentTimeMillis()) : 0L;
            ap.passwordExpirationDate = expiration;
            ap.passwordExpirationTimeout = timeout;
            if (timeout > 0L) {
                Slog.w(LOG_TAG, "setPasswordExpiration(): password will expire on "
                        + DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT)
                        .format(new Date(expiration)));
            }
            saveSettingsLocked(userHandle);

            // in case this is the first one, set the alarm on the appropriate user.
            setExpirationAlarmCheckLocked(mContext, userHandle, parent);
        }
        if (SecurityLog.isLoggingEnabled()) {
            final int affectedUserId = parent ? getProfileParentId(userHandle) : userHandle;
            SecurityLog.writeEvent(SecurityLog.TAG_PASSWORD_EXPIRATION_SET, who.getPackageName(),
                    userHandle, affectedUserId, timeout);
        }
    }

    /**
     * Return a single admin's expiration cycle time, or the min of all cycle times.
     * Returns 0 if not configured.
     */
    @Override
    public long getPasswordExpirationTimeout(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0L;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            long timeout = 0L;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.passwordExpirationTimeout : timeout;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (timeout == 0L || (admin.passwordExpirationTimeout != 0L
                        && timeout > admin.passwordExpirationTimeout)) {
                    timeout = admin.passwordExpirationTimeout;
                }
            }
            return timeout;
        }
    }

    @Override
    public boolean addCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        final int userId = UserHandle.getCallingUserId();
        List<String> changedProviders = null;

        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (activeAdmin.crossProfileWidgetProviders == null) {
                activeAdmin.crossProfileWidgetProviders = new ArrayList<>();
            }
            List<String> providers = activeAdmin.crossProfileWidgetProviders;
            if (!providers.contains(packageName)) {
                providers.add(packageName);
                changedProviders = new ArrayList<>(providers);
                saveSettingsLocked(userId);
            }
        }

        if (changedProviders != null) {
            mLocalService.notifyCrossProfileProvidersChanged(userId, changedProviders);
            return true;
        }

        return false;
    }

    @Override
    public boolean removeCrossProfileWidgetProvider(ComponentName admin, String packageName) {
        final int userId = UserHandle.getCallingUserId();
        List<String> changedProviders = null;

        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (activeAdmin.crossProfileWidgetProviders == null
                    || activeAdmin.crossProfileWidgetProviders.isEmpty()) {
                return false;
            }
            List<String> providers = activeAdmin.crossProfileWidgetProviders;
            if (providers.remove(packageName)) {
                changedProviders = new ArrayList<>(providers);
                saveSettingsLocked(userId);
            }
        }

        if (changedProviders != null) {
            mLocalService.notifyCrossProfileProvidersChanged(userId, changedProviders);
            return true;
        }

        return false;
    }

    @Override
    public List<String> getCrossProfileWidgetProviders(ComponentName admin) {
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (activeAdmin.crossProfileWidgetProviders == null
                    || activeAdmin.crossProfileWidgetProviders.isEmpty()) {
                return null;
            }
            if (mInjector.binderIsCallingUidMyUid()) {
                return new ArrayList<>(activeAdmin.crossProfileWidgetProviders);
            } else {
                return activeAdmin.crossProfileWidgetProviders;
            }
        }
    }

    /**
     * Return a single admin's expiration date/time, or the min (soonest) for all admins.
     * Returns 0 if not configured.
     */
    private long getPasswordExpirationLocked(ComponentName who, int userHandle, boolean parent) {
        long timeout = 0L;

        if (who != null) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
            return admin != null ? admin.passwordExpirationDate : timeout;
        }

        // Return the strictest policy across all participating admins.
        List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
        final int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin = admins.get(i);
            if (timeout == 0L || (admin.passwordExpirationDate != 0
                    && timeout > admin.passwordExpirationDate)) {
                timeout = admin.passwordExpirationDate;
            }
        }
        return timeout;
    }

    @Override
    public long getPasswordExpiration(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0L;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            return getPasswordExpirationLocked(who, userHandle, parent);
        }
    }

    @Override
    public void setPasswordMinimumUpperCase(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            final ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.upperCase != length) {
                metrics.upperCase = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumUpperCase(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.upperCase, PASSWORD_QUALITY_COMPLEX);
    }

    @Override
    public void setPasswordMinimumLowerCase(ComponentName who, int length, boolean parent) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.lowerCase != length) {
                metrics.lowerCase = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumLowerCase(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.lowerCase, PASSWORD_QUALITY_COMPLEX);
    }

    @Override
    public void setPasswordMinimumLetters(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.letters != length) {
                metrics.letters = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumLetters(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.letters, PASSWORD_QUALITY_COMPLEX);
    }

    @Override
    public void setPasswordMinimumNumeric(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.numeric != length) {
                metrics.numeric = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumNumeric(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.numeric, PASSWORD_QUALITY_COMPLEX);
    }

    @Override
    public void setPasswordMinimumSymbols(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.symbols != length) {
                ap.minimumPasswordMetrics.symbols = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumSymbols(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.symbols, PASSWORD_QUALITY_COMPLEX);
    }

    @Override
    public void setPasswordMinimumNonLetter(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            final PasswordMetrics metrics = ap.minimumPasswordMetrics;
            if (metrics.nonLetter != length) {
                ap.minimumPasswordMetrics.nonLetter = length;
                updatePasswordValidityCheckpointLocked(userId, parent);
                saveSettingsLocked(userId);
            }
            maybeLogPasswordComplexitySet(who, userId, parent, metrics);
        }
    }

    @Override
    public int getPasswordMinimumNonLetter(ComponentName who, int userHandle, boolean parent) {
        return getStrictestPasswordRequirement(who, userHandle, parent,
                admin -> admin.minimumPasswordMetrics.nonLetter, PASSWORD_QUALITY_COMPLEX);
    }

    /**
     * Calculates strictest (maximum) value for a given password property enforced by admin[s].
     */
    private int getStrictestPasswordRequirement(ComponentName who, int userHandle,
            boolean parent, Function<ActiveAdmin, Integer> getter, int minimumPasswordQuality) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            if (who != null) {
                final ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? getter.apply(admin) : 0;
            }

            int maxValue = 0;
            final List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                final ActiveAdmin admin = admins.get(i);
                if (!isLimitPasswordAllowed(admin, minimumPasswordQuality)) {
                    continue;
                }
                final Integer adminValue = getter.apply(admin);
                if (adminValue > maxValue) {
                    maxValue = adminValue;
                }
            }
            return maxValue;
        }
    }

    @Override
    public boolean isActivePasswordSufficient(int userHandle, boolean parent) {
        if (!mHasFeature) {
            return true;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceUserUnlocked(userHandle, parent);

        synchronized (getLockObject()) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            int credentialOwner = getCredentialOwner(userHandle, parent);
            DevicePolicyData policy = getUserDataUnchecked(credentialOwner);
            PasswordMetrics metrics = getUserPasswordMetricsLocked(credentialOwner);
            return isActivePasswordSufficientForUserLocked(
                    policy.mPasswordValidAtLastCheckpoint, metrics, userHandle, parent);
        }
    }

    @Override
    public boolean isUsingUnifiedPassword(ComponentName admin) {
        if (!mHasFeature) {
            return true;
        }
        final int userId = mInjector.userHandleGetCallingUserId();
        enforceProfileOrDeviceOwner(admin);
        enforceManagedProfile(userId, "query unified challenge status");
        return !isSeparateProfileChallengeEnabled(userId);
    }

    @Override
    public boolean isProfileActivePasswordSufficientForParent(int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "call APIs refering to the parent profile");

        synchronized (getLockObject()) {
            final int targetUser = getProfileParentId(userHandle);
            enforceUserUnlocked(targetUser, false);
            int credentialOwner = getCredentialOwner(userHandle, false);
            DevicePolicyData policy = getUserDataUnchecked(credentialOwner);
            PasswordMetrics metrics = getUserPasswordMetricsLocked(credentialOwner);
            return isActivePasswordSufficientForUserLocked(
                    policy.mPasswordValidAtLastCheckpoint, metrics, targetUser, false);
        }
    }

    private boolean isActivePasswordSufficientForUserLocked(
            boolean passwordValidAtLastCheckpoint, PasswordMetrics metrics, int userHandle,
            boolean parent) {
        if (!mInjector.storageManagerIsFileBasedEncryptionEnabled() && (metrics == null)) {
            // Before user enters their password for the first time after a reboot, return the
            // value of this flag, which tells us whether the password was valid the last time
            // settings were saved.  If DPC changes password requirements on boot so that the
            // current password no longer meets the requirements, this value will be stale until
            // the next time the password is entered.
            return passwordValidAtLastCheckpoint;
        }

        if (metrics == null) {
            // This could happen if the user never had a password set, for example, so
            // setActivePasswordState has never been called for it.
            metrics = new PasswordMetrics();
        }
        return isPasswordSufficientForUserWithoutCheckpointLocked(metrics, userHandle, parent);
    }

    /**
     * Returns {@code true} if the password represented by the {@code passwordMetrics} argument
     * sufficiently fulfills the password requirements for the user corresponding to
     * {@code userHandle} (or its parent, if {@code parent} is set to {@code true}).
     */
    private boolean isPasswordSufficientForUserWithoutCheckpointLocked(
            PasswordMetrics passwordMetrics, int userHandle, boolean parent) {
        final int requiredPasswordQuality = getPasswordQuality(null, userHandle, parent);

        if (passwordMetrics.quality < requiredPasswordQuality) {
            return false;
        }
        if (requiredPasswordQuality >= DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                && passwordMetrics.length < getPasswordMinimumLength(
                        null, userHandle, parent)) {
            return false;
        }
        if (requiredPasswordQuality != DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
            return true;
        }
        return passwordMetrics.upperCase >= getPasswordMinimumUpperCase(
                    null, userHandle, parent)
                && passwordMetrics.lowerCase >= getPasswordMinimumLowerCase(
                        null, userHandle, parent)
                && passwordMetrics.letters >= getPasswordMinimumLetters(
                        null, userHandle, parent)
                && passwordMetrics.numeric >= getPasswordMinimumNumeric(
                        null, userHandle, parent)
                && passwordMetrics.symbols >= getPasswordMinimumSymbols(
                        null, userHandle, parent)
                && passwordMetrics.nonLetter >= getPasswordMinimumNonLetter(
                        null, userHandle, parent);
    }

    @Override
    public int getCurrentFailedPasswordAttempts(int userHandle, boolean parent) {
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            if (!isCallerWithSystemUid()) {
                // This API can be called by an active device admin or by keyguard code.
                if (mContext.checkCallingPermission(permission.ACCESS_KEYGUARD_SECURE_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    getActiveAdminForCallerLocked(
                            null, DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, parent);
                }
            }

            DevicePolicyData policy = getUserDataUnchecked(getCredentialOwner(userHandle, parent));

            return policy.mFailedPasswordAttempts;
        }
    }

    @Override
    public void setMaximumFailedPasswordsForWipe(ComponentName who, int num, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_WIPE_DATA, parent);
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, parent);
            if (ap.maximumFailedPasswordsForWipe != num) {
                ap.maximumFailedPasswordsForWipe = num;
                saveSettingsLocked(userId);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            final int affectedUserId = parent ? getProfileParentId(userId) : userId;
            SecurityLog.writeEvent(SecurityLog.TAG_MAX_PASSWORD_ATTEMPTS_SET, who.getPackageName(),
                    userId, affectedUserId, num);
        }
    }

    @Override
    public int getMaximumFailedPasswordsForWipe(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin admin = (who != null)
                    ? getActiveAdminUncheckedLocked(who, userHandle, parent)
                    : getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle, parent);
            return admin != null ? admin.maximumFailedPasswordsForWipe : 0;
        }
    }

    @Override
    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle, boolean parent) {
        if (!mHasFeature) {
            return UserHandle.USER_NULL;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getAdminWithMinimumFailedPasswordsForWipeLocked(
                    userHandle, parent);
            return admin != null ? admin.getUserHandle().getIdentifier() : UserHandle.USER_NULL;
        }
    }

    /**
     * Returns the admin with the strictest policy on maximum failed passwords for:
     * <ul>
     *   <li>this user if it has a separate profile challenge, or
     *   <li>this user and all profiles that don't have their own challenge otherwise.
     * </ul>
     * <p>If the policy for the primary and any other profile are equal, it returns the admin for
     * the primary profile.
     * Returns {@code null} if no participating admin has that policy set.
     */
    private ActiveAdmin getAdminWithMinimumFailedPasswordsForWipeLocked(
            int userHandle, boolean parent) {
        int count = 0;
        ActiveAdmin strictestAdmin = null;

        // Return the strictest policy across all participating admins.
        List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
        final int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin = admins.get(i);
            if (admin.maximumFailedPasswordsForWipe ==
                    ActiveAdmin.DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE) {
                continue;  // No max number of failed passwords policy set for this profile.
            }

            // We always favor the primary profile if several profiles have the same value set.
            int userId = admin.getUserHandle().getIdentifier();
            if (count == 0 ||
                    count > admin.maximumFailedPasswordsForWipe ||
                    (count == admin.maximumFailedPasswordsForWipe &&
                            getUserInfo(userId).isPrimary())) {
                count = admin.maximumFailedPasswordsForWipe;
                strictestAdmin = admin;
            }
        }
        return strictestAdmin;
    }

    private UserInfo getUserInfo(@UserIdInt int userId) {
        final long token = mInjector.binderClearCallingIdentity();
        try {
            return mUserManager.getUserInfo(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private boolean canPOorDOCallResetPassword(ActiveAdmin admin, @UserIdInt int userId) {
        // Only if the admins targets a pre-O SDK
        return getTargetSdk(admin.info.getPackageName(), userId) < Build.VERSION_CODES.O;
    }

    /* PO or DO could do an untrusted reset in certain conditions. */
    private boolean canUserHaveUntrustedCredentialReset(@UserIdInt int userId) {
        synchronized (getLockObject()) {
            // An active DO or PO might be able to fo an untrusted credential reset
            for (final ActiveAdmin admin : getUserData(userId).mAdminList) {
                if (!isActiveAdminWithPolicyForUserLocked(admin,
                          DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, userId)) {
                    continue;
                }
                if (canPOorDOCallResetPassword(admin, userId)) {
                    return true;
                }
            }
            return false;
        }
    }
    @Override
    public boolean resetPassword(String passwordOrNull, int flags) throws RemoteException {
        final int callingUid = mInjector.binderGetCallingUid();
        final int userHandle = mInjector.userHandleGetCallingUserId();

        String password = passwordOrNull != null ? passwordOrNull : "";

        // Password resetting to empty/null is not allowed for managed profiles.
        if (TextUtils.isEmpty(password)) {
            enforceNotManagedProfile(userHandle, "clear the active password");
        }

        synchronized (getLockObject()) {
            // If caller has PO (or DO) it can change the password, so see if that's the case first.
            ActiveAdmin admin = getActiveAdminWithPolicyForUidLocked(
                    null, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, callingUid);
            final boolean preN;
            if (admin != null) {
                if (!canPOorDOCallResetPassword(admin, userHandle)) {
                    throw new SecurityException("resetPassword() is deprecated for DPC targeting O"
                            + " or later");
                }
                preN = getTargetSdk(admin.info.getPackageName(),
                        userHandle) <= android.os.Build.VERSION_CODES.M;
            } else {
                // Otherwise, make sure the caller has any active admin with the right policy.
                admin = getActiveAdminForCallerLocked(null,
                        DeviceAdminInfo.USES_POLICY_RESET_PASSWORD);
                preN = getTargetSdk(admin.info.getPackageName(),
                        userHandle) <= android.os.Build.VERSION_CODES.M;

                // As of N, password resetting to empty/null is not allowed anymore.
                // TODO Should we allow DO/PO to set an empty password?
                if (TextUtils.isEmpty(password)) {
                    if (!preN) {
                        throw new SecurityException("Cannot call with null password");
                    } else {
                        Slog.e(LOG_TAG, "Cannot call with null password");
                        return false;
                    }
                }
                // As of N, password cannot be changed by the admin if it is already set.
                if (isLockScreenSecureUnchecked(userHandle)) {
                    if (!preN) {
                        throw new SecurityException("Admin cannot change current password");
                    } else {
                        Slog.e(LOG_TAG, "Admin cannot change current password");
                        return false;
                    }
                }
            }
            // Do not allow to reset password when current user has a managed profile
            if (!isManagedProfile(userHandle)) {
                for (UserInfo userInfo : mUserManager.getProfiles(userHandle)) {
                    if (userInfo.isManagedProfile()) {
                        if (!preN) {
                            throw new IllegalStateException(
                                    "Cannot reset password on user has managed profile");
                        } else {
                            Slog.e(LOG_TAG, "Cannot reset password on user has managed profile");
                            return false;
                        }
                    }
                }
            }
            // Do not allow to reset password when user is locked
            if (!mUserManager.isUserUnlocked(userHandle)) {
                if (!preN) {
                    throw new IllegalStateException("Cannot reset password when user is locked");
                } else {
                    Slog.e(LOG_TAG, "Cannot reset password when user is locked");
                    return false;
                }
            }
        }

        return resetPasswordInternal(password, 0, null, flags, callingUid, userHandle);
    }

    private boolean resetPasswordInternal(String password, long tokenHandle, byte[] token,
            int flags, int callingUid, int userHandle) {
        int quality;
        synchronized (getLockObject()) {
            quality = getPasswordQuality(null, userHandle, /* parent */ false);
            if (quality == DevicePolicyManager.PASSWORD_QUALITY_MANAGED) {
                quality = PASSWORD_QUALITY_UNSPECIFIED;
            }
            final PasswordMetrics metrics = PasswordMetrics.computeForPassword(password);
            final int realQuality = metrics.quality;
            if (realQuality < quality
                    && quality != DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
                Slog.w(LOG_TAG, "resetPassword: password quality 0x"
                        + Integer.toHexString(realQuality)
                        + " does not meet required quality 0x"
                        + Integer.toHexString(quality));
                return false;
            }
            quality = Math.max(realQuality, quality);
            int length = getPasswordMinimumLength(null, userHandle, /* parent */ false);
            if (password.length() < length) {
                Slog.w(LOG_TAG, "resetPassword: password length " + password.length()
                        + " does not meet required length " + length);
                return false;
            }
            if (quality == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
                int neededLetters = getPasswordMinimumLetters(null, userHandle, /* parent */ false);
                if(metrics.letters < neededLetters) {
                    Slog.w(LOG_TAG, "resetPassword: number of letters " + metrics.letters
                            + " does not meet required number of letters " + neededLetters);
                    return false;
                }
                int neededNumeric = getPasswordMinimumNumeric(null, userHandle, /* parent */ false);
                if (metrics.numeric < neededNumeric) {
                    Slog.w(LOG_TAG, "resetPassword: number of numerical digits " + metrics.numeric
                            + " does not meet required number of numerical digits "
                            + neededNumeric);
                    return false;
                }
                int neededLowerCase = getPasswordMinimumLowerCase(
                        null, userHandle, /* parent */ false);
                if (metrics.lowerCase < neededLowerCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of lowercase letters "
                            + metrics.lowerCase
                            + " does not meet required number of lowercase letters "
                            + neededLowerCase);
                    return false;
                }
                int neededUpperCase = getPasswordMinimumUpperCase(
                        null, userHandle, /* parent */ false);
                if (metrics.upperCase < neededUpperCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of uppercase letters "
                            + metrics.upperCase
                            + " does not meet required number of uppercase letters "
                            + neededUpperCase);
                    return false;
                }
                int neededSymbols = getPasswordMinimumSymbols(null, userHandle, /* parent */ false);
                if (metrics.symbols < neededSymbols) {
                    Slog.w(LOG_TAG, "resetPassword: number of special symbols " + metrics.symbols
                            + " does not meet required number of special symbols " + neededSymbols);
                    return false;
                }
                int neededNonLetter = getPasswordMinimumNonLetter(
                        null, userHandle, /* parent */ false);
                if (metrics.nonLetter < neededNonLetter) {
                    Slog.w(LOG_TAG, "resetPassword: number of non-letter characters "
                            + metrics.nonLetter
                            + " does not meet required number of non-letter characters "
                            + neededNonLetter);
                    return false;
                }
            }
        }

        DevicePolicyData policy = getUserData(userHandle);
        if (policy.mPasswordOwner >= 0 && policy.mPasswordOwner != callingUid) {
            Slog.w(LOG_TAG, "resetPassword: already set by another uid and not entered by user");
            return false;
        }

        boolean callerIsDeviceOwnerAdmin = isCallerDeviceOwner(callingUid);
        boolean doNotAskCredentialsOnBoot =
                (flags & DevicePolicyManager.RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT) != 0;
        if (callerIsDeviceOwnerAdmin && doNotAskCredentialsOnBoot) {
            setDoNotAskCredentialsOnBoot();
        }

        // Don't do this with the lock held, because it is going to call
        // back in to the service.
        final long ident = mInjector.binderClearCallingIdentity();
        final boolean result;
        try {
            if (token == null) {
                if (!TextUtils.isEmpty(password)) {
                    mLockPatternUtils.saveLockPassword(password, null, quality, userHandle);
                } else {
                    mLockPatternUtils.clearLock(null, userHandle);
                }
                result = true;
            } else {
                result = mLockPatternUtils.setLockCredentialWithToken(password,
                        TextUtils.isEmpty(password) ? LockPatternUtils.CREDENTIAL_TYPE_NONE
                                : LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                        quality, tokenHandle, token, userHandle);
            }
            boolean requireEntry = (flags & DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY) != 0;
            if (requireEntry) {
                mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW,
                        UserHandle.USER_ALL);
            }
            synchronized (getLockObject()) {
                int newOwner = requireEntry ? callingUid : -1;
                if (policy.mPasswordOwner != newOwner) {
                    policy.mPasswordOwner = newOwner;
                    saveSettingsLocked(userHandle);
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
        return result;
    }

    private boolean isLockScreenSecureUnchecked(int userId) {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            return mLockPatternUtils.isSecure(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void setDoNotAskCredentialsOnBoot() {
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(UserHandle.USER_SYSTEM);
            if (!policyData.doNotAskCredentialsOnBoot) {
                policyData.doNotAskCredentialsOnBoot = true;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
        }
    }

    @Override
    public boolean getDoNotAskCredentialsOnBoot() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.QUERY_DO_NOT_ASK_CREDENTIALS_ON_BOOT, null);
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(UserHandle.USER_SYSTEM);
            return policyData.doNotAskCredentialsOnBoot;
        }
    }

    @Override
    public void setMaximumTimeToLock(ComponentName who, long timeMs, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_FORCE_LOCK, parent);
            if (ap.maximumTimeToUnlock != timeMs) {
                ap.maximumTimeToUnlock = timeMs;
                saveSettingsLocked(userHandle);
                updateMaximumTimeToLockLocked(userHandle);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            final int affectedUserId = parent ? getProfileParentId(userHandle) : userHandle;
            SecurityLog.writeEvent(SecurityLog.TAG_MAX_SCREEN_LOCK_TIMEOUT_SET,
                    who.getPackageName(), userHandle, affectedUserId, timeMs);
        }
    }

    private void updateMaximumTimeToLockLocked(@UserIdInt int userId) {
        // Update the profile's timeout
        if (isManagedProfile(userId)) {
            updateProfileLockTimeoutLocked(userId);
        }

        final long timeMs;
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            // Update the device timeout
            final int parentId = getProfileParentId(userId);
            timeMs = getMaximumTimeToLockPolicyFromAdmins(
                    getActiveAdminsForLockscreenPoliciesLocked(parentId, false));

            final DevicePolicyData policy = getUserDataUnchecked(parentId);
            if (policy.mLastMaximumTimeToLock == timeMs) {
                return;
            }
            policy.mLastMaximumTimeToLock = timeMs;

            if (policy.mLastMaximumTimeToLock != Long.MAX_VALUE) {
                // Make sure KEEP_SCREEN_ON is disabled, since that
                // would allow bypassing of the maximum time to lock.
                mInjector.settingsGlobalPutInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }

        mInjector.getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(
                UserHandle.USER_SYSTEM, timeMs);
    }

    private void updateProfileLockTimeoutLocked(@UserIdInt int userId) {
        final long timeMs;
        if (isSeparateProfileChallengeEnabled(userId)) {
            timeMs = getMaximumTimeToLockPolicyFromAdmins(
                    getActiveAdminsForLockscreenPoliciesLocked(userId, false /* parent */));
        } else {
            timeMs = Long.MAX_VALUE;
        }

        final DevicePolicyData policy = getUserDataUnchecked(userId);
        if (policy.mLastMaximumTimeToLock == timeMs) {
            return;
        }
        policy.mLastMaximumTimeToLock = timeMs;

        mInjector.getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(
                userId, policy.mLastMaximumTimeToLock);
    }

    @Override
    public long getMaximumTimeToLock(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            if (who != null) {
                final ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.maximumTimeToUnlock : 0;
            }
            // Return the strictest policy across all participating admins.
            final List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(
                    userHandle, parent);
            final long timeMs = getMaximumTimeToLockPolicyFromAdmins(admins);
            return timeMs == Long.MAX_VALUE ? 0 : timeMs;
        }
    }

    private long getMaximumTimeToLockPolicyFromAdmins(List<ActiveAdmin> admins) {
        long time = Long.MAX_VALUE;
        for (final ActiveAdmin admin : admins) {
            if (admin.maximumTimeToUnlock > 0 && admin.maximumTimeToUnlock < time) {
                time = admin.maximumTimeToUnlock;
            }
        }
        return time;
    }

    @Override
    public void setRequiredStrongAuthTimeout(ComponentName who, long timeoutMs,
            boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgument(timeoutMs >= 0, "Timeout must not be a negative number.");
        // timeoutMs with value 0 means that the admin doesn't participate
        // timeoutMs is clamped to the interval in case the internal constants change in the future
        final long minimumStrongAuthTimeout = getMinimumStrongAuthTimeoutMs();
        if (timeoutMs != 0 && timeoutMs < minimumStrongAuthTimeout) {
            timeoutMs = minimumStrongAuthTimeout;
        }
        if (timeoutMs > DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS) {
            timeoutMs = DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS;
        }

        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, parent);
            if (ap.strongAuthUnlockTimeout != timeoutMs) {
                ap.strongAuthUnlockTimeout = timeoutMs;
                saveSettingsLocked(userHandle);
            }
        }
    }

    /**
     * Return a single admin's strong auth unlock timeout or minimum value (strictest) of all
     * admins if who is null.
     * Returns 0 if not configured for the provided admin.
     */
    @Override
    public long getRequiredStrongAuthTimeout(ComponentName who, int userId, boolean parent) {
        if (!mHasFeature) {
            return DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS;
        }
        enforceFullCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userId, parent);
                return admin != null ? admin.strongAuthUnlockTimeout : 0;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(userId, parent);

            long strongAuthUnlockTimeout = DevicePolicyManager.DEFAULT_STRONG_AUTH_TIMEOUT_MS;
            for (int i = 0; i < admins.size(); i++) {
                final long timeout = admins.get(i).strongAuthUnlockTimeout;
                if (timeout != 0) { // take only participating admins into account
                    strongAuthUnlockTimeout = Math.min(timeout, strongAuthUnlockTimeout);
                }
            }
            return Math.max(strongAuthUnlockTimeout, getMinimumStrongAuthTimeoutMs());
        }
    }

    private long getMinimumStrongAuthTimeoutMs() {
        if (!mInjector.isBuildDebuggable()) {
            return MINIMUM_STRONG_AUTH_TIMEOUT_MS;
        }
        // ideally the property was named persist.sys.min_strong_auth_timeout, but system property
        // name cannot be longer than 31 characters
        return Math.min(mInjector.systemPropertiesGetLong("persist.sys.min_str_auth_timeo",
                MINIMUM_STRONG_AUTH_TIMEOUT_MS),
                MINIMUM_STRONG_AUTH_TIMEOUT_MS);
    }

    @Override
    public void lockNow(int flags, boolean parent) {
        if (!mHasFeature) {
            return;
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            final ActiveAdmin admin = getActiveAdminForCallerLocked(
                    null, DeviceAdminInfo.USES_POLICY_FORCE_LOCK, parent);

            final long ident = mInjector.binderClearCallingIdentity();
            try {
                final ComponentName adminComponent = admin.info.getComponent();
                // Evict key
                if ((flags & DevicePolicyManager.FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY) != 0) {
                    enforceManagedProfile(
                            callingUserId, "set FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY");
                    if (!isProfileOwner(adminComponent, callingUserId)) {
                        throw new SecurityException("Only profile owner admins can set "
                                + "FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY");
                    }
                    if (parent) {
                        throw new IllegalArgumentException(
                                "Cannot set FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY for the parent");
                    }
                    if (!mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
                        throw new UnsupportedOperationException(
                                "FLAG_EVICT_CREDENTIAL_ENCRYPTION_KEY only applies to FBE devices");
                    }
                    mUserManager.evictCredentialEncryptionKey(callingUserId);
                }

                // Lock all users unless this is a managed profile with a separate challenge
                final int userToLock = (parent || !isSeparateProfileChallengeEnabled(callingUserId)
                        ? UserHandle.USER_ALL : callingUserId);
                mLockPatternUtils.requireStrongAuth(
                        STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW, userToLock);

                // Require authentication for the device or profile
                if (userToLock == UserHandle.USER_ALL) {
                    // Power off the display
                    mInjector.powerManagerGoToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN, 0);
                    mInjector.getIWindowManager().lockNow(null);
                } else {
                    mInjector.getTrustManager().setDeviceLockedForUser(userToLock, true);
                }

                if (SecurityLog.isLoggingEnabled()) {
                    final int affectedUserId =
                            parent ? getProfileParentId(callingUserId) : callingUserId;
                    SecurityLog.writeEvent(SecurityLog.TAG_REMOTE_LOCK,
                            adminComponent.getPackageName(), callingUserId, affectedUserId);
                }
            } catch (RemoteException e) {
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void enforceCanManageCaCerts(ComponentName who, String callerPackage) {
        if (who == null) {
            if (!isCallerDelegate(callerPackage, DELEGATION_CERT_INSTALL)) {
                mContext.enforceCallingOrSelfPermission(MANAGE_CA_CERTIFICATES, null);
            }
        } else {
            enforceProfileOrDeviceOwner(who);
        }
    }

    private void enforceProfileOrDeviceOwner(ComponentName who) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }
    }

    @Override
    public boolean approveCaCert(String alias, int userId, boolean approval) {
        enforceManageUsers();
        synchronized (getLockObject()) {
            Set<String> certs = getUserData(userId).mAcceptedCaCertificates;
            boolean changed = (approval ? certs.add(alias) : certs.remove(alias));
            if (!changed) {
                return false;
            }
            saveSettingsLocked(userId);
        }
        mCertificateMonitor.onCertificateApprovalsChanged(userId);
        return true;
    }

    @Override
    public boolean isCaCertApproved(String alias, int userId) {
        enforceManageUsers();
        synchronized (getLockObject()) {
            return getUserData(userId).mAcceptedCaCertificates.contains(alias);
        }
    }

    private void removeCaApprovalsIfNeeded(int userId) {
        for (UserInfo userInfo : mUserManager.getProfiles(userId)) {
            boolean isSecure = mLockPatternUtils.isSecure(userInfo.id);
            if (userInfo.isManagedProfile()){
                isSecure |= mLockPatternUtils.isSecure(getProfileParentId(userInfo.id));
            }
            if (!isSecure) {
                synchronized (getLockObject()) {
                    getUserData(userInfo.id).mAcceptedCaCertificates.clear();
                    saveSettingsLocked(userInfo.id);
                }
                mCertificateMonitor.onCertificateApprovalsChanged(userId);
            }
        }
    }

    @Override
    public boolean installCaCert(ComponentName admin, String callerPackage, byte[] certBuffer)
            throws RemoteException {
        if (!mHasFeature) {
            return false;
        }
        enforceCanManageCaCerts(admin, callerPackage);

        final String alias;

        final UserHandle userHandle = mInjector.binderGetCallingUserHandle();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            alias = mCertificateMonitor.installCaCert(userHandle, certBuffer);
            if (alias == null) {
                Log.w(LOG_TAG, "Problem installing cert");
                return false;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }

        synchronized (getLockObject()) {
            getUserData(userHandle.getIdentifier()).mOwnerInstalledCaCerts.add(alias);
            saveSettingsLocked(userHandle.getIdentifier());
        }
        return true;
    }

    @Override
    public void uninstallCaCerts(ComponentName admin, String callerPackage, String[] aliases) {
        if (!mHasFeature) {
            return;
        }
        enforceCanManageCaCerts(admin, callerPackage);

        final int userId = mInjector.userHandleGetCallingUserId();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            mCertificateMonitor.uninstallCaCerts(UserHandle.of(userId), aliases);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }

        synchronized (getLockObject()) {
            if (getUserData(userId).mOwnerInstalledCaCerts.removeAll(Arrays.asList(aliases))) {
                saveSettingsLocked(userId);
            }
        }
    }

    @Override
    public boolean installKeyPair(ComponentName who, String callerPackage, byte[] privKey,
            byte[] cert, byte[] chain, String alias, boolean requestAccess,
            boolean isUserSelectable) {
        enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                DELEGATION_CERT_INSTALL);


        final int callingUid = mInjector.binderGetCallingUid();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            final KeyChainConnection keyChainConnection =
                    KeyChain.bindAsUser(mContext, UserHandle.getUserHandleForUid(callingUid));
            try {
                IKeyChainService keyChain = keyChainConnection.getService();
                if (!keyChain.installKeyPair(privKey, cert, chain, alias)) {
                    return false;
                }
                if (requestAccess) {
                    keyChain.setGrant(callingUid, alias, true);
                }
                keyChain.setUserSelectable(alias, isUserSelectable);
                return true;
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Installing certificate", e);
            } finally {
                keyChainConnection.close();
            }
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Interrupted while installing certificate", e);
            Thread.currentThread().interrupt();
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        return false;
    }

    @Override
    public boolean removeKeyPair(ComponentName who, String callerPackage, String alias) {
        enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                DELEGATION_CERT_INSTALL);

        final UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        final long id = Binder.clearCallingIdentity();
        try {
            final KeyChainConnection keyChainConnection = KeyChain.bindAsUser(mContext, userHandle);
            try {
                IKeyChainService keyChain = keyChainConnection.getService();
                return keyChain.removeKeyPair(alias);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Removing keypair", e);
            } finally {
                keyChainConnection.close();
            }
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Interrupted while removing keypair", e);
            Thread.currentThread().interrupt();
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return false;
    }

    private void enforceIsDeviceOwnerOrCertInstallerOfDeviceOwner(
            ComponentName who, String callerPackage, int callerUid) throws SecurityException {
        if (who == null) {
            if (!mOwners.hasDeviceOwner()) {
                throw new SecurityException("Not in Device Owner mode.");
            }
            if (UserHandle.getUserId(callerUid) != mOwners.getDeviceOwnerUserId()) {
                throw new SecurityException("Caller not from device owner user");
            }
            if (!isCallerDelegate(callerPackage, DELEGATION_CERT_INSTALL)) {
                throw new SecurityException("Caller with uid " + mInjector.binderGetCallingUid() +
                        "has no permission to generate keys.");
            }
        } else {
            // Caller provided - check it is the device owner.
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            }
        }
    }

    @VisibleForTesting
    public static int[] translateIdAttestationFlags(
            int idAttestationFlags) {
        Map<Integer, Integer> idTypeToAttestationFlag = new HashMap();
        idTypeToAttestationFlag.put(ID_TYPE_SERIAL, AttestationUtils.ID_TYPE_SERIAL);
        idTypeToAttestationFlag.put(ID_TYPE_IMEI, AttestationUtils.ID_TYPE_IMEI);
        idTypeToAttestationFlag.put(ID_TYPE_MEID, AttestationUtils.ID_TYPE_MEID);

        int numFlagsSet = Integer.bitCount(idAttestationFlags);
        // No flags are set - return null to indicate no device ID attestation information should
        // be included in the attestation record.
        if (numFlagsSet == 0) {
            return null;
        }

        // If the ID_TYPE_BASE_INFO is set, make sure that a non-null array is returned, even if
        // no other flag is set. That will lead to inclusion of general device make data in the
        // attestation record, but no specific device identifiers.
        if ((idAttestationFlags & ID_TYPE_BASE_INFO) != 0) {
            numFlagsSet -= 1;
            idAttestationFlags = idAttestationFlags & (~ID_TYPE_BASE_INFO);
        }

        int[] attestationUtilsFlags = new int[numFlagsSet];
        int i = 0;
        for (Integer idType: idTypeToAttestationFlag.keySet()) {
            if ((idType & idAttestationFlags) != 0) {
                attestationUtilsFlags[i++] = idTypeToAttestationFlag.get(idType);
            }
        }

        return attestationUtilsFlags;
    }

    @Override
    public boolean generateKeyPair(ComponentName who, String callerPackage, String algorithm,
            ParcelableKeyGenParameterSpec parcelableKeySpec,
            int idAttestationFlags,
            KeymasterCertificateChain attestationChain) {
        // Get attestation flags, if any.
        final int[] attestationUtilsFlags = translateIdAttestationFlags(idAttestationFlags);
        final boolean deviceIdAttestationRequired = attestationUtilsFlags != null;
        final int callingUid = mInjector.binderGetCallingUid();

        if (deviceIdAttestationRequired && attestationUtilsFlags.length > 0) {
            enforceIsDeviceOwnerOrCertInstallerOfDeviceOwner(who, callerPackage, callingUid);
        } else {
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_CERT_INSTALL);
        }
        final KeyGenParameterSpec keySpec = parcelableKeySpec.getSpec();
        final String alias = keySpec.getKeystoreAlias();
        if (TextUtils.isEmpty(alias)) {
            throw new IllegalArgumentException("Empty alias provided.");
        }
        // As the caller will be granted access to the key, ensure no UID was specified, as
        // it will not have the desired effect.
        if (keySpec.getUid() != KeyStore.UID_SELF) {
            Log.e(LOG_TAG, "Only the caller can be granted access to the generated keypair.");
            return false;
        }

        if (deviceIdAttestationRequired && (keySpec.getAttestationChallenge() == null)) {
            throw new IllegalArgumentException(
                    "Requested Device ID attestation but challenge is empty.");
        }

        final UserHandle userHandle = mInjector.binderGetCallingUserHandle();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            try (KeyChainConnection keyChainConnection =
                    KeyChain.bindAsUser(mContext, userHandle)) {
                IKeyChainService keyChain = keyChainConnection.getService();

                // Copy the provided keySpec, excluding the attestation challenge, which will be
                // used later for requesting key attestation record.
                final KeyGenParameterSpec noAttestationSpec =
                    new KeyGenParameterSpec.Builder(keySpec)
                        .setAttestationChallenge(null)
                        .build();

                final int generationResult = keyChain.generateKeyPair(algorithm,
                    new ParcelableKeyGenParameterSpec(noAttestationSpec));
                if (generationResult != KeyChain.KEY_GEN_SUCCESS) {
                    Log.e(LOG_TAG, String.format(
                            "KeyChain failed to generate a keypair, error %d.", generationResult));
                    return false;
                }

                // Set a grant for the caller here so that when the client calls
                // requestPrivateKey, it will be able to get the key from Keystore.
                // Note the use of the calling  UID, since the request for the private
                // key will come from the client's process, so the grant has to be for
                // that UID.
                keyChain.setGrant(callingUid, alias, true);

                final byte[] attestationChallenge = keySpec.getAttestationChallenge();
                if (attestationChallenge != null) {
                    final int attestationResult = keyChain.attestKey(
                            alias, attestationChallenge, attestationUtilsFlags, attestationChain);
                    if (attestationResult != KeyChain.KEY_ATTESTATION_SUCCESS) {
                        Log.e(LOG_TAG, String.format(
                                "Attestation for %s failed (rc=%d), deleting key.",
                                alias, attestationResult));
                        keyChain.removeKeyPair(alias);
                        if (attestationResult == KeyChain.KEY_ATTESTATION_CANNOT_ATTEST_IDS) {
                            throw new UnsupportedOperationException(
                                    "Device does not support Device ID attestation.");
                        }
                        return false;
                    }
                }
                return true;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "KeyChain error while generating a keypair", e);
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Interrupted while generating keypair", e);
            Thread.currentThread().interrupt();
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        return false;
    }

    @Override
    public boolean setKeyPairCertificate(ComponentName who, String callerPackage, String alias,
            byte[] cert, byte[] chain, boolean isUserSelectable) {
        enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                DELEGATION_CERT_INSTALL);

        final int callingUid = mInjector.binderGetCallingUid();
        final long id = mInjector.binderClearCallingIdentity();
        try (final KeyChainConnection keyChainConnection =
                KeyChain.bindAsUser(mContext, UserHandle.getUserHandleForUid(callingUid))) {
            IKeyChainService keyChain = keyChainConnection.getService();
            if (!keyChain.setKeyPairCertificate(alias, cert, chain)) {
                return false;
            }
            keyChain.setUserSelectable(alias, isUserSelectable);
            return true;
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Interrupted while setting keypair certificate", e);
            Thread.currentThread().interrupt();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Failed setting keypair certificate", e);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        return false;
    }

    @Override
    public void choosePrivateKeyAlias(final int uid, final Uri uri, final String alias,
            final IBinder response) {
        // Caller UID needs to be trusted, so we restrict this method to SYSTEM_UID callers.
        if (!isCallerWithSystemUid()) {
            return;
        }

        final UserHandle caller = mInjector.binderGetCallingUserHandle();
        // If there is a profile owner, redirect to that; otherwise query the device owner.
        ComponentName aliasChooser = getProfileOwner(caller.getIdentifier());
        if (aliasChooser == null && caller.isSystem()) {
            synchronized (getLockObject()) {
                final ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
                if (deviceOwnerAdmin != null) {
                    aliasChooser = deviceOwnerAdmin.info.getComponent();
                }
            }
        }
        if (aliasChooser == null) {
            sendPrivateKeyAliasResponse(null, response);
            return;
        }

        Intent intent = new Intent(DeviceAdminReceiver.ACTION_CHOOSE_PRIVATE_KEY_ALIAS);
        intent.setComponent(aliasChooser);
        intent.putExtra(DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_SENDER_UID, uid);
        intent.putExtra(DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_URI, uri);
        intent.putExtra(DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_ALIAS, alias);
        intent.putExtra(DeviceAdminReceiver.EXTRA_CHOOSE_PRIVATE_KEY_RESPONSE, response);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);

        final long id = mInjector.binderClearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(intent, caller, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String chosenAlias = getResultData();
                    sendPrivateKeyAliasResponse(chosenAlias, response);
                }
            }, null, Activity.RESULT_OK, null, null);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private void sendPrivateKeyAliasResponse(final String alias, final IBinder responseBinder) {
        final IKeyChainAliasCallback keyChainAliasResponse =
                IKeyChainAliasCallback.Stub.asInterface(responseBinder);
        // Send the response. It's OK to do this from the main thread because IKeyChainAliasCallback
        // is oneway, which means it won't block if the recipient lives in another process.
        try {
            keyChainAliasResponse.alias(alias);
        } catch (Exception e) {
            // Caller could throw RuntimeException or RemoteException back across processes. Catch
            // everything just to be sure.
            Log.e(LOG_TAG, "error while responding to callback", e);
        }
    }

    /**
     * Determine whether DPMS should check if a delegate package is already installed before
     * granting it new delegations via {@link #setDelegatedScopes}.
     */
    private static boolean shouldCheckIfDelegatePackageIsInstalled(String delegatePackage,
            int targetSdk, List<String> scopes) {
        // 1) Never skip is installed check from N.
        if (targetSdk >= Build.VERSION_CODES.N) {
            return true;
        }
        // 2) Skip if DELEGATION_CERT_INSTALL is the only scope being given.
        if (scopes.size() == 1 && scopes.get(0).equals(DELEGATION_CERT_INSTALL)) {
            return false;
        }
        // 3) Skip if all previously granted scopes are being cleared.
        if (scopes.isEmpty()) {
            return false;
        }
        // Otherwise it should check that delegatePackage is installed.
        return true;
    }

    /**
     * Set the scopes of a device owner or profile owner delegate.
     *
     * @param who the device owner or profile owner.
     * @param delegatePackage the name of the delegate package.
     * @param scopes the list of delegation scopes to be given to the delegate package.
     */
    @Override
    public void setDelegatedScopes(ComponentName who, String delegatePackage,
            List<String> scopes) throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(delegatePackage, "Delegate package is null or empty");
        Preconditions.checkCollectionElementsNotNull(scopes, "Scopes");
        // Remove possible duplicates.
        scopes = new ArrayList(new ArraySet(scopes));
        // Ensure given scopes are valid.
        if (scopes.retainAll(Arrays.asList(DELEGATIONS))) {
            throw new IllegalArgumentException("Unexpected delegation scopes");
        }

        // Retrieve the user ID of the calling process.
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            // Ensure calling process is device/profile owner.
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            // Ensure the delegate is installed (skip this for DELEGATION_CERT_INSTALL in pre-N).
            if (shouldCheckIfDelegatePackageIsInstalled(delegatePackage,
                        getTargetSdk(who.getPackageName(), userId), scopes)) {
                // Throw when the delegate package is not installed.
                if (!isPackageInstalledForUser(delegatePackage, userId)) {
                    throw new IllegalArgumentException("Package " + delegatePackage
                            + " is not installed on the current user");
                }
            }

            // Set the new delegate in user policies.
            final DevicePolicyData policy = getUserData(userId);
            if (!scopes.isEmpty()) {
                policy.mDelegationMap.put(delegatePackage, new ArrayList<>(scopes));
            } else {
                // Remove any delegation info if the given scopes list is empty.
                policy.mDelegationMap.remove(delegatePackage);
            }

            // Notify delegate package of updates.
            final Intent intent = new Intent(
                    DevicePolicyManager.ACTION_APPLICATION_DELEGATION_SCOPES_CHANGED);
            // Only call receivers registered with Context#registerReceiver (don’t wake delegate).
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            // Limit components this intent resolves to to the delegate package.
            intent.setPackage(delegatePackage);
            // Include the list of delegated scopes as an extra.
            intent.putStringArrayListExtra(DevicePolicyManager.EXTRA_DELEGATION_SCOPES,
                    (ArrayList<String>) scopes);
            // Send the broadcast.
            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));

            // Persist updates.
            saveSettingsLocked(userId);
        }
    }

    /**
     * Get the delegation scopes given to a delegate package by a device owner or profile owner.
     *
     * A DO/PO can get the scopes of any package. A non DO/PO package can get its own scopes by
     * passing in {@code null} as the {@code who} parameter and its own name as the
     * {@code delegatepackage}.
     *
     * @param who the device owner or profile owner, or {@code null} if the caller is
     *            {@code delegatePackage}.
     * @param delegatePackage the name of the delegate package whose scopes are to be retrieved.
     * @return a list of the delegation scopes currently given to {@code delegatePackage}.
     */
    @Override
    @NonNull
    public List<String> getDelegatedScopes(ComponentName who,
            String delegatePackage) throws SecurityException {
        Preconditions.checkNotNull(delegatePackage, "Delegate package is null");

        // Retrieve the user ID of the calling process.
        final int callingUid = mInjector.binderGetCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        synchronized (getLockObject()) {
            // Ensure calling process is device/profile owner.
            if (who != null) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            // Or ensure calling process is delegatePackage itself.
            } else {
                int uid = 0;
                try {
                  uid = mInjector.getPackageManager()
                          .getPackageUidAsUser(delegatePackage, userId);
                } catch(NameNotFoundException e) {
                }
                if (uid != callingUid) {
                    throw new SecurityException("Caller with uid " + callingUid + " is not "
                            + delegatePackage);
                }
            }
            final DevicePolicyData policy = getUserData(userId);
            // Retrieve the scopes assigned to delegatePackage, or null if no scope was given.
            final List<String> scopes = policy.mDelegationMap.get(delegatePackage);
            return scopes == null ? Collections.EMPTY_LIST : scopes;
        }
    }

    /**
     * Get a list of  packages that were given a specific delegation scopes by a device owner or
     * profile owner.
     *
     * @param who the device owner or profile owner.
     * @param scope the scope whose delegates are to be retrieved.
     * @return a list of the delegate packages currently given the {@code scope} delegation.
     */
    @NonNull
    public List<String> getDelegatePackages(ComponentName who, String scope)
            throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(scope, "Scope is null");
        if (!Arrays.asList(DELEGATIONS).contains(scope)) {
            throw new IllegalArgumentException("Unexpected delegation scope: " + scope);
        }

        // Retrieve the user ID of the calling process.
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            // Ensure calling process is device/profile owner.
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final DevicePolicyData policy = getUserData(userId);

            // Create a list to hold the resulting delegate packages.
            final List<String> delegatePackagesWithScope = new ArrayList<>();
            // Add all delegations containing scope to the result list.
            for (int i = 0; i < policy.mDelegationMap.size(); i++) {
                if (policy.mDelegationMap.valueAt(i).contains(scope)) {
                    delegatePackagesWithScope.add(policy.mDelegationMap.keyAt(i));
                }
            }
            return delegatePackagesWithScope;
        }
    }

    /**
     * Check whether a caller application has been delegated a given scope via
     * {@link #setDelegatedScopes} to access privileged APIs on the behalf of a profile owner or
     * device owner.
     * <p>
     * This is done by checking that {@code callerPackage} was granted {@code scope} delegation and
     * then comparing the calling UID with the UID of {@code callerPackage} as reported by
     * {@link PackageManager#getPackageUidAsUser}.
     *
     * @param callerPackage the name of the package that is trying to invoke a function in the DPMS.
     * @param scope the delegation scope to be checked.
     * @return {@code true} if the calling process is a delegate of {@code scope}.
     */
    private boolean isCallerDelegate(String callerPackage, String scope) {
        Preconditions.checkNotNull(callerPackage, "callerPackage is null");
        if (!Arrays.asList(DELEGATIONS).contains(scope)) {
            throw new IllegalArgumentException("Unexpected delegation scope: " + scope);
        }

        // Retrieve the UID and user ID of the calling process.
        final int callingUid = mInjector.binderGetCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        synchronized (getLockObject()) {
            // Retrieve user policy data.
            final DevicePolicyData policy = getUserData(userId);
            // Retrieve the list of delegation scopes granted to callerPackage.
            final List<String> scopes = policy.mDelegationMap.get(callerPackage);
            // Check callingUid only if callerPackage has the required scope delegation.
            if (scopes != null && scopes.contains(scope)) {
                try {
                    // Retrieve the expected UID for callerPackage.
                    final int uid = mInjector.getPackageManager()
                            .getPackageUidAsUser(callerPackage, userId);
                    // Return true if the caller is actually callerPackage.
                    return uid == callingUid;
                } catch (NameNotFoundException e) {
                    // Ignore.
                }
            }
            return false;
        }
    }

    /**
     * Throw a security exception if a ComponentName is given and it is not a device/profile owner
     * or if the calling process is not a delegate of the given scope.
     *
     * @param who the device owner of profile owner, or null if {@code callerPackage} is a
     *            {@code scope} delegate.
     * @param callerPackage the name of the calling package. Required if {@code who} is
     *            {@code null}.
     * @param reqPolicy the policy used in the API whose access permission is being checked.
     * @param scope the delegation scope corresponding to the API being checked.
     * @throws SecurityException if {@code who} is given and is not an owner for {@code reqPolicy};
     *            or when {@code who} is {@code null} and {@code callerPackage} is not a delegate
     *            of {@code scope}.
     */
    private void enforceCanManageScope(ComponentName who, String callerPackage, int reqPolicy,
            String scope) {
        // If a ComponentName is given ensure it is a device or profile owner according to policy.
        if (who != null) {
            synchronized (getLockObject()) {
                getActiveAdminForCallerLocked(who, reqPolicy);
            }
        // If no ComponentName is given ensure calling process has scope delegation.
        } else if (!isCallerDelegate(callerPackage, scope)) {
            throw new SecurityException("Caller with uid " + mInjector.binderGetCallingUid()
                    + " is not a delegate of scope " + scope + ".");
        }
    }

    /**
     * Helper function to preserve delegation behavior pre-O when using the deprecated functions
     * {@code #setCertInstallerPackage} and {@code #setApplicationRestrictionsManagingPackage}.
     */
    private void setDelegatedScopePreO(ComponentName who,
            String delegatePackage, String scope) {
        Preconditions.checkNotNull(who, "ComponentName is null");

        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            // Ensure calling process is device/profile owner.
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final DevicePolicyData policy = getUserData(userId);

            if (delegatePackage != null) {
                // Set package as a delegate for scope if it is not already one.
                List<String> scopes = policy.mDelegationMap.get(delegatePackage);
                if (scopes == null) {
                    scopes = new ArrayList<>();
                }
                if (!scopes.contains(scope)) {
                    scopes.add(scope);
                    setDelegatedScopes(who, delegatePackage, scopes);
                }
            }

            // Clear any existing scope delegates.
            for (int i = 0; i < policy.mDelegationMap.size(); i++) {
                final String currentPackage = policy.mDelegationMap.keyAt(i);
                final List<String> currentScopes = policy.mDelegationMap.valueAt(i);

                if (!currentPackage.equals(delegatePackage) && currentScopes.contains(scope)) {
                    final List<String> newScopes = new ArrayList(currentScopes);
                    newScopes.remove(scope);
                    setDelegatedScopes(who, currentPackage, newScopes);
                }
            }
        }
    }

    @Override
    public void setCertInstallerPackage(ComponentName who, String installerPackage)
            throws SecurityException {
        setDelegatedScopePreO(who, installerPackage, DELEGATION_CERT_INSTALL);
    }

    @Override
    public String getCertInstallerPackage(ComponentName who) throws SecurityException {
        final List<String> delegatePackages = getDelegatePackages(who, DELEGATION_CERT_INSTALL);
        return delegatePackages.size() > 0 ? delegatePackages.get(0) : null;
    }

    /**
     * @return {@code true} if the package is installed and set as always-on, {@code false} if it is
     * not installed and therefore not available.
     *
     * @throws SecurityException if the caller is not a profile or device owner.
     * @throws UnsupportedOperationException if the package does not support being set as always-on.
     */
    @Override
    public boolean setAlwaysOnVpnPackage(ComponentName admin, String vpnPackage, boolean lockdown,
            List<String> lockdownWhitelist)
            throws SecurityException {
        enforceProfileOrDeviceOwner(admin);

        final int userId = mInjector.userHandleGetCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try {
            if (vpnPackage != null && !isPackageInstalledForUser(vpnPackage, userId)) {
                Slog.w(LOG_TAG, "Non-existent VPN package specified: " + vpnPackage);
                throw new ServiceSpecificException(
                        DevicePolicyManager.ERROR_VPN_PACKAGE_NOT_FOUND, vpnPackage);
            }

            if (vpnPackage != null && lockdown && lockdownWhitelist != null) {
                for (String packageName : lockdownWhitelist) {
                    if (!isPackageInstalledForUser(packageName, userId)) {
                        Slog.w(LOG_TAG, "Non-existent package in VPN whitelist: " + packageName);
                        throw new ServiceSpecificException(
                                DevicePolicyManager.ERROR_VPN_PACKAGE_NOT_FOUND, packageName);
                    }
                }
            }
            // If some package is uninstalled after the check above, it will be ignored by CM.
            if (!mInjector.getConnectivityManager().setAlwaysOnVpnPackageForUser(
                    userId, vpnPackage, lockdown, lockdownWhitelist)) {
                throw new UnsupportedOperationException();
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
        return true;
    }

    @Override
    public String getAlwaysOnVpnPackage(ComponentName admin) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);

        final int userId = mInjector.userHandleGetCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try {
            return mInjector.getConnectivityManager().getAlwaysOnVpnPackageForUser(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isAlwaysOnVpnLockdownEnabled(ComponentName admin) throws SecurityException {
        enforceProfileOrDeviceOwner(admin);

        final int userId = mInjector.userHandleGetCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try {
            return mInjector.getConnectivityManager().isVpnLockdownEnabled(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    @Override
    public List<String> getAlwaysOnVpnLockdownWhitelist(ComponentName admin)
            throws SecurityException {
        enforceProfileOrDeviceOwner(admin);

        final int userId = mInjector.userHandleGetCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try {
            return mInjector.getConnectivityManager().getVpnLockdownWhitelist(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private void forceWipeDeviceNoLock(boolean wipeExtRequested, String reason, boolean wipeEuicc) {
        wtfIfInLock();
        boolean success = false;
        try {
            if (wipeExtRequested) {
                StorageManager sm = (StorageManager) mContext.getSystemService(
                    Context.STORAGE_SERVICE);
                sm.wipeAdoptableDisks();
            }
            mInjector.recoverySystemRebootWipeUserData(
                /*shutdown=*/ false, reason, /*force=*/ true, /*wipeEuicc=*/ wipeEuicc);
            success = true;
        } catch (IOException | SecurityException e) {
            Slog.w(LOG_TAG, "Failed requesting data wipe", e);
        } finally {
            if (!success) SecurityLog.writeEvent(SecurityLog.TAG_WIPE_FAILURE);
        }
    }

    private void forceWipeUser(int userId, String wipeReasonForUser) {
        boolean success = false;
        try {
            IActivityManager am = mInjector.getIActivityManager();
            if (am.getCurrentUser().id == userId) {
                am.switchUser(UserHandle.USER_SYSTEM);
            }

            success = mUserManagerInternal.removeUserEvenWhenDisallowed(userId);
            if (!success) {
                Slog.w(LOG_TAG, "Couldn't remove user " + userId);
            } else if (isManagedProfile(userId)) {
                sendWipeProfileNotification(wipeReasonForUser);
            }
        } catch (RemoteException re) {
            // Shouldn't happen
        } finally {
            if (!success) SecurityLog.writeEvent(SecurityLog.TAG_WIPE_FAILURE);
        }
    }

    @Override
    public void wipeDataWithReason(int flags, String wipeReasonForUser) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkStringNotEmpty(wipeReasonForUser, "wipeReasonForUser is null or empty");
        enforceFullCrossUsersPermission(mInjector.userHandleGetCallingUserId());

        final ActiveAdmin admin;
        synchronized (getLockObject()) {
            admin = getActiveAdminForCallerLocked(null, DeviceAdminInfo.USES_POLICY_WIPE_DATA);
        }
        String internalReason = "DevicePolicyManager.wipeDataWithReason() from "
                + admin.info.getComponent().flattenToShortString();
        wipeDataNoLock(
                admin.info.getComponent(), flags, internalReason, wipeReasonForUser,
                admin.getUserHandle().getIdentifier());
    }

    private void wipeDataNoLock(ComponentName admin, int flags, String internalReason,
                                String wipeReasonForUser, int userId) {
        wtfIfInLock();

        long ident = mInjector.binderClearCallingIdentity();
        try {
            // First check whether the admin is allowed to wipe the device/user/profile.
            final String restriction;
            if (userId == UserHandle.USER_SYSTEM) {
                restriction = UserManager.DISALLOW_FACTORY_RESET;
            } else if (isManagedProfile(userId)) {
                restriction = UserManager.DISALLOW_REMOVE_MANAGED_PROFILE;
            } else {
                restriction = UserManager.DISALLOW_REMOVE_USER;
            }
            if (isAdminAffectedByRestriction(admin, restriction, userId)) {
                throw new SecurityException("Cannot wipe data. " + restriction
                        + " restriction is set for user " + userId);
            }

            if ((flags & WIPE_RESET_PROTECTION_DATA) != 0) {
                if (!isDeviceOwner(admin, userId)) {
                    throw new SecurityException(
                            "Only device owner admins can set WIPE_RESET_PROTECTION_DATA");
                }
                PersistentDataBlockManager manager = (PersistentDataBlockManager)
                        mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
                if (manager != null) {
                    manager.wipe();
                }
            }

            // TODO If split user is enabled and the device owner is set in the primary user
            // (rather than system), we should probably trigger factory reset. Current code just
            // removes that user (but still clears FRP...)
            if (userId == UserHandle.USER_SYSTEM) {
                forceWipeDeviceNoLock(/*wipeExtRequested=*/ (
                        flags & WIPE_EXTERNAL_STORAGE) != 0,
                        internalReason,
                        /*wipeEuicc=*/ (flags & WIPE_EUICC) != 0);
            } else {
                forceWipeUser(userId, wipeReasonForUser);
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void sendWipeProfileNotification(String wipeReasonForUser) {
        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.DEVICE_ADMIN)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setContentTitle(mContext.getString(R.string.work_profile_deleted))
                        .setContentText(wipeReasonForUser)
                        .setColor(mContext.getColor(R.color.system_notification_accent_color))
                        .setStyle(new Notification.BigTextStyle().bigText(wipeReasonForUser))
                        .build();
        mInjector.getNotificationManager().notify(SystemMessage.NOTE_PROFILE_WIPED, notification);
    }

    private void clearWipeProfileNotification() {
        mInjector.getNotificationManager().cancel(SystemMessage.NOTE_PROFILE_WIPED);
    }

    @Override
    public void getRemoveWarning(ComponentName comp, final RemoteCallback result, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(comp, userHandle);
            if (admin == null) {
                result.sendResult(null);
                return;
            }
            Intent intent = new Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLE_REQUESTED);
            intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.setComponent(admin.info.getComponent());
            mContext.sendOrderedBroadcastAsUser(intent, new UserHandle(userHandle),
                    null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    result.sendResult(getResultExtras(false));
                }
            }, null, Activity.RESULT_OK, null, null);
        }
    }

    /**
     * Notify DPMS regarding the metric of the current password. This happens when the user changes
     * the password, but also when the user just unlocks the keyguard. In comparison,
     * reportPasswordChanged() is only called when the user changes the password.
     */
    @Override
    public void setActivePasswordState(PasswordMetrics metrics, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        // If the managed profile doesn't have a separate password, set the metrics to default
        if (isManagedProfile(userHandle) && !isSeparateProfileChallengeEnabled(userHandle)) {
            metrics = new PasswordMetrics();
        }

        validateQualityConstant(metrics.quality);
        synchronized (getLockObject()) {
            mUserPasswordMetrics.put(userHandle, metrics);
        }
    }

    @Override
    public void reportPasswordChanged(@UserIdInt int userId) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userId);

        // Managed Profile password can only be changed when it has a separate challenge.
        if (!isSeparateProfileChallengeEnabled(userId)) {
            enforceNotManagedProfile(userId, "set the active password");
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        DevicePolicyData policy = getUserData(userId);

        long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                policy.mFailedPasswordAttempts = 0;
                updatePasswordValidityCheckpointLocked(userId, /* parent */ false);
                saveSettingsLocked(userId);
                updatePasswordExpirationsLocked(userId);
                setExpirationAlarmCheckLocked(mContext, userId, /* parent */ false);

                // Send a broadcast to each profile using this password as its primary unlock.
                sendAdminCommandForLockscreenPoliciesLocked(
                        DeviceAdminReceiver.ACTION_PASSWORD_CHANGED,
                        DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, userId);
            }
            removeCaApprovalsIfNeeded(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    /**
     * Called any time the device password is updated. Resets all password expiration clocks.
     */
    private void updatePasswordExpirationsLocked(int userHandle) {
        ArraySet<Integer> affectedUserIds = new ArraySet<Integer>();
        List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(
                userHandle, /* parent */ false);
        final int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin = admins.get(i);
            if (admin.info.usesPolicy(DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD)) {
                affectedUserIds.add(admin.getUserHandle().getIdentifier());
                long timeout = admin.passwordExpirationTimeout;
                long expiration = timeout > 0L ? (timeout + System.currentTimeMillis()) : 0L;
                admin.passwordExpirationDate = expiration;
            }
        }
        for (int affectedUserId : affectedUserIds) {
            saveSettingsLocked(affectedUserId);
        }
    }

    @Override
    public void reportFailedPasswordAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        if (!isSeparateProfileChallengeEnabled(userHandle)) {
            enforceNotManagedProfile(userHandle,
                    "report failed password attempt if separate profile challenge is not in place");
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        boolean wipeData = false;
        ActiveAdmin strictestAdmin = null;
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                DevicePolicyData policy = getUserData(userHandle);
                policy.mFailedPasswordAttempts++;
                saveSettingsLocked(userHandle);
                if (mHasFeature) {
                    strictestAdmin = getAdminWithMinimumFailedPasswordsForWipeLocked(
                            userHandle, /* parent */ false);
                    int max = strictestAdmin != null
                            ? strictestAdmin.maximumFailedPasswordsForWipe : 0;
                    if (max > 0 && policy.mFailedPasswordAttempts >= max) {
                        wipeData = true;
                    }

                    sendAdminCommandForLockscreenPoliciesLocked(
                            DeviceAdminReceiver.ACTION_PASSWORD_FAILED,
                            DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, userHandle);
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }

        if (wipeData && strictestAdmin != null) {
            final int userId = strictestAdmin.getUserHandle().getIdentifier();
            Slog.i(LOG_TAG, "Max failed password attempts policy reached for admin: "
                    + strictestAdmin.info.getComponent().flattenToShortString()
                    + ". Calling wipeData for user " + userId);

            // Attempt to wipe the device/user/profile associated with the admin, as if the
            // admin had called wipeData(). That way we can check whether the admin is actually
            // allowed to wipe the device (e.g. a regular device admin shouldn't be able to wipe the
            // device if the device owner has set DISALLOW_FACTORY_RESET, but the DO should be
            // able to do so).
            // IMPORTANT: Call without holding the lock to prevent deadlock.
            try {
                String wipeReasonForUser = mContext.getString(
                        R.string.work_profile_deleted_reason_maximum_password_failure);
                wipeDataNoLock(strictestAdmin.info.getComponent(),
                        /*flags=*/ 0,
                        /*reason=*/ "reportFailedPasswordAttempt()",
                        wipeReasonForUser,
                        userId);
            } catch (SecurityException e) {
                Slog.w(LOG_TAG, "Failed to wipe user " + userId
                        + " after max failed password attempts reached.", e);
            }
        }

        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT,
                    /*result*/ 0, /*method strength*/ 1);
        }
    }

    @Override
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mFailedPasswordAttempts != 0 || policy.mPasswordOwner >= 0) {
                long ident = mInjector.binderClearCallingIdentity();
                try {
                    policy.mFailedPasswordAttempts = 0;
                    policy.mPasswordOwner = -1;
                    saveSettingsLocked(userHandle);
                    if (mHasFeature) {
                        sendAdminCommandForLockscreenPoliciesLocked(
                                DeviceAdminReceiver.ACTION_PASSWORD_SUCCEEDED,
                                DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, userHandle);
                    }
                } finally {
                    mInjector.binderRestoreCallingIdentity(ident);
                }
            }
        }

        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT, /*result*/ 1,
                    /*method strength*/ 1);
        }
    }

    @Override
    public void reportFailedFingerprintAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT, /*result*/ 0,
                    /*method strength*/ 0);
        }
    }

    @Override
    public void reportSuccessfulFingerprintAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT, /*result*/ 1,
                    /*method strength*/ 0);
        }
    }

    @Override
    public void reportKeyguardDismissed(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISMISSED);
        }
    }

    @Override
    public void reportKeyguardSecured(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_SECURED);
        }
    }

    @Override
    public ComponentName setGlobalProxy(ComponentName who, String proxySpec,
            String exclusionList) {
        if (!mHasFeature) {
            return null;
        }
        synchronized (getLockObject()) {
            Preconditions.checkNotNull(who, "ComponentName is null");

            // Only check if system user has set global proxy. We don't allow other users to set it.
            DevicePolicyData policy = getUserData(UserHandle.USER_SYSTEM);
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_SETS_GLOBAL_PROXY);

            // Scan through active admins and find if anyone has already
            // set the global proxy.
            Set<ComponentName> compSet = policy.mAdminMap.keySet();
            for (ComponentName component : compSet) {
                ActiveAdmin ap = policy.mAdminMap.get(component);
                if ((ap.specifiesGlobalProxy) && (!component.equals(who))) {
                    // Another admin already sets the global proxy
                    // Return it to the caller.
                    return component;
                }
            }

            // If the user is not system, don't set the global proxy. Fail silently.
            if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
                Slog.w(LOG_TAG, "Only the owner is allowed to set the global proxy. User "
                        + UserHandle.getCallingUserId() + " is not permitted.");
                return null;
            }
            if (proxySpec == null) {
                admin.specifiesGlobalProxy = false;
                admin.globalProxySpec = null;
                admin.globalProxyExclusionList = null;
            } else {

                admin.specifiesGlobalProxy = true;
                admin.globalProxySpec = proxySpec;
                admin.globalProxyExclusionList = exclusionList;
            }

            // Reset the global proxy accordingly
            // Do this using system permissions, as apps cannot write to secure settings
            long origId = mInjector.binderClearCallingIdentity();
            try {
                resetGlobalProxyLocked(policy);
            } finally {
                mInjector.binderRestoreCallingIdentity(origId);
            }
            return null;
        }
    }

    @Override
    public ComponentName getGlobalProxyAdmin(int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(UserHandle.USER_SYSTEM);
            // Scan through active admins and find if anyone has already
            // set the global proxy.
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin ap = policy.mAdminList.get(i);
                if (ap.specifiesGlobalProxy) {
                    // Device admin sets the global proxy
                    // Return it to the caller.
                    return ap.info.getComponent();
                }
            }
        }
        // No device admin sets the global proxy.
        return null;
    }

    @Override
    public void setRecommendedGlobalProxy(ComponentName who, ProxyInfo proxyInfo) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        long token = mInjector.binderClearCallingIdentity();
        try {
            mInjector.getConnectivityManager().setGlobalProxy(proxyInfo);
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private void resetGlobalProxyLocked(DevicePolicyData policy) {
        final int N = policy.mAdminList.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin ap = policy.mAdminList.get(i);
            if (ap.specifiesGlobalProxy) {
                saveGlobalProxyLocked(ap.globalProxySpec, ap.globalProxyExclusionList);
                return;
            }
        }
        // No device admins defining global proxies - reset global proxy settings to none
        saveGlobalProxyLocked(null, null);
    }

    private void saveGlobalProxyLocked(String proxySpec, String exclusionList) {
        if (exclusionList == null) {
            exclusionList = "";
        }
        if (proxySpec == null) {
            proxySpec = "";
        }
        // Remove white spaces
        proxySpec = proxySpec.trim();
        String data[] = proxySpec.split(":");
        int proxyPort = 8080;
        if (data.length > 1) {
            try {
                proxyPort = Integer.parseInt(data[1]);
            } catch (NumberFormatException e) {}
        }
        exclusionList = exclusionList.trim();

        ProxyInfo proxyProperties = new ProxyInfo(data[0], proxyPort, exclusionList);
        if (!proxyProperties.isValid()) {
            Slog.e(LOG_TAG, "Invalid proxy properties, ignoring: " + proxyProperties.toString());
            return;
        }
        mInjector.settingsGlobalPutString(Settings.Global.GLOBAL_HTTP_PROXY_HOST, data[0]);
        mInjector.settingsGlobalPutInt(Settings.Global.GLOBAL_HTTP_PROXY_PORT, proxyPort);
        mInjector.settingsGlobalPutString(Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                exclusionList);
    }

    /**
     * Called by an application that is administering the device to request that the storage system
     * be encrypted. Does nothing if the caller is on a secondary user or a managed profile.
     *
     * @return the new total request status (for all admins), or {@link
     *         DevicePolicyManager#ENCRYPTION_STATUS_UNSUPPORTED} if called for a non-system user
     */
    @Override
    public int setStorageEncryption(ComponentName who, boolean encrypt) {
        if (!mHasFeature) {
            return DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Check for permissions
            // Only system user can set storage encryption
            if (userHandle != UserHandle.USER_SYSTEM) {
                Slog.w(LOG_TAG, "Only owner/system user is allowed to set storage encryption. User "
                        + UserHandle.getCallingUserId() + " is not permitted.");
                return DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
            }

            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_ENCRYPTED_STORAGE);

            // Quick exit:  If the filesystem does not support encryption, we can exit early.
            if (!isEncryptionSupported()) {
                return DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
            }

            // (1) Record the value for the admin so it's sticky
            if (ap.encryptionRequested != encrypt) {
                ap.encryptionRequested = encrypt;
                saveSettingsLocked(userHandle);
            }

            DevicePolicyData policy = getUserData(UserHandle.USER_SYSTEM);
            // (2) Compute "max" for all admins
            boolean newRequested = false;
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                newRequested |= policy.mAdminList.get(i).encryptionRequested;
            }

            // Notify OS of new request
            setEncryptionRequested(newRequested);

            // Return the new global request status
            return newRequested
                    ? DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE
                    : DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE;
        }
    }

    /**
     * Get the current storage encryption request status for a given admin, or aggregate of all
     * active admins.
     */
    @Override
    public boolean getStorageEncryption(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (getLockObject()) {
            // Check for permissions if a particular caller is specified
            if (who != null) {
                // When checking for a single caller, status is based on caller's request
                ActiveAdmin ap = getActiveAdminUncheckedLocked(who, userHandle);
                return ap != null ? ap.encryptionRequested : false;
            }

            // If no particular caller is specified, return the aggregate set of requests.
            // This is short circuited by returning true on the first hit.
            DevicePolicyData policy = getUserData(userHandle);
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                if (policy.mAdminList.get(i).encryptionRequested) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Get the current encryption status of the device.
     */
    @Override
    public int getStorageEncryptionStatus(@Nullable String callerPackage, int userHandle) {
        if (!mHasFeature) {
            // Ok to return current status.
        }
        enforceFullCrossUsersPermission(userHandle);

        // It's not critical here, but let's make sure the package name is correct, in case
        // we start using it for different purposes.
        ensureCallerPackage(callerPackage);

        final ApplicationInfo ai;
        try {
            ai = mIPackageManager.getApplicationInfo(callerPackage, 0, userHandle);
        } catch (RemoteException e) {
            throw new SecurityException(e);
        }

        boolean legacyApp = false;
        if (ai.targetSdkVersion <= Build.VERSION_CODES.M) {
            legacyApp = true;
        }

        final int rawStatus = getEncryptionStatus();
        if ((rawStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER) && legacyApp) {
            return DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE;
        }
        return rawStatus;
    }

    /**
     * Hook to low-levels:  This should report if the filesystem supports encrypted storage.
     */
    private boolean isEncryptionSupported() {
        // Note, this can be implemented as
        //   return getEncryptionStatus() != DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
        // But is provided as a separate internal method if there's a faster way to do a
        // simple check for supported-or-not.
        return getEncryptionStatus() != DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
    }

    /**
     * Hook to low-levels:  Reporting the current status of encryption.
     * @return A value such as {@link DevicePolicyManager#ENCRYPTION_STATUS_UNSUPPORTED},
     * {@link DevicePolicyManager#ENCRYPTION_STATUS_INACTIVE},
     * {@link DevicePolicyManager#ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY},
     * {@link DevicePolicyManager#ENCRYPTION_STATUS_ACTIVE_PER_USER}, or
     * {@link DevicePolicyManager#ENCRYPTION_STATUS_ACTIVE}.
     */
    private int getEncryptionStatus() {
        if (mInjector.storageManagerIsFileBasedEncryptionEnabled()) {
            return DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER;
        } else if (mInjector.storageManagerIsNonDefaultBlockEncrypted()) {
            return DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE;
        } else if (mInjector.storageManagerIsEncrypted()) {
            return DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY;
        } else if (mInjector.storageManagerIsEncryptable()) {
            return DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE;
        } else {
            return DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
        }
    }

    /**
     * Hook to low-levels:  If needed, record the new admin setting for encryption.
     */
    private void setEncryptionRequested(boolean encrypt) {
    }

    /**
     * Set whether the screen capture is disabled for the user managed by the specified admin.
     */
    @Override
    public void setScreenCaptureDisabled(ComponentName who, boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (ap.disableScreenCapture != disabled) {
                ap.disableScreenCapture = disabled;
                saveSettingsLocked(userHandle);
                updateScreenCaptureDisabled(userHandle, disabled);
            }
        }
    }

    /**
     * Returns whether or not screen capture is disabled for a given admin, or disabled for any
     * active admin (if given admin is null).
     */
    @Override
    public boolean getScreenCaptureDisabled(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return (admin != null) ? admin.disableScreenCapture : false;
            }

            DevicePolicyData policy = getUserData(userHandle);
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = policy.mAdminList.get(i);
                if (admin.disableScreenCapture) {
                    return true;
                }
            }
            return false;
        }
    }

    private void updateScreenCaptureDisabled(int userHandle, boolean disabled) {
        mPolicyCache.setScreenCaptureDisabled(userHandle, disabled);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mInjector.getIWindowManager().refreshScreenCaptureDisabled(userHandle);
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, "Unable to notify WindowManager.", e);
                }
            }
        });
    }

    /**
     * Set whether auto time is required by the specified admin (must be device or profile owner).
     */
    @Override
    public void setAutoTimeRequired(ComponentName who, boolean required) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (admin.requireAutoTime != required) {
                admin.requireAutoTime = required;
                saveSettingsLocked(userHandle);
            }
        }

        // Turn AUTO_TIME on in settings if it is required
        if (required) {
            long ident = mInjector.binderClearCallingIdentity();
            try {
                mInjector.settingsGlobalPutInt(Settings.Global.AUTO_TIME, 1 /* AUTO_TIME on */);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Returns whether or not auto time is required by the device owner or any profile owner.
     */
    @Override
    public boolean getAutoTimeRequired() {
        if (!mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            if (deviceOwner != null && deviceOwner.requireAutoTime) {
                // If the device owner enforces auto time, we don't need to check the PO's
                return true;
            }

            // Now check to see if any profile owner on any user enforces auto time
            for (Integer userId : mOwners.getProfileOwnerKeys()) {
                ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                if (profileOwner != null && profileOwner.requireAutoTime) {
                    return true;
                }
            }

            return false;
        }
    }

    @Override
    public void setForceEphemeralUsers(ComponentName who, boolean forceEphemeralUsers) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        // Allow setting this policy to true only if there is a split system user.
        if (forceEphemeralUsers && !mInjector.userManagerIsSplitSystemUser()) {
            throw new UnsupportedOperationException(
                    "Cannot force ephemeral users on systems without split system user.");
        }
        boolean removeAllUsers = false;
        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            if (deviceOwner.forceEphemeralUsers != forceEphemeralUsers) {
                deviceOwner.forceEphemeralUsers = forceEphemeralUsers;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
                mUserManagerInternal.setForceEphemeralUsers(forceEphemeralUsers);
                removeAllUsers = forceEphemeralUsers;
            }
        }
        if (removeAllUsers) {
            long identitity = mInjector.binderClearCallingIdentity();
            try {
                mUserManagerInternal.removeAllUsers();
            } finally {
                mInjector.binderRestoreCallingIdentity(identitity);
            }
        }
    }

    @Override
    public boolean getForceEphemeralUsers(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            return deviceOwner.forceEphemeralUsers;
        }
    }

    private void ensureDeviceOwnerAndAllUsersAffiliated(ComponentName who) throws SecurityException {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            if (!areAllUsersAffiliatedWithDeviceLocked()) {
                throw new SecurityException("Not all users are affiliated.");
            }
        }
    }

    @Override
    public boolean requestBugreport(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        // TODO: If an unaffiliated user is removed, the admin will be able to request a bugreport
        // which could still contain data related to that user. Should we disallow that, e.g. until
        // next boot? Might not be needed given that this still requires user consent.
        ensureDeviceOwnerAndAllUsersAffiliated(who);

        if (mRemoteBugreportServiceIsActive.get()
                || (getDeviceOwnerRemoteBugreportUri() != null)) {
            Slog.d(LOG_TAG, "Remote bugreport wasn't started because there's already one running.");
            return false;
        }

        final long currentTime = System.currentTimeMillis();
        synchronized (getLockObject()) {
            DevicePolicyData policyData = getUserData(UserHandle.USER_SYSTEM);
            if (currentTime > policyData.mLastBugReportRequestTime) {
                policyData.mLastBugReportRequestTime = currentTime;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
        }

        final long callingIdentity = mInjector.binderClearCallingIdentity();
        try {
            mInjector.getIActivityManager().requestBugReport(
                    ActivityManager.BUGREPORT_OPTION_REMOTE);

            mRemoteBugreportServiceIsActive.set(true);
            mRemoteBugreportSharingAccepted.set(false);
            registerRemoteBugreportReceivers();
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG,
                    RemoteBugreportUtils.NOTIFICATION_ID,
                    RemoteBugreportUtils.buildNotification(mContext,
                            DevicePolicyManager.NOTIFICATION_BUGREPORT_STARTED), UserHandle.ALL);
            mHandler.postDelayed(mRemoteBugreportTimeoutRunnable,
                    RemoteBugreportUtils.REMOTE_BUGREPORT_TIMEOUT_MILLIS);
            return true;
        } catch (RemoteException re) {
            // should never happen
            Slog.e(LOG_TAG, "Failed to make remote calls to start bugreportremote service", re);
            return false;
        } finally {
            mInjector.binderRestoreCallingIdentity(callingIdentity);
        }
    }

    void sendDeviceOwnerCommand(String action, Bundle extras) {
        int deviceOwnerUserId;
        ComponentName deviceOwnerComponent;
        synchronized (getLockObject()) {
            deviceOwnerUserId = mOwners.getDeviceOwnerUserId();
            deviceOwnerComponent = mOwners.getDeviceOwnerComponent();
        }
        sendActiveAdminCommand(action, extras, deviceOwnerUserId,
                deviceOwnerComponent);
    }

    private void sendProfileOwnerCommand(String action, Bundle extras, int userHandle) {
        sendActiveAdminCommand(action, extras, userHandle,
                mOwners.getProfileOwnerComponent(userHandle));
    }

    private void sendActiveAdminCommand(String action, Bundle extras,
            int userHandle, ComponentName receiverComponent) {
        final Intent intent = new Intent(action);
        intent.setComponent(receiverComponent);
        if (extras != null) {
            intent.putExtras(extras);
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userHandle));
    }

    private void sendOwnerChangedBroadcast(String broadcast, int userId) {
        final Intent intent = new Intent(broadcast)
                .addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
    }

    private String getDeviceOwnerRemoteBugreportUri() {
        synchronized (getLockObject()) {
            return mOwners.getDeviceOwnerRemoteBugreportUri();
        }
    }

    private void setDeviceOwnerRemoteBugreportUriAndHash(String bugreportUri,
            String bugreportHash) {
        synchronized (getLockObject()) {
            mOwners.setDeviceOwnerRemoteBugreportUriAndHash(bugreportUri, bugreportHash);
        }
    }

    private void registerRemoteBugreportReceivers() {
        try {
            IntentFilter filterFinished = new IntentFilter(
                    DevicePolicyManager.ACTION_REMOTE_BUGREPORT_DISPATCH,
                    RemoteBugreportUtils.BUGREPORT_MIMETYPE);
            mContext.registerReceiver(mRemoteBugreportFinishedReceiver, filterFinished);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            // should never happen, as setting a constant
            Slog.w(LOG_TAG, "Failed to set type " + RemoteBugreportUtils.BUGREPORT_MIMETYPE, e);
        }
        IntentFilter filterConsent = new IntentFilter();
        filterConsent.addAction(DevicePolicyManager.ACTION_BUGREPORT_SHARING_DECLINED);
        filterConsent.addAction(DevicePolicyManager.ACTION_BUGREPORT_SHARING_ACCEPTED);
        mContext.registerReceiver(mRemoteBugreportConsentReceiver, filterConsent);
    }

    private void onBugreportFinished(Intent intent) {
        mHandler.removeCallbacks(mRemoteBugreportTimeoutRunnable);
        mRemoteBugreportServiceIsActive.set(false);
        Uri bugreportUri = intent.getData();
        String bugreportUriString = null;
        if (bugreportUri != null) {
            bugreportUriString = bugreportUri.toString();
        }
        String bugreportHash = intent.getStringExtra(
                DevicePolicyManager.EXTRA_REMOTE_BUGREPORT_HASH);
        if (mRemoteBugreportSharingAccepted.get()) {
            shareBugreportWithDeviceOwnerIfExists(bugreportUriString, bugreportHash);
            mInjector.getNotificationManager().cancel(LOG_TAG,
                    RemoteBugreportUtils.NOTIFICATION_ID);
        } else {
            setDeviceOwnerRemoteBugreportUriAndHash(bugreportUriString, bugreportHash);
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG, RemoteBugreportUtils.NOTIFICATION_ID,
                    RemoteBugreportUtils.buildNotification(mContext,
                            DevicePolicyManager.NOTIFICATION_BUGREPORT_FINISHED_NOT_ACCEPTED),
                            UserHandle.ALL);
        }
        mContext.unregisterReceiver(mRemoteBugreportFinishedReceiver);
    }

    private void onBugreportFailed() {
        mRemoteBugreportServiceIsActive.set(false);
        mInjector.systemPropertiesSet(RemoteBugreportUtils.CTL_STOP,
                RemoteBugreportUtils.REMOTE_BUGREPORT_SERVICE);
        mRemoteBugreportSharingAccepted.set(false);
        setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        mInjector.getNotificationManager().cancel(LOG_TAG, RemoteBugreportUtils.NOTIFICATION_ID);
        Bundle extras = new Bundle();
        extras.putInt(DeviceAdminReceiver.EXTRA_BUGREPORT_FAILURE_REASON,
                DeviceAdminReceiver.BUGREPORT_FAILURE_FAILED_COMPLETING);
        sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_BUGREPORT_FAILED, extras);
        mContext.unregisterReceiver(mRemoteBugreportConsentReceiver);
        mContext.unregisterReceiver(mRemoteBugreportFinishedReceiver);
    }

    private void onBugreportSharingAccepted() {
        mRemoteBugreportSharingAccepted.set(true);
        String bugreportUriString = null;
        String bugreportHash = null;
        synchronized (getLockObject()) {
            bugreportUriString = getDeviceOwnerRemoteBugreportUri();
            bugreportHash = mOwners.getDeviceOwnerRemoteBugreportHash();
        }
        if (bugreportUriString != null) {
            shareBugreportWithDeviceOwnerIfExists(bugreportUriString, bugreportHash);
        } else if (mRemoteBugreportServiceIsActive.get()) {
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG, RemoteBugreportUtils.NOTIFICATION_ID,
                    RemoteBugreportUtils.buildNotification(mContext,
                            DevicePolicyManager.NOTIFICATION_BUGREPORT_ACCEPTED_NOT_FINISHED),
                            UserHandle.ALL);
        }
    }

    private void onBugreportSharingDeclined() {
        if (mRemoteBugreportServiceIsActive.get()) {
            mInjector.systemPropertiesSet(RemoteBugreportUtils.CTL_STOP,
                    RemoteBugreportUtils.REMOTE_BUGREPORT_SERVICE);
            mRemoteBugreportServiceIsActive.set(false);
            mHandler.removeCallbacks(mRemoteBugreportTimeoutRunnable);
            mContext.unregisterReceiver(mRemoteBugreportFinishedReceiver);
        }
        mRemoteBugreportSharingAccepted.set(false);
        setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_BUGREPORT_SHARING_DECLINED, null);
    }

    private void shareBugreportWithDeviceOwnerIfExists(String bugreportUriString,
            String bugreportHash) {
        ParcelFileDescriptor pfd = null;
        try {
            if (bugreportUriString == null) {
                throw new FileNotFoundException();
            }
            Uri bugreportUri = Uri.parse(bugreportUriString);
            pfd = mContext.getContentResolver().openFileDescriptor(bugreportUri, "r");

            synchronized (getLockObject()) {
                Intent intent = new Intent(DeviceAdminReceiver.ACTION_BUGREPORT_SHARE);
                intent.setComponent(mOwners.getDeviceOwnerComponent());
                intent.setDataAndType(bugreportUri, RemoteBugreportUtils.BUGREPORT_MIMETYPE);
                intent.putExtra(DeviceAdminReceiver.EXTRA_BUGREPORT_HASH, bugreportHash);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                LocalServices.getService(ActivityManagerInternal.class)
                        .grantUriPermissionFromIntent(Process.SHELL_UID,
                                mOwners.getDeviceOwnerComponent().getPackageName(),
                                intent, mOwners.getDeviceOwnerUserId());
                mContext.sendBroadcastAsUser(intent, UserHandle.of(mOwners.getDeviceOwnerUserId()));
            }
        } catch (FileNotFoundException e) {
            Bundle extras = new Bundle();
            extras.putInt(DeviceAdminReceiver.EXTRA_BUGREPORT_FAILURE_REASON,
                    DeviceAdminReceiver.BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE);
            sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_BUGREPORT_FAILED, extras);
        } finally {
            try {
                if (pfd != null) {
                    pfd.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            mRemoteBugreportSharingAccepted.set(false);
            setDeviceOwnerRemoteBugreportUriAndHash(null, null);
        }
    }

    /**
     * Disables all device cameras according to the specified admin.
     */
    @Override
    public void setCameraDisabled(ComponentName who, boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_DISABLE_CAMERA);
            if (ap.disableCamera != disabled) {
                ap.disableCamera = disabled;
                saveSettingsLocked(userHandle);
            }
        }
        // Tell the user manager that the restrictions have changed.
        pushUserRestrictions(userHandle);
    }

    /**
     * Gets whether or not all device cameras are disabled for a given admin, or disabled for any
     * active admins.
     */
    @Override
    public boolean getCameraDisabled(ComponentName who, int userHandle) {
        return getCameraDisabled(who, userHandle, /* mergeDeviceOwnerRestriction= */ true);
    }

    private boolean getCameraDisabled(ComponentName who, int userHandle,
            boolean mergeDeviceOwnerRestriction) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return (admin != null) ? admin.disableCamera : false;
            }
            // First, see if DO has set it.  If so, it's device-wide.
            if (mergeDeviceOwnerRestriction) {
                final ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner != null && deviceOwner.disableCamera) {
                    return true;
                }
            }

            // Then check each device admin on the user.
            DevicePolicyData policy = getUserData(userHandle);
            // Determine whether or not the device camera is disabled for any active admins.
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = policy.mAdminList.get(i);
                if (admin.disableCamera) {
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public void setKeyguardDisabledFeatures(ComponentName who, int which, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        if (isManagedProfile(userHandle)) {
            if (parent) {
                which = which & PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER;
            } else {
                which = which & PROFILE_KEYGUARD_FEATURES;
            }
        }
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES, parent);
            if (ap.disabledKeyguardFeatures != which) {
                ap.disabledKeyguardFeatures = which;
                saveSettingsLocked(userHandle);
            }
        }
        if (SecurityLog.isLoggingEnabled()) {
            final int affectedUserId = parent ? getProfileParentId(userHandle) : userHandle;
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISABLED_FEATURES_SET,
                    who.getPackageName(), userHandle, affectedUserId, which);
        }
    }

    /**
     * Gets the disabled state for features in keyguard for the given admin,
     * or the aggregate of all active admins if who is null.
     */
    @Override
    public int getKeyguardDisabledFeatures(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                if (who != null) {
                    ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                    return (admin != null) ? admin.disabledKeyguardFeatures : 0;
                }

                final List<ActiveAdmin> admins;
                if (!parent && isManagedProfile(userHandle)) {
                    // If we are being asked about a managed profile, just return keyguard features
                    // disabled by admins in the profile.
                    admins = getUserDataUnchecked(userHandle).mAdminList;
                } else {
                    // Otherwise return those set by admins in the user and its profiles.
                    admins = getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
                }

                int which = DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE;
                final int N = admins.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin = admins.get(i);
                    int userId = admin.getUserHandle().getIdentifier();
                    boolean isRequestedUser = !parent && (userId == userHandle);
                    if (isRequestedUser || !isManagedProfile(userId)) {
                        // If we are being asked explicitly about this user
                        // return all disabled features even if its a managed profile.
                        which |= admin.disabledKeyguardFeatures;
                    } else {
                        // Otherwise a managed profile is only allowed to disable
                        // some features on the parent user.
                        which |= (admin.disabledKeyguardFeatures
                                & PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER);
                    }
                }
                return which;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public void setKeepUninstalledPackages(ComponentName who, String callerPackage,
            List<String> packageList) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(packageList, "packageList is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO or a keep uninstalled packages delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER,
                    DELEGATION_KEEP_UNINSTALLED_PACKAGES);
            // Get the device owner
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            // Set list of packages to be kept even if uninstalled.
            deviceOwner.keepUninstalledPackages = packageList;
            // Save settings.
            saveSettingsLocked(userHandle);
            // Notify package manager.
            mInjector.getPackageManagerInternal().setKeepUninstalledPackages(packageList);
        }
    }

    @Override
    public List<String> getKeepUninstalledPackages(ComponentName who, String callerPackage) {
        if (!mHasFeature) {
            return null;
        }
        // TODO In split system user mode, allow apps on user 0 to query the list
        synchronized (getLockObject()) {
            // Ensure the caller is a DO or a keep uninstalled packages delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER,
                    DELEGATION_KEEP_UNINSTALLED_PACKAGES);
            return getKeepUninstalledPackagesLocked();
        }
    }

    private List<String> getKeepUninstalledPackagesLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        return (deviceOwner != null) ? deviceOwner.keepUninstalledPackages : null;
    }

    @Override
    public boolean setDeviceOwner(ComponentName admin, String ownerName, int userId) {
        if (!mHasFeature) {
            return false;
        }
        if (admin == null
                || !isPackageInstalledForUser(admin.getPackageName(), userId)) {
            throw new IllegalArgumentException("Invalid component " + admin
                    + " for device owner");
        }
        final boolean hasIncompatibleAccountsOrNonAdb =
                hasIncompatibleAccountsOrNonAdbNoLock(userId, admin);
        synchronized (getLockObject()) {
            enforceCanSetDeviceOwnerLocked(admin, userId, hasIncompatibleAccountsOrNonAdb);
            final ActiveAdmin activeAdmin = getActiveAdminUncheckedLocked(admin, userId);
            if (activeAdmin == null
                    || getUserData(userId).mRemovingAdmins.contains(admin)) {
                throw new IllegalArgumentException("Not active admin: " + admin);
            }

            // Shutting down backup manager service permanently.
            long ident = mInjector.binderClearCallingIdentity();
            try {
                if (mInjector.getIBackupManager() != null) {
                    mInjector.getIBackupManager()
                            .setBackupServiceActive(UserHandle.USER_SYSTEM, false);
                }
            } catch (RemoteException e) {
                throw new IllegalStateException("Failed deactivating backup service.", e);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }

            if (isAdb()) {
                // Log device owner provisioning was started using adb.
                MetricsLogger.action(mContext, PROVISIONING_ENTRY_POINT_ADB, LOG_TAG_DEVICE_OWNER);
            }

            mOwners.setDeviceOwner(admin, ownerName, userId);
            mOwners.writeDeviceOwner();
            updateDeviceOwnerLocked();
            setDeviceOwnerSystemPropertyLocked();

            final Set<String> restrictions =
                    UserRestrictionsUtils.getDefaultEnabledForDeviceOwner();
            if (!restrictions.isEmpty()) {
                for (String restriction : restrictions) {
                    activeAdmin.ensureUserRestrictions().putBoolean(restriction, true);
                }
                activeAdmin.defaultEnabledRestrictionsAlreadySet.addAll(restrictions);
                Slog.i(LOG_TAG, "Enabled the following restrictions by default: " + restrictions);

                saveUserRestrictionsLocked(userId);
            }

            ident = mInjector.binderClearCallingIdentity();
            try {
                // TODO Send to system too?
                sendOwnerChangedBroadcast(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED, userId);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
            mDeviceAdminServiceController.startServiceForOwner(
                    admin.getPackageName(), userId, "set-device-owner");

            Slog.i(LOG_TAG, "Device owner set: " + admin + " on user " + userId);
            return true;
        }
    }

    @Override
    public boolean hasDeviceOwner() {
        enforceDeviceOwnerOrManageUsers();
        return mOwners.hasDeviceOwner();
    }

    boolean isDeviceOwner(ActiveAdmin admin) {
        return isDeviceOwner(admin.info.getComponent(), admin.getUserHandle().getIdentifier());
    }

    public boolean isDeviceOwner(ComponentName who, int userId) {
        synchronized (getLockObject()) {
            return mOwners.hasDeviceOwner()
                    && mOwners.getDeviceOwnerUserId() == userId
                    && mOwners.getDeviceOwnerComponent().equals(who);
        }
    }

    private boolean isDeviceOwnerPackage(String packageName, int userId) {
        synchronized (getLockObject()) {
            return mOwners.hasDeviceOwner()
                    && mOwners.getDeviceOwnerUserId() == userId
                    && mOwners.getDeviceOwnerPackageName().equals(packageName);
        }
    }

    private boolean isProfileOwnerPackage(String packageName, int userId) {
        synchronized (getLockObject()) {
            return mOwners.hasProfileOwner(userId)
                    && mOwners.getProfileOwnerPackage(userId).equals(packageName);
        }
    }

    public boolean isProfileOwner(ComponentName who, int userId) {
        final ComponentName profileOwner = getProfileOwner(userId);
        return who != null && who.equals(profileOwner);
    }

    @Override
    public ComponentName getDeviceOwnerComponent(boolean callingUserOnly) {
        if (!mHasFeature) {
            return null;
        }
        if (!callingUserOnly) {
            enforceManageUsers();
        }
        synchronized (getLockObject()) {
            if (!mOwners.hasDeviceOwner()) {
                return null;
            }
            if (callingUserOnly && mInjector.userHandleGetCallingUserId() !=
                    mOwners.getDeviceOwnerUserId()) {
                return null;
            }
            return mOwners.getDeviceOwnerComponent();
        }
    }

    @Override
    public int getDeviceOwnerUserId() {
        if (!mHasFeature) {
            return UserHandle.USER_NULL;
        }
        enforceManageUsers();
        synchronized (getLockObject()) {
            return mOwners.hasDeviceOwner() ? mOwners.getDeviceOwnerUserId() : UserHandle.USER_NULL;
        }
    }

    /**
     * Returns the "name" of the device owner.  It'll work for non-DO users too, but requires
     * MANAGE_USERS.
     */
    @Override
    public String getDeviceOwnerName() {
        if (!mHasFeature) {
            return null;
        }
        enforceManageUsers();
        synchronized (getLockObject()) {
            if (!mOwners.hasDeviceOwner()) {
                return null;
            }
            // TODO This totally ignores the name passed to setDeviceOwner (change for b/20679292)
            // Should setDeviceOwner/ProfileOwner still take a name?
            String deviceOwnerPackage = mOwners.getDeviceOwnerPackageName();
            return getApplicationLabel(deviceOwnerPackage, UserHandle.USER_SYSTEM);
        }
    }

    /** Returns the active device owner or {@code null} if there is no device owner. */
    @VisibleForTesting
    ActiveAdmin getDeviceOwnerAdminLocked() {
        ensureLocked();
        ComponentName component = mOwners.getDeviceOwnerComponent();
        if (component == null) {
            return null;
        }

        DevicePolicyData policy = getUserData(mOwners.getDeviceOwnerUserId());
        final int n = policy.mAdminList.size();
        for (int i = 0; i < n; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (component.equals(admin.info.getComponent())) {
                return admin;
            }
        }
        Slog.wtf(LOG_TAG, "Active admin for device owner not found. component=" + component);
        return null;
    }

    @Override
    public void clearDeviceOwner(String packageName) {
        Preconditions.checkNotNull(packageName, "packageName is null");
        final int callingUid = mInjector.binderGetCallingUid();
        try {
            int uid = mInjector.getPackageManager().getPackageUidAsUser(packageName,
                    UserHandle.getUserId(callingUid));
            if (uid != callingUid) {
                throw new SecurityException("Invalid packageName");
            }
        } catch (NameNotFoundException e) {
            throw new SecurityException(e);
        }
        synchronized (getLockObject()) {
            final ComponentName deviceOwnerComponent = mOwners.getDeviceOwnerComponent();
            final int deviceOwnerUserId = mOwners.getDeviceOwnerUserId();
            if (!mOwners.hasDeviceOwner()
                    || !deviceOwnerComponent.getPackageName().equals(packageName)
                    || (deviceOwnerUserId != UserHandle.getUserId(callingUid))) {
                throw new SecurityException(
                        "clearDeviceOwner can only be called by the device owner");
            }
            enforceUserUnlocked(deviceOwnerUserId);

            final ActiveAdmin admin = getDeviceOwnerAdminLocked();
            long ident = mInjector.binderClearCallingIdentity();
            try {
                clearDeviceOwnerLocked(admin, deviceOwnerUserId);
                removeActiveAdminLocked(deviceOwnerComponent, deviceOwnerUserId);
                sendOwnerChangedBroadcast(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED,
                        deviceOwnerUserId);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
            Slog.i(LOG_TAG, "Device owner removed: " + deviceOwnerComponent);
        }
    }

    private void clearOverrideApnUnchecked() {
        // Disable Override APNs and remove them from database.
        setOverrideApnsEnabledUnchecked(false);
        final List<ApnSetting> apns = getOverrideApnsUnchecked();
        for (int i = 0; i < apns.size(); i ++) {
            removeOverrideApnUnchecked(apns.get(i).getId());
        }
    }

    private void clearDeviceOwnerLocked(ActiveAdmin admin, int userId) {
        mDeviceAdminServiceController.stopServiceForOwner(userId, "clear-device-owner");

        if (admin != null) {
            admin.disableCamera = false;
            admin.userRestrictions = null;
            admin.defaultEnabledRestrictionsAlreadySet.clear();
            admin.forceEphemeralUsers = false;
            admin.isNetworkLoggingEnabled = false;
            mUserManagerInternal.setForceEphemeralUsers(admin.forceEphemeralUsers);
        }
        final DevicePolicyData policyData = getUserData(userId);
        policyData.mCurrentInputMethodSet = false;
        saveSettingsLocked(userId);
        final DevicePolicyData systemPolicyData = getUserData(UserHandle.USER_SYSTEM);
        systemPolicyData.mLastSecurityLogRetrievalTime = -1;
        systemPolicyData.mLastBugReportRequestTime = -1;
        systemPolicyData.mLastNetworkLogsRetrievalTime = -1;
        saveSettingsLocked(UserHandle.USER_SYSTEM);
        clearUserPoliciesLocked(userId);
        clearOverrideApnUnchecked();

        mOwners.clearDeviceOwner();
        mOwners.writeDeviceOwner();
        updateDeviceOwnerLocked();

        clearDeviceOwnerUserRestrictionLocked(UserHandle.of(userId));
        mInjector.securityLogSetLoggingEnabledProperty(false);
        mSecurityLogMonitor.stop();
        setNetworkLoggingActiveInternal(false);
        deleteTransferOwnershipBundleLocked(userId);

        try {
            if (mInjector.getIBackupManager() != null) {
                // Reactivate backup service.
                mInjector.getIBackupManager().setBackupServiceActive(UserHandle.USER_SYSTEM, true);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed reactivating backup service.", e);
        }
    }

    @Override
    public boolean setProfileOwner(ComponentName who, String ownerName, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        if (who == null
                || !isPackageInstalledForUser(who.getPackageName(), userHandle)) {
            throw new IllegalArgumentException("Component " + who
                    + " not installed for userId:" + userHandle);
        }
        final boolean hasIncompatibleAccountsOrNonAdb =
                hasIncompatibleAccountsOrNonAdbNoLock(userHandle, who);
        synchronized (getLockObject()) {
            enforceCanSetProfileOwnerLocked(who, userHandle, hasIncompatibleAccountsOrNonAdb);

            final ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin == null || getUserData(userHandle).mRemovingAdmins.contains(who)) {
                throw new IllegalArgumentException("Not active admin: " + who);
            }

            if (isAdb()) {
                // Log profile owner provisioning was started using adb.
                MetricsLogger.action(mContext, PROVISIONING_ENTRY_POINT_ADB, LOG_TAG_PROFILE_OWNER);
            }

            mOwners.setProfileOwner(who, ownerName, userHandle);
            mOwners.writeProfileOwner(userHandle);
            Slog.i(LOG_TAG, "Profile owner set: " + who + " on user " + userHandle);

            final long id = mInjector.binderClearCallingIdentity();
            try {
                if (mUserManager.isManagedProfile(userHandle)) {
                    maybeSetDefaultRestrictionsForAdminLocked(userHandle, admin,
                            UserRestrictionsUtils.getDefaultEnabledForManagedProfiles());
                    ensureUnknownSourcesRestrictionForProfileOwnerLocked(userHandle, admin,
                            true /* newOwner */);
                }
                sendOwnerChangedBroadcast(DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED,
                        userHandle);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
            mDeviceAdminServiceController.startServiceForOwner(
                    who.getPackageName(), userHandle, "set-profile-owner");
            return true;
        }
    }

    @Override
    public void clearProfileOwner(ComponentName who) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        final int userId = mInjector.userHandleGetCallingUserId();
        enforceNotManagedProfile(userId, "clear profile owner");
        enforceUserUnlocked(userId);
        synchronized (getLockObject()) {
            // Check if this is the profile owner who is calling
            final ActiveAdmin admin =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            final long ident = mInjector.binderClearCallingIdentity();
            try {
                clearProfileOwnerLocked(admin, userId);
                removeActiveAdminLocked(who, userId);
                sendOwnerChangedBroadcast(DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED,
                        userId);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
            Slog.i(LOG_TAG, "Profile owner " + who + " removed from user " + userId);
        }
    }

    public void clearProfileOwnerLocked(ActiveAdmin admin, int userId) {
        mDeviceAdminServiceController.stopServiceForOwner(userId, "clear-profile-owner");

        if (admin != null) {
            admin.disableCamera = false;
            admin.userRestrictions = null;
            admin.defaultEnabledRestrictionsAlreadySet.clear();
        }
        final DevicePolicyData policyData = getUserData(userId);
        policyData.mCurrentInputMethodSet = false;
        policyData.mOwnerInstalledCaCerts.clear();
        saveSettingsLocked(userId);
        clearUserPoliciesLocked(userId);
        mOwners.removeProfileOwner(userId);
        mOwners.writeProfileOwner(userId);
        deleteTransferOwnershipBundleLocked(userId);
    }

    @Override
    public void setDeviceOwnerLockScreenInfo(ComponentName who, CharSequence info) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!mHasFeature) {
            return;
        }

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            long token = mInjector.binderClearCallingIdentity();
            try {
                mLockPatternUtils.setDeviceOwnerInfo(info != null ? info.toString() : null);
            } finally {
                mInjector.binderRestoreCallingIdentity(token);
            }
        }
    }

    @Override
    public CharSequence getDeviceOwnerLockScreenInfo() {
        return mLockPatternUtils.getDeviceOwnerInfo();
    }

    private void clearUserPoliciesLocked(int userId) {
        // Reset some of the user-specific policies.
        final DevicePolicyData policy = getUserData(userId);
        policy.mPermissionPolicy = DevicePolicyManager.PERMISSION_POLICY_PROMPT;
        // Clear delegations.
        policy.mDelegationMap.clear();
        policy.mStatusBarDisabled = false;
        policy.mUserProvisioningState = DevicePolicyManager.STATE_USER_UNMANAGED;
        policy.mAffiliationIds.clear();
        policy.mLockTaskPackages.clear();
        updateLockTaskPackagesLocked(policy.mLockTaskPackages, userId);
        policy.mLockTaskFeatures = DevicePolicyManager.LOCK_TASK_FEATURE_NONE;
        saveSettingsLocked(userId);

        try {
            mIPackageManager.updatePermissionFlagsForAllApps(
                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                    0  /* flagValues */, userId);
            pushUserRestrictions(userId);
        } catch (RemoteException re) {
            // Shouldn't happen.
        }
    }

    @Override
    public boolean hasUserSetupCompleted() {
        return hasUserSetupCompleted(UserHandle.getCallingUserId());
    }

    // This checks only if the Setup Wizard has run.  Since Wear devices pair before
    // completing Setup Wizard, and pairing involves transferring user data, calling
    // logic may want to check mIsWatch or mPaired in addition to hasUserSetupCompleted().
    private boolean hasUserSetupCompleted(int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        return getUserData(userHandle).mUserSetupComplete;
    }

    private boolean hasPaired(int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        return getUserData(userHandle).mPaired;
    }

    @Override
    public int getUserProvisioningState() {
        if (!mHasFeature) {
            return DevicePolicyManager.STATE_USER_UNMANAGED;
        }
        enforceManageUsers();
        int userHandle = mInjector.userHandleGetCallingUserId();
        return getUserProvisioningState(userHandle);
    }

    private int getUserProvisioningState(int userHandle) {
        return getUserData(userHandle).mUserProvisioningState;
    }

    @Override
    public void setUserProvisioningState(int newState, int userHandle) {
        if (!mHasFeature) {
            return;
        }

        if (userHandle != mOwners.getDeviceOwnerUserId() && !mOwners.hasProfileOwner(userHandle)
                && getManagedUserId(userHandle) == -1) {
            // No managed device, user or profile, so setting provisioning state makes no sense.
            throw new IllegalStateException("Not allowed to change provisioning state unless a "
                      + "device or profile owner is set.");
        }

        synchronized (getLockObject()) {
            boolean transitionCheckNeeded = true;

            // Calling identity/permission checks.
            if (isAdb()) {
                // ADB shell can only move directly from un-managed to finalized as part of directly
                // setting profile-owner or device-owner.
                if (getUserProvisioningState(userHandle) !=
                        DevicePolicyManager.STATE_USER_UNMANAGED
                        || newState != DevicePolicyManager.STATE_USER_SETUP_FINALIZED) {
                    throw new IllegalStateException("Not allowed to change provisioning state "
                            + "unless current provisioning state is unmanaged, and new state is "
                            + "finalized.");
                }
                transitionCheckNeeded = false;
            } else {
                // For all other cases, caller must have MANAGE_PROFILE_AND_DEVICE_OWNERS.
                enforceCanManageProfileAndDeviceOwners();
            }

            final DevicePolicyData policyData = getUserData(userHandle);
            if (transitionCheckNeeded) {
                // Optional state transition check for non-ADB case.
                checkUserProvisioningStateTransition(policyData.mUserProvisioningState, newState);
            }
            policyData.mUserProvisioningState = newState;
            saveSettingsLocked(userHandle);
        }
    }

    private void checkUserProvisioningStateTransition(int currentState, int newState) {
        // Valid transitions for normal use-cases.
        switch (currentState) {
            case DevicePolicyManager.STATE_USER_UNMANAGED:
                // Can move to any state from unmanaged (except itself as an edge case)..
                if (newState != DevicePolicyManager.STATE_USER_UNMANAGED) {
                    return;
                }
                break;
            case DevicePolicyManager.STATE_USER_SETUP_INCOMPLETE:
            case DevicePolicyManager.STATE_USER_SETUP_COMPLETE:
                // Can only move to finalized from these states.
                if (newState == DevicePolicyManager.STATE_USER_SETUP_FINALIZED) {
                    return;
                }
                break;
            case DevicePolicyManager.STATE_USER_PROFILE_COMPLETE:
                // Current user has a managed-profile, but current user is not managed, so
                // rather than moving to finalized state, go back to unmanaged once
                // profile provisioning is complete.
                if (newState == DevicePolicyManager.STATE_USER_UNMANAGED) {
                    return;
                }
                break;
            case DevicePolicyManager.STATE_USER_SETUP_FINALIZED:
                // Cannot transition out of finalized.
                break;
        }

        // Didn't meet any of the accepted state transition checks above, throw appropriate error.
        throw new IllegalStateException("Cannot move to user provisioning state [" + newState + "] "
                + "from state [" + currentState + "]");
    }

    @Override
    public void setProfileEnabled(ComponentName who) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            // Check if this is the profile owner who is calling
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final int userId = UserHandle.getCallingUserId();
            enforceManagedProfile(userId, "enable the profile");
            // Check if the profile is already enabled.
            UserInfo managedProfile = getUserInfo(userId);
            if (managedProfile.isEnabled()) {
                Slog.e(LOG_TAG,
                        "setProfileEnabled is called when the profile is already enabled");
                return;
            }
            long id = mInjector.binderClearCallingIdentity();
            try {
                mUserManager.setUserEnabled(userId);
                UserInfo parent = mUserManager.getProfileParent(userId);
                Intent intent = new Intent(Intent.ACTION_MANAGED_PROFILE_ADDED);
                intent.putExtra(Intent.EXTRA_USER, new UserHandle(userId));
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY |
                        Intent.FLAG_RECEIVER_FOREGROUND);
                mContext.sendBroadcastAsUser(intent, new UserHandle(parent.id));
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setProfileName(ComponentName who, String profileName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = UserHandle.getCallingUserId();
        // Check if this is the profile owner (includes device owner).
        getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

        long id = mInjector.binderClearCallingIdentity();
        try {
            mUserManager.setUserName(userId, profileName);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public ComponentName getProfileOwner(int userHandle) {
        if (!mHasFeature) {
            return null;
        }

        synchronized (getLockObject()) {
            return mOwners.getProfileOwnerComponent(userHandle);
        }
    }

    // Returns the active profile owner for this user or null if the current user has no
    // profile owner.
    @VisibleForTesting
    ActiveAdmin getProfileOwnerAdminLocked(int userHandle) {
        ComponentName profileOwner = mOwners.getProfileOwnerComponent(userHandle);
        if (profileOwner == null) {
            return null;
        }
        DevicePolicyData policy = getUserData(userHandle);
        final int n = policy.mAdminList.size();
        for (int i = 0; i < n; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (profileOwner.equals(admin.info.getComponent())) {
                return admin;
            }
        }
        return null;
    }

    @Override
    public String getProfileOwnerName(int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        enforceManageUsers();
        ComponentName profileOwner = getProfileOwner(userHandle);
        if (profileOwner == null) {
            return null;
        }
        return getApplicationLabel(profileOwner.getPackageName(), userHandle);
    }

    /**
     * Canonical name for a given package.
     */
    private String getApplicationLabel(String packageName, int userHandle) {
        long token = mInjector.binderClearCallingIdentity();
        try {
            final Context userContext;
            try {
                UserHandle handle = new UserHandle(userHandle);
                userContext = mContext.createPackageContextAsUser(packageName, 0, handle);
            } catch (PackageManager.NameNotFoundException nnfe) {
                Log.w(LOG_TAG, packageName + " is not installed for user " + userHandle, nnfe);
                return null;
            }
            ApplicationInfo appInfo = userContext.getApplicationInfo();
            CharSequence result = null;
            if (appInfo != null) {
                result = appInfo.loadUnsafeLabel(userContext.getPackageManager());
            }
            return result != null ? result.toString() : null;
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    /**
     * Calls wtfStack() if called with the DPMS lock held.
     */
    private void wtfIfInLock() {
        if (Thread.holdsLock(this)) {
            Slog.wtfStack(LOG_TAG, "Shouldn't be called with DPMS lock held");
        }
    }

    /**
     * The profile owner can only be set by adb or an app with the MANAGE_PROFILE_AND_DEVICE_OWNERS
     * permission.
     * The profile owner can only be set before the user setup phase has completed,
     * except for:
     * - SYSTEM_UID
     * - adb unless hasIncompatibleAccountsOrNonAdb is true.
     */
    private void enforceCanSetProfileOwnerLocked(@Nullable ComponentName owner, int userHandle,
            boolean hasIncompatibleAccountsOrNonAdb) {
        UserInfo info = getUserInfo(userHandle);
        if (info == null) {
            // User doesn't exist.
            throw new IllegalArgumentException(
                    "Attempted to set profile owner for invalid userId: " + userHandle);
        }
        if (info.isGuest()) {
            throw new IllegalStateException("Cannot set a profile owner on a guest");
        }
        if (mOwners.hasProfileOwner(userHandle)) {
            throw new IllegalStateException("Trying to set the profile owner, but profile owner "
                    + "is already set.");
        }
        if (mOwners.hasDeviceOwner() && mOwners.getDeviceOwnerUserId() == userHandle) {
            throw new IllegalStateException("Trying to set the profile owner, but the user "
                    + "already has a device owner.");
        }
        if (isAdb()) {
            if ((mIsWatch || hasUserSetupCompleted(userHandle))
                    && hasIncompatibleAccountsOrNonAdb) {
                throw new IllegalStateException("Not allowed to set the profile owner because "
                        + "there are already some accounts on the profile");
            }
            return;
        }
        enforceCanManageProfileAndDeviceOwners();
        if ((mIsWatch || hasUserSetupCompleted(userHandle)) && !isCallerWithSystemUid()) {
            throw new IllegalStateException("Cannot set the profile owner on a user which is "
                    + "already set-up");
        }
    }

    /**
     * The Device owner can only be set by adb or an app with the MANAGE_PROFILE_AND_DEVICE_OWNERS
     * permission.
     */
    private void enforceCanSetDeviceOwnerLocked(@Nullable ComponentName owner, int userId,
            boolean hasIncompatibleAccountsOrNonAdb) {
        if (!isAdb()) {
            enforceCanManageProfileAndDeviceOwners();
        }

        final int code = checkDeviceOwnerProvisioningPreConditionLocked(
                owner, userId, isAdb(), hasIncompatibleAccountsOrNonAdb);
        switch (code) {
            case CODE_OK:
                return;
            case CODE_HAS_DEVICE_OWNER:
                throw new IllegalStateException(
                        "Trying to set the device owner, but device owner is already set.");
            case CODE_USER_HAS_PROFILE_OWNER:
                throw new IllegalStateException("Trying to set the device owner, but the user "
                        + "already has a profile owner.");
            case CODE_USER_NOT_RUNNING:
                throw new IllegalStateException("User not running: " + userId);
            case CODE_NOT_SYSTEM_USER:
                throw new IllegalStateException("User is not system user");
            case CODE_USER_SETUP_COMPLETED:
                throw new IllegalStateException(
                        "Cannot set the device owner if the device is already set-up");
            case CODE_NONSYSTEM_USER_EXISTS:
                throw new IllegalStateException("Not allowed to set the device owner because there "
                        + "are already several users on the device");
            case CODE_ACCOUNTS_NOT_EMPTY:
                throw new IllegalStateException("Not allowed to set the device owner because there "
                        + "are already some accounts on the device");
            case CODE_HAS_PAIRED:
                throw new IllegalStateException("Not allowed to set the device owner because this "
                        + "device has already paired");
            default:
                throw new IllegalStateException("Unexpected @ProvisioningPreCondition " + code);
        }
    }

    private void enforceUserUnlocked(int userId) {
        // Since we're doing this operation on behalf of an app, we only
        // want to use the actual "unlocked" state.
        Preconditions.checkState(mUserManager.isUserUnlocked(userId),
                "User must be running and unlocked");
    }

    private void enforceUserUnlocked(@UserIdInt int userId, boolean parent) {
        if (parent) {
            enforceUserUnlocked(getProfileParentId(userId));
        } else {
            enforceUserUnlocked(userId);
        }
    }

    private void enforceManageUsers() {
        final int callingUid = mInjector.binderGetCallingUid();
        if (!(isCallerWithSystemUid() || callingUid == Process.ROOT_UID)) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        }
    }

    private void enforceFullCrossUsersPermission(int userHandle) {
        enforceSystemUserOrPermissionIfCrossUser(userHandle,
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    private void enforceCrossUsersPermission(int userHandle) {
        enforceSystemUserOrPermissionIfCrossUser(userHandle,
                android.Manifest.permission.INTERACT_ACROSS_USERS);
    }

    private void enforceSystemUserOrPermission(String permission) {
        if (!(isCallerWithSystemUid() || mInjector.binderGetCallingUid() == Process.ROOT_UID)) {
            mContext.enforceCallingOrSelfPermission(permission,
                    "Must be system or have " + permission + " permission");
        }
    }

    private void enforceSystemUserOrPermissionIfCrossUser(int userHandle, String permission) {
        if (userHandle < 0) {
            throw new IllegalArgumentException("Invalid userId " + userHandle);
        }
        if (userHandle == mInjector.userHandleGetCallingUserId()) {
            return;
        }
        enforceSystemUserOrPermission(permission);
    }

    private void enforceManagedProfile(int userHandle, String message) {
        if(!isManagedProfile(userHandle)) {
            throw new SecurityException("You can not " + message + " outside a managed profile.");
        }
    }

    private void enforceNotManagedProfile(int userHandle, String message) {
        if(isManagedProfile(userHandle)) {
            throw new SecurityException("You can not " + message + " for a managed profile.");
        }
    }

    private void enforceDeviceOwnerOrManageUsers() {
        synchronized (getLockObject()) {
            if (getActiveAdminWithPolicyForUidLocked(null, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER,
                    mInjector.binderGetCallingUid()) != null) {
                return;
            }
        }
        enforceManageUsers();
    }

    private void enforceProfileOwnerOrSystemUser() {
        synchronized (getLockObject()) {
            if (getActiveAdminWithPolicyForUidLocked(null,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, mInjector.binderGetCallingUid())
                            != null) {
                return;
            }
        }
        Preconditions.checkState(isCallerWithSystemUid(),
                "Only profile owner, device owner and system may call this method.");
    }

    private void enforceProfileOwnerOrFullCrossUsersPermission(int userId) {
        if (userId == mInjector.userHandleGetCallingUserId()) {
            synchronized (getLockObject()) {
                if (getActiveAdminWithPolicyForUidLocked(null,
                        DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, mInjector.binderGetCallingUid())
                                != null) {
                    // Device Owner/Profile Owner may access the user it runs on.
                    return;
                }
            }
        }
        // Otherwise, INTERACT_ACROSS_USERS_FULL permission, system UID or root UID is required.
        enforceSystemUserOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    private boolean canUserUseLockTaskLocked(int userId) {
        if (isUserAffiliatedWithDeviceLocked(userId)) {
            return true;
        }

        // Unaffiliated profile owners are not allowed to use lock when there is a device owner.
        if (mOwners.hasDeviceOwner()) {
            return false;
        }

        final ComponentName profileOwner = getProfileOwner(userId);
        if (profileOwner == null) {
            return false;
        }

        // Managed profiles are not allowed to use lock task
        if (isManagedProfile(userId)) {
            return false;
        }

        return true;
    }

    private void enforceCanCallLockTaskLocked(ComponentName who) {
        getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        final int userId =  mInjector.userHandleGetCallingUserId();
        if (!canUserUseLockTaskLocked(userId)) {
            throw new SecurityException("User " + userId + " is not allowed to use lock task");
        }
    }

    private void ensureCallerPackage(@Nullable String packageName) {
        if (packageName == null) {
            Preconditions.checkState(isCallerWithSystemUid(),
                    "Only caller can omit package name");
        } else {
            final int callingUid = mInjector.binderGetCallingUid();
            final int userId = mInjector.userHandleGetCallingUserId();
            try {
                final ApplicationInfo ai = mIPackageManager.getApplicationInfo(
                        packageName, 0, userId);
                Preconditions.checkState(ai.uid == callingUid, "Unmatching package name");
            } catch (RemoteException e) {
                // Shouldn't happen
            }
        }
    }

    private boolean isCallerWithSystemUid() {
        return UserHandle.isSameApp(mInjector.binderGetCallingUid(), Process.SYSTEM_UID);
    }

    protected int getProfileParentId(int userHandle) {
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            UserInfo parentUser = mUserManager.getProfileParent(userHandle);
            return parentUser != null ? parentUser.id : userHandle;
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private int getCredentialOwner(int userHandle, boolean parent) {
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            if (parent) {
                UserInfo parentProfile = mUserManager.getProfileParent(userHandle);
                if (parentProfile != null) {
                    userHandle = parentProfile.id;
                }
            }
            return mUserManager.getCredentialOwnerProfile(userHandle);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private boolean isManagedProfile(int userHandle) {
        final UserInfo user = getUserInfo(userHandle);
        return user != null && user.isManagedProfile();
    }

    private void enableIfNecessary(String packageName, int userId) {
        try {
            final ApplicationInfo ai = mIPackageManager.getApplicationInfo(packageName,
                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS, userId);
            if (ai.enabledSetting
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                mIPackageManager.setApplicationEnabledSetting(packageName,
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        PackageManager.DONT_KILL_APP, userId, "DevicePolicyManager");
            }
        } catch (RemoteException e) {
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;

        synchronized (getLockObject()) {
            pw.println("Current Device Policy Manager state:");

            mOwners.dump("  ", pw);
            mDeviceAdminServiceController.dump("  ", pw);
            int userCount = mUserData.size();
            for (int u = 0; u < userCount; u++) {
                DevicePolicyData policy = getUserData(mUserData.keyAt(u));
                pw.println();
                pw.println("  Enabled Device Admins (User " + policy.mUserHandle
                        + ", provisioningState: " + policy.mUserProvisioningState + "):");
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin ap = policy.mAdminList.get(i);
                    if (ap != null) {
                        pw.print("    "); pw.print(ap.info.getComponent().flattenToShortString());
                                pw.println(":");
                        ap.dump("      ", pw);
                    }
                }
                if (!policy.mRemovingAdmins.isEmpty()) {
                    pw.println("    Removing Device Admins (User " + policy.mUserHandle + "): "
                            + policy.mRemovingAdmins);
                }

                pw.println(" ");
                pw.print("    mPasswordOwner="); pw.println(policy.mPasswordOwner);
            }
            pw.println();
            mConstants.dump("  ", pw);
            pw.println();
            mStatLogger.dump(pw, "  ");
            pw.println();
            pw.println("  Encryption Status: " + getEncryptionStatusName(getEncryptionStatus()));
        }
    }

    private String getEncryptionStatusName(int encryptionStatus) {
        switch (encryptionStatus) {
            case DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE:
                return "inactive";
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY:
                return "block default key";
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE:
                return "block";
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER:
                return "per-user";
            case DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED:
                return "unsupported";
            case DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING:
                return "activating";
            default:
                return "unknown";
        }
    }

    @Override
    public void addPersistentPreferredActivity(ComponentName who, IntentFilter filter,
            ComponentName activity) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                mIPackageManager.addPersistentPreferredActivity(filter, activity, userHandle);
                mIPackageManager.flushPackageRestrictionsAsUser(userHandle);
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void clearPackagePersistentPreferredActivities(ComponentName who, String packageName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                mIPackageManager.clearPackagePersistentPreferredActivities(packageName, userHandle);
                mIPackageManager.flushPackageRestrictionsAsUser(userHandle);
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setDefaultSmsApplication(ComponentName admin, String packageName) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        mInjector.binderWithCleanCallingIdentity(() ->
                SmsApplication.setDefaultApplication(packageName, mContext));
    }

    @Override
    public boolean setApplicationRestrictionsManagingPackage(ComponentName admin,
            String packageName) {
        try {
            setDelegatedScopePreO(admin, packageName, DELEGATION_APP_RESTRICTIONS);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    @Override
    public String getApplicationRestrictionsManagingPackage(ComponentName admin) {
        final List<String> delegatePackages = getDelegatePackages(admin,
                DELEGATION_APP_RESTRICTIONS);
        return delegatePackages.size() > 0 ? delegatePackages.get(0) : null;
    }

    @Override
    public boolean isCallerApplicationRestrictionsManagingPackage(String callerPackage) {
        return isCallerDelegate(callerPackage, DELEGATION_APP_RESTRICTIONS);
    }

    @Override
    public void setApplicationRestrictions(ComponentName who, String callerPackage,
            String packageName, Bundle settings) {
        enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                DELEGATION_APP_RESTRICTIONS);

        final UserHandle userHandle = mInjector.binderGetCallingUserHandle();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            mUserManager.setApplicationRestrictions(packageName, settings, userHandle);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public void setTrustAgentConfiguration(ComponentName admin, ComponentName agent,
            PersistableBundle args, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin, "admin is null");
        Preconditions.checkNotNull(agent, "agent is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(admin,
                    DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES, parent);
            ap.trustAgentInfos.put(agent.flattenToString(), new TrustAgentInfo(args));
            saveSettingsLocked(userHandle);
        }
    }

    @Override
    public List<PersistableBundle> getTrustAgentConfiguration(ComponentName admin,
            ComponentName agent, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(agent, "agent null");
        enforceFullCrossUsersPermission(userHandle);

        synchronized (getLockObject()) {
            final String componentName = agent.flattenToString();
            if (admin != null) {
                final ActiveAdmin ap = getActiveAdminUncheckedLocked(admin, userHandle, parent);
                if (ap == null) return null;
                TrustAgentInfo trustAgentInfo = ap.trustAgentInfos.get(componentName);
                if (trustAgentInfo == null || trustAgentInfo.options == null) return null;
                List<PersistableBundle> result = new ArrayList<>();
                result.add(trustAgentInfo.options);
                return result;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<PersistableBundle> result = null;
            // Search through all admins that use KEYGUARD_DISABLE_TRUST_AGENTS and keep track
            // of the options. If any admin doesn't have options, discard options for the rest
            // and return null.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            boolean allAdminsHaveOptions = true;
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                final ActiveAdmin active = admins.get(i);

                final boolean disablesTrust = (active.disabledKeyguardFeatures
                        & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0;
                final TrustAgentInfo info = active.trustAgentInfos.get(componentName);
                if (info != null && info.options != null && !info.options.isEmpty()) {
                    if (disablesTrust) {
                        if (result == null) {
                            result = new ArrayList<>();
                        }
                        result.add(info.options);
                    } else {
                        Log.w(LOG_TAG, "Ignoring admin " + active.info
                                + " because it has trust options but doesn't declare "
                                + "KEYGUARD_DISABLE_TRUST_AGENTS");
                    }
                } else if (disablesTrust) {
                    allAdminsHaveOptions = false;
                    break;
                }
            }
            return allAdminsHaveOptions ? result : null;
        }
    }

    @Override
    public void setRestrictionsProvider(ComponentName who, ComponentName permissionProvider) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            int userHandle = UserHandle.getCallingUserId();
            DevicePolicyData userData = getUserData(userHandle);
            userData.mRestrictionsProvider = permissionProvider;
            saveSettingsLocked(userHandle);
        }
    }

    @Override
    public ComponentName getRestrictionsProvider(int userHandle) {
        synchronized (getLockObject()) {
            if (!isCallerWithSystemUid()) {
                throw new SecurityException("Only the system can query the permission provider");
            }
            DevicePolicyData userData = getUserData(userHandle);
            return userData != null ? userData.mRestrictionsProvider : null;
        }
    }

    @Override
    public void addCrossProfileIntentFilter(ComponentName who, IntentFilter filter, int flags) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                UserInfo parent = mUserManager.getProfileParent(callingUserId);
                if (parent == null) {
                    Slog.e(LOG_TAG, "Cannot call addCrossProfileIntentFilter if there is no "
                            + "parent");
                    return;
                }
                if ((flags & DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED) != 0) {
                    mIPackageManager.addCrossProfileIntentFilter(
                            filter, who.getPackageName(), callingUserId, parent.id, 0);
                }
                if ((flags & DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT) != 0) {
                    mIPackageManager.addCrossProfileIntentFilter(filter, who.getPackageName(),
                            parent.id, callingUserId, 0);
                }
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void clearCrossProfileIntentFilters(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            long id = mInjector.binderClearCallingIdentity();
            try {
                UserInfo parent = mUserManager.getProfileParent(callingUserId);
                if (parent == null) {
                    Slog.e(LOG_TAG, "Cannot call clearCrossProfileIntentFilter if there is no "
                            + "parent");
                    return;
                }
                // Removing those that go from the managed profile to the parent.
                mIPackageManager.clearCrossProfileIntentFilters(
                        callingUserId, who.getPackageName());
                // And those that go from the parent to the managed profile.
                // If we want to support multiple managed profiles, we will have to only remove
                // those that have callingUserId as their target.
                mIPackageManager.clearCrossProfileIntentFilters(parent.id, who.getPackageName());
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    /**
     * @return true if all packages in enabledPackages are either in the list
     * permittedList or are a system app.
     */
    private boolean checkPackagesInPermittedListOrSystem(List<String> enabledPackages,
            List<String> permittedList, int userIdToCheck) {
        long id = mInjector.binderClearCallingIdentity();
        try {
            // If we have an enabled packages list for a managed profile the packages
            // we should check are installed for the parent user.
            UserInfo user = getUserInfo(userIdToCheck);
            if (user.isManagedProfile()) {
                userIdToCheck = user.profileGroupId;
            }

            for (String enabledPackage : enabledPackages) {
                boolean systemService = false;
                try {
                    ApplicationInfo applicationInfo = mIPackageManager.getApplicationInfo(
                            enabledPackage, PackageManager.MATCH_UNINSTALLED_PACKAGES,
                            userIdToCheck);
                    systemService = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                } catch (RemoteException e) {
                    Log.i(LOG_TAG, "Can't talk to package managed", e);
                }
                if (!systemService && !permittedList.contains(enabledPackage)) {
                    return false;
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        return true;
    }

    private AccessibilityManager getAccessibilityManagerForUser(int userId) {
        // Not using AccessibilityManager.getInstance because that guesses
        // at the user you require based on callingUid and caches for a given
        // process.
        IBinder iBinder = ServiceManager.getService(Context.ACCESSIBILITY_SERVICE);
        IAccessibilityManager service = iBinder == null
                ? null : IAccessibilityManager.Stub.asInterface(iBinder);
        return new AccessibilityManager(mContext, service, userId);
    }

    @Override
    public boolean setPermittedAccessibilityServices(ComponentName who, List packageList) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        if (packageList != null) {
            int userId = UserHandle.getCallingUserId();
            List<AccessibilityServiceInfo> enabledServices = null;
            long id = mInjector.binderClearCallingIdentity();
            try {
                UserInfo user = getUserInfo(userId);
                if (user.isManagedProfile()) {
                    userId = user.profileGroupId;
                }
                AccessibilityManager accessibilityManager = getAccessibilityManagerForUser(userId);
                enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }

            if (enabledServices != null) {
                List<String> enabledPackages = new ArrayList<String>();
                for (AccessibilityServiceInfo service : enabledServices) {
                    enabledPackages.add(service.getResolveInfo().serviceInfo.packageName);
                }
                if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList,
                        userId)) {
                    Slog.e(LOG_TAG, "Cannot set permitted accessibility services, "
                            + "because it contains already enabled accesibility services.");
                    return false;
                }
            }
        }

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            admin.permittedAccessiblityServices = packageList;
            saveSettingsLocked(UserHandle.getCallingUserId());
        }
        return true;
    }

    @Override
    public List getPermittedAccessibilityServices(ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.permittedAccessiblityServices;
        }
    }

    @Override
    public List getPermittedAccessibilityServicesForUser(int userId) {
        if (!mHasFeature) {
            return null;
        }
        enforceManageUsers();
        synchronized (getLockObject()) {
            List<String> result = null;
            // If we have multiple profiles we return the intersection of the
            // permitted lists. This can happen in cases where we have a device
            // and profile owner.
            int[] profileIds = mUserManager.getProfileIdsWithDisabled(userId);
            for (int profileId : profileIds) {
                // Just loop though all admins, only device or profiles
                // owners can have permitted lists set.
                DevicePolicyData policy = getUserDataUnchecked(profileId);
                final int N = policy.mAdminList.size();
                for (int j = 0; j < N; j++) {
                    ActiveAdmin admin = policy.mAdminList.get(j);
                    List<String> fromAdmin = admin.permittedAccessiblityServices;
                    if (fromAdmin != null) {
                        if (result == null) {
                            result = new ArrayList<>(fromAdmin);
                        } else {
                            result.retainAll(fromAdmin);
                        }
                    }
                }
            }

            // If we have a permitted list add all system accessibility services.
            if (result != null) {
                long id = mInjector.binderClearCallingIdentity();
                try {
                    UserInfo user = getUserInfo(userId);
                    if (user.isManagedProfile()) {
                        userId = user.profileGroupId;
                    }
                    AccessibilityManager accessibilityManager =
                            getAccessibilityManagerForUser(userId);
                    List<AccessibilityServiceInfo> installedServices =
                            accessibilityManager.getInstalledAccessibilityServiceList();

                    if (installedServices != null) {
                        for (AccessibilityServiceInfo service : installedServices) {
                            ServiceInfo serviceInfo = service.getResolveInfo().serviceInfo;
                            ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
                            if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                                result.add(serviceInfo.packageName);
                            }
                        }
                    }
                } finally {
                    mInjector.binderRestoreCallingIdentity(id);
                }
            }

            return result;
        }
    }

    @Override
    public boolean isAccessibilityServicePermittedByAdmin(ComponentName who, String packageName,
            int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(packageName, "packageName is null");
        if (!isCallerWithSystemUid()){
            throw new SecurityException(
                    "Only the system can query if an accessibility service is disabled by admin");
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin == null) {
                return false;
            }
            if (admin.permittedAccessiblityServices == null) {
                return true;
            }
            return checkPackagesInPermittedListOrSystem(Collections.singletonList(packageName),
                    admin.permittedAccessiblityServices, userHandle);
        }
    }

    private boolean checkCallerIsCurrentUserOrProfile() {
        final int callingUserId = UserHandle.getCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try {
            UserInfo currentUser;
            UserInfo callingUser = getUserInfo(callingUserId);
            try {
                currentUser = mInjector.getIActivityManager().getCurrentUser();
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Failed to talk to activity managed.", e);
                return false;
            }

            if (callingUser.isManagedProfile() && callingUser.profileGroupId != currentUser.id) {
                Slog.e(LOG_TAG, "Cannot set permitted input methods for managed profile "
                        + "of a user that isn't the foreground user.");
                return false;
            }
            if (!callingUser.isManagedProfile() && callingUserId != currentUser.id ) {
                Slog.e(LOG_TAG, "Cannot set permitted input methods "
                        + "of a user that isn't the foreground user.");
                return false;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
        return true;
    }

    @Override
    public boolean setPermittedInputMethods(ComponentName who, List packageList) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        // TODO When InputMethodManager supports per user calls remove
        //      this restriction.
        if (!checkCallerIsCurrentUserOrProfile()) {
            return false;
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        if (packageList != null) {
            // InputMethodManager fetches input methods for current user.
            // So this can only be set when calling user is the current user
            // or parent is current user in case of managed profiles.
            InputMethodManager inputMethodManager =
                    mContext.getSystemService(InputMethodManager.class);
            List<InputMethodInfo> enabledImes = inputMethodManager.getEnabledInputMethodList();

            if (enabledImes != null) {
                List<String> enabledPackages = new ArrayList<String>();
                for (InputMethodInfo ime : enabledImes) {
                    enabledPackages.add(ime.getPackageName());
                }
                if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList,
                        callingUserId)) {
                    Slog.e(LOG_TAG, "Cannot set permitted input methods, "
                            + "because it contains already enabled input method.");
                    return false;
                }
            }
        }

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            admin.permittedInputMethods = packageList;
            saveSettingsLocked(callingUserId);
        }
        return true;
    }

    @Override
    public List getPermittedInputMethods(ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.permittedInputMethods;
        }
    }

    @Override
    public List getPermittedInputMethodsForCurrentUser() {
        enforceManageUsers();
        UserInfo currentUser;
        try {
            currentUser = mInjector.getIActivityManager().getCurrentUser();
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Failed to make remote calls to get current user", e);
            // Activity managed is dead, just allow all IMEs
            return null;
        }

        int userId = currentUser.id;
        synchronized (getLockObject()) {
            List<String> result = null;
            // If we have multiple profiles we return the intersection of the
            // permitted lists. This can happen in cases where we have a device
            // and profile owner.
            int[] profileIds = mUserManager.getProfileIdsWithDisabled(userId);
            for (int profileId : profileIds) {
                // Just loop though all admins, only device or profiles
                // owners can have permitted lists set.
                DevicePolicyData policy = getUserDataUnchecked(profileId);
                final int N = policy.mAdminList.size();
                for (int j = 0; j < N; j++) {
                    ActiveAdmin admin = policy.mAdminList.get(j);
                    List<String> fromAdmin = admin.permittedInputMethods;
                    if (fromAdmin != null) {
                        if (result == null) {
                            result = new ArrayList<String>(fromAdmin);
                        } else {
                            result.retainAll(fromAdmin);
                        }
                    }
                }
            }

            // If we have a permitted list add all system input methods.
            if (result != null) {
                InputMethodManager inputMethodManager =
                        mContext.getSystemService(InputMethodManager.class);
                List<InputMethodInfo> imes = inputMethodManager.getInputMethodList();
                long id = mInjector.binderClearCallingIdentity();
                try {
                    if (imes != null) {
                        for (InputMethodInfo ime : imes) {
                            ServiceInfo serviceInfo = ime.getServiceInfo();
                            ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
                            if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                                result.add(serviceInfo.packageName);
                            }
                        }
                    }
                } finally {
                    mInjector.binderRestoreCallingIdentity(id);
                }
            }
            return result;
        }
    }

    @Override
    public boolean isInputMethodPermittedByAdmin(ComponentName who, String packageName,
            int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(packageName, "packageName is null");
        if (!isCallerWithSystemUid()) {
            throw new SecurityException(
                    "Only the system can query if an input method is disabled by admin");
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin == null) {
                return false;
            }
            if (admin.permittedInputMethods == null) {
                return true;
            }
            return checkPackagesInPermittedListOrSystem(Collections.singletonList(packageName),
                    admin.permittedInputMethods, userHandle);
        }
    }

    @Override
    public boolean setPermittedCrossProfileNotificationListeners(
            ComponentName who, List<String> packageList) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        if (!isManagedProfile(callingUserId)) {
            return false;
        }

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            admin.permittedNotificationListeners = packageList;
            saveSettingsLocked(callingUserId);
        }
        return true;
    }

    @Override
    public List<String> getPermittedCrossProfileNotificationListeners(ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.permittedNotificationListeners;
        }
    }

    @Override
    public boolean isNotificationListenerServicePermitted(String packageName, int userId) {
        if (!mHasFeature) {
            return true;
        }

        Preconditions.checkStringNotEmpty(packageName, "packageName is null or empty");
        if (!isCallerWithSystemUid()) {
            throw new SecurityException(
                    "Only the system can query if a notification listener service is permitted");
        }
        synchronized (getLockObject()) {
            ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
            if (profileOwner == null || profileOwner.permittedNotificationListeners == null) {
                return true;
            }
            return checkPackagesInPermittedListOrSystem(Collections.singletonList(packageName),
                    profileOwner.permittedNotificationListeners, userId);

        }
    }

    private void maybeSendAdminEnabledBroadcastLocked(int userHandle) {
        DevicePolicyData policyData = getUserData(userHandle);
        if (policyData.mAdminBroadcastPending) {
            // Send the initialization data to profile owner and delete the data
            ActiveAdmin admin = getProfileOwnerAdminLocked(userHandle);
            boolean clearInitBundle = true;
            if (admin != null) {
                PersistableBundle initBundle = policyData.mInitBundle;
                clearInitBundle = sendAdminCommandLocked(admin,
                        DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED,
                        initBundle == null ? null : new Bundle(initBundle),
                        null /* result receiver */,
                        true /* send in foreground */);
            }
            if (clearInitBundle) {
                // If there's no admin or we've successfully called the admin, clear the init bundle
                // otherwise, keep it around
                policyData.mInitBundle = null;
                policyData.mAdminBroadcastPending = false;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public UserHandle createAndManageUser(ComponentName admin, String name,
            ComponentName profileOwner, PersistableBundle adminExtras, int flags) {
        Preconditions.checkNotNull(admin, "admin is null");
        Preconditions.checkNotNull(profileOwner, "profileOwner is null");
        if (!admin.getPackageName().equals(profileOwner.getPackageName())) {
            throw new IllegalArgumentException("profileOwner " + profileOwner + " and admin "
                    + admin + " are not in the same package");
        }
        // Only allow the system user to use this method
        if (!mInjector.binderGetCallingUserHandle().isSystem()) {
            throw new SecurityException("createAndManageUser was called from non-system user");
        }
        final boolean ephemeral = (flags & DevicePolicyManager.MAKE_USER_EPHEMERAL) != 0;
        final boolean demo = (flags & DevicePolicyManager.MAKE_USER_DEMO) != 0
                && UserManager.isDeviceInDemoMode(mContext);
        final boolean leaveAllSystemAppsEnabled = (flags & LEAVE_ALL_SYSTEM_APPS_ENABLED) != 0;
        final int targetSdkVersion;

        // Create user.
        UserHandle user = null;
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            final int callingUid = mInjector.binderGetCallingUid();
            final long id = mInjector.binderClearCallingIdentity();
            try {
                targetSdkVersion = mInjector.getPackageManagerInternal().getUidTargetSdkVersion(
                        callingUid);

                // Return detail error code for checks inside
                // UserManagerService.createUserInternalUnchecked.
                DeviceStorageMonitorInternal deviceStorageMonitorInternal =
                        LocalServices.getService(DeviceStorageMonitorInternal.class);
                if (deviceStorageMonitorInternal.isMemoryLow()) {
                    if (targetSdkVersion >= Build.VERSION_CODES.P) {
                        throw new ServiceSpecificException(
                                UserManager.USER_OPERATION_ERROR_LOW_STORAGE, "low device storage");
                    } else {
                        return null;
                    }
                }
                if (!mUserManager.canAddMoreUsers()) {
                    if (targetSdkVersion >= Build.VERSION_CODES.P) {
                        throw new ServiceSpecificException(
                                UserManager.USER_OPERATION_ERROR_MAX_USERS, "user limit reached");
                    } else {
                        return null;
                    }
                }

                int userInfoFlags = 0;
                if (ephemeral) {
                    userInfoFlags |= UserInfo.FLAG_EPHEMERAL;
                }
                if (demo) {
                    userInfoFlags |= UserInfo.FLAG_DEMO;
                }
                String[] disallowedPackages = null;
                if (!leaveAllSystemAppsEnabled) {
                    disallowedPackages = mOverlayPackagesProvider.getNonRequiredApps(admin,
                            UserHandle.myUserId(), ACTION_PROVISION_MANAGED_USER).toArray(
                            new String[0]);
                }
                UserInfo userInfo = mUserManagerInternal.createUserEvenWhenDisallowed(name,
                        userInfoFlags, disallowedPackages);
                if (userInfo != null) {
                    user = userInfo.getUserHandle();
                }
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
        if (user == null) {
            if (targetSdkVersion >= Build.VERSION_CODES.P) {
                throw new ServiceSpecificException(UserManager.USER_OPERATION_ERROR_UNKNOWN,
                        "failed to create user");
            } else {
                return null;
            }
        }

        final int userHandle = user.getIdentifier();
        final Intent intent = new Intent(DevicePolicyManager.ACTION_MANAGED_USER_CREATED)
                .putExtra(Intent.EXTRA_USER_HANDLE, userHandle)
                .putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED,
                        leaveAllSystemAppsEnabled)
                .setPackage(getManagedProvisioningPackage(mContext))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);

        final long id = mInjector.binderClearCallingIdentity();
        try {
            final String adminPkg = admin.getPackageName();
            try {
                // Install the profile owner if not present.
                if (!mIPackageManager.isPackageAvailable(adminPkg, userHandle)) {
                    mIPackageManager.installExistingPackageAsUser(adminPkg, userHandle,
                            0 /*installFlags*/, PackageManager.INSTALL_REASON_POLICY);
                }
            } catch (RemoteException e) {
                // Does not happen, same process
            }

            // Set admin.
            setActiveAdmin(profileOwner, true, userHandle);
            final String ownerName = getProfileOwnerName(Process.myUserHandle().getIdentifier());
            setProfileOwner(profileOwner, ownerName, userHandle);

            synchronized (getLockObject()) {
                DevicePolicyData policyData = getUserData(userHandle);
                policyData.mInitBundle = adminExtras;
                policyData.mAdminBroadcastPending = true;
                saveSettingsLocked(userHandle);
            }

            if ((flags & DevicePolicyManager.SKIP_SETUP_WIZARD) != 0) {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 1, userHandle);
            }

            return user;
        } catch (Throwable re) {
            mUserManager.removeUser(userHandle);
            if (targetSdkVersion >= Build.VERSION_CODES.P) {
                throw new ServiceSpecificException(UserManager.USER_OPERATION_ERROR_UNKNOWN,
                        re.getMessage());
            } else {
                return null;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public boolean removeUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            String restriction = isManagedProfile(userHandle.getIdentifier())
                    ? UserManager.DISALLOW_REMOVE_MANAGED_PROFILE
                    : UserManager.DISALLOW_REMOVE_USER;
            if (isAdminAffectedByRestriction(who, restriction, callingUserId)) {
                Log.w(LOG_TAG, "The device owner cannot remove a user because "
                        + restriction + " is enabled, and was not set by the device owner");
                return false;
            }
            return mUserManagerInternal.removeUserEvenWhenDisallowed(userHandle.getIdentifier());
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private boolean isAdminAffectedByRestriction(
            ComponentName admin, String userRestriction, int userId) {
        switch(mUserManager.getUserRestrictionSource(userRestriction, UserHandle.of(userId))) {
            case UserManager.RESTRICTION_NOT_SET:
                return false;
            case UserManager.RESTRICTION_SOURCE_DEVICE_OWNER:
                return !isDeviceOwner(admin, userId);
            case UserManager.RESTRICTION_SOURCE_PROFILE_OWNER:
                return !isProfileOwner(admin, userId);
            default:
                return true;
        }
    }

    @Override
    public boolean switchUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                int userId = UserHandle.USER_SYSTEM;
                if (userHandle != null) {
                    userId = userHandle.getIdentifier();
                }
                return mInjector.getIActivityManager().switchUser(userId);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Couldn't switch user", e);
                return false;
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public int startUserInBackground(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        final int userId = userHandle.getIdentifier();
        if (isManagedProfile(userId)) {
            Log.w(LOG_TAG, "Managed profile cannot be started in background");
            return UserManager.USER_OPERATION_ERROR_MANAGED_PROFILE;
        }

        final long id = mInjector.binderClearCallingIdentity();
        try {
            if (!mInjector.getActivityManagerInternal().canStartMoreUsers()) {
                Log.w(LOG_TAG, "Cannot start more users in background");
                return UserManager.USER_OPERATION_ERROR_MAX_RUNNING_USERS;
            }

            if (mInjector.getIActivityManager().startUserInBackground(userId)) {
                return UserManager.USER_OPERATION_SUCCESS;
            } else {
                return UserManager.USER_OPERATION_ERROR_UNKNOWN;
            }
        } catch (RemoteException e) {
            // Same process, should not happen.
            return UserManager.USER_OPERATION_ERROR_UNKNOWN;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public int stopUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(userHandle, "UserHandle is null");

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        final int userId = userHandle.getIdentifier();
        if (isManagedProfile(userId)) {
            Log.w(LOG_TAG, "Managed profile cannot be stopped");
            return UserManager.USER_OPERATION_ERROR_MANAGED_PROFILE;
        }

        return stopUserUnchecked(userId);
    }

    @Override
    public int logoutUser(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (!isUserAffiliatedWithDeviceLocked(callingUserId)) {
                throw new SecurityException("Admin " + who +
                        " is neither the device owner or affiliated user's profile owner.");
            }
        }

        if (isManagedProfile(callingUserId)) {
            Log.w(LOG_TAG, "Managed profile cannot be logout");
            return UserManager.USER_OPERATION_ERROR_MANAGED_PROFILE;
        }

        final long id = mInjector.binderClearCallingIdentity();
        try {
            if (!mInjector.getIActivityManager().switchUser(UserHandle.USER_SYSTEM)) {
                Log.w(LOG_TAG, "Failed to switch to primary user");
                // This should never happen as target user is UserHandle.USER_SYSTEM
                return UserManager.USER_OPERATION_ERROR_UNKNOWN;
            }
        } catch (RemoteException e) {
            // Same process, should not happen.
            return UserManager.USER_OPERATION_ERROR_UNKNOWN;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }

        return stopUserUnchecked(callingUserId);
    }

    private int stopUserUnchecked(int userId) {
        final long id = mInjector.binderClearCallingIdentity();
        try {
            switch (mInjector.getIActivityManager().stopUser(userId, true /*force*/, null)) {
                case ActivityManager.USER_OP_SUCCESS:
                    return UserManager.USER_OPERATION_SUCCESS;
                case ActivityManager.USER_OP_IS_CURRENT:
                    return UserManager.USER_OPERATION_ERROR_CURRENT_USER;
                default:
                    return UserManager.USER_OPERATION_ERROR_UNKNOWN;
            }
        } catch (RemoteException e) {
            // Same process, should not happen.
            return UserManager.USER_OPERATION_ERROR_UNKNOWN;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public List<UserHandle> getSecondaryUsers(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        final long id = mInjector.binderClearCallingIdentity();
        try {
            final List<UserInfo> userInfos = mInjector.getUserManager().getUsers(true
                    /*excludeDying*/);
            final List<UserHandle> userHandles = new ArrayList<>();
            for (UserInfo userInfo : userInfos) {
                UserHandle userHandle = userInfo.getUserHandle();
                if (!userHandle.isSystem() && !isManagedProfile(userHandle.getIdentifier())) {
                    userHandles.add(userInfo.getUserHandle());
                }
            }
            return userHandles;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public boolean isEphemeralUser(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        final long id = mInjector.binderClearCallingIdentity();
        try {
            return mInjector.getUserManager().isUserEphemeral(callingUserId);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public Bundle getApplicationRestrictions(ComponentName who, String callerPackage,
            String packageName) {
        enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                DELEGATION_APP_RESTRICTIONS);

        final UserHandle userHandle = mInjector.binderGetCallingUserHandle();
        final long id = mInjector.binderClearCallingIdentity();
        try {
           Bundle bundle = mUserManager.getApplicationRestrictions(packageName, userHandle);
           // if no restrictions were saved, mUserManager.getApplicationRestrictions
           // returns null, but DPM method should return an empty Bundle as per JavaDoc
           return bundle != null ? bundle : Bundle.EMPTY;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public String[] setPackagesSuspended(ComponentName who, String callerPackage,
            String[] packageNames, boolean suspended) {
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a package access delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_PACKAGE_ACCESS);

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mIPackageManager.setPackagesSuspendedAsUser(packageNames, suspended,
                        null, null, null, PLATFORM_PACKAGE_NAME, callingUserId);
            } catch (RemoteException re) {
                // Shouldn't happen.
                Slog.e(LOG_TAG, "Failed talking to the package manager", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
            return packageNames;
        }
    }

    @Override
    public boolean isPackageSuspended(ComponentName who, String callerPackage, String packageName) {
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a package access delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_PACKAGE_ACCESS);

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mIPackageManager.isPackageSuspendedForUser(packageName, callingUserId);
            } catch (RemoteException re) {
                // Shouldn't happen.
                Slog.e(LOG_TAG, "Failed talking to the package manager", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
            return false;
        }
    }

    @Override
    public void setUserRestriction(ComponentName who, String key, boolean enabledFromThisOwner) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            return;
        }

        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            final ActiveAdmin activeAdmin =
                    getActiveAdminForCallerLocked(who,
                            DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final boolean isDeviceOwner = isDeviceOwner(who, userHandle);
            if (isDeviceOwner) {
                if (!UserRestrictionsUtils.canDeviceOwnerChange(key)) {
                    throw new SecurityException("Device owner cannot set user restriction " + key);
                }
            } else { // profile owner
                if (!UserRestrictionsUtils.canProfileOwnerChange(key, userHandle)) {
                    throw new SecurityException("Profile owner cannot set user restriction " + key);
                }
            }

            // Save the restriction to ActiveAdmin.
            final Bundle restrictions = activeAdmin.ensureUserRestrictions();
            if (enabledFromThisOwner) {
                restrictions.putBoolean(key, true);
            } else {
                restrictions.remove(key);
            }
            saveUserRestrictionsLocked(userHandle);
        }
        if (SecurityLog.isLoggingEnabled()) {
            final int eventTag = enabledFromThisOwner
                    ? SecurityLog.TAG_USER_RESTRICTION_ADDED
                    : SecurityLog.TAG_USER_RESTRICTION_REMOVED;
            SecurityLog.writeEvent(eventTag, who.getPackageName(), userHandle, key);
        }
    }

    private void saveUserRestrictionsLocked(int userId) {
        saveSettingsLocked(userId);
        pushUserRestrictions(userId);
        sendChangedNotification(userId);
    }

    private void pushUserRestrictions(int userId) {
        synchronized (getLockObject()) {
            final boolean isDeviceOwner = mOwners.isDeviceOwnerUserId(userId);
            final Bundle userRestrictions;
            // Whether device owner enforces camera restriction.
            boolean disallowCameraGlobally = false;

            if (isDeviceOwner) {
                final ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner == null) {
                    return; // Shouldn't happen.
                }
                userRestrictions = deviceOwner.userRestrictions;
                // DO can disable camera globally.
                disallowCameraGlobally = deviceOwner.disableCamera;
            } else {
                final ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                userRestrictions = profileOwner != null ? profileOwner.userRestrictions : null;
            }

            // Whether any admin enforces camera restriction.
            final int cameraRestrictionScope =
                    getCameraRestrictionScopeLocked(userId, disallowCameraGlobally);

            mUserManagerInternal.setDevicePolicyUserRestrictions(userId, userRestrictions,
                    isDeviceOwner, cameraRestrictionScope);
        }
    }

    /**
     * Get the scope of camera restriction for a given user if any.
     */
    private int getCameraRestrictionScopeLocked(int userId, boolean disallowCameraGlobally) {
        if (disallowCameraGlobally) {
            return UserManagerInternal.CAMERA_DISABLED_GLOBALLY;
        } else if (getCameraDisabled(
                /* who= */ null, userId, /* mergeDeviceOwnerRestriction= */ false)) {
            return UserManagerInternal.CAMERA_DISABLED_LOCALLY;
        }
        return UserManagerInternal.CAMERA_NOT_DISABLED;
    }

    @Override
    public Bundle getUserRestrictions(ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            final ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return activeAdmin.userRestrictions;
        }
    }

    @Override
    public boolean setApplicationHidden(ComponentName who, String callerPackage, String packageName,
            boolean hidden) {
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a package access delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_PACKAGE_ACCESS);

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mIPackageManager.setApplicationHiddenSettingAsUser(
                        packageName, hidden, callingUserId);
            } catch (RemoteException re) {
                // shouldn't happen
                Slog.e(LOG_TAG, "Failed to setApplicationHiddenSetting", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
            return false;
        }
    }

    @Override
    public boolean isApplicationHidden(ComponentName who, String callerPackage,
            String packageName) {
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a package access delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_PACKAGE_ACCESS);

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mIPackageManager.getApplicationHiddenSettingAsUser(
                        packageName, callingUserId);
            } catch (RemoteException re) {
                // shouldn't happen
                Slog.e(LOG_TAG, "Failed to getApplicationHiddenSettingAsUser", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
            return false;
        }
    }

    @Override
    public void enableSystemApp(ComponentName who, String callerPackage, String packageName) {
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or an enable system app delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_ENABLE_SYSTEM_APP);

            final boolean isDemo = isCurrentUserDemo();

            int userId = UserHandle.getCallingUserId();
            long id = mInjector.binderClearCallingIdentity();

            try {
                if (VERBOSE_LOG) {
                    Slog.v(LOG_TAG, "installing " + packageName + " for "
                            + userId);
                }

                int parentUserId = getProfileParentId(userId);
                if (!isDemo && !isSystemApp(mIPackageManager, packageName, parentUserId)) {
                    throw new IllegalArgumentException("Only system apps can be enabled this way.");
                }

                // Install the app.
                mIPackageManager.installExistingPackageAsUser(packageName, userId,
                        0 /*installFlags*/, PackageManager.INSTALL_REASON_POLICY);
                if (isDemo) {
                    // Ensure the app is also ENABLED for demo users.
                    mIPackageManager.setApplicationEnabledSetting(packageName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP, userId, "DevicePolicyManager");
                }
            } catch (RemoteException re) {
                // shouldn't happen
                Slog.wtf(LOG_TAG, "Failed to install " + packageName, re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public int enableSystemAppWithIntent(ComponentName who, String callerPackage, Intent intent) {
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or an enable system app delegate.
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_ENABLE_SYSTEM_APP);

            int userId = UserHandle.getCallingUserId();
            long id = mInjector.binderClearCallingIdentity();

            try {
                int parentUserId = getProfileParentId(userId);
                List<ResolveInfo> activitiesToEnable = mIPackageManager
                        .queryIntentActivities(intent,
                                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                parentUserId)
                        .getList();

                if (VERBOSE_LOG) {
                    Slog.d(LOG_TAG, "Enabling system activities: " + activitiesToEnable);
                }
                int numberOfAppsInstalled = 0;
                if (activitiesToEnable != null) {
                    for (ResolveInfo info : activitiesToEnable) {
                        if (info.activityInfo != null) {
                            String packageName = info.activityInfo.packageName;
                            if (isSystemApp(mIPackageManager, packageName, parentUserId)) {
                                numberOfAppsInstalled++;
                                mIPackageManager.installExistingPackageAsUser(packageName, userId,
                                        0 /*installFlags*/, PackageManager.INSTALL_REASON_POLICY);
                            } else {
                                Slog.d(LOG_TAG, "Not enabling " + packageName + " since is not a"
                                        + " system app");
                            }
                        }
                    }
                }
                return numberOfAppsInstalled;
            } catch (RemoteException e) {
                // shouldn't happen
                Slog.wtf(LOG_TAG, "Failed to resolve intent for: " + intent);
                return 0;
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    private boolean isSystemApp(IPackageManager pm, String packageName, int userId)
            throws RemoteException {
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, MATCH_UNINSTALLED_PACKAGES,
                userId);
        if (appInfo == null) {
            throw new IllegalArgumentException("The application " + packageName +
                    " is not present on this device");
        }
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    @Override
    public boolean installExistingPackage(ComponentName who, String callerPackage,
            String packageName) {
        synchronized (getLockObject()) {
            // Ensure the caller is a PO or an install existing package delegate
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_INSTALL_EXISTING_PACKAGE);
            final int callingUserId = mInjector.userHandleGetCallingUserId();
            if (!isUserAffiliatedWithDeviceLocked(callingUserId)) {
                throw new SecurityException("Admin " + who +
                        " is neither the device owner or affiliated user's profile owner.");
            }

            final long id = mInjector.binderClearCallingIdentity();
            try {
                if (VERBOSE_LOG) {
                    Slog.v(LOG_TAG, "installing " + packageName + " for "
                            + callingUserId);
                }

                // Install the package.
                return mIPackageManager.installExistingPackageAsUser(packageName, callingUserId,
                        0 /*installFlags*/, PackageManager.INSTALL_REASON_POLICY)
                        == PackageManager.INSTALL_SUCCEEDED;
            } catch (RemoteException re) {
                // shouldn't happen
                return false;
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setAccountManagementDisabled(ComponentName who, String accountType,
            boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (disabled) {
                ap.accountTypesWithManagementDisabled.add(accountType);
            } else {
                ap.accountTypesWithManagementDisabled.remove(accountType);
            }
            saveSettingsLocked(UserHandle.getCallingUserId());
        }
    }

    @Override
    public String[] getAccountTypesWithManagementDisabled() {
        return getAccountTypesWithManagementDisabledAsUser(UserHandle.getCallingUserId());
    }

    @Override
    public String[] getAccountTypesWithManagementDisabledAsUser(int userId) {
        enforceFullCrossUsersPermission(userId);
        if (!mHasFeature) {
            return null;
        }
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(userId);
            final int N = policy.mAdminList.size();
            ArraySet<String> resultSet = new ArraySet<>();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = policy.mAdminList.get(i);
                resultSet.addAll(admin.accountTypesWithManagementDisabled);
            }
            return resultSet.toArray(new String[resultSet.size()]);
        }
    }

    @Override
    public void setUninstallBlocked(ComponentName who, String callerPackage, String packageName,
            boolean uninstallBlocked) {
        final int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a block uninstall delegate
            enforceCanManageScope(who, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_BLOCK_UNINSTALL);

            long id = mInjector.binderClearCallingIdentity();
            try {
                mIPackageManager.setBlockUninstallForUser(packageName, uninstallBlocked, userId);
            } catch (RemoteException re) {
                // Shouldn't happen.
                Slog.e(LOG_TAG, "Failed to setBlockUninstallForUser", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public boolean isUninstallBlocked(ComponentName who, String packageName) {
        // This function should return true if and only if the package is blocked by
        // setUninstallBlocked(). It should still return false for other cases of blocks, such as
        // when the package is a system app, or when it is an active device admin.
        final int userId = UserHandle.getCallingUserId();

        synchronized (getLockObject()) {
            if (who != null) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            }

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mIPackageManager.getBlockUninstallForUser(packageName, userId);
            } catch (RemoteException re) {
                // Shouldn't happen.
                Slog.e(LOG_TAG, "Failed to getBlockUninstallForUser", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
        return false;
    }

    @Override
    public void setCrossProfileCallerIdDisabled(ComponentName who, boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (admin.disableCallerId != disabled) {
                admin.disableCallerId = disabled;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public boolean getCrossProfileCallerIdDisabled(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.disableCallerId;
        }
    }

    @Override
    public boolean getCrossProfileCallerIdDisabledForUser(int userId) {
        enforceCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            return (admin != null) ? admin.disableCallerId : false;
        }
    }

    @Override
    public void setCrossProfileContactsSearchDisabled(ComponentName who, boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (admin.disableContactsSearch != disabled) {
                admin.disableContactsSearch = disabled;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public boolean getCrossProfileContactsSearchDisabled(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.disableContactsSearch;
        }
    }

    @Override
    public boolean getCrossProfileContactsSearchDisabledForUser(int userId) {
        enforceCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            return (admin != null) ? admin.disableContactsSearch : false;
        }
    }

    @Override
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            boolean isContactIdIgnored, long actualDirectoryId, Intent originalIntent) {
        final Intent intent = QuickContact.rebuildManagedQuickContactsIntent(actualLookupKey,
                actualContactId, isContactIdIgnored, actualDirectoryId, originalIntent);
        final int callingUserId = UserHandle.getCallingUserId();

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                final int managedUserId = getManagedUserId(callingUserId);
                if (managedUserId < 0) {
                    return;
                }
                if (isCrossProfileQuickContactDisabled(managedUserId)) {
                    if (VERBOSE_LOG) {
                        Log.v(LOG_TAG,
                                "Cross-profile contacts access disabled for user " + managedUserId);
                    }
                    return;
                }
                ContactsInternal.startQuickContactWithErrorToastForUser(
                        mContext, intent, new UserHandle(managedUserId));
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    /**
     * @return true if cross-profile QuickContact is disabled
     */
    private boolean isCrossProfileQuickContactDisabled(int userId) {
        return getCrossProfileCallerIdDisabledForUser(userId)
                && getCrossProfileContactsSearchDisabledForUser(userId);
    }

    /**
     * @return the user ID of the managed user that is linked to the current user, if any.
     * Otherwise -1.
     */
    public int getManagedUserId(int callingUserId) {
        if (VERBOSE_LOG) {
            Log.v(LOG_TAG, "getManagedUserId: callingUserId=" + callingUserId);
        }

        for (UserInfo ui : mUserManager.getProfiles(callingUserId)) {
            if (ui.id == callingUserId || !ui.isManagedProfile()) {
                continue; // Caller user self, or not a managed profile.  Skip.
            }
            if (VERBOSE_LOG) {
                Log.v(LOG_TAG, "Managed user=" + ui.id);
            }
            return ui.id;
        }
        if (VERBOSE_LOG) {
            Log.v(LOG_TAG, "Managed user not found.");
        }
        return -1;
    }

    @Override
    public void setBluetoothContactSharingDisabled(ComponentName who, boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (admin.disableBluetoothContactSharing != disabled) {
                admin.disableBluetoothContactSharing = disabled;
                saveSettingsLocked(UserHandle.getCallingUserId());
            }
        }
    }

    @Override
    public boolean getBluetoothContactSharingDisabled(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.disableBluetoothContactSharing;
        }
    }

    @Override
    public boolean getBluetoothContactSharingDisabledForUser(int userId) {
        // TODO: Should there be a check to make sure this relationship is
        // within a profile group?
        // enforceSystemProcess("getCrossProfileCallerIdDisabled can only be called by system");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            return (admin != null) ? admin.disableBluetoothContactSharing : false;
        }
    }

    @Override
    public void setLockTaskPackages(ComponentName who, String[] packages)
            throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(packages, "packages is null");

        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            final int userHandle = mInjector.userHandleGetCallingUserId();
            setLockTaskPackagesLocked(userHandle, new ArrayList<>(Arrays.asList(packages)));
        }
    }

    private void setLockTaskPackagesLocked(int userHandle, List<String> packages) {
        DevicePolicyData policy = getUserData(userHandle);
        policy.mLockTaskPackages = packages;

        // Store the settings persistently.
        saveSettingsLocked(userHandle);
        updateLockTaskPackagesLocked(packages, userHandle);
    }

    @Override
    public String[] getLockTaskPackages(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");

        final int userHandle = mInjector.binderGetCallingUserHandle().getIdentifier();
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            final List<String> packages = getUserData(userHandle).mLockTaskPackages;
            return packages.toArray(new String[packages.size()]);
        }
    }

    @Override
    public boolean isLockTaskPermitted(String pkg) {
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            return getUserData(userHandle).mLockTaskPackages.contains(pkg);
        }
    }

    @Override
    public void setLockTaskFeatures(ComponentName who, int flags) {
        Preconditions.checkNotNull(who, "ComponentName is null");

        // Throw if Overview is used without Home.
        boolean hasHome = (flags & LOCK_TASK_FEATURE_HOME) != 0;
        boolean hasOverview = (flags & LOCK_TASK_FEATURE_OVERVIEW) != 0;
        Preconditions.checkArgument(hasHome || !hasOverview,
                "Cannot use LOCK_TASK_FEATURE_OVERVIEW without LOCK_TASK_FEATURE_HOME");
        boolean hasNotification = (flags & LOCK_TASK_FEATURE_NOTIFICATIONS) != 0;
        Preconditions.checkArgument(hasHome || !hasNotification,
            "Cannot use LOCK_TASK_FEATURE_NOTIFICATIONS without LOCK_TASK_FEATURE_HOME");

        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            setLockTaskFeaturesLocked(userHandle, flags);
        }
    }

    private void setLockTaskFeaturesLocked(int userHandle, int flags) {
        DevicePolicyData policy = getUserData(userHandle);
        policy.mLockTaskFeatures = flags;
        saveSettingsLocked(userHandle);
        updateLockTaskFeaturesLocked(flags, userHandle);
    }

    @Override
    public int getLockTaskFeatures(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            enforceCanCallLockTaskLocked(who);
            return getUserData(userHandle).mLockTaskFeatures;
        }
    }

    private void maybeClearLockTaskPolicyLocked() {
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            final List<UserInfo> userInfos = mUserManager.getUsers(/*excludeDying=*/ true);
            for (int i = userInfos.size() - 1; i >= 0; i--) {
                int userId = userInfos.get(i).id;
                if (canUserUseLockTaskLocked(userId)) {
                    continue;
                }

                final List<String> lockTaskPackages = getUserData(userId).mLockTaskPackages;
                if (!lockTaskPackages.isEmpty()) {
                    Slog.d(LOG_TAG,
                            "User id " + userId + " not affiliated. Clearing lock task packages");
                    setLockTaskPackagesLocked(userId, Collections.<String>emptyList());
                }
                final int lockTaskFeatures = getUserData(userId).mLockTaskFeatures;
                if (lockTaskFeatures != DevicePolicyManager.LOCK_TASK_FEATURE_NONE){
                    Slog.d(LOG_TAG,
                            "User id " + userId + " not affiliated. Clearing lock task features");
                    setLockTaskFeaturesLocked(userId, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userHandle) {
        if (!isCallerWithSystemUid()) {
            throw new SecurityException("notifyLockTaskModeChanged can only be called by system");
        }
        synchronized (getLockObject()) {
            final DevicePolicyData policy = getUserData(userHandle);

            if (policy.mStatusBarDisabled) {
                // Status bar is managed by LockTaskController during LockTask, so we cancel this
                // policy when LockTask starts, and reapply it when LockTask ends
                setStatusBarDisabledInternal(!isEnabled, userHandle);
            }

            Bundle adminExtras = new Bundle();
            adminExtras.putString(DeviceAdminReceiver.EXTRA_LOCK_TASK_PACKAGE, pkg);
            for (ActiveAdmin admin : policy.mAdminList) {
                final boolean ownsDevice = isDeviceOwner(admin.info.getComponent(), userHandle);
                final boolean ownsProfile = isProfileOwner(admin.info.getComponent(), userHandle);
                if (ownsDevice || ownsProfile) {
                    if (isEnabled) {
                        sendAdminCommandLocked(admin, DeviceAdminReceiver.ACTION_LOCK_TASK_ENTERING,
                                adminExtras, null);
                    } else {
                        sendAdminCommandLocked(admin, DeviceAdminReceiver.ACTION_LOCK_TASK_EXITING);
                    }
                }
            }
        }
    }

    @Override
    public void setGlobalSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            // Some settings are no supported any more. However we do not want to throw a
            // SecurityException to avoid breaking apps.
            if (GLOBAL_SETTINGS_DEPRECATED.contains(setting)) {
                Log.i(LOG_TAG, "Global setting no longer supported: " + setting);
                return;
            }

            if (!GLOBAL_SETTINGS_WHITELIST.contains(setting)
                    && !UserManager.isDeviceInDemoMode(mContext)) {
                throw new SecurityException(String.format(
                        "Permission denial: device owners cannot update %1$s", setting));
            }

            if (Settings.Global.STAY_ON_WHILE_PLUGGED_IN.equals(setting)) {
                // ignore if it contradicts an existing policy
                long timeMs = getMaximumTimeToLock(
                        who, mInjector.userHandleGetCallingUserId(), /* parent */ false);
                if (timeMs > 0 && timeMs < Long.MAX_VALUE) {
                    return;
                }
            }

            long id = mInjector.binderClearCallingIdentity();
            try {
                mInjector.settingsGlobalPutString(setting, value);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setSystemSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkStringNotEmpty(setting, "String setting is null or empty");

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            if (!SYSTEM_SETTINGS_WHITELIST.contains(setting)) {
                throw new SecurityException(String.format(
                        "Permission denial: device owners cannot update %1$s", setting));
            }

            final int callingUserId = mInjector.userHandleGetCallingUserId();

            mInjector.binderWithCleanCallingIdentity(() ->
                mInjector.settingsSystemPutStringForUser(setting, value, callingUserId));
        }
    }

    @Override
    public boolean setTime(ComponentName who, long millis) {
        Preconditions.checkNotNull(who, "ComponentName is null in setTime");
        getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        // Don't allow set time when auto time is on.
        if (mInjector.settingsGlobalGetInt(Global.AUTO_TIME, 0) == 1) {
            return false;
        }
        mInjector.binderWithCleanCallingIdentity(() -> mInjector.getAlarmManager().setTime(millis));
        return true;
    }

    @Override
    public boolean setTimeZone(ComponentName who, String timeZone) {
        Preconditions.checkNotNull(who, "ComponentName is null in setTimeZone");
        getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        // Don't allow set timezone when auto timezone is on.
        if (mInjector.settingsGlobalGetInt(Global.AUTO_TIME_ZONE, 0) == 1) {
            return false;
        }
        mInjector.binderWithCleanCallingIdentity(() ->
            mInjector.getAlarmManager().setTimeZone(timeZone));
        return true;
    }

    @Override
    public void setSecureSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = mInjector.userHandleGetCallingUserId();

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            if (isDeviceOwner(who, callingUserId)) {
                if (!SECURE_SETTINGS_DEVICEOWNER_WHITELIST.contains(setting)
                        && !isCurrentUserDemo()) {
                    throw new SecurityException(String.format(
                            "Permission denial: Device owners cannot update %1$s", setting));
                }
            } else if (!SECURE_SETTINGS_WHITELIST.contains(setting) && !isCurrentUserDemo()) {
                throw new SecurityException(String.format(
                        "Permission denial: Profile owners cannot update %1$s", setting));
            }
            if (setting.equals(Settings.Secure.INSTALL_NON_MARKET_APPS)) {
                if (getTargetSdk(who.getPackageName(), callingUserId) >= Build.VERSION_CODES.O) {
                    throw new UnsupportedOperationException(Settings.Secure.INSTALL_NON_MARKET_APPS
                            + " is deprecated. Please use the user restriction "
                            + UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES + " instead.");
                }
                if (!mUserManager.isManagedProfile(callingUserId)) {
                    Slog.e(LOG_TAG, "Ignoring setSecureSetting request for "
                            + setting + ". User restriction "
                            + UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
                            + " should be used instead.");
                } else {
                    try {
                        setUserRestriction(who, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                                (Integer.parseInt(value) == 0) ? true : false);
                    } catch (NumberFormatException exc) {
                        Slog.e(LOG_TAG, "Invalid value: " + value + " for setting " + setting);
                    }
                }
                return;
            }
            long id = mInjector.binderClearCallingIdentity();
            try {
                if (Settings.Secure.DEFAULT_INPUT_METHOD.equals(setting)) {
                    final String currentValue = mInjector.settingsSecureGetStringForUser(
                            Settings.Secure.DEFAULT_INPUT_METHOD, callingUserId);
                    if (!TextUtils.equals(currentValue, value)) {
                        // Tell the content observer that the next change will be due to the owner
                        // changing the value. There is a small race condition here that we cannot
                        // avoid: Change notifications are sent asynchronously, so it is possible
                        // that there are prior notifications queued up before the one we are about
                        // to trigger. This is a corner case that will have no impact in practice.
                        mSetupContentObserver.addPendingChangeByOwnerLocked(callingUserId);
                    }
                    getUserData(callingUserId).mCurrentInputMethodSet = true;
                    saveSettingsLocked(callingUserId);
                }
                mInjector.settingsSecurePutStringForUser(setting, value, callingUserId);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setMasterVolumeMuted(ComponentName who, boolean on) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            setUserRestriction(who, UserManager.DISALLOW_UNMUTE_DEVICE, on);
        }
    }

    @Override
    public boolean isMasterVolumeMuted(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            return audioManager.isMasterMute();
        }
    }

    @Override
    public void setUserIcon(ComponentName who, Bitmap icon) {
        synchronized (getLockObject()) {
            Preconditions.checkNotNull(who, "ComponentName is null");
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            int userId = UserHandle.getCallingUserId();
            long id = mInjector.binderClearCallingIdentity();
            try {
                mUserManagerInternal.setUserIcon(userId, icon);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public boolean setKeyguardDisabled(ComponentName who, boolean disabled) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (!isUserAffiliatedWithDeviceLocked(userId)) {
                throw new SecurityException("Admin " + who +
                        " is neither the device owner or affiliated user's profile owner.");
            }
        }
        if (isManagedProfile(userId)) {
            throw new SecurityException("Managed profile cannot disable keyguard");
        }

        long ident = mInjector.binderClearCallingIdentity();
        try {
            // disallow disabling the keyguard if a password is currently set
            if (disabled && mLockPatternUtils.isSecure(userId)) {
                return false;
            }
            mLockPatternUtils.setLockScreenDisabled(disabled, userId);
            mInjector.getIWindowManager().dismissKeyguard(null /* callback */, null /* message */);
        } catch (RemoteException e) {
            // Same process, does not happen.
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
        return true;
    }

    @Override
    public boolean setStatusBarDisabled(ComponentName who, boolean disabled) {
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (!isUserAffiliatedWithDeviceLocked(userId)) {
                throw new SecurityException("Admin " + who +
                        " is neither the device owner or affiliated user's profile owner.");
            }
            if (isManagedProfile(userId)) {
                throw new SecurityException("Managed profile cannot disable status bar");
            }
            DevicePolicyData policy = getUserData(userId);
            if (policy.mStatusBarDisabled != disabled) {
                boolean isLockTaskMode = false;
                try {
                    isLockTaskMode = mInjector.getIActivityManager().getLockTaskModeState()
                            != LOCK_TASK_MODE_NONE;
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Failed to get LockTask mode");
                }
                if (!isLockTaskMode) {
                    if (!setStatusBarDisabledInternal(disabled, userId)) {
                        return false;
                    }
                }
                policy.mStatusBarDisabled = disabled;
                saveSettingsLocked(userId);
            }
        }
        return true;
    }

    private boolean setStatusBarDisabledInternal(boolean disabled, int userId) {
        long ident = mInjector.binderClearCallingIdentity();
        try {
            IStatusBarService statusBarService = IStatusBarService.Stub.asInterface(
                    ServiceManager.checkService(Context.STATUS_BAR_SERVICE));
            if (statusBarService != null) {
                int flags1 = disabled ? STATUS_BAR_DISABLE_MASK : StatusBarManager.DISABLE_NONE;
                int flags2 = disabled ? STATUS_BAR_DISABLE2_MASK : StatusBarManager.DISABLE2_NONE;
                statusBarService.disableForUser(flags1, mToken, mContext.getPackageName(), userId);
                statusBarService.disable2ForUser(flags2, mToken, mContext.getPackageName(), userId);
                return true;
            }
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Failed to disable the status bar", e);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
        return false;
    }

    /**
     * We need to update the internal state of whether a user has completed setup or a
     * device has paired once. After that, we ignore any changes that reset the
     * Settings.Secure.USER_SETUP_COMPLETE or Settings.Secure.DEVICE_PAIRED change
     * as we don't trust any apps that might try to reset them.
     * <p>
     * Unfortunately, we don't know which user's setup state was changed, so we write all of
     * them.
     */
    void updateUserSetupCompleteAndPaired() {
        List<UserInfo> users = mUserManager.getUsers(true);
        final int N = users.size();
        for (int i = 0; i < N; i++) {
            int userHandle = users.get(i).id;
            if (mInjector.settingsSecureGetIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 0,
                    userHandle) != 0) {
                DevicePolicyData policy = getUserData(userHandle);
                if (!policy.mUserSetupComplete) {
                    policy.mUserSetupComplete = true;
                    synchronized (getLockObject()) {
                        saveSettingsLocked(userHandle);
                    }
                }
            }
            if (mIsWatch && mInjector.settingsSecureGetIntForUser(Settings.Secure.DEVICE_PAIRED, 0,
                    userHandle) != 0) {
                DevicePolicyData policy = getUserData(userHandle);
                if (!policy.mPaired) {
                    policy.mPaired = true;
                    synchronized (getLockObject()) {
                        saveSettingsLocked(userHandle);
                    }
                }
            }
        }
    }

    private class SetupContentObserver extends ContentObserver {
        private final Uri mUserSetupComplete = Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE);
        private final Uri mDeviceProvisioned = Settings.Global.getUriFor(
                Settings.Global.DEVICE_PROVISIONED);
        private final Uri mPaired = Settings.Secure.getUriFor(Settings.Secure.DEVICE_PAIRED);
        private final Uri mDefaultImeChanged = Settings.Secure.getUriFor(
                Settings.Secure.DEFAULT_INPUT_METHOD);

        @GuardedBy("getLockObject()")
        private Set<Integer> mUserIdsWithPendingChangesByOwner = new ArraySet<>();

        public SetupContentObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mInjector.registerContentObserver(mUserSetupComplete, false, this, UserHandle.USER_ALL);
            mInjector.registerContentObserver(mDeviceProvisioned, false, this, UserHandle.USER_ALL);
            if (mIsWatch) {
                mInjector.registerContentObserver(mPaired, false, this, UserHandle.USER_ALL);
            }
            mInjector.registerContentObserver(mDefaultImeChanged, false, this, UserHandle.USER_ALL);
        }

        @GuardedBy("getLockObject()")
        private void addPendingChangeByOwnerLocked(int userId) {
            mUserIdsWithPendingChangesByOwner.add(userId);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mUserSetupComplete.equals(uri) || (mIsWatch && mPaired.equals(uri))) {
                updateUserSetupCompleteAndPaired();
            } else if (mDeviceProvisioned.equals(uri)) {
                synchronized (getLockObject()) {
                    // Set PROPERTY_DEVICE_OWNER_PRESENT, for the SUW case where setting the property
                    // is delayed until device is marked as provisioned.
                    setDeviceOwnerSystemPropertyLocked();
                }
            } else if (mDefaultImeChanged.equals(uri)) {
                synchronized (getLockObject()) {
                    if (mUserIdsWithPendingChangesByOwner.contains(userId)) {
                        // This change notification was triggered by the owner changing the current
                        // IME. Ignore it.
                        mUserIdsWithPendingChangesByOwner.remove(userId);
                    } else {
                        // This change notification was triggered by the user manually changing the
                        // current IME.
                        getUserData(userId).mCurrentInputMethodSet = false;
                        saveSettingsLocked(userId);
                    }
                }
            }
        }
    }

    @VisibleForTesting
    final class LocalService extends DevicePolicyManagerInternal {
        private List<OnCrossProfileWidgetProvidersChangeListener> mWidgetProviderListeners;

        @Override
        public List<String> getCrossProfileWidgetProviders(int profileId) {
            synchronized (getLockObject()) {
                if (mOwners == null) {
                    return Collections.emptyList();
                }
                ComponentName ownerComponent = mOwners.getProfileOwnerComponent(profileId);
                if (ownerComponent == null) {
                    return Collections.emptyList();
                }

                DevicePolicyData policy = getUserDataUnchecked(profileId);
                ActiveAdmin admin = policy.mAdminMap.get(ownerComponent);

                if (admin == null || admin.crossProfileWidgetProviders == null
                        || admin.crossProfileWidgetProviders.isEmpty()) {
                    return Collections.emptyList();
                }

                return admin.crossProfileWidgetProviders;
            }
        }

        @Override
        public void addOnCrossProfileWidgetProvidersChangeListener(
                OnCrossProfileWidgetProvidersChangeListener listener) {
            synchronized (getLockObject()) {
                if (mWidgetProviderListeners == null) {
                    mWidgetProviderListeners = new ArrayList<>();
                }
                if (!mWidgetProviderListeners.contains(listener)) {
                    mWidgetProviderListeners.add(listener);
                }
            }
        }

        @Override
        public boolean isActiveAdminWithPolicy(int uid, int reqPolicy) {
            synchronized (getLockObject()) {
                return getActiveAdminWithPolicyForUidLocked(null, reqPolicy, uid) != null;
            }
        }

        private void notifyCrossProfileProvidersChanged(int userId, List<String> packages) {
            final List<OnCrossProfileWidgetProvidersChangeListener> listeners;
            synchronized (getLockObject()) {
                listeners = new ArrayList<>(mWidgetProviderListeners);
            }
            final int listenerCount = listeners.size();
            for (int i = 0; i < listenerCount; i++) {
                OnCrossProfileWidgetProvidersChangeListener listener = listeners.get(i);
                listener.onCrossProfileWidgetProvidersChanged(userId, packages);
            }
        }

        @Override
        public Intent createShowAdminSupportIntent(int userId, boolean useDefaultIfNoAdmin) {
            // This method is called from AM with its lock held, so don't take the DPMS lock.
            // b/29242568

            ComponentName profileOwner = mOwners.getProfileOwnerComponent(userId);
            if (profileOwner != null) {
                return DevicePolicyManagerService.this
                        .createShowAdminSupportIntent(profileOwner, userId);
            }

            final Pair<Integer, ComponentName> deviceOwner =
                    mOwners.getDeviceOwnerUserIdAndComponent();
            if (deviceOwner != null && deviceOwner.first == userId) {
                return DevicePolicyManagerService.this
                        .createShowAdminSupportIntent(deviceOwner.second, userId);
            }

            // We're not specifying the device admin because there isn't one.
            if (useDefaultIfNoAdmin) {
                return DevicePolicyManagerService.this.createShowAdminSupportIntent(null, userId);
            }
            return null;
        }

        @Override
        public Intent createUserRestrictionSupportIntent(int userId, String userRestriction) {
            int source;
            long ident = mInjector.binderClearCallingIdentity();
            try {
                source = mUserManager.getUserRestrictionSource(userRestriction,
                        UserHandle.of(userId));
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
            if ((source & UserManager.RESTRICTION_SOURCE_SYSTEM) != 0) {
                /*
                 * In this case, the user restriction is enforced by the system.
                 * So we won't show an admin support intent, even if it is also
                 * enforced by a profile/device owner.
                 */
                return null;
            }
            boolean enforcedByDo = (source & UserManager.RESTRICTION_SOURCE_DEVICE_OWNER) != 0;
            boolean enforcedByPo = (source & UserManager.RESTRICTION_SOURCE_PROFILE_OWNER) != 0;
            if (enforcedByDo && enforcedByPo) {
                // In this case, we'll show an admin support dialog that does not
                // specify the admin.
                return DevicePolicyManagerService.this.createShowAdminSupportIntent(null, userId);
            } else if (enforcedByPo) {
                final ComponentName profileOwner = mOwners.getProfileOwnerComponent(userId);
                if (profileOwner != null) {
                    return DevicePolicyManagerService.this
                            .createShowAdminSupportIntent(profileOwner, userId);
                }
                // This could happen if another thread has changed the profile owner since we called
                // getUserRestrictionSource
                return null;
            } else if (enforcedByDo) {
                final Pair<Integer, ComponentName> deviceOwner
                        = mOwners.getDeviceOwnerUserIdAndComponent();
                if (deviceOwner != null) {
                    return DevicePolicyManagerService.this
                            .createShowAdminSupportIntent(deviceOwner.second, deviceOwner.first);
                }
                // This could happen if another thread has changed the device owner since we called
                // getUserRestrictionSource
                return null;
            }
            return null;
        }

        @Override
        public boolean isUserAffiliatedWithDevice(int userId) {
            return DevicePolicyManagerService.this.isUserAffiliatedWithDeviceLocked(userId);
        }

        @Override
        public void reportSeparateProfileChallengeChanged(@UserIdInt int userId) {
            synchronized (getLockObject()) {
                updateMaximumTimeToLockLocked(userId);
            }
        }

        @Override
        public boolean canUserHaveUntrustedCredentialReset(@UserIdInt int userId) {
            return DevicePolicyManagerService.this.canUserHaveUntrustedCredentialReset(userId);
        }

        @Override
        public CharSequence getPrintingDisabledReasonForUser(@UserIdInt int userId) {
            synchronized (getLockObject()) {
                DevicePolicyData policy = getUserData(userId);
                if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_PRINTING,
                        UserHandle.of(userId))) {
                    Log.e(LOG_TAG, "printing is enabled");
                    return null;
                }
                String ownerPackage = mOwners.getProfileOwnerPackage(userId);
                if (ownerPackage == null) {
                    ownerPackage = mOwners.getDeviceOwnerPackageName();
                }
                PackageManager pm = mInjector.getPackageManager();
                PackageInfo packageInfo;
                try {
                    packageInfo = pm.getPackageInfo(ownerPackage, 0);
                } catch (NameNotFoundException e) {
                    Log.e(LOG_TAG, "getPackageInfo error", e);
                    return null;
                }
                if (packageInfo == null) {
                    Log.e(LOG_TAG, "packageInfo is inexplicably null");
                    return null;
                }
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                if (appInfo == null) {
                    Log.e(LOG_TAG, "appInfo is inexplicably null");
                    return null;
                }
                CharSequence appLabel = pm.getApplicationLabel(appInfo);
                if (appLabel == null) {
                    Log.e(LOG_TAG, "appLabel is inexplicably null");
                    return null;
                }
                return ((Context) ActivityThread.currentActivityThread().getSystemUiContext())
                        .getResources().getString(R.string.printing_disabled_by, appLabel);
            }
        }

        @Override
        protected DevicePolicyCache getDevicePolicyCache() {
            return mPolicyCache;
        }
    }

    private Intent createShowAdminSupportIntent(ComponentName admin, int userId) {
        // This method is called with AMS lock held, so don't take DPMS lock
        final Intent intent = new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
        intent.putExtra(Intent.EXTRA_USER_ID, userId);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public Intent createAdminSupportIntent(String restriction) {
        Preconditions.checkNotNull(restriction);
        final int uid = mInjector.binderGetCallingUid();
        final int userId = UserHandle.getUserId(uid);
        Intent intent = null;
        if (DevicePolicyManager.POLICY_DISABLE_CAMERA.equals(restriction) ||
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE.equals(restriction) ||
                DevicePolicyManager.POLICY_MANDATORY_BACKUPS.equals(restriction)) {
            synchronized (getLockObject()) {
                final DevicePolicyData policy = getUserData(userId);
                final int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    final ActiveAdmin admin = policy.mAdminList.get(i);
                    if ((admin.disableCamera &&
                                DevicePolicyManager.POLICY_DISABLE_CAMERA.equals(restriction)) ||
                        (admin.disableScreenCapture && DevicePolicyManager
                                .POLICY_DISABLE_SCREEN_CAPTURE.equals(restriction)) ||
                        (admin.mandatoryBackupTransport != null && DevicePolicyManager
                                .POLICY_MANDATORY_BACKUPS.equals(restriction))) {
                        intent = createShowAdminSupportIntent(admin.info.getComponent(), userId);
                        break;
                    }
                }
                // For the camera, a device owner on a different user can disable it globally,
                // so we need an additional check.
                if (intent == null
                        && DevicePolicyManager.POLICY_DISABLE_CAMERA.equals(restriction)) {
                    final ActiveAdmin admin = getDeviceOwnerAdminLocked();
                    if (admin != null && admin.disableCamera) {
                        intent = createShowAdminSupportIntent(admin.info.getComponent(),
                                mOwners.getDeviceOwnerUserId());
                    }
                }
            }
        } else {
            // if valid, |restriction| can only be a user restriction
            intent = mLocalService.createUserRestrictionSupportIntent(userId, restriction);
        }
        if (intent != null) {
            intent.putExtra(DevicePolicyManager.EXTRA_RESTRICTION, restriction);
        }
        return intent;
    }

    /**
     * Returns true if specified admin is allowed to limit passwords and has a
     * {@code minimumPasswordMetrics.quality} of at least {@code minPasswordQuality}
     */
    private static boolean isLimitPasswordAllowed(ActiveAdmin admin, int minPasswordQuality) {
        if (admin.minimumPasswordMetrics.quality < minPasswordQuality) {
            return false;
        }
        return admin.info.usesPolicy(DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
    }

    @Override
    public void setSystemUpdatePolicy(ComponentName who, SystemUpdatePolicy policy) {
        if (policy != null) {
            // throws exception if policy type is invalid
            policy.validateType();
            // throws exception if freeze period is invalid
            policy.validateFreezePeriods();
            Pair<LocalDate, LocalDate> record = mOwners.getSystemUpdateFreezePeriodRecord();
            // throws exception if freeze period is incompatible with previous freeze period record
            policy.validateAgainstPreviousFreezePeriod(record.first, record.second,
                    LocalDate.now());
        }
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            if (policy == null) {
                mOwners.clearSystemUpdatePolicy();
            } else {
                mOwners.setSystemUpdatePolicy(policy);
                updateSystemUpdateFreezePeriodsRecord(/* saveIfChanged */ false);
            }
            mOwners.writeDeviceOwner();
        }
        mContext.sendBroadcastAsUser(
                new Intent(DevicePolicyManager.ACTION_SYSTEM_UPDATE_POLICY_CHANGED),
                UserHandle.SYSTEM);
    }

    @Override
    public SystemUpdatePolicy getSystemUpdatePolicy() {
        synchronized (getLockObject()) {
            SystemUpdatePolicy policy =  mOwners.getSystemUpdatePolicy();
            if (policy != null && !policy.isValid()) {
                Slog.w(LOG_TAG, "Stored system update policy is invalid, return null instead.");
                return null;
            }
            return policy;
        }
    }

    private static boolean withinRange(Pair<LocalDate, LocalDate> range, LocalDate date) {
        return (!date.isBefore(range.first) && !date.isAfter(range.second));
    }

    /**
     * keeps track of the last continuous period when the system is under OTA freeze.
     *
     * DPMS keeps track of the previous dates during which OTA was freezed as a result of an
     * system update policy with freeze periods in effect. This is needed to make robust
     * validation on new system update polices, for example to prevent the OTA from being
     * frozen for more than 90 days if the DPC keeps resetting a new 24-hour freeze period
     * on midnight everyday, or having freeze periods closer than 60 days apart by DPC resetting
     * a new freeze period after a few days.
     *
     * @param saveIfChanged whether to persist the result on disk if freeze period record is
     *            updated. This should only be set to {@code false} if there is a guaranteed
     *            mOwners.writeDeviceOwner() later in the control flow to reduce the number of
     *            disk writes. Otherwise you risk inconsistent on-disk state.
     *
     * @see SystemUpdatePolicy#validateAgainstPreviousFreezePeriod
     */
    private void updateSystemUpdateFreezePeriodsRecord(boolean saveIfChanged) {
        Slog.d(LOG_TAG, "updateSystemUpdateFreezePeriodsRecord");
        synchronized (getLockObject()) {
            final SystemUpdatePolicy policy = mOwners.getSystemUpdatePolicy();
            if (policy == null) {
                return;
            }
            final LocalDate now = LocalDate.now();
            final Pair<LocalDate, LocalDate> currentPeriod = policy.getCurrentFreezePeriod(now);
            if (currentPeriod == null) {
                return;
            }
            final Pair<LocalDate, LocalDate> record = mOwners.getSystemUpdateFreezePeriodRecord();
            final LocalDate start = record.first;
            final LocalDate end = record.second;
            final boolean changed;
            if (end == null || start == null) {
                // Start a new period if there is none at the moment
                changed = mOwners.setSystemUpdateFreezePeriodRecord(now, now);
            } else if (now.equals(end.plusDays(1))) {
                // Extend the existing period
                changed = mOwners.setSystemUpdateFreezePeriodRecord(start, now);
            } else if (now.isAfter(end.plusDays(1))) {
                if (withinRange(currentPeriod, start) && withinRange(currentPeriod, end)) {
                    // The device might be off for some period. If the past freeze record
                    // is within range of the current freeze period, assume the device was off
                    // during the period [end, now] and extend the freeze record to [start, now].
                    changed = mOwners.setSystemUpdateFreezePeriodRecord(start, now);
                } else {
                    changed = mOwners.setSystemUpdateFreezePeriodRecord(now, now);
                }
            } else if (now.isBefore(start)) {
                // Systm clock was adjusted backwards, restart record
                changed = mOwners.setSystemUpdateFreezePeriodRecord(now, now);
            } else /* start <= now <= end */ {
                changed = false;
            }
            if (changed && saveIfChanged) {
                mOwners.writeDeviceOwner();
            }
        }
    }

    @Override
    public void clearSystemUpdatePolicyFreezePeriodRecord() {
        enforceShell("clearSystemUpdatePolicyFreezePeriodRecord");
        synchronized (getLockObject()) {
            // Print out current record to help diagnosed CTS failures
            Slog.i(LOG_TAG, "Clear freeze period record: "
                    + mOwners.getSystemUpdateFreezePeriodRecordAsString());
            if (mOwners.setSystemUpdateFreezePeriodRecord(null, null)) {
                mOwners.writeDeviceOwner();
            }
        }
    }

    /**
     * Checks if the caller of the method is the device owner app.
     *
     * @param callerUid UID of the caller.
     * @return true if the caller is the device owner app
     */
    @VisibleForTesting
    boolean isCallerDeviceOwner(int callerUid) {
        synchronized (getLockObject()) {
            if (!mOwners.hasDeviceOwner()) {
                return false;
            }
            if (UserHandle.getUserId(callerUid) != mOwners.getDeviceOwnerUserId()) {
                return false;
            }
            final String deviceOwnerPackageName = mOwners.getDeviceOwnerComponent()
                    .getPackageName();
                try {
                    String[] pkgs = mInjector.getIPackageManager().getPackagesForUid(callerUid);
                    for (String pkg : pkgs) {
                        if (deviceOwnerPackageName.equals(pkg)) {
                            return true;
                        }
                    }
                } catch (RemoteException e) {
                    return false;
                }
        }

        return false;
    }

    @Override
    public void notifyPendingSystemUpdate(@Nullable SystemUpdateInfo info) {
        mContext.enforceCallingOrSelfPermission(permission.NOTIFY_PENDING_SYSTEM_UPDATE,
                "Only the system update service can broadcast update information");

        if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
            Slog.w(LOG_TAG, "Only the system update service in the system user " +
                    "can broadcast update information.");
            return;
        }

        if (!mOwners.saveSystemUpdateInfo(info)) {
            // Pending system update hasn't changed, don't send duplicate notification.
            return;
        }

        final Intent intent = new Intent(DeviceAdminReceiver.ACTION_NOTIFY_PENDING_SYSTEM_UPDATE)
                .putExtra(DeviceAdminReceiver.EXTRA_SYSTEM_UPDATE_RECEIVED_TIME,
                        info == null ? -1 : info.getReceivedTime());

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                // Broadcast to device owner first if there is one.
                if (mOwners.hasDeviceOwner()) {
                    final UserHandle deviceOwnerUser =
                            UserHandle.of(mOwners.getDeviceOwnerUserId());
                    intent.setComponent(mOwners.getDeviceOwnerComponent());
                    mContext.sendBroadcastAsUser(intent, deviceOwnerUser);
                }
            }
            // Get running users.
            final int runningUserIds[];
            try {
                runningUserIds = mInjector.getIActivityManager().getRunningUserIds();
            } catch (RemoteException e) {
                // Shouldn't happen.
                Log.e(LOG_TAG, "Could not retrieve the list of running users", e);
                return;
            }
            // Send broadcasts to corresponding profile owners if any.
            for (final int userId : runningUserIds) {
                synchronized (getLockObject()) {
                    final ComponentName profileOwnerPackage =
                            mOwners.getProfileOwnerComponent(userId);
                    if (profileOwnerPackage != null) {
                        intent.setComponent(profileOwnerPackage);
                        mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
                    }
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public SystemUpdateInfo getPendingSystemUpdate(ComponentName admin) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        enforceProfileOrDeviceOwner(admin);

        return mOwners.getSystemUpdateInfo();
    }

    @Override
    public void setPermissionPolicy(ComponentName admin, String callerPackage, int policy)
            throws RemoteException {
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a permission grant state delegate.
            enforceCanManageScope(admin, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_PERMISSION_GRANT);
            DevicePolicyData userPolicy = getUserData(userId);
            if (userPolicy.mPermissionPolicy != policy) {
                userPolicy.mPermissionPolicy = policy;
                saveSettingsLocked(userId);
            }
        }
    }

    @Override
    public int getPermissionPolicy(ComponentName admin) throws RemoteException {
        int userId = UserHandle.getCallingUserId();
        synchronized (getLockObject()) {
            DevicePolicyData userPolicy = getUserData(userId);
            return userPolicy.mPermissionPolicy;
        }
    }

    @Override
    public boolean setPermissionGrantState(ComponentName admin, String callerPackage,
            String packageName, String permission, int grantState) throws RemoteException {
        UserHandle user = mInjector.binderGetCallingUserHandle();
        synchronized (getLockObject()) {
            // Ensure the caller is a DO/PO or a permission grant state delegate.
            enforceCanManageScope(admin, callerPackage, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER,
                    DELEGATION_PERMISSION_GRANT);
            long ident = mInjector.binderClearCallingIdentity();
            try {
                if (getTargetSdk(packageName, user.getIdentifier())
                        < android.os.Build.VERSION_CODES.M) {
                    return false;
                }
                if (!isRuntimePermission(permission)) {
                    return false;
                }
                final PackageManager packageManager = mInjector.getPackageManager();
                switch (grantState) {
                    case DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED: {
                        mInjector.getPackageManagerInternal().grantRuntimePermission(packageName,
                                permission, user.getIdentifier(), true /* override policy */);
                        packageManager.updatePermissionFlags(permission, packageName,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED, user);
                    } break;

                    case DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED: {
                        mInjector.getPackageManagerInternal().revokeRuntimePermission(packageName,
                                permission, user.getIdentifier(), true /* override policy */);
                        packageManager.updatePermissionFlags(permission, packageName,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED, user);
                    } break;

                    case DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT: {
                        packageManager.updatePermissionFlags(permission, packageName,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED, 0, user);
                    } break;
                }
                return true;
            } catch (SecurityException se) {
                return false;
            } catch (NameNotFoundException e) {
                return false;
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public int getPermissionGrantState(ComponentName admin, String callerPackage,
            String packageName, String permission) throws RemoteException {
        PackageManager packageManager = mInjector.getPackageManager();

        UserHandle user = mInjector.binderGetCallingUserHandle();
        if (!isCallerWithSystemUid()) {
            // Ensure the caller is a DO/PO or a permission grant state delegate.
            enforceCanManageScope(admin, callerPackage,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, DELEGATION_PERMISSION_GRANT);
        }
        synchronized (getLockObject()) {
            long ident = mInjector.binderClearCallingIdentity();
            try {
                int granted = mIPackageManager.checkPermission(permission,
                        packageName, user.getIdentifier());
                int permFlags = packageManager.getPermissionFlags(permission, packageName, user);
                if ((permFlags & PackageManager.FLAG_PERMISSION_POLICY_FIXED)
                        != PackageManager.FLAG_PERMISSION_POLICY_FIXED) {
                    // Not controlled by policy
                    return DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
                } else {
                    // Policy controlled so return result based on permission grant state
                    return granted == PackageManager.PERMISSION_GRANTED
                            ? DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            : DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
                }
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    boolean isPackageInstalledForUser(String packageName, int userHandle) {
        try {
            PackageInfo pi = mInjector.getIPackageManager().getPackageInfo(packageName, 0,
                    userHandle);
            return (pi != null) && (pi.applicationInfo.flags != 0);
        } catch (RemoteException re) {
            throw new RuntimeException("Package manager has died", re);
        }
    }

    public boolean isRuntimePermission(String permissionName) throws NameNotFoundException {
        final PackageManager packageManager = mInjector.getPackageManager();
        PermissionInfo permissionInfo = packageManager.getPermissionInfo(permissionName, 0);
        return (permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_DANGEROUS;
    }

    @Override
    public boolean isProvisioningAllowed(String action, String packageName) {
        Preconditions.checkNotNull(packageName);

        final int callingUid = mInjector.binderGetCallingUid();
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            final int uidForPackage = mInjector.getPackageManager().getPackageUidAsUser(
                    packageName, UserHandle.getUserId(callingUid));
            Preconditions.checkArgument(callingUid == uidForPackage,
                    "Caller uid doesn't match the one for the provided package.");
        } catch (NameNotFoundException e) {
            throw new IllegalArgumentException("Invalid package provided " + packageName, e);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }

        return checkProvisioningPreConditionSkipPermission(action, packageName) == CODE_OK;
    }

    @Override
    public int checkProvisioningPreCondition(String action, String packageName) {
        Preconditions.checkNotNull(packageName);
        enforceCanManageProfileAndDeviceOwners();
        return checkProvisioningPreConditionSkipPermission(action, packageName);
    }

    private int checkProvisioningPreConditionSkipPermission(String action, String packageName) {
        if (!mHasFeature) {
            return CODE_DEVICE_ADMIN_NOT_SUPPORTED;
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        if (action != null) {
            switch (action) {
                case DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE:
                    return checkManagedProfileProvisioningPreCondition(packageName, callingUserId);
                case DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE:
                    return checkDeviceOwnerProvisioningPreCondition(callingUserId);
                case DevicePolicyManager.ACTION_PROVISION_MANAGED_USER:
                    return checkManagedUserProvisioningPreCondition(callingUserId);
                case DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE:
                    return checkManagedShareableDeviceProvisioningPreCondition(callingUserId);
            }
        }
        throw new IllegalArgumentException("Unknown provisioning action " + action);
    }

    /**
     * The device owner can only be set before the setup phase of the primary user has completed,
     * except for adb command if no accounts or additional users are present on the device.
     */
    private int checkDeviceOwnerProvisioningPreConditionLocked(@Nullable ComponentName owner,
            int deviceOwnerUserId, boolean isAdb, boolean hasIncompatibleAccountsOrNonAdb) {
        if (mOwners.hasDeviceOwner()) {
            return CODE_HAS_DEVICE_OWNER;
        }
        if (mOwners.hasProfileOwner(deviceOwnerUserId)) {
            return CODE_USER_HAS_PROFILE_OWNER;
        }
        if (!mUserManager.isUserRunning(new UserHandle(deviceOwnerUserId))) {
            return CODE_USER_NOT_RUNNING;
        }
        if (mIsWatch && hasPaired(UserHandle.USER_SYSTEM)) {
            return CODE_HAS_PAIRED;
        }
        if (isAdb) {
            // if shell command runs after user setup completed check device status. Otherwise, OK.
            if (mIsWatch || hasUserSetupCompleted(UserHandle.USER_SYSTEM)) {
                if (!mInjector.userManagerIsSplitSystemUser()) {
                    if (mUserManager.getUserCount() > 1) {
                        return CODE_NONSYSTEM_USER_EXISTS;
                    }
                    if (hasIncompatibleAccountsOrNonAdb) {
                        return CODE_ACCOUNTS_NOT_EMPTY;
                    }
                } else {
                    // STOPSHIP Do proper check in split user mode
                }
            }
            return CODE_OK;
        } else {
            if (!mInjector.userManagerIsSplitSystemUser()) {
                // In non-split user mode, DO has to be user 0
                if (deviceOwnerUserId != UserHandle.USER_SYSTEM) {
                    return CODE_NOT_SYSTEM_USER;
                }
                // In non-split user mode, only provision DO before setup wizard completes
                if (hasUserSetupCompleted(UserHandle.USER_SYSTEM)) {
                    return CODE_USER_SETUP_COMPLETED;
                }
            } else {
                // STOPSHIP Do proper check in split user mode
            }
            return CODE_OK;
        }
    }

    private int checkDeviceOwnerProvisioningPreCondition(int deviceOwnerUserId) {
        synchronized (getLockObject()) {
            // hasIncompatibleAccountsOrNonAdb doesn't matter since the caller is not adb.
            return checkDeviceOwnerProvisioningPreConditionLocked(/* owner unknown */ null,
                    deviceOwnerUserId, /* isAdb= */ false,
                    /* hasIncompatibleAccountsOrNonAdb=*/ true);
        }
    }

    private int checkManagedProfileProvisioningPreCondition(String packageName, int callingUserId) {
        if (!hasFeatureManagedUsers()) {
            return CODE_MANAGED_USERS_NOT_SUPPORTED;
        }
        if (callingUserId == UserHandle.USER_SYSTEM
                && mInjector.userManagerIsSplitSystemUser()) {
            // Managed-profiles cannot be setup on the system user.
            return CODE_SPLIT_SYSTEM_USER_DEVICE_SYSTEM_USER;
        }
        if (getProfileOwner(callingUserId) != null) {
            // Managed user cannot have a managed profile.
            return CODE_USER_HAS_PROFILE_OWNER;
        }

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            final UserHandle callingUserHandle = UserHandle.of(callingUserId);
            final ComponentName ownerAdmin = getOwnerComponent(packageName, callingUserId);
            if (mUserManager.hasUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE,
                    callingUserHandle)) {
                // An admin can initiate provisioning if it has set the restriction.
                if (ownerAdmin == null || isAdminAffectedByRestriction(ownerAdmin,
                        UserManager.DISALLOW_ADD_MANAGED_PROFILE, callingUserId)) {
                    return CODE_ADD_MANAGED_PROFILE_DISALLOWED;
                }
            }
            boolean canRemoveProfile = true;
            if (mUserManager.hasUserRestriction(UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
                    callingUserHandle)) {
                // We can remove a profile if the admin itself has set the restriction.
                if (ownerAdmin == null || isAdminAffectedByRestriction(ownerAdmin,
                        UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
                        callingUserId)) {
                    canRemoveProfile = false;
                }
            }
            if (!mUserManager.canAddMoreManagedProfiles(callingUserId, canRemoveProfile)) {
                return CODE_CANNOT_ADD_MANAGED_PROFILE;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
        return CODE_OK;
    }

    private ComponentName getOwnerComponent(String packageName, int userId) {
        if (isDeviceOwnerPackage(packageName, userId)) {
            return mOwners.getDeviceOwnerComponent();
        }
        if (isProfileOwnerPackage(packageName, userId)) {
            return mOwners.getProfileOwnerComponent(userId);
        }
        return null;
    }

    /**
     * Return device owner or profile owner set on a given user.
     */
    private @Nullable ComponentName getOwnerComponent(int userId) {
        synchronized (getLockObject()) {
            if (mOwners.getDeviceOwnerUserId() == userId) {
                return mOwners.getDeviceOwnerComponent();
            }
            if (mOwners.hasProfileOwner(userId)) {
                return mOwners.getProfileOwnerComponent(userId);
            }
        }
        return null;
    }

    private int checkManagedUserProvisioningPreCondition(int callingUserId) {
        if (!hasFeatureManagedUsers()) {
            return CODE_MANAGED_USERS_NOT_SUPPORTED;
        }
        if (!mInjector.userManagerIsSplitSystemUser()) {
            // ACTION_PROVISION_MANAGED_USER only supported on split-user systems.
            return CODE_NOT_SYSTEM_USER_SPLIT;
        }
        if (callingUserId == UserHandle.USER_SYSTEM) {
            // System user cannot be a managed user.
            return CODE_SYSTEM_USER;
        }
        if (hasUserSetupCompleted(callingUserId)) {
            return CODE_USER_SETUP_COMPLETED;
        }
        if (mIsWatch && hasPaired(UserHandle.USER_SYSTEM)) {
            return CODE_HAS_PAIRED;
        }
        return CODE_OK;
    }

    private int checkManagedShareableDeviceProvisioningPreCondition(int callingUserId) {
        if (!mInjector.userManagerIsSplitSystemUser()) {
            // ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE only supported on split-user systems.
            return CODE_NOT_SYSTEM_USER_SPLIT;
        }
        return checkDeviceOwnerProvisioningPreCondition(callingUserId);
    }

    private boolean hasFeatureManagedUsers() {
        try {
            return mIPackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS, 0);
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public String getWifiMacAddress(ComponentName admin) {
        // Make sure caller has DO.
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            final WifiInfo wifiInfo = mInjector.getWifiManager().getConnectionInfo();
            if (wifiInfo == null) {
                return null;
            }
            return wifiInfo.hasRealMacAddress() ? wifiInfo.getMacAddress() : null;
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    /**
     * Returns the target sdk version number that the given packageName was built for
     * in the given user.
     */
    private int getTargetSdk(String packageName, int userId) {
        final ApplicationInfo ai;
        try {
            ai = mIPackageManager.getApplicationInfo(packageName, 0, userId);
            final int targetSdkVersion = ai == null ? 0 : ai.targetSdkVersion;
            return targetSdkVersion;
        } catch (RemoteException e) {
            // Shouldn't happen
            return 0;
        }
    }

    @Override
    public boolean isManagedProfile(ComponentName admin) {
        enforceProfileOrDeviceOwner(admin);
        return isManagedProfile(mInjector.userHandleGetCallingUserId());
    }

    @Override
    public boolean isSystemOnlyUser(ComponentName admin) {
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        final int callingUserId = mInjector.userHandleGetCallingUserId();
        return UserManager.isSplitSystemUser() && callingUserId == UserHandle.USER_SYSTEM;
    }

    @Override
    public void reboot(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        // Make sure caller has DO.
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        long ident = mInjector.binderClearCallingIdentity();
        try {
            // Make sure there are no ongoing calls on the device.
            if (mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                throw new IllegalStateException("Cannot be called with ongoing call on the device");
            }
            mInjector.powerManagerReboot(PowerManager.REBOOT_REQUESTED_BY_DEVICE_OWNER);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public void setShortSupportMessage(@NonNull ComponentName who, CharSequence message) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who,
                    mInjector.binderGetCallingUid());
            if (!TextUtils.equals(admin.shortSupportMessage, message)) {
                admin.shortSupportMessage = message;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public CharSequence getShortSupportMessage(@NonNull ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who,
                    mInjector.binderGetCallingUid());
            return admin.shortSupportMessage;
        }
    }

    @Override
    public void setLongSupportMessage(@NonNull ComponentName who, CharSequence message) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who,
                    mInjector.binderGetCallingUid());
            if (!TextUtils.equals(admin.longSupportMessage, message)) {
                admin.longSupportMessage = message;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public CharSequence getLongSupportMessage(@NonNull ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForUidLocked(who,
                    mInjector.binderGetCallingUid());
            return admin.longSupportMessage;
        }
    }

    @Override
    public CharSequence getShortSupportMessageForUser(@NonNull ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!isCallerWithSystemUid()) {
            throw new SecurityException("Only the system can query support message for user");
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin != null) {
                return admin.shortSupportMessage;
            }
        }
        return null;
    }

    @Override
    public CharSequence getLongSupportMessageForUser(@NonNull ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!isCallerWithSystemUid()) {
            throw new SecurityException("Only the system can query support message for user");
        }
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin != null) {
                return admin.longSupportMessage;
            }
        }
        return null;
    }

    @Override
    public void setOrganizationColor(@NonNull ComponentName who, int color) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        enforceManagedProfile(userHandle, "set organization color");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            admin.organizationColor = color;
            saveSettingsLocked(userHandle);
        }
    }

    @Override
    public void setOrganizationColorForUser(int color, int userId) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userId);
        enforceManageUsers();
        enforceManagedProfile(userId, "set organization color");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            admin.organizationColor = color;
            saveSettingsLocked(userId);
        }
    }

    @Override
    public int getOrganizationColor(@NonNull ComponentName who) {
        if (!mHasFeature) {
            return ActiveAdmin.DEF_ORGANIZATION_COLOR;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        enforceManagedProfile(mInjector.userHandleGetCallingUserId(), "get organization color");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.organizationColor;
        }
    }

    @Override
    public int getOrganizationColorForUser(int userHandle) {
        if (!mHasFeature) {
            return ActiveAdmin.DEF_ORGANIZATION_COLOR;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "get organization color");
        synchronized (getLockObject()) {
            ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userHandle);
            return (profileOwner != null)
                    ? profileOwner.organizationColor
                    : ActiveAdmin.DEF_ORGANIZATION_COLOR;
        }
    }

    @Override
    public void setOrganizationName(@NonNull ComponentName who, CharSequence text) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = mInjector.userHandleGetCallingUserId();

        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (!TextUtils.equals(admin.organizationName, text)) {
                admin.organizationName = (text == null || text.length() == 0)
                        ? null : text.toString();
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public CharSequence getOrganizationName(@NonNull ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        enforceManagedProfile(mInjector.userHandleGetCallingUserId(), "get organization name");
        synchronized (getLockObject()) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.organizationName;
        }
    }

    @Override
    public CharSequence getDeviceOwnerOrganizationName() {
        if (!mHasFeature) {
            return null;
        }
        enforceDeviceOwnerOrManageUsers();
        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
            return deviceOwnerAdmin == null ? null : deviceOwnerAdmin.organizationName;
        }
    }

    @Override
    public CharSequence getOrganizationNameForUser(int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "get organization name");
        synchronized (getLockObject()) {
            ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userHandle);
            return (profileOwner != null)
                    ? profileOwner.organizationName
                    : null;
        }
    }

    @Override
    public List<String> setMeteredDataDisabledPackages(ComponentName who, List<String> packageNames) {
        Preconditions.checkNotNull(who);
        Preconditions.checkNotNull(packageNames);

        if (!mHasFeature) {
            return packageNames;
        }
        synchronized (getLockObject()) {
            final ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final int callingUserId = mInjector.userHandleGetCallingUserId();
            final long identity = mInjector.binderClearCallingIdentity();
            try {
                final List<String> excludedPkgs
                        = removeInvalidPkgsForMeteredDataRestriction(callingUserId, packageNames);
                admin.meteredDisabledPackages = packageNames;
                pushMeteredDisabledPackagesLocked(callingUserId);
                saveSettingsLocked(callingUserId);
                return excludedPkgs;
            } finally {
                mInjector.binderRestoreCallingIdentity(identity);
            }
        }
    }

    private List<String> removeInvalidPkgsForMeteredDataRestriction(
            int userId, List<String> pkgNames) {
        final Set<String> activeAdmins = getActiveAdminPackagesLocked(userId);
        final List<String> excludedPkgs = new ArrayList<>();
        for (int i = pkgNames.size() - 1; i >= 0; --i) {
            final String pkgName = pkgNames.get(i);
            // If the package is an active admin, don't restrict it.
            if (activeAdmins.contains(pkgName)) {
                excludedPkgs.add(pkgName);
                continue;
            }
            // If the package doesn't exist, don't restrict it.
            try {
                if (!mInjector.getIPackageManager().isPackageAvailable(pkgName, userId)) {
                    excludedPkgs.add(pkgName);
                }
            } catch (RemoteException e) {
                // Should not happen
            }
        }
        pkgNames.removeAll(excludedPkgs);
        return excludedPkgs;
    }

    @Override
    public List<String> getMeteredDataDisabledPackages(ComponentName who) {
        Preconditions.checkNotNull(who);

        if (!mHasFeature) {
            return new ArrayList<>();
        }
        synchronized (getLockObject()) {
            final ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.meteredDisabledPackages == null
                    ? new ArrayList<>() : admin.meteredDisabledPackages;
        }
    }

    @Override
    public boolean isMeteredDataDisabledPackageForUser(ComponentName who,
            String packageName, int userId) {
        Preconditions.checkNotNull(who);

        if (!mHasFeature) {
            return false;
        }
        if (!isCallerWithSystemUid()) {
            throw new SecurityException(
                    "Only the system can query restricted pkgs for a specific user");
        }
        synchronized (getLockObject()) {
            final ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userId);
            if (admin != null && admin.meteredDisabledPackages != null) {
                return admin.meteredDisabledPackages.contains(packageName);
            }
        }
        return false;
    }

    private void pushMeteredDisabledPackagesLocked(int userId) {
        mInjector.getNetworkPolicyManagerInternal().setMeteredRestrictedPackages(
                getMeteredDisabledPackagesLocked(userId), userId);
    }

    private Set<String> getMeteredDisabledPackagesLocked(int userId) {
        final ComponentName who = getOwnerComponent(userId);
        final Set<String> restrictedPkgs = new ArraySet<>();
        if (who != null) {
            final ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userId);
            if (admin != null && admin.meteredDisabledPackages != null) {
                restrictedPkgs.addAll(admin.meteredDisabledPackages);
            }
        }
        return restrictedPkgs;
    }

    @Override
    public void setAffiliationIds(ComponentName admin, List<String> ids) {
        if (!mHasFeature) {
            return;
        }
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        for (String id : ids) {
            if (TextUtils.isEmpty(id)) {
                throw new IllegalArgumentException("ids must not contain empty string");
            }
        }

        final Set<String> affiliationIds = new ArraySet<>(ids);
        final int callingUserId = mInjector.userHandleGetCallingUserId();
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            getUserData(callingUserId).mAffiliationIds = affiliationIds;
            saveSettingsLocked(callingUserId);
            if (callingUserId != UserHandle.USER_SYSTEM && isDeviceOwner(admin, callingUserId)) {
                // Affiliation ids specified by the device owner are additionally stored in
                // UserHandle.USER_SYSTEM's DevicePolicyData.
                getUserData(UserHandle.USER_SYSTEM).mAffiliationIds = affiliationIds;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }

            // Affiliation status for any user, not just the calling user, might have changed.
            // The device owner user will still be affiliated after changing its affiliation ids,
            // but as a result of that other users might become affiliated or un-affiliated.
            maybePauseDeviceWideLoggingLocked();
            maybeResumeDeviceWideLoggingLocked();
            maybeClearLockTaskPolicyLocked();
        }
    }

    @Override
    public List<String> getAffiliationIds(ComponentName admin) {
        if (!mHasFeature) {
            return Collections.emptyList();
        }

        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return new ArrayList<String>(
                    getUserData(mInjector.userHandleGetCallingUserId()).mAffiliationIds);
        }
    }

    @Override
    public boolean isAffiliatedUser() {
        if (!mHasFeature) {
            return false;
        }

        synchronized (getLockObject()) {
            return isUserAffiliatedWithDeviceLocked(mInjector.userHandleGetCallingUserId());
        }
    }

    private boolean isUserAffiliatedWithDeviceLocked(int userId) {
        if (!mOwners.hasDeviceOwner()) {
            return false;
        }
        if (userId == mOwners.getDeviceOwnerUserId()) {
            // The user that the DO is installed on is always affiliated with the device.
            return true;
        }
        if (userId == UserHandle.USER_SYSTEM) {
            // The system user is always affiliated in a DO device, even if the DO is set on a
            // different user. This could be the case if the DO is set in the primary user
            // of a split user device.
            return true;
        }

        final ComponentName profileOwner = getProfileOwner(userId);
        if (profileOwner == null) {
            return false;
        }

        final Set<String> userAffiliationIds = getUserData(userId).mAffiliationIds;
        final Set<String> deviceAffiliationIds =
                getUserData(UserHandle.USER_SYSTEM).mAffiliationIds;
        for (String id : userAffiliationIds) {
            if (deviceAffiliationIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean areAllUsersAffiliatedWithDeviceLocked() {
        final long ident = mInjector.binderClearCallingIdentity();
        try {
            final List<UserInfo> userInfos = mUserManager.getUsers(/*excludeDying=*/ true);
            for (int i = 0; i < userInfos.size(); i++) {
                int userId = userInfos.get(i).id;
                if (!isUserAffiliatedWithDeviceLocked(userId)) {
                    Slog.d(LOG_TAG, "User id " + userId + " not affiliated.");
                    return false;
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }

        return true;
    }

    @Override
    public void setSecurityLoggingEnabled(ComponentName admin, boolean enabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            if (enabled == mInjector.securityLogGetLoggingEnabledProperty()) {
                return;
            }
            mInjector.securityLogSetLoggingEnabledProperty(enabled);
            if (enabled) {
                mSecurityLogMonitor.start();
                maybePauseDeviceWideLoggingLocked();
            } else {
                mSecurityLogMonitor.stop();
            }
        }
    }

    @Override
    public boolean isSecurityLoggingEnabled(ComponentName admin) {
        if (!mHasFeature) {
            return false;
        }

        synchronized (getLockObject()) {
            if (!isCallerWithSystemUid()) {
                Preconditions.checkNotNull(admin);
                getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            }
            return mInjector.securityLogGetLoggingEnabledProperty();
        }
    }

    private void recordSecurityLogRetrievalTime() {
        synchronized (getLockObject()) {
            final long currentTime = System.currentTimeMillis();
            DevicePolicyData policyData = getUserData(UserHandle.USER_SYSTEM);
            if (currentTime > policyData.mLastSecurityLogRetrievalTime) {
                policyData.mLastSecurityLogRetrievalTime = currentTime;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
        }
    }

    @Override
    public ParceledListSlice<SecurityEvent> retrievePreRebootSecurityLogs(ComponentName admin) {
        if (!mHasFeature) {
            return null;
        }

        Preconditions.checkNotNull(admin);
        ensureDeviceOwnerAndAllUsersAffiliated(admin);

        if (!mContext.getResources().getBoolean(R.bool.config_supportPreRebootSecurityLogs)
                || !mInjector.securityLogGetLoggingEnabledProperty()) {
            return null;
        }

        recordSecurityLogRetrievalTime();

        ArrayList<SecurityEvent> output = new ArrayList<SecurityEvent>();
        try {
            SecurityLog.readPreviousEvents(output);
            return new ParceledListSlice<SecurityEvent>(output);
        } catch (IOException e) {
            Slog.w(LOG_TAG, "Fail to read previous events" , e);
            return new ParceledListSlice<SecurityEvent>(Collections.<SecurityEvent>emptyList());
        }
    }

    @Override
    public ParceledListSlice<SecurityEvent> retrieveSecurityLogs(ComponentName admin) {
        if (!mHasFeature) {
            return null;
        }

        Preconditions.checkNotNull(admin);
        ensureDeviceOwnerAndAllUsersAffiliated(admin);

        if (!mInjector.securityLogGetLoggingEnabledProperty()) {
            return null;
        }

        recordSecurityLogRetrievalTime();

        List<SecurityEvent> logs = mSecurityLogMonitor.retrieveLogs();
        return logs != null ? new ParceledListSlice<SecurityEvent>(logs) : null;
    }

    @Override
    public long forceSecurityLogs() {
        enforceShell("forceSecurityLogs");
        if (!mInjector.securityLogGetLoggingEnabledProperty()) {
            throw new IllegalStateException("logging is not available");
        }
        return mSecurityLogMonitor.forceLogs();
    }

    private void enforceCanManageDeviceAdmin() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEVICE_ADMINS,
                null);
    }

    private void enforceCanManageProfileAndDeviceOwners() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS, null);
    }

    private void enforceCallerSystemUserHandle() {
        final int callingUid = mInjector.binderGetCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        if (userId != UserHandle.USER_SYSTEM) {
            throw new SecurityException("Caller has to be in user 0");
        }
    }

    @Override
    public boolean isUninstallInQueue(final String packageName) {
        enforceCanManageDeviceAdmin();
        final int userId = mInjector.userHandleGetCallingUserId();
        Pair<String, Integer> packageUserPair = new Pair<>(packageName, userId);
        synchronized (getLockObject()) {
            return mPackagesToRemove.contains(packageUserPair);
        }
    }

    @Override
    public void uninstallPackageWithActiveAdmins(final String packageName) {
        enforceCanManageDeviceAdmin();
        Preconditions.checkArgument(!TextUtils.isEmpty(packageName));

        final int userId = mInjector.userHandleGetCallingUserId();

        enforceUserUnlocked(userId);

        final ComponentName profileOwner = getProfileOwner(userId);
        if (profileOwner != null && packageName.equals(profileOwner.getPackageName())) {
            throw new IllegalArgumentException("Cannot uninstall a package with a profile owner");
        }

        final ComponentName deviceOwner = getDeviceOwnerComponent(/* callingUserOnly= */ false);
        if (getDeviceOwnerUserId() == userId && deviceOwner != null
                && packageName.equals(deviceOwner.getPackageName())) {
            throw new IllegalArgumentException("Cannot uninstall a package with a device owner");
        }

        final Pair<String, Integer> packageUserPair = new Pair<>(packageName, userId);
        synchronized (getLockObject()) {
            mPackagesToRemove.add(packageUserPair);
        }

        // All active admins on the user.
        final List<ComponentName> allActiveAdmins = getActiveAdmins(userId);

        // Active admins in the target package.
        final List<ComponentName> packageActiveAdmins = new ArrayList<>();
        if (allActiveAdmins != null) {
            for (ComponentName activeAdmin : allActiveAdmins) {
                if (packageName.equals(activeAdmin.getPackageName())) {
                    packageActiveAdmins.add(activeAdmin);
                    removeActiveAdmin(activeAdmin, userId);
                }
            }
        }
        if (packageActiveAdmins.size() == 0) {
            startUninstallIntent(packageName, userId);
        } else {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    for (ComponentName activeAdmin : packageActiveAdmins) {
                        removeAdminArtifacts(activeAdmin, userId);
                    }
                    startUninstallIntent(packageName, userId);
                }
            }, DEVICE_ADMIN_DEACTIVATE_TIMEOUT); // Start uninstall after timeout anyway.
        }
    }

    @Override
    public boolean isDeviceProvisioned() {
        enforceManageUsers();
        synchronized (getLockObject()) {
            return getUserDataUnchecked(UserHandle.USER_SYSTEM).mUserSetupComplete;
        }
    }

    private boolean isCurrentUserDemo() {
        if (UserManager.isDeviceInDemoMode(mContext)) {
            final int userId = mInjector.userHandleGetCallingUserId();
            final long callingIdentity = mInjector.binderClearCallingIdentity();
            try {
                return mUserManager.getUserInfo(userId).isDemo();
            } finally {
                mInjector.binderRestoreCallingIdentity(callingIdentity);
            }
        }
        return false;
    }

    private void removePackageIfRequired(final String packageName, final int userId) {
        if (!packageHasActiveAdmins(packageName, userId)) {
            // Will not do anything if uninstall was not requested or was already started.
            startUninstallIntent(packageName, userId);
        }
    }

    private void startUninstallIntent(final String packageName, final int userId) {
        final Pair<String, Integer> packageUserPair = new Pair<>(packageName, userId);
        synchronized (getLockObject()) {
            if (!mPackagesToRemove.contains(packageUserPair)) {
                // Do nothing if uninstall was not requested or was already started.
                return;
            }
            mPackagesToRemove.remove(packageUserPair);
        }
        try {
            if (mInjector.getIPackageManager().getPackageInfo(packageName, 0, userId) == null) {
                // Package does not exist. Nothing to do.
                return;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Failure talking to PackageManager while getting package info");
        }

        try { // force stop the package before uninstalling
            mInjector.getIActivityManager().forceStopPackage(packageName, userId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Failure talking to ActivityManager while force stopping package");
        }
        final Uri packageURI = Uri.parse("package:" + packageName);
        final Intent uninstallIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageURI);
        uninstallIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivityAsUser(uninstallIntent, UserHandle.of(userId));
    }

    /**
     * Removes the admin from the policy. Ideally called after the admin's
     * {@link DeviceAdminReceiver#onDisabled(Context, Intent)} has been successfully completed.
     *
     * @param adminReceiver The admin to remove
     * @param userHandle The user for which this admin has to be removed.
     */
    private void removeAdminArtifacts(final ComponentName adminReceiver, final int userHandle) {
        synchronized (getLockObject()) {
            final ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (admin == null) {
                return;
            }
            final DevicePolicyData policy = getUserData(userHandle);
            final boolean doProxyCleanup = admin.info.usesPolicy(
                    DeviceAdminInfo.USES_POLICY_SETS_GLOBAL_PROXY);
            policy.mAdminList.remove(admin);
            policy.mAdminMap.remove(adminReceiver);
            validatePasswordOwnerLocked(policy);
            if (doProxyCleanup) {
                resetGlobalProxyLocked(policy);
            }
            pushActiveAdminPackagesLocked(userHandle);
            pushMeteredDisabledPackagesLocked(userHandle);
            saveSettingsLocked(userHandle);
            updateMaximumTimeToLockLocked(userHandle);
            policy.mRemovingAdmins.remove(adminReceiver);

            Slog.i(LOG_TAG, "Device admin " + adminReceiver + " removed from user " + userHandle);
        }
        // The removed admin might have disabled camera, so update user
        // restrictions.
        pushUserRestrictions(userHandle);
    }

    @Override
    public void setDeviceProvisioningConfigApplied() {
        enforceManageUsers();
        synchronized (getLockObject()) {
            DevicePolicyData policy = getUserData(UserHandle.USER_SYSTEM);
            policy.mDeviceProvisioningConfigApplied = true;
            saveSettingsLocked(UserHandle.USER_SYSTEM);
        }
    }

    @Override
    public boolean isDeviceProvisioningConfigApplied() {
        enforceManageUsers();
        synchronized (getLockObject()) {
            final DevicePolicyData policy = getUserData(UserHandle.USER_SYSTEM);
            return policy.mDeviceProvisioningConfigApplied;
        }
    }

    /**
     * Force update internal persistent state from Settings.Secure.USER_SETUP_COMPLETE.
     *
     * It's added for testing only. Please use this API carefully if it's used by other system app
     * and bare in mind Settings.Secure.USER_SETUP_COMPLETE can be modified by user and other system
     * apps.
     */
    @Override
    public void forceUpdateUserSetupComplete() {
        enforceCanManageProfileAndDeviceOwners();
        enforceCallerSystemUserHandle();
        // no effect if it's called from user build
        if (!mInjector.isBuildDebuggable()) {
            return;
        }
        final int userId = UserHandle.USER_SYSTEM;
        boolean isUserCompleted = mInjector.settingsSecureGetIntForUser(
                Settings.Secure.USER_SETUP_COMPLETE, 0, userId) != 0;
        DevicePolicyData policy = getUserData(userId);
        policy.mUserSetupComplete = isUserCompleted;
        synchronized (getLockObject()) {
            saveSettingsLocked(userId);
        }
    }

    // TODO(b/22388012): When backup is available for secondary users and profiles, consider
    // whether there are any privacy/security implications of enabling the backup service here
    // if there are other users or profiles unmanaged or managed by a different entity (i.e. not
    // affiliated).
    @Override
    public void setBackupServiceEnabled(ComponentName admin, boolean enabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(
                    admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            if (!enabled) {
                activeAdmin.mandatoryBackupTransport = null;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
        }

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            IBackupManager ibm = mInjector.getIBackupManager();
            if (ibm != null) {
                ibm.setBackupServiceActive(UserHandle.USER_SYSTEM, enabled);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException(
                "Failed " + (enabled ? "" : "de") + "activating backup service.", e);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean isBackupServiceEnabled(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        if (!mHasFeature) {
            return true;
        }
        synchronized (getLockObject()) {
            try {
                getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
                IBackupManager ibm = mInjector.getIBackupManager();
                return ibm != null && ibm.isBackupServiceActive(UserHandle.USER_SYSTEM);
            } catch (RemoteException e) {
                throw new IllegalStateException("Failed requesting backup service state.", e);
            }
        }
    }

    @Override
    public boolean setMandatoryBackupTransport(
            ComponentName admin,
            ComponentName backupTransportComponent) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(admin);
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        final int callingUid = mInjector.binderGetCallingUid();
        final AtomicBoolean success = new AtomicBoolean(false);
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final ISelectBackupTransportCallback selectBackupTransportCallbackInternal =
                new ISelectBackupTransportCallback.Stub() {
                    public void onSuccess(String transportName) {
                        saveMandatoryBackupTransport(admin, callingUid, backupTransportComponent);
                        success.set(true);
                        countDownLatch.countDown();
                    }

                    public void onFailure(int reason) {
                        countDownLatch.countDown();
                    }
                };
        final long identity = mInjector.binderClearCallingIdentity();
        try {
            IBackupManager ibm = mInjector.getIBackupManager();
            if (ibm != null && backupTransportComponent != null) {
                if (!ibm.isBackupServiceActive(UserHandle.USER_SYSTEM)) {
                    ibm.setBackupServiceActive(UserHandle.USER_SYSTEM, true);
                }
                ibm.selectBackupTransportAsync(
                        backupTransportComponent, selectBackupTransportCallbackInternal);
                countDownLatch.await();
                if (success.get()) {
                    ibm.setBackupEnabled(true);
                }
            } else if (backupTransportComponent == null) {
                saveMandatoryBackupTransport(admin, callingUid, backupTransportComponent);
                success.set(true);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to set mandatory backup transport.", e);
        } catch (InterruptedException e) {
            throw new IllegalStateException("Failed to set mandatory backup transport.", e);
        } finally {
            mInjector.binderRestoreCallingIdentity(identity);
        }
        return success.get();
    }

    private void saveMandatoryBackupTransport(
            ComponentName admin, int callingUid, ComponentName backupTransportComponent) {
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin =
                    getActiveAdminWithPolicyForUidLocked(
                            admin,
                            DeviceAdminInfo.USES_POLICY_DEVICE_OWNER,
                            callingUid);
            if (!Objects.equals(backupTransportComponent,
                    activeAdmin.mandatoryBackupTransport)) {
                activeAdmin.mandatoryBackupTransport =
                        backupTransportComponent;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
        }
    }

    @Override
    public ComponentName getMandatoryBackupTransport() {
        if (!mHasFeature) {
            return null;
        }
        synchronized (getLockObject()) {
            ActiveAdmin activeAdmin = getDeviceOwnerAdminLocked();
            return activeAdmin == null ? null : activeAdmin.mandatoryBackupTransport;
        }
    }


    @Override
    public boolean bindDeviceAdminServiceAsUser(
            @NonNull ComponentName admin, @NonNull IApplicationThread caller,
            @Nullable IBinder activtiyToken, @NonNull Intent serviceIntent,
            @NonNull IServiceConnection connection, int flags, @UserIdInt int targetUserId) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(admin);
        Preconditions.checkNotNull(caller);
        Preconditions.checkNotNull(serviceIntent);
        Preconditions.checkArgument(
                serviceIntent.getComponent() != null || serviceIntent.getPackage() != null,
                "Service intent must be explicit (with a package name or component): "
                        + serviceIntent);
        Preconditions.checkNotNull(connection);
        Preconditions.checkArgument(mInjector.userHandleGetCallingUserId() != targetUserId,
                "target user id must be different from the calling user id");

        if (!getBindDeviceAdminTargetUsers(admin).contains(UserHandle.of(targetUserId))) {
            throw new SecurityException("Not allowed to bind to target user id");
        }

        final String targetPackage;
        synchronized (getLockObject()) {
            targetPackage = getOwnerPackageNameForUserLocked(targetUserId);
        }

        final long callingIdentity = mInjector.binderClearCallingIdentity();
        try {
            // Validate and sanitize the incoming service intent.
            final Intent sanitizedIntent =
                    createCrossUserServiceIntent(serviceIntent, targetPackage, targetUserId);
            if (sanitizedIntent == null) {
                // Fail, cannot lookup the target service.
                return false;
            }
            // Ask ActivityManager to bind it. Notice that we are binding the service with the
            // caller app instead of DevicePolicyManagerService.
            return mInjector.getIActivityManager().bindService(
                    caller, activtiyToken, serviceIntent,
                    serviceIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    connection, flags, mContext.getOpPackageName(),
                    targetUserId) != 0;
        } catch (RemoteException ex) {
            // Same process, should not happen.
        } finally {
            mInjector.binderRestoreCallingIdentity(callingIdentity);
        }

        // Failed to bind.
        return false;
    }

    @Override
    public @NonNull List<UserHandle> getBindDeviceAdminTargetUsers(@NonNull ComponentName admin) {
        if (!mHasFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(admin);

        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            final int callingUserId = mInjector.userHandleGetCallingUserId();
            final long callingIdentity = mInjector.binderClearCallingIdentity();
            try {
                ArrayList<UserHandle> targetUsers = new ArrayList<>();
                if (!isDeviceOwner(admin, callingUserId)) {
                    // Profile owners can only bind to the device owner.
                    if (canUserBindToDeviceOwnerLocked(callingUserId)) {
                        targetUsers.add(UserHandle.of(mOwners.getDeviceOwnerUserId()));
                    }
                } else {
                    // Caller is the device owner: Look for profile owners that it can bind to.
                    final List<UserInfo> userInfos = mUserManager.getUsers(/*excludeDying=*/ true);
                    for (int i = 0; i < userInfos.size(); i++) {
                        final int userId = userInfos.get(i).id;
                        if (userId != callingUserId && canUserBindToDeviceOwnerLocked(userId)) {
                            targetUsers.add(UserHandle.of(userId));
                        }
                    }
                }

                return targetUsers;
            } finally {
                mInjector.binderRestoreCallingIdentity(callingIdentity);
            }
        }
    }

    private boolean canUserBindToDeviceOwnerLocked(int userId) {
        // There has to be a device owner, under another user id.
        if (!mOwners.hasDeviceOwner() || userId == mOwners.getDeviceOwnerUserId()) {
            return false;
        }

        // The user must have a profile owner that belongs to the same package as the device owner.
        if (!mOwners.hasProfileOwner(userId) || !TextUtils.equals(
                mOwners.getDeviceOwnerPackageName(), mOwners.getProfileOwnerPackage(userId))) {
            return false;
        }

        // The user must be affiliated.
        return isUserAffiliatedWithDeviceLocked(userId);
    }

    /**
     * Return true if a given user has any accounts that'll prevent installing a device or profile
     * owner {@code owner}.
     * - If the user has no accounts, then return false.
     * - Otherwise, if the owner is unknown (== null), or is not test-only, then return true.
     * - Otherwise, if there's any account that does not have ..._ALLOWED, or does have
     *   ..._DISALLOWED, return true.
     * - Otherwise return false.
     *
     * If the caller is *not* ADB, it also returns true.  The returned value shouldn't be used
     * when the caller is not ADB.
     *
     * DO NOT CALL IT WITH THE DPMS LOCK HELD.
     */
    private boolean hasIncompatibleAccountsOrNonAdbNoLock(
            int userId, @Nullable ComponentName owner) {
        if (!isAdb()) {
            return true;
        }
        wtfIfInLock();

        final long token = mInjector.binderClearCallingIdentity();
        try {
            final AccountManager am = AccountManager.get(mContext);
            final Account accounts[] = am.getAccountsAsUser(userId);
            if (accounts.length == 0) {
                return false;
            }
            synchronized (getLockObject()) {
                if (owner == null || !isAdminTestOnlyLocked(owner, userId)) {
                    Log.w(LOG_TAG,
                            "Non test-only owner can't be installed with existing accounts.");
                    return true;
                }
            }

            final String[] feature_allow =
                    { DevicePolicyManager.ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED };
            final String[] feature_disallow =
                    { DevicePolicyManager.ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_DISALLOWED };

            boolean compatible = true;
            for (Account account : accounts) {
                if (hasAccountFeatures(am, account, feature_disallow)) {
                    Log.e(LOG_TAG, account + " has " + feature_disallow[0]);
                    compatible = false;
                    break;
                }
                if (!hasAccountFeatures(am, account, feature_allow)) {
                    Log.e(LOG_TAG, account + " doesn't have " + feature_allow[0]);
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                Log.w(LOG_TAG, "All accounts are compatible");
            } else {
                Log.e(LOG_TAG, "Found incompatible accounts");
            }
            return !compatible;
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private boolean hasAccountFeatures(AccountManager am, Account account, String[] features) {
        try {
            return am.hasFeatures(account, features, null, null).getResult();
        } catch (Exception e) {
            Log.w(LOG_TAG, "Failed to get account feature", e);
            return false;
        }
    }

    private boolean isAdb() {
        final int callingUid = mInjector.binderGetCallingUid();
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
    }

    @Override
    public void setNetworkLoggingEnabled(ComponentName admin, boolean enabled) {
        if (!mHasFeature) {
            return;
        }
        synchronized (getLockObject()) {
            Preconditions.checkNotNull(admin);
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            if (enabled == isNetworkLoggingEnabledInternalLocked()) {
                // already in the requested state
                return;
            }
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            deviceOwner.isNetworkLoggingEnabled = enabled;
            if (!enabled) {
                deviceOwner.numNetworkLoggingNotifications = 0;
                deviceOwner.lastNetworkLoggingNotificationTimeMs = 0;
            }
            saveSettingsLocked(mInjector.userHandleGetCallingUserId());

            setNetworkLoggingActiveInternal(enabled);
        }
    }

    private void setNetworkLoggingActiveInternal(boolean active) {
        synchronized (getLockObject()) {
            final long callingIdentity = mInjector.binderClearCallingIdentity();
            try {
                if (active) {
                    mNetworkLogger = new NetworkLogger(this, mInjector.getPackageManagerInternal());
                    if (!mNetworkLogger.startNetworkLogging()) {
                        mNetworkLogger = null;
                        Slog.wtf(LOG_TAG, "Network logging could not be started due to the logging"
                                + " service not being available yet.");
                    }
                    maybePauseDeviceWideLoggingLocked();
                    sendNetworkLoggingNotificationLocked();
                } else {
                    if (mNetworkLogger != null && !mNetworkLogger.stopNetworkLogging()) {
                        Slog.wtf(LOG_TAG, "Network logging could not be stopped due to the logging"
                                + " service not being available yet.");
                    }
                    mNetworkLogger = null;
                    mInjector.getNotificationManager().cancel(SystemMessage.NOTE_NETWORK_LOGGING);
                }
            } finally {
                mInjector.binderRestoreCallingIdentity(callingIdentity);
            }
        }
    }

    /** Pauses security and network logging if there are unaffiliated users on the device */
    private void maybePauseDeviceWideLoggingLocked() {
        if (!areAllUsersAffiliatedWithDeviceLocked()) {
            Slog.i(LOG_TAG, "There are unaffiliated users, security and network logging will be "
                    + "paused if enabled.");
            mSecurityLogMonitor.pause();
            if (mNetworkLogger != null) {
                mNetworkLogger.pause();
            }
        }
    }

    /** Resumes security and network logging (if they are enabled) if all users are affiliated */
    private void maybeResumeDeviceWideLoggingLocked() {
        if (areAllUsersAffiliatedWithDeviceLocked()) {
            final long ident = mInjector.binderClearCallingIdentity();
            try {
                mSecurityLogMonitor.resume();
                if (mNetworkLogger != null) {
                    mNetworkLogger.resume();
                }
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    /** Deletes any security and network logs that might have been collected so far */
    private void discardDeviceWideLogsLocked() {
        mSecurityLogMonitor.discardLogs();
        if (mNetworkLogger != null) {
            mNetworkLogger.discardLogs();
        }
        // TODO: We should discard pre-boot security logs here too, as otherwise those
        // logs (which might contain data from the user just removed) will be
        // available after next boot.
    }

    @Override
    public boolean isNetworkLoggingEnabled(ComponentName admin) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            enforceDeviceOwnerOrManageUsers();
            return isNetworkLoggingEnabledInternalLocked();
        }
    }

    private boolean isNetworkLoggingEnabledInternalLocked() {
        ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        return (deviceOwner != null) && deviceOwner.isNetworkLoggingEnabled;
    }

    /*
     * A maximum of 1200 events are returned, and the total marshalled size is in the order of
     * 100kB, so returning a List instead of ParceledListSlice is acceptable.
     * Ideally this would be done with ParceledList, however it only supports homogeneous types.
     *
     * @see NetworkLoggingHandler#MAX_EVENTS_PER_BATCH
     */
    @Override
    public List<NetworkEvent> retrieveNetworkLogs(ComponentName admin, long batchToken) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(admin);
        ensureDeviceOwnerAndAllUsersAffiliated(admin);

        synchronized (getLockObject()) {
            if (mNetworkLogger == null
                    || !isNetworkLoggingEnabledInternalLocked()) {
                return null;
            }

            final long currentTime = System.currentTimeMillis();
            DevicePolicyData policyData = getUserData(UserHandle.USER_SYSTEM);
            if (currentTime > policyData.mLastNetworkLogsRetrievalTime) {
                policyData.mLastNetworkLogsRetrievalTime = currentTime;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
            return mNetworkLogger.retrieveLogs(batchToken);
        }
    }

    private void sendNetworkLoggingNotificationLocked() {
        final ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
        if (deviceOwner == null || !deviceOwner.isNetworkLoggingEnabled) {
            return;
        }
        if (deviceOwner.numNetworkLoggingNotifications >=
                ActiveAdmin.DEF_MAXIMUM_NETWORK_LOGGING_NOTIFICATIONS_SHOWN) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - deviceOwner.lastNetworkLoggingNotificationTimeMs < MS_PER_DAY) {
            return;
        }
        deviceOwner.numNetworkLoggingNotifications++;
        if (deviceOwner.numNetworkLoggingNotifications
                >= ActiveAdmin.DEF_MAXIMUM_NETWORK_LOGGING_NOTIFICATIONS_SHOWN) {
            deviceOwner.lastNetworkLoggingNotificationTimeMs = 0;
        } else {
            deviceOwner.lastNetworkLoggingNotificationTimeMs = now;
        }
        final Intent intent = new Intent(DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG);
        intent.setPackage("com.android.systemui");
        final PendingIntent pendingIntent = PendingIntent.getBroadcastAsUser(mContext, 0, intent, 0,
                UserHandle.CURRENT);
        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.DEVICE_ADMIN)
                .setSmallIcon(R.drawable.ic_info_outline)
                .setContentTitle(mContext.getString(R.string.network_logging_notification_title))
                .setContentText(mContext.getString(R.string.network_logging_notification_text))
                .setTicker(mContext.getString(R.string.network_logging_notification_title))
                .setShowWhen(true)
                .setContentIntent(pendingIntent)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(mContext.getString(R.string.network_logging_notification_text)))
                .build();
        mInjector.getNotificationManager().notify(SystemMessage.NOTE_NETWORK_LOGGING, notification);
        saveSettingsLocked(mOwners.getDeviceOwnerUserId());
    }

    /**
     * Return the package name of owner in a given user.
     */
    private String getOwnerPackageNameForUserLocked(int userId) {
        return mOwners.getDeviceOwnerUserId() == userId
                ? mOwners.getDeviceOwnerPackageName()
                : mOwners.getProfileOwnerPackage(userId);
    }

    /**
     * @param rawIntent Original service intent specified by caller. It must be explicit.
     * @param expectedPackageName The expected package name of the resolved service.
     * @return Intent that have component explicitly set. {@code null} if no service is resolved
     *     with the given intent.
     * @throws SecurityException if the intent is resolved to an invalid service.
     */
    private Intent createCrossUserServiceIntent(
            @NonNull Intent rawIntent, @NonNull String expectedPackageName,
            @UserIdInt int targetUserId) throws RemoteException, SecurityException {
        ResolveInfo info = mIPackageManager.resolveService(
                rawIntent,
                rawIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                0,  // flags
                targetUserId);
        if (info == null || info.serviceInfo == null) {
            Log.e(LOG_TAG, "Fail to look up the service: " + rawIntent
                    + " or user " + targetUserId + " is not running");
            return null;
        }
        if (!expectedPackageName.equals(info.serviceInfo.packageName)) {
            throw new SecurityException("Only allow to bind service in " + expectedPackageName);
        }
        // STOPSHIP(b/37624960): Remove info.serviceInfo.exported before release.
        if (info.serviceInfo.exported && !BIND_DEVICE_ADMIN.equals(info.serviceInfo.permission)) {
            throw new SecurityException(
                    "Service must be protected by BIND_DEVICE_ADMIN permission");
        }
        // It is the system server to bind the service, it would be extremely dangerous if it
        // can be exploited to bind any service. Set the component explicitly to make sure we
        // do not bind anything accidentally.
        rawIntent.setComponent(info.serviceInfo.getComponentName());
        return rawIntent;
    }

    @Override
    public long getLastSecurityLogRetrievalTime() {
        enforceDeviceOwnerOrManageUsers();
        return getUserData(UserHandle.USER_SYSTEM).mLastSecurityLogRetrievalTime;
     }

    @Override
    public long getLastBugReportRequestTime() {
        enforceDeviceOwnerOrManageUsers();
        return getUserData(UserHandle.USER_SYSTEM).mLastBugReportRequestTime;
     }

    @Override
    public long getLastNetworkLogRetrievalTime() {
        enforceDeviceOwnerOrManageUsers();
        return getUserData(UserHandle.USER_SYSTEM).mLastNetworkLogsRetrievalTime;
    }

    @Override
    public boolean setResetPasswordToken(ComponentName admin, byte[] token) {
        if (!mHasFeature) {
            return false;
        }
        if (token == null || token.length < 32) {
            throw new IllegalArgumentException("token must be at least 32-byte long");
        }
        synchronized (getLockObject()) {
            final int userHandle = mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            DevicePolicyData policy = getUserData(userHandle);
            long ident = mInjector.binderClearCallingIdentity();
            try {
                if (policy.mPasswordTokenHandle != 0) {
                    mLockPatternUtils.removeEscrowToken(policy.mPasswordTokenHandle, userHandle);
                }

                policy.mPasswordTokenHandle = mLockPatternUtils.addEscrowToken(token, userHandle);
                saveSettingsLocked(userHandle);
                return policy.mPasswordTokenHandle != 0;
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public boolean clearResetPasswordToken(ComponentName admin) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            final int userHandle = mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordTokenHandle != 0) {
                long ident = mInjector.binderClearCallingIdentity();
                try {
                    boolean result = mLockPatternUtils.removeEscrowToken(
                            policy.mPasswordTokenHandle, userHandle);
                    policy.mPasswordTokenHandle = 0;
                    saveSettingsLocked(userHandle);
                    return result;
                } finally {
                    mInjector.binderRestoreCallingIdentity(ident);
                }
            }
        }
        return false;
    }

    @Override
    public boolean isResetPasswordTokenActive(ComponentName admin) {
        synchronized (getLockObject()) {
            final int userHandle = mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordTokenHandle != 0) {
                long ident = mInjector.binderClearCallingIdentity();
                try {
                    return mLockPatternUtils.isEscrowTokenActive(policy.mPasswordTokenHandle,
                            userHandle);
                } finally {
                    mInjector.binderRestoreCallingIdentity(ident);
                }
            }
        }
        return false;
    }

    @Override
    public boolean resetPasswordWithToken(ComponentName admin, String passwordOrNull, byte[] token,
            int flags) {
        Preconditions.checkNotNull(token);
        synchronized (getLockObject()) {
            final int userHandle = mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mPasswordTokenHandle != 0) {
                final String password = passwordOrNull != null ? passwordOrNull : "";
                return resetPasswordInternal(password, policy.mPasswordTokenHandle, token,
                        flags, mInjector.binderGetCallingUid(), userHandle);
            } else {
                Slog.w(LOG_TAG, "No saved token handle");
            }
        }
        return false;
    }

    @Override
    public boolean isCurrentInputMethodSetByOwner() {
        enforceProfileOwnerOrSystemUser();
        return getUserData(mInjector.userHandleGetCallingUserId()).mCurrentInputMethodSet;
    }

    @Override
    public StringParceledListSlice getOwnerInstalledCaCerts(@NonNull UserHandle user) {
        final int userId = user.getIdentifier();
        enforceProfileOwnerOrFullCrossUsersPermission(userId);
        synchronized (getLockObject()) {
            return new StringParceledListSlice(
                    new ArrayList<>(getUserData(userId).mOwnerInstalledCaCerts));
        }
    }

    @Override
    public void clearApplicationUserData(ComponentName admin, String packageName,
            IPackageDataObserver callback) {
        Preconditions.checkNotNull(admin, "ComponentName is null");
        Preconditions.checkNotNull(packageName, "packageName is null");
        Preconditions.checkNotNull(callback, "callback is null");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }
        final int userId = UserHandle.getCallingUserId();

        long ident = mInjector.binderClearCallingIdentity();
        try {
            ActivityManager.getService().clearApplicationUserData(packageName, false, callback,
                    userId);
        } catch(RemoteException re) {
            // Same process, should not happen.
        } catch (SecurityException se) {
            // This can happen e.g. for device admin packages, do not throw out the exception,
            // because callers have no means to know beforehand for which packages this might
            // happen. If so, we send back that removal failed.
            Slog.w(LOG_TAG, "Not allowed to clear application user data for package " + packageName,
                    se);
            try {
                callback.onRemoveCompleted(packageName, false);
            } catch (RemoteException re) {
                // Caller is no longer available, ignore
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public void setLogoutEnabled(ComponentName admin, boolean enabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);

        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            if (deviceOwner.isLogoutEnabled == enabled) {
                // already in the requested state
                return;
            }
            deviceOwner.isLogoutEnabled = enabled;
            saveSettingsLocked(mInjector.userHandleGetCallingUserId());
        }
    }

    @Override
    public boolean isLogoutEnabled() {
        if (!mHasFeature) {
            return false;
        }
        synchronized (getLockObject()) {
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            return (deviceOwner != null) && deviceOwner.isLogoutEnabled;
        }
    }

    @Override
    public List<String> getDisallowedSystemApps(ComponentName admin, int userId,
            String provisioningAction) throws RemoteException {
        enforceCanManageProfileAndDeviceOwners();
        return new ArrayList<>(
                mOverlayPackagesProvider.getNonRequiredApps(admin, userId, provisioningAction));
    }

    @Override
    public void transferOwnership(@NonNull ComponentName admin, @NonNull ComponentName target,
            @Nullable PersistableBundle bundle) {
        if (!mHasFeature) {
            return;
        }

        Preconditions.checkNotNull(admin, "Admin cannot be null.");
        Preconditions.checkNotNull(target, "Target cannot be null.");

        enforceProfileOrDeviceOwner(admin);

        if (admin.equals(target)) {
            throw new IllegalArgumentException("Provided administrator and target are "
                    + "the same object.");
        }

        if (admin.getPackageName().equals(target.getPackageName())) {
            throw new IllegalArgumentException("Provided administrator and target have "
                    + "the same package name.");
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        final DevicePolicyData policy = getUserData(callingUserId);
        final DeviceAdminInfo incomingDeviceInfo = findAdmin(target, callingUserId,
                /* throwForMissingPermission= */ true);
        checkActiveAdminPrecondition(target, incomingDeviceInfo, policy);
        if (!incomingDeviceInfo.supportsTransferOwnership()) {
            throw new IllegalArgumentException("Provided target does not support "
                    + "ownership transfer.");
        }

        final long id = mInjector.binderClearCallingIdentity();
        try {
            synchronized (getLockObject()) {
                /*
                * We must ensure the whole process is atomic to prevent the device from ending up
                * in an invalid state (e.g. no active admin). This could happen if the device
                * is rebooted or work mode is turned off mid-transfer.
                * In order to guarantee atomicity, we:
                *
                * 1. Save an atomic journal file describing the transfer process
                * 2. Perform the transfer itself
                * 3. Delete the journal file
                *
                * That way if the journal file exists on device boot, we know that the transfer
                * must be reverted back to the original administrator. This logic is implemented in
                * revertTransferOwnershipIfNecessaryLocked.
                * */
                if (bundle == null) {
                    bundle = new PersistableBundle();
                }
                if (isProfileOwner(admin, callingUserId)) {
                    prepareTransfer(admin, target, bundle, callingUserId,
                            ADMIN_TYPE_PROFILE_OWNER);
                    transferProfileOwnershipLocked(admin, target, callingUserId);
                    sendProfileOwnerCommand(DeviceAdminReceiver.ACTION_TRANSFER_OWNERSHIP_COMPLETE,
                            getTransferOwnershipAdminExtras(bundle), callingUserId);
                    postTransfer(DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED, callingUserId);
                    if (isUserAffiliatedWithDeviceLocked(callingUserId)) {
                        notifyAffiliatedProfileTransferOwnershipComplete(callingUserId);
                    }
                } else if (isDeviceOwner(admin, callingUserId)) {
                    prepareTransfer(admin, target, bundle, callingUserId,
                            ADMIN_TYPE_DEVICE_OWNER);
                    transferDeviceOwnershipLocked(admin, target, callingUserId);
                    sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_TRANSFER_OWNERSHIP_COMPLETE,
                            getTransferOwnershipAdminExtras(bundle));
                    postTransfer(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED, callingUserId);
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    private void prepareTransfer(ComponentName admin, ComponentName target,
            PersistableBundle bundle, int callingUserId, String adminType) {
        saveTransferOwnershipBundleLocked(bundle, callingUserId);
        mTransferOwnershipMetadataManager.saveMetadataFile(
                new TransferOwnershipMetadataManager.Metadata(admin, target,
                        callingUserId, adminType));
    }

    private void postTransfer(String broadcast, int callingUserId) {
        deleteTransferOwnershipMetadataFileLocked();
        sendOwnerChangedBroadcast(broadcast, callingUserId);
    }

    private void notifyAffiliatedProfileTransferOwnershipComplete(int callingUserId) {
        final Bundle extras = new Bundle();
        extras.putParcelable(Intent.EXTRA_USER, UserHandle.of(callingUserId));
        sendDeviceOwnerCommand(
                DeviceAdminReceiver.ACTION_AFFILIATED_PROFILE_TRANSFER_OWNERSHIP_COMPLETE, extras);
    }

    /**
     * Transfers the profile owner for user with id profileOwnerUserId from admin to target.
     */
    private void transferProfileOwnershipLocked(ComponentName admin, ComponentName target,
            int profileOwnerUserId) {
        transferActiveAdminUncheckedLocked(target, admin, profileOwnerUserId);
        mOwners.transferProfileOwner(target, profileOwnerUserId);
        Slog.i(LOG_TAG, "Profile owner set: " + target + " on user " + profileOwnerUserId);
        mOwners.writeProfileOwner(profileOwnerUserId);
        mDeviceAdminServiceController.startServiceForOwner(
                target.getPackageName(), profileOwnerUserId, "transfer-profile-owner");
    }

    /**
     * Transfers the device owner for user with id userId from admin to target.
     */
    private void transferDeviceOwnershipLocked(ComponentName admin, ComponentName target, int userId) {
        transferActiveAdminUncheckedLocked(target, admin, userId);
        mOwners.transferDeviceOwnership(target);
        Slog.i(LOG_TAG, "Device owner set: " + target + " on user " + userId);
        mOwners.writeDeviceOwner();
        mDeviceAdminServiceController.startServiceForOwner(
                target.getPackageName(), userId, "transfer-device-owner");
    }

    private Bundle getTransferOwnershipAdminExtras(PersistableBundle bundle) {
        Bundle extras = new Bundle();
        if (bundle != null) {
            extras.putParcelable(EXTRA_TRANSFER_OWNERSHIP_ADMIN_EXTRAS_BUNDLE, bundle);
        }
        return extras;
    }

    @Override
    public void setStartUserSessionMessage(
            ComponentName admin, CharSequence startUserSessionMessage) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);

        final String startUserSessionMessageString =
                startUserSessionMessage != null ? startUserSessionMessage.toString() : null;

        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            if (TextUtils.equals(deviceOwner.startUserSessionMessage, startUserSessionMessage)) {
                return;
            }
            deviceOwner.startUserSessionMessage = startUserSessionMessageString;
            saveSettingsLocked(mInjector.userHandleGetCallingUserId());
        }

        mInjector.getActivityManagerInternal()
                .setSwitchingFromSystemUserMessage(startUserSessionMessageString);
    }

    @Override
    public void setEndUserSessionMessage(ComponentName admin, CharSequence endUserSessionMessage) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin);

        final String endUserSessionMessageString =
                endUserSessionMessage != null ? endUserSessionMessage.toString() : null;

        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            if (TextUtils.equals(deviceOwner.endUserSessionMessage, endUserSessionMessage)) {
                return;
            }
            deviceOwner.endUserSessionMessage = endUserSessionMessageString;
            saveSettingsLocked(mInjector.userHandleGetCallingUserId());
        }

        mInjector.getActivityManagerInternal()
                .setSwitchingToSystemUserMessage(endUserSessionMessageString);
    }

    @Override
    public String getStartUserSessionMessage(ComponentName admin) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(admin);

        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            return deviceOwner.startUserSessionMessage;
        }
    }

    @Override
    public String getEndUserSessionMessage(ComponentName admin) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(admin);

        synchronized (getLockObject()) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            return deviceOwner.endUserSessionMessage;
        }
    }

    private void deleteTransferOwnershipMetadataFileLocked() {
        mTransferOwnershipMetadataManager.deleteMetadataFile();
    }

    @Override
    @Nullable
    public PersistableBundle getTransferOwnershipBundle() {
        synchronized (getLockObject()) {
            final int callingUserId = mInjector.userHandleGetCallingUserId();
            getActiveAdminForCallerLocked(null, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final File bundleFile = new File(
                    mInjector.environmentGetUserSystemDirectory(callingUserId),
                    TRANSFER_OWNERSHIP_PARAMETERS_XML);
            if (!bundleFile.exists()) {
                return null;
            }
            try (FileInputStream stream = new FileInputStream(bundleFile)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, null);
                parser.next();
                return PersistableBundle.restoreFromXml(parser);
            } catch (IOException | XmlPullParserException | IllegalArgumentException e) {
                Slog.e(LOG_TAG, "Caught exception while trying to load the "
                        + "owner transfer parameters from file " + bundleFile, e);
                return null;
            }
        }
    }

    @Override
    public int addOverrideApn(@NonNull ComponentName who, @NonNull ApnSetting apnSetting) {
        if (!mHasFeature) {
            return -1;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in addOverrideApn");
        Preconditions.checkNotNull(apnSetting, "ApnSetting is null in addOverrideApn");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        int operatedId = -1;
        Uri resultUri;
        final long id = mInjector.binderClearCallingIdentity();
        try {
            resultUri = mContext.getContentResolver().insert(DPC_URI, apnSetting.toContentValues());
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        if (resultUri != null) {
            try {
                operatedId = Integer.parseInt(resultUri.getLastPathSegment());
            } catch (NumberFormatException e) {
                Slog.e(LOG_TAG, "Failed to parse inserted override APN id.", e);
            }
        }

        return operatedId;
    }

    @Override
    public boolean updateOverrideApn(@NonNull ComponentName who, int apnId,
            @NonNull ApnSetting apnSetting) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in updateOverrideApn");
        Preconditions.checkNotNull(apnSetting, "ApnSetting is null in updateOverrideApn");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        if (apnId < 0) {
            return false;
        }
        final long id = mInjector.binderClearCallingIdentity();
        try {
            return mContext.getContentResolver().update(
                    Uri.withAppendedPath(DPC_URI, Integer.toString(apnId)),
                    apnSetting.toContentValues(), null, null) > 0;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public boolean removeOverrideApn(@NonNull ComponentName who, int apnId) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in removeOverrideApn");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        return removeOverrideApnUnchecked(apnId);
    }

    private boolean removeOverrideApnUnchecked(int apnId) {
        if(apnId < 0) {
            return false;
        }
        int numDeleted = 0;
        final long id = mInjector.binderClearCallingIdentity();
        try {
            numDeleted = mContext.getContentResolver().delete(
                    Uri.withAppendedPath(DPC_URI, Integer.toString(apnId)), null, null);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        return numDeleted > 0;
    }

    @Override
    public List<ApnSetting> getOverrideApns(@NonNull ComponentName who) {
        if (!mHasFeature) {
            return Collections.emptyList();
        }
        Preconditions.checkNotNull(who, "ComponentName is null in getOverrideApns");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        return getOverrideApnsUnchecked();
    }

    private List<ApnSetting> getOverrideApnsUnchecked() {
        final Cursor cursor;
        final long id = mInjector.binderClearCallingIdentity();
        try {
            cursor = mContext.getContentResolver().query(DPC_URI, null, null, null, null);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }

        if (cursor == null) {
            return Collections.emptyList();
        }
        try {
            List<ApnSetting> apnList = new ArrayList<ApnSetting>();
            cursor.moveToPosition(-1);
            while (cursor.moveToNext()) {
                ApnSetting apn = ApnSetting.makeApnSetting(cursor);
                apnList.add(apn);
            }
            return apnList;
        } finally {
            cursor.close();
        }
    }

    @Override
    public void setOverrideApnsEnabled(@NonNull ComponentName who, boolean enabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in setOverrideApnEnabled");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        setOverrideApnsEnabledUnchecked(enabled);
    }

    private void setOverrideApnsEnabledUnchecked(boolean enabled) {
        ContentValues value = new ContentValues();
        value.put(ENFORCE_KEY, enabled);
        final long id = mInjector.binderClearCallingIdentity();
        try {
            mContext.getContentResolver().update(
                    ENFORCE_MANAGED_URI, value, null, null);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public boolean isOverrideApnEnabled(@NonNull ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null in isOverrideApnEnabled");
        synchronized (getLockObject()) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }

        Cursor enforceCursor;
        final long id = mInjector.binderClearCallingIdentity();
        try {
            enforceCursor = mContext.getContentResolver().query(
                    ENFORCE_MANAGED_URI, null, null, null, null);
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }

        if (enforceCursor == null) {
            return false;
        }
        try {
            if (enforceCursor.moveToFirst()) {
                return enforceCursor.getInt(enforceCursor.getColumnIndex(ENFORCE_KEY)) == 1;
            }
        } catch (IllegalArgumentException e) {
            Slog.e(LOG_TAG, "Cursor returned from ENFORCE_MANAGED_URI doesn't contain "
                    + "correct info.", e);
        } finally {
            enforceCursor.close();
        }
        return false;
    }

    @VisibleForTesting
    void saveTransferOwnershipBundleLocked(PersistableBundle bundle, int userId) {
        final File parametersFile = new File(
                mInjector.environmentGetUserSystemDirectory(userId),
                TRANSFER_OWNERSHIP_PARAMETERS_XML);
        final AtomicFile atomicFile = new AtomicFile(parametersFile);
        FileOutputStream stream = null;
        try {
            stream = atomicFile.startWrite();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(stream, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_TRANSFER_OWNERSHIP_BUNDLE);
            bundle.saveToXml(serializer);
            serializer.endTag(null, TAG_TRANSFER_OWNERSHIP_BUNDLE);
            serializer.endDocument();
            atomicFile.finishWrite(stream);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(LOG_TAG, "Caught exception while trying to save the "
                    + "owner transfer parameters to file " + parametersFile, e);
            parametersFile.delete();
            atomicFile.failWrite(stream);
        }
    }

    void deleteTransferOwnershipBundleLocked(int userId) {
        final File parametersFile = new File(mInjector.environmentGetUserSystemDirectory(userId),
                TRANSFER_OWNERSHIP_PARAMETERS_XML);
        parametersFile.delete();
    }

    private void maybeLogPasswordComplexitySet(ComponentName who, int userId, boolean parent,
            PasswordMetrics metrics) {
        if (SecurityLog.isLoggingEnabled()) {
            final int affectedUserId = parent ? getProfileParentId(userId) : userId;
            SecurityLog.writeEvent(SecurityLog.TAG_PASSWORD_COMPLEXITY_SET, who.getPackageName(),
                    userId, affectedUserId, metrics.length, metrics.quality, metrics.letters,
                    metrics.nonLetter, metrics.numeric, metrics.upperCase, metrics.lowerCase,
                    metrics.symbols);
        }
    }

    private static String getManagedProvisioningPackage(Context context) {
        return context.getResources().getString(R.string.config_managed_provisioning_package);
    }
}
