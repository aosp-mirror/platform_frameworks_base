/*
 * Copyright (C) 2016 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.hardware.camera2.params.OutputConfiguration.ROTATION_90;
import static android.view.InsetsState.TYPE_TOP_BAR;
import static android.view.Surface.ROTATION_0;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.Size;
import android.view.DisplayCutout;
import android.view.InsetsSource;
import android.view.SurfaceControl;
import android.view.ViewRootImpl;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedList;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:WindowStateTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowStateTests extends WindowTestsBase {
    private static int sPreviousNewInsetsMode;

    @BeforeClass
    public static void setUpOnce() {
        // TODO: Make use of SettingsSession when it becomes feasible for this.
        sPreviousNewInsetsMode = ViewRootImpl.sNewInsetsMode;
        // To let the insets provider control the insets visibility, the insets mode has to be
        // NEW_INSETS_MODE_FULL.
        ViewRootImpl.sNewInsetsMode = NEW_INSETS_MODE_FULL;
    }

    @AfterClass
    public static void tearDownOnce() {
        ViewRootImpl.sNewInsetsMode = sPreviousNewInsetsMode;
    }

    @Before
    public void setUp() {
        // TODO: Let the insets source with new mode keep the visibility control, and remove this
        // setup code. Now mTopFullscreenOpaqueWindowState will take back the control of insets
        // visibility.
        spyOn(mDisplayContent);
        doNothing().when(mDisplayContent).layoutAndAssignWindowLayersIfNeeded();
    }

    @Test
    public void testIsParentWindowHidden() {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child2");

        // parentWindow is initially set to hidden.
        assertTrue(parentWindow.mHidden);
        assertFalse(parentWindow.isParentWindowHidden());
        assertTrue(child1.isParentWindowHidden());
        assertTrue(child2.isParentWindowHidden());

        parentWindow.mHidden = false;
        assertFalse(parentWindow.isParentWindowHidden());
        assertFalse(child1.isParentWindowHidden());
        assertFalse(child2.isParentWindowHidden());
    }

    @Test
    public void testIsChildWindow() {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child2");
        final WindowState randomWindow = createWindow(null, TYPE_APPLICATION, "randomWindow");

        assertFalse(parentWindow.isChildWindow());
        assertTrue(child1.isChildWindow());
        assertTrue(child2.isChildWindow());
        assertFalse(randomWindow.isChildWindow());
    }

    @Test
    public void testHasChild() {
        final WindowState win1 = createWindow(null, TYPE_APPLICATION, "win1");
        final WindowState win11 = createWindow(win1, FIRST_SUB_WINDOW, "win11");
        final WindowState win12 = createWindow(win1, FIRST_SUB_WINDOW, "win12");
        final WindowState win2 = createWindow(null, TYPE_APPLICATION, "win2");
        final WindowState win21 = createWindow(win2, FIRST_SUB_WINDOW, "win21");
        final WindowState randomWindow = createWindow(null, TYPE_APPLICATION, "randomWindow");

        assertTrue(win1.hasChild(win11));
        assertTrue(win1.hasChild(win12));
        assertTrue(win2.hasChild(win21));

        assertFalse(win1.hasChild(win21));
        assertFalse(win1.hasChild(randomWindow));

        assertFalse(win2.hasChild(win11));
        assertFalse(win2.hasChild(win12));
        assertFalse(win2.hasChild(randomWindow));
    }

    @Test
    public void testGetParentWindow() {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child2");

        assertNull(parentWindow.getParentWindow());
        assertEquals(parentWindow, child1.getParentWindow());
        assertEquals(parentWindow, child2.getParentWindow());
    }

    @Test
    public void testOverlayWindowHiddenWhenSuspended() {
        final WindowState overlayWindow = spy(createWindow(null, TYPE_APPLICATION_OVERLAY,
                "overlayWindow"));
        overlayWindow.setHiddenWhileSuspended(true);
        verify(overlayWindow).hideLw(true, true);
        overlayWindow.setHiddenWhileSuspended(false);
        verify(overlayWindow).showLw(true, true);
    }

    @Test
    public void testGetTopParentWindow() {
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        final WindowState child1 = createWindow(root, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(child1, FIRST_SUB_WINDOW, "child2");

        assertEquals(root, root.getTopParentWindow());
        assertEquals(root, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
        assertEquals(root, child2.getTopParentWindow());

        // Test case were child is detached from parent.
        root.removeChild(child1);
        assertEquals(child1, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
    }

    @Test
    public void testIsOnScreen_hiddenByPolicy() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, "window");
        window.setHasSurface(true);
        assertTrue(window.isOnScreen());
        window.hideLw(false /* doAnimation */);
        assertFalse(window.isOnScreen());
    }

    @Test
    public void testCanBeImeTarget() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        final WindowState imeWindow = createWindow(null, TYPE_INPUT_METHOD, "imeWindow");

        // Setting FLAG_NOT_FOCUSABLE without FLAG_ALT_FOCUSABLE_IM prevents the window from being
        // an IME target.
        appWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        imeWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;

        // Make windows visible
        appWindow.setHasSurface(true);
        imeWindow.setHasSurface(true);

        // Windows without flags (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM) can't be IME targets
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Add IME target flags
        appWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);
        imeWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);

        // Visible app window with flags can be IME target while an IME window can never be an IME
        // target regardless of its visibility or flags.
        assertTrue(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Make windows invisible
        appWindow.hideLw(false /* doAnimation */);
        imeWindow.hideLw(false /* doAnimation */);

        // Invisible window can't be IME targets even if they have the right flags.
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Simulate the window is in split screen primary stack and the current state is
        // minimized and home stack is resizable, so that we should ignore input for the stack.
        final DockedStackDividerController controller =
                mDisplayContent.getDockedDividerController();
        final TaskStack stack = createTaskStackOnDisplay(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY,
                ACTIVITY_TYPE_STANDARD, mDisplayContent);
        spyOn(appWindow);
        spyOn(controller);
        spyOn(stack);
        doReturn(true).when(controller).isMinimizedDock();
        doReturn(true).when(controller).isHomeStackResizable();
        doReturn(stack).when(appWindow).getStack();

        // Make sure canBeImeTarget is false due to shouldIgnoreInput is true;
        assertFalse(appWindow.canBeImeTarget());
        assertTrue(stack.shouldIgnoreInput());
    }

    @Test
    public void testGetWindow() {
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        final WindowState mediaChild = createWindow(root, TYPE_APPLICATION_MEDIA, "mediaChild");
        final WindowState mediaOverlayChild = createWindow(root,
                TYPE_APPLICATION_MEDIA_OVERLAY, "mediaOverlayChild");
        final WindowState attachedDialogChild = createWindow(root,
                TYPE_APPLICATION_ATTACHED_DIALOG, "attachedDialogChild");
        final WindowState subPanelChild = createWindow(root,
                TYPE_APPLICATION_SUB_PANEL, "subPanelChild");
        final WindowState aboveSubPanelChild = createWindow(root,
                TYPE_APPLICATION_ABOVE_SUB_PANEL, "aboveSubPanelChild");

        final LinkedList<WindowState> windows = new LinkedList<>();

        root.getWindow(w -> {
            windows.addLast(w);
            return false;
        });

        // getWindow should have returned candidate windows in z-order.
        assertEquals(aboveSubPanelChild, windows.pollFirst());
        assertEquals(subPanelChild, windows.pollFirst());
        assertEquals(attachedDialogChild, windows.pollFirst());
        assertEquals(root, windows.pollFirst());
        assertEquals(mediaOverlayChild, windows.pollFirst());
        assertEquals(mediaChild, windows.pollFirst());
        assertTrue(windows.isEmpty());
    }

    @Test
    public void testPrepareWindowToDisplayDuringRelayout() {
        // Call prepareWindowToDisplayDuringRelayout for a window without FLAG_TURN_SCREEN_ON before
        // calling setCurrentLaunchCanTurnScreenOn for windows with flag in the same appWindowToken.
        final AppWindowToken appWindowToken = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState first = createWindow(null, TYPE_APPLICATION, appWindowToken, "first");
        final WindowState second = createWindow(null, TYPE_APPLICATION, appWindowToken, "second");
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        testPrepareWindowToDisplayDuringRelayout(first, false /* expectedWakeupCalled */,
                true /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for two window that have FLAG_TURN_SCREEN_ON
        // from the same appWindowToken. Only one should trigger the wakeup.
        appWindowToken.setCurrentLaunchCanTurnScreenOn(true);
        first.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Without window flags, the state of ActivityRecord.canTurnScreenOn should still be able to
        // turn on the screen.
        appWindowToken.setCurrentLaunchCanTurnScreenOn(true);
        first.mAttrs.flags &= ~WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        doReturn(true).when(appWindowToken.mActivityRecord).canTurnScreenOn();

        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for a windows that are not children of an
        // appWindowToken. Both windows have the FLAG_TURNS_SCREEN_ON so both should call wakeup
        final WindowToken windowToken = WindowTestUtils.createTestWindowToken(FIRST_SUB_WINDOW,
                mDisplayContent);
        final WindowState firstWindow = createWindow(null, TYPE_APPLICATION, windowToken,
                "firstWindow");
        final WindowState secondWindow = createWindow(null, TYPE_APPLICATION, windowToken,
                "secondWindow");
        firstWindow.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        secondWindow.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        final WindowState.PowerManagerWrapper powerManagerWrapper =
                mSystemServicesTestRule.getPowerManagerWrapper();
        reset(powerManagerWrapper);
        firstWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(powerManagerWrapper).wakeUp(anyLong(), anyInt(), anyString());

        reset(powerManagerWrapper);
        secondWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(powerManagerWrapper).wakeUp(anyLong(), anyInt(), anyString());
    }

    private void testPrepareWindowToDisplayDuringRelayout(WindowState appWindow,
            boolean expectedWakeupCalled, boolean expectedCurrentLaunchCanTurnScreenOn) {
        final WindowState.PowerManagerWrapper powerManagerWrapper =
                mSystemServicesTestRule.getPowerManagerWrapper();
        reset(powerManagerWrapper);
        appWindow.prepareWindowToDisplayDuringRelayout(false /* wasVisible */);

        if (expectedWakeupCalled) {
            verify(powerManagerWrapper).wakeUp(anyLong(), anyInt(), anyString());
        } else {
            verify(powerManagerWrapper, never()).wakeUp(anyLong(), anyInt(), anyString());
        }
        // If wakeup is expected to be called, the currentLaunchCanTurnScreenOn should be false
        // because the state will be consumed.
        assertThat(appWindow.mAppToken.currentLaunchCanTurnScreenOn(),
                is(expectedCurrentLaunchCanTurnScreenOn));
    }

    @Test
    public void testCanAffectSystemUiFlags() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mToken.setHidden(false);
        assertTrue(app.canAffectSystemUiFlags());
        app.mToken.setHidden(true);
        assertFalse(app.canAffectSystemUiFlags());
        app.mToken.setHidden(false);
        app.mAttrs.alpha = 0.0f;
        assertFalse(app.canAffectSystemUiFlags());
    }

    @Test
    public void testCanAffectSystemUiFlags_disallow() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mToken.setHidden(false);
        assertTrue(app.canAffectSystemUiFlags());
        app.getTask().setCanAffectSystemUiFlags(false);
        assertFalse(app.canAffectSystemUiFlags());
    }

    @Test
    public void testVisibleWithInsetsProvider() throws Exception {
        final WindowState topBar = createWindow(null, TYPE_STATUS_BAR, "topBar");
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        topBar.mHasSurface = true;
        assertTrue(topBar.isVisible());
        mDisplayContent.getInsetsStateController().getSourceProvider(TYPE_TOP_BAR)
                .setWindow(topBar, null);
        mDisplayContent.getInsetsStateController().onBarControlTargetChanged(app, app);
        mDisplayContent.getInsetsStateController().getSourceProvider(TYPE_TOP_BAR)
                .onInsetsModified(app, new InsetsSource(TYPE_TOP_BAR));
        waitUntilHandlersIdle();
        assertFalse(topBar.isVisible());
    }

    @Test
    public void testIsSelfOrAncestorWindowAnimating() {
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        final WindowState child1 = createWindow(root, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(child1, FIRST_SUB_WINDOW, "child2");
        assertFalse(child2.isSelfOrAncestorWindowAnimatingExit());
        child2.mAnimatingExit = true;
        assertTrue(child2.isSelfOrAncestorWindowAnimatingExit());
        child2.mAnimatingExit = false;
        root.mAnimatingExit = true;
        assertTrue(child2.isSelfOrAncestorWindowAnimatingExit());
    }

    @Test
    @FlakyTest(bugId = 74078662)
    public void testLayoutSeqResetOnReparent() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mLayoutSeq = 1;
        mDisplayContent.mLayoutSeq = 1;

        DisplayContent newDisplay = createNewDisplay();

        app.onDisplayChanged(newDisplay);

        assertThat(app.mLayoutSeq, not(is(mDisplayContent.mLayoutSeq)));
    }

    @Test
    public void testDisplayIdUpdatedOnReparent() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        // fake a different display
        app.mInputWindowHandle.displayId = mDisplayContent.getDisplayId() + 1;
        app.onDisplayChanged(mDisplayContent);

        assertThat(app.mInputWindowHandle.displayId, is(mDisplayContent.getDisplayId()));
        assertThat(app.getDisplayId(), is(mDisplayContent.getDisplayId()));
    }

    @Test
    public void testSeamlesslyRotateWindow() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);

        app.mHasSurface = true;
        app.mSurfaceControl = mock(SurfaceControl.class);
        try {
            app.getFrameLw().set(10, 20, 60, 80);
            app.updateSurfacePosition(t);

            app.seamlesslyRotateIfAllowed(t, ROTATION_0, ROTATION_90, true);

            assertTrue(app.mSeamlesslyRotated);

            // Verify we un-rotate the window state surface.
            Matrix matrix = new Matrix();
            // Un-rotate 90 deg
            matrix.setRotate(270);
            // Translate it back to origin
            matrix.postTranslate(0, mDisplayInfo.logicalWidth);
            verify(t).setMatrix(eq(app.mSurfaceControl), eq(matrix), any(float[].class));

            // Verify we update the position as well.
            float[] currentSurfacePos = {app.mLastSurfacePosition.x, app.mLastSurfacePosition.y};
            matrix.mapPoints(currentSurfacePos);
            verify(t).setPosition(eq(app.mSurfaceControl), eq(currentSurfacePos[0]),
                    eq(currentSurfacePos[1]));
        } finally {
            app.mSurfaceControl = null;
            app.mHasSurface = false;
        }
    }

    @Test
    @FlakyTest(bugId = 74078662)
    public void testDisplayCutoutIsCalculatedRelativeToFrame() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        WindowFrames wf = app.getWindowFrames();
        wf.mParentFrame.set(7, 10, 185, 380);
        wf.mDisplayFrame.set(wf.mParentFrame);
        final DisplayCutout cutout = new DisplayCutout(
                Insets.of(0, 15, 0, 22) /* safeInset */,
                null /* boundLeft */,
                new Rect(95, 0, 105, 15),
                null /* boundRight */,
                new Rect(95, 378, 105, 400));
        wf.setDisplayCutout(new WmDisplayCutout(cutout, new Size(200, 400)));

        app.computeFrameLw();
        assertThat(app.getWmDisplayCutout().getDisplayCutout(), is(cutout.inset(7, 10, 5, 20)));
    }

    @Test
    public void testVisibilityChangeSwitchUser() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, "app");
        window.mHasSurface = true;
        window.setShowToOwnerOnlyLocked(true);

        mWm.mCurrentUserId = 1;
        window.switchUser();
        assertFalse(window.isVisible());
        assertFalse(window.isVisibleByPolicy());

        mWm.mCurrentUserId = 0;
        window.switchUser();
        assertTrue(window.isVisible());
        assertTrue(window.isVisibleByPolicy());
    }

    @Test
    public void testGetTransformationMatrix() {
        final int PARENT_WINDOW_OFFSET = 1;
        final int DISPLAY_IN_PARENT_WINDOW_OFFSET = 2;
        final int WINDOW_OFFSET = 3;
        final float OFFSET_SUM =
                PARENT_WINDOW_OFFSET + DISPLAY_IN_PARENT_WINDOW_OFFSET + WINDOW_OFFSET;

        final WindowState win0 = createWindow(null, TYPE_APPLICATION, "win0");

        final DisplayContent dc = createNewDisplay();
        win0.getFrameLw().offsetTo(PARENT_WINDOW_OFFSET, 0);
        dc.reparentDisplayContent(win0, win0.getSurfaceControl());
        dc.updateLocation(win0, DISPLAY_IN_PARENT_WINDOW_OFFSET, 0);

        final float[] values = new float[9];
        final Matrix matrix = new Matrix();
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        final WindowState win1 = createWindow(null, TYPE_APPLICATION, dc, "win1");
        win1.mHasSurface = true;
        win1.mSurfaceControl = mock(SurfaceControl.class);
        win1.getFrameLw().offsetTo(WINDOW_OFFSET, 0);
        win1.updateSurfacePosition(t);
        win1.getTransformationMatrix(values, matrix);

        matrix.getValues(values);
        assertEquals(OFFSET_SUM, values[Matrix.MTRANS_X], 0f);
        assertEquals(0f, values[Matrix.MTRANS_Y], 0f);
    }
}
