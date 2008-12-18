/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view.animation;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.TypedValue;

/**
 * Abstraction for an Animation that can be applied to Views, Surfaces, or
 * other objects. See the {@link android.view.animation animation package
 * description file}.
 */
public abstract class Animation {
    /**
     * Repeat the animation indefinitely.
     */
    public static final int INFINITE = -1;

    /**
     * When the animation reaches the end and the repeat count is INFINTE_REPEAT
     * or a positive value, the animation restarts from the beginning.
     */
    public static final int RESTART = 1;

    /**
     * When the animation reaches the end and the repeat count is INFINTE_REPEAT
     * or a positive value, the animation plays backward (and then forward again).
     */
    public static final int REVERSE = 2;

    /**
     * Can be used as the start time to indicate the start time should be the current
     * time when {@link #getTransformation(long, Transformation)} is invoked for the
     * first animation frame. This can is useful for short animations.
     */
    public static final int START_ON_FIRST_FRAME = -1;

    /**
     * The specified dimension is an absolute number of pixels.
     */
    public static final int ABSOLUTE = 0;

    /**
     * The specified dimension holds a float and should be multiplied by the
     * height or width of the object being animated.
     */
    public static final int RELATIVE_TO_SELF = 1;

    /**
     * The specified dimension holds a float and should be multiplied by the
     * height or width of the parent of the object being animated.
     */
    public static final int RELATIVE_TO_PARENT = 2;

    /**
     * Requests that the content being animated be kept in its current Z
     * order.
     */
    public static final int ZORDER_NORMAL = 0;
    
    /**
     * Requests that the content being animated be forced on top of all other
     * content for the duration of the animation.
     */
    public static final int ZORDER_TOP = 1;
    
    /**
     * Requests that the content being animated be forced under all other
     * content for the duration of the animation.
     */
    public static final int ZORDER_BOTTOM = -1;
    
    /**
     * Set by {@link #getTransformation(long, Transformation)} when the animation ends.
     */
    boolean mEnded = false;

    /**
     * Set by {@link #getTransformation(long, Transformation)} when the animation starts.
     */
    boolean mStarted = false;

    /**
     * Set by {@link #getTransformation(long, Transformation)} when the animation repeats
     * in REVERSE mode.
     */
    boolean mCycleFlip = false;

    /**
     * This value must be set to true by {@link #initialize(int, int, int, int)}. It
     * indicates the animation was successfully initialized and can be played.
     */
    boolean mInitialized = false;

    /**
     * Indicates whether the animation transformation should be applied before the
     * animation starts.
     */
    boolean mFillBefore = true;

    /**
     * Indicates whether the animation transformation should be applied after the
     * animation ends.
     */
    boolean mFillAfter = false;

    /**
     * The time in milliseconds at which the animation must start;
     */
    long mStartTime = -1;

    /**
     * The delay in milliseconds after which the animation must start. When the
     * start offset is > 0, the start time of the animation is startTime + startOffset.
     */
    long mStartOffset;

    /**
     * The duration of one animation cycle in milliseconds.
     */
    long mDuration;

    /**
     * The number of times the animation must repeat. By default, an animation repeats
     * indefinitely.
     */
    int mRepeatCount = 0;

    /**
     * Indicates how many times the animation was repeated.
     */
    int mRepeated = 0;

    /**
     * The behavior of the animation when it repeats. The repeat mode is either
     * {@link #RESTART} or {@link #REVERSE}.
     *
     */
    int mRepeatMode = RESTART;

    /**
     * The interpolator used by the animation to smooth the movement.
     */
    Interpolator mInterpolator;

    /**
     * The animation listener to be notified when the animation starts, ends or repeats.
     */
    AnimationListener mListener;

    /**
     * Desired Z order mode during animation.
     */
    private int mZAdjustment;
    
    // Indicates what was the last value returned by getTransformation()
    private boolean mMore = true;

    /**
     * Creates a new animation with a duration of 0ms, the default interpolator, with
     * fillBefore set to true and fillAfter set to false
     */
    public Animation() {
        ensureInterpolator();
    }

    /**
     * Creates a new animation whose parameters come from the specified context and
     * attributes set.
     *
     * @param context the application environment
     * @param attrs the set of attributes holding the animation parameters
     */
    public Animation(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.Animation);

        setDuration((long) a.getInt(com.android.internal.R.styleable.Animation_duration, 0));
        setStartOffset((long) a.getInt(com.android.internal.R.styleable.Animation_startOffset, 0));
        
