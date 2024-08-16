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

import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.view.WindowManager.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS;

import android.compat.testing.PlatformCompatChangeRule;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.testng.Assert;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatFocusOverrides}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatFocusOverridesTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatFocusOverridesTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideEnabled_inMultiWindow_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ true);
            });

            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideEnabled_noMultiWindowMode_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ false);
            });

            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideEnabled_pinnedWindowMode_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInPinnedWindowingMode(/* multiWindowMode */ true);
            });

            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideEnabled_freeformMode_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInFreeformWindowingMode(/* freeformWindowingMode */ true);
            });

            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ true);
            });
            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testIsCompatFakeFocusEnabled_propertyDisabledAndOverrideOn_fakeFocusDisabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ true);
            });
            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testIsCompatFakeFocusEnabled_propertyEnabled_noOverride_fakeFocusEnabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().enable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ true);
            });
            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ true);
        });
    }

    @Test
    public void testIsCompatFakeFocusEnabled_propertyDisabled_fakeFocusDisabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ true);
            });
            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ false);
        });
    }

    @Test
    public void testIsCompatFakeFocusEnabled_propertyEnabled_fakeFocusEnabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().enable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInMultiWindowMode(/* multiWindowMode */ true);
            });
            robot.checkShouldSendFakeFocusOnTopActivity(/* expected */ true);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<FocusOverridesRobotTest> consumer) {
        final FocusOverridesRobotTest robot = new FocusOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class FocusOverridesRobotTest extends AppCompatRobotBase {

        FocusOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        void checkShouldSendFakeFocusOnTopActivity(boolean expected) {
            Assert.assertEquals(activity().top().mAppCompatController.getAppCompatFocusOverrides()
                    .shouldSendFakeFocus(), expected);
        }
    }

}
