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

package com.android.server.alarm;

import static com.android.server.alarm.AlarmManagerService.DEBUG_BATCH;
import static com.android.server.alarm.AlarmManagerService.clampPositive;
import static com.android.server.alarm.AlarmManagerService.dumpAlarmList;
import static com.android.server.alarm.AlarmManagerService.isTimeTickAlarm;

import android.app.AlarmManager;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.StatLogger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * Batching implementation of an Alarm Store.
 * This keeps the alarms in batches, which are sorted on the start time of their delivery window.
 */
public class BatchingAlarmStore implements AlarmStore {
    @VisibleForTesting
    static final String TAG = BatchingAlarmStore.class.getSimpleName();

    private final ArrayList<Batch> mAlarmBatches = new ArrayList<>();
    private int mSize;
    private Runnable mOnAlarmClockRemoved;

    interface Stats {
        int REBATCH_ALL_ALARMS = 0;
        int GET_COUNT = 1;
    }

    final StatLogger mStatLogger = new StatLogger(TAG + " stats", new String[]{
            "REBATCH_ALL_ALARMS",
            "GET_COUNT",
    });

    private static final Comparator<Batch> sBatchOrder = Comparator.comparingLong(b -> b.mStart);

    private static final Comparator<Alarm> sIncreasingTimeOrder = Comparator.comparingLong(
            Alarm::getWhenElapsed);

    @Override
    public void add(Alarm a) {
        insertAndBatchAlarm(a);
        mSize++;
    }

    @Override
    public void addAll(ArrayList<Alarm> alarms) {
        if (alarms == null) {
            return;
        }
        for (final Alarm a : alarms) {
            add(a);
        }
    }

    @Override
    public ArrayList<Alarm> remove(Predicate<Alarm> whichAlarms) {
        final ArrayList<Alarm> removed = new ArrayList<>();
        for (int i = mAlarmBatches.size() - 1; i >= 0; i--) {
            final Batch b = mAlarmBatches.get(i);
            removed.addAll(b.remove(whichAlarms));
            if (b.size() == 0) {
                mAlarmBatches.remove(i);
            }
        }
        if (!removed.isEmpty()) {
            mSize -= removed.size();
            // Not needed if only whole batches were removed, but keeping existing behavior.
            rebatchAllAlarms();
        }
        return removed;
    }

    @Override
    public void setAlarmClockRemovalListener(Runnable listener) {
        mOnAlarmClockRemoved = listener;
    }

    @Override
    public Alarm getNextWakeFromIdleAlarm() {
        for (final Batch batch : mAlarmBatches) {
            if ((batch.mFlags & AlarmManager.FLAG_WAKE_FROM_IDLE) == 0) {
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                final Alarm a = batch.get(i);
                if ((a.flags & AlarmManager.FLAG_WAKE_FROM_IDLE) != 0) {
                    return a;
                }
            }
        }
        return null;
    }

    private void rebatchAllAlarms() {
        final long start = mStatLogger.getTime();
        final ArrayList<Batch> oldBatches = (ArrayList<Batch>) mAlarmBatches.clone();
        mAlarmBatches.clear();
        for (final Batch batch : oldBatches) {
            for (int i = 0; i < batch.size(); i++) {
                insertAndBatchAlarm(batch.get(i));
            }
        }
        mStatLogger.logDurationStat(Stats.REBATCH_ALL_ALARMS, start);
    }

    @Override
    public int size() {
        return mSize;
    }

    @Override
    public long getNextWakeupDeliveryTime() {
        for (Batch b : mAlarmBatches) {
            if (b.hasWakeups()) {
                return b.mStart;
            }
        }
        return 0;
    }

    @Override
    public long getNextDeliveryTime() {
        if (mAlarmBatches.size() > 0) {
            return mAlarmBatches.get(0).mStart;
        }
        return 0;
    }

