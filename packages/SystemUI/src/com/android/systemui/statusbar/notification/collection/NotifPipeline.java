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

package com.android.systemui.statusbar.notification.collection;

import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.SectionsProvider;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

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
 *   screen and notifications for other users. So participate, see
 *   {@link #addPreGroupFilter} and similar methods.
 * - Grouped: Notifications that are part of the same group are clustered together into a single
 *   GroupEntry. These groups are then transformed in order to remove children or completely split
 *   them apart. To participate, see {@link #addPromoter}.
 * - Sorted: All top-level notifications are sorted. To participate, see
 *   {@link #setSectionsProvider} and {@link #setComparators}
 *
 * The exact order of all hooks is as follows:
 *  0. Collection listeners are fired ({@link #addCollectionListener}).
 *  1. Pre-group filters are fired on each notification ({@link #addPreGroupFilter}).
 *  2. Initial grouping is performed (NotificationEntries will have their parents set
 *     appropriately).
 *  3. OnBeforeTransformGroupListeners are fired ({@link #addOnBeforeTransformGroupsListener})
 *  4. NotifPromoters are called on each notification with a parent ({@link #addPromoter})
 *  5. OnBeforeSortListeners are fired ({@link #addOnBeforeSortListener})
 *  6. SectionsProvider is called on each top-level entry in the list ({@link #setSectionsProvider})
 *  7. Top-level entries within the same section are sorted by NotifComparators
 *     ({@link #setComparators})
 *  8. Pre-render filters are fired on each notification ({@link #addPreRenderFilter})
 *  9. OnBeforeRenderListListeners are fired ({@link #addOnBeforeRenderListListener})
 *  9. The list is handed off to the view layer to be rendered
 */
@Singleton
public class NotifPipeline {
    private final NotifCollection mNotifCollection;
    private final NotifListBuilderImpl mNotifListBuilder;

    @Inject
    public NotifPipeline(
            NotifCollection notifCollection,
            NotifListBuilderImpl notifListBuilder) {
        mNotifCollection = notifCollection;
        mNotifListBuilder = notifListBuilder;
    }

    /**
     * Returns the list of "active" notifications, i.e. the notifications that are currently posted
     * to the phone. In general, this tracks closely to the list maintained by NotificationManager,
     * but it can diverge slightly due to lifetime extenders.
     *
     * The returned collection is read-only, unsorted, unfiltered, and ungrouped.
     */
    public Collection<NotificationEntry> getActiveNotifs() {
        return mNotifCollection.getActiveNotifs();
    }

    /**
     * Registers a listener to be informed when notifications are added, removed or updated.
     */
    public void addCollectionListener(NotifCollectionListener listener) {
        mNotifCollection.addCollectionListener(listener);
    }

    /**
     * Registers a lifetime extender. Lifetime extenders can cause notifications that have been
     * dismissed or retracted to be temporarily retained in the collection.
     */
    public void addNotificationLifetimeExtender(NotifLifetimeExtender extender) {
        mNotifCollection.addNotificationLifetimeExtender(extender);
    }

    /**
     * Registers a filter with the pipeline before grouping, promoting and sorting occurs. Filters
     * are called on each notification in the order that they were registered. If any filter
     * returns true, the notification is removed from the pipeline (and no other filters are
     * called on that notif).
     */
    public void addPreGroupFilter(NotifFilter filter) {
        mNotifListBuilder.addPreGroupFilter(filter);
    }

    /**
     * Called after notifications have been filtered and after the initial grouping has been
     * performed but before NotifPromoters have had a chance to promote children out of groups.
     */
    public void addOnBeforeTransformGroupsListener(OnBeforeTransformGroupsListener listener) {
        mNotifListBuilder.addOnBeforeTransformGroupsListener(listener);
    }

    /**
     * Registers a promoter with the pipeline. Promoters are able to promote child notifications to
     * top-level, i.e. move a notification that would be a child of a group and make it appear
     * ungrouped. Promoters are called on each child notification in the order that they are
     * registered. If any promoter returns true, the notification is removed from the group (and no
     * other promoters are called on it).
     */
    public void addPromoter(NotifPromoter promoter) {
        mNotifListBuilder.addPromoter(promoter);
    }

    /**
     * Called after notifs have been filtered and groups have been determined but before sections
     * have been determined or the notifs have been sorted.
     */
    public void addOnBeforeSortListener(OnBeforeSortListener listener) {
        mNotifListBuilder.addOnBeforeSortListener(listener);
    }

    /**
     * Assigns sections to each top-level entry, where a section is simply an integer. Sections are
     * the primary metric by which top-level entries are sorted; NotifComparators are only consulted
     * when two entries are in the same section. The pipeline doesn't assign any particular meaning
     * to section IDs -- from it's perspective they're just numbers and it sorts them by a simple
     * numerical comparison.
     */
    public void setSectionsProvider(SectionsProvider provider) {
        mNotifListBuilder.setSectionsProvider(provider);
    }

    /**
     * Comparators that are used to sort top-level entries that share the same section. The
     * comparators are executed in order until one of them returns a non-zero result. If all return
     * zero, the pipeline falls back to sorting by rank (and, failing that, Notification.when).
     */
    public void setComparators(List<NotifComparator> comparators) {
        mNotifListBuilder.setComparators(comparators);
    }

    /**
     * Registers a filter with the pipeline to filter right before rendering the list (after
     * pre-group filtering, grouping, promoting and sorting occurs). Filters are
     * called on each notification in the order that they were registered. If any filter returns
     * true, the notification is removed from the pipeline (and no other filters are called on that
     * notif).
     */
    public void addPreRenderFilter(NotifFilter filter) {
        mNotifListBuilder.addPreRenderFilter(filter);
    }

    /**
     * Called at the end of the pipeline after the notif list has been finalized but before it has
     * been handed off to the view layer.
     */
    public void addOnBeforeRenderListListener(OnBeforeRenderListListener listener) {
        mNotifListBuilder.addOnBeforeRenderListListener(listener);
    }

    /**
     * Returns a read-only view in to the current shade list, i.e. the list of notifications that
     * are currently present in the shade. If this method is called during pipeline execution it
     * will return the current state of the list, which will likely be only partially-generated.
     */
    public List<ListEntry> getShadeList() {
        return mNotifListBuilder.getShadeList();
    }
}
