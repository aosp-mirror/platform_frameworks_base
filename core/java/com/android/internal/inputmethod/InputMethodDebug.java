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

import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import java.util.StringJoiner;

/**
 * Provides useful methods for debugging.
 */
public final class InputMethodDebug {
    /**
     * Not intended to be instantiated.
     */
    private InputMethodDebug() {
    }

    /**
     * Converts {@link StartInputReason} to {@link String} for debug logging.
     *
     * @param reason integer constant for {@link StartInputReason}.
     * @return {@link String} message corresponds for the given {@code reason}.
     */
    public static String startInputReasonToString(@StartInputReason int reason) {
        switch (reason) {
            case StartInputReason.UNSPECIFIED:
                return "UNSPECIFIED";
            case StartInputReason.WINDOW_FOCUS_GAIN:
                return "WINDOW_FOCUS_GAIN";
            case StartInputReason.WINDOW_FOCUS_GAIN_REPORT_ONLY:
                return "WINDOW_FOCUS_GAIN_REPORT_ONLY";
            case StartInputReason.APP_CALLED_RESTART_INPUT_API:
                return "APP_CALLED_RESTART_INPUT_API";
            case StartInputReason.CHECK_FOCUS:
                return "CHECK_FOCUS";
            case StartInputReason.BOUND_TO_IMMS:
                return "BOUND_TO_IMMS";
            case StartInputReason.UNBOUND_FROM_IMMS:
                return "UNBOUND_FROM_IMMS";
            case StartInputReason.ACTIVATED_BY_IMMS:
                return "ACTIVATED_BY_IMMS";
            case StartInputReason.DEACTIVATED_BY_IMMS:
                return "DEACTIVATED_BY_IMMS";
            case StartInputReason.SESSION_CREATED_BY_IME:
                return "SESSION_CREATED_BY_IME";
            default:
                return "Unknown=" + reason;
        }
    }

    /**
     * Converts {@link UnbindReason} to {@link String} for debug logging.
     *
     * @param reason integer constant for {@link UnbindReason}.
     * @return {@link String} message corresponds for the given {@code reason}.
     */
    public static String unbindReasonToString(@UnbindReason int reason) {
        switch (reason) {
            case UnbindReason.UNSPECIFIED:
                return "UNSPECIFIED";
            case UnbindReason.SWITCH_CLIENT:
                return "SWITCH_CLIENT";
            case UnbindReason.SWITCH_IME:
                return "SWITCH_IME";
            case UnbindReason.DISCONNECT_IME:
                return "DISCONNECT_IME";
            case UnbindReason.NO_IME:
                return "NO_IME";
            case UnbindReason.SWITCH_IME_FAILED:
                return "SWITCH_IME_FAILED";
            case UnbindReason.SWITCH_USER:
                return "SWITCH_USER";
            default:
                return "Unknown=" + reason;
        }
    }

    /**
     * Converts {@link SoftInputModeFlags} to {@link String} for debug logging.
     *
     * @param softInputMode integer constant for {@link SoftInputModeFlags}.
     * @return {@link String} message corresponds for the given {@code softInputMode}.
     */
    public static String softInputModeToString(@SoftInputModeFlags int softInputMode) {
        final StringJoiner joiner = new StringJoiner("|");
        final int state = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
        final int adjust = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
        final boolean isForwardNav =
                (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0;

        switch (state) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                joiner.add("STATE_UNSPECIFIED");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                joiner.add("STATE_UNCHANGED");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                joiner.add("STATE_HIDDEN");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                joiner.add("STATE_ALWAYS_HIDDEN");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                joiner.add("STATE_VISIBLE");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                joiner.add("STATE_ALWAYS_VISIBLE");
                break;
            default:
                joiner.add("STATE_UNKNOWN(" + state + ")");
                break;
        }

        switch (adjust) {
            case WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED:
                joiner.add("ADJUST_UNSPECIFIED");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE:
                joiner.add("ADJUST_RESIZE");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN:
                joiner.add("ADJUST_PAN");
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING:
                joiner.add("ADJUST_NOTHING");
                break;
            default:
                joiner.add("ADJUST_UNKNOWN(" + adjust + ")");
                break;
        }

        if (isForwardNav) {
            // This is a special bit that is set by the system only during the window navigation.
            joiner.add("IS_FORWARD_NAVIGATION");
        }

        return joiner.setEmptyValue("(none)").toString();
    }

    /**
     * Converts {@link StartInputFlags} to {@link String} for debug logging.
     *
     * @param startInputFlags integer constant for {@link StartInputFlags}.
     * @return {@link String} message corresponds for the given {@code startInputFlags}.
     */
    public static String startInputFlagsToString(@StartInputFlags int startInputFlags) {
        final StringJoiner joiner = new StringJoiner("|");
        if ((startInputFlags & StartInputFlags.VIEW_HAS_FOCUS) != 0) {
            joiner.add("VIEW_HAS_FOCUS");
        }
        if ((startInputFlags & StartInputFlags.IS_TEXT_EDITOR) != 0) {
            joiner.add("IS_TEXT_EDITOR");
        }
        if ((startInputFlags & StartInputFlags.FIRST_WINDOW_FOCUS_GAIN) != 0) {
            joiner.add("FIRST_WINDOW_FOCUS_GAIN");
        }
        if ((startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0) {
            joiner.add("INITIAL_CONNECTION");
        }

        return joiner.setEmptyValue("(none)").toString();
    }
}
