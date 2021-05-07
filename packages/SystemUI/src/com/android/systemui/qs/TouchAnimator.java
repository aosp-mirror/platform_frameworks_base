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

import android.util.FloatProperty;
import android.util.MathUtils;
import android.util.Property;
import android.view.View;
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
    private final KeyframeSet[] mKeyframeSets;
    private final float mStartDelay;
    private final float mEndDelay;
    private final float mSpan;
    private final Interpolator mInterpolator;
    private final Listener mListener;
    private float mLastT = -1;

    private TouchAnimator(Object[] targets, KeyframeSet[] keyframeSets,
            float startDelay, float endDelay, Interpolator interpolator, Listener listener) {
        mTargets = targets;
        mKeyframeSets = keyframeSets;
        mStartDelay = startDelay;
        mEndDelay = endDelay;
        mSpan = (1 - mEndDelay - mStartDelay);
        mInterpolator = interpolator;
        mListener = listener;
    }

    public void setPosition(float fraction) {
        if (Float.isNaN(fraction)) return;
        float t = MathUtils.constrain((fraction - mStartDelay) / mSpan, 0, 1);
        if (mInterpolator != null) {
            t = mInterpolator.getInterpolation(t);
        }
        if (t == mLastT) {
            return;
        }
        if (mListener != null) {
            if (t == 1) {
                mListener.onAnimationAtEnd();
            } else if (t == 0) {
                mListener.onAnimationAtStart();
            } else if (mLastT <= 0 || mLastT == 1) {
                mListener.onAnimationStarted();
            }
            mLastT = t;
        }
        for (int i = 0; i < mTargets.length; i++) {
            mKeyframeSets[i].setValue(t, mTargets[i]);
        }
    }

    private static final FloatProperty<TouchAnimator> POSITION =
            new FloatProperty<TouchAnimator>("position") {
        @Override
        public void setValue(TouchAnimator touchAnimator, float value) {
            touchAnimator.setPosition(value);
        }

        @Override
        public Float get(TouchAnimator touchAnimator) {
            return touchAnimator.mLastT;
        }
    };

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
         * Called when the animator moves into a position of "1". Start and end delays are
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
        private List<KeyframeSet> mValues = new ArrayList<>();

        private float mStartDelay;
        private float mEndDelay;
        private Interpolator mInterpolator;
        private Listener mListener;

        public Builder addFloat(Object target, String property, float... values) {
            add(target, KeyframeSet.ofFloat(getProperty(target, property, float.class), values));
            return this;
        }

        public Builder addInt(Object target, String property, int... values) {
            add(target, KeyframeSet.ofInt(getProperty(target, property, int.class), values));
            return this;
        }

        private void add(Object target, KeyframeSet keyframeSet) {
            mTargets.add(target);
            mValues.add(keyframeSet);
        }

        private static Property getProperty(Object target, String property, Class<?> cls) {
            if (target instanceof View) {
                switch (property) {
                    case "translationX":
                        return View.TRANSLATION_X;
                    case "translationY":
                        return View.TRANSLATION_Y;
                    case "translationZ":
                        return View.TRANSLATION_Z;
                    case "alpha":
                        return View.ALPHA;
                    case "rotation":
                        return View.ROTATION;
                    case "x":
                        return View.X;
                    case "y":
                        return View.Y;
                    case "scaleX":
                        return View.SCALE_X;
                    case "scaleY":
                        return View.SCALE_Y;
                }
            }
            if (target instanceof TouchAnimator && "position".equals(property)) {
                return POSITION;
            }
            return Property.of(target.getClass(), cls, property);
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
                    mValues.toArray(new KeyframeSet[mValues.size()]),
                    mStartDelay, mEndDelay, mInterpolator, mListener);
        }
    }

    private static abstract class KeyframeSet {
        private final float mFrameWidth;
        private final int mSize;

        public KeyframeSet(int size) {
            mSize = size;
            mFrameWidth = 1 / (float) (size - 1);
        }

        void setValue(float fraction, Object target) {
            int i = MathUtils.constrain((int) Math.ceil(fraction / mFrameWidth), 1, mSize - 1);
            float amount = (fraction - mFrameWidth * (i - 1)) / mFrameWidth;
            interpolate(i, amount, target);
        }

        protected abstract void interpolate(int index, float amount, Object target);

        public static KeyframeSet ofInt(Property property, int... values) {
            return new IntKeyframeSet((Property<?, Integer>) property, values);
        }

        public static KeyframeSet ofFloat(Property property, float... values) {
            return new FloatKeyframeSet((Property<?, Float>) property, values);
        }
    }

    private static class FloatKeyframeSet<T> extends KeyframeSet {
        private final float[] mValues;
        private final Property<T, Float> mProperty;

        public FloatKeyframeSet(Property<T, Float> property, float[] values) {
            super(values.length);
            mProperty = property;
            mValues = values;
        }

        @Override
        protected void interpolate(int index, float amount, Object target) {
            float firstFloat = mValues[index - 1];
            float secondFloat = mValues[index];
            mProperty.set((T) target, firstFloat + (secondFloat - firstFloat) * amount);
        }
    }

    private static class IntKeyframeSet<T> extends KeyframeSet {
        private final int[] mValues;
        private final Property<T, Integer> mProperty;

        public IntKeyframeSet(Property<T, Integer> property, int[] values) {
            super(values.length);
            mProperty = property;
            mValues = values;
        }

        @Override
        protected void interpolate(int index, float amount, Object target) {
            int firstFloat = mValues[index - 1];
            int secondFloat = mValues[index];
            mProperty.set((T) target, (int) (firstFloat + (secondFloat - firstFloat) * amount));
        }
    }
}
