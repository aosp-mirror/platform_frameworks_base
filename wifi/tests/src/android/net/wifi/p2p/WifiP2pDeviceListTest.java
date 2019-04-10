/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.wifi.p2p;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pDeviceList}
 */
@SmallTest
public class WifiP2pDeviceListTest {

    private static final WifiP2pDevice TEST_DEVICE_1 = new WifiP2pDevice("aa:bb:cc:dd:ee:ff");
    private static final WifiP2pDevice TEST_DEVICE_2 = new WifiP2pDevice("aa:bb:cc:dd:ee:f1");
    private static final WifiP2pDevice TEST_DEVICE_3 = new WifiP2pDevice("11:22:33:44:55:66");
    private static final WifiP2pDevice TEST_DEVICE_4 = new WifiP2pDevice("a0:b0:c0:d0:e0:f0");

    /**
     * Verify basic operations.
     */
    @Test
    public void testListOperations() throws Exception {
        WifiP2pDeviceList list = new WifiP2pDeviceList();
        list.update(TEST_DEVICE_1);
        list.update(TEST_DEVICE_2);
        list.update(TEST_DEVICE_3);
        assertEquals(3, list.getDeviceList().size());

        assertEquals(TEST_DEVICE_1, list.get(TEST_DEVICE_1.deviceAddress));
        assertEquals(null, list.get(TEST_DEVICE_4.deviceAddress));

        list.remove(TEST_DEVICE_2.deviceAddress);
        assertEquals(null, list.get(TEST_DEVICE_2.deviceAddress));

        list.remove(TEST_DEVICE_3);
        assertEquals(null, list.get(TEST_DEVICE_3.deviceAddress));

        assertEquals(1, list.getDeviceList().size());

        list.clear();
        assertEquals(0, list.getDeviceList().size());

        Parcel parcelW = Parcel.obtain();
        list.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiP2pDeviceList fromParcel = WifiP2pDeviceList.CREATOR.createFromParcel(parcelR);

        assertEquals(list.toString(), fromParcel.toString());
    }
}
