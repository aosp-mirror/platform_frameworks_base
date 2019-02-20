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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.view.Display;
import android.view.IApplicationToken;
import android.view.IWindow;
import android.view.Surface;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager;

/**
 * A collection of static functions that can be referenced by other test packages to provide access
 * to WindowManager related test functionality.
 */
public class WindowTestUtils {
    private static int sNextTaskId = 0;

    /** An extension of {@link DisplayContent} to gain package scoped access. */
    public static class TestDisplayContent extends DisplayContent {

        private TestDisplayContent(Display display, WindowManagerService service,
                ActivityDisplay activityDisplay) {
            super(display, service, activityDisplay);
        }

        /**
         * Stubbing method of non-public parent class isn't supported, so here explicitly overrides.
         */
        @Override
        public DisplayRotation getDisplayRotation() {
            return null;
        }

        /**
         * Stubbing method of non-public parent class isn't supported, so here explicitly overrides.
         */
        @Override
        DockedStackDividerController getDockedDividerController() {
            return null;
        }

        /** Create a mocked default {@link DisplayContent}. */
        public static TestDisplayContent create(Context context) {
            final TestDisplayContent displayContent = mock(TestDisplayContent.class);
            displayContent.isDefaultDisplay = true;

            final DisplayPolicy displayPolicy = mock(DisplayPolicy.class);
            when(displayPolicy.navigationBarCanMove()).thenReturn(true);
            when(displayPolicy.hasNavigationBar()).thenReturn(true);

            final DisplayRotation displayRotation = new DisplayRotation(
                    mock(WindowManagerService.class), displayContent, displayPolicy,
                    mock(DisplayWindowSettings.class), context, new Object());
            displayRotation.mPortraitRotation = Surface.ROTATION_0;
            displayRotation.mLandscapeRotation = Surface.ROTATION_90;
            displayRotation.mUpsideDownRotation = Surface.ROTATION_180;
            displayRotation.mSeascapeRotation = Surface.ROTATION_270;

            when(displayContent.getDisplayRotation()).thenReturn(displayRotation);

            return displayContent;
        }
    }

    /** Create a mocked default {@link DisplayContent}. */
    public static TestDisplayContent createTestDisplayContent() {
        final TestDisplayContent displayContent = mock(TestDisplayContent.class);
        DockedStackDividerController divider = mock(DockedStackDividerController.class);
        when(displayContent.getDockedDividerController()).thenReturn(divider);

        return displayContent;
    }

