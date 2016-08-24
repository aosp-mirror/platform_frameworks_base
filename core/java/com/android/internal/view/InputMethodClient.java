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

    @Retention(SOURCE)
    @IntDef({START_INPUT_REASON_UNSPECIFIED, START_INPUT_REASON_WINDOW_FOCUS_GAIN,
            START_INPUT_REASON_WINDOW_FOCUS_GAIN_REPORT_ONLY,
            START_INPUT_REASON_APP_CALLED_RESTART_INPUT_API, START_INPUT_REASON_CHECK_FOCUS,
            START_INPUT_REASON_BOUND_TO_IMMS, START_INPUT_REASON_ACTIVATED_BY_IMMS,
            START_INPUT_REASON_DEACTIVATED_BY_IMMS})
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
    public static final int UNBIND_REASON_RESET_IME = 6;

    @Retention(SOURCE)
    @IntDef({UNBIND_REASON_UNSPECIFIED, UNBIND_REASON_SWITCH_CLIENT, UNBIND_REASON_SWITCH_IME,
            UNBIND_REASON_DISCONNECT_IME, UNBIND_REASON_NO_IME, UNBIND_REASON_SWITCH_IME_FAILED,
            UNBIND_REASON_RESET_IME})
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
            case UNBIND_REASON_RESET_IME:
                return "RESET_IME";
            default:
                return "Unknown=" + reason;
        }
    }
}
