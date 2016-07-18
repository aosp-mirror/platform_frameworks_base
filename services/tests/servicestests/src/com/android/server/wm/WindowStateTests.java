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

import android.content.Context;
import android.test.AndroidTestCase;
import android.view.IWindow;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/angler/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.WindowStateTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
public class WindowStateTests extends AndroidTestCase {

    private WindowManagerService mWm;
    private WindowToken mWindowToken;
    private final WindowManagerPolicy mPolicy = new TestWindowManagerPolicy();
    private final IWindow mIWindow = new TestIWindow();

    @Override
    public void setUp() throws Exception {
        final Context context = getContext();
//        final InputManagerService im = new InputManagerService(context);
        mWm = WindowManagerService.main(context, /*im*/ null, true, false, false, mPolicy);
        mWindowToken = new WindowToken(mWm, null, 0, false);
    }

    private WindowState createWindow(WindowState parent) {
        final int type = (parent == null) ? TYPE_APPLICATION : FIRST_SUB_WINDOW;
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(type);

        return new WindowState(mWm, null, mIWindow, mWindowToken, parent, 0, 0, attrs, 0,
                mWm.getDefaultDisplayContentLocked(), 0);
    }
}