    @Override
    public ArrayList<Alarm> removePendingAlarms(long nowElapsed) {
        final ArrayList<Alarm> removedAlarms = new ArrayList<>();
        while (mAlarmBatches.size() > 0) {
            final Batch batch = mAlarmBatches.get(0);
            if (batch.mStart > nowElapsed) {
                break;
            }
            mAlarmBatches.remove(0);
            for (int i = 0; i < batch.size(); i++) {
                removedAlarms.add(batch.get(i));
            }
        }
        mSize -= removedAlarms.size();
        return removedAlarms;
    }

    @Override
    public boolean updateAlarmDeliveries(AlarmDeliveryCalculator deliveryCalculator) {
        boolean changed = false;
        for (final Batch b : mAlarmBatches) {
            for (int i = 0; i < b.size(); i++) {
                changed |= deliveryCalculator.updateAlarmDelivery(b.get(i));
            }
        }
        if (changed) {
            rebatchAllAlarms();
        }
        return changed;
    }

    @Override
    public ArrayList<Alarm> asList() {
        final ArrayList<Alarm> allAlarms = new ArrayList<>();
        for (final Batch batch : mAlarmBatches) {
            for (int i = 0; i < batch.size(); i++) {
                allAlarms.add(batch.get(i));
            }
        }
        return allAlarms;
    }

    @Override
    public void dump(IndentingPrintWriter ipw, long nowElapsed, SimpleDateFormat sdf) {
        ipw.print("Pending alarm batches: ");
        ipw.println(mAlarmBatches.size());
        for (Batch b : mAlarmBatches) {
            ipw.print(b);
            ipw.println(':');
            ipw.increaseIndent();
            dumpAlarmList(ipw, b.mAlarms, nowElapsed, sdf);
            ipw.decreaseIndent();
        }
        mStatLogger.dump(ipw);
    }

