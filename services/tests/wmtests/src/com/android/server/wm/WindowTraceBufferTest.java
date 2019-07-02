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


/**
 * Test class for {@link WindowTraceBuffer}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowTraceBufferTest
 */
@SmallTest
@Presubmit
public class WindowTraceBufferTest {
    private File mFile;
    private WindowTraceBuffer mBuffer;

    @Before
    public void setUp() throws Exception {
        final Context testContext = getInstrumentation().getContext();
        mFile = testContext.getFileStreamPath("tracing_test.dat");
        mFile.delete();

        mBuffer = new WindowTraceBuffer(10);
    }

    @After
    public void tearDown() throws Exception {
        mFile.delete();
    }

    @Test
    public void test_addItem() {
        ProtoOutputStream toWrite = getDummy(1);
        final int objectSize = toWrite.getRawSize();
        mBuffer.setCapacity(objectSize);
        mBuffer.resetBuffer();

        Preconditions.checkArgument(mBuffer.size() == 0);

        mBuffer.add(toWrite);

        assertEquals("Item was not added to the buffer", 1, mBuffer.size());
        assertEquals("Total buffer getSize differs from inserted object",
                mBuffer.getBufferSize(), objectSize);
        assertEquals("Available buffer space does not match used one", 0,
                mBuffer.getAvailableSpace());
    }

    @Test
    public void test_addItemMustOverwriteOne() {
        ProtoOutputStream toWrite1 = getDummy(1);
        ProtoOutputStream toWrite2 = getDummy(2);
        ProtoOutputStream toWrite3 = getDummy(3);
        final int objectSize = toWrite1.getRawSize();
        final int bufferCapacity = objectSize * 2 + 1;
        mBuffer.setCapacity(bufferCapacity);
        mBuffer.resetBuffer();

        mBuffer.add(toWrite1);
        byte[] toWrite1Bytes = toWrite1.getBytes();
        assertTrue("First element should be in the list",
                mBuffer.contains(toWrite1Bytes));

        mBuffer.add(toWrite2);
        byte[] toWrite2Bytes = toWrite2.getBytes();
        assertTrue("First element should be in the list",
                mBuffer.contains(toWrite1Bytes));
        assertTrue("Second element should be in the list",
                mBuffer.contains(toWrite2Bytes));

        mBuffer.add(toWrite3);
        byte[] toWrite3Bytes = toWrite3.getBytes();
        assertTrue("First element should not be in the list",
                !mBuffer.contains(toWrite1Bytes));
        assertTrue("Second element should be in the list",
                mBuffer.contains(toWrite2Bytes));
        assertTrue("Third element should be in the list",
                mBuffer.contains(toWrite3Bytes));
        assertEquals("Buffer should have 2 elements", 2, mBuffer.size());
        assertEquals(String.format("Buffer is full, used space should be %d", bufferCapacity),
                mBuffer.getBufferSize(), bufferCapacity - 1);
        assertEquals(" Buffer is full, available space should be 0", 1,
                mBuffer.getAvailableSpace());
    }

    @Test
    public void test_addItemMustOverwriteMultiple() {
        ProtoOutputStream toWriteSmall1 = getDummy(1);
        ProtoOutputStream toWriteSmall2 = getDummy(2);
        final int objectSize = toWriteSmall1.getRawSize();
        final int bufferCapacity = objectSize * 2;
        mBuffer.setCapacity(bufferCapacity);
        mBuffer.resetBuffer();

        ProtoOutputStream toWriteBig = new ProtoOutputStream();
        toWriteBig.write(MAGIC_NUMBER, 1);
        toWriteBig.write(MAGIC_NUMBER, 2);

        mBuffer.add(toWriteSmall1);
        byte[] toWriteSmall1Bytes = toWriteSmall1.getBytes();
        assertTrue("First element should be in the list",
                mBuffer.contains(toWriteSmall1Bytes));

        mBuffer.add(toWriteSmall2);
        byte[] toWriteSmall2Bytes = toWriteSmall2.getBytes();
        assertTrue("First element should be in the list",
                mBuffer.contains(toWriteSmall1Bytes));
        assertTrue("Second element should be in the list",
                mBuffer.contains(toWriteSmall2Bytes));

        mBuffer.add(toWriteBig);
        byte[] toWriteBigBytes = toWriteBig.getBytes();
        assertTrue("Third element should overwrite all others",
                !mBuffer.contains(toWriteSmall1Bytes));
        assertTrue("Third element should overwrite all others",
                !mBuffer.contains(toWriteSmall2Bytes));
        assertTrue("Third element should overwrite all others",
                mBuffer.contains(toWriteBigBytes));

        assertEquals(" Buffer should have only 1 big element", 1, mBuffer.size());
        assertEquals(String.format(" Buffer is full, used space should be %d", bufferCapacity),
                mBuffer.getBufferSize(), bufferCapacity);
        assertEquals(" Buffer is full, available space should be 0", 0,
                mBuffer.getAvailableSpace());
    }

    @Test
    public void test_startResetsBuffer() {
        ProtoOutputStream toWrite = getDummy(1);
        mBuffer.resetBuffer();
        Preconditions.checkArgument(mBuffer.size() == 0);

        mBuffer.add(toWrite);
        assertEquals("Item was not added to the buffer", 1, mBuffer.size());
        mBuffer.resetBuffer();
        assertEquals("Buffer should be empty after reset", 0, mBuffer.size());
        assertEquals("Buffer size should be 0 after reset", 0, mBuffer.getBufferSize());
    }

    private ProtoOutputStream getDummy(int value) {
        ProtoOutputStream toWrite = new ProtoOutputStream();
        toWrite.write(MAGIC_NUMBER, value);
        toWrite.flush();

        return toWrite;
    }

}
