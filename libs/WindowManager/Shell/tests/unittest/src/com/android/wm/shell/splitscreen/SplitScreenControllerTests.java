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

import static com.android.wm.shell.common.split.SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

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
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.recents.RecentTasksController;
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

    @Mock ShellTaskOrganizer mTaskOrganizer;
    @Mock SyncTransactionQueue mSyncQueue;
    @Mock RootTaskDisplayAreaOrganizer mRootTDAOrganizer;
    @Mock ShellExecutor mMainExecutor;
    @Mock DisplayController mDisplayController;
    @Mock DisplayImeController mDisplayImeController;
    @Mock DisplayInsetsController mDisplayInsetsController;
    @Mock Transitions mTransitions;
    @Mock TransactionPool mTransactionPool;
    @Mock IconProvider mIconProvider;
    @Mock Optional<RecentTasksController> mRecentTasks;

    private SplitScreenController mSplitScreenController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSplitScreenController = spy(new SplitScreenController(mTaskOrganizer, mSyncQueue, mContext,
                mRootTDAOrganizer, mMainExecutor, mDisplayController, mDisplayImeController,
                mDisplayInsetsController, mTransitions, mTransactionPool, mIconProvider,
                mRecentTasks));
    }

    @Test
    public void testIsLaunchingAdjacently_notInSplitScreen() {
        doReturn(false).when(mSplitScreenController).isSplitScreenVisible();

        // Verify launching the same activity returns true.
        Intent startIntent = createStartIntent("startActivity");
        ActivityManager.RunningTaskInfo focusTaskInfo =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, startIntent);
        mSplitScreenController.onFocusTaskChanged(focusTaskInfo);
        assertTrue(mSplitScreenController.isLaunchingAdjacently(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));

        // Verify launching different activity returns false.
        Intent diffIntent = createStartIntent("diffActivity");
        focusTaskInfo =
                createTaskInfo(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, diffIntent);
        mSplitScreenController.onFocusTaskChanged(focusTaskInfo);
        assertFalse(mSplitScreenController.isLaunchingAdjacently(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));
    }

    @Test
    public void testIsLaunchingAdjacently_inSplitScreen() {
        doReturn(true).when(mSplitScreenController).isSplitScreenVisible();

        // Verify launching the same activity returns true.
        Intent startIntent = createStartIntent("startActivity");
        ActivityManager.RunningTaskInfo pairingTaskInfo =
                createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, startIntent);
        doReturn(pairingTaskInfo).when(mSplitScreenController).getTaskInfo(anyInt());
        assertTrue(mSplitScreenController.isLaunchingAdjacently(
                startIntent, SPLIT_POSITION_TOP_OR_LEFT));

        // Verify launching different activity returns false.
        Intent diffIntent = createStartIntent("diffActivity");
        pairingTaskInfo =
                createTaskInfo(WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, diffIntent);
        doReturn(pairingTaskInfo).when(mSplitScreenController).getTaskInfo(anyInt());
        assertFalse(mSplitScreenController.isLaunchingAdjacently(
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
