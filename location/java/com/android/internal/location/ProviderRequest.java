/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.location;

import static android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY;
import static android.location.LocationRequest.QUALITY_HIGH_ACCURACY;
import static android.location.LocationRequest.QUALITY_LOW_POWER;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.location.LocationRequest;
import android.location.LocationRequest.Quality;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.WorkSource;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Location provider request.
 * @hide
 */
public final class ProviderRequest implements Parcelable {

    public static final long INTERVAL_DISABLED = Long.MAX_VALUE;

    public static final ProviderRequest EMPTY_REQUEST = new ProviderRequest(
            INTERVAL_DISABLED, QUALITY_BALANCED_POWER_ACCURACY, false, false, new WorkSource());

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@link "
            + "ProviderRequest}")
    public final boolean reportLocation;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@link "
            + "ProviderRequest}")
    public final long interval;
    private final @Quality int mQuality;
    private final boolean mLowPower;
    private final boolean mLocationSettingsIgnored;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@link "
            + "ProviderRequest}")
    public final List<LocationRequest> locationRequests;
    private final WorkSource mWorkSource;

    private ProviderRequest(long intervalMillis, @Quality int quality, boolean lowPower,
            boolean locationSettingsIgnored, @NonNull WorkSource workSource) {
        reportLocation = intervalMillis != INTERVAL_DISABLED;
        interval = intervalMillis;
        mQuality = quality;
        mLowPower = lowPower;
        mLocationSettingsIgnored = locationSettingsIgnored;
        if (intervalMillis != INTERVAL_DISABLED) {
            locationRequests = Collections.singletonList(new LocationRequest.Builder(intervalMillis)
                    .setQuality(quality)
                    .setLowPower(lowPower)
                    .setLocationSettingsIgnored(locationSettingsIgnored)
                    .setWorkSource(workSource)
                    .build());
        } else {
            locationRequests = Collections.emptyList();
        }
        mWorkSource = Objects.requireNonNull(workSource);
    }

    /**
     * True if this is an active request with a valid location reporting interval, false if this
     * request is inactive and does not require any locations to be reported.
     */
    public boolean isActive() {
        return interval != INTERVAL_DISABLED;
    }

    /**
     * The interval at which a provider should report location. Will return
     * {@link #INTERVAL_DISABLED} for an inactive request.
     */
    public @IntRange(from = 0) long getIntervalMillis() {
        return interval;
    }

    /**
     * The quality hint for this location request. The quality hint informs the provider how it
     * should attempt to manage any accuracy vs power tradeoffs while attempting to satisfy this
     * provider request.
     */
    public @Quality int getQuality() {
        return mQuality;
    }

    /**
     * Whether any applicable hardware low power modes should be used to satisfy this request.
     */
    public boolean isLowPower() {
        return mLowPower;
    }

    /**
     * Whether the provider should ignore all location settings, user consents, power restrictions
     * or any other restricting factors and always satisfy this request to the best of their
     * ability. This should only be used in case of a user initiated emergency.
     */
    public boolean isLocationSettingsIgnored() {
        return mLocationSettingsIgnored;
    }

    /**
     * The power blame for this provider request.
     */
    public @NonNull WorkSource getWorkSource() {
        return mWorkSource;
    }

    public static final Parcelable.Creator<ProviderRequest> CREATOR =
            new Parcelable.Creator<ProviderRequest>() {
                @Override
                public ProviderRequest createFromParcel(Parcel in) {
                    long intervalMillis = in.readLong();
                    if (intervalMillis == INTERVAL_DISABLED) {
                        return EMPTY_REQUEST;
                    } else {
                        return new ProviderRequest(
                                intervalMillis,
                                /* quality= */ in.readInt(),
                                /* lowPower= */ in.readBoolean(),
                                /* locationSettingsIgnored= */ in.readBoolean(),
                                /* workSource= */ in.readTypedObject(WorkSource.CREATOR));
                    }
                }

                @Override
                public ProviderRequest[] newArray(int size) {
                    return new ProviderRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeLong(interval);
        if (interval != INTERVAL_DISABLED) {
            parcel.writeInt(mQuality);
            parcel.writeBoolean(mLowPower);
            parcel.writeBoolean(mLocationSettingsIgnored);
            parcel.writeTypedObject(mWorkSource, flags);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ProviderRequest that = (ProviderRequest) o;
        if (interval == INTERVAL_DISABLED) {
            return that.interval == INTERVAL_DISABLED;
        } else {
            return interval == that.interval
                    && mQuality == that.mQuality
                    && mLowPower == that.mLowPower
                    && mLocationSettingsIgnored == that.mLocationSettingsIgnored
                    && mWorkSource.equals(that.mWorkSource);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(interval, mQuality, mWorkSource);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("ProviderRequest[");
        if (interval != INTERVAL_DISABLED) {
            s.append("@");
            TimeUtils.formatDuration(interval, s);
            if (mQuality != QUALITY_BALANCED_POWER_ACCURACY) {
                if (mQuality == QUALITY_HIGH_ACCURACY) {
                    s.append(", HIGH_ACCURACY");
                } else if (mQuality == QUALITY_LOW_POWER) {
                    s.append(", LOW_POWER");
                }
            }
            if (mLowPower) {
                s.append(", lowPower");
            }
            if (mLocationSettingsIgnored) {
                s.append(", locationSettingsIgnored");
            }
            if (!mWorkSource.isEmpty()) {
                s.append(", ").append(mWorkSource);
            }
        } else {
            s.append("OFF");
        }
        s.append(']');
        return s.toString();
    }

    /**
     * A Builder for {@link ProviderRequest}s.
     */
    public static class Builder {
        private long mIntervalMillis = INTERVAL_DISABLED;
        private int mQuality = QUALITY_BALANCED_POWER_ACCURACY;
        private boolean mLowPower;
        private boolean mLocationSettingsIgnored;
        private WorkSource mWorkSource = new WorkSource();

        /**
         * Sets the request interval. Use {@link #INTERVAL_DISABLED} for an inactive request.
         * Defaults to {@link #INTERVAL_DISABLED}.
         */
        public @NonNull Builder setIntervalMillis(@IntRange(from = 0) long intervalMillis) {
            mIntervalMillis = Preconditions.checkArgumentInRange(intervalMillis, 0, Long.MAX_VALUE,
                    "intervalMillis");
            return this;
        }

        /**
         * Sets the request quality. The quality is a hint to providers on how they should weigh
         * power vs accuracy tradeoffs. High accuracy locations may cost more power to produce, and
         * lower accuracy locations may cost less power to produce. Defaults to
         * {@link LocationRequest#QUALITY_BALANCED_POWER_ACCURACY}.
         */
        public @NonNull Builder setQuality(@Quality int quality) {
            Preconditions.checkArgument(
                    quality == QUALITY_LOW_POWER || quality == QUALITY_BALANCED_POWER_ACCURACY
                            || quality == QUALITY_HIGH_ACCURACY);
            mQuality = quality;
            return this;
        }

        /**
         * Sets whether hardware low power mode should be used. False by default.
         */
        public @NonNull Builder setLowPower(boolean lowPower) {
            mLowPower = lowPower;
            return this;
        }

        /**
         * Sets whether location settings should be ignored. False by default.
         */
        public @NonNull Builder setLocationSettingsIgnored(boolean locationSettingsIgnored) {
            this.mLocationSettingsIgnored = locationSettingsIgnored;
            return this;
        }

        /**
         * Sets the work source for power blame. Empty by default.
         */
        public @NonNull Builder setWorkSource(@NonNull WorkSource workSource) {
            mWorkSource = Objects.requireNonNull(workSource);
            return this;
        }

        /**
         * Builds a ProviderRequest.
         */
        public @NonNull ProviderRequest build() {
            if (mIntervalMillis == INTERVAL_DISABLED) {
                return EMPTY_REQUEST;
            } else {
                return new ProviderRequest(mIntervalMillis, mQuality, mLowPower,
                        mLocationSettingsIgnored, mWorkSource);
            }
        }
    }
}
