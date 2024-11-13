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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.Mockito.clearInvocations;

import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

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
            robot.transparentActivity((ta) -> {
                ta.launchTransparentActivityInTask();

                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
            });
        }, /* policyEnabled */ false);
    }

    @Test
    public void testNotStartingWithoutTask() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.launchTransparentActivity();

                ta.checkTopActivityTransparentPolicyStartNotInvoked();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
            });
        });
    }

    @Test
    public void testPolicyRunningWhenTransparentIsUsed() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setIgnoreOrientationRequest(true);
                ta.launchTransparentActivityInTask();

                ta.checkTopActivityTransparentPolicyStartNotInvoked();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);
            });
        });
    }

    @Test
    public void testCleanLetterboxConfigListenerWhenTranslucentIsDestroyed() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setIgnoreOrientationRequest(true);
                ta.launchTransparentActivityInTask();
                ta.checkTopActivityTransparentPolicyStartNotInvoked();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);

                robot.clearInteractions();
                ta.activity().destroyTopActivity();

                ta.checkTopActivityTransparentPolicyStopInvoked();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
            });
        });
    }

    @Test
    public void testApplyStrategyAgainWhenOpaqueIsDestroyed() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setIgnoreOrientationRequest(true);
                ta.launchOpaqueActivityInTask();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);

                ta.launchTransparentActivityInTask();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);

                ta.activity().destroyActivity(/* fromTop */ 1);
                ta.checkTopActivityTransparentPolicyStartInvoked();
            });
        });
    }

    @Test
    public void testResetOpaqueReferenceWhenOpaqueIsDestroyed() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.launchTransparentActivityInTask();

                robot.clearInteractions();
                ta.activity().destroyActivity(/* fromTop */ 1);

                ta.checkTopActivityTransparentPolicyStartInvoked();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
            });
        });
    }

    @Test
    public void testNotApplyStrategyAgainWhenOpaqueIsNotDestroyed() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setIgnoreOrientationRequest(true);
                ta.launchOpaqueActivityInTask();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);

                ta.launchTransparentActivityInTask();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);

                robot.clearInteractions();
                ta.checkTopActivityTransparentPolicyStopNotInvoked();
            });
        });
    }

    @Test
    public void testApplyStrategyToTranslucentActivities() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.applyOnActivity((a) -> {
                    a.configureTopActivity(/* minAspect */ 1.2f, /* maxAspect */ 1.5f,
                            SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable */ true);
                    a.setIgnoreOrientationRequest(true);
                    a.launchActivity(/* minAspect */ 1.1f, /* maxAspect */ 3f,
                            SCREEN_ORIENTATION_LANDSCAPE, /* transparent */true,
                            /* withComponent */ false, /* addToTask */true);
                });
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);
                ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
                ta.applyOnActivity((a) -> {
                    a.checkTopActivityConfigOrientation(SCREEN_ORIENTATION_PORTRAIT);
                    a.checkTopActivityAspectRatios(/* minAspectRatio */ 1.2f,
                            /* maxAspectRatio */ 1.5f);
                });
            });
        });
    }

    @Test
    public void testApplyStrategyToTransparentActivitiesRetainsWindowConfigurationProperties() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setIgnoreOrientationRequest(true);
                ta.launchTransparentActivity();

                ta.forceChangeInTopActivityConfiguration();
                ta.activity().attachTopActivityToTask();

                ta.checkTopActivityConfigurationConfiguration();
            });
        });
    }

    @Test
    public void testApplyStrategyToMultipleTranslucentActivities() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setIgnoreOrientationRequest(true);
                ta.launchTransparentActivityInTask();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);
                ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);

                ta.launchTransparentActivityInTask();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);
                ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 2);
            });
        });
    }

    @Test
    public void testNotApplyStrategyToTranslucentActivitiesOverEmbeddedActivities() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.activity().setTopActivityAsEmbedded(true);
                ta.launchTransparentActivityInTask();

                ta.checkTopActivityTransparentPolicyStartNotInvoked();
                ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
            });
        });
    }

    @EnableFlags(com.android.window.flags.Flags.FLAG_RESPECT_NON_TOP_VISIBLE_FIXED_ORIENTATION)
    @Test
    public void testNotRunStrategyToTranslucentActivitiesIfRespectOrientation() {
        runTestScenario(robot -> robot.transparentActivity(ta -> ta.applyOnActivity((a) -> {
            a.setIgnoreOrientationRequest(false);
            // The translucent activity is SCREEN_ORIENTATION_PORTRAIT.
            ta.launchTransparentActivityInTask();
            // Though TransparentPolicyState will be started, it won't be considered as running.
            ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);

            // If the display changes to ignore orientation request, e.g. unfold, the policy should
            // take effect.
            a.setIgnoreOrientationRequest(true);
            ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ true);
            ta.setDisplayContentBounds(0, 0, 900, 1800);
            ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
        })), /* displayWidth */ 500,  /* displayHeight */ 1000);
    }

    @Test
    public void testNotRunStrategyToTranslucentActivitiesIfTaskIsFreeform() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.applyOnActivity((a) -> {
                    a.setIgnoreOrientationRequest(true);
                    ta.launchTransparentActivityInTask();
                    a.setTaskWindowingMode(WINDOWING_MODE_FREEFORM);

                    ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
                });
            });
        }, /* displayWidth */ 2800,  /* displayHeight */ 1400);
    }

    @Test
    public void testTranslucentActivitiesDontGoInSizeCompatMode() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.applyOnActivity((a) -> {
                    a.setIgnoreOrientationRequest(true);
                    a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
                    a.rotateDisplayForTopActivity(ROTATION_90);
                    a.checkTopActivityInSizeCompatMode(/* inScm */ true);
                    a.rotateDisplayForTopActivity(ROTATION_0);
                    a.checkTopActivityInSizeCompatMode(/* inScm */ false);

                    ta.launchTransparentActivityInTask();

                    a.checkTopActivityInSizeCompatMode(/* inScm */ false);
                    a.rotateDisplayForTopActivity(ROTATION_90);
                    a.checkTopActivityInSizeCompatMode(/* inScm */ false);
                });
            });
        }, /* displayWidth */ 2800,  /* displayHeight */ 1400);
    }

    @Test
    public void testCheckOpaqueIsLetterboxedWhenStrategyIsApplied() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.applyOnActivity((a) -> {
                    a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
                    a.setIgnoreOrientationRequest(true);
                    ta.launchTransparentActivity();

                    a.assertFalseOnTopActivity(ActivityRecord::fillsParent);
                    a.assertTrueOnActivity(/* fromTop */ 1, ActivityRecord::fillsParent);
                    a.applyToActivity(/* fromTop */ 1, (activity) -> {
                        activity.finishing = true;
                    });
                    a.assertFalseOnActivity(/* fromTop */ 1, ActivityRecord::occludesParent);
                    a.attachTopActivityToTask();

                    ta.checkTopActivityTransparentPolicyStateIsRunning(/* running */ false);
                });
            });
        });
    }

    @Test
    public void testTranslucentActivitiesWhenUnfolding() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.applyOnActivity((a) -> {
                    a.applyToTopActivity((topActivity) -> {
                        topActivity.mWmService.mAppCompatConfiguration
                                .setLetterboxHorizontalPositionMultiplier(1.0f);
                    });
                    a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
                    a.setIgnoreOrientationRequest(true);
                    ta.launchTransparentActivityInTask();
                    ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);

                    a.setTaskWindowingMode(WINDOWING_MODE_FULLSCREEN);
                    a.configureTopActivityFoldablePosture(/* isHalfFolded */ true,
                            /* isTabletop */ false);
                    a.checkTopActivityRecomputedConfiguration();
                    ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
                    robot.clearInteractions();
                    a.configureTopActivityFoldablePosture(/* isHalfFolded */ false,
                            /* isTabletop */ false);
                    a.checkTopActivityRecomputedConfiguration();
                    ta.checkTopActivityHasInheritedBoundsFrom(/* fromTop */ 1);
                });
            });
        }, /* displayWidth */ 2800,  /* displayHeight */ 1400);
    }


    @Test
    public void testTranslucentActivity_clearSizeCompatMode_inheritedCompatDisplayInsetsCleared() {
        runTestScenario((robot) -> {
            robot.transparentActivity((ta) -> {
                ta.applyOnActivity((a) -> {
                    a.setIgnoreOrientationRequest(true);
                    a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
                    // Rotate to put activity in size compat mode.
                    a.rotateDisplayForTopActivity(ROTATION_90);
                    a.checkTopActivityInSizeCompatMode(/* inScm */ true);

                    ta.launchTransparentActivityInTask();
                    a.assertNotNullOnTopActivity(ActivityRecord::getAppCompatDisplayInsets);
                    a.applyToTopActivity((top) -> {
                        top.mAppCompatController.getAppCompatSizeCompatModePolicy()
                                .clearSizeCompatMode();
                    });
                    a.assertNullOnTopActivity(ActivityRecord::getAppCompatDisplayInsets);
                });
            });
        });
    }

    private void runTestScenario(Consumer<TransparentPolicyRobotTest> consumer,
                                 boolean policyEnabled, int displayWidth, int displayHeight) {
        final TransparentPolicyRobotTest robot =
                new TransparentPolicyRobotTest(mWm, mAtm, mSupervisor, displayWidth, displayHeight);
        robot.conf().enableTranslucentPolicy(policyEnabled);
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
     */
    private static class TransparentPolicyRobotTest extends AppCompatRobotBase {

        @NonNull
        private final AppCompatTransparentActivityRobot mTransparentActivityRobot;

        private TransparentPolicyRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor,
                int displayWidth, int displayHeight) {
            super(wm, atm, supervisor, displayWidth, displayHeight);
            mTransparentActivityRobot = new AppCompatTransparentActivityRobot(activity());
            // We always create at least an opaque activity in a Task
            activity().createNewTaskWithBaseActivity();
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getTransparentPolicy());
        }

        void transparentActivity(@NonNull Consumer<AppCompatTransparentActivityRobot> consumer) {
            consumer.accept(mTransparentActivityRobot);
        }

        void clearInteractions() {
            activity().applyToAllActivities((activity) -> {
                clearInvocations(activity);
                clearInvocations(activity.mAppCompatController.getTransparentPolicy());
            });
        }
    }
}
