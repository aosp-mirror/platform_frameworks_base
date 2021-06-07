/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.NonNull;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;

/** Multiplier that makes things more expensive in adaptive and full battery saver are active. */
class PowerSaveModeMultiplier extends Multiplier {
    private final InternalResourceService mIrs;
    private final PowerManager mPowerManager;

    PowerSaveModeMultiplier(@NonNull InternalResourceService irs) {
        super(true, false);
        mIrs = irs;
        mPowerManager = irs.getContext().getSystemService(PowerManager.class);
    }

    double getCurrentMultiplier() {
        if (mPowerManager.isPowerSaveMode()) {
            return 1.5;
        }
        // TODO: get adaptive power save mode
        if (mPowerManager.isPowerSaveMode()) {
            return 1.25;
        }
        return 1;
    }

    @Override
    void dump(IndentingPrintWriter pw) {
        pw.print("power save=");
        pw.println(mPowerManager.isPowerSaveMode());
    }
}
