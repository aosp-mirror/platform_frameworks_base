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
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.compat.testing.PlatformCompatChangeRule;
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
    @DisableCompatChanges({OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testRecomputeConfigurationForCameraCompatIfNeeded_allDisabledNoRecompute() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.conf().enableCameraCompatSplitScreenAspectRatio(false);
            robot.activateCamera(/* isCameraActive */ false);

            robot.recomputeConfigurationForCameraCompatIfNeeded();
            robot.checkRecomputeConfigurationInvoked(/* invoked */ false);

        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testRecomputeConfigurationForCameraCompatIfNeeded_cameraEnabledRecompute() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.conf().enableCameraCompatSplitScreenAspectRatio(false);
            robot.activateCamera(/* isCameraActive */ false);

            robot.recomputeConfigurationForCameraCompatIfNeeded();
            robot.checkRecomputeConfigurationInvoked(/* invoked */ true);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testRecomputeConfigurationForCameraSplitScreenCompatIfNeeded_recompute() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.conf().enableCameraCompatSplitScreenAspectRatio(true);
            robot.activateCamera(/* isCameraActive */ false);

            robot.recomputeConfigurationForCameraCompatIfNeeded();
            robot.checkRecomputeConfigurationInvoked(/* invoked */ true);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void testRecomputeConfigurationForCameraSplitScreenCompatIfNeededWithCamera_recompute() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.conf().enableCameraCompatSplitScreenAspectRatio(false);
            robot.activateCamera(/* isCameraActive */ true);

            robot.recomputeConfigurationForCameraCompatIfNeeded();
            robot.checkRecomputeConfigurationInvoked(/* invoked */ true);
        });
    }

    void runTestScenario(@NonNull Consumer<CameraPolicyRobotTest> consumer) {
        spyOn(mWm.mLetterboxConfiguration);
        final CameraPolicyRobotTest robot = new CameraPolicyRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class CameraPolicyRobotTest extends AppCompatRobotBase {

        private final WindowManagerService mWm;

        CameraPolicyRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
            mWm = wm;
            spyOn(mWm);
        }

        void activateCamera(boolean isCameraActive) {
            doReturn(isCameraActive).when(activity().top()).isCameraActive();
        }

        void recomputeConfigurationForCameraCompatIfNeeded() {
            getAppCompatCameraPolicy().recomputeConfigurationForCameraCompatIfNeeded();
        }

        void checkRecomputeConfigurationInvoked(boolean invoked) {
            if (invoked) {
                verify(activity().top()).recomputeConfiguration();
            } else {
                verify(activity().top(), never()).recomputeConfiguration();
            }
        }

        private AppCompatCameraPolicy getAppCompatCameraPolicy() {
            return activity().top().mAppCompatController.getAppCompatCameraPolicy();
        }
    }

}
