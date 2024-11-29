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

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.Slog;
import android.view.HapticFeedbackConstants;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.modules.expresslog.Counter;
import com.android.modules.expresslog.Histogram;

import java.util.ArrayDeque;
import java.util.Queue;

/** Helper class for async write of atoms to {@link FrameworkStatsLog} using a given Handler. */
public class VibratorFrameworkStatsLogger {
    private static final String TAG = "VibratorFrameworkStatsLogger";

    // VibrationReported pushed atom needs to be throttled to at most one every 10ms.
    private static final int VIBRATION_REPORTED_MIN_INTERVAL_MILLIS = 10;
    // We accumulate events that should take 3s to write and drop excessive metrics.
    private static final int VIBRATION_REPORTED_MAX_QUEUE_SIZE = 300;
    // Warning about dropping entries after this amount of atoms were dropped by the throttle.
    private static final int VIBRATION_REPORTED_WARNING_QUEUE_SIZE = 200;

    // Latency between 0ms and 99ms, with 100 representing overflow latencies >= 100ms.
    // Underflow not expected.
    private static final Histogram sVibrationParamRequestLatencyHistogram = new Histogram(
            "vibrator.value_vibration_param_request_latency",
            new Histogram.UniformOptions(20, 0, 100));

    // Scales in [0, 2), with 2 representing overflow scales >= 2.
    // Underflow expected to represent how many times scales were cleared (set to -1).
    private static final Histogram sVibrationParamScaleHistogram = new Histogram(
            "vibrator.value_vibration_param_scale", new Histogram.UniformOptions(20, 0, 2));

    // Scales in [0, 2), with 2 representing overflow scales >= 2.
    // Underflow not expected.
    private static final Histogram sAdaptiveHapticScaleHistogram = new Histogram(
            "vibrator.value_vibration_adaptive_haptic_scale",
            new Histogram.UniformOptions(20, 0, 2));

    // Sizes in [1KB, ~4.5MB) defined by scaled buckets.
    private static final Histogram sVibrationVendorEffectSizeHistogram = new Histogram(
            "vibrator.value_vibration_vendor_effect_size",
            new Histogram.ScaledRangeOptions(25, 0, 1, 1.4f));

    // Session vibration count in [0, ~840) defined by scaled buckets.
    private static final Histogram sVibrationVendorSessionVibrationsHistogram = new Histogram(
            "vibrator.value_vibration_vendor_session_vibrations",
            new Histogram.ScaledRangeOptions(20, 0, 1, 1.4f));

    private final Object mLock = new Object();
    private final Handler mHandler;
    private final long mVibrationReportedLogIntervalMillis;
    private final long mVibrationReportedQueueMaxSize;
    private final Runnable mConsumeVibrationStatsQueueRunnable =
            () -> writeVibrationReportedFromQueue();

    @GuardedBy("mLock")
    private long mLastVibrationReportedLogUptime;
    @GuardedBy("mLock")
    private Queue<VibrationStats.StatsInfo> mVibrationStatsQueue = new ArrayDeque<>();

    VibratorFrameworkStatsLogger(Handler handler) {
        this(handler, VIBRATION_REPORTED_MIN_INTERVAL_MILLIS, VIBRATION_REPORTED_MAX_QUEUE_SIZE);
    }

    @VisibleForTesting
    VibratorFrameworkStatsLogger(Handler handler, int vibrationReportedLogIntervalMillis,
            int vibrationReportedQueueMaxSize) {
        mHandler = handler;
        mVibrationReportedLogIntervalMillis = vibrationReportedLogIntervalMillis;
        mVibrationReportedQueueMaxSize = vibrationReportedQueueMaxSize;
    }

