/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cpu;

import android.annotation.IntDef;
import android.util.IntArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** CPU availability monitoring config. */
public final class CpuAvailabilityMonitoringConfig {
    /** Constant to monitor all cpusets. */
    public static final int CPUSET_ALL = 1;

    /** Constant to monitor background cpusets. */
    public static final int CPUSET_BACKGROUND = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CPUSET_"}, value = {
            CPUSET_ALL,
            CPUSET_BACKGROUND
    })
    public @interface Cpuset {
    }

    /**
     * The CPUSET to monitor.
     *
     * <p>The value must be one of the {@code CPUSET_*} constants.
     */
    @Cpuset
    public final int cpuset;

    /**
     * CPU availability percent thresholds.
     *
     * <p>CPU availability change notifications are sent when the latest or last N seconds average
     * CPU availability percent crosses any of these thresholds since the last notification.
     */
    private final IntArray mThresholds;

    public IntArray getThresholds() {
        return mThresholds;
    }

    /**
     * Builder for the construction of {@link CpuAvailabilityMonitoringConfig} objects.
     *
     * <p>The builder must contain at least one threshold before calling {@link build}.
     */
    public static final class Builder {
        private final int mCpuset;
        private final IntArray mThresholds = new IntArray();

        public Builder(int cpuset, int... thresholds) {
            mCpuset = cpuset;
            for (int threshold : thresholds) {
                addThreshold(threshold);
            }
        }

        /** Adds the given threshold to the builder object. */
        public Builder addThreshold(int threshold) {
            if (mThresholds.indexOf(threshold) == -1) {
                mThresholds.add(threshold);
            }
            return this;
        }

        /** Returns the {@link CpuAvailabilityMonitoringConfig} object. */
        public CpuAvailabilityMonitoringConfig build() {
            return new CpuAvailabilityMonitoringConfig(this);
        }
    }

    @Override
    public String toString() {
        return "CpuAvailabilityMonitoringConfig{cpuset=" + cpuset + ", mThresholds=" + mThresholds
                + ')';
    }

    private CpuAvailabilityMonitoringConfig(Builder builder) {
        if (builder.mCpuset != CPUSET_ALL && builder.mCpuset != CPUSET_BACKGROUND) {
            throw new IllegalStateException("Cpuset must be either CPUSET_ALL (" + CPUSET_ALL
                    + ") or CPUSET_BACKGROUND (" + CPUSET_BACKGROUND + "). Builder contains "
                    + builder.mCpuset);
        }
        if (builder.mThresholds.size() == 0) {
            throw new IllegalStateException("Must provide at least one threshold");
        }
        this.cpuset = builder.mCpuset;
        this.mThresholds = builder.mThresholds.clone();
    }
}
