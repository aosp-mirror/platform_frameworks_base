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
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TimeDetector;
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
import android.os.TimestampedValue;
import android.provider.Settings;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.TimeUtils;

import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Monitors the network time. If looking up the network time fails for some reason, it tries a few
 * times with a short interval and then resets to checking on longer intervals.
 *
 * <p>When available, the time is always suggested to the {@link
 * com.android.server.timedetector.TimeDetectorService} where it may be used to set the device
 * system clock, depending on user settings and what other signals are available.
 */
public class NetworkTimeUpdateService extends Binder {

    private static final String TAG = "NetworkTimeUpdateService";
    private static final boolean DBG = false;

    private static final int EVENT_AUTO_TIME_ENABLED = 1;
    private static final int EVENT_POLL_NETWORK_TIME = 2;
    private static final int EVENT_NETWORK_CHANGED = 3;

    private static final String ACTION_POLL =
            "com.android.server.NetworkTimeUpdateService.action.POLL";

    private static final int POLL_REQUEST = 0;

    private Network mDefaultNetwork = null;

    private final Context mContext;
    private final NtpTrustedTime mTime;
    private final AlarmManager mAlarmManager;
    private final TimeDetector mTimeDetector;
    private final ConnectivityManager mCM;
    private final PendingIntent mPendingPollIntent;
    private final PowerManager.WakeLock mWakeLock;

    // NTP lookup is done on this thread and handler
    private Handler mHandler;
    private AutoTimeSettingObserver mAutoTimeSettingObserver;
    private NetworkTimeUpdateCallback mNetworkTimeUpdateCallback;

    // Normal polling frequency
    private final long mPollingIntervalMs;
    // Try-again polling interval, in case the network request failed
    private final long mPollingIntervalShorterMs;
    // Number of times to try again
    private final int mTryAgainTimesMax;
    // Keeps track of how many quick attempts were made to fetch NTP time.
    // During bootup, the network may not have been up yet, or it's taking time for the
    // connection to happen.
    private int mTryAgainCounter;

    public NetworkTimeUpdateService(Context context) {
        mContext = context;
        mTime = NtpTrustedTime.getInstance(context);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mTimeDetector = mContext.getSystemService(TimeDetector.class);
        mCM = mContext.getSystemService(ConnectivityManager.class);

        Intent pollIntent = new Intent(ACTION_POLL, null);
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST, pollIntent, 0);

        mPollingIntervalMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingInterval);
        mPollingIntervalShorterMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingIntervalShorter);
        mTryAgainTimesMax = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpRetry);

        mWakeLock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);
    }

    /** Initialize the receivers and initiate the first NTP request */
    public void systemRunning() {
        registerForAlarms();

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new MyHandler(thread.getLooper());
        mNetworkTimeUpdateCallback = new NetworkTimeUpdateCallback();
        mCM.registerDefaultNetworkCallback(mNetworkTimeUpdateCallback, mHandler);

        mAutoTimeSettingObserver = new AutoTimeSettingObserver(mContext, mHandler,
                EVENT_AUTO_TIME_ENABLED);
        mAutoTimeSettingObserver.observe();
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
        // If we don't have any default network, don't bother.
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
        NtpTrustedTime.TimeResult cachedNtpResult = mTime.getCachedTimeResult();
        if (cachedNtpResult == null || cachedNtpResult.getAgeMillis() >= mPollingIntervalMs) {
            if (DBG) Log.d(TAG, "Stale NTP fix; forcing refresh");
            mTime.forceRefresh();
            cachedNtpResult = mTime.getCachedTimeResult();
        }

        if (cachedNtpResult != null && cachedNtpResult.getAgeMillis() < mPollingIntervalMs) {
            // Obtained fresh fix; schedule next normal update
            resetAlarm(mPollingIntervalMs);

            // Suggest the time to the time detector. It may choose use it to set the system clock.
            TimestampedValue<Long> timeSignal = new TimestampedValue<>(
                    cachedNtpResult.getElapsedRealtimeMillis(), cachedNtpResult.getTimeMillis());
            NetworkTimeSuggestion timeSuggestion = new NetworkTimeSuggestion(timeSignal);
            timeSuggestion.addDebugInfo("Origin: NetworkTimeUpdateService. event=" + event);
            mTimeDetector.suggestNetworkTime(timeSuggestion);
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

    /** Handler to do the network accesses on */
    private class MyHandler extends Handler {

        MyHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_AUTO_TIME_ENABLED:
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

    /**
     * Observer to watch for changes to the AUTO_TIME setting. It only triggers when the setting
     * is enabled.
     */
    private static class AutoTimeSettingObserver extends ContentObserver {

        private final Context mContext;
        private final int mMsg;
        private final Handler mHandler;

        AutoTimeSettingObserver(Context context, Handler handler, int msg) {
            super(handler);
            mContext = context;
            mHandler = handler;
            mMsg = msg;
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME),
                    false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            if (isAutomaticTimeEnabled()) {
                mHandler.obtainMessage(mMsg).sendToTarget();
            }
        }

        /**
         * Checks if the user prefers to automatically set the time.
         */
        private boolean isAutomaticTimeEnabled() {
            ContentResolver resolver = mContext.getContentResolver();
            return Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 0) != 0;
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
        pw.println("\nTryAgainCounter: " + mTryAgainCounter);
        NtpTrustedTime.TimeResult ntpResult = mTime.getCachedTimeResult();
        pw.println("NTP cache result: " + ntpResult);
        if (ntpResult != null) {
            pw.println("NTP result age: " + ntpResult.getAgeMillis());
        }
        pw.println();
    }
}
