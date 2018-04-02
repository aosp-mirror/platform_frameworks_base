/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.net.ConnectivityManager.MULTIPATH_PREFERENCE_HANDOVER;
import static android.net.ConnectivityManager.MULTIPATH_PREFERENCE_RELIABILITY;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.provider.Settings.Global.NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES;

import static com.android.server.net.NetworkPolicyManagerInternal.QUOTA_TYPE_MULTIPATH;
import static com.android.server.net.NetworkPolicyManagerService.OPPORTUNISTIC_QUOTA_UNKNOWN;

import android.app.usage.NetworkStatsManager;
import android.app.usage.NetworkStatsManager.UsageCallback;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkIdentity;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.net.StringNetworkSpecifier;
import android.os.BestClock;
import android.os.Handler;
import android.os.SystemClock;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.DebugUtils;
import android.util.Pair;
import android.util.Range;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkStatsManagerInternal;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages multipath data budgets.
 *
 * Informs the return value of ConnectivityManager#getMultipathPreference() based on:
 * - The user's data plan, as returned by getSubscriptionOpportunisticQuota().
 * - The amount of data usage that occurs on mobile networks while they are not the system default
 *   network (i.e., when the app explicitly selected such networks).
 *
 * Currently, quota is determined on a daily basis, from midnight to midnight local time.
 *
 * @hide
 */
public class MultipathPolicyTracker {
    private static String TAG = MultipathPolicyTracker.class.getSimpleName();

    private static final boolean DBG = false;

    private final Context mContext;
    private final Handler mHandler;
    private final Clock mClock;
    private final Dependencies mDeps;
    private final ContentResolver mResolver;
    private final ConfigChangeReceiver mConfigChangeReceiver;

    @VisibleForTesting
    final ContentObserver mSettingsObserver;

    private ConnectivityManager mCM;
    private NetworkPolicyManager mNPM;
    private NetworkStatsManager mStatsManager;

    private NetworkCallback mMobileNetworkCallback;
    private NetworkPolicyManager.Listener mPolicyListener;


    /**
     * Divider to calculate opportunistic quota from user-set data limit or warning: 5% of user-set
     * limit.
     */
    private static final int OPQUOTA_USER_SETTING_DIVIDER = 20;

    public static class Dependencies {
        public Clock getClock() {
            return new BestClock(ZoneOffset.UTC, SystemClock.currentNetworkTimeClock(),
                    Clock.systemUTC());
        }
    }

    public MultipathPolicyTracker(Context ctx, Handler handler) {
        this(ctx, handler, new Dependencies());
    }

    public MultipathPolicyTracker(Context ctx, Handler handler, Dependencies deps) {
        mContext = ctx;
        mHandler = handler;
        mClock = deps.getClock();
        mDeps = deps;
        mResolver = mContext.getContentResolver();
        mSettingsObserver = new SettingsObserver(mHandler);
        mConfigChangeReceiver = new ConfigChangeReceiver();
        // Because we are initialized by the ConnectivityService constructor, we can't touch any
        // connectivity APIs. Service initialization is done in start().
    }

    public void start() {
        mCM = mContext.getSystemService(ConnectivityManager.class);
        mNPM = mContext.getSystemService(NetworkPolicyManager.class);
        mStatsManager = mContext.getSystemService(NetworkStatsManager.class);

        registerTrackMobileCallback();
        registerNetworkPolicyListener();
        final Uri defaultSettingUri =
                Settings.Global.getUriFor(NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES);
        mResolver.registerContentObserver(defaultSettingUri, false, mSettingsObserver);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        mContext.registerReceiverAsUser(
                mConfigChangeReceiver, UserHandle.ALL, intentFilter, null, mHandler);
    }

    public void shutdown() {
        maybeUnregisterTrackMobileCallback();
        unregisterNetworkPolicyListener();
        for (MultipathTracker t : mMultipathTrackers.values()) {
            t.shutdown();
        }
        mMultipathTrackers.clear();
        mResolver.unregisterContentObserver(mSettingsObserver);
        mContext.unregisterReceiver(mConfigChangeReceiver);
    }

    // Called on an arbitrary binder thread.
    public Integer getMultipathPreference(Network network) {
        if (network == null) {
            return null;
        }
        MultipathTracker t = mMultipathTrackers.get(network);
        if (t != null) {
            return t.getMultipathPreference();
        }
        return null;
    }

