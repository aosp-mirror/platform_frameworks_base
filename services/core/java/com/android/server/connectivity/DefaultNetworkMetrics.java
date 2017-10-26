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

package com.android.server.connectivity;

import android.net.LinkProperties;
import android.net.metrics.DefaultNetworkEvent;
import android.net.metrics.IpConnectivityLog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.connectivity.metrics.nano.IpConnectivityLogClass.IpConnectivityEvent;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks events related to the default network for the purpose of default network metrics.
 * {@hide}
 */
public class DefaultNetworkMetrics {

    private static final int ROLLING_LOG_SIZE = 64;

    // Event buffer used for metrics upload. The buffer is cleared when events are collected.
    @GuardedBy("this")
    private final List<DefaultNetworkEvent> mEvents = new ArrayList<>();

    public synchronized void listEvents(PrintWriter pw) {
        long localTimeMs = System.currentTimeMillis();
        for (DefaultNetworkEvent ev : mEvents) {
            pw.println(ev);
        }
    }

    public synchronized void listEventsAsProto(PrintWriter pw) {
        for (DefaultNetworkEvent ev : mEvents) {
            pw.print(IpConnectivityEventBuilder.toProto(ev));
        }
    }

    public synchronized void flushEvents(List<IpConnectivityEvent> out) {
        for (DefaultNetworkEvent ev : mEvents) {
            out.add(IpConnectivityEventBuilder.toProto(ev));
        }
        mEvents.clear();
    }

    public synchronized void logDefaultNetworkEvent(
            NetworkAgentInfo newNai, NetworkAgentInfo prevNai) {
        DefaultNetworkEvent ev = new DefaultNetworkEvent();
        if (newNai != null) {
            ev.netId = newNai.network().netId;
            ev.transportTypes = newNai.networkCapabilities.getTransportTypes();
        }
        if (prevNai != null) {
            ev.prevNetId = prevNai.network().netId;
            final LinkProperties lp = prevNai.linkProperties;
            ev.prevIPv4 = lp.hasIPv4Address() && lp.hasIPv4DefaultRoute();
            ev.prevIPv6 = lp.hasGlobalIPv6Address() && lp.hasIPv6DefaultRoute();
        }

        mEvents.add(ev);
    }
}
