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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.HardwareBuffer;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;
import android.window.BackEvent;
import android.window.BackNavigationInfo;
import android.window.IOnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.TaskSnapshot;

import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(WindowTestRunner.class)
public class BackNavigationControllerTests extends WindowTestsBase {

    private BackNavigationController mBackNavigationController;
    private WindowManagerInternal mWindowManagerInternal;

    @Before
    public void setUp() throws Exception {
        mBackNavigationController = new BackNavigationController();
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        mWindowManagerInternal = mock(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);
        TaskSnapshotController taskSnapshotController = createMockTaskSnapshotController();
        mBackNavigationController.setTaskSnapshotController(taskSnapshotController);
    }

    @Test
    public void backNavInfo_HomeWhenBackToLauncher() {
        IOnBackInvokedCallback callback = withSystemCallback(createTopTaskWithActivity());

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getDepartingAnimationTarget()).isNotNull();
        assertThat(backNavigationInfo.getTaskWindowConfiguration()).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));
    }

    @Test
    public void backTypeCrossTaskWhenBackToPreviousTask() {
        Task taskA = createTask(mDefaultDisplay);
        createActivityRecord(taskA);
        withSystemCallback(createTopTaskWithActivity());
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_TASK));
    }

    @Test
    public void backTypeCrossActivityWhenBackToPreviousActivity() {
        Task task = createTopTaskWithActivity();
        WindowState window = createAppWindow(task, FIRST_APPLICATION_WINDOW, "window");
        addToWindowMap(window, true);
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallback(callback, OnBackInvokedDispatcher.PRIORITY_SYSTEM);
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));
        assertWithMessage("Activity callback").that(
                backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);

        // Until b/207481538 is implemented, this should be null
        assertThat(backNavigationInfo.getScreenshotSurface()).isNull();
        assertThat(backNavigationInfo.getScreenshotHardwareBuffer()).isNull();
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

        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallback(callback, OnBackInvokedDispatcher.PRIORITY_DEFAULT);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getType()).isEqualTo(BackNavigationInfo.TYPE_CALLBACK);
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
    }

    @Test
    public void preparesForBackToHome() {
        Task task = createTopTaskWithActivity();
        withSystemCallback(task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));
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

    private IOnBackInvokedCallback withSystemCallback(Task task) {
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        task.getTopMostActivity().getTopChild().setOnBackInvokedCallback(callback,
                OnBackInvokedDispatcher.PRIORITY_SYSTEM);
        return callback;
    }

    private IOnBackInvokedCallback withAppCallback(Task task) {
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        task.getTopMostActivity().getTopChild().setOnBackInvokedCallback(callback,
                OnBackInvokedDispatcher.PRIORITY_DEFAULT);
        return callback;
    }

    @Nullable
    private BackNavigationInfo startBackNavigation() {
        return mBackNavigationController.startBackNavigation(mWm, new StubTransaction());
    }

    @NonNull
    private IOnBackInvokedCallback createOnBackInvokedCallback() {
        return new IOnBackInvokedCallback.Stub() {
            @Override
            public void onBackStarted() {
            }

            @Override
            public void onBackProgressed(BackEvent backEvent) {
            }

            @Override
            public void onBackCancelled() {
            }

            @Override
            public void onBackInvoked() {
            }
        };
    }

    @NonNull
    private TaskSnapshotController createMockTaskSnapshotController() {
        TaskSnapshotController taskSnapshotController = mock(TaskSnapshotController.class);
        TaskSnapshot taskSnapshot = mock(TaskSnapshot.class);
        when(taskSnapshot.getHardwareBuffer()).thenReturn(mock(HardwareBuffer.class));
        when(taskSnapshotController.getSnapshot(anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(taskSnapshot);
        return taskSnapshotController;
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
        mAtm.setFocusedTask(task.mTaskId, record);
        addToWindowMap(window, true);
        return task;
    }

    private void addToWindowMap(WindowState window, boolean focus) {
        mWm.mWindowMap.put(window.mClient.asBinder(), window);
        if (focus) {
            doReturn(window.getWindowInfo().token)
                    .when(mWindowManagerInternal).getFocusedWindowToken();
        }
    }
}
