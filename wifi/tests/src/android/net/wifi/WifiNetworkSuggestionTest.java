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

import static org.junit.Assert.*;

import android.os.Parcel;
import android.support.test.filters.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.WifiNetworkSuggestion}.
 */
@SmallTest
public class WifiNetworkSuggestionTest {
    private static final String TEST_SSID = "\"Test123\"";
    private static final String TEST_SSID_1 = "\"Test1234\"";

    /**
     * Check that parcel marshalling/unmarshalling works
     */
    @Test
    public void testWifiNetworkSuggestionParcel() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, true, 0);

        Parcel parcelW = Parcel.obtain();
        suggestion.writeToParcel(parcelW, 0);
        byte[] bytes = parcelW.marshall();
        parcelW.recycle();

        Parcel parcelR = Parcel.obtain();
        parcelR.unmarshall(bytes, 0, bytes.length);
        parcelR.setDataPosition(0);
        WifiNetworkSuggestion parcelSuggestion =
                WifiNetworkSuggestion.CREATOR.createFromParcel(parcelR);

        // Two suggestion objects are considered equal if they point to the same network (i.e same
        // SSID + keyMgmt + same UID). |isAppInteractionRequired| & |isUserInteractionRequired| are
        // not considered for equality and hence needs to be checked for explicitly below.
        assertEquals(suggestion, parcelSuggestion);
        assertEquals(suggestion.isAppInteractionRequired,
                parcelSuggestion.isAppInteractionRequired);
        assertEquals(suggestion.isUserInteractionRequired,
                parcelSuggestion.isUserInteractionRequired);
    }

    /**
     * Check NetworkSuggestion equals returns {@code true} for 2 network suggestions with the same
     * SSID, key mgmt and UID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsSame() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, true, false, 0);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, true, 0);

        assertEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * key mgmt and UID, but different SSID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenSsidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, 0);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID_1;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, false, 0);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID and UID, but different key mgmt.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenKeyMgmtIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, 0);

        WifiConfiguration configuration1 = new WifiConfiguration();
        configuration1.SSID = TEST_SSID;
        configuration1.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration1, false, false, 0);

        assertNotEquals(suggestion, suggestion1);
    }

    /**
     * Check NetworkSuggestion equals returns {@code false} for 2 network suggestions with the same
     * SSID and key mgmt, but different UID.
     */
    @Test
    public void testWifiNetworkSuggestionEqualsFailsWhenUidIsDifferent() {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = TEST_SSID;
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        WifiNetworkSuggestion suggestion =
                new WifiNetworkSuggestion(configuration, false, false, 0);

        WifiNetworkSuggestion suggestion1 =
                new WifiNetworkSuggestion(configuration, false, false, 1);

        assertNotEquals(suggestion, suggestion1);
    }
}
