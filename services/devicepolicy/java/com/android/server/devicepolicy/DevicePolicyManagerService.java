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

import static android.Manifest.permission.MANAGE_CA_CERTIFICATES;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.WIPE_EXTERNAL_STORAGE;
import static android.app.admin.DevicePolicyManager.WIPE_RESET_PROTECTION_DATA;
import static android.content.pm.PackageManager.GET_UNINSTALLED_PACKAGES;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.Manifest.permission;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.AccountManager;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.IActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.IDevicePolicyManager;
import android.app.admin.SecurityLog;
import android.app.admin.SecurityLog.SecurityEvent;
import android.app.admin.SystemUpdatePolicy;
import android.app.backup.IBackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.IAudioService;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
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
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.storage.StorageManager;
import android.provider.ContactsContract.QuickContact;
import android.provider.ContactsInternal;
import android.provider.Settings;
import android.security.Credentials;
import android.security.IKeyChainAliasCallback;
import android.security.IKeyChainService;
import android.security.KeyChain;
import android.security.KeyChain.KeyChainConnection;
import android.service.persistentdata.PersistentDataBlockManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
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
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.ParcelableString;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.devicepolicy.DevicePolicyManagerService.ActiveAdmin.TrustAgentInfo;
import com.android.server.pm.UserRestrictionsUtils;
import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of the device policy APIs.
 */
public class DevicePolicyManagerService extends IDevicePolicyManager.Stub {

    private static final String LOG_TAG = "DevicePolicyManagerService";

    private static final boolean VERBOSE_LOG = false; // DO NOT SUBMIT WITH TRUE

    private static final String DEVICE_POLICIES_XML = "device_policies.xml";

    private static final String TAG_ACCEPTED_CA_CERTIFICATES = "accepted-ca-certificate";

    private static final String TAG_LOCK_TASK_COMPONENTS = "lock-task-component";

    private static final String TAG_STATUS_BAR = "statusbar";

    private static final String ATTR_DISABLED = "disabled";

    private static final String ATTR_NAME = "name";

    private static final String DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML =
            "do-not-ask-credentials-on-boot";

    private static final String TAG_AFFILIATION_ID = "affiliation-id";

    private static final String TAG_ADMIN_BROADCAST_PENDING = "admin-broadcast-pending";

    private static final String ATTR_VALUE = "value";

    private static final String TAG_INITIALIZATION_BUNDLE = "initialization-bundle";

    private static final int REQUEST_EXPIRE_PASSWORD = 5571;

    private static final long MS_PER_DAY = 86400 * 1000;

    private static final long EXPIRATION_GRACE_PERIOD_MS = 5 * MS_PER_DAY; // 5 days, in ms

    private static final String ACTION_EXPIRED_PASSWORD_NOTIFICATION
            = "com.android.server.ACTION_EXPIRED_PASSWORD_NOTIFICATION";

    private static final int MONITORING_CERT_NOTIFICATION_ID = R.plurals.ssl_ca_cert_warning;
    private static final int PROFILE_WIPED_NOTIFICATION_ID = 1001;

    private static final String ATTR_PERMISSION_PROVIDER = "permission-provider";
    private static final String ATTR_SETUP_COMPLETE = "setup-complete";
    private static final String ATTR_PROVISIONING_STATE = "provisioning-state";
    private static final String ATTR_PERMISSION_POLICY = "permission-policy";

    private static final String ATTR_DELEGATED_CERT_INSTALLER = "delegated-cert-installer";
    private static final String ATTR_APPLICATION_RESTRICTIONS_MANAGER
            = "application-restrictions-manager";

    /**
     *  System property whose value is either "true" or "false", indicating whether
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
    }

    /**
     * Keyguard features that when set on a managed profile that doesn't have its own challenge will
     * affect the profile's parent user. These can also be set on the managed profile's parent DPM
     * instance.
     */
    private static final int PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER =
            DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
            | DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;

    /**
     * Keyguard features that when set on a profile affect the profile content or challenge only.
     * These cannot be set on the managed profile's parent DPM instance
     */
    private static final int PROFILE_KEYGUARD_FEATURES_PROFILE_ONLY =
            DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;

    /** Keyguard features that are allowed to be set on a managed profile */
    private static final int PROFILE_KEYGUARD_FEATURES =
            PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER | PROFILE_KEYGUARD_FEATURES_PROFILE_ONLY;

