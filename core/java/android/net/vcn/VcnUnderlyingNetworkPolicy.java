/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.net.NetworkCapabilities;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * VcnUnderlyingNetworkPolicy represents the Network policy for a VCN-managed Network.
 *
 * <p>Transports that are bringing up networks capable of acting as a VCN's underlying network
 * should query for policy state upon major capability changes (e.g. changing of TRUSTED bit), and
 * when prompted by VcnManagementService via VcnUnderlyingNetworkPolicyListener.
 *
 * @hide
 */
public final class VcnUnderlyingNetworkPolicy implements Parcelable {
    private final VcnNetworkPolicyResult mVcnNetworkPolicyResult;

    /**
     * Constructs a VcnUnderlyingNetworkPolicy with the specified parameters.
     *
     * @hide
     */
    public VcnUnderlyingNetworkPolicy(
            boolean isTearDownRequested, @NonNull NetworkCapabilities mergedNetworkCapabilities) {
        Objects.requireNonNull(
                mergedNetworkCapabilities, "mergedNetworkCapabilities must be nonnull");

        mVcnNetworkPolicyResult =
                new VcnNetworkPolicyResult(isTearDownRequested, mergedNetworkCapabilities);
    }

    private VcnUnderlyingNetworkPolicy(@NonNull VcnNetworkPolicyResult vcnNetworkPolicyResult) {
        this.mVcnNetworkPolicyResult =
                Objects.requireNonNull(vcnNetworkPolicyResult, "vcnNetworkPolicyResult");
    }

    /**
     * Returns whether this Carrier VCN policy policy indicates that the underlying Network should
     * be torn down.
     */
    public boolean isTeardownRequested() {
        return mVcnNetworkPolicyResult.isTeardownRequested();
    }

    /**
     * Returns the NetworkCapabilities with Carrier VCN policy bits merged into the provided
     * capabilities.
     */
    @NonNull
    public NetworkCapabilities getMergedNetworkCapabilities() {
        return mVcnNetworkPolicyResult.getNetworkCapabilities();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mVcnNetworkPolicyResult);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VcnUnderlyingNetworkPolicy)) return false;
        final VcnUnderlyingNetworkPolicy that = (VcnUnderlyingNetworkPolicy) o;

        return mVcnNetworkPolicyResult.equals(that.mVcnNetworkPolicyResult);
    }

    @Override
    public String toString() {
        return mVcnNetworkPolicyResult.toString();
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mVcnNetworkPolicyResult, flags);
    }

    /** Implement the Parcelable interface */
    public static final @NonNull Creator<VcnUnderlyingNetworkPolicy> CREATOR =
            new Creator<VcnUnderlyingNetworkPolicy>() {
                public VcnUnderlyingNetworkPolicy createFromParcel(Parcel in) {
                    return new VcnUnderlyingNetworkPolicy(in.readParcelable(null));
                }

                public VcnUnderlyingNetworkPolicy[] newArray(int size) {
                    return new VcnUnderlyingNetworkPolicy[size];
                }
            };
}
