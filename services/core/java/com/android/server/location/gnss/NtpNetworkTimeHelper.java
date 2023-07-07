/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.LocalLog;
import android.util.Log;
import android.util.NtpTrustedTime;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * Handles injecting network time to GNSS by explicitly making NTP requests when needed.
 */
class NtpNetworkTimeHelper extends NetworkTimeHelper {

    private static final String TAG = "NtpNetworkTimeHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // states for injecting ntp
    private static final int STATE_PENDING_NETWORK = 0;
    private static final int STATE_RETRIEVING_AND_INJECTING = 1;
    private static final int STATE_IDLE = 2;

    // how often to request NTP time, in milliseconds
    // current setting 24 hours
    @VisibleForTesting
    static final long NTP_INTERVAL = 24 * 60 * 60 * 1000;

    // how long to wait if we have a network error in NTP
    // the initial value of the exponential backoff
    // current setting - 5 minutes
    @VisibleForTesting
    static final long RETRY_INTERVAL = 5 * 60 * 1000;
    // how long to wait if we have a network error in NTP
    // the max value of the exponential backoff
    // current setting - 4 hours
    private static final long MAX_RETRY_INTERVAL = 4 * 60 * 60 * 1000;

    private static final long WAKELOCK_TIMEOUT_MILLIS = 60 * 1000;
    private static final String WAKELOCK_KEY = "NtpTimeHelper";

    private final LocalLog mDumpLog = new LocalLog(10, /*useLocalTimestamps=*/false);

    @GuardedBy("this")
    private final ExponentialBackOff mNtpBackOff = new ExponentialBackOff(RETRY_INTERVAL,
            MAX_RETRY_INTERVAL);

    private final ConnectivityManager mConnMgr;
    private final NtpTrustedTime mNtpTime;
    private final WakeLock mWakeLock;
    private final Handler mHandler;

    private final InjectTimeCallback mCallback;

    // flags to trigger NTP when network becomes available
    // initialized to STATE_PENDING_NETWORK so we do NTP when the network comes up after booting
    @GuardedBy("this")
    private int mInjectNtpTimeState = STATE_PENDING_NETWORK;

    // Enables periodic time injection in addition to injection for other reasons.
    @GuardedBy("this")
    private boolean mPeriodicTimeInjection;

    @VisibleForTesting
    NtpNetworkTimeHelper(Context context, Looper looper, InjectTimeCallback callback,
            NtpTrustedTime ntpTime) {
        mConnMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mCallback = callback;
        mNtpTime = ntpTime;
        mHandler = new Handler(looper);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
    }

    NtpNetworkTimeHelper(Context context, Looper looper, InjectTimeCallback callback) {
        this(context, looper, callback, NtpTrustedTime.getInstance(context));
    }

    @Override
    synchronized void setPeriodicTimeInjectionMode(boolean periodicTimeInjectionEnabled) {
        if (periodicTimeInjectionEnabled) {
            mPeriodicTimeInjection = true;
        }
    }

    @Override
    void demandUtcTimeInjection() {
        retrieveAndInjectNtpTime("demandUtcTimeInjection");
    }

    @Override
    synchronized void onNetworkAvailable() {
        if (mInjectNtpTimeState == STATE_PENDING_NETWORK) {
            retrieveAndInjectNtpTime("onNetworkAvailable");
        }
    }

    @Override
    void dump(PrintWriter pw) {
        pw.println("NtpNetworkTimeHelper:");

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.increaseIndent();
        synchronized (this) {
            ipw.println("mInjectNtpTimeState=" + mInjectNtpTimeState);
            ipw.println("mPeriodicTimeInjection=" + mPeriodicTimeInjection);
            ipw.println("mNtpBackOff=" + mNtpBackOff);
        }

        ipw.println("Debug log:");
        ipw.increaseIndent();
        mDumpLog.dump(ipw);
        ipw.decreaseIndent();

        ipw.println("NtpTrustedTime:");
        ipw.increaseIndent();
        mNtpTime.dump(ipw);
        ipw.decreaseIndent();
    }

