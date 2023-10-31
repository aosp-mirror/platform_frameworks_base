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

import static android.companion.CompanionDeviceManager.MESSAGE_ONEWAY_PING;
import static android.companion.CompanionDeviceManager.MESSAGE_REQUEST_PING;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;

import com.android.internal.util.HexDump;

import libcore.util.EmptyArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests that CDM can intake incoming messages in the system data transport and output results.
 *
 * Build/Install/Run: atest CompanionTests:SystemDataTransportTest
 */
public class SystemDataTransportTest extends InstrumentationTestCase {
    private static final String TAG = "SystemDataTransportTest";

    private static final int MESSAGE_INVALID = 0xF00DCAFE;
    private static final int MESSAGE_ONEWAY_INVALID = 0x43434343; // ++++
    private static final int MESSAGE_RESPONSE_INVALID = 0x33333333; // !!!!
    private static final int MESSAGE_REQUEST_INVALID = 0x63636363; // ????

    private static final int MESSAGE_RESPONSE_SUCCESS = 0x33838567; // !SUC
    private static final int MESSAGE_RESPONSE_FAILURE = 0x33706573; // !FAI

    private Context mContext;
    private CompanionDeviceManager mCdm;
    private int mAssociationId;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getInstrumentation().getTargetContext();
        mCdm = mContext.getSystemService(CompanionDeviceManager.class);
        mAssociationId = createAssociation();
        mCdm.enableSecureTransport(false);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        mCdm.disassociate(mAssociationId);
        mCdm.enableSecureTransport(true);
    }

    public void testPingHandRolled() {
        // NOTE: These packets are explicitly hand-rolled to verify wire format;
        // the remainder of the tests are fine using generated packets

        // MESSAGE_REQUEST_PING with payload "HELLO WORLD!"
        final byte[] input = new byte[] {
                0x63, (byte) 0x80, 0x73, 0x78, // message: MESSAGE_REQUEST_PING
                0x00, 0x00, 0x00, 0x2A, // sequence: 42
                0x00, 0x00, 0x00, 0x0C, // length: 12
                0x48, 0x45, 0x4C, 0x4C, 0x4F, 0x20, 0x57, 0x4F, 0x52, 0x4C, 0x44, 0x21,
        };
        // MESSAGE_RESPONSE_SUCCESS with payload "HELLO WORLD!"
        final byte[] expected = new byte[] {
                0x33, (byte) 0x83, (byte) 0x85, 0x67, // message: MESSAGE_RESPONSE_SUCCESS
                0x00, 0x00, 0x00, 0x2A, // sequence: 42
                0x00, 0x00, 0x00, 0x0C, // length: 12
                0x48, 0x45, 0x4C, 0x4C, 0x4F, 0x20, 0x57, 0x4F, 0x52, 0x4C, 0x44, 0x21,
        };
        assertTransportBehavior(input, expected);
    }

    public void testPingTrickle() {
        final byte[] input = generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 1, TAG);
        final byte[] expected = generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 1, TAG);

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(mAssociationId, new TrickleInputStream(in), out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testPingDelay() {
        final byte[] input = generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 1, TAG);
        final byte[] expected = generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 1, TAG);

        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(mAssociationId, new DelayingInputStream(in, 1000),
                new DelayingOutputStream(out, 1000));

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
    }

    public void testPingGiant() {
        final byte[] blob = new byte[500_000];
        new Random().nextBytes(blob);

        final byte[] input = generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 1, blob);
    }

    public void testMultiplePingPing() {
        final byte[] input = concat(
                generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 1, "red"),
                generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 2, "green"));
        final byte[] expected = concat(
                generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 1, "red"),
                generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 2, "green"));
        assertTransportBehavior(input, expected);
    }

    public void testMultipleInvalidPing() {
        final byte[] input = concat(
                generatePacket(MESSAGE_INVALID, /* sequence */ 1, "red"),
                generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 2, "green"));
        final byte[] expected =
                generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 2, "green");
        assertTransportBehavior(input, expected);
    }

    public void testMultipleInvalidRequestPing() {
        final byte[] input = concat(
                generatePacket(MESSAGE_REQUEST_INVALID, /* sequence */ 1, "red"),
                generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 2, "green"));
        final byte[] expected = concat(
                generatePacket(MESSAGE_RESPONSE_FAILURE, /* sequence */ 1),
                generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 2, "green"));
        assertTransportBehavior(input, expected);
    }

    public void testMultipleInvalidResponsePing() {
        final byte[] input = concat(
                generatePacket(MESSAGE_RESPONSE_INVALID, /* sequence */ 1, "red"),
                generatePacket(MESSAGE_REQUEST_PING, /* sequence */ 2, "green"));
        final byte[] expected =
                generatePacket(MESSAGE_RESPONSE_SUCCESS, /* sequence */ 2, "green");
        assertTransportBehavior(input, expected);
    }

    public void testDoubleAttach() {
        // Connect an empty connection that is stalled out
        final InputStream in = new EmptyInputStream();
        final OutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(mAssociationId, in, out);
        SystemClock.sleep(1000);

        // Attach a second transport that has some packets; it should disconnect
        // the first transport and start replying on the second one
        testPingHandRolled();
    }

    public void testInvalidOnewayMessages() throws InterruptedException {
        // Add a callback
        final CountDownLatch received = new CountDownLatch(1);
        mCdm.addOnMessageReceivedListener(Runnable::run, MESSAGE_ONEWAY_INVALID,
                (id, data) -> received.countDown());

        final byte[] input = generatePacket(MESSAGE_ONEWAY_INVALID, /* sequence */ 1);
        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(mAssociationId, in, out);

        // Assert that a one-way message was ignored (does not trigger a callback)
        assertFalse(received.await(5, TimeUnit.SECONDS));

        // There should not be a response to one-way messages
        assertEquals(0, out.toByteArray().length);
    }


    public void testOnewayMessages() throws InterruptedException {
        // Add a callback
        final CountDownLatch received = new CountDownLatch(1);
        mCdm.addOnMessageReceivedListener(Runnable::run, MESSAGE_ONEWAY_PING,
                (id, data) -> received.countDown());

        final byte[] input = generatePacket(MESSAGE_ONEWAY_PING, /* sequence */ 1);
        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(mAssociationId, in, out);

        // Assert that a one-way message was received
        assertTrue(received.await(1, TimeUnit.SECONDS));

        // There should not be a response to one-way messages
        assertEquals(0, out.toByteArray().length);
    }

    public static byte[] concat(byte[]... blobs) {
        int length = 0;
        for (byte[] blob : blobs) {
            length += blob.length;
        }
        final ByteBuffer buf = ByteBuffer.allocate(length);
        for (byte[] blob : blobs) {
            buf.put(blob);
        }
        return buf.array();
    }

    public static byte[] generatePacket(int message, int sequence) {
        return generatePacket(message, sequence, EmptyArray.BYTE);
    }

    public static byte[] generatePacket(int message, int sequence, String data) {
        return generatePacket(message, sequence, data.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] generatePacket(int message, int sequence, byte[] data) {
        return ByteBuffer.allocate(data.length + 12)
                .putInt(message)
                .putInt(sequence)
                .putInt(data.length)
                .put(data)
                .array();
    }

    private int createAssociation() {
        getInstrumentation().getUiAutomation().executeShellCommand("cmd companiondevice associate "
                + mContext.getUserId() + " " + mContext.getPackageName() + " AA:BB:CC:DD:EE:FF");
        List<AssociationInfo> infos;
        for (int i = 0; i < 100; i++) {
            infos = mCdm.getMyAssociations();
            if (!infos.isEmpty()) {
                return infos.get(0).getId();
            } else {
                SystemClock.sleep(100);
            }
        }
        throw new AssertionError();
    }

    private void assertTransportBehavior(byte[] input, byte[] expected) {
        final ByteArrayInputStream in = new ByteArrayInputStream(input);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        mCdm.attachSystemDataTransport(mAssociationId, in, out);

        final byte[] actual = waitForByteArray(out, expected.length);
        assertEquals(HexDump.toHexString(expected), HexDump.toHexString(actual));
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
        public int read(byte[] b, int off, int len) throws IOException {
            // Instead of hanging indefinitely, wait a bit and claim that
            // nothing was read, without hitting EOF
            SystemClock.sleep(100);
            return 0;
        }
    }

    private static class DelayingInputStream extends FilterInputStream {
        private final long mDelay;

        DelayingInputStream(InputStream in, long delay) {
            super(in);
            mDelay = delay;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            SystemClock.sleep(mDelay);
            return super.read(b, off, len);
        }
    }

    private static class DelayingOutputStream extends FilterOutputStream {
        private final long mDelay;

        DelayingOutputStream(OutputStream out, long delay) {
            super(out);
            mDelay = delay;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            SystemClock.sleep(mDelay);
            super.write(b, off, len);
        }
    }

    private static class TrickleInputStream extends FilterInputStream {
        TrickleInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, 1);
        }
    }
}
