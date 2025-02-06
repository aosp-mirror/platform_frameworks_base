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
import java.util.Arrays;

/**
 * Headroom request params used by {@link SystemHealthManager#getCpuHeadroom(CpuHeadroomParams)}.
 *
 * <p>This class is immutable and one should use the {@link Builder} to build a new instance.
 */
@FlaggedApi(Flags.FLAG_CPU_GPU_HEADROOMS)
public final class CpuHeadroomParams {
    /** @hide */
    @IntDef(flag = false, prefix = {"CPU_HEADROOM_CALCULATION_TYPE_"}, value = {
            CPU_HEADROOM_CALCULATION_TYPE_MIN, // 0
            CPU_HEADROOM_CALCULATION_TYPE_AVERAGE, // 1
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CpuHeadroomCalculationType {
    }

    /**
     * The headroom calculation type bases on minimum value over a specified window.
     */
    public static final int CPU_HEADROOM_CALCULATION_TYPE_MIN = 0;

    /**
     * The headroom calculation type bases on average value over a specified window.
     */
    public static final int CPU_HEADROOM_CALCULATION_TYPE_AVERAGE = 1;

    /** @hide */
    public final CpuHeadroomParamsInternal mInternal;

    private CpuHeadroomParams() {
        mInternal = new CpuHeadroomParamsInternal();
    }

    public static final class Builder {
        private int mCalculationType = -1;
        private int mCalculationWindowMillis = -1;
        private int[] mTids = null;

        public Builder() {
        }

        /**
         * Returns a new builder copy with the same values as the params.
         */
        public Builder(@NonNull CpuHeadroomParams params) {
            if (params.mInternal.calculationType >= 0) {
                mCalculationType = params.mInternal.calculationType;
            }
            if (params.mInternal.calculationWindowMillis >= 0) {
                mCalculationWindowMillis = params.mInternal.calculationWindowMillis;
            }
            if (params.mInternal.tids != null) {
                mTids = Arrays.copyOf(params.mInternal.tids, params.mInternal.tids.length);
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
                @CpuHeadroomCalculationType int calculationType) {
            switch (calculationType) {
                case CPU_HEADROOM_CALCULATION_TYPE_MIN:
                case CPU_HEADROOM_CALCULATION_TYPE_AVERAGE: {
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
         *                     {@link SystemHealthManager#getCpuHeadroomCalculationWindowRange()}.
         *                     The smaller the window size, the larger fluctuation in the headroom
         *                     value should be expected. The default value can be retrieved from
         *                     the {@link CpuHeadroomParams#getCalculationWindowMillis}. The device
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
         * Sets the thread TIDs to track.
         * <p>
         * The TIDs should belong to the same of the process that will make the headroom call. And
         * they should not have different core affinity.
         * <p>
         * If not set or set to empty, the headroom will be based on the PID of the process making
         * the call.
         *
         * @param tids non-null list of TIDs, where maximum size can be read from
         *             {@link SystemHealthManager#getMaxCpuHeadroomTidsSize()}.
         * @throws IllegalArgumentException if the TID is not positive.
         */
        @NonNull
        public Builder setTids(@NonNull int... tids) {
            for (int tid : tids) {
                if (tid <= 0) {
                    throw new IllegalArgumentException("Invalid TID: " + tid);
                }
            }
            mTids = tids;
            return this;
        }

        /**
         * Builds the {@link CpuHeadroomParams} object.
         */
        @NonNull
        public CpuHeadroomParams build() {
            CpuHeadroomParams params = new CpuHeadroomParams();
            if (mCalculationType >= 0) {
                params.mInternal.calculationType = (byte) mCalculationType;
            }
            if (mCalculationWindowMillis >= 0) {
                params.mInternal.calculationWindowMillis = mCalculationWindowMillis;
            }
            if (mTids != null) {
                params.mInternal.tids = mTids;
            }
            return params;
        }
    }

    /**
     * Returns a new builder with the same values as this object.
     */
    @NonNull
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * Gets the headroom calculation type.
     * <p>
     * This will return the default value chosen by the device if not set.
     */
    public @CpuHeadroomCalculationType int getCalculationType() {
        @CpuHeadroomCalculationType int validatedType = switch ((int) mInternal.calculationType) {
            case CPU_HEADROOM_CALCULATION_TYPE_MIN,
                 CPU_HEADROOM_CALCULATION_TYPE_AVERAGE -> mInternal.calculationType;
            default -> CPU_HEADROOM_CALCULATION_TYPE_MIN;
        };
        return validatedType;
    }

    /**
     * Gets the headroom calculation window size in milliseconds.
     * <p>
     * This will return the default value chosen by the device if not set.
     */
    public long getCalculationWindowMillis() {
        return mInternal.calculationWindowMillis;
    }

    /**
     * Gets the TIDs to track.
     * <p>
     * This will return a copy of the TIDs in the params, or null if the params is not set.
     */
    @NonNull
    public int[] getTids() {
        return mInternal.tids == null ? null : Arrays.copyOf(mInternal.tids, mInternal.tids.length);
    }

    @Override
    public String toString() {
        return "CpuHeadroomParams{"
                + "calculationType=" + mInternal.calculationType
                + ", calculationWindowMillis=" + mInternal.calculationWindowMillis
                + ", tids=" + Arrays.toString(mInternal.tids)
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CpuHeadroomParams that = (CpuHeadroomParams) o;
        return mInternal.equals(that.mInternal);
    }

    @Override
    public int hashCode() {
        return mInternal.hashCode();
    }
}
