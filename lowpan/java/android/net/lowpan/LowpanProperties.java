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

package android.net.lowpan;

import android.net.LinkAddress;
import android.net.RouteInfo;
import java.util.List;

/** {@hide} */
public final class LowpanProperties {

    public static final LowpanProperty<Boolean> KEY_INTERFACE_ENABLED =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.INTERFACE_ENABLED", Boolean.class);
    public static final LowpanProperty<Boolean> KEY_INTERFACE_COMMISSIONED =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.INTERFACE_COMMISSIONED", Boolean.class);
    public static final LowpanProperty<Boolean> KEY_INTERFACE_CONNECTED =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.INTERFACE_CONNECTED", Boolean.class);
    public static final LowpanProperty<Boolean> KEY_INTERFACE_UP =
            new LowpanStandardProperty("android.net.lowpan.property.INTERFACE_UP", Boolean.class);
    public static final LowpanProperty<String> KEY_INTERFACE_STATE =
            new LowpanStandardProperty("android.net.lowpan.property.INTERFACE_STATE", String.class);

    public static final LowpanProperty<String> KEY_NETWORK_NAME =
            new LowpanStandardProperty("android.net.lowpan.property.NETWORK_NAME", Boolean.class);
    public static final LowpanProperty<Integer> KEY_NETWORK_PANID =
            new LowpanStandardProperty("android.net.lowpan.property.NETWORK_PANID", Integer.class);
    public static final LowpanProperty<byte[]> KEY_NETWORK_XPANID =
            new LowpanStandardProperty("android.net.lowpan.property.NETWORK_XPANID", byte[].class);
    public static final LowpanProperty<byte[]> KEY_NETWORK_MASTER_KEY =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.NETWORK_MASTER_KEY", byte[].class);
    public static final LowpanProperty<Integer> KEY_NETWORK_MASTER_KEY_INDEX =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.NETWORK_MASTER_KEY_INDEX", Integer.class);
    public static final LowpanProperty<Integer> KEY_NETWORK_TYPE =
            new LowpanStandardProperty("android.net.lowpan.property.NETWORK_TYPE", Integer.class);
    public static final LowpanProperty<String> KEY_NETWORK_ROLE =
            new LowpanStandardProperty("android.net.lowpan.property.NETWORK_ROLE", String.class);

    public static final LowpanProperty<Integer> KEY_CHANNEL =
            new LowpanStandardProperty("android.net.lowpan.property.CHANNEL", Integer.class);
    public static final LowpanProperty<int[]> KEY_CHANNEL_MASK =
            new LowpanStandardProperty("android.net.lowpan.property.CHANNEL_MASK", int[].class);
    public static final LowpanProperty<Integer> KEY_MAX_TX_POWER =
            new LowpanStandardProperty("android.net.lowpan.property.MAX_TX_POWER", Integer.class);
    public static final LowpanProperty<Integer> KEY_RSSI =
            new LowpanStandardProperty("android.net.lowpan.property.RSSI", Integer.class);

    public static final LowpanProperty<Integer> KEY_LQI =
            new LowpanStandardProperty("android.net.lowpan.property.LQI", Integer.class);
    public static final LowpanProperty<byte[]> KEY_BEACON_ADDRESS =
            new LowpanStandardProperty("android.net.lowpan.property.BEACON_ADDRESS", byte[].class);
    public static final LowpanProperty<Boolean> KEY_BEACON_CAN_ASSIST =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.BEACON_CAN_ASSIST", Boolean.class);

    public static final LowpanProperty<String> KEY_DRIVER_VERSION =
            new LowpanStandardProperty("android.net.lowpan.property.DRIVER_VERSION", String.class);

    public static final LowpanProperty<String> KEY_NCP_VERSION =
            new LowpanStandardProperty("android.net.lowpan.property.NCP_VERSION", String.class);

    public static final LowpanProperty<List<LinkAddress>> KEY_LINK_ADDRESS_ARRAY =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.LINK_ADDRESS_ARRAY", LinkAddress[].class);

    public static final LowpanProperty<List<RouteInfo>> KEY_ROUTE_INFO_ARRAY =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.ROUTE_INFO_ARRAY", RouteInfo[].class);

    /** @hide */
    public static final LowpanProperty<byte[]> KEY_EXTENDED_ADDRESS =
            new LowpanStandardProperty(
                    "android.net.lowpan.property.EXTENDED_ADDRESS", byte[].class);

    /** @hide */
    public static final LowpanProperty<byte[]> KEY_MAC_ADDRESS =
            new LowpanStandardProperty("android.net.lowpan.property.MAC_ADDRESS", byte[].class);

    /** @hide */
    private LowpanProperties() {}

    /** @hide */
    static final class LowpanStandardProperty<T> extends LowpanProperty<T> {
        private final String mName;
        private final Class<T> mType;

        LowpanStandardProperty(String name, Class<T> type) {
            mName = name;
            mType = type;
        }

        @Override
        public String getName() {
            return mName;
        }

        @Override
        public Class<T> getType() {
            return mType;
        }

        @Override
        public String toString() {
            return getName();
        }
    }
}
