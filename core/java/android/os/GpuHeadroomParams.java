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
import android.os.health.SystemHealthManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Headroom request params used by {@link SystemHealthManager#getGpuHeadroom(GpuHeadroomParams)}.
 */
@FlaggedApi(Flags.FLAG_CPU_GPU_HEADROOMS)
public final class GpuHeadroomParams {
    final GpuHeadroomParamsInternal mInternal;

    public GpuHeadroomParams() {
        mInternal = new GpuHeadroomParamsInternal();
    }

    /** @hide */
    @IntDef(flag = false, prefix = {"GPU_HEADROOM_CALCULATION_TYPE_"}, value = {
            GPU_HEADROOM_CALCULATION_TYPE_MIN, // 0
            GPU_HEADROOM_CALCULATION_TYPE_AVERAGE, // 1
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GpuHeadroomCalculationType {
    }

    /**
     * Calculates the headroom based on minimum value over a device-defined window.
     */
    public static final int GPU_HEADROOM_CALCULATION_TYPE_MIN = 0;

    /**
     * Calculates the headroom based on average value over a device-defined window.
     */
    public static final int GPU_HEADROOM_CALCULATION_TYPE_AVERAGE = 1;

    private static final int CALCULATION_WINDOW_MILLIS_MIN = 50;
    private static final int CALCULATION_WINDOW_MILLIS_MAX = 10000;

    /**
     * Sets the headroom calculation type.
     * <p>
     *
     * @throws IllegalArgumentException if the type is invalid.
     */
    public void setCalculationType(@GpuHeadroomCalculationType int calculationType) {
        switch (calculationType) {
            case GPU_HEADROOM_CALCULATION_TYPE_MIN:
            case GPU_HEADROOM_CALCULATION_TYPE_AVERAGE:
                mInternal.calculationType = (byte) calculationType;
                return;
        }
        throw new IllegalArgumentException("Invalid calculation type: " + calculationType);
    }

    /**
     * Gets the headroom calculation type.
     * Default to {@link #GPU_HEADROOM_CALCULATION_TYPE_MIN} if the params is not set.
     */
    public @GpuHeadroomCalculationType int getCalculationType() {
        @GpuHeadroomCalculationType int validatedType = switch ((int) mInternal.calculationType) {
            case GPU_HEADROOM_CALCULATION_TYPE_MIN, GPU_HEADROOM_CALCULATION_TYPE_AVERAGE ->
                    mInternal.calculationType;
            default -> GPU_HEADROOM_CALCULATION_TYPE_MIN;
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
     * @throws IllegalArgumentException if the window is invalid.
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
     * This will return the default value chosen by the device if not set.
     */
    public @IntRange(from = CALCULATION_WINDOW_MILLIS_MIN, to =
            CALCULATION_WINDOW_MILLIS_MAX) int getCalculationWindowMillis() {
        return mInternal.calculationWindowMillis;
    }

    /**
     * @hide
     */
    public GpuHeadroomParamsInternal getInternal() {
        return mInternal;
    }
}
