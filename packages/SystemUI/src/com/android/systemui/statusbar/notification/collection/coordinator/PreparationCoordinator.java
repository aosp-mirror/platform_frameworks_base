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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.android.systemui.statusbar.notification.NotificationUtils.logKey;
import static com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.statusbar.notification.collection.GroupEntry;
import com.android.systemui.statusbar.notification.collection.ListEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.inflation.BindEventManagerImpl;
import com.android.systemui.statusbar.notification.collection.inflation.NotifInflater;
import com.android.systemui.statusbar.notification.collection.inflation.NotifUiAdjustment;
import com.android.systemui.statusbar.notification.collection.inflation.NotifUiAdjustmentProvider;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotifViewBarn;
import com.android.systemui.statusbar.notification.collection.render.NotifViewController;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager;
import com.android.systemui.statusbar.notification.row.NotifInflationErrorManager.NotifInflationErrorListener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * Kicks off core notification inflation and view rebinding when a notification is added or updated.
 * Aborts inflation when a notification is removed.
 *
 * If a notification was uninflated, this coordinator will filter the notification out from the
 * {@link ShadeListBuilder} until it is inflated.
 */
@CoordinatorScope
public class PreparationCoordinator implements Coordinator {
    private static final String TAG = "PreparationCoordinator";

    private final PreparationCoordinatorLogger mLogger;
    private final NotifInflater mNotifInflater;
    private final NotifInflationErrorManager mNotifErrorManager;
    private final NotifViewBarn mViewBarn;
    private final NotifUiAdjustmentProvider mAdjustmentProvider;
    private final ArrayMap<NotificationEntry, Integer> mInflationStates = new ArrayMap<>();

    /**
     * The map of notifications to the NotifUiAdjustment (i.e. parameters) that were calculated
     * when the inflation started.  If an update of any kind results in the adjustment changing,
     * then the row must be reinflated.  If the row is being inflated, then the inflation must be
     * aborted and restarted.
     */
    private final ArrayMap<NotificationEntry, NotifUiAdjustment> mInflationAdjustments =
            new ArrayMap<>();

    /**
     * The set of notifications that are currently inflating something. Note that this is
     * separate from inflation state as a view could either be uninflated or inflated and still be
     * inflating something.
     */
    private final ArraySet<NotificationEntry> mInflatingNotifs = new ArraySet<>();

    private final IStatusBarService mStatusBarService;

    /**
     * The number of children in a group we actually keep inflated since we don't actually show
     * all the children and don't need every child inflated at all times.
     */
    private final int mChildBindCutoff;

    /** How long we can delay a group while waiting for all children to inflate */
    private final long mMaxGroupInflationDelay;
    private final BindEventManagerImpl mBindEventManager;

    @Inject
    public PreparationCoordinator(
            PreparationCoordinatorLogger logger,
            NotifInflater notifInflater,
            NotifInflationErrorManager errorManager,
            NotifViewBarn viewBarn,
            NotifUiAdjustmentProvider adjustmentProvider,
            IStatusBarService service,
            BindEventManagerImpl bindEventManager) {
        this(
                logger,
                notifInflater,
                errorManager,
                viewBarn,
                adjustmentProvider,
                service,
                bindEventManager,
                CHILD_BIND_CUTOFF,
                MAX_GROUP_INFLATION_DELAY);
    }

    @VisibleForTesting
    PreparationCoordinator(
            PreparationCoordinatorLogger logger,
            NotifInflater notifInflater,
            NotifInflationErrorManager errorManager,
            NotifViewBarn viewBarn,
            NotifUiAdjustmentProvider adjustmentProvider,
            IStatusBarService service,
            BindEventManagerImpl bindEventManager,
            int childBindCutoff,
            long maxGroupInflationDelay) {
        mLogger = logger;
        mNotifInflater = notifInflater;
        mNotifErrorManager = errorManager;
        mViewBarn = viewBarn;
        mAdjustmentProvider = adjustmentProvider;
        mStatusBarService = service;
        mChildBindCutoff = childBindCutoff;
        mMaxGroupInflationDelay = maxGroupInflationDelay;
        mBindEventManager = bindEventManager;
    }

