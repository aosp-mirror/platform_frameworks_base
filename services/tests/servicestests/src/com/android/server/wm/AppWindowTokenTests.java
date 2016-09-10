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
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/$TARGET_PRODUCT/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.AppWindowTokenTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppWindowTokenTests {

    private static WindowManagerService sWm = null;
    private final IWindow mIWindow = new TestIWindow();

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        sWm = TestWindowManagerPolicy.getWindowManagerService(context);
    }

    @Test
    public void testFindMainWindow() throws Exception {
        final TestAppWindowToken token = new TestAppWindowToken();

        assertNull(token.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, token);
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token);
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token);
        token.addWindow(window1);
        assertEquals(window1, token.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, token.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, token);
        token.addWindow(window2);
        assertEquals(window2, token.findMainWindow());
    }

    private WindowState createWindow(WindowState parent, int type, WindowToken token) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);

        return new WindowState(sWm, null, mIWindow, token, parent, 0, 0, attrs, 0,
                sWm.getDefaultDisplayContentLocked(), 0);
    }

    /* Used so we can gain access to some protected members of the {@link AppWindowToken} class */
    private class TestAppWindowToken extends AppWindowToken {

        TestAppWindowToken() {
            super(sWm, null, false);
        }

        int getWindowsCount() {
            return mChildren.size();
        }

        boolean hasWindow(WindowState w) {
            return mChildren.contains(w);
        }
    }
}
