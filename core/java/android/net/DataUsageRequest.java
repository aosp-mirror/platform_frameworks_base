/**
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.net;

import android.net.NetworkTemplate;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Defines a request to register a callbacks. Used to be notified on data usage via
 * {@link android.app.usage.NetworkStatsManager#registerDataUsageCallback}.
 * If no {@code uid}s are set, callbacks are restricted to device-owners,
 * carrier-privileged apps, or system apps.
 *
 * @hide
 */
public final class DataUsageRequest implements Parcelable {

    public static final String PARCELABLE_KEY = "DataUsageRequest";
    public static final int REQUEST_ID_UNSET = 0;

    /**
     * Identifies the request.  {@link DataUsageRequest}s should only be constructed by
     * the Framework and it is used internally to identify the request.
     */
    public final int requestId;

    /**
     * {@link NetworkTemplate} describing the network to monitor.
     */
    public final NetworkTemplate template;

    /**
     * Threshold in bytes to be notified on.
     */
    public final long thresholdInBytes;

    public DataUsageRequest(int requestId, NetworkTemplate template, long thresholdInBytes) {
        this.requestId = requestId;
        this.template = template;
        this.thresholdInBytes = thresholdInBytes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(requestId);
        dest.writeParcelable(template, flags);
        dest.writeLong(thresholdInBytes);
    }

    public static final Creator<DataUsageRequest> CREATOR =
            new Creator<DataUsageRequest>() {
                @Override
                public DataUsageRequest createFromParcel(Parcel in) {
                    int requestId = in.readInt();
                    NetworkTemplate template = in.readParcelable(null);
                    long thresholdInBytes = in.readLong();
                    DataUsageRequest result = new DataUsageRequest(requestId, template,
                            thresholdInBytes);
                    return result;
                }

                @Override
                public DataUsageRequest[] newArray(int size) {
                    return new DataUsageRequest[size];
                }
            };

    @Override
    public String toString() {
        return "DataUsageRequest [ requestId=" + requestId
                + ", networkTemplate=" + template
                + ", thresholdInBytes=" + thresholdInBytes + " ]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DataUsageRequest == false) return false;
        DataUsageRequest that = (DataUsageRequest) obj;
        return that.requestId == this.requestId
                && Objects.equals(that.template, this.template)
                && that.thresholdInBytes == this.thresholdInBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestId, template, thresholdInBytes);
   }

}
