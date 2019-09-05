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

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Looper;
import android.os.Trace;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * This class provides a simple timing engine for running animations
 * which calculate animated values and set them on target objects.
 *
 * <p>There is a single timing pulse that all animations use. It runs in a
 * custom handler to ensure that property changes happen on the UI thread.</p>
 *
 * <p>By default, ValueAnimator uses non-linear time interpolation, via the
 * {@link AccelerateDecelerateInterpolator} class, which accelerates into and decelerates
 * out of an animation. This behavior can be changed by calling
 * {@link ValueAnimator#setInterpolator(TimeInterpolator)}.</p>
 *
 * <p>Animators can be created from either code or resource files. Here is an example
 * of a ValueAnimator resource file:</p>
 *
 * {@sample development/samples/ApiDemos/res/anim/animator.xml ValueAnimatorResources}
 *
 * <p>Starting from API 23, it is also possible to use a combination of {@link PropertyValuesHolder}
 * and {@link Keyframe} resource tags to create a multi-step animation.
 * Note that you can specify explicit fractional values (from 0 to 1) for
 * each keyframe to determine when, in the overall duration, the animation should arrive at that
 * value. Alternatively, you can leave the fractions off and the keyframes will be equally
 * distributed within the total duration:</p>
 *
 * {@sample development/samples/ApiDemos/res/anim/value_animator_pvh_kf.xml
 * ValueAnimatorKeyframeResources}
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about animating with {@code ValueAnimator}, read the
 * <a href="{@docRoot}guide/topics/graphics/prop-animation.html#value-animator">Property
 * Animation</a> developer guide.</p>
 * </div>
 */
@SuppressWarnings("unchecked")
public class ValueAnimator extends Animator implements AnimationHandler.AnimationFrameCallback {
    private static final String TAG = "ValueAnimator";
    private static final boolean DEBUG = false;

    /**
     * Internal constants
     */

    /**
     * System-wide animation scale.
     *
     * <p>To check whether animations are enabled system-wise use {@link #areAnimatorsEnabled()}.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static float sDurationScale = 1.0f;

    /**
     * Internal variables
     * NOTE: This object implements the clone() method, making a deep copy of any referenced
     * objects. As other non-trivial fields are added to this class, make sure to add logic
     * to clone() to make deep copies of them.
     */

    /**
     * The first time that the animation's animateFrame() method is called. This time is used to
     * determine elapsed time (and therefore the elapsed fraction) in subsequent calls
     * to animateFrame().
     *
     * Whenever mStartTime is set, you must also update mStartTimeCommitted.
     */
    long mStartTime = -1;

    /**
     * When true, the start time has been firmly committed as a chosen reference point in
     * time by which the progress of the animation will be evaluated.  When false, the
     * start time may be updated when the first animation frame is committed so as
     * to compensate for jank that may have occurred between when the start time was
     * initialized and when the frame was actually drawn.
     *
     * This flag is generally set to false during the first frame of the animation
     * when the animation playing state transitions from STOPPED to RUNNING or
     * resumes after having been paused.  This flag is set to true when the start time
     * is firmly committed and should not be further compensated for jank.
     */
    boolean mStartTimeCommitted;

    /**
     * Set when setCurrentPlayTime() is called. If negative, animation is not currently seeked
     * to a value.
     */
    float mSeekFraction = -1;

    /**
     * Set on the next frame after pause() is called, used to calculate a new startTime
     * or delayStartTime which allows the animator to continue from the point at which
     * it was paused. If negative, has not yet been set.
     */
    private long mPauseTime;

    /**
     * Set when an animator is resumed. This triggers logic in the next frame which
     * actually resumes the animator.
     */
    private boolean mResumed = false;

    // The time interpolator to be used if none is set on the animation
    private static final TimeInterpolator sDefaultInterpolator =
            new AccelerateDecelerateInterpolator();

    /**
     * Flag to indicate whether this animator is playing in reverse mode, specifically
     * by being started or interrupted by a call to reverse(). This flag is different than
     * mPlayingBackwards, which indicates merely whether the current iteration of the
     * animator is playing in reverse. It is used in corner cases to determine proper end
     * behavior.
     */
    private boolean mReversing;

    /**
     * Tracks the overall fraction of the animation, ranging from 0 to mRepeatCount + 1
     */
    private float mOverallFraction = 0f;

    /**
     * Tracks current elapsed/eased fraction, for querying in getAnimatedFraction().
     * This is calculated by interpolating the fraction (range: [0, 1]) in the current iteration.
     */
    private float mCurrentFraction = 0f;

    /**
     * Tracks the time (in milliseconds) when the last frame arrived.
     */
    private long mLastFrameTime = -1;

    /**
     * Tracks the time (in milliseconds) when the first frame arrived. Note the frame may arrive
     * during the start delay.
     */
    private long mFirstFrameTime = -1;

    /**
     * Additional playing state to indicate whether an animator has been start()'d. There is
     * some lag between a call to start() and the first animation frame. We should still note
     * that the animation has been started, even if it's first animation frame has not yet
     * happened, and reflect that state in isRunning().
     * Note that delayed animations are different: they are not started until their first
     * animation frame, which occurs after their delay elapses.
     */
    private boolean mRunning = false;

    /**
     * Additional playing state to indicate whether an animator has been start()'d, whether or
     * not there is a nonzero startDelay.
     */
    private boolean mStarted = false;

    /**
     * Tracks whether we've notified listeners of the onAnimationStart() event. This can be
     * complex to keep track of since we notify listeners at different times depending on
     * startDelay and whether start() was called before end().
     */
    private boolean mStartListenersCalled = false;

    /**
     * Flag that denotes whether the animation is set up and ready to go. Used to
     * set up animation that has not yet been started.
     */
    boolean mInitialized = false;

    /**
     * Flag that tracks whether animation has been requested to end.
     */
    private boolean mAnimationEndRequested = false;

    //
    // Backing variables
    //

    // How long the animation should last in ms
    @UnsupportedAppUsage
    private long mDuration = 300;

    // The amount of time in ms to delay starting the animation after start() is called. Note
    // that this start delay is unscaled. When there is a duration scale set on the animator, the
    // scaling factor will be applied to this delay.
    private long mStartDelay = 0;

    // The number of times the animation will repeat. The default is 0, which means the animation
    // will play only once
    private int mRepeatCount = 0;

    /**
     * The type of repetition that will occur when repeatMode is nonzero. RESTART means the
     * animation will start from the beginning on every new cycle. REVERSE means the animation
     * will reverse directions on each iteration.
     */
    private int mRepeatMode = RESTART;

    /**
     * Whether or not the animator should register for its own animation callback to receive
     * animation pulse.
     */
    private boolean mSelfPulse = true;

    /**
     * Whether or not the animator has been requested to start without pulsing. This flag gets set
     * in startWithoutPulsing(), and reset in start().
     */
    private boolean mSuppressSelfPulseRequested = false;

    /**
     * The time interpolator to be used. The elapsed fraction of the animation will be passed
     * through this interpolator to calculate the interpolated fraction, which is then used to
     * calculate the animated values.
     */
    private TimeInterpolator mInterpolator = sDefaultInterpolator;

    /**
     * The set of listeners to be sent events through the life of an animation.
     */
    ArrayList<AnimatorUpdateListener> mUpdateListeners = null;

    /**
     * The property/value sets being animated.
     */
    PropertyValuesHolder[] mValues;

    /**
     * A hashmap of the PropertyValuesHolder objects. This map is used to lookup animated values
     * by property name during calls to getAnimatedValue(String).
     */
    HashMap<String, PropertyValuesHolder> mValuesMap;

    /**
     * If set to non-negative value, this will override {@link #sDurationScale}.
     */
    private float mDurationScale = -1f;

    /**
     * Public constants
     */

    /** @hide */
    @IntDef({RESTART, REVERSE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RepeatMode {}

    /**
     * When the animation reaches the end and <code>repeatCount</code> is INFINITE
     * or a positive value, the animation restarts from the beginning.
     */
    public static final int RESTART = 1;
    /**
     * When the animation reaches the end and <code>repeatCount</code> is INFINITE
     * or a positive value, the animation reverses direction on every iteration.
     */
    public static final int REVERSE = 2;
    /**
     * This value used used with the {@link #setRepeatCount(int)} property to repeat
     * the animation indefinitely.
     */
    public static final int INFINITE = -1;

    /**
     * @hide
     */
    @TestApi
    public static void setDurationScale(float durationScale) {
        sDurationScale = durationScale;
    }

    /**
     * @hide
     */
    @TestApi
    public static float getDurationScale() {
        return sDurationScale;
    }

    /**
     * Returns whether animators are currently enabled, system-wide. By default, all
     * animators are enabled. This can change if either the user sets a Developer Option
     * to set the animator duration scale to 0 or by Battery Savery mode being enabled
     * (which disables all animations).
     *
     * <p>Developers should not typically need to call this method, but should an app wish
     * to show a different experience when animators are disabled, this return value
     * can be used as a decider of which experience to offer.
     *
     * @return boolean Whether animators are currently enabled. The default value is
     * <code>true</code>.
     */
    public static boolean areAnimatorsEnabled() {
        return !(sDurationScale == 0);
    }

    /**
     * Creates a new ValueAnimator object. This default constructor is primarily for
     * use internally; the factory methods which take parameters are more generally
     * useful.
     */
    public ValueAnimator() {
    }

    /**
     * Constructs and returns a ValueAnimator that animates between int values. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * @param values A set of values that the animation will animate between over time.
     * @return A ValueAnimator object that is set up to animate between the given values.
     */
    public static ValueAnimator ofInt(int... values) {
        ValueAnimator anim = new ValueAnimator();
        anim.setIntValues(values);
        return anim;
    }

    /**
     * Constructs and returns a ValueAnimator that animates between color values. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * @param values A set of values that the animation will animate between over time.
     * @return A ValueAnimator object that is set up to animate between the given values.
     */
    public static ValueAnimator ofArgb(int... values) {
        ValueAnimator anim = new ValueAnimator();
        anim.setIntValues(values);
        anim.setEvaluator(ArgbEvaluator.getInstance());
        return anim;
    }

    /**
     * Constructs and returns a ValueAnimator that animates between float values. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * @param values A set of values that the animation will animate between over time.
     * @return A ValueAnimator object that is set up to animate between the given values.
     */
    public static ValueAnimator ofFloat(float... values) {
        ValueAnimator anim = new ValueAnimator();
        anim.setFloatValues(values);
        return anim;
    }

    /**
     * Constructs and returns a ValueAnimator that animates between the values
     * specified in the PropertyValuesHolder objects.
     *
     * @param values A set of PropertyValuesHolder objects whose values will be animated
     * between over time.
     * @return A ValueAnimator object that is set up to animate between the given values.
     */
    public static ValueAnimator ofPropertyValuesHolder(PropertyValuesHolder... values) {
        ValueAnimator anim = new ValueAnimator();
        anim.setValues(values);
        return anim;
    }
    /**
     * Constructs and returns a ValueAnimator that animates between Object values. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * <p><strong>Note:</strong> The Object values are stored as references to the original
     * objects, which means that changes to those objects after this method is called will
     * affect the values on the animator. If the objects will be mutated externally after
     * this method is called, callers should pass a copy of those objects instead.
     *
     * <p>Since ValueAnimator does not know how to animate between arbitrary Objects, this
     * factory method also takes a TypeEvaluator object that the ValueAnimator will use
     * to perform that interpolation.
     *
     * @param evaluator A TypeEvaluator that will be called on each animation frame to
     * provide the ncessry interpolation between the Object values to derive the animated
     * value.
     * @param values A set of values that the animation will animate between over time.
     * @return A ValueAnimator object that is set up to animate between the given values.
     */
    public static ValueAnimator ofObject(TypeEvaluator evaluator, Object... values) {
        ValueAnimator anim = new ValueAnimator();
        anim.setObjectValues(values);
        anim.setEvaluator(evaluator);
        return anim;
    }

    /**
     * Sets int values that will be animated between. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * <p>If there are already multiple sets of values defined for this ValueAnimator via more
     * than one PropertyValuesHolder object, this method will set the values for the first
     * of those objects.</p>
     *
     * @param values A set of values that the animation will animate between over time.
     */
    public void setIntValues(int... values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (mValues == null || mValues.length == 0) {
            setValues(PropertyValuesHolder.ofInt("", values));
        } else {
            PropertyValuesHolder valuesHolder = mValues[0];
            valuesHolder.setIntValues(values);
        }
        // New property/values/target should cause re-initialization prior to starting
        mInitialized = false;
    }

    /**
     * Sets float values that will be animated between. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * <p>If there are already multiple sets of values defined for this ValueAnimator via more
     * than one PropertyValuesHolder object, this method will set the values for the first
     * of those objects.</p>
     *
     * @param values A set of values that the animation will animate between over time.
     */
    public void setFloatValues(float... values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (mValues == null || mValues.length == 0) {
            setValues(PropertyValuesHolder.ofFloat("", values));
        } else {
            PropertyValuesHolder valuesHolder = mValues[0];
            valuesHolder.setFloatValues(values);
        }
        // New property/values/target should cause re-initialization prior to starting
        mInitialized = false;
    }

    /**
     * Sets the values to animate between for this animation. A single
     * value implies that that value is the one being animated to. However, this is not typically
     * useful in a ValueAnimator object because there is no way for the object to determine the
     * starting value for the animation (unlike ObjectAnimator, which can derive that value
     * from the target object and property being animated). Therefore, there should typically
     * be two or more values.
     *
     * <p><strong>Note:</strong> The Object values are stored as references to the original
     * objects, which means that changes to those objects after this method is called will
     * affect the values on the animator. If the objects will be mutated externally after
     * this method is called, callers should pass a copy of those objects instead.
     *
     * <p>If there are already multiple sets of values defined for this ValueAnimator via more
     * than one PropertyValuesHolder object, this method will set the values for the first
     * of those objects.</p>
     *
     * <p>There should be a TypeEvaluator set on the ValueAnimator that knows how to interpolate
     * between these value objects. ValueAnimator only knows how to interpolate between the
     * primitive types specified in the other setValues() methods.</p>
     *
     * @param values The set of values to animate between.
     */
    public void setObjectValues(Object... values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (mValues == null || mValues.length == 0) {
            setValues(PropertyValuesHolder.ofObject("", null, values));
        } else {
            PropertyValuesHolder valuesHolder = mValues[0];
            valuesHolder.setObjectValues(values);
        }
        // New property/values/target should cause re-initialization prior to starting
        mInitialized = false;
    }

    /**
     * Sets the values, per property, being animated between. This function is called internally
     * by the constructors of ValueAnimator that take a list of values. But a ValueAnimator can
     * be constructed without values and this method can be called to set the values manually
     * instead.
     *
     * @param values The set of values, per property, being animated between.
     */
    public void setValues(PropertyValuesHolder... values) {
        int numValues = values.length;
        mValues = values;
        mValuesMap = new HashMap<String, PropertyValuesHolder>(numValues);
        for (int i = 0; i < numValues; ++i) {
            PropertyValuesHolder valuesHolder = values[i];
            mValuesMap.put(valuesHolder.getPropertyName(), valuesHolder);
        }
        // New property/values/target should cause re-initialization prior to starting
        mInitialized = false;
    }

    /**
     * Returns the values that this ValueAnimator animates between. These values are stored in
     * PropertyValuesHolder objects, even if the ValueAnimator was created with a simple list
     * of value objects instead.
     *
     * @return PropertyValuesHolder[] An array of PropertyValuesHolder objects which hold the
     * values, per property, that define the animation.
     */
    public PropertyValuesHolder[] getValues() {
        return mValues;
    }

    /**
     * This function is called immediately before processing the first animation
     * frame of an animation. If there is a nonzero <code>startDelay</code>, the
     * function is called after that delay ends.
     * It takes care of the final initialization steps for the
     * animation.
     *
     *  <p>Overrides of this method should call the superclass method to ensure
     *  that internal mechanisms for the animation are set up correctly.</p>
     */
    @CallSuper
    void initAnimation() {
        if (!mInitialized) {
            int numValues = mValues.length;
            for (int i = 0; i < numValues; ++i) {
                mValues[i].init();
            }
            mInitialized = true;
        }
    }

    /**
     * Sets the length of the animation. The default duration is 300 milliseconds.
     *
     * @param duration The length of the animation, in milliseconds. This value cannot
     * be negative.
     * @return ValueAnimator The object called with setDuration(). This return
     * value makes it easier to compose statements together that construct and then set the
     * duration, as in <code>ValueAnimator.ofInt(0, 10).setDuration(500).start()</code>.
     */
    @Override
    public ValueAnimator setDuration(long duration) {
        if (duration < 0) {
            throw new IllegalArgumentException("Animators cannot have negative duration: " +
                    duration);
        }
        mDuration = duration;
        return this;
    }

    /**
     * Overrides the global duration scale by a custom value.
     *
     * @param durationScale The duration scale to set; or {@code -1f} to use the global duration
     *                      scale.
     * @hide
     */
    public void overrideDurationScale(float durationScale) {
        mDurationScale = durationScale;
    }

    private float resolveDurationScale() {
        return mDurationScale >= 0f ? mDurationScale : sDurationScale;
    }

    private long getScaledDuration() {
        return (long)(mDuration * resolveDurationScale());
    }

    /**
     * Gets the length of the animation. The default duration is 300 milliseconds.
     *
     * @return The length of the animation, in milliseconds.
     */
    @Override
    public long getDuration() {
        return mDuration;
    }

    @Override
    public long getTotalDuration() {
        if (mRepeatCount == INFINITE) {
            return DURATION_INFINITE;
        } else {
            return mStartDelay + (mDuration * (mRepeatCount + 1));
        }
    }

    /**
     * Sets the position of the animation to the specified point in time. This time should
     * be between 0 and the total duration of the animation, including any repetition. If
     * the animation has not yet been started, then it will not advance forward after it is
     * set to this time; it will simply set the time to this value and perform any appropriate
     * actions based on that time. If the animation is already running, then setCurrentPlayTime()
     * will set the current playing time to this value and continue playing from that point.
     *
     * @param playTime The time, in milliseconds, to which the animation is advanced or rewound.
     */
    public void setCurrentPlayTime(long playTime) {
        float fraction = mDuration > 0 ? (float) playTime / mDuration : 1;
        setCurrentFraction(fraction);
    }

    /**
     * Sets the position of the animation to the specified fraction. This fraction should
     * be between 0 and the total fraction of the animation, including any repetition. That is,
     * a fraction of 0 will position the animation at the beginning, a value of 1 at the end,
     * and a value of 2 at the end of a reversing animator that repeats once. If
     * the animation has not yet been started, then it will not advance forward after it is
     * set to this fraction; it will simply set the fraction to this value and perform any
     * appropriate actions based on that fraction. If the animation is already running, then
     * setCurrentFraction() will set the current fraction to this value and continue
     * playing from that point. {@link Animator.AnimatorListener} events are not called
     * due to changing the fraction; those events are only processed while the animation
     * is running.
     *
     * @param fraction The fraction to which the animation is advanced or rewound. Values
     * outside the range of 0 to the maximum fraction for the animator will be clamped to
     * the correct range.
     */
    public void setCurrentFraction(float fraction) {
        initAnimation();
        fraction = clampFraction(fraction);
        mStartTimeCommitted = true; // do not allow start time to be compensated for jank
        if (isPulsingInternal()) {
            long seekTime = (long) (getScaledDuration() * fraction);
            long currentTime = AnimationUtils.currentAnimationTimeMillis();
            // Only modify the start time when the animation is running. Seek fraction will ensure
            // non-running animations skip to the correct start time.
            mStartTime = currentTime - seekTime;
        } else {
            // If the animation loop hasn't started, or during start delay, the startTime will be
            // adjusted once the delay has passed based on seek fraction.
            mSeekFraction = fraction;
        }
        mOverallFraction = fraction;
        final float currentIterationFraction = getCurrentIterationFraction(fraction, mReversing);
        animateValue(currentIterationFraction);
    }

    /**
     * Calculates current iteration based on the overall fraction. The overall fraction will be
     * in the range of [0, mRepeatCount + 1]. Both current iteration and fraction in the current
     * iteration can be derived from it.
     */
    private int getCurrentIteration(float fraction) {
        fraction = clampFraction(fraction);
        // If the overall fraction is a positive integer, we consider the current iteration to be
        // complete. In other words, the fraction for the current iteration would be 1, and the
        // current iteration would be overall fraction - 1.
        double iteration = Math.floor(fraction);
        if (fraction == iteration && fraction > 0) {
            iteration--;
        }
        return (int) iteration;
    }

    /**
     * Calculates the fraction of the current iteration, taking into account whether the animation
     * should be played backwards. E.g. When the animation is played backwards in an iteration,
     * the fraction for that iteration will go from 1f to 0f.
     */
    private float getCurrentIterationFraction(float fraction, boolean inReverse) {
        fraction = clampFraction(fraction);
        int iteration = getCurrentIteration(fraction);
        float currentFraction = fraction - iteration;
        return shouldPlayBackward(iteration, inReverse) ? 1f - currentFraction : currentFraction;
    }

    /**
     * Clamps fraction into the correct range: [0, mRepeatCount + 1]. If repeat count is infinite,
     * no upper bound will be set for the fraction.
     *
     * @param fraction fraction to be clamped
     * @return fraction clamped into the range of [0, mRepeatCount + 1]
     */
    private float clampFraction(float fraction) {
        if (fraction < 0) {
            fraction = 0;
        } else if (mRepeatCount != INFINITE) {
            fraction = Math.min(fraction, mRepeatCount + 1);
        }
        return fraction;
    }

    /**
     * Calculates the direction of animation playing (i.e. forward or backward), based on 1)
     * whether the entire animation is being reversed, 2) repeat mode applied to the current
     * iteration.
     */
    private boolean shouldPlayBackward(int iteration, boolean inReverse) {
        if (iteration > 0 && mRepeatMode == REVERSE &&
                (iteration < (mRepeatCount + 1) || mRepeatCount == INFINITE)) {
            // if we were seeked to some other iteration in a reversing animator,
            // figure out the correct direction to start playing based on the iteration
            if (inReverse) {
                return (iteration % 2) == 0;
            } else {
                return (iteration % 2) != 0;
            }
        } else {
            return inReverse;
        }
    }

    /**
     * Gets the current position of the animation in time, which is equal to the current
     * time minus the time that the animation started. An animation that is not yet started will
     * return a value of zero, unless the animation has has its play time set via
     * {@link #setCurrentPlayTime(long)} or {@link #setCurrentFraction(float)}, in which case
     * it will return the time that was set.
     *
     * @return The current position in time of the animation.
     */
    public long getCurrentPlayTime() {
        if (!mInitialized || (!mStarted && mSeekFraction < 0)) {
            return 0;
        }
        if (mSeekFraction >= 0) {
            return (long) (mDuration * mSeekFraction);
        }
        float durationScale = resolveDurationScale();
        if (durationScale == 0f) {
            durationScale = 1f;
        }
        return (long) ((AnimationUtils.currentAnimationTimeMillis() - mStartTime) / durationScale);
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called.
     *
     * @return the number of milliseconds to delay running the animation
     */
    @Override
    public long getStartDelay() {
        return mStartDelay;
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called. Note that the start delay should always be non-negative. Any
     * negative start delay will be clamped to 0 on N and above.
     *
     * @param startDelay The amount of the delay, in milliseconds
     */
    @Override
    public void setStartDelay(long startDelay) {
        // Clamp start delay to non-negative range.
        if (startDelay < 0) {
            Log.w(TAG, "Start delay should always be non-negative");
            startDelay = 0;
        }
        mStartDelay = startDelay;
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation. This is a
     * requested time that the animation will attempt to honor, but the actual delay between
     * frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     *
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     *
     * Note that this method should be called from the same thread that {@link #start()} is
     * called in order to check the frame delay for that animation. A runtime exception will be
     * thrown if the calling thread does not have a Looper.
     *
     * @return the requested time between frames, in milliseconds
     */
    public static long getFrameDelay() {
        return AnimationHandler.getInstance().getFrameDelay();
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation. This is a
     * requested time that the animation will attempt to honor, but the actual delay between
     * frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     *
     * The frame delay may be ignored when the animation system uses an external timing
     * source, such as the display refresh rate (vsync), to govern animations.
     *
     * Note that this method should be called from the same thread that {@link #start()} is
     * called in order to have the new frame delay take effect on that animation. A runtime
     * exception will be thrown if the calling thread does not have a Looper.
     *
     * @param frameDelay the requested time between frames, in milliseconds
     */
    public static void setFrameDelay(long frameDelay) {
        AnimationHandler.getInstance().setFrameDelay(frameDelay);
    }

    /**
     * The most recent value calculated by this <code>ValueAnimator</code> when there is just one
     * property being animated. This value is only sensible while the animation is running. The main
     * purpose for this read-only property is to retrieve the value from the <code>ValueAnimator</code>
     * during a call to {@link AnimatorUpdateListener#onAnimationUpdate(ValueAnimator)}, which
     * is called during each animation frame, immediately after the value is calculated.
     *
     * @return animatedValue The value most recently calculated by this <code>ValueAnimator</code> for
     * the single property being animated. If there are several properties being animated
     * (specified by several PropertyValuesHolder objects in the constructor), this function
     * returns the animated value for the first of those objects.
     */
    public Object getAnimatedValue() {
        if (mValues != null && mValues.length > 0) {
            return mValues[0].getAnimatedValue();
        }
        // Shouldn't get here; should always have values unless ValueAnimator was set up wrong
        return null;
    }

    /**
     * The most recent value calculated by this <code>ValueAnimator</code> for <code>propertyName</code>.
     * The main purpose for this read-only property is to retrieve the value from the
     * <code>ValueAnimator</code> during a call to
     * {@link AnimatorUpdateListener#onAnimationUpdate(ValueAnimator)}, which
     * is called during each animation frame, immediately after the value is calculated.
     *
     * @return animatedValue The value most recently calculated for the named property
     * by this <code>ValueAnimator</code>.
     */
    public Object getAnimatedValue(String propertyName) {
        PropertyValuesHolder valuesHolder = mValuesMap.get(propertyName);
        if (valuesHolder != null) {
            return valuesHolder.getAnimatedValue();
        } else {
            // At least avoid crashing if called with bogus propertyName
            return null;
        }
    }

    /**
     * Sets how many times the animation should be repeated. If the repeat
     * count is 0, the animation is never repeated. If the repeat count is
     * greater than 0 or {@link #INFINITE}, the repeat mode will be taken
     * into account. The repeat count is 0 by default.
     *
     * @param value the number of times the animation should be repeated
     */
    public void setRepeatCount(int value) {
        mRepeatCount = value;
    }
    /**
     * Defines how many times the animation should repeat. The default value
     * is 0.
     *
     * @return the number of times the animation should repeat, or {@link #INFINITE}
     */
    public int getRepeatCount() {
        return mRepeatCount;
    }

    /**
     * Defines what this animation should do when it reaches the end. This
     * setting is applied only when the repeat count is either greater than
     * 0 or {@link #INFINITE}. Defaults to {@link #RESTART}.
     *
     * @param value {@link #RESTART} or {@link #REVERSE}
     */
    public void setRepeatMode(@RepeatMode int value) {
        mRepeatMode = value;
    }

    /**
     * Defines what this animation should do when it reaches the end.
     *
     * @return either one of {@link #REVERSE} or {@link #RESTART}
     */
    @RepeatMode
    public int getRepeatMode() {
        return mRepeatMode;
    }

    /**
     * Adds a listener to the set of listeners that are sent update events through the life of
     * an animation. This method is called on all listeners for every frame of the animation,
     * after the values for the animation have been calculated.
     *
     * @param listener the listener to be added to the current set of listeners for this animation.
     */
    public void addUpdateListener(AnimatorUpdateListener listener) {
        if (mUpdateListeners == null) {
            mUpdateListeners = new ArrayList<AnimatorUpdateListener>();
        }
        mUpdateListeners.add(listener);
    }

    /**
     * Removes all listeners from the set listening to frame updates for this animation.
     */
    public void removeAllUpdateListeners() {
        if (mUpdateListeners == null) {
            return;
        }
        mUpdateListeners.clear();
        mUpdateListeners = null;
    }

    /**
     * Removes a listener from the set listening to frame updates for this animation.
     *
     * @param listener the listener to be removed from the current set of update listeners
     * for this animation.
     */
    public void removeUpdateListener(AnimatorUpdateListener listener) {
        if (mUpdateListeners == null) {
            return;
        }
        mUpdateListeners.remove(listener);
        if (mUpdateListeners.size() == 0) {
            mUpdateListeners = null;
        }
    }


    /**
     * The time interpolator used in calculating the elapsed fraction of this animation. The
     * interpolator determines whether the animation runs with linear or non-linear motion,
     * such as acceleration and deceleration. The default value is
     * {@link android.view.animation.AccelerateDecelerateInterpolator}
     *
     * @param value the interpolator to be used by this animation. A value of <code>null</code>
     * will result in linear interpolation.
     */
    @Override
    public void setInterpolator(TimeInterpolator value) {
        if (value != null) {
            mInterpolator = value;
        } else {
            mInterpolator = new LinearInterpolator();
        }
    }

    /**
     * Returns the timing interpolator that this ValueAnimator uses.
     *
     * @return The timing interpolator for this ValueAnimator.
     */
    @Override
    public TimeInterpolator getInterpolator() {
        return mInterpolator;
    }

    /**
     * The type evaluator to be used when calculating the animated values of this animation.
     * The system will automatically assign a float or int evaluator based on the type
     * of <code>startValue</code> and <code>endValue</code> in the constructor. But if these values
     * are not one of these primitive types, or if different evaluation is desired (such as is
     * necessary with int values that represent colors), a custom evaluator needs to be assigned.
     * For example, when running an animation on color values, the {@link ArgbEvaluator}
     * should be used to get correct RGB color interpolation.
     *
     * <p>If this ValueAnimator has only one set of values being animated between, this evaluator
     * will be used for that set. If there are several sets of values being animated, which is
     * the case if PropertyValuesHolder objects were set on the ValueAnimator, then the evaluator
     * is assigned just to the first PropertyValuesHolder object.</p>
     *
     * @param value the evaluator to be used this animation
     */
    public void setEvaluator(TypeEvaluator value) {
        if (value != null && mValues != null && mValues.length > 0) {
            mValues[0].setEvaluator(value);
        }
    }

    private void notifyStartListeners() {
        if (mListeners != null && !mStartListenersCalled) {
            ArrayList<AnimatorListener> tmpListeners =
                    (ArrayList<AnimatorListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onAnimationStart(this, mReversing);
            }
        }
        mStartListenersCalled = true;
    }

    /**
     * Start the animation playing. This version of start() takes a boolean flag that indicates
     * whether the animation should play in reverse. The flag is usually false, but may be set
     * to true if called from the reverse() method.
     *
     * <p>The animation started by calling this method will be run on the thread that called
     * this method. This thread should have a Looper on it (a runtime exception will be thrown if
     * this is not the case). Also, if the animation will animate
     * properties of objects in the view hierarchy, then the calling thread should be the UI
     * thread for that view hierarchy.</p>
     *
     * @param playBackwards Whether the ValueAnimator should start playing in reverse.
     */
    private void start(boolean playBackwards) {
        if (Looper.myLooper() == null) {
            throw new AndroidRuntimeException("Animators may only be run on Looper threads");
        }
        mReversing = playBackwards;
        mSelfPulse = !mSuppressSelfPulseRequested;
        // Special case: reversing from seek-to-0 should act as if not seeked at all.
        if (playBackwards && mSeekFraction != -1 && mSeekFraction != 0) {
            if (mRepeatCount == INFINITE) {
                // Calculate the fraction of the current iteration.
                float fraction = (float) (mSeekFraction - Math.floor(mSeekFraction));
                mSeekFraction = 1 - fraction;
            } else {
                mSeekFraction = 1 + mRepeatCount - mSeekFraction;
            }
        }
        mStarted = true;
        mPaused = false;
        mRunning = false;
        mAnimationEndRequested = false;
        // Resets mLastFrameTime when start() is called, so that if the animation was running,
        // calling start() would put the animation in the
        // started-but-not-yet-reached-the-first-frame phase.
        mLastFrameTime = -1;
        mFirstFrameTime = -1;
        mStartTime = -1;
        addAnimationCallback(0);

        if (mStartDelay == 0 || mSeekFraction >= 0 || mReversing) {
            // If there's no start delay, init the animation and notify start listeners right away
            // to be consistent with the previous behavior. Otherwise, postpone this until the first
            // frame after the start delay.
            startAnimation();
            if (mSeekFraction == -1) {
                // No seek, start at play time 0. Note that the reason we are not using fraction 0
                // is because for animations with 0 duration, we want to be consistent with pre-N
                // behavior: skip to the final value immediately.
                setCurrentPlayTime(0);
            } else {
                setCurrentFraction(mSeekFraction);
            }
        }
    }

    void startWithoutPulsing(boolean inReverse) {
        mSuppressSelfPulseRequested = true;
        if (inReverse) {
            reverse();
        } else {
            start();
        }
        mSuppressSelfPulseRequested = false;
    }

    @Override
    public void start() {
        start(false);
    }

    @Override
    public void cancel() {
        if (Looper.myLooper() == null) {
            throw new AndroidRuntimeException("Animators may only be run on Looper threads");
        }

        // If end has already been requested, through a previous end() or cancel() call, no-op
        // until animation starts again.
        if (mAnimationEndRequested) {
            return;
        }

        // Only cancel if the animation is actually running or has been started and is about
        // to run
        // Only notify listeners if the animator has actually started
        if ((mStarted || mRunning) && mListeners != null) {
            if (!mRunning) {
                // If it's not yet running, then start listeners weren't called. Call them now.
                notifyStartListeners();
            }
            ArrayList<AnimatorListener> tmpListeners =
                    (ArrayList<AnimatorListener>) mListeners.clone();
            for (AnimatorListener listener : tmpListeners) {
                listener.onAnimationCancel(this);
            }
        }
        endAnimation();

    }

    @Override
    public void end() {
        if (Looper.myLooper() == null) {
            throw new AndroidRuntimeException("Animators may only be run on Looper threads");
        }
        if (!mRunning) {
            // Special case if the animation has not yet started; get it ready for ending
            startAnimation();
            mStarted = true;
        } else if (!mInitialized) {
            initAnimation();
        }
        animateValue(shouldPlayBackward(mRepeatCount, mReversing) ? 0f : 1f);
        endAnimation();
    }

    @Override
    public void resume() {
        if (Looper.myLooper() == null) {
            throw new AndroidRuntimeException("Animators may only be resumed from the same " +
                    "thread that the animator was started on");
        }
        if (mPaused && !mResumed) {
            mResumed = true;
            if (mPauseTime > 0) {
                addAnimationCallback(0);
            }
        }
        super.resume();
    }

    @Override
    public void pause() {
        boolean previouslyPaused = mPaused;
        super.pause();
        if (!previouslyPaused && mPaused) {
            mPauseTime = -1;
            mResumed = false;
        }
    }

    @Override
    public boolean isRunning() {
        return mRunning;
    }

    @Override
    public boolean isStarted() {
        return mStarted;
    }

    /**
     * Plays the ValueAnimator in reverse. If the animation is already running,
     * it will stop itself and play backwards from the point reached when reverse was called.
     * If the animation is not currently running, then it will start from the end and
     * play backwards. This behavior is only set for the current animation; future playing
     * of the animation will use the default behavior of playing forward.
     */
    @Override
    public void reverse() {
        if (isPulsingInternal()) {
            long currentTime = AnimationUtils.currentAnimationTimeMillis();
            long currentPlayTime = currentTime - mStartTime;
            long timeLeft = getScaledDuration() - currentPlayTime;
            mStartTime = currentTime - timeLeft;
            mStartTimeCommitted = true; // do not allow start time to be compensated for jank
            mReversing = !mReversing;
        } else if (mStarted) {
            mReversing = !mReversing;
            end();
        } else {
            start(true);
        }
    }

    /**
     * @hide
     */
    @Override
    public boolean canReverse() {
        return true;
    }

    /**
     * Called internally to end an animation by removing it from the animations list. Must be
     * called on the UI thread.
     */
    private void endAnimation() {
        if (mAnimationEndRequested) {
            return;
        }
        removeAnimationCallback();

        mAnimationEndRequested = true;
        mPaused = false;
        boolean notify = (mStarted || mRunning) && mListeners != null;
        if (notify && !mRunning) {
            // If it's not yet running, then start listeners weren't called. Call them now.
            notifyStartListeners();
        }
        mRunning = false;
        mStarted = false;
        mStartListenersCalled = false;
        mLastFrameTime = -1;
        mFirstFrameTime = -1;
        mStartTime = -1;
        if (notify && mListeners != null) {
            ArrayList<AnimatorListener> tmpListeners =
                    (ArrayList<AnimatorListener>) mListeners.clone();
            int numListeners = tmpListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                tmpListeners.get(i).onAnimationEnd(this, mReversing);
            }
        }
        // mReversing needs to be reset *after* notifying the listeners for the end callbacks.
        mReversing = false;
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.asyncTraceEnd(Trace.TRACE_TAG_VIEW, getNameForTrace(),
                    System.identityHashCode(this));
        }
    }

    /**
     * Called internally to start an animation by adding it to the active animations list. Must be
     * called on the UI thread.
     */
    private void startAnimation() {
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.asyncTraceBegin(Trace.TRACE_TAG_VIEW, getNameForTrace(),
                    System.identityHashCode(this));
        }

        mAnimationEndRequested = false;
        initAnimation();
        mRunning = true;
        if (mSeekFraction >= 0) {
            mOverallFraction = mSeekFraction;
        } else {
            mOverallFraction = 0f;
        }
        if (mListeners != null) {
            notifyStartListeners();
        }
    }

    /**
     * Internal only: This tracks whether the animation has gotten on the animation loop. Note
     * this is different than {@link #isRunning()} in that the latter tracks the time after start()
     * is called (or after start delay if any), which may be before the animation loop starts.
     */
    private boolean isPulsingInternal() {
        return mLastFrameTime >= 0;
    }

    /**
     * Returns the name of this animator for debugging purposes.
     */
    String getNameForTrace() {
        return "animator";
    }

    /**
     * Applies an adjustment to the animation to compensate for jank between when
     * the animation first ran and when the frame was drawn.
     * @hide
     */
    public void commitAnimationFrame(long frameTime) {
        if (!mStartTimeCommitted) {
            mStartTimeCommitted = true;
            long adjustment = frameTime - mLastFrameTime;
            if (adjustment > 0) {
                mStartTime += adjustment;
                if (DEBUG) {
                    Log.d(TAG, "Adjusted start time by " + adjustment + " ms: " + toString());
                }
            }
        }
    }

    /**
     * This internal function processes a single animation frame for a given animation. The
     * currentTime parameter is the timing pulse sent by the handler, used to calculate the
     * elapsed duration, and therefore
     * the elapsed fraction, of the animation. The return value indicates whether the animation
     * should be ended (which happens when the elapsed time of the animation exceeds the
     * animation's duration, including the repeatCount).
     *
     * @param currentTime The current time, as tracked by the static timing handler
     * @return true if the animation's duration, including any repetitions due to
     * <code>repeatCount</code> has been exceeded and the animation should be ended.
     */
    boolean animateBasedOnTime(long currentTime) {
        boolean done = false;
        if (mRunning) {
            final long scaledDuration = getScaledDuration();
            final float fraction = scaledDuration > 0 ?
                    (float)(currentTime - mStartTime) / scaledDuration : 1f;
            final float lastFraction = mOverallFraction;
            final boolean newIteration = (int) fraction > (int) lastFraction;
            final boolean lastIterationFinished = (fraction >= mRepeatCount + 1) &&
                    (mRepeatCount != INFINITE);
            if (scaledDuration == 0) {
                // 0 duration animator, ignore the repeat count and skip to the end
                done = true;
            } else if (newIteration && !lastIterationFinished) {
                // Time to repeat
                if (mListeners != null) {
                    int numListeners = mListeners.size();
                    for (int i = 0; i < numListeners; ++i) {
                        mListeners.get(i).onAnimationRepeat(this);
                    }
                }
            } else if (lastIterationFinished) {
                done = true;
            }
            mOverallFraction = clampFraction(fraction);
            float currentIterationFraction = getCurrentIterationFraction(
                    mOverallFraction, mReversing);
            animateValue(currentIterationFraction);
        }
        return done;
    }

    /**
     * Internal use only.
     *
     * This method does not modify any fields of the animation. It should be called when seeking
     * in an AnimatorSet. When the last play time and current play time are of different repeat
     * iterations,
     * {@link android.view.animation.Animation.AnimationListener#onAnimationRepeat(Animation)}
     * will be called.
     */
    @Override
    void animateBasedOnPlayTime(long currentPlayTime, long lastPlayTime, boolean inReverse) {
        if (currentPlayTime < 0 || lastPlayTime < 0) {
            throw new UnsupportedOperationException("Error: Play time should never be negative.");
        }

        initAnimation();
        // Check whether repeat callback is needed only when repeat count is non-zero
        if (mRepeatCount > 0) {
            int iteration = (int) (currentPlayTime / mDuration);
            int lastIteration = (int) (lastPlayTime / mDuration);

            // Clamp iteration to [0, mRepeatCount]
            iteration = Math.min(iteration, mRepeatCount);
            lastIteration = Math.min(lastIteration, mRepeatCount);

            if (iteration != lastIteration) {
                if (mListeners != null) {
                    int numListeners = mListeners.size();
                    for (int i = 0; i < numListeners; ++i) {
                        mListeners.get(i).onAnimationRepeat(this);
                    }
                }
            }
        }

        if (mRepeatCount != INFINITE && currentPlayTime >= (mRepeatCount + 1) * mDuration) {
            skipToEndValue(inReverse);
        } else {
            // Find the current fraction:
            float fraction = currentPlayTime / (float) mDuration;
            fraction = getCurrentIterationFraction(fraction, inReverse);
            animateValue(fraction);
        }
    }

    /**
     * Internal use only.
     * Skips the animation value to end/start, depending on whether the play direction is forward
     * or backward.
     *
     * @param inReverse whether the end value is based on a reverse direction. If yes, this is
     *                  equivalent to skip to start value in a forward playing direction.
     */
    void skipToEndValue(boolean inReverse) {
        initAnimation();
        float endFraction = inReverse ? 0f : 1f;
        if (mRepeatCount % 2 == 1 && mRepeatMode == REVERSE) {
            // This would end on fraction = 0
            endFraction = 0f;
        }
        animateValue(endFraction);
    }

    @Override
    boolean isInitialized() {
        return mInitialized;
    }

    /**
     * Processes a frame of the animation, adjusting the start time if needed.
     *
     * @param frameTime The frame time.
     * @return true if the animation has ended.
     * @hide
     */
    public final boolean doAnimationFrame(long frameTime) {
        if (mStartTime < 0) {
            // First frame. If there is start delay, start delay count down will happen *after* this
            // frame.
            mStartTime = mReversing
                    ? frameTime
                    : frameTime + (long) (mStartDelay * resolveDurationScale());
        }

        // Handle pause/resume
        if (mPaused) {
            mPauseTime = frameTime;
            removeAnimationCallback();
            return false;
        } else if (mResumed) {
            mResumed = false;
            if (mPauseTime > 0) {
                // Offset by the duration that the animation was paused
                mStartTime += (frameTime - mPauseTime);
            }
        }

        if (!mRunning) {
            // If not running, that means the animation is in the start delay phase of a forward
            // running animation. In the case of reversing, we want to run start delay in the end.
            if (mStartTime > frameTime && mSeekFraction == -1) {
                // This is when no seek fraction is set during start delay. If developers change the
                // seek fraction during the delay, animation will start from the seeked position
                // right away.
                return false;
            } else {
                // If mRunning is not set by now, that means non-zero start delay,
                // no seeking, not reversing. At this point, start delay has passed.
                mRunning = true;
                startAnimation();
            }
        }

        if (mLastFrameTime < 0) {
            if (mSeekFraction >= 0) {
                long seekTime = (long) (getScaledDuration() * mSeekFraction);
                mStartTime = frameTime - seekTime;
                mSeekFraction = -1;
            }
            mStartTimeCommitted = false; // allow start time to be compensated for jank
        }
        mLastFrameTime = frameTime;
        // The frame time might be before the start time during the first frame of
        // an animation.  The "current time" must always be on or after the start
        // time to avoid animating frames at negative time intervals.  In practice, this
        // is very rare and only happens when seeking backwards.
        final long currentTime = Math.max(frameTime, mStartTime);
        boolean finished = animateBasedOnTime(currentTime);

        if (finished) {
            endAnimation();
        }
        return finished;
    }

    @Override
    boolean pulseAnimationFrame(long frameTime) {
        if (mSelfPulse) {
            // Pulse animation frame will *always* be after calling start(). If mSelfPulse isn't
            // set to false at this point, that means child animators did not call super's start().
            // This can happen when the Animator is just a non-animating wrapper around a real
            // functional animation. In this case, we can't really pulse a frame into the animation,
            // because the animation cannot necessarily be properly initialized (i.e. no start/end
            // values set).
            return false;
        }
        return doAnimationFrame(frameTime);
    }

    private void addOneShotCommitCallback() {
        if (!mSelfPulse) {
            return;
        }
        getAnimationHandler().addOneShotCommitCallback(this);
    }

    private void removeAnimationCallback() {
        if (!mSelfPulse) {
            return;
        }
        getAnimationHandler().removeCallback(this);
    }

    private void addAnimationCallback(long delay) {
        if (!mSelfPulse) {
            return;
        }
        getAnimationHandler().addAnimationFrameCallback(this, delay);
    }

    /**
     * Returns the current animation fraction, which is the elapsed/interpolated fraction used in
     * the most recent frame update on the animation.
     *
     * @return Elapsed/interpolated fraction of the animation.
     */
    public float getAnimatedFraction() {
        return mCurrentFraction;
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
    @CallSuper
    @UnsupportedAppUsage
    void animateValue(float fraction) {
        fraction = mInterpolator.getInterpolation(fraction);
        mCurrentFraction = fraction;
        int numValues = mValues.length;
        for (int i = 0; i < numValues; ++i) {
            mValues[i].calculateValue(fraction);
        }
        if (mUpdateListeners != null) {
            int numListeners = mUpdateListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                mUpdateListeners.get(i).onAnimationUpdate(this);
            }
        }
    }

    @Override
    public ValueAnimator clone() {
        final ValueAnimator anim = (ValueAnimator) super.clone();
        if (mUpdateListeners != null) {
            anim.mUpdateListeners = new ArrayList<AnimatorUpdateListener>(mUpdateListeners);
        }
        anim.mSeekFraction = -1;
        anim.mReversing = false;
        anim.mInitialized = false;
        anim.mStarted = false;
        anim.mRunning = false;
        anim.mPaused = false;
        anim.mResumed = false;
        anim.mStartListenersCalled = false;
        anim.mStartTime = -1;
        anim.mStartTimeCommitted = false;
        anim.mAnimationEndRequested = false;
        anim.mPauseTime = -1;
        anim.mLastFrameTime = -1;
        anim.mFirstFrameTime = -1;
        anim.mOverallFraction = 0;
        anim.mCurrentFraction = 0;
        anim.mSelfPulse = true;
        anim.mSuppressSelfPulseRequested = false;

        PropertyValuesHolder[] oldValues = mValues;
        if (oldValues != null) {
            int numValues = oldValues.length;
            anim.mValues = new PropertyValuesHolder[numValues];
            anim.mValuesMap = new HashMap<String, PropertyValuesHolder>(numValues);
            for (int i = 0; i < numValues; ++i) {
                PropertyValuesHolder newValuesHolder = oldValues[i].clone();
                anim.mValues[i] = newValuesHolder;
                anim.mValuesMap.put(newValuesHolder.getPropertyName(), newValuesHolder);
            }
        }
        return anim;
    }

    /**
     * Implementors of this interface can add themselves as update listeners
     * to an <code>ValueAnimator</code> instance to receive callbacks on every animation
     * frame, after the current frame's values have been calculated for that
     * <code>ValueAnimator</code>.
     */
    public static interface AnimatorUpdateListener {
        /**
         * <p>Notifies the occurrence of another frame of the animation.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationUpdate(ValueAnimator animation);

    }

    /**
     * Return the number of animations currently running.
     *
     * Used by StrictMode internally to annotate violations.
     * May be called on arbitrary threads!
     *
     * @hide
     */
    public static int getCurrentAnimationsCount() {
        return AnimationHandler.getAnimationCount();
    }

    @Override
    public String toString() {
        String returnVal = "ValueAnimator@" + Integer.toHexString(hashCode());
        if (mValues != null) {
            for (int i = 0; i < mValues.length; ++i) {
                returnVal += "\n    " + mValues[i].toString();
            }
        }
        return returnVal;
    }

    /**
     * <p>Whether or not the ValueAnimator is allowed to run asynchronously off of
     * the UI thread. This is a hint that informs the ValueAnimator that it is
     * OK to run the animation off-thread, however ValueAnimator may decide
     * that it must run the animation on the UI thread anyway. For example if there
     * is an {@link AnimatorUpdateListener} the animation will run on the UI thread,
     * regardless of the value of this hint.</p>
     *
     * <p>Regardless of whether or not the animation runs asynchronously, all
     * listener callbacks will be called on the UI thread.</p>
     *
     * <p>To be able to use this hint the following must be true:</p>
     * <ol>
     * <li>{@link #getAnimatedFraction()} is not needed (it will return undefined values).</li>
     * <li>The animator is immutable while {@link #isStarted()} is true. Requests
     *    to change values, duration, delay, etc... may be ignored.</li>
     * <li>Lifecycle callback events may be asynchronous. Events such as
     *    {@link Animator.AnimatorListener#onAnimationEnd(Animator)} or
     *    {@link Animator.AnimatorListener#onAnimationRepeat(Animator)} may end up delayed
     *    as they must be posted back to the UI thread, and any actions performed
     *    by those callbacks (such as starting new animations) will not happen
     *    in the same frame.</li>
     * <li>State change requests ({@link #cancel()}, {@link #end()}, {@link #reverse()}, etc...)
     *    may be asynchronous. It is guaranteed that all state changes that are
     *    performed on the UI thread in the same frame will be applied as a single
     *    atomic update, however that frame may be the current frame,
     *    the next frame, or some future frame. This will also impact the observed
     *    state of the Animator. For example, {@link #isStarted()} may still return true
     *    after a call to {@link #end()}. Using the lifecycle callbacks is preferred over
     *    queries to {@link #isStarted()}, {@link #isRunning()}, and {@link #isPaused()}
     *    for this reason.</li>
     * </ol>
     * @hide
     */
    @Override
    public void setAllowRunningAsynchronously(boolean mayRunAsync) {
        // It is up to subclasses to support this, if they can.
    }

    /**
     * @return The {@link AnimationHandler} that will be used to schedule updates for this animator.
     * @hide
     */
    public AnimationHandler getAnimationHandler() {
        return AnimationHandler.getInstance();
    }
}
