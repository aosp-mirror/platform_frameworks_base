/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.nfc_extras;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.nfc.INfcAdapterExtras;
import android.nfc.NfcAdapter;
import android.os.RemoteException;
import android.util.Log;

/**
 * Provides additional methods on an {@link NfcAdapter} for Card Emulation
 * and management of {@link NfcExecutionEnvironment}'s.
 *
 * There is a 1-1 relationship between an {@link NfcAdapterExtras} object and
 * a {@link NfcAdapter} object.
 */
public final class NfcAdapterExtras {
    private static final String TAG = "NfcAdapterExtras";

    /**
     * Broadcast Action: an RF field ON has been detected.
     *
     * <p class="note">This is an unreliable signal, and will be removed.
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission
     * to receive.
     */
    public static final String ACTION_RF_FIELD_ON_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_ON_DETECTED";

    /**
     * Broadcast Action: an RF field OFF has been detected.
     *
     * <p class="note">This is an unreliable signal, and will be removed.
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission
     * to receive.
     */
    public static final String ACTION_RF_FIELD_OFF_DETECTED =
            "com.android.nfc_extras.action.RF_FIELD_OFF_DETECTED";

    // protected by NfcAdapterExtras.class, and final after first construction,
    // except for attemptDeadServiceRecovery() when NFC crashes - we accept a
    // best effort recovery
    private static NfcAdapter sAdapter;
    private static INfcAdapterExtras sService;
    private static NfcAdapterExtras sSingleton;
    private static NfcExecutionEnvironment sEmbeddedEe;
    private static CardEmulationRoute sRouteOff;
    private static CardEmulationRoute sRouteOnWhenScreenOn;

    /** get service handles */
    private static void initService() {
        final INfcAdapterExtras service = sAdapter.getNfcAdapterExtrasInterface();
        if (service != null) {
            // Leave stale rather than receive a null value.
            sService = service;
        }
    }

    /**
     * Get the {@link NfcAdapterExtras} for the given {@link NfcAdapter}.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @param adapter a {@link NfcAdapter}, must not be null
     * @return the {@link NfcAdapterExtras} object for the given {@link NfcAdapter}
     */
    public static NfcAdapterExtras get(NfcAdapter adapter) {
        synchronized(NfcAdapterExtras.class) {
            if (sSingleton == null) {
                try {
                    sAdapter = adapter;
                    sSingleton = new NfcAdapterExtras();
                    sEmbeddedEe = new NfcExecutionEnvironment(sSingleton);
                    sRouteOff = new CardEmulationRoute(CardEmulationRoute.ROUTE_OFF, null);
                    sRouteOnWhenScreenOn = new CardEmulationRoute(
                            CardEmulationRoute.ROUTE_ON_WHEN_SCREEN_ON, sEmbeddedEe);
                    initService();
                } finally {
                    if (sService == null) {
                        sRouteOnWhenScreenOn = null;
                        sRouteOff = null;
                        sEmbeddedEe = null;
                        sSingleton = null;
                        sAdapter = null;
                    }
                }
            }
            return sSingleton;
        }
    }

    private NfcAdapterExtras() {}

    /**
     * Immutable data class that describes a card emulation route.
     */
    public final static class CardEmulationRoute {
        /**
         * Card Emulation is turned off on this NfcAdapter.
         * <p>This is the default routing state after boot.
         */
        public static final int ROUTE_OFF = 1;

        /**
         * Card Emulation is routed to {@link #nfcEe} only when the screen is on,
         * otherwise it is turned off.
         */
        public static final int ROUTE_ON_WHEN_SCREEN_ON = 2;

        /**
         * A route such as {@link #ROUTE_OFF} or {@link #ROUTE_ON_WHEN_SCREEN_ON}.
         */
        public final int route;

        /**
         * The {@link NFcExecutionEnvironment} that is Card Emulation is routed to.
         * <p>null if {@link #route} is {@link #ROUTE_OFF}, otherwise not null.
         */
        public final NfcExecutionEnvironment nfcEe;

        public CardEmulationRoute(int route, NfcExecutionEnvironment nfcEe) {
            if (route == ROUTE_OFF && nfcEe != null) {
                throw new IllegalArgumentException("must not specifiy a NFC-EE with ROUTE_OFF");
            } else if (route != ROUTE_OFF && nfcEe == null) {
                throw new IllegalArgumentException("must specifiy a NFC-EE for this route");
            }
            this.route = route;
            this.nfcEe = nfcEe;
        }
    }

    /**
     * NFC service dead - attempt best effort recovery
     */
    void attemptDeadServiceRecovery(Exception e) {
        Log.e(TAG, "NFC Adapter Extras dead - attempting to recover");
        sAdapter.attemptDeadServiceRecovery(e);
        initService();
    }

    INfcAdapterExtras getService() {
        return sService;
    }

    /**
     * Get the routing state of this NFC EE.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @return
     */
    public CardEmulationRoute getCardEmulationRoute() {
        try {
            int route = sService.getCardEmulationRoute();
            return route == CardEmulationRoute.ROUTE_OFF ?
                    sRouteOff :
                    sRouteOnWhenScreenOn;
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
            return sRouteOff;
        }
    }

    /**
     * Set the routing state of this NFC EE.
     *
     * <p>This routing state is not persisted across reboot.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @param route a {@link #CardEmulationRoute}
     */
    public void setCardEmulationRoute(CardEmulationRoute route) {
        try {
            sService.setCardEmulationRoute(route.route);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }

    /**
     * Get the {@link NfcExecutionEnvironment} that is embedded with the
     * {@link NFcAdapter}.
     *
     * <p class="note">
     * Requires the {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission.
     *
     * @return a {@link NfcExecutionEnvironment}, or null if there is no embedded NFC-EE
     */
    public NfcExecutionEnvironment getEmbeddedExecutionEnvironment() {
        return sEmbeddedEe;
    }

    /**
     * Authenticate the client application.
     *
     * Some implementations of NFC Adapter Extras may require applications
     * to authenticate with a token, before using other methods.
     *
     * @param a implementation specific token
     * @throws a {@link java.lang.SecurityException} if authentication failed
     */
    public void authenticate(byte[] token) {
        try {
            sService.authenticate(token);
        } catch (RemoteException e) {
            attemptDeadServiceRecovery(e);
        }
    }
}
