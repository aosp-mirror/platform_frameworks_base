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
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.NotificationIconAreaController
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
    private val viewDiffer = ShadeViewDiffer(rootController, logger)

    fun attach(listBuilder: ShadeListBuilder) =
            listBuilder.setOnRenderListListener(::onNewNotifTree)

    private fun onNewNotifTree(tree: List<ListEntry>) = viewDiffer.applySpec(buildTree(tree))

    private fun buildTree(notifList: List<ListEntry>): NodeSpec {
        val root = NodeSpecImpl(null, rootController).apply {
            // Insert first section header, if present
            notifList.firstOrNull()?.section?.headerController?.let {
                children.add(NodeSpecImpl(this, it))
            }
            notifList.asSequence().zipWithNext().forEach { (prev, entry) ->
                // Insert new header if the section has changed between two entries
                entry.section.takeIf { it != prev.section }?.headerController?.let {
                    children.add(NodeSpecImpl(this, it))
                }
                children.add(buildNotifNode(entry, this))
            }
        }
        notificationIconAreaController.updateNotificationIcons(notifList)
        return root
    }

    private fun buildNotifNode(entry: ListEntry, parent: NodeSpec): NodeSpec = when (entry) {
        is NotificationEntry -> NodeSpecImpl(parent, viewBarn.requireView(entry))
        is GroupEntry -> NodeSpecImpl(parent, viewBarn.requireView(checkNotNull(entry.summary)))
                .apply { entry.children.forEach { children.add(buildNotifNode(it, this)) } }
        else -> throw RuntimeException("Unexpected entry: $entry")
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
