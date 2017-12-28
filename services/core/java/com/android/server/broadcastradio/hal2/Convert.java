/**
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.broadcastradio.V2_0.AmFmBandRange;
import android.hardware.broadcastradio.V2_0.AmFmRegionConfig;
import android.hardware.broadcastradio.V2_0.Properties;
import android.hardware.broadcastradio.V2_0.Result;
import android.hardware.broadcastradio.V2_0.VendorKeyValue;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.os.ParcelableException;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

class Convert {
    private static final String TAG = "BcRadio2Srv.convert";

    static void throwOnError(String action, int result) {
        switch (result) {
            case Result.OK:
                return;
            case Result.UNKNOWN_ERROR:
                throw new ParcelableException(new RuntimeException(action + ": UNKNOWN_ERROR"));
            case Result.INTERNAL_ERROR:
                throw new ParcelableException(new RuntimeException(action + ": INTERNAL_ERROR"));
            case Result.INVALID_ARGUMENTS:
                throw new IllegalArgumentException(action + ": INVALID_ARGUMENTS");
            case Result.INVALID_STATE:
                throw new IllegalStateException(action + ": INVALID_STATE");
            case Result.NOT_SUPPORTED:
                throw new UnsupportedOperationException(action + ": NOT_SUPPORTED");
            case Result.TIMEOUT:
                throw new ParcelableException(new RuntimeException(action + ": TIMEOUT"));
            default:
                throw new ParcelableException(new RuntimeException(
                        action + ": unknown error (" + result + ")"));
        }
    }

    private static @NonNull Map<String, String>
    vendorInfoFromHal(@Nullable List<VendorKeyValue> info) {
        if (info == null) return Collections.emptyMap();

        Map<String, String> map = new HashMap<>();
        for (VendorKeyValue kvp : info) {
            if (kvp.key == null || kvp.value == null) {
                Slog.w(TAG, "VendorKeyValue contains null pointers");
                continue;
            }
            map.put(kvp.key, kvp.value);
        }

        return map;
    }

    private static @NonNull int[]
    identifierTypesToProgramTypes(@NonNull int[] idTypes) {
        Set<Integer> pTypes = new HashSet<>();

        for (int idType : idTypes) {
            switch (idType) {
                case ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY:
                case ProgramSelector.IDENTIFIER_TYPE_RDS_PI:
                    // TODO(b/69958423): verify AM/FM with region info
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_AM);
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_FM);
                    break;
                case ProgramSelector.IDENTIFIER_TYPE_HD_STATION_ID_EXT:
                    // TODO(b/69958423): verify AM/FM with region info
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_AM_HD);
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_FM_HD);
                    break;
                case ProgramSelector.IDENTIFIER_TYPE_DAB_SIDECC:
                case ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE:
                case ProgramSelector.IDENTIFIER_TYPE_DAB_SCID:
                case ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY:
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_DAB);
                    break;
                case ProgramSelector.IDENTIFIER_TYPE_DRMO_SERVICE_ID:
                case ProgramSelector.IDENTIFIER_TYPE_DRMO_FREQUENCY:
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_DRMO);
                    break;
                case ProgramSelector.IDENTIFIER_TYPE_SXM_SERVICE_ID:
                case ProgramSelector.IDENTIFIER_TYPE_SXM_CHANNEL:
                    pTypes.add(ProgramSelector.PROGRAM_TYPE_SXM);
                    break;
                default:
                    break;
            }
            if (idType >= ProgramSelector.IDENTIFIER_TYPE_VENDOR_PRIMARY_START
                    && idType <= ProgramSelector.IDENTIFIER_TYPE_VENDOR_PRIMARY_END) {
                pTypes.add(idType);
            }
        }

        return pTypes.stream().mapToInt(Integer::intValue).toArray();
    }

    private static @NonNull RadioManager.BandDescriptor[]
    amfmConfigToBands(@Nullable AmFmRegionConfig config) {
        if (config == null) return new RadioManager.BandDescriptor[0];

        int len = config.ranges.size();
        List<RadioManager.BandDescriptor> bands = new ArrayList<>(len);

        // Just a dummy value.
        int region = RadioManager.REGION_ITU_1;

        for (AmFmBandRange range : config.ranges) {
            FrequencyBand bandType = Utils.getBand(range.lowerBound);
            if (bandType == FrequencyBand.UNKNOWN) {
                Slog.e(TAG, "Unknown frequency band at " + range.lowerBound + "kHz");
                continue;
            }
            if (bandType == FrequencyBand.FM) {
                bands.add(new RadioManager.FmBandDescriptor(region, RadioManager.BAND_FM,
                    range.lowerBound, range.upperBound, range.spacing,

                    // TODO(b/69958777): stereo, rds, ta, af, ea
                    true, true, true, true, true
                ));
            } else {  // AM
                bands.add(new RadioManager.AmBandDescriptor(region, RadioManager.BAND_AM,
                    range.lowerBound, range.upperBound, range.spacing,

                    // TODO(b/69958777): stereo
                    true
                ));
            }
        }

        return bands.toArray(new RadioManager.BandDescriptor[bands.size()]);
    }

    static @NonNull RadioManager.ModuleProperties
    propertiesFromHal(int id, @NonNull String serviceName, @NonNull Properties prop,
            @Nullable AmFmRegionConfig amfmConfig) {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(prop);

        int[] supportedIdentifierTypes = prop.supportedIdentifierTypes.stream().
                mapToInt(Integer::intValue).toArray();
        int[] supportedProgramTypes = identifierTypesToProgramTypes(supportedIdentifierTypes);

        return new RadioManager.ModuleProperties(
                id,
                serviceName,

                // There is no Class concept in HAL 2.0.
                RadioManager.CLASS_AM_FM,

                prop.maker,
                prop.product,
                prop.version,
                prop.serial,

                /* HAL 2.0 only supports single tuner and audio source per
                 * HAL implementation instance. */
                1,      // numTuners
                1,      // numAudioSources
                false,  // isCaptureSupported

                amfmConfigToBands(amfmConfig),
                true,  // isBgScanSupported is deprecated
                supportedProgramTypes,
                supportedIdentifierTypes,
                vendorInfoFromHal(prop.vendorInfo));
    }
}
