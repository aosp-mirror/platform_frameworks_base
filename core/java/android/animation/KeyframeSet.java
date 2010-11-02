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
import java.util.Arrays;

/**
 * This class holds a collection of Keyframe objects and is called by ValueAnimator to calculate
 * values between those keyframes for a given animation. The class internal to the animation
 * package because it is an implementation detail of how Keyframes are stored and used.
 */
class KeyframeSet {

    private int mNumKeyframes;

    Keyframe mFirstKeyframe;
    Keyframe mLastKeyframe;
    TimeInterpolator mInterpolator; // only used in the 2-keyframe case
    ArrayList<Keyframe> mKeyframes; // only used when there are not 2 keyframes

    public KeyframeSet(Keyframe... keyframes) {
        mNumKeyframes = keyframes.length;
        mKeyframes = new ArrayList<Keyframe>();
        mKeyframes.addAll(Arrays.asList(keyframes));
        mFirstKeyframe = mKeyframes.get(0);
        mLastKeyframe = mKeyframes.get(mNumKeyframes - 1);
        mInterpolator = mLastKeyframe.getInterpolator();
    }

    public static KeyframeSet ofInt(int... values) {
        int numKeyframes = values.length;
        Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes,2)];
        if (numKeyframes == 1) {
            keyframes[0] = new Keyframe(0f, (Object) null);
            keyframes[1] = new Keyframe(1f, values[0]);
        } else {
            keyframes[0] = new Keyframe(0f, values[0]);
            for (int i = 1; i < numKeyframes; ++i) {
                keyframes[i] = new Keyframe((float) i / (numKeyframes - 1), values[i]);
            }
        }
        return new KeyframeSet(keyframes);
    }

    public static KeyframeSet ofFloat(float... values) {
        int numKeyframes = values.length;
        Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes,2)];
        if (numKeyframes == 1) {
            keyframes[0] = new Keyframe(0f, (Object) null);
            keyframes[1] = new Keyframe(1f, values[0]);
        } else {
            keyframes[0] = new Keyframe(0f, values[0]);
            for (int i = 1; i < numKeyframes; ++i) {
                keyframes[i] = new Keyframe((float) i / (numKeyframes - 1), values[i]);
            }
        }
        return new KeyframeSet(keyframes);
    }

    public static KeyframeSet ofDouble(double... values) {
        int numKeyframes = values.length;
        Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes,2)];
        if (numKeyframes == 1) {
            keyframes[0] = new Keyframe(0f, (Object) null);
            keyframes[1] = new Keyframe(1f, values[0]);
        } else {
            keyframes[0] = new Keyframe(0f, values[0]);
            for (int i = 1; i < numKeyframes; ++i) {
                keyframes[i] = new Keyframe((float) i / (numKeyframes - 1), values[i]);
            }
        }
        return new KeyframeSet(keyframes);
    }

    public static KeyframeSet ofLong(long... values) {
        int numKeyframes = values.length;
        Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes,2)];
        if (numKeyframes == 1) {
            keyframes[0] = new Keyframe(0f, (Object) null);
            keyframes[1] = new Keyframe(1f, values[0]);
        } else {
            keyframes[0] = new Keyframe(0f, values[0]);
            for (int i = 1; i < numKeyframes; ++i) {
                keyframes[i] = new Keyframe((float) i / (numKeyframes - 1), values[i]);
            }
        }
        return new KeyframeSet(keyframes);
    }

    public static KeyframeSet ofObject(Object... values) {
        int numKeyframes = values.length;
        Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes,2)];
        if (numKeyframes == 1) {
            keyframes[0] = new Keyframe(0f, (Object) null);
            keyframes[1] = new Keyframe(1f, values[0]);
        } else {
            keyframes[0] = new Keyframe(0f, values[0]);
            for (int i = 1; i < numKeyframes; ++i) {
                keyframes[i] = new Keyframe((float) i / (numKeyframes - 1), values[i]);
            }
        }
        return new KeyframeSet(keyframes);
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

        // Special-case optimization for the common case of only two keyframes
        if (mNumKeyframes == 2) {
            if (mInterpolator != null) {
                fraction = mInterpolator.getInterpolation(fraction);
            }
            return evaluator.evaluate(fraction, mFirstKeyframe.getValue(),
                    mLastKeyframe.getValue());
        }

        if (fraction <= 0f) {
            final Keyframe nextKeyframe = mKeyframes.get(1);
            final TimeInterpolator interpolator = nextKeyframe.getInterpolator();
            if (interpolator != null) {
                fraction = interpolator.getInterpolation(fraction);
            }
            final float prevFraction = mFirstKeyframe.getFraction();
            float intervalFraction = (fraction - prevFraction) /
                (nextKeyframe.getFraction() - prevFraction);
            return evaluator.evaluate(intervalFraction, mFirstKeyframe.getValue(),
                    nextKeyframe.getValue());
        } else if (fraction >= 1f) {
            final Keyframe prevKeyframe = mKeyframes.get(mNumKeyframes - 2);
            final TimeInterpolator interpolator = mLastKeyframe.getInterpolator();
            if (interpolator != null) {
                fraction = interpolator.getInterpolation(fraction);
            }
            final float prevFraction = prevKeyframe.getFraction();
            float intervalFraction = (fraction - prevFraction) /
                (mLastKeyframe.getFraction() - prevFraction);
            return evaluator.evaluate(intervalFraction, prevKeyframe.getValue(),
                    mLastKeyframe.getValue());
        }
        Keyframe prevKeyframe = mFirstKeyframe;
        for (int i = 1; i < mNumKeyframes; ++i) {
            Keyframe nextKeyframe = mKeyframes.get(i);
            if (fraction < nextKeyframe.getFraction()) {
                final TimeInterpolator interpolator = nextKeyframe.getInterpolator();
                if (interpolator != null) {
                    fraction = interpolator.getInterpolation(fraction);
                }
                final float prevFraction = prevKeyframe.getFraction();
                float intervalFraction = (fraction - prevFraction) /
                    (nextKeyframe.getFraction() - prevFraction);
                return evaluator.evaluate(intervalFraction, prevKeyframe.getValue(),
                        nextKeyframe.getValue());
            }
            prevKeyframe = nextKeyframe;
        }
        // shouldn't reach here
        return mLastKeyframe.getValue();
    }
}
