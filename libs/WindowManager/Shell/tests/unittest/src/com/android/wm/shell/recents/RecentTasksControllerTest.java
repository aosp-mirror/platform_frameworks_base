/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.recents;

import static android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.launcher3.Flags.FLAG_ENABLE_USE_TOP_VISIBLE_ACTIVITY_FOR_EXCLUDE_FROM_RECENT_TASK;
import static com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE;
import static com.android.wm.shell.shared.split.SplitScreenConstants.SNAP_TO_2_50_50;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.lang.Integer.MAX_VALUE;

import android.app.ActivityManager;
import android.app.ActivityManager.RecentTaskInfo;
import android.app.ActivityTaskManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.window.flags.Flags;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.desktopmode.DesktopRepository;
import com.android.wm.shell.desktopmode.DesktopUserRepositories;
import com.android.wm.shell.desktopmode.DesktopWallpaperActivity;
import com.android.wm.shell.shared.GroupedTaskInfo;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;
import com.android.wm.shell.shared.split.SplitBounds;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Tests for {@link RecentTasksController}
 *
 * Usage: atest WMShellUnitTests:RecentTasksControllerTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class RecentTasksControllerTest extends ShellTestCase {

    @Mock
    private Context mContext;
    @Mock
    private TaskStackListenerImpl mTaskStackListener;
    @Mock
    private ShellCommandHandler mShellCommandHandler;
    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private DisplayInsetsController mDisplayInsetsController;
    @Mock
    private IRecentTasksListener mRecentTasksListener;
    @Mock
    private TaskStackTransitionObserver mTaskStackTransitionObserver;
    @Mock
    private DesktopUserRepositories mDesktopUserRepositories;
    @Mock
    private DesktopRepository mDesktopRepository;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private ShellTaskOrganizer mShellTaskOrganizer;
    private RecentTasksController mRecentTasksController;
    private RecentTasksController mRecentTasksControllerReal;
    private ShellInit mShellInit;
    private ShellController mShellController;
    private TestShellExecutor mMainExecutor;
    private static StaticMockitoSession sMockitoSession;

    @Before
    public void setUp() {
        sMockitoSession = mockitoSession().initMocks(this).strictness(Strictness.LENIENT)
                .mockStatic(DesktopModeStatus.class).startMocking();
        ExtendedMockito.doReturn(true)
                .when(() -> DesktopModeStatus.canEnterDesktopMode(any()));

        mMainExecutor = new TestShellExecutor();
        when(mDesktopUserRepositories.getCurrent()).thenReturn(mDesktopRepository);
        when(mDesktopUserRepositories.getProfile(anyInt())).thenReturn(mDesktopRepository);
        when(mContext.getPackageManager()).thenReturn(mock(PackageManager.class));
        when(mContext.getSystemService(KeyguardManager.class))
                .thenReturn(mock(KeyguardManager.class));
        mShellInit = spy(new ShellInit(mMainExecutor));
        mShellController = spy(new ShellController(mContext, mShellInit, mShellCommandHandler,
                mDisplayInsetsController, mMainExecutor));
        mRecentTasksControllerReal = new RecentTasksController(mContext, mShellInit,
                mShellController, mShellCommandHandler, mTaskStackListener, mActivityTaskManager,
                Optional.of(mDesktopUserRepositories), mTaskStackTransitionObserver,
                mMainExecutor);
        mRecentTasksController = spy(mRecentTasksControllerReal);
        mShellTaskOrganizer = new ShellTaskOrganizer(mShellInit, mShellCommandHandler,
                null /* sizeCompatUI */, Optional.empty(), Optional.of(mRecentTasksController),
                mMainExecutor);
        mShellInit.init();
    }

    @After
    public void tearDown() {
        sMockitoSession.finishMocking();
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), isA(RecentTasksController.class));
    }

    @Test
    public void instantiateController_addDumpCallback() {
        verify(mShellCommandHandler, times(1)).addDumpCallback(any(),
                isA(RecentTasksController.class));
    }

    @Test
    public void instantiateController_addExternalInterface() {
        verify(mShellController, times(1)).addExternalInterface(
                eq(IRecentTasks.DESCRIPTOR), any(), any());
    }

    @Test
    public void instantiateController_initializesRepository() {
        verify(mDesktopUserRepositories, times(1)).getCurrent();
        verify(mDesktopRepository, times(1)).addActiveTaskListener(any());
    }

    @Test
    public void testInvalidateExternalInterface_unregistersListener() {
        // Note: We have to use the real instance of the controller here since that is the instance
        // that is passed to ShellController internally, and the instance that the listener will be
        // unregistered from
        mRecentTasksControllerReal.registerRecentTasksListener(new IRecentTasksListener.Default());
        assertTrue(mRecentTasksControllerReal.hasRecentTasksListener());
        // Create initial interface
        mShellController.createExternalInterfaces(new Bundle());
        // Recreate the interface to trigger invalidation of the previous instance
        mShellController.createExternalInterfaces(new Bundle());
        assertFalse(mRecentTasksControllerReal.hasRecentTasksListener());
    }

    @Test
    public void testAddRemoveSplitNotifyChange() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        setRawList(t1, t2);

        mRecentTasksController.addSplitPair(t1.taskId, t2.taskId, mock(SplitBounds.class));
        verify(mRecentTasksController).notifyRecentTasksChanged();

        reset(mRecentTasksController);
        mRecentTasksController.removeSplitPair(t1.taskId);
        verify(mRecentTasksController).notifyRecentTasksChanged();
    }

    @Test
    public void testAddSameSplitBoundsInfoSkipNotifyChange() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        setRawList(t1, t2);

        // Verify only one update if the split info is the same
        SplitBounds bounds1 = new SplitBounds(new Rect(0, 0, 50, 50),
                new Rect(50, 50, 100, 100), t1.taskId, t2.taskId, SNAP_TO_2_50_50);
        mRecentTasksController.addSplitPair(t1.taskId, t2.taskId, bounds1);
        SplitBounds bounds2 = new SplitBounds(new Rect(0, 0, 50, 50),
                new Rect(50, 50, 100, 100), t1.taskId, t2.taskId, SNAP_TO_2_50_50);
        mRecentTasksController.addSplitPair(t1.taskId, t2.taskId, bounds2);
        verify(mRecentTasksController, times(1)).notifyRecentTasksChanged();
    }

    @Test
    public void testGetRecentTasks() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        setRawList(t1, t2, t3);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);
        assertGroupedTasksListEquals(recentTasks,
                t1.taskId, -1,
                t2.taskId, -1,
                t3.taskId, -1);
    }

    @EnableFlags(FLAG_ENABLE_USE_TOP_VISIBLE_ACTIVITY_FOR_EXCLUDE_FROM_RECENT_TASK)
    @Test
    public void testGetRecentTasks_removesDesktopWallpaperActivity() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo desktopWallpaperTaskInfo = makeDesktopWallpaperTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        setRawList(t1, desktopWallpaperTaskInfo, t3);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);
        assertGroupedTasksListEquals(recentTasks, t1.taskId, -1, t3.taskId, -1);
    }

    @Test
    public void testGetRecentTasks_withPairs() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        RecentTaskInfo t4 = makeTaskInfo(4);
        RecentTaskInfo t5 = makeTaskInfo(5);
        RecentTaskInfo t6 = makeTaskInfo(6);
        setRawList(t1, t2, t3, t4, t5, t6);

        // Mark a couple pairs [t2, t4], [t3, t5]
        SplitBounds pair1Bounds =
                new SplitBounds(new Rect(), new Rect(), 2, 4, SNAP_TO_2_50_50);
        SplitBounds pair2Bounds =
                new SplitBounds(new Rect(), new Rect(), 3, 5, SNAP_TO_2_50_50);

        mRecentTasksController.addSplitPair(t2.taskId, t4.taskId, pair1Bounds);
        mRecentTasksController.addSplitPair(t3.taskId, t5.taskId, pair2Bounds);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);
        assertGroupedTasksListEquals(recentTasks,
                t1.taskId, -1,
                t2.taskId, t4.taskId,
                t3.taskId, t5.taskId,
                t6.taskId, -1);
    }

    @Test
    public void testGetRecentTasks_ReturnsRecentTasksAsynchronously() {
        @SuppressWarnings("unchecked")
        final List<GroupedTaskInfo>[] recentTasks = new List[1];
        Consumer<List<GroupedTaskInfo>> consumer = argument -> recentTasks[0] = argument;
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        RecentTaskInfo t4 = makeTaskInfo(4);
        RecentTaskInfo t5 = makeTaskInfo(5);
        RecentTaskInfo t6 = makeTaskInfo(6);
        setRawList(t1, t2, t3, t4, t5, t6);

        // Mark a couple pairs [t2, t4], [t3, t5]
        SplitBounds pair1Bounds =
                new SplitBounds(new Rect(), new Rect(), 2, 4, SNAP_TO_2_50_50);
        SplitBounds pair2Bounds =
                new SplitBounds(new Rect(), new Rect(), 3, 5, SNAP_TO_2_50_50);

        mRecentTasksController.addSplitPair(t2.taskId, t4.taskId, pair1Bounds);
        mRecentTasksController.addSplitPair(t3.taskId, t5.taskId, pair2Bounds);

        mRecentTasksController.asRecentTasks()
                .getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0, Runnable::run,
                        consumer);
        mMainExecutor.flushAll();

        assertGroupedTasksListEquals(recentTasks[0],
                t1.taskId, -1,
                t2.taskId, t4.taskId,
                t3.taskId, t5.taskId,
                t6.taskId, -1);
    }

    @Test
    public void testGetRecentTasks_hasActiveDesktopTasks_proto2Enabled_groupFreeformTasks() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        RecentTaskInfo t4 = makeTaskInfo(4);
        setRawList(t1, t2, t3, t4);

        when(mDesktopUserRepositories.getCurrent().isActiveTask(1)).thenReturn(true);
        when(mDesktopUserRepositories.getCurrent().isActiveTask(3)).thenReturn(true);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);

        // 2 freeform tasks should be grouped into one, 3 total recents entries
        assertEquals(3, recentTasks.size());
        GroupedTaskInfo freeformGroup = recentTasks.get(0);
        GroupedTaskInfo singleGroup1 = recentTasks.get(1);
        GroupedTaskInfo singleGroup2 = recentTasks.get(2);

        // Check that groups have expected types
        assertEquals(GroupedTaskInfo.TYPE_FREEFORM, freeformGroup.getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, singleGroup1.getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, singleGroup2.getType());

        // Check freeform group entries
        assertEquals(t1, freeformGroup.getTaskInfoList().get(0));
        assertEquals(t3, freeformGroup.getTaskInfoList().get(1));

        // Check single entries
        assertEquals(t2, singleGroup1.getTaskInfo1());
        assertEquals(t4, singleGroup2.getTaskInfo1());
    }

    @Test
    public void testGetRecentTasks_hasActiveDesktopTasks_proto2Enabled_freeformTaskOrder() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        RecentTaskInfo t4 = makeTaskInfo(4);
        RecentTaskInfo t5 = makeTaskInfo(5);
        setRawList(t1, t2, t3, t4, t5);

        SplitBounds pair1Bounds =
                new SplitBounds(new Rect(), new Rect(), 1, 2, SNAP_TO_2_50_50);
        mRecentTasksController.addSplitPair(t1.taskId, t2.taskId, pair1Bounds);

        when(mDesktopUserRepositories.getCurrent().isActiveTask(3)).thenReturn(true);
        when(mDesktopUserRepositories.getCurrent().isActiveTask(5)).thenReturn(true);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);

        // 2 split screen tasks grouped, 2 freeform tasks grouped, 3 total recents entries
        assertEquals(3, recentTasks.size());
        GroupedTaskInfo splitGroup = recentTasks.get(0);
        GroupedTaskInfo freeformGroup = recentTasks.get(1);
        GroupedTaskInfo singleGroup = recentTasks.get(2);

        // Check that groups have expected types
        assertEquals(GroupedTaskInfo.TYPE_SPLIT, splitGroup.getType());
        assertEquals(GroupedTaskInfo.TYPE_FREEFORM, freeformGroup.getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, singleGroup.getType());

        // Check freeform group entries
        assertEquals(t3, freeformGroup.getTaskInfoList().get(0));
        assertEquals(t5, freeformGroup.getTaskInfoList().get(1));

        // Check split group entries
        assertEquals(t1, splitGroup.getTaskInfoList().get(0));
        assertEquals(t2, splitGroup.getTaskInfoList().get(1));

        // Check single entry
        assertEquals(t4, singleGroup.getTaskInfo1());
    }

    @Test
    public void testGetRecentTasks_hasActiveDesktopTasks_proto2Disabled_doNotGroupFreeformTasks() {
        ExtendedMockito.doReturn(false)
                .when(() -> DesktopModeStatus.canEnterDesktopMode(any()));

        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        RecentTaskInfo t4 = makeTaskInfo(4);
        setRawList(t1, t2, t3, t4);

        when(mDesktopUserRepositories.getCurrent().isActiveTask(1)).thenReturn(true);
        when(mDesktopUserRepositories.getCurrent().isActiveTask(3)).thenReturn(true);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);

        // Expect no grouping of tasks
        assertEquals(4, recentTasks.size());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, recentTasks.get(0).getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, recentTasks.get(1).getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, recentTasks.get(2).getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, recentTasks.get(3).getType());

        assertEquals(t1, recentTasks.get(0).getTaskInfo1());
        assertEquals(t2, recentTasks.get(1).getTaskInfo1());
        assertEquals(t3, recentTasks.get(2).getTaskInfo1());
        assertEquals(t4, recentTasks.get(3).getTaskInfo1());
    }

    @Test
    public void testGetRecentTasks_proto2Enabled_includesMinimizedFreeformTasks() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        RecentTaskInfo t4 = makeTaskInfo(4);
        RecentTaskInfo t5 = makeTaskInfo(5);
        setRawList(t1, t2, t3, t4, t5);

        when(mDesktopRepository.isActiveTask(1)).thenReturn(true);
        when(mDesktopRepository.isActiveTask(2)).thenReturn(false);
        when(mDesktopRepository.isActiveTask(3)).thenReturn(true);
        when(mDesktopRepository.isActiveTask(4)).thenReturn(false);
        when(mDesktopRepository.isActiveTask(5)).thenReturn(true);
        when(mDesktopRepository.isMinimizedTask(3)).thenReturn(true);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);

        // 3 freeform tasks should be grouped into one, 2 single tasks, 3 total recents entries
        assertEquals(3, recentTasks.size());
        GroupedTaskInfo freeformGroup = recentTasks.get(0);
        GroupedTaskInfo singleGroup1 = recentTasks.get(1);
        GroupedTaskInfo singleGroup2 = recentTasks.get(2);

        // Check that groups have expected types
        assertEquals(GroupedTaskInfo.TYPE_FREEFORM, freeformGroup.getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, singleGroup1.getType());
        assertEquals(GroupedTaskInfo.TYPE_FULLSCREEN, singleGroup2.getType());

        // Check freeform group entries
        assertEquals(3, freeformGroup.getTaskInfoList().size());
        assertEquals(t1, freeformGroup.getTaskInfoList().get(0));
        assertEquals(t3, freeformGroup.getTaskInfoList().get(1));
        assertEquals(t5, freeformGroup.getTaskInfoList().get(2));

        // Check single entries
        assertEquals(t2, singleGroup1.getTaskInfo1());
        assertEquals(t4, singleGroup2.getTaskInfo1());
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_PERSISTENCE)
    public void testGetRecentTasks_hasDesktopTasks_persistenceEnabled_freeformTaskHaveBoundsSet() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);

        t1.lastNonFullscreenBounds = new Rect(100, 200, 300, 400);
        t2.lastNonFullscreenBounds = new Rect(150, 250, 350, 450);
        setRawList(t1, t2);

        when(mDesktopUserRepositories.getCurrent().isActiveTask(1)).thenReturn(true);
        when(mDesktopUserRepositories.getCurrent().isActiveTask(2)).thenReturn(true);

        ArrayList<GroupedTaskInfo> recentTasks =
                mRecentTasksController.getRecentTasks(MAX_VALUE, RECENT_IGNORE_UNAVAILABLE, 0);

        assertEquals(1, recentTasks.size());
        GroupedTaskInfo freeformGroup = recentTasks.get(0);

        // Check bounds
        assertEquals(t1.lastNonFullscreenBounds, freeformGroup.getTaskInfoList().get(
                0).configuration.windowConfiguration.getAppBounds());
        assertEquals(t2.lastNonFullscreenBounds, freeformGroup.getTaskInfoList().get(
                1).configuration.windowConfiguration.getAppBounds());

        // Check position in parent
        assertEquals(new Point(t1.lastNonFullscreenBounds.left,
                        t1.lastNonFullscreenBounds.top),
                freeformGroup.getTaskInfoList().get(0).positionInParent);
        assertEquals(new Point(t2.lastNonFullscreenBounds.left,
                        t2.lastNonFullscreenBounds.top),
                freeformGroup.getTaskInfoList().get(1).positionInParent);
    }

    @Test
    public void testRemovedTaskRemovesSplit() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        RecentTaskInfo t2 = makeTaskInfo(2);
        RecentTaskInfo t3 = makeTaskInfo(3);
        setRawList(t1, t2, t3);

        // Add a pair
        SplitBounds pair1Bounds =
                new SplitBounds(new Rect(), new Rect(), 2, 3, SNAP_TO_2_50_50);
        mRecentTasksController.addSplitPair(t2.taskId, t3.taskId, pair1Bounds);
        reset(mRecentTasksController);

        // Remove one of the tasks and ensure the pair is removed
        SurfaceControl mockLeash = mock(SurfaceControl.class);
        ActivityManager.RunningTaskInfo rt2 = makeRunningTaskInfo(2);
        mShellTaskOrganizer.onTaskAppeared(rt2, mockLeash);
        mShellTaskOrganizer.onTaskVanished(rt2);

        verify(mRecentTasksController).removeSplitPair(t2.taskId);
    }

    @Test
    public void testTaskWindowingModeChangedNotifiesChange() {
        RecentTaskInfo t1 = makeTaskInfo(1);
        setRawList(t1);

        // Remove one of the tasks and ensure the pair is removed
        SurfaceControl mockLeash = mock(SurfaceControl.class);
        ActivityManager.RunningTaskInfo rt2Fullscreen = makeRunningTaskInfo(2);
        rt2Fullscreen.configuration.windowConfiguration.setWindowingMode(
                WINDOWING_MODE_FULLSCREEN);
        mShellTaskOrganizer.onTaskAppeared(rt2Fullscreen, mockLeash);

        // Change the windowing mode and ensure the recent tasks change is notified
        ActivityManager.RunningTaskInfo rt2MultiWIndow = makeRunningTaskInfo(2);
        rt2MultiWIndow.configuration.windowConfiguration.setWindowingMode(
                WINDOWING_MODE_MULTI_WINDOW);
        mShellTaskOrganizer.onTaskInfoChanged(rt2MultiWIndow);

        verify(mRecentTasksController).notifyRecentTasksChanged();
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS})
    public void onTaskAdded_desktopModeRunningAppsEnabled_triggersOnRunningTaskAppeared()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskAdded(taskInfo);

        verify(mRecentTasksListener).onRunningTaskAppeared(taskInfo);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS)
    public void onTaskAdded_desktopModeRunningAppsDisabled_doesNotTriggerOnRunningTaskAppeared()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskAdded(taskInfo);

        verify(mRecentTasksListener, never()).onRunningTaskAppeared(any());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS})
    public void taskWindowingModeChanged_desktopRunningAppsEnabled_triggersOnRunningTaskChanged()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskRunningInfoChanged(taskInfo);

        verify(mRecentTasksListener).onRunningTaskChanged(taskInfo);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS)
    public void
            taskWindowingModeChanged_desktopRunningAppsDisabled_doesNotTriggerOnRunningTaskChanged()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskRunningInfoChanged(taskInfo);

        verify(mRecentTasksListener, never()).onRunningTaskChanged(any());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE,
            Flags.FLAG_ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS})
    public void onTaskRemoved_desktopModeRunningAppsEnabled_triggersOnRunningTaskVanished()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskRemoved(taskInfo);

        verify(mRecentTasksListener).onRunningTaskVanished(taskInfo);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    @DisableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_TASKBAR_RUNNING_APPS)
    public void onTaskRemoved_desktopModeRunningAppsDisabled_doesNotTriggerOnRunningTaskVanished()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskRemoved(taskInfo);

        verify(mRecentTasksListener, never()).onRunningTaskVanished(any());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TASK_STACK_OBSERVER_IN_SHELL)
    public void onTaskMovedToFront_TaskStackObserverEnabled_triggersOnTaskMovedToFront()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskMovedToFrontThroughTransition(taskInfo);

        GroupedTaskInfo runningTask = GroupedTaskInfo.forFullscreenTasks(taskInfo);
        verify(mRecentTasksListener).onTaskMovedToFront(eq(runningTask));
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TASK_STACK_OBSERVER_IN_SHELL)
    public void onTaskMovedToFront_TaskStackObserverEnabled_doesNotTriggersOnTaskMovedToFront()
            throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);

        mRecentTasksControllerReal.onTaskMovedToFront(taskInfo);

        verify(mRecentTasksListener, never()).onTaskMovedToFront(any());
    }

    @Test
    public void getNullSplitBoundsNonSplitTask() {
        SplitBounds sb = mRecentTasksController.getSplitBoundsForTaskId(3);
        assertNull("splitBounds should be null for non-split task", sb);
    }

    @Test
    public void getNullSplitBoundsInvalidTask() {
        SplitBounds sb = mRecentTasksController.getSplitBoundsForTaskId(INVALID_TASK_ID);
        assertNull("splitBounds should be null for invalid taskID", sb);
    }

    @Test
    public void getSplitBoundsForSplitTask() {
        SplitBounds pair1Bounds = mock(SplitBounds.class);
        SplitBounds pair2Bounds = mock(SplitBounds.class);

        mRecentTasksController.addSplitPair(1, 2, pair1Bounds);
        mRecentTasksController.addSplitPair(4, 3, pair2Bounds);

        SplitBounds splitBounds2 = mRecentTasksController.getSplitBoundsForTaskId(2);
        SplitBounds splitBounds1 = mRecentTasksController.getSplitBoundsForTaskId(1);
        assertEquals("Different splitBounds for same pair", splitBounds1, splitBounds2);
        assertEquals(splitBounds1, pair1Bounds);

        SplitBounds splitBounds3 = mRecentTasksController.getSplitBoundsForTaskId(3);
        SplitBounds splitBounds4 = mRecentTasksController.getSplitBoundsForTaskId(4);
        assertEquals("Different splitBounds for same pair", splitBounds3, splitBounds4);
        assertEquals(splitBounds4, pair2Bounds);
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TASK_STACK_OBSERVER_IN_SHELL)
    @EnableFlags(com.android.wm.shell.Flags.FLAG_ENABLE_SHELL_TOP_TASK_TRACKING)
    public void shellTopTaskTracker_onTaskStackChanged_expectNoRecentsChanged() throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        mRecentTasksControllerReal.onTaskStackChanged();
        verify(mRecentTasksListener, never()).onRecentTasksChanged();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TASK_STACK_OBSERVER_IN_SHELL)
    @EnableFlags(com.android.wm.shell.Flags.FLAG_ENABLE_SHELL_TOP_TASK_TRACKING)
    public void shellTopTaskTracker_onTaskRemoved_expectNoRecentsChanged() throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);
        mRecentTasksControllerReal.onTaskRemoved(taskInfo);
        verify(mRecentTasksListener, never()).onRecentTasksChanged();
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TASK_STACK_OBSERVER_IN_SHELL)
    @EnableFlags(com.android.wm.shell.Flags.FLAG_ENABLE_SHELL_TOP_TASK_TRACKING)
    public void shellTopTaskTracker_onVisibleTasksChanged() throws Exception {
        mRecentTasksControllerReal.registerRecentTasksListener(mRecentTasksListener);
        ActivityManager.RunningTaskInfo taskInfo = makeRunningTaskInfo(/* taskId= */10);
        mRecentTasksControllerReal.onVisibleTasksChanged(List.of(taskInfo));
        verify(mRecentTasksListener, never()).onVisibleTasksChanged(any());
    }

    /**
     * Helper to create a task with a given task id.
     */
    private RecentTaskInfo makeTaskInfo(int taskId) {
        RecentTaskInfo info = new RecentTaskInfo();
        info.taskId = taskId;

        Intent intent = new Intent();
        intent.setComponent(new ComponentName("com." + taskId, "Activity" + taskId));
        info.baseIntent = intent;

        info.lastNonFullscreenBounds = new Rect();
        return info;
    }

    /**
     * Helper to create a desktop wallpaper activity with a given task id.
     */
    private RecentTaskInfo makeDesktopWallpaperTaskInfo(int taskId) {
        RecentTaskInfo info = makeTaskInfo(taskId);
        info.baseIntent.setComponent(DesktopWallpaperActivity.getWallpaperActivityComponent());
        return info;
    }

    /**
     * Helper to create a running task with a given task id.
     */
    private ActivityManager.RunningTaskInfo makeRunningTaskInfo(int taskId) {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.taskId = taskId;
        info.realActivity = new ComponentName("testPackage", "testClass");
        return info;
    }

    /**
     * Helper to set the raw task list on the controller.
     */
    private ArrayList<RecentTaskInfo> setRawList(
            RecentTaskInfo... tasks) {
        ArrayList<RecentTaskInfo> rawList = new ArrayList<>();
        for (RecentTaskInfo task : tasks) {
            rawList.add(task);
        }
        doReturn(rawList).when(mActivityTaskManager).getRecentTasks(anyInt(), anyInt(),
                anyInt());
        return rawList;
    }

    /**
     * Asserts that the recent tasks matches the given task ids.
     *
     * @param expectedTaskIds list of task ids that map to the flattened task ids of the tasks in
     *                        the grouped task list
     */
    private void assertGroupedTasksListEquals(List<GroupedTaskInfo> recentTasks,
            int... expectedTaskIds) {
        int[] flattenedTaskIds = new int[recentTasks.size() * 2];
        for (int i = 0; i < recentTasks.size(); i++) {
            GroupedTaskInfo pair = recentTasks.get(i);
            int taskId1 = pair.getTaskInfo1().taskId;
            flattenedTaskIds[2 * i] = taskId1;
            flattenedTaskIds[2 * i + 1] = pair.getTaskInfo2() != null
                    ? pair.getTaskInfo2().taskId
                    : -1;

            if (pair.getTaskInfo2() != null) {
                assertNotNull(pair.getSplitBounds());
                int leftTopTaskId = pair.getSplitBounds().leftTopTaskId;
                int bottomRightTaskId = pair.getSplitBounds().rightBottomTaskId;
                // Unclear if pairs are ordered by split position, most likely not.
                assertTrue(leftTopTaskId == taskId1
                        || leftTopTaskId == pair.getTaskInfo2().taskId);
                assertTrue(bottomRightTaskId == taskId1
                        || bottomRightTaskId == pair.getTaskInfo2().taskId);
            } else {
                assertNull(pair.getSplitBounds());
            }
        }
        assertTrue("Expected: " + Arrays.toString(expectedTaskIds)
                        + " Received: " + Arrays.toString(flattenedTaskIds),
                Arrays.equals(flattenedTaskIds, expectedTaskIds));
    }
}
