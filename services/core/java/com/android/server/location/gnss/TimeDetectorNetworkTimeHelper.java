/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.location.gnss;

import android.annotation.DurationMillisLong;
import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.time.UnixEpochTime;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.timedetector.NetworkTimeSuggestion;
import com.android.server.timedetector.TimeDetectorInternal;
import com.android.server.timezonedetector.StateChangeListener;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Handles injecting network time to GNSS by using information from the platform time detector.
 */
public class TimeDetectorNetworkTimeHelper extends NetworkTimeHelper {

    /** Returns {@code true} if the TimeDetectorNetworkTimeHelper is being used. */
    public static boolean isInUse() {
        return NetworkTimeHelper.USE_TIME_DETECTOR_IMPL;
    }

    /**
     * An interface exposed for easier testing that the surrounding class uses for interacting with
     * platform services, handlers, etc.
     */
    interface Environment {

        /**
         * Returns the current elapsed realtime value. The same as calling {@link
         * SystemClock#elapsedRealtime()} but easier to fake in tests.
         */
        @ElapsedRealtimeLong long elapsedRealtimeMillis();

        /**
         * Returns the latest / best network time available from the time detector service.
         */
        @Nullable NetworkTimeSuggestion getLatestNetworkTime();

        /**
         * Sets a listener that will receive a callback when the value returned by {@link
         * #getLatestNetworkTime()} has changed.
         */
        void setNetworkTimeUpdateListener(StateChangeListener stateChangeListener);

        /**
         * Requests asynchronous execution of {@link
         * TimeDetectorNetworkTimeHelper#queryAndInjectNetworkTime}, to execute as soon as possible.
         * The thread used is the same as used by {@link #requestDelayedTimeQueryCallback}.
         * Only one immediate callback can be requested at a time; requesting a new immediate
         * callback will clear any previously requested one.
         */
        void requestImmediateTimeQueryCallback(TimeDetectorNetworkTimeHelper helper, String reason);

        /**
         * Requests a delayed call to
         * {@link TimeDetectorNetworkTimeHelper#delayedQueryAndInjectNetworkTime()}.
         * The thread used is the same as used by {@link #requestImmediateTimeQueryCallback}.
         * Only one delayed callback can be scheduled at a time; requesting a new delayed callback
         * will clear any previously requested one.
         */
        void requestDelayedTimeQueryCallback(
                TimeDetectorNetworkTimeHelper helper, @DurationMillisLong long delayMillis);

        /**
         * Clear a delayed time query callback. This has no effect if no delayed callback is
         * currently set.
         */
        void clearDelayedTimeQueryCallback();
    }

    private static final String TAG = "TDNetworkTimeHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /** The maximum age of a network time signal that will be passed to GNSS. */
    @VisibleForTesting
    static final int MAX_NETWORK_TIME_AGE_MILLIS = 24 * 60 * 60 * 1000;

    /**
     * The maximum time that is allowed to pass before a network time signal should be evaluated to
     * be passed to GNSS when mOnDemandTimeInjection == false.
     */
    static final int NTP_REFRESH_INTERVAL_MILLIS = MAX_NETWORK_TIME_AGE_MILLIS;

    private final LocalLog mDumpLog = new LocalLog(10, /*useLocalTimestamps=*/false);

    /** The object the helper uses to interact with other components. */
    @NonNull private final Environment mEnvironment;
    @NonNull private final InjectTimeCallback mInjectTimeCallback;

    /** Set to true if the GNSS engine requested on-demand NTP time injections. */
    @GuardedBy("this")
    private boolean mPeriodicTimeInjectionEnabled;

    /**
     * Set to true when a network time has been injected. Used to ensure that a network time is
     * injected if this object wasn't listening when a network time signal first became available.
     */
    @GuardedBy("this")
    private boolean mNetworkTimeInjected;

    TimeDetectorNetworkTimeHelper(
            @NonNull Environment environment, @NonNull InjectTimeCallback injectTimeCallback) {
        mInjectTimeCallback = Objects.requireNonNull(injectTimeCallback);
        mEnvironment = Objects.requireNonNull(environment);

        // Start listening for new network time updates immediately.
        mEnvironment.setNetworkTimeUpdateListener(this::onNetworkTimeAvailable);
    }

