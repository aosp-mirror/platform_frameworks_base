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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import android.compat.testing.PlatformCompatChangeRule;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.MediumTest;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Tests for App Compat specific code about sizes.
 *
 * Build/Install/Run:
 *  atest WmTests:AppCompatSizeCompatTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatSizeCompatTests extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusEnabledUnsetProp() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ true);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusEnabledTrueProp() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().enable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ true);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusEnabledFalseProp() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusDisabledUnsetProp() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusDisabledTrueProp() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().enable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ true);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusDisabledFalseProp() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_compatFakeFocusEnabledFeatureDisabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCompatFakeFocus(/* enabled */ false);
            robot.activity().createActivityWithComponent();

            robot.putTopActivityInMultiWindowMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInPinnedWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);

            robot.putTopActivityInFreeformWindowingMode();
            robot.checkShouldSendCompatFakeFocus(/* expected */ false);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<SizeCompatRobotTest> consumer) {
        spyOn(mWm.mAppCompatConfiguration);
        final SizeCompatRobotTest robot = new SizeCompatRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class SizeCompatRobotTest extends AppCompatRobotBase {

        SizeCompatRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        void checkShouldSendCompatFakeFocus(boolean expected) {
            Assert.assertEquals(expected, activity().top().shouldSendCompatFakeFocus());
        }

        void putTopActivityInMultiWindowMode() {
            applyOnActivity((a) -> {
                a.setTopActivityInMultiWindowMode(true);
                a.setTopActivityInFreeformWindowingMode(false);
                a.setTopActivityInPinnedWindowingMode(false);
            });
        }

        void putTopActivityInPinnedWindowingMode() {
            applyOnActivity((a) -> {
                a.setTopActivityInMultiWindowMode(false);
                a.setTopActivityInPinnedWindowingMode(true);
                a.setTopActivityInFreeformWindowingMode(false);
            });
        }

        void putTopActivityInFreeformWindowingMode() {
            applyOnActivity((a) -> {
                a.setTopActivityInMultiWindowMode(false);
                a.setTopActivityInPinnedWindowingMode(false);
                a.setTopActivityInFreeformWindowingMode(true);
            });
        }
    }
}
