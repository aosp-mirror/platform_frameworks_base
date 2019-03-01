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

import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_IS_SCREEN_DECOR;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayCutout;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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
        assertInsetBy(mAppWindow.displayFrame, 0, 0, 0, 0);
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
        assertInsetByTopBottom(mAppWindow.displayFrame, 0, NAV_BAR_HEIGHT);
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
        assertInsetByTopBottom(mAppWindow.displayFrame, 0, NAV_BAR_HEIGHT);
    }

    @Test
    public void addingWindow_doesNotTamperWithSysuiFlags() {
        mAppWindow.attrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        mPolicy.addWindow(mAppWindow);

        assertEquals(0, mAppWindow.attrs.systemUiVisibility);
        assertEquals(0, mAppWindow.attrs.subtreeSystemUiVisibility);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout() {
        addDisplayCutout();

        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.displayFrame, 0, 0);
    }

    @Test
    public void layoutWindowLw_withhDisplayCutout_never() {
        addDisplayCutout();

        mAppWindow.attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.displayFrame, STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_layoutFullscreen() {
        addDisplayCutout();

        mAppWindow.attrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, 0);
        assertInsetBy(mAppWindow.displayFrame, 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen() {
        addDisplayCutout();

        mAppWindow.attrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, STATUS_BAR_HEIGHT, 0);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.displayFrame, STATUS_BAR_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout() {
        addDisplayCutout();

        mAppWindow.attrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
        mAppWindow.attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.stableFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.contentFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.decorFrame, 0, 0);
        assertInsetByTopBottom(mAppWindow.displayFrame, 0, 0);
    }


    @Test
    public void layoutWindowLw_withDisplayCutout_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90);
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetBy(mAppWindow.parentFrame, DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mAppWindow.stableFrame, 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mAppWindow.contentFrame,
                DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mAppWindow.decorFrame, 0, 0, 0, 0);
        assertInsetBy(mAppWindow.displayFrame, DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_seascape() {
        addDisplayCutout();
        setRotation(ROTATION_270);
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetBy(mAppWindow.parentFrame, 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetBy(mAppWindow.stableFrame, NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, 0, 0);
        assertInsetBy(mAppWindow.contentFrame,
                NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, DISPLAY_CUTOUT_HEIGHT, 0);
        assertInsetBy(mAppWindow.decorFrame, 0, 0, 0, 0);
        assertInsetBy(mAppWindow.displayFrame, 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90);

        mAppWindow.attrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetBy(mAppWindow.parentFrame, DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        assertInsetBy(mAppWindow.stableFrame, 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mAppWindow.contentFrame,
                DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mAppWindow.decorFrame, 0, 0, 0, 0);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_floatingInScreen() {
        addDisplayCutout();

        mAppWindow.attrs.flags = FLAG_LAYOUT_IN_SCREEN;
        mAppWindow.attrs.type = TYPE_APPLICATION_OVERLAY;
        mAppWindow.attrs.width = DISPLAY_WIDTH;
        mAppWindow.attrs.height = DISPLAY_HEIGHT;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetByTopBottom(mAppWindow.parentFrame, 0, NAV_BAR_HEIGHT);
        assertInsetByTopBottom(mAppWindow.displayFrame, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout_landscape() {
        addDisplayCutout();
        setRotation(ROTATION_90);

        mAppWindow.attrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        mAppWindow.attrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mPolicy.addWindow(mAppWindow);

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
        mPolicy.layoutWindowLw(mAppWindow, null, mFrames);

        assertInsetBy(mAppWindow.parentFrame, 0, 0, 0, 0);
        assertInsetBy(mAppWindow.stableFrame, 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mAppWindow.contentFrame,
                DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
        assertInsetBy(mAppWindow.decorFrame, 0, 0, 0, 0);
    }

    @Test
    public void layoutHint_screenDecorWindow() {
        addDisplayCutout();
        mAppWindow.attrs.privateFlags |= PRIVATE_FLAG_IS_SCREEN_DECOR;

        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

        final Rect frame = new Rect();
        final Rect content = new Rect();
        final Rect stable = new Rect();
        final Rect outsets = new Rect();
        final DisplayCutout.ParcelableWrapper cutout = new DisplayCutout.ParcelableWrapper();
        mPolicy.getLayoutHintLw(mAppWindow.attrs, null /* taskBounds */, mFrames, frame, content,
                stable, outsets, cutout);

        assertThat(frame, equalTo(mFrames.mUnrestricted));
        assertThat(content, equalTo(new Rect()));
        assertThat(stable, equalTo(new Rect()));
        assertThat(outsets, equalTo(new Rect()));
        assertThat(cutout.get(), equalTo(DisplayCutout.NO_CUTOUT));
    }

    @Test
    public void layoutHint_appWindow() {
        // Initialize DisplayFrames
        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

        final Rect outFrame = new Rect();
        final Rect outContentInsets = new Rect();
        final Rect outStableInsets = new Rect();
        final Rect outOutsets = new Rect();
        final DisplayCutout.ParcelableWrapper outDisplayCutout =
                new DisplayCutout.ParcelableWrapper();

        mPolicy.getLayoutHintLw(mAppWindow.attrs, null, mFrames, outFrame, outContentInsets,
                outStableInsets, outOutsets, outDisplayCutout);

        assertThat(outFrame, is(mFrames.mUnrestricted));
        assertThat(outContentInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
        assertThat(outStableInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
        assertThat(outOutsets, is(new Rect()));
        assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
    }

    @Test
    public void layoutHint_appWindowInTask() {
        // Initialize DisplayFrames
        mPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

        final Rect taskBounds = new Rect(100, 100, 200, 200);

        final Rect outFrame = new Rect();
        final Rect outContentInsets = new Rect();
        final Rect outStableInsets = new Rect();
        final Rect outOutsets = new Rect();
        final DisplayCutout.ParcelableWrapper outDisplayCutout =
                new DisplayCutout.ParcelableWrapper();

        mPolicy.getLayoutHintLw(mAppWindow.attrs, taskBounds, mFrames, outFrame, outContentInsets,
                outStableInsets, outOutsets, outDisplayCutout);

        assertThat(outFrame, is(taskBounds));
        assertThat(outContentInsets, is(new Rect()));
        assertThat(outStableInsets, is(new Rect()));
        assertThat(outOutsets, is(new Rect()));
        assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
    }
}