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
package com.android.systemui.statusbar.notification.collection

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.NotifPipelineFlags
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderEntryListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderGroupListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnAfterRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.InternalNotifUpdater
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.render.RenderStageManager
import javax.inject.Inject

/**
 * The system that constructs the "shade list", the filtered, grouped, and sorted list of
 * notifications that are currently being displayed to the user in the notification shade.
 *
 * The pipeline proceeds through a series of stages in order to produce the final list (see below).
 * Each stage exposes hooks and listeners to allow other code to participate.
 *
 * This list differs from the canonical one we receive from system server in a few ways:
 * - Filtered: Some notifications are filtered out. For example, we filter out notifications whose
 *   views haven't been inflated yet. We also filter out some notifications if we're on the lock
 *   screen and notifications for other users. To participate, see
 *   [.addPreGroupFilter] and similar methods.
 * - Grouped: Notifications that are part of the same group are clustered together into a single
 *   GroupEntry. These groups are then transformed in order to remove children or completely split
 *   them apart. To participate, see [.addPromoter].
 * - Sorted: All top-level notifications are sorted. To participate, see
 *   [.setSections] and [.setComparators]
 *
 * The exact order of all hooks is as follows:
 *  0. Collection listeners are fired ([.addCollectionListener]).
 *  1. Pre-group filters are fired on each notification ([.addPreGroupFilter]).
 *  2. Initial grouping is performed (NotificationEntries will have their parents set
 *     appropriately).
 *  3. OnBeforeTransformGroupListeners are fired ([.addOnBeforeTransformGroupsListener])
 *  4. NotifPromoters are called on each notification with a parent ([.addPromoter])
 *  5. OnBeforeSortListeners are fired ([.addOnBeforeSortListener])
 *  6. Top-level entries are assigned sections by NotifSections ([.setSections])
 *  7. Top-level entries within the same section are sorted by NotifComparators ([.setComparators])
 *  8. OnBeforeFinalizeFilterListeners are fired ([.addOnBeforeFinalizeFilterListener])
 *  9. Finalize filters are fired on each notification ([.addFinalizeFilter])
 *  10. OnBeforeRenderListListeners are fired ([.addOnBeforeRenderListListener])
 *  11. The list is handed off to the view layer to be rendered
 *  12. OnAfterRenderListListeners are fired ([.addOnAfterRenderListListener])
 *  13. OnAfterRenderGroupListeners are fired ([.addOnAfterRenderGroupListener])
 *  13. OnAfterRenderEntryListeners are fired ([.addOnAfterRenderEntryListener])
 */
