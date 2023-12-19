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

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class ByteBufferPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public enum MyByteOrder {
        BIG(ByteOrder.BIG_ENDIAN),
        LITTLE(ByteOrder.LITTLE_ENDIAN);
        final ByteOrder byteOrder;

        MyByteOrder(ByteOrder byteOrder) {
            this.byteOrder = byteOrder;
        }
    }

    public static Collection<Object[]> getData() {
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

    enum MyBufferType {
        DIRECT,
        HEAP,
        MAPPED;
    }

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
        result.order(byteOrder.byteOrder);
        result.position(aligned ? 0 : 1);
        return result;
    }

    //
    // peeking
    //

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_getByte(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.get();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_getByteArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        byte[] dst = new byte[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                src.position(aligned ? 0 : 1);
                src.get(dst);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_getByte_indexed(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.get(i);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_getChar(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getChar();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeCharBuffer_getCharArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        CharBuffer src =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asCharBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_getChar_indexed(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getChar(i * 2);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_getDouble(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getDouble();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeDoubleBuffer_getDoubleArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        DoubleBuffer src =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asDoubleBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_getFloat(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getFloat();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeFloatBuffer_getFloatArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        FloatBuffer src =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asFloatBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_getInt(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getInt();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeIntBuffer_getIntArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        IntBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asIntBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_getLong(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getLong();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeLongBuffer_getLongArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        LongBuffer src =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asLongBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_getShort(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.getShort();
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeShortBuffer_getShortArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ShortBuffer src =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asShortBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_putByte(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(0);
            for (int i = 0; i < 1024; ++i) {
                src.put((byte) 0);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_putByteArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer dst = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        byte[] src = new byte[1024];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            for (int i = 0; i < 1024; ++i) {
                dst.position(aligned ? 0 : 1);
                dst.put(src);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeByteBuffer_putChar(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putChar(' ');
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeCharBuffer_putCharArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        CharBuffer dst =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asCharBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_putDouble(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putDouble(0.0);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeDoubleBuffer_putDoubleArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        DoubleBuffer dst =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asDoubleBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_putFloat(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putFloat(0.0f);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeFloatBuffer_putFloatArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        FloatBuffer dst =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asFloatBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_putInt(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putInt(0);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeIntBuffer_putIntArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        IntBuffer dst = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asIntBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_putLong(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putLong(0L);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeLongBuffer_putLongArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        LongBuffer dst =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asLongBuffer();
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
    @Parameters(method = "getData")
    public void timeByteBuffer_putShort(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            for (int i = 0; i < 1024; ++i) {
                src.putShort((short) 0);
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeShortBuffer_putShortArray(
            MyByteOrder byteOrder, boolean aligned, MyBufferType bufferType) throws Exception {
        ShortBuffer dst =
                ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType).asShortBuffer();
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
    @Parameters(method = "getData")
    public void time_new_byteArray() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            byte[] bs = new byte[8192];
        }
    }

    @Test
    @Parameters(method = "getData")
    public void time_ByteBuffer_allocate() throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            ByteBuffer bs = ByteBuffer.allocate(8192);
        }
    }
}
