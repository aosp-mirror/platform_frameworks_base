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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityStarter.Request;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_BOUNDS;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_DONE;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.annotation.NonNull;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.SparseArray;

import androidx.test.filters.MediumTest;

import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/**
 * Tests for exercising {@link LaunchParamsController}.
 *
 * Build/Install/Run:
 *  atest WmTests:LaunchParamsControllerTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class LaunchParamsControllerTests extends WindowTestsBase {
    private LaunchParamsController mController;
    private TestLaunchParamsPersister mPersister;

    @Before
    public void setUp() throws Exception {
        mPersister = new TestLaunchParamsPersister();
        mController = new LaunchParamsController(mAtm, mPersister);
    }

    /**
     * Makes sure positioners get values passed to controller.
     */
    @Test
    public void testArgumentPropagation() {
        final LaunchParamsModifier
                positioner = mock(LaunchParamsModifier.class);
        mController.registerModifier(positioner);

        final ActivityRecord record = new ActivityBuilder(mAtm).build();
        final ActivityRecord source = new ActivityBuilder(mAtm).build();
        final WindowLayout layout = new WindowLayout(0, 0, 0, 0, 0, 0, 0);
        final ActivityOptions options = mock(ActivityOptions.class);
        final Request request = new Request();

        mController.calculate(record.getTask(), layout, record, source, options, request,
                PHASE_BOUNDS, new LaunchParams());
        verify(positioner, times(1)).onCalculate(eq(record.getTask()), eq(layout), eq(record),
                eq(source), eq(options), eq(request), anyInt(), any(), any());
    }

    /**
     * Makes sure controller passes stored params to modifiers.
     */
    @Test
    public void testStoredParamsRecovery() {
        final LaunchParamsModifier positioner = mock(LaunchParamsModifier.class);
        mController.registerModifier(positioner);

        final ComponentName name = new ComponentName("com.android.foo", ".BarActivity");
        final int userId = 0;
        final ActivityRecord activity = new ActivityBuilder(mAtm).setComponent(name)
                .setUid(userId).build();
        final LaunchParams expected = new LaunchParams();
        expected.mPreferredTaskDisplayArea = mock(TaskDisplayArea.class);
        expected.mWindowingMode = WINDOWING_MODE_PINNED;
        expected.mBounds.set(200, 300, 400, 500);

        mPersister.putLaunchParams(userId, name, expected);

        mController.calculate(activity.getTask(), null /*layout*/, activity, null /*source*/,
                null /*options*/, null /*request*/, PHASE_BOUNDS, new LaunchParams());
        verify(positioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                anyInt(), eq(expected), any());
    }

    /**
     * Ensures positioners further down the chain are not called when RESULT_DONE is returned.
     */
    @Test
    public void testEarlyExit() {
        final LaunchParamsModifier
                ignoredPositioner = mock(LaunchParamsModifier.class);
        final LaunchParamsModifier earlyExitPositioner =
                (task, layout, activity, source, options, phase, currentParams, outParams, request)
                        -> RESULT_DONE;

        mController.registerModifier(ignoredPositioner);
        mController.registerModifier(earlyExitPositioner);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, null /*request*/,
                PHASE_BOUNDS, new LaunchParams());
        verify(ignoredPositioner, never()).onCalculate(any(), any(), any(), any(), any(), any(),
                anyInt(), any(), any());
    }

    /**
     * Ensures that positioners are called in the correct order.
     */
    @Test
    public void testRegistration() {
        LaunchParamsModifier earlyExitPositioner =
                new InstrumentedPositioner(RESULT_DONE, new LaunchParams());

        final LaunchParamsModifier firstPositioner = spy(earlyExitPositioner);

        mController.registerModifier(firstPositioner);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, null /*request*/, PHASE_BOUNDS,
                new LaunchParams());
        verify(firstPositioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                anyInt(), any(), any());

        final LaunchParamsModifier secondPositioner = spy(earlyExitPositioner);

        mController.registerModifier(secondPositioner);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, null /*request*/, PHASE_BOUNDS,
                new LaunchParams());
        verify(firstPositioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                anyInt(), any(), any());
        verify(secondPositioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                anyInt(), any(), any());
    }

    /**
     * Makes sure positioners further down the registration chain are called.
     */
    @Test
    public void testPassThrough() {
        final LaunchParamsModifier
                positioner1 = mock(LaunchParamsModifier.class);
        final LaunchParams params = new LaunchParams();
        params.mWindowingMode = WINDOWING_MODE_FREEFORM;
        params.mBounds.set(0, 0, 30, 20);
        params.mPreferredTaskDisplayArea = mock(TaskDisplayArea.class);

        final InstrumentedPositioner positioner2 = new InstrumentedPositioner(RESULT_CONTINUE,
                params);

        mController.registerModifier(positioner1);
        mController.registerModifier(positioner2);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/, null /*source*/,
                null /*options*/, null /*request*/, PHASE_BOUNDS, new LaunchParams());

        verify(positioner1, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                anyInt(), eq(positioner2.getLaunchParams()), any());
    }

    /**
     * Ensures skipped results are not propagated.
     */
    @Test
    public void testSkip() {
        final LaunchParams params1 = new LaunchParams();
        params1.mBounds.set(0, 0, 10, 10);
        final InstrumentedPositioner positioner1 = new InstrumentedPositioner(RESULT_SKIP, params1);

        final LaunchParams params2 = new LaunchParams();
        params2.mBounds.set(0, 0, 20, 30);
        final InstrumentedPositioner positioner2 =
                new InstrumentedPositioner(RESULT_CONTINUE, params2);

        mController.registerModifier(positioner1);
        mController.registerModifier(positioner2);

        final LaunchParams result = new LaunchParams();

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/, null /*source*/,
                null /*options*/, null /*request*/, PHASE_BOUNDS, result);

        assertEquals(result, positioner2.getLaunchParams());
    }

    /**
     * Tests preferred display id calculation for VR.
     */
    @Test
    public void testVrPreferredDisplay() {
        final TestDisplayContent vrDisplay = createNewDisplayContent();
        mAtm.mVr2dDisplayId = vrDisplay.mDisplayId;

        final LaunchParams result = new LaunchParams();
        final ActivityRecord vrActivity = new ActivityBuilder(mAtm).build();
        vrActivity.requestedVrComponent = vrActivity.mActivityComponent;

        // VR activities should always land on default display.
        mController.calculate(null /*task*/, null /*layout*/, vrActivity /*activity*/,
                null /*source*/, null /*options*/, null/*request*/, PHASE_BOUNDS, result);
        assertEquals(mRootWindowContainer.getDefaultTaskDisplayArea(),
                result.mPreferredTaskDisplayArea);

        // Otherwise, always lands on VR 2D display.
        final ActivityRecord vr2dActivity = new ActivityBuilder(mAtm).build();
        mController.calculate(null /*task*/, null /*layout*/, vr2dActivity /*activity*/,
                null /*source*/, null /*options*/, null /*request*/, PHASE_BOUNDS, result);
        assertEquals(vrDisplay.getDefaultTaskDisplayArea(), result.mPreferredTaskDisplayArea);
        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/, null /*source*/,
                null /*options*/, null /*request*/, PHASE_BOUNDS, result);
        assertEquals(vrDisplay.getDefaultTaskDisplayArea(), result.mPreferredTaskDisplayArea);

        mAtm.mVr2dDisplayId = INVALID_DISPLAY;
    }


    /**
     * Ensures that {@link LaunchParamsController} calculates to {@link PHASE_BOUNDS} phase by
     * default.
     */
    @Test
    public void testCalculatePhase() {
        final LaunchParamsModifier positioner = mock(LaunchParamsModifier.class);
        mController.registerModifier(positioner);

        final ActivityRecord record = new ActivityBuilder(mAtm).build();
        final ActivityRecord source = new ActivityBuilder(mAtm).build();
        final WindowLayout layout = new WindowLayout(0, 0, 0, 0, 0, 0, 0);
        final ActivityOptions options = mock(ActivityOptions.class);

        mController.calculate(record.getTask(), layout, record, source, options, null/*request*/,
                PHASE_BOUNDS, new LaunchParams());
        verify(positioner, times(1)).onCalculate(eq(record.getTask()), eq(layout), eq(record),
                eq(source), eq(options), any(), eq(PHASE_BOUNDS), any(), any());
    }

    /**
     * Ensures that {@link LaunchParamsModifier} doesn't alter non-root tasks' windowingMode.
     */
    @Test
    public void testLayoutNonRootTaskWindowingModeChange() {
        final LaunchParams params = new LaunchParams();
        final int windowingMode = WINDOWING_MODE_FREEFORM;
        params.mWindowingMode = windowingMode;
        final InstrumentedPositioner positioner = new InstrumentedPositioner(RESULT_DONE, params);
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor).setCreateParentTask(true).build();
        task.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);

        mController.registerModifier(positioner);

        final int beforeWindowMode = task.getWindowingMode();
        assertNotEquals(windowingMode, beforeWindowMode);

        layoutTask(task);

        final int afterWindowMode = task.getWindowingMode();
        assertEquals(afterWindowMode, beforeWindowMode);
    }

    /**
     * Ensures that {@link LaunchParamsModifier} requests specifying bounds during
     * layout are honored if window is in freeform.
     */
    @Test
    public void testLayoutTaskBoundsChangeFreeformWindow() {
        final Rect expected = new Rect(10, 20, 30, 40);

        final LaunchParams params = new LaunchParams();
        params.mBounds.set(expected);
        final InstrumentedPositioner positioner = new InstrumentedPositioner(RESULT_DONE, params);
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();

        mController.registerModifier(positioner);

        assertNotEquals(expected, task.getBounds());

        layoutTask(task);

        // Task will make adjustments to requested bounds. We only need to guarantee that the
        // reuqested bounds are expected.
        assertEquals(expected, task.getRequestedOverrideBounds());
    }

    /**
     * Ensures that {@link LaunchParamsModifier} requests specifying bounds during
     * layout are honored if window is in multiwindow mode.
     */
    @Test
    public void testLayoutTaskBoundsChangeMultiWindow() {
        final Rect expected = new Rect(10, 20, 30, 40);

        final LaunchParams params = new LaunchParams();
        params.mBounds.set(expected);
        final InstrumentedPositioner positioner = new InstrumentedPositioner(RESULT_DONE, params);
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setWindowingMode(WINDOWING_MODE_MULTI_WINDOW).build();

        mController.registerModifier(positioner);

        assertNotEquals(expected, task.getBounds());

        layoutTask(task);

        assertEquals(expected, task.getRequestedOverrideBounds());
    }

    /**
     * Ensures that {@link LaunchParamsModifier} requests specifying bounds during
     * layout are set to last non-fullscreen bounds.
     */
    @Test
    public void testLayoutTaskBoundsChangeFixedWindow() {
        final Rect expected = new Rect(10, 20, 30, 40);

        final LaunchParams params = new LaunchParams();
        params.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        params.mBounds.set(expected);
        final InstrumentedPositioner positioner = new InstrumentedPositioner(RESULT_DONE, params);
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor).build();

        mController.registerModifier(positioner);

        assertNotEquals(expected, task.getBounds());

        layoutTask(task);

        assertNotEquals(expected, task.getBounds());
        assertEquals(expected, task.mLastNonFullscreenBounds);
    }

    public static class InstrumentedPositioner implements LaunchParamsModifier {

        private final int mReturnVal;
        private final LaunchParams mParams;

        InstrumentedPositioner(int returnVal, LaunchParams params) {
            mReturnVal = returnVal;
            mParams = params;
        }

        @Override
        public int onCalculate(Task task, WindowLayout layout, ActivityRecord activity,
                ActivityRecord source, ActivityOptions options, Request request, int phase,
                LaunchParams currentParams, LaunchParams outParams) {
            outParams.set(mParams);
            return mReturnVal;
        }

        LaunchParams getLaunchParams() {
            return mParams;
        }
    }

    /**
     * Test double for {@link LaunchParamsPersister}. This class only manages an in-memory storage
     * of a mapping from user ID and component name to launch params.
     */
    static class TestLaunchParamsPersister extends LaunchParamsPersister {

        private final SparseArray<Map<ComponentName, LaunchParams>> mMap =
                new SparseArray<>();
        private final LaunchParams mTmpParams = new LaunchParams();

        TestLaunchParamsPersister() {
            super(null, null, null);
        }

        void putLaunchParams(int userId, ComponentName name, LaunchParams params) {
            Map<ComponentName, LaunchParams> map = mMap.get(userId);
            if (map == null) {
                map = new ArrayMap<>();
                mMap.put(userId, map);
            }

            LaunchParams paramRecord = map.get(name);
            if (paramRecord == null) {
                paramRecord = new LaunchParams();
                map.put(name, params);
            }

            paramRecord.set(params);
        }

        @Override
        void onUnlockUser(int userId) {
            if (mMap.get(userId) == null) {
                mMap.put(userId, new ArrayMap<>());
            }
        }

        @Override
        void saveTask(Task task, DisplayContent display) {
            final int userId = task.mUserId;
            final ComponentName realActivity = task.realActivity;
            mTmpParams.mPreferredTaskDisplayArea = task.getDisplayArea();
            mTmpParams.mWindowingMode = task.getWindowingMode();
            if (task.mLastNonFullscreenBounds != null) {
                mTmpParams.mBounds.set(task.mLastNonFullscreenBounds);
            } else {
                mTmpParams.mBounds.setEmpty();
            }
            putLaunchParams(userId, realActivity, mTmpParams);
        }

        @Override
        void getLaunchParams(Task task, ActivityRecord activity, LaunchParams params) {
            final int userId = task != null ? task.mUserId : activity.mUserId;
            final ComponentName name = task != null
                    ? task.realActivity : activity.mActivityComponent;

            params.reset();
            final Map<ComponentName, LaunchParams> map = mMap.get(userId);
            if (map == null) {
                return;
            }

            final LaunchParams paramsRecord = map.get(name);
            if (paramsRecord != null) {
                params.set(paramsRecord);
            }
        }
    }

    private TestDisplayContent createNewDisplayContent() {
        return addNewDisplayContentAt(DisplayContent.POSITION_TOP);
    }

    private void layoutTask(@NonNull Task task) {
        mController.layoutTask(task, null /* layout */, null /* activity */, null /* source */,
                null /* options */);
    }
}
