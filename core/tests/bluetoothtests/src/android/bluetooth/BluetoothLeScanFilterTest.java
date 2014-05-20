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

import android.bluetooth.BluetoothLeScanner.ScanResult;
import android.os.Parcel;
import android.os.ParcelUuid;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Unit test cases for Bluetooth LE scan filters.
 * <p>
 * To run this test, use adb shell am instrument -e class
 * 'android.bluetooth.BluetoothLeScanFilterTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
public class BluetoothLeScanFilterTest extends TestCase {

    private static final String DEVICE_MAC = "01:02:03:04:05:AB";
    private ScanResult mScanResult;
    private BluetoothLeScanFilter.Builder mFilterBuilder;

    @Override
    protected void setUp() throws Exception {
        byte[] scanRecord = new byte[] {
                0x02, 0x01, 0x1a, // advertising flags
                0x05, 0x02, 0x0b, 0x11, 0x0a, 0x11, // 16 bit service uuids
                0x04, 0x09, 0x50, 0x65, 0x64, // name
                0x02, 0x0A, (byte) 0xec, // tx power level
                0x05, 0x16, 0x0b, 0x11, 0x50, 0x64, // service data
                0x05, (byte) 0xff, (byte) 0xe0, 0x00, 0x02, 0x15, // manufacturer specific data
                0x03, 0x50, 0x01, 0x02, // an unknown data type won't cause trouble
        };

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = adapter.getRemoteDevice(DEVICE_MAC);
        mScanResult = new ScanResult(device, scanRecord, -10, 1397545200000000L);
        mFilterBuilder = BluetoothLeScanFilter.newBuilder();
    }

    @SmallTest
    public void testNameFilter() {
        BluetoothLeScanFilter filter = mFilterBuilder.name("Ped").build();
        assertTrue("name filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder.name("Pem").build();
        assertFalse("name filter fails", filter.matches(mScanResult));

    }

    @SmallTest
    public void testDeviceFilter() {
        BluetoothLeScanFilter filter = mFilterBuilder.macAddress(DEVICE_MAC).build();
        assertTrue("device filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder.macAddress("11:22:33:44:55:66").build();
        assertFalse("device filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testServiceUuidFilter() {
        BluetoothLeScanFilter filter = mFilterBuilder.serviceUuid(
                ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB")).build();
        assertTrue("uuid filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder.serviceUuid(
                ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB")).build();
        assertFalse("uuid filter fails", filter.matches(mScanResult));

        filter = mFilterBuilder
                .serviceUuid(ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB"))
                .serviceUuidMask(ParcelUuid.fromString("FFFFFFF0-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))
                .build();
        assertTrue("uuid filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testServiceDataFilter() {
        byte[] serviceData = new byte[] {
                0x0b, 0x11, 0x50, 0x64 };
        BluetoothLeScanFilter filter = mFilterBuilder.serviceData(serviceData).build();
        assertTrue("service data filter fails", filter.matches(mScanResult));

        byte[] nonMatchData = new byte[] {
                0x0b, 0x01, 0x50, 0x64 };
        filter = mFilterBuilder.serviceData(nonMatchData).build();
        assertFalse("service data filter fails", filter.matches(mScanResult));

        byte[] mask = new byte[] {
                (byte) 0xFF, (byte) 0x00, (byte) 0xFF, (byte) 0xFF };
        filter = mFilterBuilder.serviceData(nonMatchData).serviceDataMask(mask).build();
        assertTrue("partial service data filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testManufacturerSpecificData() {
        byte[] manufacturerData = new byte[] {
                (byte) 0xE0, 0x00, 0x02, 0x15 };
        int manufacturerId = 224;
        BluetoothLeScanFilter filter =
                mFilterBuilder.manufacturerData(manufacturerId, manufacturerData).build();
        assertTrue("manufacturerData filter fails", filter.matches(mScanResult));

        byte[] nonMatchData = new byte[] {
                (byte) 0xF0, 0x00, 0x02, 0x15 };
        filter = mFilterBuilder.manufacturerData(manufacturerId, nonMatchData).build();
        assertFalse("manufacturerData filter fails", filter.matches(mScanResult));

        byte[] mask = new byte[] {
                (byte) 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        filter = mFilterBuilder.manufacturerData(manufacturerId, nonMatchData)
                .manufacturerDataMask(mask).build();
        assertTrue("partial manufacturerData filter fails", filter.matches(mScanResult));
    }

    @SmallTest
    public void testReadWriteParcel() {
        BluetoothLeScanFilter filter = mFilterBuilder.build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.name("Ped").build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.macAddress("11:22:33:44:55:66").build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.serviceUuid(
                ParcelUuid.fromString("0000110C-0000-1000-8000-00805F9B34FB")).build();
        testReadWriteParcelForFilter(filter);

        filter = mFilterBuilder.serviceUuidMask(
                ParcelUuid.fromString("FFFFFFF0-FFFF-FFFF-FFFF-FFFFFFFFFFFF")).build();
        testReadWriteParcelForFilter(filter);

        byte[] serviceData = new byte[] {
                0x0b, 0x11, 0x50, 0x64 };

        filter = mFilterBuilder.serviceData(serviceData).build();
        testReadWriteParcelForFilter(filter);

        byte[] serviceDataMask = new byte[] {
                (byte) 0xFF, (byte) 0x00, (byte) 0xFF, (byte) 0xFF };
        filter = mFilterBuilder.serviceDataMask(serviceDataMask).build();
        testReadWriteParcelForFilter(filter);

        byte[] manufacturerData = new byte[] {
                (byte) 0xE0, 0x00, 0x02, 0x15 };
        int manufacturerId = 224;
        filter = mFilterBuilder.manufacturerData(manufacturerId, manufacturerData).build();
        testReadWriteParcelForFilter(filter);

        byte[] manufacturerDataMask = new byte[] {
                (byte) 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
        filter = mFilterBuilder.manufacturerDataMask(manufacturerDataMask).build();
        testReadWriteParcelForFilter(filter);
    }

    private void testReadWriteParcelForFilter(BluetoothLeScanFilter filter) {
        Parcel parcel = Parcel.obtain();
        filter.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        BluetoothLeScanFilter filterFromParcel =
                BluetoothLeScanFilter.CREATOR.createFromParcel(parcel);
        System.out.println(filterFromParcel);
        assertEquals(filter, filterFromParcel);
    }
}
