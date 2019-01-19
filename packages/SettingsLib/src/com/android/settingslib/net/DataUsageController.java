/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settingslib.net;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkStatsHistory.FIELD_RX_BYTES;
import static android.net.NetworkStatsHistory.FIELD_TX_BYTES;
import static android.net.TrafficStats.MB_IN_BYTES;
import static android.telephony.TelephonyManager.SIM_STATE_READY;
import static android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
import static android.text.format.DateUtils.FORMAT_SHOW_DATE;

import android.app.usage.NetworkStats.Bucket;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Range;

import com.android.internal.R;

import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.Locale;

public class DataUsageController {

    private static final String TAG = "DataUsageController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int FIELDS = FIELD_RX_BYTES | FIELD_TX_BYTES;
    private static final StringBuilder PERIOD_BUILDER = new StringBuilder(50);
    private static final java.util.Formatter PERIOD_FORMATTER = new java.util.Formatter(
            PERIOD_BUILDER, Locale.getDefault());

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final ConnectivityManager mConnectivityManager;
    private final INetworkStatsService mStatsService;
    private final NetworkPolicyManager mPolicyManager;
    private final NetworkStatsManager mNetworkStatsManager;

    private INetworkStatsSession mSession;
    private Callback mCallback;
    private NetworkNameProvider mNetworkController;
    private int mSubscriptionId;

