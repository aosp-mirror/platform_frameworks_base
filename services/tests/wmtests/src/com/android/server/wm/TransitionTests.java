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
import static android.window.TransitionInfo.TRANSIT_HIDE;
import static android.window.TransitionInfo.TRANSIT_OPEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;
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
        final ActivityRecord closing = createActivityRecord(oldTask);
        final ActivityRecord opening = createActivityRecord(newTask);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = new ArrayMap<>();
        ArraySet<WindowContainer> participants = new ArraySet();
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        // End states.
        closing.mVisibleRequested = false;
        opening.mVisibleRequested = true;

        int transitType = TRANSIT_OLD_TASK_OPEN;

        // Check basic both tasks participating
        participants.add(oldTask);
        participants.add(newTask);
        TransitionInfo info =
                Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(2, info.getChanges().size());
        assertEquals(transitType, info.getType());

        // Check that children are pruned
        participants.add(opening);
        participants.add(closing);
        info = Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check combined prune and promote
        participants.remove(newTask);
        info = Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check multi promote
        participants.remove(oldTask);
        info = Transition.calculateTransitionInfo(transitType, participants, changes);
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
        final ActivityRecord closing = createActivityRecord(oldTask);
        final ActivityRecord opening = createActivityRecord(newNestedTask);
        final ActivityRecord opening2 = createActivityRecord(newNestedTask2);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = new ArrayMap<>();
        ArraySet<WindowContainer> participants = new ArraySet();
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(newNestedTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(newNestedTask2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(opening2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        // End states.
        closing.mVisibleRequested = false;
        opening.mVisibleRequested = true;
        opening2.mVisibleRequested = true;

        int transitType = TRANSIT_OLD_TASK_OPEN;

        // Check full promotion from leaf
        participants.add(oldTask);
        participants.add(opening);
        participants.add(opening2);
        TransitionInfo info =
                Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(2, info.getChanges().size());
        assertEquals(transitType, info.getType());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check that unchanging but visible descendant of sibling prevents promotion
        participants.remove(opening2);
        info = Transition.calculateTransitionInfo(transitType, participants, changes);
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
        final ActivityRecord showing = createActivityRecord(showNestedTask);
        final ActivityRecord showing2 = createActivityRecord(showTask2);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = new ArrayMap<>();
        ArraySet<WindowContainer> participants = new ArraySet();
        // Start states.
        changes.put(showTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showNestedTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showTask2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(tda, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showing, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showing2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        // End states.
        showing.mVisibleRequested = true;
        showing2.mVisibleRequested = true;

        int transitType = TRANSIT_OLD_TASK_OPEN;

        // Check promotion to DisplayArea
        participants.add(showing);
        participants.add(showing2);
        TransitionInfo info =
                Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(1, info.getChanges().size());
        assertEquals(transitType, info.getType());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));

        ITaskOrganizer mockOrg = mock(ITaskOrganizer.class);
        // Check that organized tasks get reported even if not top
        showTask.mTaskOrganizer = mockOrg;
        info = Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(showTask.mRemoteToken.toWindowContainerToken()));
        // Even if DisplayArea explicitly participating
        participants.add(tda);
        info = Transition.calculateTransitionInfo(transitType, participants, changes);
        assertEquals(2, info.getChanges().size());
    }

    @Test
    public void testCreateInfo_existenceChange() {
        TransitionController controller = mock(TransitionController.class);
        BLASTSyncEngine sync = new BLASTSyncEngine(mWm);
        Transition transition = new Transition(
                TRANSIT_OLD_TASK_OPEN, 0 /* flags */, controller, sync);

        final Task openTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final ActivityRecord opening = createActivityRecord(openTask);
        opening.mVisibleRequested = false; // starts invisible
        final Task closeTask = createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        final ActivityRecord closing = createActivityRecord(closeTask);
        closing.mVisibleRequested = true; // starts visible

        transition.collectExistenceChange(openTask);
        transition.collect(opening);
        transition.collect(closing);
        opening.mVisibleRequested = true;
        closing.mVisibleRequested = false;

        TransitionInfo info = Transition.calculateTransitionInfo(
                0, transition.mParticipants, transition.mChanges);
        assertEquals(2, info.getChanges().size());
        // There was an existence change on open, so it should be OPEN rather than SHOW
        assertEquals(TRANSIT_OPEN,
                info.getChange(openTask.mRemoteToken.toWindowContainerToken()).getMode());
        // No exestence change on closing, so HIDE rather than CLOSE
        assertEquals(TRANSIT_HIDE,
                info.getChange(closeTask.mRemoteToken.toWindowContainerToken()).getMode());
    }
}