    private static final int CODE_OK = 0;
    private static final int CODE_HAS_DEVICE_OWNER = 1;
    private static final int CODE_USER_HAS_PROFILE_OWNER = 2;
    private static final int CODE_USER_NOT_RUNNING = 3;
    private static final int CODE_USER_SETUP_COMPLETED = 4;
    private static final int CODE_NONSYSTEM_USER_EXISTS = 5;
    private static final int CODE_ACCOUNTS_NOT_EMPTY = 6;
    private static final int CODE_NOT_SYSTEM_USER = 7;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ CODE_OK, CODE_HAS_DEVICE_OWNER, CODE_USER_HAS_PROFILE_OWNER, CODE_USER_NOT_RUNNING,
            CODE_USER_SETUP_COMPLETED, CODE_NOT_SYSTEM_USER })
    private @interface DeviceOwnerPreConditionCode {}

    private static final int DEVICE_ADMIN_DEACTIVATE_TIMEOUT = 10000;

    final Context mContext;
    final Injector mInjector;
    final IPackageManager mIPackageManager;
    final UserManager mUserManager;
    final UserManagerInternal mUserManagerInternal;
    final TelephonyManager mTelephonyManager;
    private final LockPatternUtils mLockPatternUtils;

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
    boolean mHasFeature;

    private final SecurityLogMonitor mSecurityLogMonitor;

    private final AtomicBoolean mRemoteBugreportServiceIsActive = new AtomicBoolean();
    private final AtomicBoolean mRemoteBugreportSharingAccepted = new AtomicBoolean();

    private final Runnable mRemoteBugreportTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if(mRemoteBugreportServiceIsActive.get()) {
                onBugreportFailed();
            }
        }
    };

    private final BroadcastReceiver mRemoteBugreportFinishedReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (DevicePolicyManager.ACTION_REMOTE_BUGREPORT_DISPATCH.equals(intent.getAction())
                    && mRemoteBugreportServiceIsActive.get()) {
                onBugreportFinished(intent);
            }
        }
    };

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
        private DevicePolicyManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new DevicePolicyManagerService(context);
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
            mService.onStartUser(userHandle);
        }
    }

    public static class DevicePolicyData {
        int mActivePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        int mActivePasswordLength = 0;
        int mActivePasswordUpperCase = 0;
        int mActivePasswordLowerCase = 0;
        int mActivePasswordLetters = 0;
        int mActivePasswordNumeric = 0;
        int mActivePasswordSymbols = 0;
        int mActivePasswordNonLetter = 0;
        int mFailedPasswordAttempts = 0;

        int mUserHandle;
        int mPasswordOwner = -1;
        long mLastMaximumTimeToLock = -1;
        boolean mUserSetupComplete = false;
        int mUserProvisioningState;
        int mPermissionPolicy;

        final ArrayMap<ComponentName, ActiveAdmin> mAdminMap = new ArrayMap<>();
        final ArrayList<ActiveAdmin> mAdminList = new ArrayList<>();
        final ArrayList<ComponentName> mRemovingAdmins = new ArrayList<>();

        final ArraySet<String> mAcceptedCaCertificates = new ArraySet<>();

        // This is the list of component allowed to start lock task mode.
        List<String> mLockTaskPackages = new ArrayList<>();

        boolean mStatusBarDisabled = false;

        ComponentName mRestrictionsProvider;

        String mDelegatedCertInstallerPackage;

        boolean doNotAskCredentialsOnBoot = false;

        String mApplicationRestrictionsManagingPackage;

        Set<String> mAffiliationIds = new ArraySet<>();

        // Used for initialization of users created by createAndManageUsers.
        boolean mAdminBroadcastPending = false;
        PersistableBundle mInitBundle = null;

        public DevicePolicyData(int userHandle) {
            mUserHandle = userHandle;
        }
    }

    final SparseArray<DevicePolicyData> mUserData = new SparseArray<>();

    final Handler mHandler;

    BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                    getSendingUserId());

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
            if (Intent.ACTION_USER_UNLOCKED.equals(action)
                    || Intent.ACTION_USER_STARTED.equals(action)
                    || KeyChain.ACTION_STORAGE_CHANGED.equals(action)) {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_ALL);
                new MonitoringCertNotificationTask().execute(userId);
            }
            if (Intent.ACTION_USER_ADDED.equals(action)) {
                disableSecurityLoggingIfNotCompliant();
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                disableSecurityLoggingIfNotCompliant();
                removeUserData(userHandle);
            } else if (Intent.ACTION_USER_STARTED.equals(action)) {
                synchronized (DevicePolicyManagerService.this) {
                    // Reset the policy data
                    mUserData.remove(userHandle);
                    sendAdminEnabledBroadcastLocked(userHandle);
                }
                handlePackagesChanged(null /* check all admins */, userHandle);
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
            }
        }
    };

    static class ActiveAdmin {
        private static final String TAG_DISABLE_KEYGUARD_FEATURES = "disable-keyguard-features";
        private static final String TAG_DISABLE_CAMERA = "disable-camera";
        private static final String TAG_DISABLE_CALLER_ID = "disable-caller-id";
        private static final String TAG_DISABLE_CONTACTS_SEARCH = "disable-contacts-search";
        private static final String TAG_DISABLE_BLUETOOTH_CONTACT_SHARING
                = "disable-bt-contacts-sharing";
        private static final String TAG_DISABLE_SCREEN_CAPTURE = "disable-screen-capture";
        private static final String TAG_DISABLE_ACCOUNT_MANAGEMENT = "disable-account-management";
        private static final String TAG_REQUIRE_AUTO_TIME = "require_auto_time";
        private static final String TAG_FORCE_EPHEMERAL_USERS = "force_ephemeral_users";
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
        private static final String TAG_MAX_FAILED_PASSWORD_WIPE = "max-failed-password-wipe";
        private static final String TAG_MAX_TIME_TO_UNLOCK = "max-time-to-unlock";
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
        private static final String TAG_SHORT_SUPPORT_MESSAGE = "short-support-message";
        private static final String TAG_LONG_SUPPORT_MESSAGE = "long-support-message";
        private static final String TAG_PARENT_ADMIN = "parent-admin";
        private static final String TAG_ORGANIZATION_COLOR = "organization-color";
        private static final String TAG_ORGANIZATION_NAME = "organization-name";

        final DeviceAdminInfo info;

        int passwordQuality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

        static final int DEF_MINIMUM_PASSWORD_LENGTH = 0;
        int minimumPasswordLength = DEF_MINIMUM_PASSWORD_LENGTH;

        static final int DEF_PASSWORD_HISTORY_LENGTH = 0;
        int passwordHistoryLength = DEF_PASSWORD_HISTORY_LENGTH;

        static final int DEF_MINIMUM_PASSWORD_UPPER_CASE = 0;
        int minimumPasswordUpperCase = DEF_MINIMUM_PASSWORD_UPPER_CASE;

        static final int DEF_MINIMUM_PASSWORD_LOWER_CASE = 0;
        int minimumPasswordLowerCase = DEF_MINIMUM_PASSWORD_LOWER_CASE;

        static final int DEF_MINIMUM_PASSWORD_LETTERS = 1;
        int minimumPasswordLetters = DEF_MINIMUM_PASSWORD_LETTERS;

        static final int DEF_MINIMUM_PASSWORD_NUMERIC = 1;
        int minimumPasswordNumeric = DEF_MINIMUM_PASSWORD_NUMERIC;

        static final int DEF_MINIMUM_PASSWORD_SYMBOLS = 1;
        int minimumPasswordSymbols = DEF_MINIMUM_PASSWORD_SYMBOLS;

        static final int DEF_MINIMUM_PASSWORD_NON_LETTER = 0;
        int minimumPasswordNonLetter = DEF_MINIMUM_PASSWORD_NON_LETTER;

        static final long DEF_MAXIMUM_TIME_TO_UNLOCK = 0;
        long maximumTimeToUnlock = DEF_MAXIMUM_TIME_TO_UNLOCK;

        static final int DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE = 0;
        int maximumFailedPasswordsForWipe = DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE;

        static final long DEF_PASSWORD_EXPIRATION_TIMEOUT = 0;
        long passwordExpirationTimeout = DEF_PASSWORD_EXPIRATION_TIMEOUT;

        static final long DEF_PASSWORD_EXPIRATION_DATE = 0;
        long passwordExpirationDate = DEF_PASSWORD_EXPIRATION_DATE;

        static final int DEF_KEYGUARD_FEATURES_DISABLED = 0; // none

        int disabledKeyguardFeatures = DEF_KEYGUARD_FEATURES_DISABLED;

        boolean encryptionRequested = false;
        boolean disableCamera = false;
        boolean disableCallerId = false;
        boolean disableContactsSearch = false;
        boolean disableBluetoothContactSharing = true;
        boolean disableScreenCapture = false; // Can only be set by a device/profile owner.
        boolean requireAutoTime = false; // Can only be set by a device owner.
        boolean forceEphemeralUsers = false; // Can only be set by a device owner.

        ActiveAdmin parentAdmin;
        final boolean isParent;

        static class TrustAgentInfo {
            public PersistableBundle options;
            TrustAgentInfo(PersistableBundle bundle) {
                options = bundle;
            }
        }

        Set<String> accountTypesWithManagementDisabled = new ArraySet<>();

        // The list of permitted accessibility services package namesas set by a profile
        // or device owner. Null means all accessibility services are allowed, empty means
        // none except system services are allowed.
        List<String> permittedAccessiblityServices;

        // The list of permitted input methods package names as set by a profile or device owner.
        // Null means all input methods are allowed, empty means none except system imes are
        // allowed.
        List<String> permittedInputMethods;

        // List of package names to keep cached.
        List<String> keepUninstalledPackages;

        // TODO: review implementation decisions with frameworks team
        boolean specifiesGlobalProxy = false;
        String globalProxySpec = null;
        String globalProxyExclusionList = null;

        ArrayMap<String, TrustAgentInfo> trustAgentInfos = new ArrayMap<>();

        List<String> crossProfileWidgetProviders;

        Bundle userRestrictions;

        // Support text provided by the admin to display to the user.
        CharSequence shortSupportMessage = null;
        CharSequence longSupportMessage = null;

        // Background color of confirm credentials screen. Default: teal.
        static final int DEF_ORGANIZATION_COLOR = Color.parseColor("#00796B");
        int organizationColor = DEF_ORGANIZATION_COLOR;

        // Default title of confirm credentials screen
        String organizationName = null;

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
            if (passwordQuality != DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                out.startTag(null, TAG_PASSWORD_QUALITY);
                out.attribute(null, ATTR_VALUE, Integer.toString(passwordQuality));
                out.endTag(null, TAG_PASSWORD_QUALITY);
                if (minimumPasswordLength != DEF_MINIMUM_PASSWORD_LENGTH) {
                    out.startTag(null, TAG_MIN_PASSWORD_LENGTH);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordLength));
                    out.endTag(null, TAG_MIN_PASSWORD_LENGTH);
                }
                if(passwordHistoryLength != DEF_PASSWORD_HISTORY_LENGTH) {
                    out.startTag(null, TAG_PASSWORD_HISTORY_LENGTH);
                    out.attribute(null, ATTR_VALUE, Integer.toString(passwordHistoryLength));
                    out.endTag(null, TAG_PASSWORD_HISTORY_LENGTH);
                }
                if (minimumPasswordUpperCase != DEF_MINIMUM_PASSWORD_UPPER_CASE) {
                    out.startTag(null, TAG_MIN_PASSWORD_UPPERCASE);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordUpperCase));
                    out.endTag(null, TAG_MIN_PASSWORD_UPPERCASE);
                }
                if (minimumPasswordLowerCase != DEF_MINIMUM_PASSWORD_LOWER_CASE) {
                    out.startTag(null, TAG_MIN_PASSWORD_LOWERCASE);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordLowerCase));
                    out.endTag(null, TAG_MIN_PASSWORD_LOWERCASE);
                }
                if (minimumPasswordLetters != DEF_MINIMUM_PASSWORD_LETTERS) {
                    out.startTag(null, TAG_MIN_PASSWORD_LETTERS);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordLetters));
                    out.endTag(null, TAG_MIN_PASSWORD_LETTERS);
                }
                if (minimumPasswordNumeric != DEF_MINIMUM_PASSWORD_NUMERIC) {
                    out.startTag(null, TAG_MIN_PASSWORD_NUMERIC);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordNumeric));
                    out.endTag(null, TAG_MIN_PASSWORD_NUMERIC);
                }
                if (minimumPasswordSymbols != DEF_MINIMUM_PASSWORD_SYMBOLS) {
                    out.startTag(null, TAG_MIN_PASSWORD_SYMBOLS);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordSymbols));
                    out.endTag(null, TAG_MIN_PASSWORD_SYMBOLS);
                }
                if (minimumPasswordNonLetter > DEF_MINIMUM_PASSWORD_NON_LETTER) {
                    out.startTag(null, TAG_MIN_PASSWORD_NONLETTER);
                    out.attribute(null, ATTR_VALUE, Integer.toString(minimumPasswordNonLetter));
                    out.endTag(null, TAG_MIN_PASSWORD_NONLETTER);
                }
            }
            if (maximumTimeToUnlock != DEF_MAXIMUM_TIME_TO_UNLOCK) {
                out.startTag(null, TAG_MAX_TIME_TO_UNLOCK);
                out.attribute(null, ATTR_VALUE, Long.toString(maximumTimeToUnlock));
                out.endTag(null, TAG_MAX_TIME_TO_UNLOCK);
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
            if (disabledKeyguardFeatures != DEF_KEYGUARD_FEATURES_DISABLED) {
                out.startTag(null, TAG_DISABLE_KEYGUARD_FEATURES);
                out.attribute(null, ATTR_VALUE, Integer.toString(disabledKeyguardFeatures));
                out.endTag(null, TAG_DISABLE_KEYGUARD_FEATURES);
            }
            if (!accountTypesWithManagementDisabled.isEmpty()) {
                out.startTag(null, TAG_DISABLE_ACCOUNT_MANAGEMENT);
                for (String ac : accountTypesWithManagementDisabled) {
                    out.startTag(null, TAG_ACCOUNT_TYPE);
                    out.attribute(null, ATTR_VALUE, ac);
                    out.endTag(null, TAG_ACCOUNT_TYPE);
                }
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
                final int providerCount = crossProfileWidgetProviders.size();
                for (int i = 0; i < providerCount; i++) {
                    String provider = crossProfileWidgetProviders.get(i);
                    out.startTag(null, TAG_PROVIDER);
                    out.attribute(null, ATTR_VALUE, provider);
                    out.endTag(null, TAG_PROVIDER);
                }
                out.endTag(null, TAG_CROSS_PROFILE_WIDGET_PROVIDERS);
            }
            writePackageListToXml(out, TAG_PERMITTED_ACCESSIBILITY_SERVICES,
                    permittedAccessiblityServices);
            writePackageListToXml(out, TAG_PERMITTED_IMES, permittedInputMethods);
            writePackageListToXml(out, TAG_KEEP_UNINSTALLED_PACKAGES, keepUninstalledPackages);
            if (hasUserRestrictions()) {
                UserRestrictionsUtils.writeRestrictions(
                        out, userRestrictions, TAG_USER_RESTRICTIONS);
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
        }

        void writePackageListToXml(XmlSerializer out, String outerTag,
                List<String> packageList)
                throws IllegalArgumentException, IllegalStateException, IOException {
            if (packageList == null) {
                return;
            }

            out.startTag(null, outerTag);
            for (String packageName : packageList) {
                out.startTag(null, TAG_PACKAGE_LIST_ITEM);
                out.attribute(null, ATTR_VALUE, packageName);
                out.endTag(null, TAG_PACKAGE_LIST_ITEM);
            }
            out.endTag(null, outerTag);
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
                    passwordQuality = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_LENGTH.equals(tag)) {
                    minimumPasswordLength = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_PASSWORD_HISTORY_LENGTH.equals(tag)) {
                    passwordHistoryLength = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_UPPERCASE.equals(tag)) {
                    minimumPasswordUpperCase = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_LOWERCASE.equals(tag)) {
                    minimumPasswordLowerCase = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_LETTERS.equals(tag)) {
                    minimumPasswordLetters = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_NUMERIC.equals(tag)) {
                    minimumPasswordNumeric = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_SYMBOLS.equals(tag)) {
                    minimumPasswordSymbols = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MIN_PASSWORD_NONLETTER.equals(tag)) {
                    minimumPasswordNonLetter = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_MAX_TIME_TO_UNLOCK.equals(tag)) {
                    maximumTimeToUnlock = Long.parseLong(
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
                } else if (TAG_DISABLE_KEYGUARD_FEATURES.equals(tag)) {
                    disabledKeyguardFeatures = Integer.parseInt(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_ACCOUNT_MANAGEMENT.equals(tag)) {
                    accountTypesWithManagementDisabled = readDisableAccountInfo(parser, tag);
                } else if (TAG_MANAGE_TRUST_AGENT_FEATURES.equals(tag)) {
                    trustAgentInfos = getAllTrustAgentInfos(parser, tag);
                } else if (TAG_CROSS_PROFILE_WIDGET_PROVIDERS.equals(tag)) {
                    crossProfileWidgetProviders = getCrossProfileWidgetProviders(parser, tag);
                } else if (TAG_PERMITTED_ACCESSIBILITY_SERVICES.equals(tag)) {
                    permittedAccessiblityServices = readPackageList(parser, tag);
                } else if (TAG_PERMITTED_IMES.equals(tag)) {
                    permittedInputMethods = readPackageList(parser, tag);
                } else if (TAG_KEEP_UNINSTALLED_PACKAGES.equals(tag)) {
                    keepUninstalledPackages = readPackageList(parser, tag);
                } else if (TAG_USER_RESTRICTIONS.equals(tag)) {
                    UserRestrictionsUtils.readRestrictions(parser, ensureUserRestrictions());
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

        private Set<String> readDisableAccountInfo(XmlPullParser parser, String tag)
                throws XmlPullParserException, IOException {
            int outerDepthDAM = parser.getDepth();
            int typeDAM;
            Set<String> result = new ArraySet<>();
            while ((typeDAM=parser.next()) != END_DOCUMENT
                    && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
                if (typeDAM == END_TAG || typeDAM == TEXT) {
                    continue;
                }
                String tagDAM = parser.getName();
                if (TAG_ACCOUNT_TYPE.equals(tagDAM)) {
                    result.add(parser.getAttributeValue(null, ATTR_VALUE));
                } else {
                    Slog.w(LOG_TAG, "Unknown tag under " + tag +  ": " + tagDAM);
                }
            }
            return result;
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

        private List<String> getCrossProfileWidgetProviders(XmlPullParser parser, String tag)
                throws XmlPullParserException, IOException  {
            int outerDepthDAM = parser.getDepth();
            int typeDAM;
            ArrayList<String> result = null;
            while ((typeDAM=parser.next()) != END_DOCUMENT
                    && (typeDAM != END_TAG || parser.getDepth() > outerDepthDAM)) {
                if (typeDAM == END_TAG || typeDAM == TEXT) {
                    continue;
                }
                String tagDAM = parser.getName();
                if (TAG_PROVIDER.equals(tagDAM)) {
                    final String provider = parser.getAttributeValue(null, ATTR_VALUE);
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(provider);
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

        void dump(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("uid="); pw.println(getUid());
            pw.print(prefix); pw.println("policies:");
            ArrayList<DeviceAdminInfo.PolicyInfo> pols = info.getUsedPolicies();
            if (pols != null) {
                for (int i=0; i<pols.size(); i++) {
                    pw.print(prefix); pw.print("  "); pw.println(pols.get(i).tag);
                }
            }
            pw.print(prefix); pw.print("passwordQuality=0x");
                    pw.println(Integer.toHexString(passwordQuality));
            pw.print(prefix); pw.print("minimumPasswordLength=");
                    pw.println(minimumPasswordLength);
            pw.print(prefix); pw.print("passwordHistoryLength=");
                    pw.println(passwordHistoryLength);
            pw.print(prefix); pw.print("minimumPasswordUpperCase=");
                    pw.println(minimumPasswordUpperCase);
            pw.print(prefix); pw.print("minimumPasswordLowerCase=");
                    pw.println(minimumPasswordLowerCase);
            pw.print(prefix); pw.print("minimumPasswordLetters=");
                    pw.println(minimumPasswordLetters);
            pw.print(prefix); pw.print("minimumPasswordNumeric=");
                    pw.println(minimumPasswordNumeric);
            pw.print(prefix); pw.print("minimumPasswordSymbols=");
                    pw.println(minimumPasswordSymbols);
            pw.print(prefix); pw.print("minimumPasswordNonLetter=");
                    pw.println(minimumPasswordNonLetter);
            pw.print(prefix); pw.print("maximumTimeToUnlock=");
                    pw.println(maximumTimeToUnlock);
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
            pw.print(prefix); pw.print("isParent=");
                    pw.println(isParent);
            if (parentAdmin != null) {
                pw.print(prefix);  pw.println("parentAdmin:");
                parentAdmin.dump(prefix + "  ", pw);
            }
        }
    }

    private void handlePackagesChanged(String packageName, int userHandle) {
        boolean removed = false;
        if (VERBOSE_LOG) Slog.d(LOG_TAG, "Handling package changes for user " + userHandle);
        DevicePolicyData policy = getUserData(userHandle);
        synchronized (this) {
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
                            removed = true;
                            policy.mAdminList.remove(i);
                            policy.mAdminMap.remove(aa.info.getComponent());
                        }
                    }
                } catch (RemoteException re) {
                    // Shouldn't happen
                }
            }
            if (removed) {
                validatePasswordOwnerLocked(policy);
                saveSettingsLocked(policy.mUserHandle);
            }

            // Check if delegated cert installer or app restrictions managing packages are removed.
            if (isRemovedPackage(packageName, policy.mDelegatedCertInstallerPackage, userHandle)) {
                policy.mDelegatedCertInstallerPackage = null;
                saveSettingsLocked(policy.mUserHandle);
            }
            if (isRemovedPackage(
                    packageName, policy.mApplicationRestrictionsManagingPackage, userHandle)) {
                policy.mApplicationRestrictionsManagingPackage = null;
                saveSettingsLocked(policy.mUserHandle);
            }
        }
        if (removed) {
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

        private final Context mContext;

        Injector(Context context) {
            mContext = context;
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

        NotificationManager getNotificationManager() {
            return mContext.getSystemService(NotificationManager.class);
        }

        PowerManagerInternal getPowerManagerInternal() {
            return LocalServices.getService(PowerManagerInternal.class);
        }

        TelephonyManager getTelephonyManager() {
            return TelephonyManager.from(mContext);
        }

        IWindowManager getIWindowManager() {
            return IWindowManager.Stub
                    .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        }

        IActivityManager getIActivityManager() {
            return ActivityManagerNative.getDefault();
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

        void registerContentObserver(Uri uri, boolean notifyForDescendents,
                ContentObserver observer, int userHandle) {
            mContext.getContentResolver().registerContentObserver(uri, notifyForDescendents,
                    observer, userHandle);
        }

        int settingsSecureGetIntForUser(String name, int def, int userHandle) {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    name, def, userHandle);
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

        void settingsGlobalPutInt(String name, int value) {
            Settings.Global.putInt(mContext.getContentResolver(), name, value);
        }

        void settingsSecurePutString(String name, String value) {
            Settings.Secure.putString(mContext.getContentResolver(), name, value);
        }

        void settingsGlobalPutString(String name, String value) {
            Settings.Global.putString(mContext.getContentResolver(), name, value);
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
        mOwners = Preconditions.checkNotNull(injector.newOwners());

        mUserManager = Preconditions.checkNotNull(injector.getUserManager());
        mUserManagerInternal = Preconditions.checkNotNull(injector.getUserManagerInternal());
        mIPackageManager = Preconditions.checkNotNull(injector.getIPackageManager());
        mTelephonyManager = Preconditions.checkNotNull(injector.getTelephonyManager());

        mLocalService = new LocalService();
        mLockPatternUtils = injector.newLockPatternUtils();

        mSecurityLogMonitor = new SecurityLogMonitor(this);

        mHasFeature = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN);
        if (!mHasFeature) {
            // Skip the rest of the initialization
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(ACTION_EXPIRED_PASSWORD_NOTIFICATION);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_STARTED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
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
        mContext.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);

        LocalServices.addService(DevicePolicyManagerInternal.class, mLocalService);
    }

    /**
     * Creates and loads the policy data from xml.
     * @param userHandle the user for whom to load the policy data
     * @return
     */
    @NonNull
    DevicePolicyData getUserData(int userHandle) {
        synchronized (this) {
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
        synchronized (this) {
            if (userHandle == UserHandle.USER_SYSTEM) {
                Slog.w(LOG_TAG, "Tried to remove device policy file for user 0! Ignoring.");
                return;
            }
            mOwners.removeProfileOwner(userHandle);
            mOwners.writeProfileOwner(userHandle);

            DevicePolicyData policy = mUserData.get(userHandle);
            if (policy != null) {
                mUserData.remove(userHandle);
            }
            File policyFile = new File(mInjector.environmentGetUserSystemDirectory(userHandle),
                    DEVICE_POLICIES_XML);
            policyFile.delete();
            Slog.i(LOG_TAG, "Removed device policy file " + policyFile.getAbsolutePath());
        }
        updateScreenCaptureDisabledInWindowManager(userHandle, false /* default value */);
    }

    void loadOwners() {
        synchronized (this) {
            mOwners.load();
            setDeviceOwnerSystemPropertyLocked();
            findOwnerComponentIfNecessaryLocked();
            migrateUserRestrictionsIfNecessaryLocked();

            // TODO PO may not have a class name either due to b/17652534.  Address that too.

            updateDeviceOwnerLocked();
        }
    }

    private void setDeviceOwnerSystemPropertyLocked() {
        // Device owner may still be provisioned, do not set the read-only system property yet.
        if (mInjector.settingsGlobalGetInt(Settings.Global.DEVICE_PROVISIONED, 0) == 0) {
            return;
        }
        // Still at the first stage of CryptKeeper double bounce, mOwners.hasDeviceOwner is
        // always false at this point.
        if (StorageManager.inCryptKeeperBounce()) {
            return;
        }

        if (!TextUtils.isEmpty(mInjector.systemPropertiesGet(PROPERTY_DEVICE_OWNER_PRESENT))) {
            Slog.w(LOG_TAG, "Trying to set ro.device_owner, but it has already been set?");
        } else {
            if (mOwners.hasDeviceOwner()) {
                mInjector.systemPropertiesSet(PROPERTY_DEVICE_OWNER_PRESENT, "true");
                Slog.i(LOG_TAG, "Set ro.device_owner property to true");
                disableSecurityLoggingIfNotCompliant();
                if (mInjector.securityLogGetLoggingEnabledProperty()) {
                    mSecurityLogMonitor.start();
                }
            } else {
                mInjector.systemPropertiesSet(PROPERTY_DEVICE_OWNER_PRESENT, "false");
                Slog.i(LOG_TAG, "Set ro.device_owner property to false");
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
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
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
        ActiveAdmin admin = getUserData(userHandle).mAdminMap.get(who);
        if (admin != null
                && who.getPackageName().equals(admin.info.getActivityInfo().packageName)
                && who.getClassName().equals(admin.info.getActivityInfo().name)) {
            return admin;
        }
        return null;
    }

    ActiveAdmin getActiveAdminUncheckedLocked(ComponentName who, int userHandle, boolean parent) {
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

    /**
     * Send an update to one specific admin, get notified when that admin returns a result.
     */
    void sendAdminCommandLocked(ActiveAdmin admin, String action, Bundle adminExtras,
            BroadcastReceiver result) {
        Intent intent = new Intent(action);
        intent.setComponent(admin.info.getComponent());
        if (action.equals(DeviceAdminReceiver.ACTION_PASSWORD_EXPIRING)) {
            intent.putExtra("expiration", admin.passwordExpirationDate);
        }
        if (adminExtras != null) {
            intent.putExtras(adminExtras);
        }
        if (result != null) {
            mContext.sendOrderedBroadcastAsUser(intent, admin.getUserHandle(),
                    null, result, mHandler, Activity.RESULT_OK, null, null);
        } else {
            mContext.sendBroadcastAsUser(intent, admin.getUserHandle());
        }
    }

    /**
     * Send an update to all admins of a user that enforce a specified policy.
     */
    void sendAdminCommandLocked(String action, int reqPolicy, int userHandle) {
        final DevicePolicyData policy = getUserData(userHandle);
        final int count = policy.mAdminList.size();
        if (count > 0) {
            for (int i = 0; i < count; i++) {
                final ActiveAdmin admin = policy.mAdminList.get(i);
                if (admin.info.usesPolicy(reqPolicy)) {
                    sendAdminCommandLocked(admin, action);
                }
            }
        }
    }

    /**
     * Send an update intent to all admins of a user and its profiles. Only send to admins that
     * enforce a specified policy.
     */
    private void sendAdminCommandToSelfAndProfilesLocked(String action, int reqPolicy,
            int userHandle) {
        int[] profileIds = mUserManager.getProfileIdsWithDisabled(userHandle);
        for (int profileId : profileIds) {
            sendAdminCommandLocked(action, reqPolicy, profileId);
        }
    }

    /**
     * Sends a broadcast to each profile that share the password unlock with the given user id.
     */
    private void sendAdminCommandForLockscreenPoliciesLocked(
            String action, int reqPolicy, int userHandle) {
        if (isSeparateProfileChallengeEnabled(userHandle)) {
            sendAdminCommandLocked(action, reqPolicy, userHandle);
        } else {
            sendAdminCommandToSelfAndProfilesLocked(action, reqPolicy, userHandle);
        }
    }

    void removeActiveAdminLocked(final ComponentName adminReceiver, final int userHandle) {
        final ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
        if (admin != null) {
            getUserData(userHandle).mRemovingAdmins.add(adminReceiver);
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
            boolean throwForMissiongPermission) {
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
            if (throwForMissiongPermission &&
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

    private JournaledFile makeJournaledFile(int userHandle) {
        final String base = userHandle == UserHandle.USER_SYSTEM
                ? mInjector.getDevicePolicyFilePathForSystemUser() + DEVICE_POLICIES_XML
                : new File(mInjector.environmentGetUserSystemDirectory(userHandle),
                        DEVICE_POLICIES_XML).getAbsolutePath();
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
            if (policy.mUserProvisioningState != DevicePolicyManager.STATE_USER_UNMANAGED) {
                out.attribute(null, ATTR_PROVISIONING_STATE,
                        Integer.toString(policy.mUserProvisioningState));
            }
            if (policy.mPermissionPolicy != DevicePolicyManager.PERMISSION_POLICY_PROMPT) {
                out.attribute(null, ATTR_PERMISSION_POLICY,
                        Integer.toString(policy.mPermissionPolicy));
            }
            if (policy.mDelegatedCertInstallerPackage != null) {
                out.attribute(null, ATTR_DELEGATED_CERT_INSTALLER,
                        policy.mDelegatedCertInstallerPackage);
            }
            if (policy.mApplicationRestrictionsManagingPackage != null) {
                out.attribute(null, ATTR_APPLICATION_RESTRICTIONS_MANAGER,
                        policy.mApplicationRestrictionsManagingPackage);
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

            if (policy.mActivePasswordQuality != 0 || policy.mActivePasswordLength != 0
                    || policy.mActivePasswordUpperCase != 0 || policy.mActivePasswordLowerCase != 0
                    || policy.mActivePasswordLetters != 0 || policy.mActivePasswordNumeric != 0
                    || policy.mActivePasswordSymbols != 0 || policy.mActivePasswordNonLetter != 0) {
                out.startTag(null, "active-password");
                out.attribute(null, "quality", Integer.toString(policy.mActivePasswordQuality));
                out.attribute(null, "length", Integer.toString(policy.mActivePasswordLength));
                out.attribute(null, "uppercase", Integer.toString(policy.mActivePasswordUpperCase));
                out.attribute(null, "lowercase", Integer.toString(policy.mActivePasswordLowerCase));
                out.attribute(null, "letters", Integer.toString(policy.mActivePasswordLetters));
                out.attribute(null, "numeric", Integer
                        .toString(policy.mActivePasswordNumeric));
                out.attribute(null, "symbols", Integer.toString(policy.mActivePasswordSymbols));
                out.attribute(null, "nonletter", Integer.toString(policy.mActivePasswordNonLetter));
                out.endTag(null, "active-password");
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
                out.attribute(null, "id", id);
                out.endTag(null, TAG_AFFILIATION_ID);
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
            String provisioningState = parser.getAttributeValue(null, ATTR_PROVISIONING_STATE);
            if (!TextUtils.isEmpty(provisioningState)) {
                policy.mUserProvisioningState = Integer.parseInt(provisioningState);
            }
            String permissionPolicy = parser.getAttributeValue(null, ATTR_PERMISSION_POLICY);
            if (!TextUtils.isEmpty(permissionPolicy)) {
                policy.mPermissionPolicy = Integer.parseInt(permissionPolicy);
            }
            policy.mDelegatedCertInstallerPackage = parser.getAttributeValue(null,
                    ATTR_DELEGATED_CERT_INSTALLER);
            policy.mApplicationRestrictionsManagingPackage = parser.getAttributeValue(null,
                    ATTR_APPLICATION_RESTRICTIONS_MANAGER);

            type = parser.next();
            int outerDepth = parser.getDepth();
            policy.mLockTaskPackages.clear();
            policy.mAdminList.clear();
            policy.mAdminMap.clear();
            policy.mAffiliationIds.clear();
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
                                /* throwForMissionPermission= */ false);
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
                } else if ("failed-password-attempts".equals(tag)) {
                    policy.mFailedPasswordAttempts = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                } else if ("password-owner".equals(tag)) {
                    policy.mPasswordOwner = Integer.parseInt(
                            parser.getAttributeValue(null, "value"));
                } else if ("active-password".equals(tag)) {
                    policy.mActivePasswordQuality = Integer.parseInt(
                            parser.getAttributeValue(null, "quality"));
                    policy.mActivePasswordLength = Integer.parseInt(
                            parser.getAttributeValue(null, "length"));
                    policy.mActivePasswordUpperCase = Integer.parseInt(
                            parser.getAttributeValue(null, "uppercase"));
                    policy.mActivePasswordLowerCase = Integer.parseInt(
                            parser.getAttributeValue(null, "lowercase"));
                    policy.mActivePasswordLetters = Integer.parseInt(
                            parser.getAttributeValue(null, "letters"));
                    policy.mActivePasswordNumeric = Integer.parseInt(
                            parser.getAttributeValue(null, "numeric"));
                    policy.mActivePasswordSymbols = Integer.parseInt(
                            parser.getAttributeValue(null, "symbols"));
                    policy.mActivePasswordNonLetter = Integer.parseInt(
                            parser.getAttributeValue(null, "nonletter"));
                } else if (TAG_ACCEPTED_CA_CERTIFICATES.equals(tag)) {
                    policy.mAcceptedCaCertificates.add(parser.getAttributeValue(null, ATTR_NAME));
                } else if (TAG_LOCK_TASK_COMPONENTS.equals(tag)) {
                    policy.mLockTaskPackages.add(parser.getAttributeValue(null, "name"));
                } else if (TAG_STATUS_BAR.equals(tag)) {
                    policy.mStatusBarDisabled = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_DISABLED));
                } else if (DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML.equals(tag)) {
                    policy.doNotAskCredentialsOnBoot = true;
                } else if (TAG_AFFILIATION_ID.equals(tag)) {
                    policy.mAffiliationIds.add(parser.getAttributeValue(null, "id"));
                } else if (TAG_ADMIN_BROADCAST_PENDING.equals(tag)) {
                    String pending = parser.getAttributeValue(null, ATTR_VALUE);
                    policy.mAdminBroadcastPending = Boolean.toString(true).equals(pending);
                } else if (TAG_INITIALIZATION_BUNDLE.equals(tag)) {
                    policy.mInitBundle = PersistableBundle.restoreFromXml(parser);
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

        // Validate that what we stored for the password quality matches
        // sufficiently what is currently set.  Note that this is only
        // a sanity check in case the two get out of sync; this should
        // never normally happen.
        final long identity = mInjector.binderClearCallingIdentity();
        try {
            int actualPasswordQuality = mLockPatternUtils.getActivePasswordQuality(userHandle);
            if (actualPasswordQuality < policy.mActivePasswordQuality) {
                Slog.w(LOG_TAG, "Active password quality 0x"
                        + Integer.toHexString(policy.mActivePasswordQuality)
                        + " does not match actual quality 0x"
                        + Integer.toHexString(actualPasswordQuality));
                policy.mActivePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
                policy.mActivePasswordLength = 0;
                policy.mActivePasswordUpperCase = 0;
                policy.mActivePasswordLowerCase = 0;
                policy.mActivePasswordLetters = 0;
                policy.mActivePasswordNumeric = 0;
                policy.mActivePasswordSymbols = 0;
                policy.mActivePasswordNonLetter = 0;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(identity);
        }

        validatePasswordOwnerLocked(policy);
        updateMaximumTimeToLockLocked(userHandle);
        updateLockTaskPackagesLocked(policy.mLockTaskPackages, userHandle);
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
    void systemReady(int phase) {
        if (!mHasFeature) {
            return;
        }
        switch (phase) {
            case SystemService.PHASE_LOCK_SETTINGS_READY:
                onLockSettingsReady();
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

        onStartUser(UserHandle.USER_SYSTEM);

        // Register an observer for watching for user setup complete.
        new SetupContentObserver(mHandler).register();
        // Initialize the user setup state, to handle the upgrade case.
        updateUserSetupComplete();

        List<String> packageList;
        synchronized (this) {
            packageList = getKeepUninstalledPackagesLocked();
        }
        if (packageList != null) {
            mInjector.getPackageManagerInternal().setKeepUninstalledPackages(packageList);
        }

        synchronized (this) {
            // push the force-ephemeral-users policy to the user manager.
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            if (deviceOwner != null) {
                mUserManagerInternal.setForceEphemeralUsers(deviceOwner.forceEphemeralUsers);
            }
        }
    }

    private void ensureDeviceOwnerUserStarted() {
        final int userId;
        synchronized (this) {
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

    private void onStartUser(int userId) {
        updateScreenCaptureDisabledInWindowManager(userId,
                getScreenCaptureDisabled(null, userId));
        pushUserRestrictions(userId);
    }

    private void cleanUpOldUsers() {
        // This is needed in case the broadcast {@link Intent.ACTION_USER_REMOVED} was not handled
        // before reboot
        Set<Integer> usersWithProfileOwners;
        Set<Integer> usersWithData;
        synchronized(this) {
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
        synchronized (this) {
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
                            DeviceAdminReceiver.ACTION_PASSWORD_EXPIRING);
                }
            }
            setExpirationAlarmCheckLocked(mContext, userHandle, /* parent */ false);
        }
    }

    private class MonitoringCertNotificationTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(Integer... params) {
            int userHandle = params[0];

            if (userHandle == UserHandle.USER_ALL) {
                for (UserInfo userInfo : mUserManager.getUsers(true)) {
                    manageNotification(userInfo.getUserHandle());
                }
            } else {
                manageNotification(UserHandle.of(userHandle));
            }
            return null;
        }

        private void manageNotification(UserHandle userHandle) {
            if (!mUserManager.isUserUnlocked(userHandle)) {
                return;
            }

            // Call out to KeyChain to check for CAs which are waiting for approval.
            final List<String> pendingCertificates;
            try {
                pendingCertificates = getInstalledCaCertificates(userHandle);
            } catch (RemoteException | RuntimeException e) {
                Log.e(LOG_TAG, "Could not retrieve certificates from KeyChain service", e);
                return;
            }

            synchronized (DevicePolicyManagerService.this) {
                final DevicePolicyData policy = getUserData(userHandle.getIdentifier());

                // Remove deleted certificates. Flush xml if necessary.
                if (policy.mAcceptedCaCertificates.retainAll(pendingCertificates)) {
                    saveSettingsLocked(userHandle.getIdentifier());
                }
                // Trim to approved certificates.
                pendingCertificates.removeAll(policy.mAcceptedCaCertificates);
            }

            if (pendingCertificates.isEmpty()) {
                mInjector.getNotificationManager().cancelAsUser(
                        null, MONITORING_CERT_NOTIFICATION_ID, userHandle);
                return;
            }

            // Build and show a warning notification
            int smallIconId;
            String contentText;
            int parentUserId = userHandle.getIdentifier();
            if (getProfileOwner(userHandle.getIdentifier()) != null) {
                contentText = mContext.getString(R.string.ssl_ca_cert_noti_managed,
                        getProfileOwnerName(userHandle.getIdentifier()));
                smallIconId = R.drawable.stat_sys_certificate_info;
                parentUserId = getProfileParentId(userHandle.getIdentifier());
            } else if (getDeviceOwnerUserId() == userHandle.getIdentifier()) {
                contentText = mContext.getString(R.string.ssl_ca_cert_noti_managed,
                        getDeviceOwnerName());
                smallIconId = R.drawable.stat_sys_certificate_info;
            } else {
                contentText = mContext.getString(R.string.ssl_ca_cert_noti_by_unknown);
                smallIconId = android.R.drawable.stat_sys_warning;
            }

            final int numberOfCertificates = pendingCertificates.size();
            Intent dialogIntent = new Intent(Settings.ACTION_MONITORING_CERT_INFO);
            dialogIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            dialogIntent.setPackage("com.android.settings");
            dialogIntent.putExtra(Settings.EXTRA_NUMBER_OF_CERTIFICATES, numberOfCertificates);
            dialogIntent.putExtra(Intent.EXTRA_USER_ID, userHandle.getIdentifier());
            PendingIntent notifyIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    dialogIntent, PendingIntent.FLAG_UPDATE_CURRENT, null,
                    new UserHandle(parentUserId));

            final Context userContext;
            try {
                final String packageName = mContext.getPackageName();
                userContext = mContext.createPackageContextAsUser(packageName, 0, userHandle);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(LOG_TAG, "Create context as " + userHandle + " failed", e);
                return;
            }
            final Notification noti = new Notification.Builder(userContext)
                .setSmallIcon(smallIconId)
                .setContentTitle(mContext.getResources().getQuantityText(
                        R.plurals.ssl_ca_cert_warning, numberOfCertificates))
                .setContentText(contentText)
                .setContentIntent(notifyIntent)
                .setPriority(Notification.PRIORITY_HIGH)
                .setShowWhen(false)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .build();

            mInjector.getNotificationManager().notifyAsUser(
                    null, MONITORING_CERT_NOTIFICATION_ID, noti, userHandle);
        }

        private List<String> getInstalledCaCertificates(UserHandle userHandle)
                throws RemoteException, RuntimeException {
            KeyChainConnection conn = null;
            try {
                conn = KeyChain.bindAsUser(mContext, userHandle);
                List<ParcelableString> aliases = conn.getService().getUserCaAliases().getList();
                List<String> result = new ArrayList<>(aliases.size());
                for (int i = 0; i < aliases.size(); i++) {
                    result.add(aliases.get(i).string);
                }
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (AssertionError e) {
                throw new RuntimeException(e);
            } finally {
                if (conn != null) {
                    conn.close();
                }
            }
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
                /* throwForMissionPermission= */ true);
        if (info == null) {
            throw new IllegalArgumentException("Bad admin: " + adminReceiver);
        }
        if (!info.getActivityInfo().applicationInfo.isInternal()) {
            throw new IllegalArgumentException("Only apps in internal storage can be active admin: "
                    + adminReceiver);
        }
        synchronized (this) {
            long ident = mInjector.binderClearCallingIdentity();
            try {
                if (!refreshing
                        && getActiveAdminUncheckedLocked(adminReceiver, userHandle) != null) {
                    throw new IllegalArgumentException("Admin is already added");
                }
                if (policy.mRemovingAdmins.contains(adminReceiver)) {
                    throw new IllegalArgumentException(
                            "Trying to set an admin which is being removed");
                }
                ActiveAdmin newAdmin = new ActiveAdmin(info, /* parent */ false);
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

    @Override
    public boolean isAdminActive(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            return getActiveAdminUncheckedLocked(adminReceiver, userHandle) != null;
        }
    }

    @Override
    public boolean isRemovingAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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

    public void forceRemoveActiveAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(adminReceiver, "ComponentName is null");
        enforceShell("forceRemoveActiveAdmin");
        long ident = mInjector.binderClearCallingIdentity();
        try {
            final ApplicationInfo ai;
            try {
                ai = mIPackageManager.getApplicationInfo(adminReceiver.getPackageName(),
                        0, userHandle);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
            if (ai == null) {
                throw new IllegalStateException("Couldn't find package to remove admin "
                        + adminReceiver.getPackageName() + " " + userHandle);
            }
            if ((ai.flags & ApplicationInfo.FLAG_TEST_ONLY) == 0) {
                throw new SecurityException("Attempt to remove non-test admin " + adminReceiver
                        + adminReceiver + " " + userHandle);
            }
            // If admin is a device or profile owner tidy that up first.
            synchronized (this)  {
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
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    private void enforceShell(String method) {
        final int callingUid = Binder.getCallingUid();
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
        synchronized (this) {
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

        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.passwordQuality != quality) {
                ap.passwordQuality = quality;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordQuality(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int mode = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.passwordQuality : mode;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (mode < admin.passwordQuality) {
                    mode = admin.passwordQuality;
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
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordLength != length) {
                ap.minimumPasswordLength = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumLength(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordLength : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (length < admin.minimumPasswordLength) {
                    length = admin.minimumPasswordLength;
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordHistoryLength(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.passwordHistoryLength != length) {
                ap.passwordHistoryLength = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordHistoryLength(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.passwordHistoryLength : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (length < admin.passwordHistoryLength) {
                    length = admin.passwordHistoryLength;
                }
            }

            return length;
        }
    }

    @Override
    public void setPasswordExpirationTimeout(ComponentName who, long timeout, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgumentNonnegative(timeout, "Timeout must be >= 0 ms");
        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (this) {
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
        synchronized (this) {
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

        synchronized (this) {
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

        synchronized (this) {
            ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(admin,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (activeAdmin.crossProfileWidgetProviders == null) {
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
        synchronized (this) {
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
        synchronized (this) {
            return getPasswordExpirationLocked(who, userHandle, parent);
        }
    }

    @Override
    public void setPasswordMinimumUpperCase(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordUpperCase != length) {
                ap.minimumPasswordUpperCase = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumUpperCase(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordUpperCase : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (length < admin.minimumPasswordUpperCase) {
                    length = admin.minimumPasswordUpperCase;
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumLowerCase(ComponentName who, int length, boolean parent) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordLowerCase != length) {
                ap.minimumPasswordLowerCase = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumLowerCase(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordLowerCase : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (length < admin.minimumPasswordLowerCase) {
                    length = admin.minimumPasswordLowerCase;
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumLetters(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordLetters != length) {
                ap.minimumPasswordLetters = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumLetters(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordLetters : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                    continue;
                }
                if (length < admin.minimumPasswordLetters) {
                    length = admin.minimumPasswordLetters;
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumNumeric(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordNumeric != length) {
                ap.minimumPasswordNumeric = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumNumeric(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordNumeric : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                    continue;
                }
                if (length < admin.minimumPasswordNumeric) {
                    length = admin.minimumPasswordNumeric;
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumSymbols(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordSymbols != length) {
                ap.minimumPasswordSymbols = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumSymbols(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordSymbols : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                    continue;
                }
                if (length < admin.minimumPasswordSymbols) {
                    length = admin.minimumPasswordSymbols;
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumNonLetter(ComponentName who, int length, boolean parent) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            if (ap.minimumPasswordNonLetter != length) {
                ap.minimumPasswordNonLetter = length;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getPasswordMinimumNonLetter(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.minimumPasswordNonLetter : length;
            }

            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins =
                    getActiveAdminsForLockscreenPoliciesLocked(userHandle, parent);
            final int N = admins.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = admins.get(i);
                if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                    continue;
                }
                if (length < admin.minimumPasswordNonLetter) {
                    length = admin.minimumPasswordNonLetter;
                }
            }
            return length;
        }
    }

    @Override
    public boolean isActivePasswordSufficient(int userHandle, boolean parent) {
        if (!mHasFeature) {
            return true;
        }
        enforceFullCrossUsersPermission(userHandle);

        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, parent);
            DevicePolicyData policy = getUserDataUnchecked(getCredentialOwner(userHandle, parent));
            return isActivePasswordSufficientForUserLocked(policy, userHandle, parent);
        }
    }

    @Override
    public boolean isProfileActivePasswordSufficientForParent(int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "call APIs refering to the parent profile");

        synchronized (this) {
            int targetUser = getProfileParentId(userHandle);
            DevicePolicyData policy = getUserDataUnchecked(getCredentialOwner(userHandle, false));
            return isActivePasswordSufficientForUserLocked(policy, targetUser, false);
        }
    }

    private boolean isActivePasswordSufficientForUserLocked(
            DevicePolicyData policy, int userHandle, boolean parent) {
        if (policy.mActivePasswordQuality < getPasswordQuality(null, userHandle, parent)
                || policy.mActivePasswordLength < getPasswordMinimumLength(
                        null, userHandle, parent)) {
            return false;
        }
        if (policy.mActivePasswordQuality != DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
            return true;
        }
        return policy.mActivePasswordUpperCase >= getPasswordMinimumUpperCase(
                    null, userHandle, parent)
                && policy.mActivePasswordLowerCase >= getPasswordMinimumLowerCase(
                        null, userHandle, parent)
                && policy.mActivePasswordLetters >= getPasswordMinimumLetters(
                        null, userHandle, parent)
                && policy.mActivePasswordNumeric >= getPasswordMinimumNumeric(
                        null, userHandle, parent)
                && policy.mActivePasswordSymbols >= getPasswordMinimumSymbols(
                        null, userHandle, parent)
                && policy.mActivePasswordNonLetter >= getPasswordMinimumNonLetter(
                        null, userHandle, parent);
    }

    @Override
    public int getCurrentFailedPasswordAttempts(int userHandle, boolean parent) {
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            if (!isCallerWithSystemUid()) {
                // This API can only be called by an active device admin,
                // so try to retrieve it to check that the caller is one.
                getActiveAdminForCallerLocked(
                        null, DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, parent);
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
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_WIPE_DATA, parent);
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, parent);
            if (ap.maximumFailedPasswordsForWipe != num) {
                ap.maximumFailedPasswordsForWipe = num;
                saveSettingsLocked(mInjector.userHandleGetCallingUserId());
            }
        }
    }

    @Override
    public int getMaximumFailedPasswordsForWipe(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
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
        synchronized (this) {
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

    @Override
    public boolean resetPassword(String passwordOrNull, int flags) throws RemoteException {
        if (!mHasFeature) {
            return false;
        }
        final int callingUid = mInjector.binderGetCallingUid();
        final int userHandle = mInjector.userHandleGetCallingUserId();

        String password = passwordOrNull != null ? passwordOrNull : "";

        // Password resetting to empty/null is not allowed for managed profiles.
        if (TextUtils.isEmpty(password)) {
            enforceNotManagedProfile(userHandle, "clear the active password");
        }

        int quality;
        synchronized (this) {
            // If caller has PO (or DO) it can change the password, so see if that's the case first.
            ActiveAdmin admin = getActiveAdminWithPolicyForUidLocked(
                    null, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, callingUid);
            final boolean preN;
            if (admin != null) {
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

            quality = getPasswordQuality(null, userHandle, /* parent */ false);
            if (quality == DevicePolicyManager.PASSWORD_QUALITY_MANAGED) {
                quality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
            }
            if (quality != DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                int realQuality = LockPatternUtils.computePasswordQuality(password);
                if (realQuality < quality
                        && quality != DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
                    Slog.w(LOG_TAG, "resetPassword: password quality 0x"
                            + Integer.toHexString(realQuality)
                            + " does not meet required quality 0x"
                            + Integer.toHexString(quality));
                    return false;
                }
                quality = Math.max(realQuality, quality);
            }
            int length = getPasswordMinimumLength(null, userHandle, /* parent */ false);
            if (password.length() < length) {
                Slog.w(LOG_TAG, "resetPassword: password length " + password.length()
                        + " does not meet required length " + length);
                return false;
            }
            if (quality == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
                int letters = 0;
                int uppercase = 0;
                int lowercase = 0;
                int numbers = 0;
                int symbols = 0;
                int nonletter = 0;
                for (int i = 0; i < password.length(); i++) {
                    char c = password.charAt(i);
                    if (c >= 'A' && c <= 'Z') {
                        letters++;
                        uppercase++;
                    } else if (c >= 'a' && c <= 'z') {
                        letters++;
                        lowercase++;
                    } else if (c >= '0' && c <= '9') {
                        numbers++;
                        nonletter++;
                    } else {
                        symbols++;
                        nonletter++;
                    }
                }
                int neededLetters = getPasswordMinimumLetters(null, userHandle, /* parent */ false);
                if(letters < neededLetters) {
                    Slog.w(LOG_TAG, "resetPassword: number of letters " + letters
                            + " does not meet required number of letters " + neededLetters);
                    return false;
                }
                int neededNumbers = getPasswordMinimumNumeric(null, userHandle, /* parent */ false);
                if (numbers < neededNumbers) {
                    Slog.w(LOG_TAG, "resetPassword: number of numerical digits " + numbers
                            + " does not meet required number of numerical digits "
                            + neededNumbers);
                    return false;
                }
                int neededLowerCase = getPasswordMinimumLowerCase(
                        null, userHandle, /* parent */ false);
                if (lowercase < neededLowerCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of lowercase letters " + lowercase
                            + " does not meet required number of lowercase letters "
                            + neededLowerCase);
                    return false;
                }
                int neededUpperCase = getPasswordMinimumUpperCase(
                        null, userHandle, /* parent */ false);
                if (uppercase < neededUpperCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of uppercase letters " + uppercase
                            + " does not meet required number of uppercase letters "
                            + neededUpperCase);
                    return false;
                }
                int neededSymbols = getPasswordMinimumSymbols(null, userHandle, /* parent */ false);
                if (symbols < neededSymbols) {
                    Slog.w(LOG_TAG, "resetPassword: number of special symbols " + symbols
                            + " does not meet required number of special symbols " + neededSymbols);
                    return false;
                }
                int neededNonLetter = getPasswordMinimumNonLetter(
                        null, userHandle, /* parent */ false);
                if (nonletter < neededNonLetter) {
                    Slog.w(LOG_TAG, "resetPassword: number of non-letter characters " + nonletter
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
        try {
            if (!TextUtils.isEmpty(password)) {
                mLockPatternUtils.saveLockPassword(password, null, quality, userHandle);
            } else {
                mLockPatternUtils.clearLock(userHandle);
            }
            boolean requireEntry = (flags & DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY) != 0;
            if (requireEntry) {
                mLockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW,
                        UserHandle.USER_ALL);
            }
            synchronized (this) {
                int newOwner = requireEntry ? callingUid : -1;
                if (policy.mPasswordOwner != newOwner) {
                    policy.mPasswordOwner = newOwner;
                    saveSettingsLocked(userHandle);
                }
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }

        return true;
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_FORCE_LOCK, parent);
            if (ap.maximumTimeToUnlock != timeMs) {
                ap.maximumTimeToUnlock = timeMs;
                saveSettingsLocked(userHandle);
                updateMaximumTimeToLockLocked(userHandle);
            }
        }
    }

    void updateMaximumTimeToLockLocked(int userHandle) {
        // Calculate the min timeout for all profiles - including the ones with a separate
        // challenge. Ideally if the timeout only affected the profile challenge we'd lock that
        // challenge only and keep the screen on. However there is no easy way of doing that at the
        // moment so we set the screen off timeout regardless of whether it affects the parent user
        // or the profile challenge only.
        long timeMs = Long.MAX_VALUE;
        int[] profileIds = mUserManager.getProfileIdsWithDisabled(userHandle);
        for (int profileId : profileIds) {
            DevicePolicyData policy = getUserDataUnchecked(profileId);
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = policy.mAdminList.get(i);
                if (admin.maximumTimeToUnlock > 0
                        && timeMs > admin.maximumTimeToUnlock) {
                    timeMs = admin.maximumTimeToUnlock;
                }
                // If userInfo.id is a managed profile, we also need to look at
                // the policies set on the parent.
                if (admin.hasParentActiveAdmin()) {
                    final ActiveAdmin parentAdmin = admin.getParentActiveAdmin();
                    if (parentAdmin.maximumTimeToUnlock > 0
                            && timeMs > parentAdmin.maximumTimeToUnlock) {
                        timeMs = parentAdmin.maximumTimeToUnlock;
                    }
                }
            }
        }

        // We only store the last maximum time to lock on the parent profile. So if calling from a
        // managed profile, retrieve the policy for the parent.
        DevicePolicyData policy = getUserDataUnchecked(getProfileParentId(userHandle));
        if (policy.mLastMaximumTimeToLock == timeMs) {
            return;
        }
        policy.mLastMaximumTimeToLock = timeMs;

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            if (policy.mLastMaximumTimeToLock != Long.MAX_VALUE) {
                // Make sure KEEP_SCREEN_ON is disabled, since that
                // would allow bypassing of the maximum time to lock.
                mInjector.settingsGlobalPutInt(Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
            }

            mInjector.getPowerManagerInternal().setMaximumScreenOffTimeoutFromDeviceAdmin(
                    (int) Math.min(policy.mLastMaximumTimeToLock, Integer.MAX_VALUE));
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
    }

    @Override
    public long getMaximumTimeToLock(ComponentName who, int userHandle, boolean parent) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle, parent);
                return admin != null ? admin.maximumTimeToUnlock : 0;
            }
            // Return the strictest policy across all participating admins.
            List<ActiveAdmin> admins = getActiveAdminsForLockscreenPoliciesLocked(
                    userHandle, parent);
            return getMaximumTimeToLockPolicyFromAdmins(admins);
        }
    }

    @Override
    public long getMaximumTimeToLockForUserAndProfiles(int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            // All admins for this user.
            ArrayList<ActiveAdmin> admins = new ArrayList<ActiveAdmin>();
            for (UserInfo userInfo : mUserManager.getProfiles(userHandle)) {
                DevicePolicyData policy = getUserData(userInfo.id);
                admins.addAll(policy.mAdminList);
                // If it is a managed profile, it may have parent active admins
                if (userInfo.isManagedProfile()) {
                    for (ActiveAdmin admin : policy.mAdminList) {
                        if (admin.hasParentActiveAdmin()) {
                            admins.add(admin.getParentActiveAdmin());
                        }
                    }
                }
            }
            return getMaximumTimeToLockPolicyFromAdmins(admins);
        }
    }

    private long getMaximumTimeToLockPolicyFromAdmins(List<ActiveAdmin> admins) {
        long time = 0;
        final int N = admins.size();
        for (int i = 0; i < N; i++) {
            ActiveAdmin admin = admins.get(i);
            if (time == 0) {
                time = admin.maximumTimeToUnlock;
            } else if (admin.maximumTimeToUnlock != 0
                    && time > admin.maximumTimeToUnlock) {
                time = admin.maximumTimeToUnlock;
            }
        }
        return time;
    }

    @Override
    public void lockNow(boolean parent) {
        if (!mHasFeature) {
            return;
        }
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(
                    null, DeviceAdminInfo.USES_POLICY_FORCE_LOCK, parent);

            int userToLock = mInjector.userHandleGetCallingUserId();

            // Unless this is a managed profile with work challenge enabled, lock all users.
            if (parent || !isSeparateProfileChallengeEnabled(userToLock)) {
                userToLock = UserHandle.USER_ALL;
            }
            final long ident = mInjector.binderClearCallingIdentity();
            try {
                mLockPatternUtils.requireStrongAuth(
                        STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW, userToLock);
                if (userToLock == UserHandle.USER_ALL) {
                    // Power off the display
                    mInjector.powerManagerGoToSleep(SystemClock.uptimeMillis(),
                            PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN, 0);
                    mInjector.getIWindowManager().lockNow(null);
                }
            } catch (RemoteException e) {
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void enforceCanManageCaCerts(ComponentName who) {
        if (who == null) {
            if (!isCallerDelegatedCertInstaller()) {
                mContext.enforceCallingOrSelfPermission(MANAGE_CA_CERTIFICATES, null);
            }
        } else {
            synchronized (this) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            }
        }
    }

    private void enforceCanManageInstalledKeys(ComponentName who) {
        if (who == null) {
            if (!isCallerDelegatedCertInstaller()) {
                throw new SecurityException("who == null, but caller is not cert installer");
            }
        } else {
            synchronized (this) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            }
        }
    }

    private boolean isCallerDelegatedCertInstaller() {
        final int callingUid = mInjector.binderGetCallingUid();
        final int userHandle = UserHandle.getUserId(callingUid);
        synchronized (this) {
            final DevicePolicyData policy = getUserData(userHandle);
            if (policy.mDelegatedCertInstallerPackage == null) {
                return false;
            }

            try {
                int uid = mContext.getPackageManager().getPackageUidAsUser(
                        policy.mDelegatedCertInstallerPackage, userHandle);
                return uid == callingUid;
            } catch (NameNotFoundException e) {
                return false;
            }
        }
    }

    @Override
    public boolean approveCaCert(String alias, int userId, boolean approval) {
        enforceManageUsers();
        synchronized (this) {
            Set<String> certs = getUserData(userId).mAcceptedCaCertificates;
            boolean changed = (approval ? certs.add(alias) : certs.remove(alias));
            if (!changed) {
                return false;
            }
            saveSettingsLocked(userId);
        }
        new MonitoringCertNotificationTask().execute(userId);
        return true;
    }

    @Override
    public boolean isCaCertApproved(String alias, int userId) {
        enforceManageUsers();
        synchronized (this) {
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
                synchronized (this) {
                    getUserData(userInfo.id).mAcceptedCaCertificates.clear();
                    saveSettingsLocked(userInfo.id);
                }

                new MonitoringCertNotificationTask().execute(userInfo.id);
            }
        }
    }

    @Override
    public boolean installCaCert(ComponentName admin, byte[] certBuffer) throws RemoteException {
        enforceCanManageCaCerts(admin);

        byte[] pemCert;
        try {
            X509Certificate cert = parseCert(certBuffer);
            pemCert = Credentials.convertToPem(cert);
        } catch (CertificateException ce) {
            Log.e(LOG_TAG, "Problem converting cert", ce);
            return false;
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Problem reading cert", ioe);
            return false;
        }

        final UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        final long id = mInjector.binderClearCallingIdentity();
        try {
            final KeyChainConnection keyChainConnection = KeyChain.bindAsUser(mContext, userHandle);
            try {
                keyChainConnection.getService().installCaCertificate(pemCert);
                return true;
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "installCaCertsToKeyChain(): ", e);
            } finally {
                keyChainConnection.close();
            }
        } catch (InterruptedException e1) {
            Log.w(LOG_TAG, "installCaCertsToKeyChain(): ", e1);
            Thread.currentThread().interrupt();
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
        return false;
    }

    private static X509Certificate parseCert(byte[] certBuffer) throws CertificateException {
        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) certFactory.generateCertificate(new ByteArrayInputStream(
                certBuffer));
    }

    @Override
    public void uninstallCaCerts(ComponentName admin, String[] aliases) {
        enforceCanManageCaCerts(admin);

        final UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        final long id = mInjector.binderClearCallingIdentity();
        try {
            final KeyChainConnection keyChainConnection = KeyChain.bindAsUser(mContext, userHandle);
            try {
                for (int i = 0 ; i < aliases.length; i++) {
                    keyChainConnection.getService().deleteCaCertificate(aliases[i]);
                }
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "from CaCertUninstaller: ", e);
            } finally {
                keyChainConnection.close();
            }
        } catch (InterruptedException ie) {
            Log.w(LOG_TAG, "CaCertUninstaller: ", ie);
            Thread.currentThread().interrupt();
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public boolean installKeyPair(ComponentName who, byte[] privKey, byte[] cert, byte[] chain,
            String alias, boolean requestAccess) {
        enforceCanManageInstalledKeys(who);

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
    public boolean removeKeyPair(ComponentName who, String alias) {
        enforceCanManageInstalledKeys(who);

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
            ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdminLocked();
            if (deviceOwnerAdmin != null) {
                aliasChooser = deviceOwnerAdmin.info.getComponent();
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
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... unused) {
                try {
                    keyChainAliasResponse.alias(alias);
                } catch (Exception e) {
                    // Catch everything (not just RemoteException): caller could throw a
                    // RuntimeException back across processes.
                    Log.e(LOG_TAG, "error while responding to callback", e);
                }
                return null;
            }
        }.execute();
    }

    @Override
    public void setCertInstallerPackage(ComponentName who, String installerPackage)
            throws SecurityException {
        int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (getTargetSdk(who.getPackageName(), userHandle) >= Build.VERSION_CODES.N) {
                if (installerPackage != null &&
                        !isPackageInstalledForUser(installerPackage, userHandle)) {
                    throw new IllegalArgumentException("Package " + installerPackage
                            + " is not installed on the current user");
                }
            }
            DevicePolicyData policy = getUserData(userHandle);
            policy.mDelegatedCertInstallerPackage = installerPackage;
            saveSettingsLocked(userHandle);
        }
    }

    @Override
    public String getCertInstallerPackage(ComponentName who) throws SecurityException {
        int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            DevicePolicyData policy = getUserData(userHandle);
            return policy.mDelegatedCertInstallerPackage;
        }
    }

    /**
     * @return {@code true} if the package is installed and set as always-on, {@code false} if it is
     * not installed and therefore not available.
     *
     * @throws SecurityException if the caller is not a profile or device owner.
     * @throws UnsupportedException if the package does not support being set as always-on.
     */
    @Override
    public boolean setAlwaysOnVpnPackage(ComponentName admin, String vpnPackage, boolean lockdown)
            throws SecurityException {
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }

        final int userId = mInjector.userHandleGetCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try {
            if (vpnPackage != null && !isPackageInstalledForUser(vpnPackage, userId)) {
                return false;
            }
            ConnectivityManager connectivityManager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (!connectivityManager.setAlwaysOnVpnPackageForUser(userId, vpnPackage, lockdown)) {
                throw new UnsupportedOperationException();
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
        return true;
    }

    @Override
    public String getAlwaysOnVpnPackage(ComponentName admin)
            throws SecurityException {
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }

        final int userId = mInjector.userHandleGetCallingUserId();
        final long token = mInjector.binderClearCallingIdentity();
        try{
            ConnectivityManager connectivityManager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            return connectivityManager.getAlwaysOnVpnPackageForUser(userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    private void wipeDataLocked(boolean wipeExtRequested, String reason) {
        if (wipeExtRequested) {
            StorageManager sm = (StorageManager) mContext.getSystemService(
                    Context.STORAGE_SERVICE);
            sm.wipeAdoptableDisks();
        }
        try {
            RecoverySystem.rebootWipeUserData(mContext, reason);
        } catch (IOException | SecurityException e) {
            Slog.w(LOG_TAG, "Failed requesting data wipe", e);
        }
    }

    @Override
    public void wipeData(int flags) {
        if (!mHasFeature) {
            return;
        }
        final int userHandle = mInjector.userHandleGetCallingUserId();
        enforceFullCrossUsersPermission(userHandle);
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            final ActiveAdmin admin = getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_WIPE_DATA);

            final String source = admin.info.getComponent().flattenToShortString();

            long ident = mInjector.binderClearCallingIdentity();
            try {
                if ((flags & WIPE_RESET_PROTECTION_DATA) != 0) {
                    if (!isDeviceOwner(admin.info.getComponent(), userHandle)) {
                        throw new SecurityException(
                               "Only device owner admins can set WIPE_RESET_PROTECTION_DATA");
                    }
                    PersistentDataBlockManager manager = (PersistentDataBlockManager)
                            mContext.getSystemService(Context.PERSISTENT_DATA_BLOCK_SERVICE);
                    if (manager != null) {
                        manager.wipe();
                    }
                }
                boolean wipeExtRequested = (flags & WIPE_EXTERNAL_STORAGE) != 0;
                wipeDeviceOrUserLocked(wipeExtRequested, userHandle,
                        "DevicePolicyManager.wipeData() from " + source);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    private void wipeDeviceOrUserLocked(boolean wipeExtRequested, final int userHandle, String reason) {
        if (userHandle == UserHandle.USER_SYSTEM) {
            wipeDataLocked(wipeExtRequested, reason);
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        IActivityManager am = mInjector.getIActivityManager();
                        if (am.getCurrentUser().id == userHandle) {
                            am.switchUser(UserHandle.USER_SYSTEM);
                        }

                        boolean isManagedProfile = isManagedProfile(userHandle);
                        if (!mUserManager.removeUser(userHandle)) {
                            Slog.w(LOG_TAG, "Couldn't remove user " + userHandle);
                        } else if (isManagedProfile) {
                            sendWipeProfileNotification();
                        }
                    } catch (RemoteException re) {
                        // Shouldn't happen
                    }
                }
            });
        }
    }

    private void sendWipeProfileNotification() {
        String contentText = mContext.getString(R.string.work_profile_deleted_description_dpm_wipe);
        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(mContext.getString(R.string.work_profile_deleted))
                .setContentText(contentText)
                .setColor(mContext.getColor(R.color.system_notification_accent_color))
                .setStyle(new Notification.BigTextStyle().bigText(contentText))
                .build();
        mInjector.getNotificationManager().notify(PROFILE_WIPED_NOTIFICATION_ID, notification);
    }

    private void clearWipeProfileNotification() {
        mInjector.getNotificationManager().cancel(PROFILE_WIPED_NOTIFICATION_ID);
    }

    @Override
    public void getRemoveWarning(ComponentName comp, final RemoteCallback result, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        synchronized (this) {
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

    @Override
    public void setActivePasswordState(int quality, int length, int letters, int uppercase,
            int lowercase, int numbers, int symbols, int nonletter, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceFullCrossUsersPermission(userHandle);

        // Managed Profile password can only be changed when it has a separate challenge.
        if (!isSeparateProfileChallengeEnabled(userHandle)) {
            enforceNotManagedProfile(userHandle, "set the active password");
        }

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        validateQualityConstant(quality);

        DevicePolicyData policy = getUserData(userHandle);

        long ident = mInjector.binderClearCallingIdentity();
        try {
            synchronized (this) {
                policy.mActivePasswordQuality = quality;
                policy.mActivePasswordLength = length;
                policy.mActivePasswordLetters = letters;
                policy.mActivePasswordLowerCase = lowercase;
                policy.mActivePasswordUpperCase = uppercase;
                policy.mActivePasswordNumeric = numbers;
                policy.mActivePasswordSymbols = symbols;
                policy.mActivePasswordNonLetter = nonletter;
                policy.mFailedPasswordAttempts = 0;
                saveSettingsLocked(userHandle);
                updatePasswordExpirationsLocked(userHandle);
                setExpirationAlarmCheckLocked(mContext, userHandle, /* parent */ false);

                // Send a broadcast to each profile using this password as its primary unlock.
                sendAdminCommandForLockscreenPoliciesLocked(
                        DeviceAdminReceiver.ACTION_PASSWORD_CHANGED,
                        DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, userHandle);
            }
            removeCaApprovalsIfNeeded(userHandle);
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

        final long ident = mInjector.binderClearCallingIdentity();
        try {
            boolean wipeData = false;
            int identifier = 0;
            synchronized (this) {
                DevicePolicyData policy = getUserData(userHandle);
                policy.mFailedPasswordAttempts++;
                saveSettingsLocked(userHandle);
                if (mHasFeature) {
                    ActiveAdmin strictestAdmin = getAdminWithMinimumFailedPasswordsForWipeLocked(
                            userHandle, /* parent */ false);
                    int max = strictestAdmin != null
                            ? strictestAdmin.maximumFailedPasswordsForWipe : 0;
                    if (max > 0 && policy.mFailedPasswordAttempts >= max) {
                        // Wipe the user/profile associated with the policy that was violated. This
                        // is not necessarily calling user: if the policy that fired was from a
                        // managed profile rather than the main user profile, we wipe former only.
                        wipeData = true;
                        identifier = strictestAdmin.getUserHandle().getIdentifier();
                    }

                    sendAdminCommandForLockscreenPoliciesLocked(
                            DeviceAdminReceiver.ACTION_PASSWORD_FAILED,
                            DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, userHandle);
                }
            }
            if (wipeData) {
                // Call without holding lock.
                wipeDeviceOrUserLocked(false, identifier,
                        "reportFailedPasswordAttempt()");
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }

        if (mInjector.securityLogIsLoggingEnabled()) {
            SecurityLog.writeEvent(SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT, /*result*/ 0,
                    /*method strength*/ 1);
        }
    }

    @Override
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        enforceFullCrossUsersPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        synchronized (this) {
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
        synchronized(this) {
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
        synchronized(this) {
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
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        long token = mInjector.binderClearCallingIdentity();
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.setGlobalProxy(proxyInfo);
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
     * Set the storage encryption request for a single admin.  Returns the new total request
     * status (for all admins).
     */
    @Override
    public int setStorageEncryption(ComponentName who, boolean encrypt) {
        if (!mHasFeature) {
            return DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            // Check for permissions
            // Only system user can set storage encryption
            if (userHandle != UserHandle.USER_SYSTEM) {
                Slog.w(LOG_TAG, "Only owner/system user is allowed to set storage encryption. User "
                        + UserHandle.getCallingUserId() + " is not permitted.");
                return 0;
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
        synchronized (this) {
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
        } else if ("com.google.android.apps.enterprise.dmagent".equals(ai.packageName)
                && ai.versionCode == 697) {
            // TODO: STOPSHIP remove this (revert ag/895987) once a new prebuilt is dropped
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
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (ap.disableScreenCapture != disabled) {
                ap.disableScreenCapture = disabled;
                saveSettingsLocked(userHandle);
                updateScreenCaptureDisabledInWindowManager(userHandle, disabled);
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
        synchronized (this) {
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

    private void updateScreenCaptureDisabledInWindowManager(final int userHandle,
            final boolean disabled) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mInjector.getIWindowManager().setScreenCaptureDisabled(userHandle, disabled);
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, "Unable to notify WindowManager.", e);
                }
            }
        });
    }

    /**
     * Set whether auto time is required by the specified admin (must be device owner).
     */
    @Override
    public void setAutoTimeRequired(ComponentName who, boolean required) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
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
     * Returns whether or not auto time is required by the device owner.
     */
    @Override
    public boolean getAutoTimeRequired() {
        if (!mHasFeature) {
            return false;
        }
        synchronized (this) {
            ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
            return (deviceOwner != null) ? deviceOwner.requireAutoTime : false;
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
        synchronized (this) {
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
        synchronized (this) {
            final ActiveAdmin deviceOwner =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            return deviceOwner.forceEphemeralUsers;
        }
    }

    private boolean isDeviceOwnerManagedSingleUserDevice() {
        synchronized (this) {
            if (!mOwners.hasDeviceOwner()) {
                return false;
            }
        }
        final long callingIdentity = mInjector.binderClearCallingIdentity();
        try {
            if (mInjector.userManagerIsSplitSystemUser()) {
                // In split system user mode, only allow the case where the device owner is managing
                // the only non-system user of the device
                return (mUserManager.getUserCount() == 2
                        && mOwners.getDeviceOwnerUserId() != UserHandle.USER_SYSTEM);
            } else  {
                return mUserManager.getUserCount() == 1;
            }
        } finally {
            mInjector.binderRestoreCallingIdentity(callingIdentity);
        }
    }

    private void ensureDeviceOwnerManagingSingleUser(ComponentName who) throws SecurityException {
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        if (!isDeviceOwnerManagedSingleUserDevice()) {
            throw new SecurityException(
                    "There should only be one user, managed by Device Owner");
        }
    }

    @Override
    public boolean requestBugreport(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        ensureDeviceOwnerManagingSingleUser(who);

        if (mRemoteBugreportServiceIsActive.get()
                || (getDeviceOwnerRemoteBugreportUri() != null)) {
            Slog.d(LOG_TAG, "Remote bugreport wasn't started because there's already one running.");
            return false;
        }

        final long callingIdentity = mInjector.binderClearCallingIdentity();
        try {
            ActivityManagerNative.getDefault().requestBugReport(
                    ActivityManager.BUGREPORT_OPTION_REMOTE);

            mRemoteBugreportServiceIsActive.set(true);
            mRemoteBugreportSharingAccepted.set(false);
            registerRemoteBugreportReceivers();
            mInjector.getNotificationManager().notifyAsUser(LOG_TAG, RemoteBugreportUtils.NOTIFICATION_ID,
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

    synchronized void sendDeviceOwnerCommand(String action, Bundle extras) {
        Intent intent = new Intent(action);
        intent.setComponent(mOwners.getDeviceOwnerComponent());
        if (extras != null) {
            intent.putExtras(extras);
        }
        mContext.sendBroadcastAsUser(intent, UserHandle.of(mOwners.getDeviceOwnerUserId()));
    }

    private synchronized String getDeviceOwnerRemoteBugreportUri() {
        return mOwners.getDeviceOwnerRemoteBugreportUri();
    }

    private synchronized void setDeviceOwnerRemoteBugreportUriAndHash(String bugreportUri,
            String bugreportHash) {
        mOwners.setDeviceOwnerRemoteBugreportUriAndHash(bugreportUri, bugreportHash);
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
        synchronized (this) {
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

            synchronized (this) {
                Intent intent = new Intent(DeviceAdminReceiver.ACTION_BUGREPORT_SHARE);
                intent.setComponent(mOwners.getDeviceOwnerComponent());
                intent.setDataAndType(bugreportUri, RemoteBugreportUtils.BUGREPORT_MIMETYPE);
                intent.putExtra(DeviceAdminReceiver.EXTRA_BUGREPORT_HASH, bugreportHash);
                mContext.grantUriPermission(mOwners.getDeviceOwnerComponent().getPackageName(),
                        bugreportUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(
                    who, DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES, parent);
            if (ap.disabledKeyguardFeatures != which) {
                ap.disabledKeyguardFeatures = which;
                saveSettingsLocked(userHandle);
            }
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
            synchronized (this) {
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
    public void setKeepUninstalledPackages(ComponentName who, List<String> packageList) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkNotNull(packageList, "packageList is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            admin.keepUninstalledPackages = packageList;
            saveSettingsLocked(userHandle);
            mInjector.getPackageManagerInternal().setKeepUninstalledPackages(packageList);
        }
    }

    @Override
    public List<String> getKeepUninstalledPackages(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!mHasFeature) {
            return null;
        }
        // TODO In split system user mode, allow apps on user 0 to query the list
        synchronized (this) {
            // Check if this is the device owner who is calling
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
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
        synchronized (this) {
            enforceCanSetDeviceOwnerLocked(userId);
            if (getActiveAdminUncheckedLocked(admin, userId) == null) {
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

            mOwners.setDeviceOwner(admin, ownerName, userId);
            mOwners.writeDeviceOwner();
            updateDeviceOwnerLocked();
            setDeviceOwnerSystemPropertyLocked();
            Intent intent = new Intent(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED);

            ident = mInjector.binderClearCallingIdentity();
            try {
                // TODO Send to system too?
                mContext.sendBroadcastAsUser(intent, new UserHandle(userId));
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
            return true;
        }
    }

    public boolean isDeviceOwner(ComponentName who, int userId) {
        synchronized (this) {
            return mOwners.hasDeviceOwner()
                    && mOwners.getDeviceOwnerUserId() == userId
                    && mOwners.getDeviceOwnerComponent().equals(who);
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            if (!mOwners.hasDeviceOwner()) {
                return null;
            }
            // TODO This totally ignores the name passed to setDeviceOwner (change for b/20679292)
            // Should setDeviceOwner/ProfileOwner still take a name?
            String deviceOwnerPackage = mOwners.getDeviceOwnerPackageName();
            return getApplicationLabel(deviceOwnerPackage, UserHandle.USER_SYSTEM);
        }
    }

    // Returns the active device owner or null if there is no device owner.
    @VisibleForTesting
    ActiveAdmin getDeviceOwnerAdminLocked() {
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
            int uid = mContext.getPackageManager().getPackageUidAsUser(packageName,
                    UserHandle.getUserId(callingUid));
            if (uid != callingUid) {
                throw new SecurityException("Invalid packageName");
            }
        } catch (NameNotFoundException e) {
            throw new SecurityException(e);
        }
        synchronized (this) {
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
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    private void clearDeviceOwnerLocked(ActiveAdmin admin, int userId) {
        if (admin != null) {
            admin.disableCamera = false;
            admin.userRestrictions = null;
            admin.forceEphemeralUsers = false;
            mUserManagerInternal.setForceEphemeralUsers(admin.forceEphemeralUsers);
        }
        clearUserPoliciesLocked(userId);

        mOwners.clearDeviceOwner();
        mOwners.writeDeviceOwner();
        updateDeviceOwnerLocked();
        disableSecurityLoggingIfNotCompliant();
        try {
            // Reactivate backup service.
            mInjector.getIBackupManager().setBackupServiceActive(UserHandle.USER_SYSTEM, true);
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
        synchronized (this) {
            enforceCanSetProfileOwnerLocked(userHandle);

            if (getActiveAdminUncheckedLocked(who, userHandle) == null) {
                throw new IllegalArgumentException("Not active admin: " + who);
            }

            mOwners.setProfileOwner(who, ownerName, userHandle);
            mOwners.writeProfileOwner(userHandle);
            return true;
        }
    }

    @Override
    public void clearProfileOwner(ComponentName who) {
        if (!mHasFeature) {
            return;
        }
        final UserHandle callingUser = mInjector.binderGetCallingUserHandle();
        final int userId = callingUser.getIdentifier();
        enforceNotManagedProfile(userId, "clear profile owner");
        enforceUserUnlocked(userId);
        // Check if this is the profile owner who is calling
        final ActiveAdmin admin =
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        synchronized (this) {
            final long ident = mInjector.binderClearCallingIdentity();
            try {
                clearProfileOwnerLocked(admin, userId);
                removeActiveAdminLocked(who, userId);
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    public void clearProfileOwnerLocked(ActiveAdmin admin, int userId) {
        if (admin != null) {
            admin.disableCamera = false;
            admin.userRestrictions = null;
        }
        clearUserPoliciesLocked(userId);
        mOwners.removeProfileOwner(userId);
        mOwners.writeProfileOwner(userId);
    }

    @Override
    public void setDeviceOwnerLockScreenInfo(ComponentName who, CharSequence info) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        if (!mHasFeature) {
            return;
        }

        synchronized (this) {
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
        // Reset some of the user-specific policies
        DevicePolicyData policy = getUserData(userId);
        policy.mPermissionPolicy = DevicePolicyManager.PERMISSION_POLICY_PROMPT;
        policy.mDelegatedCertInstallerPackage = null;
        policy.mApplicationRestrictionsManagingPackage = null;
        policy.mStatusBarDisabled = false;
        policy.mUserProvisioningState = DevicePolicyManager.STATE_USER_UNMANAGED;
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

    private boolean hasUserSetupCompleted(int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        return getUserData(userHandle).mUserSetupComplete;
    }

    @Override
    public int getUserProvisioningState() {
        if (!mHasFeature) {
            return DevicePolicyManager.STATE_USER_UNMANAGED;
        }
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

        synchronized (this) {
            boolean transitionCheckNeeded = true;

            // Calling identity/permission checks.
            final int callingUid = mInjector.binderGetCallingUid();
            if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
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
        synchronized (this) {
            // Check if this is the profile owner who is calling
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            final int userId = UserHandle.getCallingUserId();
            enforceManagedProfile(userId, "enable the profile");

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

        synchronized (this) {
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
                PackageManager pm = userContext.getPackageManager();
                result = pm.getApplicationLabel(appInfo);
            }
            return result != null ? result.toString() : null;
        } finally {
            mInjector.binderRestoreCallingIdentity(token);
        }
    }

    /**
     * The profile owner can only be set by adb or an app with the MANAGE_PROFILE_AND_DEVICE_OWNERS
     * permission.
     * The profile owner can only be set before the user setup phase has completed,
     * except for:
     * - SYSTEM_UID
     * - adb if there are not accounts.
     */
    private void enforceCanSetProfileOwnerLocked(int userHandle) {
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
        int callingUid = mInjector.binderGetCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            if (hasUserSetupCompleted(userHandle) &&
                    AccountManager.get(mContext).getAccountsAsUser(userHandle).length > 0) {
                throw new IllegalStateException("Not allowed to set the profile owner because "
                        + "there are already some accounts on the profile");
            }
            return;
        }
        enforceCanManageProfileAndDeviceOwners();
        if (hasUserSetupCompleted(userHandle) && !isCallerWithSystemUid()) {
            throw new IllegalStateException("Cannot set the profile owner on a user which is "
                    + "already set-up");
        }
    }

    /**
     * The Device owner can only be set by adb or an app with the MANAGE_PROFILE_AND_DEVICE_OWNERS
     * permission.
     */
    private void enforceCanSetDeviceOwnerLocked(int userId) {
        int callingUid = mInjector.binderGetCallingUid();
        boolean isAdb = callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
        if (!isAdb) {
            enforceCanManageProfileAndDeviceOwners();
        }

        final int code = checkSetDeviceOwnerPreCondition(userId, isAdb);
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
            default:
                throw new IllegalStateException("Unknown @DeviceOwnerPreConditionCode " + code);
        }
    }

    private void enforceUserUnlocked(int userId) {
        // Since we're doing this operation on behalf of an app, we only
        // want to use the actual "unlocked" state.
        Preconditions.checkState(mUserManager.isUserUnlocked(userId),
                "User must be running and unlocked");
    }

    private void enforceManageUsers() {
        final int callingUid = mInjector.binderGetCallingUid();
        if (!(isCallerWithSystemUid() || callingUid == Process.ROOT_UID)) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        }
    }

    private void enforceFullCrossUsersPermission(int userHandle) {
        enforceSystemUserOrPermission(userHandle,
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL);
    }

    private void enforceCrossUsersPermission(int userHandle) {
        enforceSystemUserOrPermission(userHandle,
                android.Manifest.permission.INTERACT_ACROSS_USERS);
    }

    private void enforceSystemUserOrPermission(int userHandle, String permission) {
        if (userHandle < 0) {
            throw new IllegalArgumentException("Invalid userId " + userHandle);
        }
        final int callingUid = mInjector.binderGetCallingUid();
        if (userHandle == UserHandle.getUserId(callingUid)) {
            return;
        }
        if (!(isCallerWithSystemUid() || callingUid == Process.ROOT_UID)) {
            mContext.enforceCallingOrSelfPermission(permission,
                    "Must be system or have " + permission + " permission");
        }
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

    private int getProfileParentId(int userHandle) {
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
        return getUserInfo(userHandle).isManagedProfile();
    }

    private void enableIfNecessary(String packageName, int userId) {
        try {
            ApplicationInfo ai = mIPackageManager.getApplicationInfo(packageName,
                    PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                    userId);
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
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            pw.println("Permission Denial: can't dump DevicePolicyManagerService from from pid="
                    + mInjector.binderGetCallingPid()
                    + ", uid=" + mInjector.binderGetCallingUid());
            return;
        }

        synchronized (this) {
            pw.println("Current Device Policy Manager state:");
            mOwners.dump("  ", pw);
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
            pw.println("Encryption Status: " + getEncryptionStatusName(getEncryptionStatus()));
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
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                mIPackageManager.addPersistentPreferredActivity(filter, activity, userHandle);
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
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                mIPackageManager.clearPackagePersistentPreferredActivities(packageName, userHandle);
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public boolean setApplicationRestrictionsManagingPackage(ComponentName admin,
            String packageName) {
        Preconditions.checkNotNull(admin, "ComponentName is null");

        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (packageName != null && !isPackageInstalledForUser(packageName, userHandle)) {
                return false;
            }
            DevicePolicyData policy = getUserData(userHandle);
            policy.mApplicationRestrictionsManagingPackage = packageName;
            saveSettingsLocked(userHandle);
            return true;
        }
    }

    @Override
    public String getApplicationRestrictionsManagingPackage(ComponentName admin) {
        Preconditions.checkNotNull(admin, "ComponentName is null");

        final int userHandle = mInjector.userHandleGetCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            DevicePolicyData policy = getUserData(userHandle);
            return policy.mApplicationRestrictionsManagingPackage;
        }
    }

    @Override
    public boolean isCallerApplicationRestrictionsManagingPackage() {
        final int callingUid = mInjector.binderGetCallingUid();
        final int userHandle = UserHandle.getUserId(callingUid);
        synchronized (this) {
            final DevicePolicyData policy = getUserData(userHandle);
            if (policy.mApplicationRestrictionsManagingPackage == null) {
                return false;
            }

            try {
                int uid = mContext.getPackageManager().getPackageUidAsUser(
                        policy.mApplicationRestrictionsManagingPackage, userHandle);
                return uid == callingUid;
            } catch (NameNotFoundException e) {
                return false;
            }
        }
    }

    private void enforceCanManageApplicationRestrictions(ComponentName who) {
        if (who != null) {
            synchronized (this) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            }
        } else if (!isCallerApplicationRestrictionsManagingPackage()) {
            throw new SecurityException(
                    "No admin component given, and caller cannot manage application restrictions "
                    + "for other apps.");
        }
    }

    @Override
    public void setApplicationRestrictions(ComponentName who, String packageName, Bundle settings) {
        enforceCanManageApplicationRestrictions(who);

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
        synchronized (this) {
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

        synchronized (this) {
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
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            int userHandle = UserHandle.getCallingUserId();
            DevicePolicyData userData = getUserData(userHandle);
            userData.mRestrictionsProvider = permissionProvider;
            saveSettingsLocked(userHandle);
        }
    }

    @Override
    public ComponentName getRestrictionsProvider(int userHandle) {
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
                            enabledPackage, PackageManager.GET_UNINSTALLED_PACKAGES, userIdToCheck);
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

        synchronized (this) {
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

        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin == null) {
                return false;
            }
            if (admin.permittedAccessiblityServices == null) {
                return true;
            }
            return checkPackagesInPermittedListOrSystem(Arrays.asList(packageName),
                    admin.permittedAccessiblityServices, userHandle);
        }
    }

    private boolean checkCallerIsCurrentUserOrProfile() {
        int callingUserId = UserHandle.getCallingUserId();
        long token = mInjector.binderClearCallingIdentity();
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
                        mInjector.binderGetCallingUserHandle().getIdentifier())) {
                    Slog.e(LOG_TAG, "Cannot set permitted input methods, "
                            + "because it contains already enabled input method.");
                    return false;
                }
            }
        }

        synchronized (this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            admin.permittedInputMethods = packageList;
            saveSettingsLocked(UserHandle.getCallingUserId());
        }
        return true;
    }

    @Override
    public List getPermittedInputMethods(ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        synchronized (this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.permittedInputMethods;
        }
    }

    @Override
    public List getPermittedInputMethodsForCurrentUser() {
        UserInfo currentUser;
        try {
            currentUser = mInjector.getIActivityManager().getCurrentUser();
        } catch (RemoteException e) {
            Slog.e(LOG_TAG, "Failed to make remote calls to get current user", e);
            // Activity managed is dead, just allow all IMEs
            return null;
        }

        int userId = currentUser.id;
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            if (admin == null) {
                return false;
            }
            if (admin.permittedInputMethods == null) {
                return true;
            }
            return checkPackagesInPermittedListOrSystem(Arrays.asList(packageName),
                    admin.permittedInputMethods, userHandle);
        }
    }

    private void sendAdminEnabledBroadcastLocked(int userHandle) {
        DevicePolicyData policyData = getUserData(userHandle);
        if (policyData.mAdminBroadcastPending) {
            // Send the initialization data to profile owner and delete the data
            ActiveAdmin admin = getProfileOwnerAdminLocked(userHandle);
            if (admin != null) {
                PersistableBundle initBundle = policyData.mInitBundle;
                sendAdminCommandLocked(admin, DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED,
                        initBundle == null ? null : new Bundle(initBundle), null);
            }
            policyData.mInitBundle = null;
            policyData.mAdminBroadcastPending = false;
            saveSettingsLocked(userHandle);
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
        if (!mInjector.userManagerIsSplitSystemUser()
                && (flags & DevicePolicyManager.MAKE_USER_EPHEMERAL) != 0) {
            throw new IllegalArgumentException(
                    "Ephemeral users are only supported on systems with a split system user.");
        }
        // Create user.
        UserHandle user = null;
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            final long id = mInjector.binderClearCallingIdentity();
            try {
                int userInfoFlags = 0;
                if ((flags & DevicePolicyManager.MAKE_USER_EPHEMERAL) != 0) {
                    userInfoFlags |= UserInfo.FLAG_EPHEMERAL;
                }
                UserInfo userInfo = mUserManagerInternal.createUserEvenWhenDisallowed(name,
                        userInfoFlags);
                if (userInfo != null) {
                    user = userInfo.getUserHandle();
                }
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
        if (user == null) {
            return null;
        }
        // Set admin.
        final long id = mInjector.binderClearCallingIdentity();
        try {
            final String adminPkg = admin.getPackageName();

            final int userHandle = user.getIdentifier();
            try {
                // Install the profile owner if not present.
                if (!mIPackageManager.isPackageAvailable(adminPkg, userHandle)) {
                    mIPackageManager.installExistingPackageAsUser(adminPkg, userHandle);
                }
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Failed to make remote calls for createAndManageUser, "
                        + "removing created user", e);
                mUserManager.removeUser(user.getIdentifier());
                return null;
            }

            setActiveAdmin(profileOwner, true, userHandle);
            // User is not started yet, the broadcast by setActiveAdmin will not be received.
            // So we store adminExtras for broadcasting when the user starts for first time.
            synchronized(this) {
                DevicePolicyData policyData = getUserData(userHandle);
                policyData.mInitBundle = adminExtras;
                policyData.mAdminBroadcastPending = true;
                saveSettingsLocked(userHandle);
            }
            final String ownerName = getProfileOwnerName(Process.myUserHandle().getIdentifier());
            setProfileOwner(profileOwner, ownerName, userHandle);

            if ((flags & DevicePolicyManager.SKIP_SETUP_WIZARD) != 0) {
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 1, userHandle);
            }

            return user;
        } finally {
            mInjector.binderRestoreCallingIdentity(id);
        }
    }

    @Override
    public boolean removeUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mUserManager.removeUser(userHandle.getIdentifier());
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public boolean switchUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
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
    public Bundle getApplicationRestrictions(ComponentName who, String packageName) {
        enforceCanManageApplicationRestrictions(who);

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
    public String[] setPackagesSuspended(ComponentName who, String[] packageNames,
            boolean suspended) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = mInjector.binderClearCallingIdentity();
            try {
                return mIPackageManager.setPackagesSuspendedAsUser(
                        packageNames, suspended, callingUserId);
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
    public boolean isPackageSuspended(ComponentName who, String packageName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

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
        synchronized (this) {
            ActiveAdmin activeAdmin =
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
            activeAdmin.ensureUserRestrictions().putBoolean(key, enabledFromThisOwner);
            saveSettingsLocked(userHandle);

            pushUserRestrictions(userHandle);

            sendChangedNotification(userHandle);
        }
    }

    private void pushUserRestrictions(int userId) {
        synchronized (this) {
            final Bundle global;
            final Bundle local = new Bundle();
            if (mOwners.isDeviceOwnerUserId(userId)) {
                global = new Bundle();

                final ActiveAdmin deviceOwner = getDeviceOwnerAdminLocked();
                if (deviceOwner == null) {
                    return; // Shouldn't happen.
                }

                UserRestrictionsUtils.sortToGlobalAndLocal(deviceOwner.userRestrictions,
                        global, local);
                // DO can disable camera globally.
                if (deviceOwner.disableCamera) {
                    global.putBoolean(UserManager.DISALLOW_CAMERA, true);
                }
            } else {
                global = null;

                ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userId);
                if (profileOwner != null) {
                    UserRestrictionsUtils.merge(local, profileOwner.userRestrictions);
                }
            }
            // Also merge in *local* camera restriction.
            if (getCameraDisabled(/* who= */ null,
                    userId, /* mergeDeviceOwnerRestriction= */ false)) {
                local.putBoolean(UserManager.DISALLOW_CAMERA, true);
            }
            mUserManagerInternal.setDevicePolicyUserRestrictions(userId, local, global);
        }
    }

    @Override
    public Bundle getUserRestrictions(ComponentName who) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            final ActiveAdmin activeAdmin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return activeAdmin.userRestrictions;
        }
    }

    @Override
    public boolean setApplicationHidden(ComponentName who, String packageName,
            boolean hidden) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

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
    public boolean isApplicationHidden(ComponentName who, String packageName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

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
    public void enableSystemApp(ComponentName who, String packageName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            int userId = UserHandle.getCallingUserId();
            long id = mInjector.binderClearCallingIdentity();

            try {
                if (VERBOSE_LOG) {
                    Slog.v(LOG_TAG, "installing " + packageName + " for "
                            + userId);
                }

                int parentUserId = getProfileParentId(userId);
                if (!isSystemApp(mIPackageManager, packageName, parentUserId)) {
                    throw new IllegalArgumentException("Only system apps can be enabled this way.");
                }

                // Install the app.
                mIPackageManager.installExistingPackageAsUser(packageName, userId);

            } catch (RemoteException re) {
                // shouldn't happen
                Slog.wtf(LOG_TAG, "Failed to install " + packageName, re);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public int enableSystemAppWithIntent(ComponentName who, Intent intent) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

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
                                mIPackageManager.installExistingPackageAsUser(packageName, userId);
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
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, GET_UNINSTALLED_PACKAGES,
                userId);
        if (appInfo == null) {
            throw new IllegalArgumentException("The application " + packageName +
                    " is not present on this device");
        }
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    @Override
    public void setAccountManagementDisabled(ComponentName who, String accountType,
            boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
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
        synchronized (this) {
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
    public void setUninstallBlocked(ComponentName who, String packageName,
            boolean uninstallBlocked) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

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

        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.disableCallerId;
        }
    }

    @Override
    public boolean getCrossProfileCallerIdDisabledForUser(int userId) {
        enforceCrossUsersPermission(userId);
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.disableContactsSearch;
        }
    }

    @Override
    public boolean getCrossProfileContactsSearchDisabledForUser(int userId) {
        enforceCrossUsersPermission(userId);
        synchronized (this) {
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
            synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
            ActiveAdmin admin = getProfileOwnerAdminLocked(userId);
            return (admin != null) ? admin.disableBluetoothContactSharing : false;
        }
    }

    /**
     * Sets which packages may enter lock task mode.
     *
     * <p>This function can only be called by the device owner or alternatively by the profile owner
     * in case the user is affiliated.
     *
     * @param packages The list of packages allowed to enter lock task mode.
     */
    @Override
    public void setLockTaskPackages(ComponentName who, String[] packages)
            throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            ActiveAdmin deviceOwner = getActiveAdminWithPolicyForUidLocked(
                who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER, mInjector.binderGetCallingUid());
            ActiveAdmin profileOwner = getActiveAdminWithPolicyForUidLocked(
                who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER, mInjector.binderGetCallingUid());
            if (deviceOwner != null || (profileOwner != null && isAffiliatedUser())) {
                int userHandle = mInjector.userHandleGetCallingUserId();
                setLockTaskPackagesLocked(userHandle, new ArrayList<>(Arrays.asList(packages)));
            } else {
                throw new SecurityException("Admin " + who +
                    " is neither the device owner or affiliated user's profile owner.");
            }
        }
    }

    private void setLockTaskPackagesLocked(int userHandle, List<String> packages) {
        DevicePolicyData policy = getUserData(userHandle);
        policy.mLockTaskPackages = packages;

        // Store the settings persistently.
        saveSettingsLocked(userHandle);
        updateLockTaskPackagesLocked(packages, userHandle);
    }

    /**
     * This function returns the list of components allowed to start the task lock mode.
     */
    @Override
    public String[] getLockTaskPackages(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            int userHandle = mInjector.binderGetCallingUserHandle().getIdentifier();
            final List<String> packages = getLockTaskPackagesLocked(userHandle);
            return packages.toArray(new String[packages.size()]);
        }
    }

    private List<String> getLockTaskPackagesLocked(int userHandle) {
        final DevicePolicyData policy = getUserData(userHandle);
        return policy.mLockTaskPackages;
    }

    /**
     * This function lets the caller know whether the given package is allowed to start the
     * lock task mode.
     * @param pkg The package to check
     */
    @Override
    public boolean isLockTaskPermitted(String pkg) {
        // Get current user's devicepolicy
        int uid = mInjector.binderGetCallingUid();
        int userHandle = UserHandle.getUserId(uid);
        DevicePolicyData policy = getUserData(userHandle);
        synchronized (this) {
            for (int i = 0; i < policy.mLockTaskPackages.size(); i++) {
                String lockTaskPackage = policy.mLockTaskPackages.get(i);

                // If the given package equals one of the packages stored our list,
                // we allow this package to start lock task mode.
                if (lockTaskPackage.equals(pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void notifyLockTaskModeChanged(boolean isEnabled, String pkg, int userHandle) {
        if (!isCallerWithSystemUid()) {
            throw new SecurityException("notifyLockTaskModeChanged can only be called by system");
        }
        synchronized (this) {
            final DevicePolicyData policy = getUserData(userHandle);
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

        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            // Some settings are no supported any more. However we do not want to throw a
            // SecurityException to avoid breaking apps.
            if (GLOBAL_SETTINGS_DEPRECATED.contains(setting)) {
                Log.i(LOG_TAG, "Global setting no longer supported: " + setting);
                return;
            }

            if (!GLOBAL_SETTINGS_WHITELIST.contains(setting)) {
                throw new SecurityException(String.format(
                        "Permission denial: device owners cannot update %1$s", setting));
            }

            if (Settings.Global.STAY_ON_WHILE_PLUGGED_IN.equals(setting)) {
                // ignore if it contradicts an existing policy
                long timeMs = getMaximumTimeToLock(
                        who, mInjector.userHandleGetCallingUserId(), /* parent */ false);
                if (timeMs > 0 && timeMs < Integer.MAX_VALUE) {
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
    public void setSecureSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = mInjector.userHandleGetCallingUserId();

        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            if (isDeviceOwner(who, callingUserId)) {
                if (!SECURE_SETTINGS_DEVICEOWNER_WHITELIST.contains(setting)) {
                    throw new SecurityException(String.format(
                            "Permission denial: Device owners cannot update %1$s", setting));
                }
            } else if (!SECURE_SETTINGS_WHITELIST.contains(setting)) {
                throw new SecurityException(String.format(
                        "Permission denial: Profile owners cannot update %1$s", setting));
            }

            long id = mInjector.binderClearCallingIdentity();
            try {
                mInjector.settingsSecurePutStringForUser(setting, value, callingUserId);
            } finally {
                mInjector.binderRestoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setMasterVolumeMuted(ComponentName who, boolean on) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            int userId = UserHandle.getCallingUserId();
            long identity = mInjector.binderClearCallingIdentity();
            try {
                IAudioService iAudioService = IAudioService.Stub.asInterface(
                        ServiceManager.getService(Context.AUDIO_SERVICE));
                iAudioService.setMasterMute(on, 0, mContext.getPackageName(), userId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to setMasterMute", re);
            } finally {
                mInjector.binderRestoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public boolean isMasterVolumeMuted(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            return audioManager.isMasterMute();
        }
    }

    @Override
    public void setUserIcon(ComponentName who, Bitmap icon) {
        synchronized (this) {
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
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        final int userId = UserHandle.getCallingUserId();

        long ident = mInjector.binderClearCallingIdentity();
        try {
            // disallow disabling the keyguard if a password is currently set
            if (disabled && mLockPatternUtils.isSecure(userId)) {
                return false;
            }
            mLockPatternUtils.setLockScreenDisabled(disabled, userId);
        } finally {
            mInjector.binderRestoreCallingIdentity(ident);
        }
        return true;
    }

    @Override
    public boolean setStatusBarDisabled(ComponentName who, boolean disabled) {
        int userId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            DevicePolicyData policy = getUserData(userId);
            if (policy.mStatusBarDisabled != disabled) {
                if (!setStatusBarDisabledInternal(disabled, userId)) {
                    return false;
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
     * We need to update the internal state of whether a user has completed setup once. After
     * that, we ignore any changes that reset the Settings.Secure.USER_SETUP_COMPLETE changes
     * as we don't trust any apps that might try to reset it.
     * <p>
     * Unfortunately, we don't know which user's setup state was changed, so we write all of
     * them.
     */
    void updateUserSetupComplete() {
        List<UserInfo> users = mUserManager.getUsers(true);
        final int N = users.size();
        for (int i = 0; i < N; i++) {
            int userHandle = users.get(i).id;
            if (mInjector.settingsSecureGetIntForUser(Settings.Secure.USER_SETUP_COMPLETE, 0,
                    userHandle) != 0) {
                DevicePolicyData policy = getUserData(userHandle);
                if (!policy.mUserSetupComplete) {
                    policy.mUserSetupComplete = true;
                    synchronized (this) {
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

        public SetupContentObserver(Handler handler) {
            super(handler);
        }

        void register() {
            mInjector.registerContentObserver(mUserSetupComplete, false, this, UserHandle.USER_ALL);
            mInjector.registerContentObserver(mDeviceProvisioned, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mUserSetupComplete.equals(uri)) {
                updateUserSetupComplete();
            } else if (mDeviceProvisioned.equals(uri)) {
                synchronized (DevicePolicyManagerService.this) {
                    // Set PROPERTY_DEVICE_OWNER_PRESENT, for the SUW case where setting the property
                    // is delayed until device is marked as provisioned.
                    setDeviceOwnerSystemPropertyLocked();
                }
            }
        }
    }

    @VisibleForTesting
    final class LocalService extends DevicePolicyManagerInternal {
        private List<OnCrossProfileWidgetProvidersChangeListener> mWidgetProviderListeners;

        @Override
        public List<String> getCrossProfileWidgetProviders(int profileId) {
            synchronized (DevicePolicyManagerService.this) {
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
            synchronized (DevicePolicyManagerService.this) {
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
            synchronized(DevicePolicyManagerService.this) {
                return getActiveAdminWithPolicyForUidLocked(null, reqPolicy, uid) != null;
            }
        }

        private void notifyCrossProfileProvidersChanged(int userId, List<String> packages) {
            final List<OnCrossProfileWidgetProvidersChangeListener> listeners;
            synchronized (DevicePolicyManagerService.this) {
                listeners = new ArrayList<>(mWidgetProviderListeners);
            }
            final int listenerCount = listeners.size();
            for (int i = 0; i < listenerCount; i++) {
                OnCrossProfileWidgetProvidersChangeListener listener = listeners.get(i);
                listener.onCrossProfileWidgetProvidersChanged(userId, packages);
            }
        }

        @Override
        public Intent createPackageSuspendedDialogIntent(String packageName, int userId) {
            Intent intent = new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
            intent.putExtra(Intent.EXTRA_USER_ID, userId);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // This method is called from AM with its lock held, so don't take the DPMS lock.
            // b/29242568

            ComponentName profileOwner = mOwners.getProfileOwnerComponent(userId);
            if (profileOwner != null) {
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, profileOwner);
                return intent;
            }

            final Pair<Integer, ComponentName> deviceOwner =
                    mOwners.getDeviceOwnerUserIdAndComponent();
            if (deviceOwner != null && deviceOwner.first == userId) {
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceOwner.second);
                return intent;
            }

            // We're not specifying the device admin because there isn't one.
            return intent;
        }
    }

    /**
     * Returns true if specified admin is allowed to limit passwords and has a
     * {@code passwordQuality} of at least {@code minPasswordQuality}
     */
    private static boolean isLimitPasswordAllowed(ActiveAdmin admin, int minPasswordQuality) {
        if (admin.passwordQuality < minPasswordQuality) {
            return false;
        }
        return admin.info.usesPolicy(DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
    }

    @Override
    public void setSystemUpdatePolicy(ComponentName who, SystemUpdatePolicy policy) {
        if (policy != null && !policy.isValid()) {
            throw new IllegalArgumentException("Invalid system update policy.");
        }
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            if (policy == null) {
                mOwners.clearSystemUpdatePolicy();
            } else {
                mOwners.setSystemUpdatePolicy(policy);
            }
            mOwners.writeDeviceOwner();
        }
        mContext.sendBroadcastAsUser(
                new Intent(DevicePolicyManager.ACTION_SYSTEM_UPDATE_POLICY_CHANGED),
                UserHandle.SYSTEM);
    }

    @Override
    public SystemUpdatePolicy getSystemUpdatePolicy() {
        synchronized (this) {
            SystemUpdatePolicy policy =  mOwners.getSystemUpdatePolicy();
            if (policy != null && !policy.isValid()) {
                Slog.w(LOG_TAG, "Stored system update policy is invalid, return null instead.");
                return null;
            }
            return policy;
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
        synchronized (this) {
            if (!mOwners.hasDeviceOwner()) {
                return false;
            }
            if (UserHandle.getUserId(callerUid) != mOwners.getDeviceOwnerUserId()) {
                return false;
            }
            final String deviceOwnerPackageName = mOwners.getDeviceOwnerComponent()
                    .getPackageName();
            final String[] pkgs = mContext.getPackageManager().getPackagesForUid(callerUid);

            for (String pkg : pkgs) {
                if (deviceOwnerPackageName.equals(pkg)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void notifyPendingSystemUpdate(long updateReceivedTime) {
        mContext.enforceCallingOrSelfPermission(permission.NOTIFY_PENDING_SYSTEM_UPDATE,
                "Only the system update service can broadcast update information");

        if (UserHandle.getCallingUserId() != UserHandle.USER_SYSTEM) {
            Slog.w(LOG_TAG, "Only the system update service in the system user " +
                    "can broadcast update information.");
            return;
        }
        Intent intent = new Intent(DeviceAdminReceiver.ACTION_NOTIFY_PENDING_SYSTEM_UPDATE);
        intent.putExtra(DeviceAdminReceiver.EXTRA_SYSTEM_UPDATE_RECEIVED_TIME,
                updateReceivedTime);

        synchronized (this) {
            final String deviceOwnerPackage =
                    mOwners.hasDeviceOwner() ? mOwners.getDeviceOwnerComponent().getPackageName()
                            : null;
            if (deviceOwnerPackage == null) {
                return;
            }
            final UserHandle deviceOwnerUser = new UserHandle(mOwners.getDeviceOwnerUserId());

            ActivityInfo[] receivers = null;
            try {
                receivers  = mContext.getPackageManager().getPackageInfo(
                        deviceOwnerPackage, PackageManager.GET_RECEIVERS).receivers;
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Cannot find device owner package", e);
            }
            if (receivers != null) {
                long ident = mInjector.binderClearCallingIdentity();
                try {
                    for (int i = 0; i < receivers.length; i++) {
                        if (permission.BIND_DEVICE_ADMIN.equals(receivers[i].permission)) {
                            intent.setComponent(new ComponentName(deviceOwnerPackage,
                                    receivers[i].name));
                            mContext.sendBroadcastAsUser(intent, deviceOwnerUser);
                        }
                    }
                } finally {
                    mInjector.binderRestoreCallingIdentity(ident);
                }
            }
        }
    }

    @Override
    public void setPermissionPolicy(ComponentName admin, int policy) throws RemoteException {
        int userId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
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
        synchronized (this) {
            DevicePolicyData userPolicy = getUserData(userId);
            return userPolicy.mPermissionPolicy;
        }
    }

    @Override
    public boolean setPermissionGrantState(ComponentName admin, String packageName,
            String permission, int grantState) throws RemoteException {
        UserHandle user = mInjector.binderGetCallingUserHandle();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            long ident = mInjector.binderClearCallingIdentity();
            try {
                if (getTargetSdk(packageName, user.getIdentifier())
                        < android.os.Build.VERSION_CODES.M) {
                    return false;
                }
                final PackageManager packageManager = mContext.getPackageManager();
                switch (grantState) {
                    case DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED: {
                        packageManager.grantRuntimePermission(packageName, permission, user);
                        packageManager.updatePermissionFlags(permission, packageName,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                                PackageManager.FLAG_PERMISSION_POLICY_FIXED, user);
                    } break;

                    case DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED: {
                        packageManager.revokeRuntimePermission(packageName,
                                permission, user);
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
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public int getPermissionGrantState(ComponentName admin, String packageName,
            String permission) throws RemoteException {
        PackageManager packageManager = mContext.getPackageManager();

        UserHandle user = mInjector.binderGetCallingUserHandle();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
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

    @Override
    public boolean isProvisioningAllowed(String action) {
        if (!mHasFeature) {
            return false;
        }

        final int callingUserId = mInjector.userHandleGetCallingUserId();
        if (DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE.equals(action)) {
            if (!hasFeatureManagedUsers()) {
                return false;
            }
            synchronized (this) {
                if (mOwners.hasDeviceOwner()) {
                    if (!mInjector.userManagerIsSplitSystemUser()) {
                        // Only split-system-user systems support managed-profiles in combination with
                        // device-owner.
                        return false;
                    }
                    if (mOwners.getDeviceOwnerUserId() != UserHandle.USER_SYSTEM) {
                        // Only system device-owner supports managed-profiles. Non-system device-owner
                        // doesn't.
                        return false;
                    }
                    if (callingUserId == UserHandle.USER_SYSTEM) {
                        // Managed-profiles cannot be setup on the system user, only regular users.
                        return false;
                    }
                }
            }
            if (getProfileOwner(callingUserId) != null) {
                // Managed user cannot have a managed profile.
                return false;
            }
            final long ident = mInjector.binderClearCallingIdentity();
            try {
                if (!mUserManager.canAddMoreManagedProfiles(callingUserId, true)) {
                    return false;
                }
            } finally {
                mInjector.binderRestoreCallingIdentity(ident);
            }
            return true;
        } else if (DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE.equals(action)) {
            return isDeviceOwnerProvisioningAllowed(callingUserId);
        } else if (DevicePolicyManager.ACTION_PROVISION_MANAGED_USER.equals(action)) {
            if (!hasFeatureManagedUsers()) {
                return false;
            }
            if (!mInjector.userManagerIsSplitSystemUser()) {
                // ACTION_PROVISION_MANAGED_USER only supported on split-user systems.
                return false;
            }
            if (callingUserId == UserHandle.USER_SYSTEM) {
                // System user cannot be a managed user.
                return false;
            }
            if (hasUserSetupCompleted(callingUserId)) {
                return false;
            }
            return true;
        } else if (DevicePolicyManager.ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE.equals(action)) {
            if (!mInjector.userManagerIsSplitSystemUser()) {
                // ACTION_PROVISION_MANAGED_SHAREABLE_DEVICE only supported on split-user systems.
                return false;
            }
            return isDeviceOwnerProvisioningAllowed(callingUserId);
        }
        throw new IllegalArgumentException("Unknown provisioning action " + action);
    }

    /*
     * The device owner can only be set before the setup phase of the primary user has completed,
     * except for adb command if no accounts or additional users are present on the device.
     */
    private synchronized @DeviceOwnerPreConditionCode int checkSetDeviceOwnerPreCondition(
            int deviceOwnerUserId, boolean isAdb) {
        if (mOwners.hasDeviceOwner()) {
            return CODE_HAS_DEVICE_OWNER;
        }
        if (mOwners.hasProfileOwner(deviceOwnerUserId)) {
            return CODE_USER_HAS_PROFILE_OWNER;
        }
        if (!mUserManager.isUserRunning(new UserHandle(deviceOwnerUserId))) {
            return CODE_USER_NOT_RUNNING;
        }
        if (isAdb) {
            // if shell command runs after user setup completed check device status. Otherwise, OK.
            if (hasUserSetupCompleted(UserHandle.USER_SYSTEM)) {
                if (!mInjector.userManagerIsSplitSystemUser()) {
                    if (mUserManager.getUserCount() > 1) {
                        return CODE_NONSYSTEM_USER_EXISTS;
                    }
                    if (AccountManager.get(mContext).getAccounts().length > 0) {
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

    private boolean isDeviceOwnerProvisioningAllowed(int deviceOwnerUserId) {
        return CODE_OK == checkSetDeviceOwnerPreCondition(deviceOwnerUserId, /* isAdb */ false);
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
        synchronized (this) {
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
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        }
        final int callingUserId = mInjector.userHandleGetCallingUserId();
        final UserInfo user = getUserInfo(callingUserId);
        return user != null && user.isManagedProfile();
    }

    @Override
    public boolean isSystemOnlyUser(ComponentName admin) {
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
        final int callingUserId = mInjector.userHandleGetCallingUserId();
        return UserManager.isSplitSystemUser() && callingUserId == UserHandle.USER_SYSTEM;
    }

    @Override
    public void reboot(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        // Make sure caller has DO.
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        synchronized (this) {
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
        enforceManagedProfile(userHandle, "set organization name");
        synchronized (this) {
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
        synchronized(this) {
            ActiveAdmin admin = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            return admin.organizationName;
        }
    }

    @Override
    public CharSequence getOrganizationNameForUser(int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        enforceFullCrossUsersPermission(userHandle);
        enforceManagedProfile(userHandle, "get organization name");
        synchronized (this) {
            ActiveAdmin profileOwner = getProfileOwnerAdminLocked(userHandle);
            return (profileOwner != null)
                    ? profileOwner.organizationName
                    : null;
        }
    }

    @Override
    public void setAffiliationIds(ComponentName admin, List<String> ids) {
        final Set<String> affiliationIds = new ArraySet<String>(ids);
        final int callingUserId = mInjector.userHandleGetCallingUserId();

        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            getUserData(callingUserId).mAffiliationIds = affiliationIds;
            saveSettingsLocked(callingUserId);
            if (callingUserId != UserHandle.USER_SYSTEM && isDeviceOwner(admin, callingUserId)) {
                // Affiliation ids specified by the device owner are additionally stored in
                // UserHandle.USER_SYSTEM's DevicePolicyData.
                getUserData(UserHandle.USER_SYSTEM).mAffiliationIds = affiliationIds;
                saveSettingsLocked(UserHandle.USER_SYSTEM);
            }
        }
    }

    @Override
    public boolean isAffiliatedUser() {
        final int callingUserId = mInjector.userHandleGetCallingUserId();

        synchronized (this) {
            if (mOwners.getDeviceOwnerUserId() == callingUserId) {
                // The user that the DO is installed on is always affiliated.
                return true;
            }
            final ComponentName profileOwner = getProfileOwner(callingUserId);
            if (profileOwner == null
                    || !profileOwner.getPackageName().equals(mOwners.getDeviceOwnerPackageName())) {
                return false;
            }
            final Set<String> userAffiliationIds = getUserData(callingUserId).mAffiliationIds;
            final Set<String> deviceAffiliationIds =
                    getUserData(UserHandle.USER_SYSTEM).mAffiliationIds;
            for (String id : userAffiliationIds) {
                if (deviceAffiliationIds.contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void disableSecurityLoggingIfNotCompliant() {
        if (!isDeviceOwnerManagedSingleUserDevice()) {
            mInjector.securityLogSetLoggingEnabledProperty(false);
            Slog.w(LOG_TAG, "Security logging turned off as it's no longer a single user device.");
        }
    }

    @Override
    public void setSecurityLoggingEnabled(ComponentName admin, boolean enabled) {
        Preconditions.checkNotNull(admin);
        ensureDeviceOwnerManagingSingleUser(admin);

        synchronized (this) {
            if (enabled == mInjector.securityLogGetLoggingEnabledProperty()) {
                return;
            }
            mInjector.securityLogSetLoggingEnabledProperty(enabled);
            if (enabled) {
                mSecurityLogMonitor.start();
            } else {
                mSecurityLogMonitor.stop();
            }
        }
    }

    @Override
    public boolean isSecurityLoggingEnabled(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            return mInjector.securityLogGetLoggingEnabledProperty();
        }
    }

    @Override
    public ParceledListSlice<SecurityEvent> retrievePreRebootSecurityLogs(ComponentName admin) {
        Preconditions.checkNotNull(admin);
        ensureDeviceOwnerManagingSingleUser(admin);

        if (!mContext.getResources().getBoolean(R.bool.config_supportPreRebootSecurityLogs)) {
            return null;
        }

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
        Preconditions.checkNotNull(admin);
        ensureDeviceOwnerManagingSingleUser(admin);

        List<SecurityEvent> logs = mSecurityLogMonitor.retrieveLogs();
        return logs != null ? new ParceledListSlice<SecurityEvent>(logs) : null;
    }

    private void enforceCanManageDeviceAdmin() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_DEVICE_ADMINS,
                null);
    }

    private void enforceCanManageProfileAndDeviceOwners() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS, null);
    }

    @Override
    public boolean isUninstallInQueue(final String packageName) {
        enforceCanManageDeviceAdmin();
        final int userId = mInjector.userHandleGetCallingUserId();
        Pair<String, Integer> packageUserPair = new Pair<>(packageName, userId);
        synchronized (this) {
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
        synchronized (this) {
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

    private void removePackageIfRequired(final String packageName, final int userId) {
        if (!packageHasActiveAdmins(packageName, userId)) {
            // Will not do anything if uninstall was not requested or was already started.
            startUninstallIntent(packageName, userId);
        }
    }

    private void startUninstallIntent(final String packageName, final int userId) {
        final Pair<String, Integer> packageUserPair = new Pair<>(packageName, userId);
        synchronized (this) {
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
        synchronized (this) {
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
            saveSettingsLocked(userHandle);
            updateMaximumTimeToLockLocked(userHandle);
            policy.mRemovingAdmins.remove(adminReceiver);
        }
        // The removed admin might have disabled camera, so update user
        // restrictions.
        pushUserRestrictions(userHandle);
    }
}
