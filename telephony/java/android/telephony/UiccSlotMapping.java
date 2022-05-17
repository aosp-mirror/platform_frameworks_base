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

package android.telephony;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * <p>Provides information for a SIM slot mapping, which establishes a unique mapping between a
 * logical SIM slot and a physical SIM slot and port index.  A logical SIM slot represents a
 * potentially active SIM slot, where a physical SIM slot and port index represent a hardware SIM
 * slot and port (capable of having an active profile) which can be mapped to a logical sim slot.
 * <p>It contains the following parameters:
 * <ul>
 * <li>Port index: unique index referring to a port belonging to the physical SIM slot.
 * If the SIM does not support multiple enabled profiles, the port index is default index 0.</li>
 * <li>Physical slot index: unique index referring to a physical SIM slot. Physical slot IDs start
 * at 0 and go up depending on the number of physical slots on the device.
 * This differs from the number of logical slots a device has, which corresponds to the number of
 * active slots a device is capable of using. For example, a device which switches between dual-SIM
 * and single-SIM mode may always have two physical slots, but in single-SIM mode it will have only
 * one logical slot.</li>
 * <li>Logical slot index: unique index referring to a logical SIM slot, Logical slot IDs start at 0
 * and go up depending on the number of supported active slots on a device.
 * For example, a dual-SIM device typically has slot 0 and slot 1. If a device has multiple physical
 * slots but only supports one active slot, it will have only the logical slot ID 0</li>
 * </ul>
 *
 * <p> This configurations tells a specific logical slot is mapped to a port from an actual physical
 * sim slot @see <a href="https://developer.android.com/guide/topics/connectivity/telecom/telephony-ids">the Android Developer Site</a>
 * for more information.
 * @hide
 */
@SystemApi
public final class UiccSlotMapping implements Parcelable {
    private final int mPortIndex;
    private final int mPhysicalSlotIndex;
    private final int mLogicalSlotIndex;

    public static final @NonNull Creator<UiccSlotMapping> CREATOR =
            new Creator<UiccSlotMapping>() {
        @Override
        public UiccSlotMapping createFromParcel(Parcel in) {
            return new UiccSlotMapping(in);
        }

        @Override
        public UiccSlotMapping[] newArray(int size) {
            return new UiccSlotMapping[size];
        }
    };

    private UiccSlotMapping(Parcel in) {
        mPortIndex = in.readInt();
        mPhysicalSlotIndex = in.readInt();
        mLogicalSlotIndex = in.readInt();
    }

    @Override
    public void writeToParcel(@Nullable Parcel dest, int flags) {
        dest.writeInt(mPortIndex);
        dest.writeInt(mPhysicalSlotIndex);
        dest.writeInt(mLogicalSlotIndex);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     *
     * @param portIndex The port index is an enumeration of the ports available on the UICC.
     * @param physicalSlotIndex is unique index referring to a physical SIM slot.
     * @param logicalSlotIndex is unique index referring to a logical SIM slot.
     *
     */
    public UiccSlotMapping(int portIndex, int physicalSlotIndex, int logicalSlotIndex) {
        this.mPortIndex = portIndex;
        this.mPhysicalSlotIndex = physicalSlotIndex;
        this.mLogicalSlotIndex = logicalSlotIndex;
    }

    /**
     * Port index is the unique index referring to a port belonging to the physical SIM slot.
     * If the SIM does not support multiple enabled profiles, the port index is default index 0.
     *
     * @return port index.
     */
    @IntRange(from = 0)
    public int getPortIndex() {
        return mPortIndex;
    }

    /**
     * Gets the physical slot index for the slot that the UICC is currently inserted in.
     *
     * @return physical slot index which is the index of actual physical UICC slot.
     */
    @IntRange(from = 0)
    public int getPhysicalSlotIndex() {
        return mPhysicalSlotIndex;
    }

    /**
     * Gets logical slot index for the slot that the UICC is currently attached.
     * Logical slot index is the unique index referring to a logical slot(logical modem stack).
     *
     * @return logical slot index;
     */
    @IntRange(from = 0)
    public int getLogicalSlotIndex() {
        return mLogicalSlotIndex;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        UiccSlotMapping that = (UiccSlotMapping) obj;
        return (mPortIndex == that.mPortIndex)
                && (mPhysicalSlotIndex == that.mPhysicalSlotIndex)
                && (mLogicalSlotIndex == that.mLogicalSlotIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPortIndex, mPhysicalSlotIndex, mLogicalSlotIndex);
    }

    @NonNull
    @Override
    public String toString() {
        return "UiccSlotMapping (mPortIndex="
                + mPortIndex
                + ", mPhysicalSlotIndex="
                + mPhysicalSlotIndex
                + ", mLogicalSlotIndex="
                + mLogicalSlotIndex
                + ")";
    }
}
