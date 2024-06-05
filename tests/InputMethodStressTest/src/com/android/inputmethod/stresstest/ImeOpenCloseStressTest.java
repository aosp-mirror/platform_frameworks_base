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

import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;

import static com.android.inputmethod.stresstest.ImeStressTestUtil.INPUT_METHOD_MANAGER_HIDE_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.INPUT_METHOD_MANAGER_SHOW_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.REQUEST_FOCUS_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.TestActivity;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.TestActivity.createIntent;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.WINDOW_INSETS_CONTROLLER_HIDE_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.WINDOW_INSETS_CONTROLLER_SHOW_ON_CREATE;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.callOnMainSync;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.getWindowAndSoftInputFlagParameters;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.hasUnfocusableWindowFlags;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.isImeShown;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.requestFocusAndVerify;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyImeAlwaysHiddenWithWindowFlagSet;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyImeIsAlwaysHidden;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.verifyWindowAndViewFocus;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntil;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsHidden;
import static com.android.inputmethod.stresstest.ImeStressTestUtil.waitOnMainUntilImeIsShown;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EditText;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RootPermissionTest
@RunWith(Parameterized.class)
public final class ImeOpenCloseStressTest {

    private static final String TAG = "ImeOpenCloseStressTest";
    private static final int NUM_TEST_ITERATIONS = 10;

    @Rule(order = 0) public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();
    @Rule(order = 1) public ImeStressTestRule mImeStressTestRule =
            new ImeStressTestRule(true /* useSimpleTestIme */);
    @Rule(order = 2) public ScreenCaptureRule mScreenCaptureRule =
            new ScreenCaptureRule("/sdcard/InputMethodStressTest");

    private final Instrumentation mInstrumentation;
    private final int mSoftInputFlags;
    private final int mWindowFocusFlags;

    @Parameterized.Parameters(
            name = "windowFocusFlags={0}, softInputVisibility={1}, softInputAdjustment={2}")
    public static List<Object[]> windowAndSoftInputFlagParameters() {
        return getWindowAndSoftInputFlagParameters();
    }

    public ImeOpenCloseStressTest(
            int windowFocusFlags, int softInputVisibility, int softInputAdjustment) {
        mSoftInputFlags = softInputVisibility | softInputAdjustment;
        mWindowFocusFlags = windowFocusFlags;
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
    }

    @Test
    public void testShowHideWithInputMethodManager_waitingVisibilityChange() {
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        // Test only once if window flags set to save time.
        int iterNum = hasUnfocusableWindowFlags(activity) ? 1 : NUM_TEST_ITERATIONS;
        for (int i = 0; i < iterNum; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            Log.i(TAG, msgPrefix + "start");
            callOnMainSync(activity::showImeWithInputMethodManager);
            verifyShowBehavior(activity);

            callOnMainSync(activity::hideImeWithInputMethodManager);
            verifyHideBehavior(activity);
        }
    }

