/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.utils;

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * An {@link AlarmManager.OnAlarmListener} that will queue up all pending alarms and only
 * schedule one alarm for the earliest alarm. Since {@link AlarmManager} has a maximum limit on the
 * number of alarms that can be set at one time, this allows clients to maintain alarm times for
 * various keys without risking hitting the AlarmManager alarm limit. Only one alarm time will be
 * kept for each key {@code K}.
 *
 * @param <K> Any class that will be used as the key. Must have a proper equals() implementation.
 * @hide
 */
public abstract class AlarmQueue<K> implements AlarmManager.OnAlarmListener {
    private static final String TAG = AlarmQueue.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final long NOT_SCHEDULED = -1;

    /**
     * Internal priority queue for each key's alarm, ordered by the time the alarm should go off.
     * The pair is the key and its associated alarm time (in the elapsed realtime timebase).
     */
    private static class AlarmPriorityQueue<Q> extends PriorityQueue<Pair<Q, Long>> {
        AlarmPriorityQueue() {
            super(1, (o1, o2) -> (int) (o1.second - o2.second));
        }

        /**
         * Remove any instances of the key from the queue.
         *
         * @return true if an instance was removed, false otherwise.
         */
        public boolean removeKey(@NonNull Q key) {
            boolean removed = false;
            Pair[] alarms = toArray(new Pair[size()]);
            for (int i = alarms.length - 1; i >= 0; --i) {
                if (key.equals(alarms[i].first)) {
                    remove(alarms[i]);
                    removed = true;
                }
            }
            return removed;
        }
    }

    @VisibleForTesting
    static class Injector {
        long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    /** Runnable used to schedule an alarm with AlarmManager. NEVER run this with the lock held. */
    private final Runnable mScheduleAlarmRunnable = new Runnable() {
        @Override
        public void run() {
            mHandler.removeCallbacks(this);

            final AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
            if (alarmManager == null) {
                // The system isn't fully booted. Clients of this class may not have
                // direct access to (be notified when) the system is ready, so retry
                // setting the alarm after some delay. Leave enough time so that we don't cause
                // any unneeded startup delay.
                mHandler.postDelayed(this, 30_000);
                return;
            }
            final long nextTriggerTimeElapsed;
            final long minTimeBetweenAlarmsMs;
            synchronized (mLock) {
                if (mTriggerTimeElapsed == NOT_SCHEDULED) {
                    return;
                }
                nextTriggerTimeElapsed = mTriggerTimeElapsed;
                minTimeBetweenAlarmsMs = mMinTimeBetweenAlarmsMs;
            }
            // Never call out to AlarmManager with the lock held. This could sit below AM.
            if (mExactAlarm) {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME,
                        nextTriggerTimeElapsed, mAlarmTag, AlarmQueue.this, mHandler);
            } else {
                alarmManager.setWindow(AlarmManager.ELAPSED_REALTIME,
                        nextTriggerTimeElapsed, minTimeBetweenAlarmsMs / 2,
                        mAlarmTag, AlarmQueue.this, mHandler);
            }
        }
    };

    private final Object mLock = new Object();

    private final Context mContext;
    private final Handler mHandler;
    private final Injector mInjector;

    @GuardedBy("mLock")
    private final AlarmPriorityQueue<K> mAlarmPriorityQueue = new AlarmPriorityQueue<>();
    private final String mAlarmTag;
    private final String mDumpTitle;
    /** Whether to use an exact alarm or an inexact alarm. */
    private final boolean mExactAlarm;
    /** The minimum amount of time between check alarms. */
    @GuardedBy("mLock")
    private long mMinTimeBetweenAlarmsMs;
    /** The next time the alarm is set to go off, in the elapsed realtime timebase. */
    @GuardedBy("mLock")
    @ElapsedRealtimeLong
    private long mTriggerTimeElapsed = NOT_SCHEDULED;

