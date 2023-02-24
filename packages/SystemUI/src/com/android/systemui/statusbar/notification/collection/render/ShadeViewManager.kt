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
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.PipelineDumpable
import com.android.systemui.statusbar.notification.collection.PipelineDumper
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.util.traceSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * Responsible for building and applying the "shade node spec": the list (tree) of things that
 * currently populate the notification shade.
 */
class ShadeViewManager @AssistedInject constructor(
    context: Context,
    @Assisted listContainer: NotificationListContainer,
    @Assisted private val stackController: NotifStackController,
    mediaContainerController: MediaContainerController,
    featureManager: NotificationSectionsFeatureManager,
    sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    nodeSpecBuilderLogger: NodeSpecBuilderLogger,
    shadeViewDifferLogger: ShadeViewDifferLogger,
    private val viewBarn: NotifViewBarn
) : PipelineDumpable {
    // We pass a shim view here because the listContainer may not actually have a view associated
    // with it and the differ never actually cares about the root node's view.
    private val rootController = RootNodeController(listContainer, View(context))
    private val specBuilder = NodeSpecBuilder(mediaContainerController, featureManager,
        sectionHeaderVisibilityProvider, viewBarn, nodeSpecBuilderLogger)
    private val viewDiffer = ShadeViewDiffer(rootController, shadeViewDifferLogger)

    /** Method for attaching this manager to the pipeline. */
    fun attach(renderStageManager: RenderStageManager) {
        renderStageManager.setViewRenderer(viewRenderer)
    }

    override fun dumpPipeline(d: PipelineDumper) = with(d) {
        dump("rootController", rootController)
        dump("specBuilder", specBuilder)
        dump("viewDiffer", viewDiffer)
    }

    private val viewRenderer = object : NotifViewRenderer {

        override fun onRenderList(notifList: List<ListEntry>) {
            traceSection("ShadeViewManager.onRenderList") {
                viewDiffer.applySpec(specBuilder.buildNodeSpec(rootController, notifList))
            }
        }

        override fun getStackController(): NotifStackController = stackController

        override fun getGroupController(group: GroupEntry): NotifGroupController =
            viewBarn.requireGroupController(group.requireSummary)

        override fun getRowController(entry: NotificationEntry): NotifRowController =
            viewBarn.requireRowController(entry)
    }
}

@AssistedFactory
interface ShadeViewManagerFactory {
    fun create(
        listContainer: NotificationListContainer,
        stackController: NotifStackController
    ): ShadeViewManager
}
