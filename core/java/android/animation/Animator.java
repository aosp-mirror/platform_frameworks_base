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

import java.util.ArrayList;

/**
 * This is the superclass for classes which provide basic support for animations which can be
 * started, ended, and have <code>AnimatorListeners</code> added to them.
 */
public abstract class Animator implements Cloneable {

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
     * Returns whether this Animator has been started and not yet ended. This state is a superset
     * of the state of {@link #isRunning()}, because an Animator with a nonzero
     * {@link #getStartDelay() startDelay} will return true for {@link #isStarted()} during the
     * delay phase, whereas {@link #isRunning()} will return true only after the delay phase
     * is complete.
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

    @Override
    public Animator clone() {
        try {
            final Animator anim = (Animator) super.clone();
            if (mListeners != null) {
                ArrayList<AnimatorListener> oldListeners = mListeners;
                anim.mListeners = new ArrayList<AnimatorListener>();
                int numListeners = oldListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    anim.mListeners.add(oldListeners.get(i));
                }
            }
            if (mPauseListeners != null) {
                ArrayList<AnimatorPauseListener> oldListeners = mPauseListeners;
                anim.mPauseListeners = new ArrayList<AnimatorPauseListener>();
                int numListeners = oldListeners.size();
                for (int i = 0; i < numListeners; ++i) {
                    anim.mPauseListeners.add(oldListeners.get(i));
                }
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
     *
     * @param target The object being animated
     */
    public void setTarget(Object target) {
    }

    /**
     * <p>An animation listener receives notifications from an animation.
     * Notifications indicate animation related events, such as the end or the
     * repetition of the animation.</p>
     */
    public static interface AnimatorListener {
        /**
         * <p>Notifies the start of the animation.</p>
         *
         * @param animation The started animation.
         */
        void onAnimationStart(Animator animation);

        /**
         * <p>Notifies the end of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which reached its end.
         */
        void onAnimationEnd(Animator animation);

        /**
         * <p>Notifies the cancellation of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which was canceled.
         */
        void onAnimationCancel(Animator animation);

        /**
         * <p>Notifies the repetition of the animation.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationRepeat(Animator animation);
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
        void onAnimationPause(Animator animation);

        /**
         * <p>Notifies that the animation was resumed, after being
         * previously paused.</p>
         *
         * @param animation The animation being resumed.
         * @see #resume()
         */
        void onAnimationResume(Animator animation);
    }
}
