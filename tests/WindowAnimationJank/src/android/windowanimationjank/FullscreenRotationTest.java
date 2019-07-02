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

import android.os.Bundle;

import androidx.test.jank.GfxMonitor;
import androidx.test.jank.JankTest;

/**
 * Detect janks during screen rotation for full-screen activity. Periodically change
 * orientation from left to right and track ElementLayoutActivity rendering performance
 * via GfxMonitor.
 */
public class FullscreenRotationTest extends WindowAnimationJankTestBase {
    private final static int STEP_CNT = 3;

    @Override
    public void beforeTest() throws Exception {
        getUiDevice().setOrientationLeft();
        Utils.startElementLayout(getInstrumentation(), 100);
        super.beforeTest();
    }

    @Override
    public void afterTest(Bundle metrics) {
        Utils.rotateDevice(getInstrumentation(), Utils.ROTATION_MODE_NATURAL);
        super.afterTest(metrics);
    }

    @JankTest(expectedFrames=100, defaultIterationCount=2)
    @GfxMonitor(processName=Utils.PACKAGE)
    public void testRotation() throws Exception {
        for (int i = 0; i < STEP_CNT; ++i) {
            Utils.rotateDevice(getInstrumentation(),
                    Utils.getDeviceRotation(getInstrumentation()) == Utils.ROTATION_MODE_LEFT ?
                    Utils.ROTATION_MODE_RIGHT : Utils.ROTATION_MODE_LEFT);
        }
    }
}
