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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.util.IndentingPrintWriter;
import android.util.proto.ProtoOutputStream;

import com.android.server.vibrator.GroupedAggregatedLogRecords.AggregatedLogRecord;
import com.android.server.vibrator.GroupedAggregatedLogRecords.SingleLogRecord;

import org.junit.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GroupedAggregatedLogRecordsTest {

    private static final int AGGREGATION_TIME_LIMIT = 1000;
    private static final int NO_AGGREGATION_TIME_LIMIT = 0;
    private static final long PROTO_FIELD_ID = 1;
    private static final int GROUP_1 = 1;
    private static final int GROUP_2 = 2;
    private static final int KEY_1 = 1;
    private static final int KEY_2 = 2;

    private static final IndentingPrintWriter WRITER = new IndentingPrintWriter(new StringWriter());
    private static final ProtoOutputStream PROTO_OUTPUT_STREAM = new ProtoOutputStream();

    private final List<TestSingleLogRecord> mTestRecords = new ArrayList<>();

    @Test
    public void record_noAggregation_keepsIndividualRecords() {
        int sizeLimit = 10;
        long createTime = 100;
        TestGroupedAggregatedLogRecords records = new TestGroupedAggregatedLogRecords(
                sizeLimit, NO_AGGREGATION_TIME_LIMIT, PROTO_FIELD_ID);

        for (int i = 0; i < sizeLimit; i++) {
            assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime++))).isNull();
        }

        dumpRecords(records);
        assertGroupHeadersWrittenOnce(records, GROUP_1);
        assertRecordsInRangeWrittenOnce(0, sizeLimit);
    }

    @Test
    public void record_sizeLimit_dropsOldestEntriesForNewOnes() {
        long createTime = 100;
        TestGroupedAggregatedLogRecords records = new TestGroupedAggregatedLogRecords(
                /* sizeLimit= */ 2, NO_AGGREGATION_TIME_LIMIT, PROTO_FIELD_ID);

        TestSingleLogRecord firstRecord = createRecord(GROUP_1, KEY_1, createTime++);
        assertThat(records.add(firstRecord)).isNull();
        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime++))).isNull();

        // Adding third record drops first record
        AggregatedLogRecord<TestSingleLogRecord> droppedRecord =
                records.add(createRecord(GROUP_1, KEY_1, createTime++));
        assertThat(droppedRecord).isNotNull();
        assertThat(droppedRecord.getLatest()).isEqualTo(firstRecord);

        dumpRecords(records);
        assertGroupHeadersWrittenOnce(records, GROUP_1);
        assertRecordsInRangeNotWritten(0, 1);  // First record not written
        assertRecordsInRangeWrittenOnce(1, 3); // All newest records written
    }

    @Test
    public void record_timeAggregation_aggregatesCloseRecordAndPrintsOnlyFirstAndLast() {
        long createTime = 100;
        TestGroupedAggregatedLogRecords records = new TestGroupedAggregatedLogRecords(
                /* sizeLimit= */ 1, AGGREGATION_TIME_LIMIT, PROTO_FIELD_ID);

        // No record dropped, all aggregated in a single entry
        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime))).isNull();
        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime + 1))).isNull();
        assertThat(records.add(createRecord(GROUP_1, KEY_1,
                createTime + AGGREGATION_TIME_LIMIT - 2))).isNull();
        assertThat(records.add(createRecord(GROUP_1, KEY_1,
                createTime + AGGREGATION_TIME_LIMIT - 1))).isNull();

        dumpRecords(records);
        assertGroupHeadersWrittenOnce(records, GROUP_1);
        assertRecordsInRangeWrittenOnce(0, 1); // Writes first record
        assertRecordsInRangeNotWritten(1, 3);  // Skips aggregated records in between
        assertRecordsInRangeWrittenOnce(3, 4); // Writes last record
    }

    @Test
    public void record_differentGroups_recordsKeptSeparate() {
        long createTime = 100;
        TestGroupedAggregatedLogRecords records = new TestGroupedAggregatedLogRecords(
                /* sizeLimit= */ 1, AGGREGATION_TIME_LIMIT, PROTO_FIELD_ID);

        // No record dropped, all kept in separate aggregated lists
        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime++))).isNull();
        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime++))).isNull();
        assertThat(records.add(createRecord(GROUP_2, KEY_2, createTime++))).isNull();
        assertThat(records.add(createRecord(GROUP_2, KEY_2, createTime++))).isNull();

        dumpRecords(records);
        assertGroupHeadersWrittenOnce(records, GROUP_1, GROUP_2);
        assertRecordsInRangeWrittenOnce(0, 4);
    }

    @Test
    public void record_sameGroupDifferentAggregationKeys_recordsNotAggregated() {
        long createTime = 100;
        TestGroupedAggregatedLogRecords records = new TestGroupedAggregatedLogRecords(
                /* sizeLimit= */ 1, AGGREGATION_TIME_LIMIT, PROTO_FIELD_ID);

        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime++))).isNull();

        // Second record on same group with different key not aggregated, drops first record
        AggregatedLogRecord<TestSingleLogRecord> droppedRecord =
                records.add(createRecord(GROUP_1, KEY_2, createTime++));
        assertThat(droppedRecord).isNotNull();
        assertThat(droppedRecord.getLatest()).isEqualTo(mTestRecords.get(0));

        dumpRecords(records);
        assertGroupHeadersWrittenOnce(records, GROUP_1);
        assertRecordsInRangeNotWritten(0, 1);  // Skips first record that was dropped
        assertRecordsInRangeWrittenOnce(1, 2); // Writes last record
    }

    @Test
    public void record_sameGroupAndAggregationKeysDistantTimes_recordsNotAggregated() {
        long createTime = 100;
        TestGroupedAggregatedLogRecords records = new TestGroupedAggregatedLogRecords(
                /* sizeLimit= */ 1, AGGREGATION_TIME_LIMIT, PROTO_FIELD_ID);

        assertThat(records.add(createRecord(GROUP_1, KEY_1, createTime))).isNull();

        // Second record after aggregation time limit not aggregated, drops first record
        AggregatedLogRecord<TestSingleLogRecord> droppedRecord =
                records.add(createRecord(GROUP_1, KEY_1, createTime + AGGREGATION_TIME_LIMIT));
        assertThat(droppedRecord).isNotNull();
        assertThat(droppedRecord.getLatest()).isEqualTo(mTestRecords.get(0));

        dumpRecords(records);
        assertGroupHeadersWrittenOnce(records, GROUP_1);
        assertRecordsInRangeNotWritten(0, 1);  // Skips first record that was dropped
        assertRecordsInRangeWrittenOnce(1, 2); // Writes last record
    }

    private TestSingleLogRecord createRecord(int groupKey, int aggregateKey, long createTime) {
        TestSingleLogRecord record = new TestSingleLogRecord(groupKey, aggregateKey, createTime);
        mTestRecords.add(record);
        return record;
    }

    private void dumpRecords(TestGroupedAggregatedLogRecords records) {
        records.dump(WRITER);
        records.dump(PROTO_OUTPUT_STREAM);
    }

    private void assertGroupHeadersWrittenOnce(TestGroupedAggregatedLogRecords records,
            int... groupKeys) {
        assertThat(records.dumpGroupKeys).containsExactlyElementsIn(
                Arrays.stream(groupKeys).boxed().toList());
    }

    private void assertRecordsInRangeWrittenOnce(int startIndexInclusive, int endIndexExclusive) {
        for (int i = startIndexInclusive; i < endIndexExclusive; i++) {
            assertWithMessage("record index=" + i).that(mTestRecords.get(i).dumpTextCount)
                    .isEqualTo(1);
            assertWithMessage("record index=" + i).that(mTestRecords.get(i).dumpProtoFieldIds)
                    .containsExactly(PROTO_FIELD_ID);
        }
    }

    private void assertRecordsInRangeNotWritten(int startIndexInclusive, int endIndexExclusive) {
        for (int i = startIndexInclusive; i < endIndexExclusive; i++) {
            assertWithMessage("record index=" + i).that(mTestRecords.get(i).dumpTextCount)
                    .isEqualTo(0);
            assertWithMessage("record index=" + i).that(mTestRecords.get(i).dumpProtoFieldIds)
                    .isEmpty();
        }
    }

    private static final class TestGroupedAggregatedLogRecords
            extends GroupedAggregatedLogRecords<TestSingleLogRecord> {

        public final List<Integer> dumpGroupKeys = new ArrayList<>();

        private final long mProtoFieldId;

        TestGroupedAggregatedLogRecords(int sizeLimit, int aggregationTimeLimitMs,
                long protoFieldId) {
            super(sizeLimit, aggregationTimeLimitMs);
            mProtoFieldId = protoFieldId;
        }

        @Override
        void dumpGroupHeader(IndentingPrintWriter pw, int groupKey) {
            dumpGroupKeys.add(groupKey);
        }

        @Override
        long findGroupKeyProtoFieldId(int groupKey) {
            return mProtoFieldId;
        }
    }

    private static final class TestSingleLogRecord implements SingleLogRecord {
        public final List<Long> dumpProtoFieldIds = new ArrayList<>();
        public int dumpTextCount = 0;

        private final int mGroupKey;
        private final int mAggregateKey;
        private final long mCreateTime;

        TestSingleLogRecord(int groupKey, int aggregateKey, long createTime) {
            mGroupKey = groupKey;
            mAggregateKey = aggregateKey;
            mCreateTime = createTime;
        }

        @Override
        public int getGroupKey() {
            return mGroupKey;
        }

        @Override
        public long getCreateUptimeMs() {
            return mCreateTime;
        }

        @Override
        public boolean mayAggregate(SingleLogRecord record) {
            if (record instanceof TestSingleLogRecord param) {
                return mAggregateKey == param.mAggregateKey;
            }
            return false;
        }

        @Override
        public void dump(IndentingPrintWriter pw) {
            dumpTextCount++;
        }

        @Override
        public void dump(ProtoOutputStream proto, long fieldId) {
            dumpProtoFieldIds.add(fieldId);
        }
    }
}
