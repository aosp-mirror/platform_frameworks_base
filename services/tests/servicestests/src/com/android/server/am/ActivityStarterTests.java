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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Test;

import static com.android.server.am.ActivityManagerService.ANIMATE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Tests for the {@link ActivityStack} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStarterTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStarterTests extends ActivityTestsBase {
    private static final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");

    private ActivityManagerService mService;
    private ActivityStarter mStarter;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mService = createActivityManagerService();
        mStarter = new ActivityStarter(mService, mService.mStackSupervisor);
    }

    @Test
    public void testUpdateLaunchBounds() throws Exception {
        // When in a non-resizeable stack, the task bounds should be updated.
        final TaskRecord task = createTask(mService.mStackSupervisor, testActivityComponent,
                mService.mStackSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */));
        final Rect bounds = new Rect(10, 10, 100, 100);

        mStarter.updateBounds(task, bounds);
        assertEquals(task.mBounds, bounds);
        assertEquals(task.getStack().mBounds, null);

        // When in a resizeable stack, the stack bounds should be updated as well.
        final TaskRecord task2 = createTask(mService.mStackSupervisor, testActivityComponent,
                mService.mStackSupervisor.getDefaultDisplay().createStack(
                        WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */));
        assertTrue(task2.getStack() instanceof PinnedActivityStack);
        mStarter.updateBounds(task2, bounds);

        verify(mService, times(1)).resizeStack(eq(ActivityManager.StackId.PINNED_STACK_ID),
                eq(bounds), anyBoolean(), anyBoolean(), anyBoolean(), anyInt());

        // In the case of no animation, the stack and task bounds should be set immediately.
        if (!ANIMATE) {
            assertEquals(task2.getStack().mBounds, bounds);
            assertEquals(task2.mBounds, bounds);
        } else {
            assertEquals(task2.mBounds, null);
        }
    }
}