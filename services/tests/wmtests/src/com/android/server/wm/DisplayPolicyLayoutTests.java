/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Surface.ROTATION_0;
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
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Before;
import org.junit.Test;

@SmallTest
@Presubmit
public class DisplayPolicyLayoutTests extends DisplayPolicyTestsBase {

    private DisplayFrames mFrames;
    private WindowState mWindow;
    private int mRotation = ROTATION_0;
    private boolean mHasDisplayCutout;

    @Before
    public void setUp() throws Exception {
        updateDisplayFrames();

        mWindow = spy(createWindow(null, TYPE_APPLICATION, "window"));
        // We only test window frames set by DisplayPolicy, so here prevents computeFrameLw from
        // changing those frames.
        doNothing().when(mWindow).computeFrameLw();

        final WindowManager.LayoutParams attrs = mWindow.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        attrs.format = PixelFormat.TRANSLUCENT;
    }

    public void setRotation(int rotation) {
        mRotation = rotation;
        updateDisplayFrames();
    }

    public void addDisplayCutout() {
        mHasDisplayCutout = true;
        updateDisplayFrames();
    }

    private void updateDisplayFrames() {
        final Pair<DisplayInfo, WmDisplayCutout> info = displayInfoAndCutoutForRotation(mRotation,
                mHasDisplayCutout);
        mFrames = new DisplayFrames(mDisplayContent.getDisplayId(), info.first, info.second);
    }

    @Test
    public void addingWindow_doesNotTamperWithSysuiFlags() {
        mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        addWindow(mWindow);

        assertEquals(0, mWindow.mAttrs.systemUiVisibility);
        assertEquals(0, mWindow.mAttrs.subtreeSystemUiVisibility);
    }

