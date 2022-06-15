/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.util;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class FastDataPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private static final int OUTPUT_SIZE = 64000;
    private static final int BUFFER_SIZE = 4096;

    @Test
    public void timeWrite_Upstream() throws IOException {
        final ByteArrayOutputStream os = new ByteArrayOutputStream(OUTPUT_SIZE);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            os.reset();
            final BufferedOutputStream bos = new BufferedOutputStream(os, BUFFER_SIZE);
            final DataOutput out = new DataOutputStream(bos);
            doWrite(out);
            bos.flush();
        }
    }

    @Test
    public void timeWrite_Local() throws IOException {
        final ByteArrayOutputStream os = new ByteArrayOutputStream(OUTPUT_SIZE);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            os.reset();
            final FastDataOutput out = new FastDataOutput(os, BUFFER_SIZE);
            doWrite(out);
            out.flush();
        }
    }

    @Test
    public void timeRead_Upstream() throws Exception {
        final ByteArrayInputStream is = new ByteArrayInputStream(doWrite());
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            is.reset();
            final BufferedInputStream bis = new BufferedInputStream(is, BUFFER_SIZE);
            final DataInput in = new DataInputStream(bis);
            doRead(in);
        }
    }

    @Test
    public void timeRead_Local() throws Exception {
        final ByteArrayInputStream is = new ByteArrayInputStream(doWrite());
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            is.reset();
            final DataInput in = new FastDataInput(is, BUFFER_SIZE);
            doRead(in);
        }
    }

    /**
     * Since each iteration is around 64 bytes, we need to iterate many times to
     * exercise the buffer logic.
     */
    private static final int REPEATS = 1000;

    private static byte[] doWrite() throws IOException {
        final ByteArrayOutputStream os = new ByteArrayOutputStream(OUTPUT_SIZE);
        final DataOutput out = new DataOutputStream(os);
        doWrite(out);
        return os.toByteArray();
    }

    private static void doWrite(DataOutput out) throws IOException {
        for (int i = 0; i < REPEATS; i++) {
            out.writeByte(Byte.MAX_VALUE);
            out.writeShort(Short.MAX_VALUE);
            out.writeInt(Integer.MAX_VALUE);
            out.writeLong(Long.MAX_VALUE);
            out.writeFloat(Float.MAX_VALUE);
            out.writeDouble(Double.MAX_VALUE);
            out.writeUTF("com.example.typical_package_name");
        }
    }

    private static void doRead(DataInput in) throws IOException {
        for (int i = 0; i < REPEATS; i++) {
            in.readByte();
            in.readShort();
            in.readInt();
            in.readLong();
            in.readFloat();
            in.readDouble();
            in.readUTF();
        }
    }
}
