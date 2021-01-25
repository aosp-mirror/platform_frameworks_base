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
package com.android.keyguard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;

/**
 * Similar to the {@link NumPadKey}, but displays an image.
 */
public class NumPadButton extends AlphaOptimizedImageButton {

    private final NumPadAnimator mAnimator;

    public NumPadButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        mAnimator = new NumPadAnimator(context, (GradientDrawable) getBackground(),
                attrs.getStyleAttribute());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mAnimator.updateMargin((ViewGroup.MarginLayoutParams) getLayoutParams());

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Set width/height to the same value to ensure a smooth circle for the bg
        setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        mAnimator.onLayout(b - t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mAnimator.start();
        return super.onTouchEvent(event);
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        mAnimator.reloadColors(getContext());
    }
}
