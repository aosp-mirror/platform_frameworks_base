/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.inputmethod;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;

import java.lang.annotation.Retention;

/**
 * Describes the reason why {@link android.view.inputmethod.InputMethodManager} is calling
 * {@link com.android.internal.view.IInputMethodManager#startInputOrWindowGainedFocus}.
 */
@Retention(SOURCE)
@IntDef(value = {
        StartInputReason.UNSPECIFIED,
        StartInputReason.WINDOW_FOCUS_GAIN,
        StartInputReason.WINDOW_FOCUS_GAIN_REPORT_WITH_CONNECTION,
        StartInputReason.WINDOW_FOCUS_GAIN_REPORT_WITHOUT_CONNECTION,
        StartInputReason.APP_CALLED_RESTART_INPUT_API,
        StartInputReason.CHECK_FOCUS,
        StartInputReason.BOUND_TO_IMMS,
        StartInputReason.UNBOUND_FROM_IMMS,
        StartInputReason.ACTIVATED_BY_IMMS,
        StartInputReason.DEACTIVATED_BY_IMMS,
        StartInputReason.SESSION_CREATED_BY_IME})
public @interface StartInputReason {
    /**
     * Reason is not specified.
     */
    int UNSPECIFIED = 0;
    /**
     * {@link android.view.Window} gained focus and it made the focused {@link android.view.View}
     * to (re)start a new connection.
     */
    int WINDOW_FOCUS_GAIN = 1;
    /**
     * {@link android.view.Window} gained focus and the focused view is same as current served
     * view and its input connection remains. {@link android.view.inputmethod.InputMethodManager}
     * just reports this window focus change event to sync IME input target for system.
     */
    int WINDOW_FOCUS_GAIN_REPORT_WITH_CONNECTION = 2;
    /**
     * {@link android.view.Window} gained focus but there is no {@link android.view.View} that is
     * eligible to have IME focus. {@link android.view.inputmethod.InputMethodManager} just reports
     * this window focus change event for logging.
     */
    int WINDOW_FOCUS_GAIN_REPORT_WITHOUT_CONNECTION = 3;
    /**
     * {@link android.view.inputmethod.InputMethodManager#restartInput(android.view.View)} is
     * either explicitly called by the application or indirectly called by some Framework class
     * (e.g. {@link android.widget.EditText}).
     */
    int APP_CALLED_RESTART_INPUT_API = 4;
    /**
     * {@link android.view.View} requested a new connection because of view focus change.
     */
    int CHECK_FOCUS = 5;
    /**
     * {@link android.view.inputmethod.InputMethodManager} is responding to
     * {@link com.android.internal.view.IInputMethodClient#onBindMethod}.
     */
    int BOUND_TO_IMMS = 6;
    /**
     * {@link android.view.inputmethod.InputMethodManager} is responding to
     * {@link com.android.internal.view.IInputMethodClient#onUnbindMethod}.
     */
    int UNBOUND_FROM_IMMS = 7;
    /**
     * {@link android.view.inputmethod.InputMethodManager} is responding to
     * {@link com.android.internal.view.IInputMethodClient#setActive}.
     */
    int ACTIVATED_BY_IMMS = 8;
    /**
     * {@link android.view.inputmethod.InputMethodManager} is responding to
     * {@link com.android.internal.view.IInputMethodClient#setActive}.
     */
    int DEACTIVATED_BY_IMMS = 9;
    /**
     * {@link com.android.server.inputmethod.InputMethodManagerService} is responding to
     * {@link com.android.internal.view.IInputSessionCallback#sessionCreated}.
     */
    int SESSION_CREATED_BY_IME = 10;
}
