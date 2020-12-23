/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.keyguard.KeyguardConstants;
import com.android.settingslib.animation.AppearAnimationUtils;
import com.android.settingslib.animation.DisappearAnimationUtils;
import com.android.systemui.Interpolators;

/**
 * The container for the user switcher on Keyguard.
 */
public class KeyguardUserSwitcherListView extends AlphaOptimizedLinearLayout {

    private static final String TAG = "KeyguardUserSwitcherListView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private static final int ANIMATION_DURATION_OPENING = 360;
    private static final int ANIMATION_DURATION_CLOSING = 240;

    private boolean mAnimating;
    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;

    public KeyguardUserSwitcherListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        mAppearAnimationUtils = new AppearAnimationUtils(context, ANIMATION_DURATION_OPENING,
                -0.5f, 0.5f, Interpolators.FAST_OUT_SLOW_IN);
        mDisappearAnimationUtils = new DisappearAnimationUtils(context, ANIMATION_DURATION_CLOSING,
                0.5f, 0.5f, Interpolators.FAST_OUT_LINEAR_IN);
    }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     *
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    void setDarkAmount(float darkAmount) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = getChildAt(i);
            if (v instanceof KeyguardUserDetailItemView) {
                ((KeyguardUserDetailItemView) v).setDarkAmount(darkAmount);
            }
        }
    }

    boolean isAnimating() {
        return mAnimating;
    }

    /**
     * Update visibilities of this view and child views for when the user list is open or closed.
     * If closed, this hides everything but the first item (which is always the current user).
     */
    void updateVisibilities(boolean open, boolean animate) {
        if (DEBUG) {
            Log.d(TAG, String.format("updateVisibilities: open=%b animate=%b childCount=%d",
                    open, animate, getChildCount()));
        }

        mAnimating = false;

        int userListCount = getChildCount();
        if (userListCount > 0) {
            // The first child is always the current user.
            KeyguardUserDetailItemView currentUserView = ((KeyguardUserDetailItemView) getChildAt(
                    0));
            currentUserView.updateVisibilities(true /* showItem */, open /* showTextName */,
                    animate);
            currentUserView.setClickable(true);
            currentUserView.clearAnimation();
        }

        if (userListCount <= 1) {
            return;
        }

        if (animate) {
            // Create an array of all the remaining users (that aren't the current user).
            KeyguardUserDetailItemView[] otherUserViews =
                    new KeyguardUserDetailItemView[userListCount - 1];
            for (int i = 1, n = 0; i < userListCount; i++, n++) {
                otherUserViews[n] = (KeyguardUserDetailItemView) getChildAt(i);

                // Update clickable state immediately so that the menu feels more responsive
                otherUserViews[n].setClickable(open);

                // Before running the animation, ensure visibility is set correctly
                otherUserViews[n].updateVisibilities(
                        true /* showItem */, true /* showTextName */, false /* animate */);
                otherUserViews[n].clearAnimation();
            }

            setClipChildren(false);
            setClipToPadding(false);

            mAnimating = true;

            final int nonCurrentUserCount = otherUserViews.length;
            if (open) {
                mAppearAnimationUtils.startAnimation(otherUserViews, () -> {
                    setClipChildren(true);
                    setClipToPadding(true);
                    mAnimating = false;
                });
            } else {
                mDisappearAnimationUtils.startAnimation(otherUserViews, () -> {
                    setClipChildren(true);
                    setClipToPadding(true);
                    for (int i = 0; i < nonCurrentUserCount; i++) {
                        otherUserViews[i].updateVisibilities(
                                false /* showItem */, true /* showTextName */, false /* animate */);
                    }
                    mAnimating = false;
                });
            }
        } else {
            for (int i = 1; i < userListCount; i++) {
                KeyguardUserDetailItemView nonCurrentUserView =
                        ((KeyguardUserDetailItemView) getChildAt(i));
                nonCurrentUserView.clearAnimation();
                nonCurrentUserView.updateVisibilities(
                        open /* showItem */, true /* showTextName */, false /* animate */);
                nonCurrentUserView.setClickable(open);
            }
        }
    }

    /**
     * Replaces the view at the specified position in the group.
     *
     * @param index the position in the group of the view to remove
     */
    void replaceView(KeyguardUserDetailItemView newView, int index) {
        removeViewAt(index);
        addView(newView, index);
    }

    /**
     * Removes the last view in the group.
     */
    void removeLastView() {
        int lastIndex = getChildCount() - 1;
        removeViewAt(lastIndex);
    }
}
