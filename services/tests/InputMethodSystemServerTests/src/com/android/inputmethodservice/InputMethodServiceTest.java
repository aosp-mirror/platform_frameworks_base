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

import static com.android.compatibility.common.util.SystemUtil.eventually;

import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.apps.inputmethod.simpleime.ims.InputMethodServiceWrapper;
import com.android.apps.inputmethod.simpleime.testing.TestActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class InputMethodServiceTest {
    private static final String TAG = "SimpleIMSTest";
    private static final String INPUT_METHOD_SERVICE_NAME = ".SimpleInputMethodService";
    private static final String EDIT_TEXT_DESC = "Input box";
    private static final long TIMEOUT_IN_SECONDS = 3;

    public Instrumentation mInstrumentation;
    public UiDevice mUiDevice;
    public Context mContext;
    public String mTargetPackageName;
    public TestActivity mActivity;
    public EditText mEditText;
    public InputMethodServiceWrapper mInputMethodService;
    public String mInputMethodId;

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mUiDevice = UiDevice.getInstance(mInstrumentation);
        mContext = mInstrumentation.getContext();
        mTargetPackageName = mInstrumentation.getTargetContext().getPackageName();
        mInputMethodId = getInputMethodId();
        prepareIme();
        prepareEditor();

        // Waits for input binding ready.
        eventually(
                () -> {
                    mInputMethodService =
                            InputMethodServiceWrapper.getInputMethodServiceWrapperForTesting();
                    assertThat(mInputMethodService).isNotNull();

                    // The editor won't bring up keyboard by default.
                    assertThat(mInputMethodService.getCurrentInputStarted()).isTrue();
                    assertThat(mInputMethodService.getCurrentInputViewStarted()).isFalse();
                });
    }

    @Test
    public void testShowHideKeyboard_byUserAction() throws InterruptedException {
        // Performs click on editor box to bring up the soft keyboard.
        Log.i(TAG, "Click on EditText.");
        verifyInputViewStatus(() -> clickOnEditorText(), true /* inputViewStarted */);

        // Press back key to hide soft keyboard.
        Log.i(TAG, "Press back");
        verifyInputViewStatus(
                () -> assertThat(mUiDevice.pressHome()).isTrue(), false /* inputViewStarted */);
    }

    @Test
    public void testShowHideKeyboard_byApi() throws InterruptedException {
        // Triggers to show IME via public API.
        verifyInputViewStatus(
                () -> assertThat(mActivity.showImeWithWindowInsetsController()).isTrue(),
                true /* inputViewStarted */);

        // Triggers to hide IME via public API.
        // TODO(b/242838873): investigate why WIC#hide(ime()) does not work, likely related to
        //  triggered from IME process.
        verifyInputViewStatusOnMainSync(
                () -> assertThat(mActivity.hideImeWithInputMethodManager(0 /* flags */)).isTrue(),
                false /* inputViewStarted */);
    }

    @Test
    public void testShowHideSelf() throws InterruptedException {
        // IME requests to show itself without any flags: expect shown.
        Log.i(TAG, "Call IMS#requestShowSelf(0)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestShowSelf(0), true /* inputViewStarted */);

        // IME requests to hide itself with flag: HIDE_IMPLICIT_ONLY, expect not hide (shown).
        Log.i(TAG, "Call IMS#requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY),
                true /* inputViewStarted */);

        // IME request to hide itself without any flags: expect hidden.
        Log.i(TAG, "Call IMS#requestHideSelf(0)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestHideSelf(0), false /* inputViewStarted */);

        // IME request to show itself with flag SHOW_IMPLICIT: expect shown.
        Log.i(TAG, "Call IMS#requestShowSelf(InputMethodManager.SHOW_IMPLICIT)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestShowSelf(InputMethodManager.SHOW_IMPLICIT),
                true /* inputViewStarted */);

        // IME request to hide itself with flag: HIDE_IMPLICIT_ONLY, expect hidden.
        Log.i(TAG, "Call IMS#requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY)");
        verifyInputViewStatusOnMainSync(
                () -> mInputMethodService.requestHideSelf(InputMethodManager.HIDE_IMPLICIT_ONLY),
                false /* inputViewStarted */);
    }

    private void verifyInputViewStatus(Runnable runnable, boolean inputViewStarted)
            throws InterruptedException {
        verifyInputViewStatusInternal(runnable, inputViewStarted, false /*runOnMainSync*/);
    }

    private void verifyInputViewStatusOnMainSync(Runnable runnable, boolean inputViewStarted)
            throws InterruptedException {
        verifyInputViewStatusInternal(runnable, inputViewStarted, true /*runOnMainSync*/);
    }

    private void verifyInputViewStatusInternal(
            Runnable runnable, boolean inputViewStarted, boolean runOnMainSync)
            throws InterruptedException {
        CountDownLatch signal = new CountDownLatch(1);
        mInputMethodService.setCountDownLatchForTesting(signal);
        // Runnable to trigger onStartInputView()/ onFinishInputView()
        if (runOnMainSync) {
            mInstrumentation.runOnMainSync(runnable);
        } else {
            runnable.run();
        }
        // Waits for onStartInputView() to finish.
        mInstrumentation.waitForIdleSync();
        signal.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
        // Input is not finished.
        assertThat(mInputMethodService.getCurrentInputStarted()).isTrue();
        assertThat(mInputMethodService.getCurrentInputViewStarted()).isEqualTo(inputViewStarted);
    }

    @Test
    public void testFullScreenMode() throws Exception {
        Log.i(TAG, "Set orientation natural");
        verifyFullscreenMode(() -> setOrientation(0), true /* orientationPortrait */);

        Log.i(TAG, "Set orientation left");
        verifyFullscreenMode(() -> setOrientation(1), false /* orientationPortrait */);

        Log.i(TAG, "Set orientation right");
        verifyFullscreenMode(() -> setOrientation(2), false /* orientationPortrait */);

        mUiDevice.unfreezeRotation();
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

    private void verifyFullscreenMode(Runnable runnable, boolean orientationPortrait)
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
        signal.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);

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
        mEditText = mActivity.mEditText;
        Log.i(TAG, "Finish preparing activity with editor.");
    }

    private String getInputMethodId() {
        return mTargetPackageName + "/" + INPUT_METHOD_SERVICE_NAME;
    }

    private String executeShellCommand(String cmd) throws Exception {
        Log.i(TAG, "Run command: " + cmd);
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand(cmd);
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
