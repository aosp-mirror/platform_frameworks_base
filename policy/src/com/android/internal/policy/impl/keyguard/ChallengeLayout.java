/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard;

/**
 * Interface implemented by ViewGroup-derived layouts that implement
 * special logic for presenting security challenges to the user.
 */
public interface ChallengeLayout {
    /**
     * @return true if the security challenge area of this layout is currently visible
     */
    boolean isChallengeShowing();

    /**
     * @return true if the challenge area significantly overlaps other content
     */
    boolean isChallengeOverlapping();

    /**
     * Show or hide the challenge layout.
     *
     * If you want to show the challenge layout in bouncer mode where applicable,
     * use {@link #showBouncer()} instead.
     *
     * @param b true to show, false to hide
     */
    void showChallenge(boolean b);

    /**
     * Show the bouncer challenge. This may block access to other child views.
     */
    void showBouncer();

    /**
     * Hide the bouncer challenge if it is currently showing.
     * This may restore previously blocked access to other child views.
     */
    void hideBouncer();

    /**
     * Returns true if the challenge is currently in bouncer mode,
     * potentially blocking access to other child views.
     */
    boolean isBouncing();

    /**
     * Returns the duration of the bounce animation.
     */
    int getBouncerAnimationDuration();

    /**
     * Set a listener that will respond to changes in bouncer state.
     *
     * @param listener listener to register
     */
    void setOnBouncerStateChangedListener(OnBouncerStateChangedListener listener);

    /**
     * Listener interface that reports changes in bouncer state.
     * The bouncer is
     */
    public interface OnBouncerStateChangedListener {
        /**
         * Called when the bouncer state changes.
         * The bouncer is activated when the user must pass a security challenge
         * to proceed with the requested action.
         *
         * <p>This differs from simply showing or hiding the security challenge
         * as the bouncer will prevent interaction with other elements of the UI.
         * If the user attempts to escape from the bouncer, it will be dismissed,
         * this method will be called with false as the parameter, and the action
         * should be canceled. If the security component reports a successful
         * authentication and the containing code calls hideBouncer() as a result,
         * this method will also be called with a false parameter. It is up to the
         * caller of hideBouncer to be ready for this.</p>
         *
         * @param bouncerActive true if the bouncer is now active,
         *                      false if the bouncer was dismissed.
         */
        public void onBouncerStateChanged(boolean bouncerActive);
    }
}
