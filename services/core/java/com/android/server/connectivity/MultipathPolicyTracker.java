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
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;

import static com.android.server.net.NetworkPolicyManagerInternal.QUOTA_TYPE_MULTIPATH;

import android.app.usage.NetworkStatsManager;
import android.app.usage.NetworkStatsManager.UsageCallback;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.NetworkStats;
import android.net.NetworkTemplate;
import android.net.StringNetworkSpecifier;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.net.NetworkStatsManagerInternal;

import java.util.Calendar;
import java.util.concurrent.ConcurrentHashMap;

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

    private ConnectivityManager mCM;
    private NetworkPolicyManager mNPM;
    private NetworkStatsManager mStatsManager;

    private NetworkCallback mMobileNetworkCallback;
    private NetworkPolicyManager.Listener mPolicyListener;

    // STOPSHIP: replace this with a configurable mechanism.
    private static final long DEFAULT_DAILY_MULTIPATH_QUOTA = 2_500_000;

    public MultipathPolicyTracker(Context ctx, Handler handler) {
        mContext = ctx;
        mHandler = handler;
        // Because we are initialized by the ConnectivityService constructor, we can't touch any
        // connectivity APIs. Service initialization is done in start().
    }

    public void start() {
        mCM = mContext.getSystemService(ConnectivityManager.class);
        mNPM = mContext.getSystemService(NetworkPolicyManager.class);
        mStatsManager = mContext.getSystemService(NetworkStatsManager.class);

        registerTrackMobileCallback();
        registerNetworkPolicyListener();
    }

    public void shutdown() {
        maybeUnregisterTrackMobileCallback();
        unregisterNetworkPolicyListener();
        for (MultipathTracker t : mMultipathTrackers.values()) {
            t.shutdown();
        }
        mMultipathTrackers.clear();
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

        public MultipathTracker(Network network, NetworkCapabilities nc) {
            this.network = network;
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

        private long getDailyNonDefaultDataUsage() {
            Calendar start = Calendar.getInstance();
            Calendar end = (Calendar) start.clone();
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);

            try {
                final long bytes = LocalServices.getService(NetworkStatsManagerInternal.class)
                        .getNetworkTotalBytes(mNetworkTemplate, start.getTimeInMillis(),
                                end.getTimeInMillis());
                if (DBG) Slog.d(TAG, "Non-default data usage: " + bytes);
                return bytes;
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failed to get data usage: " + e);
                return -1;
            }
        }

        void updateMultipathBudget() {
            long quota = LocalServices.getService(NetworkPolicyManagerInternal.class)
                    .getSubscriptionOpportunisticQuota(this.network, QUOTA_TYPE_MULTIPATH);
            if (DBG) Slog.d(TAG, "Opportunistic quota from data plan: " + quota + " bytes");

            if (quota == 0) {
                // STOPSHIP: replace this with a configurable mechanism.
                quota = DEFAULT_DAILY_MULTIPATH_QUOTA;
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

    // Only ever updated on the handler thread. Accessed from other binder threads to retrieve
    // the tracker for a specific network.
    private final ConcurrentHashMap <Network, MultipathTracker> mMultipathTrackers =
            new ConcurrentHashMap<>();

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
                mHandler.post(() -> {
                    for (MultipathTracker t : mMultipathTrackers.values()) {
                        t.updateMultipathBudget();
                    }
                });
            }
        };
        mNPM.registerListener(mPolicyListener);
    }

    private void unregisterNetworkPolicyListener() {
        mNPM.unregisterListener(mPolicyListener);
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
