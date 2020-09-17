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
import android.util.Log;
import android.util.NtpTrustedTime;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.location.gnss.ExponentialBackOff;

import java.util.Date;

/**
 * Handles inject NTP time to GNSS.
 *
 * <p>The client is responsible to call {@link #onNetworkAvailable()} when network is available
 * for retrieving NTP Time.
 */
class NtpTimeHelper {

    private static final String TAG = "NtpTimeHelper";
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

    private final ExponentialBackOff mNtpBackOff = new ExponentialBackOff(RETRY_INTERVAL,
            MAX_RETRY_INTERVAL);

    private final ConnectivityManager mConnMgr;
    private final NtpTrustedTime mNtpTime;
    private final WakeLock mWakeLock;
    private final Handler mHandler;

    @GuardedBy("this")
    private final InjectNtpTimeCallback mCallback;

    // flags to trigger NTP when network becomes available
    // initialized to STATE_PENDING_NETWORK so we do NTP when the network comes up after booting
    @GuardedBy("this")
    private int mInjectNtpTimeState = STATE_PENDING_NETWORK;

    // set to true if the GPS engine requested on-demand NTP time requests
    @GuardedBy("this")
    private boolean mOnDemandTimeInjection;

    interface InjectNtpTimeCallback {
        void injectTime(long time, long timeReference, int uncertainty);
    }

    @VisibleForTesting
    NtpTimeHelper(Context context, Looper looper, InjectNtpTimeCallback callback,
            NtpTrustedTime ntpTime) {
        mConnMgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mCallback = callback;
        mNtpTime = ntpTime;
        mHandler = new Handler(looper);
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_KEY);
    }

    NtpTimeHelper(Context context, Looper looper, InjectNtpTimeCallback callback) {
        this(context, looper, callback, NtpTrustedTime.getInstance(context));
    }

    synchronized void enablePeriodicTimeInjection() {
        mOnDemandTimeInjection = true;
    }

    synchronized void onNetworkAvailable() {
        if (mInjectNtpTimeState == STATE_PENDING_NETWORK) {
            retrieveAndInjectNtpTime();
        }
    }

    /**
     * @return {@code true} if there is a network available for outgoing connections,
     * {@code false} otherwise.
     */
    private boolean isNetworkConnected() {
        NetworkInfo activeNetworkInfo = mConnMgr.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    synchronized void retrieveAndInjectNtpTime() {
        if (mInjectNtpTimeState == STATE_RETRIEVING_AND_INJECTING) {
            // already downloading data
            return;
        }
        if (!isNetworkConnected()) {
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
        long delay;

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
            ntpResult = mNtpTime.getCachedTimeResult();
            if (ntpResult != null && ntpResult.getAgeMillis() < NTP_INTERVAL) {
                long time = ntpResult.getTimeMillis();
                long timeReference = ntpResult.getElapsedRealtimeMillis();
                long certainty = ntpResult.getCertaintyMillis();

                if (DEBUG) {
                    long now = System.currentTimeMillis();
                    Log.d(TAG, "NTP server returned: "
                            + time + " (" + new Date(time) + ")"
                            + " ntpResult: " + ntpResult
                            + " system time offset: " + (time - now));
                }

                // Ok to cast to int, as can't rollover in practice
                mHandler.post(() -> mCallback.injectTime(time, timeReference, (int) certainty));

                delay = NTP_INTERVAL;
                mNtpBackOff.reset();
            } else {
                Log.e(TAG, "requestTime failed");
                delay = mNtpBackOff.nextBackoffMillis();
            }

            if (DEBUG) {
                Log.d(TAG, String.format(
                        "onDemandTimeInjection=%s, refreshSuccess=%s, delay=%s",
                        mOnDemandTimeInjection,
                        refreshSuccess,
                        delay));
            }
            // TODO(b/73893222): reconcile Capabilities bit 'on demand' name vs. de facto periodic
            // injection.
            if (mOnDemandTimeInjection || !refreshSuccess) {
                /* Schedule next NTP injection.
                 * Since this is delayed, the wake lock is released right away, and will be held
                 * again when the delayed task runs.
                 */
                mHandler.postDelayed(this::retrieveAndInjectNtpTime, delay);
            }
        }
        // release wake lock held by task
        mWakeLock.release();
    }
}
