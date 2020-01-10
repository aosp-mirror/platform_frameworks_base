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

import android.compat.annotation.UnsupportedAppUsage;
import android.location.LocationRequest;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.WorkSource;
import android.util.TimeUtils;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** @hide */
public final class ProviderRequest implements Parcelable {

    public static final ProviderRequest EMPTY_REQUEST = new ProviderRequest(false, Long.MAX_VALUE,
            false, false,
            Collections.emptyList(), new WorkSource());

    /** Location reporting is requested (true) */
    @UnsupportedAppUsage
    public final boolean reportLocation;

    /** The smallest requested interval */
    @UnsupportedAppUsage
    public final long interval;

    /**
     * Whether provider shall make stronger than normal tradeoffs to substantially restrict power
     * use.
     */
    public final boolean lowPowerMode;

    /**
     * When this flag is true, providers should ignore all location settings, user consents, power
     * restrictions or any other restricting factors and always satisfy this request to the best of
     * their ability. This flag should only be used in event of an emergency.
     */
    public final boolean locationSettingsIgnored;

    /**
     * A more detailed set of requests.
     * <p>Location Providers can optionally use this to
     * fine tune location updates, for example when there
     * is a high power slow interval request and a
     * low power fast interval request.
     */
    @UnsupportedAppUsage
    public final List<LocationRequest> locationRequests;

    public final WorkSource workSource;

    private ProviderRequest(boolean reportLocation, long interval, boolean lowPowerMode,
            boolean locationSettingsIgnored, List<LocationRequest> locationRequests,
            WorkSource workSource) {
        this.reportLocation = reportLocation;
        this.interval = interval;
        this.lowPowerMode = lowPowerMode;
        this.locationSettingsIgnored = locationSettingsIgnored;
        this.locationRequests = Preconditions.checkNotNull(locationRequests);
        this.workSource = Preconditions.checkNotNull(workSource);
    }

    public static final Parcelable.Creator<ProviderRequest> CREATOR =
            new Parcelable.Creator<ProviderRequest>() {
                @Override
                public ProviderRequest createFromParcel(Parcel in) {
                    boolean reportLocation = in.readInt() == 1;
                    long interval = in.readLong();
                    boolean lowPowerMode = in.readBoolean();
                    boolean locationSettingsIgnored = in.readBoolean();
                    int count = in.readInt();
                    ArrayList<LocationRequest> locationRequests = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        locationRequests.add(LocationRequest.CREATOR.createFromParcel(in));
                    }
                    WorkSource workSource = in.readParcelable(null);
                    return new ProviderRequest(reportLocation, interval, lowPowerMode,
                            locationSettingsIgnored, locationRequests, workSource);
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
        parcel.writeInt(reportLocation ? 1 : 0);
        parcel.writeLong(interval);
        parcel.writeBoolean(lowPowerMode);
        parcel.writeBoolean(locationSettingsIgnored);
        parcel.writeInt(locationRequests.size());
        for (LocationRequest request : locationRequests) {
            request.writeToParcel(parcel, flags);
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("ProviderRequest[");
        if (reportLocation) {
            s.append("interval=");
            TimeUtils.formatDuration(interval, s);
            if (lowPowerMode) {
                s.append(", lowPowerMode");
            }
            if (locationSettingsIgnored) {
                s.append(", locationSettingsIgnored");
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
        private long mInterval = Long.MAX_VALUE;
        private boolean mLowPowerMode;
        private boolean mLocationSettingsIgnored;
        private List<LocationRequest> mLocationRequests = Collections.emptyList();
        private WorkSource mWorkSource = new WorkSource();

        public long getInterval() {
            return mInterval;
        }

        public void setInterval(long interval) {
            this.mInterval = interval;
        }

        public boolean isLowPowerMode() {
            return mLowPowerMode;
        }

        public void setLowPowerMode(boolean lowPowerMode) {
            this.mLowPowerMode = lowPowerMode;
        }

        public boolean isLocationSettingsIgnored() {
            return mLocationSettingsIgnored;
        }

        public void setLocationSettingsIgnored(boolean locationSettingsIgnored) {
            this.mLocationSettingsIgnored = locationSettingsIgnored;
        }

        public List<LocationRequest> getLocationRequests() {
            return mLocationRequests;
        }

        public void setLocationRequests(List<LocationRequest> locationRequests) {
            this.mLocationRequests = Preconditions.checkNotNull(locationRequests);
        }

        public WorkSource getWorkSource() {
            return mWorkSource;
        }

        public void setWorkSource(WorkSource workSource) {
            mWorkSource = Preconditions.checkNotNull(workSource);
        }

        /**
         * Builds a ProviderRequest object with the set information.
         */
        public ProviderRequest build() {
            if (mInterval == Long.MAX_VALUE) {
                return EMPTY_REQUEST;
            } else {
                return new ProviderRequest(true, mInterval, mLowPowerMode,
                        mLocationSettingsIgnored, mLocationRequests, mWorkSource);
            }
        }
    }
}
