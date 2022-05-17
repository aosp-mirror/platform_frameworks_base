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
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public final class ImeOpenCloseStressTest {

    private static final String TAG = "ImeOpenCloseStressTest";
    private static final int NUM_TEST_ITERATIONS = 10;

    @Rule
    public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();

    @Rule
    public ScreenCaptureRule mScreenCaptureRule =
            new ScreenCaptureRule("/sdcard/InputMethodStressTest");
    private Instrumentation mInstrumentation;

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Test
    public void testShowHide_waitingVisibilityChange() {
        TestActivity activity = TestActivity.start();
        EditText editText = activity.getEditText();
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            Log.i(TAG, msgPrefix + "start");
            mInstrumentation.runOnMainSync(activity::showIme);
            waitOnMainUntil(msgPrefix + "IME should be visible", () -> isImeShown(editText));
            mInstrumentation.runOnMainSync(activity::hideIme);
            waitOnMainUntil(msgPrefix + "IME should be hidden", () -> !isImeShown(editText));
        }
    }

    @Test
    public void testShowHide_waitingAnimationEnd() {
        TestActivity activity = TestActivity.start();
        activity.enableAnimationMonitoring();
        EditText editText = activity.getEditText();
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            Log.i(TAG, msgPrefix + "start");
            mInstrumentation.runOnMainSync(activity::showIme);
            waitOnMainUntil(msgPrefix + "IME should be visible",
                    () -> !activity.isAnimating() && isImeShown(editText));
            mInstrumentation.runOnMainSync(activity::hideIme);
            waitOnMainUntil(msgPrefix + "IME should be hidden",
                    () -> !activity.isAnimating() && !isImeShown(editText));
        }
    }

    @Test
    public void testShowHide_intervalAfterHide() {
        // Regression test for b/221483132
        TestActivity activity = TestActivity.start();
        EditText editText = activity.getEditText();
        // Intervals = 10, 20, 30, ..., 100, 150, 200, ...
        List<Integer> intervals = new ArrayList<>();
        for (int i = 10; i < 100; i += 10) intervals.add(i);
        for (int i = 100; i < 1000; i += 50) intervals.add(i);
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);
        for (int intervalMillis : intervals) {
            String msgPrefix = "Interval = " + intervalMillis + " ";
            Log.i(TAG, msgPrefix + " start");
            mInstrumentation.runOnMainSync(activity::hideIme);
            SystemClock.sleep(intervalMillis);
            mInstrumentation.runOnMainSync(activity::showIme);
            waitOnMainUntil(msgPrefix + "IME should be visible",
                    () -> isImeShown(editText));
        }
    }

    @Test
    public void testShowHideInSameFrame() {
        TestActivity activity = TestActivity.start();
        activity.enableAnimationMonitoring();
        EditText editText = activity.getEditText();
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);

        // hidden -> show -> hide
        mInstrumentation.runOnMainSync(() -> {
            Log.i(TAG, "Calling showIme() and hideIme()");
            activity.showIme();
            activity.hideIme();
        });
        // Wait until IMMS / IMS handles messages.
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        waitOnMainUntil("IME should be invisible after show/hide", () -> !isImeShown(editText));

        mInstrumentation.runOnMainSync(activity::showIme);
        waitOnMainUntil("IME should be visible",
                () -> !activity.isAnimating() && isImeShown(editText));
        mInstrumentation.waitForIdleSync();

        // shown -> hide -> show
        mInstrumentation.runOnMainSync(() -> {
            Log.i(TAG, "Calling hideIme() and showIme()");
            activity.hideIme();
            activity.showIme();
        });
        // Wait until IMMS / IMS handles messages.
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        waitOnMainUntil("IME should be visible after hide/show",
                () -> !activity.isAnimating() && isImeShown(editText));
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

        public static TestActivity start() {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            Intent intent = new Intent()
                    .setAction(Intent.ACTION_MAIN)
                    .setClass(instrumentation.getContext(), TestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return (TestActivity) instrumentation.startActivitySync(intent);
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout rootView = new LinearLayout(this);
            rootView.setOrientation(LinearLayout.VERTICAL);
            mEditText = new EditText(this);
            rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            setContentView(rootView);
        }

        public EditText getEditText() {
            return mEditText;
        }

        public void showIme() {
            Log.i(TAG, "TestActivity.showIme");
            mEditText.requestFocus();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            imm.showSoftInput(mEditText, 0);
        }

        public void hideIme() {
            Log.i(TAG, "TestActivity.hideIme");
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            imm.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
        }

        public void enableAnimationMonitoring() {
            // Enable WindowInsetsAnimation.
            // Note that this has a side effect of disabling InsetsAnimationThreadControlRunner.
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                getWindow().setDecorFitsSystemWindows(false);
                mEditText.setWindowInsetsAnimationCallback(mWindowInsetsAnimationCallback);
            });
        }

        public boolean isAnimating() {
            return mIsAnimating;
        }
    }
}
