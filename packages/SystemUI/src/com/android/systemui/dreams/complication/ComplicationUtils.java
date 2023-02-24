/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_AIR_QUALITY;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_CAST_INFO;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_DATE;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_HOME_CONTROLS;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_NONE;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_SMARTSPACE;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_TIME;
import static com.android.systemui.dreams.complication.Complication.COMPLICATION_TYPE_WEATHER;

import com.android.settingslib.dream.DreamBackend;

import java.util.Set;

/**
 * A collection of utility methods for working with {@link Complication}.
 */
public class ComplicationUtils {
    /**
     * Converts a {@link com.android.settingslib.dream.DreamBackend.ComplicationType} to
     * {@link Complication.ComplicationType}.
     */
    @Complication.ComplicationType
    public static int convertComplicationType(@DreamBackend.ComplicationType int type) {
        switch (type) {
            case DreamBackend.COMPLICATION_TYPE_TIME:
                return COMPLICATION_TYPE_TIME;
            case DreamBackend.COMPLICATION_TYPE_DATE:
                return COMPLICATION_TYPE_DATE;
            case DreamBackend.COMPLICATION_TYPE_WEATHER:
                return COMPLICATION_TYPE_WEATHER;
            case DreamBackend.COMPLICATION_TYPE_AIR_QUALITY:
                return COMPLICATION_TYPE_AIR_QUALITY;
            case DreamBackend.COMPLICATION_TYPE_CAST_INFO:
                return COMPLICATION_TYPE_CAST_INFO;
            case DreamBackend.COMPLICATION_TYPE_HOME_CONTROLS:
                return COMPLICATION_TYPE_HOME_CONTROLS;
            case DreamBackend.COMPLICATION_TYPE_SMARTSPACE:
                return COMPLICATION_TYPE_SMARTSPACE;
            default:
                return COMPLICATION_TYPE_NONE;
        }
    }

    /**
     * Converts a set of {@link com.android.settingslib.dream.DreamBackend.ComplicationType} to
     * a combined complications types state.
     */
    @Complication.ComplicationType
    public static int convertComplicationTypes(@DreamBackend.ComplicationType Set<Integer> types) {
        return types.stream().mapToInt(ComplicationUtils::convertComplicationType).reduce(
                COMPLICATION_TYPE_NONE, (a, b) -> a | b);
    }
}
