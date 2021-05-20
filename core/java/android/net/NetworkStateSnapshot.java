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

package android.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.net.module.util.NetworkIdentityUtils;

import java.util.Objects;

/**
 * Snapshot of network state.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
public final class NetworkStateSnapshot implements Parcelable {
    /** The network associated with this snapshot. */
    @NonNull
    private final Network mNetwork;

    /** The {@link NetworkCapabilities} of the network associated with this snapshot. */
    @NonNull
    private final NetworkCapabilities mNetworkCapabilities;

    /** The {@link LinkProperties} of the network associated with this snapshot. */
    @NonNull
    private final LinkProperties mLinkProperties;

    /**
     * The Subscriber Id of the network associated with this snapshot. See
     * {@link android.telephony.TelephonyManager#getSubscriberId()}.
     */
    @Nullable
    private final String mSubscriberId;

    /**
     * The legacy type of the network associated with this snapshot. See
     * {@code ConnectivityManager#TYPE_*}.
     */
    private final int mLegacyType;

    public NetworkStateSnapshot(@NonNull Network network,
            @NonNull NetworkCapabilities networkCapabilities,
            @NonNull LinkProperties linkProperties,
            @Nullable String subscriberId, int legacyType) {
        mNetwork = Objects.requireNonNull(network);
        mNetworkCapabilities = Objects.requireNonNull(networkCapabilities);
        mLinkProperties = Objects.requireNonNull(linkProperties);
        mSubscriberId = subscriberId;
        mLegacyType = legacyType;
    }

    /** @hide */
    public NetworkStateSnapshot(@NonNull Parcel in) {
        mNetwork = in.readParcelable(null);
        mNetworkCapabilities = in.readParcelable(null);
        mLinkProperties = in.readParcelable(null);
        mSubscriberId = in.readString();
        mLegacyType = in.readInt();
    }

    /** Get the network associated with this snapshot */
    @NonNull
    public Network getNetwork() {
        return mNetwork;
    }

    /** Get {@link NetworkCapabilities} of the network associated with this snapshot. */
    @NonNull
    public NetworkCapabilities getNetworkCapabilities() {
        return mNetworkCapabilities;
    }

    /** Get the {@link LinkProperties} of the network associated with this snapshot. */
    @NonNull
    public LinkProperties getLinkProperties() {
        return mLinkProperties;
    }

    /** Get the Subscriber Id of the network associated with this snapshot. */
    @Nullable
    public String getSubscriberId() {
        return mSubscriberId;
    }

    /** Get the legacy type of the network associated with this snapshot. */
    public int getLegacyType() {
        return mLegacyType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeParcelable(mNetwork, flags);
        out.writeParcelable(mNetworkCapabilities, flags);
        out.writeParcelable(mLinkProperties, flags);
        out.writeString(mSubscriberId);
        out.writeInt(mLegacyType);
    }

    @NonNull
    public static final Creator<NetworkStateSnapshot> CREATOR =
            new Creator<NetworkStateSnapshot>() {
        @NonNull
        @Override
        public NetworkStateSnapshot createFromParcel(@NonNull Parcel in) {
            return new NetworkStateSnapshot(in);
        }

        @NonNull
        @Override
        public NetworkStateSnapshot[] newArray(int size) {
            return new NetworkStateSnapshot[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NetworkStateSnapshot)) return false;
        NetworkStateSnapshot that = (NetworkStateSnapshot) o;
        return mLegacyType == that.mLegacyType
                && Objects.equals(mNetwork, that.mNetwork)
                && Objects.equals(mNetworkCapabilities, that.mNetworkCapabilities)
                && Objects.equals(mLinkProperties, that.mLinkProperties)
                && Objects.equals(mSubscriberId, that.mSubscriberId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mNetwork,
                mNetworkCapabilities, mLinkProperties, mSubscriberId, mLegacyType);
    }

    @Override
    public String toString() {
        return "NetworkStateSnapshot{"
                + "network=" + mNetwork
                + ", networkCapabilities=" + mNetworkCapabilities
                + ", linkProperties=" + mLinkProperties
                + ", subscriberId='" + NetworkIdentityUtils.scrubSubscriberId(mSubscriberId) + '\''
                + ", legacyType=" + mLegacyType
                + '}';
    }
}
