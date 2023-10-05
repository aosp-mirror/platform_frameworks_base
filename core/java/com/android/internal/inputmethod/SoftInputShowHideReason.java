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
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ImeProtoEnums;
import android.view.inputmethod.InputMethodManager;

import java.lang.annotation.Retention;

/**
 * Describes the reason why Soft input window visible / hidden.
 */
@Retention(SOURCE)
@IntDef(value = {
        SoftInputShowHideReason.NOT_SET,
        SoftInputShowHideReason.SHOW_SOFT_INPUT,
        SoftInputShowHideReason.ATTACH_NEW_INPUT,
        SoftInputShowHideReason.SHOW_SOFT_INPUT_FROM_IME,
        SoftInputShowHideReason.HIDE_SOFT_INPUT,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_IME,
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
        SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API,
        SoftInputShowHideReason.HIDE_DISPLAY_IME_POLICY_HIDE,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_EXTRACT_INPUT_CHANGED,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_IMM_DEPRECATION,
        SoftInputShowHideReason.HIDE_WINDOW_GAINED_FOCUS_WITHOUT_EDITOR,
        SoftInputShowHideReason.SHOW_IME_SCREENSHOT_FROM_IMMS,
        SoftInputShowHideReason.REMOVE_IME_SCREENSHOT_FROM_IMMS,
        SoftInputShowHideReason.HIDE_WHEN_INPUT_TARGET_INVISIBLE,
        SoftInputShowHideReason.HIDE_CLOSE_CURRENT_SESSION,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_VIEW,
        SoftInputShowHideReason.SHOW_SOFT_INPUT_LEGACY_DIRECT,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_LEGACY_DIRECT,
        SoftInputShowHideReason.SHOW_WINDOW_LEGACY_DIRECT,
        SoftInputShowHideReason.HIDE_WINDOW_LEGACY_DIRECT,
        SoftInputShowHideReason.RESET_NEW_CONFIGURATION,
        SoftInputShowHideReason.UPDATE_CANDIDATES_VIEW_VISIBILITY,
        SoftInputShowHideReason.CONTROLS_CHANGED,
        SoftInputShowHideReason.DISPLAY_CONFIGURATION_CHANGED,
        SoftInputShowHideReason.DISPLAY_INSETS_CHANGED,
        SoftInputShowHideReason.DISPLAY_CONTROLS_CHANGED,
        SoftInputShowHideReason.UNBIND_CURRENT_METHOD,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_ON_ANIMATION_STATE_CHANGED,
        SoftInputShowHideReason.HIDE_SOFT_INPUT_REQUEST_HIDE_WITH_CONTROL,
        SoftInputShowHideReason.SHOW_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT,
        SoftInputShowHideReason.SHOW_SOFT_INPUT_IMM_DEPRECATION,
})
public @interface SoftInputShowHideReason {
    /** Default, undefined reason. */
    int NOT_SET = ImeProtoEnums.REASON_NOT_SET;

    /** Show soft input by {@link android.view.inputmethod.InputMethodManager#showSoftInput}. */
    int SHOW_SOFT_INPUT = ImeProtoEnums.REASON_SHOW_SOFT_INPUT;

    /** Show soft input when {@code InputMethodManagerService#attachNewInputLocked} called. */
    int ATTACH_NEW_INPUT = ImeProtoEnums.REASON_ATTACH_NEW_INPUT;

    /** Show soft input by {@code InputMethodManagerService#showMySoftInput}. This is triggered when
     *  the IME process try to show the keyboard.
     *
     * @see android.inputmethodservice.InputMethodService#requestShowSelf(int)
     */
    int SHOW_SOFT_INPUT_FROM_IME = ImeProtoEnums.REASON_SHOW_SOFT_INPUT_FROM_IME;

    /**
     * Hide soft input by
     * {@link android.view.inputmethod.InputMethodManager#hideSoftInputFromWindow}.
     */
    int HIDE_SOFT_INPUT = ImeProtoEnums.REASON_HIDE_SOFT_INPUT;

    /**
     * Hide soft input by
     * {@link android.inputmethodservice.InputMethodService#requestHideSelf(int)}.
     */
    int HIDE_SOFT_INPUT_FROM_IME = ImeProtoEnums.REASON_HIDE_SOFT_INPUT_FROM_IME;

    /**
     * Show soft input when navigated forward to the window (with
     * {@link LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION}) which the focused view is text
     * editor and system will auto-show the IME when the window can resize or running on a large
     * screen.
     */
    int SHOW_AUTO_EDITOR_FORWARD_NAV = ImeProtoEnums.REASON_SHOW_AUTO_EDITOR_FORWARD_NAV;

