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

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public class MessageDigestPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {Algorithm.MD5},
                    {Algorithm.SHA1},
                    {Algorithm.SHA256},
                    {Algorithm.SHA384},
                    {Algorithm.SHA512}
                });
    }

    public String mProvider = "AndroidOpenSSL";

    private static final int DATA_SIZE = 8192;
    private static final byte[] DATA = new byte[DATA_SIZE];

    static {
        for (int i = 0; i < DATA_SIZE; i++) {
            DATA[i] = (byte) i;
        }
    }

    private static final int LARGE_DATA_SIZE = 256 * 1024;
    private static final byte[] LARGE_DATA = new byte[LARGE_DATA_SIZE];

    static {
        for (int i = 0; i < LARGE_DATA_SIZE; i++) {
            LARGE_DATA[i] = (byte) i;
        }
    }

    private static final ByteBuffer SMALL_BUFFER = ByteBuffer.wrap(DATA);
    private static final ByteBuffer SMALL_DIRECT_BUFFER = ByteBuffer.allocateDirect(DATA_SIZE);

    static {
        SMALL_DIRECT_BUFFER.put(DATA);
        SMALL_DIRECT_BUFFER.flip();
    }

    private static final ByteBuffer LARGE_BUFFER = ByteBuffer.wrap(LARGE_DATA);
    private static final ByteBuffer LARGE_DIRECT_BUFFER =
            ByteBuffer.allocateDirect(LARGE_DATA_SIZE);

    static {
        LARGE_DIRECT_BUFFER.put(LARGE_DATA);
        LARGE_DIRECT_BUFFER.flip();
    }

    public enum Algorithm {
        MD5,
        SHA1,
        SHA256,
        SHA384,
        SHA512
    };

    @Test
    @Parameters(method = "getData")
    public void time(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            digest.update(DATA, 0, DATA_SIZE);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeLargeArray(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            digest.update(LARGE_DATA, 0, LARGE_DATA_SIZE);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSmallChunkOfLargeArray(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            digest.update(LARGE_DATA, LARGE_DATA_SIZE / 2, DATA_SIZE);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSmallByteBuffer(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            SMALL_BUFFER.position(0);
            SMALL_BUFFER.limit(SMALL_BUFFER.capacity());
            digest.update(SMALL_BUFFER);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSmallDirectByteBuffer(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            SMALL_DIRECT_BUFFER.position(0);
            SMALL_DIRECT_BUFFER.limit(SMALL_DIRECT_BUFFER.capacity());
            digest.update(SMALL_DIRECT_BUFFER);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeLargeByteBuffer(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            LARGE_BUFFER.position(0);
            LARGE_BUFFER.limit(LARGE_BUFFER.capacity());
            digest.update(LARGE_BUFFER);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeLargeDirectByteBuffer(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            LARGE_DIRECT_BUFFER.position(0);
            LARGE_DIRECT_BUFFER.limit(LARGE_DIRECT_BUFFER.capacity());
            digest.update(LARGE_DIRECT_BUFFER);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSmallChunkOfLargeByteBuffer(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            LARGE_BUFFER.position(LARGE_BUFFER.capacity() / 2);
            LARGE_BUFFER.limit(LARGE_BUFFER.position() + DATA_SIZE);
            digest.update(LARGE_BUFFER);
            digest.digest();
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeSmallChunkOfLargeDirectByteBuffer(Algorithm algorithm) throws Exception {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            MessageDigest digest = MessageDigest.getInstance(algorithm.toString(), mProvider);
            LARGE_DIRECT_BUFFER.position(LARGE_DIRECT_BUFFER.capacity() / 2);
            LARGE_DIRECT_BUFFER.limit(LARGE_DIRECT_BUFFER.position() + DATA_SIZE);
            digest.update(LARGE_DIRECT_BUFFER);
            digest.digest();
        }
    }
}
