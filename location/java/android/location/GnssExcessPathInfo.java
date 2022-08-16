/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.ExcessPathInfo.EXCESS_PATH_INFO_HAS_ATTENUATION;
import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.ExcessPathInfo.EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH;
import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.ExcessPathInfo.EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH_UNC;
import static android.hardware.gnss.measurement_corrections.SingleSatCorrection.ExcessPathInfo.EXCESS_PATH_INFO_HAS_REFLECTING_PLANE;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Contains the info of an excess path signal caused by reflection
 *
 * @hide
 */
@SystemApi
public final class GnssExcessPathInfo implements Parcelable {

    private static final int HAS_EXCESS_PATH_LENGTH_MASK = EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH;
    private static final int HAS_EXCESS_PATH_LENGTH_UNC_MASK =
            EXCESS_PATH_INFO_HAS_EXCESS_PATH_LENGTH_UNC;
    private static final int HAS_REFLECTING_PLANE_MASK = EXCESS_PATH_INFO_HAS_REFLECTING_PLANE;
    private static final int HAS_ATTENUATION_MASK = EXCESS_PATH_INFO_HAS_ATTENUATION;

    /* A bitmask of fields present in this object (see HAS_* constants defined above) */
    private final int mFlags;
    private final float mExcessPathLengthMeters;
    private final float mExcessPathLengthUncertaintyMeters;
    @Nullable
    private final GnssReflectingPlane mReflectingPlane;
    private final float mAttenuationDb;

    private GnssExcessPathInfo(
            int flags,
            float excessPathLengthMeters,
            float excessPathLengthUncertaintyMeters,
            @Nullable GnssReflectingPlane reflectingPlane,
            float attenuationDb) {
        mFlags = flags;
        mExcessPathLengthMeters = excessPathLengthMeters;
        mExcessPathLengthUncertaintyMeters = excessPathLengthUncertaintyMeters;
        mReflectingPlane = reflectingPlane;
        mAttenuationDb = attenuationDb;
    }

    /**
     * Gets a bitmask of fields present in this object.
     *
     * <p>This API exists for JNI since it is easier for JNI to get one integer flag than looking up
     * several has* methods.
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /** Returns {@code true} if {@link #getExcessPathLengthMeters()} is valid. */
    public boolean hasExcessPathLength() {
        return (mFlags & HAS_EXCESS_PATH_LENGTH_MASK) != 0;
    }

    /**
     * Returns the excess path length to be subtracted from pseudorange before using it in
     * calculating location.
     *
     * <p>{@link #hasExcessPathLength()} must be true when calling this method. Otherwise, an
     * {@link UnsupportedOperationException} will be thrown.
     */
    @FloatRange(from = 0.0f)
    public float getExcessPathLengthMeters() {
        if (!hasExcessPathLength()) {
            throw new UnsupportedOperationException(
                    "getExcessPathLengthMeters() is not supported when hasExcessPathLength() is "
                            + "false");
        }
        return mExcessPathLengthMeters;
    }

    /** Returns {@code true} if {@link #getExcessPathLengthUncertaintyMeters()} is valid. */
    public boolean hasExcessPathLengthUncertainty() {
        return (mFlags & HAS_EXCESS_PATH_LENGTH_UNC_MASK) != 0;
    }

    /**
     * Returns the error estimate (1-sigma) for the excess path length estimate.
     *
     * <p>{@link #hasExcessPathLengthUncertainty()} must be true when calling this method.
     * Otherwise, an {@link UnsupportedOperationException} will be thrown.
     */
    @FloatRange(from = 0.0f)
    public float getExcessPathLengthUncertaintyMeters() {
        if (!hasExcessPathLengthUncertainty()) {
            throw new UnsupportedOperationException(
                    "getExcessPathLengthUncertaintyMeters() is not supported when "
                            + "hasExcessPathLengthUncertainty() is false");
        }
        return mExcessPathLengthUncertaintyMeters;
    }

    /**
     * Returns {@code true} if {@link #getReflectingPlane()} is valid.
     *
     * <p>Returns false if the satellite signal goes through multiple reflections or if reflection
     * plane serving is not supported.
     */
    public boolean hasReflectingPlane() {
        return (mFlags & HAS_REFLECTING_PLANE_MASK) != 0;
    }

