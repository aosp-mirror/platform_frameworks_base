/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.animation.Keyframe;
import android.util.MathUtils;
import android.util.Property;
import android.view.animation.Interpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class, that handles similar properties as animators (delay, interpolators)
 * but can have a float input as to the amount they should be in effect.  This allows
 * easier animation that tracks input.
 *
 * All "delays" and "times" are as fractions from 0-1.
 */
public class TouchAnimator {

    private final Object[] mTargets;
    private final Property[] mProperties;
    private final KeyframeSet[] mKeyframeSets;
    private final float mStartDelay;
    private final float mEndDelay;
    private final float mSpan;
    private final Interpolator mInterpolator;
    private final Listener mListener;
    private float mLastT;

    private TouchAnimator(Object[] targets, Property[] properties, KeyframeSet[] keyframeSets,
            float startDelay, float endDelay, Interpolator interpolator, Listener listener) {
        mTargets = targets;
        mProperties = properties;
        mKeyframeSets = keyframeSets;
        mStartDelay = startDelay;
        mEndDelay = endDelay;
        mSpan = (1 - mEndDelay - mStartDelay);
        mInterpolator = interpolator;
        mListener = listener;
    }

    public void setPosition(float fraction) {
        float t = MathUtils.constrain((fraction - mStartDelay) / mSpan, 0, 1);
        if (mInterpolator != null) {
            t = mInterpolator.getInterpolation(t);
        }
        if (mListener != null) {
            if (mLastT == 0 || mLastT == 1) {
                if (t != 0) {
                    mListener.onAnimationStarted();
                }
            } else if (t == 1) {
                mListener.onAnimationAtEnd();
            } else if (t == 0) {
                mListener.onAnimationAtStart();
            }
            mLastT = t;
        }
        for (int i = 0; i < mTargets.length; i++) {
            Object value = mKeyframeSets[i].getValue(t);
            mProperties[i].set(mTargets[i], value);
        }
    }

    public static class ListenerAdapter implements Listener {
        @Override
        public void onAnimationAtStart() { }

        @Override
        public void onAnimationAtEnd() { }

        @Override
        public void onAnimationStarted() { }
    }

    public interface Listener {
        /**
         * Called when the animator moves into a position of "0". Start and end delays are
         * taken into account, so this position may cover a range of fractional inputs.
         */
        void onAnimationAtStart();

        /**
         * Called when the animator moves into a position of "0". Start and end delays are
         * taken into account, so this position may cover a range of fractional inputs.
         */
        void onAnimationAtEnd();

        /**
         * Called when the animator moves out of the start or end position and is in a transient
         * state.
         */
        void onAnimationStarted();
    }

    public static class Builder {
        private List<Object> mTargets = new ArrayList<>();
        private List<Property> mProperties = new ArrayList<>();
        private List<KeyframeSet> mValues = new ArrayList<>();

        private float mStartDelay;
        private float mEndDelay;
        private Interpolator mInterpolator;
        private Listener mListener;

        public Builder addFloat(Object target, String property, float... values) {
            add(target, property, KeyframeSet.ofFloat(values));
            return this;
        }

        public Builder addInt(Object target, String property, int... values) {
            add(target, property, KeyframeSet.ofInt(values));
            return this;
        }

        private void add(Object target, String property, KeyframeSet keyframeSet) {
            mTargets.add(target);
            // TODO: Optimize the properties here, to use those in View when possible.
            mProperties.add(Property.of(target.getClass(), float.class, property));
            mValues.add(keyframeSet);
        }

        public Builder setStartDelay(float startDelay) {
            mStartDelay = startDelay;
            return this;
        }

        public Builder setEndDelay(float endDelay) {
            mEndDelay = endDelay;
            return this;
        }

        public Builder setInterpolator(Interpolator intepolator) {
            mInterpolator = intepolator;
            return this;
        }

        public Builder setListener(Listener listener) {
            mListener = listener;
            return this;
        }

        public TouchAnimator build() {
            return new TouchAnimator(mTargets.toArray(new Object[mTargets.size()]),
                    mProperties.toArray(new Property[mProperties.size()]),
                    mValues.toArray(new KeyframeSet[mValues.size()]),
                    mStartDelay, mEndDelay, mInterpolator, mListener);
        }
    }

    private static abstract class KeyframeSet {

        private final Keyframe[] mKeyframes;

        public KeyframeSet(Keyframe[] keyframes) {
            mKeyframes = keyframes;
        }

        Object getValue(float fraction) {
            int i;
            for (i = 1; i < mKeyframes.length && fraction > mKeyframes[i].getFraction(); i++) ;
            Keyframe first = mKeyframes[i - 1];
            Keyframe second = mKeyframes[i];
            float amount = (fraction - first.getFraction())
                    / (second.getFraction() - first.getFraction());
            return interpolate(first, second, amount);
        }

        protected abstract Object interpolate(Keyframe first, Keyframe second, float amount);

        public static KeyframeSet ofInt(int... values) {
            int numKeyframes = values.length;
            Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes, 2)];
            if (numKeyframes == 1) {
                keyframes[0] = Keyframe.ofInt(0f);
                keyframes[1] = Keyframe.ofInt(1f, values[0]);
            } else {
                keyframes[0] = Keyframe.ofInt(0f, values[0]);
                for (int i = 1; i < numKeyframes; ++i) {
                    keyframes[i] = Keyframe.ofInt((float) i / (numKeyframes - 1), values[i]);
                }
            }
            return new IntKeyframeSet(keyframes);
        }

        public static KeyframeSet ofFloat(float... values) {
            int numKeyframes = values.length;
            Keyframe keyframes[] = new Keyframe[Math.max(numKeyframes, 2)];
            if (numKeyframes == 1) {
                keyframes[0] = Keyframe.ofFloat(0f);
                keyframes[1] = Keyframe.ofFloat(1f, values[0]);
            } else {
                keyframes[0] = Keyframe.ofFloat(0f, values[0]);
                for (int i = 1; i < numKeyframes; ++i) {
                    keyframes[i] = Keyframe.ofFloat((float) i / (numKeyframes - 1), values[i]);
                }
            }
            return new FloatKeyframeSet(keyframes);
        }
    }

    private static class FloatKeyframeSet extends KeyframeSet {
        public FloatKeyframeSet(Keyframe[] keyframes) {
            super(keyframes);
        }

        @Override
        protected Object interpolate(Keyframe first, Keyframe second, float amount) {
            float firstFloat = (float) first.getValue();
            float secondFloat = (float) second.getValue();
            return firstFloat + (secondFloat - firstFloat) * amount;
        }
    }

    private static class IntKeyframeSet extends KeyframeSet {
        public IntKeyframeSet(Keyframe[] keyframes) {
            super(keyframes);
        }

        @Override
        protected Object interpolate(Keyframe first, Keyframe second, float amount) {
            int firstFloat = (int) first.getValue();
            int secondFloat = (int) second.getValue();
            return (int) (firstFloat + (secondFloat - firstFloat) * amount);
        }
    }
}
