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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.FileUtils;
import android.os.SystemClock;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/**
 * Test class for {@link KernelCpuProcReader}.
 *
 * $ atest FrameworksCoreTests:com.android.internal.os.KernelCpuProcReaderTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class KernelCpuProcReaderTest {

    private File mRoot;
    private File mTestDir;
    private File mTestFile;
    private Random mRand = new Random();

    private KernelCpuProcReader mKernelCpuProcReader;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() {
        mTestDir = getContext().getDir("test", Context.MODE_PRIVATE);
        mRoot = getContext().getFilesDir();
        mTestFile = new File(mTestDir, "test.file");
        mKernelCpuProcReader = new KernelCpuProcReader(mTestFile.getAbsolutePath());
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
        assertEquals(null, mKernelCpuProcReader.readBytes());
    }

    /**
     * Tests that reading will always return null after 5 failures.
     */
    @Test
    public void testReadErrorsLimit() throws Exception {
        mKernelCpuProcReader.setThrottleInterval(0);
        for (int i = 0; i < 3; i++) {
            assertNull(mKernelCpuProcReader.readBytes());
            SystemClock.sleep(50);
        }

        final byte[] data = new byte[1024];
        mRand.nextBytes(data);
        try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
            os.write(data);
        }
        assertTrue(Arrays.equals(data, toArray(mKernelCpuProcReader.readBytes())));

        assertTrue(mTestFile.delete());
        for (int i = 0; i < 3; i++) {
            assertNull(mKernelCpuProcReader.readBytes());
            SystemClock.sleep(50);
        }
        try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
            os.write(data);
        }
        assertNull(mKernelCpuProcReader.readBytes());
    }

    /**
     * Tests reading functionality.
     */
    @Test
    public void testSimpleRead() throws Exception {
        final byte[] data = new byte[1024];
        mRand.nextBytes(data);
        try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
            os.write(data);
        }
        assertTrue(Arrays.equals(data, toArray(mKernelCpuProcReader.readBytes())));
    }

    /**
     * Tests multiple reading functionality.
     */
    @Test
    public void testMultipleRead() throws Exception {
        mKernelCpuProcReader.setThrottleInterval(0);
        for (int i = 0; i < 100; i++) {
            final byte[] data = new byte[mRand.nextInt(102400) + 4];
            mRand.nextBytes(data);
            try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
                os.write(data);
            }
            assertTrue(Arrays.equals(data, toArray(mKernelCpuProcReader.readBytes())));
            assertTrue(mTestFile.delete());
        }
    }

    /**
     * Tests reading with resizing.
     */
    @Test
    public void testReadWithResize() throws Exception {
        final byte[] data = new byte[128001];
        mRand.nextBytes(data);
        try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
            os.write(data);
        }
        assertTrue(Arrays.equals(data, toArray(mKernelCpuProcReader.readBytes())));
    }

    /**
     * Tests that reading a file over the limit (1MB) will return null.
     */
    @Test
    public void testReadOverLimit() throws Exception {
        final byte[] data = new byte[1228800];
        mRand.nextBytes(data);
        try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
            os.write(data);
        }
        assertNull(mKernelCpuProcReader.readBytes());
    }

    /**
     * Tests throttling. Deleting underlying file should not affect cache.
     */
    @Test
    public void testThrottle() throws Exception {
        mKernelCpuProcReader.setThrottleInterval(3000);
        final byte[] data = new byte[20001];
        mRand.nextBytes(data);
        try (OutputStream os = Files.newOutputStream(mTestFile.toPath())) {
            os.write(data);
        }
        assertTrue(Arrays.equals(data, toArray(mKernelCpuProcReader.readBytes())));
        assertTrue(mTestFile.delete());
        for (int i = 0; i < 5; i++) {
            assertTrue(Arrays.equals(data, toArray(mKernelCpuProcReader.readBytes())));
            SystemClock.sleep(10);
        }
        SystemClock.sleep(5000);
        assertNull(mKernelCpuProcReader.readBytes());
    }

    private byte[] toArray(ByteBuffer buffer) {
        assertNotNull(buffer);
        byte[] arr = new byte[buffer.remaining()];
        buffer.get(arr);
        return arr;
    }
}
