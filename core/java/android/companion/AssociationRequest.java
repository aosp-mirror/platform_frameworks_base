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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.OneTimeUseBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A request for the user to select a companion device to associate with.
 *
 * You can optionally set a {@link Builder#setDeviceFilter filter} for which devices to show to the
 * user to select from.
 * The exact type and fields of the filter you can set depend on the
 * medium type. See {@link Builder}'s static factory methods for specific protocols that are
 * supported.
 *
 * You can also set {@link Builder#setSingleDevice single device} to request a popup with single
 * device to be shown instead of a list to choose from
 *
 * @param <F> Device filter type
 */
public final class AssociationRequest<F extends DeviceFilter> implements Parcelable {

    /** @hide */
    public static final int MEDIUM_TYPE_BLUETOOTH = 0;
    /** @hide */
    public static final int MEDIUM_TYPE_BLUETOOTH_LE = 1;
    /** @hide */
    public static final int MEDIUM_TYPE_WIFI = 2;

    /** @hide */
    @IntDef({MEDIUM_TYPE_BLUETOOTH, MEDIUM_TYPE_BLUETOOTH_LE, MEDIUM_TYPE_WIFI})
    @Retention(RetentionPolicy.SOURCE)
    public @interface MediumType {}

    private final boolean mSingleDevice;
    private final int mMediumType;
    private final F mDeviceFilter;

    private AssociationRequest(boolean singleDevice, int mMediumType, F deviceFilter) {
        this.mSingleDevice = singleDevice;
        this.mMediumType = mMediumType;
        this.mDeviceFilter = deviceFilter;
    }

    private AssociationRequest(Parcel in) {
        this(
            in.readByte() != 0,
            in.readInt(),
            in.readParcelable(AssociationRequest.class.getClassLoader()));
    }

    /** @hide */
    public boolean isSingleDevice() {
        return mSingleDevice;
    }

    /** @hide */
    @MediumType
    public int getMediumType() {
        return mMediumType;
    }

    /** @hide */
    @Nullable
    public F getDeviceFilter() {
        return mDeviceFilter;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (mSingleDevice ? 1 : 0));
        dest.writeInt(mMediumType);
        dest.writeParcelable(mDeviceFilter, flags);
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
     *
     * @param <F> the type of filter for the request.
     */
    public static final class Builder<F extends DeviceFilter>
            extends OneTimeUseBuilder<AssociationRequest<F>> {
        private boolean mSingleDevice = false;
        @MediumType private int mMediumType;
        @Nullable private F mDeviceFilter = null;

        private Builder() {}

        /**
         * Create a new builder for an association request with a Bluetooth LE device
         */
        @NonNull
        public static Builder<BluetoothLEDeviceFilter> createForBluetoothLEDevice() {
            return new Builder<BluetoothLEDeviceFilter>()
                    .setMediumType(MEDIUM_TYPE_BLUETOOTH_LE);
        }

        /**
         * Create a new builder for an association request with a Bluetooth(non-LE) device
         */
        @NonNull
        public static Builder<BluetoothDeviceFilter> createForBluetoothDevice() {
            return new Builder<BluetoothDeviceFilter>()
                    .setMediumType(MEDIUM_TYPE_BLUETOOTH);
        }

        //TODO implement, once specific filter classes are available
//        public static Builder<> createForWiFiDevice()
//        public static Builder<> createForNanDevice()

        /**
         * @param singleDevice if true, scanning for a device will stop as soon as at least one
         *                     fitting device is found
         */
        @NonNull
        public Builder<F> setSingleDevice(boolean singleDevice) {
            checkNotUsed();
            this.mSingleDevice = singleDevice;
            return this;
        }

        /**
         * @param deviceFilter if set, only devices matching the given filter will be shown to the
         *                     user
         */
        @NonNull
        public Builder<F> setDeviceFilter(@Nullable F deviceFilter) {
            checkNotUsed();
            this.mDeviceFilter = deviceFilter;
            return this;
        }

        /**
         * @param deviceType A type of medium over which to discover devices
         *
         * @see MediumType
         */
        @NonNull
        private Builder<F> setMediumType(@MediumType int deviceType) {
            mMediumType = deviceType;
            return this;
        }

        /** @inheritDoc */
        @NonNull
        @Override
        public AssociationRequest<F> build() {
            markUsed();
            return new AssociationRequest<>(mSingleDevice, mMediumType, mDeviceFilter);
        }
    }
}
