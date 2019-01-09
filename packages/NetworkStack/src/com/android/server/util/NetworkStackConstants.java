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

package com.android.server.util;

/**
 * Network constants used by the network stack.
 */
public final class NetworkStackConstants {

    /**
     * IPv4 constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc791
     */
    public static final int IPV4_ADDR_BITS = 32;
    public static final int IPV4_MIN_MTU = 68;
    public static final int IPV4_MAX_MTU = 65_535;

    /**
     * DHCP constants.
     *
     * See also:
     *     - https://tools.ietf.org/html/rfc2131
     */
    public static final int INFINITE_LEASE = 0xffffffff;

    private NetworkStackConstants() {
        throw new UnsupportedOperationException("This class is not to be instantiated");
    }
}
