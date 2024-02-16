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
 * Reverse geocode (ie from lat/lng to address) provider request.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_NEW_GEOCODER)
@SystemApi
public final class ReverseGeocodeRequest implements Parcelable {

    private final double mLatitude;
    private final double mLongitude;
    private final int mMaxResults;
    private final Locale mLocale;

    private final int mCallingUid;
    private final String mCallingPackage;
    @Nullable private final String mCallingAttributionTag;

    private ReverseGeocodeRequest(
            double latitude,
            double longitude,
            int maxResults,
            Locale locale,
            int callingUid,
            String callingPackage,
            @Nullable String callingAttributionTag) {
        Preconditions.checkArgumentInRange(latitude, -90.0, 90.0, "latitude");
        Preconditions.checkArgumentInRange(longitude, -180.0, 180.0, "longitude");

        mLatitude = latitude;
        mLongitude = longitude;
        mMaxResults = max(maxResults, 1);
        mLocale = Objects.requireNonNull(locale);

        mCallingUid = callingUid;
        mCallingPackage = Objects.requireNonNull(callingPackage);
        mCallingAttributionTag = callingAttributionTag;
    }

    /** The latitude of the point to be reverse geocoded. */
    @FloatRange(from = -90.0, to = 90.0)
    public double getLatitude() {
        return mLatitude;
    }

    /** The longitude of the point to be reverse geocoded. */
    @FloatRange(from = -180.0, to = 180.0)
    public double getLongitude() {
        return mLongitude;
    }

    /** The maximum number of reverse geocoding results that should be returned. */
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

    public static final @NonNull Creator<ReverseGeocodeRequest> CREATOR =
            new Creator<>() {
                @Override
                public ReverseGeocodeRequest createFromParcel(Parcel in) {
                    return new ReverseGeocodeRequest(
                            in.readDouble(),
                            in.readDouble(),
                            in.readInt(),
                            new Locale(in.readString8(), in.readString8(), in.readString8()),
                            in.readInt(),
                            Objects.requireNonNull(in.readString8()),
                            in.readString8());
                }

                @Override
                public ReverseGeocodeRequest[] newArray(int size) {
                    return new ReverseGeocodeRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeDouble(mLatitude);
        parcel.writeDouble(mLongitude);
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
        if (object instanceof ReverseGeocodeRequest that) {
            return mLatitude == that.mLatitude
                    && mLongitude == that.mLongitude
                    && mMaxResults == that.mMaxResults
                    && mCallingUid == that.mCallingUid
                    && mLocale.equals(that.mLocale)
                    && mCallingPackage.equals(that.mCallingPackage)
                    && Objects.equals(mCallingAttributionTag, that.mCallingAttributionTag);
        }

        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mLatitude,
                mLongitude,
                mMaxResults,
                mLocale,
                mCallingUid,
                mCallingPackage,
                mCallingAttributionTag);
    }

    /** A Builder for {@link ReverseGeocodeRequest}s. */
    public static final class Builder {

        private final double mLatitude;
        private final double mLongitude;
        private final int mMaxResults;
        private final Locale mLocale;

        private final int mCallingUid;
        private final String mCallingPackage;
        @Nullable private String mCallingAttributionTag;

        /** Creates a new Builder instance with the given parameters. */
        public Builder(
                @FloatRange(from = -90.0, to = 90.0) double latitude,
                @FloatRange(from = -180.0, to = 180.0) double longitude,
                @IntRange(from = 0) int maxResults,
                @NonNull Locale locale,
                int callingUid,
                @NonNull String callingPackage) {
            mLatitude = latitude;
            mLongitude = longitude;
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

        /** Builds a {@link ReverseGeocodeRequest}. */
        @NonNull
        public ReverseGeocodeRequest build() {
            return new ReverseGeocodeRequest(
                    mLatitude,
                    mLongitude,
                    mMaxResults,
                    mLocale,
                    mCallingUid,
                    mCallingPackage,
                    mCallingAttributionTag);
        }
    }
}
