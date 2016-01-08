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
import android.graphics.RectF;
import android.util.IntProperty;
import android.util.Property;
import android.view.View;

import java.util.ArrayList;

/**
 * The visual properties for a {@link TaskView}.
 */
public class TaskViewTransform {

    public static final Property<View, Integer> LEFT =
            new IntProperty<View>("left") {
                @Override
                public void setValue(View object, int v) {
                    object.setLeft(v);
                }

                @Override
                public Integer get(View object) {
                    return object.getLeft();
                }
            };

    public static final Property<View, Integer> TOP =
            new IntProperty<View>("top") {
                @Override
                public void setValue(View object, int v) {
                    object.setTop(v);
                }

                @Override
                public Integer get(View object) {
                    return object.getTop();
                }
            };

    public static final Property<View, Integer> RIGHT =
            new IntProperty<View>("right") {
                @Override
                public void setValue(View object, int v) {
                    object.setRight(v);
                }

                @Override
                public Integer get(View object) {
                    return object.getRight();
                }
            };

    public static final Property<View, Integer> BOTTOM =
            new IntProperty<View>("bottom") {
                @Override
                public void setValue(View object, int v) {
                    object.setBottom(v);
                }

                @Override
                public Integer get(View object) {
                    return object.getBottom();
                }
            };

    public float translationZ = 0;
    public float scale = 1f;
    public float alpha = 1f;

    public boolean visible = false;
    float p = 0f;

    // This is a window-space rect used for positioning the task in the stack and freeform workspace
    public RectF rect = new RectF();

    /**
     * Resets the current transform.
     */
    public void reset() {
        translationZ = 0;
        scale = 1f;
        alpha = 1f;
        visible = false;
        rect.setEmpty();
        p = 0f;
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
     *
     * @return whether hardware layers are required for this animation.
     */
    public boolean applyToTaskView(TaskView v, ArrayList<Animator> animators,
            TaskViewAnimation taskAnimation, boolean allowShadows) {
        // Return early if not visible
        boolean requiresHwLayers = false;
        if (!visible) {
            return requiresHwLayers;
        }

        if (taskAnimation.isImmediate()) {
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
                animators.add(ObjectAnimator.ofFloat(v, View.TRANSLATION_Z, v.getTranslationZ(),
                        translationZ));
            }
            if (hasScaleChangedFrom(v.getScaleX())) {
                animators.add(ObjectAnimator.ofPropertyValuesHolder(v,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, v.getScaleX(), scale),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, v.getScaleX(), scale)));
            }
            if (hasAlphaChangedFrom(v.getAlpha())) {
                animators.add(ObjectAnimator.ofFloat(v, View.ALPHA, v.getAlpha(), alpha));
                requiresHwLayers = true;
            }
            if (hasRectChangedFrom(v)) {
                animators.add(ObjectAnimator.ofPropertyValuesHolder(v,
                        PropertyValuesHolder.ofInt(LEFT, v.getLeft(), (int) rect.left),
                        PropertyValuesHolder.ofInt(TOP, v.getTop(), (int) rect.top),
                        PropertyValuesHolder.ofInt(RIGHT, v.getRight(), (int) rect.right),
                        PropertyValuesHolder.ofInt(BOTTOM, v.getBottom(), (int) rect.bottom)));
            }
        }
        return requiresHwLayers;
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
}
