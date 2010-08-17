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
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import java.util.ArrayList;

/**
 * This class provides a simple timing engine for running animations
 * which calculate animated values and set them on target objects.
 *
 * <p>There is a single timing pulse that all animations use. It runs in a
 * custom handler to ensure that property changes happen on the UI thread.</p>
 *
 * <p>By default, Animator uses non-linear time interpolation, via the
 * {@link AccelerateDecelerateInterpolator} class, which accelerates into and decelerates
 * out of an animation. This behavior can be changed by calling
 * {@link Animator#setInterpolator(Interpolator)}.</p>
 */
public class Animator extends Animatable {

    /**
     * Internal constants
     */

    /*
     * The default amount of time in ms between animation frames
     */
    private static final long DEFAULT_FRAME_DELAY = 30;

    /**
     * Messages sent to timing handler: START is sent when an animation first begins, FRAME is sent
     * by the handler to itself to process the next animation frame
     */
    private static final int ANIMATION_START = 0;
    private static final int ANIMATION_FRAME = 1;

    /**
     * Values used with internal variable mPlayingState to indicate the current state of an
     * animation.
     */
    private static final int STOPPED    = 0; // Not yet playing
    private static final int RUNNING    = 1; // Playing normally
    private static final int CANCELED   = 2; // cancel() called - need to end it
    private static final int ENDED      = 3; // end() called - need to end it
    private static final int SEEKED     = 4; // Seeked to some time value

    /**
     * Enum values used in XML attributes to indicate the value for mValueType
     */
    private static final int VALUE_TYPE_FLOAT       = 0;
    private static final int VALUE_TYPE_INT         = 1;
    private static final int VALUE_TYPE_DOUBLE      = 2;
    private static final int VALUE_TYPE_COLOR       = 3;
    private static final int VALUE_TYPE_CUSTOM      = 4;

    /**
     * Internal variables
     */


    // The first time that the animation's animateFrame() method is called. This time is used to
    // determine elapsed time (and therefore the elapsed fraction) in subsequent calls
    // to animateFrame()
    private long mStartTime;

    /**
     * Set when setCurrentPlayTime() is called. If negative, animation is not currently seeked
     * to a value.
     */
    private long mSeekTime = -1;

    // The static sAnimationHandler processes the internal timing loop on which all animations
    // are based
    private static AnimationHandler sAnimationHandler;

    // The static list of all active animations
    private static final ArrayList<Animator> sAnimations = new ArrayList<Animator>();

    // The set of animations to be started on the next animation frame
    private static final ArrayList<Animator> sPendingAnimations = new ArrayList<Animator>();

    // The time interpolator to be used if none is set on the animation
    private static final Interpolator sDefaultInterpolator = new AccelerateDecelerateInterpolator();

    // type evaluators for the three primitive types handled by this implementation
    private static final TypeEvaluator sIntEvaluator = new IntEvaluator();
    private static final TypeEvaluator sFloatEvaluator = new FloatEvaluator();
    private static final TypeEvaluator sDoubleEvaluator = new DoubleEvaluator();

    /**
     * Used to indicate whether the animation is currently playing in reverse. This causes the
     * elapsed fraction to be inverted to calculate the appropriate values.
     */
    private boolean mPlayingBackwards = false;

    /**
     * This variable tracks the current iteration that is playing. When mCurrentIteration exceeds the
     * repeatCount (if repeatCount!=INFINITE), the animation ends
     */
    private int mCurrentIteration = 0;

    /**
     * Tracks whether a startDelay'd animation has begun playing through the startDelay.
     */
    private boolean mStartedDelay = false;

    /**
     * Tracks the time at which the animation began playing through its startDelay. This is
     * different from the mStartTime variable, which is used to track when the animation became
     * active (which is when the startDelay expired and the animation was added to the active
     * animations list).
     */
    private long mDelayStartTime;

