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
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.isIndependent;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.os.IBinder;
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

    private Transition createTestTransition(int transitType) {
        TransitionController controller = mock(TransitionController.class);
        BLASTSyncEngine sync = new BLASTSyncEngine(mWm);
        return new Transition(transitType, 0 /* flags */, controller, sync);
    }

    @Test
    public void testCreateInfo_NewTask() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task newTask = createTask(mDisplayContent);
        final Task oldTask = createTask(mDisplayContent);
        final ActivityRecord closing = createActivityRecord(oldTask);
        final ActivityRecord opening = createActivityRecord(newTask);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        fillChangeMap(changes, newTask);
        // End states.
        closing.mVisibleRequested = false;
        opening.mVisibleRequested = true;

        int transit = TRANSIT_OLD_TASK_OPEN;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(oldTask);
        participants.add(newTask);
        ArraySet<WindowContainer> targets = Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        // Check that children are pruned
        participants.add(opening);
        participants.add(closing);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check combined prune and promote
        participants.remove(newTask);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check multi promote
        participants.remove(oldTask);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testCreateInfo_NestedTasks() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task newTask = createTask(mDisplayContent);
        final Task newNestedTask = createTaskInRootTask(newTask, 0);
        final Task newNestedTask2 = createTaskInRootTask(newTask, 0);
        final Task oldTask = createTask(mDisplayContent);
        final ActivityRecord closing = createActivityRecord(oldTask);
        final ActivityRecord opening = createActivityRecord(newNestedTask);
        final ActivityRecord opening2 = createActivityRecord(newNestedTask2);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(newNestedTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(newNestedTask2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(opening2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(true /* vis */, true /* exChg */));
        fillChangeMap(changes, newTask);
        // End states.
        closing.mVisibleRequested = false;
        opening.mVisibleRequested = true;
        opening2.mVisibleRequested = true;

        int transit = TRANSIT_OLD_TASK_OPEN;
        int flags = 0;

        // Check full promotion from leaf
        participants.add(oldTask);
        participants.add(opening);
        participants.add(opening2);
        ArraySet<WindowContainer> targets = Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check that unchanging but visible descendant of sibling prevents promotion
        participants.remove(opening2);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newNestedTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testCreateInfo_DisplayArea() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;
        final Task showTask = createTask(mDisplayContent);
        final Task showNestedTask = createTaskInRootTask(showTask, 0);
        final Task showTask2 = createTask(mDisplayContent);
        final DisplayArea tda = showTask.getDisplayArea();
        final ActivityRecord showing = createActivityRecord(showNestedTask);
        final ActivityRecord showing2 = createActivityRecord(showTask2);
        // Start states.
        changes.put(showTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showNestedTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showTask2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(tda, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showing, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(showing2, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        fillChangeMap(changes, tda);

        // End states.
        showing.mVisibleRequested = true;
        showing2.mVisibleRequested = true;

        int transit = TRANSIT_OLD_TASK_OPEN;
        int flags = 0;

        // Check promotion to DisplayArea
        participants.add(showing);
        participants.add(showing2);
        ArraySet<WindowContainer> targets = Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(1, info.getChanges().size());
        assertEquals(transit, info.getType());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));

        ITaskOrganizer mockOrg = mock(ITaskOrganizer.class);
        // Check that organized tasks get reported even if not top
        showTask.mTaskOrganizer = mockOrg;
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(showTask.mRemoteToken.toWindowContainerToken()));
        // Even if DisplayArea explicitly participating
        participants.add(tda);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        assertEquals(2, info.getChanges().size());
    }

    @Test
    public void testCreateInfo_existenceChange() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);

        final Task openTask = createTask(mDisplayContent);
        final ActivityRecord opening = createActivityRecord(openTask);
        opening.mVisibleRequested = false; // starts invisible
        final Task closeTask = createTask(mDisplayContent);
        final ActivityRecord closing = createActivityRecord(closeTask);
        closing.mVisibleRequested = true; // starts visible

        transition.collectExistenceChange(openTask);
        transition.collect(opening);
        transition.collect(closing);
        opening.mVisibleRequested = true;
        closing.mVisibleRequested = false;

        ArraySet<WindowContainer> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(
                0, 0, targets, transition.mChanges);
        assertEquals(2, info.getChanges().size());
        // There was an existence change on open, so it should be OPEN rather than SHOW
        assertEquals(TRANSIT_OPEN,
                info.getChange(openTask.mRemoteToken.toWindowContainerToken()).getMode());
        // No exestence change on closing, so HIDE rather than CLOSE
        assertEquals(TRANSIT_TO_BACK,
                info.getChange(closeTask.mRemoteToken.toWindowContainerToken()).getMode());
    }

    @Test
    public void testCreateInfo_ordering() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);
        // pick some number with a high enough chance of being out-of-order when added to set.
        final int taskCount = 6;

        final Task[] tasks = new Task[taskCount];
        for (int i = 0; i < taskCount; ++i) {
            // Each add goes on top, so at the end of this, task[9] should be on top
            tasks[i] = createTask(mDisplayContent,
                    WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
            final ActivityRecord act = createActivityRecord(tasks[i]);
            // alternate so that the transition doesn't get promoted to the display area
            act.mVisibleRequested = (i % 2) == 0; // starts invisible
        }

        // doesn't matter which order collected since participants is a set
        for (int i = 0; i < taskCount; ++i) {
            transition.collectExistenceChange(tasks[i]);
            final ActivityRecord act = tasks[i].getTopMostActivity();
            transition.collect(act);
            tasks[i].getTopMostActivity().mVisibleRequested = (i % 2) != 0;
        }

        ArraySet<WindowContainer> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(
                0, 0, targets, transition.mChanges);
        assertEquals(taskCount, info.getChanges().size());
        // verify order is top-to-bottem
        for (int i = 0; i < taskCount; ++i) {
            assertEquals(tasks[taskCount - i - 1].mRemoteToken.toWindowContainerToken(),
                    info.getChanges().get(i).getContainer());
        }
    }

    @Test
    public void testCreateInfo_wallpaper() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);
        // pick some number with a high enough chance of being out-of-order when added to set.
        final int taskCount = 4;
        final int showWallpaperTask = 2;

        final Task[] tasks = new Task[taskCount];
        for (int i = 0; i < taskCount; ++i) {
            // Each add goes on top, so at the end of this, task[9] should be on top
            tasks[i] = createTask(mDisplayContent,
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
            final ActivityRecord act = createActivityRecord(tasks[i]);
            // alternate so that the transition doesn't get promoted to the display area
            act.mVisibleRequested = (i % 2) == 0; // starts invisible
            if (i == showWallpaperTask) {
                doReturn(true).when(act).showWallpaper();
            }
        }

        final WallpaperWindowToken wallpaperWindowToken = spy(new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDisplayContent, true /* ownerCanManageAppTokens */));
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
        wallpaperWindowToken.setVisibleRequested(false);
        transition.collect(wallpaperWindowToken);
        wallpaperWindowToken.setVisibleRequested(true);
        wallpaperWindow.mHasSurface = true;

        // doesn't matter which order collected since participants is a set
        for (int i = 0; i < taskCount; ++i) {
            transition.collectExistenceChange(tasks[i]);
            final ActivityRecord act = tasks[i].getTopMostActivity();
            transition.collect(act);
            tasks[i].getTopMostActivity().mVisibleRequested = (i % 2) != 0;
        }

        ArraySet<WindowContainer> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(
                0, 0, targets, transition.mChanges);
        // verify that wallpaper is at bottom
        assertEquals(taskCount + 1, info.getChanges().size());
        // The wallpaper is not organized, so it won't have a token; however, it will be marked
        // as IS_WALLPAPER
        assertEquals(FLAG_IS_WALLPAPER,
                info.getChanges().get(info.getChanges().size() - 1).getFlags());
        assertEquals(FLAG_SHOW_WALLPAPER, info.getChange(
                tasks[showWallpaperTask].mRemoteToken.toWindowContainerToken()).getFlags());
    }

    @Test
    public void testTargets_noIntermediatesToWallpaper() {
        final Transition transition = createTestTransition(TRANSIT_OLD_TASK_OPEN);

        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDisplayContent, true /* ownerCanManageAppTokens */);
        // Make DA organized so we can check that they don't get included.
        WindowContainer parent = wallpaperWindowToken.getParent();
        while (parent != null && parent != mDisplayContent) {
            if (parent.asDisplayArea() != null) {
                parent.asDisplayArea().setOrganizer(
                        mock(android.window.IDisplayAreaOrganizer.class), true /* skipAppear */);
            }
            parent = parent.getParent();
        }
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
        wallpaperWindowToken.setVisibleRequested(false);
        transition.collect(wallpaperWindowToken);
        wallpaperWindowToken.setVisibleRequested(true);
        wallpaperWindow.mHasSurface = true;
        doReturn(true).when(mDisplayContent).isAttached();
        transition.collect(mDisplayContent);
        mDisplayContent.getWindowConfiguration().setRotation(
                (mDisplayContent.getWindowConfiguration().getRotation() + 1) % 4);

        ArraySet<WindowContainer> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(
                0, 0, targets, transition.mChanges);
        // The wallpaper is not organized, so it won't have a token; however, it will be marked
        // as IS_WALLPAPER
        assertEquals(FLAG_IS_WALLPAPER, info.getChanges().get(0).getFlags());
        // Make sure no intermediate display areas were pulled in between wallpaper and display.
        assertEquals(mDisplayContent.mRemoteToken.toWindowContainerToken(),
                info.getChanges().get(0).getParent());
    }

    @Test
    public void testIndependent() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;
        ITaskOrganizer mockOrg = mock(ITaskOrganizer.class);

        final Task openTask = createTask(mDisplayContent);
        final Task openInOpenTask = createTaskInRootTask(openTask, 0);
        final ActivityRecord openInOpen = createActivityRecord(openInOpenTask);

        final Task changeTask = createTask(mDisplayContent);
        final Task changeInChangeTask = createTaskInRootTask(changeTask, 0);
        final Task openInChangeTask = createTaskInRootTask(changeTask, 0);
        final ActivityRecord changeInChange = createActivityRecord(changeInChangeTask);
        final ActivityRecord openInChange = createActivityRecord(openInChangeTask);
        // set organizer for everything so that they all get added to transition info
        for (Task t : new Task[]{
                openTask, openInOpenTask, changeTask, changeInChangeTask, openInChangeTask}) {
            t.mTaskOrganizer = mockOrg;
        }

        // Start states.
        changes.put(openTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(changeTask, new Transition.ChangeInfo(true /* vis */, false /* exChg */));
        changes.put(openInOpenTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(openInChangeTask, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(changeInChangeTask,
                new Transition.ChangeInfo(true /* vis */, false /* exChg */));
        changes.put(openInOpen, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(openInChange, new Transition.ChangeInfo(false /* vis */, true /* exChg */));
        changes.put(changeInChange, new Transition.ChangeInfo(true /* vis */, false /* exChg */));
        fillChangeMap(changes, openTask);
        // End states.
        changeInChange.mVisibleRequested = true;
        openInOpen.mVisibleRequested = true;
        openInChange.mVisibleRequested = true;

        int transit = TRANSIT_OLD_TASK_OPEN;
        int flags = 0;

        // Check full promotion from leaf
        participants.add(changeInChange);
        participants.add(openInOpen);
        participants.add(openInChange);
        // Explicitly add changeTask (to test independence with parents)
        participants.add(changeTask);
        ArraySet<WindowContainer> targets = Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, changes);
        // Root changes should always be considered independent
        assertTrue(isIndependent(
                info.getChange(openTask.mRemoteToken.toWindowContainerToken()), info));
        assertTrue(isIndependent(
                info.getChange(changeTask.mRemoteToken.toWindowContainerToken()), info));

        // Children of a open/close change are not independent
        assertFalse(isIndependent(
                info.getChange(openInOpenTask.mRemoteToken.toWindowContainerToken()), info));

        // Non-root changes are not independent
        assertFalse(isIndependent(
                info.getChange(changeInChangeTask.mRemoteToken.toWindowContainerToken()), info));

        // open/close within a change are independent
        assertTrue(isIndependent(
                info.getChange(openInChangeTask.mRemoteToken.toWindowContainerToken()), info));
    }

    /** Fill the change map with all the parents of top. Change maps are usually fully populated */
    private static void fillChangeMap(ArrayMap<WindowContainer, Transition.ChangeInfo> changes,
            WindowContainer top) {
        for (WindowContainer curr = top.getParent(); curr != null; curr = curr.getParent()) {
            changes.put(curr, new Transition.ChangeInfo(true /* vis */, false /* exChg */));
        }
    }
}
