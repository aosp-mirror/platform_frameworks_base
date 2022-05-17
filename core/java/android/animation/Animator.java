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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ConstantState;
import android.os.Build;

import java.util.ArrayList;

/**
 * This is the superclass for classes which provide basic support for animations which can be
 * started, ended, and have <code>AnimatorListeners</code> added to them.
 */
public abstract class Animator implements Cloneable {

    /**
     * The value used to indicate infinite duration (e.g. when Animators repeat infinitely).
     */
    public static final long DURATION_INFINITE = -1;
    /**
     * The set of listeners to be sent events through the life of an animation.
     */
    ArrayList<AnimatorListener> mListeners = null;

    /**
     * The set of listeners to be sent pause/resume events through the life
     * of an animation.
     */
    ArrayList<AnimatorPauseListener> mPauseListeners = null;

    /**
     * Whether this animator is currently in a paused state.
     */
    boolean mPaused = false;

    /**
     * A set of flags which identify the type of configuration changes that can affect this
     * Animator. Used by the Animator cache.
     */
    @Config int mChangingConfigurations = 0;

    /**
     * If this animator is inflated from a constant state, keep a reference to it so that
     * ConstantState will not be garbage collected until this animator is collected
     */
    private AnimatorConstantState mConstantState;

    /**
     * backing field for backgroundPauseDelay property. This could be simply a hardcoded
     * value in AnimationHandler, but it is useful to be able to change the value in tests.
     */
    private static long sBackgroundPauseDelay = 10000;

    /**
     * Sets the duration for delaying pausing animators when apps go into the background.
     * Used by AnimationHandler when requested to pause animators.
     *
     * @hide
     */
    @TestApi
    public static void setBackgroundPauseDelay(long value) {
        sBackgroundPauseDelay = value;
    }

    /**
     * Gets the duration for delaying pausing animators when apps go into the background.
     * Used by AnimationHandler when requested to pause animators.
     *
     * @hide
     */
    @TestApi
    public static long getBackgroundPauseDelay() {
        return sBackgroundPauseDelay;
    }

    /**
     * Sets the behavior of animator pausing when apps go into the background.
     * This is exposed as a test API for verification, but is intended for use by internal/
     * platform code, potentially for use by a system property that could disable it
     * system wide.
     *
     * @param enable Enable (default behavior) or disable background pausing behavior.
     * @hide
     */
    @TestApi
    public static void setAnimatorPausingEnabled(boolean enable) {
        AnimationHandler.setAnimatorPausingEnabled(enable);
    }

    /**
     * Starts this animation. If the animation has a nonzero startDelay, the animation will start
     * running after that delay elapses. A non-delayed animation will have its initial
     * value(s) set immediately, followed by calls to
     * {@link AnimatorListener#onAnimationStart(Animator)} for any listeners of this animator.
     *
     * <p>The animation started by calling this method will be run on the thread that called
     * this method. This thread should have a Looper on it (a runtime exception will be thrown if
     * this is not the case). Also, if the animation will animate
     * properties of objects in the view hierarchy, then the calling thread should be the UI
     * thread for that view hierarchy.</p>
     *
     */
    public void start() {
    }

    /**
     * Cancels the animation. Unlike {@link #end()}, <code>cancel()</code> causes the animation to
     * stop in its tracks, sending an
     * {@link android.animation.Animator.AnimatorListener#onAnimationCancel(Animator)} to
     * its listeners, followed by an
     * {@link android.animation.Animator.AnimatorListener#onAnimationEnd(Animator)} message.
     *
     * <p>This method must be called on the thread that is running the animation.</p>
     */
    public void cancel() {
    }

    /**
     * Ends the animation. This causes the animation to assign the end value of the property being
     * animated, then calling the
     * {@link android.animation.Animator.AnimatorListener#onAnimationEnd(Animator)} method on
     * its listeners.
     *
     * <p>This method must be called on the thread that is running the animation.</p>
     */
    public void end() {
    }

