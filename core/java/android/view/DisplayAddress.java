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
     * @return The {@link Physical} address, or {@code null} if the ID is not stable.
     * @see SurfaceControl#getPhysicalDisplayIds
     */
    @Nullable
    public static Physical fromPhysicalDisplayId(long physicalDisplayId) {
        final Physical address = new Physical(physicalDisplayId);
        return address.getModel() == 0 ? null : address;
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
     */
    public static final class Physical extends DisplayAddress {
        private static final int PHYSICAL_DISPLAY_ID_MODEL_SHIFT = 8;
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
         */
        public long getModel() {
            return mPhysicalDisplayId >>> PHYSICAL_DISPLAY_ID_MODEL_SHIFT;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Physical
                    && mPhysicalDisplayId == ((Physical) other).mPhysicalDisplayId;
        }

        @Override
        public String toString() {
            return new StringBuilder("{")
                    .append("port=").append(getPort() & PORT_MASK)
                    .append(", model=0x").append(Long.toHexString(getModel()))
                    .append("}")
                    .toString();
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

        public static final @android.annotation.NonNull Parcelable.Creator<Physical> CREATOR =
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

        public static final @android.annotation.NonNull Parcelable.Creator<Network> CREATOR =
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