    /**
     * Returns the reflecting plane characteristics at which the signal has bounced.
     *
     * <p>{@link #hasReflectingPlane()} must be true when calling this method. Otherwise, an
     * {@link UnsupportedOperationException} will be thrown.
     */
    @NonNull
    public GnssReflectingPlane getReflectingPlane() {
        if (!hasReflectingPlane()) {
            throw new UnsupportedOperationException(
                    "getReflectingPlane() is not supported when hasReflectingPlane() is false");
        }
        return mReflectingPlane;
    }

    /** Returns {@code true} if {@link #getAttenuationDb()} is valid. */
    public boolean hasAttenuation() {
        return (mFlags & HAS_ATTENUATION_MASK) != 0;
    }

    /**
     * Returns the expected reduction of signal strength of this path in non-negative dB.
     *
     * <p>{@link #hasAttenuation()} must be true when calling this method. Otherwise, an
     * {@link UnsupportedOperationException} will be thrown.
     */
    @FloatRange(from = 0.0f)
    public float getAttenuationDb() {
        if (!hasAttenuation()) {
            throw new UnsupportedOperationException(
                    "getAttenuationDb() is not supported when hasAttenuation() is false");
        }
        return mAttenuationDb;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelFlags) {
        parcel.writeInt(mFlags);
        if (hasExcessPathLength()) {
            parcel.writeFloat(mExcessPathLengthMeters);
        }
        if (hasExcessPathLengthUncertainty()) {
            parcel.writeFloat(mExcessPathLengthUncertaintyMeters);
        }
        if (hasReflectingPlane()) {
            mReflectingPlane.writeToParcel(parcel, parcelFlags);
        }
        if (hasAttenuation()) {
            parcel.writeFloat(mAttenuationDb);
        }
    }

    public static final @NonNull Creator<GnssExcessPathInfo> CREATOR =
            new Creator<GnssExcessPathInfo>() {
                @Override
                @NonNull
                public GnssExcessPathInfo createFromParcel(@NonNull Parcel parcel) {
                    int flags = parcel.readInt();
                    float excessPathLengthMeters =
                            (flags & HAS_EXCESS_PATH_LENGTH_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    float excessPathLengthUncertaintyMeters =
                            (flags & HAS_EXCESS_PATH_LENGTH_UNC_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    GnssReflectingPlane reflectingPlane =
                            (flags & HAS_REFLECTING_PLANE_MASK) != 0
                                    ? GnssReflectingPlane.CREATOR.createFromParcel(parcel) : null;
                    float attenuationDb =
                            (flags & HAS_ATTENUATION_MASK) != 0
                                    ? parcel.readFloat() : 0;
                    return new GnssExcessPathInfo(flags, excessPathLengthMeters,
                            excessPathLengthUncertaintyMeters, reflectingPlane, attenuationDb);
                }

                @Override
                public GnssExcessPathInfo[] newArray(int i) {
                    return new GnssExcessPathInfo[i];
                }
            };

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof GnssExcessPathInfo) {
            GnssExcessPathInfo that = (GnssExcessPathInfo) obj;
            return this.mFlags == that.mFlags
                    && (!hasExcessPathLength() || Float.compare(this.mExcessPathLengthMeters,
                    that.mExcessPathLengthMeters) == 0)
                    && (!hasExcessPathLengthUncertainty() || Float.compare(
                    this.mExcessPathLengthUncertaintyMeters,
                    that.mExcessPathLengthUncertaintyMeters) == 0)
                    && (!hasReflectingPlane() || Objects.equals(this.mReflectingPlane,
                    that.mReflectingPlane))
                    && (!hasAttenuation() || Float.compare(this.mAttenuationDb,
                    that.mAttenuationDb) == 0);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFlags,
                mExcessPathLengthMeters,
                mExcessPathLengthUncertaintyMeters,
                mReflectingPlane,
                mAttenuationDb);
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("GnssExcessPathInfo[");
        if (hasExcessPathLength()) {
            builder.append(" ExcessPathLengthMeters=").append(mExcessPathLengthMeters);
        }
        if (hasExcessPathLengthUncertainty()) {
            builder.append(" ExcessPathLengthUncertaintyMeters=").append(
                    mExcessPathLengthUncertaintyMeters);
        }
        if (hasReflectingPlane()) {
            builder.append(" ReflectingPlane=").append(mReflectingPlane);
        }
        if (hasAttenuation()) {
            builder.append(" AttenuationDb=").append(mAttenuationDb);
        }
        builder.append(']');
        return builder.toString();
    }

