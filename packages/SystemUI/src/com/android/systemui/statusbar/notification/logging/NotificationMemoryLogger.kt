/*
 *
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.logging

import android.app.StatsManager
import android.util.Log
import android.util.StatsEvent
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.util.traceSection
import java.lang.Exception
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

/** Periodically logs current state of notification memory consumption. */
@SysUISingleton
class NotificationMemoryLogger
@Inject
constructor(
    private val notificationPipeline: NotifPipeline,
    private val statsManager: StatsManager,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundExecutor: Executor
) : StatsManager.StatsPullAtomCallback {

    /**
     * This class is used to accumulate and aggregate data - the fields mirror values in statd Atom
     * with ONE IMPORTANT difference - the values are in bytes, not KB!
     */
    internal data class NotificationMemoryUseAtomBuilder(val uid: Int, val style: Int) {
        var count: Int = 0
        var countWithInflatedViews: Int = 0
        var smallIconObject: Int = 0
        var smallIconBitmapCount: Int = 0
        var largeIconObject: Int = 0
        var largeIconBitmapCount: Int = 0
        var bigPictureObject: Int = 0
        var bigPictureBitmapCount: Int = 0
        var extras: Int = 0
        var extenders: Int = 0
        var smallIconViews: Int = 0
        var largeIconViews: Int = 0
        var systemIconViews: Int = 0
        var styleViews: Int = 0
        var customViews: Int = 0
        var softwareBitmaps: Int = 0
        var seenCount = 0
    }

    fun init() {
        statsManager.setPullAtomCallback(
            SysUiStatsLog.NOTIFICATION_MEMORY_USE,
            null,
            backgroundExecutor,
            this
        )
    }

    /** Called by statsd to pull data. */
    override fun onPullAtom(atomTag: Int, data: MutableList<StatsEvent>): Int =
        traceSection("NML#onPullAtom") {
            if (atomTag != SysUiStatsLog.NOTIFICATION_MEMORY_USE) {
                return StatsManager.PULL_SKIP
            }

            try {
                // Notifications can only be retrieved on the main thread, so switch to that thread.
                val notifications = getAllNotificationsOnMainThread()
                val notificationMemoryUse =
                    NotificationMemoryMeter.notificationMemoryUse(notifications)
                        .sortedWith(
                            compareBy(
                                { it.packageName },
                                { it.objectUsage.style },
                                { it.notificationKey }
                            )
                        )
                val usageData = aggregateMemoryUsageData(notificationMemoryUse)
                usageData.forEach { (_, use) ->
                    data.add(
                        SysUiStatsLog.buildStatsEvent(
                            SysUiStatsLog.NOTIFICATION_MEMORY_USE,
                            use.uid,
                            use.style,
                            use.count,
                            use.countWithInflatedViews,
                            toKb(use.smallIconObject),
                            use.smallIconBitmapCount,
                            toKb(use.largeIconObject),
                            use.largeIconBitmapCount,
                            toKb(use.bigPictureObject),
                            use.bigPictureBitmapCount,
                            toKb(use.extras),
                            toKb(use.extenders),
                            toKb(use.smallIconViews),
                            toKb(use.largeIconViews),
                            toKb(use.systemIconViews),
                            toKb(use.styleViews),
                            toKb(use.customViews),
                            toKb(use.softwareBitmaps),
                            use.seenCount
                        )
                    )
                }
            } catch (e: InterruptedException) {
                // This can happen if the device is sleeping or view walking takes too long.
                // The statsd collector will interrupt the thread and we need to handle it
                // gracefully.
                Log.w(NotificationLogger.TAG, "Timed out when measuring notification memory.", e)
                return@traceSection StatsManager.PULL_SKIP
            } catch (e: Exception) {
                // Error while collecting data, this should not crash prod SysUI. Just
                // log WTF and move on.
                Log.wtf(NotificationLogger.TAG, "Failed to measure notification memory.", e)
                return@traceSection StatsManager.PULL_SKIP
            }

            return StatsManager.PULL_SUCCESS
        }

    private fun getAllNotificationsOnMainThread() =
        runBlocking(mainDispatcher) {
            traceSection("NML#getNotifications") { notificationPipeline.allNotifs }
        }
}

/** Aggregates memory usage data by package and style, returning sums. */
@VisibleForTesting
internal fun aggregateMemoryUsageData(
    notificationMemoryUse: List<NotificationMemoryUsage>
): Map<Pair<String, Int>, NotificationMemoryLogger.NotificationMemoryUseAtomBuilder> {
    return notificationMemoryUse
        .groupingBy { Pair(it.packageName, it.objectUsage.style) }
        .aggregate {
            _,
            accumulator: NotificationMemoryLogger.NotificationMemoryUseAtomBuilder?,
            element: NotificationMemoryUsage,
            first ->
            val use =
                if (first) {
                    NotificationMemoryLogger.NotificationMemoryUseAtomBuilder(
                        element.uid,
                        element.objectUsage.style
                    )
                } else {
                    accumulator!!
                }

            use.count++
            // If the views of the notification weren't inflated, the list of memory usage
            // parameters will be empty.
            if (element.viewUsage.isNotEmpty()) {
                use.countWithInflatedViews++
            }

            use.smallIconObject += element.objectUsage.smallIcon
            if (element.objectUsage.smallIcon > 0) {
                use.smallIconBitmapCount++
            }

            use.largeIconObject += element.objectUsage.largeIcon
            if (element.objectUsage.largeIcon > 0) {
                use.largeIconBitmapCount++
            }

            use.bigPictureObject += element.objectUsage.bigPicture
            if (element.objectUsage.bigPicture > 0) {
                use.bigPictureBitmapCount++
            }

            use.extras += element.objectUsage.extras
            use.extenders += element.objectUsage.extender

            // Use totals count which are more accurate when aggregated
            // in this manner.
            element.viewUsage
                .firstOrNull { vu -> vu.viewType == ViewType.TOTAL }
                ?.let {
                    use.smallIconViews += it.smallIcon
                    use.largeIconViews += it.largeIcon
                    use.systemIconViews += it.systemIcons
                    use.styleViews += it.style
                    use.customViews += it.customViews
                    use.softwareBitmaps += it.softwareBitmapsPenalty
                }

            return@aggregate use
        }
}
/** Rounds the passed value to the nearest KB - e.g. 700B rounds to 1KB. */
private fun toKb(value: Int): Int = (value.toFloat() / 1024f).roundToInt()
