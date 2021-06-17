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

import androidx.annotation.Nullable;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;

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
 *   {@link #setSections} and {@link #setComparators}
 *
 * The exact order of all hooks is as follows:
 *  0. Collection listeners are fired ({@link #addCollectionListener}).
 *  1. Pre-group filters are fired on each notification ({@link #addPreGroupFilter}).
 *  2. Initial grouping is performed (NotificationEntries will have their parents set
 *     appropriately).
 *  3. OnBeforeTransformGroupListeners are fired ({@link #addOnBeforeTransformGroupsListener})
 *  4. NotifPromoters are called on each notification with a parent ({@link #addPromoter})
 *  5. OnBeforeSortListeners are fired ({@link #addOnBeforeSortListener})
 *  6. Top-level entries are assigned sections by NotifSections ({@link #setSections})
 *  7. Top-level entries within the same section are sorted by NotifComparators
 *     ({@link #setComparators})
 *  8. Finalize filters are fired on each notification ({@link #addFinalizeFilter})
 *  9. OnBeforeRenderListListeners are fired ({@link #addOnBeforeRenderListListener})
 *  9. The list is handed off to the view layer to be rendered
 */
@SysUISingleton
public class NotifPipeline implements CommonNotifCollection {
    private final NotifCollection mNotifCollection;
    private final ShadeListBuilder mShadeListBuilder;

    @Inject
    public NotifPipeline(
            NotifCollection notifCollection,
            ShadeListBuilder shadeListBuilder) {
        mNotifCollection = notifCollection;
        mShadeListBuilder = shadeListBuilder;
    }

    /**
     * Returns the list of all known notifications, i.e. the notifications that are currently posted
     * to the phone. In general, this tracks closely to the list maintained by NotificationManager,
     * but it can diverge slightly due to lifetime extenders.
     *
     * The returned collection is read-only, unsorted, unfiltered, and ungrouped.
     */
    @Override
    public Collection<NotificationEntry> getAllNotifs() {
        return mNotifCollection.getAllNotifs();
    }

    @Override
    public void addCollectionListener(NotifCollectionListener listener) {
        mNotifCollection.addCollectionListener(listener);
    }

    /**
     * Returns the NotificationEntry associated with [key].
     */
    @Nullable
    public NotificationEntry getEntry(String key) {
        return mNotifCollection.getEntry(key);
    }

    /**
     * Registers a lifetime extender. Lifetime extenders can cause notifications that have been
     * dismissed or retracted by system server to be temporarily retained in the collection.
     */
    public void addNotificationLifetimeExtender(NotifLifetimeExtender extender) {
        mNotifCollection.addNotificationLifetimeExtender(extender);
    }

    /**
     * Registers a dismiss interceptor. Dismiss interceptors can cause notifications that have been
     * dismissed by the user to be retained (won't send a dismissal to system server).
     */
    public void addNotificationDismissInterceptor(NotifDismissInterceptor interceptor) {
        mNotifCollection.addNotificationDismissInterceptor(interceptor);
    }

    /**
     * Registers a filter with the pipeline before grouping, promoting and sorting occurs. Filters
     * are called on each notification in the order that they were registered. If any filter
     * returns true, the notification is removed from the pipeline (and no other filters are
     * called on that notif).
     */
    public void addPreGroupFilter(NotifFilter filter) {
        mShadeListBuilder.addPreGroupFilter(filter);
    }

    /**
     * Called after notifications have been filtered and after the initial grouping has been
     * performed but before NotifPromoters have had a chance to promote children out of groups.
     */
    public void addOnBeforeTransformGroupsListener(OnBeforeTransformGroupsListener listener) {
        mShadeListBuilder.addOnBeforeTransformGroupsListener(listener);
    }

    /**
     * Registers a promoter with the pipeline. Promoters are able to promote child notifications to
     * top-level, i.e. move a notification that would be a child of a group and make it appear
     * ungrouped. Promoters are called on each child notification in the order that they are
     * registered. If any promoter returns true, the notification is removed from the group (and no
     * other promoters are called on it).
     */
    public void addPromoter(NotifPromoter promoter) {
        mShadeListBuilder.addPromoter(promoter);
    }

    /**
     * Called after notifs have been filtered and groups have been determined but before sections
     * have been determined or the notifs have been sorted.
     */
    public void addOnBeforeSortListener(OnBeforeSortListener listener) {
        mShadeListBuilder.addOnBeforeSortListener(listener);
    }

    /**
     * Sections that are used to sort top-level entries.  If two entries have the same section,
     * NotifComparators are consulted. Sections from this list are called in order for each
     * notification passed through the pipeline. The first NotifSection to return true for
     * {@link NotifSectioner#isInSection(ListEntry)} sets the entry as part of its Section.
     */
    public void setSections(List<NotifSectioner> sections) {
        mShadeListBuilder.setSectioners(sections);
    }

    /**
     * StabilityManager that is used to determine whether to suppress group and section changes.
     * This should only be set once.
     */
    public void setVisualStabilityManager(NotifStabilityManager notifStabilityManager) {
        mShadeListBuilder.setNotifStabilityManager(notifStabilityManager);
    }

    /**
     * Comparators that are used to sort top-level entries that share the same section. The
     * comparators are executed in order until one of them returns a non-zero result. If all return
     * zero, the pipeline falls back to sorting by rank (and, failing that, Notification.when).
     */
    public void setComparators(List<NotifComparator> comparators) {
        mShadeListBuilder.setComparators(comparators);
    }

    /**
     * Called after notifs have been filtered once, grouped, and sorted but before the final
     * filtering.
     */
    public void addOnBeforeFinalizeFilterListener(OnBeforeFinalizeFilterListener listener) {
        mShadeListBuilder.addOnBeforeFinalizeFilterListener(listener);
    }

    /**
     * Registers a filter with the pipeline to filter right before rendering the list (after
     * pre-group filtering, grouping, promoting and sorting occurs). Filters are
     * called on each notification in the order that they were registered. If any filter returns
     * true, the notification is removed from the pipeline (and no other filters are called on that
     * notif).
     */
    public void addFinalizeFilter(NotifFilter filter) {
        mShadeListBuilder.addFinalizeFilter(filter);
    }

    /**
     * Called at the end of the pipeline after the notif list has been finalized but before it has
     * been handed off to the view layer.
     */
    public void addOnBeforeRenderListListener(OnBeforeRenderListListener listener) {
        mShadeListBuilder.addOnBeforeRenderListListener(listener);
    }

    /**
     * Returns a read-only view in to the current shade list, i.e. the list of notifications that
     * are currently present in the shade. If this method is called during pipeline execution it
     * will return the current state of the list, which will likely be only partially-generated.
     */
    public List<ListEntry> getShadeList() {
        return mShadeListBuilder.getShadeList();
    }

    /**
     * Returns the number of notifications currently shown in the shade. This includes all
     * children and summary notifications. If this method is called during pipeline execution it
     * will return the number of notifications in its current state, which will likely be only
     * partially-generated.
     */
    public int getShadeListCount() {
        final List<ListEntry> entries = getShadeList();
        int numNotifs = 0;
        for (int i = 0; i < entries.size(); i++) {
            final ListEntry entry = entries.get(i);
            if (entry instanceof GroupEntry) {
                final GroupEntry parentEntry = (GroupEntry) entry;
                numNotifs++; // include the summary in the count
                numNotifs += parentEntry.getChildren().size();
            } else {
                numNotifs++;
            }
        }

        return numNotifs;
    }
}
