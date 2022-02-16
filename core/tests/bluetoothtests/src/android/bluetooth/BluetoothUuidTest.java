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

package android.bluetooth;

import android.os.ParcelUuid;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Unit test cases for {@link BluetoothUuid}.
 * <p>
 * To run this test, use adb shell am instrument -e class 'android.bluetooth.BluetoothUuidTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
public class BluetoothUuidTest extends TestCase {

    @SmallTest
    public void testUuidParser() {
        byte[] uuid16 = new byte[] {
                0x0B, 0x11 };
        assertEquals(ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB"),
                BluetoothUuid.parseUuidFrom(uuid16));

        byte[] uuid32 = new byte[] {
                0x0B, 0x11, 0x33, (byte) 0xFE };
        assertEquals(ParcelUuid.fromString("FE33110B-0000-1000-8000-00805F9B34FB"),
                BluetoothUuid.parseUuidFrom(uuid32));

        byte[] uuid128 = new byte[] {
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, (byte) 0xFF };
        assertEquals(ParcelUuid.fromString("FF0F0E0D-0C0B-0A09-0807-0060504030201"),
                BluetoothUuid.parseUuidFrom(uuid128));
    }

    @SmallTest
    public void testUuidType() {
        assertTrue(BluetoothUuid.is16BitUuid(
                ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB")));
        assertFalse(BluetoothUuid.is32BitUuid(
                ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB")));

        assertFalse(BluetoothUuid.is16BitUuid(
                ParcelUuid.fromString("FE33110B-0000-1000-8000-00805F9B34FB")));
        assertTrue(BluetoothUuid.is32BitUuid(
                ParcelUuid.fromString("FE33110B-0000-1000-8000-00805F9B34FB")));
        assertFalse(BluetoothUuid.is32BitUuid(
                ParcelUuid.fromString("FE33110B-1000-1000-8000-00805F9B34FB")));

    }
}