    /** Builder for {@link GnssExcessPathInfo}. */
    public static final class Builder {
        private int mFlags;
        private float mExcessPathLengthMeters;
        private float mExcessPathLengthUncertaintyMeters;
        @Nullable
        private GnssReflectingPlane mReflectingPlane;
        private float mAttenuationDb;

        /** Constructor for {@link Builder}. */
        public Builder() {}

        /**
         * Sets the excess path length to be subtracted from pseudorange before using it in
         * calculating location.
         */
        @NonNull
        public Builder setExcessPathLengthMeters(
                @FloatRange(from = 0.0f) float excessPathLengthMeters) {
            Preconditions.checkArgumentInRange(excessPathLengthMeters, 0, Float.MAX_VALUE,
                    "excessPathLengthMeters");
            mExcessPathLengthMeters = excessPathLengthMeters;
            mFlags |= HAS_EXCESS_PATH_LENGTH_MASK;
            return this;
        }

        /**
         * Clears the excess path length.
         *
         * <p>This is to negate {@link #setExcessPathLengthMeters} call.
         */
        @NonNull
        public Builder clearExcessPathLengthMeters() {
            mExcessPathLengthMeters = 0;
            mFlags &= ~HAS_EXCESS_PATH_LENGTH_MASK;
            return this;
        }

        /** Sets the error estimate (1-sigma) for the excess path length estimate */
        @NonNull
        public Builder setExcessPathLengthUncertaintyMeters(
                @FloatRange(from = 0.0f) float excessPathLengthUncertaintyMeters) {
            Preconditions.checkArgumentInRange(excessPathLengthUncertaintyMeters, 0,
                    Float.MAX_VALUE, "excessPathLengthUncertaintyMeters");
            mExcessPathLengthUncertaintyMeters = excessPathLengthUncertaintyMeters;
            mFlags |= HAS_EXCESS_PATH_LENGTH_UNC_MASK;
            return this;
        }

        /**
         * Clears the error estimate (1-sigma) for the excess path length estimate
         *
         * <p>This is to negate {@link #setExcessPathLengthUncertaintyMeters} call.
         */
        @NonNull
        public Builder clearExcessPathLengthUncertaintyMeters() {
            mExcessPathLengthUncertaintyMeters = 0;
            mFlags &= ~HAS_EXCESS_PATH_LENGTH_UNC_MASK;
            return this;
        }

        /** Sets the reflecting plane information */
        @NonNull
        public Builder setReflectingPlane(@Nullable GnssReflectingPlane reflectingPlane) {
            mReflectingPlane = reflectingPlane;
            if (reflectingPlane != null) {
                mFlags |= HAS_REFLECTING_PLANE_MASK;
            } else {
                mFlags &= ~HAS_REFLECTING_PLANE_MASK;
            }
            return this;
        }

        /**
         * Sets the attenuation value in dB.
         */
        @NonNull
        public Builder setAttenuationDb(@FloatRange(from = 0.0f) float attenuationDb) {
            Preconditions.checkArgumentInRange(attenuationDb, 0, Float.MAX_VALUE,
                    "attenuationDb");
            mAttenuationDb = attenuationDb;
            mFlags |= HAS_ATTENUATION_MASK;
            return this;
        }

        /**
         * Clears the attenuation value in dB.
         *
         * <p>This is to negate {@link #setAttenuationDb(float)} call.
         */
        @NonNull
        public Builder clearAttenuationDb() {
            mAttenuationDb = 0;
            mFlags &= ~HAS_ATTENUATION_MASK;
            return this;
        }

        /** Builds a {@link GnssExcessPathInfo} instance as specified by this builder. */
        @NonNull
        public GnssExcessPathInfo build() {
            return new GnssExcessPathInfo(
                    mFlags,
                    mExcessPathLengthMeters,
                    mExcessPathLengthUncertaintyMeters,
                    mReflectingPlane,
                    mAttenuationDb);
        }
    }
}
