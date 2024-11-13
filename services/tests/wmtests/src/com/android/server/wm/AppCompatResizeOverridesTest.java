/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.FORCE_NON_RESIZE_APP;
import static android.content.pm.ActivityInfo.FORCE_RESIZE_APP;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;

import android.compat.testing.PlatformCompatChangeRule;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import libcore.junit.util.compat.CoreCompatChangeRule;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatResizeOverrides}.
 * <p/>
 * Build/Install/Run:
 * atest WmTests:AppCompatResizeOverridesTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatResizeOverridesTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    @CoreCompatChangeRule.EnableCompatChanges({FORCE_RESIZE_APP})
    public void testShouldOverrideForceResizeApp_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceResizeApp(/* expected */ true);
        });
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges({FORCE_RESIZE_APP})
    public void testShouldOverrideForceResizeApp_propertyTrue_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceResizeApp(/* expected */ true);
        });
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({FORCE_RESIZE_APP})
    public void testShouldOverrideForceResizeApp_propertyTrue_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceResizeApp(/* expected */ false);
        });
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({FORCE_RESIZE_APP})
    public void testShouldOverrideForceResizeApp_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceResizeApp(/* expected */ false);
        });
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges({FORCE_RESIZE_APP})
    public void testShouldOverrideForceResizeApp_propertyFalse_overrideEnabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceResizeApp(/* expected */ false);
        });
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({FORCE_RESIZE_APP})
    public void testShouldOverrideForceResizeApp_propertyFalse_noOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceResizeApp(/* expected */ false);
        });
    }


    @Test
    @CoreCompatChangeRule.EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testShouldOverrideForceNonResizeApp_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceNonResizeApp(/* expected */ true);
        });
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testShouldOverrideForceNonResizeApp_propertyTrue_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceNonResizeApp(/* expected */ true);
        });
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testShouldOverrideForceNonResizeApp_propertyTrue_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceNonResizeApp(/* expected */ false);
        });
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testShouldOverrideForceNonResizeApp_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceNonResizeApp(/* expected */ false);
        });
    }

    @Test
    @CoreCompatChangeRule.EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testShouldOverrideForceNonResizeApp_propertyFalse_overrideEnabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceNonResizeApp(/* expected */ false);
        });
    }

    @Test
    @CoreCompatChangeRule.DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testShouldOverrideForceNonResizeApp_propertyFalse_noOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
            robot.activity().createActivityWithComponent();
            robot.checkShouldOverrideForceNonResizeApp(/* expected */ false);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<ResizeOverridesRobotTest> consumer) {
        final ResizeOverridesRobotTest robot = new ResizeOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class ResizeOverridesRobotTest extends AppCompatRobotBase {

        ResizeOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }


        void checkShouldOverrideForceResizeApp(boolean expected) {
            Assert.assertEquals(expected, activity().top().mAppCompatController
                    .getAppCompatResizeOverrides().shouldOverrideForceResizeApp());
        }

        void checkShouldOverrideForceNonResizeApp(boolean expected) {
            Assert.assertEquals(expected, activity().top().mAppCompatController
                    .getAppCompatResizeOverrides().shouldOverrideForceNonResizeApp());
        }
    }

}
