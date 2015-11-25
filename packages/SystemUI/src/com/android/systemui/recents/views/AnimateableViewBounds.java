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
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.IntProperty;
import android.util.Property;
import android.view.View;
import android.view.ViewOutlineProvider;

/* An outline provider that has a clip and outline that can be animated. */
public class AnimateableViewBounds extends ViewOutlineProvider {

    View mSourceView;
    Rect mClipRect = new Rect();
    Rect mClipBounds = new Rect();
    int mCornerRadius;
    float mAlpha = 1f;
    final float mMinAlpha = 0.25f;

    public static final Property<AnimateableViewBounds, Integer> CLIP_BOTTOM =
            new IntProperty<AnimateableViewBounds>("clipBottom") {
                @Override
                public void setValue(AnimateableViewBounds object, int clip) {
                    object.setClipBottom(clip, false /* force */);
                }

                @Override
                public Integer get(AnimateableViewBounds object) {
                    return object.getClipBottom();
                }
            };

    public static final Property<AnimateableViewBounds, Integer> CLIP_RIGHT =
            new IntProperty<AnimateableViewBounds>("clipRight") {
                @Override
                public void setValue(AnimateableViewBounds object, int clip) {
                    object.setClipRight(clip, false /* force */);
                }

                @Override
                public Integer get(AnimateableViewBounds object) {
                    return object.getClipRight();
                }
            };

    public AnimateableViewBounds(View source, int cornerRadius) {
        mSourceView = source;
        mCornerRadius = cornerRadius;
    }

    @Override
    public void getOutline(View view, Outline outline) {
        outline.setAlpha(mMinAlpha + mAlpha / (1f - mMinAlpha));
        outline.setRoundRect(mClipRect.left, mClipRect.top,
                mSourceView.getWidth() - mClipRect.right,
                mSourceView.getHeight() - mClipRect.bottom,
                mCornerRadius);
    }

    /** Sets the view outline alpha. */
    void setAlpha(float alpha) {
        if (Float.compare(alpha, mAlpha) != 0) {
            mAlpha = alpha;
            mSourceView.invalidateOutline();
        }
    }

    /**
     * Animates the bottom clip.
     */
    public void animateClipBottom(int bottom) {
        ObjectAnimator animator = ObjectAnimator.ofInt(this, CLIP_BOTTOM, getClipBottom(), bottom);
        animator.setDuration(150);
        animator.start();
    }

    /** Sets the bottom clip. */
    public void setClipBottom(int bottom, boolean force) {
        if (bottom != mClipRect.bottom || force) {
            mClipRect.bottom = bottom;
            mSourceView.invalidateOutline();
            updateClipBounds();
        }
    }

    /** Returns the bottom clip. */
    public int getClipBottom() {
        return mClipRect.bottom;
    }

    /** Sets the right clip. */
    public void setClipRight(int right, boolean force) {
        if (right != mClipRect.right || force) {
            mClipRect.right = right;
            mSourceView.invalidateOutline();
            updateClipBounds();
        }
    }

    /** Returns the right clip. */
    public int getClipRight() {
        return mClipRect.right;
    }

    private void updateClipBounds() {
        mClipBounds.set(mClipRect.left, mClipRect.top,
                mSourceView.getWidth() - mClipRect.right,
                mSourceView.getHeight() - mClipRect.bottom);
        mSourceView.setClipBounds(mClipBounds);
    }
}
