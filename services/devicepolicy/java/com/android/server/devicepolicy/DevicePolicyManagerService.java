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
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.TEXT;

import android.Manifest.permission;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.AccountManager;
import android.app.Activity;
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
import android.app.admin.SystemUpdatePolicy;
import android.app.backup.IBackupManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.IAudioService;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
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
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.IWindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.devicepolicy.DevicePolicyManagerService.ActiveAdmin.TrustAgentInfo;

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
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Implementation of the device policy APIs.
 */
public class DevicePolicyManagerService extends IDevicePolicyManager.Stub {

    private static final String LOG_TAG = "DevicePolicyManagerService";

    private static final boolean VERBOSE_LOG = false; // DO NOT SUBMIT WITH TRUE

    private static final String DEVICE_POLICIES_XML = "device_policies.xml";

    private static final String TAG_LOCK_TASK_COMPONENTS = "lock-task-component";

    private static final String TAG_STATUS_BAR = "statusbar";

    private static final String ATTR_DISABLED = "disabled";

    private static final String DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML =
            "do-not-ask-credentials-on-boot";

    private static final int REQUEST_EXPIRE_PASSWORD = 5571;

    private static final long MS_PER_DAY = 86400 * 1000;

    private static final long EXPIRATION_GRACE_PERIOD_MS = 5 * MS_PER_DAY; // 5 days, in ms

    protected static final String ACTION_EXPIRED_PASSWORD_NOTIFICATION
            = "com.android.server.ACTION_EXPIRED_PASSWORD_NOTIFICATION";

    private static final int MONITORING_CERT_NOTIFICATION_ID = R.string.ssl_ca_cert_warning;
    private static final int PROFILE_WIPED_NOTIFICATION_ID = 1001;

    private static final boolean DBG = false;

    private static final String ATTR_PERMISSION_PROVIDER = "permission-provider";
    private static final String ATTR_SETUP_COMPLETE = "setup-complete";
    private static final String ATTR_PERMISSION_POLICY = "permission-policy";

    private static final String ATTR_DELEGATED_CERT_INSTALLER = "delegated-cert-installer";

    private static final int STATUS_BAR_DISABLE_MASK =
            StatusBarManager.DISABLE_EXPAND |
            StatusBarManager.DISABLE_NOTIFICATION_ICONS |
            StatusBarManager.DISABLE_NOTIFICATION_ALERTS |
            StatusBarManager.DISABLE_SEARCH;

    private static final int STATUS_BAR_DISABLE2_MASK =
            StatusBarManager.DISABLE2_QUICK_SETTINGS;

