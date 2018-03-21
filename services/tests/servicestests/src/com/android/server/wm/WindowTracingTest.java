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

package com.android.server.wm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.filters.FlakyTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.Preconditions;
import com.android.server.wm.WindowManagerTraceProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Test class for {@link WindowTracing}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.WindowTracingTest
 */
@SmallTest
@FlakyTest(bugId = 74078662)
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowTracingTest extends WindowTestsBase {

    private static final byte[] MAGIC_HEADER = new byte[] {
        0x9, 0x57, 0x49, 0x4e, 0x54, 0x52, 0x41, 0x43, 0x45,
    };

    private Context mTestContext;
    private WindowTracing mWindowTracing;
    private WindowManagerService mWmMock;
    private File mFile;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();

        mWmMock = mock(WindowManagerService.class);

        mTestContext = InstrumentationRegistry.getContext();

        mFile = mTestContext.getFileStreamPath("tracing_test.dat");
        mFile.delete();

        mWindowTracing = new WindowTracing(mFile);
    }

    @Test
    public void isEnabled_returnsFalseByDefault() throws Exception {
        assertFalse(mWindowTracing.isEnabled());
    }

    @Test
    public void isEnabled_returnsTrueAfterStart() throws Exception {
        mWindowTracing.startTrace(mock(PrintWriter.class));
        assertTrue(mWindowTracing.isEnabled());
    }

    @Test
    public void isEnabled_returnsFalseAfterStop() throws Exception {
        mWindowTracing.startTrace(mock(PrintWriter.class));
        mWindowTracing.stopTrace(mock(PrintWriter.class));
        assertFalse(mWindowTracing.isEnabled());
    }

    @Test
    public void trace_discared_whenNotTracing() throws Exception {
        mWindowTracing.traceStateLocked("where", mWmMock);
        verifyZeroInteractions(mWmMock);
    }

    @Test
    public void trace_dumpsWindowManagerState_whenTracing() throws Exception {
        mWindowTracing.startTrace(mock(PrintWriter.class));
        mWindowTracing.traceStateLocked("where", mWmMock);

        verify(mWmMock).writeToProtoLocked(any(), eq(true));
    }

    @Test
    public void traceFile_startsWithMagicHeader() throws Exception {
        mWindowTracing.startTrace(mock(PrintWriter.class));
        mWindowTracing.stopTrace(mock(PrintWriter.class));

        byte[] header = new byte[MAGIC_HEADER.length];
        try (InputStream is = new FileInputStream(mFile)) {
            assertEquals(MAGIC_HEADER.length, is.read(header));
            assertArrayEquals(MAGIC_HEADER, header);
        }
    }

    @Test
    @Ignore("Figure out why this test is crashing when setting up mWmMock.")
    public void tracing_endsUpInFile() throws Exception {
        mWindowTracing.startTrace(mock(PrintWriter.class));

        doAnswer((inv) -> {
            inv.<ProtoOutputStream>getArgument(0).write(
                    WindowManagerTraceProto.WHERE, "TEST_WM_PROTO");
            return null;
        }).when(mWmMock).writeToProtoLocked(any(), any());
        mWindowTracing.traceStateLocked("TEST_WHERE", mWmMock);

        mWindowTracing.stopTrace(mock(PrintWriter.class));

        byte[] file = new byte[1000];
        int fileLength;
        try (InputStream is = new FileInputStream(mFile)) {
            fileLength = is.read(file);
            assertTrue(containsBytes(file, fileLength,
                    "TEST_WHERE".getBytes(StandardCharsets.UTF_8)));
            assertTrue(containsBytes(file, fileLength,
                    "TEST_WM_PROTO".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();

        mFile.delete();
    }

    /** Return true if {@code needle} appears anywhere in {@code haystack[0..length]} */
    boolean containsBytes(byte[] haystack, int haystackLenght, byte[] needle) {
        Preconditions.checkArgument(haystackLenght > 0);
        Preconditions.checkArgument(needle.length > 0);

        outer: for (int i = 0; i <= haystackLenght - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i+j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    public void test_containsBytes() {
        byte[] haystack = "hello_world".getBytes(StandardCharsets.UTF_8);
        assertTrue(containsBytes(haystack, haystack.length,
                "hello".getBytes(StandardCharsets.UTF_8)));
        assertTrue(containsBytes(haystack, haystack.length,
                "world".getBytes(StandardCharsets.UTF_8)));
        assertFalse(containsBytes(haystack, 6,
                "world".getBytes(StandardCharsets.UTF_8)));
        assertFalse(containsBytes(haystack, haystack.length,
                "world_".getBytes(StandardCharsets.UTF_8)));
        assertFalse(containsBytes(haystack, haystack.length,
                "absent".getBytes(StandardCharsets.UTF_8)));
    }
}
