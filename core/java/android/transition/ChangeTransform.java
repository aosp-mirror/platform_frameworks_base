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
package android.transition;

import android.animation.Animator;
import android.animation.FloatArrayEvaluator;
import android.animation.ObjectAnimator;
import android.util.FloatProperty;
import android.util.Property;
import android.view.View;
import android.view.ViewGroup;

/**
 * This Transition captures scale and rotation for Views before and after the
 * scene change and animates those changes during the transition.
 *
 * <p>ChangeTransform does not work when the pivot changes between scenes, so either the
 * pivot must be set to prevent automatic pivot adjustment or the View's size must be unchanged.</p>
 */
public class ChangeTransform extends Transition {

    private static final String TAG = "ChangeTransform";

    private static final String PROPNAME_SCALE_X = "android:changeTransform:scaleX";
    private static final String PROPNAME_SCALE_Y = "android:changeTransform:scaleY";
    private static final String PROPNAME_ROTATION_X = "android:changeTransform:rotationX";
    private static final String PROPNAME_ROTATION_Y = "android:changeTransform:rotationY";
    private static final String PROPNAME_ROTATION_Z = "android:changeTransform:rotationZ";
    private static final String PROPNAME_PIVOT_X = "android:changeTransform:pivotX";
    private static final String PROPNAME_PIVOT_Y = "android:changeTransform:pivotY";

    private static final String[] sTransitionProperties = {
            PROPNAME_SCALE_X,
            PROPNAME_SCALE_Y,
            PROPNAME_ROTATION_X,
            PROPNAME_ROTATION_Y,
            PROPNAME_ROTATION_Z,
    };

    private static final FloatProperty<View>[] sChangedProperties = new FloatProperty[] {
            (FloatProperty) View.SCALE_X,
            (FloatProperty) View.SCALE_Y,
            (FloatProperty) View.ROTATION_X,
            (FloatProperty) View.ROTATION_Y,
            (FloatProperty) View.ROTATION,
    };

    private static Property<View, float[]> TRANSFORMS = new Property<View, float[]>(float[].class,
            "transforms") {
        @Override
        public float[] get(View object) {
            return null;
        }

        @Override
        public void set(View view, float[] values) {
            for (int i = 0; i < values.length; i++) {
                float value = values[i];
                if (!Float.isNaN(value)) {
                    sChangedProperties[i].setValue(view, value);
                }
            }
        }
    };

    @Override
    public String[] getTransitionProperties() {
        return sTransitionProperties;
    }

    private void captureValues(TransitionValues values) {
        View view = values.view;
        if (view.getVisibility() == View.GONE) {
            return;
        }

        values.values.put(PROPNAME_SCALE_X, view.getScaleX());
        values.values.put(PROPNAME_SCALE_Y, view.getScaleY());
        values.values.put(PROPNAME_PIVOT_X, view.getPivotX());
        values.values.put(PROPNAME_PIVOT_Y, view.getPivotY());
        values.values.put(PROPNAME_ROTATION_X, view.getRotationX());
        values.values.put(PROPNAME_ROTATION_Y, view.getRotationY());
        values.values.put(PROPNAME_ROTATION_Z, view.getRotation());
    }

    @Override
    public void captureStartValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public void captureEndValues(TransitionValues transitionValues) {
        captureValues(transitionValues);
    }

    @Override
    public Animator createAnimator(final ViewGroup sceneRoot, TransitionValues startValues,
            TransitionValues endValues) {
        if (startValues == null || endValues == null
                || !startValues.values.containsKey(PROPNAME_SCALE_X)
                || !endValues.values.containsKey(PROPNAME_SCALE_X)
                || !isPivotSame(startValues, endValues)
                || !isChanged(startValues, endValues)) {
            return null;
        }

        float[] start = createValues(startValues);
        float[] end = createValues(endValues);
        for (int i = 0; i < start.length; i++) {
            if (start[i] == end[i]) {
                start[i] = Float.NaN;
                end[i] = Float.NaN;
            } else {
                sChangedProperties[i].setValue(endValues.view, start[i]);
            }
        }
        FloatArrayEvaluator evaluator = new FloatArrayEvaluator(new float[start.length]);
        return ObjectAnimator.ofObject(endValues.view, TRANSFORMS, evaluator, start, end);
    }

    private static float[] createValues(TransitionValues transitionValues) {
        float[] values = new float[sChangedProperties.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = (Float) transitionValues.values.get(sTransitionProperties[i]);
        }
        return values;
    }

    private static boolean isPivotSame(TransitionValues startValues, TransitionValues endValues) {
        float startPivotX = (Float) startValues.values.get(PROPNAME_PIVOT_X);
        float startPivotY = (Float) startValues.values.get(PROPNAME_PIVOT_Y);
        float endPivotX = (Float) endValues.values.get(PROPNAME_PIVOT_X);
        float endPivotY = (Float) endValues.values.get(PROPNAME_PIVOT_Y);

        // We don't support pivot changes, because they could be automatically set
        // and we can't end the state in an automatic state.
        return startPivotX == endPivotX && startPivotY == endPivotY;
    }

    private static boolean isChanged(TransitionValues startValues, TransitionValues endValues) {
        for (int i = 0; i < sChangedProperties.length; i++) {
            Object start = startValues.values.get(sTransitionProperties[i]);
            Object end = endValues.values.get(sTransitionProperties[i]);
            if (!start.equals(end)) {
                return true;
            }
        }
        return false;
    }
}
