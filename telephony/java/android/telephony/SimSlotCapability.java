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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Capabilities for a SIM Slot.
 */
public final class SimSlotCapability implements Parcelable {
    /** Slot type for UICC (removable SIM). */
    public static final int SLOT_TYPE_UICC = 1;
    /** Slot type for iUICC/iSIM (integrated SIM). */
    public static final int SLOT_TYPE_IUICC = 2;
    /** Slot type for eUICC/eSIM (embedded SIM). */
    public static final int SLOT_TYPE_EUICC = 3;
    /** Slot type for soft SIM (no physical SIM). */
    public static final int SLOT_TYPE_SOFT_SIM = 4;

    /** @hide */
    @IntDef(prefix = {"SLOT_TYPE_" }, value = {
            SLOT_TYPE_UICC,
            SLOT_TYPE_IUICC,
            SLOT_TYPE_EUICC,
            SLOT_TYPE_SOFT_SIM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SlotType {
    }

    private final int mPhysicalSlotIndex;
    private final int mSlotType;

    /** @hide */
    public SimSlotCapability(int physicalSlotId, int slotType) {
        this.mPhysicalSlotIndex = physicalSlotId;
        this.mSlotType = slotType;
    }

    private SimSlotCapability(Parcel in) {
        mPhysicalSlotIndex = in.readInt();
        mSlotType = in.readInt();
    }

    /**
     * @return physical SIM slot index
     */
    public int getPhysicalSlotIndex() {
        return mPhysicalSlotIndex;
    }

    /**
     * @return type of SIM {@link SlotType}
     */
    public @SlotType int getSlotType() {
        return mSlotType;
    }

    @Override
    public String toString() {
        return "mPhysicalSlotIndex=" + mPhysicalSlotIndex + " slotType=" + mSlotType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPhysicalSlotIndex, mSlotType);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || !(o instanceof SimSlotCapability) || hashCode() != o.hashCode()) {
            return false;
        }

        if (this == o) {
            return true;
        }

        SimSlotCapability s = (SimSlotCapability) o;

        return (mPhysicalSlotIndex == s.mPhysicalSlotIndex && mSlotType == s.mSlotType);
    }

    /**
     * {@link Parcelable#describeContents}
     */
    public @ContentsFlags int describeContents() {
        return 0;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(@NonNull Parcel dest, @WriteFlags int flags) {
        dest.writeInt(mPhysicalSlotIndex);
        dest.writeInt(mSlotType);
    }

    public static final @NonNull Parcelable.Creator<SimSlotCapability> CREATOR =
            new Parcelable.Creator() {
                public SimSlotCapability createFromParcel(Parcel in) {
                    return new SimSlotCapability(in);
                }

                public SimSlotCapability[] newArray(int size) {
                    return new SimSlotCapability[size];
                }
            };
}
