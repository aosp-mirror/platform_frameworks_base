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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Parcelable class Cell-Broadcast media stream information.
 * This may not have any more info than ServiceInfo, but kept for completeness.
 * @hide
 */
public class StreamingServiceInfo extends ServiceInfo implements Parcelable {

    public StreamingServiceInfo(Map<Locale, String> newNames, String newClassName,
            List<Locale> newLocales, String newServiceId, Date start, Date end) {
        super(newNames, newClassName, newLocales, newServiceId, start, end);
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

    StreamingServiceInfo(Parcel in) {
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
