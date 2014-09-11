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
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.ACTION_USER_ADDED;
import static android.content.Intent.ACTION_USER_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.ConnectivityManager.isNetworkTypeMobile;
import static android.net.NetworkPolicy.CYCLE_NONE;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.EXTRA_NETWORK_TEMPLATE;
import static android.net.NetworkPolicyManager.POLICY_ALLOW_BACKGROUND_BATTERY_SAVE;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkPolicyManager.dumpPolicy;
import static android.net.NetworkPolicyManager.dumpRules;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
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
import static android.telephony.TelephonyManager.SIM_STATE_READY;
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
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.IProcessObserver;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import android.os.INetworkManagementService;
import android.os.IPowerManager;
import android.os.Message;
import android.os.MessageQueue.IdleHandler;
import android.os.PowerManagerInternal;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TrustedTime;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Service that maintains low-level network policy rules, using
 * {@link NetworkStatsService} statistics to drive those rules.
 * <p>
 * Derives active rules by combining a given policy with other system status,
 * and delivers to listeners, such as {@link ConnectivityManager}, for
 * enforcement.
 */
public class NetworkPolicyManagerService extends INetworkPolicyManager.Stub {
    private static final String TAG = "NetworkPolicy";
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

    private static final String TAG_ALLOW_BACKGROUND = TAG + ":allowBackground";

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

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final IPowerManager mPowerManager;
    private final INetworkStatsService mNetworkStats;
    private final INetworkManagementService mNetworkManager;
    private final TrustedTime mTime;

    private IConnectivityManager mConnManager;
    private INotificationManager mNotifManager;
    private PowerManagerInternal mPowerManagerInternal;

    final Object mRulesLock = new Object();

    volatile boolean mScreenOn;
    volatile boolean mRestrictBackground;
    volatile boolean mRestrictPower;

    private final boolean mSuppressDefaultPolicy;

    /** Defined network policies. */
    final ArrayMap<NetworkTemplate, NetworkPolicy> mNetworkPolicy = new ArrayMap<
            NetworkTemplate, NetworkPolicy>();
    /** Currently active network rules for ifaces. */
    private final ArrayMap<NetworkPolicy, String[]> mNetworkRules = new ArrayMap<
            NetworkPolicy, String[]>();

    /** Defined UID policies. */
    final SparseIntArray mUidPolicy = new SparseIntArray();
    /** Currently derived rules for each UID. */
    private final SparseIntArray mUidRules = new SparseIntArray();

    /** UIDs that have been white-listed to always be able to have network access in
     * power save mode. */
    private final SparseBooleanArray mPowerSaveWhitelistAppIds = new SparseBooleanArray();

    /** Set of ifaces that are metered. */
    private ArraySet<String> mMeteredIfaces = new ArraySet<String>();
    /** Set of over-limit templates that have been notified. */
    private final ArraySet<NetworkTemplate> mOverLimitNotified = new ArraySet<NetworkTemplate>();

    /** Set of currently active {@link Notification} tags. */
    private final ArraySet<String> mActiveNotifs = new ArraySet<String>();

    /** Foreground at both UID and PID granularity. */
    private final SparseIntArray mUidState = new SparseIntArray();
    final SparseArray<SparseIntArray> mUidPidState = new SparseArray<SparseIntArray>();

    /** The current maximum process state that we are considering to be foreground. */
    private int mCurForegroundState = ActivityManager.PROCESS_STATE_TOP;

    private final RemoteCallbackList<INetworkPolicyListener> mListeners = new RemoteCallbackList<
            INetworkPolicyListener>();

    final Handler mHandler;

