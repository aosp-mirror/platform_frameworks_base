/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.common.usagestats.data.model

import android.annotation.CurrentTimeMillisLong
import android.app.usage.UsageStatsManager
import android.os.UserHandle
import com.android.systemui.util.time.SystemClock

/** Models a query which can be made to [UsageStatsManager] */
data class UsageStatsQuery(
    /** Specifies the user for the query. */
    val user: UserHandle,
    /**
     * The inclusive beginning of the range of events to include. Defined in unix time, see
     * [SystemClock.currentTimeMillis]
     */
    @CurrentTimeMillisLong val startTime: Long,
    /**
     * The exclusive end of the range of events to include. Defined in unix time, see
     * [SystemClock.currentTimeMillis]
     */
    @CurrentTimeMillisLong val endTime: Long,
    /**
     * The list of package names to be included in the query. If empty, events for all packages will
     * be queried.
     */
    val packageNames: List<String> = emptyList(),
)
