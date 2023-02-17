/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack;

import android.content.res.Resources;
import android.util.MathUtils;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.statusbar.notification.LegacySourceType;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.Roundable;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationRoundnessLogger;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;

import java.io.PrintWriter;
import java.util.HashSet;

import javax.inject.Inject;

/**
 * A class that manages the roundness for notification views
 */
@SysUISingleton
public class NotificationRoundnessManager implements Dumpable {

    private static final String TAG = "NotificationRoundnessManager";
    private static final SourceType DISMISS_ANIMATION = SourceType.from("DismissAnimation");

    private final ExpandableView[] mFirstInSectionViews;
    private final ExpandableView[] mLastInSectionViews;
    private final ExpandableView[] mTmpFirstInSectionViews;
    private final ExpandableView[] mTmpLastInSectionViews;
    private final NotificationRoundnessLogger mNotifLogger;
    private final DumpManager mDumpManager;
    private boolean mExpanded;
    private HashSet<ExpandableView> mAnimatedChildren;
    private Runnable mRoundingChangedCallback;
    private ExpandableNotificationRow mTrackedHeadsUp;
    private float mAppearFraction;
    private boolean mRoundForPulsingViews;
    private boolean mIsClearAllInProgress;

    private ExpandableView mSwipedView = null;
    private Roundable mViewBeforeSwipedView = null;
    private Roundable mViewAfterSwipedView = null;
    private boolean mUseRoundnessSourceTypes;

    @Inject
    NotificationRoundnessManager(
            NotificationSectionsFeatureManager sectionsFeatureManager,
            NotificationRoundnessLogger notifLogger,
            DumpManager dumpManager,
            FeatureFlags featureFlags) {
        int numberOfSections = sectionsFeatureManager.getNumberOfBuckets();
        mFirstInSectionViews = new ExpandableView[numberOfSections];
        mLastInSectionViews = new ExpandableView[numberOfSections];
        mTmpFirstInSectionViews = new ExpandableView[numberOfSections];
        mTmpLastInSectionViews = new ExpandableView[numberOfSections];
        mNotifLogger = notifLogger;
        mDumpManager = dumpManager;
        mUseRoundnessSourceTypes = featureFlags.isEnabled(Flags.USE_ROUNDNESS_SOURCETYPES);

        mDumpManager.registerDumpable(TAG, this);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mFirstInSectionViews: length=" + mFirstInSectionViews.length);
        pw.println(dumpViews(mFirstInSectionViews));
        pw.println("mLastInSectionViews: length=" + mLastInSectionViews.length);
        pw.println(dumpViews(mFirstInSectionViews));
        if (mTrackedHeadsUp != null) {
            pw.println("trackedHeadsUp=" + mTrackedHeadsUp.getEntry());
        }
        pw.println("roundForPulsingViews=" + mRoundForPulsingViews);
        pw.println("isClearAllInProgress=" + mIsClearAllInProgress);
    }

    public void updateView(ExpandableView view, boolean animate) {
        if (mUseRoundnessSourceTypes) return;
        boolean changed = updateViewWithoutCallback(view, animate);
        if (changed) {
            mRoundingChangedCallback.run();
        }
    }

    public boolean isViewAffectedBySwipe(ExpandableView expandableView) {
        return expandableView != null
                && (expandableView == mSwipedView
                || expandableView == mViewBeforeSwipedView
                || expandableView == mViewAfterSwipedView);
    }

    boolean updateViewWithoutCallback(
            ExpandableView view,
            boolean animate) {
        if (mUseRoundnessSourceTypes) return false;
        if (view == null
                || view == mViewBeforeSwipedView
                || view == mViewAfterSwipedView) {
            return false;
        }

        final boolean isTopChanged = view.requestTopRoundness(
                getRoundnessDefaultValue(view, true /* top */),
                LegacySourceType.DefaultValue,
                animate);

        final boolean isBottomChanged = view.requestBottomRoundness(
                getRoundnessDefaultValue(view, /* top = */ false),
                LegacySourceType.DefaultValue,
                animate);

        final boolean isFirstInSection = isFirstInSection(view);
        final boolean isLastInSection = isLastInSection(view);

        view.setFirstInSection(isFirstInSection);
        view.setLastInSection(isLastInSection);

        mNotifLogger.onCornersUpdated(view, isFirstInSection,
                isLastInSection, isTopChanged, isBottomChanged);

        return (isFirstInSection || isLastInSection) && (isTopChanged || isBottomChanged);
    }

    private boolean isFirstInSection(ExpandableView view) {
        if (mUseRoundnessSourceTypes) return false;
        for (int i = 0; i < mFirstInSectionViews.length; i++) {
            if (view == mFirstInSectionViews[i]) {
                return true;
            }
        }
        return false;
    }

