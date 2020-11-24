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

package com.android.internal.power;


import android.annotation.IntDef;

import com.android.internal.os.RailStats;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface to provide subsystem energy data.
 * TODO: replace this and {@link RailStats} once b/173077356 is done
 */
public interface MeasuredEnergyArray {
    int SUBSYSTEM_UNKNOWN = -1;
    int SUBSYSTEM_DISPLAY = 0;
    int NUMBER_SUBSYSTEMS = 1;
    String[] SUBSYSTEM_NAMES = {"display"};


    @IntDef(prefix = { "SUBSYSTEM_" }, value = {
            SUBSYSTEM_UNKNOWN,
            SUBSYSTEM_DISPLAY,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface MeasuredEnergySubsystem {}

    /**
     * Get the subsystem at an index in array.
     *
     * @param index into the array.
     * @return subsystem.
     */
    @MeasuredEnergySubsystem
    int getSubsystem(int index);

    /**
     * Get the energy (in microjoules) consumed since boot of the subsystem at an index.
     *
     * @param index into the array.
     * @return energy (in microjoules) consumed since boot.
     */
    long getEnergy(int index);

    /**
     * Return number of subsystems in the array.
     */
    int size();
}
