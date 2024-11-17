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
import android.os.health.SystemHealthManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
     * @hide
     */
    public CpuHeadroomParamsInternal getInternal() {
        return mInternal;
    }
}
