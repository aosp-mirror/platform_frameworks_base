/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@RunWith(Parameterized.class)
@LargeTest
public class ZipFileReadPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "readBufferSize={0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] {{1024}, {16384}, {65536}});
    }

    private File mFile;

    @Parameterized.Parameter(0)
    public int readBufferSize;

    @Before
    public void setUp() throws Exception {
        mFile = File.createTempFile(getClass().getName(), ".zip");
        writeEntries(new ZipOutputStream(new FileOutputStream(mFile)), 2, 1024 * 1024);
        ZipFile zipFile = new ZipFile(mFile);
        for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
            ZipEntry zipEntry = e.nextElement();
        }
        zipFile.close();
    }

    /** Compresses the given number of files, each of the given size, into a .zip archive. */
    protected void writeEntries(ZipOutputStream out, int entryCount, long entrySize)
            throws IOException {
        byte[] writeBuffer = new byte[8192];
        Random random = new Random();
        try {
            for (int entry = 0; entry < entryCount; ++entry) {
                ZipEntry ze = new ZipEntry(Integer.toHexString(entry));
                ze.setSize(entrySize);
                out.putNextEntry(ze);

                for (long i = 0; i < entrySize; i += writeBuffer.length) {
                    random.nextBytes(writeBuffer);
                    int byteCount = (int) Math.min(writeBuffer.length, entrySize - i);
                    out.write(writeBuffer, 0, byteCount);
                }

                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    @Test
    public void timeZipFileRead() throws Exception {
        byte[] readBuffer = new byte[readBufferSize];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ZipFile zipFile = new ZipFile(mFile);
            for (Enumeration<? extends ZipEntry> e = zipFile.entries(); e.hasMoreElements(); ) {
                ZipEntry zipEntry = e.nextElement();
                InputStream is = zipFile.getInputStream(zipEntry);
                while (true) {
                    if (is.read(readBuffer, 0, readBuffer.length) < 0) {
                        break;
                    }
                }
            }
            zipFile.close();
        }
    }
}