    /** Writes {@link FrameworkStatsLog#VIBRATOR_STATE_CHANGED} for state ON. */
    public void writeVibratorStateOnAsync(int uid, long duration) {
        mHandler.post(
                () -> FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED, uid, null,
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__ON, duration));
    }

    /** Writes {@link FrameworkStatsLog#VIBRATOR_STATE_CHANGED} for state OFF. */
    public void writeVibratorStateOffAsync(int uid) {
        mHandler.post(
                () -> FrameworkStatsLog.write_non_chained(
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED, uid, null,
                        FrameworkStatsLog.VIBRATOR_STATE_CHANGED__STATE__OFF,
                        /* duration= */ 0));
    }

    /**
     *  Writes {@link FrameworkStatsLog#VIBRATION_REPORTED} for given vibration.
     *
     *  <p>This atom is throttled to be pushed once every 10ms, so this logger can keep a queue of
     *  {@link VibrationStats.StatsInfo} entries to slowly write to statsd.
     */
    public void writeVibrationReportedAsync(VibrationStats.StatsInfo metrics) {
        boolean needsScheduling;
        long scheduleDelayMs;
        int queueSize;

        synchronized (mLock) {
            queueSize = mVibrationStatsQueue.size();
            needsScheduling = (queueSize == 0);

            if (queueSize < mVibrationReportedQueueMaxSize) {
                mVibrationStatsQueue.offer(metrics);
            }

            long nextLogUptime =
                    mLastVibrationReportedLogUptime + mVibrationReportedLogIntervalMillis;
            scheduleDelayMs = Math.max(0, nextLogUptime - SystemClock.uptimeMillis());
        }

        if ((queueSize + 1) == VIBRATION_REPORTED_WARNING_QUEUE_SIZE) {
            Slog.w(TAG, " Approaching vibration metrics queue limit, events might be dropped.");
        }

        if (needsScheduling) {
            mHandler.postDelayed(mConsumeVibrationStatsQueueRunnable, scheduleDelayMs);
        }
    }

    /** Writes next {@link FrameworkStatsLog#VIBRATION_REPORTED} from the queue. */
    private void writeVibrationReportedFromQueue() {
        boolean needsScheduling;
        VibrationStats.StatsInfo stats;

        synchronized (mLock) {
            stats = mVibrationStatsQueue.poll();
            needsScheduling = !mVibrationStatsQueue.isEmpty();

            if (stats != null) {
                mLastVibrationReportedLogUptime = SystemClock.uptimeMillis();
            }
        }

        if (stats == null) {
            Slog.w(TAG, "Unexpected vibration metric flush with empty queue. Ignoring.");
        } else {
            stats.writeVibrationReported();
        }

        if (needsScheduling) {
            mHandler.postDelayed(mConsumeVibrationStatsQueueRunnable,
                    mVibrationReportedLogIntervalMillis);
        }
    }

    /** Logs adaptive haptic scale value applied to a vibration, only if it's not 1.0. */
    public void logVibrationAdaptiveHapticScale(int uid, float scale) {
        if (Float.compare(scale, 1f) != 0) {
            sAdaptiveHapticScaleHistogram.logSampleWithUid(uid, scale);
        }
    }

    /** Logs a vibration param scale value received by the vibrator control service. */
    public void logVibrationParamScale(float scale) {
        sVibrationParamScaleHistogram.logSample(scale);
    }

    /** Logs the latency of a successful vibration params request completed before a vibration. */
    public void logVibrationParamRequestLatency(int uid, long latencyMs) {
        sVibrationParamRequestLatencyHistogram.logSampleWithUid(uid, (float) latencyMs);
    }

    /** Logs a vibration params request timed out before a vibration. */
    public void logVibrationParamRequestTimeout(int uid) {
        Counter.logIncrementWithUid("vibrator.value_vibration_param_request_timeout", uid);
    }

    /** Logs when a response received for a vibration params request is ignored by the service. */
    public void logVibrationParamResponseIgnored() {
        Counter.logIncrement("vibrator.value_vibration_param_response_ignored");
    }

    /** Logs only if the haptics feedback effect is one of the KEYBOARD_ constants. */
    public static void logPerformHapticsFeedbackIfKeyboard(int uid, int hapticsFeedbackEffect) {
        boolean isKeyboard;
        switch (hapticsFeedbackEffect) {
            case HapticFeedbackConstants.KEYBOARD_TAP:
            case HapticFeedbackConstants.KEYBOARD_RELEASE:
                isKeyboard = true;
                break;
            default:
                isKeyboard = false;
                break;
        }
        if (isKeyboard) {
            Counter.logIncrementWithUid("vibrator.value_perform_haptic_feedback_keyboard", uid);
        }
    }

    /** Logs when a vendor vibration session successfully started. */
    public void logVibrationVendorSessionStarted(int uid) {
        Counter.logIncrementWithUid("vibrator.value_vibration_vendor_session_started", uid);
    }

    /**
     * Logs when a vendor vibration session is interrupted by the platform.
     *
     * <p>A vendor session is interrupted if it has successfully started and its end was not
     * requested by the vendor. This could be the vibrator service interrupting an ongoing session,
     * the vibrator HAL triggering the session completed callback early.
     */
    public void logVibrationVendorSessionInterrupted(int uid) {
        Counter.logIncrementWithUid("vibrator.value_vibration_vendor_session_interrupted", uid);
    }

    /** Logs the number of vibrations requested for a single vendor vibration session. */
    public void logVibrationVendorSessionVibrations(int uid, int vibrationCount) {
        sVibrationVendorSessionVibrationsHistogram.logSampleWithUid(uid, vibrationCount);
    }

    /**
     * Logs if given vibration contains at least one {@link VibrationEffect.VendorEffect}.
     *
     * <p>Each {@link VibrationEffect.VendorEffect} will also log the parcel data size for the
     * {@link VibrationEffect.VendorEffect#getVendorData()} it holds.
     */
    public void logVibrationCountAndSizeIfVendorEffect(int uid,
            @Nullable CombinedVibration vibration) {
        if (vibration == null) {
            return;
        }
        boolean hasVendorEffects = logVibrationSizeOfVendorEffects(uid, vibration);
        if (hasVendorEffects) {
            // Increment CombinedVibration with one or more vendor effects only once.
            Counter.logIncrementWithUid("vibrator.value_vibration_vendor_effect_requests", uid);
        }
    }

    private static boolean logVibrationSizeOfVendorEffects(int uid, CombinedVibration vibration) {
        if (vibration instanceof CombinedVibration.Mono mono) {
            if (mono.getEffect() instanceof VibrationEffect.VendorEffect effect) {
                logVibrationVendorEffectSize(uid, effect);
                return true;
            }
            return false;
        }
        if (vibration instanceof CombinedVibration.Stereo stereo) {
            boolean hasVendorEffects = false;
            for (int i = 0; i < stereo.getEffects().size(); i++) {
                if (stereo.getEffects().valueAt(i) instanceof VibrationEffect.VendorEffect effect) {
                    logVibrationVendorEffectSize(uid, effect);
                    hasVendorEffects = true;
                }
            }
            return hasVendorEffects;
        }
        if (vibration instanceof CombinedVibration.Sequential sequential) {
            boolean hasVendorEffects = false;
            for (int i = 0; i < sequential.getEffects().size(); i++) {
                hasVendorEffects |= logVibrationSizeOfVendorEffects(uid,
                        sequential.getEffects().get(i));
            }
            return hasVendorEffects;
        }
        // Unknown combined vibration, skip metrics.
        return false;
    }

    private static void logVibrationVendorEffectSize(int uid, VibrationEffect.VendorEffect effect) {
        int dataSize;
        Parcel vendorData = Parcel.obtain();
        try {
            // Measure data size as it'll be sent to the HAL via binder, not the serialization size.
            // PersistableBundle creates an XML representation for the data in writeToStream, so it
            // might be larger than the actual data that is transferred between processes.
            effect.getVendorData().writeToParcel(vendorData, /* flags= */ 0);
            dataSize = vendorData.dataSize();
        } finally {
            vendorData.recycle();
        }
        sVibrationVendorEffectSizeHistogram.logSampleWithUid(uid, dataSize);
    }
}
