/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.android.internal.R;

public class KeyguardWidgetCarousel extends KeyguardWidgetPager {

    private float mAdjacentPagesAngle;
    private static float MAX_SCROLL_PROGRESS = 1.3f;
    private static float CAMERA_DISTANCE = 10000;

    public KeyguardWidgetCarousel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetCarousel(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetCarousel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mAdjacentPagesAngle = context.getResources().getInteger(R.integer.kg_carousel_angle);
    }

    protected float getMaxScrollProgress() {
        return MAX_SCROLL_PROGRESS;
    }

    public float getAlphaForPage(int screenCenter, int index) {
        View child = getChildAt(index);
        if (child == null) return 0f;

        float scrollProgress = getScrollProgress(screenCenter, child, index);
        if (!isOverScrollChild(index, scrollProgress)) {
            scrollProgress = getBoundedScrollProgress(screenCenter, child, index);
            float alpha = 1 - Math.abs(scrollProgress / MAX_SCROLL_PROGRESS);
            return alpha;
        } else {
            return 1f;
        }
    }

    private void updatePageAlphaValues(int screenCenter) {
        if (mChildrenOutlineFadeAnimation != null) {
            mChildrenOutlineFadeAnimation.cancel();
            mChildrenOutlineFadeAnimation = null;
        }
        if (!isReordering(false)) {
            for (int i = 0; i < getChildCount(); i++) {
                KeyguardWidgetFrame child = getWidgetPageAt(i);
                if (child != null) {
                    float alpha = getAlphaForPage(screenCenter, i);
                    child.setBackgroundAlpha(alpha);
                    child.setContentAlpha(alpha);
                }
            }
        }

    }

    @Override
    protected void screenScrolled(int screenCenter) {
        mScreenCenter = screenCenter;
        updatePageAlphaValues(screenCenter);
        for (int i = 0; i < getChildCount(); i++) {
            KeyguardWidgetFrame v = getWidgetPageAt(i);
            float scrollProgress = getScrollProgress(screenCenter, v, i);
            if (v == mDragView || v == null) continue;
            v.setCameraDistance(CAMERA_DISTANCE);

            if (isOverScrollChild(i, scrollProgress)) {
                v.setRotationY(- OVERSCROLL_MAX_ROTATION * scrollProgress);
                v.setOverScrollAmount(Math.abs(scrollProgress), scrollProgress < 0);
            } else {
                scrollProgress = getBoundedScrollProgress(screenCenter, v, i);
                int width = v.getMeasuredWidth();
                float pivotX = (width / 2f) + scrollProgress * (width / 2f);
                float pivotY = v.getMeasuredHeight() / 2;
                float rotationY = - mAdjacentPagesAngle * scrollProgress;
                v.setPivotX(pivotX);
                v.setPivotY(pivotY);
                v.setRotationY(rotationY);
                v.setOverScrollAmount(0f, false);
            }

            float alpha = v.getAlpha();
            // If the view has 0 alpha, we set it to be invisible so as to prevent
            // it from accepting touches
            if (alpha == 0) {
                v.setVisibility(INVISIBLE);
            } else if (v.getVisibility() != VISIBLE) {
                v.setVisibility(VISIBLE);
            }
        }
    }
}
