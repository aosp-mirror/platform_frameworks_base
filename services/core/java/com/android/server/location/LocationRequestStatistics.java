/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.util.Log;
import android.util.TimeUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Objects;

/**
 * Holds statistics for location requests (active requests by provider).
 *
 * <p>Must be externally synchronized.
 */
public class LocationRequestStatistics {
    private static final String TAG = "LocationStats";

    // Maps package name and provider to location request statistics.
    public final HashMap<PackageProviderKey, PackageStatistics> statistics
            = new HashMap<PackageProviderKey, PackageStatistics>();

    public final RequestSummaryLimitedHistory history = new RequestSummaryLimitedHistory();

    /**
     * Signals that a package has started requesting locations.
     *
     * @param packageName  Name of package that has requested locations.
     * @param featureId    Feature id associated with the request.
     * @param providerName Name of provider that is requested (e.g. "gps").
     * @param intervalMs   The interval that is requested in ms.
     */
    public void startRequesting(String packageName, @Nullable String featureId, String providerName,
            long intervalMs, boolean isForeground) {
        PackageProviderKey key = new PackageProviderKey(packageName, featureId, providerName);
        PackageStatistics stats = statistics.get(key);
        if (stats == null) {
            stats = new PackageStatistics();
            statistics.put(key, stats);
        }
        stats.startRequesting(intervalMs);
        stats.updateForeground(isForeground);
        history.addRequest(packageName, featureId, providerName, intervalMs);
    }

    /**
     * Signals that a package has stopped requesting locations.
     *
     * @param packageName  Name of package that has stopped requesting locations.
     * @param featureId    Feature id associated with the request.
     * @param providerName Provider that is no longer being requested.
     */
    public void stopRequesting(String packageName, @Nullable String featureId,
            String providerName) {
        PackageProviderKey key = new PackageProviderKey(packageName, featureId, providerName);
        PackageStatistics stats = statistics.get(key);
        if (stats != null) {
            stats.stopRequesting();
        }
        history.removeRequest(packageName, featureId, providerName);
    }

    /**
     * Signals that a package possibly switched background/foreground.
     *
     * @param packageName  Name of package that has stopped requesting locations.
     * @param featureId    Feature id associated with the request.
     * @param providerName Provider that is no longer being requested.
     */
    public void updateForeground(String packageName, @Nullable String featureId,
            String providerName, boolean isForeground) {
        PackageProviderKey key = new PackageProviderKey(packageName, featureId, providerName);
        PackageStatistics stats = statistics.get(key);
        if (stats != null) {
            stats.updateForeground(isForeground);
        }
    }

    /**
     * A key that holds package, feature id, and provider names.
     */
    public static class PackageProviderKey implements Comparable<PackageProviderKey> {
        /**
         * Name of package requesting location.
         */
        public final String mPackageName;
        /**
         * Feature id associated with the request, which can be used to attribute location access to
         * different parts of the application.
         */
        @Nullable
        public final String mFeatureId;
        /**
         * Name of provider being requested (e.g. "gps").
         */
        public final String mProviderName;

        PackageProviderKey(String packageName, @Nullable String featureId, String providerName) {
            this.mPackageName = packageName;
            this.mFeatureId = featureId;
            this.mProviderName = providerName;
        }

        @NonNull
        @Override
        public String toString() {
            return mProviderName + ": " + mPackageName
                    + (mFeatureId == null ? "" : ": " + mFeatureId);
        }

        /**
         * Sort by provider, then package, then feature
         */
        @Override
        public int compareTo(PackageProviderKey other) {
            final int providerCompare = mProviderName.compareTo(other.mProviderName);
            if (providerCompare != 0) {
                return providerCompare;
            }

            final int packageCompare = mPackageName.compareTo(other.mPackageName);
            if (packageCompare != 0) {
                return packageCompare;
            }

            return Objects.compare(mFeatureId, other.mFeatureId, Comparator
                    .nullsFirst(String::compareTo));
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PackageProviderKey)) {
                return false;
            }

