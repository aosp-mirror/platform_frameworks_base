/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.IDisplayWindowListener;
import android.view.WindowContainerTransaction;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for the {@link ActivityTaskManagerService} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityTaskManagerServiceTests
 */
@MediumTest
@RunWith(WindowTestRunner.class)
public class ActivityTaskManagerServiceTests extends ActivityTestsBase {

    @Before
    public void setUp() throws Exception {
        doReturn(false).when(mService).isBooting();
        doReturn(true).when(mService).isBooted();
    }

    /** Verify that activity is finished correctly upon request. */
    @Test
    public void testActivityFinish() {
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        final ActivityRecord activity = stack.getBottomMostTask().getTopNonFinishingActivity();
        assertTrue("Activity must be finished", mService.finishActivity(activity.appToken,
                0 /* resultCode */, null /* resultData */,
                Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
        assertTrue(activity.finishing);

        assertTrue("Duplicate activity finish request must also return 'true'",
                mService.finishActivity(activity.appToken, 0 /* resultCode */,
                        null /* resultData */, Activity.DONT_FINISH_TASK_WITH_ACTIVITY));
    }

    @Test
    public void testTaskTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new StackBuilder(mRootActivityContainer)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        final Task task = stack.getTopMostTask();
        WindowContainerTransaction t = new WindowContainerTransaction();
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(task.mRemoteToken, new Rect(10, 10, 100, 100));
        mService.applyContainerTransaction(t);
        assertEquals(newBounds, task.getBounds());
    }

    @Test
    public void testStackTransaction() {
        removeGlobalMinSizeRestriction();
        final ActivityStack stack = new StackBuilder(mRootActivityContainer)
                .setWindowingMode(WINDOWING_MODE_FREEFORM).build();
        ActivityManager.StackInfo info =
                mService.getStackInfo(WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        WindowContainerTransaction t = new WindowContainerTransaction();
        assertEquals(stack.mRemoteToken, info.stackToken);
        Rect newBounds = new Rect(10, 10, 100, 100);
        t.setBounds(info.stackToken, new Rect(10, 10, 100, 100));
        mService.applyContainerTransaction(t);
        assertEquals(newBounds, stack.getBounds());
    }

    @Test
    public void testDisplayWindowListener() {
        final ArrayList<Integer> added = new ArrayList<>();
        final ArrayList<Integer> changed = new ArrayList<>();
        final ArrayList<Integer> removed = new ArrayList<>();
        IDisplayWindowListener listener = new IDisplayWindowListener.Stub() {
            @Override
            public void onDisplayAdded(int displayId) {
                added.add(displayId);
            }

            @Override
            public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                changed.add(displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                removed.add(displayId);
            }
        };
        mService.mWindowManager.registerDisplayWindowListener(listener);
        // Check that existing displays call added
        assertEquals(1, added.size());
        assertEquals(0, changed.size());
        assertEquals(0, removed.size());
        added.clear();
        // Check adding a display
        ActivityDisplay newDisp1 = new TestActivityDisplay.Builder(mService, 600, 800).build();
        assertEquals(1, added.size());
        assertEquals(0, changed.size());
        assertEquals(0, removed.size());
        added.clear();
        // Check that changes are reported
        Configuration c = new Configuration(newDisp1.getRequestedOverrideConfiguration());
        c.windowConfiguration.setBounds(new Rect(0, 0, 1000, 1300));
        newDisp1.onRequestedOverrideConfigurationChanged(c);
        mService.mRootActivityContainer.ensureVisibilityAndConfig(null /* starting */,
                newDisp1.mDisplayId, false /* markFrozenIfConfigChanged */,
                false /* deferResume */);
        assertEquals(0, added.size());
        assertEquals(1, changed.size());
        assertEquals(0, removed.size());
        changed.clear();
        // Check that removal is reported
        newDisp1.remove();
        assertEquals(0, added.size());
        assertEquals(0, changed.size());
        assertEquals(1, removed.size());
    }
}

