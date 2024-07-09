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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.window.flags.Flags.FLAG_CAMERA_COMPAT_FOR_FREEFORM;

import static org.mockito.ArgumentMatchers.any;

import android.compat.testing.PlatformCompatChangeRule;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatCameraPolicy}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatCameraPolicyTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatCameraPolicyTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testDisplayRotationCompatPolicy_presentWhenEnabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(true);
        });
    }

    @Test
    public void testDisplayRotationCompatPolicy_notPresentWhenDisabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(false);
        });
    }

    @Test
    @EnableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testCameraCompatFreeformPolicy_presentWhenEnabledAndDW() {
        runTestScenario((robot) -> {
            robot.allowEnterDesktopMode(true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(true);
        });
    }

    @Test
    @EnableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testCameraCompatFreeformPolicy_notPresentWhenNoDW() {
        runTestScenario((robot) -> {
            robot.allowEnterDesktopMode(false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(false);
        });
    }

    @Test
    @DisableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testCameraCompatFreeformPolicy_notPresentWhenNoFlag() {
        runTestScenario((robot) -> {
            robot.allowEnterDesktopMode(true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(false);
        });
    }

    @Test
    @EnableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testCameraCompatFreeformPolicy_notPresentWhenNoFlagAndNoDW() {
        runTestScenario((robot) -> {
            robot.allowEnterDesktopMode(false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(false);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<DisplayRotationPolicyRobotTest> consumer) {
        spyOn(mWm.mLetterboxConfiguration);
        final DisplayRotationPolicyRobotTest robot =
                new DisplayRotationPolicyRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    @Test
    public void testIsCameraCompatTreatmentActive_whenTreatmentForTopActivityIsEnabled() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a)-> {
                a.createActivityWithComponent();
                a.enableTreatmentForTopActivity(/* enabled */ true);
            });

            robot.checkIsCameraCompatTreatmentActiveForTopActivity(/* active */ true);
        });
    }

    @Test
    public void testIsCameraCompatTreatmentNotActive_whenTreatmentForTopActivityIsDisabled() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a)-> {
                a.createActivityWithComponent();
                a.enableTreatmentForTopActivity(/* enabled */ false);
            });

            robot.checkIsCameraCompatTreatmentActiveForTopActivity(/* active */ false);
        });
    }

    private static class DisplayRotationPolicyRobotTest extends AppCompatRobotBase {

        DisplayRotationPolicyRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        void checkTopActivityHasDisplayRotationCompatPolicy(boolean exists) {
            Assert.assertEquals(exists, activity().top().mDisplayContent
                    .mAppCompatCameraPolicy.hasDisplayRotationCompatPolicy());
        }

        void checkTopActivityHasCameraCompatFreeformPolicy(boolean exists) {
            Assert.assertEquals(exists, activity().top().mDisplayContent
                    .mAppCompatCameraPolicy.hasCameraCompatFreeformPolicy());
        }

        void checkIsCameraCompatTreatmentActiveForTopActivity(boolean active) {
            Assert.assertEquals(getTopAppCompatCameraPolicy()
                    .isTreatmentEnabledForActivity(activity().top()), active);
        }

        // TODO(b/350460645): Create Desktop Windowing Robot to reuse common functionalities.
        void allowEnterDesktopMode(boolean isAllowed) {
            doReturn(isAllowed).when(() ->
                    DesktopModeLaunchParamsModifier.canEnterDesktopMode(any()));
        }

        private AppCompatCameraPolicy getTopAppCompatCameraPolicy() {
            return activity().top().mDisplayContent.mAppCompatCameraPolicy;
        }
    }
}