    // Track information on mobile networks as they come and go.
    class MultipathTracker {
        final Network network;
        final int subId;
        final String subscriberId;

        private long mQuota;
        /** Current multipath budget. Nonzero iff we have budget and a UsageCallback is armed. */
        private long mMultipathBudget;
        private final NetworkTemplate mNetworkTemplate;
        private final UsageCallback mUsageCallback;
        private NetworkCapabilities mNetworkCapabilities;

        public MultipathTracker(Network network, NetworkCapabilities nc) {
            this.network = network;
            this.mNetworkCapabilities = new NetworkCapabilities(nc);
            try {
                subId = Integer.parseInt(
                        ((StringNetworkSpecifier) nc.getNetworkSpecifier()).toString());
            } catch (ClassCastException | NullPointerException | NumberFormatException e) {
                throw new IllegalStateException(String.format(
                        "Can't get subId from mobile network %s (%s): %s",
                        network, nc, e.getMessage()));
            }

            TelephonyManager tele = mContext.getSystemService(TelephonyManager.class);
            if (tele == null) {
                throw new IllegalStateException(String.format("Missing TelephonyManager"));
            }
            tele = tele.createForSubscriptionId(subId);
            if (tele == null) {
                throw new IllegalStateException(String.format(
                        "Can't get TelephonyManager for subId %d", subId));
            }

            subscriberId = tele.getSubscriberId();
            mNetworkTemplate = new NetworkTemplate(
                    NetworkTemplate.MATCH_MOBILE, subscriberId, new String[] { subscriberId },
                    null, NetworkStats.METERED_ALL, NetworkStats.ROAMING_ALL,
                    NetworkStats.DEFAULT_NETWORK_NO);
            mUsageCallback = new UsageCallback() {
                @Override
                public void onThresholdReached(int networkType, String subscriberId) {
                    if (DBG) Slog.d(TAG, "onThresholdReached for network " + network);
                    mMultipathBudget = 0;
                    updateMultipathBudget();
                }
            };

            updateMultipathBudget();
        }

        public void setNetworkCapabilities(NetworkCapabilities nc) {
            mNetworkCapabilities = new NetworkCapabilities(nc);
        }

        // TODO: calculate with proper timezone information
        private long getDailyNonDefaultDataUsage() {
            final ZonedDateTime end =
                    ZonedDateTime.ofInstant(mClock.instant(), ZoneId.systemDefault());
            final ZonedDateTime start = end.truncatedTo(ChronoUnit.DAYS);

            final long bytes = getNetworkTotalBytes(
                    start.toInstant().toEpochMilli(),
                    end.toInstant().toEpochMilli());
            if (DBG) Slog.d(TAG, "Non-default data usage: " + bytes);
            return bytes;
        }

        private long getNetworkTotalBytes(long start, long end) {
            try {
                return LocalServices.getService(NetworkStatsManagerInternal.class)
                        .getNetworkTotalBytes(mNetworkTemplate, start, end);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to get data usage: " + e);
                return -1;
            }
        }

        private NetworkIdentity getTemplateMatchingNetworkIdentity(NetworkCapabilities nc) {
            return new NetworkIdentity(
                    ConnectivityManager.TYPE_MOBILE,
                    0 /* subType, unused for template matching */,
                    subscriberId,
                    null /* networkId, unused for matching mobile networks */,
                    !nc.hasCapability(NET_CAPABILITY_NOT_ROAMING),
                    !nc.hasCapability(NET_CAPABILITY_NOT_METERED),
                    false /* defaultNetwork, templates should have DEFAULT_NETWORK_ALL */);
        }

        private long getRemainingDailyBudget(long limitBytes,
                Range<ZonedDateTime> cycle) {
            final long start = cycle.getLower().toInstant().toEpochMilli();
            final long end = cycle.getUpper().toInstant().toEpochMilli();
            final long totalBytes = getNetworkTotalBytes(start, end);
            final long remainingBytes = totalBytes == -1 ? 0 : Math.max(0, limitBytes - totalBytes);
            // 1 + ((end - now - 1) / millisInDay with integers is equivalent to:
            // ceil((double)(end - now) / millisInDay)
            final long remainingDays =
                    1 + ((end - mClock.millis() - 1) / TimeUnit.DAYS.toMillis(1));

            return remainingBytes / Math.max(1, remainingDays);
        }

