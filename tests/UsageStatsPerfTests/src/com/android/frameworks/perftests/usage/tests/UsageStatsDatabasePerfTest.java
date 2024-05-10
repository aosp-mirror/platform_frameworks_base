/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.usage.tests;

import static junit.framework.Assert.assertEquals;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.SystemClock;
import android.perftests.utils.ManualBenchmarkState;
import android.perftests.utils.PerfManualStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.usage.IntervalStats;
import com.android.server.usage.PackagesTokenData;
import com.android.server.usage.UsageStatsDatabase;
import com.android.server.usage.UsageStatsDatabase.StatCombiner;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class UsageStatsDatabasePerfTest {
    protected static Context sContext;
    private static UsageStatsDatabase sUsageStatsDatabase;
    private static File mTestDir;

    // Represents how many apps might have used in a day by a user with a few apps
    final static int FEW_PKGS = 10;
    // Represent how many apps might have used in a day by a user with many apps
    final static int MANY_PKGS = 50;
    // Represents how many usage events per app a device might have with light usage
    final static int LIGHT_USE = 10;
    // Represents how many usage events per app a device might have with heavy usage
    final static int HEAVY_USE = 50;

    private static final StatCombiner<UsageEvents.Event> sUsageStatsCombiner =
            new StatCombiner<UsageEvents.Event>() {
                @Override
                public boolean combine(IntervalStats stats, boolean mutable,
                        List<UsageEvents.Event> accResult) {
                    final int size = stats.events.size();
                    for (int i = 0; i < size; i++) {
                        accResult.add(stats.events.get(i));
                    }
                    return true;
                }
            };


    @Rule
    public PerfManualStatusReporter mPerfManualStatusReporter = new PerfManualStatusReporter();

    @BeforeClass
    public static void setUpOnce() {
        sContext = InstrumentationRegistry.getTargetContext();
        mTestDir = new File(sContext.getFilesDir(), "UsageStatsDatabasePerfTest");
        sUsageStatsDatabase = new UsageStatsDatabase(mTestDir);
        sUsageStatsDatabase.readMappingsLocked();
        sUsageStatsDatabase.init(1);
    }

    private static void populateIntervalStats(IntervalStats intervalStats, int packageCount,
            int eventsPerPackage) {
        for (int pkg = 0; pkg < packageCount; pkg++) {
            UsageEvents.Event event = new UsageEvents.Event();
            event.mPackage = "fake.package.name" + pkg;
            event.mClass = event.mPackage + ".class1";
            event.mTimeStamp = 1;
            event.mEventType = UsageEvents.Event.ACTIVITY_RESUMED;
            for (int evt = 0; evt < eventsPerPackage; evt++) {
                intervalStats.events.insert(event);
                intervalStats.update(event.mPackage, event.mClass, event.mTimeStamp,
                        event.mEventType, 1);
            }
        }
    }

    private static void clearUsageStatsFiles() {
        File[] intervalDirs = mTestDir.listFiles();
        for (File intervalDir : intervalDirs) {
            if (intervalDir.isDirectory()) {
                File[] usageFiles = intervalDir.listFiles();
                for (File f : usageFiles) {
                    f.delete();
                }
            }
        }
    }

    private void runQueryUsageStatsTest(int packageCount, int eventsPerPackage) throws IOException {
        final ManualBenchmarkState benchmarkState = mPerfManualStatusReporter.getBenchmarkState();
        IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats, packageCount, eventsPerPackage);
        sUsageStatsDatabase.putUsageStats(0, intervalStats);
        long elapsedTimeNs = 0;
        while (benchmarkState.keepRunning(elapsedTimeNs)) {
            final long startTime = SystemClock.elapsedRealtimeNanos();
            List<UsageEvents.Event> temp = sUsageStatsDatabase.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, 0, 2, sUsageStatsCombiner, false);
            final long endTime = SystemClock.elapsedRealtimeNanos();
            elapsedTimeNs = endTime - startTime;
            assertEquals(packageCount * eventsPerPackage, temp.size());
        }
    }

    private void runPutUsageStatsTest(int packageCount, int eventsPerPackage) throws IOException {
        final ManualBenchmarkState benchmarkState = mPerfManualStatusReporter.getBenchmarkState();
        IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats, packageCount, eventsPerPackage);
        long elapsedTimeNs = 0;
        while (benchmarkState.keepRunning(elapsedTimeNs)) {
            final long startTime = SystemClock.elapsedRealtimeNanos();
            sUsageStatsDatabase.putUsageStats(0, intervalStats);
            final long endTime = SystemClock.elapsedRealtimeNanos();
            elapsedTimeNs = endTime - startTime;
            clearUsageStatsFiles();
        }
    }

    private void runObfuscateStatsTest(int packageCount, int eventsPerPackage) {
        final ManualBenchmarkState benchmarkState = mPerfManualStatusReporter.getBenchmarkState();
        IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats, packageCount, eventsPerPackage);
        long elapsedTimeNs = 0;
        while (benchmarkState.keepRunning(elapsedTimeNs)) {
            final long startTime = SystemClock.elapsedRealtimeNanos();
            PackagesTokenData packagesTokenData = new PackagesTokenData();
            intervalStats.obfuscateData(packagesTokenData);
            final long endTime = SystemClock.elapsedRealtimeNanos();
            elapsedTimeNs = endTime - startTime;
            clearUsageStatsFiles();
        }
    }

    private void runDeobfuscateStatsTest(int packageCount, int eventsPerPackage) {
        final ManualBenchmarkState benchmarkState = mPerfManualStatusReporter.getBenchmarkState();
        IntervalStats intervalStats = new IntervalStats();
        populateIntervalStats(intervalStats, packageCount, eventsPerPackage);
        long elapsedTimeNs = 0;
        while (benchmarkState.keepRunning(elapsedTimeNs)) {
            PackagesTokenData packagesTokenData = new PackagesTokenData();
            intervalStats.obfuscateData(packagesTokenData);
            final long startTime = SystemClock.elapsedRealtimeNanos();
            intervalStats.deobfuscateData(packagesTokenData);
            final long endTime = SystemClock.elapsedRealtimeNanos();
            elapsedTimeNs = endTime - startTime;
            clearUsageStatsFiles();
        }
    }

    @Test
    public void testQueryUsageStats_FewPkgsLightUse() throws IOException {
        runQueryUsageStatsTest(FEW_PKGS, LIGHT_USE);
    }

    @Test
    public void testPutUsageStats_FewPkgsLightUse() throws IOException {
        runPutUsageStatsTest(FEW_PKGS, LIGHT_USE);
    }

    @Test
    public void testObfuscateStats_FewPkgsLightUse() {
        runObfuscateStatsTest(FEW_PKGS, LIGHT_USE);
    }

    @Test
    public void testDeobfuscateStats_FewPkgsLightUse() {
        runDeobfuscateStatsTest(FEW_PKGS, LIGHT_USE);
    }

    @Test
    public void testQueryUsageStats_FewPkgsHeavyUse() throws IOException {
        runQueryUsageStatsTest(FEW_PKGS, HEAVY_USE);
    }

    @Test
    public void testPutUsageStats_FewPkgsHeavyUse() throws IOException {
        runPutUsageStatsTest(FEW_PKGS, HEAVY_USE);
    }

    @Test
    public void testObfuscateStats_FewPkgsHeavyUse() {
        runObfuscateStatsTest(FEW_PKGS, HEAVY_USE);
    }

    @Test
    public void testDeobfuscateStats_FewPkgsHeavyUse() {
        runDeobfuscateStatsTest(FEW_PKGS, HEAVY_USE);
    }

    @Test
    public void testQueryUsageStats_ManyPkgsLightUse() throws IOException {
        runQueryUsageStatsTest(MANY_PKGS, LIGHT_USE);
    }

    @Test
    public void testPutUsageStats_ManyPkgsLightUse() throws IOException {
        runPutUsageStatsTest(MANY_PKGS, LIGHT_USE);
    }

    @Test
    public void testObfuscateStats_ManyPkgsLightUse() {
        runObfuscateStatsTest(MANY_PKGS, LIGHT_USE);
    }

    @Test
    public void testDeobfuscateStats_ManyPkgsLightUse() {
        runDeobfuscateStatsTest(MANY_PKGS, LIGHT_USE);
    }

    @Test
    public void testQueryUsageStats_ManyPkgsHeavyUse() throws IOException {
        runQueryUsageStatsTest(MANY_PKGS, HEAVY_USE);
    }

    @Test
    public void testPutUsageStats_ManyPkgsHeavyUse() throws IOException {
        runPutUsageStatsTest(MANY_PKGS, HEAVY_USE);
    }

    @Test
    public void testObfuscateStats_ManyPkgsHeavyUse() {
        runObfuscateStatsTest(MANY_PKGS, HEAVY_USE);
    }

    @Test
    public void testDeobfuscateStats_ManyPkgsHeavyUse() {
        runDeobfuscateStatsTest(MANY_PKGS, HEAVY_USE);
    }
}
