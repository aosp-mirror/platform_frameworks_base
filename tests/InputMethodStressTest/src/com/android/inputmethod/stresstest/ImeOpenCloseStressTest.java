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
import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import static com.android.inputmethod.stresstest.ImeStressTestUtil.isImeShown;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntil;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public final class ImeOpenCloseStressTest {

    private static final int NUM_TEST_ITERATIONS = 10;

    @Rule
    public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();

    @Test
    public void test() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent()
                .setAction(Intent.ACTION_MAIN)
                .setClass(instrumentation.getContext(), TestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        TestActivity activity = (TestActivity) instrumentation.startActivitySync(intent);
        EditText editText = activity.getEditText();
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            instrumentation.runOnMainSync(activity::showIme);
            waitOnMainUntil(msgPrefix + "IME should be visible",
                    () -> !activity.isAnimating() && isImeShown(editText));
            instrumentation.runOnMainSync(activity::hideIme);
            waitOnMainUntil(msgPrefix + "IME should be hidden",
                    () -> !activity.isAnimating() && !isImeShown(editText));
            // b/b/221483132, wait until IMS and IMMS handles IMM#notifyImeHidden.
            // There is no good signal, so we just wait a second.
            SystemClock.sleep(1000);
        }
    }

    public static class TestActivity extends Activity {

        private EditText mEditText;
        private boolean mIsAnimating;

        private final WindowInsetsAnimation.Callback mWindowInsetsAnimationCallback =
                new WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                    @Override
                    public WindowInsetsAnimation.Bounds onStart(WindowInsetsAnimation animation,
                            WindowInsetsAnimation.Bounds bounds) {
                        mIsAnimating = true;
                        return super.onStart(animation, bounds);
                    }

                    @Override
                    public void onEnd(WindowInsetsAnimation animation) {
                        super.onEnd(animation);
                        mIsAnimating = false;
                    }

                    @Override
                    public WindowInsets onProgress(WindowInsets insets,
                            List<WindowInsetsAnimation> runningAnimations) {
                        return insets;
                    }
                };


        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout rootView = new LinearLayout(this);
            rootView.setOrientation(LinearLayout.VERTICAL);
            mEditText = new EditText(this);
            rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            setContentView(rootView);
            // Enable WindowInsetsAnimation.
            getWindow().setDecorFitsSystemWindows(false);
            mEditText.setWindowInsetsAnimationCallback(mWindowInsetsAnimationCallback);
        }

        public EditText getEditText() {
            return mEditText;
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

        public boolean isAnimating() {
            return mIsAnimating;
        }
    }
}
