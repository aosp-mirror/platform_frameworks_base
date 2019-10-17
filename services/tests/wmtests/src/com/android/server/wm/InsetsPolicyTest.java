/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_KEYGUARD;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;
import android.util.IntArray;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.test.InsetsModeSession;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@FlakyTest(detail = "Promote to pre-submit once confirmed stable.")
@Presubmit
@RunWith(WindowTestRunner.class)
public class InsetsPolicyTest extends WindowTestsBase {
    private static InsetsModeSession sInsetsModeSession;

    @BeforeClass
    public static void setUpOnce() {
        // To let the insets provider control the insets visibility, the insets mode has to be
        // NEW_INSETS_MODE_FULL.
        sInsetsModeSession = new InsetsModeSession(NEW_INSETS_MODE_FULL);
    }

    @AfterClass
    public static void tearDownOnce() {
        sInsetsModeSession.close();
    }

    @Test
    public void testControlsForDispatch_regular() {
        addWindow(TYPE_STATUS_BAR, "topBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_dockedStackVisible() {
        addWindow(TYPE_STATUS_BAR, "topBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final WindowState win = createWindowOnStack(null, WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                ACTIVITY_TYPE_STANDARD, TYPE_APPLICATION, mDisplayContent, "app");
        final InsetsSourceControl[] controls = addWindowAndGetControlsForDispatch(win);

        // The app must not control any system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_freeformStackVisible() {
        addWindow(TYPE_STATUS_BAR, "topBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final WindowState win = createWindowOnStack(null, WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, TYPE_APPLICATION, mDisplayContent, "app");
        final InsetsSourceControl[] controls = addWindowAndGetControlsForDispatch(win);

        // The app must not control any bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_dockedDividerControllerResizing() {
        addWindow(TYPE_STATUS_BAR, "topBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");
        mDisplayContent.getDockedDividerController().setResizing(true);

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app must not control any system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_keyguard() {
        addWindow(TYPE_STATUS_BAR, "topBar").mAttrs.privateFlags |= PRIVATE_FLAG_KEYGUARD;
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app must not control the top bar.
        assertNotNull(controls);
        assertEquals(1, controls.length);
    }

    // TODO: adjust this test if we pretend to the app that it's still able to control it.
    @Test
    public void testControlsForDispatch_forceStatusBarVisibleTransparent() {
        addWindow(TYPE_STATUS_BAR, "topBar").mAttrs.privateFlags |=
                PRIVATE_FLAG_FORCE_STATUS_BAR_VISIBLE_TRANSPARENT;
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app must not control the top bar.
        assertNotNull(controls);
        assertEquals(1, controls.length);
    }

    @Test
    public void testControlsForDispatch_statusBarForceShowNavigation() {
        addWindow(TYPE_STATUS_BAR, "topBar").mAttrs.privateFlags |=
                PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app must not control the navigation bar.
        assertNotNull(controls);
        assertEquals(1, controls.length);
    }

    @Test
    public void testShowTransientBars_bothCanBeTransient_appGetsBothFakeControls() {
        addWindow(TYPE_STATUS_BAR, "topBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        addWindow(TYPE_NAVIGATION_BAR, "navBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        final WindowState app = addWindow(TYPE_APPLICATION, "app");

        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();
        policy.updateBarControlTarget(app);
        policy.showTransient(
                IntArray.wrap(new int[]{TYPE_TOP_BAR, InsetsState.TYPE_NAVIGATION_BAR}));
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(app);

        // The app must get both fake controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNull(controls[i].getLeash());
        }
    }

    @Test
    public void testShowTransientBars_topCanBeTransient_appGetsTopFakeControl() {
        addWindow(TYPE_STATUS_BAR, "topBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        addWindow(TYPE_NAVIGATION_BAR, "navBar")
                .getControllableInsetProvider().getSource().setVisible(true);
        final WindowState app = addWindow(TYPE_APPLICATION, "app");

        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();
        policy.updateBarControlTarget(app);
        policy.showTransient(
                IntArray.wrap(new int[]{TYPE_TOP_BAR, InsetsState.TYPE_NAVIGATION_BAR}));
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(app);

        // The app must get the fake control of the top bar, and must get the real control of the
        // navigation bar.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls[i];
            if (control.getType() == TYPE_TOP_BAR) {
                assertNull(controls[i].getLeash());
            } else {
                assertNotNull(controls[i].getLeash());
            }
        }
    }

    @Test
    public void testAbortTransientBars_bothCanBeAborted_appGetsBothRealControls() {
        addWindow(TYPE_STATUS_BAR, "topBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        addWindow(TYPE_NAVIGATION_BAR, "navBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        final WindowState app = addWindow(TYPE_APPLICATION, "app");

        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();
        policy.updateBarControlTarget(app);
        policy.showTransient(
                IntArray.wrap(new int[]{TYPE_TOP_BAR, InsetsState.TYPE_NAVIGATION_BAR}));
        InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(app);

        // The app must get both fake controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNull(controls[i].getLeash());
        }

        final InsetsState state = policy.getInsetsForDispatch(app);
        state.setSourceVisible(TYPE_TOP_BAR, true);
        state.setSourceVisible(InsetsState.TYPE_NAVIGATION_BAR, true);
        policy.onInsetsModified(app, state);

        controls = mDisplayContent.getInsetsStateController().getControlsForDispatch(app);

        // The app must get both real controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNotNull(controls[i].getLeash());
        }
    }

    private WindowState addWindow(int type, String name) {
        final WindowState win = createWindow(null, type, name);
        mDisplayContent.getDisplayPolicy().addWindowLw(win, win.mAttrs);
        return win;
    }

    private InsetsSourceControl[] addAppWindowAndGetControlsForDispatch() {
        return addWindowAndGetControlsForDispatch(addWindow(TYPE_APPLICATION, "app"));
    }

    private InsetsSourceControl[] addWindowAndGetControlsForDispatch(WindowState win) {
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(win);
        return mDisplayContent.getInsetsStateController().getControlsForDispatch(win);
    }
}
