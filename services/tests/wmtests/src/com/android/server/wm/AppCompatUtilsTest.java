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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

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

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<AppCompatUtilsRobotTest> consumer) {
        final AppCompatUtilsRobotTest robot = new AppCompatUtilsRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class AppCompatUtilsRobotTest extends AppCompatRobotBase {

        private final WindowState mWindowState;

        AppCompatUtilsRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
            mWindowState = Mockito.mock(WindowState.class);
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getAppCompatAspectRatioPolicy());
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

        void checkTopActivityLetterboxReason(@NonNull String expected) {
            Assert.assertEquals(expected,
                    AppCompatUtils.getLetterboxReasonString(activity().top(), mWindowState));
        }

    }

}
