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

import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import com.android.app.tracing.coroutines.withContext
import com.android.systemui.common.usagestats.data.model.UsageStatsQuery
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel
import com.android.systemui.common.usagestats.shared.model.ActivityEventModel.Lifecycle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** Repository for querying UsageStatsManager */
interface UsageStatsRepository {
    /** Query activity events. */
    suspend fun queryActivityEvents(query: UsageStatsQuery): List<ActivityEventModel>
}

@SysUISingleton
class UsageStatsRepositoryImpl
@Inject
constructor(
    @Background private val bgContext: CoroutineContext,
    private val usageStatsManager: UsageStatsManager,
) : UsageStatsRepository {
    private companion object {
        const val TAG = "UsageStatsRepository"
    }

    override suspend fun queryActivityEvents(query: UsageStatsQuery): List<ActivityEventModel> =
        withContext("$TAG#queryActivityEvents", bgContext) {
            val systemQuery: UsageEventsQuery =
                UsageEventsQuery.Builder(query.startTime, query.endTime)
                    .apply {
                        setUserId(query.user.identifier)
                        setEventTypes(
                            UsageEvents.Event.ACTIVITY_RESUMED,
                            UsageEvents.Event.ACTIVITY_PAUSED,
                            UsageEvents.Event.ACTIVITY_STOPPED,
                            UsageEvents.Event.ACTIVITY_DESTROYED,
                        )
                        if (query.packageNames.isNotEmpty()) {
                            setPackageNames(*query.packageNames.toTypedArray())
                        }
                    }
                    .build()

            val events: UsageEvents? = usageStatsManager.queryEvents(systemQuery)

            buildList {
                events.forEachEvent { event ->
                    val lifecycle =
                        when (event.eventType) {
                            UsageEvents.Event.ACTIVITY_RESUMED -> Lifecycle.RESUMED
                            UsageEvents.Event.ACTIVITY_PAUSED -> Lifecycle.PAUSED
                            UsageEvents.Event.ACTIVITY_STOPPED -> Lifecycle.STOPPED
                            UsageEvents.Event.ACTIVITY_DESTROYED -> Lifecycle.DESTROYED
                            else -> Lifecycle.UNKNOWN
                        }

                    add(
                        ActivityEventModel(
                            instanceId = event.instanceId,
                            packageName = event.packageName,
                            lifecycle = lifecycle,
                            timestamp = event.timeStamp,
                        )
                    )
                }
            }
        }
}

private inline fun UsageEvents?.forEachEvent(action: (UsageEvents.Event) -> Unit) {
    this ?: return
    val event = UsageEvents.Event()
    while (getNextEvent(event)) {
        action(event)
    }
}
