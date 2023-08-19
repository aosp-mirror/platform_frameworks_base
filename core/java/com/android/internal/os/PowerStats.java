/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.os;

import android.os.BatteryConsumer;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;

import java.util.Arrays;

/**
 * Container for power stats, acquired by various PowerStatsCollector classes. See subclasses for
 * details.
 */
public final class PowerStats {
    /**
     * Power component (e.g. CPU, WIFI etc) that this snapshot relates to.
     */
    public @BatteryConsumer.PowerComponent int powerComponentId;

    /**
     * Duration, in milliseconds, covered by this snapshot.
     */
    public long durationMs;

    /**
     * Device-wide stats.
     */
    public long[] stats;

    /**
     * Per-UID CPU stats.
     */
    public final SparseArray<long[]> uidStats = new SparseArray<>();

    /**
     * Prints the contents of the stats snapshot.
     */
    public void dump(IndentingPrintWriter pw) {
        pw.print("PowerStats: ");
        pw.println(BatteryConsumer.powerComponentIdToString(powerComponentId));
        pw.increaseIndent();
        pw.print("duration", durationMs).println();
        for (int i = 0; i < uidStats.size(); i++) {
            pw.print("UID ");
            pw.print(uidStats.keyAt(i));
            pw.print(": ");
            pw.print(Arrays.toString(uidStats.valueAt(i)));
            pw.println();
        }
        pw.decreaseIndent();
    }
}
