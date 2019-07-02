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

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Property;
import android.view.View;

import com.android.systemui.recents.utilities.AnimationProps;
import com.android.systemui.recents.utilities.Utilities;

import java.util.ArrayList;

/**
 * The visual properties for a {@link TaskView}.
 */
public class TaskViewTransform {

    public static final Property<View, Rect> LTRB =
            new Property<View, Rect>(Rect.class, "leftTopRightBottom") {

                private Rect mTmpRect = new Rect();

                @Override
                public void set(View v, Rect ltrb) {
                    v.setLeftTopRightBottom(ltrb.left, ltrb.top, ltrb.right, ltrb.bottom);
                }

                @Override
                public Rect get(View v) {
                    mTmpRect.set(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                    return mTmpRect;
                }
            };

    public float translationZ = 0;
    public float scale = 1f;
    public float alpha = 1f;
    public float dimAlpha = 0f;
    public float viewOutlineAlpha = 0f;

    public boolean visible = false;

    // This is a window-space rect used for positioning the task in the stack
    public RectF rect = new RectF();

    /**
     * Fills int this transform from the state of the given TaskView.
     */
    public void fillIn(TaskView tv) {
        translationZ = tv.getTranslationZ();
        scale = tv.getScaleX();
        alpha = tv.getAlpha();
        visible = true;
        dimAlpha = tv.getDimAlpha();
        viewOutlineAlpha = tv.getViewBounds().getAlpha();
        rect.set(tv.getLeft(), tv.getTop(), tv.getRight(), tv.getBottom());
    }

    /**
     * Copies the transform state from another {@link TaskViewTransform}.
     */
    public void copyFrom(TaskViewTransform other) {
        translationZ = other.translationZ;
        scale = other.scale;
        alpha = other.alpha;
        visible = other.visible;
        dimAlpha = other.dimAlpha;
        viewOutlineAlpha = other.viewOutlineAlpha;
        rect.set(other.rect);
    }

    /**
     * @return whether {@param other} is the same transform as this
     */
    public boolean isSame(TaskViewTransform other) {
        return translationZ == other.translationZ
                && scale == other.scale
                && other.alpha == alpha
                && dimAlpha == other.dimAlpha
                && visible == other.visible
                && rect.equals(other.rect);
    }

    /**
     * Resets the current transform.
     */
    public void reset() {
        translationZ = 0;
        scale = 1f;
        alpha = 1f;
        dimAlpha = 0f;
        viewOutlineAlpha = 0f;
        visible = false;
        rect.setEmpty();
    }

    /** Convenience functions to compare against current property values */
    public boolean hasAlphaChangedFrom(float v) {
        return (Float.compare(alpha, v) != 0);
    }

    public boolean hasScaleChangedFrom(float v) {
        return (Float.compare(scale, v) != 0);
    }

    public boolean hasTranslationZChangedFrom(float v) {
        return (Float.compare(translationZ, v) != 0);
    }

    public boolean hasRectChangedFrom(View v) {
        return ((int) rect.left != v.getLeft()) || ((int) rect.right != v.getRight()) ||
                ((int) rect.top != v.getTop()) || ((int) rect.bottom != v.getBottom());
    }

    /**
     * Applies this transform to a view.
     */
    public void applyToTaskView(TaskView v, ArrayList<Animator> animators,
            AnimationProps animation, boolean allowShadows) {
        // Return early if not visible
        if (!visible) {
            return;
        }

        if (animation.isImmediate()) {
            if (allowShadows && hasTranslationZChangedFrom(v.getTranslationZ())) {
                v.setTranslationZ(translationZ);
            }
            if (hasScaleChangedFrom(v.getScaleX())) {
                v.setScaleX(scale);
                v.setScaleY(scale);
            }
            if (hasAlphaChangedFrom(v.getAlpha())) {
                v.setAlpha(alpha);
            }
            if (hasRectChangedFrom(v)) {
                v.setLeftTopRightBottom((int) rect.left, (int) rect.top, (int) rect.right,
                        (int) rect.bottom);
            }
        } else {
            if (allowShadows && hasTranslationZChangedFrom(v.getTranslationZ())) {
                ObjectAnimator anim = ObjectAnimator.ofFloat(v, View.TRANSLATION_Z,
                        v.getTranslationZ(), translationZ);
                animators.add(animation.apply(AnimationProps.TRANSLATION_Z, anim));
            }
            if (hasScaleChangedFrom(v.getScaleX())) {
                ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(v,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, v.getScaleX(), scale),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, v.getScaleX(), scale));
                animators.add(animation.apply(AnimationProps.SCALE, anim));
            }
            if (hasAlphaChangedFrom(v.getAlpha())) {
                ObjectAnimator anim = ObjectAnimator.ofFloat(v, View.ALPHA, v.getAlpha(), alpha);
                animators.add(animation.apply(AnimationProps.ALPHA, anim));
            }
            if (hasRectChangedFrom(v)) {
                Rect fromViewRect = new Rect(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
                Rect toViewRect = new Rect();
                rect.round(toViewRect);
                ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(v,
                        PropertyValuesHolder.ofObject(LTRB, Utilities.RECT_EVALUATOR,
                                fromViewRect, toViewRect));
                animators.add(animation.apply(AnimationProps.BOUNDS, anim));
            }
        }
    }

    /** Reset the transform on a view. */
    public static void reset(TaskView v) {
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setTranslationZ(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        v.getViewBounds().setClipBottom(0);
        v.setLeftTopRightBottom(0, 0, 0, 0);
    }

    @Override
    public String toString() {
        return "R: " + rect + " V: " + visible;
    }
}
