/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.os.health.SystemHealthManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Headroom request params used by {@link SystemHealthManager#getGpuHeadroom(GpuHeadroomParams)}.
 *
 * <p>This class is immutable and one should use the {@link Builder} to build a new instance.
 */
@FlaggedApi(Flags.FLAG_CPU_GPU_HEADROOMS)
public final class GpuHeadroomParams {
    /** @hide */
    @IntDef(flag = false, prefix = {"GPU_HEADROOM_CALCULATION_TYPE_"}, value = {
            GPU_HEADROOM_CALCULATION_TYPE_MIN, // 0
            GPU_HEADROOM_CALCULATION_TYPE_AVERAGE, // 1
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GpuHeadroomCalculationType {
    }

    /**
     * The headroom calculation type bases on minimum value over a specified window.
     */
    public static final int GPU_HEADROOM_CALCULATION_TYPE_MIN = 0;

    /**
     * The headroom calculation type bases on average value over a specified window.
     */
    public static final int GPU_HEADROOM_CALCULATION_TYPE_AVERAGE = 1;

    /**
     * The minimum size of the window to compute the headroom over.
     */
    public static final int GPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MIN = 50;

    /**
     * The maximum size of the window to compute the headroom over.
     */
    public static final int GPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MAX = 10000;

    /**
     * @hide
     */
    public final GpuHeadroomParamsInternal mInternal;

    /**
     * @hide
     */
    private GpuHeadroomParams() {
        mInternal = new GpuHeadroomParamsInternal();
    }

    public static final class Builder {
        private int mCalculationType = -1;
        private int mCalculationWindowMillis = -1;

        public Builder() {
        }

        /**
         * Returns a new builder with the same values as this object.
         */
        public Builder(@NonNull GpuHeadroomParams params) {
            if (params.mInternal.calculationType >= 0) {
                mCalculationType = params.mInternal.calculationType;
            }
            if (params.mInternal.calculationWindowMillis >= 0) {
                mCalculationWindowMillis = params.mInternal.calculationWindowMillis;
            }
        }

        /**
         * Sets the headroom calculation type.
         * <p>
         *
         * @throws IllegalArgumentException if the type is invalid.
         */
        @NonNull
        public Builder setCalculationType(
                @GpuHeadroomCalculationType int calculationType) {
            switch (calculationType) {
                case GPU_HEADROOM_CALCULATION_TYPE_MIN:
                case GPU_HEADROOM_CALCULATION_TYPE_AVERAGE: {
                    mCalculationType = calculationType;
                    return this;
                }
            }
            throw new IllegalArgumentException("Invalid calculation type: " + calculationType);
        }

        /**
         * Sets the headroom calculation window size in milliseconds.
         * <p>
         *
         * @param windowMillis the window size in milliseconds ranges from
         *                     {@link SystemHealthManager#getGpuHeadroomCalculationWindowRange()}.
         *                     The smaller the window size, the larger fluctuation in the headroom
         *                     value should be expected. The default value can be retrieved from
         *                     the {@link GpuHeadroomParams#getCalculationWindowMillis}. The device
         *                     will try to use the closest feasible window size to this param.
         * @throws IllegalArgumentException if the window is invalid.
         */
        @NonNull
        public Builder setCalculationWindowMillis(@IntRange(from = 1) int windowMillis) {
            if (windowMillis <= 0) {
                throw new IllegalArgumentException("Invalid calculation window: " + windowMillis);
            }
            mCalculationWindowMillis = windowMillis;
            return this;
        }

        /**
         * Builds the {@link GpuHeadroomParams} object.
         */
        @NonNull
        public GpuHeadroomParams build() {
            GpuHeadroomParams params = new GpuHeadroomParams();
            if (mCalculationType >= 0) {
                params.mInternal.calculationType = (byte) mCalculationType;
            }
            if (mCalculationWindowMillis >= 0) {
                params.mInternal.calculationWindowMillis = mCalculationWindowMillis;
            }
            return params;
        }
    }

    /**
     * Gets the headroom calculation type.
     * <p>
     * This will return the default value chosen by the device if not set.
     */
    public @GpuHeadroomCalculationType int getCalculationType() {
        @GpuHeadroomCalculationType int validatedType = switch ((int) mInternal.calculationType) {
            case GPU_HEADROOM_CALCULATION_TYPE_MIN,
                 GPU_HEADROOM_CALCULATION_TYPE_AVERAGE -> mInternal.calculationType;
            default -> GPU_HEADROOM_CALCULATION_TYPE_MIN;
        };
        return validatedType;
    }

    /**
     * Gets the headroom calculation window size in milliseconds.
     * <p>
     * This will return the default value chosen by the device if not set.
     */
    public int getCalculationWindowMillis() {
        return mInternal.calculationWindowMillis;
    }

    @Override
    public String toString() {
        return "GpuHeadroomParams{"
                + "calculationType=" + mInternal.calculationType
                + ", calculationWindowMillis=" + mInternal.calculationWindowMillis
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GpuHeadroomParams that = (GpuHeadroomParams) o;
        return mInternal.equals(that.mInternal);
    }

    @Override
    public int hashCode() {
        return mInternal.hashCode();
    }
}
