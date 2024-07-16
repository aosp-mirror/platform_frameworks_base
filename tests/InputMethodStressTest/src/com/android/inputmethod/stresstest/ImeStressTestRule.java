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

import android.app.Instrumentation;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.IOException;

/**
 * Do setup and cleanup for Ime stress tests, including disabling lock and auto-rotate screen,
 * pressing home and enabling a simple test Ime during the tests.
 */
public class ImeStressTestRule extends TestWatcher {
    private static final String LOCK_SCREEN_OFF_COMMAND = "locksettings set-disabled true";
    private static final String LOCK_SCREEN_ON_COMMAND = "locksettings set-disabled false";
    private static final String SIMPLE_IME_ID =
            "com.android.apps.inputmethod.simpleime/.SimpleInputMethodService";
    private static final String ENABLE_IME_COMMAND = "ime enable " + SIMPLE_IME_ID;
    private static final String SET_IME_COMMAND = "ime set " + SIMPLE_IME_ID;
    private static final String DISABLE_IME_COMMAND = "ime disable " + SIMPLE_IME_ID;
    private static final String RESET_IME_COMMAND = "ime reset";

    @NonNull
    private final Instrumentation mInstrumentation;
    @NonNull
    private final UiDevice mUiDevice;
    // Whether the screen orientation is set to portrait.
    private boolean mIsPortrait;
    // Whether to use a simple test Ime or system default Ime for test.
    private final boolean mUseSimpleTestIme;

    public ImeStressTestRule(boolean useSimpleTestIme) {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mUiDevice = UiDevice.getInstance(mInstrumentation);
        // Default is portrait mode
        mIsPortrait = true;
        mUseSimpleTestIme = useSimpleTestIme;
    }

    public void setIsPortrait(boolean isPortrait) {
        mIsPortrait = isPortrait;
    }

    @Override
    protected void starting(Description description) {
        disableLockScreen();
        setOrientation();
        mUiDevice.pressHome();
        if (mUseSimpleTestIme) {
            enableSimpleIme();
        } else {
            resetImeToDefault();
        }

        mInstrumentation.waitForIdleSync();
    }

    @Override
    protected void finished(Description description) {
        if (mUseSimpleTestIme) {
            disableSimpleIme();
        }
        unfreezeRotation();
        restoreLockScreen();
    }

    private void disableLockScreen() {
        try {
            executeShellCommand(LOCK_SCREEN_OFF_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not disable lock screen.", e);
        }
    }

    private void restoreLockScreen() {
        try {
            executeShellCommand(LOCK_SCREEN_ON_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not enable lock screen.", e);
        }
    }

    private void setOrientation() {
        try {
            mUiDevice.freezeRotation();
            if (mIsPortrait) {
                mUiDevice.setOrientationNatural();
            } else {
                mUiDevice.setOrientationLeft();
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Could not freeze rotation or set screen orientation.", e);
        }
    }

    private void unfreezeRotation() {
        try {
            mUiDevice.unfreezeRotation();
        } catch (RemoteException e) {
            throw new RuntimeException("Could not unfreeze screen rotation.", e);
        }
    }

    private void enableSimpleIme() {
        try {
            executeShellCommand(ENABLE_IME_COMMAND);
            executeShellCommand(SET_IME_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not enable SimpleTestIme.", e);
        }
    }

    private void disableSimpleIme() {
        try {
            executeShellCommand(DISABLE_IME_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not disable SimpleTestIme.", e);
        }
    }

    private void resetImeToDefault() {
        try {
            executeShellCommand(RESET_IME_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not reset Ime to default.", e);
        }
    }

    @NonNull
    private String executeShellCommand(@NonNull String cmd) throws IOException {
        return mUiDevice.executeShellCommand(cmd);
    }
}
