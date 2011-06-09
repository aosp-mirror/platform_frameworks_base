/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.widget.multiwaveview;

import java.util.ArrayList;
import java.util.HashMap;

import android.animation.Animator.AnimatorListener;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator.AnimatorUpdateListener;

class Tweener {
    private static final String TAG = "Tweener";

    private Object object;
    ObjectAnimator animator;
    private static HashMap<Object, Tweener> sTweens = new HashMap<Object, Tweener>();

    public Tweener(Object obj, ObjectAnimator anim) {
        object = obj;
        animator = anim;
    }

    public static Tweener to(Object object, long duration, Object... vars) {
        long delay = 0;
        AnimatorUpdateListener updateListener = null;
        AnimatorListener listener = null;
        TimeInterpolator interpolator = null;

        // Iterate through arguments and discover properties to animate
        ArrayList<PropertyValuesHolder> props = new ArrayList<PropertyValuesHolder>(vars.length/2);
        for (int i = 0; i < vars.length; i+=2) {
            if (!(vars[i] instanceof String)) {
                throw new IllegalArgumentException("Key must be a string: " + vars[i]);
            }
            String key = (String) vars[i];
            Object value = vars[i+1];
            if ("simultaneousTween".equals(key)) {
                // TODO
            } else if ("ease".equals(key)) {
                interpolator = (TimeInterpolator) value; // TODO: multiple interpolators?
            } else if ("onUpdate".equals(key) || "onUpdateListener".equals(key)) {
                updateListener = (AnimatorUpdateListener) value;
            } else if ("onComplete".equals(key) || "onCompleteListener".equals(key)) {
                listener = (AnimatorListener) value;
            } else if ("delay".equals(key)) {
                delay = ((Number) value).longValue();
            } else if ("syncWith".equals(key)) {
                // TODO
            } else if (value instanceof Number[]) {
                // TODO: support Tween.from()
            } else if (value instanceof Number) {
                float floatValue = ((Number)value).floatValue();
                props.add(PropertyValuesHolder.ofFloat(key, floatValue));
            } else {
                throw new IllegalArgumentException(
                        "Bad argument for key \"" + key + "with value" + value);
            }
        }

        // Re-use existing tween, if present
        Tweener tween = sTweens.get(object);
        if (tween == null) {
            ObjectAnimator anim = ObjectAnimator.ofPropertyValuesHolder(object,
                    props.toArray(new PropertyValuesHolder[props.size()]));
            tween = new Tweener(object, anim);
            sTweens.put(object, tween);
        } else {
            tween.animator.cancel();
            replace(props, object);
        }

        if (interpolator != null) {
            tween.animator.setInterpolator(interpolator);
        }

        // Update animation with properties discovered in loop above
        tween.animator.setStartDelay(delay);
        tween.animator.setDuration(duration);
        if (updateListener != null) {
            tween.animator.removeAllUpdateListeners(); // There should be only one
            tween.animator.addUpdateListener(updateListener);
        }
        if (listener != null) {
            tween.animator.removeAllListeners(); // There should be only one.
            tween.animator.addListener(listener);
        }
        tween.animator.start();

        return tween;
    }

    Tweener from(Object object, long duration, Object... vars) {
        // TODO:  for v of vars
        //            toVars[v] = object[v]
        //            object[v] = vars[v]
        return Tweener.to(object, duration, vars);
    }

    static void replace(ArrayList<PropertyValuesHolder> props, Object... args) {
        for (final Object killobject : args) {
            Tweener tween = sTweens.get(killobject);
            if (tween != null) {
                if (killobject == tween.object) {
                    tween.animator.cancel();
                    if (props != null) {
                        tween.animator.setValues(
                                props.toArray(new PropertyValuesHolder[props.size()]));
                    } else {
                        sTweens.remove(tween);
                    }
                }
            }
        }
    }
}