    /**
     * @return {@code true} if there is a network available for outgoing connections,
     * {@code false} otherwise.
     */
    private boolean isNetworkConnected() {
        NetworkInfo activeNetworkInfo = mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private synchronized void retrieveAndInjectNtpTime(String reason) {
        if (mInjectNtpTimeState == STATE_RETRIEVING_AND_INJECTING) {
            // already downloading data
            return;
        }
        if (!isNetworkConnected()) {
            // try to inject the cached NTP time
            maybeInjectCachedNtpTime(reason + "[Network not connected]");
            // try again when network is up
            mInjectNtpTimeState = STATE_PENDING_NETWORK;
            return;
        }
        mInjectNtpTimeState = STATE_RETRIEVING_AND_INJECTING;

        // hold wake lock while task runs
        mWakeLock.acquire(WAKELOCK_TIMEOUT_MILLIS);
        new Thread(this::blockingGetNtpTimeAndInject).start();
    }

    /** {@link NtpTrustedTime#forceRefresh} is a blocking network operation. */
    private void blockingGetNtpTimeAndInject() {
        long debugId = SystemClock.elapsedRealtime();
        long delayMillis;

        // force refresh NTP cache when outdated
        boolean refreshSuccess = true;
        NtpTrustedTime.TimeResult ntpResult = mNtpTime.getCachedTimeResult();
        if (ntpResult == null || ntpResult.getAgeMillis() >= NTP_INTERVAL) {
            // Blocking network operation.
            refreshSuccess = mNtpTime.forceRefresh();
        }

        synchronized (this) {
            mInjectNtpTimeState = STATE_IDLE;

            // only update when NTP time is fresh
            // If refreshSuccess is false, cacheAge does not drop down.
            String injectReason = "blockingGetNtpTimeAndInject:"
                    + ", debugId=" + debugId
                    + ", refreshSuccess=" + refreshSuccess;
            if (maybeInjectCachedNtpTime(injectReason)) {
                delayMillis = NTP_INTERVAL;
                mNtpBackOff.reset();
            } else {
                logWarn("maybeInjectCachedNtpTime() returned false");
                delayMillis = mNtpBackOff.nextBackoffMillis();
            }

            if (mPeriodicTimeInjection || !refreshSuccess) {
                String debugMsg = "blockingGetNtpTimeAndInject: Scheduling later NTP retrieval"
                                + ", debugId=" + debugId
                                + ", mPeriodicTimeInjection=" + mPeriodicTimeInjection
                                + ", refreshSuccess=" + refreshSuccess
                                + ", delayMillis=" + delayMillis;
                logDebug(debugMsg);

                // Schedule next NTP injection.
                // Since this is delayed, the wake lock is released right away, and will be held
                // again when the delayed task runs.
                String reason = "scheduled: debugId=" + debugId;
                mHandler.postDelayed(() -> retrieveAndInjectNtpTime(reason), delayMillis);
            }
        }
        // release wake lock held by task
        mWakeLock.release();
    }

    /** Returns true if successfully inject cached NTP time. */
    private synchronized boolean maybeInjectCachedNtpTime(String reason) {
        NtpTrustedTime.TimeResult ntpResult = mNtpTime.getCachedTimeResult();
        if (ntpResult == null || ntpResult.getAgeMillis() >= NTP_INTERVAL) {
            String debugMsg = "maybeInjectCachedNtpTime: Not injecting latest NTP time"
                    + ", reason=" + reason
                    + ", ntpResult=" + ntpResult;
            logDebug(debugMsg);

            return false;
        }

        long unixEpochTimeMillis = ntpResult.getTimeMillis();
        long currentTimeMillis = System.currentTimeMillis();
        String debugMsg = "maybeInjectCachedNtpTime: Injecting latest NTP time"
                + ", reason=" + reason
                + ", ntpResult=" + ntpResult
                + ", System time offset millis=" + (unixEpochTimeMillis - currentTimeMillis);
        logDebug(debugMsg);

        long timeReferenceMillis = ntpResult.getElapsedRealtimeMillis();
        int uncertaintyMillis = ntpResult.getUncertaintyMillis();
        mHandler.post(() -> mCallback.injectTime(unixEpochTimeMillis, timeReferenceMillis,
                uncertaintyMillis));
        return true;
    }

    private void logWarn(String logMsg) {
        mDumpLog.log(logMsg);
        Log.e(TAG, logMsg);
    }

    private void logDebug(String debugMsg) {
        mDumpLog.log(debugMsg);
        if (DEBUG) {
            Log.d(TAG, debugMsg);
        }
    }
}
