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

import static android.view.View.VISIBLE;

import android.app.ActivityManager.TaskDescription;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.view.Display;
import android.view.DisplayInfo;
import org.junit.Assert;
import org.junit.After;
import org.junit.Before;
import org.mockito.MockitoAnnotations;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.AppOpsManager.OP_NONE;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
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

import com.android.server.AttributeCache;

import java.util.HashSet;
import java.util.LinkedList;

/**
 * Common base class for window manager unit test classes.
 */
class WindowTestsBase {
    static WindowManagerService sWm = null;
    static TestWindowManagerPolicy sPolicy = null;
    private final static IWindow sIWindow = new TestIWindow();
    private final static Session sMockSession = mock(Session.class);
    // The default display is removed in {@link #setUp} and then we iterate over all displays to
    // make sure we don't collide with any existing display. If we run into no other display, the
    // added display should be treated as default. This cannot be the default display
    private static int sNextDisplayId = Display.DEFAULT_DISPLAY + 1;
    private static int sNextStackId = FIRST_DYNAMIC_STACK_ID;

    private static boolean sOneTimeSetupDone = false;
    static DisplayContent sDisplayContent;
    static DisplayInfo sDisplayInfo = new DisplayInfo();
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
    static HashSet<WindowState> sCommonWindows;

    @Before
    public void setUp() throws Exception {
        if (sOneTimeSetupDone) {
            return;
        }
        sOneTimeSetupDone = true;
        MockitoAnnotations.initMocks(this);
        final Context context = InstrumentationRegistry.getTargetContext();
        AttributeCache.init(context);
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
        sPolicy = (TestWindowManagerPolicy) sWm.mPolicy;
        sLayersController = new WindowLayersController(sWm);

        // Make sure that display ids don't overlap, so there won't be several displays with same
        // ids among RootWindowContainer children.
        for (DisplayContent dc : sWm.mRoot.mChildren) {
            if (dc.getDisplayId() >= sNextDisplayId) {
                sNextDisplayId = dc.getDisplayId() + 1;
            }

            // The default display must be preserved as some tests require it to function
            // (such as policy rotation).
            if (dc.getDisplayId() != Display.DEFAULT_DISPLAY) {
                // It is safe to remove these displays as new displays will always be created with
                // new ids.
                dc.removeImmediately();
            }
        }

        context.getDisplay().getDisplayInfo(sDisplayInfo);
        sDisplayContent = createNewDisplay();
        sWm.mDisplayEnabled = true;
        sWm.mDisplayReady = true;

        // Set-up some common windows.
        sCommonWindows = new HashSet();
        sWallpaperWindow = createCommonWindow(null, TYPE_WALLPAPER, "wallpaperWindow");
        sImeWindow = createCommonWindow(null, TYPE_INPUT_METHOD, "sImeWindow");
        sWm.mInputMethodWindow = sImeWindow;
        sImeDialogWindow = createCommonWindow(null, TYPE_INPUT_METHOD_DIALOG, "sImeDialogWindow");
        sStatusBarWindow = createCommonWindow(null, TYPE_STATUS_BAR, "sStatusBarWindow");
        sNavBarWindow = createCommonWindow(null, TYPE_NAVIGATION_BAR, "sNavBarWindow");
        sDockedDividerWindow = createCommonWindow(null, TYPE_DOCK_DIVIDER, "sDockedDividerWindow");
        sAppWindow = createCommonWindow(null, TYPE_BASE_APPLICATION, "sAppWindow");
        sChildAppWindowAbove = createCommonWindow(sAppWindow, TYPE_APPLICATION_ATTACHED_DIALOG,
                "sChildAppWindowAbove");
        sChildAppWindowBelow = createCommonWindow(sAppWindow, TYPE_APPLICATION_MEDIA_OVERLAY,
                "sChildAppWindowBelow");
    }

