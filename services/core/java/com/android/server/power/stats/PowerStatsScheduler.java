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

package com.android.server.power.stats;

import android.annotation.DurationMillisLong;
import android.app.AlarmManager;
import android.os.ConditionVariable;
import android.os.Handler;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.Clock;
import com.android.internal.os.MonotonicClock;

import java.io.PrintWriter;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Controls the frequency at which {@link PowerStatsSpan}'s are generated and stored in
 * {@link PowerStatsStore}.
 */
public class PowerStatsScheduler {
    private static final long MINUTE_IN_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long HOUR_IN_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final AlarmScheduler mAlarmScheduler;
    private boolean mEnablePeriodicPowerStatsCollection;
    @DurationMillisLong
    private final long mAggregatedPowerStatsSpanDuration;
    @DurationMillisLong
    private final long mPowerStatsAggregationPeriod;
    private final PowerStatsStore mPowerStatsStore;
    private final Clock mClock;
    private final MonotonicClock mMonotonicClock;
    private final Handler mHandler;
    private final Runnable mPowerStatsCollector;
    private final Supplier<Long> mEarliestAvailableBatteryHistoryTimeMs;
    private final PowerStatsAggregator mPowerStatsAggregator;
    private long mLastSavedSpanEndMonotonicTime;

    /**
     * External dependency on AlarmManager.
     */
    public interface AlarmScheduler {
        /**
         * Should use AlarmManager to schedule an inexact, non-wakeup alarm.
         */
        void scheduleAlarm(long triggerAtMillis, String tag,
                AlarmManager.OnAlarmListener onAlarmListener, Handler handler);
    }

    public PowerStatsScheduler(Runnable powerStatsCollector,
            PowerStatsAggregator powerStatsAggregator,
            @DurationMillisLong long aggregatedPowerStatsSpanDuration,
            @DurationMillisLong long powerStatsAggregationPeriod, PowerStatsStore powerStatsStore,
            AlarmScheduler alarmScheduler, Clock clock, MonotonicClock monotonicClock,
            Supplier<Long> earliestAvailableBatteryHistoryTimeMs, Handler handler) {
        mPowerStatsAggregator = powerStatsAggregator;
        mAggregatedPowerStatsSpanDuration = aggregatedPowerStatsSpanDuration;
        mPowerStatsAggregationPeriod = powerStatsAggregationPeriod;
        mPowerStatsStore = powerStatsStore;
        mAlarmScheduler = alarmScheduler;
        mClock = clock;
        mMonotonicClock = monotonicClock;
        mHandler = handler;
        mPowerStatsCollector = powerStatsCollector;
        mEarliestAvailableBatteryHistoryTimeMs = earliestAvailableBatteryHistoryTimeMs;
    }

    /**
     * Kicks off the scheduling of power stats aggregation spans.
     */
    public void start(boolean enablePeriodicPowerStatsCollection) {
        mEnablePeriodicPowerStatsCollection = enablePeriodicPowerStatsCollection;
        if (mEnablePeriodicPowerStatsCollection) {
            schedulePowerStatsAggregation();
            scheduleNextPowerStatsAggregation();
        }
    }

    private void scheduleNextPowerStatsAggregation() {
        mAlarmScheduler.scheduleAlarm(mClock.elapsedRealtime() + mPowerStatsAggregationPeriod,
                "PowerStats",
                () -> {
                    schedulePowerStatsAggregation();
                    mHandler.post(this::scheduleNextPowerStatsAggregation);
                }, mHandler);
    }

    /**
     * Initiate an asynchronous process of aggregation of power stats.
     */
    @VisibleForTesting
    public void schedulePowerStatsAggregation() {
        // Catch up the power stats collectors
        mPowerStatsCollector.run();
        mHandler.post(this::aggregateAndStorePowerStats);
    }

    private void aggregateAndStorePowerStats() {
        long currentTimeMillis = mClock.currentTimeMillis();
        long currentMonotonicTime = mMonotonicClock.monotonicTime();
        long startTime = getLastSavedSpanEndMonotonicTime();
        if (startTime < 0) {
            startTime = mEarliestAvailableBatteryHistoryTimeMs.get();
        }
        long endTimeMs = alignToWallClock(startTime + mAggregatedPowerStatsSpanDuration,
                mAggregatedPowerStatsSpanDuration, currentMonotonicTime, currentTimeMillis);
        while (endTimeMs <= currentMonotonicTime) {
            mPowerStatsAggregator.aggregatePowerStats(startTime, endTimeMs,
                    stats -> {
                        storeAggregatedPowerStats(stats);
                        mLastSavedSpanEndMonotonicTime = stats.getStartTime() + stats.getDuration();
                    });

            startTime = endTimeMs;
            endTimeMs += mAggregatedPowerStatsSpanDuration;
        }
    }

