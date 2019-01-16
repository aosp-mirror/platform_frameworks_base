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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.util.proto.ProtoOutputStream;

import androidx.test.filters.SmallTest;

import com.android.internal.util.Preconditions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;


/**
 * Test class for {@link WindowTraceBuffer} and {@link WindowTraceQueueBuffer}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowTraceBufferTest
 */
@SmallTest
@Presubmit
public class WindowTraceBufferTest {
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
        final int objectSize = toWrite1.getRawSize();
        final int bufferCapacity = objectSize * 2;

        final WindowTraceBuffer buffer = buildQueueBuffer(bufferCapacity);

        buffer.add(toWrite1);
        byte[] toWrite1Bytes = toWrite1.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWrite1Bytes));

        buffer.add(toWrite2);
        byte[] toWrite2Bytes = toWrite2.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWrite1Bytes));
        assertTrue("Second element should be in the list",
                buffer.contains(toWrite2Bytes));

        buffer.add(toWrite3);
        byte[] toWrite3Bytes = toWrite3.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWrite1Bytes));
        assertTrue("Second element should be in the list",
                buffer.contains(toWrite2Bytes));
        assertTrue("Third element should not be in the list",
                !buffer.contains(toWrite3Bytes));

        assertEquals("Buffer should have 2 elements", buffer.mBuffer.size(), 2);
        assertEquals(String.format("Buffer is full, used space should be %d", bufferCapacity),
                buffer.mBufferSize, bufferCapacity);
        assertEquals("Buffer is full, available space should be 0",
                buffer.getAvailableSpace(), 0);
    }

    @Test
    public void testTraceRingBuffer_addItem() throws Exception {
        ProtoOutputStream toWrite = getDummy(1);
        final int objectSize = toWrite.getRawSize();

        final WindowTraceBuffer buffer = buildRingBuffer(objectSize);

        Preconditions.checkArgument(buffer.mBuffer.isEmpty());

        buffer.add(toWrite);

        assertEquals("Item was not added to the buffer", buffer.mBuffer.size(), 1);
        assertEquals("Total buffer getSize differs from inserted object",
                buffer.mBufferSize, objectSize);
        assertEquals("Available buffer space does not match used one",
                buffer.getAvailableSpace(), 0);
    }

    @Test
    public void testTraceRingBuffer_addItemMustOverwriteOne() throws Exception {
        ProtoOutputStream toWrite1 = getDummy(1);
        ProtoOutputStream toWrite2 = getDummy(2);
        ProtoOutputStream toWrite3 = getDummy(3);
        final int objectSize = toWrite1.getRawSize();

        final int bufferCapacity = objectSize * 2 + 1;
        final WindowTraceBuffer buffer = buildRingBuffer(bufferCapacity);

        buffer.add(toWrite1);
        byte[] toWrite1Bytes = toWrite1.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWrite1Bytes));

        buffer.add(toWrite2);
        byte[] toWrite2Bytes = toWrite2.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWrite1Bytes));
        assertTrue("Second element should be in the list",
                buffer.contains(toWrite2Bytes));

        buffer.add(toWrite3);
        byte[] toWrite3Bytes = toWrite3.getBytes();
        assertTrue("First element should not be in the list",
                !buffer.contains(toWrite1Bytes));
        assertTrue("Second element should be in the list",
                buffer.contains(toWrite2Bytes));
        assertTrue("Third element should be in the list",
                buffer.contains(toWrite3Bytes));
        assertEquals("Buffer should have 2 elements", buffer.mBuffer.size(), 2);
        assertEquals(String.format("Buffer is full, used space should be %d", bufferCapacity),
                buffer.mBufferSize, bufferCapacity - 1);
        assertEquals(" Buffer is full, available space should be 0",
                buffer.getAvailableSpace(), 1);
    }

    @Test
    public void testTraceRingBuffer_addItemMustOverwriteMultiple() throws Exception {
        ProtoOutputStream toWriteSmall1 = getDummy(1);
        ProtoOutputStream toWriteSmall2 = getDummy(2);
        final int objectSize = toWriteSmall1.getRawSize();

        final int bufferCapacity = objectSize * 2;
        final WindowTraceBuffer buffer = buildRingBuffer(bufferCapacity);

        ProtoOutputStream toWriteBig = new ProtoOutputStream();
        toWriteBig.write(MAGIC_NUMBER, 1);
        toWriteBig.write(MAGIC_NUMBER, 2);

        buffer.add(toWriteSmall1);
        byte[] toWriteSmall1Bytes = toWriteSmall1.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWriteSmall1Bytes));

        buffer.add(toWriteSmall2);
        byte[] toWriteSmall2Bytes = toWriteSmall2.getBytes();
        assertTrue("First element should be in the list",
                buffer.contains(toWriteSmall1Bytes));
        assertTrue("Second element should be in the list",
                buffer.contains(toWriteSmall2Bytes));

        buffer.add(toWriteBig);
        byte[] toWriteBigBytes = toWriteBig.getBytes();
        assertTrue("Third element should overwrite all others",
                !buffer.contains(toWriteSmall1Bytes));
        assertTrue("Third element should overwrite all others",
                !buffer.contains(toWriteSmall2Bytes));
        assertTrue("Third element should overwrite all others",
                buffer.contains(toWriteBigBytes));

        assertEquals(" Buffer should have only 1 big element", buffer.mBuffer.size(), 1);
        assertEquals(String.format(" Buffer is full, used space should be %d", bufferCapacity),
                buffer.mBufferSize, bufferCapacity);
        assertEquals(" Buffer is full, available space should be 0",
                buffer.getAvailableSpace(), 0);
    }

    private WindowTraceBuffer buildRingBuffer(int capacity) throws IOException {
        return new WindowTraceBuffer.Builder()
                .setContinuousMode(true)
                .setBufferCapacity(capacity)
                .setTraceFile(mFile)
                .build();
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
