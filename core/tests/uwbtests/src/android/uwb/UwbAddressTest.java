/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test of {@link UwbAddress}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UwbAddressTest {

    @Test
    public void testFromBytes_Short() {
        runFromBytes(UwbAddress.SHORT_ADDRESS_BYTE_LENGTH);
    }

    @Test
    public void testFromBytes_Extended() {
        runFromBytes(UwbAddress.EXTENDED_ADDRESS_BYTE_LENGTH);
    }

    private void runFromBytes(int len) {
        byte[] addressBytes = getByteArray(len);
        UwbAddress address = UwbAddress.fromBytes(addressBytes);
        assertEquals(address.size(), len);
        assertEquals(addressBytes, address.toBytes());
    }

    private byte[] getByteArray(int len) {
        byte[] res = new byte[len];
        for (int i = 0; i < len; i++) {
            res[i] = (byte) i;
        }
        return res;
    }

    @Test
    public void testParcel_Short() {
        runParcel(true);
    }

    @Test
    public void testParcel_Extended() {
        runParcel(false);
    }

    private void runParcel(boolean useShortAddress) {
        Parcel parcel = Parcel.obtain();
        UwbAddress address = UwbTestUtils.getUwbAddress(useShortAddress);
        address.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        UwbAddress fromParcel = UwbAddress.CREATOR.createFromParcel(parcel);
        assertEquals(address, fromParcel);
    }
}
