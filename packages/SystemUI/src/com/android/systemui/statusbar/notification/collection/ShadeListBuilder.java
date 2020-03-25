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

package com.android.systemui.statusbar.notification.collection;

import static com.android.systemui.statusbar.notification.collection.GroupEntry.ROOT_ENTRY;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_BUILD_STARTED;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_FINALIZE_FILTERING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_FINALIZING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_GROUPING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_IDLE;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_PRE_GROUP_FILTERING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_RESETTING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_SORTING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_TRANSFORMING;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderLogger;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSection;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.util.Assert;
import com.android.systemui.util.time.SystemClock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The second half of {@link NotifPipeline}. Sits downstream of the NotifCollection and transforms
 * its "notification set" into the "shade list", the filtered, grouped, and sorted list of
 * notifications that are currently present in the notification shade.
 */
@MainThread
@Singleton
public class ShadeListBuilder implements Dumpable {
    private final SystemClock mSystemClock;
    private final ShadeListBuilderLogger mLogger;

    private List<ListEntry> mNotifList = new ArrayList<>();
    private List<ListEntry> mNewNotifList = new ArrayList<>();

    private final PipelineState mPipelineState = new PipelineState();
    private final Map<String, GroupEntry> mGroups = new ArrayMap<>();
    private Collection<NotificationEntry> mAllEntries = Collections.emptyList();
    private int mIterationCount = 0;

    private final List<NotifFilter> mNotifPreGroupFilters = new ArrayList<>();
    private final List<NotifPromoter> mNotifPromoters = new ArrayList<>();
    private final List<NotifFilter> mNotifFinalizeFilters = new ArrayList<>();
    private final List<NotifComparator> mNotifComparators = new ArrayList<>();
    private final List<NotifSection> mNotifSections = new ArrayList<>();

    private final List<OnBeforeTransformGroupsListener> mOnBeforeTransformGroupsListeners =
            new ArrayList<>();
    private final List<OnBeforeSortListener> mOnBeforeSortListeners =
            new ArrayList<>();
    private final List<OnBeforeFinalizeFilterListener> mOnBeforeFinalizeFilterListeners =
            new ArrayList<>();
    private final List<OnBeforeRenderListListener> mOnBeforeRenderListListeners =
            new ArrayList<>();
    @Nullable private OnRenderListListener mOnRenderListListener;

    private List<ListEntry> mReadOnlyNotifList = Collections.unmodifiableList(mNotifList);
    private List<ListEntry> mReadOnlyNewNotifList = Collections.unmodifiableList(mNewNotifList);

    @Inject
    public ShadeListBuilder(
            SystemClock systemClock,
            ShadeListBuilderLogger logger,
            DumpManager dumpManager) {
        Assert.isMainThread();
        mSystemClock = systemClock;
        mLogger = logger;
        dumpManager.registerDumpable(TAG, this);
    }

    /**
     * Attach the list builder to the NotifCollection. After this is called, it will start building
     * the notif list in response to changes to the colletion.
     */
    public void attach(NotifCollection collection) {
        Assert.isMainThread();
        collection.setBuildListener(mReadyForBuildListener);
    }

    /**
     * Registers the listener that's responsible for rendering the notif list to the screen. Called
     * At the very end of pipeline execution, after all other listeners and pluggables have fired.
     */
    public void setOnRenderListListener(OnRenderListListener onRenderListListener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnRenderListListener = onRenderListListener;
    }

    void addOnBeforeTransformGroupsListener(OnBeforeTransformGroupsListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeTransformGroupsListeners.add(listener);
    }

    void addOnBeforeSortListener(OnBeforeSortListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeSortListeners.add(listener);
    }

    void addOnBeforeFinalizeFilterListener(OnBeforeFinalizeFilterListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeFinalizeFilterListeners.add(listener);
    }

