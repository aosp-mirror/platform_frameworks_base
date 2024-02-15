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

package com.android.fsverity;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.security.FileIntegrityManager;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Test helper that works with the host-side test to set up a test file, and to verify fs-verity
 * verification is done expectedly.
 */
public class Helper {
    private static final String TAG = "FsVerityTest";

    private static final String FILENAME = "test.file";

    private static final long BLOCK_SIZE = 4096;

    @Test
    public void prepareTest() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        android.os.Bundle testArgs = InstrumentationRegistry.getArguments();

        String basename = testArgs.getString("basename");
        context.deleteFile(basename);

        assertThat(testArgs).isNotNull();
        int fileSize = Integer.parseInt(testArgs.getString("fileSize"));
        Log.d(TAG, "Preparing test file with size " + fileSize);

        byte[] bytes = new byte[8192];
        Arrays.fill(bytes, (byte) '1');
        try (FileOutputStream os = context.openFileOutput(basename, Context.MODE_PRIVATE)) {
            for (int i = 0; i < fileSize; i += bytes.length) {
                if (i + bytes.length > fileSize) {
                    os.write(bytes, 0, fileSize % bytes.length);
                } else {
                    os.write(bytes);
                }
            }
        }

        // Enable fs-verity
        FileIntegrityManager fim = context.getSystemService(FileIntegrityManager.class);
        fim.setupFsVerity(context.getFileStreamPath(basename));
    }

    @Test
    public void verifyFileRead() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        // Collect indices that the backing blocks are supposed to be corrupted.
        android.os.Bundle testArgs = InstrumentationRegistry.getArguments();
        assertThat(testArgs).isNotNull();
        String filePath = testArgs.getString("filePath");
        String csv = testArgs.getString("brokenBlockIndicesCsv");
        Log.d(TAG, "brokenBlockIndicesCsv: " + csv);
        String[] strings = csv.split(",");
        var corrupted = new ArrayList(strings.length);
        for (int i = 0; i < strings.length; i++) {
            corrupted.add(Integer.parseInt(strings[i]));
        }

        // Expect the read to succeed or fail per the prior.
        try (var file = new RandomAccessFile(filePath, "r")) {
            long total_blocks = (file.length() + BLOCK_SIZE - 1) / BLOCK_SIZE;
            for (int i = 0; i < (int) total_blocks; i++) {
                file.seek(i * BLOCK_SIZE);
                if (corrupted.contains(i)) {
                    Log.d(TAG, "Expecting read at block #" + i + " to fail");
                    assertThrows(IOException.class, () -> file.read());
                } else {
                    assertThat(file.readByte()).isEqualTo('1');
                }
            }
        }
    }
}
