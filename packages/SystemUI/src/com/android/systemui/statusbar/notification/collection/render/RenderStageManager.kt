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

package com.android.systemui.statusbar.notification.collection.render

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineDumpable
import com.android.systemui.statusbar.notification.collection.PipelineDumper
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderEntryListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderGroupListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
import com.android.systemui.util.traceSection
import javax.inject.Inject

/**
 * The class which is part of the pipeline which guarantees a consistent that coordinators get a
 * consistent interface to the view system regardless of the [NotifViewRenderer] implementation
 * provided to [setViewRenderer].
 */
@SysUISingleton
class RenderStageManager @Inject constructor() : PipelineDumpable {
    private val onAfterRenderListListeners = mutableListOf<OnAfterRenderListListener>()
    private val onAfterRenderGroupListeners = mutableListOf<OnAfterRenderGroupListener>()
    private val onAfterRenderEntryListeners = mutableListOf<OnAfterRenderEntryListener>()
    private var viewRenderer: NotifViewRenderer? = null

    /** Attach this stage to the rest of the pipeline */
    fun attach(listBuilder: ShadeListBuilder) {
        listBuilder.setOnRenderListListener(::onRenderList)
    }

    private fun onRenderList(notifList: List<ListEntry>) {
        traceSection("RenderStageManager.onRenderList") {
            val viewRenderer = viewRenderer ?: return
            viewRenderer.onRenderList(notifList)
            dispatchOnAfterRenderList(viewRenderer, notifList)
            dispatchOnAfterRenderGroups(viewRenderer, notifList)
            dispatchOnAfterRenderEntries(viewRenderer, notifList)
            viewRenderer.onDispatchComplete()
        }
    }

    /** Provides this class with the view rendering implementation. */
    fun setViewRenderer(renderer: NotifViewRenderer) {
        viewRenderer = renderer
    }

    /** Adds a listener that will get a single callback after rendering the list. */
    fun addOnAfterRenderListListener(listener: OnAfterRenderListListener) {
        onAfterRenderListListeners.add(listener)
    }

    /** Adds a listener that will get a callback for each group rendered. */
    fun addOnAfterRenderGroupListener(listener: OnAfterRenderGroupListener) {
        onAfterRenderGroupListeners.add(listener)
    }

    /** Adds a listener that will get a callback for each entry rendered. */
    fun addOnAfterRenderEntryListener(listener: OnAfterRenderEntryListener) {
        onAfterRenderEntryListeners.add(listener)
    }

    override fun dumpPipeline(d: PipelineDumper) = with(d) {
        dump("viewRenderer", viewRenderer)
        dump("onAfterRenderListListeners", onAfterRenderListListeners)
        dump("onAfterRenderGroupListeners", onAfterRenderGroupListeners)
        dump("onAfterRenderEntryListeners", onAfterRenderEntryListeners)
    }

    private fun dispatchOnAfterRenderList(
        viewRenderer: NotifViewRenderer,
        entries: List<ListEntry>
    ) {
        traceSection("RenderStageManager.dispatchOnAfterRenderList") {
            val stackController = viewRenderer.getStackController()
            onAfterRenderListListeners.forEach { listener ->
                listener.onAfterRenderList(entries, stackController)
            }
        }
    }

    private fun dispatchOnAfterRenderGroups(
        viewRenderer: NotifViewRenderer,
        entries: List<ListEntry>
    ) {
        traceSection("RenderStageManager.dispatchOnAfterRenderGroups") {
            if (onAfterRenderGroupListeners.isEmpty()) {
                return
            }
            entries.asSequence().filterIsInstance<GroupEntry>().forEach { group ->
                val controller = viewRenderer.getGroupController(group)
                onAfterRenderGroupListeners.forEach { listener ->
                    listener.onAfterRenderGroup(group, controller)
                }
            }
        }
    }

    private fun dispatchOnAfterRenderEntries(
        viewRenderer: NotifViewRenderer,
        entries: List<ListEntry>
    ) {
        traceSection("RenderStageManager.dispatchOnAfterRenderEntries") {
            if (onAfterRenderEntryListeners.isEmpty()) {
                return
            }
            entries.forEachNotificationEntry { entry ->
                val controller = viewRenderer.getRowController(entry)
                onAfterRenderEntryListeners.forEach { listener ->
                    listener.onAfterRenderEntry(entry, controller)
                }
            }
        }
    }

    /**
     * Performs a forward, depth-first traversal of the list where the group's summary
     * immediately precedes the group's children.
     */
    private inline fun List<ListEntry>.forEachNotificationEntry(
        action: (NotificationEntry) -> Unit
    ) {
        forEach { entry ->
            when (entry) {
                is NotificationEntry -> action(entry)
                is GroupEntry -> {
                    action(entry.requireSummary)
                    entry.children.forEach(action)
                }
                else -> error("Unhandled entry: $entry")
            }
        }
    }
}
