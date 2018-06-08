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
 * {@link DiscoverySessionCallback#onServiceDiscovered(PeerHandle, byte[], java.util.List)} or
 * received messages in {@link DiscoverySessionCallback#onMessageReceived(PeerHandle, byte[])}, and
 * used when sending messages e,g, {@link DiscoverySession#sendMessage(PeerHandle, int, byte[])},
 * or when configuring a network link to a peer, e.g.
 * {@link DiscoverySession#createNetworkSpecifierOpen(PeerHandle)} or
 * {@link DiscoverySession#createNetworkSpecifierPassphrase(PeerHandle, String)}.
 * <p>
 * Note that while a {@code PeerHandle} can be used to track a particular peer (i.e. you can compare
 * the values received from subsequent messages) - it is good practice not to rely on it. Instead
 * use an application level peer identifier encoded in the message,
 * {@link DiscoverySession#sendMessage(PeerHandle, int, byte[])}, and/or in the Publish
 * configuration's service-specific information field,
 * {@link PublishConfig.Builder#setServiceSpecificInfo(byte[])}, or match filter,
 * {@link PublishConfig.Builder#setMatchFilter(java.util.List)}.
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
