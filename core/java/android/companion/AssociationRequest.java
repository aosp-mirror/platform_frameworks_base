/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.companion;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.OneTimeUseBuilder;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A request for the user to select a companion device to associate with.
 *
 * You can optionally set {@link Builder#addDeviceFilter filters} for which devices to show to the
 * user to select from.
 * The exact type and fields of the filter you can set depend on the
 * medium type. See {@link Builder}'s static factory methods for specific protocols that are
 * supported.
 *
 * You can also set {@link Builder#setSingleDevice single device} to request a popup with single
 * device to be shown instead of a list to choose from
 */
public final class AssociationRequest implements Parcelable {

    private final boolean mSingleDevice;
    private final List<DeviceFilter<?>> mDeviceFilters;

    private AssociationRequest(
            boolean singleDevice, @Nullable List<DeviceFilter<?>> deviceFilters) {
        this.mSingleDevice = singleDevice;
        this.mDeviceFilters = CollectionUtils.emptyIfNull(deviceFilters);
    }

    private AssociationRequest(Parcel in) {
        this(
            in.readByte() != 0,
            in.readParcelableList(new ArrayList<>(), AssociationRequest.class.getClassLoader()));
    }

    /** @hide */
    public boolean isSingleDevice() {
        return mSingleDevice;
    }

    /** @hide */
    @NonNull
    public List<DeviceFilter<?>> getDeviceFilters() {
        return mDeviceFilters;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AssociationRequest that = (AssociationRequest) o;
        return mSingleDevice == that.mSingleDevice &&
                Objects.equals(mDeviceFilters, that.mDeviceFilters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSingleDevice, mDeviceFilters);
    }

    @Override
    public String toString() {
        return "AssociationRequest{" +
                "mSingleDevice=" + mSingleDevice +
                ", mDeviceFilters=" + mDeviceFilters +
                '}';
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (mSingleDevice ? 1 : 0));
        dest.writeParcelableList(mDeviceFilters, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AssociationRequest> CREATOR = new Creator<AssociationRequest>() {
        @Override
        public AssociationRequest createFromParcel(Parcel in) {
            return new AssociationRequest(in);
        }

        @Override
        public AssociationRequest[] newArray(int size) {
            return new AssociationRequest[size];
        }
    };

    /**
     * A builder for {@link AssociationRequest}
     */
    public static final class Builder extends OneTimeUseBuilder<AssociationRequest> {
        private boolean mSingleDevice = false;
        @Nullable private ArrayList<DeviceFilter<?>> mDeviceFilters = null;

        public Builder() {}

        /**
         * @param singleDevice if true, scanning for a device will stop as soon as at least one
         *                     fitting device is found
         */
        @NonNull
        public Builder setSingleDevice(boolean singleDevice) {
            checkNotUsed();
            this.mSingleDevice = singleDevice;
            return this;
        }

        /**
         * @param deviceFilter if set, only devices matching the given filter will be shown to the
         *                     user
         */
        @NonNull
        public Builder addDeviceFilter(@Nullable DeviceFilter<?> deviceFilter) {
            checkNotUsed();
            if (deviceFilter != null) {
                mDeviceFilters = ArrayUtils.add(mDeviceFilters, deviceFilter);
            }
            return this;
        }

        /** @inheritDoc */
        @NonNull
        @Override
        public AssociationRequest build() {
            markUsed();
            return new AssociationRequest(mSingleDevice, mDeviceFilters);
        }
    }
}
