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
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IWindow;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/$TARGET_PRODUCT/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.WindowTokenTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowTokenTests {

    private WindowManagerService mWm = null;
    private final IWindow mIWindow = new TestIWindow();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        mWm = TestWindowManagerPolicy.getWindowManagerService(context);
    }

    @Test
    public void testAddWindow() throws Exception {
        final TestWindowToken token = new TestWindowToken();

        assertEquals(0, token.getWindowsCount());

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token);
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token);
        final WindowState window3 = createWindow(null, TYPE_APPLICATION, token);

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
    public void testAdjustAnimLayer() throws Exception {
        final TestWindowToken token = new TestWindowToken();
        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token);
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token);
        final WindowState window3 = createWindow(null, TYPE_APPLICATION, token);

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

    @Test
    public void testGetTopWindow() throws Exception {
        final TestWindowToken token = new TestWindowToken();

        assertNull(token.getTopWindow());

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token);
        token.addWindow(window1);
        assertEquals(window1, token.getTopWindow());
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token);
        assertEquals(window12, token.getTopWindow());

        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token);
        token.addWindow(window2);
        // Since new windows are added to the bottom of the token, we would still expect the
        // previous one to the top.
        assertEquals(window12, token.getTopWindow());
    }

    @Test
    public void testGetWindowIndex() throws Exception {
        final TestWindowToken token = new TestWindowToken();

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token);
        assertEquals(-1, token.getWindowIndex(window1));
        token.addWindow(window1);
        assertEquals(0, token.getWindowIndex(window1));
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token);
        // Child windows should report the same index as their parents.
        assertEquals(0, token.getWindowIndex(window11));
        assertEquals(0, token.getWindowIndex(window12));

        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token);
        assertEquals(-1, token.getWindowIndex(window2));
        token.addWindow(window2);
        // Since new windows are added to the bottom of the token, we would expect the added window
        // to be at index 0.
        assertEquals(0, token.getWindowIndex(window2));
        assertEquals(1, token.getWindowIndex(window1));
    }

    private WindowState createWindow(WindowState parent, int type, WindowToken token) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);

        return new WindowState(mWm, null, mIWindow, token, parent, 0, 0, attrs, 0,
                mWm.getDefaultDisplayContentLocked(), 0);
    }

    /* Used so we can gain access to some protected members of the {@link WindowToken} class */
    private class TestWindowToken extends WindowToken {

        TestWindowToken() {
            super(mWm, null, 0, false);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }
}
