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

package com.android.systemui.statusbar.notification.collection.render

import android.content.Context
import android.view.View
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.NotificationIconAreaController
import com.android.systemui.util.traceSection
import javax.inject.Inject

/**
 * Responsible for building and applying the "shade node spec": the list (tree) of things that
 * currently populate the notification shade.
 */
class ShadeViewManager constructor(
    context: Context,
    listContainer: NotificationListContainer,
    logger: ShadeViewDifferLogger,
    private val viewBarn: NotifViewBarn,
    private val notificationIconAreaController: NotificationIconAreaController
) {
    // We pass a shim view here because the listContainer may not actually have a view associated
    // with it and the differ never actually cares about the root node's view.
    private val rootController = RootNodeController(listContainer, View(context))
    private val specBuilder = NodeSpecBuilder(viewBarn)
    private val viewDiffer = ShadeViewDiffer(rootController, logger)

    fun attach(listBuilder: ShadeListBuilder) =
            listBuilder.setOnRenderListListener(::onNewNotifTree)

    private fun onNewNotifTree(notifList: List<ListEntry>) {
        traceSection("ShadeViewManager.onNewNotifTree") {
            viewDiffer.applySpec(specBuilder.buildNodeSpec(rootController, notifList))
            updateGroupCounts(notifList)
            notificationIconAreaController.updateNotificationIcons(notifList)
        }
    }

    private fun updateGroupCounts(notifList: List<ListEntry>) {
        traceSection("ShadeViewManager.updateGroupCounts") {
            notifList.asSequence().filterIsInstance<GroupEntry>().forEach { groupEntry ->
                val controller = viewBarn.requireView(checkNotNull(groupEntry.summary))
                val row = controller.view as ExpandableNotificationRow
                row.setUntruncatedChildCount(groupEntry.untruncatedChildCount)
            }
        }
    }
}

class ShadeViewManagerFactory @Inject constructor(
    private val context: Context,
    private val logger: ShadeViewDifferLogger,
    private val viewBarn: NotifViewBarn,
    private val notificationIconAreaController: NotificationIconAreaController
) {
    fun create(listContainer: NotificationListContainer) =
            ShadeViewManager(
                    context,
                    listContainer,
                    logger,
                    viewBarn,
                    notificationIconAreaController)
}
