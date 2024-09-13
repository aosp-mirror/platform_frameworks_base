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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.WindowConfiguration.WindowingMode;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager.UserMinAspectRatio;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.Surface;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.server.wm.utils.TestComponentStack;

import org.junit.Assert;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Robot implementation for {@link ActivityRecord}.
 */
class AppCompatActivityRobot {

    private static final int DEFAULT_DISPLAY_WIDTH = 1000;
    private static final int DEFAULT_DISPLAY_HEIGHT = 2000;

    private static final float DELTA_ASPECT_RATIO_TOLERANCE = 0.0001f;
    private static final float COMPAT_SCALE_TOLERANCE = 0.0001f;

    private static final String TEST_COMPONENT_NAME = AppCompatActivityRobot.class.getName();

    @NonNull
    private final ActivityTaskManagerService mAtm;
    @NonNull
    private final ActivityTaskSupervisor mSupervisor;
    @NonNull
    private final TestComponentStack<ActivityRecord> mActivityStack;
    @NonNull
    private final TestComponentStack<Task> mTaskStack;

    private final int mDisplayWidth;
    private final int mDisplayHeight;
    private DisplayContent mDisplayContent;

    @Nullable
    private Consumer<ActivityRecord> mOnPostActivityCreation;

    @Nullable
    private Consumer<DisplayContent> mOnPostDisplayContentCreation;

    AppCompatActivityRobot(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm, @NonNull ActivityTaskSupervisor supervisor,
            int displayWidth, int displayHeight,
            @Nullable Consumer<ActivityRecord> onPostActivityCreation,
            @Nullable Consumer<DisplayContent> onPostDisplayContentCreation) {
        mAtm = atm;
        mSupervisor = supervisor;
        mDisplayWidth = displayWidth;
        mDisplayHeight = displayHeight;
        mActivityStack = new TestComponentStack<>();
        mTaskStack = new TestComponentStack<>();
        mOnPostActivityCreation = onPostActivityCreation;
        mOnPostDisplayContentCreation = onPostDisplayContentCreation;
        createNewDisplay();
    }

    AppCompatActivityRobot(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm, @NonNull ActivityTaskSupervisor supervisor,
            int displayWidth, int displayHeight) {
        this(wm, atm, supervisor, displayWidth, displayHeight, /* onPostActivityCreation */ null,
                /* onPostDisplayContentCreation */ null);
    }

    AppCompatActivityRobot(@NonNull WindowManagerService wm,
            @NonNull ActivityTaskManagerService atm, @NonNull ActivityTaskSupervisor supervisor) {
        this(wm, atm, supervisor, DEFAULT_DISPLAY_WIDTH, DEFAULT_DISPLAY_HEIGHT);
    }

    void createActivityWithComponent() {
        createActivityWithComponentInNewTask(/* inNewTask */ mTaskStack.isEmpty(),
                /* inNewDisplay */ false);
    }

    void createActivityWithComponentWithoutTask() {
        createActivityWithComponentInNewTask(/* inNewTask */ false, /* inNewDisplay */ false);
    }

    void createActivityWithComponentInNewTask() {
        createActivityWithComponentInNewTask(/* inNewTask */ true, /* inNewDisplay */ false);
    }

    void createActivityWithComponentInNewTaskAndDisplay() {
        createActivityWithComponentInNewTask(/* inNewTask */ true, /* inNewDisplay */ true);
    }

    void configureTopActivity(float minAspect, float maxAspect, int screenOrientation,
            boolean isUnresizable) {
        prepareLimitedBounds(mActivityStack.top(), minAspect, maxAspect, screenOrientation,
                isUnresizable);
    }

    void configureUnresizableTopActivity(@ActivityInfo.ScreenOrientation int screenOrientation) {
        configureTopActivity(/* minAspect */ -1, /* maxAspect */ -1, screenOrientation,
                /* isUnresizable */ true);
    }

    void setDisplayNaturalOrientation(@Configuration.Orientation int naturalOrientation) {
        doReturn(naturalOrientation).when(mDisplayContent).getNaturalOrientation();
    }

    void configureTaskBounds(@NonNull Rect taskBounds) {
        doReturn(taskBounds).when(mTaskStack.top()).getBounds();
    }

    void configureTopActivityBounds(@NonNull Rect activityBounds) {
        doReturn(activityBounds).when(mActivityStack.top()).getBounds();
    }