    @Test
    public void testShowHideWithInputMethodManager_waitingAnimationEnd() {
        assumeFalse("Has unfocusable window flags", hasUnfocusableWindowFlags(mWindowFocusFlags));

        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        activity.enableAnimationMonitoring();
        EditText editText = activity.getEditText();
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            Log.i(TAG, msgPrefix + "start");
            callOnMainSync(activity::showImeWithInputMethodManager);
            waitOnMainUntil(
                    msgPrefix + "IME should have been shown",
                    () -> !activity.isAnimating() && isImeShown(editText));

            callOnMainSync(activity::hideImeWithInputMethodManager);
            waitOnMainUntil(
                    msgPrefix + "IME should have been hidden",
                    () -> !activity.isAnimating() && !isImeShown(editText));
        }
    }

    @Test
    public void testShowHideWithInputMethodManager_intervalAfterHide() {
        // Regression test for b/221483132
        assumeFalse("Has unfocusable window flags", hasUnfocusableWindowFlags(mWindowFocusFlags));

        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        // Intervals = 10, 20, 30, ..., 100, 150, 200, ...
        List<Integer> intervals = new ArrayList<>();
        for (int i = 10; i < 100; i += 10) intervals.add(i);
        for (int i = 100; i < 1000; i += 50) intervals.add(i);
        for (int intervalMillis : intervals) {
            String msgPrefix = "Interval = " + intervalMillis + " ";
            Log.i(TAG, msgPrefix + " start");
            callOnMainSync(activity::hideImeWithInputMethodManager);
            SystemClock.sleep(intervalMillis);

            callOnMainSync(activity::showImeWithInputMethodManager);
            verifyShowBehavior(activity);
        }
    }

    @Test
    public void testShowHideWithInputMethodManager_inSameFrame() {
        assumeFalse("Has unfocusable window flags", hasUnfocusableWindowFlags(mWindowFocusFlags));
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        // hidden -> show -> hide
        mInstrumentation.runOnMainSync(
                () -> {
                    Log.i(TAG, "Calling showIme() and hideIme()");
                    activity.showImeWithInputMethodManager();
                    activity.hideImeWithInputMethodManager();
                });
        // Wait until IMMS / IMS handles messages.
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        verifyHideBehavior(activity);

        mInstrumentation.runOnMainSync(activity::showImeWithInputMethodManager);
        verifyShowBehavior(activity);
        mInstrumentation.waitForIdleSync();

        // shown -> hide -> show
        mInstrumentation.runOnMainSync(
                () -> {
                    Log.i(TAG, "Calling hideIme() and showIme()");
                    activity.hideImeWithInputMethodManager();
                    activity.showImeWithInputMethodManager();
                });
        // Wait until IMMS / IMS handles messages.
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        verifyShowBehavior(activity);
    }

    /**
     * Test IME hidden by calling show and hide IME consecutively with
     * {@link android.view.inputmethod.InputMethodManager} APIs in
     * {@link android.app.Activity#onCreate}.
     *
     * <p> Note for developers: Use {@link WindowManager.LayoutParams#SOFT_INPUT_STATE_UNCHANGED}
     * window flag to avoid some softInputMode visibility flags may take presence over
     * {@link android.view.inputmethod.InputMethodManager} APIs (e.g. use showSoftInput to show
     * IME in {@link android.app.Activity#onCreate} but being hidden by
     * {@link WindowManager.LayoutParams#SOFT_INPUT_STATE_ALWAYS_HIDDEN} window flag after the
     * activity window focused).</p>
     */
    @Test
    public void testShowHideWithInputMethodManager_onCreate() {
        if (mSoftInputFlags != SOFT_INPUT_STATE_UNCHANGED) {
            return;
        }
        // Show and hide with InputMethodManager at onCreate()
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Arrays.asList(
                                REQUEST_FOCUS_ON_CREATE,
                                INPUT_METHOD_MANAGER_SHOW_ON_CREATE,
                                INPUT_METHOD_MANAGER_HIDE_ON_CREATE));
        TestActivity activity = TestActivity.start(intent);

        verifyHideBehavior(activity);
    }

    @Test
    public void testShowWithInputMethodManager_notRequestFocus() {
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);

        // Show InputMethodManager without requesting focus
        callOnMainSync(activity::showImeWithInputMethodManager);

        int windowFlags = activity.getWindow().getAttributes().flags;
        EditText editText = activity.getEditText();
        if ((windowFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ false, /*expectViewFocus*/ false);
        } else {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ false);
        }
        // The Ime should always be hidden because view never gains focus.
        verifyImeIsAlwaysHidden(editText);
    }

    @Test
    public void testShowHideWithWindowInsetsController_waitingVisibilityChange() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        // Test only once if window flags set to save time.
        int iterNum = hasUnfocusableWindowFlags(activity) ? 1 : NUM_TEST_ITERATIONS;
        for (int i = 0; i < iterNum; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            Log.i(TAG, msgPrefix + "start");
            mInstrumentation.runOnMainSync(activity::showImeWithWindowInsetsController);
            verifyShowBehavior(activity);
            mInstrumentation.runOnMainSync(activity::hideImeWithWindowInsetsController);
            verifyHideBehavior(activity);
        }
    }

    @Test
    public void testShowHideWithWindowInsetsController_waitingAnimationEnd() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        assumeFalse("Has unfocusable window flags", hasUnfocusableWindowFlags(mWindowFocusFlags));
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        activity.enableAnimationMonitoring();
        EditText editText = activity.getEditText();
        for (int i = 0; i < NUM_TEST_ITERATIONS; i++) {
            String msgPrefix = "Iteration #" + i + " ";
            Log.i(TAG, msgPrefix + "start");
            mInstrumentation.runOnMainSync(activity::showImeWithWindowInsetsController);
            waitOnMainUntil(
                    msgPrefix + "IME should have been shown",
                    () -> !activity.isAnimating() && isImeShown(editText));

            mInstrumentation.runOnMainSync(activity::hideImeWithWindowInsetsController);
            waitOnMainUntil(
                    msgPrefix + "IME should have been hidden",
                    () -> !activity.isAnimating() && !isImeShown(editText));
        }
    }

    @Test
    public void testShowHideWithWindowInsetsController_intervalAfterHide() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        assumeFalse("Has unfocusable window flags", hasUnfocusableWindowFlags(mWindowFocusFlags));
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        // Intervals = 10, 20, 30, ..., 100, 150, 200, ...
        List<Integer> intervals = new ArrayList<>();
        for (int i = 10; i < 100; i += 10) intervals.add(i);
        for (int i = 100; i < 1000; i += 50) intervals.add(i);
        for (int intervalMillis : intervals) {
            String msgPrefix = "Interval = " + intervalMillis + " ";
            Log.i(TAG, msgPrefix + " start");
            mInstrumentation.runOnMainSync(activity::hideImeWithWindowInsetsController);
            SystemClock.sleep(intervalMillis);

            mInstrumentation.runOnMainSync(activity::showImeWithWindowInsetsController);
            verifyShowBehavior(activity);
        }
    }

    @Test
    public void testShowHideWithWindowInsetsController_inSameFrame() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        assumeFalse("Has unfocusable window flags", hasUnfocusableWindowFlags(mWindowFocusFlags));
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        // Request focus after app starts to avoid triggering auto-show behavior.
        requestFocusAndVerify(activity);

        // hidden -> show -> hide
        mInstrumentation.runOnMainSync(
                () -> {
                    Log.i(TAG, "Calling showIme() and hideIme()");
                    activity.showImeWithWindowInsetsController();
                    activity.hideImeWithWindowInsetsController();
                });
        // Wait until IMMS / IMS handles messages.
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        verifyHideBehavior(activity);

        mInstrumentation.runOnMainSync(activity::showImeWithWindowInsetsController);
        verifyShowBehavior(activity);
        mInstrumentation.waitForIdleSync();

        // shown -> hide -> show
        mInstrumentation.runOnMainSync(
                () -> {
                    Log.i(TAG, "Calling hideIme() and showIme()");
                    activity.hideImeWithWindowInsetsController();
                    activity.showImeWithWindowInsetsController();
                });
        // Wait until IMMS / IMS handles messages.
        SystemClock.sleep(1000);
        mInstrumentation.waitForIdleSync();
        verifyShowBehavior(activity);
    }

    @Test
    public void testShowWithWindowInsetsController_onCreate_requestFocus() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        // Show with InputMethodManager at onCreate()
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Arrays.asList(
                                REQUEST_FOCUS_ON_CREATE, WINDOW_INSETS_CONTROLLER_SHOW_ON_CREATE));
        TestActivity activity = TestActivity.start(intent);

        verifyShowBehavior(activity);
    }

    @Test
    public void testShowWithWindowInsetsController_onCreate_notRequestFocus() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        // Show and hide with WindowInsetsController at onCreate()
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(WINDOW_INSETS_CONTROLLER_SHOW_ON_CREATE));
        TestActivity activity = TestActivity.start(intent);

        // Ime is shown but with a fallback InputConnection
        verifyShowBehaviorNotRequestFocus(activity);
    }

    @Test
    public void testShowWithWindowInsetsController_afterStart_notRequestFocus() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        // Show and hide with WindowInsetsController at onCreate()
        Intent intent = createIntent(mWindowFocusFlags, mSoftInputFlags, Collections.emptyList());
        TestActivity activity = TestActivity.start(intent);
        mInstrumentation.runOnMainSync(activity::showImeWithWindowInsetsController);

        // Ime is shown but with a fallback InputConnection
        verifyShowBehaviorNotRequestFocus(activity);
    }

    /**
     * Test IME hidden by calling show and hide IME consecutively with
     * {@link android.view.WindowInsetsController} APIs in
     * {@link android.app.Activity#onCreate}.
     *
     * <p> Note for developers: Use {@link WindowManager.LayoutParams#SOFT_INPUT_STATE_UNCHANGED}
     * window flag to avoid some softInputMode visibility flags may take presence over
     * {@link android.view.WindowInsetsController} APIs (e.g. use showSoftInput to show
     * IME in {@link android.app.Activity#onCreate} but being hidden by
     * {@link WindowManager.LayoutParams#SOFT_INPUT_STATE_ALWAYS_HIDDEN} window flag after the
     * activity window focused).</p>
     */
    @Test
    public void testHideWithWindowInsetsController_onCreate_requestFocus() {
        assumeTrue("Is at least Android R", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
        if (mSoftInputFlags != SOFT_INPUT_STATE_UNCHANGED) {
            return;
        }
        // Show and hide with WindowInsetsController at onCreate()
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Arrays.asList(
                                REQUEST_FOCUS_ON_CREATE,
                                WINDOW_INSETS_CONTROLLER_SHOW_ON_CREATE,
                                WINDOW_INSETS_CONTROLLER_HIDE_ON_CREATE));
        TestActivity activity = TestActivity.start(intent);

        verifyHideBehavior(activity);
    }

    @Test
    public void testScreenOffOn() throws Exception {
        Intent intent1 =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        TestActivity activity = TestActivity.start(intent1);
        // Show Ime with InputMethodManager to ensure the keyboard is shown on the second activity
        callOnMainSync(activity::showImeWithInputMethodManager);

        Thread.sleep(1000);
        verifyShowBehavior(activity);

        UiDevice uiDevice = UiDevice.getInstance(mInstrumentation);

        if (uiDevice.isScreenOn()) {
            uiDevice.sleep();
        }
        Thread.sleep(1000);
        if (!uiDevice.isScreenOn()) {
            uiDevice.wakeUp();
        }

        verifyShowBehavior(activity);
    }

    // TODO: Add tests for activities that don't handle the rotation.
    @Test
    public void testRotateScreenWithKeyboardOn() throws Exception {
        Intent intent =
                createIntent(
                        mWindowFocusFlags,
                        mSoftInputFlags,
                        Collections.singletonList(REQUEST_FOCUS_ON_CREATE));
        TestActivity activity = TestActivity.start(intent);
        // Show Ime with InputMethodManager to ensure the keyboard is shown on the second activity
        callOnMainSync(activity::showImeWithInputMethodManager);
        Thread.sleep(2000);
        verifyShowBehavior(activity);

        UiDevice uiDevice = UiDevice.getInstance(mInstrumentation);

        uiDevice.setOrientationRight();
        uiDevice.waitForIdle();
        Thread.sleep(1000);
        Log.i(TAG, "Rotate screen right");
        assertThat(uiDevice.isNaturalOrientation()).isFalse();
        verifyRotateBehavior(activity);

        uiDevice.setOrientationLeft();
        uiDevice.waitForIdle();
        Thread.sleep(1000);
        Log.i(TAG, "Rotate screen left");
        assertThat(uiDevice.isNaturalOrientation()).isFalse();
        verifyRotateBehavior(activity);

        uiDevice.setOrientationNatural();
        uiDevice.waitForIdle();
    }

    private static void verifyShowBehavior(TestActivity activity) {
        if (hasUnfocusableWindowFlags(activity)) {
            verifyImeAlwaysHiddenWithWindowFlagSet(activity);
            return;
        }
        EditText editText = activity.getEditText();

        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        waitOnMainUntilImeIsShown(editText);
    }

    private static void verifyHideBehavior(TestActivity activity) {
        if (hasUnfocusableWindowFlags(activity)) {
            verifyImeAlwaysHiddenWithWindowFlagSet(activity);
            return;
        }
        EditText editText = activity.getEditText();

        verifyWindowAndViewFocus(editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
        waitOnMainUntilImeIsHidden(editText);
    }

    private static void verifyShowBehaviorNotRequestFocus(TestActivity activity) {
        int windowFlags = activity.getWindow().getAttributes().flags;
        EditText editText = activity.getEditText();

        if ((windowFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ false, /*expectViewFocus*/ false);
            verifyImeIsAlwaysHidden(editText);
        } else if ((windowFlags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0
                || (windowFlags & WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0) {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ false);
            verifyImeIsAlwaysHidden(editText);
        } else {
            verifyWindowAndViewFocus(
                    editText, /*expectWindowFocus*/ true, /*expectViewFocus*/ false);
            // Ime is shown but with a fallback InputConnection
            waitOnMainUntilImeIsShown(editText);
        }
    }

    private static void verifyRotateBehavior(TestActivity activity) {
        // Get the new TestActivity after recreation.
        TestActivity newActivity = TestActivity.getLastCreatedInstance();
        assertThat(newActivity).isNotNull();
        assertThat(newActivity).isNotEqualTo(activity);

        EditText newEditText = newActivity.getEditText();
        int softInputMode = newActivity.getWindow().getAttributes().softInputMode;
        int softInputVisibility = softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;

        if (hasUnfocusableWindowFlags(newActivity)) {
            verifyImeAlwaysHiddenWithWindowFlagSet(newActivity);
            return;
        }

        if (softInputVisibility == WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN) {
            // After rotation, the keyboard would be hidden only when the flag is
            // SOFT_INPUT_STATE_ALWAYS_HIDDEN. However, SOFT_INPUT_STATE_HIDDEN is different because
            // it requires appending SOFT_INPUT_IS_FORWARD_NAVIGATION flag, which won't be added
            // when rotating the devices (rotating doesn't navigate forward to the next app window.)
            verifyWindowAndViewFocus(newEditText, /*expectWindowFocus*/ true, /*expectViewFocus*/
                    true);
            waitOnMainUntilImeIsHidden(newEditText);

        } else {
            // Other cases, keyboard would be shown.
            verifyWindowAndViewFocus(newEditText, /*expectWindowFocus*/ true, /*expectViewFocus*/
                    true);
            waitOnMainUntilImeIsShown(newEditText);
        }
    }
}
