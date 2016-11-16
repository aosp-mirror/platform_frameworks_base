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
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IWindow;
import android.view.WindowManager;

import static android.app.AppOpsManager.OP_NONE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for the {@link WindowToken} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.WindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowTokenTests extends WindowTestsBase {

    @Test
    public void testAddWindow() throws Exception {
        final TestWindowToken token = new TestWindowToken();

        assertEquals(0, token.getWindowsCount());

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token, "window12");
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token, "window2");
        final WindowState window3 = createWindow(null, TYPE_APPLICATION, token, "window3");

        token.addWindow(window1);
        // NOTE: Child windows will not be added to the token as window containers can only
        // contain/reference their direct children.
        token.addWindow(window11);
        token.addWindow(window12);
        token.addWindow(window2);
        token.addWindow(window3);

        // Should not contain the child windows that were added above.
        assertEquals(3, token.getWindowsCount());
        assertTrue(token.hasWindow(window1));
        assertFalse(token.hasWindow(window11));
        assertFalse(token.hasWindow(window12));
        assertTrue(token.hasWindow(window2));
        assertTrue(token.hasWindow(window3));
    }

    @Test
    public void testChildRemoval() throws Exception {
        final TestWindowToken token = new TestWindowToken();
        final DisplayContent dc = sWm.getDefaultDisplayContentLocked();

        assertEquals(token, dc.getWindowToken(token.token));

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token, "window1");
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token, "window2");
        token.addWindow(window1);
        token.addWindow(window2);

        window2.removeImmediately();
        // The token should still be mapped in the display content since it still has a child.
        assertEquals(token, dc.getWindowToken(token.token));

        window1.removeImmediately();
        // The token should have been removed from the display content since it no longer has a
        // child.
        assertEquals(null, dc.getWindowToken(token.token));
    }

    @Test
    public void testAdjustAnimLayer() throws Exception {
        final TestWindowToken token = new TestWindowToken();
        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token, "window12");
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token, "window2");
        final WindowState window3 = createWindow(null, TYPE_APPLICATION, token, "window3");

        token.addWindow(window1);
        token.addWindow(window2);
        token.addWindow(window3);

        final int adj = 50;
        final int window2StartLayer = window2.mLayer = 100;
        final int window3StartLayer = window3.mLayer = 200;
        final int highestLayer = token.adjustAnimLayer(adj);

        assertEquals(adj, window1.mWinAnimator.mAnimLayer);
        assertEquals(adj, window11.mWinAnimator.mAnimLayer);
        assertEquals(adj, window12.mWinAnimator.mAnimLayer);
        assertEquals(window2StartLayer + adj, window2.mWinAnimator.mAnimLayer);
        assertEquals(window3StartLayer + adj, window3.mWinAnimator.mAnimLayer);
        assertEquals(window3StartLayer + adj, highestLayer);
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    private class TestWindowToken extends WindowToken {

        TestWindowToken() {
            super(sWm, mock(IBinder.class), 0, false, sWm.getDefaultDisplayContentLocked());
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }
}
