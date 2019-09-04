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

import static android.app.usage.UsageEvents.Event.MAX_EVENT_TYPE;

import static junit.framework.TestCase.fail;

import static org.testng.Assert.assertEquals;

import android.app.usage.TimeSparseArray;
import android.app.usage.UsageEvents.Event;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.res.Configuration;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class UsageStatsDatabaseTest {

    private static final int MAX_TESTED_VERSION = 4;
    protected Context mContext;
    private UsageStatsDatabase mUsageStatsDatabase;
    private File mTestDir;

    private IntervalStats mIntervalStats = new IntervalStats();
    private long mEndTime = 0;

    // Key under which the payload blob is stored
    // same as UsageStatsBackupHelper.KEY_USAGE_STATS
    static final String KEY_USAGE_STATS = "usage_stats";

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
        final int timeProgression = 23;
        long time = System.currentTimeMillis() - (numberOfEvents*timeProgression);
        mIntervalStats = new IntervalStats();

        mIntervalStats.majorVersion = 7;
        mIntervalStats.minorVersion = 8;
        mIntervalStats.beginTime = time;
        mIntervalStats.interactiveTracker.count = 2;
        mIntervalStats.interactiveTracker.duration = 111111;
        mIntervalStats.nonInteractiveTracker.count = 3;
        mIntervalStats.nonInteractiveTracker.duration = 222222;
        mIntervalStats.keyguardShownTracker.count = 4;
        mIntervalStats.keyguardShownTracker.duration = 333333;
        mIntervalStats.keyguardHiddenTracker.count = 5;
        mIntervalStats.keyguardHiddenTracker.duration = 4444444;

        for (int i = 0; i < numberOfEvents; i++) {
            Event event = new Event();
            final int packageInt = ((i / 3) % 7); //clusters of 3 events from 7 "apps"
            event.mPackage = "fake.package.name" + packageInt;
            if (packageInt == 3) {
                // Third app is an instant app
                event.mFlags |= Event.FLAG_IS_PACKAGE_INSTANT_APP;
            }

            final int instanceId = i % 11;
            event.mClass = ".fake.class.name" + instanceId;
            event.mTimeStamp = time;
            event.mEventType = i % (MAX_EVENT_TYPE + 1); //"random" event type
            event.mInstanceId = instanceId;


            final int rootPackageInt = (i % 5); // 5 "apps" start each task
            event.mTaskRootPackage = "fake.package.name" + rootPackageInt;

            final int rootClassInt = i % 6;
            event.mTaskRootClass = ".fake.class.name" + rootClassInt;

            switch (event.mEventType) {
                case Event.CONFIGURATION_CHANGE:
                    //empty config,
                    event.mConfiguration = new Configuration();
                    break;
                case Event.SHORTCUT_INVOCATION:
                    //"random" shortcut
                    event.mShortcutId = "shortcut" + (i % 8);
                    break;
                case Event.STANDBY_BUCKET_CHANGED:
                    //"random" bucket and reason
                    event.mBucketAndReason = (((i % 5 + 1) * 10) << 16) & (i % 5 + 1) << 8;
                    break;
                case Event.NOTIFICATION_INTERRUPTION:
                    //"random" channel
                    event.mNotificationChannelId = "channel" + (i % 5);
                    break;
            }

            mIntervalStats.addEvent(event);
            mIntervalStats.update(event.mPackage, event.mClass, event.mTimeStamp, event.mEventType,
                    event.mInstanceId);

            time += timeProgression; // Arbitrary progression of time
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

        Configuration config10 = new Configuration();
        final Locale locale10 = new Locale.Builder()
                                    .setLocale(new Locale("zh", "CN"))
                                    .setScript("Hans")
                                    .build();
        config10.setLocale(locale10);
        mIntervalStats.getOrCreateConfigurationStats(config10);

        Configuration config11 = new Configuration();
        final Locale locale11 = new Locale.Builder()
                                    .setLocale(new Locale("zh", "CN"))
                                    .setScript("Hant")
                                    .build();
        config11.setLocale(locale11);
        mIntervalStats.getOrCreateConfigurationStats(config11);

        mIntervalStats.activeConfiguration = config9;
    }

    void compareUsageStats(UsageStats us1, UsageStats us2) {
        assertEquals(us1.mPackageName, us2.mPackageName);
        // mBeginTimeStamp is based on the enclosing IntervalStats, don't bother checking
        // mEndTimeStamp is based on the enclosing IntervalStats, don't bother checking
        assertEquals(us1.mLastTimeUsed, us2.mLastTimeUsed);
        assertEquals(us1.mLastTimeVisible, us2.mLastTimeVisible);
        assertEquals(us1.mTotalTimeInForeground, us2.mTotalTimeInForeground);
        assertEquals(us1.mTotalTimeVisible, us2.mTotalTimeVisible);
        assertEquals(us1.mLastTimeForegroundServiceUsed, us2.mLastTimeForegroundServiceUsed);
        assertEquals(us1.mTotalTimeForegroundServiceUsed, us2.mTotalTimeForegroundServiceUsed);
        // mLaunchCount not persisted, so skipped
        assertEquals(us1.mAppLaunchCount, us2.mAppLaunchCount);
        assertEquals(us1.mChooserCounts, us2.mChooserCounts);
    }

    void compareUsageEvent(Event e1, Event e2, int debugId, int minVersion) {
        switch (minVersion) {
            case 4: // test fields added in version 4
                assertEquals(e1.mInstanceId, e2.mInstanceId, "Usage event " + debugId);
                assertEquals(e1.mTaskRootPackage, e2.mTaskRootPackage, "Usage event " + debugId);
                assertEquals(e1.mTaskRootClass, e2.mTaskRootClass, "Usage event " + debugId);
                // fallthrough
            default:
                assertEquals(e1.mPackage, e2.mPackage, "Usage event " + debugId);
                assertEquals(e1.mClass, e2.mClass, "Usage event " + debugId);
                assertEquals(e1.mTimeStamp, e2.mTimeStamp, "Usage event " + debugId);
                assertEquals(e1.mEventType, e2.mEventType, "Usage event " + debugId);
                switch (e1.mEventType) {
                    case Event.CONFIGURATION_CHANGE:
                        assertEquals(e1.mConfiguration, e2.mConfiguration,
                                "Usage event " + debugId + e2.mConfiguration.toString());
                        break;
                    case Event.SHORTCUT_INVOCATION:
                        assertEquals(e1.mShortcutId, e2.mShortcutId, "Usage event " + debugId);
                        break;
                    case Event.STANDBY_BUCKET_CHANGED:
                        assertEquals(e1.mBucketAndReason, e2.mBucketAndReason,
                                "Usage event " + debugId);
                        break;
                    case Event.NOTIFICATION_INTERRUPTION:
                        assertEquals(e1.mNotificationChannelId, e2.mNotificationChannelId,
                                "Usage event " + debugId);
                        break;
                }
                assertEquals(e1.mFlags, e2.mFlags);
        }
    }

    void compareIntervalStats(IntervalStats stats1, IntervalStats stats2, int minVersion) {
        assertEquals(stats1.majorVersion, stats2.majorVersion);
        assertEquals(stats1.minorVersion, stats2.minorVersion);
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
            compareUsageEvent(stats1.events.get(i), stats2.events.get(i), i, minVersion);
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
        compareIntervalStats(mIntervalStats, stats.get(0), MAX_TESTED_VERSION);
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

        final int minVersion = oldVersion < newVersion ? oldVersion : newVersion;
        // The written and read IntervalStats should match
        compareIntervalStats(mIntervalStats, stats.get(0), minVersion);
    }

    /**
     * Runs the Backup and Restore tests.
     * Will write the generated IntervalStat to a database and create a backup in the specified
     * version's format. The database will then be restored from the blob and the restored
     * interval stats will be compared to the generated stats.
     */
    void runBackupRestoreTest(int version) throws IOException {
        UsageStatsDatabase prevDB = new UsageStatsDatabase(mTestDir);
        prevDB.init(1);
        prevDB.putUsageStats(UsageStatsManager.INTERVAL_DAILY, mIntervalStats);
        // Create a backup with a specific version
        byte[] blob = prevDB.getBackupPayload(KEY_USAGE_STATS, version);

        clearUsageStatsFiles();

        UsageStatsDatabase newDB = new UsageStatsDatabase(mTestDir);
        newDB.init(1);
        // Attempt to restore the usage stats from the backup
        newDB.applyRestoredPayload(KEY_USAGE_STATS, blob);
        List<IntervalStats> stats = newDB.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, mEndTime,
                mIntervalStatsVerifier);


        if (version > newDB.BACKUP_VERSION || version < 1) {
            if (stats != null && stats.size() != 0) {
                fail("UsageStatsDatabase should ne be able to restore from unknown data versions");
            }
            return;
        }

        assertEquals(1, stats.size());

        // Clear non backed up data from expected IntervalStats
        mIntervalStats.activeConfiguration = null;
        mIntervalStats.configurations.clear();
        mIntervalStats.events.clear();

        // The written and read IntervalStats should match
        compareIntervalStats(mIntervalStats, stats.get(0), version);
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


    /**
     * Test the version upgrade from 3 to 4
     */
    @Test
    public void testBackupRestore() throws IOException {
        runBackupRestoreTest(1);
        runBackupRestoreTest(4);

        // test invalid backup versions as well
        runBackupRestoreTest(0);
        runBackupRestoreTest(99999);
    }

    /**
     * Test the pruning in indexFilesLocked() that only allow up to 100 daily files, 50 weekly files
     * , 12 monthly files, 10 yearly files.
     */
    @Test
    public void testMaxFiles() throws IOException {
        final File[] intervalDirs = new File[]{
            new File(mTestDir, "daily"),
            new File(mTestDir, "weekly"),
            new File(mTestDir, "monthly"),
            new File(mTestDir, "yearly"),
        };
        // Create 10 extra files under each interval dir.
        final int extra = 10;
        final int length = intervalDirs.length;
        for (int i = 0; i < length; i++) {
            final int numFiles = UsageStatsDatabase.MAX_FILES_PER_INTERVAL_TYPE[i] + extra;
            for (int f = 0; f < numFiles; f++) {
                final AtomicFile file = new AtomicFile(new File(intervalDirs[i], Long.toString(f)));
                FileOutputStream fos = file.startWrite();
                fos.write(1);
                file.finishWrite(fos);
            }
        }
        // indexFilesLocked() list files under each interval dir, if number of files are more than
        // the max allowed files for each interval type, it deletes the lowest numbered files.
        mUsageStatsDatabase.forceIndexFiles();
        final int len = mUsageStatsDatabase.mSortedStatFiles.length;
        for (int i = 0; i < len; i++) {
            final TimeSparseArray<AtomicFile> files =  mUsageStatsDatabase.mSortedStatFiles[i];
            // The stats file for each interval type equals to max allowed.
            assertEquals(UsageStatsDatabase.MAX_FILES_PER_INTERVAL_TYPE[i],
                    files.size());
            // The highest numbered file,
            assertEquals(UsageStatsDatabase.MAX_FILES_PER_INTERVAL_TYPE[i] + extra - 1,
                    files.keyAt(files.size() - 1));
            // The lowest numbered file:
            assertEquals(extra, files.keyAt(0));
        }
    }
}
