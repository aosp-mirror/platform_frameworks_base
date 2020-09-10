/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.activity;

import static android.app.servertransaction.ActivityLifecycleItem.ON_CREATE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_DESTROY;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_RESUME;
import static android.app.servertransaction.ActivityLifecycleItem.ON_START;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.IActivityTaskManager;
import android.app.LoadedApk;
import android.app.servertransaction.PendingTransactionActions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManagerGlobal;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Test for verifying {@link android.app.ActivityThread} class.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksMockingCoreTests:android.app.activity.ActivityThreadClientTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
@Presubmit
public class ActivityThreadClientTest {

    @Test
    @UiThreadTest
    public void testLifecycleAfterFinished_OnCreate() throws Exception {
        try (ClientMockSession clientSession = new ClientMockSession()) {
            ActivityClientRecord r = clientSession.stubActivityRecord();

            Activity activity = clientSession.launchActivity(r);
            activity.finish();
            assertEquals(ON_CREATE, r.getLifecycleState());

            clientSession.startActivity(r);
            assertEquals(ON_CREATE, r.getLifecycleState());

            clientSession.resumeActivity(r);
            assertEquals(ON_CREATE, r.getLifecycleState());

            clientSession.pauseActivity(r);
            assertEquals(ON_CREATE, r.getLifecycleState());

            clientSession.stopActivity(r);
            assertEquals(ON_CREATE, r.getLifecycleState());

            clientSession.destroyActivity(r);
            assertEquals(ON_DESTROY, r.getLifecycleState());
        }
    }

    @Test
    @UiThreadTest
    public void testLifecycleAfterFinished_OnStart() throws Exception {
        try (ClientMockSession clientSession = new ClientMockSession()) {
            ActivityClientRecord r = clientSession.stubActivityRecord();

            Activity activity = clientSession.launchActivity(r);
            clientSession.startActivity(r);
            activity.finish();
            assertEquals(ON_START, r.getLifecycleState());

            clientSession.resumeActivity(r);
            assertEquals(ON_START, r.getLifecycleState());

            clientSession.pauseActivity(r);
            assertEquals(ON_START, r.getLifecycleState());

            clientSession.stopActivity(r);
            assertEquals(ON_STOP, r.getLifecycleState());

            clientSession.destroyActivity(r);
            assertEquals(ON_DESTROY, r.getLifecycleState());
        }
    }

    @Test
    @UiThreadTest
    public void testLifecycleAfterFinished_OnResume() throws Exception {
        try (ClientMockSession clientSession = new ClientMockSession()) {
            ActivityClientRecord r = clientSession.stubActivityRecord();

            Activity activity = clientSession.launchActivity(r);
            clientSession.startActivity(r);
            clientSession.resumeActivity(r);
            activity.finish();
            assertEquals(ON_RESUME, r.getLifecycleState());

            clientSession.pauseActivity(r);
            assertEquals(ON_PAUSE, r.getLifecycleState());

            clientSession.stopActivity(r);
            assertEquals(ON_STOP, r.getLifecycleState());

            clientSession.destroyActivity(r);
            assertEquals(ON_DESTROY, r.getLifecycleState());
        }
    }

    private class ClientMockSession implements AutoCloseable {
        private MockitoSession mMockSession;
        private ActivityThread mThread;

        private ClientMockSession() throws RemoteException {
            mThread = ActivityThread.currentActivityThread();
            mMockSession = mockitoSession()
                    .strictness(Strictness.LENIENT)
                    .spyStatic(ActivityTaskManager.class)
                    .spyStatic(WindowManagerGlobal.class)
                    .startMocking();
            doReturn(Mockito.mock(WindowManagerGlobal.class))
                    .when(WindowManagerGlobal::getInstance);
            IActivityTaskManager mockAtm = Mockito.mock(IActivityTaskManager.class);
            doReturn(mockAtm).when(ActivityTaskManager::getService);
            when(mockAtm.finishActivity(any(), anyInt(), any(), anyInt())).thenReturn(true);
        }

        private Activity launchActivity(ActivityClientRecord r) {
            return mThread.handleLaunchActivity(r, null /* pendingActions */,
                    null /* customIntent */);
        }

        private void startActivity(ActivityClientRecord r) {
            mThread.handleStartActivity(r.token, null /* pendingActions */);
        }

        private void resumeActivity(ActivityClientRecord r) {
            mThread.handleResumeActivity(r.token, true /* finalStateRequest */,
                    true /* isForward */, "test");
        }

        private void pauseActivity(ActivityClientRecord r) {
            mThread.handlePauseActivity(r.token, false /* finished */,
                    false /* userLeaving */, 0 /* configChanges */, null /* pendingActions */,
                    "test");
        }

        private void stopActivity(ActivityClientRecord r) {
            mThread.handleStopActivity(r.token, 0 /* configChanges */,
                    new PendingTransactionActions(), false /* finalStateRequest */, "test");
        }

        private void destroyActivity(ActivityClientRecord r) {
            mThread.handleDestroyActivity(r.token, true /* finishing */, 0 /* configChanges */,
                    false /* getNonConfigInstance */, "test");
        }

        private ActivityClientRecord stubActivityRecord() {
            ComponentName component = new ComponentName(
                    InstrumentationRegistry.getInstrumentation().getContext(), TestActivity.class);
            ActivityInfo info = new ActivityInfo();
            info.packageName = component.getPackageName();
            info.name = component.getClassName();
            info.exported = true;
            info.applicationInfo = new ApplicationInfo();
            info.applicationInfo.packageName = info.packageName;
            info.applicationInfo.uid = UserHandle.myUserId();
            info.applicationInfo.sourceDir = "/test/sourceDir";

            // mock the function for preventing NPE in emulator environment. The purpose of the
            // test is for activity client state changes, we don't focus on the updating for the
            // ApplicationInfo.
            LoadedApk packageInfo = mThread.peekPackageInfo(info.packageName,
                    true /* includeCode */);
            spyOn(packageInfo);
            doNothing().when(packageInfo).updateApplicationInfo(any(), any());

            return new ActivityClientRecord(new Binder(), Intent.makeMainActivity(component),
                    0 /* ident */, info, new Configuration(),
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null /* referrer */,
                    null /* voiceInteractor */, null /* state */, null /* persistentState */,
                    null /* pendingResults */, null /* pendingNewIntents */, true /* isForward */,
                    null /* profilerInfo */,  mThread /* client */, null /* asssitToken */,
                    null /* fixedRotationAdjustments */);
        }

        @Override
        public void close() {
            mMockSession.finishMocking();
        }
    }

    // Test activity
    public static class TestActivity extends Activity {
    }
}