        private long getUserPolicyOpportunisticQuotaBytes() {
            // Keep the most restrictive applicable policy
            long minQuota = Long.MAX_VALUE;
            final NetworkIdentity identity = getTemplateMatchingNetworkIdentity(
                    mNetworkCapabilities);

            final NetworkPolicy[] policies = mNPM.getNetworkPolicies();
            for (NetworkPolicy policy : policies) {
                if (policy.hasCycle() && policy.template.matches(identity)) {
                    final long cycleStart = policy.cycleIterator().next().getLower()
                            .toInstant().toEpochMilli();
                    // Prefer user-defined warning, otherwise use hard limit
                    final long activeWarning = getActiveWarning(policy, cycleStart);
                    final long policyBytes = (activeWarning == WARNING_DISABLED)
                            ? getActiveLimit(policy, cycleStart)
                            : activeWarning;

                    if (policyBytes != LIMIT_DISABLED && policyBytes != WARNING_DISABLED) {
                        final long policyBudget = getRemainingDailyBudget(policyBytes,
                                policy.cycleIterator().next());
                        minQuota = Math.min(minQuota, policyBudget);
                    }
                }
            }

            if (minQuota == Long.MAX_VALUE) {
                return OPPORTUNISTIC_QUOTA_UNKNOWN;
            }

            return minQuota / OPQUOTA_USER_SETTING_DIVIDER;
        }

        void updateMultipathBudget() {
            long quota = LocalServices.getService(NetworkPolicyManagerInternal.class)
                    .getSubscriptionOpportunisticQuota(this.network, QUOTA_TYPE_MULTIPATH);
            if (DBG) Slog.d(TAG, "Opportunistic quota from data plan: " + quota + " bytes");

            // Fallback to user settings-based quota if not available from phone plan
            if (quota == OPPORTUNISTIC_QUOTA_UNKNOWN) {
                quota = getUserPolicyOpportunisticQuotaBytes();
                if (DBG) Slog.d(TAG, "Opportunistic quota from user policy: " + quota + " bytes");
            }

            if (quota == OPPORTUNISTIC_QUOTA_UNKNOWN) {
                quota = getDefaultDailyMultipathQuotaBytes();
                if (DBG) Slog.d(TAG, "Setting quota: " + quota + " bytes");
            }

            if (haveMultipathBudget() && quota == mQuota) {
                // If we already have a usage callback pending , there's no need to re-register it
                // if the quota hasn't changed. The callback will simply fire as expected when the
                // budget is spent. Also: if we re-register the callback when we're below the
                // UsageCallback's minimum value of 2MB, we'll overshoot the budget.
                if (DBG) Slog.d(TAG, "Quota still " + quota + ", not updating.");
                return;
            }
            mQuota = quota;

            // If we can't get current usage, assume the worst and don't give
            // ourselves any budget to work with.
            final long usage = getDailyNonDefaultDataUsage();
            final long budget = (usage == -1) ? 0 : Math.max(0, quota - usage);
            if (budget > 0) {
                if (DBG) Slog.d(TAG, "Setting callback for " + budget +
                        " bytes on network " + network);
                registerUsageCallback(budget);
            } else {
                maybeUnregisterUsageCallback();
            }
        }

        public int getMultipathPreference() {
            if (haveMultipathBudget()) {
                return MULTIPATH_PREFERENCE_HANDOVER | MULTIPATH_PREFERENCE_RELIABILITY;
            }
            return 0;
        }

        // For debugging only.
        public long getQuota() {
            return mQuota;
        }

        // For debugging only.
        public long getMultipathBudget() {
            return mMultipathBudget;
        }

        private boolean haveMultipathBudget() {
            return mMultipathBudget > 0;
        }

        private void registerUsageCallback(long budget) {
            maybeUnregisterUsageCallback();
            mStatsManager.registerUsageCallback(mNetworkTemplate, TYPE_MOBILE, budget,
                    mUsageCallback, mHandler);
            mMultipathBudget = budget;
        }

        private void maybeUnregisterUsageCallback() {
            if (haveMultipathBudget()) {
                if (DBG) Slog.d(TAG, "Unregistering callback, budget was " + mMultipathBudget);
                mStatsManager.unregisterUsageCallback(mUsageCallback);
                mMultipathBudget = 0;
            }
        }

        void shutdown() {
            maybeUnregisterUsageCallback();
        }
    }

    private static long getActiveWarning(NetworkPolicy policy, long cycleStart) {
        return policy.lastWarningSnooze < cycleStart
                ? policy.warningBytes
                : WARNING_DISABLED;
    }