    /**
     * Flag that represents the current state of the animation. Used to figure out when to start
     * an animation (if state == STOPPED). Also used to end an animation that
     * has been cancel()'d or end()'d since the last animation frame. Possible values are
     * STOPPED, RUNNING, ENDED, CANCELED.
     */
    private int mPlayingState = STOPPED;

    /**
     * Internal collections used to avoid set collisions as animations start and end while being
     * processed.
     */
    private static final ArrayList<Animator> sEndingAnims = new ArrayList<Animator>();
    private static final ArrayList<Animator> sDelayedAnims = new ArrayList<Animator>();
    private static final ArrayList<Animator> sReadyAnims = new ArrayList<Animator>();

    /**
     * Flag that denotes whether the animation is set up and ready to go. Used by seek() to
     * set up animation that has not yet been started.
     */
    private boolean mInitialized = false;

    //
    // Backing variables
    //

    // How long the animation should last in ms
    private long mDuration;

    // The value that the animation should start from, set in the constructor
    private Object mValueFrom;

    // The value that the animation should animate to, set in the constructor
    private Object mValueTo;

    // The amount of time in ms to delay starting the animation after start() is called
    private long mStartDelay = 0;

    // The number of milliseconds between animation frames
    private static long sFrameDelay = DEFAULT_FRAME_DELAY;

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
     * The time interpolator to be used. The elapsed fraction of the animation will be passed
     * through this interpolator to calculate the interpolated fraction, which is then used to
     * calculate the animated values.
     */
    private Interpolator mInterpolator = sDefaultInterpolator;

    /**
     * The type evaluator used to calculate the animated values. This evaluator is determined
     * automatically based on the type of the start/end objects passed into the constructor,
     * but the system only knows about the primitive types int, double, and float. Any other
     * type will need to set the evaluator to a custom evaluator for that type.
     */
    private TypeEvaluator mEvaluator;

    /**
     * The set of listeners to be sent events through the life of an animation.
     */
    private ArrayList<AnimatorUpdateListener> mUpdateListeners = null;

    /**
     * The current value calculated by the animation. The value is calculated in animateFraction(),
     * prior to calling the setter (if set) and sending out the onAnimationUpdate() callback
     * to the update listeners.
     */
    private Object mAnimatedValue = null;

    /**
     * The set of keyframes (time/value pairs) that define this animation.
     */
    private KeyframeSet mKeyframeSet = null;

    /**
     * The type of the values, as determined by the valueFrom/valueTo properties.
     */
    Class mValueType;

    /**
     * Public constants
     */

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
     * Creates a new animation whose parameters come from the specified context and
     * attributes set.
     *
     * @param context the application environment
     * @param attrs the set of attributes holding the animation parameters
     */
    public Animator(Context context, AttributeSet attrs) {
        TypedArray a =
                context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Animator);

        mDuration = (long) a.getInt(com.android.internal.R.styleable.Animator_duration, 0);

        mStartDelay = (long) a.getInt(com.android.internal.R.styleable.Animator_startOffset, 0);

        final int resID =
                a.getResourceId(com.android.internal.R.styleable.Animator_interpolator, 0);
        if (resID > 0) {
            setInterpolator(AnimationUtils.loadInterpolator(context, resID));
        }
        int valueType = a.getInt(com.android.internal.R.styleable.Animator_valueType,
                VALUE_TYPE_FLOAT);

