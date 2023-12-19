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

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class ByteBufferScalarVersusVectorPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {
                        ByteBufferPerfTest.MyByteOrder.BIG,
                        true,
                        ByteBufferPerfTest.MyBufferType.DIRECT
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.LITTLE,
                        true,
                        ByteBufferPerfTest.MyBufferType.DIRECT
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.BIG,
                        false,
                        ByteBufferPerfTest.MyBufferType.DIRECT
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.LITTLE,
                        false,
                        ByteBufferPerfTest.MyBufferType.DIRECT
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.BIG,
                        true,
                        ByteBufferPerfTest.MyBufferType.HEAP
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.LITTLE,
                        true,
                        ByteBufferPerfTest.MyBufferType.HEAP
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.BIG,
                        false,
                        ByteBufferPerfTest.MyBufferType.HEAP
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.LITTLE,
                        false,
                        ByteBufferPerfTest.MyBufferType.HEAP
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.BIG,
                        true,
                        ByteBufferPerfTest.MyBufferType.MAPPED
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.LITTLE,
                        true,
                        ByteBufferPerfTest.MyBufferType.MAPPED
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.BIG,
                        false,
                        ByteBufferPerfTest.MyBufferType.MAPPED
                    },
                    {
                        ByteBufferPerfTest.MyByteOrder.LITTLE,
                        false,
                        ByteBufferPerfTest.MyBufferType.MAPPED
                    }
                });
    }

    @Test
    @Parameters(method = "getData")
    public void timeManualByteBufferCopy(
            ByteBufferPerfTest.MyByteOrder byteOrder,
            boolean aligned,
            ByteBufferPerfTest.MyBufferType bufferType)
            throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        ByteBuffer dst = ByteBufferPerfTest.newBuffer(byteOrder, aligned, bufferType);
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(0);
            dst.position(0);
            for (int i = 0; i < 8192; ++i) {
                dst.put(src.get());
            }
        }
    }

    @Test
    @Parameters({"true", "false"})
    public void timeByteBufferBulkGet(boolean aligned) throws Exception {
        ByteBuffer src = ByteBuffer.allocate(aligned ? 8192 : 8192 + 1);
        byte[] dst = new byte[8192];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            src.get(dst, 0, dst.length);
        }
    }

    @Test
    @Parameters({"true", "false"})
    public void timeDirectByteBufferBulkGet(boolean aligned) throws Exception {
        ByteBuffer src = ByteBuffer.allocateDirect(aligned ? 8192 : 8192 + 1);
        byte[] dst = new byte[8192];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(aligned ? 0 : 1);
            src.get(dst, 0, dst.length);
        }
    }
}
