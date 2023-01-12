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

import android.annotation.ElapsedRealtimeLong;
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
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;
import android.util.NtpTrustedTime;
import android.util.NtpTrustedTime.TimeResult;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Refreshes network time periodically, when network connectivity becomes available and when the
 * user enables automatic time detection.
 *
 * <p>For periodic requests, this service attempts to leave an interval between successful requests.
 * If a request fails, it retries a number of times with a "short" interval and then resets to the
 * normal interval. The process then repeats.
 *
 * <p>When a valid network time is available, the time is always suggested to the {@link
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
    private final ConnectivityManager mCM;
    private final PowerManager.WakeLock mWakeLock;
    private final NtpTrustedTime mNtpTrustedTime;
    private final Engine.RefreshCallbacks mRefreshCallbacks;
    private final Engine mEngine;

    // Blocking NTP lookup is done using this handler
    private final Handler mHandler;

    // This field is only updated and accessed by the mHandler thread (except dump()).
    @GuardedBy("mLock")
    @Nullable
    private Network mDefaultNetwork = null;

    public NetworkTimeUpdateService(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
        mCM = mContext.getSystemService(ConnectivityManager.class);
        mWakeLock = context.getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNtpTrustedTime = NtpTrustedTime.getInstance(context);

        Supplier<Long> elapsedRealtimeMillisSupplier = SystemClock::elapsedRealtime;
        int tryAgainTimesMax = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpRetry);
        int normalPollingIntervalMillis = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingInterval);
        int shortPollingIntervalMillis = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_ntpPollingIntervalShorter);
        mEngine = new EngineImpl(elapsedRealtimeMillisSupplier, normalPollingIntervalMillis,
                shortPollingIntervalMillis, tryAgainTimesMax, mNtpTrustedTime);

        AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
        TimeDetectorInternal timeDetectorInternal =
                LocalServices.getService(TimeDetectorInternal.class);
        // Broadcast alarms sent by system are immutable
        Intent pollIntent = new Intent(ACTION_POLL, null);
        PendingIntent pendingPollIntent = PendingIntent.getBroadcast(mContext, POLL_REQUEST,
                pollIntent, PendingIntent.FLAG_IMMUTABLE);
        mRefreshCallbacks = new Engine.RefreshCallbacks() {
            @Override
            public void scheduleNextRefresh(@ElapsedRealtimeLong long elapsedRealtimeMillis) {
                alarmManager.cancel(pendingPollIntent);
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME, elapsedRealtimeMillis, pendingPollIntent);
            }

            @Override
            public void submitSuggestion(NetworkTimeSuggestion suggestion) {
                timeDetectorInternal.suggestNetworkTime(suggestion);
            }
        };

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

            return mEngine.forceRefreshForTests(network, mRefreshCallbacks);
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
            mEngine.refreshIfRequiredAndReschedule(network, reason, mRefreshCallbacks);
        } finally {
            mWakeLock.release();
        }
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

        synchronized (mLock) {
            pw.println("mDefaultNetwork=" + mDefaultNetwork);
        }
        mEngine.dump(pw);
        pw.println();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new NetworkTimeUpdateServiceShellCommand(this).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    /**
     * The interface the service uses to interact with the time refresh logic.
     * Extracted for testing.
     */
    @VisibleForTesting
    interface Engine {
        interface RefreshCallbacks {
            void scheduleNextRefresh(@ElapsedRealtimeLong long elapsedRealtimeMillis);

            void submitSuggestion(@NonNull NetworkTimeSuggestion suggestion);
        }

        /**
         * Forces the engine to refresh the network time (for tests). See {@link
         * NetworkTimeUpdateService#forceRefreshForTests()}. This is a blocking call. This method
         * must not schedule any calls.
         */
        boolean forceRefreshForTests(
                @NonNull Network network, @NonNull RefreshCallbacks refreshCallbacks);

        /**
         * Attempts to refresh the network time if required, i.e. if there isn't a recent-enough
         * network time available. It must also schedule the next call. This is a blocking call.
         *
         * @param network the network to use
         * @param reason the reason for the refresh (for logging)
         */
        void refreshIfRequiredAndReschedule(@NonNull Network network, @NonNull String reason,
                @NonNull RefreshCallbacks refreshCallbacks);

        void dump(@NonNull PrintWriter pw);
    }

    @VisibleForTesting
    static class EngineImpl implements Engine {

        /**
         * A log that records the decisions to fetch a network time update.
         * This is logged in bug reports to assist with debugging issues with network time
         * suggestions.
         */
        @NonNull
        private final LocalLog mLocalDebugLog = new LocalLog(30, false /* useLocalTimestamps */);

        private final int mNormalPollingIntervalMillis;
        private final int mShortPollingIntervalMillis;
        private final int mTryAgainTimesMax;
        private final NtpTrustedTime mNtpTrustedTime;

        /**
         * Keeps track of successive time refresh failures have occurred. This is reset to zero when
         * time refresh is successful or if the number exceeds (a non-negative) {@link
         * #mTryAgainTimesMax}.
         */
        @GuardedBy("this")
        private int mTryAgainCounter;

        private final Supplier<Long> mElapsedRealtimeMillisSupplier;

        @VisibleForTesting
        EngineImpl(@NonNull Supplier<Long> elapsedRealtimeMillisSupplier,
                int normalPollingIntervalMillis, int shortPollingIntervalMillis,
                int tryAgainTimesMax, @NonNull NtpTrustedTime ntpTrustedTime) {
            mElapsedRealtimeMillisSupplier = Objects.requireNonNull(elapsedRealtimeMillisSupplier);
            mNormalPollingIntervalMillis = normalPollingIntervalMillis;
            mShortPollingIntervalMillis = shortPollingIntervalMillis;
            mTryAgainTimesMax = tryAgainTimesMax;
            mNtpTrustedTime = Objects.requireNonNull(ntpTrustedTime);
        }

        @Override
        public boolean forceRefreshForTests(
                @NonNull Network network, @NonNull RefreshCallbacks refreshCallbacks) {
            boolean success = mNtpTrustedTime.forceRefresh(network);
            logToDebugAndDumpsys("forceRefreshForTests: success=" + success);

            if (success) {
                makeNetworkTimeSuggestion(mNtpTrustedTime.getCachedTimeResult(),
                        "EngineImpl.forceRefreshForTests()", refreshCallbacks);
            }
            return success;
        }

        @Override
        public void refreshIfRequiredAndReschedule(
                @NonNull Network network, @NonNull String reason,
                @NonNull RefreshCallbacks refreshCallbacks) {
            long currentElapsedRealtimeMillis = mElapsedRealtimeMillisSupplier.get();

            final int maxNetworkTimeAgeMillis = mNormalPollingIntervalMillis;
            // Force an NTP fix when outdated
            NtpTrustedTime.TimeResult initialTimeResult = mNtpTrustedTime.getCachedTimeResult();
            if (calculateTimeResultAgeMillis(initialTimeResult, currentElapsedRealtimeMillis)
                    >= maxNetworkTimeAgeMillis) {
                if (DBG) Log.d(TAG, "Stale NTP fix; forcing refresh using network=" + network);
                boolean successful = mNtpTrustedTime.forceRefresh(network);
                if (successful) {
                    synchronized (this) {
                        mTryAgainCounter = 0;
                    }
                } else {
                    String logMsg = "forceRefresh() returned false:"
                            + " initialTimeResult=" + initialTimeResult
                            + ", currentElapsedRealtimeMillis=" + currentElapsedRealtimeMillis;
                    logToDebugAndDumpsys(logMsg);
                }
            }

            synchronized (this) {
                long nextPollDelayMillis;
                NtpTrustedTime.TimeResult latestTimeResult = mNtpTrustedTime.getCachedTimeResult();
                if (calculateTimeResultAgeMillis(latestTimeResult, currentElapsedRealtimeMillis)
                        < maxNetworkTimeAgeMillis) {
                    // Obtained fresh fix; schedule next normal update
                    nextPollDelayMillis = mNormalPollingIntervalMillis
                            - latestTimeResult.getAgeMillis(currentElapsedRealtimeMillis);

                    makeNetworkTimeSuggestion(latestTimeResult, reason, refreshCallbacks);
                } else {
                    // No fresh fix; schedule retry
                    mTryAgainCounter++;
                    if (mTryAgainTimesMax < 0 || mTryAgainCounter <= mTryAgainTimesMax) {
                        nextPollDelayMillis = mShortPollingIntervalMillis;
                    } else {
                        // Try much later
                        mTryAgainCounter = 0;

                        nextPollDelayMillis = mNormalPollingIntervalMillis;
                    }
                }
                long nextRefreshElapsedRealtimeMillis =
                        currentElapsedRealtimeMillis + nextPollDelayMillis;
                refreshCallbacks.scheduleNextRefresh(nextRefreshElapsedRealtimeMillis);

                logToDebugAndDumpsys("refreshIfRequiredAndReschedule:"
                        + " network=" + network
                        + ", reason=" + reason
                        + ", currentElapsedRealtimeMillis=" + currentElapsedRealtimeMillis
                        + ", initialTimeResult=" + initialTimeResult
                        + ", latestTimeResult=" + latestTimeResult
                        + ", mTryAgainCounter=" + mTryAgainCounter
                        + ", nextPollDelayMillis=" + nextPollDelayMillis
                        + ", nextRefreshElapsedRealtimeMillis="
                        + Duration.ofMillis(nextRefreshElapsedRealtimeMillis)
                        + " (" + nextRefreshElapsedRealtimeMillis + ")");
            }
        }

        private static long calculateTimeResultAgeMillis(
                @Nullable TimeResult timeResult,
                @ElapsedRealtimeLong long currentElapsedRealtimeMillis) {
            return timeResult == null ? Long.MAX_VALUE
                    : timeResult.getAgeMillis(currentElapsedRealtimeMillis);
        }

        /** Suggests the time to the time detector. It may choose use it to set the system clock. */
        private void makeNetworkTimeSuggestion(@NonNull TimeResult ntpResult,
                @NonNull String debugInfo, @NonNull RefreshCallbacks refreshCallbacks) {
            UnixEpochTime timeSignal = new UnixEpochTime(
                    ntpResult.getElapsedRealtimeMillis(), ntpResult.getTimeMillis());
            NetworkTimeSuggestion timeSuggestion =
                    new NetworkTimeSuggestion(timeSignal, ntpResult.getUncertaintyMillis());
            timeSuggestion.addDebugInfo(debugInfo);
            timeSuggestion.addDebugInfo(ntpResult.toString());
            refreshCallbacks.submitSuggestion(timeSuggestion);
        }

        @Override
        public void dump(PrintWriter pw) {
            IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
            ipw.println("mNormalPollingIntervalMillis=" + mNormalPollingIntervalMillis);
            ipw.println("mShortPollingIntervalMillis=" + mShortPollingIntervalMillis);
            ipw.println("mTryAgainTimesMax=" + mTryAgainTimesMax);

            synchronized (this) {
                ipw.println("mTryAgainCounter=" + mTryAgainCounter);
            }
            ipw.println();

            ipw.println("NtpTrustedTime:");
            ipw.increaseIndent();
            mNtpTrustedTime.dump(ipw);
            ipw.decreaseIndent();
            ipw.println();

            ipw.println("Debug log:");
            ipw.increaseIndent();
            mLocalDebugLog.dump(ipw);
            ipw.decreaseIndent();
            ipw.println();
        }

        private void logToDebugAndDumpsys(String logMsg) {
            if (DBG) {
                Log.d(TAG, logMsg);
            }
            mLocalDebugLog.log(logMsg);
        }
    }
}
