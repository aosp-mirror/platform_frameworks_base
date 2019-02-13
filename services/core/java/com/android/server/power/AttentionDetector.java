/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.power;

import android.attention.AttentionManagerInternal;
import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.service.attention.AttentionService;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class responsible for checking if the user is currently paying attention to the phone and
 * notifying {@link PowerManagerService} that user activity should be renewed.
 *
 * This class also implements a limit of how long the extension should be, to avoid security
 * issues where the device would never be locked.
 */
public class AttentionDetector {

    private static final String TAG = "AttentionDetector";
    private static final boolean DEBUG = false;

    /**
     * Invoked whenever user attention is detected.
     */
    private final Runnable mOnUserAttention;

    /**
     * The maximum time, in millis, that the phone can stay unlocked because of attention events,
     * triggered by any user.
     */
    @VisibleForTesting
    protected long mMaximumExtensionMillis;

    private final Object mLock;

    /**
     * {@link android.service.attention.AttentionService} API timeout.
     */
    private long mMaxAttentionApiTimeoutMillis;

    /**
     * Last known user activity.
     */
    private long mLastUserActivityTime;

    @VisibleForTesting
    protected AttentionManagerInternal mAttentionManager;

    /**
     * If we're currently waiting for an attention callback
     */
    private boolean mRequested;

    /**
     * Current wakefulness of the device. {@see PowerManagerInternal}
     */
    private int mWakefulness;

    /**
     * Describes how many times in a row was the timeout extended.
     */
    private AtomicLong mConsecutiveTimeoutExtendedCount = new AtomicLong(0);

    @VisibleForTesting
    final AttentionCallbackInternal mCallback = new AttentionCallbackInternal() {

        @Override
        public void onSuccess(int requestCode, int result, long timestamp) {
            Slog.v(TAG, "onSuccess: " + requestCode + ", " + result
                    + " - current requestCode: " + getRequestCode());
            synchronized (mLock) {
                if (requestCode == getRequestCode() && mRequested) {
                    mRequested = false;
                    if (mWakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE) {
                        if (DEBUG) Slog.d(TAG, "Device slept before receiving callback.");
                        return;
                    }
                    if (result == AttentionService.ATTENTION_SUCCESS_PRESENT) {
                        mOnUserAttention.run();
                    } else {
                        resetConsecutiveExtensionCount();
                    }
                }
            }
        }

        @Override
        public void onFailure(int requestCode, int error) {
            Slog.i(TAG, "Failed to check attention: " + error);
            synchronized (mLock) {
                if (requestCode == getRequestCode()) {
                    mRequested = false;
                }
            }
        }
    };

    public AttentionDetector(Runnable onUserAttention, Object lock) {
        mOnUserAttention = onUserAttention;
        mLock = lock;
    }

    public void systemReady(Context context) {
        mAttentionManager = LocalServices.getService(AttentionManagerInternal.class);
        mMaximumExtensionMillis = context.getResources().getInteger(
                com.android.internal.R.integer.config_attentionMaximumExtension);
        mMaxAttentionApiTimeoutMillis = context.getResources().getInteger(
                com.android.internal.R.integer.config_attentionApiTimeout);
    }

    public long updateUserActivity(long nextScreenDimming) {
        if (!isAttentionServiceSupported()) {
            return nextScreenDimming;
        }

        final long now = SystemClock.uptimeMillis();
        final long whenToCheck = nextScreenDimming - getAttentionTimeout();
        final long whenToStopExtending = mLastUserActivityTime + mMaximumExtensionMillis;
        if (now < whenToCheck) {
            if (DEBUG) {
                Slog.d(TAG, "Do not check for attention yet, wait " + (whenToCheck - now));
            }
            return whenToCheck;
        } else if (whenToStopExtending < whenToCheck) {
            if (DEBUG) {
                Slog.d(TAG, "Let device sleep to avoid false results and improve security "
                        + (whenToCheck - whenToStopExtending));
            }
            return nextScreenDimming;
        } else if (mRequested) {
            if (DEBUG) {
                Slog.d(TAG, "Pending attention callback, wait. " + getRequestCode());
            }
            return whenToCheck;
        }

        // Ideally we should attribute mRequested to the result of #checkAttention, but the
        // callback might arrive before #checkAttention returns (if there are cached results.)
        // This means that we must assume that the request was successful, and then cancel it
        // afterwards if AttentionManager couldn't deliver it.
        mRequested = true;
        final boolean sent = mAttentionManager.checkAttention(getRequestCode(),
                getAttentionTimeout(), mCallback);
        if (!sent) {
            mRequested = false;
        }

        Slog.v(TAG, "Checking user attention with request code: " + getRequestCode());
        return whenToCheck;
    }

    /**
     * Handles user activity by cancelling any pending attention requests and keeping track of when
     * the activity happened.
     *
     * @param eventTime Activity time, in uptime millis.
     * @param event Activity type as defined in {@link PowerManager}.
     * @return 0 when activity was ignored, 1 when handled, -1 when invalid.
     */
    public int onUserActivity(long eventTime, int event) {
        switch (event) {
            case PowerManager.USER_ACTIVITY_EVENT_ATTENTION:
                mConsecutiveTimeoutExtendedCount.incrementAndGet();
                return 0;
            case PowerManager.USER_ACTIVITY_EVENT_OTHER:
            case PowerManager.USER_ACTIVITY_EVENT_BUTTON:
            case PowerManager.USER_ACTIVITY_EVENT_TOUCH:
            case PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY:
                cancelCurrentRequestIfAny();
                mLastUserActivityTime = eventTime;
                resetConsecutiveExtensionCount();
                return 1;
            default:
                if (DEBUG) {
                    Slog.d(TAG, "Attention not reset. Unknown activity event: " + event);
                }
                return -1;
        }
    }

    public void onWakefulnessChangeStarted(int wakefulness) {
        mWakefulness = wakefulness;
        if (wakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE) {
            cancelCurrentRequestIfAny();
            resetConsecutiveExtensionCount();
        }
    }

    private void cancelCurrentRequestIfAny() {
        if (mRequested) {
            mAttentionManager.cancelAttentionCheck(getRequestCode());
            mRequested = false;
        }
    }

    private void resetConsecutiveExtensionCount() {
        final long previousCount = mConsecutiveTimeoutExtendedCount.getAndSet(0);
        if (previousCount > 0) {
            StatsLog.write(StatsLog.SCREEN_TIMEOUT_EXTENSION_REPORTED, previousCount);
        }
    }

    @VisibleForTesting
    int getRequestCode() {
        return (int) (mLastUserActivityTime % Integer.MAX_VALUE);
    }

    @VisibleForTesting
    long getAttentionTimeout() {
        return mMaxAttentionApiTimeoutMillis;
    }

    /**
     * {@see AttentionManagerInternal#isAttentionServiceSupported}
     */
    @VisibleForTesting
    boolean isAttentionServiceSupported() {
        return mAttentionManager.isAttentionServiceSupported();
    }

    public void dump(PrintWriter pw) {
        pw.print("AttentionDetector:");
        pw.print(" mMaximumExtensionMillis=" + mMaximumExtensionMillis);
        pw.print(" mMaxAttentionApiTimeoutMillis=" + mMaxAttentionApiTimeoutMillis);
        pw.print(" mLastUserActivityTime(excludingAttention)=" + mLastUserActivityTime);
        pw.print(" mAttentionServiceSupported=" + isAttentionServiceSupported());
        pw.print(" mRequested=" + mRequested);
    }
}