    private final AtomicFile mPolicyFile;

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
        mTime = checkNotNull(time, "missing TrustedTime");

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper(), mHandlerCallback);

        mSuppressDefaultPolicy = suppressDefaultPolicy;

        mPolicyFile = new AtomicFile(new File(systemDir, "netpolicy.xml"));
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void bindNotificationManager(INotificationManager notifManager) {
        mNotifManager = checkNotNull(notifManager, "missing INotificationManager");
    }

    public void systemReady() {
        if (!isBandwidthControlEnabled()) {
            Slog.w(TAG, "bandwidth controls disabled, unable to enforce policy");
            return;
        }

        final PackageManager pm = mContext.getPackageManager();

        synchronized (mRulesLock) {
            SystemConfig sysConfig = SystemConfig.getInstance();
            ArraySet<String> allowPower = sysConfig.getAllowInPowerSave();
            for (int i=0; i<allowPower.size(); i++) {
                String pkg = allowPower.valueAt(i);
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if ((ai.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        mPowerSaveWhitelistAppIds.put(UserHandle.getAppId(ai.uid), true);
                    }
                } catch (PackageManager.NameNotFoundException e) {
                }
            }

            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            mPowerManagerInternal.registerLowPowerModeObserver(
                    new PowerManagerInternal.LowPowerModeListener() {
                @Override
                public void onLowPowerModeChanged(boolean enabled) {
                    synchronized (mRulesLock) {
                        if (mRestrictPower != enabled) {
                            mRestrictPower = enabled;
                            updateRulesForGlobalChangeLocked(true);
                        }
                    }
                }
            });
            mRestrictPower = mPowerManagerInternal.getLowPowerModeEnabled();

            // read policy from disk
            readPolicyLocked();

            if (mRestrictBackground || mRestrictPower) {
                updateRulesForGlobalChangeLocked(true);
                updateNotificationsLocked();
            }
        }

        updateScreenOn();

        try {
            mActivityManager.registerProcessObserver(mProcessObserver);
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

        // watch for network interfaces to be claimed
        final IntentFilter connFilter = new IntentFilter(CONNECTIVITY_ACTION_IMMEDIATE);
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

    }

    private IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
        }

        @Override
        public void onProcessStateChanged(int pid, int uid, int procState) {
            synchronized (mRulesLock) {
                // because a uid can have multiple pids running inside, we need to
                // remember all pid states and summarize foreground at uid level.

                // record foreground for this specific pid
                SparseIntArray pidState = mUidPidState.get(uid);
                if (pidState == null) {
                    pidState = new SparseIntArray(2);
                    mUidPidState.put(uid, pidState);
                }
                pidState.put(pid, procState);
                computeUidStateLocked(uid);
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            synchronized (mRulesLock) {
                // clear records and recompute, when they exist
                final SparseIntArray pidState = mUidPidState.get(uid);
                if (pidState != null) {
                    pidState.delete(pid);
                    if (pidState.size() <= 0) {
                        mUidPidState.remove(uid);
                    }
                    computeUidStateLocked(uid);
                }
            }
        }
    };

    private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // screen-related broadcasts are protected by system, no need
            // for permissions check.
            mHandler.obtainMessage(MSG_SCREEN_ON_CHANGED).sendToTarget();
        }
    };

    private BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
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
                    updateRulesForUidLocked(uid);
                }
            }
        }
    };

    private BroadcastReceiver mUidRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and UID_REMOVED is protected

            final int uid = intent.getIntExtra(EXTRA_UID, -1);
            if (uid == -1) return;

            // remove any policy and update rules to clean up
            if (LOGV) Slog.v(TAG, "ACTION_UID_REMOVED for uid=" + uid);
            synchronized (mRulesLock) {
                mUidPolicy.delete(uid);
                updateRulesForUidLocked(uid);
                writePolicyLocked();
            }
        }
    };

    private BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and USER_ADDED and USER_REMOVED
            // broadcasts are protected

            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (userId == -1) return;

            synchronized (mRulesLock) {
                // Remove any policies for given user; both cleaning up after a
                // USER_REMOVED, and one last sanity check during USER_ADDED
                removePoliciesForUserLocked(userId);
                // Update global restrict for new user
                updateRulesForGlobalChangeLocked(true);
            }
        }
    };

    /**
     * Receiver that watches for {@link INetworkStatsService} updates, which we
     * use to check against {@link NetworkPolicy#warningBytes}.
     */
    private BroadcastReceiver mStatsReceiver = new BroadcastReceiver() {
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
    private BroadcastReceiver mAllowReceiver = new BroadcastReceiver() {
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
    private BroadcastReceiver mSnoozeWarningReceiver = new BroadcastReceiver() {
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
    private BroadcastReceiver mWifiConfigReceiver = new BroadcastReceiver() {
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
    private BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
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
                    policy = new NetworkPolicy(template, CYCLE_NONE, Time.TIMEZONE_UTC,
                            WARNING_DISABLED, LIMIT_DISABLED, SNOOZE_NEVER, SNOOZE_NEVER,
                            meteredHint, true);
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

    /**
     * Observer that watches for {@link INetworkManagementService} alerts.
     */
    private INetworkManagementEventObserver mAlertObserver = new BaseNetworkObserver() {
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

        // ongoing notification when restricting background data
        if (mRestrictBackground) {
            enqueueRestrictedNotification(TAG_ALLOW_BACKGROUND);
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
        final TelephonyManager tele = TelephonyManager.from(mContext);

        switch (template.getMatchRule()) {
            case MATCH_MOBILE_3G_LOWER:
            case MATCH_MOBILE_4G:
            case MATCH_MOBILE_ALL:
                // mobile templates are relevant when SIM is ready and
                // subscriberId matches.
                if (tele.getSimState() == SIM_STATE_READY) {
                    return Objects.equals(tele.getSubscriberId(), template.getSubscriberId());
                } else {
                    return false;
                }
        }
        return true;
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
        builder.setColor(mContext.getResources().getColor(
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
        // XXX what to do about multi-user?
        try {
            final String packageName = mContext.getPackageName();
            final int[] idReceived = new int[1];
            mNotifManager.enqueueNotificationWithTag(
                    packageName, packageName, tag, 0x0, builder.getNotification(), idReceived,
                    UserHandle.USER_OWNER);
            mActiveNotifs.add(tag);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Show ongoing notification to reflect that {@link #mRestrictBackground}
     * has been enabled.
     */
    private void enqueueRestrictedNotification(String tag) {
        final Resources res = mContext.getResources();
        final Notification.Builder builder = new Notification.Builder(mContext);

        final CharSequence title = res.getText(R.string.data_usage_restricted_title);
        final CharSequence body = res.getString(R.string.data_usage_restricted_body);

        builder.setOnlyAlertOnce(true);
        builder.setOngoing(true);
        builder.setSmallIcon(R.drawable.stat_notify_error);
        builder.setTicker(title);
        builder.setContentTitle(title);
        builder.setContentText(body);
        builder.setColor(mContext.getResources().getColor(
                com.android.internal.R.color.system_notification_accent_color));

        final Intent intent = buildAllowBackgroundDataIntent();
        builder.setContentIntent(
                PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));

        // TODO: move to NotificationManager once we can mock it
        // XXX what to do about multi-user?
        try {
            final String packageName = mContext.getPackageName();
            final int[] idReceived = new int[1];
            mNotifManager.enqueueNotificationWithTag(packageName, packageName, tag,
                    0x0, builder.getNotification(), idReceived, UserHandle.USER_OWNER);
            mActiveNotifs.add(tag);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private void cancelNotification(String tag) {
        // TODO: move to NotificationManager once we can mock it
        // XXX what to do about multi-user?
        try {
            final String packageName = mContext.getPackageName();
            mNotifManager.cancelNotificationWithTag(
                    packageName, tag, 0x0, UserHandle.USER_OWNER);
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
     * Control {@link IConnectivityManager#setPolicyDataEnable(int, boolean)}
     * for the given {@link NetworkTemplate}.
     */
    private void setNetworkTemplateEnabled(NetworkTemplate template, boolean enabled) {
        final TelephonyManager tele = TelephonyManager.from(mContext);

        switch (template.getMatchRule()) {
            case MATCH_MOBILE_3G_LOWER:
            case MATCH_MOBILE_4G:
            case MATCH_MOBILE_ALL:
                // TODO: offer more granular control over radio states once
                // 4965893 is available.
                if (tele.getSimState() == SIM_STATE_READY
                        && Objects.equals(tele.getSubscriberId(), template.getSubscriberId())) {
                    setPolicyDataEnable(TYPE_MOBILE, enabled);
                    setPolicyDataEnable(TYPE_WIMAX, enabled);
                }
                break;
            case MATCH_WIFI:
                setPolicyDataEnable(TYPE_WIFI, enabled);
                break;
            case MATCH_ETHERNET:
                setPolicyDataEnable(TYPE_ETHERNET, enabled);
                break;
            default:
                throw new IllegalArgumentException("unexpected template");
        }
    }

    /**
     * Examine all connected {@link NetworkState}, looking for
     * {@link NetworkPolicy} that need to be enforced. When matches found, set
     * remaining quota based on usage cycle and historical stats.
     */
    void updateNetworkRulesLocked() {
        if (LOGV) Slog.v(TAG, "updateIfacesLocked()");

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            // ignored; service lives in system_server
            return;
        }

        // If we are in restrict power mode, we want to treat all interfaces
        // as metered, to restrict access to the network by uid.  However, we
        // will not have a bandwidth limit.  Also only do this if restrict
        // background data use is *not* enabled, since that takes precendence
        // use over those networks can have a cost associated with it).
        final boolean powerSave = mRestrictPower && !mRestrictBackground;

        // First, generate identities of all connected networks so we can
        // quickly compare them against all defined policies below.
        final ArrayList<Pair<String, NetworkIdentity>> connIdents = new ArrayList<>(states.length);
        final ArraySet<String> connIfaces = new ArraySet<String>(states.length);
        for (NetworkState state : states) {
            if (state.networkInfo.isConnected()) {
                final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);

                final String baseIface = state.linkProperties.getInterfaceName();
                if (baseIface != null) {
                    connIdents.add(Pair.create(baseIface, ident));
                    if (powerSave) {
                        connIfaces.add(baseIface);
                    }
                }

                // Stacked interfaces are considered to have same identity as
                // their parent network.
                final List<LinkProperties> stackedLinks = state.linkProperties.getStackedLinks();
                for (LinkProperties stackedLink : stackedLinks) {
                    final String stackedIface = stackedLink.getInterfaceName();
                    if (stackedIface != null) {
                        connIdents.add(Pair.create(stackedIface, ident));
                        if (powerSave) {
                            connIfaces.add(stackedIface);
                        }
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
                Slog.d(TAG, "applying policy " + policy.toString() + " to ifaces "
                        + Arrays.toString(ifaces));
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
                    removeInterfaceQuota(iface);
                    setInterfaceQuota(iface, quotaBytes);
                    newMeteredIfaces.add(iface);
                    if (powerSave) {
                        connIfaces.remove(iface);
                    }
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
            removeInterfaceQuota(iface);
            setInterfaceQuota(iface, Long.MAX_VALUE);
            newMeteredIfaces.add(iface);
        }

        mHandler.obtainMessage(MSG_ADVISE_PERSIST_THRESHOLD, lowestRule).sendToTarget();

        // remove quota on any trailing interfaces
        for (int i = mMeteredIfaces.size() - 1; i >= 0; i--) {
            final String iface = mMeteredIfaces.valueAt(i);
            if (!newMeteredIfaces.contains(iface)) {
                removeInterfaceQuota(iface);
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

        // avoid creating policy when SIM isn't ready
        if (tele.getSimState() != SIM_STATE_READY) return;

        final String subscriberId = tele.getSubscriberId();
        final NetworkIdentity probeIdent = new NetworkIdentity(
                TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, null, false);

        // examine to see if any policy is defined for active mobile
        boolean mobileDefined = false;
        for (int i = mNetworkPolicy.size()-1; i >= 0; i--) {
            if (mNetworkPolicy.valueAt(i).template.matches(probeIdent)) {
                mobileDefined = true;
                break;
            }
        }

        if (!mobileDefined) {
            Slog.i(TAG, "no policy for active mobile network; generating default policy");

            // build default mobile policy, and assume usage cycle starts today
            final long warningBytes = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_networkPolicyDefaultWarning)
                    * MB_IN_BYTES;

            final Time time = new Time();
            time.setToNow();

            final int cycleDay = time.monthDay;
            final String cycleTimezone = time.timezone;

            final NetworkTemplate template = buildTemplateMobileAll(subscriberId);
            final NetworkPolicy policy = new NetworkPolicy(template, cycleDay, cycleTimezone,
                    warningBytes, LIMIT_DISABLED, SNOOZE_NEVER, SNOOZE_NEVER, true, true);
            addNetworkPolicyLocked(policy);
        }
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
            in.setInput(fis, null);

            int type;
            int version = VERSION_INIT;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_POLICY_LIST.equals(tag)) {
                        version = readIntAttribute(in, ATTR_VERSION);
                        if (version >= VERSION_ADDED_RESTRICT_BACKGROUND) {
                            mRestrictBackground = readBooleanAttribute(
                                    in, ATTR_RESTRICT_BACKGROUND);
                        } else {
                            mRestrictBackground = false;
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

                        final NetworkTemplate template = new NetworkTemplate(
                                networkTemplate, subscriberId, networkId);
                        mNetworkPolicy.put(template, new NetworkPolicy(template, cycleDay,
                                cycleTimezone, warningBytes, limitBytes, lastWarningSnooze,
                                lastLimitSnooze, metered, inferred));

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
                        final int uid = UserHandle.getUid(UserHandle.USER_OWNER, appId);
                        if (UserHandle.isApp(uid)) {
                            setUidPolicyUncheckedLocked(uid, policy, false);
                        } else {
                            Slog.w(TAG, "unable to apply policy to UID " + uid + "; ignoring");
                        }
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
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, TAG_POLICY_LIST);
            writeIntAttribute(out, ATTR_VERSION, VERSION_LATEST);
            writeBooleanAttribute(out, ATTR_RESTRICT_BACKGROUND, mRestrictBackground);

            // write all known network policies
            for (int i = 0; i < mNetworkPolicy.size(); i++) {
                final NetworkPolicy policy = mNetworkPolicy.valueAt(i);
                final NetworkTemplate template = policy.template;

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
            final int oldPolicy = mUidPolicy.get(uid, POLICY_NONE);
            if (oldPolicy != policy) {
                setUidPolicyUncheckedLocked(uid, policy, true);
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
                setUidPolicyUncheckedLocked(uid, policy, true);
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
                setUidPolicyUncheckedLocked(uid, policy, true);
            }
        }
    }

    private void setUidPolicyUncheckedLocked(int uid, int policy, boolean persist) {
        mUidPolicy.put(uid, policy);

        // uid policy changed, recompute rules and persist policy.
        updateRulesForUidLocked(uid);
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

    @Override
    public int[] getPowerSaveAppIdWhitelist() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            int size = mPowerSaveWhitelistAppIds.size();
            int[] appids = new int[size];
            for (int i = 0; i < size; i++) {
                appids[i] = mPowerSaveWhitelistAppIds.keyAt(i);
            }
            return appids;
        }
    }

    /**
     * Remove any policies associated with given {@link UserHandle}, persisting
     * if any changes are made.
     */
    void removePoliciesForUserLocked(int userId) {
        if (LOGV) Slog.v(TAG, "removePoliciesForUserLocked()");

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
                updateRulesForUidLocked(uid);
            }
            writePolicyLocked();
        }
    }

    @Override
    public void registerListener(INetworkPolicyListener listener) {
        // TODO: create permission for observing network policy
        mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

        mListeners.register(listener);

        // TODO: consider dispatching existing rules to new listeners
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

        maybeRefreshTrustedTime();
        synchronized (mRulesLock) {
            mNetworkPolicy.clear();
            for (NetworkPolicy policy : policies) {
                mNetworkPolicy.put(policy.template, policy);
            }

            updateNetworkEnabledLocked();
            updateNetworkRulesLocked();
            updateNotificationsLocked();
            writePolicyLocked();
        }
    }

    void addNetworkPolicyLocked(NetworkPolicy policy) {
        mNetworkPolicy.put(policy.template, policy);

        updateNetworkEnabledLocked();
        updateNetworkRulesLocked();
        updateNotificationsLocked();
        writePolicyLocked();
    }

    @Override
    public NetworkPolicy[] getNetworkPolicies() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, TAG);

        synchronized (mRulesLock) {
            return mNetworkPolicy.values().toArray(new NetworkPolicy[mNetworkPolicy.size()]);
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

            updateNetworkEnabledLocked();
            updateNetworkRulesLocked();
            updateNotificationsLocked();
            writePolicyLocked();
        }
    }

    @Override
    public void setRestrictBackground(boolean restrictBackground) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        maybeRefreshTrustedTime();
        synchronized (mRulesLock) {
            mRestrictBackground = restrictBackground;
            updateRulesForGlobalChangeLocked(false);
            updateNotificationsLocked();
            writePolicyLocked();
        }

        mHandler.obtainMessage(MSG_RESTRICT_BACKGROUND_CHANGED, restrictBackground ? 1 : 0, 0)
                .sendToTarget();
    }

    @Override
    public boolean getRestrictBackground() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return mRestrictBackground;
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

                updateNetworkEnabledLocked();
                updateNetworkRulesLocked();
                updateNotificationsLocked();
                writePolicyLocked();

                fout.println("Cleared snooze timestamps");
                return;
            }

            fout.print("Restrict background: "); fout.println(mRestrictBackground);
            fout.print("Restrict power: "); fout.println(mRestrictPower);
            fout.print("Current foreground state: "); fout.println(mCurForegroundState);
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
                dumpPolicy(fout, policy);
                fout.println();
            }
            fout.decreaseIndent();

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

            final SparseBooleanArray knownUids = new SparseBooleanArray();
            collectKeys(mUidState, knownUids);
            collectKeys(mUidRules, knownUids);

            fout.println("Status for known UIDs:");
            fout.increaseIndent();
            size = knownUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = knownUids.keyAt(i);
                fout.print("UID=");
                fout.print(uid);

                int state = mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
                fout.print(" state=");
                fout.print(state);
                fout.print(state <= mCurForegroundState ? " (fg)" : " (bg)");

                fout.print(" pids=");
                final int foregroundIndex = mUidPidState.indexOfKey(uid);
                if (foregroundIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpSparseIntArray(fout, mUidPidState.valueAt(foregroundIndex));
                }

                fout.print(" rules=");
                final int rulesIndex = mUidRules.indexOfKey(uid);
                if (rulesIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpRules(fout, mUidRules.valueAt(rulesIndex));
                }

                fout.println();
            }
            fout.decreaseIndent();
        }
    }

    @Override
    public boolean isUidForeground(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return isUidForegroundLocked(uid);
        }
    }

    boolean isUidForegroundLocked(int uid) {
        // only really in foreground when screen is also on
        return mScreenOn && mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY)
                <= mCurForegroundState;
    }

    /**
     * Process state of PID changed; recompute state at UID level. If
     * changed, will trigger {@link #updateRulesForUidLocked(int)}.
     */
    void computeUidStateLocked(int uid) {
        final SparseIntArray pidState = mUidPidState.get(uid);

        // current pid is dropping foreground; examine other pids
        int uidState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        if (pidState != null) {
            final int size = pidState.size();
            for (int i = 0; i < size; i++) {
                final int state = pidState.valueAt(i);
                if (state < uidState) {
                    uidState = state;
                }
            }
        }

        final int oldUidState = mUidState.get(uid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        if (oldUidState != uidState) {
            // state changed, push updated rules
            mUidState.put(uid, uidState);
            final boolean oldForeground = oldUidState <= mCurForegroundState;
            final boolean newForeground = uidState <= mCurForegroundState;
            if (oldForeground != newForeground) {
                updateRulesForUidLocked(uid);
            }
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
            if (mUidState.valueAt(i) <= mCurForegroundState) {
                final int uid = mUidState.keyAt(i);
                updateRulesForUidLocked(uid);
            }
        }
    }

    /**
     * Update rules that might be changed by {@link #mRestrictBackground}
     * or {@link #mRestrictPower} value.
     */
    void updateRulesForGlobalChangeLocked(boolean restrictedNetworksChanged) {
        final PackageManager pm = mContext.getPackageManager();
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        // If we are in restrict power mode, we allow all important apps
        // to have data access.  Otherwise, we restrict data access to only
        // the top apps.
        mCurForegroundState = (!mRestrictBackground && mRestrictPower)
                ? ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
                : ActivityManager.PROCESS_STATE_TOP;

        // update rules for all installed applications
        final List<UserInfo> users = um.getUsers();
        final List<ApplicationInfo> apps = pm.getInstalledApplications(
                PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS);

        for (UserInfo user : users) {
            for (ApplicationInfo app : apps) {
                final int uid = UserHandle.getUid(user.id, app.uid);
                updateRulesForUidLocked(uid);
            }
        }

        // limit data usage for some internal system services
        updateRulesForUidLocked(android.os.Process.MEDIA_UID);
        updateRulesForUidLocked(android.os.Process.DRM_UID);

        // If the set of restricted networks may have changed, re-evaluate those.
        if (restrictedNetworksChanged) {
            updateNetworkRulesLocked();
        }
    }

    private static boolean isUidValidForRules(int uid) {
        // allow rules on specific system services, and any apps
        if (uid == android.os.Process.MEDIA_UID || uid == android.os.Process.DRM_UID
                || UserHandle.isApp(uid)) {
            return true;
        }

        return false;
    }

    void updateRulesForUidLocked(int uid) {
        if (!isUidValidForRules(uid)) return;

        final int uidPolicy = mUidPolicy.get(uid, POLICY_NONE);
        final boolean uidForeground = isUidForegroundLocked(uid);

        // derive active rules based on policy and active state
        int uidRules = RULE_ALLOW_ALL;
        if (!uidForeground && (uidPolicy & POLICY_REJECT_METERED_BACKGROUND) != 0) {
            // uid in background, and policy says to block metered data
            uidRules = RULE_REJECT_METERED;
        } else if (mRestrictBackground) {
            if (!uidForeground) {
                // uid in background, and global background disabled
                uidRules = RULE_REJECT_METERED;
            }
        } else if (mRestrictPower) {
            final boolean whitelisted = mPowerSaveWhitelistAppIds.get(UserHandle.getAppId(uid));
            if (!whitelisted && !uidForeground
                    && (uidPolicy & POLICY_ALLOW_BACKGROUND_BATTERY_SAVE) == 0) {
                // uid is in background, restrict power use mode is on (so we want to
                // restrict all background network access), and this uid is not on the
                // white list of those allowed background access.
                uidRules = RULE_REJECT_METERED;
            }
        }

        // TODO: only dispatch when rules actually change

        if (uidRules == RULE_ALLOW_ALL) {
            mUidRules.delete(uid);
        } else {
            mUidRules.put(uid, uidRules);
        }

        final boolean rejectMetered = (uidRules & RULE_REJECT_METERED) != 0;
        setUidNetworkRules(uid, rejectMetered);

        // dispatch changed rule to existing listeners
        mHandler.obtainMessage(MSG_RULES_CHANGED, uid, uidRules).sendToTarget();

        try {
            // adjust stats accounting based on foreground status
            mNetworkStats.setUidForeground(uid, uidForeground);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RULES_CHANGED: {
                    final int uid = msg.arg1;
                    final int uidRules = msg.arg2;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        if (listener != null) {
                            try {
                                listener.onUidRulesChanged(uid, uidRules);
                            } catch (RemoteException e) {
                            }
                        }
                    }
                    mListeners.finishBroadcast();
                    return true;
                }
                case MSG_METERED_IFACES_CHANGED: {
                    final String[] meteredIfaces = (String[]) msg.obj;
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        if (listener != null) {
                            try {
                                listener.onMeteredIfacesChanged(meteredIfaces);
                            } catch (RemoteException e) {
                            }
                        }
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
                    final int length = mListeners.beginBroadcast();
                    for (int i = 0; i < length; i++) {
                        final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
                        if (listener != null) {
                            try {
                                listener.onRestrictBackgroundChanged(restrictBackground);
                            } catch (RemoteException e) {
                            }
                        }
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

    private void setUidNetworkRules(int uid, boolean rejectOnQuotaInterfaces) {
        try {
            mNetworkManager.setUidNetworkRules(uid, rejectOnQuotaInterfaces);
        } catch (IllegalStateException e) {
            Log.wtf(TAG, "problem setting uid rules", e);
        } catch (RemoteException e) {
            // ignored; service lives in system_server
        }
    }

    /**
     * Control {@link IConnectivityManager#setPolicyDataEnable(int, boolean)}.
     */
    private void setPolicyDataEnable(int networkType, boolean enabled) {
        try {
            mConnManager.setPolicyDataEnable(networkType, enabled);
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

    private static void dumpSparseIntArray(PrintWriter fout, SparseIntArray value) {
        fout.print("[");
        final int size = value.size();
        for (int i = 0; i < size; i++) {
            fout.print(value.keyAt(i) + "=" + value.valueAt(i));
            if (i < size - 1) fout.print(",");
        }
        fout.print("]");
    }
}
