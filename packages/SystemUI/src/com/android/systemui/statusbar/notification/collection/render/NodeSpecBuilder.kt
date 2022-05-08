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

import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.util.traceSection

/**
 * Converts a notif list (the output of the ShadeListBuilder) into a NodeSpec, an abstract
 * representation of which views should be present in the shade. This spec will later be consumed
 * by the ViewDiffer, which will add and remove views until the shade matches the spec. Up until
 * this point, the pipeline has dealt with pure data representations of notifications (in the
 * form of NotificationEntries). In this step, NotificationEntries finally become associated with
 * the views that will represent them. In addition, we add in any non-notification views that also
 * need to present in the shade, notably the section headers.
 */
class NodeSpecBuilder(
    private val viewBarn: NotifViewBarn
) {
    fun buildNodeSpec(
        rootController: NodeController,
        notifList: List<ListEntry>
    ): NodeSpec = traceSection("NodeSpecBuilder.buildNodeSpec") {
        val root = NodeSpecImpl(null, rootController)
        var currentSection: NotifSection? = null
        val prevSections = mutableSetOf<NotifSection?>()

        for (entry in notifList) {
            val section = entry.section!!

            if (prevSections.contains(section)) {
                throw java.lang.RuntimeException("Section ${section.label} has been duplicated")
            }

            // If this notif begins a new section, first add the section's header view
            if (section != currentSection) {
                section.headerController?.let { headerController ->
                    root.children.add(NodeSpecImpl(root, headerController))
                }
                prevSections.add(currentSection)
                currentSection = section
            }

            // Finally, add the actual notif node!
            root.children.add(buildNotifNode(root, entry))
        }

        return root
    }

    private fun buildNotifNode(parent: NodeSpec, entry: ListEntry): NodeSpec = when (entry) {
        is NotificationEntry -> NodeSpecImpl(parent, viewBarn.requireView(entry))
        is GroupEntry -> NodeSpecImpl(parent, viewBarn.requireView(checkNotNull(entry.summary)))
                .apply { entry.children.forEach { children.add(buildNotifNode(this, it)) } }
        else -> throw RuntimeException("Unexpected entry: $entry")
    }
}