    private static final Set<String> DEVICE_OWNER_USER_RESTRICTIONS;
    static {
        DEVICE_OWNER_USER_RESTRICTIONS = new HashSet();
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_USB_FILE_TRANSFER);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_CONFIG_TETHERING);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_NETWORK_RESET);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_FACTORY_RESET);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_ADD_USER);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_CONFIG_CELL_BROADCASTS);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_UNMUTE_MICROPHONE);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_ADJUST_VOLUME);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_SMS);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_FUN);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_SAFE_BOOT);
        DEVICE_OWNER_USER_RESTRICTIONS.add(UserManager.DISALLOW_CREATE_WINDOWS);
    }

    // The following user restrictions cannot be changed by any active admin, including device
    // owner and profile owner.
    private static final Set<String> IMMUTABLE_USER_RESTRICTIONS;
    static {
        IMMUTABLE_USER_RESTRICTIONS = new HashSet();
        IMMUTABLE_USER_RESTRICTIONS.add(UserManager.DISALLOW_WALLPAPER);
    }

    private static final Set<String> SECURE_SETTINGS_WHITELIST;
    private static final Set<String> SECURE_SETTINGS_DEVICEOWNER_WHITELIST;
    private static final Set<String> GLOBAL_SETTINGS_WHITELIST;
    private static final Set<String> GLOBAL_SETTINGS_DEPRECATED;
    static {
        SECURE_SETTINGS_WHITELIST = new HashSet();
        SECURE_SETTINGS_WHITELIST.add(Settings.Secure.DEFAULT_INPUT_METHOD);
        SECURE_SETTINGS_WHITELIST.add(Settings.Secure.SKIP_FIRST_USE_HINTS);
        SECURE_SETTINGS_WHITELIST.add(Settings.Secure.INSTALL_NON_MARKET_APPS);

        SECURE_SETTINGS_DEVICEOWNER_WHITELIST = new HashSet();
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST.addAll(SECURE_SETTINGS_WHITELIST);
        SECURE_SETTINGS_DEVICEOWNER_WHITELIST.add(Settings.Secure.LOCATION_MODE);

        GLOBAL_SETTINGS_WHITELIST = new HashSet();
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.ADB_ENABLED);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.AUTO_TIME);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.AUTO_TIME_ZONE);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.DATA_ROAMING);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.USB_MASS_STORAGE_ENABLED);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.WIFI_SLEEP_POLICY);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.STAY_ON_WHILE_PLUGGED_IN);
        GLOBAL_SETTINGS_WHITELIST.add(Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN);

        GLOBAL_SETTINGS_DEPRECATED = new HashSet();
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.BLUETOOTH_ON);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.MODE_RINGER);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.NETWORK_PREFERENCE);
        GLOBAL_SETTINGS_DEPRECATED.add(Settings.Global.WIFI_ON);
    }

    // Keyguard features that when set of a profile will affect the profiles
    // parent user.
    private static final int PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER =
            DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS
            | DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT;

    // Keyguard features that are allowed to be set on a managed profile
    private static final int PROFILE_KEYGUARD_FEATURES =
            PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER
            | DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS;

    final Context mContext;
    final UserManager mUserManager;
    final PowerManager.WakeLock mWakeLock;

    final LocalService mLocalService;

    final PowerManager mPowerManager;
    final PowerManagerInternal mPowerManagerInternal;

    IWindowManager mIWindowManager;
    NotificationManager mNotificationManager;

    // Stores and loads state on device and profile owners.
    private DeviceOwner mDeviceOwner;

    private final Binder mToken = new Binder();

    /**
     * Whether or not device admin feature is supported. If it isn't return defaults for all
     * public methods.
     */
    private boolean mHasFeature;

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
            if (phase == PHASE_LOCK_SETTINGS_READY) {
                mService.systemReady();
            }
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
        int mPermissionPolicy;

        final HashMap<ComponentName, ActiveAdmin> mAdminMap = new HashMap<>();
        final ArrayList<ActiveAdmin> mAdminList = new ArrayList<>();
        final ArrayList<ComponentName> mRemovingAdmins = new ArrayList<>();

        // This is the list of component allowed to start lock task mode.
        List<String> mLockTaskPackages = new ArrayList<>();

        boolean mStatusBarDisabled = false;

        ComponentName mRestrictionsProvider;

        String mDelegatedCertInstallerPackage;

        boolean doNotAskCredentialsOnBoot = false;

        public DevicePolicyData(int userHandle) {
            mUserHandle = userHandle;
        }
    }

    final SparseArray<DevicePolicyData> mUserData = new SparseArray<>();

    Handler mHandler = new Handler();

    BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                    getSendingUserId());
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || ACTION_EXPIRED_PASSWORD_NOTIFICATION.equals(action)) {
                if (DBG) Slog.v(LOG_TAG, "Sending password expiration notifications for action "
                        + action + " for user " + userHandle);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handlePasswordExpirationNotification(userHandle);
                    }
                });
            }
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || KeyChain.ACTION_STORAGE_CHANGED.equals(action)) {
                new MonitoringCertNotificationTask().execute(intent);
            }
            if (Intent.ACTION_USER_REMOVED.equals(action)) {
                removeUserData(userHandle);
            } else if (Intent.ACTION_USER_STARTED.equals(action)
                    || Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {

                if (Intent.ACTION_USER_STARTED.equals(action)) {
                    // Reset the policy data
                    synchronized (DevicePolicyManagerService.this) {
                        mUserData.remove(userHandle);
                    }
                }
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
        private static final String TAG_DISABLE_BLUETOOTH_CONTACT_SHARING
                = "disable-bt-contacts-sharing";
        private static final String TAG_DISABLE_SCREEN_CAPTURE = "disable-screen-capture";
        private static final String TAG_DISABLE_ACCOUNT_MANAGEMENT = "disable-account-management";
        private static final String TAG_REQUIRE_AUTO_TIME = "require_auto_time";
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
        boolean disableBluetoothContactSharing = true;
        boolean disableScreenCapture = false; // Can only be set by a device/profile owner.
        boolean requireAutoTime = false; // Can only be set by a device owner.

        static class TrustAgentInfo {
            public PersistableBundle options;
            TrustAgentInfo(PersistableBundle bundle) {
                options = bundle;
            }
        }

        Set<String> accountTypesWithManagementDisabled = new HashSet<String>();

        // The list of permitted accessibility services package namesas set by a profile
        // or device owner. Null means all accessibility services are allowed, empty means
        // none except system services are allowed.
        List<String> permittedAccessiblityServices;

        // The list of permitted input methods package names as set by a profile or device owner.
        // Null means all input methods are allowed, empty means none except system imes are
        // allowed.
        List<String> permittedInputMethods;

        // TODO: review implementation decisions with frameworks team
        boolean specifiesGlobalProxy = false;
        String globalProxySpec = null;
        String globalProxyExclusionList = null;

        HashMap<String, TrustAgentInfo> trustAgentInfos = new HashMap<String, TrustAgentInfo>();

        List<String> crossProfileWidgetProviders;

        ActiveAdmin(DeviceAdminInfo _info) {
            info = _info;
        }

        int getUid() { return info.getActivityInfo().applicationInfo.uid; }

        public UserHandle getUserHandle() {
            return new UserHandle(UserHandle.getUserId(info.getActivityInfo().applicationInfo.uid));
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
            if (disableBluetoothContactSharing) {
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
                } else if (TAG_DISABLE_BLUETOOTH_CONTACT_SHARING.equals(tag)) {
                    disableBluetoothContactSharing = Boolean.parseBoolean(parser
                            .getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_DISABLE_SCREEN_CAPTURE.equals(tag)) {
                    disableScreenCapture = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_VALUE));
                } else if (TAG_REQUIRE_AUTO_TIME.equals(tag)) {
                    requireAutoTime= Boolean.parseBoolean(
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
            Set<String> result = new HashSet<String>();
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

        private HashMap<String, TrustAgentInfo> getAllTrustAgentInfos(
                XmlPullParser parser, String tag) throws XmlPullParserException, IOException {
            int outerDepthDAM = parser.getDepth();
            int typeDAM;
            HashMap<String, TrustAgentInfo> result = new HashMap<String, TrustAgentInfo>();
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
                    PersistableBundle bundle = new PersistableBundle();
                    bundle.restoreFromXml(parser);
                    result.options = bundle;
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
            pw.print(prefix); pw.print("disableBluetoothContactSharing=");
                    pw.println(disableBluetoothContactSharing);
            pw.print(prefix); pw.print("disableScreenCapture=");
                    pw.println(disableScreenCapture);
            pw.print(prefix); pw.print("requireAutoTime=");
                    pw.println(requireAutoTime);
            pw.print(prefix); pw.print("disabledKeyguardFeatures=");
                    pw.println(disabledKeyguardFeatures);
            pw.print(prefix); pw.print("crossProfileWidgetProviders=");
                    pw.println(crossProfileWidgetProviders);
            if (!(permittedAccessiblityServices == null)) {
                pw.print(prefix); pw.print("permittedAccessibilityServices=");
                        pw.println(permittedAccessiblityServices.toString());
            }
            if (!(permittedInputMethods == null)) {
                pw.print(prefix); pw.print("permittedInputMethods=");
                        pw.println(permittedInputMethods.toString());
            }
        }
    }

    private void handlePackagesChanged(String packageName, int userHandle) {
        boolean removed = false;
        if (DBG) Slog.d(LOG_TAG, "Handling package changes for user " + userHandle);
        DevicePolicyData policy = getUserData(userHandle);
        IPackageManager pm = AppGlobals.getPackageManager();
        synchronized (this) {
            for (int i = policy.mAdminList.size() - 1; i >= 0; i--) {
                ActiveAdmin aa = policy.mAdminList.get(i);
                try {
                    // If we're checking all packages or if the specific one we're checking matches,
                    // then check if the package and receiver still exist.
                    final String adminPackage = aa.info.getPackageName();
                    if (packageName == null || packageName.equals(adminPackage)) {
                        if (pm.getPackageInfo(adminPackage, 0, userHandle) == null
                                || pm.getReceiverInfo(aa.info.getComponent(), 0, userHandle)
                                    == null) {
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
                syncDeviceCapabilitiesLocked(policy);
                saveSettingsLocked(policy.mUserHandle);
            }

            if (policy.mDelegatedCertInstallerPackage != null &&
                    (packageName == null
                    || packageName.equals(policy.mDelegatedCertInstallerPackage))) {
                try {
                    // Check if delegated cert installer package is removed.
                    if (pm.getPackageInfo(
                            policy.mDelegatedCertInstallerPackage, 0, userHandle) == null) {
                        policy.mDelegatedCertInstallerPackage = null;
                        saveSettingsLocked(policy.mUserHandle);
                    }
                } catch (RemoteException e) {
                    // Shouldn't happen
                }
            }
        }
    }

    /**
     * Instantiates the service.
     */
    public DevicePolicyManagerService(Context context) {
        mContext = context;
        mUserManager = UserManager.get(mContext);
        mHasFeature = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_DEVICE_ADMIN);
        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DPM");
        mLocalService = new LocalService();
        if (!mHasFeature) {
            // Skip the rest of the initialization
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(ACTION_EXPIRED_PASSWORD_NOTIFICATION);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_STARTED);
        filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);
        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, filter, null, mHandler);

        LocalServices.addService(DevicePolicyManagerInternal.class, mLocalService);
    }

    /**
     * Creates and loads the policy data from xml.
     * @param userHandle the user for whom to load the policy data
     * @return
     */
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
        long ident = Binder.clearCallingIdentity();
        try {
            return getUserData(userHandle);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void removeUserData(int userHandle) {
        synchronized (this) {
            if (userHandle == UserHandle.USER_OWNER) {
                Slog.w(LOG_TAG, "Tried to remove device policy file for user 0! Ignoring.");
                return;
            }
            if (mDeviceOwner != null) {
                mDeviceOwner.removeProfileOwner(userHandle);
                mDeviceOwner.writeOwnerFile();
            }

            DevicePolicyData policy = mUserData.get(userHandle);
            if (policy != null) {
                mUserData.remove(userHandle);
            }
            File policyFile = new File(Environment.getUserSystemDirectory(userHandle),
                    DEVICE_POLICIES_XML);
            policyFile.delete();
            Slog.i(LOG_TAG, "Removed device policy file " + policyFile.getAbsolutePath());
        }
        updateScreenCaptureDisabledInWindowManager(userHandle, false /* default value */);
    }

    void loadDeviceOwner() {
        synchronized (this) {
            mDeviceOwner = DeviceOwner.load();
            updateDeviceOwnerLocked();
        }
    }

    /**
     * Set an alarm for an upcoming event - expiration warning, expiration, or post-expiration
     * reminders.  Clears alarm if no expirations are configured.
     */
    protected void setExpirationAlarmCheckLocked(Context context, DevicePolicyData policy) {
        final long expiration = getPasswordExpirationLocked(null, policy.mUserHandle);
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

        long token = Binder.clearCallingIdentity();
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcastAsUser(context, REQUEST_EXPIRE_PASSWORD,
                    new Intent(ACTION_EXPIRED_PASSWORD_NOTIFICATION),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT,
                    new UserHandle(policy.mUserHandle));
            am.cancel(pi);
            if (alarmTime != 0) {
                am.set(AlarmManager.RTC, alarmTime, pi);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private IWindowManager getWindowManager() {
        if (mIWindowManager == null) {
            IBinder b = ServiceManager.getService(Context.WINDOW_SERVICE);
            mIWindowManager = IWindowManager.Stub.asInterface(b);
        }
        return mIWindowManager;
    }

    private NotificationManager getNotificationManager() {
        if (mNotificationManager == null) {
            mNotificationManager =
                    (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }
        return mNotificationManager;
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

    ActiveAdmin getActiveAdminForCallerLocked(ComponentName who, int reqPolicy)
            throws SecurityException {
        final int callingUid = Binder.getCallingUid();

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
                    + Binder.getCallingUid() + " for policy #" + reqPolicy);
        }
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
                throw new SecurityException("Admin " + who + " is not owned by uid "
                        + Binder.getCallingUid());
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

    private boolean isActiveAdminWithPolicyForUserLocked(ActiveAdmin admin, int reqPolicy,
            int userId) {
        boolean ownsDevice = isDeviceOwner(admin.info.getPackageName());
        boolean ownsProfile = (getProfileOwner(userId) != null
                && getProfileOwner(userId).getPackageName()
                    .equals(admin.info.getPackageName()));
        boolean ownsInitialization = isDeviceInitializer(admin.info.getPackageName())
                && !hasUserSetupCompleted(userId);

        if (reqPolicy == DeviceAdminInfo.USES_POLICY_DEVICE_OWNER) {
            if ((userId == UserHandle.USER_OWNER && (ownsDevice || ownsInitialization))
                    || (ownsDevice && ownsProfile)) {
                return true;
            }
        } else if (reqPolicy == DeviceAdminInfo.USES_POLICY_PROFILE_OWNER) {
            if ((userId == UserHandle.USER_OWNER && ownsDevice) || ownsProfile
                    || ownsInitialization) {
                return true;
            }
        } else {
            if (admin.info.usesPolicy(reqPolicy)) {
                return true;
            }
        }
        return false;
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
        List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
        for (UserInfo ui : profiles) {
            int id = ui.id;
            sendAdminCommandLocked(action, reqPolicy, id);
        }
    }

    void removeActiveAdminLocked(final ComponentName adminReceiver, int userHandle) {
        final ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
        if (admin != null) {
            synchronized (this) {
                getUserData(userHandle).mRemovingAdmins.add(adminReceiver);
            }
            sendAdminCommandLocked(admin,
                    DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLED,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            synchronized (DevicePolicyManagerService.this) {
                                int userHandle = admin.getUserHandle().getIdentifier();
                                DevicePolicyData policy = getUserData(userHandle);
                                boolean doProxyCleanup = admin.info.usesPolicy(
                                        DeviceAdminInfo.USES_POLICY_SETS_GLOBAL_PROXY);
                                policy.mAdminList.remove(admin);
                                policy.mAdminMap.remove(adminReceiver);
                                validatePasswordOwnerLocked(policy);
                                syncDeviceCapabilitiesLocked(policy);
                                if (doProxyCleanup) {
                                    resetGlobalProxyLocked(getUserData(userHandle));
                                }
                                saveSettingsLocked(userHandle);
                                updateMaximumTimeToLockLocked(policy);
                                policy.mRemovingAdmins.remove(adminReceiver);
                            }
                        }
                    });
        }
    }

    public DeviceAdminInfo findAdmin(ComponentName adminName, int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        enforceCrossUserPermission(userHandle);
        Intent resolveIntent = new Intent();
        resolveIntent.setComponent(adminName);
        List<ResolveInfo> infos = mContext.getPackageManager().queryBroadcastReceivers(
                resolveIntent,
                PackageManager.GET_META_DATA | PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                userHandle);
        if (infos == null || infos.size() <= 0) {
            throw new IllegalArgumentException("Unknown admin: " + adminName);
        }

        try {
            return new DeviceAdminInfo(mContext, infos.get(0));
        } catch (XmlPullParserException e) {
            Slog.w(LOG_TAG, "Bad device admin requested for user=" + userHandle + ": " + adminName,
                    e);
            return null;
        } catch (IOException e) {
            Slog.w(LOG_TAG, "Bad device admin requested for user=" + userHandle + ": " + adminName,
                    e);
            return null;
        }
    }

    private static JournaledFile makeJournaledFile(int userHandle) {
        final String base = userHandle == 0
                ? "/data/system/" + DEVICE_POLICIES_XML
                : new File(Environment.getUserSystemDirectory(userHandle), DEVICE_POLICIES_XML)
                        .getAbsolutePath();
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
            if (policy.mPermissionPolicy != DevicePolicyManager.PERMISSION_POLICY_PROMPT) {
                out.attribute(null, ATTR_PERMISSION_POLICY,
                        Integer.toString(policy.mPermissionPolicy));
            }
            if (policy.mDelegatedCertInstallerPackage != null) {
                out.attribute(null, ATTR_DELEGATED_CERT_INSTALLER,
                        policy.mDelegatedCertInstallerPackage);
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

            out.endTag(null, "policies");

            out.endDocument();
            stream.flush();
            FileUtils.sync(stream);
            stream.close();
            journal.commit();
            sendChangedNotification(userHandle);
        } catch (IOException e) {
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
        long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, new UserHandle(userHandle));
        } finally {
            Binder.restoreCallingIdentity(ident);
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
            String permissionPolicy = parser.getAttributeValue(null, ATTR_PERMISSION_POLICY);
            if (!TextUtils.isEmpty(permissionPolicy)) {
                policy.mPermissionPolicy = Integer.parseInt(permissionPolicy);
            }
            policy.mDelegatedCertInstallerPackage = parser.getAttributeValue(null,
                    ATTR_DELEGATED_CERT_INSTALLER);

            type = parser.next();
            int outerDepth = parser.getDepth();
            policy.mLockTaskPackages.clear();
            policy.mAdminList.clear();
            policy.mAdminMap.clear();
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
                                ComponentName.unflattenFromString(name), userHandle);
                        if (DBG && (UserHandle.getUserId(dai.getActivityInfo().applicationInfo.uid)
                                != userHandle)) {
                            Slog.w(LOG_TAG, "findAdmin returned an incorrect uid "
                                    + dai.getActivityInfo().applicationInfo.uid + " for user "
                                    + userHandle);
                        }
                        if (dai != null) {
                            ActiveAdmin ap = new ActiveAdmin(dai);
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
                } else if (TAG_LOCK_TASK_COMPONENTS.equals(tag)) {
                    policy.mLockTaskPackages.add(parser.getAttributeValue(null, "name"));
                } else if (TAG_STATUS_BAR.equals(tag)) {
                    policy.mStatusBarDisabled = Boolean.parseBoolean(
                            parser.getAttributeValue(null, ATTR_DISABLED));
                } else if (DO_NOT_ASK_CREDENTIALS_ON_BOOT_XML.equals(tag)) {
                    policy.doNotAskCredentialsOnBoot = true;
                } else {
                    Slog.w(LOG_TAG, "Unknown tag: " + tag);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } catch (NullPointerException e) {
            Slog.w(LOG_TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Slog.w(LOG_TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Slog.w(LOG_TAG, "failed parsing " + file + " " + e);
        } catch (FileNotFoundException e) {
            // Don't be noisy, this is normal if we haven't defined any policies.
        } catch (IOException e) {
            Slog.w(LOG_TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(LOG_TAG, "failed parsing " + file + " " + e);
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
        final long identity = Binder.clearCallingIdentity();
        try {
            LockPatternUtils utils = new LockPatternUtils(mContext);
            if (utils.getActivePasswordQuality(userHandle) < policy.mActivePasswordQuality) {
                Slog.w(LOG_TAG, "Active password quality 0x"
                        + Integer.toHexString(policy.mActivePasswordQuality)
                        + " does not match actual quality 0x"
                        + Integer.toHexString(utils.getActivePasswordQuality(userHandle)));
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
            Binder.restoreCallingIdentity(identity);
        }

        validatePasswordOwnerLocked(policy);
        syncDeviceCapabilitiesLocked(policy);
        updateMaximumTimeToLockLocked(policy);
        addDeviceInitializerToLockTaskPackagesLocked(userHandle);
        updateLockTaskPackagesLocked(policy.mLockTaskPackages, userHandle);
        if (policy.mStatusBarDisabled) {
            setStatusBarDisabledInternal(policy.mStatusBarDisabled, userHandle);
        }
    }

    private void updateLockTaskPackagesLocked(List<String> packages, int userId) {
        IActivityManager am = ActivityManagerNative.getDefault();
        long ident = Binder.clearCallingIdentity();
        try {
            am.updateLockTaskPackages(userId, packages.toArray(new String[packages.size()]));
        } catch (RemoteException e) {
            // Not gonna happen.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void updateDeviceOwnerLocked() {
        IActivityManager am = ActivityManagerNative.getDefault();
        long ident = Binder.clearCallingIdentity();
        try {
            am.updateDeviceOwner(getDeviceOwner());
        } catch (RemoteException e) {
            // Not gonna happen.
        } finally {
            Binder.restoreCallingIdentity(ident);
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

    /**
     * Pushes down policy information to the system for any policies related to general device
     * capabilities that need to be enforced by lower level services (e.g. Camera services).
     */
    void syncDeviceCapabilitiesLocked(DevicePolicyData policy) {
        // Ensure the status of the camera is synced down to the system. Interested native services
        // should monitor this value and act accordingly.
        String cameraPropertyForUser = SYSTEM_PROP_DISABLE_CAMERA_PREFIX + policy.mUserHandle;
        boolean systemState = SystemProperties.getBoolean(cameraPropertyForUser, false);
        boolean cameraDisabled = getCameraDisabled(null, policy.mUserHandle);
        if (cameraDisabled != systemState) {
            long token = Binder.clearCallingIdentity();
            try {
                String value = cameraDisabled ? "1" : "0";
                if (DBG) Slog.v(LOG_TAG, "Change in camera state ["
                        + cameraPropertyForUser + "] = " + value);
                SystemProperties.set(cameraPropertyForUser, value);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    public void systemReady() {
        if (!mHasFeature) {
            return;
        }
        getUserData(UserHandle.USER_OWNER);
        loadDeviceOwner();
        cleanUpOldUsers();
        // Register an observer for watching for user setup complete.
        new SetupContentObserver(mHandler).register(mContext.getContentResolver());
        // Initialize the user setup state, to handle the upgrade case.
        updateUserSetupComplete();

        // Update the screen capture disabled cache in the window manager
        List<UserInfo> users = mUserManager.getUsers(true);
        final int N = users.size();
        for (int i = 0; i < N; i++) {
            int userHandle = users.get(i).id;
            updateScreenCaptureDisabledInWindowManager(userHandle,
                    getScreenCaptureDisabled(null, userHandle));
        }
    }

    private void cleanUpOldUsers() {
        // This is needed in case the broadcast {@link Intent.ACTION_USER_REMOVED} was not handled
        // before reboot
        Set<Integer> usersWithProfileOwners;
        Set<Integer> usersWithData;
        synchronized(this) {
            usersWithProfileOwners = mDeviceOwner != null
                    ? mDeviceOwner.getProfileOwnerKeys() : new HashSet<Integer>();
            usersWithData = new HashSet<Integer>();
            for (int i = 0; i < mUserData.size(); i++) {
                usersWithData.add(mUserData.keyAt(i));
            }
        }
        List<UserInfo> allUsers = mUserManager.getUsers();

        Set<Integer> deletedUsers = new HashSet<Integer>();
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

            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo ui : profiles) {
                int profileUserHandle = ui.id;
                final DevicePolicyData policy = getUserData(profileUserHandle);
                final int count = policy.mAdminList.size();
                if (count > 0) {
                    for (int i = 0; i < count; i++) {
                        final ActiveAdmin admin = policy.mAdminList.get(i);
                        if (admin.info.usesPolicy(DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD)
                                && admin.passwordExpirationTimeout > 0L
                                && now >= admin.passwordExpirationDate - EXPIRATION_GRACE_PERIOD_MS
                                && admin.passwordExpirationDate > 0L) {
                            sendAdminCommandLocked(admin,
                                    DeviceAdminReceiver.ACTION_PASSWORD_EXPIRING);
                        }
                    }
                }
            }
            setExpirationAlarmCheckLocked(mContext, getUserData(userHandle));
        }
    }

    private class MonitoringCertNotificationTask extends AsyncTask<Intent, Void, Void> {
        @Override
        protected Void doInBackground(Intent... params) {
            int userHandle = params[0].getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_ALL);

            if (userHandle == UserHandle.USER_ALL) {
                for (UserInfo userInfo : mUserManager.getUsers()) {
                    manageNotification(userInfo.getUserHandle());
                }
            } else {
                manageNotification(new UserHandle(userHandle));
            }
            return null;
        }

        private void manageNotification(UserHandle userHandle) {
            if (!mUserManager.isUserRunning(userHandle)) {
                return;
            }

            // Call out to KeyChain to check for user-added CAs
            boolean hasCert = false;
            try {
                KeyChainConnection kcs = KeyChain.bindAsUser(mContext, userHandle);
                try {
                    if (!kcs.getService().getUserCaAliases().getList().isEmpty()) {
                        hasCert = true;
                    }
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Could not connect to KeyChain service", e);
                } finally {
                    kcs.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Log.e(LOG_TAG, "Could not connect to KeyChain service", e);
            }
            if (!hasCert) {
                getNotificationManager().cancelAsUser(
                        null, MONITORING_CERT_NOTIFICATION_ID, userHandle);
                return;
            }

            // Build and show a warning notification
            int smallIconId;
            String contentText;
            final String ownerName = getDeviceOwnerName();
            if (isManagedProfile(userHandle.getIdentifier())) {
                contentText = mContext.getString(R.string.ssl_ca_cert_noti_by_administrator);
                smallIconId = R.drawable.stat_sys_certificate_info;
            } else if (ownerName != null) {
                contentText = mContext.getString(R.string.ssl_ca_cert_noti_managed, ownerName);
                smallIconId = R.drawable.stat_sys_certificate_info;
            } else {
                contentText = mContext.getString(R.string.ssl_ca_cert_noti_by_unknown);
                smallIconId = android.R.drawable.stat_sys_warning;
            }

            Intent dialogIntent = new Intent(Settings.ACTION_MONITORING_CERT_INFO);
            dialogIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            dialogIntent.setPackage("com.android.settings");
            PendingIntent notifyIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    dialogIntent, PendingIntent.FLAG_UPDATE_CURRENT, null, userHandle);

            final Context userContext;
            try {
                userContext = mContext.createPackageContextAsUser("android", 0, userHandle);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(LOG_TAG, "Create context as " + userHandle + " failed", e);
                return;
            }
            final Notification noti = new Notification.Builder(userContext)
                .setSmallIcon(smallIconId)
                .setContentTitle(mContext.getString(R.string.ssl_ca_cert_warning))
                .setContentText(contentText)
                .setContentIntent(notifyIntent)
                .setPriority(Notification.PRIORITY_HIGH)
                .setShowWhen(false)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .build();

            getNotificationManager().notifyAsUser(
                    null, MONITORING_CERT_NOTIFICATION_ID, noti, userHandle);
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
        enforceCrossUserPermission(userHandle);

        DevicePolicyData policy = getUserData(userHandle);
        DeviceAdminInfo info = findAdmin(adminReceiver, userHandle);
        if (info == null) {
            throw new IllegalArgumentException("Bad admin: " + adminReceiver);
        }
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (!refreshing
                        && getActiveAdminUncheckedLocked(adminReceiver, userHandle) != null) {
                    throw new IllegalArgumentException("Admin is already added");
                }
                ActiveAdmin newAdmin = new ActiveAdmin(info);
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
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public boolean isAdminActive(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            return getActiveAdminUncheckedLocked(adminReceiver, userHandle) != null;
        }
    }

    @Override
    public boolean isRemovingAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        enforceCrossUserPermission(userHandle);
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
        enforceCrossUserPermission(userHandle);
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

        enforceCrossUserPermission(userHandle);
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
        enforceCrossUserPermission(userHandle);
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

    @Override
    public void removeActiveAdmin(ComponentName adminReceiver, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(adminReceiver, userHandle);
            if (admin == null) {
                return;
            }
            if (admin.getUid() != Binder.getCallingUid()) {
                // Active device owners must remain active admins.
                if (isDeviceOwner(adminReceiver.getPackageName())) {
                    return;
                }
                mContext.enforceCallingOrSelfPermission(
                        android.Manifest.permission.MANAGE_DEVICE_ADMINS, null);
            }
            long ident = Binder.clearCallingIdentity();
            try {
                removeActiveAdminLocked(adminReceiver, userHandle);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void setPasswordQuality(ComponentName who, int quality) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        validateQualityConstant(quality);

        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.passwordQuality != quality) {
                ap.passwordQuality = quality;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordQuality(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int mode = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.passwordQuality : mode;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (mode < admin.passwordQuality) {
                        mode = admin.passwordQuality;
                    }
                }
            }
            return mode;
        }
    }

    @Override
    public void setPasswordMinimumLength(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordLength != length) {
                ap.minimumPasswordLength = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumLength(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordLength : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (length < admin.minimumPasswordLength) {
                        length = admin.minimumPasswordLength;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordHistoryLength(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.passwordHistoryLength != length) {
                ap.passwordHistoryLength = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordHistoryLength(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.passwordHistoryLength : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (length < admin.passwordHistoryLength) {
                        length = admin.passwordHistoryLength;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordExpirationTimeout(ComponentName who, long timeout) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        Preconditions.checkArgumentNonnegative(timeout, "Timeout must be >= 0 ms");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD);
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
            // in case this is the first one
            setExpirationAlarmCheckLocked(mContext, getUserData(userHandle));
        }
    }

    /**
     * Return a single admin's expiration cycle time, or the min of all cycle times.
     * Returns 0 if not configured.
     */
    @Override
    public long getPasswordExpirationTimeout(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0L;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            long timeout = 0L;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.passwordExpirationTimeout : timeout;
            }

            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (timeout == 0L || (admin.passwordExpirationTimeout != 0L
                            && timeout > admin.passwordExpirationTimeout)) {
                        timeout = admin.passwordExpirationTimeout;
                    }
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
            if (Binder.getCallingUid() == Process.myUid()) {
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
    private long getPasswordExpirationLocked(ComponentName who, int userHandle) {
        long timeout = 0L;

        if (who != null) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
            return admin != null ? admin.passwordExpirationDate : timeout;
        }

        List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
        for (UserInfo userInfo : profiles) {
            DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
            final int N = policy.mAdminList.size();
            for (int i = 0; i < N; i++) {
                ActiveAdmin admin = policy.mAdminList.get(i);
                if (timeout == 0L || (admin.passwordExpirationDate != 0
                        && timeout > admin.passwordExpirationDate)) {
                    timeout = admin.passwordExpirationDate;
                }
            }
        }
        return timeout;
    }

    @Override
    public long getPasswordExpiration(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0L;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            return getPasswordExpirationLocked(who, userHandle);
        }
    }

    @Override
    public void setPasswordMinimumUpperCase(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordUpperCase != length) {
                ap.minimumPasswordUpperCase = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumUpperCase(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordUpperCase : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (length < admin.minimumPasswordUpperCase) {
                        length = admin.minimumPasswordUpperCase;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumLowerCase(ComponentName who, int length) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordLowerCase != length) {
                ap.minimumPasswordLowerCase = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumLowerCase(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordLowerCase : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (length < admin.minimumPasswordLowerCase) {
                        length = admin.minimumPasswordLowerCase;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumLetters(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordLetters != length) {
                ap.minimumPasswordLetters = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumLetters(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordLetters : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                        continue;
                    }
                    if (length < admin.minimumPasswordLetters) {
                        length = admin.minimumPasswordLetters;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumNumeric(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordNumeric != length) {
                ap.minimumPasswordNumeric = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumNumeric(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordNumeric : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i = 0; i < N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                        continue;
                    }
                    if (length < admin.minimumPasswordNumeric) {
                        length = admin.minimumPasswordNumeric;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumSymbols(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordSymbols != length) {
                ap.minimumPasswordSymbols = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumSymbols(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordSymbols : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                        continue;
                    }
                    if (length < admin.minimumPasswordSymbols) {
                        length = admin.minimumPasswordSymbols;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public void setPasswordMinimumNonLetter(ComponentName who, int length) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (ap.minimumPasswordNonLetter != length) {
                ap.minimumPasswordNonLetter = length;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getPasswordMinimumNonLetter(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            int length = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.minimumPasswordNonLetter : length;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (!isLimitPasswordAllowed(admin, PASSWORD_QUALITY_COMPLEX)) {
                        continue;
                    }
                    if (length < admin.minimumPasswordNonLetter) {
                        length = admin.minimumPasswordNonLetter;
                    }
                }
            }
            return length;
        }
    }

    @Override
    public boolean isActivePasswordSufficient(int userHandle) {
        if (!mHasFeature) {
            return true;
        }
        enforceCrossUserPermission(userHandle);

        synchronized (this) {

            // The active password is stored in the user that runs the launcher
            // If the user this is called from is part of a profile group, that is the parent
            // of the group.
            UserInfo parent = getProfileParent(userHandle);
            int id = (parent == null) ? userHandle : parent.id;
            DevicePolicyData policy = getUserDataUnchecked(id);

            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null, DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD);
            if (policy.mActivePasswordQuality < getPasswordQuality(null, userHandle)
                    || policy.mActivePasswordLength < getPasswordMinimumLength(null, userHandle)) {
                return false;
            }
            if (policy.mActivePasswordQuality != DevicePolicyManager.PASSWORD_QUALITY_COMPLEX) {
                return true;
            }
            return policy.mActivePasswordUpperCase >= getPasswordMinimumUpperCase(null, userHandle)
                && policy.mActivePasswordLowerCase >= getPasswordMinimumLowerCase(null, userHandle)
                && policy.mActivePasswordLetters >= getPasswordMinimumLetters(null, userHandle)
                && policy.mActivePasswordNumeric >= getPasswordMinimumNumeric(null, userHandle)
                && policy.mActivePasswordSymbols >= getPasswordMinimumSymbols(null, userHandle)
                && policy.mActivePasswordNonLetter >= getPasswordMinimumNonLetter(null, userHandle);
        }
    }

    @Override
    public int getCurrentFailedPasswordAttempts(int userHandle) {
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_WATCH_LOGIN);

            // The active password is stored in the parent.
            UserInfo parent = getProfileParent(userHandle);
            int id = (parent == null) ? userHandle : parent.id;
            DevicePolicyData policy = getUserDataUnchecked(id);

            return policy.mFailedPasswordAttempts;
        }
    }

    @Override
    public void setMaximumFailedPasswordsForWipe(ComponentName who, int num) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_WIPE_DATA);
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_WATCH_LOGIN);
            if (ap.maximumFailedPasswordsForWipe != num) {
                ap.maximumFailedPasswordsForWipe = num;
                saveSettingsLocked(userHandle);
            }
        }
    }

    @Override
    public int getMaximumFailedPasswordsForWipe(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            ActiveAdmin admin = (who != null) ? getActiveAdminUncheckedLocked(who, userHandle)
                    : getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle);
            return admin != null ? admin.maximumFailedPasswordsForWipe : 0;
        }
    }

    @Override
    public int getProfileWithMinimumFailedPasswordsForWipe(int userHandle) {
        if (!mHasFeature) {
            return UserHandle.USER_NULL;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            ActiveAdmin admin = getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle);
            return admin != null ? admin.getUserHandle().getIdentifier() : UserHandle.USER_NULL;
        }
    }

    /**
     * Returns the admin with the strictest policy on maximum failed passwords for this user and all
     * profiles that are visible from this user. If the policy for the primary and any other profile
     * are equal, it returns the admin for the primary profile.
     * Returns {@code null} if none of them have that policy set.
     */
    private ActiveAdmin getAdminWithMinimumFailedPasswordsForWipeLocked(int userHandle) {
        int count = 0;
        ActiveAdmin strictestAdmin = null;
        for (UserInfo userInfo : mUserManager.getProfiles(userHandle)) {
            DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
            for (ActiveAdmin admin : policy.mAdminList) {
                if (admin.maximumFailedPasswordsForWipe ==
                        ActiveAdmin.DEF_MAXIMUM_FAILED_PASSWORDS_FOR_WIPE) {
                    continue;  // No max number of failed passwords policy set for this profile.
                }

                // We always favor the primary profile if several profiles have the same value set.
                if (count == 0 ||
                        count > admin.maximumFailedPasswordsForWipe ||
                        (userInfo.isPrimary() && count >= admin.maximumFailedPasswordsForWipe)) {
                    count = admin.maximumFailedPasswordsForWipe;
                    strictestAdmin = admin;
                }
            }
        }
        return strictestAdmin;
    }

    @Override
    public boolean resetPassword(String passwordOrNull, int flags) {
        if (!mHasFeature) {
            return false;
        }
        final int userHandle = UserHandle.getCallingUserId();
        enforceNotManagedProfile(userHandle, "reset the password");

        String password = passwordOrNull != null ? passwordOrNull : "";

        int quality;
        synchronized (this) {
            // This api can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_RESET_PASSWORD);
            quality = getPasswordQuality(null, userHandle);
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
            int length = getPasswordMinimumLength(null, userHandle);
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
                int neededLetters = getPasswordMinimumLetters(null, userHandle);
                if(letters < neededLetters) {
                    Slog.w(LOG_TAG, "resetPassword: number of letters " + letters
                            + " does not meet required number of letters " + neededLetters);
                    return false;
                }
                int neededNumbers = getPasswordMinimumNumeric(null, userHandle);
                if (numbers < neededNumbers) {
                    Slog.w(LOG_TAG, "resetPassword: number of numerical digits " + numbers
                            + " does not meet required number of numerical digits "
                            + neededNumbers);
                    return false;
                }
                int neededLowerCase = getPasswordMinimumLowerCase(null, userHandle);
                if (lowercase < neededLowerCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of lowercase letters " + lowercase
                            + " does not meet required number of lowercase letters "
                            + neededLowerCase);
                    return false;
                }
                int neededUpperCase = getPasswordMinimumUpperCase(null, userHandle);
                if (uppercase < neededUpperCase) {
                    Slog.w(LOG_TAG, "resetPassword: number of uppercase letters " + uppercase
                            + " does not meet required number of uppercase letters "
                            + neededUpperCase);
                    return false;
                }
                int neededSymbols = getPasswordMinimumSymbols(null, userHandle);
                if (symbols < neededSymbols) {
                    Slog.w(LOG_TAG, "resetPassword: number of special symbols " + symbols
                            + " does not meet required number of special symbols " + neededSymbols);
                    return false;
                }
                int neededNonLetter = getPasswordMinimumNonLetter(null, userHandle);
                if (nonletter < neededNonLetter) {
                    Slog.w(LOG_TAG, "resetPassword: number of non-letter characters " + nonletter
                            + " does not meet required number of non-letter characters "
                            + neededNonLetter);
                    return false;
                }
            }
        }

        int callingUid = Binder.getCallingUid();
        DevicePolicyData policy = getUserData(userHandle);
        if (policy.mPasswordOwner >= 0 && policy.mPasswordOwner != callingUid) {
            Slog.w(LOG_TAG, "resetPassword: already set by another uid and not entered by user");
            return false;
        }

        boolean callerIsDeviceOwnerAdmin = isCallerDeviceOwnerOrInitializer(callingUid);
        boolean doNotAskCredentialsOnBoot =
                (flags & DevicePolicyManager.RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT) != 0;
        if (callerIsDeviceOwnerAdmin && doNotAskCredentialsOnBoot) {
            setDoNotAskCredentialsOnBoot();
        }

        // Don't do this with the lock held, because it is going to call
        // back in to the service.
        long ident = Binder.clearCallingIdentity();
        try {
            LockPatternUtils utils = new LockPatternUtils(mContext);
            if (!TextUtils.isEmpty(password)) {
                utils.saveLockPassword(password, null, quality, userHandle);
            } else {
                utils.clearLock(userHandle);
            }
            boolean requireEntry = (flags & DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY) != 0;
            if (requireEntry) {
                utils.requireCredentialEntry(UserHandle.USER_ALL);
            }
            synchronized (this) {
                int newOwner = requireEntry ? callingUid : -1;
                if (policy.mPasswordOwner != newOwner) {
                    policy.mPasswordOwner = newOwner;
                    saveSettingsLocked(userHandle);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return true;
    }

    private void setDoNotAskCredentialsOnBoot() {
        synchronized (this) {
            DevicePolicyData policyData = getUserData(UserHandle.USER_OWNER);
            if (!policyData.doNotAskCredentialsOnBoot) {
                policyData.doNotAskCredentialsOnBoot = true;
                saveSettingsLocked(UserHandle.USER_OWNER);
            }
        }
    }

    @Override
    public boolean getDoNotAskCredentialsOnBoot() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.QUERY_DO_NOT_ASK_CREDENTIALS_ON_BOOT, null);
        synchronized (this) {
            DevicePolicyData policyData = getUserData(UserHandle.USER_OWNER);
            return policyData.doNotAskCredentialsOnBoot;
        }
    }

    @Override
    public void setMaximumTimeToLock(ComponentName who, long timeMs) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_FORCE_LOCK);
            if (ap.maximumTimeToUnlock != timeMs) {
                ap.maximumTimeToUnlock = timeMs;
                saveSettingsLocked(userHandle);
                updateMaximumTimeToLockLocked(getUserData(userHandle));
            }
        }
    }

    void updateMaximumTimeToLockLocked(DevicePolicyData policy) {
        long timeMs = getMaximumTimeToLock(null, policy.mUserHandle);
        if (policy.mLastMaximumTimeToLock == timeMs) {
            return;
        }

        long ident = Binder.clearCallingIdentity();
        try {
            if (timeMs <= 0) {
                timeMs = Integer.MAX_VALUE;
            } else {
                // Make sure KEEP_SCREEN_ON is disabled, since that
                // would allow bypassing of the maximum time to lock.
                Settings.Global.putInt(mContext.getContentResolver(),
                        Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
            }

            policy.mLastMaximumTimeToLock = timeMs;
            mPowerManagerInternal.setMaximumScreenOffTimeoutFromDeviceAdmin((int)timeMs);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public long getMaximumTimeToLock(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            long time = 0;

            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return admin != null ? admin.maximumTimeToUnlock : time;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin admin = policy.mAdminList.get(i);
                    if (time == 0) {
                        time = admin.maximumTimeToUnlock;
                    } else if (admin.maximumTimeToUnlock != 0
                            && time > admin.maximumTimeToUnlock) {
                        time = admin.maximumTimeToUnlock;
                    }
                }
            }
            return time;
        }
    }

    @Override
    public void lockNow() {
        if (!mHasFeature) {
            return;
        }
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_FORCE_LOCK);
            lockNowUnchecked();
        }
    }

    private void lockNowUnchecked() {
        long ident = Binder.clearCallingIdentity();
        try {
            // Power off the display
            mPowerManager.goToSleep(SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_DEVICE_ADMIN, 0);
            // Ensure the device is locked
            new LockPatternUtils(mContext).requireCredentialEntry(UserHandle.USER_ALL);
            getWindowManager().lockNow(null);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isExtStorageEncrypted() {
        String state = SystemProperties.get("vold.decrypt");
        return !"".equals(state);
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

    private boolean isCallerDelegatedCertInstaller() {
        final int callingUid = Binder.getCallingUid();
        final int userHandle = UserHandle.getUserId(callingUid);
        synchronized (this) {
            final DevicePolicyData policy = getUserData(userHandle);
            if (policy.mDelegatedCertInstallerPackage == null) {
                return false;
            }

            try {
                int uid = mContext.getPackageManager().getPackageUid(
                        policy.mDelegatedCertInstallerPackage, userHandle);
                return uid == callingUid;
            } catch (NameNotFoundException e) {
                return false;
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
        final long id = Binder.clearCallingIdentity();
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
            Binder.restoreCallingIdentity(id);
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
        final long id = Binder.clearCallingIdentity();
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
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override
    public boolean installKeyPair(ComponentName who, byte[] privKey, byte[] cert, String alias) {
        if (who == null) {
            if (!isCallerDelegatedCertInstaller()) {
                throw new SecurityException("who == null, but caller is not cert installer");
            }
        } else {
            synchronized (this) {
                getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            }
        }
        final UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        final long id = Binder.clearCallingIdentity();
        try {
          final KeyChainConnection keyChainConnection = KeyChain.bindAsUser(mContext, userHandle);
          try {
              IKeyChainService keyChain = keyChainConnection.getService();
              return keyChain.installKeyPair(privKey, cert, alias);
          } catch (RemoteException e) {
              Log.e(LOG_TAG, "Installing certificate", e);
          } finally {
              keyChainConnection.close();
          }
        } catch (InterruptedException e) {
            Log.w(LOG_TAG, "Interrupted while installing certificate", e);
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
        if (UserHandle.getAppId(Binder.getCallingUid()) != Process.SYSTEM_UID) {
            return;
        }

        final UserHandle caller = Binder.getCallingUserHandle();
        // If there is a profile owner, redirect to that; otherwise query the device owner.
        ComponentName aliasChooser = getProfileOwner(caller.getIdentifier());
        if (aliasChooser == null && caller.isOwner()) {
            ActiveAdmin deviceOwnerAdmin = getDeviceOwnerAdmin();
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

        final long id = Binder.clearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(intent, caller, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String chosenAlias = getResultData();
                    sendPrivateKeyAliasResponse(chosenAlias, response);
                }
            }, null, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(id);
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
    public void wipeData(int flags, final int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceCrossUserPermission(userHandle);
        synchronized (this) {
            // This API can only be called by an active device admin,
            // so try to retrieve it to check that the caller is one.
            final ActiveAdmin admin = getActiveAdminForCallerLocked(null,
                    DeviceAdminInfo.USES_POLICY_WIPE_DATA);

            final String source;
            final ComponentName cname = admin.info.getComponent();
            if (cname != null) {
                source = cname.flattenToShortString();
            } else {
                source = admin.info.getPackageName();
            }

            long ident = Binder.clearCallingIdentity();
            try {
                if ((flags & WIPE_RESET_PROTECTION_DATA) != 0) {
                    boolean ownsInitialization = isDeviceInitializer(admin.info.getPackageName())
                            && !hasUserSetupCompleted(userHandle);
                    if (userHandle != UserHandle.USER_OWNER
                            || !(isDeviceOwner(admin.info.getPackageName())
                                    || ownsInitialization)) {
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
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void wipeDeviceOrUserLocked(boolean wipeExtRequested, final int userHandle, String reason) {
        if (userHandle == UserHandle.USER_OWNER) {
            wipeDataLocked(wipeExtRequested, reason);
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        IActivityManager am = ActivityManagerNative.getDefault();
                        if (am.getCurrentUser().id == userHandle) {
                            am.switchUser(UserHandle.USER_OWNER);
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
        getNotificationManager().notify(PROFILE_WIPED_NOTIFICATION_ID, notification);
    }

    private void clearWipeProfileNotification() {
        getNotificationManager().cancel(PROFILE_WIPED_NOTIFICATION_ID);
    }

    @Override
    public void getRemoveWarning(ComponentName comp, final RemoteCallback result, int userHandle) {
        if (!mHasFeature) {
            return;
        }
        enforceCrossUserPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        synchronized (this) {
            ActiveAdmin admin = getActiveAdminUncheckedLocked(comp, userHandle);
            if (admin == null) {
                try {
                    result.sendResult(null);
                } catch (RemoteException e) {
                }
                return;
            }
            Intent intent = new Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_DISABLE_REQUESTED);
            intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.setComponent(admin.info.getComponent());
            mContext.sendOrderedBroadcastAsUser(intent, new UserHandle(userHandle),
                    null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        result.sendResult(getResultExtras(false));
                    } catch (RemoteException e) {
                    }
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
        enforceCrossUserPermission(userHandle);
        enforceNotManagedProfile(userHandle, "set the active password");

        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);
        DevicePolicyData p = getUserData(userHandle);

        validateQualityConstant(quality);

        synchronized (this) {
            if (p.mActivePasswordQuality != quality || p.mActivePasswordLength != length
                    || p.mFailedPasswordAttempts != 0 || p.mActivePasswordLetters != letters
                    || p.mActivePasswordUpperCase != uppercase
                    || p.mActivePasswordLowerCase != lowercase
                    || p.mActivePasswordNumeric != numbers
                    || p.mActivePasswordSymbols != symbols
                    || p.mActivePasswordNonLetter != nonletter) {
                long ident = Binder.clearCallingIdentity();
                try {
                    p.mActivePasswordQuality = quality;
                    p.mActivePasswordLength = length;
                    p.mActivePasswordLetters = letters;
                    p.mActivePasswordLowerCase = lowercase;
                    p.mActivePasswordUpperCase = uppercase;
                    p.mActivePasswordNumeric = numbers;
                    p.mActivePasswordSymbols = symbols;
                    p.mActivePasswordNonLetter = nonletter;
                    p.mFailedPasswordAttempts = 0;
                    saveSettingsLocked(userHandle);
                    updatePasswordExpirationsLocked(userHandle);
                    setExpirationAlarmCheckLocked(mContext, p);
                    sendAdminCommandToSelfAndProfilesLocked(
                            DeviceAdminReceiver.ACTION_PASSWORD_CHANGED,
                            DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD, userHandle);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    /**
     * Called any time the device password is updated. Resets all password expiration clocks.
     */
    private void updatePasswordExpirationsLocked(int userHandle) {
            List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            for (UserInfo userInfo : profiles) {
                int profileId = userInfo.id;
                DevicePolicyData policy = getUserDataUnchecked(profileId);
                final int N = policy.mAdminList.size();
                if (N > 0) {
                    for (int i=0; i<N; i++) {
                        ActiveAdmin admin = policy.mAdminList.get(i);
                        if (admin.info.usesPolicy(DeviceAdminInfo.USES_POLICY_EXPIRE_PASSWORD)) {
                            long timeout = admin.passwordExpirationTimeout;
                            long expiration = timeout > 0L ? (timeout + System.currentTimeMillis()) : 0L;
                            admin.passwordExpirationDate = expiration;
                        }
                    }
                }
                saveSettingsLocked(profileId);
            }
    }

    @Override
    public void reportFailedPasswordAttempt(int userHandle) {
        enforceCrossUserPermission(userHandle);
        enforceNotManagedProfile(userHandle, "report failed password attempt");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        long ident = Binder.clearCallingIdentity();
        try {
            boolean wipeData = false;
            int identifier = 0;
            synchronized (this) {
                DevicePolicyData policy = getUserData(userHandle);
                policy.mFailedPasswordAttempts++;
                saveSettingsLocked(userHandle);
                if (mHasFeature) {
                    ActiveAdmin strictestAdmin =
                            getAdminWithMinimumFailedPasswordsForWipeLocked(userHandle);
                    int max = strictestAdmin != null
                            ? strictestAdmin.maximumFailedPasswordsForWipe : 0;
                    if (max > 0 && policy.mFailedPasswordAttempts >= max) {
                        // Wipe the user/profile associated with the policy that was violated. This
                        // is not necessarily calling user: if the policy that fired was from a
                        // managed profile rather than the main user profile, we wipe former only.
                        wipeData = true;
                        identifier = strictestAdmin.getUserHandle().getIdentifier();
                    }
                    sendAdminCommandToSelfAndProfilesLocked(
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
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void reportSuccessfulPasswordAttempt(int userHandle) {
        enforceCrossUserPermission(userHandle);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.BIND_DEVICE_ADMIN, null);

        synchronized (this) {
            DevicePolicyData policy = getUserData(userHandle);
            if (policy.mFailedPasswordAttempts != 0 || policy.mPasswordOwner >= 0) {
                long ident = Binder.clearCallingIdentity();
                try {
                    policy.mFailedPasswordAttempts = 0;
                    policy.mPasswordOwner = -1;
                    saveSettingsLocked(userHandle);
                    if (mHasFeature) {
                        sendAdminCommandToSelfAndProfilesLocked(
                                DeviceAdminReceiver.ACTION_PASSWORD_SUCCEEDED,
                                DeviceAdminInfo.USES_POLICY_WATCH_LOGIN, userHandle);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
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

            // Only check if owner has set global proxy. We don't allow other users to set it.
            DevicePolicyData policy = getUserData(UserHandle.USER_OWNER);
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

            // If the user is not the owner, don't set the global proxy. Fail silently.
            if (UserHandle.getCallingUserId() != UserHandle.USER_OWNER) {
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
            long origId = Binder.clearCallingIdentity();
            try {
                resetGlobalProxyLocked(policy);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            return null;
        }
    }

    @Override
    public ComponentName getGlobalProxyAdmin(int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        enforceCrossUserPermission(userHandle);
        synchronized(this) {
            DevicePolicyData policy = getUserData(UserHandle.USER_OWNER);
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
        long token = Binder.clearCallingIdentity();
        try {
            ConnectivityManager connectivityManager = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.setGlobalProxy(proxyInfo);
        } finally {
            Binder.restoreCallingIdentity(token);
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
        ContentResolver res = mContext.getContentResolver();

        ProxyInfo proxyProperties = new ProxyInfo(data[0], proxyPort, exclusionList);
        if (!proxyProperties.isValid()) {
            Slog.e(LOG_TAG, "Invalid proxy properties, ignoring: " + proxyProperties.toString());
            return;
        }
        Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST, data[0]);
        Settings.Global.putInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, proxyPort);
        Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
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
            // Only owner can set storage encryption
            if (userHandle != UserHandle.USER_OWNER
                    || UserHandle.getCallingUserId() != UserHandle.USER_OWNER) {
                Slog.w(LOG_TAG, "Only owner is allowed to set storage encryption. User "
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

            DevicePolicyData policy = getUserData(UserHandle.USER_OWNER);
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
        enforceCrossUserPermission(userHandle);
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
    public int getStorageEncryptionStatus(int userHandle) {
        if (!mHasFeature) {
            // Ok to return current status.
        }
        enforceCrossUserPermission(userHandle);
        return getEncryptionStatus();
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
     * {@link DevicePolicyManager#ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY}, or
     * {@link DevicePolicyManager#ENCRYPTION_STATUS_ACTIVE}.
     */
    private int getEncryptionStatus() {
        String status = SystemProperties.get("ro.crypto.state", "unsupported");
        if ("encrypted".equalsIgnoreCase(status)) {
            final long token = Binder.clearCallingIdentity();
            try {
                return LockPatternUtils.isDeviceEncrypted()
                        ? DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE
                        : DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } else if ("unencrypted".equalsIgnoreCase(status)) {
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

    private void updateScreenCaptureDisabledInWindowManager(int userHandle, boolean disabled) {
        long ident = Binder.clearCallingIdentity();
        try {
            getWindowManager().setScreenCaptureDisabled(userHandle, disabled);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Unable to notify WindowManager.", e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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
            long ident = Binder.clearCallingIdentity();
            try {
                Settings.Global.putInt(mContext.getContentResolver(),
                        Settings.Global.AUTO_TIME, 1 /* AUTO_TIME on */);
            } finally {
                Binder.restoreCallingIdentity(ident);
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
            ActiveAdmin deviceOwner = getDeviceOwnerAdmin();
            return (deviceOwner != null) ? deviceOwner.requireAutoTime : false;
        }
    }

    /**
     * The system property used to share the state of the camera. The native camera service
     * is expected to read this property and act accordingly. The userId should be appended
     * to this key.
     */
    public static final String SYSTEM_PROP_DISABLE_CAMERA_PREFIX = "sys.secpolicy.camera.off_";

    /**
     * Disables all device cameras according to the specified admin.
     */
    @Override
    public void setCameraDisabled(ComponentName who, boolean disabled) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_DISABLE_CAMERA);
            if (ap.disableCamera != disabled) {
                ap.disableCamera = disabled;
                saveSettingsLocked(userHandle);
            }
            syncDeviceCapabilitiesLocked(getUserData(userHandle));
        }
    }

    /**
     * Gets whether or not all device cameras are disabled for a given admin, or disabled for any
     * active admins.
     */
    @Override
    public boolean getCameraDisabled(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (this) {
            if (who != null) {
                ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                return (admin != null) ? admin.disableCamera : false;
            }

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

    /**
     * Selectively disable keyguard features.
     */
    @Override
    public void setKeyguardDisabledFeatures(ComponentName who, int which) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        if (isManagedProfile(userHandle)) {
            which = which & PROFILE_KEYGUARD_FEATURES;
        }
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(who,
                    DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES);
            if (ap.disabledKeyguardFeatures != which) {
                ap.disabledKeyguardFeatures = which;
                saveSettingsLocked(userHandle);
            }
            syncDeviceCapabilitiesLocked(getUserData(userHandle));
        }
    }

    /**
     * Gets the disabled state for features in keyguard for the given admin,
     * or the aggregate of all active admins if who is null.
     */
    @Override
    public int getKeyguardDisabledFeatures(ComponentName who, int userHandle) {
        if (!mHasFeature) {
            return 0;
        }
        enforceCrossUserPermission(userHandle);
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                if (who != null) {
                    ActiveAdmin admin = getActiveAdminUncheckedLocked(who, userHandle);
                    return (admin != null) ? admin.disabledKeyguardFeatures : 0;
                }

                UserInfo user = mUserManager.getUserInfo(userHandle);
                final List<UserInfo> profiles;
                if (user.isManagedProfile()) {
                    // If we are being asked about a managed profile just return
                    // keyguard features disabled by admins in the profile.
                    profiles = new ArrayList<UserInfo>(1);
                    profiles.add(user);
                } else {
                    // Otherwise return those set by admins in the user
                    // and its profiles.
                    profiles = mUserManager.getProfiles(userHandle);
                }

                // Determine which keyguard features are disabled by any active admin.
                int which = 0;
                for (UserInfo userInfo : profiles) {
                    DevicePolicyData policy = getUserData(userInfo.id);
                    final int N = policy.mAdminList.size();
                    for (int i = 0; i < N; i++) {
                        ActiveAdmin admin = policy.mAdminList.get(i);
                        if (userInfo.id == userHandle || !userInfo.isManagedProfile()) {
                            // If we are being asked explictly about this user
                            // return all disabled features even if its a managed profile.
                            which |= admin.disabledKeyguardFeatures;
                        } else {
                            // Otherwise a managed profile is only allowed to disable
                            // some features on the parent user.
                            which |= (admin.disabledKeyguardFeatures
                                    & PROFILE_KEYGUARD_FEATURES_AFFECT_OWNER);
                        }
                    }
                }
                return which;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean setDeviceOwner(String packageName, String ownerName) {
        if (!mHasFeature) {
            return false;
        }
        if (packageName == null
                || !DeviceOwner.isInstalled(packageName, mContext.getPackageManager())) {
            throw new IllegalArgumentException("Invalid package name " + packageName
                    + " for device owner");
        }
        synchronized (this) {
            enforceCanSetDeviceOwner();

            // Shutting down backup manager service permanently.
            long ident = Binder.clearCallingIdentity();
            try {
                IBackupManager ibm = IBackupManager.Stub.asInterface(
                        ServiceManager.getService(Context.BACKUP_SERVICE));
                ibm.setBackupServiceActive(UserHandle.USER_OWNER, false);
            } catch (RemoteException e) {
                throw new IllegalStateException("Failed deactivating backup service.", e);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }

            if (mDeviceOwner == null) {
                // Device owner is not set and does not exist, set it.
                mDeviceOwner = DeviceOwner.createWithDeviceOwner(packageName, ownerName);
            } else {
                // Device owner state already exists, update it.
                mDeviceOwner.setDeviceOwner(packageName, ownerName);
            }
            mDeviceOwner.writeOwnerFile();
            updateDeviceOwnerLocked();
            Intent intent = new Intent(DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED);

            ident = Binder.clearCallingIdentity();
            try {
                mContext.sendBroadcastAsUser(intent, UserHandle.OWNER);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            return true;
        }
    }

    @Override
    public boolean isDeviceOwner(String packageName) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (this) {
            return mDeviceOwner != null
                    && mDeviceOwner.hasDeviceOwner()
                    && mDeviceOwner.getDeviceOwnerPackageName().equals(packageName);
        }
    }

    @Override
    public String getDeviceOwner() {
        if (!mHasFeature) {
            return null;
        }
        synchronized (this) {
            if (mDeviceOwner != null && mDeviceOwner.hasDeviceOwner()) {
                return mDeviceOwner.getDeviceOwnerPackageName();
            }
        }
        return null;
    }

    @Override
    public String getDeviceOwnerName() {
        if (!mHasFeature) {
            return null;
        }
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
        synchronized (this) {
            if (mDeviceOwner == null || !mDeviceOwner.hasDeviceOwner()) {
                return null;
            }
            String deviceOwnerPackage = mDeviceOwner.getDeviceOwnerPackageName();
            return getApplicationLabel(deviceOwnerPackage, UserHandle.USER_OWNER);
        }
    }

    // Returns the active device owner or null if there is no device owner.
    private ActiveAdmin getDeviceOwnerAdmin() {
        String deviceOwnerPackageName = getDeviceOwner();
        if (deviceOwnerPackageName == null) {
            return null;
        }

        DevicePolicyData policy = getUserData(UserHandle.USER_OWNER);
        final int n = policy.mAdminList.size();
        for (int i = 0; i < n; i++) {
            ActiveAdmin admin = policy.mAdminList.get(i);
            if (deviceOwnerPackageName.equals(admin.info.getPackageName())) {
                return admin;
            }
        }
        return null;
    }

    @Override
    public void clearDeviceOwner(String packageName) {
        Preconditions.checkNotNull(packageName, "packageName is null");
        try {
            int uid = mContext.getPackageManager().getPackageUid(packageName, 0);
            if (uid != Binder.getCallingUid()) {
                throw new SecurityException("Invalid packageName");
            }
        } catch (NameNotFoundException e) {
            throw new SecurityException(e);
        }
        if (!isDeviceOwner(packageName)) {
            throw new SecurityException("clearDeviceOwner can only be called by the device owner");
        }
        synchronized (this) {
            clearUserPoliciesLocked(new UserHandle(UserHandle.USER_OWNER));
            if (mDeviceOwner != null) {
                mDeviceOwner.clearDeviceOwner();
                mDeviceOwner.writeOwnerFile();
                updateDeviceOwnerLocked();
            }
        }
    }

    @Override
    public boolean setDeviceInitializer(ComponentName who, ComponentName initializer) {
        if (!mHasFeature) {
            return false;
        }
        if (initializer == null || !DeviceOwner.isInstalled(
                initializer.getPackageName(), mContext.getPackageManager())) {
            throw new IllegalArgumentException("Invalid component name " + initializer
                    + " for device initializer");
        }
        boolean isInitializerSystemApp;
        try {
            isInitializerSystemApp = isSystemApp(AppGlobals.getPackageManager(),
                    initializer.getPackageName(), Binder.getCallingUserHandle().getIdentifier());
        } catch (RemoteException | IllegalArgumentException e) {
            isInitializerSystemApp = false;
            Slog.e(LOG_TAG, "Fail to check if device initialzer is system app.", e);
        }
        if (!isInitializerSystemApp) {
            throw new IllegalArgumentException("Only system app can be set as device initializer.");
        }
        synchronized (this) {
            enforceCanSetDeviceInitializer(who);

            if (mDeviceOwner != null && mDeviceOwner.hasDeviceInitializer()) {
                throw new IllegalStateException(
                        "Trying to set device initializer but device initializer is already set.");
            }

            if (mDeviceOwner == null) {
                // Device owner state does not exist, create it.
                mDeviceOwner = DeviceOwner.createWithDeviceInitializer(initializer);
            } else {
                // Device owner already exists, update it.
                mDeviceOwner.setDeviceInitializer(initializer);
            }

            addDeviceInitializerToLockTaskPackagesLocked(UserHandle.USER_OWNER);
            mDeviceOwner.writeOwnerFile();
            return true;
        }
    }

    private void enforceCanSetDeviceInitializer(ComponentName who) {
        if (who == null) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.MANAGE_DEVICE_ADMINS, null);
            if (hasUserSetupCompleted(UserHandle.USER_OWNER)) {
                throw new IllegalStateException(
                        "Trying to set device initializer but device is already provisioned.");
            }
        } else {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
        }
    }

    @Override
    public boolean isDeviceInitializer(String packageName) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (this) {
            return mDeviceOwner != null
                    && mDeviceOwner.hasDeviceInitializer()
                    && mDeviceOwner.getDeviceInitializerPackageName().equals(packageName);
        }
    }

    @Override
    public String getDeviceInitializer() {
        if (!mHasFeature) {
            return null;
        }
        synchronized (this) {
            if (mDeviceOwner != null && mDeviceOwner.hasDeviceInitializer()) {
                return mDeviceOwner.getDeviceInitializerPackageName();
            }
        }
        return null;
    }

    @Override
    public ComponentName getDeviceInitializerComponent() {
        if (!mHasFeature) {
            return null;
        }
        synchronized (this) {
            if (mDeviceOwner != null && mDeviceOwner.hasDeviceInitializer()) {
                return mDeviceOwner.getDeviceInitializerComponent();
            }
        }
        return null;
    }

    @Override
    public void clearDeviceInitializer(ComponentName who) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");

        ActiveAdmin admin = getActiveAdminUncheckedLocked(who, UserHandle.getCallingUserId());

        if (admin.getUid() != Binder.getCallingUid()) {
            throw new SecurityException("Admin " + who + " is not owned by uid "
                    + Binder.getCallingUid());
        }

        if (!isDeviceInitializer(admin.info.getPackageName())
                && !isDeviceOwner(admin.info.getPackageName())) {
            throw new SecurityException(
                    "clearDeviceInitializer can only be called by the device initializer/owner");
        }
        synchronized (this) {
            long ident = Binder.clearCallingIdentity();
            try {
                if (mDeviceOwner != null) {
                    mDeviceOwner.clearDeviceInitializer();
                    mDeviceOwner.writeOwnerFile();
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public boolean setProfileOwner(ComponentName who, String ownerName, int userHandle) {
        if (!mHasFeature) {
            return false;
        }
        if (who == null
                || !DeviceOwner.isInstalledForUser(who.getPackageName(), userHandle)) {
            throw new IllegalArgumentException("Component " + who
                    + " not installed for userId:" + userHandle);
        }
        synchronized (this) {
            enforceCanSetProfileOwner(userHandle);
            if (mDeviceOwner == null) {
                // Device owner state does not exist, create it.
                mDeviceOwner = DeviceOwner.createWithProfileOwner(who, ownerName,
                        userHandle);
            } else {
                // Device owner state already exists, update it.
                mDeviceOwner.setProfileOwner(who, ownerName, userHandle);
            }
            mDeviceOwner.writeOwnerFile();
            return true;
        }
    }

    @Override
    public void clearProfileOwner(ComponentName who) {
        if (!mHasFeature) {
            return;
        }
        UserHandle callingUser = Binder.getCallingUserHandle();
        // Check if this is the profile owner who is calling
        getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
        synchronized (this) {
            clearUserPoliciesLocked(callingUser);
            if (mDeviceOwner != null) {
                mDeviceOwner.removeProfileOwner(callingUser.getIdentifier());
                mDeviceOwner.writeOwnerFile();
            }
        }
    }

    private void clearUserPoliciesLocked(UserHandle userHandle) {
        int userId = userHandle.getIdentifier();
        // Reset some of the user-specific policies
        DevicePolicyData policy = getUserData(userId);
        policy.mPermissionPolicy = DevicePolicyManager.PERMISSION_POLICY_PROMPT;
        policy.mDelegatedCertInstallerPackage = null;
        policy.mStatusBarDisabled = false;
        saveSettingsLocked(userId);

        final long ident = Binder.clearCallingIdentity();
        try {
            clearUserRestrictions(userHandle);
            AppGlobals.getPackageManager().updatePermissionFlagsForAllApps(
                    PackageManager.FLAG_PERMISSION_POLICY_FIXED,
                    0  /* flagValues */, userHandle.getIdentifier());
        } catch (RemoteException re) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    private void clearUserRestrictions(UserHandle userHandle) {
        Bundle userRestrictions = mUserManager.getUserRestrictions();
        mUserManager.setUserRestrictions(new Bundle(), userHandle);
        IAudioService iAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        if (userRestrictions.getBoolean(UserManager.DISALLOW_ADJUST_VOLUME)) {
            try {
                iAudioService.setMasterMute(true, 0, mContext.getPackageName(),
                        userHandle.getIdentifier());
            } catch (RemoteException e) {
                // Not much we can do here.
            }
        }
        if (userRestrictions.getBoolean(UserManager.DISALLOW_UNMUTE_MICROPHONE)) {
            try {
                iAudioService.setMicrophoneMute(true, mContext.getPackageName(),
                        userHandle.getIdentifier());
            } catch (RemoteException e) {
                // Not much we can do here.
            }
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
        DevicePolicyData policy = getUserData(userHandle);
        // If policy is null, return true, else check if the setup has completed.
        return policy == null || policy.mUserSetupComplete;
    }

    @Override
    public boolean setUserEnabled(ComponentName who) {
        if (!mHasFeature) {
            return false;
        }
        synchronized (this) {
            if (who == null) {
                throw new NullPointerException("ComponentName is null");
            }
            int userId = UserHandle.getCallingUserId();

            ActiveAdmin activeAdmin =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            if (!isDeviceInitializer(activeAdmin.info.getPackageName())) {
                throw new SecurityException(
                        "This method can only be called by device initializers");
            }

            long id = Binder.clearCallingIdentity();
            try {
                if (!isDeviceOwner(activeAdmin.info.getPackageName())) {
                    IPackageManager ipm = AppGlobals.getPackageManager();
                    ipm.setComponentEnabledSetting(who,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP, userId);

                    removeActiveAdmin(who, userId);
                }

                if (userId == UserHandle.USER_OWNER) {
                    Settings.Global.putInt(mContext.getContentResolver(),
                            Settings.Global.DEVICE_PROVISIONED, 1);
                }
                Settings.Secure.putIntForUser(mContext.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 1, userId);
            } catch (RemoteException e) {
                Log.i(LOG_TAG, "Can't talk to package manager", e);
                return false;
            } finally {
                restoreCallingIdentity(id);
            }
            return true;
        }
    }

    @Override
    public void setProfileEnabled(ComponentName who) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            // Check if this is the profile owner who is calling
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            int userId = UserHandle.getCallingUserId();

            long id = Binder.clearCallingIdentity();
            try {
                mUserManager.setUserEnabled(userId);
                Intent intent = new Intent(Intent.ACTION_MANAGED_PROFILE_ADDED);
                intent.putExtra(Intent.EXTRA_USER, new UserHandle(userHandle));
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY |
                        Intent.FLAG_RECEIVER_FOREGROUND);
                // TODO This should send to parent of profile (which is always owner at the moment).
                mContext.sendBroadcastAsUser(intent, UserHandle.OWNER);
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setProfileName(ComponentName who, String profileName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int userId = UserHandle.getCallingUserId();
        // Check if this is the profile owner (includes device owner).
        getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

        long id = Binder.clearCallingIdentity();
        try {
            mUserManager.setUserName(userId, profileName);
        } finally {
            restoreCallingIdentity(id);
        }
    }

    @Override
    public ComponentName getProfileOwner(int userHandle) {
        if (!mHasFeature) {
            return null;
        }

        synchronized (this) {
            if (mDeviceOwner != null) {
                return mDeviceOwner.getProfileOwnerComponent(userHandle);
            }
        }
        return null;
    }

    // Returns the active profile owner for this user or null if the current user has no
    // profile owner.
    private ActiveAdmin getProfileOwnerAdmin(int userHandle) {
        ComponentName profileOwner =
                mDeviceOwner != null ? mDeviceOwner.getProfileOwnerComponent(userHandle) : null;
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
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_USERS, null);
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
        long token = Binder.clearCallingIdentity();
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
            Binder.restoreCallingIdentity(token);
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
    private void enforceCanSetProfileOwner(int userHandle) {
        UserInfo info = mUserManager.getUserInfo(userHandle);
        if (info == null) {
            // User doesn't exist.
            throw new IllegalArgumentException(
                    "Attempted to set profile owner for invalid userId: " + userHandle);
        }
        if (info.isGuest()) {
            throw new IllegalStateException("Cannot set a profile owner on a guest");
        }
        if (getProfileOwner(userHandle) != null) {
            throw new IllegalStateException("Trying to set the profile owner, but profile owner "
                    + "is already set.");
        }
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            if (hasUserSetupCompleted(userHandle) &&
                    AccountManager.get(mContext).getAccountsAsUser(userHandle).length > 0) {
                throw new IllegalStateException("Not allowed to set the profile owner because "
                        + "there are already some accounts on the profile");
            }
            return;
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS, null);
        if (hasUserSetupCompleted(userHandle)
                && UserHandle.getAppId(callingUid) != Process.SYSTEM_UID) {
            throw new IllegalStateException("Cannot set the profile owner on a user which is "
                    + "already set-up");
        }
    }

    /**
     * The Device owner can only be set by adb or an app with the MANAGE_PROFILE_AND_DEVICE_OWNERS
     * permission.
     * The device owner can only be set before the setup phase of the primary user has completed,
     * except for adb if no accounts or additional users are present on the device.
     */
    private void enforceCanSetDeviceOwner() {
        if (mDeviceOwner != null && mDeviceOwner.hasDeviceOwner()) {
            throw new IllegalStateException("Trying to set the device owner, but device owner "
                    + "is already set.");
        }
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID) {
            if (!hasUserSetupCompleted(UserHandle.USER_OWNER)) {
                return;
            }
            if (mUserManager.getUserCount() > 1) {
                throw new IllegalStateException("Not allowed to set the device owner because there "
                        + "are already several users on the device");
            }
            if (AccountManager.get(mContext).getAccounts().length > 0) {
                throw new IllegalStateException("Not allowed to set the device owner because there "
                        + "are already some accounts on the device");
            }
            return;
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS, null);
        if (hasUserSetupCompleted(UserHandle.USER_OWNER)) {
            throw new IllegalStateException("Cannot set the device owner if the device is "
                    + "already set-up");
        }
    }

    private void enforceCrossUserPermission(int userHandle) {
        if (userHandle < 0) {
            throw new IllegalArgumentException("Invalid userId " + userHandle);
        }
        final int callingUid = Binder.getCallingUid();
        if (userHandle == UserHandle.getUserId(callingUid)) return;
        if (callingUid != Process.SYSTEM_UID && callingUid != 0) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL, "Must be system or have"
                    + " INTERACT_ACROSS_USERS_FULL permission");
        }
    }

    private void enforceSystemProcess(String message) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException(message);
        }
    }

    private void enforceNotManagedProfile(int userHandle, String message) {
        if(isManagedProfile(userHandle)) {
            throw new SecurityException("You can not " + message + " for a managed profile. ");
        }
    }

    private UserInfo getProfileParent(int userHandle) {
        long ident = Binder.clearCallingIdentity();
        try {
            return mUserManager.getProfileParent(userHandle);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private boolean isManagedProfile(int userHandle) {
        long ident = Binder.clearCallingIdentity();
        try {
            return mUserManager.getUserInfo(userHandle).isManagedProfile();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void enableIfNecessary(String packageName, int userId) {
        try {
            IPackageManager ipm = AppGlobals.getPackageManager();
            ApplicationInfo ai = ipm.getApplicationInfo(packageName,
                    PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                    userId);
            if (ai.enabledSetting
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                ipm.setApplicationEnabledSetting(packageName,
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
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        final Printer p = new PrintWriterPrinter(pw);

        synchronized (this) {
            p.println("Current Device Policy Manager state:");
            if (mDeviceOwner != null) {
                mDeviceOwner.dump("  ", pw);
            }
            int userCount = mUserData.size();
            for (int u = 0; u < userCount; u++) {
                DevicePolicyData policy = getUserData(mUserData.keyAt(u));
                p.println("  Enabled Device Admins (User " + policy.mUserHandle + "):");
                final int N = policy.mAdminList.size();
                for (int i=0; i<N; i++) {
                    ActiveAdmin ap = policy.mAdminList.get(i);
                    if (ap != null) {
                        pw.print("  "); pw.print(ap.info.getComponent().flattenToShortString());
                                pw.println(":");
                        ap.dump("    ", pw);
                    }
                }
                if (!policy.mRemovingAdmins.isEmpty()) {
                    p.println("  Removing Device Admins (User " + policy.mUserHandle + "): "
                            + policy.mRemovingAdmins);
                }

                pw.println(" ");
                pw.print("  mPasswordOwner="); pw.println(policy.mPasswordOwner);
            }
        }
    }

    @Override
    public void addPersistentPreferredActivity(ComponentName who, IntentFilter filter,
            ComponentName activity) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            IPackageManager pm = AppGlobals.getPackageManager();
            long id = Binder.clearCallingIdentity();
            try {
                pm.addPersistentPreferredActivity(filter, activity, userHandle);
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void clearPackagePersistentPreferredActivities(ComponentName who, String packageName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final int userHandle = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            IPackageManager pm = AppGlobals.getPackageManager();
            long id = Binder.clearCallingIdentity();
            try {
                pm.clearPackagePersistentPreferredActivities(packageName, userHandle);
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setApplicationRestrictions(ComponentName who, String packageName, Bundle settings) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = Binder.clearCallingIdentity();
            try {
                mUserManager.setApplicationRestrictions(packageName, settings, userHandle);
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setTrustAgentConfiguration(ComponentName admin, ComponentName agent,
            PersistableBundle args) {
        if (!mHasFeature) {
            return;
        }
        Preconditions.checkNotNull(admin, "admin is null");
        Preconditions.checkNotNull(agent, "agent is null");
        final int userHandle = UserHandle.getCallingUserId();
        enforceNotManagedProfile(userHandle, "set trust agent configuration");
        synchronized (this) {
            ActiveAdmin ap = getActiveAdminForCallerLocked(admin,
                    DeviceAdminInfo.USES_POLICY_DISABLE_KEYGUARD_FEATURES);
            ap.trustAgentInfos.put(agent.flattenToString(), new TrustAgentInfo(args));
            saveSettingsLocked(userHandle);
            syncDeviceCapabilitiesLocked(getUserData(userHandle));
        }
    }

    @Override
    public List<PersistableBundle> getTrustAgentConfiguration(ComponentName admin,
            ComponentName agent, int userHandle) {
        if (!mHasFeature) {
            return null;
        }
        Preconditions.checkNotNull(agent, "agent null");
        enforceCrossUserPermission(userHandle);

        synchronized (this) {
            final String componentName = agent.flattenToString();
            if (admin != null) {
                final ActiveAdmin ap = getActiveAdminUncheckedLocked(admin, userHandle);
                if (ap == null) return null;
                TrustAgentInfo trustAgentInfo = ap.trustAgentInfos.get(componentName);
                if (trustAgentInfo == null || trustAgentInfo.options == null) return null;
                List<PersistableBundle> result = new ArrayList<PersistableBundle>();
                result.add(trustAgentInfo.options);
                return result;
            }

            // Return strictest policy for this user and profiles that are visible from this user.
            final List<UserInfo> profiles = mUserManager.getProfiles(userHandle);
            List<PersistableBundle> result = null;

            // Search through all admins that use KEYGUARD_DISABLE_TRUST_AGENTS and keep track
            // of the options. If any admin doesn't have options, discard options for the rest
            // and return null.
            boolean allAdminsHaveOptions = true;
            for (UserInfo userInfo : profiles) {
                DevicePolicyData policy = getUserDataUnchecked(userInfo.id);
                final int N = policy.mAdminList.size();
                for (int i=0; i < N; i++) {
                    final ActiveAdmin active = policy.mAdminList.get(i);
                    final boolean disablesTrust = (active.disabledKeyguardFeatures
                            & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0;
                    final TrustAgentInfo info = active.trustAgentInfos.get(componentName);
                    if (info != null && info.options != null && !info.options.isEmpty()) {
                        if (disablesTrust) {
                            if (result == null) {
                                result = new ArrayList<PersistableBundle>();
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
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
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

            IPackageManager pm = AppGlobals.getPackageManager();
            long id = Binder.clearCallingIdentity();
            try {
                if ((flags & DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED) != 0) {
                    pm.addCrossProfileIntentFilter(filter, who.getPackageName(), callingUserId,
                            UserHandle.USER_OWNER, 0);
                }
                if ((flags & DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT) != 0) {
                    pm.addCrossProfileIntentFilter(filter, who.getPackageName(),
                            UserHandle.USER_OWNER, callingUserId, 0);
                }
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void clearCrossProfileIntentFilters(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            IPackageManager pm = AppGlobals.getPackageManager();
            long id = Binder.clearCallingIdentity();
            try {
                // Removing those that go from the managed profile to the primary user.
                pm.clearCrossProfileIntentFilters(callingUserId, who.getPackageName());
                // And those that go from the primary user to the managed profile.
                // If we want to support multiple managed profiles, we will have to only remove
                // those that have callingUserId as their target.
                pm.clearCrossProfileIntentFilters(UserHandle.USER_OWNER, who.getPackageName());
            } catch (RemoteException re) {
                // Shouldn't happen
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    /**
     * @return true if all packages in enabledPackages are either in the list
     * permittedList or are a system app.
     */
    private boolean checkPackagesInPermittedListOrSystem(List<String> enabledPackages,
            List<String> permittedList) {
        int userIdToCheck = UserHandle.getCallingUserId();
        long id = Binder.clearCallingIdentity();
        try {
            // If we have an enabled packages list for a managed profile the packages
            // we should check are installed for the parent user.
            UserInfo user = mUserManager.getUserInfo(userIdToCheck);
            if (user.isManagedProfile()) {
                userIdToCheck = user.profileGroupId;
            }

            IPackageManager pm = AppGlobals.getPackageManager();
            for (String enabledPackage : enabledPackages) {
                boolean systemService = false;
                try {
                    ApplicationInfo applicationInfo = pm.getApplicationInfo(enabledPackage,
                            PackageManager.GET_UNINSTALLED_PACKAGES, userIdToCheck);
                    systemService = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                } catch (RemoteException e) {
                    Log.i(LOG_TAG, "Can't talk to package managed", e);
                }
                if (!systemService && !permittedList.contains(enabledPackage)) {
                    return false;
                }
            }
        } finally {
            restoreCallingIdentity(id);
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
            long id = Binder.clearCallingIdentity();
            try {
                UserInfo user = mUserManager.getUserInfo(userId);
                if (user.isManagedProfile()) {
                    userId = user.profileGroupId;
                }
                AccessibilityManager accessibilityManager = getAccessibilityManagerForUser(userId);
                enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            } finally {
                restoreCallingIdentity(id);
            }

            if (enabledServices != null) {
                List<String> enabledPackages = new ArrayList<String>();
                for (AccessibilityServiceInfo service : enabledServices) {
                    enabledPackages.add(service.getResolveInfo().serviceInfo.packageName);
                }
                if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList)) {
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
            List<UserInfo> profiles = mUserManager.getProfiles(userId);
            final int PROFILES_SIZE = profiles.size();
            for (int i = 0; i < PROFILES_SIZE; ++i) {
                // Just loop though all admins, only device or profiles
                // owners can have permitted lists set.
                DevicePolicyData policy = getUserDataUnchecked(profiles.get(i).id);
                final int N = policy.mAdminList.size();
                for (int j = 0; j < N; j++) {
                    ActiveAdmin admin = policy.mAdminList.get(j);
                    List<String> fromAdmin = admin.permittedAccessiblityServices;
                    if (fromAdmin != null) {
                        if (result == null) {
                            result = new ArrayList<String>(fromAdmin);
                        } else {
                            result.retainAll(fromAdmin);
                        }
                    }
                }
            }

            // If we have a permitted list add all system accessibility services.
            if (result != null) {
                long id = Binder.clearCallingIdentity();
                try {
                    UserInfo user = mUserManager.getUserInfo(userId);
                    if (user.isManagedProfile()) {
                        userId = user.profileGroupId;
                    }
                    AccessibilityManager accessibilityManager =
                            getAccessibilityManagerForUser(userId);
                    List<AccessibilityServiceInfo> installedServices =
                            accessibilityManager.getInstalledAccessibilityServiceList();

                    IPackageManager pm = AppGlobals.getPackageManager();
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
                    restoreCallingIdentity(id);
                }
            }

            return result;
        }
    }

    private boolean checkCallerIsCurrentUserOrProfile() {
        int callingUserId = UserHandle.getCallingUserId();
        long token = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser;
            UserInfo callingUser = mUserManager.getUserInfo(callingUserId);
            try {
                currentUser = ActivityManagerNative.getDefault().getCurrentUser();
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
            Binder.restoreCallingIdentity(token);
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
            InputMethodManager inputMethodManager = (InputMethodManager) mContext
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            List<InputMethodInfo> enabledImes = inputMethodManager.getEnabledInputMethodList();

            if (enabledImes != null) {
                List<String> enabledPackages = new ArrayList<String>();
                for (InputMethodInfo ime : enabledImes) {
                    enabledPackages.add(ime.getPackageName());
                }
                if (!checkPackagesInPermittedListOrSystem(enabledPackages, packageList)) {
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
            currentUser = ActivityManagerNative.getDefault().getCurrentUser();
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
            List<UserInfo> profiles = mUserManager.getProfiles(userId);
            final int PROFILES_SIZE = profiles.size();
            for (int i = 0; i < PROFILES_SIZE; ++i) {
                // Just loop though all admins, only device or profiles
                // owners can have permitted lists set.
                DevicePolicyData policy = getUserDataUnchecked(profiles.get(i).id);
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
                InputMethodManager inputMethodManager = (InputMethodManager) mContext
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                List<InputMethodInfo> imes = inputMethodManager.getInputMethodList();
                long id = Binder.clearCallingIdentity();
                try {
                    IPackageManager pm = AppGlobals.getPackageManager();
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
                    restoreCallingIdentity(id);
                }
            }
            return result;
        }
    }

    @Override
    public UserHandle createUser(ComponentName who, String name) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            long id = Binder.clearCallingIdentity();
            try {
                UserInfo userInfo = mUserManager.createUser(name, 0 /* flags */);
                if (userInfo != null) {
                    return userInfo.getUserHandle();
                }
                return null;
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public UserHandle createAndInitializeUser(ComponentName who, String name,
            String ownerName, ComponentName profileOwnerComponent, Bundle adminExtras) {
        UserHandle user = createUser(who, name);
        if (user == null) {
            return null;
        }
        long id = Binder.clearCallingIdentity();
        try {
            String profileOwnerPkg = profileOwnerComponent.getPackageName();
            final IPackageManager ipm = AppGlobals.getPackageManager();
            IActivityManager activityManager = ActivityManagerNative.getDefault();

            final int userHandle = user.getIdentifier();
            try {
                // Install the profile owner if not present.
                if (!ipm.isPackageAvailable(profileOwnerPkg, userHandle)) {
                    ipm.installExistingPackageAsUser(profileOwnerPkg, userHandle);
                }

                // Start user in background.
                activityManager.startUserInBackground(userHandle);
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Failed to make remote calls for configureUser", e);
            }

            setActiveAdmin(profileOwnerComponent, true, userHandle, adminExtras);
            setProfileOwner(profileOwnerComponent, ownerName, userHandle);
            return user;
        } finally {
            restoreCallingIdentity(id);
        }
    }

    @Override
    public boolean removeUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            long id = Binder.clearCallingIdentity();
            try {
                return mUserManager.removeUser(userHandle.getIdentifier());
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public boolean switchUser(ComponentName who, UserHandle userHandle) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            long id = Binder.clearCallingIdentity();
            try {
                int userId = UserHandle.USER_OWNER;
                if (userHandle != null) {
                    userId = userHandle.getIdentifier();
                }
                return ActivityManagerNative.getDefault().switchUser(userId);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Couldn't switch user", e);
                return false;
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public Bundle getApplicationRestrictions(ComponentName who, String packageName) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final UserHandle userHandle = new UserHandle(UserHandle.getCallingUserId());

        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = Binder.clearCallingIdentity();
            try {
                Bundle bundle = mUserManager.getApplicationRestrictions(packageName, userHandle);
                // if no restrictions were saved, mUserManager.getApplicationRestrictions
                // returns null, but DPM method should return an empty Bundle as per JavaDoc
                return bundle != null ? bundle : Bundle.EMPTY;
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setUserRestriction(ComponentName who, String key, boolean enabled) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        final UserHandle user = new UserHandle(UserHandle.getCallingUserId());
        final int userHandle = user.getIdentifier();
        synchronized (this) {
            ActiveAdmin activeAdmin =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            boolean isDeviceOwner = isDeviceOwner(activeAdmin.info.getPackageName());
            if (!isDeviceOwner && userHandle != UserHandle.USER_OWNER
                    && DEVICE_OWNER_USER_RESTRICTIONS.contains(key)) {
                throw new SecurityException("Profile owners cannot set user restriction " + key);
            }
            if (IMMUTABLE_USER_RESTRICTIONS.contains(key)) {
                throw new SecurityException("User restriction " + key + " cannot be changed");
            }
            boolean alreadyRestricted = mUserManager.hasUserRestriction(key, user);

            IAudioService iAudioService = null;
            if (UserManager.DISALLOW_UNMUTE_MICROPHONE.equals(key)
                    || UserManager.DISALLOW_ADJUST_VOLUME.equals(key)) {
                iAudioService = IAudioService.Stub.asInterface(
                        ServiceManager.getService(Context.AUDIO_SERVICE));
            }

            long id = Binder.clearCallingIdentity();
            try {
                if (enabled && !alreadyRestricted) {
                    if (UserManager.DISALLOW_UNMUTE_MICROPHONE.equals(key)) {
                        iAudioService.setMicrophoneMute(true, mContext.getPackageName(),
                                userHandle);
                    } else if (UserManager.DISALLOW_ADJUST_VOLUME.equals(key)) {
                        iAudioService.setMasterMute(true, 0, mContext.getPackageName(),
                                userHandle);
                    }
                    if (UserManager.DISALLOW_CONFIG_WIFI.equals(key)) {
                        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 0,
                                userHandle);
                    } else if (UserManager.DISALLOW_SHARE_LOCATION.equals(key)) {
                        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF,
                                userHandle);
                        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "",
                                userHandle);
                    } else if (UserManager.DISALLOW_DEBUGGING_FEATURES.equals(key)) {
                        // Only disable adb if changing for primary user, since it is global
                        if (userHandle == UserHandle.USER_OWNER) {
                            Settings.Global.putStringForUser(mContext.getContentResolver(),
                                    Settings.Global.ADB_ENABLED, "0", userHandle);
                        }
                    } else if (UserManager.ENSURE_VERIFY_APPS.equals(key)) {
                        Settings.Global.putStringForUser(mContext.getContentResolver(),
                                Settings.Global.PACKAGE_VERIFIER_ENABLE, "1",
                                userHandle);
                        Settings.Global.putStringForUser(mContext.getContentResolver(),
                                Settings.Global.PACKAGE_VERIFIER_INCLUDE_ADB, "1",
                                userHandle);
                    } else if (UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES.equals(key)) {
                        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                                Settings.Secure.INSTALL_NON_MARKET_APPS, 0,
                                userHandle);
                    }
                }
                mUserManager.setUserRestriction(key, enabled, user);
                if (enabled != alreadyRestricted) {
                    if (UserManager.DISALLOW_SHARE_LOCATION.equals(key)) {
                        // Send out notifications however as some clients may want to reread the
                        // value which actually changed due to a restriction having been applied.
                        final String property = Settings.Secure.SYS_PROP_SETTING_VERSION;
                        long version = SystemProperties.getLong(property, 0) + 1;
                        SystemProperties.set(property, Long.toString(version));

                        final String name = Settings.Secure.LOCATION_PROVIDERS_ALLOWED;
                        Uri url = Uri.withAppendedPath(Settings.Secure.CONTENT_URI, name);
                        mContext.getContentResolver().notifyChange(url, null, true, userHandle);
                    }
                }
                if (!enabled && alreadyRestricted) {
                    if (UserManager.DISALLOW_UNMUTE_MICROPHONE.equals(key)) {
                        iAudioService.setMicrophoneMute(false, mContext.getPackageName(),
                                userHandle);
                    } else if (UserManager.DISALLOW_ADJUST_VOLUME.equals(key)) {
                        iAudioService.setMasterMute(false, 0, mContext.getPackageName(),
                                userHandle);
                    }
                }
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to talk to AudioService.", re);
            } finally {
                restoreCallingIdentity(id);
            }
            sendChangedNotification(userHandle);
        }
    }

    @Override
    public boolean setApplicationHidden(ComponentName who, String packageName,
            boolean hidden) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            long id = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                return pm.setApplicationHiddenSettingAsUser(packageName, hidden, callingUserId);
            } catch (RemoteException re) {
                // shouldn't happen
                Slog.e(LOG_TAG, "Failed to setApplicationHiddenSetting", re);
            } finally {
                restoreCallingIdentity(id);
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

            long id = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                return pm.getApplicationHiddenSettingAsUser(packageName, callingUserId);
            } catch (RemoteException re) {
                // shouldn't happen
                Slog.e(LOG_TAG, "Failed to getApplicationHiddenSettingAsUser", re);
            } finally {
                restoreCallingIdentity(id);
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
            long id = Binder.clearCallingIdentity();

            try {
                if (DBG) {
                    Slog.v(LOG_TAG, "installing " + packageName + " for "
                            + userId);
                }

                UserManager um = UserManager.get(mContext);
                UserInfo primaryUser = um.getProfileParent(userId);

                // Call did not come from a managed profile
                if (primaryUser == null) {
                    primaryUser = um.getUserInfo(userId);
                }

                IPackageManager pm = AppGlobals.getPackageManager();
                if (!isSystemApp(pm, packageName, primaryUser.id)) {
                    throw new IllegalArgumentException("Only system apps can be enabled this way.");
                }

                // Install the app.
                pm.installExistingPackageAsUser(packageName, userId);

            } catch (RemoteException re) {
                // shouldn't happen
                Slog.wtf(LOG_TAG, "Failed to install " + packageName, re);
            } finally {
                restoreCallingIdentity(id);
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
            long id = Binder.clearCallingIdentity();

            try {
                UserManager um = UserManager.get(mContext);
                UserInfo primaryUser = um.getProfileParent(userId);

                // Call did not come from a managed profile.
                if (primaryUser == null) {
                    primaryUser = um.getUserInfo(userId);
                }

                IPackageManager pm = AppGlobals.getPackageManager();
                List<ResolveInfo> activitiesToEnable = pm.queryIntentActivities(intent,
                        intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                        0, // no flags
                        primaryUser.id);

                if (DBG) Slog.d(LOG_TAG, "Enabling system activities: " + activitiesToEnable);
                int numberOfAppsInstalled = 0;
                if (activitiesToEnable != null) {
                    for (ResolveInfo info : activitiesToEnable) {
                        if (info.activityInfo != null) {
                            String packageName = info.activityInfo.packageName;
                            if (isSystemApp(pm, packageName, primaryUser.id)) {
                                numberOfAppsInstalled++;
                                pm.installExistingPackageAsUser(packageName, userId);
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
                restoreCallingIdentity(id);
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
        enforceCrossUserPermission(userId);
        if (!mHasFeature) {
            return null;
        }
        synchronized (this) {
            DevicePolicyData policy = getUserData(userId);
            final int N = policy.mAdminList.size();
            HashSet<String> resultSet = new HashSet<String>();
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

            long id = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                pm.setBlockUninstallForUser(packageName, uninstallBlocked, userId);
            } catch (RemoteException re) {
                // Shouldn't happen.
                Slog.e(LOG_TAG, "Failed to setBlockUninstallForUser", re);
            } finally {
                restoreCallingIdentity(id);
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

            long id = Binder.clearCallingIdentity();
            try {
                IPackageManager pm = AppGlobals.getPackageManager();
                return pm.getBlockUninstallForUser(packageName, userId);
            } catch (RemoteException re) {
                // Shouldn't happen.
                Slog.e(LOG_TAG, "Failed to getBlockUninstallForUser", re);
            } finally {
                restoreCallingIdentity(id);
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
                saveSettingsLocked(UserHandle.getCallingUserId());
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
        // TODO: Should there be a check to make sure this relationship is within a profile group?
        //enforceSystemProcess("getCrossProfileCallerIdDisabled can only be called by system");
        synchronized (this) {
            ActiveAdmin admin = getProfileOwnerAdmin(userId);
            return (admin != null) ? admin.disableCallerId : false;
        }
    }

    @Override
    public void startManagedQuickContact(String actualLookupKey, long actualContactId,
            Intent originalIntent) {
        final Intent intent = QuickContact.rebuildManagedQuickContactsIntent(
                actualLookupKey, actualContactId, originalIntent);
        final int callingUserId = UserHandle.getCallingUserId();

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                final int managedUserId = getManagedUserId(callingUserId);
                if (managedUserId < 0) {
                    return;
                }
                if (getCrossProfileCallerIdDisabledForUser(managedUserId)) {
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
            Binder.restoreCallingIdentity(ident);
        }
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
            ActiveAdmin admin = getProfileOwnerAdmin(userId);
            return (admin != null) ? admin.disableBluetoothContactSharing : false;
        }
    }

    /**
     * Sets which packages may enter lock task mode.
     *
     * This function can only be called by the device owner.
     * @param packages The list of packages allowed to enter lock task mode.
     */
    @Override
    public void setLockTaskPackages(ComponentName who, String[] packages)
            throws SecurityException {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);

            int userHandle = Binder.getCallingUserHandle().getIdentifier();
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

    /**
     * This function returns the list of components allowed to start the task lock mode.
     */
    @Override
    public String[] getLockTaskPackages(ComponentName who) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER);
            int userHandle = Binder.getCallingUserHandle().getIdentifier();
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
        int uid = Binder.getCallingUid();
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
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("notifyLockTaskModeChanged can only be called by system");
        }
        synchronized (this) {
            final DevicePolicyData policy = getUserData(userHandle);
            Bundle adminExtras = new Bundle();
            adminExtras.putString(DeviceAdminReceiver.EXTRA_LOCK_TASK_PACKAGE, pkg);
            for (ActiveAdmin admin : policy.mAdminList) {
                boolean ownsDevice = isDeviceOwner(admin.info.getPackageName());
                boolean ownsProfile = (getProfileOwner(userHandle) != null
                        && getProfileOwner(userHandle).equals(admin.info.getPackageName()));
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
        final ContentResolver contentResolver = mContext.getContentResolver();
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
                long timeMs = getMaximumTimeToLock(who, UserHandle.getCallingUserId());
                if (timeMs > 0 && timeMs < Integer.MAX_VALUE) {
                    return;
                }
            }

            long id = Binder.clearCallingIdentity();
            try {
                Settings.Global.putString(contentResolver, setting, value);
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setSecureSetting(ComponentName who, String setting, String value) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        int callingUserId = UserHandle.getCallingUserId();
        final ContentResolver contentResolver = mContext.getContentResolver();

        synchronized (this) {
            ActiveAdmin activeAdmin =
                    getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);

            if (isDeviceOwner(activeAdmin.info.getPackageName())) {
                if (!SECURE_SETTINGS_DEVICEOWNER_WHITELIST.contains(setting)) {
                    throw new SecurityException(String.format(
                            "Permission denial: Device owners cannot update %1$s", setting));
                }
            } else if (!SECURE_SETTINGS_WHITELIST.contains(setting)) {
                throw new SecurityException(String.format(
                        "Permission denial: Profile owners cannot update %1$s", setting));
            }

            long id = Binder.clearCallingIdentity();
            try {
                Settings.Secure.putStringForUser(contentResolver, setting, value, callingUserId);
            } finally {
                restoreCallingIdentity(id);
            }
        }
    }

    @Override
    public void setMasterVolumeMuted(ComponentName who, boolean on) {
        Preconditions.checkNotNull(who, "ComponentName is null");
        synchronized (this) {
            getActiveAdminForCallerLocked(who, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            int userId = UserHandle.getCallingUserId();
            long identity = Binder.clearCallingIdentity();
            try {
                IAudioService iAudioService = IAudioService.Stub.asInterface(
                        ServiceManager.getService(Context.AUDIO_SERVICE));
                iAudioService.setMasterMute(on, 0, mContext.getPackageName(), userId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Failed to setMasterMute", re);
            } finally {
                Binder.restoreCallingIdentity(identity);
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
            long id = Binder.clearCallingIdentity();
            try {
                mUserManager.setUserIcon(userId, icon);
            } finally {
                restoreCallingIdentity(id);
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
        LockPatternUtils utils = new LockPatternUtils(mContext);

        long ident = Binder.clearCallingIdentity();
        try {
            // disallow disabling the keyguard if a password is currently set
            if (disabled && utils.isSecure(userId)) {
                return false;
            }
            utils.setLockScreenDisabled(disabled, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
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
        long ident = Binder.clearCallingIdentity();
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
            Binder.restoreCallingIdentity(ident);
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
        ContentResolver resolver = mContext.getContentResolver();
        final int N = users.size();
        for (int i = 0; i < N; i++) {
            int userHandle = users.get(i).id;
            if (Settings.Secure.getIntForUser(resolver, Settings.Secure.USER_SETUP_COMPLETE, 0,
                    userHandle) != 0) {
                DevicePolicyData policy = getUserData(userHandle);
                if (!policy.mUserSetupComplete) {
                    policy.mUserSetupComplete = true;
                    synchronized (this) {
                        // The DeviceInitializer was whitelisted but now should be removed.
                        removeDeviceInitializerFromLockTaskPackages(userHandle);
                        saveSettingsLocked(userHandle);
                    }
                }
            }
        }
    }

    private void addDeviceInitializerToLockTaskPackagesLocked(int userHandle) {
        if (hasUserSetupCompleted(userHandle)) {
            return;
        }

        final String deviceInitializerPackage = getDeviceInitializer();
        if (deviceInitializerPackage == null) {
            return;
        }

        final List<String> packages = getLockTaskPackagesLocked(userHandle);
        if (!packages.contains(deviceInitializerPackage)) {
            packages.add(deviceInitializerPackage);
            setLockTaskPackagesLocked(userHandle, packages);
        }
    }

    private void removeDeviceInitializerFromLockTaskPackages(int userHandle) {
        final String deviceInitializerPackage = getDeviceInitializer();
        if (deviceInitializerPackage == null) {
            return;
        }

        List<String> packages = getLockTaskPackagesLocked(userHandle);
        if (packages.remove(deviceInitializerPackage)) {
            setLockTaskPackagesLocked(userHandle, packages);
        }
    }

    private class SetupContentObserver extends ContentObserver {

        private final Uri mUserSetupComplete = Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE);

        public SetupContentObserver(Handler handler) {
            super(handler);
        }

        void register(ContentResolver resolver) {
            resolver.registerContentObserver(mUserSetupComplete, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mUserSetupComplete.equals(uri)) {
                updateUserSetupComplete();
            }
        }
    }

    private final class LocalService extends DevicePolicyManagerInternal {
        private List<OnCrossProfileWidgetProvidersChangeListener> mWidgetProviderListeners;

        @Override
        public List<String> getCrossProfileWidgetProviders(int profileId) {
            synchronized (DevicePolicyManagerService.this) {
                if (mDeviceOwner == null) {
                    return Collections.emptyList();
                }
                ComponentName ownerComponent = mDeviceOwner.getProfileOwnerComponent(profileId);
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
            final int userId = UserHandle.getUserId(uid);
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
                mDeviceOwner.clearSystemUpdatePolicy();
            } else {
                mDeviceOwner.setSystemUpdatePolicy(policy);
            }
            mDeviceOwner.writeOwnerFile();
        }
        mContext.sendBroadcastAsUser(
                new Intent(DevicePolicyManager.ACTION_SYSTEM_UPDATE_POLICY_CHANGED),
                UserHandle.OWNER);
    }

    @Override
    public SystemUpdatePolicy getSystemUpdatePolicy() {
        synchronized (this) {
            SystemUpdatePolicy policy =  mDeviceOwner.getSystemUpdatePolicy();
            if (policy != null && !policy.isValid()) {
                Slog.w(LOG_TAG, "Stored system update policy is invalid, return null instead.");
                return null;
            }
            return policy;
        }
    }

    /**
     * Checks if the caller of the method is the device owner app or device initialization app.
     *
     * @param callerUid UID of the caller.
     * @return true if the caller is the device owner app or device initializer.
     */
    private boolean isCallerDeviceOwnerOrInitializer(int callerUid) {
        String[] pkgs = mContext.getPackageManager().getPackagesForUid(callerUid);
        for (String pkg : pkgs) {
            if (isDeviceOwner(pkg) || isDeviceInitializer(pkg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void notifyPendingSystemUpdate(long updateReceivedTime) {
        mContext.enforceCallingOrSelfPermission(permission.NOTIFY_PENDING_SYSTEM_UPDATE,
                "Only the system update service can broadcast update information");

        if (UserHandle.getCallingUserId() != UserHandle.USER_OWNER) {
            Slog.w(LOG_TAG, "Only the system update service in the primary user" +
                    "can broadcast update information.");
            return;
        }
        Intent intent = new Intent(DeviceAdminReceiver.ACTION_NOTIFY_PENDING_SYSTEM_UPDATE);
        intent.putExtra(DeviceAdminReceiver.EXTRA_SYSTEM_UPDATE_RECEIVED_TIME,
                updateReceivedTime);

        synchronized (this) {
            String deviceOwnerPackage = getDeviceOwner();
            if (deviceOwnerPackage == null) {
                return;
            }

            ActivityInfo[] receivers = null;
            try {
                receivers  = mContext.getPackageManager().getPackageInfo(
                        deviceOwnerPackage, PackageManager.GET_RECEIVERS).receivers;
            } catch (NameNotFoundException e) {
                Log.e(LOG_TAG, "Cannot find device owner package", e);
            }
            if (receivers != null) {
                long ident = Binder.clearCallingIdentity();
                try {
                    for (int i = 0; i < receivers.length; i++) {
                        if (permission.BIND_DEVICE_ADMIN.equals(receivers[i].permission)) {
                            intent.setComponent(new ComponentName(deviceOwnerPackage,
                                    receivers[i].name));
                            mContext.sendBroadcastAsUser(intent, UserHandle.OWNER);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
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
        UserHandle user = Binder.getCallingUserHandle();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            long ident = Binder.clearCallingIdentity();
            try {
                final ApplicationInfo ai = AppGlobals.getPackageManager()
                        .getApplicationInfo(packageName, 0, user.getIdentifier());
                final int targetSdkVersion = ai == null ? 0 : ai.targetSdkVersion;
                if (targetSdkVersion < android.os.Build.VERSION_CODES.M) {
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
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public int getPermissionGrantState(ComponentName admin, String packageName,
            String permission) throws RemoteException {
        PackageManager packageManager = mContext.getPackageManager();

        UserHandle user = Binder.getCallingUserHandle();
        synchronized (this) {
            getActiveAdminForCallerLocked(admin, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
            long ident = Binder.clearCallingIdentity();
            try {
                int granted = AppGlobals.getPackageManager().checkPermission(permission,
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
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
}
