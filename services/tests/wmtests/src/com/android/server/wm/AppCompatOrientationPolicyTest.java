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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import com.android.server.wm.utils.TestComponentStack;

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
            robot.configureSetIgnoreOrientationRequest(true);
            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrideEnabled_optOut_isUnchanged() {
        runTestScenario((robot) -> {
            robot.disableProperty(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
            robot.createActivityWithComponent();
            robot.configureSetIgnoreOrientationRequest(true);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrides_optOutSystem_returnsUser() {
        runTestScenario((robot) -> {
            robot.disableProperty(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);
            robot.configureIsUserAppAspectRatioFullscreenEnabled(true);

            robot.createActivityWithComponent();
            robot.configureSetIgnoreOrientationRequest(true);
            robot.prepareGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrides_optOutUser_returnsUser() {
        runTestScenario((robot) -> {
            robot.disableProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE);
            robot.configureIsUserAppAspectRatioFullscreenEnabled(true);

            robot.createActivityWithComponent();
            robot.configureSetIgnoreOrientationRequest(true);
            robot.prepareGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_FULLSCREEN);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenOverrideEnabled_returnsUnchanged()
            throws Exception {
        runTestScenarioWithActivity((robot) -> {
            robot.configureSetIgnoreOrientationRequest(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ANY_ORIENTATION_TO_USER})
    public void testOverrideOrientationIfNeeded_fullscreenAndUserOverrideEnabled_isUnchanged() {
        runTestScenario((robot) -> {
            robot.prepareIsUserAppAspectRatioSettingsEnabled(true);

            robot.createActivityWithComponent();
            robot.configureSetIgnoreOrientationRequest(true);
            robot.prepareGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);

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
            robot.disableProperty(PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE);

            robot.createActivityWithComponent();

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT,
            OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testOverrideOrientationIfNeeded_whenCameraNotActive_isUnchanged() {
        runTestScenario((robot) -> {
            robot.configureIsCameraCompatTreatmentEnabled(true);
            robot.configureIsCameraCompatTreatmentEnabledAtBuildTime(true);

            robot.createActivityWithComponentInNewTask();
            robot.prepareIsTopActivityEligibleForOrientationOverride(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT,
            OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA})
    public void testOverrideOrientationIfNeeded_whenCameraActive_returnsPortrait() {
        runTestScenario((robot) -> {
            robot.configureIsCameraCompatTreatmentEnabled(true);
            robot.configureIsCameraCompatTreatmentEnabledAtBuildTime(true);

            robot.createActivityWithComponentInNewTask();
            robot.prepareIsTopActivityEligibleForOrientationOverride(true);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userFullscreenOverride_returnsUser() {
        runTestScenarioWithActivity((robot) -> {
            robot.prepareShouldApplyUserFullscreenOverride(true);
            robot.configureSetIgnoreOrientationRequest(true);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_fullscreenOverride_cameraActivity_unchanged() {
        runTestScenario((robot) -> {
            robot.configureIsCameraCompatTreatmentEnabled(true);
            robot.configureIsCameraCompatTreatmentEnabledAtBuildTime(true);

            robot.createActivityWithComponentInNewTask();
            robot.configureIsTopActivityCameraActive(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_respectOrientationRequestOverUserFullScreen() {
        runTestScenarioWithActivity((robot) -> {
            robot.prepareShouldApplyUserFullscreenOverride(true);
            robot.configureSetIgnoreOrientationRequest(false);

            robot.checkOverrideOrientationIsNot(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* notExpected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_userFullScreenOverrideOverSystem_returnsUser() {
        runTestScenarioWithActivity((robot) -> {
            robot.prepareShouldApplyUserFullscreenOverride(true);
            robot.configureSetIgnoreOrientationRequest(true);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, OVERRIDE_ANY_ORIENTATION})
    public void testOverrideOrientationIfNeeded_respectOrientationReqOverUserFullScreenAndSystem() {
        runTestScenarioWithActivity((robot) -> {
            robot.prepareShouldApplyUserFullscreenOverride(true);
            robot.configureSetIgnoreOrientationRequest(false);

            robot.checkOverrideOrientationIsNot(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* notExpected */ SCREEN_ORIENTATION_USER);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userFullScreenOverrideDisabled_returnsUnchanged() {
        runTestScenarioWithActivity((robot) -> {
            robot.prepareShouldApplyUserFullscreenOverride(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_PORTRAIT,
                    /* expected */ SCREEN_ORIENTATION_PORTRAIT);
        });
    }

    @Test
    public void testOverrideOrientationIfNeeded_userAspectRatioApplied_unspecifiedOverridden() {
        runTestScenarioWithActivity((robot) -> {
            robot.prepareShouldApplyUserMinAspectRatioOverride(true);

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
            robot.prepareShouldApplyUserFullscreenOverride(false);

            robot.checkOverrideOrientation(/* candidate */ SCREEN_ORIENTATION_UNSPECIFIED,
                    /* expected */ SCREEN_ORIENTATION_UNSPECIFIED);
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
        spyOn(mWm.mLetterboxConfiguration);
        final OrientationPolicyRobotTest robot =
                new OrientationPolicyRobotTest(mWm, mAtm, mSupervisor, withActivity);
        consumer.accept(robot);
    }

    private static class OrientationPolicyRobotTest {

        @NonNull
        private final ActivityTaskManagerService mAtm;
        @NonNull
        private final WindowManagerService mWm;
        @NonNull
        private final LetterboxConfiguration mLetterboxConfiguration;
        @NonNull
        private final TestComponentStack<ActivityRecord> mActivityStack;
        @NonNull
        private final TestComponentStack<Task> mTaskStack;

        @NonNull
        private final ActivityTaskSupervisor mSupervisor;

        OrientationPolicyRobotTest(@NonNull WindowManagerService wm,
                                   @NonNull ActivityTaskManagerService atm,
                                   @NonNull ActivityTaskSupervisor supervisor,
                                   boolean withActivity) {
            mAtm = atm;
            mWm = wm;
            spyOn(mWm);
            mSupervisor = supervisor;
            mActivityStack = new TestComponentStack<>();
            mTaskStack = new TestComponentStack<>();
            mLetterboxConfiguration = mWm.mLetterboxConfiguration;
            if (withActivity) {
                createActivityWithComponent();
            }
        }

        void configureSetIgnoreOrientationRequest(boolean enabled) {
            mActivityStack.top().mDisplayContent.setIgnoreOrientationRequest(enabled);
        }

        void configureIsUserAppAspectRatioFullscreenEnabled(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration).isUserAppAspectRatioFullscreenEnabled();
        }

        void configureIsCameraCompatTreatmentEnabled(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled();
        }

        void configureIsCameraCompatTreatmentEnabledAtBuildTime(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration)
                    .isCameraCompatTreatmentEnabledAtBuildTime();
        }

        void prepareGetUserMinAspectRatioOverrideCode(int orientation) {
            spyOn(mActivityStack.top().mLetterboxUiController);
            doReturn(orientation).when(mActivityStack.top()
                    .mLetterboxUiController).getUserMinAspectRatioOverrideCode();
        }

        void prepareShouldApplyUserFullscreenOverride(boolean enabled) {
            spyOn(mActivityStack.top().mLetterboxUiController);
            doReturn(enabled).when(mActivityStack.top()
                    .mLetterboxUiController).shouldApplyUserFullscreenOverride();
        }

        void prepareShouldApplyUserMinAspectRatioOverride(boolean enabled) {
            spyOn(mActivityStack.top().mLetterboxUiController);
            doReturn(enabled).when(mActivityStack.top()
                    .mLetterboxUiController).shouldApplyUserMinAspectRatioOverride();
        }

        void prepareIsUserAppAspectRatioSettingsEnabled(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration).isUserAppAspectRatioSettingsEnabled();
        }

        void prepareIsTopActivityEligibleForOrientationOverride(boolean enabled) {
            final DisplayRotationCompatPolicy displayPolicy =
                    mActivityStack.top().mDisplayContent.mDisplayRotationCompatPolicy;
            spyOn(displayPolicy);
            doReturn(enabled).when(displayPolicy)
                    .isActivityEligibleForOrientationOverride(eq(mActivityStack.top()));
        }

        void configureIsTopActivityCameraActive(boolean enabled) {
            final DisplayRotationCompatPolicy displayPolicy =
                    mActivityStack.top().mDisplayContent.mDisplayRotationCompatPolicy;
            spyOn(displayPolicy);
            doReturn(enabled).when(displayPolicy)
                    .isCameraActive(eq(mActivityStack.top()), /* mustBeFullscreen= */ eq(true));
        }

        void disableProperty(@NonNull String propertyName) {
            setPropertyValue(propertyName, /* enabled */ false);
        }

        int overrideOrientationIfNeeded(@ActivityInfo.ScreenOrientation int candidate) {
            return mActivityStack.top().mAppCompatController.getOrientationPolicy()
                    .overrideOrientationIfNeeded(candidate);
        }

        void checkOrientationRequestMapped() {
            verify(mWm).mapOrientationRequest(SCREEN_ORIENTATION_PORTRAIT);
        }

        void checkOverrideOrientation(@ActivityInfo.ScreenOrientation int candidate,
                                      @ActivityInfo.ScreenOrientation int expected) {
            Assert.assertEquals(expected, overrideOrientationIfNeeded(candidate));
        }

        void checkOverrideOrientationIsNot(@ActivityInfo.ScreenOrientation int candidate,
                                           @ActivityInfo.ScreenOrientation int notExpected) {
            Assert.assertNotEquals(notExpected, overrideOrientationIfNeeded(candidate));
        }

        private void createActivityWithComponent() {
            if (mTaskStack.isEmpty()) {
                final DisplayContent displayContent = new TestDisplayContent
                        .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
                final Task task = new TaskBuilder(mSupervisor).setDisplay(displayContent).build();
                mTaskStack.push(task);
            }
            final ActivityRecord activity = new ActivityBuilder(mAtm)
                    .setOnTop(true)
                    .setTask(mTaskStack.top())
                    // Set the component to be that of the test class in order
                    // to enable compat changes
                    .setComponent(ComponentName.createRelative(mAtm.mContext,
                            com.android.server.wm.LetterboxUiControllerTest.class.getName()))
                    .build();
            mActivityStack.push(activity);
        }

        private void createActivityWithComponentInNewTask() {
            final DisplayContent displayContent = new TestDisplayContent
                    .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
            final Task task = new TaskBuilder(mSupervisor).setDisplay(displayContent).build();
            final ActivityRecord activity = new ActivityBuilder(mAtm)
                    .setOnTop(true)
                    .setTask(task)
                    // Set the component to be that of the test class in order
                    // to enable compat changes
                    .setComponent(ComponentName.createRelative(mAtm.mContext,
                            com.android.server.wm.LetterboxUiControllerTest.class.getName()))
                    .build();
            mTaskStack.push(task);
            mActivityStack.push(activity);
        }

        private void setPropertyValue(@NonNull String propertyName, boolean enabled) {
            PackageManager.Property property = new PackageManager.Property(propertyName,
                    /* value */ enabled, /* packageName */ "",
                    /* className */ "");
            PackageManager pm = mWm.mContext.getPackageManager();
            spyOn(pm);
            try {
                doReturn(property).when(pm).getProperty(eq(propertyName), anyString());
            } catch (PackageManager.NameNotFoundException e) {
                fail(e.getLocalizedMessage());
            }
        }
    }
}
