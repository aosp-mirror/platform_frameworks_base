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

package com.android.server.usage;

import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.fail;

import static org.testng.Assert.assertEquals;

import android.app.usage.EventList;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UsageStatsDatabaseTest {
    protected Context mContext;
    private UsageStatsDatabase mUsageStatsDatabase;
    private File mTestDir;

    private IntervalStats mIntervalStats = new IntervalStats();
    private long mEndTime = 0;

    private static final UsageStatsDatabase.StatCombiner<IntervalStats> mIntervalStatsVerifier =
            new UsageStatsDatabase.StatCombiner<IntervalStats>() {
                @Override
                public void combine(IntervalStats stats, boolean mutable,
                        List<IntervalStats> accResult) {
                    accResult.add(stats);
                }
            };

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mTestDir = new File(mContext.getFilesDir(), "UsageStatsDatabaseTest");
        mUsageStatsDatabase = new UsageStatsDatabase(mTestDir);
        mUsageStatsDatabase.init(1);
        populateIntervalStats();
        clearUsageStatsFiles();
    }

    /**
     * A debugging utility for viewing the files currently in the test directory
     */
    private void clearUsageStatsFiles() {
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

    /**
     * A debugging utility for viewing the files currently in the test directory
     */
    private String dumpUsageStatsFiles() {
        StringBuilder sb = new StringBuilder();
        File[] intervalDirs = mTestDir.listFiles();
        for (File intervalDir : intervalDirs) {
            if (intervalDir.isDirectory()) {
                File[] usageFiles = intervalDir.listFiles();
                for (File f : usageFiles) {
                    sb.append(f.toString());
                }
            }
        }
        return sb.toString();
    }

    private void populateIntervalStats() {
        final int numberOfEvents = 3000;
        long time = 1;
        mIntervalStats = new IntervalStats();

        mIntervalStats.beginTime = 1;
        mIntervalStats.interactiveTracker.count = 2;
        mIntervalStats.interactiveTracker.duration = 111111;
        mIntervalStats.nonInteractiveTracker.count = 3;
        mIntervalStats.nonInteractiveTracker.duration = 222222;
        mIntervalStats.keyguardShownTracker.count = 4;
        mIntervalStats.keyguardShownTracker.duration = 333333;
        mIntervalStats.keyguardHiddenTracker.count = 5;
        mIntervalStats.keyguardHiddenTracker.duration = 4444444;

        if (mIntervalStats.events == null) {
            mIntervalStats.events = new EventList();
        }

        for (int i = 0; i < numberOfEvents; i++) {
            UsageEvents.Event event = new UsageEvents.Event();
            final int packageInt = ((i / 3) % 7);
            event.mPackage = "fake.package.name" + packageInt; //clusters of 3 events from 7 "apps"
            if (packageInt == 3) {
                // Third app is an instant app
                event.mFlags |= UsageEvents.Event.FLAG_IS_PACKAGE_INSTANT_APP;
            } else if (packageInt == 2 || packageInt == 4) {
                event.mClass = ".fake.class.name" + i % 11;
            }


            event.mTimeStamp = time;
            event.mEventType = i % 19; //"random" event type

            switch (event.mEventType) {
                case UsageEvents.Event.CONFIGURATION_CHANGE:
                    //empty config,
                    event.mConfiguration = new Configuration();
                    break;
                case UsageEvents.Event.SHORTCUT_INVOCATION:
                    //"random" shortcut
                    event.mShortcutId = "shortcut" + (i % 8);
                    break;
                case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                    //"random" bucket and reason
                    event.mBucketAndReason = (((i % 5 + 1) * 10) << 16) & (i % 5 + 1) << 8;
                    break;
                case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                    //"random" channel
                    event.mNotificationChannelId = "channel" + (i % 5);
                    break;
            }

            mIntervalStats.events.insert(event);
            mIntervalStats.update(event.mPackage, event.mTimeStamp, event.mEventType);

            time += 23; // Arbitrary progression of time
        }
        mEndTime = time;

        Configuration config1 = new Configuration();
        config1.fontScale = 3.3f;
        config1.mcc = 4;
        mIntervalStats.getOrCreateConfigurationStats(config1);

        Configuration config2 = new Configuration();
        config2.mnc = 5;
        config2.setLocale(new Locale("en", "US"));
        mIntervalStats.getOrCreateConfigurationStats(config2);

        Configuration config3 = new Configuration();
        config3.touchscreen = 6;
        config3.keyboard = 7;
        mIntervalStats.getOrCreateConfigurationStats(config3);

        Configuration config4 = new Configuration();
        config4.keyboardHidden = 8;
        config4.hardKeyboardHidden = 9;
        mIntervalStats.getOrCreateConfigurationStats(config4);

        Configuration config5 = new Configuration();
        config5.navigation = 10;
        config5.navigationHidden = 11;
        mIntervalStats.getOrCreateConfigurationStats(config5);

        Configuration config6 = new Configuration();
        config6.orientation = 12;
        //Ignore screen layout, it's determined by locale
        mIntervalStats.getOrCreateConfigurationStats(config6);

        Configuration config7 = new Configuration();
        config7.colorMode = 14;
        config7.uiMode = 15;
        mIntervalStats.getOrCreateConfigurationStats(config7);

        Configuration config8 = new Configuration();
        config8.screenWidthDp = 16;
        config8.screenHeightDp = 17;
        mIntervalStats.getOrCreateConfigurationStats(config8);

        Configuration config9 = new Configuration();
        config9.smallestScreenWidthDp = 18;
        config9.densityDpi = 19;
        mIntervalStats.getOrCreateConfigurationStats(config9);

        mIntervalStats.activeConfiguration = config9;
    }

    void compareUsageStats(UsageStats us1, UsageStats us2) {
        assertEquals(us1.mPackageName, us2.mPackageName);
        // mBeginTimeStamp is based on the enclosing IntervalStats, don't bother checking
        // mEndTimeStamp is based on the enclosing IntervalStats, don't bother checking
        assertEquals(us1.mLastTimeUsed, us2.mLastTimeUsed);
        assertEquals(us1.mTotalTimeInForeground, us2.mTotalTimeInForeground);
        // mLaunchCount not persisted, so skipped
        assertEquals(us1.mAppLaunchCount, us2.mAppLaunchCount);
        assertEquals(us1.mLastEvent, us2.mLastEvent);
        assertEquals(us1.mChooserCounts, us2.mChooserCounts);
    }

    void compareUsageEvent(UsageEvents.Event e1, UsageEvents.Event e2, int debugId) {
        assertEquals(e1.mPackage, e2.mPackage, "Usage event " + debugId);
        assertEquals(e1.mClass, e2.mClass, "Usage event " + debugId);
        assertEquals(e1.mTimeStamp, e2.mTimeStamp, "Usage event " + debugId);
        assertEquals(e1.mEventType, e2.mEventType, "Usage event " + debugId);
        switch (e1.mEventType) {
            case UsageEvents.Event.CONFIGURATION_CHANGE:
                assertEquals(e1.mConfiguration, e2.mConfiguration,
                        "Usage event " + debugId + e2.mConfiguration.toString());
                break;
            case UsageEvents.Event.SHORTCUT_INVOCATION:
                assertEquals(e1.mShortcutId, e2.mShortcutId, "Usage event " + debugId);
                break;
            case UsageEvents.Event.STANDBY_BUCKET_CHANGED:
                assertEquals(e1.mBucketAndReason, e2.mBucketAndReason, "Usage event " + debugId);
                break;
            case UsageEvents.Event.NOTIFICATION_INTERRUPTION:
                assertEquals(e1.mNotificationChannelId, e2.mNotificationChannelId,
                        "Usage event " + debugId);
                break;
        }
        assertEquals(e1.mFlags, e2.mFlags);
    }

    void compareIntervalStats(IntervalStats stats1, IntervalStats stats2) {
        assertEquals(stats1.beginTime, stats2.beginTime);
        assertEquals(stats1.endTime, stats2.endTime);
        assertEquals(stats1.interactiveTracker.count, stats2.interactiveTracker.count);
        assertEquals(stats1.interactiveTracker.duration, stats2.interactiveTracker.duration);
        assertEquals(stats1.nonInteractiveTracker.count, stats2.nonInteractiveTracker.count);
        assertEquals(stats1.nonInteractiveTracker.duration, stats2.nonInteractiveTracker.duration);
        assertEquals(stats1.keyguardShownTracker.count, stats2.keyguardShownTracker.count);
        assertEquals(stats1.keyguardShownTracker.duration, stats2.keyguardShownTracker.duration);
        assertEquals(stats1.keyguardHiddenTracker.count, stats2.keyguardHiddenTracker.count);
        assertEquals(stats1.keyguardHiddenTracker.duration, stats2.keyguardHiddenTracker.duration);

        String[] usageKey1 = stats1.packageStats.keySet().toArray(new String[0]);
        String[] usageKey2 = stats2.packageStats.keySet().toArray(new String[0]);
        for (int i = 0; i < usageKey1.length; i++) {
            UsageStats usageStats1 = stats1.packageStats.get(usageKey1[i]);
            UsageStats usageStats2 = stats2.packageStats.get(usageKey2[i]);
            compareUsageStats(usageStats1, usageStats2);
        }

        assertEquals(stats1.configurations.size(), stats2.configurations.size());
        Configuration[] configSet1 = stats1.configurations.keySet().toArray(new Configuration[0]);
        for (int i = 0; i < configSet1.length; i++) {
            if (!stats2.configurations.containsKey(configSet1[i])) {
                Configuration[] configSet2 = stats2.configurations.keySet().toArray(
                        new Configuration[0]);
                String debugInfo = "";
                for (Configuration c : configSet1) {
                    debugInfo += c.toString() + "\n";
                }
                debugInfo += "\n";
                for (Configuration c : configSet2) {
                    debugInfo += c.toString() + "\n";
                }
                fail("Config " + configSet1[i].toString()
                        + " not found in deserialized IntervalStat\n" + debugInfo);
            }
        }
        assertEquals(stats1.activeConfiguration, stats2.activeConfiguration);

        assertEquals(stats1.events.size(), stats2.events.size());
        for (int i = 0; i < stats1.events.size(); i++) {
            compareUsageEvent(stats1.events.get(i), stats2.events.get(i), i);
        }
    }

    /**
     * Runs the Write Read test.
     * Will write the generated IntervalStat to disk, read it from disk and compare the two
     */
    void runWriteReadTest(int interval) throws IOException {
        mUsageStatsDatabase.putUsageStats(interval, mIntervalStats);
        List<IntervalStats> stats = mUsageStatsDatabase.queryUsageStats(interval, 0, mEndTime,
                mIntervalStatsVerifier);

        assertEquals(1, stats.size());
        compareIntervalStats(mIntervalStats, stats.get(0));
    }

    /**
     * Demonstrate that IntervalStats can be serialized and deserialized from disk without loss of
     * relevant data.
     */
    @Test
    public void testWriteRead() throws IOException {
        runWriteReadTest(UsageStatsManager.INTERVAL_DAILY);
        runWriteReadTest(UsageStatsManager.INTERVAL_WEEKLY);
        runWriteReadTest(UsageStatsManager.INTERVAL_MONTHLY);
        runWriteReadTest(UsageStatsManager.INTERVAL_YEARLY);
    }

    /**
     * Runs the Version Change tests.
     * Will write the generated IntervalStat to disk in one version format, "upgrade" to another
     * version and read the automatically upgraded files on disk in the new file format.
     */
    void runVersionChangeTest(int oldVersion, int newVersion, int interval) throws IOException {
        // Write IntervalStats to disk in old version format
        UsageStatsDatabase prevDB = new UsageStatsDatabase(mTestDir, oldVersion);
        prevDB.init(1);
        prevDB.putUsageStats(interval, mIntervalStats);

        // Simulate an upgrade to a new version and read from the disk
        UsageStatsDatabase newDB = new UsageStatsDatabase(mTestDir, newVersion);
        newDB.init(mEndTime);
        List<IntervalStats> stats = newDB.queryUsageStats(interval, 0, mEndTime,
                mIntervalStatsVerifier);

        assertEquals(1, stats.size());
        // The written and read IntervalStats should match
        compareIntervalStats(mIntervalStats, stats.get(0));
    }

    /**
     * Test the version upgrade from 3 to 4
     */
    @Test
    public void testVersionUpgradeFrom3to4() throws IOException {
        runVersionChangeTest(3, 4, UsageStatsManager.INTERVAL_DAILY);
        runVersionChangeTest(3, 4, UsageStatsManager.INTERVAL_WEEKLY);
        runVersionChangeTest(3, 4, UsageStatsManager.INTERVAL_MONTHLY);
        runVersionChangeTest(3, 4, UsageStatsManager.INTERVAL_YEARLY);
    }
}
