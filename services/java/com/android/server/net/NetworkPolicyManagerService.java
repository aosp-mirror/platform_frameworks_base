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
import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.READ_NETWORK_USAGE_HISTORY;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_UID_REMOVED;
import static android.content.Intent.EXTRA_UID;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkPolicyManager.EXTRA_NETWORK_TEMPLATE;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_METERED_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkPolicyManager.dumpPolicy;
import static android.net.NetworkPolicyManager.dumpRules;
import static android.net.NetworkPolicyManager.isUidValidForPolicy;
import static android.net.NetworkTemplate.MATCH_ETHERNET;
import static android.net.NetworkTemplate.MATCH_MOBILE_3G_LOWER;
import static android.net.NetworkTemplate.MATCH_MOBILE_4G;
import static android.net.NetworkTemplate.MATCH_MOBILE_ALL;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static android.net.NetworkTemplate.buildTemplateMobileAll;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.server.NetworkManagementService.LIMIT_GLOBAL_ALERT;
import static com.android.server.net.NetworkPolicyManagerService.XmlUtils.readBooleanAttribute;
import static com.android.server.net.NetworkPolicyManagerService.XmlUtils.readIntAttribute;
import static com.android.server.net.NetworkPolicyManagerService.XmlUtils.readLongAttribute;
import static com.android.server.net.NetworkPolicyManagerService.XmlUtils.writeBooleanAttribute;
import static com.android.server.net.NetworkPolicyManagerService.XmlUtils.writeIntAttribute;
import static com.android.server.net.NetworkPolicyManagerService.XmlUtils.writeLongAttribute;
import static com.android.server.net.NetworkStatsService.ACTION_NETWORK_STATS_UPDATED;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

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
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.NetworkIdentity;
import android.net.NetworkPolicy;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.INetworkManagementService;
import android.os.IPowerManager;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.format.Formatter;
import android.text.format.Time;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TrustedTime;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.os.AtomicFile;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
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
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import libcore.io.IoUtils;

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
    private static final boolean LOGD = true;
    private static final boolean LOGV = false;

    private static final int VERSION_INIT = 1;
    private static final int VERSION_ADDED_SNOOZE = 2;
    private static final int VERSION_ADDED_RESTRICT_BACKGROUND = 3;

    private static final long KB_IN_BYTES = 1024;
    private static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    private static final long GB_IN_BYTES = MB_IN_BYTES * 1024;

    // @VisibleForTesting
    public static final int TYPE_WARNING = 0x1;
    public static final int TYPE_LIMIT = 0x2;
    public static final int TYPE_LIMIT_SNOOZED = 0x3;

    private static final String TAG_POLICY_LIST = "policy-list";
    private static final String TAG_NETWORK_POLICY = "network-policy";
    private static final String TAG_UID_POLICY = "uid-policy";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_RESTRICT_BACKGROUND = "restrictBackground";
    private static final String ATTR_NETWORK_TEMPLATE = "networkTemplate";
    private static final String ATTR_SUBSCRIBER_ID = "subscriberId";
    private static final String ATTR_CYCLE_DAY = "cycleDay";
    private static final String ATTR_WARNING_BYTES = "warningBytes";
    private static final String ATTR_LIMIT_BYTES = "limitBytes";
    private static final String ATTR_LAST_SNOOZE = "lastSnooze";
    private static final String ATTR_UID = "uid";
    private static final String ATTR_POLICY = "policy";

    private static final String TAG_ALLOW_BACKGROUND = TAG + ":allowBackground";

    // @VisibleForTesting
    public static final String ACTION_ALLOW_BACKGROUND =
            "com.android.server.action.ACTION_ALLOW_BACKGROUND";

    private static final long TIME_CACHE_MAX_AGE = DAY_IN_MILLIS;

    private static final int MSG_RULES_CHANGED = 0x1;
    private static final int MSG_METERED_IFACES_CHANGED = 0x2;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final IPowerManager mPowerManager;
    private final INetworkStatsService mNetworkStats;
    private final INetworkManagementService mNetworkManager;
    private final TrustedTime mTime;

    private IConnectivityManager mConnManager;
    private INotificationManager mNotifManager;

    private final Object mRulesLock = new Object();

    private volatile boolean mScreenOn;
    private volatile boolean mRestrictBackground;

    private final boolean mSuppressDefaultPolicy;

    /** Defined network policies. */
    private HashMap<NetworkTemplate, NetworkPolicy> mNetworkPolicy = Maps.newHashMap();
    /** Currently active network rules for ifaces. */
    private HashMap<NetworkPolicy, String[]> mNetworkRules = Maps.newHashMap();

    /** Defined UID policies. */
    private SparseIntArray mUidPolicy = new SparseIntArray();
    /** Currently derived rules for each UID. */
    private SparseIntArray mUidRules = new SparseIntArray();

    /** Set of ifaces that are metered. */
    private HashSet<String> mMeteredIfaces = Sets.newHashSet();
    /** Set of over-limit templates that have been notified. */
    private HashSet<NetworkTemplate> mOverLimitNotified = Sets.newHashSet();

    /** Set of currently active {@link Notification} tags. */
    private HashSet<String> mActiveNotifs = Sets.newHashSet();
    /** Current values from {@link #setPolicyDataEnable(int, boolean)}. */
    private SparseBooleanArray mActiveNetworkEnabled = new SparseBooleanArray();

    /** Foreground at both UID and PID granularity. */
    private SparseBooleanArray mUidForeground = new SparseBooleanArray();
    private SparseArray<SparseBooleanArray> mUidPidForeground = new SparseArray<
            SparseBooleanArray>();

    private final RemoteCallbackList<INetworkPolicyListener> mListeners = new RemoteCallbackList<
            INetworkPolicyListener>();

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

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

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), mHandlerCallback);

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
        synchronized (mRulesLock) {
            // read policy from disk
            readPolicyLocked();

            if (mRestrictBackground) {
                updateRulesForRestrictBackgroundLocked();
                updateNotificationsLocked();
            }
        }

        updateScreenOn();

        try {
            mActivityManager.registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            // ouch, no foregroundActivities updates means some processes may
            // never get network access.
            Slog.e(TAG, "unable to register IProcessObserver", e);
        }

        try {
            mNetworkManager.registerObserver(mAlertObserver);
        } catch (RemoteException e) {
            // ouch, no alert updates means we fall back to
            // ACTION_NETWORK_STATS_UPDATED broadcasts.
            Slog.e(TAG, "unable to register INetworkManagementEventObserver", e);
        }

        // TODO: traverse existing processes to know foreground state, or have
        // activitymanager dispatch current state when new observer attached.

        final IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, screenFilter, null, mHandler);

        // watch for network interfaces to be claimed
        final IntentFilter connFilter = new IntentFilter(CONNECTIVITY_ACTION_IMMEDIATE);
        mContext.registerReceiver(mConnReceiver, connFilter, CONNECTIVITY_INTERNAL, mHandler);

        // listen for package/uid changes to update policy
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(ACTION_PACKAGE_ADDED);
        packageFilter.addAction(ACTION_UID_REMOVED);
        mContext.registerReceiver(mPackageReceiver, packageFilter, null, mHandler);

        // listen for stats update events
        final IntentFilter statsFilter = new IntentFilter(ACTION_NETWORK_STATS_UPDATED);
        mContext.registerReceiver(
                mStatsReceiver, statsFilter, READ_NETWORK_USAGE_HISTORY, mHandler);

        // listen for restrict background changes from notifications
        final IntentFilter allowFilter = new IntentFilter(ACTION_ALLOW_BACKGROUND);
        mContext.registerReceiver(mAllowReceiver, allowFilter, MANAGE_NETWORK_POLICY, mHandler);

    }

    private IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
            // only someone like AMS should only be calling us
            mContext.enforceCallingOrSelfPermission(MANAGE_APP_TOKENS, TAG);

            synchronized (mRulesLock) {
                // because a uid can have multiple pids running inside, we need to
                // remember all pid states and summarize foreground at uid level.

                // record foreground for this specific pid
                SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
                if (pidForeground == null) {
                    pidForeground = new SparseBooleanArray(2);
                    mUidPidForeground.put(uid, pidForeground);
                }
                pidForeground.put(pid, foregroundActivities);
                computeUidForegroundLocked(uid);
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            // only someone like AMS should only be calling us
            mContext.enforceCallingOrSelfPermission(MANAGE_APP_TOKENS, TAG);

            synchronized (mRulesLock) {
                // clear records and recompute, when they exist
                final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
                if (pidForeground != null) {
                    pidForeground.delete(pid);
                    computeUidForegroundLocked(uid);
                }
            }
        }
    };

    private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mRulesLock) {
                // screen-related broadcasts are protected by system, no need
                // for permissions check.
                updateScreenOn();
            }
        }
    };

    private BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and PACKAGE_ADDED and UID_REMOVED
            // are protected broadcasts.

            final String action = intent.getAction();
            final int uid = intent.getIntExtra(EXTRA_UID, 0);
            synchronized (mRulesLock) {
                if (ACTION_PACKAGE_ADDED.equals(action)) {
                    // update rules for UID, since it might be subject to
                    // global background data policy.
                    if (LOGV) Slog.v(TAG, "ACTION_PACKAGE_ADDED for uid=" + uid);
                    updateRulesForUidLocked(uid);

                } else if (ACTION_UID_REMOVED.equals(action)) {
                    // remove any policy and update rules to clean up.
                    if (LOGV) Slog.v(TAG, "ACTION_UID_REMOVED for uid=" + uid);
                    mUidPolicy.delete(uid);
                    updateRulesForUidLocked(uid);
                    writePolicyLocked();
                }
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
     * Observer that watches for {@link INetworkManagementService} alerts.
     */
    private INetworkManagementEventObserver mAlertObserver = new NetworkAlertObserver() {
        @Override
        public void limitReached(String limitName, String iface) {
            // only someone like NMS should be calling us
            mContext.enforceCallingOrSelfPermission(CONNECTIVITY_INTERNAL, TAG);

            synchronized (mRulesLock) {
                if (mMeteredIfaces.contains(iface) && !LIMIT_GLOBAL_ALERT.equals(limitName)) {
                    try {
                        // force stats update to make sure we have numbers that
                        // caused alert to trigger.
                        mNetworkStats.forceUpdate();
                    } catch (RemoteException e) {
                        Slog.w(TAG, "problem updating network stats");
                    }

                    updateNetworkEnabledLocked();
                    updateNotificationsLocked();
                }
            }
        }
    };

    /**
     * Check {@link NetworkPolicy} against current {@link INetworkStatsService}
     * to show visible notifications as needed.
     */
    private void updateNotificationsLocked() {
        if (LOGV) Slog.v(TAG, "updateNotificationsLocked()");

        // keep track of previously active notifications
        final HashSet<String> beforeNotifs = Sets.newHashSet();
        beforeNotifs.addAll(mActiveNotifs);
        mActiveNotifs.clear();

        // TODO: when switching to kernel notifications, compute next future
        // cycle boundary to recompute notifications.

        // examine stats for each active policy
        final long currentTime = currentTimeMillis(true);
        for (NetworkPolicy policy : mNetworkPolicy.values()) {
            // ignore policies that aren't relevant to user
            if (!isTemplateRelevant(policy.template)) continue;

            final long start = computeLastCycleBoundary(currentTime, policy);
            final long end = currentTime;

            final long totalBytes = getTotalBytes(policy.template, start, end);
            if (totalBytes == UNKNOWN_BYTES) continue;

            if (policy.limitBytes != LIMIT_DISABLED && totalBytes >= policy.limitBytes) {
                if (policy.lastSnooze >= start) {
                    enqueueNotification(policy, TYPE_LIMIT_SNOOZED, totalBytes);
                } else {
                    enqueueNotification(policy, TYPE_LIMIT, totalBytes);
                    notifyOverLimitLocked(policy.template);
                }

            } else {
                notifyUnderLimitLocked(policy.template);

                if (policy.warningBytes != WARNING_DISABLED && totalBytes >= policy.warningBytes) {
                    enqueueNotification(policy, TYPE_WARNING, totalBytes);
                }
            }
        }

        // ongoing notification when restricting background data
        if (mRestrictBackground) {
            enqueueRestrictedNotification(TAG_ALLOW_BACKGROUND);
        }

        // cancel stale notifications that we didn't renew above
        for (String tag : beforeNotifs) {
            if (!mActiveNotifs.contains(tag)) {
                cancelNotification(tag);
            }
        }
    }

    /**
     * Test if given {@link NetworkTemplate} is relevant to user based on
     * current device state, such as when {@link #getActiveSubscriberId()}
     * matches. This is regardless of data connection status.
     */
    private boolean isTemplateRelevant(NetworkTemplate template) {
        switch (template.getMatchRule()) {
            case MATCH_MOBILE_3G_LOWER:
            case MATCH_MOBILE_4G:
            case MATCH_MOBILE_ALL:
                // mobile templates are relevant when subscriberid is active
                return Objects.equal(getActiveSubscriberId(), template.getSubscriberId());
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
        builder.setOngoing(true);

        final Resources res = mContext.getResources();
        switch (type) {
            case TYPE_WARNING: {
                final CharSequence title = res.getText(R.string.data_usage_warning_title);
                final CharSequence body = res.getString(R.string.data_usage_warning_body,
                        Formatter.formatFileSize(mContext, policy.warningBytes));

                builder.setSmallIcon(R.drawable.ic_menu_info_details);
                builder.setTicker(title);
                builder.setContentTitle(title);
                builder.setContentText(body);

                final Intent intent = buildViewDataUsageIntent(policy.template);
                builder.setContentIntent(PendingIntent.getActivity(
                        mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));
                break;
            }
            case TYPE_LIMIT: {
                final CharSequence body = res.getText(R.string.data_usage_limit_body);

                final CharSequence title;
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
                        break;
                    default:
                        title = null;
                        break;
                }

                builder.setSmallIcon(com.android.internal.R.drawable.ic_menu_block);
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

                builder.setSmallIcon(R.drawable.ic_menu_info_details);
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
                    packageName, tag, 0x0, builder.getNotification(), idReceived);
            mActiveNotifs.add(tag);
        } catch (RemoteException e) {
            Slog.w(TAG, "problem during enqueueNotification: " + e);
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
        builder.setSmallIcon(R.drawable.ic_menu_info_details);
        builder.setTicker(title);
        builder.setContentTitle(title);
        builder.setContentText(body);

        final Intent intent = buildAllowBackgroundDataIntent();
        builder.setContentIntent(
                PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT));

        // TODO: move to NotificationManager once we can mock it
        try {
            final String packageName = mContext.getPackageName();
            final int[] idReceived = new int[1];
            mNotifManager.enqueueNotificationWithTag(packageName, tag,
                    0x0, builder.getNotification(), idReceived);
            mActiveNotifs.add(tag);
        } catch (RemoteException e) {
            Slog.w(TAG, "problem during enqueueNotification: " + e);
        }
    }

    private void cancelNotification(String tag) {
        // TODO: move to NotificationManager once we can mock it
        try {
            final String packageName = mContext.getPackageName();
            mNotifManager.cancelNotificationWithTag(
                    packageName, tag, 0x0);
        } catch (RemoteException e) {
            Slog.w(TAG, "problem during enqueueNotification: " + e);
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
    private void updateNetworkEnabledLocked() {
        if (LOGV) Slog.v(TAG, "updateNetworkEnabledLocked()");

        // TODO: reset any policy-disabled networks when any policy is removed
        // completely, which is currently rare case.

        final long currentTime = currentTimeMillis(true);
        for (NetworkPolicy policy : mNetworkPolicy.values()) {
            // shortcut when policy has no limit
            if (policy.limitBytes == LIMIT_DISABLED) {
                setNetworkTemplateEnabled(policy.template, true);
                continue;
            }

            final long start = computeLastCycleBoundary(currentTime, policy);
            final long end = currentTime;

            final long totalBytes = getTotalBytes(policy.template, start, end);
            if (totalBytes == UNKNOWN_BYTES) continue;

            // disable data connection when over limit and not snoozed
            final boolean overLimit = policy.limitBytes != LIMIT_DISABLED
                    && totalBytes > policy.limitBytes && policy.lastSnooze < start;
            final boolean enabled = !overLimit;

            setNetworkTemplateEnabled(policy.template, enabled);
        }
    }

    /**
     * Control {@link IConnectivityManager#setPolicyDataEnable(int, boolean)}
     * for the given {@link NetworkTemplate}.
     */
    private void setNetworkTemplateEnabled(NetworkTemplate template, boolean enabled) {
        switch (template.getMatchRule()) {
            case MATCH_MOBILE_3G_LOWER:
            case MATCH_MOBILE_4G:
            case MATCH_MOBILE_ALL:
                // TODO: offer more granular control over radio states once
                // 4965893 is available.
                if (Objects.equal(getActiveSubscriberId(), template.getSubscriberId())) {
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
    private void updateNetworkRulesLocked() {
        if (LOGV) Slog.v(TAG, "updateIfacesLocked()");

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network state");
            return;
        }

        // first, derive identity for all connected networks, which can be used
        // to match against templates.
        final HashMap<NetworkIdentity, String> networks = Maps.newHashMap();
        for (NetworkState state : states) {
            // stash identity and iface away for later use
            if (state.networkInfo.isConnected()) {
                final String iface = state.linkProperties.getInterfaceName();
                final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);
                networks.put(ident, iface);
            }
        }

        // build list of rules and ifaces to enforce them against
        mNetworkRules.clear();
        final ArrayList<String> ifaceList = Lists.newArrayList();
        for (NetworkPolicy policy : mNetworkPolicy.values()) {

            // collect all active ifaces that match this template
            ifaceList.clear();
            for (Map.Entry<NetworkIdentity, String> entry : networks.entrySet()) {
                final NetworkIdentity ident = entry.getKey();
                if (policy.template.matches(ident)) {
                    final String iface = entry.getValue();
                    ifaceList.add(iface);
                }
            }

            if (ifaceList.size() > 0) {
                final String[] ifaces = ifaceList.toArray(new String[ifaceList.size()]);
                mNetworkRules.put(policy, ifaces);
            }
        }

        final HashSet<String> newMeteredIfaces = Sets.newHashSet();

        // apply each policy that we found ifaces for; compute remaining data
        // based on current cycle and historical stats, and push to kernel.
        final long currentTime = currentTimeMillis(true);
        for (NetworkPolicy policy : mNetworkRules.keySet()) {
            final String[] ifaces = mNetworkRules.get(policy);

            final long start = computeLastCycleBoundary(currentTime, policy);
            final long end = currentTime;

            final long totalBytes = getTotalBytes(policy.template, start, end);
            if (totalBytes == UNKNOWN_BYTES) continue;

            if (LOGD) {
                Slog.d(TAG, "applying policy " + policy.toString() + " to ifaces "
                        + Arrays.toString(ifaces));
            }

            final boolean hasLimit = policy.limitBytes != LIMIT_DISABLED;
            if (hasLimit) {
                final long quotaBytes;
                if (policy.lastSnooze >= start) {
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
                }
            }
        }

        // remove quota on any trailing interfaces
        for (String iface : mMeteredIfaces) {
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

        final String subscriberId = getActiveSubscriberId();
        final NetworkIdentity probeIdent = new NetworkIdentity(
                TYPE_MOBILE, TelephonyManager.NETWORK_TYPE_UNKNOWN, subscriberId, false);

        // examine to see if any policy is defined for active mobile
        boolean mobileDefined = false;
        for (NetworkPolicy policy : mNetworkPolicy.values()) {
            if (policy.template.matches(probeIdent)) {
                mobileDefined = true;
            }
        }

        if (!mobileDefined) {
            Slog.i(TAG, "no policy for active mobile network; generating default policy");

            // build default mobile policy, and assume usage cycle starts today
            final long warningBytes = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_networkPolicyDefaultWarning)
                    * MB_IN_BYTES;

            final Time time = new Time(Time.TIMEZONE_UTC);
            time.setToNow();
            final int cycleDay = time.monthDay;

            final NetworkTemplate template = buildTemplateMobileAll(subscriberId);
            mNetworkPolicy.put(template, new NetworkPolicy(
                    template, cycleDay, warningBytes, LIMIT_DISABLED, SNOOZE_NEVER));
            writePolicyLocked();
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
                        final int cycleDay = readIntAttribute(in, ATTR_CYCLE_DAY);
                        final long warningBytes = readLongAttribute(in, ATTR_WARNING_BYTES);
                        final long limitBytes = readLongAttribute(in, ATTR_LIMIT_BYTES);
                        final long lastSnooze;
                        if (version >= VERSION_ADDED_SNOOZE) {
                            lastSnooze = readLongAttribute(in, ATTR_LAST_SNOOZE);
                        } else {
                            lastSnooze = SNOOZE_NEVER;
                        }

                        final NetworkTemplate template = new NetworkTemplate(
                                networkTemplate, subscriberId);
                        mNetworkPolicy.put(template, new NetworkPolicy(
                                template, cycleDay, warningBytes, limitBytes, lastSnooze));

                    } else if (TAG_UID_POLICY.equals(tag)) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        final int policy = readIntAttribute(in, ATTR_POLICY);

                        if (isUidValidForPolicy(mContext, uid)) {
                            setUidPolicyUnchecked(uid, policy, false);
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
            Slog.e(TAG, "problem reading network stats", e);
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "problem reading network stats", e);
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
            mContext.sendBroadcast(broadcast);
        }
    }

    private void writePolicyLocked() {
        if (LOGV) Slog.v(TAG, "writePolicyLocked()");

        FileOutputStream fos = null;
        try {
            fos = mPolicyFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, TAG_POLICY_LIST);
            writeIntAttribute(out, ATTR_VERSION, VERSION_ADDED_RESTRICT_BACKGROUND);
            writeBooleanAttribute(out, ATTR_RESTRICT_BACKGROUND, mRestrictBackground);

            // write all known network policies
            for (NetworkPolicy policy : mNetworkPolicy.values()) {
                final NetworkTemplate template = policy.template;

                out.startTag(null, TAG_NETWORK_POLICY);
                writeIntAttribute(out, ATTR_NETWORK_TEMPLATE, template.getMatchRule());
                final String subscriberId = template.getSubscriberId();
                if (subscriberId != null) {
                    out.attribute(null, ATTR_SUBSCRIBER_ID, subscriberId);
                }
                writeIntAttribute(out, ATTR_CYCLE_DAY, policy.cycleDay);
                writeLongAttribute(out, ATTR_WARNING_BYTES, policy.warningBytes);
                writeLongAttribute(out, ATTR_LIMIT_BYTES, policy.limitBytes);
                writeLongAttribute(out, ATTR_LAST_SNOOZE, policy.lastSnooze);
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

        if (!isUidValidForPolicy(mContext, uid)) {
            throw new IllegalArgumentException("cannot apply policy to UID " + uid);
        }

        setUidPolicyUnchecked(uid, policy, true);
    }

    private void setUidPolicyUnchecked(int uid, int policy, boolean persist) {
        final int oldPolicy;
        synchronized (mRulesLock) {
            oldPolicy = getUidPolicy(uid);
            mUidPolicy.put(uid, policy);

            // uid policy changed, recompute rules and persist policy.
            updateRulesForUidLocked(uid);
            if (persist) {
                writePolicyLocked();
            }
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

    @Override
    public NetworkPolicy[] getNetworkPolicies() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, TAG);

        synchronized (mRulesLock) {
            return mNetworkPolicy.values().toArray(new NetworkPolicy[mNetworkPolicy.size()]);
        }
    }

    @Override
    public void snoozePolicy(NetworkTemplate template) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        final long currentTime = currentTimeMillis(true);
        synchronized (mRulesLock) {
            // find and snooze local policy that matches
            final NetworkPolicy policy = mNetworkPolicy.get(template);
            if (policy == null) {
                throw new IllegalArgumentException("unable to find policy for " + template);
            }

            policy.lastSnooze = currentTime;

            updateNetworkEnabledLocked();
            updateNetworkRulesLocked();
            updateNotificationsLocked();
            writePolicyLocked();
        }
    }

    @Override
    public void setRestrictBackground(boolean restrictBackground) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            mRestrictBackground = restrictBackground;
            updateRulesForRestrictBackgroundLocked();
            updateNotificationsLocked();
            writePolicyLocked();
        }
    }

    @Override
    public boolean getRestrictBackground() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return mRestrictBackground;
        }
    }

    private NetworkPolicy findPolicyForNetworkLocked(NetworkIdentity ident) {
        for (NetworkPolicy policy : mNetworkPolicy.values()) {
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

        if (policy == null) {
            // missing policy means we can't derive useful quota info
            return null;
        }

        final long currentTime = currentTimeMillis(false);

        final long start = computeLastCycleBoundary(currentTime, policy);
        final long end = currentTime;

        // find total bytes used under policy
        final long totalBytes = getTotalBytes(policy.template, start, end);
        if (totalBytes == UNKNOWN_BYTES) return null;

        // report soft and hard limits under policy
        final long softLimitBytes = policy.warningBytes != WARNING_DISABLED ? policy.warningBytes
                : NetworkQuotaInfo.NO_LIMIT;
        final long hardLimitBytes = policy.limitBytes != LIMIT_DISABLED ? policy.limitBytes
                : NetworkQuotaInfo.NO_LIMIT;

        return new NetworkQuotaInfo(totalBytes, softLimitBytes, hardLimitBytes);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        final HashSet<String> argSet = new HashSet<String>();
        for (String arg : args) {
            argSet.add(arg);
        }

        synchronized (mRulesLock) {
            if (argSet.contains("unsnooze")) {
                for (NetworkPolicy policy : mNetworkPolicy.values()) {
                    policy.lastSnooze = SNOOZE_NEVER;
                }
                writePolicyLocked();
                fout.println("Wiped snooze timestamps");
                return;
            }

            fout.print("Restrict background: "); fout.println(mRestrictBackground);
            fout.println("Network policies:");
            for (NetworkPolicy policy : mNetworkPolicy.values()) {
                fout.print("  "); fout.println(policy.toString());
            }

            fout.println("Policy status for known UIDs:");

            final SparseBooleanArray knownUids = new SparseBooleanArray();
            collectKeys(mUidPolicy, knownUids);
            collectKeys(mUidForeground, knownUids);
            collectKeys(mUidRules, knownUids);

            final int size = knownUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = knownUids.keyAt(i);
                fout.print("  UID=");
                fout.print(uid);

                fout.print(" policy=");
                final int policyIndex = mUidPolicy.indexOfKey(uid);
                if (policyIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpPolicy(fout, mUidPolicy.valueAt(policyIndex));
                }

                fout.print(" foreground=");
                final int foregroundIndex = mUidPidForeground.indexOfKey(uid);
                if (foregroundIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpSparseBooleanArray(fout, mUidPidForeground.valueAt(foregroundIndex));
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
        }
    }

    @Override
    public boolean isUidForeground(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            // only really in foreground when screen is also on
            return mUidForeground.get(uid, false) && mScreenOn;
        }
    }

    /**
     * Foreground for PID changed; recompute foreground at UID level. If
     * changed, will trigger {@link #updateRulesForUidLocked(int)}.
     */
    private void computeUidForegroundLocked(int uid) {
        final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);

        // current pid is dropping foreground; examine other pids
        boolean uidForeground = false;
        final int size = pidForeground.size();
        for (int i = 0; i < size; i++) {
            if (pidForeground.valueAt(i)) {
                uidForeground = true;
                break;
            }
        }

        final boolean oldUidForeground = mUidForeground.get(uid, false);
        if (oldUidForeground != uidForeground) {
            // foreground changed, push updated rules
            mUidForeground.put(uid, uidForeground);
            updateRulesForUidLocked(uid);
        }
    }

    private void updateScreenOn() {
        synchronized (mRulesLock) {
            try {
                mScreenOn = mPowerManager.isScreenOn();
            } catch (RemoteException e) {
            }
            updateRulesForScreenLocked();
        }
    }

    /**
     * Update rules that might be changed by {@link #mScreenOn} value.
     */
    private void updateRulesForScreenLocked() {
        // only update rules for anyone with foreground activities
        final int size = mUidForeground.size();
        for (int i = 0; i < size; i++) {
            if (mUidForeground.valueAt(i)) {
                final int uid = mUidForeground.keyAt(i);
                updateRulesForUidLocked(uid);
            }
        }
    }

    /**
     * Update rules that might be changed by {@link #mRestrictBackground} value.
     */
    private void updateRulesForRestrictBackgroundLocked() {
        // update rules for all installed applications
        final PackageManager pm = mContext.getPackageManager();
        final List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo app : apps) {
            updateRulesForUidLocked(app.uid);
        }

        // and catch system UIDs
        // TODO: keep in sync with android_filesystem_config.h
        for (int uid = 1000; uid <= 1025; uid++) {
            updateRulesForUidLocked(uid);
        }
        for (int uid = 2000; uid <= 2002; uid++) {
            updateRulesForUidLocked(uid);
        }
        for (int uid = 3000; uid <= 3007; uid++) {
            updateRulesForUidLocked(uid);
        }
        for (int uid = 9998; uid <= 9999; uid++) {
            updateRulesForUidLocked(uid);
        }
    }

    private void updateRulesForUidLocked(int uid) {
        final int uidPolicy = getUidPolicy(uid);
        final boolean uidForeground = isUidForeground(uid);

        // derive active rules based on policy and active state
        int uidRules = RULE_ALLOW_ALL;
        if (!uidForeground && (uidPolicy & POLICY_REJECT_METERED_BACKGROUND) != 0) {
            // uid in background, and policy says to block metered data
            uidRules = RULE_REJECT_METERED;
        }
        if (!uidForeground && mRestrictBackground) {
            // uid in background, and global background disabled
            uidRules = RULE_REJECT_METERED;
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
            Slog.w(TAG, "problem dispatching foreground change");
        }
    }

    private Handler.Callback mHandlerCallback = new Handler.Callback() {
        /** {@inheritDoc} */
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
            Slog.e(TAG, "problem setting interface quota", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "problem setting interface quota", e);
        }
    }

    private void removeInterfaceQuota(String iface) {
        try {
            mNetworkManager.removeInterfaceQuota(iface);
        } catch (IllegalStateException e) {
            Slog.e(TAG, "problem removing interface quota", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "problem removing interface quota", e);
        }
    }

    private void setInterfaceAlert(String iface, long alertBytes) {
        try {
            mNetworkManager.setInterfaceAlert(iface, alertBytes);
        } catch (IllegalStateException e) {
            Slog.e(TAG, "problem setting interface alert", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "problem setting interface alert", e);
        }
    }

    private void removeInterfaceAlert(String iface) {
        try {
            mNetworkManager.removeInterfaceAlert(iface);
        } catch (IllegalStateException e) {
            Slog.e(TAG, "problem removing interface alert", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "problem removing interface alert", e);
        }
    }

    private void setUidNetworkRules(int uid, boolean rejectOnQuotaInterfaces) {
        try {
            mNetworkManager.setUidNetworkRules(uid, rejectOnQuotaInterfaces);
        } catch (IllegalStateException e) {
            Slog.e(TAG, "problem setting uid rules", e);
        } catch (RemoteException e) {
            Slog.e(TAG, "problem setting uid rules", e);
        }
    }

    /**
     * Control {@link IConnectivityManager#setPolicyDataEnable(int, boolean)},
     * dispatching only when actually changed.
     */
    private void setPolicyDataEnable(int networkType, boolean enabled) {
        synchronized (mActiveNetworkEnabled) {
            final boolean prevEnabled = mActiveNetworkEnabled.get(networkType, true);
            if (prevEnabled == enabled) return;

            try {
                mConnManager.setPolicyDataEnable(networkType, enabled);
            } catch (RemoteException e) {
                Slog.e(TAG, "problem setting network enabled", e);
            }

            mActiveNetworkEnabled.put(networkType, enabled);
        }
    }

    private String getActiveSubscriberId() {
        final TelephonyManager telephony = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephony.getSubscriberId();
    }

    private static final long UNKNOWN_BYTES = -1;

    private long getTotalBytes(NetworkTemplate template, long start, long end) {
        try {
            final NetworkStats stats = mNetworkStats.getSummaryForNetwork(
                    template, start, end);
            final NetworkStats.Entry entry = stats.getValues(0, null);
            return entry.rxBytes + entry.txBytes;
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading summary for template " + template);
            return UNKNOWN_BYTES;
        }
    }

    private long currentTimeMillis(boolean allowRefresh) {
        // try refreshing time source when stale
        if (mTime.getCacheAge() > TIME_CACHE_MAX_AGE && allowRefresh) {
            mTime.forceRefresh();
        }

        return mTime.hasCache() ? mTime.currentTimeMillis() : System.currentTimeMillis();
    }

    private static Intent buildAllowBackgroundDataIntent() {
        return new Intent(ACTION_ALLOW_BACKGROUND);
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

    private static void collectKeys(SparseIntArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void collectKeys(SparseBooleanArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void dumpSparseBooleanArray(PrintWriter fout, SparseBooleanArray value) {
        fout.print("[");
        final int size = value.size();
        for (int i = 0; i < size; i++) {
            fout.print(value.keyAt(i) + "=" + value.valueAt(i));
            if (i < size - 1) fout.print(",");
        }
        fout.print("]");
    }

    public static class XmlUtils {
        public static int readIntAttribute(XmlPullParser in, String name) throws IOException {
            final String value = in.getAttributeValue(null, name);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new ProtocolException("problem parsing " + name + "=" + value + " as int");
            }
        }

        public static void writeIntAttribute(XmlSerializer out, String name, int value)
                throws IOException {
            out.attribute(null, name, Integer.toString(value));
        }

        public static long readLongAttribute(XmlPullParser in, String name) throws IOException {
            final String value = in.getAttributeValue(null, name);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ProtocolException("problem parsing " + name + "=" + value + " as long");
            }
        }

        public static void writeLongAttribute(XmlSerializer out, String name, long value)
                throws IOException {
            out.attribute(null, name, Long.toString(value));
        }

        public static boolean readBooleanAttribute(XmlPullParser in, String name) {
            final String value = in.getAttributeValue(null, name);
            return Boolean.parseBoolean(value);
        }

        public static void writeBooleanAttribute(XmlSerializer out, String name, boolean value)
                throws IOException {
            out.attribute(null, name, Boolean.toString(value));
        }
    }
}
