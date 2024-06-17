/*
 * Copyright 2023 The Android Open Source Project
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

package android.location.provider;

import static java.lang.Math.max;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.location.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Locale;
import java.util.Objects;

/**
 * Forward geocode (ie from address to lat/lng) provider request.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_NEW_GEOCODER)
@SystemApi
public final class ForwardGeocodeRequest implements Parcelable {

    private final String mLocationName;
    private final double mLowerLeftLatitude;
    private final double mLowerLeftLongitude;
    private final double mUpperRightLatitude;
    private final double mUpperRightLongitude;
    private final int mMaxResults;
    private final Locale mLocale;
    private final int mCallingUid;
    private final String mCallingPackage;
    @Nullable private final String mCallingAttributionTag;

    private ForwardGeocodeRequest(
            @NonNull String locationName,
            double lowerLeftLatitude,
            double lowerLeftLongitude,
            double upperRightLatitude,
            double upperRightLongitude,
            int maxResults,
            @NonNull Locale locale,
            int callingUid,
            @NonNull String callingPackage,
            @Nullable String callingAttributionTag) {
        Preconditions.checkArgument(locationName != null, "locationName must not be null");
        Preconditions.checkArgumentInRange(lowerLeftLatitude, -90.0, 90.0, "lowerLeftLatitude");
        Preconditions.checkArgumentInRange(lowerLeftLongitude, -180.0, 180.0, "lowerLeftLongitude");
        Preconditions.checkArgumentInRange(upperRightLatitude, -90.0, 90.0, "upperRightLatitude");
        Preconditions.checkArgumentInRange(
                upperRightLongitude, -180.0, 180.0, "upperRightLongitude");

        mLocationName = locationName;
        mLowerLeftLatitude = lowerLeftLatitude;
        mLowerLeftLongitude = lowerLeftLongitude;
        mUpperRightLatitude = upperRightLatitude;
        mUpperRightLongitude = upperRightLongitude;
        mMaxResults = max(maxResults, 1);
        mLocale = Objects.requireNonNull(locale);

        mCallingUid = callingUid;
        mCallingPackage = Objects.requireNonNull(callingPackage);
        mCallingAttributionTag = callingAttributionTag;
    }

    /**
     * The location name to be forward geocoded. An arbitrary user string that could have any value.
     */
    @NonNull
    public String getLocationName() {
        return mLocationName;
    }

    /** The lower left latitude of the bounding box that should constrain forward geocoding. */
    @FloatRange(from = -90.0, to = 90.0)
    public double getLowerLeftLatitude() {
        return mLowerLeftLatitude;
    }

    /** The lower left longitude of the bounding box that should constrain forward geocoding. */
    @FloatRange(from = -180.0, to = 180.0)
    public double getLowerLeftLongitude() {
        return mLowerLeftLongitude;
    }

    /** The upper right latitude of the bounding box that should constrain forward geocoding. */
    @FloatRange(from = -90.0, to = 90.0)
    public double getUpperRightLatitude() {
        return mUpperRightLatitude;
    }

    /** The upper right longitude of the bounding box that should constrain forward geocoding. */
    @FloatRange(from = -180.0, to = 180.0)
    public double getUpperRightLongitude() {
        return mUpperRightLongitude;
    }

    /** The maximum number of forward geocoding results that should be returned. */
    @IntRange(from = 1)
    public int getMaxResults() {
        return mMaxResults;
    }

    /** The locale that results should be localized to (best effort). */
    @NonNull
    public Locale getLocale() {
        return mLocale;
    }

    /** The UID of the caller this geocoding request is happening on behalf of. */
    public int getCallingUid() {
        return mCallingUid;
    }

    /** The package of the caller this geocoding request is happening on behalf of. */
    @NonNull
    public String getCallingPackage() {
        return mCallingPackage;
    }

    /** The attribution tag of the caller this geocoding request is happening on behalf of. */
    @Nullable
    public String getCallingAttributionTag() {
        return mCallingAttributionTag;
    }

    public static final @NonNull Creator<ForwardGeocodeRequest> CREATOR =
            new Creator<>() {
                @Override
                public ForwardGeocodeRequest createFromParcel(Parcel in) {
                    return new ForwardGeocodeRequest(
                            Objects.requireNonNull(in.readString8()),
                            in.readDouble(),
                            in.readDouble(),
                            in.readDouble(),
                            in.readDouble(),
                            in.readInt(),
                            new Locale(in.readString8(), in.readString8(), in.readString8()),
                            in.readInt(),
                            Objects.requireNonNull(in.readString8()),
                            in.readString8());
                }

                @Override
                public ForwardGeocodeRequest[] newArray(int size) {
                    return new ForwardGeocodeRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeString8(mLocationName);
        parcel.writeDouble(mLowerLeftLatitude);
        parcel.writeDouble(mLowerLeftLongitude);
        parcel.writeDouble(mUpperRightLatitude);
        parcel.writeDouble(mUpperRightLongitude);
        parcel.writeInt(mMaxResults);
        parcel.writeString8(mLocale.getLanguage());
        parcel.writeString8(mLocale.getCountry());
        parcel.writeString8(mLocale.getVariant());
        parcel.writeInt(mCallingUid);
        parcel.writeString8(mCallingPackage);
        parcel.writeString8(mCallingAttributionTag);
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (object instanceof ForwardGeocodeRequest that) {
            return mLowerLeftLatitude == that.mLowerLeftLatitude
                    && mLowerLeftLongitude == that.mLowerLeftLongitude
                    && mUpperRightLatitude == that.mUpperRightLatitude
                    && mUpperRightLongitude == that.mUpperRightLongitude
                    && mMaxResults == that.mMaxResults
                    && mCallingUid == that.mCallingUid
                    && mLocale.equals(that.mLocale)
                    && mCallingPackage.equals(that.mCallingPackage)
                    && mLocationName.equals(that.mLocationName)
                    && Objects.equals(mCallingAttributionTag, that.mCallingAttributionTag);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mLocationName,
                mLowerLeftLatitude,
                mLowerLeftLongitude,
                mUpperRightLatitude,
                mUpperRightLongitude,
                mMaxResults,
                mLocale,
                mCallingUid,
                mCallingPackage,
                mCallingAttributionTag);
    }

    /** A Builder for {@link ReverseGeocodeRequest}s. */
    public static final class Builder {

        private final String mLocationName;
        private final double mLowerLeftLatitude;
        private final double mLowerLeftLongitude;
        private final double mUpperRightLatitude;
        private final double mUpperRightLongitude;
        private final int mMaxResults;
        private final Locale mLocale;

        private final int mCallingUid;
        private final String mCallingPackage;
        @Nullable private String mCallingAttributionTag;

        /** Creates a new Builder instance with the given parameters. */
        public Builder(
                @NonNull String locationName,
                @FloatRange(from = -90.0, to = 90.0) double lowerLeftLatitude,
                @FloatRange(from = -180.0, to = 180.0) double lowerLeftLongitude,
                @FloatRange(from = -90.0, to = 90.0) double upperRightLatitude,
                @FloatRange(from = -180.0, to = 180.0) double upperRightLongitude,
                @IntRange(from = 1) int maxResults,
                @NonNull Locale locale,
                int callingUid,
                @NonNull String callingPackage) {
            mLocationName = locationName;
            mLowerLeftLatitude = lowerLeftLatitude;
            mLowerLeftLongitude = lowerLeftLongitude;
            mUpperRightLatitude = upperRightLatitude;
            mUpperRightLongitude = upperRightLongitude;
            mMaxResults = maxResults;
            mLocale = locale;
            mCallingUid = callingUid;
            mCallingPackage = callingPackage;
            mCallingAttributionTag = null;
        }

        /** Sets the attribution tag. */
        @NonNull
        public Builder setCallingAttributionTag(@NonNull String attributionTag) {
            mCallingAttributionTag = attributionTag;
            return this;
        }

        /** Builds a {@link ForwardGeocodeRequest}. */
        @NonNull
        public ForwardGeocodeRequest build() {
            return new ForwardGeocodeRequest(
                    mLocationName,
                    mLowerLeftLatitude,
                    mLowerLeftLongitude,
                    mUpperRightLatitude,
                    mUpperRightLongitude,
                    mMaxResults,
                    mLocale,
                    mCallingUid,
                    mCallingPackage,
                    mCallingAttributionTag);
        }
    }
}
