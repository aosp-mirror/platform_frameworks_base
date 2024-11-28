/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite.stub;

import android.telephony.satellite.stub.UUID;
import android.telephony.satellite.stub.SatellitePosition;
import android.telephony.satellite.stub.EarfcnRange;

/**
 * @hide
 */
parcelable SatelliteInfo {
    /**
     * Unique identification number for the satellite.
     * This ID is used to distinguish between different satellites in the network.
     */
    UUID id;

    /**
     * Position information of a geostationary satellite.
     * This includes the longitude and altitude of the satellite.
     * If the SatellitePosition is invalid,
     * longitudeDegree and altitudeKm will be represented as DOUBLE.NaN.
     */
    SatellitePosition position;

    /**
     * The frequency bands to scan.
     * Bands will be filled only if the whole band is needed.
     * Maximum length of the vector is 8.
     */
    int[] bands;

    /**
     * The supported frequency ranges. Earfcn ranges and earfcns won't overlap.
     */
    EarfcnRange[] earfcnRanges;
}
