/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_H;
import static com.android.server.wm.WindowManagerTraceFileProto.MAGIC_NUMBER_L;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.util.proto.ProtoOutputStream;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;


/**
 * Test class for {@link WindowTraceBuffer} and {@link WindowTraceQueueBuffer}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowTraceBufferTest
 */
@SmallTest
@Presubmit
public class WindowTraceBufferTest {
    private static final long MAGIC_NUMBER_VALUE = ((long) MAGIC_NUMBER_H << 32) | MAGIC_NUMBER_L;

    private File mFile;

    @Before
    public void setUp() throws Exception {
        final Context testContext = getInstrumentation().getContext();
        mFile = testContext.getFileStreamPath("tracing_test.dat");
        mFile.delete();
    }

    @After
    public void tearDown() throws Exception {
        mFile.delete();
    }

    @Test
    public void testTraceQueueBuffer_addItem() throws Exception {
        ProtoOutputStream toWrite1 = getDummy(1);
        ProtoOutputStream toWrite2 = getDummy(2);
        ProtoOutputStream toWrite3 = getDummy(3);
        byte[] toWrite1Bytes = toWrite1.getBytes();
        byte[] toWrite2Bytes = toWrite2.getBytes();
        byte[] toWrite3Bytes = toWrite3.getBytes();

        final int objectSize = toWrite1.getBytes().length;
        final int bufferCapacity = objectSize * 2;

        final WindowTraceBuffer buffer = buildQueueBuffer(bufferCapacity);

        buffer.add(toWrite1);
        assertTrue("First element should be in the list",
                buffer.mBuffer.stream().anyMatch(p -> Arrays.equals(p, toWrite1Bytes)));

        buffer.add(toWrite2);
        assertTrue("First element should be in the list",
                buffer.mBuffer.stream().anyMatch(p -> Arrays.equals(p, toWrite1Bytes)));
        assertTrue("Second element should be in the list",
                buffer.mBuffer.stream().anyMatch(p -> Arrays.equals(p, toWrite2Bytes)));

        buffer.add(toWrite3);

        assertTrue("Third element should not be in the list",
                buffer.mBuffer.stream().noneMatch(p -> Arrays.equals(p, toWrite3Bytes)));

        assertEquals("Buffer should have 2 elements", buffer.mBuffer.size(), 2);
        assertEquals(String.format("Buffer is full, used space should be %d", bufferCapacity),
                buffer.mBufferSize, bufferCapacity);
        assertEquals("Buffer is full, available space should be 0",
                buffer.getAvailableSpace(), 0);
    }

    private ProtoOutputStream getDummy(int value) {
        ProtoOutputStream toWrite = new ProtoOutputStream();
        toWrite.write(MAGIC_NUMBER, value);
        toWrite.flush();

        return toWrite;
    }

    private WindowTraceBuffer buildQueueBuffer(int size) throws IOException {
        return new WindowTraceQueueBuffer(size, mFile, false);
    }
}
