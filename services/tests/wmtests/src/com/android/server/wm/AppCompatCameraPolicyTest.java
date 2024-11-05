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

import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.AppCompatCameraPolicy.isTreatmentEnabledForActivity;
import static com.android.server.wm.AppCompatCameraPolicy.shouldOverrideMinAspectRatioForCamera;
import static com.android.window.flags.Flags.FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.compat.testing.PlatformCompatChangeRule;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

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
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(/* exists= */ true);
        });
    }

    @Test
    public void testDisplayRotationCompatPolicy_notPresentWhenDisabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(/* exists= */ false);
        });
    }

    @Test
    public void testDisplayRotationCompatPolicy_startedWhenEnabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(/* exists= */ true);
            robot.checkTopActivityDisplayRotationCompatPolicyIsRunning();
        });
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraCompatFreeformPolicy_presentWhenEnabledAndDW() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(/* isAllowed= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ true);
        });
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraCompatFreeformPolicy_notPresentWhenNoDW() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(/* isAllowed= */ false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ false);
        });
    }

    @Test
    @DisableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraCompatFreeformPolicy_notPresentWhenNoFlag() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(/* isAllowed= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ false);
        });
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraCompatFreeformPolicy_notPresentWhenNoFlagAndNoDW() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(/* isAllowed= */ false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ false);
        });
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraCompatFreeformPolicy_startedWhenEnabledAndDW() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(/* isAllowed= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ true);
            robot.checkTopActivityCameraCompatFreeformPolicyIsRunning();
        });
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraStateManager_existsWhenCameraCompatFreeformExists() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ true);
            robot.checkTopActivityHasCameraStateMonitor(/* exists= */ true);
        });
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraStateManager_startedWhenCameraCompatFreeformExists() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ true);
            robot.checkTopActivityHasCameraStateMonitor(/* exists= */ true);
            robot.checkTopActivityCameraStateMonitorIsRunning();
        });
    }

    @Test
    public void testCameraStateManager_existsWhenDisplayRotationCompatPolicyExists() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(/* exists= */ true);
            robot.checkTopActivityHasCameraStateMonitor(/* exists= */ true);
        });
    }

    @Test
    public void testCameraStateManager_startedWhenDisplayRotationCompatPolicyExists() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(/* exists= */ true);
            robot.checkTopActivityHasCameraStateMonitor(/* exists= */ true);
            robot.checkTopActivityCameraStateMonitorIsRunning();
        });
    }

    @Test
    @DisableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testCameraStateManager_doesNotExistWhenNoPolicyExists() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ false);
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkTopActivityHasDisplayRotationCompatPolicy(/* exists= */ false);
            robot.checkTopActivityHasCameraCompatFreeformPolicy(/* exists= */ false);
            robot.checkTopActivityHasCameraStateMonitor(/* exists= */ false);
        });
    }

    @Test
    public void testIsCameraCompatTreatmentActive_whenTreatmentForTopActivityIsEnabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
            robot.applyOnActivity((a)-> {
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.enableFullscreenCameraCompatTreatmentForTopActivity(/* enabled */ true);
            });

            robot.checkIsCameraCompatTreatmentActiveForTopActivity(/* active */ true);
        });
    }

    @Test
    public void testIsCameraCompatTreatmentNotActive_whenTreatmentForTopActivityIsDisabled() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
            robot.applyOnActivity((a)-> {
                a.createActivityWithComponent();
                a.enableFullscreenCameraCompatTreatmentForTopActivity(/* enabled */ false);
            });

            robot.checkIsCameraCompatTreatmentActiveForTopActivity(/* active */ false);
        });
    }

    @Test
    @EnableCompatChanges(OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA)
    public void testShouldOverrideMinAspectRatioForCamera_whenCameraIsNotRunning() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a)-> {
                robot.dw().allowEnterDesktopMode(true);
                robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setIsCameraRunningAndWindowingModeEligibleFullscreen(/* enabled */ false);
            });

            robot.checkShouldOverrideMinAspectRatioForCamera(/* active */ false);
        });
    }

    @Test
    @DisableCompatChanges(OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA)
    public void testShouldOverrideMinAspectRatioForCamera_whenCameraIsRunning_overrideDisabled() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a)-> {
                robot.dw().allowEnterDesktopMode(true);
                robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setIsCameraRunningAndWindowingModeEligibleFullscreen(/* active */ true);
            });

            robot.checkShouldOverrideMinAspectRatioForCamera(/* active */ false);
        });
    }

    @Test
    @EnableCompatChanges(OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA)
    public void testShouldOverrideMinAspectRatioForCameraFullscr_cameraIsRunning_overrideEnabled() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a)-> {
                robot.conf().enableCameraCompatTreatmentAtBuildTime(/* enabled= */ true);
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setIsCameraRunningAndWindowingModeEligibleFullscreen(/* active */ true);
            });

            robot.checkShouldOverrideMinAspectRatioForCamera(/* active */ true);
        });
    }


    @Test
    @EnableCompatChanges(OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA)
    @EnableFlags(FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void testShouldOverrideMinAspectRatioForCameraFreeform_cameraRunning_overrideEnabled() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a)-> {
                robot.dw().allowEnterDesktopMode(true);
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setIsCameraRunningAndWindowingModeEligibleFreeform(/* active */ true);
            });

            robot.checkShouldOverrideMinAspectRatioForCamera(/* active */ true);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<AppCompatCameraPolicyRobotTest> consumer) {
        final AppCompatCameraPolicyRobotTest robot =
                new AppCompatCameraPolicyRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }


    private static class AppCompatCameraPolicyRobotTest extends AppCompatRobotBase {
        AppCompatCameraPolicyRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        @Override
        void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
            super.onPostDisplayContentCreation(displayContent);

            spyOn(displayContent.mAppCompatCameraPolicy);
            if (displayContent.mAppCompatCameraPolicy.mDisplayRotationCompatPolicy != null) {
                spyOn(displayContent.mAppCompatCameraPolicy.mDisplayRotationCompatPolicy);
            }
            if (displayContent.mAppCompatCameraPolicy.mCameraCompatFreeformPolicy != null) {
                spyOn(displayContent.mAppCompatCameraPolicy.mCameraCompatFreeformPolicy);
            }
        }

        void checkTopActivityHasDisplayRotationCompatPolicy(boolean exists) {
            assertEquals(exists, activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .hasDisplayRotationCompatPolicy());
        }

        void checkTopActivityHasCameraCompatFreeformPolicy(boolean exists) {
            assertEquals(exists, activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .hasCameraCompatFreeformPolicy());
        }

        void checkTopActivityHasCameraStateMonitor(boolean exists) {
            assertEquals(exists, activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .hasCameraStateMonitor());
        }

        void checkTopActivityDisplayRotationCompatPolicyIsRunning() {
            assertTrue(activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .mDisplayRotationCompatPolicy.isRunning());
        }

        void checkTopActivityCameraCompatFreeformPolicyIsRunning() {
            assertTrue(activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .mCameraCompatFreeformPolicy.isRunning());
        }

        void checkTopActivityCameraStateMonitorIsRunning() {
            assertTrue(activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .mCameraStateMonitor.isRunning());
        }

        void checkIsCameraCompatTreatmentActiveForTopActivity(boolean active) {
            assertEquals(active, isTreatmentEnabledForActivity(activity().top()));
        }

        void checkShouldOverrideMinAspectRatioForCamera(boolean expected) {
            assertEquals(expected, shouldOverrideMinAspectRatioForCamera(activity().top()));
        }
    }
}
