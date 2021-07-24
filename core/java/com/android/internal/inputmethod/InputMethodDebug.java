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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
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
            case StartInputReason.WINDOW_FOCUS_GAIN_REPORT_WITH_CONNECTION:
                return "WINDOW_FOCUS_GAIN_REPORT_WITH_CONNECTION";
            case StartInputReason.WINDOW_FOCUS_GAIN_REPORT_WITHOUT_CONNECTION:
                return "WINDOW_FOCUS_GAIN_REPORT_WITHOUT_CONNECTION";
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
        if ((startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0) {
            joiner.add("INITIAL_CONNECTION");
        }

        return joiner.setEmptyValue("(none)").toString();
    }


    /**
     * Converts {@link SoftInputShowHideReason} to {@link String} for history dump.
     */
    public static String softInputDisplayReasonToString(@SoftInputShowHideReason int reason) {
        switch (reason) {
            case SoftInputShowHideReason.SHOW_SOFT_INPUT:
                return "SHOW_SOFT_INPUT";
            case SoftInputShowHideReason.ATTACH_NEW_INPUT:
                return "ATTACH_NEW_INPUT";
            case SoftInputShowHideReason.SHOW_MY_SOFT_INPUT:
                return "SHOW_MY_SOFT_INPUT";
            case SoftInputShowHideReason.HIDE_SOFT_INPUT:
                return "HIDE_SOFT_INPUT";
            case SoftInputShowHideReason.HIDE_MY_SOFT_INPUT:
                return "HIDE_MY_SOFT_INPUT";
            case SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV:
                return "SHOW_AUTO_EDITOR_FORWARD_NAV";
            case SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV:
                return "SHOW_STATE_VISIBLE_FORWARD_NAV";
            case SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE:
                return "SHOW_STATE_ALWAYS_VISIBLE";
            case SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE:
                return "SHOW_SETTINGS_ON_CHANGE";
            case SoftInputShowHideReason.HIDE_SWITCH_USER:
                return "HIDE_SWITCH_USER";
            case SoftInputShowHideReason.HIDE_INVALID_USER:
                return "HIDE_INVALID_USER";
            case SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW:
                return "HIDE_UNSPECIFIED_WINDOW";
            case SoftInputShowHideReason.HIDE_STATE_HIDDEN_FORWARD_NAV:
                return "HIDE_STATE_HIDDEN_FORWARD_NAV";
            case SoftInputShowHideReason.HIDE_ALWAYS_HIDDEN_STATE:
                return "HIDE_ALWAYS_HIDDEN_STATE";
            case SoftInputShowHideReason.HIDE_RESET_SHELL_COMMAND:
                return "HIDE_RESET_SHELL_COMMAND";
            case SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE:
                return "HIDE_SETTINGS_ON_CHANGE";
            case SoftInputShowHideReason.HIDE_POWER_BUTTON_GO_HOME:
                return "HIDE_POWER_BUTTON_GO_HOME";
            case SoftInputShowHideReason.HIDE_DOCKED_STACK_ATTACHED:
                return "HIDE_DOCKED_STACK_ATTACHED";
            case SoftInputShowHideReason.HIDE_RECENTS_ANIMATION:
                return "HIDE_RECENTS_ANIMATION";
            case SoftInputShowHideReason.HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR:
                return "HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR";
            case SoftInputShowHideReason.HIDE_REMOVE_CLIENT:
                return "HIDE_REMOVE_CLIENT";
            case SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY:
                return "SHOW_RESTORE_IME_VISIBILITY";
            case SoftInputShowHideReason.SHOW_TOGGLE_SOFT_INPUT:
                return "SHOW_TOGGLE_SOFT_INPUT";
            case SoftInputShowHideReason.HIDE_TOGGLE_SOFT_INPUT:
                return "HIDE_TOGGLE_SOFT_INPUT";
            case SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API:
                return "SHOW_SOFT_INPUT_BY_INSETS_API";
            default:
                return "Unknown=" + reason;
        }
    }

    /**
     * Converts {@link InputConnectionCommandType} to human readable {@link String}.
     */
    public static String inputConnectionCommandTypeToString(@InputConnectionCommandType int type) {
        switch (type) {
            case InputConnectionCommandType.BEGIN_BATCH_EDIT:
                return "beginBatchEdit";
            case InputConnectionCommandType.CLEAR_META_KEY_STATES:
                return "clearMetaKeyStates";
            case InputConnectionCommandType.COMMIT_COMPLETION:
                return "commitCompletion";
            case InputConnectionCommandType.COMMIT_CONTENT:
                return "commitContent";
            case InputConnectionCommandType.COMMIT_CORRECTION:
                return "commitCorrection";
            case InputConnectionCommandType.COMMIT_TEXT:
                return "commitText";
            case InputConnectionCommandType.DELETE_SURROUNDING_TEXT:
                return "deleteSurroundingText";
            case InputConnectionCommandType.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS:
                return "deleteSurroundingTextInCodePoints";
            case InputConnectionCommandType.END_BATCH_EDIT:
                return "endBatchEdit";
            case InputConnectionCommandType.FINISH_COMPOSING_TEXT:
                return "finishComposingText";
            case InputConnectionCommandType.GET_CURSOR_CAPS_MODE:
                return "getCursorCapsMode";
            case InputConnectionCommandType.GET_EXTRACTED_TEXT:
                return "getExtractedText";
            case InputConnectionCommandType.GET_SELECTED_TEXT:
                return "getSelectedText";
            case InputConnectionCommandType.GET_SURROUNDING_TEXT:
                return "getSurroundingText";
            case InputConnectionCommandType.GET_TEXT_AFTER_CURSOR:
                return "getTextAfterCursor";
            case InputConnectionCommandType.GET_TEXT_BEFORE_CURSOR:
                return "getTextBeforeCursor";
            case InputConnectionCommandType.PERFORM_CONTEXT_MENU_ACTION:
                return "performContextMenuAction";
            case InputConnectionCommandType.PERFORM_EDITOR_ACTION:
                return "performEditorAction";
            case InputConnectionCommandType.PERFORM_SPELL_CHECK:
                return "performSpellCheck";
            case InputConnectionCommandType.REQUEST_CURSOR_UPDATES:
                return "requestCursorUpdates";
            case InputConnectionCommandType.SEND_KEY_EVENT:
                return "sendKeyEvent";
            case InputConnectionCommandType.SET_COMPOSING_REGION:
                return "setComposingRegion";
            case InputConnectionCommandType.SET_COMPOSING_TEXT:
                return "setComposingText";
            case InputConnectionCommandType.SET_IME_CONSUMES_INPUT:
                return "setImeConsumesInput";
            case InputConnectionCommandType.SET_SELECTION:
                return "setSelection";
            default:
                return "Unknown=" + type;
        }
    }

    /**
     * Converts {@link InputConnectionCommand} to human readable {@link String}.
     */
    @NonNull
    public static String dumpInputConnectionCommand(@Nullable InputConnectionCommand command) {
        if (command == null) {
            return "null";
        }
        switch (command.mCommandType) {
            case InputConnectionCommandType.BEGIN_BATCH_EDIT:
                return "beginBatchEdit()";
            case InputConnectionCommandType.CLEAR_META_KEY_STATES:
                return "clearMetaKeyStates(" + command.mIntArg0 + ")";
            case InputConnectionCommandType.COMMIT_COMPLETION:
                return "commitCompletion(" + command.mParcelable + ")";
            case InputConnectionCommandType.COMMIT_CONTENT:
                return "commitContent(" + command.mParcelable + ", " + command.mFlags + ", "
                        + command.mBundle + ")";
            case InputConnectionCommandType.COMMIT_CORRECTION:
                return "commitCorrection(" + command.mParcelable + ")";
            case InputConnectionCommandType.COMMIT_TEXT:
                return "commitText(" + command.mCharSequence + ", " + command.mIntArg0 + ")";
            case InputConnectionCommandType.DELETE_SURROUNDING_TEXT:
                return "deleteSurroundingText(" + command.mIntArg0 + ", " + command.mIntArg1 + ")";
            case InputConnectionCommandType.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS:
                return "deleteSurroundingTextInCodePoints(" + command.mIntArg0 + ", "
                        + command.mIntArg1 + ")";
            case InputConnectionCommandType.END_BATCH_EDIT:
                return "endBatchEdit()";
            case InputConnectionCommandType.FINISH_COMPOSING_TEXT:
                return "finishComposingText()";
            case InputConnectionCommandType.GET_CURSOR_CAPS_MODE:
                return "getCursorCapsMode(" + command.mIntArg0 + ")";
            case InputConnectionCommandType.GET_EXTRACTED_TEXT:
                return "getExtractedText(" + command.mParcelable + ", " + command.mFlags + ")";
            case InputConnectionCommandType.GET_SELECTED_TEXT:
                return "getSelectedText(" + command.mFlags + ")";
            case InputConnectionCommandType.GET_SURROUNDING_TEXT:
                return "getSurroundingText(" + command.mIntArg0 + ", " + command.mIntArg1 + ", "
                        + command.mFlags + ")";
            case InputConnectionCommandType.GET_TEXT_AFTER_CURSOR:
                return "getTextAfterCursor(" + command.mIntArg0 + ", " + command.mFlags + ")";
            case InputConnectionCommandType.GET_TEXT_BEFORE_CURSOR:
                return "getTextBeforeCursor(" + command.mIntArg0 + ", " + command.mFlags + ")";
            case InputConnectionCommandType.PERFORM_CONTEXT_MENU_ACTION:
                return "performContextMenuAction(" + command.mIntArg0 + ")";
            case InputConnectionCommandType.PERFORM_EDITOR_ACTION:
                return "performEditorAction(" + command.mIntArg0 + ")";
            case InputConnectionCommandType.PERFORM_SPELL_CHECK:
                return "performSpellCheck()";
            case InputConnectionCommandType.REQUEST_CURSOR_UPDATES:
                return "requestCursorUpdates(" + command.mIntArg0 + ")";
            case InputConnectionCommandType.SEND_KEY_EVENT:
                return "sendKeyEvent(" + command.mParcelable + ")";
            case InputConnectionCommandType.SET_COMPOSING_REGION:
                return "setComposingRegion(" + command.mIntArg0 + ", " + command.mIntArg1 + ")";
            case InputConnectionCommandType.SET_COMPOSING_TEXT:
                return "setComposingText(" + command.mCharSequence + ", " + command.mIntArg0 + ")";
            case InputConnectionCommandType.SET_IME_CONSUMES_INPUT:
                return "setImeConsumesInput(" + (command.mIntArg0 != 0) + ")";
            case InputConnectionCommandType.SET_SELECTION:
                return "setSelection(" + command.mIntArg0 + ", " + command.mIntArg1 + ")";
            default:
                return "unknown(type=" + command.mCommandType + ")";
        }
    }

    /**
     * Return a fixed size string of the object.
     * TODO(b/151575861): Take & return with StringBuilder to make more memory efficient.
     */
    @NonNull
    @AnyThread
    public static String objToString(Object obj) {
        if (obj == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(64);
        sb.setLength(0);
        sb.append(obj.getClass().getName());
        sb.append("@");
        sb.append(Integer.toHexString(obj.hashCode()));
        return sb.toString();
    }
}
