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

package com.android.systemui.statusbar.notification.collection.listbuilder;

import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.SectionsProvider;

import java.util.List;

/**
 * The system that constructs the current "notification list", the list of notifications that are
 * currently being displayed to the user.
 *
 * The pipeline proceeds through a series of stages in order to produce the final list (see below).
 * Each stage exposes hooks and listeners for other code to participate.
 *
 * This list differs from the canonical one we receive from system server in a few ways:
 * - Filtered: Some notifications are filtered out. For example, we filter out notifications whose
 *   views haven't been inflated yet. We also filter out some notifications if we're on the lock
 *   screen. To participate, see {@link #addFilter(NotifFilter)}.
 * - Grouped: Notifications that are part of the same group are clustered together into a single
 *   GroupEntry. These groups are then transformed in order to remove children or completely split
 *   them apart. To participate, see {@link #addPromoter(NotifPromoter)}.
 * - Sorted: All top-level notifications are sorted. To participate, see
 *   {@link #setSectionsProvider(SectionsProvider)} and {@link #setComparators(List)}
 *
 * The exact order of all hooks is as follows:
 *  0. Collection listeners are fired (see {@link NotifCollection}).
 *  1. NotifFilters are called on each notification currently in NotifCollection.
 *  2. Initial grouping is performed (NotificationEntries will have their parents set
 *     appropriately).
 *  3. OnBeforeTransformGroupListeners are fired
 *  4. NotifPromoters are called on each notification with a parent
 *  5. OnBeforeSortListeners are fired
 *  6. SectionsProvider is called on each top-level entry in the list
 *  7. The top-level entries are sorted using the provided NotifComparators (plus some additional
 *     built-in logic).
 *  8. OnBeforeRenderListListeners are fired
 *  9. The list is handed off to the view layer to be rendered.
 */
public interface NotifListBuilder {

    /**
     * Registers a filter with the pipeline before grouping, promoting and sorting occurs. Filters
     * are called on each notification in the order that they were registered. If any filter
     * returns true, the notification is removed from the pipeline (and no other filters are
     * called on that notif).
     */
    void addPreGroupFilter(NotifFilter filter);

    /**
     * Registers a promoter with the pipeline. Promoters are able to promote child notifications to
     * top-level, i.e. move a notification that would be a child of a group and make it appear
     * ungrouped. Promoters are called on each child notification in the order that they are
     * registered. If any promoter returns true, the notification is removed from the group (and no
     * other promoters are called on it).
     */
    void addPromoter(NotifPromoter promoter);

    /**
     * Assigns sections to each top-level entry, where a section is simply an integer. Sections are
     * the primary metric by which top-level entries are sorted; NotifComparators are only consulted
     * when two entries are in the same section. The pipeline doesn't assign any particular meaning
     * to section IDs -- from it's perspective they're just numbers and it sorts them by a simple
     * numerical comparison.
     */
    void setSectionsProvider(SectionsProvider provider);

    /**
     * Comparators that are used to sort top-level entries that share the same section. The
     * comparators are executed in order until one of them returns a non-zero result. If all return
     * zero, the pipeline falls back to sorting by rank (and, failing that, Notification.when).
     */
    void setComparators(List<NotifComparator> comparators);

    /**
     * Registers a filter with the pipeline to filter right before rendering the list (after
     * pre-group filtering, grouping, promoting and sorting occurs). Filters are
     * called on each notification in the order that they were registered. If any filter returns
     * true, the notification is removed from the pipeline (and no other filters are called on that
     * notif).
     */
    void addPreRenderFilter(NotifFilter filter);

    /**
     * Called after notifications have been filtered and after the initial grouping has been
     * performed but before NotifPromoters have had a chance to promote children out of groups.
     */
    void addOnBeforeTransformGroupsListener(OnBeforeTransformGroupsListener listener);

    /**
     * Called after notifs have been filtered and groups have been determined but before sections
     * have been determined or the notifs have been sorted.
     */
    void addOnBeforeSortListener(OnBeforeSortListener listener);

    /**
     * Called at the end of the pipeline after the notif list has been finalized but before it has
     * been handed off to the view layer.
     */
    void addOnBeforeRenderListListener(OnBeforeRenderListListener listener);

    /**
     * Returns a read-only view in to the current notification list. If this method is called
     * during pipeline execution it will return the current state of the list, which will likely
     * be only partially-generated.
     */
    List<ListEntry> getActiveNotifs();
}
