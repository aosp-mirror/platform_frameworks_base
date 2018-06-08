/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo.WindowLayout;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.server.am.LaunchParamsController.LaunchParams;
import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

import com.android.server.am.LaunchParamsController.LaunchParamsModifier;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_DONE;
import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_CONTINUE;
import static com.android.server.am.LaunchParamsController.LaunchParamsModifier.RESULT_SKIP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Tests for exercising {@link LaunchParamsController}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:LaunchParamsControllerTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LaunchParamsControllerTests extends ActivityTestsBase {
    private ActivityManagerService mService;
    private LaunchParamsController mController;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mService = createActivityManagerService();
        mController = new LaunchParamsController(mService);
    }

    /**
     * Makes sure positioners get values passed to controller.
     */
    @Test
    public void testArgumentPropagation() {
        final LaunchParamsModifier
                positioner = mock(LaunchParamsModifier.class);
        mController.registerModifier(positioner);

        final ActivityRecord record = new ActivityBuilder(mService).build();
        final ActivityRecord source = new ActivityBuilder(mService).build();
        final WindowLayout layout = new WindowLayout(0, 0, 0, 0, 0, 0, 0);
        final ActivityOptions options = mock(ActivityOptions.class);

        mController.calculate(record.getTask(), layout, record, source, options,
                new LaunchParams());
        verify(positioner, times(1)).onCalculate(eq(record.getTask()), eq(layout), eq(record),
                eq(source), eq(options), any(), any());
    }

    /**
     * Ensures positioners further down the chain are not called when RESULT_DONE is returned.
     */
    @Test
    public void testEarlyExit() {
        final LaunchParamsModifier
                ignoredPositioner = mock(LaunchParamsModifier.class);
        final LaunchParamsModifier earlyExitPositioner =
                (task, layout, activity, source, options, currentParams, outParams) -> RESULT_DONE;

        mController.registerModifier(ignoredPositioner);
        mController.registerModifier(earlyExitPositioner);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, new LaunchParams());
        verify(ignoredPositioner, never()).onCalculate(any(), any(), any(), any(), any(),
                any(), any());
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
                null /*source*/, null /*options*/, new LaunchParams());
        verify(firstPositioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                any());

        final LaunchParamsModifier secondPositioner = spy(earlyExitPositioner);

        mController.registerModifier(secondPositioner);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, new LaunchParams());
        verify(firstPositioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                any());
        verify(secondPositioner, times(1)).onCalculate(any(), any(), any(), any(), any(), any(),
                any());
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
        params.mPreferredDisplayId = 3;

        final InstrumentedPositioner positioner2 = new InstrumentedPositioner(RESULT_CONTINUE,
                params);

        mController.registerModifier(positioner1);
        mController.registerModifier(positioner2);

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/, null /*source*/,
                null /*options*/, new LaunchParams());

        verify(positioner1, times(1)).onCalculate(any(), any(), any(), any(), any(),
                eq(positioner2.getLaunchParams()), any());
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

        final LaunchParams
                result = new LaunchParams();

        mController.calculate(null /*task*/, null /*layout*/, null /*activity*/, null /*source*/,
                null /*options*/, result);

        assertEquals(result, positioner2.getLaunchParams());
    }

    /**
     * Ensures that {@link LaunchParamsModifier} requests specifying display id during
     * layout are honored.
     */
    @Test
    public void testLayoutTaskPreferredDisplayChange() {
        final LaunchParams params = new LaunchParams();
        params.mPreferredDisplayId = 2;
        final InstrumentedPositioner positioner = new InstrumentedPositioner(RESULT_DONE, params);
        final TaskRecord task = new TaskBuilder(mService.mStackSupervisor).build();

        mController.registerModifier(positioner);

        doNothing().when(mService).moveStackToDisplay(anyInt(), anyInt());
        mController.layoutTask(task, null /* windowLayout */);
        verify(mService, times(1)).moveStackToDisplay(eq(task.getStackId()),
                eq(params.mPreferredDisplayId));
    }

    /**
     * Ensures that {@link LaunchParamsModifier} requests specifying windowingMode during
     * layout are honored.
     */
    @Test
    public void testLayoutTaskWindowingModeChange() {
        final LaunchParams params = new LaunchParams();
        final int windowingMode = WINDOWING_MODE_FREEFORM;
        params.mWindowingMode = windowingMode;
        final InstrumentedPositioner positioner = new InstrumentedPositioner(RESULT_DONE, params);
        final TaskRecord task = new TaskBuilder(mService.mStackSupervisor).build();

        mController.registerModifier(positioner);

        final int beforeWindowMode = task.getStack().getWindowingMode();
        assertNotEquals(beforeWindowMode, windowingMode);

        mController.layoutTask(task, null /* windowLayout */);

        final int afterWindowMode = task.getStack().getWindowingMode();
        assertEquals(afterWindowMode, windowingMode);
    }

    public static class InstrumentedPositioner implements
            LaunchParamsModifier {

        final private int mReturnVal;
        final private LaunchParams mParams;

        InstrumentedPositioner(int returnVal, LaunchParams params) {
            mReturnVal = returnVal;
            mParams = params;
        }

        @Override
        public int onCalculate(TaskRecord task, WindowLayout layout, ActivityRecord activity,
                   ActivityRecord source, ActivityOptions options,
                   LaunchParams currentParams, LaunchParams outParams) {
            outParams.set(mParams);
            return mReturnVal;
        }

        LaunchParams getLaunchParams() {
            return mParams;
        }
    }
}
