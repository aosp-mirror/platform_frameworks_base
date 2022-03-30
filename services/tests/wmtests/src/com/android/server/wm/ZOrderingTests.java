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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.WindowStateAnimator.PRESERVED_SURFACE_LAYER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.util.SparseBooleanArray;
import android.view.IRecentsAnimationRunner;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.function.Function;

/**
 * Tests for the {@link DisplayContent#assignChildLayers(SurfaceControl.Transaction)} method.
 *
 * Build/Install/Run:
 *  atest WmTests:ZOrderingTests
 */
@SmallTest
@Presubmit
@WindowTestsBase.UseTestDisplay(addAllCommonWindows = true)
@RunWith(WindowTestRunner.class)
public class ZOrderingTests extends WindowTestsBase {

    private static class LayerRecordingTransaction extends SurfaceControl.Transaction {
        // We have WM use our Hierarchy recording subclass of SurfaceControl.Builder
        // such that we can keep track of the parents of Surfaces as they are constructed.
        private final HashMap<SurfaceControl, SurfaceControl> mParentFor = new HashMap<>();
        HashMap<SurfaceControl, Integer> mLayersForControl = new HashMap<>();
        HashMap<SurfaceControl, SurfaceControl> mRelativeLayersForControl = new HashMap<>();

        @Override
        public SurfaceControl.Transaction setLayer(SurfaceControl sc, int layer) {
            mRelativeLayersForControl.remove(sc);
            mLayersForControl.put(sc, layer);
            return this;
        }

        @Override
        public SurfaceControl.Transaction setRelativeLayer(SurfaceControl sc,
                SurfaceControl relativeTo,
                int layer) {
            mRelativeLayersForControl.put(sc, relativeTo);
            mLayersForControl.put(sc, layer);
            return this;
        }

        private int getLayer(SurfaceControl sc) {
            return mLayersForControl.getOrDefault(sc, 0);
        }

        private SurfaceControl getRelativeLayer(SurfaceControl sc) {
            return mRelativeLayersForControl.get(sc);
        }

        void addParentFor(SurfaceControl child, SurfaceControl parent) {
            mParentFor.put(child, parent);
        }

        SurfaceControl getParentFor(SurfaceControl child) {
            return mParentFor.get(child);
        }

        @Override
        public void close() {

        }
    }

    private static class HierarchyRecorder extends SurfaceControl.Builder {
        private LayerRecordingTransaction mTransaction;
        private SurfaceControl mPendingParent;

        HierarchyRecorder(SurfaceSession s, LayerRecordingTransaction transaction) {
            super(s);
            mTransaction = transaction;
        }

        @Override
        public SurfaceControl.Builder setParent(SurfaceControl sc) {
            mPendingParent = sc;
            return super.setParent(sc);
        }

        @Override
        public SurfaceControl build() {
            final SurfaceControl sc = super.build();
            mTransaction.addParentFor(sc, mPendingParent);
            mPendingParent = null;
            return sc;
        }
    }

    private static class HierarchyRecordingBuilderFactory implements Function<SurfaceSession,
                SurfaceControl.Builder> {
        private LayerRecordingTransaction mTransaction;

        HierarchyRecordingBuilderFactory(LayerRecordingTransaction transaction) {
            mTransaction = transaction;
        }

        @Override
        public SurfaceControl.Builder apply(SurfaceSession s) {
            final LayerRecordingTransaction transaction = mTransaction;
            return new HierarchyRecorder(s, transaction);
        }
    }

    private LayerRecordingTransaction mTransaction;

    @Override
    void beforeCreateTestDisplay() {
        // We can't use @Before here because it may happen after WindowTestsBase @Before
        // which is after construction of the DisplayContent, meaning the HierarchyRecorder
        // would miss construction of the top-level layers.
        mTransaction = new LayerRecordingTransaction();
        mWm.mSurfaceControlFactory = new HierarchyRecordingBuilderFactory(mTransaction);
        mWm.mTransactionFactory = () -> mTransaction;
    }

    @After
    public void tearDown() {
        mTransaction.close();
    }

    private static LinkedList<SurfaceControl> getAncestors(LayerRecordingTransaction t,
            SurfaceControl sc) {
        LinkedList<SurfaceControl> p = new LinkedList<>();
        SurfaceControl current = sc;
        do {
            p.addLast(current);

            SurfaceControl rs = t.getRelativeLayer(current);
            if (rs != null) {
                current = rs;
            } else {
                current = t.getParentFor(current);
            }
        } while (current != null);
        return p;
    }


