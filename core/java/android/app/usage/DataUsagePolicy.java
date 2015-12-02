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

package android.app.usage;

import android.net.ConnectivityManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Defines a policy for data usage callbacks, made through {@link DataUsagePolicy.Builder} and used
 * to be notified on data usage via {@link NetworkStatsManager#registerDataUsageCallback}.
 */
public class DataUsagePolicy {

    /**
     * Network type to be monitored, as defined in {@link ConnectivityManager}, e.g.
     * {@link ConnectivityManager#TYPE_MOBILE}, {@link ConnectivityManager#TYPE_WIFI} etc.
     */
    public final int networkType;

    /**
     * Set of subscriber ids to be monitored for the given network type. May be empty if not
     * applicable.
     * <p>Should not be modified once created.
     */
    public final String[] subscriberIds;

    /**
     * Set of UIDs of which to monitor data usage.
     *
     * <p>If not {@code null}, the caller will be notified when any of the uids exceed
     * the given threshold. If empty all uids for which the calling process has access
     * to stats will be monitored.
     * <p>Should not be modified once created.
     */
    public final int[] uids;

    /**
     * Threshold in bytes to be notified on.
     */
    public final long thresholdInBytes;

    /**
     * @hide
     */
    DataUsagePolicy(int networkType, String[] subscriberIds, int[] uids,
                long thresholdInBytes) {
        this.networkType = networkType;
        this.subscriberIds = subscriberIds;
        this.uids = uids;
        this.thresholdInBytes = thresholdInBytes;
    }

    /**
     * Builder used to create {@link DataUsagePolicy} objects.
     */
    public static class Builder {
        private static final int INVALID_NETWORK_TYPE = -1;
        private int mNetworkType = INVALID_NETWORK_TYPE;
        private List<String> mSubscriberList = new ArrayList<>();
        private IntArray mUids = new IntArray();
        private long mThresholdInBytes;

        /**
         * Default constructor for Builder.
         */
        public Builder() {}

        /**
         * Build {@link DataUsagePolicy} given the current policies.
         */
        public DataUsagePolicy build() {
            if (mNetworkType == INVALID_NETWORK_TYPE) {
                throw new IllegalArgumentException(
                        "DataUsagePolicy requires a valid network type to be set");
            }
            return new DataUsagePolicy(mNetworkType,
                    mSubscriberList.toArray(new String[mSubscriberList.size()]),
                    mUids.toArray(), mThresholdInBytes);
        }

        /**
         * Specifies that the given {@code subscriberId} should be monitored.
         *
         * @param subscriberId the subscriber id of the network interface.
         */
        public Builder addSubscriberId(String subscriberId) {
            mSubscriberList.add(subscriberId);
            return this;
        }

        /**
         * Specifies that the given {@code uid} should be monitored.
         */
        public Builder addUid(int uid) {
            mUids.add(uid);
            return this;
        }

        /**
         * Specifies that the callback should monitor the given network. It is mandatory
         * to set one.
         *
         * @param networkType As defined in {@link ConnectivityManager}, e.g.
         *            {@link ConnectivityManager#TYPE_MOBILE},
         *            {@link ConnectivityManager#TYPE_WIFI}, etc.
         */
        public Builder setNetworkType(int networkType) {
            mNetworkType = networkType;
            return this;
        }

        /**
         * Sets the threshold in bytes on which the listener should be called. The framework may
         * impose a minimum threshold to avoid too many notifications to be triggered.
         */
        public Builder setThreshold(long thresholdInBytes) {
            mThresholdInBytes = thresholdInBytes;
            return this;
        }
    }

    @Override
    public String toString() {
        return "DataUsagePolicy [ networkType=" + networkType
                + ", subscriberIds=" + Arrays.toString(subscriberIds)
                + ", uids=" + Arrays.toString(uids)
                + ", thresholdInBytes=" + thresholdInBytes + " ]";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DataUsagePolicy == false) return false;
        DataUsagePolicy that = (DataUsagePolicy) obj;
        return that.networkType == this.networkType
                && Arrays.deepEquals(that.subscriberIds, this.subscriberIds)
                && Arrays.equals(that.uids, this.uids)
                && that.thresholdInBytes == this.thresholdInBytes;
    }

    @Override
    public int hashCode() {
        // Start with a non-zero constant.
        int result = 17;

        // Include a hash for each field.
        result = 31 * result + networkType;
        result = 31 * result + Arrays.deepHashCode(subscriberIds);
        result = 31 * result + Arrays.hashCode(uids);
        result = 31 * result + (int) (thresholdInBytes ^ (thresholdInBytes >>> 32));

        return result;
   }
}
