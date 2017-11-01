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
import static android.content.res.Configuration.EMPTY;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static org.mockito.Mockito.mock;

/**
 * A collection of static functions that can be referenced by other test packages to provide access
 * to WindowManager related test functionality.
 */
public class WindowTestUtils {
    public static int sNextTaskId = 0;

    /**
     * Retrieves an instance of {@link WindowManagerService}, creating it if necessary.
     */
    public static WindowManagerService getWindowManagerService(Context context) {
        return TestWindowManagerPolicy.getWindowManagerService(context);
    }

    /**
     * Retrieves an instance of a mock {@link WindowManagerService}.
     */
    public static WindowManagerService getMockWindowManagerService() {
        return mock(WindowManagerService.class);
    }

    /**
     * Creates a mock instance of {@link StackWindowController}.
     */
    public static StackWindowController createMockStackWindowContainerController() {
        StackWindowController controller = mock(StackWindowController.class);
        controller.mContainer = mock(TestTaskStack.class);
        return controller;
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    public static Task createTaskInStack(WindowManagerService service, TaskStack stack,
            int userId) {
        final Task newTask = new Task(sNextTaskId++, stack, userId, service, null, EMPTY, 0, false,
                false, new ActivityManager.TaskDescription(), null);
        stack.addTask(newTask, POSITION_TOP);
        return newTask;
    }

    /**
     * An extension of {@link TestTaskStack}, which overrides package scoped methods that would not
     * normally be mocked out.
     */
    public static class TestTaskStack extends TaskStack {
        TestTaskStack(WindowManagerService service, int stackId) {
            super(service, stackId);
        }

        @Override
        void addTask(Task task, int position, boolean showForAllUsers, boolean moveParents) {
            // Do nothing.
        }
    }

    /** Used so we can gain access to some protected members of the {@link AppWindowToken} class. */
    public static class TestAppWindowToken extends AppWindowToken {
        boolean mOnTop = false;

        TestAppWindowToken(DisplayContent dc) {
            super(dc.mService, new IApplicationToken.Stub() {}, false, dc, true /* fillsParent */,
                    null /* overrideConfig */, null /* bounds */);
        }

        TestAppWindowToken(WindowManagerService service, IApplicationToken token,
                boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
                boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
                int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
                boolean alwaysFocusable, AppWindowContainerController controller,
                Configuration overrideConfig, Rect bounds) {
            super(service, token, voiceInteraction, dc, inputDispatchingTimeoutNanos, fullscreen,
                    showForAllUsers, targetSdk, orientation, rotationAnimationHint, configChanges,
                    launchTaskBehind, alwaysFocusable, controller, overrideConfig, bounds);
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

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    public static class TestWindowToken extends WindowToken {
        int adj = 0;

        TestWindowToken(int type, DisplayContent dc) {
            this(type, dc, false /* persistOnEmpty */);
        }

        TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(dc.mService, mock(IBinder.class), type, persistOnEmpty, dc,
                    false /* ownerCanManageAppTokens */);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }

        @Override
        int getAnimLayerAdjustment() {
            return adj;
        }
    }

    /* Used so we can gain access to some protected members of the {@link Task} class */
    public static class TestTask extends Task {
        boolean mShouldDeferRemoval = false;
        boolean mOnDisplayChangedCalled = false;
        private boolean mUseLocalIsAnimating = false;
        private boolean mIsAnimating = false;

        TestTask(int taskId, TaskStack stack, int userId, WindowManagerService service, Rect bounds,
                Configuration overrideConfig, int resizeMode, boolean supportsPictureInPicture,
                boolean homeTask, TaskWindowContainerController controller) {
            super(taskId, stack, userId, service, bounds, overrideConfig, resizeMode,
                    supportsPictureInPicture, homeTask, new ActivityManager.TaskDescription(),
                    controller);
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
        boolean isAnimating() {
            return mUseLocalIsAnimating ? mIsAnimating : super.isAnimating();
        }

        void setLocalIsAnimating(boolean isAnimating) {
            mUseLocalIsAnimating = true;
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
                        public void onSnapshotChanged(ActivityManager.TaskSnapshot snapshot) {

                        }

                        @Override
                        public void requestResize(Rect bounds, int resizeMode) {

                        }
                    }, stackController, 0 /* userId */, null /* bounds */,
                    EMPTY /* overrideConfig*/, RESIZE_MODE_UNRESIZEABLE,
                    false /* supportsPictureInPicture */, false /* homeTask*/, true /* toTop*/,
                    true /* showForAllUsers */, new ActivityManager.TaskDescription(),
                    stackController.mService);
        }

        @Override
        TestTask createTask(int taskId, TaskStack stack, int userId, Rect bounds,
                Configuration overrideConfig, int resizeMode, boolean supportsPictureInPicture,
                boolean homeTask, ActivityManager.TaskDescription taskDescription) {
            return new TestTask(taskId, stack, userId, mService, bounds, overrideConfig, resizeMode,
                    supportsPictureInPicture, homeTask, this);
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
                    0 /* inputDispatchingTimeoutNanos */, taskController.mService,
                    null /* overrideConfig */, null /* bounds */);
            mToken = token;
        }

        @Override
        AppWindowToken createAppWindow(WindowManagerService service, IApplicationToken token,
                boolean voiceInteraction, DisplayContent dc, long inputDispatchingTimeoutNanos,
                boolean fullscreen, boolean showForAllUsers, int targetSdk, int orientation,
                int rotationAnimationHint, int configChanges, boolean launchTaskBehind,
                boolean alwaysFocusable, AppWindowContainerController controller,
                Configuration overrideConfig, Rect bounds) {
            return new TestAppWindowToken(service, token, voiceInteraction, dc,
                    inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk,
                    orientation,
                    rotationAnimationHint, configChanges, launchTaskBehind, alwaysFocusable,
                    controller, overrideConfig, bounds);
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
