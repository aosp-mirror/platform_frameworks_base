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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT;
import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.draganddrop.DragAndDropController;
import com.android.wm.shell.recents.RecentTasksController;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Tests for {@link SplitScreenController}
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitScreenControllerTests extends ShellTestCase {

    @Mock ShellInit mShellInit;
    @Mock ShellController mShellController;
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
    @Mock Optional<RecentTasksController> mRecentTasks;

    private SplitScreenController mSplitScreenController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSplitScreenController = spy(new SplitScreenController(mContext, mShellInit,
                mShellCommandHandler, mShellController, mTaskOrganizer, mSyncQueue,
                mRootTDAOrganizer, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mDragAndDropController, mTransitions, mTransactionPool,
                mIconProvider, mRecentTasks, mMainExecutor));
    }

    @Test
    public void instantiateController_addInitCallback() {
        verify(mShellInit, times(1)).addInitCallback(any(), any());
    }

    @Test
    public void instantiateController_registerDumpCallback() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellCommandHandler, times(1)).addDumpCallback(any(), any());
    }

    @Test
    public void instantiateController_registerCommandCallback() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellCommandHandler, times(1)).addCommandCallback(eq("splitscreen"), any(), any());
    }

    @Test
    public void testControllerRegistersKeyguardChangeListener() {
        doReturn(mMainExecutor).when(mTaskOrganizer).getExecutor();
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(new DisplayLayout());
        mSplitScreenController.onInit();
        verify(mShellController, times(1)).addKeyguardChangeListener(any());
    }

    @Test
    public void testShouldAddMultipleTaskFlag_notInSplitScreen() {
        doReturn(false).when(mSplitScreenController).isSplitScreenVisible();
        doReturn(true).when(mSplitScreenController).isValidToEnterSplitScreen(any());

        // Verify launching the same activity returns true.
        Intent startIntent = createStartIntent("startActivity");
        ActivityManager.RunningTaskInfo focusTaskInfo =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, startIntent);
        doReturn(focusTaskInfo).when(mSplitScreenController).getFocusingTaskInfo();
        assertTrue(mSplitScreenController.shouldAddMultipleTaskFlag(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));

        // Verify launching different activity returns false.
        Intent diffIntent = createStartIntent("diffActivity");
        focusTaskInfo =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, diffIntent);
        doReturn(focusTaskInfo).when(mSplitScreenController).getFocusingTaskInfo();
        assertFalse(mSplitScreenController.shouldAddMultipleTaskFlag(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));
    }

    @Test
    public void testShouldAddMultipleTaskFlag_inSplitScreen() {
        doReturn(true).when(mSplitScreenController).isSplitScreenVisible();
        Intent startIntent = createStartIntent("startActivity");
        ActivityManager.RunningTaskInfo sameTaskInfo =
                createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, startIntent);
        Intent diffIntent = createStartIntent("diffActivity");
        ActivityManager.RunningTaskInfo differentTaskInfo =
                createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, diffIntent);

        // Verify launching the same activity return false.
        doReturn(sameTaskInfo).when(mSplitScreenController)
                .getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT);
        assertFalse(mSplitScreenController.shouldAddMultipleTaskFlag(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));

        // Verify launching the same activity as adjacent returns true.
        doReturn(differentTaskInfo).when(mSplitScreenController)
                .getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT);
        doReturn(sameTaskInfo).when(mSplitScreenController)
                .getTaskInfo(SPLIT_POSITION_BOTTOM_OR_RIGHT);
        assertTrue(mSplitScreenController.shouldAddMultipleTaskFlag(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));

        // Verify launching different activity from adjacent returns false.
        doReturn(differentTaskInfo).when(mSplitScreenController)
                .getTaskInfo(SPLIT_POSITION_TOP_OR_LEFT);
        doReturn(differentTaskInfo).when(mSplitScreenController)
                .getTaskInfo(SPLIT_POSITION_BOTTOM_OR_RIGHT);
        assertFalse(mSplitScreenController.shouldAddMultipleTaskFlag(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));
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
