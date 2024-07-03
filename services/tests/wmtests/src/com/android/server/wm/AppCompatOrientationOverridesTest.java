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

import static android.content.pm.ActivityInfo.OVERRIDE_CAMERA_COMPAT_DISABLE_REFRESH;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.view.WindowManager.PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.AppCompatOrientationOverrides.OrientationOverridesState.MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP;
import static com.android.server.wm.AppCompatOrientationOverrides.OrientationOverridesState.SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import com.android.server.wm.utils.CurrentTimeMillisSupplierFake;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Test class for {@link AppCompatOrientationOverrides}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatOrientationOverridesTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatOrientationOverridesTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

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
    public void testShouldIgnoreOrientationRequestLoop_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(false);
            });
            robot.checkRequestLoopExtended((i) -> {
                robot.checkShouldNotIgnoreOrientationLoop();
                robot.checkExpectedLoopCount(/* expectedCount */ 0);
            });
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_propertyIsFalseAndOverride_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.prop().disable(
                    PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(false);
            });
            robot.checkRequestLoopExtended((i) -> {
                robot.checkShouldNotIgnoreOrientationLoop();
                robot.checkExpectedLoopCount(/* expectedCount */ 0);
            });
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_isLetterboxed_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(true);
            });
            robot.checkRequestLoopExtended((i) -> {
                robot.checkShouldNotIgnoreOrientationLoop();
                robot.checkExpectedLoopCount(/* expectedCount */ i);
            });
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_noLoop_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(false);
            });

            robot.checkShouldNotIgnoreOrientationLoop();
            robot.checkExpectedLoopCount(/* expectedCount */ 0);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_timeout_returnsFalse() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(false);
            });

            robot.prepareMockedTime();
            robot.checkRequestLoopExtended((i) -> {
                robot.checkShouldNotIgnoreOrientationLoop();
                robot.checkExpectedLoopCount(/* expectedCount */ 0);
                robot.delay();
            });
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_returnsTrue() {
        runTestScenario((robot) -> {
            robot.conf().enablePolicyForIgnoringRequestedOrientation(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setLetterboxedForFixedOrientationAndAspectRatio(false);
            });

            robot.checkRequestLoop((i) -> {
                robot.checkShouldNotIgnoreOrientationLoop();
                robot.checkExpectedLoopCount(/* expectedCount */ i);
            });
            robot.checkShouldIgnoreOrientationLoop();
            robot.checkExpectedLoopCount(/* expectedCount */ MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP);
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
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<OrientationOverridesRobotTest> consumer) {
        spyOn(mWm.mLetterboxConfiguration);
        final OrientationOverridesRobotTest robot =
                new OrientationOverridesRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class OrientationOverridesRobotTest extends AppCompatRobotBase {

        @NonNull
        private final CurrentTimeMillisSupplierFake mTestCurrentTimeMillisSupplier;

        OrientationOverridesRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
            mTestCurrentTimeMillisSupplier = new CurrentTimeMillisSupplierFake();
        }

        void prepareRelaunchingAfterRequestedOrientationChanged(boolean enabled) {
            getTopOrientationOverrides().setRelaunchingAfterRequestedOrientationChanged(enabled);
        }

        // Useful to reduce timeout during tests
        void prepareMockedTime() {
            getTopOrientationOverrides().mOrientationOverridesState.mCurrentTimeMillisSupplier =
                    mTestCurrentTimeMillisSupplier;
        }

        void delay() {
            mTestCurrentTimeMillisSupplier.delay(SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS);
        }

        void checkShouldIgnoreRequestedOrientation(boolean expected,
                @Configuration.Orientation int requestedOrientation) {
            assertEquals(expected, getTopOrientationOverrides()
                    .shouldIgnoreRequestedOrientation(requestedOrientation));
        }

        void checkExpectedLoopCount(int expectedCount) {
            assertEquals(expectedCount, getTopOrientationOverrides()
                    .getSetOrientationRequestCounter());
        }

        void checkShouldNotIgnoreOrientationLoop() {
            assertFalse(getTopOrientationOverrides().shouldIgnoreOrientationRequestLoop());
        }

        void checkShouldIgnoreOrientationLoop() {
            assertTrue(getTopOrientationOverrides().shouldIgnoreOrientationRequestLoop());
        }

        void checkRequestLoop(IntConsumer consumer) {
            for (int i = 0; i < MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i++) {
                consumer.accept(i);
            }
        }

        void checkRequestLoopExtended(IntConsumer consumer) {
            for (int i = 0; i <= MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP; i++) {
                consumer.accept(i);
            }
        }

        private AppCompatOrientationOverrides getTopOrientationOverrides() {
            return activity().top().mAppCompatController.getAppCompatOverrides()
                    .getAppCompatOrientationOverrides();
        }
    }
}
