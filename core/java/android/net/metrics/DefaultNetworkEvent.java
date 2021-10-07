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

package android.net.metrics;

import java.util.BitSet;
import java.util.StringJoiner;

/**
 * An event recorded by ConnectivityService when there is a change in the default network.
 * {@hide}
 */
public class DefaultNetworkEvent {

    // The creation time in milliseconds of this DefaultNetworkEvent.
    public final long creationTimeMs;
    // The network ID of the network or 0 if none.
    public int netId = 0;
    // The list of transport types, as defined in NetworkCapabilities.java.
    public int transports;
    // The list of transport types of the last previous default network.
    public int previousTransports;
    // Whether the network has IPv4/IPv6 connectivity.
    public boolean ipv4;
    public boolean ipv6;
    // The initial network score when this network became the default network.
    public int initialScore;
    // The initial network score when this network stopped being the default network.
    public int finalScore;
    // The total duration in milliseconds this network was the default network.
    public long durationMs;
    // The total duration in milliseconds this network was the default network and was validated.
    public long validatedMs;

    public DefaultNetworkEvent(long timeMs) {
        creationTimeMs = timeMs;
    }

    /** Update the durationMs of this DefaultNetworkEvent for the given current time. */
    public void updateDuration(long timeMs) {
        durationMs = timeMs - creationTimeMs;
    }

    @Override
    public String toString() {
        StringJoiner j = new StringJoiner(", ", "DefaultNetworkEvent(", ")");
        j.add("netId=" + netId);
        j.add("transports=" + BitSet.valueOf(new long[] { transports }));
        j.add("ip=" + ipSupport());
        if (initialScore > 0) {
            j.add("initial_score=" + initialScore);
        }
        if (finalScore > 0) {
            j.add("final_score=" + finalScore);
        }
        j.add(String.format("duration=%.0fs", durationMs / 1000.0));
        j.add(String.format("validation=%04.1f%%", (validatedMs * 100.0) / durationMs));
        return j.toString();
    }

    private String ipSupport() {
        if (ipv4 && ipv6) {
            return "IPv4v6";
        }
        if (ipv6) {
            return "IPv6";
        }
        if (ipv4) {
            return "IPv4";
        }
        return "NONE";
    }
}
