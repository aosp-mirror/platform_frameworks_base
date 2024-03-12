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

import android.stats.sysui.NotificationEnums
import android.util.Log
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.dump.DumpsysTableLogger
import com.android.systemui.dump.Row
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import dalvik.annotation.optimization.NeverCompile
import java.io.PrintWriter
import javax.inject.Inject

/** Dumps current notification memory use to bug reports for easier debugging. */
@SysUISingleton
class NotificationMemoryDumper
@Inject
constructor(val dumpManager: DumpManager, val notificationPipeline: NotifPipeline) : Dumpable {

    fun init() {
        dumpManager.registerNormalDumpable(javaClass.simpleName, this)
        Log.i("NotificationMemory", "Registered dumpable.")
    }

    @NeverCompile
    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(notificationPipeline.allNotifs)
                .sortedWith(compareBy({ it.packageName }, { it.notificationKey }))
        dumpNotificationObjects(pw, memoryUse)
        dumpNotificationViewUsage(pw, memoryUse)
    }

    /** Renders a table of notification object usage into passed [PrintWriter]. */
    private fun dumpNotificationObjects(pw: PrintWriter, memoryUse: List<NotificationMemoryUsage>) {
        val columns =
            listOf(
                "Package",
                "Small Icon",
                "Large Icon",
                "Style",
                "Style Icon",
                "Big Picture",
                "Extender",
                "Extras",
                "Custom View",
                "Key"
            )
        val rows: List<Row> =
            memoryUse.map {
                listOf(
                    it.packageName,
                    toKb(it.objectUsage.smallIcon),
                    toKb(it.objectUsage.largeIcon),
                    styleEnumToString(it.objectUsage.style),
                    toKb(it.objectUsage.styleIcon),
                    toKb(it.objectUsage.bigPicture),
                    toKb(it.objectUsage.extender),
                    toKb(it.objectUsage.extras),
                    it.objectUsage.hasCustomView.toString(),
                    // | is a  field delimiter in the output format so we need to replace
                    // it to avoid breakage.
                    it.notificationKey.replace('|', '│')
                )
            }

        // Calculate totals for easily glanceable summary.
        data class Totals(
            var smallIcon: Int = 0,
            var largeIcon: Int = 0,
            var styleIcon: Int = 0,
            var bigPicture: Int = 0,
            var extender: Int = 0,
            var extras: Int = 0,
        )

        val totals =
            memoryUse.fold(Totals()) { t, usage ->
                t.smallIcon += usage.objectUsage.smallIcon
                t.largeIcon += usage.objectUsage.largeIcon
                t.styleIcon += usage.objectUsage.styleIcon
                t.bigPicture += usage.objectUsage.bigPicture
                t.extender += usage.objectUsage.extender
                t.extras += usage.objectUsage.extras
                t
            }

        val totalsRow: List<Row> =
            listOf(
                listOf(
                    "TOTALS",
                    toKb(totals.smallIcon),
                    toKb(totals.largeIcon),
                    "",
                    toKb(totals.styleIcon),
                    toKb(totals.bigPicture),
                    toKb(totals.extender),
                    toKb(totals.extras),
                    "",
                    ""
                )
            )
        val tableLogger = DumpsysTableLogger("Notification Object Usage", columns, rows + totalsRow)
        tableLogger.printTableData(pw)
    }

    /** Renders a table of notification view usage into passed [PrintWriter] */
    private fun dumpNotificationViewUsage(
        pw: PrintWriter,
        memoryUse: List<NotificationMemoryUsage>,
    ) {

        data class Totals(
            var smallIcon: Int = 0,
            var largeIcon: Int = 0,
            var style: Int = 0,
            var customViews: Int = 0,
            var softwareBitmapsPenalty: Int = 0,
        )

        val columns =
            listOf(
                "Package",
                "View Type",
                "Small Icon",
                "Large Icon",
                "Style Use",
                "Custom View",
                "Software Bitmaps",
                "Key"
            )
        val rows =
            memoryUse
                .filter { it.viewUsage.isNotEmpty() }
                .flatMap { use ->
                    use.viewUsage.map { view ->
                        listOf(
                            use.packageName,
                            view.viewType.toString(),
                            toKb(view.smallIcon),
                            toKb(view.largeIcon),
                            toKb(view.style),
                            toKb(view.customViews),
                            toKb(view.softwareBitmapsPenalty),
                            // | is a  field delimiter in the output format so we need to replace
                            // it to avoid breakage.
                            use.notificationKey.replace('|', '│')
                        )
                    }
                }

        val totals = Totals()
        memoryUse
            .filter { it.viewUsage.isNotEmpty() }
            .map { it.viewUsage.firstOrNull { view -> view.viewType == ViewType.TOTAL } }
            .filterNotNull()
            .forEach { view ->
                totals.smallIcon += view.smallIcon
                totals.largeIcon += view.largeIcon
                totals.style += view.style
                totals.customViews += view.customViews
                totals.softwareBitmapsPenalty += view.softwareBitmapsPenalty
            }

        val totalsRow: List<Row> =
            listOf(
                listOf(
                    "TOTALS",
                    "",
                    toKb(totals.smallIcon),
                    toKb(totals.largeIcon),
                    toKb(totals.style),
                    toKb(totals.customViews),
                    toKb(totals.softwareBitmapsPenalty),
                    ""
                )
            )
        val tableLogger = DumpsysTableLogger("Notification View Usage", columns, rows + totalsRow)
        tableLogger.printTableData(pw)
    }

    private fun styleEnumToString(styleEnum: Int): String =
        when (styleEnum) {
            NotificationEnums.STYLE_UNSPECIFIED -> "Unspecified"
            NotificationEnums.STYLE_NONE -> "None"
            NotificationEnums.STYLE_BIG_PICTURE -> "BigPicture"
            NotificationEnums.STYLE_BIG_TEXT -> "BigText"
            NotificationEnums.STYLE_CALL -> "Call"
            NotificationEnums.STYLE_DECORATED_CUSTOM_VIEW -> "DCustomView"
            NotificationEnums.STYLE_INBOX -> "Inbox"
            NotificationEnums.STYLE_MEDIA -> "Media"
            NotificationEnums.STYLE_MESSAGING -> "Messaging"
            NotificationEnums.STYLE_RANKER_GROUP -> "RankerGroup"
            else -> "Unknown"
        }

    private fun toKb(bytes: Int): String {
        if (bytes == 0) {
            return "--"
        }

        return "%.2f KB".format(bytes / 1024f)
    }
}
