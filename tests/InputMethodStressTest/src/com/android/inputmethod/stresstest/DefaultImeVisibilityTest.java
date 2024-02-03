/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;

import static com.android.compatibility.common.util.SystemUtil.eventually;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.REQUEST_FOCUS_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.TestActivity.createIntent;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.callOnMainSync;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.requestFocusAndVerify;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyWindowAndViewFocus;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsHidden;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsShown;

import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Intent;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.support.test.uiautomator.UiDevice;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Test IME visibility by using system default IME to ensure the behavior is consistent
 * across Android platform versions.
 */
@RootPermissionTest
@RunWith(Parameterized.class)
public final class DefaultImeVisibilityTest {

    @Rule(order = 0)
    public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();
    // Use system default IME for test.
    @Rule(order = 1)
    public ImeStressTestRule mImeStressTestRule =
            new ImeStressTestRule(false /* useSimpleTestIme */);

    @Rule(order = 2)
    public ScreenCaptureRule mScreenCaptureRule =
            new ScreenCaptureRule("/sdcard/InputMethodStressTest");

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(3);

    private static final int NUM_TEST_ITERATIONS = 10;

    private final boolean mIsPortrait;

    @Parameterized.Parameters(name = "isPortrait={0}")
    public static List<Boolean> isPortraitCases() {
        // Test in both portrait and landscape mode.
        return Arrays.asList(true, false);
    }

    public DefaultImeVisibilityTest(boolean isPortrait) {
        mIsPortrait = isPortrait;
        mImeStressTestRule.setIsPortrait(isPortrait);
    }

    @Test
    public void showHideDefaultIme() {
        Intent intent =
                createIntent(
                        0x0 /* No window focus flags */,
                        SOFT_INPUT_STATE_HIDDEN | SOFT_INPUT_ADJUST_RESIZE,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        ImeStressTestUtil.TestActivity activity = ImeStressTestUtil.TestActivity.start(intent);
        EditText editText = activity.getEditText();

        UiDevice uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        eventually(
                () ->
                        assertWithMessage("Display rotation should have been updated")
                                .that(uiDevice.getDisplayRotation())
                                .isEqualTo(mIsPortrait ? 0 : 1),
                TIMEOUT);

        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            // TODO(b/291752364): Remove the explicit focus request once the issue with view focus
            //  change between fullscreen IME and actual editText is fixed.
            requestFocusAndVerify(activity);
            verifyWindowAndViewFocus(editText, true, true);
            callOnMainSync(activity::showImeWithInputMethodManager);
            waitOnMainUntilImeIsShown(editText);

            callOnMainSync(activity::hideImeWithInputMethodManager);
            waitOnMainUntilImeIsHidden(editText);
        }
    }
}
