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
import android.view.Display;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.ActivityManager.StackId.FIRST_DYNAMIC_STACK_ID;
import static android.app.AppOpsManager.OP_NONE;
import static android.content.res.Configuration.EMPTY;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static org.mockito.Mockito.mock;

/**
 * Common base class for window manager unit test classes.
 */
public class WindowTestsBase {
    static WindowManagerService sWm = null;
    private final IWindow mIWindow = new TestIWindow();
    private final Session mMockSession = mock(Session.class);
    Display mDisplay;
    private static int sNextStackId = FIRST_DYNAMIC_STACK_ID;
    private static int sNextTaskId = 0;

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
        mDisplay = context.getDisplay();
    }

    /** Asserts that the first entry is greater than the second entry. */
    void assertGreaterThan(int first, int second) throws Exception {
        Assert.assertTrue("Excepted " + first + " to be greater than " + second, first > second);
    }

    WindowToken createWindowToken(DisplayContent dc, int type) {
        if (type < FIRST_APPLICATION_WINDOW || type > LAST_APPLICATION_WINDOW) {
            return new WindowToken(sWm, mock(IBinder.class), type, false, dc);
        }

        final int stackId = sNextStackId++;
        dc.addStackToDisplay(stackId, true);
        final TaskStack stack = sWm.mStackIdToStack.get(stackId);
        final Task task = new Task(sNextTaskId++, stack, 0, sWm, null, EMPTY, false);
        stack.addTask(task, true);
        final AppWindowToken token = new AppWindowToken(sWm, null, false, dc);
        task.addAppToken(0, token, 0, false);
        return token;
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
}
