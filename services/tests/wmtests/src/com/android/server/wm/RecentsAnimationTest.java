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

import static android.app.ActivityManager.PROCESS_STATE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:RecentsAnimationTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RecentsAnimationTest extends WindowTestsBase {

    private final ComponentName mRecentsComponent =
            new ComponentName(mContext.getPackageName(), "RecentsActivity");

    @Before
    public void setUp() throws Exception {
        final RecentTasks recentTasks = mAtm.getRecentTasks();
        spyOn(recentTasks);
        doReturn(mRecentsComponent).when(recentTasks).getRecentsComponent();
    }

    @Test
    public void testPreloadRecentsActivity() {
        TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task homeStack =
                defaultTaskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME);
        defaultTaskDisplayArea.positionChildAt(POSITION_TOP, homeStack,
                false /* includingParents */);
        ActivityRecord topRunningHomeActivity = homeStack.topRunningActivity();
        if (topRunningHomeActivity == null) {
            topRunningHomeActivity = new ActivityBuilder(mAtm)
                    .setParentTask(homeStack)
                    .setCreateTask(true)
                    .build();
        }

        ActivityInfo aInfo = new ActivityInfo();
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.uid = 10001;
        aInfo.applicationInfo.targetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
        aInfo.packageName = aInfo.applicationInfo.packageName = mRecentsComponent.getPackageName();
        aInfo.processName = "recents";
        doReturn(aInfo).when(mSupervisor).resolveActivity(any() /* intent */, any() /* rInfo */,
                anyInt() /* startFlags */, any() /* profilerInfo */);

        // Assume its process is alive because the caller should be the recents service.
        final WindowProcessController proc = mSystemServicesTestRule.addProcess(aInfo.packageName,
                aInfo.processName, 12345 /* pid */, aInfo.applicationInfo.uid);
        proc.setCurrentProcState(PROCESS_STATE_HOME);

        Intent recentsIntent = new Intent().setComponent(mRecentsComponent);
        // Null animation indicates to preload.
        mAtm.preloadRecentsActivity(recentsIntent);

        Task recentsStack = defaultTaskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS);
        assertThat(recentsStack).isNotNull();

        ActivityRecord recentsActivity = recentsStack.getTopNonFinishingActivity();
        // The activity is started in background so it should be invisible and will be stopped.
        assertThat(recentsActivity).isNotNull();
        assertThat(recentsActivity.getState()).isEqualTo(STOPPING);
        assertFalse(recentsActivity.isVisibleRequested());

        spyOn(recentsActivity);
        // Preload when the recents activity exists. It should ensure the configuration.
        mAtm.preloadRecentsActivity(recentsIntent);

        verify(recentsActivity).ensureActivityConfiguration(eq(true) /* ignoreVisibility */);
    }
}
