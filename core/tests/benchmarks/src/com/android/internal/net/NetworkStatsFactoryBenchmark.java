/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.net;

import android.net.NetworkStats;
import android.os.SystemClock;

import com.google.caliper.SimpleBenchmark;

import java.io.File;

public class NetworkStatsFactoryBenchmark extends SimpleBenchmark {
    private File mStats;

    // TODO: consider staging stats file with different number of rows

    @Override
    protected void setUp() {
        mStats = new File("/proc/net/xt_qtaguid/stats");
    }

    @Override
    protected void tearDown() {
        mStats = null;
    }

    public void timeReadNetworkStatsDetailJava(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            NetworkStatsFactory.javaReadNetworkStatsDetail(mStats, NetworkStats.UID_ALL);
        }
    }

    public void timeReadNetworkStatsDetailNative(int reps) {
        for (int i = 0; i < reps; i++) {
            final NetworkStats stats = new NetworkStats(SystemClock.elapsedRealtime(), 0);
            NetworkStatsFactory.nativeReadNetworkStatsDetail(
                    stats, mStats.getAbsolutePath(), NetworkStats.UID_ALL);
        }
    }
}
