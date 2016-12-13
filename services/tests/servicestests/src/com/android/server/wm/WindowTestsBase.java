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

import org.junit.Assert;
import org.junit.Before;

import android.content.Context;
import android.os.IBinder;
import android.support.test.InstrumentationRegistry;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.AppOpsManager.OP_NONE;
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
import static org.mockito.Mockito.mock;

/**
 * Common base class for window manager unit test classes.
 */
public class WindowTestsBase {
    static WindowManagerService sWm = null;
    private final IWindow mIWindow = new TestIWindow();
    private final Session mMockSession = mock(Session.class);
    private static int sNextStackId = FIRST_DYNAMIC_STACK_ID;
    private static int sNextTaskId = 0;

    private static boolean sOneTimeSetupDone = false;
    protected static DisplayContent sDisplayContent;
    protected static WindowLayersController sLayersController;
    protected static WindowState sWallpaperWindow;
    protected static WindowState sImeWindow;
    protected static WindowState sImeDialogWindow;
    protected static WindowState sStatusBarWindow;
    protected static WindowState sDockedDividerWindow;
    protected static WindowState sNavBarWindow;
    protected static WindowState sAppWindow;
    protected static WindowState sChildAppWindowAbove;
    protected static WindowState sChildAppWindowBelow;

    @Before
    public void setUp() throws Exception {
        if (sOneTimeSetupDone) {
            return;
        }
        sOneTimeSetupDone = true;
        final Context context = InstrumentationRegistry.getTargetContext();
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
        sLayersController = new WindowLayersController(sWm);
        sDisplayContent = new DisplayContent(context.getDisplay(), sWm, sLayersController,
                new WallpaperController(sWm));

        // Set-up some common windows.
        sWallpaperWindow = createWindow(null, TYPE_WALLPAPER, sDisplayContent, "wallpaperWindow");
        sImeWindow = createWindow(null, TYPE_INPUT_METHOD, sDisplayContent, "sImeWindow");
        sImeDialogWindow =
                createWindow(null, TYPE_INPUT_METHOD_DIALOG, sDisplayContent, "sImeDialogWindow");
        sStatusBarWindow = createWindow(null, TYPE_STATUS_BAR, sDisplayContent, "sStatusBarWindow");
        final WindowToken statusBarToken = sStatusBarWindow.mToken;
        sNavBarWindow =
                createWindow(null, TYPE_NAVIGATION_BAR, statusBarToken, "sNavBarWindow");
        sDockedDividerWindow =
                createWindow(null, TYPE_DOCK_DIVIDER, statusBarToken, "sDockedDividerWindow");
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

    private WindowToken createWindowToken(DisplayContent dc, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return new TestWindowToken(type, dc);
        }

        final int stackId = sNextStackId++;
        dc.addStackToDisplay(stackId, true);
        final TaskStack stack = sWm.mStackIdToStack.get(stackId);
        final Task task = new Task(sNextTaskId++, stack, 0, sWm, null, EMPTY, false, 0, false);
        stack.addTask(task, true);
        final TestAppWindowToken token = new TestAppWindowToken(dc);
        task.addChild(token, 0);
        return token;
    }

    WindowState createWindow(WindowState parent, int type, String name) {
        return (parent == null)
                ? createWindow(parent, type, sDisplayContent, name)
                : createWindow(parent, type, parent.mToken, name);
    }

    WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, type);
        return createWindow(parent, type, token, name);
    }

    WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(sWm, mMockSession, mIWindow, token, parent, OP_NONE,
                0, attrs, 0, 0);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    class TestWindowToken extends WindowToken {

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

    /* Used so we can gain access to some protected members of the {@link AppWindowToken} class */
    class TestAppWindowToken extends AppWindowToken {

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
}
