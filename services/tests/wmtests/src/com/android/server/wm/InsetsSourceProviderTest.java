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

import static android.view.InsetsSource.ID_IME;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class InsetsSourceProviderTest extends WindowTestsBase {

    private InsetsSource mSource = new InsetsSource(
            InsetsSource.createId(null, 0, statusBars()), statusBars());
    private InsetsSourceProvider mProvider;
    private InsetsSource mImeSource = new InsetsSource(ID_IME, ime());
    private InsetsSourceProvider mImeProvider;

    @Before
    public void setUp() throws Exception {
        mSource.setVisible(true);
        mProvider = new InsetsSourceProvider(mSource,
                mDisplayContent.getInsetsStateController(), mDisplayContent);
        mImeProvider = new InsetsSourceProvider(mImeSource,
                mDisplayContent.getInsetsStateController(), mDisplayContent);
    }

    @Test
    public void testPostLayout() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.setBounds(0, 0, 500, 1000);
        statusBar.getFrame().set(0, 0, 500, 100);
        statusBar.mHasSurface = true;
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateSourceFrame(statusBar.getFrame());
        mProvider.onPostLayout();
        assertEquals(new Rect(0, 0, 500, 100), mProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 100, 0, 0), mProvider.getInsetsHint());

        // Change the bounds and call onPostLayout. Make sure the insets hint gets updated.
        statusBar.setBounds(0, 10, 500, 1000);
        mProvider.onPostLayout();
        assertEquals(Insets.of(0, 90, 0, 0), mProvider.getInsetsHint());
    }

    @Test
    public void testPostLayout_invisible() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.setBounds(0, 0, 500, 1000);
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateSourceFrame(statusBar.getFrame());
        mProvider.onPostLayout();
        assertTrue(mProvider.getSource().getFrame().isEmpty());
        assertEquals(Insets.NONE, mProvider.getInsetsHint());
    }

    @Test
    public void testPostLayout_frameProvider() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.getFrame().set(0, 0, 500, 100);
        statusBar.mHasSurface = true;
        mProvider.setWindowContainer(statusBar,
                (displayFrames, windowState, rect) -> {
                    rect.set(10, 10, 20, 20);
                    return 0;
                }, null);
        mProvider.updateSourceFrame(statusBar.getFrame());
        mProvider.onPostLayout();
        assertEquals(new Rect(10, 10, 20, 20), mProvider.getSource().getFrame());
    }

    @Test
    public void testUpdateControlForTarget() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);

        // We must not have control or control target before we have the insets source window.
        mProvider.updateControlForTarget(target, true /* force */, null /* statsToken */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());

        // We can have the control or the control target after we have the insets source window.
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateControlForTarget(target, false /* force */, null /* statsToken */);
        assertNotNull(mProvider.getControl(target));
        assertNotNull(mProvider.getControlTarget());

        // We must not have control or control target while we are performing seamless rotation.
        // And the control and the control target must not be updated during that.
        mProvider.startSeamlessRotation();
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());
        mProvider.updateControlForTarget(target, true /* force */, null /* statsToken */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());

        // We can have the control and the control target after seamless rotation.
        mProvider.finishSeamlessRotation();
        mProvider.updateControlForTarget(target, false /* force */, null /* statsToken */);
        assertNotNull(mProvider.getControl(target));
        assertNotNull(mProvider.getControlTarget());

        // We can clear the control and the control target.
        mProvider.updateControlForTarget(null, false /* force */, null /* statsToken */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());

        // We must not have control or control target if the insets source window doesn't have a
        // surface.
        statusBar.setSurfaceControl(null);
        mProvider.updateControlForTarget(target, true /* force */, null /* statsToken */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());
    }

    @Test
    public void testUpdateControlForFakeTarget() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateFakeControlTarget(target);
        assertNotNull(mProvider.getControl(target));
        assertNull(mProvider.getControl(target).getLeash());
        mProvider.updateFakeControlTarget(null);
        assertNull(mProvider.getControl(target));
    }

    @Test
    public void testGetLeash() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        final WindowState fakeTarget = createWindow(null, TYPE_APPLICATION, "fakeTarget");
        final WindowState otherTarget = createWindow(null, TYPE_APPLICATION, "otherTarget");
        statusBar.getFrame().set(0, 0, 500, 100);

        // We must not have control or control target before we have the insets source window,
        // so also no leash.
        mProvider.updateControlForTarget(target, true /* force */, null /* statsToken */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());
        assertNull(mProvider.getLeash(target));

        // We can have the control or the control target after we have the insets source window,
        // but no leash as this is not yet ready for dispatching.
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateControlForTarget(target, false /* force */, null /* statsToken */);
        assertNotNull(mProvider.getControl(target));
        assertNotNull(mProvider.getControlTarget());
        assertEquals(mProvider.getControlTarget(), target);
        assertNull(mProvider.getLeash(target));

        // Set the leash to be ready for dispatching.
        mProvider.mIsLeashReadyForDispatching = true;
        assertNotNull(mProvider.getLeash(target));

        // We do have fake control for the fake control target, but that has no leash.
        mProvider.updateFakeControlTarget(fakeTarget);
        assertNotNull(mProvider.getControl(fakeTarget));
        assertNotNull(mProvider.getFakeControlTarget());
        assertNotEquals(mProvider.getControlTarget(), fakeTarget);
        assertNull(mProvider.getLeash(fakeTarget));

        // We don't have any control for a different (non-fake control target), so also no leash.
        assertNull(mProvider.getControl(otherTarget));
        assertNotNull(mProvider.getControlTarget());
        assertNotEquals(mProvider.getControlTarget(), otherTarget);
        assertNull(mProvider.getLeash(otherTarget));
    }

    @Test
    public void testUpdateSourceFrame() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        mProvider.setWindowContainer(statusBar, null, null);
        statusBar.setBounds(0, 0, 500, 1000);

        mProvider.setServerVisible(true);
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.updateSourceFrame(statusBar.getFrame());
        assertEquals(statusBar.getFrame(), mProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 100, 0, 0), mProvider.getInsetsHint());

        // Only change the source frame but not the visibility.
        statusBar.getFrame().set(0, 0, 500, 90);
        mProvider.updateSourceFrame(statusBar.getFrame());
        assertEquals(statusBar.getFrame(), mProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 90, 0, 0), mProvider.getInsetsHint());

        mProvider.setServerVisible(false);
        statusBar.getFrame().set(0, 0, 500, 80);
        mProvider.updateSourceFrame(statusBar.getFrame());
        assertTrue(mProvider.getSource().getFrame().isEmpty());
        assertEquals(Insets.of(0, 90, 0, 0), mProvider.getInsetsHint());

        // Only change the visibility but not the frame.
        mProvider.setServerVisible(true);
        assertEquals(statusBar.getFrame(), mProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 80, 0, 0), mProvider.getInsetsHint());
    }

    @Test
    public void testUpdateSourceFrameForIme() {
        final WindowState inputMethod = createWindow(null, TYPE_INPUT_METHOD, "inputMethod");

        inputMethod.getFrame().set(new Rect(0, 400, 500, 500));

        mImeProvider.setWindowContainer(inputMethod, null, null);
        mImeProvider.setServerVisible(false);
        mImeSource.setVisible(true);
        mImeProvider.updateSourceFrame(inputMethod.getFrame());
        assertEquals(new Rect(0, 0, 0, 0), mImeSource.getFrame());
        Insets insets = mImeSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 0), insets);

        mImeProvider.setServerVisible(true);
        mImeSource.setVisible(true);
        mImeProvider.updateSourceFrame(inputMethod.getFrame());
        assertEquals(inputMethod.getFrame(), mImeSource.getFrame());
        insets = mImeSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 100), insets);
    }

    @Test
    public void testUpdateInsetsControlPosition() {
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");

        final WindowState ime1 = createWindow(null, TYPE_INPUT_METHOD, "ime1");
        ime1.getFrame().set(new Rect(0, 0, 0, 0));
        mImeProvider.setWindowContainer(ime1, null, null);
        mImeProvider.updateControlForTarget(target, false /* force */, null /* statsToken */);
        ime1.getFrame().set(new Rect(0, 400, 500, 500));
        mImeProvider.updateInsetsControlPosition(ime1);
        assertEquals(new Point(0, 400), mImeProvider.getControl(target).getSurfacePosition());

        final WindowState ime2 = createWindow(null, TYPE_INPUT_METHOD, "ime2");
        ime2.getFrame().set(new Rect(0, 0, 0, 0));
        mImeProvider.setWindowContainer(ime2, null, null);
        mImeProvider.updateControlForTarget(target, false /* force */, null /* statsToken */);
        ime2.getFrame().set(new Rect(0, 400, 500, 500));
        mImeProvider.updateInsetsControlPosition(ime2);
        assertEquals(new Point(0, 400), mImeProvider.getControl(target).getSurfacePosition());
    }

    @Test
    public void testSetRequestedVisibleTypes() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateControlForTarget(target, false /* force */, null /* statsToken */);
        target.setRequestedVisibleTypes(0, statusBars());
        mProvider.updateClientVisibility(target, null /* statsToken */);
        assertFalse(mSource.isVisible());
    }

    @Test
    public void testSetRequestedVisibleTypes_noControl() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindowContainer(statusBar, null, null);
        target.setRequestedVisibleTypes(0, statusBars());
        mProvider.updateClientVisibility(target, null /* statsToken */);
        assertTrue(mSource.isVisible());
    }

    @Test
    public void testInsetGeometries() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.getFrame().set(0, 0, 500, 100);
        statusBar.mHasSurface = true;
        mProvider.setWindowContainer(statusBar, null, null);
        mProvider.updateSourceFrame(statusBar.getFrame());
        mProvider.onPostLayout();
        assertEquals(new Rect(0, 0, 500, 100), mProvider.getSource().getFrame());
        // Still apply top insets if window overlaps even if it's top doesn't exactly match
        // the inset-window's top.
        assertEquals(Insets.of(0, 100, 0, 0),
                mProvider.getSource().calculateInsets(new Rect(0, -100, 500, 400),
                        false /* ignoreVisibility */));

        // Don't apply left insets if window is left-of inset-window but still overlaps
        statusBar.getFrame().set(100, 0, 0, 0);
        assertEquals(Insets.of(0, 0, 0, 0),
                mProvider.getSource().calculateInsets(new Rect(-100, 0, 400, 500),
                        false /* ignoreVisibility */));
    }
}
