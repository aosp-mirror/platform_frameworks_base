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

import java.util.ArrayList;
import java.util.List;
import com.android.powermodel.AttributionKey;
import com.android.powermodel.ComponentActivity;
import com.android.powermodel.RawBatteryStats;
import com.android.powermodel.SpecialApp;

public class ModemBatteryStatsReader {
    private ModemBatteryStatsReader() {
    }

    public static List<ComponentActivity> createActivities(RawBatteryStats bs) {
        final List<ComponentActivity> result = new ArrayList<ComponentActivity>();

        // The whole system
        createGlobal(result, bs);

        // The apps
        createApps(result, bs);

        // The synthetic "cell" app.
        createRemainder(result, bs);

        return result;
    }

    private static void createGlobal(List<ComponentActivity> result, RawBatteryStats bs) {
        final ModemGlobalActivity global
                = new ModemGlobalActivity(new AttributionKey(SpecialApp.GLOBAL));

        final RawBatteryStats.GlobalNetwork gn = bs.getSingle(RawBatteryStats.GlobalNetwork.class);
        final RawBatteryStats.Misc misc = bs.getSingle(RawBatteryStats.Misc.class);

        // Null here just means no network activity.
        if (gn != null && misc != null) {
            global.rxPacketCount = gn.mobileRxTotalPackets;
            global.txPacketCount = gn.mobileTxTotalPackets;

            global.totalActiveTimeMs = misc.mobileRadioActiveTimeMs;
        }

        result.add(global);
    }

    private static void createApps(List<ComponentActivity> result, RawBatteryStats bs) {
        for (AttributionKey key: bs.getApps()) {
            final int uid = key.getUid();
            final RawBatteryStats.Network network
                    = bs.getSingle(RawBatteryStats.Network.class, uid);

            // Null here just means no network activity.
            if (network != null) {
                final ModemAppActivity app = new ModemAppActivity(key);

                app.rxPacketCount = network.mobileRxPackets;
                app.txPacketCount = network.mobileTxPackets;

                result.add(app);
            }
        }
    }

    private static void createRemainder(List<ComponentActivity> result, RawBatteryStats bs) {
        final RawBatteryStats.SignalStrengthTime strength
                = bs.getSingle(RawBatteryStats.SignalStrengthTime.class);
        final RawBatteryStats.SignalScanningTime scanning
                = bs.getSingle(RawBatteryStats.SignalScanningTime.class);
        final RawBatteryStats.Misc misc = bs.getSingle(RawBatteryStats.Misc.class);

        if (strength != null && scanning != null && misc != null) {
            final ModemRemainderActivity remainder
                    = new ModemRemainderActivity(new AttributionKey(SpecialApp.REMAINDER));

            // Signal strength buckets
            remainder.strengthTimeMs = strength.phoneSignalStrengthTimeMs;

            // Time spent scanning
            remainder.scanningTimeMs = scanning.phoneSignalScanningTimeMs;

            // Unaccounted for active time
            final long totalActiveTimeMs = misc.mobileRadioActiveTimeMs;
            long appActiveTimeMs = 0;
            for (RawBatteryStats.Network nw: bs.getMultiple(RawBatteryStats.Network.class)) {
                appActiveTimeMs += nw.mobileRadioActiveTimeUs / 1000;
            }
            remainder.activeTimeMs = totalActiveTimeMs - appActiveTimeMs;

            result.add(remainder);
        }
    }
}

