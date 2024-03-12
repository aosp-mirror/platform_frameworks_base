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

import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_BUTTON;
import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.ColorId.NUM_PAD_KEY;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.VectorDrawable;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;

/**
 * Similar to the {@link NumPadKey}, but displays an image.
 */
public class NumPadButton extends AlphaOptimizedImageButton implements NumPadAnimationListener {

    @Nullable
    private NumPadAnimator mAnimator;
    private int mOrientation;
    private int mStyleAttr;
    private boolean mIsTransparentMode;

    public NumPadButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mStyleAttr = attrs.getStyleAttribute();
        setupAnimator();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mOrientation = newConfig.orientation;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // Set width/height to the same value to ensure a smooth circle for the bg, but shrink
        // the height to match the old pin bouncer.
        // This is only used for PIN/PUK; the main PIN pad now uses ConstraintLayout, which will
        // force our width/height to conform to the ratio in the layout.
        int width = getMeasuredWidth();

        boolean shortenHeight = mAnimator == null
                || mOrientation == Configuration.ORIENTATION_LANDSCAPE;
        int height = shortenHeight ? (int) (width * .66f) : width;

        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int width = r - l;
        int height = b - t;
        if (mAnimator != null) mAnimator.onLayout(width, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mAnimator != null) mAnimator.expand();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mAnimator != null) mAnimator.contract();
                break;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Reload colors from resources.
     **/
    public void reloadColors() {
        if (mAnimator != null) mAnimator.reloadColors(getContext());

        int textColorResId = mIsTransparentMode ? NUM_PAD_KEY : NUM_PAD_BUTTON;
        int imageColor = Utils.getColorAttrDefaultColor(getContext(), textColorResId);
        ((VectorDrawable) getDrawable()).setTintList(ColorStateList.valueOf(imageColor));
    }

    @Override
    public void setProgress(float progress) {
        if (mAnimator != null) {
            mAnimator.setProgress(progress);
        }
    }

    /**
     * Set whether button is transparent mode.
     *
     * @param isTransparentMode
     */
    public void setTransparentMode(boolean isTransparentMode) {
        if (mIsTransparentMode == isTransparentMode) {
            return;
        }

        mIsTransparentMode = isTransparentMode;

        if (isTransparentMode) {
            setBackgroundColor(getResources().getColor(android.R.color.transparent));
        } else {
            setBackground(getContext().getDrawable(R.drawable.num_pad_key_background));
        }
        setupAnimator();
        reloadColors();
        requestLayout();
    }

    /**
     * Set up the animator for the NumPadButton.
     */
    private void setupAnimator() {
        Drawable background = getBackground();
        if (background instanceof GradientDrawable) {
            mAnimator = new NumPadAnimator(getContext(), background.mutate(),
                    mStyleAttr, getDrawable());
        } else {
            mAnimator = null;
        }
    }
}
