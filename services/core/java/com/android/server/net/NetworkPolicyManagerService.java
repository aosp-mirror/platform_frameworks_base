/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.net;

import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS;
import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.MANAGE_SUBSCRIPTION_PLANS;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.OBSERVE_NETWORK_POLICY;
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.content.pm.PackageManager.MATCH_ANY_USER;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.INetd.FIREWALL_CHAIN_DOZABLE;
import static android.net.INetd.FIREWALL_CHAIN_POWERSAVE;
import static android.net.INetd.FIREWALL_CHAIN_RESTRICTED;
import static android.net.INetd.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.FIREWALL_RULE_ALLOW;
import static android.net.INetd.FIREWALL_RULE_DENY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkIdentity.OEM_NONE;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.ALLOWED_METERED_REASON_FOREGROUND;
import static android.net.NetworkPolicyManager.ALLOWED_METERED_REASON_MASK;
import static android.net.NetworkPolicyManager.ALLOWED_METERED_REASON_SYSTEM;
import static android.net.NetworkPolicyManager.ALLOWED_METERED_REASON_USER_EXEMPTED;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_FOREGROUND;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_NONE;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_POWER_SAVE_ALLOWLIST;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_POWER_SAVE_EXCEPT_IDLE_ALLOWLIST;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_RESTRICTED_MODE_PERMISSIONS;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_SYSTEM;
import static android.net.NetworkPolicyManager.BLOCKED_METERED_REASON_ADMIN_DISABLED;
import static android.net.NetworkPolicyManager.BLOCKED_METERED_REASON_DATA_SAVER;
import static android.net.NetworkPolicyManager.BLOCKED_METERED_REASON_MASK;
import static android.net.NetworkPolicyManager.BLOCKED_METERED_REASON_USER_RESTRICTED;
import static android.net.NetworkPolicyManager.BLOCKED_REASON_APP_STANDBY;
import static android.net.NetworkPolicyManager.BLOCKED_REASON_BATTERY_SAVER;
import static android.net.NetworkPolicyManager.BLOCKED_REASON_DOZE;
import static android.net.NetworkPolicyManager.BLOCKED_REASON_NONE;
import static android.net.NetworkPolicyManager.BLOCKED_REASON_RESTRICTED_MODE;
import static android.net.NetworkPolicyManager.EXTRA_NETWORK_TEMPLATE;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.net.NetworkPolicyManager.MASK_ALL_NETWORKS;
import static android.net.NetworkPolicyManager.MASK_METERED_NETWORKS;
import static android.net.NetworkPolicyManager.MASK_RESTRICTED_MODE_NETWORKS;
import static android.net.NetworkPolicyManager.POLICY_ALLOW_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_ALLOW_METERED;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.RULE_REJECT_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.NetworkPolicyManager.RULE_REJECT_RESTRICTED_MODE;
import static android.net.NetworkPolicyManager.RULE_TEMPORARY_ALLOW_METERED;
import static android.net.NetworkPolicyManager.SUBSCRIPTION_OVERRIDE_UNMETERED;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode;
import static android.net.NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground;
import static android.net.NetworkPolicyManager.resolveNetworkId;
import static android.net.NetworkPolicyManager.uidPoliciesToString;
import static android.net.NetworkPolicyManager.uidRulesToString;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.NetworkTemplate.MATCH_MOBILE;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.net.netstats.provider.NetworkStatsProvider.QUOTA_UNLIMITED;
import static android.os.Trace.TRACE_TAG_NETWORK;
import static android.provider.Settings.Global.NETPOLICY_OVERRIDE_ENABLED;
import static android.provider.Settings.Global.NETPOLICY_QUOTA_ENABLED;
import static android.provider.Settings.Global.NETPOLICY_QUOTA_FRAC_JOBS;
import static android.provider.Settings.Global.NETPOLICY_QUOTA_FRAC_MULTIPATH;
import static android.provider.Settings.Global.NETPOLICY_QUOTA_LIMITED;
import static android.provider.Settings.Global.NETPOLICY_QUOTA_UNLIMITED;
import static android.telephony.CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED;
import static android.telephony.CarrierConfigManager.DATA_CYCLE_THRESHOLD_DISABLED;
import static android.telephony.CarrierConfigManager.DATA_CYCLE_USE_PLATFORM_DEFAULT;
import static android.telephony.CarrierConfigManager.KEY_DATA_LIMIT_NOTIFICATION_BOOL;
import static android.telephony.CarrierConfigManager.KEY_DATA_RAPID_NOTIFICATION_BOOL;
import static android.telephony.CarrierConfigManager.KEY_DATA_WARNING_NOTIFICATION_BOOL;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.internal.util.ArrayUtils.appendInt;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.readStringAttribute;
import static com.android.internal.util.XmlUtils.readThisIntArrayXml;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntArrayXml;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.internal.util.XmlUtils.writeStringAttribute;
import static com.android.server.NetworkManagementService.LIMIT_GLOBAL_ALERT;
import static com.android.server.net.NetworkPolicyLogger.NTWK_ALLOWED_ALLOWLIST;
import static com.android.server.net.NetworkPolicyLogger.NTWK_ALLOWED_DEFAULT;
import static com.android.server.net.NetworkPolicyLogger.NTWK_ALLOWED_NON_METERED;
import static com.android.server.net.NetworkPolicyLogger.NTWK_ALLOWED_SYSTEM;
import static com.android.server.net.NetworkPolicyLogger.NTWK_ALLOWED_TMP_ALLOWLIST;
import static com.android.server.net.NetworkPolicyLogger.NTWK_BLOCKED_BG_RESTRICT;
import static com.android.server.net.NetworkPolicyLogger.NTWK_BLOCKED_DENYLIST;
import static com.android.server.net.NetworkPolicyLogger.NTWK_BLOCKED_POWER;
import static com.android.server.net.NetworkPolicyLogger.NTWK_BLOCKED_RESTRICTED_MODE;
import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_UPDATED;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessCapability;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkIdentity;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkPolicyManager.UidState;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStateSnapshot;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.net.TelephonyNetworkSpecifier;
import android.net.TrafficStats;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.BestClock;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.Message;
import android.os.MessageQueue.IdleHandler;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.PowerWhitelistManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;
import android.telephony.SubscriptionPlan;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.DataUnit;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.RecurrenceRule;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.StatLogger;
import com.android.internal.util.XmlUtils;
import com.android.net.module.util.NetworkIdentityUtils;
import com.android.net.module.util.PermissionUtils;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemConfig;
import com.android.server.connectivity.MultipathPolicyTracker;
import com.android.server.usage.AppStandbyInternal;
import com.android.server.usage.AppStandbyInternal.AppIdleStateChangeListener;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Service that maintains low-level network policy rules, using
 * {@link NetworkStatsService} statistics to drive those rules.
 * <p>
 * Derives active rules by combining a given policy with other system status,
 * and delivers to listeners, such as {@link ConnectivityManager}, for
 * enforcement.
 *
 * <p>
 * This class uses 2 locks to synchronize state:
 * <ul>
 * <li>{@code mUidRulesFirstLock}: used to guard state related to individual UIDs (such as firewall
 * rules).
 * <li>{@code mNetworkPoliciesSecondLock}: used to guard state related to network interfaces (such
 * as network policies).
 * </ul>
 *
 * <p>
 * As such, methods that require synchronization have the following prefixes:
 * <ul>
 * <li>{@code UL()}: require the "UID" lock ({@code mUidRulesFirstLock}).
 * <li>{@code NL()}: require the "Network" lock ({@code mNetworkPoliciesSecondLock}).
 * <li>{@code AL()}: require all locks, which must be obtained in order ({@code mUidRulesFirstLock}
 * first, then {@code mNetworkPoliciesSecondLock}, then {@code mYetAnotherGuardThirdLock}, etc..
 * </ul>
 */
public class NetworkPolicyManagerService extends INetworkPolicyManager.Stub {
    static final String TAG = NetworkPolicyLogger.TAG;
    private static final boolean LOGD = NetworkPolicyLogger.LOGD;
    private static final boolean LOGV = NetworkPolicyLogger.LOGV;

    /**
     * No opportunistic quota could be calculated from user data plan or data settings.
     */
    public static final int OPPORTUNISTIC_QUOTA_UNKNOWN = -1;

    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADDED_SNOOZE = 2;
    private static final int VERSION_ADDED_RESTRICT_BACKGROUND = 3;
    private static final int VERSION_ADDED_METERED = 4;
    private static final int VERSION_SPLIT_SNOOZE = 5;
    private static final int VERSION_ADDED_TIMEZONE = 6;
    private static final int VERSION_ADDED_INFERRED = 7;
    private static final int VERSION_SWITCH_APP_ID = 8;
    private static final int VERSION_ADDED_NETWORK_ID = 9;
    private static final int VERSION_SWITCH_UID = 10;
    private static final int VERSION_ADDED_CYCLE = 11;
    private static final int VERSION_ADDED_NETWORK_TYPES = 12;
    private static final int VERSION_LATEST = VERSION_ADDED_NETWORK_TYPES;

    @VisibleForTesting
    public static final int TYPE_WARNING = SystemMessage.NOTE_NET_WARNING;
    @VisibleForTesting
    public static final int TYPE_LIMIT = SystemMessage.NOTE_NET_LIMIT;
    @VisibleForTesting
    public static final int TYPE_LIMIT_SNOOZED = SystemMessage.NOTE_NET_LIMIT_SNOOZED;
    @VisibleForTesting
    public static final int TYPE_RAPID = SystemMessage.NOTE_NET_RAPID;

    private static final String TAG_POLICY_LIST = "policy-list";
    private static final String TAG_NETWORK_POLICY = "network-policy";
    private static final String TAG_SUBSCRIPTION_PLAN = "subscription-plan";
    private static final String TAG_UID_POLICY = "uid-policy";
    private static final String TAG_APP_POLICY = "app-policy";
    private static final String TAG_WHITELIST = "whitelist";
    private static final String TAG_RESTRICT_BACKGROUND = "restrict-background";
    private static final String TAG_REVOKED_RESTRICT_BACKGROUND = "revoked-restrict-background";
    private static final String TAG_XML_UTILS_INT_ARRAY = "int-array";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_RESTRICT_BACKGROUND = "restrictBackground";
    private static final String ATTR_NETWORK_TEMPLATE = "networkTemplate";
    private static final String ATTR_SUBSCRIBER_ID = "subscriberId";
    private static final String ATTR_NETWORK_ID = "networkId";
    @Deprecated private static final String ATTR_CYCLE_DAY = "cycleDay";
    @Deprecated private static final String ATTR_CYCLE_TIMEZONE = "cycleTimezone";
    private static final String ATTR_CYCLE_START = "cycleStart";
    private static final String ATTR_CYCLE_END = "cycleEnd";
    private static final String ATTR_CYCLE_PERIOD = "cyclePeriod";
    private static final String ATTR_WARNING_BYTES = "warningBytes";
    private static final String ATTR_LIMIT_BYTES = "limitBytes";
    private static final String ATTR_LAST_SNOOZE = "lastSnooze";
    private static final String ATTR_LAST_WARNING_SNOOZE = "lastWarningSnooze";
    private static final String ATTR_LAST_LIMIT_SNOOZE = "lastLimitSnooze";
    private static final String ATTR_METERED = "metered";
    private static final String ATTR_INFERRED = "inferred";
    private static final String ATTR_UID = "uid";
    private static final String ATTR_APP_ID = "appId";
    private static final String ATTR_POLICY = "policy";
    private static final String ATTR_SUB_ID = "subId";
    private static final String ATTR_TITLE = "title";
    private static final String ATTR_SUMMARY = "summary";
    private static final String ATTR_LIMIT_BEHAVIOR = "limitBehavior";
    private static final String ATTR_USAGE_BYTES = "usageBytes";
    private static final String ATTR_USAGE_TIME = "usageTime";
    private static final String ATTR_OWNER_PACKAGE = "ownerPackage";
    private static final String ATTR_NETWORK_TYPES = "networkTypes";
    private static final String ATTR_XML_UTILS_NAME = "name";

    private static final String ACTION_ALLOW_BACKGROUND =
            "com.android.server.net.action.ALLOW_BACKGROUND";
    private static final String ACTION_SNOOZE_WARNING =
            "com.android.server.net.action.SNOOZE_WARNING";
    private static final String ACTION_SNOOZE_RAPID =
            "com.android.server.net.action.SNOOZE_RAPID";

    /**
     * Indicates the maximum wait time for admin data to be available.
     */
    private static final long WAIT_FOR_ADMIN_DATA_TIMEOUT_MS = 10_000;

    private static final long QUOTA_UNLIMITED_DEFAULT = DataUnit.MEBIBYTES.toBytes(20);
    private static final float QUOTA_LIMITED_DEFAULT = 0.1f;
    private static final float QUOTA_FRAC_JOBS_DEFAULT = 0.5f;
    private static final float QUOTA_FRAC_MULTIPATH_DEFAULT = 0.5f;

    private static final int MSG_RULES_CHANGED = 1;
    private static final int MSG_METERED_IFACES_CHANGED = 2;
    private static final int MSG_LIMIT_REACHED = 5;
    private static final int MSG_RESTRICT_BACKGROUND_CHANGED = 6;
    private static final int MSG_ADVISE_PERSIST_THRESHOLD = 7;
    private static final int MSG_UPDATE_INTERFACE_QUOTA = 10;
    private static final int MSG_REMOVE_INTERFACE_QUOTA = 11;
    private static final int MSG_POLICIES_CHANGED = 13;
    private static final int MSG_RESET_FIREWALL_RULES_BY_UID = 15;
    private static final int MSG_SUBSCRIPTION_OVERRIDE = 16;
    private static final int MSG_METERED_RESTRICTED_PACKAGES_CHANGED = 17;
    private static final int MSG_SET_NETWORK_TEMPLATE_ENABLED = 18;
    private static final int MSG_SUBSCRIPTION_PLANS_CHANGED = 19;
    private static final int MSG_STATS_PROVIDER_WARNING_OR_LIMIT_REACHED = 20;
    // TODO: Add similar docs for other messages.
    /**
     * Message to indicate that reasons for why an uid is blocked changed.
     * arg1 = uid
     * arg2 = oldBlockedReasons
     * obj = newBlockedReasons
     */
    private static final int MSG_BLOCKED_REASON_CHANGED = 21;

    private static final int UID_MSG_STATE_CHANGED = 100;
    private static final int UID_MSG_GONE = 101;

    private static final String PROP_SUB_PLAN_OWNER = "persist.sys.sub_plan_owner";

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private NetworkStatsManagerInternal mNetworkStats;
    private final INetworkManagementService mNetworkManager;
    private UsageStatsManagerInternal mUsageStats;
    private AppStandbyInternal mAppStandby;
    private final Clock mClock;
    private final UserManager mUserManager;
    private final CarrierConfigManager mCarrierConfigManager;
    private final MultipathPolicyTracker mMultipathPolicyTracker;

    private ConnectivityManager mConnManager;
    private PowerManagerInternal mPowerManagerInternal;
    private PowerWhitelistManager mPowerWhitelistManager;

    /** Current cached value of the current Battery Saver mode's setting for restrict background. */
    @GuardedBy("mUidRulesFirstLock")
    private boolean mRestrictBackgroundLowPowerMode;

    // Store the status of restrict background before turning on battery saver.
    // Used to restore mRestrictBackground when battery saver is turned off.
    private boolean mRestrictBackgroundBeforeBsm;

    // Denotes the status of restrict background read from disk.
    private boolean mLoadedRestrictBackground;

    // See main javadoc for instructions on how to use these locks.
    final Object mUidRulesFirstLock = new Object();
    final Object mNetworkPoliciesSecondLock = new Object();

    @GuardedBy({"mUidRulesFirstLock", "mNetworkPoliciesSecondLock"})
    volatile boolean mSystemReady;

    @GuardedBy("mUidRulesFirstLock") volatile boolean mRestrictBackground;
    @GuardedBy("mUidRulesFirstLock") volatile boolean mRestrictPower;
    @GuardedBy("mUidRulesFirstLock") volatile boolean mDeviceIdleMode;
    // Store whether user flipped restrict background in battery saver mode
    @GuardedBy("mUidRulesFirstLock")
    volatile boolean mRestrictBackgroundChangedInBsm;
    @GuardedBy("mUidRulesFirstLock")
    volatile boolean mRestrictedNetworkingMode;

    private final boolean mSuppressDefaultPolicy;

    private final CountDownLatch mAdminDataAvailableLatch = new CountDownLatch(1);

    private volatile boolean mNetworkManagerReady;

    /** Defined network policies. */
    @GuardedBy("mNetworkPoliciesSecondLock")
    final ArrayMap<NetworkTemplate, NetworkPolicy> mNetworkPolicy = new ArrayMap<>();

    /** Map from subId to subscription plans. */
    @GuardedBy("mNetworkPoliciesSecondLock")
    final SparseArray<SubscriptionPlan[]> mSubscriptionPlans = new SparseArray<>();
    /** Map from subId to package name that owns subscription plans. */
    @GuardedBy("mNetworkPoliciesSecondLock")
    final SparseArray<String> mSubscriptionPlansOwner = new SparseArray<>();

    /** Map from subId to daily opportunistic quota. */
    @GuardedBy("mNetworkPoliciesSecondLock")
    final SparseLongArray mSubscriptionOpportunisticQuota = new SparseLongArray();

    /** Defined UID policies. */
    @GuardedBy("mUidRulesFirstLock") final SparseIntArray mUidPolicy = new SparseIntArray();
    /** Currently derived rules for each UID. */
    @GuardedBy("mUidRulesFirstLock") final SparseIntArray mUidRules = new SparseIntArray();

    @GuardedBy("mUidRulesFirstLock")
    final SparseIntArray mUidFirewallStandbyRules = new SparseIntArray();
    @GuardedBy("mUidRulesFirstLock")
    final SparseIntArray mUidFirewallDozableRules = new SparseIntArray();
    @GuardedBy("mUidRulesFirstLock")
    final SparseIntArray mUidFirewallPowerSaveRules = new SparseIntArray();
    @GuardedBy("mUidRulesFirstLock")
    final SparseIntArray mUidFirewallRestrictedModeRules = new SparseIntArray();

    /** Set of states for the child firewall chains. True if the chain is active. */
    @GuardedBy("mUidRulesFirstLock")
    final SparseBooleanArray mFirewallChainStates = new SparseBooleanArray();

    // "Power save mode" is the concept used in the DeviceIdleController that includes various
    // features including Doze and Battery Saver. It include Battery Saver, but "power save mode"
    // and "battery saver" are not equivalent.

    /**
     * UIDs that have been allowlisted to always be able to have network access
     * in power save mode, except device idle (doze) still applies.
     * TODO: An int array might be sufficient
     */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mPowerSaveWhitelistExceptIdleAppIds = new SparseBooleanArray();

    /**
     * UIDs that have been allowlisted to always be able to have network access
     * in power save mode.
     * TODO: An int array might be sufficient
     */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mPowerSaveWhitelistAppIds = new SparseBooleanArray();

    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mPowerSaveTempWhitelistAppIds = new SparseBooleanArray();

    /**
     * UIDs that have been allowlisted temporarily to be able to have network access despite being
     * idle. Other power saving restrictions still apply.
     */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mAppIdleTempWhitelistAppIds = new SparseBooleanArray();

    /**
     * UIDs that have been initially allowlisted by system to avoid restricted background.
     */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mDefaultRestrictBackgroundAllowlistUids =
            new SparseBooleanArray();

    /**
     * UIDs that have been initially allowlisted by system to avoid restricted background,
     * but later revoked by user.
     */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mRestrictBackgroundAllowlistRevokedUids =
            new SparseBooleanArray();

    final Object mMeteredIfacesLock = new Object();
    /** Set of ifaces that are metered. */
    @GuardedBy("mMeteredIfacesLock")
    private ArraySet<String> mMeteredIfaces = new ArraySet<>();
    /** Set of over-limit templates that have been notified. */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final ArraySet<NetworkTemplate> mOverLimitNotified = new ArraySet<>();

    /** Set of currently active {@link Notification} tags. */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final ArraySet<NotificationId> mActiveNotifs = new ArraySet<>();

    /** Foreground at UID granularity. */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseArray<UidState> mUidState = new SparseArray<>();

    @GuardedBy("mUidRulesFirstLock")
    private final SparseArray<UidBlockedState> mUidBlockedState = new SparseArray<>();

    /** Map from network ID to last observed meteredness state */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final SparseBooleanArray mNetworkMetered = new SparseBooleanArray();
    /** Map from network ID to last observed roaming state */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final SparseBooleanArray mNetworkRoaming = new SparseBooleanArray();

    /** Map from netId to subId as of last update */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final SparseIntArray mNetIdToSubId = new SparseIntArray();

    /** Map from subId to subscriberId as of last update */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final SparseArray<String> mSubIdToSubscriberId = new SparseArray<>();
    /** Set of all merged subscriberId as of last update */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private List<String[]> mMergedSubscriberIds = new ArrayList<>();
    /** Map from subId to carrierConfig as of last update */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private final SparseArray<PersistableBundle> mSubIdToCarrierConfig =
            new SparseArray<PersistableBundle>();

    /**
     * Indicates the uids restricted by admin from accessing metered data. It's a mapping from
     * userId to restricted uids which belong to that user.
     */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseArray<Set<Integer>> mMeteredRestrictedUids = new SparseArray<>();

    private final RemoteCallbackList<INetworkPolicyListener>
            mListeners = new RemoteCallbackList<>();

    final Handler mHandler;
    @VisibleForTesting
    final Handler mUidEventHandler;

    private final ServiceThread mUidEventThread;

    @GuardedBy({"mUidRulesFirstLock", "mNetworkPoliciesSecondLock"})
    private final AtomicFile mPolicyFile;

    private final AppOpsManager mAppOps;

    private final IPackageManager mIPm;

    private ActivityManagerInternal mActivityManagerInternal;

    private final NetworkPolicyLogger mLogger = new NetworkPolicyLogger();

    /** List of apps indexed by uid and whether they have the internet permission */
    @GuardedBy("mUidRulesFirstLock")
    private final SparseBooleanArray mInternetPermissionMap = new SparseBooleanArray();

    private RestrictedModeObserver mRestrictedModeObserver;

    // TODO: keep allowlist of system-critical services that should never have
    // rules enforced, such as system, phone, and radio UIDs.

    // TODO: migrate notifications to SystemUI


    interface Stats {
        int UPDATE_NETWORK_ENABLED = 0;
        int IS_UID_NETWORKING_BLOCKED = 1;

        int COUNT = IS_UID_NETWORKING_BLOCKED + 1;
    }

    private static class RestrictedModeObserver extends ContentObserver {
        private final Context mContext;
        private final RestrictedModeListener mListener;

        RestrictedModeObserver(Context ctx, RestrictedModeListener listener) {
            super(null);
            mContext = ctx;
            mListener = listener;
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.RESTRICTED_NETWORKING_MODE), false,
                    this);
        }

        public boolean isRestrictedModeEnabled() {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.RESTRICTED_NETWORKING_MODE, 0) != 0;
        }

        @Override
        public void onChange(boolean selfChange) {
            mListener.onChange(isRestrictedModeEnabled());
        }

        public interface RestrictedModeListener {
            void onChange(boolean enabled);
        }
    }

    public final StatLogger mStatLogger = new StatLogger(new String[]{
            "updateNetworkEnabledNL()",
            "isUidNetworkingBlocked()",
    });

    public NetworkPolicyManagerService(Context context, IActivityManager activityManager,
            INetworkManagementService networkManagement) {
        this(context, activityManager, networkManagement, AppGlobals.getPackageManager(),
                getDefaultClock(), getDefaultSystemDir(), false);
    }

    private static @NonNull File getDefaultSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    private static @NonNull Clock getDefaultClock() {
        return new BestClock(ZoneOffset.UTC, SystemClock.currentNetworkTimeClock(),
                Clock.systemUTC());
    }

    public NetworkPolicyManagerService(Context context, IActivityManager activityManager,
            INetworkManagementService networkManagement, IPackageManager pm, Clock clock,
            File systemDir, boolean suppressDefaultPolicy) {
        mContext = Objects.requireNonNull(context, "missing context");
        mActivityManager = Objects.requireNonNull(activityManager, "missing activityManager");
        mNetworkManager = Objects.requireNonNull(networkManagement, "missing networkManagement");
        mPowerWhitelistManager = mContext.getSystemService(PowerWhitelistManager.class);
        mClock = Objects.requireNonNull(clock, "missing Clock");
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mCarrierConfigManager = mContext.getSystemService(CarrierConfigManager.class);
        mIPm = pm;

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper(), mHandlerCallback);

        // We create another thread for the UID events, which are more time-critical.
        mUidEventThread = new ServiceThread(TAG + ".uid", Process.THREAD_PRIORITY_FOREGROUND,
                /*allowIo=*/ false);
        mUidEventThread.start();
        mUidEventHandler = new Handler(mUidEventThread.getLooper(), mUidEventHandlerCallback);

        mSuppressDefaultPolicy = suppressDefaultPolicy;

        mPolicyFile = new AtomicFile(new File(systemDir, "netpolicy.xml"), "net-policy");

        mAppOps = context.getSystemService(AppOpsManager.class);
        mMultipathPolicyTracker = new MultipathPolicyTracker(mContext, mHandler);
        // Expose private service for system components to use.
        LocalServices.addService(NetworkPolicyManagerInternal.class,
                new NetworkPolicyManagerInternalImpl());
    }

    public void bindConnectivityManager() {
        mConnManager = Objects.requireNonNull(mContext.getSystemService(ConnectivityManager.class),
                "missing ConnectivityManager");
    }

    @GuardedBy("mUidRulesFirstLock")
    private void updatePowerSaveWhitelistUL() {
        int[] whitelist = mPowerWhitelistManager.getWhitelistedAppIds(/* includingIdle */ false);
        mPowerSaveWhitelistExceptIdleAppIds.clear();
        for (int uid : whitelist) {
            mPowerSaveWhitelistExceptIdleAppIds.put(uid, true);
        }

        whitelist = mPowerWhitelistManager.getWhitelistedAppIds(/* includingIdle */ true);
        mPowerSaveWhitelistAppIds.clear();
        for (int uid : whitelist) {
            mPowerSaveWhitelistAppIds.put(uid, true);
        }
    }

    /**
     * Allows pre-defined apps for restrict background, but only if the user didn't already
     * revoked them.
     *
     * @return whether any uid has been added to allowlist.
     */
    @GuardedBy("mUidRulesFirstLock")
    boolean addDefaultRestrictBackgroundAllowlistUidsUL() {
        final List<UserInfo> users = mUserManager.getUsers();
        final int numberUsers = users.size();

        boolean changed = false;
        for (int i = 0; i < numberUsers; i++) {
            final UserInfo user = users.get(i);
            changed = addDefaultRestrictBackgroundAllowlistUidsUL(user.id) || changed;
        }
        return changed;
    }

    @GuardedBy("mUidRulesFirstLock")
    private boolean addDefaultRestrictBackgroundAllowlistUidsUL(int userId) {
        final SystemConfig sysConfig = SystemConfig.getInstance();
        final PackageManager pm = mContext.getPackageManager();
        final ArraySet<String> allowDataUsage = sysConfig.getAllowInDataUsageSave();
        boolean changed = false;
        for (int i = 0; i < allowDataUsage.size(); i++) {
            final String pkg = allowDataUsage.valueAt(i);
            if (LOGD)
                Slog.d(TAG, "checking restricted background exemption for package " + pkg
                        + " and user " + userId);
            final ApplicationInfo app;
            try {
                app = pm.getApplicationInfoAsUser(pkg, PackageManager.MATCH_SYSTEM_ONLY, userId);
            } catch (PackageManager.NameNotFoundException e) {
                if (LOGD) Slog.d(TAG, "No ApplicationInfo for package " + pkg);
                // Ignore it - some apps on allow-in-data-usage-save are optional.
                continue;
            }
            if (!app.isPrivilegedApp()) {
                Slog.e(TAG, "addDefaultRestrictBackgroundAllowlistUidsUL(): "
                        + "skipping non-privileged app  " + pkg);
                continue;
            }
            final int uid = UserHandle.getUid(userId, app.uid);
            mDefaultRestrictBackgroundAllowlistUids.append(uid, true);
            if (LOGD)
                Slog.d(TAG, "Adding uid " + uid + " (user " + userId + ") to default restricted "
                        + "background allowlist. Revoked status: "
                        + mRestrictBackgroundAllowlistRevokedUids.get(uid));
            if (!mRestrictBackgroundAllowlistRevokedUids.get(uid)) {
                if (LOGD)
                    Slog.d(TAG, "adding default package " + pkg + " (uid " + uid + " for user "
                            + userId + ") to restrict background allowlist");
                setUidPolicyUncheckedUL(uid, POLICY_ALLOW_METERED_BACKGROUND, false);
                changed = true;
            }
        }
        return changed;
    }

    private void initService(CountDownLatch initCompleteSignal) {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "systemReady");
        final int oldPriority = Process.getThreadPriority(Process.myTid());
        try {
            // Boost thread's priority during system server init
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);
            if (!isBandwidthControlEnabled()) {
                Slog.w(TAG, "bandwidth controls disabled, unable to enforce policy");
                return;
            }

            mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
            mAppStandby = LocalServices.getService(AppStandbyInternal.class);
            mNetworkStats = LocalServices.getService(NetworkStatsManagerInternal.class);

            synchronized (mUidRulesFirstLock) {
                synchronized (mNetworkPoliciesSecondLock) {
                    updatePowerSaveWhitelistUL();
                    mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
                    mPowerManagerInternal.registerLowPowerModeObserver(
                            new PowerManagerInternal.LowPowerModeListener() {
                                @Override
                                public int getServiceType() {
                                    return ServiceType.NETWORK_FIREWALL;
                                }

                                @Override
                                public void onLowPowerModeChanged(PowerSaveState result) {
                                    final boolean enabled = result.batterySaverEnabled;
                                    if (LOGD) {
                                        Slog.d(TAG, "onLowPowerModeChanged(" + enabled + ")");
                                    }
                                    synchronized (mUidRulesFirstLock) {
                                        if (mRestrictPower != enabled) {
                                            mRestrictPower = enabled;
                                            updateRulesForRestrictPowerUL();
                                        }
                                    }
                                }
                            });
                    mRestrictPower = mPowerManagerInternal.getLowPowerState(
                            ServiceType.NETWORK_FIREWALL).batterySaverEnabled;

                    mRestrictedModeObserver = new RestrictedModeObserver(mContext,
                            enabled -> {
                                synchronized (mUidRulesFirstLock) {
                                    mRestrictedNetworkingMode = enabled;
                                    updateRestrictedModeAllowlistUL();
                                }
                            });
                    mRestrictedNetworkingMode = mRestrictedModeObserver.isRestrictedModeEnabled();

                    mSystemReady = true;

                    waitForAdminData();

                    // read policy from disk
                    readPolicyAL();

                    // Update the restrictBackground if battery saver is turned on
                    mRestrictBackgroundBeforeBsm = mLoadedRestrictBackground;
                    mRestrictBackgroundLowPowerMode = mPowerManagerInternal
                            .getLowPowerState(ServiceType.DATA_SAVER).batterySaverEnabled;
                    if (mRestrictBackgroundLowPowerMode && !mLoadedRestrictBackground) {
                        mLoadedRestrictBackground = true;
                    }
                    mPowerManagerInternal.registerLowPowerModeObserver(
                            new PowerManagerInternal.LowPowerModeListener() {
                                @Override
                                public int getServiceType() {
                                    return ServiceType.DATA_SAVER;
                                }

                                @Override
                                public void onLowPowerModeChanged(PowerSaveState result) {
                                    synchronized (mUidRulesFirstLock) {
                                        updateRestrictBackgroundByLowPowerModeUL(result);
                                    }
                                }
                            });

                    if (addDefaultRestrictBackgroundAllowlistUidsUL()) {
                        writePolicyAL();
                    }

                    setRestrictBackgroundUL(mLoadedRestrictBackground, "init_service");
                    updateRulesForGlobalChangeAL(false);
                    updateNotificationsNL();
                }
            }

            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            try {
                // TODO: There shouldn't be a need to receive callback for all changes.
                mActivityManager.registerUidObserver(mUidObserver,
                        ActivityManager.UID_OBSERVER_PROCSTATE|ActivityManager.UID_OBSERVER_GONE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, "android");
                mNetworkManager.registerObserver(mAlertObserver);
            } catch (RemoteException e) {
                // ignored; both services live in system_server
            }

            // listen for changes to power save allowlist
            final IntentFilter whitelistFilter = new IntentFilter(
                    PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
            mContext.registerReceiver(mPowerSaveWhitelistReceiver, whitelistFilter, null, mHandler);

            // watch for network interfaces to be claimed
            final IntentFilter connFilter = new IntentFilter(CONNECTIVITY_ACTION);
            mContext.registerReceiver(mConnReceiver, connFilter, NETWORK_STACK, mHandler);

            // listen for package changes to update policy
            final IntentFilter packageFilter = new IntentFilter();
            packageFilter.addAction(ACTION_PACKAGE_ADDED);
            packageFilter.addDataScheme("package");
            mContext.registerReceiver(mPackageReceiver, packageFilter, null, mHandler);

            // listen for UID changes to update policy
            mContext.registerReceiver(
                    mUidRemovedReceiver, new IntentFilter(ACTION_UID_REMOVED), null, mHandler);

            // listen for user changes to update policy
            final IntentFilter userFilter = new IntentFilter();
            userFilter.addAction(ACTION_USER_ADDED);
            userFilter.addAction(ACTION_USER_REMOVED);
            mContext.registerReceiver(mUserReceiver, userFilter, null, mHandler);

            // listen for stats update events
            final IntentFilter statsFilter = new IntentFilter(ACTION_NETWORK_STATS_UPDATED);
            mContext.registerReceiver(
                    mStatsReceiver, statsFilter, READ_NETWORK_USAGE_HISTORY, mHandler);

            // listen for restrict background changes from notifications
            final IntentFilter allowFilter = new IntentFilter(ACTION_ALLOW_BACKGROUND);
            mContext.registerReceiver(mAllowReceiver, allowFilter, MANAGE_NETWORK_POLICY, mHandler);

            // Listen for snooze from notifications
            mContext.registerReceiver(mSnoozeReceiver,
                    new IntentFilter(ACTION_SNOOZE_WARNING), MANAGE_NETWORK_POLICY, mHandler);
            mContext.registerReceiver(mSnoozeReceiver,
                    new IntentFilter(ACTION_SNOOZE_RAPID), MANAGE_NETWORK_POLICY, mHandler);

            // listen for configured wifi networks to be loaded
            final IntentFilter wifiFilter =
                    new IntentFilter(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
            mContext.registerReceiver(mWifiReceiver, wifiFilter, null, mHandler);

            // listen for carrier config changes to update data cycle information
            final IntentFilter carrierConfigFilter = new IntentFilter(
                    ACTION_CARRIER_CONFIG_CHANGED);
            mContext.registerReceiver(mCarrierConfigReceiver, carrierConfigFilter, null, mHandler);

            // listen for meteredness changes
            mConnManager.registerNetworkCallback(
                    new NetworkRequest.Builder().build(), mNetworkCallback);

            mAppStandby.addListener(new NetPolicyAppIdleStateChangeListener());
            synchronized (mUidRulesFirstLock) {
                updateRulesForAppIdleParoleUL();
            }

            // Listen for subscriber changes
            mContext.getSystemService(SubscriptionManager.class).addOnSubscriptionsChangedListener(
                    new HandlerExecutor(mHandler),
                    new OnSubscriptionsChangedListener() {
                        @Override
                        public void onSubscriptionsChanged() {
                            updateNetworksInternal();
                        }
                    });

            // tell systemReady() that the service has been initialized
            initCompleteSignal.countDown();
        } finally {
            // Restore the default priority after init is done
            Process.setThreadPriority(oldPriority);
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    public CountDownLatch networkScoreAndNetworkManagementServiceReady() {
        mNetworkManagerReady = true;
        final CountDownLatch initCompleteSignal = new CountDownLatch(1);
        mHandler.post(() -> initService(initCompleteSignal));
        return initCompleteSignal;
    }

    public void systemReady(CountDownLatch initCompleteSignal) {
        // wait for initService to complete
        try {
            if (!initCompleteSignal.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Service " + TAG +" init timeout");
            }
            mMultipathPolicyTracker.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Service " + TAG + " init interrupted", e);
        }
    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq,
                @ProcessCapability int capability) {
            // TODO: Avoid creating a new UidStateCallbackInfo object every time
            // we get a callback for an uid
            mUidEventHandler.obtainMessage(UID_MSG_STATE_CHANGED,
                    new UidStateCallbackInfo(uid, procState, procStateSeq, capability))
                            .sendToTarget();
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            mUidEventHandler.obtainMessage(UID_MSG_GONE, uid, 0).sendToTarget();
        }

        @Override public void onUidActive(int uid) {
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    private static final class UidStateCallbackInfo {
        public final int uid;
        public final int procState;
        public final long procStateSeq;
        @ProcessCapability
        public final int capability;

        UidStateCallbackInfo(int uid, int procState, long procStateSeq,
                @ProcessCapability int capability) {
            this.uid = uid;
            this.procState = procState;
            this.procStateSeq = procStateSeq;
            this.capability = capability;
        }
    }

    final private BroadcastReceiver mPowerSaveWhitelistReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and POWER_SAVE_WHITELIST_CHANGED is protected
            synchronized (mUidRulesFirstLock) {
                updatePowerSaveWhitelistUL();
                updateRulesForRestrictPowerUL();
                updateRulesForAppIdleUL();
            }
        }
    };

    final private BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and PACKAGE_ADDED is protected

            final String action = intent.getAction();
            final int uid = intent.getIntExtra(EXTRA_UID, -1);
            if (uid == -1) return;

            if (ACTION_PACKAGE_ADDED.equals(action)) {
                // update rules for UID, since it might be subject to
                // global background data policy
                if (LOGV) Slog.v(TAG, "ACTION_PACKAGE_ADDED for uid=" + uid);
                // Clear the cache for the app
                synchronized (mUidRulesFirstLock) {
                    mInternetPermissionMap.delete(uid);
                    updateRestrictionRulesForUidUL(uid);
                }
            }
        }
    };

    final private BroadcastReceiver mUidRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and UID_REMOVED is protected

            final int uid = intent.getIntExtra(EXTRA_UID, -1);
            if (uid == -1) return;

            // remove any policy and update rules to clean up
            if (LOGV) Slog.v(TAG, "ACTION_UID_REMOVED for uid=" + uid);
            synchronized (mUidRulesFirstLock) {
                onUidDeletedUL(uid);
                synchronized (mNetworkPoliciesSecondLock) {
                    writePolicyAL();
                }
            }
        }
    };

    final private BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and USER_ADDED and USER_REMOVED
            // broadcasts are protected

            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (userId == -1) return;

            switch (action) {
                case ACTION_USER_REMOVED:
                case ACTION_USER_ADDED:
                    synchronized (mUidRulesFirstLock) {
                        // Remove any persistable state for the given user; both cleaning up after a
                        // USER_REMOVED, and one last check during USER_ADDED
                        removeUserStateUL(userId, true, false);
                        // Removing outside removeUserStateUL since that can also be called when
                        // user resets app preferences.
                        mMeteredRestrictedUids.remove(userId);
                        if (action == ACTION_USER_ADDED) {
                            // Add apps that are allowed by default.
                            addDefaultRestrictBackgroundAllowlistUidsUL(userId);
                        }
                        // Update global restrict for that user
                        synchronized (mNetworkPoliciesSecondLock) {
                            updateRulesForGlobalChangeAL(true);
                        }
                    }
                    break;
            }
        }
    };

    /**
     * Receiver that watches for {@link INetworkStatsService} updates, which we
     * use to check against {@link NetworkPolicy#warningBytes}.
     */
    final private BroadcastReceiver mStatsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified
            // READ_NETWORK_USAGE_HISTORY permission above.

            synchronized (mNetworkPoliciesSecondLock) {
                updateNetworkRulesNL();
                updateNetworkEnabledNL();
                updateNotificationsNL();
            }
        }
    };

    /**
     * Receiver that watches for {@link Notification} control of
     * {@link #mRestrictBackground}.
     */
    final private BroadcastReceiver mAllowReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified MANAGE_NETWORK_POLICY
            // permission above.

            setRestrictBackground(false);
        }
    };

    /**
     * Receiver that watches for {@link Notification} control of
     * {@link NetworkPolicy#lastWarningSnooze}.
     */
    final private BroadcastReceiver mSnoozeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified MANAGE_NETWORK_POLICY
            // permission above.

            final NetworkTemplate template = intent.getParcelableExtra(EXTRA_NETWORK_TEMPLATE);
            if (ACTION_SNOOZE_WARNING.equals(intent.getAction())) {
                performSnooze(template, TYPE_WARNING);
            } else if (ACTION_SNOOZE_RAPID.equals(intent.getAction())) {
                performSnooze(template, TYPE_RAPID);
            }
        }
    };

    /**
     * Receiver that watches for {@link WifiConfiguration} to be loaded so that
     * we can perform upgrade logic. After initial upgrade logic, it updates
     * {@link #mMeteredIfaces} based on configuration changes.
     */
    final private BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mUidRulesFirstLock) {
                synchronized (mNetworkPoliciesSecondLock) {
                    upgradeWifiMeteredOverrideAL();
                }
            }
            // Only need to perform upgrade logic once
            mContext.unregisterReceiver(this);
        }
    };

    private static boolean updateCapabilityChange(SparseBooleanArray lastValues, boolean newValue,
            Network network) {
        final boolean lastValue = lastValues.get(network.getNetId(), false);
        final boolean changed = (lastValue != newValue)
                || lastValues.indexOfKey(network.getNetId()) < 0;
        if (changed) {
            lastValues.put(network.getNetId(), newValue);
        }
        return changed;
    }

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network,
                NetworkCapabilities networkCapabilities) {
            if (network == null || networkCapabilities == null) return;

            synchronized (mNetworkPoliciesSecondLock) {
                final boolean newMetered = !networkCapabilities
                        .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                final boolean meteredChanged = updateCapabilityChange(
                        mNetworkMetered, newMetered, network);

                final boolean newRoaming = !networkCapabilities
                        .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
                final boolean roamingChanged = updateCapabilityChange(
                        mNetworkRoaming, newRoaming, network);

                if (meteredChanged || roamingChanged) {
                    mLogger.meterednessChanged(network.getNetId(), newMetered);
                    updateNetworkRulesNL();
                }
            }
        }
    };

    /**
     * Observer that watches for {@link INetworkManagementService} alerts.
     */
    final private INetworkManagementEventObserver mAlertObserver
            = new BaseNetworkObserver() {
        @Override
        public void limitReached(String limitName, String iface) {
            // only someone like NMS should be calling us
            NetworkStack.checkNetworkStackPermission(mContext);

            if (!LIMIT_GLOBAL_ALERT.equals(limitName)) {
                mHandler.obtainMessage(MSG_LIMIT_REACHED, iface).sendToTarget();
            }
        }
    };

    /**
     * Check {@link NetworkPolicy} against current {@link INetworkStatsService}
     * to show visible notifications as needed.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    void updateNotificationsNL() {
        if (LOGV) Slog.v(TAG, "updateNotificationsNL()");
        Trace.traceBegin(TRACE_TAG_NETWORK, "updateNotificationsNL");

        // keep track of previously active notifications
        final ArraySet<NotificationId> beforeNotifs = new ArraySet<NotificationId>(mActiveNotifs);
        mActiveNotifs.clear();

        // TODO: when switching to kernel notifications, compute next future
        // cycle boundary to recompute notifications.

        // examine stats for each active policy
        final long now = mClock.millis();
        for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
            final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
            final int subId = findRelevantSubIdNL(policy.template);

            // ignore policies that aren't relevant to user
            if (subId == INVALID_SUBSCRIPTION_ID) continue;
            if (!policy.hasCycle()) continue;

            final Pair<ZonedDateTime, ZonedDateTime> cycle = NetworkPolicyManager
                    .cycleIterator(policy).next();
            final long cycleStart = cycle.first.toInstant().toEpochMilli();
            final long cycleEnd = cycle.second.toInstant().toEpochMilli();
            final long totalBytes = getTotalBytes(policy.template, cycleStart, cycleEnd);

            // Carrier might want to manage notifications themselves
            final PersistableBundle config = mSubIdToCarrierConfig.get(subId);
            if (!CarrierConfigManager.isConfigForIdentifiedCarrier(config)) {
                if (LOGV) Slog.v(TAG, "isConfigForIdentifiedCarrier returned false");
                // Don't show notifications until we confirm that the loaded config is from an
                // identified carrier, which may want to manage their own notifications. This method
                // should be called every time the carrier config changes anyways, and there's no
                // reason to alert if there isn't a carrier.
                continue;
            }

            final boolean notifyWarning = getBooleanDefeatingNullable(config,
                    KEY_DATA_WARNING_NOTIFICATION_BOOL, true);
            final boolean notifyLimit = getBooleanDefeatingNullable(config,
                    KEY_DATA_LIMIT_NOTIFICATION_BOOL, true);
            final boolean notifyRapid = getBooleanDefeatingNullable(config,
                    KEY_DATA_RAPID_NOTIFICATION_BOOL, true);

            // Notify when data usage is over warning
            if (notifyWarning) {
                if (policy.isOverWarning(totalBytes) && !policy.isOverLimit(totalBytes)) {
                    final boolean snoozedThisCycle = policy.lastWarningSnooze >= cycleStart;
                    if (!snoozedThisCycle) {
                        enqueueNotification(policy, TYPE_WARNING, totalBytes, null);
                    }
                }
            }

            // Notify when data usage is over limit
            if (notifyLimit) {
                if (policy.isOverLimit(totalBytes)) {
                    final boolean snoozedThisCycle = policy.lastLimitSnooze >= cycleStart;
                    if (snoozedThisCycle) {
                        enqueueNotification(policy, TYPE_LIMIT_SNOOZED, totalBytes, null);
                    } else {
                        enqueueNotification(policy, TYPE_LIMIT, totalBytes, null);
                        notifyOverLimitNL(policy.template);
                    }
                } else {
                    notifyUnderLimitNL(policy.template);
                }
            }

            // Warn if average usage over last 4 days is on track to blow pretty
            // far past the plan limits.
            if (notifyRapid && policy.limitBytes != LIMIT_DISABLED) {
                final long recentDuration = TimeUnit.DAYS.toMillis(4);
                final long recentStart = now - recentDuration;
                final long recentEnd = now;
                final long recentBytes = getTotalBytes(policy.template, recentStart, recentEnd);

                final long cycleDuration = cycleEnd - cycleStart;
                final long projectedBytes = (recentBytes * cycleDuration) / recentDuration;
                final long alertBytes = (policy.limitBytes * 3) / 2;

                if (LOGD) {
                    Slog.d(TAG, "Rapid usage considering recent " + recentBytes + " projected "
                            + projectedBytes + " alert " + alertBytes);
                }

                final boolean snoozedRecently = policy.lastRapidSnooze >= now
                        - DateUtils.DAY_IN_MILLIS;
                if (projectedBytes > alertBytes && !snoozedRecently) {
                    enqueueNotification(policy, TYPE_RAPID, 0,
                            findRapidBlame(policy.template, recentStart, recentEnd));
                }
            }
        }

        // cancel stale notifications that we didn't renew above
        for (int i = beforeNotifs.size()-1; i >= 0; i--) {
            final NotificationId notificationId = beforeNotifs.valueAt(i);
            if (!mActiveNotifs.contains(notificationId)) {
                cancelNotification(notificationId);
            }
        }

        Trace.traceEnd(TRACE_TAG_NETWORK);
    }

    /**
     * Attempt to find a specific app to blame for rapid data usage during the
     * given time period.
     */
    private @Nullable ApplicationInfo findRapidBlame(NetworkTemplate template,
            long start, long end) {
        long totalBytes = 0;
        long maxBytes = 0;
        int maxUid = 0;

        final NetworkStats stats = getNetworkUidBytes(template, start, end);
        NetworkStats.Entry entry = null;
        for (int i = 0; i < stats.size(); i++) {
            entry = stats.getValues(i, entry);
            final long bytes = entry.rxBytes + entry.txBytes;
            totalBytes += bytes;
            if (bytes > maxBytes) {
                maxBytes = bytes;
                maxUid = entry.uid;
            }
        }

        // Only point blame if the majority of usage was done by a single app.
        // TODO: support shared UIDs
        if (maxBytes > 0 && maxBytes > totalBytes / 2) {
            final String[] packageNames = mContext.getPackageManager().getPackagesForUid(maxUid);
            if (packageNames != null && packageNames.length == 1) {
                try {
                    return mContext.getPackageManager().getApplicationInfo(packageNames[0],
                            MATCH_ANY_USER | MATCH_DISABLED_COMPONENTS | MATCH_DIRECT_BOOT_AWARE
                                    | MATCH_DIRECT_BOOT_UNAWARE | MATCH_UNINSTALLED_PACKAGES);
                } catch (NameNotFoundException ignored) {
                }
            }
        }

        return null;
    }

    /**
     * Test if given {@link NetworkTemplate} is relevant to user based on
     * current device state, such as when
     * {@link TelephonyManager#getSubscriberId()} matches. This is regardless of
     * data connection status.
     *
     * @return relevant subId, or {@link #INVALID_SUBSCRIPTION_ID} when no
     *         matching subId found.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private int findRelevantSubIdNL(NetworkTemplate template) {
        // Mobile template is relevant when any active subscriber matches
        for (int i = 0; i < mSubIdToSubscriberId.size(); i++) {
            final int subId = mSubIdToSubscriberId.keyAt(i);
            final String subscriberId = mSubIdToSubscriberId.valueAt(i);
            final NetworkIdentity probeIdent = new NetworkIdentity(TYPE_MOBILE,
                    TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false, true,
                    true, OEM_NONE);
            /* While OEM_NONE indicates "any non OEM managed network", OEM_NONE is meant to be a
             * placeholder value here. The probeIdent is matched against a NetworkTemplate which
             * should have its OEM managed value set to OEM_MANAGED_ALL, which will cause the
             * template to match probeIdent without regard to OEM managed status. */
            if (template.matches(probeIdent)) {
                return subId;
            }
        }
        return INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Notify that given {@link NetworkTemplate} is over
     * {@link NetworkPolicy#limitBytes}, potentially showing dialog to user.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private void notifyOverLimitNL(NetworkTemplate template) {
        if (!mOverLimitNotified.contains(template)) {
            mContext.startActivity(buildNetworkOverLimitIntent(mContext.getResources(), template));
            mOverLimitNotified.add(template);
        }
    }

    @GuardedBy("mNetworkPoliciesSecondLock")
    private void notifyUnderLimitNL(NetworkTemplate template) {
        mOverLimitNotified.remove(template);
    }

    /**
     * Show notification for combined {@link NetworkPolicy} and specific type,
     * like {@link #TYPE_LIMIT}. Okay to call multiple times.
     */
    private void enqueueNotification(NetworkPolicy policy, int type, long totalBytes,
            ApplicationInfo rapidBlame) {
        final NotificationId notificationId = new NotificationId(policy, type);
        final Notification.Builder builder =
                new Notification.Builder(mContext, SystemNotificationChannels.NETWORK_ALERTS);
        builder.setOnlyAlertOnce(true);
        builder.setWhen(0L);
        builder.setColor(mContext.getColor(
                com.android.internal.R.color.system_notification_accent_color));

        final Resources res = mContext.getResources();
        final CharSequence title;
        final CharSequence body;
        switch (type) {
            case TYPE_WARNING: {
                title = res.getText(R.string.data_usage_warning_title);
                body = res.getString(R.string.data_usage_warning_body,
                        Formatter.formatFileSize(mContext, totalBytes, Formatter.FLAG_IEC_UNITS));

                builder.setSmallIcon(R.drawable.stat_notify_error);

                final Intent snoozeIntent = buildSnoozeWarningIntent(policy.template);
                builder.setDeleteIntent(PendingIntent.getBroadcast(
                        mContext, 0, snoozeIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));

                final Intent viewIntent = buildViewDataUsageIntent(res, policy.template);
                // TODO: Resolve to single code path.
                if (UserManager.isHeadlessSystemUserMode()) {
                    builder.setContentIntent(PendingIntent.getActivityAsUser(
                            mContext, 0, viewIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE,
                            /* options= */ null, UserHandle.CURRENT));
                } else {
                    builder.setContentIntent(PendingIntent.getActivity(
                            mContext, 0, viewIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));
                }
                break;
            }
            case TYPE_LIMIT: {
                switch (policy.template.getMatchRule()) {
                    case MATCH_MOBILE:
                        title = res.getText(R.string.data_usage_mobile_limit_title);
                        break;
                    case MATCH_WIFI:
                        title = res.getText(R.string.data_usage_wifi_limit_title);
                        break;
                    default:
                        return;
                }
                body = res.getText(R.string.data_usage_limit_body);

                builder.setOngoing(true);
                builder.setSmallIcon(R.drawable.stat_notify_disabled_data);

                final Intent intent = buildNetworkOverLimitIntent(res, policy.template);
                // TODO: Resolve to single code path.
                if (UserManager.isHeadlessSystemUserMode()) {
                    builder.setContentIntent(PendingIntent.getActivityAsUser(
                            mContext, 0, intent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE,
                            /* options= */ null, UserHandle.CURRENT));
                } else {
                    builder.setContentIntent(PendingIntent.getActivity(
                            mContext, 0, intent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));
                }
                break;
            }
            case TYPE_LIMIT_SNOOZED: {
                switch (policy.template.getMatchRule()) {
                    case MATCH_MOBILE:
                        title = res.getText(R.string.data_usage_mobile_limit_snoozed_title);
                        break;
                    case MATCH_WIFI:
                        title = res.getText(R.string.data_usage_wifi_limit_snoozed_title);
                        break;
                    default:
                        return;
                }
                final long overBytes = totalBytes - policy.limitBytes;
                body = res.getString(R.string.data_usage_limit_snoozed_body,
                        Formatter.formatFileSize(mContext, overBytes, Formatter.FLAG_IEC_UNITS));

                builder.setOngoing(true);
                builder.setSmallIcon(R.drawable.stat_notify_error);
                builder.setChannelId(SystemNotificationChannels.NETWORK_STATUS);

                final Intent intent = buildViewDataUsageIntent(res, policy.template);
                // TODO: Resolve to single code path.
                if (UserManager.isHeadlessSystemUserMode()) {
                    builder.setContentIntent(PendingIntent.getActivityAsUser(
                            mContext, 0, intent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE,
                            /* options= */ null, UserHandle.CURRENT));
                } else {
                    builder.setContentIntent(PendingIntent.getActivity(
                            mContext, 0, intent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));
                }
                break;
            }
            case TYPE_RAPID: {
                title = res.getText(R.string.data_usage_rapid_title);
                if (rapidBlame != null) {
                    body = res.getString(R.string.data_usage_rapid_app_body,
                            rapidBlame.loadLabel(mContext.getPackageManager()));
                } else {
                    body = res.getString(R.string.data_usage_rapid_body);
                }

                builder.setSmallIcon(R.drawable.stat_notify_error);

                final Intent snoozeIntent = buildSnoozeRapidIntent(policy.template);
                builder.setDeleteIntent(PendingIntent.getBroadcast(
                        mContext, 0, snoozeIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));

                final Intent viewIntent = buildViewDataUsageIntent(res, policy.template);
                // TODO: Resolve to single code path.
                if (UserManager.isHeadlessSystemUserMode()) {
                    builder.setContentIntent(PendingIntent.getActivityAsUser(
                            mContext, 0, viewIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE,
                            /* options= */ null, UserHandle.CURRENT));
                } else {
                    builder.setContentIntent(PendingIntent.getActivity(
                            mContext, 0, viewIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));
                }
                break;
            }
            default: {
                return;
            }
        }

        builder.setTicker(title);
        builder.setContentTitle(title);
        builder.setContentText(body);
        builder.setStyle(new Notification.BigTextStyle().bigText(body));

        mContext.getSystemService(NotificationManager.class).notifyAsUser(notificationId.getTag(),
                notificationId.getId(), builder.build(), UserHandle.ALL);
        mActiveNotifs.add(notificationId);
    }

    private void cancelNotification(NotificationId notificationId) {
        mContext.getSystemService(NotificationManager.class).cancel(notificationId.getTag(),
                notificationId.getId());
    }

    /**
     * Receiver that watches for {@link IConnectivityManager} to claim network
     * interfaces. Used to apply {@link NetworkPolicy} to matching networks.
     */
    private BroadcastReceiver mConnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified NETWORK_STACK
            // permission above.
            updateNetworksInternal();
        }
    };

    private void updateNetworksInternal() {
        // Get all of our cross-process communication with telephony out of
        // the way before we acquire internal locks.
        updateSubscriptions();

        synchronized (mUidRulesFirstLock) {
            synchronized (mNetworkPoliciesSecondLock) {
                ensureActiveMobilePolicyAL();
                normalizePoliciesNL();
                updateNetworkEnabledNL();
                updateNetworkRulesNL();
                updateNotificationsNL();
            }
        }
    }

    @VisibleForTesting
    void updateNetworks() throws InterruptedException {
        updateNetworksInternal();
        final CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
    }

    @VisibleForTesting
    Handler getHandlerForTesting() {
        return mHandler;
    }

    /**
     * Update mobile policies with data cycle information from {@link CarrierConfigManager}
     * if necessary.
     *
     * @param subId that has its associated NetworkPolicy updated if necessary
     * @return if any policies were updated
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private boolean maybeUpdateMobilePolicyCycleAL(int subId, String subscriberId) {
        if (LOGV) Slog.v(TAG, "maybeUpdateMobilePolicyCycleAL()");

        // find and update the mobile NetworkPolicy for this subscriber id
        boolean policyUpdated = false;
        final NetworkIdentity probeIdent = new NetworkIdentity(TYPE_MOBILE,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false, true, true,
                OEM_NONE);
        for (int i = mNetworkPolicy.size() - 1; i >= 0; i--) {
            final NetworkTemplate template = mNetworkPolicy.keyAt(i);
            if (template.matches(probeIdent)) {
                final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
                policyUpdated |= updateDefaultMobilePolicyAL(subId, policy);
            }
        }
        return policyUpdated;
    }

    /**
     * Returns the cycle day that should be used for a mobile NetworkPolicy.
     *
     * It attempts to get an appropriate cycle day from the passed in CarrierConfig. If it's unable
     * to do so, it returns the fallback value.
     *
     * @param config The CarrierConfig to read the value from.
     * @param fallbackCycleDay to return if the CarrierConfig can't be read.
     * @return cycleDay to use in the mobile NetworkPolicy.
     */
    @VisibleForTesting
    int getCycleDayFromCarrierConfig(@Nullable PersistableBundle config,
            int fallbackCycleDay) {
        if (config == null) {
            return fallbackCycleDay;
        }
        int cycleDay =
                config.getInt(CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT);
        if (cycleDay == DATA_CYCLE_USE_PLATFORM_DEFAULT) {
            return fallbackCycleDay;
        }
        // validate cycleDay value
        final Calendar cal = Calendar.getInstance();
        if (cycleDay < cal.getMinimum(Calendar.DAY_OF_MONTH) ||
                cycleDay > cal.getMaximum(Calendar.DAY_OF_MONTH)) {
            Slog.e(TAG, "Invalid date in "
                    + "CarrierConfigManager.KEY_MONTHLY_DATA_CYCLE_DAY_INT: " + cycleDay);
            return fallbackCycleDay;
        }
        return cycleDay;
    }

    /**
     * Returns the warning bytes that should be used for a mobile NetworkPolicy.
     *
     * It attempts to get an appropriate value from the passed in CarrierConfig. If it's unable
     * to do so, it returns the fallback value.
     *
     * @param config The CarrierConfig to read the value from.
     * @param fallbackWarningBytes to return if the CarrierConfig can't be read.
     * @return warningBytes to use in the mobile NetworkPolicy.
     */
    @VisibleForTesting
    long getWarningBytesFromCarrierConfig(@Nullable PersistableBundle config,
            long fallbackWarningBytes) {
        if (config == null) {
            return fallbackWarningBytes;
        }
        long warningBytes =
                config.getLong(CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG);

        if (warningBytes == DATA_CYCLE_THRESHOLD_DISABLED) {
            return WARNING_DISABLED;
        } else if (warningBytes == DATA_CYCLE_USE_PLATFORM_DEFAULT) {
            return getPlatformDefaultWarningBytes();
        } else if (warningBytes < 0) {
            Slog.e(TAG, "Invalid value in "
                    + "CarrierConfigManager.KEY_DATA_WARNING_THRESHOLD_BYTES_LONG; expected a "
                    + "non-negative value but got: " + warningBytes);
            return fallbackWarningBytes;
        }

        return warningBytes;
    }

    /**
     * Returns the limit bytes that should be used for a mobile NetworkPolicy.
     *
     * It attempts to get an appropriate value from the passed in CarrierConfig. If it's unable
     * to do so, it returns the fallback value.
     *
     * @param config The CarrierConfig to read the value from.
     * @param fallbackLimitBytes to return if the CarrierConfig can't be read.
     * @return limitBytes to use in the mobile NetworkPolicy.
     */
    @VisibleForTesting
    long getLimitBytesFromCarrierConfig(@Nullable PersistableBundle config,
            long fallbackLimitBytes) {
        if (config == null) {
            return fallbackLimitBytes;
        }
        long limitBytes =
                config.getLong(CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG);

        if (limitBytes == DATA_CYCLE_THRESHOLD_DISABLED) {
            return LIMIT_DISABLED;
        } else if (limitBytes == DATA_CYCLE_USE_PLATFORM_DEFAULT) {
            return getPlatformDefaultLimitBytes();
        } else if (limitBytes < 0) {
            Slog.e(TAG, "Invalid value in "
                    + "CarrierConfigManager.KEY_DATA_LIMIT_THRESHOLD_BYTES_LONG; expected a "
                    + "non-negative value but got: " + limitBytes);
            return fallbackLimitBytes;
        }
        return limitBytes;
    }

    /**
     * Receiver that watches for {@link CarrierConfigManager} to be changed.
     */
    private BroadcastReceiver mCarrierConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // No need to do a permission check, because the ACTION_CARRIER_CONFIG_CHANGED
            // broadcast is protected and can't be spoofed. Runs on a background handler thread.

            if (!intent.hasExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX)) {
                return;
            }
            final int subId = intent.getIntExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, -1);

            // Get all of our cross-process communication with telephony out of
            // the way before we acquire internal locks.
            updateSubscriptions();

            synchronized (mUidRulesFirstLock) {
                synchronized (mNetworkPoliciesSecondLock) {
                    final String subscriberId = mSubIdToSubscriberId.get(subId, null);
                    if (subscriberId != null) {
                        ensureActiveMobilePolicyAL(subId, subscriberId);
                        maybeUpdateMobilePolicyCycleAL(subId, subscriberId);
                    } else {
                        Slog.wtf(TAG, "Missing subscriberId for subId " + subId);
                    }

                    // update network and notification rules, as the data cycle changed and it's
                    // possible that we should be triggering warnings/limits now
                    handleNetworkPoliciesUpdateAL(true);
                }
            }
        }
    };

    /**
     * Handles all tasks that need to be run after a new network policy has been set, or an existing
     * one has been updated.
     *
     * @param shouldNormalizePolicies true iff network policies need to be normalized after the
     *                                update.
     */
    @GuardedBy({"mUidRulesFirstLock", "mNetworkPoliciesSecondLock"})
    void handleNetworkPoliciesUpdateAL(boolean shouldNormalizePolicies) {
        if (shouldNormalizePolicies) {
            normalizePoliciesNL();
        }
        updateNetworkEnabledNL();
        updateNetworkRulesNL();
        updateNotificationsNL();
        writePolicyAL();
    }

    /**
     * Proactively control network data connections when they exceed
     * {@link NetworkPolicy#limitBytes}.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    void updateNetworkEnabledNL() {
        if (LOGV) Slog.v(TAG, "updateNetworkEnabledNL()");
        Trace.traceBegin(TRACE_TAG_NETWORK, "updateNetworkEnabledNL");

        // TODO: reset any policy-disabled networks when any policy is removed
        // completely, which is currently rare case.

        final long startTime = mStatLogger.getTime();

        for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
            final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
            // shortcut when policy has no limit
            if (policy.limitBytes == LIMIT_DISABLED || !policy.hasCycle()) {
                setNetworkTemplateEnabled(policy.template, true);
                continue;
            }

            final Pair<ZonedDateTime, ZonedDateTime> cycle = NetworkPolicyManager
                    .cycleIterator(policy).next();
            final long start = cycle.first.toInstant().toEpochMilli();
            final long end = cycle.second.toInstant().toEpochMilli();
            final long totalBytes = getTotalBytes(policy.template, start, end);

            // disable data connection when over limit and not snoozed
            final boolean overLimitWithoutSnooze = policy.isOverLimit(totalBytes)
                    && policy.lastLimitSnooze < start;
            final boolean networkEnabled = !overLimitWithoutSnooze;

            setNetworkTemplateEnabled(policy.template, networkEnabled);
        }

        mStatLogger.logDurationStat(Stats.UPDATE_NETWORK_ENABLED, startTime);
        Trace.traceEnd(TRACE_TAG_NETWORK);
    }

    /**
     * Proactively disable networks that match the given
     * {@link NetworkTemplate}.
     */
    private void setNetworkTemplateEnabled(NetworkTemplate template, boolean enabled) {
        // Don't call setNetworkTemplateEnabledInner() directly because we may have a lock
        // held. Call it via the handler.
        mHandler.obtainMessage(MSG_SET_NETWORK_TEMPLATE_ENABLED, enabled ? 1 : 0, 0, template)
                .sendToTarget();
    }

    private void setNetworkTemplateEnabledInner(NetworkTemplate template, boolean enabled) {
        // TODO: reach into ConnectivityManager to proactively disable bringing
        // up this network, since we know that traffic will be blocked.

        if (template.getMatchRule() == MATCH_MOBILE) {
            // If mobile data usage hits the limit or if the user resumes the data, we need to
            // notify telephony.

            final IntArray matchingSubIds = new IntArray();
            synchronized (mNetworkPoliciesSecondLock) {
                for (int i = 0; i < mSubIdToSubscriberId.size(); i++) {
                    final int subId = mSubIdToSubscriberId.keyAt(i);
                    final String subscriberId = mSubIdToSubscriberId.valueAt(i);

                    final NetworkIdentity probeIdent = new NetworkIdentity(TYPE_MOBILE,
                            TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false, true,
                            true, OEM_NONE);
                    // Template is matched when subscriber id matches.
                    if (template.matches(probeIdent)) {
                        matchingSubIds.add(subId);
                    }
                }
            }

            // Only talk with telephony outside of locks
            final TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
            for (int i = 0; i < matchingSubIds.size(); i++) {
                final int subId = matchingSubIds.get(i);
                tm.createForSubscriptionId(subId).setPolicyDataEnabled(enabled);
            }
        }
    }

    /**
     * Collect all ifaces from a {@link NetworkStateSnapshot} into the given set.
     */
    private static void collectIfaces(ArraySet<String> ifaces, NetworkStateSnapshot snapshot) {
        ifaces.addAll(snapshot.linkProperties.getAllInterfaceNames());
    }

    /**
     * Examine all currently active subscriptions from
     * {@link SubscriptionManager#getActiveSubscriptionInfoList()} and update
     * internal data structures.
     * <p>
     * Callers <em>must not</em> hold any locks when this method called.
     */
    void updateSubscriptions() {
        if (LOGV) Slog.v(TAG, "updateSubscriptions()");
        Trace.traceBegin(TRACE_TAG_NETWORK, "updateSubscriptions");

        final TelephonyManager tm = mContext.getSystemService(TelephonyManager.class);
        final SubscriptionManager sm = mContext.getSystemService(SubscriptionManager.class);
        final List<SubscriptionInfo> subList = CollectionUtils.emptyIfNull(
                sm.getActiveSubscriptionInfoList());

        final List<String[]> mergedSubscriberIdsList = new ArrayList();
        final SparseArray<String> subIdToSubscriberId = new SparseArray<>(subList.size());
        final SparseArray<PersistableBundle> subIdToCarrierConfig =
                new SparseArray<PersistableBundle>();
        for (final SubscriptionInfo sub : subList) {
            final int subId = sub.getSubscriptionId();
            final TelephonyManager tmSub = tm.createForSubscriptionId(subId);
            final String subscriberId = tmSub.getSubscriberId();
            if (!TextUtils.isEmpty(subscriberId)) {
                subIdToSubscriberId.put(tmSub.getSubscriptionId(), subscriberId);
            } else {
                Slog.wtf(TAG, "Missing subscriberId for subId " + tmSub.getSubscriptionId());
            }

            final String[] mergedSubscriberId = ArrayUtils.defeatNullable(
                    tmSub.getMergedImsisFromGroup());
            mergedSubscriberIdsList.add(mergedSubscriberId);

            final PersistableBundle config = mCarrierConfigManager.getConfigForSubId(subId);
            if (config != null) {
                subIdToCarrierConfig.put(subId, config);
            } else {
                Slog.e(TAG, "Missing CarrierConfig for subId " + subId);
            }
        }

        synchronized (mNetworkPoliciesSecondLock) {
            mSubIdToSubscriberId.clear();
            for (int i = 0; i < subIdToSubscriberId.size(); i++) {
                mSubIdToSubscriberId.put(subIdToSubscriberId.keyAt(i),
                        subIdToSubscriberId.valueAt(i));
            }

            mMergedSubscriberIds = mergedSubscriberIdsList;

            mSubIdToCarrierConfig.clear();
            for (int i = 0; i < subIdToCarrierConfig.size(); i++) {
                mSubIdToCarrierConfig.put(subIdToCarrierConfig.keyAt(i),
                        subIdToCarrierConfig.valueAt(i));
            }
        }

        Trace.traceEnd(TRACE_TAG_NETWORK);
    }

    /**
     * Examine all connected {@link NetworkStateSnapshot}, looking for
     * {@link NetworkPolicy} that need to be enforced. When matches found, set
     * remaining quota based on usage cycle and historical stats.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    void updateNetworkRulesNL() {
        if (LOGV) Slog.v(TAG, "updateNetworkRulesNL()");
        Trace.traceBegin(TRACE_TAG_NETWORK, "updateNetworkRulesNL");

        final List<NetworkStateSnapshot> snapshots = mConnManager.getAllNetworkStateSnapshot();

        // First, generate identities of all connected networks so we can
        // quickly compare them against all defined policies below.
        mNetIdToSubId.clear();
        final ArrayMap<NetworkStateSnapshot, NetworkIdentity> identified = new ArrayMap<>();
        for (final NetworkStateSnapshot snapshot : snapshots) {
            mNetIdToSubId.put(snapshot.network.getNetId(), parseSubId(snapshot));

            // Policies matched by NPMS only match by subscriber ID or by ssid. Thus subtype
            // in the object created here is never used and its value doesn't matter, so use
            // NETWORK_TYPE_UNKNOWN.
            final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, snapshot,
                    true, TelephonyManager.NETWORK_TYPE_UNKNOWN /* subType */);
            identified.put(snapshot, ident);
        }

        final ArraySet<String> newMeteredIfaces = new ArraySet<>();
        long lowestRule = Long.MAX_VALUE;

        // For every well-defined policy, compute remaining data based on
        // current cycle and historical stats, and push to kernel.
        final ArraySet<String> matchingIfaces = new ArraySet<>();
        for (int i = mNetworkPolicy.size() - 1; i >= 0; i--) {
           final NetworkPolicy policy = mNetworkPolicy.valueAt(i);

            // Collect all ifaces that match this policy
            matchingIfaces.clear();
            for (int j = identified.size() - 1; j >= 0; j--) {
                if (policy.template.matches(identified.valueAt(j))) {
                    collectIfaces(matchingIfaces, identified.keyAt(j));
                }
            }

            if (LOGD) {
                Slog.d(TAG, "Applying " + policy + " to ifaces " + matchingIfaces);
            }

            final boolean hasWarning = policy.warningBytes != LIMIT_DISABLED;
            final boolean hasLimit = policy.limitBytes != LIMIT_DISABLED;
            long limitBytes = Long.MAX_VALUE;
            if (hasLimit && policy.hasCycle()) {
                final Pair<ZonedDateTime, ZonedDateTime> cycle = NetworkPolicyManager
                        .cycleIterator(policy).next();
                final long start = cycle.first.toInstant().toEpochMilli();
                final long end = cycle.second.toInstant().toEpochMilli();
                final long totalBytes = getTotalBytes(policy.template, start, end);

                if (policy.lastLimitSnooze < start) {
                    // remaining "quota" bytes are based on total usage in
                    // current cycle. kernel doesn't like 0-byte rules, so we
                    // set 1-byte quota and disable the radio later.
                    limitBytes = Math.max(1, policy.limitBytes - totalBytes);
                }
            }

            if (hasLimit || policy.metered) {
                if (matchingIfaces.size() > 1) {
                    // TODO: switch to shared quota once NMS supports
                    Slog.w(TAG, "shared quota unsupported; generating rule for each iface");
                }

                // Set the interface limit. For interfaces which has no cycle, or metered with
                // no policy limit, or snoozed limit notification; we still need to put iptables
                // rule hooks to restrict apps for data saver, so push really high quota.
                for (int j = matchingIfaces.size() - 1; j >= 0; j--) {
                    final String iface = matchingIfaces.valueAt(j);
                    setInterfaceQuotaAsync(iface, limitBytes);
                    newMeteredIfaces.add(iface);
                }
            }

            // keep track of lowest warning or limit of active policies
            if (hasWarning && policy.warningBytes < lowestRule) {
                lowestRule = policy.warningBytes;
            }
            if (hasLimit && policy.limitBytes < lowestRule) {
                lowestRule = policy.limitBytes;
            }
        }

        // One final pass to catch any metered ifaces that don't have explicitly
        // defined policies; typically Wi-Fi networks.
        for (final NetworkStateSnapshot snapshot : snapshots) {
            if (!snapshot.networkCapabilities.hasCapability(NET_CAPABILITY_NOT_METERED)) {
                matchingIfaces.clear();
                collectIfaces(matchingIfaces, snapshot);
                for (int j = matchingIfaces.size() - 1; j >= 0; j--) {
                    final String iface = matchingIfaces.valueAt(j);
                    if (!newMeteredIfaces.contains(iface)) {
                        setInterfaceQuotaAsync(iface, Long.MAX_VALUE);
                        newMeteredIfaces.add(iface);
                    }
                }
            }
        }

        // Remove quota from any interfaces that are no longer metered.
        synchronized (mMeteredIfacesLock) {
            for (int i = mMeteredIfaces.size() - 1; i >= 0; i--) {
                final String iface = mMeteredIfaces.valueAt(i);
                if (!newMeteredIfaces.contains(iface)) {
                    removeInterfaceQuotaAsync(iface);
                }
            }
            mMeteredIfaces = newMeteredIfaces;
        }

        final ContentResolver cr = mContext.getContentResolver();
        final boolean quotaEnabled = Settings.Global.getInt(cr,
                NETPOLICY_QUOTA_ENABLED, 1) != 0;
        final long quotaUnlimited = Settings.Global.getLong(cr,
                NETPOLICY_QUOTA_UNLIMITED, QUOTA_UNLIMITED_DEFAULT);
        final float quotaLimited = Settings.Global.getFloat(cr,
                NETPOLICY_QUOTA_LIMITED, QUOTA_LIMITED_DEFAULT);

        // Finally, calculate our opportunistic quotas
        mSubscriptionOpportunisticQuota.clear();
        for (final NetworkStateSnapshot snapshot : snapshots) {
            if (!quotaEnabled) continue;
            if (snapshot.network == null) continue;
            final int subId = getSubIdLocked(snapshot.network);
            final SubscriptionPlan plan = getPrimarySubscriptionPlanLocked(subId);
            if (plan == null) continue;

            final long quotaBytes;
            final long limitBytes = plan.getDataLimitBytes();
            if (!snapshot.networkCapabilities.hasCapability(NET_CAPABILITY_NOT_ROAMING)) {
                // Clamp to 0 when roaming
                quotaBytes = 0;
            } else if (limitBytes == SubscriptionPlan.BYTES_UNKNOWN) {
                quotaBytes = OPPORTUNISTIC_QUOTA_UNKNOWN;
            } else if (limitBytes == SubscriptionPlan.BYTES_UNLIMITED) {
                // Unlimited data; let's use 20MiB/day (600MiB/month)
                quotaBytes = quotaUnlimited;
            } else {
                // Limited data; let's only use 10% of remaining budget
                final Range<ZonedDateTime> cycle = plan.cycleIterator().next();
                final long start = cycle.getLower().toInstant().toEpochMilli();
                final long end = cycle.getUpper().toInstant().toEpochMilli();
                final Instant now = mClock.instant();
                final long startOfDay = ZonedDateTime.ofInstant(now, cycle.getLower().getZone())
                        .truncatedTo(ChronoUnit.DAYS)
                        .toInstant().toEpochMilli();
                final long totalBytes = getTotalBytes(
                        NetworkTemplate.buildTemplateMobileAll(snapshot.subscriberId),
                        start, startOfDay);
                final long remainingBytes = limitBytes - totalBytes;
                // Number of remaining days including current day
                final long remainingDays =
                        1 + ((end - now.toEpochMilli() - 1) / TimeUnit.DAYS.toMillis(1));

                quotaBytes = Math.max(0, (long) ((remainingBytes / remainingDays) * quotaLimited));
            }

            mSubscriptionOpportunisticQuota.put(subId, quotaBytes);
        }

        final String[] meteredIfaces;
        synchronized (mMeteredIfacesLock) {
            meteredIfaces = mMeteredIfaces.toArray(new String[mMeteredIfaces.size()]);
        }
        mHandler.obtainMessage(MSG_METERED_IFACES_CHANGED, meteredIfaces).sendToTarget();

        mHandler.obtainMessage(MSG_ADVISE_PERSIST_THRESHOLD, lowestRule).sendToTarget();

        Trace.traceEnd(TRACE_TAG_NETWORK);
    }

    /**
     * Once any {@link #mNetworkPolicy} are loaded from disk, ensure that we
     * have at least a default mobile policy defined.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private void ensureActiveMobilePolicyAL() {
        if (LOGV) Slog.v(TAG, "ensureActiveMobilePolicyAL()");
        if (mSuppressDefaultPolicy) return;

        for (int i = 0; i < mSubIdToSubscriberId.size(); i++) {
            final int subId = mSubIdToSubscriberId.keyAt(i);
            final String subscriberId = mSubIdToSubscriberId.valueAt(i);

            ensureActiveMobilePolicyAL(subId, subscriberId);
        }
    }

    /**
     * Once any {@link #mNetworkPolicy} are loaded from disk, ensure that we
     * have at least a default mobile policy defined.
     *
     * @param subId to build a default policy for
     * @param subscriberId that we check for an existing policy
     * @return true if a mobile network policy was added, or false one already existed.
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private boolean ensureActiveMobilePolicyAL(int subId, String subscriberId) {
        // Poke around to see if we already have a policy
        final NetworkIdentity probeIdent = new NetworkIdentity(TYPE_MOBILE,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false, true, true,
                OEM_NONE);
        for (int i = mNetworkPolicy.size() - 1; i >= 0; i--) {
            final NetworkTemplate template = mNetworkPolicy.keyAt(i);
            if (template.matches(probeIdent)) {
                if (LOGD) {
                    Slog.d(TAG, "Found template " + template + " which matches subscriber "
                            + NetworkIdentityUtils.scrubSubscriberId(subscriberId));
                }
                return false;
            }
        }

        Slog.i(TAG, "No policy for subscriber "
                + NetworkIdentityUtils.scrubSubscriberId(subscriberId)
                + "; generating default policy");
        final NetworkPolicy policy = buildDefaultMobilePolicy(subId, subscriberId);
        addNetworkPolicyAL(policy);
        return true;
    }

    private long getPlatformDefaultWarningBytes() {
        final int dataWarningConfig = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkPolicyDefaultWarning);
        if (dataWarningConfig == WARNING_DISABLED) {
            return WARNING_DISABLED;
        } else {
            return dataWarningConfig * MB_IN_BYTES;
        }
    }

    private long getPlatformDefaultLimitBytes() {
        return LIMIT_DISABLED;
    }

    @VisibleForTesting
    NetworkPolicy buildDefaultMobilePolicy(int subId, String subscriberId) {
        final NetworkTemplate template = buildTemplateMobileAll(subscriberId);
        final RecurrenceRule cycleRule = NetworkPolicy
                .buildRule(ZonedDateTime.now().getDayOfMonth(), ZoneId.systemDefault());
        final NetworkPolicy policy = new NetworkPolicy(template, cycleRule,
                getPlatformDefaultWarningBytes(), getPlatformDefaultLimitBytes(),
                SNOOZE_NEVER, SNOOZE_NEVER, true, true);
        synchronized (mUidRulesFirstLock) {
            synchronized (mNetworkPoliciesSecondLock) {
                updateDefaultMobilePolicyAL(subId, policy);
            }
        }
        return policy;
    }

    /**
     * Update the given {@link NetworkPolicy} based on any carrier-provided
     * defaults via {@link SubscriptionPlan} or {@link CarrierConfigManager}.
     * Leaves policy untouched if the user has modified it.
     *
     * @return if the policy was modified
     */
    @GuardedBy("mNetworkPoliciesSecondLock")
    private boolean updateDefaultMobilePolicyAL(int subId, NetworkPolicy policy) {
        if (!policy.inferred) {
            if (LOGD) Slog.d(TAG, "Ignoring user-defined policy " + policy);
            return false;
        }

        final NetworkPolicy original = new NetworkPolicy(policy.template, policy.cycleRule,
                policy.warningBytes, policy.limitBytes, policy.lastWarningSnooze,
                policy.lastLimitSnooze, policy.metered, policy.inferred);

        final SubscriptionPlan[] plans = mSubscriptionPlans.get(subId);
        if (!ArrayUtils.isEmpty(plans)) {
            final SubscriptionPlan plan = plans[0];
            policy.cycleRule = plan.getCycleRule();
            final long planLimitBytes = plan.getDataLimitBytes();
            if (planLimitBytes == SubscriptionPlan.BYTES_UNKNOWN) {
                policy.warningBytes = getPlatformDefaultWarningBytes();
                policy.limitBytes = getPlatformDefaultLimitBytes();
            } else if (planLimitBytes == SubscriptionPlan.BYTES_UNLIMITED) {
                policy.warningBytes = NetworkPolicy.WARNING_DISABLED;
                policy.limitBytes = NetworkPolicy.LIMIT_DISABLED;
            } else {
                policy.warningBytes = (planLimitBytes * 9) / 10;
                switch (plan.getDataLimitBehavior()) {
                    case SubscriptionPlan.LIMIT_BEHAVIOR_BILLED:
                    case SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED:
                        policy.limitBytes = planLimitBytes;
                        break;
                    default:
                        policy.limitBytes = NetworkPolicy.LIMIT_DISABLED;
                        break;
                }
            }
        } else {
            final PersistableBundle config = mSubIdToCarrierConfig.get(subId);
            final int currentCycleDay;
            if (policy.cycleRule.isMonthly()) {
                currentCycleDay = policy.cycleRule.start.getDayOfMonth();
            } else {
                currentCycleDay = NetworkPolicy.CYCLE_NONE;
            }
            final int cycleDay = getCycleDayFromCarrierConfig(config, currentCycleDay);
            policy.cycleRule = NetworkPolicy.buildRule(cycleDay, ZoneId.systemDefault());
            policy.warningBytes = getWarningBytesFromCarrierConfig(config, policy.warningBytes);
            policy.limitBytes = getLimitBytesFromCarrierConfig(config, policy.limitBytes);
        }

        if (policy.equals(original)) {
            return false;
        } else {
            Slog.d(TAG, "Updated " + original + " to " + policy);
            return true;
        }
    }

    @GuardedBy({"mUidRulesFirstLock", "mNetworkPoliciesSecondLock"})
    private void readPolicyAL() {
        if (LOGV) Slog.v(TAG, "readPolicyAL()");

        // clear any existing policy and read from disk
        mNetworkPolicy.clear();
        mSubscriptionPlans.clear();
        mSubscriptionPlansOwner.clear();
        mUidPolicy.clear();

        FileInputStream fis = null;
        try {
            fis = mPolicyFile.openRead();
            final TypedXmlPullParser in = Xml.resolvePullParser(fis);

             // Must save the <restrict-background> tags and convert them to <uid-policy> later,
             // to skip UIDs that were explicitly denied.
            final SparseBooleanArray restrictBackgroundAllowedUids = new SparseBooleanArray();

            int type;
            int version = VERSION_INIT;
            boolean insideAllowlist = false;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_POLICY_LIST.equals(tag)) {
                        final boolean oldValue = mRestrictBackground;
                        version = readIntAttribute(in, ATTR_VERSION);
                        mLoadedRestrictBackground = (version >= VERSION_ADDED_RESTRICT_BACKGROUND)
                                && readBooleanAttribute(in, ATTR_RESTRICT_BACKGROUND);
                    } else if (TAG_NETWORK_POLICY.equals(tag)) {
                        final int networkTemplate = readIntAttribute(in, ATTR_NETWORK_TEMPLATE);
                        final String subscriberId = in.getAttributeValue(null, ATTR_SUBSCRIBER_ID);
                        final String networkId;
                        if (version >= VERSION_ADDED_NETWORK_ID) {
                            networkId = in.getAttributeValue(null, ATTR_NETWORK_ID);
                        } else {
                            networkId = null;
                        }
                        final RecurrenceRule cycleRule;
                        if (version >= VERSION_ADDED_CYCLE) {
                            final String start = readStringAttribute(in, ATTR_CYCLE_START);
                            final String end = readStringAttribute(in, ATTR_CYCLE_END);
                            final String period = readStringAttribute(in, ATTR_CYCLE_PERIOD);
                            cycleRule = new RecurrenceRule(
                                    RecurrenceRule.convertZonedDateTime(start),
                                    RecurrenceRule.convertZonedDateTime(end),
                                    RecurrenceRule.convertPeriod(period));
                        } else {
                            final int cycleDay = readIntAttribute(in, ATTR_CYCLE_DAY);
                            final String cycleTimezone;
                            if (version >= VERSION_ADDED_TIMEZONE) {
                                cycleTimezone = in.getAttributeValue(null, ATTR_CYCLE_TIMEZONE);
                            } else {
                                cycleTimezone = "UTC";
                            }
                            cycleRule = NetworkPolicy.buildRule(cycleDay, ZoneId.of(cycleTimezone));
                        }
                        final long warningBytes = readLongAttribute(in, ATTR_WARNING_BYTES);
                        final long limitBytes = readLongAttribute(in, ATTR_LIMIT_BYTES);
                        final long lastLimitSnooze;
                        if (version >= VERSION_SPLIT_SNOOZE) {
                            lastLimitSnooze = readLongAttribute(in, ATTR_LAST_LIMIT_SNOOZE);
                        } else if (version >= VERSION_ADDED_SNOOZE) {
                            lastLimitSnooze = readLongAttribute(in, ATTR_LAST_SNOOZE);
                        } else {
                            lastLimitSnooze = SNOOZE_NEVER;
                        }
                        final boolean metered;
                        if (version >= VERSION_ADDED_METERED) {
                            metered = readBooleanAttribute(in, ATTR_METERED);
                        } else {
                            switch (networkTemplate) {
                                case MATCH_MOBILE:
                                    metered = true;
                                    break;
                                default:
                                    metered = false;
                            }
                        }
                        final long lastWarningSnooze;
                        if (version >= VERSION_SPLIT_SNOOZE) {
                            lastWarningSnooze = readLongAttribute(in, ATTR_LAST_WARNING_SNOOZE);
                        } else {
                            lastWarningSnooze = SNOOZE_NEVER;
                        }
                        final boolean inferred;
                        if (version >= VERSION_ADDED_INFERRED) {
                            inferred = readBooleanAttribute(in, ATTR_INFERRED);
                        } else {
                            inferred = false;
                        }

                        final NetworkTemplate template = new NetworkTemplate(networkTemplate,
                                subscriberId, networkId);
                        if (template.isPersistable()) {
                            mNetworkPolicy.put(template, new NetworkPolicy(template, cycleRule,
                                    warningBytes, limitBytes, lastWarningSnooze,
                                    lastLimitSnooze, metered, inferred));
                        }

                    } else if (TAG_SUBSCRIPTION_PLAN.equals(tag)) {
                        final String start = readStringAttribute(in, ATTR_CYCLE_START);
                        final String end = readStringAttribute(in, ATTR_CYCLE_END);
                        final String period = readStringAttribute(in, ATTR_CYCLE_PERIOD);
                        final SubscriptionPlan.Builder builder = new SubscriptionPlan.Builder(
                                RecurrenceRule.convertZonedDateTime(start),
                                RecurrenceRule.convertZonedDateTime(end),
                                RecurrenceRule.convertPeriod(period));
                        builder.setTitle(readStringAttribute(in, ATTR_TITLE));
                        builder.setSummary(readStringAttribute(in, ATTR_SUMMARY));

                        final long limitBytes = readLongAttribute(in, ATTR_LIMIT_BYTES,
                                SubscriptionPlan.BYTES_UNKNOWN);
                        final int limitBehavior = readIntAttribute(in, ATTR_LIMIT_BEHAVIOR,
                                SubscriptionPlan.LIMIT_BEHAVIOR_UNKNOWN);
                        if (limitBytes != SubscriptionPlan.BYTES_UNKNOWN
                                && limitBehavior != SubscriptionPlan.LIMIT_BEHAVIOR_UNKNOWN) {
                            builder.setDataLimit(limitBytes, limitBehavior);
                        }

                        final long usageBytes = readLongAttribute(in, ATTR_USAGE_BYTES,
                                SubscriptionPlan.BYTES_UNKNOWN);
                        final long usageTime = readLongAttribute(in, ATTR_USAGE_TIME,
                                SubscriptionPlan.TIME_UNKNOWN);
                        if (usageBytes != SubscriptionPlan.BYTES_UNKNOWN
                                && usageTime != SubscriptionPlan.TIME_UNKNOWN) {
                            builder.setDataUsage(usageBytes, usageTime);
                        }

                        final int subId = readIntAttribute(in, ATTR_SUB_ID);
                        final String ownerPackage = readStringAttribute(in, ATTR_OWNER_PACKAGE);

                        if (version >= VERSION_ADDED_NETWORK_TYPES) {
                            final int depth = in.getDepth();
                            while (XmlUtils.nextElementWithin(in, depth)) {
                                if (TAG_XML_UTILS_INT_ARRAY.equals(in.getName())
                                        && ATTR_NETWORK_TYPES.equals(
                                                readStringAttribute(in, ATTR_XML_UTILS_NAME))) {
                                    final int[] networkTypes =
                                            readThisIntArrayXml(in, TAG_XML_UTILS_INT_ARRAY, null);
                                    builder.setNetworkTypes(networkTypes);
                                }
                            }
                        }

                        final SubscriptionPlan plan = builder.build();
                        mSubscriptionPlans.put(subId, ArrayUtils.appendElement(
                                SubscriptionPlan.class, mSubscriptionPlans.get(subId), plan));
                        mSubscriptionPlansOwner.put(subId, ownerPackage);
                    } else if (TAG_UID_POLICY.equals(tag)) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        final int policy = readIntAttribute(in, ATTR_POLICY);

                        if (UserHandle.isApp(uid)) {
                            setUidPolicyUncheckedUL(uid, policy, false);
                        } else {
                            Slog.w(TAG, "unable to apply policy to UID " + uid + "; ignoring");
                        }
                    } else if (TAG_APP_POLICY.equals(tag)) {
                        final int appId = readIntAttribute(in, ATTR_APP_ID);
                        final int policy = readIntAttribute(in, ATTR_POLICY);

                        // TODO: set for other users during upgrade
                        // app policy is deprecated so this is only used in pre system user split.
                        final int uid = UserHandle.getUid(UserHandle.USER_SYSTEM, appId);
                        if (UserHandle.isApp(uid)) {
                            setUidPolicyUncheckedUL(uid, policy, false);
                        } else {
                            Slog.w(TAG, "unable to apply policy to UID " + uid + "; ignoring");
                        }
                    } else if (TAG_WHITELIST.equals(tag)) {
                        insideAllowlist = true;
                    } else if (TAG_RESTRICT_BACKGROUND.equals(tag) && insideAllowlist) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        restrictBackgroundAllowedUids.append(uid, true);
                    } else if (TAG_REVOKED_RESTRICT_BACKGROUND.equals(tag) && insideAllowlist) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        mRestrictBackgroundAllowlistRevokedUids.put(uid, true);
                    }
                } else if (type == END_TAG) {
                    if (TAG_WHITELIST.equals(tag)) {
                        insideAllowlist = false;
                    }

                }
            }

            final int size = restrictBackgroundAllowedUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = restrictBackgroundAllowedUids.keyAt(i);
                final int policy = mUidPolicy.get(uid, POLICY_NONE);
                if ((policy & POLICY_REJECT_METERED_BACKGROUND) != 0) {
                    Slog.w(TAG, "ignoring restrict-background-allowlist for " + uid
                            + " because its policy is " + uidPoliciesToString(policy));
                    continue;
                }
                if (UserHandle.isApp(uid)) {
                    final int newPolicy = policy | POLICY_ALLOW_METERED_BACKGROUND;
                    if (LOGV)
                        Log.v(TAG, "new policy for " + uid + ": " + uidPoliciesToString(newPolicy));
                    setUidPolicyUncheckedUL(uid, newPolicy, false);
                } else {
                    Slog.w(TAG, "unable to update policy on UID " + uid);
                }
            }

        } catch (FileNotFoundException e) {
            // missing policy is okay, probably first boot
            upgradeDefaultBackgroundDataUL();
        } catch (Exception e) {
            Log.wtf(TAG, "problem reading network policy", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    /**
     * Upgrade legacy background data flags, notifying listeners of one last
     * change to always-true.
     */
    private void upgradeDefaultBackgroundDataUL() {
        // This method is only called when we're unable to find the network policy flag, which
        // usually happens on first boot of a new device and not one that has received an OTA.

        // Seed from the default value configured for this device.
        mLoadedRestrictBackground = Settings.Global.getInt(
                mContext.getContentResolver(), Global.DEFAULT_RESTRICT_BACKGROUND_DATA, 0) == 1;

        // NOTE: We used to read the legacy setting here :
        //
        // final int legacyFlagValue = Settings.Secure.getInt(
        //        mContext.getContentResolver(), Settings.Secure.BACKGROUND_DATA, ..);
        //
        // This is no longer necessary because we will never upgrade directly from Gingerbread
        // to O+. Devices upgrading from ICS onwards to O will have a netpolicy.xml file that
        // contains the correct value that we will continue to use.
    }

    /**
     * Perform upgrade step of moving any user-defined meterness overrides over
     * into {@link WifiConfiguration}.
     */
    @GuardedBy({"mNetworkPoliciesSecondLock", "mUidRulesFirstLock"})
    private void upgradeWifiMeteredOverrideAL() {
        boolean modified = false;
        final WifiManager wm = mContext.getSystemService(WifiManager.class);
        final List<WifiConfiguration> configs = wm.getConfiguredNetworks();
        for (int i = 0; i < mNetworkPolicy.size(); ) {
            final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
            if (policy.template.getMatchRule() == NetworkTemplate.MATCH_WIFI
                    && !policy.inferred) {
                mNetworkPolicy.removeAt(i);
                modified = true;

                final String networkId = resolveNetworkId(policy.template.getNetworkId());
                for (WifiConfiguration config : configs) {
                    if (Objects.equals(resolveNetworkId(config), networkId)) {
                        Slog.d(TAG, "Found network " + networkId + "; upgrading metered hint");
                        config.meteredOverride = policy.metered
                                ? WifiConfiguration.METERED_OVERRIDE_METERED
                                : WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
                        wm.updateNetwork(config);
                    }
                }
            } else {
                i++;
            }
        }
        if (modified) {
            writePolicyAL();
        }
    }

    @GuardedBy({"mUidRulesFirstLock", "mNetworkPoliciesSecondLock"})
    void writePolicyAL() {
        if (LOGV) Slog.v(TAG, "writePolicyAL()");

        FileOutputStream fos = null;
        try {
            fos = mPolicyFile.startWrite();

            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);

            out.startTag(null, TAG_POLICY_LIST);
            writeIntAttribute(out, ATTR_VERSION, VERSION_LATEST);
            writeBooleanAttribute(out, ATTR_RESTRICT_BACKGROUND, mRestrictBackground);

            // write all known network policies
            for (int i = 0; i < mNetworkPolicy.size(); i++) {
                final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
                final NetworkTemplate template = policy.template;
                if (!template.isPersistable()) continue;

                out.startTag(null, TAG_NETWORK_POLICY);
                writeIntAttribute(out, ATTR_NETWORK_TEMPLATE, template.getMatchRule());
                final String subscriberId = template.getSubscriberId();
                if (subscriberId != null) {
                    out.attribute(null, ATTR_SUBSCRIBER_ID, subscriberId);
                }
                final String networkId = template.getNetworkId();
                if (networkId != null) {
                    out.attribute(null, ATTR_NETWORK_ID, networkId);
                }
                writeStringAttribute(out, ATTR_CYCLE_START,
                        RecurrenceRule.convertZonedDateTime(policy.cycleRule.start));
                writeStringAttribute(out, ATTR_CYCLE_END,
                        RecurrenceRule.convertZonedDateTime(policy.cycleRule.end));
                writeStringAttribute(out, ATTR_CYCLE_PERIOD,
                        RecurrenceRule.convertPeriod(policy.cycleRule.period));
                writeLongAttribute(out, ATTR_WARNING_BYTES, policy.warningBytes);
                writeLongAttribute(out, ATTR_LIMIT_BYTES, policy.limitBytes);
                writeLongAttribute(out, ATTR_LAST_WARNING_SNOOZE, policy.lastWarningSnooze);
                writeLongAttribute(out, ATTR_LAST_LIMIT_SNOOZE, policy.lastLimitSnooze);
                writeBooleanAttribute(out, ATTR_METERED, policy.metered);
                writeBooleanAttribute(out, ATTR_INFERRED, policy.inferred);
                out.endTag(null, TAG_NETWORK_POLICY);
            }

            // write all known subscription plans
            for (int i = 0; i < mSubscriptionPlans.size(); i++) {
                final int subId = mSubscriptionPlans.keyAt(i);
                final String ownerPackage = mSubscriptionPlansOwner.get(subId);
                final SubscriptionPlan[] plans = mSubscriptionPlans.valueAt(i);
                if (ArrayUtils.isEmpty(plans)) continue;

                for (SubscriptionPlan plan : plans) {
                    out.startTag(null, TAG_SUBSCRIPTION_PLAN);
                    writeIntAttribute(out, ATTR_SUB_ID, subId);
                    writeStringAttribute(out, ATTR_OWNER_PACKAGE, ownerPackage);
                    final RecurrenceRule cycleRule = plan.getCycleRule();
                    writeStringAttribute(out, ATTR_CYCLE_START,
                            RecurrenceRule.convertZonedDateTime(cycleRule.start));
                    writeStringAttribute(out, ATTR_CYCLE_END,
                            RecurrenceRule.convertZonedDateTime(cycleRule.end));
                    writeStringAttribute(out, ATTR_CYCLE_PERIOD,
                            RecurrenceRule.convertPeriod(cycleRule.period));
                    writeStringAttribute(out, ATTR_TITLE, plan.getTitle());
                    writeStringAttribute(out, ATTR_SUMMARY, plan.getSummary());
                    writeLongAttribute(out, ATTR_LIMIT_BYTES, plan.getDataLimitBytes());
                    writeIntAttribute(out, ATTR_LIMIT_BEHAVIOR, plan.getDataLimitBehavior());
                    writeLongAttribute(out, ATTR_USAGE_BYTES, plan.getDataUsageBytes());
                    writeLongAttribute(out, ATTR_USAGE_TIME, plan.getDataUsageTime());
                    try {
                        writeIntArrayXml(plan.getNetworkTypes(), ATTR_NETWORK_TYPES, out);
                    } catch (XmlPullParserException ignored) { }
                    out.endTag(null, TAG_SUBSCRIPTION_PLAN);
                }
            }

            // write all known uid policies
            for (int i = 0; i < mUidPolicy.size(); i++) {
                final int uid = mUidPolicy.keyAt(i);
                final int policy = mUidPolicy.valueAt(i);

                // skip writing empty policies
                if (policy == POLICY_NONE) continue;

                out.startTag(null, TAG_UID_POLICY);
                writeIntAttribute(out, ATTR_UID, uid);
                writeIntAttribute(out, ATTR_POLICY, policy);
                out.endTag(null, TAG_UID_POLICY);
            }

            out.endTag(null, TAG_POLICY_LIST);

            // write all allowlists
            out.startTag(null, TAG_WHITELIST);

            // revoked restrict background allowlist
            int size = mRestrictBackgroundAllowlistRevokedUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = mRestrictBackgroundAllowlistRevokedUids.keyAt(i);
                out.startTag(null, TAG_REVOKED_RESTRICT_BACKGROUND);
                writeIntAttribute(out, ATTR_UID, uid);
                out.endTag(null, TAG_REVOKED_RESTRICT_BACKGROUND);
            }

            out.endTag(null, TAG_WHITELIST);

            out.endDocument();

            mPolicyFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mPolicyFile.failWrite(fos);
            }
        }
    }

    @Override
    public void setUidPolicy(int uid, int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        if (!UserHandle.isApp(uid)) {
            throw new IllegalArgumentException("cannot apply policy to UID " + uid);
        }
        synchronized (mUidRulesFirstLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
                if (oldPolicy != policy) {
                    setUidPolicyUncheckedUL(uid, oldPolicy, policy, true);
                    mLogger.uidPolicyChanged(uid, oldPolicy, policy);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override
    public void addUidPolicy(int uid, int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        if (!UserHandle.isApp(uid)) {
            throw new IllegalArgumentException("cannot apply policy to UID " + uid);
        }

        synchronized (mUidRulesFirstLock) {
            final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
            policy |= oldPolicy;
            if (oldPolicy != policy) {
                setUidPolicyUncheckedUL(uid, oldPolicy, policy, true);
                mLogger.uidPolicyChanged(uid, oldPolicy, policy);
            }
        }
    }

    @Override
    public void removeUidPolicy(int uid, int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        if (!UserHandle.isApp(uid)) {
            throw new IllegalArgumentException("cannot apply policy to UID " + uid);
        }

        synchronized (mUidRulesFirstLock) {
            final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
            policy = oldPolicy & ~policy;
            if (oldPolicy != policy) {
                setUidPolicyUncheckedUL(uid, oldPolicy, policy, true);
                mLogger.uidPolicyChanged(uid, oldPolicy, policy);
            }
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private void setUidPolicyUncheckedUL(int uid, int oldPolicy, int policy, boolean persist) {
        setUidPolicyUncheckedUL(uid, policy, false);

        final boolean notifyApp;
        if (!isUidValidForAllowlistRulesUL(uid)) {
            notifyApp = false;
        } else {
            final boolean wasDenied = oldPolicy == POLICY_REJECT_METERED_BACKGROUND;
            final boolean isDenied = policy == POLICY_REJECT_METERED_BACKGROUND;
            final boolean wasAllowed = oldPolicy == POLICY_ALLOW_METERED_BACKGROUND;
            final boolean isAllowed = policy == POLICY_ALLOW_METERED_BACKGROUND;
            final boolean wasBlocked = wasDenied || (mRestrictBackground && !wasAllowed);
            final boolean isBlocked = isDenied || (mRestrictBackground && !isAllowed);
            if ((wasAllowed && (!isAllowed || isDenied))
                    && mDefaultRestrictBackgroundAllowlistUids.get(uid)
                    && !mRestrictBackgroundAllowlistRevokedUids.get(uid)) {
                if (LOGD)
                    Slog.d(TAG, "Adding uid " + uid + " to revoked restrict background allowlist");
                mRestrictBackgroundAllowlistRevokedUids.append(uid, true);
            }
            notifyApp = wasBlocked != isBlocked;
        }
        mHandler.obtainMessage(MSG_POLICIES_CHANGED, uid, policy, Boolean.valueOf(notifyApp))
                .sendToTarget();
        if (persist) {
            synchronized (mNetworkPoliciesSecondLock) {
                writePolicyAL();
            }
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private void setUidPolicyUncheckedUL(int uid, int policy, boolean persist) {
        if (policy == POLICY_NONE) {
            mUidPolicy.delete(uid);
        } else {
            mUidPolicy.put(uid, policy);
        }

        // uid policy changed, recompute rules and persist policy.
        updateRulesForDataUsageRestrictionsUL(uid);
        if (persist) {
            synchronized (mNetworkPoliciesSecondLock) {
                writePolicyAL();
            }
        }
    }

    @Override
    public int getUidPolicy(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mUidRulesFirstLock) {
            return mUidPolicy.get(uid, POLICY_NONE);
        }
    }

    @Override
    public int[] getUidsWithPolicy(int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        int[] uids = new int[0];
        synchronized (mUidRulesFirstLock) {
            for (int i = 0; i < mUidPolicy.size(); i++) {
                final int uid = mUidPolicy.keyAt(i);
                final int uidPolicy = mUidPolicy.valueAt(i);
                if ((policy == POLICY_NONE && uidPolicy == POLICY_NONE) ||
                        (uidPolicy & policy) != 0) {
                    uids = appendInt(uids, uid);
                }
            }
        }
        return uids;
    }

    /**
     * Removes any persistable state associated with given {@link UserHandle}, persisting
     * if any changes that are made.
     */
    @GuardedBy("mUidRulesFirstLock")
    boolean removeUserStateUL(int userId, boolean writePolicy, boolean updateGlobalRules) {

        mLogger.removingUserState(userId);
        boolean changed = false;

        // Remove entries from revoked default restricted background UID allowlist
        for (int i = mRestrictBackgroundAllowlistRevokedUids.size() - 1; i >= 0; i--) {
            final int uid = mRestrictBackgroundAllowlistRevokedUids.keyAt(i);
            if (UserHandle.getUserId(uid) == userId) {
                mRestrictBackgroundAllowlistRevokedUids.removeAt(i);
                changed = true;
            }
        }

        // Remove associated UID policies
        int[] uids = new int[0];
        for (int i = 0; i < mUidPolicy.size(); i++) {
            final int uid = mUidPolicy.keyAt(i);
            if (UserHandle.getUserId(uid) == userId) {
                uids = appendInt(uids, uid);
            }
        }

        if (uids.length > 0) {
            for (int uid : uids) {
                mUidPolicy.delete(uid);
            }
            changed = true;
        }
        synchronized (mNetworkPoliciesSecondLock) {
            if (updateGlobalRules) {
                updateRulesForGlobalChangeAL(true);
            }
            if (writePolicy && changed) {
                writePolicyAL();
            }
        }
        return changed;
    }

    private boolean checkAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void enforceAnyPermissionOf(String... permissions) {
        if (!checkAnyPermissionOf(permissions)) {
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", permissions) + ".");
        }
    }

    @Override
    public void registerListener(@NonNull INetworkPolicyListener listener) {
        Objects.requireNonNull(listener);
        // TODO: Remove CONNECTIVITY_INTERNAL and the *AnyPermissionOf methods above after all apps
        //  have declared OBSERVE_NETWORK_POLICY.
        enforceAnyPermissionOf(CONNECTIVITY_INTERNAL, OBSERVE_NETWORK_POLICY);
        mListeners.register(listener);
        // TODO: Send callbacks to the newly registered listener
    }

    @Override
    public void unregisterListener(@NonNull INetworkPolicyListener listener) {
        Objects.requireNonNull(listener);
        // TODO: Remove CONNECTIVITY_INTERNAL and the *AnyPermissionOf methods above after all apps
        //  have declared OBSERVE_NETWORK_POLICY.
        enforceAnyPermissionOf(CONNECTIVITY_INTERNAL, OBSERVE_NETWORK_POLICY);
        mListeners.unregister(listener);
    }

    @Override
    public void setNetworkPolicies(NetworkPolicy[] policies) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUidRulesFirstLock) {
                synchronized (mNetworkPoliciesSecondLock) {
                    normalizePoliciesNL(policies);
                    handleNetworkPoliciesUpdateAL(false);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void addNetworkPolicyAL(NetworkPolicy policy) {
        NetworkPolicy[] policies = getNetworkPolicies(mContext.getOpPackageName());
        policies = ArrayUtils.appendElement(NetworkPolicy.class, policies, policy);
        setNetworkPolicies(policies);
    }

    @Override
    public NetworkPolicy[] getNetworkPolicies(String callingPackage) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        try {
            mContext.enforceCallingOrSelfPermission(READ_PRIVILEGED_PHONE_STATE, TAG);
            // SKIP checking run-time OP_READ_PHONE_STATE since caller or self has PRIVILEGED
            // permission
        } catch (SecurityException e) {
            mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, TAG);

            if (mAppOps.noteOp(AppOpsManager.OP_READ_PHONE_STATE, Binder.getCallingUid(),
                    callingPackage) != AppOpsManager.MODE_ALLOWED) {
                return new NetworkPolicy[0];
            }
        }

        synchronized (mNetworkPoliciesSecondLock) {
            final int size = mNetworkPolicy.size();
            final NetworkPolicy[] policies = new NetworkPolicy[size];
            for (int i = 0; i < size; i++) {
                policies[i] = mNetworkPolicy.valueAt(i);
            }
            return policies;
        }
    }

    @GuardedBy("mNetworkPoliciesSecondLock")
    private void normalizePoliciesNL() {
        normalizePoliciesNL(getNetworkPolicies(mContext.getOpPackageName()));
    }

    @GuardedBy("mNetworkPoliciesSecondLock")
    private void normalizePoliciesNL(NetworkPolicy[] policies) {
        mNetworkPolicy.clear();
        for (NetworkPolicy policy : policies) {
            if (policy == null) {
                continue;
            }
            // When two normalized templates conflict, prefer the most
            // restrictive policy
            policy.template = NetworkTemplate.normalize(policy.template, mMergedSubscriberIds);
            final NetworkPolicy existing = mNetworkPolicy.get(policy.template);
            if (existing == null || existing.compareTo(policy) > 0) {
                if (existing != null) {
                    Slog.d(TAG, "Normalization replaced " + existing + " with " + policy);
                }
                mNetworkPolicy.put(policy.template, policy);
            }
        }
    }

    @Override
    public void snoozeLimit(NetworkTemplate template) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        final long token = Binder.clearCallingIdentity();
        try {
            performSnooze(template, TYPE_LIMIT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void performSnooze(NetworkTemplate template, int type) {
        final long currentTime = mClock.millis();
        synchronized (mUidRulesFirstLock) {
            synchronized (mNetworkPoliciesSecondLock) {
                // find and snooze local policy that matches
                final NetworkPolicy policy = mNetworkPolicy.get(template);
                if (policy == null) {
                    throw new IllegalArgumentException("unable to find policy for " + template);
                }

                switch (type) {
                    case TYPE_WARNING:
                        policy.lastWarningSnooze = currentTime;
                        break;
                    case TYPE_LIMIT:
                        policy.lastLimitSnooze = currentTime;
                        break;
                    case TYPE_RAPID:
                        policy.lastRapidSnooze = currentTime;
                        break;
                    default:
                        throw new IllegalArgumentException("unexpected type");
                }

                handleNetworkPoliciesUpdateAL(true);
            }
        }
    }

    @Override
    public void setRestrictBackground(boolean restrictBackground) {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "setRestrictBackground");
        try {
            mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
            final int callingUid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mUidRulesFirstLock) {
                    setRestrictBackgroundUL(restrictBackground, "uid:" + callingUid);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private void setRestrictBackgroundUL(boolean restrictBackground, String reason) {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "setRestrictBackgroundUL");
        try {
            if (restrictBackground == mRestrictBackground) {
                // Ideally, UI should never allow this scenario...
                Slog.w(TAG, "setRestrictBackgroundUL: already " + restrictBackground);
                return;
            }
            Slog.d(TAG, "setRestrictBackgroundUL(): " + restrictBackground + "; reason: " + reason);
            final boolean oldRestrictBackground = mRestrictBackground;
            mRestrictBackground = restrictBackground;
            // Must allow foreground apps before turning data saver mode on.
            // TODO: there is no need to iterate through all apps here, just those in the foreground,
            // so it could call AM to get the UIDs of such apps, and iterate through them instead.
            updateRulesForRestrictBackgroundUL();
            try {
                if (!mNetworkManager.setDataSaverModeEnabled(mRestrictBackground)) {
                    Slog.e(TAG,
                            "Could not change Data Saver Mode on NMS to " + mRestrictBackground);
                    mRestrictBackground = oldRestrictBackground;
                    // TODO: if it knew the foreground apps (see TODO above), it could call
                    // updateRulesForRestrictBackgroundUL() again to restore state.
                    return;
                }
            } catch (RemoteException e) {
                // ignored; service lives in system_server
            }

            sendRestrictBackgroundChangedMsg();
            mLogger.restrictBackgroundChanged(oldRestrictBackground, mRestrictBackground);

            if (mRestrictBackgroundLowPowerMode) {
                mRestrictBackgroundChangedInBsm = true;
            }
            synchronized (mNetworkPoliciesSecondLock) {
                updateNotificationsNL();
                writePolicyAL();
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    private void sendRestrictBackgroundChangedMsg() {
        mHandler.removeMessages(MSG_RESTRICT_BACKGROUND_CHANGED);
        mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_CHANGED, mRestrictBackground ? 1 : 0, 0)
                .sendToTarget();
    }

    @Override
    public int getRestrictBackgroundByCaller() {
        mContext.enforceCallingOrSelfPermission(ACCESS_NETWORK_STATE, TAG);
        return getRestrictBackgroundStatusInternal(Binder.getCallingUid());
    }

    @Override
    public int getRestrictBackgroundStatus(int uid) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        return getRestrictBackgroundStatusInternal(uid);
    }

    private int getRestrictBackgroundStatusInternal(int uid) {
        synchronized (mUidRulesFirstLock) {
            // Must clear identity because getUidPolicy() is restricted to system.
            final long token = Binder.clearCallingIdentity();
            final int policy;
            try {
                policy = getUidPolicy(uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (policy == POLICY_REJECT_METERED_BACKGROUND) {
                // App is restricted.
                return RESTRICT_BACKGROUND_STATUS_ENABLED;
            }
            if (!mRestrictBackground) {
                return RESTRICT_BACKGROUND_STATUS_DISABLED;
            }
            return (mUidPolicy.get(uid) & POLICY_ALLOW_METERED_BACKGROUND) != 0
                    ? RESTRICT_BACKGROUND_STATUS_WHITELISTED
                    : RESTRICT_BACKGROUND_STATUS_ENABLED;
        }
    }

    @Override
    public boolean getRestrictBackground() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mUidRulesFirstLock) {
            return mRestrictBackground;
        }
    }

    @Override
    public void setDeviceIdleMode(boolean enabled) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "setDeviceIdleMode");
        try {
            synchronized (mUidRulesFirstLock) {
                if (mDeviceIdleMode == enabled) {
                    return;
                }
                mDeviceIdleMode = enabled;
                mLogger.deviceIdleModeEnabled(enabled);
                if (mSystemReady) {
                    // Device idle change means we need to rebuild rules for all
                    // known apps, so do a global refresh.
                    updateRulesForRestrictPowerUL();
                }
            }
            if (enabled) {
                EventLogTags.writeDeviceIdleOnPhase("net");
            } else {
                EventLogTags.writeDeviceIdleOffPhase("net");
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @Override
    public void setWifiMeteredOverride(String networkId, int meteredOverride) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        final long token = Binder.clearCallingIdentity();
        try {
            final WifiManager wm = mContext.getSystemService(WifiManager.class);
            final List<WifiConfiguration> configs = wm.getConfiguredNetworks();
            for (WifiConfiguration config : configs) {
                if (Objects.equals(resolveNetworkId(config), networkId)) {
                    config.meteredOverride = meteredOverride;
                    wm.updateNetwork(config);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void enforceSubscriptionPlanAccess(int subId, int callingUid, String callingPackage) {
        // Verify they're not lying about package name
        mAppOps.checkPackage(callingUid, callingPackage);

        final SubscriptionManager sm;
        final SubscriptionInfo si;
        final PersistableBundle config;
        final long token = Binder.clearCallingIdentity();
        try {
            sm = mContext.getSystemService(SubscriptionManager.class);
            si = sm.getActiveSubscriptionInfo(subId);
            config = mCarrierConfigManager.getConfigForSubId(subId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // First check: is caller the CarrierService?
        if (si != null) {
            if (si.isEmbedded() && sm.canManageSubscription(si, callingPackage)) {
                return;
            }
        }

        // Second check: has the CarrierService delegated access?
        if (config != null) {
            final String overridePackage = config
                    .getString(CarrierConfigManager.KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING, null);
            if (!TextUtils.isEmpty(overridePackage)
                    && Objects.equals(overridePackage, callingPackage)) {
                return;
            }
        }

        // Third check: is caller the fallback/default CarrierService?
        final String defaultPackage = mCarrierConfigManager.getDefaultCarrierServicePackageName();
        if (!TextUtils.isEmpty(defaultPackage)
                && Objects.equals(defaultPackage, callingPackage)) {
            return;
        }

        // Fourth check: is caller a testing app?
        final String testPackage = SystemProperties.get(PROP_SUB_PLAN_OWNER + "." + subId, null);
        if (!TextUtils.isEmpty(testPackage)
                && Objects.equals(testPackage, callingPackage)) {
            return;
        }

        // Fifth check: is caller a legacy testing app?
        final String legacyTestPackage = SystemProperties.get("fw.sub_plan_owner." + subId, null);
        if (!TextUtils.isEmpty(legacyTestPackage)
                && Objects.equals(legacyTestPackage, callingPackage)) {
            return;
        }

        // Final check: does the caller hold a permission?
        mContext.enforceCallingOrSelfPermission(MANAGE_SUBSCRIPTION_PLANS, TAG);
    }

    private void enforceSubscriptionPlanValidity(SubscriptionPlan[] plans) {
        // nothing to check if no plans
        if (plans.length == 0) {
            Log.d(TAG, "Received empty plans list. Clearing existing SubscriptionPlans.");
            return;
        }

        final int[] allNetworkTypes = TelephonyManager.getAllNetworkTypes();
        final ArraySet<Integer> allNetworksSet = new ArraySet<>();
        addAll(allNetworksSet, allNetworkTypes);

        final ArraySet<Integer> applicableNetworkTypes = new ArraySet<>();
        boolean hasGeneralPlan = false;
        for (int i = 0; i < plans.length; i++) {
            final int[] planNetworkTypes = plans[i].getNetworkTypes();
            final ArraySet<Integer> planNetworksSet = new ArraySet<>();
            for (int j = 0; j < planNetworkTypes.length; j++) {
                // ensure all network types are valid
                if (allNetworksSet.contains(planNetworkTypes[j])) {
                    // ensure no duplicate network types in the same SubscriptionPlan
                    if (!planNetworksSet.add(planNetworkTypes[j])) {
                        throw new IllegalArgumentException(
                                "Subscription plan contains duplicate network types.");
                    }
                } else {
                    throw new IllegalArgumentException("Invalid network type: "
                            + planNetworkTypes[j]);
                }
            }

            if (planNetworkTypes.length == allNetworkTypes.length) {
                hasGeneralPlan = true;
            } else {
                // ensure no network type applies to multiple plans
                if (!addAll(applicableNetworkTypes, planNetworkTypes)) {
                    throw new IllegalArgumentException(
                            "Multiple subscription plans defined for a single network type.");
                }
            }
        }

        // ensure at least one plan applies for every network type
        if (!hasGeneralPlan) {
            throw new IllegalArgumentException(
                    "No generic subscription plan that applies to all network types.");
        }
    }

    /**
     * Adds all of the {@code elements} to the {@code set}.
     *
     * @return {@code false} if any element is not added because the set already has the value.
     */
    private static boolean addAll(@NonNull ArraySet<Integer> set, @NonNull int... elements) {
        boolean result = true;
        for (int i = 0; i < elements.length; i++) {
            result &= set.add(elements[i]);
        }
        return result;
    }

    @Override
    public SubscriptionPlan[] getSubscriptionPlans(int subId, String callingPackage) {
        enforceSubscriptionPlanAccess(subId, Binder.getCallingUid(), callingPackage);

        final String fake = SystemProperties.get("fw.fake_plan");
        if (!TextUtils.isEmpty(fake)) {
            final List<SubscriptionPlan> plans = new ArrayList<>();
            if ("month_hard".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2007-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_BILLED)
                        .setDataUsage(1 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(36).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2017-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile Happy")
                        .setDataLimit(SubscriptionPlan.BYTES_UNLIMITED,
                                SubscriptionPlan.LIMIT_BEHAVIOR_BILLED)
                        .setDataUsage(5 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(36).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2017-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile, Charged after limit")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_BILLED)
                        .setDataUsage(5 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(36).toInstant().toEpochMilli())
                        .build());
            } else if ("month_soft".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2007-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile is the carriers name who this plan belongs to")
                        .setSummary("Crazy unlimited bandwidth plan with incredibly long title "
                                + "that should be cut off to prevent UI from looking terrible")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(1 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2017-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile, Throttled after limit")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(5 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2017-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile, No data connection after limit")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                        .setDataUsage(5 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());

            } else if ("month_over".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2007-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile is the carriers name who this plan belongs to")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(6 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2017-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile, Throttled after limit")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(5 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2017-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile, No data connection after limit")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                        .setDataUsage(5 * TrafficStats.GB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());

            } else if ("month_none".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createRecurringMonthly(ZonedDateTime.parse("2007-03-14T00:00:00.000Z"))
                        .setTitle("G-Mobile")
                        .build());
            } else if ("prepaid".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createNonrecurring(ZonedDateTime.now().minusDays(20),
                                ZonedDateTime.now().plusDays(10))
                        .setTitle("G-Mobile")
                        .setDataLimit(512 * TrafficStats.MB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                        .setDataUsage(100 * TrafficStats.MB_IN_BYTES,
                                ZonedDateTime.now().minusHours(3).toInstant().toEpochMilli())
                        .build());
            } else if ("prepaid_crazy".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createNonrecurring(ZonedDateTime.now().minusDays(20),
                                ZonedDateTime.now().plusDays(10))
                        .setTitle("G-Mobile Anytime")
                        .setDataLimit(512 * TrafficStats.MB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_DISABLED)
                        .setDataUsage(100 * TrafficStats.MB_IN_BYTES,
                                ZonedDateTime.now().minusHours(3).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createNonrecurring(ZonedDateTime.now().minusDays(10),
                                ZonedDateTime.now().plusDays(20))
                        .setTitle("G-Mobile Nickel Nights")
                        .setSummary("5¢/GB between 1-5AM")
                        .setDataLimit(5 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(15 * TrafficStats.MB_IN_BYTES,
                                ZonedDateTime.now().minusHours(30).toInstant().toEpochMilli())
                        .build());
                plans.add(SubscriptionPlan.Builder
                        .createNonrecurring(ZonedDateTime.now().minusDays(10),
                                ZonedDateTime.now().plusDays(20))
                        .setTitle("G-Mobile Bonus 3G")
                        .setSummary("Unlimited 3G data")
                        .setDataLimit(1 * TrafficStats.GB_IN_BYTES,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(300 * TrafficStats.MB_IN_BYTES,
                                ZonedDateTime.now().minusHours(1).toInstant().toEpochMilli())
                        .build());
            } else if ("unlimited".equals(fake)) {
                plans.add(SubscriptionPlan.Builder
                        .createNonrecurring(ZonedDateTime.now().minusDays(20),
                                ZonedDateTime.now().plusDays(10))
                        .setTitle("G-Mobile Awesome")
                        .setDataLimit(SubscriptionPlan.BYTES_UNLIMITED,
                                SubscriptionPlan.LIMIT_BEHAVIOR_THROTTLED)
                        .setDataUsage(50 * TrafficStats.MB_IN_BYTES,
                                ZonedDateTime.now().minusHours(3).toInstant().toEpochMilli())
                        .build());
            }
            return plans.toArray(new SubscriptionPlan[plans.size()]);
        }

        synchronized (mNetworkPoliciesSecondLock) {
            // Only give out plan details to the package that defined them,
            // so that we don't risk leaking plans between apps. We always
            // let in core system components (like the Settings app).
            final String ownerPackage = mSubscriptionPlansOwner.get(subId);
            if (Objects.equals(ownerPackage, callingPackage)
                    || (UserHandle.getCallingAppId() == android.os.Process.SYSTEM_UID)
                    || (UserHandle.getCallingAppId() == android.os.Process.PHONE_UID)) {
                return mSubscriptionPlans.get(subId);
            } else {
                Log.w(TAG, "Not returning plans because caller " + callingPackage
                        + " doesn't match owner " + ownerPackage);
                return null;
            }
        }
    }

    @Override
    public void setSubscriptionPlans(int subId, SubscriptionPlan[] plans, String callingPackage) {
        enforceSubscriptionPlanAccess(subId, Binder.getCallingUid(), callingPackage);
        enforceSubscriptionPlanValidity(plans);

        for (SubscriptionPlan plan : plans) {
            Objects.requireNonNull(plan);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mUidRulesFirstLock) {
                synchronized (mNetworkPoliciesSecondLock) {
                    mSubscriptionPlans.put(subId, plans);
                    mSubscriptionPlansOwner.put(subId, callingPackage);

                    final String subscriberId = mSubIdToSubscriberId.get(subId, null);
                    if (subscriberId != null) {
                        ensureActiveMobilePolicyAL(subId, subscriberId);
                        maybeUpdateMobilePolicyCycleAL(subId, subscriberId);
                    } else {
                        Slog.wtf(TAG, "Missing subscriberId for subId " + subId);
                    }

                    handleNetworkPoliciesUpdateAL(true);
                }
            }

            final Intent intent = new Intent(SubscriptionManager.ACTION_SUBSCRIPTION_PLANS_CHANGED);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subId);
            mContext.sendBroadcast(intent, android.Manifest.permission.MANAGE_SUBSCRIPTION_PLANS);
            mHandler.sendMessage(
                    mHandler.obtainMessage(MSG_SUBSCRIPTION_PLANS_CHANGED, subId, 0, plans));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Only visible for testing purposes. This doesn't give any access to
     * existing plans; it simply lets the debug package define new plans.
     */
    void setSubscriptionPlansOwner(int subId, String packageName) {
        mContext.enforceCallingOrSelfPermission(NETWORK_SETTINGS, TAG);
        SystemProperties.set(PROP_SUB_PLAN_OWNER + "." + subId, packageName);
    }

    @Override
    public String getSubscriptionPlansOwner(int subId) {
        if (UserHandle.getCallingAppId() != android.os.Process.SYSTEM_UID) {
            throw new SecurityException();
        }

        synchronized (mNetworkPoliciesSecondLock) {
            return mSubscriptionPlansOwner.get(subId);
        }
    }

    @Override
    public void setSubscriptionOverride(int subId, int overrideMask, int overrideValue,
            int[] networkTypes, long timeoutMillis, String callingPackage) {
        enforceSubscriptionPlanAccess(subId, Binder.getCallingUid(), callingPackage);

        final ArraySet<Integer> allNetworksSet = new ArraySet<>();
        addAll(allNetworksSet, TelephonyManager.getAllNetworkTypes());
        final IntArray applicableNetworks = new IntArray();

        // ensure all network types are valid
        for (int networkType : networkTypes) {
            if (allNetworksSet.contains(networkType)) {
                applicableNetworks.add(networkType);
            } else {
                Log.d(TAG, "setSubscriptionOverride removing invalid network type: " + networkType);
            }
        }

        // We can only override when carrier told us about plans. For the unmetered case,
        // allow override without having plans defined.
        synchronized (mNetworkPoliciesSecondLock) {
            final SubscriptionPlan plan = getPrimarySubscriptionPlanLocked(subId);
            if (overrideMask != SUBSCRIPTION_OVERRIDE_UNMETERED && plan == null
                    || plan.getDataLimitBehavior() == SubscriptionPlan.LIMIT_BEHAVIOR_UNKNOWN) {
                throw new IllegalStateException(
                        "Must provide valid SubscriptionPlan to enable overriding");
            }
        }

        // Only allow overrides when feature is enabled. However, we always
        // allow disabling of overrides for safety reasons.
        final boolean overrideEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                NETPOLICY_OVERRIDE_ENABLED, 1) != 0;
        if (overrideEnabled || overrideValue == 0) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = subId;
            args.arg2 = overrideMask;
            args.arg3 = overrideValue;
            args.arg4 = applicableNetworks.toArray();
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SUBSCRIPTION_OVERRIDE, args));
            if (timeoutMillis > 0) {
                args.arg3 = 0;
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SUBSCRIPTION_OVERRIDE, args),
                        timeoutMillis);
            }
        }
    }

    /**
     * Get multipath preference value for the given network.
     */
    public int getMultipathPreference(Network network) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        final Integer preference = mMultipathPolicyTracker.getMultipathPreference(network);
        if (preference != null) {
            return preference;
        }
        return 0;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter fout = new IndentingPrintWriter(writer, "  ");

        final ArraySet<String> argSet = new ArraySet<String>(args.length);
        for (String arg : args) {
            argSet.add(arg);
        }

        synchronized (mUidRulesFirstLock) {
            synchronized (mNetworkPoliciesSecondLock) {
                if (argSet.contains("--unsnooze")) {
                    for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
                        mNetworkPolicy.valueAt(i).clearSnooze();
                    }

                    handleNetworkPoliciesUpdateAL(true);

                    fout.println("Cleared snooze timestamps");
                    return;
                }

                fout.print("System ready: "); fout.println(mSystemReady);
                fout.print("Restrict background: "); fout.println(mRestrictBackground);
                fout.print("Restrict power: "); fout.println(mRestrictPower);
                fout.print("Device idle: "); fout.println(mDeviceIdleMode);
                fout.print("Restricted networking mode: "); fout.println(mRestrictedNetworkingMode);
                synchronized (mMeteredIfacesLock) {
                    fout.print("Metered ifaces: ");
                    fout.println(mMeteredIfaces);
                }

                fout.println();
                fout.println("mRestrictBackgroundLowPowerMode: " + mRestrictBackgroundLowPowerMode);
                fout.println("mRestrictBackgroundBeforeBsm: " + mRestrictBackgroundBeforeBsm);
                fout.println("mLoadedRestrictBackground: " + mLoadedRestrictBackground);
                fout.println("mRestrictBackgroundChangedInBsm: " + mRestrictBackgroundChangedInBsm);

                fout.println();
                fout.println("Network policies:");
                fout.increaseIndent();
                for (int i = 0; i < mNetworkPolicy.size(); i++) {
                    fout.println(mNetworkPolicy.valueAt(i).toString());
                }
                fout.decreaseIndent();

                fout.println();
                fout.println("Subscription plans:");
                fout.increaseIndent();
                for (int i = 0; i < mSubscriptionPlans.size(); i++) {
                    final int subId = mSubscriptionPlans.keyAt(i);
                    fout.println("Subscriber ID " + subId + ":");
                    fout.increaseIndent();
                    final SubscriptionPlan[] plans = mSubscriptionPlans.valueAt(i);
                    if (!ArrayUtils.isEmpty(plans)) {
                        for (SubscriptionPlan plan : plans) {
                            fout.println(plan);
                        }
                    }
                    fout.decreaseIndent();
                }
                fout.decreaseIndent();

                fout.println();
                fout.println("Active subscriptions:");
                fout.increaseIndent();
                for (int i = 0; i < mSubIdToSubscriberId.size(); i++) {
                    final int subId = mSubIdToSubscriberId.keyAt(i);
                    final String subscriberId = mSubIdToSubscriberId.valueAt(i);

                    fout.println(subId + "="
                            + NetworkIdentityUtils.scrubSubscriberId(subscriberId));
                }
                fout.decreaseIndent();

                fout.println();
                for (String[] mergedSubscribers : mMergedSubscriberIds) {
                    fout.println("Merged subscriptions: " + Arrays.toString(
                            NetworkIdentityUtils.scrubSubscriberIds(mergedSubscribers)));
                }

                fout.println();
                fout.println("Policy for UIDs:");
                fout.increaseIndent();
                int size = mUidPolicy.size();
                for (int i = 0; i < size; i++) {
                    final int uid = mUidPolicy.keyAt(i);
                    final int policy = mUidPolicy.valueAt(i);
                    fout.print("UID=");
                    fout.print(uid);
                    fout.print(" policy=");
                    fout.print(uidPoliciesToString(policy));
                    fout.println();
                }
                fout.decreaseIndent();

                size = mPowerSaveWhitelistExceptIdleAppIds.size();
                if (size > 0) {
                    fout.println("Power save whitelist (except idle) app ids:");
                    fout.increaseIndent();
                    for (int i = 0; i < size; i++) {
                        fout.print("UID=");
                        fout.print(mPowerSaveWhitelistExceptIdleAppIds.keyAt(i));
                        fout.print(": ");
                        fout.print(mPowerSaveWhitelistExceptIdleAppIds.valueAt(i));
                        fout.println();
                    }
                    fout.decreaseIndent();
                }

                size = mPowerSaveWhitelistAppIds.size();
                if (size > 0) {
                    fout.println("Power save whitelist app ids:");
                    fout.increaseIndent();
                    for (int i = 0; i < size; i++) {
                        fout.print("UID=");
                        fout.print(mPowerSaveWhitelistAppIds.keyAt(i));
                        fout.print(": ");
                        fout.print(mPowerSaveWhitelistAppIds.valueAt(i));
                        fout.println();
                    }
                    fout.decreaseIndent();
                }

                size = mAppIdleTempWhitelistAppIds.size();
                if (size > 0) {
                    fout.println("App idle whitelist app ids:");
                    fout.increaseIndent();
                    for (int i = 0; i < size; i++) {
                        fout.print("UID=");
                        fout.print(mAppIdleTempWhitelistAppIds.keyAt(i));
                        fout.print(": ");
                        fout.print(mAppIdleTempWhitelistAppIds.valueAt(i));
                        fout.println();
                    }
                    fout.decreaseIndent();
                }

                size = mDefaultRestrictBackgroundAllowlistUids.size();
                if (size > 0) {
                    fout.println("Default restrict background allowlist uids:");
                    fout.increaseIndent();
                    for (int i = 0; i < size; i++) {
                        fout.print("UID=");
                        fout.print(mDefaultRestrictBackgroundAllowlistUids.keyAt(i));
                        fout.println();
                    }
                    fout.decreaseIndent();
                }

                size = mRestrictBackgroundAllowlistRevokedUids.size();
                if (size > 0) {
                    fout.println("Default restrict background allowlist uids revoked by users:");
                    fout.increaseIndent();
                    for (int i = 0; i < size; i++) {
                        fout.print("UID=");
                        fout.print(mRestrictBackgroundAllowlistRevokedUids.keyAt(i));
                        fout.println();
                    }
                    fout.decreaseIndent();
                }

                final SparseBooleanArray knownUids = new SparseBooleanArray();
                collectKeys(mUidState, knownUids);
                collectKeys(mUidRules, knownUids);

                fout.println("Status for all known UIDs:");
                fout.increaseIndent();
                size = knownUids.size();
                for (int i = 0; i < size; i++) {
                    final int uid = knownUids.keyAt(i);
                    fout.print("UID=");
                    fout.print(uid);

                    final UidState uidState = mUidState.get(uid);
                    if (uidState == null) {
                        fout.print(" state={null}");
                    } else {
                        fout.print(" state=");
                        fout.print(uidState.toString());
                    }

                    final int uidRules = mUidRules.get(uid, RULE_NONE);
                    fout.print(" rules=");
                    fout.print(uidRulesToString(uidRules));
                    fout.println();
                }
                fout.decreaseIndent();

                fout.println("Status for just UIDs with rules:");
                fout.increaseIndent();
                size = mUidRules.size();
                for (int i = 0; i < size; i++) {
                    final int uid = mUidRules.keyAt(i);
                    fout.print("UID=");
                    fout.print(uid);
                    final int uidRules = mUidRules.get(uid, RULE_NONE);
                    fout.print(" rules=");
                    fout.print(uidRulesToString(uidRules));
                    fout.println();
                }
                fout.decreaseIndent();

                fout.println("Admin restricted uids for metered data:");
                fout.increaseIndent();
                size = mMeteredRestrictedUids.size();
                for (int i = 0; i < size; ++i) {
                    fout.print("u" + mMeteredRestrictedUids.keyAt(i) + ": ");
                    fout.println(mMeteredRestrictedUids.valueAt(i));
                }
                fout.decreaseIndent();

                fout.println();
                mStatLogger.dump(fout);

                mLogger.dumpLogs(fout);
            }
        }
        fout.println();
        mMultipathPolicyTracker.dump(fout);
    }

    @Override
    public int handleShellCommand(@NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new NetworkPolicyManagerShellCommand(mContext, this).exec(this,
                in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(), args);
    }

    void setDebugUid(int uid) {
        mLogger.setDebugUid(uid);
    }

    @VisibleForTesting
    boolean isUidForeground(int uid) {
        synchronized (mUidRulesFirstLock) {
            return isProcStateAllowedWhileIdleOrPowerSaveMode(mUidState.get(uid));
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private boolean isUidForegroundOnRestrictBackgroundUL(int uid) {
        final UidState uidState = mUidState.get(uid);
        return isProcStateAllowedWhileOnRestrictBackground(uidState);
    }

    @GuardedBy("mUidRulesFirstLock")
    private boolean isUidForegroundOnRestrictPowerUL(int uid) {
        final UidState uidState = mUidState.get(uid);
        return isProcStateAllowedWhileIdleOrPowerSaveMode(uidState);
    }

    /**
     * Process state of UID changed; if needed, will trigger
     * {@link #updateRulesForDataUsageRestrictionsUL(int)} and
     * {@link #updateRulesForPowerRestrictionsUL(int)}. Returns true if the state was updated.
     */
    @GuardedBy("mUidRulesFirstLock")
    private boolean updateUidStateUL(int uid, int procState, @ProcessCapability int capability) {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateUidStateUL");
        try {
            final UidState oldUidState = mUidState.get(uid);
            if (oldUidState == null || oldUidState.procState != procState
                    || oldUidState.capability != capability) {
                final UidState newUidState = new UidState(uid, procState, capability);
                // state changed, push updated rules
                mUidState.put(uid, newUidState);
                updateRestrictBackgroundRulesOnUidStatusChangedUL(uid, oldUidState, newUidState);
                if (isProcStateAllowedWhileIdleOrPowerSaveMode(oldUidState)
                        != isProcStateAllowedWhileIdleOrPowerSaveMode(newUidState)) {
                    updateRuleForAppIdleUL(uid);
                    if (mDeviceIdleMode) {
                        updateRuleForDeviceIdleUL(uid);
                    }
                    if (mRestrictPower) {
                        updateRuleForRestrictPowerUL(uid);
                    }
                    updateRulesForPowerRestrictionsUL(uid);
                }
                return true;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
        return false;
    }

    @GuardedBy("mUidRulesFirstLock")
    private boolean removeUidStateUL(int uid) {
        final int index = mUidState.indexOfKey(uid);
        if (index >= 0) {
            final UidState oldUidState = mUidState.valueAt(index);
            mUidState.removeAt(index);
            if (oldUidState != null) {
                updateRestrictBackgroundRulesOnUidStatusChangedUL(uid, oldUidState, null);
                if (mDeviceIdleMode) {
                    updateRuleForDeviceIdleUL(uid);
                }
                if (mRestrictPower) {
                    updateRuleForRestrictPowerUL(uid);
                }
                updateRulesForPowerRestrictionsUL(uid);
                return true;
            }
        }
        return false;
    }

    // adjust stats accounting based on foreground status
    private void updateNetworkStats(int uid, boolean uidForeground) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK,
                    "updateNetworkStats: " + uid + "/" + (uidForeground ? "F" : "B"));
        }
        try {
            mNetworkStats.setUidForeground(uid, uidForeground);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    private void updateRestrictBackgroundRulesOnUidStatusChangedUL(int uid,
            @Nullable UidState oldUidState, @Nullable UidState newUidState) {
        final boolean oldForeground =
                isProcStateAllowedWhileOnRestrictBackground(oldUidState);
        final boolean newForeground =
                isProcStateAllowedWhileOnRestrictBackground(newUidState);
        if (oldForeground != newForeground) {
            updateRulesForDataUsageRestrictionsUL(uid);
        }
    }

    @VisibleForTesting
    boolean isRestrictedModeEnabled() {
        synchronized (mUidRulesFirstLock) {
            return mRestrictedNetworkingMode;
        }
    }

    /**
     * updates restricted mode state / access for all apps
     * Called on initialization and when restricted mode is enabled / disabled.
     */
    @VisibleForTesting
    @GuardedBy("mUidRulesFirstLock")
    void updateRestrictedModeAllowlistUL() {
        mUidFirewallRestrictedModeRules.clear();
        forEachUid("updateRestrictedModeAllowlist", uid -> {
            final int oldUidRule = mUidRules.get(uid);
            final int newUidRule = getNewRestrictedModeUidRule(uid, oldUidRule);
            final boolean hasUidRuleChanged = oldUidRule != newUidRule;
            final int newFirewallRule = getRestrictedModeFirewallRule(newUidRule);

            // setUidFirewallRulesUL will allowlist all uids that are passed to it, so only add
            // non-default rules.
            if (newFirewallRule != FIREWALL_RULE_DEFAULT) {
                mUidFirewallRestrictedModeRules.append(uid, newFirewallRule);
            }

            if (hasUidRuleChanged) {
                mUidRules.put(uid, newUidRule);
                mHandler.obtainMessage(MSG_RULES_CHANGED, uid, newUidRule).sendToTarget();
            }
            updateBlockedReasonsForRestrictedModeUL(uid);
        });
        if (mRestrictedNetworkingMode) {
            // firewall rules only need to be set when this mode is being enabled.
            setUidFirewallRulesUL(FIREWALL_CHAIN_RESTRICTED, mUidFirewallRestrictedModeRules);
        }
        enableFirewallChainUL(FIREWALL_CHAIN_RESTRICTED, mRestrictedNetworkingMode);
    }

    // updates restricted mode state / access for a single app / uid.
    @VisibleForTesting
    @GuardedBy("mUidRulesFirstLock")
    void updateRestrictedModeForUidUL(int uid) {
        final int oldUidRule = mUidRules.get(uid);
        final int newUidRule = getNewRestrictedModeUidRule(uid, oldUidRule);
        final boolean hasUidRuleChanged = oldUidRule != newUidRule;

        if (hasUidRuleChanged) {
            mUidRules.put(uid, newUidRule);
            mHandler.obtainMessage(MSG_RULES_CHANGED, uid, newUidRule).sendToTarget();
        }
        updateBlockedReasonsForRestrictedModeUL(uid);

        // if restricted networking mode is on, and the app has an access exemption, the uid rule
        // will not change, but the firewall rule will have to be updated.
        if (mRestrictedNetworkingMode) {
            // Note: setUidFirewallRule also updates mUidFirewallRestrictedModeRules.
            // In this case, default firewall rules can also be added.
            setUidFirewallRule(FIREWALL_CHAIN_RESTRICTED, uid,
                    getRestrictedModeFirewallRule(newUidRule));
        }
    }

    private void updateBlockedReasonsForRestrictedModeUL(int uid) {
        UidBlockedState uidBlockedState = mUidBlockedState.get(uid);
        if (uidBlockedState == null) {
            uidBlockedState = new UidBlockedState();
            mUidBlockedState.put(uid, uidBlockedState);
        }
        final int oldEffectiveBlockedReasons = uidBlockedState.effectiveBlockedReasons;
        if (mRestrictedNetworkingMode) {
            uidBlockedState.blockedReasons |= BLOCKED_REASON_RESTRICTED_MODE;
        } else {
            uidBlockedState.blockedReasons &= ~BLOCKED_REASON_RESTRICTED_MODE;
        }
        if (hasRestrictedModeAccess(uid)) {
            uidBlockedState.allowedReasons |= ALLOWED_REASON_RESTRICTED_MODE_PERMISSIONS;
        } else {
            uidBlockedState.allowedReasons &= ALLOWED_REASON_RESTRICTED_MODE_PERMISSIONS;
        }
        uidBlockedState.updateEffectiveBlockedReasons();
        if (oldEffectiveBlockedReasons != uidBlockedState.effectiveBlockedReasons) {
            mHandler.obtainMessage(MSG_BLOCKED_REASON_CHANGED, uid,
                    uidBlockedState.effectiveBlockedReasons, oldEffectiveBlockedReasons)
                    .sendToTarget();
        }
    }

    private int getNewRestrictedModeUidRule(int uid, int oldUidRule) {
        int newRule = oldUidRule;
        newRule &= ~MASK_RESTRICTED_MODE_NETWORKS;
        if (mRestrictedNetworkingMode && !hasRestrictedModeAccess(uid)) {
            newRule |= RULE_REJECT_RESTRICTED_MODE;
        }
        return newRule;
    }

    private static int getRestrictedModeFirewallRule(int uidRule) {
        if ((uidRule & RULE_REJECT_RESTRICTED_MODE) != 0) {
            // rejected in restricted mode, this is the default behavior.
            return FIREWALL_RULE_DEFAULT;
        } else {
            return FIREWALL_RULE_ALLOW;
        }
    }

    private boolean hasRestrictedModeAccess(int uid) {
        try {
            // TODO: this needs to be kept in sync with
            // PermissionMonitor#hasRestrictedNetworkPermission
            return mIPm.checkUidPermission(CONNECTIVITY_USE_RESTRICTED_NETWORKS, uid)
                    == PERMISSION_GRANTED
                    || mIPm.checkUidPermission(NETWORK_STACK, uid) == PERMISSION_GRANTED
                    || mIPm.checkUidPermission(PERMISSION_MAINLINE_NETWORK_STACK, uid)
                    == PERMISSION_GRANTED;
        } catch (RemoteException e) {
            return false;
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    void updateRulesForPowerSaveUL() {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateRulesForPowerSaveUL");
        try {
            updateRulesForWhitelistedPowerSaveUL(mRestrictPower, FIREWALL_CHAIN_POWERSAVE,
                    mUidFirewallPowerSaveRules);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    void updateRuleForRestrictPowerUL(int uid) {
        updateRulesForWhitelistedPowerSaveUL(uid, mRestrictPower, FIREWALL_CHAIN_POWERSAVE);
    }

    @GuardedBy("mUidRulesFirstLock")
    void updateRulesForDeviceIdleUL() {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateRulesForDeviceIdleUL");
        try {
            updateRulesForWhitelistedPowerSaveUL(mDeviceIdleMode, FIREWALL_CHAIN_DOZABLE,
                    mUidFirewallDozableRules);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    void updateRuleForDeviceIdleUL(int uid) {
        updateRulesForWhitelistedPowerSaveUL(uid, mDeviceIdleMode, FIREWALL_CHAIN_DOZABLE);
    }

    // NOTE: since both fw_dozable and fw_powersave uses the same map
    // (mPowerSaveTempWhitelistAppIds) for allowlisting, we can reuse their logic in this method.
    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForWhitelistedPowerSaveUL(boolean enabled, int chain,
            SparseIntArray rules) {
        if (enabled) {
            // Sync the whitelists before enabling the chain.  We don't care about the rules if
            // we are disabling the chain.
            final SparseIntArray uidRules = rules;
            uidRules.clear();
            final List<UserInfo> users = mUserManager.getUsers();
            for (int ui = users.size() - 1; ui >= 0; ui--) {
                UserInfo user = users.get(ui);
                updateRulesForWhitelistedAppIds(uidRules, mPowerSaveTempWhitelistAppIds, user.id);
                updateRulesForWhitelistedAppIds(uidRules, mPowerSaveWhitelistAppIds, user.id);
                if (chain == FIREWALL_CHAIN_POWERSAVE) {
                    updateRulesForWhitelistedAppIds(uidRules,
                            mPowerSaveWhitelistExceptIdleAppIds, user.id);
                }
            }
            for (int i = mUidState.size() - 1; i >= 0; i--) {
                if (isProcStateAllowedWhileIdleOrPowerSaveMode(mUidState.valueAt(i))) {
                    uidRules.put(mUidState.keyAt(i), FIREWALL_RULE_ALLOW);
                }
            }
            setUidFirewallRulesUL(chain, uidRules, CHAIN_TOGGLE_ENABLE);
        } else {
            setUidFirewallRulesUL(chain, null, CHAIN_TOGGLE_DISABLE);
        }
    }

    private void updateRulesForWhitelistedAppIds(final SparseIntArray uidRules,
            final SparseBooleanArray whitelistedAppIds, int userId) {
        for (int i = whitelistedAppIds.size() - 1; i >= 0; --i) {
            if (whitelistedAppIds.valueAt(i)) {
                final int appId = whitelistedAppIds.keyAt(i);
                final int uid = UserHandle.getUid(userId, appId);
                uidRules.put(uid, FIREWALL_RULE_ALLOW);
            }
        }
    }

    /**
     * Returns whether a uid is allowlisted from power saving restrictions (eg: Battery Saver, Doze
     * mode, and app idle).
     *
     * @param deviceIdleMode if true then we don't consider
     *        {@link #mPowerSaveWhitelistExceptIdleAppIds} for checking if the {@param uid} is
     *        allowlisted.
     */
    @GuardedBy("mUidRulesFirstLock")
    private boolean isWhitelistedFromPowerSaveUL(int uid, boolean deviceIdleMode) {
        final int appId = UserHandle.getAppId(uid);
        boolean isWhitelisted = mPowerSaveTempWhitelistAppIds.get(appId)
                || mPowerSaveWhitelistAppIds.get(appId);
        if (!deviceIdleMode) {
            isWhitelisted = isWhitelisted || isWhitelistedFromPowerSaveExceptIdleUL(uid);
        }
        return isWhitelisted;
    }

    /**
     * Returns whether a uid is allowlisted from power saving restrictions, except Device idle
     * (eg: Battery Saver and app idle).
     */
    @GuardedBy("mUidRulesFirstLock")
    private boolean isWhitelistedFromPowerSaveExceptIdleUL(int uid) {
        final int appId = UserHandle.getAppId(uid);
        return mPowerSaveWhitelistExceptIdleAppIds.get(appId);
    }

    // NOTE: since both fw_dozable and fw_powersave uses the same map
    // (mPowerSaveTempWhitelistAppIds) for allowlisting, we can reuse their logic in this method.
    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForWhitelistedPowerSaveUL(int uid, boolean enabled, int chain) {
        if (enabled) {
            final boolean isWhitelisted = isWhitelistedFromPowerSaveUL(uid,
                    chain == FIREWALL_CHAIN_DOZABLE);
            if (isWhitelisted || isUidForegroundOnRestrictPowerUL(uid)) {
                setUidFirewallRule(chain, uid, FIREWALL_RULE_ALLOW);
            } else {
                setUidFirewallRule(chain, uid, FIREWALL_RULE_DEFAULT);
            }
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    void updateRulesForAppIdleUL() {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateRulesForAppIdleUL");
        try {
            final SparseIntArray uidRules = mUidFirewallStandbyRules;
            uidRules.clear();

            // Fully update the app idle firewall chain.
            final List<UserInfo> users = mUserManager.getUsers();
            for (int ui = users.size() - 1; ui >= 0; ui--) {
                UserInfo user = users.get(ui);
                int[] idleUids = mUsageStats.getIdleUidsForUser(user.id);
                for (int uid : idleUids) {
                    if (!mPowerSaveTempWhitelistAppIds.get(UserHandle.getAppId(uid), false)) {
                        // quick check: if this uid doesn't have INTERNET permission, it
                        // doesn't have network access anyway, so it is a waste to mess
                        // with it here.
                        if (hasInternetPermissionUL(uid) && !isUidForegroundOnRestrictPowerUL(uid)) {
                            uidRules.put(uid, FIREWALL_RULE_DENY);
                        }
                    }
                }
            }

            setUidFirewallRulesUL(FIREWALL_CHAIN_STANDBY, uidRules, CHAIN_TOGGLE_NONE);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    void updateRuleForAppIdleUL(int uid) {
        if (!isUidValidForDenylistRulesUL(uid)) return;

        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateRuleForAppIdleUL: " + uid );
        }
        try {
            int appId = UserHandle.getAppId(uid);
            if (!mPowerSaveTempWhitelistAppIds.get(appId) && isUidIdle(uid)
                    && !isUidForegroundOnRestrictPowerUL(uid)) {
                setUidFirewallRule(FIREWALL_CHAIN_STANDBY, uid, FIREWALL_RULE_DENY);
                if (LOGD) Log.d(TAG, "updateRuleForAppIdleUL DENY " + uid);
            } else {
                setUidFirewallRule(FIREWALL_CHAIN_STANDBY, uid, FIREWALL_RULE_DEFAULT);
                if (LOGD) Log.d(TAG, "updateRuleForAppIdleUL " + uid + " to DEFAULT");
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    /**
     * Toggle the firewall standby chain and inform listeners if the uid rules have effectively
     * changed.
     */
    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForAppIdleParoleUL() {
        final boolean paroled = mAppStandby.isInParole();
        final boolean enableChain = !paroled;

        int ruleCount = mUidFirewallStandbyRules.size();
        final SparseIntArray blockedUids = new SparseIntArray();
        for (int i = 0; i < ruleCount; i++) {
            final int uid = mUidFirewallStandbyRules.keyAt(i);
            if (!isUidValidForDenylistRulesUL(uid)) {
                continue;
            }
            int oldRules = mUidRules.get(uid);
            if (enableChain) {
                // Chain wasn't enabled before and the other power-related
                // chains are allowlists, so we can clear the
                // MASK_ALL_NETWORKS part of the rules and re-inform listeners if
                // the effective rules result in blocking network access.
                oldRules &= MASK_METERED_NETWORKS;
            } else {
                // Skip if it had no restrictions to begin with
                if ((oldRules & MASK_ALL_NETWORKS) == 0) continue;
            }
            final boolean isUidIdle = !paroled && isUidIdle(uid);
            if (isUidIdle && !mPowerSaveTempWhitelistAppIds.get(UserHandle.getAppId(uid))
                    && !isUidForegroundOnRestrictPowerUL(uid)) {
                mUidFirewallStandbyRules.put(uid, FIREWALL_RULE_DENY);
                blockedUids.put(uid, FIREWALL_RULE_DENY);
            } else {
                mUidFirewallStandbyRules.put(uid, FIREWALL_RULE_DEFAULT);
            }
            final int newUidRules = updateRulesForPowerRestrictionsUL(uid, oldRules,
                    isUidIdle);
            if (newUidRules == RULE_NONE) {
                mUidRules.delete(uid);
            } else {
                mUidRules.put(uid, newUidRules);
            }
        }
        setUidFirewallRulesUL(FIREWALL_CHAIN_STANDBY, blockedUids,
                enableChain ? CHAIN_TOGGLE_ENABLE : CHAIN_TOGGLE_DISABLE);
    }

    /**
     * Update rules that might be changed by {@link #mRestrictBackground},
     * {@link #mRestrictPower}, or {@link #mDeviceIdleMode} value.
     */
    @GuardedBy({"mUidRulesFirstLock", "mNetworkPoliciesSecondLock"})
    private void updateRulesForGlobalChangeAL(boolean restrictedNetworksChanged) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK,
                    "updateRulesForGlobalChangeAL: " + (restrictedNetworksChanged ? "R" : "-"));
        }
        try {
            updateRulesForAppIdleUL();
            updateRulesForRestrictPowerUL();
            updateRulesForRestrictBackgroundUL();
            updateRestrictedModeAllowlistUL();

            // If the set of restricted networks may have changed, re-evaluate those.
            if (restrictedNetworksChanged) {
                normalizePoliciesNL();
                updateNetworkRulesNL();
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    // TODO: rename / document to make it clear these are global (not app-specific) rules
    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForRestrictPowerUL() {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateRulesForRestrictPowerUL");
        try {
            updateRulesForDeviceIdleUL();
            updateRulesForPowerSaveUL();
            forEachUid("updateRulesForRestrictPower",
                    uid -> updateRulesForPowerRestrictionsUL(uid));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForRestrictBackgroundUL() {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "updateRulesForRestrictBackgroundUL");
        try {
            forEachUid("updateRulesForRestrictBackground",
                    uid -> updateRulesForDataUsageRestrictionsUL(uid));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    private void forEachUid(String tag, IntConsumer consumer) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "forEachUid-" + tag);
        }
        try {
            // update rules for all installed applications
            final PackageManager pm = mContext.getPackageManager();
            final List<UserInfo> users;
            final List<ApplicationInfo> apps;

            Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "list-users");
            try {
                users = mUserManager.getUsers();
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
            }
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "list-uids");
            try {
                apps = pm.getInstalledApplications(
                        PackageManager.MATCH_ANY_USER | PackageManager.MATCH_DISABLED_COMPONENTS
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
            }

            final int usersSize = users.size();
            final int appsSize = apps.size();
            for (int i = 0; i < usersSize; i++) {
                final UserInfo user = users.get(i);
                for (int j = 0; j < appsSize; j++) {
                    final ApplicationInfo app = apps.get(j);
                    final int uid = UserHandle.getUid(user.id, app.uid);
                    consumer.accept(uid);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForTempWhitelistChangeUL(int appId) {
        final List<UserInfo> users = mUserManager.getUsers();
        final int numUsers = users.size();
        for (int i = 0; i < numUsers; i++) {
            final UserInfo user = users.get(i);
            int uid = UserHandle.getUid(user.id, appId);
            // Update external firewall rules.
            updateRuleForAppIdleUL(uid);
            updateRuleForDeviceIdleUL(uid);
            updateRuleForRestrictPowerUL(uid);
            // Update internal rules.
            updateRulesForPowerRestrictionsUL(uid);
        }
    }

    // TODO: the MEDIA / DRM restriction might not be needed anymore, in which case both
    // methods below could be merged into a isUidValidForRules() method.
    @GuardedBy("mUidRulesFirstLock")
    private boolean isUidValidForDenylistRulesUL(int uid) {
        // allow rules on specific system services, and any apps
        if (uid == android.os.Process.MEDIA_UID || uid == android.os.Process.DRM_UID
                || isUidValidForAllowlistRulesUL(uid)) {
            return true;
        }

        return false;
    }

    @GuardedBy("mUidRulesFirstLock")
    private boolean isUidValidForAllowlistRulesUL(int uid) {
        return UserHandle.isApp(uid) && hasInternetPermissionUL(uid);
    }

    /**
     * Set whether or not an app should be allowlisted for network access while in app idle. Other
     * power saving restrictions may still apply.
     */
    @VisibleForTesting
    void setAppIdleWhitelist(int uid, boolean shouldWhitelist) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mUidRulesFirstLock) {
            if (mAppIdleTempWhitelistAppIds.get(uid) == shouldWhitelist) {
                // No change.
                return;
            }

            final long token = Binder.clearCallingIdentity();
            try {
                mLogger.appIdleWlChanged(uid, shouldWhitelist);
                if (shouldWhitelist) {
                    mAppIdleTempWhitelistAppIds.put(uid, true);
                } else {
                    mAppIdleTempWhitelistAppIds.delete(uid);
                }
                updateRuleForAppIdleUL(uid);
                updateRulesForPowerRestrictionsUL(uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    /** Return the list of UIDs currently in the app idle allowlist. */
    @VisibleForTesting
    int[] getAppIdleWhitelist() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mUidRulesFirstLock) {
            final int len = mAppIdleTempWhitelistAppIds.size();
            int[] uids = new int[len];
            for (int i = 0; i < len; ++i) {
                uids[i] = mAppIdleTempWhitelistAppIds.keyAt(i);
            }
            return uids;
        }
    }

    /** Returns if the UID is currently considered idle. */
    @VisibleForTesting
    boolean isUidIdle(int uid) {
        synchronized (mUidRulesFirstLock) {
            if (mAppIdleTempWhitelistAppIds.get(uid)) {
                // UID is temporarily allowlisted.
                return false;
            }
        }

        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        final int userId = UserHandle.getUserId(uid);

        if (packages != null) {
            for (String packageName : packages) {
                if (!mUsageStats.isAppIdle(packageName, uid, userId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Checks if an uid has INTERNET permissions.
     * <p>
     * Useful for the cases where the lack of network access can simplify the rules.
     */
    @GuardedBy("mUidRulesFirstLock")
    private boolean hasInternetPermissionUL(int uid) {
        try {
            if (mInternetPermissionMap.get(uid)) {
                return true;
            }
            // If the cache shows that uid doesn't have internet permission,
            // then always re-check with PackageManager just to be safe.
            final boolean hasPermission = mIPm.checkUidPermission(Manifest.permission.INTERNET,
                    uid) == PackageManager.PERMISSION_GRANTED;
            mInternetPermissionMap.put(uid, hasPermission);
            return hasPermission;
        } catch (RemoteException e) {
        }
        return true;
    }

    /**
     * Clears all state - internal and external - associated with an UID.
     */
    @GuardedBy("mUidRulesFirstLock")
    private void onUidDeletedUL(int uid) {
        // First cleanup in-memory state synchronously...
        mUidRules.delete(uid);
        mUidPolicy.delete(uid);
        mUidFirewallStandbyRules.delete(uid);
        mUidFirewallDozableRules.delete(uid);
        mUidFirewallPowerSaveRules.delete(uid);
        mPowerSaveWhitelistExceptIdleAppIds.delete(uid);
        mPowerSaveWhitelistAppIds.delete(uid);
        mPowerSaveTempWhitelistAppIds.delete(uid);
        mAppIdleTempWhitelistAppIds.delete(uid);
        mUidFirewallRestrictedModeRules.delete(uid);

        // ...then update iptables asynchronously.
        mHandler.obtainMessage(MSG_RESET_FIREWALL_RULES_BY_UID, uid, 0).sendToTarget();
    }

    /**
     * Applies network rules to bandwidth and firewall controllers based on uid policy.
     *
     * <p>There are currently 4 types of restriction rules:
     * <ul>
     * <li>Doze mode
     * <li>App idle mode
     * <li>Battery Saver Mode (also referred as power save).
     * <li>Data Saver Mode (The Feature Formerly Known As 'Restrict Background Data').
     * </ul>
     *
     * <p>This method changes both the external firewall rules and the internal state.
     */
    @GuardedBy("mUidRulesFirstLock")
    private void updateRestrictionRulesForUidUL(int uid) {
        // Methods below only changes the firewall rules for the power-related modes.
        updateRuleForDeviceIdleUL(uid);
        updateRuleForAppIdleUL(uid);
        updateRuleForRestrictPowerUL(uid);

        // If the uid has the necessary permissions, then it should be added to the restricted mode
        // firewall allowlist.
        updateRestrictedModeForUidUL(uid);

        // Update internal state for power-related modes.
        updateRulesForPowerRestrictionsUL(uid);

        // Update firewall and internal rules for Data Saver Mode.
        updateRulesForDataUsageRestrictionsUL(uid);
    }

    /**
     * Applies network rules to bandwidth controllers based on process state and user-defined
     * restrictions (allowlist / denylist).
     *
     * <p>
     * {@code netd} defines 3 firewall chains that govern whether an app has access to metered
     * networks:
     * <ul>
     * <li>@{code bw_penalty_box}: UIDs added to this chain do not have access (denylist).
     * <li>@{code bw_happy_box}: UIDs added to this chain have access (allowlist), unless they're
     *     also in denylist.
     * <li>@{code bw_data_saver}: when enabled (through {@link #setRestrictBackground(boolean)}),
     *     no UIDs other than those in allowlist will have access.
     * <ul>
     *
     * <p>The @{code bw_penalty_box} and @{code bw_happy_box} are primarily managed through the
     * {@link #setUidPolicy(int, int)} and {@link #addRestrictBackgroundAllowlistedUid(int)} /
     * {@link #removeRestrictBackgroundDenylistedUid(int)} methods (for denylist and allowlist
     * respectively): these methods set the proper internal state (denylist / allowlist), then call
     * this ({@link #updateRulesForDataUsageRestrictionsUL(int)}) to propagate the rules to
     * {@link INetworkManagementService}, but this method should also be called in events (like
     * Data Saver Mode flips or UID state changes) that might affect the foreground app, since the
     * following rules should also be applied:
     *
     * <ul>
     * <li>When Data Saver mode is on, the foreground app should be temporarily added to
     *     {@code bw_happy_box} before the @{code bw_data_saver} chain is enabled.
     * <li>If the foreground app was restricted by the user (i.e. has the policy
     *     {@code POLICY_REJECT_METERED_BACKGROUND}), it should be temporarily removed from
     *     {@code bw_penalty_box}.
     * <li>When the app leaves foreground state, the temporary changes above should be reverted.
     * </ul>
     *
     * <p>For optimization, the rules are only applied on user apps that have internet access
     * permission, since there is no need to change the {@code iptables} rule if the app does not
     * have permission to use the internet.
     *
     * <p>The {@link #mUidRules} map is used to define the transtion of states of an UID.
     *
     */
    private void updateRulesForDataUsageRestrictionsUL(int uid) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK,
                    "updateRulesForDataUsageRestrictionsUL: " + uid);
        }
        try {
            updateRulesForDataUsageRestrictionsULInner(uid);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    private void updateRulesForDataUsageRestrictionsULInner(int uid) {
        if (!isUidValidForAllowlistRulesUL(uid)) {
            if (LOGD) Slog.d(TAG, "no need to update restrict data rules for uid " + uid);
            return;
        }

        final int uidPolicy = mUidPolicy.get(uid, POLICY_NONE);
        final int oldUidRules = mUidRules.get(uid, RULE_NONE);
        final boolean isForeground = isUidForegroundOnRestrictBackgroundUL(uid);
        final boolean isRestrictedByAdmin = isRestrictedByAdminUL(uid);
        UidBlockedState uidBlockedState = mUidBlockedState.get(uid);
        if (uidBlockedState == null) {
            uidBlockedState = new UidBlockedState();
            mUidBlockedState.put(uid, uidBlockedState);
        }

        final boolean isDenied = (uidPolicy & POLICY_REJECT_METERED_BACKGROUND) != 0;
        final boolean isAllowed = (uidPolicy & POLICY_ALLOW_METERED_BACKGROUND) != 0;

        // copy oldUidRules and clear out METERED_NETWORKS rules.
        int newUidRules = oldUidRules & (~MASK_METERED_NETWORKS);

        // First step: define the new rule based on user restrictions and foreground state.
        if (isRestrictedByAdmin) {
            newUidRules |= RULE_REJECT_METERED;
        } else if (isForeground) {
            if (isDenied || (mRestrictBackground && !isAllowed)) {
                newUidRules |= RULE_TEMPORARY_ALLOW_METERED;
            } else if (isAllowed) {
                newUidRules |= RULE_ALLOW_METERED;
            }
        } else {
            if (isDenied) {
                newUidRules |= RULE_REJECT_METERED;
            } else if (mRestrictBackground && isAllowed) {
                newUidRules |= RULE_ALLOW_METERED;
            }
        }

        int newBlockedReasons = BLOCKED_REASON_NONE;
        int newAllowedReasons = ALLOWED_REASON_NONE;
        newBlockedReasons |= (isRestrictedByAdmin ? BLOCKED_METERED_REASON_ADMIN_DISABLED : 0);
        newBlockedReasons |= (mRestrictBackground ? BLOCKED_METERED_REASON_DATA_SAVER : 0);
        newBlockedReasons |= (isDenied ? BLOCKED_METERED_REASON_USER_RESTRICTED : 0);

        newAllowedReasons |= (isSystem(uid) ? ALLOWED_METERED_REASON_SYSTEM : 0);
        newAllowedReasons |= (isForeground ? ALLOWED_METERED_REASON_FOREGROUND : 0);
        newAllowedReasons |= (isAllowed ? ALLOWED_METERED_REASON_USER_EXEMPTED : 0);

        if (LOGV) {
            Log.v(TAG, "updateRuleForRestrictBackgroundUL(" + uid + ")"
                    + ": isForeground=" +isForeground
                    + ", isDenied=" + isDenied
                    + ", isAllowed=" + isAllowed
                    + ", isRestrictedByAdmin=" + isRestrictedByAdmin
                    + ", oldRule=" + uidRulesToString(oldUidRules & MASK_METERED_NETWORKS)
                    + ", newRule=" + uidRulesToString(newUidRules & MASK_METERED_NETWORKS)
                    + ", newUidRules=" + uidRulesToString(newUidRules)
                    + ", oldUidRules=" + uidRulesToString(oldUidRules));
        }

        if (newUidRules == RULE_NONE) {
            mUidRules.delete(uid);
        } else {
            mUidRules.put(uid, newUidRules);
        }

        // Second step: apply bw changes based on change of state.
        if (newUidRules != oldUidRules) {
            if (hasRule(newUidRules, RULE_TEMPORARY_ALLOW_METERED)) {
                // Temporarily allow foreground app, removing from denylist if necessary
                // (since bw_penalty_box prevails over bw_happy_box).

                setMeteredNetworkAllowlist(uid, true);
                // TODO: if statement below is used to avoid an unnecessary call to netd / iptables,
                // but ideally it should be just:
                //    setMeteredNetworkDenylist(uid, isDenied);
                if (isDenied) {
                    setMeteredNetworkDenylist(uid, false);
                }
            } else if (hasRule(oldUidRules, RULE_TEMPORARY_ALLOW_METERED)) {
                // Remove temporary exemption from app that is not on foreground anymore.

                // TODO: if statements below are used to avoid unnecessary calls to netd / iptables,
                // but ideally they should be just:
                //    setMeteredNetworkAllowlist(uid, isAllowed);
                //    setMeteredNetworkDenylist(uid, isDenied);
                if (!isAllowed) {
                    setMeteredNetworkAllowlist(uid, false);
                }
                if (isDenied || isRestrictedByAdmin) {
                    setMeteredNetworkDenylist(uid, true);
                }
            } else if (hasRule(newUidRules, RULE_REJECT_METERED)
                    || hasRule(oldUidRules, RULE_REJECT_METERED)) {
                // Flip state because app was explicitly added or removed to denylist.
                setMeteredNetworkDenylist(uid, (isDenied || isRestrictedByAdmin));
                if (hasRule(oldUidRules, RULE_REJECT_METERED) && isAllowed) {
                    // Since denial prevails over allowance, we need to handle the special case
                    // where app is allowed and denied at the same time (although such
                    // scenario should be blocked by the UI), then it is removed from the denylist.
                    setMeteredNetworkAllowlist(uid, isAllowed);
                }
            } else if (hasRule(newUidRules, RULE_ALLOW_METERED)
                    || hasRule(oldUidRules, RULE_ALLOW_METERED)) {
                // Flip state because app was explicitly added or removed to allowlist.
                setMeteredNetworkAllowlist(uid, isAllowed);
            } else {
                // All scenarios should have been covered above.
                Log.wtf(TAG, "Unexpected change of metered UID state for " + uid
                        + ": foreground=" + isForeground
                        + ", allowlisted=" + isAllowed
                        + ", denylisted=" + isDenied
                        + ", isRestrictedByAdmin=" + isRestrictedByAdmin
                        + ", newRule=" + uidRulesToString(newUidRules)
                        + ", oldRule=" + uidRulesToString(oldUidRules));
            }

            // Dispatch changed rule to existing listeners.
            mHandler.obtainMessage(MSG_RULES_CHANGED, uid, newUidRules).sendToTarget();
        }

        final int oldEffectiveBlockedReasons = uidBlockedState.effectiveBlockedReasons;
        uidBlockedState.blockedReasons = (uidBlockedState.blockedReasons
                & ~BLOCKED_METERED_REASON_MASK) | newBlockedReasons;
        uidBlockedState.allowedReasons = (uidBlockedState.allowedReasons
                & ~ALLOWED_METERED_REASON_MASK) | newAllowedReasons;
        uidBlockedState.updateEffectiveBlockedReasons();
        if (oldEffectiveBlockedReasons != uidBlockedState.effectiveBlockedReasons) {
            mHandler.obtainMessage(MSG_BLOCKED_REASON_CHANGED, uid,
                    uidBlockedState.effectiveBlockedReasons, oldEffectiveBlockedReasons)
                    .sendToTarget();
        }
    }

    /**
     * Updates the power-related part of the {@link #mUidRules} for a given map, and notify external
     * listeners in case of change.
     * <p>
     * There are 3 power-related rules that affects whether an app has background access on
     * non-metered networks, and when the condition applies and the UID is not allowed for power
     * restriction, it's added to the equivalent firewall chain:
     * <ul>
     * <li>App is idle: {@code fw_standby} firewall chain.
     * <li>Device is idle: {@code fw_dozable} firewall chain.
     * <li>Battery Saver Mode is on: {@code fw_powersave} firewall chain.
     * </ul>
     * <p>
     * This method updates the power-related part of the {@link #mUidRules} for a given uid based on
     * these modes, the UID process state (foreground or not), and the UID allowlist state.
     * <p>
     * <strong>NOTE: </strong>This method does not update the firewall rules on {@code netd}.
     */
    @GuardedBy("mUidRulesFirstLock")
    private void updateRulesForPowerRestrictionsUL(int uid) {
        final int oldUidRules = mUidRules.get(uid, RULE_NONE);

        final int newUidRules = updateRulesForPowerRestrictionsUL(uid, oldUidRules,
                isUidIdle(uid));

        if (newUidRules == RULE_NONE) {
            mUidRules.delete(uid);
        } else {
            mUidRules.put(uid, newUidRules);
        }
    }

    /**
     * Similar to above but ignores idle state if app standby is currently disabled by parole.
     *
     * @param uid the uid of the app to update rules for
     * @param oldUidRules the current rules for the uid, in order to determine if there's a change
     * @param isUidIdle whether uid is idle or not
     *
     * @return the new computed rules for the uid
     */
    @GuardedBy("mUidRulesFirstLock")
    private int updateRulesForPowerRestrictionsUL(int uid, int oldUidRules, boolean isUidIdle) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK,
                    "updateRulesForPowerRestrictionsUL: " + uid + "/" + oldUidRules + "/"
                            + (isUidIdle ? "I" : "-"));
        }
        try {
            return updateRulesForPowerRestrictionsULInner(uid, oldUidRules, isUidIdle);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private int updateRulesForPowerRestrictionsULInner(int uid, int oldUidRules,
            boolean isUidIdle) {
        if (!isUidValidForDenylistRulesUL(uid)) {
            if (LOGD) Slog.d(TAG, "no need to update restrict power rules for uid " + uid);
            return RULE_NONE;
        }

        final boolean restrictMode = isUidIdle || mRestrictPower || mDeviceIdleMode;
        final boolean isForeground = isUidForegroundOnRestrictPowerUL(uid);

        final boolean isWhitelisted = isWhitelistedFromPowerSaveUL(uid, mDeviceIdleMode);

        // Copy existing uid rules and clear ALL_NETWORK rules.
        int newUidRules = oldUidRules & (~MASK_ALL_NETWORKS);

        UidBlockedState uidBlockedState = mUidBlockedState.get(uid);
        if (uidBlockedState == null) {
            uidBlockedState = new UidBlockedState();
            mUidBlockedState.put(uid, uidBlockedState);
        }

        // First step: define the new rule based on user restrictions and foreground state.

        // NOTE: if statements below could be inlined, but it's easier to understand the logic
        // by considering the foreground and non-foreground states.
        if (isForeground) {
            if (restrictMode) {
                newUidRules |= RULE_ALLOW_ALL;
            }
        } else if (restrictMode) {
            newUidRules |= isWhitelisted ? RULE_ALLOW_ALL : RULE_REJECT_ALL;
        }

        int newBlockedReasons = BLOCKED_REASON_NONE;
        int newAllowedReasons = ALLOWED_REASON_NONE;
        newBlockedReasons |= (mRestrictPower ? BLOCKED_REASON_BATTERY_SAVER : 0);
        newBlockedReasons |= (mDeviceIdleMode ? BLOCKED_REASON_DOZE : 0);
        newBlockedReasons |= (isUidIdle ? BLOCKED_REASON_APP_STANDBY : 0);
        newBlockedReasons |= (uidBlockedState.blockedReasons & BLOCKED_REASON_RESTRICTED_MODE);

        newAllowedReasons |= (isSystem(uid) ? ALLOWED_REASON_SYSTEM : 0);
        newAllowedReasons |= (isForeground ? ALLOWED_REASON_FOREGROUND : 0);
        newAllowedReasons |= (isWhitelistedFromPowerSaveUL(uid, true)
                ? ALLOWED_REASON_POWER_SAVE_ALLOWLIST : 0);
        newAllowedReasons |= (isWhitelistedFromPowerSaveExceptIdleUL(uid)
                ? ALLOWED_REASON_POWER_SAVE_EXCEPT_IDLE_ALLOWLIST : 0);

        if (LOGV) {
            Log.v(TAG, "updateRulesForPowerRestrictionsUL(" + uid + ")"
                    + ", isIdle: " + isUidIdle
                    + ", mRestrictPower: " + mRestrictPower
                    + ", mDeviceIdleMode: " + mDeviceIdleMode
                    + ", isForeground=" + isForeground
                    + ", isWhitelisted=" + isWhitelisted
                    + ", oldRule=" + uidRulesToString(oldUidRules & MASK_ALL_NETWORKS)
                    + ", newRule=" + uidRulesToString(newUidRules & MASK_ALL_NETWORKS)
                    + ", newUidRules=" + uidRulesToString(newUidRules)
                    + ", oldUidRules=" + uidRulesToString(oldUidRules));
        }

        // Second step: notify listeners if state changed.
        if (newUidRules != oldUidRules) {
            if ((newUidRules & MASK_ALL_NETWORKS) == RULE_NONE || hasRule(newUidRules,
                    RULE_ALLOW_ALL)) {
                if (LOGV) Log.v(TAG, "Allowing non-metered access for UID " + uid);
            } else if (hasRule(newUidRules, RULE_REJECT_ALL)) {
                if (LOGV) Log.v(TAG, "Rejecting non-metered access for UID " + uid);
            } else {
                // All scenarios should have been covered above
                Log.wtf(TAG, "Unexpected change of non-metered UID state for " + uid
                        + ": foreground=" + isForeground
                        + ", whitelisted=" + isWhitelisted
                        + ", newRule=" + uidRulesToString(newUidRules)
                        + ", oldRule=" + uidRulesToString(oldUidRules));
            }
            mHandler.obtainMessage(MSG_RULES_CHANGED, uid, newUidRules).sendToTarget();
        }

        final int oldEffectiveBlockedReasons = uidBlockedState.effectiveBlockedReasons;
        uidBlockedState.blockedReasons = (uidBlockedState.blockedReasons
                & BLOCKED_METERED_REASON_MASK) | newBlockedReasons;
        uidBlockedState.allowedReasons = (uidBlockedState.allowedReasons
                & ALLOWED_METERED_REASON_MASK) | newAllowedReasons;
        uidBlockedState.updateEffectiveBlockedReasons();
        if (oldEffectiveBlockedReasons != uidBlockedState.effectiveBlockedReasons) {
            mHandler.obtainMessage(MSG_BLOCKED_REASON_CHANGED, uid,
                    uidBlockedState.effectiveBlockedReasons, oldEffectiveBlockedReasons)
                    .sendToTarget();
        }

        return newUidRules;
    }

    private class NetPolicyAppIdleStateChangeListener extends AppIdleStateChangeListener {
        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle, int bucket,
                int reason) {
            try {
                final int uid = mContext.getPackageManager().getPackageUidAsUser(packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                synchronized (mUidRulesFirstLock) {
                    mLogger.appIdleStateChanged(uid, idle);
                    updateRuleForAppIdleUL(uid);
                    updateRulesForPowerRestrictionsUL(uid);
                }
            } catch (NameNotFoundException nnfe) {
            }
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            synchronized (mUidRulesFirstLock) {
                mLogger.paroleStateChanged(isParoleOn);
                updateRulesForAppIdleParoleUL();
            }
        }
    }

    private void dispatchUidRulesChanged(INetworkPolicyListener listener, int uid, int uidRules) {
        try {
            listener.onUidRulesChanged(uid, uidRules);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchMeteredIfacesChanged(INetworkPolicyListener listener,
            String[] meteredIfaces) {
        try {
            listener.onMeteredIfacesChanged(meteredIfaces);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchRestrictBackgroundChanged(INetworkPolicyListener listener,
            boolean restrictBackground) {
        try {
            listener.onRestrictBackgroundChanged(restrictBackground);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchUidPoliciesChanged(INetworkPolicyListener listener, int uid,
            int uidPolicies) {
        try {
            listener.onUidPoliciesChanged(uid, uidPolicies);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchSubscriptionOverride(INetworkPolicyListener listener, int subId,
            int overrideMask, int overrideValue, int[] networkTypes) {
        try {
            listener.onSubscriptionOverride(subId, overrideMask, overrideValue, networkTypes);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchSubscriptionPlansChanged(INetworkPolicyListener listener, int subId,
            SubscriptionPlan[] plans) {
        try {
            listener.onSubscriptionPlansChanged(subId, plans);
        } catch (RemoteException ignored) {
        }
    }

    private void dispatchBlockedReasonChanged(INetworkPolicyListener listener, int uid,
            int oldBlockedReasons, int newBlockedReasons) {
        try {
            listener.onBlockedReasonChanged(uid, oldBlockedReasons, newBlockedReasons);
        } catch (RemoteException ignored) {
        }
    }

    private final Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RULES_CHANGED: {
                    final int uid = msg.arg1;
                    final int uidRules = msg.arg2;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchUidRulesChanged(listener, uid, uidRules);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_METERED_IFACES_CHANGED: {
                    final String[] meteredIfaces = (String[]) msg.obj;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchMeteredIfacesChanged(listener, meteredIfaces);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_STATS_PROVIDER_WARNING_OR_LIMIT_REACHED: {
                    mNetworkStats.forceUpdate();

                    synchronized (mNetworkPoliciesSecondLock) {
                        // Some providers might hit the limit reached event prior to others. Thus,
                        // re-calculate and update interface quota for every provider is needed.
                        updateNetworkRulesNL();
                        updateNetworkEnabledNL();
                        updateNotificationsNL();
                    }
                    return true;
                }
                case MSG_LIMIT_REACHED: {
                    final String iface = (String) msg.obj;
                    synchronized (mMeteredIfacesLock) {
                        // fast return if not needed.
                        if (!mMeteredIfaces.contains(iface)) {
                            return true;
                        }
                    }

                    // force stats update to make sure the service have the numbers that caused
                    // alert to trigger.
                    mNetworkStats.forceUpdate();

                    synchronized (mNetworkPoliciesSecondLock) {
                        updateNetworkRulesNL();
                        updateNetworkEnabledNL();
                        updateNotificationsNL();
                    }
                    return true;
                }
                case MSG_RESTRICT_BACKGROUND_CHANGED: {
                    final boolean restrictBackground = msg.arg1 != 0;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchRestrictBackgroundChanged(listener, restrictBackground);
                    }
                    mListeners.finishBroadcast();
                    final Intent intent =
                            new Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
                    intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                    return true;
                }
                case MSG_POLICIES_CHANGED: {
                    final int uid = msg.arg1;
                    final int policy = msg.arg2;
                    final Boolean notifyApp = (Boolean) msg.obj;
                    // First notify internal listeners...
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchUidPoliciesChanged(listener, uid, policy);
                    }
                    mListeners.finishBroadcast();
                    // ...then apps listening to ACTION_RESTRICT_BACKGROUND_CHANGED
                    if (notifyApp.booleanValue()) {
                        broadcastRestrictBackgroundChanged(uid, notifyApp);
                    }
                    return true;
                }
                case MSG_ADVISE_PERSIST_THRESHOLD: {
                    final long lowestRule = (Long) msg.obj;
                    // make sure stats are recorded frequently enough; we aim
                    // for 2MB threshold for 2GB/month rules.
                    final long persistThreshold = lowestRule / 1000;
                    mNetworkStats.advisePersistThreshold(persistThreshold);
                    return true;
                }
                case MSG_UPDATE_INTERFACE_QUOTA: {
                    final String iface = (String) msg.obj;
                    // int params need to be stitched back into a long
                    final long quota = ((long) msg.arg1 << 32) | (msg.arg2 & 0xFFFFFFFFL);
                    removeInterfaceQuota(iface);
                    setInterfaceQuota(iface, quota);
                    mNetworkStats.setStatsProviderLimitAsync(iface, quota);
                    return true;
                }
                case MSG_REMOVE_INTERFACE_QUOTA: {
                    final String iface = (String) msg.obj;
                    removeInterfaceQuota(iface);
                    mNetworkStats.setStatsProviderLimitAsync(iface, QUOTA_UNLIMITED);
                    return true;
                }
                case MSG_RESET_FIREWALL_RULES_BY_UID: {
                    resetUidFirewallRules(msg.arg1);
                    return true;
                }
                case MSG_SUBSCRIPTION_OVERRIDE: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final int subId = (int) args.arg1;
                    final int overrideMask = (int) args.arg2;
                    final int overrideValue = (int) args.arg3;
                    final int[] networkTypes = (int[]) args.arg4;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchSubscriptionOverride(listener, subId, overrideMask, overrideValue,
                                networkTypes);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_METERED_RESTRICTED_PACKAGES_CHANGED: {
                    final int userId = msg.arg1;
                    final Set<String> packageNames = (Set<String>) msg.obj;
                    setMeteredRestrictedPackagesInternal(packageNames, userId);
                    return true;
                }
                case MSG_SET_NETWORK_TEMPLATE_ENABLED: {
                    final NetworkTemplate template = (NetworkTemplate) msg.obj;
                    final boolean enabled = msg.arg1 != 0;
                    setNetworkTemplateEnabledInner(template, enabled);
                    return true;
                }
                case MSG_SUBSCRIPTION_PLANS_CHANGED: {
                    final SubscriptionPlan[] plans = (SubscriptionPlan[]) msg.obj;
                    final int subId = msg.arg1;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchSubscriptionPlansChanged(listener, subId, plans);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_BLOCKED_REASON_CHANGED: {
                    final int uid = msg.arg1;
                    final int newBlockedReasons = msg.arg2;
                    final int oldBlockedReasons = (int) msg.obj;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchBlockedReasonChanged(listener, uid,
                                oldBlockedReasons, newBlockedReasons);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

    private final Handler.Callback mUidEventHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case UID_MSG_STATE_CHANGED: {
                    final UidStateCallbackInfo uidStateCallbackInfo =
                            (UidStateCallbackInfo) msg.obj;
                    final int uid = uidStateCallbackInfo.uid;
                    final int procState = uidStateCallbackInfo.procState;
                    final long procStateSeq = uidStateCallbackInfo.procStateSeq;
                    final int capability = uidStateCallbackInfo.capability;

                    handleUidChanged(uid, procState, procStateSeq, capability);
                    return true;
                }
                case UID_MSG_GONE: {
                    final int uid = msg.arg1;
                    handleUidGone(uid);
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

    void handleUidChanged(int uid, int procState, long procStateSeq,
            @ProcessCapability int capability) {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "onUidStateChanged");
        try {
            boolean updated;
            synchronized (mUidRulesFirstLock) {
                // We received a uid state change callback, add it to the history so that it
                // will be useful for debugging.
                mLogger.uidStateChanged(uid, procState, procStateSeq, capability);
                // Now update the network policy rules as per the updated uid state.
                updated = updateUidStateUL(uid, procState, capability);
                // Updating the network rules is done, so notify AMS about this.
                mActivityManagerInternal.notifyNetworkPolicyRulesUpdated(uid, procStateSeq);
            }
            // Do this without the lock held. handleUidChanged() and handleUidGone() are
            // called from the handler, so there's no multi-threading issue.
            if (updated) {
                updateNetworkStats(uid, isProcStateAllowedWhileOnRestrictBackground(procState));
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    void handleUidGone(int uid) {
        Trace.traceBegin(Trace.TRACE_TAG_NETWORK, "onUidGone");
        try {
            boolean updated;
            synchronized (mUidRulesFirstLock) {
                updated = removeUidStateUL(uid);
            }
            // Do this without the lock held. handleUidChanged() and handleUidGone() are
            // called from the handler, so there's no multi-threading issue.
            if (updated) {
                updateNetworkStats(uid, false);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    private void broadcastRestrictBackgroundChanged(int uid, Boolean changed) {
        final PackageManager pm = mContext.getPackageManager();
        final String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) {
            final int userId = UserHandle.getUserId(uid);
            for (String packageName : packages) {
                final Intent intent =
                        new Intent(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
                intent.setPackage(packageName);
                intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
            }
        }
    }

    private void setInterfaceQuotaAsync(String iface, long quotaBytes) {
        // long quotaBytes split up into two ints to fit in message
        mHandler.obtainMessage(MSG_UPDATE_INTERFACE_QUOTA, (int) (quotaBytes >> 32),
                (int) (quotaBytes & 0xFFFFFFFF), iface).sendToTarget();
    }

    private void setInterfaceQuota(String iface, long quotaBytes) {
        try {
            mNetworkManager.setInterfaceQuota(iface, quotaBytes);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting interface quota", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void removeInterfaceQuotaAsync(String iface) {
        mHandler.obtainMessage(MSG_REMOVE_INTERFACE_QUOTA, iface).sendToTarget();
    }

    private void removeInterfaceQuota(String iface) {
        try {
            mNetworkManager.removeInterfaceQuota(iface);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem removing interface quota", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void setMeteredNetworkDenylist(int uid, boolean enable) {
        if (LOGV) Slog.v(TAG, "setMeteredNetworkDenylist " + uid + ": " + enable);
        try {
            mNetworkManager.setUidOnMeteredNetworkDenylist(uid, enable);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting denylist (" + enable + ") rules for " + uid, e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void setMeteredNetworkAllowlist(int uid, boolean enable) {
        if (LOGV) Slog.v(TAG, "setMeteredNetworkAllowlist " + uid + ": " + enable);
        try {
            mNetworkManager.setUidOnMeteredNetworkAllowlist(uid, enable);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting allowlist (" + enable + ") rules for " + uid, e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private static final int CHAIN_TOGGLE_NONE = 0;
    private static final int CHAIN_TOGGLE_ENABLE = 1;
    private static final int CHAIN_TOGGLE_DISABLE = 2;
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = false, value = {
            CHAIN_TOGGLE_NONE,
            CHAIN_TOGGLE_ENABLE,
            CHAIN_TOGGLE_DISABLE
    })
    public @interface ChainToggleType {
    }

    /**
     * Calls {@link #setUidFirewallRulesUL(int, SparseIntArray)} and
     * {@link #enableFirewallChainUL(int, boolean)} synchronously.
     *
     * @param chain firewall chain.
     * @param uidRules new UID rules; if {@code null}, only toggles chain state.
     * @param toggle whether the chain should be enabled, disabled, or not changed.
     */
    @GuardedBy("mUidRulesFirstLock")
    private void setUidFirewallRulesUL(int chain, @Nullable SparseIntArray uidRules,
            @ChainToggleType int toggle) {
        if (uidRules != null) {
            setUidFirewallRulesUL(chain, uidRules);
        }
        if (toggle != CHAIN_TOGGLE_NONE) {
            enableFirewallChainUL(chain, toggle == CHAIN_TOGGLE_ENABLE);
        }
    }

    /**
     * Set uid rules on a particular firewall chain. This is going to synchronize the rules given
     * here to netd.  It will clean up dead rules and make sure the target chain only contains rules
     * specified here.
     */
    private void setUidFirewallRulesUL(int chain, SparseIntArray uidRules) {
        try {
            int size = uidRules.size();
            int[] uids = new int[size];
            int[] rules = new int[size];
            for(int index = size - 1; index >= 0; --index) {
                uids[index] = uidRules.keyAt(index);
                rules[index] = uidRules.valueAt(index);
            }
            mNetworkManager.setFirewallUidRules(chain, uids, rules);
            mLogger.firewallRulesChanged(chain, uids, rules);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting firewall uid rules", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Add or remove a uid to the firewall denylist for all network ifaces.
     */
    private void setUidFirewallRule(int chain, int uid, int rule) {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_NETWORK)) {
            Trace.traceBegin(Trace.TRACE_TAG_NETWORK,
                    "setUidFirewallRule: " + chain + "/" + uid + "/" + rule);
        }
        try {
            if (chain == FIREWALL_CHAIN_DOZABLE) {
                mUidFirewallDozableRules.put(uid, rule);
            } else if (chain == FIREWALL_CHAIN_STANDBY) {
                mUidFirewallStandbyRules.put(uid, rule);
            } else if (chain == FIREWALL_CHAIN_POWERSAVE) {
                mUidFirewallPowerSaveRules.put(uid, rule);
            } else if (chain == FIREWALL_CHAIN_RESTRICTED) {
                mUidFirewallRestrictedModeRules.put(uid, rule);
            }

            try {
                mNetworkManager.setFirewallUidRule(chain, uid, rule);
                mLogger.uidFirewallRuleChanged(chain, uid, rule);
            } catch (IllegalStateException e) {
                Log.wtf(TAG, "problem setting firewall uid rules", e);
            } catch (RemoteException e) {
                // ignored; service lives in system_server
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_NETWORK);
        }
    }

    /**
     * Add or remove a uid to the firewall denylist for all network ifaces.
     */
    @GuardedBy("mUidRulesFirstLock")
    private void enableFirewallChainUL(int chain, boolean enable) {
        if (mFirewallChainStates.indexOfKey(chain) >= 0 &&
                mFirewallChainStates.get(chain) == enable) {
            // All is the same, nothing to do.
            return;
        }
        mFirewallChainStates.put(chain, enable);
        try {
            mNetworkManager.setFirewallChainEnabled(chain, enable);
            mLogger.firewallChainEnabled(chain, enable);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem enable firewall chain", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Resets all firewall rules associated with an UID.
     */
    private void resetUidFirewallRules(int uid) {
        try {
            mNetworkManager.setFirewallUidRule(FIREWALL_CHAIN_DOZABLE, uid, FIREWALL_RULE_DEFAULT);
            mNetworkManager.setFirewallUidRule(FIREWALL_CHAIN_STANDBY, uid, FIREWALL_RULE_DEFAULT);
            mNetworkManager
                    .setFirewallUidRule(FIREWALL_CHAIN_POWERSAVE, uid, FIREWALL_RULE_DEFAULT);
            mNetworkManager
                    .setFirewallUidRule(FIREWALL_CHAIN_RESTRICTED, uid, FIREWALL_RULE_DEFAULT);
            mNetworkManager.setUidOnMeteredNetworkAllowlist(uid, false);
            mNetworkManager.setUidOnMeteredNetworkDenylist(uid, false);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem resetting firewall uid rules for " + uid, e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    @Deprecated
    private long getTotalBytes(NetworkTemplate template, long start, long end) {
        return getNetworkTotalBytes(template, start, end);
    }

    private long getNetworkTotalBytes(NetworkTemplate template, long start, long end) {
        try {
            return mNetworkStats.getNetworkTotalBytes(template, start, end);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to read network stats: " + e);
            return 0;
        }
    }

    private NetworkStats getNetworkUidBytes(NetworkTemplate template, long start, long end) {
        try {
            return mNetworkStats.getNetworkUidBytes(template, start, end);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to read network stats: " + e);
            return new NetworkStats(SystemClock.elapsedRealtime(), 0);
        }
    }

    private boolean isBandwidthControlEnabled() {
        final long token = Binder.clearCallingIdentity();
        try {
            return mNetworkManager.isBandwidthControlEnabled();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static Intent buildAllowBackgroundDataIntent() {
        return new Intent(ACTION_ALLOW_BACKGROUND);
    }

    private static Intent buildSnoozeWarningIntent(NetworkTemplate template) {
        final Intent intent = new Intent(ACTION_SNOOZE_WARNING);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    private static Intent buildSnoozeRapidIntent(NetworkTemplate template) {
        final Intent intent = new Intent(ACTION_SNOOZE_RAPID);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    private static Intent buildNetworkOverLimitIntent(Resources res, NetworkTemplate template) {
        final Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(
                res.getString(R.string.config_networkOverLimitComponent)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    private static Intent buildViewDataUsageIntent(Resources res, NetworkTemplate template) {
        final Intent intent = new Intent();
        intent.setComponent(ComponentName.unflattenFromString(
                res.getString(R.string.config_dataUsageSummaryComponent)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    @VisibleForTesting
    void addIdleHandler(IdleHandler handler) {
        mHandler.getLooper().getQueue().addIdleHandler(handler);
    }

    @GuardedBy("mUidRulesFirstLock")
    @VisibleForTesting
    void updateRestrictBackgroundByLowPowerModeUL(final PowerSaveState result) {
        if (mRestrictBackgroundLowPowerMode == result.batterySaverEnabled) {
            // Nothing changed. Nothing to do.
            return;
        }
        mRestrictBackgroundLowPowerMode = result.batterySaverEnabled;

        boolean restrictBackground = mRestrictBackgroundLowPowerMode;
        boolean shouldInvokeRestrictBackground;
        // store the temporary mRestrictBackgroundChangedInBsm and update it at the end.
        boolean localRestrictBgChangedInBsm = mRestrictBackgroundChangedInBsm;

        if (mRestrictBackgroundLowPowerMode) {
            // Try to turn on restrictBackground if (1) it is off and (2) batter saver need to
            // turn it on.
            shouldInvokeRestrictBackground = !mRestrictBackground;
            mRestrictBackgroundBeforeBsm = mRestrictBackground;
            localRestrictBgChangedInBsm = false;
        } else {
            // Try to restore the restrictBackground if it doesn't change in bsm
            shouldInvokeRestrictBackground = !mRestrictBackgroundChangedInBsm;
            restrictBackground = mRestrictBackgroundBeforeBsm;
        }

        if (shouldInvokeRestrictBackground) {
            setRestrictBackgroundUL(restrictBackground, "low_power");
        }

        // Change it at last so setRestrictBackground() won't affect this variable
        mRestrictBackgroundChangedInBsm = localRestrictBgChangedInBsm;
    }

    private static void collectKeys(SparseIntArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void collectKeys(SparseArray<UidState> source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    @Override
    public void factoryReset(String subscriber) {
        mContext.enforceCallingOrSelfPermission(NETWORK_SETTINGS, TAG);

        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_NETWORK_RESET)) {
            return;
        }

        // Turn mobile data limit off
        NetworkPolicy[] policies = getNetworkPolicies(mContext.getOpPackageName());
        NetworkTemplate template = NetworkTemplate.buildTemplateMobileAll(subscriber);
        for (NetworkPolicy policy : policies) {
            if (policy.template.equals(template)) {
                policy.limitBytes = NetworkPolicy.LIMIT_DISABLED;
                policy.inferred = false;
                policy.clearSnooze();
            }
        }
        setNetworkPolicies(policies);

        // Turn restrict background data off
        setRestrictBackground(false);

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL)) {
            // Remove app's "restrict background data" flag
            for (int uid : getUidsWithPolicy(POLICY_REJECT_METERED_BACKGROUND)) {
                setUidPolicy(uid, POLICY_NONE);
            }
        }
    }

    @Override
    public boolean isUidNetworkingBlocked(int uid, boolean isNetworkMetered) {
        final long startTime = mStatLogger.getTime();

        mContext.enforceCallingOrSelfPermission(OBSERVE_NETWORK_POLICY, TAG);
        final int uidRules;
        final boolean isBackgroundRestricted;
        synchronized (mUidRulesFirstLock) {
            uidRules = mUidRules.get(uid, RULE_NONE);
            isBackgroundRestricted = mRestrictBackground;
        }
        final boolean ret = isUidNetworkingBlockedInternal(uid, uidRules, isNetworkMetered,
                isBackgroundRestricted, mLogger);

        mStatLogger.logDurationStat(Stats.IS_UID_NETWORKING_BLOCKED, startTime);

        return ret;
    }

    @Override
    public boolean checkUidNetworkingBlocked(int uid, int uidRules,
            boolean isNetworkMetered, boolean isBackgroundRestricted) {
        mContext.enforceCallingOrSelfPermission(OBSERVE_NETWORK_POLICY, TAG);
        // Log of invoking this function is disabled because it will be called very frequently. And
        // metrics are unlikely needed on this method because the callers are external and this
        // method doesn't take any locks or perform expensive operations.
        return isUidNetworkingBlockedInternal(uid, uidRules, isNetworkMetered,
                isBackgroundRestricted, null);
    }

    @Override
    public boolean isUidRestrictedOnMeteredNetworks(int uid) {
        mContext.enforceCallingOrSelfPermission(OBSERVE_NETWORK_POLICY, TAG);
        final int uidRules;
        final boolean isBackgroundRestricted;
        synchronized (mUidRulesFirstLock) {
            uidRules = mUidRules.get(uid, RULE_ALLOW_ALL);
            isBackgroundRestricted = mRestrictBackground;
        }
        // TODO(b/177490332): The logic here might not be correct because it doesn't consider
        //  RULE_REJECT_METERED condition. And it could be replaced by
        //  isUidNetworkingBlockedInternal().
        return isBackgroundRestricted
                && !hasRule(uidRules, RULE_ALLOW_METERED)
                && !hasRule(uidRules, RULE_TEMPORARY_ALLOW_METERED);
    }

    private static boolean isSystem(int uid) {
        return uid < Process.FIRST_APPLICATION_UID;
    }

    static boolean isUidNetworkingBlockedInternal(int uid, int uidRules, boolean isNetworkMetered,
            boolean isBackgroundRestricted, @Nullable NetworkPolicyLogger logger) {
        final int reason;
        // Networks are never blocked for system components
        if (isSystem(uid)) {
            reason = NTWK_ALLOWED_SYSTEM;
        } else if (hasRule(uidRules, RULE_REJECT_RESTRICTED_MODE)) {
            reason = NTWK_BLOCKED_RESTRICTED_MODE;
        } else if (hasRule(uidRules, RULE_REJECT_ALL)) {
            reason = NTWK_BLOCKED_POWER;
        } else if (!isNetworkMetered) {
            reason = NTWK_ALLOWED_NON_METERED;
        } else if (hasRule(uidRules, RULE_REJECT_METERED)) {
            reason = NTWK_BLOCKED_DENYLIST;
        } else if (hasRule(uidRules, RULE_ALLOW_METERED)) {
            reason = NTWK_ALLOWED_ALLOWLIST;
        } else if (hasRule(uidRules, RULE_TEMPORARY_ALLOW_METERED)) {
            reason = NTWK_ALLOWED_TMP_ALLOWLIST;
        } else if (isBackgroundRestricted) {
            reason = NTWK_BLOCKED_BG_RESTRICT;
        } else {
            reason = NTWK_ALLOWED_DEFAULT;
        }

        final boolean blocked;
        switch(reason) {
            case NTWK_ALLOWED_DEFAULT:
            case NTWK_ALLOWED_NON_METERED:
            case NTWK_ALLOWED_TMP_ALLOWLIST:
            case NTWK_ALLOWED_ALLOWLIST:
            case NTWK_ALLOWED_SYSTEM:
                blocked = false;
                break;
            case NTWK_BLOCKED_RESTRICTED_MODE:
            case NTWK_BLOCKED_POWER:
            case NTWK_BLOCKED_DENYLIST:
            case NTWK_BLOCKED_BG_RESTRICT:
                blocked = true;
                break;
            default:
                throw new IllegalArgumentException();
        }
        if (logger != null) {
            logger.networkBlocked(uid, reason);
        }

        return blocked;
    }

    private class NetworkPolicyManagerInternalImpl extends NetworkPolicyManagerInternal {

        @Override
        public void resetUserState(int userId) {
            synchronized (mUidRulesFirstLock) {
                boolean changed = removeUserStateUL(userId, false, true);
                changed = addDefaultRestrictBackgroundAllowlistUidsUL(userId) || changed;
                if (changed) {
                    synchronized (mNetworkPoliciesSecondLock) {
                        writePolicyAL();
                    }
                }
            }
        }

        @Override
        public void onTempPowerSaveWhitelistChange(int appId, boolean added) {
            synchronized (mUidRulesFirstLock) {
                if (!mSystemReady) {
                    return;
                }
                mLogger.tempPowerSaveWlChanged(appId, added);
                if (added) {
                    mPowerSaveTempWhitelistAppIds.put(appId, true);
                } else {
                    mPowerSaveTempWhitelistAppIds.delete(appId);
                }
                updateRulesForTempWhitelistChangeUL(appId);
            }
        }

        @Override
        public SubscriptionPlan getSubscriptionPlan(Network network) {
            synchronized (mNetworkPoliciesSecondLock) {
                final int subId = getSubIdLocked(network);
                return getPrimarySubscriptionPlanLocked(subId);
            }
        }

        @Override
        public SubscriptionPlan getSubscriptionPlan(NetworkTemplate template) {
            synchronized (mNetworkPoliciesSecondLock) {
                final int subId = findRelevantSubIdNL(template);
                return getPrimarySubscriptionPlanLocked(subId);
            }
        }

        @Override
        public long getSubscriptionOpportunisticQuota(Network network, int quotaType) {
            final long quotaBytes;
            synchronized (mNetworkPoliciesSecondLock) {
                quotaBytes = mSubscriptionOpportunisticQuota.get(getSubIdLocked(network),
                        OPPORTUNISTIC_QUOTA_UNKNOWN);
            }
            if (quotaBytes == OPPORTUNISTIC_QUOTA_UNKNOWN) {
                return OPPORTUNISTIC_QUOTA_UNKNOWN;
            }

            if (quotaType == QUOTA_TYPE_JOBS) {
                return (long) (quotaBytes * Settings.Global.getFloat(mContext.getContentResolver(),
                        NETPOLICY_QUOTA_FRAC_JOBS, QUOTA_FRAC_JOBS_DEFAULT));
            } else if (quotaType == QUOTA_TYPE_MULTIPATH) {
                return (long) (quotaBytes * Settings.Global.getFloat(mContext.getContentResolver(),
                        NETPOLICY_QUOTA_FRAC_MULTIPATH, QUOTA_FRAC_MULTIPATH_DEFAULT));
            } else {
                return OPPORTUNISTIC_QUOTA_UNKNOWN;
            }
        }

        @Override
        public void onAdminDataAvailable() {
            mAdminDataAvailableLatch.countDown();
        }

        @Override
        public void setAppIdleWhitelist(int uid, boolean shouldWhitelist) {
            NetworkPolicyManagerService.this.setAppIdleWhitelist(uid, shouldWhitelist);
        }

        @Override
        public void setMeteredRestrictedPackages(Set<String> packageNames, int userId) {
            setMeteredRestrictedPackagesInternal(packageNames, userId);
        }

        @Override
        public void setMeteredRestrictedPackagesAsync(Set<String> packageNames, int userId) {
            mHandler.obtainMessage(MSG_METERED_RESTRICTED_PACKAGES_CHANGED,
                    userId, 0, packageNames).sendToTarget();
        }

        @Override
        public void onStatsProviderWarningOrLimitReached(@NonNull String tag) {
            Log.v(TAG, "onStatsProviderWarningOrLimitReached: " + tag);
            mHandler.obtainMessage(MSG_STATS_PROVIDER_WARNING_OR_LIMIT_REACHED).sendToTarget();
        }
    }

    private void setMeteredRestrictedPackagesInternal(Set<String> packageNames, int userId) {
        synchronized (mUidRulesFirstLock) {
            final Set<Integer> newRestrictedUids = new ArraySet<>();
            for (String packageName : packageNames) {
                final int uid = getUidForPackage(packageName, userId);
                if (uid >= 0) {
                    newRestrictedUids.add(uid);
                }
            }
            final Set<Integer> oldRestrictedUids = mMeteredRestrictedUids.get(userId);
            mMeteredRestrictedUids.put(userId, newRestrictedUids);
            handleRestrictedPackagesChangeUL(oldRestrictedUids, newRestrictedUids);
            mLogger.meteredRestrictedPkgsChanged(newRestrictedUids);
        }
    }

    private int getUidForPackage(String packageName, int userId) {
        try {
            return mContext.getPackageManager().getPackageUidAsUser(packageName,
                    PackageManager.MATCH_KNOWN_PACKAGES, userId);
        } catch (NameNotFoundException e) {
            return -1;
        }
    }

    private int parseSubId(@NonNull NetworkStateSnapshot snapshot) {
        int subId = INVALID_SUBSCRIPTION_ID;
        if (snapshot.networkCapabilities.hasTransport(TRANSPORT_CELLULAR)) {
            NetworkSpecifier spec = snapshot.networkCapabilities.getNetworkSpecifier();
            if (spec instanceof TelephonyNetworkSpecifier) {
                subId = ((TelephonyNetworkSpecifier) spec).getSubscriptionId();
            }
        }
        return subId;
    }

    @GuardedBy("mNetworkPoliciesSecondLock")
    private int getSubIdLocked(Network network) {
        return mNetIdToSubId.get(network.getNetId(), INVALID_SUBSCRIPTION_ID);
    }

    @GuardedBy("mNetworkPoliciesSecondLock")
    private SubscriptionPlan getPrimarySubscriptionPlanLocked(int subId) {
        final SubscriptionPlan[] plans = mSubscriptionPlans.get(subId);
        if (!ArrayUtils.isEmpty(plans)) {
            for (SubscriptionPlan plan : plans) {
                if (plan.getCycleRule().isRecurring()) {
                    // Recurring plans will always have an active cycle
                    return plan;
                } else {
                    // Non-recurring plans need manual test for active cycle
                    final Range<ZonedDateTime> cycle = plan.cycleIterator().next();
                    if (cycle.contains(ZonedDateTime.now(mClock))) {
                        return plan;
                    }
                }
            }
        }
        return null;
    }

    /**
     * This will only ever be called once - during device boot.
     */
    private void waitForAdminData() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN)) {
            ConcurrentUtils.waitForCountDownNoInterrupt(mAdminDataAvailableLatch,
                    WAIT_FOR_ADMIN_DATA_TIMEOUT_MS, "Wait for admin data");
        }
    }

    private void handleRestrictedPackagesChangeUL(Set<Integer> oldRestrictedUids,
            Set<Integer> newRestrictedUids) {
        if (!mNetworkManagerReady) {
            return;
        }
        if (oldRestrictedUids == null) {
            for (int uid : newRestrictedUids) {
                updateRulesForDataUsageRestrictionsUL(uid);
            }
            return;
        }
        for (int uid : oldRestrictedUids) {
            if (!newRestrictedUids.contains(uid)) {
                updateRulesForDataUsageRestrictionsUL(uid);
            }
        }
        for (int uid : newRestrictedUids) {
            if (!oldRestrictedUids.contains(uid)) {
                updateRulesForDataUsageRestrictionsUL(uid);
            }
        }
    }

    @GuardedBy("mUidRulesFirstLock")
    private boolean isRestrictedByAdminUL(int uid) {
        final Set<Integer> restrictedUids = mMeteredRestrictedUids.get(
                UserHandle.getUserId(uid));
        return restrictedUids != null && restrictedUids.contains(uid);
    }

    private static boolean hasRule(int uidRules, int rule) {
        return (uidRules & rule) != 0;
    }

    private static boolean getBooleanDefeatingNullable(@Nullable PersistableBundle bundle,
            String key, boolean defaultValue) {
        return (bundle != null) ? bundle.getBoolean(key, defaultValue) : defaultValue;
    }

    private class UidBlockedState {
        public int blockedReasons;
        public int allowedReasons;
        public int effectiveBlockedReasons;

        UidBlockedState() {
            blockedReasons = BLOCKED_REASON_NONE;
            allowedReasons = ALLOWED_REASON_NONE;
            effectiveBlockedReasons = BLOCKED_REASON_NONE;
        }

        void updateEffectiveBlockedReasons() {
            effectiveBlockedReasons = blockedReasons;
            // If the uid is not subject to any blocked reasons, then return early
            if (blockedReasons == BLOCKED_REASON_NONE) {
                return;
            }
            if ((allowedReasons & ALLOWED_REASON_SYSTEM) != 0) {
                effectiveBlockedReasons = (blockedReasons & ALLOWED_METERED_REASON_MASK);
            }
            if ((allowedReasons & ALLOWED_METERED_REASON_SYSTEM) != 0) {
                effectiveBlockedReasons = (blockedReasons & ~ALLOWED_METERED_REASON_MASK);
            }
            if ((allowedReasons & ALLOWED_REASON_FOREGROUND) != 0) {
                effectiveBlockedReasons &= ~BLOCKED_REASON_BATTERY_SAVER;
                effectiveBlockedReasons &= ~BLOCKED_REASON_DOZE;
                effectiveBlockedReasons &= ~BLOCKED_REASON_APP_STANDBY;
            }
            if ((allowedReasons & ALLOWED_METERED_REASON_FOREGROUND) != 0) {
                effectiveBlockedReasons &= ~BLOCKED_METERED_REASON_DATA_SAVER;
                effectiveBlockedReasons &= ~BLOCKED_METERED_REASON_USER_RESTRICTED;
            }
            if ((allowedReasons & ALLOWED_REASON_POWER_SAVE_ALLOWLIST) != 0) {
                effectiveBlockedReasons &= ~BLOCKED_REASON_BATTERY_SAVER;
                effectiveBlockedReasons &= ~BLOCKED_REASON_DOZE;
                effectiveBlockedReasons &= ~BLOCKED_REASON_APP_STANDBY;
            }
            if ((allowedReasons & ALLOWED_REASON_POWER_SAVE_EXCEPT_IDLE_ALLOWLIST) != 0) {
                effectiveBlockedReasons &= ~BLOCKED_REASON_BATTERY_SAVER;
                effectiveBlockedReasons &= ~BLOCKED_REASON_APP_STANDBY;
            }
            if ((allowedReasons & ALLOWED_REASON_RESTRICTED_MODE_PERMISSIONS) != 0) {
                effectiveBlockedReasons &= ~BLOCKED_REASON_RESTRICTED_MODE;
            }
            if ((allowedReasons & ALLOWED_METERED_REASON_USER_EXEMPTED) != 0) {
                effectiveBlockedReasons &= ~BLOCKED_METERED_REASON_DATA_SAVER;
            }
        }
    }

    private class NotificationId {
        private final String mTag;
        private final int mId;

        NotificationId(NetworkPolicy policy, int type) {
            mTag = buildNotificationTag(policy, type);
            mId = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NotificationId)) return false;
            NotificationId that = (NotificationId) o;
            return Objects.equals(mTag, that.mTag);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mTag);
        }

        /**
         * Build unique tag that identifies an active {@link NetworkPolicy}
         * notification of a specific type, like {@link #TYPE_LIMIT}.
         */
        private String buildNotificationTag(NetworkPolicy policy, int type) {
            return TAG + ":" + policy.template.hashCode() + ":" + type;
        }

        public String getTag() {
            return mTag;
        }

        public int getId() {
            return mId;
        }
    }
}
