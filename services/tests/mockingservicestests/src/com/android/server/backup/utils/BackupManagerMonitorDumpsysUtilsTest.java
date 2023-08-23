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

import android.app.backup.BackupManagerMonitor;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class BackupManagerMonitorDumpsysUtilsTest {
    private File mTempFile;
    private TestBackupManagerMonitorDumpsysUtils mBackupManagerMonitorDumpsysUtils;
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        mTempFile = tmp.newFile("testbmmevents.txt");
        mBackupManagerMonitorDumpsysUtils = new TestBackupManagerMonitorDumpsysUtils();
    }


    @Test
    public void parseBackupManagerMonitorEventForDumpsys_bundleIsNull_noLogsWrittenToFile()
            throws Exception {
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(null);

        assertTrue(mTempFile.length() == 0);

    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_missingID_noLogsWrittenToFile()
            throws Exception {
        Bundle event = new Bundle();
        event.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY, 1);
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);

        assertTrue(mTempFile.length() == 0);
    }

    @Test
    public void parseBackupManagerMonitorEventForDumpsys_missingCategory_noLogsWrittenToFile()
            throws Exception {
        Bundle event = new Bundle();
        event.putInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID, 1);
        mBackupManagerMonitorDumpsysUtils.parseBackupManagerMonitorRestoreEventForDumpsys(event);

        assertTrue(mTempFile.length() == 0);
    }

    private class TestBackupManagerMonitorDumpsysUtils
            extends BackupManagerMonitorDumpsysUtils {
        TestBackupManagerMonitorDumpsysUtils() {
            super();
        }

        @Override
        public File getBMMEventsFile() {
            return mTempFile;
        }
    }
}
