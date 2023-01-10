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
import android.annotation.RequiresPermission;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.time.UnixEpochTime;
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
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.LocalLog;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.NtpTrustedTime.TimeResult;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;

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

    private static final String ACTION_POLL =
            "com.android.server.timedetector.NetworkTimeUpdateService.action.POLL";
    private static final int POLL_REQUEST = 0;

    private final Object mLock = new Object();
    private final Context mContext;
    private final NtpTrustedTime mNtpTrustedTime;
    private final AlarmManager mAlarmManager;
    private final TimeDetectorInternal mTimeDetectorInternal;
    private final ConnectivityManager mCM;
    private final PendingIntent mPendingPollIntent;
    private final PowerManager.WakeLock mWakeLock;

    // Normal polling frequency
    private final int mNormalPollingIntervalMillis;
    // Try-again polling interval, in case the network request failed
    private final int mShortPollingIntervalMillis;
    // Number of times to try again
    private final int mTryAgainTimesMax;

    /**
     * A log that records the decisions to fetch a network time update.
     * This is logged in bug reports to assist with debugging issues with network time suggestions.
     */
    private final LocalLog mLocalLog = new LocalLog(30, false /* useLocalTimestamps */);

    // Blocking NTP lookup is done using this handler
    private final Handler mHandler;

    // This field is only updated and accessed by the mHandler thread (except dump()).
    @GuardedBy("mLock")
    @Nullable
    private Network mDefaultNetwork = null;

    // Keeps track of how many quick attempts were made to fetch NTP time.
    // During bootup, the network may not have been up yet, or it's taking time for the
    // connection to happen.
    // This field is only updated and accessed by the mHandler thread (except dump()).
    @GuardedBy("mLock")
    private int mTryAgainCounter;

    public NetworkTimeUpdateService(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mTimeDetectorInternal = LocalServices.getService(TimeDetectorInternal.class);
        mCM = mContext.getSystemService(ConnectivityManager.class);
        mWakeLock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNtpTrustedTime = NtpTrustedTime.getInstance(context);

        mTryAgainTimesMax = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpRetry);
        mNormalPollingIntervalMillis = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingInterval);
        mShortPollingIntervalMillis = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingIntervalShorter);

        // Broadcast alarms sent by system are immutable
        Intent pollIntent = new Intent(ACTION_POLL, null);
        mPendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST,
                pollIntent, PendingIntent.FLAG_IMMUTABLE);

        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = thread.getThreadHandler();
    }

    /** Initialize the receivers and initiate the first NTP request */
    public void systemRunning() {
        // Listen for scheduled refreshes.
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onPollNetworkTime("scheduled refresh");
                    }
                },
                new IntentFilter(ACTION_POLL),
                /*broadcastPermission=*/ null,
                mHandler);

        // Listen for network connectivity changes.
        NetworkTimeUpdateCallback networkTimeUpdateCallback = new NetworkTimeUpdateCallback();
        mCM.registerDefaultNetworkCallback(networkTimeUpdateCallback, mHandler);

        // Listen for user settings changes.
        ContentResolver resolver = mContext.getContentResolver();
        AutoTimeSettingObserver autoTimeSettingObserver =
                new AutoTimeSettingObserver(mHandler, mContext);
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME),
                false, autoTimeSettingObserver);
    }

    /**
     * Overrides the NTP server config for tests. Passing {@code null} to a parameter clears the
     * test value, i.e. so the normal value will be used next time.
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    void setServerConfigForTests(@Nullable NtpTrustedTime.NtpConfig ntpConfig) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "set NTP server config for tests");

        final long token = Binder.clearCallingIdentity();
        try {
            mNtpTrustedTime.setServerConfigForTests(ntpConfig);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Clears the cached NTP time. For use during tests to simulate when no NTP time is available.
     *
     * <p>This operation takes place in the calling thread rather than the service's handler thread.
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    void clearTimeForTests() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "clear latest network time");

        final long token = Binder.clearCallingIdentity();
        try {
            mNtpTrustedTime.clearCachedTimeResult();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Forces the service to refresh the NTP time.
     *
     * <p>This operation takes place in the calling thread rather than the service's handler thread.
     * This method does not affect currently scheduled refreshes.
     *
     * <p>If the NTP request is successful it will synchronously make a suggestion to the time
     * detector, which will be asynchronously handled; therefore the effects are not guaranteed to
     * be visible when this call returns.
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    boolean forceRefreshForTests() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "force network time refresh");

        final long token = Binder.clearCallingIdentity();
        try {
            Network network;
            synchronized (mLock) {
                network = mDefaultNetwork;
            }
            if (network == null) return false;

            boolean success = mNtpTrustedTime.forceRefresh(network);
            if (success) {
                makeNetworkTimeSuggestion(mNtpTrustedTime.getCachedTimeResult(),
                        "Origin: NetworkTimeUpdateService: forceRefreshForTests");
            }
            return success;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void onPollNetworkTime(@NonNull String reason) {
        // If we don't have any default network, don't bother.
        Network network;
        synchronized (mLock) {
            network = mDefaultNetwork;
        }
        if (network == null) return;

        mWakeLock.acquire();
        try {
            onPollNetworkTimeUnderWakeLock(network, reason);
        } finally {
            mWakeLock.release();
        }
    }

    private void onPollNetworkTimeUnderWakeLock(
            @NonNull Network network, @NonNull String reason) {
        long currentElapsedRealtimeMillis = SystemClock.elapsedRealtime();

        final int maxNetworkTimeAgeMillis = mNormalPollingIntervalMillis;
        // Force an NTP fix when outdated
        NtpTrustedTime.TimeResult cachedNtpResult = mNtpTrustedTime.getCachedTimeResult();
        if (cachedNtpResult == null
                || cachedNtpResult.getAgeMillis(currentElapsedRealtimeMillis)
                >= maxNetworkTimeAgeMillis) {
            if (DBG) Log.d(TAG, "Stale NTP fix; forcing refresh using network=" + network);
            boolean success = mNtpTrustedTime.forceRefresh(network);
            if (success) {
                synchronized (mLock) {
                    mTryAgainCounter = 0;
                }
            } else {
                String logMsg = "forceRefresh() returned false:"
                        + " cachedNtpResult=" + cachedNtpResult
                        + ", currentElapsedRealtimeMillis=" + currentElapsedRealtimeMillis;

                if (DBG) {
                    Log.d(TAG, logMsg);
                }
                mLocalLog.log(logMsg);
            }

            cachedNtpResult = mNtpTrustedTime.getCachedTimeResult();
        }

        if (cachedNtpResult != null
                && cachedNtpResult.getAgeMillis(currentElapsedRealtimeMillis)
                < maxNetworkTimeAgeMillis) {
            // Obtained fresh fix; schedule next normal update
            scheduleNextRefresh(mNormalPollingIntervalMillis
                    - cachedNtpResult.getAgeMillis(currentElapsedRealtimeMillis));

            makeNetworkTimeSuggestion(cachedNtpResult, reason);
        } else {
            synchronized (mLock) {
                // No fresh fix; schedule retry
                mTryAgainCounter++;
                if (mTryAgainTimesMax < 0 || mTryAgainCounter <= mTryAgainTimesMax) {
                    scheduleNextRefresh(mShortPollingIntervalMillis);
                } else {
                    // Try much later
                    String logMsg = "mTryAgainTimesMax exceeded,"
                            + " cachedNtpResult=" + cachedNtpResult;
                    if (DBG) {
                        Log.d(TAG, logMsg);
                    }
                    mLocalLog.log(logMsg);
                    mTryAgainCounter = 0;

                    scheduleNextRefresh(mNormalPollingIntervalMillis);
                }
            }
        }
    }

    /** Suggests the time to the time detector. It may choose use it to set the system clock. */
    private void makeNetworkTimeSuggestion(
            @NonNull TimeResult ntpResult, @NonNull String debugInfo) {
        UnixEpochTime timeSignal = new UnixEpochTime(
                ntpResult.getElapsedRealtimeMillis(), ntpResult.getTimeMillis());
        NetworkTimeSuggestion timeSuggestion =
                new NetworkTimeSuggestion(timeSignal, ntpResult.getUncertaintyMillis());
        timeSuggestion.addDebugInfo(debugInfo);
        timeSuggestion.addDebugInfo(ntpResult.toString());
        mTimeDetectorInternal.suggestNetworkTime(timeSuggestion);
    }

    /**
     * Cancel old alarm and starts a new one for the specified interval.
     *
     * @param delayMillis when to trigger the alarm, starting from now.
     */
    private void scheduleNextRefresh(long delayMillis) {
        mAlarmManager.cancel(mPendingPollIntent);
        long now = SystemClock.elapsedRealtime();
        long next = now + delayMillis;
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME, next, mPendingPollIntent);
    }

    // All callbacks will be invoked using mHandler because of how the callback is registered.
    private class NetworkTimeUpdateCallback extends NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.d(TAG, String.format("New default network %s; checking time.", network));
            synchronized (mLock) {
                mDefaultNetwork = network;
            }

            // Running on mHandler so invoke directly.
            onPollNetworkTime("network available");
        }

        @Override
        public void onLost(@NonNull Network network) {
            synchronized (mLock) {
                if (network.equals(mDefaultNetwork)) {
                    mDefaultNetwork = null;
                }
            }
        }
    }

    /**
     * Observer to watch for changes to the AUTO_TIME setting. It only triggers when the setting
     * is enabled.
     */
    private class AutoTimeSettingObserver extends ContentObserver {

        private final Context mContext;

        AutoTimeSettingObserver(@NonNull Handler handler, @NonNull Context context) {
            super(handler);
            mContext = Objects.requireNonNull(context);
        }

        @Override
        public void onChange(boolean selfChange) {
            // onChange() will be invoked using handler, see the constructor.
            if (isAutomaticTimeEnabled()) {
                onPollNetworkTime("automatic time enabled");
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

        pw.println("mNormalPollingIntervalMillis="
                + Duration.ofMillis(mNormalPollingIntervalMillis));
        pw.println("mShortPollingIntervalMillis="
                + Duration.ofMillis(mShortPollingIntervalMillis));
        pw.println("mTryAgainTimesMax=" + mTryAgainTimesMax);
        synchronized (mLock) {
            pw.println("mDefaultNetwork=" + mDefaultNetwork);
            pw.println("mTryAgainCounter=" + mTryAgainCounter);
        }
        pw.println();
        pw.println("NtpTrustedTime:");
        mNtpTrustedTime.dump(pw);
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
