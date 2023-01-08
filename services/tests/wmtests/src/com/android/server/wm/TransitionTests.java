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
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.window.TransitionInfo.FLAG_FILLS_TASK;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_BEHIND_STARTING_WINDOW;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;
import static android.window.TransitionInfo.isIndependent;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.SurfaceControl;
import android.window.IDisplayAreaOrganizer;
import android.window.IRemoteTransition;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskOrganizer;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TaskFragmentOrganizer;
import android.window.TransitionInfo;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.internal.graphics.ColorUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Build/Install/Run:
 *  atest WmTests:TransitionTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class TransitionTests extends WindowTestsBase {
    final SurfaceControl.Transaction mMockT = mock(SurfaceControl.Transaction.class);
    private BLASTSyncEngine mSyncEngine;

    private Transition createTestTransition(int transitType) {
        TransitionTracer tracer = mock(TransitionTracer.class);
        final TransitionController controller = new TransitionController(
                mock(ActivityTaskManagerService.class), mock(TaskSnapshotController.class),
                mock(TransitionTracer.class));

        mSyncEngine = createTestBLASTSyncEngine();
        final Transition t = new Transition(transitType, 0 /* flags */, controller, mSyncEngine);
        t.startCollecting(0 /* timeoutMs */);
        return t;
    }

    @Test
    public void testCreateInfo_NewTask() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task newTask = createTask(mDisplayContent);
        final Task oldTask = createTask(mDisplayContent);
        final ActivityRecord closing = createActivityRecord(oldTask);
        final ActivityRecord opening = createActivityRecord(newTask);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(newTask, false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(oldTask, true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, true /* exChg */));
        fillChangeMap(changes, newTask);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(oldTask);
        participants.add(newTask);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        // Check that children are pruned
        participants.add(opening);
        participants.add(closing);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check combined prune and promote
        participants.remove(newTask);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check multi promote
        participants.remove(oldTask);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testCreateInfo_NestedTasks() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
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
        changes.put(newTask, new Transition.ChangeInfo(newTask, false /* vis */, true /* exChg */));
        changes.put(newNestedTask,
                new Transition.ChangeInfo(newNestedTask, false /* vis */, true /* exChg */));
        changes.put(newNestedTask2,
                new Transition.ChangeInfo(newNestedTask2, false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(oldTask, true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(opening2,
                new Transition.ChangeInfo(opening2, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, true /* exChg */));
        fillChangeMap(changes, newTask);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);
        opening2.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check full promotion from leaf
        participants.add(oldTask);
        participants.add(opening);
        participants.add(opening2);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));

        // Check that unchanging but visible descendant of sibling prevents promotion
        participants.remove(opening2);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(newNestedTask.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(oldTask.mRemoteToken.toWindowContainerToken()));
    }

    @Test
    public void testCreateInfo_DisplayArea() {
        assumeTrue(mDisplayContent.mTransitionController.useShellTransitionsRotation());

        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;
        final Task showTask = createTask(mDisplayContent);
        final Task showNestedTask = createTaskInRootTask(showTask, 0);
        final Task showTask2 = createTask(mDisplayContent);
        final DisplayArea tda = showTask.getDisplayArea();
        final ActivityRecord showing = createActivityRecord(showNestedTask);
        final ActivityRecord showing2 = createActivityRecord(showTask2);
        // Start states.
        changes.put(showTask,
                new Transition.ChangeInfo(showTask, false /* vis */, true /* exChg */));
        changes.put(showNestedTask,
                new Transition.ChangeInfo(showNestedTask, false /* vis */, true /* exChg */));
        changes.put(showTask2,
                new Transition.ChangeInfo(showTask2, false /* vis */, true /* exChg */));
        changes.put(tda, new Transition.ChangeInfo(tda, false /* vis */, true /* exChg */));
        changes.put(showing, new Transition.ChangeInfo(showing, false /* vis */, true /* exChg */));
        changes.put(showing2,
                new Transition.ChangeInfo(showing2, false /* vis */, true /* exChg */));
        fillChangeMap(changes, tda);

        // End states.
        showing.setVisibleRequested(true);
        showing2.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check promotion to DisplayArea
        participants.add(showing);
        participants.add(showing2);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(1, info.getChanges().size());
        assertEquals(transit, info.getType());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));

        // Check that organized tasks get reported even if not top
        makeTaskOrganized(showTask);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertNotNull(info.getChange(tda.mRemoteToken.toWindowContainerToken()));
        assertNotNull(info.getChange(showTask.mRemoteToken.toWindowContainerToken()));
        // Even if DisplayArea explicitly participating
        participants.add(tda);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
    }

    @Test
    public void testCreateInfo_existenceChange() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);

        final Task openTask = createTask(mDisplayContent);
        final ActivityRecord opening = createActivityRecord(openTask);
        opening.setVisibleRequested(false); // starts invisible
        final Task closeTask = createTask(mDisplayContent);
        final ActivityRecord closing = createActivityRecord(closeTask);
        closing.setVisibleRequested(true); // starts visible

        transition.collectExistenceChange(openTask);
        transition.collect(opening);
        transition.collect(closing);
        opening.setVisibleRequested(true);
        closing.setVisibleRequested(false);

        ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(0, 0, targets, mMockT);
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
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        // pick some number with a high enough chance of being out-of-order when added to set.
        final int taskCount = 6;

        final Task[] tasks = new Task[taskCount];
        for (int i = 0; i < taskCount; ++i) {
            // Each add goes on top, so at the end of this, task[9] should be on top
            tasks[i] = createTask(mDisplayContent,
                    WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
            final ActivityRecord act = createActivityRecord(tasks[i]);
            // alternate so that the transition doesn't get promoted to the display area
            act.setVisibleRequested((i % 2) == 0); // starts invisible
        }

        // doesn't matter which order collected since participants is a set
        for (int i = 0; i < taskCount; ++i) {
            transition.collectExistenceChange(tasks[i]);
            final ActivityRecord act = tasks[i].getTopMostActivity();
            transition.collect(act);
            tasks[i].getTopMostActivity().setVisibleRequested((i % 2) != 0);
        }

        ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(0, 0, targets, mMockT);
        assertEquals(taskCount, info.getChanges().size());
        // verify order is top-to-bottem
        for (int i = 0; i < taskCount; ++i) {
            assertEquals(tasks[taskCount - i - 1].mRemoteToken.toWindowContainerToken(),
                    info.getChanges().get(i).getContainer());
        }
    }

    @Test
    public void testCreateInfo_wallpaper() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
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
            act.setVisibleRequested((i % 2) == 0); // starts invisible
            act.visibleIgnoringKeyguard = (i % 2) == 0;
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
            tasks[i].getTopMostActivity().setVisibleRequested((i % 2) != 0);
        }

        ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(0, 0, targets, mMockT);
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
    public void testCreateInfo_PromoteSimilarClose() {
        final Transition transition = createTestTransition(TRANSIT_CLOSE);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task topTask = createTask(mDisplayContent);
        final Task belowTask = createTask(mDisplayContent);
        final ActivityRecord showing = createActivityRecord(belowTask);
        final ActivityRecord hiding = createActivityRecord(topTask);
        final ActivityRecord closing = createActivityRecord(topTask);
        // Start states.
        changes.put(topTask, new Transition.ChangeInfo(topTask, true /* vis */, false /* exChg */));
        changes.put(belowTask,
                new Transition.ChangeInfo(belowTask, false /* vis */, false /* exChg */));
        changes.put(showing,
                new Transition.ChangeInfo(showing, false /* vis */, false /* exChg */));
        changes.put(hiding, new Transition.ChangeInfo(hiding, true /* vis */, false /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, true /* exChg */));
        fillChangeMap(changes, topTask);
        // End states.
        showing.setVisibleRequested(true);
        closing.setVisibleRequested(false);
        hiding.setVisibleRequested(false);

        participants.add(belowTask);
        participants.add(hiding);
        participants.add(closing);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        assertEquals(2, targets.size());
        assertTrue(Transition.containsChangeFor(belowTask, targets));
        assertTrue(Transition.containsChangeFor(topTask, targets));
    }

    @Test
    public void testCreateInfo_PromoteSimilarOpen() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task topTask = createTask(mDisplayContent);
        final Task belowTask = createTask(mDisplayContent);
        final ActivityRecord showing = createActivityRecord(topTask);
        final ActivityRecord opening = createActivityRecord(topTask);
        final ActivityRecord closing = createActivityRecord(belowTask);
        // Start states.
        changes.put(topTask,
                new Transition.ChangeInfo(topTask, false /* vis */, false /* exChg */));
        changes.put(belowTask,
                new Transition.ChangeInfo(belowTask, true /* vis */, false /* exChg */));
        changes.put(showing,
                new Transition.ChangeInfo(showing, false /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, topTask);
        // End states.
        showing.setVisibleRequested(true);
        opening.setVisibleRequested(true);
        closing.setVisibleRequested(false);

        participants.add(belowTask);
        participants.add(showing);
        participants.add(opening);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        assertEquals(2, targets.size());
        assertTrue(Transition.containsChangeFor(belowTask, targets));
        assertTrue(Transition.containsChangeFor(topTask, targets));
    }

    @Test
    public void testCreateInfo_NoAnimation() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task newTask = createTask(mDisplayContent);
        final Task oldTask = createTask(mDisplayContent);
        final ActivityRecord closing = createActivityRecord(oldTask);
        final ActivityRecord opening = createActivityRecord(newTask);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(newTask, false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(oldTask, true /* vis */, true /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, true /* exChg */));
        transition.setNoAnimation(opening);
        fillChangeMap(changes, newTask);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check that no-animation flag is promoted
        participants.add(oldTask);
        participants.add(newTask);
        participants.add(opening);
        participants.add(closing);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertTrue(info.getChange(newTask.mRemoteToken.toWindowContainerToken())
                .hasFlags(TransitionInfo.FLAG_NO_ANIMATION));

        // Check that no-animation flag is NOT promoted if at-least on child *is* animated
        final ActivityRecord opening2 = createActivityRecord(newTask);
        changes.put(opening2,
                new Transition.ChangeInfo(opening2, false /* vis */, true /* exChg */));
        participants.add(opening2);
        targets = Transition.calculateTargets(participants, changes);
        info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertNotNull(info.getChange(newTask.mRemoteToken.toWindowContainerToken()));
        assertFalse(info.getChange(newTask.mRemoteToken.toWindowContainerToken())
                .hasFlags(TransitionInfo.FLAG_NO_ANIMATION));
    }

    @Test
    public void testTargets_noIntermediatesToWallpaper() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);

        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDisplayContent, true /* ownerCanManageAppTokens */);
        // Make DA organized so we can check that they don't get included.
        WindowContainer parent = wallpaperWindowToken.getParent();
        makeDisplayAreaOrganized(parent, mDisplayContent);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");
        wallpaperWindowToken.setVisibleRequested(false);
        transition.collect(wallpaperWindowToken);
        wallpaperWindowToken.setVisibleRequested(true);
        wallpaperWindow.mHasSurface = true;
        doReturn(true).when(mDisplayContent).isAttached();
        transition.collect(mDisplayContent);
        assertFalse("The change of non-interesting window container should be skipped",
                transition.mChanges.containsKey(mDisplayContent.getParent()));
        mDisplayContent.getWindowConfiguration().setRotation(
                (mDisplayContent.getWindowConfiguration().getRotation() + 1) % 4);

        ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(0, 0, targets, mMockT);
        // The wallpaper is not organized, so it won't have a token; however, it will be marked
        // as IS_WALLPAPER
        assertEquals(FLAG_IS_WALLPAPER, info.getChanges().get(0).getFlags());
        // Make sure no intermediate display areas were pulled in between wallpaper and display.
        assertEquals(mDisplayContent.mRemoteToken.toWindowContainerToken(),
                info.getChanges().get(0).getParent());
    }

    @Test
    public void testRunningRemoteTransition() {
        final TestTransitionPlayer testPlayer = new TestTransitionPlayer(
                mAtm.getTransitionController(), mAtm.mWindowOrganizerController);
        final WindowProcessController playerProc = mSystemServicesTestRule.addProcess(
                "pkg.player", "proc.player", 5000 /* pid */, 5000 /* uid */);
        testPlayer.mController.registerTransitionPlayer(testPlayer, playerProc);
        doReturn(mock(IBinder.class)).when(playerProc.getThread()).asBinder();
        final WindowProcessController delegateProc = mSystemServicesTestRule.addProcess(
                "pkg.delegate", "proc.delegate", 6000 /* pid */, 6000 /* uid */);
        doReturn(mock(IBinder.class)).when(delegateProc.getThread()).asBinder();
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true)
                .setVisible(false).build();
        app.setVisibleRequested(true);
        final TransitionController controller = app.mTransitionController;
        final Transition transition = controller.createTransition(TRANSIT_OPEN);
        final RemoteTransition remoteTransition = new RemoteTransition(
                mock(IRemoteTransition.class));
        remoteTransition.setAppThread(delegateProc.getThread());
        transition.collect(app);
        controller.requestStartTransition(transition, null /* startTask */, remoteTransition,
                null /* displayChange */);
        testPlayer.startTransition();
        testPlayer.onTransactionReady(app.getSyncTransaction());
        assertTrue(playerProc.isRunningRemoteTransition());
        assertTrue(delegateProc.isRunningRemoteTransition());
        assertTrue(controller.mRemotePlayer.reportRunning(delegateProc.getThread()));
        assertTrue(app.isVisible());

        testPlayer.finish();
        assertFalse(playerProc.isRunningRemoteTransition());
        assertFalse(delegateProc.isRunningRemoteTransition());
        assertFalse(controller.mRemotePlayer.reportRunning(delegateProc.getThread()));
    }

    @Test
    public void testOpenActivityInTheSameTaskWithDisplayChange() {
        final ActivityRecord closing = createActivityRecord(mDisplayContent);
        closing.setVisibleRequested(true);
        final Task task = closing.getTask();
        makeTaskOrganized(task);
        final ActivityRecord opening = createActivityRecord(task);
        opening.setVisibleRequested(false);
        makeDisplayAreaOrganized(mDisplayContent.getDefaultTaskDisplayArea(), mDisplayContent);
        final WindowContainer<?>[] wcs = { closing, opening, task, mDisplayContent };
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        for (WindowContainer<?> wc : wcs) {
            transition.collect(wc);
        }
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);
        final int newRotation = mDisplayContent.getWindowConfiguration().getRotation() + 1;
        for (WindowContainer<?> wc : wcs) {
            wc.getWindowConfiguration().setRotation(newRotation);
        }

        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        // Especially the activities must be in the targets.
        for (WindowContainer<?> wc : wcs) {
            assertTrue(Transition.containsChangeFor(wc, targets));
        }
    }

    @Test
    public void testIndependent() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task openTask = createTask(mDisplayContent);
        final Task openInOpenTask = createTaskInRootTask(openTask, 0);
        final ActivityRecord openInOpen = createActivityRecord(openInOpenTask);

        final Task changeTask = createTask(mDisplayContent);
        final Task changeInChangeTask = createTaskInRootTask(changeTask, 0);
        final Task openInChangeTask = createTaskInRootTask(changeTask, 0);
        final ActivityRecord changeInChange = createActivityRecord(changeInChangeTask);
        final ActivityRecord openInChange = createActivityRecord(openInChangeTask);
        // set organizer for everything so that they all get added to transition info
        makeTaskOrganized(openTask, openInOpenTask, changeTask, changeInChangeTask,
                openInChangeTask);

        // Start states.
        changes.put(openTask,
                new Transition.ChangeInfo(openTask, false /* vis */, true /* exChg */));
        changes.put(changeTask,
                new Transition.ChangeInfo(changeTask, true /* vis */, false /* exChg */));
        changes.put(openInOpenTask,
                new Transition.ChangeInfo(openInOpenTask, false /* vis */, true /* exChg */));
        changes.put(openInChangeTask,
                new Transition.ChangeInfo(openInChangeTask, false /* vis */, true /* exChg */));
        changes.put(changeInChangeTask,
                new Transition.ChangeInfo(changeInChangeTask, true /* vis */, false /* exChg */));
        changes.put(openInOpen,
                new Transition.ChangeInfo(openInOpen, false /* vis */, true /* exChg */));
        changes.put(openInChange,
                new Transition.ChangeInfo(openInChange, false /* vis */, true /* exChg */));
        changes.put(changeInChange,
                new Transition.ChangeInfo(changeInChange, true /* vis */, false /* exChg */));
        fillChangeMap(changes, openTask);
        // End states.
        changeInChange.setVisibleRequested(true);
        openInOpen.setVisibleRequested(true);
        openInChange.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check full promotion from leaf
        participants.add(changeInChange);
        participants.add(openInOpen);
        participants.add(openInChange);
        // Explicitly add changeTask (to test independence with parents)
        participants.add(changeTask);
        final ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
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

    @Test
    public void testOpenOpaqueTask() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task newTask = createTask(mDisplayContent);
        doReturn(false).when(newTask).isTranslucent(any());
        final Task oldTask = createTask(mDisplayContent);
        doReturn(false).when(oldTask).isTranslucent(any());

        final ActivityRecord closing = createActivityRecord(oldTask);
        closing.setOccludesParent(true);
        final ActivityRecord opening = createActivityRecord(newTask);
        opening.setOccludesParent(false);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(newTask, false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(oldTask, true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, newTask);
        // End states.
        closing.setVisibleRequested(true);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(oldTask);
        participants.add(newTask);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        assertTrue((info.getChanges().get(0).getFlags() & FLAG_TRANSLUCENT) == 0);
        assertTrue((info.getChanges().get(1).getFlags() & FLAG_TRANSLUCENT) == 0);
    }

    @Test
    public void testOpenTranslucentTask() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task newTask = createTask(mDisplayContent);
        doReturn(true).when(newTask).isTranslucent(any());
        final Task oldTask = createTask(mDisplayContent);
        doReturn(false).when(oldTask).isTranslucent(any());

        final ActivityRecord closing = createActivityRecord(oldTask);
        closing.setOccludesParent(true);
        final ActivityRecord opening = createActivityRecord(newTask);
        opening.setOccludesParent(false);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(newTask, false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(oldTask, true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, newTask);
        // End states.
        closing.setVisibleRequested(true);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(oldTask);
        participants.add(newTask);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        assertTrue((info.getChanges().get(0).getFlags() & FLAG_TRANSLUCENT) != 0);
        assertTrue((info.getChanges().get(1).getFlags() & FLAG_TRANSLUCENT) == 0);
    }

    @Test
    public void testTimeout() {
        final TransitionController controller = new TransitionController(mAtm,
                mock(TaskSnapshotController.class), mock(TransitionTracer.class));
        final BLASTSyncEngine sync = new BLASTSyncEngine(mWm);
        final CountDownLatch latch = new CountDownLatch(1);
        // When the timeout is reached, it will finish the sync-group and notify transaction ready.
        final Transition t = new Transition(TRANSIT_OPEN, 0 /* flags */, controller, sync) {
            @Override
            public void onTransactionReady(int syncId, SurfaceControl.Transaction transaction) {
                latch.countDown();
            }
        };
        t.startCollecting(10 /* timeoutMs */);
        assertTrue(awaitInWmLock(() -> latch.await(3, TimeUnit.SECONDS)));
    }

    @Test
    public void testTransitionBounds() {
        registerTestTransitionPlayer();
        final int offset = 10;
        final Function<WindowContainer<?>, TransitionInfo.Change> test = wc -> {
            final Transition transition = wc.mTransitionController.createTransition(TRANSIT_OPEN);
            transition.collect(wc);
            final int nextRotation = (wc.getWindowConfiguration().getRotation() + 1) % 4;
            wc.getWindowConfiguration().setRotation(nextRotation);
            wc.getWindowConfiguration().setDisplayRotation(nextRotation);
            final Rect bounds = wc.getWindowConfiguration().getBounds();
            // Flip the bounds with offset.
            wc.getWindowConfiguration().setBounds(
                    new Rect(offset, offset, bounds.height(), bounds.width()));
            final int flags = 0;
            final TransitionInfo info = Transition.calculateTransitionInfo(transition.mType, flags,
                    Transition.calculateTargets(transition.mParticipants, transition.mChanges),
                    mMockT);
            transition.abort();
            return info.getChanges().get(0);
        };

        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final TransitionInfo.Change changeOfActivity = test.apply(app);
        // There will be letterbox if the activity bounds don't match parent, so always use its
        // parent bounds for animation.
        assertEquals(app.getParent().getBounds(), changeOfActivity.getEndAbsBounds());
        final int endRotation = app.mTransitionController.useShellTransitionsRotation()
                ? app.getWindowConfiguration().getRotation()
                // Without shell rotation, fixed rotation is done by core so the info should not
                // contain rotation change.
                : app.getParent().getWindowConfiguration().getRotation();
        assertEquals(endRotation, changeOfActivity.getEndRotation());

        // Non-activity target always uses its configuration for end info.
        final Task task = app.getTask();
        final TransitionInfo.Change changeOfTask = test.apply(task);
        assertEquals(task.getBounds(), changeOfTask.getEndAbsBounds());
        assertEquals(new Point(offset, offset), changeOfTask.getEndRelOffset());
        assertEquals(task.getWindowConfiguration().getRotation(), changeOfTask.getEndRotation());
    }

    @Test
    public void testDisplayRotationChange() {
        final Task task = createActivityRecord(mDisplayContent).getTask();
        final WindowState statusBar = createWindow(null, TYPE_STATUS_BAR, "statusBar");
        final WindowState navBar = createWindow(null, TYPE_NAVIGATION_BAR, "navBar");
        final WindowState ime = createWindow(null, TYPE_INPUT_METHOD, "ime");
        final WindowToken decorToken = new WindowToken.Builder(mWm, mock(IBinder.class),
                TYPE_NAVIGATION_BAR_PANEL).setDisplayContent(mDisplayContent)
                .setRoundedCornerOverlay(true).build();
        final WindowState screenDecor =
                createWindow(null, decorToken.windowType, decorToken, "screenDecor");
        final WindowState[] windows = { statusBar, navBar, ime, screenDecor };
        makeWindowVisible(windows);
        mDisplayContent.getDisplayPolicy().addWindowLw(statusBar, statusBar.mAttrs);
        mDisplayContent.getDisplayPolicy().addWindowLw(navBar, navBar.mAttrs);
        final TestTransitionPlayer player = registerTestTransitionPlayer();

        mDisplayContent.getDisplayRotation().setRotation(mDisplayContent.getRotation() + 1);
        mDisplayContent.setLastHasContent();
        mDisplayContent.requestChangeTransitionIfNeeded(1 /* any changes */,
                null /* displayChange */);
        assertEquals(WindowContainer.SYNC_STATE_NONE, statusBar.mSyncState);
        assertEquals(WindowContainer.SYNC_STATE_NONE, navBar.mSyncState);
        assertEquals(WindowContainer.SYNC_STATE_NONE, screenDecor.mSyncState);
        assertEquals(WindowContainer.SYNC_STATE_WAITING_FOR_DRAW, ime.mSyncState);

        final AsyncRotationController asyncRotationController =
                mDisplayContent.getAsyncRotationController();
        assertNotNull(asyncRotationController);
        for (WindowState w : windows) {
            w.setOrientationChanging(true);
        }
        player.startTransition();

        assertFalse(mDisplayContent.mTransitionController.isCollecting(statusBar.mToken));
        assertFalse(mDisplayContent.mTransitionController.isCollecting(decorToken));
        assertTrue(ime.mToken.inTransition());
        assertTrue(task.inTransition());
        assertTrue(asyncRotationController.isTargetToken(decorToken));
        assertShouldFreezeInsetsPosition(asyncRotationController, statusBar, true);

        if (TransitionController.SYNC_METHOD != BLASTSyncEngine.METHOD_BLAST) {
            // Only seamless window syncs its draw transaction with transition.
            assertFalse(asyncRotationController.handleFinishDrawing(statusBar, mMockT));
            assertTrue(asyncRotationController.handleFinishDrawing(screenDecor, mMockT));
        }
        screenDecor.setOrientationChanging(false);
        // Status bar finishes drawing before the start transaction. Its fade-in animation will be
        // executed until the transaction is committed, so it is still in target tokens.
        statusBar.setOrientationChanging(false);
        assertTrue(asyncRotationController.isTargetToken(statusBar.mToken));

        final SurfaceControl.Transaction startTransaction = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.TransactionCommittedListener transactionCommittedListener =
                onRotationTransactionReady(player, startTransaction);

        // The transaction is committed, so fade-in animation for status bar is consumed.
        transactionCommittedListener.onTransactionCommitted();
        assertFalse(asyncRotationController.isTargetToken(statusBar.mToken));
        assertShouldFreezeInsetsPosition(asyncRotationController, navBar, false);

        // Navigation bar finishes drawing after the start transaction, so its fade-in animation
        // can execute directly.
        navBar.setOrientationChanging(false);
        assertFalse(asyncRotationController.isTargetToken(navBar.mToken));
        assertNull(mDisplayContent.getAsyncRotationController());
    }

    @Test
    public void testAppTransitionWithRotationChange() {
        final TestTransitionPlayer player = registerTestTransitionPlayer();
        final boolean useFixedRotation = !player.mController.useShellTransitionsRotation();
        if (useFixedRotation) {
            testFixedRotationOpen(player);
        } else {
            testShellRotationOpen(player);
        }
    }

    private void testShellRotationOpen(TestTransitionPlayer player) {
        final WindowState statusBar = createWindow(null, TYPE_STATUS_BAR, "statusBar");
        makeWindowVisible(statusBar);
        mDisplayContent.getDisplayPolicy().addWindowLw(statusBar, statusBar.mAttrs);
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Transition transition = app.mTransitionController.createTransition(TRANSIT_OPEN);
        app.mTransitionController.requestStartTransition(transition, app.getTask(),
                null /* remoteTransition */, null /* displayChange */);
        mDisplayContent.getDisplayRotation().setRotation(mDisplayContent.getRotation() + 1);
        final int anyChanges = 1;
        mDisplayContent.setLastHasContent();
        mDisplayContent.requestChangeTransitionIfNeeded(anyChanges, null /* displayChange */);
        transition.setKnownConfigChanges(mDisplayContent, anyChanges);
        final AsyncRotationController asyncRotationController =
                mDisplayContent.getAsyncRotationController();
        assertNotNull(asyncRotationController);
        assertShouldFreezeInsetsPosition(asyncRotationController, statusBar, true);

        statusBar.setOrientationChanging(true);
        player.startTransition();
        // Non-app windows should not be collected.
        assertFalse(statusBar.mToken.inTransition());
        assertTrue(app.getTask().inTransition());

        final SurfaceControl.Transaction startTransaction = mock(SurfaceControl.Transaction.class);
        final SurfaceControl leash = statusBar.mToken.getAnimationLeash();
        doReturn(true).when(leash).isValid();
        final SurfaceControl.TransactionCommittedListener transactionCommittedListener =
                onRotationTransactionReady(player, startTransaction);
        // The leash should be unrotated.
        verify(startTransaction).setMatrix(eq(leash), any(), any());

        // The redrawn window will be faded in when the transition finishes. And because this test
        // only use one non-activity window, the fade rotation controller should also be cleared.
        statusBar.mWinAnimator.mDrawState = WindowStateAnimator.DRAW_PENDING;
        final SurfaceControl.Transaction postDrawTransaction =
                mock(SurfaceControl.Transaction.class);
        final boolean layoutNeeded = statusBar.finishDrawing(postDrawTransaction,
                Integer.MAX_VALUE);
        assertFalse(layoutNeeded);

        transactionCommittedListener.onTransactionCommitted();
        player.finish();
        // The controller should capture the draw transaction and merge it when preparing to run
        // fade-in animation.
        verify(mDisplayContent.getPendingTransaction()).merge(eq(postDrawTransaction));
        assertNull(mDisplayContent.getAsyncRotationController());
    }

    private void testFixedRotationOpen(TestTransitionPlayer player) {
        final WindowState statusBar = createWindow(null, TYPE_STATUS_BAR, "statusBar");
        makeWindowVisible(statusBar);
        mDisplayContent.getDisplayPolicy().addWindowLw(statusBar, statusBar.mAttrs);
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Transition transition = app.mTransitionController.createTransition(TRANSIT_OPEN);
        app.mTransitionController.requestStartTransition(transition, app.getTask(),
                null /* remoteTransition */, null /* displayChange */);
        app.mTransitionController.collectExistenceChange(app.getTask());
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(app);
        final AsyncRotationController asyncRotationController =
                mDisplayContent.getAsyncRotationController();
        assertNotNull(asyncRotationController);
        assertShouldFreezeInsetsPosition(asyncRotationController, statusBar, true);
        assertTrue(app.getTask().inTransition());

        player.start();
        player.finish();
        app.getTask().finishSync(mWm.mTransactionFactory.get(), false /* cancel */);

        // The open transition is finished. Continue to play seamless display change transition,
        // so the previous async rotation controller should still exist.
        mDisplayContent.getDisplayRotation().setRotation(mDisplayContent.getRotation() + 1);
        mDisplayContent.setLastHasContent();
        mDisplayContent.requestChangeTransitionIfNeeded(1 /* changes */, null /* displayChange */);
        assertTrue(mDisplayContent.hasTopFixedRotationLaunchingApp());
        assertNotNull(mDisplayContent.getAsyncRotationController());

        // The app is still in transition, so the callback should be no-op.
        mDisplayContent.mTransitionController.dispatchLegacyAppTransitionFinished(app);
        assertTrue(mDisplayContent.hasTopFixedRotationLaunchingApp());

        statusBar.setOrientationChanging(true);
        player.startTransition();
        // Non-app windows should not be collected.
        assertFalse(mDisplayContent.mTransitionController.isCollecting(statusBar.mToken));

        onRotationTransactionReady(player, mWm.mTransactionFactory.get()).onTransactionCommitted();
        assertEquals(ROTATION_ANIMATION_SEAMLESS, player.mLastReady.getChange(
                mDisplayContent.mRemoteToken.toWindowContainerToken()).getRotationAnimation());
        player.finish();

        // The controller should be cleared if the target windows are drawn.
        statusBar.finishDrawing(mWm.mTransactionFactory.get(), Integer.MAX_VALUE);
        statusBar.setOrientationChanging(false);
        assertNull(mDisplayContent.getAsyncRotationController());
    }

    private static void assertShouldFreezeInsetsPosition(AsyncRotationController controller,
            WindowState w, boolean freeze) {
        if (TransitionController.SYNC_METHOD != BLASTSyncEngine.METHOD_BLAST) {
            // Non blast sync should never freeze insets position.
            freeze = false;
        }
        assertEquals(freeze, controller.shouldFreezeInsetsPosition(w));
    }

    @Test
    public void testDeferRotationForTransientLaunch() {
        final TestTransitionPlayer player = registerTestTransitionPlayer();
        assumeFalse(mDisplayContent.mTransitionController.useShellTransitionsRotation());
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord home = new ActivityBuilder(mAtm)
                .setTask(mDisplayContent.getDefaultTaskDisplayArea().getRootHomeTask())
                .setScreenOrientation(SCREEN_ORIENTATION_NOSENSOR).setVisible(false).build();
        final Transition transition = home.mTransitionController.createTransition(TRANSIT_OPEN);
        final int prevRotation = mDisplayContent.getRotation();
        transition.setTransientLaunch(home, null /* restoreBelow */);
        home.mTransitionController.requestStartTransition(transition, home.getTask(),
                null /* remoteTransition */, null /* displayChange */);
        transition.collectExistenceChange(home);
        home.setVisibleRequested(true);
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(home);
        doReturn(true).when(home).hasFixedRotationTransform(any());
        player.startTransition();
        player.onTransactionReady(mDisplayContent.getSyncTransaction());

        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        final RemoteDisplayChangeController displayChangeController = mDisplayContent
                .mRemoteDisplayChangeController;
        spyOn(displayRotation);
        spyOn(displayChangeController);
        doReturn(true).when(displayChangeController).isWaitingForRemoteDisplayChange();
        doReturn(prevRotation + 1).when(displayRotation).rotationForOrientation(
                anyInt() /* orientation */, anyInt() /* lastRotation */);
        // Rotation update is skipped while the recents animation is running.
        assertFalse(mDisplayContent.updateRotationUnchecked());
        assertEquals(SCREEN_ORIENTATION_UNSET, displayRotation.getLastOrientation());
        // Return to the app without fixed orientation from recents.
        app.moveFocusableActivityToTop("test");
        player.finish();
        // The display should be updated to the changed orientation after the animation is finish.
        assertNotEquals(mDisplayContent.getRotation(), prevRotation);
    }

    @Test
    public void testIntermediateVisibility() {
        final TaskSnapshotController snapshotController = mock(TaskSnapshotController.class);
        final TransitionController controller = new TransitionController(mAtm, snapshotController,
                mock(TransitionTracer.class));
        final ITransitionPlayer player = new ITransitionPlayer.Default();
        controller.registerTransitionPlayer(player, null /* playerProc */);
        final Transition openTransition = controller.createTransition(TRANSIT_OPEN);

        // Start out with task2 visible and set up a transition that closes task2 and opens task1
        final Task task1 = createTask(mDisplayContent);
        final ActivityRecord activity1 = createActivityRecord(task1);
        activity1.setVisibleRequested(false);
        activity1.setVisible(false);
        final Task task2 = createTask(mDisplayContent);
        makeTaskOrganized(task1, task2);
        final ActivityRecord activity2 = createActivityRecord(task1);
        activity2.setVisibleRequested(true);
        activity2.setVisible(true);

        openTransition.collectExistenceChange(task1);
        openTransition.collectExistenceChange(activity1);
        openTransition.collectExistenceChange(task2);
        openTransition.collectExistenceChange(activity2);

        activity1.setVisibleRequested(true);
        activity1.setVisible(true);
        activity2.setVisibleRequested(false);

        // Using abort to force-finish the sync (since we can't wait for drawing in unit test).
        // We didn't call abort on the transition itself, so it will still run onTransactionReady
        // normally.
        mWm.mSyncEngine.abort(openTransition.getSyncId());

        // Before finishing openTransition, we are now going to simulate closing task1 to return
        // back to (open) task2.
        final Transition closeTransition = controller.createTransition(TRANSIT_CLOSE);

        closeTransition.collectExistenceChange(task1);
        closeTransition.collectExistenceChange(activity1);
        closeTransition.collectExistenceChange(task2);
        closeTransition.collectExistenceChange(activity2);

        activity1.setVisibleRequested(false);
        activity2.setVisibleRequested(true);

        openTransition.finishTransition();

        // We finished the openTransition. Even though activity1 is visibleRequested=false, since
        // the closeTransition animation hasn't played yet, make sure that we didn't commit
        // visible=false on activity1 since it needs to remain visible for the animation.
        assertTrue(activity1.isVisible());
        assertTrue(activity2.isVisible());

        // Using abort to force-finish the sync (since we obviously can't wait for drawing).
        // We didn't call abort on the actual transition, so it will still run onTransactionReady
        // normally.
        mWm.mSyncEngine.abort(closeTransition.getSyncId());

        closeTransition.finishTransition();

        assertFalse(activity1.isVisible());
        assertTrue(activity2.isVisible());
    }

    @Test
    public void testTransientLaunch() {
        final TaskSnapshotController snapshotController = mock(TaskSnapshotController.class);
        final ArrayList<ActivityRecord> enteringAnimReports = new ArrayList<>();
        final TransitionController controller = new TransitionController(mAtm, snapshotController,
                mock(TransitionTracer.class)) {
            @Override
            protected void dispatchLegacyAppTransitionFinished(ActivityRecord ar) {
                if (ar.mEnteringAnimation) {
                    enteringAnimReports.add(ar);
                }
                super.dispatchLegacyAppTransitionFinished(ar);
            }
        };
        final ITransitionPlayer player = new ITransitionPlayer.Default();
        controller.registerTransitionPlayer(player, null /* playerProc */);
        final Transition openTransition = controller.createTransition(TRANSIT_OPEN);

        // Start out with task2 visible and set up a transition that closes task2 and opens task1
        final Task task1 = createTask(mDisplayContent);
        final ActivityRecord activity1 = createActivityRecord(task1);
        activity1.setVisibleRequested(false);
        activity1.setVisible(false);
        final Task task2 = createTask(mDisplayContent);
        makeTaskOrganized(task1, task2);
        final ActivityRecord activity2 = createActivityRecord(task2);
        activity2.setVisibleRequested(true);
        activity2.setVisible(true);

        openTransition.collectExistenceChange(task1);
        openTransition.collectExistenceChange(activity1);
        openTransition.collectExistenceChange(task2);
        openTransition.collectExistenceChange(activity2);

        activity1.setVisibleRequested(true);
        activity1.setVisible(true);
        activity2.setVisibleRequested(false);

        // Using abort to force-finish the sync (since we can't wait for drawing in unit test).
        // We didn't call abort on the transition itself, so it will still run onTransactionReady
        // normally.
        mWm.mSyncEngine.abort(openTransition.getSyncId());

        verify(snapshotController, times(1)).recordSnapshot(eq(task2), eq(false));

        openTransition.finishTransition();

        // We are now going to simulate closing task1 to return back to (open) task2.
        final Transition closeTransition = controller.createTransition(TRANSIT_CLOSE);

        closeTransition.collectExistenceChange(task1);
        closeTransition.collectExistenceChange(activity1);
        closeTransition.collectExistenceChange(task2);
        closeTransition.collectExistenceChange(activity2);
        closeTransition.setTransientLaunch(activity2, null /* restoreBelow */);

        activity1.setVisibleRequested(false);
        activity2.setVisibleRequested(true);
        activity2.setVisible(true);

        // Using abort to force-finish the sync (since we obviously can't wait for drawing).
        // We didn't call abort on the actual transition, so it will still run onTransactionReady
        // normally.
        mWm.mSyncEngine.abort(closeTransition.getSyncId());

        // Make sure we haven't called recordSnapshot (since we are transient, it shouldn't be
        // called until finish).
        verify(snapshotController, times(0)).recordSnapshot(eq(task1), eq(false));

        enteringAnimReports.clear();
        closeTransition.finishTransition();

        verify(snapshotController, times(1)).recordSnapshot(eq(task1), eq(false));
        assertTrue(enteringAnimReports.contains(activity2));
    }

    @Test
    public void testNotReadyPushPop() {
        final TaskSnapshotController snapshotController = mock(TaskSnapshotController.class);
        final TransitionController controller = new TransitionController(mAtm, snapshotController,
                mock(TransitionTracer.class));
        final ITransitionPlayer player = new ITransitionPlayer.Default();
        controller.registerTransitionPlayer(player, null /* playerProc */);
        final Transition openTransition = controller.createTransition(TRANSIT_OPEN);

        // Start out with task2 visible and set up a transition that closes task2 and opens task1
        final Task task1 = createTask(mDisplayContent);
        openTransition.collectExistenceChange(task1);

        assertFalse(openTransition.allReady());

        openTransition.setAllReady();

        openTransition.deferTransitionReady();
        assertFalse(openTransition.allReady());

        openTransition.continueTransitionReady();
        assertTrue(openTransition.allReady());
    }

    @Test
    public void testIsBehindStartingWindowChange() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity0 = createActivityRecord(task);
        final ActivityRecord activity1 = createActivityRecord(task);
        doReturn(true).when(activity1).hasStartingWindow();

        // Start states.
        changes.put(activity0,
                new Transition.ChangeInfo(activity0, true /* vis */, false /* exChg */));
        changes.put(activity1,
                new Transition.ChangeInfo(activity1, false /* vis */, false /* exChg */));
        // End states.
        activity0.setVisibleRequested(false);
        activity1.setVisibleRequested(true);

        participants.add(activity0);
        participants.add(activity1);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);

        // All windows in the Task should have FLAG_IS_BEHIND_STARTING_WINDOW because the starting
        // window should cover the whole Task.
        assertEquals(2, info.getChanges().size());
        assertTrue(info.getChanges().get(0).hasFlags(FLAG_IS_BEHIND_STARTING_WINDOW));
        assertTrue(info.getChanges().get(1).hasFlags(FLAG_IS_BEHIND_STARTING_WINDOW));

    }

    @Test
    public void testFlagInTaskWithEmbeddedActivity() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final ActivityRecord nonEmbeddedActivity = createActivityRecord(task);
        assertFalse(nonEmbeddedActivity.isEmbedded());
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        mAtm.mTaskFragmentOrganizerController.registerOrganizer(
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder()));
        final TaskFragment embeddedTf = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(2)
                .setOrganizer(organizer)
                .build();
        final ActivityRecord closingActivity = embeddedTf.getBottomMostActivity();
        final ActivityRecord openingActivity = embeddedTf.getTopMostActivity();
        // Start states.
        changes.put(embeddedTf,
                new Transition.ChangeInfo(embeddedTf, true /* vis */, false /* exChg */));
        changes.put(closingActivity,
                new Transition.ChangeInfo(closingActivity, true /* vis */, false /* exChg */));
        changes.put(openingActivity,
                new Transition.ChangeInfo(openingActivity, false /* vis */, true /* exChg */));
        changes.put(nonEmbeddedActivity, new Transition.ChangeInfo(nonEmbeddedActivity,
                true /* vis */, false /* exChg */));
        // End states.
        closingActivity.setVisibleRequested(false);
        openingActivity.setVisibleRequested(true);
        nonEmbeddedActivity.setVisibleRequested(false);

        participants.add(closingActivity);
        participants.add(openingActivity);
        participants.add(nonEmbeddedActivity);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);

        // All windows in the Task should have FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY because the Task
        // contains embedded activity.
        assertEquals(3, info.getChanges().size());
        assertTrue(info.getChanges().get(0).hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY));
        assertTrue(info.getChanges().get(1).hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY));
        assertTrue(info.getChanges().get(2).hasFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY));
    }

    @Test
    public void testFlagFillsTask_embeddingNotFillingTask() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final Rect taskBounds = new Rect(0, 0, 500, 1000);
        task.getConfiguration().windowConfiguration.setBounds(taskBounds);
        final ActivityRecord nonEmbeddedActivity = createActivityRecord(task);
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        mAtm.mTaskFragmentOrganizerController.registerOrganizer(
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder()));
        final TaskFragment embeddedTf = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .createActivityCount(1)
                .setOrganizer(organizer)
                .build();
        final ActivityRecord embeddedActivity = embeddedTf.getTopMostActivity();
        // Start states.
        changes.put(task, new Transition.ChangeInfo(task, true /* vis */, false /* exChg */));
        changes.put(nonEmbeddedActivity,
                new Transition.ChangeInfo(nonEmbeddedActivity, true /* vis */, false /* exChg */));
        changes.put(embeddedTf,
                new Transition.ChangeInfo(embeddedTf, false /* vis */, true /* exChg */));
        // End states.
        nonEmbeddedActivity.setVisibleRequested(false);
        embeddedActivity.setVisibleRequested(true);
        embeddedTf.setBounds(new Rect(0, 0, 500, 500));

        participants.add(nonEmbeddedActivity);
        participants.add(embeddedTf);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);

        // The embedded with bounds overridden should not have the flag.
        assertEquals(2, info.getChanges().size());
        assertFalse(info.getChanges().get(0).hasFlags(FLAG_FILLS_TASK));
        assertEquals(embeddedTf.getBounds(), info.getChanges().get(0).getEndAbsBounds());
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_FILLS_TASK));
    }

    @Test
    public void testFlagFillsTask_openActivityFillingTask() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final Rect taskBounds = new Rect(0, 0, 500, 1000);
        task.getConfiguration().windowConfiguration.setBounds(taskBounds);
        final ActivityRecord activity = createActivityRecord(task);
        // Start states: set bounds to make sure the start bounds is ignored if it is not visible.
        activity.getConfiguration().windowConfiguration.setBounds(new Rect(0, 0, 250, 500));
        activity.setVisibleRequested(false);
        changes.put(activity, new Transition.ChangeInfo(activity));
        // End states: reset bounds to fill Task.
        activity.getConfiguration().windowConfiguration.setBounds(taskBounds);
        activity.setVisibleRequested(true);

        participants.add(activity);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);

        // Opening activity that is filling Task after transition should have the flag.
        assertEquals(1, info.getChanges().size());
        assertTrue(info.getChanges().get(0).hasFlags(FLAG_FILLS_TASK));
    }

    @Test
    public void testFlagFillsTask_closeActivityFillingTask() {
        final Transition transition = createTestTransition(TRANSIT_CLOSE);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final Rect taskBounds = new Rect(0, 0, 500, 1000);
        task.getConfiguration().windowConfiguration.setBounds(taskBounds);
        final ActivityRecord activity = createActivityRecord(task);
        // Start states: fills Task without override.
        activity.setVisibleRequested(true);
        changes.put(activity, new Transition.ChangeInfo(activity));
        // End states: set bounds to make sure the start bounds is ignored if it is not visible.
        activity.getConfiguration().windowConfiguration.setBounds(new Rect(0, 0, 250, 500));
        activity.setVisibleRequested(false);

        participants.add(activity);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);

        // Closing activity that is filling Task before transition should have the flag.
        assertEquals(1, info.getChanges().size());
        assertTrue(info.getChanges().get(0).hasFlags(FLAG_FILLS_TASK));
    }

    @Test
    public void testReparentChangeLastParent() {
        final Transition transition = createTestTransition(TRANSIT_CHANGE);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        // Reparent activity in transition.
        final Task lastParent = createTask(mDisplayContent);
        final Task newParent = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(lastParent);
        activity.setVisibleRequested(true);
        // Skip manipulate the SurfaceControl.
        doNothing().when(activity).setDropInputMode(anyInt());
        changes.put(activity, new Transition.ChangeInfo(activity));
        activity.reparent(newParent, POSITION_TOP);
        activity.setVisibleRequested(false);

        participants.add(activity);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);

        // Change contains last parent info.
        assertEquals(1, info.getChanges().size());
        assertEquals(lastParent.mRemoteToken.toWindowContainerToken(),
                info.getChanges().get(0).getLastParent());
    }

    @Test
    public void testIncludeEmbeddedActivityReparent() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        final Task task = createTask(mDisplayContent);
        task.setBounds(new Rect(0, 0, 2000, 1000));
        final ActivityRecord activity = createActivityRecord(task);
        activity.setVisibleRequested(true);
        // Skip manipulate the SurfaceControl.
        doNothing().when(activity).setDropInputMode(anyInt());
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        mAtm.mTaskFragmentOrganizerController.registerOrganizer(
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder()));
        final TaskFragment embeddedTf = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(organizer)
                .build();
        // TaskFragment with different bounds from Task.
        embeddedTf.setBounds(new Rect(0, 0, 1000, 1000));

        // Start states.
        transition.collect(activity);
        transition.collectExistenceChange(embeddedTf);

        // End states.
        activity.reparent(embeddedTf, POSITION_TOP);

        // Verify that both activity and TaskFragment are included.
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        assertTrue(Transition.containsChangeFor(embeddedTf, targets));
        assertTrue(Transition.containsChangeFor(activity, targets));
    }

    @Test
    public void testChangeSetBackgroundColor() {
        final Transition transition = createTestTransition(TRANSIT_CHANGE);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        // Test background color for Activity and embedded TaskFragment.
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        mAtm.mTaskFragmentOrganizerController.registerOrganizer(
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder()));
        final Task task = createTask(mDisplayContent);
        final TaskFragment embeddedTf = createTaskFragmentWithEmbeddedActivity(task, organizer);
        final ActivityRecord embeddedActivity = embeddedTf.getTopMostActivity();
        final ActivityRecord nonEmbeddedActivity = createActivityRecord(task);
        final ActivityManager.TaskDescription taskDescription =
                new ActivityManager.TaskDescription.Builder()
                        .setBackgroundColor(Color.YELLOW)
                        .build();
        task.setTaskDescription(taskDescription);

        // Start states:
        embeddedActivity.setVisibleRequested(true);
        nonEmbeddedActivity.setVisibleRequested(false);
        changes.put(embeddedTf, new Transition.ChangeInfo(embeddedTf));
        changes.put(nonEmbeddedActivity, new Transition.ChangeInfo(nonEmbeddedActivity));
        // End states:
        embeddedActivity.setVisibleRequested(false);
        nonEmbeddedActivity.setVisibleRequested(true);

        participants.add(embeddedTf);
        participants.add(nonEmbeddedActivity);
        final ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        final TransitionInfo info = Transition.calculateTransitionInfo(transition.mType,
                0 /* flags */, targets, mMockT);

        // Background color should be set on both Activity and embedded TaskFragment.
        final int expectedBackgroundColor = ColorUtils.setAlphaComponent(
                taskDescription.getBackgroundColor(), 255);
        assertEquals(2, info.getChanges().size());
        assertEquals(expectedBackgroundColor, info.getChanges().get(0).getBackgroundColor());
        assertEquals(expectedBackgroundColor, info.getChanges().get(1).getBackgroundColor());
    }

    @Test
    public void testTransitionVisibleChange() {
        registerTestTransitionPlayer();
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Transition transition = new Transition(TRANSIT_OPEN, 0 /* flags */,
                app.mTransitionController, mWm.mSyncEngine);
        app.mTransitionController.moveToCollecting(transition, BLASTSyncEngine.METHOD_NONE);
        final ArrayList<WindowContainer> freezeCalls = new ArrayList<>();
        transition.setContainerFreezer(new Transition.IContainerFreezer() {
            @Override
            public boolean freeze(@NonNull WindowContainer wc, @NonNull Rect bounds) {
                freezeCalls.add(wc);
                return true;
            }

            @Override
            public void cleanUp(SurfaceControl.Transaction t) {
            }
        });
        final Task task = app.getTask();
        transition.collect(task);
        final Rect bounds = new Rect(task.getBounds());
        Configuration c = new Configuration(task.getRequestedOverrideConfiguration());
        bounds.inset(10, 10);
        c.windowConfiguration.setBounds(bounds);
        task.onRequestedOverrideConfigurationChanged(c);
        assertTrue(freezeCalls.contains(task));
        transition.abort();
    }

    @Test
    public void testDeferTransitionReady_deferStartedTransition() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        transition.setAllReady();
        transition.start();

        assertTrue(mSyncEngine.isReady(transition.getSyncId()));

        transition.deferTransitionReady();

        // Both transition ready tracker and sync engine should be deferred.
        assertFalse(transition.allReady());
        assertFalse(mSyncEngine.isReady(transition.getSyncId()));

        transition.continueTransitionReady();

        assertTrue(transition.allReady());
        assertTrue(mSyncEngine.isReady(transition.getSyncId()));
    }

    @Test
    public void testVisibleChange_snapshot() {
        registerTestTransitionPlayer();
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Transition transition = new Transition(TRANSIT_CHANGE, 0 /* flags */,
                app.mTransitionController, mWm.mSyncEngine);
        app.mTransitionController.moveToCollecting(transition, BLASTSyncEngine.METHOD_NONE);
        final SurfaceControl mockSnapshot = mock(SurfaceControl.class);
        transition.setContainerFreezer(new Transition.IContainerFreezer() {
            @Override
            public boolean freeze(@NonNull WindowContainer wc, @NonNull Rect bounds) {
                Objects.requireNonNull(transition.mChanges.get(wc)).mSnapshot = mockSnapshot;
                return true;
            }

            @Override
            public void cleanUp(SurfaceControl.Transaction t) {
            }
        });
        final Task task = app.getTask();
        transition.collect(task);
        final Rect bounds = new Rect(task.getBounds());
        Configuration c = new Configuration(task.getRequestedOverrideConfiguration());
        bounds.inset(10, 10);
        c.windowConfiguration.setBounds(bounds);
        task.onRequestedOverrideConfigurationChanged(c);

        ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                transition.mParticipants, transition.mChanges);
        TransitionInfo info = Transition.calculateTransitionInfo(
                TRANSIT_CHANGE, 0, targets, mMockT);
        assertEquals(mockSnapshot,
                info.getChange(task.mRemoteToken.toWindowContainerToken()).getSnapshot());
        transition.abort();
    }

    @Test
    public void testCollectReparentChange() {
        registerTestTransitionPlayer();

        // Reparent activity in transition.
        final Task lastParent = createTask(mDisplayContent);
        final Task newParent = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(lastParent);
        doReturn(true).when(lastParent).shouldRemoveSelfOnLastChildRemoval();
        doNothing().when(activity).setDropInputMode(anyInt());
        activity.setVisibleRequested(true);

        final Transition transition = new Transition(TRANSIT_CHANGE, 0 /* flags */,
                activity.mTransitionController, mWm.mSyncEngine);
        activity.mTransitionController.moveToCollecting(transition);
        transition.collect(activity);
        activity.reparent(newParent, POSITION_TOP);

        // ChangeInfo#mCommonAncestor should be set after reparent.
        final Transition.ChangeInfo change = transition.mChanges.get(activity);
        assertEquals(newParent.getDisplayArea(), change.mCommonAncestor);
    }

    private static void makeTaskOrganized(Task... tasks) {
        final ITaskOrganizer organizer = mock(ITaskOrganizer.class);
        for (Task t : tasks) {
            t.mTaskOrganizer = organizer;
        }
    }

    private static void makeDisplayAreaOrganized(WindowContainer<?> from,
            WindowContainer<?> end) {
        final IDisplayAreaOrganizer organizer = mock(IDisplayAreaOrganizer.class);
        while (from != null && from != end) {
            if (from.asDisplayArea() != null) {
                from.asDisplayArea().mOrganizer = organizer;
            }
            from = from.getParent();
        }
        if (end.asDisplayArea() != null) {
            end.asDisplayArea().mOrganizer = organizer;
        }
    }

    /** Fill the change map with all the parents of top. Change maps are usually fully populated */
    private static void fillChangeMap(ArrayMap<WindowContainer, Transition.ChangeInfo> changes,
            WindowContainer top) {
        for (WindowContainer curr = top.getParent(); curr != null; curr = curr.getParent()) {
            changes.put(curr, new Transition.ChangeInfo(curr, true /* vis */, false /* exChg */));
        }
    }

    private static SurfaceControl.TransactionCommittedListener onRotationTransactionReady(
            TestTransitionPlayer player, SurfaceControl.Transaction startTransaction) {
        final ArgumentCaptor<SurfaceControl.TransactionCommittedListener> listenerCaptor =
                ArgumentCaptor.forClass(SurfaceControl.TransactionCommittedListener.class);
        player.onTransactionReady(startTransaction);
        verify(startTransaction).addTransactionCommittedListener(any(), listenerCaptor.capture());
        return listenerCaptor.getValue();
    }
}
