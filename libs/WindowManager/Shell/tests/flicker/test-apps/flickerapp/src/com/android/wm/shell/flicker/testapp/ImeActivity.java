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

package com.android.wm.shell.flicker.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

public class ImeActivity extends Activity {
    private static final String ACTION_OPEN_IME =
            "com.android.wm.shell.flicker.testapp.action.OPEN_IME";
    private static final String ACTION_CLOSE_IME =
            "com.android.wm.shell.flicker.testapp.action.CLOSE_IME";

    private InputMethodManager mImm;
    private View mEditText;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(p);
        setContentView(R.layout.activity_ime);

        mEditText = findViewById(R.id.plain_text_input);
        mImm = getSystemService(InputMethodManager.class);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        final String action = intent.getAction();
        if (ACTION_OPEN_IME.equals(action)) {
            mEditText.requestFocus();
            mImm.showSoftInput(mEditText, InputMethodManager.SHOW_FORCED);
        } else if (ACTION_CLOSE_IME.equals(action)) {
            mImm.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
            mEditText.clearFocus();
        }
    }
}
