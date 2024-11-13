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

import static com.android.inputmethod.stresstest.ImeStressTestUtil.REQUEST_FOCUS_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.TestActivity;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.TestActivity.createIntent;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.UNFOCUSABLE_VIEW;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.callOnMainSync;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.getWindowAndSoftInputFlagParameters;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.hasUnfocusableWindowFlags;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.requestFocusAndVerify;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyImeAlwaysHiddenWithWindowFlagSet;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyImeIsAlwaysHidden;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyWindowAndViewFocus;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsShown;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collections;
import java.util.List;

/**
 * Tests to verify the "auto-show" behavior in {@code InputMethodManagerService} when the window
 * gaining the focus to start the input.
 */
@RootPermissionTest
@RunWith(Parameterized.class)
public final class AutoShowTest {

    @Rule(order = 0) public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();
    @Rule(order = 1) public ImeStressTestRule mImeStressTestRule =
        new ImeStressTestRule(true /* useSimpleTestIme */);
    @Rule(order = 2) public ScreenCaptureRule mScreenCaptureRule =
            new ScreenCaptureRule("/sdcard/InputMethodStressTest");
    @Parameterized.Parameters(
            name = "windowFocusFlags={0}, softInputVisibility={1}, softInputAdjustment={2}")
    public static List<Object[]> windowAndSoftInputFlagParameters() {
        return getWindowAndSoftInputFlagParameters();
    }

    private final int mSoftInputFlags;
    private final int mWindowFocusFlags;
    private final Instrumentation mInstrumentation;
    private final boolean mIsLargeScreen;

    public AutoShowTest(int windowFocusFlags, int softInputVisibility, int softInputAdjustment) {
        mSoftInputFlags = softInputVisibility | softInputAdjustment;
        mWindowFocusFlags = windowFocusFlags;
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mIsLargeScreen = mInstrumentation.getContext().getResources()
                .getConfiguration().isLayoutSizeAtLeast(Configuration.SCREENLAYOUT_SIZE_LARGE);
    }

    /**
     * Test auto-show IME behavior when the {@link EditText} is focusable ({@link
     * EditText#isFocusableInTouchMode} is {@code true}) and has called {@link
     * EditText#requestFocus}.
     */
    @Test
    public void autoShow_hasFocusedView_requestFocus() {
        // request focus at onCreate()
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        TestActivity activity = TestActivity.start(intent);

        verifyAutoShowBehavior_forwardWithKeyboardOff(activity);
    }

