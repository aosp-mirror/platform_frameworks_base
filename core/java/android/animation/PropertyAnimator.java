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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This subclass of {@link Animator} provides support for animating properties on target objects.
 * The constructors of this class take parameters to define the target object that will be animated
 * as well as the name of the property that will be animated. Appropriate set/get functions
 * are then determined internally and the animation will call these functions as necessary to
 * animate the property.
 */
public final class PropertyAnimator extends Animator {

    // The target object on which the property exists, set in the constructor
    private Object mTarget;

    private String mPropertyName;

    private Method mGetter = null;

    // The property setter that is assigned internally, based on the propertyName passed into
    // the constructor
    private Method mSetter;

    // These maps hold all property entries for a particular class. This map
    // is used to speed up property/setter/getter lookups for a given class/property
    // combination. No need to use reflection on the combination more than once.
    private static final HashMap<Object, HashMap<String, Method>> sSetterPropertyMap =
            new HashMap<Object, HashMap<String, Method>>();
    private static final HashMap<Object, HashMap<String, Method>> sGetterPropertyMap =
            new HashMap<Object, HashMap<String, Method>>();

    // This lock is used to ensure that only one thread is accessing the property maps
    // at a time.
    private ReentrantReadWriteLock propertyMapLock = new ReentrantReadWriteLock();

