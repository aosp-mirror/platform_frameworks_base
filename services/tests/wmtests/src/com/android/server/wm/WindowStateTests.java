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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.InsetsSource.ID_IME;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
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
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.notification.Flags.FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION;
import static com.android.server.wm.DisplayContent.IME_TARGET_CONTROL;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.WindowContainer.SYNC_STATE_WAITING_FOR_DRAW;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.when;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.InputConfig;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.util.ArraySet;
import android.util.MergedConfiguration;
import android.view.Gravity;
import android.view.IWindow;
import android.view.InputWindowHandle;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.window.ClientWindowFrames;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentOrganizer;

import androidx.test.filters.SmallTest;

import com.android.server.testutils.StubTransaction;
import com.android.server.wm.SensitiveContentPackages.PackageInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build/Install/Run:
 * atest WmTests:WindowStateTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowStateTests extends WindowTestsBase {

    @After
    public void tearDown() {
        mWm.mSensitiveContentPackages.clearBlockedApps();
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

        // Verifies that a window without animation can be hidden even if its parent is animating.
        window.show(false /* doAnimation */, false /* requestAnim */);
        assertTrue(window.isVisibleByPolicy());
        window.getParent().startAnimation(mTransaction, mock(AnimationAdapter.class),
                false /* hidden */, SurfaceAnimator.ANIMATION_TYPE_TOKEN_TRANSFORM);
        window.mAttrs.windowAnimations = 0;
        window.hide(true /* doAnimation */, true /* requestAnim */);
        assertFalse(window.isSelfAnimating(0, SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION));
        assertFalse(window.isVisibleByPolicy());
        assertFalse(window.isOnScreen());

        // Verifies that a window with animation can be hidden after the hide animation is finished.
        window.show(false /* doAnimation */, false /* requestAnim */);
        window.mAttrs.windowAnimations = android.R.style.Animation_Dialog;
        window.hide(true /* doAnimation */, true /* requestAnim */);
        assertTrue(window.isSelfAnimating(0, SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION));
        assertTrue(window.isVisibleByPolicy());
        window.cancelAnimation();
        assertFalse(window.isVisibleByPolicy());
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

        // Verify that app window can still be IME target as long as it is visible (even if
        // it is going to become invisible).
        appWindow.mActivityRecord.setVisibleRequested(false);
        assertTrue(appWindow.canBeImeTarget());

        // Make windows invisible
        appWindow.hide(false /* doAnimation */, false /* requestAnim */);
        imeWindow.hide(false /* doAnimation */, false /* requestAnim */);

        // Invisible window can't be IME targets even if they have the right flags.
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Simulate the window is in split screen root task.
        final Task rootTask = createTask(mDisplayContent,
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        spyOn(appWindow);
        spyOn(rootTask);
        rootTask.setFocusable(false);
        doReturn(rootTask).when(appWindow).getRootTask();

        // Make sure canBeImeTarget is false;
        assertFalse(appWindow.canBeImeTarget());
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

        final var powerManager = mWm.mPowerManager;
        clearInvocations(powerManager);
        firstWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(powerManager).wakeUp(anyLong(), anyInt(), anyString());

        clearInvocations(powerManager);
        secondWindow.prepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        verify(powerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    private void testPrepareWindowToDisplayDuringRelayout(WindowState appWindow,
            boolean expectedWakeupCalled, boolean expectedCurrentLaunchCanTurnScreenOn) {
        final var powerManager = mWm.mPowerManager;
        clearInvocations(powerManager);
        appWindow.prepareWindowToDisplayDuringRelayout(false /* wasVisible */);

        if (expectedWakeupCalled) {
            verify(powerManager).wakeUp(anyLong(), anyInt(), anyString());
        } else {
            verify(powerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
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
    public void testCanAffectSystemUiFlags_starting() {
        final WindowState app = createWindow(null, TYPE_APPLICATION_STARTING, "app");
        app.mActivityRecord.setVisible(true);
        app.mStartingData = new SnapshotStartingData(mWm, null, 0);
        assertFalse(app.canAffectSystemUiFlags());
        app.mStartingData = new SplashScreenStartingData(mWm, 0, 0);
        assertTrue(app.canAffectSystemUiFlags());
    }

    @Test
    public void testCanAffectSystemUiFlags_disallow() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mActivityRecord.setVisible(true);
        assertTrue(app.canAffectSystemUiFlags());
        app.getTask().setCanAffectSystemUiFlags(false);
        assertFalse(app.canAffectSystemUiFlags());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_STATUS_BAR })
    @Test
    public void testVisibleWithInsetsProvider() {
        final WindowState statusBar = mStatusBarWindow;
        final WindowState app = mAppWindow;
        statusBar.mHasSurface = true;
        assertTrue(statusBar.isVisible());
        final int statusBarId = InsetsSource.createId(null, 0, statusBars());
        mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(statusBarId, statusBars())
                .setWindowContainer(statusBar, null /* frameProvider */,
                        null /* imeFrameProvider */);
        mDisplayContent.getInsetsStateController().onBarControlTargetChanged(
                app, null /* fakeTopControlling */, app, null /* fakeNavControlling */);
        app.setRequestedVisibleTypes(0, statusBars());
        mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(statusBarId, statusBars())
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
    public void testOnExitAnimationDone() {
        final WindowState parent = createWindow(null, TYPE_APPLICATION, "parent");
        final WindowState child = createWindow(parent, TYPE_APPLICATION_PANEL, "child");
        final SurfaceControl.Transaction t = parent.getPendingTransaction();
        child.startAnimation(t, mock(AnimationAdapter.class), false /* hidden */,
                SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION);
        parent.mAnimatingExit = parent.mRemoveOnExit = parent.mWindowRemovalAllowed = true;
        child.mAnimatingExit = child.mRemoveOnExit = child.mWindowRemovalAllowed = true;
        final int[] numRemovals = new int[2];
        parent.registerWindowContainerListener(new WindowContainerListener() {
            @Override
            public void onRemoved() {
                numRemovals[0]++;
            }
        });
        child.registerWindowContainerListener(new WindowContainerListener() {
            @Override
            public void onRemoved() {
                numRemovals[1]++;
            }
        });
        spyOn(parent);
        // parent onExitAnimationDone
        //   -> child onExitAnimationDone() -> no-op because isAnimating()
        //   -> parent destroySurface()
        //     -> parent removeImmediately() because mDestroying+mRemoveOnExit
        //       -> child removeImmediately() -> cancelAnimation()
        //       -> child onExitAnimationDone()
        //         -> child destroySurface() because animation is canceled
        //           -> child removeImmediately() -> no-op because mRemoved
        parent.onExitAnimationDone();
        // There must be no additional destroySurface() of parent from its child.
        verify(parent, atMost(1)).destroySurface(anyBoolean(), anyBoolean());
        assertEquals(1, numRemovals[0]);
        assertEquals(1, numRemovals[1]);
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
    public void testApplyWithNextDraw() {
        final WindowState win = createWindow(null, TYPE_APPLICATION_OVERLAY, "app");
        final SurfaceControl.Transaction[] handledT = { null };
        // The normal case that the draw transaction is applied with finishing drawing.
        win.applyWithNextDraw(t -> handledT[0] = t);
        assertTrue(win.syncNextBuffer());
        final SurfaceControl.Transaction drawT = new StubTransaction();
        final SurfaceControl.Transaction currT = win.getSyncTransaction();
        clearInvocations(currT);
        win.mWinAnimator.mLastHidden = true;
        assertTrue(win.finishDrawing(drawT, Integer.MAX_VALUE));
        // The draw transaction should be merged to current transaction even if the state is hidden.
        verify(currT).merge(eq(drawT));
        assertEquals(drawT, handledT[0]);
        assertFalse(win.syncNextBuffer());

        // If the window is gone before reporting drawn, the sync state should be cleared.
        win.applyWithNextDraw(t -> handledT[0] = t);
        win.destroySurfaceUnchecked();
        assertFalse(win.syncNextBuffer());
        assertNotEquals(drawT, handledT[0]);
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
        assertTrue(child.hasCompatScale());

        makeWindowVisible(w, child);
        w.setRequestedSize(100, 200);
        child.setRequestedSize(50, 100);
        child.mAttrs.width = child.mAttrs.height = 0;
        w.mAttrs.x = w.mAttrs.y = 100;
        w.mAttrs.width = w.mAttrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        w.mAttrs.gravity = Gravity.TOP | Gravity.LEFT;
        child.mAttrs.gravity = Gravity.CENTER;
        DisplayContentTests.performLayout(mDisplayContent);
        final Rect parentFrame = w.getFrame();
        final Rect childFrame = child.getFrame();

        // Frame on screen = 200x400 (200, 200 - 400, 600). Compat frame on client = 100x200.
        final Rect unscaledCompatFrame = new Rect(w.getWindowFrames().mCompatFrame);
        unscaledCompatFrame.scale(overrideScale);
        assertEquals(parentFrame, unscaledCompatFrame);

        // Frame on screen = 100x200 (250, 300 - 350, 500). Compat frame on client = 50x100.
        unscaledCompatFrame.set(child.getWindowFrames().mCompatFrame);
        unscaledCompatFrame.scale(overrideScale);
        assertEquals(childFrame, unscaledCompatFrame);

        // The position of child is relative to parent. So the local coordinates should be scaled.
        final Point expectedChildPos = new Point(
                (int) ((childFrame.left - parentFrame.left) / overrideScale),
                (int) ((childFrame.top - parentFrame.top) / overrideScale));
        final Point childPos = new Point();
        child.transformFrameToSurfacePosition(childFrame.left, childFrame.top, childPos);
        assertEquals(expectedChildPos, childPos);

        // Surface should apply the scale.
        final SurfaceControl.Transaction t = w.getPendingTransaction();
        w.prepareSurfaces();
        verify(t).setMatrix(w.mSurfaceControl, overrideScale, 0, 0, overrideScale);
        // Child surface inherits parent's scale, so it doesn't need to scale.
        verify(t, never()).setMatrix(any(), anyInt(), anyInt(), anyInt(), anyInt());

        // According to "dp * density / 160 = px", density is scaled and the size in dp is the same.
        final Configuration winConfig = w.getConfiguration();
        final Configuration clientConfig = new Configuration(w.getConfiguration());
        CompatibilityInfo.scaleConfiguration(w.mInvGlobalScale, clientConfig);

        assertEquals(winConfig.screenWidthDp, clientConfig.screenWidthDp);
        assertEquals(winConfig.screenHeightDp, clientConfig.screenHeightDp);
        assertEquals(winConfig.smallestScreenWidthDp, clientConfig.smallestScreenWidthDp);
        assertEquals(winConfig.densityDpi, (int) (clientConfig.densityDpi * overrideScale));

        final Rect unscaledClientBounds = new Rect(clientConfig.windowConfiguration.getBounds());
        unscaledClientBounds.scale(overrideScale);
        assertEquals(w.getWindowConfiguration().getBounds(), unscaledClientBounds);

        // Child window without scale (e.g. different app) should apply inverse scale of parent.
        doReturn(1f).when(cmp).getCompatScale(anyString(), anyInt());
        final WindowState child2 = createWindow(w, TYPE_APPLICATION_SUB_PANEL, "child2");
        makeWindowVisible(w, child2);
        clearInvocations(t);
        child2.prepareSurfaces();
        verify(t).setMatrix(child2.mSurfaceControl, w.mInvGlobalScale, 0, 0, w.mInvGlobalScale);
    }

    @SetupWindows(addWindows = { W_ABOVE_ACTIVITY, W_NOTIFICATION_SHADE })
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

        // No need to wait for a window of invisible activity even if the window has surface.
        final WindowState invisibleApp = mAppWindow;
        invisibleApp.mActivityRecord.setVisibleRequested(false);
        invisibleApp.mActivityRecord.allDrawn = false;
        outWaitingForDrawn.clear();
        invisibleApp.requestDrawIfNeeded(outWaitingForDrawn);
        assertTrue(outWaitingForDrawn.isEmpty());

        // Drawn state should not be changed for insets change if the window is not visible.
        startingApp.mActivityRecord.setVisibleRequested(false);
        makeWindowVisibleAndDrawn(startingApp);
        startingApp.getConfiguration().orientation = 0; // Reset to be the same as last reported.
        startingApp.getWindowFrames().setInsetsChanged(true);
        startingApp.updateResizingWindowIfNeeded();
        assertTrue(mWm.mResizingWindows.contains(startingApp));
        assertTrue(startingApp.isDrawn());
        assertFalse(startingApp.getOrientationChanging());

        // Even if the display is frozen, invisible requested window should not be affected.
        mWm.startFreezingDisplay(0, 0, mDisplayContent);
        startingApp.getWindowFrames().setInsetsChanged(true);
        startingApp.updateResizingWindowIfNeeded();
        assertTrue(startingApp.isDrawn());
    }

    @SetupWindows(addWindows = W_ABOVE_ACTIVITY)
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
                    any() /* insetsState */, anyBoolean() /* forceLayout */,
                    anyBoolean() /* alwaysConsumeSystemBars */, anyInt() /* displayId */,
                    anyInt() /* seqId */, anyBoolean() /* dragResizing */);
        } catch (RemoteException ignored) {
        }
        win.reportResized();
        win.updateResizingWindowIfNeeded();

        // Even "resized" throws remote exception, it is still considered as reported. So the window
        // shouldn't be resized again (which may block unfreeze in real case).
        assertThat(mWm.mResizingWindows).doesNotContain(win);
        assertFalse(win.getOrientationChanging());
    }

    @SetupWindows(addWindows = W_ABOVE_ACTIVITY)
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
    public void testEmbeddedActivityResizing_clearAllDrawn() {
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        registerTaskFragmentOrganizer(
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder()));
        final Task task = createTask(mDisplayContent);
        final TaskFragment embeddedTf = createTaskFragmentWithEmbeddedActivity(task, organizer);
        final ActivityRecord embeddedActivity = embeddedTf.getTopMostActivity();
        final WindowState win = createWindow(null /* parent */, TYPE_APPLICATION, embeddedActivity,
                "App window");
        doReturn(true).when(embeddedActivity).isVisible();
        embeddedActivity.setVisibleRequested(true);
        makeWindowVisible(win);
        win.mLayoutSeq = win.getDisplayContent().mLayoutSeq;
        // Set the bounds twice:
        // 1. To make sure there is no orientation change after #reportResized, which can also cause
        // #clearAllDrawn.
        // 2. Make #isLastConfigReportedToClient to be false after #reportResized, so it can process
        // to check if we need redraw.
        embeddedTf.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        embeddedTf.setBounds(0, 0, 1000, 2000);
        win.reportResized();
        embeddedTf.setBounds(500, 0, 1000, 2000);

        // Clear all drawn when the window config of embedded TaskFragment is changed.
        win.updateResizingWindowIfNeeded();
        verify(embeddedActivity).clearAllDrawn();
    }

    @Test
    public void testCantReceiveTouchWhenAppTokenHiddenRequested() {
        final WindowState win0 = createWindow(null, TYPE_APPLICATION, "win0");
        win0.mActivityRecord.setVisibleRequested(false);
        assertFalse(win0.canReceiveTouchInput());
    }

    @Test
    public void testCantReceiveTouchWhenNotFocusable() {
        final WindowState win0 = createWindow(null, TYPE_APPLICATION, "win0");
        final Task rootTask = win0.mActivityRecord.getRootTask();
        spyOn(rootTask);
        when(rootTask.shouldIgnoreInput()).thenReturn(true);
        assertFalse(win0.canReceiveTouchInput());
    }

    private boolean testFlag(int flags, int test) {
        return (flags & test) == test;
    }

    @Test
    public void testUpdateInputWindowHandle() {
        final WindowState win = createWindow(null, TYPE_APPLICATION, "win");
        win.mAttrs.inputFeatures = WindowManager.LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY;
        win.mAttrs.flags = FLAG_WATCH_OUTSIDE_TOUCH | FLAG_SPLIT_TOUCH;
        final InputWindowHandle handle = new InputWindowHandle(
                win.mInputWindowHandle.getInputApplicationHandle(), win.getDisplayId());
        final InputWindowHandleWrapper handleWrapper = new InputWindowHandleWrapper(handle);
        final IBinder inputChannelToken = mock(IBinder.class);
        win.mInputChannelToken = inputChannelToken;

        mDisplayContent.getInputMonitor().populateInputWindowHandle(handleWrapper, win);

        assertTrue(handleWrapper.isChanged());
        assertTrue(testFlag(handle.inputConfig, InputConfig.WATCH_OUTSIDE_TOUCH));
        assertFalse(testFlag(handle.inputConfig, InputConfig.PREVENT_SPLITTING));
        assertTrue(testFlag(handle.inputConfig, InputConfig.DISABLE_USER_ACTIVITY));
        // The window of standard resizable task should not use surface crop as touchable region.
        assertFalse(handle.replaceTouchableRegionWithCrop);
        assertEquals(inputChannelToken, handle.token);
        assertEquals(win.mActivityRecord.getInputApplicationHandle(false /* update */),
                handle.inputApplicationHandle);

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
        InputMonitor.populateOverlayInputInfo(handleWrapper);
        // The overlay attributes should be set.
        assertTrue(handleWrapper.isChanged());
        assertFalse(handleWrapper.isFocusable());
        assertNull(handle.token);
        assertEquals(0L, handle.dispatchingTimeoutMillis);
        assertTrue(testFlag(handle.inputConfig, InputConfig.NO_INPUT_CHANNEL));
    }

    @Test
    public void testHasActiveVisibleWindow() {
        final int uid = ActivityBuilder.DEFAULT_FAKE_UID;

        final WindowState app = createWindow(null, TYPE_APPLICATION, "app", uid);
        app.mActivityRecord.setVisible(false);
        app.mActivityRecord.setVisibility(false);
        assertFalse(mAtm.hasActiveVisibleWindow(uid));

        app.mActivityRecord.setVisibility(true);
        assertTrue(mAtm.hasActiveVisibleWindow(uid));

        // Make the activity invisible and add a visible toast. The uid should have no active
        // visible window because toast can be misused by legacy app to bypass background check.
        app.mActivityRecord.setVisibility(false);
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

        // The number of windows should be independent of the existence of uid state.
        mAtm.mActiveUids.onUidInactive(uid);
        mAtm.mActiveUids.onUidActive(uid, 0 /* any proc state */);
        assertTrue(mAtm.mActiveUids.hasNonAppVisibleWindow(uid));
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testNeedsRelativeLayeringToIme_notAttached() {
        WindowState sameTokenWindow = createWindow(null, TYPE_BASE_APPLICATION, mAppWindow.mToken,
                "SameTokenWindow");
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        makeWindowVisible(mImeWindow);
        sameTokenWindow.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertTrue(sameTokenWindow.needsRelativeLayeringToIme());
        sameTokenWindow.removeImmediately();
        assertFalse(sameTokenWindow.needsRelativeLayeringToIme());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testNeedsRelativeLayeringToIme_startingWindow() {
        WindowState sameTokenWindow = createWindow(null, TYPE_APPLICATION_STARTING,
                mAppWindow.mToken, "SameTokenWindow");
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        makeWindowVisible(mImeWindow);
        sameTokenWindow.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertFalse(sameTokenWindow.needsRelativeLayeringToIme());
    }

    @UseTestDisplay(addWindows = {W_ACTIVITY, W_INPUT_METHOD})
    @Test
    public void testNeedsRelativeLayeringToIme_systemDialog() {
        WindowState systemDialogWindow = createWindow(null, TYPE_SECURE_SYSTEM_OVERLAY,
                mDisplayContent,
                "SystemDialog", true);
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        mAppWindow.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        makeWindowVisible(mImeWindow);
        systemDialogWindow.mAttrs.flags |= FLAG_ALT_FOCUSABLE_IM;
        assertTrue(systemDialogWindow.needsRelativeLayeringToIme());
    }

    @UseTestDisplay(addWindows = {W_INPUT_METHOD})
    @Test
    public void testNeedsRelativeLayeringToIme_notificationShadeShouldNotHideSystemDialog() {
        WindowState systemDialogWindow = createWindow(null, TYPE_SECURE_SYSTEM_OVERLAY,
                mDisplayContent,
                "SystemDialog", true);
        mDisplayContent.setImeLayeringTarget(systemDialogWindow);
        makeWindowVisible(mImeWindow);
        WindowState notificationShade = createWindow(null, TYPE_NOTIFICATION_SHADE,
                mDisplayContent, "NotificationShade", true);
        notificationShade.mAttrs.flags |= FLAG_ALT_FOCUSABLE_IM;
        assertFalse(notificationShade.needsRelativeLayeringToIme());
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

        // Verify that invisible non-activity window won't dispatch insets changed.
        final WindowState overlay = createWindow(null, TYPE_APPLICATION_OVERLAY, "overlay");
        makeWindowVisible(overlay);
        assertTrue(overlay.isReadyToDispatchInsetsState());
        overlay.mHasSurface = false;
        assertFalse(overlay.isReadyToDispatchInsetsState());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        assertFalse(overlay.getWindowFrames().hasInsetsChanged());
    }

    @SetupWindows(addWindows = { W_INPUT_METHOD, W_ACTIVITY })
    @Test
    public void testImeAlwaysReceivesVisibleNavigationBarInsets() {
        final int navId = InsetsSource.createId(null, 0, navigationBars());
        final InsetsSource navSource = new InsetsSource(navId, navigationBars());
        mImeWindow.mAboveInsetsState.addSource(navSource);
        mAppWindow.mAboveInsetsState.addSource(navSource);

        navSource.setVisible(false);
        assertTrue(mImeWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));
        assertFalse(mAppWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));

        navSource.setVisible(true);
        assertTrue(mImeWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));
        assertTrue(mAppWindow.getInsetsState().isSourceOrDefaultVisible(navId, navigationBars()));
    }

    @Test
    public void testAdjustImeInsetsVisibilityWhenSwitchingApps() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final WindowState app2 = createWindow(null, TYPE_APPLICATION, "app2");
        final WindowState imeWindow = createWindow(null, TYPE_APPLICATION, "imeWindow");
        spyOn(imeWindow);
        doReturn(true).when(imeWindow).isVisible();
        mDisplayContent.mInputMethodWindow = imeWindow;

        final InsetsStateController controller = mDisplayContent.getInsetsStateController();
        controller.getImeSourceProvider().setWindowContainer(imeWindow, null, null);

        // Simulate app requests IME with updating all windows Insets State when IME is above app.
        mDisplayContent.setImeLayeringTarget(app);
        mDisplayContent.setImeInputTarget(app);
        app.setRequestedVisibleTypes(ime(), ime());
        assertTrue(mDisplayContent.shouldImeAttachedToApp());
        controller.getImeSourceProvider().scheduleShowImePostLayout(app, null /* statsToken */);
        controller.getImeSourceProvider().getSource().setVisible(true);
        controller.updateAboveInsetsState(false);

        // Expect all app windows behind IME can receive IME insets visible.
        assertTrue(app.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertTrue(app2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));

        // Simulate app plays closing transition to app2.
        app.mActivityRecord.commitVisibility(false, false);
        assertTrue(app.mActivityRecord.mLastImeShown);
        assertTrue(app.mActivityRecord.mImeInsetsFrozenUntilStartInput);

        // Verify the IME insets is visible on app, but not for app2 during app task switching.
        assertTrue(app.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertFalse(app2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
    }

    @Test
    public void testAdjustImeInsetsVisibilityWhenSwitchingApps_toAppInMultiWindowMode() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        final WindowState app2 = createWindow(null, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD, TYPE_APPLICATION, mDisplayContent, "app2");
        final WindowState imeWindow = createWindow(null, TYPE_APPLICATION, "imeWindow");
        spyOn(imeWindow);
        doReturn(true).when(imeWindow).isVisible();
        mDisplayContent.mInputMethodWindow = imeWindow;

        final InsetsStateController controller = mDisplayContent.getInsetsStateController();
        controller.getImeSourceProvider().setWindowContainer(imeWindow, null, null);

        // Simulate app2 in multi-window mode is going to background to switch to the fullscreen
        // app which requests IME with updating all windows Insets State when IME is above app.
        app2.mActivityRecord.mImeInsetsFrozenUntilStartInput = true;
        mDisplayContent.setImeLayeringTarget(app);
        mDisplayContent.setImeInputTarget(app);
        app.setRequestedVisibleTypes(ime(), ime());
        assertTrue(mDisplayContent.shouldImeAttachedToApp());
        controller.getImeSourceProvider().scheduleShowImePostLayout(app, null /* statsToken */);
        controller.getImeSourceProvider().getSource().setVisible(true);
        controller.updateAboveInsetsState(false);

        // Expect app windows behind IME can receive IME insets visible,
        // but not for app2 in background.
        assertTrue(app.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertFalse(app2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));

        // Simulate app plays closing transition to app2.
        // And app2 is now IME layering target but not yet to be the IME input target.
        mDisplayContent.setImeLayeringTarget(app2);
        app.mActivityRecord.commitVisibility(false, false);
        assertTrue(app.mActivityRecord.mLastImeShown);
        assertTrue(app.mActivityRecord.mImeInsetsFrozenUntilStartInput);

        // Verify the IME insets is still visible on app, but not for app2 during task switching.
        assertTrue(app.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
        assertFalse(app2.getInsetsState().isSourceOrDefaultVisible(ID_IME, ime()));
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testUpdateImeControlTargetWhenLeavingMultiWindow() {
        WindowState app = createWindow(null, TYPE_BASE_APPLICATION,
                mAppWindow.mToken, "app");
        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());

        spyOn(app);
        mDisplayContent.setImeInputTarget(mAppWindow);
        mDisplayContent.setImeLayeringTarget(mAppWindow);

        // Simulate entering multi-window mode and verify if the IME control target is remote.
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, app.getWindowingMode());
        assertEquals(mDisplayContent.mRemoteInsetsControlTarget,
                mDisplayContent.computeImeControlTarget());

        // Simulate exiting multi-window mode and verify if the IME control target changed
        // to the app window.
        spyOn(app.getDisplayContent());
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_FULLSCREEN);

        // Expect updateImeParent will be invoked when the configuration of the IME control
        // target has changed.
        verify(app.getDisplayContent()).updateImeControlTarget(eq(true) /* updateImeParent */);
        assertEquals(mAppWindow, mDisplayContent.getImeTarget(IME_TARGET_CONTROL).getWindow());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD, W_NOTIFICATION_SHADE })
    @Test
    public void testNotificationShadeHasImeInsetsWhenMultiWindow() {
        WindowState app = createWindow(null, TYPE_BASE_APPLICATION,
                mAppWindow.mToken, "app");

        // Simulate entering multi-window mode and windowing mode is multi-window.
        app.mActivityRecord.getRootTask().setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        assertEquals(WINDOWING_MODE_MULTI_WINDOW, app.getWindowingMode());

        // Simulate notificationShade is shown and being IME layering target.
        mNotificationShadeWindow.setHasSurface(true);
        mNotificationShadeWindow.mAttrs.flags &= ~FLAG_NOT_FOCUSABLE;
        assertTrue(mNotificationShadeWindow.canBeImeTarget());
        mDisplayContent.getInsetsStateController().getOrCreateSourceProvider(ID_IME, ime())
                .setWindowContainer(mImeWindow, null, null);

        mDisplayContent.computeImeTarget(true);
        assertEquals(mNotificationShadeWindow, mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
        mDisplayContent.getInsetsStateController().getRawInsetsState()
                .setSourceVisible(ID_IME, true);

        // Verify notificationShade can still get IME insets even windowing mode is multi-window.
        InsetsState state = mNotificationShadeWindow.getInsetsState();
        assertNotNull(state.peekSource(ID_IME));
        assertTrue(state.isSourceOrDefaultVisible(ID_IME, ime()));
    }

    @Test
    public void testRequestedVisibility() {
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        app.mActivityRecord.setVisible(false);
        app.mActivityRecord.setVisibility(false);
        assertFalse(app.isVisibleRequested());

        // It doesn't have a surface yet, but should still be visible requested.
        app.setHasSurface(false);
        app.mActivityRecord.setVisibility(true);

        assertFalse(app.isVisible());
        assertTrue(app.isVisibleRequested());
    }

    @Test
    public void testKeepClearAreas() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, "window");
        makeWindowVisible(window);

        final Rect keepClearArea1 = new Rect(0, 0, 10, 10);
        final Rect keepClearArea2 = new Rect(5, 10, 15, 20);
        final List<Rect> keepClearAreas = Arrays.asList(keepClearArea1, keepClearArea2);
        window.setKeepClearAreas(keepClearAreas, Collections.emptyList());

        // Test that the keep-clear rects are stored and returned
        final List<Rect> windowKeepClearAreas = new ArrayList();
        window.getKeepClearAreas(windowKeepClearAreas, new ArrayList());
        assertEquals(new ArraySet(keepClearAreas), new ArraySet(windowKeepClearAreas));

        // Test that keep-clear rects are overwritten
        window.setKeepClearAreas(Collections.emptyList(), Collections.emptyList());
        windowKeepClearAreas.clear();
        window.getKeepClearAreas(windowKeepClearAreas, new ArrayList());
        assertEquals(0, windowKeepClearAreas.size());

        // Move the window position
        final SurfaceControl.Transaction t = spy(StubTransaction.class);
        window.mSurfaceControl = mock(SurfaceControl.class);
        final Rect frame = window.getFrame();
        frame.set(10, 20, 60, 80);
        window.updateSurfacePosition(t);
        assertEquals(new Point(frame.left, frame.top), window.mLastSurfacePosition);

        // Test that the returned keep-clear rects are translated to display space
        window.setKeepClearAreas(keepClearAreas, Collections.emptyList());
        Rect expectedArea1 = new Rect(keepClearArea1);
        expectedArea1.offset(frame.left, frame.top);
        Rect expectedArea2 = new Rect(keepClearArea2);
        expectedArea2.offset(frame.left, frame.top);

        windowKeepClearAreas.clear();
        window.getKeepClearAreas(windowKeepClearAreas, new ArrayList());
        assertEquals(new ArraySet(Arrays.asList(expectedArea1, expectedArea2)),
                     new ArraySet(windowKeepClearAreas));
    }

    @Test
    public void testUnrestrictedKeepClearAreas() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, "window");
        makeWindowVisible(window);

        final Rect keepClearArea1 = new Rect(0, 0, 10, 10);
        final Rect keepClearArea2 = new Rect(5, 10, 15, 20);
        final List<Rect> keepClearAreas = Arrays.asList(keepClearArea1, keepClearArea2);
        window.setKeepClearAreas(Collections.emptyList(), keepClearAreas);

        // Test that the keep-clear rects are stored and returned
        final List<Rect> restrictedKeepClearAreas = new ArrayList();
        final List<Rect> unrestrictedKeepClearAreas = new ArrayList();
        window.getKeepClearAreas(restrictedKeepClearAreas, unrestrictedKeepClearAreas);
        assertEquals(Collections.emptySet(), new ArraySet(restrictedKeepClearAreas));
        assertEquals(new ArraySet(keepClearAreas), new ArraySet(unrestrictedKeepClearAreas));

        // Test that keep-clear rects are overwritten
        window.setKeepClearAreas(Collections.emptyList(), Collections.emptyList());
        unrestrictedKeepClearAreas.clear();
        window.getKeepClearAreas(unrestrictedKeepClearAreas, new ArrayList());
        assertEquals(0, unrestrictedKeepClearAreas.size());

        // Move the window position
        final SurfaceControl.Transaction t = spy(StubTransaction.class);
        window.mSurfaceControl = mock(SurfaceControl.class);
        final Rect frame = window.getFrame();
        frame.set(10, 20, 60, 80);
        window.updateSurfacePosition(t);
        assertEquals(new Point(frame.left, frame.top), window.mLastSurfacePosition);

        // Test that the returned keep-clear rects are translated to display space
        window.setKeepClearAreas(Collections.emptyList(), keepClearAreas);
        Rect expectedArea1 = new Rect(keepClearArea1);
        expectedArea1.offset(frame.left, frame.top);
        Rect expectedArea2 = new Rect(keepClearArea2);
        expectedArea2.offset(frame.left, frame.top);

        unrestrictedKeepClearAreas.clear();
        window.getKeepClearAreas(restrictedKeepClearAreas, unrestrictedKeepClearAreas);
        assertEquals(Collections.emptySet(), new ArraySet(restrictedKeepClearAreas));
        assertEquals(new ArraySet(Arrays.asList(expectedArea1, expectedArea2)),
                     new ArraySet(unrestrictedKeepClearAreas));
    }

    @Test
    public void testImeTargetChangeListener_OnImeInputTargetVisibilityChanged() {
        final TestImeTargetChangeListener listener = new TestImeTargetChangeListener();
        mWm.mImeTargetChangeListener = listener;

        final WindowState imeTarget = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                createActivityRecord(mDisplayContent), "imeTarget");

        imeTarget.mActivityRecord.setVisibleRequested(true);
        makeWindowVisible(imeTarget);
        mDisplayContent.setImeInputTarget(imeTarget);
        waitHandlerIdle(mWm.mH);

        assertThat(listener.mImeTargetToken).isEqualTo(imeTarget.mClient.asBinder());
        assertThat(listener.mIsRemoved).isFalse();
        assertThat(listener.mIsVisibleForImeInputTarget).isTrue();

        imeTarget.mActivityRecord.setVisibleRequested(false);
        waitHandlerIdle(mWm.mH);

        assertThat(listener.mImeTargetToken).isEqualTo(imeTarget.mClient.asBinder());
        assertThat(listener.mIsRemoved).isFalse();
        assertThat(listener.mIsVisibleForImeInputTarget).isFalse();

        imeTarget.removeImmediately();
        assertThat(listener.mImeTargetToken).isEqualTo(imeTarget.mClient.asBinder());
        assertThat(listener.mIsRemoved).isTrue();
        assertThat(listener.mIsVisibleForImeInputTarget).isFalse();
    }

    @SetupWindows(addWindows = {W_INPUT_METHOD})
    @Test
    public void testImeTargetChangeListener_OnImeTargetOverlayVisibilityChanged() {
        final TestImeTargetChangeListener listener = new TestImeTargetChangeListener();
        mWm.mImeTargetChangeListener = listener;

        // Scenario 1: test addWindow/relayoutWindow to add Ime layering overlay window as visible.
        final WindowToken windowToken = createTestWindowToken(TYPE_APPLICATION_OVERLAY,
                mDisplayContent);
        final IWindow client = new TestIWindow();
        final Session session = getTestSession();
        final ClientWindowFrames outFrames = new ClientWindowFrames();
        final MergedConfiguration outConfig = new MergedConfiguration();
        final SurfaceControl outSurfaceControl = new SurfaceControl();
        final InsetsState outInsetsState = new InsetsState();
        final InsetsSourceControl.Array outControls = new InsetsSourceControl.Array();
        final Bundle outBundle = new Bundle();
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                TYPE_APPLICATION_OVERLAY);
        params.setTitle("imeLayeringTargetOverlay");
        params.token = windowToken.token;
        params.flags = FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM;

        mWm.addWindow(session, client, params, View.VISIBLE, DEFAULT_DISPLAY,
                0 /* userUd */, WindowInsets.Type.defaultVisible(), null, new InsetsState(),
                new InsetsSourceControl.Array(), new Rect(), new float[1]);
        mWm.relayoutWindow(session, client, params, 100, 200, View.VISIBLE, 0, 0, 0,
                outFrames, outConfig, outSurfaceControl, outInsetsState, outControls, outBundle);
        waitHandlerIdle(mWm.mH);

        final WindowState imeLayeringTargetOverlay = mDisplayContent.getWindow(
                w -> w.mClient.asBinder() == client.asBinder());
        assertThat(imeLayeringTargetOverlay.isVisible()).isTrue();
        assertThat(listener.mImeTargetToken).isEqualTo(client.asBinder());
        assertThat(listener.mIsRemoved).isFalse();
        assertThat(listener.mIsVisibleForImeTargetOverlay).isTrue();

        // Scenario 2: test relayoutWindow to let the Ime layering target overlay window invisible.
        mWm.relayoutWindow(session, client, params, 100, 200, View.GONE, 0, 0, 0,
                outFrames, outConfig, outSurfaceControl, outInsetsState, outControls, outBundle);
        waitHandlerIdle(mWm.mH);

        assertThat(imeLayeringTargetOverlay.isVisible()).isFalse();
        assertThat(listener.mImeTargetToken).isEqualTo(client.asBinder());
        assertThat(listener.mIsRemoved).isFalse();
        assertThat(listener.mIsVisibleForImeTargetOverlay).isFalse();

        // Scenario 3: test removeWindow to remove the Ime layering target overlay window.
        mWm.removeClientToken(session, client.asBinder());
        waitHandlerIdle(mWm.mH);

        assertThat(listener.mImeTargetToken).isEqualTo(client.asBinder());
        assertThat(listener.mIsRemoved).isTrue();
        assertThat(listener.mIsVisibleForImeTargetOverlay).isFalse();
    }

    @Test
    @RequiresFlagsEnabled(FLAG_SENSITIVE_NOTIFICATION_APP_PROTECTION)
    public void testIsSecureLocked_sensitiveContentProtectionManagerEnabled() {
        String testPackage = "test";
        int ownerId1 = 20;
        int ownerId2 = 21;
        final WindowState window1 = createWindow(null, TYPE_APPLICATION, "window1", ownerId1);
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, "window2", ownerId2);

        // Setting packagename for targeted feature
        window1.mAttrs.packageName = testPackage;
        window2.mAttrs.packageName = testPackage;

        PackageInfo blockedPackage = new PackageInfo(testPackage, ownerId1);
        ArraySet<PackageInfo> blockedPackages = new ArraySet();
        blockedPackages.add(blockedPackage);
        mWm.mSensitiveContentPackages.addBlockScreenCaptureForApps(blockedPackages);

        assertTrue(window1.isSecureLocked());
        assertFalse(window2.isSecureLocked());
    }

    private static class TestImeTargetChangeListener implements ImeTargetChangeListener {
        private IBinder mImeTargetToken;
        private boolean mIsRemoved;
        private boolean mIsVisibleForImeTargetOverlay;
        private boolean mIsVisibleForImeInputTarget;

        @Override
        public void onImeTargetOverlayVisibilityChanged(IBinder overlayWindowToken,
                @WindowManager.LayoutParams.WindowType int windowType, boolean visible,
                boolean removed) {
            mImeTargetToken = overlayWindowToken;
            mIsVisibleForImeTargetOverlay = visible;
            mIsRemoved = removed;
        }

        @Override
        public void onImeInputTargetVisibilityChanged(IBinder imeInputTarget,
                boolean visibleRequested, boolean removed) {
            mImeTargetToken = imeInputTarget;
            mIsVisibleForImeInputTarget = visibleRequested;
            mIsRemoved = removed;
        }
    }
}
