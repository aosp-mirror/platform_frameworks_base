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

package com.android.server.utils.quota;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.utils.quota.Uptc.string;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.LongArrayQueue;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.quota.CountQuotaTrackerProto;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Class that tracks whether an app has exceeded its defined count quota.
 *
 * Quotas are applied per userId-package-tag combination (UPTC). Tags can be null.
 *
 * This tracker tracks the count of instantaneous events.
 *
 * Limits are applied according to the category the UPTC is placed in. If a UPTC reaches its limit,
 * it will be considered out of quota until it is below that limit again. A {@link Category} is a
 * basic construct to apply different limits to different groups of UPTCs. For example, standby
 * buckets can be a set of categories, or foreground & background could be two categories. If every
 * UPTC should have the same limits applied, then only one category is needed
 * ({@see Category.SINGLE_CATEGORY}).
 *
 * Note: all limits are enforced per category unless explicitly stated otherwise.
 *
 * Test: atest com.android.server.utils.quota.CountQuotaTrackerTest
 *
 * @hide
 */
public class CountQuotaTracker extends QuotaTracker {
    private static final String TAG = CountQuotaTracker.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String ALARM_TAG_CLEANUP = "*" + TAG + ".cleanup*";

    @VisibleForTesting
    static class ExecutionStats {
        /**
         * The time after which this record should be considered invalid (out of date), in the
         * elapsed realtime timebase.
         */
        public long expirationTimeElapsed;

        /** The window size that's used when counting the number of events. */
        public long windowSizeMs;
        /** The maximum number of events allowed within the window size. */
        public int countLimit;

        /** The total number of events that occurred in the window. */
        public int countInWindow;

        /**
         * The time after which the app will be under the category quota again. This is only valid
         * if {@link #countInWindow} >= {@link #countLimit}.
         */
        public long inQuotaTimeElapsed;

        @Override
        public String toString() {
            return "expirationTime=" + expirationTimeElapsed + ", "
                    + "windowSizeMs=" + windowSizeMs + ", "
                    + "countLimit=" + countLimit + ", "
                    + "countInWindow=" + countInWindow + ", "
                    + "inQuotaTime=" + inQuotaTimeElapsed;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ExecutionStats) {
                ExecutionStats other = (ExecutionStats) obj;
                return this.expirationTimeElapsed == other.expirationTimeElapsed
                        && this.windowSizeMs == other.windowSizeMs
                        && this.countLimit == other.countLimit
                        && this.countInWindow == other.countInWindow
                        && this.inQuotaTimeElapsed == other.inQuotaTimeElapsed;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = 0;
            result = 31 * result + Long.hashCode(expirationTimeElapsed);
            result = 31 * result + Long.hashCode(windowSizeMs);
            result = 31 * result + countLimit;
            result = 31 * result + countInWindow;
            result = 31 * result + Long.hashCode(inQuotaTimeElapsed);
            return result;
        }
    }

    /** List of times of all instantaneous events for a UPTC, in chronological order. */
    // TODO(146148168): introduce a bucketized mode that's more efficient but less accurate
    @GuardedBy("mLock")
    private final UptcMap<LongArrayQueue> mEventTimes = new UptcMap<>();

    /** Cached calculation results for each app. */
    @GuardedBy("mLock")
    private final UptcMap<ExecutionStats> mExecutionStatsCache = new UptcMap<>();

    private final Handler mHandler;

    @GuardedBy("mLock")
    private long mNextCleanupTimeElapsed = 0;
    @GuardedBy("mLock")
    private final AlarmManager.OnAlarmListener mEventCleanupAlarmListener = () ->
            CountQuotaTracker.this.mHandler.obtainMessage(MSG_CLEAN_UP_EVENTS).sendToTarget();

    /** The rolling window size for each Category's count limit. */
    @GuardedBy("mLock")
    private final ArrayMap<Category, Long> mCategoryCountWindowSizesMs = new ArrayMap<>();

