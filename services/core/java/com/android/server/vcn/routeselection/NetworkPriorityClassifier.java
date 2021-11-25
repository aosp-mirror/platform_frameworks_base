/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.vcn.routeselection;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static com.android.server.VcnManagementService.LOCAL_LOG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnManager;
import android.os.ParcelUuid;
import android.os.PersistableBundle;
import android.telephony.SubscriptionManager;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.TelephonySubscriptionTracker.TelephonySubscriptionSnapshot;

import java.util.Set;

/** @hide */
class NetworkPriorityClassifier {
    @NonNull private static final String TAG = NetworkPriorityClassifier.class.getSimpleName();
    /**
     * Minimum signal strength for a WiFi network to be eligible for switching to
     *
     * <p>A network that satisfies this is eligible to become the selected underlying network with
     * no additional conditions
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT = -70;
    /**
     * Minimum signal strength to continue using a WiFi network
     *
     * <p>A network that satisfies the conditions may ONLY continue to be used if it is already
     * selected as the underlying network. A WiFi network satisfying this condition, but NOT the
     * prospective-network RSSI threshold CANNOT be switched to.
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int WIFI_EXIT_RSSI_THRESHOLD_DEFAULT = -74;
    /** Priority for any cellular network for which the subscription is listed as opportunistic */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_OPPORTUNISTIC_CELLULAR = 0;
    /** Priority for any WiFi network which is in use, and satisfies the in-use RSSI threshold */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_WIFI_IN_USE = 1;
    /** Priority for any WiFi network which satisfies the prospective-network RSSI threshold */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_WIFI_PROSPECTIVE = 2;
    /** Priority for any standard macro cellular network */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_MACRO_CELLULAR = 3;
    /** Priority for any other networks (including unvalidated, etc) */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int PRIORITY_ANY = Integer.MAX_VALUE;

    private static final SparseArray<String> PRIORITY_TO_STRING_MAP = new SparseArray<>();

    static {
        PRIORITY_TO_STRING_MAP.put(
                PRIORITY_OPPORTUNISTIC_CELLULAR, "PRIORITY_OPPORTUNISTIC_CELLULAR");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_WIFI_IN_USE, "PRIORITY_WIFI_IN_USE");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_WIFI_PROSPECTIVE, "PRIORITY_WIFI_PROSPECTIVE");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_MACRO_CELLULAR, "PRIORITY_MACRO_CELLULAR");
        PRIORITY_TO_STRING_MAP.put(PRIORITY_ANY, "PRIORITY_ANY");
    }

    /**
     * Gives networks a priority class, based on the following priorities:
     *
     * <ol>
     *   <li>Opportunistic cellular
     *   <li>Carrier WiFi, signal strength >= WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT
     *   <li>Carrier WiFi, active network + signal strength >= WIFI_EXIT_RSSI_THRESHOLD_DEFAULT
     *   <li>Macro cellular
     *   <li>Any others
     * </ol>
     */
    static int calculatePriorityClass(
            UnderlyingNetworkRecord networkRecord,
            ParcelUuid subscriptionGroup,
            TelephonySubscriptionSnapshot snapshot,
            UnderlyingNetworkRecord currentlySelected,
            PersistableBundle carrierConfig) {
        final NetworkCapabilities caps = networkRecord.networkCapabilities;

        // mRouteSelectionNetworkRequest requires a network be both VALIDATED and NOT_SUSPENDED

        if (networkRecord.isBlocked) {
            logWtf("Network blocked for System Server: " + networkRecord.network);
            return PRIORITY_ANY;
        }

        if (caps.hasTransport(TRANSPORT_CELLULAR)
                && isOpportunistic(snapshot, caps.getSubscriptionIds())) {
            // If this carrier is the active data provider, ensure that opportunistic is only
            // ever prioritized if it is also the active data subscription. This ensures that
            // if an opportunistic subscription is still in the process of being switched to,
            // or switched away from, the VCN does not attempt to continue using it against the
            // decision made at the telephony layer. Failure to do so may result in the modem
            // switching back and forth.
            //
            // Allow the following two cases:
            // 1. Active subId is NOT in the group that this VCN is supporting
            // 2. This opportunistic subscription is for the active subId
            if (!snapshot.getAllSubIdsInGroup(subscriptionGroup)
                            .contains(SubscriptionManager.getActiveDataSubscriptionId())
                    || caps.getSubscriptionIds()
                            .contains(SubscriptionManager.getActiveDataSubscriptionId())) {
                return PRIORITY_OPPORTUNISTIC_CELLULAR;
            }
        }

        if (caps.hasTransport(TRANSPORT_WIFI)) {
            if (caps.getSignalStrength() >= getWifiExitRssiThreshold(carrierConfig)
                    && currentlySelected != null
                    && networkRecord.network.equals(currentlySelected.network)) {
                return PRIORITY_WIFI_IN_USE;
            }

            if (caps.getSignalStrength() >= getWifiEntryRssiThreshold(carrierConfig)) {
                return PRIORITY_WIFI_PROSPECTIVE;
            }
        }

        // Disallow opportunistic subscriptions from matching PRIORITY_MACRO_CELLULAR, as might
        // be the case when Default Data SubId (CBRS) != Active Data SubId (MACRO), as might be
        // the case if the Default Data SubId does not support certain services (eg voice
        // calling)
        if (caps.hasTransport(TRANSPORT_CELLULAR)
                && !isOpportunistic(snapshot, caps.getSubscriptionIds())) {
            return PRIORITY_MACRO_CELLULAR;
        }

        return PRIORITY_ANY;
    }

    static boolean isOpportunistic(
            @NonNull TelephonySubscriptionSnapshot snapshot, Set<Integer> subIds) {
        if (snapshot == null) {
            logWtf("Got null snapshot");
            return false;
        }
        for (int subId : subIds) {
            if (snapshot.isOpportunistic(subId)) {
                return true;
            }
        }
        return false;
    }

    static int getWifiEntryRssiThreshold(@Nullable PersistableBundle carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_WIFI_ENTRY_RSSI_THRESHOLD_KEY,
                    WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT);
        }
        return WIFI_ENTRY_RSSI_THRESHOLD_DEFAULT;
    }

    static int getWifiExitRssiThreshold(@Nullable PersistableBundle carrierConfig) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(
                    VcnManager.VCN_NETWORK_SELECTION_WIFI_EXIT_RSSI_THRESHOLD_KEY,
                    WIFI_EXIT_RSSI_THRESHOLD_DEFAULT);
        }
        return WIFI_EXIT_RSSI_THRESHOLD_DEFAULT;
    }

    static String priorityClassToString(int priorityClass) {
        return PRIORITY_TO_STRING_MAP.get(priorityClass, "unknown");
    }

    private static void logWtf(String msg) {
        Slog.wtf(TAG, msg);
        LOCAL_LOG.log(TAG + " WTF: " + msg);
    }
}
