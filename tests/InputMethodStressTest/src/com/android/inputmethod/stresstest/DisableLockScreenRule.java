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

package com.android.inputmethod.stresstest;

import android.support.test.uiautomator.UiDevice;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.IOException;

/** Disable lock screen during the test. */
public class DisableLockScreenRule extends TestWatcher {
    private static final String LOCK_SCREEN_OFF_COMMAND = "locksettings set-disabled true";
    private static final String LOCK_SCREEN_ON_COMMAND = "locksettings set-disabled false";

    private final UiDevice mUiDevice =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    @Override
    protected void starting(Description description) {
        try {
            mUiDevice.executeShellCommand(LOCK_SCREEN_OFF_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not disable lock screen.", e);
        }
    }

    @Override
    protected void finished(Description description) {
        try {
            mUiDevice.executeShellCommand(LOCK_SCREEN_ON_COMMAND);
        } catch (IOException e) {
            throw new RuntimeException("Could not enable lock screen.", e);
        }
    }
}