    @Test
    public void layoutWindowLw_appDrawsBars() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.flags |= FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_appWontDrawBars() {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.flags &= ~FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, NAV_BAR_HEIGHT);
        }
    }

    @Test
    public void layoutWindowLw_appWontDrawBars_forceStatus() throws Exception {
        synchronized (mWm.mGlobalLock) {
            mWindow.mAttrs.flags &= ~FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            mWindow.mAttrs.privateFlags |= PRIVATE_FLAG_FORCE_DRAW_STATUS_BAR_BACKGROUND;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, NAV_BAR_HEIGHT);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_never() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_layoutFullscreen() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), STATUS_BAR_HEIGHT, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_FULLSCREEN;
            mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getStableFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getContentFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDecorFrame(), 0, 0);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), 0, 0);
        }
    }


    @Test
    public void layoutWindowLw_withDisplayCutout_landscape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_90);
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
            assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_seascape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_270);
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
            assertInsetBy(mWindow.getStableFrameLw(), NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, 0, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    NAV_BAR_HEIGHT, STATUS_BAR_HEIGHT, DISPLAY_CUTOUT_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getDisplayFrameLw(), 0, 0, DISPLAY_CUTOUT_HEIGHT, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreen_landscape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_90);

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), DISPLAY_CUTOUT_HEIGHT, 0, 0, 0);
            assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_floatingInScreen() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();

            mWindow.mAttrs.flags = FLAG_LAYOUT_IN_SCREEN;
            mWindow.mAttrs.type = TYPE_APPLICATION_OVERLAY;
            mWindow.mAttrs.width = DISPLAY_WIDTH;
            mWindow.mAttrs.height = DISPLAY_HEIGHT;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetByTopBottom(mWindow.getParentFrame(), 0, NAV_BAR_HEIGHT);
            assertInsetByTopBottom(mWindow.getDisplayFrameLw(), STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT);
        }
    }

    @Test
    public void layoutWindowLw_withDisplayCutout_fullscreenInCutout_landscape() {
        synchronized (mWm.mGlobalLock) {
            addDisplayCutout();
            setRotation(ROTATION_90);

            mWindow.mAttrs.subtreeSystemUiVisibility |= SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            mWindow.mAttrs.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            addWindow(mWindow);

            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);
            mDisplayPolicy.layoutWindowLw(mWindow, null, mFrames);

            assertInsetBy(mWindow.getParentFrame(), 0, 0, 0, 0);
            assertInsetBy(mWindow.getStableFrameLw(), 0, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getContentFrameLw(),
                    DISPLAY_CUTOUT_HEIGHT, STATUS_BAR_HEIGHT, NAV_BAR_HEIGHT, 0);
            assertInsetBy(mWindow.getDecorFrame(), 0, 0, 0, 0);
        }
    }

    @Test
    public void layoutHint_appWindow() {
        synchronized (mWm.mGlobalLock) {
            // Initialize DisplayFrames
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

            final Rect outFrame = new Rect();
            final Rect outContentInsets = new Rect();
            final Rect outStableInsets = new Rect();
            final Rect outOutsets = new Rect();
            final DisplayCutout.ParcelableWrapper outDisplayCutout =
                    new DisplayCutout.ParcelableWrapper();

            mDisplayPolicy.getLayoutHintLw(mWindow.mAttrs, null, mFrames,
                    false /* floatingStack */, outFrame, outContentInsets, outStableInsets,
                    outOutsets, outDisplayCutout);

            assertThat(outFrame, is(mFrames.mUnrestricted));
            assertThat(outContentInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
            assertThat(outStableInsets, is(new Rect(0, STATUS_BAR_HEIGHT, 0, NAV_BAR_HEIGHT)));
            assertThat(outOutsets, is(new Rect()));
            assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        }
    }

    @Test
    public void layoutHint_appWindowInTask() {
        synchronized (mWm.mGlobalLock) {
            // Initialize DisplayFrames
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

            final Rect taskBounds = new Rect(100, 100, 200, 200);

            final Rect outFrame = new Rect();
            final Rect outContentInsets = new Rect();
            final Rect outStableInsets = new Rect();
            final Rect outOutsets = new Rect();
            final DisplayCutout.ParcelableWrapper outDisplayCutout =
                    new DisplayCutout.ParcelableWrapper();

            mDisplayPolicy.getLayoutHintLw(mWindow.mAttrs, taskBounds, mFrames,
                    false /* floatingStack */, outFrame, outContentInsets, outStableInsets,
                    outOutsets, outDisplayCutout);

            assertThat(outFrame, is(taskBounds));
            assertThat(outContentInsets, is(new Rect()));
            assertThat(outStableInsets, is(new Rect()));
            assertThat(outOutsets, is(new Rect()));
            assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        }
    }

    @Test
    public void layoutHint_appWindowInTask_outsideContentFrame() {
        synchronized (mWm.mGlobalLock) {
            // Initialize DisplayFrames
            mDisplayPolicy.beginLayoutLw(mFrames, 0 /* UI mode */);

            // Task is in the nav bar area (usually does not happen, but this is similar enough to
            // the possible overlap with the IME)
            final Rect taskBounds = new Rect(100, mFrames.mContent.bottom + 1,
                    200, mFrames.mContent.bottom + 10);

            final Rect outFrame = new Rect();
            final Rect outContentInsets = new Rect();
            final Rect outStableInsets = new Rect();
            final Rect outOutsets = new Rect();
            final DisplayCutout.ParcelableWrapper outDisplayCutout =
                    new DisplayCutout.ParcelableWrapper();

            mDisplayPolicy.getLayoutHintLw(mWindow.mAttrs, taskBounds, mFrames,
                    true /* floatingStack */, outFrame, outContentInsets, outStableInsets,
                    outOutsets, outDisplayCutout);

            assertThat(outFrame, is(taskBounds));
            assertThat(outContentInsets, is(new Rect()));
            assertThat(outStableInsets, is(new Rect()));
            assertThat(outOutsets, is(new Rect()));
            assertThat(outDisplayCutout, is(new DisplayCutout.ParcelableWrapper()));
        }
    }

    /**
     * Asserts that {@code actual} is inset by the given amounts from the full display rect.
     *
     * Convenience wrapper for when only the top and bottom inset are non-zero.
     */
    private void assertInsetByTopBottom(Rect actual, int expectedInsetTop,
            int expectedInsetBottom) {
        assertInsetBy(actual, 0, expectedInsetTop, 0, expectedInsetBottom);
    }

    /** Asserts that {@code actual} is inset by the given amounts from the full display rect. */
    private void assertInsetBy(Rect actual, int expectedInsetLeft, int expectedInsetTop,
            int expectedInsetRight, int expectedInsetBottom) {
        assertEquals(new Rect(expectedInsetLeft, expectedInsetTop,
                mFrames.mDisplayWidth - expectedInsetRight,
                mFrames.mDisplayHeight - expectedInsetBottom), actual);
    }
}
