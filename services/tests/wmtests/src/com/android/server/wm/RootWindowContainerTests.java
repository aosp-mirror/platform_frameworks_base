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

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.Task.ActivityState.FINISHING;
import static com.android.server.wm.Task.ActivityState.PAUSED;
import static com.android.server.wm.Task.ActivityState.PAUSING;
import static com.android.server.wm.Task.ActivityState.STOPPED;
import static com.android.server.wm.Task.ActivityState.STOPPING;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for RootWindowContainer.
 *
 * Build/Install/Run:
 *  atest WmTests:RootWindowContainerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RootWindowContainerTests extends WindowTestsBase {

    @Test
    public void testUpdateDefaultDisplayWindowingModeOnSettingsRetrieved() {
        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mWm.getDefaultDisplayContentLocked().getWindowingMode());

        mWm.mIsPc = true;
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;

        mWm.mRoot.onSettingsRetrieved();

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mWm.getDefaultDisplayContentLocked().getWindowingMode());
    }

    /**
     * This test ensures that an existing single instance activity with alias name can be found by
     * the same activity info. So {@link ActivityStarter#getReusableTask} won't miss it that leads
     * to create an unexpected new instance.
     */
    @Test
    public void testFindActivityByTargetComponent() {
        final ComponentName aliasComponent = ComponentName.createRelative(
                DEFAULT_COMPONENT_PACKAGE_NAME, ".AliasActivity");
        final ComponentName targetComponent = ComponentName.createRelative(
                aliasComponent.getPackageName(), ".TargetActivity");
        final ActivityRecord activity = new ActivityBuilder(mWm.mAtmService)
                .setComponent(aliasComponent)
                .setTargetActivity(targetComponent.getClassName())
                .setLaunchMode(ActivityInfo.LAUNCH_SINGLE_INSTANCE)
                .setCreateTask(true)
                .build();

        assertEquals(activity, mWm.mRoot.findActivity(activity.intent, activity.info,
                false /* compareIntentFilters */));
    }

    @Test
    public void testAllPausedActivitiesComplete() {
        DisplayContent displayContent = mWm.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        ActivityRecord activity = createActivityRecord(displayContent);
        Task task = activity.getTask();
        task.setPausingActivity(activity);

        activity.setState(PAUSING, "test PAUSING");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isFalse();

        activity.setState(PAUSED, "test PAUSED");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();

        activity.setState(STOPPED, "test STOPPED");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();

        activity.setState(STOPPING, "test STOPPING");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();

        activity.setState(FINISHING, "test FINISHING");
        assertThat(mWm.mRoot.allPausedActivitiesComplete()).isTrue();
    }

    @Test
    public void testTaskLayerRank() {
        final Task rootTask = new TaskBuilder(mSupervisor).build();
        final Task task1 = new TaskBuilder(mSupervisor).setParentTask(rootTask).build();
        new ActivityBuilder(mAtm).setTask(task1).build().mVisibleRequested = true;
        // RootWindowContainer#invalidateTaskLayers should post to update.
        waitHandlerIdle(mWm.mH);

        assertEquals(1, task1.mLayerRank);
        // Only tasks that directly contain activities have a ranking.
        assertEquals(Task.LAYER_RANK_INVISIBLE, rootTask.mLayerRank);

        final Task task2 = new TaskBuilder(mSupervisor).build();
        new ActivityBuilder(mAtm).setTask(task2).build().mVisibleRequested = true;
        waitHandlerIdle(mWm.mH);

        // Note that ensureActivitiesVisible is disabled in SystemServicesTestRule, so both the
        // activities have the visible rank.
        assertEquals(2, task1.mLayerRank);
        // The task2 is the top task, so it has a lower rank as a higher priority oom score.
        assertEquals(1, task2.mLayerRank);

        task2.moveToBack("test", null /* task */);
        waitHandlerIdle(mWm.mH);

        assertEquals(1, task1.mLayerRank);
        assertEquals(2, task2.mLayerRank);
    }

    @Test
    public void testForceStopPackage() {
        final Task task = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopMostActivity();
        final WindowProcessController wpc = activity.app;
        final ActivityRecord[] activities = {
                activity,
                new ActivityBuilder(mWm.mAtmService).setTask(task).setUseProcess(wpc).build(),
                new ActivityBuilder(mWm.mAtmService).setTask(task).setUseProcess(wpc).build()
        };
        activities[0].detachFromProcess();
        activities[1].finishing = true;
        activities[1].destroyImmediately("test");
        spyOn(wpc);
        doReturn(true).when(wpc).isRemoved();

        mWm.mAtmService.mInternal.onForceStopPackage(wpc.mInfo.packageName, true /* doit */,
                false /* evenPersistent */, wpc.mUserId);
        // The activity without process should be removed.
        assertEquals(2, task.getChildCount());

        wpc.handleAppDied();
        // The activities with process should be removed because WindowProcessController#isRemoved.
        assertFalse(task.hasChild());
        assertFalse(wpc.hasActivities());
    }
}

