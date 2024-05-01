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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.ArrayMap
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.render.NotifGroupController
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/** A small coordinator which finds, stores, and applies the closest notification time. */
@CoordinatorScope
class GroupWhenCoordinator
@Inject
constructor(
    @Main private val delayableExecutor: DelayableExecutor,
    private val systemClock: SystemClock
) : Coordinator {

    private val invalidator = object : Invalidator("GroupWhenCoordinator") {}
    private val notificationGroupTimes = ArrayMap<GroupEntry, Long>()
    private var cancelInvalidateListRunnable: Runnable? = null

    private val invalidateListRunnable: Runnable = Runnable {
        invalidator.invalidateList("future notification invalidation")
    }

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnBeforeFinalizeFilterListener(::onBeforeFinalizeFilterListener)
        pipeline.addOnAfterRenderGroupListener(::onAfterRenderGroupListener)
        pipeline.addPreRenderInvalidator(invalidator)
    }

    private fun onBeforeFinalizeFilterListener(entries: List<ListEntry>) {
        cancelListInvalidation()
        notificationGroupTimes.clear()

        val now = systemClock.currentTimeMillis()
        var closestFutureTime = Long.MAX_VALUE
        entries.asSequence().filterIsInstance<GroupEntry>().forEach { groupEntry ->
            val whenMillis = calculateGroupNotificationTime(groupEntry, now)
            notificationGroupTimes[groupEntry] = whenMillis
            if (whenMillis > now) {
                closestFutureTime = min(closestFutureTime, whenMillis)
            }
        }

        if (closestFutureTime != Long.MAX_VALUE) {
            cancelInvalidateListRunnable =
                delayableExecutor.executeDelayed(invalidateListRunnable, closestFutureTime - now)
        }
    }

    private fun cancelListInvalidation() {
        cancelInvalidateListRunnable?.run()
        cancelInvalidateListRunnable = null
    }

    private fun onAfterRenderGroupListener(group: GroupEntry, controller: NotifGroupController) {
        notificationGroupTimes[group]?.let(controller::setNotificationGroupWhen)
    }

    private fun calculateGroupNotificationTime(
        groupEntry: GroupEntry,
        currentTimeMillis: Long
    ): Long {
        var pastTime = Long.MIN_VALUE
        var futureTime = Long.MAX_VALUE
        groupEntry.children
            .asSequence()
            .mapNotNull { child -> child.sbn.notification.getWhen().takeIf { it > 0 } }
            .forEach { time ->
                val isInThePast = currentTimeMillis - time > 0
                if (isInThePast) {
                    pastTime = max(pastTime, time)
                } else {
                    futureTime = min(futureTime, time)
                }
            }

        if (pastTime == Long.MIN_VALUE && futureTime == Long.MAX_VALUE) {
            return checkNotNull(groupEntry.summary).creationTime
        }

        return if (futureTime != Long.MAX_VALUE) futureTime else pastTime
    }
}