@SysUISingleton
class NotifPipeline @Inject constructor(
    notifPipelineFlags: NotifPipelineFlags,
    private val mNotifCollection: NotifCollection,
    private val mShadeListBuilder: ShadeListBuilder,
    private val mRenderStageManager: RenderStageManager
) : CommonNotifCollection {
    /**
     * Returns the list of all known notifications, i.e. the notifications that are currently posted
     * to the phone. In general, this tracks closely to the list maintained by NotificationManager,
     * but it can diverge slightly due to lifetime extenders.
     *
     * The returned collection is read-only, unsorted, unfiltered, and ungrouped.
     */
    override fun getAllNotifs(): Collection<NotificationEntry> {
        return mNotifCollection.allNotifs
    }

    override fun addCollectionListener(listener: NotifCollectionListener) {
        mNotifCollection.addCollectionListener(listener)
    }

    override fun removeCollectionListener(listener: NotifCollectionListener) {
        mNotifCollection.removeCollectionListener(listener)
    }

    /**
     * Returns the NotificationEntry associated with [key].
     */
    override fun getEntry(key: String): NotificationEntry? {
        return mNotifCollection.getEntry(key)
    }

    val isNewPipelineEnabled: Boolean = notifPipelineFlags.isNewPipelineEnabled()

    /**
     * Registers a lifetime extender. Lifetime extenders can cause notifications that have been
     * dismissed or retracted by system server to be temporarily retained in the collection.
     */
    fun addNotificationLifetimeExtender(extender: NotifLifetimeExtender) {
        mNotifCollection.addNotificationLifetimeExtender(extender)
    }

    /**
     * Registers a dismiss interceptor. Dismiss interceptors can cause notifications that have been
     * dismissed by the user to be retained (won't send a dismissal to system server).
     */
    fun addNotificationDismissInterceptor(interceptor: NotifDismissInterceptor) {
        mNotifCollection.addNotificationDismissInterceptor(interceptor)
    }

    /**
     * Registers a filter with the pipeline before grouping, promoting and sorting occurs. Filters
     * are called on each notification in the order that they were registered. If any filter
     * returns true, the notification is removed from the pipeline (and no other filters are
     * called on that notif).
     */
    fun addPreGroupFilter(filter: NotifFilter) {
        mShadeListBuilder.addPreGroupFilter(filter)
    }

    /**
     * Called after notifications have been filtered and after the initial grouping has been
     * performed but before NotifPromoters have had a chance to promote children out of groups.
     */
    fun addOnBeforeTransformGroupsListener(listener: OnBeforeTransformGroupsListener) {
        mShadeListBuilder.addOnBeforeTransformGroupsListener(listener)
    }

    /**
     * Registers a promoter with the pipeline. Promoters are able to promote child notifications to
     * top-level, i.e. move a notification that would be a child of a group and make it appear
     * ungrouped. Promoters are called on each child notification in the order that they are
     * registered. If any promoter returns true, the notification is removed from the group (and no
     * other promoters are called on it).
     */
    fun addPromoter(promoter: NotifPromoter) {
        mShadeListBuilder.addPromoter(promoter)
    }

    /**
     * Called after notifs have been filtered and groups have been determined but before sections
     * have been determined or the notifs have been sorted.
     */
    fun addOnBeforeSortListener(listener: OnBeforeSortListener) {
        mShadeListBuilder.addOnBeforeSortListener(listener)
    }

    /**
     * Sections that are used to sort top-level entries.  If two entries have the same section,
     * NotifComparators are consulted. Sections from this list are called in order for each
     * notification passed through the pipeline. The first NotifSection to return true for
     * [NotifSectioner.isInSection] sets the entry as part of its Section.
     */
    fun setSections(sections: List<NotifSectioner>) {
        mShadeListBuilder.setSectioners(sections)
    }

    /**
     * StabilityManager that is used to determine whether to suppress group and section changes.
     * This should only be set once.
     */
    fun setVisualStabilityManager(notifStabilityManager: NotifStabilityManager) {
        mShadeListBuilder.setNotifStabilityManager(notifStabilityManager)
    }

    /**
     * Comparators that are used to sort top-level entries that share the same section. The
     * comparators are executed in order until one of them returns a non-zero result. If all return
     * zero, the pipeline falls back to sorting by rank (and, failing that, Notification.when).
     */
    fun setComparators(comparators: List<NotifComparator>) {
        mShadeListBuilder.setComparators(comparators)
    }

    /**
     * Called after notifs have been filtered once, grouped, and sorted but before the final
     * filtering.
     */
    fun addOnBeforeFinalizeFilterListener(listener: OnBeforeFinalizeFilterListener) {
        mShadeListBuilder.addOnBeforeFinalizeFilterListener(listener)
    }

    /**
     * Registers a filter with the pipeline to filter right before rendering the list (after
     * pre-group filtering, grouping, promoting and sorting occurs). Filters are
     * called on each notification in the order that they were registered. If any filter returns
     * true, the notification is removed from the pipeline (and no other filters are called on that
     * notif).
     */
    fun addFinalizeFilter(filter: NotifFilter) {
        mShadeListBuilder.addFinalizeFilter(filter)
    }

    /**
     * Called at the end of the pipeline after the notif list has been finalized but before it has
     * been handed off to the view layer.
     */
    fun addOnBeforeRenderListListener(listener: OnBeforeRenderListListener) {
        mShadeListBuilder.addOnBeforeRenderListListener(listener)
    }

    /** Registers an invalidator that can be used to invalidate the entire notif list. */
    fun addPreRenderInvalidator(invalidator: Invalidator) {
        mShadeListBuilder.addPreRenderInvalidator(invalidator)
    }

    /**
     * Called at the end of the pipeline after the notif list has been handed off to the view layer.
     */
    fun addOnAfterRenderListListener(listener: OnAfterRenderListListener) {
        mRenderStageManager.addOnAfterRenderListListener(listener)
    }

    /**
     * Called at the end of the pipeline after a group has been handed off to the view layer.
     */
    fun addOnAfterRenderGroupListener(listener: OnAfterRenderGroupListener) {
        mRenderStageManager.addOnAfterRenderGroupListener(listener)
    }

    /**
     * Called at the end of the pipeline after an entry has been handed off to the view layer.
     * This will be called for every top level entry, every group summary, and every group child.
     */
    fun addOnAfterRenderEntryListener(listener: OnAfterRenderEntryListener) {
        mRenderStageManager.addOnAfterRenderEntryListener(listener)
    }

    /**
     * Get an object which can be used to update a notification (internally to the pipeline)
     * in response to a user action.
     *
     * @param name the name of the component that will update notifiations
     * @return an updater
     */
    fun getInternalNotifUpdater(name: String?): InternalNotifUpdater {
        return mNotifCollection.getInternalNotifUpdater(name)
    }
}