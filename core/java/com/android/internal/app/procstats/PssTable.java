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

import static com.android.internal.app.procstats.ProcessStats.PSS_SAMPLE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.PSS_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.PSS_USS_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.PSS_COUNT;

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
            final int key = that.getKeyAt(i);
            final int state = SparseMappingTable.getIdFromKey(key);
            mergeStats(state, (int)that.getValue(key, PSS_SAMPLE_COUNT),
                    that.getValue(key, PSS_MINIMUM),
                    that.getValue(key, PSS_AVERAGE),
                    that.getValue(key, PSS_MAXIMUM),
                    that.getValue(key, PSS_USS_MINIMUM),
                    that.getValue(key, PSS_USS_AVERAGE),
                    that.getValue(key, PSS_USS_MAXIMUM));
        }
    }

    /**
     * Merge the supplied PSS data in.  The new min pss will be the minimum of the existing
     * one and the new one, the average will now incorporate the new average, etc.
     */
    public void mergeStats(int state, int inCount, long minPss, long avgPss, long maxPss,
            long minUss, long avgUss, long maxUss) {
        final int key = getOrAddKey((byte)state, PSS_COUNT);
        final long count = getValue(key, PSS_SAMPLE_COUNT);
        if (count == 0) {
            setValue(key, PSS_SAMPLE_COUNT, inCount);
            setValue(key, PSS_MINIMUM, minPss);
            setValue(key, PSS_AVERAGE, avgPss);
            setValue(key, PSS_MAXIMUM, maxPss);
            setValue(key, PSS_USS_MINIMUM, minUss);
            setValue(key, PSS_USS_AVERAGE, avgUss);
            setValue(key, PSS_USS_MAXIMUM, maxUss);
        } else {
            setValue(key, PSS_SAMPLE_COUNT, count + inCount);

            long val;

            val = getValue(key, PSS_MINIMUM);
            if (val > minPss) {
                setValue(key, PSS_MINIMUM, minPss);
            }

            val = getValue(key, PSS_AVERAGE);
            setValue(key, PSS_AVERAGE,
                    (long)(((val*(double)count)+(avgPss*(double)inCount)) / (count+inCount)));

            val = getValue(key, PSS_MAXIMUM);
            if (val < maxPss) {
                setValue(key, PSS_MAXIMUM, maxPss);
            }

            val = getValue(key, PSS_USS_MINIMUM);
            if (val > minUss) {
                setValue(key, PSS_USS_MINIMUM, minUss);
            }

            val = getValue(key, PSS_USS_AVERAGE);
            setValue(key, PSS_USS_AVERAGE,
                    (long)(((val*(double)count)+(avgUss*(double)inCount)) / (count+inCount)));

            val = getValue(key, PSS_USS_MAXIMUM);
            if (val < maxUss) {
                setValue(key, PSS_USS_MAXIMUM, maxUss);
            }
        }
    }
}
