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

package android.net.vcn;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.net.NetworkCapabilities;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * VcnNetworkPolicyResult represents the Network policy result for a Network transport applying its
 * VCN policy via {@link VcnManager#applyVcnNetworkPolicy(NetworkCapabilities, LinkProperties)}.
 *
 * <p>Bearers that are bringing up networks capable of acting as a VCN's underlying network should
 * query for Network policy results upon any capability changes (e.g. changing of TRUSTED bit), and
 * when prompted by VcnManagementService via {@link VcnManager.VcnNetworkPolicyListener}.
 *
 * @hide
 */
@SystemApi
public final class VcnNetworkPolicyResult implements Parcelable {
    private final boolean mIsTearDownRequested;
    private final NetworkCapabilities mNetworkCapabilities;

    /**
     * Constructs a VcnNetworkPolicyResult with the specified parameters.
     *
     * @hide
     */
    public VcnNetworkPolicyResult(
            boolean isTearDownRequested, @NonNull NetworkCapabilities networkCapabilities) {
        Objects.requireNonNull(networkCapabilities, "networkCapabilities must be non-null");

        mIsTearDownRequested = isTearDownRequested;
        mNetworkCapabilities = networkCapabilities;
    }

    /**
     * Returns whether this VCN policy result requires that the underlying Network should be torn
     * down.
     *
     * <p>Upon querying for the current Network policy result, the bearer must check this method,
     * and MUST tear down the corresponding Network if it returns true.
     */
    public boolean isTeardownRequested() {
        return mIsTearDownRequested;
    }

    /**
     * Returns the NetworkCapabilities that the bearer should be using for the corresponding
     * Network.
     */
    @NonNull
    public NetworkCapabilities getNetworkCapabilities() {
        return mNetworkCapabilities;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsTearDownRequested, mNetworkCapabilities);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VcnNetworkPolicyResult)) return false;
        final VcnNetworkPolicyResult that = (VcnNetworkPolicyResult) o;

        return mIsTearDownRequested == that.mIsTearDownRequested
                && mNetworkCapabilities.equals(that.mNetworkCapabilities);
    }

    @Override
    public String toString() {
        return "VcnNetworkPolicyResult { "
                + "mIsTeardownRequested = "
                + mIsTearDownRequested
                + ", mNetworkCapabilities"
                + mNetworkCapabilities
                + " }";
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsTearDownRequested);
        dest.writeParcelable(mNetworkCapabilities, flags);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<VcnNetworkPolicyResult> CREATOR =
            new Creator<VcnNetworkPolicyResult>() {
                public VcnNetworkPolicyResult createFromParcel(Parcel in) {
                    return new VcnNetworkPolicyResult(in.readBoolean(), in.readParcelable(null));
                }

                public VcnNetworkPolicyResult[] newArray(int size) {
                    return new VcnNetworkPolicyResult[size];
                }
            };
}
