/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.window.BackNavigationInfo.typeToString;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;
import android.window.BackAnimationAdapter;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.IOnBackInvokedCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Presubmit
@RunWith(WindowTestRunner.class)
public class BackNavigationControllerTests extends WindowTestsBase {
    private BackNavigationController mBackNavigationController;
    private WindowManagerInternal mWindowManagerInternal;
    private BackAnimationAdapter mBackAnimationAdapter;
    private Task mRootHomeTask;

    @Before
    public void setUp() throws Exception {
        mBackNavigationController = Mockito.spy(new BackNavigationController());
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        mWindowManagerInternal = mock(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);
        mBackNavigationController.setWindowManager(mWm);
        mBackAnimationAdapter = mock(BackAnimationAdapter.class);
        mRootHomeTask = initHomeActivity();
    }

    @Test
    public void backNavInfo_HomeWhenBackToLauncher() {
        Task task = createTopTaskWithActivity();
        IOnBackInvokedCallback callback = withSystemCallback(task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));

        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());
    }

    @Test
    public void backTypeCrossTaskWhenBackToPreviousTask() {
        Task taskA = createTask(mDefaultDisplay);
        ActivityRecord recordA = createActivityRecord(taskA);
        Mockito.doNothing().when(recordA).reparentSurfaceControl(any(), any());

        final Task topTask = createTopTaskWithActivity();
        withSystemCallback(topTask);
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_TASK));

        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());

        // reset drawning status
        topTask.forAllWindows(w -> {
            makeWindowVisibleAndDrawn(w);
        }, true);
        setupKeyguardOccluded();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));

        doReturn(true).when(recordA).canShowWhenLocked();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_TASK));
    }

    @Test
    public void backTypeBackToHomeDifferentUser() {
        Task taskA = createTask(mDefaultDisplay);
        ActivityRecord recordA = createActivityRecord(taskA);
        Mockito.doNothing().when(recordA).reparentSurfaceControl(any(), any());
        doReturn(false).when(taskA).showToCurrentUser();

        withSystemCallback(createTopTaskWithActivity());
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));
    }

    @Test
    public void backTypeCrossActivityWhenBackToPreviousActivity() {
        CrossActivityTestCase testCase = createTopTaskWithTwoActivities();
        IOnBackInvokedCallback callback = withSystemCallback(testCase.task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));

        // reset drawing status
        testCase.recordFront.forAllWindows(w -> {
            makeWindowVisibleAndDrawn(w);
        }, true);
        setupKeyguardOccluded();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));

        doReturn(true).when(testCase.recordBack).canShowWhenLocked();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));
    }

    @Test
    public void backInfoWithNullWindow() {
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(backNavigationInfo).isNull();
    }

    @Test
    public void backInfoWindowWithNoActivity() {
        WindowState window = createWindow(null, WindowManager.LayoutParams.TYPE_WALLPAPER,
                "Wallpaper");
        addToWindowMap(window, true);
        makeWindowVisibleAndDrawn(window);

        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(callback, OnBackInvokedDispatcher.PRIORITY_DEFAULT));

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getType()).isEqualTo(BackNavigationInfo.TYPE_CALLBACK);
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
    }

    @Test
    public void preparesForBackToHome() {
        final Task topTask = createTopTaskWithActivity();
        withSystemCallback(topTask);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));

        setupKeyguardOccluded();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
    }

    @Test
    public void backTypeCallback() {
        Task task = createTopTaskWithActivity();
        IOnBackInvokedCallback appCallback = withAppCallback(task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(appCallback);
    }

    @Test
    public void testUnregisterCallbacksWithSystemCallback()
            throws InterruptedException, RemoteException {
        CountDownLatch systemLatch = new CountDownLatch(1);
        CountDownLatch appLatch = new CountDownLatch(1);

        final ApplicationInfo info = mock(ApplicationInfo.class);
        final Context context = mock(Context.class);
        Mockito.doReturn(true).when(info).isOnBackInvokedCallbackEnabled();
        Mockito.doReturn(info).when(context).getApplicationInfo();

        Task task = createTopTaskWithActivity();
        WindowState appWindow = task.getTopVisibleAppMainWindow();
        WindowOnBackInvokedDispatcher dispatcher =
                new WindowOnBackInvokedDispatcher(context);
        doAnswer(invocation -> {
            appWindow.setOnBackInvokedCallbackInfo(invocation.getArgument(1));
            return null;
        }).when(appWindow.mSession).setOnBackInvokedCallbackInfo(eq(appWindow.mClient), any());

        addToWindowMap(appWindow, true);
        dispatcher.attachToWindow(appWindow.mSession, appWindow.mClient);


        OnBackInvokedCallback appCallback = createBackCallback(appLatch);
        OnBackInvokedCallback systemCallback = createBackCallback(systemLatch);

        // Register both a system callback and an application callback
        dispatcher.registerSystemOnBackInvokedCallback(systemCallback);
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                appCallback);

        // Check that the top callback is the app callback
        assertEquals(appCallback, dispatcher.getTopCallback());

        // Now unregister the app callback and check that the top callback is the system callback
        dispatcher.unregisterOnBackInvokedCallback(appCallback);
        assertEquals(systemCallback, dispatcher.getTopCallback());

        // Verify that this has correctly been propagated to the server and that the
        // BackNavigationInfo object will contain the system callback
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        IOnBackInvokedCallback callback = backNavigationInfo.getOnBackInvokedCallback();
        assertThat(callback).isNotNull();

        try {
            callback.onBackInvoked();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // Check that the system callback has been call
        assertTrue("System callback has not been called",
                systemLatch.await(500, TimeUnit.MILLISECONDS));
        assertEquals("App callback should not have been called",
                1, appLatch.getCount());
    }

    @Test
    public void backInfoWindowWithoutDrawn() {
        WindowState window = createWindow(null, WindowManager.LayoutParams.TYPE_APPLICATION,
                "TestWindow");
        addToWindowMap(window, true);

        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(callback, OnBackInvokedDispatcher.PRIORITY_DEFAULT));

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(backNavigationInfo).isNull();
    }

    private IOnBackInvokedCallback withSystemCallback(Task task) {
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        task.getTopMostActivity().getTopChild().setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(callback, OnBackInvokedDispatcher.PRIORITY_SYSTEM));
        return callback;
    }

    private IOnBackInvokedCallback withAppCallback(Task task) {
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        task.getTopMostActivity().getTopChild().setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(callback, OnBackInvokedDispatcher.PRIORITY_DEFAULT));
        return callback;
    }

    @Nullable
    private BackNavigationInfo startBackNavigation() {
        return mBackNavigationController.startBackNavigation(null, mBackAnimationAdapter);
    }

    @NonNull
    private IOnBackInvokedCallback createOnBackInvokedCallback() {
        return new IOnBackInvokedCallback.Stub() {
            @Override
            public void onBackStarted(BackMotionEvent backMotionEvent) {
            }

            @Override
            public void onBackProgressed(BackMotionEvent backMotionEvent) {
            }

            @Override
            public void onBackCancelled() {
            }

            @Override
            public void onBackInvoked() {
            }
        };
    }

    private OnBackInvokedCallback createBackCallback(CountDownLatch latch) {
        return new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                if (latch != null) {
                    latch.countDown();
                }
            }
        };
    }

    private Task initHomeActivity() {
        final Task task = mDisplayContent.getDefaultTaskDisplayArea().getRootHomeTask();
        task.forAllLeafTasks((t) -> {
            if (t.getTopMostActivity() == null) {
                final ActivityRecord r = createActivityRecord(t);
                Mockito.doNothing().when(t).reparentSurfaceControl(any(), any());
                Mockito.doNothing().when(r).reparentSurfaceControl(any(), any());
            }
        }, true);
        return task;
    }

    private void setupKeyguardOccluded() {
        final KeyguardController kc = mRootHomeTask.mTaskSupervisor.getKeyguardController();
        doReturn(true).when(kc).isKeyguardLocked(anyInt());
        doReturn(true).when(kc).isDisplayOccluded(anyInt());
    }

    @NonNull
    private Task createTopTaskWithActivity() {
        Task task = createTask(mDefaultDisplay);
        ActivityRecord record = createActivityRecord(task);
        // enable OnBackInvokedCallbacks
        record.info.applicationInfo.privateFlagsExt |=
                PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, record, "window");
        when(record.mSurfaceControl.isValid()).thenReturn(true);
        Mockito.doNothing().when(task).reparentSurfaceControl(any(), any());
        mAtm.setFocusedTask(task.mTaskId, record);
        addToWindowMap(window, true);
        makeWindowVisibleAndDrawn(window);
        return task;
    }

    @NonNull
    private CrossActivityTestCase createTopTaskWithTwoActivities() {
        Task task = createTask(mDefaultDisplay);
        ActivityRecord record1 = createActivityRecord(task);
        ActivityRecord record2 = createActivityRecord(task);
        // enable OnBackInvokedCallbacks
        record2.info.applicationInfo.privateFlagsExt |=
                PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        WindowState window1 = createWindow(null, FIRST_APPLICATION_WINDOW, record1, "window1");
        WindowState window2 = createWindow(null, FIRST_APPLICATION_WINDOW, record2, "window2");
        when(task.mSurfaceControl.isValid()).thenReturn(true);
        when(record1.mSurfaceControl.isValid()).thenReturn(true);
        when(record2.mSurfaceControl.isValid()).thenReturn(true);
        Mockito.doNothing().when(task).reparentSurfaceControl(any(), any());
        Mockito.doNothing().when(record1).reparentSurfaceControl(any(), any());
        Mockito.doNothing().when(record2).reparentSurfaceControl(any(), any());
        mAtm.setFocusedTask(task.mTaskId, record1);
        mAtm.setFocusedTask(task.mTaskId, record2);
        addToWindowMap(window1, true);
        addToWindowMap(window2, true);

        makeWindowVisibleAndDrawn(window2);

        CrossActivityTestCase testCase = new CrossActivityTestCase();
        testCase.task = task;
        testCase.recordBack = record1;
        testCase.recordFront = record2;
        return testCase;
    }

    private void addToWindowMap(WindowState window, boolean focus) {
        mWm.mWindowMap.put(window.mClient.asBinder(), window);
        if (focus) {
            doReturn(window.getWindowInfo().token)
                    .when(mWindowManagerInternal).getFocusedWindowToken();
            doReturn(window).when(mWm).getFocusedWindowLocked();
        }
    }

    private class CrossActivityTestCase {
        public Task task;
        public ActivityRecord recordBack;
        public ActivityRecord recordFront;
    }
}
