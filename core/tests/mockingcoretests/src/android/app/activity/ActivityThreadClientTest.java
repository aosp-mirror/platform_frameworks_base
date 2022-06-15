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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.ActivityThread;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.LoadedApk;
import android.app.servertransaction.PendingTransactionActions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.testing.PollingCheck;
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

import java.util.concurrent.TimeUnit;

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
    private static final long WAIT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

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

    @Test
    public void testLifecycleOfRelaunch() throws Exception {
        try (ClientMockSession clientSession = new ClientMockSession()) {
            ActivityThread activityThread = clientSession.mockThread();
            ActivityClientRecord r = clientSession.stubActivityRecord();
            final TestActivity[] activity = new TestActivity[1];

            // Verify for ON_CREATE state. Activity should not be relaunched.
            getInstrumentation().runOnMainSync(() -> {
                activity[0] = (TestActivity) clientSession.launchActivity(r);
            });
            recreateAndVerifyNoRelaunch(activityThread, activity[0]);

            // Verify for ON_START state. Activity should be relaunched.
            getInstrumentation().runOnMainSync(() -> clientSession.startActivity(r));
            recreateAndVerifyRelaunched(activityThread, activity[0], r, ON_START);

            // Verify for ON_RESUME state. Activity should be relaunched.
            getInstrumentation().runOnMainSync(() -> clientSession.resumeActivity(r));
            recreateAndVerifyRelaunched(activityThread, activity[0], r, ON_RESUME);

            // Verify for ON_PAUSE state. Activity should be relaunched.
            getInstrumentation().runOnMainSync(() -> clientSession.pauseActivity(r));
            recreateAndVerifyRelaunched(activityThread, activity[0], r, ON_PAUSE);

            // Verify for ON_STOP state. Activity should be relaunched.
            getInstrumentation().runOnMainSync(() -> clientSession.stopActivity(r));
            recreateAndVerifyRelaunched(activityThread, activity[0], r, ON_STOP);

            // Verify for ON_DESTROY state. Activity should not be relaunched.
            getInstrumentation().runOnMainSync(() -> clientSession.destroyActivity(r));
            recreateAndVerifyNoRelaunch(activityThread, activity[0]);
        }
    }

    private void recreateAndVerifyNoRelaunch(ActivityThread activityThread, TestActivity activity) {
        clearInvocations(activityThread);
        getInstrumentation().runOnMainSync(() -> activity.recreate());
        getInstrumentation().waitForIdleSync();

        verify(activityThread, never()).handleRelaunchActivity(any(), any());
    }

    private void recreateAndVerifyRelaunched(ActivityThread activityThread, TestActivity activity,
            ActivityClientRecord r, int expectedState) throws Exception {
        clearInvocations(activityThread);
        getInstrumentation().runOnMainSync(() -> activity.recreate());

        verify(activityThread, timeout(WAIT_TIMEOUT_MS)).handleRelaunchActivity(any(), any());

        // Wait for the relaunch to complete.
        PollingCheck.check("Waiting for the expected state " + expectedState + " timeout",
                WAIT_TIMEOUT_MS,
                () -> expectedState == r.getLifecycleState());
        assertEquals(expectedState, r.getLifecycleState());
    }

    private class ClientMockSession implements AutoCloseable {
        private MockitoSession mMockSession;
        private ActivityThread mThread;

        private ClientMockSession() {
            mThread = ActivityThread.currentActivityThread();
            mMockSession = mockitoSession()
                    .strictness(Strictness.LENIENT)
                    .spyStatic(ActivityClient.class)
                    .spyStatic(WindowManagerGlobal.class)
                    .startMocking();
            doReturn(Mockito.mock(WindowManagerGlobal.class))
                    .when(WindowManagerGlobal::getInstance);
            final ActivityClient mockAc = Mockito.mock(ActivityClient.class);
            doReturn(mockAc).when(ActivityClient::getInstance);
            doReturn(true).when(mockAc).finishActivity(any() /* token */,
                    anyInt() /* resultCode */, any() /* resultData */, anyInt() /* finishTask */);
        }

        private Activity launchActivity(ActivityClientRecord r) {
            return mThread.handleLaunchActivity(r, null /* pendingActions */,
                    null /* customIntent */);
        }

        private void startActivity(ActivityClientRecord r) {
            mThread.handleStartActivity(r, null /* pendingActions */, null /* activityOptions */);
        }

        private void resumeActivity(ActivityClientRecord r) {
            mThread.handleResumeActivity(r, true /* finalStateRequest */,
                    true /* isForward */, "test");
        }

        private void pauseActivity(ActivityClientRecord r) {
            mThread.handlePauseActivity(r, false /* finished */,
                    false /* userLeaving */, 0 /* configChanges */, null /* pendingActions */,
                    "test");
        }

        private void stopActivity(ActivityClientRecord r) {
            mThread.handleStopActivity(r, 0 /* configChanges */,
                    new PendingTransactionActions(), false /* finalStateRequest */, "test");
        }

        private void destroyActivity(ActivityClientRecord r) {
            mThread.handleDestroyActivity(r, true /* finishing */, 0 /* configChanges */,
                    false /* getNonConfigInstance */, "test");
        }

        private ActivityThread mockThread() {
            spyOn(mThread);
            return mThread;
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

            return new ActivityClientRecord(mock(IBinder.class), Intent.makeMainActivity(component),
                    0 /* ident */, info, new Configuration(),
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, null /* referrer */,
                    null /* voiceInteractor */, null /* state */, null /* persistentState */,
                    null /* pendingResults */, null /* pendingNewIntents */,
                    null /* activityOptions */, true /* isForward */, null /* profilerInfo */,
                    mThread /* client */, null /* asssitToken */,
                    null /* fixedRotationAdjustments */, null /* shareableActivityToken */,
                    false /* launchedFromBubble */);
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
