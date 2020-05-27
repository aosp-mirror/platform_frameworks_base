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

package android.view;

import static android.view.InsetsState.ISIDE_BOTTOM;
import static android.view.InsetsState.ISIDE_TOP;
import static android.view.InsetsState.ITYPE_BOTTOM_GESTURES;
import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_CLIMATE_BAR;
import static android.view.InsetsState.ITYPE_EXTRA_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;
import android.view.test.InsetsModeSession;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link InsetsState}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsStateTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsStateTest {

    private InsetsState mState = new InsetsState();
    private InsetsState mState2 = new InsetsState();

    @Test
    public void testCalculateInsets() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
            mState.getSource(ITYPE_IME).setVisible(true);
            SparseIntArray typeSideMap = new SparseIntArray();
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, SOFT_INPUT_ADJUST_RESIZE, 0, typeSideMap);
            assertEquals(Insets.of(0, 100, 0, 100), insets.getSystemWindowInsets());
            assertEquals(Insets.of(0, 100, 0, 100), insets.getInsets(Type.all()));
            assertEquals(ISIDE_TOP, typeSideMap.get(ITYPE_STATUS_BAR));
            assertEquals(ISIDE_BOTTOM, typeSideMap.get(ITYPE_IME));
            assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.statusBars()));
            assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(Type.ime()));
        }
    }

    @Test
    public void testCalculateInsets_imeAndNav() throws Exception{
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(0, 200, 100, 300));
            mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);
            mState.getSource(ITYPE_IME).setFrame(new Rect(0, 100, 100, 300));
            mState.getSource(ITYPE_IME).setVisible(true);
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, SOFT_INPUT_ADJUST_RESIZE, 0, null);
            assertEquals(100, insets.getStableInsetBottom());
            assertEquals(Insets.of(0, 0, 0, 100), insets.getInsetsIgnoringVisibility(Type.systemBars()));
            assertEquals(Insets.of(0, 0, 0, 200), insets.getSystemWindowInsets());
            assertEquals(Insets.of(0, 0, 0, 200), insets.getInsets(Type.all()));
            assertEquals(Insets.of(0, 0, 0, 100), insets.getInsets(Type.navigationBars()));
            assertEquals(Insets.of(0, 0, 0, 200), insets.getInsets(Type.ime()));
        }
    }

    @Test
    public void testCalculateInsets_navRightStatusTop() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
            mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, 0, 0, null);
            assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
            assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.statusBars()));
            assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(Type.navigationBars()));
        }
    }

    @Test
    public void testCalculateInsets_imeIgnoredWithoutAdjustResize() {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
            mState.getSource(ITYPE_IME).setVisible(true);
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, SOFT_INPUT_ADJUST_NOTHING, 0, null);
            assertEquals(0, insets.getSystemWindowInsetBottom());
            assertEquals(100, insets.getInsets(ime()).bottom);
            assertTrue(insets.isVisible(ime()));
        }
    }

    @Test
    public void testCalculateInsets_systemUiFlagLayoutStable() {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(false);
            mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
            mState.getSource(ITYPE_IME).setVisible(true);
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, SOFT_INPUT_ADJUST_NOTHING,
                    SYSTEM_UI_FLAG_LAYOUT_STABLE, null);
            assertEquals(100, insets.getSystemWindowInsetTop());
            insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false, false,
                    DisplayCutout.NO_CUTOUT, SOFT_INPUT_ADJUST_NOTHING,
                    0 /* legacySystemUiFlags */, null);
            assertEquals(0, insets.getSystemWindowInsetTop());
        }
    }


    @Test
    public void testCalculateInsets_captionStatusBarOverlap() throws Exception {
        try (InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_CAPTION_BAR).setFrame(new Rect(0, 0, 100, 300));
            mState.getSource(ITYPE_CAPTION_BAR).setVisible(true);

            Rect visibleInsets = mState.calculateVisibleInsets(
                    new Rect(0, 0, 100, 400), SOFT_INPUT_ADJUST_NOTHING);
            assertEquals(new Rect(0, 300, 0, 0), visibleInsets);
        }
    }

    @Test
    public void testCalculateInsets_captionBarOffset() throws Exception {
        try (InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_CAPTION_BAR).setFrame(new Rect(0, 0, 100, 300));
            mState.getSource(ITYPE_CAPTION_BAR).setVisible(true);

            Rect visibleInsets = mState.calculateVisibleInsets(
                    new Rect(0, 0, 150, 400), SOFT_INPUT_ADJUST_NOTHING);
            assertEquals(new Rect(0, 300, 0, 0), visibleInsets);
        }
    }

    @Test
    public void testCalculateInsets_extraNavRightStatusTop() throws Exception {
        try (InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
            mState.getSource(ITYPE_EXTRA_NAVIGATION_BAR).setVisible(true);
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, 0, 0, null);
            assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
            assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.statusBars()));
            assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(Type.navigationBars()));
        }
    }

    @Test
    public void testCalculateInsets_navigationRightClimateTop() throws Exception {
        try (InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_CLIMATE_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_CLIMATE_BAR).setVisible(true);
            mState.getSource(ITYPE_NAVIGATION_BAR).setFrame(new Rect(80, 0, 100, 300));
            mState.getSource(ITYPE_NAVIGATION_BAR).setVisible(true);
            WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false,
                    false, DisplayCutout.NO_CUTOUT, 0, 0, null);
            assertEquals(Insets.of(0, 100, 20, 0), insets.getSystemWindowInsets());
            assertEquals(Insets.of(0, 100, 0, 0), insets.getInsets(Type.statusBars()));
            assertEquals(Insets.of(0, 0, 20, 0), insets.getInsets(Type.navigationBars()));
        }
    }

    @Test
    public void testStripForDispatch() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.removeSource(ITYPE_IME);
        WindowInsets insets = mState.calculateInsets(new Rect(0, 0, 100, 300), null, false, false,
                DisplayCutout.NO_CUTOUT, SOFT_INPUT_ADJUST_RESIZE, 0, null);
        assertEquals(0, insets.getSystemWindowInsetBottom());
    }

    @Test
    public void testEquals_differentRect() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 10, 10));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentSource() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_sameButDifferentInsertOrder() {
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState2.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        assertEqualsAndHashCode();
    }

    @Test
    public void testEquals_visibility() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState2.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_differentFrame() {
        mState.setDisplayFrame(new Rect(0, 1, 2, 3));
        mState.setDisplayFrame(new Rect(4, 5, 6, 7));
        assertNotEqualsAndHashCode();
    }

    @Test
    public void testEquals_sameFrame() {
        mState.setDisplayFrame(new Rect(0, 1, 2, 3));
        mState2.setDisplayFrame(new Rect(0, 1, 2, 3));
        assertEqualsAndHashCode();
    }

    @Test
    public void testParcelUnparcel() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisibleFrame(new Rect(0, 0, 50, 10));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        Parcel p = Parcel.obtain();
        mState.writeToParcel(p, 0 /* flags */);
        p.setDataPosition(0);
        mState2.readFromParcel(p);
        p.recycle();
        assertEquals(mState, mState2);
    }

    @Test
    public void testCopy() {
        mState.getSource(ITYPE_IME).setFrame(new Rect(0, 0, 100, 100));
        mState.getSource(ITYPE_IME).setVisibleFrame(new Rect(0, 0, 50, 10));
        mState.getSource(ITYPE_IME).setVisible(true);
        mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
        mState2.set(mState, true);
        assertEquals(mState, mState2);
    }

    @Test
    public void testGetDefaultVisibility() {
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_STATUS_BAR));
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_NAVIGATION_BAR));
        assertTrue(InsetsState.getDefaultVisibility(ITYPE_CAPTION_BAR));
        assertFalse(InsetsState.getDefaultVisibility(ITYPE_IME));
    }

    @Test
    public void testCalculateVisibleInsets() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
            mState.getSource(ITYPE_IME).setVisible(true);

            // Make sure bottom gestures are ignored
            mState.getSource(ITYPE_BOTTOM_GESTURES).setFrame(new Rect(0, 100, 100, 300));
            mState.getSource(ITYPE_BOTTOM_GESTURES).setVisible(true);
            Rect visibleInsets = mState.calculateVisibleInsets(
                    new Rect(0, 0, 100, 300), SOFT_INPUT_ADJUST_PAN);
            assertEquals(new Rect(0, 100, 0, 100), visibleInsets);
        }
    }

    @Test
    public void testCalculateVisibleInsets_adjustNothing() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_FULL)) {
            mState.getSource(ITYPE_STATUS_BAR).setFrame(new Rect(0, 0, 100, 100));
            mState.getSource(ITYPE_STATUS_BAR).setVisible(true);
            mState.getSource(ITYPE_IME).setFrame(new Rect(0, 200, 100, 300));
            mState.getSource(ITYPE_IME).setVisible(true);

            // Make sure bottom gestures are ignored
            mState.getSource(ITYPE_BOTTOM_GESTURES).setFrame(new Rect(0, 100, 100, 300));
            mState.getSource(ITYPE_BOTTOM_GESTURES).setVisible(true);
            Rect visibleInsets = mState.calculateVisibleInsets(
                    new Rect(0, 0, 100, 300), SOFT_INPUT_ADJUST_NOTHING);
            assertEquals(new Rect(0, 100, 0, 0), visibleInsets);
        }
    }

    private void assertEqualsAndHashCode() {
        assertEquals(mState, mState2);
        assertEquals(mState.hashCode(), mState2.hashCode());
    }

    private void assertNotEqualsAndHashCode() {
        assertNotEquals(mState, mState2);
        assertNotEquals(mState.hashCode(), mState2.hashCode());
    }
}
