/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel.component;

import com.android.powermodel.ActivityReport;
import com.android.powermodel.AttributionKey;
import com.android.powermodel.Component;
import com.android.powermodel.ComponentActivity;
import com.android.powermodel.PowerProfile;
import com.android.powermodel.util.Conversion;

/**
 * Encapsulates the work done by the remaining 
 */
public class ModemRemainderActivity extends ComponentActivity {
    /**
     * Construct a new ModemRemainderActivity.
     */
    public ModemRemainderActivity(AttributionKey attribution) {
        super(attribution);
    }

    /**
     * Number of milliseconds spent at each of the signal strengths.
     */
    public long[] strengthTimeMs;

    /**
     * Number of milliseconds spent scanning for a network.
     */
    public long scanningTimeMs;

    /**
     * Number of milliseconds that the radio is active for reasons other
     * than an app transmitting and receiving data.
     */
    public long activeTimeMs;

    @Override
    public ModemRemainderPower applyProfile(ActivityReport activityReport, PowerProfile profile) {
        // Profile
        final ModemProfile modemProfile = (ModemProfile)profile.getComponent(Component.MODEM);
        if (modemProfile == null) {
            return null;
        }

        // Activity
        final ModemRemainderPower result = new ModemRemainderPower();
        result.attribution = this.attribution;
        result.activity = this;

        // strengthMah
        // TODO: If the array lengths don't match... then?
        result.strengthMah = new double[this.strengthTimeMs.length];
        for (int i=0; i<this.strengthTimeMs.length; i++) {
            result.strengthMah[i] = Conversion.msToHr(
                    this.strengthTimeMs[i] * modemProfile.getTxMa()[i]);
            result.powerMah += result.strengthMah[i];
        }

        // scanningMah
        result.scanningMah = Conversion.msToHr(this.scanningTimeMs * modemProfile.getScanningMa());
        result.powerMah += result.scanningMah;

        // activeMah
        result.activeMah = Conversion.msToHr(
                this.activeTimeMs * ModemAppActivity.getAverageModemPowerMa(modemProfile));
        result.powerMah += result.activeMah;

        return result;
    }
}

