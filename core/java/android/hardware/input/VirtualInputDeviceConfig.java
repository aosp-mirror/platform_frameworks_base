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
import android.view.Display;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Common configurations to create virtual input devices.
 *
 * @hide
 */
@SystemApi
public abstract class VirtualInputDeviceConfig {

    /**
     * The maximum length of a device name (in bytes in UTF-8 encoding).
     *
     * This limitation comes directly from uinput.
     * See also UINPUT_MAX_NAME_SIZE in linux/uinput.h
     */
    private static final int DEVICE_NAME_MAX_LENGTH = 80;

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
        mInputDeviceName = Objects.requireNonNull(builder.mInputDeviceName, "Missing device name");

        if (mAssociatedDisplayId == Display.INVALID_DISPLAY) {
            throw new IllegalArgumentException(
                    "Display association is required for virtual input devices.");
        }

        // Comparison is greater or equal because the device name must fit into a const char*
        // including the \0-terminator. Therefore the actual number of bytes that can be used
        // for device name is DEVICE_NAME_MAX_LENGTH - 1
        if (mInputDeviceName.getBytes(StandardCharsets.UTF_8).length >= DEVICE_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Input device name exceeds maximum length of "
                    + DEVICE_NAME_MAX_LENGTH + "bytes: " + mInputDeviceName);
        }
    }

    protected VirtualInputDeviceConfig(@NonNull Parcel in) {
        mVendorId = in.readInt();
        mProductId = in.readInt();
        mAssociatedDisplayId = in.readInt();
        mInputDeviceName = Objects.requireNonNull(in.readString8(), "Missing device name");
    }

    /**
     * The vendor id uniquely identifies the company who manufactured the device.
     *
     * @see Builder#setVendorId(int) (int)
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * The product id uniquely identifies which product within the address space of a given vendor,
     * identified by the device's vendor id.
     *
     * @see Builder#setProductId(int)
     */
    public int getProductId() {
        return mProductId;
    }

    /**
     * The associated display ID of the virtual input device.
     *
     * @see Builder#setAssociatedDisplayId(int)
     */
    public int getAssociatedDisplayId() {
        return mAssociatedDisplayId;
    }

    /**
     * The name of the virtual input device.
     *
     * @see Builder#setInputDeviceName(String)
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
        private int mAssociatedDisplayId = Display.INVALID_DISPLAY;
        private String mInputDeviceName;

        /**
         * Sets the vendor id of the device, identifying the company who manufactured the device.
         */
        @NonNull
        public T setVendorId(int vendorId) {
            mVendorId = vendorId;
            return self();
        }


        /**
         * Sets the product id of the device, uniquely identifying the device within the address
         * space of a given vendor, identified by the device's vendor id.
         */
        @NonNull
        public T setProductId(int productId) {
            mProductId = productId;
            return self();
        }

        /**
         * Sets the associated display ID of the virtual input device. Required.
         *
         * <p>The input device is restricted to the display with the given ID and may not send
         * events to any other display.</p>
         */
        @NonNull
        public T setAssociatedDisplayId(int displayId) {
            mAssociatedDisplayId = displayId;
            return self();
        }

        /**
         * Sets the name of the virtual input device. Required.
         *
         * <p>The name must be unique among all input devices that belong to the same virtual
         * device.</p>
         *
         * <p>The maximum allowed length of the name is 80 bytes in UTF-8 encoding, enforced by
         * {@code UINPUT_MAX_NAME_SIZE}.</p>
         */
        @NonNull
        public T setInputDeviceName(@NonNull String deviceName) {
            mInputDeviceName = Objects.requireNonNull(deviceName);
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
