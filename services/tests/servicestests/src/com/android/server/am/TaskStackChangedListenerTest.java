/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.support.test.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ITaskStackListener;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.UiDevice;

import com.android.internal.annotations.GuardedBy;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class TaskStackChangedListenerTest extends ITaskStackListener.Stub {

    private IActivityManager mService;

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static boolean sTaskStackChangedCalled;
    private static boolean sActivityBResumed;

    @Before
    public void setUp() throws Exception {
        mService = ActivityManagerNative.getDefault();
        mService.registerTaskStackListener(this);
    }

    @Test
    public void testTaskStackChanged_afterFinish() throws Exception {
        Context ctx = InstrumentationRegistry.getContext();
        ctx.startActivity(new Intent(ctx, ActivityA.class));
        UiDevice.getInstance(getInstrumentation()).waitForIdle();
        synchronized (sLock) {
            Assert.assertTrue(sTaskStackChangedCalled);
        }
        Assert.assertTrue(sActivityBResumed);
    }

    @Override
    public void onTaskStackChanged() throws RemoteException {
        synchronized (sLock) {
            sTaskStackChangedCalled = true;
        }
    }

    @Override
    public void onActivityPinned() throws RemoteException {
    }

    @Override
    public void onPinnedActivityRestartAttempt() throws RemoteException {
    }

    @Override
    public void onPinnedStackAnimationEnded() throws RemoteException {
    }

    @Override
    public void onActivityForcedResizable(String packageName, int taskId) throws RemoteException {
    }

    @Override
    public void onActivityDismissingDockedStack() throws RemoteException {
    }

    public static class ActivityA extends Activity {

        private boolean mActivityBLaunched = false;

        @Override
        protected void onPostResume() {
            super.onPostResume();
            if (mActivityBLaunched) {
                return;
            }
            mActivityBLaunched = true;
            finish();
            startActivity(new Intent(this, ActivityB.class));
        }
    }

    public static class ActivityB extends Activity {

        @Override
        protected void onPostResume() {
            super.onPostResume();
            synchronized (sLock) {
                sTaskStackChangedCalled = false;
            }
            sActivityBResumed = true;
            finish();
        }
    }
}
