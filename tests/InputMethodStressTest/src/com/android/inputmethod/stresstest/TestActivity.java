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

package com.android.inputmethod.stresstest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

public class TestActivity extends Activity {

    private EditText mEditText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        mEditText = new EditText(this);
        rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        setContentView(rootView);
    }

    public boolean hasWindowFocus() {
        return mEditText.hasWindowFocus();
    }

    public boolean isImeShown() {
        WindowInsets insets = mEditText.getRootWindowInsets();
        return insets.isVisible(WindowInsets.Type.ime());
    }

    public void showIme() {
        mEditText.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.showSoftInput(mEditText, 0);
    }

    public void hideIme() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
    }
}