            PackageProviderKey otherKey = (PackageProviderKey) other;
            return mPackageName.equals(otherKey.mPackageName)
                    && mProviderName.equals(otherKey.mProviderName)
                    && Objects.equals(mFeatureId, otherKey.mFeatureId);
        }

        @Override
        public int hashCode() {
            int hash = mPackageName.hashCode() + 31 * mProviderName.hashCode();
            if (mFeatureId != null) {
                hash += mFeatureId.hashCode() + 31 * hash;
            }
            return hash;
        }
    }

    /**
     * A data structure to hold past requests
     */
    public static class RequestSummaryLimitedHistory {
        @VisibleForTesting
        static final int MAX_SIZE = 100;

        final ArrayList<RequestSummary> mList = new ArrayList<>(MAX_SIZE);

        /**
         * Append an added location request to the history
         */
        @VisibleForTesting
        void addRequest(String packageName, @Nullable String featureId, String providerName,
                long intervalMs) {
            addRequestSummary(new RequestSummary(packageName, featureId, providerName, intervalMs));
        }

        /**
         * Append a removed location request to the history
         */
        @VisibleForTesting
        void removeRequest(String packageName, @Nullable String featureId, String providerName) {
            addRequestSummary(new RequestSummary(
                    packageName, featureId, providerName, RequestSummary.REQUEST_ENDED_INTERVAL));
        }

        private void addRequestSummary(RequestSummary summary) {
            while (mList.size() >= MAX_SIZE) {
                mList.remove(0);
            }
            mList.add(summary);
        }

        /**
         * Dump history to a printwriter (for dumpsys location)
         */
        public void dump(IndentingPrintWriter ipw) {
            long systemElapsedOffsetMillis = System.currentTimeMillis()
                    - SystemClock.elapsedRealtime();

            ipw.println("Last Several Location Requests:");
            ipw.increaseIndent();

            for (RequestSummary requestSummary : mList) {
                requestSummary.dump(ipw, systemElapsedOffsetMillis);
            }

            ipw.decreaseIndent();
        }
    }

    /**
     * A data structure to hold a single request
     */
    static class RequestSummary {
        /**
         * Name of package requesting location.
         */
        private final String mPackageName;

        /**
         * Feature id associated with the request for identifying subsystem of an application.
         */
        @Nullable
        private final String mFeatureId;
        /**
         * Name of provider being requested (e.g. "gps").
         */
        private final String mProviderName;
        /**
         * Interval Requested, or REQUEST_ENDED_INTERVAL indicating request has ended
         */
        private final long mIntervalMillis;
        /**
         * Elapsed time of request
         */
        private final long mElapsedRealtimeMillis;

        /**
         * Placeholder for requested ending (other values indicate request started/changed)
         */
        static final long REQUEST_ENDED_INTERVAL = -1;

        RequestSummary(String packageName, @Nullable String featureId, String providerName,
                long intervalMillis) {
            this.mPackageName = packageName;
            this.mFeatureId = featureId;
            this.mProviderName = providerName;
            this.mIntervalMillis = intervalMillis;
            this.mElapsedRealtimeMillis = SystemClock.elapsedRealtime();
        }

        void dump(IndentingPrintWriter ipw, long systemElapsedOffsetMillis) {
            StringBuilder s = new StringBuilder();
            long systemTimeMillis = systemElapsedOffsetMillis + mElapsedRealtimeMillis;
            s.append("At ").append(TimeUtils.logTimeOfDay(systemTimeMillis)).append(": ")
                    .append(mIntervalMillis == REQUEST_ENDED_INTERVAL ? "- " : "+ ")
                    .append(String.format("%7s", mProviderName)).append(" request from ")
                    .append(mPackageName);
            if (mFeatureId != null) {
                s.append(" with feature ").append(mFeatureId);
            }
            if (mIntervalMillis != REQUEST_ENDED_INTERVAL) {
                s.append(" at interval ").append(mIntervalMillis / 1000).append(" seconds");
            }
            ipw.println(s);
        }
    }

    /**
     * Usage statistics for a package/provider pair.
     */
    public static class PackageStatistics {
        // Time when this package first requested location.
        private final long mInitialElapsedTimeMs;
        // Number of active location requests this package currently has.
        private int mNumActiveRequests;
        // Time when this package most recently went from not requesting location to requesting.
        private long mLastActivitationElapsedTimeMs;
        // The fastest interval this package has ever requested.
        private long mFastestIntervalMs;
        // The slowest interval this package has ever requested.
        private long mSlowestIntervalMs;
        // The total time this app has requested location (not including currently running
        // requests).
        private long mTotalDurationMs;

        // Time when this package most recently went to foreground, requesting location. 0 means
        // not currently in foreground.
        private long mLastForegroundElapsedTimeMs;
        // The time this app has requested location (not including currently running requests),
        // while in foreground.
        private long mForegroundDurationMs;

        // Time when package last went dormant (stopped requesting location)
        private long mLastStopElapsedTimeMs;

        private PackageStatistics() {
            mInitialElapsedTimeMs = SystemClock.elapsedRealtime();
            mNumActiveRequests = 0;
            mTotalDurationMs = 0;
            mFastestIntervalMs = Long.MAX_VALUE;
            mSlowestIntervalMs = 0;
            mForegroundDurationMs = 0;
            mLastForegroundElapsedTimeMs = 0;
            mLastStopElapsedTimeMs = 0;
        }

        private void startRequesting(long intervalMs) {
            if (mNumActiveRequests == 0) {
                mLastActivitationElapsedTimeMs = SystemClock.elapsedRealtime();
            }

            if (intervalMs < mFastestIntervalMs) {
                mFastestIntervalMs = intervalMs;
            }

            if (intervalMs > mSlowestIntervalMs) {
                mSlowestIntervalMs = intervalMs;
            }

            mNumActiveRequests++;
        }

        private void updateForeground(boolean isForeground) {
            long nowElapsedTimeMs = SystemClock.elapsedRealtime();
            // if previous interval was foreground, accumulate before resetting start
            if (mLastForegroundElapsedTimeMs != 0) {
                mForegroundDurationMs += (nowElapsedTimeMs - mLastForegroundElapsedTimeMs);
            }
            mLastForegroundElapsedTimeMs = isForeground ? nowElapsedTimeMs : 0;
        }

        private void stopRequesting() {
            if (mNumActiveRequests <= 0) {
                // Shouldn't be a possible code path
                Log.e(TAG, "Reference counting corrupted in usage statistics.");
                return;
            }

            mNumActiveRequests--;
            if (mNumActiveRequests == 0) {
                mLastStopElapsedTimeMs = SystemClock.elapsedRealtime();
                long lastDurationMs = mLastStopElapsedTimeMs - mLastActivitationElapsedTimeMs;
                mTotalDurationMs += lastDurationMs;
                updateForeground(false);
            }
        }

        /**
         * Returns the duration that this request has been active.
         */
        public long getDurationMs() {
            long currentDurationMs = mTotalDurationMs;
            if (mNumActiveRequests > 0) {
                currentDurationMs
                        += SystemClock.elapsedRealtime() - mLastActivitationElapsedTimeMs;
            }
            return currentDurationMs;
        }

        /**
         * Returns the duration that this request has been active.
         */
        public long getForegroundDurationMs() {
            long currentDurationMs = mForegroundDurationMs;
            if (mLastForegroundElapsedTimeMs != 0) {
                currentDurationMs
                        += SystemClock.elapsedRealtime() - mLastForegroundElapsedTimeMs;
            }
            return currentDurationMs;
        }

        /**
         * Returns the time since the initial request in ms.
         */
        public long getTimeSinceFirstRequestMs() {
            return SystemClock.elapsedRealtime() - mInitialElapsedTimeMs;
        }

        /**
         * Returns the time since the last request stopped in ms.
         */
        public long getTimeSinceLastRequestStoppedMs() {
            return SystemClock.elapsedRealtime() - mLastStopElapsedTimeMs;
        }

        /**
         * Returns the fastest interval that has been tracked.
         */
        public long getFastestIntervalMs() {
            return mFastestIntervalMs;
        }

        /**
         * Returns the slowest interval that has been tracked.
         */
        public long getSlowestIntervalMs() {
            return mSlowestIntervalMs;
        }

        /**
         * Returns true if a request is active for these tracked statistics.
         */
        public boolean isActive() {
            return mNumActiveRequests > 0;
        }

        @Override
        public String toString() {
            StringBuilder s = new StringBuilder();
            if (mFastestIntervalMs == mSlowestIntervalMs) {
                s.append("Interval ").append(mFastestIntervalMs / 1000).append(" seconds");
            } else {
                s.append("Min interval ").append(mFastestIntervalMs / 1000).append(" seconds");
                s.append(": Max interval ").append(mSlowestIntervalMs / 1000).append(" seconds");
            }
            s.append(": Duration requested ")
                    .append((getDurationMs() / 1000) / 60)
                    .append(" total, ")
                    .append((getForegroundDurationMs() / 1000) / 60)
                    .append(" foreground, out of the last ")
                    .append((getTimeSinceFirstRequestMs() / 1000) / 60)
                    .append(" minutes");
            if (isActive()) {
                s.append(": Currently active");
            } else {
                s.append(": Last active ")
                        .append((getTimeSinceLastRequestStoppedMs() / 1000) / 60)
                        .append(" minutes ago");
            }
            return s.toString();
        }
    }
}
