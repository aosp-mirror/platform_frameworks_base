/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;

/**
 * Base class that does shell test case setup.
 */
public abstract class ShellTestCase {

    protected TestableContext mContext;

    @Before
    public void shellSetup() {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final DisplayManager dm = context.getSystemService(DisplayManager.class);
        mContext = new TestableContext(
                context.createDisplayContext(dm.getDisplay(DEFAULT_DISPLAY)));

        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
    }

    @After
    public void shellTearDown() {
        InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    protected Context getContext() {
        return mContext;
    }
}
