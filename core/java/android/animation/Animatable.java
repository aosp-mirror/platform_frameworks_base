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
 * started, ended, and have <code>AnimatableListeners</code> added to them.
 */
public abstract class Animatable {


    /**
     * The set of listeners to be sent events through the life of an animation.
     */
    ArrayList<AnimatableListener> mListeners = null;

    /**
     * Starts this animation. If the animation has a nonzero startDelay, the animation will start
     * running after that delay elapses. Note that the animation does not start synchronously with
     * this call, because all animation events are posted to a central timing loop so that animation
     * times are all synchronized on a single timing pulse on the UI thread. So the animation will
     * start the next time that event handler processes events.
     */
    public void start() {
    }

    /**
     * Cancels the animation. Unlike {@link #end()}, <code>cancel()</code> causes the animation to
     * stop in its tracks, sending an {@link AnimatableListener#onAnimationCancel(Animatable)} to
     * its listeners, followed by an {@link AnimatableListener#onAnimationEnd(Animatable)} message.
     */
    public void cancel() {
    }

    /**
     * Ends the animation. This causes the animation to assign the end value of the property being
     * animated, then calling the {@link AnimatableListener#onAnimationEnd(Animatable)} method on
     * its listeners.
     */
    public void end() {
    }

    /**
     * Adds a listener to the set of listeners that are sent events through the life of an
     * animation, such as start, repeat, and end.
     *
     * @param listener the listener to be added to the current set of listeners for this animation.
     */
    public void addListener(AnimatableListener listener) {
        if (mListeners == null) {
            mListeners = new ArrayList<AnimatableListener>();
        }
        mListeners.add(listener);
    }

    /**
     * Removes a listener from the set listening to this animation.
     *
     * @param listener the listener to be removed from the current set of listeners for this
     *                 animation.
     */
    public void removeListener(AnimatableListener listener) {
        if (mListeners == null) {
            return;
        }
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            mListeners = null;
        }
    }

    /**
     * Gets the set of {@link AnimatableListener} objects that are currently
     * listening for events on this <code>Animatable</code> object.
     *
     * @return ArrayList<AnimatableListener> The set of listeners.
     */
    public ArrayList<AnimatableListener> getListeners() {
        return mListeners;
    }

    /**
     * Removes all listeners from this object. This is equivalent to calling
     * <code>getListeners()</code> followed by calling <code>clear()</code> on the
     * returned list of listeners.
     */
    public void removeAllListeners() {
        if (mListeners != null) {
            mListeners.clear();
            mListeners = null;
        }
    }

    /**
     * <p>An animation listener receives notifications from an animation.
     * Notifications indicate animation related events, such as the end or the
     * repetition of the animation.</p>
     */
    public static interface AnimatableListener {
        /**
         * <p>Notifies the start of the animation.</p>
         *
         * @param animation The started animation.
         */
        void onAnimationStart(Animatable animation);

        /**
         * <p>Notifies the end of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which reached its end.
         */
        void onAnimationEnd(Animatable animation);

        /**
         * <p>Notifies the cancellation of the animation. This callback is not invoked
         * for animations with repeat count set to INFINITE.</p>
         *
         * @param animation The animation which was canceled.
         */
        void onAnimationCancel(Animatable animation);

        /**
         * <p>Notifies the repetition of the animation.</p>
         *
         * @param animation The animation which was repeated.
         */
        void onAnimationRepeat(Animatable animation);
    }
}
