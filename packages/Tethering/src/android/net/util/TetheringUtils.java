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
package android.net.util;

import android.net.TetherStatsParcel;
import android.net.TetheringRequestParcel;

import androidx.annotation.NonNull;

import java.io.FileDescriptor;
import java.net.SocketException;
import java.util.Objects;

/**
 * The classes and the methods for tethering utilization.
 *
 * {@hide}
 */
public class TetheringUtils {
    /**
     *  The object which records offload Tx/Rx forwarded bytes/packets.
     *  TODO: Replace the inner class ForwardedStats of class OffloadHardwareInterface with
     *  this class as well.
     */
    public static class ForwardedStats {
        public final long rxBytes;
        public final long rxPackets;
        public final long txBytes;
        public final long txPackets;

        public ForwardedStats() {
            rxBytes = 0;
            rxPackets = 0;
            txBytes = 0;
            txPackets = 0;
        }

        public ForwardedStats(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.rxPackets = 0;
            this.txBytes = txBytes;
            this.txPackets = 0;
        }

        public ForwardedStats(long rxBytes, long rxPackets, long txBytes, long txPackets) {
            this.rxBytes = rxBytes;
            this.rxPackets = rxPackets;
            this.txBytes = txBytes;
            this.txPackets = txPackets;
        }

        public ForwardedStats(@NonNull TetherStatsParcel tetherStats) {
            rxBytes = tetherStats.rxBytes;
            rxPackets = tetherStats.rxPackets;
            txBytes = tetherStats.txBytes;
            txPackets = tetherStats.txPackets;
        }

        public ForwardedStats(@NonNull ForwardedStats other) {
            rxBytes = other.rxBytes;
            rxPackets = other.rxPackets;
            txBytes = other.txBytes;
            txPackets = other.txPackets;
        }

        /** Add Tx/Rx bytes/packets and return the result as a new object. */
        @NonNull
        public ForwardedStats add(@NonNull ForwardedStats other) {
            return new ForwardedStats(rxBytes + other.rxBytes, rxPackets + other.rxPackets,
                    txBytes + other.txBytes, txPackets + other.txPackets);
        }

        /** Subtract Tx/Rx bytes/packets and return the result as a new object. */
        @NonNull
        public ForwardedStats subtract(@NonNull ForwardedStats other) {
            // TODO: Perhaps throw an exception if any negative difference value just in case.
            final long rxBytesDiff = Math.max(rxBytes - other.rxBytes, 0);
            final long rxPacketsDiff = Math.max(rxPackets - other.rxPackets, 0);
            final long txBytesDiff = Math.max(txBytes - other.txBytes, 0);
            final long txPacketsDiff = Math.max(txPackets - other.txPackets, 0);
            return new ForwardedStats(rxBytesDiff, rxPacketsDiff, txBytesDiff, txPacketsDiff);
        }

        /** Returns the string representation of this object. */
        @NonNull
        public String toString() {
            return String.format("ForwardedStats(rxb: %d, rxp: %d, txb: %d, txp: %d)", rxBytes,
                    rxPackets, txBytes, txPackets);
        }
    }

    /**
     * Configures a socket for receiving ICMPv6 router solicitations and sending advertisements.
     * @param fd the socket's {@link FileDescriptor}.
     * @param ifIndex the interface index.
     */
    public static native void setupRaSocket(FileDescriptor fd, int ifIndex)
            throws SocketException;

    /**
     * Read s as an unsigned 16-bit integer.
     */
    public static int uint16(short s) {
        return s & 0xffff;
    }

    /** Check whether two TetheringRequestParcels are the same. */
    public static boolean isTetheringRequestEquals(final TetheringRequestParcel request,
            final TetheringRequestParcel otherRequest) {
        if (request == otherRequest) return true;

        return request != null && otherRequest != null
                && request.tetheringType == otherRequest.tetheringType
                && Objects.equals(request.localIPv4Address, otherRequest.localIPv4Address)
                && Objects.equals(request.staticClientAddress, otherRequest.staticClientAddress)
                && request.exemptFromEntitlementCheck == otherRequest.exemptFromEntitlementCheck
                && request.showProvisioningUi == otherRequest.showProvisioningUi;
    }
}
