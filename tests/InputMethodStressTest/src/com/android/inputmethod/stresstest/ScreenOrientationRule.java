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

import android.os.RemoteException;
import android.support.test.uiautomator.UiDevice;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

import java.io.IOException;

/**
 * Disable auto-rotate during the test and set the screen orientation to portrait or landscape
 * before the test starts.
 */
public class ScreenOrientationRule extends TestWatcher {
    private static final String SET_PORTRAIT_MODE_CMD = "settings put system user_rotation 0";
    private static final String SET_LANDSCAPE_MODE_CMD = "settings put system user_rotation 1";

    private final boolean mIsPortrait;
    private final UiDevice mUiDevice =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    ScreenOrientationRule(boolean isPortrait) {
        mIsPortrait = isPortrait;
    }

    @Override
    protected void starting(Description description) {
        try {
            mUiDevice.freezeRotation();
            mUiDevice.executeShellCommand(mIsPortrait ? SET_PORTRAIT_MODE_CMD :
                    SET_LANDSCAPE_MODE_CMD);
        } catch (IOException e) {
            throw new RuntimeException("Could not set screen orientation.", e);
        } catch (RemoteException e) {
            throw new RuntimeException("Could not freeze rotation.", e);
        }
    }

    @Override
    protected void finished(Description description) {
        try {
            mUiDevice.unfreezeRotation();
        } catch (RemoteException e) {
            throw new RuntimeException("Could not unfreeze screen rotation.", e);
        }
    }
}
