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
import android.test.suitebuilder.annotation.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
@LargeTest
public class ByteBufferScalarVersusVectorPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Parameters(name = "mByteOrder={0}, mAligned={1}, mBufferType={2}")
    public static Collection<Object[]> data() {
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

    @Parameterized.Parameter(0)
    public ByteBufferPerfTest.MyByteOrder mByteOrder;

    @Parameterized.Parameter(1)
    public boolean mAligned;

    @Parameterized.Parameter(2)
    public ByteBufferPerfTest.MyBufferType mBufferType;

    @Test
    public void timeManualByteBufferCopy() throws Exception {
        ByteBuffer src = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
        ByteBuffer dst = ByteBufferPerfTest.newBuffer(mByteOrder, mAligned, mBufferType);
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
    public void timeByteBufferBulkGet() throws Exception {
        ByteBuffer src = ByteBuffer.allocate(mAligned ? 8192 : 8192 + 1);
        byte[] dst = new byte[8192];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            src.get(dst, 0, dst.length);
        }
    }

    @Test
    public void timeDirectByteBufferBulkGet() throws Exception {
        ByteBuffer src = ByteBuffer.allocateDirect(mAligned ? 8192 : 8192 + 1);
        byte[] dst = new byte[8192];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            src.position(mAligned ? 0 : 1);
            src.get(dst, 0, dst.length);
        }
    }
}
