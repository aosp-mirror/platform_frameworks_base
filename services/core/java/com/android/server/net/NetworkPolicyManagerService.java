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
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
import static android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.ConnectivityManager.isNetworkTypeMobile;
import static android.net.NetworkPolicy.CYCLE_NONE;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.EXTRA_NETWORK_TEMPLATE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_ALLOW;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DENY;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_ALLOW_METERED;
import static android.net.NetworkPolicyManager.MASK_METERED_NETWORKS;
import static android.net.NetworkPolicyManager.MASK_ALL_NETWORKS;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.RULE_REJECT_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.NetworkPolicyManager.RULE_TEMPORARY_ALLOW_METERED;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkPolicyManager.uidRulesToString;
import static android.net.NetworkTemplate.MATCH_MOBILE_3G_LOWER;
import static android.net.NetworkTemplate.MATCH_MOBILE_4G;
import static android.net.NetworkTemplate.MATCH_MOBILE_ALL;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.net.wifi.WifiManager.CHANGE_REASON_ADDED;
import static android.net.wifi.WifiManager.CHANGE_REASON_REMOVED;
import static android.net.wifi.WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION;
import static android.net.wifi.WifiManager.EXTRA_CHANGE_REASON;
import static android.net.wifi.WifiManager.EXTRA_NETWORK_INFO;
import static android.net.wifi.WifiManager.EXTRA_WIFI_CONFIGURATION;
import static android.net.wifi.WifiManager.EXTRA_WIFI_INFO;
import static android.text.format.DateUtils.DAY_IN_MILLIS;

import static com.android.internal.util.ArrayUtils.appendInt;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.internal.util.XmlUtils.readBooleanAttribute;
import static com.android.internal.util.XmlUtils.readIntAttribute;
import static com.android.internal.util.XmlUtils.readLongAttribute;
import static com.android.internal.util.XmlUtils.writeBooleanAttribute;
import static com.android.internal.util.XmlUtils.writeIntAttribute;
import static com.android.internal.util.XmlUtils.writeLongAttribute;
import static com.android.server.NetworkManagementService.LIMIT_GLOBAL_ALERT;
import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_UPDATED;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.IUidObserver;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.NetworkIdentity;
import android.net.NetworkInfo;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.NetworkTemplate;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IDeviceIdleController;
import android.os.INetworkManagementService;
import android.os.IPowerManager;
import android.os.Message;
import android.os.MessageQueue.IdleHandler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.DebugUtils;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TrustedTime;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.DeviceIdleController;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;

import libcore.io.IoUtils;

import com.google.android.collect.Lists;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service that maintains low-level network policy rules, using
 * {@link NetworkStatsService} statistics to drive those rules.
 * <p>
 * Derives active rules by combining a given policy with other system status,
 * and delivers to listeners, such as {@link ConnectivityManager}, for
 * enforcement.
 */
public class NetworkPolicyManagerService extends INetworkPolicyManager.Stub {
    static final String TAG = "NetworkPolicy";
    private static final boolean LOGD = false;
    private static final boolean LOGV = false;

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
    private static final int VERSION_LATEST = VERSION_SWITCH_UID;

    @VisibleForTesting
    public static final int TYPE_WARNING = 0x1;
    @VisibleForTesting
    public static final int TYPE_LIMIT = 0x2;
    @VisibleForTesting
    public static final int TYPE_LIMIT_SNOOZED = 0x3;

    private static final String TAG_POLICY_LIST = "policy-list";
    private static final String TAG_NETWORK_POLICY = "network-policy";
    private static final String TAG_UID_POLICY = "uid-policy";
    private static final String TAG_APP_POLICY = "app-policy";
    private static final String TAG_WHITELIST = "whitelist";
    private static final String TAG_RESTRICT_BACKGROUND = "restrict-background";
    private static final String TAG_REVOKED_RESTRICT_BACKGROUND = "revoked-restrict-background";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_RESTRICT_BACKGROUND = "restrictBackground";
    private static final String ATTR_NETWORK_TEMPLATE = "networkTemplate";
    private static final String ATTR_SUBSCRIBER_ID = "subscriberId";
    private static final String ATTR_NETWORK_ID = "networkId";
    private static final String ATTR_CYCLE_DAY = "cycleDay";
    private static final String ATTR_CYCLE_TIMEZONE = "cycleTimezone";
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

    private static final String ACTION_ALLOW_BACKGROUND =
            "com.android.server.net.action.ALLOW_BACKGROUND";
    private static final String ACTION_SNOOZE_WARNING =
            "com.android.server.net.action.SNOOZE_WARNING";

    private static final long TIME_CACHE_MAX_AGE = DAY_IN_MILLIS;

    private static final int MSG_RULES_CHANGED = 1;
    private static final int MSG_METERED_IFACES_CHANGED = 2;
    private static final int MSG_LIMIT_REACHED = 5;
    private static final int MSG_RESTRICT_BACKGROUND_CHANGED = 6;
    private static final int MSG_ADVISE_PERSIST_THRESHOLD = 7;
    private static final int MSG_SCREEN_ON_CHANGED = 8;
    private static final int MSG_RESTRICT_BACKGROUND_WHITELIST_CHANGED = 9;
    private static final int MSG_UPDATE_INTERFACE_QUOTA = 10;
    private static final int MSG_REMOVE_INTERFACE_QUOTA = 11;
    private static final int MSG_RESTRICT_BACKGROUND_BLACKLIST_CHANGED = 12;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final IPowerManager mPowerManager;
    private final INetworkStatsService mNetworkStats;
    private final INetworkManagementService mNetworkManager;
    private UsageStatsManagerInternal mUsageStats;
    private final TrustedTime mTime;
    private final UserManager mUserManager;

    private IConnectivityManager mConnManager;
    private INotificationManager mNotifManager;
    private PowerManagerInternal mPowerManagerInternal;
    private IDeviceIdleController mDeviceIdleController;

    final Object mRulesLock = new Object();

    volatile boolean mSystemReady;
    volatile boolean mScreenOn;
    volatile boolean mRestrictBackground;
    volatile boolean mRestrictPower;
    volatile boolean mDeviceIdleMode;

    private final boolean mSuppressDefaultPolicy;

    /** Defined network policies. */
    final ArrayMap<NetworkTemplate, NetworkPolicy> mNetworkPolicy = new ArrayMap<>();
    /** Currently active network rules for ifaces. */
    final ArrayMap<NetworkPolicy, String[]> mNetworkRules = new ArrayMap<>();

    /** Defined UID policies. */
    final SparseIntArray mUidPolicy = new SparseIntArray();
    /** Currently derived rules for each UID. */
    final SparseIntArray mUidRules = new SparseIntArray();

    final SparseIntArray mUidFirewallStandbyRules = new SparseIntArray();
    final SparseIntArray mUidFirewallDozableRules = new SparseIntArray();
    final SparseIntArray mUidFirewallPowerSaveRules = new SparseIntArray();

    /** Set of states for the child firewall chains. True if the chain is active. */
    final SparseBooleanArray mFirewallChainStates = new SparseBooleanArray();

    /**
     * UIDs that have been white-listed to always be able to have network access
     * in power save mode, except device idle (doze) still applies.
     * TODO: An int array might be sufficient
     */
    private final SparseBooleanArray mPowerSaveWhitelistExceptIdleAppIds = new SparseBooleanArray();

    /**
     * UIDs that have been white-listed to always be able to have network access
     * in power save mode.
     * TODO: An int array might be sufficient
     */
    private final SparseBooleanArray mPowerSaveWhitelistAppIds = new SparseBooleanArray();

    private final SparseBooleanArray mPowerSaveTempWhitelistAppIds = new SparseBooleanArray();

    /**
     * UIDs that have been white-listed to avoid restricted background.
     */
    private final SparseBooleanArray mRestrictBackgroundWhitelistUids = new SparseBooleanArray();

    /**
     * UIDs that have been initially white-listed by system to avoid restricted background.
     */
    private final SparseBooleanArray mDefaultRestrictBackgroundWhitelistUids =
            new SparseBooleanArray();

    /**
     * UIDs that have been initially white-listed by system to avoid restricted background,
     * but later revoked by user.
     */
    private final SparseBooleanArray mRestrictBackgroundWhitelistRevokedUids =
            new SparseBooleanArray();

    /** Set of ifaces that are metered. */
    private ArraySet<String> mMeteredIfaces = new ArraySet<>();
    /** Set of over-limit templates that have been notified. */
    private final ArraySet<NetworkTemplate> mOverLimitNotified = new ArraySet<>();

    /** Set of currently active {@link Notification} tags. */
    private final ArraySet<String> mActiveNotifs = new ArraySet<String>();

    /** Foreground at UID granularity. */
    final SparseIntArray mUidState = new SparseIntArray();

    /** Higher priority listener before general event dispatch */
    private INetworkPolicyListener mConnectivityListener;

    private final RemoteCallbackList<INetworkPolicyListener>
            mListeners = new RemoteCallbackList<>();

    final Handler mHandler;

    private final AtomicFile mPolicyFile;

    private final AppOpsManager mAppOps;

    private final MyPackageMonitor mPackageMonitor;
    private final IPackageManager mIPm;


    // TODO: keep whitelist of system-critical services that should never have
    // rules enforced, such as system, phone, and radio UIDs.

    // TODO: migrate notifications to SystemUI

    public NetworkPolicyManagerService(Context context, IActivityManager activityManager,
            IPowerManager powerManager, INetworkStatsService networkStats,
            INetworkManagementService networkManagement) {
        this(context, activityManager, powerManager, networkStats, networkManagement,
                NtpTrustedTime.getInstance(context), getSystemDir(), false);
    }

    private static File getSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    public NetworkPolicyManagerService(Context context, IActivityManager activityManager,
            IPowerManager powerManager, INetworkStatsService networkStats,
            INetworkManagementService networkManagement, TrustedTime time, File systemDir,
            boolean suppressDefaultPolicy) {
        mContext = checkNotNull(context, "missing context");
        mActivityManager = checkNotNull(activityManager, "missing activityManager");
        mPowerManager = checkNotNull(powerManager, "missing powerManager");
        mNetworkStats = checkNotNull(networkStats, "missing networkStats");
        mNetworkManager = checkNotNull(networkManagement, "missing networkManagement");
        mDeviceIdleController = IDeviceIdleController.Stub.asInterface(ServiceManager.getService(
                Context.DEVICE_IDLE_CONTROLLER));
        mTime = checkNotNull(time, "missing TrustedTime");
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mIPm = AppGlobals.getPackageManager();

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper(), mHandlerCallback);

        mSuppressDefaultPolicy = suppressDefaultPolicy;

        mPolicyFile = new AtomicFile(new File(systemDir, "netpolicy.xml"));

        mAppOps = context.getSystemService(AppOpsManager.class);

        mPackageMonitor = new MyPackageMonitor();

        // Expose private service for system components to use.
        LocalServices.addService(NetworkPolicyManagerInternal.class,
                new NetworkPolicyManagerInternalImpl());
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void bindNotificationManager(INotificationManager notifManager) {
        mNotifManager = checkNotNull(notifManager, "missing INotificationManager");
    }