    /**
     * The maximum count for each Category. For each max value count in the map, the app will
     * not be allowed any more events within the latest time interval of its rolling window size.
     *
     * @see #mCategoryCountWindowSizesMs
     */
    @GuardedBy("mLock")
    private final ArrayMap<Category, Integer> mMaxCategoryCounts = new ArrayMap<>();

    /** The longest period a registered category applies to. */
    @GuardedBy("mLock")
    private long mMaxPeriodMs = 0;

    /** Drop any old events. */
    private static final int MSG_CLEAN_UP_EVENTS = 1;

    public CountQuotaTracker(@NonNull Context context, @NonNull Categorizer categorizer) {
        this(context, categorizer, new Injector());
    }

    @VisibleForTesting
    CountQuotaTracker(@NonNull Context context, @NonNull Categorizer categorizer,
            Injector injector) {
        super(context, categorizer, injector);

        mHandler = new CqtHandler(context.getMainLooper());
    }

    // Exposed API to users.

    /**
     * Record that an instantaneous event happened.
     *
     * @return true if the UPTC is within quota, false otherwise.
     */
    public boolean noteEvent(int userId, @NonNull String packageName, @Nullable String tag) {
        synchronized (mLock) {
            if (!isEnabledLocked() || isQuotaFreeLocked(userId, packageName)) {
                return true;
            }
            final long nowElapsed = mInjector.getElapsedRealtime();

            final LongArrayQueue times = mEventTimes
                    .getOrCreate(userId, packageName, tag, mCreateLongArrayQueue);
            times.addLast(nowElapsed);
            final ExecutionStats stats = getExecutionStatsLocked(userId, packageName, tag);
            stats.countInWindow++;
            stats.expirationTimeElapsed = Math.min(stats.expirationTimeElapsed,
                    nowElapsed + stats.windowSizeMs);
            if (stats.countInWindow == stats.countLimit) {
                final long windowEdgeElapsed = nowElapsed - stats.windowSizeMs;
                while (times.size() > 0 && times.peekFirst() < windowEdgeElapsed) {
                    times.removeFirst();
                }
                stats.inQuotaTimeElapsed = times.peekFirst() + stats.windowSizeMs;
                postQuotaStatusChanged(userId, packageName, tag);
            } else if (stats.countLimit > 9
                    && stats.countInWindow == stats.countLimit * 4 / 5) {
                // TODO: log high watermark to statsd
                Slog.w(TAG, string(userId, packageName, tag)
                        + " has reached 80% of it's count limit of " + stats.countLimit);
            }
            maybeScheduleCleanupAlarmLocked();
            return isWithinQuotaLocked(stats);
        }
    }

    /**
     * Set count limit over a rolling time window for the specified category.
     *
     * @param category     The category these limits apply to.
     * @param limit        The maximum event count an app can have in the rolling window. Must be
     *                     nonnegative.
     * @param timeWindowMs The rolling time window (in milliseconds) to use when checking quota
     *                     usage. Must be at least {@value #MIN_WINDOW_SIZE_MS} and no longer than
     *                     {@value #MAX_WINDOW_SIZE_MS}
     */
    public void setCountLimit(@NonNull Category category, int limit, long timeWindowMs) {
        if (limit < 0 || timeWindowMs < 0) {
            throw new IllegalArgumentException("Limit and window size must be nonnegative.");
        }
        synchronized (mLock) {
            final Integer oldLimit = mMaxCategoryCounts.put(category, limit);
            final long newWindowSizeMs = Math.max(MIN_WINDOW_SIZE_MS,
                    Math.min(timeWindowMs, MAX_WINDOW_SIZE_MS));
            final Long oldWindowSizeMs = mCategoryCountWindowSizesMs.put(category, newWindowSizeMs);
            if (oldLimit != null && oldWindowSizeMs != null
                    && oldLimit == limit && oldWindowSizeMs == newWindowSizeMs) {
                // No change.
                return;
            }
            mDeleteOldEventTimesFunctor.updateMaxPeriod();
            mMaxPeriodMs = mDeleteOldEventTimesFunctor.mMaxPeriodMs;
            invalidateAllExecutionStatsLocked();
        }
        scheduleQuotaCheck();
    }