    private static void assertZOrderGreaterThan(LayerRecordingTransaction t, SurfaceControl left,
            SurfaceControl right) {
        final LinkedList<SurfaceControl> leftParentChain = getAncestors(t, left);
        final LinkedList<SurfaceControl> rightParentChain = getAncestors(t, right);

        SurfaceControl leftTop = leftParentChain.peekLast();
        SurfaceControl rightTop = rightParentChain.peekLast();
        while (leftTop != null && rightTop != null && leftTop == rightTop) {
            leftParentChain.removeLast();
            rightParentChain.removeLast();
            leftTop = leftParentChain.peekLast();
            rightTop = rightParentChain.peekLast();
        }

        if (rightTop == null) { // right is the parent of left.
            assertThat(t.getLayer(leftTop)).isGreaterThan(0);
        } else if (leftTop == null) { // left is the parent of right.
            assertThat(t.getLayer(rightTop)).isLessThan(0);
        } else {
            assertThat(t.getLayer(leftTop)).isGreaterThan(t.getLayer(rightTop));
        }
    }

    void assertWindowHigher(WindowState left, WindowState right) {
        assertZOrderGreaterThan(mTransaction, left.getSurfaceControl(), right.getSurfaceControl());
    }

    WindowState createWindow(String name) {
        return createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, name);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithNoTarget() {
        mDisplayContent.setImeLayeringTarget(null);
        mDisplayContent.assignChildLayers(mTransaction);

        // The Ime has an higher base layer than app windows and lower base layer than system
        // windows, so it should be above app windows and below system windows if there isn't an IME
        // target.
        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mNavBarWindow, mImeWindow);
        assertWindowHigher(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTarget() {
        final WindowState imeAppTarget = createWindow("imeAppTarget");
        mDisplayContent.setImeLayeringTarget(imeAppTarget);

        mDisplayContent.assignChildLayers(mTransaction);

        // Ime should be above all app windows and below system windows if it is targeting an app
        // window.
        assertWindowHigher(mImeWindow, imeAppTarget);
        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mNavBarWindow, mImeWindow);
        assertWindowHigher(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTargetWithChildWindows() {
        final WindowState imeAppTarget = createWindow("imeAppTarget");
        final WindowState imeAppTargetChildAboveWindow = createWindow(imeAppTarget,
                TYPE_APPLICATION_ATTACHED_DIALOG, imeAppTarget.mToken,
                "imeAppTargetChildAboveWindow");
        final WindowState imeAppTargetChildBelowWindow = createWindow(imeAppTarget,
                TYPE_APPLICATION_MEDIA_OVERLAY, imeAppTarget.mToken,
                "imeAppTargetChildBelowWindow");

        mDisplayContent.setImeLayeringTarget(imeAppTarget);
        makeWindowVisible(mImeWindow);
        mDisplayContent.assignChildLayers(mTransaction);

        // Ime should be above all app windows except for child windows that are z-ordered above it
        // and below system windows if it is targeting an app window.
        assertWindowHigher(mImeWindow, imeAppTarget);
        assertWindowHigher(imeAppTargetChildAboveWindow, mImeWindow);
        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mNavBarWindow, mImeWindow);
        assertWindowHigher(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithAppTargetAndAppAbove() {
        final WindowState appBelowImeTarget = createWindow("appBelowImeTarget");
        final WindowState imeAppTarget = createWindow("imeAppTarget");
        final WindowState appAboveImeTarget = createWindow("appAboveImeTarget");

        mDisplayContent.setImeLayeringTarget(imeAppTarget);
        mDisplayContent.setImeControlTarget(imeAppTarget);
        mDisplayContent.assignChildLayers(mTransaction);

        // Ime should be above all app windows except for non-fullscreen app window above it and
        // below system windows if it is targeting an app window.
        assertWindowHigher(mImeWindow, imeAppTarget);
        assertWindowHigher(mImeWindow, appBelowImeTarget);
        assertWindowHigher(appAboveImeTarget, mImeWindow);
        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mNavBarWindow, mImeWindow);
        assertWindowHigher(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForImeNonAppImeTarget() {
        final WindowState imeSystemOverlayTarget = createWindow(null, TYPE_SYSTEM_OVERLAY,
                mDisplayContent, "imeSystemOverlayTarget",
                true /* ownerCanAddInternalSystemWindow */);

        mDisplayContent.setImeLayeringTarget(imeSystemOverlayTarget);
        mDisplayContent.assignChildLayers(mTransaction);

        // The IME target base layer is higher than all window except for the nav bar window, so the
        // IME should be above all windows except for the nav bar.
        assertWindowHigher(mImeWindow, imeSystemOverlayTarget);
        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mImeWindow, mDockedDividerWindow);

        // The IME has a higher base layer than the status bar so we may expect it to go
        // above the status bar once they are both in the Non-App layer, as past versions of this
        // test enforced. However this seems like the wrong behavior unless the status bar is the
        // IME target.
        assertWindowHigher(mNavBarWindow, mImeWindow);
        assertWindowHigher(mStatusBarWindow, mImeWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testAssignWindowLayers_ForStatusBarImeTarget() {
        mDisplayContent.setImeLayeringTarget(mStatusBarWindow);
        mDisplayContent.setImeControlTarget(mStatusBarWindow);
        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mImeWindow, mDockedDividerWindow);
        assertWindowHigher(mImeWindow, mStatusBarWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testStackLayers() {
        final WindowState anyWindow1 = createWindow("anyWindow");
        final WindowState pinnedStackWindow = createWindow(null, WINDOWING_MODE_PINNED,
                ACTIVITY_TYPE_STANDARD, TYPE_BASE_APPLICATION, mDisplayContent,
                "pinnedStackWindow");
        final WindowState dockedStackWindow = createWindow(null,
                WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD, TYPE_BASE_APPLICATION,
                mDisplayContent, "dockedStackWindow");
        final WindowState assistantStackWindow = createWindow(null,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT, TYPE_BASE_APPLICATION,
                mDisplayContent, "assistantStackWindow");
        final WindowState homeActivityWindow = createWindow(null, WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_HOME, TYPE_BASE_APPLICATION,
                mDisplayContent, "homeActivityWindow");
        final WindowState anyWindow2 = createWindow("anyWindow2");

        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(dockedStackWindow, homeActivityWindow);
        assertWindowHigher(assistantStackWindow, homeActivityWindow);
        assertWindowHigher(pinnedStackWindow, homeActivityWindow);
        assertWindowHigher(anyWindow1, homeActivityWindow);
        assertWindowHigher(anyWindow2, homeActivityWindow);
        assertWindowHigher(pinnedStackWindow, anyWindow1);
        assertWindowHigher(pinnedStackWindow, anyWindow2);
        assertWindowHigher(pinnedStackWindow, dockedStackWindow);
        assertWindowHigher(pinnedStackWindow, assistantStackWindow);
    }

    @Test
    public void testAssignWindowLayers_ForSysUiPanels() {
        final WindowState navBarPanel =
                createWindow(null, TYPE_NAVIGATION_BAR_PANEL, mDisplayContent, "NavBarPanel");
        final WindowState statusBarPanel =
                createWindow(null, TYPE_STATUS_BAR_ADDITIONAL, mDisplayContent,
                        "StatusBarAdditional");
        final WindowState statusBarSubPanel =
                createWindow(null, TYPE_STATUS_BAR_SUB_PANEL, mDisplayContent, "StatusBarSubPanel");
        mDisplayContent.assignChildLayers(mTransaction);

        // Ime should be above all app windows and below system windows if it is targeting an app
        // window.
        assertWindowHigher(navBarPanel, mNavBarWindow);
        assertWindowHigher(statusBarPanel, mStatusBarWindow);
        assertWindowHigher(statusBarSubPanel, statusBarPanel);
    }

    @Test
    public void testAssignWindowLayers_ForImeOnAppWithRecentsAnimating() {
        final WindowState imeAppTarget = createWindow(null, TYPE_APPLICATION,
                mAppWindow.mActivityRecord, "imeAppTarget");
        mDisplayContent.setImeInputTarget(imeAppTarget);
        mDisplayContent.setImeLayeringTarget(imeAppTarget);
        mDisplayContent.setImeControlTarget(imeAppTarget);
        mDisplayContent.updateImeParent();

        // Simulate the ime layering target task is animating with recents animation.
        final Task imeAppTargetTask = imeAppTarget.getTask();
        final SurfaceAnimator imeTargetTaskAnimator = imeAppTargetTask.mSurfaceAnimator;
        spyOn(imeTargetTaskAnimator);
        doReturn(ANIMATION_TYPE_RECENTS).when(imeTargetTaskAnimator).getAnimationType();
        doReturn(true).when(imeTargetTaskAnimator).isAnimating();

        mDisplayContent.assignChildLayers(mTransaction);

        // Ime should on top of the application window when in recents animation and keep
        // attached on app.
        assertTrue(mDisplayContent.shouldImeAttachedToApp());
        assertWindowHigher(mImeWindow, imeAppTarget);
    }

    @Test
    public void testAssignWindowLayers_ForImeOnPopupImeLayeringTarget() {
        final WindowState imeAppTarget = createWindow(null, TYPE_APPLICATION,
                mAppWindow.mActivityRecord, "imeAppTarget");
        mDisplayContent.setImeInputTarget(imeAppTarget);
        mDisplayContent.setImeLayeringTarget(imeAppTarget);
        mDisplayContent.setImeControlTarget(imeAppTarget);

        // Set a popup IME layering target and keeps the original IME control target behinds it.
        final WindowState popupImeTargetWin = createWindow(imeAppTarget,
                TYPE_APPLICATION_SUB_PANEL, mAppWindow.mActivityRecord, "popupImeTargetWin");
        mDisplayContent.setImeLayeringTarget(popupImeTargetWin);
        mDisplayContent.updateImeParent();

        // Ime should on top of the popup IME layering target window.
        mDisplayContent.assignChildLayers(mTransaction);
        assertWindowHigher(mImeWindow, popupImeTargetWin);
    }


    @Test
    public void testAssignWindowLayers_ForNegativelyZOrderedSubtype() {
        // TODO(b/70040778): We should aim to eliminate the last user of TYPE_APPLICATION_MEDIA
        // then we can drop all negative layering on the windowing side.

        final WindowState anyWindow = createWindow("anyWindow");
        final WindowState child = createWindow(anyWindow, TYPE_APPLICATION_MEDIA, mDisplayContent,
                "TypeApplicationMediaChild");
        final WindowState mediaOverlayChild = createWindow(anyWindow,
                TYPE_APPLICATION_MEDIA_OVERLAY,
                mDisplayContent, "TypeApplicationMediaOverlayChild");

        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(anyWindow, mediaOverlayChild);
        assertWindowHigher(mediaOverlayChild, child);
    }

    @Test
    public void testAssignWindowLayers_ForPostivelyZOrderedSubtype() {
        final WindowState anyWindow = createWindow("anyWindow");
        final ArrayList<WindowState> childList = new ArrayList<>();
        childList.add(createWindow(anyWindow, TYPE_APPLICATION_PANEL, mDisplayContent,
                "TypeApplicationPanelChild"));
        childList.add(createWindow(anyWindow, TYPE_APPLICATION_SUB_PANEL, mDisplayContent,
                "TypeApplicationSubPanelChild"));
        childList.add(createWindow(anyWindow, TYPE_APPLICATION_ATTACHED_DIALOG, mDisplayContent,
                "TypeApplicationAttachedDialogChild"));
        childList.add(createWindow(anyWindow, TYPE_APPLICATION_ABOVE_SUB_PANEL, mDisplayContent,
                "TypeApplicationAboveSubPanelPanelChild"));

        final LayerRecordingTransaction t = mTransaction;
        mDisplayContent.assignChildLayers(t);

        for (int i = childList.size() - 1; i >= 0; i--) {
            assertThat(t.getLayer(childList.get(i).getSurfaceControl()))
                    .isGreaterThan(PRESERVED_SURFACE_LAYER);
        }
    }

    @Test
    public void testDockedDividerPosition() {
        final Task pinnedTask =
                createTask(mDisplayContent, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        final WindowState pinnedWindow =
                createAppWindow(pinnedTask, ACTIVITY_TYPE_STANDARD, "pinnedWindow");

        final Task belowTask =
                createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState belowTaskWindow =
                createAppWindow(belowTask, ACTIVITY_TYPE_STANDARD, "belowTaskWindow");

        final Task splitScreenTask1 =
                createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final WindowState splitWindow1 =
                createAppWindow(splitScreenTask1, ACTIVITY_TYPE_STANDARD, "splitWindow1");
        final Task splitScreenTask2 =
                createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final WindowState splitWindow2 =
                createAppWindow(splitScreenTask2, ACTIVITY_TYPE_STANDARD, "splitWindow2");
        splitScreenTask1.setAdjacentTaskFragment(splitScreenTask2, true /* moveTogether */);
        splitScreenTask2.setAdjacentTaskFragment(splitScreenTask1, true /* moveTogether */);

        final Task aboveTask =
                createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState aboveTaskWindow =
                createAppWindow(aboveTask, ACTIVITY_TYPE_STANDARD, "aboveTaskWindow");

        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(splitWindow1, belowTaskWindow);
        assertWindowHigher(splitWindow2, belowTaskWindow);
        assertWindowHigher(mDockedDividerWindow, splitWindow1);
        assertWindowHigher(mDockedDividerWindow, splitWindow2);
        assertWindowHigher(aboveTaskWindow, mDockedDividerWindow);
        assertWindowHigher(pinnedWindow, aboveTaskWindow);
    }


    @Test
    public void testDockedDividerPosition_noAboveTask() {
        final Task pinnedTask =
                createTask(mDisplayContent, WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        final WindowState pinnedWindow =
                createAppWindow(pinnedTask, ACTIVITY_TYPE_STANDARD, "pinnedWindow");

        final Task belowTask =
                createTask(mDisplayContent, WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState belowTaskWindow =
                createAppWindow(belowTask, ACTIVITY_TYPE_STANDARD, "belowTaskWindow");

        final Task splitScreenTask1 =
                createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final WindowState splitWindow1 =
                createAppWindow(splitScreenTask1, ACTIVITY_TYPE_STANDARD, "splitWindow1");
        final Task splitScreenTask2 =
                createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW, ACTIVITY_TYPE_STANDARD);
        final WindowState splitWindow2 =
                createAppWindow(splitScreenTask2, ACTIVITY_TYPE_STANDARD, "splitWindow2");
        splitScreenTask1.setAdjacentTaskFragment(splitScreenTask2, true /* moveTogether */);
        splitScreenTask2.setAdjacentTaskFragment(splitScreenTask1, true /* moveTogether */);

        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(splitWindow1, belowTaskWindow);
        assertWindowHigher(splitWindow2, belowTaskWindow);
        assertWindowHigher(mDockedDividerWindow, splitWindow1);
        assertWindowHigher(mDockedDividerWindow, splitWindow2);
        assertWindowHigher(pinnedWindow, mDockedDividerWindow);
    }

    @Test
    public void testAttachNavBarWhenEnteringRecents_expectNavBarHigherThanIme() {
        // create RecentsAnimationController
        IRecentsAnimationRunner mockRunner = mock(IRecentsAnimationRunner.class);
        when(mockRunner.asBinder()).thenReturn(new Binder());
        final int displayId = mDisplayContent.getDisplayId();
        RecentsAnimationController controller = new RecentsAnimationController(
                mWm, mockRunner, null, displayId);
        spyOn(controller);
        controller.mShouldAttachNavBarToAppDuringTransition = true;
        doReturn(mNavBarWindow).when(controller).getNavigationBarWindow();
        mWm.setRecentsAnimationController(controller);

        // set ime visible
        spyOn(mDisplayContent.mInputMethodWindow);
        doReturn(true).when(mDisplayContent.mInputMethodWindow).isVisible();

        // create home activity
        Task rootHomeTask = mDisplayContent.getDefaultTaskDisplayArea().getRootHomeTask();
        final ActivityRecord homeActivity = new ActivityBuilder(mWm.mAtmService)
                .setParentTask(rootHomeTask)
                .setCreateTask(true)
                .build();
        homeActivity.setVisibility(true);

        // start recent animation
        controller.initialize(homeActivity.getActivityType(), new SparseBooleanArray(),
                homeActivity);

        mDisplayContent.assignChildLayers(mTransaction);
        assertZOrderGreaterThan(mTransaction, mNavBarWindow.mToken.getSurfaceControl(),
                mDisplayContent.getImeContainer().getSurfaceControl());
    }

    @Test
    public void testPopupWindowAndParentIsImeTarget_expectHigherThanIme_inMultiWindow() {
        // Simulate the app window is in multi windowing mode and being IME target
        mAppWindow.getConfiguration().windowConfiguration.setWindowingMode(
                WINDOWING_MODE_MULTI_WINDOW);
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        mDisplayContent.setImeInputTarget(mAppWindow);
        makeWindowVisible(mImeWindow);

        // Create a popupWindow
        assertWindowHigher(mImeWindow, mAppWindow);
        final WindowState popupWindow = createWindow(mAppWindow, TYPE_APPLICATION_PANEL,
                mDisplayContent, "PopupWindow");
        spyOn(popupWindow);

        mDisplayContent.assignChildLayers(mTransaction);

        // Verify the surface layer of the popupWindow should higher than IME
        verify(popupWindow).needsRelativeLayeringToIme();
        assertThat(popupWindow.needsRelativeLayeringToIme()).isTrue();
        assertZOrderGreaterThan(mTransaction, popupWindow.getSurfaceControl(),
                mDisplayContent.getImeContainer().getSurfaceControl());
    }
}
