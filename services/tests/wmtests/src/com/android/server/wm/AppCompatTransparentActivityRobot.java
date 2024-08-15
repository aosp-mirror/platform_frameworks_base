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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Assert;

import java.util.function.Consumer;

/**
 * Robot implementation for {@link ActivityRecord} when dealing with transparent activities.
 */
class AppCompatTransparentActivityRobot {

    @Nullable
    private WindowConfiguration mTopActivityWindowConfiguration;

    @NonNull
    private final AppCompatActivityRobot mActivityRobot;

    AppCompatTransparentActivityRobot(@NonNull AppCompatActivityRobot activityRobot) {
        mActivityRobot = activityRobot;
    }

    @NonNull
    AppCompatActivityRobot activity() {
        return mActivityRobot;
    }

    @NonNull
    void applyOnActivity(@NonNull Consumer<AppCompatActivityRobot> consumer) {
        consumer.accept(mActivityRobot);
    }

    void setDisplayContentBounds(int left, int top, int right, int bottom) {
        mActivityRobot.displayContent().setBounds(left, top, right, bottom);
    }

    void launchTransparentActivity() {
        mActivityRobot.launchActivity(/*minAspectRatio */ -1, /* maxAspectRatio */ -1,
                SCREEN_ORIENTATION_PORTRAIT, /* transparent */ true,
                /* withComponent */ false, /* addToTask */ false);
    }

    void launchTransparentActivityInTask() {
        mActivityRobot.launchActivity(/*minAspectRatio */ -1,
                /* maxAspectRatio */ -1, SCREEN_ORIENTATION_PORTRAIT, /* transparent */ true,
                /* withComponent */ false, /* addToTask */true);
    }

    void launchOpaqueActivityInTask() {
        mActivityRobot.launchActivity(/*minAspectRatio */ -1,
                /* maxAspectRatio */ -1, SCREEN_ORIENTATION_PORTRAIT, /* transparent */ false,
                /* withComponent */ false, /* addToTask */true);
    }

    void forceChangeInTopActivityConfiguration() {
        activity().applyToTopActivity((topActivity) -> {
            final Configuration requestedConfig =
                    topActivity.getRequestedOverrideConfiguration();
            mTopActivityWindowConfiguration = requestedConfig.windowConfiguration;
            mTopActivityWindowConfiguration.setActivityType(ACTIVITY_TYPE_STANDARD);
            mTopActivityWindowConfiguration.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
            mTopActivityWindowConfiguration.setAlwaysOnTop(true);
            topActivity.onRequestedOverrideConfigurationChanged(requestedConfig);
        });
    }

    void checkTopActivityConfigurationConfiguration() {
        activity().applyToTopActivity((topActivity) -> {
            // The original override of WindowConfiguration should keep.
            assertEquals(ACTIVITY_TYPE_STANDARD, topActivity.getActivityType());
            assertEquals(WINDOWING_MODE_MULTI_WINDOW,
                    mTopActivityWindowConfiguration.getWindowingMode());
            assertTrue(mTopActivityWindowConfiguration.isAlwaysOnTop());
            // Unless display is going to be rotated, it should always inherit from parent.
            assertEquals(ROTATION_UNDEFINED,
                    mTopActivityWindowConfiguration.getDisplayRotation());
        });
    }

    void checkTopActivityTransparentPolicyStateIsRunning(boolean running) {
        assertEquals(running,
                activity().top().mAppCompatController.getTransparentPolicy().isRunning());
    }

    void checkTopActivityTransparentPolicyStartInvoked() {
        activity().applyToTopActivity((topActivity) -> {
            verify(topActivity.mAppCompatController.getTransparentPolicy()).start();
        });
    }

    void checkTopActivityTransparentPolicyStartNotInvoked() {
        verify(activity().top().mAppCompatController.getTransparentPolicy(), never()).start();
    }

    void checkTopActivityTransparentPolicyStopInvoked() {
        verify(activity().top().mAppCompatController.getTransparentPolicy()).stop();
    }

    void checkTopActivityTransparentPolicyStopNotInvoked() {
        verify(activity().top().mAppCompatController.getTransparentPolicy(), never()).stop();
    }

    void checkTopActivityHasInheritedBoundsFrom(int fromTop) {
        final ActivityRecord topActivity = activity().top();
        final ActivityRecord otherActivity = activity().getFromTop(/* fromTop */ fromTop);
        final Rect opaqueBounds = otherActivity.getConfiguration().windowConfiguration
                .getBounds();
        final Rect translucentRequestedBounds = topActivity.getRequestedOverrideBounds();
        Assert.assertEquals(opaqueBounds, translucentRequestedBounds);
    }
}
