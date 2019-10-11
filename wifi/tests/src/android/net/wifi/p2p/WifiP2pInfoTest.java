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

import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pInfo}
 */
@SmallTest
public class WifiP2pInfoTest {

    private InetAddress mGroupOnwerIpv4Address;

    @Before
    public void setUp() throws Exception {
        byte[] ipv4 = {(byte) 192, (byte) 168, (byte) 49, (byte) 1};
        mGroupOnwerIpv4Address = InetAddress.getByAddress(ipv4);
    }

    /**
     * Verifies copy constructor.
     */
    @Test
    public void testCopyOperator() throws Exception {
        WifiP2pInfo info = new WifiP2pInfo();
        info.groupFormed = true;
        info.isGroupOwner = true;
        info.groupOwnerAddress = mGroupOnwerIpv4Address;

        WifiP2pInfo copiedInfo = new WifiP2pInfo(info);

        // no equals operator, use toString for data comparison.
        assertEquals(info.toString(), copiedInfo.toString());
    }

    /**
     * Verifies parcel serialization/deserialization.
     */
    @Test
    public void testParcelOperation() throws Exception {
        WifiP2pInfo info = new WifiP2pInfo();
        info.groupFormed = true;
        info.isGroupOwner = true;
        info.groupOwnerAddress = mGroupOnwerIpv4Address;

        Parcel parcelW = Parcel.obtain();
        info.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiP2pInfo fromParcel = WifiP2pInfo.CREATOR.createFromParcel(parcelR);

        // no equals operator, use toString for data comparison.
        assertEquals(info.toString(), fromParcel.toString());
    }
}
