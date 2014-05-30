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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Unit test cases for Bluetooth LE scan filters.
 * <p>
 * To run this test, use adb shell am instrument -e class 'android.bluetooth.ScanFilterTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
public class ScanFilterTest extends TestCase {

    private static final String DEVICE_MAC = "01:02:03:04:05:AB";
    private ScanResult mScanResult;
    private ScanFilter.Builder mFilterBuilder;

    @Override
    protected void setUp() throws Exception {
        byte[] scanRecord = new byte[] {
                0x02, 0x01, 0x1a, // advertising flags
                0x05, 0x02, 0x0b, 0x11, 0x0a, 0x11, // 16 bit service uuids
                0x04, 0x09, 0x50, 0x65, 0x64, // setName
                0x02, 0x0A, (byte) 0xec, // tx power level
                0x05, 0x16, 0x0b, 0x11, 0x50, 0x64, // service data
                0x05, (byte) 0xff, (byte) 0xe0, 0x00, 0x02, 0x15, // manufacturer specific data
                0x03, 0x50, 0x01, 0x02, // an unknown data type won't cause trouble
        };

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(DEVICE_MAC);
        mScanResult = new ScanResult(device, scanRecord, -10, 1397545200000000L);
        mFilterBuilder = new ScanFilter.Builder();
    }

    @SmallTest
    public void testsetNameFilter() {
        ScanFilter filter = mFilterBuilder.setName("Ped").build();
        assertTrue("setName filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder.setName("Pem").build();
        assertFalse("setName filter fails", filter.matches(mScanResult));

    }

    @SmallTest
    public void testDeviceFilter() {
        ScanFilter filter = mFilterBuilder.setMacAddress(DEVICE_MAC).build();
        assertTrue("device filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder.setMacAddress("11:22:33:44:55:66").build();
        assertFalse("device filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testsetServiceUuidFilter() {
        ScanFilter filter = mFilterBuilder.setServiceUuid(
                ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB")).build();
        assertTrue("uuid filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder.setServiceUuid(
                ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB")).build();
        assertFalse("uuid filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder
                .setServiceUuid(ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB"),
                        ParcelUuid.fromString("FFFFFFF0-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))
                .build();
        assertTrue("uuid filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testsetServiceDataFilter() {
        byte[] setServiceData = new byte[] {
                0x0b, 0x11, 0x50, 0x64 };
        ScanFilter filter = mFilterBuilder.setServiceData(setServiceData).build();
        assertTrue("service data filter fails", filter.matches(mScanResult));

        byte[] nonMatchData = new byte[] {
                0x0b, 0x01, 0x50, 0x64 };
        filter = mFilterBuilder.setServiceData(nonMatchData).build();
        assertFalse("service data filter fails", filter.matches(mScanResult));

        byte[] mask = new byte[] {
                (byte) 0xFF, (byte) 0x00, (byte) 0xFF, (byte) 0xFF };
        filter = mFilterBuilder.setServiceData(nonMatchData, mask).build();
        assertTrue("partial service data filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testManufacturerSpecificData() {
        byte[] setManufacturerData = new byte[] {
                (byte) 0xE0, 0x00, 0x02, 0x15 };
        int manufacturerId = 224;
        ScanFilter filter =
                mFilterBuilder.setManufacturerData(manufacturerId, setManufacturerData).build();
        assertTrue("setManufacturerData filter fails", filter.matches(mScanResult));

        byte[] nonMatchData = new byte[] {
                (byte) 0xF0, 0x00, 0x02, 0x15 };
        filter = mFilterBuilder.setManufacturerData(manufacturerId, nonMatchData).build();
        assertFalse("setManufacturerData filter fails", filter.matches(mScanResult));

        byte[] mask = new byte[] {
                (byte) 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        filter = mFilterBuilder.setManufacturerData(manufacturerId, nonMatchData, mask).build();
        assertTrue("partial setManufacturerData filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testReadWriteParcel() {
        ScanFilter filter = mFilterBuilder.build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.setName("Ped").build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.setMacAddress("11:22:33:44:55:66").build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.setServiceUuid(
                ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB")).build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.setServiceUuid(
                ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB"),
                ParcelUuid.fromString("FFFFFFF0-FFFF-FFFF-FFFF-FFFFFFFFFFFF")).build();
        testReadWriteParcelForFilter(filter);

        byte[] setServiceData = new byte[] {
                0x0b, 0x11, 0x50, 0x64 };

        filter = mFilterBuilder.setServiceData(setServiceData).build();
        testReadWriteParcelForFilter(filter);

        byte[] serviceDataMask = new byte[] {
                (byte) 0xFF, (byte) 0x00, (byte) 0xFF, (byte) 0xFF };
        filter = mFilterBuilder.setServiceData(setServiceData, serviceDataMask).build();
        testReadWriteParcelForFilter(filter);

        byte[] manufacturerData = new byte[] {
                (byte) 0xE0, 0x00, 0x02, 0x15 };
        int manufacturerId = 224;
        filter = mFilterBuilder.setManufacturerData(manufacturerId, manufacturerData).build();
        testReadWriteParcelForFilter(filter);

        byte[] manufacturerDataMask = new byte[] {
                (byte) 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        filter = mFilterBuilder.setManufacturerData(manufacturerId, manufacturerData,
                manufacturerDataMask).build();
        testReadWriteParcelForFilter(filter);
    }

    private void testReadWriteParcelForFilter(ScanFilter filter) {
        Parcel parcel = Parcel.obtain();
        filter.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ScanFilter filterFromParcel =
                ScanFilter.CREATOR.createFromParcel(parcel);
        System.out.println(filterFromParcel);
        assertEquals(filter, filterFromParcel);
    }
}
