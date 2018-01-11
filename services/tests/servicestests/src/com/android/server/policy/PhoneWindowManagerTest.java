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

package com.android.server.policy;

import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_BOTTOM;
import static com.android.server.policy.WindowManagerPolicy.NAV_BAR_RIGHT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.PixelFormat;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.WindowManager;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class PhoneWindowManagerTest {

    private static FakeWindowState createOpaqueFullscreen(boolean hasLightNavBar) {
        final FakeWindowState state = new FakeWindowState();
        state.attrs = new WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT,
                TYPE_BASE_APPLICATION,
                FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR | FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.OPAQUE);
        state.attrs.subtreeSystemUiVisibility =
                hasLightNavBar ? SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
        return state;
    }

    private static FakeWindowState createDimmingDialogWindow(boolean canBeImTarget) {
        final FakeWindowState state = new FakeWindowState();
        state.attrs = new WindowManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT,
                TYPE_APPLICATION,
                FLAG_DIM_BEHIND  | (canBeImTarget ? 0 : FLAG_ALT_FOCUSABLE_IM),
                PixelFormat.TRANSLUCENT);
        state.isDimming = true;
        return state;
    }

    private static FakeWindowState createInputMethodWindow(boolean visible, boolean drawNavBar,
            boolean hasLightNavBar) {
        final FakeWindowState state = new FakeWindowState();
        state.attrs = new WindowManager.LayoutParams(MATCH_PARENT, MATCH_PARENT,
                TYPE_INPUT_METHOD,
                FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN
                        | (drawNavBar ? FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS : 0),
                PixelFormat.TRANSPARENT);
        state.attrs.subtreeSystemUiVisibility =
                hasLightNavBar ? SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
        state.visible = visible;
        state.policyVisible = visible;
        return state;
    }


    @Test
    public void testChooseNavigationColorWindowLw() throws Exception {
        final FakeWindowState opaque = createOpaqueFullscreen(false);

        final FakeWindowState dimmingImTarget = createDimmingDialogWindow(true);
        final FakeWindowState dimmingNonImTarget = createDimmingDialogWindow(false);

        final FakeWindowState visibleIme = createInputMethodWindow(true, true, false);
        final FakeWindowState invisibleIme = createInputMethodWindow(false, true, false);
        final FakeWindowState imeNonDrawNavBar = createInputMethodWindow(true, false, false);

        // If everything is null, return null
        assertNull(null, PhoneWindowManager.chooseNavigationColorWindowLw(
                null, null, null, NAV_BAR_BOTTOM));

        assertEquals(opaque, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, opaque, null, NAV_BAR_BOTTOM));
        assertEquals(dimmingImTarget, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, dimmingImTarget, null, NAV_BAR_BOTTOM));
        assertEquals(dimmingNonImTarget, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, dimmingNonImTarget, null, NAV_BAR_BOTTOM));

        assertEquals(visibleIme, PhoneWindowManager.chooseNavigationColorWindowLw(
                null, null, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(visibleIme, PhoneWindowManager.chooseNavigationColorWindowLw(
                null, dimmingImTarget, visibleIme, NAV_BAR_BOTTOM));
        // TODO(b/69002467): A dimming window that is shown above the IME window should win.
        assertEquals(visibleIme, PhoneWindowManager.chooseNavigationColorWindowLw(
                null, dimmingNonImTarget, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(visibleIme, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, opaque, visibleIme, NAV_BAR_BOTTOM));
        assertEquals(visibleIme, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, dimmingImTarget, visibleIme, NAV_BAR_BOTTOM));
        // TODO(b/69002467): A dimming window that is shown above the IME window should win.
        assertEquals(visibleIme, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, dimmingNonImTarget, visibleIme, NAV_BAR_BOTTOM));

        assertEquals(opaque, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, opaque, invisibleIme, NAV_BAR_BOTTOM));
        assertEquals(opaque, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, opaque, invisibleIme, NAV_BAR_BOTTOM));
        assertEquals(opaque, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, opaque, visibleIme, NAV_BAR_RIGHT));

        // Only IME windows that have FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS should be navigation color
        // window, but it's not yet implemented.
        // TODO(b/69002467): Support this.
        assertEquals(imeNonDrawNavBar, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, opaque, imeNonDrawNavBar, NAV_BAR_BOTTOM));
        // TODO(b/69002467): Support this.
        assertEquals(imeNonDrawNavBar, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, dimmingImTarget, imeNonDrawNavBar, NAV_BAR_BOTTOM));
        // TODO(b/69002467): Support this.
        assertEquals(imeNonDrawNavBar, PhoneWindowManager.chooseNavigationColorWindowLw(
                opaque, dimmingNonImTarget, imeNonDrawNavBar, NAV_BAR_BOTTOM));
    }

    @Test
    public void testUpdateLightNavigationBarLw() throws Exception {
        final FakeWindowState opaqueDarkNavBar = createOpaqueFullscreen(false);
        final FakeWindowState opaqueLightNavBar = createOpaqueFullscreen(true);

        final FakeWindowState dimming = createDimmingDialogWindow(false);

        final FakeWindowState imeDrawDarkNavBar = createInputMethodWindow(true,true, false);
        final FakeWindowState imeDrawLightNavBar = createInputMethodWindow(true,true, true);

        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                PhoneWindowManager.updateLightNavigationBarLw(
                        SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, null, null,
                        null, null));

        // Opaque top fullscreen window overrides SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR flag.
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, opaqueDarkNavBar, null, opaqueDarkNavBar));
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueDarkNavBar, opaqueDarkNavBar, null,
                opaqueDarkNavBar));
        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                PhoneWindowManager.updateLightNavigationBarLw(0, opaqueLightNavBar,
                        opaqueLightNavBar, null, opaqueLightNavBar));
        assertEquals(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                PhoneWindowManager.updateLightNavigationBarLw(SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR,
                        opaqueLightNavBar, opaqueLightNavBar, null, opaqueLightNavBar));

        // Dimming window clears SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, dimming, null, dimming));
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                0, opaqueLightNavBar, dimming, null, dimming));
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueDarkNavBar, dimming, null, dimming));
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueLightNavBar, dimming, null, dimming));
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueLightNavBar, dimming, imeDrawLightNavBar,
                dimming));

        // IME window clears SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, null, null, imeDrawDarkNavBar,
                imeDrawDarkNavBar));

        // Even if the top fullscreen has SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, IME window wins.
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, opaqueLightNavBar, opaqueLightNavBar,
                imeDrawDarkNavBar, imeDrawDarkNavBar));

        // Currently SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR in IME windows is ignored.
        // TODO(b/69002467): Support this.
        assertEquals(0, PhoneWindowManager.updateLightNavigationBarLw(
                0, opaqueDarkNavBar, opaqueDarkNavBar, imeDrawLightNavBar, imeDrawLightNavBar));
    }
}
