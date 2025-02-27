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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.location.GnssAssistance.GnssSatelliteCorrections;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A class contains Glonass assistance.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_GNSS_ASSISTANCE_INTERFACE)
@SystemApi
public final class GlonassAssistance implements Parcelable {

    /** The Glonass almanac. */
    @Nullable private final GlonassAlmanac mAlmanac;

    /** The UTC model. */
    @Nullable private final UtcModel mUtcModel;

    /** The list of time models. */
    @NonNull private final List<TimeModel> mTimeModels;

    /** The list of Glonass ephemeris. */
    @NonNull private final List<GlonassSatelliteEphemeris> mSatelliteEphemeris;

    /** The list of Glonass satellite corrections. */
    @NonNull private final List<GnssSatelliteCorrections> mSatelliteCorrections;

    private GlonassAssistance(Builder builder) {
        mAlmanac = builder.mAlmanac;
        mUtcModel = builder.mUtcModel;
        if (builder.mTimeModels != null) {
            mTimeModels = Collections.unmodifiableList(new ArrayList<>(builder.mTimeModels));
        } else {
            mTimeModels = new ArrayList<>();
        }
        if (builder.mSatelliteEphemeris != null) {
            mSatelliteEphemeris =
                    Collections.unmodifiableList(new ArrayList<>(builder.mSatelliteEphemeris));
        } else {
            mSatelliteEphemeris = new ArrayList<>();
        }
        if (builder.mSatelliteCorrections != null) {
            mSatelliteCorrections =
                    Collections.unmodifiableList(new ArrayList<>(builder.mSatelliteCorrections));
        } else {
            mSatelliteCorrections = new ArrayList<>();
        }
    }

    /** Returns the Glonass almanac. */
    @Nullable
    public GlonassAlmanac getAlmanac() {
        return mAlmanac;
    }

    /** Returns the UTC model. */
    @Nullable
    public UtcModel getUtcModel() {
        return mUtcModel;
    }

    /** Returns the list of time models. */
    @NonNull
    public List<TimeModel> getTimeModels() {
        return mTimeModels;
    }

    /** Returns the list of Glonass satellite ephemeris. */
    @NonNull
    public List<GlonassSatelliteEphemeris> getSatelliteEphemeris() {
        return mSatelliteEphemeris;
    }

    /** Returns the list of Glonass satellite corrections. */
    @NonNull
    public List<GnssSatelliteCorrections> getSatelliteCorrections() {
        return mSatelliteCorrections;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mAlmanac, flags);
        dest.writeTypedObject(mUtcModel, flags);
        dest.writeTypedList(mTimeModels);
        dest.writeTypedList(mSatelliteEphemeris);
        dest.writeTypedList(mSatelliteCorrections);
    }

    @Override
    @NonNull
    public String toString() {
        StringBuilder builder = new StringBuilder("GlonassAssistance[");
        builder.append("almanac = ").append(mAlmanac);
        builder.append(", utcModel = ").append(mUtcModel);
        builder.append(", timeModels = ").append(mTimeModels);
        builder.append(", satelliteEphemeris = ").append(mSatelliteEphemeris);
        builder.append(", satelliteCorrections = ").append(mSatelliteCorrections);
        builder.append("]");
        return builder.toString();
    }

    public static final @NonNull Creator<GlonassAssistance> CREATOR =
            new Creator<GlonassAssistance>() {
                @Override
                public GlonassAssistance createFromParcel(Parcel in) {
                    return new GlonassAssistance.Builder()
                            .setAlmanac(in.readTypedObject(GlonassAlmanac.CREATOR))
                            .setUtcModel(in.readTypedObject(UtcModel.CREATOR))
                            .setTimeModels(in.createTypedArrayList(TimeModel.CREATOR))
                            .setSatelliteEphemeris(
                                    in.createTypedArrayList(GlonassSatelliteEphemeris.CREATOR))
                            .setSatelliteCorrections(
                                    in.createTypedArrayList(GnssSatelliteCorrections.CREATOR))
                            .build();
                }

                @Override
                public GlonassAssistance[] newArray(int size) {
                    return new GlonassAssistance[size];
                }
            };

    /** Builder for {@link GlonassAssistance}. */
    public static final class Builder {
        private GlonassAlmanac mAlmanac;
        private UtcModel mUtcModel;
        private List<TimeModel> mTimeModels;
        private List<GlonassSatelliteEphemeris> mSatelliteEphemeris;
        private List<GnssSatelliteCorrections> mSatelliteCorrections;

        /** Sets the Glonass almanac. */
        @NonNull
        public Builder setAlmanac(
                @Nullable @SuppressLint("NullableCollection") GlonassAlmanac almanac) {
            mAlmanac = almanac;
            return this;
        }

        /** Sets the UTC model. */
        @NonNull
        public Builder setUtcModel(
                @Nullable @SuppressLint("NullableCollection") UtcModel utcModel) {
            mUtcModel = utcModel;
            return this;
        }

        /** Sets the list of time models. */
        @NonNull
        public Builder setTimeModels(
                @Nullable @SuppressLint("NullableCollection") List<TimeModel> timeModels) {
            mTimeModels = timeModels;
            return this;
        }

        /** Sets the list of Glonass satellite ephemeris. */
        @NonNull
        public Builder setSatelliteEphemeris(
                @Nullable @SuppressLint("NullableCollection")
                        List<GlonassSatelliteEphemeris> satelliteEphemeris) {
            mSatelliteEphemeris = satelliteEphemeris;
            return this;
        }

        /** Sets the list of Glonass satellite corrections. */
        @NonNull
        public Builder setSatelliteCorrections(
                @Nullable @SuppressLint("NullableCollection")
                        List<GnssSatelliteCorrections> satelliteCorrections) {
            mSatelliteCorrections = satelliteCorrections;
            return this;
        }

        /** Builds the {@link GlonassAssistance}. */
        @NonNull
        public GlonassAssistance build() {
            return new GlonassAssistance(this);
        }
    }
}