    @Override
    public void dumpProto(ProtoOutputStream pos, long nowElapsed) {
        for (Batch b : mAlarmBatches) {
            b.dumpDebug(pos, AlarmManagerServiceDumpProto.PENDING_ALARM_BATCHES, nowElapsed);
        }
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public int getCount(Predicate<Alarm> condition) {
        long start = mStatLogger.getTime();

        int count = 0;
        for (Batch b : mAlarmBatches) {
            for (int i = 0; i < b.size(); i++) {
                if (condition.test(b.get(i))) {
                    count++;
                }
            }
        }
        mStatLogger.logDurationStat(Stats.GET_COUNT, start);
        return count;
    }

    private void insertAndBatchAlarm(Alarm alarm) {
        final int whichBatch = ((alarm.flags & AlarmManager.FLAG_STANDALONE) != 0) ? -1
                : attemptCoalesce(alarm.getWhenElapsed(), alarm.getMaxWhenElapsed());

        if (whichBatch < 0) {
            addBatch(mAlarmBatches, new Batch(alarm));
        } else {
            final Batch batch = mAlarmBatches.get(whichBatch);
            if (batch.add(alarm)) {
                // The start time of this batch advanced, so batch ordering may
                // have just been broken.  Move it to where it now belongs.
                mAlarmBatches.remove(whichBatch);
                addBatch(mAlarmBatches, batch);
            }
        }
    }

    static void addBatch(ArrayList<Batch> list, Batch newBatch) {
        int index = Collections.binarySearch(list, newBatch, sBatchOrder);
        if (index < 0) {
            index = 0 - index - 1;
        }
        list.add(index, newBatch);
    }

    // Return the index of the matching batch, or -1 if none found.
    private int attemptCoalesce(long whenElapsed, long maxWhen) {
        final int n = mAlarmBatches.size();
        for (int i = 0; i < n; i++) {
            Batch b = mAlarmBatches.get(i);
            if ((b.mFlags & AlarmManager.FLAG_STANDALONE) == 0 && b.canHold(whenElapsed, maxWhen)) {
                return i;
            }
        }
        return -1;
    }

    final class Batch {
        long mStart;     // These endpoints are always in ELAPSED
        long mEnd;
        int mFlags;      // Flags for alarms, such as FLAG_STANDALONE.

        final ArrayList<Alarm> mAlarms = new ArrayList<>();

        Batch(Alarm seed) {
            mStart = seed.getWhenElapsed();
            mEnd = clampPositive(seed.getMaxWhenElapsed());
            mFlags = seed.flags;
            mAlarms.add(seed);
        }

        int size() {
            return mAlarms.size();
        }

        Alarm get(int index) {
            return mAlarms.get(index);
        }

        boolean canHold(long whenElapsed, long maxWhen) {
            return (mEnd >= whenElapsed) && (mStart <= maxWhen);
        }

        boolean add(Alarm alarm) {
            boolean newStart = false;
            // narrows the batch if necessary; presumes that canHold(alarm) is true
            int index = Collections.binarySearch(mAlarms, alarm, sIncreasingTimeOrder);
            if (index < 0) {
                index = 0 - index - 1;
            }
            mAlarms.add(index, alarm);
            if (DEBUG_BATCH) {
                Slog.v(TAG, "Adding " + alarm + " to " + this);
            }
            if (alarm.getWhenElapsed() > mStart) {
                mStart = alarm.getWhenElapsed();
                newStart = true;
            }
            if (alarm.getMaxWhenElapsed() < mEnd) {
                mEnd = alarm.getMaxWhenElapsed();
            }
            mFlags |= alarm.flags;

            if (DEBUG_BATCH) {
                Slog.v(TAG, "    => now " + this);
            }
            return newStart;
        }

        ArrayList<Alarm> remove(Predicate<Alarm> predicate) {
            final ArrayList<Alarm> removed = new ArrayList<>();
            long newStart = 0;  // recalculate endpoints as we go
            long newEnd = Long.MAX_VALUE;
            int newFlags = 0;
            for (int i = 0; i < mAlarms.size(); ) {
                Alarm alarm = mAlarms.get(i);
                if (predicate.test(alarm)) {
                    removed.add(mAlarms.remove(i));
                    if (alarm.alarmClock != null && mOnAlarmClockRemoved != null) {
                        mOnAlarmClockRemoved.run();
                    }
                    if (isTimeTickAlarm(alarm)) {
                        // This code path is not invoked when delivering alarms, only when removing
                        // alarms due to the caller cancelling it or getting uninstalled, etc.
                        Slog.wtf(TAG, "Removed TIME_TICK alarm");
                    }
                } else {
                    if (alarm.getWhenElapsed() > newStart) {
                        newStart = alarm.getWhenElapsed();
                    }
                    if (alarm.getMaxWhenElapsed() < newEnd) {
                        newEnd = alarm.getMaxWhenElapsed();
                    }
                    newFlags |= alarm.flags;
                    i++;
                }
            }
            if (!removed.isEmpty()) {
                // commit the new batch bounds
                mStart = newStart;
                mEnd = newEnd;
                mFlags = newFlags;
            }
            return removed;
        }

        boolean hasWakeups() {
            final int n = mAlarms.size();
            for (int i = 0; i < n; i++) {
                Alarm a = mAlarms.get(i);
                if (a.wakeup) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(40);
            b.append("Batch{");
            b.append(Integer.toHexString(this.hashCode()));
            b.append(" num=");
            b.append(size());
            b.append(" start=");
            b.append(mStart);
            b.append(" end=");
            b.append(mEnd);
            if (mFlags != 0) {
                b.append(" flgs=0x");
                b.append(Integer.toHexString(mFlags));
            }
            b.append('}');
            return b.toString();
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId, long nowElapsed) {
            final long token = proto.start(fieldId);

            proto.write(BatchProto.START_REALTIME, mStart);
            proto.write(BatchProto.END_REALTIME, mEnd);
            proto.write(BatchProto.FLAGS, mFlags);
            for (Alarm a : mAlarms) {
                a.dumpDebug(proto, BatchProto.ALARMS, nowElapsed);
            }

            proto.end(token);
        }
    }
}
