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

package com.android.bluetoothmidiservice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.midi.MidiFramer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * End to end testing of the Bluetooth Encoder and Decoder
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class BluetoothMidiCodecTest {

    private static final String TAG = "BluetoothMidiCodecTest";
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final long NANOS_PER_MSEC = 1000000L;

    static class EncoderDecoderChecker implements PacketEncoder.PacketReceiver {
        BluetoothPacketEncoder mEncoder;
        BluetoothPacketDecoder mDecoder;
        AccumulatingMidiReceiver mReceiver;
        MidiFramer mFramer;
        AccumulatingMidiReceiver mBypassReceiver;
        MidiFramer mBypassFramer;
        int mMaxPacketsPerConnection;
        int mConnectionIntervalMillis;
        BlockingQueue<byte[]> mPacketQueue;
        ScheduledExecutorService mScheduler;

        EncoderDecoderChecker() {
            this(2, 15, 20);
        }

        EncoderDecoderChecker(
                int maxPacketsPerConnection,
                int connectionIntervalMillis,
                int maxBytesPerPacket) {
            mMaxPacketsPerConnection = maxPacketsPerConnection;
            mConnectionIntervalMillis = connectionIntervalMillis;
            mEncoder = new BluetoothPacketEncoder(this, maxBytesPerPacket);
            mDecoder = new BluetoothPacketDecoder(maxBytesPerPacket);
            mReceiver = new AccumulatingMidiReceiver();
            mFramer = new MidiFramer(mReceiver);
            mBypassReceiver = new AccumulatingMidiReceiver();
            mBypassFramer = new MidiFramer(mBypassReceiver);
            mScheduler = Executors.newSingleThreadScheduledExecutor();
            mPacketQueue = new LinkedBlockingDeque<>(maxPacketsPerConnection);
        }

        void processQueue() throws InterruptedException {
            for (int i = 0; i < mMaxPacketsPerConnection; i++) {
                byte[] packet = mPacketQueue.poll(0, TimeUnit.SECONDS);
                if (packet == null) break;
                Log.d(TAG, "decode " + MidiFramer.formatMidiData(packet, 0, packet.length));
                mDecoder.decodePacket(packet, mFramer);
            }
            Log.d(TAG, "call writeComplete()");
            mEncoder.writeComplete();
        }

        public void start() {
            mScheduler.scheduleAtFixedRate(
                    () -> {
                        Log.d(TAG, "run scheduled task");
                        try {
                            processQueue();
                        } catch (Exception e) {
                            assertEquals(null, e);
                        }
                    },
                    mConnectionIntervalMillis, // initial delay
                    mConnectionIntervalMillis, // period
                    TimeUnit.MILLISECONDS);
        }

        public void stop() {
            // TODO wait for queue to empty
            mScheduler.shutdown();
        }

        // TODO Should this block?
        // Store the packets and then write them from a periodic task.
        @Override
        public void writePacket(byte[] buffer, int count) {
            Log.d(TAG, "writePacket() passed " + MidiFramer.formatMidiData(buffer, 0, count));
            byte[] packet = new byte[count];
            System.arraycopy(buffer, 0, packet, 0, count);
            try {
                mPacketQueue.put(packet);
            } catch (Exception e) {
                assertEquals(null, e);
            }
            Log.d(TAG, "writePacket() returns");
        }

        void test(final byte[][] midi)
                throws IOException, InterruptedException {
            test(midi, 2);
        }

        // Send the MIDI messages through the encoder,
        // then through the decoder,
        // then gather the resulting MIDI and compare the results.
        void test(final byte[][] midi, int intervalMillis)
                throws IOException, InterruptedException {
            start();
            long timestamp = 0;
            // Send all of the MIDI messages and gather the response.
            for (int i = 0; i < midi.length; i++) {
                byte[] outMessage = midi[i];
                Log.d(TAG, "outMessage "
                        + MidiFramer.formatMidiData(outMessage, 0, outMessage.length));
                mEncoder.send(outMessage, 0, outMessage.length, timestamp);
                timestamp += 2 * NANOS_PER_MSEC;
                // Also send a copy through a MidiFramer to align the messages.
                mBypassFramer.send(outMessage, 0, outMessage.length, timestamp);
            }
            Thread.sleep(200);
            stop();

            // Compare the gathered rows with the expected rows.
            byte[][] expectedMessages = mBypassReceiver.getBuffers();
            byte[][] inMessages = mReceiver.getBuffers();
            Log.d(TAG, "expectedMessage length = " + expectedMessages.length
                    + ", inMessages length = " + inMessages.length);
            assertEquals(expectedMessages.length, inMessages.length);
            Long[] actualTimestamps = mReceiver.getTimestamps();
            long previousTime = 0;
            for (int i = 0; i < expectedMessages.length; i++) {
                byte[] expectedMessage = expectedMessages[i];
                Log.d(TAG, "expectedMessage = "
                        + MidiFramer.formatMidiData(expectedMessage,
                        0, expectedMessage.length));
                byte[] actualMessage = inMessages[i];
                Log.d(TAG, "actualMessage   = "
                        + MidiFramer.formatMidiData(actualMessage, 0, actualMessage.length));
                assertArrayEquals(expectedMessage, actualMessage);
                // Are the timestamps monotonic?
                long currentTime =  actualTimestamps[i];
                Log.d(TAG, "previousTime   = " + previousTime
                        + ", currentTime   = " + currentTime);
                assertTrue(currentTime >= previousTime);
                previousTime = currentTime;
            }
        }
    }

    @Test
    public void testOneNoteOn() throws IOException, InterruptedException {
        final byte[][] midi = {
                {(byte) 0x90, 0x40, 0x64}
                };
        EncoderDecoderChecker checker = new EncoderDecoderChecker();
        checker.test(midi);
    }

    @Test
    public void testTwoNoteOnSameTime() throws IOException, InterruptedException {
        final byte[][] midi = {
                {(byte) 0x90, 0x40, 0x64, (byte) 0x90, 0x47, 0x70}
                };
        EncoderDecoderChecker checker = new EncoderDecoderChecker();
        checker.test(midi);
    }

    @Test
    public void testTwoNoteOnStaggered() throws IOException, InterruptedException {
        final byte[][] midi = {
                {(byte) 0x90, 0x40, 0x64},
                {(byte) 0x90, 0x47, 0x70}
                };
        EncoderDecoderChecker checker = new EncoderDecoderChecker();
        checker.test(midi);
    }

    public void checkNoteBurst(int maxPacketsPerConnection,
            int period,
            int maxBytesPerPacket) throws IOException, InterruptedException {
        final int numNotes = 100;
        final byte[][] midi = new byte[numNotes][];
        int channel = 2;
        for (int i = 0; i < numNotes; i++) {
            byte[] message = {(byte) (0x90 + channel), (byte) (i + 1), 0x64};
            midi[i] = message;
            channel ^= 1;
        }
        EncoderDecoderChecker checker = new EncoderDecoderChecker(
                maxPacketsPerConnection, 15, maxBytesPerPacket);
        checker.test(midi, period);
    }

    @Test
    public void testNoteBurstM1P6() throws IOException, InterruptedException {
        checkNoteBurst(1, 6, 20);
    }
    @Test
    public void testNoteBurstM1P2() throws IOException, InterruptedException {
        checkNoteBurst(1, 2, 20);
    }
    @Test
    public void testNoteBurstM2P6() throws IOException, InterruptedException {
        checkNoteBurst(2, 6, 20);
    }
    @Test
    public void testNoteBurstM2P2() throws IOException, InterruptedException {
        checkNoteBurst(2, 2, 20);
    }
    @Test
    public void testNoteBurstM2P0() throws IOException, InterruptedException {
        checkNoteBurst(2, 0, 20);
    }
    @Test
    public void testNoteBurstM2P6B21() throws IOException, InterruptedException {
        checkNoteBurst(2, 6, 21);
    }
    @Test
    public void testNoteBurstM2P2B21() throws IOException, InterruptedException {
        checkNoteBurst(2, 2, 21);
    }
    @Test
    public void testNoteBurstM2P0B21() throws IOException, InterruptedException {
        checkNoteBurst(2, 0, 21);
    }
}
