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
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;

import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntil;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsShown;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public final class AutoShowTest {

    @Rule
    public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();

    @Test
    public void autoShow() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent()
                .setAction(Intent.ACTION_MAIN)
                .setClass(instrumentation.getContext(), TestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        TestActivity activity = (TestActivity) instrumentation.startActivitySync(intent);
        EditText editText = activity.getEditText();
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);
        waitOnMainUntilImeIsShown(editText);
    }

    public static class TestActivity extends Activity {
        private EditText mEditText;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // IME will be auto-shown if the following conditions are met:
            // 1. SoftInputMode state is SOFT_INPUT_STATE_UNSPECIFIED.
            // 2. SoftInputMode adjust is SOFT_INPUT_ADJUST_RESIZE.
            getWindow().setSoftInputMode(SOFT_INPUT_STATE_UNSPECIFIED | SOFT_INPUT_ADJUST_RESIZE);
            LinearLayout rootView = new LinearLayout(this);
            rootView.setOrientation(LinearLayout.VERTICAL);
            mEditText = new EditText(this);
            rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            setContentView(rootView);
            // 3. The focused view is a text editor (View#onCheckIsTextEditor() returns true).
            mEditText.requestFocus();
        }

        public EditText getEditText() {
            return mEditText;
        }
    }
}
