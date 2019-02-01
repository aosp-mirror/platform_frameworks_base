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

package android.net.util;

import static com.android.internal.util.Preconditions.checkArgument;

import android.net.MacAddress;
import android.text.TextUtils;

import java.net.NetworkInterface;
import java.net.SocketException;


/**
 * Encapsulate the interface parameters common to IpClient/IpServer components.
 *
 * Basically all java.net.NetworkInterface methods throw Exceptions. IpClient
 * and IpServer (sub)components need most or all of this information at some
 * point during their lifecycles, so pass only this simplified object around
 * which can be created once when IpClient/IpServer are told to start.
 *
 * @hide
 */
public class InterfaceParams {
    public final String name;
    public final int index;
    public final MacAddress macAddr;
    public final int defaultMtu;

    // TODO: move the below to NetworkStackConstants when this class is moved to the NetworkStack.
    private static final int ETHER_MTU = 1500;
    private static final int IPV6_MIN_MTU = 1280;


    public static InterfaceParams getByName(String name) {
        final NetworkInterface netif = getNetworkInterfaceByName(name);
        if (netif == null) return null;

        // Not all interfaces have MAC addresses, e.g. rmnet_data0.
        final MacAddress macAddr = getMacAddress(netif);

        try {
            return new InterfaceParams(name, netif.getIndex(), macAddr, netif.getMTU());
        } catch (IllegalArgumentException|SocketException e) {
            return null;
        }
    }

    public InterfaceParams(String name, int index, MacAddress macAddr) {
        this(name, index, macAddr, ETHER_MTU);
    }

    public InterfaceParams(String name, int index, MacAddress macAddr, int defaultMtu) {
        checkArgument((!TextUtils.isEmpty(name)), "impossible interface name");
        checkArgument((index > 0), "invalid interface index");
        this.name = name;
        this.index = index;
        this.macAddr = (macAddr != null) ? macAddr : MacAddress.fromBytes(new byte[] {
                0x02, 0x00, 0x00, 0x00, 0x00, 0x00 });
        this.defaultMtu = (defaultMtu > IPV6_MIN_MTU) ? defaultMtu : IPV6_MIN_MTU;
    }

    @Override
    public String toString() {
        return String.format("%s/%d/%s/%d", name, index, macAddr, defaultMtu);
    }

    private static NetworkInterface getNetworkInterfaceByName(String name) {
        try {
            return NetworkInterface.getByName(name);
        } catch (NullPointerException|SocketException e) {
            return null;
        }
    }

    private static MacAddress getMacAddress(NetworkInterface netif) {
        try {
            return MacAddress.fromBytes(netif.getHardwareAddress());
        } catch (IllegalArgumentException|NullPointerException|SocketException e) {
            return null;
        }
    }
}
