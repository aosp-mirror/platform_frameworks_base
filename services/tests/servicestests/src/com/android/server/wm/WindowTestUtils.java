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

package com.android.server.wm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.invocation.InvocationOnMock;

/**
 * A collection of static functions that can be referenced by other test packages to provide access
 * to WindowManager related test functionality.
 */
public class WindowTestUtils {
    public static int sNextTaskId = 0;

    /**
     * Retrieves an instance of a mock {@link WindowManagerService}.
     */
    public static WindowManagerService getMockWindowManagerService() {
        final WindowManagerService service = mock(WindowManagerService.class);
        final WindowHashMap windowMap = new WindowHashMap();
        when(service.getWindowManagerLock()).thenReturn(windowMap);
        return service;
    }

    /**
     * Creates a mock instance of {@link StackWindowController}.
     */
    public static StackWindowController createMockStackWindowContainerController() {
        StackWindowController controller = mock(StackWindowController.class);
        controller.mContainer = mock(TestTaskStack.class);

        // many components rely on the {@link StackWindowController#adjustConfigurationForBounds}
        // to properly set bounds values in the configuration. We must mimick those actions here.
        doAnswer((InvocationOnMock invocationOnMock) -> {
            final Configuration config = invocationOnMock.<Configuration>getArgument(7);
            final Rect bounds = invocationOnMock.<Rect>getArgument(0);
            config.windowConfiguration.setBounds(bounds);
            return null;
        }).when(controller).adjustConfigurationForBounds(any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), anyFloat(), any(), any(), anyInt());

        return controller;
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    public static Task createTaskInStack(WindowManagerService service, TaskStack stack,
            int userId) {
        synchronized (service.mWindowMap) {
            final Task newTask = new Task(sNextTaskId++, stack, userId, service, 0, false,
                    new ActivityManager.TaskDescription(), null);
            stack.addTask(newTask, POSITION_TOP);
            return newTask;
        }
    }

    /**
     * An extension of {@link TestTaskStack}, which overrides package scoped methods that would not
     * normally be mocked out.
     */
    public static class TestTaskStack extends TaskStack {
        TestTaskStack(WindowManagerService service, int stackId) {
            super(service, stackId, null);
        }

        @Override
        void addTask(Task task, int position, boolean showForAllUsers, boolean moveParents) {
            // Do nothing.
        }
    }

    static TestAppWindowToken createTestAppWindowToken(DisplayContent dc) {
        synchronized (dc.mService.mWindowMap) {
            return new TestAppWindowToken(dc);
        }
    }

    /** Used so we can gain access to some protected members of the {@link AppWindowToken} class. */
    public static class TestAppWindowToken extends AppWindowToken {
        boolean mOnTop = false;

        private TestAppWindowToken(DisplayContent dc) {
            super(dc.mService, new IApplicationToken.Stub() {
                public String getName() {return null;}
                }, false, dc, true /* fillsParent */);
        }

        TestAppWindowToken(WindowManagerService service, IApplicationToken token,
                boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
                boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
                int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
                boolean alwaysFocusable, AppWindowContainerController controller) {
            super(service, token, voiceInteraction, dc, inputDispatchingTimeoutNanos, fullscreen,
                    showForAllUsers, targetSdk, orientation, rotationAnimationHint, configChanges,
                    launchTaskBehind, alwaysFocusable, controller);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }

        WindowState getFirstChild() {
            return mChildren.peekFirst();
        }

        WindowState getLastChild() {
            return mChildren.peekLast();
        }

        int positionInParent() {
            return getParent().mChildren.indexOf(this);
        }

        void setIsOnTop(boolean onTop) {
            mOnTop = onTop;
        }

