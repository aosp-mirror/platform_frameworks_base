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
import android.app.time.UnixEpochTime;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
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
 * <p>When a valid network time is available, the network time is always suggested to the {@link
 * com.android.server.timedetector.TimeDetectorService} where it may be used to set the device
 * system clock, depending on user settings and what other signals are available.
 */
public class NetworkTimeUpdateService extends Binder {

    private static final String TAG = "NetworkTimeUpdateService";
    private static final boolean DBG = false;

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
        mRefreshCallbacks = new Engine.RefreshCallbacks() {
            private final AlarmManager.OnAlarmListener mOnAlarmListener =
                    new ScheduledRefreshAlarmListener();

            @Override
            public void scheduleNextRefresh(@ElapsedRealtimeLong long elapsedRealtimeMillis) {
                alarmManager.cancel(mOnAlarmListener);

                String alarmTag = "NetworkTimeUpdateService.POLL";
                Handler handler = null; // Use the main thread
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME, elapsedRealtimeMillis, alarmTag,
                        mOnAlarmListener, handler);
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
        // Listen for network connectivity changes.
        NetworkConnectivityCallback networkConnectivityCallback = new NetworkConnectivityCallback();
        mCM.registerDefaultNetworkCallback(networkConnectivityCallback, mHandler);

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
        Network network;
        synchronized (mLock) {
            network = mDefaultNetwork;
        }

        mWakeLock.acquire();
        try {
            mEngine.refreshAndRescheduleIfRequired(network, reason, mRefreshCallbacks);
        } finally {
            mWakeLock.release();
        }
    }

    private class ScheduledRefreshAlarmListener implements AlarmManager.OnAlarmListener, Runnable {

        @Override
        public void onAlarm() {
            // The OnAlarmListener has to complete quickly or an ANR will be triggered by the
            // platform regardless of the receiver thread used. Instead of blocking the receiver
            // thread, the long-running / blocking work is posted to mHandler to allow onAlarm()
            // to return immediately.
            mHandler.post(this);
        }

        @Override
        public void run() {
            onPollNetworkTime("scheduled refresh");
        }
    }

