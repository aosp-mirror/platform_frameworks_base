/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.platform.test.annotations.Presubmit;
import android.view.IRecentsAnimationRunner;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.AttributeCache;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * atest FrameworksServicesTests:RecentsAnimationTest
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RecentsAnimationTest extends ActivityTestsBase {
    private static final int TEST_CALLING_PID = 3;

    private Context mContext = InstrumentationRegistry.getContext();
    private ActivityManagerService mService;
    private ComponentName mRecentsComponent;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mRecentsComponent = new ComponentName(mContext.getPackageName(), "RecentsActivity");
        mService = setupActivityManagerService(new MyTestActivityManagerService(mContext));
        AttributeCache.init(mContext);
    }

    @Test
    public void testCancelAnimationOnStackOrderChange() throws Exception {
        ActivityStack fullscreenStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        ActivityStack recentsStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_RECENTS, true /* onTop */);
        ActivityRecord recentsActivity = new ActivityBuilder(mService)
                .setComponent(mRecentsComponent)
                .setCreateTask(true)
                .setStack(recentsStack)
                .build();
        ActivityStack fullscreenStack2 = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        ActivityRecord fsActivity = new ActivityBuilder(mService)
                .setComponent(new ComponentName(mContext.getPackageName(), "App1"))
                .setCreateTask(true)
                .setStack(fullscreenStack2)
                .build();
        doReturn(true).when(mService.mWindowManager).canStartRecentsAnimation();

        // Start the recents animation
        Intent recentsIntent = new Intent();
        recentsIntent.setComponent(mRecentsComponent);
        mService.startRecentsActivity(recentsIntent, null, mock(IRecentsAnimationRunner.class));

        fullscreenStack.moveToFront("Activity start");

        // Ensure that the recents animation was canceled
        verify(mService.mWindowManager, times(1)).cancelRecentsAnimationSynchronously(
                eq(REORDER_KEEP_IN_PLACE), any());
    }

    private class MyTestActivityManagerService extends TestActivityManagerService {
        MyTestActivityManagerService(Context context) {
            super(context);
        }

        @Override
        protected RecentTasks createRecentTasks() {
            RecentTasks recents = mock(RecentTasks.class);
            doReturn(mRecentsComponent).when(recents).getRecentsComponent();
            System.out.println(mRecentsComponent);
            return recents;
        }
    }
}
