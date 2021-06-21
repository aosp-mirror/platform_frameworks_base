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
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * NetworkSpecifier object for VCN underlying network requests.
 *
 * <p>This matches any underlying network with the appropriate subIds.
 *
 * @hide
 */
public final class VcnUnderlyingNetworkSpecifier extends NetworkSpecifier implements Parcelable {
    @NonNull private final int[] mSubIds;

    /**
     * Builds a new VcnUnderlyingNetworkSpecifier with the given list of subIds
     *
     * @hide
     */
    public VcnUnderlyingNetworkSpecifier(@NonNull int[] subIds) {
        mSubIds = Objects.requireNonNull(subIds, "subIds were null");
    }

    /**
     * Retrieves the list of subIds supported by this VcnUnderlyingNetworkSpecifier
     *
     * @hide
     */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public int[] getSubIds() {
        return mSubIds;
    }

    public static final @NonNull Creator<VcnUnderlyingNetworkSpecifier> CREATOR =
            new Creator<VcnUnderlyingNetworkSpecifier>() {
                @Override
                public VcnUnderlyingNetworkSpecifier createFromParcel(Parcel in) {
                    int[] subIds = in.createIntArray();
                    return new VcnUnderlyingNetworkSpecifier(subIds);
                }

                @Override
                public VcnUnderlyingNetworkSpecifier[] newArray(int size) {
                    return new VcnUnderlyingNetworkSpecifier[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeIntArray(mSubIds);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mSubIds);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof VcnUnderlyingNetworkSpecifier)) {
            return false;
        }

        VcnUnderlyingNetworkSpecifier lhs = (VcnUnderlyingNetworkSpecifier) obj;
        return Arrays.equals(mSubIds, lhs.mSubIds);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append("VcnUnderlyingNetworkSpecifier [")
                .append("mSubIds = ").append(Arrays.toString(mSubIds))
                .append("]")
                .toString();
    }

    /** @hide */
    @Override
    public boolean canBeSatisfiedBy(NetworkSpecifier other) {
        if (other instanceof TelephonyNetworkSpecifier) {
            return ArrayUtils.contains(
                    mSubIds, ((TelephonyNetworkSpecifier) other).getSubscriptionId());
        }
        // TODO(b/180140053): Allow matching against WifiNetworkAgentSpecifier

        // MatchAllNetworkSpecifier matched in NetworkCapabilities.
        return equals(other);
    }
}