    private static long getActiveLimit(NetworkPolicy policy, long cycleStart) {
        return policy.lastLimitSnooze < cycleStart
                ? policy.limitBytes
                : LIMIT_DISABLED;
    }

    // Only ever updated on the handler thread. Accessed from other binder threads to retrieve
    // the tracker for a specific network.
    private final ConcurrentHashMap <Network, MultipathTracker> mMultipathTrackers =
            new ConcurrentHashMap<>();

    private long getDefaultDailyMultipathQuotaBytes() {
        final String setting = Settings.Global.getString(mContext.getContentResolver(),
                NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES);
        if (setting != null) {
            try {
                return Long.parseLong(setting);
            } catch(NumberFormatException e) {
                // fall through
            }
        }

        return mContext.getResources().getInteger(
                R.integer.config_networkDefaultDailyMultipathQuotaBytes);
    }

    // TODO: this races with app code that might respond to onAvailable() by immediately calling
    // getMultipathPreference. Fix this by adding to ConnectivityService the ability to directly
    // invoke NetworkCallbacks on tightly-coupled classes such as this one which run on its
    // handler thread.
    private void registerTrackMobileCallback() {
        final NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NET_CAPABILITY_INTERNET)
                .addTransportType(TRANSPORT_CELLULAR)
                .build();
        mMobileNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                MultipathTracker existing = mMultipathTrackers.get(network);
                if (existing != null) {
                    existing.setNetworkCapabilities(nc);
                    existing.updateMultipathBudget();
                    return;
                }

                try {
                    mMultipathTrackers.put(network, new MultipathTracker(network, nc));
                } catch (IllegalStateException e) {
                    Slog.e(TAG, "Can't track mobile network " + network + ": " + e.getMessage());
                }
                if (DBG) Slog.d(TAG, "Tracking mobile network " + network);
            }

            @Override
            public void onLost(Network network) {
                MultipathTracker existing = mMultipathTrackers.get(network);
                if (existing != null) {
                    existing.shutdown();
                    mMultipathTrackers.remove(network);
                }
                if (DBG) Slog.d(TAG, "No longer tracking mobile network " + network);
            }
        };

        mCM.registerNetworkCallback(request, mMobileNetworkCallback, mHandler);
    }

    /**
     * Update multipath budgets for all trackers. To be called on the mHandler thread.
     */
    private void updateAllMultipathBudgets() {
        for (MultipathTracker t : mMultipathTrackers.values()) {
            t.updateMultipathBudget();
        }
    }

    private void maybeUnregisterTrackMobileCallback() {
        if (mMobileNetworkCallback != null) {
            mCM.unregisterNetworkCallback(mMobileNetworkCallback);
        }
        mMobileNetworkCallback = null;
    }

    private void registerNetworkPolicyListener() {
        mPolicyListener = new NetworkPolicyManager.Listener() {
            @Override
            public void onMeteredIfacesChanged(String[] meteredIfaces) {
                // Dispatched every time opportunistic quota is recalculated.
                mHandler.post(() -> updateAllMultipathBudgets());
            }
        };
        mNPM.registerListener(mPolicyListener);
    }

    private void unregisterNetworkPolicyListener() {
        mNPM.unregisterListener(mPolicyListener);
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            Slog.wtf(TAG, "Should never be reached.");
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!Settings.Global.getUriFor(NETWORK_DEFAULT_DAILY_MULTIPATH_QUOTA_BYTES)
                    .equals(uri)) {
                Slog.wtf(TAG, "Unexpected settings observation: " + uri);
            }
            if (DBG) Slog.d(TAG, "Settings change: updating budgets.");
            updateAllMultipathBudgets();
        }
    }

    private final class ConfigChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DBG) Slog.d(TAG, "Configuration change: updating budgets.");
            updateAllMultipathBudgets();
        }
    }

    public void dump(IndentingPrintWriter pw) {
        // Do not use in production. Access to class data is only safe on the handler thrad.
        pw.println("MultipathPolicyTracker:");
        pw.increaseIndent();
        for (MultipathTracker t : mMultipathTrackers.values()) {
            pw.println(String.format("Network %s: quota %d, budget %d. Preference: %s",
                    t.network, t.getQuota(), t.getMultipathBudget(),
                    DebugUtils.flagsToString(ConnectivityManager.class, "MULTIPATH_PREFERENCE_",
                            t.getMultipathPreference())));
        }
        pw.decreaseIndent();
    }
}
