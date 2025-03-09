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

import static android.app.CameraCompatTaskInfo.CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.CameraCompatTaskInfo.FreeformCameraCompatMode;
import android.app.TaskInfo;
import android.graphics.Rect;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.annotation.NonNull;

import com.android.window.flags.Flags;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatUtils}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatUtilsTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatUtilsTest extends WindowTestsBase {

    @Test
    public void getLetterboxReasonString_inSizeCompatMode() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setTopActivityInSizeCompatMode(/* inScm */ true);
            });

            robot.checkTopActivityLetterboxReason(/* expected */ "SIZE_COMPAT_MODE");
        });
    }

    @Test
    public void getLetterboxReasonString_fixedOrientation() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.checkTopActivityInSizeCompatMode(/* inScm */ false);
            });
            robot.setIsLetterboxedForFixedOrientationAndAspectRatio(
                    /* forFixedOrientationAndAspectRatio */ true);

            robot.checkTopActivityLetterboxReason(/* expected */ "FIXED_ORIENTATION");
        });
    }

    @Test
    public void getLetterboxReasonString_isLetterboxedForDisplayCutout() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.checkTopActivityInSizeCompatMode(/* inScm */ false);
            });
            robot.setIsLetterboxedForFixedOrientationAndAspectRatio(
                    /* forFixedOrientationAndAspectRatio */ false);
            robot.setIsLetterboxedForDisplayCutout(/* displayCutout */ true);

            robot.checkTopActivityLetterboxReason(/* expected */ "DISPLAY_CUTOUT");
        });
    }

    @Test
    public void getLetterboxReasonString_aspectRatio() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.checkTopActivityInSizeCompatMode(/* inScm */ false);
            });
            robot.setIsLetterboxedForFixedOrientationAndAspectRatio(
                    /* forFixedOrientationAndAspectRatio */ false);
            robot.setIsLetterboxedForDisplayCutout(/* displayCutout */ false);
            robot.setIsLetterboxedForAspectRatioOnly(/* forAspectRatio */ true);

            robot.checkTopActivityLetterboxReason(/* expected */ "ASPECT_RATIO");
        });
    }

    @Test
    public void getLetterboxReasonString_unknownReason() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.checkTopActivityInSizeCompatMode(/* inScm */ false);
            });
            robot.setIsLetterboxedForFixedOrientationAndAspectRatio(
                    /* forFixedOrientationAndAspectRatio */ false);
            robot.setIsLetterboxedForDisplayCutout(/* displayCutout */ false);
            robot.setIsLetterboxedForAspectRatioOnly(/* forAspectRatio */ false);

            robot.checkTopActivityLetterboxReason(/* expected */ "UNKNOWN_REASON");
        });
    }

    @Test
    public void testTopActivityEligibleForUserAspectRatioButton_eligible() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTask();
                a.setIgnoreOrientationRequest(true);
            });
            robot.conf().enableUserAppAspectRatioSettings(true);

            robot.checkTaskInfoEligibleForUserAspectRatioButton(true);
        });
    }

    @Test
    public void testTopActivityEligibleForUserAspectRatioButton_disabled_notEligible() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTask();
                a.setIgnoreOrientationRequest(true);
            });
            robot.conf().enableUserAppAspectRatioSettings(false);

            robot.checkTaskInfoEligibleForUserAspectRatioButton(false);
        });
    }

    @Test
    public void testTopActivityEligibleForUserAspectRatioButton_inSizeCompatMode_notEligible() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponentInNewTask();
                a.setIgnoreOrientationRequest(true);
                a.setTopActivityOrganizedTask();
                a.setTopActivityInSizeCompatMode(true);
                a.setTopActivityVisible(true);
            });
            robot.conf().enableUserAppAspectRatioSettings(true);

            robot.checkTaskInfoEligibleForUserAspectRatioButton(false);
        });
    }

    @Test
    public void testTopActivityEligibleForUserAspectRatioButton_transparentTop_notEligible() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.launchTransparentActivityInTask();
                ta.activity().setIgnoreOrientationRequest(true);
            });
            robot.conf().enableUserAppAspectRatioSettings(true);

            robot.checkTaskInfoEligibleForUserAspectRatioButton(false);
        });
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CAMERA_COMPAT_FOR_DESKTOP_WINDOWING)
    public void getTaskInfoPropagatesCameraCompatMode() {
        runTestScenario((robot) -> {
            robot.dw().allowEnterDesktopMode(/* isAllowed= */ true);
            robot.applyOnActivity(
                    AppCompatActivityRobot::createActivityWithComponentInNewTaskAndDisplay);
            robot.setCameraCompatTreatmentEnabledForActivity(/* enabled= */ true);

            robot.setFreeformCameraCompatMode(CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE);
            robot.checkTaskInfoFreeformCameraCompatMode(
                    CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE);
        });
    }

    @Test
    public void testTopActivityLetterboxed_hasBounds() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.checkTopActivityInSizeCompatMode(/* inScm */ false);
                a.setIgnoreOrientationRequest(true);
                a.configureTopActivityBounds(new Rect(20, 30, 520, 630));
            });
            robot.setIsLetterboxedForAspectRatioOnly(/* forAspectRatio */ true);


            robot.checkTaskInfoTopActivityHasBounds(/* expected */ new Rect(20, 30, 520, 630));
        });
    }

    @Test
    public void testTopActivityNotLetterboxed_hasNoBounds() {
        runTestScenario((robot) -> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(true);
            });

            robot.checkTaskInfoTopActivityHasBounds(/* expected */ null);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<AppCompatUtilsRobotTest> consumer) {
        final AppCompatUtilsRobotTest robot = new AppCompatUtilsRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class AppCompatUtilsRobotTest extends AppCompatRobotBase {

        private final WindowState mWindowState;
        @NonNull
        private final AppCompatTransparentActivityRobot mTransparentActivityRobot;

        AppCompatUtilsRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
            mTransparentActivityRobot = new AppCompatTransparentActivityRobot(activity());
            mWindowState = Mockito.mock(WindowState.class);
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getAppCompatAspectRatioPolicy());
        }

        @Override
        void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
            super.onPostDisplayContentCreation(displayContent);
            mockPortraitDisplay(displayContent);
            if (displayContent.mAppCompatCameraPolicy.hasCameraCompatFreeformPolicy()) {
                spyOn(displayContent.mAppCompatCameraPolicy.mCameraCompatFreeformPolicy);
            }
        }

        void transparentActivity(@NonNull Consumer<AppCompatTransparentActivityRobot> consumer) {
            // We always create at least an opaque activity in a Task.
            activity().createNewTaskWithBaseActivity();
            consumer.accept(mTransparentActivityRobot);
        }

        void setIsLetterboxedForFixedOrientationAndAspectRatio(
                boolean forFixedOrientationAndAspectRatio) {
            when(activity().top().mAppCompatController.getAppCompatAspectRatioPolicy()
                    .isLetterboxedForFixedOrientationAndAspectRatio())
                        .thenReturn(forFixedOrientationAndAspectRatio);
        }

        void setIsLetterboxedForAspectRatioOnly(boolean forAspectRatio) {
            when(activity().top().mAppCompatController.getAppCompatAspectRatioPolicy()
                    .isLetterboxedForAspectRatioOnly()).thenReturn(forAspectRatio);
        }

        void setIsLetterboxedForDisplayCutout(boolean displayCutout) {
            when(mWindowState.isLetterboxedForDisplayCutout()).thenReturn(displayCutout);
        }

        void setFreeformCameraCompatMode(@FreeformCameraCompatMode int mode) {
            doReturn(mode).when(activity().top().mDisplayContent.mAppCompatCameraPolicy
                    .mCameraCompatFreeformPolicy).getCameraCompatMode(activity().top());
        }

        void checkTopActivityLetterboxReason(@NonNull String expected) {
            Assert.assertEquals(expected,
                    AppCompatUtils.getLetterboxReasonString(activity().top(), mWindowState));
        }

        @NonNull
        TaskInfo getTopTaskInfo() {
            return activity().top().getTask().getTaskInfo();
        }

        void checkTaskInfoEligibleForUserAspectRatioButton(boolean eligible) {
            Assert.assertEquals(eligible, getTopTaskInfo().appCompatTaskInfo
                    .eligibleForUserAspectRatioButton());
        }

        void checkTaskInfoFreeformCameraCompatMode(@FreeformCameraCompatMode int mode) {
            Assert.assertEquals(mode, getTopTaskInfo().appCompatTaskInfo
                    .cameraCompatTaskInfo.freeformCameraCompatMode);
        }

        void checkTaskInfoTopActivityHasBounds(Rect bounds) {
            Assert.assertEquals(bounds, getTopTaskInfo().appCompatTaskInfo
                    .topActivityLetterboxBounds);
        }

        void setCameraCompatTreatmentEnabledForActivity(boolean enabled) {
            doReturn(enabled).when(activity().displayContent().mAppCompatCameraPolicy
                    .mCameraCompatFreeformPolicy).isTreatmentEnabledForActivity(
                            eq(activity().top()), anyBoolean());
        }

        private void mockPortraitDisplay(DisplayContent displayContent) {
            doAnswer(invocation -> {
                DisplayInfo displayInfo = new DisplayInfo();
                displayContent.getDisplay().getDisplayInfo(displayInfo);
                displayInfo.rotation = Surface.ROTATION_90;
                // Set height and width so that the natural orientation (when rotation is 0) is
                // portrait.
                displayInfo.logicalHeight = 600;
                displayInfo.logicalWidth =  800;
                return displayInfo;
            }).when(displayContent.mWmService.mDisplayManagerInternal).getDisplayInfo(anyInt());
        }
    }
}
