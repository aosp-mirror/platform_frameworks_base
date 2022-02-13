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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/** @hide */
@SystemApi
public final class EthernetNetworkUpdateRequest implements Parcelable {
    @NonNull
    private final StaticIpConfiguration mIpConfig;
    @NonNull
    private final NetworkCapabilities mNetworkCapabilities;

    @NonNull
    public StaticIpConfiguration getIpConfig() {
        return new StaticIpConfiguration(mIpConfig);
    }

    @NonNull
    public NetworkCapabilities getNetworkCapabilities() {
        return new NetworkCapabilities(mNetworkCapabilities);
    }

    public EthernetNetworkUpdateRequest(@NonNull final StaticIpConfiguration ipConfig,
            @NonNull final NetworkCapabilities networkCapabilities) {
        Objects.requireNonNull(ipConfig);
        Objects.requireNonNull(networkCapabilities);
        mIpConfig = new StaticIpConfiguration(ipConfig);
        mNetworkCapabilities = new NetworkCapabilities(networkCapabilities);
    }

    private EthernetNetworkUpdateRequest(@NonNull final Parcel source) {
        Objects.requireNonNull(source);
        mIpConfig = StaticIpConfiguration.CREATOR.createFromParcel(source);
        mNetworkCapabilities = NetworkCapabilities.CREATOR.createFromParcel(source);
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

        return Objects.equals(that.getIpConfig(), mIpConfig)
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
