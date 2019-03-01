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

import static android.app.AppOpsManager.OP_NONE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
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
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.Log;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

import com.android.server.AttributeCache;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;

import java.util.HashSet;
import java.util.LinkedList;

/**
 * Common base class for window manager unit test classes.
 *
 * Make sure any requests to WM hold the WM lock if needed b/73966377
 */
class WindowTestsBase {
    private static final String TAG = WindowTestsBase.class.getSimpleName();
    WindowManagerService sWm = null;  // TODO(roosa): rename to mWm in follow-up CL
    private final IWindow mIWindow = new TestIWindow();
    private Session mMockSession;
    // The default display is removed in {@link #setUp} and then we iterate over all displays to
    // make sure we don't collide with any existing display. If we run into no other display, the
    // added display should be treated as default. This cannot be the default display
    private static int sNextDisplayId = DEFAULT_DISPLAY + 1;
    static int sNextStackId = 1000;

    DisplayContent mDisplayContent;
    DisplayInfo mDisplayInfo = new DisplayInfo();
    WindowState mWallpaperWindow;
    WindowState mImeWindow;
    WindowState mImeDialogWindow;
    WindowState mStatusBarWindow;
    WindowState mDockedDividerWindow;
    WindowState mNavBarWindow;
    WindowState mAppWindow;
    WindowState mChildAppWindowAbove;
    WindowState mChildAppWindowBelow;
    HashSet<WindowState> mCommonWindows;
    WallpaperController mWallpaperController;

    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Rule
    public final WindowManagerServiceRule mWmRule = new WindowManagerServiceRule();

    static WindowState.PowerManagerWrapper mPowerManagerWrapper;  // TODO(roosa): make non-static.

