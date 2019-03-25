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

package com.android.server.wm.test.filters;

import android.os.Bundle;

import com.android.test.filters.SelectTest;

/**
 * JUnit test filter that select Window Manager Service related tests from FrameworksCoreTests.
 *
 * <p>Use this filter when running FrameworksCoreTests as
 * <pre>
 * adb shell am instrument -w \
 *     -e filter com.android.server.wm.test.filters.CoreTestsFilter  \
 *     -e selectTest_verbose true \
 *     com.android.frameworks.coretests/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
public final class CoreTestsFilter extends SelectTest {

    private static final String[] SELECTED_CORE_TESTS = {
            "android.app.servertransaction.", // all tests under the package.
            "android.view.DisplayCutoutTest",
            "android.view.InsetsControllerTest",
            "android.view.InsetsSourceTest",
            "android.view.InsetsSourceConsumerTest",
            "android.view.InsetsStateTest",
    };

    public CoreTestsFilter(Bundle testArgs) {
        super(addSelectTest(testArgs, SELECTED_CORE_TESTS));
    }
}
