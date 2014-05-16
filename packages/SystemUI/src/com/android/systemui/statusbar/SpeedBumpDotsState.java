/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import java.util.HashMap;
import java.util.Map;

/**
 * A state of a {@link com.android.systemui.statusbar.SpeedBumpDotsLayout}
 */
public class SpeedBumpDotsState {

    public static final int HIDDEN = 1;
    public static final int SHOWN = 2;
    private static final int VISIBILITY_ANIMATION_DELAY_PER_ELEMENT = 80;

    private final SpeedBumpDotsLayout mHostView;
    private final HashMap<View, ViewState> mStateMap = new HashMap<View, ViewState>();
    private final Interpolator mFastOutSlowInInterpolator;
    private int mActiveState = 0;

    public SpeedBumpDotsState(SpeedBumpDotsLayout hostLayout) {
        mHostView = hostLayout;
        mFastOutSlowInInterpolator = AnimationUtils
                .loadInterpolator(hostLayout.getContext(),
                        android.R.interpolator.fast_out_slow_in);
    }

    public SpeedBumpDotsLayout getHostView() {
        return mHostView;
    }

    public void resetViewStates() {
        int numChildren = mHostView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = mHostView.getChildAt(i);
            ViewState viewState = mStateMap.get(child);
            if (viewState == null) {
                viewState = new ViewState();
                mStateMap.put(child, viewState);
            }
        }
    }

    public ViewState getViewStateForView(View requestedView) {
        return mStateMap.get(requestedView);
    }

    public void apply() {
        int childCount = mHostView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mHostView.getChildAt(i);
            ViewState viewState = mStateMap.get(child);
            float translationX = child.getTranslationX();
            float translationY = child.getTranslationY();
            float scale = child.getScaleX();
            float alpha = child.getAlpha();
            if (translationX != viewState.xTranslation) {
                child.setTranslationX(viewState.xTranslation);
            }
            if (translationY != viewState.yTranslation) {
                child.setTranslationY(viewState.yTranslation);
            }
            if (scale != viewState.scale) {
                child.setScaleX(viewState.scale);
                child.setScaleY(viewState.scale);
            }
            if (alpha != viewState.alpha) {
                child.setAlpha(viewState.alpha);
            }
        }
    }

    public void animateToState() {
        int childCount = mHostView.getChildCount();
        int middleIndex = (childCount - 1) / 2;
        long delayPerElement = VISIBILITY_ANIMATION_DELAY_PER_ELEMENT;
        boolean isAppearing = getActiveState() == SHOWN;
        boolean isDisappearing = getActiveState() == HIDDEN;
        for (int i = 0; i < childCount; i++) {
            int delayIndex;
            if (i <= middleIndex) {
                delayIndex = i * 2;
            } else {
                int distToMiddle = i - middleIndex;
                delayIndex = (childCount - 1) - (distToMiddle - 1) * 2;
            }
            long startDelay = 0;
            if (isAppearing || isDisappearing) {
                if (isDisappearing) {
                    delayIndex = childCount - 1 - delayIndex;
                }
                startDelay = delayIndex * delayPerElement;
            }
            View child = mHostView.getChildAt(i);
            ViewState viewState = mStateMap.get(child);
            child.animate().setInterpolator(mFastOutSlowInInterpolator)
                    .setStartDelay(startDelay)
                    .alpha(viewState.alpha).withLayer()
                    .translationX(viewState.xTranslation)
                    .translationY(viewState.yTranslation)
                    .scaleX(viewState.scale).scaleY(viewState.scale);
        }
    }

    public int getActiveState() {
        return mActiveState;
    }

    public void setActiveState(int mActiveState) {
        this.mActiveState = mActiveState;
    }

    public static class ViewState {
        float xTranslation;
        float yTranslation;
        float alpha;
        float scale;
    }
}
