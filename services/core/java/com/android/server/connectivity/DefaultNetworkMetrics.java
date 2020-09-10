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
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.BitUtils;
import com.android.internal.util.RingBuffer;
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

    public final long creationTimeMs = SystemClock.elapsedRealtime();

    // Event buffer used for metrics upload. The buffer is cleared when events are collected.
    @GuardedBy("this")
    private final List<DefaultNetworkEvent> mEvents = new ArrayList<>();

    // Rolling event buffer used for dumpsys and bugreports.
    @GuardedBy("this")
    private final RingBuffer<DefaultNetworkEvent> mEventsLog =
            new RingBuffer(DefaultNetworkEvent.class, ROLLING_LOG_SIZE);

    // Information about the current status of the default network.
    @GuardedBy("this")
    private DefaultNetworkEvent mCurrentDefaultNetwork;
    // True if the current default network has been validated.
    @GuardedBy("this")
    private boolean mIsCurrentlyValid;
    @GuardedBy("this")
    private long mLastValidationTimeMs;
    // Transport information about the last default network.
    @GuardedBy("this")
    private int mLastTransports;

    public DefaultNetworkMetrics() {
        newDefaultNetwork(creationTimeMs, null);
    }

    public synchronized void listEvents(PrintWriter pw) {
        pw.println("default network events:");
        long localTimeMs = System.currentTimeMillis();
        long timeMs = SystemClock.elapsedRealtime();
        for (DefaultNetworkEvent ev : mEventsLog.toArray()) {
            printEvent(localTimeMs, pw, ev);
        }
        mCurrentDefaultNetwork.updateDuration(timeMs);
        // When printing default network events for bug reports, update validation time
        // and refresh the last validation timestmap for future validation time updates.
        if (mIsCurrentlyValid) {
            updateValidationTime(timeMs);
            mLastValidationTimeMs = timeMs;
        }
        printEvent(localTimeMs, pw, mCurrentDefaultNetwork);
    }

    /**
     * Convert events in the ring buffer to a list of IpConnectivityEvent protos
     */
    public synchronized List<IpConnectivityEvent> listEventsAsProto() {
        List<IpConnectivityEvent> list = new ArrayList<>();
        for (DefaultNetworkEvent ev : mEventsLog.toArray()) {
            list.add(IpConnectivityEventBuilder.toProto(ev));
        }
        return list;
    }

    public synchronized void flushEvents(List<IpConnectivityEvent> out) {
        for (DefaultNetworkEvent ev : mEvents) {
            out.add(IpConnectivityEventBuilder.toProto(ev));
        }
        mEvents.clear();
    }

    public synchronized void logDefaultNetworkValidity(long timeMs, boolean isValid) {
        // Transition from valid to invalid: update validity duration since last update
        if (!isValid && mIsCurrentlyValid) {
            mIsCurrentlyValid = false;
            updateValidationTime(timeMs);
        }

        // Transition from invalid to valid: simply mark the validation timestamp.
        if (isValid && !mIsCurrentlyValid) {
            mIsCurrentlyValid = true;
            mLastValidationTimeMs = timeMs;
        }
    }

    private void updateValidationTime(long timeMs) {
        mCurrentDefaultNetwork.validatedMs += timeMs - mLastValidationTimeMs;
    }

    public synchronized void logDefaultNetworkEvent(
            long timeMs, NetworkAgentInfo newNai, NetworkAgentInfo oldNai) {
        logCurrentDefaultNetwork(timeMs, oldNai);
        newDefaultNetwork(timeMs, newNai);
    }

    private void logCurrentDefaultNetwork(long timeMs, NetworkAgentInfo oldNai) {
        if (mIsCurrentlyValid) {
            updateValidationTime(timeMs);
        }
        DefaultNetworkEvent ev = mCurrentDefaultNetwork;
        ev.updateDuration(timeMs);
        ev.previousTransports = mLastTransports;
        // oldNai is null if the system had no default network before the transition.
        if (oldNai != null) {
            // The system acquired a new default network.
            fillLinkInfo(ev, oldNai);
            ev.finalScore = oldNai.getCurrentScore();
        }
        // Only change transport of the previous default network if the event currently logged
        // corresponds to an existing default network, and not to the absence of a default network.
        // This allows to log pairs of transports for successive default networks regardless of
        // whether or not the system experienced a period without any default network.
        if (ev.transports != 0) {
            mLastTransports = ev.transports;
        }
        mEvents.add(ev);
        mEventsLog.append(ev);
    }

    private void newDefaultNetwork(long timeMs, NetworkAgentInfo newNai) {
        DefaultNetworkEvent ev = new DefaultNetworkEvent(timeMs);
        ev.durationMs = timeMs;
        // newNai is null if the system has no default network after the transition.
        if (newNai != null) {
            fillLinkInfo(ev, newNai);
            ev.initialScore = newNai.getCurrentScore();
            if (newNai.lastValidated) {
                mIsCurrentlyValid = true;
                mLastValidationTimeMs = timeMs;
            }
        } else {
            mIsCurrentlyValid = false;
        }
        mCurrentDefaultNetwork = ev;
    }

    private static void fillLinkInfo(DefaultNetworkEvent ev, NetworkAgentInfo nai) {
        LinkProperties lp = nai.linkProperties;
        ev.netId = nai.network().netId;
        ev.transports |= BitUtils.packBits(nai.networkCapabilities.getTransportTypes());
        ev.ipv4 |= lp.hasIpv4Address() && lp.hasIpv4DefaultRoute();
        ev.ipv6 |= lp.hasGlobalIpv6Address() && lp.hasIpv6DefaultRoute();
    }

    private static void printEvent(long localTimeMs, PrintWriter pw, DefaultNetworkEvent ev) {
        long localCreationTimeMs = localTimeMs - ev.durationMs;
        pw.println(String.format("%tT.%tL: %s", localCreationTimeMs, localCreationTimeMs, ev));
    }
}
