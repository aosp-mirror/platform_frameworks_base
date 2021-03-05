/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.window.DisplayAreaOrganizer.FEATURE_RUNTIME_TASK_CONTAINER_FIRST;

import static com.android.server.wm.ActivityStarter.Request;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_BOUNDS;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.platform.test.annotations.Presubmit;
import android.view.Gravity;
import android.view.InsetsState;

import androidx.test.filters.SmallTest;

import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier.Result;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tests for default task bounds.
 *
 * Build/Install/Run:
 *  atest WmTests:TaskLaunchParamsModifierTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TaskLaunchParamsModifierTests extends WindowTestsBase {
    private static final Rect DISPLAY_BOUNDS = new Rect(/* left */ 0, /* top */ 0,
            /* right */ 1920, /* bottom */ 1080);
    private static final Rect DISPLAY_STABLE_BOUNDS = new Rect(/* left */ 100,
            /* top */ 200, /* right */ 1620, /* bottom */ 680);

    private ActivityRecord mActivity;

    private TaskLaunchParamsModifier mTarget;

    private LaunchParams mCurrent;
    private LaunchParams mResult;

    @Before
    public void setUp() throws Exception {
        mActivity = new ActivityBuilder(mAtm).build();
        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.N_MR1;
        mActivity.info.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;

        mTarget = new TaskLaunchParamsModifier(mSupervisor);

        mCurrent = new LaunchParams();
        mCurrent.reset();
        mResult = new LaunchParams();
        mResult.reset();
    }

    @Test
    public void testReturnsSkipWithEmptyActivity() {
        final Task task = new TaskBuilder(mSupervisor).build();
        assertEquals(RESULT_SKIP, new CalculateRequestBuilder().setActivity(null).calculate());
    }

    // =======================
    // Display Related Tests
    // =======================
    @Test
    public void testDefaultToPrimaryDisplayArea() {
        createNewDisplayContent(WINDOWING_MODE_FREEFORM);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(mRootWindowContainer.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesPreviousDisplayAreaIfSet() {
        createNewDisplayContent(WINDOWING_MODE_FREEFORM);
        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = display.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(display.getDefaultTaskDisplayArea(), mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesSourcesDisplayAreaIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        ActivityRecord source = createSourceActivity(fullscreenDisplay);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().setSource(source).calculate());

        assertEquals(fullscreenDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesOptionsDisplayIdIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        ActivityRecord source = createSourceActivity(freeformDisplay);

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(fullscreenDisplay.mDisplayId);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).setOptions(options).calculate());

        assertEquals(fullscreenDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesOptionsDisplayAreaTokenIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        ActivityRecord source = createSourceActivity(freeformDisplay);

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskDisplayArea(fullscreenDisplay.getDefaultTaskDisplayArea()
                .mRemoteToken.toWindowContainerToken());

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).setOptions(options).calculate());

        assertEquals(fullscreenDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesTasksDisplayAreaIdPriorToSourceIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        ActivityRecord reusableActivity = createSourceActivity(fullscreenDisplay);
        ActivityRecord source = createSourceActivity(freeformDisplay);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder()
                        .setTask(reusableActivity.getTask())
                        .setSource(source)
                        .calculate());

        assertEquals(fullscreenDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesTaskDisplayAreaIdIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        ActivityRecord source = createSourceActivity(freeformDisplay);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder()
                        .setTask(source.getTask())
                        .calculate());

        assertEquals(freeformDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesNoDisplaySourceHandoverDisplayIdIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = fullscreenDisplay.getDefaultTaskDisplayArea();
        ActivityRecord reusableActivity = createSourceActivity(fullscreenDisplay);
        ActivityRecord source = createSourceActivity(freeformDisplay);
        source.mHandoverLaunchDisplayId = freeformDisplay.mDisplayId;
        source.noDisplay = true;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder()
                        .setTask(reusableActivity.getTask())
                        .setSource(source)
                        .calculate());

        assertEquals(freeformDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesNoDisplaySourceHandoverDisplayAreaIdIfSet() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = fullscreenDisplay.getDefaultTaskDisplayArea();
        ActivityRecord reusableActivity = createSourceActivity(fullscreenDisplay);
        ActivityRecord source = createSourceActivity(freeformDisplay);
        source.mHandoverTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        source.noDisplay = true;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder()
                        .setTask(reusableActivity.getTask())
                        .setSource(source)
                        .calculate());

        assertEquals(freeformDisplay.getDefaultTaskDisplayArea(),
                mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesDisplayAreaFromTopMostActivityInApplicationIfAvailable() {
        final String processName = "processName";
        final int uid = 124214;
        final TestDisplayContent secondScreen = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final TaskDisplayArea expectedDisplayArea = secondScreen.getDefaultTaskDisplayArea();
        final WindowProcessController controller = mock(WindowProcessController.class);

        when(controller.getTopActivityDisplayArea()).thenReturn(expectedDisplayArea);

        when(mActivity.getProcessName()).thenReturn(processName);
        when(mActivity.getUid()).thenReturn(uid);
        doReturn(controller)
                .when(mAtm)
                .getProcessController(processName, uid);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(expectedDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesDisplayAreaFromLaunchingActivityIfApplicationLaunching() {
        final String processName = "processName";
        final int uid = 124214;
        final TestDisplayContent secondScreen = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final TaskDisplayArea expectedTaskDisplayArea = secondScreen.getDefaultTaskDisplayArea();
        final WindowProcessController controller = mock(WindowProcessController.class);

        when(controller.getTopActivityDisplayArea()).thenReturn(expectedTaskDisplayArea);

        when(mActivity.getProcessName()).thenReturn(processName);
        when(mActivity.getUid()).thenReturn(uid);
        doReturn(null)
                .when(mAtm)
                .getProcessController(processName, uid);

        doReturn(controller)
                .when(mAtm)
                .getProcessController(mActivity.launchedFromPid, mActivity.launchedFromUid);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(expectedTaskDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testDisplayAreaFromLaunchingActivityTakesPrecedence() {
        final String processName = "processName";
        final int uid = 124214;
        final TestDisplayContent firstScreen = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final TestDisplayContent secondScreen = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final TaskDisplayArea firstTaskDisplayArea = firstScreen.getDefaultTaskDisplayArea();
        final TaskDisplayArea expectedTaskDisplayArea = secondScreen.getDefaultTaskDisplayArea();
        final WindowProcessController controllerForLaunching = mock(WindowProcessController.class);
        final WindowProcessController controllerForApplication =
                mock(WindowProcessController.class);

        when(mActivity.getProcessName()).thenReturn(processName);
        when(mActivity.getUid()).thenReturn(uid);

        when(controllerForApplication.getTopActivityDisplayArea()).thenReturn(firstTaskDisplayArea);
        when(controllerForLaunching.getTopActivityDisplayArea())
                .thenReturn(expectedTaskDisplayArea);

        doReturn(controllerForApplication)
                .when(mAtm)
                .getProcessController(processName, uid);
        doReturn(controllerForLaunching)
                .when(mAtm)
                .getProcessController(mActivity.launchedFromPid, mActivity.launchedFromUid);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(expectedTaskDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesDisplayAreaOriginalProcessAsLastResort() {
        final TestDisplayContent firstScreen = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final TestDisplayContent secondScreen = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        final TaskDisplayArea expectedTaskDisplayArea = secondScreen.getDefaultTaskDisplayArea();
        final Request request = new Request();
        request.realCallingPid = 12412413;
        request.realCallingUid = 235424;

        final WindowProcessController controller = mock(WindowProcessController.class);

        when(controller.getTopActivityDisplayArea()).thenReturn(expectedTaskDisplayArea);

        doReturn(null)
                .when(mAtm)
                .getProcessController(mActivity.processName, mActivity.info.applicationInfo.uid);

        doReturn(null)
                .when(mAtm)
                .getProcessController(mActivity.launchedFromPid, mActivity.launchedFromUid);

        doReturn(controller)
                .when(mAtm)
                .getProcessController(request.realCallingPid, request.realCallingUid);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setRequest(request).calculate());

        assertEquals(expectedTaskDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testUsesDefaultDisplayAreaIfWindowProcessControllerIsNotPresent() {
        doReturn(null)
                .when(mAtm)
                .getProcessController(mActivity.processName, mActivity.info.applicationInfo.uid);

        doReturn(null)
                .when(mAtm)
                .getProcessController(mActivity.launchedFromPid, mActivity.launchedFromUid);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(DEFAULT_DISPLAY, mResult.mPreferredTaskDisplayArea.getDisplayId());
    }

    @Test
    public void testOverridesDisplayAreaWithStandardTypeAndFullscreenMode() {
        final TaskDisplayArea secondaryDisplayArea = createTaskDisplayArea(mDefaultDisplay,
                mWm, "SecondaryDisplayArea", FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        final Task launchRoot = createTaskStackOnTaskDisplayArea(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, secondaryDisplayArea);
        launchRoot.mCreatedByOrganizer = true;

        secondaryDisplayArea.setLaunchRootTask(launchRoot, new int[] { WINDOWING_MODE_FULLSCREEN },
                new int[] { ACTIVITY_TYPE_STANDARD });

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(secondaryDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testOverridesDisplayAreaWithHomeTypeAndFullscreenMode() {
        final TaskDisplayArea secondaryDisplayArea = createTaskDisplayArea(mDefaultDisplay,
                mWm, "SecondaryDisplayArea", FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        final Task launchRoot = createTaskStackOnTaskDisplayArea(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, secondaryDisplayArea);
        launchRoot.mCreatedByOrganizer = true;

        mActivity.setActivityType(ACTIVITY_TYPE_HOME);
        secondaryDisplayArea.setLaunchRootTask(launchRoot, new int[] { WINDOWING_MODE_FULLSCREEN },
                new int[] { ACTIVITY_TYPE_HOME });

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(secondaryDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testOverridesDisplayAreaWithStandardTypeAndFreeformMode() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TaskDisplayArea secondaryDisplayArea = createTaskDisplayArea(freeformDisplay,
                mWm, "SecondaryDisplayArea", FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        final Task launchRoot = createTaskStackOnTaskDisplayArea(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, secondaryDisplayArea);
        launchRoot.mCreatedByOrganizer = true;

        secondaryDisplayArea.setLaunchRootTask(launchRoot, new int[] { WINDOWING_MODE_FREEFORM },
                new int[] { ACTIVITY_TYPE_STANDARD });

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(secondaryDisplayArea, mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testNotOverrideDisplayAreaWhenActivityOptionsHasDisplayArea() {
        final TaskDisplayArea secondaryDisplayArea = createTaskDisplayArea(mDefaultDisplay,
                mWm, "SecondaryDisplayArea", FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        final Task launchRoot = createTaskStackOnTaskDisplayArea(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, secondaryDisplayArea);
        launchRoot.mCreatedByOrganizer = true;

        secondaryDisplayArea.setLaunchRootTask(launchRoot, new int[] { WINDOWING_MODE_FULLSCREEN },
                new int[] { ACTIVITY_TYPE_STANDARD });

        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskDisplayArea(
                mDefaultDisplay.getDefaultTaskDisplayArea().mRemoteToken.toWindowContainerToken());

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(
                mDefaultDisplay.getDefaultTaskDisplayArea(), mResult.mPreferredTaskDisplayArea);
    }

    @Test
    public void testRecalculateFreeformInitialBoundsWithOverrideDisplayArea() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TaskDisplayArea secondaryDisplayArea = createTaskDisplayArea(freeformDisplay,
                mWm, "SecondaryDisplayArea", FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        secondaryDisplayArea.setBounds(DISPLAY_BOUNDS.width() / 2, 0,
                        DISPLAY_BOUNDS.width(), DISPLAY_BOUNDS.height());
        final Task launchRoot = createTaskStackOnTaskDisplayArea(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, secondaryDisplayArea);
        launchRoot.mCreatedByOrganizer = true;
        secondaryDisplayArea.setLaunchRootTask(launchRoot, new int[] { WINDOWING_MODE_FREEFORM },
                new int[] { ACTIVITY_TYPE_STANDARD });
        final Rect secondaryDAStableBounds = new Rect();
        secondaryDisplayArea.getStableRect(secondaryDAStableBounds);

        // Specify the display and provide a layout so that it will be set to freeform bounds.
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(freeformDisplay.getDisplayId());
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).setLayout(layout).calculate());

        assertEquals(secondaryDisplayArea, mResult.mPreferredTaskDisplayArea);
        assertTrue(secondaryDAStableBounds.contains(mResult.mBounds));
    }

    @Test
    public void testRecalculateFreeformInitialBoundsWithOverrideDisplayArea_unresizableApp() {
        mAtm.mSupportsNonResizableMultiWindow = true;

        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final TaskDisplayArea secondaryDisplayArea = createTaskDisplayArea(freeformDisplay,
                mWm, "SecondaryDisplayArea", FEATURE_RUNTIME_TASK_CONTAINER_FIRST);
        secondaryDisplayArea.setBounds(DISPLAY_BOUNDS.width() / 2, 0,
                DISPLAY_BOUNDS.width(), DISPLAY_BOUNDS.height());
        final Task launchRoot = createTaskStackOnTaskDisplayArea(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, secondaryDisplayArea);
        launchRoot.mCreatedByOrganizer = true;
        secondaryDisplayArea.setLaunchRootTask(launchRoot, new int[] { WINDOWING_MODE_FREEFORM },
                new int[] { ACTIVITY_TYPE_STANDARD });
        final Rect secondaryDAStableBounds = new Rect();
        secondaryDisplayArea.getStableRect(secondaryDAStableBounds);

        // The bounds will get updated for unresizable with opposite orientation on freeform display
        final Rect displayBounds = new Rect(freeformDisplay.getBounds());
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.screenOrientation = displayBounds.width() > displayBounds.height()
                ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_LANDSCAPE;
        final ActivityOptions options = ActivityOptions.makeBasic()
                .setLaunchDisplayId(freeformDisplay.getDisplayId());

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(secondaryDisplayArea, mResult.mPreferredTaskDisplayArea);
        assertTrue(secondaryDAStableBounds.contains(mResult.mBounds));
    }

    // =====================================
    // Launch Windowing Mode Related Tests
    // =====================================
    @Test
    public void testBoundsInOptionsInfersFreeformOnFreeformDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(new Rect(0, 0, 100, 100));

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testBoundsInOptionsInfersFreeformWithResizeableActivity() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchBounds(new Rect(0, 0, 100, 100));

        mCurrent.mPreferredTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testInheritsFreeformModeFromSourceOnFullscreenDisplay() {
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);
        final ActivityRecord source = createSourceActivity(fullscreenDisplay);
        source.setWindowingMode(WINDOWING_MODE_FREEFORM);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }


    @Test
    public void testKeepsPictureInPictureLaunchModeInOptions() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_PINNED);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_PINNED, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testKeepsPictureInPictureLaunchModeWithBoundsInOptions() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_PINNED);
        options.setLaunchBounds(new Rect(0, 0, 100, 100));

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_PINNED, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testKeepsFullscreenLaunchModeInOptionsOnNonFreeformDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);

        mCurrent.mPreferredTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testNonEmptyLayoutInfersFreeformOnFreeformDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testLayoutWithGravityAndEmptySizeInfersFreeformAndRespectsCurrentSize() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final Rect expectedLaunchBounds = new Rect(0, 0, 200, 100);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mBounds.set(expectedLaunchBounds);

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(expectedLaunchBounds.width(), mResult.mBounds.width());
        assertEquals(expectedLaunchBounds.height(), mResult.mBounds.height());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testNonEmptyLayoutUsesFullscreenWithResizeableActivity() {
        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).build();

        mCurrent.mPreferredTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testLaunchesFullscreenOnFullscreenDisplayWithFreeformHistory() {
        mCurrent.mPreferredTaskDisplayArea = null;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testRespectsFullyResolvedCurrentParam_Fullscreen() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FULLSCREEN;

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testRespectsModeFromFullyResolvedCurrentParam_NonEmptyBounds() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testForceMaximizesUnresizeableApp() {
        mAtm.mSupportsNonResizableMultiWindow = false;
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(new Rect(0, 0, 200, 100));

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testLaunchesPortraitSizeCompatOnFreeformLandscapeDisplayWithFreeformSizeCompat() {
        mAtm.mSupportsNonResizableMultiWindow = true;
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        Rect expectedLaunchBounds = new Rect(0, 0, 100, 200);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(expectedLaunchBounds);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(expectedLaunchBounds);

        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(expectedLaunchBounds, mResult.mBounds);

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testLaunchesLandscapeSizeCompatOnFreeformLandscapeDisplayWithFreeformSizeCompat() {
        mAtm.mSupportsNonResizableMultiWindow = true;
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final ActivityOptions options = ActivityOptions.makeBasic();
        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.screenOrientation = SCREEN_ORIENTATION_LANDSCAPE;
        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testLaunchesPortraitUnresizableOnFreeformDisplayWithFreeformSizeCompat() {
        mAtm.mSupportsNonResizableMultiWindow = true;
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);
        final ActivityOptions options = ActivityOptions.makeBasic();
        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;
        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testSkipsForceMaximizingAppsOnNonFreeformDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);
        options.setLaunchBounds(new Rect(0, 0, 200, 100));

        mCurrent.mPreferredTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        mActivity.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testUsesFullscreenOnNonFreeformDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(DEFAULT_DISPLAY);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testUsesFullscreenWhenRequestedOnFreeformDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testUsesFreeformByDefaultForPostNApp() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testUsesFreeformByDefaultForPreNResizeableAppWithoutOrientationRequest() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FREEFORM, mResult.mWindowingMode,
                WINDOWING_MODE_FREEFORM);
    }

    @Test
    public void testSkipsFreeformForPreNResizeableAppOnNonFullscreenDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(DEFAULT_DISPLAY);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquivalentWindowingMode(WINDOWING_MODE_FULLSCREEN, mResult.mWindowingMode,
                WINDOWING_MODE_FULLSCREEN);
    }

    // ================================
    // Launching Bounds Related Tests
    // ===============================
    @Test
    public void testKeepsBoundsWithPictureInPictureLaunchModeInOptions() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_PINNED);

        final Rect expected = new Rect(0, 0, 100, 100);
        options.setLaunchBounds(expected);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(expected, mResult.mBounds);
    }

    @Test
    public void testRespectsLaunchBoundsWithFreeformSourceOnFullscreenDisplay() {
        final TestDisplayContent fullscreenDisplay = createNewDisplayContent(
                WINDOWING_MODE_FULLSCREEN);
        final ActivityRecord source = createSourceActivity(fullscreenDisplay);
        source.setWindowingMode(WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        final Rect expected = new Rect(0, 0, 150, 150);
        options.setLaunchBounds(expected);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).setOptions(options).calculate());

        assertEquals(expected, mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_LeftGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.left, mResult.mBounds.left);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_TopGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.TOP).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.top, mResult.mBounds.top);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_TopLeftGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.TOP | Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.left, mResult.mBounds.left);
        assertEquals(DISPLAY_STABLE_BOUNDS.top, mResult.mBounds.top);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_RightGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.right, mResult.mBounds.right);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_BottomGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.BOTTOM).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.bottom, mResult.mBounds.bottom);
    }

    @Test
    public void testNonEmptyLayoutBoundsRespectsGravityWithEmptySize_BottomRightGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setGravity(Gravity.BOTTOM | Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(DISPLAY_STABLE_BOUNDS.right, mResult.mBounds.right);
        assertEquals(DISPLAY_STABLE_BOUNDS.bottom, mResult.mBounds.bottom);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_CenterToDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(800, 400, 920, 480), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_LeftGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(100, 400, 220, 480), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_TopGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.TOP).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(800, 200, 920, 280), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_TopLeftGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.TOP | Gravity.LEFT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(100, 200, 220, 280), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_RightGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(1500, 400, 1620, 480), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_BottomGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.BOTTOM).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(800, 600, 920, 680), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutBoundsOnFreeformDisplay_RightBottomGravity() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidth(120).setHeight(80).setGravity(Gravity.BOTTOM | Gravity.RIGHT).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(1500, 600, 1620, 680), mResult.mBounds);
    }

    @Test
    public void testNonEmptyLayoutFractionBoundsOnFreeformDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setWidthFraction(0.125f).setHeightFraction(0.1f).build();

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).calculate());

        assertEquals(new Rect(765, 416, 955, 464), mResult.mBounds);
    }

    @Test
    public void testRespectBoundsFromFullyResolvedCurrentParam_NonEmptyBounds() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(new Rect(0, 0, 200, 100), mResult.mBounds);
    }

    @Test
    public void testReturnBoundsForFullscreenWindowingMode() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(new Rect(0, 0, 200, 100), mResult.mBounds);
    }

    @Test
    public void testUsesDisplayOrientationForNoSensorOrientation() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_NOSENSOR;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        final int orientationForDisplay = orientationFromBounds(freeformDisplay.getBounds());
        final int orientationForTask = orientationFromBounds(mResult.mBounds);
        assertEquals("Launch bounds orientation should be the same as the display, but"
                        + " display orientation is "
                        + ActivityInfo.screenOrientationToString(orientationForDisplay)
                        + " launch bounds orientation is "
                        + ActivityInfo.screenOrientationToString(orientationForTask),
                orientationForDisplay, orientationForTask);
    }

    @Test
    public void testRespectsAppRequestedOrientation_Landscape() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_LANDSCAPE;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, orientationFromBounds(mResult.mBounds));
    }

    @Test
    public void testRespectsAppRequestedOrientation_Portrait() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FREEFORM);

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(SCREEN_ORIENTATION_PORTRAIT, orientationFromBounds(mResult.mBounds));
    }

    @Test
    public void testDefaultSizeSmallerThanBigScreen() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        final int resultWidth = mResult.mBounds.width();
        final int displayWidth = freeformDisplay.getBounds().width();
        assertTrue("Result width " + resultWidth + " is not smaller than " + displayWidth,
                resultWidth < displayWidth);

        final int resultHeight = mResult.mBounds.height();
        final int displayHeight = freeformDisplay.getBounds().height();
        assertTrue("Result width " + resultHeight + " is not smaller than "
                        + displayHeight, resultHeight < displayHeight);
    }

    @Test
    public void testDefaultFreeformSizeCenteredToDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals("Distance to left and right should be equal.",
                mResult.mBounds.left - DISPLAY_STABLE_BOUNDS.left,
                DISPLAY_STABLE_BOUNDS.right - mResult.mBounds.right, /* delta */ 1);
        assertEquals("Distance to top and bottom should be equal.",
                mResult.mBounds.top - DISPLAY_STABLE_BOUNDS.top,
                DISPLAY_STABLE_BOUNDS.bottom - mResult.mBounds.bottom, /* delta */ 1);
    }

    @Test
    public void testCascadesToSourceSizeForFreeform() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityRecord source = createSourceActivity(freeformDisplay);
        source.setBounds(0, 0, 412, 732);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).setOptions(options).calculate());

        final Rect displayBounds = freeformDisplay.getBounds();
        assertTrue("Left bounds should be larger than 0.", mResult.mBounds.left > 0);
        assertTrue("Top bounds should be larger than 0.", mResult.mBounds.top > 0);
        assertTrue("Bounds should be centered at somewhere in the left half, but it's "
                + "centerX is " + mResult.mBounds.centerX(),
                mResult.mBounds.centerX() < displayBounds.centerX());
        assertTrue("Bounds should be centered at somewhere in the top half, but it's "
                        + "centerY is " + mResult.mBounds.centerY(),
                mResult.mBounds.centerY() < displayBounds.centerY());
    }

    @Test
    public void testAdjustBoundsToFitDisplay_TopLeftOutOfDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityRecord source = createSourceActivity(freeformDisplay);
        source.setBounds(0, 0, 200, 400);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).setOptions(options).calculate());

        final Rect displayBounds = freeformDisplay.getBounds();
        assertTrue("display bounds doesn't contain result. display bounds: "
                + displayBounds + " result: " + mResult.mBounds,
                displayBounds.contains(mResult.mBounds));
    }

    @Test
    public void testAdjustBoundsToFitDisplay_BottomRightOutOfDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityRecord source = createSourceActivity(freeformDisplay);
        source.setBounds(1720, 680, 1920, 1080);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setSource(source).setOptions(options).calculate());

        final Rect displayBounds = freeformDisplay.getBounds();
        assertTrue("display bounds doesn't contain result. display bounds: "
                        + displayBounds + " result: " + mResult.mBounds,
                displayBounds.contains(mResult.mBounds));
    }

    @Test
    public void testAdjustBoundsToFitNewDisplay_LargerThanDisplay() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(100, 300, 1820, 1380);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertTrue("Result bounds should start from app bounds's origin, but it's "
                        + mResult.mBounds,
                mResult.mBounds.left == 100 && mResult.mBounds.top == 200);
    }

    @Test
    public void testAdjustBoundsToFitNewDisplay_LargerThanDisplay_RTL() {
        final Configuration overrideConfig =
                mRootWindowContainer.getRequestedOverrideConfiguration();
        // Egyptian Arabic is a RTL language.
        overrideConfig.setLayoutDirection(new Locale("ar", "EG"));
        mRootWindowContainer.onRequestedOverrideConfigurationChanged(overrideConfig);

        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(100, 300, 1820, 1380);

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertTrue("Result bounds should start from top-right corner of app bounds, but "
                        + "it's " + mResult.mBounds,
                mResult.mBounds.left == -100 && mResult.mBounds.top == 200);
    }

    @Test
    public void testRespectsLayoutMinDimensions() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        // This test case requires a relatively big app bounds to ensure the default size calculated
        // by letterbox won't be too small to hold the minimum width/height.
        configInsetsState(
                freeformDisplay.getInsetsStateController().getRawInsetsState(), freeformDisplay,
                new Rect(10, 10, 1910, 1070));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        final ActivityInfo.WindowLayout layout = new WindowLayoutBuilder()
                .setMinWidth(500).setMinHeight(800).build();

        mActivity.info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.LOLLIPOP;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setLayout(layout).setOptions(options).calculate());

        assertEquals(500, mResult.mBounds.width());
        assertEquals(800, mResult.mBounds.height());
    }

    @Test
    public void testRotatesInPlaceInitialBoundsMismatchOrientation() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(100, 100, 500, 300));

        mActivity.info.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(200, 0, 400, 400), mResult.mBounds);
    }

    @Test
    public void testShiftsToRightForCloseToLeftBoundsWhenConflict() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(50, 50, 100, 150));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(50, 50, 500, 300));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(170, 50, 620, 300), mResult.mBounds);
    }

    @Test
    public void testShiftsToLeftForCloseToRightBoundsWhenConflict() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(1720, 50, 1830, 150));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(1720, 50, 1850, 300));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(1600, 50, 1730, 300), mResult.mBounds);
    }

    @Test
    public void testShiftsToRightFirstForHorizontallyCenteredAndCloseToTopWhenConflict() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(0, 0, 100, 300));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(0, 0, 1800, 200));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(120, 0, 1920, 200), mResult.mBounds);
    }

    @Test
    public void testShiftsToLeftNoSpaceOnRightForHorizontallyCenteredAndCloseToTopWhenConflict() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(120, 0, 240, 300));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(120, 0, 1860, 200));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(0, 0, 1740, 200), mResult.mBounds);
    }

    @Test
    public void testShiftsToBottomRightFirstForCenteredBoundsWhenConflict() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(120, 0, 240, 100));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(120, 0, 1800, 1013));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(240, 67, 1920, 1080), mResult.mBounds);
    }

    @Test
    public void testShiftsToTopLeftIfNoSpaceOnBottomRightForCenteredBoundsWhenConflict() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(120, 67, 240, 100));

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);
        options.setLaunchBounds(new Rect(120, 67, 1800, 1020));

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(0, 0, 1680, 953), mResult.mBounds);
    }

    @Test
    public void returnsNonFullscreenBoundsOnFullscreenDisplayWithFreeformHistory() {
        mCurrent.mPreferredTaskDisplayArea = null;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        // Returned bounds with in fullscreen mode will be set to last non-fullscreen bounds.
        assertEquals(new Rect(0, 0, 200, 100), mResult.mBounds);
    }

    @Test
    public void testAdjustsBoundsToFitInDisplayFullyResolvedBounds() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        mCurrent.mPreferredTaskDisplayArea = null;
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(-100, -200, 200, 100);

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(freeformDisplay.mDisplayId);

        assertEquals(RESULT_CONTINUE,
                new CalculateRequestBuilder().setOptions(options).calculate());

        assertEquals(new Rect(100, 200, 400, 500), mResult.mBounds);
    }

    @Test
    public void testAdjustsBoundsToAvoidConflictFullyResolvedBounds() {
        final TestDisplayContent freeformDisplay = createNewDisplayContent(
                WINDOWING_MODE_FREEFORM);

        addFreeformTaskTo(freeformDisplay, new Rect(0, 0, 200, 100));

        mCurrent.mPreferredTaskDisplayArea = freeformDisplay.getDefaultTaskDisplayArea();
        mCurrent.mWindowingMode = WINDOWING_MODE_FREEFORM;
        mCurrent.mBounds.set(0, 0, 200, 100);

        assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());

        assertEquals(new Rect(120, 0, 320, 100), mResult.mBounds);
    }

    @Test
    public void testAdjustBoundsToAvoidConflictAlwaysExits() {
        Rect displayBounds = new Rect(0, 0, 40, 40);
        List<Rect> existingTaskBounds = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            for (int j = 0; j < 9; j++) {
                int left = i * 5;
                int top = j * 5;
                existingTaskBounds.add(new Rect(left, top, left + 20, top + 20));
            }
        }
        Rect startingBounds = new Rect(0, 0, 20, 20);
        Rect adjustedBounds = new Rect(startingBounds);
        mTarget.adjustBoundsToAvoidConflict(displayBounds, existingTaskBounds, adjustedBounds);
        assertEquals(startingBounds, adjustedBounds);
    }

    @Test
    public void testNoMultiDisplaySupports() {
        final boolean orgValue = mAtm.mSupportsMultiDisplay;
        final TestDisplayContent display = createNewDisplayContent(WINDOWING_MODE_FULLSCREEN);
        mCurrent.mPreferredTaskDisplayArea = display.getDefaultTaskDisplayArea();

        try {
            mAtm.mSupportsMultiDisplay = false;
            assertEquals(RESULT_CONTINUE, new CalculateRequestBuilder().calculate());
            assertEquals(mRootWindowContainer.getDefaultTaskDisplayArea(),
                    mResult.mPreferredTaskDisplayArea);
        } finally {
            mAtm.mSupportsMultiDisplay = orgValue;
        }
    }

    private TestDisplayContent createNewDisplayContent(int windowingMode) {
        final TestDisplayContent display = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        display.setWindowingMode(windowingMode);
        display.setBounds(DISPLAY_BOUNDS);
        display.getConfiguration().densityDpi = DENSITY_DEFAULT;
        display.getConfiguration().orientation = ORIENTATION_LANDSCAPE;
        configInsetsState(display.getInsetsStateController().getRawInsetsState(), display,
                DISPLAY_STABLE_BOUNDS);
        return display;
    }

    /**
     * Creates insets sources so that we can get the expected stable frame.
     */
    private static void configInsetsState(InsetsState state, DisplayContent display,
            Rect stableFrame) {
        final Rect displayFrame = display.getBounds();
        final int dl = displayFrame.left;
        final int dt = displayFrame.top;
        final int dr = displayFrame.right;
        final int db = displayFrame.bottom;
        final int sl = stableFrame.left;
        final int st = stableFrame.top;
        final int sr = stableFrame.right;
        final int sb = stableFrame.bottom;

        state.setDisplayFrame(displayFrame);
        if (sl > dl) {
            state.getSource(ITYPE_CLIMATE_BAR).setFrame(dl, dt, sl, db);
        }
        if (st > dt) {
            state.getSource(ITYPE_STATUS_BAR).setFrame(dl, dt, dr, st);
        }
        if (sr < dr) {
            state.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setFrame(sr, dt, dr, db);
        }
        if (sb < db) {
            state.getSource(ITYPE_NAVIGATION_BAR).setFrame(dl, sb, dr, db);
        }
        // Recompute config and push to children.
        display.onRequestedOverrideConfigurationChanged(display.getConfiguration());
    }

    private ActivityRecord createSourceActivity(TestDisplayContent display) {
        final Task stack = display.getDefaultTaskDisplayArea()
                .createRootTask(display.getWindowingMode(), ACTIVITY_TYPE_STANDARD, true);
        return new ActivityBuilder(mAtm).setTask(stack).build();
    }

    private void addFreeformTaskTo(TestDisplayContent display, Rect bounds) {
        final Task stack = display.getDefaultTaskDisplayArea()
                .createRootTask(display.getWindowingMode(), ACTIVITY_TYPE_STANDARD, true);
        stack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        final Task task = new TaskBuilder(mSupervisor).setParentTask(stack).build();
        // Just work around the unnecessary adjustments for bounds.
        task.getWindowConfiguration().setBounds(bounds);
    }

    private void assertEquivalentWindowingMode(int expected, int actual, int parentWindowingMode) {
        if (expected != parentWindowingMode) {
            assertEquals(expected, actual);
        } else {
            assertEquals(WINDOWING_MODE_UNDEFINED, actual);
        }
    }

    private int orientationFromBounds(Rect bounds) {
        return bounds.width() > bounds.height() ? SCREEN_ORIENTATION_LANDSCAPE
                : SCREEN_ORIENTATION_PORTRAIT;
    }

    private class CalculateRequestBuilder {
        private Task mTask;
        private ActivityInfo.WindowLayout mLayout;
        private ActivityRecord mActivity = TaskLaunchParamsModifierTests.this.mActivity;
        private ActivityRecord mSource;
        private ActivityOptions mOptions;
        private Request mRequest;
        private int mPhase = PHASE_BOUNDS;
        private LaunchParams mCurrentParams = mCurrent;
        private LaunchParams mOutParams = mResult;

        private CalculateRequestBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        private CalculateRequestBuilder setLayout(ActivityInfo.WindowLayout layout) {
            mLayout = layout;
            return this;
        }

        private CalculateRequestBuilder setActivity(ActivityRecord activity) {
            mActivity = activity;
            return this;
        }

        private CalculateRequestBuilder setSource(ActivityRecord source) {
            mSource = source;
            return this;
        }

        private CalculateRequestBuilder setOptions(ActivityOptions options) {
            mOptions = options;
            return this;
        }

        private CalculateRequestBuilder setRequest(Request request) {
            mRequest = request;
            return this;
        }

        private @Result int calculate() {
            return mTarget.onCalculate(mTask, mLayout, mActivity, mSource, mOptions, mRequest,
                    mPhase, mCurrentParams, mOutParams);
        }
    }

    private static class WindowLayoutBuilder {
        private int mWidth = -1;
        private int mHeight = -1;
        private float mWidthFraction = -1f;
        private float mHeightFraction = -1f;
        private int mGravity = Gravity.NO_GRAVITY;
        private int mMinWidth = -1;
        private int mMinHeight = -1;

        private WindowLayoutBuilder setWidth(int width) {
            mWidth = width;
            return this;
        }

        private WindowLayoutBuilder setHeight(int height) {
            mHeight = height;
            return this;
        }

        private WindowLayoutBuilder setWidthFraction(float widthFraction) {
            mWidthFraction = widthFraction;
            return this;
        }

        private WindowLayoutBuilder setHeightFraction(float heightFraction) {
            mHeightFraction = heightFraction;
            return this;
        }

        private WindowLayoutBuilder setGravity(int gravity) {
            mGravity = gravity;
            return this;
        }

        private WindowLayoutBuilder setMinWidth(int minWidth) {
            mMinWidth = minWidth;
            return this;
        }

        private WindowLayoutBuilder setMinHeight(int minHeight) {
            mMinHeight = minHeight;
            return this;
        }

        private ActivityInfo.WindowLayout build() {
            return new ActivityInfo.WindowLayout(mWidth, mWidthFraction, mHeight, mHeightFraction,
                    mGravity, mMinWidth, mMinHeight);
        }
    }
}
