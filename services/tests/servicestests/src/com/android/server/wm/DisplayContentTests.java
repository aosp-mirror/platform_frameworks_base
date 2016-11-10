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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
import android.content.res.Configuration;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;
import android.view.IWindow;
import android.view.WindowManager;

import java.util.ArrayList;

import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.res.Configuration.EMPTY;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Tests for the {@link DisplayContent} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.DisplayContentTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DisplayContentTests {

    private static WindowManagerService sWm = null;
    private final IWindow mIWindow = new TestIWindow();
    private final Session mMockSession = mock(Session.class);
    private Display mDisplay;
    private int mNextStackId = FIRST_DYNAMIC_STACK_ID;
    private int mNextTaskId = 0;

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
        mDisplay = context.getDisplay();
    }

    @Test
    public void testForAllWindows() throws Exception {
        final DisplayContent dc = new DisplayContent(mDisplay, sWm, null, null);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, dc, "wallpaper");
        final WindowState imeWindow = createWindow(null, TYPE_INPUT_METHOD, dc, "ime");
        final WindowState imeDialogWindow = createWindow(null, TYPE_INPUT_METHOD_DIALOG, dc,
                "ime dialog");
        final WindowState statusBarWindow = createWindow(null, TYPE_STATUS_BAR, dc, "status bar");
        final WindowState navBarWindow = createWindow(null, TYPE_NAVIGATION_BAR,
                statusBarWindow.mToken, "nav bar");
        final WindowState appWindow = createWindow(null, TYPE_BASE_APPLICATION, dc, "app");
        final WindowState negChildAppWindow = createWindow(appWindow, TYPE_APPLICATION_MEDIA,
                appWindow.mToken, "negative app child");
        final WindowState posChildAppWindow = createWindow(appWindow,
                TYPE_APPLICATION_ATTACHED_DIALOG, appWindow.mToken, "positive app child");
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION, dc,
                "exiting app");
        final AppWindowToken exitingAppToken = exitingAppWindow.mAppToken;
        exitingAppToken.mIsExiting = true;
        exitingAppToken.mTask.mStack.mExitingAppTokens.add(exitingAppToken);

        final ArrayList<WindowState> windows = new ArrayList();

        // Test forward traversal.
        dc.forAllWindows(windows::add, false /* traverseTopToBottom */);

        assertEquals(wallpaperWindow, windows.get(0));
        assertEquals(exitingAppWindow, windows.get(1));
        assertEquals(negChildAppWindow, windows.get(2));
        assertEquals(appWindow, windows.get(3));
        assertEquals(posChildAppWindow, windows.get(4));
        assertEquals(statusBarWindow, windows.get(5));
        assertEquals(navBarWindow, windows.get(6));
        assertEquals(imeWindow, windows.get(7));
        assertEquals(imeDialogWindow, windows.get(8));

        // Test backward traversal.
        windows.clear();
        dc.forAllWindows(windows::add, true /* traverseTopToBottom */);

        assertEquals(wallpaperWindow, windows.get(8));
        assertEquals(exitingAppWindow, windows.get(7));
        assertEquals(negChildAppWindow, windows.get(6));
        assertEquals(appWindow, windows.get(5));
        assertEquals(posChildAppWindow, windows.get(4));
        assertEquals(statusBarWindow, windows.get(3));
        assertEquals(navBarWindow, windows.get(2));
        assertEquals(imeWindow, windows.get(1));
        assertEquals(imeDialogWindow, windows.get(0));
    }

    private WindowToken createWindowToken(DisplayContent dc, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return new WindowToken(sWm, mock(IBinder.class), type, false, dc);
        }

        final int stackId = mNextStackId++;
        dc.addStackToDisplay(stackId, true);
        final TaskStack stack = sWm.mStackIdToStack.get(stackId);
        final Task task = new Task(mNextTaskId++, stack, 0, sWm, null, EMPTY, false);
        stack.addTask(task, true);
        final AppWindowToken token = new AppWindowToken(sWm, null, false, dc);
        task.addAppToken(0, token, 0, false);
        return token;
    }

    private WindowState createWindow(WindowState parent, int type, DisplayContent dc, String name) {
        final WindowToken token = createWindowToken(dc, type);
        return createWindow(parent, type, token, name);
    }

    private WindowState createWindow(WindowState parent, int type, WindowToken token, String name) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);
        attrs.setTitle(name);

        final WindowState w = new WindowState(sWm, mMockSession, mIWindow, token, parent, OP_NONE,
                0, attrs, 0, 0);
        // TODO: Probably better to make this call in the WindowState ctor to avoid errors with
        // adding it to the token...
        token.addWindow(w);
        return w;
    }
}
