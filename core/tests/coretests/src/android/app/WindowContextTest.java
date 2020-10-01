/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License.
 */

package android.app;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link WindowContext}
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:WindowContextTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@FlakyTest(bugId = 150812449, detail = "Remove after confirmed it's stable.")
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowContextTest {
    @Test
    public void testWindowContextRelease_doRemoveWindowToken() throws Throwable {
        final Context instContext = InstrumentationRegistry.getInstrumentation()
                .getTargetContext();
        final Display display = instContext.getSystemService(DisplayManager.class)
                .getDisplay(DEFAULT_DISPLAY);
        final Context context = instContext.createDisplayContext(display);
        final WindowContext windowContext = new WindowContext(context, TYPE_APPLICATION_OVERLAY,
                null /* options */);

        final IBinder token = windowContext.getActivityToken();

        final IWindowManager wms = WindowManagerGlobal.getWindowManagerService();
        assertTrue("Token must be registered to WMS", wms.isWindowToken(token));

        windowContext.release();

        assertFalse("Token must be unregistered to WMS", wms.isWindowToken(token));
    }
}
