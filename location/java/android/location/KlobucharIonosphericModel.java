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
 * A class contains Klobuchar ionospheric model coefficients used by GPS, BDS, QZSS.
 *
 * <p>This is defined in IS-GPS-200 section 20.3.3.5.1.7.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class KlobucharIonosphericModel implements Parcelable {
    /** Alpha0 coefficientin seconds. */
    double mAlpha0;
    /** Alpha1 coefficient in seconds per semi-circle. */
    double mAlpha1;
    /** Alpha2 coefficient in seconds per semi-circle squared. */
    double mAlpha2;
    /** Alpha3 coefficient in seconds per semi-circle cubed. */
    double mAlpha3;
    /** Beta0 coefficient in seconds. */
    double mBeta0;
    /** Beta1 coefficient in seconds per semi-circle. */
    double mBeta1;
    /** Beta2 coefficient in seconds per semi-circle squared. */
    double mBeta2;
    /** Beta3 coefficient in seconds per semi-circle cubed. */
    double mBeta3;

    private KlobucharIonosphericModel(Builder builder) {
        Preconditions.checkArgumentInRange(builder.mAlpha0, -1.193e-7f, 1.193e-7f, "Alpha0");
        Preconditions.checkArgumentInRange(builder.mAlpha1, -9.54e-7f, 9.54e-7f, "Alpha1");
        Preconditions.checkArgumentInRange(builder.mAlpha2, -7.63e-6f, 7.63e-6f, "Alpha2");
        Preconditions.checkArgumentInRange(builder.mAlpha3, -7.63e-6f, 7.63e-6f, "Alpha3");
        Preconditions.checkArgumentInRange(builder.mBeta0, -262144.0f, 262144.0f, "Beta0");
        Preconditions.checkArgumentInRange(builder.mBeta1, -2097152.0f, 2097152.0f, "Beta1");
        Preconditions.checkArgumentInRange(builder.mBeta2, -8388608.0f, 8388608.0f, "Beta2");
        Preconditions.checkArgumentInRange(builder.mBeta3, -8388608.0f, 8388608.0f, "Beta3");
        mAlpha0 = builder.mAlpha0;
        mAlpha1 = builder.mAlpha1;
        mAlpha2 = builder.mAlpha2;
        mAlpha3 = builder.mAlpha3;
        mBeta0 = builder.mBeta0;
        mBeta1 = builder.mBeta1;
        mBeta2 = builder.mBeta2;
        mBeta3 = builder.mBeta3;
    }

    /** Returns the alpha0 coefficient in seconds. */
    @FloatRange(from = -1.193e-7f, to = 1.193e-7f)
    public double getAlpha0() {
        return mAlpha0;
    }

    /** Returns the alpha1 coefficient in seconds per semi-circle. */
    @FloatRange(from = -9.54e-7f, to = 9.54e-7f)
    public double getAlpha1() {
        return mAlpha1;
    }

    /** Returns the alpha2 coefficient in seconds per semi-circle squared. */
    @FloatRange(from = -7.63e-6f, to = 7.63e-6f)
    public double getAlpha2() {
        return mAlpha2;
    }

    /** Returns the alpha3 coefficient in seconds per semi-circle cubed. */
    @FloatRange(from = -7.63e-6f, to = 7.63e-6f)
    public double getAlpha3() {
        return mAlpha3;
    }

    /** Returns the beta0 coefficient in seconds. */
    @FloatRange(from = -262144.0f, to = 262144.0f)
    public double getBeta0() {
        return mBeta0;
    }

    /** Returns the beta1 coefficient in seconds per semi-circle. */
    @FloatRange(from = -2097152.0f, to = 2097152.0f)
    public double getBeta1() {
        return mBeta1;
    }

    /** Returns the beta2 coefficient in seconds per semi-circle squared. */
    @FloatRange(from = -8388608.0f, to = 8388608.0f)
    public double getBeta2() {
        return mBeta2;
    }

    /** Returns the beta3 coefficient in seconds per semi-circle cubed. */
    @FloatRange(from = -8388608.0f, to = 8388608.0f)
    public double getBeta3() {
        return mBeta3;
    }

    public static final @NonNull Creator<KlobucharIonosphericModel> CREATOR =
            new Creator<KlobucharIonosphericModel>() {
                @Override
                @NonNull
                public KlobucharIonosphericModel createFromParcel(Parcel in) {
                    return new KlobucharIonosphericModel.Builder()
                            .setAlpha0(in.readDouble())
                            .setAlpha1(in.readDouble())
                            .setAlpha2(in.readDouble())
                            .setAlpha3(in.readDouble())
                            .setBeta0(in.readDouble())
                            .setBeta1(in.readDouble())
                            .setBeta2(in.readDouble())
                            .setBeta3(in.readDouble())
                            .build();
                }
                @Override
                public KlobucharIonosphericModel[] newArray(int size) {
                    return new KlobucharIonosphericModel[size];
                }
            };


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeDouble(mAlpha0);
        parcel.writeDouble(mAlpha1);
        parcel.writeDouble(mAlpha2);
        parcel.writeDouble(mAlpha3);
        parcel.writeDouble(mBeta0);
        parcel.writeDouble(mBeta1);
        parcel.writeDouble(mBeta2);
        parcel.writeDouble(mBeta3);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("KlobucharIonosphericModel[");
        builder.append("alpha0 = ").append(mAlpha0);
        builder.append(", alpha1 = ").append(mAlpha1);
        builder.append(", alpha2 = ").append(mAlpha2);
        builder.append(", alpha3 = ").append(mAlpha3);
        builder.append(", beta0 = ").append(mBeta0);
        builder.append(", beta1 = ").append(mBeta1);
        builder.append(", beta2 = ").append(mBeta2);
        builder.append(", beta3 = ").append(mBeta3);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link KlobucharIonosphericModel} */
    public static final class Builder {
        private double mAlpha0;
        private double mAlpha1;
        private double mAlpha2;
        private double mAlpha3;
        private double mBeta0;
        private double mBeta1;
        private double mBeta2;
        private double mBeta3;

        /** Sets the alpha0 coefficient in seconds. */
        @NonNull
        public Builder setAlpha0(@FloatRange(from = -1.193e-7f, to = 1.193e-7f) double alpha0) {
            mAlpha0 = alpha0;
            return this;
        }

        /** Sets the alpha1 coefficient in seconds per semi-circle. */
        @NonNull
        public Builder setAlpha1(@FloatRange(from = -9.54e-7f, to = 9.54e-7f) double alpha1) {
            mAlpha1 = alpha1;
            return this;
        }

        /** Sets the alpha2 coefficient in seconds per semi-circle squared. */
        @NonNull
        public Builder setAlpha2(@FloatRange(from = -7.63e-6f, to = 7.63e-6f) double alpha2) {
            mAlpha2 = alpha2;
            return this;
        }

        /** Sets the alpha3 coefficient in seconds per semi-circle cubed. */
        @NonNull
        public Builder setAlpha3(@FloatRange(from = -7.63e-6f, to = 7.63e-6f) double alpha3) {
            mAlpha3 = alpha3;
            return this;
        }

        /** Sets the beta0 coefficient in seconds. */
        @NonNull
        public Builder setBeta0(@FloatRange(from = -262144.0f, to = 262144.0f) double beta0) {
            mBeta0 = beta0;
            return this;
        }

        /** Sets the beta1 coefficient in seconds per semi-circle. */
        @NonNull
        public Builder setBeta1(@FloatRange(from = -2097152.0f, to = 2097152.0f) double beta1) {
            mBeta1 = beta1;
            return this;
        }

        /** Sets the beta2 coefficient in seconds per semi-circle squared. */
        @NonNull
        public Builder setBeta2(@FloatRange(from = -8388608.0f, to = 8388608.0f) double beta2) {
            mBeta2 = beta2;
            return this;
        }

        /** Sets the beta3 coefficient in seconds per semi-circle cubed. */
        @NonNull
        public Builder setBeta3(@FloatRange(from = -8388608.0f, to = 8388608.0f) double beta3) {
            mBeta3 = beta3;
            return this;
        }

        /** Builds a {@link KlobucharIonosphericModel} instance as specified by this builder. */
        @NonNull
        public KlobucharIonosphericModel build() {
            return new KlobucharIonosphericModel(this);
        }
    }
}
