/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.location.provider;

import android.annotation.SystemApi;

/**
 * Class that exposes FusedBatchOptions to the GmsCore.
 *
 * @deprecated This class may no longer be used from Android P and onwards.
 * @hide
 */
@Deprecated
@SystemApi
public class GmsFusedBatchOptions {

    public void setMaxPowerAllocationInMW(double value) {}

    public double getMaxPowerAllocationInMW() {
        return 0;
    }

    public void setPeriodInNS(long value) {}

    public long getPeriodInNS() {
        return 0;
    }

    public void setSmallestDisplacementMeters(float value) {}

    public float getSmallestDisplacementMeters() {
        return 0;
    }

    public void setSourceToUse(int source) {}

    public void resetSourceToUse(int source) {}

    public boolean isSourceToUseSet(int source) {
        return false;
    }

    public int getSourcesToUse() {
        return 0;
    }

    public void setFlag(int flag) {}

    public void resetFlag(int flag) {}

    public boolean isFlagSet(int flag) {
        return false;
    }

    public int getFlags() {
        return 0;
    }

    /**
     * Definition of enum flag sets needed by this class.
     * Such values need to be kept in sync with the ones in fused_location.h
     */
    public static final class SourceTechnologies {
        public static int GNSS = 1 << 0;
        public static int WIFI = 1 << 1;
        public static int SENSORS = 1 << 2;
        public static int CELL = 1 << 3;
        public static int BLUETOOTH = 1 << 4;
    }

    public static final class BatchFlags {
        public static int WAKEUP_ON_FIFO_FULL = 1 << 0;
        public static int CALLBACK_ON_LOCATION_FIX = 1 << 1;
    }
}
