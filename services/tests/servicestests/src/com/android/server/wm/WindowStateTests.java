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
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IWindow;
import android.view.WindowManager;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link WindowState} class.
 *
 * runtest frameworks-services -c com.android.server.wm.WindowStateTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowStateTests extends WindowTestsBase {

    private WindowToken mWindowToken;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mWindowToken = new WindowToken(sWm, new Binder(), 0, false,
                sWm.getDefaultDisplayContentLocked());
    }

    @Test
    public void testIsParentWindowHidden() throws Exception {
        final WindowState parentWindow =
                createWindow(null, TYPE_APPLICATION, mWindowToken, "parentWindow");
        final WindowState child1 =
                createWindow(parentWindow, FIRST_SUB_WINDOW, mWindowToken, "child1");
        final WindowState child2 =
                createWindow(parentWindow, FIRST_SUB_WINDOW, mWindowToken, "child2");

        assertFalse(parentWindow.mHidden);
        assertFalse(parentWindow.isParentWindowHidden());
        assertFalse(child1.isParentWindowHidden());
        assertFalse(child2.isParentWindowHidden());

        parentWindow.mHidden = true;
        assertFalse(parentWindow.isParentWindowHidden());
        assertTrue(child1.isParentWindowHidden());
        assertTrue(child2.isParentWindowHidden());
    }

    @Test
    public void testIsChildWindow() throws Exception {
        final WindowState parentWindow =
                createWindow(null, TYPE_APPLICATION, mWindowToken, "parentWindow");
        final WindowState child1 =
                createWindow(parentWindow, FIRST_SUB_WINDOW, mWindowToken, "child1");
        final WindowState child2 =
                createWindow(parentWindow, FIRST_SUB_WINDOW, mWindowToken, "child2");
        final WindowState randomWindow =
                createWindow(null, TYPE_APPLICATION, mWindowToken, "randomWindow");

        assertFalse(parentWindow.isChildWindow());
        assertTrue(child1.isChildWindow());
        assertTrue(child2.isChildWindow());
        assertFalse(randomWindow.isChildWindow());
    }

    @Test
    public void testHasChild() throws Exception {
        final WindowState win1 = createWindow(null, TYPE_APPLICATION, mWindowToken, "win1");
        final WindowState win11 = createWindow(win1, FIRST_SUB_WINDOW, mWindowToken, "win11");
        final WindowState win12 = createWindow(win1, FIRST_SUB_WINDOW, mWindowToken, "win12");
        final WindowState win2 = createWindow(null, TYPE_APPLICATION, mWindowToken, "win2");
        final WindowState win21 = createWindow(win2, FIRST_SUB_WINDOW, mWindowToken, "win21");
        final WindowState randomWindow =
                createWindow(null, TYPE_APPLICATION, mWindowToken, "randomWindow");

        assertTrue(win1.hasChild(win11));
        assertTrue(win1.hasChild(win12));
        assertTrue(win2.hasChild(win21));

        assertFalse(win1.hasChild(win21));
        assertFalse(win1.hasChild(randomWindow));

        assertFalse(win2.hasChild(win11));
        assertFalse(win2.hasChild(win12));
        assertFalse(win2.hasChild(randomWindow));
    }

    @Test
    public void testGetParentWindow() throws Exception {
        final WindowState parentWindow =
                createWindow(null, TYPE_APPLICATION, mWindowToken, "parentWindow");
        final WindowState child1 =
                createWindow(parentWindow, FIRST_SUB_WINDOW, mWindowToken, "child1");
        final WindowState child2 =
                createWindow(parentWindow, FIRST_SUB_WINDOW, mWindowToken, "child2");

        assertNull(parentWindow.getParentWindow());
        assertEquals(parentWindow, child1.getParentWindow());
        assertEquals(parentWindow, child2.getParentWindow());
    }

    @Test
    public void testGetTopParentWindow() throws Exception {
        final WindowState root = createWindow(null, TYPE_APPLICATION, mWindowToken, "root");
        final WindowState child1 = createWindow(root, FIRST_SUB_WINDOW, mWindowToken, "child1");
        final WindowState child2 = createWindow(child1, FIRST_SUB_WINDOW, mWindowToken, "child2");

        assertEquals(root, root.getTopParentWindow());
        assertEquals(root, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
        assertEquals(root, child2.getTopParentWindow());
    }

    @Test
    public void testIsOnScreen_hiddenByPolicy() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, mWindowToken, "window");
        window.setHasSurface(true);
        assertTrue(window.isOnScreen());
        window.hideLw(false /* doAnimation */);
        assertFalse(window.isOnScreen());
    }
}
