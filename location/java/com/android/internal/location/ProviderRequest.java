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

import java.util.ArrayList;
import java.util.List;

import android.location.LocationRequest;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TimeUtils;

/** @hide */
public final class ProviderRequest implements Parcelable {
    /** Location reporting is requested (true) */
    public boolean reportLocation = false;

    /** The smallest requested interval */
    public long interval = Long.MAX_VALUE;

    /**
     * A more detailed set of requests.
     * <p>Location Providers can optionally use this to
     * fine tune location updates, for example when there
     * is a high power slow interval request and a
     * low power fast interval request.
     */
    public List<LocationRequest> locationRequests = new ArrayList<LocationRequest>();

    public ProviderRequest() { }

    public static final Parcelable.Creator<ProviderRequest> CREATOR =
            new Parcelable.Creator<ProviderRequest>() {
        @Override
        public ProviderRequest createFromParcel(Parcel in) {
            ProviderRequest request = new ProviderRequest();
            request.reportLocation = in.readInt() == 1;
            request.interval = in.readLong();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                request.locationRequests.add(LocationRequest.CREATOR.createFromParcel(in));
            }
            return request;
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
            s.append("ON");
            s.append(" interval=");
            TimeUtils.formatDuration(interval, s);
        } else {
            s.append("OFF");
        }
        s.append(']');
        return s.toString();
    }
}
