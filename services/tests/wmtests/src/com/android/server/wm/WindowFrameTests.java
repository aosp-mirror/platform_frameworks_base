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
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.DisplayCutout.fromBoundingRect;
import static android.view.WindowManager.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager.TaskDescription;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IWindow;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link WindowState#computeFrameLw} method and other window frame machinery.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:WindowFrameTests
 */
@SmallTest
@Presubmit
public class WindowFrameTests extends WindowTestsBase {

    private WindowToken mWindowToken;
    private final IWindow mIWindow = new TestIWindow();
    private final Rect mEmptyRect = new Rect();

    static class WindowStateWithTask extends WindowState {
        final Task mTask;
        boolean mDockedResizingForTest = false;
        WindowStateWithTask(WindowManagerService wm, IWindow iWindow, WindowToken windowToken,
                WindowManager.LayoutParams attrs, Task t) {
            super(wm, mock(Session.class), iWindow, windowToken, null, 0, 0, attrs, 0, 0,
                    false /* ownerCanAddInternalSystemWindow */);
            mTask = t;
        }

        @Override
        Task getTask() {
            return mTask;
        }

        @Override
        boolean isDockedResizing() {
            return mDockedResizingForTest;
        }
    }

    private static class TaskWithBounds extends Task {
        final Rect mBounds;
        final Rect mOverrideDisplayedBounds = new Rect();
        boolean mFullscreenForTest = true;

        TaskWithBounds(TaskStack stack, WindowManagerService wm, Rect bounds) {
            super(0, stack, 0, wm, 0, false, new TaskDescription(), null);
            mBounds = bounds;
            setBounds(bounds);
        }

        @Override
        public Rect getBounds() {
            return mBounds;
        }

        @Override
        public void getBounds(Rect out) {
            out.set(mBounds);
        }

        @Override
        public void getRequestedOverrideBounds(Rect outBounds) {
            outBounds.set(mBounds);
        }
        @Override
        Rect getOverrideDisplayedBounds() {
            return mOverrideDisplayedBounds;
        }
        @Override
        boolean isFullscreen() {
            return mFullscreenForTest;
        }
    }

    TaskStack mStubStack;

