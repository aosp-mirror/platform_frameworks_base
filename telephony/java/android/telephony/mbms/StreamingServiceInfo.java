/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony.mbms;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Describes a single MBMS streaming service.
 */
public final class StreamingServiceInfo extends ServiceInfo implements Parcelable {

    /**
     * @param names User displayable names listed by language.
     * @param className The class name for this service - used by frontend apps to categorize and
     *                  filter.
     * @param locales The languages available for this service content.
     * @param serviceId The carrier's identifier for the service.
     * @param start The start time indicating when this service will be available.
     * @param end The end time indicating when this session stops being available.
     * @hide
     */
    @SystemApi
    @TestApi
    public StreamingServiceInfo(Map<Locale, String> names, String className,
            List<Locale> locales, String serviceId, Date start, Date end) {
        super(names, className, locales, serviceId, start, end);
    }

    public static final Parcelable.Creator<StreamingServiceInfo> CREATOR =
            new Parcelable.Creator<StreamingServiceInfo>() {
        @Override
        public StreamingServiceInfo createFromParcel(Parcel source) {
            return new StreamingServiceInfo(source);
        }

        @Override
        public StreamingServiceInfo[] newArray(int size) {
            return new StreamingServiceInfo[size];
        }
    };

    private StreamingServiceInfo(Parcel in) {
        super(in);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
