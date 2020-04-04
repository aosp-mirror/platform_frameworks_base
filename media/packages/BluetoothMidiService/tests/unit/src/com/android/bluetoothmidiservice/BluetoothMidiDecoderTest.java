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

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BluetoothMidiDecoderTest {

    private static final String TAG = "BluetoothMidiDecoderTest";
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final long NANOS_PER_MSEC = 1000000L;

    static class DecoderChecker {
        AccumulatingMidiReceiver mReceiver;
        BluetoothPacketDecoder mDecoder;

        DecoderChecker() {
            mReceiver = new AccumulatingMidiReceiver();
            final int maxBytes = 20;
            mDecoder = new BluetoothPacketDecoder(maxBytes);
        }

        void compareWithExpected(final byte[][] expectedMessages) {
            byte[][] actualRows = mReceiver.getBuffers();
            Long[] actualTimestamps = mReceiver.getTimestamps();
            long previousTime = 0;
            // Compare the gathered with the expected.
            assertEquals(expectedMessages.length, actualRows.length);
            for (int i = 0; i < expectedMessages.length; i++) {
                byte[] expectedRow = expectedMessages[i];
                Log.d(TAG, "expectedRow = "
                        + MidiFramer.formatMidiData(expectedRow, 0, expectedRow.length));
                byte[] actualRow = actualRows[i];
                Log.d(TAG, "actualRow   = "
                        + MidiFramer.formatMidiData(actualRow, 0, actualRow.length));
                assertArrayEquals(expectedRow, actualRow);
                // Are the timestamps monotonic?
                long currentTime =  actualTimestamps[i];
                Log.d(TAG, "previousTime   = " + previousTime + ", currentTime   = " + currentTime);
                assertTrue(currentTime >= previousTime);
                previousTime = currentTime;
            }
        }

        void decodePacket(byte[] packet) throws IOException {
            mDecoder.decodePacket(packet, mReceiver);
        }

        void decodePackets(byte[][] multiplePackets) throws IOException {
            try {
                for (int i = 0; i < multiplePackets.length; i++) {
                    byte[] packet = multiplePackets[i];
                    mDecoder.decodePacket(packet, mReceiver);
                }
            } catch (Exception e) {
                assertEquals(null, e);
            }
        }

        void test(byte[] encoded, byte[][] decoded) throws IOException {
            decodePacket(encoded);
            compareWithExpected(decoded);
        }

        void test(byte[][] encoded, byte[][] decoded) throws IOException {
            decodePackets(encoded);
            compareWithExpected(decoded);
        }
    }

    @Test
    public void testOneNoteOn() throws IOException {
        final byte[] encoded = {
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x90, 0x40, 0x64
                };
        final byte[][] decoded = {
                {(byte) 0x90, 0x40, 0x64}
                };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testReservedHeaderBit() throws IOException {
        final byte[] encoded = {
                // Decoder should ignore the reserved bit.
                (byte) (0x80 | 0x40), // set RESERVED bit in header!
                (byte) 0x80, // high bit of timestamp
                (byte) 0x90, 0x40, 0x64
                };
        final byte[][] decoded = {
                {(byte) 0x90, 0x40, 0x64}
                };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testTwoNotesOnRunning() throws IOException {
        final byte[] encoded = {
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x90, 0x40, 0x64,
                (byte) 0x85, // timestamp
                (byte) 0x42, 0x70
                };
        final byte[][] decoded = {
                {(byte) 0x90, 0x40, 0x64},
                {(byte) 0x42, 0x70}
                };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testTwoNoteOnsTwoChannels() throws IOException {
        final byte[] encoded = {
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x93, 0x40, 0x60,
                // two channels so no running status
                (byte) 0x80, // high bit of timestamp
                (byte) 0x95, 0x47, 0x64
                };
        final byte[][] decoded = {
                {(byte) 0x93, 0x40, 0x60,  (byte) 0x95, 0x47, 0x64}
                };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testTwoNoteOnsOverTime() throws IOException {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x98, 0x45, 0x60
                },
                {
                (byte) 0x80, // high bit of header must be set
                (byte) 0x82, // timestamp advanced by 2 msec
                (byte) 0x90, 0x40, 0x64,
                (byte) 0x84, // timestamp needed because of time delay
                // encoder uses running status
                0x47, 0x72
                }};
        final byte[][] decoded = {
                {(byte) 0x98, 0x45, 0x60},
                {(byte) 0x90, 0x40, 0x64},
                {(byte) 0x47, 0x72}
                };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testSysExBasic() throws IOException {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // timestamp
                (byte) 0xF0, 0x7D, // Begin prototyping SysEx
                0x01, 0x02, 0x03, 0x04, 0x05,
                (byte) 0x80, // timestamp
                (byte) 0xF7 // End SysEx
                }};
        final byte[][] decoded = {
                {(byte) 0xF0, 0x7D, // experimental SysEx
                0x01, 0x02, 0x03, 0x04, 0x05, (byte) 0xF7}
                };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testSysExTwoPackets() throws IOException {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // timestamp
                (byte) 0xF0, 0x7D, // Begin prototyping SysEx
                0x01, 0x02
                },
                {
                (byte) 0x80, // high bit of header must be set
                0x03, 0x04, 0x05,
                (byte) 0x80, // timestamp
                (byte) 0xF7 // End SysEx
                }};
        final byte[][] decoded = {
            {(byte) 0xF0, 0x7D, 0x01, 0x02}, // experimental SysEx
            {0x03, 0x04, 0x05, (byte) 0xF7}
        };
        new DecoderChecker().test(encoded, decoded);
    }

    @Test
    public void testSysExThreePackets() throws IOException {
        final byte[][] encoded = {
                {(byte) 0x80, // high bit of header must be set
                (byte) 0x80, // timestamp
                (byte) 0xF0, 0x7D, // Begin prototyping SysEx
                0x01, 0x02
                },
                {
                (byte) 0x80, // high bit of header must be set
                0x03, 0x04, 0x05,
                },
                {
                (byte) 0x80, // high bit of header must be set
                0x06, 0x07, 0x08,
                (byte) 0x80, // timestamp
                (byte) 0xF7 // End SysEx
                }};
        final byte[][] decoded = {
                {(byte) 0xF0, 0x7D, 0x01, 0x02}, // experimental SysEx
                {0x03, 0x04, 0x05},
                {0x06, 0x07, 0x08, (byte) 0xF7}
                };
        new DecoderChecker().test(encoded, decoded);
    }

}
