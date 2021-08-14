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
import com.android.systemui.animation.Interpolators;

/**
 * The container for the user switcher on Keyguard.
 */
public class KeyguardUserSwitcherListView extends AlphaOptimizedLinearLayout {

    private static final String TAG = "KeyguardUserSwitcherListView";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private boolean mAnimating;
    private final AppearAnimationUtils mAppearAnimationUtils;
    private final DisappearAnimationUtils mDisappearAnimationUtils;

    public KeyguardUserSwitcherListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAppearAnimationUtils = new AppearAnimationUtils(context,
                AppearAnimationUtils.DEFAULT_APPEAR_DURATION,
                -0.5f /* translationScaleFactor */,
                0.5f /* delayScaleFactor */,
                Interpolators.FAST_OUT_SLOW_IN);
        mDisappearAnimationUtils = new DisappearAnimationUtils(context,
                AppearAnimationUtils.DEFAULT_APPEAR_DURATION,
                0.2f /* translationScaleFactor */,
                0.2f /* delayScaleFactor */,
                Interpolators.FAST_OUT_SLOW_IN_REVERSE);
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

        int childCount = getChildCount();
        KeyguardUserDetailItemView[] userItemViews = new KeyguardUserDetailItemView[childCount];
        for (int i = 0; i < childCount; i++) {
            userItemViews[i] = (KeyguardUserDetailItemView) getChildAt(i);
            userItemViews[i].clearAnimation();
            if (i == 0) {
                // The first child is always the current user.
                userItemViews[i].updateVisibilities(true /* showItem */, open /* showTextName */,
                        animate);
                userItemViews[i].setClickable(true);
            } else {
                // Update clickable state immediately so that the menu feels more responsive
                userItemViews[i].setClickable(open);
                // Before running the animation, ensure visibility is set correctly
                userItemViews[i].updateVisibilities(animate || open /* showItem */,
                        true /* showTextName */, false /* animate */);
            }
        }

        if (animate) {
            // AnimationUtils will immediately hide/show the first item in the array. Since the
            // first view is the current user, we want to manage its visibility separately.
            // Set first item to null so AnimationUtils ignores it.
            userItemViews[0] = null;

            setClipChildren(false);
            setClipToPadding(false);
            mAnimating = true;
            (open ? mAppearAnimationUtils : mDisappearAnimationUtils)
                    .startAnimation(userItemViews, () -> {
                        setClipChildren(true);
                        setClipToPadding(true);
                        mAnimating = false;
                    });
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
