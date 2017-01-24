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

package com.android.server.wm;

import android.app.ActivityManager.TaskDescription;
import android.app.ActivityManagerInternal;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.view.IApplicationToken;
import org.junit.Assert;
import org.junit.Before;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import android.content.Context;
import android.os.IBinder;
import android.support.test.InstrumentationRegistry;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.EMPTY;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static org.mockito.Mockito.mock;

import com.android.server.AttributeCache;
import com.android.server.LocalServices;

/**
 * Common base class for window manager unit test classes.
 */
class WindowTestsBase {
    static WindowManagerService sWm = null;
    static TestWindowManagerPolicy sPolicy = null;
    private final static IWindow sIWindow = new TestIWindow();
    private final static Session sMockSession = mock(Session.class);
    static int sNextStackId = FIRST_DYNAMIC_STACK_ID;
    private static int sNextTaskId = 0;

    private static boolean sOneTimeSetupDone = false;
    static DisplayContent sDisplayContent;
    static WindowLayersController sLayersController;
    static WindowState sWallpaperWindow;
    static WindowState sImeWindow;
    static WindowState sImeDialogWindow;
    static WindowState sStatusBarWindow;
    static WindowState sDockedDividerWindow;
    static WindowState sNavBarWindow;
    static WindowState sAppWindow;
    static WindowState sChildAppWindowAbove;
    static WindowState sChildAppWindowBelow;
    static @Mock ActivityManagerInternal sMockAm;

    @Before
    public void setUp() throws Exception {
        if (sOneTimeSetupDone) {
            Mockito.reset(sMockAm);
            return;
        }
        sOneTimeSetupDone = true;
        MockitoAnnotations.initMocks(this);
        final Context context = InstrumentationRegistry.getTargetContext();
        LocalServices.addService(ActivityManagerInternal.class, sMockAm);
        AttributeCache.init(context);
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
        sPolicy = (TestWindowManagerPolicy) sWm.mPolicy;
        sLayersController = new WindowLayersController(sWm);
        sDisplayContent = new DisplayContent(context.getDisplay(), sWm, sLayersController,
                new WallpaperController(sWm));
        sWm.mRoot.addChild(sDisplayContent, 0);
        sWm.mDisplayEnabled = true;
        sWm.mDisplayReady = true;

        // Set-up some common windows.
        sWallpaperWindow = createWindow(null, TYPE_WALLPAPER, sDisplayContent, "wallpaperWindow");
        sImeWindow = createWindow(null, TYPE_INPUT_METHOD, sDisplayContent, "sImeWindow");
        sImeDialogWindow =
                createWindow(null, TYPE_INPUT_METHOD_DIALOG, sDisplayContent, "sImeDialogWindow");
        sStatusBarWindow = createWindow(null, TYPE_STATUS_BAR, sDisplayContent, "sStatusBarWindow");
        sNavBarWindow =
                createWindow(null, TYPE_NAVIGATION_BAR, sDisplayContent, "sNavBarWindow");
        sDockedDividerWindow =
                createWindow(null, TYPE_DOCK_DIVIDER, sDisplayContent, "sDockedDividerWindow");
        sAppWindow = createWindow(null, TYPE_BASE_APPLICATION, sDisplayContent, "sAppWindow");
        sChildAppWindowAbove = createWindow(sAppWindow,
                TYPE_APPLICATION_ATTACHED_DIALOG, sAppWindow.mToken, "sChildAppWindowAbove");
        sChildAppWindowBelow = createWindow(sAppWindow,
                TYPE_APPLICATION_MEDIA_OVERLAY, sAppWindow.mToken, "sChildAppWindowBelow");
    }

    /** Asserts that the first entry is greater than the second entry. */
    void assertGreaterThan(int first, int second) throws Exception {
        Assert.assertTrue("Excepted " + first + " to be greater than " + second, first > second);
    }

    /**
     * Waits until the main handler for WM has processed all messages.
     */
    void waitUntilHandlerIdle() {
        sWm.mH.runWithScissors(() -> { }, 0);
    }

    private static WindowToken createWindowToken(DisplayContent dc, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return new TestWindowToken(type, dc);
        }

