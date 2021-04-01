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

import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.RoundedCorners.NO_ROUNDED_CORNERS;
import static android.view.Surface.ROTATION_0;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;

import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_BOTTOM;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_RIGHT;
import static com.android.server.wm.utils.WmDisplayCutout.NO_CUTOUT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowInsets.Side;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayPolicyTests extends WindowTestsBase {

    private WindowState createOpaqueFullscreen(boolean hasLightNavBar) {
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, "opaqueFullscreen");
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        attrs.format = PixelFormat.OPAQUE;
        attrs.insetsFlags.appearance = hasLightNavBar ? APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        return win;
    }

    private WindowState createDimmingDialogWindow(boolean canBeImTarget) {
        final WindowState win = spy(createWindow(null, TYPE_APPLICATION, "dimmingDialog"));
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = WRAP_CONTENT;
        attrs.height = WRAP_CONTENT;
        attrs.flags = FLAG_DIM_BEHIND | (canBeImTarget ? 0 : FLAG_ALT_FOCUSABLE_IM);
        attrs.format = PixelFormat.TRANSLUCENT;
        when(win.isDimming()).thenReturn(true);
        return win;
    }

    private WindowState createInputMethodWindow(boolean visible, boolean drawNavBar,
            boolean hasLightNavBar) {
        final WindowState win = createWindow(null, TYPE_INPUT_METHOD, "inputMethod");
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN
                | (drawNavBar ? FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS : 0);
        attrs.format = PixelFormat.TRANSPARENT;
        attrs.insetsFlags.appearance = hasLightNavBar ? APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
        win.mHasSurface = visible;
        return win;
    }

    @Test
    public void testChooseNavigationColorWindowLw() {
        final WindowState opaque = createOpaqueFullscreen(false);

        final WindowState dimmingImTarget = createDimmingDialogWindow(true);
        final WindowState dimmingNonImTarget = createDimmingDialogWindow(false);

        final WindowState visibleIme = createInputMethodWindow(true, true, false);
        final WindowState invisibleIme = createInputMethodWindow(false, true, false);
        final WindowState imeNonDrawNavBar = createInputMethodWindow(true, false, false);

        // If everything is null, return null
        assertNull(null, DisplayPolicy.chooseNavigationColorWindowLw(
                null, null, null, NAV_BAR_BOTTOM));

        assertEquals(opaque, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, opaque, null, NAV_BAR_BOTTOM));
        assertEquals(dimmingImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, dimmingImTarget, null, NAV_BAR_BOTTOM));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, dimmingNonImTarget, null, NAV_BAR_BOTTOM));

        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                null, null, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                null, dimmingImTarget, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                null, dimmingNonImTarget, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, opaque, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(visibleIme, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, dimmingImTarget, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, dimmingNonImTarget, visibleIme, NAV_BAR_BOTTOM));

        assertEquals(opaque, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, opaque, invisibleIme, NAV_BAR_BOTTOM));
        assertEquals(opaque, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, opaque, invisibleIme, NAV_BAR_BOTTOM));
        assertEquals(opaque, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, opaque, visibleIme, NAV_BAR_RIGHT));

        // Only IME windows that have FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS should be navigation color
        // window.
        assertEquals(opaque, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, opaque, imeNonDrawNavBar, NAV_BAR_BOTTOM));
        assertEquals(dimmingImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, dimmingImTarget, imeNonDrawNavBar, NAV_BAR_BOTTOM));
        assertEquals(dimmingNonImTarget, DisplayPolicy.chooseNavigationColorWindowLw(
                opaque, dimmingNonImTarget, imeNonDrawNavBar, NAV_BAR_BOTTOM));
    }

    @Test
    public void testUpdateLightNavigationBarLw() {
        DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final WindowState opaqueDarkNavBar = createOpaqueFullscreen(false);
        final WindowState opaqueLightNavBar = createOpaqueFullscreen(true);

        final WindowState dimming = createDimmingDialogWindow(false);

        final WindowState imeDrawDarkNavBar = createInputMethodWindow(true, true, false);
        final WindowState imeDrawLightNavBar = createInputMethodWindow(true, true, true);

        assertEquals(APPEARANCE_LIGHT_NAVIGATION_BARS,
                displayPolicy.updateLightNavigationBarLw(
                        APPEARANCE_LIGHT_NAVIGATION_BARS, null, null,
                        null, null));

        // Opaque top fullscreen window overrides APPEARANCE_LIGHT_NAVIGATION_BARS flag.
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, opaqueDarkNavBar, null, opaqueDarkNavBar));
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueDarkNavBar, opaqueDarkNavBar, null,
                opaqueDarkNavBar));
        assertEquals(APPEARANCE_LIGHT_NAVIGATION_BARS,
                displayPolicy.updateLightNavigationBarLw(0, opaqueLightNavBar,
                        opaqueLightNavBar, null, opaqueLightNavBar));
        assertEquals(APPEARANCE_LIGHT_NAVIGATION_BARS,
                displayPolicy.updateLightNavigationBarLw(APPEARANCE_LIGHT_NAVIGATION_BARS,
                        opaqueLightNavBar, opaqueLightNavBar, null, opaqueLightNavBar));

        // Dimming window clears APPEARANCE_LIGHT_NAVIGATION_BARS.
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, dimming, null, dimming));
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                0, opaqueLightNavBar, dimming, null, dimming));
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueDarkNavBar, dimming, null, dimming));
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueLightNavBar, dimming, null, dimming));
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueLightNavBar, dimming, imeDrawLightNavBar,
                dimming));

        // IME window clears APPEARANCE_LIGHT_NAVIGATION_BARS
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, null, null, imeDrawDarkNavBar,
                imeDrawDarkNavBar));

        // Even if the top fullscreen has APPEARANCE_LIGHT_NAVIGATION_BARS, IME window wins.
        assertEquals(0, displayPolicy.updateLightNavigationBarLw(
                APPEARANCE_LIGHT_NAVIGATION_BARS, opaqueLightNavBar, opaqueLightNavBar,
                imeDrawDarkNavBar, imeDrawDarkNavBar));

        // IME window should be able to use APPEARANCE_LIGHT_NAVIGATION_BARS.
        assertEquals(APPEARANCE_LIGHT_NAVIGATION_BARS,
                displayPolicy.updateLightNavigationBarLw(0, opaqueDarkNavBar,
                        opaqueDarkNavBar, imeDrawLightNavBar, imeDrawLightNavBar));
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testComputeTopFullscreenOpaqueWindow() {
        final WindowManager.LayoutParams attrs = mAppWindow.mAttrs;
        attrs.x = attrs.y = 0;
        attrs.height = attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        policy.applyPostLayoutPolicyLw(
                mAppWindow, attrs, null /* attached */, null /* imeTarget */);

        assertEquals(mAppWindow, policy.getTopFullscreenOpaqueWindow());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMainAppWindowDisallowFitSystemWindowTypes() {
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        final WindowState activity = createBaseApplicationWindow();
        activity.mAttrs.privateFlags |= PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;

        policy.adjustWindowParamsLw(activity, activity.mAttrs);
    }

    private WindowState createApplicationWindow() {
        final WindowState win = createWindow(null, TYPE_APPLICATION, "Application");
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags = FLAG_SHOW_WHEN_LOCKED | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        attrs.format = PixelFormat.OPAQUE;
        win.mHasSurface = true;
        return win;
    }

    private WindowState createBaseApplicationWindow() {
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, "Application");
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        attrs.format = PixelFormat.OPAQUE;
        win.mHasSurface = true;
        return win;
    }

    @Test
    public void testOverlappingWithNavBar() {
        final WindowState targetWin = createApplicationWindow();
        final WindowFrames winFrame = targetWin.getWindowFrames();
        winFrame.mFrame.set(new Rect(100, 100, 200, 200));

        final WindowState navigationBar = createNavigationBarWindow();

        navigationBar.getFrame().set(new Rect(100, 200, 200, 300));

        assertFalse("Freeform is overlapping with navigation bar",
                DisplayPolicy.isOverlappingWithNavBar(targetWin, navigationBar));

        winFrame.mFrame.set(new Rect(100, 101, 200, 201));
        assertTrue("Freeform should be overlapping with navigation bar (bottom)",
                DisplayPolicy.isOverlappingWithNavBar(targetWin, navigationBar));

        winFrame.mFrame.set(new Rect(99, 200, 199, 300));
        assertTrue("Freeform should be overlapping with navigation bar (right)",
                DisplayPolicy.isOverlappingWithNavBar(targetWin, navigationBar));

        winFrame.mFrame.set(new Rect(199, 200, 299, 300));
        assertTrue("Freeform should be overlapping with navigation bar (left)",
                DisplayPolicy.isOverlappingWithNavBar(targetWin, navigationBar));
    }

    private WindowState createNavigationBarWindow() {
        final WindowState win = createWindow(null, TYPE_NAVIGATION_BAR, "NavigationBar");
        win.mHasSurface = true;
        return win;
    }

    @UseTestDisplay(addWindows = { W_NAVIGATION_BAR, W_INPUT_METHOD })
    @Test
    public void testImeMinimalSourceFrame() {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = 1000;
        displayInfo.logicalHeight = 2000;
        displayInfo.rotation = ROTATION_0;

        displayPolicy.addWindowLw(mNavBarWindow, mNavBarWindow.mAttrs);
        mNavBarWindow.getControllableInsetProvider().setServerVisible(true);
        final InsetsState state = mDisplayContent.getInsetsStateController().getRawInsetsState();
        mImeWindow.mAboveInsetsState.set(state);
        mDisplayContent.mDisplayFrames = new DisplayFrames(mDisplayContent.getDisplayId(),
                state, displayInfo, NO_CUTOUT, NO_ROUNDED_CORNERS);

        mDisplayContent.setInputMethodWindowLocked(mImeWindow);
        mImeWindow.mAttrs.setFitInsetsSides(Side.all() & ~Side.BOTTOM);
        mImeWindow.mGivenContentInsets.set(0, displayInfo.logicalHeight, 0, 0);
        mImeWindow.getControllableInsetProvider().setServerVisible(true);

        displayPolicy.layoutWindowLw(mNavBarWindow, null, mDisplayContent.mDisplayFrames);
        displayPolicy.layoutWindowLw(mImeWindow, null, mDisplayContent.mDisplayFrames);

        final InsetsSource imeSource = state.peekSource(ITYPE_IME);
        final InsetsSource navBarSource = state.peekSource(ITYPE_NAVIGATION_BAR);

        assertNotNull(imeSource);
        assertNotNull(navBarSource);
        assertFalse(imeSource.getFrame().isEmpty());
        assertFalse(navBarSource.getFrame().isEmpty());
        assertTrue(imeSource.getFrame().contains(navBarSource.getFrame()));
    }
}
