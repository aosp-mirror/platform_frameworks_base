/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY;
import static android.location.LocationRequest.QUALITY_HIGH_ACCURACY;
import static android.location.LocationRequest.QUALITY_LOW_POWER;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.location.LocationRequest;
import android.location.LocationRequest.Quality;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.WorkSource;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Location provider request.
 * @hide
 */
@SystemApi
public final class ProviderRequest implements Parcelable {

    public static final long INTERVAL_DISABLED = Long.MAX_VALUE;

    public static final @NonNull ProviderRequest EMPTY_REQUEST = new ProviderRequest(
            INTERVAL_DISABLED,
            QUALITY_BALANCED_POWER_ACCURACY,
            0,
            false,
            false,
            false,
            new WorkSource());

    private final long mIntervalMillis;
    private final @Quality int mQuality;
    private final long mMaxUpdateDelayMillis;
    private final boolean mLowPower;
    private final boolean mAdasGnssBypass;
    private final boolean mLocationSettingsIgnored;
    private final WorkSource mWorkSource;

    /**
     * Listener to be invoked when a new request is set to the provider.
     */
    public interface ChangedListener {

        /**
         * Invoked when a new request is set.
         *
         * @param provider the location provider associated with the request
         * @param request the new {@link ProviderRequest}
         */
        void onProviderRequestChanged(@NonNull String provider, @NonNull ProviderRequest request);
    }

    private ProviderRequest(
            long intervalMillis,
            @Quality int quality,
            long maxUpdateDelayMillis,
            boolean lowPower,
            boolean adasGnssBypass,
            boolean locationSettingsIgnored,
            @NonNull WorkSource workSource) {
        mIntervalMillis = intervalMillis;
        mQuality = quality;
        mMaxUpdateDelayMillis = maxUpdateDelayMillis;
        mLowPower = lowPower;
        mAdasGnssBypass = adasGnssBypass;
        mLocationSettingsIgnored = locationSettingsIgnored;
        mWorkSource = Objects.requireNonNull(workSource);
    }

    /**
     * True if this is an active request with a valid location reporting interval, false if this
     * request is inactive and does not require any locations to be reported.
     */
    public boolean isActive() {
        return mIntervalMillis != INTERVAL_DISABLED;
    }

    /**
     * The interval at which a provider should report location. Will return
     * {@link #INTERVAL_DISABLED} for an inactive request.
     */
    public @IntRange(from = 0) long getIntervalMillis() {
        return mIntervalMillis;
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
     * The maximum time any location update may be delayed, and thus grouped with following updates
     * to enable location batching. If the maximum update delay is equal to or greater than
     * twice the interval, then the provider may provide batched results if possible. The maximum
     * batch size a provider is allowed to return is the maximum update delay divided by the
     * interval.
     */
    public @IntRange(from = 0) long getMaxUpdateDelayMillis() {
        return mMaxUpdateDelayMillis;
    }

    /**
     * Whether any applicable hardware low power modes should be used to satisfy this request.
     */
    public boolean isLowPower() {
        return mLowPower;
    }

    /**
     * Returns true if this request may access GNSS even if location settings would normally deny
     * this, in order to enable automotive safety features. This field is only respected on
     * automotive devices, and only if the client is recognized as a legitimate ADAS (Advanced
     * Driving Assistance Systems) application.
     *
     * @hide
     */
    public boolean isAdasGnssBypass() {
        return mAdasGnssBypass;
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
     * Returns true if any bypass flag is set on this request.
     *
     * @hide
     */
    public boolean isBypass() {
        return mAdasGnssBypass || mLocationSettingsIgnored;
    }

    /**
     * The power blame for this provider request.
     */
    public @NonNull WorkSource getWorkSource() {
        return mWorkSource;
    }

    public static final @NonNull Creator<ProviderRequest> CREATOR = new Creator<ProviderRequest>() {
        @Override
        public ProviderRequest createFromParcel(Parcel in) {
            long intervalMillis = in.readLong();
            if (intervalMillis == INTERVAL_DISABLED) {
                return EMPTY_REQUEST;
            } else {
                return new ProviderRequest(
                        intervalMillis,
                        /* quality= */ in.readInt(),
                        /* maxUpdateDelayMillis= */ in.readLong(),
                        /* lowPower= */ in.readBoolean(),
                        /* adasGnssBypass= */ in.readBoolean(),
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
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeLong(mIntervalMillis);
        if (mIntervalMillis != INTERVAL_DISABLED) {
            parcel.writeInt(mQuality);
            parcel.writeLong(mMaxUpdateDelayMillis);
            parcel.writeBoolean(mLowPower);
            parcel.writeBoolean(mAdasGnssBypass);
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
        if (mIntervalMillis == INTERVAL_DISABLED) {
            return that.mIntervalMillis == INTERVAL_DISABLED;
        } else {
            return mIntervalMillis == that.mIntervalMillis
                    && mQuality == that.mQuality
                    && mMaxUpdateDelayMillis == that.mMaxUpdateDelayMillis
                    && mLowPower == that.mLowPower
                    && mAdasGnssBypass == that.mAdasGnssBypass
                    && mLocationSettingsIgnored == that.mLocationSettingsIgnored
                    && mWorkSource.equals(that.mWorkSource);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIntervalMillis, mQuality, mWorkSource);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("ProviderRequest[");
        if (mIntervalMillis != INTERVAL_DISABLED) {
            s.append("@");
            TimeUtils.formatDuration(mIntervalMillis, s);
            if (mQuality != QUALITY_BALANCED_POWER_ACCURACY) {
                if (mQuality == QUALITY_HIGH_ACCURACY) {
                    s.append(", HIGH_ACCURACY");
                } else if (mQuality == QUALITY_LOW_POWER) {
                    s.append(", LOW_POWER");
                }
            }
            if (mMaxUpdateDelayMillis / 2 > mIntervalMillis) {
                s.append(", maxUpdateDelay=");
                TimeUtils.formatDuration(mMaxUpdateDelayMillis, s);
            }
            if (mLowPower) {
                s.append(", lowPower");
            }
            if (mAdasGnssBypass) {
                s.append(", adasGnssBypass");
            }
            if (mLocationSettingsIgnored) {
                s.append(", settingsBypass");
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
    public static final class Builder {

        private long mIntervalMillis = INTERVAL_DISABLED;
        private int mQuality = QUALITY_BALANCED_POWER_ACCURACY;
        private long mMaxUpdateDelayMillis = 0;
        private boolean mLowPower;
        private boolean mAdasGnssBypass;
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
         * Sets the maximum time any location update may be delayed, and thus grouped with following
         * updates to enable location batching. If the maximum update delay is equal to or greater
         * than twice the interval, then location providers may provide batched results. Defaults to
         * 0.
         */
        public @NonNull Builder setMaxUpdateDelayMillis(
                @IntRange(from = 0) long maxUpdateDelayMillis) {
            mMaxUpdateDelayMillis = Preconditions.checkArgumentInRange(maxUpdateDelayMillis, 0,
                    Long.MAX_VALUE, "maxUpdateDelayMillis");
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
         * Sets whether this ADAS request should bypass GNSS settings. False by default.
         *
         * @hide
         */
        public @NonNull Builder setAdasGnssBypass(boolean adasGnssBypass) {
            this.mAdasGnssBypass = adasGnssBypass;
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
                return new ProviderRequest(
                        mIntervalMillis,
                        mQuality,
                        mMaxUpdateDelayMillis,
                        mLowPower,
                        mAdasGnssBypass,
                        mLocationSettingsIgnored,
                        mWorkSource);
            }
        }
    }
}
