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
 * limitations under the License.
 */

package com.android.internal.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Parcel;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

/**
 * Test BatteryStatsHistory.
 */
@RunWith(AndroidJUnit4.class)
public class BatteryStatsHistoryTest {
    private static final String TAG = "BatteryStatsHistoryTest";
    private static final int MAX_HISTORY_FILES = 32;
    private static final int MAX_HISTORY_BUFFER_KB = 128;

    // Initializing max history files and buffer to the default values of non-low-ram device
    // to maintain consistency in the tests
    private final BatteryStatsImpl mBatteryStatsImpl = new MockBatteryStatsImpl()
            .setMaxHistoryFiles(MAX_HISTORY_FILES)
            .setMaxHistoryBuffer(MAX_HISTORY_BUFFER_KB * 1024);
    private final Parcel mHistoryBuffer = Parcel.obtain();
    private File mSystemDir;
    private File mHistoryDir;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = InstrumentationRegistry.getContext();
        mSystemDir = context.getDataDir();
        mHistoryDir = new File(mSystemDir, BatteryStatsHistory.HISTORY_DIR);
        String[] files = mHistoryDir.list();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                new File(mHistoryDir, files[i]).delete();
            }
        }
        mHistoryDir.delete();
    }

    @Test
    public void testConstruct() {
        BatteryStatsHistory history =
                new BatteryStatsHistory(mBatteryStatsImpl, mSystemDir, mHistoryBuffer);
        createActiveFile(history);
        verifyFileNumbers(history, Arrays.asList(0));
        verifyActiveFile(history, "0.bin");
    }

    @Test
    public void testStartNextFile() {
        BatteryStatsHistory history =
                new BatteryStatsHistory(mBatteryStatsImpl, mSystemDir, mHistoryBuffer);

        List<Integer> fileList = new ArrayList<>();
        fileList.add(0);
        createActiveFile(history);

        // create file 1 to 31.
        for (int i = 1; i < MAX_HISTORY_FILES; i++) {
            fileList.add(i);
            history.startNextFile();
            createActiveFile(history);
            verifyFileNumbers(history, fileList);
            verifyActiveFile(history, i + ".bin");
        }

        // create file 32
        history.startNextFile();
        createActiveFile(history);
        fileList.add(32);
        fileList.remove(0);
        // verify file 0 is deleted.
        verifyFileDeleted("0.bin");
        verifyFileNumbers(history, fileList);
        verifyActiveFile(history, "32.bin");

        // create file 33
        history.startNextFile();
        createActiveFile(history);
        // verify file 1 is deleted
        fileList.add(33);
        fileList.remove(0);
        verifyFileDeleted("1.bin");
        verifyFileNumbers(history, fileList);
        verifyActiveFile(history, "33.bin");

        assertEquals(0, history.getHistoryUsedSize());

        // create a new BatteryStatsHistory object, it will pick up existing history files.
        BatteryStatsHistory history2 =
                new BatteryStatsHistory(mBatteryStatsImpl, mSystemDir, mHistoryBuffer);
        // verify construct can pick up all files from file system.
        verifyFileNumbers(history2, fileList);
        verifyActiveFile(history2, "33.bin");

        history2.resetAllFiles();
        createActiveFile(history2);
        // verify all existing files are deleted.
        for (int i = 2; i < 33; ++i) {
            verifyFileDeleted(i + ".bin");
        }

        // verify file 0 is created
        verifyFileNumbers(history2, Arrays.asList(0));
        verifyActiveFile(history2, "0.bin");

        // create file 1.
        history2.startNextFile();
        createActiveFile(history2);
        verifyFileNumbers(history2, Arrays.asList(0, 1));
        verifyActiveFile(history2, "1.bin");
    }

    private void verifyActiveFile(BatteryStatsHistory history, String file) {
        final File expectedFile = new File(mHistoryDir, file);
        assertEquals(expectedFile.getPath(), history.getActiveFile().getBaseFile().getPath());
        assertTrue(expectedFile.exists());
    }

    private void verifyFileNumbers(BatteryStatsHistory history, List<Integer> fileList) {
        assertEquals(fileList.size(), history.getFilesNumbers().size());
        for (int i = 0; i < fileList.size(); i++) {
            assertEquals(fileList.get(i), history.getFilesNumbers().get(i));
            final File expectedFile =
                    new File(mHistoryDir, fileList.get(i) + ".bin");
            assertTrue(expectedFile.exists());
        }
    }

    private void verifyFileDeleted(String file) {
        assertFalse(new File(mHistoryDir, file).exists());
    }

    private void createActiveFile(BatteryStatsHistory history) {
        final File file = history.getActiveFile().getBaseFile();
        try {
            file.createNewFile();
        } catch (IOException e) {
            Log.e(TAG, "Error creating history file " + file.getPath(), e);
        }
    }
}