    private boolean isLastInSection(ExpandableView view) {
        if (mUseRoundnessSourceTypes) return false;
        for (int i = mLastInSectionViews.length - 1; i >= 0; i--) {
            if (view == mLastInSectionViews[i]) {
                return true;
            }
        }
        return false;
    }

    void setViewsAffectedBySwipe(
            Roundable viewBefore,
            ExpandableView viewSwiped,
            Roundable viewAfter) {
        // This method requires you to change the roundness of the current View targets and reset
        // the roundness of the old View targets (if any) to 0f.
        // To avoid conflicts, it generates a set of old Views and removes the current Views
        // from this set.
        HashSet<Roundable> oldViews = new HashSet<>();
        if (mViewBeforeSwipedView != null) oldViews.add(mViewBeforeSwipedView);
        if (mSwipedView != null) oldViews.add(mSwipedView);
        if (mViewAfterSwipedView != null) oldViews.add(mViewAfterSwipedView);

        final SourceType source;
        if (mUseRoundnessSourceTypes) {
            source = DISMISS_ANIMATION;
        } else {
            source = LegacySourceType.OnDismissAnimation;
        }

        mViewBeforeSwipedView = viewBefore;
        if (viewBefore != null) {
            oldViews.remove(viewBefore);
            viewBefore.requestRoundness(/* top = */ 0f, /* bottom = */ 1f, source);
        }

        mSwipedView = viewSwiped;
        if (viewSwiped != null) {
            oldViews.remove(viewSwiped);
            viewSwiped.requestRoundness(/* top = */ 1f, /* bottom = */ 1f, source);
        }

        mViewAfterSwipedView = viewAfter;
        if (viewAfter != null) {
            oldViews.remove(viewAfter);
            viewAfter.requestRoundness(/* top = */ 1f, /* bottom = */ 0f, source);
        }

        // After setting the current Views, reset the views that are still present in the set.
        for (Roundable oldView : oldViews) {
            oldView.requestRoundnessReset(source);
        }
    }

    void setClearAllInProgress(boolean isClearingAll) {
        mIsClearAllInProgress = isClearingAll;
    }

    /**
     * Check if "Clear all" notifications is in progress.
     */
    public boolean isClearAllInProgress() {
        return mIsClearAllInProgress;
    }

    /**
     * Check if we can request the `Pulsing` roundness for notification.
     */
    public boolean shouldRoundNotificationPulsing() {
        return mRoundForPulsingViews;
    }

