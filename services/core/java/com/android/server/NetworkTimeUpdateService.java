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

package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.TimeUtils;

import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Monitors the network time and updates the system time if it is out of sync
 * and there hasn't been any NITZ update from the carrier recently.
 * If looking up the network time fails for some reason, it tries a few times with a short
 * interval and then resets to checking on longer intervals.
 * <p>
 * If the user enables AUTO_TIME, it will check immediately for the network time, if NITZ wasn't
 * available.
 * </p>
 */
public class NetworkTimeUpdateService extends Binder {

    private static final String TAG = "NetworkTimeUpdateService";
    private static final boolean DBG = false;

    private static final int EVENT_AUTO_TIME_CHANGED = 1;
    private static final int EVENT_POLL_NETWORK_TIME = 2;
    private static final int EVENT_NETWORK_CHANGED = 3;

    private static final String ACTION_POLL =
            "com.android.server.NetworkTimeUpdateService.action.POLL";

    private static final int POLL_REQUEST = 0;

    private static final long NOT_SET = -1;
    private long mNitzTimeSetTime = NOT_SET;
    private Network mDefaultNetwork = null;

    private final Context mContext;
    private final NtpTrustedTime mTime;
    private final AlarmManager mAlarmManager;
    private final ConnectivityManager mCM;
    private final PendingIntent mPendingPollIntent;
    private final PowerManager.WakeLock mWakeLock;

    // NTP lookup is done on this thread and handler
    private Handler mHandler;
    private SettingsObserver mSettingsObserver;
    private NetworkTimeUpdateCallback mNetworkTimeUpdateCallback;

    // Normal polling frequency
    private final long mPollingIntervalMs;
    // Try-again polling interval, in case the network request failed
    private final long mPollingIntervalShorterMs;
    // Number of times to try again
    private final int mTryAgainTimesMax;
    // If the time difference is greater than this threshold, then update the time.
    private final int mTimeErrorThresholdMs;
    // Keeps track of how many quick attempts were made to fetch NTP time.
    // During bootup, the network may not have been up yet, or it's taking time for the
    // connection to happen.
    private int mTryAgainCounter;

    public NetworkTimeUpdateService(Context context) {
        mContext = context;
        mTime = NtpTrustedTime.getInstance(context);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mCM = mContext.getSystemService(ConnectivityManager.class);

        Intent pollIntent = new Intent(ACTION_POLL, null);
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST, pollIntent, 0);

        mPollingIntervalMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingInterval);
        mPollingIntervalShorterMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingIntervalShorter);
        mTryAgainTimesMax = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpRetry);
        mTimeErrorThresholdMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpThreshold);

        mWakeLock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);
    }

    /** Initialize the receivers and initiate the first NTP request */
    public void systemRunning() {
        registerForTelephonyIntents();
        registerForAlarms();

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new MyHandler(thread.getLooper());
        mNetworkTimeUpdateCallback = new NetworkTimeUpdateCallback();
        mCM.registerDefaultNetworkCallback(mNetworkTimeUpdateCallback, mHandler);

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_AUTO_TIME_CHANGED);
        mSettingsObserver.observe(mContext);
    }

    private void registerForTelephonyIntents() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        mContext.registerReceiver(mNitzReceiver, intentFilter);
    }

    private void registerForAlarms() {
        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mHandler.obtainMessage(EVENT_POLL_NETWORK_TIME).sendToTarget();
                }
            }, new IntentFilter(ACTION_POLL));
    }

    private void onPollNetworkTime(int event) {
        // If Automatic time is not set, don't bother. Similarly, if we don't
        // have any default network, don't bother.
        if (mDefaultNetwork == null) return;
        mWakeLock.acquire();
        try {
            onPollNetworkTimeUnderWakeLock(event);
        } finally {
            mWakeLock.release();
        }
    }

    private void onPollNetworkTimeUnderWakeLock(int event) {
        // Force an NTP fix when outdated
        if (mTime.getCacheAge() >= mPollingIntervalMs) {
            if (DBG) Log.d(TAG, "Stale NTP fix; forcing refresh");
            mTime.forceRefresh();
        }

        if (mTime.getCacheAge() < mPollingIntervalMs) {
            // Obtained fresh fix; schedule next normal update
            resetAlarm(mPollingIntervalMs);
            if (isAutomaticTimeRequested()) {
                updateSystemClock(event);
            }

        } else {
            // No fresh fix; schedule retry
            mTryAgainCounter++;
            if (mTryAgainTimesMax < 0 || mTryAgainCounter <= mTryAgainTimesMax) {
                resetAlarm(mPollingIntervalShorterMs);
            } else {
                // Try much later
                mTryAgainCounter = 0;
                resetAlarm(mPollingIntervalMs);
            }
        }
    }

    private long getNitzAge() {
        if (mNitzTimeSetTime == NOT_SET) {
            return Long.MAX_VALUE;
        } else {
            return SystemClock.elapsedRealtime() - mNitzTimeSetTime;
        }
    }

    /**
     * Consider updating system clock based on current NTP fix, if requested by
     * user, significant enough delta, and we don't have a recent NITZ.
     */
    private void updateSystemClock(int event) {
        final boolean forceUpdate = (event == EVENT_AUTO_TIME_CHANGED);
        if (!forceUpdate) {
            if (getNitzAge() < mPollingIntervalMs) {
                if (DBG) Log.d(TAG, "Ignoring NTP update due to recent NITZ");
                return;
            }

            final long skew = Math.abs(mTime.currentTimeMillis() - System.currentTimeMillis());
            if (skew < mTimeErrorThresholdMs) {
                if (DBG) Log.d(TAG, "Ignoring NTP update due to low skew");
                return;
            }
        }

        SystemClock.setCurrentTimeMillis(mTime.currentTimeMillis());
    }

    /**
     * Cancel old alarm and starts a new one for the specified interval.
     *
     * @param interval when to trigger the alarm, starting from now.
     */
    private void resetAlarm(long interval) {
        mAlarmManager.cancel(mPendingPollIntent);
        long now = SystemClock.elapsedRealtime();
        long next = now + interval;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, next, mPendingPollIntent);
    }

    /**
     * Checks if the user prefers to automatically set the time.
     */
    private boolean isAutomaticTimeRequested() {
        return Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.AUTO_TIME, 0) != 0;
    }

    /** Receiver for Nitz time events */
    private BroadcastReceiver mNitzReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DBG) Log.d(TAG, "Received " + action);
            if (TelephonyIntents.ACTION_NETWORK_SET_TIME.equals(action)) {
                mNitzTimeSetTime = SystemClock.elapsedRealtime();
            }
        }
    };

    /** Handler to do the network accesses on */
    private class MyHandler extends Handler {

        public MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_AUTO_TIME_CHANGED:
                case EVENT_POLL_NETWORK_TIME:
                case EVENT_NETWORK_CHANGED:
                    onPollNetworkTime(msg.what);
                    break;
            }
        }
    }

    private class NetworkTimeUpdateCallback extends NetworkCallback {
        @Override
        public void onAvailable(Network network) {
            Log.d(TAG, String.format("New default network %s; checking time.", network));
            mDefaultNetwork = network;
            // Running on mHandler so invoke directly.
            onPollNetworkTime(EVENT_NETWORK_CHANGED);
        }

        @Override
        public void onLost(Network network) {
            if (network.equals(mDefaultNetwork)) mDefaultNetwork = null;
        }
    }

    /** Observer to watch for changes to the AUTO_TIME setting */
    private static class SettingsObserver extends ContentObserver {

        private int mMsg;
        private Handler mHandler;

        SettingsObserver(Handler handler, int msg) {
            super(handler);
            mHandler = handler;
            mMsg = msg;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mMsg).sendToTarget();
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        pw.print("PollingIntervalMs: ");
        TimeUtils.formatDuration(mPollingIntervalMs, pw);
        pw.print("\nPollingIntervalShorterMs: ");
        TimeUtils.formatDuration(mPollingIntervalShorterMs, pw);
        pw.println("\nTryAgainTimesMax: " + mTryAgainTimesMax);
        pw.print("TimeErrorThresholdMs: ");
        TimeUtils.formatDuration(mTimeErrorThresholdMs, pw);
        pw.println("\nTryAgainCounter: " + mTryAgainCounter);
        pw.println("NTP cache age: " + mTime.getCacheAge());
        pw.println("NTP cache certainty: " + mTime.getCacheCertainty());
        pw.println();
    }
}