    void addOnBeforeRenderListListener(OnBeforeRenderListListener listener) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        mOnBeforeRenderListListeners.add(listener);
    }

    void addPreGroupFilter(NotifFilter filter) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifPreGroupFilters.add(filter);
        filter.setInvalidationListener(this::onPreGroupFilterInvalidated);
    }

    void addFinalizeFilter(NotifFilter filter) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifFinalizeFilters.add(filter);
        filter.setInvalidationListener(this::onFinalizeFilterInvalidated);
    }

    void addPromoter(NotifPromoter promoter) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifPromoters.add(promoter);
        promoter.setInvalidationListener(this::onPromoterInvalidated);
    }

    void setSections(List<NotifSection> sections) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifSections.clear();
        for (NotifSection section : sections) {
            mNotifSections.add(section);
            section.setInvalidationListener(this::onNotifSectionInvalidated);
        }
    }

    void setComparators(List<NotifComparator> comparators) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifComparators.clear();
        for (NotifComparator comparator : comparators) {
            mNotifComparators.add(comparator);
            comparator.setInvalidationListener(this::onNotifComparatorInvalidated);
        }
    }

    List<ListEntry> getShadeList() {
        Assert.isMainThread();
        return mReadOnlyNotifList;
    }

    private final CollectionReadyForBuildListener mReadyForBuildListener =
            new CollectionReadyForBuildListener() {
                @Override
                public void onBuildList(Collection<NotificationEntry> entries) {
                    Assert.isMainThread();
                    mPipelineState.requireIsBefore(STATE_BUILD_STARTED);

                    mLogger.logOnBuildList();
                    mAllEntries = entries;
                    buildList();
                }
            };

    private void onPreGroupFilterInvalidated(NotifFilter filter) {
        Assert.isMainThread();

        mLogger.logPreGroupFilterInvalidated(filter.getName(), mPipelineState.getState());

        rebuildListIfBefore(STATE_PRE_GROUP_FILTERING);
    }

    private void onPromoterInvalidated(NotifPromoter promoter) {
        Assert.isMainThread();

        mLogger.logPromoterInvalidated(promoter.getName(), mPipelineState.getState());

        rebuildListIfBefore(STATE_TRANSFORMING);
    }

    private void onNotifSectionInvalidated(NotifSection section) {
        Assert.isMainThread();

        mLogger.logNotifSectionInvalidated(section.getName(), mPipelineState.getState());

        rebuildListIfBefore(STATE_SORTING);
    }

    private void onFinalizeFilterInvalidated(NotifFilter filter) {
        Assert.isMainThread();

        mLogger.logFinalizeFilterInvalidated(filter.getName(), mPipelineState.getState());

        rebuildListIfBefore(STATE_FINALIZE_FILTERING);
    }

    private void onNotifComparatorInvalidated(NotifComparator comparator) {
        Assert.isMainThread();

        mLogger.logNotifComparatorInvalidated(comparator.getName(), mPipelineState.getState());

        rebuildListIfBefore(STATE_SORTING);
    }

    /**
     * The core algorithm of the pipeline. See the top comment in {@link NotifPipeline} for
     * details on our contracts with other code.
     *
     * Once the build starts we are very careful to protect against reentrant code. Anything that
     * tries to invalidate itself after the pipeline has passed it by will return in an exception.
     * In general, we should be extremely sensitive to client code doing things in the wrong order;
     * if we detect that behavior, we should crash instantly.
     */
    private void buildList() {
        mLogger.logStartBuildList(mIterationCount);

        mPipelineState.requireIsBefore(STATE_BUILD_STARTED);
        mPipelineState.setState(STATE_BUILD_STARTED);

        // Step 1: Reset notification states
        mPipelineState.incrementTo(STATE_RESETTING);
        resetNotifs();

        // Step 2: Filter out any notifications that shouldn't be shown right now
        mPipelineState.incrementTo(STATE_PRE_GROUP_FILTERING);
        filterNotifs(mAllEntries, mNotifList, mNotifPreGroupFilters);

        // Step 3: Group notifications with the same group key and set summaries
        mPipelineState.incrementTo(STATE_GROUPING);
        groupNotifs(mNotifList, mNewNotifList);
        applyNewNotifList();
        pruneIncompleteGroups(mNotifList);

        // Step 4: Group transforming
        // Move some notifs out of their groups and up to top-level (mostly used for heads-upping)
        dispatchOnBeforeTransformGroups(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_TRANSFORMING);
        promoteNotifs(mNotifList);
        pruneIncompleteGroups(mNotifList);

        // Step 5: Sort
        // Assign each top-level entry a section, then sort the list by section and then within
        // section by our list of custom comparators
        dispatchOnBeforeSort(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_SORTING);
        sortList();

        // Step 6: Filter out entries after pre-group filtering, grouping, promoting and sorting
        // Now filters can see grouping information to determine whether to filter or not.
        dispatchOnBeforeFinalizeFilter(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_FINALIZE_FILTERING);
        filterNotifs(mNotifList, mNewNotifList, mNotifFinalizeFilters);
        applyNewNotifList();
        pruneIncompleteGroups(mNotifList);

        // Step 7: Lock in our group structure and log anything that's changed since the last run
        mPipelineState.incrementTo(STATE_FINALIZING);
        logFilterChanges();
        logParentingChanges();
        freeEmptyGroups();

        // Step 8: Dispatch the new list, first to any listeners and then to the view layer
        if (mIterationCount % 10 == 0) {
            mLogger.logFinalList(mNotifList);
        }
        dispatchOnBeforeRenderList(mReadOnlyNotifList);
        if (mOnRenderListListener != null) {
            mOnRenderListListener.onRenderList(mReadOnlyNotifList);
        }

        // Step 9: We're done!
        mLogger.logEndBuildList(mIterationCount);
        mPipelineState.setState(STATE_IDLE);
        mIterationCount++;
    }

    /**
     * Points mNotifList to the list stored in mNewNotifList.
     * Reuses the (emptied) mNotifList as mNewNotifList.
     *
     * Accordingly, updates the ReadOnlyNotifList pointers.
     */
    private void applyNewNotifList() {
        mNotifList.clear();
        List<ListEntry> emptyList = mNotifList;
        mNotifList = mNewNotifList;
        mNewNotifList = emptyList;

        List<ListEntry> readOnlyNotifList = mReadOnlyNotifList;
        mReadOnlyNotifList = mReadOnlyNewNotifList;
        mReadOnlyNewNotifList = readOnlyNotifList;
    }

    private void resetNotifs() {
        for (GroupEntry group : mGroups.values()) {
            group.setPreviousParent(group.getParent());
            group.setParent(null);
            group.clearChildren();
            group.setSummary(null);
        }

        for (NotificationEntry entry : mAllEntries) {
            entry.setPreviousParent(entry.getParent());
            entry.setParent(null);

            entry.mPreviousExcludingFilter = entry.mExcludingFilter;
            entry.mExcludingFilter = null;

            if (entry.mFirstAddedIteration == -1) {
                entry.mFirstAddedIteration = mIterationCount;
            }
        }

        mNotifList.clear();
    }

    private void filterNotifs(
            Collection<? extends ListEntry> entries,
            List<ListEntry> out,
            List<NotifFilter> filters) {
        final long now = mSystemClock.uptimeMillis();
        for (ListEntry entry : entries)  {
            if (entry instanceof GroupEntry) {
                final GroupEntry groupEntry = (GroupEntry) entry;

                // apply filter on its summary
                final NotificationEntry summary = groupEntry.getRepresentativeEntry();
                if (applyFilters(summary, now, filters)) {
                    groupEntry.setSummary(null);
                    annulAddition(summary);
                }

                // apply filter on its children
                final List<NotificationEntry> children = groupEntry.getRawChildren();
                for (int j = children.size() - 1; j >= 0; j--) {
                    final NotificationEntry child = children.get(j);
                    if (applyFilters(child, now, filters)) {
                        children.remove(child);
                        annulAddition(child);
                    }
                }

                out.add(groupEntry);
            } else {
                if (applyFilters((NotificationEntry) entry, now, filters)) {
                    annulAddition(entry);
                } else {
                    out.add(entry);
                }
            }
        }
    }

    private void groupNotifs(List<ListEntry> entries, List<ListEntry> out) {
        for (ListEntry listEntry : entries) {
            // since grouping hasn't happened yet, all notifs are NotificationEntries
            NotificationEntry entry = (NotificationEntry) listEntry;
            if (entry.getSbn().isGroup()) {
                final String topLevelKey = entry.getSbn().getGroupKey();

                GroupEntry group = mGroups.get(topLevelKey);
                if (group == null) {
                    group = new GroupEntry(topLevelKey);
                    group.mFirstAddedIteration = mIterationCount;
                    mGroups.put(topLevelKey, group);
                }
                if (group.getParent() == null) {
                    group.setParent(ROOT_ENTRY);
                    out.add(group);
                }

                entry.setParent(group);

                if (entry.getSbn().getNotification().isGroupSummary()) {
                    final NotificationEntry existingSummary = group.getSummary();

                    if (existingSummary == null) {
                        group.setSummary(entry);
                    } else {
                        mLogger.logDuplicateSummary(
                                group.getKey(),
                                existingSummary.getKey(),
                                entry.getKey());

                        // Use whichever one was posted most recently
                        if (entry.getSbn().getPostTime()
                                > existingSummary.getSbn().getPostTime()) {
                            group.setSummary(entry);
                            annulAddition(existingSummary, out);
                        } else {
                            annulAddition(entry, out);
                        }
                    }
                } else {
                    group.addChild(entry);
                }

            } else {

                final String topLevelKey = entry.getKey();
                if (mGroups.containsKey(topLevelKey)) {
                    mLogger.logDuplicateTopLevelKey(topLevelKey);
                } else {
                    entry.setParent(ROOT_ENTRY);
                    out.add(entry);
                }
            }
        }
    }

    private void promoteNotifs(List<ListEntry> list) {
        for (int i = 0; i < list.size(); i++) {
            final ListEntry tle = list.get(i);

            if (tle instanceof GroupEntry) {
                final GroupEntry group = (GroupEntry) tle;

                group.getRawChildren().removeIf(child -> {
                    final boolean shouldPromote = applyTopLevelPromoters(child);

                    if (shouldPromote) {
                        child.setParent(ROOT_ENTRY);
                        list.add(child);
                    }

                    return shouldPromote;
                });
            }
        }
    }

    private void pruneIncompleteGroups(List<ListEntry> shadeList) {
        for (int i = 0; i < shadeList.size(); i++) {
            final ListEntry tle = shadeList.get(i);

            if (tle instanceof GroupEntry) {
                final GroupEntry group = (GroupEntry) tle;
                final List<NotificationEntry> children = group.getRawChildren();

                if (group.getSummary() != null && children.size() == 0) {
                    shadeList.remove(i);
                    i--;

                    NotificationEntry summary = group.getSummary();
                    summary.setParent(ROOT_ENTRY);
                    shadeList.add(summary);

                    group.setSummary(null);
                    annulAddition(group, shadeList);

                } else if (group.getSummary() == null
                        || children.size() < MIN_CHILDREN_FOR_GROUP) {
                    // If the group doesn't provide a summary or is too small, ignore it and add
                    // its children (if any) directly to top-level.

                    shadeList.remove(i);
                    i--;

                    if (group.getSummary() != null) {
                        final NotificationEntry summary = group.getSummary();
                        group.setSummary(null);
                        annulAddition(summary, shadeList);
                    }

                    for (int j = 0; j < children.size(); j++) {
                        final NotificationEntry child = children.get(j);
                        child.setParent(ROOT_ENTRY);
                        shadeList.add(child);
                    }
                    children.clear();

                    annulAddition(group, shadeList);
                }
            }
        }
    }

    /**
     * If a ListEntry was added to the shade list and then later removed (e.g. because it was a
     * group that was broken up), this method will erase any bookkeeping traces of that addition
     * and/or check that they were already erased.
     *
     * Before calling this method, the entry must already have been removed from its parent. If
     * it's a group, its summary must be null and its children must be empty.
     */
    private void annulAddition(ListEntry entry, List<ListEntry> shadeList) {

        // This function does very little, but if any of its assumptions are violated (and it has a
        // lot of them), it will put the system into an inconsistent state. So we check all of them
        // here.

        if (entry.getParent() == null || entry.mFirstAddedIteration == -1) {
            throw new IllegalStateException(
                    "Cannot nullify addition of " + entry.getKey() + ": no such addition. ("
                            + entry.getParent() + " " + entry.mFirstAddedIteration + ")");
        }

        if (entry.getParent() == ROOT_ENTRY) {
            if (shadeList.contains(entry)) {
                throw new IllegalStateException("Cannot nullify addition of " + entry.getKey()
                        + ": it's still in the shade list.");
            }
        }

        if (entry instanceof GroupEntry) {
            GroupEntry ge = (GroupEntry) entry;
            if (ge.getSummary() != null) {
                throw new IllegalStateException(
                        "Cannot nullify group " + ge.getKey() + ": summary is not null");
            }
            if (!ge.getChildren().isEmpty()) {
                throw new IllegalStateException(
                        "Cannot nullify group " + ge.getKey() + ": still has children");
            }
        } else if (entry instanceof NotificationEntry) {
            if (entry == entry.getParent().getSummary()
                    || entry.getParent().getChildren().contains(entry)) {
                throw new IllegalStateException("Cannot nullify addition of child "
                        + entry.getKey() + ": it's still attached to its parent.");
            }
        }

        annulAddition(entry);

    }

    /**
     * Erases bookkeeping traces stored on an entry when it is removed from the notif list.
     * This can happen if the entry is removed from a group that was broken up or if the entry was
     * filtered out during any of the filtering steps.
     */
    private void annulAddition(ListEntry entry) {
        // TODO: We should null out the entry's section and promoter here. However, if we do that,
        //  future runs will think that the section changed. We need a mPreviousNotifSection,
        //  similar to what we do for parents.
        entry.setParent(null);
        if (entry.mFirstAddedIteration == mIterationCount) {
            entry.mFirstAddedIteration = -1;
        }
    }

    private void sortList() {
        // Assign sections to top-level elements and sort their children
        for (ListEntry entry : mNotifList) {
            Pair<NotifSection, Integer> sectionWithIndex = applySections(entry);
            if (entry instanceof GroupEntry) {
                GroupEntry parent = (GroupEntry) entry;
                for (NotificationEntry child : parent.getChildren()) {
                    child.mNotifSection = sectionWithIndex.first;
                    child.setSection(sectionWithIndex.second);
                }
                parent.sortChildren(sChildComparator);
            }
        }

        // Finally, sort all top-level elements
        mNotifList.sort(mTopLevelComparator);
    }

    private void freeEmptyGroups() {
        mGroups.values().removeIf(ge -> ge.getSummary() == null && ge.getChildren().isEmpty());
    }

    private void logFilterChanges() {
        for (NotificationEntry entry : mAllEntries) {
            if (entry.mExcludingFilter != entry.mPreviousExcludingFilter) {
                mLogger.logFilterChanged(
                        entry.getKey(),
                        entry.mPreviousExcludingFilter,
                        entry.mExcludingFilter);
            }
        }
    }

    private void logParentingChanges() {
        for (NotificationEntry entry : mAllEntries) {
            if (entry.getParent() != entry.getPreviousParent()) {
                mLogger.logParentChanged(
                        entry.getKey(),
                        entry.getPreviousParent() == null
                                ? null : entry.getPreviousParent().getKey(),
                        entry.getParent() == null
                                ? null : entry.getParent().getKey());
            }
        }
        for (GroupEntry group : mGroups.values()) {
            if (group.getParent() != group.getPreviousParent()) {
                mLogger.logParentChanged(
                        group.getKey(),
                        group.getPreviousParent() == null
                                ? null : group.getPreviousParent().getKey(),
                        group.getParent() == null
                                ? null : group.getParent().getKey());
            }
        }
    }

    private final Comparator<ListEntry> mTopLevelComparator = (o1, o2) -> {

        int cmp = Integer.compare(o1.getSection(), o2.getSection());

        if (cmp == 0) {
            for (int i = 0; i < mNotifComparators.size(); i++) {
                cmp = mNotifComparators.get(i).compare(o1, o2);
                if (cmp != 0) {
                    break;
                }
            }
        }

        final NotificationEntry rep1 = o1.getRepresentativeEntry();
        final NotificationEntry rep2 = o2.getRepresentativeEntry();

        if (cmp == 0) {
            cmp = rep1.getRanking().getRank() - rep2.getRanking().getRank();
        }

        if (cmp == 0) {
            cmp = Long.compare(
                    rep2.getSbn().getNotification().when,
                    rep1.getSbn().getNotification().when);
        }

        return cmp;
    };

    private static final Comparator<NotificationEntry> sChildComparator = (o1, o2) -> {
        int cmp = o1.getRanking().getRank() - o2.getRanking().getRank();

        if (cmp == 0) {
            cmp = Long.compare(
                    o2.getSbn().getNotification().when,
                    o1.getSbn().getNotification().when);
        }

        return cmp;
    };

    private boolean applyFilters(NotificationEntry entry, long now, List<NotifFilter> filters) {
        entry.mExcludingFilter = findRejectingFilter(entry, now, filters);
        return entry.mExcludingFilter != null;
    }

    @Nullable private static NotifFilter findRejectingFilter(NotificationEntry entry, long now,
            List<NotifFilter> filters) {
        final int size = filters.size();

        for (int i = 0; i < size; i++) {
            NotifFilter filter = filters.get(i);
            if (filter.shouldFilterOut(entry, now)) {
                return filter;
            }
        }
        return null;
    }

    private boolean applyTopLevelPromoters(NotificationEntry entry) {
        NotifPromoter promoter = findPromoter(entry);

        if (promoter != entry.mNotifPromoter) {
            mLogger.logPromoterChanged(
                    entry.getKey(),
                    entry.mNotifPromoter != null ? entry.mNotifPromoter.getName() : null,
                    promoter != null ? promoter.getName() : null);
            entry.mNotifPromoter = promoter;
        }

        return promoter != null;
    }

    @Nullable private NotifPromoter findPromoter(NotificationEntry entry) {
        for (int i = 0; i < mNotifPromoters.size(); i++) {
            NotifPromoter promoter = mNotifPromoters.get(i);
            if (promoter.shouldPromoteToTopLevel(entry)) {
                return promoter;
            }
        }
        return null;
    }

    private Pair<NotifSection, Integer> applySections(ListEntry entry) {
        final Pair<NotifSection, Integer> sectionWithIndex = findSection(entry);
        final NotifSection section = sectionWithIndex.first;
        final Integer sectionIndex = sectionWithIndex.second;

        if (section != entry.mNotifSection) {
            mLogger.logSectionChanged(
                    entry.getKey(),
                    entry.mNotifSection != null ? entry.mNotifSection.getName() : null,
                    entry.getSection(),
                    section.getName(),
                    sectionIndex);

            entry.mNotifSection = section;
            entry.setSection(sectionIndex);
        }

        return sectionWithIndex;
    }

    private Pair<NotifSection, Integer> findSection(ListEntry entry) {
        for (int i = 0; i < mNotifSections.size(); i++) {
            NotifSection sectioner = mNotifSections.get(i);
            if (sectioner.isInSection(entry)) {
                return new Pair<>(sectioner, i);
            }
        }
        return new Pair<>(sDefaultSection, mNotifSections.size());
    }

    private void rebuildListIfBefore(@PipelineState.StateName int state) {
        mPipelineState.requireIsBefore(state);
        if (mPipelineState.is(STATE_IDLE)) {
            buildList();
        }
    }

    private void dispatchOnBeforeTransformGroups(List<ListEntry> entries) {
        for (int i = 0; i < mOnBeforeTransformGroupsListeners.size(); i++) {
            mOnBeforeTransformGroupsListeners.get(i).onBeforeTransformGroups(entries);
        }
    }

    private void dispatchOnBeforeSort(List<ListEntry> entries) {
        for (int i = 0; i < mOnBeforeSortListeners.size(); i++) {
            mOnBeforeSortListeners.get(i).onBeforeSort(entries);
        }
    }

    private void dispatchOnBeforeFinalizeFilter(List<ListEntry> entries) {
        for (int i = 0; i < mOnBeforeFinalizeFilterListeners.size(); i++) {
            mOnBeforeFinalizeFilterListeners.get(i).onBeforeFinalizeFilter(entries);
        }
    }

    private void dispatchOnBeforeRenderList(List<ListEntry> entries) {
        for (int i = 0; i < mOnBeforeRenderListListeners.size(); i++) {
            mOnBeforeRenderListListeners.get(i).onBeforeRenderList(entries);
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, PrintWriter pw, @NonNull String[] args) {
        pw.println("\t" + TAG + " shade notifications:");
        if (getShadeList().size() == 0) {
            pw.println("\t\t None");
        }

        pw.println(ListDumper.dumpTree(
                getShadeList(),
                true,
                "\t\t"));
    }

    /** See {@link #setOnRenderListListener(OnRenderListListener)} */
    public interface OnRenderListListener {
        /**
         * Called with the final filtered, grouped, and sorted list.
         *
         * @param entries A read-only view into the current notif list. Note that this list is
         *                backed by the live list and will change in response to new pipeline runs.
         */
        void onRenderList(@NonNull List<ListEntry> entries);
    }

    private static final NotifSection sDefaultSection =
            new NotifSection("DefaultSection") {
                @Override
                public boolean isInSection(ListEntry entry) {
                    return true;
                }
            };

    private static final String TAG = "NotifListBuilderImpl";

    private static final int MIN_CHILDREN_FOR_GROUP = 2;
}