    private float getRoundnessDefaultValue(Roundable view, boolean top) {
        if (mUseRoundnessSourceTypes) return 0f;

        if (view == null) {
            return 0f;
        }
        if (view == mViewBeforeSwipedView
                || view == mSwipedView
                || view == mViewAfterSwipedView) {
            return 1f;
        }
        if (view instanceof ExpandableNotificationRow
                && ((ExpandableNotificationRow) view).canViewBeCleared()
                && mIsClearAllInProgress) {
            return 1.0f;
        }
        if (view instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) view;
            if ((expandableView.isPinned()
                    || (expandableView.isHeadsUpAnimatingAway()) && !mExpanded)) {
                return 1.0f;
            }
            if (isFirstInSection(expandableView) && top) {
                return 1.0f;
            }
            if (isLastInSection(expandableView) && !top) {
                return 1.0f;
            }

            if (view == mTrackedHeadsUp) {
                // If we're pushing up on a headsup the appear fraction is < 0 and it needs to
                // still be rounded.
                return MathUtils.saturate(1.0f - mAppearFraction);
            }
            if (expandableView.showingPulsing() && mRoundForPulsingViews) {
                return 1.0f;
            }
            if (expandableView.isChildInGroup()) {
                return 0f;
            }
            final Resources resources = expandableView.getResources();
            return resources.getDimension(R.dimen.notification_corner_radius_small)
                    / resources.getDimension(R.dimen.notification_corner_radius);
        }
        return 0f;
    }

    public void setExpanded(float expandedHeight, float appearFraction) {
        if (mUseRoundnessSourceTypes) return;
        mExpanded = expandedHeight != 0.0f;
        mAppearFraction = appearFraction;
        if (mTrackedHeadsUp != null) {
            updateView(mTrackedHeadsUp, false /* animate */);
        }
    }

    public void updateRoundedChildren(NotificationSection[] sections) {
        if (mUseRoundnessSourceTypes) return;
        boolean anyChanged = false;
        for (int i = 0; i < sections.length; i++) {
            mTmpFirstInSectionViews[i] = mFirstInSectionViews[i];
            mTmpLastInSectionViews[i] = mLastInSectionViews[i];
            mFirstInSectionViews[i] = sections[i].getFirstVisibleChild();
            mLastInSectionViews[i] = sections[i].getLastVisibleChild();
        }
        anyChanged |= handleRemovedOldViews(sections, mTmpFirstInSectionViews, true);
        anyChanged |= handleRemovedOldViews(sections, mTmpLastInSectionViews, false);
        anyChanged |= handleAddedNewViews(sections, mTmpFirstInSectionViews, true);
        anyChanged |= handleAddedNewViews(sections, mTmpLastInSectionViews, false);
        if (anyChanged) {
            mRoundingChangedCallback.run();
        }

        mNotifLogger.onSectionCornersUpdated(sections, anyChanged);
    }

    private boolean handleRemovedOldViews(
            NotificationSection[] sections,
            ExpandableView[] oldViews,
            boolean first) {
        if (mUseRoundnessSourceTypes) return false;
        boolean anyChanged = false;
        for (ExpandableView oldView : oldViews) {
            if (oldView != null) {
                boolean isStillPresent = false;
                boolean adjacentSectionChanged = false;
                for (NotificationSection section : sections) {
                    ExpandableView newView =
                            (first ? section.getFirstVisibleChild()
                                    : section.getLastVisibleChild());
                    if (newView == oldView) {
                        isStillPresent = true;
                        if (oldView.isFirstInSection() != isFirstInSection(oldView)
                                || oldView.isLastInSection() != isLastInSection(oldView)) {
                            adjacentSectionChanged = true;
                        }
                        break;
                    }
                }
                if (!isStillPresent || adjacentSectionChanged) {
                    anyChanged = true;
                    if (!oldView.isRemoved()) {
                        updateViewWithoutCallback(oldView, oldView.isShown());
                    }
                }
            }
        }
        return anyChanged;
    }

    private boolean handleAddedNewViews(
            NotificationSection[] sections,
            ExpandableView[] oldViews,
            boolean first) {
        if (mUseRoundnessSourceTypes) return false;
        boolean anyChanged = false;
        for (NotificationSection section : sections) {
            ExpandableView newView =
                    (first ? section.getFirstVisibleChild() : section.getLastVisibleChild());
            if (newView != null) {
                boolean wasAlreadyPresent = false;
                for (ExpandableView oldView : oldViews) {
                    if (oldView == newView) {
                        wasAlreadyPresent = true;
                        break;
                    }
                }
                if (!wasAlreadyPresent) {
                    anyChanged = true;
                    updateViewWithoutCallback(newView,
                            newView.isShown() && !mAnimatedChildren.contains(newView));
                }
            }
        }
        return anyChanged;
    }

    public void setAnimatedChildren(HashSet<ExpandableView> animatedChildren) {
        mAnimatedChildren = animatedChildren;
    }

    /**
     * Check if the view should be animated
     * @param view target view
     * @return true, if is in the AnimatedChildren set
     */
    public boolean isAnimatedChild(ExpandableView view) {
        return mAnimatedChildren.contains(view);
    }

    public void setOnRoundingChangedCallback(Runnable roundingChangedCallback) {
        mRoundingChangedCallback = roundingChangedCallback;
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow row) {
        ExpandableNotificationRow previous = mTrackedHeadsUp;
        mTrackedHeadsUp = row;
        if (previous != null) {
            updateView(previous, true /* animate */);
        }
    }

    public void setShouldRoundPulsingViews(boolean shouldRoundPulsingViews) {
        mRoundForPulsingViews = shouldRoundPulsingViews;
    }

    private String dumpViews(ExpandableView[] views) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < views.length; i++) {
            if (views[i] == null) continue;

            sb.append("\t")
                    .append("[").append(i).append("] ")
                    .append("isPinned=").append(views[i].isPinned()).append(" ")
                    .append("isFirstInSection=").append(views[i].isFirstInSection()).append(" ")
                    .append("isLastInSection=").append(views[i].isLastInSection()).append(" ");

            if (views[i] instanceof ExpandableNotificationRow) {
                sb.append("entry=");
                dumpEntry(((ExpandableNotificationRow) views[i]).getEntry(), sb);
            }

            sb.append("\n");
        }
        return sb.toString();
    }

    private void dumpEntry(NotificationEntry entry, StringBuilder sb) {
        sb.append("NotificationEntry{key=").append(entry.getKey()).append(" ");

        if (entry.getSection() != null) {
            sb.append(" section=")
                    .append(entry.getSection().getLabel());
        }

        sb.append("}");
    }
}
