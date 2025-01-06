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

import android.location.Location;

/**
 * Binder interface for S2 cell IDs callbacks.
 * @hide
 */
oneway interface IS2CellIdsCallback {

    /**
     * Called with the resulting list of S2 cell IDs. The first cell is expected to contain
     * the requested latitude/longitude. Its level represent the population density. Optionally,
     * the list can also contain additional nearby cells.
     */
    void onResult(in long[] s2CellIds);

    /** Called if any error occurs while processing the query. */
    void onError();
}
