/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.view.WindowManager.LayoutParams;

import java.lang.annotation.Retention;

/**
 * Describes the reason why Soft input window visible / hidden.
 */
@Retention(SOURCE)
@IntDef(value = {
        SoftInputShowHideReason.SHOW_SOFT_INPUT,
        SoftInputShowHideReason.ATTACH_NEW_INPUT,
        SoftInputShowHideReason.SHOW_MY_SOFT_INPUT,
        SoftInputShowHideReason.HIDE_SOFT_INPUT,
        SoftInputShowHideReason.HIDE_MY_SOFT_INPUT,
        SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV,
        SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV,
        SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE,
        SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE,
        SoftInputShowHideReason.HIDE_SWITCH_USER,
        SoftInputShowHideReason.HIDE_INVALID_USER,
        SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW,
        SoftInputShowHideReason.HIDE_STATE_HIDDEN_FORWARD_NAV,
        SoftInputShowHideReason.HIDE_ALWAYS_HIDDEN_STATE,
        SoftInputShowHideReason.HIDE_RESET_SHELL_COMMAND,
        SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE,
        SoftInputShowHideReason.HIDE_POWER_BUTTON_GO_HOME,
        SoftInputShowHideReason.HIDE_DOCKED_STACK_ATTACHED,
        SoftInputShowHideReason.HIDE_RECENTS_ANIMATION,
        SoftInputShowHideReason.HIDE_BUBBLES,
        SoftInputShowHideReason.HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR,
        SoftInputShowHideReason.HIDE_REMOVE_CLIENT,
        SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY,
        SoftInputShowHideReason.SHOW_TOGGLE_SOFT_INPUT,
        SoftInputShowHideReason.HIDE_TOGGLE_SOFT_INPUT,
        SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API})
public @interface SoftInputShowHideReason {
    /** Show soft input by {@link android.view.inputmethod.InputMethodManager#showSoftInput}. */
    int SHOW_SOFT_INPUT = 0;

    /** Show soft input when {@code InputMethodManagerService#attachNewInputLocked} called. */
    int ATTACH_NEW_INPUT = 1;

    /** Show soft input by {@code InputMethodManagerService#showMySoftInput}. */
    int SHOW_MY_SOFT_INPUT = 2;

    /**
     * Hide soft input by
     * {@link android.view.inputmethod.InputMethodManager#hideSoftInputFromWindow}.
     */
    int HIDE_SOFT_INPUT = 3;

    /** Hide soft input by {@code InputMethodManagerService#hideMySoftInput}. */
    int HIDE_MY_SOFT_INPUT = 4;

    /**
     * Show soft input when navigated forward to the window (with
     * {@link LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION}} which the focused view is text
     * editor and system will auto-show the IME when the window can resize or running on a large
     * screen.
     */
    int SHOW_AUTO_EDITOR_FORWARD_NAV = 5;

    /**
     * Show soft input when navigated forward to the window with
     * {@link LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION} and
     * {@link LayoutParams#SOFT_INPUT_STATE_VISIBLE}.
     */
    int SHOW_STATE_VISIBLE_FORWARD_NAV = 6;

    /**
     * Show soft input when the window with {@link LayoutParams#SOFT_INPUT_STATE_ALWAYS_VISIBLE}.
     */
    int SHOW_STATE_ALWAYS_VISIBLE = 7;

    /**
     * Show soft input during {@code InputMethodManagerService} receive changes from
     * {@code SettingsProvider}.
     */
    int SHOW_SETTINGS_ON_CHANGE = 8;

    /** Hide soft input during switching user. */
    int HIDE_SWITCH_USER = 9;

    /** Hide soft input when the user is invalid. */
    int HIDE_INVALID_USER = 10;

    /**
     * Hide soft input when the window with {@link LayoutParams#SOFT_INPUT_STATE_UNSPECIFIED} which
     * the focused view is not text editor.
     */
    int HIDE_UNSPECIFIED_WINDOW = 11;

    /**
     * Hide soft input when navigated forward to the window with
     * {@link LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION} and
     * {@link LayoutParams#SOFT_INPUT_STATE_HIDDEN}.
     */
    int HIDE_STATE_HIDDEN_FORWARD_NAV = 12;

    /**
     * Hide soft input when the window with {@link LayoutParams#SOFT_INPUT_STATE_ALWAYS_HIDDEN}.
     */
    int HIDE_ALWAYS_HIDDEN_STATE = 13;

    /** Hide soft input when "adb shell ime <command>" called. */
    int HIDE_RESET_SHELL_COMMAND = 14;

    /**
     * Hide soft input during {@code InputMethodManagerService} receive changes from
     * {@code SettingsProvider}.
     */
    int HIDE_SETTINGS_ON_CHANGE = 15;

    /**
     * Hide soft input from {@link com.android.server.policy.PhoneWindowManager} when setting
     * {@link com.android.internal.R.integer#config_shortPressOnPowerBehavior} in config.xml as
     * dismiss IME.
     */
    int HIDE_POWER_BUTTON_GO_HOME = 16;

    /** Hide soft input when attaching docked stack. */
    int HIDE_DOCKED_STACK_ATTACHED = 17;

    /**
     * Hide soft input when {@link com.android.server.wm.RecentsAnimationController} starts
     * intercept touch from app window.
     */
    int HIDE_RECENTS_ANIMATION = 18;

    /**
     * Hide soft input when {@link com.android.wm.shell.bubbles.BubbleController} is expanding,
     * switching, or collapsing Bubbles.
     */
    int HIDE_BUBBLES = 19;

    /**
     * Hide soft input when focusing the same window (e.g. screen turned-off and turn-on) which no
     * valid focused editor.
     *
     * Note: From Android R, the window focus change callback is processed by InputDispatcher,
     * some focus behavior changes (e.g. There are an activity with a dialog window, after
     * screen turned-off and turned-on, before Android R the window focus sequence would be
     * the activity first and then the dialog focused, however, in R the focus sequence would be
     * only the dialog focused as it's the latest window with input focus) makes we need to hide
     * soft-input when the same window focused again to align with the same behavior prior to R.
     */
    int HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR = 20;

    /**
     * Hide soft input when a {@link com.android.internal.view.IInputMethodClient} is removed.
     */
    int HIDE_REMOVE_CLIENT = 21;

    /**
     * Show soft input when the system invoking
     * {@link com.android.server.wm.WindowManagerInternal#shouldRestoreImeVisibility}.
     */
    int SHOW_RESTORE_IME_VISIBILITY = 22;

    /**
     * Show soft input by
     * {@link android.view.inputmethod.InputMethodManager#toggleSoftInput(int, int)};
     */
    int SHOW_TOGGLE_SOFT_INPUT = 23;

    /**
     * Hide soft input by
     * {@link android.view.inputmethod.InputMethodManager#toggleSoftInput(int, int)};
     */
    int HIDE_TOGGLE_SOFT_INPUT = 24;

    /**
     * Show soft input by
     * {@link android.view.InsetsController#show(int)};
     */
    int SHOW_SOFT_INPUT_BY_INSETS_API = 25;
}
