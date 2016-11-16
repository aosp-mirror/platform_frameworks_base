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
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IWindow;
import android.view.WindowManager;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link AppWindowToken} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.AppWindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppWindowTokenTests extends WindowTestsBase {

    @Test
    public void testAddWindow_Order() throws Exception {
        final TestAppWindowToken token = new TestAppWindowToken();

        assertEquals(0, token.getWindowsCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, token, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, token,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, token, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, token, "win4");

        token.addWindow(win1);
        token.addWindow(startingWin);
        token.addWindow(baseWin);
        token.addWindow(win4);

        // Should not contain the windows that were added above.
        assertEquals(4, token.getWindowsCount());
        assertTrue(token.hasWindow(win1));
        assertTrue(token.hasWindow(startingWin));
        assertTrue(token.hasWindow(baseWin));
        assertTrue(token.hasWindow(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, token.getLastChild());

        // The base application window should be below all other windows.
        assertEquals(baseWin, token.getFirstChild());
    }

    @Test
    public void testFindMainWindow() throws Exception {
        final TestAppWindowToken token = new TestAppWindowToken();

        assertNull(token.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, token, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token, "window12");
        token.addWindow(window1);
        assertEquals(window1, token.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, token.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, token, "window2");
        token.addWindow(window2);
        assertEquals(window2, token.findMainWindow());
    }

    /* Used so we can gain access to some protected members of the {@link AppWindowToken} class */
    private class TestAppWindowToken extends AppWindowToken {

        TestAppWindowToken() {
            super(sWm, null, false, sWm.getDefaultDisplayContentLocked());
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
