/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.net.vcn;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;

import java.util.List;

/**
 * Utility class for VCN callers get information from VCN network
 *
 * @hide
 */
public class VcnUtils {
    /** Get the WifiInfo of the VCN's underlying WiFi network */
    @Nullable
    public static WifiInfo getWifiInfoFromVcnCaps(
            @NonNull ConnectivityManager connectivityMgr,
            @NonNull NetworkCapabilities networkCapabilities) {
        final NetworkCapabilities underlyingCaps =
                getVcnUnderlyingCaps(connectivityMgr, networkCapabilities);

        if (underlyingCaps == null) {
            return null;
        }

        final TransportInfo underlyingTransportInfo = underlyingCaps.getTransportInfo();
        if (!(underlyingTransportInfo instanceof WifiInfo)) {
            return null;
        }

        return (WifiInfo) underlyingTransportInfo;
    }

    /** Get the subscription ID of the VCN's underlying Cell network */
    public static int getSubIdFromVcnCaps(
            @NonNull ConnectivityManager connectivityMgr,
            @NonNull NetworkCapabilities networkCapabilities) {
        final NetworkCapabilities underlyingCaps =
                getVcnUnderlyingCaps(connectivityMgr, networkCapabilities);

        if (underlyingCaps == null) {
            return INVALID_SUBSCRIPTION_ID;
        }

        final NetworkSpecifier underlyingNetworkSpecifier = underlyingCaps.getNetworkSpecifier();
        if (!(underlyingNetworkSpecifier instanceof TelephonyNetworkSpecifier)) {
            return INVALID_SUBSCRIPTION_ID;
        }

        return ((TelephonyNetworkSpecifier) underlyingNetworkSpecifier).getSubscriptionId();
    }

    @Nullable
    private static NetworkCapabilities getVcnUnderlyingCaps(
            @NonNull ConnectivityManager connectivityMgr,
            @NonNull NetworkCapabilities networkCapabilities) {
        // Return null if it is not a VCN network
        if (networkCapabilities.getTransportInfo() == null
                || !(networkCapabilities.getTransportInfo() instanceof VcnTransportInfo)) {
            return null;
        }

        // As of Android 16, VCN has one underlying network, and only one. If there are more
        // than one networks due to future changes in the VCN mainline code, just take the first
        // network
        final List<Network> underlyingNws = networkCapabilities.getUnderlyingNetworks();
        if (underlyingNws == null) {
            return null;
        }

        return connectivityMgr.getNetworkCapabilities(underlyingNws.get(0));
    }
}
