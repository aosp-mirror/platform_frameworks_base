/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.transport;

import android.annotation.Nullable;
import android.content.ComponentName;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Responsible for aggregating {@link TransportClient} relevant times. */
public class TransportStats {
    private final Object mStatsLock = new Object();
    private final Map<ComponentName, Stats> mTransportStats = new HashMap<>();

    void registerConnectionTime(ComponentName transportComponent, long timeMs) {
        synchronized (mStatsLock) {
            mTransportStats
                    .computeIfAbsent(transportComponent, name -> new Stats())
                    .register(timeMs);
        }
    }

    /** Returns {@link Stats} for transport whose host service is {@code transportComponent}. */
    @Nullable
    public Stats getStatsForTransport(ComponentName transportComponent) {
        synchronized (mStatsLock) {
            Stats stats = mTransportStats.get(transportComponent);
            if (stats == null) {
                return null;
            }
            return new Stats(stats);
        }
    }

    public void dump(PrintWriter pw) {
        synchronized (mStatsLock) {
            Optional<Stats> aggregatedStats =
                    mTransportStats.values().stream().reduce(Stats::merge);
            if (aggregatedStats.isPresent()) {
                dumpStats(pw, "", aggregatedStats.get());
            }
            if (!mTransportStats.isEmpty()) {
                pw.println("Per transport:");
                for (ComponentName transportComponent : mTransportStats.keySet()) {
                    Stats stats = mTransportStats.get(transportComponent);
                    pw.println("    " + transportComponent.flattenToShortString());
                    dumpStats(pw, "        ", stats);
                }
            }
        }
    }

    private static void dumpStats(PrintWriter pw, String prefix, Stats stats) {
        pw.println(
                String.format(
                        Locale.US, "%sAverage connection time: %.2f ms", prefix, stats.mAverage));
        pw.println(String.format(Locale.US, "%sMax connection time: %d ms", prefix, stats.mMax));
        pw.println(String.format(Locale.US, "%sMin connection time: %d ms", prefix, stats.mMin));
        pw.println(String.format(Locale.US, "%sNumber of connections: %d ", prefix, stats.mN));
    }

    public static final class Stats {
        public static Stats merge(Stats a, Stats b) {
            return new Stats(
                    a.mN + b.mN,
                    (a.mAverage * a.mN + b.mAverage * b.mN) / (a.mN + b.mN),
                    Math.max(a.mMax, b.mMax),
                    Math.min(a.mMin, b.mMin));
        }

        public int mN;
        public double mAverage;
        public long mMax;
        public long mMin;

        public Stats() {
            mN = 0;
            mAverage = 0;
            mMax = 0;
            mMin = Long.MAX_VALUE;
        }

        private Stats(Stats original) {
            mN = original.mN;
            mAverage = original.mAverage;
            mMax = original.mMax;
            mMin = original.mMin;
        }

        private Stats(int n, double average, long max, long min) {
            mN = n;
            mAverage = average;
            mMax = max;
            mMin = min;
        }

        private void register(long sample) {
            mAverage = (mAverage * mN + sample) / (mN + 1);
            mN++;
            mMax = Math.max(mMax, sample);
            mMin = Math.min(mMin, sample);
        }
    }
}
