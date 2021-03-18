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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.os.Process.SYSTEM_UID;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.StartingSurfaceController.DEBUG_ENABLE_SHELL_DRAWER;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.voice.IVoiceInteractionSession;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.window.ITaskOrganizer;
import android.window.ITransitionPlayer;
import android.window.StartingWindowInfo;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;

import com.android.internal.policy.AttributeCache;
import com.android.internal.util.ArrayUtils;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.Description;
import org.mockito.Mockito;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashMap;

/** Common base class for window manager unit test classes. */
class WindowTestsBase extends SystemServiceTestsBase {
    final Context mContext = getInstrumentation().getTargetContext();

    // Default package name
    static final String DEFAULT_COMPONENT_PACKAGE_NAME = "com.foo";

    // Default base activity name
    private static final String DEFAULT_COMPONENT_CLASS_NAME = ".BarActivity";

    ActivityTaskManagerService mAtm;
    RootWindowContainer mRootWindowContainer;
    ActivityTaskSupervisor mSupervisor;
    WindowManagerService mWm;
    private final IWindow mIWindow = new TestIWindow();
    private Session mMockSession;

    DisplayInfo mDisplayInfo = new DisplayInfo();
    DisplayContent mDefaultDisplay;

    /**
     * It is {@link #mDefaultDisplay} by default. If the test class or method is annotated with
     * {@link UseTestDisplay}, it will be an additional display.
     */
    DisplayContent mDisplayContent;

    // The following fields are only available depending on the usage of annotation UseTestDisplay.
    WindowState mWallpaperWindow;
    WindowState mImeWindow;
    WindowState mImeDialogWindow;
    WindowState mStatusBarWindow;
    WindowState mNotificationShadeWindow;
    WindowState mDockedDividerWindow;
    WindowState mNavBarWindow;
    WindowState mAppWindow;
    WindowState mChildAppWindowAbove;
    WindowState mChildAppWindowBelow;

    /**
     * Spied {@link Transaction} class than can be used to verify calls.
     */
    Transaction mTransaction;

    @BeforeClass
    public static void setUpOnceBase() {
        AttributeCache.init(getInstrumentation().getTargetContext());
    }

    @Before
    public void setUpBase() {
        mAtm = mSystemServicesTestRule.getActivityTaskManagerService();
        mSupervisor = mAtm.mTaskSupervisor;
        mRootWindowContainer = mAtm.mRootWindowContainer;
        mWm = mSystemServicesTestRule.getWindowManagerService();
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        mDefaultDisplay = mWm.mRoot.getDefaultDisplay();
        mTransaction = mSystemServicesTestRule.mTransaction;
        mMockSession = mock(Session.class);

        mContext.getSystemService(DisplayManager.class)
                .getDisplay(Display.DEFAULT_DISPLAY).getDisplayInfo(mDisplayInfo);

        // Only create an additional test display for annotated test class/method because it may
        // significantly increase the execution time.
        final Description description = mSystemServicesTestRule.getDescription();
        UseTestDisplay testDisplayAnnotation = description.getAnnotation(UseTestDisplay.class);
        if (testDisplayAnnotation == null) {
            testDisplayAnnotation = description.getTestClass().getAnnotation(UseTestDisplay.class);
        }
        if (testDisplayAnnotation != null) {
            createTestDisplay(testDisplayAnnotation);
        } else {
            mDisplayContent = mDefaultDisplay;
        }

        // Ensure letterbox aspect ratio is not overridden on any device target.
        // {@link com.android.internal.R.dimen.config_fixedOrientationLetterboxAspectRatio}, is set
        // on some device form factors.
        mAtm.mWindowManager.setFixedOrientationLetterboxAspectRatio(0);
    }

