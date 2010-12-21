/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony;

import android.os.SystemProperties;

public class SimRegionCache {
    public static final int MCC_UNSET  = Integer.MIN_VALUE;
    public static final int MCC_KOREAN = 450;

    private static int regionFromMcc = MCC_UNSET;

    /**
     * Returns the region as read from the MCC of the SIM card.
     * If the property {@link TelephonyProperties#
     * PROPERTY_ICC_OPERATOR_NUMERIC}
     * returns null or an empty String, the value is {@link #MCC_UNSET}
     *
     * @return the cached region, if set.
     */
    public static int getRegion() {
        if (regionFromMcc == MCC_UNSET) {
            String plmn = SystemProperties.get(
                    TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC,
                    null);

            if (plmn != null && plmn.length() >= 3) {
                try {
                    regionFromMcc = Integer.parseInt(plmn.substring(0, 3));
                } catch(Exception e) {
                    // Nothing that can be done here.
                }
            }
        }
        return regionFromMcc;
    }
}
