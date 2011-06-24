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

package com.android.internal.util;

/**
 * This class defines Message.what base addresses for various protocols that are recognized
 * to be unique by any {@link com.android.internal.util.Statemachine} implementation. This
 * allows for interaction between different StateMachine implementations without a conflict
 * of message codes.
 *
 * As an example, all messages in {@link android.net.wifi.WifiStateMachine} will have message
 * codes with Message.what starting at Protocol.WIFI + 1 and less than or equal to Protocol.WIFI +
 * Protocol.MAX_MESSAGE
 *
 * NOTE: After a value is created and source released a value shouldn't be changed to
 * maintain backwards compatibility.
 *
 * {@hide}
 */
public class Protocol {
    public static final int MAX_MESSAGE                                             = 0x0000FFFF;

    /** Base reserved for system */
    public static final int BASE_SYSTEM_RESERVED                                    = 0x00010000;
    public static final int BASE_SYSTEM_ASYNC_CHANNEL                               = 0x00011000;

    /** Non system protocols */
    public static final int BASE_WIFI                                               = 0x00020000;
    public static final int BASE_WIFI_WATCHDOG                                      = 0x00021000;
    public static final int BASE_WIFI_P2P_MANAGER                                   = 0x00022000;
    public static final int BASE_WIFI_P2P_SERVICE                                   = 0x00023000;
    public static final int BASE_WIFI_MONITOR                                       = 0x00024000;
    public static final int BASE_DHCP                                               = 0x00030000;
    public static final int BASE_DATA_CONNECTION                                    = 0x00040000;
    public static final int BASE_DATA_CONNECTION_AC                                 = 0x00041000;
    public static final int BASE_DATA_CONNECTION_TRACKER                            = 0x00042000;

    //TODO: define all used protocols
}
