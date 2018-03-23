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

package com.android.systemui.statusbar.stack;

import android.view.View;

import com.android.systemui.statusbar.ActivatableNotificationView;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import java.util.HashSet;

/**
 * A class that manages the roundness for notification views
 */
class NotificationRoundnessManager implements OnHeadsUpChangedListener {

    private boolean mExpanded;
    private ActivatableNotificationView mFirst;
    private ActivatableNotificationView mLast;
    private HashSet<View> mAnimatedChildren;
    private Runnable mRoundingChangedCallback;
    private ExpandableNotificationRow mTrackedHeadsUp;
    private float mAppearFraction;

    @Override
    public void onHeadsUpPinned(ExpandableNotificationRow headsUp) {
        updateRounding(headsUp, false /* animate */);
    }

    @Override
    public void onHeadsUpUnPinned(ExpandableNotificationRow headsUp) {
        updateRounding(headsUp, true /* animate */);
    }

    public void onHeadsupAnimatingAwayChanged(ExpandableNotificationRow row,
            boolean isAnimatingAway) {
        updateRounding(row, false /* animate */);
    }

    private void updateRounding(ActivatableNotificationView view, boolean animate) {
        float topRoundness = getRoundness(view, true /* top */);
        float bottomRoundness = getRoundness(view, false /* top */);
        boolean firstChanged = view.setTopRoundness(topRoundness, animate);
        boolean secondChanged = view.setBottomRoundness(bottomRoundness, animate);
        if ((view == mFirst || view == mLast) && (firstChanged || secondChanged)) {
            mRoundingChangedCallback.run();
        }
    }

    private float getRoundness(ActivatableNotificationView view, boolean top) {
        if ((view.isPinned() || view.isHeadsUpAnimatingAway()) && !mExpanded) {
            return 1.0f;
        }
        if (view == mFirst && top) {
            return 1.0f;
        }
        if (view == mLast && !top) {
            return 1.0f;
        }
        if (view == mTrackedHeadsUp && mAppearFraction <= 0.0f) {
            // If we're pushing up on a headsup the appear fraction is < 0 and it needs to still be
            // rounded.
            return 1.0f;
        }
        return 0.0f;
    }

    public void setExpanded(float expandedHeight, float appearFraction) {
        mExpanded = expandedHeight != 0.0f;
        mAppearFraction = appearFraction;
        if (mTrackedHeadsUp != null) {
            updateRounding(mTrackedHeadsUp, true);
        }
    }

    public void setFirstAndLastBackgroundChild(ActivatableNotificationView first,
            ActivatableNotificationView last) {
        boolean firstChanged = mFirst != first;
        boolean lastChanged = mLast != last;
        if (!firstChanged && !lastChanged) {
            return;
        }
        ActivatableNotificationView oldFirst = mFirst;
        ActivatableNotificationView oldLast = mLast;
        mFirst = first;
        mLast = last;
        if (firstChanged && oldFirst != null && !oldFirst.isRemoved()) {
            updateRounding(oldFirst, oldFirst.isShown());
        }
        if (lastChanged && oldLast != null && !oldLast.isRemoved()) {
            updateRounding(oldLast, oldLast.isShown());
        }
        if (mFirst != null) {
            updateRounding(mFirst, mFirst.isShown() && !mAnimatedChildren.contains(mFirst));
        }
        if (mLast != null) {
            updateRounding(mLast, mLast.isShown() && !mAnimatedChildren.contains(mLast));
        }
        mRoundingChangedCallback.run();
    }

    public void setAnimatedChildren(HashSet<View> animatedChildren) {
        mAnimatedChildren = animatedChildren;
    }

    public void setOnRoundingChangedCallback(Runnable roundingChangedCallback) {
        mRoundingChangedCallback = roundingChangedCallback;
    }

    public void setTrackingHeadsUp(ExpandableNotificationRow row) {
        mTrackedHeadsUp = row;
    }
}
