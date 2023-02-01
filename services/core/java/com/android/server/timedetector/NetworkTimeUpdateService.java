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

        /**
         * The usual interval between refresh attempts. Always used after a successful request.
         *
         * <p>The value also determines whether a network time result is considered fresh.
         * Refreshes only take place from this class when the latest time result is considered too
         * old.
         */
        private final int mNormalPollingIntervalMillis;

        /**
         * A shortened interval between refresh attempts used after a failure to refresh.
         * Always shorter than {@link #mNormalPollingIntervalMillis} and only used when {@link
         * #mTryAgainTimesMax} != 0.
         *
         * <p>This value is also the lower bound for the interval allowed between successive
         * refreshes when the latest time result is missing or too old, e.g. a refresh may not be
         * triggered when network connectivity is restored if the last attempt was too recent.
         */
        private final int mShortPollingIntervalMillis;

        /**
         * The number of times {@link #mShortPollingIntervalMillis} can be used after successive
         * failures before switching back to using {@link #mNormalPollingIntervalMillis} once before
         * repeating. When this value is negative, the refresh algorithm will continue to use {@link
         * #mShortPollingIntervalMillis} until a successful refresh.
         */
        private final int mTryAgainTimesMax;

        private final NtpTrustedTime mNtpTrustedTime;

        /**
         * Records the time of the last refresh attempt (successful or otherwise) by this service.
         * This is used when scheduling the next refresh attempt. In cases where {@link
         * #refreshIfRequiredAndReschedule} is called too frequently, this will prevent each call
         * resulting in a network request. See also {@link #mShortPollingIntervalMillis}.
         *
         * <p>Time servers are a shared resource and so Android should avoid loading them.
         * Generally, a refresh attempt will succeed and the service won't need to make further
         * requests and this field will not limit requests.
         */
        // This field is only updated and accessed by the mHandler thread (except dump()).
        @GuardedBy("this")
        @ElapsedRealtimeLong
        private Long mLastRefreshAttemptElapsedRealtimeMillis;

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
            if (shortPollingIntervalMillis > normalPollingIntervalMillis) {
                throw new IllegalArgumentException(String.format(
                        "shortPollingIntervalMillis (%s) > normalPollingIntervalMillis (%s)",
                        shortPollingIntervalMillis, normalPollingIntervalMillis));
            }
            mNormalPollingIntervalMillis = normalPollingIntervalMillis;
            mShortPollingIntervalMillis = shortPollingIntervalMillis;
            mTryAgainTimesMax = tryAgainTimesMax;
            mNtpTrustedTime = Objects.requireNonNull(ntpTrustedTime);
        }

        @Override
        public boolean forceRefreshForTests(
                @NonNull Network network, @NonNull RefreshCallbacks refreshCallbacks) {
            boolean refreshSuccessful = tryRefresh(network);
            logToDebugAndDumpsys("forceRefreshForTests: refreshSuccessful=" + refreshSuccessful);

            if (refreshSuccessful) {
                makeNetworkTimeSuggestion(mNtpTrustedTime.getCachedTimeResult(),
                        "EngineImpl.forceRefreshForTests()", refreshCallbacks);
            }
            return refreshSuccessful;
        }

        @Override
        public void refreshIfRequiredAndReschedule(
                @NonNull Network network, @NonNull String reason,
                @NonNull RefreshCallbacks refreshCallbacks) {
            // Attempt to refresh the network time if there is no latest time result, or if the
            // latest time result is considered too old.
            NtpTrustedTime.TimeResult initialTimeResult = mNtpTrustedTime.getCachedTimeResult();
            boolean shouldAttemptRefresh;
            synchronized (this) {
                long currentElapsedRealtimeMillis = mElapsedRealtimeMillisSupplier.get();

                // calculateTimeResultAgeMillis() safely handles a null initialTimeResult.
                long timeResultAgeMillis = calculateTimeResultAgeMillis(
                        initialTimeResult, currentElapsedRealtimeMillis);
                shouldAttemptRefresh =
                        timeResultAgeMillis >= mNormalPollingIntervalMillis
                        && isRefreshAllowed(currentElapsedRealtimeMillis);
            }

            boolean refreshSuccessful = false;
            if (shouldAttemptRefresh) {
                // This is a blocking call. Deliberately invoked without holding the "this" monitor
                // to avoid blocking logic that wants to use the "this" monitor.
                refreshSuccessful = tryRefresh(network);
            }

            synchronized (this) {
                // Manage mTryAgainCounter.
                if (shouldAttemptRefresh) {
                    if (refreshSuccessful) {
                        // Reset failure tracking.
                        mTryAgainCounter = 0;
                    } else {
                        if (mTryAgainTimesMax < 0) {
                            // When mTryAgainTimesMax is negative there's no enforced maximum and
                            // short intervals should be used until a successful refresh. Setting
                            // mTryAgainCounter to 1 is sufficient for the interval calculations
                            // below. There's no need to increment.
                            mTryAgainCounter = 1;
                        } else {
                            mTryAgainCounter++;
                            if (mTryAgainCounter > mTryAgainTimesMax) {
                                mTryAgainCounter = 0;
                            }
                        }
                    }
                }

                // currentElapsedRealtimeMillis is used to evaluate ages and refresh scheduling
                // below. Capturing this after a possible successful refresh ensures that latest
                // time result ages will be >= 0.
                long currentElapsedRealtimeMillis = mElapsedRealtimeMillisSupplier.get();

                // This section of code deliberately doesn't assume it is the only component using
                // mNtpTrustedTime to obtain NTP times: another component in the same process could
                // be gathering NTP signals (which then won't have been suggested to the time
                // detector).
                // TODO(b/222295093): Make this class the sole owner of mNtpTrustedTime and
                //  simplify / reduce duplicate suggestions.
                NtpTrustedTime.TimeResult latestTimeResult = mNtpTrustedTime.getCachedTimeResult();
                long latestTimeResultAgeMillis = calculateTimeResultAgeMillis(
                        latestTimeResult, currentElapsedRealtimeMillis);

                // Suggest the latest time result to the time detector if it is fresh regardless of
                // whether refresh happened above.
                if (latestTimeResultAgeMillis < mNormalPollingIntervalMillis) {
                    // We assume the time detector service will detect duplicate suggestions and not
                    // do more work than it has to, so no need to avoid making duplicate
                    // suggestions.
                    makeNetworkTimeSuggestion(latestTimeResult, reason, refreshCallbacks);
                }

                // (Re)schedule the next refresh based on the latest state.
                // Determine which refresh delay to use by using the current value of
                // mTryAgainCounter. The refresh delay is applied to a different point in time
                // depending on whether the latest available time result (if any) is still
                // considered fresh to ensure the delay acts correctly.
                long refreshDelayMillis = mTryAgainCounter > 0
                        ? mShortPollingIntervalMillis : mNormalPollingIntervalMillis;
                long nextRefreshElapsedRealtimeMillis;
                if (latestTimeResultAgeMillis < mNormalPollingIntervalMillis) {
                    // The latest time result is fresh, use it to determine when next to refresh.
                    nextRefreshElapsedRealtimeMillis =
                            latestTimeResult.getElapsedRealtimeMillis() + refreshDelayMillis;
                } else if (mLastRefreshAttemptElapsedRealtimeMillis != null) {
                    // The latest time result is missing or old and still needs to be refreshed.
                    // mLastRefreshAttemptElapsedRealtimeMillis, which should always be set by this
                    // point because there's no fresh time result, should be very close to
                    // currentElapsedRealtimeMillis unless the refresh was not allowed.
                    nextRefreshElapsedRealtimeMillis =
                            mLastRefreshAttemptElapsedRealtimeMillis + refreshDelayMillis;
                } else {
                    // This should not happen: mLastRefreshAttemptElapsedRealtimeMillis should
                    // always be non-null by this point.
                    logToDebugAndDumpsys(
                            "mLastRefreshAttemptElapsedRealtimeMillis unexpectedly missing."
                                    + " Scheduling using currentElapsedRealtimeMillis");
                    nextRefreshElapsedRealtimeMillis =
                            currentElapsedRealtimeMillis + refreshDelayMillis;
                }
                refreshCallbacks.scheduleNextRefresh(nextRefreshElapsedRealtimeMillis);

                logToDebugAndDumpsys("refreshIfRequiredAndReschedule:"
                        + " network=" + network
                        + ", reason=" + reason
                        + ", initialTimeResult=" + initialTimeResult
                        + ", shouldAttemptRefresh=" + shouldAttemptRefresh
                        + ", refreshSuccessful=" + refreshSuccessful
                        + ", currentElapsedRealtimeMillis="
                        + formatElapsedRealtimeMillis(currentElapsedRealtimeMillis)
                        + ", latestTimeResult=" + latestTimeResult
                        + ", mTryAgainCounter=" + mTryAgainCounter
                        + ", refreshDelayMillis=" + refreshDelayMillis
                        + ", nextRefreshElapsedRealtimeMillis="
                        + formatElapsedRealtimeMillis(nextRefreshElapsedRealtimeMillis));
            }
        }

        private static String formatElapsedRealtimeMillis(
                @ElapsedRealtimeLong long elapsedRealtimeMillis) {
            return Duration.ofMillis(elapsedRealtimeMillis) + " (" + elapsedRealtimeMillis + ")";
        }

        private static long calculateTimeResultAgeMillis(
                @Nullable TimeResult timeResult,
                @ElapsedRealtimeLong long currentElapsedRealtimeMillis) {
            return timeResult == null ? Long.MAX_VALUE
                    : timeResult.getAgeMillis(currentElapsedRealtimeMillis);
        }

        @GuardedBy("this")
        private boolean isRefreshAllowed(@ElapsedRealtimeLong long currentElapsedRealtimeMillis) {
            if (mLastRefreshAttemptElapsedRealtimeMillis == null) {
                return true;
            }
            // Use the second meaning of mShortPollingIntervalMillis: to determine the minimum time
            // allowed after an unsuccessful refresh before another can be attempted.
            long nextRefreshAllowedElapsedRealtimeMillis =
                    mLastRefreshAttemptElapsedRealtimeMillis + mShortPollingIntervalMillis;
            return currentElapsedRealtimeMillis >= nextRefreshAllowedElapsedRealtimeMillis;
        }

        private boolean tryRefresh(@NonNull Network network) {
            long currentElapsedRealtimeMillis = mElapsedRealtimeMillisSupplier.get();
            synchronized (this) {
                mLastRefreshAttemptElapsedRealtimeMillis = currentElapsedRealtimeMillis;
            }
            return mNtpTrustedTime.forceRefresh(network);
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
                String lastRefreshAttemptValue = mLastRefreshAttemptElapsedRealtimeMillis == null
                        ? "null"
                        : formatElapsedRealtimeMillis(mLastRefreshAttemptElapsedRealtimeMillis);
                ipw.println("mLastRefreshAttemptElapsedRealtimeMillis=" + lastRefreshAttemptValue);
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
