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

import android.content.Context;
import android.view.View;
import com.android.systemui.R;

/**
 * The Algorithm of the {@link com.android.systemui.statusbar.SpeedBumpDotsLayout} which can be
 * queried for {@link * com.android.systemui.statusbar.SpeedBumpDotsState}
 */
public class SpeedBumpDotsAlgorithm {

    private final float mDotRadius;

    public SpeedBumpDotsAlgorithm(Context context) {
        mDotRadius = context.getResources().getDimensionPixelSize(R.dimen.speed_bump_dots_height)
                / 2.0f;
    }

    public void getState(SpeedBumpDotsState resultState) {

        // First reset the current state and ensure that every View has a ViewState
        resultState.resetViewStates();

        SpeedBumpDotsLayout hostView = resultState.getHostView();
        boolean currentlyVisible = hostView.isCurrentlyVisible();
        resultState.setActiveState(currentlyVisible
                ? SpeedBumpDotsState.SHOWN
                : SpeedBumpDotsState.HIDDEN);
        int hostWidth = hostView.getWidth();
        float layoutWidth = hostWidth - 2 * mDotRadius;
        int childCount = hostView.getChildCount();
        float paddingBetween = layoutWidth / (childCount - 1);
        float centerY = hostView.getHeight() / 2.0f;
        for (int i = 0; i < childCount; i++) {
            View child = hostView.getChildAt(i);
            SpeedBumpDotsState.ViewState viewState = resultState.getViewStateForView(child);
            if (currentlyVisible) {
                float xTranslation = i * paddingBetween;
                viewState.xTranslation = xTranslation;
                viewState.yTranslation = calculateYTranslation(hostView, centerY, xTranslation,
                        layoutWidth);
            } else {
                viewState.xTranslation = layoutWidth / 2;
                viewState.yTranslation = centerY - mDotRadius;
            }
            viewState.alpha = currentlyVisible ? 1.0f : 0.0f;
            viewState.scale = currentlyVisible ? 1.0f : 0.5f;
        }
    }

    private float calculateYTranslation(SpeedBumpDotsLayout hostView, float centerY,
            float xTranslation, float layoutWidth) {
        float t = hostView.getAnimationProgress();
        if (t == 0.0f || t == 1.0f) {
            return centerY - mDotRadius;
        }
        float damping = (0.5f -Math.abs(0.5f - t)) * 1.3f;
        float partialOffset = xTranslation / layoutWidth;
        float indentFactor = (float) (Math.sin((t + partialOffset * 1.5f) * - Math.PI) * damping);
        return (1.0f - indentFactor) * centerY - mDotRadius;
    }

}