        setFillBefore(a.getBoolean(com.android.internal.R.styleable.Animation_fillBefore, mFillBefore));
        setFillAfter(a.getBoolean(com.android.internal.R.styleable.Animation_fillAfter, mFillAfter));

        final int resID = a.getResourceId(com.android.internal.R.styleable.Animation_interpolator, 0);
        if (resID > 0) {
            setInterpolator(context, resID);
        }

        setRepeatCount(a.getInt(com.android.internal.R.styleable.Animation_repeatCount, mRepeatCount));
        setRepeatMode(a.getInt(com.android.internal.R.styleable.Animation_repeatMode, RESTART));

        setZAdjustment(a.getInt(com.android.internal.R.styleable.Animation_zAdjustment, ZORDER_NORMAL));
        
        ensureInterpolator();

        a.recycle();
    }

    /**
     * Reset the initialization state of this animation.
     *
     * @see #initialize(int, int, int, int)
     */
    public void reset() {
        mInitialized = false;
        mCycleFlip = false;
        mRepeated = 0;
        mMore = true;
    }

    /**
     * Whether or not the animation has been initialized.
     *
     * @return Has this animation been initialized.
     * @see #initialize(int, int, int, int)
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    /**
     * Initialize this animation with the dimensions of the object being
     * animated as well as the objects parents. (This is to support animation
     * sizes being specifed relative to these dimensions.)
     *
     * <p>Objects that interpret a Animations should call this method when
     * the sizes of the object being animated and its parent are known, and
     * before calling {@link #getTransformation}.
     *
     *
     * @param width Width of the object being animated
     * @param height Height of the object being animated
     * @param parentWidth Width of the animated object's parent
     * @param parentHeight Height of the animated object's parent
     */
    public void initialize(int width, int height, int parentWidth, int parentHeight) {
        mInitialized = true;
        mCycleFlip = false;
        mRepeated = 0;
        mMore = true;
    }

    /**
     * Sets the acceleration curve for this animation. The interpolator is loaded as
     * a resource from the specified context.
     *
     * @param context The application environment
     * @param resID The resource identifier of the interpolator to load
     * @attr ref android.R.styleable#Animation_interpolator
     */
    public void setInterpolator(Context context, int resID) {
        setInterpolator(AnimationUtils.loadInterpolator(context, resID));
    }

    /**
     * Sets the acceleration curve for this animation. Defaults to a linear
     * interpolation.
     *
     * @param i The interpolator which defines the acceleration curve
     * @attr ref android.R.styleable#Animation_interpolator
     */
    public void setInterpolator(Interpolator i) {
        mInterpolator = i;
    }

    /**
     * When this animation should start relative to the start time. This is most
     * useful when composing complex animations using an {@link AnimationSet }
     * where some of the animations components start at different times.
     *
     * @param startOffset When this Animation should start, in milliseconds from
     *                    the start time of the root AnimationSet.
     * @attr ref android.R.styleable#Animation_startOffset
     */
    public void setStartOffset(long startOffset) {
        mStartOffset = startOffset;
    }

    /**
     * How long this animation should last.
     * 
     * @param durationMillis Duration in milliseconds
     * @attr ref android.R.styleable#Animation_duration
     */
    public void setDuration(long durationMillis) {
        mDuration = durationMillis;
    }

    /**
     * Ensure that the duration that this animation will run is not longer
     * than <var>durationMillis</var>.  In addition to adjusting the duration
     * itself, this ensures that the repeat count also will not make it run
     * longer than the given time.
     * 
     * @param durationMillis The maximum duration the animation is allowed
     * to run.
     */
    public void restrictDuration(long durationMillis) {
        if (mStartOffset > durationMillis) {
            mStartOffset = durationMillis;
            mDuration = 0;
            mRepeatCount = 0;
            return;
        }
        
        long dur = mDuration + mStartOffset;
        if (dur > durationMillis) {
            mDuration = dur = durationMillis-mStartOffset;
        }
        if (mRepeatCount < 0 || mRepeatCount > durationMillis
                || (dur*mRepeatCount) > durationMillis) {
            mRepeatCount = (int)(durationMillis/dur);
        }
    }
    
    /**
     * How much to scale the duration by.
     *
     * @param scale The amount to scale the duration.
     */
    public void scaleCurrentDuration(float scale) {
        mDuration = (long) (mDuration * scale);
    }

    /**
     * When this animation should start. When the start time is set to
     * {@link #START_ON_FIRST_FRAME}, the animation will start the first time
     * {@link #getTransformation(long, Transformation)} is invoked. The time passed
     * to this method should be obtained by calling
     * {@link AnimationUtils#currentAnimationTimeMillis()} instead of
     * {@link System#currentTimeMillis()}.
     *
     * @param startTimeMillis the start time in milliseconds
     */
    public void setStartTime(long startTimeMillis) {
        mStartTime = startTimeMillis;
        mStarted = mEnded = false;
        mCycleFlip = false;
        mRepeated = 0;
        mMore = true;
    }

    /**
     * Convenience method to start the animation the first time
     * {@link #getTransformation(long, Transformation)} is invoked.
     */
    public void start() {
        setStartTime(-1);
    }

    /**
     * Convenience method to start the animation at the current time in
     * milliseconds.
     */
    public void startNow() {
        setStartTime(AnimationUtils.currentAnimationTimeMillis());
    }

    /**
     * Defines what this animation should do when it reaches the end. This
     * setting is applied only when the repeat count is either greater than
     * 0 or {@link #INFINITE}. Defaults to {@link #RESTART}. 
     *
     * @param repeatMode {@link #RESTART} or {@link #REVERSE}
     * @attr ref android.R.styleable#Animation_repeatMode
     */
    public void setRepeatMode(int repeatMode) {
        mRepeatMode = repeatMode;
    }

    /**
     * Sets how many times the animation should be repeated. If the repeat
     * count is 0, the animation is never repeated. If the repeat count is
     * greater than 0 or {@link #INFINITE}, the repeat mode will be taken
     * into account. The repeat count if 0 by default.
     *
     * @param repeatCount the number of times the animation should be repeated
     * @attr ref android.R.styleable#Animation_repeatCount
     */
    public void setRepeatCount(int repeatCount) {
        if (repeatCount < 0) {
            repeatCount = INFINITE;
        }
        mRepeatCount = repeatCount;
    }

    /**
     * If fillBefore is true, this animation will apply its transformation
     * before the start time of the animation. Defaults to false if not set.
     * Note that this applies when using an {@link
     * android.view.animation.AnimationSet AnimationSet} to chain
     * animations. The transformation is not applied before the AnimationSet
     * itself starts.
     *
     * @param fillBefore true if the animation should apply its transformation before it starts
     * @attr ref android.R.styleable#Animation_fillBefore
     */
    public void setFillBefore(boolean fillBefore) {
        mFillBefore = fillBefore;
    }

    /**
     * If fillAfter is true, the transformation that this animation performed
     * will persist when it is finished. Defaults to false if not set.
     * Note that this applies when using an {@link
     * android.view.animation.AnimationSet AnimationSet} to chain
     * animations. The transformation is not applied before the AnimationSet
     * itself starts.
     *
     * @param fillAfter true if the animation should apply its transformation after it ends
     * @attr ref android.R.styleable#Animation_fillAfter
     */
    public void setFillAfter(boolean fillAfter) {
        mFillAfter = fillAfter;
    }

    /**
     * Set the Z ordering mode to use while running the animation.
     * 
     * @param zAdjustment The desired mode, one of {@link #ZORDER_NORMAL},
     * {@link #ZORDER_TOP}, or {@link #ZORDER_BOTTOM}.
     * @attr ref android.R.styleable#Animation_zAdjustment
     */
    public void setZAdjustment(int zAdjustment) {
        mZAdjustment = zAdjustment;
    }
    
    /**
     * Gets the acceleration curve type for this animation.
     *
     * @return the {@link Interpolator} associated to this animation
     * @attr ref android.R.styleable#Animation_interpolator
     */
    public Interpolator getInterpolator() {
        return mInterpolator;
    }

    /**
     * When this animation should start. If the animation has not startet yet,
     * this method might return {@link #START_ON_FIRST_FRAME}.
     *
     * @return the time in milliseconds when the animation should start or
     *         {@link #START_ON_FIRST_FRAME}
     */
    public long getStartTime() {
        return mStartTime;
    }

    /**
     * How long this animation should last
     *
     * @return the duration in milliseconds of the animation
     * @attr ref android.R.styleable#Animation_duration
     */
    public long getDuration() {
        return mDuration;
    }

    /**
     * When this animation should start, relative to StartTime
     *
     * @return the start offset in milliseconds
     * @attr ref android.R.styleable#Animation_startOffset
     */
    public long getStartOffset() {
        return mStartOffset;
    }

    /**
     * Defines what this animation should do when it reaches the end.
     *
     * @return either one of {@link #REVERSE} or {@link #RESTART}
     * @attr ref android.R.styleable#Animation_repeatMode
     */
    public int getRepeatMode() {
        return mRepeatMode;
    }

    /**
     * Defines how many times the animation should repeat. The default value
     * is 0.
     *
     * @return the number of times the animation should repeat, or {@link #INFINITE}
     * @attr ref android.R.styleable#Animation_repeatCount
     */
    public int getRepeatCount() {
        return mRepeatCount;
    }

    /**
     * If fillBefore is true, this animation will apply its transformation
     * before the start time of the animation.
     *
     * @return true if the animation applies its transformation before it starts
     * @attr ref android.R.styleable#Animation_fillBefore
     */
    public boolean getFillBefore() {
        return mFillBefore;
    }

    /**
     * If fillAfter is true, this animation will apply its transformation
     * after the end time of the animation.
     *
     * @return true if the animation applies its transformation after it ends
     * @attr ref android.R.styleable#Animation_fillAfter
     */
    public boolean getFillAfter() {
        return mFillAfter;
    }

    /**
     * Returns the Z ordering mode to use while running the animation as
     * previously set by {@link #setZAdjustment}.
     * 
     * @return Returns one of {@link #ZORDER_NORMAL},
     * {@link #ZORDER_TOP}, or {@link #ZORDER_BOTTOM}.
     * @attr ref android.R.styleable#Animation_zAdjustment
     */
    public int getZAdjustment() {
        return mZAdjustment;
    }

    /**
     * <p>Indicates whether or not this animation will affect the transformation
     * matrix. For instance, a fade animation will not affect the matrix whereas
     * a scale animation will.</p>
     *
     * @return true if this animation will change the transformation matrix
     */
    public boolean willChangeTransformationMatrix() {
        // assume we will change the matrix
        return true;
    }

    /**
     * <p>Indicates whether or not this animation will affect the bounds of the
     * animated view. For instance, a fade animation will not affect the bounds
     * whereas a 200% scale animation will.</p>
     *
     * @return true if this animation will change the view's bounds
     */
    public boolean willChangeBounds() {
        // assume we will change the bounds
        return true;
    }

    /**
     * <p>Binds an animation listener to this animation. The animation listener
     * is notified of animation events such as the end of the animation or the
     * repetition of the animation.</p>
     *
     * @param listener the animation listener to be notified
     */
    public void setAnimationListener(AnimationListener listener) {
        mListener = listener;
    }

    /**
     * Gurantees that this animation has an interpolator. Will use
     * a AccelerateDecelerateInterpolator is nothing else was specified.
     */
    protected void ensureInterpolator() {
        if (mInterpolator == null) {
            mInterpolator = new AccelerateDecelerateInterpolator();
        }
    }

    /**
     * Compute a hint at how long the entire animation may last, in milliseconds.
     * Animations can be written to cause themselves to run for a different
     * duration than what is computed here, but generally this should be
     * accurate.
     */
    public long computeDurationHint() {
        return (getStartOffset() + getDuration()) * (getRepeatCount() + 1);
    }
    
    /**
     * Gets the transformation to apply at a specified point in time. Implementations of this
     * method should always replace the specified Transformation or document they are doing
     * otherwise.
     *
     * @param currentTime Where we are in the animation. This is wall clock time.
     * @param outTransformation A tranformation object that is provided by the
     *        caller and will be filled in by the animation.
     * @return True if the animation is still running
     */
    public boolean getTransformation(long currentTime, Transformation outTransformation) {
        if (mStartTime == -1) {
            mStartTime = currentTime;
        }

        final long startOffset = getStartOffset();
        float normalizedTime = ((float) (currentTime - (mStartTime + startOffset))) /
                    (float) mDuration;

        boolean expired = normalizedTime >= 1.0f;
        // Pin time to 0.0 to 1.0 range
        normalizedTime = Math.max(Math.min(normalizedTime, 1.0f), 0.0f);
        mMore = !expired;

        if ((normalizedTime >= 0.0f || mFillBefore) && (normalizedTime <= 1.0f || mFillAfter)) {
            if (!mStarted) {
                if (mListener != null) {
                    mListener.onAnimationStart(this);
                }
                mStarted = true;
            }

            if (mCycleFlip) {
                normalizedTime = 1.0f - normalizedTime;
            }

            final float interpolatedTime = mInterpolator.getInterpolation(normalizedTime);
            applyTransformation(interpolatedTime, outTransformation);
        }

        if (expired) {
            if (mRepeatCount == mRepeated) {
                if (!mEnded) {
                    if (mListener != null) {
                        mListener.onAnimationEnd(this);
                    }
                    mEnded = true;
                }
            } else {
                if (mRepeatCount > 0) {
                    mRepeated++;
                }

                if (mRepeatMode == REVERSE) {
                    mCycleFlip = !mCycleFlip;
                }

                mStartTime = -1;
                mMore = true;

                if (mListener != null) {
                    mListener.onAnimationRepeat(this);
                }
            }
        }

        return mMore;
    }

    /**
     * <p>Indicates whether this animation has started or not.</p>
     *
     * @return true if the animation has started, false otherwise
     */
    public boolean hasStarted() {
        return mStarted;
    }

    /**
     * <p>Indicates whether this animation has ended or not.</p>
     *
     * @return true if the animation has ended, false otherwise
     */
    public boolean hasEnded() {
        return mEnded;
    }

    /**
     * Helper for getTransformation. Subclasses should implement this to apply
     * their transforms given an interpolation value.  Implementations of this
     * method should always replace the specified Transformation or document
     * they are doing otherwise.
     * 
     * @param interpolatedTime The value of the normalized time (0.0 to 1.0)
     *        after it has been run through the interpolation function.
     * @param t The Transofrmation object to fill in with the current
     *        transforms.
     */
    protected void applyTransformation(float interpolatedTime, Transformation t) {
    }

    /**
     * Convert the information in the description of a size to an actual
     * dimension
     *
     * @param type One of Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
     *             Animation.RELATIVE_TO_PARENT.
     * @param value The dimension associated with the type parameter
     * @param size The size of the object being animated
     * @param parentSize The size of the parent of the object being animated
     * @return The dimension to use for the animation
     */
    protected float resolveSize(int type, float value, int size, int parentSize) {
        switch (type) {
            case ABSOLUTE:
                return value;
            case RELATIVE_TO_SELF:
                return size * value;
            case RELATIVE_TO_PARENT:
                return parentSize * value;
            default:
                return value;
        }
    }
    
    /**
     * Utility class to parse a string description of a size.
     */
    protected static class Description {
        /**
         * One of Animation.ABSOLUTE, Animation.RELATIVE_TO_SELF, or
         * Animation.RELATIVE_TO_PARENT.
         */
        public int type;

        /**
         * The absolute or relative dimension for this Description.
         */
        public float value;

        /**
         * Size descriptions can appear inthree forms:
         * <ol>
         * <li>An absolute size. This is represented by a number.</li>
         * <li>A size relative to the size of the object being animated. This
         * is represented by a number followed by "%".</li> *
         * <li>A size relative to the size of the parent of object being
         * animated. This is represented by a number followed by "%p".</li>
         * </ol>
         * @param value The typed value to parse
         * @return The parsed version of the description
         */
        static Description parseValue(TypedValue value) {
            Description d = new Description();
            if (value == null) {
                d.type = ABSOLUTE;
                d.value = 0;
            } else {
                if (value.type == TypedValue.TYPE_FRACTION) {
                    d.type = (value.data & TypedValue.COMPLEX_UNIT_MASK) ==
                            TypedValue.COMPLEX_UNIT_FRACTION_PARENT ?
                                    RELATIVE_TO_PARENT : RELATIVE_TO_SELF;
                    d.value = TypedValue.complexToFloat(value.data);
                    return d;
                } else if (value.type == TypedValue.TYPE_FLOAT) {
                    d.type = ABSOLUTE;
                    d.value = value.getFloat();
                    return d;
                } else if (value.type >= TypedValue.TYPE_FIRST_INT &&
                        value.type <= TypedValue.TYPE_LAST_INT) {
                    d.type = ABSOLUTE;
                    d.value = value.data;
                    return d;
                }
            }

            d.type = ABSOLUTE;
            d.value = 0.0f;

            return d;
        }
    }

    /**
     * <p>An animation listener receives notifications from an animation.
     * Notifications indicate animation related events, such as the end or the
     * repetition of the animation.</p>
     */
    public static interface AnimationListener {
        /**
         * <p>Notifies the start of the animation.</p>
         *
         * @param animation The started animation.
         */
        void onAnimationStart(Animation animation);

        /**
         * <p>Notifies the end of the animation. This callback is invoked
         * only for animation with repeat mode set to NO_REPEAT.</p>
         *
         * @param animation The animation which reached its end.
         */
        void onAnimationEnd(Animation animation);

        /**
         * <p>Notifies the repetition of the animation. This callback is invoked
         * only for animation with repeat mode set to RESTART or REVERSE.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationRepeat(Animation animation);
    }
}
