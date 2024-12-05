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
 */
@FlaggedApi(Flags.FLAG_CPU_GPU_HEADROOMS)
public final class CpuHeadroomParams {
    final CpuHeadroomParamsInternal mInternal;

    public CpuHeadroomParams() {
        mInternal = new CpuHeadroomParamsInternal();
    }

    /** @hide */
    @IntDef(flag = false, prefix = {"CPU_HEADROOM_CALCULATION_TYPE_"}, value = {
            CPU_HEADROOM_CALCULATION_TYPE_MIN, // 0
            CPU_HEADROOM_CALCULATION_TYPE_AVERAGE, // 1
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CpuHeadroomCalculationType {
    }

    /**
     * Calculates the headroom based on minimum value over a device-defined window.
     */
    public static final int CPU_HEADROOM_CALCULATION_TYPE_MIN = 0;

    /**
     * Calculates the headroom based on average value over a device-defined window.
     */
    public static final int CPU_HEADROOM_CALCULATION_TYPE_AVERAGE = 1;

    private static final int CALCULATION_WINDOW_MILLIS_MIN = 50;
    private static final int CALCULATION_WINDOW_MILLIS_MAX = 10000;
    private static final int MAX_TID_COUNT = 5;

    /**
     * Sets the headroom calculation type.
     * <p>
     *
     * @throws IllegalArgumentException if the type is invalid.
     */
    public void setCalculationType(@CpuHeadroomCalculationType int calculationType) {
        switch (calculationType) {
            case CPU_HEADROOM_CALCULATION_TYPE_MIN:
            case CPU_HEADROOM_CALCULATION_TYPE_AVERAGE:
                mInternal.calculationType = (byte) calculationType;
                return;
        }
        throw new IllegalArgumentException("Invalid calculation type: " + calculationType);
    }

    /**
     * Gets the headroom calculation type.
     * Default to {@link #CPU_HEADROOM_CALCULATION_TYPE_MIN} if not set.
     */
    public @CpuHeadroomCalculationType int getCalculationType() {
        @CpuHeadroomCalculationType int validatedType = switch ((int) mInternal.calculationType) {
            case CPU_HEADROOM_CALCULATION_TYPE_MIN, CPU_HEADROOM_CALCULATION_TYPE_AVERAGE ->
                    mInternal.calculationType;
            default -> CPU_HEADROOM_CALCULATION_TYPE_MIN;
        };
        return validatedType;
    }

    /**
     * Sets the headroom calculation window size in milliseconds.
     * <p>
     *
     * @param windowMillis the window size in milliseconds ranges from [50, 10000]. The smaller the
     *                     window size, the larger fluctuation in the headroom value should be
     *                     expected. The default value can be retrieved from the
     *                     {@link #getCalculationWindowMillis}. The device will try to use the
     *                     closest feasible window size to this param.
     * @throws IllegalArgumentException if the window size is not in allowed range.
     */
    public void setCalculationWindowMillis(
            @IntRange(from = CALCULATION_WINDOW_MILLIS_MIN, to =
                    CALCULATION_WINDOW_MILLIS_MAX) int windowMillis) {
        if (windowMillis < CALCULATION_WINDOW_MILLIS_MIN
                || windowMillis > CALCULATION_WINDOW_MILLIS_MAX) {
            throw new IllegalArgumentException("Invalid calculation window: " + windowMillis);
        }
        mInternal.calculationWindowMillis = windowMillis;
    }

    /**
     * Gets the headroom calculation window size in milliseconds.
     * <p>
     * This will return the default value chosen by the device if the params is not set.
     */
    public @IntRange(from = CALCULATION_WINDOW_MILLIS_MIN, to =
            CALCULATION_WINDOW_MILLIS_MAX) long getCalculationWindowMillis() {
        return mInternal.calculationWindowMillis;
    }

    /**
     * Sets the thread TIDs to track.
     * <p>
     * The TIDs should belong to the same of the process that will the headroom call. And they
     * should not have different core affinity.
     * <p>
     * If not set, the headroom will be based on the PID of the process making the call.
     *
     * @param tids non-empty list of TIDs, maximum 5.
     * @throws IllegalArgumentException if the list size is not in allowed range or TID is not
     *                                  positive.
     */
    public void setTids(@NonNull int... tids) {
        if (tids.length == 0 || tids.length > MAX_TID_COUNT) {
            throw new IllegalArgumentException("Invalid number of TIDs: " + tids.length);
        }
        for (int tid : tids) {
            if (tid <= 0) {
                throw new IllegalArgumentException("Invalid TID: " + tid);
            }
        }
        mInternal.tids = Arrays.copyOf(tids, tids.length);
    }

    /**
     * @hide
     */
    public CpuHeadroomParamsInternal getInternal() {
        return mInternal;
    }
}
