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

import android.view.View
import com.android.systemui.statusbar.notification.collection.PipelineDumpable
import com.android.systemui.statusbar.notification.collection.PipelineDumper
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.NotificationListContainer

/**
 * Temporary wrapper around [NotificationListContainer], for use by [ShadeViewDiffer]. Long term,
 * we should just modify NLC to implement the NodeController interface.
 */
class RootNodeController(
    private val listContainer: NotificationListContainer,
    override val view: View
) : NodeController, PipelineDumpable {
    override val nodeLabel: String = "<root>"

    override fun getChildAt(index: Int): View? {
        return listContainer.getContainerChildAt(index)
    }

    override fun getChildCount(): Int {
        return listContainer.containerChildCount
    }

    override fun addChildAt(child: NodeController, index: Int) {
        listContainer.addContainerViewAt(child.view, index)
        listContainer.onNotificationViewUpdateFinished()
        (child.view as? ExpandableNotificationRow)?.isChangingPosition = false
    }

    override fun moveChildTo(child: NodeController, index: Int) {
        listContainer.changeViewPosition(child.view as ExpandableView, index)
    }

    override fun removeChild(child: NodeController, isTransfer: Boolean) {
        if (isTransfer) {
            listContainer.setChildTransferInProgress(true)
            (child.view as? ExpandableNotificationRow)?.isChangingPosition = true
        }
        listContainer.removeContainerView(child.view)
        if (isTransfer) {
            listContainer.setChildTransferInProgress(false)
        }
    }

    override fun dumpPipeline(d: PipelineDumper) = with(d) {
        dump("listContainer", listContainer)
    }
}
