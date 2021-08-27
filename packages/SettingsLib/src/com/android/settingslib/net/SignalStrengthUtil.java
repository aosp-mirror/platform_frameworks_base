/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.net;

import android.content.Context;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;

/**
 * Utilities for dealing with signal strength.
 */
public class SignalStrengthUtil {
    /**
     * @return whether we should artificially inflate the signal strength and number of levels by 1
     * bar for the subscription with the given id
     */
    public static boolean shouldInflateSignalStrength(Context context, int subscriptionId) {
        final CarrierConfigManager carrierConfigMgr =
                context.getSystemService(CarrierConfigManager.class);
        PersistableBundle bundle = null;
        if (carrierConfigMgr != null) {
            bundle = carrierConfigMgr.getConfigForSubId(subscriptionId);
        }
        return (bundle != null && bundle.getBoolean(
                CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL, false));
    }
}
