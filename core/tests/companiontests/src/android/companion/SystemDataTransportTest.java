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

package android.companion;

import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;

import com.android.internal.util.HexDump;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class SystemDataTransportTest extends InstrumentationTestCase {
    private static final String TAG = "SystemDataTransportTest";

    private static final int COMMAND_INVALID = 0xF00DCAFE;
    private static final int COMMAND_PING_V0 = 0x50490000;
    private static final int COMMAND_PONG_V0 = 0x504F0000;

    private CompanionDeviceManager mCdm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mCdm = getInstrumentation().getTargetContext()
                .getSystemService(CompanionDeviceManager.class);
    }

    public void testPingHandRolled() {
        // NOTE: These packets are explicitly hand-rolled to verify wire format;
        // the remainder of the tests are fine using generated packets

        // PING v0 with payload "HELLO WORLD!"
        final byte[] input = new byte[] {
                0x50, 0x49, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x0C,
                0x48, 0x45, 0x4C, 0x4C, 0x4F, 0x20, 0x57, 0x4F, 0x52, 0x4C, 0x44, 0x21,
        };
        // PONG v0 with payload "HELLO WORLD!"
        final byte[] expected = new byte[] {
                0x50, 0x4F, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x0C,
                0x48, 0x45, 0x4C, 0x4C, 0x4F, 0x20, 0x57, 0x4F, 0x52, 0x4C, 0x44, 0x21,
        };

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, in, out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testPingTrickle() {
        final byte[] input = generatePacket(COMMAND_PING_V0, TAG);
        final byte[] expected = generatePacket(COMMAND_PONG_V0, TAG);

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, new TrickleInputStream(in), out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testPingDelay() {
        final byte[] input = generatePacket(COMMAND_PING_V0, TAG);
        final byte[] expected = generatePacket(COMMAND_PONG_V0, TAG);

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, new DelayingInputStream(in, 1000),
                new DelayingOutputStream(out, 1000));

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testPingGiant() {
        final byte[] blob = new byte[500_000];
        new Random().nextBytes(blob);

        final byte[] input = generatePacket(COMMAND_PING_V0, blob);
        final byte[] expected = generatePacket(COMMAND_PONG_V0, blob);

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, in, out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testMutiplePingPing() {
        final byte[] input = concat(generatePacket(COMMAND_PING_V0, "red"),
                generatePacket(COMMAND_PING_V0, "green"));
        final byte[] expected = concat(generatePacket(COMMAND_PONG_V0, "red"),
                generatePacket(COMMAND_PONG_V0, "green"));

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, in, out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testMultipleInvalidPing() {
        final byte[] input = concat(generatePacket(COMMAND_INVALID, "red"),
                generatePacket(COMMAND_PING_V0, "green"));
        final byte[] expected = generatePacket(COMMAND_PONG_V0, "green");

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, in, out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testDoubleAttach() {
        // Connect an empty connection that is stalled out
        final InputStream in = new EmptyInputStream();
        final OutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(42, in, out);
        SystemClock.sleep(1000);

        // Attach a second transport that has some packets; it should disconnect
        // the first transport and start replying on the second one
        testPingHandRolled();
    }

    public static byte[] concat(byte[] a, byte[] b) {
        return ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    }

    public static byte[] generatePacket(int command, String data) {
        return generatePacket(command, data.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] generatePacket(int command, byte[] data) {
        return ByteBuffer.allocate(data.length + 8)
                .putInt(command).putInt(data.length).put(data).array();
    }

    private static byte[] waitForByteArray(ByteArrayOutputStream out, int size) {
        int i = 0;
        while (out.size() < size) {
            SystemClock.sleep(100);
            if (i++ % 10 == 0) {
                Log.w(TAG, "Waiting for data...");
            }
            if (i > 100) {
                fail();
            }
        }
        return out.toByteArray();
    }

    private static class EmptyInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {
            // Instead of hanging indefinitely, wait a bit and claim that
            // nothing was read, without hitting EOF
            SystemClock.sleep(100);
            return 0;
        }
    }

    private static class DelayingInputStream extends FilterInputStream {
        private final long mDelay;

        public DelayingInputStream(InputStream in, long delay) {
            super(in);
            mDelay = delay;
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {
            SystemClock.sleep(mDelay);
            return super.read(b, off, len);
        }
    }

    private static class DelayingOutputStream extends FilterOutputStream {
        private final long mDelay;

        public DelayingOutputStream(OutputStream out, long delay) {
            super(out);
            mDelay = delay;
        }

        @Override
        public void write(byte b[], int off, int len) throws IOException {
            SystemClock.sleep(mDelay);
            super.write(b, off, len);
        }
    }

    private static class TrickleInputStream extends FilterInputStream {
        public TrickleInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte b[], int off, int len) throws IOException {
            return super.read(b, off, 1);
        }
    }
}