    /**
     * Creates a mock instance of {@link TestTaskStack}.
     */
    public static TaskStack createMockTaskStack() {
        return mock(TestTaskStack.class);
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    public static Task createTaskInStack(WindowManagerService service, TaskStack stack,
            int userId) {
        synchronized (service.mGlobalLock) {
            final Task newTask = new Task(sNextTaskId++, stack, userId, service, 0, false,
                    new ActivityManager.TaskDescription(), null);
            stack.addTask(newTask, POSITION_TOP);
            return newTask;
        }
    }

    /** Creates an {@link AppWindowToken} and adds it to the specified {@link Task}. */
    public static TestAppWindowToken createAppWindowTokenInTask(DisplayContent dc, Task task) {
        final TestAppWindowToken newToken = createTestAppWindowToken(dc);
        task.addChild(newToken, POSITION_TOP);
        return newToken;
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
        synchronized (dc.mWmService.mGlobalLock) {
            return new TestAppWindowToken(dc, true /* skipOnParentChanged */);
        }
    }

    static TestAppWindowToken createTestAppWindowToken(DisplayContent dc,
            boolean skipOnParentChanged) {
        synchronized (dc.mWmService.mGlobalLock) {
            return new TestAppWindowToken(dc, skipOnParentChanged);
        }
    }

    /** Used so we can gain access to some protected members of the {@link AppWindowToken} class. */
    public static class TestAppWindowToken extends AppWindowToken {
        boolean mOnTop = false;
        private boolean mSkipPrepareSurfaces;
        private Transaction mPendingTransactionOverride;
        boolean mSkipOnParentChanged = true;

        private TestAppWindowToken(DisplayContent dc, boolean skipOnParentChanged) {
            super(dc.mWmService, new IApplicationToken.Stub() {
                @Override
                public String getName() {
                    return null;
                }
            }, new ComponentName("", ""), false, dc, true /* fillsParent */);
            mTargetSdk = Build.VERSION_CODES.CUR_DEVELOPMENT;
            mSkipOnParentChanged = skipOnParentChanged;
        }

        TestAppWindowToken(WindowManagerService service, IApplicationToken token,
                ComponentName activityComponent, boolean voiceInteraction, DisplayContent dc,
                long inputDispatchingTimeoutNanos, boolean fullscreen, boolean showForAllUsers,
                int targetSdk, int orientation, int rotationAnimationHint, int configChanges,
                boolean launchTaskBehind, boolean alwaysFocusable, ActivityRecord activityRecord) {
            super(service, token, activityComponent, voiceInteraction, dc,
                    inputDispatchingTimeoutNanos, fullscreen, showForAllUsers, targetSdk,
                    orientation, rotationAnimationHint, configChanges, launchTaskBehind,
                    alwaysFocusable, activityRecord);
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
        void onParentChanged() {
            if (!mSkipOnParentChanged) {
                super.onParentChanged();
            } else {
                updateConfigurationFromParent(this);
            }
        }

        @Override
        boolean isOnTop() {
            return mOnTop;
        }

        @Override
        void prepareSurfaces() {
            if (!mSkipPrepareSurfaces) {
                super.prepareSurfaces();
            }
        }

        void setSkipPrepareSurfaces(boolean ignore) {
            mSkipPrepareSurfaces = ignore;
        }

        void setPendingTransaction(Transaction transaction) {
            mPendingTransactionOverride = transaction;
        }

        @Override
        public Transaction getPendingTransaction() {
            return mPendingTransactionOverride == null
                    ? super.getPendingTransaction()
                    : mPendingTransactionOverride;
        }
    }

    /**
     * Used when we don't want to perform surface related operation in
     * {@link WindowContainer#onParentChanged} or the overridden method, but the configuration
     * still needs to propagate from parent.
     *
     * @see ConfigurationContainer#onParentChanged
     */
    static void updateConfigurationFromParent(WindowContainer container) {
        final WindowContainer parent = container.getParent();
        if (parent != null) {
            container.onConfigurationChanged(parent.getConfiguration());
            container.onMergedOverrideConfigurationChanged();
        }
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc) {
        return createTestWindowToken(type, dc, false /* persistOnEmpty */);
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc,
            boolean persistOnEmpty) {
        synchronized (dc.mWmService.mGlobalLock) {
            return new TestWindowToken(type, dc, persistOnEmpty);
        }
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    public static class TestWindowToken extends WindowToken {

        private TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(dc.mWmService, mock(IBinder.class), type, persistOnEmpty, dc,
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
                TaskRecord taskRecord) {
            super(taskId, stack, userId, service, resizeMode, supportsPictureInPicture,
                    new ActivityManager.TaskDescription(), taskRecord);
            stack.addTask(this, POSITION_TOP);
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

    public static TestTask createTestTask(TaskStack stack) {
        return new TestTask(sNextTaskId++, stack, 0, stack.mWmService, RESIZE_MODE_UNRESIZEABLE,
                false, mock(TaskRecord.class));
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
        boolean mResizeReported;

        TestWindowState(WindowManagerService service, Session session, IWindow window,
                WindowManager.LayoutParams attrs, WindowToken token) {
            super(service, session, window, token, null, OP_NONE, 0, attrs, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
        }

        @Override
        void reportResized() {
            super.reportResized();
            mResizeReported = true;
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

        @Override
        void onParentChanged() {
            updateConfigurationFromParent(this);
        }
    }
}
