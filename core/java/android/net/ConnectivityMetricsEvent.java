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

package android.net;

import android.os.Parcel;
import android.os.Parcelable;

/** {@hide} */
public final class ConnectivityMetricsEvent implements Parcelable {

    /**  The time when this event was collected, as returned by System.currentTimeMillis(). */
    public long timestamp;

    /** Opaque event-specific data. */
    public Parcelable data;

    public ConnectivityMetricsEvent() {
    }

    public ConnectivityMetricsEvent(Parcel in) {
        timestamp = in.readLong();
        data = in.readParcelable(null);
    }

    /** Implement the Parcelable interface */
    public static final Parcelable.Creator<ConnectivityMetricsEvent> CREATOR
            = new Parcelable.Creator<ConnectivityMetricsEvent> (){
        public ConnectivityMetricsEvent createFromParcel(Parcel source) {
            return new ConnectivityMetricsEvent(source);
        }

        public ConnectivityMetricsEvent[] newArray(int size) {
            return new ConnectivityMetricsEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestamp);
        dest.writeParcelable(data, 0);
    }

    @Override
    public String toString() {
        return String.format("ConnectivityMetricsEvent(%tT.%tL): %s", timestamp, timestamp, data);
    }
}
