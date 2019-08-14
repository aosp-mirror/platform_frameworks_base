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

import android.content.Context;
import android.util.MathUtils;

import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.HashSet;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A class that manages the roundness for notification views
 */
@Singleton
public class NotificationRoundnessManager implements OnHeadsUpChangedListener {

    private final ActivatableNotificationView[] mFirstInSectionViews;
    private final ActivatableNotificationView[] mLastInSectionViews;
    private final ActivatableNotificationView[] mTmpFirstInSectionViews;
    private final ActivatableNotificationView[] mTmpLastInSectionViews;
    private final KeyguardBypassController mBypassController;
    private boolean mExpanded;
    private HashSet<ExpandableView> mAnimatedChildren;
    private Runnable mRoundingChangedCallback;
    private ExpandableNotificationRow mTrackedHeadsUp;
    private float mAppearFraction;

    @Inject
    NotificationRoundnessManager(
            KeyguardBypassController keyguardBypassController,
            Context context) {
        int numberOfSections = NotificationData.getNotificationBuckets(context).length;
        mFirstInSectionViews = new ActivatableNotificationView[numberOfSections];
        mLastInSectionViews = new ActivatableNotificationView[numberOfSections];
        mTmpFirstInSectionViews = new ActivatableNotificationView[numberOfSections];
        mTmpLastInSectionViews = new ActivatableNotificationView[numberOfSections];
        mBypassController = keyguardBypassController;
    }

    @Override
    public void onHeadsUpPinned(NotificationEntry headsUp) {
        updateView(headsUp.getRow(), false /* animate */);
    }

    @Override
    public void onHeadsUpUnPinned(NotificationEntry headsUp) {
        updateView(headsUp.getRow(), true /* animate */);
    }

    public void onHeadsupAnimatingAwayChanged(ExpandableNotificationRow row,
            boolean isAnimatingAway) {
        updateView(row, false /* animate */);
    }

    @Override
    public void onHeadsUpStateChanged(NotificationEntry entry, boolean isHeadsUp) {
        updateView(entry.getRow(), false /* animate */);
    }

    private void updateView(ActivatableNotificationView view, boolean animate) {
        boolean changed = updateViewWithoutCallback(view, animate);
        if (changed) {
            mRoundingChangedCallback.run();
        }
    }

    private boolean updateViewWithoutCallback(ActivatableNotificationView view,
            boolean animate) {
        float topRoundness = getRoundness(view, true /* top */);
        float bottomRoundness = getRoundness(view, false /* top */);
        boolean topChanged = view.setTopRoundness(topRoundness, animate);
        boolean bottomChanged = view.setBottomRoundness(bottomRoundness, animate);
        boolean firstInSection = isFirstInSection(view, false /* exclude first section */);
        boolean lastInSection = isLastInSection(view, false /* exclude last section */);
        view.setFirstInSection(firstInSection);
        view.setLastInSection(lastInSection);
        return (firstInSection || lastInSection) && (topChanged || bottomChanged);
    }

    private boolean isFirstInSection(ActivatableNotificationView view,
            boolean includeFirstSection) {
        int numNonEmptySections = 0;
        for (int i = 0; i < mFirstInSectionViews.length; i++) {
            if (view == mFirstInSectionViews[i]) {
                return includeFirstSection || numNonEmptySections > 0;
            }
            if (mFirstInSectionViews[i] != null) {
                numNonEmptySections++;
            }
        }
        return false;
    }

    private boolean isLastInSection(ActivatableNotificationView view, boolean includeLastSection) {
        int numNonEmptySections = 0;
        for (int i = mLastInSectionViews.length - 1; i >= 0; i--) {
            if (view == mLastInSectionViews[i]) {
                return includeLastSection || numNonEmptySections > 0;
            }
            if (mLastInSectionViews[i] != null) {
                numNonEmptySections++;
            }
        }
        return false;
    }

    private float getRoundness(ActivatableNotificationView view, boolean top) {
        if ((view.isPinned() || view.isHeadsUpAnimatingAway()) && !mExpanded) {
            return 1.0f;
        }
        if (isFirstInSection(view, true /* include first section */) && top) {
            return 1.0f;
        }
        if (isLastInSection(view, true /* include last section */) && !top) {
            return 1.0f;
        }
        if (view == mTrackedHeadsUp) {
            // If we're pushing up on a headsup the appear fraction is < 0 and it needs to still be
            // rounded.
            return MathUtils.saturate(1.0f - mAppearFraction);
        }
        if (view.showingPulsing() && !mBypassController.getBypassEnabled()) {
            return 1.0f;
        }
        return 0.0f;
    }

    public void setExpanded(float expandedHeight, float appearFraction) {
        mExpanded = expandedHeight != 0.0f;
        mAppearFraction = appearFraction;
        if (mTrackedHeadsUp != null) {
            updateView(mTrackedHeadsUp, true);
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
    }

    private boolean handleRemovedOldViews(NotificationSection[] sections,
            ActivatableNotificationView[] oldViews, boolean first) {
        boolean anyChanged = false;
        for (ActivatableNotificationView oldView : oldViews) {
            if (oldView != null) {
                boolean isStillPresent = false;
                boolean adjacentSectionChanged = false;
                for (NotificationSection section : sections) {
                    ActivatableNotificationView newView =
                            (first ? section.getFirstVisibleChild()
                                    : section.getLastVisibleChild());
                    if (newView == oldView) {
                        isStillPresent = true;
                        if (oldView.isFirstInSection() != isFirstInSection(oldView,
                                false /* exclude first section */)
                                || oldView.isLastInSection() != isLastInSection(oldView,
                                false /* exclude last section */)) {
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
            ActivatableNotificationView[] oldViews, boolean first) {
        boolean anyChanged = false;
        for (NotificationSection section : sections) {
            ActivatableNotificationView newView =
                    (first ? section.getFirstVisibleChild() : section.getLastVisibleChild());
            if (newView != null) {
                boolean wasAlreadyPresent = false;
                for (ActivatableNotificationView oldView : oldViews) {
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
}
