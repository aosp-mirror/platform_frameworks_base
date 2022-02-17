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

package com.android.internal.app.procstats;

import android.util.SparseArray;

/**
 * The internal interface to access the process stats service, to be used within
 * system_server only.
 */
public abstract class ProcessStatsInternal {
    /**
     * Return the duration over the given time, that an UID spent in each processs state
     * which is defined in the {@link ProcessStats}, the key of the array is the uid.
     */
    public abstract SparseArray<long[]> getUidProcStateStatsOverTime(long minTime);
}
