/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.NetworkStatsHistory.FIELD_RX_BYTES;
import static android.net.NetworkStatsHistory.FIELD_TX_BYTES;
import static android.telephony.TelephonyManager.SIM_STATE_READY;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.INetworkStatsSession;
import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.systemui.statusbar.policy.NetworkController.DataUsageInfo;

import java.text.SimpleDateFormat;
import java.util.Date;

public class MobileDataController {
    private static final String TAG = "MobileDataController";
    private static final boolean DEBUG = true;

    private static final SimpleDateFormat MMM_D = new SimpleDateFormat("MMM d");
    private static final int FIELDS = FIELD_RX_BYTES | FIELD_TX_BYTES;

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final ConnectivityManager mConnectivityManager;
    private final INetworkStatsService mStatsService;
    private final NetworkPolicyManager mPolicyManager;

    private INetworkStatsSession mSession;
    private Callback mCallback;

    public MobileDataController(Context context) {
        mContext = context;
        mTelephonyManager = TelephonyManager.from(context);
        mConnectivityManager = ConnectivityManager.from(context);
        mStatsService = INetworkStatsService.Stub.asInterface(
                ServiceManager.getService(Context.NETWORK_STATS_SERVICE));
        mPolicyManager = NetworkPolicyManager.from(mContext);

        try {
            mSession = mStatsService.openSession();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to open stats session");
            mSession = null;
        }
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    private DataUsageInfo warn(String msg) {
        Log.w(TAG, "Failed to get data usage, " + msg);
        return null;
    }

    public DataUsageInfo getDataUsageInfo() {
        final String subscriberId = getActiveSubscriberId(mContext);
        if (subscriberId == null) {
            return warn("no subscriber id");
        }
        if (mSession == null) {
            return warn("no stats session");
        }
        final NetworkTemplate template = NetworkTemplate.buildTemplateMobileAll(subscriberId);
        final NetworkPolicy policy = findNetworkPolicy(template);
        try {
            final NetworkStatsHistory history = mSession.getHistoryForNetwork(template, FIELDS);
            final long now = System.currentTimeMillis();
            // period = last 4 wks for now
            final long start = now - DateUtils.WEEK_IN_MILLIS * 4;
            final long end = now;
            final long callStart = System.currentTimeMillis();
            final NetworkStatsHistory.Entry entry = history.getValues(start, end, now, null);
            final long callEnd = System.currentTimeMillis();
            if (DEBUG) Log.d(TAG, String.format("history call from %s to %s now=%s took %sms: %s",
                    new Date(start), new Date(end), new Date(now), callEnd - callStart,
                    historyEntryToString(entry)));
            if (entry == null) {
                return warn("no entry data");
            }
            final long totalBytes = entry.rxBytes + entry.txBytes;
            final DataUsageInfo usage = new DataUsageInfo();
            usage.maxLevel = (long) (totalBytes / .4);
            usage.usageLevel = totalBytes;
            usage.period = MMM_D.format(new Date(start)) + " - " + MMM_D.format(new Date(end));
            if (policy != null) {
                usage.limitLevel = policy.limitBytes > 0 ? policy.limitBytes : 0;
                usage.warningLevel = policy.warningBytes > 0 ? policy.warningBytes : 0;
            }
            return usage;
        } catch (RemoteException e) {
            return warn("remote call failed");
        }
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

    private static String historyEntryToString(NetworkStatsHistory.Entry entry) {
        return entry == null ? null : new StringBuilder("Entry[")
                .append("bucketDuration=").append(entry.bucketDuration)
                .append(",bucketStart=").append(entry.bucketStart)
                .append(",activeTime=").append(entry.activeTime)
                .append(",rxBytes=").append(entry.rxBytes)
                .append(",rxPackets=").append(entry.rxPackets)
                .append(",txBytes=").append(entry.txBytes)
                .append(",txPackets=").append(entry.txPackets)
                .append(",operations=").append(entry.operations)
                .append(']').toString();
    }

    public void setMobileDataEnabled(boolean enabled) {
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

    private static String getActiveSubscriberId(Context context) {
        final TelephonyManager tele = TelephonyManager.from(context);
        final String actualSubscriberId = tele.getSubscriberId();
        return actualSubscriberId;
    }

    public interface Callback {
        void onMobileDataEnabled(boolean enabled);
    }
}
