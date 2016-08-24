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

import android.util.DebugUtils;

import static com.android.internal.app.procstats.ProcessStats.STATE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.STATE_NOTHING;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_SAMPLE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_CACHED_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_CACHED_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_CACHED_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_FREE_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_FREE_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_FREE_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_ZRAM_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_ZRAM_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_ZRAM_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_KERNEL_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_KERNEL_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_KERNEL_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_NATIVE_MINIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_NATIVE_AVERAGE;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_NATIVE_MAXIMUM;
import static com.android.internal.app.procstats.ProcessStats.SYS_MEM_USAGE_COUNT;

import java.io.PrintWriter;


/**
 * Class to accumulate system mem usage data.
 */
public class SysMemUsageTable extends SparseMappingTable.Table {
    /**
     * Construct the SysMemUsageTable with 'tableData' as backing store
     * for the longs data.
     */
    public SysMemUsageTable(SparseMappingTable tableData) {
        super(tableData);
    }

    /**
     * Merge the stats given into our own values.
     *
     * @param that  SysMemUsageTable to copy from.
     */
    public void mergeStats(SysMemUsageTable that) {
        final int N = that.getKeyCount();
        for (int i=0; i<N; i++) {
            final int key = that.getKeyAt(i);

            final int state = SparseMappingTable.getIdFromKey(key);
            final long[] addData = that.getArrayForKey(key);
            final int addOff = SparseMappingTable.getIndexFromKey(key);

            mergeStats(state, addData, addOff);
        }
    }

    /**
     * Merge the stats given into our own values.
     *
     * @param state     The state
     * @param addData   The data array to copy
     * @param addOff    The index in addOff to start copying from
     */
    public void mergeStats(int state, long[] addData, int addOff) {
        final int key = getOrAddKey((byte)state, SYS_MEM_USAGE_COUNT);
        
        final long[] dstData = getArrayForKey(key);
        final int dstOff = SparseMappingTable.getIndexFromKey(key);

        SysMemUsageTable.mergeSysMemUsage(dstData, dstOff, addData, addOff);
    }

    /**
     * Return a long[] containing the merge of all of the usage in this table.
     */
    public long[] getTotalMemUsage() {
        long[] total = new long[SYS_MEM_USAGE_COUNT];
        final int N = getKeyCount();
        for (int i=0; i<N; i++) {
            final int key = getKeyAt(i);

            final long[] addData = getArrayForKey(key);
            final int addOff = SparseMappingTable.getIndexFromKey(key);

            SysMemUsageTable.mergeSysMemUsage(total, 0, addData, addOff);
        }
        return total;
    }

    /**
     * Merge the stats from one raw long[] into another.
     *
     * @param dstData The destination array
     * @param dstOff  The index in the destination array to start from
     * @param addData The source array
     * @param addOff  The index in the source array to start from
     */
    public static void mergeSysMemUsage(long[] dstData, int dstOff,
            long[] addData, int addOff) {
        final long dstCount = dstData[dstOff+SYS_MEM_USAGE_SAMPLE_COUNT];
        final long addCount = addData[addOff+SYS_MEM_USAGE_SAMPLE_COUNT];
        if (dstCount == 0) {
            dstData[dstOff+SYS_MEM_USAGE_SAMPLE_COUNT] = addCount;
            for (int i=SYS_MEM_USAGE_CACHED_MINIMUM; i<SYS_MEM_USAGE_COUNT; i++) {
                dstData[dstOff+i] = addData[addOff+i];
            }
        } else if (addCount > 0) {
            dstData[dstOff+SYS_MEM_USAGE_SAMPLE_COUNT] = dstCount + addCount;
            for (int i=SYS_MEM_USAGE_CACHED_MINIMUM; i<SYS_MEM_USAGE_COUNT; i+=3) {
                if (dstData[dstOff+i] > addData[addOff+i]) {
                    dstData[dstOff+i] = addData[addOff+i];
                }
                dstData[dstOff+i+1] = (long)(
                        ((dstData[dstOff+i+1]*(double)dstCount)
                                + (addData[addOff+i+1]*(double)addCount))
                                / (dstCount+addCount) );
                if (dstData[dstOff+i+2] < addData[addOff+i+2]) {
                    dstData[dstOff+i+2] = addData[addOff+i+2];
                }
            }
        }
    }


    public void dump(PrintWriter pw, String prefix, int[] screenStates, int[] memStates) {
        int printedScreen = -1;
        for (int is=0; is<screenStates.length; is++) {
            int printedMem = -1;
            for (int im=0; im<memStates.length; im++) {
                final int iscreen = screenStates[is];
                final int imem = memStates[im];
                final int bucket = ((iscreen + imem) * STATE_COUNT);
                long count = getValueForId((byte)bucket, SYS_MEM_USAGE_SAMPLE_COUNT);
                if (count > 0) {
                    pw.print(prefix);
                    if (screenStates.length > 1) {
                        DumpUtils.printScreenLabel(pw, printedScreen != iscreen
                                ? iscreen : STATE_NOTHING);
                        printedScreen = iscreen;
                    }
                    if (memStates.length > 1) {
                        DumpUtils.printMemLabel(pw,
                                printedMem != imem ? imem : STATE_NOTHING, '\0');
                        printedMem = imem;
                    }
                    pw.print(": ");
                    pw.print(count);
                    pw.println(" samples:");
                    dumpCategory(pw, prefix, "  Cached", bucket, SYS_MEM_USAGE_CACHED_MINIMUM);
                    dumpCategory(pw, prefix, "  Free", bucket, SYS_MEM_USAGE_FREE_MINIMUM);
                    dumpCategory(pw, prefix, "  ZRam", bucket, SYS_MEM_USAGE_ZRAM_MINIMUM);
                    dumpCategory(pw, prefix, "  Kernel", bucket, SYS_MEM_USAGE_KERNEL_MINIMUM);
                    dumpCategory(pw, prefix, "  Native", bucket, SYS_MEM_USAGE_NATIVE_MINIMUM);
                }
            }
        }
    }

    private void dumpCategory(PrintWriter pw, String prefix, String label, int bucket, int index) {
        pw.print(prefix); pw.print(label);
        pw.print(": ");
        DebugUtils.printSizeValue(pw, getValueForId((byte)bucket, index) * 1024);
        pw.print(" min, ");
        DebugUtils.printSizeValue(pw, getValueForId((byte)bucket, index + 1) * 1024);
        pw.print(" avg, ");
        DebugUtils.printSizeValue(pw, getValueForId((byte)bucket, index+2) * 1024);
        pw.println(" max");
    }
    
}


