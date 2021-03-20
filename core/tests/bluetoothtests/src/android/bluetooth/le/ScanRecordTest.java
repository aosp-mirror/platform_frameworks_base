/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import android.os.BytesMatcher;
import android.os.ParcelUuid;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.util.HexDump;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Unit test cases for {@link ScanRecord}.
 * <p>
 * To run this test, use adb shell am instrument -e class 'android.bluetooth.ScanRecordTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
public class ScanRecordTest extends TestCase {
    /**
     * Example raw beacons captured from a Blue Charm BC011
     */
    private static final String RECORD_URL = "0201060303AAFE1716AAFE10EE01626C7565636861726D626561636F6E730009168020691E0EFE13551109426C7565436861726D5F313639363835000000";
    private static final String RECORD_UUID = "0201060303AAFE1716AAFE00EE626C7565636861726D31000000000001000009168020691E0EFE13551109426C7565436861726D5F313639363835000000";
    private static final String RECORD_TLM = "0201060303AAFE1116AAFE20000BF017000008874803FB93540916802069080EFE13551109426C7565436861726D5F313639363835000000000000000000";
    private static final String RECORD_IBEACON = "0201061AFF4C000215426C7565436861726D426561636F6E730EFE1355C509168020691E0EFE13551109426C7565436861726D5F31363936383500000000";

    @SmallTest
    public void testMatchesAnyField_Eddystone_Parser() {
        final List<String> found = new ArrayList<>();
        final Predicate<byte[]> matcher = (v) -> {
            found.add(HexDump.toHexString(v));
            return false;
        };
        ScanRecord.parseFromBytes(HexDump.hexStringToByteArray(RECORD_URL))
                .matchesAnyField(matcher);

        assertEquals(Arrays.asList(
                "020106",
                "0303AAFE",
                "1716AAFE10EE01626C7565636861726D626561636F6E7300",
                "09168020691E0EFE1355",
                "1109426C7565436861726D5F313639363835"), found);
    }

    @SmallTest
    public void testMatchesAnyField_Eddystone() {
        final BytesMatcher matcher = BytesMatcher.decode("⊆0016AAFE/00FFFFFF");
        assertMatchesAnyField(RECORD_URL, matcher);
        assertMatchesAnyField(RECORD_UUID, matcher);
        assertMatchesAnyField(RECORD_TLM, matcher);
        assertNotMatchesAnyField(RECORD_IBEACON, matcher);
    }

    @SmallTest
    public void testMatchesAnyField_iBeacon_Parser() {
        final List<String> found = new ArrayList<>();
        final Predicate<byte[]> matcher = (v) -> {
            found.add(HexDump.toHexString(v));
            return false;
        };
        ScanRecord.parseFromBytes(HexDump.hexStringToByteArray(RECORD_IBEACON))
                .matchesAnyField(matcher);

        assertEquals(Arrays.asList(
                "020106",
                "1AFF4C000215426C7565436861726D426561636F6E730EFE1355C5",
                "09168020691E0EFE1355",
                "1109426C7565436861726D5F313639363835"), found);
    }

    @SmallTest
    public void testMatchesAnyField_iBeacon() {
        final BytesMatcher matcher = BytesMatcher.decode("⊆00FF4C0002/00FFFFFFFF");
        assertNotMatchesAnyField(RECORD_URL, matcher);
        assertNotMatchesAnyField(RECORD_UUID, matcher);
        assertNotMatchesAnyField(RECORD_TLM, matcher);
        assertMatchesAnyField(RECORD_IBEACON, matcher);
    }

    @SmallTest
    public void testParser() {
        byte[] scanRecord = new byte[] {
                0x02, 0x01, 0x1a, // advertising flags
                0x05, 0x02, 0x0b, 0x11, 0x0a, 0x11, // 16 bit service uuids
                0x04, 0x09, 0x50, 0x65, 0x64, // name
                0x02, 0x0A, (byte) 0xec, // tx power level
                0x05, 0x16, 0x0b, 0x11, 0x50, 0x64, // service data
                0x05, (byte) 0xff, (byte) 0xe0, 0x00, 0x02, 0x15, // manufacturer specific data
                0x03, 0x50, 0x01, 0x02, // an unknown data type won't cause trouble
        };
        ScanRecord data = ScanRecord.parseFromBytes(scanRecord);
        assertEquals(0x1a, data.getAdvertiseFlags());
        ParcelUuid uuid1 = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid2 = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");
        assertTrue(data.getServiceUuids().contains(uuid1));
        assertTrue(data.getServiceUuids().contains(uuid2));

        assertEquals("Ped", data.getDeviceName());
        assertEquals(-20, data.getTxPowerLevel());

        assertTrue(data.getManufacturerSpecificData().get(0x00E0) != null);
        assertArrayEquals(new byte[] {
                0x02, 0x15 }, data.getManufacturerSpecificData().get(0x00E0));

        assertTrue(data.getServiceData().containsKey(uuid2));
        assertArrayEquals(new byte[] {
                0x50, 0x64 }, data.getServiceData().get(uuid2));
    }

    // Assert two byte arrays are equal.
    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            fail("expected:<" + Arrays.toString(expected) +
                    "> but was:<" + Arrays.toString(actual) + ">");
        }

    }

    private static void assertMatchesAnyField(String record, BytesMatcher matcher) {
        assertTrue(ScanRecord.parseFromBytes(HexDump.hexStringToByteArray(record))
                .matchesAnyField(matcher));
    }

    private static void assertNotMatchesAnyField(String record, BytesMatcher matcher) {
        assertFalse(ScanRecord.parseFromBytes(HexDump.hexStringToByteArray(record))
                .matchesAnyField(matcher));
    }
}
