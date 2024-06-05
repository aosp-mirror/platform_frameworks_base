/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * NTN signal strength related information.
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public final class NtnSignalStrength implements Parcelable {
    /** Non-terrestrial network signal strength is not available. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NTN_SIGNAL_STRENGTH_NONE = 0;
    /** Non-terrestrial network signal strength is poor. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NTN_SIGNAL_STRENGTH_POOR = 1;
    /** Non-terrestrial network signal strength is moderate. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NTN_SIGNAL_STRENGTH_MODERATE = 2;
    /** Non-terrestrial network signal strength is good. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NTN_SIGNAL_STRENGTH_GOOD = 3;
    /** Non-terrestrial network signal strength is great. */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final int NTN_SIGNAL_STRENGTH_GREAT = 4;
    @NtnSignalStrengthLevel private int mLevel;

    /** @hide */
    @IntDef(prefix = "NTN_SIGNAL_STRENGTH_", value = {
            NTN_SIGNAL_STRENGTH_NONE,
            NTN_SIGNAL_STRENGTH_POOR,
            NTN_SIGNAL_STRENGTH_MODERATE,
            NTN_SIGNAL_STRENGTH_GOOD,
            NTN_SIGNAL_STRENGTH_GREAT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NtnSignalStrengthLevel {}

    /**
     * Create a parcelable object to inform the current non-terrestrial signal strength
     * @hide
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public NtnSignalStrength(@NtnSignalStrengthLevel int level) {
        this.mLevel = level;
    }

    /**
     * This constructor is used to create a copy of an existing NtnSignalStrength object.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public NtnSignalStrength(@Nullable NtnSignalStrength source) {
        this.mLevel = (source == null) ? NTN_SIGNAL_STRENGTH_NONE : source.getLevel();
    }

    private NtnSignalStrength(Parcel in) {
        readFromParcel(in);
    }

    /**
     * Returns notified non-terrestrial network signal strength level.
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @NtnSignalStrengthLevel public int getLevel() {
        return mLevel;
    }

    /**
     * @return 0
     */
    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public int describeContents() {
        return 0;
    }

    /**
     * @param out  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mLevel);
    }

    private void readFromParcel(Parcel in) {
        mLevel = in.readInt();
    }

    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    @NonNull public static final Creator<NtnSignalStrength> CREATOR =
            new Creator<NtnSignalStrength>() {
                @Override public NtnSignalStrength createFromParcel(Parcel in) {
                    return new NtnSignalStrength(in);
                }

                @Override public NtnSignalStrength[] newArray(int size) {
                    return new NtnSignalStrength[size];
                }
            };

    @Override
    public int hashCode() {
        return mLevel;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        NtnSignalStrength that = (NtnSignalStrength) obj;
        return mLevel == that.mLevel;
    }

    @Override public String toString() {
        return "NtnSignalStrength{"
                + "mLevel=" + mLevel
                + '}';
    }
}
