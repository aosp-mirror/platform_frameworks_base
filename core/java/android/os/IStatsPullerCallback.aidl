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

package android.os;

import android.os.StatsLogEventWrapper;

/**
  * Binder interface to pull atoms for the stats service.
  * {@hide}
  */
interface IStatsPullerCallback {
    /**
     * Pull data for the specified atom tag. Returns an array of StatsLogEventWrapper containing
     * the data.
     *
     * Note: These pulled atoms should not have uid/attribution chain. Additionally, the event
     * timestamps will be truncated to the nearest 5 minutes.
     */
    StatsLogEventWrapper[] pullData(int atomTag, long elapsedNanos, long wallClocknanos);

}
