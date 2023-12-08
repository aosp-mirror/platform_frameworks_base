/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.vcn.routeselection;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Objects;

/**
 * A record of a single underlying network, caching relevant fields.
 *
 * @hide
 */
public class UnderlyingNetworkRecord {
    @NonNull public final Network network;
    @NonNull public final NetworkCapabilities networkCapabilities;
    @NonNull public final LinkProperties linkProperties;
    public final boolean isBlocked;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public UnderlyingNetworkRecord(
            @NonNull Network network,
            @NonNull NetworkCapabilities networkCapabilities,
            @NonNull LinkProperties linkProperties,
            boolean isBlocked) {
        this.network = network;
        this.networkCapabilities = networkCapabilities;
        this.linkProperties = linkProperties;
        this.isBlocked = isBlocked;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UnderlyingNetworkRecord)) return false;
        final UnderlyingNetworkRecord that = (UnderlyingNetworkRecord) o;

        return network.equals(that.network)
                && networkCapabilities.equals(that.networkCapabilities)
                && linkProperties.equals(that.linkProperties)
                && isBlocked == that.isBlocked;
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, networkCapabilities, linkProperties, isBlocked);
    }

    /** Return whether two records represent the same network */
    public static boolean isSameNetwork(
            @Nullable UnderlyingNetworkRecord leftRecord,
            @Nullable UnderlyingNetworkRecord rightRecord) {
        final Network left = leftRecord == null ? null : leftRecord.network;
        final Network right = rightRecord == null ? null : rightRecord.network;
        return Objects.equals(left, right);
    }

    /** Dumps the state of this record for logging and debugging purposes. */
    void dump(IndentingPrintWriter pw) {
        pw.println("UnderlyingNetworkRecord:");
        pw.increaseIndent();

        pw.println("mNetwork: " + network);
        pw.println("mNetworkCapabilities: " + networkCapabilities);
        pw.println("mLinkProperties: " + linkProperties);

        pw.decreaseIndent();
    }

    /** Builder to incrementally construct an UnderlyingNetworkRecord. */
    static class Builder {
        @NonNull private final Network mNetwork;

        @Nullable private NetworkCapabilities mNetworkCapabilities;
        @Nullable private LinkProperties mLinkProperties;
        boolean mIsBlocked;
        boolean mWasIsBlockedSet;

        Builder(@NonNull Network network) {
            mNetwork = network;
        }

        @NonNull
        Network getNetwork() {
            return mNetwork;
        }

        void setNetworkCapabilities(@NonNull NetworkCapabilities networkCapabilities) {
            mNetworkCapabilities = networkCapabilities;
        }

        @Nullable
        NetworkCapabilities getNetworkCapabilities() {
            return mNetworkCapabilities;
        }

        void setLinkProperties(@NonNull LinkProperties linkProperties) {
            mLinkProperties = linkProperties;
        }

        void setIsBlocked(boolean isBlocked) {
            mIsBlocked = isBlocked;
            mWasIsBlockedSet = true;
        }

        boolean isValid() {
            return mNetworkCapabilities != null && mLinkProperties != null && mWasIsBlockedSet;
        }

        UnderlyingNetworkRecord build() {
            if (!isValid()) {
                throw new IllegalArgumentException(
                        "Called build before UnderlyingNetworkRecord was valid");
            }

            return new UnderlyingNetworkRecord(
                    mNetwork, mNetworkCapabilities, mLinkProperties, mIsBlocked);
        }
    }
}