    @Override
    synchronized void setPeriodicTimeInjectionMode(boolean periodicTimeInjectionEnabled) {
        // Periodic time injection has a complicated history. See b/73893222. When it is true, it
        // doesn't mean ONLY send it periodically.
        //
        // periodicTimeInjectionEnabled == true means the GNSS would like to be told the time
        // periodically in addition to all the other triggers (e.g. network available).

        mPeriodicTimeInjectionEnabled = periodicTimeInjectionEnabled;
        if (!periodicTimeInjectionEnabled) {
            // Cancel any previously scheduled periodic query.
            removePeriodicNetworkTimeQuery();
        }

        // Inject the latest network time in all cases if it is available.
        // Calling queryAndInjectNetworkTime() will cause a time signal to be injected if one is
        // available AND will cause the next periodic query to be scheduled.
        String reason = "setPeriodicTimeInjectionMode(" + periodicTimeInjectionEnabled + ")";
        mEnvironment.requestImmediateTimeQueryCallback(this, reason);
    }

    void onNetworkTimeAvailable() {
        // A new network time could become available at any time. Make sure it is passed to GNSS.
        mEnvironment.requestImmediateTimeQueryCallback(this, "onNetworkTimeAvailable");
    }

    @Override
    void onNetworkAvailable() {
        // In the original NetworkTimeHelper implementation, onNetworkAvailable() would cause an NTP
        // refresh to be made if it had previously been blocked by network issues. This
        // implementation generally relies on components associated with the time detector to
        // monitor the network and call onNetworkTimeAvailable() when a time is available. However,
        // it also checks mNetworkTimeInjected in case this component wasn't listening for
        // onNetworkTimeAvailable() when the last one became available.
        synchronized (this) {
            if (!mNetworkTimeInjected) {
                // Guard against ordering issues: This check should ensure that if a network time
                // became available before this class started listening then the initial network
                // time will still be injected.
                mEnvironment.requestImmediateTimeQueryCallback(this, "onNetworkAvailable");
            }
        }
    }

    @Override
    void demandUtcTimeInjection() {
        mEnvironment.requestImmediateTimeQueryCallback(this, "demandUtcTimeInjection");
    }

    // This method should always be invoked on the mEnvironment thread.
    void delayedQueryAndInjectNetworkTime() {
        queryAndInjectNetworkTime("delayedTimeQueryCallback");
    }

    // This method should always be invoked on the mEnvironment thread.
    synchronized void queryAndInjectNetworkTime(@NonNull String reason) {
        NetworkTimeSuggestion latestNetworkTime = mEnvironment.getLatestNetworkTime();

        maybeInjectNetworkTime(latestNetworkTime, reason);

        // Deschedule (if needed) any previously scheduled periodic query.
        removePeriodicNetworkTimeQuery();

        if (mPeriodicTimeInjectionEnabled) {
            int maxDelayMillis = NTP_REFRESH_INTERVAL_MILLIS;
            String debugMsg = "queryAndInjectNtpTime: Scheduling periodic query"
                            + " reason=" + reason
                            + " latestNetworkTime=" + latestNetworkTime
                            + " maxDelayMillis=" + maxDelayMillis;
            logToDumpLog(debugMsg);

            // GNSS is expecting periodic injections, so schedule the next one.
            mEnvironment.requestDelayedTimeQueryCallback(this, maxDelayMillis);
        }
    }

    private long calculateTimeSignalAgeMillis(
            @Nullable NetworkTimeSuggestion networkTimeSuggestion) {
        if (networkTimeSuggestion == null) {
            return Long.MAX_VALUE;
        }

        long suggestionElapsedRealtimeMillis =
                networkTimeSuggestion.getUnixEpochTime().getElapsedRealtimeMillis();
        long currentElapsedRealtimeMillis = mEnvironment.elapsedRealtimeMillis();
        return currentElapsedRealtimeMillis - suggestionElapsedRealtimeMillis;
    }

