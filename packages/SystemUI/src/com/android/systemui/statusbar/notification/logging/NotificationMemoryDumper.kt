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
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import java.io.PrintWriter
import javax.inject.Inject

/** Dumps current notification memory use to bug reports for easier debugging. */
@SysUISingleton
class NotificationMemoryDumper
@Inject
constructor(val dumpManager: DumpManager, val notificationPipeline: NotifPipeline) : Dumpable {

    fun init() {
        dumpManager.registerNormalDumpable(javaClass.simpleName, this)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val memoryUse =
            NotificationMemoryMeter.notificationMemoryUse(notificationPipeline.allNotifs)
                .sortedWith(compareBy({ it.packageName }, { it.notificationKey }))
        dumpNotificationObjects(pw, memoryUse)
        dumpNotificationViewUsage(pw, memoryUse)
    }

    /** Renders a table of notification object usage into passed [PrintWriter]. */
    private fun dumpNotificationObjects(pw: PrintWriter, memoryUse: List<NotificationMemoryUsage>) {
        pw.println("Notification Object Usage")
        pw.println("-----------")
        pw.println(
            "Package".padEnd(35) +
                "\t\tSmall\tLarge\t${"Style".padEnd(15)}\t\tStyle\tBig\tExtend.\tExtras\tCustom"
        )
        pw.println("".padEnd(35) + "\t\tIcon\tIcon\t${"".padEnd(15)}\t\tIcon\tPicture\t \t \tView")
        pw.println()

        memoryUse.forEach { use ->
            pw.println(
                use.packageName.padEnd(35) +
                    "\t\t" +
                    "${use.objectUsage.smallIcon}\t${use.objectUsage.largeIcon}\t" +
                    (styleEnumToString(use.objectUsage.style).take(15) ?: "").padEnd(15) +
                    "\t\t${use.objectUsage.styleIcon}\t" +
                    "${use.objectUsage.bigPicture}\t${use.objectUsage.extender}\t" +
                    "${use.objectUsage.extras}\t${use.objectUsage.hasCustomView}\t" +
                    use.notificationKey
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

        pw.println()
        pw.println("TOTALS")
        pw.println(
            "".padEnd(35) +
                "\t\t" +
                "${toKb(totals.smallIcon)}\t${toKb(totals.largeIcon)}\t" +
                "".padEnd(15) +
                "\t\t${toKb(totals.styleIcon)}\t" +
                "${toKb(totals.bigPicture)}\t${toKb(totals.extender)}\t" +
                toKb(totals.extras)
        )
        pw.println()
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

        val totals = Totals()
        pw.println("Notification View Usage")
        pw.println("-----------")
        pw.println("View Type".padEnd(24) + "\tSmall\tLarge\tStyle\tCustom\tSoftware")
        pw.println("".padEnd(24) + "\tIcon\tIcon\tUse\tView\tBitmaps")
        pw.println()
        memoryUse
            .filter { it.viewUsage.isNotEmpty() }
            .forEach { use ->
                pw.println(use.packageName + " " + use.notificationKey)
                use.viewUsage.forEach { view ->
                    pw.println(
                        "  ${view.viewType.toString().padEnd(24)}\t${view.smallIcon}" +
                            "\t${view.largeIcon}\t${view.style}" +
                            "\t${view.customViews}\t${view.softwareBitmapsPenalty}"
                    )

                    if (view.viewType == ViewType.TOTAL) {
                        totals.smallIcon += view.smallIcon
                        totals.largeIcon += view.largeIcon
                        totals.style += view.style
                        totals.customViews += view.customViews
                        totals.softwareBitmapsPenalty += view.softwareBitmapsPenalty
                    }
                }
            }
        pw.println()
        pw.println("TOTALS")
        pw.println(
            "  ${"".padEnd(24)}\t${toKb(totals.smallIcon)}" +
                "\t${toKb(totals.largeIcon)}\t${toKb(totals.style)}" +
                "\t${toKb(totals.customViews)}\t${toKb(totals.softwareBitmapsPenalty)}"
        )
        pw.println()
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
        return (bytes / 1024).toString() + " KB"
    }
}