    @Before
    public void setUp() throws Exception {
        mWindowToken = createAppWindowToken(mWm.getDefaultDisplayContentLocked(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        mStubStack = WindowTestUtils.createMockTaskStack();
    }

    // Do not use this function directly in the tests below. Instead, use more explicit function
    // such as assertFlame().
    private void assertRect(Rect rect, int left, int top, int right, int bottom) {
        assertEquals(left, rect.left);
        assertEquals(top, rect.top);
        assertEquals(right, rect.right);
        assertEquals(bottom, rect.bottom);
    }

    private void assertContentInset(WindowState w, int left, int top, int right, int bottom) {
        assertRect(w.getContentInsets(), left, top, right, bottom);
    }

    private void assertVisibleInset(WindowState w, int left, int top, int right, int bottom) {
        assertRect(w.getVisibleInsets(), left, top, right, bottom);
    }

    private void assertStableInset(WindowState w, int left, int top, int right, int bottom) {
        assertRect(w.getStableInsets(), left, top, right, bottom);
    }

    private void assertFrame(WindowState w, int left, int top, int right, int bottom) {
        assertRect(w.getFrameLw(), left, top, right, bottom);
    }

    private void assertContentFrame(WindowState w, Rect expectedRect) {
        assertRect(w.getContentFrameLw(), expectedRect.left, expectedRect.top, expectedRect.right,
                expectedRect.bottom);
    }

    private void assertVisibleFrame(WindowState w, Rect expectedRect) {
        assertRect(w.getVisibleFrameLw(), expectedRect.left, expectedRect.top, expectedRect.right,
                expectedRect.bottom);
    }

    private void assertStableFrame(WindowState w, Rect expectedRect) {
        assertRect(w.getStableFrameLw(), expectedRect.left, expectedRect.top, expectedRect.right,
                expectedRect.bottom);
    }

    private void assertPolicyCrop(WindowStateWithTask w, int left, int top, int right, int bottom) {
        Rect policyCrop = new Rect();
        w.calculatePolicyCrop(policyCrop);
        assertRect(policyCrop, left, top, right, bottom);
    }

    @Test
    public void testLayoutInFullscreenTaskInsets() {
        // fullscreen task doesn't use bounds for computeFrame
        final Task task = new TaskWithBounds(mStubStack, mWm, null);
        WindowState w = createWindow(task, MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final int bottomContentInset = 100;
        final int topContentInset = 50;
        final int bottomVisibleInset = 30;
        final int topVisibleInset = 70;
        final int leftStableInset = 20;
        final int rightStableInset = 90;

        // With no insets or system decor all the frames incoming from PhoneWindowManager
        // are identical.
        final Rect pf = new Rect(0, 0, 1000, 1000);
        final Rect df = pf;
        final Rect of = df;
        final Rect cf = new Rect(pf);
        // Produce some insets
        cf.top += 50;
        cf.bottom -= 100;
        final Rect vf = new Rect(pf);
        vf.top += topVisibleInset;
        vf.bottom -= bottomVisibleInset;
        final Rect sf = new Rect(pf);
        sf.left += leftStableInset;
        sf.right -= rightStableInset;

        final Rect dcf = pf;
        // When mFrame extends past cf, the content insets are
        // the difference between mFrame and ContentFrame. Visible
        // and stable frames work the same way.
        w.getWindowFrames().setFrames(pf, df, of, cf, vf, dcf, sf, mEmptyRect);
        w.computeFrameLw();
        assertFrame(w, 0, 0, 1000, 1000);
        assertContentInset(w, 0, topContentInset, 0, bottomContentInset);
        assertVisibleInset(w, 0, topVisibleInset, 0, bottomVisibleInset);
        assertStableInset(w, leftStableInset, 0, rightStableInset, 0);
        assertContentFrame(w, cf);
        assertVisibleFrame(w, vf);
        assertStableFrame(w, sf);
        // On the other hand getFrame() doesn't extend past cf we won't get any insets
        w.mAttrs.x = 100;
        w.mAttrs.y = 100;
        w.mAttrs.width = 100; w.mAttrs.height = 100; //have to clear MATCH_PARENT
        w.mRequestedWidth = 100;
        w.mRequestedHeight = 100;
        w.computeFrameLw();
        assertFrame(w, 100, 100, 200, 200);
        assertContentInset(w, 0, 0, 0, 0);
        // In this case the frames are shrunk to the window frame.
        assertContentFrame(w, w.getFrameLw());
        assertVisibleFrame(w, w.getFrameLw());
        assertStableFrame(w, w.getFrameLw());
    }

    @Test
    public void testLayoutInFullscreenTaskNoInsets() {
        // fullscreen task doesn't use bounds for computeFrame
        final Task task = new TaskWithBounds(mStubStack, mWm, null);
        WindowState w = createWindow(task, MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        // With no insets or system decor all the frames incoming from PhoneWindowManager
        // are identical.
        final Rect pf = new Rect(0, 0, 1000, 1000);

        // Here the window has FILL_PARENT, FILL_PARENT
        // so we expect it to fill the entire available frame.
        w.getWindowFrames().setFrames(pf, pf, pf, pf, pf, pf, pf, pf);
        w.computeFrameLw();
        assertFrame(w, 0, 0, 1000, 1000);

        // It can select various widths and heights within the bounds.
        // Strangely the window attribute width is ignored for normal windows
        // and we use mRequestedWidth/mRequestedHeight
        w.mAttrs.width = 300;
        w.mAttrs.height = 300;
        w.computeFrameLw();
        // Explicit width and height without requested width/height
        // gets us nothing.
        assertFrame(w, 0, 0, 0, 0);

        w.mRequestedWidth = 300;
        w.mRequestedHeight = 300;
        w.computeFrameLw();
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
        w.computeFrameLw();
        assertFrame(w, 0, 0, 100, 100);
        w.mAttrs.flags = 0;

        // But sizes too large will be clipped to the containing frame
        w.mRequestedWidth = 1200;
        w.mRequestedHeight = 1200;
        w.computeFrameLw();
        assertFrame(w, 0, 0, 1000, 1000);

        // Before they are clipped though windows will be shifted
        w.mAttrs.x = 300;
        w.mAttrs.y = 300;
        w.mRequestedWidth = 1000;
        w.mRequestedHeight = 1000;
        w.computeFrameLw();
        assertFrame(w, 0, 0, 1000, 1000);

        // If there is room to move around in the parent frame the window will be shifted according
        // to gravity.
        w.mAttrs.x = 0;
        w.mAttrs.y = 0;
        w.mRequestedWidth = 300;
        w.mRequestedHeight = 300;
        w.mAttrs.gravity = Gravity.RIGHT | Gravity.TOP;
        w.computeFrameLw();
        assertFrame(w, 700, 0, 1000, 300);
        w.mAttrs.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        w.computeFrameLw();
        assertFrame(w, 700, 700, 1000, 1000);
        // Window specified  x and y are interpreted as offsets in the opposite
        // direction of gravity
        w.mAttrs.x = 100;
        w.mAttrs.y = 100;
        w.computeFrameLw();
        assertFrame(w, 600, 600, 900, 900);
    }

    @Test
    public void testLayoutNonfullscreenTask() {
        final DisplayInfo displayInfo = mWm.getDefaultDisplayContentLocked().getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;

        final int taskLeft = logicalWidth / 4;
        final int taskTop = logicalHeight / 4;
        final int taskRight = logicalWidth / 4 * 3;
        final int taskBottom = logicalHeight / 4 * 3;
        final Rect taskBounds = new Rect(taskLeft, taskTop, taskRight, taskBottom);
        final TaskWithBounds task = new TaskWithBounds(mStubStack, mWm, taskBounds);
        task.mFullscreenForTest = false;
        WindowState w = createWindow(task, MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, pf, pf, pf, pf, pf, pf, mEmptyRect);
        w.computeFrameLw();
        // For non fullscreen tasks the containing frame is based off the
        // task bounds not the parent frame.
        assertFrame(w, taskLeft, taskTop, taskRight, taskBottom);
        assertContentFrame(w, taskBounds);
        assertContentInset(w, 0, 0, 0, 0);

        pf.set(0, 0, logicalWidth, logicalHeight);
        // We still produce insets against the containing frame the same way.
        final int cfRight = logicalWidth / 2;
        final int cfBottom = logicalHeight / 2;
        final Rect cf = new Rect(0, 0, cfRight, cfBottom);
        windowFrames.setFrames(pf, pf, pf, cf, cf, pf, cf, mEmptyRect);
        w.computeFrameLw();
        assertFrame(w, taskLeft, taskTop, taskRight, taskBottom);
        int contentInsetRight = taskRight - cfRight;
        int contentInsetBottom = taskBottom - cfBottom;
        assertContentInset(w, 0, 0, contentInsetRight, contentInsetBottom);
        assertContentFrame(w, new Rect(taskLeft, taskTop, taskRight - contentInsetRight,
                taskBottom - contentInsetBottom));

        pf.set(0, 0, logicalWidth, logicalHeight);
        // If we set displayed bounds, the insets will be computed with the main task bounds
        // but the frame will be positioned according to the displayed bounds.
        final int insetLeft = logicalWidth / 5;
        final int insetTop = logicalHeight / 5;
        final int insetRight = insetLeft + (taskRight - taskLeft);
        final int insetBottom = insetTop + (taskBottom - taskTop);
        task.mOverrideDisplayedBounds.set(taskBounds);
        task.mBounds.set(insetLeft, insetTop, insetRight, insetBottom);
        windowFrames.setFrames(pf, pf, pf, cf, cf, pf, cf, mEmptyRect);
        w.computeFrameLw();
        assertFrame(w, taskLeft, taskTop, taskRight, taskBottom);
        contentInsetRight = insetRight - cfRight;
        contentInsetBottom = insetBottom - cfBottom;
        assertContentInset(w, 0, 0, contentInsetRight, contentInsetBottom);
        assertContentFrame(w, new Rect(taskLeft, taskTop, taskRight - contentInsetRight,
                taskBottom - contentInsetBottom));
    }

    @Test
    public void testCalculatePolicyCrop() {
        final WindowStateWithTask w = createWindow(
                new TaskWithBounds(mStubStack, mWm, null), MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final DisplayInfo displayInfo = w.getDisplayContent().getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;
        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        final Rect df = pf;
        final Rect of = df;
        final Rect cf = new Rect(pf);
        // Produce some insets
        cf.top += displayInfo.logicalWidth / 10;
        cf.bottom -= displayInfo.logicalWidth / 5;
        final Rect vf = cf;
        final Rect sf = vf;
        // We use a decor content frame with insets to produce cropping.
        Rect dcf = new Rect(cf);

        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, df, of, cf, vf, dcf, sf, mEmptyRect);
        w.computeFrameLw();
        assertPolicyCrop(w, 0, cf.top, logicalWidth, cf.bottom);

        windowFrames.mDecorFrame.setEmpty();
        // Likewise with no decor frame we would get no crop
        w.computeFrameLw();
        assertPolicyCrop(w, 0, 0, logicalWidth, logicalHeight);

        // Now we set up a window which doesn't fill the entire decor frame.
        // Normally it would be cropped to it's frame but in the case of docked resizing
        // we need to account for the fact the windows surface will be made
        // fullscreen and thus also make the crop fullscreen.

        windowFrames.setFrames(pf, pf, pf, pf, pf, pf, pf, pf);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;
        w.mAttrs.width = logicalWidth / 2;
        w.mAttrs.height = logicalHeight / 2;
        w.mRequestedWidth = logicalWidth / 2;
        w.mRequestedHeight = logicalHeight / 2;
        w.computeFrameLw();

        // Normally the crop is shrunk from the decor frame
        // to the computed window frame.
        assertPolicyCrop(w, 0, 0, logicalWidth / 2, logicalHeight / 2);

        w.mDockedResizingForTest = true;
        // But if we are docked resizing it won't be, however we will still be
        // shrunk to the decor frame and the display.
        assertPolicyCrop(w, 0, 0,
                Math.min(pf.width(), displayInfo.logicalWidth),
                Math.min(pf.height(), displayInfo.logicalHeight));
    }

    @Test
    public void testLayoutLetterboxedWindow() {
        // First verify task behavior in multi-window mode.
        final DisplayInfo displayInfo = mWm.getDefaultDisplayContentLocked().getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;

        final int taskLeft = logicalWidth / 5;
        final int taskTop = logicalHeight / 5;
        final int taskRight = logicalWidth / 4 * 3;
        final int taskBottom = logicalHeight / 4 * 3;
        final Rect taskBounds = new Rect(taskLeft, taskTop, taskRight, taskBottom);
        final TaskWithBounds task = new TaskWithBounds(mStubStack, mWm, taskBounds);
        task.mFullscreenForTest = false;
        WindowState w = createWindow(task, MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, pf, pf, pf, pf, pf, pf, mEmptyRect);
        w.computeFrameLw();
        // For non fullscreen tasks the containing frame is based off the
        // task bounds not the parent frame.
        assertFrame(w, taskLeft, taskTop, taskRight, taskBottom);
        assertContentFrame(w, taskBounds);
        assertContentInset(w, 0, 0, 0, 0);

        // Now simulate switch to fullscreen for letterboxed app.
        final int xInset = logicalWidth / 10;
        final Rect cf = new Rect(xInset, 0, logicalWidth - xInset, logicalHeight);
        Configuration config = new Configuration(w.mAppToken.getRequestedOverrideConfiguration());
        config.windowConfiguration.setBounds(cf);
        w.mAppToken.onRequestedOverrideConfigurationChanged(config);
        pf.set(0, 0, logicalWidth, logicalHeight);
        task.mFullscreenForTest = true;
        windowFrames.setFrames(pf, pf, pf, cf, cf, pf, cf, mEmptyRect);
        w.computeFrameLw();
        assertFrame(w, cf.left, cf.top, cf.right, cf.bottom);
        assertContentFrame(w, cf);
        assertContentInset(w, 0, 0, 0, 0);
    }

    @Test
    public void testDisplayCutout() {
        // Regular fullscreen task and window
        final Task task = new TaskWithBounds(mStubStack, mWm, null);
        WindowState w = createWindow(task, MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, 1000, 2000);
        // Create a display cutout of size 50x50, aligned top-center
        final WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(500, 0, 550, 50, BOUNDS_POSITION_TOP),
                pf.width(), pf.height());

        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, pf, pf, pf, pf, pf, pf, pf);
        windowFrames.setDisplayCutout(cutout);
        w.computeFrameLw();

        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetTop(), 50);
        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetBottom(), 0);
        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetLeft(), 0);
        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetRight(), 0);
    }

    @Test
    public void testDisplayCutout_tempDisplayedBounds() {
        // Regular fullscreen task and window
        final TaskWithBounds task = new TaskWithBounds(mStubStack, mWm,
                new Rect(0, 0, 1000, 2000));
        task.mFullscreenForTest = false;
        task.setOverrideDisplayedBounds(new Rect(0, -500, 1000, 1500));
        WindowState w = createWindow(task, MATCH_PARENT, MATCH_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, -500, 1000, 1500);
        // Create a display cutout of size 50x50, aligned top-center
        final WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(500, 0, 550, 50, BOUNDS_POSITION_TOP),
                pf.width(), pf.height());

        final WindowFrames windowFrames = w.getWindowFrames();
        windowFrames.setFrames(pf, pf, pf, pf, pf, pf, pf, pf);
        windowFrames.setDisplayCutout(cutout);
        w.computeFrameLw();

        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetTop(), 50);
        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetBottom(), 0);
        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetLeft(), 0);
        assertEquals(w.getWmDisplayCutout().getDisplayCutout().getSafeInsetRight(), 0);
    }

    private WindowStateWithTask createWindow(Task task, int width, int height) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.width = width;
        attrs.height = height;

        return new WindowStateWithTask(mWm, mIWindow, mWindowToken, attrs, task);
    }
}
