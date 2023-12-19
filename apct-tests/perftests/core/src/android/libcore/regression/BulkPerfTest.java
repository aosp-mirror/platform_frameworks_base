/*
 * Copyright (C) 2022 The Android Open Source Project.
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
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class BulkPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {true, MyBufferType.DIRECT, MyBufferType.DIRECT, 4096},
                    {false, MyBufferType.DIRECT, MyBufferType.DIRECT, 4096},
                    {true, MyBufferType.HEAP, MyBufferType.DIRECT, 4096},
                    {false, MyBufferType.HEAP, MyBufferType.DIRECT, 4096},
                    {true, MyBufferType.MAPPED, MyBufferType.DIRECT, 4096},
                    {false, MyBufferType.MAPPED, MyBufferType.DIRECT, 4096},
                    {true, MyBufferType.DIRECT, MyBufferType.HEAP, 4096},
                    {false, MyBufferType.DIRECT, MyBufferType.HEAP, 4096},
                    {true, MyBufferType.HEAP, MyBufferType.HEAP, 4096},
                    {false, MyBufferType.HEAP, MyBufferType.HEAP, 4096},
                    {true, MyBufferType.MAPPED, MyBufferType.HEAP, 4096},
                    {false, MyBufferType.MAPPED, MyBufferType.HEAP, 4096},
                    {true, MyBufferType.DIRECT, MyBufferType.MAPPED, 4096},
                    {false, MyBufferType.DIRECT, MyBufferType.MAPPED, 4096},
                    {true, MyBufferType.HEAP, MyBufferType.MAPPED, 4096},
                    {false, MyBufferType.HEAP, MyBufferType.MAPPED, 4096},
                    {true, MyBufferType.MAPPED, MyBufferType.MAPPED, 4096},
                    {false, MyBufferType.MAPPED, MyBufferType.MAPPED, 4096},
                    {true, MyBufferType.DIRECT, MyBufferType.DIRECT, 1232896},
                    {false, MyBufferType.DIRECT, MyBufferType.DIRECT, 1232896},
                    {true, MyBufferType.HEAP, MyBufferType.DIRECT, 1232896},
                    {false, MyBufferType.HEAP, MyBufferType.DIRECT, 1232896},
                    {true, MyBufferType.MAPPED, MyBufferType.DIRECT, 1232896},
                    {false, MyBufferType.MAPPED, MyBufferType.DIRECT, 1232896},
                    {true, MyBufferType.DIRECT, MyBufferType.HEAP, 1232896},
                    {false, MyBufferType.DIRECT, MyBufferType.HEAP, 1232896},
                    {true, MyBufferType.HEAP, MyBufferType.HEAP, 1232896},
                    {false, MyBufferType.HEAP, MyBufferType.HEAP, 1232896},
                    {true, MyBufferType.MAPPED, MyBufferType.HEAP, 1232896},
                    {false, MyBufferType.MAPPED, MyBufferType.HEAP, 1232896},
                    {true, MyBufferType.DIRECT, MyBufferType.MAPPED, 1232896},
                    {false, MyBufferType.DIRECT, MyBufferType.MAPPED, 1232896},
                    {true, MyBufferType.HEAP, MyBufferType.MAPPED, 1232896},
                    {false, MyBufferType.HEAP, MyBufferType.MAPPED, 1232896},
                    {true, MyBufferType.MAPPED, MyBufferType.MAPPED, 1232896},
                    {false, MyBufferType.MAPPED, MyBufferType.MAPPED, 1232896},
                });
    }

    enum MyBufferType {
        DIRECT,
        HEAP,
        MAPPED
    }

    public static ByteBuffer newBuffer(boolean aligned, MyBufferType bufferType, int bsize)
            throws IOException {
        int size = aligned ? bsize : bsize + 8 + 1;
        ByteBuffer result = null;
        switch (bufferType) {
            case DIRECT:
                result = ByteBuffer.allocateDirect(size);
                break;
            case HEAP:
                result = ByteBuffer.allocate(size);
                break;
            case MAPPED:
                File tmpFile = File.createTempFile("MappedByteBufferTest", ".tmp");
                tmpFile.createNewFile();
                tmpFile.deleteOnExit();
                RandomAccessFile raf = new RandomAccessFile(tmpFile, "rw");
                raf.setLength(size);
                FileChannel fc = raf.getChannel();
                result = fc.map(FileChannel.MapMode.READ_WRITE, 0, fc.size());
                break;
        }
        result.position(aligned ? 0 : 1);
        return result;
    }

    @Test
    @Parameters(method = "getData")
    public void timePut(boolean align, MyBufferType sBuf, MyBufferType dBuf, int size)
            throws Exception {
        ByteBuffer src = BulkPerfTest.newBuffer(align, sBuf, size);
        ByteBuffer data = BulkPerfTest.newBuffer(align, dBuf, size);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(align ? 0 : 1);
            data.position(align ? 0 : 1);
            src.put(data);
        }
    }
}
