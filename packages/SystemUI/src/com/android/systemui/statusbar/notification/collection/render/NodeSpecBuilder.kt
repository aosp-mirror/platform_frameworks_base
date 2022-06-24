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

import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.util.Compile
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
    private val mediaContainerController: MediaContainerController,
    private val sectionsFeatureManager: NotificationSectionsFeatureManager,
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    private val viewBarn: NotifViewBarn,
    private val logger: NodeSpecBuilderLogger
) {
    private var lastSections = setOf<NotifSection?>()

    fun buildNodeSpec(
        rootController: NodeController,
        notifList: List<ListEntry>
    ): NodeSpec = traceSection("NodeSpecBuilder.buildNodeSpec") {
        val root = NodeSpecImpl(null, rootController)

        // The media container should be added as the first child of the root node
        // TODO: Perhaps the node spec building process should be more of a pipeline of its own?
        if (sectionsFeatureManager.isMediaControlsEnabled()) {
            root.children.add(NodeSpecImpl(root, mediaContainerController))
        }

        var currentSection: NotifSection? = null
        val prevSections = mutableSetOf<NotifSection?>()
        val showHeaders = sectionHeaderVisibilityProvider.sectionHeadersVisible
        val sectionOrder = mutableListOf<NotifSection?>()
        val sectionHeaders = mutableMapOf<NotifSection?, NodeController?>()
        val sectionCounts = mutableMapOf<NotifSection?, Int>()

        for (entry in notifList) {
            val section = entry.section!!
            if (prevSections.contains(section)) {
                throw java.lang.RuntimeException("Section ${section.label} has been duplicated")
            }

            // If this notif begins a new section, first add the section's header view
            if (section != currentSection) {
                if (section.headerController != currentSection?.headerController && showHeaders) {
                    section.headerController?.let { headerController ->
                        root.children.add(NodeSpecImpl(root, headerController))
                        if (Compile.IS_DEBUG) {
                            sectionHeaders[section] = headerController
                        }
                    }
                }
                prevSections.add(currentSection)
                currentSection = section
                if (Compile.IS_DEBUG) {
                    sectionOrder.add(section)
                }
            }

            // Finally, add the actual notif node!
            root.children.add(buildNotifNode(root, entry))
            if (Compile.IS_DEBUG) {
                sectionCounts[section] = sectionCounts.getOrDefault(section, 0) + 1
            }
        }

        if (Compile.IS_DEBUG) {
            logger.logBuildNodeSpec(lastSections, sectionHeaders, sectionCounts, sectionOrder)
            lastSections = sectionCounts.keys
        }

        return@traceSection root
    }

    private fun buildNotifNode(parent: NodeSpec, entry: ListEntry): NodeSpec = when (entry) {
        is NotificationEntry -> NodeSpecImpl(parent, viewBarn.requireNodeController(entry))
        is GroupEntry ->
            NodeSpecImpl(parent, viewBarn.requireNodeController(checkNotNull(entry.summary)))
                .apply { entry.children.forEach { children.add(buildNotifNode(this, it)) } }
        else -> throw RuntimeException("Unexpected entry: $entry")
    }
}
