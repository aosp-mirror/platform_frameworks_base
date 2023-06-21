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

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkState;
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

import static java.util.Objects.requireNonNull;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.os.Trace;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.NotificationInteractionTracker;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.PipelineState;
import com.android.systemui.statusbar.notification.collection.listbuilder.SemiStableSort;
import com.android.systemui.statusbar.notification.collection.listbuilder.SemiStableSort.StableOrder;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderHelper;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderLogger;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.DefaultNotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt;
import com.android.systemui.util.Assert;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.inject.Inject;

/**
 * The second half of {@link NotifPipeline}. Sits downstream of the NotifCollection and transforms
 * its "notification set" into the "shade list", the filtered, grouped, and sorted list of
 * notifications that are currently present in the notification shade.
 */
@MainThread
@SysUISingleton
public class ShadeListBuilder implements Dumpable, PipelineDumpable {
    private final SystemClock mSystemClock;
    private final ShadeListBuilderLogger mLogger;
    private final NotificationInteractionTracker mInteractionTracker;
    private final DumpManager mDumpManager;
    // used exclusivly by ShadeListBuilder#notifySectionEntriesUpdated
    // TODO replace temp with collection pool for readability
    private final ArrayList<ListEntry> mTempSectionMembers = new ArrayList<>();
    private NotifPipelineFlags mFlags;
    private final boolean mAlwaysLogList;

    private List<ListEntry> mNotifList = new ArrayList<>();
    private List<ListEntry> mNewNotifList = new ArrayList<>();

    private final SemiStableSort mSemiStableSort = new SemiStableSort();
    private final StableOrder<ListEntry> mStableOrder = this::getStableOrderRank;
    private final PipelineState mPipelineState = new PipelineState();
    private final Map<String, GroupEntry> mGroups = new ArrayMap<>();
    private Collection<NotificationEntry> mAllEntries = Collections.emptyList();
    private int mIterationCount = 0;

    private final List<NotifFilter> mNotifPreGroupFilters = new ArrayList<>();
    private final List<NotifPromoter> mNotifPromoters = new ArrayList<>();
    private final List<NotifFilter> mNotifFinalizeFilters = new ArrayList<>();
    private final List<NotifComparator> mNotifComparators = new ArrayList<>();
    private final List<NotifSection> mNotifSections = new ArrayList<>();
    private NotifStabilityManager mNotifStabilityManager;

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
    private final NotifPipelineChoreographer mChoreographer;

    private int mConsecutiveReentrantRebuilds = 0;
    @VisibleForTesting public static final int MAX_CONSECUTIVE_REENTRANT_REBUILDS = 3;

    @Inject
    public ShadeListBuilder(
            DumpManager dumpManager,
            NotifPipelineChoreographer pipelineChoreographer,
            NotifPipelineFlags flags,
            NotificationInteractionTracker interactionTracker,
            ShadeListBuilderLogger logger,
            SystemClock systemClock
    ) {
        mSystemClock = systemClock;
        mLogger = logger;
        mFlags = flags;
        mAlwaysLogList = flags.isDevLoggingEnabled();
        mInteractionTracker = interactionTracker;
        mChoreographer = pipelineChoreographer;
        mDumpManager = dumpManager;
        setSectioners(Collections.emptyList());
    }

    /**
     * Attach the list builder to the NotifCollection. After this is called, it will start building
     * the notif list in response to changes to the colletion.
     */
    public void attach(NotifCollection collection) {
        Assert.isMainThread();
        mDumpManager.registerDumpable(TAG, this);
        collection.addCollectionListener(mInteractionTracker);
        collection.setBuildListener(mReadyForBuildListener);
        mChoreographer.addOnEvalListener(this::buildList);
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

    void addPreRenderInvalidator(Invalidator invalidator) {
        Assert.isMainThread();

        mPipelineState.requireState(STATE_IDLE);
        invalidator.setInvalidationListener(this::onPreRenderInvalidated);
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
            final NotifSection section = new NotifSection(sectioner, mNotifSections.size());
            final NotifComparator sectionComparator = section.getComparator();
            mNotifSections.add(section);
            sectioner.setInvalidationListener(this::onNotifSectionInvalidated);
            if (sectionComparator != null) {
                sectionComparator.setInvalidationListener(this::onNotifComparatorInvalidated);
            }
        }

        mNotifSections.add(new NotifSection(DEFAULT_SECTIONER, mNotifSections.size()));

        // validate sections
        final ArraySet<Integer> seenBuckets = new ArraySet<>();
        int lastBucket = mNotifSections.size() > 0
                ? mNotifSections.get(0).getBucket()
                : 0;
        for (NotifSection section : mNotifSections) {
            if (lastBucket != section.getBucket() && seenBuckets.contains(section.getBucket())) {
                throw new IllegalStateException("setSectioners with non contiguous sections "
                        + section.getLabel() + " has an already seen bucket");
            }
            lastBucket = section.getBucket();
            seenBuckets.add(lastBucket);
        }
    }

