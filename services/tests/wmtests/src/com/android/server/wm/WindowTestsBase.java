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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.os.Process.SYSTEM_UID;
import static android.view.View.VISIBLE;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.wm.WindowStateAnimator.HAS_DRAWN;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindow;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowManager;

import com.android.server.AttributeCache;

import org.junit.Before;
import org.junit.BeforeClass;

/** Common base class for window manager unit test classes. */
class WindowTestsBase extends SystemServiceTestsBase {
    private static final String TAG = WindowTestsBase.class.getSimpleName();

    WindowManagerService mWm;
    private final IWindow mIWindow = new TestIWindow();
    private Session mMockSession;
    static int sNextStackId = 1000;

    /** Non-default display. */
    DisplayContent mDisplayContent;
    DisplayInfo mDisplayInfo = new DisplayInfo();
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
        mWm = mSystemServicesTestRule.getWindowManagerService();
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        mTransaction = mSystemServicesTestRule.mTransaction;
        mMockSession = mock(Session.class);
        final Context context = getInstrumentation().getTargetContext();
        // If @Before throws an exception, the error isn't logged. This will make sure any failures
        // in the set up are clear. This can be removed when b/37850063 is fixed.
        try {
            beforeCreateDisplay();

            context.getDisplay().getDisplayInfo(mDisplayInfo);
            mDisplayContent = createNewDisplay(true /* supportIme */);

            // Set-up some common windows.
            mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
            mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
            mDisplayContent.mInputMethodWindow = mImeWindow;
            mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                    "mImeDialogWindow");
            mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
            mNotificationShadeWindow = createCommonWindow(null, TYPE_NOTIFICATION_SHADE,
                    "mNotificationShadeWindow");
            mNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "mNavBarWindow");
            mDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER,
                    "mDockedDividerWindow");
            mAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "mAppWindow");
            mChildAppWindowAbove = createCommonWindow(mAppWindow,
                    TYPE_APPLICATION_ATTACHED_DIALOG,
                    "mChildAppWindowAbove");
            mChildAppWindowBelow = createCommonWindow(mAppWindow,
                    TYPE_APPLICATION_MEDIA_OVERLAY,
                    "mChildAppWindowBelow");
            mDisplayContent.getInsetsPolicy().setRemoteInsetsControllerControlsSystemBars(false);

            // Adding a display will cause freezing the display. Make sure to wait until it's
            // unfrozen to not run into race conditions with the tests.
            waitUntilHandlersIdle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up test", e);
            throw e;
        }
    }

    void beforeCreateDisplay() {
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
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return WindowTestUtils.createTestWindowToken(type, dc);
        }

        return createActivityRecord(dc, windowingMode, activityType);
    }

    ActivityRecord createActivityRecord(DisplayContent dc, int windowingMode, int activityType) {
        return createTestActivityRecord(dc, windowingMode, activityType);
    }

    ActivityRecord createTestActivityRecord(DisplayContent dc, int
            windowingMode, int activityType) {
        final ActivityStack stack = createTaskStackOnDisplay(windowingMode, activityType, dc);
        return WindowTestUtils.createTestActivityRecord(stack);
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

    WindowState createWindowOnStack(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final ActivityRecord activity =
                WindowTestUtils.createTestActivityRecord(task.getDisplayContent());
        task.addChild(activity, 0);
        return createWindow(null, type, activity, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(
                dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
        return createWindow(parent, type, token, name, 0 /* ownerId */);
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
        return createWindow(parent, type, token, name, ownerId, UserHandle.getUserId(ownerId),
                ownerCanAddInternalSystemWindow, mWm, mMockSession, mIWindow,
                mSystemServicesTestRule.getPowerManagerWrapper());
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, int userId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow,
            WindowState.PowerManagerWrapper powerManagerWrapper) {
        SystemServicesTestRule.checkHoldsLock(service.mGlobalLock);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(service, session, iWindow, token, parent,
                OP_NONE, 0, attrs, VISIBLE, ownerId, userId,
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
            win.showLw(false /* doAnimation */, false /* requestAnim */);
        }
    }

    static void makeWindowVisibleAndDrawn(WindowState... windows) {
        makeWindowVisible(windows);
        for (WindowState win : windows) {
            win.mWinAnimator.mDrawState = HAS_DRAWN;
        }
    }

    /** Creates a {@link ActivityStack} and adds it to the specified {@link DisplayContent}. */
    ActivityStack createTaskStackOnDisplay(DisplayContent dc) {
        return createTaskStackOnDisplay(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, dc);
    }

    ActivityStack createTaskStackOnDisplay(int windowingMode, int activityType, DisplayContent dc) {
        return new ActivityTestsBase.StackBuilder(dc.mWmService.mRoot)
                .setDisplay(dc)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setCreateActivity(false)
                .setIntent(new Intent())
                .build();
    }

    ActivityStack createTaskStackOnTaskDisplayArea(int windowingMode, int activityType,
            TaskDisplayArea tda) {
        return new ActivityTestsBase.StackBuilder(tda.mWmService.mRoot)
                .setTaskDisplayArea(tda)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setCreateActivity(false)
                .setIntent(new Intent())
                .build();
    }

    /** Creates a {@link Task} and adds it to the specified {@link ActivityStack}. */
    Task createTaskInStack(ActivityStack stack, int userId) {
        return WindowTestUtils.createTaskInStack(mWm, stack, userId);
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay() {
        return createNewDisplay(true /* supportIme */);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplay(boolean supportIme) {
        return createNewDisplay(mDisplayInfo, supportIme);
    }

    /** Creates a {@link DisplayContent} that supports IME and adds it to the system. */
    DisplayContent createNewDisplay(DisplayInfo info) {
        return createNewDisplay(info, true /* supportIme */);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    private DisplayContent createNewDisplay(DisplayInfo info, boolean supportIme) {
        final DisplayContent display =
                new TestDisplayContent.Builder(mWm.mAtmService, info).build();
        final DisplayContent dc = display.mDisplayContent;
        // this display can show IME.
        dc.mWmService.mDisplayWindowSettings.setShouldShowImeLocked(dc, supportIme);
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
        return createNewDisplay(displayInfo, true /* supportIme */);
    }

    /** Creates a {@link com.android.server.wm.WindowTestUtils.TestWindowState} */
    WindowTestUtils.TestWindowState createWindowState(WindowManager.LayoutParams attrs,
            WindowToken token) {
        SystemServicesTestRule.checkHoldsLock(mWm.mGlobalLock);

        return new WindowTestUtils.TestWindowState(mWm, mMockSession, mIWindow, attrs, token);
    }

    /** Creates a {@link DisplayContent} as parts of simulate display info for test. */
    DisplayContent createMockSimulatedDisplay() {
        DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.type = Display.TYPE_VIRTUAL;
        displayInfo.ownerUid = SYSTEM_UID;
        return createNewDisplay(displayInfo, false /* supportIme */);
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

    /** Sets the default minimum task size to 1 so that tests can use small task sizes */
    void removeGlobalMinSizeRestriction() {
        mWm.mAtmService.mRootWindowContainer.mDefaultMinSizeOfResizeableTaskDp = 1;
    }
}
