/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.usb;

import static org.junit.Assert.assertEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Random;

/**
 * Unit tests for com.android.server.usb.UsbMidiPacketConverter.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UsbMidiPacketConverterTest {
    private byte[] generateRandomByteStream(Random rnd, int size) {
        byte[] output = new byte[size];
        rnd.nextBytes(output);
        return output;
    }

    private void compareByteArrays(byte[] expectedArray, byte[] outputArray) {
        assertEquals(expectedArray.length, outputArray.length);
        for (int i = 0; i < outputArray.length; i++) {
            assertEquals(expectedArray[i], outputArray[i]);
        }
    }

    @Test
    public void testDecoderSinglePacket() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createDecoders(2);
        byte[] input = new byte[] {0x19 /* Cable 1 Note-On */, (byte) 0x91, 0x33, 0x66};
        byte[] expectedOutputCable0 = new byte[] {};
        byte[] expectedOutputCable1 = new byte[] {(byte) 0x91, 0x33, 0x66};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        byte[] actualOutputCable1 = usbMidiPacketConverter.pullDecodedMidiPackets(1);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
        compareByteArrays(expectedOutputCable1, actualOutputCable1);
    }

    @Test
    public void testDecoderMultiplePackets() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createDecoders(4);
        byte[] input = new byte[] {
                0x1B /* Cable 1 Control Change */, (byte) 0xB4, 0x55, 0x6E,
                0x35 /* Cable 3 Single byte SysEx */, (byte) 0xF8, 0x00, 0x00,
                0x02 /* Cable 0 Two byte System Common */, (byte) 0xF3, 0x12, 0x00};
        byte[] expectedOutputCable0 = new byte[] {(byte) 0xF3, 0x12};
        byte[] expectedOutputCable1 = new byte[] {(byte) 0xB4, 0x55, 0x6E};
        byte[] expectedOutputCable2 = new byte[] {};
        byte[] expectedOutputCable3 = new byte[] {(byte) 0xF8};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        byte[] actualOutputCable1 = usbMidiPacketConverter.pullDecodedMidiPackets(1);
        byte[] actualOutputCable2 = usbMidiPacketConverter.pullDecodedMidiPackets(2);
        byte[] actualOutputCable3 = usbMidiPacketConverter.pullDecodedMidiPackets(3);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
        compareByteArrays(expectedOutputCable1, actualOutputCable1);
        compareByteArrays(expectedOutputCable2, actualOutputCable2);
        compareByteArrays(expectedOutputCable3, actualOutputCable3);
    }

    @Test
    public void testDecoderSysExEndFirstByte() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createDecoders(2);
        byte[] input = new byte[] {
                0x14 /* Cable 1 SysEx Start */, (byte) 0xF0, 0x00, 0x01,
                0x15 /* Cable 1 Single byte SysEx End */, (byte) 0xF7, 0x00, 0x00};
        byte[] expectedOutputCable0 = new byte[] {};
        byte[] expectedOutputCable1 = new byte[] {
                (byte) 0xF0, 0x00, 0x01,
                (byte) 0xF7};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        byte[] actualOutputCable1 = usbMidiPacketConverter.pullDecodedMidiPackets(1);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
        compareByteArrays(expectedOutputCable1, actualOutputCable1);
    }

    @Test
    public void testDecoderSysExEndSecondByte() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createDecoders(1);
        byte[] input = new byte[] {
                0x04 /* Cable 0 SysEx Start */, (byte) 0xF0, 0x00, 0x01,
                0x06 /* Cable 0 Two byte SysEx End */, 0x02, (byte) 0xF7, 0x00};
        byte[] expectedOutputCable0 = new byte[] {
                (byte) 0xF0, 0x00, 0x01,
                0x02, (byte) 0xF7};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
    }

    @Test
    public void testDecoderSysExEndThirdByte() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        byte[] input = new byte[] {
                0x04 /* Cable 0 SysEx Start */, (byte) 0xF0, 0x00, 0x01,
                0x07 /* Cable 0 Three byte SysEx End */, 0x02, 0x03, (byte) 0xF7};
        usbMidiPacketConverter.createDecoders(1);
        byte[] expectedOutputCable0 = new byte[] {
                (byte) 0xF0, 0x00, 0x01,
                0x02, 0x03, (byte) 0xF7};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
    }

    @Test
    public void testDecoderSysExStartEnd() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        byte[] input = new byte[] {
                0x06 /* Cable 0 Two byte SysEx End */, (byte) 0xF0, (byte) 0xF7, 0x00};
        usbMidiPacketConverter.createDecoders(1);
        byte[] expectedOutputCable0 = new byte[] {
                (byte) 0xF0, (byte) 0xF7};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
    }

    @Test
    public void testDecoderSysExStartByteEnd() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        byte[] input = new byte[] {
                0x07 /* Cable 0 Three byte SysEx End */, (byte) 0xF0, 0x44, (byte) 0xF7};
        usbMidiPacketConverter.createDecoders(1);
        byte[] expectedOutputCable0 = new byte[] {
                (byte) 0xF0, 0x44, (byte) 0xF7};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
    }

    @Test
    public void testDecoderDefaultToFirstCable() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        byte[] input = new byte[] {0x49 /* Cable 4 Note-On */, (byte) 0x91, 0x22, 0x33};
        usbMidiPacketConverter.createDecoders(1);
        byte[] expectedOutputCable0 = new byte[] {
                (byte) 0x91, 0x22, 0x33};
        usbMidiPacketConverter.decodeMidiPackets(input, input.length);
        byte[] actualOutputCable0 = usbMidiPacketConverter.pullDecodedMidiPackets(0);
        compareByteArrays(expectedOutputCable0, actualOutputCable0);
    }

    @Test
    public void testDecoderLargePacketDoesNotCrash() {
        for (long seed = 1001; seed < 5000; seed += 777) {
            UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
            usbMidiPacketConverter.createDecoders(3);
            Random rnd = new Random(seed);
            byte[] input = generateRandomByteStream(rnd, 1003 /* arbitrary large size */);
            usbMidiPacketConverter.decodeMidiPackets(input, input.length);
            usbMidiPacketConverter.pullDecodedMidiPackets(0);
            usbMidiPacketConverter.pullDecodedMidiPackets(1);
            usbMidiPacketConverter.pullDecodedMidiPackets(2);
        }
    }

    @Test
    public void testEncoderBasic() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);
        byte[] input = new byte[] {(byte) 0x91 /* Note-On */, 0x33, 0x66};
        byte[] expectedOutput = new byte[] {
                0x09 /* Cable 0 Note-On */, (byte) 0x91, 0x33, 0x66};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderMultiplePackets() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(3);
        byte[] inputCable2 = new byte[] {
                (byte) 0xB4 /* Control Change */, 0x55, 0x6E};
        byte[] inputCable1 = new byte[] {
                (byte) 0xF8 /* Timing Clock (Single Byte) */,
                (byte) 0xF3 /* Song Select (Two Bytes) */, 0x12};
        byte[] expectedOutput = new byte[] {
            0x2B /* Cable 2 Control Change */, (byte) 0xB4, 0x55, 0x6E,
            0x15 /* Cable 1 Timing Clock */, (byte) 0xF8, 0x00, 0x00,
            0x12 /* Cable 1 Two Byte System Common */, (byte) 0xF3, 0x12, 0x00};
        usbMidiPacketConverter.encodeMidiPackets(inputCable2, inputCable2.length, 2);
        usbMidiPacketConverter.encodeMidiPackets(inputCable1, inputCable1.length, 1);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderWeavePackets() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(2);
        byte[] inputCable1Msg1 = new byte[] {
                (byte) 0x93 /* Note-On */, 0x23, 0x43};
        byte[] inputCable0Msg = new byte[] {
                (byte) 0xB4 /* Control Change */, 0x65, 0x26};
        byte[] inputCable1Msg2 = new byte[] {
                (byte) 0xA4 /* Poly-KeyPress */, 0x52, 0x76};
        byte[] expectedOutput = new byte[] {
                0x19 /* Cable 1 Note-On */, (byte) 0x93, 0x23, 0x43,
                0x0B /* Cable 0 Control Change */, (byte) 0xB4, 0x65, 0x26,
                0x1A /* Cable 1 Poly-KeyPress */, (byte) 0xA4, 0x52, 0x76};
        usbMidiPacketConverter.encodeMidiPackets(inputCable1Msg1, inputCable1Msg1.length, 1);
        usbMidiPacketConverter.encodeMidiPackets(inputCable0Msg, inputCable0Msg.length, 0);
        usbMidiPacketConverter.encodeMidiPackets(inputCable1Msg2, inputCable1Msg2.length, 1);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderSysExEndFirstByte() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);
        byte[] input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, 0x00, 0x01,
                (byte) 0xF7 /* SysEx End */};
        byte[] expectedOutput = new byte[] {
                0x04 /* Cable 0 Three Byte SysEx Start */, (byte) 0xF0, 0x00, 0x01,
                0x05 /* Cable 0 One Byte SysEx End */, (byte) 0xF7, 0x00, 0x00};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderSysExEndSecondByte() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);
        byte[] input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, 0x00, 0x01,
                0x02, (byte) 0xF7 /* SysEx End */};
        byte[] expectedOutput = new byte[] {
                0x04 /* Cable 0 Three Byte SysEx Start */, (byte) 0xF0, 0x00, 0x01,
                0x06 /* Cable 0 Two Byte SysEx End */, 0x02, (byte) 0xF7, 0x00};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderSysExEndThirdByte() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);
        byte[] input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, 0x00, 0x01,
                0x02, 0x03, (byte) 0xF7 /* SysEx End */};
        byte[] expectedOutput = new byte[] {
                0x04 /* Cable 0 Three Byte SysEx Start */, (byte) 0xF0, 0x00, 0x01,
                0x07 /* Cable 0 Three Byte SysEx End */, 0x02, 0x03, (byte) 0xF7};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderSysExStartEnd() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);
        byte[] input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, (byte) 0xF7 /* SysEx End */};
        byte[] expectedOutput = new byte[] {
                0x06 /* Cable 0 Two Byte SysEx End */, (byte) 0xF0, (byte) 0xF7, 0x00};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderSysExStartByteEnd() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);
        byte[] input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, 0x44, (byte) 0xF7 /* SysEx End */};
        byte[] expectedOutput = new byte[] {
                0x07 /* Cable 0 Three Byte SysEx End */, (byte) 0xF0, 0x44, (byte) 0xF7};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderMultiplePulls() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(1);

        byte[] input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, 0x44, 0x55,
                0x66, 0x77}; // 0x66 and 0x77 will not be pulled the first time
        byte[] expectedOutput = new byte[] {
                0x04 /* SysEx Start */, (byte) 0xF0, 0x44, 0x55};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);

        input = new byte[] {
                0x11, // Combined with 0x66 and 0x77 above
                0x22, (byte) 0xF7 /* SysEx End */};
        expectedOutput = new byte[] {
                0x04 /* Cable 0 SysEx Continue */, 0x66, 0x77, 0x11,
                0x06 /* Cable 0 Two Byte SysEx End */, 0x22, (byte) 0xF7, 0x00};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);

        input = new byte[] {
                (byte) 0xF0 /* SysEx Start */, (byte) 0xF7 /* SysEx End */};
        expectedOutput = new byte[] {
                0x06 /* Cable 0 Two Byte SysEx End */, (byte) 0xF0, (byte) 0xF7, 0x00};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 0);
        output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderDefaultToFirstCable() {
        UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
        usbMidiPacketConverter.createEncoders(2);
        byte[] input = new byte[] {(byte) 0x91 /* Note-On */, 0x22, 0x33};
        byte[] expectedOutput = new byte[] {
                0x09 /* Cable 0 Note-On */, (byte) 0x91, 0x22, 0x33};
        usbMidiPacketConverter.encodeMidiPackets(input, input.length, 4);
        byte[] output = usbMidiPacketConverter.pullEncodedMidiPackets();
        compareByteArrays(expectedOutput, output);
    }

    @Test
    public void testEncoderLargePacketDoesNotCrash() {
        for (long seed = 234; seed < 4000; seed += 666) {
            Random rnd = new Random(seed);
            UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
            usbMidiPacketConverter.createEncoders(4);
            for (int cableNumber = 0; cableNumber < 4; cableNumber++) {
                byte[] input = generateRandomByteStream(rnd, 1003 /* arbitrary large size */);
                usbMidiPacketConverter.encodeMidiPackets(input, input.length, cableNumber);
            }
            usbMidiPacketConverter.pullEncodedMidiPackets();
        }
    }

    @Test
    public void testEncodeDecode() {
        final int bufferSize = 30;
        final int numCables = 16;
        final int bytesToEncodePerEncoding = 10;
        byte[][] rawMidi = new byte[numCables][bufferSize];
        for (long seed = 45; seed < 3000; seed += 300) {
            Random rnd = new Random(seed);
            for (int cableNumber = 0; cableNumber < numCables; cableNumber++) {
                rawMidi[cableNumber] =  generateRandomByteStream(rnd, bufferSize);

                // Change the last byte to SysEx End.
                // This way the encoder is guaranteed to flush all packets.
                rawMidi[cableNumber][bufferSize - 1] = (byte) 0xF7;
            }
            UsbMidiPacketConverter usbMidiPacketConverter = new UsbMidiPacketConverter();
            usbMidiPacketConverter.createEncoders(numCables);
            // Encode packets and interweave them
            for (int startByte = 0; startByte < bufferSize;
                    startByte += bytesToEncodePerEncoding) {
                for (int cableNumber = 0; cableNumber < numCables; cableNumber++) {
                    byte[] bytesToEncode = Arrays.copyOfRange(rawMidi[cableNumber], startByte,
                            startByte + bytesToEncodePerEncoding);
                    usbMidiPacketConverter.encodeMidiPackets(bytesToEncode, bytesToEncode.length,
                            cableNumber);
                }
            }
            byte[] usbMidi = usbMidiPacketConverter.pullEncodedMidiPackets();

            usbMidiPacketConverter.createDecoders(numCables);

            // Now decode the MIDI packets to check if they are the same as the original
            usbMidiPacketConverter.decodeMidiPackets(usbMidi, usbMidi.length);
            for (int cableNumber = 0; cableNumber < numCables; cableNumber++) {
                byte[] decodedRawMidi = usbMidiPacketConverter.pullDecodedMidiPackets(cableNumber);
                compareByteArrays(rawMidi[cableNumber], decodedRawMidi);
            }
        }
    }
}
