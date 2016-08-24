/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.windowanimationjank;

import java.io.IOException;
import java.util.StringTokenizer;

import android.support.test.jank.JankTestBase;
import android.support.test.uiautomator.UiDevice;

/**
 * This adds additional system level jank monitor and its result is merged with primary monitor
 * used in test.
 */
public abstract class WindowAnimationJankTestBase extends JankTestBase {
    private static final String TAG = "WindowAnimationJankTestBase";

    protected WindowAnimationJankTestBase() {
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // fix device orientation
        getUiDevice().setOrientationNatural();

        // Start from the home screen
        getUiDevice().pressHome();
        getUiDevice().waitForIdle();
    }

    @Override
    protected void tearDown() throws Exception {
        getUiDevice().unfreezeRotation();
        super.tearDown();
    }

    protected UiDevice getUiDevice() {
        return UiDevice.getInstance(getInstrumentation());
    }
}
