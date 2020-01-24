/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import android.content.Context
import android.service.notification.NotificationListenerService.REASON_APP_CANCEL
import android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL
import android.service.notification.NotificationListenerService.REASON_CANCEL
import android.service.notification.NotificationListenerService.REASON_CANCEL_ALL
import android.service.notification.NotificationListenerService.REASON_CLICK
import android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout

import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.R
import com.android.systemui.statusbar.notification.ForegroundServiceDismissalFeatureController
import com.android.systemui.statusbar.notification.NotificationEntryListener
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.row.DungeonRow
import com.android.systemui.util.Assert

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller for the bottom area of NotificationStackScrollLayout. It owns swiped-away foreground
 * service notifications and can reinstantiate them when requested.
 */
@Singleton
class ForegroundServiceSectionController @Inject constructor(
    val entryManager: NotificationEntryManager,
    val featureController: ForegroundServiceDismissalFeatureController
) {
    private val TAG = "FgsSectionController"
    private var context: Context? = null

    private val entries = mutableSetOf<NotificationEntry>()

    private var entriesView: View? = null

    init {
        if (featureController.isForegroundServiceDismissalEnabled()) {
            entryManager.addNotificationRemoveInterceptor(this::shouldInterceptRemoval)

            entryManager.addNotificationEntryListener(object : NotificationEntryListener {
                override fun onPostEntryUpdated(entry: NotificationEntry) {
                    if (entries.contains(entry)) {
                        removeEntry(entry)
                        addEntry(entry)
                        update()
                    }
                }
            })
        }
    }

    private fun shouldInterceptRemoval(
        key: String,
        entry: NotificationEntry?,
        reason: Int
    ): Boolean {
        Assert.isMainThread()
        val isClearAll = reason == REASON_CANCEL_ALL
        val isUserDismiss = reason == REASON_CANCEL || reason == REASON_CLICK
        val isAppCancel = reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL
        val isSummaryCancel = reason == REASON_GROUP_SUMMARY_CANCELED

        if (entry == null) return false

        // We only want to retain notifications that the user dismissed
        // TODO: centralize the entry.isClearable logic and this so that it's clear when a notif is
        // clearable
        if (isUserDismiss && !entry.sbn.isClearable) {
            if (!hasEntry(entry)) {
                addEntry(entry)
                update()
            }
            // TODO: This isn't ideal. Slightly better would at least be to have NEM update the
            // notif list when an entry gets intercepted
            entryManager.updateNotifications(
                    "FgsSectionController.onNotificationRemoveRequested")
            return true
        } else if ((isClearAll || isSummaryCancel) && !entry.sbn.isClearable) {
            // In the case where a FGS notification is part of a group that is cleared or a clear
            // all, we actually want to stop its removal but also not put it into the dungeon
            return true
        } else if (hasEntry(entry)) {
            removeEntry(entry)
            update()
            return false
        }

        return false
    }

    private fun removeEntry(entry: NotificationEntry) {
        Assert.isMainThread()
        entries.remove(entry)
    }

    private fun addEntry(entry: NotificationEntry) {
        Assert.isMainThread()
        entries.add(entry)
    }

    fun hasEntry(entry: NotificationEntry): Boolean {
        Assert.isMainThread()
        return entries.contains(entry)
    }

    fun initialize(context: Context) {
        this.context = context
    }

    fun createView(li: LayoutInflater): View {
        entriesView = li.inflate(R.layout.foreground_service_dungeon, null)
        // Start out gone
        entriesView!!.visibility = View.GONE
        return entriesView!!
    }

    private fun update() {
        Assert.isMainThread()
        if (entriesView == null) {
            throw IllegalStateException("ForegroundServiceSectionController is trying to show " +
                    "dismissed fgs notifications without having been initialized!")
        }

        // TODO: these views should be recycled and not inflating on the main thread
        (entriesView!!.findViewById(R.id.entry_list) as LinearLayout).apply {
            removeAllViews()
            entries.sortedBy { it.ranking.rank }.forEach { entry ->
                val child = LayoutInflater.from(context)
                        .inflate(R.layout.foreground_service_dungeon_row, null) as DungeonRow

                child.entry = entry
                child.setOnClickListener {
                    removeEntry(child.entry!!)
                    update()
                    entry.row.unDismiss()
                    entry.row.resetTranslation()
                    entryManager.updateNotifications("ForegroundServiceSectionController.onClick")
                }

                addView(child)
            }
        }

        if (entries.isEmpty()) {
            entriesView?.visibility = View.GONE
        } else {
            entriesView?.visibility = View.VISIBLE
        }
    }
}
