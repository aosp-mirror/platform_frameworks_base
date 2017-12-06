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

package android.net.wifi.aware;

/**
 * Opaque object used to represent a Wi-Fi Aware peer. Obtained from discovery sessions in
 * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle, byte[], java.util.List)}, used
 * when sending messages e,g, {@link DiscoverySession#sendMessage(PeerHandle, int, byte[])},
 * or when configuring a network link to a peer, e.g.
 * {@link DiscoverySession#createNetworkSpecifierOpen(PeerHandle)} or
 * {@link DiscoverySession#createNetworkSpecifierPassphrase(PeerHandle, String)}.
 */
public class PeerHandle {
    /** @hide */
    public PeerHandle(int peerId) {
        this.peerId = peerId;
    }

    /** @hide */
    public int peerId;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PeerHandle)) {
            return false;
        }

        return peerId == ((PeerHandle) o).peerId;
    }

    @Override
    public int hashCode() {
        return peerId;
    }
}