    /**
     * @param alarmTag               The tag to use when scheduling the alarm with AlarmManager.
     * @param dumpTitle              The title to use when dumping state.
     * @param exactAlarm             Whether or not to use an exact alarm. If false, this will use
     *                               an inexact window alarm.
     * @param minTimeBetweenAlarmsMs The minimum amount of time that should be between alarms. If
     *                               one alarm will go off too soon after another, the second one
     *                               will be delayed to meet this minimum time.
     */
    public AlarmQueue(@NonNull Context context, @NonNull Looper looper, @NonNull String alarmTag,
            @NonNull String dumpTitle, boolean exactAlarm, long minTimeBetweenAlarmsMs) {
        this(context, looper, alarmTag, dumpTitle, exactAlarm, minTimeBetweenAlarmsMs,
                new Injector());
    }

    @VisibleForTesting
    AlarmQueue(@NonNull Context context, @NonNull Looper looper, @NonNull String alarmTag,
            @NonNull String dumpTitle, boolean exactAlarm, long minTimeBetweenAlarmsMs,
            @NonNull Injector injector) {
        mContext = context;
        mAlarmTag = alarmTag;
        mDumpTitle = dumpTitle.trim();
        mExactAlarm = exactAlarm;
        mHandler = new Handler(looper);
        mInjector = injector;
        if (minTimeBetweenAlarmsMs < 0) {
            throw new IllegalArgumentException("min time between alarms must be non-negative");
        }
        mMinTimeBetweenAlarmsMs = minTimeBetweenAlarmsMs;
    }

    /**
     * Add an alarm for the specified key that should go off at the provided time
     * (in the elapsed realtime timebase). This will also remove any existing alarm for the key.
     */
    public void addAlarm(K key, @ElapsedRealtimeLong long alarmTimeElapsed) {
        synchronized (mLock) {
            final boolean removed = mAlarmPriorityQueue.removeKey(key);
            mAlarmPriorityQueue.offer(new Pair<>(key, alarmTimeElapsed));
            if (mTriggerTimeElapsed == NOT_SCHEDULED || removed
                    || alarmTimeElapsed < mTriggerTimeElapsed) {
                setNextAlarmLocked();
            }
        }
    }

    /**
     * Get the current minimum time between alarms.
     *
     * @see #setMinTimeBetweenAlarmsMs(long)
     */
    public long getMinTimeBetweenAlarmsMs() {
        synchronized (mLock) {
            return mMinTimeBetweenAlarmsMs;
        }
    }

    /** Remove the alarm for this specific key. */
    public void removeAlarmForKey(K key) {
        synchronized (mLock) {
            if (mAlarmPriorityQueue.removeKey(key)) {
                setNextAlarmLocked();
            }
        }
    }

    /** Remove all alarms tied to the specified user. */
    public void removeAlarmsForUserId(@UserIdInt int userId) {
        boolean removed = false;
        synchronized (mLock) {
            Pair[] alarms = mAlarmPriorityQueue.toArray(new Pair[mAlarmPriorityQueue.size()]);
            for (int i = alarms.length - 1; i >= 0; --i) {
                final K key = (K) alarms[i].first;
                if (isForUser(key, userId)) {
                    mAlarmPriorityQueue.remove(alarms[i]);
                    removed = true;
                }
            }
            if (removed) {
                setNextAlarmLocked();
            }
        }
    }

    /** Cancel and remove all alarms. */
    public void removeAllAlarms() {
        synchronized (mLock) {
            mAlarmPriorityQueue.clear();
            setNextAlarmLocked(0);
        }
    }

    /** Remove all alarms that satisfy the predicate. */
    protected void removeAlarmsIf(@NonNull Predicate<K> predicate) {
        boolean removed = false;
        synchronized (mLock) {
            Pair[] alarms = mAlarmPriorityQueue.toArray(new Pair[mAlarmPriorityQueue.size()]);
            for (int i = alarms.length - 1; i >= 0; --i) {
                final K key = (K) alarms[i].first;
                if (predicate.test(key)) {
                    mAlarmPriorityQueue.remove(alarms[i]);
                    removed = true;
                }
            }
            if (removed) {
                setNextAlarmLocked();
            }
        }
    }

