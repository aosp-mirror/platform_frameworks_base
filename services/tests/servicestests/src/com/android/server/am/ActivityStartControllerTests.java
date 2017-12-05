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

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.service.voice.IVoiceInteractionSession;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.am.ActivityStarter.Factory;

import org.junit.runner.RunWith;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

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
        mStarter = mock(ActivityStarter.class);
        doReturn(mStarter).when(mFactory).getStarter(any(), any(), any(), any());
        mController = new ActivityStartController(mService, mService.mStackSupervisor, mFactory);
    }

    /**
     * Ensures that the starter is correctly invoked on
     * {@link ActivityStartController#startActivity}
     */
    @Test
    @Presubmit
    public void testStartActivity() {
        final Random random = new Random();

        final IApplicationThread applicationThread = mock(IApplicationThread.class);
        final Intent intent = mock(Intent.class);
        final Intent ephemeralIntent = mock(Intent.class);
        final String resolvedType = "TestType";
        final ActivityInfo aInfo = mock(ActivityInfo.class);
        final ResolveInfo rInfo = mock(ResolveInfo.class);
        final IVoiceInteractionSession voiceInteractionSession =
                mock(IVoiceInteractionSession.class);
        final IVoiceInteractor voiceInteractor = mock(IVoiceInteractor.class);
        final IBinder resultTo = mock(IBinder.class);
        final String resultWho = "resultWho";
        final int requestCode = random.nextInt();
        final int callingPid = random.nextInt();
        final int callingUid = random.nextInt();
        final String callingPackage = "callingPackage";
        final int realCallingPid = random.nextInt();
        final int realCallingUid = random.nextInt();
        final int startFlags = random.nextInt();
        final ActivityOptions options = mock(ActivityOptions.class);
        final boolean ignoreTargetSecurity = random.nextBoolean();
        final boolean componentSpecified = random.nextBoolean();
        final ActivityRecord[] outActivity = new ActivityRecord[1];
        final TaskRecord inTask = mock(TaskRecord.class);
        final String reason ="reason";

        mController.startActivity(applicationThread, intent, ephemeralIntent, resolvedType,
                aInfo, rInfo, voiceInteractionSession, voiceInteractor, resultTo, resultWho,
                requestCode, callingPid, callingUid, callingPackage, realCallingPid, realCallingUid,
                startFlags, options, ignoreTargetSecurity, componentSpecified, outActivity, inTask,
                reason);

        // The starter should receive a start command with the originally provided parameters
        verify(mStarter, times(1)).startActivityLocked(eq(applicationThread), eq(intent),
                eq(ephemeralIntent), eq(resolvedType), eq(aInfo), eq(rInfo),
                eq(voiceInteractionSession), eq(voiceInteractor), eq(resultTo), eq(resultWho),
                eq(requestCode), eq(callingPid), eq(callingUid), eq(callingPackage),
                eq(realCallingPid), eq(realCallingUid), eq(startFlags), eq(options),
                eq(ignoreTargetSecurity), eq(componentSpecified), eq(outActivity), eq(inTask),
                eq(reason));
    }

    /**
     * Ensures that pending launches are processed.
     */
    @Test
    @Presubmit
    public void testPendingActivityLaunches() {
        final Random random = new Random();

        final ActivityRecord activity = new ActivityBuilder(mService).build();
        final ActivityRecord source = new ActivityBuilder(mService).build();
        final int startFlags = random.nextInt();
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ProcessRecord process= new ProcessRecord(null, mService.mContext.getApplicationInfo(),
                "name", 12345);

        mController.addPendingActivityLaunch(
                new PendingActivityLaunch(activity, source, startFlags, stack, process));
        final boolean resume = random.nextBoolean();
        mController.doPendingActivityLaunches(resume);

        verify(mStarter, times(1)).startActivity(eq(activity), eq(source), eq(null),
                eq(null), eq(startFlags), eq(resume), eq(null), eq(null), eq(null));
    }
}
