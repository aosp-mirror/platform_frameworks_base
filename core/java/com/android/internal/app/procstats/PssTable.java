/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

import static com.android.internal.app.procstats.ProcessStats.PSS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_COUNT;
import static com.android.internal.app.procstats.ProcessStats.PSS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_RSS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_RSS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_RSS_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_SAMPLE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_MINIMUM;

import android.service.procstats.ProcessStatsStateProto;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

/**
 * Class to accumulate PSS data.
 */
public class PssTable extends SparseMappingTable.Table {
    /**
     * Construct the PssTable with 'tableData' as backing store
     * for the longs data.
     */
    public PssTable(SparseMappingTable tableData) {
        super(tableData);
    }

    /**
     * Merge the the values from the other table into this one.
     */
    public void mergeStats(PssTable that) {
        final int N = that.getKeyCount();
        for (int i=0; i<N; i++) {
            final int thatKey = that.getKeyAt(i);
            final int state = SparseMappingTable.getIdFromKey(thatKey);

            final int key = getOrAddKey((byte)state, PSS_COUNT);
            final long[] stats = getArrayForKey(key);
            final int statsIndex = SparseMappingTable.getIndexFromKey(key);

            final long[] thatStats = that.getArrayForKey(thatKey);
            final int thatStatsIndex = SparseMappingTable.getIndexFromKey(thatKey);

            mergeStats(stats, statsIndex, thatStats, thatStatsIndex);
        }
    }

    /**
     * Merge the supplied PSS data in.  The new min pss will be the minimum of the existing
     * one and the new one, the average will now incorporate the new average, etc.
     */
    public void mergeStats(int state, int inCount, long minPss, long avgPss, long maxPss,
            long minUss, long avgUss, long maxUss, long minRss, long avgRss, long maxRss) {
        final int key = getOrAddKey((byte)state, PSS_COUNT);
        final long[] stats = getArrayForKey(key);
        final int statsIndex = SparseMappingTable.getIndexFromKey(key);
        mergeStats(stats, statsIndex, inCount, minPss, avgPss, maxPss, minUss, avgUss, maxUss,
                minRss, avgRss, maxRss);
    }

    public static void mergeStats(final long[] stats, final int statsIndex,
            final long[] thatStats, int thatStatsIndex) {
        mergeStats(stats, statsIndex, (int)thatStats[thatStatsIndex + PSS_SAMPLE_COUNT],
                thatStats[thatStatsIndex + PSS_MINIMUM],
                thatStats[thatStatsIndex + PSS_AVERAGE],
                thatStats[thatStatsIndex + PSS_MAXIMUM],
                thatStats[thatStatsIndex + PSS_USS_MINIMUM],
                thatStats[thatStatsIndex + PSS_USS_AVERAGE],
                thatStats[thatStatsIndex + PSS_USS_MAXIMUM],
                thatStats[thatStatsIndex + PSS_RSS_MINIMUM],
                thatStats[thatStatsIndex + PSS_RSS_AVERAGE],
                thatStats[thatStatsIndex + PSS_RSS_MAXIMUM]);
    }

    public static void mergeStats(final long[] stats, final int statsIndex, final int inCount,
            final long minPss, final long avgPss, final long maxPss,
            final long minUss, final long avgUss, final long maxUss,
            final long minRss, final long avgRss, final long maxRss) {
        final long count = stats[statsIndex + PSS_SAMPLE_COUNT];
        if (count == 0) {
            stats[statsIndex + PSS_SAMPLE_COUNT] = inCount;
            stats[statsIndex + PSS_MINIMUM] = minPss;
            stats[statsIndex + PSS_AVERAGE] = avgPss;
            stats[statsIndex + PSS_MAXIMUM] = maxPss;
            stats[statsIndex + PSS_USS_MINIMUM] = minUss;
            stats[statsIndex + PSS_USS_AVERAGE] = avgUss;
            stats[statsIndex + PSS_USS_MAXIMUM] = maxUss;
            stats[statsIndex + PSS_RSS_MINIMUM] = minRss;
            stats[statsIndex + PSS_RSS_AVERAGE] = avgRss;
            stats[statsIndex + PSS_RSS_MAXIMUM] = maxRss;
        } else {
            stats[statsIndex + PSS_SAMPLE_COUNT] = count + inCount;

            if (stats[statsIndex + PSS_MINIMUM] > minPss) {
                stats[statsIndex + PSS_MINIMUM] = minPss;
            }

            stats[statsIndex + PSS_AVERAGE] = (long)(((stats[statsIndex + PSS_AVERAGE]
                    * (double)count) + (avgPss * (double)inCount)) / (count + inCount));

            if (stats[statsIndex + PSS_MAXIMUM] < maxPss) {
                stats[statsIndex + PSS_MAXIMUM] = maxPss;
            }

            if (stats[statsIndex + PSS_USS_MINIMUM] > minUss) {
                stats[statsIndex + PSS_USS_MINIMUM] = minUss;
            }

            stats[statsIndex + PSS_USS_AVERAGE] = (long)(((stats[statsIndex + PSS_USS_AVERAGE]
                    * (double)count) + (avgUss * (double)inCount)) / (count + inCount));

            if (stats[statsIndex + PSS_USS_MAXIMUM] < maxUss) {
                stats[statsIndex + PSS_USS_MAXIMUM] = maxUss;
            }

            if (stats[statsIndex + PSS_RSS_MINIMUM] > minRss) {
            }

            stats[statsIndex + PSS_RSS_AVERAGE] = (long)(((stats[statsIndex + PSS_RSS_AVERAGE]
                    * (double)count) + (avgRss * (double)inCount)) / (count + inCount));

            if (stats[statsIndex + PSS_RSS_MAXIMUM] < maxRss) {
                stats[statsIndex + PSS_RSS_MAXIMUM] = maxRss;
            }
        }
    }

    public void writeStatsToProtoForKey(ProtoOutputStream proto, int key) {
        final long[] stats = getArrayForKey(key);
        final int statsIndex = SparseMappingTable.getIndexFromKey(key);
        writeStatsToProto(proto, stats, statsIndex);
    }

    public static void writeStatsToProto(ProtoOutputStream proto, final long[] stats,
            final int statsIndex) {
        proto.write(ProcessStatsStateProto.SAMPLE_SIZE, stats[statsIndex + PSS_SAMPLE_COUNT]);
        ProtoUtils.toAggStatsProto(proto, ProcessStatsStateProto.PSS,
                stats[statsIndex + PSS_MINIMUM],
                stats[statsIndex + PSS_AVERAGE],
                stats[statsIndex + PSS_MAXIMUM]);
        ProtoUtils.toAggStatsProto(proto, ProcessStatsStateProto.USS,
                stats[statsIndex + PSS_USS_MINIMUM],
                stats[statsIndex + PSS_USS_AVERAGE],
                stats[statsIndex + PSS_USS_MAXIMUM]);
        ProtoUtils.toAggStatsProto(proto, ProcessStatsStateProto.RSS,
                stats[statsIndex + PSS_RSS_MINIMUM],
                stats[statsIndex + PSS_RSS_AVERAGE],
                stats[statsIndex + PSS_RSS_MAXIMUM]);
    }

    long[] getRssMeanAndMax(int key) {
        final long[] stats = getArrayForKey(key);
        final int statsIndex = SparseMappingTable.getIndexFromKey(key);
        return new long[]{stats[statsIndex + PSS_RSS_AVERAGE], stats[statsIndex + PSS_RSS_MAXIMUM]};
    }
}
