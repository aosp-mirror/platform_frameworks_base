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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.ArrayMap
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.render.NotifRowController
import javax.inject.Inject
import kotlin.math.max

/**
 * A small coordinator which ensures the "alerted" bell shows not just for recently alerted entries,
 * but also on the summary for every such entry.
 */
@CoordinatorScope
class RowAlertTimeCoordinator @Inject constructor() : Coordinator {

    private val latestAlertTimeBySummary = ArrayMap<NotificationEntry, Long>()

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnBeforeFinalizeFilterListener(::onBeforeFinalizeFilterListener)
        pipeline.addOnAfterRenderEntryListener(::onAfterRenderEntry)
    }

    private fun onBeforeFinalizeFilterListener(entries: List<ListEntry>) {
        latestAlertTimeBySummary.clear()
        entries.asSequence().filterIsInstance<GroupEntry>().forEach { groupEntry ->
            val summary = checkNotNull(groupEntry.summary)
            latestAlertTimeBySummary[summary] = groupEntry.calculateLatestAlertTime()
        }
    }

    private fun onAfterRenderEntry(entry: NotificationEntry, controller: NotifRowController) {
        // Show the "alerted" bell icon based on the latest group member for summaries
        val lastAudiblyAlerted = latestAlertTimeBySummary[entry] ?: entry.lastAudiblyAlertedMs
        controller.setLastAudibleMs(lastAudiblyAlerted)
    }

    private fun GroupEntry.calculateLatestAlertTime(): Long {
        val lastChildAlertedTime = children.maxOfOrNull { it.lastAudiblyAlertedMs } ?: 0
        val summaryAlertedTime = checkNotNull(summary).lastAudiblyAlertedMs
        return max(lastChildAlertedTime, summaryAlertedTime)
    }
}
