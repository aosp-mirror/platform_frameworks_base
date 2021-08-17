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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Tests for the {@link WindowState#computeFrameLw} method and other window frame machinery.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowFrameTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowFrameTests extends WindowTestsBase {

    private DisplayContent mTestDisplayContent;

    @Before
    public void setUp() throws Exception {
        DisplayInfo testDisplayInfo = new DisplayInfo(mDisplayInfo);
        testDisplayInfo.displayCutout = null;
        mTestDisplayContent = createNewDisplay(testDisplayInfo);
    }

    // Do not use this function directly in the tests below. Instead, use more explicit function
    // such as assertFlame().
    private void assertRect(Rect rect, int left, int top, int right, int bottom) {
        assertEquals(left, rect.left);
        assertEquals(top, rect.top);
        assertEquals(right, rect.right);
        assertEquals(bottom, rect.bottom);
    }

    private void assertFrame(WindowState w, Rect frame) {
        assertEquals(w.getFrame(), frame);
    }

    private void assertFrame(WindowState w, int left, int top, int right, int bottom) {
        assertRect(w.getFrame(), left, top, right, bottom);
    }

    private void assertRelFrame(WindowState w, int left, int top, int right, int bottom) {
        assertRect(w.getRelativeFrame(), left, top, right, bottom);
    }

    @Test
    public void testLayoutInFullscreenTask() {
        // fullscreen task doesn't use bounds for computeFrame
        WindowState w = createWindow();
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        // With no insets or system decor all the frames incoming from PhoneWindowManager
        // are identical.
        final Rect pf = new Rect(0, 0, 1000, 1000);

        // Here the window has FILL_PARENT, FILL_PARENT
        // so we expect it to fill the entire available frame.
        w.getWindowFrames().setFrames(pf, pf);
        w.computeFrame();
        assertFrame(w, 0, 0, 1000, 1000);
        assertRelFrame(w, 0, 0, 1000, 1000);

        // It can select various widths and heights within the bounds.
        // Strangely the window attribute width is ignored for normal windows
        // and we use mRequestedWidth/mRequestedHeight
        w.mAttrs.width = 300;
        w.mAttrs.height = 300;
        w.computeFrame();
        // Explicit width and height without requested width/height
        // gets us nothing.
        assertFrame(w, 0, 0, 0, 0);

        w.mRequestedWidth = 300;
        w.mRequestedHeight = 300;
        w.computeFrame();
        // With requestedWidth/Height we can freely choose our size within the
        // parent bounds.
        assertFrame(w, 0, 0, 300, 300);

        // With FLAG_SCALED though, requestedWidth/height is used to control
        // the unscaled surface size, and mAttrs.width/height becomes the
        // layout controller.
        w.mAttrs.flags = WindowManager.LayoutParams.FLAG_SCALED;
        w.mRequestedHeight = -1;
        w.mRequestedWidth = -1;
        w.mAttrs.width = 100;
        w.mAttrs.height = 100;
        w.computeFrame();
        assertFrame(w, 0, 0, 100, 100);
        w.mAttrs.flags = 0;

        // But sizes too large will be clipped to the containing frame
        w.mRequestedWidth = 1200;
        w.mRequestedHeight = 1200;
        w.computeFrame();
        assertFrame(w, 0, 0, 1000, 1000);

        // Before they are clipped though windows will be shifted
        w.mAttrs.x = 300;
        w.mAttrs.y = 300;
        w.mRequestedWidth = 1000;
        w.mRequestedHeight = 1000;
        w.computeFrame();
        assertFrame(w, 0, 0, 1000, 1000);

        // If there is room to move around in the parent frame the window will be shifted according
        // to gravity.
        w.mAttrs.x = 0;
        w.mAttrs.y = 0;
        w.mRequestedWidth = 300;
        w.mRequestedHeight = 300;
        w.mAttrs.gravity = Gravity.RIGHT | Gravity.TOP;
        w.computeFrame();
        assertFrame(w, 700, 0, 1000, 300);
        assertRelFrame(w, 700, 0, 1000, 300);
        w.mAttrs.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        w.computeFrame();
        assertFrame(w, 700, 700, 1000, 1000);
        assertRelFrame(w, 700, 700, 1000, 1000);
        // Window specified  x and y are interpreted as offsets in the opposite
        // direction of gravity
        w.mAttrs.x = 100;
        w.mAttrs.y = 100;
        w.computeFrame();
        assertFrame(w, 600, 600, 900, 900);
        assertRelFrame(w, 600, 600, 900, 900);
    }

    @Test
    public void testLayoutNonfullscreenTask() {
        removeGlobalMinSizeRestriction();
        final DisplayInfo displayInfo = mWm.getDefaultDisplayContentLocked().getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;

        final Rect taskBounds = new Rect(
                logicalWidth / 4, logicalHeight / 4, logicalWidth / 4 * 3, logicalHeight / 4 * 3);
        WindowState w = createWindow();
        final Task task = w.getTask();
        // Use split-screen because it is non-fullscreen, but also not floating
        task.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        task.setBounds(taskBounds);
        // The bounds we are requesting might be different from what the system resolved based on
        // other factors.
        final Rect resolvedTaskBounds = task.getBounds();
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, pf);
        w.computeFrame();
        // For non fullscreen tasks the containing frame is based off the
        // task bounds not the parent frame.
        assertEquals(resolvedTaskBounds, w.getFrame());
        assertEquals(0, w.getRelativeFrame().left);
        assertEquals(0, w.getRelativeFrame().top);

        pf.set(0, 0, logicalWidth, logicalHeight);
        // We still produce insets against the containing frame the same way.
        final int cfRight = logicalWidth / 2;
        final int cfBottom = logicalHeight / 2;
        final Rect cf = new Rect(0, 0, cfRight, cfBottom);
        windowFrames.setFrames(pf, pf);
        w.computeFrame();
        assertEquals(resolvedTaskBounds, w.getFrame());
        assertEquals(0, w.getRelativeFrame().left);
        assertEquals(0, w.getRelativeFrame().top);
    }

    @Test
    @FlakyTest(bugId = 137879065)
    public void testLayoutLetterboxedWindow() {
        // First verify task behavior in multi-window mode.
        final DisplayInfo displayInfo = mTestDisplayContent.getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;

        final int taskLeft = logicalWidth / 5;
        final int taskTop = logicalHeight / 5;
        final int taskRight = logicalWidth / 4 * 3;
        final int taskBottom = logicalHeight / 4 * 3;
        final Rect taskBounds = new Rect(taskLeft, taskTop, taskRight, taskBottom);
        WindowState w = createWindow();
        final Task task = w.getTask();
        // Use split-screen because it is non-fullscreen, but also not floating
        task.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY);
        task.setBounds(taskBounds);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, pf);
        w.computeFrame();
        // For non fullscreen tasks the containing frame is based off the
        // task bounds not the parent frame.
        assertFrame(w, taskLeft, taskTop, taskRight, taskBottom);

        // Now simulate switch to fullscreen for letterboxed app.
        final int xInset = logicalWidth / 10;
        final Rect cf = new Rect(xInset, 0, logicalWidth - xInset, logicalHeight);
        Configuration config = new Configuration(w.mActivityRecord.getRequestedOverrideConfiguration());
        config.windowConfiguration.setBounds(cf);
        config.windowConfiguration.setAppBounds(cf);
        w.mActivityRecord.onRequestedOverrideConfigurationChanged(config);
        pf.set(0, 0, logicalWidth, logicalHeight);
        task.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        task.setBounds(null);
        windowFrames.setFrames(pf, pf);
        w.computeFrame();
        assertFrame(w, cf);
    }

    @Test
    public void testFreeformContentInsets() {
        removeGlobalMinSizeRestriction();
        // fullscreen task doesn't use bounds for computeFrame
        WindowState w = createWindow();
        final Task task = w.getTask();
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;
        task.setWindowingMode(WINDOWING_MODE_FREEFORM);

        // Make IME attach to the window and can produce insets.
        final DisplayContent dc = mTestDisplayContent;
        dc.setImeLayeringTarget(w);
        WindowState mockIme = mock(WindowState.class);
        Mockito.doReturn(true).when(mockIme).isVisibleNow();
        dc.mInputMethodWindow = mockIme;
        final InsetsState state = dc.getInsetsStateController().getRawInsetsState();
        final InsetsSource imeSource = state.getSource(ITYPE_IME);
        final Rect imeFrame = new Rect(state.getDisplayFrame());
        imeFrame.top = 400;
        imeSource.setFrame(imeFrame);
        imeSource.setVisible(true);
        w.updateRequestedVisibility(state);
        w.mAboveInsetsState.addSource(imeSource);

        // With no insets or system decor all the frames incoming from PhoneWindowManager
        // are identical.
        final Rect pf = new Rect(0, 0, 1000, 800);

        // First check that it only gets moved up enough to show window.
        final Rect winRect = new Rect(200, 200, 300, 500);
        task.setBounds(winRect);
        w.getWindowFrames().setFrames(pf, pf);
        w.computeFrame();
        assertFrame(w, winRect.left, imeFrame.top - winRect.height(), winRect.right, imeFrame.top);

        // Now check that it won't get moved beyond the top
        winRect.bottom = 650;
        task.setBounds(winRect);
        w.setBounds(winRect);
        w.getWindowFrames().setFrames(pf, pf);
        w.computeFrame();
        assertFrame(w, winRect.left, 0, winRect.right, winRect.height());

        // Now we have status bar. Check that it won't go into the status bar area.
        final Rect statusBarFrame = new Rect(state.getDisplayFrame());
        statusBarFrame.bottom = 60;
        state.getSource(ITYPE_STATUS_BAR).setFrame(statusBarFrame);
        w.getWindowFrames().setFrames(pf, pf);
        w.computeFrame();
        assertFrame(w, winRect.left, statusBarFrame.bottom, winRect.right,
                statusBarFrame.bottom + winRect.height());

        // Check that it's moved back without ime insets
        state.removeSource(ITYPE_IME);
        w.getWindowFrames().setFrames(pf, pf);
        w.computeFrame();
        assertEquals(winRect, w.getFrame());
    }

    private WindowState createWindow() {
        final WindowState ws = createWindow(null, TYPE_APPLICATION, mTestDisplayContent, "WindowFrameTests");
        spyOn(ws);
        return ws;
    }
}
