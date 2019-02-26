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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.LinkedList;

/**
 * Tests for the {@link WindowLayersController} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.ZOrderingTests
 */
@SmallTest
@FlakyTest(bugId = 74078662)
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ZOrderingTests extends WindowTestsBase {

    private class LayerRecordingTransaction extends SurfaceControl.Transaction {
        HashMap<SurfaceControl, Integer> mLayersForControl = new HashMap();
        HashMap<SurfaceControl, SurfaceControl> mRelativeLayersForControl = new HashMap();

        @Override
        public SurfaceControl.Transaction setLayer(SurfaceControl sc, int layer) {
            mRelativeLayersForControl.remove(sc);
            mLayersForControl.put(sc, layer);
            return super.setLayer(sc, layer);
        }

        @Override
        public SurfaceControl.Transaction setRelativeLayer(SurfaceControl sc,
                SurfaceControl relativeTo,
                int layer) {
            mRelativeLayersForControl.put(sc, relativeTo);
            mLayersForControl.put(sc, layer);
            return super.setRelativeLayer(sc, relativeTo, layer);
        }

        private int getLayer(SurfaceControl sc) {
            return mLayersForControl.getOrDefault(sc, 0);
        }

        private SurfaceControl getRelativeLayer(SurfaceControl sc) {
            return mRelativeLayersForControl.get(sc);
        }
    };

    // We have WM use our Hierarchy recording subclass of SurfaceControl.Builder
    // such that we can keep track of the parents of Surfaces as they are constructed.
    private HashMap<SurfaceControl, SurfaceControl> mParentFor = new HashMap();

    private class HierarchyRecorder extends SurfaceControl.Builder {
        SurfaceControl mPendingParent;

        HierarchyRecorder(SurfaceSession s) {
            super(s);
        }

        public SurfaceControl.Builder setParent(SurfaceControl sc) {
            mPendingParent = sc;
            return super.setParent(sc);
        }
        public SurfaceControl build() {
            SurfaceControl sc = super.build();
            mParentFor.put(sc, mPendingParent);
            mPendingParent = null;
            return sc;
        }
    };

    class HierarchyRecordingBuilderFactory implements SurfaceBuilderFactory {
        public SurfaceControl.Builder make(SurfaceSession s) {
            return new HierarchyRecorder(s);
        }
    };

    private LayerRecordingTransaction mTransaction;

    @Override
    void beforeCreateDisplay() {
        // We can't use @Before here because it may happen after WindowTestsBase @Before
        // which is after construction of the DisplayContent, meaning the HierarchyRecorder
        // would miss construction of the top-level layers.
        mTransaction = new LayerRecordingTransaction();
        sWm.mSurfaceBuilderFactory = new HierarchyRecordingBuilderFactory();
        sWm.mTransactionFactory = () -> mTransaction;
    }

    @After
    public void after() {
        mTransaction.close();
        mParentFor.clear();
    }

    LinkedList<SurfaceControl> getAncestors(LayerRecordingTransaction t, SurfaceControl sc) {
        LinkedList<SurfaceControl> p = new LinkedList();
        SurfaceControl current = sc;
        do {
            p.addLast(current);

            SurfaceControl rs = t.getRelativeLayer(current);
            if (rs != null) {
                current = rs;
            } else {
                current = mParentFor.get(current);
            }
        } while (current != null);
        return p;
    }


    void assertZOrderGreaterThan(LayerRecordingTransaction t, SurfaceControl left,
            SurfaceControl right) throws Exception {
        final LinkedList<SurfaceControl> leftParentChain = getAncestors(t, left);
        final LinkedList<SurfaceControl> rightParentChain = getAncestors(t, right);

        SurfaceControl commonAncestor = null;
        SurfaceControl leftTop = leftParentChain.peekLast();
        SurfaceControl rightTop = rightParentChain.peekLast();
        while (leftTop != null && rightTop != null && leftTop == rightTop) {
            commonAncestor = leftParentChain.removeLast();
            rightParentChain.removeLast();
            leftTop = leftParentChain.peekLast();
            rightTop = rightParentChain.peekLast();
        }

        if (rightTop == null) { // right is the parent of left.
            assertGreaterThan(t.getLayer(leftTop), 0);
        } else if (leftTop == null) { // left is the parent of right.
            assertGreaterThan(0, t.getLayer(rightTop));
        } else {
            assertGreaterThan(t.getLayer(leftTop),
                    t.getLayer(rightTop));
        }
    }

    void assertWindowHigher(WindowState left, WindowState right) throws Exception {
        assertZOrderGreaterThan(mTransaction, left.getSurfaceControl(), right.getSurfaceControl());
    }

    WindowState createWindow(String name) {
        return createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, name);
    }

    @Test
    public void testAssignWindowLayers_ForImeWithNoTarget() throws Exception {
        sWm.mInputMethodTarget = null;
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
    public void testAssignWindowLayers_ForImeWithAppTarget() throws Exception {
        final WindowState imeAppTarget = createWindow("imeAppTarget");
        sWm.mInputMethodTarget = imeAppTarget;

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
    public void testAssignWindowLayers_ForImeWithAppTargetWithChildWindows() throws Exception {
        final WindowState imeAppTarget = createWindow("imeAppTarget");
        final WindowState imeAppTargetChildAboveWindow = createWindow(imeAppTarget,
                TYPE_APPLICATION_ATTACHED_DIALOG, imeAppTarget.mToken,
                "imeAppTargetChildAboveWindow");
        final WindowState imeAppTargetChildBelowWindow = createWindow(imeAppTarget,
                TYPE_APPLICATION_MEDIA_OVERLAY, imeAppTarget.mToken,
                "imeAppTargetChildBelowWindow");

        sWm.mInputMethodTarget = imeAppTarget;
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
    public void testAssignWindowLayers_ForImeWithAppTargetAndAppAbove() throws Exception {
        final WindowState appBelowImeTarget = createWindow("appBelowImeTarget");
        final WindowState imeAppTarget = createWindow("imeAppTarget");
        final WindowState appAboveImeTarget = createWindow("appAboveImeTarget");

        sWm.mInputMethodTarget = imeAppTarget;
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
    public void testAssignWindowLayers_ForImeNonAppImeTarget() throws Exception {
        final WindowState imeSystemOverlayTarget = createWindow(null, TYPE_SYSTEM_OVERLAY,
                mDisplayContent, "imeSystemOverlayTarget",
                true /* ownerCanAddInternalSystemWindow */);

        sWm.mInputMethodTarget = imeSystemOverlayTarget;
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
    public void testAssignWindowLayers_ForStatusBarImeTarget() throws Exception {
        sWm.mInputMethodTarget = mStatusBarWindow;
        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(mImeWindow, mChildAppWindowAbove);
        assertWindowHigher(mImeWindow, mAppWindow);
        assertWindowHigher(mImeWindow, mDockedDividerWindow);
        assertWindowHigher(mImeWindow, mStatusBarWindow);

        // And, IME dialogs should always have an higher layer than the IME.
        assertWindowHigher(mImeDialogWindow, mImeWindow);
    }

    @Test
    public void testStackLayers() throws Exception {
        final WindowState anyWindow1 = createWindow("anyWindow");
        final WindowState pinnedStackWindow = createWindowOnStack(null, WINDOWING_MODE_PINNED,
                ACTIVITY_TYPE_STANDARD, TYPE_BASE_APPLICATION, mDisplayContent,
                "pinnedStackWindow");
        final WindowState dockedStackWindow = createWindowOnStack(null,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, TYPE_BASE_APPLICATION,
                mDisplayContent, "dockedStackWindow");
        final WindowState assistantStackWindow = createWindowOnStack(null, WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_ASSISTANT, TYPE_BASE_APPLICATION,
                mDisplayContent, "assistantStackWindow");
        final WindowState homeActivityWindow = createWindowOnStack(null, WINDOWING_MODE_FULLSCREEN,
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
    public void testAssignWindowLayers_ForSysUiPanels() throws Exception {
        final WindowState navBarPanel =
                createWindow(null, TYPE_NAVIGATION_BAR_PANEL, mDisplayContent, "NavBarPanel");
        final WindowState statusBarPanel =
                createWindow(null, TYPE_STATUS_BAR_PANEL, mDisplayContent, "StatusBarPanel");
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
    public void testAssignWindowLayers_ForNegativelyZOrderedSubtype() throws Exception {
        // TODO(b/70040778): We should aim to eliminate the last user of TYPE_APPLICATION_MEDIA
        // then we can drop all negative layering on the windowing side.

        final WindowState anyWindow = createWindow("anyWindow");
        final WindowState child = createWindow(anyWindow, TYPE_APPLICATION_MEDIA, mDisplayContent,
                "TypeApplicationMediaChild");
        final WindowState mediaOverlayChild = createWindow(anyWindow, TYPE_APPLICATION_MEDIA_OVERLAY,
                mDisplayContent, "TypeApplicationMediaOverlayChild");

        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(anyWindow, mediaOverlayChild);
        assertWindowHigher(mediaOverlayChild, child);
    }

    @Test
    public void testDockedDividerPosition() throws Exception {
        final WindowState pinnedStackWindow = createWindowOnStack(null, WINDOWING_MODE_PINNED,
                ACTIVITY_TYPE_STANDARD, TYPE_BASE_APPLICATION, mDisplayContent,
                "pinnedStackWindow");
        final WindowState splitScreenWindow = createWindowOnStack(null,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, TYPE_BASE_APPLICATION,
                mDisplayContent, "splitScreenWindow");
        final WindowState splitScreenSecondaryWindow = createWindowOnStack(null,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD,
                TYPE_BASE_APPLICATION, mDisplayContent, "splitScreenSecondaryWindow");
        final WindowState assistantStackWindow = createWindowOnStack(null, WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_ASSISTANT, TYPE_BASE_APPLICATION,
                mDisplayContent, "assistantStackWindow");

        mDisplayContent.assignChildLayers(mTransaction);

        assertWindowHigher(mDockedDividerWindow, splitScreenWindow);
        assertWindowHigher(mDockedDividerWindow, splitScreenSecondaryWindow);
        assertWindowHigher(pinnedStackWindow, mDockedDividerWindow);
    }
}
