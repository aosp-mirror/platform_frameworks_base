/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;

/**
 * Common configurations to create virtual input devices.
 *
 * @hide
 */
@SystemApi
public abstract class VirtualInputDeviceConfig {
    /** The vendor id uniquely identifies the company who manufactured the device. */
    private final int mVendorId;
    /**
     * The product id uniquely identifies which product within the address space of a given vendor,
     * identified by the device's vendor id.
     */
    private final int mProductId;
    /** The associated display ID of the virtual input device. */
    private final int mAssociatedDisplayId;
    /** The name of the virtual input device. */
    @NonNull
    private final String mInputDeviceName;

    protected VirtualInputDeviceConfig(@NonNull Builder<? extends Builder<?>> builder) {
        mVendorId = builder.mVendorId;
        mProductId = builder.mProductId;
        mAssociatedDisplayId = builder.mAssociatedDisplayId;
        mInputDeviceName = builder.mInputDeviceName;
    }

    protected VirtualInputDeviceConfig(@NonNull Parcel in) {
        mVendorId = in.readInt();
        mProductId = in.readInt();
        mAssociatedDisplayId = in.readInt();
        mInputDeviceName = in.readString8();
    }

    /**
     * The vendor id uniquely identifies the company who manufactured the device.
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * The product id uniquely identifies which product within the address space of a given vendor,
     * identified by the device's vendor id.
     */
    public int getProductId() {
        return mProductId;
    }

    /**
     * The associated display ID of the virtual input device.
     */
    public int getAssociatedDisplayId() {
        return mAssociatedDisplayId;
    }

    /**
     * The name of the virtual input device.
     */
    @NonNull
    public String getInputDeviceName() {
        return mInputDeviceName;
    }

    void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mVendorId);
        dest.writeInt(mProductId);
        dest.writeInt(mAssociatedDisplayId);
        dest.writeString8(mInputDeviceName);
    }

    @Override
    public String toString() {
        return getClass().getName() + "( "
                + " name=" + mInputDeviceName
                + " vendorId=" + mVendorId
                + " productId=" + mProductId
                + " associatedDisplayId=" + mAssociatedDisplayId
                + additionalFieldsToString() + ")";
    }

    /** @hide */
    @NonNull
    String additionalFieldsToString() {
        return "";
    }

    /**
     * A builder for {@link VirtualInputDeviceConfig}
     *
     * @param <T> The subclass to be built.
     */
    @SuppressWarnings({"StaticFinalBuilder", "MissingBuildMethod"})
    public abstract static class Builder<T extends Builder<T>> {

        private int mVendorId;
        private int mProductId;
        private int mAssociatedDisplayId;
        @NonNull
        private String mInputDeviceName;

        /** @see VirtualInputDeviceConfig#getVendorId(). */
        @NonNull
        public T setVendorId(int vendorId) {
            mVendorId = vendorId;
            return self();
        }


        /** @see VirtualInputDeviceConfig#getProductId(). */
        @NonNull
        public T setProductId(int productId) {
            mProductId = productId;
            return self();
        }

        /** @see VirtualInputDeviceConfig#getAssociatedDisplayId(). */
        @NonNull
        public T setAssociatedDisplayId(int displayId) {
            mAssociatedDisplayId = displayId;
            return self();
        }

        /** @see VirtualInputDeviceConfig#getInputDeviceName(). */
        @NonNull
        public T setInputDeviceName(@NonNull String deviceName) {
            mInputDeviceName = deviceName;
            return self();
        }

        /**
         * Each subclass should return itself to allow the builder to chain properly
         */
        T self() {
            return (T) this;
        }
    }
}