    private void createTestDisplay(UseTestDisplay annotation) {
        beforeCreateTestDisplay();
        mDisplayContent = createNewDisplayWithImeSupport(DISPLAY_IME_POLICY_LOCAL);

        final boolean addAll = annotation.addAllCommonWindows();
        final @CommonTypes int[] requestedWindows = annotation.addWindows();

        if (addAll || ArrayUtils.contains(requestedWindows, W_WALLPAPER)) {
            mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_INPUT_METHOD)) {
            mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
            mDisplayContent.mInputMethodWindow = mImeWindow;
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_INPUT_METHOD_DIALOG)) {
            mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                    "mImeDialogWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_STATUS_BAR)) {
            mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_NOTIFICATION_SHADE)) {
            mNotificationShadeWindow = createCommonWindow(null, TYPE_NOTIFICATION_SHADE,
                    "mNotificationShadeWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_NAVIGATION_BAR)) {
            mNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "mNavBarWindow");
        }
        if (addAll || ArrayUtils.contains(requestedWindows, W_DOCK_DIVIDER)) {
            mDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER,
                    "mDockedDividerWindow");
        }
        final boolean addAboveApp = ArrayUtils.contains(requestedWindows, W_ABOVE_ACTIVITY);
        final boolean addBelowApp = ArrayUtils.contains(requestedWindows, W_BELOW_ACTIVITY);
        if (addAll || addAboveApp || addBelowApp
                || ArrayUtils.contains(requestedWindows, W_ACTIVITY)) {
            mAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");
        }
        if (addAll || addAboveApp) {
            mChildAppWindowAbove = createCommonWindow(mAppWindow, TYPE_APPLICATION_ATTACHED_DIALOG,
                    "mChildAppWindowAbove");
        }
        if (addAll || addBelowApp) {
            mChildAppWindowBelow = createCommonWindow(mAppWindow, TYPE_APPLICATION_MEDIA_OVERLAY,
                    "mChildAppWindowBelow");
        }

        mDisplayContent.getInsetsPolicy().setRemoteInsetsControllerControlsSystemBars(false);

        // Adding a display will cause freezing the display. Make sure to wait until it's
        // unfrozen to not run into race conditions with the tests.
        waitUntilHandlersIdle();
    }

    void beforeCreateTestDisplay() {
        // Called before display is created.
    }

    private WindowState createCommonWindow(WindowState parent, int type, String name) {
        final WindowState win = createWindow(parent, type, name);
        // Prevent common windows from been IME targets.
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        return win;
    }

    private WindowToken createWindowToken(
            DisplayContent dc, int windowingMode, int activityType, int type) {
        if (type == TYPE_WALLPAPER) {
            return createWallpaperToken(dc);
        }
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return createTestWindowToken(type, dc);
        }

        return createActivityRecord(dc, windowingMode, activityType);
    }

    private WindowToken createWallpaperToken(DisplayContent dc) {
        return new WallpaperWindowToken(mWm, mock(IBinder.class), true /* explicit */, dc,
                true /* ownerCanManageAppTokens */);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final ActivityRecord activity = createNonAttachedActivityRecord(task.getDisplayContent());
        task.addChild(activity, 0);
        return createWindow(null, type, activity, name);
    }

    // TODO: Move these calls to a builder?
    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            IWindow iwindow) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                false /* ownerCanAddInternalSystemWindow */, iwindow);
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, mDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    WindowState createWindow(WindowState parent, int type, String name, int ownerId) {
        return (parent == null)
                ? createWindow(parent, type, mDisplayContent, name, ownerId)
                : createWindow(parent, type, parent.mToken, name, ownerId);
    }

    WindowState createWindow(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        return createWindow(
                parent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type, dc, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            int ownerId) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, ownerId);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                ownerCanAddInternalSystemWindow);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        return createWindow(parent, type, token, name, 0 /* ownerId */,
                false /* ownerCanAddInternalSystemWindow */);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId) {
        return createWindow(parent, type, token, name, ownerId,
                false /* ownerCanAddInternalSystemWindow */);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow) {
        return createWindow(parent, type, token, name, ownerId, ownerCanAddInternalSystemWindow,
                mIWindow);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow, IWindow iwindow) {
        return createWindow(parent, type, token, name, ownerId, UserHandle.getUserId(ownerId),
                ownerCanAddInternalSystemWindow, mWm, mMockSession, iwindow,
                mSystemServicesTestRule.getPowerManagerWrapper());
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, int userId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow,
            WindowState.PowerManagerWrapper powerManagerWrapper) {
        SystemServicesTestRule.checkHoldsLock(service.mGlobalLock);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);
        attrs.packageName = "test";

        final WindowState w = new WindowState(service, session, iWindow, token, parent,
                OP_NONE, attrs, VISIBLE, ownerId, userId,
                ownerCanAddInternalSystemWindow,
                powerManagerWrapper);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    static void makeWindowVisible(WindowState... windows) {
        for (WindowState win : windows) {
            win.mViewVisibility = View.VISIBLE;
            win.mRelayoutCalled = true;
            win.mHasSurface = true;
            win.mHidden = false;
            win.show(false /* doAnimation */, false /* requestAnim */);
        }
    }

    static void makeWindowVisibleAndDrawn(WindowState... windows) {
        makeWindowVisible(windows);
        for (WindowState win : windows) {
            win.mWinAnimator.mDrawState = HAS_DRAWN;
        }
    }

    /**
     * Gets the order of the given {@link Task} as its z-order in the hierarchy below this TDA.
     * The Task can be a direct child of a child TaskDisplayArea. {@code -1} if not found.
     */
    static int getTaskIndexOf(TaskDisplayArea taskDisplayArea, Task task) {
        int index = 0;
        final int childCount = taskDisplayArea.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final WindowContainer wc = taskDisplayArea.getChildAt(i);
            if (wc.asTask() != null) {
                if (wc.asTask() == task) {
                    return index;
                }
                index++;
            } else {
                final TaskDisplayArea tda = wc.asTaskDisplayArea();
                final int subIndex = getTaskIndexOf(tda, task);
                if (subIndex > -1) {
                    return index + subIndex;
                } else {
                    index += tda.getRootTaskCount();
                }
            }
        }
        return -1;
    }

    /** Creates a {@link TaskDisplayArea} right above the default one. */
    static TaskDisplayArea createTaskDisplayArea(DisplayContent displayContent,
            WindowManagerService service, String name, int displayAreaFeature) {
        final TaskDisplayArea newTaskDisplayArea = new TaskDisplayArea(
                displayContent, service, name, displayAreaFeature);
        final TaskDisplayArea defaultTaskDisplayArea = displayContent.getDefaultTaskDisplayArea();

        // Insert the new TDA to the correct position.
        defaultTaskDisplayArea.getParent().addChild(newTaskDisplayArea,
                defaultTaskDisplayArea.getParent().mChildren.indexOf(defaultTaskDisplayArea)
                        + 1);
        return newTaskDisplayArea;
    }

    /** Creates a {@link Task} and adds it to the specified {@link DisplayContent}. */
    Task createTaskStackOnDisplay(DisplayContent dc) {
        return createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, dc);
    }

    Task createTaskStackOnDisplay(int windowingMode, int activityType, DisplayContent dc) {
        return new TaskBuilder(dc.mAtmService.mTaskSupervisor)
                .setDisplay(dc)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setIntent(new Intent())
                .build();
    }

    Task createTaskStackOnTaskDisplayArea(int windowingMode, int activityType,
            TaskDisplayArea tda) {
        return new TaskBuilder(tda.mDisplayContent.mAtmService.mTaskSupervisor)
                .setTaskDisplayArea(tda)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setIntent(new Intent())
                .build();
    }

    /** Creates a {@link Task} and adds it to the specified {@link Task}. */
    Task createTaskInStack(Task stack, int userId) {
        final Task task = new TaskBuilder(stack.mTaskSupervisor)
                .setUserId(userId)
                .setParentTask(stack)
                .build();
        return task;
    }

    Task createTaskWithActivity(TaskDisplayArea taskDisplayArea,
            int windowingMode, int activityType, boolean onTop, boolean twoLevelTask) {
        final TaskBuilder builder = new TaskBuilder(mSupervisor)
                .setTaskDisplayArea(taskDisplayArea)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setOnTop(onTop)
                .setCreateActivity(true);
        if (twoLevelTask) {
            return builder
                    .setCreateParentTask(true)
                    .build()
                    .getRootTask();
        } else {
            return builder.build();
        }
    }

    /** Creates an {@link ActivityRecord}. */
    static ActivityRecord createNonAttachedActivityRecord(DisplayContent dc) {
        final ActivityRecord activity = new ActivityBuilder(dc.mWmService.mAtmService)
                .setOnTop(true)
                .build();
        postCreateActivitySetup(activity, dc);
        return activity;
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * [Task] - [ActivityRecord]
     */
    ActivityRecord createActivityRecord(DisplayContent dc) {
        return createActivityRecord(dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * [Task] - [ActivityRecord]
     */
    ActivityRecord createActivityRecord(DisplayContent dc, int windowingMode,
            int activityType) {
        final Task task = createTaskStackOnDisplay(windowingMode, activityType, dc);
        return createActivityRecord(dc, task);
    }

    /**
     *  Creates an {@link ActivityRecord} and adds it to the specified {@link Task}.
     * [Task] - [ActivityRecord]
     */
    static ActivityRecord createActivityRecord(Task task) {
        return createActivityRecord(task.getDisplayContent(), task);
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to the specified {@link Task}.
     * [Task] - [ActivityRecord]
     */
    static ActivityRecord createActivityRecord(DisplayContent dc, Task task) {
        final ActivityRecord activity = new ActivityBuilder(dc.mWmService.mAtmService)
                .setTask(task)
                .setOnTop(true)
                .build();
        postCreateActivitySetup(activity, dc);
        return activity;
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * Then adds the new created {@link Task} to a new created parent {@link Task}
     * [Task1] - [Task2] - [ActivityRecord]
     */
    ActivityRecord createActivityRecordWithParentTask(DisplayContent dc, int windowingMode,
            int activityType) {
        final Task task = createTaskStackOnDisplay(windowingMode, activityType, dc);
        return createActivityRecordWithParentTask(task);
    }

    /**
     * Creates an {@link ActivityRecord} and adds it to a new created {@link Task}.
     * Then adds the new created {@link Task} to the specified parent {@link Task}
     * [Task1] - [Task2] - [ActivityRecord]
     */
    static ActivityRecord createActivityRecordWithParentTask(Task parentTask) {
        final ActivityRecord activity = new ActivityBuilder(parentTask.mAtmService)
                .setParentTask(parentTask)
                .setCreateTask(true)
                .setOnTop(true)
                .build();
        postCreateActivitySetup(activity, parentTask.getDisplayContent());
        return activity;
    }

    private static void postCreateActivitySetup(ActivityRecord activity, DisplayContent dc) {
        activity.onDisplayChanged(dc);
        activity.setOccludesParent(true);
        activity.setVisible(true);
        activity.mVisibleRequested = true;
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay() {
        return createNewDisplayWithImeSupport(DISPLAY_IME_POLICY_LOCAL);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplayWithImeSupport(@DisplayImePolicy int imePolicy) {
        return createNewDisplay(mDisplayInfo, imePolicy);
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay(DisplayInfo info) {
        return createNewDisplay(info, DISPLAY_IME_POLICY_LOCAL);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplay(DisplayInfo info, @DisplayImePolicy int imePolicy) {
        final DisplayContent display =
                new TestDisplayContent.Builder(mAtm, info).build();
        final DisplayContent dc = display.mDisplayContent;
        // this display can show IME.
        dc.mWmService.mDisplayWindowSettings.setDisplayImePolicy(dc, imePolicy);
        return dc;
    }

    /**
     * Creates a {@link DisplayContent} with given display state and adds it to the system.
     *
     * @param displayState For initializing the state of the display. See
     *                     {@link Display#getState()}.
     */
    DisplayContent createNewDisplay(int displayState) {
        // Leverage main display info & initialize it with display state for given displayId.
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.state = displayState;
        return createNewDisplay(displayInfo, DISPLAY_IME_POLICY_LOCAL);
    }

    /** Creates a {@link TestWindowState} */
    TestWindowState createWindowState(WindowManager.LayoutParams attrs, WindowToken token) {
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        return new TestWindowState(mWm, mMockSession, mIWindow, attrs, token);
    }

    /** Creates a {@link DisplayContent} as parts of simulate display info for test. */
    DisplayContent createMockSimulatedDisplay() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        displayInfo.ownerUid = SYSTEM_UID;
        return createNewDisplay(displayInfo, DISPLAY_IME_POLICY_FALLBACK_DISPLAY);
    }

    IDisplayWindowInsetsController createDisplayWindowInsetsController() {
        return new IDisplayWindowInsetsController.Stub() {

            @Override
            public void insetsChanged(InsetsState insetsState) throws RemoteException {
            }

            @Override
            public void insetsControlChanged(InsetsState insetsState,
                    InsetsSourceControl[] insetsSourceControls) throws RemoteException {
            }

            @Override
            public void showInsets(int i, boolean b) throws RemoteException {
            }

            @Override
            public void hideInsets(int i, boolean b) throws RemoteException {
            }

            @Override
            public void topFocusedWindowChanged(String packageName) {
            }
        };
    }

    /**
     * Avoids rotating screen disturbed by some conditions. It is usually used for the default
     * display that is not the instance of {@link TestDisplayContent} (it bypasses the conditions).
     *
     * @see DisplayRotation#updateRotationUnchecked
     */
    void unblockDisplayRotation(DisplayContent dc) {
        mWm.stopFreezingDisplayLocked();
        // The rotation animation won't actually play, it needs to be cleared manually.
        dc.setRotationAnimation(null);
    }

    // The window definition for UseTestDisplay#addWindows. The test can declare to add only
    // necessary windows, that avoids adding unnecessary overhead of unused windows.
    static final int W_NOTIFICATION_SHADE = TYPE_NOTIFICATION_SHADE;
    static final int W_STATUS_BAR = TYPE_STATUS_BAR;
    static final int W_NAVIGATION_BAR = TYPE_NAVIGATION_BAR;
    static final int W_INPUT_METHOD_DIALOG = TYPE_INPUT_METHOD_DIALOG;
    static final int W_INPUT_METHOD = TYPE_INPUT_METHOD;
    static final int W_DOCK_DIVIDER = TYPE_DOCK_DIVIDER;
    static final int W_ABOVE_ACTIVITY = TYPE_APPLICATION_ATTACHED_DIALOG;
    static final int W_ACTIVITY = TYPE_BASE_APPLICATION;
    static final int W_BELOW_ACTIVITY = TYPE_APPLICATION_MEDIA_OVERLAY;
    static final int W_WALLPAPER = TYPE_WALLPAPER;

    /** The common window types supported by {@link UseTestDisplay}. */
    @Retention(RetentionPolicy.RUNTIME)
    @IntDef(value = {
            W_NOTIFICATION_SHADE,
            W_STATUS_BAR,
            W_NAVIGATION_BAR,
            W_INPUT_METHOD_DIALOG,
            W_INPUT_METHOD,
            W_DOCK_DIVIDER,
            W_ABOVE_ACTIVITY,
            W_ACTIVITY,
            W_BELOW_ACTIVITY,
            W_WALLPAPER,
    })
    @interface CommonTypes {
    }

    /**
     * The annotation for class and method (higher priority) to create a non-default display that
     * will be assigned to {@link #mDisplayContent}. It is used if the test needs
     * <ul>
     * <li>Pure empty display.</li>
     * <li>Configured common windows.</li>
     * <li>Independent and customizable orientation.</li>
     * <li>Cross display operation.</li>
     * </ul>
     *
     * @see TestDisplayContent
     * @see #createTestDisplay
     **/
    @Target({ ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @interface UseTestDisplay {
        boolean addAllCommonWindows() default false;
        @CommonTypes int[] addWindows() default {};
    }

    /** Creates and adds a {@link TestDisplayContent} to supervisor at the given position. */
    TestDisplayContent addNewDisplayContentAt(int position) {
        return new TestDisplayContent.Builder(mAtm, 1000, 1500).setPosition(position).build();
    }

    /** Sets the default minimum task size to 1 so that tests can use small task sizes */
    public void removeGlobalMinSizeRestriction() {
        mAtm.mRootWindowContainer.mDefaultMinSizeOfResizeableTaskDp = 1;
    }

    /**
     * Builder for creating new activities.
     */
    protected static class ActivityBuilder {
        static final int DEFAULT_FAKE_UID = 12345;
        // An id appended to the end of the component name to make it unique
        private static int sCurrentActivityId = 0;

        private final ActivityTaskManagerService mService;

        private ComponentName mComponent;
        private String mTargetActivity;
        private Task mTask;
        private String mProcessName = "name";
        private String mAffinity;
        private int mUid = DEFAULT_FAKE_UID;
        private boolean mCreateTask = false;
        private Task mParentTask;
        private int mActivityFlags;
        private int mLaunchMode;
        private int mResizeMode = RESIZE_MODE_RESIZEABLE;
        private float mMaxAspectRatio;
        private float mMinAspectRatio;
        private boolean mSupportsSizeChanges;
        private int mScreenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        private boolean mLaunchTaskBehind = false;
        private int mConfigChanges;
        private int mLaunchedFromPid;
        private int mLaunchedFromUid;
        private WindowProcessController mWpc;
        private Bundle mIntentExtras;
        private boolean mOnTop = false;

        ActivityBuilder(ActivityTaskManagerService service) {
            mService = service;
        }

        ActivityBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        ActivityBuilder setTargetActivity(String targetActivity) {
            mTargetActivity = targetActivity;
            return this;
        }

        ActivityBuilder setIntentExtras(Bundle extras) {
            mIntentExtras = extras;
            return this;
        }

        static ComponentName getDefaultComponent() {
            return ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                    DEFAULT_COMPONENT_PACKAGE_NAME);
        }

        ActivityBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        ActivityBuilder setActivityFlags(int flags) {
            mActivityFlags = flags;
            return this;
        }

        ActivityBuilder setLaunchMode(int launchMode) {
            mLaunchMode = launchMode;
            return this;
        }

        ActivityBuilder setParentTask(Task parentTask) {
            mParentTask = parentTask;
            return this;
        }

        ActivityBuilder setCreateTask(boolean createTask) {
            mCreateTask = createTask;
            return this;
        }

        ActivityBuilder setProcessName(String name) {
            mProcessName = name;
            return this;
        }

        ActivityBuilder setUid(int uid) {
            mUid = uid;
            return this;
        }

        ActivityBuilder setResizeMode(int resizeMode) {
            mResizeMode = resizeMode;
            return this;
        }

        ActivityBuilder setMaxAspectRatio(float maxAspectRatio) {
            mMaxAspectRatio = maxAspectRatio;
            return this;
        }

        ActivityBuilder setMinAspectRatio(float minAspectRatio) {
            mMinAspectRatio = minAspectRatio;
            return this;
        }

        ActivityBuilder setSupportsSizeChanges(boolean supportsSizeChanges) {
            mSupportsSizeChanges = supportsSizeChanges;
            return this;
        }

        ActivityBuilder setScreenOrientation(int screenOrientation) {
            mScreenOrientation = screenOrientation;
            return this;
        }

        ActivityBuilder setLaunchTaskBehind(boolean launchTaskBehind) {
            mLaunchTaskBehind = launchTaskBehind;
            return this;
        }

        ActivityBuilder setConfigChanges(int configChanges) {
            mConfigChanges = configChanges;
            return this;
        }

        ActivityBuilder setLaunchedFromPid(int pid) {
            mLaunchedFromPid = pid;
            return this;
        }

        ActivityBuilder setLaunchedFromUid(int uid) {
            mLaunchedFromUid = uid;
            return this;
        }

        ActivityBuilder setUseProcess(WindowProcessController wpc) {
            mWpc = wpc;
            return this;
        }

        ActivityBuilder setAffinity(String affinity) {
            mAffinity = affinity;
            return this;
        }

        ActivityBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        ActivityRecord build() {
            SystemServicesTestRule.checkHoldsLock(mService.mGlobalLock);
            try {
                mService.deferWindowLayout();
                return buildInner();
            } finally {
                mService.continueWindowLayout();
            }
        }

        ActivityRecord buildInner() {
            if (mComponent == null) {
                final int id = sCurrentActivityId++;
                mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                        DEFAULT_COMPONENT_CLASS_NAME + id);
            }

            if (mCreateTask) {
                mTask = new TaskBuilder(mService.mTaskSupervisor)
                        .setComponent(mComponent)
                        .setParentTask(mParentTask).build();
            } else if (mTask == null && mParentTask != null && DisplayContent.alwaysCreateRootTask(
                    mParentTask.getWindowingMode(), mParentTask.getActivityType())) {
                // The stack can be the task root.
                mTask = mParentTask;
            }

            Intent intent = new Intent();
            intent.setComponent(mComponent);
            if (mIntentExtras != null) {
                intent.putExtras(mIntentExtras);
            }
            final ActivityInfo aInfo = new ActivityInfo();
            aInfo.applicationInfo = new ApplicationInfo();
            aInfo.applicationInfo.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;
            aInfo.applicationInfo.packageName = mComponent.getPackageName();
            aInfo.applicationInfo.uid = mUid;
            aInfo.processName = mProcessName;
            aInfo.packageName = mComponent.getPackageName();
            aInfo.name = mComponent.getClassName();
            if (mTargetActivity != null) {
                aInfo.targetActivity = mTargetActivity;
            }
            aInfo.flags |= mActivityFlags;
            aInfo.launchMode = mLaunchMode;
            aInfo.resizeMode = mResizeMode;
            aInfo.setMaxAspectRatio(mMaxAspectRatio);
            aInfo.setMinAspectRatio(mMinAspectRatio);
            aInfo.supportsSizeChanges = mSupportsSizeChanges;
            aInfo.screenOrientation = mScreenOrientation;
            aInfo.configChanges |= mConfigChanges;
            aInfo.taskAffinity = mAffinity;

            ActivityOptions options = null;
            if (mLaunchTaskBehind) {
                options = ActivityOptions.makeTaskLaunchBehind();
            }
            final ActivityRecord activity = new ActivityRecord.Builder(mService)
                    .setLaunchedFromPid(mLaunchedFromPid)
                    .setLaunchedFromUid(mLaunchedFromUid)
                    .setIntent(intent)
                    .setActivityInfo(aInfo)
                    .setActivityOptions(options)
                    .build();

            spyOn(activity);
            if (mTask != null) {
                // fullscreen value is normally read from resources in ctor, so for testing we need
                // to set it somewhere else since we can't mock resources.
                doReturn(true).when(activity).occludesParent();
                doReturn(true).when(activity).fillsParent();
                mTask.addChild(activity);
                if (mOnTop) {
                    // Move the task to front after activity added.
                    // Or {@link TaskDisplayArea#mPreferredTopFocusableStack} could be other stacks
                    // (e.g. home stack).
                    mTask.moveToFront("createActivity");
                }
                // Make visible by default...
                activity.mVisibleRequested = true;
                activity.setVisible(true);
            }

            final WindowProcessController wpc;
            if (mWpc != null) {
                wpc = mWpc;
            } else {
                wpc = new WindowProcessController(mService,
                        aInfo.applicationInfo, mProcessName, mUid,
                        UserHandle.getUserId(mUid), mock(Object.class),
                        mock(WindowProcessListener.class));
                wpc.setThread(mock(IApplicationThread.class));
            }
            wpc.setThread(mock(IApplicationThread.class));
            activity.setProcess(wpc);
            doReturn(wpc).when(mService).getProcessController(
                    activity.processName, activity.info.applicationInfo.uid);

            // Resume top activities to make sure all other signals in the system are connected.
            mService.mRootWindowContainer.resumeFocusedTasksTopActivities();
            return activity;
        }
    }

    /**
     * Builder for creating new tasks.
     */
    protected static class TaskBuilder {
        private final ActivityTaskSupervisor mSupervisor;

        private TaskDisplayArea mTaskDisplayArea;
        private ComponentName mComponent;
        private String mPackage;
        private int mFlags = 0;
        private int mTaskId = -1;
        private int mUserId = 0;
        private int mWindowingMode = WINDOWING_MODE_UNDEFINED;
        private int mActivityType = ACTIVITY_TYPE_STANDARD;
        private ActivityInfo mActivityInfo;
        private Intent mIntent;
        private boolean mOnTop = true;
        private IVoiceInteractionSession mVoiceSession;

        private boolean mCreateParentTask = false;
        private Task mParentTask;

        private boolean mCreateActivity = false;

        TaskBuilder(ActivityTaskSupervisor supervisor) {
            mSupervisor = supervisor;
            mTaskDisplayArea = mSupervisor.mRootWindowContainer.getDefaultTaskDisplayArea();
        }

        /**
         * Set the parent {@link DisplayContent} and use the default task display area. Overrides
         * the task display area, if was set before.
         */
        TaskBuilder setDisplay(DisplayContent display) {
            mTaskDisplayArea = display.getDefaultTaskDisplayArea();
            return this;
        }

        /** Set the parent {@link TaskDisplayArea}. Overrides the display, if was set before. */
        TaskBuilder setTaskDisplayArea(TaskDisplayArea taskDisplayArea) {
            mTaskDisplayArea = taskDisplayArea;
            return this;
        }

        TaskBuilder setComponent(ComponentName component) {
            mComponent = component;
            return this;
        }

        TaskBuilder setPackage(String packageName) {
            mPackage = packageName;
            return this;
        }

        TaskBuilder setFlags(int flags) {
            mFlags = flags;
            return this;
        }

        TaskBuilder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        TaskBuilder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        TaskBuilder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        TaskBuilder setActivityType(int activityType) {
            mActivityType = activityType;
            return this;
        }

        TaskBuilder setActivityInfo(ActivityInfo info) {
            mActivityInfo = info;
            return this;
        }

        TaskBuilder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        TaskBuilder setOnTop(boolean onTop) {
            mOnTop = onTop;
            return this;
        }

        TaskBuilder setVoiceSession(IVoiceInteractionSession session) {
            mVoiceSession = session;
            return this;
        }

        TaskBuilder setCreateParentTask(boolean createParentTask) {
            mCreateParentTask = createParentTask;
            return this;
        }

        TaskBuilder setParentTask(Task parentTask) {
            mParentTask = parentTask;
            return this;
        }

        TaskBuilder setCreateActivity(boolean createActivity) {
            mCreateActivity = createActivity;
            return this;
        }

        Task build() {
            SystemServicesTestRule.checkHoldsLock(mSupervisor.mService.mGlobalLock);

            // Create parent task.
            if (mParentTask == null && mCreateParentTask) {
                mParentTask = mTaskDisplayArea.createRootTask(
                        WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
            }
            if (mParentTask != null && !Mockito.mockingDetails(mParentTask).isSpy()) {
                spyOn(mParentTask);
            }

            // Create task.
            if (mActivityInfo == null) {
                mActivityInfo = new ActivityInfo();
                mActivityInfo.applicationInfo = new ApplicationInfo();
                mActivityInfo.applicationInfo.packageName = mPackage;
            }

            if (mIntent == null) {
                mIntent = new Intent();
                if (mComponent == null) {
                    mComponent = ComponentName.createRelative(DEFAULT_COMPONENT_PACKAGE_NAME,
                            DEFAULT_COMPONENT_CLASS_NAME);
                }
                mIntent.setComponent(mComponent);
                mIntent.setFlags(mFlags);
            }

            final Task.Builder builder = new Task.Builder(mSupervisor.mService)
                    .setTaskId(mTaskId >= 0 ? mTaskId : mTaskDisplayArea.getNextRootTaskId())
                    .setWindowingMode(mWindowingMode)
                    .setActivityInfo(mActivityInfo)
                    .setIntent(mIntent)
                    .setOnTop(mOnTop)
                    .setVoiceSession(mVoiceSession);
            final Task task;
            if (mParentTask == null) {
                task = builder.setActivityType(mActivityType)
                        .setParent(mTaskDisplayArea)
                        .build();
            } else {
                task = builder.setParent(mParentTask).build();
                mParentTask.moveToFront("build-task");
            }
            spyOn(task);
            task.mUserId = mUserId;
            final Task rootTask = task.getRootTask();
            if (task != rootTask && !Mockito.mockingDetails(rootTask).isSpy()) {
                spyOn(rootTask);
            }
            doNothing().when(rootTask).startActivityLocked(
                    any(), any(), anyBoolean(), anyBoolean(), any());

            // Create child task with activity.
            if (mCreateActivity) {
                new ActivityBuilder(mSupervisor.mService)
                        .setTask(task)
                        .build();
                if (mOnTop) {
                    // We move the task to front again in order to regain focus after activity
                    // added to the stack. Or {@link TaskDisplayArea#mPreferredTopFocusableStack}
                    // could be other stacks (e.g. home stack).
                    task.moveToFront("createActivityTask");
                } else {
                    task.moveToBack("createActivityTask", null);
                }
            }

            return task;
        }
    }

    static class TestStartingWindowOrganizer extends ITaskOrganizer.Stub {
        private final ActivityTaskManagerService mAtm;
        private final WindowManagerService mWMService;
        private final WindowState.PowerManagerWrapper mPowerManagerWrapper;

        private Runnable mRunnableWhenAddingSplashScreen;
        private final SparseArray<IBinder> mTaskAppMap = new SparseArray<>();
        private final HashMap<IBinder, WindowState> mAppWindowMap = new HashMap<>();

        TestStartingWindowOrganizer(ActivityTaskManagerService service,
                WindowState.PowerManagerWrapper powerManagerWrapper) {
            mAtm = service;
            mWMService = mAtm.mWindowManager;
            mPowerManagerWrapper = powerManagerWrapper;
            if (DEBUG_ENABLE_SHELL_DRAWER) {
                mAtm.mTaskOrganizerController.setDeferTaskOrgCallbacksConsumer(Runnable::run);
                mAtm.mTaskOrganizerController.registerTaskOrganizer(this);
            }
        }

        void setRunnableWhenAddingSplashScreen(Runnable r) {
            if (DEBUG_ENABLE_SHELL_DRAWER) {
                mRunnableWhenAddingSplashScreen = r;
            } else {
                ((TestWindowManagerPolicy) mWMService.mPolicy).setRunnableWhenAddingSplashScreen(r);
            }
        }

        @Override
        public void addStartingWindow(StartingWindowInfo info, IBinder appToken) {
            synchronized (mWMService.mGlobalLock) {
                final ActivityRecord activity = mWMService.mRoot.getActivityRecord(
                        appToken);
                IWindow iWindow = mock(IWindow.class);
                doReturn(mock(IBinder.class)).when(iWindow).asBinder();
                final WindowState window = WindowTestsBase.createWindow(null,
                        TYPE_APPLICATION_STARTING, activity,
                        "Starting window", 0 /* ownerId */, 0 /* userId*/,
                        false /* internalWindows */, mWMService, mock(Session.class),
                        iWindow,
                        mPowerManagerWrapper);
                activity.mStartingWindow = window;
                mAppWindowMap.put(appToken, window);
                mTaskAppMap.put(info.taskInfo.taskId, appToken);
            }
            if (mRunnableWhenAddingSplashScreen != null) {
                mRunnableWhenAddingSplashScreen.run();
                mRunnableWhenAddingSplashScreen = null;
            }
        }
        @Override
        public void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
                boolean playRevealAnimation) {
            synchronized (mWMService.mGlobalLock) {
                final IBinder appToken = mTaskAppMap.get(taskId);
                if (appToken != null) {
                    mTaskAppMap.remove(taskId);
                    final ActivityRecord activity = mWMService.mRoot.getActivityRecord(
                            appToken);
                    WindowState win = mAppWindowMap.remove(appToken);
                    activity.removeChild(win);
                    activity.mStartingWindow = null;
                }
            }
        }
        @Override
        public void copySplashScreenView(int taskId) {
        }
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo info, SurfaceControl leash) {
        }
        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo info) {
        }
        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
        }
        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        }
    }

    static class TestSplitOrganizer extends ITaskOrganizer.Stub {
        final ActivityTaskManagerService mService;
        Task mPrimary;
        Task mSecondary;
        boolean mInSplit = false;
        // moves everything to secondary. Most tests expect this since sysui usually does it.
        boolean mMoveToSecondaryOnEnter = true;
        int mDisplayId;
        private static final int[] CONTROLLED_ACTIVITY_TYPES = {
                ACTIVITY_TYPE_STANDARD,
                ACTIVITY_TYPE_HOME,
                ACTIVITY_TYPE_RECENTS,
                ACTIVITY_TYPE_UNDEFINED
        };
        private static final int[] CONTROLLED_WINDOWING_MODES = {
                WINDOWING_MODE_FULLSCREEN,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
                WINDOWING_MODE_UNDEFINED
        };
        TestSplitOrganizer(ActivityTaskManagerService service, DisplayContent display) {
            mService = service;
            mDisplayId = display.mDisplayId;
            mService.mTaskOrganizerController.registerTaskOrganizer(this);
            mPrimary = mService.mTaskOrganizerController.createRootTask(
                    display, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, null);
            mSecondary = mService.mTaskOrganizerController.createRootTask(
                    display, WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, null);;
        }
        TestSplitOrganizer(ActivityTaskManagerService service) {
            this(service, service.mTaskSupervisor.mRootWindowContainer.getDefaultDisplay());
        }
        public void setMoveToSecondaryOnEnter(boolean move) {
            mMoveToSecondaryOnEnter = move;
        }
        @Override
        public void addStartingWindow(StartingWindowInfo info, IBinder appToken) {
        }
        @Override
        public void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
                boolean playRevealAnimation) {
        }
        @Override
        public void copySplashScreenView(int taskId) {
        }
        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo info, SurfaceControl leash) {
        }
        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo info) {
        }
        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo info) {
            if (mInSplit) {
                return;
            }
            if (info.topActivityType == ACTIVITY_TYPE_UNDEFINED) {
                // Not populated
                return;
            }
            if (info.configuration.windowConfiguration.getWindowingMode()
                    != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                return;
            }
            mInSplit = true;
            if (!mMoveToSecondaryOnEnter) {
                return;
            }
            DisplayContent dc = mService.mRootWindowContainer.getDisplayContent(mDisplayId);
            dc.getDefaultTaskDisplayArea().setLaunchRootTask(
                    mSecondary, CONTROLLED_WINDOWING_MODES, CONTROLLED_ACTIVITY_TYPES);
            dc.forAllRootTasks(rootTask -> {
                if (!WindowConfiguration.isSplitScreenWindowingMode(rootTask.getWindowingMode())) {
                    rootTask.reparent(mSecondary, POSITION_BOTTOM);
                }
            });
        }
        @Override
        public void onBackPressedOnTaskRoot(ActivityManager.RunningTaskInfo taskInfo) {
        }
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc) {
        return createTestWindowToken(type, dc, false /* persistOnEmpty */);
    }

    static TestWindowToken createTestWindowToken(int type, DisplayContent dc,
            boolean persistOnEmpty) {
        SystemServicesTestRule.checkHoldsLock(dc.mWmService.mGlobalLock);

        return new TestWindowToken(type, dc, persistOnEmpty);
    }

    /** Used so we can gain access to some protected members of the {@link WindowToken} class */
    static class TestWindowToken extends WindowToken {

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

    /** Used to track resize reports. */
    static class TestWindowState extends WindowState {
        boolean mResizeReported;

        TestWindowState(WindowManagerService service, Session session, IWindow window,
                WindowManager.LayoutParams attrs, WindowToken token) {
            super(service, session, window, token, null, OP_NONE, attrs, 0, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
        }

        @Override
        void reportResized() {
            super.reportResized();
            mResizeReported = true;
        }

        @Override
        public boolean isGoneForLayout() {
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

    class TestTransitionPlayer extends ITransitionPlayer.Stub {
        final TransitionController mController;
        final WindowOrganizerController mOrganizer;
        Transition mLastTransit = null;
        TransitionRequestInfo mLastRequest = null;
        TransitionInfo mLastReady = null;

        TestTransitionPlayer(@NonNull TransitionController controller,
                @NonNull WindowOrganizerController organizer) {
            mController = controller;
            mOrganizer = organizer;
        }

        void clear() {
            mLastTransit = null;
            mLastReady = null;
            mLastRequest = null;
        }

        @Override
        public void onTransitionReady(IBinder transitToken, TransitionInfo transitionInfo,
                SurfaceControl.Transaction transaction) throws RemoteException {
            mLastTransit = Transition.fromBinder(transitToken);
            mLastReady = transitionInfo;
        }

        @Override
        public void requestStartTransition(IBinder transitToken,
                TransitionRequestInfo request) throws RemoteException {
            mLastTransit = Transition.fromBinder(transitToken);
            mLastRequest = request;
        }

        public void start() {
            mOrganizer.startTransition(mLastRequest.getType(), mLastTransit, null);
            mLastTransit.onTransactionReady(mLastTransit.getSyncId(),
                    mock(SurfaceControl.Transaction.class));
        }

        public void finish() {
            mController.finishTransition(mLastTransit);
        }
    }
}
