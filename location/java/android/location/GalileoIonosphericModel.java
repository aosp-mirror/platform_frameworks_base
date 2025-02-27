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
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Contains Galileo ionospheric model.
 *
 * <p>This is defined in Galileo-OS-SIS-ICD-v2.1, section 5.1.6.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GalileoIonosphericModel implements Parcelable {
    /** Effective ionisation level 1st order parameter in sfu. */
    private final double mAi0;

    /** Effective ionisation level 2nd order parameter in sfu per degree. */
    private final double mAi1;

    /** Effective ionisation level 3nd order parameter in sfu per degree squared. */
    private final double mAi2;

    private GalileoIonosphericModel(Builder builder) {
        Preconditions.checkArgumentInRange(builder.mAi0, 0.0f, 512.0f, "Ai0");
        Preconditions.checkArgumentInRange(builder.mAi1, -4.0f, 4.0f, "Ai1");
        Preconditions.checkArgumentInRange(builder.mAi2, -0.5f, 0.5f, "Ai2");
        mAi0 = builder.mAi0;
        mAi1 = builder.mAi1;
        mAi2 = builder.mAi2;
    }

    /** Returns the effective ionisation level 1st order parameter in sfu. */
    @FloatRange(from = 0.0f, to = 512.0f)
    public double getAi0() {
        return mAi0;
    }

    /** Returns the effective ionisation level 2nd order parameter in sfu per degree. */
    @FloatRange(from = -4.0f, to = 4.0f)
    public double getAi1() {
        return mAi1;
    }

    /** Returns the effective ionisation level 3nd order parameter in sfu per degree squared. */
    @FloatRange(from = -0.5f, to = 0.5f)
    public double getAi2() {
        return mAi2;
    }

    public static final @NonNull Parcelable.Creator<GalileoIonosphericModel> CREATOR =
            new Parcelable.Creator<GalileoIonosphericModel>() {
                @Override
                public GalileoIonosphericModel createFromParcel(@NonNull Parcel source) {
                    return new GalileoIonosphericModel.Builder()
                            .setAi0(source.readDouble())
                            .setAi1(source.readDouble())
                            .setAi2(source.readDouble())
                            .build();
                }

                @Override
                public GalileoIonosphericModel[] newArray(int size) {
                    return new GalileoIonosphericModel[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeDouble(mAi0);
        dest.writeDouble(mAi1);
        dest.writeDouble(mAi2);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GalileoIonosphericModel[");
        builder.append("ai0 = ").append(mAi0);
        builder.append(", ai1 = ").append(mAi1);
        builder.append(", ai2 = ").append(mAi2);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GalileoIonosphericModel}. */
    public static final class Builder {
        private double mAi0;
        private double mAi1;
        private double mAi2;

        /** Sets the effective ionisation level 1st order parameter in sfu. */
        @NonNull
        public Builder setAi0(@FloatRange(from = 0.0f, to = 512.0f) double ai0) {
            mAi0 = ai0;
            return this;
        }

        /** Sets the effective ionisation level 2nd order parameter in sfu per degree. */
        @NonNull
        public Builder setAi1(@FloatRange(from = -4.0f, to = 4.0f) double ai1) {
            mAi1 = ai1;
            return this;
        }

        /** Sets the effective ionisation level 3nd order parameter in sfu per degree squared. */
        @NonNull
        public Builder setAi2(@FloatRange(from = -0.5f, to = 0.5f) double ai2) {
            mAi2 = ai2;
            return this;
        }

        /** Builds a {@link GalileoIonosphericModel}. */
        @NonNull
        public GalileoIonosphericModel build() {
            return new GalileoIonosphericModel(this);
        }
    }
}
