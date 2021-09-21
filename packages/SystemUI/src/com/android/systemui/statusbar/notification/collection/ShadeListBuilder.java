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
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_GROUP_STABILIZING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_IDLE;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_PRE_GROUP_FILTERING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_RESETTING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_SORTING;
import static com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState.STATE_TRANSFORMING;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.NotificationInteractionTracker;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderLogger;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
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
import java.util.Objects;

import javax.inject.Inject;

/**
 * The second half of {@link NotifPipeline}. Sits downstream of the NotifCollection and transforms
 * its "notification set" into the "shade list", the filtered, grouped, and sorted list of
 * notifications that are currently present in the notification shade.
 */
@MainThread
@SysUISingleton
public class ShadeListBuilder implements Dumpable {
    private final SystemClock mSystemClock;
    private final ShadeListBuilderLogger mLogger;
    private final NotificationInteractionTracker mInteractionTracker;
    // used exclusivly by ShadeListBuilder#notifySectionEntriesUpdated
    private final ArrayList<ListEntry> mTempSectionMembers = new ArrayList<>();

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
    @Nullable private NotifStabilityManager mNotifStabilityManager;

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
            DumpManager dumpManager,
            NotificationInteractionTracker interactionTracker
    ) {
        Assert.isMainThread();
        mSystemClock = systemClock;
        mLogger = logger;
        mInteractionTracker = interactionTracker;
        dumpManager.registerDumpable(TAG, this);

        setSectioners(Collections.emptyList());
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

    void setSectioners(List<NotifSectioner> sectioners) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        mNotifSections.clear();
        for (NotifSectioner sectioner : sectioners) {
            mNotifSections.add(new NotifSection(sectioner, mNotifSections.size()));
            sectioner.setInvalidationListener(this::onNotifSectionInvalidated);
        }

        mNotifSections.add(new NotifSection(DEFAULT_SECTIONER, mNotifSections.size()));
    }

    void setNotifStabilityManager(NotifStabilityManager notifStabilityManager) {
        Assert.isMainThread();
        mPipelineState.requireState(STATE_IDLE);

        if (mNotifStabilityManager != null) {
            throw new IllegalStateException(
                    "Attempting to set the NotifStabilityManager more than once. There should "
                            + "only be one visual stability manager. Manager is being set by "
                            + mNotifStabilityManager.getName() + " and "
                            + notifStabilityManager.getName());
        }

        mNotifStabilityManager = notifStabilityManager;
        mNotifStabilityManager.setInvalidationListener(this::onReorderingAllowedInvalidated);
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

    private void onReorderingAllowedInvalidated(NotifStabilityManager stabilityManager) {
        Assert.isMainThread();

        mLogger.logReorderingAllowedInvalidated(
                stabilityManager.getName(),
                mPipelineState.getState());

        rebuildListIfBefore(STATE_GROUPING);
    }

    private void onPromoterInvalidated(NotifPromoter promoter) {
        Assert.isMainThread();

        mLogger.logPromoterInvalidated(promoter.getName(), mPipelineState.getState());

        rebuildListIfBefore(STATE_TRANSFORMING);
    }

    private void onNotifSectionInvalidated(NotifSectioner section) {
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
        mPipelineState.requireIsBefore(STATE_BUILD_STARTED);
        mPipelineState.setState(STATE_BUILD_STARTED);

        // Step 1: Reset notification states
        mPipelineState.incrementTo(STATE_RESETTING);
        resetNotifs();
        onBeginRun();

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

        // Step 4.5: Reassign/revert any groups to maintain visual stability
        mPipelineState.incrementTo(STATE_GROUP_STABILIZING);
        stabilizeGroupingNotifs(mNotifList);


        // Step 5: Filter out entries after pre-group filtering, grouping and promoting
        // Now filters can see grouping information to determine whether to filter or not.
        dispatchOnBeforeFinalizeFilter(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_FINALIZE_FILTERING);
        filterNotifs(mNotifList, mNewNotifList, mNotifFinalizeFilters);
        applyNewNotifList();
        pruneIncompleteGroups(mNotifList);

        // Step 6: Sort
        // Assign each top-level entry a section, then sort the list by section and then within
        // section by our list of custom comparators
        dispatchOnBeforeSort(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_SORTING);
        sortListAndNotifySections();

        // Step 7: Lock in our group structure and log anything that's changed since the last run
        mPipelineState.incrementTo(STATE_FINALIZING);
        logChanges();
        freeEmptyGroups();
        cleanupPluggables();

        // Step 8: Dispatch the new list, first to any listeners and then to the view layer
        dispatchOnBeforeRenderList(mReadOnlyNotifList);
        if (mOnRenderListListener != null) {
            mOnRenderListListener.onRenderList(mReadOnlyNotifList);
        }

        // Step 9: We're done!
        mLogger.logEndBuildList(
                mIterationCount,
                mReadOnlyNotifList.size(),
                countChildren(mReadOnlyNotifList));
        if (mIterationCount % 10 == 0) {
            mLogger.logFinalList(mNotifList);
        }
        mPipelineState.setState(STATE_IDLE);
        mIterationCount++;
    }

    private void notifySectionEntriesUpdated() {
        NotifSection currentSection = null;
        mTempSectionMembers.clear();
        for (int i = 0; i < mNotifList.size(); i++) {
            ListEntry currentEntry = mNotifList.get(i);
            if (currentSection != currentEntry.getSection()) {
                if (currentSection != null) {
                    currentSection.getSectioner().onEntriesUpdated(mTempSectionMembers);
                    mTempSectionMembers.clear();
                }
                currentSection = currentEntry.getSection();
            }
            mTempSectionMembers.add(currentEntry);
        }
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
            group.beginNewAttachState();
            group.clearChildren();
            group.setSummary(null);
        }

        for (NotificationEntry entry : mAllEntries) {
            entry.beginNewAttachState();

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
                    group = new GroupEntry(topLevelKey, mSystemClock.uptimeMillis());
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
                                mIterationCount,
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
                    mLogger.logDuplicateTopLevelKey(mIterationCount, topLevelKey);
                } else {
                    entry.setParent(ROOT_ENTRY);
                    out.add(entry);
                }
            }
        }
    }

    private void stabilizeGroupingNotifs(List<ListEntry> topLevelList) {
        if (mNotifStabilityManager == null) {
            return;
        }

        for (int i = 0; i < topLevelList.size(); i++) {
            final ListEntry tle = topLevelList.get(i);
            if (tle instanceof GroupEntry) {
                // maybe put children back into their old group (including moving back to top-level)
                GroupEntry groupEntry = (GroupEntry) tle;
                List<NotificationEntry> children = groupEntry.getRawChildren();
                for (int j = 0; j < groupEntry.getChildren().size(); j++) {
                    if (maybeSuppressGroupChange(children.get(j), topLevelList)) {
                        // child was put back into its previous group, so we remove it from this
                        // group
                        children.remove(j);
                        j--;
                    }
                }
            } else {
                // maybe put top-level-entries back into their previous groups
                if (maybeSuppressGroupChange(tle.getRepresentativeEntry(), topLevelList)) {
                    // entry was put back into its previous group, so we remove it from the list of
                    // top-level-entries
                    topLevelList.remove(i);
                    i--;
                }
            }
        }
    }

    /**
     * Returns true if the group change was suppressed, else false
     */
    private boolean maybeSuppressGroupChange(NotificationEntry entry, List<ListEntry> out) {
        if (!entry.wasAttachedInPreviousPass()) {
            return false; // new entries are allowed
        }

        final GroupEntry prevParent = entry.getPreviousAttachState().getParent();
        final GroupEntry assignedParent = entry.getParent();
        if (prevParent != assignedParent
                && !mNotifStabilityManager.isGroupChangeAllowed(entry.getRepresentativeEntry())) {
            entry.getAttachState().getSuppressedChanges().setParent(assignedParent);
            entry.setParent(prevParent);
            if (prevParent == ROOT_ENTRY) {
                out.add(entry);
            } else if (prevParent != null) {
                prevParent.addChild(entry);
                if (!mGroups.containsKey(prevParent.getKey())) {
                    mGroups.put(prevParent.getKey(), prevParent);
                }
            }

            return true;
        }

        return false;
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

                    if (group.getSummary() != null
                            && group.wasAttachedInPreviousPass()
                            && mNotifStabilityManager != null
                            && !mNotifStabilityManager.isGroupChangeAllowed(group.getSummary())) {
                        // if this group was previously attached and group changes aren't
                        // allowed, keep it around until group changes are allowed again
                        group.getAttachState().getSuppressedChanges().setWasPruneSuppressed(true);
                        continue;
                    }

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
        entry.setParent(null);
        entry.getAttachState().setSection(null);
        entry.getAttachState().setPromoter(null);
        if (entry.mFirstAddedIteration == mIterationCount) {
            entry.mFirstAddedIteration = -1;
        }
    }

    private void sortListAndNotifySections() {
        // Assign sections to top-level elements and sort their children
        for (ListEntry entry : mNotifList) {
            NotifSection section = applySections(entry);
            if (entry instanceof GroupEntry) {
                GroupEntry parent = (GroupEntry) entry;
                for (NotificationEntry child : parent.getChildren()) {
                    child.getAttachState().setSection(section);
                }
                parent.sortChildren(sChildComparator);
            }
        }

        // Finally, sort all top-level elements
        mNotifList.sort(mTopLevelComparator);

        // notify sections since the list is sorted now
        notifySectionEntriesUpdated();
    }

    private void freeEmptyGroups() {
        mGroups.values().removeIf(ge -> ge.getSummary() == null && ge.getChildren().isEmpty());
    }

    private void logChanges() {
        for (NotificationEntry entry : mAllEntries) {
            logAttachStateChanges(entry);
        }
        for (GroupEntry group : mGroups.values()) {
            logAttachStateChanges(group);
        }
    }

    private void logAttachStateChanges(ListEntry entry) {

        final ListAttachState curr = entry.getAttachState();
        final ListAttachState prev = entry.getPreviousAttachState();

        if (!Objects.equals(curr, prev)) {
            mLogger.logEntryAttachStateChanged(
                    mIterationCount,
                    entry.getKey(),
                    prev.getParent(),
                    curr.getParent());

            if (curr.getParent() != prev.getParent()) {
                mLogger.logParentChanged(mIterationCount, prev.getParent(), curr.getParent());
            }

            if (curr.getSuppressedChanges().getParent() != null) {
                mLogger.logParentChangeSuppressed(
                        mIterationCount,
                        curr.getSuppressedChanges().getParent(),
                        curr.getParent());
            }

            if (curr.getSuppressedChanges().getWasPruneSuppressed()) {
                mLogger.logGroupPruningSuppressed(
                        mIterationCount,
                        curr.getParent());
            }

            if (curr.getExcludingFilter() != prev.getExcludingFilter()) {
                mLogger.logFilterChanged(
                        mIterationCount,
                        prev.getExcludingFilter(),
                        curr.getExcludingFilter());
            }

            // When something gets detached, its promoter and section are always set to null, so
            // don't bother logging those changes.
            final boolean wasDetached = curr.getParent() == null && prev.getParent() != null;

            if (!wasDetached && curr.getPromoter() != prev.getPromoter()) {
                mLogger.logPromoterChanged(
                        mIterationCount,
                        prev.getPromoter(),
                        curr.getPromoter());
            }

            if (!wasDetached && curr.getSection() != prev.getSection()) {
                mLogger.logSectionChanged(
                        mIterationCount,
                        prev.getSection(),
                        curr.getSection());
            }

            if (curr.getSuppressedChanges().getSection() != null) {
                mLogger.logSectionChangeSuppressed(
                        mIterationCount,
                        curr.getSuppressedChanges().getSection(),
                        curr.getSection());
            }
        }
    }

    private void onBeginRun() {
        if (mNotifStabilityManager != null) {
            mNotifStabilityManager.onBeginRun();
        }
    }

    private void cleanupPluggables() {
        callOnCleanup(mNotifPreGroupFilters);
        callOnCleanup(mNotifPromoters);
        callOnCleanup(mNotifFinalizeFilters);
        callOnCleanup(mNotifComparators);

        for (int i = 0; i < mNotifSections.size(); i++) {
            mNotifSections.get(i).getSectioner().onCleanup();
        }

        if (mNotifStabilityManager != null) {
            callOnCleanup(List.of(mNotifStabilityManager));
        }
    }

    private void callOnCleanup(List<? extends Pluggable<?>> pluggables) {
        for (int i = 0; i < pluggables.size(); i++) {
            pluggables.get(i).onCleanup();
        }
    }

    private final Comparator<ListEntry> mTopLevelComparator = (o1, o2) -> {

        int cmp = Integer.compare(
                o1.getSectionIndex(),
                o2.getSectionIndex());

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
        final NotifFilter filter = findRejectingFilter(entry, now, filters);
        entry.getAttachState().setExcludingFilter(filter);
        if (filter != null) {
            // notification is removed from the list, so we reset its initialization time
            entry.resetInitializationTime();
        }
        return filter != null;
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
        entry.getAttachState().setPromoter(promoter);
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

    private NotifSection applySections(ListEntry entry) {
        final NotifSection newSection = findSection(entry);
        final ListAttachState prevAttachState = entry.getPreviousAttachState();

        NotifSection finalSection = newSection;

        // have we seen this entry before and are we changing its section?
        if (mNotifStabilityManager != null
                && entry.wasAttachedInPreviousPass()
                && newSection != prevAttachState.getSection()) {

            // are section changes allowed?
            if (!mNotifStabilityManager.isSectionChangeAllowed(entry.getRepresentativeEntry())) {
                // record the section that we wanted to change to
                entry.getAttachState().getSuppressedChanges().setSection(newSection);

                // keep the previous section
                finalSection = prevAttachState.getSection();
            }
        }

        entry.getAttachState().setSection(finalSection);
        return finalSection;
    }

    @NonNull
    private NotifSection findSection(ListEntry entry) {
        for (int i = 0; i < mNotifSections.size(); i++) {
            NotifSection section = mNotifSections.get(i);
            if (section.getSectioner().isInSection(entry)) {
                return section;
            }
        }
        throw new RuntimeException("Missing default sectioner!");
    }

    private void rebuildListIfBefore(@PipelineState.StateName int state) {
        mPipelineState.requireIsBefore(state);
        if (mPipelineState.is(STATE_IDLE)) {
            buildList();
        }
    }

    private static int countChildren(List<ListEntry> entries) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            final ListEntry entry = entries.get(i);
            if (entry instanceof GroupEntry) {
                count += ((GroupEntry) entry).getChildren().size();
            }
        }
        return count;
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
                mInteractionTracker,
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

    private static final NotifSectioner DEFAULT_SECTIONER =
            new NotifSectioner("UnknownSection") {
                @Override
                public boolean isInSection(ListEntry entry) {
                    return true;
                }
            };

    private static final int MIN_CHILDREN_FOR_GROUP = 2;

    private static final String TAG = "ShadeListBuilder";
}
