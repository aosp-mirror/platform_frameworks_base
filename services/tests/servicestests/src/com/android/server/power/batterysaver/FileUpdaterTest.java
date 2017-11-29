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
package com.android.server.power.batterysaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.camera2.impl.GetCommand;
import android.os.Handler;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 atest $ANDROID_BUILD_TOP/frameworks/base/services/tests/servicestests/src/com/android/server/power/batterysaver/FileUpdaterTest.java
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class FileUpdaterTest {

    private class FileUpdaterTestable extends FileUpdater {
        FileUpdaterTestable(Context context, Looper looper, int maxRetries, int retryIntervalMs) {
            super(context, looper, maxRetries, retryIntervalMs);
        }

        @Override
        String injectReadFromFileTrimmed(String file) throws IOException {
            return mInjector.injectReadFromFileTrimmed(file);
        }

        @Override
        void injectWriteToFile(String file, String value) throws IOException {
            mInjector.injectWriteToFile(file, value);
        }

        @Override
        void injectWtf(String message, Throwable e) {
            mInjector.injectWtf(message, e);
        }

        @Override
        File injectDefaultValuesFilename() {
            return new File(InstrumentationRegistry.getContext().getCacheDir() +
                    "/test-default.xml");
        }

        @Override
        boolean injectShouldSkipWrite() {
            return false;
        }
    }

    private interface Injector {
        String injectReadFromFileTrimmed(String file) throws IOException;
        void injectWriteToFile(String file, String value) throws IOException;
        void injectWtf(String message, Throwable e);
    }

    private Handler mMainHandler;

    @Mock
    private Injector mInjector;

    private static final int MAX_RETRIES = 3;

    private FileUpdaterTestable mInstance;

    public static <T> T anyOrNull(Class<T> clazz) {
        return ArgumentMatchers.argThat(value -> true);
    }

    public static String anyOrNullString() {
        return ArgumentMatchers.argThat(value -> true);
    }

    @Before
    public void setUp() {
        mMainHandler = new Handler(Looper.getMainLooper());

        MockitoAnnotations.initMocks(this);

        mInstance = newInstance();
    }

    private FileUpdaterTestable newInstance() {
        return new FileUpdaterTestable(
                InstrumentationRegistry.getContext(),
                Looper.getMainLooper(),
                MAX_RETRIES,
                0 /* retry with no delays*/);
    }

    private void waitUntilMainHandlerDrain() throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        mMainHandler.post(() -> l.countDown());
        assertTrue(l.await(5, TimeUnit.SECONDS));
    }

    private void veriryWtf(int times) {
        verify(mInjector, times(times)).injectWtf(anyOrNullString(), anyOrNull(Throwable.class));
    }

    @Test
    public void testNoWrites() throws Exception {
        doReturn("111").when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");
        doReturn("333").when(mInjector).injectReadFromFileTrimmed("file3");

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(0)).injectWriteToFile(anyOrNullString(), anyOrNullString());

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(0)).injectWriteToFile(anyOrNullString(), anyOrNullString());

        // No WTF should have happened.
        veriryWtf(0);
    }

    @Test
    public void testSimpleWrite() throws Exception {
        doReturn("111").when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");
        doReturn("333").when(mInjector).injectReadFromFileTrimmed("file3");

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();
        values.put("file1", "11");

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "11");

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "111");

        // No WTF should have happened.
        veriryWtf(0);
    }

    @Test
    public void testMultiWrites() throws Exception {
        doReturn("111").when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");
        doReturn("333").when(mInjector).injectReadFromFileTrimmed("file3");

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();
        values.put("file1", "11");
        values.put("file2", "22");
        values.put("file3", "33");

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "11");
        verify(mInjector, times(1)).injectWriteToFile("file2", "22");
        verify(mInjector, times(1)).injectWriteToFile("file3", "33");

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "111");
        verify(mInjector, times(1)).injectWriteToFile("file2", "222");
        verify(mInjector, times(1)).injectWriteToFile("file3", "333");

        // No WTF should have happened.
        veriryWtf(0);
    }

    @Test
    public void testCantReadDefault() throws Exception {
        doThrow(new IOException("can't read")).when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();
        values.put("file1", "11");
        values.put("file2", "22");

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(0)).injectWriteToFile("file1", "11");
        verify(mInjector, times(1)).injectWriteToFile("file2", "22");

        veriryWtf(1);

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(0)).injectWriteToFile("file1", "111");
        verify(mInjector, times(1)).injectWriteToFile("file2", "222");

        veriryWtf(1);
    }

    @Test
    public void testWriteGiveUp() throws Exception {
        doReturn("111").when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");
        doReturn("333").when(mInjector).injectReadFromFileTrimmed("fail1");

        doThrow(new IOException("can't write")).when(mInjector).injectWriteToFile(
                eq("fail1"), eq("33"));

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();
        values.put("file1", "11");
        values.put("file2", "22");
        values.put("fail1", "33");

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "11");
        verify(mInjector, times(1)).injectWriteToFile("file2", "22");

        verify(mInjector, times(MAX_RETRIES + 1)).injectWriteToFile("fail1", "33");

        // 1 WTF.
        veriryWtf(1);

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "111");
        verify(mInjector, times(1)).injectWriteToFile("file2", "222");

        verify(mInjector, times(1)).injectWriteToFile("fail1", "333");

        // No further WTF.
        veriryWtf(1);
    }

    @Test
    public void testSuccessWithRetry() throws Exception {
        doReturn("111").when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");
        doReturn("333").when(mInjector).injectReadFromFileTrimmed("fail1");

        final AtomicInteger counter = new AtomicInteger();
        doAnswer((inv) -> {
            if (counter.getAndIncrement() <= 1) {
                throw new IOException();
            }
            return null;
            }).when(mInjector).injectWriteToFile(eq("fail1"), eq("33"));

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();
        values.put("file1", "11");
        values.put("file2", "22");
        values.put("fail1", "33");

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "11");
        verify(mInjector, times(1)).injectWriteToFile("file2", "22");

        // Should succeed after 2 retries.
        verify(mInjector, times(3)).injectWriteToFile("fail1", "33");

        // No WTF.
        veriryWtf(0);

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "111");
        verify(mInjector, times(1)).injectWriteToFile("file2", "222");
        verify(mInjector, times(1)).injectWriteToFile("fail1", "333");

        // Still no WTF.
        veriryWtf(0);
    }

    @Test
    public void testAll() throws Exception {
        // Run multiple tests on the single target instance.

        reset(mInjector);
        testSimpleWrite();

        reset(mInjector);
        testWriteGiveUp();

        reset(mInjector);
        testMultiWrites();

        reset(mInjector);
        testSuccessWithRetry();

        reset(mInjector);
        testMultiWrites();
    }

    @Test
    public void testWriteReadDefault() throws Exception {
        doReturn("111").when(mInjector).injectReadFromFileTrimmed("file1");
        doReturn("222").when(mInjector).injectReadFromFileTrimmed("file2");
        doReturn("333").when(mInjector).injectReadFromFileTrimmed("file3");

        // Write
        final ArrayMap<String, String> values = new ArrayMap<>();
        values.put("file1", "11");
        values.put("file2", "22");
        values.put("file3", "33");

        mInstance.writeFiles(values);
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "11");
        verify(mInjector, times(1)).injectWriteToFile("file2", "22");
        verify(mInjector, times(1)).injectWriteToFile("file3", "33");

        // Clear and reload the default.
        assertEquals(3, mInstance.getDefaultValuesForTest().size());
        mInstance.getDefaultValuesForTest().clear();
        assertEquals(0, mInstance.getDefaultValuesForTest().size());

        mInstance.systemReady(/*runtimeRestarted=*/ true);

        assertEquals(3, mInstance.getDefaultValuesForTest().size());

        // Reset to default
        mInstance.restoreDefault();
        waitUntilMainHandlerDrain();

        verify(mInjector, times(1)).injectWriteToFile("file1", "111");
        verify(mInjector, times(1)).injectWriteToFile("file2", "222");
        verify(mInjector, times(1)).injectWriteToFile("file3", "333");

        // Make sure the default file still exists.
        assertTrue(mInstance.injectDefaultValuesFilename().exists());

        // Simulate a clean boot.
        mInstance.getDefaultValuesForTest().clear();
        assertEquals(0, mInstance.getDefaultValuesForTest().size());

        mInstance.systemReady(/*runtimeRestarted=*/ false);

        // Default is empty, and the file is gone.
        assertEquals(0, mInstance.getDefaultValuesForTest().size());
        assertFalse(mInstance.injectDefaultValuesFilename().exists());

        // No WTF should have happened.
        veriryWtf(0);
   }
}
