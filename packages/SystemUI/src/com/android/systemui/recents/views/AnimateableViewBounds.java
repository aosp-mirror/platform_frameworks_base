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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;
import com.android.systemui.recents.RecentsConfiguration;

/* An outline provider that has a clip and outline that can be animated. */
public class AnimateableViewBounds extends ViewOutlineProvider {

    RecentsConfiguration mConfig;

    View mSourceView;
    Rect mClipRect = new Rect();
    Rect mOutlineClipRect = new Rect();
    int mCornerRadius;
    float mAlpha = 1f;
    final float mMinAlpha = 0.25f;

    ObjectAnimator mClipTopAnimator;
    ObjectAnimator mClipRightAnimator;
    ObjectAnimator mClipBottomAnimator;

    public AnimateableViewBounds(View source, int cornerRadius) {
        mConfig = RecentsConfiguration.getInstance();
        mSourceView = source;
        mCornerRadius = cornerRadius;
        mSourceView.setClipToOutline(true);
        setClipTop(getClipTop());
        setClipRight(getClipRight());
        setClipBottom(getClipBottom());
        setOutlineClipBottom(getOutlineClipBottom());
    }

    @Override
    public void getOutline(View view, Outline outline) {
        outline.setAlpha(mMinAlpha + mAlpha / (1f - mMinAlpha));
        outline.setRoundRect(Math.max(mClipRect.left, mOutlineClipRect.left),
                Math.max(mClipRect.top, mOutlineClipRect.top),
                mSourceView.getMeasuredWidth() - Math.max(mClipRect.right, mOutlineClipRect.right),
                mSourceView.getMeasuredHeight() - Math.max(mClipRect.bottom, mOutlineClipRect.bottom),
                mCornerRadius);
    }

    /** Sets the view outline alpha. */
    void setAlpha(float alpha) {
        if (Float.compare(alpha, mAlpha) != 0) {
            mAlpha = alpha;
            mSourceView.invalidateOutline();
        }
    }

    /** Animates the top clip. */
    void animateClipTop(int top, int duration, ValueAnimator.AnimatorUpdateListener updateListener) {
        if (mClipTopAnimator != null) {
            mClipTopAnimator.removeAllListeners();
            mClipTopAnimator.cancel();
        }
        mClipTopAnimator = ObjectAnimator.ofInt(this, "clipTop", top);
        mClipTopAnimator.setDuration(duration);
        mClipTopAnimator.setInterpolator(mConfig.fastOutSlowInInterpolator);
        if (updateListener != null) {
            mClipTopAnimator.addUpdateListener(updateListener);
        }
        mClipTopAnimator.start();
    }

    /** Sets the top clip. */
    public void setClipTop(int top) {
        if (top != mClipRect.top) {
            mClipRect.top = top;
            mSourceView.invalidateOutline();
        }
    }

    /** Returns the top clip. */
    public int getClipTop() {
        return mClipRect.top;
    }

    /** Animates the right clip. */
    void animateClipRight(int right, int duration) {
        if (mClipRightAnimator != null) {
            mClipRightAnimator.removeAllListeners();
            mClipRightAnimator.cancel();
        }
        mClipRightAnimator = ObjectAnimator.ofInt(this, "clipRight", right);
        mClipRightAnimator.setDuration(duration);
        mClipRightAnimator.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mClipRightAnimator.start();
    }

    /** Sets the right clip. */
    public void setClipRight(int right) {
        if (right != mClipRect.right) {
            mClipRect.right = right;
            mSourceView.invalidateOutline();
        }
    }

    /** Returns the right clip. */
    public int getClipRight() {
        return mClipRect.right;
    }

    /** Animates the bottom clip. */
    void animateClipBottom(int bottom, int duration) {
        if (mClipBottomAnimator != null) {
            mClipBottomAnimator.removeAllListeners();
            mClipBottomAnimator.cancel();
        }
        mClipBottomAnimator = ObjectAnimator.ofInt(this, "clipBottom", bottom);
        mClipBottomAnimator.setDuration(duration);
        mClipBottomAnimator.setInterpolator(mConfig.fastOutSlowInInterpolator);
        mClipBottomAnimator.start();
    }

    /** Sets the bottom clip. */
    public void setClipBottom(int bottom) {
        if (bottom != mClipRect.bottom) {
            mClipRect.bottom = bottom;
            mSourceView.invalidateOutline();
        }
    }

    /** Returns the bottom clip. */
    public int getClipBottom() {
        return mClipRect.bottom;
    }

    /** Sets the outline bottom clip. */
    public void setOutlineClipBottom(int bottom) {
        if (bottom != mOutlineClipRect.bottom) {
            mOutlineClipRect.bottom = bottom;
            mSourceView.invalidateOutline();
        }
    }

    /** Gets the outline bottom clip. */
    public int getOutlineClipBottom() {
        return mOutlineClipRect.bottom;
    }
}
