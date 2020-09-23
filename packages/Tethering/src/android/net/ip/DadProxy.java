/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.ip;

import android.net.util.InterfaceParams;
import android.os.Handler;

import androidx.annotation.VisibleForTesting;

/**
 * Basic Duplicate address detection proxy.
 *
 * @hide
 */
public class DadProxy {
    private static final String TAG = DadProxy.class.getSimpleName();

    @VisibleForTesting
    public static NeighborPacketForwarder naForwarder;
    public static NeighborPacketForwarder nsForwarder;

    public DadProxy(Handler h, InterfaceParams tetheredIface) {
        naForwarder = new NeighborPacketForwarder(h, tetheredIface,
                                        NeighborPacketForwarder.ICMPV6_NEIGHBOR_ADVERTISEMENT);
        nsForwarder = new NeighborPacketForwarder(h, tetheredIface,
                                        NeighborPacketForwarder.ICMPV6_NEIGHBOR_SOLICITATION);
    }

    /** Stop NS/NA Forwarders. */
    public void stop() {
        naForwarder.stop();
        nsForwarder.stop();
    }

    /** Set upstream iface on both forwarders. */
    public void setUpstreamIface(InterfaceParams upstreamIface) {
        naForwarder.setUpstreamIface(upstreamIface);
        nsForwarder.setUpstreamIface(upstreamIface);
    }
}
