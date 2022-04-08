/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection

import android.annotation.MainThread
import android.view.ViewGroup

import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.notification.VisualStabilityManager
import com.android.systemui.statusbar.notification.collection.GroupEntry.ROOT_ENTRY
import com.android.systemui.statusbar.notification.stack.NotificationListItem
import com.android.systemui.util.Assert

import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.IllegalStateException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A consumer of a Notification tree built by [ShadeListBuilder] which will update the notification
 * presenter with the minimum operations required to make the old tree match the new one
 */
@MainThread
@Singleton
class NotifViewManager @Inject constructor(
    private val rowRegistry: NotifViewBarn,
    private val stabilityManager: VisualStabilityManager,
    private val featureFlags: FeatureFlags
) {
    var currentNotifs = listOf<ListEntry>()

    private lateinit var listContainer: SimpleNotificationListContainer

    fun attach(listBuilder: ShadeListBuilder) {
        if (featureFlags.isNewNotifPipelineRenderingEnabled) {
            listBuilder.setOnRenderListListener { entries: List<ListEntry> ->
                this.onNotifTreeBuilt(entries)
            }
        }
    }

    fun setViewConsumer(consumer: SimpleNotificationListContainer) {
        listContainer = consumer
    }

    /**
     * Callback for when the tree is rebuilt
     */
    fun onNotifTreeBuilt(notifList: List<ListEntry>) {
        Assert.isMainThread()

        /*
         * The assumption here is that anything from the old NotificationViewHierarchyManager that
         * is responsible for filtering is done via the NotifFilter logic. This tree we get should
         * be *the stuff to display* +/- redacted stuff
         */

        detachRows(notifList)
        attachRows(notifList)

        currentNotifs = notifList
    }

    private fun detachRows(entries: List<ListEntry>) {
        // To properly detach rows, we are looking to remove any view in the consumer that is not
        // present in the incoming list.
        //
        // Every listItem was top-level, so it's entry's parent was ROOT_ENTRY, but now
        // there are two possibilities:
        //
        //      1. It is not present in the entry list
        //          1a. It has moved to be a child in the entry list - transfer it
        //          1b. It is gone completely - remove it
        //      2. It is present in the entry list - diff the children
        getListItems(listContainer)
                .filter {
                    // Ignore things that are showing the blocking helper
                    !it.isBlockingHelperShowing
                }
                .forEach { listItem ->
                    val noLongerTopLevel = listItem.entry.parent != ROOT_ENTRY
                    val becameChild = noLongerTopLevel && listItem.entry.parent != null

                    val idx = entries.indexOf(listItem.entry)

                    if (noLongerTopLevel) {
                        // Summaries won't become children; remove the whole group
                        if (listItem.isSummaryWithChildren) {
                            listItem.removeAllChildren()
                        }

                        if (becameChild) {
                            // Top-level element is becoming a child, don't generate an animation
                            listContainer.setChildTransferInProgress(true)
                        }
                        listContainer.removeListItem(listItem)
                        listContainer.setChildTransferInProgress(false)
                    } else if (entries[idx] is GroupEntry) {
                        // A top-level entry exists. If it's a group, diff the children
                        val groupChildren = (entries[idx] as GroupEntry).children
                        listItem.attachedChildren?.forEach { listChild ->
                            if (!groupChildren.contains(listChild.entry)) {
                                listItem.removeChildNotification(listChild)

                                // TODO: the old code only calls this if the notif is gone from
                                // NEM.getActiveNotificationUnfiltered(). Do we care?
                                listContainer.notifyGroupChildRemoved(
                                        listChild.view, listChild.view.parent as ViewGroup)
                            }
                        }
                    }
                }
    }

    /** Convenience method for getting a sequence of [NotificationListItem]s */
    private fun getListItems(container: SimpleNotificationListContainer):
            Sequence<NotificationListItem> {
        return (0 until container.getContainerChildCount()).asSequence()
                .map { container.getContainerChildAt(it) }
                .filterIsInstance<NotificationListItem>()
    }

    private fun attachRows(entries: List<ListEntry>) {

        var orderChanged = false

        // To attach rows we can use _this one weird trick_: if the intended view to add does not
        // have a parent, then simply add it (and its children).
        entries.forEach { entry ->
            // TODO: We should eventually map GroupEntry's themselves to views so that we don't
            // depend on representativeEntry here which may actually be null in the future
            val listItem = rowRegistry.requireView(entry.representativeEntry!!)

            if (listItem.view.parent == null) {
                listContainer.addListItem(listItem)
                stabilityManager.notifyViewAddition(listItem.view)
            }

            if (entry is GroupEntry) {
                for ((idx, childEntry) in entry.children.withIndex()) {
                    val childListItem = rowRegistry.requireView(childEntry)
                    // Child hasn't been added yet. add it!
                    if (listItem.attachedChildren == null ||
                            !listItem.attachedChildren.contains(childListItem)) {
                        // TODO: old code here just Log.wtf()'d here. This might wreak havoc
                        if (childListItem.view.parent != null) {
                            throw IllegalStateException("trying to add a notification child that " +
                                    "already has a parent. class: " +
                                    "${childListItem.view.parent?.javaClass} " +
                                    "\n child: ${childListItem.view}"
                            )
                        }

                        listItem.addChildNotification(childListItem, idx)
                        stabilityManager.notifyViewAddition(childListItem.view)
                        listContainer.notifyGroupChildAdded(childListItem.view)
                    }
                }

                // finally after removing and adding has been performed we can apply the order
                orderChanged = orderChanged ||
                        listItem.applyChildOrder(
                                getChildListFromParent(entry),
                                stabilityManager,
                                null /*TODO: stability callback */
                        )
                listItem.setUntruncatedChildCount(entry.untruncatedChildCount)
            }
        }

        if (orderChanged) {
            listContainer.generateChildOrderChangedEvent()
        }
    }

    private fun getChildListFromParent(parent: ListEntry): List<NotificationListItem> {
        if (parent is GroupEntry) {
            return parent.children.map { child -> rowRegistry.requireView(child) }
                    .toList()
        }

        return emptyList()
    }

    fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<String>) {
    }
}

private const val TAG = "NotifViewDataSource"