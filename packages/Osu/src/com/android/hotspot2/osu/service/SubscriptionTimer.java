package com.android.hotspot2.osu.service;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.android.hotspot2.Utils;
import com.android.hotspot2.WifiNetworkAdapter;
import com.android.hotspot2.osu.OSUManager;
import com.android.hotspot2.pps.HomeSP;

import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class SubscriptionTimer implements Runnable {
    private final Handler mHandler;
    private final OSUManager mOSUManager;
    private final WifiNetworkAdapter mWifiNetworkAdapter;
    private final Map<HomeSP, UpdateAction> mOutstanding = new HashMap<>();

    private static class UpdateAction {
        private final long mRemediation;
        private final long mPolicy;

        private UpdateAction(HomeSP homeSP, long now) {
            mRemediation = homeSP.getSubscriptionUpdate() != null ?
                    now + homeSP.getSubscriptionUpdate().getInterval() : -1;
            mPolicy = homeSP.getPolicy() != null ?
                    now + homeSP.getPolicy().getPolicyUpdate().getInterval() : -1;

            Log.d(OSUManager.TAG, "Timer set for " + homeSP.getFQDN() +
                    ", remediation: " + Utils.toUTCString(mRemediation) +
                    ", policy: " + Utils.toUTCString(mPolicy));
        }

        private boolean remediate(long now) {
            return mRemediation > 0 && now >= mRemediation;
        }

        private boolean policyUpdate(long now) {
            return mPolicy > 0 && now >= mPolicy;
        }

        private long nextExpiry(long now) {
            long min = Long.MAX_VALUE;
            if (mRemediation > now) {
                min = mRemediation;
            }
            if (mPolicy > now) {
                min = Math.min(min, mPolicy);
            }
            return min;
        }
    }

    private static final String ACTION_TIMER =
            "com.android.hotspot2.osu.service.SubscriptionTimer.action.TICK";

    public SubscriptionTimer(OSUManager osuManager,
                             WifiNetworkAdapter wifiNetworkAdapter, Context context) {
        mOSUManager = osuManager;
        mWifiNetworkAdapter = wifiNetworkAdapter;
        mHandler = new Handler();
    }

    @Override
    public void run() {
        checkUpdates();
    }

    public void checkUpdates() {
        mHandler.removeCallbacks(this);
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        Collection<HomeSP> homeSPs = mWifiNetworkAdapter.getLoadedSPs();
        if (homeSPs.isEmpty()) {
            return;
        }
        for (HomeSP homeSP : homeSPs) {
            UpdateAction updateAction = mOutstanding.get(homeSP);
            try {
                if (updateAction == null) {
                    updateAction = new UpdateAction(homeSP, now);
                    mOutstanding.put(homeSP, updateAction);
                } else if (updateAction.remediate(now)) {
                    mOSUManager.remediate(homeSP, false);
                    mOutstanding.put(homeSP, new UpdateAction(homeSP, now));
                } else if (updateAction.policyUpdate(now)) {
                    mOSUManager.remediate(homeSP, true);
                    mOutstanding.put(homeSP, new UpdateAction(homeSP, now));
                }
                next = Math.min(next, updateAction.nextExpiry(now));
            } catch (IOException | SAXException e) {
                Log.d(OSUManager.TAG, "Failed subscription update: " + e.getMessage());
            }
        }
        setAlarm(next);
    }

    private void setAlarm(long tod) {
        long delay = tod - System.currentTimeMillis();
        mHandler.postAtTime(this, Math.max(1, delay));
    }
}