    /**
     * Show soft input when navigated forward to the window with
     * {@link LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION} and
     * {@link LayoutParams#SOFT_INPUT_STATE_VISIBLE}.
     */
    int SHOW_STATE_VISIBLE_FORWARD_NAV = ImeProtoEnums.REASON_SHOW_STATE_VISIBLE_FORWARD_NAV;

    /**
     * Show soft input when the window with {@link LayoutParams#SOFT_INPUT_STATE_ALWAYS_VISIBLE}.
     */
    int SHOW_STATE_ALWAYS_VISIBLE = ImeProtoEnums.REASON_SHOW_STATE_ALWAYS_VISIBLE;

    /**
     * Show soft input during {@code InputMethodManagerService} receive changes from
     * {@code SettingsProvider}.
     */
    int SHOW_SETTINGS_ON_CHANGE = ImeProtoEnums.REASON_SHOW_SETTINGS_ON_CHANGE;

    /** Hide soft input during switching user. */
    int HIDE_SWITCH_USER = ImeProtoEnums.REASON_HIDE_SWITCH_USER;

    /** Hide soft input when the user is invalid. */
    int HIDE_INVALID_USER = ImeProtoEnums.REASON_HIDE_INVALID_USER;

    /**
     * Hide soft input when the window with {@link LayoutParams#SOFT_INPUT_STATE_UNSPECIFIED} which
     * the focused view is not text editor.
     */
    int HIDE_UNSPECIFIED_WINDOW = ImeProtoEnums.REASON_HIDE_UNSPECIFIED_WINDOW;

    /**
     * Hide soft input when navigated forward to the window with
     * {@link LayoutParams#SOFT_INPUT_IS_FORWARD_NAVIGATION} and
     * {@link LayoutParams#SOFT_INPUT_STATE_HIDDEN}.
     */
    int HIDE_STATE_HIDDEN_FORWARD_NAV = ImeProtoEnums.REASON_HIDE_STATE_HIDDEN_FORWARD_NAV;

    /**
     * Hide soft input when the window with {@link LayoutParams#SOFT_INPUT_STATE_ALWAYS_HIDDEN}.
     */
    int HIDE_ALWAYS_HIDDEN_STATE = ImeProtoEnums.REASON_HIDE_ALWAYS_HIDDEN_STATE;

    /** Hide soft input when "adb shell ime <command>" called. */
    int HIDE_RESET_SHELL_COMMAND = ImeProtoEnums.REASON_HIDE_RESET_SHELL_COMMAND;

    /**
     * Hide soft input during {@code InputMethodManagerService} receive changes from
     * {@code SettingsProvider}.
     */
    int HIDE_SETTINGS_ON_CHANGE = ImeProtoEnums.REASON_HIDE_SETTINGS_ON_CHANGE;

    /**
     * Hide soft input from {@link com.android.server.policy.PhoneWindowManager} when setting
     * {@link com.android.internal.R.integer#config_shortPressOnPowerBehavior} in config.xml as
     * dismiss IME.
     */
    int HIDE_POWER_BUTTON_GO_HOME = ImeProtoEnums.REASON_HIDE_POWER_BUTTON_GO_HOME;

    /** Hide soft input when attaching docked stack. */
    int HIDE_DOCKED_STACK_ATTACHED = ImeProtoEnums.REASON_HIDE_DOCKED_STACK_ATTACHED;

    /**
     * Hide soft input when {@link com.android.server.wm.RecentsAnimationController} starts
     * intercept touch from app window.
     */
    int HIDE_RECENTS_ANIMATION = ImeProtoEnums.REASON_HIDE_RECENTS_ANIMATION;

    /**
     * Hide soft input when {@link com.android.wm.shell.bubbles.BubbleController} is expanding,
     * switching, or collapsing Bubbles.
     */
    int HIDE_BUBBLES = ImeProtoEnums.REASON_HIDE_BUBBLES;

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
    int HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR =
            ImeProtoEnums.REASON_HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR;

    /**
     * Hide soft input when a {@link com.android.internal.inputmethod.IInputMethodClient} is
     * removed.
     */
    int HIDE_REMOVE_CLIENT = ImeProtoEnums.REASON_HIDE_REMOVE_CLIENT;

    /**
     * Show soft input when the system invoking
     * {@link com.android.server.wm.WindowManagerInternal#shouldRestoreImeVisibility}.
     */
    int SHOW_RESTORE_IME_VISIBILITY = ImeProtoEnums.REASON_SHOW_RESTORE_IME_VISIBILITY;

