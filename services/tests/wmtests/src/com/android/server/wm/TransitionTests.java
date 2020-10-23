/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.window.ITaskOrganizer;
import android.window.TransitionInfo;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:TransitionRecordTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TransitionTests extends WindowTestsBase {

    @Test
    public void testCreateInfo_NewTask() {
        final Task newTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task oldTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        newTask.setHasBeenVisible(true);
        oldTask.setHasBeenVisible(false);
        final ActivityRecord closing = createActivityRecordInTask(oldTask);
        final ActivityRecord opening = createActivityRecordInTask(newTask);
        closing.setVisible(true);
        closing.mVisibleRequested = false;
        opening.setVisible(false);
        opening.mVisibleRequested = true;
        ArrayMap<WindowContainer, Transition.ChangeInfo> participants = new ArrayMap<>();

        int transitType = TRANSIT_OLD_TASK_OPEN;

        // Check basic both tasks participating
        participants.put(oldTask, new Transition.ChangeInfo());
        participants.put(newTask, new Transition.ChangeInfo());
        TransitionInfo info =
                Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertEquals(transitType, info.getType());

        // Check that children are pruned
        participants.put(opening, new Transition.ChangeInfo());
        participants.put(closing, new Transition.ChangeInfo());
        info = Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check combined prune and promote
        participants.remove(newTask);
        info = Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check multi promote
        participants.remove(oldTask);
        info = Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testCreateInfo_NestedTasks() {
        final Task newTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task newNestedTask = createTaskInStack(newTask, 0);
        final Task newNestedTask2 = createTaskInStack(newTask, 0);
        final Task oldTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        newTask.setHasBeenVisible(true);
        oldTask.setHasBeenVisible(false);
        final ActivityRecord closing = createActivityRecordInTask(oldTask);
        final ActivityRecord opening = createActivityRecordInTask(newNestedTask);
        final ActivityRecord opening2 = createActivityRecordInTask(newNestedTask2);
        closing.setVisible(true);
        closing.mVisibleRequested = false;
        opening.setVisible(false);
        opening.mVisibleRequested = true;
        opening2.setVisible(false);
        opening2.mVisibleRequested = true;
        ArrayMap<WindowContainer, Transition.ChangeInfo> participants = new ArrayMap<>();

        int transitType = TRANSIT_OLD_TASK_OPEN;

        // Check full promotion from leaf
        participants.put(oldTask, new Transition.ChangeInfo());
        participants.put(opening, new Transition.ChangeInfo());
        participants.put(opening2, new Transition.ChangeInfo());
        TransitionInfo info =
                Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertEquals(transitType, info.getType());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check that unchanging but visible descendant of sibling prevents promotion
        participants.remove(opening2);
        info = Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newNestedTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testCreateInfo_DisplayArea() {
        final Task showTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final Task showNestedTask = createTaskInStack(showTask, 0);
        final Task showTask2 = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final DisplayArea tda = showTask.getDisplayArea();
        showTask.setHasBeenVisible(true);
        showTask2.setHasBeenVisible(true);
        final ActivityRecord showing = createActivityRecordInTask(showNestedTask);
        final ActivityRecord showing2 = createActivityRecordInTask(showTask2);
        showing.setVisible(false);
        showing.mVisibleRequested = true;
        showing2.setVisible(false);
        showing2.mVisibleRequested = true;
        ArrayMap<WindowContainer, Transition.ChangeInfo> participants = new ArrayMap<>();

        int transitType = TRANSIT_OLD_TASK_OPEN;

        // Check promotion to DisplayArea
        participants.put(showing, new Transition.ChangeInfo());
        participants.put(showing2, new Transition.ChangeInfo());
        TransitionInfo info =
                Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(1, info.getChanges().size());
        assertEquals(transitType, info.getType());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));

        ITaskOrganizer mockOrg = mock(ITaskOrganizer.class);
        // Check that organized tasks get reported even if not top
        showTask.mTaskOrganizer = mockOrg;
        info = Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(showTask.mRemoteToken.toWindowContainerToken()));
        // Even if DisplayArea explicitly participating
        participants.put(tda, new Transition.ChangeInfo());
        info = Transition.calculateTransitionInfo(transitType, participants);
        assertEquals(2, info.getChanges().size());
    }
}
