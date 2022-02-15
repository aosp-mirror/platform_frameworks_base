/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.usage;

import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_SYSTEM;
import static android.app.usage.UsageStatsManager.REASON_MAIN_FORCED_BY_USER;
import static android.app.usage.UsageStatsManager.REASON_MAIN_TIMEOUT;
import static android.app.usage.UsageStatsManager.REASON_MAIN_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_MOVE_TO_FOREGROUND;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_NOTIFICATION_SEEN;
import static android.app.usage.UsageStatsManager.REASON_SUB_USAGE_SLICE_PINNED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED;
import static android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET;
import static android.app.usage.UsageStatsManager.standbyBucketToString;

import android.os.FileUtils;
import android.test.AndroidTestCase;

import java.io.File;
import java.util.Map;

public class AppIdleHistoryTests extends AndroidTestCase {

    File mStorageDir;

    private static final String PACKAGE_1 = "com.android.testpackage1";
    private static final String PACKAGE_2 = "com.android.testpackage2";
    private static final String PACKAGE_3 = "com.android.testpackage3";
    private static final String PACKAGE_4 = "com.android.testpackage4";

    private static final int USER_ID = 0;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mStorageDir = new File(getContext().getFilesDir(), "appidle");
        mStorageDir.mkdirs();
    }

    @Override
    protected void tearDown() throws Exception {
        FileUtils.deleteContents(mStorageDir);
        super.tearDown();
    }

    public void testFilesCreation() {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 0);

        aih.updateDisplay(true, /* elapsedRealtime= */ 1000);
        aih.updateDisplay(false, /* elapsedRealtime= */ 2000);
        // Screen On time file should be written right away
        assertTrue(aih.getScreenOnTimeFile().exists());

        aih.writeAppIdleTimes(USER_ID, /* elapsedRealtime= */ 2000);
        // stats file should be written now
        assertTrue(new File(new File(mStorageDir, "users/" + USER_ID),
                AppIdleHistory.APP_IDLE_FILENAME).exists());
    }

    public void testScreenOnTime() {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);
        aih.updateDisplay(false, 2000);
        assertEquals(aih.getScreenOnTime(2000), 0);
        aih.updateDisplay(true, 3000);
        assertEquals(aih.getScreenOnTime(4000), 1000);
        assertEquals(aih.getScreenOnTime(5000), 2000);
        aih.updateDisplay(false, 6000);
        // Screen on time should not keep progressing with screen is off
        assertEquals(aih.getScreenOnTime(7000), 3000);
        assertEquals(aih.getScreenOnTime(8000), 3000);
        aih.writeAppIdleDurations();

        // Check if the screen on time is persisted across instantiations
        AppIdleHistory aih2 = new AppIdleHistory(mStorageDir, 0);
        assertEquals(aih2.getScreenOnTime(11000), 3000);
        aih2.updateDisplay(true, 4000);
        aih2.updateDisplay(false, 5000);
        assertEquals(aih2.getScreenOnTime(13000), 4000);
    }

    public void testBuckets() {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);

        aih.setAppStandbyBucket(PACKAGE_1, USER_ID, 1000, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE);
        // ACTIVE means not idle
        assertFalse(aih.isIdle(PACKAGE_1, USER_ID, 2000));

        aih.setAppStandbyBucket(PACKAGE_2, USER_ID, 2000, STANDBY_BUCKET_ACTIVE,
                REASON_MAIN_USAGE);
        aih.setAppStandbyBucket(PACKAGE_3, USER_ID, 2500, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);
        aih.setAppStandbyBucket(PACKAGE_4, USER_ID, 2750, STANDBY_BUCKET_RESTRICTED,
                REASON_MAIN_FORCED_BY_USER);
        aih.setAppStandbyBucket(PACKAGE_1, USER_ID, 3000, STANDBY_BUCKET_RARE,
                REASON_MAIN_TIMEOUT);

        assertEquals(aih.getAppStandbyBucket(PACKAGE_1, USER_ID, 3000), STANDBY_BUCKET_RARE);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_2, USER_ID, 3000), STANDBY_BUCKET_ACTIVE);
        assertEquals(aih.getAppStandbyReason(PACKAGE_1, USER_ID, 3000), REASON_MAIN_TIMEOUT);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_3, USER_ID, 3000), STANDBY_BUCKET_RESTRICTED);
        assertEquals(aih.getAppStandbyReason(PACKAGE_3, USER_ID, 3000),
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);
        assertEquals(aih.getAppStandbyReason(PACKAGE_4, USER_ID, 3000),
                REASON_MAIN_FORCED_BY_USER);

        // RARE and RESTRICTED are considered idle
        assertTrue(aih.isIdle(PACKAGE_1, USER_ID, 3000));
        assertFalse(aih.isIdle(PACKAGE_2, USER_ID, 3000));
        assertTrue(aih.isIdle(PACKAGE_3, USER_ID, 3000));
        assertTrue(aih.isIdle(PACKAGE_4, USER_ID, 3000));

        // Check persistence
        aih.writeAppIdleDurations();
        aih.writeAppIdleTimes(USER_ID, /* elapsedRealtime= */ 3000);
        aih = new AppIdleHistory(mStorageDir, 4000);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_1, USER_ID, 5000), STANDBY_BUCKET_RARE);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_2, USER_ID, 5000), STANDBY_BUCKET_ACTIVE);
        assertEquals(aih.getAppStandbyReason(PACKAGE_1, USER_ID, 5000), REASON_MAIN_TIMEOUT);
        assertEquals(aih.getAppStandbyBucket(PACKAGE_3, USER_ID, 3000), STANDBY_BUCKET_RESTRICTED);
        assertEquals(aih.getAppStandbyReason(PACKAGE_3, USER_ID, 3000),
                REASON_MAIN_FORCED_BY_SYSTEM
                        | REASON_SUB_FORCED_SYSTEM_FLAG_BACKGROUND_RESOURCE_USAGE);
        assertEquals(aih.getAppStandbyReason(PACKAGE_4, USER_ID, 3000),
                REASON_MAIN_FORCED_BY_USER);

        assertTrue(aih.shouldInformListeners(PACKAGE_1, USER_ID, 5000, STANDBY_BUCKET_RARE));
        assertFalse(aih.shouldInformListeners(PACKAGE_1, USER_ID, 5000, STANDBY_BUCKET_RARE));
        assertTrue(aih.shouldInformListeners(PACKAGE_1, USER_ID, 5000, STANDBY_BUCKET_FREQUENT));
    }

    public void testJobRunTime() throws Exception {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);

        aih.setLastJobRunTime(PACKAGE_1, USER_ID, 2000);
        assertEquals(Long.MAX_VALUE, aih.getTimeSinceLastJobRun(PACKAGE_2, USER_ID, 0));
        assertEquals(4000, aih.getTimeSinceLastJobRun(PACKAGE_1, USER_ID, 6000));

        aih.setLastJobRunTime(PACKAGE_2, USER_ID, 6000);
        assertEquals(1000, aih.getTimeSinceLastJobRun(PACKAGE_2, USER_ID, 7000));
        assertEquals(5000, aih.getTimeSinceLastJobRun(PACKAGE_1, USER_ID, 7000));
    }

    public void testReason() throws Exception {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);
        aih.reportUsage(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_SUB_USAGE_MOVE_TO_FOREGROUND, 2000, 0);
        assertEquals(REASON_MAIN_USAGE | REASON_SUB_USAGE_MOVE_TO_FOREGROUND,
                aih.getAppStandbyReason(PACKAGE_1, USER_ID, 3000));
        aih.setAppStandbyBucket(PACKAGE_1, USER_ID, 4000, STANDBY_BUCKET_WORKING_SET,
                REASON_MAIN_TIMEOUT);
        aih.writeAppIdleTimes(USER_ID, /* elapsedRealtime= */ 4000);

        aih = new AppIdleHistory(mStorageDir, 5000);
        assertEquals(REASON_MAIN_TIMEOUT, aih.getAppStandbyReason(PACKAGE_1, USER_ID, 5000));
    }

    public void testNullPackage() throws Exception {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000);
        // Report usage of a package
        aih.reportUsage(PACKAGE_1, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_SUB_USAGE_MOVE_TO_FOREGROUND, 2000, 0);
        // "Accidentally" report usage against a null named package
        aih.reportUsage(null, USER_ID, STANDBY_BUCKET_ACTIVE,
                REASON_SUB_USAGE_MOVE_TO_FOREGROUND, 2000, 0);
        // Persist data
        aih.writeAppIdleTimes(USER_ID, /* elapsedRealtime= */ 2000);
        // Recover data from disk
        aih = new AppIdleHistory(mStorageDir, 5000);
        // Verify data is intact
        assertEquals(REASON_MAIN_USAGE | REASON_SUB_USAGE_MOVE_TO_FOREGROUND,
                aih.getAppStandbyReason(PACKAGE_1, USER_ID, 3000));
    }

    public void testBucketExpiryTimes() throws Exception {
        AppIdleHistory aih = new AppIdleHistory(mStorageDir, 1000 /* elapsedRealtime */);
        aih.reportUsage(PACKAGE_1, USER_ID, STANDBY_BUCKET_WORKING_SET,
                REASON_SUB_USAGE_SLICE_PINNED,
                2000 /* elapsedRealtime */, 6000 /* expiryRealtime */);
        assertEquals(5000 /* expectedExpiryTimeMs */, aih.getBucketExpiryTimeMs(PACKAGE_1, USER_ID,
                STANDBY_BUCKET_WORKING_SET, 2000 /* elapsedRealtime */));
        aih.reportUsage(PACKAGE_2, USER_ID, STANDBY_BUCKET_FREQUENT,
                REASON_SUB_USAGE_NOTIFICATION_SEEN,
                2000 /* elapsedRealtime */, 3000 /* expiryRealtime */);
        assertEquals(2000 /* expectedExpiryTimeMs */, aih.getBucketExpiryTimeMs(PACKAGE_2, USER_ID,
                STANDBY_BUCKET_FREQUENT, 2000 /* elapsedRealtime */));
        aih.writeAppIdleTimes(USER_ID, 4000 /* elapsedRealtime */);

        // Persist data
        aih = new AppIdleHistory(mStorageDir, 5000 /* elapsedRealtime */);
        final Map<Integer, Long> expectedExpiryTimes1 = Map.of(
                STANDBY_BUCKET_ACTIVE, 0L,
                STANDBY_BUCKET_WORKING_SET, 5000L,
                STANDBY_BUCKET_FREQUENT, 0L,
                STANDBY_BUCKET_RARE, 0L,
                STANDBY_BUCKET_RESTRICTED, 0L
        );
        // For PACKAGE_1, only WORKING_SET bucket should have an expiry time.
        verifyBucketExpiryTimes(aih, PACKAGE_1, USER_ID, 5000 /* elapsedRealtime */,
                expectedExpiryTimes1);
        final Map<Integer, Long> expectedExpiryTimes2 = Map.of(
                STANDBY_BUCKET_ACTIVE, 0L,
                STANDBY_BUCKET_WORKING_SET, 0L,
                STANDBY_BUCKET_FREQUENT, 0L,
                STANDBY_BUCKET_RARE, 0L,
                STANDBY_BUCKET_RESTRICTED, 0L
        );
        // For PACKAGE_2, there shouldn't be any expiry time since the one set earlier would have
        // elapsed by the time the data was persisted to disk
        verifyBucketExpiryTimes(aih, PACKAGE_2, USER_ID, 5000 /* elapsedRealtime */,
                expectedExpiryTimes2);
    }

    private void verifyBucketExpiryTimes(AppIdleHistory aih, String packageName, int userId,
            long elapsedRealtimeMs, Map<Integer, Long> expectedExpiryTimesMs) throws Exception {
        for (Map.Entry<Integer, Long> entry : expectedExpiryTimesMs.entrySet()) {
            final int bucket = entry.getKey();
            final long expectedExpiryTimeMs = entry.getValue();
            final long actualExpiryTimeMs = aih.getBucketExpiryTimeMs(packageName, userId, bucket,
                    elapsedRealtimeMs);
            assertEquals("Unexpected expiry time for pkg=" + packageName + ", userId=" + userId
                            + ", bucket=" + standbyBucketToString(bucket),
                    expectedExpiryTimeMs, actualExpiryTimeMs);
        }
    }
}