    void setNotifStabilityManager(@NonNull NotifStabilityManager notifStabilityManager) {
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

    @NonNull
    private NotifStabilityManager getStabilityManager() {
        if (mNotifStabilityManager == null) {
            return DefaultNotifStabilityManager.INSTANCE;
        }
        return mNotifStabilityManager;
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
        // NOTE: Accessing this method when the pipeline is running is generally going to provide
        //  incorrect results, and indicates a poorly behaved component of the pipeline.
        mPipelineState.requireState(STATE_IDLE);
        return mReadOnlyNotifList;
    }

    private final CollectionReadyForBuildListener mReadyForBuildListener =
            new CollectionReadyForBuildListener() {
                @Override
                public void onBuildList(Collection<NotificationEntry> entries, String reason) {
                    Assert.isMainThread();
                    mPipelineState.requireIsBefore(STATE_BUILD_STARTED);

                    mLogger.logOnBuildList(reason);
                    mAllEntries = entries;
                    scheduleRebuild(/* reentrant = */ false);
                }
            };

    private void onPreRenderInvalidated(Invalidator invalidator, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logPreRenderInvalidated(invalidator, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_FINALIZING);
    }

    private void onPreGroupFilterInvalidated(NotifFilter filter, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logPreGroupFilterInvalidated(filter, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_PRE_GROUP_FILTERING);
    }

    private void onReorderingAllowedInvalidated(NotifStabilityManager stabilityManager,
            @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logReorderingAllowedInvalidated(
                stabilityManager,
                mPipelineState.getState(),
                reason);

        rebuildListIfBefore(STATE_GROUPING);
    }

    private void onPromoterInvalidated(NotifPromoter promoter, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logPromoterInvalidated(promoter, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_TRANSFORMING);
    }

    private void onNotifSectionInvalidated(NotifSectioner section, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logNotifSectionInvalidated(section, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_SORTING);
    }

    private void onFinalizeFilterInvalidated(NotifFilter filter, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logFinalizeFilterInvalidated(filter, mPipelineState.getState(), reason);

        rebuildListIfBefore(STATE_FINALIZE_FILTERING);
    }

    private void onNotifComparatorInvalidated(NotifComparator comparator, @Nullable String reason) {
        Assert.isMainThread();

        mLogger.logNotifComparatorInvalidated(comparator, mPipelineState.getState(), reason);

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
        Trace.beginSection("ShadeListBuilder.buildList");
        mPipelineState.requireIsBefore(STATE_BUILD_STARTED);

        if (!mNotifStabilityManager.isPipelineRunAllowed()) {
            mLogger.logPipelineRunSuppressed();
            Trace.endSection();
            return;
        }

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

        // Step 5: Section & Sort
        // Assign each top-level entry a section, and copy to all of its children
        dispatchOnBeforeSort(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_SORTING);
        assignSections();
        notifySectionEntriesUpdated();
        // Sort the list by section and then within section by our list of custom comparators
        sortListAndGroups();

        // Step 6: Filter out entries after pre-group filtering, grouping, promoting, and sorting
        // Now filters can see grouping, sectioning, and order information to determine whether
        // to filter or not.
        dispatchOnBeforeFinalizeFilter(mReadOnlyNotifList);
        mPipelineState.incrementTo(STATE_FINALIZE_FILTERING);
        filterNotifs(mNotifList, mNewNotifList, mNotifFinalizeFilters);
        applyNewNotifList();
        pruneIncompleteGroups(mNotifList);

        // Step 7: Lock in our group structure and log anything that's changed since the last run
        mPipelineState.incrementTo(STATE_FINALIZING);
        logChanges();
        freeEmptyGroups();
        cleanupPluggables();

        // Step 8: Dispatch the new list, first to any listeners and then to the view layer
        dispatchOnBeforeRenderList(mReadOnlyNotifList);
        Trace.beginSection("ShadeListBuilder.onRenderList");
        if (mOnRenderListListener != null) {
            mOnRenderListListener.onRenderList(mReadOnlyNotifList);
        }
        Trace.endSection();

        Trace.beginSection("ShadeListBuilder.logEndBuildList");
        // Step 9: We're done!
        mLogger.logEndBuildList(
                mIterationCount,
                mReadOnlyNotifList.size(),
                countChildren(mReadOnlyNotifList),
                /* enforcedVisualStability */ !mNotifStabilityManager.isEveryChangeAllowed());
        if (mAlwaysLogList || mIterationCount % 10 == 0) {
            Trace.beginSection("ShadeListBuilder.logFinalList");
            mLogger.logFinalList(mNotifList);
            Trace.endSection();
        }
        Trace.endSection();
        mPipelineState.setState(STATE_IDLE);
        mIterationCount++;
        Trace.endSection();
    }

    private void notifySectionEntriesUpdated() {
        Trace.beginSection("ShadeListBuilder.notifySectionEntriesUpdated");
        mTempSectionMembers.clear();
        for (NotifSection section : mNotifSections) {
            for (ListEntry entry : mNotifList) {
                if (section == entry.getSection()) {
                    mTempSectionMembers.add(entry);
                }
            }
            section.getSectioner().onEntriesUpdated(mTempSectionMembers);
            mTempSectionMembers.clear();
        }
        Trace.endSection();
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
        }

        mNotifList.clear();
    }

