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

import android.view.animation.Interpolator;

/**
 * This class holds a time/value pair for an animation. The Keyframe class is used
 * by {@link Animator} to define the values that the animation target will have over the course
 * of the animation. As the time proceeds from one keyframe to the other, the value of the
 * target object will animate between the value at the previous keyframe and the value at the
 * next keyframe. Each keyframe also holds an option {@link android.view.animation.Interpolator}
 * object, which defines the time interpolation over the intervalue preceding the keyframe.
 */
public class Keyframe {
    /**
     * The time at which mValue will hold true.
     */
    private float mFraction;

    /**
     * The value of the animation at the time mFraction.
     */
    private Object mValue;

    /**
     * The type of the value in this Keyframe. This type is determined at construction time,
     * based on the type of the <code>value</code> object passed into the constructor.
     */
    private Class mValueType;

    /**
     * The optional time interpolator for the interval preceding this keyframe. A null interpolator
     * (the default) results in linear interpolation over the interval.
     */
    private Interpolator mInterpolator = null;

    /**
     * Private constructor, called from the public constructors with the additional
     * <code>valueType</code> parameter.
     *
     * @param fraction The time, expressed as a value between 0 and 1, representing the fraction
     * of time elapsed of the overall animation duration.
     * @param value The value that the object will animate to as the animation time approaches
     * the time in this keyframe, and the the value animated from as the time passes the time in
     * this keyframe.
     * @param valueType The type of the <code>value</code> object. This is used by the
     * {@link #getValue()} functionm, which is queried by {@link Animator} to determine
     * the type of {@link TypeEvaluator} to use to interpolate between values.
     */
    private Keyframe(float fraction, Object value, Class valueType) {
        mFraction = fraction;
        mValue = value;
        mValueType = valueType;
    }

    /**
     * Constructs a Keyframe object with the given time and value. The time defines the
     * time, as a proportion of an overall animation's duration, at which the value will hold true
     * for the animation. The value for the animation between keyframes will be calculated as
     * an interpolation between the values at those keyframes.
     *
     * @param fraction The time, expressed as a value between 0 and 1, representing the fraction
     * of time elapsed of the overall animation duration.
     * @param value The value that the object will animate to as the animation time approaches
     * the time in this keyframe, and the the value animated from as the time passes the time in
     * this keyframe.
     */
    public Keyframe(float fraction, Object value) {
        this(fraction, value, Object.class);
    }

    /**
     * Constructs a Keyframe object with the given time and integer value. The time defines the
     * time, as a proportion of an overall animation's duration, at which the value will hold true
     * for the animation. The value for the animation between keyframes will be calculated as
     * an interpolation between the values at those keyframes.
     *
     * @param fraction The time, expressed as a value between 0 and 1, representing the fraction
     * of time elapsed of the overall animation duration.
     * @param value The value that the object will animate to as the animation time approaches
     * the time in this keyframe, and the the value animated from as the time passes the time in
     * this keyframe.
     */
    public Keyframe(float fraction, int value) {
        this(fraction, value, int.class);
    }

    /**
     * Constructs a Keyframe object with the given time and float value. The time defines the
     * time, as a proportion of an overall animation's duration, at which the value will hold true
     * for the animation. The value for the animation between keyframes will be calculated as
     * an interpolation between the values at those keyframes.
     *
     * @param fraction The time, expressed as a value between 0 and 1, representing the fraction
     * of time elapsed of the overall animation duration.
     * @param value The value that the object will animate to as the animation time approaches
     * the time in this keyframe, and the the value animated from as the time passes the time in
     * this keyframe.
     */
    public Keyframe(float fraction, float value) {
        this(fraction, value, float.class);
    }

    /**
     * Constructs a Keyframe object with the given time and double value. The time defines the
     * time, as a proportion of an overall animation's duration, at which the value will hold true
     * for the animation. The value for the animation between keyframes will be calculated as
     * an interpolation between the values at those keyframes.
     *
     * @param fraction The time, expressed as a value between 0 and 1, representing the fraction
     * of time elapsed of the overall animation duration.
     * @param value The value that the object will animate to as the animation time approaches
     * the time in this keyframe, and the the value animated from as the time passes the time in
     * this keyframe.
     */
    public Keyframe(float fraction, double value) {
        this(fraction, value, double.class);
    }

    /**
     * Gets the value for this Keyframe.
     *
     * @return The value for this Keyframe.
     */
    public Object getValue() {
        return mValue;
    }

    /**
     * Sets the value for this Keyframe.
     *
     * @param value value for this Keyframe.
     */
    public void setValue(Object value) {
        mValue = value;
    }

    /**
     * Gets the time for this keyframe, as a fraction of the overall animation duration.
     *
     * @return The time associated with this keyframe, as a fraction of the overall animation
     * duration. This should be a value between 0 and 1.
     */
    public float getFraction() {
        return mFraction;
    }

    /**
     * Sets the time for this keyframe, as a fraction of the overall animation duration.
     *
     * @param fraction time associated with this keyframe, as a fraction of the overall animation
     * duration. This should be a value between 0 and 1.
     */
    public void setFraction(float fraction) {
        mFraction = fraction;
    }

    /**
     * Gets the optional interpolator for this Keyframe. A value of <code>null</code> indicates
     * that there is no interpolation, which is the same as linear interpolation.
     *
     * @return The optional interpolator for this Keyframe.
     */
    public Interpolator getInterpolator() {
        return mInterpolator;
    }

    /**
     * Sets the optional interpolator for this Keyframe. A value of <code>null</code> indicates
     * that there is no interpolation, which is the same as linear interpolation.
     *
     * @return The optional interpolator for this Keyframe.
     */
    public void setInterpolator(Interpolator interpolator) {
        mInterpolator = interpolator;
    }

    /**
     * Gets the type of keyframe. This information is used by Animator to determine the type of
     * {@link TypeEvaluator} to use when calculating values between keyframes. The type is based
     * on the type of Keyframe created.
     *
     * @return The type of the value stored in the Keyframe.
     */
    public Class getType() {
        return mValueType;
    }
}
