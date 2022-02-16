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

import android.os.Parcel;
import android.os.ParcelUuid;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Unit test cases for {@link AdvertiseData}.
 * <p>
 * To run the test, use adb shell am instrument -e class 'android.bluetooth.le.AdvertiseDataTest' -w
 * 'com.android.bluetooth.tests/android.bluetooth.BluetoothTestRunner'
 */
public class AdvertiseDataTest extends TestCase {

    private AdvertiseData.Builder mAdvertiseDataBuilder;

    @Override
    protected void setUp() throws Exception {
        mAdvertiseDataBuilder = new AdvertiseData.Builder();
    }

    @SmallTest
    public void testEmptyData() {
        Parcel parcel = Parcel.obtain();
        AdvertiseData data = mAdvertiseDataBuilder.build();
        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }

    @SmallTest
    public void testEmptyServiceUuid() {
        Parcel parcel = Parcel.obtain();
        AdvertiseData data = mAdvertiseDataBuilder.setIncludeDeviceName(true).build();
        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }

    @SmallTest
    public void testEmptyManufacturerData() {
        Parcel parcel = Parcel.obtain();
        int manufacturerId = 50;
        byte[] manufacturerData = new byte[0];
        AdvertiseData data =
                mAdvertiseDataBuilder.setIncludeDeviceName(true)
                        .addManufacturerData(manufacturerId, manufacturerData).build();
        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }

    @SmallTest
    public void testEmptyServiceData() {
        Parcel parcel = Parcel.obtain();
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        byte[] serviceData = new byte[0];
        AdvertiseData data =
                mAdvertiseDataBuilder.setIncludeDeviceName(true)
                        .addServiceData(uuid, serviceData).build();
        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }

    @SmallTest
    public void testServiceUuid() {
        Parcel parcel = Parcel.obtain();
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid2 = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");

        AdvertiseData data =
                mAdvertiseDataBuilder.setIncludeDeviceName(true)
                        .addServiceUuid(uuid).addServiceUuid(uuid2).build();
        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }

    @SmallTest
    public void testManufacturerData() {
        Parcel parcel = Parcel.obtain();
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        ParcelUuid uuid2 = ParcelUuid.fromString("0000110B-0000-1000-8000-00805F9B34FB");

        int manufacturerId = 50;
        byte[] manufacturerData = new byte[] {
                (byte) 0xF0, 0x00, 0x02, 0x15 };
        AdvertiseData data =
                mAdvertiseDataBuilder.setIncludeDeviceName(true)
                        .addServiceUuid(uuid).addServiceUuid(uuid2)
                        .addManufacturerData(manufacturerId, manufacturerData).build();

        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }

    @SmallTest
    public void testServiceData() {
        Parcel parcel = Parcel.obtain();
        ParcelUuid uuid = ParcelUuid.fromString("0000110A-0000-1000-8000-00805F9B34FB");
        byte[] serviceData = new byte[] {
                (byte) 0xF0, 0x00, 0x02, 0x15 };
        AdvertiseData data =
                mAdvertiseDataBuilder.setIncludeDeviceName(true)
                        .addServiceData(uuid, serviceData).build();
        data.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        AdvertiseData dataFromParcel =
                AdvertiseData.CREATOR.createFromParcel(parcel);
        assertEquals(data, dataFromParcel);
    }
}
