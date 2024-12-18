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

package android.location.provider;

import android.os.Bundle;

import android.location.Location;
import android.location.provider.IS2CellIdsCallback;
import android.location.provider.IS2LevelCallback;

/**
 * Binder interface for services that implement a population density provider. Do not implement this
 * directly, extend {@link PopulationDensityProviderBase} instead.
 * @hide
 */
oneway interface IPopulationDensityProvider {
    /**
     * Gets the default S2 level to be used to coarsen any location, in case a more precise answer
     * from the method below can't be obtained.
     */
    void getDefaultCoarseningLevel(in IS2LevelCallback callback);

    /**
     * Requests a list of IDs of the S2 cells to be used to coarsen a location. The answer should
     * contain at least one S2 cell, which should contain the requested location. Its level
     * represents the population density. Optionally, if numAdditionalCells is greater than 0,
     * additional nearby cells can be also returned, to assist in coarsening nearby locations.
     */
    void getCoarsenedS2Cells(double latitudeDegrees, double longitudeDegrees,
        int numAdditionalCells, in IS2CellIdsCallback callback);
}