    @Before
    public void setUp() throws Exception {
        // If @Before throws an exception, the error isn't logged. This will make sure any failures
        // in the set up are clear. This can be removed when b/37850063 is fixed.
        try {
            mMockSession = mock(Session.class);
            mPowerManagerWrapper = mock(WindowState.PowerManagerWrapper.class);

            final Context context = InstrumentationRegistry.getTargetContext();
            AttributeCache.init(context);

            sWm = mWmRule.getWindowManagerService();
            beforeCreateDisplay();

            mWallpaperController = new WallpaperController(sWm);

            context.getDisplay().getDisplayInfo(mDisplayInfo);
            mDisplayContent = createNewDisplay();
            sWm.mDisplayEnabled = true;
            sWm.mDisplayReady = true;

            // Set-up some common windows.
            mCommonWindows = new HashSet();
            synchronized (sWm.mWindowMap) {
                mWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
                mImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "mImeWindow");
                sWm.mInputMethodWindow = mImeWindow;
                mImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG,
                        "mImeDialogWindow");
                mStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "mStatusBarWindow");
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
            }
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

    @After
    public void tearDown() throws Exception {
        // If @After throws an exception, the error isn't logged. This will make sure any failures
        // in the tear down are clear. This can be removed when b/37850063 is fixed.
        try {
            final LinkedList<WindowState> nonCommonWindows = new LinkedList();

            synchronized (sWm.mWindowMap) {
                sWm.mRoot.forAllWindows(w -> {
                    if (!mCommonWindows.contains(w)) {
                        nonCommonWindows.addLast(w);
                    }
                }, true /* traverseTopToBottom */);

                while (!nonCommonWindows.isEmpty()) {
                    nonCommonWindows.pollLast().removeImmediately();
                }

                mDisplayContent.removeImmediately();
                sWm.mInputMethodTarget = null;
                sWm.mClosingApps.clear();
                sWm.mOpeningApps.clear();
            }

            // Wait until everything is really cleaned up.
            waitUntilHandlersIdle();
        } catch (Exception e) {
            Log.e(TAG, "Failed to tear down test", e);
            throw e;
        }
    }

    /**
     * @return A SurfaceBuilderFactory to inject in to the WindowManagerService during
     *         set-up (or null).
     */
    SurfaceBuilderFactory getSurfaceBuilderFactory() {
        return null;
    }

    private WindowState createCommonWindow(WindowState parent, int type, String name) {
        synchronized (sWm.mWindowMap) {
            final WindowState win = createWindow(parent, type, name);
            mCommonWindows.add(win);
            // Prevent common windows from been IMe targets
            win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
            return win;
        }
    }

    /** Asserts that the first entry is greater than the second entry. */
    void assertGreaterThan(int first, int second) throws Exception {
        Assert.assertTrue("Excepted " + first + " to be greater than " + second, first > second);
    }

    /** Asserts that the first entry is greater than the second entry. */
    void assertLessThan(int first, int second) throws Exception {
        Assert.assertTrue("Excepted " + first + " to be less than " + second, first < second);
    }

    /**
     * Waits until the main handler for WM has processed all messages.
     */
    void waitUntilHandlersIdle() {
        mWmRule.waitUntilWindowManagerHandlersIdle();
    }

    private WindowToken createWindowToken(
            DisplayContent dc, int windowingMode, int activityType, int type) {
        synchronized (sWm.mWindowMap) {
            if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
                return WindowTestUtils.createTestWindowToken(type, dc);
            }

            return createAppWindowToken(dc, windowingMode, activityType);
        }
    }

    AppWindowToken createAppWindowToken(DisplayContent dc, int windowingMode, int activityType) {
        final TaskStack stack = createStackControllerOnStackOnDisplay(windowingMode, activityType,
                dc).mContainer;
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken appWindowToken =
                WindowTestUtils.createTestAppWindowToken(dc);
        task.addChild(appWindowToken, 0);
        return appWindowToken;
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        synchronized (sWm.mWindowMap) {
            return (parent == null)
                    ? createWindow(parent, type, mDisplayContent, name)
                    : createWindow(parent, type, parent.mToken, name);
        }
    }

    WindowState createWindowOnStack(WindowState parent, int windowingMode, int activityType,
            int type, DisplayContent dc, String name) {
        synchronized (sWm.mWindowMap) {
            final WindowToken token = createWindowToken(dc, windowingMode, activityType, type);
            return createWindow(parent, type, token, name);
        }
    }

    WindowState createAppWindow(Task task, int type, String name) {
        synchronized (sWm.mWindowMap) {
            final AppWindowToken token = WindowTestUtils.createTestAppWindowToken(mDisplayContent);
            task.addChild(token, 0);
            return createWindow(null, type, token, name);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        synchronized (sWm.mWindowMap) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name);
        }
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        synchronized (sWm.mWindowMap) {
            final WindowToken token = createWindowToken(
                    dc, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, type);
            return createWindow(parent, type, token, name, 0 /* ownerId */,
                    ownerCanAddInternalSystemWindow);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        synchronized (sWm.mWindowMap) {
            return createWindow(parent, type, token, name, 0 /* ownerId */,
                    false /* ownerCanAddInternalSystemWindow */);
        }
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            int ownerId, boolean ownerCanAddInternalSystemWindow) {
        return createWindow(parent, type, token, name, ownerId, ownerCanAddInternalSystemWindow,
                sWm, mMockSession, mIWindow);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token,
            String name, int ownerId, boolean ownerCanAddInternalSystemWindow,
            WindowManagerService service, Session session, IWindow iWindow) {
        synchronized (service.mWindowMap) {
            final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
            attrs.setTitle(name);

            final WindowState w = new WindowState(service, session, iWindow, token, parent,
                    OP_NONE,
                    0, attrs, VISIBLE, ownerId, ownerCanAddInternalSystemWindow,
                    mPowerManagerWrapper);
            // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
            // adding it to the token...
            token.addWindow(w);
            return w;
        }
    }

    /** Creates a {@link TaskStack} and adds it to the specified {@link DisplayContent}. */
    TaskStack createTaskStackOnDisplay(DisplayContent dc) {
        synchronized (sWm.mWindowMap) {
            return createStackControllerOnDisplay(dc).mContainer;
        }
    }

    StackWindowController createStackControllerOnDisplay(DisplayContent dc) {
        synchronized (sWm.mWindowMap) {
            return createStackControllerOnStackOnDisplay(
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, dc);
        }
    }

    StackWindowController createStackControllerOnStackOnDisplay(
            int windowingMode, int activityType, DisplayContent dc) {
        synchronized (sWm.mWindowMap) {
            final Configuration overrideConfig = new Configuration();
            overrideConfig.windowConfiguration.setWindowingMode(windowingMode);
            overrideConfig.windowConfiguration.setActivityType(activityType);
            final int stackId = ++sNextStackId;
            final StackWindowController controller = new StackWindowController(stackId, null,
                    dc.getDisplayId(), true /* onTop */, new Rect(), sWm);
            controller.onOverrideConfigurationChanged(overrideConfig);
            return controller;
        }
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    Task createTaskInStack(TaskStack stack, int userId) {
        return WindowTestUtils.createTaskInStack(sWm, stack, userId);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay() {
        final int displayId = sNextDisplayId++;
        final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                mDisplayInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
        synchronized (sWm.mWindowMap) {
            return new DisplayContent(display, sWm, mWallpaperController,
                    mock(DisplayWindowController.class));
        }
    }

    /** Creates a {@link com.android.server.wm.WindowTestUtils.TestWindowState} */
    WindowTestUtils.TestWindowState createWindowState(WindowManager.LayoutParams attrs,
            WindowToken token) {
        synchronized (sWm.mWindowMap) {
            return new WindowTestUtils.TestWindowState(sWm, mMockSession, mIWindow, attrs, token);
        }
    }

}