    // All callbacks will be invoked using mHandler because of how the callback is registered.
    private class NetworkConnectivityCallback extends ConnectivityManager.NetworkCallback {
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
         * Checks if the user prefers to automatically set the device's system clock time.
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
     * The interface the service uses to interact with the network time refresh logic.
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
         * @param network the network to use, or null if no network is available
         * @param reason the reason for the refresh (for logging)
         */
        void refreshAndRescheduleIfRequired(@Nullable Network network, @NonNull String reason,
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
         * Records the elapsed realtime of the last refresh attempt (successful or otherwise) by
         * this service. This is used when scheduling the next refresh attempt. In cases where
         * {@link #refreshAndRescheduleIfRequired} is called too frequently, this will prevent each
         * call resulting in a network request. See also {@link #mShortPollingIntervalMillis}.
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
                TimeResult cachedTimeResult = mNtpTrustedTime.getCachedTimeResult();
                if (cachedTimeResult == null) {
                    logToDebugAndDumpsys(
                            "forceRefreshForTests: cachedTimeResult unexpectedly null");
                } else {
                    makeNetworkTimeSuggestion(cachedTimeResult,
                            "EngineImpl.forceRefreshForTests()", refreshCallbacks);
                }
            }
            return refreshSuccessful;
        }

        @Override
        public void refreshAndRescheduleIfRequired(
                @Nullable Network network, @NonNull String reason,
                @NonNull RefreshCallbacks refreshCallbacks) {
            if (network == null) {
                // If we don't have any default network, don't do anything: When a new network
                // is available then this method will be called again.
                logToDebugAndDumpsys("refreshIfRequiredAndReschedule:"
                        + " reason=" + reason
                        + ": No default network available. No refresh attempted and no next"
                        + " attempt scheduled.");
                return;
            }

            // Step 1: Work out if the latest time result, if any, needs to be refreshed and handle
            // the refresh.

            // A refresh should be attempted if there is no latest time result, or if the latest
            // time result is considered too old.
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
                // to avoid blocking other logic that wants to use the "this" monitor, e.g. dump().
                refreshSuccessful = tryRefresh(network);
            }

            synchronized (this) {
                // This section of code deliberately doesn't assume it is the only component using
                // the NtpTrustedTime singleton to obtain NTP times: another component in the same
                // process could be gathering NTP signals (which then won't have been suggested to
                // the time detector).
                // TODO(b/222295093): Make this class the sole user of the NtpTrustedTime singleton
                //  and simplify / reduce duplicate suggestions and other logic.
                NtpTrustedTime.TimeResult latestTimeResult = mNtpTrustedTime.getCachedTimeResult();

                // currentElapsedRealtimeMillis is used to evaluate ages and refresh scheduling
                // below. Capturing this after obtaining the cached time result ensures that latest
                // time result ages will be >= 0.
                long currentElapsedRealtimeMillis = mElapsedRealtimeMillisSupplier.get();

                long latestTimeResultAgeMillis = calculateTimeResultAgeMillis(
                        latestTimeResult, currentElapsedRealtimeMillis);

                // Step 2: Set mTryAgainCounter.
                //   + == 0: The last attempt was successful OR the latest time result is acceptable
                //           OR the mTryAgainCounter exceeded mTryAgainTimesMax and has been reset
                //           to 0. In all these cases the normal refresh interval should be used.
                //   + > 0: The last refresh attempt was unsuccessful. Some number of retries are
                //          allowed using the short interval depending on mTryAgainTimesMax.
                if (shouldAttemptRefresh) {
                    if (refreshSuccessful) {
                        mTryAgainCounter = 0;
                    } else {
                        if (mTryAgainTimesMax < 0) {
                            // When mTryAgainTimesMax is negative there's no enforced maximum and
                            // short intervals should be used until a successful refresh. Setting
                            // mTryAgainCounter to 1 is sufficient for the interval calculations
                            // below, i.e. there's no need to increment.
                            mTryAgainCounter = 1;
                        } else {
                            mTryAgainCounter++;
                            if (mTryAgainCounter > mTryAgainTimesMax) {
                                mTryAgainCounter = 0;
                            }
                        }
                    }
                }
                if (latestTimeResultAgeMillis < mNormalPollingIntervalMillis) {
                    // The latest time result may indicate a successful refresh has been achieved by
                    // another user of the NtpTrustedTime singleton. This could be an "else if", but
                    // this is deliberately done defensively in all cases to maintain the invariant
                    // that mTryAgainCounter will be 0 if the latest time result is currently ok.
                    mTryAgainCounter = 0;
                }

                // Step 3: Suggest the latest time result to the time detector if it is fresh
                // regardless of whether a refresh happened / succeeded above. The time detector
                // service can detect duplicate suggestions and not do more work than it has to, so
                // there is no need to avoid making duplicate suggestions.
                if (latestTimeResultAgeMillis < mNormalPollingIntervalMillis) {
                    makeNetworkTimeSuggestion(latestTimeResult, reason, refreshCallbacks);
                }

                // Step 4: (Re)schedule the next refresh attempt based on the latest state.

                // Determine which refresh attempt delay to use by using the current value of
                // mTryAgainCounter.
                long refreshAttemptDelayMillis = mTryAgainCounter > 0
                        ? mShortPollingIntervalMillis : mNormalPollingIntervalMillis;

                // The refresh attempt delay is applied to a different point in time depending on
                // whether a refresh attempt is overdue to ensure the refresh attempt scheduling
                // acts correctly / safely, i.e. won't schedule actions for immediate execution or
                // in the past.
                long nextRefreshElapsedRealtimeMillis;
                if (latestTimeResultAgeMillis < refreshAttemptDelayMillis) {
                    // The latestTimeResultAgeMillis and refreshAttemptDelayMillis indicate a
                    // refresh attempt is not yet due.  This branch uses the elapsed realtime of the
                    // latest time result to calculate when the latest time result will become too
                    // old and the next refresh attempt will be due.
                    //
                    // Possibilities:
                    //   + A refresh was attempted and successful, mTryAgainCounter will be set
                    //     to 0, refreshAttemptDelayMillis == mNormalPollingIntervalMillis, and this
                    //     branch will execute.
                    //   + No refresh was attempted, but something else refreshed the latest time
                    //     result held by the NtpTrustedTime.
                    //
                    // If a refresh was attempted but was unsuccessful, latestTimeResultAgeMillis >=
                    // mNormalPollingIntervalMillis (because otherwise it wouldn't be attempted),
                    // this branch won't be executed, and the one below will be instead.
                    nextRefreshElapsedRealtimeMillis =
                            latestTimeResult.getElapsedRealtimeMillis() + refreshAttemptDelayMillis;
                } else if (mLastRefreshAttemptElapsedRealtimeMillis != null) {
                    // This branch is executed when the latest time result is missing, or it's older
                    // than refreshAttemptDelayMillis. There may already have been attempts to
                    // refresh the network time that have failed, so the important point for this
                    // branch is not how old the latest time result is, but when the last refresh
                    // attempt took place:
                    //   + If a refresh was just attempted (and failed), then
                    //     mLastRefreshAttemptElapsedRealtimeMillis will be close to
                    //     currentElapsedRealtimeMillis.
                    //   + If a refresh was not just attempted, for a refresh not to have been
                    //     attempted EITHER:
                    //     + The latest time result must be < mNormalPollingIntervalMillis ago
                    //       (would be handled by the branch above)
                    //     + A refresh wasn't allowed because {time since last refresh attempt}
                    //       < mShortPollingIntervalMillis, so
                    //       (mLastRefreshAttemptElapsedRealtimeMillis + refreshAttemptDelayMillis)
                    //       would have to be in the future regardless of the
                    //       refreshAttemptDelayMillis value. This ignores the execution time
                    //       between the "current time" used to work out whether a refresh needed to
                    //       happen, and "current time" used to compute the last time result age,
                    //       but a single short interval shouldn't matter.
                    nextRefreshElapsedRealtimeMillis =
                            mLastRefreshAttemptElapsedRealtimeMillis + refreshAttemptDelayMillis;
                } else {
                    // This branch should never execute: mLastRefreshAttemptElapsedRealtimeMillis
                    // should always be non-null because a refresh should always be attempted at
                    // least once above. Regardelss, the calculation below should result in safe
                    // scheduling behavior.
                    String logMsg = "mLastRefreshAttemptElapsedRealtimeMillis unexpectedly missing."
                            + " Scheduling using currentElapsedRealtimeMillis";
                    Log.w(TAG, logMsg);
                    logToDebugAndDumpsys(logMsg);
                    nextRefreshElapsedRealtimeMillis =
                            currentElapsedRealtimeMillis + refreshAttemptDelayMillis;
                }

                // Defensive coding to guard against bad scheduling / logic errors above: Try to
                // ensure that alarms aren't scheduled in the past.
                if (nextRefreshElapsedRealtimeMillis <= currentElapsedRealtimeMillis) {
                    String logMsg = "nextRefreshElapsedRealtimeMillis is a time in the past."
                            + " Scheduling using currentElapsedRealtimeMillis instead";
                    Log.w(TAG, logMsg);
                    logToDebugAndDumpsys(logMsg);
                    nextRefreshElapsedRealtimeMillis =
                            currentElapsedRealtimeMillis + refreshAttemptDelayMillis;
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
                        + ", refreshAttemptDelayMillis=" + refreshAttemptDelayMillis
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

        /**
         * Attempts a network time refresh. Updates {@link
         * #mLastRefreshAttemptElapsedRealtimeMillis} regardless of the outcome and returns whether
         * the attempt was successful. The latest successful refresh result can be found in {@link
         * NtpTrustedTime#getCachedTimeResult()}.
         */
        private boolean tryRefresh(@NonNull Network network) {
            long currentElapsedRealtimeMillis = mElapsedRealtimeMillisSupplier.get();
            synchronized (this) {
                mLastRefreshAttemptElapsedRealtimeMillis = currentElapsedRealtimeMillis;
            }
            return mNtpTrustedTime.forceRefresh(network);
        }

        /**
         * Suggests the network time to the time detector. It may choose use it to set the system
         * clock.
         */
        private void makeNetworkTimeSuggestion(@NonNull TimeResult timeResult,
                @NonNull String debugInfo, @NonNull RefreshCallbacks refreshCallbacks) {
            UnixEpochTime timeSignal = new UnixEpochTime(
                    timeResult.getElapsedRealtimeMillis(), timeResult.getTimeMillis());
            NetworkTimeSuggestion timeSuggestion =
                    new NetworkTimeSuggestion(timeSignal, timeResult.getUncertaintyMillis());
            timeSuggestion.addDebugInfo(debugInfo);
            timeSuggestion.addDebugInfo(timeResult.toString());
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
