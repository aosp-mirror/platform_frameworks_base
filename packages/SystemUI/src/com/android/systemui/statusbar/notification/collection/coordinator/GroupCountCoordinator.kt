package com.android.systemui.statusbar.notification.collection.coordinator

import android.util.ArrayMap
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.render.NotifGroupController
import javax.inject.Inject

/** A small coordinator which calculates, stores, and applies the untruncated child count. */
@CoordinatorScope
class GroupCountCoordinator @Inject constructor() : Coordinator {
    private val untruncatedChildCounts = ArrayMap<GroupEntry, Int>()

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnBeforeFinalizeFilterListener(::onBeforeFinalizeFilter)
        pipeline.addOnAfterRenderGroupListener(::onAfterRenderGroup)
    }

    private fun onBeforeFinalizeFilter(entries: List<ListEntry>) {
        // save untruncated child counts to our internal map
        untruncatedChildCounts.clear()
        entries.asSequence().filterIsInstance<GroupEntry>().forEach { groupEntry ->
            untruncatedChildCounts[groupEntry] = groupEntry.children.size
        }
    }

    private fun onAfterRenderGroup(group: GroupEntry, controller: NotifGroupController) {
        // find the untruncated child count for a group and apply it to the controller
        val count = untruncatedChildCounts[group]
        checkNotNull(count) { "No untruncated child count for group: ${group.key}" }
        controller.setUntruncatedChildCount(count)
    }
}