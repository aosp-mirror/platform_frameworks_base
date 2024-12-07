/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.location;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A class contains ionospheric correction.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class IonosphericCorrection implements Parcelable {

    /** Carrier frequency in Hz to differentiate signals from the same satellite. e.g. GPS L1/L5 */
    private final long mCarrierFrequencyHz;

    /** Ionospheric correction. */
    @NonNull private final GnssCorrectionComponent mIonosphericCorrection;

    /**
     * Creates a new {@link IonosphericCorrection} instance.
     *
     * @param carrierFrequencyHz Carrier frequency in Hz to differentiate signals from the
     *     samesatellite. e.g. GPS L1/L5
     * @param ionosphericCorrection Ionospheric correction.
     */
    public IonosphericCorrection(
            @IntRange(from = 0) long carrierFrequencyHz,
            @NonNull GnssCorrectionComponent ionosphericCorrection) {
        Preconditions.checkArgument(carrierFrequencyHz > 0);
        Preconditions.checkNotNull(ionosphericCorrection, "IonosphericCorrection cannot be null");
        mCarrierFrequencyHz = carrierFrequencyHz;
        mIonosphericCorrection = ionosphericCorrection;
    }

    /**
     * Returns the carrier frequency in Hz to differentiate signals from the same satellite. e.g.
     * GPS L1/L5
     */
    @IntRange(from = 0)
    public long getCarrierFrequencyHz() {
        return mCarrierFrequencyHz;
    }

    /** Returns the Ionospheric correction. */
    @NonNull
    public GnssCorrectionComponent getIonosphericCorrection() {
        return mIonosphericCorrection;
    }

    public static final @NonNull Creator<IonosphericCorrection> CREATOR =
            new Creator<IonosphericCorrection>() {
                @Override
                @NonNull
                public IonosphericCorrection createFromParcel(Parcel in) {
                    long carrierFrequencyHz = in.readLong();
                    GnssCorrectionComponent ionosphericCorrection =
                            in.readTypedObject(GnssCorrectionComponent.CREATOR);
                    return new IonosphericCorrection(carrierFrequencyHz, ionosphericCorrection);
                }

                @Override
                public IonosphericCorrection[] newArray(int size) {
                    return new IonosphericCorrection[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mCarrierFrequencyHz);
        dest.writeTypedObject(mIonosphericCorrection, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("IonosphericCorrection[");
        builder.append("carrierFrequencyHz = ").append(mCarrierFrequencyHz);
        builder.append(", ionosphericCorrection = ").append(mIonosphericCorrection);
        builder.append("]");
        return builder.toString();
    }
}
