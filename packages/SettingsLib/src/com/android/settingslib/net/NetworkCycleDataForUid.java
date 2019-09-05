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

import java.util.concurrent.TimeUnit;

/**
 * Usage data in a billing cycle for a specific Uid.
 */
public class NetworkCycleDataForUid extends NetworkCycleData {

    private long mBackgroudUsage;
    private long mForegroudUsage;

    private NetworkCycleDataForUid() {
    }

    public long getBackgroudUsage() {
        return mBackgroudUsage;
    }

    public long getForegroudUsage() {
        return mForegroudUsage;
    }

    public static class Builder extends NetworkCycleData.Builder {

        private NetworkCycleDataForUid mObject = new NetworkCycleDataForUid();

        public Builder setBackgroundUsage(long backgroundUsage) {
            getObject().mBackgroudUsage = backgroundUsage;
            return this;
        }

        public Builder setForegroundUsage(long foregroundUsage) {
            getObject().mForegroudUsage = foregroundUsage;
            return this;
        }

        @Override
        public NetworkCycleDataForUid getObject() {
            return mObject;
        }

        @Override
        public NetworkCycleDataForUid build() {
            return getObject();
        }
    }

}