    /**
     * Show soft input by
     * {@link android.view.inputmethod.InputMethodManager#toggleSoftInput(int, int)};
     */
    int SHOW_TOGGLE_SOFT_INPUT = ImeProtoEnums.REASON_SHOW_TOGGLE_SOFT_INPUT;

    /**
     * Hide soft input by
     * {@link android.view.inputmethod.InputMethodManager#toggleSoftInput(int, int)};
     */
    int HIDE_TOGGLE_SOFT_INPUT = ImeProtoEnums.REASON_HIDE_TOGGLE_SOFT_INPUT;

    /**
     * Show soft input by
     * {@link android.view.InsetsController#show(int)};
     */
    int SHOW_SOFT_INPUT_BY_INSETS_API = ImeProtoEnums.REASON_SHOW_SOFT_INPUT_BY_INSETS_API;

    /**
     * Hide soft input if Ime policy has been set to {@link WindowManager#DISPLAY_IME_POLICY_HIDE}.
     * See also {@code InputMethodManagerService#mImeHiddenByDisplayPolicy}.
     */
    int HIDE_DISPLAY_IME_POLICY_HIDE = ImeProtoEnums.REASON_HIDE_DISPLAY_IME_POLICY_HIDE;

    /**
     * Hide soft input by {@link android.view.InsetsController#hide(int)}.
     */
    int HIDE_SOFT_INPUT_BY_INSETS_API = ImeProtoEnums.REASON_HIDE_SOFT_INPUT_BY_INSETS_API;

    /**
     * Hide soft input by {@link android.inputmethodservice.InputMethodService#handleBack(boolean)}.
     */
    int HIDE_SOFT_INPUT_BY_BACK_KEY = ImeProtoEnums.REASON_HIDE_SOFT_INPUT_BY_BACK_KEY;

    /**
     * Hide soft input by
     * {@link android.inputmethodservice.InputMethodService#onToggleSoftInput(int, int)}.
     */
    int HIDE_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT =
            ImeProtoEnums.REASON_HIDE_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT;

    /**
     * Hide soft input by
     * {@link android.inputmethodservice.InputMethodService#onExtractingInputChanged(EditorInfo)})}.
     */
    int HIDE_SOFT_INPUT_EXTRACT_INPUT_CHANGED =
            ImeProtoEnums.REASON_HIDE_SOFT_INPUT_EXTRACT_INPUT_CHANGED;

    /**
     * Hide soft input by the deprecated
     * {@link InputMethodManager#hideSoftInputFromInputMethod(IBinder, int)}.
     */
    int HIDE_SOFT_INPUT_IMM_DEPRECATION = ImeProtoEnums.REASON_HIDE_SOFT_INPUT_IMM_DEPRECATION;

    /**
     * Hide soft input when the window gained focus without an editor from the IME shown window.
     */
    int HIDE_WINDOW_GAINED_FOCUS_WITHOUT_EDITOR =
            ImeProtoEnums.REASON_HIDE_WINDOW_GAINED_FOCUS_WITHOUT_EDITOR;

    /**
     * Shows ime screenshot by {@link com.android.server.inputmethod.InputMethodManagerService}.
     */
    int SHOW_IME_SCREENSHOT_FROM_IMMS = ImeProtoEnums.REASON_SHOW_IME_SCREENSHOT_FROM_IMMS;

    /**
     * Removes ime screenshot by {@link com.android.server.inputmethod.InputMethodManagerService}.
     */
    int REMOVE_IME_SCREENSHOT_FROM_IMMS = ImeProtoEnums.REASON_REMOVE_IME_SCREENSHOT_FROM_IMMS;

    /**
     * Hide soft input when the input target being removed or being obscured by an non-IME
     * focusable overlay window.
     */
    int HIDE_WHEN_INPUT_TARGET_INVISIBLE = ImeProtoEnums.REASON_HIDE_WHEN_INPUT_TARGET_INVISIBLE;

    /**
     * Hide soft input when {@link InputMethodManager#closeCurrentInput()} gets called.
     */
    int HIDE_CLOSE_CURRENT_SESSION = ImeProtoEnums.REASON_HIDE_CLOSE_CURRENT_SESSION;

    /**
     * Hide soft input when {@link InputMethodManager#hideSoftInputFromView(View, int)} gets called.
     */
    int HIDE_SOFT_INPUT_FROM_VIEW = ImeProtoEnums.REASON_HIDE_SOFT_INPUT_FROM_VIEW;

