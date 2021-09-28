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

import android.annotation.AnimRes;
import android.annotation.ColorInt;
import android.annotation.InterpolatorRes;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.TypedValue;

import dalvik.system.CloseGuard;

/**
 * Abstraction for an Animation that can be applied to Views, Surfaces, or
 * other objects. See the {@link android.view.animation animation package
 * description file}.
 */
public abstract class Animation implements Cloneable {
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

    // Use a preload holder to isolate static initialization into inner class, which allows
    // Animation and its subclasses to be compile-time initialized.
    private static class NoImagePreloadHolder {
        public static final boolean USE_CLOSEGUARD
                = SystemProperties.getBoolean("log.closeguard.Animation", false);
    }

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
     * animation starts. The value of this variable is only relevant if mFillEnabled is true;
     * otherwise it is assumed to be true.
     */
    boolean mFillBefore = true;

    /**
     * Indicates whether the animation transformation should be applied after the
     * animation ends.
     */
    boolean mFillAfter = false;

    /**
     * Indicates whether fillBefore should be taken into account.
     */
    boolean mFillEnabled = false;

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
     * An animation listener to be notified when the animation starts, ends or repeats.
     */
    // If you need to chain the AnimationListener, wrap the existing Animation into an AnimationSet
    // and add your new listener to that set
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 117519981)
    private AnimationListener mListener;

    /**
     * Desired Z order mode during animation.
     */
    private int mZAdjustment;

    /**
     * Desired background color behind animation.
     */
    private int mBackgroundColor;

    /**
     * scalefactor to apply to pivot points, etc. during animation. Subclasses retrieve the
     * value via getScaleFactor().
     */
    private float mScaleFactor = 1f;

    private boolean mShowWallpaper;
    private boolean mHasRoundedCorners;

    private boolean mMore = true;
    private boolean mOneMoreTime = true;

    @UnsupportedAppUsage
    RectF mPreviousRegion = new RectF();
    @UnsupportedAppUsage
    RectF mRegion = new RectF();
    @UnsupportedAppUsage
    Transformation mTransformation = new Transformation();
    @UnsupportedAppUsage
    Transformation mPreviousTransformation = new Transformation();

    private final CloseGuard guard = CloseGuard.get();

    private Handler mListenerHandler;
    private Runnable mOnStart;
    private Runnable mOnRepeat;
    private Runnable mOnEnd;

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

        setFillEnabled(a.getBoolean(com.android.internal.R.styleable.Animation_fillEnabled, mFillEnabled));
        setFillBefore(a.getBoolean(com.android.internal.R.styleable.Animation_fillBefore, mFillBefore));
        setFillAfter(a.getBoolean(com.android.internal.R.styleable.Animation_fillAfter, mFillAfter));

        setRepeatCount(a.getInt(com.android.internal.R.styleable.Animation_repeatCount, mRepeatCount));
        setRepeatMode(a.getInt(com.android.internal.R.styleable.Animation_repeatMode, RESTART));

        setZAdjustment(a.getInt(com.android.internal.R.styleable.Animation_zAdjustment, ZORDER_NORMAL));

        setDetachWallpaper(
                a.getBoolean(com.android.internal.R.styleable.Animation_detachWallpaper, false));
        setShowWallpaper(
                a.getBoolean(com.android.internal.R.styleable.Animation_showWallpaper, false));
        setHasRoundedCorners(
                a.getBoolean(com.android.internal.R.styleable.Animation_hasRoundedCorners, false));

        final int resID = a.getResourceId(com.android.internal.R.styleable.Animation_interpolator, 0);

        a.recycle();

        if (resID > 0) {
            setInterpolator(context, resID);
        }

        ensureInterpolator();
    }

    @Override
    protected Animation clone() throws CloneNotSupportedException {
        final Animation animation = (Animation) super.clone();
        animation.mPreviousRegion = new RectF();
        animation.mRegion = new RectF();
        animation.mTransformation = new Transformation();
        animation.mPreviousTransformation = new Transformation();
        return animation;
    }

    /**
     * Reset the initialization state of this animation.
     *
     * @see #initialize(int, int, int, int)
     */
    public void reset() {
        mPreviousRegion.setEmpty();
        mPreviousTransformation.clear();
        mInitialized = false;
        mCycleFlip = false;
        mRepeated = 0;
        mMore = true;
        mOneMoreTime = true;
        mListenerHandler = null;
    }

    /**
     * Cancel the animation. Cancelling an animation invokes the animation
     * listener, if set, to notify the end of the animation.
     *
     * If you cancel an animation manually, you must call {@link #reset()}
     * before starting the animation again.
     *
     * @see #reset()
     * @see #start()
     * @see #startNow()
     */
    public void cancel() {
        if (mStarted && !mEnded) {
            fireAnimationEnd();
            mEnded = true;
            guard.close();
        }
        // Make sure we move the animation to the end
        mStartTime = Long.MIN_VALUE;
        mMore = mOneMoreTime = false;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void detach() {
        if (mStarted && !mEnded) {
            mEnded = true;
            guard.close();
            fireAnimationEnd();
        }
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
     * sizes being specified relative to these dimensions.)
     *
     * <p>Objects that interpret Animations should call this method when
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
        reset();
        mInitialized = true;
    }

    /**
     * Sets the handler used to invoke listeners.
     *
     * @hide
     */
    public void setListenerHandler(Handler handler) {
        if (mListenerHandler == null) {
            mOnStart = new Runnable() {
                public void run() {
                    dispatchAnimationStart();
                }
            };
            mOnRepeat = new Runnable() {
                public void run() {
                    dispatchAnimationRepeat();
                }
            };
            mOnEnd = new Runnable() {
                public void run() {
                    dispatchAnimationEnd();
                }
            };
        }
        mListenerHandler = handler;
    }

    /**
     * Sets the acceleration curve for this animation. The interpolator is loaded as
     * a resource from the specified context.
     *
     * @param context The application environment
     * @param resID The resource identifier of the interpolator to load
     * @attr ref android.R.styleable#Animation_interpolator
     */
    public void setInterpolator(Context context, @AnimRes @InterpolatorRes int resID) {
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
     * How long this animation should last. The duration cannot be negative.
     *
     * @param durationMillis Duration in milliseconds
     *
     * @throws java.lang.IllegalArgumentException if the duration is < 0
     *
     * @attr ref android.R.styleable#Animation_duration
     */
    public void setDuration(long durationMillis) {
        if (durationMillis < 0) {
            throw new IllegalArgumentException("Animation duration cannot be negative");
        }
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
        // If we start after the duration, then we just won't run.
        if (mStartOffset > durationMillis) {
            mStartOffset = durationMillis;
            mDuration = 0;
            mRepeatCount = 0;
            return;
        }

        long dur = mDuration + mStartOffset;
        if (dur > durationMillis) {
            mDuration = durationMillis-mStartOffset;
            dur = durationMillis;
        }
        // If the duration is 0 or less, then we won't run.
        if (mDuration <= 0) {
            mDuration = 0;
            mRepeatCount = 0;
            return;
        }
        // Reduce the number of repeats to keep below the maximum duration.
        // The comparison between mRepeatCount and duration is to catch
        // overflows after multiplying them.
        if (mRepeatCount < 0 || mRepeatCount > durationMillis
                || (dur*mRepeatCount) > durationMillis) {
            // Figure out how many times to do the animation.  Subtract 1 since
            // repeat count is the number of times to repeat so 0 runs once.
            mRepeatCount = (int)(durationMillis/dur) - 1;
            if (mRepeatCount < 0) {
                mRepeatCount = 0;
            }
        }
    }

    /**
     * How much to scale the duration by.
     *
     * @param scale The amount to scale the duration.
     */
    public void scaleCurrentDuration(float scale) {
        mDuration = (long) (mDuration * scale);
        mStartOffset = (long) (mStartOffset * scale);
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
     * into account. The repeat count is 0 by default.
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
     * If fillEnabled is true, this animation will apply the value of fillBefore.
     *
     * @return true if the animation will take fillBefore into account
     * @attr ref android.R.styleable#Animation_fillEnabled
     */
    public boolean isFillEnabled() {
        return mFillEnabled;
    }

    /**
     * If fillEnabled is true, the animation will apply the value of fillBefore.
     * Otherwise, fillBefore is ignored and the animation
     * transformation is always applied until the animation ends.
     *
     * @param fillEnabled true if the animation should take the value of fillBefore into account
     * @attr ref android.R.styleable#Animation_fillEnabled
     *
     * @see #setFillBefore(boolean)
     * @see #setFillAfter(boolean)
     */
    public void setFillEnabled(boolean fillEnabled) {
        mFillEnabled = fillEnabled;
    }

    /**
     * If fillBefore is true, this animation will apply its transformation
     * before the start time of the animation. Defaults to true if
     * {@link #setFillEnabled(boolean)} is not set to true.
     * Note that this applies when using an {@link
     * android.view.animation.AnimationSet AnimationSet} to chain
     * animations. The transformation is not applied before the AnimationSet
     * itself starts.
     *
     * @param fillBefore true if the animation should apply its transformation before it starts
     * @attr ref android.R.styleable#Animation_fillBefore
     *
     * @see #setFillEnabled(boolean)
     */
    public void setFillBefore(boolean fillBefore) {
        mFillBefore = fillBefore;
    }

    /**
     * If fillAfter is true, the transformation that this animation performed
     * will persist when it is finished. Defaults to false if not set.
     * Note that this applies to individual animations and when using an {@link
     * android.view.animation.AnimationSet AnimationSet} to chain
     * animations.
     *
     * @param fillAfter true if the animation should apply its transformation after it ends
     * @attr ref android.R.styleable#Animation_fillAfter
     *
     * @see #setFillEnabled(boolean)
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
     * Set background behind an animation.
     *
     * @param bg The background color. If 0, no background.
     *
     * @deprecated None of window animations are running with background color.
     */
    @Deprecated
    public void setBackgroundColor(@ColorInt int bg) {
        mBackgroundColor = bg;
    }

    /**
     * The scale factor is set by the call to <code>getTransformation</code>. Overrides of
     * {@link #getTransformation(long, Transformation, float)} will get this value
     * directly. Overrides of {@link #applyTransformation(float, Transformation)} can
     * call this method to get the value.
     *
     * @return float The scale factor that should be applied to pre-scaled values in
     * an Animation such as the pivot points in {@link ScaleAnimation} and {@link RotateAnimation}.
     */
    protected float getScaleFactor() {
        return mScaleFactor;
    }

    /**
     * If detachWallpaper is true, and this is a window animation of a window
     * that has a wallpaper background, then the window will be detached from
     * the wallpaper while it runs.  That is, the animation will only be applied
     * to the window, and the wallpaper behind it will remain static.
     *
     * @param detachWallpaper true if the wallpaper should be detached from the animation
     * @attr ref android.R.styleable#Animation_detachWallpaper
     *
     * @deprecated All window animations are running with detached wallpaper.
     */
    @Deprecated
    public void setDetachWallpaper(boolean detachWallpaper) {
    }

    /**
     * If this animation is run as a window animation, this will make the wallpaper visible behind
     * the animation.
     *
     * @param showWallpaper Whether the wallpaper should be shown during the animation.
     * @attr ref android.R.styleable#Animation_detachWallpaper
     * @hide
     */
    public void setShowWallpaper(boolean showWallpaper) {
        mShowWallpaper = showWallpaper;
    }

    /**
     * If this is a window animation, the window will have rounded corners matching the display
     * corner radius.
     *
     * @param hasRoundedCorners Whether the window should have rounded corners or not.
     * @attr ref android.R.styleable#Animation_hasRoundedCorners
     * @see com.android.internal.policy.ScreenDecorationsUtils#getWindowCornerRadius(Resources)
     * @hide
     */
    public void setHasRoundedCorners(boolean hasRoundedCorners) {
        mHasRoundedCorners = hasRoundedCorners;
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
     * before the start time of the animation. If fillBefore is false and
     * {@link #isFillEnabled() fillEnabled} is true, the transformation will not be applied until
     * the start time of the animation.
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
     * Returns the background color behind the animation.
     *
     * @deprecated None of window animations are running with background color.
     */
    @Deprecated
    @ColorInt
    public int getBackgroundColor() {
        return mBackgroundColor;
    }

    /**
     * Return value of {@link #setDetachWallpaper(boolean)}.
     * @attr ref android.R.styleable#Animation_detachWallpaper
     *
     * @deprecated All window animations are running with detached wallpaper.
     */
    @Deprecated
    public boolean getDetachWallpaper() {
        return true;
    }

    /**
     * @return If run as a window animation, returns whether the wallpaper will be shown behind
     *         during the animation.
     * @attr ref android.R.styleable#Animation_showWallpaper
     * @hide
     */
    public boolean getShowWallpaper() {
        return mShowWallpaper;
    }

    /**
     * @return if a window animation should have rounded corners or not.
     *
     * @attr ref android.R.styleable#Animation_hasRoundedCorners
     * @hide
     */
    public boolean hasRoundedCorners() {
        return mHasRoundedCorners;
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

    private boolean hasAnimationListener() {
        return mListener != null;
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
     * @param outTransformation A transformation object that is provided by the
     *        caller and will be filled in by the animation.
     * @return True if the animation is still running
     */
    public boolean getTransformation(long currentTime, Transformation outTransformation) {
        if (mStartTime == -1) {
            mStartTime = currentTime;
        }

        final long startOffset = getStartOffset();
        final long duration = mDuration;
        float normalizedTime;
        if (duration != 0) {
            normalizedTime = ((float) (currentTime - (mStartTime + startOffset))) /
                    (float) duration;
        } else {
            // time is a step-change with a zero duration
            normalizedTime = currentTime < mStartTime ? 0.0f : 1.0f;
        }

        final boolean expired = normalizedTime >= 1.0f || isCanceled();
        mMore = !expired;

        if (!mFillEnabled) normalizedTime = Math.max(Math.min(normalizedTime, 1.0f), 0.0f);

        if ((normalizedTime >= 0.0f || mFillBefore) && (normalizedTime <= 1.0f || mFillAfter)) {
            if (!mStarted) {
                fireAnimationStart();
                mStarted = true;
                if (NoImagePreloadHolder.USE_CLOSEGUARD) {
                    guard.open("cancel or detach or getTransformation");
                }
            }

            if (mFillEnabled) normalizedTime = Math.max(Math.min(normalizedTime, 1.0f), 0.0f);

            if (mCycleFlip) {
                normalizedTime = 1.0f - normalizedTime;
            }

            final float interpolatedTime = mInterpolator.getInterpolation(normalizedTime);
            applyTransformation(interpolatedTime, outTransformation);
        }

        if (expired) {
            if (mRepeatCount == mRepeated || isCanceled()) {
                if (!mEnded) {
                    mEnded = true;
                    guard.close();
                    fireAnimationEnd();
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

                fireAnimationRepeat();
            }
        }

        if (!mMore && mOneMoreTime) {
            mOneMoreTime = false;
            return true;
        }

        return mMore;
    }

    private boolean isCanceled() {
        return mStartTime == Long.MIN_VALUE;
    }

    private void fireAnimationStart() {
        if (hasAnimationListener()) {
            if (mListenerHandler == null) dispatchAnimationStart();
            else mListenerHandler.postAtFrontOfQueue(mOnStart);
        }
    }

    private void fireAnimationRepeat() {
        if (hasAnimationListener()) {
            if (mListenerHandler == null) dispatchAnimationRepeat();
            else mListenerHandler.postAtFrontOfQueue(mOnRepeat);
        }
    }

    private void fireAnimationEnd() {
        if (hasAnimationListener()) {
            if (mListenerHandler == null) dispatchAnimationEnd();
            else mListenerHandler.postAtFrontOfQueue(mOnEnd);
        }
    }

    void dispatchAnimationStart() {
        if (mListener != null) {
            mListener.onAnimationStart(this);
        }
    }

    void dispatchAnimationRepeat() {
        if (mListener != null) {
            mListener.onAnimationRepeat(this);
        }
    }

    void dispatchAnimationEnd() {
        if (mListener != null) {
            mListener.onAnimationEnd(this);
        }
    }

    /**
     * Gets the transformation to apply at a specified point in time. Implementations of this
     * method should always replace the specified Transformation or document they are doing
     * otherwise.
     *
     * @param currentTime Where we are in the animation. This is wall clock time.
     * @param outTransformation A transformation object that is provided by the
     *        caller and will be filled in by the animation.
     * @param scale Scaling factor to apply to any inputs to the transform operation, such
     *        pivot points being rotated or scaled around.
     * @return True if the animation is still running
     */
    public boolean getTransformation(long currentTime, Transformation outTransformation,
            float scale) {
        mScaleFactor = scale;
        return getTransformation(currentTime, outTransformation);
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
     * @param t The Transformation object to fill in with the current
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
     * @param left
     * @param top
     * @param right
     * @param bottom
     * @param invalidate
     * @param transformation
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void getInvalidateRegion(int left, int top, int right, int bottom,
            RectF invalidate, Transformation transformation) {

        final RectF tempRegion = mRegion;
        final RectF previousRegion = mPreviousRegion;

        invalidate.set(left, top, right, bottom);
        transformation.getMatrix().mapRect(invalidate);
        // Enlarge the invalidate region to account for rounding errors
        invalidate.inset(-1.0f, -1.0f);
        tempRegion.set(invalidate);
        invalidate.union(previousRegion);

        previousRegion.set(tempRegion);

        final Transformation tempTransformation = mTransformation;
        final Transformation previousTransformation = mPreviousTransformation;

        tempTransformation.set(transformation);
        transformation.set(previousTransformation);
        previousTransformation.set(tempTransformation);
    }

    /**
     * @param left
     * @param top
     * @param right
     * @param bottom
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void initializeInvalidateRegion(int left, int top, int right, int bottom) {
        final RectF region = mPreviousRegion;
        region.set(left, top, right, bottom);
        // Enlarge the invalidate region to account for rounding errors
        region.inset(-1.0f, -1.0f);
        if (mFillBefore) {
            final Transformation previousTransformation = mPreviousTransformation;
            applyTransformation(mInterpolator.getInterpolation(0.0f), previousTransformation);
        }
    }

    protected void finalize() throws Throwable {
        try {
            if (guard != null) {
                guard.warnIfOpen();
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * Return true if this animation changes the view's alpha property.
     *
     * @hide
     */
    public boolean hasAlpha() {
        return false;
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
         * <p>Notifies the end of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which reached its end.
         */
        void onAnimationEnd(Animation animation);

        /**
         * <p>Notifies the repetition of the animation.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationRepeat(Animation animation);
    }
}
