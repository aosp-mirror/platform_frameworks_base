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

import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH;
import static android.view.WindowManager.PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.window.flags.Flags.FLAG_CAMERA_COMPAT_FOR_FREEFORM;

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
import org.testng.Assert;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatCameraOverrides}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatCameraOverridesTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatCameraOverridesTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testShouldRefreshActivityForCameraCompat_flagIsDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(false);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldRefreshActivityForCameraCompat_overrideEnabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldRefreshActivityForCameraCompat_propertyIsTrueAndOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().enable(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityForCameraCompat(false);
        });
    }

    @Test
    public void testShouldRefreshActivityForCameraCompat_propertyIsFalse_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().disable(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityForCameraCompat(false);
        });
    }

    @Test
    public void testShouldRefreshActivityForCameraCompat_propertyIsTrue_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().enable(PROPERTY_CAMERA_COMPAT_ALLOW_REFRESH);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityForCameraCompat(true);
        });
    }


    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_flagIsDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(false);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityViaPauseForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityViaPauseForCameraCompat(true);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE})
    public void testShouldRefreshActivityViaPauseForCameraCompat_propertyFalseAndOverrideFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().disable(PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityViaPauseForCameraCompat(false);
        });
    }

    @Test
    public void testShouldRefreshActivityViaPauseForCameraCompat_propertyIsTrue_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().enable(PROPERTY_CAMERA_COMPAT_ENABLE_REFRESH_VIA_PAUSE);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldRefreshActivityViaPauseForCameraCompat(true);
        });
    }

    @Test
    public void testShouldForceRotateForCameraCompat_flagIsDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(false);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldForceRotateForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION})
    public void testShouldForceRotateForCameraCompat_overrideEnabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldForceRotateForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FORCE_ROTATION})
    public void testShouldForceRotateForCameraCompat_propertyIsTrueAndOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().enable(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldForceRotateForCameraCompat(false);
        });
    }

    @Test
    public void testShouldForceRotateForCameraCompat_propertyIsFalse_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().disable(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldForceRotateForCameraCompat(false);
        });
    }

    @Test
    public void testShouldForceRotateForCameraCompat_propertyIsTrue_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatTreatment(true);
            robot.prop().enable(PROPERTY_CAMERA_COMPAT_ALLOW_FORCE_ROTATION);
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldForceRotateForCameraCompat(true);
        });
    }

    @Test
    @DisableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testShouldApplyCameraCompatFreeformTreatment_flagIsDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldApplyFreeformTreatmentForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT})
    @EnableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testShouldApplyCameraCompatFreeformTreatment_overrideEnabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldApplyFreeformTreatmentForCameraCompat(false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_FREEFORM_WINDOWING_TREATMENT})
    @EnableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testShouldApplyCameraCompatFreeformTreatment_disabledByOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldApplyFreeformTreatmentForCameraCompat(false);
        });
    }

    @Test
    @EnableFlags(FLAG_CAMERA_COMPAT_FOR_FREEFORM)
    public void testShouldApplyCameraCompatFreeformTreatment_notDisabledByOverride_returnsTrue() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponentInNewTask();

            robot.checkShouldApplyFreeformTreatmentForCameraCompat(true);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA,
            OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void testShouldRecomputeConfigurationForCameraCompat() {
        runTestScenario((robot) -> {
            robot.conf().enableCameraCompatSplitScreenAspectRatio(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTask();
                a.setShouldCreateCompatDisplayInsets(false);
            });

            robot.checkShouldApplyFreeformTreatmentForCameraCompat(true);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.checkIsOverrideMinAspectRatioForCameraEnabled(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyTrue_overrideEnabled_returnsTrue() {
        runTestScenario((robot) -> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkIsOverrideMinAspectRatioForCameraEnabled(/* expected */ true);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyTrue_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkIsOverrideMinAspectRatioForCameraEnabled(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.checkIsOverrideMinAspectRatioForCameraEnabled(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyFalse_overrideEnabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkIsOverrideMinAspectRatioForCameraEnabled(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyFalse_noOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkIsOverrideMinAspectRatioForCameraEnabled(/* expected */ false);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<CameraOverridesRobotTest> consumer) {
        final CameraOverridesRobotTest robot = new CameraOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class CameraOverridesRobotTest extends AppCompatRobotBase {

        CameraOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        @Override
        void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
            super.onPostDisplayContentCreation(displayContent);
            spyOn(displayContent.mAppCompatCameraPolicy);
        }

        void checkShouldRefreshActivityForCameraCompat(boolean expected) {
            Assert.assertEquals(getAppCompatCameraOverrides()
                    .shouldRefreshActivityForCameraCompat(), expected);
        }

        void checkShouldRefreshActivityViaPauseForCameraCompat(boolean expected) {
            Assert.assertEquals(getAppCompatCameraOverrides()
                    .shouldRefreshActivityViaPauseForCameraCompat(), expected);
        }

        void checkShouldForceRotateForCameraCompat(boolean expected) {
            Assert.assertEquals(getAppCompatCameraOverrides()
                    .shouldForceRotateForCameraCompat(), expected);
        }

        void checkShouldApplyFreeformTreatmentForCameraCompat(boolean expected) {
            Assert.assertEquals(getAppCompatCameraOverrides()
                    .shouldApplyFreeformTreatmentForCameraCompat(), expected);
        }

        void checkIsOverrideMinAspectRatioForCameraEnabled(boolean expected) {
            Assert.assertEquals(getAppCompatCameraOverrides()
                    .isOverrideMinAspectRatioForCameraEnabled(), expected);
        }

        private AppCompatCameraOverrides getAppCompatCameraOverrides() {
            return activity().top().mAppCompatController.getAppCompatCameraOverrides();
        }
    }
}
