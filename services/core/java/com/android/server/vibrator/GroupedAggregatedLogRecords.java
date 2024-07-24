/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.os.SystemClock;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import java.util.ArrayDeque;

/**
 * A generic grouped list of aggregated log records to be printed in dumpsys.
 *
 * <p>This can be used to dump history of operations or requests to the vibrator services, e.g.
 * vibration requests grouped by usage or vibration parameters sent to the vibrator control service.
 *
 * @param <T> The type of log entries aggregated in this record.
 */
abstract class GroupedAggregatedLogRecords<T extends GroupedAggregatedLogRecords.SingleLogRecord> {
    private final SparseArray<ArrayDeque<AggregatedLogRecord<T>>> mGroupedRecords;
    private final int mSizeLimit;
    private final int mAggregationTimeLimitMs;

    GroupedAggregatedLogRecords(int sizeLimit, int aggregationTimeLimitMs) {
        mGroupedRecords = new SparseArray<>();
        mSizeLimit = sizeLimit;
        mAggregationTimeLimitMs = aggregationTimeLimitMs;
    }

    /** Prints a header to identify the group to be logged. */
    abstract void dumpGroupHeader(IndentingPrintWriter pw, int groupKey);

    /** Returns the {@link ProtoOutputStream} repeated field id to log records of this group. */
    abstract long findGroupKeyProtoFieldId(int groupKey);

    /**
     * Adds given entry to this record list, dropping the oldest record if size limit was reached
     * for its group.
     *
     * @param record The new {@link SingleLogRecord} to be recorded.
     * @return The oldest {@link AggregatedLogRecord} entry being dropped from the group list if
     * it's full, null otherwise.
     */
    final synchronized AggregatedLogRecord<T> add(T record) {
        int groupKey = record.getGroupKey();
        if (!mGroupedRecords.contains(groupKey)) {
            mGroupedRecords.put(groupKey, new ArrayDeque<>(mSizeLimit));
        }
        ArrayDeque<AggregatedLogRecord<T>> records = mGroupedRecords.get(groupKey);
        if (mAggregationTimeLimitMs > 0 && !records.isEmpty()) {
            AggregatedLogRecord<T> lastAggregatedRecord = records.getLast();
            if (lastAggregatedRecord.mayAggregate(record, mAggregationTimeLimitMs)) {
                lastAggregatedRecord.record(record);
                return null;
            }
        }
        AggregatedLogRecord<T> removedRecord = null;
        if (records.size() >= mSizeLimit) {
            removedRecord = records.removeFirst();
        }
        records.addLast(new AggregatedLogRecord<>(record));
        return removedRecord;
    }

    final synchronized void dump(IndentingPrintWriter pw) {
        for (int i = 0; i < mGroupedRecords.size(); i++) {
            dumpGroupHeader(pw, mGroupedRecords.keyAt(i));
            pw.increaseIndent();
            for (AggregatedLogRecord<T> records : mGroupedRecords.valueAt(i)) {
                records.dump(pw);
            }
            pw.decreaseIndent();
            pw.println();
        }
    }

    final synchronized void dump(ProtoOutputStream proto) {
        for (int i = 0; i < mGroupedRecords.size(); i++) {
            long fieldId = findGroupKeyProtoFieldId(mGroupedRecords.keyAt(i));
            for (AggregatedLogRecord<T> records : mGroupedRecords.valueAt(i)) {
                records.dump(proto, fieldId);
            }
        }
    }

    /**
     * Represents an aggregation of log record entries that can be printed in a compact manner.
     *
     * <p>The aggregation is controlled by a time limit on the difference between the creation time
     * of two consecutive entries that {@link SingleLogRecord#mayAggregate}.
     *
     * @param <T> The type of log entries aggregated in this record.
     */
    static final class AggregatedLogRecord<T extends SingleLogRecord> {
        private final T mFirst;
        private T mLatest;
        private int mCount;

        AggregatedLogRecord(T record) {
            mLatest = mFirst = record;
            mCount = 1;
        }

        T getLatest() {
            return mLatest;
        }

        synchronized boolean mayAggregate(T record, long timeLimitMs) {
            long timeDeltaMs = Math.abs(mLatest.getCreateUptimeMs() - record.getCreateUptimeMs());
            return mLatest.mayAggregate(record) && timeDeltaMs < timeLimitMs;
        }

        synchronized void record(T record) {
            mLatest = record;
            mCount++;
        }

        synchronized void dump(IndentingPrintWriter pw) {
            mFirst.dump(pw);
            if (mCount == 1) {
                return;
            }
            if (mCount > 2) {
                pw.println("-> Skipping " + (mCount - 2) + " aggregated entries, latest:");
            }
            mLatest.dump(pw);
        }

        synchronized void dump(ProtoOutputStream proto, long fieldId) {
            mFirst.dump(proto, fieldId);
            if (mCount > 1) {
                mLatest.dump(proto, fieldId);
            }
        }
    }

    /**
     * Represents a single log entry that can be grouped and aggregated for compact logging.
     *
     * <p>Entries are first grouped by an integer group key, and then aggregated with consecutive
     * entries of same group within a limited timespan.
     */
    interface SingleLogRecord {

        /** The group identifier for this record (e.g. vibration usage). */
        int getGroupKey();

        /**
         * The timestamp in millis that should be used for aggregation of close entries.
         *
         * <p>Should be {@link SystemClock#uptimeMillis()} to be used for calculations.
         */
        long getCreateUptimeMs();

        /**
         * Returns true if this record can be aggregated with the given one (e.g. the represent the
         * same vibration request from the same process client).
         */
        boolean mayAggregate(SingleLogRecord record);

        /** Writes this record into given {@link IndentingPrintWriter}. */
        void dump(IndentingPrintWriter pw);

        /** Writes this record into given {@link ProtoOutputStream} field. */
        void dump(ProtoOutputStream proto, long fieldId);
    }
}