    @Override
    public void attach(NotifPipeline pipeline) {
        mNotifErrorManager.addInflationErrorListener(mInflationErrorListener);
        mAdjustmentProvider.addDirtyListener(
                () -> mNotifInflatingFilter.invalidateList("adjustmentProviderChanged"));

        pipeline.addCollectionListener(mNotifCollectionListener);
        // Inflate after grouping/sorting since that affects what views to inflate.
        pipeline.addOnBeforeFinalizeFilterListener(this::inflateAllRequiredViews);
        pipeline.addFinalizeFilter(mNotifInflationErrorFilter);
        pipeline.addFinalizeFilter(mNotifInflatingFilter);
    }

    private final NotifCollectionListener mNotifCollectionListener = new NotifCollectionListener() {

        @Override
        public void onEntryInit(NotificationEntry entry) {
            mInflationStates.put(entry, STATE_UNINFLATED);
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            abortInflation(entry, "entryUpdated");
            @InflationState int state = getInflationState(entry);
            if (state == STATE_INFLATED) {
                mInflationStates.put(entry, STATE_INFLATED_INVALID);
            } else if (state == STATE_ERROR) {
                // Updated so maybe it won't error out now.
                mInflationStates.put(entry, STATE_UNINFLATED);
            }
        }

        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            abortInflation(entry, "entryRemoved reason=" + reason);
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mInflationStates.remove(entry);
            mViewBarn.removeViewForEntry(entry);
            mInflationAdjustments.remove(entry);
        }
    };

    private final NotifFilter mNotifInflationErrorFilter = new NotifFilter(
            TAG + "InflationError") {
        /**
         * Filters out notifications that threw an error when attempting to inflate.
         */
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return getInflationState(entry) == STATE_ERROR;
        }
    };

    private final NotifFilter mNotifInflatingFilter = new NotifFilter(TAG + "Inflating") {
        private final Map<GroupEntry, Boolean> mIsDelayedGroupCache = new ArrayMap<>();

        /**
         * Filters out notifications that either (a) aren't inflated or (b) are part of a group
         * that isn't completely inflated yet
         */
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            final GroupEntry parent = requireNonNull(entry.getParent());
            Boolean isMemberOfDelayedGroup = mIsDelayedGroupCache.get(parent);
            if (isMemberOfDelayedGroup == null) {
                isMemberOfDelayedGroup = shouldWaitForGroupToInflate(parent, now);
                mIsDelayedGroupCache.put(parent, isMemberOfDelayedGroup);
            }

            return !isInflated(entry) || isMemberOfDelayedGroup;
        }

        @Override
        public void onCleanup() {
            mIsDelayedGroupCache.clear();
        }
    };

    private final NotifInflationErrorListener mInflationErrorListener =
            new NotifInflationErrorListener() {
        @Override
        public void onNotifInflationError(NotificationEntry entry, Exception e) {
            mViewBarn.removeViewForEntry(entry);
            mInflationStates.put(entry, STATE_ERROR);
            try {
                final StatusBarNotification sbn = entry.getSbn();
                // report notification inflation errors back up
                // to notification delegates
                mStatusBarService.onNotificationError(
                        sbn.getPackageName(),
                        sbn.getTag(),
                        sbn.getId(),
                        sbn.getUid(),
                        sbn.getInitialPid(),
                        e.getMessage(),
                        sbn.getUser().getIdentifier());
            } catch (RemoteException ex) {
                // System server is dead, nothing to do about that
            }
            mNotifInflationErrorFilter.invalidateList("onNotifInflationError for " + logKey(entry));
        }

        @Override
        public void onNotifInflationErrorCleared(NotificationEntry entry) {
            mNotifInflationErrorFilter.invalidateList(
                    "onNotifInflationErrorCleared for " + logKey(entry));
        }
    };

    private void inflateAllRequiredViews(List<ListEntry> entries) {
        for (int i = 0, size = entries.size(); i < size; i++) {
            ListEntry entry = entries.get(i);
            if (entry instanceof GroupEntry) {
                GroupEntry groupEntry = (GroupEntry) entry;
                inflateRequiredGroupViews(groupEntry);
            } else {
                NotificationEntry notifEntry = (NotificationEntry) entry;
                inflateRequiredNotifViews(notifEntry);
            }
        }
    }

    private void inflateRequiredGroupViews(GroupEntry groupEntry) {
        NotificationEntry summary = groupEntry.getSummary();
        List<NotificationEntry> children = groupEntry.getChildren();
        inflateRequiredNotifViews(summary);
        for (int j = 0; j < children.size(); j++) {
            NotificationEntry child = children.get(j);
            boolean childShouldBeBound = j < mChildBindCutoff;
            if (childShouldBeBound) {
                inflateRequiredNotifViews(child);
            } else {
                if (mInflatingNotifs.contains(child)) {
                    abortInflation(child, "Past last visible group child");
                }
                if (isInflated(child)) {
                    // TODO: May want to put an animation hint here so view manager knows to treat
                    //  this differently from a regular removal animation
                    freeNotifViews(child, "Past last visible group child");
                }
            }
        }
    }

    private void inflateRequiredNotifViews(NotificationEntry entry) {
        NotifUiAdjustment newAdjustment = mAdjustmentProvider.calculateAdjustment(entry);
        if (mInflatingNotifs.contains(entry)) {
            // Already inflating this entry
            String errorIfNoOldAdjustment = "Inflating notification has no adjustments";
            if (needToReinflate(entry, newAdjustment, errorIfNoOldAdjustment)) {
                inflateEntry(entry, newAdjustment, "adjustment changed while inflating");
            }
            return;
        }
        @InflationState int state = mInflationStates.get(entry);
        switch (state) {
            case STATE_UNINFLATED:
                inflateEntry(entry, newAdjustment, "entryAdded");
                break;
            case STATE_INFLATED_INVALID:
                rebind(entry, newAdjustment, "entryUpdated");
                break;
            case STATE_INFLATED:
                String errorIfNoOldAdjustment = "Fully inflated notification has no adjustments";
                if (needToReinflate(entry, newAdjustment, errorIfNoOldAdjustment)) {
                    rebind(entry, newAdjustment, "adjustment changed after inflated");
                }
                break;
            case STATE_ERROR:
                if (needToReinflate(entry, newAdjustment, null)) {
                    inflateEntry(entry, newAdjustment, "adjustment changed after error");
                }
                break;
            default:
                // Nothing to do.
        }
    }

    private boolean needToReinflate(@NonNull NotificationEntry entry,
            @NonNull NotifUiAdjustment newAdjustment, @Nullable String oldAdjustmentMissingError) {
        NotifUiAdjustment oldAdjustment = mInflationAdjustments.get(entry);
        if (oldAdjustment == null) {
            if (oldAdjustmentMissingError == null) {
                return true;
            } else {
                throw new IllegalStateException(oldAdjustmentMissingError);
            }
        }
        return NotifUiAdjustment.needReinflate(oldAdjustment, newAdjustment);
    }

    private void inflateEntry(NotificationEntry entry,
            NotifUiAdjustment newAdjustment,
            String reason) {
        abortInflation(entry, reason);
        mInflationAdjustments.put(entry, newAdjustment);
        mInflatingNotifs.add(entry);
        NotifInflater.Params params = getInflaterParams(newAdjustment, reason);
        mNotifInflater.inflateViews(entry, params, this::onInflationFinished);
    }

    private void rebind(NotificationEntry entry,
            NotifUiAdjustment newAdjustment,
            String reason) {
        mInflationAdjustments.put(entry, newAdjustment);
        mInflatingNotifs.add(entry);
        NotifInflater.Params params = getInflaterParams(newAdjustment, reason);
        mNotifInflater.rebindViews(entry, params, this::onInflationFinished);
    }

    NotifInflater.Params getInflaterParams(NotifUiAdjustment adjustment, String reason) {
        return new NotifInflater.Params(adjustment.isMinimized(), reason);
    }

    private void abortInflation(NotificationEntry entry, String reason) {
        final boolean taskAborted = mNotifInflater.abortInflation(entry);
        final boolean wasInflating = mInflatingNotifs.remove(entry);
        if (taskAborted || wasInflating) {
            mLogger.logInflationAborted(entry, reason);
        }
    }

    private void onInflationFinished(NotificationEntry entry, NotifViewController controller) {
        mLogger.logNotifInflated(entry);
        mInflatingNotifs.remove(entry);
        mViewBarn.registerViewForEntry(entry, controller);
        mInflationStates.put(entry, STATE_INFLATED);
        mBindEventManager.notifyViewBound(entry);
        mNotifInflatingFilter.invalidateList("onInflationFinished for " + logKey(entry));
    }

    private void freeNotifViews(NotificationEntry entry, String reason) {
        mLogger.logFreeNotifViews(entry, reason);
        mViewBarn.removeViewForEntry(entry);
        mNotifInflater.releaseViews(entry);
        // TODO: clear the entry's row here, or even better, stop setting the row on the entry!
        mInflationStates.put(entry, STATE_UNINFLATED);
    }

    private boolean isInflated(NotificationEntry entry) {
        @InflationState int state = getInflationState(entry);
        return (state == STATE_INFLATED) || (state == STATE_INFLATED_INVALID);
    }

    private @InflationState int getInflationState(NotificationEntry entry) {
        Integer stateObj = mInflationStates.get(entry);
        requireNonNull(stateObj,
                "Asking state of a notification preparation coordinator doesn't know about");
        return stateObj;
    }

    private boolean shouldWaitForGroupToInflate(GroupEntry group, long now) {
        if (group == GroupEntry.ROOT_ENTRY || group.wasAttachedInPreviousPass()) {
            return false;
        }
        if (isBeyondGroupInitializationWindow(group, now)) {
            mLogger.logGroupInflationTookTooLong(group);
            return false;
        }
        if (mInflatingNotifs.contains(group.getSummary())) {
            mLogger.logDelayingGroupRelease(group, group.getSummary());
            return true;
        }
        for (NotificationEntry child : group.getChildren()) {
            if (mInflatingNotifs.contains(child) && !child.wasAttachedInPreviousPass()) {
                mLogger.logDelayingGroupRelease(group, child);
                return true;
            }
        }
        mLogger.logDoneWaitingForGroupInflation(group);
        return false;
    }

    private boolean isBeyondGroupInitializationWindow(GroupEntry entry, long now) {
        return now - entry.getCreationTime() > mMaxGroupInflationDelay;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"},
            value = {STATE_UNINFLATED, STATE_INFLATED_INVALID, STATE_INFLATED, STATE_ERROR})
    @interface InflationState {}

    /** The notification has no views attached. */
    private static final int STATE_UNINFLATED = 0;

    /** The notification is inflated. */
    private static final int STATE_INFLATED = 1;

    /**
     * The notification is inflated, but its content may be out-of-date since the notification has
     * been updated.
     */
    private static final int STATE_INFLATED_INVALID = 2;

    /** The notification errored out while inflating */
    private static final int STATE_ERROR = -1;

    /**
     * How big the buffer of extra views we keep around to be ready to show when we do need to
     * dynamically inflate a row.
     */
    private static final int EXTRA_VIEW_BUFFER_COUNT = 1;

    private static final long MAX_GROUP_INFLATION_DELAY = 500;

    private static final int CHILD_BIND_CUTOFF =
            NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED + EXTRA_VIEW_BUFFER_COUNT;
}