    /**
     * Update the minimum time that should be between alarms. This helps avoid thrashing when alarms
     * are scheduled very closely together and may result in some batching of expired alarms.
     */
    public void setMinTimeBetweenAlarmsMs(long minTimeMs) {
        if (minTimeMs < 0) {
            throw new IllegalArgumentException("min time between alarms must be non-negative");
        }
        synchronized (mLock) {
            mMinTimeBetweenAlarmsMs = minTimeMs;
        }
    }

    /** Return true if the key is for the specified user. */
    protected abstract boolean isForUser(@NonNull K key, int userId);

    /** Handle all of the alarms that have now expired (their trigger time has passed). */
    protected abstract void processExpiredAlarms(@NonNull ArraySet<K> expired);

    /** Sets an alarm with {@link AlarmManager} for the earliest alarm in the queue after now. */
    @GuardedBy("mLock")
    private void setNextAlarmLocked() {
        setNextAlarmLocked(mInjector.getElapsedRealtime());
    }

    /**
     * Sets an alarm with {@link AlarmManager} for the earliest alarm in the queue, using
     * {@code earliestTriggerElapsed} as a floor.
     */
    @GuardedBy("mLock")
    private void setNextAlarmLocked(long earliestTriggerElapsed) {
        if (mAlarmPriorityQueue.size() == 0) {
            mHandler.post(() -> {
                // Never call out to AlarmManager with the lock held. This could sit below AM.
                final AlarmManager alarmManager = mContext.getSystemService(AlarmManager.class);
                if (alarmManager != null) {
                    // This should only be null at boot time. No concerns around not
                    // cancelling if we get null here, so no need to retry.
                    alarmManager.cancel(this);
                }
            });
            mTriggerTimeElapsed = NOT_SCHEDULED;
            return;
        }

        final Pair<K, Long> alarm = mAlarmPriorityQueue.peek();
        final long nextTriggerTimeElapsed = Math.max(earliestTriggerElapsed, alarm.second);
        // Only schedule the alarm if one of the following is true:
        // 1. There isn't one currently scheduled
        // 2. The new alarm is significantly earlier than the previous alarm. If it's
        // earlier but not significantly so, then we essentially delay the check for some
        // apps by up to a minute.
        // 3. The alarm is after the current alarm.
        if (mTriggerTimeElapsed == NOT_SCHEDULED
                || nextTriggerTimeElapsed < mTriggerTimeElapsed - MINUTE_IN_MILLIS
                || mTriggerTimeElapsed < nextTriggerTimeElapsed) {
            if (DEBUG) {
                Slog.d(TAG, "Scheduling alarm at " + nextTriggerTimeElapsed
                        + " for key " + alarm.first);
            }
            mTriggerTimeElapsed = nextTriggerTimeElapsed;
            mHandler.post(mScheduleAlarmRunnable);
        }
    }

    @Override
    public void onAlarm() {
        final ArraySet<K> expired = new ArraySet<>();
        synchronized (mLock) {
            final long nowElapsed = mInjector.getElapsedRealtime();
            while (mAlarmPriorityQueue.size() > 0) {
                final Pair<K, Long> alarm = mAlarmPriorityQueue.peek();
                if (alarm.second <= nowElapsed) {
                    expired.add(alarm.first);
                    mAlarmPriorityQueue.remove(alarm);
                } else {
                    break;
                }
            }
            setNextAlarmLocked(nowElapsed + mMinTimeBetweenAlarmsMs);
        }
        // Don't "call out" with the lock held to avoid potential deadlocks.
        if (expired.size() > 0) {
            processExpiredAlarms(expired);
        }
    }

    /** Dump internal state. */
    public void dump(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.print(mDumpTitle);
            pw.println(" alarms:");
            pw.increaseIndent();

            if (mAlarmPriorityQueue.size() == 0) {
                pw.println("NOT WAITING");
            } else {
                Pair[] alarms = mAlarmPriorityQueue.toArray(new Pair[mAlarmPriorityQueue.size()]);
                for (int i = 0; i < alarms.length; ++i) {
                    final K key = (K) alarms[i].first;
                    pw.print(key);
                    pw.print(": ");
                    pw.print(alarms[i].second);
                    pw.println();
                }
            }

            pw.decreaseIndent();
        }
    }
}
