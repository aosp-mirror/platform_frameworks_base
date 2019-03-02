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
 * limitations under the License
 */

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Intent;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.am.ActivityStarter.Factory;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Random;

/**
 * Tests for the {@link ActivityStartController} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:ActivityStartControllerTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStartControllerTests extends ActivityTestsBase {
    private ActivityManagerService mService;
    private ActivityStartController mController;
    private Factory mFactory;
    private ActivityStarter mStarter;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mService = createActivityManagerService();
        mFactory = mock(Factory.class);
        mController = new ActivityStartController(mService, mService.mStackSupervisor, mFactory);
        mStarter = spy(new ActivityStarter(mController, mService, mService.mStackSupervisor,
                mock(ActivityStartInterceptor.class)));
        doReturn(mStarter).when(mFactory).obtain();
    }

    /**
     * Ensures that pending launches are processed.
     */
    @Test
    public void testPendingActivityLaunches() {
        final Random random = new Random();

        final ActivityRecord activity = new ActivityBuilder(mService).build();
        final ActivityRecord source = new ActivityBuilder(mService).build();
        final int startFlags = random.nextInt();
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ProcessRecord process= new ProcessRecord(null, null,
                mService.mContext.getApplicationInfo(), "name", 12345);

        mController.addPendingActivityLaunch(
                new PendingActivityLaunch(activity, source, startFlags, stack, process));
        final boolean resume = random.nextBoolean();
        mController.doPendingActivityLaunches(resume);

        verify(mStarter, times(1)).startResolvedActivity(eq(activity), eq(source), eq(null),
                eq(null), eq(startFlags), eq(resume), eq(null), eq(null), eq(null));
    }


    /**
     * Ensures instances are recycled after execution.
     */
    @Test
    public void testRecycling() throws Exception {
        final Intent intent = new Intent();
        final ActivityStarter optionStarter = new ActivityStarter(mController, mService,
                mService.mStackSupervisor, mock(ActivityStartInterceptor.class));
        optionStarter
                .setIntent(intent)
                .setReason("Test")
                .execute();
        verify(mFactory, times(1)).recycle(eq(optionStarter));
    }
}
