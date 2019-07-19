/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.wm;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.UiAutomation;

import org.junit.Before;

public class WindowManagerPerfTestBase {
    static final UiAutomation sUiAutomation = getInstrumentation().getUiAutomation();
    static final long NANOS_PER_S = 1000L * 1000 * 1000;
    static final long WARMUP_DURATION = 1 * NANOS_PER_S;
    static final long TEST_DURATION = 5 * NANOS_PER_S;

    @Before
    public void setUp() {
        // In order to be closer to the real use case.
        sUiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        sUiAutomation.executeShellCommand("wm dismiss-keyguard");
    }
}
