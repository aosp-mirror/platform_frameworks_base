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

import android.annotation.Nullable;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.service.NetworkIdentityProto;
import android.telephony.Annotation.NetworkType;
import android.util.proto.ProtoOutputStream;

import com.android.net.module.util.NetworkIdentityUtils;

import java.util.Objects;

/**
 * Network definition that includes strong identity. Analogous to combining
 * {@link NetworkCapabilities} and an IMSI.
 *
 * @hide
 */
public class NetworkIdentity implements Comparable<NetworkIdentity> {
    private static final String TAG = "NetworkIdentity";

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
    final int mSubType;
    final String mSubscriberId;
    final String mNetworkId;
    final boolean mRoaming;
    final boolean mMetered;
    final boolean mDefaultNetwork;
    final int mOemManaged;

    public NetworkIdentity(
            int type, int subType, String subscriberId, String networkId, boolean roaming,
            boolean metered, boolean defaultNetwork, int oemManaged) {
        mType = type;
        mSubType = subType;
        mSubscriberId = subscriberId;
        mNetworkId = networkId;
        mRoaming = roaming;
        mMetered = metered;
        mDefaultNetwork = defaultNetwork;
        mOemManaged = oemManaged;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mSubType, mSubscriberId, mNetworkId, mRoaming, mMetered,
                mDefaultNetwork, mOemManaged);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof NetworkIdentity) {
            final NetworkIdentity ident = (NetworkIdentity) obj;
            return mType == ident.mType && mSubType == ident.mSubType && mRoaming == ident.mRoaming
                    && Objects.equals(mSubscriberId, ident.mSubscriberId)
                    && Objects.equals(mNetworkId, ident.mNetworkId)
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
        builder.append(", subType=");
        if (mSubType == SUBTYPE_COMBINED) {
            builder.append("COMBINED");
        } else {
            builder.append(mSubType);
        }
        if (mSubscriberId != null) {
            builder.append(", subscriberId=")
                    .append(NetworkIdentityUtils.scrubSubscriberId(mSubscriberId));
        }
        if (mNetworkId != null) {
            builder.append(", networkId=").append(mNetworkId);
        }
        if (mRoaming) {
            builder.append(", ROAMING");
        }
        builder.append(", metered=").append(mMetered);
        builder.append(", defaultNetwork=").append(mDefaultNetwork);
        // TODO(180557699): Print a human readable string for OEM managed state.
        builder.append(", oemManaged=").append(mOemManaged);
        return builder.append("}").toString();
    }

    public void dumpDebug(ProtoOutputStream proto, long tag) {
        final long start = proto.start(tag);

        proto.write(NetworkIdentityProto.TYPE, mType);

        // Not dumping mSubType, subtypes are no longer supported.

        if (mSubscriberId != null) {
            proto.write(NetworkIdentityProto.SUBSCRIBER_ID,
                    NetworkIdentityUtils.scrubSubscriberId(mSubscriberId));
        }
        proto.write(NetworkIdentityProto.NETWORK_ID, mNetworkId);
        proto.write(NetworkIdentityProto.ROAMING, mRoaming);
        proto.write(NetworkIdentityProto.METERED, mMetered);
        proto.write(NetworkIdentityProto.DEFAULT_NETWORK, mDefaultNetwork);
        proto.write(NetworkIdentityProto.OEM_MANAGED_NETWORK, mOemManaged);

        proto.end(start);
    }

    public int getType() {
        return mType;
    }

    public int getSubType() {
        return mSubType;
    }

    public String getSubscriberId() {
        return mSubscriberId;
    }

    public String getNetworkId() {
        return mNetworkId;
    }

    public boolean getRoaming() {
        return mRoaming;
    }

    public boolean getMetered() {
        return mMetered;
    }

    public boolean getDefaultNetwork() {
        return mDefaultNetwork;
    }

    public int getOemManaged() {
        return mOemManaged;
    }

    /**
     * Build a {@link NetworkIdentity} from the given {@link NetworkState} and
     * {@code subType}, assuming that any mobile networks are using the current IMSI.
     * The subType if applicable, should be set as one of the TelephonyManager.NETWORK_TYPE_*
     * constants, or {@link android.telephony.TelephonyManager#NETWORK_TYPE_UNKNOWN} if not.
     */
    // TODO: Delete this function after NetworkPolicyManagerService finishes the migration.
    public static NetworkIdentity buildNetworkIdentity(Context context,
            NetworkState state, boolean defaultNetwork, @NetworkType int subType) {
        final NetworkStateSnapshot snapshot = new NetworkStateSnapshot(state.linkProperties,
                state.networkCapabilities, state.network, state.subscriberId,
                state.legacyNetworkType);
        return buildNetworkIdentity(context, snapshot, defaultNetwork, subType);
    }

    /**
     * Build a {@link NetworkIdentity} from the given {@link NetworkStateSnapshot} and
     * {@code subType}, assuming that any mobile networks are using the current IMSI.
     * The subType if applicable, should be set as one of the TelephonyManager.NETWORK_TYPE_*
     * constants, or {@link android.telephony.TelephonyManager#NETWORK_TYPE_UNKNOWN} if not.
     */
    public static NetworkIdentity buildNetworkIdentity(Context context,
            NetworkStateSnapshot snapshot, boolean defaultNetwork, @NetworkType int subType) {
        final int legacyType = snapshot.legacyType;

        final String subscriberId = snapshot.subscriberId;
        String networkId = null;
        boolean roaming = !snapshot.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        boolean metered = !snapshot.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED);

        final int oemManaged = getOemBitfield(snapshot.networkCapabilities);

        if (legacyType == TYPE_WIFI) {
            if (snapshot.networkCapabilities.getSsid() != null) {
                networkId = snapshot.networkCapabilities.getSsid();
                if (networkId == null) {
                    // TODO: Figure out if this code path never runs. If so, remove them.
                    final WifiManager wifi = (WifiManager) context.getSystemService(
                            Context.WIFI_SERVICE);
                    final WifiInfo info = wifi.getConnectionInfo();
                    networkId = info != null ? info.getSSID() : null;
                }
            }
        }

        return new NetworkIdentity(legacyType, subType, subscriberId, networkId, roaming, metered,
                defaultNetwork, oemManaged);
    }

    /**
     * Builds a bitfield of {@code NetworkIdentity.OEM_*} based on {@link NetworkCapabilities}.
     * @hide
     */
    public static int getOemBitfield(NetworkCapabilities nc) {
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
    public int compareTo(NetworkIdentity another) {
        int res = Integer.compare(mType, another.mType);
        if (res == 0) {
            res = Integer.compare(mSubType, another.mSubType);
        }
        if (res == 0 && mSubscriberId != null && another.mSubscriberId != null) {
            res = mSubscriberId.compareTo(another.mSubscriberId);
        }
        if (res == 0 && mNetworkId != null && another.mNetworkId != null) {
            res = mNetworkId.compareTo(another.mNetworkId);
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
