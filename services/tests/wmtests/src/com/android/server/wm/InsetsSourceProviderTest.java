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

import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.InsetsState;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class InsetsSourceProviderTest extends WindowTestsBase {

    private InsetsSource mSource = new InsetsSource(ITYPE_STATUS_BAR);
    private InsetsSourceProvider mProvider;
    private InsetsSource mImeSource = new InsetsSource(ITYPE_IME);
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
        statusBar.getFrame().set(0, 0, 500, 100);
        statusBar.mHasSurface = true;
        mProvider.setWindow(statusBar, null, null);
        mProvider.onPostLayout();
        assertEquals(new Rect(0, 0, 500, 100), mProvider.getSource().getFrame());
        assertEquals(Insets.of(0, 100, 0, 0),
                mProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));
        assertEquals(Insets.of(0, 100, 0, 0),
                mProvider.getSource().calculateVisibleInsets(new Rect(0, 0, 500, 500)));
    }

    @Test
    public void testPostLayout_givenInsets() {
        final WindowState ime = createWindow(null, TYPE_APPLICATION, "ime");
        ime.getFrame().set(0, 0, 500, 100);
        ime.mGivenContentInsets.set(0, 0, 0, 60);
        ime.mGivenVisibleInsets.set(0, 0, 0, 75);
        ime.mHasSurface = true;
        mProvider.setWindow(ime, null, null);
        mProvider.onPostLayout();
        assertEquals(new Rect(0, 0, 500, 40), mProvider.getSource().getFrame());
        assertEquals(new Rect(0, 0, 500, 25), mProvider.getSource().getVisibleFrame());
        assertEquals(Insets.of(0, 40, 0, 0),
                mProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));
        assertEquals(Insets.of(0, 25, 0, 0),
                mProvider.getSource().calculateVisibleInsets(new Rect(0, 0, 500, 500)));
    }

    @Test
    public void testPostLayout_invisible() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindow(statusBar, null, null);
        mProvider.onPostLayout();
        assertEquals(Insets.NONE, mProvider.getSource().calculateInsets(new Rect(0, 0, 500, 500),
                        false /* ignoreVisibility */));
    }

    @Test
    public void testPostLayout_frameProvider() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.getFrame().set(0, 0, 500, 100);
        statusBar.mHasSurface = true;
        mProvider.setWindow(statusBar,
                (displayFrames, windowState, rect) -> {
                    rect.set(10, 10, 20, 20);
                }, null);
        mProvider.onPostLayout();
        assertEquals(new Rect(10, 10, 20, 20), mProvider.getSource().getFrame());
    }

    @Test
    public void testUpdateControlForTarget() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);

        // We must not have control or control target before we have the insets source window.
        mProvider.updateControlForTarget(target, true /* force */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());

        // We can have the control or the control target after we have the insets source window.
        mProvider.setWindow(statusBar, null, null);
        mProvider.updateControlForTarget(target, false /* force */);
        assertNotNull(mProvider.getControl(target));
        assertNotNull(mProvider.getControlTarget());

        // We must not have control or control target while we are performing seamless rotation.
        // And the control and the control target must not be updated during that.
        mProvider.startSeamlessRotation();
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());
        mProvider.updateControlForTarget(target, true /* force */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());

        // We can have the control and the control target after seamless rotation.
        mProvider.finishSeamlessRotation();
        mProvider.updateControlForTarget(target, false /* force */);
        assertNotNull(mProvider.getControl(target));
        assertNotNull(mProvider.getControlTarget());

        // We can clear the control and the control target.
        mProvider.updateControlForTarget(null, false /* force */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());

        // We must not have control or control target if the insets source window doesn't have a
        // surface.
        statusBar.setSurfaceControl(null);
        mProvider.updateControlForTarget(target, true /* force */);
        assertNull(mProvider.getControl(target));
        assertNull(mProvider.getControlTarget());
    }

    @Test
    public void testUpdateControlForFakeTarget() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindow(statusBar, null, null);
        mProvider.updateControlForFakeTarget(target);
        assertNotNull(mProvider.getControl(target));
        assertNull(mProvider.getControl(target).getLeash());
        mProvider.updateControlForFakeTarget(null);
        assertNull(mProvider.getControl(target));
    }

    @Test
    public void testUpdateSourceFrameForIme() {
        final WindowState inputMethod = createWindow(null, TYPE_INPUT_METHOD, "inputMethod");

        inputMethod.getFrame().set(new Rect(0, 400, 500, 500));

        mImeProvider.setWindow(inputMethod, null, null);
        mImeProvider.setServerVisible(false);
        mImeSource.setVisible(true);
        mImeProvider.updateSourceFrame();
        assertEquals(new Rect(0, 0, 0, 0), mImeSource.getFrame());
        Insets insets = mImeSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 0), insets);

        mImeProvider.setServerVisible(true);
        mImeSource.setVisible(true);
        mImeProvider.updateSourceFrame();
        assertEquals(inputMethod.getFrame(), mImeSource.getFrame());
        insets = mImeSource.calculateInsets(new Rect(0, 0, 500, 500),
                false /* ignoreVisibility */);
        assertEquals(Insets.of(0, 0, 0, 100), insets);
    }

    @Test
    public void testInsetsModified() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindow(statusBar, null, null);
        mProvider.updateControlForTarget(target, false /* force */);
        InsetsState state = new InsetsState();
        state.getSource(ITYPE_STATUS_BAR).setVisible(false);
        target.updateRequestedVisibility(state);
        mProvider.updateClientVisibility(target);
        assertFalse(mSource.isVisible());
    }

    @Test
    public void testInsetsModified_noControl() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        final WindowState target = createWindow(null, TYPE_APPLICATION, "target");
        statusBar.getFrame().set(0, 0, 500, 100);
        mProvider.setWindow(statusBar, null, null);
        InsetsState state = new InsetsState();
        state.getSource(ITYPE_STATUS_BAR).setVisible(false);
        target.updateRequestedVisibility(state);
        mProvider.updateClientVisibility(target);
        assertTrue(mSource.isVisible());
    }

    @Test
    public void testInsetGeometries() {
        final WindowState statusBar = createWindow(null, TYPE_APPLICATION, "statusBar");
        statusBar.getFrame().set(0, 0, 500, 100);
        statusBar.mHasSurface = true;
        mProvider.setWindow(statusBar, null, null);
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
