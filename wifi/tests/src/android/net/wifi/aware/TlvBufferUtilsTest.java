/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.aware;

import static org.hamcrest.core.IsEqual.equalTo;

import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;

import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit test harness for TlvBufferUtils class.
 */
@SmallTest
public class TlvBufferUtilsTest {
    @Rule
    public ErrorCollector collector = new ErrorCollector();

    /*
     * TlvBufferUtils Tests
     */

    @Test
    public void testTlvBuild() {
        TlvBufferUtils.TlvConstructor tlv11 = new TlvBufferUtils.TlvConstructor(1, 1);
        tlv11.allocate(15);
        tlv11.putByte(0, (byte) 2);
        tlv11.putByteArray(2, new byte[] {
                0, 1, 2 });

        collector.checkThat("tlv11-correct-construction",
                tlv11.getArray(), equalTo(new byte[]{0, 1, 2, 2, 3, 0, 1, 2}));

        TlvBufferUtils.TlvConstructor tlv01 = new TlvBufferUtils.TlvConstructor(0, 1);
        tlv01.allocate(15);
        tlv01.putByte(0, (byte) 2);
        tlv01.putByteArray(2, new byte[] {
                0, 1, 2 });

        collector.checkThat("tlv01-correct-construction",
                tlv01.getArray(), equalTo(new byte[] {1, 2, 3, 0, 1, 2 }));

        collector.checkThat("tlv11-valid",
                TlvBufferUtils.isValid(tlv11.getArray(), 1, 1),
                equalTo(true));
        collector.checkThat("tlv01-valid",
                TlvBufferUtils.isValid(tlv01.getArray(), 0, 1),
                equalTo(true));
    }

    /**
     * Verify that can build a valid TLV from a List of byte[].
     */
    @Test
    public void testTlvListOperations() {
        byte[] entry1 = { 1, 2, 3 };
        byte[] entry2 = { 4, 5 };
        byte[] entry3 = new byte[0];
        List<byte[]> data = new ArrayList<>();
        data.add(entry1);
        data.add(entry2);
        data.add(entry3);
        data.add(null); // zero-length should work

        TlvBufferUtils.TlvConstructor tlv01 = new TlvBufferUtils.TlvConstructor(0, 1);
        tlv01.allocateAndPut(data);
        byte[] tlvData = tlv01.getArray();
        List<byte[]> parsedList = new TlvBufferUtils.TlvIterable(0, 1, tlvData).toList();

        collector.checkThat("tlvData-correct-length", tlvData.length,
                equalTo(entry1.length + 1 + entry2.length + 1 + entry3.length + 1 + 1));
        collector.checkThat("parsedList-correct-length", parsedList.size(), equalTo(4));
        collector.checkThat("parsedList-entry1", parsedList.get(0), equalTo(entry1));
        collector.checkThat("parsedList-entry2", parsedList.get(1), equalTo(entry2));
        collector.checkThat("parsedList-entry3", parsedList.get(2), equalTo(entry3));
        collector.checkThat("parsedList-entry4", parsedList.get(3), equalTo(new byte[0]));
    }

    /**
     * Verify that can parse a (correctly formatted) byte array to a list.
     */
    @Test
    public void testTlvParseToList() {
        byte[] validTlv01 = { 0, 1, 55, 2, 33, 66, 0 };

        List<byte[]> parsedList = new TlvBufferUtils.TlvIterable(0, 1, validTlv01).toList();

        collector.checkThat("parsedList-entry1", parsedList.get(0), equalTo(new byte[0]));
        collector.checkThat("parsedList-entry2", parsedList.get(1), equalTo(new byte[] { 55 }));
        collector.checkThat("parsedList-entry3", parsedList.get(2), equalTo(new byte[] { 33, 66 }));
        collector.checkThat("parsedList-entry4", parsedList.get(3), equalTo(new byte[0]));
    }

    /**
     * Verify that an exception is thrown when trying to parse an invalid array.
     */
    @Test(expected = BufferOverflowException.class)
    public void testTlvParseToListError() {
        byte[] invalidTlv01 = { 0, 1, 55, 2, 55, 66, 3 }; // bad data

        List<byte[]> data = new TlvBufferUtils.TlvIterable(0, 1, invalidTlv01).toList();
    }

