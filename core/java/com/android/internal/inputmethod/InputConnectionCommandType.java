/*
 * Copyright (C) 2021 The Android Open Source Project
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

@Retention(SOURCE)
@IntDef(value = {
        InputConnectionCommandType.BEGIN_BATCH_EDIT,
        InputConnectionCommandType.CLEAR_META_KEY_STATES,
        InputConnectionCommandType.COMMIT_COMPLETION,
        InputConnectionCommandType.COMMIT_CONTENT,
        InputConnectionCommandType.COMMIT_CORRECTION,
        InputConnectionCommandType.COMMIT_TEXT,
        InputConnectionCommandType.DELETE_SURROUNDING_TEXT,
        InputConnectionCommandType.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS,
        InputConnectionCommandType.END_BATCH_EDIT,
        InputConnectionCommandType.FINISH_COMPOSING_TEXT,
        InputConnectionCommandType.GET_CURSOR_CAPS_MODE,
        InputConnectionCommandType.GET_EXTRACTED_TEXT,
        InputConnectionCommandType.GET_SELECTED_TEXT,
        InputConnectionCommandType.GET_SURROUNDING_TEXT,
        InputConnectionCommandType.GET_TEXT_AFTER_CURSOR,
        InputConnectionCommandType.GET_TEXT_BEFORE_CURSOR,
        InputConnectionCommandType.PERFORM_CONTEXT_MENU_ACTION,
        InputConnectionCommandType.PERFORM_EDITOR_ACTION,
        InputConnectionCommandType.PERFORM_SPELL_CHECK,
        InputConnectionCommandType.REQUEST_CURSOR_UPDATES,
        InputConnectionCommandType.SEND_KEY_EVENT,
        InputConnectionCommandType.SET_COMPOSING_REGION,
        InputConnectionCommandType.SET_COMPOSING_TEXT,
        InputConnectionCommandType.SET_IME_CONSUMES_INPUT,
        InputConnectionCommandType.SET_SELECTION,
})
public @interface InputConnectionCommandType {
    int FIRST_COMMAND = 1;

    int BEGIN_BATCH_EDIT = FIRST_COMMAND;
    int CLEAR_META_KEY_STATES = 2;
    int COMMIT_COMPLETION = 3;
    int COMMIT_CONTENT = 4;
    int COMMIT_CORRECTION = 5;
    int COMMIT_TEXT = 6;
    int DELETE_SURROUNDING_TEXT = 7;
    int DELETE_SURROUNDING_TEXT_IN_CODE_POINTS = 8;
    int END_BATCH_EDIT = 9;
    int FINISH_COMPOSING_TEXT = 10;
    int GET_CURSOR_CAPS_MODE = 11;
    int GET_EXTRACTED_TEXT = 12;
    int GET_SELECTED_TEXT = 13;
    int GET_SURROUNDING_TEXT = 14;
    int GET_TEXT_AFTER_CURSOR = 15;
    int GET_TEXT_BEFORE_CURSOR = 16;
    int PERFORM_CONTEXT_MENU_ACTION = 17;
    int PERFORM_EDITOR_ACTION = 18;
    int PERFORM_SPELL_CHECK = 19;
    int PERFORM_PRIVATE_COMMAND = 20;
    int REQUEST_CURSOR_UPDATES = 21;
    int SEND_KEY_EVENT = 22;
    int SET_COMPOSING_REGION = 23;
    int SET_COMPOSING_TEXT = 24;
    int SET_IME_CONSUMES_INPUT = 25;
    int SET_SELECTION = 26;

    int LAST_COMMAND = SET_SELECTION;
}
