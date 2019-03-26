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
 *     -e filter com.android.server.wm.test.filters.FrameworksTestsFilter  \
 *     -e selectTest_verbose true \
 *     com.android.frameworks.coretests/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 */
public final class FrameworksTestsFilter extends SelectTest {

    private static final String[] SELECTED_TESTS = {
            // Test specifications for FrameworksCoreTests.
            "android.app.servertransaction.", // all tests under the package.
            "android.view.DisplayCutoutTest",
            // Test specifications for FrameworksServicesTests.
            "com.android.server.policy.", // all tests under the package.
            "com.android.server.am.ActivityLaunchParamsModifierTests",
            "com.android.server.am.ActivityRecordTests",
            "com.android.server.am.ActivityStackSupervisorTests",
            "com.android.server.am.ActivityStackTests",
            "com.android.server.am.ActivityStartControllerTests",
            "com.android.server.am.ActivityStarterTests",
            "com.android.server.am.ActivityStartInterceptorTest",
            "com.android.server.am.AssistDataRequesterTest",
            "com.android.server.am.ClientLifecycleManagerTests",
            "com.android.server.am.LaunchParamsControllerTests",
            "com.android.server.am.PendingRemoteAnimationRegistryTest",
            "com.android.server.am.RecentsAnimationTest",
            "com.android.server.am.RecentTasksTest",
            "com.android.server.am.RunningTasksTest",
            "com.android.server.am.SafeActivityOptionsTest",
            "com.android.server.am.TaskLaunchParamsModifierTests",
            "com.android.server.am.TaskPersisterTest",
            "com.android.server.am.TaskRecordTests",
            "com.android.server.am.TaskStackChangedListenerTest",
    };

    public FrameworksTestsFilter(Bundle testArgs) {
        super(addSelectTest(testArgs, SELECTED_TESTS));
    }
}
