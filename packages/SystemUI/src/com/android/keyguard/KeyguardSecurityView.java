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
package com.android.keyguard;

import android.content.res.ColorStateList;
import android.view.MotionEvent;

public interface KeyguardSecurityView {
    int SCREEN_ON = 1;
    int VIEW_REVEALED = 2;

    int PROMPT_REASON_NONE = 0;

    /**
     * Strong auth is required because the device has just booted.
     */
    int PROMPT_REASON_RESTART = 1;

    /**
     * Strong auth is required because the user hasn't used strong auth since a while.
     */
    int PROMPT_REASON_TIMEOUT = 2;

    /**
     * Strong auth is required because a device admin requested it.
     */
    int PROMPT_REASON_DEVICE_ADMIN = 3;

    /**
     * Some auth is required because the user force locked.
     */
    int PROMPT_REASON_USER_REQUEST = 4;

    /**
     * Some auth is required because too many wrong credentials led to a lockout.
     */
    int PROMPT_REASON_AFTER_LOCKOUT = 5;

    /***
     * Strong auth is require to prepare for an unattended update.
     */
    int PROMPT_REASON_PREPARE_FOR_UPDATE = 6;

    /**
     * Primary auth is required because the user uses weak/convenience biometrics and hasn't used
     * primary auth since a while
     */
    int PROMPT_REASON_NON_STRONG_BIOMETRIC_TIMEOUT = 7;

    /**
     * Some auth is required because the trustagent expired either from timeout or manually by the
     * user
     */
    int PROMPT_REASON_TRUSTAGENT_EXPIRED = 8;

    /**
     * Some auth is required because adaptive auth has determined risk
     */
    int PROMPT_REASON_ADAPTIVE_AUTH_REQUEST = 9;

    /**
     * Strong auth is required because the device has just booted because of an automatic
     * mainline update.
     */
    int PROMPT_REASON_RESTART_FOR_MAINLINE_UPDATE = 16;

    /**
     * Reset the view and prepare to take input. This should do things like clearing the
     * password or pattern and clear error messages.
     */
    void reset();

    /**
     * Emulate activity life cycle within the view. When called, the view should clean up
     * and prepare to be removed.
     */
    void onPause();

    /**
     * Emulate activity life cycle within this view.  When called, the view should prepare itself
     * to be shown.
     * @param reason the root cause of the event.
     */
    void onResume(int reason);

    /**
     * Inquire whether this view requires IME (keyboard) interaction.
     *
     * @return true if IME interaction is required.
     */
    boolean needsInput();

    /**
     * Show a string explaining why the security view needs to be solved.
     *
     * @param reason a flag indicating which string should be shown, see {@link #PROMPT_REASON_NONE}
     *               and {@link #PROMPT_REASON_RESTART}
     */
    void showPromptReason(int reason);

    /**
     * Show a message on the security view with a specified color
     *
     * @param message the message to show
     * @param colorState the color to use
     */
    void showMessage(CharSequence message, ColorStateList colorState, boolean animated);

    /**
     * Starts the animation which should run when the security view appears.
     */
    void startAppearAnimation();

    /**
     * Starts the animation which should run when the security view disappears.
     *
     * @param finishRunnable the runnable to be run when the animation ended
     * @return true if an animation started and {@code finishRunnable} will be run, false if no
     *         animation started and {@code finishRunnable} will not be run
     */
    boolean startDisappearAnimation(Runnable finishRunnable);

    /**
     * The localized name of the security view, provided to accessibility. This may be the content
     * description, but content descriptions have other implications, so the title is kept separate.
     *
     * @return The View's title.
     */
    CharSequence getTitle();

    /**
     * If the parent should not be allowed to intercept touch events.
     * @param event A touch event.
     * @return {@code true} if touch should be passed forward.
     * @see android.view.ViewGroup#requestDisallowInterceptTouchEvent(boolean)
     */
    default boolean disallowInterceptTouch(MotionEvent event) {
        return false;
    }

    /**
     * When bouncer was visible but is being dragged down or dismissed.
     */
    default void onStartingToHide() {};
}