        switch (valueType) {
            case VALUE_TYPE_FLOAT:
                mValueFrom = a.getFloat(com.android.internal.R.styleable.Animator_valueFrom, 0f);
                mValueTo = a.getFloat(com.android.internal.R.styleable.Animator_valueTo, 0f);
                mValueType = float.class;
                break;
            case VALUE_TYPE_INT:
                mValueFrom = a.getInt(com.android.internal.R.styleable.Animator_valueFrom, 0);
                mValueTo = a.getInt(com.android.internal.R.styleable.Animator_valueTo, 0);
                mValueType = int.class;
                break;
            case VALUE_TYPE_DOUBLE:
                mValueFrom = (double)
                        a.getFloat(com.android.internal.R.styleable.Animator_valueFrom, 0f);
                mValueTo = (double)
                        a.getFloat(com.android.internal.R.styleable.Animator_valueTo, 0f);
                mValueType = double.class;
                break;
            case VALUE_TYPE_COLOR:
                mValueFrom = a.getInt(com.android.internal.R.styleable.Animator_valueFrom, 0);
                mValueTo = a.getInt(com.android.internal.R.styleable.Animator_valueTo, 0);
                mEvaluator = new RGBEvaluator();
                mValueType = int.class;
                break;
            case VALUE_TYPE_CUSTOM:
                // TODO: How to get an 'Object' value?
                mValueFrom = a.getFloat(com.android.internal.R.styleable.Animator_valueFrom, 0f);
                mValueTo = a.getFloat(com.android.internal.R.styleable.Animator_valueTo, 0f);
                mValueType = Object.class;
                break;
        }

        mRepeatCount = a.getInt(com.android.internal.R.styleable.Animator_repeatCount, mRepeatCount);
        mRepeatMode = a.getInt(com.android.internal.R.styleable.Animator_repeatMode, RESTART);