    public DataUsageController(Context context) {
        mContext = context;
        mTelephonyManager = TelephonyManager.from(context);
        mConnectivityManager = ConnectivityManager.from(context);
        mStatsService = INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        mPolicyManager = NetworkPolicyManager.from(mContext);
        mNetworkStatsManager = context.getSystemService(NetworkStatsManager.class);
        mSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    public void setNetworkController(NetworkNameProvider networkController) {
        mNetworkController = networkController;
    }

    /**
     * By default this class will just get data usage information for the default data subscription,
     * but this method can be called to require it to use an explicit subscription id which may be
     * different from the default one (this is useful for the case of multi-SIM devices).
     */
    public void setSubscriptionId(int subscriptionId) {
        mSubscriptionId = subscriptionId;
    }

    /**
     * Returns the default warning level in bytes.
     */
    public long getDefaultWarningLevel() {
        return MB_IN_BYTES
                * mContext.getResources().getInteger(R.integer.default_data_warning_level_mb);
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    private DataUsageInfo warn(String msg) {
        Log.w(TAG, "Failed to get data usage, " + msg);
        return null;
    }

    public DataUsageInfo getDataUsageInfo() {
        final String subscriberId = getActiveSubscriberId();
        if (subscriberId == null) {
            return warn("no subscriber id");
        }
        NetworkTemplate template = NetworkTemplate.buildTemplateMobileAll(subscriberId);
        template = NetworkTemplate.normalize(template, mTelephonyManager.getMergedSubscriberIds());

        return getDataUsageInfo(template);
    }

    public DataUsageInfo getWifiDataUsageInfo() {
        NetworkTemplate template = NetworkTemplate.buildTemplateWifiWildcard();
        return getDataUsageInfo(template);
    }

    public DataUsageInfo getDataUsageInfo(NetworkTemplate template) {
        final NetworkPolicy policy = findNetworkPolicy(template);
        final long now = System.currentTimeMillis();
        final long start, end;
        final Iterator<Range<ZonedDateTime>> it = (policy != null) ? policy.cycleIterator() : null;
        if (it != null && it.hasNext()) {
            final Range<ZonedDateTime> cycle = it.next();
            start = cycle.getLower().toInstant().toEpochMilli();
            end = cycle.getUpper().toInstant().toEpochMilli();
        } else {
            // period = last 4 wks
            end = now;
            start = now - DateUtils.WEEK_IN_MILLIS * 4;
        }
        final long totalBytes = getUsageLevel(template, start, end);
        if (totalBytes < 0L) {
            return warn("no entry data");
        }
        final DataUsageInfo usage = new DataUsageInfo();
        usage.startDate = start;
        usage.usageLevel = totalBytes;
        usage.period = formatDateRange(start, end);
        usage.cycleStart = start;
        usage.cycleEnd = end;

        if (policy != null) {
            usage.limitLevel = policy.limitBytes > 0 ? policy.limitBytes : 0;
            usage.warningLevel = policy.warningBytes > 0 ? policy.warningBytes : 0;
        } else {
            usage.warningLevel = getDefaultWarningLevel();
        }
        if (usage != null && mNetworkController != null) {
            usage.carrier = mNetworkController.getMobileDataNetworkName();
        }
        return usage;
    }

    /**
     * Get the total usage level recorded in the network history
     * @param template the network template to retrieve the network history
     * @return the total usage level recorded in the network history or -1L if there is error
     * retrieving the data.
     */
    public long getHistoricalUsageLevel(NetworkTemplate template) {
        return getUsageLevel(template, 0L /* start */, System.currentTimeMillis() /* end */);
    }

    private long getUsageLevel(NetworkTemplate template, long start, long end) {
        try {
            final Bucket bucket = mNetworkStatsManager.querySummaryForDevice(
                    getNetworkType(template), getActiveSubscriberId(),
                    start, end);
            if (bucket != null) {
                return bucket.getRxBytes() + bucket.getTxBytes();
            }
            Log.w(TAG, "Failed to get data usage, no entry data");
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get data usage, remote call failed");
        }
        return -1L;
    }

    private NetworkPolicy findNetworkPolicy(NetworkTemplate template) {
        if (mPolicyManager == null || template == null) return null;
        final NetworkPolicy[] policies = mPolicyManager.getNetworkPolicies();
        if (policies == null) return null;
        final int N = policies.length;
        for (int i = 0; i < N; i++) {
            final NetworkPolicy policy = policies[i];
            if (policy != null && template.equals(policy.template)) {
                return policy;
            }
        }
        return null;
    }

    private static String statsBucketToString(Bucket bucket) {
        return bucket == null ? null : new StringBuilder("Entry[")
            .append("bucketDuration=").append(bucket.getEndTimeStamp() - bucket.getStartTimeStamp())
            .append(",bucketStart=").append(bucket.getStartTimeStamp())
            .append(",rxBytes=").append(bucket.getRxBytes())
            .append(",rxPackets=").append(bucket.getRxPackets())
            .append(",txBytes=").append(bucket.getTxBytes())
            .append(",txPackets=").append(bucket.getTxPackets())
            .append(']').toString();
    }

    public void setMobileDataEnabled(boolean enabled) {
        Log.d(TAG, "setMobileDataEnabled: enabled=" + enabled);
        mTelephonyManager.setDataEnabled(enabled);
        if (mCallback != null) {
            mCallback.onMobileDataEnabled(enabled);
        }
    }

    public boolean isMobileDataSupported() {
        // require both supported network and ready SIM
        return mConnectivityManager.isNetworkSupported(TYPE_MOBILE)
                && mTelephonyManager.getSimState() == SIM_STATE_READY;
    }

    public boolean isMobileDataEnabled() {
        return mTelephonyManager.getDataEnabled();
    }

    static int getNetworkType(NetworkTemplate networkTemplate) {
        if (networkTemplate == null) {
            return ConnectivityManager.TYPE_NONE;
        }
        final int matchRule = networkTemplate.getMatchRule();
        switch (matchRule) {
            case NetworkTemplate.MATCH_MOBILE:
            case NetworkTemplate.MATCH_MOBILE_WILDCARD:
                return ConnectivityManager.TYPE_MOBILE;
            case NetworkTemplate.MATCH_WIFI:
            case NetworkTemplate.MATCH_WIFI_WILDCARD:
                return  ConnectivityManager.TYPE_WIFI;
            case NetworkTemplate.MATCH_ETHERNET:
                return  ConnectivityManager.TYPE_ETHERNET;
            default:
                return ConnectivityManager.TYPE_MOBILE;
        }
    }

    private String getActiveSubscriberId() {
        final TelephonyManager tele = TelephonyManager.from(mContext);
        int subscriptionId = mSubscriptionId;
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        final String actualSubscriberId = tele.getSubscriberId(subscriptionId);
        return actualSubscriberId;
    }

    private String formatDateRange(long start, long end) {
        final int flags = FORMAT_SHOW_DATE | FORMAT_ABBREV_MONTH;
        synchronized (PERIOD_BUILDER) {
            PERIOD_BUILDER.setLength(0);
            return DateUtils.formatDateRange(mContext, PERIOD_FORMATTER, start, end, flags, null)
                    .toString();
        }
    }

    public interface NetworkNameProvider {
        String getMobileDataNetworkName();
    }

    public static class DataUsageInfo {
        public String carrier;
        public String period;
        public long startDate;
        public long limitLevel;
        public long warningLevel;
        public long usageLevel;
        public long cycleStart;
        public long cycleEnd;
    }

    public interface Callback {
        void onMobileDataEnabled(boolean enabled);
    }
}
