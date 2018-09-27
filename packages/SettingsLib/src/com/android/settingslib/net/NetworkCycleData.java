/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.net;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Data structure representing usage data in a billing cycle.
 */
public class NetworkCycleData {
    public static final long BUCKET_DURATION_MS = TimeUnit.DAYS.toMillis(1);
    public long startTime;
    public long endTime;
    public long totalUsage;
    public List<NetworkCycleData> usageBuckets;

    private NetworkCycleData(Builder builder) {
        startTime = builder.mStart;
        endTime = builder.mEnd;
        totalUsage = builder.mTotalUsage;
        usageBuckets = builder.mUsageBuckets;
    }

    public static class Builder {
        private long mStart;
        private long mEnd;
        private long mTotalUsage;
        private List<NetworkCycleData> mUsageBuckets;

        public Builder setStartTime(long start) {
            mStart = start;
            return this;
        }

        public Builder setEndTime(long end) {
            mEnd = end;
            return this;
        }

        public Builder setTotalUsage(long total) {
            mTotalUsage = total;
            return this;
        }

        public Builder setUsageBuckets(List<NetworkCycleData> buckets) {
            mUsageBuckets = buckets;
            return this;
        }

        public NetworkCycleData build() {
            return new NetworkCycleData(this);
        }
    }
}
