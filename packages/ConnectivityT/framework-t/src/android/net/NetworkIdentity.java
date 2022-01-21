/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkTemplate.NETWORK_TYPE_ALL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.service.NetworkIdentityProto;
import android.telephony.Annotation;
import android.telephony.TelephonyManager;
import android.util.proto.ProtoOutputStream;

import com.android.net.module.util.NetworkCapabilitiesUtils;
import com.android.net.module.util.NetworkIdentityUtils;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Network definition that includes strong identity. Analogous to combining
 * {@link NetworkCapabilities} and an IMSI.
 *
 * @hide
 */
// @SystemApi(client = MODULE_LIBRARIES)
public class NetworkIdentity implements Comparable<NetworkIdentity> {
    private static final String TAG = "NetworkIdentity";

    /** @hide */
    // TODO: Remove this after migrating all callers to use
    //  {@link NetworkTemplate#NETWORK_TYPE_ALL} instead.
    public static final int SUBTYPE_COMBINED = -1;

    /**
     * Network has no {@code NetworkCapabilities#NET_CAPABILITY_OEM_*}.
     * @hide
     */
    public static final int OEM_NONE = 0x0;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PAID}.
     * @hide
     */
    public static final int OEM_PAID = 0x1;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PRIVATE}.
     * @hide
     */
    public static final int OEM_PRIVATE = 0x2;

    final int mType;
    final int mRatType;
    final String mSubscriberId;
    final String mWifiNetworkKey;
    final boolean mRoaming;
    final boolean mMetered;
    final boolean mDefaultNetwork;
    final int mOemManaged;

    /** @hide */
    public NetworkIdentity(
            int type, int ratType, @Nullable String subscriberId, @Nullable String wifiNetworkKey,
            boolean roaming, boolean metered, boolean defaultNetwork, int oemManaged) {
        mType = type;
        mRatType = ratType;
        mSubscriberId = subscriberId;
        mWifiNetworkKey = wifiNetworkKey;
        mRoaming = roaming;
        mMetered = metered;
        mDefaultNetwork = defaultNetwork;
        mOemManaged = oemManaged;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mRatType, mSubscriberId, mWifiNetworkKey, mRoaming, mMetered,
                mDefaultNetwork, mOemManaged);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof NetworkIdentity) {
            final NetworkIdentity ident = (NetworkIdentity) obj;
            return mType == ident.mType && mRatType == ident.mRatType && mRoaming == ident.mRoaming
                    && Objects.equals(mSubscriberId, ident.mSubscriberId)
                    && Objects.equals(mWifiNetworkKey, ident.mWifiNetworkKey)
                    && mMetered == ident.mMetered
                    && mDefaultNetwork == ident.mDefaultNetwork
                    && mOemManaged == ident.mOemManaged;
        }
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("{");
        builder.append("type=").append(mType);
        builder.append(", ratType=");
        if (mRatType == NETWORK_TYPE_ALL) {
            builder.append("COMBINED");
        } else {
            builder.append(mRatType);
        }
        if (mSubscriberId != null) {
            builder.append(", subscriberId=")
                    .append(NetworkIdentityUtils.scrubSubscriberId(mSubscriberId));
        }
        if (mWifiNetworkKey != null) {
            builder.append(", wifiNetworkKey=").append(mWifiNetworkKey);
        }
        if (mRoaming) {
            builder.append(", ROAMING");
        }
        builder.append(", metered=").append(mMetered);
        builder.append(", defaultNetwork=").append(mDefaultNetwork);
        builder.append(", oemManaged=").append(getOemManagedNames(mOemManaged));
        return builder.append("}").toString();
    }

    /**
     * Get the human readable representation of a bitfield representing the OEM managed state of a
     * network.
     */
    static String getOemManagedNames(int oemManaged) {
        if (oemManaged == OEM_NONE) {
            return "OEM_NONE";
        }
        final int[] bitPositions = NetworkCapabilitiesUtils.unpackBits(oemManaged);
        final ArrayList<String> oemManagedNames = new ArrayList<String>();
        for (int position : bitPositions) {
            oemManagedNames.add(nameOfOemManaged(1 << position));
        }
        return String.join(",", oemManagedNames);
    }

    private static String nameOfOemManaged(int oemManagedBit) {
        switch (oemManagedBit) {
            case OEM_PAID:
                return "OEM_PAID";
            case OEM_PRIVATE:
                return "OEM_PRIVATE";
            default:
                return "Invalid(" + oemManagedBit + ")";
        }
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long tag) {
        final long start = proto.start(tag);

        proto.write(NetworkIdentityProto.TYPE, mType);

        // TODO: dump mRatType as well.

        proto.write(NetworkIdentityProto.ROAMING, mRoaming);
        proto.write(NetworkIdentityProto.METERED, mMetered);
        proto.write(NetworkIdentityProto.DEFAULT_NETWORK, mDefaultNetwork);
        proto.write(NetworkIdentityProto.OEM_MANAGED_NETWORK, mOemManaged);

        proto.end(start);
    }

    /** @hide */
    public int getType() {
        return mType;
    }

    /** @hide */
    public int getRatType() {
        return mRatType;
    }

    /** @hide */
    public String getSubscriberId() {
        return mSubscriberId;
    }

    /** @hide */
    public String getWifiNetworkKey() {
        return mWifiNetworkKey;
    }

    /** @hide */
    public boolean getRoaming() {
        return mRoaming;
    }

    /** @hide */
    public boolean getMetered() {
        return mMetered;
    }

    /** @hide */
    public boolean getDefaultNetwork() {
        return mDefaultNetwork;
    }

    /** @hide */
    public int getOemManaged() {
        return mOemManaged;
    }

    /**
     * Assemble a {@link NetworkIdentity} from the passed arguments.
     *
     * This methods builds an identity based on the capabilities of the network in the
     * snapshot and other passed arguments. The identity is used as a key to record data usage.
     *
     * @param snapshot the snapshot of network state. See {@link NetworkStateSnapshot}.
     * @param defaultNetwork whether the network is a default network.
     * @param ratType the Radio Access Technology(RAT) type of the network. Or
     *                {@link TelephonyManager#NETWORK_TYPE_UNKNOWN} if not applicable.
     *                See {@code TelephonyManager.NETWORK_TYPE_*}.
     * @hide
     */
    // TODO: Remove this after all callers are migrated to use new Api.
    @NonNull
    public static NetworkIdentity buildNetworkIdentity(Context context,
            @NonNull NetworkStateSnapshot snapshot,
            boolean defaultNetwork, @Annotation.NetworkType int ratType) {
        final int legacyType = snapshot.getLegacyType();

        final String subscriberId = snapshot.getSubscriberId();
        String wifiNetworkKey = null;
        boolean roaming = !snapshot.getNetworkCapabilities().hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        boolean metered = !(snapshot.getNetworkCapabilities().hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                || snapshot.getNetworkCapabilities().hasCapability(
                NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED));

        final int oemManaged = getOemBitfield(snapshot.getNetworkCapabilities());

        if (legacyType == TYPE_WIFI) {
            final TransportInfo transportInfo = snapshot.getNetworkCapabilities()
                    .getTransportInfo();
            if (transportInfo instanceof WifiInfo) {
                final WifiInfo info = (WifiInfo) transportInfo;
                wifiNetworkKey = info != null ? info.getCurrentNetworkKey() : null;
            }
        }

        return new NetworkIdentity(legacyType, ratType, subscriberId, wifiNetworkKey, roaming,
                metered, defaultNetwork, oemManaged);
    }

    /**
     * Builds a bitfield of {@code NetworkIdentity.OEM_*} based on {@link NetworkCapabilities}.
     * @hide
     */
    public static int getOemBitfield(@NonNull NetworkCapabilities nc) {
        int oemManaged = OEM_NONE;

        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID)) {
            oemManaged |= OEM_PAID;
        }
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE)) {
            oemManaged |= OEM_PRIVATE;
        }

        return oemManaged;
    }

    @Override
    public int compareTo(@NonNull NetworkIdentity another) {
        Objects.requireNonNull(another);
        int res = Integer.compare(mType, another.mType);
        if (res == 0) {
            res = Integer.compare(mRatType, another.mRatType);
        }
        if (res == 0 && mSubscriberId != null && another.mSubscriberId != null) {
            res = mSubscriberId.compareTo(another.mSubscriberId);
        }
        if (res == 0 && mWifiNetworkKey != null && another.mWifiNetworkKey != null) {
            res = mWifiNetworkKey.compareTo(another.mWifiNetworkKey);
        }
        if (res == 0) {
            res = Boolean.compare(mRoaming, another.mRoaming);
        }
        if (res == 0) {
            res = Boolean.compare(mMetered, another.mMetered);
        }
        if (res == 0) {
            res = Boolean.compare(mDefaultNetwork, another.mDefaultNetwork);
        }
        if (res == 0) {
            res = Integer.compare(mOemManaged, another.mOemManaged);
        }
        return res;
    }
}
