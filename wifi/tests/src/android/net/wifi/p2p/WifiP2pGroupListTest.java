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
import static org.junit.Assert.assertFalse;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Unit test harness for {@link android.net.wifi.p2p.WifiP2pGroupList}
 */
@SmallTest
public class WifiP2pGroupListTest {

    private static final WifiP2pDevice TEST_GROUP_OWNER_1 = new WifiP2pDevice("aa:bb:cc:dd:ee:f0");
    private static final WifiP2pDevice TEST_GROUP_OWNER_2 = new WifiP2pDevice("aa:bb:cc:dd:ee:f1");
    private static final WifiP2pDevice TEST_GROUP_OWNER_3 = new WifiP2pDevice("aa:bb:cc:dd:ee:f2");
    private static final WifiP2pDevice TEST_GROUP_OWNER_OTHER =
            new WifiP2pDevice("aa:bb:cc:dd:ee:f3");

    private WifiP2pGroup mTestGroup1;
    private WifiP2pGroup mTestGroup2;
    private WifiP2pGroup mTestGroup3;
    private WifiP2pGroup mTestGroup4;

    private WifiP2pGroup createGroup(
            int networkId, String networkName,
            String passphrase, boolean isGo,
            WifiP2pDevice goDev) {
        WifiP2pGroup group = new WifiP2pGroup();
        group.setNetworkId(networkId);
        group.setNetworkName(networkName);
        group.setPassphrase(passphrase);
        group.setIsGroupOwner(isGo);
        group.setOwner(goDev);
        return group;
    }

    @Before
    public void setUp() throws Exception {
        mTestGroup1 = createGroup(0, "testGroup1", "12345678", false, TEST_GROUP_OWNER_1);
        mTestGroup2 = createGroup(1, "testGroup2", "12345678", true, TEST_GROUP_OWNER_2);
        mTestGroup3 = createGroup(2, "testGroup3", "12345678", false, TEST_GROUP_OWNER_3);
        mTestGroup4 = createGroup(3, "testGroup4", "12345678", false, TEST_GROUP_OWNER_1);
    }

    /**
     * Verify basic operations.
     */
    @Test
    public void testListOperations() throws Exception {
        WifiP2pGroupList list = new WifiP2pGroupList();
        list.add(mTestGroup1);
        list.add(mTestGroup2);
        list.add(mTestGroup3);
        list.add(mTestGroup4);
        assertEquals(4, list.getGroupList().size());

        // in list
        assertEquals(mTestGroup2.getNetworkId(),
                list.getNetworkId(TEST_GROUP_OWNER_2.deviceAddress));
        assertEquals(TEST_GROUP_OWNER_2.deviceAddress,
                list.getOwnerAddr(mTestGroup2.getNetworkId()));
        // not in list
        assertEquals(-1, list.getNetworkId(TEST_GROUP_OWNER_OTHER.deviceAddress));
        // if there are groups with the same GO, return the first one found.
        assertEquals(mTestGroup1.getNetworkId(),
                list.getNetworkId(TEST_GROUP_OWNER_1.deviceAddress));
        // identify groups with the same GO, but different network names.
        assertEquals(mTestGroup4.getNetworkId(),
                list.getNetworkId(TEST_GROUP_OWNER_1.deviceAddress, "testGroup4"));

        list.remove(mTestGroup3.getNetworkId());
        assertEquals(-1, list.getNetworkId(TEST_GROUP_OWNER_3.deviceAddress));
        assertFalse(list.contains(mTestGroup3.getNetworkId()));

        assertEquals(3, list.getGroupList().size());

        Parcel parcelW = Parcel.obtain();
        list.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiP2pGroupList fromParcel = WifiP2pGroupList.CREATOR.createFromParcel(parcelR);

        assertEquals(list.toString(), fromParcel.toString());

        list.clear();
        assertEquals(0, list.getGroupList().size());

    }
}
