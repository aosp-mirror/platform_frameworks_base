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

import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import com.android.apps.inputmethod.simpleime.ims.InputMethodServiceWrapper;

/** The {@link InputMethodService} implementation for SimpeTestIme app. */
public class SimpleInputMethodService extends InputMethodServiceWrapper {
    private static final String TAG = "SimpleIMS";

    private FrameLayout mInputView;

    @Override
    public View onCreateInputView() {
        Log.i(TAG, "onCreateInputView()");
        mInputView = (FrameLayout) LayoutInflater.from(this).inflate(R.layout.input_view, null);
        return mInputView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        mInputView.removeAllViews();
        SimpleKeyboard keyboard = new SimpleKeyboard(this, R.layout.qwerty_10_9_9);
        mInputView.addView(keyboard.inflateKeyboardView(LayoutInflater.from(this), mInputView));
    }

    void handle(String data, int keyboardState) {
        InputConnection inputConnection = getCurrentInputConnection();
        Integer keyCode = KeyCodeConstants.KEY_NAME_TO_CODE_MAP.get(data);
        Log.v(TAG, "keyCode: " + keyCode);
        if (keyCode != null) {
            inputConnection.sendKeyEvent(
                    new KeyEvent(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            KeyEvent.ACTION_DOWN,
                            keyCode,
                            0,
                            KeyCodeConstants.isAlphaKeyCode(keyCode) ? keyboardState : 0));
        }
    }
}
