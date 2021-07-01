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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.VectorDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import com.android.settingslib.Utils;

/**
 * Similar to the {@link NumPadKey}, but displays an image.
 */
public class NumPadButton extends AlphaOptimizedImageButton {

    @Nullable
    private NumPadAnimator mAnimator;
    private int mOrientation;

    public NumPadButton(Context context, AttributeSet attrs) {
        super(context, attrs);

        Drawable background = getBackground();
        if (background instanceof RippleDrawable) {
            mAnimator = new NumPadAnimator(context, (RippleDrawable) getBackground(),
                    attrs.getStyleAttribute());
        } else {
            mAnimator = null;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mOrientation = newConfig.orientation;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Set width/height to the same value to ensure a smooth circle for the bg, but shrink
        // the height to match the old pin bouncer
        int width = getMeasuredWidth();

        boolean shortenHeight = mAnimator == null
                || mOrientation == Configuration.ORIENTATION_LANDSCAPE;
        int height = shortenHeight ? (int) (width * .66f) : width;

        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (mAnimator != null) mAnimator.onLayout(b - t);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && mAnimator != null) {
            mAnimator.start();
        }
        return super.onTouchEvent(event);
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        if (mAnimator != null) {
            mAnimator.reloadColors(getContext());
	} else {
            // Needed for old style pin
            int textColor = Utils.getColorAttr(getContext(), android.R.attr.textColorPrimary)
                    .getDefaultColor();
            ((VectorDrawable) getDrawable()).setTintList(ColorStateList.valueOf(textColor));
        }
    }
}
