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
 * Describes the reason why {@link com.android.server.inputmethod.InputMethodManagerService} is
 * calling {@link com.android.internal.view.IInputMethodClient#onUnbindMethod}.
 */
@Retention(SOURCE)
@IntDef(value = {
        UnbindReason.UNSPECIFIED,
        UnbindReason.SWITCH_CLIENT,
        UnbindReason.SWITCH_IME,
        UnbindReason.DISCONNECT_IME,
        UnbindReason.NO_IME,
        UnbindReason.SWITCH_IME_FAILED,
        UnbindReason.SWITCH_USER})
public @interface UnbindReason {
    /**
     * Reason is not specified.
     */
    int UNSPECIFIED = 0;
    /**
     * When a new IME client becomes active, the previous IME client will unbound from the current
     * IME.
     */
    int SWITCH_CLIENT = 1;
    /**
     * Before a new IME becomes active, the current IME client be unbound from the previous IME.
     */
    int SWITCH_IME = 2;
    /**
     * When the current IME is disconnected, the current IME client will be unbound.
     */
    int DISCONNECT_IME = 3;
    /**
     * When the system loses the last enabled IME, the current IME client will be unbound.
     */
    int NO_IME = 4;
    /**
     * When the system failed to switch to another IME, the current IME client will be unbound.
     */
    int SWITCH_IME_FAILED = 5;
    /**
     * When a new user becomes foreground, the previous IME client will be unbound from the previous
     * user's active IME.
     */
    int SWITCH_USER = 6;
}
