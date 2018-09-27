/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

import android.content.ComponentName;
import android.os.ParcelFileDescriptor;
import com.android.internal.app.procstats.ProcessStats;

interface IProcessStats {
    byte[] getCurrentStats(out List<ParcelFileDescriptor> historic);
    ParcelFileDescriptor getStatsOverTime(long minTime);
    int getCurrentMemoryState();

    /**
     * Get stats committed after highWaterMarkMs
     * @param highWaterMarkMs Report stats committed after this time.
     * @param section Integer mask to indicate which sections to include in the stats.
     * @param doAggregate Whether to aggregate the stats or keep them separated.
     * @param List of Files of individual commits in protobuf binary or one that is merged from them.
     */
     long getCommittedStats(long highWaterMarkMs, int section, boolean doAggregate,
        out List<ParcelFileDescriptor> committedStats);
}
