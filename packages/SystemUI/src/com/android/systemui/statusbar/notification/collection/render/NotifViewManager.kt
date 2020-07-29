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

import android.annotation.MainThread
import android.view.View
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntry.ROOT_ENTRY
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import javax.inject.Inject

/**
 * A consumer of a Notification tree built by [ShadeListBuilder] which will update the notification
 * presenter with the minimum operations required to make the old tree match the new one
 */
@MainThread
class NotifViewManager constructor(
    private val listContainer: NotificationListContainer,
    private val viewBarn: NotifViewBarn,
    private val logger: NotifViewManagerLogger
) {
    private val rootNode = RootWrapper(listContainer)
    private val rows = mutableMapOf<ListEntry, RowNode>()

    fun attach(listBuilder: ShadeListBuilder) {
        listBuilder.setOnRenderListListener(::onNewNotifTree)
    }

    private fun onNewNotifTree(tree: List<ListEntry>) {
        // Step 1: Detach all views whose parents have changed
        detachRowsWithModifiedParents()

        // Step 2: Attach all new views and reattach all views whose parents changed.
        // Also reorder existing children to match the spec we've received
        val orderChanged = addAndReorderChildren(rootNode, tree)
        if (orderChanged) {
            listContainer.generateChildOrderChangedEvent()
        }
    }

    private fun detachRowsWithModifiedParents() {
        val toRemove = mutableListOf<ListEntry>()
        for (row in rows.values) {
            val oldParentEntry = row.nodeParent?.entry
            val newParentEntry = row.entry.parent

            if (newParentEntry != oldParentEntry) {
                // If the parent is null, then we should remove the child completely. If not, then
                // the parent merely changed: we'll detach it for now and then attach it to the
                // new parent in step 2.
                val isTransfer = newParentEntry != null
                if (!isTransfer) {
                    toRemove.add(row.entry)
                }

                if (!isTransfer && !isAttachedToRootEntry(oldParentEntry)) {
                    // If our view parent has also been removed (i.e. is no longer attached to the
                    // root entry) then we skip removing the child here
                    logger.logSkippingDetach(row.entry.key, row.nodeParent?.entry?.key)
                } else {
                    logger.logDetachingChild(
                            row.entry.key,
                            isTransfer,
                            oldParentEntry?.key,
                            newParentEntry?.key)
                    row.nodeParent?.removeChild(row, isTransfer)
                    row.nodeParent = null
                }
            }
        }
        rows.keys.removeAll(toRemove)
    }

    private fun addAndReorderChildren(parent: ParentNode, childEntries: List<ListEntry>): Boolean {
        var orderChanged = false
        for ((index, entry) in childEntries.withIndex()) {
            val row = getRowNode(entry)
            val currView = parent.getChildViewAt(index)
            if (currView != row.view) {
                when (row.nodeParent) {
                    null -> {
                        logger.logAttachingChild(row.entry.key, parent.entry.key)
                        parent.addChildAt(row, index)
                        row.nodeParent = parent
                    }
                    parent -> {
                        logger.logMovingChild(row.entry.key, parent.entry.key, index)
                        parent.moveChild(row, index)
                        orderChanged = true
                    }
                    else -> {
                        throw IllegalStateException("Child ${row.entry.key} should have parent " +
                                "${parent.entry.key} but is actually " +
                                "${row.nodeParent?.entry?.key}")
                    }
                }
            }
            if (row is GroupWrapper) {
                val childOrderChanged = addAndReorderChildren(row, row.entry.children)
                orderChanged = orderChanged || childOrderChanged
            }
        }
        // TODO: setUntruncatedChildCount

        return orderChanged
    }

    private fun getRowNode(entry: ListEntry): RowNode {
        return rows.getOrPut(entry) {
            when (entry) {
                is NotificationEntry -> RowWrapper(entry, viewBarn.requireView(entry))
                is GroupEntry ->
                    GroupWrapper(
                            entry,
                            viewBarn.requireView(checkNotNull(entry.summary)),
                            listContainer)
                else -> throw RuntimeException(
                        "Unexpected entry type for ${entry.key}: ${entry.javaClass}")
            }
        }
    }
}

class NotifViewManagerBuilder @Inject constructor(
    private val viewBarn: NotifViewBarn,
    private val logger: NotifViewManagerLogger
) {
    fun build(listContainer: NotificationListContainer): NotifViewManager {
        return NotifViewManager(listContainer, viewBarn, logger)
    }
}

private fun isAttachedToRootEntry(entry: ListEntry?): Boolean {
    return when (entry) {
        null -> false
        ROOT_ENTRY -> true
        else -> isAttachedToRootEntry(entry.parent)
    }
}

private interface Node {
    val entry: ListEntry
    val nodeParent: ParentNode?
}

private interface ParentNode : Node {
    fun getChildViewAt(index: Int): View?
    fun addChildAt(child: RowNode, index: Int)
    fun moveChild(child: RowNode, index: Int)
    fun removeChild(child: RowNode, isTransfer: Boolean)
}

private interface RowNode : Node {
    val view: ExpandableNotificationRow
    override var nodeParent: ParentNode?
}

private class RootWrapper(
    private val listContainer: NotificationListContainer
) : ParentNode {
    override val entry: ListEntry = ROOT_ENTRY
    override val nodeParent: ParentNode? = null

    override fun getChildViewAt(index: Int): View? {
        return listContainer.getContainerChildAt(index)
    }

    override fun addChildAt(child: RowNode, index: Int) {
        listContainer.addContainerViewAt(child.view, index)
    }

    override fun moveChild(child: RowNode, index: Int) {
        listContainer.changeViewPosition(child.view, index)
    }

    override fun removeChild(child: RowNode, isTransfer: Boolean) {
        if (isTransfer) {
            listContainer.setChildTransferInProgress(true)
        }
        listContainer.removeContainerView(child.view)
        if (isTransfer) {
            listContainer.setChildTransferInProgress(false)
        }
    }
}

private class GroupWrapper(
    override val entry: GroupEntry,
    override val view: ExpandableNotificationRow,
    val listContainer: NotificationListContainer
) : RowNode, ParentNode {

    override var nodeParent: ParentNode? = null

    override fun getChildViewAt(index: Int): View? {
        return view.getChildNotificationAt(index)
    }

    override fun addChildAt(child: RowNode, index: Int) {
        view.addChildNotification(child.view, index)
        listContainer.notifyGroupChildAdded(child.view)
    }

    override fun moveChild(child: RowNode, index: Int) {
        view.removeChildNotification(child.view)
        view.addChildNotification(child.view, index)
    }

    override fun removeChild(child: RowNode, isTransfer: Boolean) {
        view.removeChildNotification(child.view)
        if (isTransfer) {
            listContainer.notifyGroupChildRemoved(child.view, view)
        }
    }
}

private class RowWrapper(
    override val entry: NotificationEntry,
    override val view: ExpandableNotificationRow
) : RowNode {
    override var nodeParent: ParentNode? = null
}
