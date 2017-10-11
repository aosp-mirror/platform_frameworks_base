/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.graphics.drawable.AnimatedRotateDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

public class AnimatedImageView extends ImageView {
    private AnimatedRotateDrawable mDrawable;
    private boolean mAnimating;

    public AnimatedImageView(Context context) {
        super(context);
    }

    public AnimatedImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void updateDrawable() {
        if (isShown() && mDrawable != null) {
            mDrawable.stop();
        }
        final Drawable drawable = getDrawable();
        if (drawable instanceof AnimatedRotateDrawable) {
            mDrawable = (AnimatedRotateDrawable) drawable;
            // TODO: define in drawable xml once we have public attrs.
            mDrawable.setFramesCount(56);
            mDrawable.setFramesDuration(32);
            if (isShown() && mAnimating) {
                mDrawable.start();
            }
        } else {
            mDrawable = null;
        }
    }

    private void updateAnimating() {
        if (mDrawable != null) {
            if (getVisibility() == View.VISIBLE && mAnimating) {
                mDrawable.start();
            } else {
                mDrawable.stop();
            }
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateDrawable();
    }

    @Override
    public void setImageResource(int resid) {
        super.setImageResource(resid);
        updateDrawable();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimating();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateAnimating();
    }

    public void setAnimating(boolean animating) {
        mAnimating = animating;
        updateAnimating();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int vis) {
        super.onVisibilityChanged(changedView, vis);
        updateAnimating();
    }
}