    @Test
    public void testTlvIterate() {
        final String ascii = "ABC";
        final String nonAscii = "何かもっと複雑な";

        TlvBufferUtils.TlvConstructor tlv22 = new TlvBufferUtils.TlvConstructor(2, 2);
        tlv22.allocate(18);
        tlv22.putInt(0, 2);
        tlv22.putShort(2, (short) 3);
        tlv22.putZeroLengthElement(55);

        TlvBufferUtils.TlvIterable tlv22It = new TlvBufferUtils.TlvIterable(2, 2, tlv22.getArray());
        int count = 0;
        for (TlvBufferUtils.TlvElement tlv : tlv22It) {
            if (count == 0) {
                collector.checkThat("tlv22-correct-iteration-mType", tlv.type, equalTo(0));
                collector.checkThat("tlv22-correct-iteration-mLength", tlv.length, equalTo(4));
                collector.checkThat("tlv22-correct-iteration-DATA", tlv.getInt(), equalTo(2));
            } else if (count == 1) {
                collector.checkThat("tlv22-correct-iteration-mType", tlv.type, equalTo(2));
                collector.checkThat("tlv22-correct-iteration-mLength", tlv.length, equalTo(2));
                collector.checkThat("tlv22-correct-iteration-DATA", (int) tlv.getShort(),
                        equalTo(3));
            } else if (count == 2) {
                collector.checkThat("tlv22-correct-iteration-mType", tlv.type, equalTo(55));
                collector.checkThat("tlv22-correct-iteration-mLength", tlv.length, equalTo(0));
            } else {
                collector.checkThat("Invalid number of iterations in loop - tlv22", true,
                        equalTo(false));
            }
            ++count;
        }
        if (count != 3) {
            collector.checkThat("Invalid number of iterations outside loop - tlv22", true,
                    equalTo(false));
        }

        TlvBufferUtils.TlvConstructor tlv02 = new TlvBufferUtils.TlvConstructor(0, 2);
        tlv02.allocate(100);
        tlv02.putByte(0, (byte) 2);
        tlv02.putString(0, ascii);
        tlv02.putString(0, nonAscii);

        TlvBufferUtils.TlvIterable tlv02It = new TlvBufferUtils.TlvIterable(0, 2, tlv02.getArray());
        count = 0;
        for (TlvBufferUtils.TlvElement tlv : tlv02It) {
            if (count == 0) {
                collector.checkThat("tlv02-correct-iteration-mLength", tlv.length, equalTo(1));
                collector.checkThat("tlv02-correct-iteration-DATA", (int) tlv.getByte(),
                        equalTo(2));
            } else if (count == 1) {
                collector.checkThat("tlv02-correct-iteration-mLength", tlv.length,
                        equalTo(ascii.length()));
                collector.checkThat("tlv02-correct-iteration-DATA", tlv.getString().equals(ascii),
                        equalTo(true));
            } else if (count == 2) {
                collector.checkThat("tlv02-correct-iteration-mLength", tlv.length,
                        equalTo(nonAscii.getBytes().length));
                collector.checkThat("tlv02-correct-iteration-DATA",
                        tlv.getString().equals(nonAscii), equalTo(true));
            } else {
                collector.checkThat("Invalid number of iterations in loop - tlv02", true,
                        equalTo(false));
            }
            ++count;
        }
        collector.checkThat("Invalid number of iterations outside loop - tlv02", count,
                equalTo(3));

        collector.checkThat("tlv22-valid",
                TlvBufferUtils.isValid(tlv22.getArray(), 2, 2),
                equalTo(true));
        collector.checkThat("tlv02-valid",
                TlvBufferUtils.isValid(tlv02.getArray(), 0, 2),
                equalTo(true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvInvalidSizeT1L0() {
        TlvBufferUtils.TlvConstructor tlv10 = new TlvBufferUtils.TlvConstructor(1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvInvalidSizeTm3L2() {
        TlvBufferUtils.TlvConstructor tlv10 = new TlvBufferUtils.TlvConstructor(-3, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvInvalidSizeT1Lm2() {
        TlvBufferUtils.TlvConstructor tlv10 = new TlvBufferUtils.TlvConstructor(1, -2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvInvalidSizeT1L3() {
        TlvBufferUtils.TlvConstructor tlv10 = new TlvBufferUtils.TlvConstructor(1, 3);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvInvalidSizeT3L1() {
        TlvBufferUtils.TlvConstructor tlv10 = new TlvBufferUtils.TlvConstructor(3, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvItInvalidSizeT1L0() {
        final byte[] dummy = {
                0, 1, 2 };
        final int dummyLength = 3;
        TlvBufferUtils.TlvIterable tlvIt10 = new TlvBufferUtils.TlvIterable(1, 0, dummy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvItInvalidSizeTm3L2() {
        final byte[] dummy = {
                0, 1, 2 };
        final int dummyLength = 3;
        TlvBufferUtils.TlvIterable tlvIt10 = new TlvBufferUtils.TlvIterable(-3, 2, dummy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvItInvalidSizeT1Lm2() {
        final byte[] dummy = {
                0, 1, 2 };
        final int dummyLength = 3;
        TlvBufferUtils.TlvIterable tlvIt10 = new TlvBufferUtils.TlvIterable(1, -2, dummy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvItInvalidSizeT1L3() {
        final byte[] dummy = {
                0, 1, 2 };
        final int dummyLength = 3;
        TlvBufferUtils.TlvIterable tlvIt10 = new TlvBufferUtils.TlvIterable(1, 3, dummy);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTlvItInvalidSizeT3L1() {
        final byte[] dummy = {
                0, 1, 2 };
        final int dummyLength = 3;
        TlvBufferUtils.TlvIterable tlvIt10 = new TlvBufferUtils.TlvIterable(3, 1, dummy);
    }

    /**
     * Validate that a malformed byte array fails the TLV validity test.
     */
    @Test
    public void testTlvInvalidByteArray() {
        TlvBufferUtils.TlvConstructor tlv01 = new TlvBufferUtils.TlvConstructor(0, 1);
        tlv01.allocate(15);
        tlv01.putByte(0, (byte) 2);
        tlv01.putByteArray(2, new byte[]{0, 1, 2});

        byte[] array = tlv01.getArray();
        array[0] = 10;

        collector.checkThat("tlv01-invalid",
                TlvBufferUtils.isValid(array, 0, 1), equalTo(false));
    }
}
