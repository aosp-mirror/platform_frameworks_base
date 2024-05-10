/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.apps.inputmethod.simpleime;

import android.view.KeyEvent;

import java.util.HashMap;

/** Holder of key codes and their name. */
public final class KeyCodeConstants {
    private KeyCodeConstants() {}

    static final HashMap<String, Integer> KEY_NAME_TO_CODE_MAP = new HashMap<>();

    static {
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_A", KeyEvent.KEYCODE_A);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_B", KeyEvent.KEYCODE_B);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_C", KeyEvent.KEYCODE_C);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_D", KeyEvent.KEYCODE_D);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_E", KeyEvent.KEYCODE_E);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_F", KeyEvent.KEYCODE_F);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_G", KeyEvent.KEYCODE_G);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_H", KeyEvent.KEYCODE_H);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_I", KeyEvent.KEYCODE_I);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_J", KeyEvent.KEYCODE_J);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_K", KeyEvent.KEYCODE_K);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_L", KeyEvent.KEYCODE_L);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_M", KeyEvent.KEYCODE_M);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_N", KeyEvent.KEYCODE_N);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_O", KeyEvent.KEYCODE_O);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_P", KeyEvent.KEYCODE_P);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_Q", KeyEvent.KEYCODE_Q);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_R", KeyEvent.KEYCODE_R);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_S", KeyEvent.KEYCODE_S);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_T", KeyEvent.KEYCODE_T);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_U", KeyEvent.KEYCODE_U);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_V", KeyEvent.KEYCODE_V);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_W", KeyEvent.KEYCODE_W);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_X", KeyEvent.KEYCODE_X);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_Y", KeyEvent.KEYCODE_Y);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_Z", KeyEvent.KEYCODE_Z);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_DEL", KeyEvent.KEYCODE_DEL);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_SPACE", KeyEvent.KEYCODE_SPACE);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_ENTER", KeyEvent.KEYCODE_ENTER);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_COMMA", KeyEvent.KEYCODE_COMMA);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_PERIOD", KeyEvent.KEYCODE_PERIOD);
        KEY_NAME_TO_CODE_MAP.put("KEYCODE_TAB", KeyEvent.KEYCODE_TAB);
    }

    public static boolean isAlphaKeyCode(int keyCode) {
        return keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z;
    }
}
