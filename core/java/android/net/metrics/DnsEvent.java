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

import android.net.NetworkCapabilities;
import java.util.Arrays;
import com.android.internal.util.BitUtils;

/**
 * A DNS event recorded by NetdEventListenerService.
 * {@hide}
 */
final public class DnsEvent {

    private static final int SIZE_LIMIT = 20000;

    // Network id of the network associated with the event, or 0 if unspecified.
    public final int netId;
    // Transports of the network associated with the event, as defined in NetworkCapabilities.
    // It is the caller responsability to ensure the value of transports does not change between
    // calls to addResult.
    public final long transports;
    // The number of DNS queries recorded. Queries are stored in the structure-of-array style where
    // the eventTypes, returnCodes, and latenciesMs arrays have the same length and the i-th event
    // is spread across the three array at position i.
    public int eventCount;
    // The types of DNS queries as defined in INetdEventListener.
    public byte[] eventTypes;
    // Current getaddrinfo codes go from 1 to EAI_MAX = 15. gethostbyname returns errno, but there
    // are fewer than 255 errno values. So we store the result code in a byte as well.
    public byte[] returnCodes;
    // Latencies in milliseconds of queries, stored as ints.
    public int[] latenciesMs;

    public DnsEvent(int netId, long transports, int initialCapacity) {
        this.netId = netId;
        this.transports = transports;
        eventTypes = new byte[initialCapacity];
        returnCodes = new byte[initialCapacity];
        latenciesMs = new int[initialCapacity];
    }

    public void addResult(byte eventType, byte returnCode, int latencyMs) {
        if (eventCount >= SIZE_LIMIT) {
            // TODO: implement better rate limiting that does not biases metrics.
            return;
        }
        if (eventCount == eventTypes.length) {
            resize((int) (1.4 * eventCount));
        }
        eventTypes[eventCount] = eventType;
        returnCodes[eventCount] = returnCode;
        latenciesMs[eventCount] = latencyMs;
        eventCount++;
    }

    public void resize(int newLength) {
        eventTypes = Arrays.copyOf(eventTypes, newLength);
        returnCodes = Arrays.copyOf(returnCodes, newLength);
        latenciesMs = Arrays.copyOf(latenciesMs, newLength);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("DnsEvent(").append(netId).append(", ");
        for (int t : BitUtils.unpackBits(transports)) {
            builder.append(NetworkCapabilities.transportNameOf(t)).append(", ");
        }
        return builder.append(eventCount).append(" events)").toString();
    }
}