        final TaskStack stack = createTaskStackOnDisplay(dc);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final TestAppWindowToken token = new TestAppWindowToken(dc);
        task.addChild(token, 0);
        return token;
    }

    static WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, sDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final AppWindowToken token = new TestAppWindowToken(sDisplayContent);
        task.addChild(token, 0);
        return createWindow(null, type, token, name);
    }

    static WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, type);
        return createWindow(parent, type, token, name);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(sWm, sMockSession, sIWindow, token, parent, OP_NONE,
                0, attrs, 0, 0);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    /** Creates a {@link TaskStack} and adds it to the specified {@link DisplayContent}. */
    static TaskStack createTaskStackOnDisplay(DisplayContent dc) {
        final int stackId = sNextStackId++;
        dc.addStackToDisplay(stackId, true);
        return sWm.mStackIdToStack.get(stackId);
    }

    /**Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    static Task createTaskInStack(TaskStack stack, int userId) {
        final Task newTask = new Task(sNextTaskId++, stack, userId, sWm, null, EMPTY, false, 0,
                false, false, new TaskDescription(), null);
        stack.addTask(newTask, POSITION_TOP);
        return newTask;
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    static class TestWindowToken extends WindowToken {

        TestWindowToken(int type, DisplayContent dc) {
            this(type, dc, false /* persistOnEmpty */);
        }

        TestWindowToken(int type, DisplayContent dc, boolean persistOnEmpty) {
            super(sWm, mock(IBinder.class), type, persistOnEmpty, dc);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }

    /** Used so we can gain access to some protected members of the {@link AppWindowToken} class. */
    static class TestAppWindowToken extends AppWindowToken {

        TestAppWindowToken(DisplayContent dc) {
            super(sWm, null, false, dc);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }

        WindowState getFirstChild() {
            return mChildren.getFirst();
        }

        WindowState getLastChild() {
            return mChildren.getLast();
        }
    }

    /* Used so we can gain access to some protected members of the {@link Task} class */
    class TestTask extends Task {

        boolean mShouldDeferRemoval = false;
        boolean mOnDisplayChangedCalled = false;

        TestTask(int taskId, TaskStack stack, int userId, WindowManagerService service, Rect bounds,
                Configuration overrideConfig, boolean isOnTopLauncher, int resizeMode,
                boolean supportsPictureInPicture, boolean homeTask,
                TaskWindowContainerController controller) {
            super(taskId, stack, userId, service, bounds, overrideConfig, isOnTopLauncher,
                    resizeMode, supportsPictureInPicture, homeTask, new TaskDescription(),
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
    }

    /**
     * Used so we can gain access to some protected members of {@link TaskWindowContainerController}
     * class.
     */
    class TestTaskWindowContainerController extends TaskWindowContainerController {

        TestTaskWindowContainerController() {
            this(createTaskStackOnDisplay(sDisplayContent).mStackId);
        }

        TestTaskWindowContainerController(int stackId) {
            super(sNextTaskId++, snapshot -> {}, stackId, 0 /* userId */, null /* bounds */,
                    EMPTY /* overrideConfig*/, RESIZE_MODE_UNRESIZEABLE,
                    false /* supportsPictureInPicture */, false /* homeTask*/,
                    false /* isOnTopLauncher */, true /* toTop*/, true /* showForAllUsers */,
                    new TaskDescription());
        }

        @Override
        TestTask createTask(int taskId, TaskStack stack, int userId, Rect bounds,
                Configuration overrideConfig, int resizeMode, boolean supportsPictureInPicture,
                boolean homeTask, boolean isOnTopLauncher, TaskDescription taskDescription) {
            return new TestTask(taskId, stack, userId, mService, bounds, overrideConfig,
                    isOnTopLauncher, resizeMode, supportsPictureInPicture, homeTask, this);
        }
    }

    class TestAppWindowContainerController extends AppWindowContainerController {

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
                    0 /* inputDispatchingTimeoutNanos */, sWm);
            mToken = token;
        }

        AppWindowToken getAppWindowToken() {
            return (AppWindowToken) sDisplayContent.getWindowToken(mToken.asBinder());
        }
    }

    class TestIApplicationToken implements IApplicationToken {

        private final Binder mBinder = new Binder();
        @Override
        public IBinder asBinder() {
            return mBinder;
        }
    }

    /** Used to track resize reports. */
    class TestWindowState extends WindowState {
        boolean resizeReported;

        TestWindowState(WindowManager.LayoutParams attrs, WindowToken token) {
            super(sWm, sMockSession, sIWindow, token, null, OP_NONE, 0, attrs, 0, 0);
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
