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
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_4_3;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_DISPLAY_SIZE;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_SPLIT_SCREEN;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.AppCompatConfiguration.DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.util.function.Consumer;

/**
 * Test class for {@link DesktopAppCompatAspectRatioPolicy}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:DesktopAppCompatAspectRatioPolicyTests
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class DesktopAppCompatAspectRatioPolicyTests extends WindowTestsBase {
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private static final float FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO = 1.33f;

    @Test
    public void testHasMinAspectRatioOverride_userAspectRatioEnabled_returnTrue() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.setGetUserMinAspectRatioOverrideValue(3 / 2f);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);
            });

            robot.checkHasMinAspectRatioOverride(/* expected */ true);
        });
    }

    @Test
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO})
    public void testHasMinAspectRatioOverride_overrideDisabled_returnsFalse() {
        runTestScenario((robot)-> {
            robot.activity().createActivityWithComponent();

            robot.checkHasMinAspectRatioOverride(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO})
    public void testHasMinAspectRatioOverride_overrideEnabled_propertyFalse_returnsFalse() {
        runTestScenario((robot)-> {
            robot.activity().createActivityWithComponent();
            robot.prop().disable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);

            robot.checkHasMinAspectRatioOverride(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO})
    public void testHasMinAspectRatioOverride_overrideDisabled_propertyTrue_returnsFalse() {
        runTestScenario((robot)-> {
            robot.activity().createActivityWithComponent();
            robot.prop().enable(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE);

            robot.checkHasMinAspectRatioOverride(/* expected */ false);
        });
    }

    @Test
    @DisableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO})
    public void testHasMinAspectRatioOverride_overrideEnabled_nonPortraitActivity_returnsFalse() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_UNSPECIFIED);
            });

            robot.checkHasMinAspectRatioOverride(/* expected */ false);
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testHasMinAspectRatioOverride_splitScreenAspectRatioOverride_returnTrue() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });

            robot.checkHasMinAspectRatioOverride(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testHasMinAspectRatioOverride_largeMinAspectRatioOverride_returnTrue() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });

            robot.checkHasMinAspectRatioOverride(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testHasMinAspectRatioOverride_mediumMinAspectRatioOverride_returnTrue() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });

            robot.checkHasMinAspectRatioOverride(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL})
    public void testHasMinAspectRatioOverride_smallMinAspectRatioOverride_returnTrue() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });

            robot.checkHasMinAspectRatioOverride(/* expected */ true);
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_TO_ALIGN_WITH_SPLIT_SCREEN})
    public void testCalculateAspectRatio_splitScreenAspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ false);
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioSplitScreenAspectRatio();
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_LARGE})
    public void testCalculateAspectRatio_largeMinAspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ false);
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioLargeAspectRatioOverride();
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_MEDIUM})
    public void testCalculateAspectRatio_mediumMinAspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ false);
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioMediumAspectRatioOverride();
        });
    }

    @Test
    @EnableCompatChanges({ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO,
            ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_SMALL})
    public void testCalculateAspectRatio_smallMinAspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ false);
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioSmallAspectRatioOverride();
        });
    }

    @Test
    public void testCalculateAspectRatio_defaultMultiWindowLetterboxAspectRatio() {
        runTestScenario((robot)-> {
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ false);
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
                a.setTaskDisplayAreaWindowingMode(WINDOWING_MODE_FREEFORM);
            });

            robot.checkCalculateAspectRatioDefaultLetterboxAspectRatioForMultiWindow();
        });
    }

    @Test
    public void testCalculateAspectRatio_displayAspectRatioEnabledForFixedOrientationLetterbox() {
        runTestScenario((robot)-> {
            robot.conf().enableDisplayAspectRatioEnabledForFixedOrientationLetterbox(true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.configureTopActivity(/* minAspect */ 0, /* maxAspect */ 0,
                        SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable */ false);
            });

            robot.checkCalculateAspectRatioDisplayAreaAspectRatio();
        });
    }

    @Test
    public void testCalculateAspectRatio_defaultMinAspectRatio_fixedOrientationAspectRatio() {
        runTestScenario((robot)-> {
            robot.applyOnConf((c) -> {
                c.enableDisplayAspectRatioEnabledForFixedOrientationLetterbox(false);
                c.setFixedOrientationLetterboxAspectRatio(FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO);
            });
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.configureTopActivity(/* minAspect */ 0, /* maxAspect */ 0,
                        SCREEN_ORIENTATION_PORTRAIT, /* isUnresizable */ false);
            });

            robot.checkCalculateAspectRatioDefaultMinFixedOrientationAspectRatio();
        });
    }

    @Test
    public void testCalculateAspectRatio_splitScreenForUnresizeableEnabled() {
        runTestScenario((robot) -> {
            robot.conf().enableSplitScreenAspectRatioForUnresizableApps(/* isEnabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.configureUnresizableTopActivity(SCREEN_ORIENTATION_PORTRAIT);
            });

            robot.checkCalculateAspectRatioSplitScreenAspectRatio();
        });
    }

    @Test
    public void testCalculateAspectRatio_user3By2AspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.setGetUserMinAspectRatioOverrideValue(3 / 2f);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_3_2);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioUser3By2AspectRatiOverride();
        });
    }

    @Test
    public void testCalculateAspectRatio_user4By3AspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.setGetUserMinAspectRatioOverrideValue(4 / 3f);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_4_3);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioUser4By3AspectRatiOverride();
        });
    }

    @Test
    public void testCalculateAspectRatio_user16By9AspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.setGetUserMinAspectRatioOverrideValue(16 / 9f);
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_16_9);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioUser16By9AspectRatioOverride();
        });
    }

    @Test
    public void testCalculateAspectRatio_userSplitScreenAspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.setGetUserMinAspectRatioOverrideValue(robot.getSplitScreenAspectRatio());
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_SPLIT_SCREEN);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioSplitScreenAspectRatio();
        });
    }

    @Test
    public void testCalculateAspectRatio_userDisplayAreaAspectRatioOverride() {
        runTestScenario((robot)-> {
            robot.conf().enableUserAppAspectRatioSettings(/* enabled */ true);
            robot.applyOnActivity((a) -> {
                a.createActivityWithComponent();
                a.setIgnoreOrientationRequest(/* enabled */ true);
                a.setGetUserMinAspectRatioOverrideValue(robot.getDisplayAreaAspectRatio());
                a.setGetUserMinAspectRatioOverrideCode(USER_MIN_ASPECT_RATIO_DISPLAY_SIZE);
            });
            robot.setDesiredAspectRatio(1f);

            robot.checkCalculateAspectRatioDisplayAreaAspectRatio();
        });
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    void runTestScenario(@NonNull Consumer<DesktopAppCompatAspectRatioPolicyRobotTest> consumer) {
        final DesktopAppCompatAspectRatioPolicyRobotTest robot =
                new DesktopAppCompatAspectRatioPolicyRobotTest(mWm, mAtm, mSupervisor);
        consumer.accept(robot);
    }

    private static class DesktopAppCompatAspectRatioPolicyRobotTest extends AppCompatRobotBase {
        DesktopAppCompatAspectRatioPolicyRobotTest(@NonNull WindowManagerService wm,
                @NonNull ActivityTaskManagerService atm,
                @NonNull ActivityTaskSupervisor supervisor) {
            super(wm, atm, supervisor);
        }

        @Override
        void onPostActivityCreation(@NonNull ActivityRecord activity) {
            super.onPostActivityCreation(activity);
            spyOn(activity.mAppCompatController.getAppCompatAspectRatioOverrides());
            spyOn(activity.mAppCompatController.getDesktopAppCompatAspectRatioPolicy());
        }

        void setDesiredAspectRatio(float aspectRatio) {
            doReturn(aspectRatio).when(getDesktopAppCompatAspectRatioPolicy())
                    .getDesiredAspectRatio(any());
        }

        DesktopAppCompatAspectRatioPolicy getDesktopAppCompatAspectRatioPolicy() {
            return getTopActivity().mAppCompatController.getDesktopAppCompatAspectRatioPolicy();
        }

        float calculateAspectRatio() {
            return getDesktopAppCompatAspectRatioPolicy().calculateAspectRatio(
                    getTopActivity().getTask());
        }

        ActivityRecord getTopActivity() {
            return this.activity().top();
        }

        float getSplitScreenAspectRatio() {
            return  getTopActivity().mAppCompatController.getAppCompatAspectRatioOverrides()
                    .getSplitScreenAspectRatio();
        }

        float getDisplayAreaAspectRatio() {
            final Rect appBounds = getTopActivity().getDisplayArea().getWindowConfiguration()
                    .getAppBounds();
            return AppCompatUtils.computeAspectRatio(appBounds);
        }

        void checkHasMinAspectRatioOverride(boolean expected) {
            assertEquals(expected, this.activity().top().mAppCompatController
                    .getDesktopAppCompatAspectRatioPolicy().hasMinAspectRatioOverride(
                            this.activity().top().getTask()));
        }

        void checkCalculateAspectRatioSplitScreenAspectRatio() {
            assertEquals(getSplitScreenAspectRatio(), calculateAspectRatio(), FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioLargeAspectRatioOverride() {
            assertEquals(OVERRIDE_MIN_ASPECT_RATIO_LARGE_VALUE, calculateAspectRatio(),
                    FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioMediumAspectRatioOverride() {
            assertEquals(OVERRIDE_MIN_ASPECT_RATIO_MEDIUM_VALUE, calculateAspectRatio(),
                    FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioSmallAspectRatioOverride() {
            assertEquals(OVERRIDE_MIN_ASPECT_RATIO_SMALL_VALUE, calculateAspectRatio(),
                    FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioDefaultLetterboxAspectRatioForMultiWindow() {
            assertEquals(DEFAULT_LETTERBOX_ASPECT_RATIO_FOR_MULTI_WINDOW, calculateAspectRatio(),
                    FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioDisplayAreaAspectRatio() {
            assertEquals(getDisplayAreaAspectRatio(), calculateAspectRatio(), FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioDefaultMinFixedOrientationAspectRatio() {
            assertEquals(FIXED_ORIENTATION_LETTERBOX_ASPECT_RATIO, calculateAspectRatio(),
                    FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioUser3By2AspectRatiOverride() {
            assertEquals(3 / 2f, calculateAspectRatio(), FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioUser4By3AspectRatiOverride() {
            assertEquals(4 / 3f, calculateAspectRatio(), FLOAT_TOLLERANCE);
        }

        void checkCalculateAspectRatioUser16By9AspectRatioOverride() {
            assertEquals(16 / 9f, calculateAspectRatio(), FLOAT_TOLLERANCE);
        }
    }
}
