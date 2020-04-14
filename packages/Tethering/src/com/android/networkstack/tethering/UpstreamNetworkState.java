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
package com.android.networkstack.tethering;

import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;

/**
 * Snapshot of tethering upstream network state.
 */
public class UpstreamNetworkState {
    /** {@link LinkProperties}. */
    public final LinkProperties linkProperties;
    /** {@link NetworkCapabilities}. */
    public final NetworkCapabilities networkCapabilities;
    /** {@link Network}. */
    public final Network network;

    /** Constructs a new UpstreamNetworkState. */
    public UpstreamNetworkState(LinkProperties linkProperties,
            NetworkCapabilities networkCapabilities, Network network) {
        this.linkProperties = linkProperties;
        this.networkCapabilities = networkCapabilities;
        this.network = network;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format("UpstreamNetworkState{%s, %s, %s}",
                network == null ? "null" : network,
                networkCapabilities == null ? "null" : networkCapabilities,
                linkProperties == null ? "null" : linkProperties);
    }
}
