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

package com.android.server.backup.utils;

import static org.junit.Assert.assertTrue;
import static org.testng.AssertJUnit.assertFalse;
import android.app.backup.BackupAnnotations;
import android.app.backup.BackupManagerMonitor;
import android.os.Bundle;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileWriter;

public class BackupManagerMonitorDumpsysUtilsTest {
    private long mRetentionPeriod;
    private File mTempBMMEventsFile;
    private File mTempSetUpDateFile;

    private long mSizeLimit;
    private TestBackupManagerMonitorDumpsysUtils mBackupManagerMonitorDumpsysUtils;
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        mRetentionPeriod = 30 * 60 * 1000;
        mSizeLimit = 25 * 1024 * 1000;
        mTempBMMEventsFile = tmp.newFile("testbmmevents.txt");
        mTempSetUpDateFile = tmp.newFile("testSetUpDate.txt");
        mBackupManagerMonitorDumpsysUtils = new TestBackupManagerMonitorDumpsysUtils();
    }


    @Test
    public void parseBackupManagerMonitorEventForDumpsys_bundleIsNull_noLogsWrittenToFile()
            throws Exception {
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(null);

        assertTrue(mTempBMMEventsFile.length() == 0);

    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_missingID_noLogsWrittenToFile()
            throws Exception {
        Bundle event = new Bundle();
        event.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY, 1);
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);

        assertTrue(mTempBMMEventsFile.length() == 0);
    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_missingCategory_noLogsWrittenToFile()
            throws Exception {
        Bundle event = new Bundle();
        event.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID, 1);
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);

        assertTrue(mTempBMMEventsFile.length() == 0);
    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_eventWithCategoryAndId_eventIsWrittenToFile()
            throws Exception {
        Bundle event = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);

        assertTrue(mTempBMMEventsFile.length() != 0);
    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_firstEvent_recordSetUpTimestamp()
            throws Exception {
        assertTrue(mTempBMMEventsFile.length()==0);
        assertTrue(mTempSetUpDateFile.length()==0);

        Bundle event = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);

        assertTrue(mTempBMMEventsFile.length() != 0);
        assertTrue(mTempSetUpDateFile.length()!=0);
    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_notFirstEvent_doNotChangeSetUpTimestamp()
            throws Exception {
        Bundle event1 = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event1);
        String setUpTimestampBefore = mBackupManagerMonitorDumpsysUtils.getSetUpDate();

        Bundle event2 = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event2);
        String setUpTimestampAfter = mBackupManagerMonitorDumpsysUtils.getSetUpDate();

        assertTrue(setUpTimestampBefore.equals(setUpTimestampAfter));
    }


    @Test
    public void parseBackupManagerMonitorEventForDumpsys_fileOverSizeLimit_doNotRecordEvents()
            throws Exception {
        assertTrue(mTempBMMEventsFile.length() == 0);
        Bundle event = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);
        long fileSizeBefore = mTempBMMEventsFile.length();

        mBackupManagerMonitorDumpsysUtils.setTestSizeLimit(0);
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);
        long fileSizeAfter = mTempBMMEventsFile.length();
        assertTrue(mBackupManagerMonitorDumpsysUtils.isFileLargerThanSizeLimit(mTempBMMEventsFile));
        assertTrue(fileSizeBefore == fileSizeAfter);
    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_fileUnderSizeLimit_recordEvents()
            throws Exception {
        assertTrue(mTempBMMEventsFile.length() == 0);
        Bundle event = createRestoreBMMEvent();

        mBackupManagerMonitorDumpsysUtils.setTestSizeLimit(25 * 1024 * 1000);
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);
        assertFalse(mBackupManagerMonitorDumpsysUtils.isFileLargerThanSizeLimit(mTempBMMEventsFile));
        assertTrue(mTempBMMEventsFile.length() != 0);
    }

    @Test
    public void deleteExpiredBackupManagerMonitorEvent_eventsAreExpired_deleteEventsAndReturnTrue()
            throws Exception {
        Bundle event = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);
        assertTrue(mTempBMMEventsFile.length() != 0);
        // Re-initialise the test BackupManagerMonitorDumpsysUtils to
        // clear the cached value of isAfterRetentionPeriod
        mBackupManagerMonitorDumpsysUtils = new TestBackupManagerMonitorDumpsysUtils();

        // set a retention period of 0 second
        mBackupManagerMonitorDumpsysUtils.setTestRetentionPeriod(0);

        assertTrue(mBackupManagerMonitorDumpsysUtils.deleteExpiredBMMEvents());
        assertFalse(mTempBMMEventsFile.exists());
    }

    @Test
    public void deleteExpiredBackupManagerMonitorEvent_eventsAreNotExpired_returnFalse() throws
            Exception {
        Bundle event = createRestoreBMMEvent();
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);
        assertTrue(mTempBMMEventsFile.length() != 0);

        // set a retention period of 30 minutes
        mBackupManagerMonitorDumpsysUtils.setTestRetentionPeriod(30 * 60 * 1000);

        assertFalse(mBackupManagerMonitorDumpsysUtils.deleteExpiredBMMEvents());
        assertTrue(mTempBMMEventsFile.length() != 0);
    }

    @Test
    public void isAfterRetentionPeriod_afterRetentionPeriod_returnTrue() throws
            Exception {
        mBackupManagerMonitorDumpsysUtils.recordSetUpTimestamp();

        // set a retention period of 0 second
        mBackupManagerMonitorDumpsysUtils.setTestRetentionPeriod(0);

        assertTrue(mBackupManagerMonitorDumpsysUtils.isAfterRetentionPeriod());
    }

    @Test
    public void isAfterRetentionPeriod_beforeRetentionPeriod_returnFalse() throws
            Exception {
        mBackupManagerMonitorDumpsysUtils.recordSetUpTimestamp();

        // set a retention period of 30 minutes
        mBackupManagerMonitorDumpsysUtils.setTestRetentionPeriod(30 * 60 * 1000);

        assertFalse(mBackupManagerMonitorDumpsysUtils.isAfterRetentionPeriod());
    }

    @Test
    public void isAfterRetentionPeriod_noSetupDate_returnFalse() throws
            Exception {
        assertTrue(mTempSetUpDateFile.length() == 0);

        assertFalse(mBackupManagerMonitorDumpsysUtils.isAfterRetentionPeriod());
    }

    @Test
    public void isDateAfterNMillisec_date1IsAfterThanDate2_returnTrue() throws
            Exception {
        long timestamp1 = System.currentTimeMillis();
        long timestamp2 = timestamp1 - 1;

        assertTrue(mBackupManagerMonitorDumpsysUtils.isDateAfterNMillisec(timestamp1, timestamp2,
                0));
    }

    @Test
    public void isDateAfterNMillisec_date1IsAfterNMillisecFromDate2_returnTrue() throws
            Exception {
        long timestamp1 = System.currentTimeMillis();
        long timestamp2 = timestamp1 + 10;

        assertTrue(mBackupManagerMonitorDumpsysUtils.isDateAfterNMillisec(timestamp1, timestamp2,
                10));
    }

    @Test
    public void isDateAfterNMillisec_date1IsLessThanNMillisecFromDate2_returnFalse() throws
            Exception {
        long timestamp1 = System.currentTimeMillis();
        long timestamp2 = timestamp1 + 10;

        assertFalse(mBackupManagerMonitorDumpsysUtils.isDateAfterNMillisec(timestamp1, timestamp2,
                11));
    }

    @Test
    public void recordSetUpTimestamp_timestampNotSetBefore_setTimestamp() throws
            Exception {
        assertTrue(mTempSetUpDateFile.length() == 0);

        mBackupManagerMonitorDumpsysUtils.recordSetUpTimestamp();

        assertTrue(mTempSetUpDateFile.length() != 0);
    }

    @Test
    public void recordSetUpTimestamp_timestampSetBefore_doNothing() throws
            Exception {
        mBackupManagerMonitorDumpsysUtils.recordSetUpTimestamp();
        assertTrue(mTempSetUpDateFile.length() != 0);
        String timestampBefore = mBackupManagerMonitorDumpsysUtils.getSetUpDate();

        mBackupManagerMonitorDumpsysUtils.recordSetUpTimestamp();

        assertTrue(mTempSetUpDateFile.length() != 0);
        String timestampAfter = mBackupManagerMonitorDumpsysUtils.getSetUpDate();
        assertTrue(timestampAfter.equals(timestampBefore));
    }

    private Bundle createRestoreBMMEvent() {
        Bundle event = new Bundle();
        event.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID, 1);
        event.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY, 1);
        event.putInt(BackupManagerMonitor.EXTRA_LOG_OPERATION_TYPE,
                BackupAnnotations.OperationType.RESTORE);
        return event;
    }

    private class TestBackupManagerMonitorDumpsysUtils
            extends BackupManagerMonitorDumpsysUtils {

        private long testRetentionPeriod;
        private long testSizeLimit;

        TestBackupManagerMonitorDumpsysUtils() {
            super();
            this.testRetentionPeriod = mRetentionPeriod;
            this.testSizeLimit = mSizeLimit;
        }

        public void setTestRetentionPeriod(long testRetentionPeriod) {
            this.testRetentionPeriod = testRetentionPeriod;
        }
        public void setTestSizeLimit(long testSizeLimit) {
            this.testSizeLimit = testSizeLimit;
        }

        @Override
        public File getBMMEventsFile() {
            return mTempBMMEventsFile;
        }

        @Override
        File getSetUpDateFile() {
            return mTempSetUpDateFile;
        }

        @Override
        long getRetentionPeriodInMillisec() {
            return testRetentionPeriod;
        }

        @Override
        long getBMMEventsFileSizeLimit(){
            return testSizeLimit;
        }


    }
}
