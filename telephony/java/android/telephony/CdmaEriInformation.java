/**
 * Copyright (C) 2020 The Android Open Source Project
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
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * CDMA ERI (Enhanced Roaming Indicator) information.
 *
 * This contains the following ERI information
 *
 * 1. ERI (Enhanced Roaming Indicator) icon index. The number is assigned by
 *    3GPP2 C.R1001-H v1.0 Table 8.1-1. Additionally carriers define their own
 *    ERI icon index.
 * 2. CDMA ERI icon mode. This represents how the icon should be displayed.
 *    Its one of the following CDMA ERI icon mode
 *    {@link android.telephony.CdmaEriInformation#ERI_ICON_MODE_NORMAL}
 *    {@link android.telephony.CdmaEriInformation#ERI_ICON_MODE_FLASH}
 *
 * @hide
 */
public final class CdmaEriInformation implements Parcelable {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ERI_"}, value = {
                ERI_ON,
                ERI_OFF,
                ERI_FLASH
            })
    public @interface EriIconIndex {}

    /**
     * ERI (Enhanced Roaming Indicator) is ON i.e value 0 defined by
     * 3GPP2 C.R1001-H v1.0 Table 8.1-1.
     */
    public static final int ERI_ON = 0;

    /**
     * ERI (Enhanced Roaming Indicator) is OFF i.e value 1 defined by
     * 3GPP2 C.R1001-H v1.0 Table 8.1-1.
     */
    public static final int ERI_OFF = 1;

    /**
     * ERI (Enhanced Roaming Indicator) is FLASH i.e value 2 defined by
     * 3GPP2 C.R1001-H v1.0 Table 8.1-1.
     */
    public static final int ERI_FLASH = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ERI_ICON_MODE_"}, value = {
                ERI_ICON_MODE_NORMAL,
                ERI_ICON_MODE_FLASH
            })
    public @interface EriIconMode {}

    /**
     * ERI (Enhanced Roaming Indicator) icon mode is normal. This constant represents that
     * the ERI icon should be displayed normally.
     *
     * Note: ERI is defined 3GPP2 C.R1001-H Table 8.1-1
     */
    public static final int ERI_ICON_MODE_NORMAL = 0;

    /**
     * ERI (Enhanced Roaming Indicator) icon mode flash. This constant represents that
     * the ERI icon should be flashing.
     *
     * Note: ERI is defined 3GPP2 C.R1001-H Table 8.1-1
     */
    public static final int ERI_ICON_MODE_FLASH = 1;

    private @EriIconIndex int mIconIndex;
    private @EriIconMode int mIconMode;

    /**
     * Creates CdmaEriInformation from iconIndex and iconMode
     *
     * @hide
     */
    public CdmaEriInformation(@EriIconIndex int iconIndex, @EriIconMode int iconMode) {
        mIconIndex = iconIndex;
        mIconMode = iconMode;
    }

    /** Gets the ERI icon index */
    public @EriIconIndex int getEriIconIndex() {
        return mIconIndex;
    }

    /**
     * Sets the ERI icon index
     *
     * @hide
     */
    public void setEriIconIndex(@EriIconIndex int iconIndex) {
        mIconIndex = iconIndex;
    }

    /** Gets the ERI icon mode */
    public @EriIconMode int getEriIconMode() {
        return mIconMode;
    }

    /**
     * Sets the ERI icon mode
     *
     * @hide
     */
    public void setEriIconMode(@EriIconMode int iconMode) {
        mIconMode = iconMode;
    }
    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mIconIndex);
        dest.writeInt(mIconMode);
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Construct a CdmaEriInformation object from the given parcel
     */
    private CdmaEriInformation(Parcel in) {
        mIconIndex = in.readInt();
        mIconMode = in.readInt();
    }

    /** Implement the Parcelable interface */
    public static final @android.annotation.NonNull Parcelable.Creator<CdmaEriInformation> CREATOR =
            new Parcelable.Creator<CdmaEriInformation>() {
        @Override
        public CdmaEriInformation createFromParcel(Parcel in) {
            return new CdmaEriInformation(in);
        }

        @Override
        public CdmaEriInformation[] newArray(int size) {
            return new CdmaEriInformation[size];
        }
    };
}
