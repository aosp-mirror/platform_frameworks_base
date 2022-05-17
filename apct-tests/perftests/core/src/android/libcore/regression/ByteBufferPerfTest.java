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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class ByteBufferPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public enum MyByteOrder {
        BIG(ByteOrder.BIG_ENDIAN),
        LITTLE(ByteOrder.LITTLE_ENDIAN);
        final ByteOrder mByteOrder;

        MyByteOrder(ByteOrder mByteOrder) {
            this.mByteOrder = mByteOrder;
        }
    }

    @Parameters(name = "mByteOrder={0}, mAligned={1}, mBufferType={2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(
                new Object[][] {
                    {MyByteOrder.BIG, true, MyBufferType.DIRECT},
                    {MyByteOrder.LITTLE, true, MyBufferType.DIRECT},
                    {MyByteOrder.BIG, false, MyBufferType.DIRECT},
                    {MyByteOrder.LITTLE, false, MyBufferType.DIRECT},
                    {MyByteOrder.BIG, true, MyBufferType.HEAP},
                    {MyByteOrder.LITTLE, true, MyBufferType.HEAP},
                    {MyByteOrder.BIG, false, MyBufferType.HEAP},
                    {MyByteOrder.LITTLE, false, MyBufferType.HEAP},
                    {MyByteOrder.BIG, true, MyBufferType.MAPPED},
                    {MyByteOrder.LITTLE, true, MyBufferType.MAPPED},
                    {MyByteOrder.BIG, false, MyBufferType.MAPPED},
                    {MyByteOrder.LITTLE, false, MyBufferType.MAPPED}
                });
    }

    @Parameterized.Parameter(0)
    public MyByteOrder mByteOrder;

    @Parameterized.Parameter(1)
    public boolean mAligned;

    enum MyBufferType {
        DIRECT,
        HEAP,
        MAPPED;
    }

    @Parameterized.Parameter(2)
    public MyBufferType mBufferType;

    public static ByteBuffer newBuffer(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws IOException {
        int size = aligned ? 8192 : 8192 + 8 + 1;
        ByteBuffer result = null;
        switch (bufferType) {
            case DIRECT:
                result = ByteBuffer.allocateDirect(size);
                break;
            case HEAP:
                result = ByteBuffer.allocate(size);
                break;
            case MAPPED:
                File tmpFile = new File("/sdcard/bm.tmp");
                if (new File("/tmp").isDirectory()) {
                    // We're running on the desktop.
                    tmpFile = File.createTempFile("MappedByteBufferTest", ".tmp");
                }
                tmpFile.createNewFile();
                tmpFile.deleteOnExit();
                RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
                raf.setLength(8192 * 8);
                FileChannel fc = raf.getChannel();
                result = fc.map(FileChannel.MapMode.READ_WRITE, 0, fc.size());
                break;
        }
        result.order(byteOrder.mByteOrder);
        result.position(aligned ? 0 : 1);
        return result;
    }

    //
    // peeking
    //

    @Test
    public void timeByteBuffer_getByte() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.get();
            }
        }
    }

    @Test
    public void timeByteBuffer_getByteArray() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        byte[] dst = new byte[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(mAligned ? 0 : 1);
                src.get(dst);
            }
        }
    }

    @Test
    public void timeByteBuffer_getByte_indexed() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.get(i);
            }
        }
    }

    @Test
    public void timeByteBuffer_getChar() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getChar();
            }
        }
    }

    @Test
    public void timeCharBuffer_getCharArray() throws Exception {
        CharBuffer src =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asCharBuffer();
        char[] dst = new char[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(0);
                src.get(dst);
            }
        }
    }

    @Test
    public void timeByteBuffer_getChar_indexed() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getChar(i * 2);
            }
        }
    }

    @Test
    public void timeByteBuffer_getDouble() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getDouble();
            }
        }
    }

    @Test
    public void timeDoubleBuffer_getDoubleArray() throws Exception {
        DoubleBuffer src =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asDoubleBuffer();
        double[] dst = new double[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(0);
                src.get(dst);
            }
        }
    }

    @Test
    public void timeByteBuffer_getFloat() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getFloat();
            }
        }
    }

    @Test
    public void timeFloatBuffer_getFloatArray() throws Exception {
        FloatBuffer src =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asFloatBuffer();
        float[] dst = new float[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(0);
                src.get(dst);
            }
        }
    }

    @Test
    public void timeByteBuffer_getInt() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getInt();
            }
        }
    }

    @Test
    public void timeIntBuffer_getIntArray() throws Exception {
        IntBuffer src =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asIntBuffer();
        int[] dst = new int[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(0);
                src.get(dst);
            }
        }
    }

    @Test
    public void timeByteBuffer_getLong() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getLong();
            }
        }
    }

    @Test
    public void timeLongBuffer_getLongArray() throws Exception {
        LongBuffer src =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asLongBuffer();
        long[] dst = new long[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(0);
                src.get(dst);
            }
        }
    }

    @Test
    public void timeByteBuffer_getShort() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getShort();
            }
        }
    }

    @Test
    public void timeShortBuffer_getShortArray() throws Exception {
        ShortBuffer src =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asShortBuffer();
        short[] dst = new short[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(0);
                src.get(dst);
            }
        }
    }

    //
    // poking
    //

    @Test
    public void timeByteBuffer_putByte() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(0);
            for (int i = 0; i < 1024; ++i) {
                src.put((byte) 0);
            }
        }
    }

    @Test
    public void timeByteBuffer_putByteArray() throws Exception {
        ByteBuffer dst = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        byte[] src = new byte[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(mAligned ? 0 : 1);
                dst.put(src);
            }
        }
    }

    @Test
    public void timeByteBuffer_putChar() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putChar(' ');
            }
        }
    }

    @Test
    public void timeCharBuffer_putCharArray() throws Exception {
        CharBuffer dst =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asCharBuffer();
        char[] src = new char[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(0);
                dst.put(src);
            }
        }
    }

    @Test
    public void timeByteBuffer_putDouble() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putDouble(0.0);
            }
        }
    }

    @Test
    public void timeDoubleBuffer_putDoubleArray() throws Exception {
        DoubleBuffer dst =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asDoubleBuffer();
        double[] src = new double[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(0);
                dst.put(src);
            }
        }
    }

    @Test
    public void timeByteBuffer_putFloat() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putFloat(0.0f);
            }
        }
    }

    @Test
    public void timeFloatBuffer_putFloatArray() throws Exception {
        FloatBuffer dst =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asFloatBuffer();
        float[] src = new float[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(0);
                dst.put(src);
            }
        }
    }

    @Test
    public void timeByteBuffer_putInt() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putInt(0);
            }
        }
    }

    @Test
    public void timeIntBuffer_putIntArray() throws Exception {
        IntBuffer dst =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asIntBuffer();
        int[] src = new int[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(0);
                dst.put(src);
            }
        }
    }

    @Test
    public void timeByteBuffer_putLong() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putLong(0L);
            }
        }
    }

    @Test
    public void timeLongBuffer_putLongArray() throws Exception {
        LongBuffer dst =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asLongBuffer();
        long[] src = new long[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(0);
                dst.put(src);
            }
        }
    }

    @Test
    public void timeByteBuffer_putShort() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putShort((short) 0);
            }
        }
    }

    @Test
    public void timeShortBuffer_putShortArray() throws Exception {
        ShortBuffer dst =
                ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType).asShortBuffer();
        short[] src = new short[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(0);
                dst.put(src);
            }
        }
    }

    @Test
    public void time_new_byteArray() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            byte[] bs = new byte[8192];
        }
    }

    @Test
    public void time_ByteBuffer_allocate() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ByteBuffer bs = ByteBuffer.allocate(8192);
        }
    }
}
