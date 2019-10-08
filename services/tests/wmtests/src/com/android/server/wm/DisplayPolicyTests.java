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

import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_BOTTOM;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_RIGHT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
@Presubmit
public class DisplayPolicyTests extends WindowTestsBase {

    private WindowState createOpaqueFullscreen(boolean hasLightNavBar) {
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, "opaqueFullscreen");
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = MATCH_PARENT;
        attrs.height = MATCH_PARENT;
        attrs.flags =
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        attrs.format = PixelFormat.OPAQUE;
        attrs.systemUiVisibility = attrs.subtreeSystemUiVisibility = win.mSystemUiVisibility =
                hasLightNavBar ? SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
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
        attrs.systemUiVisibility = attrs.subtreeSystemUiVisibility = win.mSystemUiVisibility =
                hasLightNavBar ? SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
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
        final WindowState opaqueDarkNavBar = createOpaqueFullscreen(false);
        final WindowState opaqueLightNavBar = createOpaqueFullscreen(true);

        final WindowState dimming = createDimmingDialogWindow(false);

        final WindowState imeDrawDarkNavBar = createInputMethodWindow(true, true, false);
        final WindowState imeDrawLightNavBar = createInputMethodWindow(true, true, true);

        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                DisplayPolicy.updateLightNavigationBarLw(
                        SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, null, null,
                        null, null));

        // Opaque top fullscreen window overrides SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR flag.
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, opaqueDarkNavBar, null, opaqueDarkNavBar));
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueDarkNavBar, opaqueDarkNavBar, null,
                opaqueDarkNavBar));
        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                DisplayPolicy.updateLightNavigationBarLw(0, opaqueLightNavBar,
                        opaqueLightNavBar, null, opaqueLightNavBar));
        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                DisplayPolicy.updateLightNavigationBarLw(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                        opaqueLightNavBar, opaqueLightNavBar, null, opaqueLightNavBar));

        // Dimming window clears SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, dimming, null, dimming));
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                0, opaqueLightNavBar, dimming, null, dimming));
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueDarkNavBar, dimming, null, dimming));
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueLightNavBar, dimming, null, dimming));
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueLightNavBar, dimming, imeDrawLightNavBar,
                dimming));

        // IME window clears SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, null, null, imeDrawDarkNavBar,
                imeDrawDarkNavBar));

        // Even if the top fullscreen has SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, IME window wins.
        assertEquals(0, DisplayPolicy.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueLightNavBar, opaqueLightNavBar,
                imeDrawDarkNavBar, imeDrawDarkNavBar));

        // IME window should be able to use SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.
        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                DisplayPolicy.updateLightNavigationBarLw(0, opaqueDarkNavBar,
                        opaqueDarkNavBar, imeDrawLightNavBar, imeDrawLightNavBar));
    }

    @Test
    public void testShouldRotateSeamlessly() {
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        final WindowManager.LayoutParams attrs = mAppWindow.mAttrs;
        attrs.x = attrs.y = 0;
        attrs.height = attrs.width = WindowManager.LayoutParams.MATCH_PARENT;
        attrs.rotationAnimation = ROTATION_ANIMATION_SEAMLESS;
        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        doReturn(Surface.ROTATION_180).when(displayRotation).getUpsideDownRotation();

        synchronized (mWm.mGlobalLock) {
            policy.focusChangedLw(null /* lastFocus */, mAppWindow);
            policy.applyPostLayoutPolicyLw(
                    mAppWindow, attrs, null /* attached */, null /* imeTarget */);
            spyOn(policy);
            doReturn(true).when(policy).navigationBarCanMove();
            // The focused fullscreen opaque window without override bounds should be able to be
            // rotated seamlessly.
            assertTrue(policy.shouldRotateSeamlessly(
                    displayRotation, Surface.ROTATION_0, Surface.ROTATION_90));

            spyOn(mAppWindow.mAppToken);
            doReturn(false).when(mAppWindow.mAppToken).matchParentBounds();
            // No seamless rotation if the window may be positioned with offset after rotation.
            assertFalse(policy.shouldRotateSeamlessly(
                    displayRotation, Surface.ROTATION_0, Surface.ROTATION_90));
        }
    }

    @Test
    public void testShouldShowToastWhenScreenLocked() {
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        final WindowState activity = createApplicationWindow();
        final WindowState toast = createToastWindow();

        synchronized (mWm.mGlobalLock) {
            policy.adjustWindowParamsLw(
                    toast, toast.mAttrs, 0 /* callingPid */, 0 /* callingUid */);

            assertTrue(policy.canToastShowWhenLocked(0 /* callingUid */));
            assertNotEquals(0, toast.getAttrs().flags & FLAG_SHOW_WHEN_LOCKED);
        }
    }

    private WindowState createToastWindow() {
        final WindowState win = createWindow(null, TYPE_TOAST, "Toast");
        final WindowManager.LayoutParams attrs = win.mAttrs;
        attrs.width = WRAP_CONTENT;
        attrs.height = WRAP_CONTENT;
        attrs.flags = FLAG_KEEP_SCREEN_ON | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE;
        attrs.format = PixelFormat.TRANSLUCENT;
        return win;
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
}