    /**
     * Performs a power stats aggregation pass and then dumps all stored aggregated power stats
     * spans followed by the remainder that has not been stored yet.
     */
    public void aggregateAndDumpPowerStats(PrintWriter pw) {
        if (mHandler.getLooper().isCurrentThread()) {
            throw new IllegalStateException("Should not be executed on the bg handler thread.");
        }

        schedulePowerStatsAggregation();

        // Wait for the aggregation process to finish storing aggregated stats spans in the store.
        awaitCompletion();

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        mHandler.post(() -> {
            mPowerStatsStore.dump(ipw);
            // Aggregate the remainder of power stats and dump the results without storing them yet.
            long powerStoreEndMonotonicTime = getLastSavedSpanEndMonotonicTime();
            mPowerStatsAggregator.aggregatePowerStats(powerStoreEndMonotonicTime, 0,
                    stats -> {
                        // Create a PowerStatsSpan for consistency of the textual output
                        PowerStatsSpan span = PowerStatsStore.createPowerStatsSpan(stats);
                        if (span != null) {
                            span.dump(ipw);
                        }
                    });
        });

        awaitCompletion();
    }

    /**
     * Align the supplied time to the wall clock, for aesthetic purposes. For example, if
     * the schedule is configured with a 15-min interval, the captured aggregated stats will
     * be for spans XX:00-XX:15, XX:15-XX:30, XX:30-XX:45 and XX:45-XX:60. Only the current
     * time is used for the alignment, so if the wall clock changed during an aggregation span,
     * or if the device was off (which stops the monotonic clock), the alignment may be
     * temporarily broken.
     */
    @VisibleForTesting
    public static long alignToWallClock(long targetMonotonicTime, long interval,
            long currentMonotonicTime, long currentTimeMillis) {

        // Estimate the wall clock time for the requested targetMonotonicTime
        long targetWallClockTime = currentTimeMillis + (targetMonotonicTime - currentMonotonicTime);

        if (interval >= MINUTE_IN_MILLIS && TimeUnit.HOURS.toMillis(1) % interval == 0) {
            // If the interval is a divisor of an hour, e.g. 10 minutes, 15 minutes, etc

            // First, round up to the next whole minute
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(targetWallClockTime + MINUTE_IN_MILLIS - 1);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            // Now set the minute to a multiple of the requested interval
            int intervalInMinutes = (int) (interval / MINUTE_IN_MILLIS);
            cal.set(Calendar.MINUTE,
                    ((cal.get(Calendar.MINUTE) + intervalInMinutes - 1) / intervalInMinutes)
                            * intervalInMinutes);

            long adjustment = cal.getTimeInMillis() - targetWallClockTime;
            return targetMonotonicTime + adjustment;
        } else if (interval >= HOUR_IN_MILLIS && TimeUnit.DAYS.toMillis(1) % interval == 0) {
            // If the interval is a divisor of a day, e.g. 2h, 3h, etc

            // First, round up to the next whole hour
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(targetWallClockTime + HOUR_IN_MILLIS - 1);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);

            // Now set the hour of day to a multiple of the requested interval
            int intervalInHours = (int) (interval / HOUR_IN_MILLIS);
            cal.set(Calendar.HOUR_OF_DAY,
                    ((cal.get(Calendar.HOUR_OF_DAY) + intervalInHours - 1) / intervalInHours)
                    * intervalInHours);

            long adjustment = cal.getTimeInMillis() - targetWallClockTime;
            return targetMonotonicTime + adjustment;
        }

        return targetMonotonicTime;
    }

    private long getLastSavedSpanEndMonotonicTime() {
        if (mLastSavedSpanEndMonotonicTime != 0) {
            return mLastSavedSpanEndMonotonicTime;
        }

        mLastSavedSpanEndMonotonicTime = -1;
        for (PowerStatsSpan.Metadata metadata : mPowerStatsStore.getTableOfContents()) {
            if (metadata.getSections().contains(AggregatedPowerStatsSection.TYPE)) {
                for (PowerStatsSpan.TimeFrame timeFrame : metadata.getTimeFrames()) {
                    long endMonotonicTime = timeFrame.startMonotonicTime + timeFrame.duration;
                    if (endMonotonicTime > mLastSavedSpanEndMonotonicTime) {
                        mLastSavedSpanEndMonotonicTime = endMonotonicTime;
                    }
                }
            }
        }
        return mLastSavedSpanEndMonotonicTime;
    }

    private void storeAggregatedPowerStats(AggregatedPowerStats stats) {
        mPowerStatsStore.storeAggregatedPowerStats(stats);
    }

    private void awaitCompletion() {
        ConditionVariable done = new ConditionVariable();
        mHandler.post(done::open);
        done.block();
    }
}
