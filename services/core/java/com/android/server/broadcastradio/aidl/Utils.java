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

package com.android.server.broadcastradio.aidl;

final class Utils {

    private Utils() {
        throw new UnsupportedOperationException("Utils class is noninstantiable");
    }

    public enum FrequencyBand {
        UNKNOWN,
        FM,
        AM_LW,
        AM_MW,
        AM_SW,
    }

    static FrequencyBand getBand(int freq) {
        // keep in sync with hardware/interfaces/broadcastradio/common/utilsaidl/Utils.cpp
        if (freq < 30) return FrequencyBand.UNKNOWN;
        if (freq < 500) return FrequencyBand.AM_LW;
        if (freq < 1705) return FrequencyBand.AM_MW;
        if (freq < 30000) return FrequencyBand.AM_SW;
        if (freq < 60000) return FrequencyBand.UNKNOWN;
        if (freq < 110000) return FrequencyBand.FM;
        return FrequencyBand.UNKNOWN;
    }

}
