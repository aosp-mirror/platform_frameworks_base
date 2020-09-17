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

import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.midi.MidiFramer;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class MidiFramerTest {

    private static final String TAG = "MidiFramerTest";
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};

    // For testing MidiFramer
    // TODO move MidiFramer tests to their own file
    static class FramerChecker {
        AccumulatingMidiReceiver mReceiver;
        MidiFramer mFramer;

        FramerChecker() {
            mReceiver = new AccumulatingMidiReceiver();
            mFramer = new MidiFramer(mReceiver);
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
                Log.d(TAG, "actualRow = "
                        + MidiFramer.formatMidiData(actualRow, 0, actualRow.length));
                assertArrayEquals(expectedRow, actualRow);
            }
        }

        void send(byte[] data) throws IOException {
            Log.d(TAG, "send " + MidiFramer.formatMidiData(data, 0, data.length));
            mFramer.send(data, 0, data.length, 0);
        }
    }

    @Test
    public void testFramerTwoNoteOns() throws IOException {
        final byte[][] expected = {
                {(byte) 0x90, 0x40, 0x64},
                {(byte) 0x90, 0x47, 0x50}
                };
        FramerChecker checker = new FramerChecker();
        checker.send(new byte[] {(byte) 0x90, 0x40, 0x64, (byte) 0x90, 0x47, 0x50});
        checker.compareWithExpected(expected);
    }

    @Test
    public void testFramerTwoNoteOnsRunning() throws IOException {
        final byte[][] expected = {
                {(byte) 0x90, 0x40, 0x64},
                {(byte) 0x90, 0x47, 0x70}
                };
        FramerChecker checker = new FramerChecker();
        // Two notes with running status
        checker.send(new byte[] {(byte) 0x90, 0x40, 0x64, 0x47, 0x70});
        checker.compareWithExpected(expected);
    }

    @Test
    public void testFramerPreGarbage() throws IOException {
        final byte[][] expected = {
                {(byte) 0x90, 0x40, 0x64},
                {(byte) 0x90, 0x47, 0x70}
                };
        FramerChecker checker = new FramerChecker();
        // Garbage can come before the first status byte if you connect
        // a MIDI cable in the middle of a message.
        checker.send(new byte[] {0x01, 0x02, // garbage bytes
                (byte) 0x90, 0x40, 0x64, 0x47, 0x70});
        checker.compareWithExpected(expected);
    }
}
