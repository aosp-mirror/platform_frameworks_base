/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.os;

import android.os.ResultReceiver;

/** @hide */
interface IPowerStatsService {
    /** @hide */
    const String KEY_MONITORS = "monitors";
    /** @hide */
    const String KEY_ENERGY = "energy";
    /** @hide */
    const String KEY_TIMESTAMPS = "timestamps";

    /** @hide */
    const int RESULT_SUCCESS = 0;
    /** @hide */
    const int RESULT_UNSUPPORTED_POWER_MONITOR = 1;

    /** {@hide} */
    oneway void getSupportedPowerMonitors(in ResultReceiver resultReceiver);
    /** {@hide} */
    oneway void getPowerMonitorReadings(in int[] powerMonitorIndices,
            in ResultReceiver resultReceiver);
}
