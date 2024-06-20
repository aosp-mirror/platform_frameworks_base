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

package com.android.inputmethodservice;

import static android.view.WindowInsets.Type.captionBar;

import static com.android.compatibility.common.util.SystemUtil.eventually;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.RemoteException;
import android.provider.Settings;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Log;
import android.view.WindowManagerGlobal;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.apps.inputmethod.simpleime.ims.InputMethodServiceWrapper;
import com.android.apps.inputmethod.simpleime.testing.TestActivity;
import com.android.compatibility.common.util.SystemUtil;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class InputMethodServiceTest {
    private static final String TAG = "SimpleIMSTest";
    private static final String INPUT_METHOD_SERVICE_NAME = ".SimpleInputMethodService";
    private static final String EDIT_TEXT_DESC = "Input box";
    private static final long TIMEOUT_IN_SECONDS = 3;
    private static final String ENABLE_SHOW_IME_WITH_HARD_KEYBOARD_CMD =
            "settings put secure " + Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD + " 1";
    private static final String DISABLE_SHOW_IME_WITH_HARD_KEYBOARD_CMD =
            "settings put secure " + Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD + " 0";

    private Instrumentation mInstrumentation;
    private UiDevice mUiDevice;
    private Context mContext;
    private String mTargetPackageName;
    private TestActivity mActivity;
    private InputMethodServiceWrapper mInputMethodService;
    private String mInputMethodId;
    private boolean mShowImeWithHardKeyboardEnabled;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mUiDevice = UiDevice.getInstance(mInstrumentation);
        mContext = mInstrumentation.getContext();
        mTargetPackageName = mInstrumentation.getTargetContext().getPackageName();
        mInputMethodId = getInputMethodId();
        prepareIme();
        prepareEditor();
        mInstrumentation.waitForIdleSync();
        mUiDevice.freezeRotation();
        mUiDevice.setOrientationNatural();
        // Waits for input binding ready.
        eventually(() -> {
            mInputMethodService =
                    InputMethodServiceWrapper.getInputMethodServiceWrapperForTesting();
            assertThat(mInputMethodService).isNotNull();

            // The editor won't bring up keyboard by default.
            assertThat(mInputMethodService.getCurrentInputStarted()).isTrue();
            assertThat(mInputMethodService.getCurrentInputViewStarted()).isFalse();
        });
        // Save the original value of show_ime_with_hard_keyboard from Settings.
        mShowImeWithHardKeyboardEnabled = Settings.Secure.getInt(
                mInputMethodService.getContentResolver(),
                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0;
    }

    @After
    public void tearDown() throws Exception {
        mUiDevice.unfreezeRotation();
        executeShellCommand("ime disable " + mInputMethodId);
        // Change back the original value of show_ime_with_hard_keyboard in Settings.
        executeShellCommand(mShowImeWithHardKeyboardEnabled
                ? ENABLE_SHOW_IME_WITH_HARD_KEYBOARD_CMD
                : DISABLE_SHOW_IME_WITH_HARD_KEYBOARD_CMD);
    }

    /**
     * This checks that the IME can be shown and hidden by user actions
     * (i.e. tapping on an EditText, tapping the Home button).
     */
    @Test
    public void testShowHideKeyboard_byUserAction() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // Performs click on editor box to bring up the soft keyboard.
        Log.i(TAG, "Click on EditText.");
        verifyInputViewStatus(
                () -> clickOnEditorText(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // Press home key to hide soft keyboard.
        Log.i(TAG, "Press home");
        verifyInputViewStatus(
                () -> assertThat(mUiDevice.pressHome()).isTrue(),
                true /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks that the IME can be shown and hidden using the WindowInsetsController APIs.
     */
    @Test
    public void testShowHideKeyboard_byApi() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // Triggers to show IME via public API.
        verifyInputViewStatus(
                () -> assertThat(mActivity.showImeWithWindowInsetsController()).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // Triggers to hide IME via public API.
        verifyInputViewStatusOnMainSync(
                () -> assertThat(mActivity.hideImeWithWindowInsetsController()).isTrue(),
                true /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks the result of calling IMS#requestShowSelf and IMS#requestHideSelf.
     */
    @Test
    public void testShowHideSelf() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // IME request to show itself without any flags, expect shown.
        Log.i(TAG, "Call IMS#requestShowSelf(0)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestShowSelf(0 /* flags */),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // IME request to hide itself with flag HIDE_IMPLICIT_ONLY, expect not hide (shown).
        Log.i(TAG, "Call IMS#requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY),
                false /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // IME request to hide itself without any flags, expect hidden.
        Log.i(TAG, "Call IMS#requestHideSelf(0)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestHideSelf(0 /* flags */),
                true /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();

        // IME request to show itself with flag SHOW_IMPLICIT, expect shown.
        Log.i(TAG, "Call IMS#requestShowSelf(InputMethodManager.SHOW_IMPLICIT)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestShowSelf(InputMethodManager.SHOW_IMPLICIT),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // IME request to hide itself with flag HIDE_IMPLICIT_ONLY, expect hidden.
        Log.i(TAG, "Call IMS#requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY),
                true /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks the return value of IMS#onEvaluateInputViewShown,
     * when show_ime_with_hard_keyboard is enabled.
     */
    @Test
    public void testOnEvaluateInputViewShown_showImeWithHardKeyboard() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_NO;
        eventually(() -> assertThat(mInputMethodService.onEvaluateInputViewShown()).isTrue());

        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_NOKEYS;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_NO;
        eventually(() -> assertThat(mInputMethodService.onEvaluateInputViewShown()).isTrue());

        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;
        eventually(() -> assertThat(mInputMethodService.onEvaluateInputViewShown()).isTrue());
    }

    /**
     * This checks the return value of IMSonEvaluateInputViewShown,
     * when show_ime_with_hard_keyboard is disabled.
     */
    @Test
    public void testOnEvaluateInputViewShown_disableShowImeWithHardKeyboard() throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_NO;
        eventually(() -> assertThat(mInputMethodService.onEvaluateInputViewShown()).isFalse());

        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_NOKEYS;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_NO;
        eventually(() -> assertThat(mInputMethodService.onEvaluateInputViewShown()).isTrue());

        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;
        eventually(() -> assertThat(mInputMethodService.onEvaluateInputViewShown()).isTrue());
    }

    /**
     * This checks that any (implicit or explicit) show request,
     * when IMS#onEvaluateInputViewShown returns false, results in the IME not being shown.
     */
    @Test
    public void testShowSoftInput_disableShowImeWithHardKeyboard() throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        // Simulate connecting a hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_NO;

        // When InputMethodService#onEvaluateInputViewShown() returns false, the Ime should not be
        // shown no matter what the show flag is.
        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_IMPLICIT)).isTrue(),
                false /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();

        verifyInputViewStatusOnMainSync(
                () -> assertThat(mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                false /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks that an explicit show request results in the IME being shown.
     */
    @Test
    public void testShowSoftInputExplicitly() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // When InputMethodService#onEvaluateInputViewShown() returns true and flag is EXPLICIT, the
        // Ime should be shown.
        verifyInputViewStatusOnMainSync(
                () -> assertThat(mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that an implicit show request results in the IME being shown.
     */
    @Test
    public void testShowSoftInputImplicitly() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // When InputMethodService#onEvaluateInputViewShown() returns true and flag is IMPLICIT,
        // the IME should be shown.
        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_IMPLICIT)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that an explicit show request when the IME is not previously shown,
     * and it should be shown in fullscreen mode, results in the IME being shown.
     */
    @Test
    public void testShowSoftInputExplicitly_fullScreenMode() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // Set orientation landscape to enable fullscreen mode.
        setOrientation(2);
        eventually(() -> assertThat(mUiDevice.isNaturalOrientation()).isFalse());
        // Wait for the TestActivity to be recreated.
        eventually(() ->
                assertThat(TestActivity.getLastCreatedInstance()).isNotEqualTo(mActivity));
        // Get the new TestActivity.
        mActivity = TestActivity.getLastCreatedInstance();
        assertThat(mActivity).isNotNull();
        InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        // Wait for the new EditText to be served by InputMethodManager.
        eventually(() -> assertThat(
                imm.hasActiveInputConnection(mActivity.getEditText())).isTrue());

        verifyInputViewStatusOnMainSync(() -> assertThat(
                        mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that an implicit show request when the IME is not previously shown,
     * and it should be shown in fullscreen mode, results in the IME not being shown.
     */
    @Test
    public void testShowSoftInputImplicitly_fullScreenMode() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        // Set orientation landscape to enable fullscreen mode.
        setOrientation(2);
        eventually(() -> assertThat(mUiDevice.isNaturalOrientation()).isFalse());
        // Wait for the TestActivity to be recreated.
        eventually(() ->
                assertThat(TestActivity.getLastCreatedInstance()).isNotEqualTo(mActivity));
        // Get the new TestActivity.
        mActivity = TestActivity.getLastCreatedInstance();
        assertThat(mActivity).isNotNull();
        InputMethodManager imm = mContext.getSystemService(InputMethodManager.class);
        // Wait for the new EditText to be served by InputMethodManager.
        eventually(() -> assertThat(
                imm.hasActiveInputConnection(mActivity.getEditText())).isTrue());

        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_IMPLICIT)).isTrue(),
                false /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks that an explicit show request when a hard keyboard is connected,
     * results in the IME being shown.
     */
    @Test
    public void testShowSoftInputExplicitly_withHardKeyboard() throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        // Simulate connecting a hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that an implicit show request when a hard keyboard is connected,
     * results in the IME not being shown.
     */
    @Test
    public void testShowSoftInputImplicitly_withHardKeyboard() throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        // Simulate connecting a hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_IMPLICIT)).isTrue(),
                false /* expected */,
                false /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks that an explicit show request followed by connecting a hard keyboard
     * and a configuration change, still results in the IME being shown.
     */
    @Test
    public void testShowSoftInputExplicitly_thenConfigurationChanged() throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        // Start with no hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_NOKEYS;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        verifyInputViewStatusOnMainSync(
                () -> assertThat(mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // Simulate connecting a hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        // Simulate a fake configuration change to avoid triggering the recreation of TestActivity.
        mInputMethodService.getResources().getConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;

        verifyInputViewStatusOnMainSync(() -> mInputMethodService.onConfigurationChanged(
                mInputMethodService.getResources().getConfiguration()),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that an implicit show request followed by connecting a hard keyboard
     * and a configuration change, does not trigger IMS#onFinishInputView,
     * but results in the IME being hidden.
     */
    @Test
    public void testShowSoftInputImplicitly_thenConfigurationChanged() throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        // Start with no hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_NOKEYS;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_IMPLICIT)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // Simulate connecting a hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        // Simulate a fake configuration change to avoid triggering the recreation of TestActivity.
        mInputMethodService.getResources().getConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;

        // Normally, IMS#onFinishInputView will be called when finishing the input view by the user.
        // But if IMS#hideWindow is called when receiving a new configuration change, we don't
        // expect that it's user-driven to finish the lifecycle of input view with
        // IMS#onFinishInputView, because the input view will be re-initialized according to the
        // last #mShowInputRequested state. So in this case we treat the input view as still alive.
        verifyInputViewStatusOnMainSync(() -> mInputMethodService.onConfigurationChanged(
                mInputMethodService.getResources().getConfiguration()),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isFalse();
    }

    /**
     * This checks that an explicit show request directly followed by an implicit show request,
     * while a hardware keyboard is connected, still results in the IME being shown
     * (i.e. the implicit show request is treated as explicit).
     */
    @Test
    public void testShowSoftInputExplicitly_thenShowSoftInputImplicitly_withHardKeyboard()
            throws Exception {
        setShowImeWithHardKeyboard(false /* enabled */);

        // Simulate connecting a hard keyboard.
        mInputMethodService.getResources().getConfiguration().keyboard =
                Configuration.KEYBOARD_QWERTY;
        mInputMethodService.getResources().getConfiguration().hardKeyboardHidden =
                Configuration.HARDKEYBOARDHIDDEN_YES;

        // Explicit show request.
        verifyInputViewStatusOnMainSync(() -> assertThat(
                        mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // Implicit show request.
        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_IMPLICIT)).isTrue(),
                false /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        // Simulate a fake configuration change to avoid triggering the recreation of TestActivity.
        // This should now consider the implicit show request, but keep the state from the
        // explicit show request, and thus not hide the keyboard.
        verifyInputViewStatusOnMainSync(() -> mInputMethodService.onConfigurationChanged(
                        mInputMethodService.getResources().getConfiguration()),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that a forced show request directly followed by an explicit show request,
     * and then a hide not always request, still results in the IME being shown
     * (i.e. the explicit show request retains the forced state).
     */
    @Test
    public void testShowSoftInputForced_testShowSoftInputExplicitly_thenHideSoftInputNotAlways()
            throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        verifyInputViewStatusOnMainSync(() -> assertThat(
                mActivity.showImeWithInputMethodManager(InputMethodManager.SHOW_FORCED)).isTrue(),
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        verifyInputViewStatusOnMainSync(() -> assertThat(
                        mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue(),
                false /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();

        verifyInputViewStatusOnMainSync(() ->
                        mActivity.hideImeWithInputMethodManager(InputMethodManager.HIDE_NOT_ALWAYS),
                false /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
    }

    /**
     * This checks that the IME fullscreen mode state is updated after changing orientation.
     */
    @Test
    public void testFullScreenMode() throws Exception {
        setShowImeWithHardKeyboard(true /* enabled */);

        Log.i(TAG, "Set orientation natural");
        verifyFullscreenMode(() -> setOrientation(0),
                false /* expected */,
                true /* orientationPortrait */);

        Log.i(TAG, "Set orientation left");
        verifyFullscreenMode(() -> setOrientation(1),
                true /* expected */,
                false /* orientationPortrait */);

        Log.i(TAG, "Set orientation right");
        verifyFullscreenMode(() -> setOrientation(2),
                false /* expected */,
                false /* orientationPortrait */);
    }

    /**
     * This checks that when the system navigation bar is not created (e.g. emulator),
     * then the IME caption bar is also not created.
     */
    @Test
    public void testNoNavigationBar_thenImeNoCaptionBar() throws Exception {
        boolean hasNavigationBar = WindowManagerGlobal.getWindowManagerService()
                .hasNavigationBar(mInputMethodService.getDisplayId());
        assumeFalse("Must not have a navigation bar", hasNavigationBar);

        assertEquals(Insets.NONE, mInputMethodService.getWindow().getWindow().getDecorView()
                .getRootWindowInsets().getInsetsIgnoringVisibility(captionBar()));
    }

    /**
     * This checks that trying to show and hide the navigation bar takes effect
     * when the IME does draw the IME navigation bar.
     */
    @Test
    public void testShowHideImeNavigationBar_doesDrawImeNavBar() throws Exception {
        boolean hasNavigationBar = WindowManagerGlobal.getWindowManagerService()
                .hasNavigationBar(mInputMethodService.getDisplayId());
        assumeTrue("Must have a navigation bar", hasNavigationBar);

        setShowImeWithHardKeyboard(true /* enabled */);

        // Show IME
        verifyInputViewStatusOnMainSync(
                () -> {
                    mInputMethodService.getInputMethodInternal().onNavButtonFlagsChanged(
                            InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR
                                    | InputMethodNavButtonFlags.SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN
                    );
                    assertThat(mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue();
                },
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
        assertThat(mInputMethodService.isImeNavigationBarShownForTesting()).isTrue();

        // Try to hide IME nav bar
        mInstrumentation.runOnMainSync(() -> mInputMethodService.getWindow().getWindow()
                .getInsetsController().hide(captionBar()));
        mInstrumentation.waitForIdleSync();
        assertThat(mInputMethodService.isImeNavigationBarShownForTesting()).isFalse();

        // Try to show IME nav bar
        mInstrumentation.runOnMainSync(() -> mInputMethodService.getWindow().getWindow()
                .getInsetsController().show(captionBar()));
        mInstrumentation.waitForIdleSync();
        assertThat(mInputMethodService.isImeNavigationBarShownForTesting()).isTrue();
    }
    /**
     * This checks that trying to show and hide the navigation bar has no effect
     * when the IME does not draw the IME navigation bar.
     *
     * Note: The IME navigation bar is *never* visible in 3 button navigation mode.
     */
    @Test
    public void testShowHideImeNavigationBar_doesNotDrawImeNavBar() throws Exception {
        boolean hasNavigationBar = WindowManagerGlobal.getWindowManagerService()
                .hasNavigationBar(mInputMethodService.getDisplayId());
        assumeTrue("Must have a navigation bar", hasNavigationBar);

        setShowImeWithHardKeyboard(true /* enabled */);

        // Show IME
        verifyInputViewStatusOnMainSync(() -> {
            mInputMethodService.getInputMethodInternal().onNavButtonFlagsChanged(
                    0 /* navButtonFlags */);
            assertThat(mActivity.showImeWithInputMethodManager(0 /* flags */)).isTrue();
        },
                true /* expected */,
                true /* inputViewStarted */);
        assertThat(mInputMethodService.isInputViewShown()).isTrue();
        assertThat(mInputMethodService.isImeNavigationBarShownForTesting()).isFalse();

        // Try to hide IME nav bar
        mInstrumentation.runOnMainSync(() -> mInputMethodService.getWindow().getWindow()
                .getInsetsController().hide(captionBar()));
        mInstrumentation.waitForIdleSync();
        assertThat(mInputMethodService.isImeNavigationBarShownForTesting()).isFalse();

        // Try to show IME nav bar
        mInstrumentation.runOnMainSync(() -> mInputMethodService.getWindow().getWindow()
                .getInsetsController().show(captionBar()));
        mInstrumentation.waitForIdleSync();
        assertThat(mInputMethodService.isImeNavigationBarShownForTesting()).isFalse();
    }

    private void verifyInputViewStatus(
            Runnable runnable, boolean expected, boolean inputViewStarted)
            throws InterruptedException {
        verifyInputViewStatusInternal(runnable, expected, inputViewStarted,
                false /* runOnMainSync */);
    }

    private void verifyInputViewStatusOnMainSync(
            Runnable runnable, boolean expected, boolean inputViewStarted)
            throws InterruptedException {
        verifyInputViewStatusInternal(runnable, expected, inputViewStarted,
                true /* runOnMainSync */);
    }

    /**
     * Verifies the status of the Input View after executing the given runnable.
     *
     * @param runnable the runnable to execute for showing or hiding the IME.
     * @param expected whether the runnable is expected to trigger the signal.
     * @param inputViewStarted the expected state of the Input View after executing the runnable.
     * @param runOnMainSync whether to execute the runnable on the main thread.
     */
    private void verifyInputViewStatusInternal(
            Runnable runnable, boolean expected, boolean inputViewStarted, boolean runOnMainSync)
            throws InterruptedException {
        CountDownLatch signal = new CountDownLatch(1);
        mInputMethodService.setCountDownLatchForTesting(signal);
        // Runnable to trigger onStartInputView() / onFinishInputView() / onConfigurationChanged()
        if (runOnMainSync) {
            mInstrumentation.runOnMainSync(runnable);
        } else {
            runnable.run();
        }
        mInstrumentation.waitForIdleSync();
        boolean completed = signal.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (expected && !completed) {
            fail("Timed out waiting for"
                    + " onStartInputView() / onFinishInputView() / onConfigurationChanged()");
        } else if (!expected && completed) {
            fail("Unexpected call"
                    + " onStartInputView() / onFinishInputView() / onConfigurationChanged()");
        }
        // Input is not finished.
        assertThat(mInputMethodService.getCurrentInputStarted()).isTrue();
        assertThat(mInputMethodService.getCurrentInputViewStarted()).isEqualTo(inputViewStarted);
    }

    private void setOrientation(int orientation) {
        // Simple wrapper for catching RemoteException.
        try {
            switch (orientation) {
                case 1:
                    mUiDevice.setOrientationLeft();
                    break;
                case 2:
                    mUiDevice.setOrientationRight();
                    break;
                default:
                    mUiDevice.setOrientationNatural();
            }
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verifies the IME fullscreen mode state after executing the given runnable.
     *
     * @param runnable the runnable to execute for setting the orientation.
     * @param expected whether the runnable is expected to trigger the signal.
     * @param orientationPortrait whether the orientation is expected to be portrait.
     */
    private void verifyFullscreenMode(
            Runnable runnable, boolean expected, boolean orientationPortrait)
            throws InterruptedException {
        CountDownLatch signal = new CountDownLatch(1);
        mInputMethodService.setCountDownLatchForTesting(signal);

        // Runnable to trigger onConfigurationChanged()
        try {
            runnable.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // Waits for onConfigurationChanged() to finish.
        mInstrumentation.waitForIdleSync();
        boolean completed = signal.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        if (expected && !completed) {
            fail("Timed out waiting for onConfigurationChanged()");
        } else if (!expected && completed) {
            fail("Unexpected call onConfigurationChanged()");
        }

        clickOnEditorText();
        eventually(() -> assertThat(mInputMethodService.isInputViewShown()).isTrue());

        assertThat(mInputMethodService.getResources().getConfiguration().orientation)
                .isEqualTo(
                        orientationPortrait
                                ? Configuration.ORIENTATION_PORTRAIT
                                : Configuration.ORIENTATION_LANDSCAPE);
        EditorInfo editorInfo = mInputMethodService.getCurrentInputEditorInfo();
        assertThat(editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN).isEqualTo(0);
        assertThat(editorInfo.internalImeOptions & EditorInfo.IME_INTERNAL_FLAG_APP_WINDOW_PORTRAIT)
                .isEqualTo(
                        orientationPortrait ? EditorInfo.IME_INTERNAL_FLAG_APP_WINDOW_PORTRAIT : 0);
        assertThat(mInputMethodService.onEvaluateFullscreenMode()).isEqualTo(!orientationPortrait);
        assertThat(mInputMethodService.isFullscreenMode()).isEqualTo(!orientationPortrait);

        mUiDevice.pressBack();
    }

    private void prepareIme() throws Exception {
        executeShellCommand("ime enable " + mInputMethodId);
        executeShellCommand("ime set " + mInputMethodId);
        mInstrumentation.waitForIdleSync();
        Log.i(TAG, "Finish preparing IME");
    }

    private void prepareEditor() {
        mActivity = TestActivity.start(mInstrumentation);
        Log.i(TAG, "Finish preparing activity with editor.");
    }

    private String getInputMethodId() {
        return mTargetPackageName + "/" + INPUT_METHOD_SERVICE_NAME;
    }

    /**
     * Sets the value of show_ime_with_hard_keyboard, only if it is different to the default value.
     *
     * @param enabled the value to be set.
     */
    private void setShowImeWithHardKeyboard(boolean enabled) throws IOException {
        if (mShowImeWithHardKeyboardEnabled != enabled) {
            executeShellCommand(enabled
                    ? ENABLE_SHOW_IME_WITH_HARD_KEYBOARD_CMD
                    : DISABLE_SHOW_IME_WITH_HARD_KEYBOARD_CMD);
            mInstrumentation.waitForIdleSync();
        }
    }

    private String executeShellCommand(String cmd) throws IOException {
        Log.i(TAG, "Run command: " + cmd);
        return SystemUtil.runShellCommandOrThrow(cmd);
    }

    private void clickOnEditorText() {
        // Find the editText and click it.
        UiObject2 editTextUiObject =
                mUiDevice.wait(
                        Until.findObject(By.desc(EDIT_TEXT_DESC)),
                        TimeUnit.SECONDS.toMillis(TIMEOUT_IN_SECONDS));
        assertThat(editTextUiObject).isNotNull();
        editTextUiObject.click();
        mInstrumentation.waitForIdleSync();
    }
}
