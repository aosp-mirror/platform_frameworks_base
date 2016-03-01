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

import java.util.Arrays;
import java.util.Objects;

/**
 * Defines a request to register a callbacks. Used to be notified on data usage via
 * {@link android.app.usage.NetworkStatsManager#registerDataUsageCallback}.
 * If no {@code uid}s are set, callbacks are restricted to device-owners,
 * carrier-privileged apps, or system apps.
 */
public final class DataUsageRequest implements Parcelable {

    /**
     * @hide
     */
    public static final String PARCELABLE_KEY = "DataUsageRequest";

    /**
     * @hide
     */
    public static final int REQUEST_ID_UNSET = 0;

    /**
     * Identifies the request.  {@link DataUsageRequest}s should only be constructed by
     * the Framework and it is used internally to identify the request.
     * @hide
     */
    public final int requestId;

    /**
     * Set of {@link NetworkTemplate}s describing the networks to monitor.
     * @hide
     */
    public final NetworkTemplate[] templates;

    /**
     * Set of UIDs of which to monitor data usage.
     *
     * <p>If not {@code null}, the caller will be notified when any of the uids exceed
     * the given threshold. If {@code null} all uids for which the calling process has access
     * to stats will be monitored.
     * @hide
     */
    public final int[] uids;

    /**
     * Threshold in bytes to be notified on.
     * @hide
     */
    public final long thresholdInBytes;

    /**
     * @hide
     */
    public DataUsageRequest(int requestId, NetworkTemplate[] templates, int[] uids,
                long thresholdInBytes) {
        this.requestId = requestId;
        this.templates = templates;
        this.uids = uids;
        this.thresholdInBytes = thresholdInBytes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(requestId);
        dest.writeTypedArray(templates, flags);
        dest.writeIntArray(uids);
        dest.writeLong(thresholdInBytes);
    }

    public static final Creator<DataUsageRequest> CREATOR =
            new Creator<DataUsageRequest>() {
                @Override
                public DataUsageRequest createFromParcel(Parcel in) {
                    int requestId = in.readInt();
                    NetworkTemplate[] templates = in.createTypedArray(NetworkTemplate.CREATOR);
                    int[] uids = in.createIntArray();
                    long thresholdInBytes = in.readLong();
                    DataUsageRequest result = new DataUsageRequest(requestId,
                            templates, uids, thresholdInBytes);
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
                + ", networkTemplates=" + Arrays.toString(templates)
                + ", uids=" + Arrays.toString(uids)
                + ", thresholdInBytes=" + thresholdInBytes + " ]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DataUsageRequest == false) return false;
        DataUsageRequest that = (DataUsageRequest) obj;
        return that.requestId == this.requestId
                && Arrays.deepEquals(that.templates, this.templates)
                && Arrays.equals(that.uids, this.uids)
                && that.thresholdInBytes == this.thresholdInBytes;
    }

    @Override
    public int hashCode() {
        // Start with a non-zero constant.
        int result = 17;

        // Include a hash for each field.
        result = 31 * result + requestId;
        result = 31 * result + Arrays.deepHashCode(templates);
        result = 31 * result + Arrays.hashCode(uids);
        result = 31 * result + (int) (thresholdInBytes ^ (thresholdInBytes >>> 32));

        return result;
   }

}
