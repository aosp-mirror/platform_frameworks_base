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

import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyImeIsAlwaysHidden;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntil;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsShown;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests to verify the "auto show" behavior in {@code InputMethodManagerService} when the window
 * gaining the focus to start the input.
 */
@RootPermissionTest
@RunWith(Parameterized.class)
public final class AutoShowTest {

    @Rule
    public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();

    @Rule
    public ScreenCaptureRule mScreenCaptureRule =
            new ScreenCaptureRule("/sdcard/InputMethodStressTest");

    private static final int[] SOFT_INPUT_VISIBILITY_FLAGS =
            new int[] {
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED,
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE,
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            };

    private static final int[] SOFT_INPUT_ADJUST_FLAGS =
            new int[] {
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED,
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN,
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            };

    // TODO(b/240359838): add test case {@code Configuration.SCREENLAYOUT_SIZE_LARGE}.
    @Parameterized.Parameters(
            name =
                    "softInputVisibility={0}, softInputAdjustment={1},"
                            + " softInputModeIsForwardNavigation={2}")
    public static List<Object[]> softInputModeConfigs() {
        ArrayList<Object[]> params = new ArrayList<>();
        for (int softInputVisibility : SOFT_INPUT_VISIBILITY_FLAGS) {
            for (int softInputAdjust : SOFT_INPUT_ADJUST_FLAGS) {
                params.add(new Object[] {softInputVisibility, softInputAdjust, true});
                params.add(new Object[] {softInputVisibility, softInputAdjust, false});
            }
        }
        return params;
    }

    private static final String SOFT_INPUT_FLAGS = "soft_input_flags";

    private final int mSoftInputVisibility;
    private final int mSoftInputAdjustment;
    private final boolean mSoftInputIsForwardNavigation;

    public AutoShowTest(
            int softInputVisibility,
            int softInputAdjustment,
            boolean softInputIsForwardNavigation) {
        mSoftInputVisibility = softInputVisibility;
        mSoftInputAdjustment = softInputAdjustment;
        mSoftInputIsForwardNavigation = softInputIsForwardNavigation;
    }

    @Test
    public void autoShow() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        int flags = mSoftInputVisibility | mSoftInputAdjustment;
        if (mSoftInputIsForwardNavigation) {
            flags |= WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
        }

        Intent intent =
                new Intent()
                        .setAction(Intent.ACTION_MAIN)
                        .setClass(instrumentation.getContext(), TestActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(SOFT_INPUT_FLAGS, flags);
        TestActivity activity = (TestActivity) instrumentation.startActivitySync(intent);
        EditText editText = activity.getEditText();
        waitOnMainUntil("activity should gain focus", editText::hasWindowFocus);

        if (mSoftInputVisibility == WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                || mSoftInputVisibility
                        == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) {
            // IME will be auto-shown if softInputMode is set with flag:
            // SOFT_INPUT_STATE_VISIBLE or SOFT_INPUT_STATE_ALWAYS_VISIBLE
            waitOnMainUntilImeIsShown(editText);
        } else if (mSoftInputVisibility == WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                || mSoftInputVisibility
                        == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) {
            // IME will be not be shown if softInputMode is set with flag:
            // SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_STATE_ALWAYS_HIDDEN
            verifyImeIsAlwaysHidden(editText);
        } else {
            // The current system behavior will choose to show IME automatically when navigating
            // forward to an app that has no visibility state specified  (i.e.
            // SOFT_INPUT_STATE_UNSPECIFIED) with set SOFT_INPUT_ADJUST_RESIZE flag.
            if (mSoftInputVisibility == WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                    && mSoftInputAdjustment == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    && mSoftInputIsForwardNavigation) {
                waitOnMainUntilImeIsShown(editText);
            }
        }
    }

    public static class TestActivity extends Activity {
        private EditText mEditText;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            int flags = getIntent().getIntExtra(SOFT_INPUT_FLAGS, 0);
            getWindow().setSoftInputMode(flags);
            LinearLayout rootView = new LinearLayout(this);
            rootView.setOrientation(LinearLayout.VERTICAL);
            mEditText = new EditText(this);
            rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            setContentView(rootView);
            // Ensure the focused view is a text editor (View#onCheckIsTextEditor() returns true) to
            // automatically display a soft input window.
            mEditText.requestFocus();
        }

        public EditText getEditText() {
            return mEditText;
        }
    }
}
