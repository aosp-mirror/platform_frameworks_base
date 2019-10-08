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
 * Encapsulates the work done by the celluar modem on behalf of an app.
 */
public class ModemAppActivity extends ComponentActivity {
    /**
     * Construct a new ModemAppActivity.
     */
    public ModemAppActivity(AttributionKey attribution) {
        super(attribution);
    }

    /**
     * The number of packets received by the app.
     */
    public long rxPacketCount;

    /**
     * The number of packets sent by the app.
     */
    public long txPacketCount;

    @Override
    public ModemAppPower applyProfile(ActivityReport activityReport, PowerProfile profile) {
        // Profile
        final ModemProfile modemProfile = (ModemProfile)profile.getComponent(Component.MODEM);
        if (modemProfile == null) {
            // TODO: This is kind of a big problem...  Should this throw instead?
            return null;
        }

        // Activity
        final ModemGlobalActivity global
                = (ModemGlobalActivity)activityReport.findGlobalComponent(Component.MODEM);
        if (global == null) {
            return null;
        }

        final double averageModemPowerMa = getAverageModemPowerMa(modemProfile);
        final long totalPacketCount = global.rxPacketCount + global.txPacketCount;
        final long appPacketCount = this.rxPacketCount + this.txPacketCount;

        final ModemAppPower result = new ModemAppPower();
        result.attribution = this.attribution;
        result.activity = this;
        result.powerMah = Conversion.msToHr(
                (totalPacketCount > 0 ? (appPacketCount / (double)totalPacketCount) : 0)
                * global.totalActiveTimeMs
                * averageModemPowerMa);
        return result;
    }

    static final double getAverageModemPowerMa(ModemProfile profile) {
        double sumMa = profile.getRxMa();
        for (float powerAtTxLevelMa: profile.getTxMa()) {
            sumMa += powerAtTxLevelMa;
        }
        return sumMa / (profile.getTxMa().length + 1);
    }
}

