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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.NetworkTemplate.NETWORK_TYPE_ALL;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.service.NetworkIdentityProto;
import android.telephony.TelephonyManager;
import android.util.proto.ProtoOutputStream;

import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.NetworkCapabilitiesUtils;
import com.android.net.module.util.NetworkIdentityUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Network definition that includes strong identity. Analogous to combining
 * {@link NetworkCapabilities} and an IMSI.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public class NetworkIdentity {
    private static final String TAG = "NetworkIdentity";

    /** @hide */
    // TODO: Remove this after migrating all callers to use
    //  {@link NetworkTemplate#NETWORK_TYPE_ALL} instead.
    public static final int SUBTYPE_COMBINED = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "OEM_MANAGED_" }, flag = true, value = {
            NetworkTemplate.OEM_MANAGED_NO,
            NetworkTemplate.OEM_MANAGED_PAID,
            NetworkTemplate.OEM_MANAGED_PRIVATE
    })
    public @interface OemManaged{}

    /**
     * Network has no {@code NetworkCapabilities#NET_CAPABILITY_OEM_*}.
     * @hide
     */
    public static final int OEM_NONE = 0x0;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PAID}.
     * @hide
     */
    public static final int OEM_PAID = 1 << 0;
    /**
     * Network has {@link NetworkCapabilities#NET_CAPABILITY_OEM_PRIVATE}.
     * @hide
     */
    public static final int OEM_PRIVATE = 1 << 1;

    private static final long SUPPORTED_OEM_MANAGED_TYPES = OEM_PAID | OEM_PRIVATE;

    final int mType;
    final int mRatType;
    final int mSubId;
    final String mSubscriberId;
    final String mWifiNetworkKey;
    final boolean mRoaming;
    final boolean mMetered;
    final boolean mDefaultNetwork;
    final int mOemManaged;

    /** @hide */
    public NetworkIdentity(
            int type, int ratType, @Nullable String subscriberId, @Nullable String wifiNetworkKey,
            boolean roaming, boolean metered, boolean defaultNetwork, int oemManaged, int subId) {
        mType = type;
        mRatType = ratType;
        mSubscriberId = subscriberId;
        mWifiNetworkKey = wifiNetworkKey;
        mRoaming = roaming;
        mMetered = metered;
        mDefaultNetwork = defaultNetwork;
        mOemManaged = oemManaged;
        mSubId = subId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mRatType, mSubscriberId, mWifiNetworkKey, mRoaming, mMetered,
                mDefaultNetwork, mOemManaged, mSubId);
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
                    && mOemManaged == ident.mOemManaged
                    && mSubId == ident.mSubId;
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
        builder.append(", subId=").append(mSubId);
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

        proto.write(NetworkIdentityProto.TYPE_FIELD_NUMBER, mType);

        // TODO: dump mRatType as well.

        proto.write(NetworkIdentityProto.ROAMING_FIELD_NUMBER, mRoaming);
        proto.write(NetworkIdentityProto.METERED_FIELD_NUMBER, mMetered);
        proto.write(NetworkIdentityProto.DEFAULT_NETWORK_FIELD_NUMBER, mDefaultNetwork);
        proto.write(NetworkIdentityProto.OEM_MANAGED_NETWORK_FIELD_NUMBER, mOemManaged);

        proto.end(start);
    }

    /** Get the network type of this instance. */
    public int getType() {
        return mType;
    }

    /** Get the Radio Access Technology(RAT) type of this instance. */
    public int getRatType() {
        return mRatType;
    }

    /** Get the Subscriber Id of this instance. */
    @Nullable
    public String getSubscriberId() {
        return mSubscriberId;
    }

    /** Get the Wifi Network Key of this instance. See {@link WifiInfo#getNetworkKey()}. */
    @Nullable
    public String getWifiNetworkKey() {
        return mWifiNetworkKey;
    }

    /** @hide */
    // TODO: Remove this function after all callers are removed.
    public boolean getRoaming() {
        return mRoaming;
    }

    /** Return whether this network is roaming. */
    public boolean isRoaming() {
        return mRoaming;
    }

    /** @hide */
    // TODO: Remove this function after all callers are removed.
    public boolean getMetered() {
        return mMetered;
    }

    /** Return whether this network is metered. */
    public boolean isMetered() {
        return mMetered;
    }

    /** @hide */
    // TODO: Remove this function after all callers are removed.
    public boolean getDefaultNetwork() {
        return mDefaultNetwork;
    }

    /** Return whether this network is the default network. */
    public boolean isDefaultNetwork() {
        return mDefaultNetwork;
    }

    /** Get the OEM managed type of this instance. */
    public int getOemManaged() {
        return mOemManaged;
    }

    /** Get the SubId of this instance. */
    public int getSubId() {
        return mSubId;
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
     * @deprecated See {@link NetworkIdentity.Builder}.
     */
    // TODO: Remove this after all callers are migrated to use new Api.
    @Deprecated
    @NonNull
    public static NetworkIdentity buildNetworkIdentity(Context context,
            @NonNull NetworkStateSnapshot snapshot, boolean defaultNetwork, int ratType) {
        final NetworkIdentity.Builder builder = new NetworkIdentity.Builder()
                .setNetworkStateSnapshot(snapshot).setDefaultNetwork(defaultNetwork)
                .setSubId(snapshot.getSubId());
        if (snapshot.getLegacyType() == TYPE_MOBILE && ratType != NETWORK_TYPE_ALL) {
            builder.setRatType(ratType);
        }
        return builder.build();
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

    /** @hide */
    public static int compare(@NonNull NetworkIdentity left, @NonNull NetworkIdentity right) {
        Objects.requireNonNull(right);
        int res = Integer.compare(left.mType, right.mType);
        if (res == 0) {
            res = Integer.compare(left.mRatType, right.mRatType);
        }
        if (res == 0 && left.mSubscriberId != null && right.mSubscriberId != null) {
            res = left.mSubscriberId.compareTo(right.mSubscriberId);
        }
        if (res == 0 && left.mWifiNetworkKey != null && right.mWifiNetworkKey != null) {
            res = left.mWifiNetworkKey.compareTo(right.mWifiNetworkKey);
        }
        if (res == 0) {
            res = Boolean.compare(left.mRoaming, right.mRoaming);
        }
        if (res == 0) {
            res = Boolean.compare(left.mMetered, right.mMetered);
        }
        if (res == 0) {
            res = Boolean.compare(left.mDefaultNetwork, right.mDefaultNetwork);
        }
        if (res == 0) {
            res = Integer.compare(left.mOemManaged, right.mOemManaged);
        }
        if (res == 0) {
            res = Integer.compare(left.mSubId, right.mSubId);
        }
        return res;
    }

    /**
     * Builder class for {@link NetworkIdentity}.
     */
    public static final class Builder {
        // Need to be synchronized with ConnectivityManager.
        // TODO: Use {@link ConnectivityManager#MAX_NETWORK_TYPE} when this file is in the module.
        private static final int MAX_NETWORK_TYPE = 18; // TYPE_TEST
        private static final int MIN_NETWORK_TYPE = TYPE_MOBILE;

        private int mType;
        private int mRatType;
        private String mSubscriberId;
        private String mWifiNetworkKey;
        private boolean mRoaming;
        private boolean mMetered;
        private boolean mDefaultNetwork;
        private int mOemManaged;
        private int mSubId;

        /**
         * Creates a new Builder.
         */
        public Builder() {
            // Initialize with default values. Will be overwritten by setters.
            mType = ConnectivityManager.TYPE_NONE;
            mRatType = NetworkTemplate.NETWORK_TYPE_ALL;
            mSubscriberId = null;
            mWifiNetworkKey = null;
            mRoaming = false;
            mMetered = false;
            mDefaultNetwork = false;
            mOemManaged = NetworkTemplate.OEM_MANAGED_NO;
            mSubId = INVALID_SUBSCRIPTION_ID;
        }

        /**
         * Add an {@link NetworkStateSnapshot} into the {@link NetworkIdentity} instance.
         * This is a useful shorthand that will read from the snapshot and set the
         * following fields, if they are set in the snapshot :
         *  - type
         *  - subscriberId
         *  - roaming
         *  - metered
         *  - oemManaged
         *  - wifiNetworkKey
         *
         * @param snapshot The target {@link NetworkStateSnapshot} object.
         * @return The builder object.
         */
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setNetworkStateSnapshot(@NonNull NetworkStateSnapshot snapshot) {
            setType(snapshot.getLegacyType());

            setSubscriberId(snapshot.getSubscriberId());
            setRoaming(!snapshot.getNetworkCapabilities().hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING));
            setMetered(!(snapshot.getNetworkCapabilities().hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    || snapshot.getNetworkCapabilities().hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)));

            setOemManaged(getOemBitfield(snapshot.getNetworkCapabilities()));

            if (mType == TYPE_WIFI) {
                final TransportInfo transportInfo = snapshot.getNetworkCapabilities()
                        .getTransportInfo();
                if (transportInfo instanceof WifiInfo) {
                    final WifiInfo info = (WifiInfo) transportInfo;
                    setWifiNetworkKey(info.getNetworkKey());
                }
            }
            return this;
        }

        /**
         * Set the network type of the network.
         *
         * @param type the network type. See {@link ConnectivityManager#TYPE_*}.
         *
         * @return this builder.
         */
        @NonNull
        public Builder setType(int type) {
            // Include TYPE_NONE for compatibility, type field might not be filled by some
            // networks such as test networks.
            if ((type < MIN_NETWORK_TYPE || MAX_NETWORK_TYPE < type)
                    && type != ConnectivityManager.TYPE_NONE) {
                throw new IllegalArgumentException("Invalid network type: " + type);
            }
            mType = type;
            return this;
        }

        /**
         * Set the Radio Access Technology(RAT) type of the network.
         *
         * No RAT type is specified by default. Call clearRatType to reset.
         *
         * @param ratType the Radio Access Technology(RAT) type if applicable. See
         *                {@code TelephonyManager.NETWORK_TYPE_*}.
         *
         * @return this builder.
         */
        @NonNull
        public Builder setRatType(int ratType) {
            if (!CollectionUtils.contains(TelephonyManager.getAllNetworkTypes(), ratType)
                    && ratType != TelephonyManager.NETWORK_TYPE_UNKNOWN
                    && ratType != NetworkStatsManager.NETWORK_TYPE_5G_NSA) {
                throw new IllegalArgumentException("Invalid ratType " + ratType);
            }
            mRatType = ratType;
            return this;
        }

        /**
         * Clear the Radio Access Technology(RAT) type of the network.
         *
         * @return this builder.
         */
        @NonNull
        public Builder clearRatType() {
            mRatType = NetworkTemplate.NETWORK_TYPE_ALL;
            return this;
        }

        /**
         * Set the Subscriber Id.
         *
         * @param subscriberId the Subscriber Id of the network. Or null if not applicable.
         * @return this builder.
         */
        @NonNull
        public Builder setSubscriberId(@Nullable String subscriberId) {
            mSubscriberId = subscriberId;
            return this;
        }

        /**
         * Set the Wifi Network Key.
         *
         * @param wifiNetworkKey Wifi Network Key of the network,
         *                        see {@link WifiInfo#getNetworkKey()}.
         *                        Or null if not applicable.
         * @return this builder.
         */
        @NonNull
        public Builder setWifiNetworkKey(@Nullable String wifiNetworkKey) {
            mWifiNetworkKey = wifiNetworkKey;
            return this;
        }

        /**
         * Set whether this network is roaming.
         *
         * This field is false by default. Call with false to reset.
         *
         * @param roaming the roaming status of the network.
         * @return this builder.
         */
        @NonNull
        public Builder setRoaming(boolean roaming) {
            mRoaming = roaming;
            return this;
        }

        /**
         * Set whether this network is metered.
         *
         * This field is false by default. Call with false to reset.
         *
         * @param metered the meteredness of the network.
         * @return this builder.
         */
        @NonNull
        public Builder setMetered(boolean metered) {
            mMetered = metered;
            return this;
        }

        /**
         * Set whether this network is the default network.
         *
         * This field is false by default. Call with false to reset.
         *
         * @param defaultNetwork the default network status of the network.
         * @return this builder.
         */
        @NonNull
        public Builder setDefaultNetwork(boolean defaultNetwork) {
            mDefaultNetwork = defaultNetwork;
            return this;
        }

        /**
         * Set the OEM managed type.
         *
         * @param oemManaged Type of OEM managed network or unmanaged networks.
         *                   See {@code NetworkTemplate#OEM_MANAGED_*}.
         * @return this builder.
         */
        @NonNull
        public Builder setOemManaged(@OemManaged int oemManaged) {
            // Assert input does not contain illegal oemManage bits.
            if ((~SUPPORTED_OEM_MANAGED_TYPES & oemManaged) != 0) {
                throw new IllegalArgumentException("Invalid value for OemManaged : " + oemManaged);
            }
            mOemManaged = oemManaged;
            return this;
        }

        /**
         * Set the Subscription Id.
         *
         * @param subId the Subscription Id of the network. Or INVALID_SUBSCRIPTION_ID if not
         *              applicable.
         * @return this builder.
         */
        @NonNull
        public Builder setSubId(int subId) {
            mSubId = subId;
            return this;
        }

        private void ensureValidParameters() {
            // Assert non-mobile network cannot have a ratType.
            if (mType != TYPE_MOBILE && mRatType != NetworkTemplate.NETWORK_TYPE_ALL) {
                throw new IllegalArgumentException(
                        "Invalid ratType " + mRatType + " for type " + mType);
            }

            // Assert non-wifi network cannot have a wifi network key.
            if (mType != TYPE_WIFI && mWifiNetworkKey != null) {
                throw new IllegalArgumentException("Invalid wifi network key for type " + mType);
            }
        }

        /**
         * Builds the instance of the {@link NetworkIdentity}.
         *
         * @return the built instance of {@link NetworkIdentity}.
         */
        @NonNull
        public NetworkIdentity build() {
            ensureValidParameters();
            return new NetworkIdentity(mType, mRatType, mSubscriberId, mWifiNetworkKey,
                    mRoaming, mMetered, mDefaultNetwork, mOemManaged, mSubId);
        }
    }
}
