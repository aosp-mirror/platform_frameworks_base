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

import static org.junit.Assert.assertEquals;

import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.midi.MidiFramer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class BluetoothMidiEncoderTest {

    private static final String TAG = "BluetoothMidiEncoderTest";
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final long NANOS_PER_MSEC = 1000000L;

    static class AccumulatingPacketReceiver implements PacketEncoder.PacketReceiver {
        ArrayList<byte[]> mBuffers = new ArrayList<byte[]>();

        public void writePacket(byte[] buffer, int count) {
            byte[] actualRow = new byte[count];
            Log.d(TAG, "writePacket() passed " + MidiFramer.formatMidiData(buffer, 0, count));
            System.arraycopy(buffer, 0, actualRow, 0, count);
            mBuffers.add(actualRow);
        }

        byte[][] getBuffers() {
            return mBuffers.toArray(new byte[mBuffers.size()][]);
        }
    }

    static class EncoderChecker {
        AccumulatingPacketReceiver mReceiver;
        BluetoothPacketEncoder mEncoder;

        EncoderChecker() {
            mReceiver = new AccumulatingPacketReceiver();
            final int maxBytes = 20;
            mEncoder = new BluetoothPacketEncoder(mReceiver, maxBytes);
        }

        void send(byte[] data) throws IOException {
            send(data, 0);
        }

        void send(byte[] data, long timestamp) throws IOException {
            Log.d(TAG, "send " + MidiFramer.formatMidiData(data, 0, data.length));
            mEncoder.send(data, 0, data.length, timestamp);
        }

        void compareWithExpected(final byte[][] expected) {
            byte[][] actualRows = mReceiver.getBuffers();
            assertEquals(expected.length, actualRows.length);
            // Compare the gathered rows with the expected rows.
            for (int i = 0; i < expected.length; i++) {
                byte[] expectedRow = expected[i];
                Log.d(TAG, "expectedRow = "
                        + MidiFramer.formatMidiData(expectedRow, 0, expectedRow.length));
                byte[] actualRow = actualRows[i];
                Log.d(TAG, "actualRow   = "
                        + MidiFramer.formatMidiData(actualRow, 0, actualRow.length));
                assertEquals(expectedRow.length, actualRow.length);
                for (int k = 0; k < expectedRow.length; k++) {
                    assertEquals(expectedRow[k], actualRow[k]);
                }
            }
        }

        void writeComplete() {
            mEncoder.writeComplete();
        }

    }

    @Test
    public void testOneNoteOn() throws IOException  {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x90, 0x40, 0x64
                }};
        EncoderChecker checker = new EncoderChecker();
        checker.send(new byte[] {(byte) 0x90, 0x40, 0x64});
        checker.compareWithExpected(encoded);
    }

    @Test
    public void testTwoNoteOnsSameChannel() throws IOException  {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x90, 0x40, 0x64,
                // encoder converts to running status
                0x47, 0x72
                }};
        EncoderChecker checker = new EncoderChecker();
        checker.send(new byte[] {(byte) 0x90, 0x40, 0x64, (byte) 0x90, 0x47, 0x72});
        checker.compareWithExpected(encoded);
    }

    @Test
    public void testTwoNoteOnsTwoChannels() throws IOException  {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x93, 0x40, 0x60,
                // two channels so no running status
                (byte) 0x80, // high bit of timestamp
                (byte) 0x95, 0x47, 0x64
                }};
        EncoderChecker checker = new EncoderChecker();
        checker.send(new byte[] {(byte) 0x93, 0x40, 0x60, (byte) 0x95, 0x47, 0x64});
        checker.compareWithExpected(encoded);
    }

    @Test
    public void testTwoNoteOnsOverTime() throws IOException  {
        final byte[][] encoded = {
                {
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // high bit of timestamp
                (byte) 0x98, 0x45, 0x60
                },
                {
                (byte) 0x80, // high bit of header must be set
                (byte) 0x82, // timestamp advanced by 2 msec
                (byte) 0x90, 0x40, 0x64,
                (byte) 0x84, // timestamp needed because of time delay
                // encoder converts to running status
                0x47, 0x72
                }};
        EncoderChecker checker = new EncoderChecker();
        long timestamp = 0;
        // Send one note. This will cause an immediate packet write
        // because we don't know when the next one will arrive.
        checker.send(new byte[] {(byte) 0x98, 0x45, 0x60}, timestamp);

        // Send two notes. These should accumulate into the
        // same packet because we do not yet have a writeComplete.
        timestamp += 2 * NANOS_PER_MSEC;
        checker.send(new byte[] {(byte) 0x90, 0x40, 0x64}, timestamp);
        timestamp += 2 * NANOS_PER_MSEC;
        checker.send(new byte[] {(byte) 0x90, 0x47, 0x72}, timestamp);
        // Tell the encoder that the first packet has been written to the
        // hardware. So it can flush the two pending notes.
        checker.writeComplete();
        checker.compareWithExpected(encoded);
    }

    @Test
    public void testSysExBasic() throws IOException  {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
                (byte) 0x80, // timestamp
                (byte) 0xF0, 0x7D, // Begin prototyping SysEx
                0x01, 0x02, 0x03, 0x04, 0x05,
                (byte) 0x80, // timestamp
                (byte) 0xF7 // End SysEx
                }};
        EncoderChecker checker = new EncoderChecker();
        checker.send(new byte[] {(byte) 0xF0, 0x7D, // experimental SysEx
                0x01, 0x02, 0x03, 0x04, 0x05, (byte) 0xF7});
        checker.compareWithExpected(encoded);
    }

    @Test
    public void testSysExTwoPackets() throws IOException  {
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
        EncoderChecker checker = new EncoderChecker();
        // Send in two messages.
        checker.send(new byte[] {(byte) 0xF0, 0x7D, // experimental SysEx
                0x01, 0x02});
        checker.send(new byte[] {0x03, 0x04, 0x05, (byte) 0xF7});
        // Tell the encoder that the first packet has been written to the
        // hardware. So it can flush the remaining data.
        checker.writeComplete();
        checker.compareWithExpected(encoded);
    }

    @Test
    public void testSysExThreePackets() throws IOException  {
        final byte[][] encoded = {{
                (byte) 0x80, // high bit of header must be set
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
        EncoderChecker checker = new EncoderChecker();
        // Send in three messages.
        checker.send(new byte[] {(byte) 0xF0, 0x7D, // experimental SysEx
                0x01, 0x02});
        checker.send(new byte[] {0x03, 0x04, 0x05});
        checker.writeComplete();
        checker.send(new byte[] {0x06, 0x07, 0x08, (byte) 0xF7});
        checker.writeComplete();
        checker.compareWithExpected(encoded);
    }
}
