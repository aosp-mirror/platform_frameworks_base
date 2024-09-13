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

package com.android.systemui.common.usagestats.domain

import android.annotation.CurrentTimeMillisLong
import android.os.UserHandle
import com.android.systemui.common.usagestats.data.model.UsageStatsQuery
import com.android.systemui.common.usagestats.data.repository.UsageStatsRepository
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

@SysUISingleton
class UsageStatsInteractor
@Inject
constructor(
    private val userTracker: UserTracker,
    private val repository: UsageStatsRepository,
    private val systemClock: SystemClock,
) {
    suspend fun queryActivityEvents(
        @CurrentTimeMillisLong startTime: Long,
        @CurrentTimeMillisLong endTime: Long = systemClock.currentTimeMillis(),
        userHandle: UserHandle = UserHandle.CURRENT,
        packageNames: List<String> = emptyList(),
    ): List<ActivityEventModel> {
        val user =
            if (userHandle == UserHandle.CURRENT) {
                userTracker.userHandle
            } else {
                userHandle
            }

        return repository.queryActivityEvents(
            UsageStatsQuery(
                startTime = startTime,
                endTime = endTime,
                user = user,
                packageNames = packageNames,
            )
        )
    }
}
