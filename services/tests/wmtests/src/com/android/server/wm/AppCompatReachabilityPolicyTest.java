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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Test class for {@link AppCompatReachabilityPolicy}.
 * <p/>
 * Build/Install/Run:
 * atest WmTests:AppCompatReachabilityPolicyTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatReachabilityPolicyTest extends WindowTestsBase {

    @Test
    public void handleHorizontalDoubleTap_reachabilityDisabled_nothingHappen() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableHorizontalReachability(/* enabled */ false);
            robot.activity().setTopActivityInTransition(/* inTransition */ true);
            robot.doubleTapAt(100, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ false);
        });
    }

    @Test
    public void handleHorizontalDoubleTap_reachabilityEnabledInTransition_nothingHappen() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableHorizontalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ true);
            robot.doubleTapAt(100, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ false);
        });
    }

    @Test
    public void handleHorizontalDoubleTap_reachabilityDisabledNotInTransition_nothingHappen() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableHorizontalReachability(/* enabled */ false);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);
            robot.doubleTapAt(100, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ false);
        });
    }

    @Test
    public void handleHorizontalDoubleTap_leftInnerFrame_moveToLeft() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableHorizontalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.configureLetterboxInnerFrameWidth(/* left */ 100, /* right */ 200);
            robot.doubleTapAt(99, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ true);
            robot.applyOnConf((c) -> {
                c.checkToNextLeftStop(/* invoked */ true);
                c.checkToNextRightStop(/* invoked */ false);
            });
        });
    }

    @Test
    public void handleHorizontalDoubleTap_rightInnerFrame_moveToRight() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableHorizontalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.configureLetterboxInnerFrameWidth(/* left */ 100, /* right */ 200);
            robot.doubleTapAt(201, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ true);
            robot.applyOnConf((c) -> {
                c.checkToNextLeftStop(/* invoked */ false);
                c.checkToNextRightStop(/* invoked */ true);
            });
        });
    }

    @Test
    public void handleHorizontalDoubleTap_intoInnerFrame_noMove() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableHorizontalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.configureLetterboxInnerFrameWidth(/* left */ 100, /* right */ 200);
            robot.doubleTapAt(150, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ true);
            robot.applyOnConf((c) -> {
                c.checkToNextLeftStop(/* invoked */ false);
                c.checkToNextRightStop(/* invoked */ false);
            });
        });
    }


    @Test
    public void handleVerticalDoubleTap_reachabilityDisabled_nothingHappen() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableVerticalReachability(/* enabled */ false);
            robot.activity().setTopActivityInTransition(/* inTransition */ true);
            robot.doubleTapAt(100, 100);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ false);
        });
    }

    @Test
    public void handleVerticalDoubleTap_reachabilityEnabledInTransition_nothingHappen() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableVerticalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ true);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ false);
        });
    }

    @Test
    public void handleVerticalDoubleTap_reachabilityDisabledNotInTransition_nothingHappen() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableVerticalReachability(/* enabled */ false);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ false);
        });
    }

    @Test
    public void handleVerticalDoubleTap_topInnerFrame_moveToTop() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableVerticalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.configureLetterboxInnerFrameHeight(/* top */ 100, /* bottom */ 200);
            robot.doubleTapAt(100, 99);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ true);
            robot.applyOnConf((c) -> {
                c.checkToNextTopStop(/* invoked */ true);
                c.checkToNextBottomStop(/* invoked */ false);
            });
        });
    }

    @Test
    public void handleVerticalDoubleTap_bottomInnerFrame_moveToBottom() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableVerticalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.configureLetterboxInnerFrameHeight(/* top */ 100, /* bottom */ 200);
            robot.doubleTapAt(100, 201);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ true);
            robot.applyOnConf((c) -> {
                c.checkToNextTopStop(/* invoked */ false);
                c.checkToNextBottomStop(/* invoked */ true);
            });
        });
    }

    @Test
    public void handleVerticalDoubleTap_intoInnerFrame_noMove() {
        runTestScenario((robot) -> {
            robot.activity().createActivityWithComponent();
            robot.enableVerticalReachability(/* enabled */ true);
            robot.activity().setTopActivityInTransition(/* inTransition */ false);

            robot.configureLetterboxInnerFrameHeight(/* top */ 100, /* bottom */ 200);
            robot.doubleTapAt(100, 150);

            robot.checkLetterboxInnerFrameProvidedInvoked(/* invoked */ true);
            robot.applyOnConf((c) -> {
                c.checkToNextTopStop(/* invoked */ false);
                c.checkToNextBottomStop(/* invoked */ false);
            });
        });
    }


    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<ReachabilityPolicyRobotTest> consumer) {
        final ReachabilityPolicyRobotTest robot =
                new ReachabilityPolicyRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class ReachabilityPolicyRobotTest extends AppCompatRobotBase {

        private final Supplier<Rect> mLetterboxInnerBoundsSupplier = spy(Rect::new);

        ReachabilityPolicyRobotTest(@NonNull WindowManagerService wm,
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

        void configureLetterboxInnerFrameWidth(int left, int right) {
            doReturn(new Rect(left, /* top */ 0, right, /* bottom */ 100))
                    .when(mLetterboxInnerBoundsSupplier).get();
        }

        void configureLetterboxInnerFrameHeight(int top, int bottom) {
            doReturn(new Rect(/* left */ 0, top, /* right */ 100, bottom))
                    .when(mLetterboxInnerBoundsSupplier).get();
        }

        void enableHorizontalReachability(boolean enabled) {
            doReturn(enabled).when(getAppCompatReachabilityOverrides())
                    .isHorizontalReachabilityEnabled();
        }

        void enableVerticalReachability(boolean enabled) {
            doReturn(enabled).when(getAppCompatReachabilityOverrides())
                    .isVerticalReachabilityEnabled();
        }

        void doubleTapAt(int x, int y) {
            getAppCompatReachabilityPolicy().handleDoubleTap(x, y);
        }

        void checkLetterboxInnerFrameProvidedInvoked(boolean invoked) {
            verify(mLetterboxInnerBoundsSupplier, times(invoked ? 1 : 0)).get();
        }

        @NonNull
        private AppCompatReachabilityOverrides getAppCompatReachabilityOverrides() {
            return activity().top().mAppCompatController.getAppCompatReachabilityOverrides();
        }

        @NonNull
        private AppCompatReachabilityPolicy getAppCompatReachabilityPolicy() {
            return activity().top().mAppCompatController.getAppCompatReachabilityPolicy();
        }

    }

}
