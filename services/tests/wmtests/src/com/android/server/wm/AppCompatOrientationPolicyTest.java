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

import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION_TO_USER;
import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatOrientationPolicy}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatOrientationPolicyTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatOrientationPolicyTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testOverrideOrientationIfNeeded_mapInvokedOnRequest() {
        runTestScenarioWithActivity((robot) -> {
            robot.overrideOrientationIfNeeded(SCREEN_ORIENTATION_PORTRAIT);
            robot.checkOrientationRequestMapped();
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrideEnabled_returnsUser() {
        runTestScenarioWithActivity((robot) -> {
            robot.activity().setIgnoreOrientationRequest(true);
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrideEnabled_optOut_isUnchanged() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(true);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrides_optOutSystem_returnsUser() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
            robot.conf().enableUserAppAspectRatioFullscreen(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(true);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrides_optOutUser_returnsUser() {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE);
            robot.conf().enableUserAppAspectRatioFullscreen(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(true);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrideEnabled_returnsUnchanged()
            throws Exception {
        runTestScenarioWithActivity((robot) -> {
            robot.activity().setIgnoreOrientationRequest(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenAndUserOverrideEnabled_isUnchanged() {
        runTestScenario((robot) -> {
            robot.conf().enableUserAppAspectRatioSettings(true);

            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(true);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT})
    public void testOverrideOrientationIfNeeded_portraitOverrideEnabled_returnsPortrait() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(
                    /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR})
    public void testOverrideOrientationIfNeeded_portraitOverrideEnabled_returnsNosensor() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(
                    /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_NOSENSOR);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR})
    public void testOverrideOrientationIfNeeded_nosensorOverride_orientationFixed_isUnchanged() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(
                    /* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE})
    public void testOverrideOrientationIfNeeded_reverseLandscape_portraitOrUndefined_isUnchanged() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(
                    /* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
            robot.checkOverrideOrientation(
                    /* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE})
    public void testOverrideOrientationIfNeeded_reverseLandscape_Landscape_getsReverseLandscape() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_LANDSCAPE,
                    /* expected */ SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT})
    public void testOverrideOrientationIfNeeded_portraitOverride_orientationFixed_IsUnchanged() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_NOSENSOR,
                    /* expected */ SCREEN_ORIENTATION_NOSENSOR);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_portraitAndIgnoreFixedOverrides_returnsPortrait() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_NOSENSOR,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_noSensorAndIgnoreFixedOverrides_returnsNosensor() {
        runTestScenarioWithActivity((robot) -> {
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_NOSENSOR);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT})
    public void testOverrideOrientationIfNeeded_propertyIsFalse_isUnchanged()
            throws Exception {
        runTestScenario((robot) -> {
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);

            robot.activity().createActivityWithComponent();

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT,
            OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testOverrideOrientationIfNeeded_whenCameraNotActive_isUnchanged() {
        runTestScenario((robot) -> {
            robot.applyOnConf((c)-> {
                c.enableCameraCompatTreatment(true);
                c.enableCameraCompatTreatmentAtBuildTime(true);
            });
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setTopActivityEligibleForOrientationOverride(false);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT,
            OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testOverrideOrientationIfNeeded_whenCameraActive_returnsPortrait() {
        runTestScenario((robot) -> {
            robot.applyOnConf((c) -> {
                c.enableCameraCompatTreatment(true);
                c.enableCameraCompatTreatmentAtBuildTime(true);
            });
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setTopActivityEligibleForOrientationOverride(true);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userFullscreenOverride_returnsUser() {
        runTestScenarioWithActivity((robot) -> {
            robot.applyOnActivity((a) -> {
                a.setShouldApplyUserFullscreenOverride(true);
                a.setIgnoreOrientationRequest(true);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_fullscreenOverride_cameraActivity_unchanged() {
        runTestScenario((robot) -> {
            robot.applyOnConf((c) -> {
                c.enableCameraCompatTreatment(true);
                c.enableCameraCompatTreatmentAtBuildTime(true);
            });
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTaskAndDisplay();
                a.setTopActivityCameraActive(false);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_respectOrientationRequestOverUserFullScreen() {
        runTestScenarioWithActivity((robot) -> {
            robot.applyOnActivity((a) -> {
                a.setShouldApplyUserFullscreenOverride(true);
                a.setIgnoreOrientationRequest(false);
            });

            robot.checkOverrideOrientationIsNot(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* notExpected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_userFullScreenOverrideOverSystem_returnsUser() {
        runTestScenarioWithActivity((robot) -> {
            robot.applyOnActivity((a) -> {
                a.setShouldApplyUserFullscreenOverride(true);
                a.setIgnoreOrientationRequest(true);
            });

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_respectOrientationReqOverUserFullScreenAndSystem() {
        runTestScenarioWithActivity((robot) -> {
            robot.applyOnActivity((a) -> {
                a.setShouldApplyUserFullscreenOverride(true);
                a.setIgnoreOrientationRequest(false);
            });
            robot.checkOverrideOrientationIsNot(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* notExpected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userFullScreenOverrideDisabled_returnsUnchanged() {
        runTestScenarioWithActivity((robot) -> {
            robot.activity().setShouldApplyUserFullscreenOverride(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userAspectRatioApplied_unspecifiedOverridden() {
        runTestScenarioWithActivity((robot) -> {
            robot.activity().setShouldApplyUserMinAspectRatioOverride(true);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_LOCKED,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_LANDSCAPE,
                    /* expected */ SCREEN_ORIENTATION_LANDSCAPE);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userAspectRatioNotApplied_isUnchanged() {
        runTestScenarioWithActivity((robot) -> {
            robot.activity().setShouldApplyUserFullscreenOverride(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_activityRelaunching_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.activity().createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldIgnoreRequestedOrientation(/* expected */ true,
                    /* requestedOrientation */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_cameraCompatTreatment_returnsTrue() {
        runTestScenario((robot) -> {
            robot.applyOnConf((c) -> {
                c.enableCameraCompatTreatment(true);
                c.enableCameraCompatTreatmentAtBuildTime(true);
                c.enablePolicyForIgnoringRequestedOrientation(true);
            });
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTask();
                a.enableTreatmentForTopActivity(true);
            });
            robot.prepareRelaunchingAfterRequestedOrientationChanged(false);

            robot.checkShouldIgnoreRequestedOrientation(/* expected */ true,
                    /* requestedOrientation */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    public void testShouldIgnoreRequestedOrientation_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);

            robot.activity().createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldIgnoreRequestedOrientation(/* expected */ false,
                    /* requestedOrientation */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    public void testShouldIgnoreRequestedOrientation_propertyIsTrue_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.prop().enable(PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION);

            robot.activity().createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldIgnoreRequestedOrientation(/* expected */ true,
                    /* requestedOrientation */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_propertyIsFalseAndOverride_returnsFalse()
            throws Exception {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.prop().disable(PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION);

            robot.activity().createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldIgnoreRequestedOrientation(/* expected */ false,
                    /* requestedOrientation */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH})
    public void testShouldIgnoreRequestedOrientation_flagIsDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(false);
            });

            robot.checkShouldIgnoreRequestedOrientation(/* expected */ false,
                    /* requestedOrientation */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }


    /**
     * Runs a test scenario with an existing activity providing a Robot.
     */
    void runTestScenarioWithActivity(@NonNull Consumer<OrientationPolicyRobotTest> consumer) {
        runTestScenario(/* withActivity */ true, consumer);
    }

    /**
     * Runs a test scenario without an existing activity providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<OrientationPolicyRobotTest> consumer) {
        runTestScenario(/* withActivity */ false, consumer);
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(boolean withActivity,
                         @NonNull Consumer<OrientationPolicyRobotTest> consumer) {
        final OrientationPolicyRobotTest robot =
                new OrientationPolicyRobotTest(mWm, mAtm, mSupervisor, withActivity);
        consumer.accept(robot);
    }

    private static class OrientationPolicyRobotTest extends AppCompatRobotBase{

        private final WindowManagerService mWm;

        OrientationPolicyRobotTest(@NonNull WindowManagerService wm,
                                   @NonNull ActivityTaskManagerService atm,
                                   @NonNull ActivityTaskSupervisor supervisor,
                                   boolean withActivity) {
            super(wm, atm, supervisor);
            mWm = wm;
            spyOn(mWm);
            if (withActivity) {
                activity().createActivityWithComponent();
            }
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getAppCompatAspectRatioOverrides());
            spyOn(activity.mAppCompatController.getAppCompatAspectRatioPolicy());
        }

        @Override
        void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
            super.onPostDisplayContentCreation(displayContent);
            spyOn(displayContent.mAppCompatCameraPolicy);
            if (displayContent.mAppCompatCameraPolicy.hasDisplayRotationCompatPolicy()) {
                spyOn(displayContent.mAppCompatCameraPolicy.mDisplayRotationCompatPolicy);
            }
            if (displayContent.mAppCompatCameraPolicy.hasCameraCompatFreeformPolicy()) {
                spyOn(displayContent.mAppCompatCameraPolicy.mCameraCompatFreeformPolicy);
            }
        }

        void prepareRelaunchingAfterRequestedOrientationChanged(boolean enabled) {
            getTopOrientationOverrides().setRelaunchingAfterRequestedOrientationChanged(enabled);
        }

        int overrideOrientationIfNeeded(@ActivityInfo.ScreenOrientation int candidate) {
            return activity().top().mAppCompatController.getOrientationPolicy()
                    .overrideOrientationIfNeeded(candidate);
        }

        void checkOrientationRequestMapped() {
            verify(mWm).mapOrientationRequest(SCREEN_ORIENTATION_PORTRAIT);
        }

        void checkOverrideOrientation(@ActivityInfo.ScreenOrientation int candidate,
                                      @ActivityInfo.ScreenOrientation int expected) {
            assertEquals(expected, overrideOrientationIfNeeded(candidate));
        }

        void checkOverrideOrientationIsNot(@ActivityInfo.ScreenOrientation int candidate,
                                           @ActivityInfo.ScreenOrientation int notExpected) {
            Assert.assertNotEquals(notExpected, overrideOrientationIfNeeded(candidate));
        }

        void checkShouldIgnoreRequestedOrientation(boolean expected,
                @Configuration.Orientation int requestedOrientation) {
            assertEquals(expected, getTopAppCompatOrientationPolicy()
                    .shouldIgnoreRequestedOrientation(requestedOrientation));
        }

        private AppCompatOrientationOverrides getTopOrientationOverrides() {
            return activity().top().mAppCompatController.getAppCompatOverrides()
                    .getAppCompatOrientationOverrides();
        }

        private AppCompatOrientationPolicy getTopAppCompatOrientationPolicy() {
            return activity().top().mAppCompatController.getOrientationPolicy();
        }
    }
}
