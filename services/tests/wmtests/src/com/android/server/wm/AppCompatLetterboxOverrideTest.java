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

import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_OVERRIDE_UNSET;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;

import static junit.framework.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Test class for {@link AppCompatLetterboxOverrides}.
 *
 * Build/Install/Run:
 * atest WmTests:AppCompatLetterboxOverrideTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatLetterboxOverrideTest extends WindowTestsBase {

    @Test
    public void testIsLetterboxEducationEnabled() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxEducationEnabled(/* enabled */ true);
            robot.checkLetterboxEducationEnabled(/* enabled */ true);

            robot.conf().setLetterboxEducationEnabled(/* enabled */ false);
            robot.checkLetterboxEducationEnabled(/* enabled */ false);
        });
    }

    @Test
    public void testShouldLetterboxHaveRoundedCorners() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxActivityCornersRounded(/* rounded */ true);
            robot.activity().setTopActivityFillsParent(/* fillsParent */ true);
            robot.checkShouldLetterboxHaveRoundedCorners(/* expected */ true);

            robot.conf().setLetterboxActivityCornersRounded(/* rounded */ false);
            robot.checkShouldLetterboxHaveRoundedCorners(/* expected */ false);

            robot.conf().setLetterboxActivityCornersRounded(/* rounded */ true);
            robot.activity().setTopActivityFillsParent(/* fillsParent */ false);
            robot.checkShouldLetterboxHaveRoundedCorners(/* expected */ false);
        });
    }

    @Test
    public void testHasWallpaperBackgroundForLetterbox() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ false);

            robot.invokeCheckWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */ false);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ false);

            robot.invokeCheckWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */ true);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ true);

            robot.invokeCheckWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */ true);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ true);

            robot.invokeCheckWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */ false);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ false);
        });
    }

    @Test
    public void testCheckWallpaperBackgroundForLetterbox() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ false);

            robot.checkWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */
                    true, /* expected */ true);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ true);

            robot.checkWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */
                    true, /* expected */ false);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ true);

            robot.checkWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */
                    false, /* expected */ true);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ false);

            robot.checkWallpaperBackgroundForLetterbox(/* wallpaperShouldBeShown */
                    false, /* expected */ false);
            robot.checkHasWallpaperBackgroundForLetterbox(/* expected */ false);
        });
    }

    @Test
    public void testLetterboxActivityCornersRadius() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxActivityCornersRadius(/* cornerRadius */ 0);
            robot.checkLetterboxActivityCornersRadius(/* cornerRadius */ 0);

            robot.conf().setLetterboxActivityCornersRadius(/* cornerRadius */ 37);
            robot.checkLetterboxActivityCornersRadius(/* cornerRadius */ 37);

            robot.conf().setLetterboxActivityCornersRadius(/* cornerRadius */ 5);
            robot.checkLetterboxActivityCornersRadius(/* cornerRadius */ 5);
        });
    }

    @Test
    public void testLetterboxActivityCornersRaunded() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxActivityCornersRounded(/* rounded */ true);
            robot.checkLetterboxActivityCornersRounded(/* expected */ true);

            robot.conf().setLetterboxActivityCornersRounded(/* rounded */ false);
            robot.checkLetterboxActivityCornersRounded(/* expected */ false);
        });
    }

    @Test
    public void testLetterboxBackgroundType() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxBackgroundType(LETTERBOX_BACKGROUND_OVERRIDE_UNSET);
            robot.checkLetterboxBackgroundType(LETTERBOX_BACKGROUND_OVERRIDE_UNSET);

            robot.conf().setLetterboxBackgroundType(LETTERBOX_BACKGROUND_SOLID_COLOR);
            robot.checkLetterboxBackgroundType(LETTERBOX_BACKGROUND_SOLID_COLOR);

            robot.conf().setLetterboxBackgroundType(LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND);
            robot.checkLetterboxBackgroundType(LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND);

            robot.conf().setLetterboxBackgroundType(
                    LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING);
            robot.checkLetterboxBackgroundType(LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING);

            robot.conf().setLetterboxBackgroundType(LETTERBOX_BACKGROUND_WALLPAPER);
            robot.checkLetterboxBackgroundType(LETTERBOX_BACKGROUND_WALLPAPER);
        });
    }

    @Test
    public void testLetterboxBackgroundWallpaperBlurRadiusPx() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxBackgroundWallpaperBlurRadiusPx(-1);
            robot.checkLetterboxWallpaperBlurRadiusPx(0);

            robot.conf().setLetterboxBackgroundWallpaperBlurRadiusPx(0);
            robot.checkLetterboxWallpaperBlurRadiusPx(0);

            robot.conf().setLetterboxBackgroundWallpaperBlurRadiusPx(10);
            robot.checkLetterboxWallpaperBlurRadiusPx(10);
        });
    }

    @Test
    public void testLetterboxBackgroundWallpaperDarkScrimAlpha() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.conf().setLetterboxBackgroundWallpaperDarkScrimAlpha(-1f);
            robot.checkLetterboxWallpaperDarkScrimAlpha(0);

            robot.conf().setLetterboxBackgroundWallpaperDarkScrimAlpha(1.1f);
            robot.checkLetterboxWallpaperDarkScrimAlpha(0);

            robot.conf().setLetterboxBackgroundWallpaperDarkScrimAlpha(0.001f);
            robot.checkLetterboxWallpaperDarkScrimAlpha(0.001f);

            robot.conf().setLetterboxBackgroundWallpaperDarkScrimAlpha(0.999f);
            robot.checkLetterboxWallpaperDarkScrimAlpha(0.999f);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<LetterboxOverridesRobotTest> consumer) {
        final LetterboxOverridesRobotTest robot =
                new LetterboxOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class LetterboxOverridesRobotTest extends AppCompatRobotBase {
        LetterboxOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        void invokeCheckWallpaperBackgroundForLetterbox(boolean wallpaperShouldBeShown) {
            getLetterboxOverrides().checkWallpaperBackgroundForLetterbox(wallpaperShouldBeShown);
        }

        void checkLetterboxEducationEnabled(boolean enabled) {
            assertEquals(enabled, getLetterboxOverrides().isLetterboxEducationEnabled());
        }

        void checkShouldLetterboxHaveRoundedCorners(boolean expected) {
            assertEquals(expected,
                    getLetterboxOverrides().shouldLetterboxHaveRoundedCorners());
        }

        void checkHasWallpaperBackgroundForLetterbox(boolean expected) {
            assertEquals(expected,
                    getLetterboxOverrides().hasWallpaperBackgroundForLetterbox());
        }

        void checkWallpaperBackgroundForLetterbox(boolean wallpaperShouldBeShown,
                boolean expected) {
            assertEquals(expected,
                    getLetterboxOverrides().checkWallpaperBackgroundForLetterbox(
                            wallpaperShouldBeShown));
        }

        void checkLetterboxActivityCornersRadius(int expected) {
            assertEquals(expected, getLetterboxOverrides().getLetterboxActivityCornersRadius());
        }

        void checkLetterboxActivityCornersRounded(boolean expected) {
            assertEquals(expected, getLetterboxOverrides().isLetterboxActivityCornersRounded());
        }

        void checkLetterboxBackgroundType(@LetterboxBackgroundType int expected) {
            assertEquals(expected, getLetterboxOverrides().getLetterboxBackgroundType());
        }

        void checkLetterboxWallpaperBlurRadiusPx(int expected) {
            assertEquals(expected, getLetterboxOverrides().getLetterboxWallpaperBlurRadiusPx());
        }

        void checkLetterboxWallpaperDarkScrimAlpha(float expected) {
            assertEquals(expected, getLetterboxOverrides().getLetterboxWallpaperDarkScrimAlpha(),
                    FLOAT_TOLLERANCE);
        }

        @NonNull
        private AppCompatLetterboxOverrides getLetterboxOverrides() {
            return activity().top().mAppCompatController.getAppCompatLetterboxOverrides();
        }

    }

}
