/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nan;

/**
 * Base class for a listener which is called with the MAC address of the NAN interface whenever
 * it is changed. Change may be due to device joining a cluster, starting a cluster, or discovery
 * interface change (addresses are randomized at regular intervals). The implication is that
 * peers you've been communicating with may no longer recognize you and you need to re-establish
 * your identity - e.g. by starting a discovery session. This actual MAC address of the
 * interface may also be useful if the application uses alternative (non-NAN) discovery but needs
 * to set up a NAN connection. The provided NAN discovery interface MAC address can then be used
 * in {@link WifiNanSession#createNetworkSpecifier(int, byte[], byte[])}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanIdentityChangedListener {
    /**
     * @param mac The MAC address of the NAN discovery interface. The application must have the
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} to get the actual MAC address,
     *            otherwise all 0's will be provided.
     */
    public void onIdentityChanged(byte[] mac) {
        /* empty */
    }
}