    /**
     * Gets the count limit for the specified category.
     */
    public int getLimit(@NonNull Category category) {
        synchronized (mLock) {
            final Integer limit = mMaxCategoryCounts.get(category);
            if (limit == null) {
                throw new IllegalArgumentException("Limit for " + category + " not defined");
            }
            return limit;
        }
    }

    /**
     * Gets the count time window for the specified category.
     */
    public long getWindowSizeMs(@NonNull Category category) {
        synchronized (mLock) {
            final Long limitMs = mCategoryCountWindowSizesMs.get(category);
            if (limitMs == null) {
                throw new IllegalArgumentException("Limit for " + category + " not defined");
            }
            return limitMs;
        }
    }

    // Internal implementation.

    @Override
    @GuardedBy("mLock")
    void dropEverythingLocked() {
        mExecutionStatsCache.clear();
        mEventTimes.clear();
    }

    @Override
    @GuardedBy("mLock")
    @NonNull
    Handler getHandler() {
        return mHandler;
    }

    @Override
    @GuardedBy("mLock")
    long getInQuotaTimeElapsedLocked(final int userId, @NonNull final String packageName,
            @Nullable final String tag) {
        return getExecutionStatsLocked(userId, packageName, tag).inQuotaTimeElapsed;
    }

    @Override
    @GuardedBy("mLock")
    void handleRemovedAppLocked(String packageName, int uid) {
        if (packageName == null) {
            Slog.wtf(TAG, "Told app removed but given null package name.");
            return;
        }
        final int userId = UserHandle.getUserId(uid);

        mEventTimes.delete(userId, packageName);
        mExecutionStatsCache.delete(userId, packageName);
    }

    @Override
    @GuardedBy("mLock")
    void handleRemovedUserLocked(int userId) {
        mEventTimes.delete(userId);
        mExecutionStatsCache.delete(userId);
    }

    @Override
    @GuardedBy("mLock")
    boolean isWithinQuotaLocked(final int userId, @NonNull final String packageName,
            @Nullable final String tag) {
        if (!isEnabledLocked()) return true;

        // Quota constraint is not enforced when quota is free.
        if (isQuotaFreeLocked(userId, packageName)) {
            return true;
        }

        return isWithinQuotaLocked(getExecutionStatsLocked(userId, packageName, tag));
    }

    @Override
    @GuardedBy("mLock")
    void maybeUpdateAllQuotaStatusLocked() {
        final UptcMap<Boolean> doneMap = new UptcMap<>();
        mEventTimes.forEach((userId, packageName, tag, events) -> {
            if (!doneMap.contains(userId, packageName, tag)) {
                maybeUpdateStatusForUptcLocked(userId, packageName, tag);
                doneMap.add(userId, packageName, tag, Boolean.TRUE);
            }
        });

    }

    @Override
    void maybeUpdateQuotaStatus(final int userId, @NonNull final String packageName,
            @Nullable final String tag) {
        synchronized (mLock) {
            maybeUpdateStatusForUptcLocked(userId, packageName, tag);
        }
    }

    @Override
    @GuardedBy("mLock")
    void onQuotaFreeChangedLocked(boolean isFree) {
        // Nothing to do here.
    }

    @Override
    @GuardedBy("mLock")
    void onQuotaFreeChangedLocked(int userId, @NonNull String packageName, boolean isFree) {
        maybeUpdateStatusForPkgLocked(userId, packageName);
    }

    @GuardedBy("mLock")
    private boolean isWithinQuotaLocked(@NonNull final ExecutionStats stats) {
        return isUnderCountQuotaLocked(stats);
    }

    @GuardedBy("mLock")
    private boolean isUnderCountQuotaLocked(@NonNull ExecutionStats stats) {
        return stats.countInWindow < stats.countLimit;
    }

