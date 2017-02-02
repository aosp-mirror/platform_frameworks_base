/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net;

import static android.net.NetworkCapabilities.NET_CAPABILITY_CBS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_DUN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOTA;
import static android.net.NetworkCapabilities.NET_CAPABILITY_IMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_MMS;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_SUPL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P;
import static android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Test;



@RunWith(AndroidJUnit4.class)
@SmallTest
public class ConnectivityManagerTest {
    static NetworkCapabilities verifyNetworkCapabilities(
            int legacyType, int transportType, int... capabilities) {
        final NetworkCapabilities nc = ConnectivityManager.networkCapabilitiesForType(legacyType);
        assertNotNull(nc);
        assertTrue(nc.hasTransport(transportType));
        for (int capability : capabilities) {
            assertTrue(nc.hasCapability(capability));
        }

        return nc;
    }

    static void verifyUnrestrictedNetworkCapabilities(int legacyType, int transportType) {
        verifyNetworkCapabilities(
                legacyType,
                transportType,
                NET_CAPABILITY_INTERNET,
                NET_CAPABILITY_NOT_RESTRICTED,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);
    }

    static void verifyRestrictedMobileNetworkCapabilities(int legacyType, int capability) {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                legacyType,
                TRANSPORT_CELLULAR,
                capability,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
        assertFalse(nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED));
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobile() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE, TRANSPORT_CELLULAR);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileCbs() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_CBS, NET_CAPABILITY_CBS);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileDun() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_DUN, NET_CAPABILITY_DUN);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileFota() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_FOTA, NET_CAPABILITY_FOTA);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileHipri() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_HIPRI, TRANSPORT_CELLULAR);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileIms() {
        verifyRestrictedMobileNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_IMS, NET_CAPABILITY_IMS);
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileMms() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_MMS,
                TRANSPORT_CELLULAR,
                NET_CAPABILITY_MMS,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeMobileSupl() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_MOBILE_SUPL,
                TRANSPORT_CELLULAR,
                NET_CAPABILITY_SUPL,
                NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeWifi() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_WIFI, TRANSPORT_WIFI);
    }

    @Test
    public void testNetworkCapabilitiesForTypeWifiP2p() {
        final NetworkCapabilities nc = verifyNetworkCapabilities(
                ConnectivityManager.TYPE_WIFI_P2P,
                TRANSPORT_WIFI,
                NET_CAPABILITY_NOT_RESTRICTED, NET_CAPABILITY_NOT_VPN,
                NET_CAPABILITY_TRUSTED, NET_CAPABILITY_WIFI_P2P);

        assertFalse(nc.hasCapability(NET_CAPABILITY_INTERNET));
    }

    @Test
    public void testNetworkCapabilitiesForTypeBluetooth() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_BLUETOOTH, TRANSPORT_BLUETOOTH);
    }

    @Test
    public void testNetworkCapabilitiesForTypeEthernet() {
        verifyUnrestrictedNetworkCapabilities(
                ConnectivityManager.TYPE_ETHERNET, TRANSPORT_ETHERNET);
    }
}
