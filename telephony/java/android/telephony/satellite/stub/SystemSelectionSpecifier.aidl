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

package android.telephony.satellite.stub;

import android.telephony.satellite.stub.SatelliteInfo;

/**
 * {@hide}
 */
parcelable SystemSelectionSpecifier {
    /** Network plmn associated with channel information. */
    String mMccMnc;

    /**
     * The frequency bands to scan.
     * Bands will be filled only if the whole band is needed.
     * Maximum length of the vector is 8.
     * The values are populated from the mBands array within the SatelliteInfo[] array, which is
     * included in the SystemSelectionSpecifier, for backward compatibility.
     */
    int[] mBands;

    /**
     * The radio channels to scan as defined in 3GPP TS 25.101 and 36.101.
     * The values are populated from the earfcns defined in the EarfcnRange[] array inside
     * SatelliteInfo[], which is included in the SystemSelectionSpecifier, for backward
     * compatibility.
     * Maximum length of the vector is 32.
     */
    int[] mEarfcs;

    /* The list of satellites configured for the current location */
    SatelliteInfo[] satelliteInfos;

    /* The list of tag IDs associated with the current location */
    int[] tagIds;
}
