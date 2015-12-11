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

import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.util.IntProperty;
import android.util.Property;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.view.animation.Interpolator;


/* The transform state for a task view */
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

    // TODO: Move this out of the transform
    public int startDelay = 0;

    public float translationZ = 0;
    public float scale = 1f;
    public float alpha = 1f;
    public float thumbnailScale = 1f;

    public boolean visible = false;
    float p = 0f;

    // This is a window-space rect used for positioning the task in the stack and freeform workspace
    public RectF rect = new RectF();

    public TaskViewTransform() {
        // Do nothing
    }

    /**
     * Resets the current transform.
     */
    public void reset() {
        startDelay = 0;
        translationZ = 0;
        scale = 1f;
        alpha = 1f;
        thumbnailScale = 1f;
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

    /** Applies this transform to a view. */
    public void applyToTaskView(TaskView v, int duration, Interpolator interp, boolean allowLayers,
            boolean allowShadows, ValueAnimator.AnimatorUpdateListener updateCallback) {
        // Check to see if any properties have changed, and update the task view
        if (duration > 0) {
            ViewPropertyAnimator anim = v.animate();
            boolean requiresLayers = false;

            // Animate to the final state
            if (allowShadows && hasTranslationZChangedFrom(v.getTranslationZ())) {
                anim.translationZ(translationZ);
            }
            if (hasScaleChangedFrom(v.getScaleX())) {
                anim.scaleX(scale)
                    .scaleY(scale);
                requiresLayers = true;
            }
            if (hasAlphaChangedFrom(v.getAlpha())) {
                // Use layers if we animate alpha
                anim.alpha(alpha);
                requiresLayers = true;
            }
            if (requiresLayers && allowLayers) {
                anim.withLayer();
            }
            if (updateCallback != null) {
                anim.setUpdateListener(updateCallback);
            } else {
                anim.setUpdateListener(null);
            }
            anim.setListener(null);
            anim.setStartDelay(startDelay)
                    .setDuration(duration)
                    .setInterpolator(interp)
                    .start();
        } else {
            // Set the changed properties
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
        }
    }

    /** Reset the transform on a view. */
    public static void reset(TaskView v) {
        // Cancel any running animations and reset the translation in case something else (like a
        // dismiss animation) changes it
        v.animate().cancel();
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setTranslationZ(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(1f);
        v.getViewBounds().setClipBottom(0, false /* forceUpdate */);
        v.setLeftTopRightBottom(0, 0, 0, 0);
        v.mThumbnailView.setBitmapScale(1f);
    }

    @Override
    public String toString() {
        return "TaskViewTransform delay: " + startDelay + " z: " + translationZ +
                " scale: " + scale + " alpha: " + alpha + " visible: " + visible +
                " rect: " + rect + " p: " + p;
    }
}
