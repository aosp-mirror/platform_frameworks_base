/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.location.provider;

import android.location.FusedBatchOptions;

/**
 * Class that exposes FusedBatchOptions to the GmsCore .
 */
public class GmsFusedBatchOptions {
    private FusedBatchOptions mOptions = new FusedBatchOptions();

    /*
     * Methods that provide a facade for properties in FusedBatchOptions.
     */
    public void setMaxPowerAllocationInMW(double value) {
        mOptions.setMaxPowerAllocationInMW(value);
    }

    public double getMaxPowerAllocationInMW() {
        return mOptions.getMaxPowerAllocationInMW();
    }

    public void setPeriodInNS(long value) {
        mOptions.setPeriodInNS(value);
    }

    public long getPeriodInNS() {
        return mOptions.getPeriodInNS();
    }

    public void setSourceToUse(int source) {
        mOptions.setSourceToUse(source);
    }

    public void resetSourceToUse(int source) {
        mOptions.resetSourceToUse(source);
    }

    public boolean isSourceToUseSet(int source) {
        return mOptions.isSourceToUseSet(source);
    }

    public int getSourcesToUse() {
        return mOptions.getSourcesToUse();
    }

    public void setFlag(int flag) {
        mOptions.setFlag(flag);
    }

    public void resetFlag(int flag) {
        mOptions.resetFlag(flag);
    }

    public boolean isFlagSet(int flag) {
        return mOptions.isFlagSet(flag);
    }

    public int getFlags() {
        return mOptions.getFlags();
    }

    /**
     * Definition of enum flag sets needed by this class.
     * Such values need to be kept in sync with the ones in fused_location.h
     */

    public static final class SourceTechnologies {
        public static int GNSS = 1<<0;
        public static int WIFI = 1<<1;
        public static int SENSORS = 1<<2;
        public static int CELL = 1<<3;
        public static int BLUETOOTH = 1<<4;
    }

    public static final class BatchFlags {
        public static int WAKEUP_ON_FIFO_FULL = 1<<0;
        public static int CALLBACK_ON_LOCATION_FIX = 1<<1;
    }

    /*
     * Method definitions for internal use.
     */

    /*
     * @hide
     */
    public FusedBatchOptions getParcelableOptions() {
        return mOptions;
    }
}
