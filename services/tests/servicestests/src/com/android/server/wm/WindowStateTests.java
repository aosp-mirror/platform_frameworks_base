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
 * limitations under the License
 */

package com.android.server.wm;

import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.WindowManager;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.FlakyTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.LinkedList;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.hardware.camera2.params.OutputConfiguration.ROTATION_90;
import static android.view.Surface.ROTATION_0;
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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link WindowState} class.
 *
 * atest FrameworksServicesTests:com.android.server.wm.WindowStateTests
 */
@SmallTest
@FlakyTest(bugId = 74078662)
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowStateTests extends WindowTestsBase {

    @Test
    public void testIsParentWindowHidden() throws Exception {
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
    public void testIsChildWindow() throws Exception {
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
    public void testHasChild() throws Exception {
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
    public void testGetParentWindow() throws Exception {
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
    public void testGetTopParentWindow() throws Exception {
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
    public void testCanBeImeTarget() throws Exception {
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
    }

    @Test
    public void testGetWindow() throws Exception {
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

        final LinkedList<WindowState> windows = new LinkedList();

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
    public void testPrepareWindowToDisplayDuringRelayout() throws Exception {
        testPrepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        testPrepareWindowToDisplayDuringRelayout(true /*wasVisible*/);

        // Call prepareWindowToDisplayDuringRelayout for a window without FLAG_TURN_SCREEN_ON
        // before calling prepareWindowToDisplayDuringRelayout for windows with flag in the same
        // appWindowToken.
        final AppWindowToken appWindowToken = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState first = createWindow(null, TYPE_APPLICATION, appWindowToken, "first");
        final WindowState second = createWindow(null, TYPE_APPLICATION, appWindowToken, "second");
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        reset(mPowerManagerWrapper);
        first.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(mPowerManagerWrapper, never()).wakeUp(anyLong(), anyString());
        assertTrue(appWindowToken.canTurnScreenOn());

        reset(mPowerManagerWrapper);
        second.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(mPowerManagerWrapper).wakeUp(anyLong(), anyString());
        assertFalse(appWindowToken.canTurnScreenOn());

        // Call prepareWindowToDisplayDuringRelayout for two window that have FLAG_TURN_SCREEN_ON
        // from the same appWindowToken. Only one should trigger the wakeup.
        appWindowToken.setCanTurnScreenOn(true);
        first.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        reset(mPowerManagerWrapper);
        first.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(mPowerManagerWrapper).wakeUp(anyLong(), anyString());
        assertFalse(appWindowToken.canTurnScreenOn());

        reset(mPowerManagerWrapper);
        second.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(mPowerManagerWrapper, never()).wakeUp(anyLong(), anyString());
        assertFalse(appWindowToken.canTurnScreenOn());

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

        reset(mPowerManagerWrapper);
        firstWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(mPowerManagerWrapper).wakeUp(anyLong(), anyString());

        reset(mPowerManagerWrapper);
        secondWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(mPowerManagerWrapper).wakeUp(anyLong(), anyString());
    }

    @Test
    public void testCanAffectSystemUiFlags() throws Exception {
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
    public void testCanAffectSystemUiFlags_disallow() throws Exception {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mToken.setHidden(false);
        assertTrue(app.canAffectSystemUiFlags());
        app.getTask().setCanAffectSystemUiFlags(false);
        assertFalse(app.canAffectSystemUiFlags());
    }

    @Test
    public void testIsSelfOrAncestorWindowAnimating() throws Exception {
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
    public void testLayoutSeqResetOnReparent() throws Exception {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mLayoutSeq = 1;
        mDisplayContent.mLayoutSeq = 1;

        app.onDisplayChanged(mDisplayContent);

        assertThat(app.mLayoutSeq, not(is(mDisplayContent.mLayoutSeq)));
    }

    @Test
    public void testDisplayIdUpdatedOnReparent() throws Exception {
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
        app.mWinAnimator.mSurfaceController = mock(WindowSurfaceController.class);
        try {
            app.mFrame.set(10, 20, 60, 80);

            app.seamlesslyRotate(t, ROTATION_0, ROTATION_90);

            assertTrue(app.mSeamlesslyRotated);
            assertEquals(new Rect(20, mDisplayInfo.logicalWidth - 60,
                    80, mDisplayInfo.logicalWidth - 10), app.mFrame);

            verify(t).setPosition(app.mSurfaceControl, app.mFrame.left, app.mFrame.top);
            verify(app.mWinAnimator.mSurfaceController).setPosition(t, 0, 50, false);
            verify(app.mWinAnimator.mSurfaceController).setMatrix(t, 0, -1, 1, 0, false);
        } finally {
            app.mSurfaceControl = null;
            app.mHasSurface = false;
        }
    }

    private void testPrepareWindowToDisplayDuringRelayout(boolean wasVisible) {
        reset(mPowerManagerWrapper);
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        root.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        root.prepareWindowToDisplayDuringRelayout(wasVisible /*wasVisible*/);
        verify(mPowerManagerWrapper).wakeUp(anyLong(), anyString());
    }
}
