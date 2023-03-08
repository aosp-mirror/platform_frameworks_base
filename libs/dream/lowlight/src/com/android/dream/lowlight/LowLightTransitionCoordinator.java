/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.dream.lowlight;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class that allows listening and running animations before entering or exiting low light.
 */
@Singleton
public class LowLightTransitionCoordinator {
    /**
     * Listener that is notified before low light entry.
     */
    public interface LowLightEnterListener {
        /**
         * Callback that is notified before the device enters low light.
         *
         * @return an optional animator that will be waited upon before entering low light.
         */
        Animator onBeforeEnterLowLight();
    }

    /**
     * Listener that is notified before low light exit.
     */
    public interface LowLightExitListener {
        /**
         * Callback that is notified before the device exits low light.
         *
         * @return an optional animator that will be waited upon before exiting low light.
         */
        Animator onBeforeExitLowLight();
    }

    private LowLightEnterListener mLowLightEnterListener;
    private LowLightExitListener mLowLightExitListener;

    @Inject
    public LowLightTransitionCoordinator() {
    }

    /**
     * Sets the listener for the low light enter event.
     *
     * Only one listener can be set at a time. This method will overwrite any previously set
     * listener. Null can be used to unset the listener.
     */
    public void setLowLightEnterListener(@Nullable LowLightEnterListener lowLightEnterListener) {
        mLowLightEnterListener = lowLightEnterListener;
    }

    /**
     * Sets the listener for the low light exit event.
     *
     * Only one listener can be set at a time. This method will overwrite any previously set
     * listener. Null can be used to unset the listener.
     */
    public void setLowLightExitListener(@Nullable LowLightExitListener lowLightExitListener) {
        mLowLightExitListener = lowLightExitListener;
    }

    /**
     * Notifies listeners that the device is about to enter or exit low light.
     *
     * @param entering true if listeners should be notified before entering low light, false if this
     *                 is notifying before exiting.
     * @param callback callback that will be run after listeners complete.
     */
    void notifyBeforeLowLightTransition(boolean entering, Runnable callback) {
        Animator animator = null;

        if (entering && mLowLightEnterListener != null) {
            animator = mLowLightEnterListener.onBeforeEnterLowLight();
        } else if (!entering && mLowLightExitListener != null) {
            animator = mLowLightExitListener.onBeforeExitLowLight();
        }

        // If the listener returned an animator to indicate it was running an animation, run the
        // callback after the animation completes, otherwise call the callback directly.
        if (animator != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animator) {
                    callback.run();
                }
            });
        } else {
            callback.run();
        }
    }
}
