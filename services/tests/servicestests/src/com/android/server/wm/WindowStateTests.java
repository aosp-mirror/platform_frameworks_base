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

import com.android.server.LocalServices;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.IWindow;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/angler/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.WindowStateTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class WindowStateTests extends AndroidTestCase {

    private static WindowManagerService sWm = null;
    private WindowToken mWindowToken;
    private final WindowManagerPolicy mPolicy = new TestWindowManagerPolicy();
    private final IWindow mIWindow = new TestIWindow();

    @Override
    public void setUp() throws Exception {
        final Context context = getContext();
//        final InputManagerService im = new InputManagerService(context);
        if (sWm == null) {
            // We only want to do this once for the test process as we don't want WM to try to
            // register a bunch of local services again.
            sWm = WindowManagerService.main(context, /*im*/ null, true, false, false, mPolicy);
        }
        mWindowToken = new WindowToken(sWm, null, 0, false);
    }

    public void testIsParentWindowHidden() throws Exception {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION);
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW);
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW);

        assertFalse(parentWindow.mHidden);
        assertFalse(parentWindow.isParentWindowHidden());
        assertFalse(child1.isParentWindowHidden());
        assertFalse(child2.isParentWindowHidden());

        parentWindow.mHidden = true;
        assertFalse(parentWindow.isParentWindowHidden());
        assertTrue(child1.isParentWindowHidden());
        assertTrue(child2.isParentWindowHidden());
    }

    public void testIsChildWindow() throws Exception {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION);
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW);
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW);
        final WindowState randomWindow = createWindow(null, TYPE_APPLICATION);

        assertFalse(parentWindow.isChildWindow());
        assertTrue(child1.isChildWindow());
        assertTrue(child2.isChildWindow());
        assertFalse(randomWindow.isChildWindow());
    }

    public void testHasChild() throws Exception {
        final WindowState win1 = createWindow(null, TYPE_APPLICATION);
        final WindowState win11 = createWindow(win1, FIRST_SUB_WINDOW);
        final WindowState win12 = createWindow(win1, FIRST_SUB_WINDOW);
        final WindowState win2 = createWindow(null, TYPE_APPLICATION);
        final WindowState win21 = createWindow(win2, FIRST_SUB_WINDOW);
        final WindowState randomWindow = createWindow(null, TYPE_APPLICATION);

        assertTrue(win1.hasChild(win11));
        assertTrue(win1.hasChild(win12));
        assertTrue(win2.hasChild(win21));

        assertFalse(win1.hasChild(win21));
        assertFalse(win1.hasChild(randomWindow));

        assertFalse(win2.hasChild(win11));
        assertFalse(win2.hasChild(win12));
        assertFalse(win2.hasChild(randomWindow));
    }

    public void testGetBottomChild() throws Exception {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION);
        assertNull(parentWindow.getBottomChild());

        final WindowState child1 = createWindow(parentWindow, TYPE_APPLICATION_PANEL);
        assertEquals(child1, parentWindow.getBottomChild());

        final WindowState child2 = createWindow(parentWindow, TYPE_APPLICATION_PANEL);
        // Since child1 and child2 are at the same layer, then child2 is expect to be added on top
        // on child1
        assertEquals(child1, parentWindow.getBottomChild());

        final WindowState child3 = createWindow(parentWindow, TYPE_APPLICATION_MEDIA_OVERLAY);
        // Since child3 is a negative layer, we would expect it to be added below current children
        // with positive layers.
        assertEquals(child3, parentWindow.getBottomChild());

        final WindowState child4 = createWindow(parentWindow, TYPE_APPLICATION_MEDIA_OVERLAY);
        // We would also expect additional negative layers to be added below existing negative
        // layers.
        assertEquals(child4, parentWindow.getBottomChild());
    }

    private WindowState createWindow(WindowState parent, int type) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);

        return new WindowState(sWm, null, mIWindow, mWindowToken, parent, 0, 0, attrs, 0,
                sWm.getDefaultDisplayContentLocked(), 0);
    }
}