    /**
     * Show soft input by legacy (discouraged) call to
     * {@link android.inputmethodservice.InputMethodService.InputMethodImpl#showSoftInput}.
     */
    int SHOW_SOFT_INPUT_LEGACY_DIRECT = ImeProtoEnums.REASON_SHOW_SOFT_INPUT_LEGACY_DIRECT;

    /**
     * Hide soft input by legacy (discouraged) call to
     * {@link android.inputmethodservice.InputMethodService.InputMethodImpl#hideSoftInput}.
     */
    int HIDE_SOFT_INPUT_LEGACY_DIRECT = ImeProtoEnums.REASON_HIDE_SOFT_INPUT_LEGACY_DIRECT;

    /**
     * Show soft input by legacy (discouraged) call to
     * {@link android.inputmethodservice.InputMethodService#showWindow}.
     */
    int SHOW_WINDOW_LEGACY_DIRECT = ImeProtoEnums.REASON_SHOW_WINDOW_LEGACY_DIRECT;

    /**
     * Hide soft input by legacy (discouraged) call to
     * {@link android.inputmethodservice.InputMethodService#hideWindow}.
     */
    int HIDE_WINDOW_LEGACY_DIRECT = ImeProtoEnums.REASON_HIDE_WINDOW_LEGACY_DIRECT;

    /**
     * Show / Hide soft input by
     * {@link android.inputmethodservice.InputMethodService#resetStateForNewConfiguration}.
     */
    int RESET_NEW_CONFIGURATION = ImeProtoEnums.REASON_RESET_NEW_CONFIGURATION;

    /**
     * Show / Hide soft input by
     * {@link android.inputmethodservice.InputMethodService#updateCandidatesVisibility}.
     */
    int UPDATE_CANDIDATES_VIEW_VISIBILITY = ImeProtoEnums.REASON_UPDATE_CANDIDATES_VIEW_VISIBILITY;

    /**
     * Show / Hide soft input by {@link android.view.InsetsController#onControlsChanged}.
     */
    int CONTROLS_CHANGED = ImeProtoEnums.REASON_CONTROLS_CHANGED;

    /**
     * Show soft input by
     * {@link com.android.wm.shell.common.DisplayImeController#onDisplayConfigurationChanged}.
     */
    int DISPLAY_CONFIGURATION_CHANGED = ImeProtoEnums.REASON_DISPLAY_CONFIGURATION_CHANGED;

    /**
     * Show soft input by
     * {@link com.android.wm.shell.common.DisplayImeController.PerDisplay#insetsChanged}.
     */
    int DISPLAY_INSETS_CHANGED = ImeProtoEnums.REASON_DISPLAY_INSETS_CHANGED;

    /**
     * Show / Hide soft input by
     * {@link com.android.wm.shell.common.DisplayImeController.PerDisplay#insetsControlChanged}.
     */
    int DISPLAY_CONTROLS_CHANGED = ImeProtoEnums.REASON_DISPLAY_CONTROLS_CHANGED;

    /** Hide soft input by
     * {@link com.android.server.inputmethod.InputMethodManagerService#onUnbindCurrentMethodByReset}.
     */
    int UNBIND_CURRENT_METHOD = ImeProtoEnums.REASON_UNBIND_CURRENT_METHOD;

    /** Hide soft input by {@link android.view.ImeInsetsSourceConsumer#onAnimationStateChanged}. */
    int HIDE_SOFT_INPUT_ON_ANIMATION_STATE_CHANGED =
            ImeProtoEnums.REASON_HIDE_SOFT_INPUT_ON_ANIMATION_STATE_CHANGED;

    /** Hide soft input when we already have a {@link android.view.InsetsSourceControl} by
     * {@link android.view.ImeInsetsSourceConsumer#requestHide}.
     */
    int HIDE_SOFT_INPUT_REQUEST_HIDE_WITH_CONTROL =
            ImeProtoEnums.REASON_HIDE_SOFT_INPUT_REQUEST_HIDE_WITH_CONTROL;

    /**
     * Show soft input by
     * {@link android.inputmethodservice.InputMethodService#onToggleSoftInput(int, int)}.
     */
    int SHOW_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT =
            ImeProtoEnums.REASON_SHOW_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT;

    /**
     * Show soft input by the deprecated
     * {@link InputMethodManager#showSoftInputFromInputMethod(IBinder, int)}.
     */
    int SHOW_SOFT_INPUT_IMM_DEPRECATION = ImeProtoEnums.REASON_SHOW_SOFT_INPUT_IMM_DEPRECATION;
}
