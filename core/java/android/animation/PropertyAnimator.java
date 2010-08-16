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

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * This subclass of {@link Animator} provides support for animating properties on target objects.
 * The constructors of this class take parameters to define the target object that will be animated
 * as well as the name of the property that will be animated. Appropriate set/get functions
 * are then determined internally and the animation will call these functions as necessary to
 * animate the property.
 */
public final class PropertyAnimator<T> extends Animator<T> {

    // The target object on which the property exists, set in the constructor
    private Object mTarget;

    private String mPropertyName;

    /**
     * Sets the name of the property that will be animated. This name is used to derive
     * a setter function that will be called to set animated values.
     * For example, a property name of <code>foo</code> will result
     * in a call to the function <code>setFoo()</code> on the target object. If either
     * <code>valueFrom</code> or <code>valueTo</code> is null, then a getter function will
     * also be derived and called.
     *
     * <p>Note that the setter function derived from this property name
     * must take the same parameter type as the
     * <code>valueFrom</code> and <code>valueTo</code> properties, otherwise the call to
     * the setter function will fail.</p>
     *
     * <p>If this PropertyAnimator has been set up to animate several properties together,
     * using more than one PropertyValuesHolder objects, then setting the propertyName simply
     * sets the propertyName in the first of those PropertyValuesHolder objects.</p>
     *
     * @param propertyName The name of the property being animated.
     */
    public void setPropertyName(String propertyName) {
        if (mValues != null) {
            // mValues should always be non-null
            PropertyValuesHolder valuesHolder = mValues[0];
            String oldName = valuesHolder.getPropertyName();
            valuesHolder.setPropertyName(propertyName);
            mValuesMap.remove(oldName);
            mValuesMap.put(propertyName, valuesHolder);
        }
        mPropertyName = propertyName;
    }

    /**
     * Gets the name of the property that will be animated. This name will be used to derive
     * a setter function that will be called to set animated values.
     * For example, a property name of <code>foo</code> will result
     * in a call to the function <code>setFoo()</code> on the target object. If either
     * <code>valueFrom</code> or <code>valueTo</code> is null, then a getter function will
     * also be derived and called.
     */
    public String getPropertyName() {
        return mPropertyName;
    }

    /**
     * Creates a new animation whose parameters come from the specified context and
     * attributes set.
     *
     * @param context the application environment
     * @param attrs the set of attributes holding the animation parameters
     */
    public PropertyAnimator(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.PropertyAnimator);

        setPropertyName(a.getString(
                com.android.internal.R.styleable.PropertyAnimator_propertyName));


        a.recycle();
    }
    /**
     * Determine the setter or getter function using the JavaBeans convention of setFoo or
     * getFoo for a property named 'foo'. This function figures out what the name of the
     * function should be and uses reflection to find the Method with that name on the
     * target object.
     *
     * @param prefix "set" or "get", depending on whether we need a setter or getter.
     * @return Method the method associated with mPropertyName.
     */
    private Method getPropertyFunction(String prefix, Class valueType) {
        // TODO: faster implementation...
        Method returnVal = null;
        String firstLetter = mPropertyName.substring(0, 1);
        String theRest = mPropertyName.substring(1);
        firstLetter = firstLetter.toUpperCase();
        String setterName = prefix + firstLetter + theRest;
        Class args[] = null;
        if (valueType != null) {
            args = new Class[1];
            args[0] = valueType;
        }
        try {
            returnVal = mTarget.getClass().getMethod(setterName, args);
        } catch (NoSuchMethodException e) {
            Log.e("PropertyAnimator",
                    "Couldn't find setter/getter for property " + mPropertyName + ": " + e);
        }
        return returnVal;
    }

    /**
     * A constructor that takes a single property name and set of values. This constructor is
     * used in the simple case of animating a single property.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public method on it called <code>setName()</code>, where <code>name</code> is
     * the value of the <code>propertyName</code> parameter.
     * @param propertyName The name of the property being animated.
     * @param values The set of values to animate between. If there is only one value, it
     * is assumed to be the final value being animated to, and the initial value will be
     * derived on the fly.
     */
    public PropertyAnimator(long duration, Object target, String propertyName, T...values) {
        super(duration, (T[]) values);
        mTarget = target;
        setPropertyName(propertyName);
    }

    /**
     * A constructor that takes <code>PropertyValueHolder</code> values. This constructor should
     * be used when animating several properties at once with the same PropertyAnimator, since
     * PropertyValuesHolder allows you to associate a set of animation values with a property
     * name.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have public methods on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter for
     * each of the PropertyValuesHolder objects.
     * @param values The PropertyValuesHolder objects which hold each the property name and values
     * to animate that property between.
     */
    public PropertyAnimator(long duration, Object target, PropertyValuesHolder...values) {
        super(duration);
        setValues(values);
        mTarget = target;
    }

    /**
     * This function is called immediately before processing the first animation
     * frame of an animation. If there is a nonzero <code>startDelay</code>, the
     * function is called after that delay ends.
     * It takes care of the final initialization steps for the
     * animation. This includes setting mEvaluator, if the user has not yet
     * set it up, and the setter/getter methods, if the user did not supply
     * them.
     *
     *  <p>Overriders of this method should call the superclass method to cause
     *  internal mechanisms to be set up correctly.</p>
     */
    @Override
    void initAnimation() {
        super.initAnimation();
        int numValues = mValues.length;
        for (int i = 0; i < numValues; ++i) {
            mValues[i].setupSetterAndGetter(mTarget);
        }
    }


    /**
     * The target object whose property will be animated by this animation
     *
     * @return The object being animated
     */
    public Object getTarget() {
        return mTarget;
    }

    /**
     * Sets the target object whose property will be animated by this animation
     *
     * @param target The object being animated
     */
    public void setTarget(Object target) {
        mTarget = target;
    }

    /**
     * This method is called with the elapsed fraction of the animation during every
     * animation frame. This function turns the elapsed fraction into an interpolated fraction
     * and then into an animated value (from the evaluator. The function is called mostly during
     * animation updates, but it is also called when the <code>end()</code>
     * function is called, to set the final value on the property.
     *
     * <p>Overrides of this method must call the superclass to perform the calculation
     * of the animated value.</p>
     *
     * @param fraction The elapsed fraction of the animation.
     */
    @Override
    void animateValue(float fraction) {
        super.animateValue(fraction);
        int numValues = mValues.length;
        for (int i = 0; i < numValues; ++i) {
            mValues[i].setAnimatedValue(mTarget);
        }
    }
}
