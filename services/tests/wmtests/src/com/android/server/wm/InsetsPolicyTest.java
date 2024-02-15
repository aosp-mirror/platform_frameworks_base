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
import static android.view.InsetsSource.ID_IME;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_CONSUME_IME_INSETS;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import android.app.StatusBarManager;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.WindowInsets;

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
        addStatusBar();
        addNavigationBar();

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The app can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_adjacentTasksVisible() {
        addStatusBar();
        addNavigationBar();

        final Task task1 = createTask(mDisplayContent);
        final Task task2 = createTask(mDisplayContent);
        task1.setAdjacentTaskFragment(task2);
        final WindowState win = createAppWindow(task1, WINDOWING_MODE_MULTI_WINDOW, "app");
        final InsetsSourceControl[] controls = addWindowAndGetControlsForDispatch(win);

        // The app must not control any system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_freeformTaskVisible() {
        addStatusBar();
        addNavigationBar();

        final WindowState win = createWindow(null, WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, TYPE_APPLICATION, mDisplayContent, "app");
        final InsetsSourceControl[] controls = addWindowAndGetControlsForDispatch(win);

        // The app must not control any system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_forceStatusBarVisible() {
        addStatusBar().mAttrs.forciblyShownTypes |= statusBars();
        addNavigationBar();

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The focused app window can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_statusBarForceShowNavigation() {
        addWindow(TYPE_NOTIFICATION_SHADE, "notificationShade").mAttrs.forciblyShownTypes |=
                navigationBars();
        addStatusBar();
        addNavigationBar();

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The focused app window can control both system bars.
        assertNotNull(controls);
        assertEquals(2, controls.length);
    }

    @Test
    public void testControlsForDispatch_statusBarForceShowNavigation_butFocusedAnyways() {
        WindowState notifShade = addWindow(TYPE_NOTIFICATION_SHADE, "notificationShade");
        notifShade.mAttrs.forciblyShownTypes |= navigationBars();
        addNavigationBar();

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
        mDisplayContent.getDisplayPolicy().setRemoteInsetsControllerControlsSystemBars(true);
        addStatusBar();
        addNavigationBar();

        final InsetsSourceControl[] controls = addAppWindowAndGetControlsForDispatch();

        // The focused app window cannot control system bars.
        assertNull(controls);
    }

    @Test
    public void testControlsForDispatch_topAppHidesStatusBar() {
        addStatusBar();
        addNavigationBar();

        // Add a fullscreen (MATCH_PARENT x MATCH_PARENT) app window which hides status bar.
        final WindowState fullscreenApp = addWindow(TYPE_APPLICATION, "fullscreenApp");
        fullscreenApp.setRequestedVisibleTypes(0, WindowInsets.Type.statusBars());

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
        assertEquals(navigationBars(), dialogControls[0].getType());

        // fullscreenApp is hiding status bar, and it can keep controlling status bar.
        final InsetsSourceControl[] fullscreenAppControls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(fullscreenApp);
        assertNotNull(fullscreenAppControls);
        assertEquals(1, fullscreenAppControls.length);
        assertEquals(statusBars(), fullscreenAppControls[0].getType());

        // Assume mFocusedWindow is updated but mTopFullscreenOpaqueWindowState hasn't.
        final WindowState newFocusedFullscreenApp = addWindow(TYPE_APPLICATION, "newFullscreenApp");
        newFocusedFullscreenApp.setRequestedVisibleTypes(
                WindowInsets.Type.statusBars(), WindowInsets.Type.statusBars());
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
        assertEquals(navigationBars(), panelControls[0].getType());

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
        assertEquals(navigationBars(), panelControls[0].getType());
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testShowTransientBars_bothCanBeTransient_appGetsBothFakeControls() {
        final WindowState statusBar = addStatusBar();
        final InsetsSourceProvider statusBarProvider = statusBar.getControllableInsetProvider();
        final int statusBarId = statusBarProvider.getSource().getId();
        statusBar.setHasSurface(true);
        statusBarProvider.setServerVisible(true);
        final WindowState navBar = addNavigationBar();
        final InsetsSourceProvider navBarProvider = statusBar.getControllableInsetProvider();
        final int navBarId = statusBarProvider.getSource().getId();
        navBar.setHasSurface(true);
        navBarProvider.setServerVisible(true);
        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();

        // Make both system bars invisible.
        mAppWindow.setRequestedVisibleTypes(
                0, navigationBars() | statusBars());
        policy.updateBarControlTarget(mAppWindow);
        waitUntilWindowAnimatorIdle();
        assertFalse(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .isSourceOrDefaultVisible(statusBarId, statusBars()));
        assertFalse(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .isSourceOrDefaultVisible(navBarId, navigationBars()));

        policy.showTransient(navigationBars() | statusBars(), true /* isGestureOnSystemBar */);
        waitUntilWindowAnimatorIdle();
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(mAppWindow);

        // The app must get both fake controls.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            assertNull(controls[i].getLeash());
        }

        assertTrue(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .isSourceOrDefaultVisible(statusBarId, statusBars()));
        assertTrue(mDisplayContent.getInsetsStateController().getRawInsetsState()
                .isSourceOrDefaultVisible(navBarId, navigationBars()));
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testShowTransientBars_statusBarCanBeTransient_appGetsStatusBarFakeControl() {
        addStatusBar().getControllableInsetProvider().getSource().setVisible(false);
        addNavigationBar().getControllableInsetProvider().setServerVisible(true);

        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();
        policy.updateBarControlTarget(mAppWindow);
        policy.showTransient(navigationBars() | statusBars(),
                true /* isGestureOnSystemBar */);
        waitUntilWindowAnimatorIdle();
        assertTrue(policy.isTransient(statusBars()));
        assertFalse(policy.isTransient(navigationBars()));
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(mAppWindow);

        // The app must get the fake control of the status bar, and must get the real control of the
        // navigation bar.
        assertEquals(2, controls.length);
        for (int i = controls.length - 1; i >= 0; i--) {
            final InsetsSourceControl control = controls[i];
            if (control.getType() == statusBars()) {
                assertNull(controls[i].getLeash());
            } else {
                assertNotNull(controls[i].getLeash());
            }
        }
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testAbortTransientBars_bothCanBeAborted_appGetsBothRealControls() {
        final InsetsSource statusBarSource =
                addStatusBar().getControllableInsetProvider().getSource();
        final InsetsSource navBarSource =
                addNavigationBar().getControllableInsetProvider().getSource();
        statusBarSource.setVisible(false);
        navBarSource.setVisible(false);
        mAppWindow.setRequestedVisibleTypes(0, navigationBars() | statusBars());
        mAppWindow.mAboveInsetsState.addSource(navBarSource);
        mAppWindow.mAboveInsetsState.addSource(statusBarSource);
        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();
        policy.updateBarControlTarget(mAppWindow);
        policy.showTransient(navigationBars() | statusBars(),
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
        state.setSourceVisible(statusBarSource.getId(), true);
        state.setSourceVisible(navBarSource.getId(), true);

        final InsetsState clientState = mAppWindow.getInsetsState();
        // The transient bar states for client should be invisible.
        assertFalse(clientState.isSourceOrDefaultVisible(statusBarSource.getId(), statusBars()));
        assertFalse(clientState.isSourceOrDefaultVisible(navBarSource.getId(), navigationBars()));
        // The original state shouldn't be modified.
        assertTrue(state.isSourceOrDefaultVisible(statusBarSource.getId(), statusBars()));
        assertTrue(state.isSourceOrDefaultVisible(navBarSource.getId(), navigationBars()));

        mAppWindow.setRequestedVisibleTypes(
                navigationBars() | statusBars(), navigationBars() | statusBars());
        policy.onRequestedVisibleTypesChanged(mAppWindow);
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
        addStatusBar().getControllableInsetProvider().getSource().setVisible(false);
        addNavigationBar().getControllableInsetProvider().getSource().setVisible(false);
        final WindowState app = addWindow(TYPE_APPLICATION, "app");
        final WindowState app2 = addWindow(TYPE_APPLICATION, "app");

        final InsetsPolicy policy = mDisplayContent.getInsetsPolicy();
        policy.updateBarControlTarget(app);
        policy.showTransient(navigationBars() | statusBars(),
                true /* isGestureOnSystemBar */);
        final InsetsSourceControl[] controls =
                mDisplayContent.getInsetsStateController().getControlsForDispatch(app);
        policy.updateBarControlTarget(app2);
        assertFalse(policy.isTransient(statusBars()));
        assertFalse(policy.isTransient(navigationBars()));
    }

    @Test
    public void testFakeControlTarget_overrideVisibilityReceivedByWindows() {
        final WindowState statusBar = addStatusBar();
        final InsetsSourceProvider statusBarProvider = statusBar.getControllableInsetProvider();
        statusBar.mSession.mCanForceShowingInsets = true;
        statusBar.setHasSurface(true);
        statusBarProvider.setServerVisible(true);

        final InsetsSource statusBarSource = statusBarProvider.getSource();
        final int statusBarId = statusBarSource.getId();
        assertTrue(statusBarSource.isVisible());

        final WindowState app1 = addWindow(TYPE_APPLICATION, "app1");
        app1.mAboveInsetsState.addSource(statusBarSource);
        assertTrue(app1.getInsetsState().peekSource(statusBarId).isVisible());

        final WindowState app2 = addWindow(TYPE_APPLICATION, "app2");
        app2.mAboveInsetsState.addSource(statusBarSource);
        assertTrue(app2.getInsetsState().peekSource(statusBarId).isVisible());

        app2.setRequestedVisibleTypes(0, navigationBars() | statusBars());
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(app2);
        waitUntilWindowAnimatorIdle();

        // app2 is the real control target now. It can override the visibility of all sources that
        // it controls.
        assertFalse(statusBarSource.isVisible());
        assertFalse(app1.getInsetsState().peekSource(statusBarId).isVisible());
        assertFalse(app2.getInsetsState().peekSource(statusBarId).isVisible());

        statusBar.mAttrs.forciblyShownTypes = statusBars();
        mDisplayContent.getDisplayPolicy().applyPostLayoutPolicyLw(
                statusBar, statusBar.mAttrs, null, null);
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(app2);
        waitUntilWindowAnimatorIdle();

        // app2 is the fake control target now. It can only override the visibility of sources
        // received by windows, but not the raw source.
        assertTrue(statusBarSource.isVisible());
        assertFalse(app1.getInsetsState().peekSource(statusBarId).isVisible());
        assertFalse(app2.getInsetsState().peekSource(statusBarId).isVisible());

    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testConsumeImeInsets() {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        final InsetsSource imeSource = new InsetsSource(ID_IME, ime());
        imeSource.setVisible(true);
        mImeWindow.mHasSurface = true;

        final WindowState win1 = addWindow(TYPE_APPLICATION, "win1");
        final WindowState win2 = addWindow(TYPE_APPLICATION, "win2");

        win1.mAboveInsetsState.addSource(imeSource);
        win1.mHasSurface = true;
        win2.mAboveInsetsState.addSource(imeSource);
        win2.mHasSurface = true;

        assertTrue(mImeWindow.isVisible());
        assertTrue(win1.isVisible());
        assertTrue(win2.isVisible());

        // Make sure both windows have visible IME insets.
        assertTrue(win1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue(win2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));

        win2.mAttrs.privateFlags |= PRIVATE_FLAG_CONSUME_IME_INSETS;

        displayPolicy.beginPostLayoutPolicyLw();
        displayPolicy.applyPostLayoutPolicyLw(win2, win2.mAttrs, null, null);
        displayPolicy.applyPostLayoutPolicyLw(win1, win1.mAttrs, null, null);
        displayPolicy.finishPostLayoutPolicyLw();

        // Make sure win2 doesn't have visible IME insets, but win1 still does.
        assertTrue(win2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertFalse(win1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue(win1.getWindowFrames().hasInsetsChanged());

        win2.mAttrs.privateFlags &= ~PRIVATE_FLAG_CONSUME_IME_INSETS;
        win2.getWindowFrames().setInsetsChanged(false);

        displayPolicy.beginPostLayoutPolicyLw();
        displayPolicy.applyPostLayoutPolicyLw(win2, win2.mAttrs, null, null);
        displayPolicy.applyPostLayoutPolicyLw(win1, win1.mAttrs, null, null);
        displayPolicy.finishPostLayoutPolicyLw();

        // Make sure both windows have visible IME insets.
        assertTrue(win1.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue(win2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue(win1.getWindowFrames().hasInsetsChanged());
    }

    private WindowState addNavigationBar() {
        final Binder owner = new Binder();
        final WindowState win = createWindow(null, TYPE_NAVIGATION_BAR, "navBar");
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        win.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.navigationBars()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.tappableElement()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.mandatorySystemGestures())
        };
        mDisplayContent.getDisplayPolicy().addWindowLw(win, win.mAttrs);
        return win;
    }

    private WindowState addStatusBar() {
        final Binder owner = new Binder();
        final WindowState win = createWindow(null, TYPE_STATUS_BAR, "statusBar");
        win.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        win.mAttrs.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.statusBars()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.tappableElement()),
                new InsetsFrameProvider(owner, 0, WindowInsets.Type.mandatorySystemGestures())
        };
        mDisplayContent.getDisplayPolicy().addWindowLw(win, win.mAttrs);
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
        mDisplayContent.getDisplayPolicy().addWindowLw(win, win.mAttrs);
        // Force update the focus in DisplayPolicy here. Otherwise, without server side focus
        // update, the policy relying on windowing type will never get updated.
        mDisplayContent.getDisplayPolicy().focusChangedLw(null, win);
        mDisplayContent.getInsetsPolicy().updateBarControlTarget(win);
        return mDisplayContent.getInsetsStateController().getControlsForDispatch(win);
    }
}
