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

import com.android.internal.R;

public class KeyguardWidgetCarousel extends KeyguardWidgetPager {

    private float mAdjacentPagesAngle;
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
        return 1.5f;
    }

    private void updatePageAlphaValues(int screenCenter) {
        boolean isInOverscroll = mOverScrollX < 0 || mOverScrollX > mMaxScrollX;
        if (!isInOverscroll) {
            for (int i = 0; i < getChildCount(); i++) {
                KeyguardWidgetFrame child = getWidgetPageAt(i);
                if (child != null) {
                    float scrollProgress = getScrollProgress(screenCenter, child, i);
                    if (!isReordering(false)) {
                        child.setBackgroundAlphaMultiplier(
                                backgroundAlphaInterpolator(Math.abs(scrollProgress)));
                    } else {
                        child.setBackgroundAlphaMultiplier(1f);
                    }
                }
            }
        }
    }

    @Override
    protected void screenScrolled(int screenCenter) {
        updatePageAlphaValues(screenCenter);
        for (int i = 0; i < getChildCount(); i++) {
            KeyguardWidgetFrame v = getWidgetPageAt(i);
            if (v == mDragView) continue;
            if (v != null) {
                float scrollProgress = getScrollProgress(screenCenter, v, i);
                int width = v.getMeasuredWidth();
                float pivotX = (width / 2f) + scrollProgress * (width / 2f);
                float pivotY = v.getMeasuredHeight() / 2;
                float rotationY = - mAdjacentPagesAngle * scrollProgress;
                v.setCameraDistance(CAMERA_DISTANCE);
                v.setPivotX(pivotX);
                v.setPivotY(pivotY);
                v.setRotationY(rotationY);
            }
        }
    }
}