    // Used to pass single value to varargs parameter in setter invocation
    private Object[] mTmpValueArray = new Object[1];


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
     * @param propertyName The name of the property being animated.
     */
    public void setPropertyName(String propertyName) {
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
     * Sets the <code>Method</code> that is called with the animated values calculated
     * during the animation. Setting the setter method is an alternative to supplying a
     * {@link #setPropertyName(String) propertyName} from which the method is derived. This
     * approach is more direct, and is especially useful when a function must be called that does
     * not correspond to the convention of <code>setName()</code>. For example, if a function
     * called <code>offset()</code> is to be called with the animated values, there is no way
     * to tell <code>PropertyAnimator</code> how to call that function simply through a property
     * name, so a setter method should be supplied instead.
     *
     * <p>Note that the setter function must take the same parameter type as the
     * <code>valueFrom</code> and <code>valueTo</code> properties, otherwise the call to
     * the setter function will fail.</p>
     *
     * @param setter The setter method that should be called with the animated values.
     */
    public void setSetter(Method setter) {
        mSetter = setter;
    }

    /**
     * Gets the <code>Method</code> that is called with the animated values calculated
     * during the animation.
     */
    public Method getSetter() {
        return mSetter;
    }

    /**
     * Sets the <code>Method</code> that is called to get unsupplied <code>valueFrom</code> or
     * <code>valueTo</code> properties. Setting the getter method is an alternative to supplying a
     * {@link #setPropertyName(String) propertyName} from which the method is derived. This
     * approach is more direct, and is especially useful when a function must be called that does
     * not correspond to the convention of <code>setName()</code>. For example, if a function
     * called <code>offset()</code> is to be called to get an initial value, there is no way
     * to tell <code>PropertyAnimator</code> how to call that function simply through a property
     * name, so a getter method should be supplied instead.
     *
     * <p>Note that the getter method is only called whether supplied here or derived
     * from the property name, if one of <code>valueFrom</code> or <code>valueTo</code> are
     * null. If both of those values are non-null, then there is no need to get one of the
     * values and the getter is not called.
     *
     * <p>Note that the getter function must return the same parameter type as the
     * <code>valueFrom</code> and <code>valueTo</code> properties (whichever of them are
     * non-null), otherwise the call to the getter function will fail.</p>
     *
     * @param getter The getter method that should be called to get initial animation values.
     */
    public void setGetter(Method getter) {
        mGetter = getter;
    }

    /**
     * Gets the <code>Method</code> that is called to get unsupplied <code>valueFrom</code> or
     * <code>valueTo</code> properties.
     */
    public Method getGetter() {
        return mGetter;
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

        mPropertyName = a.getString(com.android.internal.R.styleable.PropertyAnimator_propertyName);


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
     * A constructor that takes <code>float</code> values. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a <code>float</code> value.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName,
            float valueFrom, float valueTo) {
        super(duration, valueFrom, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes a single <code>float</code> value, which is the value that the
     * target object will animate to. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a value of the same type as the <code>Object</code>s. The
     * system also expects to find a similar getter function with which to derive the starting
     * value for the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName, float valueTo) {
        super(duration, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes <code>int</code> values. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a <code>int</code> value.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName,
            int valueFrom, int valueTo) {
        super(duration, valueFrom, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes a single <code>int</code> value, which is the value that the
     * target object will animate to. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a value of the same type as the <code>Object</code>s. The
     * system also expects to find a similar getter function with which to derive the starting
     * value for the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName, int valueTo) {
        super(duration, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes <code>double</code> values. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a <code>double</code> value.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName,
            double valueFrom, double valueTo) {
        super(duration, valueFrom, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes a single <code>double</code> value, which is the value that the
     * target object will animate to. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a value of the same type as the <code>Object</code>s. The
     * system also expects to find a similar getter function with which to derive the starting
     * value for the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName, double valueTo) {
        super(duration, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes <code>Object</code> values. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a value of the same type as the <code>Object</code>s.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName,
            Object valueFrom, Object valueTo) {
        super(duration, valueFrom, valueTo);
        mTarget = target;
        mPropertyName = propertyName;
    }

    /**
     * A constructor that takes a single <code>Object</code> value, which is the value that the
     * target object will animate to. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a value of the same type as the <code>Object</code>s. The
     * system also expects to find a similar getter function with which to derive the starting
     * value for the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param valueTo The value to which the property will animate.
     */
    public PropertyAnimator(int duration, Object target, String propertyName, Object valueTo) {
        this(duration, target, propertyName, null, valueTo);
    }

    /**
     * A constructor that takes <code>Keyframe</code>s. When this constructor
     * is called, the system expects to find a setter for <code>propertyName</code> on
     * the target object that takes a value of the same type as that returned from
     * {@link Keyframe#getType()}.
     * .
     *
     * @param duration The length of the animation, in milliseconds.
     * @param target The object whose property is to be animated. This object should
     * have a public function on it called <code>setName()</code>, where <code>name</code> is
     * the name of the property passed in as the <code>propertyName</code> parameter.
     * @param propertyName The name of the property on the <code>target</code> object
     * that will be animated. Given this name, the constructor will search for a
     * setter on the target object with the name <code>setPropertyName</code>. For example,
     * if the constructor is called with <code>propertyName = "foo"</code>, then the
     * target object should have a setter function with the name <code>setFoo()</code>.
     * @param keyframes The set of keyframes that define the times and values for the animation.
     * These keyframes should be ordered in increasing time value, should have a starting
     * keyframe with a fraction of 0 and and ending keyframe with a fraction of 1.
     */
    public PropertyAnimator(int duration, Object target, String propertyName,
            Keyframe...keyframes) {
        super(duration, keyframes);
        mTarget = target;
        mPropertyName = propertyName;
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
        if (mSetter == null) {
            try {
                // Have to lock property map prior to reading it, to guard against
                // another thread putting something in there after we've checked it
                // but before we've added an entry to it
                propertyMapLock.writeLock().lock();
                HashMap<String, Method> propertyMap = sSetterPropertyMap.get(mTarget);
                if (propertyMap != null) {
                    mSetter = propertyMap.get(mPropertyName);
                    if (mSetter != null) {
                        return;
                    }
                }
                mSetter = getPropertyFunction("set", mValueType);
                if (propertyMap == null) {
                    propertyMap = new HashMap<String, Method>();
                    sSetterPropertyMap.put(mTarget, propertyMap);
                }
                propertyMap.put(mPropertyName, mSetter);
            } finally {
                propertyMapLock.writeLock().unlock();
            }
        }
        if (getKeyframes() == null && (getValueFrom() == null || getValueTo() == null)) {
            // Need to set up the getter if not set by the user, then call it
            // to get the initial values
            if (mGetter == null) {
                try {
                    propertyMapLock.writeLock().lock();
                    HashMap<String, Method> propertyMap = sGetterPropertyMap.get(mTarget);
                    if (propertyMap != null) {
                        mGetter = propertyMap.get(mPropertyName);
                        if (mGetter != null) {
                            return;
                        }
                    }
                    mGetter = getPropertyFunction("get", null);
                    if (propertyMap == null) {
                        propertyMap = new HashMap<String, Method>();
                        sGetterPropertyMap.put(mTarget, propertyMap);
                    }
                    propertyMap.put(mPropertyName, mGetter);
                } finally {
                    propertyMapLock.writeLock().unlock();
                }
            }
            try {
                if (getValueFrom() == null) {
                    setValueFrom(mGetter.invoke(mTarget));
                }
                if (getValueTo() == null) {
                    setValueTo(mGetter.invoke(mTarget));
                }
            } catch (IllegalArgumentException e) {
                Log.e("PropertyAnimator", e.toString());
            } catch (IllegalAccessException e) {
                Log.e("PropertyAnimator", e.toString());
            } catch (InvocationTargetException e) {
                Log.e("PropertyAnimator", e.toString());
            }
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
        if (mSetter != null) {
            try {
                mTmpValueArray[0] = getAnimatedValue();
                mSetter.invoke(mTarget, mTmpValueArray);
            } catch (InvocationTargetException e) {
                Log.e("PropertyAnimator", e.toString());
            } catch (IllegalAccessException e) {
                Log.e("PropertyAnimator", e.toString());
            }
        }
    }

    @Override
    public String toString() {
        return "Animator: target: " + this.mTarget + "\n" +
                "    property: " + mPropertyName + "\n" +
                "    from: " + getValueFrom() + "\n" +
                "    to: " + getValueTo();
    }
}
