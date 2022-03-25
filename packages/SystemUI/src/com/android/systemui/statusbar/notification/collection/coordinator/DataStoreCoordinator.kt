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

import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifLiveDataStoreImpl
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.render.requireSummary
import javax.inject.Inject

/**
 * A small coordinator which updates the notif stack (the view layer which holds notifications)
 * with high-level data after the stack is populated with the final entries.
 */
@CoordinatorScope
class DataStoreCoordinator @Inject internal constructor(
    private val notifLiveDataStoreImpl: NotifLiveDataStoreImpl
) : Coordinator {

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnAfterRenderListListener { entries, _ -> onAfterRenderList(entries) }
    }

    fun onAfterRenderList(entries: List<ListEntry>) {
        val flatEntryList = flattenedEntryList(entries)
        notifLiveDataStoreImpl.setActiveNotifList(flatEntryList)
    }

    private fun flattenedEntryList(entries: List<ListEntry>) =
        mutableListOf<NotificationEntry>().also { list ->
            entries.forEach { entry ->
                when (entry) {
                    is NotificationEntry -> list.add(entry)
                    is GroupEntry -> {
                        list.add(entry.requireSummary)
                        list.addAll(entry.children)
                    }
                    else -> error("Unexpected entry $entry")
                }
            }
        }
}