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
 * limitations under the License
 */

package android.net;

import android.os.Parcel;

import junit.framework.TestCase;

public class NetworkKeyTest extends TestCase {
    public void testValidWifiKey_utf8() {
        new WifiKey("\"quotedSsid\"", "AB:CD:01:EF:23:03");
        new WifiKey("\"\"", "AB:CD:01:EF:23:03");
    }

    public void testValidWifiKey_hex() {
        new WifiKey("0x1234abcd", "AB:CD:01:EF:23:03");
    }

    public void testInvalidWifiKey_empty() {
        try {
            new WifiKey("", "AB:CD:01:EF:23:03");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected - empty SSID
        }
    }

    public void testInvalidWifiKey_unquotedUtf8() {
        try {
            new WifiKey("unquotedSsid", "AB:CD:01:EF:23:03");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected - empty SSID
        }
    }

    public void testInvalidWifiKey_invalidHex() {
        try {
            new WifiKey("0x\"nothex\"", "AB:CD:01:EF:23:03");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected - empty SSID
        }
    }

    public void testInvalidWifiKey_shortBssid() {
        try {
            new WifiKey("\"quotedSsid\"", "AB:CD:01:EF:23");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected - BSSID too short
        }
    }

    public void testInvalidWifiKey_longBssid() {
        try {
            new WifiKey("\"quotedSsid\"", "AB:CD:01:EF:23:03:11");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected - BSSID too long
        }
    }

    public void testParceling() {
        WifiKey wifiKey = new WifiKey("\"ssid\"", "00:00:00:00:00:00");
        NetworkKey networkKey = new NetworkKey(wifiKey);
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeParcelable(networkKey, 0);
            parcel.setDataPosition(0);
            networkKey = parcel.readParcelable(getClass().getClassLoader());
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }

        assertEquals(NetworkKey.TYPE_WIFI, networkKey.type);
        assertEquals("\"ssid\"", networkKey.wifiKey.ssid);
        assertEquals("00:00:00:00:00:00", networkKey.wifiKey.bssid);
    }
}