        a.recycle();
    }

    private Animator(long duration, Object valueFrom, Object valueTo, Class valueType) {
        mDuration = duration;
        mValueFrom = valueFrom;
        mValueTo= valueTo;
        this.mValueType = valueType;
    }

    /**
     * This constructor takes a set of {@link Keyframe} objects that define the values
     * for the animation, along with the times at which those values will hold true during
     * the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param keyframes The set of keyframes that define the time/value pairs for the animation.
     */
    public Animator(long duration, Keyframe...keyframes) {
        mDuration = duration;
        mKeyframeSet = new KeyframeSet(keyframes);
        mValueType = keyframes[0].getType();
    }

    /**
     * A constructor that takes <code>float</code> values.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public Animator(long duration, float valueFrom, float valueTo) {
        this(duration, valueFrom, valueTo, float.class);
    }

    /**
     * A constructor that takes <code>int</code> values.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public Animator(long duration, int valueFrom, int valueTo) {
        this(duration, valueFrom, valueTo, int.class);
    }

    /**
     * A constructor that takes <code>double</code> values.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public Animator(long duration, double valueFrom, double valueTo) {
        this(duration, valueFrom, valueTo, double.class);
    }

    /**
     * A constructor that takes <code>Object</code> values.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    public Animator(long duration, Object valueFrom, Object valueTo) {
        this(duration, valueFrom, valueTo,
                (valueFrom != null) ? valueFrom.getClass() : valueTo.getClass());
    }

    /**
     * Internal constructor that takes a single <code>float</code> value.
     * This constructor is called by PropertyAnimator.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    Animator(long duration, float valueTo) {
        this(duration, null, valueTo, float.class);
    }

    /**
     * Internal constructor that takes a single <code>int</code> value.
     * This constructor is called by PropertyAnimator.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    Animator(long duration, int valueTo) {
        this(duration, null, valueTo, int.class);
    }

    /**
     * Internal constructor that takes a single <code>double</code> value.
     * This constructor is called by PropertyAnimator.
     *
     * @param duration The length of the animation, in milliseconds.
     * @param valueFrom The initial value of the property when the animation begins.
     * @param valueTo The value to which the property will animate.
     */
    Animator(long duration, double valueTo) {
        this(duration, null, valueTo, double.class);
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
    void initAnimation() {
        if (mEvaluator == null) {
            mEvaluator = (mValueType == int.class) ? sIntEvaluator :
                (mValueType == double.class) ? sDoubleEvaluator : sFloatEvaluator;
        }
        mCurrentIteration = 0;
        mInitialized = true;
    }

    /**
     * Sets the position of the animation to the specified point in time. This time should
     * be between 0 and the total duration of the animation, including any repetition. If
     * the animation has not yet been started, then it will not advance forward after it is
     * set to this time; it will simply set the time to this value and perform any appropriate
     * actions based on that time. If the animation is already running, then seek() will
     * set the current playing time to this value and continue playing from that point.
     *
     * @param playTime The time, in milliseconds, to which the animation is advanced or rewound.
     */
    public void setCurrentPlayTime(long playTime) {
        if (!mInitialized) {
            initAnimation();
        }
        long currentTime = AnimationUtils.currentAnimationTimeMillis();
        if (mPlayingState != RUNNING) {
            mSeekTime = playTime;
            mPlayingState = SEEKED;
        }
        mStartTime = currentTime - playTime;
        animationFrame(currentTime);
    }

    /**
     * Gets the current position of the animation in time, which is equal to the current
     * time minus the time that the animation started. An animation that is not yet started will
     * return a value of zero.
     *
     * @return The current position in time of the animation.
     */
    public long getCurrentPlayTime() {
        if (!mInitialized) {
            return 0;
        }
        return AnimationUtils.currentAnimationTimeMillis() - mStartTime;
    }

    /**
     * This custom, static handler handles the timing pulse that is shared by
     * all active animations. This approach ensures that the setting of animation
     * values will happen on the UI thread and that all animations will share
     * the same times for calculating their values, which makes synchronizing
     * animations possible.
     *
     */
    private static class AnimationHandler extends Handler {
        /**
         * There are only two messages that we care about: ANIMATION_START and
         * ANIMATION_FRAME. The START message is sent when an animation's start()
         * method is called. It cannot start synchronously when start() is called
         * because the call may be on the wrong thread, and it would also not be
         * synchronized with other animations because it would not start on a common
         * timing pulse. So each animation sends a START message to the handler, which
         * causes the handler to place the animation on the active animations queue and
         * start processing frames for that animation.
         * The FRAME message is the one that is sent over and over while there are any
         * active animations to process.
         */
        @Override
        public void handleMessage(Message msg) {
            boolean callAgain = true;
            switch (msg.what) {
                // TODO: should we avoid sending frame message when starting if we
                // were already running?
                case ANIMATION_START:
                    if (sAnimations.size() > 0 || sDelayedAnims.size() > 0) {
                        callAgain = false;
                    }
                    // pendingAnims holds any animations that have requested to be started
                    // We're going to clear sPendingAnimations, but starting animation may
                    // cause more to be added to the pending list (for example, if one animation
                    // starting triggers another starting). So we loop until sPendingAnimations
                    // is empty.
                    while (sPendingAnimations.size() > 0) {
                        ArrayList<Animator> pendingCopy =
                                (ArrayList<Animator>) sPendingAnimations.clone();
                        sPendingAnimations.clear();
                        int count = pendingCopy.size();
                        for (int i = 0; i < count; ++i) {
                            Animator anim = pendingCopy.get(i);
                            // If the animation has a startDelay, place it on the delayed list
                            if (anim.mStartDelay == 0) {
                                anim.startAnimation();
                            } else {
                                sDelayedAnims.add(anim);
                            }
                        }
                    }
                    // fall through to process first frame of new animations
                case ANIMATION_FRAME:
                    // currentTime holds the common time for all animations processed
                    // during this frame
                    long currentTime = AnimationUtils.currentAnimationTimeMillis();

                    // First, process animations currently sitting on the delayed queue, adding
                    // them to the active animations if they are ready
                    int numDelayedAnims = sDelayedAnims.size();
                    for (int i = 0; i < numDelayedAnims; ++i) {
                        Animator anim = sDelayedAnims.get(i);
                        if (anim.delayedAnimationFrame(currentTime)) {
                            sReadyAnims.add(anim);
                        }
                    }
                    int numReadyAnims = sReadyAnims.size();
                    if (numReadyAnims > 0) {
                        for (int i = 0; i < numReadyAnims; ++i) {
                            Animator anim = sReadyAnims.get(i);
                            anim.startAnimation();
                            sDelayedAnims.remove(anim);
                        }
                        sReadyAnims.clear();
                    }

                    // Now process all active animations. The return value from animationFrame()
                    // tells the handler whether it should now be ended
                    int numAnims = sAnimations.size();
                    for (int i = 0; i < numAnims; ++i) {
                        Animator anim = sAnimations.get(i);
                        if (anim.animationFrame(currentTime)) {
                            sEndingAnims.add(anim);
                        }
                    }
                    if (sEndingAnims.size() > 0) {
                        for (int i = 0; i < sEndingAnims.size(); ++i) {
                            sEndingAnims.get(i).endAnimation();
                        }
                        sEndingAnims.clear();
                    }

                    // If there are still active or delayed animations, call the handler again
                    // after the frameDelay
                    if (callAgain && (!sAnimations.isEmpty() || !sDelayedAnims.isEmpty())) {
                        sendEmptyMessageDelayed(ANIMATION_FRAME, sFrameDelay);
                    }
                    break;
            }
        }
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called.
     *
     * @return the number of milliseconds to delay running the animation
     */
    public long getStartDelay() {
        return mStartDelay;
    }

    /**
     * The amount of time, in milliseconds, to delay starting the animation after
     * {@link #start()} is called.

     * @param startDelay The amount of the delay, in milliseconds
     */
    public void setStartDelay(long startDelay) {
        this.mStartDelay = startDelay;
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation. This is a
     * requested time that the animation will attempt to honor, but the actual delay between
     * frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     *
     * @return the requested time between frames, in milliseconds
     */
    public static long getFrameDelay() {
        return sFrameDelay;
    }

    /**
     * Gets the set of keyframes that define this animation.
     *
     * @return KeyframeSet The set of keyframes for this animation.
     */
    KeyframeSet getKeyframes() {
        return mKeyframeSet;
    }

    /**
     * Gets the value that this animation will start from.
     *
     * @return Object The starting value for the animation.
     */
    public Object getValueFrom() {
        if (mKeyframeSet != null) {
            return mKeyframeSet.mKeyframes.get(0).getValue();
        }
        return mValueFrom;
    }

    /**
     * Sets the value that this animation will start from.
     */
    public void setValueFrom(Object valueFrom) {
        if (mKeyframeSet != null) {
            Keyframe kf = mKeyframeSet.mKeyframes.get(0);
            kf.setValue(valueFrom);
        } else {
            mValueFrom = valueFrom;
        }
    }

    /**
     * Gets the value that this animation will animate to.
     *
     * @return Object The ending value for the animation.
     */
    public Object getValueTo() {
        if (mKeyframeSet != null) {
            int numKeyframes = mKeyframeSet.mKeyframes.size();
            return mKeyframeSet.mKeyframes.get(numKeyframes - 1).getValue();
        }
        return mValueTo;
    }

    /**
     * Sets the value that this animation will animate to.
     *
     * @return Object The ending value for the animation.
     */
    public void setValueTo(Object valueTo) {
        if (mKeyframeSet != null) {
            int numKeyframes = mKeyframeSet.mKeyframes.size();
            Keyframe kf = mKeyframeSet.mKeyframes.get(numKeyframes - 1);
            kf.setValue(valueTo);
        } else {
            mValueTo = valueTo;
        }
    }

    /**
     * The amount of time, in milliseconds, between each frame of the animation. This is a
     * requested time that the animation will attempt to honor, but the actual delay between
     * frames may be different, depending on system load and capabilities. This is a static
     * function because the same delay will be applied to all animations, since they are all
     * run off of a single timing loop.
     *
     * @param frameDelay the requested time between frames, in milliseconds
     */
    public static void setFrameDelay(long frameDelay) {
        sFrameDelay = frameDelay;
    }

    /**
     * The most recent value calculated by this <code>Animator</code> for the property
     * being animated. This value is only sensible while the animation is running. The main
     * purpose for this read-only property is to retrieve the value from the <code>Animator</code>
     * during a call to {@link AnimatorUpdateListener#onAnimationUpdate(Animator)}, which
     * is called during each animation frame, immediately after the value is calculated.
     *
     * @return animatedValue The value most recently calculated by this <code>Animator</code> for
     * the property specified in the constructor.
     */
    public Object getAnimatedValue() {
        return mAnimatedValue;
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
    public void setRepeatMode(int value) {
        mRepeatMode = value;
    }

    /**
     * Defines what this animation should do when it reaches the end.
     *
     * @return either one of {@link #REVERSE} or {@link #RESTART}
     */
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
     * @param value the interpolator to be used by this animation
     */
    public void setInterpolator(Interpolator value) {
        if (value != null) {
            mInterpolator = value;
        }
    }

    /**
     * The type evaluator to be used when calculating the animated values of this animation.
     * The system will automatically assign a float, int, or double evaluator based on the type
     * of <code>startValue</code> and <code>endValue</code> in the constructor. But if these values
     * are not one of these primitive types, or if different evaluation is desired (such as is
     * necessary with int values that represent colors), a custom evaluator needs to be assigned.
     * For example, when running an animation on color values, the {@link RGBEvaluator}
     * should be used to get correct RGB color interpolation.
     *
     * @param value the evaluator to be used this animation
     */
    public void setEvaluator(TypeEvaluator value) {
        if (value != null) {
            mEvaluator = value;
        }
    }

    private void start(boolean playBackwards) {
        mPlayingBackwards = playBackwards;
        mPlayingState = STOPPED;
        sPendingAnimations.add(this);
        if (sAnimationHandler == null) {
            sAnimationHandler = new AnimationHandler();
        }
        // TODO: does this put too many messages on the queue if the handler
        // is already running?
        sAnimationHandler.sendEmptyMessage(ANIMATION_START);
    }

    @Override
    public void start() {
        start(false);
    }

    @Override
    public void cancel() {
        if (mListeners != null) {
            ArrayList<AnimatableListener> tmpListeners =
                    (ArrayList<AnimatableListener>) mListeners.clone();
            for (AnimatableListener listener : tmpListeners) {
                listener.onAnimationCancel(this);
            }
        }
        // Just set the CANCELED flag - this causes the animation to end the next time a frame
        // is processed.
        mPlayingState = CANCELED;
    }

    @Override
    public void end() {
        // Just set the ENDED flag - this causes the animation to end the next time a frame
        // is processed.
        mPlayingState = ENDED;
    }

    /**
     * Returns whether this Animator is currently running (having been started and not yet ended).
     * @return Wehther the Animator is running.
     */
    public boolean isRunning() {
        return mPlayingState == RUNNING;
    }

    /**
     * Plays the Animator in reverse. If the animation is already running,
     * it will stop itself and play backwards from the point reached when reverse was called.
     * If the animation is not currently running, then it will start from the end and
     * play backwards. This behavior is only set for the current animation; future playing
     * of the animation will use the default behavior of playing forward.
     */
    public void reverse() {
        mPlayingBackwards = !mPlayingBackwards;
        if (mPlayingState == RUNNING) {
            long currentTime = AnimationUtils.currentAnimationTimeMillis();
            long currentPlayTime = currentTime - mStartTime;
            long timeLeft = mDuration - currentPlayTime;
            mStartTime = currentTime - timeLeft;
        } else {
            start(true);
        }
    }

    /**
     * Called internally to end an animation by removing it from the animations list. Must be
     * called on the UI thread.
     */
    private void endAnimation() {
        sAnimations.remove(this);
        if (mListeners != null) {
            ArrayList<AnimatableListener> tmpListeners =
                    (ArrayList<AnimatableListener>) mListeners.clone();
            for (AnimatableListener listener : tmpListeners) {
                listener.onAnimationEnd(this);
            }
        }
        mPlayingState = STOPPED;
    }

    /**
     * Called internally to start an animation by adding it to the active animations list. Must be
     * called on the UI thread.
     */
    private void startAnimation() {
        initAnimation();
        sAnimations.add(this);
        if (mListeners != null) {
            ArrayList<AnimatableListener> tmpListeners =
                    (ArrayList<AnimatableListener>) mListeners.clone();
            for (AnimatableListener listener : tmpListeners) {
                listener.onAnimationStart(this);
            }
        }
    }

    /**
     * Internal function called to process an animation frame on an animation that is currently
     * sleeping through its <code>startDelay</code> phase. The return value indicates whether it
     * should be woken up and put on the active animations queue.
     *
     * @param currentTime The current animation time, used to calculate whether the animation
     * has exceeded its <code>startDelay</code> and should be started.
     * @return True if the animation's <code>startDelay</code> has been exceeded and the animation
     * should be added to the set of active animations.
     */
    private boolean delayedAnimationFrame(long currentTime) {
        if (!mStartedDelay) {
            mStartedDelay = true;
            mDelayStartTime = currentTime;
        } else {
            long deltaTime = currentTime - mDelayStartTime;
            if (deltaTime > mStartDelay) {
                // startDelay ended - start the anim and record the
                // mStartTime appropriately
                mStartTime = currentTime - (deltaTime - mStartDelay);
                mPlayingState = RUNNING;
                return true;
            }
        }
        return false;
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
    private boolean animationFrame(long currentTime) {
        boolean done = false;

        if (mPlayingState == STOPPED) {
            mPlayingState = RUNNING;
            if (mSeekTime < 0) {
                mStartTime = currentTime;
            } else {
                mStartTime = currentTime - mSeekTime;
                // Now that we're playing, reset the seek time
                mSeekTime = -1;
            }
        }
        switch (mPlayingState) {
        case RUNNING:
        case SEEKED:
            float fraction = (float)(currentTime - mStartTime) / mDuration;
            if (fraction >= 1f) {
                if (mCurrentIteration < mRepeatCount || mRepeatCount == INFINITE) {
                    // Time to repeat
                    if (mListeners != null) {
                        for (AnimatableListener listener : mListeners) {
                            listener.onAnimationRepeat(this);
                        }
                    }
                    ++mCurrentIteration;
                    if (mRepeatMode == REVERSE) {
                        mPlayingBackwards = mPlayingBackwards ? false : true;
                    }
                    // TODO: doesn't account for fraction going Wayyyyy over 1, like 2+
                    fraction = fraction - 1f;
                    mStartTime += mDuration;
                } else {
                    done = true;
                    fraction = Math.min(fraction, 1.0f);
                }
            }
            if (mPlayingBackwards) {
                fraction = 1f - fraction;
            }
            animateValue(fraction);
            break;
        case ENDED:
            // The final value set on the target varies, depending on whether the animation
            // was supposed to repeat an odd number of times
            if (mRepeatCount > 0 && (mRepeatCount & 0x01) == 1) {
                animateValue(0f);
            } else {
                animateValue(1f);
            }
            // Fall through to set done flag
        case CANCELED:
            done = true;
            break;
        }

        return done;
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
    void animateValue(float fraction) {
        fraction = mInterpolator.getInterpolation(fraction);
        if (mKeyframeSet != null) {
            mAnimatedValue = mKeyframeSet.getValue(fraction, mEvaluator);
        } else {
            mAnimatedValue = mEvaluator.evaluate(fraction, mValueFrom, mValueTo);
        }
        if (mUpdateListeners != null) {
            int numListeners = mUpdateListeners.size();
            for (int i = 0; i < numListeners; ++i) {
                mUpdateListeners.get(i).onAnimationUpdate(this);
            }
        }
    }

    /**
     * Implementors of this interface can add themselves as update listeners
     * to an <code>Animator</code> instance to receive callbacks on every animation
     * frame, after the current frame's values have been calculated for that
     * <code>Animator</code>.
     */
    public static interface AnimatorUpdateListener {
        /**
         * <p>Notifies the occurrence of another frame of the animation.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationUpdate(Animator animation);

    }
}