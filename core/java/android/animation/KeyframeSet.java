/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.animation;

import java.util.ArrayList;

import android.view.animation.Interpolator;

/**
 * This class holds a collection of Keyframe objects and is called by Animator to calculate
 * values between those keyframes for a given animation. The class internal to the animation
 * package because it is an implementation detail of how Keyframes are stored and used.
 */
class KeyframeSet {

    private int mNumKeyframes;

    ArrayList<Keyframe> mKeyframes;

    public KeyframeSet(Keyframe... keyframes) {
        mKeyframes = new ArrayList<Keyframe>();
        for (Keyframe keyframe : keyframes) {
            mKeyframes.add(keyframe);
        }
        mNumKeyframes = mKeyframes.size();
    }

    /**
     * Gets the animated value, given the elapsed fraction of the animation (interpolated by the
     * animation's interpolator) and the evaluator used to calculate in-between values. This
     * function maps the input fraction to the appropriate keyframe interval and a fraction
     * between them and returns the interpolated value. Note that the input fraction may fall
     * outside the [0-1] bounds, if the animation's interpolator made that happen (e.g., a
     * spring interpolation that might send the fraction past 1.0). We handle this situation by
     * just using the two keyframes at the appropriate end when the value is outside those bounds.
     *
     * @param fraction The elapsed fraction of the animation
     * @param evaluator The type evaluator to use when calculating the interpolated values.
     * @return The animated value.
     */
    public Object getValue(float fraction, TypeEvaluator evaluator) {
        // TODO: special-case 2-keyframe common case

        if (fraction <= 0f) {
            final Keyframe prevKeyframe = mKeyframes.get(0);
            final Keyframe nextKeyframe = mKeyframes.get(1);
            final Interpolator interpolator = nextKeyframe.getInterpolator();
            if (interpolator != null) {
                fraction = interpolator.getInterpolation(fraction);
            }
            float intervalFraction = (fraction - prevKeyframe.getFraction()) /
                (nextKeyframe.getFraction() - prevKeyframe.getFraction());
            return evaluator.evaluate(intervalFraction, prevKeyframe.getValue(),
                    nextKeyframe.getValue());
        } else if (fraction >= 1f) {
            final Keyframe prevKeyframe = mKeyframes.get(mNumKeyframes - 2);
            final Keyframe nextKeyframe = mKeyframes.get(mNumKeyframes - 1);
            final Interpolator interpolator = nextKeyframe.getInterpolator();
            if (interpolator != null) {
                fraction = interpolator.getInterpolation(fraction);
            }
            float intervalFraction = (fraction - prevKeyframe.getFraction()) /
                (nextKeyframe.getFraction() - prevKeyframe.getFraction());
            return evaluator.evaluate(intervalFraction, prevKeyframe.getValue(),
                    nextKeyframe.getValue());
        }
        Keyframe prevKeyframe = mKeyframes.get(0);
        for (int i = 1; i < mNumKeyframes; ++i) {
            Keyframe nextKeyframe = mKeyframes.get(i);
            if (fraction < nextKeyframe.getFraction()) {
                final Interpolator interpolator = nextKeyframe.getInterpolator();
                if (interpolator != null) {
                    fraction = interpolator.getInterpolation(fraction);
                }
                float intervalFraction = (fraction - prevKeyframe.getFraction()) /
                    (nextKeyframe.getFraction() - prevKeyframe.getFraction());
                return evaluator.evaluate(intervalFraction, prevKeyframe.getValue(),
                        nextKeyframe.getValue());
            }
            prevKeyframe = nextKeyframe;
        }
        // shouldn't get here
        return mKeyframes.get(mNumKeyframes - 1).getValue();
    }
}