    @NonNull
    ActivityRecord top() {
        return mActivityStack.top();
    }

    @NonNull
    DisplayContent displayContent() {
        return mDisplayContent;
    }

    @NonNull
    ActivityRecord getFromTop(int fromTop) {
        return mActivityStack.getFromTop(fromTop);
    }

    void setTaskWindowingMode(@WindowingMode int windowingMode) {
        mTaskStack.top().setWindowingMode(windowingMode);
    }

    void setTaskDisplayAreaWindowingMode(@WindowingMode int windowingMode) {
        mTaskStack.top().getDisplayArea().setWindowingMode(windowingMode);
    }

    void setLetterboxedForFixedOrientationAndAspectRatio(boolean enabled) {
        doReturn(enabled).when(mActivityStack.top().mAppCompatController
                .getAppCompatAspectRatioPolicy()).isLetterboxedForFixedOrientationAndAspectRatio();
    }

    void enableTreatmentForTopActivity(boolean enabled) {
        doReturn(enabled).when(mDisplayContent.mAppCompatCameraPolicy)
                .isTreatmentEnabledForActivity(eq(mActivityStack.top()));
    }

    void setTopActivityCameraActive(boolean enabled) {
        doReturn(enabled).when(getTopDisplayRotationCompatPolicy())
                .isCameraActive(eq(mActivityStack.top()), /* mustBeFullscreen= */ eq(true));
    }

    void setTopActivityEligibleForOrientationOverride(boolean enabled) {
        doReturn(enabled).when(getTopDisplayRotationCompatPolicy())
                .isActivityEligibleForOrientationOverride(eq(mActivityStack.top()));
    }

    void setTopActivityInTransition(boolean inTransition) {
        doReturn(inTransition).when(mActivityStack.top()).isInTransition();
    }

    void setShouldApplyUserMinAspectRatioOverride(boolean enabled) {
        doReturn(enabled).when(mActivityStack.top().mAppCompatController
                .getAppCompatAspectRatioOverrides()).shouldApplyUserMinAspectRatioOverride();
    }

    void setShouldCreateCompatDisplayInsets(boolean enabled) {
        doReturn(enabled).when(mActivityStack.top()).shouldCreateAppCompatDisplayInsets();
    }

    void setTopActivityInSizeCompatMode(boolean inScm) {
        doReturn(inScm).when(mActivityStack.top()).inSizeCompatMode();
    }

    void setShouldApplyUserFullscreenOverride(boolean enabled) {
        doReturn(enabled).when(mActivityStack.top().mAppCompatController
                .getAppCompatAspectRatioOverrides()).shouldApplyUserFullscreenOverride();
    }

    void setGetUserMinAspectRatioOverrideCode(@UserMinAspectRatio int overrideCode) {
        doReturn(overrideCode).when(mActivityStack.top().mAppCompatController
                .getAppCompatAspectRatioOverrides()).getUserMinAspectRatioOverrideCode();
    }

    void setGetUserMinAspectRatioOverrideValue(float overrideValue) {
        doReturn(overrideValue).when(mActivityStack.top().mAppCompatController
                .getAppCompatAspectRatioOverrides()).getUserMinAspectRatio();
    }

    void setIgnoreOrientationRequest(boolean enabled) {
        mDisplayContent.setIgnoreOrientationRequest(enabled);
    }

    void setTopTaskInMultiWindowMode(boolean inMultiWindowMode) {
        doReturn(inMultiWindowMode).when(mTaskStack.top()).inMultiWindowMode();
    }

    void setTopActivityAsEmbedded(boolean embedded) {
        doReturn(embedded).when(mActivityStack.top()).isEmbedded();
    }

    void setTopActivityVisible(boolean isVisible) {
        doReturn(isVisible).when(mActivityStack.top()).isVisible();
    }

    void setTopActivityVisibleRequested(boolean isVisibleRequested) {
        doReturn(isVisibleRequested).when(mActivityStack.top()).isVisibleRequested();
    }

    void setTopActivityFillsParent(boolean fillsParent) {
        doReturn(fillsParent).when(mActivityStack.top()).fillsParent();
    }

    void setTopActivityInMultiWindowMode(boolean multiWindowMode) {
        doReturn(multiWindowMode).when(mActivityStack.top()).inMultiWindowMode();
        if (multiWindowMode) {
            doReturn(WINDOWING_MODE_MULTI_WINDOW).when(mActivityStack.top()).getWindowingMode();
        }
    }

