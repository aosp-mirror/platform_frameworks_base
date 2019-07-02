/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/** Display identifier that is stable across reboots.
 *
 * @hide
 */
public abstract class DisplayAddress implements Parcelable {
    /**
     * Creates an address for a physical display given its stable ID.
     *
     * A physical display ID is stable if the display can be identified using EDID information.
     *
     * @param physicalDisplayId A physical display ID.
     * @return The {@link Physical} address.
     * @see SurfaceControl#getPhysicalDisplayIds
     */
    @NonNull
    public static Physical fromPhysicalDisplayId(long physicalDisplayId) {
        return new Physical(physicalDisplayId);
    }

    /**
     * Creates an address for a network display given its MAC address.
     *
     * @param macAddress A MAC address in colon notation.
     * @return The {@link Network} address.
     */
    @NonNull
    public static Network fromMacAddress(String macAddress) {
        return new Network(macAddress);
    }

    /**
     * Address for a physically connected display.
     *
     * A {@link Physical} address is represented by a 64-bit identifier combining the port and model
     * of a display. The port, located in the least significant byte, uniquely identifies a physical
     * connector on the device for display output like eDP or HDMI. The model, located in the upper
     * bits, uniquely identifies a display model across manufacturers by encoding EDID information.
     * While the port is always stable, the model may not be available if EDID identification is not
     * supported by the platform, in which case the address is not unique.
     */
    public static final class Physical extends DisplayAddress {
        private static final long UNKNOWN_MODEL = 0;
        private static final int MODEL_SHIFT = 8;
        private static final int PORT_MASK = 0xFF;

        private final long mPhysicalDisplayId;

        /**
         * Physical port to which the display is connected.
         */
        public byte getPort() {
            return (byte) mPhysicalDisplayId;
        }

        /**
         * Model identifier unique across manufacturers.
         *
         * @return The model ID, or {@code null} if the model cannot be identified.
         */
        @Nullable
        public Long getModel() {
            final long model = mPhysicalDisplayId >>> MODEL_SHIFT;
            return model == UNKNOWN_MODEL ? null : model;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Physical
                    && mPhysicalDisplayId == ((Physical) other).mPhysicalDisplayId;
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder("{")
                    .append("port=").append(getPort() & PORT_MASK);

            final Long model = getModel();
            if (model != null) {
                builder.append(", model=0x").append(Long.toHexString(model));
            }

            return builder.append("}").toString();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(mPhysicalDisplayId);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeLong(mPhysicalDisplayId);
        }

        private Physical(long physicalDisplayId) {
            mPhysicalDisplayId = physicalDisplayId;
        }

        public static final @NonNull Parcelable.Creator<Physical> CREATOR =
                new Parcelable.Creator<Physical>() {
                    @Override
                    public Physical createFromParcel(Parcel in) {
                        return new Physical(in.readLong());
                    }

                    @Override
                    public Physical[] newArray(int size) {
                        return new Physical[size];
                    }
                };
    }

    /**
     * Address for a network-connected display.
     */
    public static final class Network extends DisplayAddress {
        private final String mMacAddress;

        @Override
        public boolean equals(Object other) {
            return other instanceof Network && mMacAddress.equals(((Network) other).mMacAddress);
        }

        @Override
        public String toString() {
            return mMacAddress;
        }

        @Override
        public int hashCode() {
            return mMacAddress.hashCode();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeString(mMacAddress);
        }

        private Network(String macAddress) {
            mMacAddress = macAddress;
        }

        public static final @NonNull Parcelable.Creator<Network> CREATOR =
                new Parcelable.Creator<Network>() {
                    @Override
                    public Network createFromParcel(Parcel in) {
                        return new Network(in.readString());
                    }

                    @Override
                    public Network[] newArray(int size) {
                        return new Network[size];
                    }
                };
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