        @Override
        boolean isOnTop() {
            return mOnTop;
        }
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc) {
        return createTestWindowToken(type, dc, false /* persistOnEmpty */);
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc,
            boolean persistOnEmpty) {
        synchronized (dc.mService.mWindowMap) {
            return new TestWindowToken(type, dc, persistOnEmpty);
        }
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    public static class TestWindowToken extends WindowToken {

        private TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(dc.mService, mock(IBinder.class), type, persistOnEmpty, dc,
                    false /* ownerCanManageAppTokens */);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }

    /* Used so we can gain access to some protected members of the {@link Task} class */
    public static class TestTask extends Task {
        boolean mShouldDeferRemoval = false;
        boolean mOnDisplayChangedCalled = false;
        private boolean mIsAnimating = false;

        TestTask(int taskId, TaskStack stack, int userId, WindowManagerService service,
                int resizeMode, boolean supportsPictureInPicture,
                TaskWindowContainerController controller) {
            super(taskId, stack, userId, service, resizeMode, supportsPictureInPicture,
                    new ActivityManager.TaskDescription(), controller);
        }

        boolean shouldDeferRemoval() {
            return mShouldDeferRemoval;
        }

        int positionInParent() {
            return getParent().mChildren.indexOf(this);
        }

        @Override
        void onDisplayChanged(DisplayContent dc) {
            super.onDisplayChanged(dc);
            mOnDisplayChangedCalled = true;
        }

        @Override
        boolean isSelfAnimating() {
            return mIsAnimating;
        }

        void setLocalIsAnimating(boolean isAnimating) {
            mIsAnimating = isAnimating;
        }
    }

    /**
     * Used so we can gain access to some protected members of {@link TaskWindowContainerController}
     * class.
     */
    public static class TestTaskWindowContainerController extends TaskWindowContainerController {

        TestTaskWindowContainerController(WindowTestsBase testsBase) {
            this(testsBase.createStackControllerOnDisplay(testsBase.mDisplayContent));
        }

        TestTaskWindowContainerController(StackWindowController stackController) {
            super(sNextTaskId++, new TaskWindowContainerListener() {
                        @Override
                        public void registerConfigurationChangeListener(
                                ConfigurationContainerListener listener) {

                        }

                        @Override
                        public void unregisterConfigurationChangeListener(
                                ConfigurationContainerListener listener) {

                        }

                        @Override
                        public void onSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {

                        }

                        @Override
                        public void requestResize(Rect bounds, int resizeMode) {

                        }
                    }, stackController, 0 /* userId */, null /* bounds */, RESIZE_MODE_UNRESIZEABLE,
                    false /* supportsPictureInPicture */, true /* toTop*/,
                    true /* showForAllUsers */, new ActivityManager.TaskDescription(),
                    stackController.mService);
        }

        @Override
        TestTask createTask(int taskId, TaskStack stack, int userId, int resizeMode,
                boolean supportsPictureInPicture, ActivityManager.TaskDescription taskDescription) {
            return new TestTask(taskId, stack, userId, mService, resizeMode,
                    supportsPictureInPicture, this);
        }
    }

    public static class TestAppWindowContainerController extends AppWindowContainerController {

        final IApplicationToken mToken;

        TestAppWindowContainerController(TestTaskWindowContainerController taskController) {
            this(taskController, new TestIApplicationToken());
        }

        TestAppWindowContainerController(TestTaskWindowContainerController taskController,
                IApplicationToken token) {
            super(taskController, token, null /* listener */, 0 /* index */,
                    SCREEN_ORIENTATION_UNSPECIFIED, true /* fullscreen */,
                    true /* showForAllUsers */, 0 /* configChanges */, false /* voiceInteraction */,
                    false /* launchTaskBehind */, false /* alwaysFocusable */,
                    0 /* targetSdkVersion */, 0 /* rotationAnimationHint */,
                    0 /* inputDispatchingTimeoutNanos */, taskController.mService);
            mToken = token;
        }

        @Override
        AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token,
                boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
                boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
                int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
                boolean alwaysFocusable, AppWindowContainerController controller) {
            return new TestAppWindowToken(service, token, voiceInteraction, dc,
                    inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk,
                    orientation,
                    rotationAnimationHint, configChanges, launchTaskBehind, alwaysFocusable,
                    controller);
        }

        AppWindowToken getAppWindowToken(DisplayContent dc) {
            return (AppWindowToken) dc.getWindowToken(mToken.asBinder());
        }
    }

    public static class TestIApplicationToken implements IApplicationToken {

        private final Binder mBinder = new Binder();
        @Override
        public IBinder asBinder() {
            return mBinder;
        }
        @Override
        public String getName() {
            return null;
        }
    }

    /** Used to track resize reports. */
    public static class TestWindowState extends WindowState {
        boolean resizeReported;

        TestWindowState(WindowManagerService service, Session session, IWindow window,
                WindowManager.LayoutParams attrs, WindowToken token) {
            super(service, session, window, token, null, OP_NONE, 0, attrs, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
        }

        @Override
        void reportResized() {
            super.reportResized();
            resizeReported = true;
        }

        @Override
        public boolean isGoneForLayoutLw() {
            return false;
        }

        @Override
        void updateResizingWindowIfNeeded() {
            // Used in AppWindowTokenTests#testLandscapeSeascapeRotationRelayout to deceive
            // the system that it can actually update the window.
            boolean hadSurface = mHasSurface;
            mHasSurface = true;

            super.updateResizingWindowIfNeeded();

            mHasSurface = hadSurface;
        }
    }
}
