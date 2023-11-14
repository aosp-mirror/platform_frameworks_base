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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
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
import static android.window.TransitionInfo.FLAG_MOVED_TO_TOP;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_SYNC;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;
import static android.window.TransitionInfo.isIndependent;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.window.flags.Flags.explicitRefreshRateHints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import android.view.WindowManager;
import android.window.IDisplayAreaOrganizer;
import android.window.IRemoteTransition;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskOrganizer;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.SystemPerformanceHinter;
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
import java.util.function.Consumer;
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

    private Transition createTestTransition(int transitType, TransitionController controller) {
        final Transition transition = new Transition(transitType, 0 /* flags */, controller,
                controller.mSyncEngine);
        spyOn(transition.mLogger);
        doNothing().when(transition.mLogger).logOnSendAsync(any());
        return transition;
    }

    private Transition createTestTransition(int transitType) {
        final TransitionController controller = new TestTransitionController(
                mock(ActivityTaskManagerService.class));

        mSyncEngine = createTestBLASTSyncEngine();
        controller.setSyncEngine(mSyncEngine);
        final Transition out = createTestTransition(transitType, controller);
        out.startCollecting(0 /* timeoutMs */);
        return out;
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
    public void testCreateInfo_MultiDisplay() {
        DisplayContent otherDisplay = createNewDisplay();
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task display0Task = createTask(mDisplayContent);
        final Task display1Task = createTask(otherDisplay);
        // Start states.
        changes.put(display0Task,
                new Transition.ChangeInfo(display0Task, false /* vis */, true /* exChg */));
        changes.put(display1Task,
                new Transition.ChangeInfo(display1Task, false /* vis */, true /* exChg */));
        fillChangeMap(changes, display0Task);
        fillChangeMap(changes, display1Task);
        // End states.
        display0Task.setVisibleRequested(true);
        display1Task.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        participants.add(display0Task);
        participants.add(display1Task);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getRootCount());
        // Check that the changes are assigned to the correct display
        assertEquals(mDisplayContent.getDisplayId(), info.getChange(
                display0Task.mRemoteToken.toWindowContainerToken()).getEndDisplayId());
        assertEquals(otherDisplay.getDisplayId(), info.getChange(
                display1Task.mRemoteToken.toWindowContainerToken()).getEndDisplayId());
        // Check that roots can be found by display and have the correct display
        assertEquals(mDisplayContent.getDisplayId(),
                info.getRoot(info.findRootIndex(mDisplayContent.getDisplayId())).getDisplayId());
        assertEquals(otherDisplay.getDisplayId(),
                info.getRoot(info.findRootIndex(otherDisplay.getDisplayId())).getDisplayId());
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
        final Task task = app.getTask();
        task.setTaskOrganizer(mock(ITaskOrganizer.class), true /* skipTaskAppeared */);
        app.setVisibleRequested(true);
        final TransitionController controller = app.mTransitionController;
        final Transition transition = controller.createTransition(TRANSIT_OPEN);
        final RemoteTransition remoteTransition = new RemoteTransition(
                mock(IRemoteTransition.class));
        remoteTransition.setAppThread(delegateProc.getThread());
        transition.collect(app);
        controller.requestStartTransition(transition, null /* startTask */, remoteTransition,
                null /* displayChange */);
        assertTrue(delegateProc.isRunningRemoteTransition());
        testPlayer.startTransition();
        app.onStartingWindowDrawn();
        // The task appeared event should be deferred until transition ready.
        assertFalse(task.taskAppearedReady());
        testPlayer.onTransactionReady(app.getSyncTransaction());
        assertTrue(task.taskAppearedReady());
        assertTrue(playerProc.isRunningRemoteTransition());
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
        // Force the change-type changes to be "dirty" so they aren't skipped
        changes.get(changeTask).mKnownConfigChanges = 1;
        changes.get(changeInChangeTask).mKnownConfigChanges = 1;
        changes.get(changeInChange).mKnownConfigChanges = 1;

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

        final Task oldTask = createTask(mDisplayContent);
        final Task newTask = createTask(mDisplayContent);

        final ActivityRecord closing = createActivityRecord(oldTask);
        closing.setOccludesParent(true);
        final ActivityRecord opening = createActivityRecord(newTask);
        opening.setOccludesParent(true);
        // Start states.
        changes.put(newTask, new Transition.ChangeInfo(newTask, false /* vis */, true /* exChg */));
        changes.put(oldTask, new Transition.ChangeInfo(oldTask, true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
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

        assertFalse(info.getChanges().get(0).hasFlags(FLAG_TRANSLUCENT));
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_TRANSLUCENT));
    }

    @Test
    public void testOpenTranslucentTask() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task oldTask = createTask(mDisplayContent);
        final Task newTask = createTask(mDisplayContent);

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

        assertTrue(info.getChanges().get(0).hasFlags(FLAG_TRANSLUCENT));
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_TRANSLUCENT));
    }

    @Test
    public void testOpenOpaqueTaskFragment() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final TaskFragment closingTaskFragment = createTaskFragmentWithActivity(task);
        final TaskFragment openingTaskFragment = createTaskFragmentWithActivity(task);

        final ActivityRecord closing = closingTaskFragment.getTopMostActivity();
        closing.setOccludesParent(true);
        final ActivityRecord opening = openingTaskFragment.getTopMostActivity();
        opening.setOccludesParent(true);
        // Start states.
        changes.put(openingTaskFragment, new Transition.ChangeInfo(openingTaskFragment,
                false /* vis */, true /* exChg */));
        changes.put(closingTaskFragment, new Transition.ChangeInfo(closingTaskFragment,
                true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, openingTaskFragment);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(closingTaskFragment);
        participants.add(openingTaskFragment);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        assertFalse(info.getChanges().get(0).hasFlags(FLAG_TRANSLUCENT));
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_TRANSLUCENT));
    }

    @Test
    public void testOpenTranslucentTaskFragment() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final TaskFragment closingTaskFragment = createTaskFragmentWithActivity(task);
        final TaskFragment openingTaskFragment = createTaskFragmentWithActivity(task);

        final ActivityRecord closing = closingTaskFragment.getTopMostActivity();
        closing.setOccludesParent(true);
        final ActivityRecord opening = openingTaskFragment.getTopMostActivity();
        opening.setOccludesParent(false);
        // Start states.
        changes.put(openingTaskFragment, new Transition.ChangeInfo(openingTaskFragment,
                false /* vis */, true /* exChg */));
        changes.put(closingTaskFragment, new Transition.ChangeInfo(closingTaskFragment,
                true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, openingTaskFragment);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(closingTaskFragment);
        participants.add(openingTaskFragment);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        assertTrue(info.getChanges().get(0).hasFlags(FLAG_TRANSLUCENT));
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_TRANSLUCENT));
    }

    @Test
    public void testCloseOpaqueTaskFragment_withFinishingActivity() {
        final Transition transition = createTestTransition(TRANSIT_CLOSE);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final TaskFragment openingTaskFragment = createTaskFragmentWithActivity(task);
        final TaskFragment closingTaskFragment = createTaskFragmentWithActivity(task);

        final ActivityRecord opening = openingTaskFragment.getTopMostActivity();
        opening.setOccludesParent(true);
        final ActivityRecord closing = closingTaskFragment.getTopMostActivity();
        closing.setOccludesParent(true);
        closing.finishing = true;
        // Start states.
        changes.put(openingTaskFragment, new Transition.ChangeInfo(openingTaskFragment,
                false /* vis */, true /* exChg */));
        changes.put(closingTaskFragment, new Transition.ChangeInfo(closingTaskFragment,
                true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, openingTaskFragment);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(closingTaskFragment);
        participants.add(openingTaskFragment);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        assertFalse(info.getChanges().get(0).hasFlags(FLAG_TRANSLUCENT));
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_TRANSLUCENT));
    }

    @Test
    public void testCloseTranslucentTaskFragment_withFinishingActivity() {
        final Transition transition = createTestTransition(TRANSIT_CLOSE);
        ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        ArraySet<WindowContainer> participants = transition.mParticipants;

        final Task task = createTask(mDisplayContent);
        final TaskFragment openingTaskFragment = createTaskFragmentWithActivity(task);
        final TaskFragment closingTaskFragment = createTaskFragmentWithActivity(task);

        final ActivityRecord opening = openingTaskFragment.getTopMostActivity();
        opening.setOccludesParent(true);
        final ActivityRecord closing = closingTaskFragment.getTopMostActivity();
        closing.setOccludesParent(false);
        closing.finishing = true;
        // Start states.
        changes.put(openingTaskFragment, new Transition.ChangeInfo(openingTaskFragment,
                false /* vis */, true /* exChg */));
        changes.put(closingTaskFragment, new Transition.ChangeInfo(closingTaskFragment,
                true /* vis */, false /* exChg */));
        changes.put(opening, new Transition.ChangeInfo(opening, false /* vis */, true /* exChg */));
        changes.put(closing, new Transition.ChangeInfo(closing, true /* vis */, false /* exChg */));
        fillChangeMap(changes, openingTaskFragment);
        // End states.
        closing.setVisibleRequested(false);
        opening.setVisibleRequested(true);

        final int transit = transition.mType;
        int flags = 0;

        // Check basic both tasks participating
        participants.add(closingTaskFragment);
        participants.add(openingTaskFragment);
        ArrayList<Transition.ChangeInfo> targets =
                Transition.calculateTargets(participants, changes);
        TransitionInfo info = Transition.calculateTransitionInfo(transit, flags, targets, mMockT);
        assertEquals(2, info.getChanges().size());
        assertEquals(transit, info.getType());

        assertTrue(info.getChanges().get(0).hasFlags(FLAG_TRANSLUCENT));
        assertFalse(info.getChanges().get(1).hasFlags(FLAG_TRANSLUCENT));
    }

    @Test
    public void testTimeout() {
        final TransitionController controller = new TestTransitionController(mAtm);
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
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        spyOn(displayPolicy);
        // Simulate gesture navigation (non-movable) so it is not seamless.
        doReturn(false).when(displayPolicy).navigationBarCanMove();
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
        mDisplayContent.mTransitionController.setSyncEngine(createTestBLASTSyncEngine());
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
        player.startTransition();

        assertFalse(mDisplayContent.mTransitionController.isCollecting(statusBar.mToken));
        assertFalse(mDisplayContent.mTransitionController.isCollecting(decorToken));
        assertTrue(ime.mToken.inTransition());
        assertTrue(task.inTransition());
        assertTrue(asyncRotationController.isTargetToken(decorToken));
        assertShouldFreezeInsetsPosition(asyncRotationController, statusBar, true);

        // Only seamless window syncs its draw transaction with transition.
        assertTrue(asyncRotationController.handleFinishDrawing(screenDecor, mMockT));
        // Status bar finishes drawing before the start transaction. Its fade-in animation will be
        // executed until the transaction is committed, so it is still in target tokens.
        assertFalse(asyncRotationController.handleFinishDrawing(statusBar, mMockT));
        assertTrue(asyncRotationController.isTargetToken(statusBar.mToken));

        // Window surface position is frozen while seamless rotation state is active.
        final Point prevPos = new Point(screenDecor.mLastSurfacePosition);
        screenDecor.getFrame().left += 1;
        screenDecor.updateSurfacePosition(mMockT);
        assertEquals(prevPos, screenDecor.mLastSurfacePosition);

        final SurfaceControl.Transaction startTransaction = mock(SurfaceControl.Transaction.class);
        final SurfaceControl.TransactionCommittedListener transactionCommittedListener =
                onRotationTransactionReady(player, startTransaction);

        // The transaction is committed, so fade-in animation for status bar is consumed.
        transactionCommittedListener.onTransactionCommitted();
        assertFalse(asyncRotationController.isTargetToken(statusBar.mToken));
        assertShouldFreezeInsetsPosition(asyncRotationController, navBar, false);

        // Navigation bar finishes drawing after the start transaction, so its fade-in animation
        // can execute directly.
        navBar.mWinAnimator.mDrawState = WindowStateAnimator.HAS_DRAWN;
        asyncRotationController.updateTargetWindows();
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
        final WindowState navBar = createWindow(null, TYPE_NAVIGATION_BAR, "navBar");
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Transition transition = app.mTransitionController.createTransition(TRANSIT_OPEN);
        app.mTransitionController.requestStartTransition(transition, app.getTask(),
                null /* remoteTransition */, null /* displayChange */);
        app.mTransitionController.collectExistenceChange(app.getTask());
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(app);
        final AsyncRotationController asyncRotationController =
                mDisplayContent.getAsyncRotationController();
        assertNotNull(asyncRotationController);
        assertTrue(asyncRotationController.shouldFreezeInsetsPosition(statusBar));
        assertTrue(app.getTask().inTransition());

        player.start();
        player.finish();
        app.getTask().finishSync(mWm.mTransactionFactory.get(), app.getTask().getSyncGroup(),
                false /* cancel */);

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

        // The bar was invisible so it is not handled by the controller. But if it becomes visible
        // and drawn before the transition starts,
        assertFalse(asyncRotationController.isTargetToken(navBar.mToken));
        navBar.finishDrawing(null /* postDrawTransaction */, Integer.MAX_VALUE);
        assertTrue(asyncRotationController.isTargetToken(navBar.mToken));
        assertTrue(asyncRotationController.shouldFreezeInsetsPosition(navBar));

        player.startTransition();
        // Non-app windows should not be collected.
        assertFalse(mDisplayContent.mTransitionController.isCollecting(statusBar.mToken));
        // Avoid DeviceStateController disturbing the test by triggering another rotation change.
        doReturn(false).when(mDisplayContent).updateRotationUnchecked();

        onRotationTransactionReady(player, mWm.mTransactionFactory.get()).onTransactionCommitted();
        assertEquals(ROTATION_ANIMATION_SEAMLESS, player.mLastReady.getChange(
                mDisplayContent.mRemoteToken.toWindowContainerToken()).getRotationAnimation());
        player.finish();

        // The controller should be cleared if the target windows are drawn.
        statusBar.finishDrawing(mWm.mTransactionFactory.get(), Integer.MAX_VALUE);
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
    public void testFinishRotationControllerWithFixedRotation() {
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true).build();
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(app);
        registerTestTransitionPlayer();
        mDisplayContent.setLastHasContent();
        mDisplayContent.requestChangeTransitionIfNeeded(1 /* changes */, null /* displayChange */);
        assertNotNull(mDisplayContent.getAsyncRotationController());
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(null);
        assertNull("Clear rotation controller if rotation is not changed",
                mDisplayContent.getAsyncRotationController());

        mDisplayContent.setFixedRotationLaunchingAppUnchecked(app);
        assertNotNull(mDisplayContent.getAsyncRotationController());
        mDisplayContent.getDisplayRotation().setRotation(
                mDisplayContent.getWindowConfiguration().getRotation() + 1);
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(null);
        assertNotNull("Keep rotation controller if rotation will be changed",
                mDisplayContent.getAsyncRotationController());
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
        final TransitionController controller = new TestTransitionController(mAtm);
        controller.setSyncEngine(mWm.mSyncEngine);
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

        // The abort should still commit visible-requested to visible.
        final Transition abortTransition = controller.createTransition(TRANSIT_OPEN);
        abortTransition.collect(activity1);
        activity1.setVisibleRequested(true);
        activity1.setVisible(false);
        abortTransition.abort();
        assertTrue(activity1.isVisible());

        // The mLaunchTaskBehind flag of an invisible initializing activity should not be cleared.
        final Transition noChangeTransition = controller.createTransition(TRANSIT_OPEN);
        noChangeTransition.collect(activity1);
        activity1.setVisibleRequested(false);
        activity1.setState(ActivityRecord.State.INITIALIZING, "test");
        activity1.mLaunchTaskBehind = true;
        mWm.mSyncEngine.abort(noChangeTransition.getSyncId());
        noChangeTransition.finishTransition();
        assertTrue(activity1.mLaunchTaskBehind);
    }

    @Test
    public void testTransientLaunch() {
        spyOn(mWm.mSnapshotController.mTaskSnapshotController);
        final ArrayList<ActivityRecord> enteringAnimReports = new ArrayList<>();
        final TransitionController controller = new TestTransitionController(mAtm) {
            @Override
            protected void dispatchLegacyAppTransitionFinished(ActivityRecord ar) {
                if (ar.mEnteringAnimation) {
                    enteringAnimReports.add(ar);
                }
                super.dispatchLegacyAppTransitionFinished(ar);
            }
        };
        controller.setSyncEngine(mWm.mSyncEngine);
        controller.mSnapshotController = mWm.mSnapshotController;
        final TaskSnapshotController taskSnapshotController = controller.mSnapshotController
                .mTaskSnapshotController;
        final ITransitionPlayer player = new ITransitionPlayer.Default();
        controller.registerTransitionPlayer(player, null /* playerProc */);
        final Transition openTransition = createTestTransition(TRANSIT_OPEN, controller);
        controller.moveToCollecting(openTransition);

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
        activity1.setState(ActivityRecord.State.RESUMED, "test");

        // Using abort to force-finish the sync (since we can't wait for drawing in unit test).
        // We didn't call abort on the transition itself, so it will still run onTransactionReady
        // normally.
        mWm.mSyncEngine.abort(openTransition.getSyncId());

        verify(taskSnapshotController, times(1)).recordSnapshot(eq(task2));

        controller.finishTransition(openTransition);

        // We are now going to simulate closing task1 to return back to (open) task2.
        final Transition closeTransition = createTestTransition(TRANSIT_CLOSE, controller);
        controller.moveToCollecting(closeTransition);

        closeTransition.collectExistenceChange(task2);
        closeTransition.collectExistenceChange(activity2);
        closeTransition.setTransientLaunch(activity2, task1);
        final Transition.ChangeInfo task1ChangeInfo = closeTransition.mChanges.get(task1);
        assertNotNull(task1ChangeInfo);
        assertTrue(task1ChangeInfo.hasChanged());
        // Make sure the unrelated activity is NOT collected.
        final Transition.ChangeInfo activity1ChangeInfo = closeTransition.mChanges.get(activity1);
        assertNull(activity1ChangeInfo);
        // No need to wait for the activity in transient hide task.
        assertEquals(WindowContainer.SYNC_STATE_NONE, activity1.mSyncState);

        // An active transient launch overrides idle state to avoid clearing power mode before the
        // transition is finished.
        spyOn(mRootWindowContainer.mTransitionController);
        doAnswer(invocation -> controller.isTransientLaunch(invocation.getArgument(0))).when(
                mRootWindowContainer.mTransitionController).isTransientLaunch(any());
        activity2.getTask().setResumedActivity(activity2, "test");
        activity2.idle = true;
        assertFalse(mRootWindowContainer.allResumedActivitiesIdle());

        activity1.setVisibleRequested(false);
        activity2.setVisibleRequested(true);
        activity2.setVisible(true);

        // Using abort to force-finish the sync (since we obviously can't wait for drawing).
        // We didn't call abort on the actual transition, so it will still run onTransactionReady
        // normally.
        mWm.mSyncEngine.abort(closeTransition.getSyncId());

        // Make sure we haven't called recordSnapshot (since we are transient, it shouldn't be
        // called until finish).
        verify(taskSnapshotController, times(0)).recordSnapshot(eq(task1));

        enteringAnimReports.clear();
        doCallRealMethod().when(mWm.mRoot).ensureActivitiesVisible(any(),
                anyInt(), anyBoolean(), anyBoolean());
        final boolean[] wasInFinishingTransition = { false };
        controller.registerLegacyListener(new WindowManagerInternal.AppTransitionListener() {
            @Override
            public void onAppTransitionFinishedLocked(IBinder token) {
                final ActivityRecord r = ActivityRecord.forToken(token);
                if (r != null) {
                    wasInFinishingTransition[0] = controller.inFinishingTransition(r);
                }
            }
        });
        assertTrue(activity1.isVisible());
        doReturn(false).when(task1).isTranslucent(null);
        assertTrue(controller.canApplyDim(task1));
        doReturn(true).when(task1).isTranslucent(null);
        assertFalse(controller.canApplyDim(task1));

        controller.finishTransition(closeTransition);
        assertTrue(wasInFinishingTransition[0]);
        assertNull(controller.mFinishingTransition);

        assertTrue(activity2.isVisible());
        assertEquals(ActivityTaskManagerService.APP_SWITCH_DISALLOW, mAtm.getBalAppSwitchesState());
        // Because task1 is occluded by task2, finishTransition should make activity1 invisible.
        assertFalse(activity1.isVisibleRequested());
        // Make sure activity1 visibility was committed
        assertFalse(activity1.isVisible());
        assertFalse(activity1.app.hasActivityInVisibleTask());
        // Make sure the userLeaving is true and the resuming activity is given,
        verify(task1).startPausing(eq(true), anyBoolean(), eq(activity2), any());

        verify(taskSnapshotController, times(1)).recordSnapshot(eq(task1));
        assertTrue(enteringAnimReports.contains(activity2));
    }

    @Test
    public void testIsTransientVisible() {
        final ActivityRecord appB = new ActivityBuilder(mAtm).setCreateTask(true)
                .setVisible(false).build();
        final ActivityRecord recent = new ActivityBuilder(mAtm).setCreateTask(true)
                .setVisible(false).build();
        final ActivityRecord appA = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task taskA = appA.getTask();
        final Task taskB = appB.getTask();
        final Task taskRecent = recent.getTask();
        registerTestTransitionPlayer();
        final TransitionController controller = mRootWindowContainer.mTransitionController;
        final Transition transition = createTestTransition(TRANSIT_OPEN, controller);
        controller.moveToCollecting(transition);
        transition.collect(recent);
        transition.collect(taskA);
        transition.setTransientLaunch(recent, taskA);
        taskRecent.moveToFront("move-recent-to-front");

        // During collecting and playing, the recent is on top so it is visible naturally.
        // While B needs isTransientVisible to keep visibility because it is occluded by recents.
        assertFalse(controller.isTransientVisible(taskB));
        assertTrue(controller.isTransientVisible(taskA));
        assertFalse(controller.isTransientVisible(taskRecent));
        // Switch to playing state.
        transition.onTransactionReady(transition.getSyncId(), mMockT);
        assertTrue(controller.isTransientVisible(taskA));

        // Switch to another task. For example, use gesture navigation to switch tasks.
        taskB.moveToFront("move-b-to-front");
        // The previous app (taskA) should be paused first so it loses transient visible. Because
        // visually it is taskA -> taskB, the pause -> resume order should be the same.
        assertFalse(controller.isTransientVisible(taskA));
        // Keep the recent visible so there won't be 2 activities pausing at the same time. It is
        // to avoid the latency to resume the current top, i.e. appB.
        assertTrue(controller.isTransientVisible(taskRecent));
        // The recent is paused after the transient transition is finished.
        controller.finishTransition(transition);
        assertFalse(controller.isTransientVisible(taskRecent));
    }

    @Test
    public void testNotReadyPushPop() {
        final TransitionController controller = new TestTransitionController(mAtm);
        controller.setSyncEngine(mWm.mSyncEngine);
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
        assertTrue(info.getChanges().get(1).hasFlags(FLAG_FILLS_TASK));
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
        final ActivityRecord app = createActivityRecord(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final Transition transition = new Transition(TRANSIT_OPEN, 0 /* flags */,
                app.mTransitionController, mWm.mSyncEngine);
        app.mTransitionController.moveToCollecting(transition);
        mWm.mSyncEngine.setSyncMethod(transition.getSyncId(), BLASTSyncEngine.METHOD_NONE);
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
        final ActivityRecord app = createActivityRecord(
                mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final Transition transition = new Transition(TRANSIT_CHANGE, 0 /* flags */,
                app.mTransitionController, mWm.mSyncEngine);
        app.mTransitionController.moveToCollecting(transition);
        mWm.mSyncEngine.setSyncMethod(transition.getSyncId(), BLASTSyncEngine.METHOD_NONE);
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

    @Test
    public void testMoveToTopWhileVisible() {
        final Transition transition = createTestTransition(TRANSIT_OPEN);
        final ArrayMap<WindowContainer, Transition.ChangeInfo> changes = transition.mChanges;
        final ArraySet<WindowContainer> participants = transition.mParticipants;

        // Start with taskB on top and taskA on bottom but both visible.
        final Task rootTaskA = createTask(mDisplayContent);
        final Task leafTaskA = createTaskInRootTask(rootTaskA, 0 /* userId */);
        final Task taskB = createTask(mDisplayContent);
        leafTaskA.setVisibleRequested(true);
        taskB.setVisibleRequested(true);
        // manually collect since this is a test transition and not known by transitionController.
        transition.collect(leafTaskA);
        rootTaskA.moveToFront("test", leafTaskA);

        // All the tasks were already visible, so there shouldn't be any changes
        ArrayList<Transition.ChangeInfo> targets = Transition.calculateTargets(
                participants, changes);
        assertTrue(targets.isEmpty());

        // After collecting order changes, it should recognize that a task moved to top.
        transition.collectOrderChanges(true);
        targets = Transition.calculateTargets(participants, changes);
        assertEquals(1, targets.size());

        // Make sure the flag is set
        final TransitionInfo info = Transition.calculateTransitionInfo(
                transition.mType, 0 /* flags */, targets, mMockT);
        assertTrue((info.getChanges().get(0).getFlags() & TransitionInfo.FLAG_MOVED_TO_TOP) != 0);
        assertEquals(TRANSIT_CHANGE, info.getChanges().get(0).getMode());
    }

    private class OrderChangeTestSetup {
        final TransitionController mController;
        final TestTransitionPlayer mPlayer;
        final Transition mTransitA;
        final Transition mTransitB;

        OrderChangeTestSetup() {
            mController = mAtm.getTransitionController();
            mPlayer = registerTestTransitionPlayer();
            mController.setSyncEngine(mWm.mSyncEngine);

            mTransitA = createTestTransition(TRANSIT_OPEN, mController);
            mTransitA.mParallelCollectType = Transition.PARALLEL_TYPE_MUTUAL;
            mTransitB = createTestTransition(TRANSIT_OPEN, mController);
            mTransitB.mParallelCollectType = Transition.PARALLEL_TYPE_MUTUAL;
        }

        void startParallelCollect(boolean activityLevelFirst) {
            // Start with taskB on top and taskA on bottom but both visible.
            final Task taskA = createTask(mDisplayContent);
            taskA.setVisibleRequested(true);
            final ActivityRecord actA = createActivityRecord(taskA);
            final TestWindowState winA = createWindowState(
                    new WindowManager.LayoutParams(TYPE_BASE_APPLICATION), actA);
            actA.addWindow(winA);
            final ActivityRecord actB = createActivityRecord(taskA);
            final TestWindowState winB = createWindowState(
                    new WindowManager.LayoutParams(TYPE_BASE_APPLICATION), actB);
            actB.addWindow(winB);

            final Task taskB = createTask(mDisplayContent);
            actA.setVisibleRequested(true);
            actB.setVisibleRequested(false);
            taskB.setVisibleRequested(true);
            assertTrue(actA.isAttached());

            final Consumer<Boolean> startAndCollectA = (doReady) -> {
                mController.startCollectOrQueue(mTransitA, (deferred) -> {
                });

                // Collect activity-level change into A
                mTransitA.collect(actA);
                actA.setVisibleRequested(false);
                winA.onSyncFinishedDrawing();
                mTransitA.collect(actB);
                actB.setVisibleRequested(true);
                winB.onSyncFinishedDrawing();
                mTransitA.start();
                if (doReady) {
                    mTransitA.setReady(mDisplayContent, true);
                }
            };
            final Consumer<Boolean> startAndCollectB = (doReady) -> {
                mController.startCollectOrQueue(mTransitB, (deferred) -> {
                });
                mTransitB.collect(taskA);
                taskA.moveToFront("test");
                mTransitB.start();
                if (doReady) {
                    mTransitB.setReady(mDisplayContent, true);
                }
            };

            if (activityLevelFirst) {
                startAndCollectA.accept(true);
                startAndCollectB.accept(false);
            } else {
                startAndCollectB.accept(true);
                startAndCollectA.accept(false);
            }
        }
    }

    @Test
    public void testMoveToTopStartAfterReadyAfterParallel() {
        // Start collect activity-only transit A
        // Start collect task transit B in parallel
        // finish A first -> should not include order change from B.
        final OrderChangeTestSetup setup = new OrderChangeTestSetup();
        setup.startParallelCollect(true /* activity first */);

        mWm.mSyncEngine.tryFinishForTest(setup.mTransitA.getSyncId());
        waitUntilHandlersIdle();
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            assertNull(setup.mPlayer.mLastReady.getChanges().get(i).getTaskInfo());
        }

        setup.mTransitB.setAllReady();
        mWm.mSyncEngine.tryFinishForTest(setup.mTransitB.getSyncId());
        waitUntilHandlersIdle();
        boolean hasOrderChange = false;
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = setup.mPlayer.mLastReady.getChanges().get(i);
            if (chg.getTaskInfo() == null) continue;
            hasOrderChange = hasOrderChange || (chg.getFlags() & FLAG_MOVED_TO_TOP) != 0;
        }
        assertTrue(hasOrderChange);
    }

    @Test
    public void testMoveToTopStartAfterReadyBeforeParallel() {
        // Start collect activity-only transit A
        // Start collect task transit B in parallel
        // finish B first -> should include order change
        // then finish A -> should NOT include order change.
        final OrderChangeTestSetup setup = new OrderChangeTestSetup();
        setup.startParallelCollect(true /* activity first */);
        // Make it unready now so that it doesn't get dequeued automatically.
        setup.mTransitA.setReady(mDisplayContent, false);

        // Make task change ready first
        setup.mTransitB.setAllReady();
        mWm.mSyncEngine.tryFinishForTest(setup.mTransitB.getSyncId());
        waitUntilHandlersIdle();
        boolean hasOrderChange = false;
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = setup.mPlayer.mLastReady.getChanges().get(i);
            if (chg.getTaskInfo() == null) continue;
            hasOrderChange = hasOrderChange || (chg.getFlags() & FLAG_MOVED_TO_TOP) != 0;
        }
        assertTrue(hasOrderChange);

        setup.mTransitA.setAllReady();
        mWm.mSyncEngine.tryFinishForTest(setup.mTransitA.getSyncId());
        waitUntilHandlersIdle();
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            assertNull(setup.mPlayer.mLastReady.getChanges().get(i).getTaskInfo());
        }
    }

    @Test
    public void testMoveToTopStartBeforeReadyAfterParallel() {
        // Start collect task transit B
        // Start collect activity-only transit A in parallel
        // finish A first -> should not include order change from B.
        final OrderChangeTestSetup setup = new OrderChangeTestSetup();
        setup.startParallelCollect(false /* activity first */);
        // Make B unready now so that it doesn't get dequeued automatically.
        setup.mTransitB.setReady(mDisplayContent, false);

        setup.mTransitA.setAllReady();
        mWm.mSyncEngine.tryFinishForTest(setup.mTransitA.getSyncId());
        waitUntilHandlersIdle();
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            assertNull(setup.mPlayer.mLastReady.getChanges().get(i).getTaskInfo());
        }

        setup.mTransitB.setAllReady();
        mWm.mSyncEngine.tryFinishForTest(setup.mTransitB.getSyncId());
        waitUntilHandlersIdle();
        boolean hasOrderChange = false;
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = setup.mPlayer.mLastReady.getChanges().get(i);
            if (chg.getTaskInfo() == null) continue;
            hasOrderChange = hasOrderChange || (chg.getFlags() & FLAG_MOVED_TO_TOP) != 0;
        }
        assertTrue(hasOrderChange);
    }

    @Test
    public void testMoveToTopStartBeforeReadyBeforeParallel() {
        // Start collect task transit B
        // Start collect activity-only transit A in parallel
        // finish B first -> should include order change
        // then finish A -> should NOT include order change.
        final OrderChangeTestSetup setup = new OrderChangeTestSetup();
        setup.startParallelCollect(false /* activity first */);

        mWm.mSyncEngine.tryFinishForTest(setup.mTransitB.getSyncId());
        waitUntilHandlersIdle();
        boolean hasOrderChange = false;
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            final TransitionInfo.Change chg = setup.mPlayer.mLastReady.getChanges().get(i);
            if (chg.getTaskInfo() == null) continue;
            hasOrderChange = hasOrderChange || (chg.getFlags() & FLAG_MOVED_TO_TOP) != 0;
        }
        assertTrue(hasOrderChange);

        setup.mTransitA.setAllReady();
        mWm.mSyncEngine.tryFinishForTest(setup.mTransitA.getSyncId());
        waitUntilHandlersIdle();
        for (int i = 0; i < setup.mPlayer.mLastReady.getChanges().size(); ++i) {
            assertNull(setup.mPlayer.mLastReady.getChanges().get(i).getTaskInfo());
        }
    }

    @Test
    public void testQueueStartCollect() {
        final TransitionController controller = mAtm.getTransitionController();
        final TestTransitionPlayer player = registerTestTransitionPlayer();

        mSyncEngine = createTestBLASTSyncEngine();
        controller.setSyncEngine(mSyncEngine);

        final Transition transitA = createTestTransition(TRANSIT_OPEN, controller);
        final Transition transitB = createTestTransition(TRANSIT_OPEN, controller);
        final Transition transitC = createTestTransition(TRANSIT_OPEN, controller);

        final boolean[] onStartA = new boolean[]{false, false};
        final boolean[] onStartB = new boolean[]{false, false};
        controller.startCollectOrQueue(transitA, (deferred) -> {
            onStartA[0] = true;
            onStartA[1] = deferred;
        });
        controller.startCollectOrQueue(transitB, (deferred) -> {
            onStartB[0] = true;
            onStartB[1] = deferred;
        });
        waitUntilHandlersIdle();

        assertTrue(onStartA[0]);
        assertFalse(onStartA[1]);
        assertTrue(transitA.isCollecting());

        // B should be queued, so no calls yet
        assertFalse(onStartB[0]);
        assertTrue(transitB.isPending());

        // finish collecting A
        transitA.start();
        transitA.setAllReady();
        mSyncEngine.tryFinishForTest(transitA.getSyncId());
        waitUntilHandlersIdle();

        assertTrue(transitA.isPlaying());
        assertTrue(transitB.isCollecting());
        assertTrue(onStartB[0]);
        // Should receive deferred = true
        assertTrue(onStartB[1]);

        // finish collecting B
        transitB.start();
        transitB.setAllReady();
        mSyncEngine.tryFinishForTest(transitB.getSyncId());
        assertTrue(transitB.isPlaying());

        // Now we should be able to start collecting directly a new transition
        final boolean[] onStartC = new boolean[]{false, false};
        controller.startCollectOrQueue(transitC, (deferred) -> {
            onStartC[0] = true;
            onStartC[1] = deferred;
        });
        waitUntilHandlersIdle();
        assertTrue(onStartC[0]);
        assertFalse(onStartC[1]);
        assertTrue(transitC.isCollecting());
    }

    @Test
    public void testQueueWithLegacy() {
        final TransitionController controller = mAtm.getTransitionController();
        final TestTransitionPlayer player = registerTestTransitionPlayer();

        mSyncEngine = createTestBLASTSyncEngine();
        controller.setSyncEngine(mSyncEngine);

        final Transition transitA = createTestTransition(TRANSIT_OPEN, controller);
        final Transition transitB = createTestTransition(TRANSIT_OPEN, controller);

        controller.startCollectOrQueue(transitA, (deferred) -> {});

        BLASTSyncEngine.SyncGroup legacySync = mSyncEngine.prepareSyncSet(
                mock(BLASTSyncEngine.TransactionReadyListener.class), "test");
        final boolean[] applyLegacy = new boolean[2];
        controller.startLegacySyncOrQueue(legacySync, (deferred) -> {
            applyLegacy[0] = true;
            applyLegacy[1] = deferred;
        });
        assertFalse(applyLegacy[0]);
        waitUntilHandlersIdle();

        controller.startCollectOrQueue(transitB, (deferred) -> {});
        assertTrue(transitA.isCollecting());

        // finish collecting A
        transitA.start();
        transitA.setAllReady();
        mSyncEngine.tryFinishForTest(transitA.getSyncId());
        waitUntilHandlersIdle();

        assertTrue(transitA.isPlaying());
        // legacy sync should start now
        assertTrue(applyLegacy[0]);
        assertTrue(applyLegacy[1]);
        // transitB must wait
        assertTrue(transitB.isPending());

        // finish legacy sync
        mSyncEngine.setReady(legacySync.mSyncId);
        mSyncEngine.tryFinishForTest(legacySync.mSyncId);
        // transitioncontroller should be notified so it can start collecting B
        assertTrue(transitB.isCollecting());
    }

    @Test
    public void testQueueParallel() {
        final TransitionController controller = mAtm.getTransitionController();
        final TestTransitionPlayer player = registerTestTransitionPlayer();

        mSyncEngine = createTestBLASTSyncEngine();
        controller.setSyncEngine(mSyncEngine);

        final Transition transitA = createTestTransition(TRANSIT_OPEN, controller);
        transitA.mParallelCollectType = Transition.PARALLEL_TYPE_MUTUAL;
        final Transition transitB = createTestTransition(TRANSIT_OPEN, controller);
        transitB.mParallelCollectType = Transition.PARALLEL_TYPE_MUTUAL;
        final Transition transitC = createTestTransition(TRANSIT_OPEN, controller);
        transitC.mParallelCollectType = Transition.PARALLEL_TYPE_MUTUAL;
        final Transition transitSync = createTestTransition(TRANSIT_OPEN, controller);
        final Transition transitD = createTestTransition(TRANSIT_OPEN, controller);

        controller.startCollectOrQueue(transitA, (deferred) -> {});
        controller.startCollectOrQueue(transitB, (deferred) -> {});
        controller.startCollectOrQueue(transitC, (deferred) -> {});
        controller.startCollectOrQueue(transitSync, (deferred) -> {});
        controller.startCollectOrQueue(transitD, (deferred) -> {});

        assertTrue(transitA.isCollecting() && !transitA.isStarted());
        // We still serialize on readiness
        assertTrue(transitB.isPending());
        assertTrue(transitC.isPending());

        transitA.start();
        transitA.setAllReady();
        transitB.start();
        transitB.setAllReady();

        // A, B, and C should be collecting in parallel now.
        assertTrue(transitA.isStarted());
        assertTrue(transitB.isStarted());
        assertTrue(transitC.isCollecting() && !transitC.isStarted());

        transitC.start();
        transitC.setAllReady();

        assertTrue(transitA.isStarted());
        assertTrue(transitB.isStarted());
        assertTrue(transitC.isStarted());
        // Not parallel so should remain pending
        assertTrue(transitSync.isPending());
        // After Sync, so should also remain pending.
        assertTrue(transitD.isPending());
        // There should always be a collector, since Sync can't collect yet, C should remain.
        assertEquals(transitC, controller.getCollectingTransition());

        mSyncEngine.tryFinishForTest(transitB.getSyncId());

        // The other transitions should remain waiting.
        assertTrue(transitA.isStarted());
        assertTrue(transitB.isPlaying());
        assertTrue(transitC.isStarted());
        assertEquals(transitC, controller.getCollectingTransition());

        mSyncEngine.tryFinishForTest(transitC.getSyncId());
        assertTrue(transitA.isStarted());
        assertTrue(transitC.isPlaying());
        // The "collecting" one became ready, so the first "waiting" should move back to collecting.
        assertEquals(transitA, controller.getCollectingTransition());

        assertTrue(transitSync.isPending());
        assertTrue(transitD.isPending());
        mSyncEngine.tryFinishForTest(transitA.getSyncId());

        // Now all collectors are done, so sync can be pulled-off the queue.
        assertTrue(transitSync.isCollecting() && !transitSync.isStarted());
        transitSync.start();
        transitSync.setAllReady();
        // Since D can run in parallel, it should be pulled-off the queue.
        assertTrue(transitSync.isStarted());
        assertTrue(transitD.isPending());

        mSyncEngine.tryFinishForTest(transitSync.getSyncId());
        assertTrue(transitD.isCollecting());

        transitD.start();
        transitD.setAllReady();
        mSyncEngine.tryFinishForTest(transitD.getSyncId());

        // Now nothing should be collecting
        assertFalse(controller.isCollecting());
    }

    @Test
    public void testNoSyncFlagIfOneTrack() {
        final TransitionController controller = mAtm.getTransitionController();
        final TestTransitionPlayer player = registerTestTransitionPlayer();

        mSyncEngine = createTestBLASTSyncEngine();
        controller.setSyncEngine(mSyncEngine);

        final Transition transitA = createTestTransition(TRANSIT_OPEN, controller);
        final Transition transitB = createTestTransition(TRANSIT_OPEN, controller);
        final Transition transitC = createTestTransition(TRANSIT_OPEN, controller);

        controller.startCollectOrQueue(transitA, (deferred) -> {});
        controller.startCollectOrQueue(transitB, (deferred) -> {});
        controller.startCollectOrQueue(transitC, (deferred) -> {});

        // Verify that, as-long as there is <= 1 track, we won't get a SYNC flag
        transitA.start();
        transitA.setAllReady();
        mSyncEngine.tryFinishForTest(transitA.getSyncId());
        assertTrue((player.mLastReady.getFlags() & FLAG_SYNC) == 0);
        transitB.start();
        transitB.setAllReady();
        mSyncEngine.tryFinishForTest(transitB.getSyncId());
        assertTrue((player.mLastReady.getFlags() & FLAG_SYNC) == 0);
        transitC.start();
        transitC.setAllReady();
        mSyncEngine.tryFinishForTest(transitC.getSyncId());
        assertTrue((player.mLastReady.getFlags() & FLAG_SYNC) == 0);
    }

    @Test
    public void testTransitionsTriggerPerformanceHints() {
        final boolean explicitRefreshRateHints = explicitRefreshRateHints();
        final var session = new SystemPerformanceHinter.HighPerfSession[1];
        if (explicitRefreshRateHints) {
            final SystemPerformanceHinter perfHinter = mWm.mSystemPerformanceHinter;
            spyOn(perfHinter);
            doAnswer(invocation -> {
                session[0] = (SystemPerformanceHinter.HighPerfSession) invocation.callRealMethod();
                spyOn(session[0]);
                return session[0];
            }).when(perfHinter).createSession(anyInt(), anyInt(), anyString());
        }
        final TransitionController controller = mDisplayContent.mTransitionController;
        final TestTransitionPlayer player = registerTestTransitionPlayer();
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Transition transitA = createTestTransition(TRANSIT_OPEN, controller);
        controller.moveToCollecting(transitA);
        transitA.collectExistenceChange(app);
        controller.requestStartTransition(transitA, app.getTask(),
                null /* remoteTransition */, null /* displayChange */);
        player.start();

        verify(mDisplayContent).enableHighPerfTransition(true);
        if (explicitRefreshRateHints) {
            verify(session[0]).start();
        }

        player.finish();
        verify(mDisplayContent).enableHighPerfTransition(false);
        if (explicitRefreshRateHints) {
            verify(session[0]).close();
        }
    }

    @Test
    public void testReadyTrackerBasics() {
        final TransitionController controller = new TestTransitionController(
                mock(ActivityTaskManagerService.class));
        controller.setFullReadyTrackingForTest(true);
        Transition transit = createTestTransition(TRANSIT_OPEN, controller);
        // Not ready if nothing has happened yet
        assertFalse(transit.mReadyTracker.isReady());

        Transition.ReadyCondition condition1 = new Transition.ReadyCondition("c1");
        transit.mReadyTracker.add(condition1);
        assertFalse(transit.mReadyTracker.isReady());

        Transition.ReadyCondition condition2 = new Transition.ReadyCondition("c2");
        transit.mReadyTracker.add(condition2);
        assertFalse(transit.mReadyTracker.isReady());

        condition2.meet();
        assertFalse(transit.mReadyTracker.isReady());

        condition1.meet();
        assertTrue(transit.mReadyTracker.isReady());
    }

    @Test
    public void testReadyTrackerAlternate() {
        final TransitionController controller = new TestTransitionController(
                mock(ActivityTaskManagerService.class));
        controller.setFullReadyTrackingForTest(true);
        Transition transit = createTestTransition(TRANSIT_OPEN, controller);

        Transition.ReadyCondition condition1 = new Transition.ReadyCondition("c1");
        transit.mReadyTracker.add(condition1);
        assertFalse(transit.mReadyTracker.isReady());

        condition1.meetAlternate("reason1");
        assertTrue(transit.mReadyTracker.isReady());
        assertEquals("reason1", condition1.mAlternate);
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
