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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
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
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.TimestampedValue;
import android.provider.Settings;
import android.util.LocalLog;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.NtpTrustedTime.TimeResult;

import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;

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
            "com.android.server.timedetector.NetworkTimeUpdateService.action.POLL";

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

    /**
     * A log that records the decisions to fetch a network time update.
     * This is logged in bug reports to assist with debugging issues with network time suggestions.
     */
    @NonNull
    private final LocalLog mLocalLog = new LocalLog(30, false /* useLocalTimestamps */);

    public NetworkTimeUpdateService(Context context) {
        mContext = context;
        mTime = NtpTrustedTime.getInstance(context);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mTimeDetector = mContext.getSystemService(TimeDetector.class);
        mCM = mContext.getSystemService(ConnectivityManager.class);

        Intent pollIntent = new Intent(ACTION_POLL, null);
        // Broadcast alarms sent by system are immutable
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST, pollIntent,
                PendingIntent.FLAG_IMMUTABLE);

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

    /**
     * Clears the cached NTP time. For use during tests to simulate when no NTP time is available.
     *
     * <p>This operation takes place in the calling thread rather than the service's handler thread.
     */
    void clearTimeForTests() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "clear latest network time");

        mTime.clearCachedTimeResult();

        mLocalLog.log("clearTimeForTests");
    }

    /**
     * Forces the service to refresh the NTP time.
     *
     * <p>This operation takes place in the calling thread rather than the service's handler thread.
     * This method does not affect currently scheduled refreshes. If the NTP request is successful
     * it will make an (asynchronously handled) suggestion to the time detector.
     */
    boolean forceRefreshForTests() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "force network time refresh");

        boolean success = mTime.forceRefresh();
        mLocalLog.log("forceRefreshForTests: success=" + success);

        if (success) {
            makeNetworkTimeSuggestion(mTime.getCachedTimeResult(),
                    "Origin: NetworkTimeUpdateService: forceRefreshForTests");
        }

        return success;
    }

    /**
     * Overrides the NTP server config for tests. Passing {@code null} to a parameter clears the
     * test value, i.e. so the normal value will be used next time.
     */
    void setServerConfigForTests(
            @Nullable String hostname, @Nullable Integer port, @Nullable Duration timeout) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "set NTP server config for tests");

        mLocalLog.log("Setting server config for tests: hostname=" + hostname
                + ", port=" + port
                + ", timeout=" + timeout);
        mTime.setServerConfigForTests(hostname, port, timeout);
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
        long currentElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        // Force an NTP fix when outdated
        NtpTrustedTime.TimeResult cachedNtpResult = mTime.getCachedTimeResult();
        if (cachedNtpResult == null || cachedNtpResult.getAgeMillis(currentElapsedRealtimeMillis)
                >= mPollingIntervalMs) {
            if (DBG) Log.d(TAG, "Stale NTP fix; forcing refresh");
            boolean isSuccessful = mTime.forceRefresh();
            if (isSuccessful) {
                mTryAgainCounter = 0;
            } else {
                String logMsg = "forceRefresh() returned false: cachedNtpResult=" + cachedNtpResult
                        + ", currentElapsedRealtimeMillis=" + currentElapsedRealtimeMillis;

                if (DBG) {
                    Log.d(TAG, logMsg);
                }
                mLocalLog.log(logMsg);
            }

            cachedNtpResult = mTime.getCachedTimeResult();
        }

        if (cachedNtpResult != null
                && cachedNtpResult.getAgeMillis(currentElapsedRealtimeMillis)
                < mPollingIntervalMs) {
            // Obtained fresh fix; schedule next normal update
            resetAlarm(mPollingIntervalMs
                    - cachedNtpResult.getAgeMillis(currentElapsedRealtimeMillis));

            makeNetworkTimeSuggestion(cachedNtpResult,
                    "Origin: NetworkTimeUpdateService. event=" + event);
        } else {
            // No fresh fix; schedule retry
            mTryAgainCounter++;
            if (mTryAgainTimesMax < 0 || mTryAgainCounter <= mTryAgainTimesMax) {
                resetAlarm(mPollingIntervalShorterMs);
            } else {
                // Try much later
                String logMsg = "mTryAgainTimesMax exceeded, cachedNtpResult=" + cachedNtpResult;
                if (DBG) {
                    Log.d(TAG, logMsg);
                }
                mLocalLog.log(logMsg);
                mTryAgainCounter = 0;
                resetAlarm(mPollingIntervalMs);
            }
        }
    }

    /** Suggests the time to the time detector. It may choose use it to set the system clock. */
    private void makeNetworkTimeSuggestion(TimeResult ntpResult, String debugInfo) {
        TimestampedValue<Long> timeSignal = new TimestampedValue<>(
                ntpResult.getElapsedRealtimeMillis(), ntpResult.getTimeMillis());
        NetworkTimeSuggestion timeSuggestion = new NetworkTimeSuggestion(timeSignal);
        timeSuggestion.addDebugInfo(debugInfo);
        mTimeDetector.suggestNetworkTime(timeSuggestion);
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
        pw.println("mPollingIntervalMs=" + Duration.ofMillis(mPollingIntervalMs));
        pw.println("mPollingIntervalShorterMs=" + Duration.ofMillis(mPollingIntervalShorterMs));
        pw.println("mTryAgainTimesMax=" + mTryAgainTimesMax);
        pw.println("mTryAgainCounter=" + mTryAgainCounter);
        pw.println();
        pw.println("NtpTrustedTime:");
        mTime.dump(pw);
        pw.println();
        pw.println("Local logs:");
        mLocalLog.dump(fd, pw, args);
        pw.println();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new NetworkTimeUpdateServiceShellCommand(this).exec(
                this, in, out, err, args, callback, resultReceiver);
    }
}
