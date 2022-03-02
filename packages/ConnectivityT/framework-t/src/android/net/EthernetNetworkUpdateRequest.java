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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** @hide */
@SystemApi
public final class EthernetNetworkUpdateRequest implements Parcelable {
    @NonNull
    private final IpConfiguration mIpConfig;
    @NonNull
    private final NetworkCapabilities mNetworkCapabilities;

    @NonNull
    public IpConfiguration getIpConfiguration() {
        return new IpConfiguration(mIpConfig);
    }

    @NonNull
    public NetworkCapabilities getNetworkCapabilities() {
        return new NetworkCapabilities(mNetworkCapabilities);
    }

    private EthernetNetworkUpdateRequest(@NonNull final IpConfiguration ipConfig,
            @NonNull final NetworkCapabilities networkCapabilities) {
        Objects.requireNonNull(ipConfig);
        Objects.requireNonNull(networkCapabilities);
        mIpConfig = new IpConfiguration(ipConfig);
        mNetworkCapabilities = new NetworkCapabilities(networkCapabilities);
    }

    private EthernetNetworkUpdateRequest(@NonNull final Parcel source) {
        Objects.requireNonNull(source);
        mIpConfig = IpConfiguration.CREATOR.createFromParcel(source);
        mNetworkCapabilities = NetworkCapabilities.CREATOR.createFromParcel(source);
    }

    /**
     * Builder used to create {@link EthernetNetworkUpdateRequest} objects.
     */
    public static final class Builder {
        @Nullable
        private IpConfiguration mBuilderIpConfig;
        @Nullable
        private NetworkCapabilities mBuilderNetworkCapabilities;

        public Builder(){}

        /**
         * Constructor to populate the builder's values with an already built
         * {@link EthernetNetworkUpdateRequest}.
         * @param request the {@link EthernetNetworkUpdateRequest} to populate with.
         */
        public Builder(@NonNull final EthernetNetworkUpdateRequest request) {
            Objects.requireNonNull(request);
            mBuilderIpConfig = new IpConfiguration(request.mIpConfig);
            mBuilderNetworkCapabilities = new NetworkCapabilities(request.mNetworkCapabilities);
        }

        /**
         * Set the {@link IpConfiguration} to be used with the {@code Builder}.
         * @param ipConfig the {@link IpConfiguration} to set.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setIpConfiguration(@NonNull final IpConfiguration ipConfig) {
            Objects.requireNonNull(ipConfig);
            mBuilderIpConfig = new IpConfiguration(ipConfig);
            return this;
        }

        /**
         * Set the {@link NetworkCapabilities} to be used with the {@code Builder}.
         * @param nc the {@link NetworkCapabilities} to set.
         * @return The builder to facilitate chaining.
         */
        @NonNull
        public Builder setNetworkCapabilities(@NonNull final NetworkCapabilities nc) {
            Objects.requireNonNull(nc);
            mBuilderNetworkCapabilities = new NetworkCapabilities(nc);
            return this;
        }

        /**
         * Build {@link EthernetNetworkUpdateRequest} return the current update request.
         */
        @NonNull
        public EthernetNetworkUpdateRequest build() {
            return new EthernetNetworkUpdateRequest(mBuilderIpConfig, mBuilderNetworkCapabilities);
        }
    }

    @Override
    public String toString() {
        return "EthernetNetworkUpdateRequest{"
                + "mIpConfig=" + mIpConfig
                + ", mNetworkCapabilities=" + mNetworkCapabilities + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EthernetNetworkUpdateRequest that = (EthernetNetworkUpdateRequest) o;

        return Objects.equals(that.getIpConfiguration(), mIpConfig)
                && Objects.equals(that.getNetworkCapabilities(), mNetworkCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIpConfig, mNetworkCapabilities);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mIpConfig.writeToParcel(dest, flags);
        mNetworkCapabilities.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Parcelable.Creator<EthernetNetworkUpdateRequest> CREATOR =
            new Parcelable.Creator<EthernetNetworkUpdateRequest>() {
                @Override
                public EthernetNetworkUpdateRequest[] newArray(int size) {
                    return new EthernetNetworkUpdateRequest[size];
                }

                @Override
                public EthernetNetworkUpdateRequest createFromParcel(@NonNull Parcel source) {
                    return new EthernetNetworkUpdateRequest(source);
                }
            };
}