    void setTopActivityInPinnedWindowingMode(boolean pinnedWindowingMode) {
        doReturn(pinnedWindowingMode).when(mActivityStack.top()).inPinnedWindowingMode();
        if (pinnedWindowingMode) {
            doReturn(WINDOWING_MODE_PINNED).when(mActivityStack.top()).getWindowingMode();
        }
    }

    void setTopActivityInFreeformWindowingMode(boolean freeformWindowingMode) {
        doReturn(freeformWindowingMode).when(mActivityStack.top()).inFreeformWindowingMode();
        if (freeformWindowingMode) {
            doReturn(WINDOWING_MODE_FREEFORM).when(mActivityStack.top()).getWindowingMode();
        }
    }

    void destroyTopActivity() {
        mActivityStack.top().removeImmediately();
    }

    void destroyActivity(int fromTop) {
        mActivityStack.applyTo(/* fromTop */ fromTop, ActivityRecord::removeImmediately);
    }

    void createNewDisplay() {
        mDisplayContent = new TestDisplayContent.Builder(mAtm, mDisplayWidth, mDisplayHeight)
                .build();
        onPostDisplayContentCreation(mDisplayContent);
    }

    void createNewTask() {
        final Task newTask = new WindowTestsBase.TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).build();
        mTaskStack.push(newTask);
    }

    void createNewTaskWithBaseActivity() {
        final Task newTask = new WindowTestsBase.TaskBuilder(mSupervisor)
                .setCreateActivity(true)
                .setDisplay(mDisplayContent).build();
        mTaskStack.push(newTask);
        pushActivity(newTask.getTopNonFinishingActivity());
    }

    void attachTopActivityToTask() {
        mTaskStack.top().addChild(mActivityStack.top());
    }

    void applyToTopActivity(Consumer<ActivityRecord> consumer) {
        consumer.accept(mActivityStack.top());
    }

    void applyToActivity(int fromTop, @NonNull Consumer<ActivityRecord> consumer) {
        mActivityStack.applyTo(fromTop, consumer);
    }

    void applyToAllActivities(@NonNull Consumer<ActivityRecord> consumer) {
        mActivityStack.applyToAll(consumer);
    }

    void rotateDisplayForTopActivity(@Surface.Rotation int rotation) {
        rotateDisplay(mActivityStack.top().mDisplayContent, rotation);
    }

    void configureTopActivityFoldablePosture(boolean isHalfFolded, boolean isTabletop) {
        mActivityStack.applyToTop((topActivity) -> {
            final DisplayRotation r = topActivity.mDisplayContent.getDisplayRotation();
            doReturn(isHalfFolded).when(r).isDisplaySeparatingHinge();
            doReturn(false).when(r)
                    .isDeviceInPosture(any(DeviceStateController.DeviceState.class),
                            anyBoolean());
            if (isHalfFolded) {
                doReturn(true).when(r)
                        .isDeviceInPosture(DeviceStateController.DeviceState.HALF_FOLDED,
                                isTabletop);
            }
            topActivity.recomputeConfiguration();
        });
    }

    private static void rotateDisplay(@Surface.Rotation DisplayContent display, int rotation) {
        final Configuration c = new Configuration();
        display.getDisplayRotation().setRotation(rotation);
        display.computeScreenConfiguration(c);
        display.onRequestedOverrideConfigurationChanged(c);
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

    void checkTopActivityRecomputedConfiguration() {
        verify(mActivityStack.top()).recomputeConfiguration();
    }

    void checkTopActivityConfigOrientation(@Configuration.Orientation int orientation) {
        Assert.assertEquals(orientation, mActivityStack.top()
                .getRequestedConfigurationOrientation());
    }

    void checkTopActivityAspectRatios(float minAspectRatio, float maxAspectRatio) {
        final ActivityRecord topActivity = mActivityStack.top();
        Assert.assertEquals(minAspectRatio, topActivity.getMinAspectRatio(),
                DELTA_ASPECT_RATIO_TOLERANCE);
        Assert.assertEquals(maxAspectRatio, topActivity.getMaxAspectRatio(),
                DELTA_ASPECT_RATIO_TOLERANCE);
    }

    void checkTopActivityInSizeCompatMode(boolean inScm) {
        final ActivityRecord topActivity = mActivityStack.top();
        Assert.assertEquals(inScm, topActivity.inSizeCompatMode());
        Assert.assertNotEquals(1f, topActivity.getCompatScale(), COMPAT_SCALE_TOLERANCE);
    }

    void launchActivity(float minAspectRatio, float maxAspectRatio,
            @ActivityInfo.ScreenOrientation int orientation, boolean transparent,
            boolean withComponent, boolean addToTask) {
        final WindowTestsBase.ActivityBuilder
                activityBuilder = new WindowTestsBase.ActivityBuilder(mAtm)
                .setScreenOrientation(orientation)
                .setLaunchedFromUid(0);
        if (transparent) {
            activityBuilder.setActivityTheme(android.R.style.Theme_Translucent);
        }
        if (withComponent) {
            // Set the component to be that of the test class in order
            // to enable compat changes
            activityBuilder.setComponent(ComponentName.createRelative(mAtm.mContext,
                    TEST_COMPONENT_NAME));
        }
        if (minAspectRatio >= 0) {
            activityBuilder.setMinAspectRatio(minAspectRatio);
        }
        if (maxAspectRatio >= 0) {
            activityBuilder.setMaxAspectRatio(maxAspectRatio);
        }
        final ActivityRecord newActivity = activityBuilder.build();
        if (addToTask) {
            if (mTaskStack.isEmpty()) {
                createNewTask();
            }
            mTaskStack.top().addChild(newActivity);
        }
        pushActivity(newActivity);
    }

    /**
     * Specific Robots can override this method to add operation to run on a newly created
     * {@link ActivityRecord}. Common case is to invoke spyOn().
     *
     * @param activity The newly created {@link ActivityRecord}.
     */
    @CallSuper
    void onPostActivityCreation(@NonNull ActivityRecord activity) {
        if (mOnPostActivityCreation != null) {
            mOnPostActivityCreation.accept(activity);
        }
    }

    /**
     * Specific Robots can override this method to add operation to run on a newly created
     * {@link DisplayContent}. Common case is to invoke spyOn().
     *
     * @param displayContent The newly created {@link DisplayContent}.
     */
    @CallSuper
    void onPostDisplayContentCreation(@NonNull DisplayContent displayContent) {
        spyOn(mDisplayContent);
        if (mOnPostDisplayContentCreation != null) {
            mOnPostDisplayContentCreation.accept(mDisplayContent);
        }
    }

    private void createActivityWithComponentInNewTask(boolean inNewTask, boolean inNewDisplay) {
        if (inNewDisplay) {
            createNewDisplay();
        }
        if (inNewTask) {
            createNewTask();
        }
        final WindowTestsBase.ActivityBuilder activityBuilder =
                new WindowTestsBase.ActivityBuilder(mAtm).setOnTop(true)
                // Set the component to be that of the test class in order
                // to enable compat changes
                .setComponent(ComponentName.createRelative(mAtm.mContext, TEST_COMPONENT_NAME));
        if (!mTaskStack.isEmpty()) {
            // We put the Activity in the current task if any.
            activityBuilder.setTask(mTaskStack.top());
        }
        pushActivity(activityBuilder.build());
    }

    /**
     * Setups activity with restriction on its bounds, such as maxAspect, minAspect,
     * fixed orientation, and/or whether it is resizable.
     */
    private void prepareLimitedBounds(ActivityRecord activity, float minAspect, float maxAspect,
            @ActivityInfo.ScreenOrientation int screenOrientation, boolean isUnresizable) {
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
        activity.mAppCompatController.getAppCompatSizeCompatModePolicy().clearSizeCompatMode();
        activity.ensureActivityConfiguration();
        // Make sure the display configuration reflects the change of activity.
        if (activity.mDisplayContent.updateOrientation()) {
            activity.mDisplayContent.sendNewConfiguration();
        }
    }

    private DisplayRotationCompatPolicy getTopDisplayRotationCompatPolicy() {
        return mActivityStack.top().mDisplayContent
                .mAppCompatCameraPolicy.mDisplayRotationCompatPolicy;
    }

    // We add the activity to the stack and spyOn() on its properties.
    private void pushActivity(@NonNull ActivityRecord activity) {
        mActivityStack.push(activity);
        onPostActivityCreation(activity);
    }
}
