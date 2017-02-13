/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.view;

import android.annotation.IntDef;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public final class InputMethodClient {
    public static final int START_INPUT_REASON_UNSPECIFIED = 0;
    public static final int START_INPUT_REASON_WINDOW_FOCUS_GAIN = 1;
    public static final int START_INPUT_REASON_WINDOW_FOCUS_GAIN_REPORT_ONLY = 2;
    public static final int START_INPUT_REASON_APP_CALLED_RESTART_INPUT_API = 3;
    public static final int START_INPUT_REASON_CHECK_FOCUS = 4;
    public static final int START_INPUT_REASON_BOUND_TO_IMMS = 5;
    public static final int START_INPUT_REASON_UNBOUND_FROM_IMMS = 6;
    public static final int START_INPUT_REASON_ACTIVATED_BY_IMMS = 7;
    public static final int START_INPUT_REASON_DEACTIVATED_BY_IMMS = 8;
    public static final int START_INPUT_REASON_SESSION_CREATED_BY_IME = 9;

    @Retention(SOURCE)
    @IntDef({START_INPUT_REASON_UNSPECIFIED, START_INPUT_REASON_WINDOW_FOCUS_GAIN,
            START_INPUT_REASON_WINDOW_FOCUS_GAIN_REPORT_ONLY,
            START_INPUT_REASON_APP_CALLED_RESTART_INPUT_API, START_INPUT_REASON_CHECK_FOCUS,
            START_INPUT_REASON_BOUND_TO_IMMS, START_INPUT_REASON_ACTIVATED_BY_IMMS,
            START_INPUT_REASON_DEACTIVATED_BY_IMMS, START_INPUT_REASON_SESSION_CREATED_BY_IME})
    public @interface StartInputReason {}

    public static String getStartInputReason(@StartInputReason final int reason) {
        switch (reason) {
            case START_INPUT_REASON_UNSPECIFIED:
                return "UNSPECIFIED";
            case START_INPUT_REASON_WINDOW_FOCUS_GAIN:
                return "WINDOW_FOCUS_GAIN";
            case START_INPUT_REASON_WINDOW_FOCUS_GAIN_REPORT_ONLY:
                return "WINDOW_FOCUS_GAIN_REPORT_ONLY";
            case START_INPUT_REASON_APP_CALLED_RESTART_INPUT_API:
                return "APP_CALLED_RESTART_INPUT_API";
            case START_INPUT_REASON_CHECK_FOCUS:
                return "CHECK_FOCUS";
            case START_INPUT_REASON_BOUND_TO_IMMS:
                return "BOUND_TO_IMMS";
            case START_INPUT_REASON_UNBOUND_FROM_IMMS:
                return "UNBOUND_FROM_IMMS";
            case START_INPUT_REASON_ACTIVATED_BY_IMMS:
                return "ACTIVATED_BY_IMMS";
            case START_INPUT_REASON_DEACTIVATED_BY_IMMS:
                return "DEACTIVATED_BY_IMMS";
            case START_INPUT_REASON_SESSION_CREATED_BY_IME:
                return "SESSION_CREATED_BY_IME";
            default:
                return "Unknown=" + reason;
        }
    }

    public static final int UNBIND_REASON_UNSPECIFIED = 0;
    public static final int UNBIND_REASON_SWITCH_CLIENT = 1;
    public static final int UNBIND_REASON_SWITCH_IME = 2;
    public static final int UNBIND_REASON_DISCONNECT_IME = 3;
    public static final int UNBIND_REASON_NO_IME = 4;
    public static final int UNBIND_REASON_SWITCH_IME_FAILED = 5;
    public static final int UNBIND_REASON_SWITCH_USER = 6;

    @Retention(SOURCE)
    @IntDef({UNBIND_REASON_UNSPECIFIED, UNBIND_REASON_SWITCH_CLIENT, UNBIND_REASON_SWITCH_IME,
            UNBIND_REASON_DISCONNECT_IME, UNBIND_REASON_NO_IME, UNBIND_REASON_SWITCH_IME_FAILED,
            UNBIND_REASON_SWITCH_USER})
    public @interface UnbindReason {}

    public static String getUnbindReason(@UnbindReason final int reason) {
        switch (reason) {
            case UNBIND_REASON_UNSPECIFIED:
                return "UNSPECIFIED";
            case UNBIND_REASON_SWITCH_CLIENT:
                return "SWITCH_CLIENT";
            case UNBIND_REASON_SWITCH_IME:
                return "SWITCH_IME";
            case UNBIND_REASON_DISCONNECT_IME:
                return "DISCONNECT_IME";
            case UNBIND_REASON_NO_IME:
                return "NO_IME";
            case UNBIND_REASON_SWITCH_IME_FAILED:
                return "SWITCH_IME_FAILED";
            case UNBIND_REASON_SWITCH_USER:
                return "SWITCH_USER";
            default:
                return "Unknown=" + reason;
        }
    }

    public static String softInputModeToString(@SoftInputModeFlags final int softInputMode) {
        final StringBuilder sb = new StringBuilder();
        final int state = softInputMode & LayoutParams.SOFT_INPUT_MASK_STATE;
        final int adjust = softInputMode & LayoutParams.SOFT_INPUT_MASK_ADJUST;
        final boolean isForwardNav =
                (softInputMode & LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0;

        switch (state) {
            case LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                sb.append("STATE_UNSPECIFIED");
                break;
            case LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                sb.append("STATE_UNCHANGED");
                break;
            case LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                sb.append("STATE_HIDDEN");
                break;
            case LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                sb.append("STATE_ALWAYS_HIDDEN");
                break;
            case LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                sb.append("STATE_VISIBLE");
                break;
            case LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                sb.append("STATE_ALWAYS_VISIBLE");
                break;
            default:
                sb.append("STATE_UNKNOWN(");
                sb.append(state);
                sb.append(")");
                break;
        }

        switch (adjust) {
            case LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED:
                sb.append("|ADJUST_UNSPECIFIED");
                break;
            case LayoutParams.SOFT_INPUT_ADJUST_RESIZE:
                sb.append("|ADJUST_RESIZE");
                break;
            case LayoutParams.SOFT_INPUT_ADJUST_PAN:
                sb.append("|ADJUST_PAN");
                break;
            case LayoutParams.SOFT_INPUT_ADJUST_NOTHING:
                sb.append("|ADJUST_NOTHING");
                break;
            default:
                sb.append("|ADJUST_UNKNOWN(");
                sb.append(adjust);
                sb.append(")");
                break;
        }

        if (isForwardNav) {
            // This is a special bit that is set by the system only during the window navigation.
            sb.append("|IS_FORWARD_NAVIGATION");
        }

        return sb.toString();
    }
}
