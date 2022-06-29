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
package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.ArraySet
import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender.OnEndLifetimeExtensionCallback
import com.android.systemui.statusbar.notification.collection.render.NotifGutsViewListener
import com.android.systemui.statusbar.notification.collection.render.NotifGutsViewManager
import com.android.systemui.statusbar.notification.row.NotificationGuts
import com.android.systemui.statusbar.notification.row.NotificationGutsManager
import java.io.PrintWriter
import javax.inject.Inject

private const val TAG = "GutsCoordinator"

/**
 * Coordinates the guts displayed by the [NotificationGutsManager] with the pipeline.
 * Specifically, this just adds the lifetime extension necessary to keep guts from disappearing.
 */
@CoordinatorScope
class GutsCoordinator @Inject constructor(
    private val notifGutsViewManager: NotifGutsViewManager,
    private val logger: GutsCoordinatorLogger,
    dumpManager: DumpManager
) : Coordinator, Dumpable {

    /** Keys of any Notifications for which we've been told the guts are open  */
    private val notifsWithOpenGuts = ArraySet<String>()

    /** Keys of any Notifications we've extended the lifetime for, based on guts  */
    private val notifsExtendingLifetime = ArraySet<String>()

    /** Callback for ending lifetime extension  */
    private var onEndLifetimeExtensionCallback: OnEndLifetimeExtensionCallback? = null

    init {
        dumpManager.registerDumpable(TAG, this)
    }

    override fun attach(pipeline: NotifPipeline) {
        notifGutsViewManager.setGutsListener(mGutsListener)
        pipeline.addNotificationLifetimeExtender(mLifetimeExtender)
    }

    override fun dump(pw: PrintWriter, args: Array<String>) {
        pw.println("  notifsWithOpenGuts: ${notifsWithOpenGuts.size}")
        for (key in notifsWithOpenGuts) {
            pw.println("   * $key")
        }
        pw.println("  notifsExtendingLifetime: ${notifsExtendingLifetime.size}")
        for (key in notifsExtendingLifetime) {
            pw.println("   * $key")
        }
        pw.println("  onEndLifetimeExtensionCallback: $onEndLifetimeExtensionCallback")
    }

    private val mLifetimeExtender: NotifLifetimeExtender = object : NotifLifetimeExtender {
        override fun getName(): String {
            return TAG
        }

        override fun setCallback(callback: OnEndLifetimeExtensionCallback) {
            onEndLifetimeExtensionCallback = callback
        }

        override fun maybeExtendLifetime(entry: NotificationEntry, reason: Int): Boolean {
            val isShowingGuts = isCurrentlyShowingGuts(entry)
            if (isShowingGuts) {
                notifsExtendingLifetime.add(entry.key)
            }
            return isShowingGuts
        }

        override fun cancelLifetimeExtension(entry: NotificationEntry) {
            notifsExtendingLifetime.remove(entry.key)
        }
    }

    private val mGutsListener: NotifGutsViewListener = object : NotifGutsViewListener {
        override fun onGutsOpen(entry: NotificationEntry, guts: NotificationGuts) {
            logger.logGutsOpened(entry.key, guts)
            if (guts.isLeavebehind) {
                // leave-behind guts should not extend the lifetime of the notification
                closeGutsAndEndLifetimeExtension(entry)
            } else {
                notifsWithOpenGuts.add(entry.key)
            }
        }

        override fun onGutsClose(entry: NotificationEntry) {
            logger.logGutsClosed(entry.key)
            closeGutsAndEndLifetimeExtension(entry)
        }
    }

    private fun isCurrentlyShowingGuts(entry: ListEntry) =
            notifsWithOpenGuts.contains(entry.key)

    private fun closeGutsAndEndLifetimeExtension(entry: NotificationEntry) {
        notifsWithOpenGuts.remove(entry.key)
        if (notifsExtendingLifetime.remove(entry.key)) {
            onEndLifetimeExtensionCallback?.onEndLifetimeExtension(mLifetimeExtender, entry)
        }
    }
}
