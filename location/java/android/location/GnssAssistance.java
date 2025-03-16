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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class contains GNSS assistance.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GnssAssistance implements Parcelable {

    /** GPS assistance. */
    @Nullable private final GpsAssistance mGpsAssistance;

    /** Glonass assistance. */
    @Nullable private final GlonassAssistance mGlonassAssistance;

    /** Galileo assistance. */
    @Nullable private final GalileoAssistance mGalileoAssistance;

    /** Beidou assistance. */
    @Nullable private final BeidouAssistance mBeidouAssistance;

    /** QZSS assistance. */
    @Nullable private final QzssAssistance mQzssAssistance;

    private GnssAssistance(Builder builder) {
        mGpsAssistance = builder.mGpsAssistance;
        mGlonassAssistance = builder.mGlonassAssistance;
        mGalileoAssistance = builder.mGalileoAssistance;
        mBeidouAssistance = builder.mBeidouAssistance;
        mQzssAssistance = builder.mQzssAssistance;
    }

    /** Returns the GPS assistance. */
    @Nullable
    public GpsAssistance getGpsAssistance() {
        return mGpsAssistance;
    }

    /** Returns the Glonass assistance. */
    @Nullable
    public GlonassAssistance getGlonassAssistance() {
        return mGlonassAssistance;
    }

    /** Returns the Galileo assistance. */
    @Nullable
    public GalileoAssistance getGalileoAssistance() {
        return mGalileoAssistance;
    }

    /** Returns the Beidou assistance. */
    @Nullable
    public BeidouAssistance getBeidouAssistance() {
        return mBeidouAssistance;
    }

    /** Returns the Qzss assistance. */
    @Nullable
    public QzssAssistance getQzssAssistance() {
        return mQzssAssistance;
    }

    public static final @NonNull Creator<GnssAssistance> CREATOR =
            new Creator<GnssAssistance>() {
                @Override
                @NonNull
                public GnssAssistance createFromParcel(Parcel in) {
                    return new GnssAssistance.Builder()
                            .setGpsAssistance(in.readTypedObject(GpsAssistance.CREATOR))
                            .setGlonassAssistance(in.readTypedObject(GlonassAssistance.CREATOR))
                            .setGalileoAssistance(in.readTypedObject(GalileoAssistance.CREATOR))
                            .setBeidouAssistance(in.readTypedObject(BeidouAssistance.CREATOR))
                            .setQzssAssistance(in.readTypedObject(QzssAssistance.CREATOR))
                            .build();
                }

                @Override
                public GnssAssistance[] newArray(int size) {
                    return new GnssAssistance[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeTypedObject(mGpsAssistance, flags);
        parcel.writeTypedObject(mGlonassAssistance, flags);
        parcel.writeTypedObject(mGalileoAssistance, flags);
        parcel.writeTypedObject(mBeidouAssistance, flags);
        parcel.writeTypedObject(mQzssAssistance, flags);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GnssAssistance[");
        builder.append("gpsAssistance = ").append(mGpsAssistance);
        builder.append(", glonassAssistance = ").append(mGlonassAssistance);
        builder.append(", galileoAssistance = ").append(mGalileoAssistance);
        builder.append(", beidouAssistance = ").append(mBeidouAssistance);
        builder.append(", qzssAssistance = ").append(mQzssAssistance);
        builder.append("]");
        return builder.toString();
    }

    /** Builder for {@link GnssAssistance}. */
    public static final class Builder {
        private GpsAssistance mGpsAssistance;
        private GlonassAssistance mGlonassAssistance;
        private GalileoAssistance mGalileoAssistance;
        private BeidouAssistance mBeidouAssistance;
        private QzssAssistance mQzssAssistance;

        /** Sets the GPS assistance. */
        @NonNull
        public Builder setGpsAssistance(@Nullable GpsAssistance gpsAssistance) {
            mGpsAssistance = gpsAssistance;
            return this;
        }

        /** Sets the Glonass assistance. */
        @NonNull
        public Builder setGlonassAssistance(@Nullable GlonassAssistance glonassAssistance) {
            mGlonassAssistance = glonassAssistance;
            return this;
        }

        /** Sets the Galileo assistance. */
        @NonNull
        public Builder setGalileoAssistance(@Nullable GalileoAssistance galileoAssistance) {
            mGalileoAssistance = galileoAssistance;
            return this;
        }

        /** Sets the Beidou assistance. */
        @NonNull
        public Builder setBeidouAssistance(@Nullable BeidouAssistance beidouAssistance) {
            mBeidouAssistance = beidouAssistance;
            return this;
        }

        /** Sets the QZSS assistance. */
        @NonNull
        public Builder setQzssAssistance(@Nullable QzssAssistance qzssAssistance) {
            mQzssAssistance = qzssAssistance;
            return this;
        }

        /** Builds a {@link GnssAssistance} instance as specified by this builder. */
        @NonNull
        public GnssAssistance build() {
            return new GnssAssistance(this);
        }
    }

    /** A class contains GNSS corrections for satellites. */
    public static final class GnssSatelliteCorrections implements Parcelable {
        /**
         * Pseudo-random or satellite ID number for the satellite, a.k.a. Space Vehicle (SV), or OSN
         * number for Glonass.
         *
         * <p>The distinction is made by looking at the constellation field. Values must be in the
         * range of:
         *
         * <p>- GPS: 1-32
         *
         * <p>- GLONASS: 1-25
         *
         * <p>- QZSS: 183-206
         *
         * <p>- Galileo: 1-36
         *
         * <p>- Beidou: 1-63
         */
        @IntRange(from = 1, to = 206)
        int mSvid;

        /** List of Ionospheric corrections */
        @NonNull List<IonosphericCorrection> mIonosphericCorrections;

        /**
         * Creates a new {@link GnssSatelliteCorrections} instance.
         *
         * @param svid The Pseudo-random or satellite ID number for the satellite, a.k.a. Space
         *     Vehicle (SV), or OSN number for Glonass.
         *     <p>The distinction is made by looking at the constellation field. Values must be in
         *     the range of:
         *     <p>- GPS: 1-32
         *     <p>- GLONASS: 1-25
         *     <p>- QZSS: 183-206
         *     <p>- Galileo: 1-36
         *     <p>- Beidou: 1-63
         * @param ionosphericCorrections The list of Ionospheric corrections.
         */
        public GnssSatelliteCorrections(
                @IntRange(from = 1, to = 206) int svid,
                @NonNull final List<IonosphericCorrection> ionosphericCorrections) {
            // Allow SV ID beyond the range to support potential future extensibility.
            Preconditions.checkArgument(svid >= 1);
            Preconditions.checkNotNull(
                    ionosphericCorrections, "IonosphericCorrections cannot be null");
            mSvid = svid;
            mIonosphericCorrections =
                    Collections.unmodifiableList(new ArrayList<>(ionosphericCorrections));
        }

        /**
         * Returns the Pseudo-random or satellite ID number for the satellite, a.k.a. Space Vehicle
         * (SV), or OSN number for Glonass.
         *
         * <p>The distinction is made by looking at the constellation field. Values must be in the
         * range of:
         *
         * <p>- GPS: 1-32
         *
         * <p>- GLONASS: 1-25
         *
         * <p>- QZSS: 183-206
         *
         * <p>- Galileo: 1-36
         *
         * <p>- Beidou: 1-63
         */
        @IntRange(from = 1, to = 206)
        public int getSvid() {
            return mSvid;
        }

        /** Returns the list of Ionospheric corrections. */
        @NonNull
        public List<IonosphericCorrection> getIonosphericCorrections() {
            return mIonosphericCorrections;
        }

        public static final @NonNull Creator<GnssSatelliteCorrections> CREATOR =
                new Creator<GnssSatelliteCorrections>() {
                    @Override
                    @NonNull
                    public GnssSatelliteCorrections createFromParcel(Parcel in) {
                        int svid = in.readInt();
                        List<IonosphericCorrection> ionosphericCorrections = new ArrayList<>();
                        in.readTypedList(ionosphericCorrections, IonosphericCorrection.CREATOR);
                        return new GnssSatelliteCorrections(svid, ionosphericCorrections);
                    }

                    @Override
                    public GnssSatelliteCorrections[] newArray(int size) {
                        return new GnssSatelliteCorrections[size];
                    }
                };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeInt(mSvid);
            parcel.writeTypedList(mIonosphericCorrections, flags);
        }

        @Override
        @NonNull
        public String toString() {
            StringBuilder builder = new StringBuilder("GnssSatelliteCorrections[");
            builder.append("svid = ").append(mSvid);
            builder.append(", ionosphericCorrections = ").append(mIonosphericCorrections);
            builder.append("]");
            return builder.toString();
        }
    }
}
