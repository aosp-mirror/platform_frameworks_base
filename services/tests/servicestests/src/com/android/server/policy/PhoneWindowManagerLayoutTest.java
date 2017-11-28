/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.WindowManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class PhoneWindowManagerLayoutTest extends PhoneWindowManagerTestBase {

    private FakeWindowState mAppWindow;

    @Before
    public void setUp() throws Exception {
        mAppWindow = new FakeWindowState();
        mAppWindow.attrs = new WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT,
                TYPE_APPLICATION,
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);

        addStatusBar();
        addNavigationBar();
    }

    @Test
    public void layoutWindowLw_appDrawsBars() throws Exception {
        mAppWindow.attrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, 0);
    }

    @Test
    public void layoutWindowLw_appWontDrawBars() throws Exception {
        mAppWindow.attrs.flags &= ~FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_appWontDrawBars_forceStatus() throws Exception {
        mAppWindow.attrs.flags &= ~FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mAppWindow.attrs.privateFlags |= PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, NAV_BAR_HEIGHT);
    }
}