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

/**
 * Base data structure representing usage data in a billing cycle.
 */
public class NetworkCycleData {

    private long mStartTime;
    private long mEndTime;
    private long mTotalUsage;

    protected NetworkCycleData() {
    }

    public long getStartTime() {
        return mStartTime;
    }

    public long getEndTime() {
        return mEndTime;
    }

    public long getTotalUsage() {
        return mTotalUsage;
    }

    public static class Builder {

        private NetworkCycleData mObject = new NetworkCycleData();

        public Builder setStartTime(long start) {
            getObject().mStartTime = start;
            return this;
        }

        public Builder setEndTime(long end) {
            getObject().mEndTime = end;
            return this;
        }

        public Builder setTotalUsage(long total) {
            getObject().mTotalUsage = total;
            return this;
        }

        protected NetworkCycleData getObject() {
            return mObject;
        }

        public NetworkCycleData build() {
            return getObject();
        }
    }
}
