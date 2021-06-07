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
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.DisplayContent.IME_TARGET_CONTROL;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.WindowContainer.SYNC_STATE_WAITING_FOR_DRAW;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.Gravity;
import android.view.InputWindowHandle;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowStateTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowStateTests extends WindowTestsBase {

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
        verify(overlayWindow).hide(true /* doAnimation */, true /* requestAnim */);
        overlayWindow.setHiddenWhileSuspended(false);
        verify(overlayWindow).show(true /* doAnimation */, true /* requestAnim */);
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
        window.hide(false /* doAnimation */, false /* requestAnim */);
        assertFalse(window.isOnScreen());
    }

    @Test
    public void testCanBeImeTarget() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        final WindowState imeWindow = createWindow(null, TYPE_INPUT_METHOD, "imeWindow");

        // Setting FLAG_NOT_FOCUSABLE prevents the window from being an IME target.
        appWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        imeWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;

        // Make windows visible
        appWindow.setHasSurface(true);
        imeWindow.setHasSurface(true);

        // Windows with FLAG_NOT_FOCUSABLE can't be IME targets
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Add IME target flags
        appWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);
        imeWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);

        // Visible app window with flags can be IME target while an IME window can never be an IME
        // target regardless of its visibility or flags.
        assertTrue(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Verify PINNED windows can't be IME target.
        int initialMode = appWindow.mActivityRecord.getWindowingMode();
        appWindow.mActivityRecord.setWindowingMode(WINDOWING_MODE_PINNED);
        assertFalse(appWindow.canBeImeTarget());
        appWindow.mActivityRecord.setWindowingMode(initialMode);

        // Make windows invisible
        appWindow.hide(false /* doAnimation */, false /* requestAnim */);
        imeWindow.hide(false /* doAnimation */, false /* requestAnim */);

        // Invisible window can't be IME targets even if they have the right flags.
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Simulate the window is in split screen primary root task and the current state is
        // minimized and home root task is resizable, so that we should ignore input for the
        // root task.
        final DockedTaskDividerController controller =
                mDisplayContent.getDockedDividerController();
        final Task rootTask = createTask(mDisplayContent,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD);
        spyOn(appWindow);
        spyOn(controller);
        spyOn(rootTask);
        rootTask.setFocusable(false);
        doReturn(rootTask).when(appWindow).getRootTask();

        // Make sure canBeImeTarget is false due to shouldIgnoreInput is true;
        assertFalse(appWindow.canBeImeTarget());
        assertTrue(rootTask.shouldIgnoreInput());
    }

    @Test
    public void testCanWindowWithEmbeddedDisplayBeImeTarget() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        final WindowState imeWindow = createWindow(null, TYPE_INPUT_METHOD, "imeWindow");

        imeWindow.setHasSurface(true);
        appWindow.setHasSurface(true);

        appWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        assertFalse(appWindow.canBeImeTarget());

        DisplayContent secondDisplay = createNewDisplay();
        final WindowState embeddedWindow = createWindow(null, TYPE_APPLICATION, secondDisplay,
                "embeddedWindow");
        appWindow.addEmbeddedDisplayContent(secondDisplay);
        embeddedWindow.setHasSurface(true);
        embeddedWindow.mAttrs.flags &= ~FLAG_NOT_FOCUSABLE;
        assertTrue(appWindow.canBeImeTarget());
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
        // calling setCurrentLaunchCanTurnScreenOn for windows with flag in the same activity.
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final WindowState first = createWindow(null, TYPE_APPLICATION, activity, "first");
        final WindowState second = createWindow(null, TYPE_APPLICATION, activity, "second");

        testPrepareWindowToDisplayDuringRelayout(first, false /* expectedWakeupCalled */,
                true /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                true /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for two windows from the same activity, one of
        // which has FLAG_TURN_SCREEN_ON. The first processed one should trigger the wakeup.
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for two window that have FLAG_TURN_SCREEN_ON
        // from the same activity. Only one should trigger the wakeup.
        activity.setCurrentLaunchCanTurnScreenOn(true);
        first.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        second.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;

        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);
        testPrepareWindowToDisplayDuringRelayout(second, false /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Without window flags, the state of ActivityRecord.canTurnScreenOn should still be able to
        // turn on the screen.
        activity.setCurrentLaunchCanTurnScreenOn(true);
        first.mAttrs.flags &= ~WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        doReturn(true).when(activity).canTurnScreenOn();

        testPrepareWindowToDisplayDuringRelayout(first, true /* expectedWakeupCalled */,
                false /* expectedCurrentLaunchCanTurnScreenOn */);

        // Call prepareWindowToDisplayDuringRelayout for a windows that are not children of an
        // activity. Both windows have the FLAG_TURNS_SCREEN_ON so both should call wakeup
        final WindowToken windowToken = createTestWindowToken(FIRST_SUB_WINDOW, mDisplayContent);
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
        assertThat(appWindow.mActivityRecord.currentLaunchCanTurnScreenOn(),
                is(expectedCurrentLaunchCanTurnScreenOn));
    }

    @Test
    public void testCanAffectSystemUiFlags() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mActivityRecord.setVisible(true);
        assertTrue(app.canAffectSystemUiFlags());
        app.mActivityRecord.setVisible(false);
        assertFalse(app.canAffectSystemUiFlags());
        app.mActivityRecord.setVisible(true);
        app.mAttrs.alpha = 0.0f;
        assertFalse(app.canAffectSystemUiFlags());
    }

    @Test
    public void testCanAffectSystemUiFlags_disallow() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mActivityRecord.setVisible(true);
        assertTrue(app.canAffectSystemUiFlags());
        app.getTask().setCanAffectSystemUiFlags(false);
        assertFalse(app.canAffectSystemUiFlags());
    }

    @UseTestDisplay(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @Test
    public void testVisibleWithInsetsProvider() {
        final WindowState statusBar = mStatusBarWindow;
        final WindowState app = mAppWindow;
        statusBar.mHasSurface = true;
        assertTrue(statusBar.isVisible());
        mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_STATUS_BAR)
                .setWindow(statusBar, null /* frameProvider */, null /* imeFrameProvider */);
        mDisplayContent.getInsetsStateController().onBarControlTargetChanged(
                app, null /* fakeTopControlling */, app, null /* fakeNavControlling */);
        final InsetsState state = new InsetsState();
        state.getSource(ITYPE_STATUS_BAR).setVisible(false);
        app.updateRequestedVisibility(state);
        mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_STATUS_BAR)
                .updateClientVisibility(app);
        waitUntilHandlersIdle();
        assertFalse(statusBar.isVisible());
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
    public void testDeferredRemovalByAnimating() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        makeWindowVisible(appWindow);
        spyOn(appWindow.mWinAnimator);
        doReturn(true).when(appWindow.mWinAnimator).getShown();
        final AnimationAdapter animation = mock(AnimationAdapter.class);
        final ActivityRecord activity = appWindow.mActivityRecord;
        activity.startAnimation(appWindow.getPendingTransaction(),
                animation, false /* hidden */, SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION);

        appWindow.removeIfPossible();
        assertTrue(appWindow.mAnimatingExit);
        assertFalse(appWindow.mRemoved);

        activity.cancelAnimation();
        assertFalse(appWindow.mAnimatingExit);
        assertTrue(appWindow.mRemoved);
    }

    @Test
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
        app.mInputWindowHandle.setDisplayId(mDisplayContent.getDisplayId() + 1);
        app.onDisplayChanged(mDisplayContent);

        assertThat(app.mInputWindowHandle.getDisplayId(), is(mDisplayContent.getDisplayId()));
        assertThat(app.getDisplayId(), is(mDisplayContent.getDisplayId()));
    }

    @Test
    public void testSeamlesslyRotateWindow() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final SurfaceControl.Transaction t = spy(StubTransaction.class);

        makeWindowVisible(app);
        app.mSurfaceControl = mock(SurfaceControl.class);
        final Rect frame = app.getFrame();
        frame.set(10, 20, 60, 80);
        app.updateSurfacePosition(t);
        assertTrue(app.mLastSurfacePosition.equals(frame.left, frame.top));
        app.seamlesslyRotateIfAllowed(t, ROTATION_0, ROTATION_90, true /* requested */);
        assertTrue(app.mSeamlesslyRotated);

        // Verify we un-rotate the window state surface.
        final Matrix matrix = new Matrix();
        // Un-rotate 90 deg.
        matrix.setRotate(270);
        // Translate it back to origin.
        matrix.postTranslate(0, mDisplayInfo.logicalWidth);
        verify(t).setMatrix(eq(app.mSurfaceControl), eq(matrix), any(float[].class));

        // Verify we update the position as well.
        final float[] curSurfacePos = {app.mLastSurfacePosition.x, app.mLastSurfacePosition.y};
        matrix.mapPoints(curSurfacePos);
        verify(t).setPosition(eq(app.mSurfaceControl), eq(curSurfacePos[0]), eq(curSurfacePos[1]));

        app.finishSeamlessRotation(t);
        assertFalse(app.mSeamlesslyRotated);
        assertNull(app.mPendingSeamlessRotate);

        // Simulate the case with deferred layout and animation.
        app.resetSurfacePositionForAnimationLeash(t);
        clearInvocations(t);
        mWm.mWindowPlacerLocked.deferLayout();
        app.updateSurfacePosition(t);
        // Because layout is deferred, the position should keep the reset value.
        assertTrue(app.mLastSurfacePosition.equals(0, 0));

        app.seamlesslyRotateIfAllowed(t, ROTATION_0, ROTATION_270, true /* requested */);
        // The last position must be updated so the surface can be unrotated properly.
        assertTrue(app.mLastSurfacePosition.equals(frame.left, frame.top));
        matrix.setRotate(90);
        matrix.postTranslate(mDisplayInfo.logicalHeight, 0);
        curSurfacePos[0] = frame.left;
        curSurfacePos[1] = frame.top;
        matrix.mapPoints(curSurfacePos);
        verify(t).setPosition(eq(app.mSurfaceControl), eq(curSurfacePos[0]), eq(curSurfacePos[1]));
    }

    @Test
    public void testVisibilityChangeSwitchUser() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, "app");
        window.mHasSurface = true;
        spyOn(window);
        doReturn(false).when(window).showForAllUsers();

        mWm.mCurrentUserId = 1;
        window.switchUser(mWm.mCurrentUserId);
        assertFalse(window.isVisible());
        assertFalse(window.isVisibleByPolicy());

        mWm.mCurrentUserId = 0;
        window.switchUser(mWm.mCurrentUserId);
        assertTrue(window.isVisible());
        assertTrue(window.isVisibleByPolicy());
    }

    @Test
    public void testCompatOverrideScale() {
        final float overrideScale = 2; // 0.5x on client side.
        final CompatModePackages cmp = mWm.mAtmService.mCompatModePackages;
        spyOn(cmp);
        doReturn(overrideScale).when(cmp).getCompatScale(anyString(), anyInt());
        final WindowState w = createWindow(null, TYPE_APPLICATION_OVERLAY, "win");
        final WindowState child = createWindow(w, TYPE_APPLICATION_PANEL, "child");

        assertTrue(w.hasCompatScale());
        assertFalse(child.hasCompatScale());

        makeWindowVisible(w, child);
        w.setRequestedSize(100, 200);
        child.setRequestedSize(50, 100);
        child.mAttrs.width = child.mAttrs.height = 0;
        w.mAttrs.x = w.mAttrs.y = 100;
        w.mAttrs.width = w.mAttrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        w.mAttrs.gravity = Gravity.TOP | Gravity.LEFT;
        child.mAttrs.gravity = Gravity.CENTER;
        DisplayContentTests.performLayout(mDisplayContent);

        // Frame on screen = 200x400 (200, 200 - 400, 600). Compat frame on client = 100x200.
        final Rect unscaledCompatFrame = new Rect(w.getWindowFrames().mCompatFrame);
        unscaledCompatFrame.scale(overrideScale);
        final Rect parentFrame = w.getFrame();
        assertEquals(w.getWindowFrames().mFrame, unscaledCompatFrame);

        final Rect childFrame = child.getFrame();
        assertEquals(childFrame, child.getWindowFrames().mCompatFrame);
        // Child frame = 50x100 (225, 250 - 275, 350) according to Gravity.CENTER.
        final int childX = parentFrame.left + child.mRequestedWidth / 2;
        final int childY = parentFrame.top + child.mRequestedHeight / 2;
        final Rect expectedChildFrame = new Rect(childX, childY, childX + child.mRequestedWidth,
                childY + child.mRequestedHeight);
        assertEquals(expectedChildFrame, childFrame);

        // Surface should apply the scale.
        w.prepareSurfaces();
        verify(w.getPendingTransaction()).setMatrix(w.getSurfaceControl(),
                overrideScale, 0, 0, overrideScale);
        // Child surface inherits parent's scale, so it doesn't need to scale.
        verify(child.getPendingTransaction(), never()).setMatrix(any(), anyInt(), anyInt(),
                anyInt(), anyInt());

        // According to "dp * density / 160 = px", density is scaled and the size in dp is the same.
        final CompatibilityInfo compatInfo = cmp.compatibilityInfoForPackageLocked(
                mContext.getApplicationInfo());
        final Configuration winConfig = w.getConfiguration();
        final Configuration clientConfig = new Configuration(w.getConfiguration());
        compatInfo.applyToConfiguration(clientConfig.densityDpi, clientConfig);

        assertEquals(winConfig.screenWidthDp, clientConfig.screenWidthDp);
        assertEquals(winConfig.screenHeightDp, clientConfig.screenHeightDp);
        assertEquals(winConfig.smallestScreenWidthDp, clientConfig.smallestScreenWidthDp);
        assertEquals(winConfig.densityDpi, (int) (clientConfig.densityDpi * overrideScale));

        final Rect unscaledClientBounds = new Rect(clientConfig.windowConfiguration.getBounds());
        unscaledClientBounds.scale(overrideScale);
        assertEquals(w.getWindowConfiguration().getBounds(), unscaledClientBounds);
    }

    @UseTestDisplay(addWindows = { W_ABOVE_ACTIVITY, W_NOTIFICATION_SHADE })
    @Test
    public void testRequestDrawIfNeeded() {
        final WindowState startingApp = createWindow(null /* parent */,
                TYPE_BASE_APPLICATION, "startingApp");
        final WindowState startingWindow = createWindow(null /* parent */,
                TYPE_APPLICATION_STARTING, startingApp.mToken, "starting");
        startingApp.mActivityRecord.mStartingWindow = startingWindow;
        final WindowState keyguardHostWindow = mNotificationShadeWindow;
        final WindowState allDrawnApp = mAppWindow;
        allDrawnApp.mActivityRecord.allDrawn = true;

        // The waiting list is used to ensure the content is ready when turning on screen.
        final List<WindowState> outWaitingForDrawn = mDisplayContent.mWaitingForDrawn;
        final List<WindowState> visibleWindows = Arrays.asList(mChildAppWindowAbove,
                keyguardHostWindow, allDrawnApp, startingApp, startingWindow);
        visibleWindows.forEach(w -> {
            w.mHasSurface = true;
            w.requestDrawIfNeeded(outWaitingForDrawn);
        });

        // Keyguard host window should be always contained. The drawn app or app with starting
        // window are unnecessary to draw.
        assertEquals(Arrays.asList(keyguardHostWindow, startingWindow), outWaitingForDrawn);
    }

    @UseTestDisplay(addWindows = W_ABOVE_ACTIVITY)
    @Test
    public void testReportResizedWithRemoteException() {
        final WindowState win = mChildAppWindowAbove;
        makeWindowVisible(win, win.getParentWindow());
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        win.updateResizingWindowIfNeeded();

        assertThat(mWm.mResizingWindows).contains(win);
        assertTrue(win.getOrientationChanging());

        mWm.mResizingWindows.remove(win);
        spyOn(win.mClient);
        try {
            doThrow(new RemoteException("test")).when(win.mClient).resized(any() /* frames */,
                    anyBoolean() /* reportDraw */, any() /* mergedConfig */,
                    anyBoolean() /* forceLayout */, anyBoolean() /* alwaysConsumeSystemBars */,
                    anyInt() /* displayId */);
        } catch (RemoteException ignored) {
        }
        win.reportResized();
        win.updateResizingWindowIfNeeded();

        // Even "resized" throws remote exception, it is still considered as reported. So the window
        // shouldn't be resized again (which may block unfreeze in real case).
        assertThat(mWm.mResizingWindows).doesNotContain(win);
        assertFalse(win.getOrientationChanging());
    }

    @UseTestDisplay(addWindows = W_ABOVE_ACTIVITY)
    @Test
    public void testRequestResizeForBlastSync() {
        final WindowState win = mChildAppWindowAbove;
        makeWindowVisible(win, win.getParentWindow());
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        win.reportResized();
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).doesNotContain(win);

        // Check that the window is in resizing if using blast sync.
        win.reportResized();
        win.prepareSync();
        assertEquals(SYNC_STATE_WAITING_FOR_DRAW, win.mSyncState);
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).contains(win);

        // Don't re-add the window again if it's been reported to the client and still waiting on
        // the client draw for blast sync.
        win.reportResized();
        mWm.mResizingWindows.remove(win);
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).doesNotContain(win);
    }

    @Test
    public void testCantReceiveTouchDuringRecentsAnimation() {
        final WindowState win0 = createWindow(null, TYPE_APPLICATION, "win0");

        // Mock active recents animation
        RecentsAnimationController recentsController = mock(RecentsAnimationController.class);
        when(recentsController.shouldApplyInputConsumer(win0.mActivityRecord)).thenReturn(true);
        mWm.setRecentsAnimationController(recentsController);
        assertFalse(win0.canReceiveTouchInput());
    }

    @Test
    public void testCantReceiveTouchWhenAppTokenHiddenRequested() {
        final WindowState win0 = createWindow(null, TYPE_APPLICATION, "win0");
        win0.mActivityRecord.mVisibleRequested = false;
        assertFalse(win0.canReceiveTouchInput());
    }

    @Test
    public void testCantReceiveTouchWhenNotFocusable() {
        final WindowState win0 = createWindow(null, TYPE_APPLICATION, "win0");
        win0.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        win0.mActivityRecord.getRootTask().setFocusable(false);
        assertFalse(win0.canReceiveTouchInput());
    }

    @Test
    public void testUpdateInputWindowHandle() {
        final WindowState win = createWindow(null, TYPE_APPLICATION, "win");
        win.mAttrs.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        final InputWindowHandle handle = new InputWindowHandle(
                win.mInputWindowHandle.getInputApplicationHandle(), win.getDisplayId());
        final InputWindowHandleWrapper handleWrapper = new InputWindowHandleWrapper(handle);
        final IBinder inputChannelToken = mock(IBinder.class);
        win.mInputChannelToken = inputChannelToken;

        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);

        assertTrue(handleWrapper.isChanged());
        // The window of standard resizable task should not use surface crop as touchable region.
        assertFalse(handle.replaceTouchableRegionWithCrop);
        assertEquals(inputChannelToken, handle.token);
        assertEquals(win.mActivityRecord.getInputApplicationHandle(false /* update */),
                handle.inputApplicationHandle);
        assertEquals(win.mAttrs.inputFeatures, handle.inputFeatures);
        assertEquals(win.isVisible(), handle.visible);

        final SurfaceControl sc = mock(SurfaceControl.class);
        final SurfaceControl.Transaction transaction = mSystemServicesTestRule.mTransaction;
        InputMonitor.setInputWindowInfoIfNeeded(transaction, sc, handleWrapper);

        // The fields of input window handle are changed, so it must set input window info
        // successfully. And then the changed flag should be reset.
        verify(transaction).setInputWindowInfo(eq(sc), eq(handle));
        assertFalse(handleWrapper.isChanged());
        // Populate the same states again, the handle should not detect change.
        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);
        assertFalse(handleWrapper.isChanged());

        // Apply the no change handle, the invocation of setInputWindowInfo should be skipped.
        clearInvocations(transaction);
        InputMonitor.setInputWindowInfoIfNeeded(transaction, sc, handleWrapper);
        verify(transaction, never()).setInputWindowInfo(any(), any());

        // The rotated bounds have higher priority as the touchable region.
        final Rect rotatedBounds = new Rect(0, 0, 123, 456);
        doReturn(rotatedBounds).when(win.mToken).getFixedRotationTransformDisplayBounds();
        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);
        assertEquals(rotatedBounds, handle.touchableRegion.getBounds());

        // Populate as an overlay to disable the input of window.
        InputMonitor.populateOverlayInputInfo(handleWrapper, false /* isVisible */);
        // The overlay attributes should be set.
        assertTrue(handleWrapper.isChanged());
        assertFalse(handle.focusable);
        assertFalse(handle.visible);
        assertNull(handle.token);
        assertEquals(0L, handle.dispatchingTimeoutMillis);
        assertEquals(WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL,
                handle.inputFeatures);
    }

    @Test
    public void testHasActiveVisibleWindow() {
        final int uid = ActivityBuilder.DEFAULT_FAKE_UID;
        mAtm.mActiveUids.onUidActive(uid, 0 /* any proc state */);

        final WindowState app = createWindow(null, TYPE_APPLICATION, "app", uid);
        app.mActivityRecord.setVisible(false);
        app.mActivityRecord.setVisibility(false /* visible */, false /* deferHidingClient */);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        app.mActivityRecord.setVisibility(true /* visible */, false /* deferHidingClient */);
        assertTrue(mAtm.hasActiveVisibleWindow(uid));

        // Make the activity invisible and add a visible toast. The uid should have no active
        // visible window because toast can be misused by legacy app to bypass background check.
        app.mActivityRecord.setVisibility(false /* visible */, false /* deferHidingClient */);
        final WindowState overlay = createWindow(null, TYPE_APPLICATION_OVERLAY, "overlay", uid);
        final WindowState toast = createWindow(null, TYPE_TOAST, app.mToken, "toast", uid);
        toast.onSurfaceShownChanged(true);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        // Though starting window should belong to system. Make sure it is ignored to avoid being
        // allow-list unexpectedly, see b/129563343.
        final WindowState starting =
                createWindow(null, TYPE_APPLICATION_STARTING, app.mToken, "starting", uid);
        starting.onSurfaceShownChanged(true);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        // Make the application overlay window visible. It should be a valid active visible window.
        overlay.onSurfaceShownChanged(true);
        assertTrue(mAtm.hasActiveVisibleWindow(uid));
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testNeedsRelativeLayeringToIme_notAttached() {
        WindowState sameTokenWindow = createWindow(null, TYPE_BASE_APPLICATION, mAppWindow.mToken,
                "SameTokenWindow");
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        sameTokenWindow.mActivityRecord.getRootTask().setWindowingMode(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertTrue(sameTokenWindow.needsRelativeLayeringToIme());
        sameTokenWindow.removeImmediately();
        assertFalse(sameTokenWindow.needsRelativeLayeringToIme());
    }

    @UseTestDisplay(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testNeedsRelativeLayeringToIme_startingWindow() {
        WindowState sameTokenWindow = createWindow(null, TYPE_APPLICATION_STARTING,
                mAppWindow.mToken, "SameTokenWindow");
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        sameTokenWindow.mActivityRecord.getRootTask().setWindowingMode(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertFalse(sameTokenWindow.needsRelativeLayeringToIme());
    }

    @Test
    public void testSetFreezeInsetsState() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        spyOn(app);
        doReturn(true).when(app).isVisible();

        // Set freezing the insets state to make the window ignore to dispatch insets changed.
        final InsetsState expectedState = new InsetsState(app.getInsetsState(),
                true /* copySources */);
        app.freezeInsetsState();
        assertEquals(expectedState, app.getFrozenInsetsState());
        assertFalse(app.isReadyToDispatchInsetsState());
        assertEquals(expectedState, app.getInsetsState());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        verify(app, never()).notifyInsetsChanged();

        // Unfreeze the insets state to make the window can dispatch insets changed.
        app.clearFrozenInsetsState();
        assertTrue(app.isReadyToDispatchInsetsState());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        verify(app).notifyInsetsChanged();
    }

    @UseTestDisplay(addWindows = { W_INPUT_METHOD, W_ACTIVITY })
    @Test
    public void testImeAlwaysReceivesVisibleNavigationBarInsets() {
        final InsetsSource navSource = new InsetsSource(ITYPE_NAVIGATION_BAR);
        mImeWindow.mAboveInsetsState.addSource(navSource);
        mAppWindow.mAboveInsetsState.addSource(navSource);

        navSource.setVisible(false);
        assertTrue(mImeWindow.getInsetsState().getSourceOrDefaultVisibility(ITYPE_NAVIGATION_BAR));
        assertFalse(mAppWindow.getInsetsState().getSourceOrDefaultVisibility(ITYPE_NAVIGATION_BAR));

        navSource.setVisible(true);
        assertTrue(mImeWindow.getInsetsState().getSourceOrDefaultVisibility(ITYPE_NAVIGATION_BAR));
        assertTrue(mAppWindow.getInsetsState().getSourceOrDefaultVisibility(ITYPE_NAVIGATION_BAR));
    }

    @UseTestDisplay(addWindows = { W_ACTIVITY })
    @Test
    public void testUpdateImeControlTargetWhenLeavingMultiWindow() {
        WindowState app = createWindow(null, TYPE_BASE_APPLICATION,
                mAppWindow.mToken, "app");
        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());

        spyOn(app);
        mDisplayContent.setImeInputTarget(mAppWindow);
        mDisplayContent.setImeLayeringTarget(mAppWindow);

        // Simulate entering multi-window mode and verify if the IME control target is remote.
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, app.getWindowingMode());
        assertEquals(mDisplayContent.mRemoteInsetsControlTarget,
                mDisplayContent.computeImeControlTarget());

        // Simulate exiting multi-window mode and verify if the IME control target changed
        // to the app window.
        spyOn(app.getDisplayContent());
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        verify(app.getDisplayContent()).updateImeControlTarget();
        assertEquals(mAppWindow, mDisplayContent.getImeTarget(IME_TARGET_CONTROL).getWindow());
    }

    @UseTestDisplay(addWindows = { W_ACTIVITY, W_INPUT_METHOD, W_NOTIFICATION_SHADE })
    @Test
    public void testNotificationShadeHasImeInsetsWhenSplitscreenActivated() {
        WindowState app = createWindow(null, TYPE_BASE_APPLICATION,
                mAppWindow.mToken, "app");

        // Simulate entering multi-window mode and verify if the split-screen is activated.
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, app.getWindowingMode());
        assertTrue(mDisplayContent.getDefaultTaskDisplayArea().isSplitScreenModeActivated());

        // Simulate notificationShade is shown and being IME layering target.
        mNotificationShadeWindow.setHasSurface(true);
        mNotificationShadeWindow.mAttrs.flags &= ~FLAG_NOT_FOCUSABLE;
        assertTrue(mNotificationShadeWindow.canBeImeTarget());
        mDisplayContent.getInsetsStateController().getSourceProvider(ITYPE_IME).setWindow(
                mImeWindow, null, null);

        mDisplayContent.computeImeTarget(true);
        assertEquals(mNotificationShadeWindow, mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
        mDisplayContent.getInsetsStateController().getRawInsetsState()
                .setSourceVisible(ITYPE_IME, true);

        // Verify notificationShade can still get IME insets even the split-screen is activated.
        InsetsState state = mDisplayContent.getInsetsStateController().getInsetsForWindow(
                mNotificationShadeWindow);
        assertNotNull(state.peekSource(ITYPE_IME));
        assertTrue(state.getSource(ITYPE_IME).isVisible());
    }
}
