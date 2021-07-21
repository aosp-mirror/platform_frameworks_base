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
 * Describes additional info in
 * {@link com.android.internal.view.IInputMethodManager#startInputOrWindowGainedFocus}.
 */
@Retention(SOURCE)
@IntDef(flag = true, value = {
        StartInputFlags.VIEW_HAS_FOCUS,
        StartInputFlags.IS_TEXT_EDITOR,
        StartInputFlags.INITIAL_CONNECTION})
public @interface StartInputFlags {
    /**
     * There is a focused view in the focused window.
     */
    int VIEW_HAS_FOCUS = 1;

    /**
     * The focused view is a text editor.
     */
    int IS_TEXT_EDITOR = 2;

    /**
     * An internal concept to distinguish "start" and "restart". This concept doesn't look well
     * documented hence we probably need to revisit this though.
     */
    int INITIAL_CONNECTION = 4;

    /**
     * The start input happens when the window gained focus to call
     * {@code android.view.inputmethod.InputMethodManager#startInputAsyncOnWindowFocusGain}.
     */
    int WINDOW_GAINED_FOCUS = 8;
}