    @GuardedBy("this")
    private void maybeInjectNetworkTime(
            @Nullable NetworkTimeSuggestion latestNetworkTime, @NonNull String reason) {
        // Historically, time would only be injected if it was under a certain age. This has been
        // kept in case it is assumed by GNSS implementations.
        if (calculateTimeSignalAgeMillis(latestNetworkTime) > MAX_NETWORK_TIME_AGE_MILLIS) {
            String debugMsg = "maybeInjectNetworkTime: Not injecting latest network time"
                    + " latestNetworkTime=" + latestNetworkTime
                    + " reason=" + reason;
            logToDumpLog(debugMsg);
            return;
        }

        UnixEpochTime unixEpochTime = latestNetworkTime.getUnixEpochTime();
        long unixEpochTimeMillis = unixEpochTime.getUnixEpochTimeMillis();
        long currentTimeMillis = System.currentTimeMillis();
        String debugMsg = "maybeInjectNetworkTime: Injecting latest network time"
                + " latestNetworkTime=" + latestNetworkTime
                + " reason=" + reason
                + " System time offset millis=" + (unixEpochTimeMillis - currentTimeMillis);
        logToDumpLog(debugMsg);

        long timeReferenceMillis = unixEpochTime.getElapsedRealtimeMillis();
        int uncertaintyMillis = latestNetworkTime.getUncertaintyMillis();
        mInjectTimeCallback.injectTime(unixEpochTimeMillis, timeReferenceMillis, uncertaintyMillis);
        mNetworkTimeInjected = true;
    }

    @Override
    void dump(@NonNull PrintWriter pw) {
        pw.println("TimeDetectorNetworkTimeHelper:");

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();
        synchronized (this) {
            ipw.println("mPeriodicTimeInjectionEnabled=" + mPeriodicTimeInjectionEnabled);
        }

        ipw.println("Debug log:");
        mDumpLog.dump(ipw);
    }

    private void logToDumpLog(@NonNull String message) {
        mDumpLog.log(message);
        if (DEBUG) {
            Log.d(TAG, message);
        }
    }

    private void removePeriodicNetworkTimeQuery() {
        // De-schedule any previously scheduled refresh. This is idempotent and has no effect if
        // there isn't one.
        mEnvironment.clearDelayedTimeQueryCallback();
    }

    /** The real implementation of {@link Environment} used outside of tests. */
    static class EnvironmentImpl implements Environment {

        /** Used to ensure one scheduled runnable is queued at a time. */
        private final Object mScheduledRunnableToken = new Object();
        /** Used to ensure one immediate runnable is queued at a time. */
        private final Object mImmediateRunnableToken = new Object();
        private final Handler mHandler;
        private final TimeDetectorInternal mTimeDetectorInternal;

        EnvironmentImpl(Looper looper) {
            mHandler = new Handler(looper);
            mTimeDetectorInternal = LocalServices.getService(TimeDetectorInternal.class);
        }

        @Override
        public long elapsedRealtimeMillis() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public NetworkTimeSuggestion getLatestNetworkTime() {
            return mTimeDetectorInternal.getLatestNetworkSuggestion();
        }

        @Override
        public void setNetworkTimeUpdateListener(StateChangeListener stateChangeListener) {
            mTimeDetectorInternal.addNetworkTimeUpdateListener(stateChangeListener);
        }

        @Override
        public void requestImmediateTimeQueryCallback(TimeDetectorNetworkTimeHelper helper,
                String reason) {
            // Ensure only one immediate callback is scheduled at a time. There's no
            // post(Runnable, Object), so we postDelayed() with a zero wait.
            synchronized (this) {
                mHandler.removeCallbacksAndMessages(mImmediateRunnableToken);
                mHandler.postDelayed(() -> helper.queryAndInjectNetworkTime(reason),
                        mImmediateRunnableToken, 0);
            }
        }

        @Override
        public void requestDelayedTimeQueryCallback(TimeDetectorNetworkTimeHelper helper,
                long delayMillis) {
            synchronized (this) {
                clearDelayedTimeQueryCallback();
                mHandler.postDelayed(helper::delayedQueryAndInjectNetworkTime,
                        mScheduledRunnableToken, delayMillis);
            }
        }

        @Override
        public synchronized void clearDelayedTimeQueryCallback() {
            mHandler.removeCallbacksAndMessages(mScheduledRunnableToken);
        }
    }
}
