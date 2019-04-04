/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;

import static org.mockito.ArgumentMatchers.any;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.platform.test.annotations.Presubmit;
import android.view.IRecentsAnimationRunner;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:RecentsAnimationTest
 */
@MediumTest
@Presubmit
public class RecentsAnimationTest extends ActivityTestsBase {

    private Context mContext = InstrumentationRegistry.getContext();
    private ComponentName mRecentsComponent;
    private RecentsAnimationController mRecentsAnimationController;

    @Before
    public void setUp() throws Exception {
        mRecentsComponent = new ComponentName(mContext.getPackageName(), "RecentsActivity");
        mService = new TestActivityTaskManagerService(mContext);
        mRecentsAnimationController = mock(RecentsAnimationController.class);
        doReturn(mRecentsAnimationController).when(
                mService.mWindowManager).getRecentsAnimationController();

        final RecentTasks recentTasks = mService.getRecentTasks();
        spyOn(recentTasks);
        mRecentsComponent = new ComponentName(mContext.getPackageName(), "RecentsActivity");
        doReturn(mRecentsComponent).when(recentTasks).getRecentsComponent();
    }

    @Test
    public void testCancelAnimationOnVisibleStackOrderChange() {
        ActivityDisplay display = mService.mRootActivityContainer.getDefaultDisplay();
        ActivityStack fullscreenStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setStack(fullscreenStack)
                .build();
        ActivityStack recentsStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setStack(recentsStack)
                .build();
        ActivityStack fullscreenStack2 = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App2"))
                .setCreateTask(true)
                .setStack(fullscreenStack2)
                .build();
        doReturn(true).when(mService.mWindowManager).canStartRecentsAnimation();

        // Start the recents animation
        Intent recentsIntent = new Intent();
        recentsIntent.setComponent(mRecentsComponent);
        mService.startRecentsActivity(recentsIntent, null, mock(IRecentsAnimationRunner.class));

        fullscreenStack.moveToFront("Activity start");

        // Ensure that the recents animation was canceled by cancelAnimationSynchronously().
        verify(mService.mWindowManager, times(1)).cancelRecentsAnimationSynchronously(
                eq(REORDER_KEEP_IN_PLACE), any());

        // Assume recents animation already started, set a state that cancel recents animation
        // with screenshot.
        doReturn(true).when(mRecentsAnimationController).shouldCancelWithDeferredScreenshot();
        // Start another fullscreen activity.
        fullscreenStack2.moveToFront("Activity start");

        // Ensure that the recents animation was canceled by cancelOnNextTransitionStart().
        verify(mRecentsAnimationController, times(1)).cancelOnNextTransitionStart();
    }

    @Test
    public void testKeepAnimationOnHiddenStackOrderChange() {
        ActivityDisplay display = mService.mRootActivityContainer.getDefaultDisplay();
        ActivityStack fullscreenStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setStack(fullscreenStack)
                .build();
        ActivityStack recentsStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setStack(recentsStack)
                .build();
        ActivityStack fullscreenStack2 = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App2"))
                .setCreateTask(true)
                .setStack(fullscreenStack2)
                .build();
        doReturn(true).when(mService.mWindowManager).canStartRecentsAnimation();

        // Start the recents animation
        Intent recentsIntent = new Intent();
        recentsIntent.setComponent(mRecentsComponent);
        mService.startRecentsActivity(recentsIntent, null, mock(IRecentsAnimationRunner.class));

        fullscreenStack.remove();

        // Ensure that the recents animation was NOT canceled
        verify(mService.mWindowManager, times(0)).cancelRecentsAnimationSynchronously(
                eq(REORDER_KEEP_IN_PLACE), any());
        verify(mRecentsAnimationController, times(0)).cancelOnNextTransitionStart();
    }
}
