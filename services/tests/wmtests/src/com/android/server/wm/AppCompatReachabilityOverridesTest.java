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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.compat.testing.PlatformCompatChangeRule;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import com.android.window.flags.Flags;

import junit.framework.Assert;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Test class for {@link AppCompatReachabilityOverrides}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatReachabilityOverridesTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatReachabilityOverridesTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    public void testIsThinLetterboxed_NegativePx_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponentWithoutTask();
            robot.conf().setThinLetterboxHeightPx(/* thinHeightPx */ -1);
            robot.checkIsVerticalThinLetterboxed(/* expected */ false);

            robot.conf().setThinLetterboxWidthPx(/* thinHeightPx */ -1);
            robot.checkIsHorizontalThinLetterboxed(/* expected */ false);
        });
    }

    @Test
    public void testIsThinLetterboxed_noTask_returnsFalse() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponentWithoutTask();
            robot.conf().setThinLetterboxHeightPx(/* thinHeightPx */ 10);
            robot.checkIsVerticalThinLetterboxed(/* expected */ false);

            robot.conf().setThinLetterboxWidthPx(/* thinHeightPx */ 10);
            robot.checkIsHorizontalThinLetterboxed(/* expected */ false);
        });
    }

    @Test
    public void testIsVerticalThinLetterboxed() {
        runTestScenario((robot) -> {
            robot.conf().setThinLetterboxHeightPx(/* thinHeightPx */ 10);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureTaskBounds(new Rect(0, 0, 100, 100));

                // (task.width() - act.width()) / 2  = 5 < 10
                a.configureTopActivityBounds(new Rect(5, 5, 95, 95));
                robot.checkIsVerticalThinLetterboxed(/* expected */ true);

                // (task.width() - act.width()) / 2  = 10 = 10
                a.configureTopActivityBounds(new Rect(10, 10, 90, 90));
                robot.checkIsVerticalThinLetterboxed(/* expected */ true);

                // (task.width() - act.width()) / 2  = 11 > 10
                a.configureTopActivityBounds(new Rect(11, 11, 89, 89));
                robot.checkIsVerticalThinLetterboxed(/* expected */ false);
            });
        });
    }

    @Test
    public void testIsHorizontalThinLetterboxed() {
        runTestScenario((robot) -> {
            robot.conf().setThinLetterboxWidthPx(/* thinHeightPx */ 10);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureTaskBounds(new Rect(0, 0, 100, 100));

                // (task.height() - act.height()) / 2  = 5 < 10
                a.configureTopActivityBounds(new Rect(5, 5, 95, 95));
                robot.checkIsHorizontalThinLetterboxed(/* expected */ true);

                // (task.height() - act.height()) / 2  = 10 = 10
                a.configureTopActivityBounds(new Rect(10, 10, 90, 90));
                robot.checkIsHorizontalThinLetterboxed(/* expected */ true);

                // (task.height() - act.height()) / 2  = 11 > 10
                a.configureTopActivityBounds(new Rect(11, 11, 89, 89));
                robot.checkIsHorizontalThinLetterboxed(/* expected */ false);
            });
        });
    }

    @Test
    @EnableFlags(Flags.FLAG_DISABLE_THIN_LETTERBOXING_POLICY)
    public void testAllowReachabilityForThinLetterboxWithFlagEnabled() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.configureIsVerticalThinLetterboxed(/* isThin */ true);
            robot.checkAllowVerticalReachabilityForThinLetterbox(/* expected */ false);
            robot.configureIsHorizontalThinLetterboxed(/* isThin */ true);
            robot.checkAllowHorizontalReachabilityForThinLetterbox(/* expected */ false);

            robot.configureIsVerticalThinLetterboxed(/* isThin */ false);
            robot.checkAllowVerticalReachabilityForThinLetterbox(/* expected */ true);
            robot.configureIsHorizontalThinLetterboxed(/* isThin */ false);
            robot.checkAllowHorizontalReachabilityForThinLetterbox(/* expected */ true);
        });
    }

    @Test
    @DisableFlags(Flags.FLAG_DISABLE_THIN_LETTERBOXING_POLICY)
    public void testAllowReachabilityForThinLetterboxWithFlagDisabled() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();

            robot.configureIsVerticalThinLetterboxed(/* isThin */ true);
            robot.checkAllowVerticalReachabilityForThinLetterbox(/* expected */ true);
            robot.configureIsHorizontalThinLetterboxed(/* isThin */ true);
            robot.checkAllowHorizontalReachabilityForThinLetterbox(/* expected */ true);

            robot.configureIsVerticalThinLetterboxed(/* isThin */ false);
            robot.checkAllowVerticalReachabilityForThinLetterbox(/* expected */ true);
            robot.configureIsHorizontalThinLetterboxed(/* isThin */ false);
            robot.checkAllowHorizontalReachabilityForThinLetterbox(/* expected */ true);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<ReachabilityOverridesRobotTest> consumer) {
        final ReachabilityOverridesRobotTest robot =
                new ReachabilityOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class ReachabilityOverridesRobotTest extends AppCompatRobotBase {

        private final Supplier<Rect> mLetterboxInnerBoundsSupplier = spy(Rect::new);

        ReachabilityOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getAppCompatReachabilityOverrides());
            activity.mAppCompatController.getAppCompatReachabilityPolicy()
                    .setLetterboxInnerBoundsSupplier(mLetterboxInnerBoundsSupplier);
        }

        void configureIsVerticalThinLetterboxed(boolean isThin) {
            doReturn(isThin).when(getAppCompatReachabilityOverrides())
                    .isVerticalThinLetterboxed();
        }

        void configureIsHorizontalThinLetterboxed(boolean isThin) {
            doReturn(isThin).when(getAppCompatReachabilityOverrides())
                    .isHorizontalThinLetterboxed();
        }

        void checkIsVerticalThinLetterboxed(boolean expected) {
            Assert.assertEquals(expected,
                    getAppCompatReachabilityOverrides().isVerticalThinLetterboxed());
        }

        void checkIsHorizontalThinLetterboxed(boolean expected) {
            Assert.assertEquals(expected,
                    getAppCompatReachabilityOverrides().isHorizontalThinLetterboxed());
        }

        void checkAllowVerticalReachabilityForThinLetterbox(boolean expected) {
            Assert.assertEquals(expected, getAppCompatReachabilityOverrides()
                    .allowVerticalReachabilityForThinLetterbox());
        }

        void checkAllowHorizontalReachabilityForThinLetterbox(boolean expected) {
            Assert.assertEquals(expected, getAppCompatReachabilityOverrides()
                    .allowHorizontalReachabilityForThinLetterbox());
        }

        @NonNull
        private AppCompatReachabilityOverrides getAppCompatReachabilityOverrides() {
            return activity().top().mAppCompatController.getAppCompatReachabilityOverrides();
        }

    }

}
