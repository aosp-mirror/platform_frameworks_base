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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.app.ActivityManager.TaskDescription;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IWindow;
import android.view.WindowManager;

import static android.view.DisplayCutout.fromBoundingRect;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.FILL_PARENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.server.wm.utils.WmDisplayCutout;

/**
 * Tests for the {@link WindowState#computeFrameLw} method and other window frame machinery.
 *
 * Build/Install/Run: bit FrameworksServicesTests:com.android.server.wm.WindowFrameTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowFrameTests extends WindowTestsBase {

    private WindowToken mWindowToken;
    private final IWindow mIWindow = new TestIWindow();

    class WindowStateWithTask extends WindowState {
        final Task mTask;
        boolean mDockedResizingForTest = false;
        WindowStateWithTask(WindowManager.LayoutParams attrs, Task t) {
            super(sWm, null, mIWindow, mWindowToken, null, 0, 0, attrs, 0, 0,
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
    };

    class TaskWithBounds extends Task {
        final Rect mBounds;
        final Rect mInsetBounds = new Rect();
        boolean mFullscreenForTest = true;
        TaskWithBounds(Rect bounds) {
            super(0, mStubStack, 0, sWm, 0, false, new TaskDescription(), null);
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
        public void getOverrideBounds(Rect outBounds) {
            outBounds.set(mBounds);
        }
        @Override
        void getTempInsetBounds(Rect outBounds) {
            outBounds.set(mInsetBounds);
        }
        @Override
        boolean isFullscreen() {
            return mFullscreenForTest;
        }
    }

    TaskStack mStubStack;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        // Just any non zero value.
        sWm.mSystemDecorLayer = 10000;

        mWindowToken = WindowTestUtils.createTestAppWindowToken(
                sWm.getDefaultDisplayContentLocked());
        mStubStack = new TaskStack(sWm, 0, null);
    }

    public void assertRect(Rect rect, int left, int top, int right, int bottom) {
        assertEquals(left, rect.left);
        assertEquals(top, rect.top);
        assertEquals(right, rect.right);
        assertEquals(bottom, rect.bottom);
    }

    @Test
    public void testLayoutInFullscreenTaskInsets() throws Exception {
        Task task = new TaskWithBounds(null); // fullscreen task doesn't use bounds for computeFrame
        WindowState w = createWindow(task, FILL_PARENT, FILL_PARENT);
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
        w.computeFrameLw(pf, df, of, cf, vf, dcf, sf, null, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame,0, 0, 1000, 1000);
        assertRect(w.mContentInsets, 0, topContentInset, 0, bottomContentInset);
        assertRect(w.mVisibleInsets, 0, topVisibleInset, 0, bottomVisibleInset);
        assertRect(w.mStableInsets, leftStableInset, 0, rightStableInset, 0);
        // The frames remain as passed in shrunk to the window frame
        assertTrue(cf.equals(w.getContentFrameLw()));
        assertTrue(vf.equals(w.getVisibleFrameLw()));
        assertTrue(sf.equals(w.getStableFrameLw()));
        // On the other hand mFrame doesn't extend past cf we won't get any insets
        w.mAttrs.x = 100;
        w.mAttrs.y = 100;
        w.mAttrs.width = 100; w.mAttrs.height = 100; //have to clear MATCH_PARENT
        w.mRequestedWidth = 100;
        w.mRequestedHeight = 100;
        w.computeFrameLw(pf, df, of, cf, vf, dcf, sf, null, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 100, 100, 200, 200);
        assertRect(w.mContentInsets, 0, 0, 0, 0);
        // In this case the frames are shrunk to the window frame.
        assertTrue(w.mFrame.equals(w.getContentFrameLw()));
        assertTrue(w.mFrame.equals(w.getVisibleFrameLw()));
        assertTrue(w.mFrame.equals(w.getStableFrameLw()));
    }

    @Test
    public void testLayoutInFullscreenTaskNoInsets() throws Exception {
        Task task = new TaskWithBounds(null); // fullscreen task doesn't use bounds for computeFrame
        WindowState w = createWindow(task, FILL_PARENT, FILL_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        // With no insets or system decor all the frames incoming from PhoneWindowManager
        // are identical.
        final Rect pf = new Rect(0, 0, 1000, 1000);

        // Here the window has FILL_PARENT, FILL_PARENT
        // so we expect it to fill the entire available frame.
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 0, 0, 1000, 1000);

        // It can select various widths and heights within the bounds.
        // Strangely the window attribute width is ignored for normal windows
        // and we use mRequestedWidth/mRequestedHeight
        w.mAttrs.width = 300;
        w.mAttrs.height = 300;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        // Explicit width and height without requested width/height
        // gets us nothing.
        assertRect(w.mFrame, 0, 0, 0, 0);

        w.mRequestedWidth = 300;
        w.mRequestedHeight = 300;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        // With requestedWidth/Height we can freely choose our size within the
        // parent bounds.
        assertRect(w.mFrame, 0, 0, 300, 300);

        // With FLAG_SCALED though, requestedWidth/height is used to control
        // the unscaled surface size, and mAttrs.width/height becomes the
        // layout controller.
        w.mAttrs.flags = WindowManager.LayoutParams.FLAG_SCALED;
        w.mRequestedHeight = -1;
        w.mRequestedWidth = -1;
        w.mAttrs.width = 100;
        w.mAttrs.height = 100;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 0, 0, 100, 100);
        w.mAttrs.flags = 0;

        // But sizes too large will be clipped to the containing frame
        w.mRequestedWidth = 1200;
        w.mRequestedHeight = 1200;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 0, 0, 1000, 1000);

        // Before they are clipped though windows will be shifted
        w.mAttrs.x = 300;
        w.mAttrs.y = 300;
        w.mRequestedWidth = 1000;
        w.mRequestedHeight = 1000;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 0, 0, 1000, 1000);

        // If there is room to move around in the parent frame the window will be shifted according
        // to gravity.
        w.mAttrs.x = 0;
        w.mAttrs.y = 0;
        w.mRequestedWidth = 300;
        w.mRequestedHeight = 300;
        w.mAttrs.gravity = Gravity.RIGHT | Gravity.TOP;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 700, 0, 1000, 300);
        w.mAttrs.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 700, 700, 1000, 1000);
        // Window specified  x and y are interpreted as offsets in the opposite
        // direction of gravity
        w.mAttrs.x = 100;
        w.mAttrs.y = 100;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, 600, 600, 900, 900);
    }

    @Test
    public void testLayoutNonfullscreenTask() {
        final DisplayInfo displayInfo = sWm.getDefaultDisplayContentLocked().getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;

        final int taskLeft = logicalWidth / 4;
        final int taskTop = logicalHeight / 4;
        final int taskRight = logicalWidth / 4 * 3;
        final int taskBottom = logicalHeight / 4 * 3;
        final Rect taskBounds = new Rect(taskLeft, taskTop, taskRight, taskBottom);
        TaskWithBounds task = new TaskWithBounds(taskBounds);
        task.mFullscreenForTest = false;
        WindowState w = createWindow(task, FILL_PARENT, FILL_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, null, WmDisplayCutout.NO_CUTOUT, false);
        // For non fullscreen tasks the containing frame is based off the
        // task bounds not the parent frame.
        assertRect(w.mFrame, taskLeft, taskTop, taskRight, taskBottom);
        assertRect(w.getContentFrameLw(), taskLeft, taskTop, taskRight, taskBottom);
        assertRect(w.mContentInsets, 0, 0, 0, 0);

        pf.set(0, 0, logicalWidth, logicalHeight);
        // We still produce insets against the containing frame the same way.
        final int cfRight = logicalWidth / 2;
        final int cfBottom = logicalHeight / 2;
        final Rect cf = new Rect(0, 0, cfRight, cfBottom);
        w.computeFrameLw(pf, pf, pf, cf, cf, pf, cf, null, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, taskLeft, taskTop, taskRight, taskBottom);
        int contentInsetRight = taskRight - cfRight;
        int contentInsetBottom = taskBottom - cfBottom;
        assertRect(w.mContentInsets, 0, 0, contentInsetRight, contentInsetBottom);
        assertRect(w.getContentFrameLw(), taskLeft, taskTop, taskRight - contentInsetRight,
                taskBottom - contentInsetBottom);

        pf.set(0, 0, logicalWidth, logicalHeight);
        // However if we set temp inset bounds, the insets will be computed
        // as if our window was laid out there,  but it will be laid out according to
        // the task bounds.
        final int insetLeft = logicalWidth / 5;
        final int insetTop = logicalHeight / 5;
        final int insetRight = insetLeft + (taskRight - taskLeft);
        final int insetBottom = insetTop + (taskBottom - taskTop);
        task.mInsetBounds.set(insetLeft, insetTop, insetRight, insetBottom);
        w.computeFrameLw(pf, pf, pf, cf, cf, pf, cf, null, WmDisplayCutout.NO_CUTOUT, false);
        assertRect(w.mFrame, taskLeft, taskTop, taskRight, taskBottom);
        contentInsetRight = insetRight - cfRight;
        contentInsetBottom = insetBottom - cfBottom;
        assertRect(w.mContentInsets, 0, 0, contentInsetRight, contentInsetBottom);
        assertRect(w.getContentFrameLw(), taskLeft, taskTop, taskRight - contentInsetRight,
                taskBottom - contentInsetBottom);
    }

    @Test
    public void testCalculatePolicyCrop() {
        final WindowStateWithTask w = createWindow(
                new TaskWithBounds(null), FILL_PARENT, FILL_PARENT);
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
        Rect dcf = cf;

        final Rect policyCrop = new Rect();

        w.computeFrameLw(pf, df, of, cf, vf, dcf, sf, null, WmDisplayCutout.NO_CUTOUT, false);
        w.calculatePolicyCrop(policyCrop);
        assertRect(policyCrop, 0, cf.top, logicalWidth, cf.bottom);

        dcf.setEmpty();
        // Likewise with no decor frame we would get no crop
        w.computeFrameLw(pf, df, of, cf, vf, dcf, sf, null, WmDisplayCutout.NO_CUTOUT, false);
        w.calculatePolicyCrop(policyCrop);
        assertRect(policyCrop, 0, 0, logicalWidth, logicalHeight);

        // Now we set up a window which doesn't fill the entire decor frame.
        // Normally it would be cropped to it's frame but in the case of docked resizing
        // we need to account for the fact the windows surface will be made
        // fullscreen and thus also make the crop fullscreen.
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;
        w.mAttrs.width = logicalWidth / 2;
        w.mAttrs.height = logicalHeight / 2;
        w.mRequestedWidth = logicalWidth / 2;
        w.mRequestedHeight = logicalHeight / 2;
        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, WmDisplayCutout.NO_CUTOUT, false);

        w.calculatePolicyCrop(policyCrop);
        // Normally the crop is shrunk from the decor frame
        // to the computed window frame.
        assertRect(policyCrop, 0, 0, logicalWidth / 2, logicalHeight / 2);

        w.mDockedResizingForTest = true;
        w.calculatePolicyCrop(policyCrop);
        // But if we are docked resizing it won't be, however we will still be
        // shrunk to the decor frame and the display.
        assertRect(policyCrop, 0, 0,
                Math.min(pf.width(), displayInfo.logicalWidth),
                Math.min(pf.height(), displayInfo.logicalHeight));
    }

    @Test
    public void testLayoutLetterboxedWindow() {
        // First verify task behavior in multi-window mode.
        final DisplayInfo displayInfo = sWm.getDefaultDisplayContentLocked().getDisplayInfo();
        final int logicalWidth = displayInfo.logicalWidth;
        final int logicalHeight = displayInfo.logicalHeight;

        final int taskLeft = logicalWidth / 5;
        final int taskTop = logicalHeight / 5;
        final int taskRight = logicalWidth / 4 * 3;
        final int taskBottom = logicalHeight / 4 * 3;
        final Rect taskBounds = new Rect(taskLeft, taskTop, taskRight, taskBottom);
        TaskWithBounds task = new TaskWithBounds(taskBounds);
        task.mInsetBounds.set(taskLeft, taskTop, taskRight, taskBottom);
        task.mFullscreenForTest = false;
        WindowState w = createWindow(task, FILL_PARENT, FILL_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, logicalWidth, logicalHeight);
        w.computeFrameLw(pf /* parentFrame */, pf /* displayFrame */, pf /* overscanFrame */,
                pf /* contentFrame */, pf /* visibleFrame */, pf /* decorFrame */,
                pf /* stableFrame */, null /* outsetFrame */, WmDisplayCutout.NO_CUTOUT, false);
        // For non fullscreen tasks the containing frame is based off the
        // task bounds not the parent frame.
        assertRect(w.mFrame, taskLeft, taskTop, taskRight, taskBottom);
        assertRect(w.getContentFrameLw(), taskLeft, taskTop, taskRight, taskBottom);
        assertRect(w.mContentInsets, 0, 0, 0, 0);

        // Now simulate switch to fullscreen for letterboxed app.
        final int xInset = logicalWidth / 10;
        final int yInset = logicalWidth / 10;
        final Rect cf = new Rect(xInset, yInset, logicalWidth - xInset, logicalHeight - yInset);
        Configuration config = new Configuration(w.mAppToken.getOverrideConfiguration());
        config.windowConfiguration.setBounds(cf);
        w.mAppToken.onOverrideConfigurationChanged(config);
        pf.set(0, 0, logicalWidth, logicalHeight);
        task.mFullscreenForTest = true;

        w.computeFrameLw(pf /* parentFrame */, pf /* displayFrame */, pf /* overscanFrame */,
                cf /* contentFrame */, cf /* visibleFrame */, pf /* decorFrame */,
                cf /* stableFrame */, null /* outsetFrame */, WmDisplayCutout.NO_CUTOUT, false);
        assertEquals(cf, w.mFrame);
        assertEquals(cf, w.getContentFrameLw());
        assertRect(w.mContentInsets, 0, 0, 0, 0);
    }

    @Test
    public void testDisplayCutout() {
        // Regular fullscreen task and window
        Task task = new TaskWithBounds(null);
        WindowState w = createWindow(task, FILL_PARENT, FILL_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, 0, 1000, 2000);
        // Create a display cutout of size 50x50, aligned top-center
        final WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(500, 0, 550, 50), pf.width(), pf.height());

        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, cutout, false);

        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetTop(), 50);
        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetBottom(), 0);
        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetLeft(), 0);
        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetRight(), 0);
    }

    @Test
    public void testDisplayCutout_tempInsetBounds() {
        // Regular fullscreen task and window
        TaskWithBounds task = new TaskWithBounds(new Rect(0, -500, 1000, 1500));
        task.mFullscreenForTest = false;
        task.mInsetBounds.set(0, 0, 1000, 2000);
        WindowState w = createWindow(task, FILL_PARENT, FILL_PARENT);
        w.mAttrs.gravity = Gravity.LEFT | Gravity.TOP;

        final Rect pf = new Rect(0, -500, 1000, 1500);
        // Create a display cutout of size 50x50, aligned top-center
        final WmDisplayCutout cutout = WmDisplayCutout.computeSafeInsets(
                fromBoundingRect(500, 0, 550, 50), pf.width(), pf.height());

        w.computeFrameLw(pf, pf, pf, pf, pf, pf, pf, pf, cutout, false);

        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetTop(), 50);
        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetBottom(), 0);
        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetLeft(), 0);
        assertEquals(w.mDisplayCutout.getDisplayCutout().getSafeInsetRight(), 0);
    }

    private WindowStateWithTask createWindow(Task task, int width, int height) {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(TYPE_APPLICATION);
        attrs.width = width;
        attrs.height = height;

        return new WindowStateWithTask(attrs, task);
    }

}
