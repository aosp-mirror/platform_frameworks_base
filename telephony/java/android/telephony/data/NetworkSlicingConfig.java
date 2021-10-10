/**
 * Copyright 2021 The Android Open Source Project
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

package android.telephony.data;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a slicing configuration
 */
public final class NetworkSlicingConfig implements Parcelable {
    private final List<UrspRule> mUrspRules;
    private final List<NetworkSliceInfo> mSliceInfo;

    public NetworkSlicingConfig() {
        mUrspRules = new ArrayList<>();
        mSliceInfo = new ArrayList<>();
    }

    /** @hide */
    public NetworkSlicingConfig(List<UrspRule> urspRules, List<NetworkSliceInfo> sliceInfo) {
        this();
        mUrspRules.addAll(urspRules);
        mSliceInfo.addAll(sliceInfo);
    }

    /** @hide */
    public NetworkSlicingConfig(Parcel p) {
        mUrspRules = p.createTypedArrayList(UrspRule.CREATOR);
        mSliceInfo = p.createTypedArrayList(NetworkSliceInfo.CREATOR);
    }

    /**
     * This list contains the current URSP rules. Empty list represents that no rules are
     * configured.
     * @return the current URSP rules for this slicing configuration.
     */
    public @NonNull List<UrspRule> getUrspRules() {
        return mUrspRules;
    }

    /**
     * @return the list of all slices for this slicing configuration.
     */
    public @NonNull List<NetworkSliceInfo> getSliceInfo() {
        return mSliceInfo;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedList(mUrspRules, flags);
        dest.writeTypedList(mSliceInfo, flags);
    }

    public static final @NonNull Parcelable.Creator<NetworkSlicingConfig> CREATOR =
            new Parcelable.Creator<NetworkSlicingConfig>() {
                @Override
                public NetworkSlicingConfig createFromParcel(Parcel source) {
                    return new NetworkSlicingConfig(source);
                }

                @Override
                public NetworkSlicingConfig[] newArray(int size) {
                    return new NetworkSlicingConfig[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NetworkSlicingConfig that = (NetworkSlicingConfig) o;
        return mUrspRules.size() == that.mUrspRules.size()
                && mUrspRules.containsAll(that.mUrspRules)
                && mSliceInfo.size() == that.mSliceInfo.size()
                && mSliceInfo.containsAll(that.mSliceInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUrspRules, mSliceInfo);
    }

    @Override
    public String toString() {
        return "{.urspRules = " + mUrspRules + ", .sliceInfo = " + mSliceInfo + "}";
    }
}
