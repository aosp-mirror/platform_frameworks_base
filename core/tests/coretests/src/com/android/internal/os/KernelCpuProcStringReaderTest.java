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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;
import android.os.SystemClock;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedWriter;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Test class for {@link KernelCpuProcStringReader}.
 *
 * $ atest FrameworksCoreTests:com.android.internal.os.KernelCpuProcStringReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(reason = "Needs kernel support")
public class KernelCpuProcStringReaderTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private File mRoot;
    private File mTestDir;
    private File mTestFile;
    private Random mRand = new Random(12345);
    private KernelCpuProcStringReader mReader;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() {
        mTestDir = getContext().getDir("test", Context.MODE_PRIVATE);
        mRoot = getContext().getFilesDir();
        mTestFile = new File(mTestDir, "test.file");
        mReader = new KernelCpuProcStringReader(mTestFile.getAbsolutePath());
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mTestDir);
        FileUtils.deleteContents(mRoot);
    }


    /**
     * Tests that reading will return null if the file does not exist.
     */
    @Test
    public void testReadInvalidFile() throws Exception {
        assertEquals(null, mReader.open());
    }

    /**
     * Tests that reading will always return null after 5 failures.
     */
    @Test
    public void testReadErrorsLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
                assertNull(iter);
            }
            SystemClock.sleep(50);
        }
        final String data = "018n9x134yrm9sry01298yMF1X980Ym908u98weruwe983^(*)0N)&tu09281my\n";
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(data);
        }
        try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
            assertEquals(data.length(), iter.size());
            assertEquals(data, iter.nextLine().toString() + '\n');
        }
        assertTrue(mTestFile.delete());
        for (int i = 0; i < 3; i++) {
            try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open(true)) {
                assertNull(iter);
            }
            SystemClock.sleep(50);
        }
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(data);
        }
        try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open(true)) {
            assertNull(iter);
        }
    }

    /** Tests nextLine functionality. */
    @Test
    public void testReadLine() throws Exception {
        final String data = "10103: 0 0 0 1 5 3 1 2 0 0 3 0 0 0 0 2 2 330 0 0 0 0 1 0 0 0 0 0 0 0"
                + " 0 0 0 0 0 0 0 0 0 0 0 13\n"
                + "50083: 0 0 0 29 0 13 0 4 5 0 0 0 0 0 1 0 0 15 0 0 0 0 0 0 1 0 0 0 0 1 0 1 7 0 "
                + "0 1 1 1 0 2 0 221\n"
                + "50227: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 196 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
                + " 0 2 0 0 0 2 721\n"
                + "10158: 0 0 0 0 19 3 9 1 0 7 4 3 3 3 1 3 10 893 2 0 3 0 0 0 0 0 0 0 0 1 0 2 0 0"
                + " 1 2 10 0 0 0 1 58\n"
                + "50138: 0 0 0 8 7 0 0 0 0 0 0 0 0 0 0 0 0 322 0 0 0 3 0 5 0 0 3 0 0 0 0 1 0 0 0"
                + " 0 0 2 0 0 7 707\n";
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(data);
        }
        try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
            assertEquals(
                    "10103: 0 0 0 1 5 3 1 2 0 0 3 0 0 0 0 2 2 330 0 0 0 0 1 0 0 0 0 0 0 0 0 0 0 0"
                            + " 0 0 0 0 0 0 0 13",
                    iter.nextLine().toString());
            assertEquals(
                    "50083: 0 0 0 29 0 13 0 4 5 0 0 0 0 0 1 0 0 15 0 0 0 0 0 0 1 0 0 0 0 1 0 1 7 "
                            + "0 0 1 1 1 0 2 0 221",
                    iter.nextLine().toString());
            long[] actual = new long[43];
            KernelCpuProcStringReader.asLongs(iter.nextLine(), actual);
            assertArrayEquals(
                    new long[]{50227, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 196, 0, 0,
                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 2, 721},
                    actual);
            assertEquals(
                    "10158: 0 0 0 0 19 3 9 1 0 7 4 3 3 3 1 3 10 893 2 0 3 0 0 0 0 0 0 0 0 1 0 2 0"
                            + " 0 1 2 10 0 0 0 1 58",
                    iter.nextLine().toString());
            assertEquals(
                    "50138: 0 0 0 8 7 0 0 0 0 0 0 0 0 0 0 0 0 322 0 0 0 3 0 5 0 0 3 0 0 0 0 1 0 0"
                            + " 0 0 0 2 0 0 7 707",
                    iter.nextLine().toString());
        }
    }

    /** Stress tests read functionality. */
    @Test
    public void testMultipleRead() throws Exception {
        for (int i = 0; i < 100; i++) {
            final String data = getTestString(600, 150);
            try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
                w.write(data);
            }
            String[] lines = data.split("\n");
            try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open(true)) {
                for (String line : lines) {
                    assertEquals(line, iter.nextLine().toString());
                }
            }
            assertTrue(mTestFile.delete());
        }
    }

    /** Tests reading lines, then converting to long[]. */
    @Test
    public void testReadLineToArray() throws Exception {
        final long[][] data = getTestArray(800, 50);
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(arrayToString(data));
        }
        long[] actual = new long[50];
        try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
            for (long[] expected : data) {
                CharBuffer cb = iter.nextLine();
                String before = cb.toString();
                assertEquals(50, KernelCpuProcStringReader.asLongs(cb, actual));
                assertArrayEquals(expected, actual);
                assertEquals("Buffer not reset to the pos before reading", before, cb.toString());
            }
        }
    }

    /** Tests error handling of converting to long[]. */
    @Test
    public void testLineToArrayErrorHandling() {
        long[] actual = new long[100];
        String invalidChar = "123: -1234 456";
        String overflow = "123: 999999999999999999999999999999999999999999999999999999999 123";
        CharBuffer cb = CharBuffer.wrap("----" + invalidChar + "+++", 4, 4 + invalidChar.length());
        assertEquals("Failed to report err for: " + invalidChar, -2,
                KernelCpuProcStringReader.asLongs(cb, actual));
        assertEquals("Buffer not reset to the same pos before reading", invalidChar, cb.toString());

        cb = CharBuffer.wrap("----" + overflow + "+++", 4, 4 + overflow.length());
        assertEquals("Failed to report err for: " + overflow, -3,
                KernelCpuProcStringReader.asLongs(cb, actual));
        assertEquals("Buffer not reset to the pos before reading", overflow, cb.toString());
    }

    /**
     * Tests that reading a file over the limit (1MB) will return null.
     */
    @Test
    public void testReadOverLimit() throws Exception {
        final String data = getTestString(1, 1024 * 1024 + 1);
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(data);
        }
        try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
            assertNull(iter);
        }
    }

    /**
     * Tests concurrent reading with 5 threads.
     */
    @Test
    public void testConcurrent() throws Exception {
        final String data = getTestString(200, 150);
        final String data1 = getTestString(180, 120);
        final String[] lines = data.split("\n");
        final String[] lines1 = data1.split("\n");
        final List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());
        try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
            w.write(data);
        }
        // An additional thread for modifying the file content.
        ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(11);
        final CountDownLatch ready = new CountDownLatch(10);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch modify = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(10);

        // Schedules 5 threads to be executed together now, and 5 to be executed after file is
        // modified.
        for (int i = 0; i < 5; i++) {
            threadPool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
                        for (String line : lines) {
                            assertEquals(line, iter.nextLine().toString());
                        }
                    }
                } catch (Throwable e) {
                    errs.add(e);
                } finally {
                    done.countDown();
                }
            });
            threadPool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    // Wait for file modification.
                    modify.await();
                    try (KernelCpuProcStringReader.ProcFileIterator iter = mReader.open()) {
                        for (String line : lines1) {
                            assertEquals(line, iter.nextLine().toString());
                        }
                    }
                } catch (Throwable e) {
                    errs.add(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue("Prep timed out", ready.await(100, TimeUnit.MILLISECONDS));
        start.countDown();

        threadPool.schedule(() -> {
            assertTrue(mTestFile.delete());
            try (BufferedWriter w = Files.newBufferedWriter(mTestFile.toPath())) {
                w.write(data1);
            } catch (Throwable e) {
                errs.add(e);
            } finally {
                modify.countDown();
            }
        }, 600, TimeUnit.MILLISECONDS);

        assertTrue("Execution timed out", done.await(3, TimeUnit.SECONDS));
        threadPool.shutdownNow();

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        errs.forEach(e -> e.printStackTrace(pw));

        assertTrue("All Exceptions:\n" + sw.toString(), errs.isEmpty());
    }

    private String getTestString(int lines, int charsPerLine) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < lines; i++) {
            for (int j = 0; j < charsPerLine; j++) {
                sb.append((char) (mRand.nextInt(93) + 32));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private long[][] getTestArray(int lines, int numPerLine) {
        return IntStream.range(0, lines).mapToObj(
                (i) -> mRand.longs(numPerLine, 0, Long.MAX_VALUE).toArray()).toArray(long[][]::new);
    }

    private String arrayToString(long[][] array) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i][0]).append(':');
            for (int j = 1; j < array[0].length; j++) {
                sb.append(' ').append(array[i][j]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
