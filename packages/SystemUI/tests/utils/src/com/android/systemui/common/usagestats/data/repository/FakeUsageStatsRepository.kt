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

package com.android.systemui.common.usagestats.data.repository

import android.annotation.CurrentTimeMillisLong
import android.app.usage.UsageEvents
import android.os.UserHandle
import com.android.systemui.common.usagestats.data.model.UsageStatsQuery
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel.Lifecycle

class FakeUsageStatsRepository : UsageStatsRepository {
    private val events = mutableMapOf<UserHandle, MutableList<UsageEvents.Event>>()

    override suspend fun queryActivityEvents(query: UsageStatsQuery): List<ActivityEventModel> {
        return events
            .getOrDefault(query.user, emptyList())
            .filter { event ->
                query.packageNames.isEmpty() || query.packageNames.contains(event.packageName)
            }
            .filter { event -> event.timeStamp in query.startTime until query.endTime }
            .filter { event -> event.eventType.toActivityLifecycle() != Lifecycle.UNKNOWN }
            .map { event ->
                ActivityEventModel(
                    instanceId = event.instanceId,
                    packageName = event.packageName,
                    lifecycle = event.eventType.toActivityLifecycle(),
                    timestamp = event.timeStamp,
                )
            }
    }

    fun addEvent(
        instanceId: Int,
        user: UserHandle,
        packageName: String,
        @UsageEvents.Event.EventType type: Int,
        @CurrentTimeMillisLong timestamp: Long,
    ) {
        events
            .getOrPut(user) { mutableListOf() }
            .add(
                UsageEvents.Event(type, timestamp).apply {
                    mPackage = packageName
                    mInstanceId = instanceId
                }
            )
    }
}

private fun Int.toActivityLifecycle(): Lifecycle =
    when (this) {
        UsageEvents.Event.ACTIVITY_RESUMED -> Lifecycle.RESUMED
        UsageEvents.Event.ACTIVITY_PAUSED -> Lifecycle.PAUSED
        UsageEvents.Event.ACTIVITY_STOPPED -> Lifecycle.STOPPED
        UsageEvents.Event.ACTIVITY_DESTROYED -> Lifecycle.DESTROYED
        else -> Lifecycle.UNKNOWN
    }
