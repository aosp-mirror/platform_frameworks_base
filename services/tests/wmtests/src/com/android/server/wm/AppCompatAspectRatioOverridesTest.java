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

import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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
 * Test class for {@link AppCompatAspectRatioOverrides}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatAspectRatioOverridesTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatAspectRatioOverridesTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testShouldApplyUserFullscreenOverride_trueProperty_returnsFalse() {
        runTestScenario((robot)-> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE);
            robot.conf().enableUserAppAspectRatioFullscreen(/* enabled */ false);

            robot.activity().createActivityWithComponent();

            robot.checkShouldApplyUserFullscreenOverride(/* expected */ false);
        });
    }

    @Test
    public void testShouldApplyUserFullscreenOverride_falseFullscreenProperty_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioFullscreen(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);

            robot.checkShouldApplyUserFullscreenOverride(/* expected */ false);
        });
    }

    @Test
    public void testShouldApplyUserFullscreenOverride_falseSettingsProperty_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);
            robot.checkShouldApplyUserFullscreenOverride(/* expected */ false);
        });
    }


    @Test
    public void testShouldApplyUserFullscreenOverride_returnsTrue() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioFullscreen(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);

            robot.checkShouldApplyUserFullscreenOverride(/* expected */ true);
        });
    }

    @Test
    public void testShouldEnableUserAspectRatioSettings_falseProperty_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldEnableUserAspectRatioSettings(/* expected */ false);
        });
    }

    @Test
    public void testShouldEnableUserAspectRatioSettings_trueProperty_returnsTrue() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldEnableUserAspectRatioSettings(/* expected */ true);
        });
    }

    @Test
    public void testShouldEnableUserAspectRatioSettings_ignoreOrientation_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ false);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldEnableUserAspectRatioSettings(/* expected */ false);
        });
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_falseProperty_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldEnableUserAspectRatioSettings(/* expected */ false);
        });
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_trueProperty_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ false);
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkShouldEnableUserAspectRatioSettings(/* enabled */ false);
        });
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_disabledIgnoreOrientationRequest() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ false);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldApplyUserMinAspectRatioOverride(/* expected */ false);
        });
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_returnsTrue() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldApplyUserMinAspectRatioOverride(/* expected */ true);
        });
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_ignoreOrientation_returnsFalse() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ false);
            robot.activity().setIgnoreOrientationRequest(/* enabled */ true);
            robot.activity().createActivityWithComponent();
            robot.activity().setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

            robot.checkShouldApplyUserMinAspectRatioOverride(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testShouldOverrideMinAspectRatio_overrideEnabled_returnsTrue() {
        runTestScenario((robot)-> {
            robot.activity().createActivityWithComponent();

            robot.checkShouldOverrideMinAspectRatio(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testShouldOverrideMinAspectRatio_propertyTrue_overrideEnabled_returnsTrue() {
        runTestScenario((robot)-> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkShouldOverrideMinAspectRatio(/* expected */ true);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testShouldOverrideMinAspectRatio_propertyTrue_overrideDisabled_returnsFalse() {
        runTestScenario((robot)-> {
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkShouldOverrideMinAspectRatio(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testShouldOverrideMinAspectRatio_overrideDisabled_returnsFalse() {
        runTestScenario((robot)-> {
            robot.activity().createActivityWithComponent();

            robot.checkShouldOverrideMinAspectRatio(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyFalse_overrideEnabled_returnsFalse() {
        runTestScenario((robot)-> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkShouldOverrideMinAspectRatio(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyFalse_noOverride_returnsFalse() {
        runTestScenario((robot)-> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);
            robot.activity().createActivityWithComponent();

            robot.checkShouldOverrideMinAspectRatio(/* expected */ false);
        });
    }

    @Test
    public void testGetFixedOrientationLetterboxAspectRatio_splitScreenAspectEnabled() {
        runTestScenario((robot)-> {
            robot.applyOnConf((c) -> {
                c.enableCameraCompatTreatment(/* enabled */ true);
                c.enableCameraCompatTreatmentAtBuildTime(/* enabled */ true);
                c.enableCameraCompatSplitScreenAspectRatio(/* enabled */ true);
                c.enableDisplayAspectRatioEnabledForFixedOrientationLetterbox(/* enabled */ false);
                c.setFixedOrientationLetterboxAspectRatio(/* aspectRatio */ 1.5f);
            });
            robot.activity().createActivityWithComponentInNewTaskAndDisplay();
            robot.checkFixedOrientationLetterboxAspectRatioForTopParent(/* expected */ 1.5f);

            robot.activity().enableTreatmentForTopActivity(/* enabled */ true);
            robot.checkAspectRatioForTopParentIsSplitScreenRatio(/* expected */ true);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<AspectRatioOverridesRobotTest> consumer) {
        final AspectRatioOverridesRobotTest robot =
                new AspectRatioOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class AspectRatioOverridesRobotTest extends AppCompatRobotBase {

        AspectRatioOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        @Override
        void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
            super.onPostDisplayContentCreation(displayContent);
            spyOn(displayContent.mAppCompatCameraPolicy);
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getAppCompatAspectRatioOverrides());
        }

        void checkShouldApplyUserFullscreenOverride(boolean expected) {
            assertEquals(expected, getTopActivityAppCompatAspectRatioOverrides()
                    .shouldApplyUserFullscreenOverride());
        }

        void checkShouldEnableUserAspectRatioSettings(boolean expected) {
            assertEquals(expected, getTopActivityAppCompatAspectRatioOverrides()
                    .shouldEnableUserAspectRatioSettings());
        }

        void checkShouldApplyUserMinAspectRatioOverride(boolean expected) {
            assertEquals(expected, getTopActivityAppCompatAspectRatioOverrides()
                    .shouldApplyUserMinAspectRatioOverride());
        }

        void checkShouldOverrideMinAspectRatio(boolean expected) {
            assertEquals(expected, getTopActivityAppCompatAspectRatioOverrides()
                    .shouldOverrideMinAspectRatio());
        }

        @NonNull
        void checkFixedOrientationLetterboxAspectRatioForTopParent(float expected) {
            assertEquals(expected,
                    getTopActivityAppCompatAspectRatioOverrides()
                            .getFixedOrientationLetterboxAspectRatio(
                                    activity().top().getParent().getConfiguration()),
                                        FLOAT_TOLLERANCE);
        }

        void checkAspectRatioForTopParentIsSplitScreenRatio(boolean expected) {
            final AppCompatAspectRatioOverrides aspectRatioOverrides =
                    getTopActivityAppCompatAspectRatioOverrides();
            if (expected) {
                assertEquals(aspectRatioOverrides.getSplitScreenAspectRatio(),
                        aspectRatioOverrides.getFixedOrientationLetterboxAspectRatio(
                                activity().top().getParent().getConfiguration()), FLOAT_TOLLERANCE);
            } else {
                assertNotEquals(aspectRatioOverrides.getSplitScreenAspectRatio(),
                        aspectRatioOverrides.getFixedOrientationLetterboxAspectRatio(
                                activity().top().getParent().getConfiguration()), FLOAT_TOLLERANCE);
            }
        }

        private AppCompatAspectRatioOverrides getTopActivityAppCompatAspectRatioOverrides() {
            return activity().top().mAppCompatController.getAppCompatAspectRatioOverrides();
        }
    }

}