    /**
     * Test auto-show IME behavior when the {@link EditText} is focusable ({@link
     * EditText#isFocusableInTouchMode} is {@code true}) and {@link EditText#requestFocus} is not
     * called. The IME should never be shown because there is no focused editor in the window.
     */
    @Test
    public void autoShow_hasFocusedView_notRequestFocus() {
        // request focus not set
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        EditText editText = activity.getEditText();

        int windowFlags = activity.getWindow().getAttributes().flags;
        if ((windowFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            // When FLAG_NOT_FOCUSABLE is set true, the view will never gain window focus.
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ false, /*expectViewFocus*/ false);
        } else {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ false);
        }
        // IME is always hidden because there is no view focus.
        verifyImeIsAlwaysHidden(editText);
    }

    /**
     * Test auto-show IME behavior when the {@link EditText} is not focusable ({@link
     * EditText#isFocusableInTouchMode} is {@code false}) and {@link EditText#requestFocus} is not
     * called. The IME should never be shown because there is no focusable editor in the window.
     */
    @Test
    public void autoShow_notFocusedView_notRequestFocus() {
        // Unfocusable view, request focus not set
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(UNFOCUSABLE_VIEW));
        TestActivity activity = TestActivity.start(intent);
        EditText editText = activity.getEditText();

        int windowFlags = activity.getWindow().getAttributes().flags;
        if ((windowFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            // When FLAG_NOT_FOCUSABLE is set true, the view will never gain window focus.
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ false, /*expectViewFocus*/ false);
        } else {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ false);
        }
        // IME is always hidden because there is no focused view.
        verifyImeIsAlwaysHidden(editText);
    }

    /**
     * Test auto-show IME behavior when the activity is navigated forward from another activity with
     * keyboard off.
     */
    @Test
    public void autoShow_forwardWithKeyboardOff() {
        // Create first activity with keyboard off
        Intent intent1 =
                createIntent(
                        0x0 /* No window focus flags */,
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        Collections.emptyList());
        TestActivity firstActivity = TestActivity.start(intent1);

        // Create second activity with parameterized flags:
        Intent intent2 =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        TestActivity secondActivity = firstActivity.startSecondTestActivity(intent2);

        // The auto-show behavior should be the same as opening the app
        verifyAutoShowBehavior_forwardWithKeyboardOff(secondActivity);
    }

    /**
     * Test auto-show IME behavior when the activity is navigated forward from another activity with
     * keyboard on.
     */
    @Test
    public void autoShow_forwardWithKeyboardOn() {
        // Create first activity with keyboard on
        Intent intent1 =
                createIntent(
                        0x0 /* No window focus flags */,
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        TestActivity firstActivity = TestActivity.start(intent1);
        // Show Ime with InputMethodManager to ensure the keyboard is on.
        callOnMainSync(firstActivity::showImeWithInputMethodManager);
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();

        // Create second activity with parameterized flags:
        Intent intent2 =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        TestActivity secondActivity = firstActivity.startSecondTestActivity(intent2);

        // The auto-show behavior should be the same as open app
        verifyAutoShowBehavior_forwardWithKeyboardOn(secondActivity);
    }

    /**
     * Test auto-show IME behavior when the activity is navigated back from another activity with
     * keyboard off.
     */
    @Test
    public void autoShow_backwardWithKeyboardOff() {
        // Not request focus at onCreate() to avoid triggering auto-show behavior
        Intent intent1 = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity firstActivity = TestActivity.start(intent1);
        // Request view focus after app starts
        requestFocusAndVerify(firstActivity);

        Intent intent2 =
                createIntent(
                        0x0 /* No window focus flags */,
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        Collections.emptyList());
        TestActivity secondActivity = firstActivity.startSecondTestActivity(intent2);
        secondActivity.finish();
        mInstrumentation.waitForIdleSync();

        // When activity is navigated back from another activity with keyboard off, the keyboard
        // will not show except when soft input visibility flag is SOFT_INPUT_STATE_ALWAYS_VISIBLE.
        verifyAutoShowBehavior_backwardWithKeyboardOff(firstActivity);
    }

    /**
     * Test auto-show IME behavior when the activity is navigated back from another activity with
     * keyboard on.
     */
    @Test
    public void autoShow_backwardWithKeyboardOn() {
        // Not request focus at onCreate() to avoid triggering auto-show behavior
        Intent intent1 = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent1);
        // Request view focus after app starts
        requestFocusAndVerify(activity);

        // Create second TestActivity
        Intent intent2 =
                createIntent(
                        0x0 /* No window focus flags */,
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        ImeStressTestUtil.TestActivity secondActivity = activity.startSecondTestActivity(intent2);
        // Show Ime with InputMethodManager to ensure the keyboard is shown on the second activity
        callOnMainSync(secondActivity::showImeWithInputMethodManager);
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        // Close the second activity
        secondActivity.finish();
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        // When activity is navigated back from another activity with keyboard on, the keyboard
        // will not hide except when soft input visibility flag is SOFT_INPUT_STATE_ALWAYS_HIDDEN.
        verifyAutoShowBehavior_backwardWithKeyboardOn(activity);
    }

    @Test
    public void clickFocusableView_requestFocus() {
        if ((mWindowFocusFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            // UiAutomator cannot get UiObject if FLAG_NOT_FOCUSABLE is set
            return;
        }
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request view focus after app starts
        requestFocusAndVerify(activity);

        // Find the editText and click it
        UiObject2 editTextUiObject =
                UiDevice.getInstance(mInstrumentation)
                        .wait(Until.findObject(By.clazz(EditText.class)), 5000);
        assertThat(editTextUiObject).isNotNull();
        editTextUiObject.click();

        // Ime will show unless window flag is set
        verifyClickBehavior(activity);
    }

    @Test
    public void clickFocusableView_notRequestFocus() {
        if ((mWindowFocusFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            // UiAutomator cannot get UiObject if FLAG_NOT_FOCUSABLE is set
            return;
        }
        // Not request focus
        Intent intent1 = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent1);

        // Find the editText and click it
        UiObject2 editTextUiObject =
                UiDevice.getInstance(mInstrumentation)
                        .wait(Until.findObject(By.clazz(EditText.class)), 5000);
        assertThat(editTextUiObject).isNotNull();
        editTextUiObject.click();

        // Ime will show unless window flag is set
        verifyClickBehavior(activity);
    }

    private void verifyAutoShowBehavior_forwardWithKeyboardOff(TestActivity activity) {
        if (hasUnfocusableWindowFlags(activity)) {
            verifyImeAlwaysHiddenWithWindowFlagSet(activity);
            return;
        }

        int softInputMode = activity.getWindow().getAttributes().softInputMode;
        int softInputVisibility = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
        int softInputAdjustment = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
        EditText editText = activity.getEditText();

        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        switch (softInputVisibility) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE: {
                // IME will be auto-shown if softInputMode is set with flag:
                // SOFT_INPUT_STATE_VISIBLE or SOFT_INPUT_STATE_ALWAYS_VISIBLE
                waitOnMainUntilImeIsShown(editText);
                break;
            }
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN: {
                // IME will be not be auto-shown if softInputMode is set with flag:
                // SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                // or stay unchanged if set SOFT_INPUT_STATE_UNCHANGED
                verifyImeIsAlwaysHidden(editText);
                break;
            }
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED: {
                if ((softInputAdjustment
                        == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) || mIsLargeScreen) {
                    // The current system behavior will choose to show IME automatically when
                    // navigating forward to an app that has no visibility state specified
                    // (i.e. SOFT_INPUT_STATE_UNSPECIFIED) with set SOFT_INPUT_ADJUST_RESIZE
                    // flag or running on a large screen device.
                    waitOnMainUntilImeIsShown(editText);
                } else {
                    verifyImeIsAlwaysHidden(editText);
                }
                break;
            }
            default:
                break;
        }
    }

    private void verifyAutoShowBehavior_forwardWithKeyboardOn(TestActivity activity) {
        int windowFlags = activity.getWindow().getAttributes().flags;
        int softInputMode = activity.getWindow().getAttributes().softInputMode;
        int softInputVisibility = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
        int softInputAdjustment = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
        EditText editText = activity.getEditText();

        if ((windowFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            // When FLAG_NOT_FOCUSABLE is set true, the view will never gain window focus. The IME
            // will always be hidden even though the view can get focus itself.
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ false, /*expectViewFocus*/ true);
            // TODO(b/252192121): Ime should be hidden but is shown.
            // waitOnMainUntilImeIsHidden(editText);
            return;
        } else if ((windowFlags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0
                || (windowFlags & WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0) {
            // When FLAG_ALT_FOCUSABLE_IM or FLAG_LOCAL_FOCUS_MODE is set, the view can gain both
            // window focus and view focus but not IME focus. The IME will always be hidden.
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
            // TODO(b/252192121): Ime should be hidden but is shown.
            // waitOnMainUntilImeIsHidden(editText);
            return;
        }
        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        switch (softInputVisibility) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE: {
                // IME will be auto-shown if softInputMode is set with flag:
                // SOFT_INPUT_STATE_VISIBLE or SOFT_INPUT_STATE_ALWAYS_VISIBLE
                waitOnMainUntilImeIsShown(editText);
                break;
            }
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN: {
                // IME will be not be auto-shown if softInputMode is set with flag:
                // SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_STATE_ALWAYS_HIDDEN
                // or stay unchanged if set SOFT_INPUT_STATE_UNCHANGED
                verifyImeIsAlwaysHidden(editText);
                break;
            }
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED: {
                if ((softInputAdjustment
                        == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) || mIsLargeScreen) {
                    // The current system behavior will choose to show IME automatically when
                    // navigating forward to an app that has no visibility state specified  (i.e.
                    // SOFT_INPUT_STATE_UNSPECIFIED) with set SOFT_INPUT_ADJUST_RESIZE flag or
                    // running on a large screen device.
                    waitOnMainUntilImeIsShown(editText);
                } else {
                    verifyImeIsAlwaysHidden(editText);
                }
                break;
            }
            default:
                break;
        }
    }

    private static void verifyAutoShowBehavior_backwardWithKeyboardOff(TestActivity activity) {
        if (hasUnfocusableWindowFlags(activity)) {
            verifyImeAlwaysHiddenWithWindowFlagSet(activity);
            return;
        }
        int softInputMode = activity.getWindow().getAttributes().softInputMode;
        int softInputVisibility = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
        EditText editText = activity.getEditText();

        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        if (softInputVisibility == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) {
            waitOnMainUntilImeIsShown(editText);
        } else {
            verifyImeIsAlwaysHidden(editText);
        }
    }

    private static void verifyAutoShowBehavior_backwardWithKeyboardOn(TestActivity activity) {
        if (hasUnfocusableWindowFlags(activity)) {
            verifyImeAlwaysHiddenWithWindowFlagSet(activity);
            return;
        }
        int softInputMode = activity.getWindow().getAttributes().softInputMode;
        int softInputVisibility = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
        EditText editText = activity.getEditText();

        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        if (softInputVisibility == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) {
            verifyImeIsAlwaysHidden(editText);
        } else {
            waitOnMainUntilImeIsShown(editText);
        }
    }

    private static void verifyClickBehavior(TestActivity activity) {
        int windowFlags = activity.getWindow().getAttributes().flags;
        EditText editText = activity.getEditText();

        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        if ((windowFlags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0
                || (windowFlags & WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0) {
            verifyImeIsAlwaysHidden(editText);
        } else {
            waitOnMainUntilImeIsShown(editText);
        }
    }
}