    void updatePowerSaveWhitelistLocked() {
        try {
            int[] whitelist = mDeviceIdleController.getAppIdWhitelistExceptIdle();
            mPowerSaveWhitelistExceptIdleAppIds.clear();
            if (whitelist != null) {
                for (int uid : whitelist) {
                    mPowerSaveWhitelistExceptIdleAppIds.put(uid, true);
                }
            }
            whitelist = mDeviceIdleController.getAppIdWhitelist();
            mPowerSaveWhitelistAppIds.clear();
            if (whitelist != null) {
                for (int uid : whitelist) {
                    mPowerSaveWhitelistAppIds.put(uid, true);
                }
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Whitelists pre-defined apps for restrict background, but only if the user didn't already
     * revoke the whitelist.
     *
     * @return whether any uid has been added to {@link #mRestrictBackgroundWhitelistUids}.
     */
    boolean addDefaultRestrictBackgroundWhitelistUidsLocked() {
        final List<UserInfo> users = mUserManager.getUsers();
        final int numberUsers = users.size();

        boolean changed = false;
        for (int i = 0; i < numberUsers; i++) {
            final UserInfo user = users.get(i);
            changed = addDefaultRestrictBackgroundWhitelistUidsLocked(user.id) || changed;
        }
        return changed;
    }

    private boolean addDefaultRestrictBackgroundWhitelistUidsLocked(int userId) {
        final SystemConfig sysConfig = SystemConfig.getInstance();
        final PackageManager pm = mContext.getPackageManager();
        final ArraySet<String> allowDataUsage = sysConfig.getAllowInDataUsageSave();
        boolean changed = false;
        for (int i = 0; i < allowDataUsage.size(); i++) {
            final String pkg = allowDataUsage.valueAt(i);
            if (LOGD)
                Slog.d(TAG, "checking restricted background whitelisting for package " + pkg
                        + " and user " + userId);
            final ApplicationInfo app;
            try {
                app = pm.getApplicationInfoAsUser(pkg, PackageManager.MATCH_SYSTEM_ONLY, userId);
            } catch (PackageManager.NameNotFoundException e) {
                // Should not happen
                Slog.wtf(TAG, "No ApplicationInfo for package " + pkg);
                continue;
            }
            if (!app.isPrivilegedApp()) {
                Slog.wtf(TAG, "pm.getApplicationInfoAsUser() returned non-privileged app: " + pkg);
                continue;
            }
            final int uid = UserHandle.getUid(userId, app.uid);
            mDefaultRestrictBackgroundWhitelistUids.append(uid, true);
            if (LOGD)
                Slog.d(TAG, "Adding uid " + uid + " (user " + userId + ") to default restricted "
                        + "background whitelist. Revoked status: "
                        + mRestrictBackgroundWhitelistRevokedUids.get(uid));
            if (!mRestrictBackgroundWhitelistRevokedUids.get(uid)) {
                Slog.i(TAG, "adding default package " + pkg + " (uid " + uid + " for user "
                        + userId + ") to restrict background whitelist");
                mRestrictBackgroundWhitelistUids.append(uid, true);
                changed = true;
            }
        }
        return changed;
    }

    void updatePowerSaveTempWhitelistLocked() {
        try {
            // Clear the states of the current whitelist
            final int N = mPowerSaveTempWhitelistAppIds.size();
            for (int i = 0; i < N; i++) {
                mPowerSaveTempWhitelistAppIds.setValueAt(i, false);
            }
            // Update the states with the new whitelist
            final int[] whitelist = mDeviceIdleController.getAppIdTempWhitelist();
            if (whitelist != null) {
                for (int uid : whitelist) {
                    mPowerSaveTempWhitelistAppIds.put(uid, true);
                }
            }
        } catch (RemoteException e) {
        }
    }

    /**
     * Remove unnecessary entries in the temp whitelist
     */
    void purgePowerSaveTempWhitelistLocked() {
        final int N = mPowerSaveTempWhitelistAppIds.size();
        for (int i = N - 1; i >= 0; i--) {
            if (mPowerSaveTempWhitelistAppIds.valueAt(i) == false) {
                mPowerSaveTempWhitelistAppIds.removeAt(i);
            }
        }
    }

    public void systemReady() {
        if (!isBandwidthControlEnabled()) {
            Slog.w(TAG, "bandwidth controls disabled, unable to enforce policy");
            return;
        }

        mUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);

        mPackageMonitor.register(mContext, mHandler.getLooper(), UserHandle.ALL, true);

        synchronized (mRulesLock) {
            updatePowerSaveWhitelistLocked();
            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            mPowerManagerInternal.registerLowPowerModeObserver(
                    new PowerManagerInternal.LowPowerModeListener() {
                @Override
                public void onLowPowerModeChanged(boolean enabled) {
                    if (LOGD) Slog.d(TAG, "onLowPowerModeChanged(" + enabled + ")");
                    synchronized (mRulesLock) {
                        if (mRestrictPower != enabled) {
                            mRestrictPower = enabled;
                            updateRulesForRestrictPowerLocked();
                            updateRulesForGlobalChangeLocked(true);
                        }
                    }
                }
            });
            mRestrictPower = mPowerManagerInternal.getLowPowerModeEnabled();

            mSystemReady = true;

            // read policy from disk
            readPolicyLocked();

            if (addDefaultRestrictBackgroundWhitelistUidsLocked()) {
                writePolicyLocked();
            }

            updateRulesForGlobalChangeLocked(false);
            updateNotificationsLocked();
        }

        updateScreenOn();

        try {
            mActivityManager.registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE|ActivityManager.UID_OBSERVER_GONE);
            mNetworkManager.registerObserver(mAlertObserver);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }

        // TODO: traverse existing processes to know foreground state, or have
        // activitymanager dispatch current state when new observer attached.

        final IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, screenFilter);

        // listen for changes to power save whitelist
        final IntentFilter whitelistFilter = new IntentFilter(
                PowerManager.ACTION_POWER_SAVE_WHITELIST_CHANGED);
        mContext.registerReceiver(mPowerSaveWhitelistReceiver, whitelistFilter, null, mHandler);

        DeviceIdleController.LocalService deviceIdleService
                = LocalServices.getService(DeviceIdleController.LocalService.class);
        deviceIdleService.setNetworkPolicyTempWhitelistCallback(mTempPowerSaveChangedCallback);

        // watch for network interfaces to be claimed
        final IntentFilter connFilter = new IntentFilter(CONNECTIVITY_ACTION);
        mContext.registerReceiver(mConnReceiver, connFilter, CONNECTIVITY_INTERNAL, mHandler);

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

        // listen for snooze warning from notifications
        final IntentFilter snoozeWarningFilter = new IntentFilter(ACTION_SNOOZE_WARNING);
        mContext.registerReceiver(mSnoozeWarningReceiver, snoozeWarningFilter,
                MANAGE_NETWORK_POLICY, mHandler);

        // listen for configured wifi networks to be removed
        final IntentFilter wifiConfigFilter = new IntentFilter(CONFIGURED_NETWORKS_CHANGED_ACTION);
        mContext.registerReceiver(mWifiConfigReceiver, wifiConfigFilter, null, mHandler);

        // listen for wifi state changes to catch metered hint
        final IntentFilter wifiStateFilter = new IntentFilter(
                WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mContext.registerReceiver(mWifiStateReceiver, wifiStateFilter, null, mHandler);

        mUsageStats.addAppIdleStateChangeListener(new AppIdleStateChangeListener());

    }

    final private IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override public void onUidStateChanged(int uid, int procState) throws RemoteException {
            synchronized (mRulesLock) {
                updateUidStateLocked(uid, procState);
            }
        }

        @Override public void onUidGone(int uid) throws RemoteException {
            synchronized (mRulesLock) {
                removeUidStateLocked(uid);
            }
        }

        @Override public void onUidActive(int uid) throws RemoteException {
        }

        @Override public void onUidIdle(int uid) throws RemoteException {
        }
    };

    final private BroadcastReceiver mPowerSaveWhitelistReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and POWER_SAVE_WHITELIST_CHANGED is protected
            synchronized (mRulesLock) {
                updatePowerSaveWhitelistLocked();
                updateRulesForGlobalChangeLocked(false);
            }
        }
    };

    final private Runnable mTempPowerSaveChangedCallback = new Runnable() {
        @Override
        public void run() {
            synchronized (mRulesLock) {
                updatePowerSaveTempWhitelistLocked();
                updateRulesForTempWhitelistChangeLocked();
                purgePowerSaveTempWhitelistLocked();
            }
        }
    };

    final private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // screen-related broadcasts are protected by system, no need
            // for permissions check.
            mHandler.obtainMessage(MSG_SCREEN_ON_CHANGED).sendToTarget();
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
                synchronized (mRulesLock) {
                    updateRestrictionRulesForUidLocked(uid);
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
            synchronized (mRulesLock) {
                mUidPolicy.delete(uid);
                updateRestrictionRulesForUidLocked(uid);
                writePolicyLocked();
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
                    synchronized (mRulesLock) {
                        // Remove any persistable state for the given user; both cleaning up after a
                        // USER_REMOVED, and one last sanity check during USER_ADDED
                        removeUserStateLocked(userId, true);
                        if (action == ACTION_USER_ADDED) {
                            // Add apps that are whitelisted by default.
                            addDefaultRestrictBackgroundWhitelistUidsLocked(userId);
                        }
                        // Update global restrict for that user
                        updateRulesForGlobalChangeLocked(true);
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

            maybeRefreshTrustedTime();
            synchronized (mRulesLock) {
                updateNetworkEnabledLocked();
                updateNotificationsLocked();
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
    final private BroadcastReceiver mSnoozeWarningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified MANAGE_NETWORK_POLICY
            // permission above.

            final NetworkTemplate template = intent.getParcelableExtra(EXTRA_NETWORK_TEMPLATE);
            performSnooze(template, TYPE_WARNING);
        }
    };

    /**
     * Receiver that watches for {@link WifiConfiguration} to be changed.
     */
    final private BroadcastReceiver mWifiConfigReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.

            final int reason = intent.getIntExtra(EXTRA_CHANGE_REASON, CHANGE_REASON_ADDED);
            if (reason == CHANGE_REASON_REMOVED) {
                final WifiConfiguration config = intent.getParcelableExtra(
                        EXTRA_WIFI_CONFIGURATION);
                if (config.SSID != null) {
                    final NetworkTemplate template = NetworkTemplate.buildTemplateWifi(config.SSID);
                    synchronized (mRulesLock) {
                        if (mNetworkPolicy.containsKey(template)) {
                            mNetworkPolicy.remove(template);
                            writePolicyLocked();
                        }
                    }
                }
            }
        }
    };

    /**
     * Receiver that watches {@link WifiInfo} state changes to infer metered
     * state. Ignores hints when policy is user-defined.
     */
    final private BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.

            // ignore when not connected
            final NetworkInfo netInfo = intent.getParcelableExtra(EXTRA_NETWORK_INFO);
            if (!netInfo.isConnected()) return;

            final WifiInfo info = intent.getParcelableExtra(EXTRA_WIFI_INFO);
            final boolean meteredHint = info.getMeteredHint();

            final NetworkTemplate template = NetworkTemplate.buildTemplateWifi(info.getSSID());
            synchronized (mRulesLock) {
                NetworkPolicy policy = mNetworkPolicy.get(template);
                if (policy == null && meteredHint) {
                    // policy doesn't exist, and AP is hinting that it's
                    // metered: create an inferred policy.
                    policy = newWifiPolicy(template, meteredHint);
                    addNetworkPolicyLocked(policy);

                } else if (policy != null && policy.inferred) {
                    // policy exists, and was inferred: update its current
                    // metered state.
                    policy.metered = meteredHint;

                    // since this is inferred for each wifi session, just update
                    // rules without persisting.
                    updateNetworkRulesLocked();
                }
            }
        }
    };

    static NetworkPolicy newWifiPolicy(NetworkTemplate template, boolean metered) {
        return new NetworkPolicy(template, CYCLE_NONE, Time.TIMEZONE_UTC,
                WARNING_DISABLED, LIMIT_DISABLED, SNOOZE_NEVER, SNOOZE_NEVER,
                metered, true);
    }

    /**
     * Observer that watches for {@link INetworkManagementService} alerts.
     */
    final private INetworkManagementEventObserver mAlertObserver
            = new BaseNetworkObserver() {
        @Override
        public void limitReached(String limitName, String iface) {
            // only someone like NMS should be calling us
            mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

            if (!LIMIT_GLOBAL_ALERT.equals(limitName)) {
                mHandler.obtainMessage(MSG_LIMIT_REACHED, iface).sendToTarget();
            }
        }
    };

    /**
     * Check {@link NetworkPolicy} against current {@link INetworkStatsService}
     * to show visible notifications as needed.
     */
    void updateNotificationsLocked() {
        if (LOGV) Slog.v(TAG, "updateNotificationsLocked()");

        // keep track of previously active notifications
        final ArraySet<String> beforeNotifs = new ArraySet<String>(mActiveNotifs);
        mActiveNotifs.clear();

        // TODO: when switching to kernel notifications, compute next future
        // cycle boundary to recompute notifications.

        // examine stats for each active policy
        final long currentTime = currentTimeMillis();
        for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
            final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
            // ignore policies that aren't relevant to user
            if (!isTemplateRelevant(policy.template)) continue;
            if (!policy.hasCycle()) continue;

            final long start = computeLastCycleBoundary(currentTime, policy);
            final long end = currentTime;
            final long totalBytes = getTotalBytes(policy.template, start, end);

            if (policy.isOverLimit(totalBytes)) {
                if (policy.lastLimitSnooze >= start) {
                    enqueueNotification(policy, TYPE_LIMIT_SNOOZED, totalBytes);
                } else {
                    enqueueNotification(policy, TYPE_LIMIT, totalBytes);
                    notifyOverLimitLocked(policy.template);
                }

            } else {
                notifyUnderLimitLocked(policy.template);

                if (policy.isOverWarning(totalBytes) && policy.lastWarningSnooze < start) {
                    enqueueNotification(policy, TYPE_WARNING, totalBytes);
                }
            }
        }

        // cancel stale notifications that we didn't renew above
        for (int i = beforeNotifs.size()-1; i >= 0; i--) {
            final String tag = beforeNotifs.valueAt(i);
            if (!mActiveNotifs.contains(tag)) {
                cancelNotification(tag);
            }
        }
    }

    /**
     * Test if given {@link NetworkTemplate} is relevant to user based on
     * current device state, such as when
     * {@link TelephonyManager#getSubscriberId()} matches. This is regardless of
     * data connection status.
     */
    private boolean isTemplateRelevant(NetworkTemplate template) {
        if (template.isMatchRuleMobile()) {
            final TelephonyManager tele = TelephonyManager.from(mContext);
            final SubscriptionManager sub = SubscriptionManager.from(mContext);

            // Mobile template is relevant when any active subscriber matches
            final int[] subIds = sub.getActiveSubscriptionIdList();
            for (int subId : subIds) {
                final String subscriberId = tele.getSubscriberId(subId);
                final NetworkIdentity probeIdent = new NetworkIdentity(TYPE_MOBILE,
                        TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false, true);
                if (template.matches(probeIdent)) {
                    return true;
                }
            }
            return false;
        } else {
            return true;
        }
    }

    /**
     * Notify that given {@link NetworkTemplate} is over
     * {@link NetworkPolicy#limitBytes}, potentially showing dialog to user.
     */
    private void notifyOverLimitLocked(NetworkTemplate template) {
        if (!mOverLimitNotified.contains(template)) {
            mContext.startActivity(buildNetworkOverLimitIntent(template));
            mOverLimitNotified.add(template);
        }
    }

    private void notifyUnderLimitLocked(NetworkTemplate template) {
        mOverLimitNotified.remove(template);
    }

    /**
     * Build unique tag that identifies an active {@link NetworkPolicy}
     * notification of a specific type, like {@link #TYPE_LIMIT}.
     */
    private String buildNotificationTag(NetworkPolicy policy, int type) {
        return TAG + ":" + policy.template.hashCode() + ":" + type;
    }

    /**
     * Show notification for combined {@link NetworkPolicy} and specific type,
     * like {@link #TYPE_LIMIT}. Okay to call multiple times.
     */
    private void enqueueNotification(NetworkPolicy policy, int type, long totalBytes) {
        final String tag = buildNotificationTag(policy, type);
        final Notification.Builder builder = new Notification.Builder(mContext);
        builder.setOnlyAlertOnce(true);
        builder.setWhen(0L);
        builder.setColor(mContext.getColor(
                com.android.internal.R.color.system_notification_accent_color));

        final Resources res = mContext.getResources();
        switch (type) {
            case TYPE_WARNING: {
                final CharSequence title = res.getText(R.string.data_usage_warning_title);
                final CharSequence body = res.getString(R.string.data_usage_warning_body);

                builder.setSmallIcon(R.drawable.stat_notify_error);
                builder.setTicker(title);
                builder.setContentTitle(title);
                builder.setContentText(body);

                final Intent snoozeIntent = buildSnoozeWarningIntent(policy.template);
                builder.setDeleteIntent(PendingIntent.getBroadcast(
                        mContext, 0, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                final Intent viewIntent = buildViewDataUsageIntent(policy.template);
                builder.setContentIntent(PendingIntent.getActivity(
                        mContext, 0, viewIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                break;
            }
            case TYPE_LIMIT: {
                final CharSequence body = res.getText(R.string.data_usage_limit_body);

                final CharSequence title;
                int icon = R.drawable.stat_notify_disabled_data;
                switch (policy.template.getMatchRule()) {
                    case MATCH_MOBILE_3G_LOWER:
                        title = res.getText(R.string.data_usage_3g_limit_title);
                        break;
                    case MATCH_MOBILE_4G:
                        title = res.getText(R.string.data_usage_4g_limit_title);
                        break;
                    case MATCH_MOBILE_ALL:
                        title = res.getText(R.string.data_usage_mobile_limit_title);
                        break;
                    case MATCH_WIFI:
                        title = res.getText(R.string.data_usage_wifi_limit_title);
                        icon = R.drawable.stat_notify_error;
                        break;
                    default:
                        title = null;
                        break;
                }

                builder.setOngoing(true);
                builder.setSmallIcon(icon);
                builder.setTicker(title);
                builder.setContentTitle(title);
                builder.setContentText(body);

                final Intent intent = buildNetworkOverLimitIntent(policy.template);
                builder.setContentIntent(PendingIntent.getActivity(
                        mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
                break;
            }
            case TYPE_LIMIT_SNOOZED: {
                final long overBytes = totalBytes - policy.limitBytes;
                final CharSequence body = res.getString(R.string.data_usage_limit_snoozed_body,
                        Formatter.formatFileSize(mContext, overBytes));

                final CharSequence title;
                switch (policy.template.getMatchRule()) {
                    case MATCH_MOBILE_3G_LOWER:
                        title = res.getText(R.string.data_usage_3g_limit_snoozed_title);
                        break;
                    case MATCH_MOBILE_4G:
                        title = res.getText(R.string.data_usage_4g_limit_snoozed_title);
                        break;
                    case MATCH_MOBILE_ALL:
                        title = res.getText(R.string.data_usage_mobile_limit_snoozed_title);
                        break;
                    case MATCH_WIFI:
                        title = res.getText(R.string.data_usage_wifi_limit_snoozed_title);
                        break;
                    default:
                        title = null;
                        break;
                }

                builder.setOngoing(true);
                builder.setSmallIcon(R.drawable.stat_notify_error);
                builder.setTicker(title);
                builder.setContentTitle(title);
                builder.setContentText(body);

                final Intent intent = buildViewDataUsageIntent(policy.template);
                builder.setContentIntent(PendingIntent.getActivity(
                        mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
                break;
            }
        }

        // TODO: move to NotificationManager once we can mock it
        try {
            final String packageName = mContext.getPackageName();
            final int[] idReceived = new int[1];
            mNotifManager.enqueueNotificationWithTag(
                    packageName, packageName, tag, 0x0, builder.getNotification(), idReceived,
                    UserHandle.USER_ALL);
            mActiveNotifs.add(tag);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void cancelNotification(String tag) {
        // TODO: move to NotificationManager once we can mock it
        try {
            final String packageName = mContext.getPackageName();
            mNotifManager.cancelNotificationWithTag(
                    packageName, tag, 0x0, UserHandle.USER_ALL);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Receiver that watches for {@link IConnectivityManager} to claim network
     * interfaces. Used to apply {@link NetworkPolicy} to matching networks.
     */
    private BroadcastReceiver mConnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.

            maybeRefreshTrustedTime();
            synchronized (mRulesLock) {
                ensureActiveMobilePolicyLocked();
                normalizePoliciesLocked();
                updateNetworkEnabledLocked();
                updateNetworkRulesLocked();
                updateNotificationsLocked();
            }
        }
    };

    /**
     * Proactively control network data connections when they exceed
     * {@link NetworkPolicy#limitBytes}.
     */
    void updateNetworkEnabledLocked() {
        if (LOGV) Slog.v(TAG, "updateNetworkEnabledLocked()");

        // TODO: reset any policy-disabled networks when any policy is removed
        // completely, which is currently rare case.

        final long currentTime = currentTimeMillis();
        for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
            final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
            // shortcut when policy has no limit
            if (policy.limitBytes == LIMIT_DISABLED || !policy.hasCycle()) {
                setNetworkTemplateEnabled(policy.template, true);
                continue;
            }

            final long start = computeLastCycleBoundary(currentTime, policy);
            final long end = currentTime;
            final long totalBytes = getTotalBytes(policy.template, start, end);

            // disable data connection when over limit and not snoozed
            final boolean overLimitWithoutSnooze = policy.isOverLimit(totalBytes)
                    && policy.lastLimitSnooze < start;
            final boolean networkEnabled = !overLimitWithoutSnooze;

            setNetworkTemplateEnabled(policy.template, networkEnabled);
        }
    }

    /**
     * Proactively disable networks that match the given
     * {@link NetworkTemplate}.
     */
    private void setNetworkTemplateEnabled(NetworkTemplate template, boolean enabled) {
        // TODO: reach into ConnectivityManager to proactively disable bringing
        // up this network, since we know that traffic will be blocked.
    }

    /**
     * Examine all connected {@link NetworkState}, looking for
     * {@link NetworkPolicy} that need to be enforced. When matches found, set
     * remaining quota based on usage cycle and historical stats.
     */
    void updateNetworkRulesLocked() {
        if (LOGV) Slog.v(TAG, "updateNetworkRulesLocked()");

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return;
        }

        // First, generate identities of all connected networks so we can
        // quickly compare them against all defined policies below.
        final ArrayList<Pair<String, NetworkIdentity>> connIdents = new ArrayList<>(states.length);
        final ArraySet<String> connIfaces = new ArraySet<String>(states.length);
        for (NetworkState state : states) {
            if (state.networkInfo != null && state.networkInfo.isConnected()) {
                final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);

                final String baseIface = state.linkProperties.getInterfaceName();
                if (baseIface != null) {
                    connIdents.add(Pair.create(baseIface, ident));
                }

                // Stacked interfaces are considered to have same identity as
                // their parent network.
                final List<LinkProperties> stackedLinks = state.linkProperties.getStackedLinks();
                for (LinkProperties stackedLink : stackedLinks) {
                    final String stackedIface = stackedLink.getInterfaceName();
                    if (stackedIface != null) {
                        connIdents.add(Pair.create(stackedIface, ident));
                    }
                }
            }
        }

        // Apply policies against all connected interfaces found above
        mNetworkRules.clear();
        final ArrayList<String> ifaceList = Lists.newArrayList();
        for (int i = mNetworkPolicy.size() - 1; i >= 0; i--) {
            final NetworkPolicy policy = mNetworkPolicy.valueAt(i);

            ifaceList.clear();
            for (int j = connIdents.size() - 1; j >= 0; j--) {
                final Pair<String, NetworkIdentity> ident = connIdents.get(j);
                if (policy.template.matches(ident.second)) {
                    ifaceList.add(ident.first);
                }
            }

            if (ifaceList.size() > 0) {
                final String[] ifaces = ifaceList.toArray(new String[ifaceList.size()]);
                mNetworkRules.put(policy, ifaces);
            }
        }

        long lowestRule = Long.MAX_VALUE;
        final ArraySet<String> newMeteredIfaces = new ArraySet<String>(states.length);

        // apply each policy that we found ifaces for; compute remaining data
        // based on current cycle and historical stats, and push to kernel.
        final long currentTime = currentTimeMillis();
        for (int i = mNetworkRules.size()-1; i >= 0; i--) {
            final NetworkPolicy policy = mNetworkRules.keyAt(i);
            final String[] ifaces = mNetworkRules.valueAt(i);

            final long start;
            final long totalBytes;
            if (policy.hasCycle()) {
                start = computeLastCycleBoundary(currentTime, policy);
                totalBytes = getTotalBytes(policy.template, start, currentTime);
            } else {
                start = Long.MAX_VALUE;
                totalBytes = 0;
            }

            if (LOGD) {
                Slog.d(TAG, "applying policy " + policy + " to ifaces " + Arrays.toString(ifaces));
            }

            final boolean hasWarning = policy.warningBytes != LIMIT_DISABLED;
            final boolean hasLimit = policy.limitBytes != LIMIT_DISABLED;
            if (hasLimit || policy.metered) {
                final long quotaBytes;
                if (!hasLimit) {
                    // metered network, but no policy limit; we still need to
                    // restrict apps, so push really high quota.
                    quotaBytes = Long.MAX_VALUE;
                } else if (policy.lastLimitSnooze >= start) {
                    // snoozing past quota, but we still need to restrict apps,
                    // so push really high quota.
                    quotaBytes = Long.MAX_VALUE;
                } else {
                    // remaining "quota" bytes are based on total usage in
                    // current cycle. kernel doesn't like 0-byte rules, so we
                    // set 1-byte quota and disable the radio later.
                    quotaBytes = Math.max(1, policy.limitBytes - totalBytes);
                }

                if (ifaces.length > 1) {
                    // TODO: switch to shared quota once NMS supports
                    Slog.w(TAG, "shared quota unsupported; generating rule for each iface");
                }

                for (String iface : ifaces) {
                    // long quotaBytes split up into two ints to fit in message
                    mHandler.obtainMessage(MSG_UPDATE_INTERFACE_QUOTA,
                            (int) (quotaBytes >> 32), (int) (quotaBytes & 0xFFFFFFFF), iface)
                            .sendToTarget();
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

        for (int i = connIfaces.size()-1; i >= 0; i--) {
            String iface = connIfaces.valueAt(i);
            // long quotaBytes split up into two ints to fit in message
            mHandler.obtainMessage(MSG_UPDATE_INTERFACE_QUOTA,
                    (int) (Long.MAX_VALUE >> 32), (int) (Long.MAX_VALUE & 0xFFFFFFFF), iface)
                    .sendToTarget();
            newMeteredIfaces.add(iface);
        }

        mHandler.obtainMessage(MSG_ADVISE_PERSIST_THRESHOLD, lowestRule).sendToTarget();

        // remove quota on any trailing interfaces
        for (int i = mMeteredIfaces.size() - 1; i >= 0; i--) {
            final String iface = mMeteredIfaces.valueAt(i);
            if (!newMeteredIfaces.contains(iface)) {
                mHandler.obtainMessage(MSG_REMOVE_INTERFACE_QUOTA, iface)
                        .sendToTarget();
            }
        }
        mMeteredIfaces = newMeteredIfaces;

        final String[] meteredIfaces = mMeteredIfaces.toArray(new String[mMeteredIfaces.size()]);
        mHandler.obtainMessage(MSG_METERED_IFACES_CHANGED, meteredIfaces).sendToTarget();
    }

    /**
     * Once any {@link #mNetworkPolicy} are loaded from disk, ensure that we
     * have at least a default mobile policy defined.
     */
    private void ensureActiveMobilePolicyLocked() {
        if (LOGV) Slog.v(TAG, "ensureActiveMobilePolicyLocked()");
        if (mSuppressDefaultPolicy) return;

        final TelephonyManager tele = TelephonyManager.from(mContext);
        final SubscriptionManager sub = SubscriptionManager.from(mContext);

        final int[] subIds = sub.getActiveSubscriptionIdList();
        for (int subId : subIds) {
            final String subscriberId = tele.getSubscriberId(subId);
            ensureActiveMobilePolicyLocked(subscriberId);
        }
    }

    private void ensureActiveMobilePolicyLocked(String subscriberId) {
        // Poke around to see if we already have a policy
        final NetworkIdentity probeIdent = new NetworkIdentity(TYPE_MOBILE,
                TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false, true);
        for (int i = mNetworkPolicy.size() - 1; i >= 0; i--) {
            final NetworkTemplate template = mNetworkPolicy.keyAt(i);
            if (template.matches(probeIdent)) {
                if (LOGD) {
                    Slog.d(TAG, "Found template " + template + " which matches subscriber "
                            + NetworkIdentity.scrubSubscriberId(subscriberId));
                }
                return;
            }
        }

        Slog.i(TAG, "No policy for subscriber " + NetworkIdentity.scrubSubscriberId(subscriberId)
                + "; generating default policy");

        // Build default mobile policy, and assume usage cycle starts today
        final long warningBytes = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkPolicyDefaultWarning) * MB_IN_BYTES;

        final Time time = new Time();
        time.setToNow();

        final int cycleDay = time.monthDay;
        final String cycleTimezone = time.timezone;

        final NetworkTemplate template = buildTemplateMobileAll(subscriberId);
        final NetworkPolicy policy = new NetworkPolicy(template, cycleDay, cycleTimezone,
                warningBytes, LIMIT_DISABLED, SNOOZE_NEVER, SNOOZE_NEVER, true, true);
        addNetworkPolicyLocked(policy);
    }

    private void readPolicyLocked() {
        if (LOGV) Slog.v(TAG, "readPolicyLocked()");

        // clear any existing policy and read from disk
        mNetworkPolicy.clear();
        mUidPolicy.clear();

        FileInputStream fis = null;
        try {
            fis = mPolicyFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, StandardCharsets.UTF_8.name());

            int type;
            int version = VERSION_INIT;
            boolean insideWhitelist = false;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_POLICY_LIST.equals(tag)) {
                        final boolean oldValue = mRestrictBackground;
                        version = readIntAttribute(in, ATTR_VERSION);
                        if (version >= VERSION_ADDED_RESTRICT_BACKGROUND) {
                            mRestrictBackground = readBooleanAttribute(
                                    in, ATTR_RESTRICT_BACKGROUND);
                        } else {
                            mRestrictBackground = false;
                        }
                        if (mRestrictBackground != oldValue) {
                            // Some early services may have read the default value,
                            // so notify them that it's changed
                            mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_CHANGED,
                                    mRestrictBackground ? 1 : 0, 0).sendToTarget();
                        }

                    } else if (TAG_NETWORK_POLICY.equals(tag)) {
                        final int networkTemplate = readIntAttribute(in, ATTR_NETWORK_TEMPLATE);
                        final String subscriberId = in.getAttributeValue(null, ATTR_SUBSCRIBER_ID);
                        final String networkId;
                        if (version >= VERSION_ADDED_NETWORK_ID) {
                            networkId = in.getAttributeValue(null, ATTR_NETWORK_ID);
                        } else {
                            networkId = null;
                        }
                        final int cycleDay = readIntAttribute(in, ATTR_CYCLE_DAY);
                        final String cycleTimezone;
                        if (version >= VERSION_ADDED_TIMEZONE) {
                            cycleTimezone = in.getAttributeValue(null, ATTR_CYCLE_TIMEZONE);
                        } else {
                            cycleTimezone = Time.TIMEZONE_UTC;
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
                                case MATCH_MOBILE_3G_LOWER:
                                case MATCH_MOBILE_4G:
                                case MATCH_MOBILE_ALL:
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
                            mNetworkPolicy.put(template, new NetworkPolicy(template, cycleDay,
                                    cycleTimezone, warningBytes, limitBytes, lastWarningSnooze,
                                    lastLimitSnooze, metered, inferred));
                        }

                    } else if (TAG_UID_POLICY.equals(tag)) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        final int policy = readIntAttribute(in, ATTR_POLICY);

                        if (UserHandle.isApp(uid)) {
                            setUidPolicyUncheckedLocked(uid, policy, false);
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
                            setUidPolicyUncheckedLocked(uid, policy, false);
                        } else {
                            Slog.w(TAG, "unable to apply policy to UID " + uid + "; ignoring");
                        }
                    } else if (TAG_WHITELIST.equals(tag)) {
                        insideWhitelist = true;
                    } else if (TAG_RESTRICT_BACKGROUND.equals(tag) && insideWhitelist) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        mRestrictBackgroundWhitelistUids.put(uid, true);
                    } else if (TAG_REVOKED_RESTRICT_BACKGROUND.equals(tag) && insideWhitelist) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        mRestrictBackgroundWhitelistRevokedUids.put(uid, true);
                    }
                } else if (type == END_TAG) {
                    if (TAG_WHITELIST.equals(tag)) {
                        insideWhitelist = false;
                    }

                }
            }

        } catch (FileNotFoundException e) {
            // missing policy is okay, probably first boot
            upgradeLegacyBackgroundData();
        } catch (IOException e) {
            Log.wtf(TAG, "problem reading network policy", e);
        } catch (XmlPullParserException e) {
            Log.wtf(TAG, "problem reading network policy", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    /**
     * Upgrade legacy background data flags, notifying listeners of one last
     * change to always-true.
     */
    private void upgradeLegacyBackgroundData() {
        mRestrictBackground = Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.BACKGROUND_DATA, 1) != 1;

        // kick off one last broadcast if restricted
        if (mRestrictBackground) {
            final Intent broadcast = new Intent(
                    ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED);
            mContext.sendBroadcastAsUser(broadcast, UserHandle.ALL);
        }
    }

    void writePolicyLocked() {
        if (LOGV) Slog.v(TAG, "writePolicyLocked()");

        FileOutputStream fos = null;
        try {
            fos = mPolicyFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
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
                writeIntAttribute(out, ATTR_CYCLE_DAY, policy.cycleDay);
                out.attribute(null, ATTR_CYCLE_TIMEZONE, policy.cycleTimezone);
                writeLongAttribute(out, ATTR_WARNING_BYTES, policy.warningBytes);
                writeLongAttribute(out, ATTR_LIMIT_BYTES, policy.limitBytes);
                writeLongAttribute(out, ATTR_LAST_WARNING_SNOOZE, policy.lastWarningSnooze);
                writeLongAttribute(out, ATTR_LAST_LIMIT_SNOOZE, policy.lastLimitSnooze);
                writeBooleanAttribute(out, ATTR_METERED, policy.metered);
                writeBooleanAttribute(out, ATTR_INFERRED, policy.inferred);
                out.endTag(null, TAG_NETWORK_POLICY);
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

            // write all whitelists
            out.startTag(null, TAG_WHITELIST);

            // restrict background whitelist
            int size = mRestrictBackgroundWhitelistUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = mRestrictBackgroundWhitelistUids.keyAt(i);
                out.startTag(null, TAG_RESTRICT_BACKGROUND);
                writeIntAttribute(out, ATTR_UID, uid);
                out.endTag(null, TAG_RESTRICT_BACKGROUND);
            }

            // revoked restrict background whitelist
            size = mRestrictBackgroundWhitelistRevokedUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = mRestrictBackgroundWhitelistRevokedUids.keyAt(i);
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

        synchronized (mRulesLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
                if (oldPolicy != policy) {
                    setUidPolicyUncheckedLocked(uid, oldPolicy, policy, true);
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

        synchronized (mRulesLock) {
            final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
            policy |= oldPolicy;
            if (oldPolicy != policy) {
                setUidPolicyUncheckedLocked(uid, oldPolicy, policy, true);
            }
        }
    }

    @Override
    public void removeUidPolicy(int uid, int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        if (!UserHandle.isApp(uid)) {
            throw new IllegalArgumentException("cannot apply policy to UID " + uid);
        }

        synchronized (mRulesLock) {
            final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
            policy = oldPolicy & ~policy;
            if (oldPolicy != policy) {
                setUidPolicyUncheckedLocked(uid, oldPolicy, policy, true);
            }
        }
    }

    private void setUidPolicyUncheckedLocked(int uid, int oldPolicy, int policy, boolean persist) {
        setUidPolicyUncheckedLocked(uid, policy, persist);

        final boolean isBlacklisted = policy == POLICY_REJECT_METERED_BACKGROUND;
        mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_BLACKLIST_CHANGED, uid,
                isBlacklisted ? 1 : 0).sendToTarget();

        final boolean wasBlacklisted = oldPolicy == POLICY_REJECT_METERED_BACKGROUND;
        // Checks if app was added or removed to the blacklist.
        if ((oldPolicy == POLICY_NONE && isBlacklisted)
                || (wasBlacklisted && policy == POLICY_NONE)) {
            mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_WHITELIST_CHANGED, uid, 1, null)
                    .sendToTarget();
        }
    }

    private void setUidPolicyUncheckedLocked(int uid, int policy, boolean persist) {
        mUidPolicy.put(uid, policy);

        // uid policy changed, recompute rules and persist policy.
        updateRulesForDataUsageRestrictionsLocked(uid);
        if (persist) {
            writePolicyLocked();
        }
    }

    @Override
    public int getUidPolicy(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return mUidPolicy.get(uid, POLICY_NONE);
        }
    }

    @Override
    public int[] getUidsWithPolicy(int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        int[] uids = new int[0];
        synchronized (mRulesLock) {
            for (int i = 0; i < mUidPolicy.size(); i++) {
                final int uid = mUidPolicy.keyAt(i);
                final int uidPolicy = mUidPolicy.valueAt(i);
                if (uidPolicy == policy) {
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
    boolean removeUserStateLocked(int userId, boolean writePolicy) {

        if (LOGV) Slog.v(TAG, "removeUserStateLocked()");
        boolean changed = false;

        // Remove entries from restricted background UID whitelist
        int[] wlUids = new int[0];
        for (int i = 0; i < mRestrictBackgroundWhitelistUids.size(); i++) {
            final int uid = mRestrictBackgroundWhitelistUids.keyAt(i);
            if (UserHandle.getUserId(uid) == userId) {
                wlUids = appendInt(wlUids, uid);
            }
        }

        if (wlUids.length > 0) {
            for (int uid : wlUids) {
                removeRestrictBackgroundWhitelistedUidLocked(uid, false, false);
            }
            changed = true;
        }

        // Remove entries from revoked default restricted background UID whitelist
        for (int i = mRestrictBackgroundWhitelistRevokedUids.size() - 1; i >= 0; i--) {
            final int uid = mRestrictBackgroundWhitelistRevokedUids.keyAt(i);
            if (UserHandle.getUserId(uid) == userId) {
                mRestrictBackgroundWhitelistRevokedUids.removeAt(i);
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

        updateRulesForGlobalChangeLocked(true);

        if (writePolicy && changed) {
            writePolicyLocked();
        }
        return changed;
    }

    @Override
    public void setConnectivityListener(INetworkPolicyListener listener) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        if (mConnectivityListener != null) {
            throw new IllegalStateException("Connectivity listener already registered");
        }
        mConnectivityListener = listener;
    }

    @Override
    public void registerListener(INetworkPolicyListener listener) {
        // TODO: create permission for observing network policy
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        mListeners.register(listener);
    }

    @Override
    public void unregisterListener(INetworkPolicyListener listener) {
        // TODO: create permission for observing network policy
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);
        mListeners.unregister(listener);
    }

    @Override
    public void setNetworkPolicies(NetworkPolicy[] policies) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        final long token = Binder.clearCallingIdentity();
        try {
            maybeRefreshTrustedTime();
            synchronized (mRulesLock) {
                normalizePoliciesLocked(policies);
                updateNetworkEnabledLocked();
                updateNetworkRulesLocked();
                updateNotificationsLocked();
                writePolicyLocked();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void addNetworkPolicyLocked(NetworkPolicy policy) {
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

        synchronized (mRulesLock) {
            final int size = mNetworkPolicy.size();
            final NetworkPolicy[] policies = new NetworkPolicy[size];
            for (int i = 0; i < size; i++) {
                policies[i] = mNetworkPolicy.valueAt(i);
            }
            return policies;
        }
    }

    private void normalizePoliciesLocked() {
        normalizePoliciesLocked(getNetworkPolicies(mContext.getOpPackageName()));
    }

    private void normalizePoliciesLocked(NetworkPolicy[] policies) {
        final TelephonyManager tele = TelephonyManager.from(mContext);
        final String[] merged = tele.getMergedSubscriberIds();

        mNetworkPolicy.clear();
        for (NetworkPolicy policy : policies) {
            // When two normalized templates conflict, prefer the most
            // restrictive policy
            policy.template = NetworkTemplate.normalize(policy.template, merged);
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
        maybeRefreshTrustedTime();
        final long currentTime = currentTimeMillis();
        synchronized (mRulesLock) {
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
                default:
                    throw new IllegalArgumentException("unexpected type");
            }

            normalizePoliciesLocked();
            updateNetworkEnabledLocked();
            updateNetworkRulesLocked();
            updateNotificationsLocked();
            writePolicyLocked();
        }
    }

    @Override
    public void onTetheringChanged(String iface, boolean tethering) {
        // No need to enforce permission because setRestrictBackground() will do it.
        if (LOGD) Log.d(TAG, "onTetherStateChanged(" + iface + ", " + tethering + ")");
        synchronized (mRulesLock) {
            if (mRestrictBackground && tethering) {
                Log.d(TAG, "Tethering on (" + iface +"); disable Data Saver");
                setRestrictBackground(false);
            }
        }
    }

    @Override
    public void setRestrictBackground(boolean restrictBackground) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        final long token = Binder.clearCallingIdentity();
        try {
            maybeRefreshTrustedTime();
            synchronized (mRulesLock) {
                if (restrictBackground == mRestrictBackground) {
                    // Ideally, UI should never allow this scenario...
                    Slog.w(TAG, "setRestrictBackground: already " + restrictBackground);
                    return;
                }
                setRestrictBackgroundLocked(restrictBackground);
            }

        } finally {
            Binder.restoreCallingIdentity(token);
        }

        mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_CHANGED, restrictBackground ? 1 : 0, 0)
                .sendToTarget();
    }

    private void setRestrictBackgroundLocked(boolean restrictBackground) {
        Slog.d(TAG, "setRestrictBackgroundLocked(): " + restrictBackground);
        final boolean oldRestrictBackground = mRestrictBackground;
        mRestrictBackground = restrictBackground;
        // Must whitelist foreground apps before turning data saver mode on.
        // TODO: there is no need to iterate through all apps here, just those in the foreground,
        // so it could call AM to get the UIDs of such apps, and iterate through them instead.
        updateRulesForRestrictBackgroundLocked();
        try {
            if (!mNetworkManager.setDataSaverModeEnabled(mRestrictBackground)) {
                Slog.e(TAG, "Could not change Data Saver Mode on NMS to " + mRestrictBackground);
                mRestrictBackground = oldRestrictBackground;
                // TODO: if it knew the foreground apps (see TODO above), it could call
                // updateRulesForRestrictBackgroundLocked() again to restore state.
                return;
            }
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
        updateNotificationsLocked();
        writePolicyLocked();
    }

    @Override
    public void addRestrictBackgroundWhitelistedUid(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        final boolean oldStatus;
        final boolean needFirewallRules;
        synchronized (mRulesLock) {
            oldStatus = mRestrictBackgroundWhitelistUids.get(uid);
            if (oldStatus) {
                if (LOGD) Slog.d(TAG, "uid " + uid + " is already whitelisted");
                return;
            }
            needFirewallRules = isUidValidForWhitelistRules(uid);
            Slog.i(TAG, "adding uid " + uid + " to restrict background whitelist");
            mRestrictBackgroundWhitelistUids.append(uid, true);
            if (mDefaultRestrictBackgroundWhitelistUids.get(uid)
                    && mRestrictBackgroundWhitelistRevokedUids.get(uid)) {
                if (LOGD) Slog.d(TAG, "Removing uid " + uid
                        + " from revoked restrict background whitelist");
                mRestrictBackgroundWhitelistRevokedUids.delete(uid);
            }
            if (needFirewallRules) {
                // Only update firewall rules if necessary...
                updateRulesForDataUsageRestrictionsLocked(uid);
            }
            // ...but always persists the whitelist request.
            writePolicyLocked();
        }
        int changed = (mRestrictBackground && !oldStatus && needFirewallRules) ? 1 : 0;
        mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_WHITELIST_CHANGED, uid, changed,
                Boolean.TRUE).sendToTarget();
    }

    @Override
    public void removeRestrictBackgroundWhitelistedUid(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        final boolean changed;
        synchronized (mRulesLock) {
            changed = removeRestrictBackgroundWhitelistedUidLocked(uid, false, true);
        }
        mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_WHITELIST_CHANGED, uid, changed ? 1 : 0,
                Boolean.FALSE).sendToTarget();
    }

    /**
     * Removes a uid from the restricted background whitelist, returning whether its current
     * {@link ConnectivityManager.RestrictBackgroundStatus} changed.
     */
    private boolean removeRestrictBackgroundWhitelistedUidLocked(int uid, boolean uidDeleted,
            boolean updateNow) {
        final boolean oldStatus = mRestrictBackgroundWhitelistUids.get(uid);
        if (!oldStatus && !uidDeleted) {
            if (LOGD) Slog.d(TAG, "uid " + uid + " was not whitelisted before");
            return false;
        }
        final boolean needFirewallRules = uidDeleted || isUidValidForWhitelistRules(uid);
        if (oldStatus) {
            Slog.i(TAG, "removing uid " + uid + " from restrict background whitelist");
            mRestrictBackgroundWhitelistUids.delete(uid);
        }
        if (mDefaultRestrictBackgroundWhitelistUids.get(uid)
                && !mRestrictBackgroundWhitelistRevokedUids.get(uid)) {
            if (LOGD) Slog.d(TAG, "Adding uid " + uid
                    + " to revoked restrict background whitelist");
            mRestrictBackgroundWhitelistRevokedUids.append(uid, true);
        }
        if (needFirewallRules) {
            // Only update firewall rules if necessary...
            updateRulesForDataUsageRestrictionsLocked(uid, uidDeleted);
        }
        if (updateNow) {
            // ...but always persists the whitelist request.
            writePolicyLocked();
        }
        // Status only changes if Data Saver is turned on (otherwise it is DISABLED, even if the
        // app was whitelisted before).
        return mRestrictBackground && needFirewallRules;
    }

    @Override
    public int[] getRestrictBackgroundWhitelistedUids() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        synchronized (mRulesLock) {
            final int size = mRestrictBackgroundWhitelistUids.size();
            final int[] whitelist = new int[size];
            for (int i = 0; i < size; i++) {
                whitelist[i] = mRestrictBackgroundWhitelistUids.keyAt(i);
            }
            if (LOGV) {
                Slog.v(TAG, "getRestrictBackgroundWhitelistedUids(): "
                        + mRestrictBackgroundWhitelistUids);
            }
            return whitelist;
        }
    }

    @Override
    public int getRestrictBackgroundByCaller() {
        mContext.enforceCallingOrSelfPermission(ACCESS_NETWORK_STATE, TAG);
        final int uid = Binder.getCallingUid();

        synchronized (mRulesLock) {
            // Must clear identity because getUidPolicy() is restricted to system.
            final long token = Binder.clearCallingIdentity();
            final int policy;
            try {
                policy = getUidPolicy(uid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (policy == POLICY_REJECT_METERED_BACKGROUND) {
                // App is blacklisted.
                return RESTRICT_BACKGROUND_STATUS_ENABLED;
            }
            if (!mRestrictBackground) {
                return RESTRICT_BACKGROUND_STATUS_DISABLED;
            }
            return mRestrictBackgroundWhitelistUids.get(uid)
                    ? RESTRICT_BACKGROUND_STATUS_WHITELISTED
                    : RESTRICT_BACKGROUND_STATUS_ENABLED;
        }
    }

    @Override
    public boolean getRestrictBackground() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return mRestrictBackground;
        }
    }

    @Override
    public void setDeviceIdleMode(boolean enabled) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            if (mDeviceIdleMode != enabled) {
                mDeviceIdleMode = enabled;
                if (mSystemReady) {
                    // Device idle change means we need to rebuild rules for all
                    // known apps, so do a global refresh.
                    updateRulesForGlobalChangeLocked(false);
                }
                if (enabled) {
                    EventLogTags.writeDeviceIdleOnPhase("net");
                } else {
                    EventLogTags.writeDeviceIdleOffPhase("net");
                }
            }
        }
    }

    private NetworkPolicy findPolicyForNetworkLocked(NetworkIdentity ident) {
        for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
            NetworkPolicy policy = mNetworkPolicy.valueAt(i);
            if (policy.template.matches(ident)) {
                return policy;
            }
        }
        return null;
    }

    @Override
    public NetworkQuotaInfo getNetworkQuotaInfo(NetworkState state) {
        mContext.enforceCallingOrSelfPermission(ACCESS_NETWORK_STATE, TAG);

        // only returns usage summary, so we don't require caller to have
        // READ_NETWORK_USAGE_HISTORY.
        final long token = Binder.clearCallingIdentity();
        try {
            return getNetworkQuotaInfoUnchecked(state);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private NetworkQuotaInfo getNetworkQuotaInfoUnchecked(NetworkState state) {
        final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);

        final NetworkPolicy policy;
        synchronized (mRulesLock) {
            policy = findPolicyForNetworkLocked(ident);
        }

        if (policy == null || !policy.hasCycle()) {
            // missing policy means we can't derive useful quota info
            return null;
        }

        final long currentTime = currentTimeMillis();

        // find total bytes used under policy
        final long start = computeLastCycleBoundary(currentTime, policy);
        final long end = currentTime;
        final long totalBytes = getTotalBytes(policy.template, start, end);

        // report soft and hard limits under policy
        final long softLimitBytes = policy.warningBytes != WARNING_DISABLED ? policy.warningBytes
                : NetworkQuotaInfo.NO_LIMIT;
        final long hardLimitBytes = policy.limitBytes != LIMIT_DISABLED ? policy.limitBytes
                : NetworkQuotaInfo.NO_LIMIT;

        return new NetworkQuotaInfo(totalBytes, softLimitBytes, hardLimitBytes);
    }

    @Override
    public boolean isNetworkMetered(NetworkState state) {
        if (state.networkInfo == null) {
            return false;
        }

        final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);

        // roaming networks are always considered metered
        if (ident.getRoaming()) {
            return true;
        }

        final NetworkPolicy policy;
        synchronized (mRulesLock) {
            policy = findPolicyForNetworkLocked(ident);
        }

        if (policy != null) {
            return policy.metered;
        } else {
            final int type = state.networkInfo.getType();
            if (isNetworkTypeMobile(type) || type == TYPE_WIMAX) {
                return true;
            }
            return false;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        final IndentingPrintWriter fout = new IndentingPrintWriter(writer, "  ");

        final ArraySet<String> argSet = new ArraySet<String>(args.length);
        for (String arg : args) {
            argSet.add(arg);
        }

        synchronized (mRulesLock) {
            if (argSet.contains("--unsnooze")) {
                for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
                    mNetworkPolicy.valueAt(i).clearSnooze();
                }

                normalizePoliciesLocked();
                updateNetworkEnabledLocked();
                updateNetworkRulesLocked();
                updateNotificationsLocked();
                writePolicyLocked();

                fout.println("Cleared snooze timestamps");
                return;
            }

            fout.print("System ready: "); fout.println(mSystemReady);
            fout.print("Restrict background: "); fout.println(mRestrictBackground);
            fout.print("Restrict power: "); fout.println(mRestrictPower);
            fout.print("Device idle: "); fout.println(mDeviceIdleMode);
            fout.println("Network policies:");
            fout.increaseIndent();
            for (int i = 0; i < mNetworkPolicy.size(); i++) {
                fout.println(mNetworkPolicy.valueAt(i).toString());
            }
            fout.decreaseIndent();

            fout.print("Metered ifaces: "); fout.println(String.valueOf(mMeteredIfaces));

            fout.println("Policy for UIDs:");
            fout.increaseIndent();
            int size = mUidPolicy.size();
            for (int i = 0; i < size; i++) {
                final int uid = mUidPolicy.keyAt(i);
                final int policy = mUidPolicy.valueAt(i);
                fout.print("UID=");
                fout.print(uid);
                fout.print(" policy=");
                fout.print(DebugUtils.flagsToString(NetworkPolicyManager.class, "POLICY_", policy));
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

            size = mRestrictBackgroundWhitelistUids.size();
            if (size > 0) {
                fout.println("Restrict background whitelist uids:");
                fout.increaseIndent();
                for (int i = 0; i < size; i++) {
                    fout.print("UID=");
                    fout.print(mRestrictBackgroundWhitelistUids.keyAt(i));
                    fout.println();
                }
                fout.decreaseIndent();
            }

            size = mDefaultRestrictBackgroundWhitelistUids.size();
            if (size > 0) {
                fout.println("Default restrict background whitelist uids:");
                fout.increaseIndent();
                for (int i = 0; i < size; i++) {
                    fout.print("UID=");
                    fout.print(mDefaultRestrictBackgroundWhitelistUids.keyAt(i));
                    fout.println();
                }
                fout.decreaseIndent();
            }

            size = mRestrictBackgroundWhitelistRevokedUids.size();
            if (size > 0) {
                fout.println("Default restrict background whitelist uids revoked by users:");
                fout.increaseIndent();
                for (int i = 0; i < size; i++) {
                    fout.print("UID=");
                    fout.print(mRestrictBackgroundWhitelistRevokedUids.keyAt(i));
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

                final int state = mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
                fout.print(" state=");
                fout.print(state);
                if (state <= ActivityManager.PROCESS_STATE_TOP) {
                    fout.print(" (fg)");
                } else {
                    fout.print(state <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
                            ? " (fg svc)" : " (bg)");
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
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {
        (new NetworkPolicyManagerShellCommand(mContext, this)).exec(
                this, in, out, err, args, resultReceiver);
    }

    @Override
    public boolean isUidForeground(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return isUidForegroundLocked(uid);
        }
    }

    private boolean isUidForegroundLocked(int uid) {
        return isUidStateForegroundLocked(
                mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY));
    }

    private boolean isUidForegroundOnRestrictBackgroundLocked(int uid) {
        final int procState = mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        return isProcStateAllowedWhileOnRestrictBackgroundLocked(procState);
    }

    private boolean isUidForegroundOnRestrictPowerLocked(int uid) {
        final int procState = mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        return isProcStateAllowedWhileIdleOrPowerSaveMode(procState);
    }

    private boolean isUidStateForegroundLocked(int state) {
        // only really in foreground when screen is also on
        return mScreenOn && state <= ActivityManager.PROCESS_STATE_TOP;
    }

    /**
     * Process state of UID changed; if needed, will trigger
     * {@link #updateRulesForDataUsageRestrictionsLocked(int)} and
     * {@link #updateRulesForPowerRestrictionsLocked(int)}
     */
    private void updateUidStateLocked(int uid, int uidState) {
        final int oldUidState = mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        if (oldUidState != uidState) {
            // state changed, push updated rules
            mUidState.put(uid, uidState);
            updateRestrictBackgroundRulesOnUidStatusChangedLocked(uid, oldUidState, uidState);
            if (isProcStateAllowedWhileIdleOrPowerSaveMode(oldUidState)
                    != isProcStateAllowedWhileIdleOrPowerSaveMode(uidState) ) {
                if (isUidIdle(uid)) {
                    updateRuleForAppIdleLocked(uid);
                }
                if (mDeviceIdleMode) {
                    updateRuleForDeviceIdleLocked(uid);
                }
                if (mRestrictPower) {
                    updateRuleForRestrictPowerLocked(uid);
                }
                updateRulesForPowerRestrictionsLocked(uid);
            }
            updateNetworkStats(uid, isUidStateForegroundLocked(uidState));
        }
    }

    private void removeUidStateLocked(int uid) {
        final int index = mUidState.indexOfKey(uid);
        if (index >= 0) {
            final int oldUidState = mUidState.valueAt(index);
            mUidState.removeAt(index);
            if (oldUidState != ActivityManager.PROCESS_STATE_CACHED_EMPTY) {
                updateRestrictBackgroundRulesOnUidStatusChangedLocked(uid, oldUidState,
                        ActivityManager.PROCESS_STATE_CACHED_EMPTY);
                if (mDeviceIdleMode) {
                    updateRuleForDeviceIdleLocked(uid);
                }
                if (mRestrictPower) {
                    updateRuleForRestrictPowerLocked(uid);
                }
                updateRulesForPowerRestrictionsLocked(uid);
                updateNetworkStats(uid, false);
            }
        }
    }

    // adjust stats accounting based on foreground status
    private void updateNetworkStats(int uid, boolean uidForeground) {
        try {
            mNetworkStats.setUidForeground(uid, uidForeground);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void updateRestrictBackgroundRulesOnUidStatusChangedLocked(int uid, int oldUidState,
            int newUidState) {
        final boolean oldForeground =
                isProcStateAllowedWhileOnRestrictBackgroundLocked(oldUidState);
        final boolean newForeground =
                isProcStateAllowedWhileOnRestrictBackgroundLocked(newUidState);
        if (oldForeground != newForeground) {
            updateRulesForDataUsageRestrictionsLocked(uid);
        }
    }

    private void updateScreenOn() {
        synchronized (mRulesLock) {
            try {
                mScreenOn = mPowerManager.isInteractive();
            } catch (RemoteException e) {
                // ignored; service lives in system_server
            }
            updateRulesForScreenLocked();
        }
    }

    /**
     * Update rules that might be changed by {@link #mScreenOn} value.
     */
    private void updateRulesForScreenLocked() {
        // only update rules for anyone with foreground activities
        final int size = mUidState.size();
        for (int i = 0; i < size; i++) {
            if (mUidState.valueAt(i) <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
                final int uid = mUidState.keyAt(i);
                updateRestrictionRulesForUidLocked(uid);
            }
        }
    }

    static boolean isProcStateAllowedWhileIdleOrPowerSaveMode(int procState) {
        return procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
    }

    static boolean isProcStateAllowedWhileOnRestrictBackgroundLocked(int procState) {
        return procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
    }

    void updateRulesForRestrictPowerLocked() {
        updateRulesForWhitelistedPowerSaveLocked(mRestrictPower, FIREWALL_CHAIN_POWERSAVE,
                mUidFirewallPowerSaveRules);
    }

    void updateRuleForRestrictPowerLocked(int uid) {
        updateRulesForWhitelistedPowerSaveLocked(uid, mRestrictPower, FIREWALL_CHAIN_POWERSAVE);
    }

    void updateRulesForDeviceIdleLocked() {
        updateRulesForWhitelistedPowerSaveLocked(mDeviceIdleMode, FIREWALL_CHAIN_DOZABLE,
                mUidFirewallDozableRules);
    }

    void updateRuleForDeviceIdleLocked(int uid) {
        updateRulesForWhitelistedPowerSaveLocked(uid, mDeviceIdleMode, FIREWALL_CHAIN_DOZABLE);
    }

    // NOTE: since both fw_dozable and fw_powersave uses the same map
    // (mPowerSaveTempWhitelistAppIds) for whitelisting, we can reuse their logic in this method.
    private void updateRulesForWhitelistedPowerSaveLocked(boolean enabled, int chain,
            SparseIntArray rules) {
        if (enabled) {
            // Sync the whitelists before enabling the chain.  We don't care about the rules if
            // we are disabling the chain.
            final SparseIntArray uidRules = rules;
            uidRules.clear();
            final List<UserInfo> users = mUserManager.getUsers();
            for (int ui = users.size() - 1; ui >= 0; ui--) {
                UserInfo user = users.get(ui);
                for (int i = mPowerSaveTempWhitelistAppIds.size() - 1; i >= 0; i--) {
                    if (mPowerSaveTempWhitelistAppIds.valueAt(i)) {
                        int appId = mPowerSaveTempWhitelistAppIds.keyAt(i);
                        int uid = UserHandle.getUid(user.id, appId);
                        uidRules.put(uid, FIREWALL_RULE_ALLOW);
                    }
                }
                for (int i = mPowerSaveWhitelistAppIds.size() - 1; i >= 0; i--) {
                    int appId = mPowerSaveWhitelistAppIds.keyAt(i);
                    int uid = UserHandle.getUid(user.id, appId);
                    uidRules.put(uid, FIREWALL_RULE_ALLOW);
                }
            }
            for (int i = mUidState.size() - 1; i >= 0; i--) {
                if (isProcStateAllowedWhileIdleOrPowerSaveMode(mUidState.valueAt(i))) {
                    uidRules.put(mUidState.keyAt(i), FIREWALL_RULE_ALLOW);
                }
            }
            setUidFirewallRules(chain, uidRules);
        }

        enableFirewallChainLocked(chain, enabled);
    }

    private void updateRulesForNonMeteredNetworksLocked() {

    }

    private boolean isWhitelistedBatterySaverLocked(int uid) {
        final int appId = UserHandle.getAppId(uid);
        return mPowerSaveTempWhitelistAppIds.get(appId) || mPowerSaveWhitelistAppIds.get(appId);
    }

    // NOTE: since both fw_dozable and fw_powersave uses the same map
    // (mPowerSaveTempWhitelistAppIds) for whitelisting, we can reuse their logic in this method.
    private void updateRulesForWhitelistedPowerSaveLocked(int uid, boolean enabled, int chain) {
        if (enabled) {
            if (isWhitelistedBatterySaverLocked(uid)
                    || isProcStateAllowedWhileIdleOrPowerSaveMode(mUidState.get(uid))) {
                setUidFirewallRule(chain, uid, FIREWALL_RULE_ALLOW);
            } else {
                setUidFirewallRule(chain, uid, FIREWALL_RULE_DEFAULT);
            }
        }
    }

    void updateRulesForAppIdleLocked() {
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
                    if (hasInternetPermissions(uid)) {
                        uidRules.put(uid, FIREWALL_RULE_DENY);
                    }
                }
            }
        }

        setUidFirewallRules(FIREWALL_CHAIN_STANDBY, uidRules);
    }

    void updateRuleForAppIdleLocked(int uid) {
        if (!isUidValidForBlacklistRules(uid)) return;

        int appId = UserHandle.getAppId(uid);
        if (!mPowerSaveTempWhitelistAppIds.get(appId) && isUidIdle(uid)
                && !isUidForegroundOnRestrictPowerLocked(uid)) {
            setUidFirewallRule(FIREWALL_CHAIN_STANDBY, uid, FIREWALL_RULE_DENY);
        } else {
            setUidFirewallRule(FIREWALL_CHAIN_STANDBY, uid, FIREWALL_RULE_DEFAULT);
        }
    }

    void updateRulesForAppIdleParoleLocked() {
        boolean enableChain = !mUsageStats.isAppIdleParoleOn();
        enableFirewallChainLocked(FIREWALL_CHAIN_STANDBY, enableChain);
    }

    /**
     * Update rules that might be changed by {@link #mRestrictBackground},
     * {@link #mRestrictPower}, or {@link #mDeviceIdleMode} value.
     */
    private void updateRulesForGlobalChangeLocked(boolean restrictedNetworksChanged) {
        long start;
        if (LOGD) start = System.currentTimeMillis();

        updateRulesForDeviceIdleLocked();
        updateRulesForAppIdleLocked();
        updateRulesForRestrictPowerLocked();
        updateRulesForRestrictBackgroundLocked();
        setRestrictBackgroundLocked(mRestrictBackground);

        // If the set of restricted networks may have changed, re-evaluate those.
        if (restrictedNetworksChanged) {
            normalizePoliciesLocked();
            updateNetworkRulesLocked();
        }
        if (LOGD) {
            final long delta = System.currentTimeMillis() - start;
            Slog.d(TAG, "updateRulesForGlobalChangeLocked(" + restrictedNetworksChanged + ") took "
                    + delta + "ms");
        }
    }

    private void updateRulesForRestrictBackgroundLocked() {
        final PackageManager pm = mContext.getPackageManager();

        // update rules for all installed applications
        final List<UserInfo> users = mUserManager.getUsers();
        final List<ApplicationInfo> apps = pm.getInstalledApplications(
                PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);

        final int usersSize = users.size();
        final int appsSize = apps.size();
        for (int i = 0; i < usersSize; i++) {
            final UserInfo user = users.get(i);
            for (int j = 0; j < appsSize; j++) {
                final ApplicationInfo app = apps.get(j);
                final int uid = UserHandle.getUid(user.id, app.uid);
                updateRulesForDataUsageRestrictionsLocked(uid);
                updateRulesForPowerRestrictionsLocked(uid);
            }
        }
    }

    private void updateRulesForTempWhitelistChangeLocked() {
        final List<UserInfo> users = mUserManager.getUsers();
        for (int i = 0; i < users.size(); i++) {
            final UserInfo user = users.get(i);
            for (int j = mPowerSaveTempWhitelistAppIds.size() - 1; j >= 0; j--) {
                int appId = mPowerSaveTempWhitelistAppIds.keyAt(j);
                int uid = UserHandle.getUid(user.id, appId);
                // Update external firewall rules.
                updateRuleForAppIdleLocked(uid);
                updateRuleForDeviceIdleLocked(uid);
                updateRuleForRestrictPowerLocked(uid);
                // Update internal rules.
                updateRulesForPowerRestrictionsLocked(uid);
            }
        }
    }

    // TODO: the MEDIA / DRM restriction might not be needed anymore, in which case both
    // methods below could be merged into a isUidValidForRules() method.
    private boolean isUidValidForBlacklistRules(int uid) {
        // allow rules on specific system services, and any apps
        if (uid == android.os.Process.MEDIA_UID || uid == android.os.Process.DRM_UID
            || (UserHandle.isApp(uid) && hasInternetPermissions(uid))) {
            return true;
        }

        return false;
    }

    private boolean isUidValidForWhitelistRules(int uid) {
        return UserHandle.isApp(uid) && hasInternetPermissions(uid);
    }

    private boolean isUidIdle(int uid) {
        final String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        final int userId = UserHandle.getUserId(uid);

        if (!ArrayUtils.isEmpty(packages)) {
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
    private boolean hasInternetPermissions(int uid) {
        try {
            if (mIPm.checkUidPermission(Manifest.permission.INTERNET, uid)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        } catch (RemoteException e) {
        }
        return true;
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
    private void updateRestrictionRulesForUidLocked(int uid) {
        // Methods below only changes the firewall rules for the power-related modes.
        updateRuleForDeviceIdleLocked(uid);
        updateRuleForAppIdleLocked(uid);
        updateRuleForRestrictPowerLocked(uid);

        // Update internal state for power-related modes.
        updateRulesForPowerRestrictionsLocked(uid);

        // Update firewall and internal rules for Data Saver Mode.
        updateRulesForDataUsageRestrictionsLocked(uid);
    }

    /**
     * Applies network rules to bandwidth controllers based on process state and user-defined
     * restrictions (blacklist / whitelist).
     *
     * <p>
     * {@code netd} defines 3 firewall chains that govern whether an app has access to metered
     * networks:
     * <ul>
     * <li>@{code bw_penalty_box}: UIDs added to this chain do not have access (blacklist).
     * <li>@{code bw_happy_box}: UIDs added to this chain have access (whitelist), unless they're
     *     also blacklisted.
     * <li>@{code bw_data_saver}: when enabled (through {@link #setRestrictBackground(boolean)}),
     *     no UIDs other those whitelisted will have access.
     * <ul>
     *
     * <p>The @{code bw_penalty_box} and @{code bw_happy_box} are primarily managed through the
     * {@link #setUidPolicy(int, int)} and {@link #addRestrictBackgroundWhitelistedUid(int)} /
     * {@link #removeRestrictBackgroundWhitelistedUid(int)} methods (for blacklist and whitelist
     * respectively): these methods set the proper internal state (blacklist / whitelist), then call
     * this ({@link #updateRulesForDataUsageRestrictionsLocked(int)}) to propagate the rules to
     * {@link INetworkManagementService}, but this method should also be called in events (like
     * Data Saver Mode flips or UID state changes) that might affect the foreground app, since the
     * following rules should also be applied:
     *
     * <ul>
     * <li>When Data Saver mode is on, the foreground app should be temporarily added to
     *     {@code bw_happy_box} before the @{code bw_data_saver} chain is enabled.
     * <li>If the foreground app is blacklisted by the user, it should be temporarily removed from
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
    private void updateRulesForDataUsageRestrictionsLocked(int uid) {
        updateRulesForDataUsageRestrictionsLocked(uid, false);
    }

    /**
     * Overloaded version of {@link #updateRulesForDataUsageRestrictionsLocked(int)} called when an
     * app is removed - it ignores the UID validity check.
     */
    private void updateRulesForDataUsageRestrictionsLocked(int uid, boolean uidDeleted) {
        if (!uidDeleted && !isUidValidForWhitelistRules(uid)) {
            if (LOGD) Slog.d(TAG, "no need to update restrict data rules for uid " + uid);
            return;
        }

        final int uidPolicy = mUidPolicy.get(uid, POLICY_NONE);
        final int oldUidRules = mUidRules.get(uid, RULE_NONE);
        final boolean isForeground = isUidForegroundOnRestrictBackgroundLocked(uid);

        final boolean isBlacklisted = (uidPolicy & POLICY_REJECT_METERED_BACKGROUND) != 0;
        final boolean isWhitelisted = mRestrictBackgroundWhitelistUids.get(uid);
        final int oldRule = oldUidRules & MASK_METERED_NETWORKS;
        int newRule = RULE_NONE;

        // First step: define the new rule based on user restrictions and foreground state.
        if (isForeground) {
            if (isBlacklisted || (mRestrictBackground && !isWhitelisted)) {
                newRule = RULE_TEMPORARY_ALLOW_METERED;
            } else if (isWhitelisted) {
                newRule = RULE_ALLOW_METERED;
            }
        } else {
            if (isBlacklisted) {
                newRule = RULE_REJECT_METERED;
            } else if (mRestrictBackground && isWhitelisted) {
                newRule = RULE_ALLOW_METERED;
            }
        }
        final int newUidRules = newRule | (oldUidRules & MASK_ALL_NETWORKS);

        if (LOGV) {
            Log.v(TAG, "updateRuleForRestrictBackgroundLocked(" + uid + ")"
                    + ": isForeground=" +isForeground
                    + ", isBlacklisted=" + isBlacklisted
                    + ", isWhitelisted=" + isWhitelisted
                    + ", oldRule=" + uidRulesToString(oldRule)
                    + ", newRule=" + uidRulesToString(newRule)
                    + ", newUidRules=" + uidRulesToString(newUidRules)
                    + ", oldUidRules=" + uidRulesToString(oldUidRules));
        }

        if (newUidRules == RULE_NONE) {
            mUidRules.delete(uid);
        } else {
            mUidRules.put(uid, newUidRules);
        }

        boolean changed = false;

        // Second step: apply bw changes based on change of state.
        if (newRule != oldRule) {
            changed = true;

            if ((newRule & RULE_TEMPORARY_ALLOW_METERED) != 0) {
                // Temporarily whitelist foreground app, removing from blacklist if necessary
                // (since bw_penalty_box prevails over bw_happy_box).

                setMeteredNetworkWhitelist(uid, true);
                // TODO: if statement below is used to avoid an unnecessary call to netd / iptables,
                // but ideally it should be just:
                //    setMeteredNetworkBlacklist(uid, isBlacklisted);
                if (isBlacklisted) {
                    setMeteredNetworkBlacklist(uid, false);
                }
            } else if ((oldRule & RULE_TEMPORARY_ALLOW_METERED) != 0) {
                // Remove temporary whitelist from app that is not on foreground anymore.

                // TODO: if statements below are used to avoid unnecessary calls to netd / iptables,
                // but ideally they should be just:
                //    setMeteredNetworkWhitelist(uid, isWhitelisted);
                //    setMeteredNetworkBlacklist(uid, isBlacklisted);
                if (!isWhitelisted) {
                    setMeteredNetworkWhitelist(uid, false);
                }
                if (isBlacklisted) {
                    setMeteredNetworkBlacklist(uid, true);
                }
            } else if ((newRule & RULE_REJECT_METERED) != 0
                    || (oldRule & RULE_REJECT_METERED) != 0) {
                // Flip state because app was explicitly added or removed to blacklist.
                setMeteredNetworkBlacklist(uid, isBlacklisted);
                if ((oldRule & RULE_REJECT_METERED) != 0 && isWhitelisted) {
                    // Since blacklist prevails over whitelist, we need to handle the special case
                    // where app is whitelisted and blacklisted at the same time (although such
                    // scenario should be blocked by the UI), then blacklist is removed.
                    setMeteredNetworkWhitelist(uid, isWhitelisted);
                }
            } else if ((newRule & RULE_ALLOW_METERED) != 0
                    || (oldRule & RULE_ALLOW_METERED) != 0) {
                // Flip state because app was explicitly added or removed to whitelist.
                setMeteredNetworkWhitelist(uid, isWhitelisted);
            } else {
                // All scenarios should have been covered above.
                Log.wtf(TAG, "Unexpected change of metered UID state for " + uid
                        + ": foreground=" + isForeground
                        + ", whitelisted=" + isWhitelisted
                        + ", blacklisted=" + isBlacklisted
                        + ", newRule=" + uidRulesToString(newUidRules)
                        + ", oldRule=" + uidRulesToString(oldUidRules));
            }

            // Dispatch changed rule to existing listeners.
            mHandler.obtainMessage(MSG_RULES_CHANGED, uid, newUidRules).sendToTarget();
        }
    }

    /**
     * Updates the power-related part of the {@link #mUidRules} for a given map, and notify external
     * listeners in case of change.
     * <p>
     * There are 3 power-related rules that affects whether an app has background access on
     * non-metered networks, and when the condition applies and the UID is not whitelisted for power
     * restriction, it's added to the equivalent firewall chain:
     * <ul>
     * <li>App is idle: {@code fw_standby} firewall chain.
     * <li>Device is idle: {@code fw_dozable} firewall chain.
     * <li>Battery Saver Mode is on: {@code fw_powersave} firewall chain.
     * </ul>
     * <p>
     * This method updates the power-related part of the {@link #mUidRules} for a given uid based on
     * these modes, the UID process state (foreground or not), and the UIDwhitelist state.
     * <p>
     * <strong>NOTE: </strong>This method does not update the firewall rules on {@code netd}.
     */
    private void updateRulesForPowerRestrictionsLocked(int uid) {
        if (!isUidValidForBlacklistRules(uid)) {
            if (LOGD) Slog.d(TAG, "no need to update restrict power rules for uid " + uid);
            return;
        }

        final boolean isIdle = isUidIdle(uid);
        final boolean restrictMode = isIdle || mRestrictPower || mDeviceIdleMode;
        final int uidPolicy = mUidPolicy.get(uid, POLICY_NONE);
        final int oldUidRules = mUidRules.get(uid, RULE_NONE);
        final boolean isForeground = isUidForegroundOnRestrictPowerLocked(uid);

        final boolean isWhitelisted = isWhitelistedBatterySaverLocked(uid);
        final int oldRule = oldUidRules & MASK_ALL_NETWORKS;
        int newRule = RULE_NONE;

        // First step: define the new rule based on user restrictions and foreground state.

        // NOTE: if statements below could be inlined, but it's easier to understand the logic
        // by considering the foreground and non-foreground states.
        if (isForeground) {
            if (restrictMode) {
                newRule = RULE_ALLOW_ALL;
            }
        } else if (restrictMode) {
            newRule = isWhitelisted ? RULE_ALLOW_ALL : RULE_REJECT_ALL;
        }

        final int newUidRules = (oldUidRules & MASK_METERED_NETWORKS) | newRule;

        if (LOGV) {
            Log.v(TAG, "updateRulesForNonMeteredNetworksLocked(" + uid + ")"
                    + ", isIdle: " + isIdle
                    + ", mRestrictPower: " + mRestrictPower
                    + ", mDeviceIdleMode: " + mDeviceIdleMode
                    + ", isForeground=" + isForeground
                    + ", isWhitelisted=" + isWhitelisted
                    + ", oldRule=" + uidRulesToString(oldRule)
                    + ", newRule=" + uidRulesToString(newRule)
                    + ", newUidRules=" + uidRulesToString(newUidRules)
                    + ", oldUidRules=" + uidRulesToString(oldUidRules));
        }

        if (newUidRules == RULE_NONE) {
            mUidRules.delete(uid);
        } else {
            mUidRules.put(uid, newUidRules);
        }

        // Second step: notify listeners if state changed.
        if (newRule != oldRule) {
            if (newRule == RULE_NONE || (newRule & RULE_ALLOW_ALL) != 0) {
                if (LOGV) Log.v(TAG, "Allowing non-metered access for UID " + uid);
            } else if ((newRule & RULE_REJECT_ALL) != 0) {
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
    }

    private class AppIdleStateChangeListener
            extends UsageStatsManagerInternal.AppIdleStateChangeListener {

        @Override
        public void onAppIdleStateChanged(String packageName, int userId, boolean idle) {
            try {
                final int uid = mContext.getPackageManager().getPackageUidAsUser(packageName,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                if (LOGV) Log.v(TAG, "onAppIdleStateChanged(): uid=" + uid + ", idle=" + idle);
                synchronized (mRulesLock) {
                    updateRuleForAppIdleLocked(uid);
                    updateRulesForPowerRestrictionsLocked(uid);
                }
            } catch (NameNotFoundException nnfe) {
            }
        }

        @Override
        public void onParoleStateChanged(boolean isParoleOn) {
            synchronized (mRulesLock) {
                updateRulesForAppIdleParoleLocked();
            }
        }
    }

    private void dispatchUidRulesChanged(INetworkPolicyListener listener, int uid, int uidRules) {
        if (listener != null) {
            try {
                listener.onUidRulesChanged(uid, uidRules);
            } catch (RemoteException ignored) {
            }
        }
    }

    private void dispatchMeteredIfacesChanged(INetworkPolicyListener listener,
            String[] meteredIfaces) {
        if (listener != null) {
            try {
                listener.onMeteredIfacesChanged(meteredIfaces);
            } catch (RemoteException ignored) {
            }
        }
    }

    private void dispatchRestrictBackgroundChanged(INetworkPolicyListener listener,
            boolean restrictBackground) {
        if (listener != null) {
            try {
                listener.onRestrictBackgroundChanged(restrictBackground);
            } catch (RemoteException ignored) {
            }
        }
    }

    private void dispatchRestrictBackgroundWhitelistChanged(INetworkPolicyListener listener,
            int uid, boolean whitelisted) {
        if (listener != null) {
            try {
                listener.onRestrictBackgroundWhitelistChanged(uid, whitelisted);
            } catch (RemoteException ignored) {
            }
        }
    }

    private void dispatchRestrictBackgroundBlacklistChanged(INetworkPolicyListener listener,
            int uid, boolean blacklisted) {
        if (listener != null) {
            try {
                listener.onRestrictBackgroundBlacklistChanged(uid, blacklisted);
            } catch (RemoteException ignored) {
            }
        }
    }

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RULES_CHANGED: {
                    final int uid = msg.arg1;
                    final int uidRules = msg.arg2;
                    dispatchUidRulesChanged(mConnectivityListener, uid, uidRules);
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
                    dispatchMeteredIfacesChanged(mConnectivityListener, meteredIfaces);
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchMeteredIfacesChanged(listener, meteredIfaces);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_LIMIT_REACHED: {
                    final String iface = (String) msg.obj;

                    maybeRefreshTrustedTime();
                    synchronized (mRulesLock) {
                        if (mMeteredIfaces.contains(iface)) {
                            try {
                                // force stats update to make sure we have
                                // numbers that caused alert to trigger.
                                mNetworkStats.forceUpdate();
                            } catch (RemoteException e) {
                                // ignored; service lives in system_server
                            }

                            updateNetworkEnabledLocked();
                            updateNotificationsLocked();
                        }
                    }
                    return true;
                }
                case MSG_RESTRICT_BACKGROUND_CHANGED: {
                    final boolean restrictBackground = msg.arg1 != 0;
                    dispatchRestrictBackgroundChanged(mConnectivityListener, restrictBackground);
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
                case MSG_RESTRICT_BACKGROUND_WHITELIST_CHANGED: {
                    // MSG_RESTRICT_BACKGROUND_WHITELIST_CHANGED can be called in 2 occasions:
                    // - when an app is whitelisted
                    // - when an app is blacklisted
                    //
                    // Whether the internal listeners (INetworkPolicyListener implementations) or
                    // app broadcast receivers are notified depend on the following rules:
                    //
                    // - App receivers are only notified when the app status changed (msg.arg2 = 1)
                    // - Listeners are only notified when app was whitelisted (msg.obj is not null),
                    //   since blacklist notifications are handled through MSG_RULES_CHANGED).
                    final int uid = msg.arg1;
                    final boolean changed = msg.arg2 == 1;
                    final Boolean whitelisted = (Boolean) msg.obj;

                    // First notify internal listeners...
                    if (whitelisted != null) {
                        final boolean whitelistedBool = whitelisted.booleanValue();
                        dispatchRestrictBackgroundWhitelistChanged(mConnectivityListener, uid,
                                whitelistedBool);
                        final int length = mListeners.beginBroadcast();
                        for (int i = 0; i < length; i++) {
                            final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                            dispatchRestrictBackgroundWhitelistChanged(listener, uid,
                                    whitelistedBool);
                        }
                        mListeners.finishBroadcast();
                    }
                    final PackageManager pm = mContext.getPackageManager();
                    final String[] packages = pm.getPackagesForUid(uid);
                    if (changed && packages != null) {
                        // ...then notify apps listening to ACTION_RESTRICT_BACKGROUND_CHANGED
                        final int userId = UserHandle.getUserId(uid);
                        for (String packageName : packages) {
                            final Intent intent = new Intent(
                                    ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED);
                            intent.setPackage(packageName);
                            intent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                            mContext.sendBroadcastAsUser(intent, UserHandle.of(userId));
                        }
                    }
                    return true;
                }
                case MSG_RESTRICT_BACKGROUND_BLACKLIST_CHANGED: {
                    final int uid = msg.arg1;
                    final boolean blacklisted = msg.arg2 == 1;

                    dispatchRestrictBackgroundBlacklistChanged(mConnectivityListener, uid,
                            blacklisted);
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        dispatchRestrictBackgroundBlacklistChanged(listener, uid,
                                blacklisted);
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_ADVISE_PERSIST_THRESHOLD: {
                    final long lowestRule = (Long) msg.obj;
                    try {
                        // make sure stats are recorded frequently enough; we aim
                        // for 2MB threshold for 2GB/month rules.
                        final long persistThreshold = lowestRule / 1000;
                        mNetworkStats.advisePersistThreshold(persistThreshold);
                    } catch (RemoteException e) {
                        // ignored; service lives in system_server
                    }
                    return true;
                }
                case MSG_SCREEN_ON_CHANGED: {
                    updateScreenOn();
                    return true;
                }
                case MSG_UPDATE_INTERFACE_QUOTA: {
                    removeInterfaceQuota((String) msg.obj);
                    // int params need to be stitched back into a long
                    setInterfaceQuota((String) msg.obj,
                            ((long) msg.arg1 << 32) | (msg.arg2 & 0xFFFFFFFFL));
                    return true;
                }
                case MSG_REMOVE_INTERFACE_QUOTA: {
                    removeInterfaceQuota((String) msg.obj);
                    return true;
                }
                default: {
                    return false;
                }
            }
        }
    };

    private void setInterfaceQuota(String iface, long quotaBytes) {
        try {
            mNetworkManager.setInterfaceQuota(iface, quotaBytes);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting interface quota", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
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

    private void setMeteredNetworkBlacklist(int uid, boolean enable) {
        if (LOGV) Slog.v(TAG, "setMeteredNetworkBlacklist " + uid + ": " + enable);
        try {
            mNetworkManager.setUidMeteredNetworkBlacklist(uid, enable);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting blacklist (" + enable + ") rules for " + uid, e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void setMeteredNetworkWhitelist(int uid, boolean enable) {
        if (LOGV) Slog.v(TAG, "setMeteredNetworkWhitelist " + uid + ": " + enable);
        try {
            mNetworkManager.setUidMeteredNetworkWhitelist(uid, enable);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting whitelist (" + enable + ") rules for " + uid, e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Set uid rules on a particular firewall chain. This is going to synchronize the rules given
     * here to netd.  It will clean up dead rules and make sure the target chain only contains rules
     * specified here.
     */
    private void setUidFirewallRules(int chain, SparseIntArray uidRules) {
        try {
            int size = uidRules.size();
            int[] uids = new int[size];
            int[] rules = new int[size];
            for(int index = size - 1; index >= 0; --index) {
                uids[index] = uidRules.keyAt(index);
                rules[index] = uidRules.valueAt(index);
            }
            mNetworkManager.setFirewallUidRules(chain, uids, rules);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting firewall uid rules", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Add or remove a uid to the firewall blacklist for all network ifaces.
     */
    private void setUidFirewallRule(int chain, int uid, int rule) {
        if (chain == FIREWALL_CHAIN_DOZABLE) {
            mUidFirewallDozableRules.put(uid, rule);
        } else if (chain == FIREWALL_CHAIN_STANDBY) {
            mUidFirewallStandbyRules.put(uid, rule);
        } else if (chain == FIREWALL_CHAIN_POWERSAVE) {
            mUidFirewallPowerSaveRules.put(uid, rule);
        }

        try {
            mNetworkManager.setFirewallUidRule(chain, uid, rule);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting firewall uid rules", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Add or remove a uid to the firewall blacklist for all network ifaces.
     */
    private void enableFirewallChainLocked(int chain, boolean enable) {
        if (mFirewallChainStates.indexOfKey(chain) >= 0 &&
                mFirewallChainStates.get(chain) == enable) {
            // All is the same, nothing to do.
            return;
        }
        mFirewallChainStates.put(chain, enable);
        try {
            mNetworkManager.setFirewallChainEnabled(chain, enable);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem enable firewall chain", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private long getTotalBytes(NetworkTemplate template, long start, long end) {
        try {
            return mNetworkStats.getNetworkTotalBytes(template, start, end);
        } catch (RuntimeException e) {
            Slog.w(TAG, "problem reading network stats: " + e);
            return 0;
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return 0;
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

    /**
     * Try refreshing {@link #mTime} when stale.
     */
    void maybeRefreshTrustedTime() {
        if (mTime.getCacheAge() > TIME_CACHE_MAX_AGE) {
            mTime.forceRefresh();
        }
    }

    private long currentTimeMillis() {
        return mTime.hasCache() ? mTime.currentTimeMillis() : System.currentTimeMillis();
    }

    private static Intent buildAllowBackgroundDataIntent() {
        return new Intent(ACTION_ALLOW_BACKGROUND);
    }

    private static Intent buildSnoozeWarningIntent(NetworkTemplate template) {
        final Intent intent = new Intent(ACTION_SNOOZE_WARNING);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    private static Intent buildNetworkOverLimitIntent(NetworkTemplate template) {
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.android.systemui", "com.android.systemui.net.NetworkOverLimitActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    private static Intent buildViewDataUsageIntent(NetworkTemplate template) {
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_NETWORK_TEMPLATE, template);
        return intent;
    }

    @VisibleForTesting
    public void addIdleHandler(IdleHandler handler) {
        mHandler.getLooper().getQueue().addIdleHandler(handler);
    }

    private static void collectKeys(SparseIntArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    @Override
    public void factoryReset(String subscriber) {
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

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

    private class MyPackageMonitor extends PackageMonitor {

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            if (LOGV) Slog.v(TAG, "onPackageRemoved: " + packageName + " ->" + uid);
            synchronized (mRulesLock) {
                removeRestrictBackgroundWhitelistedUidLocked(uid, true, true);
                updateRestrictionRulesForUidLocked(uid);
            }
        }
    }

    private class NetworkPolicyManagerInternalImpl extends NetworkPolicyManagerInternal {

        @Override
        public void resetUserState(int userId) {
            synchronized (mRulesLock) {
                boolean changed = removeUserStateLocked(userId, false);
                changed = addDefaultRestrictBackgroundWhitelistUidsLocked(userId) || changed;
                if (changed) {
                    writePolicyLocked();
                }
            }
        }
    }
}
