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

package com.android.server;

import android.net.LinkAddress;
import android.net.RouteInfo;

/**
 * Observer for network events, to use with {@link NetworkObserverRegistry}.
 */
public interface NetworkObserver {

    /**
     * @see android.net.INetdUnsolicitedEventListener#onInterfaceChanged(java.lang.String, boolean)
     */
    default void onInterfaceChanged(String ifName, boolean up) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener#onInterfaceRemoved(String)
     */
    default void onInterfaceRemoved(String ifName) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener
     *          #onInterfaceAddressUpdated(String, String, int, int)
     */
    default void onInterfaceAddressUpdated(LinkAddress address, String ifName) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener
     *          #onInterfaceAddressRemoved(String, String, int, int)
     */
    default void onInterfaceAddressRemoved(LinkAddress address, String ifName) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener#onInterfaceLinkStateChanged(String, boolean)
     */
    default void onInterfaceLinkStateChanged(String ifName, boolean up) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener#onInterfaceAdded(String)
     */
    default void onInterfaceAdded(String ifName) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener
     *          #onInterfaceClassActivityChanged(boolean, int, long, int)
     */
    default void onInterfaceClassActivityChanged(
            boolean isActive, int label, long timestamp, int uid) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener#onQuotaLimitReached(String, String)
     */
    default void onQuotaLimitReached(String alertName, String ifName) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener
     *          #onInterfaceDnsServerInfo(String, long, String[])
     */
    default void onInterfaceDnsServerInfo(String ifName, long lifetime, String[] servers) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener
     *          #onRouteChanged(boolean, String, String, String)
     */
    default void onRouteUpdated(RouteInfo route) {}

    /**
     * @see android.net.INetdUnsolicitedEventListener
     *          #onRouteChanged(boolean, String, String, String)
     */
    default void onRouteRemoved(RouteInfo route) {}
}
