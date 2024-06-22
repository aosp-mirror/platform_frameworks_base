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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.AppCompatOrientationCapability.OrientationCapabilityState.MIN_COUNT_TO_IGNORE_REQUEST_IN_LOOP;
import static com.android.server.wm.AppCompatOrientationCapability.OrientationCapabilityState.SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import com.android.server.wm.utils.TestComponentStack;

import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/**
 * Test class for {@link AppCompatOrientationCapability}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:AppCompatOrientationCapabilityTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppCompatOrientationCapabilityTest extends WindowTestsBase {

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_activityRelaunching_returnsTrue() {
        runTestScenario((robot) -> {
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_cameraCompatTreatment_returnsTrue() {
        runTestScenario((robot) -> {
            robot.prepareIsCameraCompatTreatmentEnabled(true);
            robot.prepareIsCameraCompatTreatmentEnabledAtBuildTime(true);
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);

            robot.createActivityWithComponentInNewTask();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(false);
            robot.prepareIsTreatmentEnabledForTopActivity(true);

            robot.checkShouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    public void testShouldIgnoreRequestedOrientation_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);

            robot.createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldNotIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    public void testShouldIgnoreRequestedOrientation_propertyIsTrue_returnsTrue() {
        runTestScenario((robot) -> {
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.enableProperty(PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION);

            robot.createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION})
    public void testShouldIgnoreRequestedOrientation_propertyIsFalseAndOverride_returnsFalse()
            throws Exception {
        runTestScenario((robot) -> {
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.disableProperty(PROPERTY_COMPAT_IGNORE_REQUESTED_ORIENTATION);

            robot.createActivityWithComponent();
            robot.prepareRelaunchingAfterRequestedOrientationChanged(true);

            robot.checkShouldNotIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    @Test
    public void testShouldIgnoreOrientationRequestLoop_overrideDisabled_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(false);

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
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.disableProperty(
                    PROPERTY_COMPAT_ALLOW_IGNORING_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(false);

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
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(true);

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
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(false);

            robot.checkShouldNotIgnoreOrientationLoop();
            robot.checkExpectedLoopCount(/* expectedCount */ 0);
        });
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED})
    public void testShouldIgnoreOrientationRequestLoop_timeout_returnsFalse() {
        runTestScenario((robot) -> {
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(false);

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
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(false);

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
            robot.prepareIsPolicyForIgnoringRequestedOrientationEnabled(true);
            robot.createActivityWithComponent();
            robot.prepareIsLetterboxedForFixedOrientationAndAspectRatio(false);

            robot.checkShouldNotIgnoreRequestedOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<OrientationCapabilityRobotTest> consumer) {
        spyOn(mWm.mLetterboxConfiguration);
        final OrientationCapabilityRobotTest robot =
                new OrientationCapabilityRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class OrientationCapabilityRobotTest {

        @NonNull
        private final ActivityTaskManagerService mAtm;
        @NonNull
        private final WindowManagerService mWm;
        @NonNull
        private final ActivityTaskSupervisor mSupervisor;
        @NonNull
        private final LetterboxConfiguration mLetterboxConfiguration;
        @NonNull
        private final TestComponentStack<ActivityRecord> mActivityStack;
        @NonNull
        private final TestComponentStack<Task> mTaskStack;
        @NonNull
        private final CurrentTimeMillisSupplierTest mTestCurrentTimeMillisSupplier;


        OrientationCapabilityRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            mAtm = atm;
            mWm = wm;
            mSupervisor = supervisor;
            mActivityStack = new TestComponentStack<>();
            mTaskStack = new TestComponentStack<>();
            mLetterboxConfiguration = mWm.mLetterboxConfiguration;
            mTestCurrentTimeMillisSupplier = new CurrentTimeMillisSupplierTest();
        }

        void prepareRelaunchingAfterRequestedOrientationChanged(boolean enabled) {
            getTopOrientationCapability().setRelaunchingAfterRequestedOrientationChanged(enabled);
        }

        void prepareIsPolicyForIgnoringRequestedOrientationEnabled(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration)
                    .isPolicyForIgnoringRequestedOrientationEnabled();
        }

        void prepareIsCameraCompatTreatmentEnabled(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration).isCameraCompatTreatmentEnabled();
        }

        void prepareIsCameraCompatTreatmentEnabledAtBuildTime(boolean enabled) {
            doReturn(enabled).when(mLetterboxConfiguration)
                    .isCameraCompatTreatmentEnabledAtBuildTime();
        }

        void prepareIsTreatmentEnabledForTopActivity(boolean enabled) {
            final DisplayRotationCompatPolicy displayPolicy = mActivityStack.top()
                    .mDisplayContent.mDisplayRotationCompatPolicy;
            spyOn(displayPolicy);
            doReturn(enabled).when(displayPolicy)
                    .isTreatmentEnabledForActivity(eq(mActivityStack.top()));
        }

        // Useful to reduce timeout during tests
        void prepareMockedTime() {
            getTopOrientationCapability().mOrientationCapabilityState.mCurrentTimeMillisSupplier =
                    mTestCurrentTimeMillisSupplier;
        }

        void delay() {
            mTestCurrentTimeMillisSupplier.delay(SET_ORIENTATION_REQUEST_COUNTER_TIMEOUT_MS);
        }

        void enableProperty(@NonNull String propertyName) {
            setPropertyValue(propertyName, /* enabled */ true);
        }

        void disableProperty(@NonNull String propertyName) {
            setPropertyValue(propertyName, /* enabled */ false);
        }

        void prepareIsLetterboxedForFixedOrientationAndAspectRatio(boolean enabled) {
            spyOn(mActivityStack.top());
            doReturn(enabled).when(mActivityStack.top())
                    .isLetterboxedForFixedOrientationAndAspectRatio();
        }

        void createActivityWithComponent() {
            createActivityWithComponentInNewTask(/* inNewTask */ mTaskStack.isEmpty());
        }

        void createActivityWithComponentInNewTask() {
            createActivityWithComponentInNewTask(/* inNewTask */ true);
        }

        private void createActivityWithComponentInNewTask(boolean inNewTask) {
            if (inNewTask) {
                createNewTask();
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

        void checkShouldIgnoreRequestedOrientation(
                @Configuration.Orientation int expectedOrientation) {
            assertTrue(getTopOrientationCapability()
                    .shouldIgnoreRequestedOrientation(expectedOrientation));
        }

        void checkShouldNotIgnoreRequestedOrientation(
                @Configuration.Orientation int expectedOrientation) {
            assertFalse(getTopOrientationCapability()
                    .shouldIgnoreRequestedOrientation(expectedOrientation));
        }

        void checkExpectedLoopCount(int expectedCount) {
            assertEquals(expectedCount, getTopOrientationCapability()
                    .getSetOrientationRequestCounter());
        }

        void checkShouldNotIgnoreOrientationLoop() {
            assertFalse(getTopOrientationCapability().shouldIgnoreOrientationRequestLoop());
        }

        void checkShouldIgnoreOrientationLoop() {
            assertTrue(getTopOrientationCapability().shouldIgnoreOrientationRequestLoop());
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

        private AppCompatOrientationCapability getTopOrientationCapability() {
            return mActivityStack.top().mAppCompatController.getAppCompatCapability()
                    .getAppCompatOrientationCapability();
        }

        private void createNewTask() {
            final DisplayContent displayContent = new TestDisplayContent
                    .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
            final Task newTask = new TaskBuilder(mSupervisor).setDisplay(displayContent).build();
            mTaskStack.push(newTask);
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

        private static class CurrentTimeMillisSupplierTest implements LongSupplier {

            private long mCurrenTimeMillis = System.currentTimeMillis();

            @Override
            public long getAsLong() {
                return mCurrenTimeMillis;
            }

            public void delay(long delay) {
                mCurrenTimeMillis += delay;
            }
        }
    }
}
