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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.StatusBarManager;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsVisibilities;

import androidx.test.filters.SmallTest;

import com.android.server.statusbar.StatusBarManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class InsetsPolicyTest extends WindowTestsBase {

    @Before
    public void setup() {
        mWm.mAnimator.ready();
    }

    @Test
    public void testControlsForDispatch_regular() {
        addWindow(TYPE_STATUS_BAR, "statusBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_multiWindowTaskVisible() {
        addWindow(TYPE_STATUS_BAR, "statusBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final WindowState win = createWindow(null, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD, TYPE_APPLICATION, mDisplayContent, "app");
        final InsetsSourceControl[] controls = addWindowAndGetControlsForDispatch(win);

        // The app must not control any system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_freeformTaskVisible() {
        addWindow(TYPE_STATUS_BAR, "statusBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final WindowState win = createWindow(null, WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, TYPE_APPLICATION, mDisplayContent, "app");
        final InsetsSourceControl[] controls = addWindowAndGetControlsForDispatch(win);

        // The app must not control any system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_forceStatusBarVisible() {
        addWindow(TYPE_STATUS_BAR, "statusBar").mAttrs.privateFlags |=
                PRIVATE_FLAG_FORCE_SHOW_STATUS_BAR;
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The focused app window can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_statusBarForceShowNavigation() {
        addWindow(TYPE_NOTIFICATION_SHADE, "notificationShade").mAttrs.privateFlags |=
                PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        addWindow(TYPE_STATUS_BAR, "statusBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The focused app window can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_statusBarForceShowNavigation_butFocusedAnyways() {
        WindowState notifShade = addWindow(TYPE_NOTIFICATION_SHADE, "notificationShade");
        notifShade.mAttrs.privateFlags |= PRIVATE_FLAG_STATUS_FORCE_SHOW_NAVIGATION;
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        mDisplayContent.getInsetsPolicy().updateBarControlTarget(notifShade);
        InsetsSourceControl[] controls
                = mDisplayContent.getInsetsStateController().getControlsForDispatch(notifShade);

        // The app controls the navigation bar.
        assertNotNull(controls);
        assertEquals(1, controls.length);
    }

    @Test
    public void testControlsForDispatch_remoteInsetsControllerControlsBars_appHasNoControl() {
        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());
        mDisplayContent.getInsetsPolicy().setRemoteInsetsControllerControlsSystemBars(true);
        addWindow(TYPE_STATUS_BAR, "statusBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The focused app window cannot control system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_topAppHidesStatusBar() {
        addWindow(TYPE_STATUS_BAR, "statusBar");
        addWindow(TYPE_NAVIGATION_BAR, "navBar");

        // Add a fullscreen (MATCH_PARENT x MATCH_PARENT) app window which hides status bar.
        final WindowState fullscreenApp = addWindow(TYPE_APPLICATION, "fullscreenApp");
        final InsetsVisibilities requestedVisibilities = new InsetsVisibilities();
        requestedVisibilities.setVisibility(ITYPE_STATUS_BAR, false);
        fullscreenApp.setRequestedVisibilities(requestedVisibilities);

        // Add a non-fullscreen dialog window.
        final WindowState dialog = addWindow(TYPE_APPLICATION, "dialog");
        dialog.mAttrs.width = WRAP_CONTENT;
        dialog.mAttrs.height = WRAP_CONTENT;

        // Let fullscreenApp be mTopFullscreenOpaqueWindowState.
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        displayPolicy.beginPostLayoutPolicyLw();
        displayPolicy.applyPostLayoutPolicyLw(dialog, dialog.mAttrs, fullscreenApp, null);
        displayPolicy.applyPostLayoutPolicyLw(fullscreenApp, fullscreenApp.mAttrs, null, null);
        displayPolicy.finishPostLayoutPolicyLw();
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(dialog);

        assertEquals(fullscreenApp, displayPolicy.getTopFullscreenOpaqueWindow());

        // dialog is the focused window, but it can only control navigation bar.
        final InsetsSourceControl[] dialogControls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(dialog);
        assertNotNull(dialogControls);
        assertEquals(1, dialogControls.length);
        assertEquals(ITYPE_NAVIGATION_BAR, dialogControls[0].getType());

        // fullscreenApp is hiding status bar, and it can keep controlling status bar.
        final InsetsSourceControl[] fullscreenAppControls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(fullscreenApp);
        assertNotNull(fullscreenAppControls);
        assertEquals(1, fullscreenAppControls.length);
        assertEquals(ITYPE_STATUS_BAR, fullscreenAppControls[0].getType());

        // Assume mFocusedWindow is updated but mTopFullscreenOpaqueWindowState hasn't.
        final WindowState newFocusedFullscreenApp = addWindow(TYPE_APPLICATION, "newFullscreenApp");
        final InsetsVisibilities newRequestedVisibilities = new InsetsVisibilities();
        newRequestedVisibilities.setVisibility(ITYPE_STATUS_BAR, true);
        newFocusedFullscreenApp.setRequestedVisibilities(newRequestedVisibilities);
        // Make sure status bar is hidden by previous insets state.
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(fullscreenApp);

        final StatusBarManagerInternal sbmi =
                mDisplayContent.getDisplayPolicy().getStatusBarManagerInternal();
        clearInvocations(sbmi);
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(newFocusedFullscreenApp);
        // The status bar should be shown by newFocusedFullscreenApp even
        // mTopFullscreenOpaqueWindowState is still fullscreenApp.
        verify(sbmi).setWindowState(mDisplayContent.mDisplayId, StatusBarManager.WINDOW_STATUS_BAR,
                StatusBarManager.WINDOW_STATE_SHOWING);

        // Add a system window: panel.
        final WindowState panel = addWindow(TYPE_STATUS_BAR_SUB_PANEL, "panel");
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(panel);

        // panel is the focused window, but it can only control navigation bar.
        // Because fullscreenApp is hiding status bar.
        InsetsSourceControl[] panelControls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(panel);
        assertNotNull(panelControls);
        assertEquals(1, panelControls.length);
        assertEquals(ITYPE_NAVIGATION_BAR, panelControls[0].getType());

        // Add notificationShade and make it can receive keys.
        final WindowState shade = addWindow(TYPE_NOTIFICATION_SHADE, "notificationShade");
        shade.setHasSurface(true);
        assertTrue(shade.canReceiveKeys());
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(panel);

        // panel can control both system bars now.
        panelControls = mDisplayContent.getInsetsStateController().getControlsForDispatch(panel);
        assertNotNull(panelControls);
        assertEquals(2, panelControls.length);

        // Make notificationShade cannot receive keys.
        shade.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        assertFalse(shade.canReceiveKeys());
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(panel);

        // panel can only control navigation bar now.
        panelControls = mDisplayContent.getInsetsStateController().getControlsForDispatch(panel);
        assertNotNull(panelControls);
        assertEquals(1, panelControls.length);
        assertEquals(ITYPE_NAVIGATION_BAR, panelControls[0].getType());
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testShowTransientBars_bothCanBeTransient_appGetsBothFakeControls() {
        final WindowState statusBar = addNonFocusableWindow(TYPE_STATUS_BAR, "statusBar");
        statusBar.setHasSurface(true);
        statusBar.getControllableInsetProvider().setServerVisible(true);
        final WindowState navBar = addNonFocusableWindow(TYPE_NAVIGATION_BAR, "navBar");
        navBar.setHasSurface(true);
        navBar.getControllableInsetProvider().setServerVisible(true);
        final InsetsPolicy policy = spy(mDisplayContent.getInsetsPolicy());
        doNothing().when(policy).startAnimation(anyBoolean(), any());

        // Make both system bars invisible.
        final InsetsVisibilities requestedVisibilities = new InsetsVisibilities();
        requestedVisibilities.setVisibility(ITYPE_STATUS_BAR, false);
        requestedVisibilities.setVisibility(ITYPE_NAVIGATION_BAR, false);
        mAppWindow.setRequestedVisibilities(requestedVisibilities);
        policy.updateBarControlTarget(mAppWindow);
        waitUntilWindowAnimatorIdle();
        assertFalse(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .getSource(ITYPE_STATUS_BAR).isVisible());
        assertFalse(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .getSource(ITYPE_NAVIGATION_BAR).isVisible());

        policy.showTransient(new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR},
                true /* isGestureOnSystemBar */);
        waitUntilWindowAnimatorIdle();
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(mAppWindow);

        // The app must get both fake controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNull(controls[i].getLeash());
        }

        assertTrue(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .getSource(ITYPE_STATUS_BAR).isVisible());
        assertTrue(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .getSource(ITYPE_NAVIGATION_BAR).isVisible());
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testShowTransientBars_statusBarCanBeTransient_appGetsStatusBarFakeControl() {
        addNonFocusableWindow(TYPE_STATUS_BAR, "statusBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        addNonFocusableWindow(TYPE_NAVIGATION_BAR, "navBar")
                .getControllableInsetProvider().setServerVisible(true);

        final InsetsPolicy policy = spy(mDisplayContent.getInsetsPolicy());
        doNothing().when(policy).startAnimation(anyBoolean(), any());
        policy.updateBarControlTarget(mAppWindow);
        policy.showTransient(new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR},
                true /* isGestureOnSystemBar */);
        waitUntilWindowAnimatorIdle();
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(mAppWindow);

        // The app must get the fake control of the status bar, and must get the real control of the
        // navigation bar.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls[i];
            if (control.getType() == ITYPE_STATUS_BAR) {
                assertNull(controls[i].getLeash());
            } else {
                assertNotNull(controls[i].getLeash());
            }
        }
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testAbortTransientBars_bothCanBeAborted_appGetsBothRealControls() {
        final InsetsSource statusBarSource = addNonFocusableWindow(TYPE_STATUS_BAR, "statusBar")
                .getControllableInsetProvider().getSource();
        final InsetsSource navBarSource = addNonFocusableWindow(TYPE_NAVIGATION_BAR, "navBar")
                .getControllableInsetProvider().getSource();
        statusBarSource.setVisible(false);
        navBarSource.setVisible(false);
        mAppWindow.mAboveInsetsState.addSource(navBarSource);
        mAppWindow.mAboveInsetsState.addSource(statusBarSource);
        final InsetsPolicy policy = spy(mDisplayContent.getInsetsPolicy());
        doNothing().when(policy).startAnimation(anyBoolean(), any());
        policy.updateBarControlTarget(mAppWindow);
        policy.showTransient(new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR},
                true /* isGestureOnSystemBar */);
        waitUntilWindowAnimatorIdle();
        InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(mAppWindow);

        // The app must get both fake controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNull(controls[i].getLeash());
        }

        final InsetsState state = mAppWindow.getInsetsState();
        state.setSourceVisible(ITYPE_STATUS_BAR, true);
        state.setSourceVisible(ITYPE_NAVIGATION_BAR, true);

        final InsetsState clientState = mAppWindow.getInsetsState();
        // The transient bar states for client should be invisible.
        assertFalse(clientState.getSource(ITYPE_STATUS_BAR).isVisible());
        assertFalse(clientState.getSource(ITYPE_NAVIGATION_BAR).isVisible());
        // The original state shouldn't be modified.
        assertTrue(state.getSource(ITYPE_STATUS_BAR).isVisible());
        assertTrue(state.getSource(ITYPE_NAVIGATION_BAR).isVisible());

        final InsetsVisibilities requestedVisibilities = new InsetsVisibilities();
        requestedVisibilities.setVisibility(ITYPE_STATUS_BAR, true);
        requestedVisibilities.setVisibility(ITYPE_NAVIGATION_BAR, true);
        mAppWindow.setRequestedVisibilities(requestedVisibilities);
        policy.onInsetsModified(mAppWindow);
        waitUntilWindowAnimatorIdle();

        controls = mDisplayContent.getInsetsStateController().getControlsForDispatch(mAppWindow);

        // The app must get both real controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNotNull(controls[i].getLeash());
        }
    }

    @Test
    public void testShowTransientBars_abortsWhenControlTargetChanges() {
        addNonFocusableWindow(TYPE_STATUS_BAR, "statusBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        addNonFocusableWindow(TYPE_NAVIGATION_BAR, "navBar")
                .getControllableInsetProvider().getSource().setVisible(false);
        final WindowState app = addWindow(TYPE_APPLICATION, "app");
        final WindowState app2 = addWindow(TYPE_APPLICATION, "app");

        final InsetsPolicy policy = spy(mDisplayContent.getInsetsPolicy());
        doNothing().when(policy).startAnimation(anyBoolean(), any());
        policy.updateBarControlTarget(app);
        policy.showTransient(new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR},
                true /* isGestureOnSystemBar */);
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(app);
        policy.updateBarControlTarget(app2);
        assertFalse(policy.isTransient(ITYPE_STATUS_BAR));
        assertFalse(policy.isTransient(ITYPE_NAVIGATION_BAR));
    }

    private WindowState addNonFocusableWindow(int type, String name) {
        WindowState win = addWindow(type, name);
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        return win;
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
