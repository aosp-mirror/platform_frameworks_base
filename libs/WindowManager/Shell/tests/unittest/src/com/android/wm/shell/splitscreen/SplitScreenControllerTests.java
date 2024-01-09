/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.splitscreen;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.view.WindowManager.PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.LaunchAdjacentController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.sysui.ShellSharedConstants;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.windowdecor.WindowDecorViewModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link SplitScreenController}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitScreenControllerTests extends ShellTestCase {

    private static final String TEST_PACKAGE = "com.android.wm.shell.splitscreen";
    private static final String TEST_NOT_ALLOWED_PACKAGE = "com.android.wm.shell.splitscreen.fake";
    private static final String TEST_ACTIVITY = "TestActivity";

    @Mock ShellInit mShellInit;
    @Mock ShellCommandHandler mShellCommandHandler;
    @Mock ShellTaskOrganizer mTaskOrganizer;
    @Mock SyncTransactionQueue mSyncQueue;
    @Mock RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    @Mock ShellExecutor mMainExecutor;
    @Mock DisplayController mDisplayController;
    @Mock DisplayImeController mDisplayImeController;
    @Mock DisplayInsetsController mDisplayInsetsController;
    @Mock DragAndDropController mDragAndDropController;
    @Mock Transitions mTransitions;
    @Mock TransactionPool mTransactionPool;
    @Mock IconProvider mIconProvider;
    @Mock StageCoordinator mStageCoordinator;
    @Mock RecentTasksController mRecentTasks;
    @Mock LaunchAdjacentController mLaunchAdjacentController;
    @Mock WindowDecorViewModel mWindowDecorViewModel;
    @Mock DesktopTasksController mDesktopTasksController;
    @Captor ArgumentCaptor<Intent> mIntentCaptor;

    private ShellController mShellController;
    private SplitScreenController mSplitScreenController;

    @Before
    public void setup() {
        assumeTrue(ActivityTaskManager.supportsSplitScreenMultiWindow(mContext));
        MockitoAnnotations.initMocks(this);
        String[] appsSupportingMultiInstance = mContext.getResources()
                .getStringArray(R.array.config_appsSupportMultiInstancesSplit);
        mShellController = spy(new ShellController(mContext, mShellInit, mShellCommandHandler,
                mMainExecutor));
        mSplitScreenController = spy(new SplitScreenController(mContext, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                appsSupportingMultiInstance));
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), isA(SplitScreenController.class));
    }

    @Test
    @UiThreadTest
    public void instantiateController_registerDumpCallback() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellCommandHandler, times(1)).addDumpCallback(any(), any());
    }

    @Test
    @UiThreadTest
    public void instantiateController_registerCommandCallback() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellCommandHandler, times(1)).addCommandCallback(eq("splitscreen"), any(), any());
    }

    @Test
    @UiThreadTest
    public void testControllerRegistersKeyguardChangeListener() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellController, times(1)).addKeyguardChangeListener(any());
    }

    @Test
    @UiThreadTest
    public void instantiateController_addExternalInterface() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellController, times(1)).addExternalInterface(
                eq(ShellSharedConstants.KEY_EXTRA_SHELL_SPLIT_SCREEN), any(), any());
    }

    @Test
    public void testInvalidateExternalInterface_unregistersListener() {
        mSplitScreenController.onInit();
        mSplitScreenController.registerSplitScreenListener(
                new SplitScreen.SplitScreenListener() {});
        verify(mStageCoordinator).registerSplitScreenListener(any());
        // Create initial interface
        mShellController.createExternalInterfaces(new Bundle());
        // Recreate the interface to trigger invalidation of the previous instance
        mShellController.createExternalInterfaces(new Bundle());
        verify(mStageCoordinator).unregisterSplitScreenListener(any());
    }

    @Test
    public void testStartIntent_appendsNoUserActionFlag() {
        Intent startIntent = createStartIntent("startActivity");
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, startIntent, FLAG_IMMUTABLE);

        mSplitScreenController.startIntent(pendingIntent, mContext.getUserId(), null,
                SPLIT_POSITION_TOP_OR_LEFT, null);

        verify(mStageCoordinator).startIntent(eq(pendingIntent), mIntentCaptor.capture(),
                eq(SPLIT_POSITION_TOP_OR_LEFT), isNull());
        assertEquals(FLAG_ACTIVITY_NO_USER_ACTION,
                mIntentCaptor.getValue().getFlags() & FLAG_ACTIVITY_NO_USER_ACTION);
    }

    @Test
    public void startIntent_multiInstancesSupported_appendsMultipleTaskFag() {
        doReturn(true).when(mSplitScreenController).supportsMultiInstanceSplit(any());
        Intent startIntent = createStartIntent("startActivity");
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, startIntent, FLAG_IMMUTABLE);
        // Put the same component to the top running task
        ActivityManager.RunningTaskInfo topRunningTask =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, startIntent);
        doReturn(topRunningTask).when(mRecentTasks).getTopRunningTask();

        mSplitScreenController.startIntent(pendingIntent, mContext.getUserId(), null,
                SPLIT_POSITION_TOP_OR_LEFT, null);

        verify(mStageCoordinator).startIntent(eq(pendingIntent), mIntentCaptor.capture(),
                eq(SPLIT_POSITION_TOP_OR_LEFT), isNull());
        assertEquals(FLAG_ACTIVITY_MULTIPLE_TASK,
                mIntentCaptor.getValue().getFlags() & FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Test
    public void startIntent_multiInstancesNotSupported_startTaskInBackgroundBeforeSplitActivated() {
        doNothing().when(mSplitScreenController).startTask(anyInt(), anyInt(), any());
        Intent startIntent = createStartIntent("startActivity");
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, startIntent, FLAG_IMMUTABLE);
        // Put the same component to the top running task
        ActivityManager.RunningTaskInfo topRunningTask =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, startIntent);
        doReturn(topRunningTask).when(mRecentTasks).getTopRunningTask();
        // Put the same component into a task in the background
        ActivityManager.RecentTaskInfo sameTaskInfo = new ActivityManager.RecentTaskInfo();
        doReturn(sameTaskInfo).when(mRecentTasks).findTaskInBackground(any(), anyInt());

        mSplitScreenController.startIntent(pendingIntent, mContext.getUserId(), null,
                SPLIT_POSITION_TOP_OR_LEFT, null);

        verify(mStageCoordinator).startTask(anyInt(), eq(SPLIT_POSITION_TOP_OR_LEFT),
                isNull());
        verify(mSplitScreenController, never()).supportsMultiInstanceSplit(any());
        verify(mStageCoordinator, never()).switchSplitPosition(any());
    }

    @Test
    public void startIntent_multiInstancesSupported_startTaskInBackgroundAfterSplitActivated() {
        doReturn(true).when(mSplitScreenController).supportsMultiInstanceSplit(any());
        doNothing().when(mSplitScreenController).startTask(anyInt(), anyInt(), any());
        Intent startIntent = createStartIntent("startActivity");
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, startIntent, FLAG_IMMUTABLE);
        // Put the same component into another side of the split
        doReturn(true).when(mSplitScreenController).isSplitScreenVisible();
        ActivityManager.RunningTaskInfo sameTaskInfo =
                createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, startIntent);
        doReturn(sameTaskInfo).when(mSplitScreenController).getTaskInfo(
                SPLIT_POSITION_BOTTOM_OR_RIGHT);
        // Put the same component into a task in the background
        doReturn(new ActivityManager.RecentTaskInfo()).when(mRecentTasks)
                .findTaskInBackground(any(), anyInt());

        mSplitScreenController.startIntent(pendingIntent, mContext.getUserId(), null,
                SPLIT_POSITION_TOP_OR_LEFT, null);
        verify(mSplitScreenController, never()).supportsMultiInstanceSplit(any());
        verify(mStageCoordinator).startTask(anyInt(), eq(SPLIT_POSITION_TOP_OR_LEFT),
                isNull());
    }

    @Test
    public void startIntent_multiInstancesNotSupported_switchesPositionAfterSplitActivated() {
        doReturn(false).when(mSplitScreenController).supportsMultiInstanceSplit(any());
        Intent startIntent = createStartIntent("startActivity");
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, startIntent, FLAG_IMMUTABLE);
        // Put the same component into another side of the split
        doReturn(true).when(mSplitScreenController).isSplitScreenVisible();
        ActivityManager.RunningTaskInfo sameTaskInfo =
                createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, startIntent);
        doReturn(sameTaskInfo).when(mSplitScreenController).getTaskInfo(
                SPLIT_POSITION_BOTTOM_OR_RIGHT);

        mSplitScreenController.startIntent(pendingIntent, mContext.getUserId(), null,
                SPLIT_POSITION_TOP_OR_LEFT, null);

        verify(mStageCoordinator).switchSplitPosition(anyString());
    }

    @Test
    public void supportsMultiInstanceSplit_inStaticAllowList() {
        String[] allowList = { TEST_PACKAGE };
        SplitScreenController controller = new SplitScreenController(mContext, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                allowList);
        ComponentName component = new ComponentName(TEST_PACKAGE, TEST_ACTIVITY);
        assertEquals(true, controller.supportsMultiInstanceSplit(component));
    }

    @Test
    public void supportsMultiInstanceSplit_notInStaticAllowList() {
        String[] allowList = { TEST_PACKAGE };
        SplitScreenController controller = new SplitScreenController(mContext, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                allowList);
        ComponentName component = new ComponentName(TEST_NOT_ALLOWED_PACKAGE, TEST_ACTIVITY);
        assertEquals(false, controller.supportsMultiInstanceSplit(component));
    }

    @Test
    public void supportsMultiInstanceSplit_activityPropertyTrue()
            throws PackageManager.NameNotFoundException {
        Context context = spy(mContext);
        ComponentName component = new ComponentName(TEST_PACKAGE, TEST_ACTIVITY);
        PackageManager pm = mock(PackageManager.class);
        doReturn(pm).when(context).getPackageManager();
        PackageManager.Property activityProp = new PackageManager.Property("", true, "", "");
        doReturn(activityProp).when(pm).getProperty(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
                eq(component));
        PackageManager.Property appProp = new PackageManager.Property("", false, "", "");
        doReturn(appProp).when(pm).getProperty(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
                eq(component.getPackageName()));

        SplitScreenController controller = new SplitScreenController(context, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                new String[0]);
        // Expect activity property to override application property
        assertEquals(true, controller.supportsMultiInstanceSplit(component));
    }

    @Test
    public void supportsMultiInstanceSplit_activityPropertyFalseApplicationPropertyTrue()
            throws PackageManager.NameNotFoundException {
        Context context = spy(mContext);
        ComponentName component = new ComponentName(TEST_PACKAGE, TEST_ACTIVITY);
        PackageManager pm = mock(PackageManager.class);
        doReturn(pm).when(context).getPackageManager();
        PackageManager.Property activityProp = new PackageManager.Property("", false, "", "");
        doReturn(activityProp).when(pm).getProperty(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
                eq(component));
        PackageManager.Property appProp = new PackageManager.Property("", true, "", "");
        doReturn(appProp).when(pm).getProperty(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
                eq(component.getPackageName()));

        SplitScreenController controller = new SplitScreenController(context, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                new String[0]);
        // Expect activity property to override application property
        assertEquals(false, controller.supportsMultiInstanceSplit(component));
    }

    @Test
    public void supportsMultiInstanceSplit_noActivityPropertyApplicationPropertyTrue()
            throws PackageManager.NameNotFoundException {
        Context context = spy(mContext);
        ComponentName component = new ComponentName(TEST_PACKAGE, TEST_ACTIVITY);
        PackageManager pm = mock(PackageManager.class);
        doReturn(pm).when(context).getPackageManager();
        doThrow(PackageManager.NameNotFoundException.class).when(pm).getProperty(
                eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI), eq(component));
        PackageManager.Property appProp = new PackageManager.Property("", true, "", "");
        doReturn(appProp).when(pm).getProperty(eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI),
                eq(component.getPackageName()));

        SplitScreenController controller = new SplitScreenController(context, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                new String[0]);
        // Expect fall through to app property
        assertEquals(true, controller.supportsMultiInstanceSplit(component));
    }

    @Test
    public void supportsMultiInstanceSplit_noActivityOrAppProperty()
            throws PackageManager.NameNotFoundException {
        Context context = spy(mContext);
        ComponentName component = new ComponentName(TEST_PACKAGE, TEST_ACTIVITY);
        PackageManager pm = mock(PackageManager.class);
        doReturn(pm).when(context).getPackageManager();
        doThrow(PackageManager.NameNotFoundException.class).when(pm).getProperty(
                eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI), eq(component));
        doThrow(PackageManager.NameNotFoundException.class).when(pm).getProperty(
                eq(PROPERTY_SUPPORTS_MULTI_INSTANCE_SYSTEM_UI), eq(component.getPackageName()));

        SplitScreenController controller = new SplitScreenController(context, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mLaunchAdjacentController, mWindowDecorViewModel,
                mDesktopTasksController, mMainExecutor, mStageCoordinator,
                new String[0]);
        assertEquals(false, controller.supportsMultiInstanceSplit(component));
    }

    private Intent createStartIntent(String activityName) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(mContext, activityName));
        return intent;
    }

    private ActivityManager.RunningTaskInfo createTaskInfo(int winMode, int actType,
            Intent strIntent) {
        ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.configuration.windowConfiguration.setActivityType(actType);
        info.configuration.windowConfiguration.setWindowingMode(winMode);
        info.supportsMultiWindow = true;
        info.baseIntent = strIntent;
        info.baseActivity = strIntent.getComponent();
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = info.baseActivity.getPackageName();
        activityInfo.name = info.baseActivity.getClassName();
        info.topActivityInfo = activityInfo;
        return info;
    }
}
