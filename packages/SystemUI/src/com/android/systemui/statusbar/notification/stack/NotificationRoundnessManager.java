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
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
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
    private ExpandableView mViewBeforeSwipedView = null;
    private ExpandableView mViewAfterSwipedView = null;

    @Inject
    NotificationRoundnessManager(
            NotificationSectionsFeatureManager sectionsFeatureManager,
            NotificationRoundnessLogger notifLogger,
            DumpManager dumpManager) {
        int numberOfSections = sectionsFeatureManager.getNumberOfBuckets();
        mFirstInSectionViews = new ExpandableView[numberOfSections];
        mLastInSectionViews = new ExpandableView[numberOfSections];
        mTmpFirstInSectionViews = new ExpandableView[numberOfSections];
        mTmpLastInSectionViews = new ExpandableView[numberOfSections];
        mNotifLogger = notifLogger;
        mDumpManager = dumpManager;

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

    boolean updateViewWithoutCallback(ExpandableView view,
            boolean animate) {
        if (view == null
                || view == mViewBeforeSwipedView
                || view == mViewAfterSwipedView) {
            return false;
        }

        final float topRoundness = getRoundnessFraction(view, true /* top */);
        final float bottomRoundness = getRoundnessFraction(view, false /* top */);

        final boolean topChanged = view.setTopRoundness(topRoundness, animate);
        final boolean bottomChanged = view.setBottomRoundness(bottomRoundness, animate);

        final boolean isFirstInSection = isFirstInSection(view);
        final boolean isLastInSection = isLastInSection(view);

        view.setFirstInSection(isFirstInSection);
        view.setLastInSection(isLastInSection);

        mNotifLogger.onCornersUpdated(view, isFirstInSection,
                isLastInSection, topChanged, bottomChanged);

        return (isFirstInSection || isLastInSection) && (topChanged || bottomChanged);
    }

    private boolean isFirstInSection(ExpandableView view) {
        for (int i = 0; i < mFirstInSectionViews.length; i++) {
            if (view == mFirstInSectionViews[i]) {
                return true;
            }
        }
        return false;
    }

    private boolean isLastInSection(ExpandableView view) {
        for (int i = mLastInSectionViews.length - 1; i >= 0; i--) {
            if (view == mLastInSectionViews[i]) {
                return true;
            }
        }
        return false;
    }

    void setViewsAffectedBySwipe(
            ExpandableView viewBefore,
            ExpandableView viewSwiped,
            ExpandableView viewAfter) {
        final boolean animate = true;

        ExpandableView oldViewBefore = mViewBeforeSwipedView;
        mViewBeforeSwipedView = viewBefore;
        if (oldViewBefore != null) {
            final float bottomRoundness = getRoundnessFraction(oldViewBefore, false /* top */);
            oldViewBefore.setBottomRoundness(bottomRoundness,  animate);
        }
        if (viewBefore != null) {
            viewBefore.setBottomRoundness(1f, animate);
        }

        ExpandableView oldSwipedview = mSwipedView;
        mSwipedView = viewSwiped;
        if (oldSwipedview != null) {
            final float bottomRoundness = getRoundnessFraction(oldSwipedview, false /* top */);
            final float topRoundness = getRoundnessFraction(oldSwipedview, true /* top */);
            oldSwipedview.setTopRoundness(topRoundness, animate);
            oldSwipedview.setBottomRoundness(bottomRoundness, animate);
        }
        if (viewSwiped != null) {
            viewSwiped.setTopRoundness(1f, animate);
            viewSwiped.setBottomRoundness(1f, animate);
        }

        ExpandableView oldViewAfter = mViewAfterSwipedView;
        mViewAfterSwipedView = viewAfter;
        if (oldViewAfter != null) {
            final float topRoundness = getRoundnessFraction(oldViewAfter, true /* top */);
            oldViewAfter.setTopRoundness(topRoundness, animate);
        }
        if (viewAfter != null) {
            viewAfter.setTopRoundness(1f, animate);
        }
    }

    void setClearAllInProgress(boolean isClearingAll) {
        mIsClearAllInProgress = isClearingAll;
    }

    private float getRoundnessFraction(ExpandableView view, boolean top) {
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
        if ((view.isPinned()
                || (view.isHeadsUpAnimatingAway()) && !mExpanded)) {
            return 1.0f;
        }
        if (isFirstInSection(view) && top) {
            return 1.0f;
        }
        if (isLastInSection(view) && !top) {
            return 1.0f;
        }

        if (view == mTrackedHeadsUp) {
            // If we're pushing up on a headsup the appear fraction is < 0 and it needs to still be
            // rounded.
            return MathUtils.saturate(1.0f - mAppearFraction);
        }
        if (view.showingPulsing() && mRoundForPulsingViews) {
            return 1.0f;
        }
        final Resources resources = view.getResources();
        return resources.getDimension(R.dimen.notification_corner_radius_small)
                / resources.getDimension(R.dimen.notification_corner_radius);
    }

    public void setExpanded(float expandedHeight, float appearFraction) {
        mExpanded = expandedHeight != 0.0f;
        mAppearFraction = appearFraction;
        if (mTrackedHeadsUp != null) {
            updateView(mTrackedHeadsUp, false /* animate */);
        }
    }

    public void updateRoundedChildren(NotificationSection[] sections) {
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

    private boolean handleRemovedOldViews(NotificationSection[] sections,
            ExpandableView[] oldViews, boolean first) {
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

    private boolean handleAddedNewViews(NotificationSection[] sections,
            ExpandableView[] oldViews, boolean first) {
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
