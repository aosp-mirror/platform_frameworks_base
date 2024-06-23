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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import com.android.server.wm.utils.TestComponentStack;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Test class for {@link TransparentPolicy}.
 *
 * Build/Install/Run:
 * atest WmTests:TransparentPolicyTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class TransparentPolicyTest extends WindowTestsBase {

    @Test
    public void testNotStartingWhenDisabled() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivityInTask();

            robot.checkTopActivityPolicyStateIsNotRunning();
        }, /* policyEnabled */ false);
    }

    @Test
    public void testNotStartingWithoutTask() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivity();

            robot.checkTopActivityPolicyStartNotInvoked();
            robot.checkTopActivityPolicyStateIsNotRunning();
        });
    }

    @Test
    public void testPolicyRunningWhenTransparentIsUsed() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivityInTask();

            robot.checkTopActivityPolicyStartNotInvoked();
            robot.checkTopActivityPolicyStateIsRunning();
        });
    }

    @Test
    public void testCleanLetterboxConfigListenerWhenTranslucentIsDestroyed() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivityInTask();
            robot.checkTopActivityPolicyStartNotInvoked();
            robot.checkTopActivityPolicyStateIsRunning();

            robot.clearInteractions();
            robot.destroyTopActivity();

            robot.checkTopActivityPolicyStopInvoked();
            robot.checkTopActivityPolicyStateIsNotRunning();
        });
    }

    @Test
    public void testApplyStrategyAgainWhenOpaqueIsDestroyed() {
        runTestScenario((robot) -> {
            robot.launchOpaqueActivityInTask();
            robot.checkTopActivityPolicyStateIsNotRunning();

            robot.launchTransparentActivityInTask();
            robot.checkTopActivityPolicyStateIsRunning();

            robot.destroyActivity(/* fromTop */ 1);
            robot.checkTopActivityPolicyStartInvoked();
        });
    }

    @Test
    public void testResetOpaqueReferenceWhenOpaqueIsDestroyed() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivityInTask();

            robot.clearInteractions();
            robot.destroyActivity(/* fromTop */ 1);

            robot.checkTopActivityPolicyStartInvoked();
            robot.checkTopActivityPolicyStateIsNotRunning();
        });
    }

    @Test
    public void testNotApplyStrategyAgainWhenOpaqueIsNotDestroyed() {
        runTestScenario((robot) -> {
            robot.launchOpaqueActivityInTask();
            robot.checkTopActivityPolicyStateIsNotRunning();

            robot.launchTransparentActivityInTask();
            robot.checkTopActivityPolicyStateIsRunning();

            robot.clearInteractions();
            robot.checkTopActivityPolicyStopNotInvoked();
        });
    }

    @Test
    public void testApplyStrategyToTranslucentActivities() {
        runTestScenario((robot) -> {
            robot.configureTopActivity(/* minAspect */ 1.2f, /* maxAspect */ 1.5f,
                    SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable */ true);
            robot.configureTopActivityIgnoreOrientationRequest(true);
            robot.launchActivity(/* minAspect */ 1.1f, /* maxAspect */ 3f,
                    SCREEN_ORIENTATION_LANDSCAPE, /* transparent */true, /* addToTask */true);
            robot.checkTopActivityPolicyStateIsRunning();
            robot.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
            robot.checkTopOrientation(SCREEN_ORIENTATION_PORTRAIT);
            robot.checkTopAspectRatios(/* minAspectRatio */ 1.2f, /* maxAspectRatio */ 1.5f);
        });
    }

    @Test
    public void testApplyStrategyToTransparentActivitiesRetainsWindowConfigurationProperties() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivity();

            robot.forceChangeInTopActivityConfiguration();
            robot.attachTopActivityToTask();

            robot.checkTopActivityConfigurationConfiguration();
        });
    }

    @Test
    public void testApplyStrategyToMultipleTranslucentActivities() {
        runTestScenario((robot) -> {
            robot.launchTransparentActivityInTask();
            robot.checkTopActivityPolicyStateIsRunning();
            robot.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);

            robot.launchTransparentActivityInTask();
            robot.checkTopActivityPolicyStateIsRunning();
            robot.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 2);
        });
    }

    @Test
    public void testNotApplyStrategyToTranslucentActivitiesOverEmbeddedActivities() {
        runTestScenario((robot) -> {
            robot.configureTopActivityAsEmbedded();
            robot.launchTransparentActivityInTask();

            robot.checkTopActivityPolicyStartNotInvoked();
            robot.checkTopActivityPolicyStateIsNotRunning();
        });
    }

    @Test
    public void testTranslucentActivitiesDontGoInSizeCompatMode() {
        runTestScenario((robot) -> {
            robot.configureTopActivityIgnoreOrientationRequest(true);
            robot.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            robot.rotateDisplayForTopActivity(ROTATION_90);
            robot.checkTopActivitySizeCompatMode(/* inScm */ true);
            robot.rotateDisplayForTopActivity(ROTATION_0);
            robot.checkTopActivitySizeCompatMode(/* inScm */ false);

            robot.launchTransparentActivityInTask();
            robot.checkTopActivitySizeCompatMode(/* inScm */ false);
            robot.rotateDisplayForTopActivity(ROTATION_90);
            robot.checkTopActivitySizeCompatMode(/* inScm */ false);
        }, /* displayWidth */ 2800,  /* displayHeight */ 1400);
    }

    @Test
    public void testCheckOpaqueIsLetterboxedWhenStrategyIsApplied() {
        runTestScenario((robot) -> {
            robot.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            robot.configureTopActivityIgnoreOrientationRequest(true);
            robot.launchTransparentActivity();

            robot.assertFalseOnTopActivity(ActivityRecord::fillsParent);
            robot.assertTrueOnActivity(/* fromTop */ 1, ActivityRecord::fillsParent);
            robot.applyTo(/* fromTop */ 1, (activity) -> {
                activity.finishing = true;
            });
            robot.assertFalseOnActivity(/* fromTop */ 1, ActivityRecord::occludesParent);
            robot.attachTopActivityToTask();

            robot.checkTopActivityPolicyStateIsNotRunning();
        });
    }

    @Test
    public void testTranslucentActivitiesWhenUnfolding() {
        runTestScenario((robot) -> {
            robot.applyToTop((activity) -> {
                activity.mWmService.mLetterboxConfiguration
                        .setLetterboxHorizontalPositionMultiplier(1.0f);
            });
            robot.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            robot.configureTopActivityIgnoreOrientationRequest(true);
            robot.launchTransparentActivityInTask();
            robot.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);

            robot.configureTaskWindowingMode(WINDOWING_MODE_FULLSCREEN);

            robot.configureTopActivityFoldablePosture(/* isHalfFolded */ true,
                    /* isTabletop */ false);
            robot.checkTopActivityRecomputedConfiguration();
            robot.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
            robot.clearInteractions();

            robot.configureTopActivityFoldablePosture(/* isHalfFolded */ false,
                    /* isTabletop */ false);
            robot.checkTopActivityRecomputedConfiguration();
            robot.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
        }, /* displayWidth */ 2800,  /* displayHeight */ 1400);
    }


    @Test
    public void testTranslucentActivity_clearSizeCompatMode_inheritedCompatDisplayInsetsCleared() {
        runTestScenario((robot) -> {
            robot.configureTopActivityIgnoreOrientationRequest(true);
            robot.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            // Rotate to put activity in size compat mode.
            robot.rotateDisplayForTopActivity(ROTATION_90);
            robot.checkTopActivitySizeCompatMode(/* inScm */ true);

            robot.launchTransparentActivityInTask();
            robot.assertNotNullOnTopActivity(ActivityRecord::getCompatDisplayInsets);
            robot.applyToTop(ActivityRecord::clearSizeCompatMode);
            robot.assertNullOnTopActivity(ActivityRecord::getCompatDisplayInsets);
        });
    }

    private void runTestScenario(Consumer<TransparentPolicyRobotTest> consumer,
                                 boolean policyEnabled, int displayWidth, int displayHeight) {
        spyOn(mWm.mLetterboxConfiguration);
        when(mWm.mLetterboxConfiguration.isTranslucentLetterboxingEnabled())
                .thenReturn(policyEnabled);
        final TestDisplayContent.Builder builder = new TestDisplayContent.Builder(mAtm,
                displayWidth, displayHeight);
        final Task task = new TaskBuilder(mSupervisor).setDisplay(builder.build())
                .setCreateActivity(true).build();
        final ActivityRecord opaqueActivity = task.getTopNonFinishingActivity();
        final TransparentPolicyRobotTest robot = new TransparentPolicyRobotTest(mAtm, task,
                opaqueActivity);
        consumer.accept(robot);
    }

    private void runTestScenario(Consumer<TransparentPolicyRobotTest> consumer,
                                 int displayWidth, int displayHeight) {
        runTestScenario(consumer, /* policyEnabled */ true, displayWidth, displayHeight);
    }

    private void runTestScenario(Consumer<TransparentPolicyRobotTest> consumer,
                                 boolean policyEnabled) {
        runTestScenario(consumer, policyEnabled, /* displayWidth */ 2000,
                /* displayHeight */ 1000);
    }

    private void runTestScenario(Consumer<TransparentPolicyRobotTest> consumer) {
        runTestScenario(consumer, /* policyEnabled */ true);
    }

    /**
     * Robot pattern implementation for TransparentPolicy
     * TODO(b/344587983): Extract Robot to be reused in different test classes.
     */
    private static class TransparentPolicyRobotTest {

        @NonNull
        private final ActivityTaskManagerService mAtm;
        @NonNull
        private final Task mTask;
        @NonNull
        private final TestComponentStack<ActivityRecord> mActivityStack;
        @NonNull
        private WindowConfiguration mTopActivityWindowConfiguration;

        private TransparentPolicyRobotTest(@NonNull ActivityTaskManagerService atm,
                                           @NonNull Task task,
                                           @NonNull ActivityRecord opaqueActivity) {
            mAtm = atm;
            mTask = task;
            mActivityStack = new TestComponentStack<>();
            mActivityStack.push(opaqueActivity);
            spyOn(opaqueActivity.mAppCompatController.getTransparentPolicy());
        }

        void configureTopActivityAsEmbedded() {
            final ActivityRecord topActivity = mActivityStack.top();
            spyOn(topActivity);
            doReturn(true).when(topActivity).isEmbedded();
        }

        private void launchActivity(float minAspectRatio, float maxAspectRatio,
                                    @Configuration.Orientation int orientation, boolean transparent,
                                    boolean addToTask) {
            final ActivityBuilder activityBuilder = new ActivityBuilder(mAtm)
                    .setScreenOrientation(orientation)
                    .setLaunchedFromUid(mActivityStack.base().getUid());
            if (transparent) {
                activityBuilder.setActivityTheme(android.R.style.Theme_Translucent);
            }
            if (minAspectRatio >= 0) {
                activityBuilder.setMinAspectRatio(minAspectRatio);
            }
            if (maxAspectRatio >= 0) {
                activityBuilder.setMaxAspectRatio(maxAspectRatio);
            }
            final ActivityRecord newActivity = activityBuilder.build();
            if (addToTask) {
                mTask.addChild(newActivity);
            }
            spyOn(newActivity.mAppCompatController.getTransparentPolicy());
            mActivityStack.push(newActivity);
        }

        void attachTopActivityToTask() {
            mTask.addChild(mActivityStack.top());
        }

        void launchTransparentActivity() {
            launchActivity(/*minAspectRatio */ -1, /* maxAspectRatio */ -1,
                    SCREEN_ORIENTATION_PORTRAIT, /* transparent */ true,
                    /* addToTask */ false);
        }

        void launchTransparentActivityInTask() {
            launchActivity(/*minAspectRatio */ -1, /* maxAspectRatio */ -1,
                    SCREEN_ORIENTATION_PORTRAIT, /* transparent */ true,
                    /* addToTask */true);
        }

        void launchOpaqueActivityInTask() {
            launchActivity(/*minAspectRatio */ -1, /* maxAspectRatio */ -1,
                    SCREEN_ORIENTATION_PORTRAIT, /* transparent */ false,
                    /* addToTask */true);
        }

        void destroyTopActivity() {
            mActivityStack.top().removeImmediately();
        }

        void destroyActivity(int fromTop) {
            mActivityStack.applyTo(/* fromTop */ fromTop, ActivityRecord::removeImmediately);
        }

        void forceChangeInTopActivityConfiguration() {
            mActivityStack.applyToTop((activity) -> {
                final Configuration requestedConfig = activity.getRequestedOverrideConfiguration();
                mTopActivityWindowConfiguration = requestedConfig.windowConfiguration;
                mTopActivityWindowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
                mTopActivityWindowConfiguration.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
                mTopActivityWindowConfiguration.setAlwaysOnTop(true);
                activity.onRequestedOverrideConfigurationChanged(requestedConfig);
            });
        }

        void checkTopActivityPolicyStateIsRunning() {
            assertTrue(mActivityStack.top().mAppCompatController
                    .getTransparentPolicy().isRunning());
        }

        void checkTopActivityPolicyStateIsNotRunning() {
            assertFalse(mActivityStack.top().mAppCompatController
                    .getTransparentPolicy().isRunning());
        }

        void checkTopActivityPolicyStopInvoked() {
            verify(mActivityStack.top().mAppCompatController.getTransparentPolicy()).stop();
        }

        void checkTopActivityPolicyStopNotInvoked() {
            mActivityStack.applyToTop((activity) -> {
                verify(activity.mAppCompatController.getTransparentPolicy(), never()).stop();
            });
        }

        void checkTopActivityPolicyStartInvoked() {
            mActivityStack.applyToTop((activity) -> {
                verify(activity.mAppCompatController.getTransparentPolicy()).start();
            });
        }

        void checkTopActivityPolicyStartNotInvoked() {
            verify(mActivityStack.top().mAppCompatController.getTransparentPolicy(),
                    never()).start();
        }

        void assertTrueOnActivity(int fromTop, Predicate<ActivityRecord> predicate) {
            mActivityStack.applyTo(fromTop, (activity) -> {
                Assert.assertTrue(predicate.test(activity));
            });
        }

        void assertFalseOnTopActivity(Predicate<ActivityRecord> predicate) {
            Assert.assertFalse(predicate.test(mActivityStack.top()));
        }

        void assertFalseOnActivity(int fromTop, Predicate<ActivityRecord> predicate) {
            mActivityStack.applyTo(fromTop, (activity) -> {
                Assert.assertFalse(predicate.test(activity));
            });
        }

        void assertNotNullOnTopActivity(Function<ActivityRecord, Object> getter) {
            Assert.assertNotNull(getter.apply(mActivityStack.top()));
        }

        void assertNullOnTopActivity(Function<ActivityRecord, Object> getter) {
            Assert.assertNull(getter.apply(mActivityStack.top()));
        }

        void checkTopActivityConfigurationConfiguration() {
            mActivityStack.applyToTop((activity) -> {
                // The original override of WindowConfiguration should keep.
                assertEquals(ACTIVITY_TYPE_STANDARD, activity.getActivityType());
                assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                        mTopActivityWindowConfiguration.getWindowingMode());
                assertTrue(mTopActivityWindowConfiguration.isAlwaysOnTop());
                // Unless display is going to be rotated, it should always inherit from parent.
                assertEquals(ROTATION_UNDEFINED,
                        mTopActivityWindowConfiguration.getDisplayRotation());
            });
        }

        void checkTopActivityHasInheritedBoundsFrom(int fromTop) {
            final ActivityRecord topActivity = mActivityStack.top();
            final ActivityRecord otherActivity = mActivityStack.getFromTop(/* fromTop */ fromTop);
            final Rect opaqueBounds = otherActivity.getConfiguration().windowConfiguration
                    .getBounds();
            final Rect translucentRequestedBounds = topActivity.getRequestedOverrideBounds();
            Assert.assertEquals(opaqueBounds, translucentRequestedBounds);
        }

        void checkTopActivityRecomputedConfiguration() {
            verify(mActivityStack.top()).recomputeConfiguration();
        }

        void checkTopOrientation(int orientation) {
            Assert.assertEquals(orientation, mActivityStack.top()
                    .getRequestedConfigurationOrientation());
        }

        void configureTaskWindowingMode(int windowingMode) {
            mTask.setWindowingMode(windowingMode);
        }

        void checkTopAspectRatios(float minAspectRatio, float maxAspectRatio) {
            final ActivityRecord topActivity = mActivityStack.top();
            Assert.assertEquals(minAspectRatio, topActivity.getMinAspectRatio(), 0.0001);
            Assert.assertEquals(maxAspectRatio, topActivity.getMaxAspectRatio(), 0.0001);
        }

        void checkTopActivitySizeCompatMode(boolean inScm) {
            Assert.assertEquals(inScm, mActivityStack.top().inSizeCompatMode());
        }

        void clearInteractions() {
            mActivityStack.applyToAll((activity) -> {
                clearInvocations(activity);
                clearInvocations(activity.mAppCompatController.getTransparentPolicy());
            });
        }

        void configureTopActivity(float minAspect, float maxAspect, int screenOrientation,
                                  boolean isUnresizable) {
            prepareLimitedBounds(mActivityStack.top(), minAspect, maxAspect, screenOrientation,
                    isUnresizable);
        }

        void configureTopActivityIgnoreOrientationRequest(boolean ignoreOrientationRequest) {
            mActivityStack.top().mDisplayContent
                    .setIgnoreOrientationRequest(ignoreOrientationRequest);
        }

        void configureUnresizableTopActivity(int screenOrientation) {
            configureTopActivity(-1, -1, screenOrientation, true);
        }

        void applyToTop(Consumer<ActivityRecord> consumer) {
            consumer.accept(mActivityStack.top());
        }

        void applyTo(int fromTop, Consumer<ActivityRecord> consumer) {
            mActivityStack.applyTo(fromTop, consumer);
        }

        void rotateDisplayForTopActivity(int rotation) {
            rotateDisplay(mActivityStack.top().mDisplayContent, rotation);
        }

        /**
         * Setups activity with restriction on its bounds, such as maxAspect, minAspect,
         * fixed orientation, and/or whether it is resizable.
         */
        void prepareLimitedBounds(ActivityRecord activity, float minAspect, float maxAspect,
                                  int screenOrientation, boolean isUnresizable) {
            activity.info.resizeMode = isUnresizable
                    ? RESIZE_MODE_UNRESIZEABLE
                    : RESIZE_MODE_RESIZEABLE;
            final Task task = activity.getTask();
            if (task != null) {
                // Update the Task resize value as activity will follow the task.
                task.mResizeMode = activity.info.resizeMode;
                task.getRootActivity().info.resizeMode = activity.info.resizeMode;
            }
            activity.setVisibleRequested(true);
            if (maxAspect >= 0) {
                activity.info.setMaxAspectRatio(maxAspect);
            }
            if (minAspect >= 0) {
                activity.info.setMinAspectRatio(minAspect);
            }
            if (screenOrientation != SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.info.screenOrientation = screenOrientation;
                activity.setRequestedOrientation(screenOrientation);
            }
            // Make sure to use the provided configuration to construct the size compat fields.
            activity.clearSizeCompatMode();
            activity.ensureActivityConfiguration();
            // Make sure the display configuration reflects the change of activity.
            if (activity.mDisplayContent.updateOrientation()) {
                activity.mDisplayContent.sendNewConfiguration();
            }
        }

        void configureTopActivityFoldablePosture(boolean isHalfFolded, boolean isTabletop) {
            mActivityStack.applyToTop((activity) -> {
                final DisplayRotation r = activity.mDisplayContent.getDisplayRotation();
                doReturn(isHalfFolded).when(r).isDisplaySeparatingHinge();
                doReturn(false).when(r)
                        .isDeviceInPosture(any(DeviceStateController.DeviceState.class),
                                anyBoolean());
                if (isHalfFolded) {
                    doReturn(true).when(r)
                            .isDeviceInPosture(DeviceStateController.DeviceState.HALF_FOLDED,
                                    isTabletop);
                }
                activity.recomputeConfiguration();
            });
        }

        private static void rotateDisplay(DisplayContent display, int rotation) {
            final Configuration c = new Configuration();
            display.getDisplayRotation().setRotation(rotation);
            display.computeScreenConfiguration(c);
            display.onRequestedOverrideConfigurationChanged(c);
        }
    }
}