    /**
     * Pauses a running animation. This method should only be called on the same thread on
     * which the animation was started. If the animation has not yet been {@link
     * #isStarted() started} or has since ended, then the call is ignored. Paused
     * animations can be resumed by calling {@link #resume()}.
     *
     * @see #resume()
     * @see #isPaused()
     * @see AnimatorPauseListener
     */
    public void pause() {
        if (isStarted() && !mPaused) {
            mPaused = true;
            if (mPauseListeners != null) {
                ArrayList<AnimatorPauseListener> tmpListeners =
                        (ArrayList<AnimatorPauseListener>) mPauseListeners.clone();
                int numListeners = tmpListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    tmpListeners.get(i).onAnimationPause(this);
                }
            }
        }
    }

    /**
     * Resumes a paused animation, causing the animator to pick up where it left off
     * when it was paused. This method should only be called on the same thread on
     * which the animation was started. Calls to resume() on an animator that is
     * not currently paused will be ignored.
     *
     * @see #pause()
     * @see #isPaused()
     * @see AnimatorPauseListener
     */
    public void resume() {
        if (mPaused) {
            mPaused = false;
            if (mPauseListeners != null) {
                ArrayList<AnimatorPauseListener> tmpListeners =
                        (ArrayList<AnimatorPauseListener>) mPauseListeners.clone();
                int numListeners = tmpListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    tmpListeners.get(i).onAnimationResume(this);
                }
            }
        }
    }

    /**
     * Returns whether this animator is currently in a paused state.
     *
     * @return True if the animator is currently paused, false otherwise.
     *
     * @see #pause()
     * @see #resume()
     */
    public boolean isPaused() {
        return mPaused;
    }

    /**
     * The amount of time, in milliseconds, to delay processing the animation
     * after {@link #start()} is called.
     *
     * @return the number of milliseconds to delay running the animation
     */
    public abstract long getStartDelay();

    /**
     * The amount of time, in milliseconds, to delay processing the animation
     * after {@link #start()} is called.

     * @param startDelay The amount of the delay, in milliseconds
     */
    public abstract void setStartDelay(long startDelay);

    /**
     * Sets the duration of the animation.
     *
     * @param duration The length of the animation, in milliseconds.
     */
    public abstract Animator setDuration(long duration);

    /**
     * Gets the duration of the animation.
     *
     * @return The length of the animation, in milliseconds.
     */
    public abstract long getDuration();

    /**
     * Gets the total duration of the animation, accounting for animation sequences, start delay,
     * and repeating. Return {@link #DURATION_INFINITE} if the duration is infinite.
     *
     * @return  Total time an animation takes to finish, starting from the time {@link #start()}
     *          is called. {@link #DURATION_INFINITE} will be returned if the animation or any
     *          child animation repeats infinite times.
     */
    public long getTotalDuration() {
        long duration = getDuration();
        if (duration == DURATION_INFINITE) {
            return DURATION_INFINITE;
        } else {
            return getStartDelay() + duration;
        }
    }

    /**
     * The time interpolator used in calculating the elapsed fraction of the
     * animation. The interpolator determines whether the animation runs with
     * linear or non-linear motion, such as acceleration and deceleration. The
     * default value is {@link android.view.animation.AccelerateDecelerateInterpolator}.
     *
     * @param value the interpolator to be used by this animation
     */
    public abstract void setInterpolator(TimeInterpolator value);

    /**
     * Returns the timing interpolator that this animation uses.
     *
     * @return The timing interpolator for this animation.
     */
    public TimeInterpolator getInterpolator() {
        return null;
    }

    /**
     * Returns whether this Animator is currently running (having been started and gone past any
     * initial startDelay period and not yet ended).
     *
     * @return Whether the Animator is running.
     */
    public abstract boolean isRunning();

    /**
     * Returns whether this Animator has been started and not yet ended. For reusable
     * Animators (which most Animators are, apart from the one-shot animator produced by
     * {@link android.view.ViewAnimationUtils#createCircularReveal(
     * android.view.View, int, int, float, float) createCircularReveal()}),
     * this state is a superset of {@link #isRunning()}, because an Animator with a
     * nonzero {@link #getStartDelay() startDelay} will return true for {@link #isStarted()} during
     * the delay phase, whereas {@link #isRunning()} will return true only after the delay phase
     * is complete. Non-reusable animators will always return true after they have been
     * started, because they cannot return to a non-started state.
     *
     * @return Whether the Animator has been started and not yet ended.
     */
    public boolean isStarted() {
        // Default method returns value for isRunning(). Subclasses should override to return a
        // real value.
        return isRunning();
    }

    /**
     * Adds a listener to the set of listeners that are sent events through the life of an
     * animation, such as start, repeat, and end.
     *
     * @param listener the listener to be added to the current set of listeners for this animation.
     */
    public void addListener(AnimatorListener listener) {
        if (mListeners == null) {
            mListeners = new ArrayList<AnimatorListener>();
        }
        mListeners.add(listener);
    }

    /**
     * Removes a listener from the set listening to this animation.
     *
     * @param listener the listener to be removed from the current set of listeners for this
     *                 animation.
     */
    public void removeListener(AnimatorListener listener) {
        if (mListeners == null) {
            return;
        }
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            mListeners = null;
        }
    }

    /**
     * Gets the set of {@link android.animation.Animator.AnimatorListener} objects that are currently
     * listening for events on this <code>Animator</code> object.
     *
     * @return ArrayList<AnimatorListener> The set of listeners.
     */
    public ArrayList<AnimatorListener> getListeners() {
        return mListeners;
    }

    /**
     * Adds a pause listener to this animator.
     *
     * @param listener the listener to be added to the current set of pause listeners
     * for this animation.
     */
    public void addPauseListener(AnimatorPauseListener listener) {
        if (mPauseListeners == null) {
            mPauseListeners = new ArrayList<AnimatorPauseListener>();
        }
        mPauseListeners.add(listener);
    }

    /**
     * Removes a pause listener from the set listening to this animation.
     *
     * @param listener the listener to be removed from the current set of pause
     * listeners for this animation.
     */
    public void removePauseListener(AnimatorPauseListener listener) {
        if (mPauseListeners == null) {
            return;
        }
        mPauseListeners.remove(listener);
        if (mPauseListeners.size() == 0) {
            mPauseListeners = null;
        }
    }

    /**
     * Removes all {@link #addListener(android.animation.Animator.AnimatorListener) listeners}
     * and {@link #addPauseListener(android.animation.Animator.AnimatorPauseListener)
     * pauseListeners} from this object.
     */
    public void removeAllListeners() {
        if (mListeners != null) {
            mListeners.clear();
            mListeners = null;
        }
        if (mPauseListeners != null) {
            mPauseListeners.clear();
            mPauseListeners = null;
        }
    }

    /**
     * Return a mask of the configuration parameters for which this animator may change, requiring
     * that it should be re-created from Resources. The default implementation returns whatever
     * value was provided through setChangingConfigurations(int) or 0 by default.
     *
     * @return Returns a mask of the changing configuration parameters, as defined by
     * {@link android.content.pm.ActivityInfo}.
     * @see android.content.pm.ActivityInfo
     * @hide
     */
    public @Config int getChangingConfigurations() {
        return mChangingConfigurations;
    }

    /**
     * Set a mask of the configuration parameters for which this animator may change, requiring
     * that it be re-created from resource.
     *
     * @param configs A mask of the changing configuration parameters, as
     * defined by {@link android.content.pm.ActivityInfo}.
     *
     * @see android.content.pm.ActivityInfo
     * @hide
     */
    public void setChangingConfigurations(@Config int configs) {
        mChangingConfigurations = configs;
    }

    /**
     * Sets the changing configurations value to the union of the current changing configurations
     * and the provided configs.
     * This method is called while loading the animator.
     * @hide
     */
    public void appendChangingConfigurations(@Config int configs) {
        mChangingConfigurations |= configs;
    }

    /**
     * Return a {@link android.content.res.ConstantState} instance that holds the shared state of
     * this Animator.
     * <p>
     * This constant state is used to create new instances of this animator when needed, instead
     * of re-loading it from resources. Default implementation creates a new
     * {@link AnimatorConstantState}. You can override this method to provide your custom logic or
     * return null if you don't want this animator to be cached.
     *
     * @return The ConfigurationBoundResourceCache.BaseConstantState associated to this Animator.
     * @see android.content.res.ConstantState
     * @see #clone()
     * @hide
     */
    public ConstantState<Animator> createConstantState() {
        return new AnimatorConstantState(this);
    }

    @Override
    public Animator clone() {
        try {
            final Animator anim = (Animator) super.clone();
            if (mListeners != null) {
                anim.mListeners = new ArrayList<AnimatorListener>(mListeners);
            }
            if (mPauseListeners != null) {
                anim.mPauseListeners = new ArrayList<AnimatorPauseListener>(mPauseListeners);
            }
            return anim;
        } catch (CloneNotSupportedException e) {
           throw new AssertionError();
        }
    }

    /**
     * This method tells the object to use appropriate information to extract
     * starting values for the animation. For example, a AnimatorSet object will pass
     * this call to its child objects to tell them to set up the values. A
     * ObjectAnimator object will use the information it has about its target object
     * and PropertyValuesHolder objects to get the start values for its properties.
     * A ValueAnimator object will ignore the request since it does not have enough
     * information (such as a target object) to gather these values.
     */
    public void setupStartValues() {
    }

    /**
     * This method tells the object to use appropriate information to extract
     * ending values for the animation. For example, a AnimatorSet object will pass
     * this call to its child objects to tell them to set up the values. A
     * ObjectAnimator object will use the information it has about its target object
     * and PropertyValuesHolder objects to get the start values for its properties.
     * A ValueAnimator object will ignore the request since it does not have enough
     * information (such as a target object) to gather these values.
     */
    public void setupEndValues() {
    }

    /**
     * Sets the target object whose property will be animated by this animation. Not all subclasses
     * operate on target objects (for example, {@link ValueAnimator}, but this method
     * is on the superclass for the convenience of dealing generically with those subclasses
     * that do handle targets.
     * <p>
     * <strong>Note:</strong> The target is stored as a weak reference internally to avoid leaking
     * resources by having animators directly reference old targets. Therefore, you should
     * ensure that animator targets always have a hard reference elsewhere.
     *
     * @param target The object being animated
     */
    public void setTarget(@Nullable Object target) {
    }

    // Hide reverse() and canReverse() for now since reverse() only work for simple
    // cases, like we don't support sequential, neither startDelay.
    // TODO: make reverse() works for all the Animators.
    /**
     * @hide
     */
    public boolean canReverse() {
        return false;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void reverse() {
        throw new IllegalStateException("Reverse is not supported");
    }

    // Pulse an animation frame into the animation.
    boolean pulseAnimationFrame(long frameTime) {
        // TODO: Need to find a better signal than this. There's a bug in SystemUI that's preventing
        // returning !isStarted() from working.
        return false;
    }

    /**
     * Internal use only.
     * This call starts the animation in regular or reverse direction without requiring them to
     * register frame callbacks. The caller will be responsible for all the subsequent animation
     * pulses. Specifically, the caller needs to call doAnimationFrame(...) for the animation on
     * every frame.
     *
     * @param inReverse whether the animation should play in reverse direction
     */
    void startWithoutPulsing(boolean inReverse) {
        if (inReverse) {
            reverse();
        } else {
            start();
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
    void skipToEndValue(boolean inReverse) {}


    /**
     * Internal use only.
     *
     * Returns whether the animation has start/end values setup. For most of the animations, this
     * should always be true. For ObjectAnimators, the start values are setup in the initialization
     * of the animation.
     */
    boolean isInitialized() {
        return true;
    }

    /**
     * Internal use only.
     */
    void animateBasedOnPlayTime(long currentPlayTime, long lastPlayTime, boolean inReverse) {}

    /**
     * <p>An animation listener receives notifications from an animation.
     * Notifications indicate animation related events, such as the end or the
     * repetition of the animation.</p>
     */
    public static interface AnimatorListener {

        /**
         * <p>Notifies the start of the animation as well as the animation's overall play direction.
         * This method's default behavior is to call {@link #onAnimationStart(Animator)}. This
         * method can be overridden, though not required, to get the additional play direction info
         * when an animation starts. Skipping calling super when overriding this method results in
         * {@link #onAnimationStart(Animator)} not getting called.
         *
         * @param animation The started animation.
         * @param isReverse Whether the animation is playing in reverse.
         */
        default void onAnimationStart(@NonNull Animator animation, boolean isReverse) {
            onAnimationStart(animation);
        }

        /**
         * <p>Notifies the end of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * <p>This method's default behavior is to call {@link #onAnimationEnd(Animator)}. This
         * method can be overridden, though not required, to get the additional play direction info
         * when an animation ends. Skipping calling super when overriding this method results in
         * {@link #onAnimationEnd(Animator)} not getting called.
         *
         * @param animation The animation which reached its end.
         * @param isReverse Whether the animation is playing in reverse.
         */
        default void onAnimationEnd(@NonNull Animator animation, boolean isReverse) {
            onAnimationEnd(animation);
        }

        /**
         * <p>Notifies the start of the animation.</p>
         *
         * @param animation The started animation.
         */
        void onAnimationStart(@NonNull Animator animation);

        /**
         * <p>Notifies the end of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which reached its end.
         */
        void onAnimationEnd(@NonNull Animator animation);

        /**
         * <p>Notifies the cancellation of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which was canceled.
         */
        void onAnimationCancel(@NonNull Animator animation);

        /**
         * <p>Notifies the repetition of the animation.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationRepeat(@NonNull Animator animation);
    }

    /**
     * A pause listener receives notifications from an animation when the
     * animation is {@link #pause() paused} or {@link #resume() resumed}.
     *
     * @see #addPauseListener(AnimatorPauseListener)
     */
    public static interface AnimatorPauseListener {
        /**
         * <p>Notifies that the animation was paused.</p>
         *
         * @param animation The animaton being paused.
         * @see #pause()
         */
        void onAnimationPause(@NonNull Animator animation);

        /**
         * <p>Notifies that the animation was resumed, after being
         * previously paused.</p>
         *
         * @param animation The animation being resumed.
         * @see #resume()
         */
        void onAnimationResume(@NonNull Animator animation);
    }

    /**
     * <p>Whether or not the Animator is allowed to run asynchronously off of
     * the UI thread. This is a hint that informs the Animator that it is
     * OK to run the animation off-thread, however the Animator may decide
     * that it must run the animation on the UI thread anyway.
     *
     * <p>Regardless of whether or not the animation runs asynchronously, all
     * listener callbacks will be called on the UI thread.</p>
     *
     * <p>To be able to use this hint the following must be true:</p>
     * <ol>
     * <li>The animator is immutable while {@link #isStarted()} is true. Requests
     *    to change duration, delay, etc... may be ignored.</li>
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
    public void setAllowRunningAsynchronously(boolean mayRunAsync) {
        // It is up to subclasses to support this, if they can.
    }

    /**
     * Creates a {@link ConstantState} which holds changing configurations information associated
     * with the given Animator.
     * <p>
     * When {@link #newInstance()} is called, default implementation clones the Animator.
     */
    private static class AnimatorConstantState extends ConstantState<Animator> {

        final Animator mAnimator;
        @Config int mChangingConf;

        public AnimatorConstantState(Animator animator) {
            mAnimator = animator;
            // ensure a reference back to here so that constante state is not gc'ed.
            mAnimator.mConstantState = this;
            mChangingConf = mAnimator.getChangingConfigurations();
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConf;
        }

        @Override
        public Animator newInstance() {
            final Animator clone = mAnimator.clone();
            clone.mConstantState = this;
            return clone;
        }
    }
}
