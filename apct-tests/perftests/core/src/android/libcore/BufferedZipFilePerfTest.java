/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
@LargeTest
public final class BufferedZipFilePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    int[] mReadSize = new int[] {4, 32, 128};
    int[] mCompressedSize = new int[] {128, 1024, 8192, 65536};
    private File mFile;

    @Before
    public void setUp() throws Exception {
        mFile = File.createTempFile("BufferedZipFilePerfTest", ".zip");
        mFile.deleteOnExit();
        Random random = new Random(0);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(mFile));
        for (int i = 0; i < mCompressedSize.length; i++) {
            byte[] data = new byte[8192];
            out.putNextEntry(new ZipEntry("entry.data" + mCompressedSize[i]));
            int written = 0;
            while (written < mCompressedSize[i]) {
                random.nextBytes(data);
                int toWrite = Math.min(mCompressedSize[i] - written, data.length);
                out.write(data, 0, toWrite);
                written += toWrite;
            }
        }
        out.close();
    }

    @Test
    public void timeUnbufferedRead() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mCompressedSize.length; i++) {
                for (int j = 0; j < mReadSize.length; j++) {
                    ZipFile zipFile = new ZipFile(mFile);
                    ZipEntry entry = zipFile.getEntry("entry.data" + mCompressedSize[i]);
                    InputStream in = zipFile.getInputStream(entry);
                    byte[] buffer = new byte[mReadSize[j]];
                    while (in.read(buffer) != -1) {
                        // Keep reading
                    }
                    in.close();
                    zipFile.close();
                }
            }
        }
    }

    @Test
    public void timeBufferedRead() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < mCompressedSize.length; i++) {
                for (int j = 0; j < mReadSize.length; j++) {
                    ZipFile zipFile = new ZipFile(mFile);
                    ZipEntry entry = zipFile.getEntry("entry.data" + mCompressedSize[i]);
                    InputStream in = new BufferedInputStream(zipFile.getInputStream(entry));
                    byte[] buffer = new byte[mReadSize[j]];
                    while (in.read(buffer) != -1) {
                        // Keep reading
                    }
                    in.close();
                    zipFile.close();
                }
            }
        }
    }
}