    private void filterNotifs(
            Collection<? extends ListEntry> entries,
            List<ListEntry> out,
            List<NotifFilter> filters) {
        Trace.beginSection("ShadeListBuilder.filterNotifs");
        final long now = mSystemClock.uptimeMillis();
        for (ListEntry entry : entries) {
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
        Trace.endSection();
    }

    private void groupNotifs(List<ListEntry> entries, List<ListEntry> out) {
        Trace.beginSection("ShadeListBuilder.groupNotifs");
        for (ListEntry listEntry : entries) {
            // since grouping hasn't happened yet, all notifs are NotificationEntries
            NotificationEntry entry = (NotificationEntry) listEntry;
            if (entry.getSbn().isGroup()) {
                final String topLevelKey = entry.getSbn().getGroupKey();

                GroupEntry group = mGroups.get(topLevelKey);
                if (group == null) {
                    group = new GroupEntry(topLevelKey, mSystemClock.uptimeMillis());
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
                        mLogger.logDuplicateSummary(mIterationCount, group, existingSummary, entry);

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
        Trace.endSection();
    }

    private void stabilizeGroupingNotifs(List<ListEntry> topLevelList) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            return;
        }
        Trace.beginSection("ShadeListBuilder.stabilizeGroupingNotifs");

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
        Trace.endSection();
    }

    /**
     * Returns true if the group change was suppressed, else false
     */
    private boolean maybeSuppressGroupChange(NotificationEntry entry, List<ListEntry> out) {
        final GroupEntry prevParent = entry.getPreviousAttachState().getParent();
        if (prevParent == null) {
            // New entries are always allowed.
            return false;
        }
        final GroupEntry assignedParent = entry.getParent();
        if (prevParent == assignedParent) {
            // Nothing to change.
            return false;
        }
        if (prevParent != ROOT_ENTRY && prevParent.getParent() == null) {
            // Previous parent was a group, which has been removed (hence, its parent is null).
            // Always allow this group change, otherwise the child will remain attached to the
            // removed group and be removed from the shade until visual stability ends.
            return false;
        }
        // TODO: Rather than perform "half" of the move here and require the caller remove the child
        //  from the assignedParent, ideally we would have an atomic "move" operation.
        if (!getStabilityManager().isGroupChangeAllowed(entry.getRepresentativeEntry())) {
            entry.getAttachState().getSuppressedChanges().setParent(assignedParent);
            entry.setParent(prevParent);
            if (prevParent == ROOT_ENTRY) {
                out.add(entry);
            } else {
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
        Trace.beginSection("ShadeListBuilder.promoteNotifs");
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
        Trace.endSection();
    }

    private void pruneIncompleteGroups(List<ListEntry> shadeList) {
        Trace.beginSection("ShadeListBuilder.pruneIncompleteGroups");
        // Any group which lost a child on this run to stability is exempt from being pruned or
        //  having its summary promoted, regardless of how many children it has
        Set<String> groupsWithChildrenLostToStability =
                getGroupsWithChildrenLostToStability(shadeList);
        // Groups with children lost to stability are exempt from summary promotion.
        ArraySet<String> groupsExemptFromSummaryPromotion =
                new ArraySet<>(groupsWithChildrenLostToStability);
        // Any group which lost a child to filtering or promotion is exempt from having its summary
        // promoted when it has no attached children.
        addGroupsWithChildrenLostToFiltering(groupsExemptFromSummaryPromotion);
        addGroupsWithChildrenLostToPromotion(shadeList, groupsExemptFromSummaryPromotion);

        // Iterate backwards, so that we can remove elements without affecting indices of
        // yet-to-be-accessed entries.
        for (int i = shadeList.size() - 1; i >= 0; i--) {
            final ListEntry tle = shadeList.get(i);

            if (tle instanceof GroupEntry) {
                final GroupEntry group = (GroupEntry) tle;
                final List<NotificationEntry> children = group.getRawChildren();
                final boolean hasSummary = group.getSummary() != null;

                if (hasSummary && children.size() == 0) {
                    if (groupsExemptFromSummaryPromotion.contains(group.getKey())) {
                        // This group lost a child on this run to promotion or stability, so it is
                        //  exempt from having its summary promoted to the top level, so prune it.
                        //  It has no children, so it will just vanish.
                        pruneGroupAtIndexAndPromoteAnyChildren(shadeList, group, i);
                    } else {
                        // For any other summary with no children, promote the summary.
                        pruneGroupAtIndexAndPromoteSummary(shadeList, group, i);
                    }
                } else if (!hasSummary) {
                    // If the group doesn't provide a summary, ignore it and add
                    //  any children it may have directly to top-level.
                    pruneGroupAtIndexAndPromoteAnyChildren(shadeList, group, i);
                } else if (children.size() < MIN_CHILDREN_FOR_GROUP) {
                    // This group has a summary and insufficient, but nonzero children.
                    checkState(hasSummary, "group must have summary at this point");
                    checkState(!children.isEmpty(), "empty group should have been promoted");

                    if (groupsWithChildrenLostToStability.contains(group.getKey())) {
                        // This group lost a child on this run to stability, so it is exempt from
                        //  the "min children" requirement; keep it around in case more children are
                        //  added before changes are allowed again.
                        group.getAttachState().getSuppressedChanges().setWasPruneSuppressed(true);
                        continue;
                    }
                    if (group.wasAttachedInPreviousPass()
                            && !getStabilityManager().isGroupPruneAllowed(group)) {
                        checkState(!children.isEmpty(), "empty group should have been pruned");
                        // This group was previously attached and group changes aren't
                        //  allowed; keep it around until group changes are allowed again.
                        group.getAttachState().getSuppressedChanges().setWasPruneSuppressed(true);
                        continue;
                    }

                    // The group is too small, ignore it and add
                    // its children (if any) directly to top-level.
                    pruneGroupAtIndexAndPromoteAnyChildren(shadeList, group, i);
                }
            }
        }
        Trace.endSection();
    }

    private void pruneGroupAtIndexAndPromoteSummary(List<ListEntry> shadeList,
            GroupEntry group, int index) {
        // Validate that the group has no children
        checkArgument(group.getChildren().isEmpty(), "group should have no children");

        NotificationEntry summary = group.getSummary();
        summary.setParent(ROOT_ENTRY);
        // The list may be sorted; replace the group with the summary, in its place
        ListEntry oldEntry = shadeList.set(index, summary);

        // Validate that the replaced entry was the group entry
        checkState(oldEntry == group);

        group.setSummary(null);
        annulAddition(group, shadeList);
        summary.getAttachState().setGroupPruneReason(
                "SUMMARY with no children @ " + mPipelineState.getStateName());
    }

    private void pruneGroupAtIndexAndPromoteAnyChildren(List<ListEntry> shadeList,
            GroupEntry group, int index) {
        // REMOVE the GroupEntry at this index
        ListEntry oldEntry = shadeList.remove(index);

        // Validate that the replaced entry was the group entry
        checkState(oldEntry == group);

        List<NotificationEntry> children = group.getRawChildren();
        boolean hasSummary = group.getSummary() != null;

        // Remove the group summary, if present, and leave detached.
        if (hasSummary) {
            final NotificationEntry summary = group.getSummary();
            group.setSummary(null);
            annulAddition(summary, shadeList);
            summary.getAttachState().setGroupPruneReason(
                    "SUMMARY with too few children @ " + mPipelineState.getStateName());
        }

        // Promote any children
        if (!children.isEmpty()) {
            // create the reason we will report on the child for why its group was pruned.
            String childReason = hasSummary
                    ? ("CHILD with " + (children.size() - 1) + " siblings @ "
                        + mPipelineState.getStateName())
                    : ("CHILD with no summary @ " + mPipelineState.getStateName());

            // Remove children from the group and add them to the shadeList.
            for (int j = 0; j < children.size(); j++) {
                final NotificationEntry child = children.get(j);
                child.setParent(ROOT_ENTRY);
                child.getAttachState().setGroupPruneReason(requireNonNull(childReason));
            }
            // The list may be sorted, so add the children in order where the group was.
            shadeList.addAll(index, children);
            children.clear();
        }

        annulAddition(group, shadeList);
    }

    /**
     * Collect the keys of any groups which have already lost a child to stability this run.
     *
     * If stability is being enforced, then {@link #stabilizeGroupingNotifs(List)} might have
     * detached some children from their groups and left them at the top level because the child was
     * previously attached at the top level.  Doing so would set the
     * {@link SuppressedAttachState#getParent() suppressed parent} for the current attach state.
     *
     * If we've already removed a child from this group, we don't want to remove any more children
     * from the group (even if that would leave only a single notification in the group) because
     * that could cascade over multiple runs and allow a large group of notifications all show up as
     * top level (ungrouped) notifications.
     */
    @NonNull
    private Set<String> getGroupsWithChildrenLostToStability(List<ListEntry> shadeList) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            return Collections.emptySet();
        }
        ArraySet<String> groupsWithChildrenLostToStability = new ArraySet<>();
        for (int i = 0; i < shadeList.size(); i++) {
            final ListEntry tle = shadeList.get(i);
            final GroupEntry suppressedParent =
                    tle.getAttachState().getSuppressedChanges().getParent();
            if (suppressedParent != null) {
                // This top-level-entry was supposed to be attached to this group,
                //  so mark the group as having lost a child to stability.
                groupsWithChildrenLostToStability.add(suppressedParent.getKey());
            }
        }
        return groupsWithChildrenLostToStability;
    }

    /**
     * Collect the keys of any groups which have already lost a child to a {@link NotifPromoter}
     * this run.
     *
     * These groups will be exempt from appearing without any children.
     */
    private void addGroupsWithChildrenLostToPromotion(List<ListEntry> shadeList, Set<String> out) {
        for (int i = 0; i < shadeList.size(); i++) {
            final ListEntry tle = shadeList.get(i);
            if (tle.getAttachState().getPromoter() != null) {
                // This top-level-entry was part of a group, but was promoted out of it.
                final String groupKey = tle.getRepresentativeEntry().getSbn().getGroupKey();
                out.add(groupKey);
            }
        }
    }

    /**
     * Collect the keys of any groups which have already lost a child to a {@link NotifFilter}
     * this run.
     *
     * These groups will be exempt from appearing without any children.
     */
    private void addGroupsWithChildrenLostToFiltering(Set<String> out) {
        for (ListEntry tle : mAllEntries) {
            StatusBarNotification sbn = tle.getRepresentativeEntry().getSbn();
            if (sbn.isGroup()
                    && !sbn.getNotification().isGroupSummary()
                    && tle.getAttachState().getExcludingFilter() != null) {
                out.add(sbn.getGroupKey());
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

        if (entry.getParent() == null) {
            throw new IllegalStateException(
                    "Cannot nullify addition of " + entry.getKey() + ": no parent.");
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
        entry.getAttachState().detach();
    }

    private void assignSections() {
        Trace.beginSection("ShadeListBuilder.assignSections");
        // Assign sections to top-level elements and their children
        for (ListEntry entry : mNotifList) {
            NotifSection section = applySections(entry);
            if (entry instanceof GroupEntry) {
                GroupEntry parent = (GroupEntry) entry;
                for (NotificationEntry child : parent.getChildren()) {
                    setEntrySection(child, section);
                }
            }
        }
        Trace.endSection();
    }

    private void sortListAndGroups() {
        Trace.beginSection("ShadeListBuilder.sortListAndGroups");
        sortWithSemiStableSort();
        Trace.endSection();
    }

    private void sortWithSemiStableSort() {
        // Sort each group's children
        boolean allSorted = true;
        for (ListEntry entry : mNotifList) {
            if (entry instanceof GroupEntry) {
                GroupEntry parent = (GroupEntry) entry;
                allSorted &= sortGroupChildren(parent.getRawChildren());
            }
        }
        // Sort each section within the top level list
        mNotifList.sort(mTopLevelComparator);
        if (!getStabilityManager().isEveryChangeAllowed()) {
            for (List<ListEntry> subList : getSectionSubLists(mNotifList)) {
                allSorted &= mSemiStableSort.stabilizeTo(subList, mStableOrder, mNewNotifList);
            }
            applyNewNotifList();
        }
        assignIndexes(mNotifList);
        if (!allSorted) {
            // Report suppressed order changes
            getStabilityManager().onEntryReorderSuppressed();
        }
    }

    private Iterable<List<ListEntry>> getSectionSubLists(List<ListEntry> entries) {
        return ShadeListBuilderHelper.INSTANCE.getSectionSubLists(entries);
    }

    private boolean sortGroupChildren(List<NotificationEntry> entries) {
        if (getStabilityManager().isEveryChangeAllowed()) {
            entries.sort(mGroupChildrenComparator);
            return true;
        } else {
            return mSemiStableSort.sort(entries, mStableOrder, mGroupChildrenComparator);
        }
    }

    /** Determine whether the items in the list are sorted according to the comparator */
    @VisibleForTesting
    public static <T> boolean isSorted(List<T> items, Comparator<? super T> comparator) {
        if (items.size() <= 1) {
            return true;
        }
        Iterator<T> iterator = items.iterator();
        T previous = iterator.next();
        T current;
        while (iterator.hasNext()) {
            current = iterator.next();
            if (comparator.compare(previous, current) > 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    /**
     * Assign the index of each notification relative to the total order
     */
    private void assignIndexes(List<ListEntry> notifList) {
        if (notifList.size() == 0) return;
        NotifSection currentSection = requireNonNull(notifList.get(0).getSection());
        int sectionMemberIndex = 0;
        for (int i = 0; i < notifList.size(); i++) {
            final ListEntry entry = notifList.get(i);
            NotifSection section = requireNonNull(entry.getSection());
            if (section.getIndex() != currentSection.getIndex()) {
                sectionMemberIndex = 0;
                currentSection = section;
            }
            entry.getAttachState().setStableIndex(sectionMemberIndex++);
            if (entry instanceof GroupEntry) {
                final GroupEntry parent = (GroupEntry) entry;
                final NotificationEntry summary = parent.getSummary();
                if (summary != null) {
                    summary.getAttachState().setStableIndex(sectionMemberIndex++);
                }
                for (NotificationEntry child : parent.getChildren()) {
                    child.getAttachState().setStableIndex(sectionMemberIndex++);
                }
            }
        }
    }

    private void freeEmptyGroups() {
        Trace.beginSection("ShadeListBuilder.freeEmptyGroups");
        mGroups.values().removeIf(ge -> ge.getSummary() == null && ge.getChildren().isEmpty());
        Trace.endSection();
    }

    private void logChanges() {
        Trace.beginSection("ShadeListBuilder.logChanges");
        for (NotificationEntry entry : mAllEntries) {
            logAttachStateChanges(entry);
        }
        for (GroupEntry group : mGroups.values()) {
            logAttachStateChanges(group);
        }
        Trace.endSection();
    }

    private void logAttachStateChanges(ListEntry entry) {

        final ListAttachState curr = entry.getAttachState();
        final ListAttachState prev = entry.getPreviousAttachState();

        if (!Objects.equals(curr, prev)) {
            mLogger.logEntryAttachStateChanged(
                    mIterationCount,
                    entry,
                    prev.getParent(),
                    curr.getParent());

            if (curr.getParent() != prev.getParent()) {
                mLogger.logParentChanged(mIterationCount, prev.getParent(), curr.getParent());
            }

            GroupEntry currSuppressedParent = curr.getSuppressedChanges().getParent();
            GroupEntry prevSuppressedParent = prev.getSuppressedChanges().getParent();
            if (currSuppressedParent != null && (prevSuppressedParent == null
                    || !prevSuppressedParent.getKey().equals(currSuppressedParent.getKey()))) {
                mLogger.logParentChangeSuppressedStarted(
                        mIterationCount,
                        currSuppressedParent,
                        curr.getParent());
            }
            if (prevSuppressedParent != null && currSuppressedParent == null) {
                mLogger.logParentChangeSuppressedStopped(
                        mIterationCount,
                        prevSuppressedParent,
                        prev.getParent());
            }

            if (curr.getSuppressedChanges().getSection() != null) {
                mLogger.logSectionChangeSuppressed(
                        mIterationCount,
                        curr.getSuppressedChanges().getSection(),
                        curr.getSection());
            }

            if (curr.getSuppressedChanges().getWasPruneSuppressed()) {
                mLogger.logGroupPruningSuppressed(
                        mIterationCount,
                        curr.getParent());
            }

            if (!Objects.equals(curr.getGroupPruneReason(), prev.getGroupPruneReason())) {
                mLogger.logPrunedReasonChanged(
                        mIterationCount,
                        prev.getGroupPruneReason(),
                        curr.getGroupPruneReason());
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
        }
    }

    private void onBeginRun() {
        getStabilityManager().onBeginRun();
    }

    private void cleanupPluggables() {
        Trace.beginSection("ShadeListBuilder.cleanupPluggables");
        callOnCleanup(mNotifPreGroupFilters);
        callOnCleanup(mNotifPromoters);
        callOnCleanup(mNotifFinalizeFilters);
        callOnCleanup(mNotifComparators);

        for (int i = 0; i < mNotifSections.size(); i++) {
            final NotifSection notifSection = mNotifSections.get(i);
            notifSection.getSectioner().onCleanup();
            final NotifComparator comparator = notifSection.getComparator();
            if (comparator != null) {
                comparator.onCleanup();
            }
        }

        callOnCleanup(List.of(getStabilityManager()));
        Trace.endSection();
    }

    private void callOnCleanup(List<? extends Pluggable<?>> pluggables) {
        for (int i = 0; i < pluggables.size(); i++) {
            pluggables.get(i).onCleanup();
        }
    }

    @Nullable
    private NotifComparator getSectionComparator(
            @NonNull ListEntry o1, @NonNull ListEntry o2) {
        final NotifSection section = o1.getSection();
        if (section != o2.getSection()) {
            throw new RuntimeException("Entry ordering should only be done within sections");
        }
        if (section != null) {
            return section.getComparator();
        }
        return null;
    }

    private final Comparator<ListEntry> mTopLevelComparator = (o1, o2) -> {
        int cmp = Integer.compare(
                o1.getSectionIndex(),
                o2.getSectionIndex());
        if (cmp != 0) return cmp;

        NotifComparator sectionComparator = getSectionComparator(o1, o2);
        if (sectionComparator != null) {
            cmp = sectionComparator.compare(o1, o2);
            if (cmp != 0) return cmp;
        }

        for (int i = 0; i < mNotifComparators.size(); i++) {
            cmp = mNotifComparators.get(i).compare(o1, o2);
            if (cmp != 0) return cmp;
        }

        cmp = Integer.compare(
                o1.getRepresentativeEntry().getRanking().getRank(),
                o2.getRepresentativeEntry().getRanking().getRank());
        if (cmp != 0) return cmp;

        cmp = -1 * Long.compare(
                o1.getRepresentativeEntry().getSbn().getNotification().when,
                o2.getRepresentativeEntry().getSbn().getNotification().when);
        return cmp;
    };


    private final Comparator<NotificationEntry> mGroupChildrenComparator = (o1, o2) -> {
        int cmp = Integer.compare(
                o1.getRepresentativeEntry().getRanking().getRank(),
                o2.getRepresentativeEntry().getRanking().getRank());
        if (cmp != 0) return cmp;

        cmp = -1 * Long.compare(
                o1.getRepresentativeEntry().getSbn().getNotification().when,
                o2.getRepresentativeEntry().getSbn().getNotification().when);
        return cmp;
    };

    @Nullable
    private Integer getStableOrderRank(ListEntry entry) {
        if (getStabilityManager().isEntryReorderingAllowed(entry)) {
            // let the stability manager constrain or allow reordering
            return null;
        }
        if (entry.getAttachState().getSectionIndex()
                != entry.getPreviousAttachState().getSectionIndex()) {
            // stable index is only valid within the same section; otherwise we allow reordering
            return null;
        }
        final int stableIndex = entry.getPreviousAttachState().getStableIndex();
        return stableIndex == -1 ? null : stableIndex;
    }

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
        if (entry.wasAttachedInPreviousPass() && newSection != prevAttachState.getSection()) {

            // are section changes allowed?
            if (!getStabilityManager().isSectionChangeAllowed(entry.getRepresentativeEntry())) {
                // record the section that we wanted to change to
                entry.getAttachState().getSuppressedChanges().setSection(newSection);

                // keep the previous section
                finalSection = prevAttachState.getSection();
            }
        }

        setEntrySection(entry, finalSection);
        return finalSection;
    }

    private void setEntrySection(ListEntry entry, NotifSection finalSection) {
        entry.getAttachState().setSection(finalSection);
        NotificationEntry representativeEntry = entry.getRepresentativeEntry();
        if (representativeEntry != null) {
            representativeEntry.getAttachState().setSection(finalSection);
            if (finalSection != null) {
                representativeEntry.setBucket(finalSection.getBucket());
            }
        }
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

    private void rebuildListIfBefore(@PipelineState.StateName int rebuildState) {
        final @PipelineState.StateName int currentState = mPipelineState.getState();

        // If the pipeline is idle, requesting an invalidation is always okay, and starts a new run.
        if (currentState == STATE_IDLE) {
            scheduleRebuild(/* reentrant = */ false, rebuildState);
            return;
        }

        // If the pipeline is running, it is okay to request an invalidation of a *later* stage.
        // Since the current pipeline run hasn't run it yet, no new pipeline run is needed.
        if (rebuildState > currentState) {
            return;
        }

        // If the pipeline is running, it is bad to request an invalidation of *earlier* stages or
        // the *current* stage; this will run the pipeline more often than needed, and may even
        // cause an infinite loop of pipeline runs.
        //
        // Unfortunately, there are some unfixed bugs that cause reentrant pipeline runs, so we keep
        // a counter and allow a few reentrant runs in a row between any two non-reentrant runs.
        //
        // It is technically possible for a *pair* of invalidations, one reentrant and one not, to
        // trigger *each other*, alternating responsibility for pipeline runs in an infinite loop
        // but constantly resetting the reentrant run counter. Hopefully that doesn't happen.
        scheduleRebuild(/* reentrant = */ true, rebuildState);
    }

    private void scheduleRebuild(boolean reentrant) {
        scheduleRebuild(reentrant, STATE_IDLE);
    }

    private void scheduleRebuild(boolean reentrant, @PipelineState.StateName int rebuildState) {
        if (!reentrant) {
            mConsecutiveReentrantRebuilds = 0;
            mChoreographer.schedule();
            return;
        }

        final @PipelineState.StateName int currentState = mPipelineState.getState();

        final String rebuildStateName = PipelineState.getStateName(rebuildState);
        final String currentStateName = PipelineState.getStateName(currentState);
        final IllegalStateException exception = new IllegalStateException(
                "Reentrant notification pipeline rebuild of state " + rebuildStateName
                        + " while pipeline in state " + currentStateName + ".");

        mConsecutiveReentrantRebuilds++;

        if (mConsecutiveReentrantRebuilds > MAX_CONSECUTIVE_REENTRANT_REBUILDS) {
            Log.e(TAG, "Crashing after more than " + MAX_CONSECUTIVE_REENTRANT_REBUILDS
                    + " consecutive reentrant notification pipeline rebuilds.", exception);
            throw exception;
        }

        Log.wtf(TAG, "Allowing " + mConsecutiveReentrantRebuilds
                + " consecutive reentrant notification pipeline rebuild(s).", exception);
        mChoreographer.schedule();
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
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeTransformGroups");
        for (int i = 0; i < mOnBeforeTransformGroupsListeners.size(); i++) {
            mOnBeforeTransformGroupsListeners.get(i).onBeforeTransformGroups(entries);
        }
        Trace.endSection();
    }

    private void dispatchOnBeforeSort(List<ListEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeSort");
        for (int i = 0; i < mOnBeforeSortListeners.size(); i++) {
            mOnBeforeSortListeners.get(i).onBeforeSort(entries);
        }
        Trace.endSection();
    }

    private void dispatchOnBeforeFinalizeFilter(List<ListEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeFinalizeFilter");
        for (int i = 0; i < mOnBeforeFinalizeFilterListeners.size(); i++) {
            mOnBeforeFinalizeFilterListeners.get(i).onBeforeFinalizeFilter(entries);
        }
        Trace.endSection();
    }

    private void dispatchOnBeforeRenderList(List<ListEntry> entries) {
        Trace.beginSection("ShadeListBuilder.dispatchOnBeforeRenderList");
        for (int i = 0; i < mOnBeforeRenderListListeners.size(); i++) {
            mOnBeforeRenderListListeners.get(i).onBeforeRenderList(entries);
        }
        Trace.endSection();
    }

    @Override
    public void dump(PrintWriter pw, @NonNull String[] args) {
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

    @Override
    public void dumpPipeline(@NonNull PipelineDumper d) {
        d.dump("choreographer", mChoreographer);
        d.dump("notifPreGroupFilters", mNotifPreGroupFilters);
        d.dump("onBeforeTransformGroupsListeners", mOnBeforeTransformGroupsListeners);
        d.dump("notifPromoters", mNotifPromoters);
        d.dump("onBeforeSortListeners", mOnBeforeSortListeners);
        d.dump("notifSections", mNotifSections);
        d.dump("notifComparators", mNotifComparators);
        d.dump("onBeforeFinalizeFilterListeners", mOnBeforeFinalizeFilterListeners);
        d.dump("notifFinalizeFilters", mNotifFinalizeFilters);
        d.dump("onBeforeRenderListListeners", mOnBeforeRenderListListeners);
        d.dump("onRenderListListener", mOnRenderListListener);
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

    private static final NotifSectioner DEFAULT_SECTIONER = new NotifSectioner("UnknownSection",
            NotificationPriorityBucketKt.BUCKET_UNKNOWN) {
        @Override
        public boolean isInSection(ListEntry entry) {
            return true;
        }
    };

    private static final int MIN_CHILDREN_FOR_GROUP = 2;

    private static final String TAG = "ShadeListBuilder";
}