    @After
    public void tearDown() throws Exception {
        final LinkedList<WindowState> nonCommonWindows = new LinkedList();
        sWm.mRoot.forAllWindows(w -> {
            if (!sCommonWindows.contains(w)) {
                nonCommonWindows.addLast(w);
            }
        }, true /* traverseTopToBottom */);

        while (!nonCommonWindows.isEmpty()) {
            nonCommonWindows.pollLast().removeImmediately();
        }

        sWm.mInputMethodTarget = null;
    }

    private static WindowState createCommonWindow(WindowState parent, int type, String name) {
        final WindowState win = createWindow(parent, type, name);
        sCommonWindows.add(win);
        // Prevent common windows from been IMe targets
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        return win;
    }

    /** Asserts that the first entry is greater than the second entry. */
    void assertGreaterThan(int first, int second) throws Exception {
        Assert.assertTrue("Excepted " + first + " to be greater than " + second, first > second);
    }

    /**
     * Waits until the main handler for WM has processed all messages.
     */
    void waitUntilHandlersIdle() {
        sWm.mH.runWithScissors(() -> { }, 0);
        sWm.mAnimationHandler.runWithScissors(() -> { }, 0);
    }

    private static WindowToken createWindowToken(DisplayContent dc, int stackId, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return new WindowTestUtils.TestWindowToken(type, dc);
        }

        final TaskStack stack = stackId == INVALID_STACK_ID
                ? createTaskStackOnDisplay(dc)
                : createStackControllerOnStackOnDisplay(stackId, dc).mContainer;
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token = new WindowTestUtils.TestAppWindowToken(dc);
        task.addChild(token, 0);
        return token;
    }

    static WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, sDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    static WindowState createWindowOnStack(WindowState parent, int stackId, int type,
            DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, stackId, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createAppWindow(Task task, int type, String name) {
        final AppWindowToken token = new WindowTestUtils.TestAppWindowToken(sDisplayContent);
        task.addChild(token, 0);
        return createWindow(null, type, token, name);
    }

    static WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, INVALID_STACK_ID, type);
        return createWindow(parent, type, token, name);
    }

    static WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowToken token = createWindowToken(dc, INVALID_STACK_ID, type);
        return createWindow(parent, type, token, name, ownerCanAddInternalSystemWindow);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        return createWindow(parent, type, token, name, false /* ownerCanAddInternalSystemWindow */);
    }

    static WindowState createWindow(WindowState parent, int type, WindowToken token, String name,
            boolean ownerCanAddInternalSystemWindow) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(sWm, sMockSession, sIWindow, token, parent, OP_NONE,
                0, attrs, VISIBLE, 0, ownerCanAddInternalSystemWindow);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    /** Creates a {@link TaskStack} and adds it to the specified {@link DisplayContent}. */
    static TaskStack createTaskStackOnDisplay(DisplayContent dc) {
        return createStackControllerOnDisplay(dc).mContainer;
    }

    static StackWindowController createStackControllerOnDisplay(DisplayContent dc) {
        final int stackId = ++sNextStackId;
        return createStackControllerOnStackOnDisplay(stackId, dc);
    }

    static StackWindowController createStackControllerOnStackOnDisplay(int stackId,
            DisplayContent dc) {
        return new StackWindowController(stackId, null, dc.getDisplayId(),
                true /* onTop */, new Rect(), sWm);
    }

    /** Creates a {@link Task} and adds it to the specified {@link TaskStack}. */
    static Task createTaskInStack(TaskStack stack, int userId) {
        return WindowTestUtils.createTaskInStack(sWm, stack, userId);
    }

    /** Creates a {@link DisplayContent} and adds it to the system. */
    DisplayContent createNewDisplay() {
        final int displayId = sNextDisplayId++;
        final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                sDisplayInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
        return new DisplayContent(display, sWm, sLayersController, new WallpaperController(sWm));
    }

    /** Creates a {@link com.android.server.wm.WindowTestUtils.TestWindowState} */
    WindowTestUtils.TestWindowState createWindowState(WindowManager.LayoutParams attrs,
            WindowToken token) {
        return new WindowTestUtils.TestWindowState(sWm, sMockSession, sIWindow, attrs, token);
    }

}