    /** Returns the execution stats of the app in the most recent window. */
    @GuardedBy("mLock")
    @VisibleForTesting
    @NonNull
    ExecutionStats getExecutionStatsLocked(final int userId, @NonNull final String packageName,
            @Nullable final String tag) {
        return getExecutionStatsLocked(userId, packageName, tag, true);
    }

    @GuardedBy("mLock")
    @NonNull
    private ExecutionStats getExecutionStatsLocked(final int userId,
            @NonNull final String packageName, @Nullable String tag,
            final boolean refreshStatsIfOld) {
        final ExecutionStats stats =
                mExecutionStatsCache.getOrCreate(userId, packageName, tag, mCreateExecutionStats);
        if (refreshStatsIfOld) {
            final Category category = mCategorizer.getCategory(userId, packageName, tag);
            final long countWindowSizeMs = mCategoryCountWindowSizesMs.getOrDefault(category,
                    Long.MAX_VALUE);
            final int countLimit = mMaxCategoryCounts.getOrDefault(category, Integer.MAX_VALUE);
            if (stats.expirationTimeElapsed <= mInjector.getElapsedRealtime()
                    || stats.windowSizeMs != countWindowSizeMs
                    || stats.countLimit != countLimit) {
                // The stats are no longer valid.
                stats.windowSizeMs = countWindowSizeMs;
                stats.countLimit = countLimit;
                updateExecutionStatsLocked(userId, packageName, tag, stats);
            }
        }

        return stats;
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    void updateExecutionStatsLocked(final int userId, @NonNull final String packageName,
            @Nullable final String tag, @NonNull ExecutionStats stats) {
        stats.countInWindow = 0;
        if (stats.countLimit == 0) {
            // UPTC won't be in quota until configuration changes.
            stats.inQuotaTimeElapsed = Long.MAX_VALUE;
        } else {
            stats.inQuotaTimeElapsed = 0;
        }

        // This can be used to determine when an app will have enough quota to transition from
        // out-of-quota to in-quota.
        final long nowElapsed = mInjector.getElapsedRealtime();
        stats.expirationTimeElapsed = nowElapsed + mMaxPeriodMs;

        final LongArrayQueue events = mEventTimes.get(userId, packageName, tag);
        if (events == null) {
            return;
        }

        // The minimum time between the start time and the beginning of the events that were
        // looked at --> how much time the stats will be valid for.
        long emptyTimeMs = Long.MAX_VALUE - nowElapsed;

        final long eventStartWindowElapsed = nowElapsed - stats.windowSizeMs;
        for (int i = events.size() - 1; i >= 0; --i) {
            final long eventTimeElapsed = events.get(i);
            if (eventTimeElapsed < eventStartWindowElapsed) {
                // This event happened before the window. No point in going any further.
                break;
            }
            stats.countInWindow++;
            emptyTimeMs = Math.min(emptyTimeMs, eventTimeElapsed - eventStartWindowElapsed);

            if (stats.countInWindow >= stats.countLimit) {
                stats.inQuotaTimeElapsed = Math.max(stats.inQuotaTimeElapsed,
                        eventTimeElapsed + stats.windowSizeMs);
            }
        }

        stats.expirationTimeElapsed = nowElapsed + emptyTimeMs;
    }

    /** Invalidate ExecutionStats for all apps. */
    @GuardedBy("mLock")
    private void invalidateAllExecutionStatsLocked() {
        final long nowElapsed = mInjector.getElapsedRealtime();
        mExecutionStatsCache.forEach((appStats) -> {
            if (appStats != null) {
                appStats.expirationTimeElapsed = nowElapsed;
            }
        });
    }

    @GuardedBy("mLock")
    private void invalidateAllExecutionStatsLocked(final int userId,
            @NonNull final String packageName) {
        final ArrayMap<String, ExecutionStats> appStats =
                mExecutionStatsCache.get(userId, packageName);
        if (appStats != null) {
            final long nowElapsed = mInjector.getElapsedRealtime();
            final int numStats = appStats.size();
            for (int i = 0; i < numStats; ++i) {
                final ExecutionStats stats = appStats.valueAt(i);
                if (stats != null) {
                    stats.expirationTimeElapsed = nowElapsed;
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void invalidateExecutionStatsLocked(final int userId, @NonNull final String packageName,
            @Nullable String tag) {
        final ExecutionStats stats = mExecutionStatsCache.get(userId, packageName, tag);
        if (stats != null) {
            stats.expirationTimeElapsed = mInjector.getElapsedRealtime();
        }
    }

    private static final class EarliestEventTimeFunctor implements Consumer<LongArrayQueue> {
        long earliestTimeElapsed = Long.MAX_VALUE;

        @Override
        public void accept(LongArrayQueue events) {
            if (events != null && events.size() > 0) {
                earliestTimeElapsed = Math.min(earliestTimeElapsed, events.get(0));
            }
        }

        void reset() {
            earliestTimeElapsed = Long.MAX_VALUE;
        }
    }

    private final EarliestEventTimeFunctor mEarliestEventTimeFunctor =
            new EarliestEventTimeFunctor();

    /** Schedule a cleanup alarm if necessary and there isn't already one scheduled. */
    @GuardedBy("mLock")
    @VisibleForTesting
    void maybeScheduleCleanupAlarmLocked() {
        if (mNextCleanupTimeElapsed > mInjector.getElapsedRealtime()) {
            // There's already an alarm scheduled. Just stick with that one. There's no way we'll
            // end up scheduling an earlier alarm.
            if (DEBUG) {
                Slog.v(TAG, "Not scheduling cleanup since there's already one at "
                        + mNextCleanupTimeElapsed + " (in " + (mNextCleanupTimeElapsed
                        - mInjector.getElapsedRealtime()) + "ms)");
            }
            return;
        }

        mEarliestEventTimeFunctor.reset();
        mEventTimes.forEach(mEarliestEventTimeFunctor);
        final long earliestEndElapsed = mEarliestEventTimeFunctor.earliestTimeElapsed;
        if (earliestEndElapsed == Long.MAX_VALUE) {
            // Couldn't find a good time to clean up. Maybe this was called after we deleted all
            // events.
            if (DEBUG) {
                Slog.d(TAG, "Didn't find a time to schedule cleanup");
            }
            return;
        }

        // Need to keep events for all apps up to the max period, regardless of their current
        // category.
        long nextCleanupElapsed = earliestEndElapsed + mMaxPeriodMs;
        if (nextCleanupElapsed - mNextCleanupTimeElapsed <= 10 * MINUTE_IN_MILLIS) {
            // No need to clean up too often. Delay the alarm if the next cleanup would be too soon
            // after it.
            nextCleanupElapsed += 10 * MINUTE_IN_MILLIS;
        }
        mNextCleanupTimeElapsed = nextCleanupElapsed;
        scheduleAlarm(AlarmManager.ELAPSED_REALTIME, nextCleanupElapsed, ALARM_TAG_CLEANUP,
                mEventCleanupAlarmListener);
        if (DEBUG) {
            Slog.d(TAG, "Scheduled next cleanup for " + mNextCleanupTimeElapsed);
        }
    }

    @GuardedBy("mLock")
    private boolean maybeUpdateStatusForPkgLocked(final int userId,
            @NonNull final String packageName) {
        final UptcMap<Boolean> done = new UptcMap<>();

        if (!mEventTimes.contains(userId, packageName)) {
            return false;
        }
        final ArrayMap<String, LongArrayQueue> events = mEventTimes.get(userId, packageName);
        if (events == null) {
            Slog.wtf(TAG,
                    "Events map was null even though mEventTimes said it contained "
                            + string(userId, packageName, null));
            return false;
        }

        // Lambdas can't interact with non-final outer variables.
        final boolean[] changed = {false};
        events.forEach((tag, eventList) -> {
            if (!done.contains(userId, packageName, tag)) {
                changed[0] |= maybeUpdateStatusForUptcLocked(userId, packageName, tag);
                done.add(userId, packageName, tag, Boolean.TRUE);
            }
        });

        return changed[0];
    }

    /**
     * Posts that the quota status for the UPTC has changed if it has changed. Avoid calling if
     * there are no {@link QuotaChangeListener}s registered as the work done will be useless.
     *
     * @return true if the in/out quota status changed
     */
    @GuardedBy("mLock")
    private boolean maybeUpdateStatusForUptcLocked(final int userId,
            @NonNull final String packageName, @Nullable final String tag) {
        final boolean oldInQuota = isWithinQuotaLocked(
                getExecutionStatsLocked(userId, packageName, tag, false));

        final boolean newInQuota;
        if (!isEnabledLocked() || isQuotaFreeLocked(userId, packageName)) {
            newInQuota = true;
        } else {
            newInQuota = isWithinQuotaLocked(
                    getExecutionStatsLocked(userId, packageName, tag, true));
        }

        if (!newInQuota) {
            maybeScheduleStartAlarmLocked(userId, packageName, tag);
        } else {
            cancelScheduledStartAlarmLocked(userId, packageName, tag);
        }

        if (oldInQuota != newInQuota) {
            if (DEBUG) {
                Slog.d(TAG,
                        "Quota status changed from " + oldInQuota + " to " + newInQuota + " for "
                                + string(userId, packageName, tag));
            }
            postQuotaStatusChanged(userId, packageName, tag);
            return true;
        }

        return false;
    }

    private final class DeleteEventTimesFunctor implements Consumer<LongArrayQueue> {
        private long mMaxPeriodMs;

        @Override
        public void accept(LongArrayQueue times) {
            if (times != null) {
                // Remove everything older than mMaxPeriodMs time ago.
                while (times.size() > 0
                        && times.peekFirst() <= mInjector.getElapsedRealtime() - mMaxPeriodMs) {
                    times.removeFirst();
                }
            }
        }

        private void updateMaxPeriod() {
            long maxPeriodMs = 0;
            for (int i = mCategoryCountWindowSizesMs.size() - 1; i >= 0; --i) {
                maxPeriodMs = Long.max(maxPeriodMs, mCategoryCountWindowSizesMs.valueAt(i));
            }
            mMaxPeriodMs = maxPeriodMs;
        }
    }

    private final DeleteEventTimesFunctor mDeleteOldEventTimesFunctor =
            new DeleteEventTimesFunctor();

    @GuardedBy("mLock")
    @VisibleForTesting
    void deleteObsoleteEventsLocked() {
        mEventTimes.forEach(mDeleteOldEventTimesFunctor);
    }

    private class CqtHandler extends Handler {
        CqtHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                switch (msg.what) {
                    case MSG_CLEAN_UP_EVENTS: {
                        if (DEBUG) {
                            Slog.d(TAG, "Cleaning up events.");
                        }
                        deleteObsoleteEventsLocked();
                        maybeScheduleCleanupAlarmLocked();
                        break;
                    }
                }
            }
        }
    }

    private Function<Void, LongArrayQueue> mCreateLongArrayQueue = aVoid -> new LongArrayQueue();
    private Function<Void, ExecutionStats> mCreateExecutionStats = aVoid -> new ExecutionStats();

    //////////////////////// TESTING HELPERS /////////////////////////////

    @VisibleForTesting
    @Nullable
    LongArrayQueue getEvents(int userId, String packageName, String tag) {
        return mEventTimes.get(userId, packageName, tag);
    }

    //////////////////////////// DATA DUMP //////////////////////////////

    /** Dump state in text format. */
    public void dump(final IndentingPrintWriter pw) {
        pw.print(TAG);
        pw.println(":");
        pw.increaseIndent();

        synchronized (mLock) {
            super.dump(pw);
            pw.println();

            pw.println("Instantaneous events:");
            pw.increaseIndent();
            mEventTimes.forEach((userId, pkgName, tag, events) -> {
                if (events.size() > 0) {
                    pw.print(string(userId, pkgName, tag));
                    pw.println(":");
                    pw.increaseIndent();
                    pw.print(events.get(0));
                    for (int i = 1; i < events.size(); ++i) {
                        pw.print(", ");
                        pw.print(events.get(i));
                    }
                    pw.decreaseIndent();
                    pw.println();
                }
            });
            pw.decreaseIndent();

            pw.println();
            pw.println("Cached execution stats:");
            pw.increaseIndent();
            mExecutionStatsCache.forEach((userId, pkgName, tag, stats) -> {
                if (stats != null) {
                    pw.print(string(userId, pkgName, tag));
                    pw.println(":");
                    pw.increaseIndent();
                    pw.println(stats);
                    pw.decreaseIndent();
                }
            });
            pw.decreaseIndent();

            pw.println();
            pw.println("Limits:");
            pw.increaseIndent();
            final int numCategories = mCategoryCountWindowSizesMs.size();
            for (int i = 0; i < numCategories; ++i) {
                final Category category = mCategoryCountWindowSizesMs.keyAt(i);
                pw.print(category);
                pw.print(": ");
                pw.print(mMaxCategoryCounts.get(category));
                pw.print(" events in ");
                pw.println(TimeUtils.formatDuration(mCategoryCountWindowSizesMs.get(category)));
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }

    /**
     * Dump state to proto.
     *
     * @param proto   The ProtoOutputStream to write to.
     * @param fieldId The field ID of the {@link CountQuotaTrackerProto}.
     */
    public void dump(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        synchronized (mLock) {
            super.dump(proto, CountQuotaTrackerProto.BASE_QUOTA_DATA);

            for (int i = 0; i < mCategoryCountWindowSizesMs.size(); ++i) {
                final Category category = mCategoryCountWindowSizesMs.keyAt(i);
                final long clToken = proto.start(CountQuotaTrackerProto.COUNT_LIMIT);
                category.dumpDebug(proto, CountQuotaTrackerProto.CountLimit.CATEGORY);
                proto.write(CountQuotaTrackerProto.CountLimit.LIMIT,
                        mMaxCategoryCounts.get(category));
                proto.write(CountQuotaTrackerProto.CountLimit.WINDOW_SIZE_MS,
                        mCategoryCountWindowSizesMs.get(category));
                proto.end(clToken);
            }

            mExecutionStatsCache.forEach((userId, pkgName, tag, stats) -> {
                final boolean isQuotaFree = isIndividualQuotaFreeLocked(userId, pkgName);

                final long usToken = proto.start(CountQuotaTrackerProto.UPTC_STATS);

                (new Uptc(userId, pkgName, tag))
                        .dumpDebug(proto, CountQuotaTrackerProto.UptcStats.UPTC);

                proto.write(CountQuotaTrackerProto.UptcStats.IS_QUOTA_FREE, isQuotaFree);

                final LongArrayQueue events = mEventTimes.get(userId, pkgName, tag);
                if (events != null) {
                    for (int j = events.size() - 1; j >= 0; --j) {
                        final long eToken = proto.start(CountQuotaTrackerProto.UptcStats.EVENTS);
                        proto.write(CountQuotaTrackerProto.Event.TIMESTAMP_ELAPSED, events.get(j));
                        proto.end(eToken);
                    }
                }

                final long statsToken = proto.start(
                        CountQuotaTrackerProto.UptcStats.EXECUTION_STATS);
                proto.write(
                        CountQuotaTrackerProto.ExecutionStats.EXPIRATION_TIME_ELAPSED,
                        stats.expirationTimeElapsed);
                proto.write(
                        CountQuotaTrackerProto.ExecutionStats.WINDOW_SIZE_MS,
                        stats.windowSizeMs);
                proto.write(CountQuotaTrackerProto.ExecutionStats.COUNT_LIMIT, stats.countLimit);
                proto.write(
                        CountQuotaTrackerProto.ExecutionStats.COUNT_IN_WINDOW,
                        stats.countInWindow);
                proto.write(
                        CountQuotaTrackerProto.ExecutionStats.IN_QUOTA_TIME_ELAPSED,
                        stats.inQuotaTimeElapsed);
                proto.end(statsToken);

                proto.end(usToken);
            });

            proto.end(token);
        }
    }
}
