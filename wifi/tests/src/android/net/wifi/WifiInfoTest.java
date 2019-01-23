/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiInfo}.
 */
@SmallTest
public class WifiInfoTest {
    private static final long TEST_TX_SUCCESS = 1;
    private static final long TEST_TX_RETRIES = 2;
    private static final long TEST_TX_BAD = 3;
    private static final long TEST_RX_SUCCESS = 4;
    private static final String TEST_PACKAGE_NAME = "com.test.example";

    /**
     *  Verify parcel write/read with WifiInfo.
     */
    @Test
    public void testWifiInfoParcelWriteRead() throws Exception {
        WifiInfo writeWifiInfo = new WifiInfo();
        writeWifiInfo.txSuccess = TEST_TX_SUCCESS;
        writeWifiInfo.txRetries = TEST_TX_RETRIES;
        writeWifiInfo.txBad = TEST_TX_BAD;
        writeWifiInfo.rxSuccess = TEST_RX_SUCCESS;
        writeWifiInfo.setTrusted(true);
        writeWifiInfo.setOsuAp(true);
        writeWifiInfo.setNetworkSuggestionOrSpecifierPackageName(TEST_PACKAGE_NAME);

        Parcel parcel = Parcel.obtain();
        writeWifiInfo.writeToParcel(parcel, 0);
        // Rewind the pointer to the head of the parcel.
        parcel.setDataPosition(0);
        WifiInfo readWifiInfo = WifiInfo.CREATOR.createFromParcel(parcel);

        assertNotNull(readWifiInfo);
        assertEquals(TEST_TX_SUCCESS, readWifiInfo.txSuccess);
        assertEquals(TEST_TX_RETRIES, readWifiInfo.txRetries);
        assertEquals(TEST_TX_BAD, readWifiInfo.txBad);
        assertEquals(TEST_RX_SUCCESS, readWifiInfo.rxSuccess);
        assertTrue(readWifiInfo.isTrusted());
        assertTrue(readWifiInfo.isOsuAp());
        assertEquals(TEST_PACKAGE_NAME, readWifiInfo.getNetworkSuggestionOrSpecifierPackageName());
    }
}
