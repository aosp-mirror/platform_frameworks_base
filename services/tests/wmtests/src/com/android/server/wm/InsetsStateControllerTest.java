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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.DisplayContent.IME_TARGET_INPUT;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class InsetsStateControllerTest extends WindowTestsBase {

    @Test
    public void testStripForDispatch_navBar() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState ime = createWindow(null, TYPE_APPLICATION, "ime");

        // IME cannot be the IME target.
        ime.mAttrs.flags |= FLAG_NOT_FOCUSABLE;

        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        getController().getSourceProvider(ITYPE_IME).setWindow(ime, null, null);
        assertNull(getController().getInsetsForWindow(navBar).peekSource(ITYPE_IME));
        assertNull(getController().getInsetsForWindow(navBar).peekSource(ITYPE_STATUS_BAR));
    }

    @Test
    public void testStripForDispatch_pip() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");

        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        app.setWindowingMode(WINDOWING_MODE_PINNED);

        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_STATUS_BAR));
        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_NAVIGATION_BAR));
        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_IME));
    }

    @Test
    public void testStripForDispatch_freeform() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");

        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        app.setWindowingMode(WINDOWING_MODE_FREEFORM);

        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_STATUS_BAR));
        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_NAVIGATION_BAR));
    }

    @Test
    public void testStripForDispatch_multiwindow_alwaysOnTop() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");

        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        app.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        app.setAlwaysOnTop(true);

        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_STATUS_BAR));
        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_NAVIGATION_BAR));
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testStripForDispatch_independentSources() {
        getController().getSourceProvider(ITYPE_IME).setWindow(mImeWindow, null, null);

        final WindowState app1 = createWindow(null, TYPE_APPLICATION, "app1");
        final WindowState app2 = createWindow(null, TYPE_APPLICATION, "app2");

        app1.mAboveInsetsState.addSource(getController().getRawInsetsState().getSource(ITYPE_IME));

        getController().getRawInsetsState().setSourceVisible(ITYPE_IME, true);
        assertFalse(getController().getInsetsForWindow(app2).getSource(ITYPE_IME)
                .isVisible());
        assertTrue(getController().getInsetsForWindow(app1).getSource(ITYPE_IME)
                .isVisible());
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testStripForDispatch_belowIme() {
        getController().getSourceProvider(ITYPE_IME).setWindow(mImeWindow, null, null);

        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mAboveInsetsState.getSource(ITYPE_IME).setVisible(true);
        app.mAboveInsetsState.getSource(ITYPE_IME).setFrame(mImeWindow.getFrame());

        getController().getRawInsetsState().setSourceVisible(ITYPE_IME, true);
        assertTrue(getController().getInsetsForWindow(app).getSource(ITYPE_IME).isVisible());
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testStripForDispatch_aboveIme() {
        getController().getSourceProvider(ITYPE_IME).setWindow(mImeWindow, null, null);

        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");

        getController().getRawInsetsState().setSourceVisible(ITYPE_IME, true);
        assertFalse(getController().getInsetsForWindow(app).getSource(ITYPE_IME)
                .isVisible());
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testStripForDispatch_imeOrderChanged() {
        // This can be the IME z-order target while app cannot be the IME z-order target.
        // This is also the only IME control target in this test, so IME won't be invisible caused
        // by the control-target change.
        mDisplayContent.updateImeInputAndControlTarget(
                createWindow(null, TYPE_APPLICATION, "base"));

        // Make IME and stay visible during the test.
        mImeWindow.setHasSurface(true);
        getController().getSourceProvider(ITYPE_IME).setWindow(mImeWindow, null, null);
        getController().onImeControlTargetChanged(mDisplayContent.getImeTarget(IME_TARGET_INPUT));
        final InsetsState requestedState = new InsetsState();
        requestedState.getSource(ITYPE_IME).setVisible(true);
        mDisplayContent.getImeTarget(IME_TARGET_INPUT).getWindow()
                .updateRequestedVisibility(requestedState);
        getController().onInsetsModified(mDisplayContent.getImeTarget(IME_TARGET_INPUT));

        // Send our spy window (app) into the system so that we can detect the invocation.
        final WindowState win = createWindow(null, TYPE_APPLICATION, "app");
        win.setHasSurface(true);
        final WindowToken parent = win.mToken;
        parent.removeChild(win);
        final WindowState app = spy(win);
        parent.addWindow(app);

        // Adding FLAG_NOT_FOCUSABLE makes app above IME.
        app.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        mDisplayContent.computeImeTarget(true);
        mDisplayContent.applySurfaceChangesTransaction();

        // app won't get visible IME insets while above IME even when IME is visible.
        assertTrue(getController().getRawInsetsState().getSourceOrDefaultVisibility(ITYPE_IME));
        assertFalse(getController().getInsetsForWindow(app).getSource(ITYPE_IME)
                .isVisible());

        // Reset invocation counter.
        clearInvocations(app);

        // Removing FLAG_NOT_FOCUSABLE makes app below IME.
        app.mAttrs.flags &= ~FLAG_NOT_FOCUSABLE;
        mDisplayContent.computeImeTarget(true);
        mDisplayContent.applySurfaceChangesTransaction();
        app.mAboveInsetsState.getSource(ITYPE_IME).setVisible(true);
        app.mAboveInsetsState.getSource(ITYPE_IME).setFrame(mImeWindow.getFrame());

        // Make sure app got notified.
        verify(app, atLeastOnce()).notifyInsetsChanged();

        // app will get visible IME insets while below IME.
        assertTrue(getController().getInsetsForWindow(app).getSource(ITYPE_IME).isVisible());
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testStripForDispatch_childWindow_altFocusable() {
        getController().getSourceProvider(ITYPE_IME).setWindow(mImeWindow, null, null);

        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final WindowState child = createWindow(app, TYPE_APPLICATION, "child");
        app.mAboveInsetsState.set(getController().getRawInsetsState());
        child.mAboveInsetsState.set(getController().getRawInsetsState());
        child.mAttrs.flags |= FLAG_ALT_FOCUSABLE_IM;

        mDisplayContent.computeImeTarget(true);
        mDisplayContent.setLayoutNeeded();
        mDisplayContent.applySurfaceChangesTransaction();

        getController().getRawInsetsState().setSourceVisible(ITYPE_IME, true);
        assertTrue(getController().getInsetsForWindow(app).getSource(ITYPE_IME).isVisible());
        assertFalse(getController().getInsetsForWindow(child).getSource(ITYPE_IME)
                .isVisible());
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testStripForDispatch_childWindow_splitScreen() {
        getController().getSourceProvider(ITYPE_IME).setWindow(mImeWindow, null, null);

        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final WindowState child = createWindow(app, TYPE_APPLICATION, "child");
        app.mAboveInsetsState.addSource(getController().getRawInsetsState().peekSource(ITYPE_IME));
        child.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        child.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);

        mDisplayContent.computeImeTarget(true);
        mDisplayContent.setLayoutNeeded();
        mDisplayContent.applySurfaceChangesTransaction();

        getController().getRawInsetsState().setSourceVisible(ITYPE_IME, true);
        assertTrue(getController().getInsetsForWindow(app).getSource(ITYPE_IME).isVisible());
        assertFalse(getController().getInsetsForWindow(child).getSource(ITYPE_IME)
                .isVisible());
    }

    @Test
    public void testImeForDispatch() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState ime = createWindow(null, TYPE_APPLICATION, "ime");

        // IME cannot be the IME target.
        ime.mAttrs.flags |= FLAG_NOT_FOCUSABLE;

        InsetsSourceProvider statusBarProvider =
                getController().getSourceProvider(ITYPE_STATUS_BAR);
        statusBarProvider.setWindow(statusBar, null, ((displayFrames, windowState, rect) ->
                        rect.set(0, 1, 2, 3)));
        getController().getSourceProvider(ITYPE_IME).setWindow(ime, null, null);
        statusBar.setControllableInsetProvider(statusBarProvider);

        statusBarProvider.onPostLayout();

        final InsetsState state = getController().getInsetsForWindow(ime);
        assertEquals(new Rect(0, 1, 2, 3), state.getSource(ITYPE_STATUS_BAR).getFrame());
    }

    @Test
    public void testBarControllingWinChanged() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState climateBar = createWindow(null, TYPE_APPLICATION, "climateBar");
        final WindowState extraNavBar = createWindow(null, TYPE_APPLICATION, "extraNavBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        getController().getSourceProvider(ITYPE_CLIMATE_BAR).setWindow(climateBar, null, null);
        getController().getSourceProvider(ITYPE_EXTRA_NAVIGATION_BAR).setWindow(extraNavBar, null,
                null);
        getController().onBarControlTargetChanged(app, null, app, null);
        InsetsSourceControl[] controls = getController().getControlsForDispatch(app);
        assertEquals(4, controls.length);
    }

    @Test
    public void testControlRevoked() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().onBarControlTargetChanged(app, null, null, null);
        assertNotNull(getController().getControlsForDispatch(app));
        getController().onBarControlTargetChanged(null, null, null, null);
        assertNull(getController().getControlsForDispatch(app));
    }

    @Test
    public void testControlRevoked_animation() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().onBarControlTargetChanged(app, null, null, null);
        assertNotNull(getController().getControlsForDispatch(app));
        statusBar.cancelAnimation();
        assertNull(getController().getControlsForDispatch(app));
    }

    @Test
    public void testTransientVisibilityOfFixedRotationState() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final InsetsSourceProvider provider = getController().getSourceProvider(ITYPE_STATUS_BAR);
        provider.setWindow(statusBar, null, null);

        final InsetsState rotatedState = new InsetsState(app.getInsetsState(),
                true /* copySources */);
        spyOn(app.mToken);
        doReturn(rotatedState).when(app.mToken).getFixedRotationTransformInsetsState();
        assertTrue(rotatedState.getSource(ITYPE_STATUS_BAR).isVisible());

        provider.getSource().setVisible(false);
        mDisplayContent.getInsetsPolicy().showTransient(new int[] { ITYPE_STATUS_BAR });

        assertTrue(mDisplayContent.getInsetsPolicy().isTransient(ITYPE_STATUS_BAR));
        assertFalse(app.getInsetsState().getSource(ITYPE_STATUS_BAR).isVisible());
    }

    @Test
    public void testUpdateAboveInsetsState_provideInsets() {
        final WindowState app = createTestWindow("app");
        final WindowState statusBar = createTestWindow("statusBar");
        final WindowState navBar = createTestWindow("navBar");

        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);

        assertNull(app.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNull(statusBar.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNull(navBar.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));

        getController().updateAboveInsetsState(statusBar, true /* notifyInsetsChange */);

        assertNotNull(app.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNull(statusBar.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNull(navBar.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));

        verify(app, atLeastOnce()).notifyInsetsChanged();
    }

    @Test
    public void testUpdateAboveInsetsState_receiveInsets() {
        final WindowState app = createTestWindow("app");
        final WindowState statusBar = createTestWindow("statusBar");
        final WindowState navBar = createTestWindow("navBar");

        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);

        assertNull(app.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNull(app.mAboveInsetsState.peekSource(ITYPE_NAVIGATION_BAR));

        getController().updateAboveInsetsState(app, true /* notifyInsetsChange */);

        assertNotNull(app.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNotNull(app.mAboveInsetsState.peekSource(ITYPE_NAVIGATION_BAR));

        verify(app, atLeastOnce()).notifyInsetsChanged();
    }

    @Test
    public void testUpdateAboveInsetsState_zOrderChanged() {
        final WindowState ime = createTestWindow("ime");
        final WindowState app = createTestWindow("app");
        final WindowState statusBar = createTestWindow("statusBar");
        final WindowState navBar = createTestWindow("navBar");

        getController().getSourceProvider(ITYPE_IME).setWindow(ime, null, null);
        getController().getSourceProvider(ITYPE_IME).setClientVisible(true);
        getController().getSourceProvider(ITYPE_STATUS_BAR).setWindow(statusBar, null, null);
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        getController().updateAboveInsetsState(ime, false /* notifyInsetsChange */);
        getController().updateAboveInsetsState(statusBar, false /* notifyInsetsChange */);
        getController().updateAboveInsetsState(navBar, false /* notifyInsetsChange */);

        // ime is below others.
        assertNull(app.mAboveInsetsState.peekSource(ITYPE_IME));
        assertNull(statusBar.mAboveInsetsState.peekSource(ITYPE_IME));
        assertNull(navBar.mAboveInsetsState.peekSource(ITYPE_IME));
        assertNotNull(ime.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNotNull(ime.mAboveInsetsState.peekSource(ITYPE_NAVIGATION_BAR));

        ime.getParent().positionChildAt(POSITION_TOP, ime, true /* includingParents */);
        getController().updateAboveInsetsState(ime, true /* notifyInsetsChange */);

        // ime is above others.
        assertNotNull(app.mAboveInsetsState.peekSource(ITYPE_IME));
        assertNotNull(statusBar.mAboveInsetsState.peekSource(ITYPE_IME));
        assertNotNull(navBar.mAboveInsetsState.peekSource(ITYPE_IME));
        assertNull(ime.mAboveInsetsState.peekSource(ITYPE_STATUS_BAR));
        assertNull(ime.mAboveInsetsState.peekSource(ITYPE_NAVIGATION_BAR));

        verify(ime, atLeastOnce()).notifyInsetsChanged();
        verify(app, atLeastOnce()).notifyInsetsChanged();
        verify(statusBar, atLeastOnce()).notifyInsetsChanged();
        verify(navBar, atLeastOnce()).notifyInsetsChanged();
    }

    @Test
    public void testDispatchGlobalInsets() {
        final WindowState navBar = createWindow(null, TYPE_APPLICATION, "navBar");
        getController().getSourceProvider(ITYPE_NAVIGATION_BAR).setWindow(navBar, null, null);
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        assertNull(getController().getInsetsForWindow(app).peekSource(ITYPE_NAVIGATION_BAR));
        app.mAttrs.receiveInsetsIgnoringZOrder = true;
        assertNotNull(getController().getInsetsForWindow(app).peekSource(ITYPE_NAVIGATION_BAR));
    }

    private WindowState createTestWindow(String name) {
        final WindowState win = createWindow(null, TYPE_APPLICATION, name);
        win.setHasSurface(true);
        spyOn(win);
        return win;
    }

    private InsetsStateController getController() {
        return mDisplayContent.getInsetsStateController();
    }
}
