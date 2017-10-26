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
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.WindowLayout;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

import com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner.RESULT_DONE;
import static com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner.RESULT_CONTINUE;
import static com.android.server.am.LaunchingBoundsController.LaunchingBoundsPositioner.RESULT_SKIP;

import static org.junit.Assert.assertEquals;

/**
 * Tests for exercising {@link LaunchingBoundsController}.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.LaunchingBoundsControllerTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LaunchingBoundsControllerTests extends ActivityTestsBase {
    private LaunchingBoundsController mController;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mController = new LaunchingBoundsController();
    }

    /**
     * Makes sure positioners get values passed to controller.
     */
    @Test
    public void testArgumentPropagation() {
        final ActivityManagerService service = createActivityManagerService();
        final LaunchingBoundsPositioner positioner = mock(LaunchingBoundsPositioner.class);
        mController.registerPositioner(positioner);

        final ActivityRecord record = new ActivityBuilder(service).build();
        final ActivityRecord source = new ActivityBuilder(service).build();
        final WindowLayout layout = new WindowLayout(0, 0, 0, 0, 0, 0, 0);
        final ActivityOptions options = mock(ActivityOptions.class);

        mController.calculateBounds(record.getTask(), layout, record, source, options, new Rect());
        verify(positioner, times(1)).onCalculateBounds(eq(record.getTask()), eq(layout), eq(record),
                eq(source), eq(options), any(), any());
    }

    /**
     * Ensures positioners further down the chain are not called when RESULT_DONE is returned.
     */
    @Test
    public void testEarlyExit() {
        final LaunchingBoundsPositioner ignoredPositioner = mock(LaunchingBoundsPositioner.class);
        final LaunchingBoundsPositioner earlyExitPositioner =
                (task, layout, activity, source, options, current, result) -> RESULT_DONE;

        mController.registerPositioner(ignoredPositioner);
        mController.registerPositioner(earlyExitPositioner);

        mController.calculateBounds(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, new Rect());
        verify(ignoredPositioner, never()).onCalculateBounds(any(), any(), any(), any(), any(),
                any(), any());
    }

    /**
     * Ensures that positioners are called in the correct order.
     */
    @Test
    public void testRegistration() {
        LaunchingBoundsPositioner earlyExitPositioner =
                new InstrumentedPositioner(RESULT_DONE, new Rect());

        final LaunchingBoundsPositioner firstPositioner = spy(earlyExitPositioner);

        mController.registerPositioner(firstPositioner);

        mController.calculateBounds(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, new Rect());
        verify(firstPositioner, times(1)).onCalculateBounds(any(), any(), any(), any(), any(),
                any(), any());

        final LaunchingBoundsPositioner secondPositioner = spy(earlyExitPositioner);

        mController.registerPositioner(secondPositioner);

        mController.calculateBounds(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, new Rect());
        verify(firstPositioner, times(1)).onCalculateBounds(any(), any(), any(), any(), any(),
                any(), any());
        verify(secondPositioner, times(1)).onCalculateBounds(any(), any(), any(), any(), any(),
                any(), any());
    }

    /**
     * Makes sure positioners further down the registration chain are called.
     */
    @Test
    public void testPassThrough() {
        final LaunchingBoundsPositioner positioner1 = mock(LaunchingBoundsPositioner.class);
        final InstrumentedPositioner positioner2 = new InstrumentedPositioner(RESULT_CONTINUE,
                new Rect (0, 0, 30, 20));

        mController.registerPositioner(positioner1);
        mController.registerPositioner(positioner2);

        mController.calculateBounds(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, new Rect());

        verify(positioner1, times(1)).onCalculateBounds(any(), any(), any(), any(), any(),
                eq(positioner2.getLaunchBounds()), any());
    }

    /**
     * Ensures skipped results are not propagated.
     */
    @Test
    public void testSkip() {
        final InstrumentedPositioner positioner1 =
                new InstrumentedPositioner(RESULT_SKIP, new Rect(0, 0, 10, 10));


        final InstrumentedPositioner positioner2 =
                new InstrumentedPositioner(RESULT_CONTINUE, new Rect(0, 0, 20, 30));

        mController.registerPositioner(positioner1);
        mController.registerPositioner(positioner2);

        final Rect resultBounds = new Rect();

        mController.calculateBounds(null /*task*/, null /*layout*/, null /*activity*/,
                null /*source*/, null /*options*/, resultBounds);

        assertEquals(resultBounds, positioner2.getLaunchBounds());
    }

    public static class InstrumentedPositioner implements LaunchingBoundsPositioner {
        private int mReturnVal;
        private Rect mBounds;
        InstrumentedPositioner(int returnVal, Rect bounds) {
            mReturnVal = returnVal;
            mBounds = bounds;
        }

        @Override
        public int onCalculateBounds(TaskRecord task, ActivityInfo.WindowLayout layout,
                ActivityRecord activity, ActivityRecord source,
                ActivityOptions options, Rect current, Rect result) {
            result.set(mBounds);
            return mReturnVal;
        }

        Rect getLaunchBounds() {
            return mBounds;
        }
    }
}
